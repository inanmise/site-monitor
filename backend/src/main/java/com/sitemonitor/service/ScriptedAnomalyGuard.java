package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final AppSettingsService appSettings;

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
        if (!enabled() || m == null || m.getId() == null || res == null) return null;
        // Zaten kapalıysa tekrar kapatma: aynı e-posta her manuel tetikte yeniden giderdi.
        if (Boolean.FALSE.equals(m.getActive())) return null;
        try {
            String reason = severeViolation(res);
            if (reason == null) reason = timeoutStreak(m, res);
            if (reason == null) return null;
            disable(m, reason);
            return reason;
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

        List<com.sitemonitor.model.ScriptedCheck> recent = checkRepo.findRecentByMonitorId(m.getId(), limit);
        // Seri ancak TAM DOLU pencerede sayılır: 3 kaydı olan yeni bir monitör 5'lik eşiği geçmiş
        // sayılmamalı (aksi halde ilk üç zaman aşımı izlemeyi kapatırdı).
        if (recent == null || recent.size() < limit) return null;
        for (var c : recent) {
            if (!"TIMEOUT".equals(c.getStatus())) return null;
        }
        return String.format(
                "Anomali durumu tespit edildi: son %d koşumun %d'i de süre aşımıyla bitti — senaryo "
                + "verilen bütçede tamamlanmıyor ve her turda bir k6 süreci %d saniye boyunca kaynak "
                + "tutuyor. İzleme otomatik olarak devre dışı bırakıldı. Script'i hızlandırıp ya da "
                + "süreç bütçesini gözden geçirip izlemeyi yeniden açabilirsiniz.",
                limit, limit, m.getTimeoutSeconds() == null ? 0 : m.getTimeoutSeconds());
    }

    /** Kapat + sebebi yaz + açık alarmı kapat + aktivite kaydı + sahibine bildir. */
    private void disable(ScriptedMonitor m, String reason) {
        String now = ISO.format(Instant.now());
        m.setActive(false);
        m.setDisabledReason(reason);
        m.setDisabledAt(now);
        m.setUpdatedAt(now);
        monitorRepo.save(m);
        log.error("Sentetik izleme otomatik devre dışı bırakıldı — {} · {}", m.getName(), reason);

        // Kapatılan izleme artık sweep üretmez → açık alarm kendiliğinden ASLA çözülemezdi.
        try {
            escalationService.resolveOpenAlertsSilently(m.getName(),
                    Set.of(EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW),
                    ACTOR);
        } catch (Exception e) {
            log.warn("Anomali kapatmasında açık alarmlar çözülemedi ({}): {}", m.getName(), e.toString());
        }

        try {
            activityLog.recordLifecycle(ActivityLogService.SCRIPTED, m.getId(), m.getName(), m.getName(),
                    m.getTeamId(), "PAUSED", ACTOR);
        } catch (Exception e) {
            log.warn("Anomali kapatması aktivite akışına yazılamadı ({}): {}", m.getName(), e.toString());
        }

        notifyOwners(m, reason);
    }

    /**
     * Takımın alarm alıcılarına bildirir — yeni bir alarm TÜRÜ açılmaz.
     *
     * <p>Gerekçe: yeni bir tür, olay/fırtına/kesinti/rapor zincirinin her halkasına bağlanmayı
     * gerektirir ve yarım bağlanan tür sessiz boşluk üretir. Burada gereken tek şey bir bildirim;
     * izlemenin durumu zaten aktivite akışında ve ekranda görünür. Gövde standart alarm şablonuyla
     * kurulur (Outlook-güvenli işaretleme oradan gelir, elle HTML yazılmaz).
     */
    private void notifyOwners(ScriptedMonitor m, String reason) {
        if (Boolean.FALSE.equals(m.getNotifyEmail())) return;
        try {
            List<String> to = escalationService.teamAlertEmails(m.getTeamId());
            if (to.isEmpty()) {
                log.warn("Anomali bildirimi gönderilemedi — {} için alıcı yok", m.getName());
                return;
            }
            String subject = "Sentetik izleme devre dışı bırakıldı: " + m.getName();
            String body = reason + "\n\nİzleme: " + m.getName()
                    + "\nDurum: DEVRE DIŞI (otomatik)\n"
                    + "Yeniden açmak için izlemeyi düzenleyip aktif hâle getirmeniz yeterli; "
                    + "kapatma sebebi izleme sayfasında görünmeye devam eder.";
            emailService.sendAlert(to.toArray(new String[0]), subject, body,
                    m.getName(), "CRITICAL", EscalationService.TYPE_SCRIPTED_FAIL, null, Map.of());
        } catch (Exception e) {
            log.warn("Anomali bildirimi gönderilemedi ({}): {}", m.getName(), e.toString());
        }
    }
}
