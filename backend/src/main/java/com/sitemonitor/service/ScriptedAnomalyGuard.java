package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sentetik izleme ANOMALİ guard'ı (L3) — koşum sonrası, kaydedilmiş sonuç üzerinden.
 *
 * <p><b>Neden var.</b> Kaydetme anındaki statik analiz (L1) bir döngünün kaç tur döneceğini
 * kanıtlayamaz; çalışma anındaki sert tavanlar (L2) yükü sınırlar ama bitmeyen bir izlemeyi
 * durdurmaz. 2026-08'de sahada bir monitör <b>289 koşumun 289'unda</b> zaman aşımına düştü ve
 * sistem bunu 289 kez fark etmeden tekrarladı: her tur bir k6 alt süreci, bir havuz permit'i ve
 * 180 saniyelik CPU/soket işgali demekti. Bu sınıf o döngüyü kırar.
 *
 * <p><b>İki tetik.</b>
 * <ul>
 *   <li><b>Ağır ihlal → ANINDA.</b> Koşumda atılan istek sayısı tavanı aşarsa tek koşum yeter.
 *       Bu bir atak imzasıdır; "bir kez daha görelim" demenin bedeli üretim sistemine bir tur
 *       daha yüktür.</li>
 *   <li><b>Ardışık zaman aşımı → EŞİKTE.</b> Geçici ağ dalgalanması bir-iki koşumu vurur;
 *       kalıcı sorun hepsini. Eşik ayarlanabilir (varsayılan 5).</li>
 * </ul>
 *
 * <p><b>Gerçek kesintiye dokunulmaz (k6r B2, 2026-10-31).</b> Hedef uzun süre asılı kalıyorsa (yavaş / cevapsız uç) her koşum
 * da zaman aşımına düşer; guard bunu "bitmeyen betik" sanıp izlemeyi kapatır, açık kesinti alarmını kapatır ve nöbetçiye
 * "DÜZELDİ" push'u atardı — kesinti sürerken izleme körleşirdi. İzlemenin AÇIK bir {@code SCRIPTED_FAIL} alarmı varsa zaman
 * aşımı serisi izlemeyi KAPATMAZ, alarma dokunmaz, bildirim göndermez (yalnız INFO, izleme başına saatte bir). Alarm
 * okunamazsa da kapatılmaz (bilinmeyen = dokunma). İstek tavanı aşımı tetikleyicisi değişmez.
 *
 * <p><b>Teyit zinciri sürerken (k6r inceleme #1).</b> Alarm teyit zincirinin SONUNDA açılır; zincirin yeniden kontrolleri
 * ({@code scripted_checks.recheck = true}) seriye SAYILMAZ ve bir yeniden kontrol sonucu seriyi değerlendirmez — yoksa
 * {@code confirm-attempts} ≥ 4'te alarm açılmadan seri dolar, izleme kapanır ve alarm hiç açılmazdı. SCRIPTED_FAIL için süren
 * bir toplu kesinti bastırması ({@code network_outage_events} ONGOING) varsa da dokunulmaz: bastırma sırasında alarm açılmaz
 * ama kesinti gerçektir.
 *
 * <p><b>Kapatma SEBEBİYLE birlikte yazılır.</b> {@code active=false} tek başına kullanıcının
 * kendi kapattığı izlemeden ayırt edilemezdi; {@code disabledReason} ekranda kalıcı uyarı olarak
 * durur ve kullanıcı izlemeyi yeniden açtığında temizlenir.
 *
 * <p><b>Açık alarm kapatılır.</b> Kapatılan izleme artık sweep üretmez; açık bir
 * {@code SCRIPTED_FAIL} alarmı hiçbir zaman kendiliğinden çözülemez ve sonsuza kadar asılı
 * kalırdı (silme yolundaki {@code resolveOpenAlertsSilently} ile aynı gerekçe).
 *
 * <p>Tüm yol SESSİZ hata toleranslıdır: buradaki bir istisna kontrol akışını ya da sonuç
 * kaydını bozmamalı — guard bir güvenlik ağıdır, tek başına bir bağımlılık değil.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptedAnomalyGuard {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    /** Kapatmayı yapan "kim" — denetim kaydında insan aktörden ayrılsın. */
    public static final String ACTOR = "Sistem (anomali guard)";

    private final ScriptedMonitorRepository monitorRepo;
    private final ScriptedCheckRepository checkRepo;
    private final ScriptedCheckerService scriptedChecker;
    private final ActivityLogService activityLog;
    private final EscalationService escalationService;
    private final EmailNotificationService emailService;
    private final UserPushService userPushService;
    private final AppSettingsService appSettings;

    /** Açık {@code SCRIPTED_FAIL} alarmını okumak için (k6r B2). Yoksa (elle kurulan testler) eski davranış: seri kapatır. */
    private AlertEventRepository alertEventRepo;

    @Autowired(required = false)
    void setAlertEventRepository(AlertEventRepository repo) {
        this.alertEventRepo = repo;
    }

    /** SCRIPTED_FAIL için süren toplu kesinti bastırmasını okumak için (k6r inceleme #1). Yoksa bu denetim atlanır. */
    private NetworkOutageEventRepository outageEventRepo;

    @Autowired(required = false)
    void setNetworkOutageEventRepository(NetworkOutageEventRepository repo) {
        this.outageEventRepo = repo;
    }

    /** "Açık kesinti var — kapatılmadı" satırının izleme başına en sık yazılma aralığı (ms). */
    static final long OPEN_OUTAGE_LOG_EVERY_MS = 3_600_000L;
    /** İzleme kimliği → son "kapatılmadı" günlük satırının anı. Sınırlı: tavanı aşınca temizlenir. */
    private final ConcurrentHashMap<Long, Long> openOutageLoggedAt = new ConcurrentHashMap<>();
    private static final int OPEN_OUTAGE_LOG_MAX_KEYS = 10_000;

    /** Kaç ardışık zaman aşımından sonra izleme kapatılır. ≤0 ⇒ bu tetik kapalı. */
    int timeoutStreakLimit() {
        return appSettings.getInt("site.monitor.scripted.anomaly.timeout-streak", 5);
    }

    /** Guard tamamen kapatılabilsin (acil kaçış kapısı) — varsayılan AÇIK. */
    boolean enabled() {
        return appSettings.getBoolean("site.monitor.scripted.anomaly.enabled", true);
    }

    /**
     * Bir koşum kaydedildikten SONRA çağrılır. Gerekirse izlemeyi kapatır.
     *
     * @param m   koşan monitör
     * @param res koşum sonucu (kaydı zaten yazılmış)
     * @return kapatıldıysa sebep, aksi halde null (çağıran isterse kullanıcıya iletir)
     */
    public String evaluate(ScriptedMonitor m, ScriptedCheckerService.ScriptedResult res) {
        return evaluate(m, res, false);
    }

    /**
     * @param manual ELLE koşum mu ("Şimdi kontrol et"). Ürün kararı 2026-09-29: elle kontrol yalnız gözlemdir — izlemeyi
     *               KAPATMAZ ve takıma KRİTİK push/e-posta ATMAZ. Toplu elle kontrolde k6 havuzu (varsayılan 2 permit)
     *               sıkışır ve koşumlar zaman aşımına düşebilir; bunlar guard'ı tetikleyip sağlıklı izlemeyi kapatıyordu.
     *               Guard yalnız zamanlanmış koşumlarda çalışır ve seriyi yalnız zamanlanmış kayıtlardan sayar.
     */
    public String evaluate(ScriptedMonitor m, ScriptedCheckerService.ScriptedResult res, boolean manual) {
        return evaluate(m, res, manual, false);
    }

    /**
     * @param recheck teyit / kurtarma zincirinin YENİDEN KONTROLÜ mü (k6r inceleme #1): istek tavanı aşımı yine kapatır (koşum
     *                yükü gerçek), zaman aşımı serisi DEĞERLENDİRİLMEZ — seri yalnız zamanlanmış koşumlardan ve yalnız
     *                zamanlanmış bir koşumun ardından sayılır.
     */
    public String evaluate(ScriptedMonitor m, ScriptedCheckerService.ScriptedResult res, boolean manual, boolean recheck) {
        if (manual) return null;
        if (!enabled() || m == null || m.getId() == null || res == null) return null;
        // Zaten kapalıysa tekrar kapatma: aynı e-posta her manuel tetikte yeniden giderdi.
        if (Boolean.FALSE.equals(m.getActive())) return null;
        try {
            String reason = severeViolation(res);
            if (reason == null && !recheck) reason = timeoutStreak(m, res);
            if (reason == null) return null;
            return disable(m, reason) ? reason : null;
        } catch (Exception e) {
            log.warn("Anomali guard'ı çalıştırılamadı ({}): {}", m.getName(), e.toString());
            return null;
        }
    }

    /** Tek koşumda kapatmayı hak eden ihlal — istek tavanı aşımı. */
    private String severeViolation(ScriptedCheckerService.ScriptedResult res) {
        int cap = scriptedChecker.maxRequestsPerRun();
        if (cap <= 0) return null;
        Long reqs = res.phases() == null ? null : res.phases().httpReqs();
        if (reqs == null || reqs <= cap) return null;
        return String.format(
                "Anomali durumu tespit edildi: tek koşumda %d istek atıldı (tavan %d). Bu, üretim "
                + "sistemine yük bindiren bir kalıptır; izleme otomatik olarak devre dışı bırakıldı. "
                + "Script'teki döngüyü/istek sayısını düşürüp izlemeyi yeniden açabilirsiniz.", reqs, cap);
    }

    /** Ardışık zaman aşımı serisi — yalnız SON koşum da zaman aşımıysa sorgulanır (gereksiz sorgu yok). */
    private String timeoutStreak(ScriptedMonitor m, ScriptedCheckerService.ScriptedResult res) {
        int limit = timeoutStreakLimit();
        if (limit <= 0 || !"TIMEOUT".equals(res.status())) return null;

        // Yalnız ZAMANLANMIŞ koşumlar sayılır (2026-09-29): araya giren elle koşumlar ne seriyi uzatır ne kırar.
        List<com.sitemonitor.model.ScriptedCheck> recent = checkRepo.findRecentScheduledByMonitorId(m.getId(), limit);
        // Seri ancak TAM DOLU pencerede sayılır: 3 kaydı olan yeni bir monitör 5'lik eşiği geçmiş
        // sayılmamalı (aksi halde ilk üç zaman aşımı izlemeyi kapatırdı).
        if (recent == null || recent.size() < limit) return null;
        for (var c : recent) {
            if (!"TIMEOUT".equals(c.getStatus())) return null;
        }
        // k6r B2: hedef gerçekten asılıysa açık kesinti alarmı onu anlatıyor — izleme körleştirilmez, alarm kapatılmaz.
        if (outageAlarmKeepsMonitor(m)) return null;
        return String.format(
                "Anomali durumu tespit edildi: son %d koşumun %d'i de süre aşımıyla bitti — senaryo "
                + "verilen bütçede tamamlanmıyor ve her turda bir k6 süreci %d saniye boyunca kaynak "
                + "tutuyor. İzleme otomatik olarak devre dışı bırakıldı. Script'i hızlandırıp ya da "
                + "süreç bütçesini gözden geçirip izlemeyi yeniden açabilirsiniz.",
                limit, limit, m.getTimeoutSeconds() == null ? 0 : m.getTimeoutSeconds());
    }

    /**
     * Zaman aşımı serisi izlemeyi kapatmamalı mı (k6r B2 + inceleme #1 / #4): izleme ÖNCE güncel satırından okunur (tarama
     * sırasında ad / takım değişmişse alarm yeni adla aranır); izlemenin AÇIK {@code SCRIPTED_FAIL} alarmı varsa (yalnız BU
     * izlemenin olayı — ad yalnız takım içinde tekil, başka takımın aynı adlı senaryosunun alarmı sayılmaz:
     * {@link EscalationService#closableBy}) ya da SCRIPTED_FAIL için süren bir toplu kesinti bastırması varsa ya da bunlardan
     * biri okunamadıysa (satır silinmiş dahil) true. Alarm okuyucusu yoksa (elle kurulan testler) false = eski davranış.
     */
    private boolean outageAlarmKeepsMonitor(ScriptedMonitor m) {
        AlertEventRepository repo = alertEventRepo;
        if (repo == null) return false;
        ScriptedMonitor cur;
        try {
            cur = monitorRepo.findById(m.getId()).orElse(null);
        } catch (Exception e) {
            if (logDue(m.getId()))
                log.warn("Anomali guard'ı: {} güncel satırı okunamadı ({}) — zaman aşımı serisine rağmen izleme KAPATILMADI "
                        + "(bilinmeyen durumda dokunulmaz)", m.getName(), e.getClass().getSimpleName());
            return true;
        }
        if (cur == null) return true;   // arada silindi: kapatılacak satır yok
        String name = cur.getName() == null ? m.getName() : cur.getName();
        boolean open = false;
        boolean suppressed = false;
        try {
            Map<String, Object> owner = ownerCtx(cur);
            for (AlertEvent a : repo.findOpenAlerts(name, EscalationService.TYPE_SCRIPTED_FAIL)) {
                if (a != null && EscalationService.closableBy(a, owner)) { open = true; break; }
            }
            NetworkOutageEventRepository outages = outageEventRepo;
            if (!open && outages != null)
                suppressed = outages.findFirstBySourceAndStatusOrderByIdDesc(EscalationService.TYPE_SCRIPTED_FAIL, "ONGOING").isPresent();
        } catch (Exception e) {
            if (logDue(m.getId()))
                log.warn("Anomali guard'ı: {} için açık kesinti alarmı / bastırma okunamadı ({}) — zaman aşımı serisine rağmen "
                        + "izleme KAPATILMADI (bilinmeyen durumda dokunulmaz)", name, e.getClass().getSimpleName());
            return true;
        }
        if (open && logDue(m.getId()))
            log.info("Anomali guard'ı: {} son koşumlarının hepsi zaman aşımı, ama izlemenin AÇIK kesinti alarmı var — hedef "
                    + "gerçekten yanıt vermiyor; izleme kapatılmadı, alarma dokunulmadı", name);
        if (suppressed && logDue(m.getId()))
            log.info("Anomali guard'ı: {} son koşumlarının hepsi zaman aşımı, ama sentetik izlemeler için toplu kesinti bastırması "
                    + "sürüyor — izleme kapatılmadı", name);
        return open || suppressed;
    }

    /** İzleme başına saatte bir günlük satırı (sınırlı harita). */
    private boolean logDue(Long monitorId) {
        if (monitorId == null) return true;
        long now = System.currentTimeMillis();
        if (openOutageLoggedAt.size() > OPEN_OUTAGE_LOG_MAX_KEYS) openOutageLoggedAt.clear();
        Long last = openOutageLoggedAt.get(monitorId);
        if (last != null && now - last < OPEN_OUTAGE_LOG_EVERY_MS) return false;
        openOutageLoggedAt.put(monitorId, now);
        return true;
    }

    /**
     * Kapat + sebebi yaz + açık alarmı kapat + aktivite kaydı + sahibine bildir.
     *
     * <p>CHECKS-4 (2026-10-22): kapatma HEDEFLİ, koşullu bir UPDATE'tir ({@code active=false, disabled_reason,
     * disabled_at, updated_at WHERE id=? AND active=true}). {@code m} sweep başında okunmuş eski bir kopya olabilir; onu
     * {@code save} etmek kullanıcının arada kaydettiği script / env / ad / yönlendirme değişikliğini geri yazıyordu.
     * Güncel satır kapatmadan SONRA yeniden okunur — alarm kapanışı, aktivite ve bildirim güncel adla gider.
     *
     * @return kapatıldı mı (0 satır = kullanıcı zaten kapattı / satır silindi → bildirim yok)
     */
    private boolean disable(ScriptedMonitor m, String reason) {
        String now = ISO.format(Instant.now());
        int changed = monitorRepo.disableIfActive(m.getId(), reason, now);
        if (changed == 0) {
            log.info("Anomali guard'ı: {} zaten kapalı ya da silinmiş — kapatma/bildirim atlandı", m.getName());
            return false;
        }
        // Çağıranın elindeki kopya da kapalı görünsün (aynı sweep içinde ikinci bir değerlendirme yeniden kapatmasın).
        m.setActive(false);
        m.setDisabledReason(reason);
        m.setDisabledAt(now);
        m.setUpdatedAt(now);
        ScriptedMonitor cur = m;
        try {
            cur = monitorRepo.findById(m.getId()).orElse(m);
        } catch (Exception e) {
            log.debug("Anomali guard'ı: güncel satır okunamadı ({}): {}", m.getName(), e.toString());
        }
        log.error("Sentetik izleme otomatik devre dışı bırakıldı — {} · {}", cur.getName(), reason);

        // Kapatılan izleme artık sweep üretmez → açık alarm kendiliğinden ASLA çözülemezdi. YALNIZ bu izlemenin olayı
        // (L-M3, 2026-10-22): ad yalnız takım içinde benzersiz; sahiplik bağlamı olmadan başka takımın aynı adlı
        // senaryosunun açık alarmı da sessizce kapanıyordu (silme yolundaki D-b1 kuralıyla aynı bağlam).
        try {
            escalationService.resolveOpenAlertsSilently(cur.getName(),
                    Set.of(EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW),
                    ACTOR, ownerCtx(cur));
        } catch (Exception e) {
            log.warn("Anomali kapatmasında açık alarmlar çözülemedi ({}): {}", cur.getName(), e.toString());
        }

        try {
            activityLog.recordLifecycle(ActivityLogService.SCRIPTED, cur.getId(), cur.getName(), cur.getName(),
                    cur.getTeamId(), "PAUSED", ACTOR);
        } catch (Exception e) {
            log.warn("Anomali kapatması aktivite akışına yazılamadı ({}): {}", cur.getName(), e.toString());
        }

        notifyOwners(cur, reason, now);
        return true;
    }

    /** İzlemenin sahiplik bağlamı — {@code MonitoringController.ownerCtx} ile aynı biçim (sentetik izleme bağımsızdır). */
    static Map<String, Object> ownerCtx(ScriptedMonitor m) {
        Map<String, Object> c = new java.util.HashMap<>();
        if (m.getId() != null) c.put("monitor_id", m.getId());
        if (m.getTeamId() != null) c.put("team_id", m.getTeamId());
        return c;
    }

    /**
     * Takımın alarm alıcılarına bildirir — yeni bir alarm TÜRÜ açılmaz.
     *
     * <p>Gerekçe: yeni bir tür, olay/fırtına/kesinti/rapor zincirinin her halkasına bağlanmayı
     * gerektirir ve yarım bağlanan tür sessiz boşluk üretir. Burada gereken tek şey bir bildirim;
     * izlemenin durumu zaten aktivite akışında ve ekranda görünür. Gövde standart alarm şablonuyla
     * kurulur (Outlook-güvenli işaretleme oradan gelir, elle HTML yazılmaz).
     */
    private void notifyOwners(ScriptedMonitor m, String reason, String disabledAt) {
        String body = reason + "\n\nİzleme: " + m.getName()
                + "\nDurum: DEVRE DIŞI (otomatik)\n"
                + "Yeniden açmak için izlemeyi düzenleyip aktif hâle getirmeniz yeterli; "
                + "kapatma sebebi izleme sayfasında görünmeye devam eder.";

        if (!Boolean.FALSE.equals(m.getNotifyEmail())) {
            try {
                // Alıcı, monitörün KENDİ bildirim grubundan çözülür. Eski teamAlertEmails(teamId) =
                // collectTeamEmails(teamId, null, null), yani grubu yok sayıyordu: bu bildirim aynı
                // monitörün normal alarmlarından BAŞKA bir adres kümesine gidiyordu.
                List<String> to = escalationService.teamEmailsForMonitor(m.getTeamId(), m.getNotificationGroupId());
                if (to.isEmpty()) log.warn("Anomali e-postası gönderilemedi — {} için alıcı yok", m.getName());
                else emailService.sendAlert(to.toArray(new String[0]),
                        "[Site Monitor] Sentetik izleme devre dışı bırakıldı: " + m.getName(), body,
                        m.getName(), "CRITICAL", EscalationService.TYPE_SCRIPTED_FAIL, null, Map.of());
            } catch (Exception e) {
                log.warn("Anomali e-postası gönderilemedi ({}): {}", m.getName(), e.toString());
            }
        }

        // PUSH halkası. disable() bu metottan ÖNCE resolveOpenAlertsSilently çağırıyor ve o yol
        // enqueueResolvePushQuietly ile telefona "DÜZELDİ" atıyor (2026-09-10 ürün kararı, doğru
        // davranış). Push halkası burada bağlı olmadığı sürece yalnız push kullanan nöbetçi,
        // OTOMATİK KAPATILMIŞ bir izleme için "normale döndü" görüp izlemenin artık hiç koşmadığını
        // hiçbir kanaldan öğrenemiyordu — ters yönde bilgi. Kanal paritesi kuralı: e-postaya
        // bağlanan her bildirim push'a da bağlanır.
        try {
            // İki dilli metin (2026-10-04, öneri 5): push kişinin dilinde; Türkçe metin bugünküyle aynı.
            userPushService.enqueueTeamNoticeLocalized(m.getTeamId(), "SCRIPTED_DISABLED", "CRITICAL", "SCRIPTED",
                    m.getName(),
                    new UserPushService.LocalizedText(
                            "Sentetik izleme otomatik olarak DEVRE DIŞI bırakıldı: " + m.getName() + " — " + reason,
                            "Synthetic monitor was automatically DISABLED: " + m.getName() + " — " + reason),
                    "scripted-disabled:" + m.getId() + ":" + disabledAt, java.util.Set.of());
        } catch (Exception e) {
            log.warn("Anomali push'u gönderilemedi ({}): {}", m.getName(), e.toString());
        }
    }
}
