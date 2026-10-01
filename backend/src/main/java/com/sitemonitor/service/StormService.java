package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Alarm fırtınası (alert storm) motoru — çok sayıda monitör kısa bir pencerede birden düştüğünde
 * bireysel alarmları TEK toplu bildirime indirger (UptimeRobot "Alert settings" modeli).
 *
 * <p><b>Tasarım — akışlı sayaç + terfi (Design B):</b> Tampon YOK; her doğrulanmış kesinti bugünkü gibi
 * ANINDA gider — ancak scope'u için aktif bir storm varsa (attach) ya da bu kesinti eşiği aşıyorsa (promote)
 * bastırılır/toplu gider. Kalıcı {@link AlertEvent}'ler sayacın kendisidir → doğal crash-safe; bellek-içi
 * sayaç yoktur. Terfi, {@code alert_storms} tablosunda scope-başına-tek-aktif kısmi UNIQUE indeks +
 * {@code INSERT … ON CONFLICT DO NOTHING} ile atomiktir → eşzamanlı worker'larda çift storm/çift alarm YOK.
 *
 * <p><b>Yalnız BİLDİRİMİ gruplar:</b> incident'ler (AlertEvent) per-monitör kaydedilmeye devam eder →
 * geçmiş/uptime% hiç etkilenmez. Bağ {@code AlertEvent.stormId} iledir.
 *
 * <p><b>Katmanlama:</b> {@code EscalationService.processConfirmedOutage} → {@link #evaluate} hunisi, mevcut
 * iki ≥%50 bulk-suppression geçidinin (SchedulerService cert yolu + MonitoringOutageService izleme yolu)
 * ALTINDA çalışır — yalnız onları aşmış (yani &lt;%50 / çok-tipli) selleri toplar; gerçek altyapı kesintisi
 * zaten yukarıda yutulur.
 *
 * <p><b>Feature-flag default AÇIK</b> ({@code site.monitor.storm.enabled}); admin "Alert Settings"ten kapatabilir.
 * Kapalıyken {@link #evaluate} ilk satırda {@code SEND_INDIVIDUAL} döner → mevcut alarm davranışı bit-bit aynı.
 *
 * <p><b>TAKIM YALITIMI (ürün kararı 2026-09-29, prod olayı).</b> Fırtına TAKIM BAZINDA değerlendirilir: kapsam
 * anahtarı {@code TEAM:<id>} (grup kipinde {@code TEAM:<id>|GROUP:<grup>}); eşik (SAYI ya da takımın aktif
 * izlemelerinin YÜZDESİ), pencere sayımı, kök-neden ve bildirim (e-posta + push + webhook) yalnız alarmın SAHİBİ
 * takımın kümesinden. Kuruluş geneli ({@code ACCOUNT}, "Tüm monitörler") fırtına YOK — eskisi yaşam döngüsünde
 * dağıtılır. Her takım dağıtımı yalnız KENDİ üyelerinin sayısını, adını ve kök-nedenini görür; fırtına push'u
 * bireysel alarmdan geniş kitleye (seviye yükseltmesiyle) gitmez ({@link #stormPushLevel}). 7/24 (NOC) izlemede açık
 * onayla (izleme başına {@code noc_notify}) kapsanan üyeler için ayrı kanaldır — kuralı değişmedi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StormService {

    private final AlertStormRepository stormRepo;
    private final AlertEventRepository alertEventRepo;
    private final AppSettingsService appSettings;
    private final EmailNotificationService emailService;
    private final WebhookService webhookService;
    private final TeamRepository teamRepo;
    private final EscalationContactRepository contactRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final JdbcTemplate jdbcTemplate;
    // Toplam-aktif-monitör denominatörü + per-group grup çözümü için monitör repo'ları
    private final HttpMonitorRepository httpRepo;
    private final PortMonitorRepository portRepo;
    private final KeywordMonitorRepository keywordRepo;
    private final PingMonitorRepository pingRepo;
    private final DnsMonitorRepository dnsRepo;
    private final DomainMonitorRepository domainRepo;
    /** Takim mailinin yerine gecebilecek bildirim grubu (damgasiz -- asagiya bakin). */
    private final NotificationGroupService notificationGroups;

    /** 9. tür (sayfa-bütünlüğü) — @RequiredArgsConstructor'ı (ve StormServiceTest'in elle çağrısını)
     *  büyütmemek için alan enjeksiyonu (SchedulerService ile aynı desen). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.PageMonitorRepository pageRepo;

    /** Senaryo türü — alan enjeksiyonu (constructor/test büyütmemek için; pageRepo ile aynı desen). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.ScriptedMonitorRepository scriptedRepo;

    /** Sayfa hızı türü — alan enjeksiyonu (pageRepo/scriptedRepo ile aynı desen). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedRepo;

    /** Kişi-push kanalı — alan enjeksiyonu (constructor/test büyütmemek için, pageRepo deseni).
     *  Fırtına yolunda push HİÇ YOKTU: ürünün en ciddi olayında (12 monitör birden düştü) yalnız
     *  kişi-push'u kullanan nöbetçi hiçbir bildirim almıyordu. null-güvenli çağrılır. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserPushService userPushService;

    /**
     * 7/24 İzleme Ekibi (NOC, 2026-09-27) — fırtınada NOC da TOPLU tek e-posta alır (sözleşme §4). Karar ve teslim
     * {@code NocNotificationService}'te (bireysel hunideki ile AYNI kurallar); alan enjeksiyonu, null-güvenli.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocNotificationService nocNotifications;

    /** Olay ctx'indeki kanal bastırma damgasını okumak için — alan enjeksiyonu, null-güvenli. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Bildirim günlüğü (2026-09-30): fırtına e-postaları eskiden doğrudan {@code sendHtml} ile gidiyor ve
     * {@code notification_logs}'a HİÇ yazılmıyordu — ne SMTP günlüğünde ne üye alarmın "Bildirimler" bölümünde
     * görünüyordu; takım "hiç posta gelmedi" diyor, sistem "gönderdim" diyordu ve ikisi de kanıtsızdı. Artık her
     * fırtına postası (açılış / günlük tekrar / çözüm) o takımın ÜYE ALARMLARININ her birine bağlı bir satır bırakır
     * (tetik {@code STORM_INITIAL} / {@code STORM_REALERT} / {@code STORM_RESOLVE}). Alan enjeksiyonu, null-güvenli.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.NotificationLogRepository notificationLogRepo;


    /** Fırtına e-posta tetik adları — üye alarmın bildirim günlüğünde görünür; ön yüz {@code MAIL_TRIGGER} eşler. */
    public static final String TRIGGER_STORM_INITIAL = "STORM_INITIAL";
    public static final String TRIGGER_STORM_REALERT = "STORM_REALERT";
    public static final String TRIGGER_STORM_RESOLVE = "STORM_RESOLVE";

    /** Sessiz pencere ayarı: son üye katılımından bu kadar dakika sonra yeni üye gelmediyse fırtına mühürlenir. */
    public static final String KEY_QUIET = "site.monitor.storm.quiet-minutes";
    public static final int QUIET_DEFAULT = 5, QUIET_MIN = 5, QUIET_MAX = 1440;

    public enum StormAction {
        /** Storm devrede değil / eşik altı → bireysel alarm gönder (bugünkü davranış, sıfır gecikme). */
        SEND_INDIVIDUAL,
        /** Storm üyesi (attach) veya terfi → bireysel bildirim bastırıldı (toplu alarm storm üzerinden). */
        SUPPRESSED
    }

    public static final String KEY_ENABLED   = "site.monitor.storm.enabled";
    public static final String KEY_UNIT      = "site.monitor.storm.threshold-unit";     // PERCENT | COUNT
    public static final String KEY_VALUE     = "site.monitor.storm.threshold-value";
    public static final String KEY_WINDOW    = "site.monitor.storm.window-minutes";
    public static final String KEY_PER_GROUP = "site.monitor.storm.per-group";

    /** 2026-09-29 öncesinin kuruluş geneli kapsamı — artık üretilmez; aktif kalan eskisi yaşam döngüsünde dağıtılır. */
    static final String SCOPE_ACCOUNT = "ACCOUNT";
    static final String UNGROUPED     = "__UNGROUPED__";
    /** Takım kapsamlı fırtına anahtarı: {@code TEAM:<takımId>} ya da grup kipinde {@code TEAM:<takımId>|GROUP:<grup>}. */
    static final String TEAM_SCOPE_PREFIX     = "TEAM:";
    static final String GROUP_SCOPE_SEP       = "|GROUP:";
    static final String SCOPE_TYPE_TEAM       = "TEAM";
    static final String SCOPE_TYPE_TEAM_GROUP = "TEAM_GROUP";
    /** alert_storms.scope_key kolon genişliği (SchedulerService DDL'i VARCHAR(200)). */
    private static final int SCOPE_KEY_MAX = 200;
    /** Bir "storm" için gereken minimum monitör tabanı — 1'lik storm anlamsız. */
    static final int MIN_THRESHOLD = 2;
    /**
     * YÜZDE kipinde mutlak taban (2026-09-29, O-4): payda artık takımın kendi izlemeleri olduğundan küçük takımda yüzde
     * 1–2'ye iniyordu; tek bir host düşünce aynı pencerede ACCESSIBILITY + PORT_DOWN (+ DNS) açılıp "fırtına" sayılıyor ve
     * bireysel alarmlar bastırılıyordu. Fırtına = ÇOK HEDEF birden; yüzde ne derse desin en az bu kadar farklı hedef.
     */
    static final int PERCENT_MIN_TARGETS = 3;

    /**
     * Eski (2026-09-29 öncesi kuruluş geneli) fırtınanın emekliye ayrılması uygulama açılışından bu süre SONRA (O-3):
     * ilk izleme turları hayalet üyeleri fırtına AKTİFKEN kapatsın (bireysel "ÇÖZÜLDÜ" yerine tek toplu çözüm) ve kalan
     * üyeler takım bazında yeniden değerlendirilsin. Süre boyunca eski fırtına ETKİSİZDİR (toplu tekrar / 7-24 tik yok).
     */
    @org.springframework.beans.factory.annotation.Value("${site.monitor.storm.legacy-retire-grace-minutes:15}")
    long legacyRetireGraceMinutes = 15;

    /** Açılış anı (ms) — eski fırtına emeklilik penceresinin başlangıcı; test ileri alabilsin diye paket-özel. */
    long startedAtMs = System.currentTimeMillis();

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String INSTANCE_ID = resolveHostname() + "-storm";
    private static final int LOCK_TTL_SECONDS = 90;

    // Toplam-aktif-monitör cache (denominatör) — her kesintide saymamak için (~60sn TTL).
    private volatile long cachedTotal = 0;
    private volatile long cachedTotalAt = 0;
    /** Takım başına payda önbelleği (YÜZDE eşiği takım kümesinde, 2026-09-29): takımId → {toplam, damga ms}. */
    private final Map<Long, long[]> teamTotalCache = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Karar hunisi ────────────────────────────────────────────────────────────

    /**
     * processConfirmedOutage'ın INITIAL dispatch'inden HEMEN ÖNCE çağrılır (event zaten kaydedilmiş).
     * Storm üyesiyse/terfi ediyorsa {@code SUPPRESSED} döner (çağıran bireysel {@code sendCombinedAlert}'i atlar);
     * değilse {@code SEND_INDIVIDUAL}. stormId (+ per-group modda groupName) çağıranın {@code event} nesnesine
     * damgalanır; çağıran zaten event'i tekrar save ettiğinden ayrıca kaydetmeye gerek yok.
     */
    public StormAction evaluate(AlertEvent event, Map<String, Object> ctx) {
        if (!isEnabled()) return StormAction.SEND_INDIVIDUAL;
        if (event == null || event.getAlertType() == null
                || !EscalationService.DOWN_ALERT_TYPES.contains(event.getAlertType())) {
            return StormAction.SEND_INDIVIDUAL;   // yalnız gerçek DOWN tipleri storm'a girer
        }

        // TAKIM YALITIMI (2026-09-29): eskiden kapsam ACCOUNT'tu (tüm takımlar TEK sayaç) — bir takımın arızaları eşiği
        // aşınca "N monitör birden erişilemez · Tüm monitörler" bildirimi üyesi olan HER takıma gidiyor, fırtına sürerken
        // başka takımın yeni alarmı da o fırtınaya bağlanıp bireysel bildirimi yutuluyordu. Artık kapsam alarmın SAHİBİ
        // takımıdır. Sahipsiz alarm fırtınaya girmez — bireysel hat karar verir (sahipsiz kayıt = bildirim yok).
        Long teamId = event.getTeamId();
        if (teamId == null) return StormAction.SEND_INDIVIDUAL;

        boolean perGroup = appSettings.getBoolean(KEY_PER_GROUP, false);
        String group = null;
        if (perGroup) {
            String g = resolveGroup(event, ctx);   // lazy — yalnız per-group modda repo lookup
            group = (g != null && !g.isBlank()) ? g : UNGROUPED;
            event.setGroupName(group);             // sayım grup filtresi + toplu recovery için damgala
        }
        String scopeKey = teamScopeKey(teamId, group);
        String scopeType = perGroup ? SCOPE_TYPE_TEAM_GROUP : SCOPE_TYPE_TEAM;

        try {
            // 1) Scope'un aktif storm'u var mı → bağla (attach), bireysel gönderme.
            //    MÜHÜRLÜ fırtına (2026-09-30) yeni üye ALMAZ: son üye katılımından quiet-minutes geçtiyse patlama
            //    bitmiştir; alarm bireysel hatta gider (yaşam döngüsü fırtınayı en geç 30 sn içinde kapatır). Eskiden
            //    kalıcı başarısız üyeler fırtınayı süresiz açık tutuyor, takımın HER yeni DOWN alarmı sessizce yutuluyordu.
            Optional<AlertStorm> active = stormRepo.findByScopeKeyAndResolvedFalse(scopeKey);
            if (active.isPresent()) {
                AlertStorm storm = active.get();
                if (isSealed(storm, now())) {
                    log.info("🌩 Storm #{} mühürlü (son üye {} — sessiz pencere {} dk doldu) — {} [{}] bireysel gönderiliyor",
                            storm.getId(), memberClock(storm), quietMinutes(), event.getDomain(), event.getAlertType());
                    return StormAction.SEND_INDIVIDUAL;
                }
                event.setStormId(storm.getId());
                bumpMemberCount(storm);
                recordMember(storm.getId(), event.getId(), AlertStormMember.JOIN_ATTACH);
                log.info("🌩 Storm üyesi eklendi (bireysel bildirim yok): {} [{}] → storm #{}",
                        event.getDomain(), event.getAlertType(), storm.getId());
                return StormAction.SUPPRESSED;
            }

            // 2) Pencere-içi açık DOWN eş sayısı ≥ eşik mi → terfi; değilse normal bireysel (sıfır gecikme).
            String since = windowSince();
            // Pencere sorgusu kuruluş genelidir (pencere 1–15 dk, küme küçük); TAKIM süzgeci burada — başka takımın açık
            // DOWN'ı bu takımın sayacına, üye listesine ve kök-nedenine GİRMEZ.
            List<AlertEvent> peers = new ArrayList<>();
            for (AlertEvent p : perGroup
                    ? alertEventRepo.findOpenDownSinceInGroup(EscalationService.DOWN_ALERT_TYPES, since, group)
                    : alertEventRepo.findOpenDownSince(EscalationService.DOWN_ALERT_TYPES, since)) {
                if (p != null && teamId.equals(p.getTeamId())) peers.add(p);
            }
            // Tetikleyen event, tanımı gereği scope'ta açık bir DOWN'dır; ancak per-group modda group_name'i henüz
            // commit edilmemiş olabileceğinden sorgu onu HARİÇ tutabilir → eşik off-by-one'ı (per-group N+1 gerektirirdi).
            // Sayıma ve üye listesine mutlaka dahil et (M1). linkPeers zaten skipId ile onu atlar (çağıran kaydeder).
            if (event.getId() == null || peers.stream().noneMatch(p -> event.getId().equals(p.getId()))) {
                peers.add(event);
            }
            int threshold = computeThreshold(teamId);
            // Eşik FARKLI HEDEF sayısıyla (O-4): aynı host'un ACCESSIBILITY + PORT_DOWN + DNS_FAILURE alarmları tek hedeftir.
            int targets = distinctTargets(peers);
            if (targets < threshold) return StormAction.SEND_INDIVIDUAL;

            // 3) Atomik terfi — scope-başına-tek-aktif UNIQUE; kazanan (rows==1) toplu alarm gönderir. Açılış anlık
            //    görüntüsü (takım, eşik, pencere, hedef sayısı, tetikleyen) AYNI INSERT'te yazılır (2026-10-01).
            boolean created = insertStormIfAbsent(scopeKey, scopeType, peers,
                    new OpenSnapshot(teamId, group, threshold, targets, event.getId()));
            Optional<AlertStorm> stormOpt = stormRepo.findByScopeKeyAndResolvedFalse(scopeKey);
            if (stormOpt.isEmpty()) {
                log.warn("Storm terfi sonrası aktif storm bulunamadı ({}) — bireysel gönderiliyor", scopeKey);
                return StormAction.SEND_INDIVIDUAL;
            }
            AlertStorm storm = stormOpt.get();
            if (!created && isSealed(storm, now())) {
                // Terfi yarışını kaybettik ama bulunan fırtına eski ve mühürlü (yaşam döngüsü henüz kapatmadı): yutma.
                log.info("🌩 Storm #{} mühürlü — {} [{}] bireysel gönderiliyor", storm.getId(), event.getDomain(), event.getAlertType());
                return StormAction.SEND_INDIVIDUAL;
            }

            // Penceredeki tüm açık DOWN üyeleri storm'a bağla (mevcut event'i çağıran kaydeder → burada atla).
            event.setStormId(storm.getId());
            linkPeers(peers, storm.getId(), event.getId());

            if (created) {
                // Gözlem: üyelik TEK toplu INSERT (açılış postası duyuru damgasını bu satırlara yazar).
                recordMembers(storm.getId(), peers, event.getId(), AlertStormMember.JOIN_PEER);
                sendStormAlert(storm, peers, "INITIAL");   // kazanan → TEK toplu alarm (senkron; worker thread)
                log.warn("🌩🔴 ALARM FIRTINASI başladı — storm #{} [{}] {} monitör (eşik {}) — TEK toplu bildirim gönderildi",
                        storm.getId(), scopeKey, peers.size(), threshold);
            }
            return StormAction.SUPPRESSED;

        } catch (Exception e) {
            // Storm mantığı asla bireysel alarmı düşürmesin — hata olursa güvenli tarafta bireysel gönder.
            log.warn("Storm değerlendirmesi hata verdi ({} [{}]) — bireysel alarma düşülüyor: {}",
                    event.getDomain(), event.getAlertType(), e.getMessage());
            return StormAction.SEND_INDIVIDUAL;
        }
    }

    /** Açık alarm bir storm üyesiyse ve storm hâlâ aktifse true (çağıran bireysel çözüm e-postasını bastırır). */
    public boolean isActive(Long stormId) {
        if (stormId == null) return false;
        try {
            return stormRepo.findById(stormId).map(s -> !Boolean.TRUE.equals(s.getResolved())).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Yaşam döngüsü sweep'i (çözülme / günlük re-alert / toggle-off) ────────────

    @Scheduled(fixedDelayString = "${site.monitor.storm.sweep-ms:30000}", initialDelay = 45000)
    public void lifecycleSweep() {
        List<AlertStorm> active;
        try {
            active = stormRepo.findByResolvedFalse();
        } catch (Exception e) {
            return;   // tablo henüz yok / DB erişilemez — sessiz
        }
        if (active.isEmpty()) return;

        boolean enabled = isEnabled();
        if (!tryLock()) return;   // HA: yalnız bir pod yönetir
        try {
            for (AlertStorm storm : active) {
                try {
                    if (!enabled) { disband(storm, "özellik kapatıldı"); continue; }   // toggle-off: geri-bağla, sessiz kapat
                    // 2026-09-29 öncesinin kuruluş geneli (ACCOUNT) / takımsız grup fırtınası: takım yalıtımında karşılığı
                    // yok. Sürseydi günlük toplu tekrarı "Tüm monitörler" etiketiyle üyesi olan her takıma giderdi. İlk
                    // izleme turlarına kadar ETKİSİZ bekler (tekrar/7-24 yok), sonra takım bazında emekliye ayrılır (O-3).
                    if (!isTeamScoped(storm)) {
                        if (legacyRetireDue()) retireLegacy(storm);
                        continue;
                    }
                    lifecycleOne(storm);
                } catch (Exception e) {
                    log.warn("Storm yaşam döngüsü hatası #{}: {}", storm.getId(), e.getMessage());
                }
            }
        } finally {
            releaseLock();
        }
    }

    private void lifecycleOne(AlertStorm storm) {
        List<AlertEvent> members = alertEventRepo.findByStormId(storm.getId());
        // Histerezis de FARKLI HEDEF sayısıyla (O-4) — açılış kuralıyla aynı ölçü.
        long activeDown = distinctTargets(members.stream().filter(m -> !Boolean.TRUE.equals(m.getResolved())).toList());
        storm.setMemberCount(members.size());
        if (storm.getPeakTargets() == null || activeDown > storm.getPeakTargets()) storm.setPeakTargets((int) activeDown);

        int threshold = computeThreshold(teamOfScope(storm.getScopeKey()));
        int resolveFloor = Math.max(MIN_THRESHOLD, (threshold + 1) / 2);   // histerezis: eşiğin altı → flapping'i önler

        if (activeDown < resolveFloor) {
            resolveStorm(storm, members, "histerezis tabanının altına inildi", RESOLVE_FLOOR);
            return;
        }
        // ÖMÜR SINIRI (2026-09-30): son üye katılımından quiet-minutes geçtiyse patlama bitmiştir — hâlâ-down üye
        // sayısı ne olursa olsun fırtına kapanır. Kalıcı başarısız izlemeler (asla iyileşmeyen sentetik testler)
        // fırtınayı süresiz açık tutup takımın sonraki tüm alarmlarını bildirimsiz bırakıyordu.
        if (isSealed(storm, now())) {
            resolveStorm(storm, members, "sessiz pencere doldu (" + quietMinutes() + " dk yeni üye yok)", RESOLVE_SEALED);
            return;
        }
        // Aktif storm sürüyor → günlük toplu re-alert (aynı-UTC-gün kuralı, bireysel re-alert'in aynası)
        String last = storm.getLastReAlertAt() != null ? storm.getLastReAlertAt() : storm.getCreatedAt();
        // Storm günlük toplu re-alert — rolling 24 saat (23:59'da açılıp 00:00'da tekrar alarmlama edge'i, M10 ile tutarlı).
        if (last == null || EscalationService.reAlertDue(last, now(), RE_ALERT_HOURS)) {
            List<AlertEvent> stillDown = members.stream()
                    .filter(m -> !Boolean.TRUE.equals(m.getResolved())).toList();
            sendStormAlert(storm, stillDown, "DAILY_REALERT");
            log.info("🌩 Storm #{} günlük toplu re-alert gönderildi — {} monitör hâlâ down", storm.getId(), stillDown.size());
        } else {
            // memberCount + tepe hedef tazelemesi — HEDEFLİ UPDATE (2026-10-01). Varlığın save'i, okuma ile yazma arasında
            // katılan üyenin last_member_at damgasını eski değere geri sarıp fırtınayı erken mühürleyebiliyordu.
            try {
                jdbcTemplate.update("UPDATE alert_storms SET member_count = ?, peak_targets = GREATEST(COALESCE(peak_targets, 0), ?) "
                        + "WHERE id = ? AND resolved = false", storm.getMemberCount(), (int) activeDown, storm.getId());
            } catch (Exception ex) {
                log.debug("Storm #{} sayaç tazelemesi yazılamadı: {}", storm.getId(), ex.getMessage());
            }
        }
        // 7/24: fırtına SÜRERKEN katılan kapsanan izlemeler bireysel alarm üretmez (evaluate bastırır) — NOC'a toplu
        // güncelleme bu tik'ten gider (fırtına başına en çok ~5 dk'da bir; tekilleştirme ve aralık serviste).
        if (nocNotifications != null) {
            try {
                nocNotifications.onStormTick(storm, members.stream().filter(m -> !Boolean.TRUE.equals(m.getResolved())).toList(),
                        scopeLabel(storm), rootCauseLabel(storm.getRootCause()));
            } catch (Exception ex) {
                log.warn("Storm #{} 7/24 güncellemesi atlandı: {}", storm.getId(), ex.toString());
            }
        }
    }

    /**
     * Çözülme (histerezis tabanının altına inildi ya da sessiz pencere doldu): TEK toplu recovery + hâlâ-down üyeleri
     * bireysel hatta döndür.
     *
     * <p>Hâlâ-down üye iki sınıftır (2026-09-30): fırtınanın SON toplu postasında DUYURULMUŞ olan ({@code createdAt ≤}
     * fırtınanın {@code lastReAlertAt}'i) "bildirildi" sayılarak çözülür — günlük tekrar kadansı o postadan sürer, ikinci
     * bir İLK bildirim gitmez; postadan SONRA katılan (hiç duyurulmamış) üye ise {@code lastReAlertAt=null} ile çözülür
     * ve ilk turda bireysel İLK bildirimini alır. Eskiden hepsi ikinci sınıftı: duyurulmuş üyeler de yeniden İLK alıyordu.
     */
    private void resolveStorm(AlertStorm storm, List<AlertEvent> members, String reason, String reasonCode) {
        List<AlertEvent> recovered = members.stream()
                .filter(m -> Boolean.TRUE.equals(m.getResolved())).toList();
        List<AlertEvent> stillDown = members.stream()
                .filter(m -> !Boolean.TRUE.equals(m.getResolved())).toList();

        String announcedAt = storm.getLastReAlertAt();
        String closingAt = now();
        List<Object[]> leaves = new ArrayList<>(members.size());
        int announced = 0, unannounced = 0;
        for (AlertEvent e : stillDown) {
            if (announcedInStorm(e, announcedAt)) {
                if (alertEventRepo.releaseFromStormAsNotified(e.getId(), storm.getId(), announcedAt) == 1) {
                    announced++;
                    leave(leaves, storm.getId(), e.getId(), closingAt, AlertStormMember.LEAVE_NOTIFIED);
                } else {
                    alertEventRepo.unlinkFromStorm(e.getId());   // bağ değişmişse (yarış) güvenli taraf: bireysel İLK
                    leave(leaves, storm.getId(), e.getId(), closingAt, AlertStormMember.LEAVE_UNLINKED);
                }
            } else {
                alertEventRepo.unlinkFromStorm(e.getId());   // koşullu — çözülmüş üyeyi diriltmeden bağı kaldır (M6)
                leave(leaves, storm.getId(), e.getId(), closingAt, AlertStormMember.LEAVE_UNLINKED);
                unannounced++;
            }
        }
        for (AlertEvent e : recovered) {
            leave(leaves, storm.getId(), e.getId(), e.getResolvedAt() != null ? e.getResolvedAt() : closingAt, AlertStormMember.LEAVE_RECOVERED);
        }
        flushLeaves(storm.getId(), leaves);
        storm.setResolved(true);
        storm.setResolvedAt(closingAt);
        storm.setResolveReason(reasonCode);
        stormRepo.save(storm);

        if (!recovered.isEmpty()) sendStormRecovery(storm, recovered, stillDown);
        log.info("🌩✅ Storm #{} çözüldü ({}) — {} kurtuldu, {} hâlâ down ({} duyurulmuş → bildirildi sayıldı, {} duyurulmamış → bireysel ilk bildirim)",
                storm.getId(), reason, recovered.size(), stillDown.size(), announced, unannounced);
    }

    /**
     * Fırtına postasını takımın ÜYE alarmlarının bildirim günlüğüne yazar — üye başına bir satır, aynı gövde
     * (SMTP günlüğü ve alarm penceresi bu tablodan okur). Günlük yazımı asla gönderimi düşürmez.
     */
    private void logStormMail(TeamDispatch d, AlertStorm storm, String trigger, String subject, String html, String status) {
        if (notificationLogRepo == null || d == null || d.members() == null) return;
        String recipients = String.join(", ", d.emails());
        String name = d.teamName() != null ? d.teamName() : "-";
        String body = html != null ? html : "";
        String stamp = now();
        for (AlertEvent m : d.members()) {
            if (m == null || m.getId() == null) continue;
            try {
                com.sitemonitor.model.NotificationLog entry = new com.sitemonitor.model.NotificationLog();
                entry.setAlertEventId(m.getId());
                entry.setSentAt(stamp);
                entry.setRecipientName(name);
                entry.setRecipientEmail(recipients);
                entry.setRecipientRole("COMBINED");
                entry.setSubject(subject);
                entry.setMessage(body);
                entry.setEmailStatus(status != null ? status : "SKIPPED");
                entry.setWebhookStatus("SKIPPED");
                entry.setTrigger(trigger);
                entry.setEmailFrom(emailService.getEmailFrom());
                notificationLogRepo.save(entry);
            } catch (Exception ex) {
                log.warn("Storm #{} bildirim günlüğü yazılamadı (olay {}): {}", storm.getId(), m.getId(), ex.getMessage());
            }
        }
    }

    /** Üye, fırtınanın son toplu postasında duyurulmuş muydu — açılışı o postadan ÖNCE ise evet. */
    static boolean announcedInStorm(AlertEvent e, String stormLastReAlertAt) {
        if (e == null || stormLastReAlertAt == null || e.getCreatedAt() == null) return false;
        return e.getCreatedAt().compareTo(stormLastReAlertAt) <= 0;
    }

    /** Fırtına mühürlü mü: son üye katılımından ({@code lastMemberAt}, yoksa {@code createdAt}) quiet-minutes geçti. */
    boolean isSealed(AlertStorm storm, String nowIso) {
        String last = memberClock(storm);
        if (last == null) return false;
        try {
            java.time.LocalDateTime lastAt = java.time.LocalDateTime.parse(last, ISO_LDT);
            java.time.LocalDateTime now = java.time.LocalDateTime.parse(nowIso, ISO_LDT);
            return !now.isBefore(lastAt.plusMinutes(quietMinutes()));
        } catch (Exception e) {
            return false;   // ayrıştırılamayan damga mühür SAYILMAZ (mevcut davranış korunur)
        }
    }

    private static String memberClock(AlertStorm storm) {
        return storm.getLastMemberAt() != null ? storm.getLastMemberAt() : storm.getCreatedAt();
    }

    private static final DateTimeFormatter ISO_LDT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Sessiz pencere (dk) — ayar; {@value #QUIET_MIN}–{@value #QUIET_MAX} aralığına kırpılır. */
    public int quietMinutes() {
        return clamp(appSettings.getInt(KEY_QUIET, QUIET_DEFAULT), QUIET_MIN, QUIET_MAX);
    }

    /** Aktif storm'u zarifçe dağıt (özellik kapatıldı / eski kapsam): üyeleri geri-bağla (bireysele dön), kapat. */
    private void disband(AlertStorm storm, String reason) {
        List<AlertEvent> stillDown = alertEventRepo.findByStormIdAndResolvedFalse(storm.getId());
        // Fırtına aktifken KURTULAN üyelerin bireysel çözüm maili bilinçli bastırılıyor
        // (EscalationService:734-747: "TEK toplu recovery, fırtına dağıldığında storm sweep'inden
        // gider"). Admin storm.enabled'ı kapattığında bu yol yalnız hâlâ-down üyeleri geri
        // bağlayıp storm'u sendStormRecovery ÇAĞIRMADAN kapatıyordu: zaten çözülmüş üyeler için
        // toplu recovery artık hiç gönderilmiyordu. O takımlar "düştü" mailini aldı, "düzeldi"yi
        // hiç almayacaktı. Kapanış yolu ne olursa olsun bildirim simetrik kalmalı.
        List<AlertEvent> recovered = new ArrayList<>();
        Set<Long> stillDownIds = new java.util.HashSet<>();
        for (AlertEvent e : stillDown) if (e.getId() != null) stillDownIds.add(e.getId());
        for (AlertEvent e : alertEventRepo.findByStormId(storm.getId()))
            if (e.getId() != null && !stillDownIds.contains(e.getId())) recovered.add(e);

        String closingAt = now();
        List<Object[]> leaves = new ArrayList<>(stillDown.size() + recovered.size());
        for (AlertEvent e : stillDown) {
            alertEventRepo.unlinkFromStorm(e.getId());   // koşullu geri-bağlama (M6); sonraki sweep bireysel re-alert
            leave(leaves, storm.getId(), e.getId(), closingAt, AlertStormMember.LEAVE_UNLINKED);
        }
        for (AlertEvent e : recovered) {
            leave(leaves, storm.getId(), e.getId(), e.getResolvedAt() != null ? e.getResolvedAt() : closingAt, AlertStormMember.LEAVE_RECOVERED);
        }
        flushLeaves(storm.getId(), leaves);
        storm.setResolved(true);
        storm.setResolvedAt(closingAt);
        storm.setResolveReason(RESOLVE_DISABLED);
        stormRepo.save(storm);
        if (!recovered.isEmpty()) sendStormRecovery(storm, recovered, stillDown);
        log.info("🌩 Storm #{} kapatıldı ({}) — {} kurtulan için toplu çözüm gitti, "
                + "{} üye bireysel alarmlamaya döndü", storm.getId(), reason, recovered.size(), stillDown.size());
    }

    /** Eski kapsamlı fırtınanın emeklilik penceresi doldu mu (açılış + {@link #legacyRetireGraceMinutes}). */
    boolean legacyRetireDue() {
        return System.currentTimeMillis() - startedAtMs >= Math.max(0, legacyRetireGraceMinutes) * 60_000L;
    }

    /**
     * Eski (kuruluş geneli) fırtınayı TAKIM BAZINDA emekliye ayırır (2026-09-29, O-3).
     *
     * <p>Eskiden {@link #disband} ediliyordu: hâlâ-down üyeler {@code lastReAlertAt=null} ile çözülüyor ve ilk turda HER
     * biri tek tek INITIAL e-posta + push üretiyordu (bir takımda 10 üye = 10 alarm), hayalet üyeler de fırtına artık aktif
     * olmadığı için tek tek "ÇÖZÜLDÜ" alıyordu. Şimdi: hâlâ-down üyeler sahibi takım (+ grup kipinde grup) başına toplanır;
     * takım eşiği FARKLI HEDEF sayısıyla aşılıyorsa üyeler o takımın fırtınasına SESSİZCE taşınır (açılış postası YOK —
     * takım bu üyeler için eski fırtınanın postasını zaten aldı; toplu tekrar kadansı o andan sürer); aşılmıyorsa üyeler
     * "BİLDİRİLDİ" damgasıyla çözülür (tekrar INITIAL yok, bireysel günlük tekrar kadansı). Sahipsiz üye de bildirimsiz
     * çözülür. Kurtulan üyelerin TEK toplu çözümü {@link #sendStormRecovery} ile yalnız kurtulanı olan takıma ve yalnız
     * kendi üyeleriyle gider; çözüm push'u o takımda açılışı ALMIŞ olanlara (takım süzgeçli önceki alıcılar) — başka
     * takıma ASLA.
     */
    private void retireLegacy(AlertStorm legacy) {
        List<AlertEvent> members = alertEventRepo.findByStormId(legacy.getId());
        List<AlertEvent> stillDown = new ArrayList<>();   // kurtulanlar SONDA yeniden okunur (D-b7a)
        for (AlertEvent m : members) if (!Boolean.TRUE.equals(m.getResolved())) stillDown.add(m);
        String notifiedAt = legacy.getLastReAlertAt() != null ? legacy.getLastReAlertAt()
                : legacy.getCreatedAt() != null ? legacy.getCreatedAt() : now();
        boolean perGroup = appSettings.getBoolean(KEY_PER_GROUP, false);

        Map<String, List<AlertEvent>> byScope = new LinkedHashMap<>();
        List<AlertEvent> ownerless = new ArrayList<>();
        for (AlertEvent m : stillDown) {
            if (m.getTeamId() == null) { ownerless.add(m); continue; }
            String g = !perGroup ? null
                    : (m.getGroupName() != null && !m.getGroupName().isBlank() ? m.getGroupName() : UNGROUPED);
            byScope.computeIfAbsent(teamScopeKey(m.getTeamId(), g), k -> new ArrayList<>()).add(m);
        }
        int moved = 0, released = 0;
        List<Object[]> leaves = new ArrayList<>(stillDown.size());
        for (Map.Entry<String, List<AlertEvent>> e : byScope.entrySet()) {
            String key = e.getKey();
            List<AlertEvent> ms = e.getValue();
            AlertStorm target = null;
            int targetsNow = distinctTargets(ms);
            int thresholdNow = computeThreshold(teamOfScope(key));
            if (targetsNow >= thresholdNow) {
                boolean created = insertStormIfAbsent(key,
                        key.contains(GROUP_SCOPE_SEP) ? SCOPE_TYPE_TEAM_GROUP : SCOPE_TYPE_TEAM, ms,
                        new OpenSnapshot(teamOfScope(key), groupOfScope(key), thresholdNow, targetsNow, null));
                target = stormRepo.findByScopeKeyAndResolvedFalse(key).orElse(null);
                if (target != null && created) {
                    target.setLastReAlertAt(notifiedAt);   // toplu tekrar kadansı eski postadan sürer (erken tekrar yok)
                    // Açılış push'u / 7-24 postası ESKİ fırtınanın kimliğiyle gitti: çözüm push'unun "önceden alanlar"
                    // listesi ve 7/24 açılış kaydı bunu da sayar (D-b6) — sessiz taşıma 7/24'e yeni açılış postası atmaz.
                    target.setLegacyStormId(legacy.getId());
                    stormRepo.save(target);
                    recordMembers(target.getId(), ms, null, AlertStormMember.JOIN_LEGACY);
                    // Eski fırtına bu üyeleri zaten duyurmuştu — yeni fırtınada da "duyurulmuş" sayılır (erken tekrar yok).
                    markAnnounced(target.getId(), ms, notifiedAt);
                }
            }
            String movedAt = now();
            for (AlertEvent m : ms) {
                if (target != null && alertEventRepo.moveToStormIfOpen(m.getId(), legacy.getId(), target.getId()) == 1) {
                    moved++;
                    leave(leaves, legacy.getId(), m.getId(), movedAt, AlertStormMember.LEAVE_MOVED);
                } else {
                    alertEventRepo.releaseFromStormAsNotified(m.getId(), legacy.getId(), notifiedAt); released++;
                    leave(leaves, legacy.getId(), m.getId(), movedAt, AlertStormMember.LEAVE_RELEASED);
                }
            }
        }
        for (AlertEvent m : ownerless) {
            alertEventRepo.releaseFromStormAsNotified(m.getId(), legacy.getId(), notifiedAt); released++;
            leave(leaves, legacy.getId(), m.getId(), now(), AlertStormMember.LEAVE_RELEASED);
        }
        flushLeaves(legacy.getId(), leaves);

        legacy.setResolved(true);
        legacy.setResolvedAt(now());
        legacy.setResolveReason(RESOLVE_LEGACY);
        stormRepo.save(legacy);
        // D-b7(a) (2026-09-29): kurtulanlar işlemin SONUNDA yeniden okunur. Üye listesi baştan okunduktan sonra, taşıma /
        // çözme sürerken izleme turu bir üyeyi çözebilir: bireysel çözüm e-postası "fırtına aktif" diye bastırılmıştı, üye
        // ise baştaki okumada "hâlâ düşük" göründüğü için kurtulanlara girmiyordu → hiçbir çözüm bildirimi gitmiyordu.
        // Taşıma ve çözme yalnız AÇIK satırda koştuğundan geç çözülen üye eski fırtınaya bağlı kalır ve burada görünür.
        // Fırtına kapandıktan SONRA çözülen üye bireysel çözüm yolundan gider (isActive=false) — çift bildirim olmasın diye
        // yalnız kapanış anına kadar çözülenler sayılır. Sessiz kapanan üye sendStormRecovery'de ayrıca elenir (O-b2).
        String closedAt = legacy.getResolvedAt();
        List<AlertEvent> recoveredNow = new ArrayList<>();
        for (AlertEvent m : alertEventRepo.findByStormId(legacy.getId())) {
            if (!Boolean.TRUE.equals(m.getResolved())) continue;
            if (m.getResolvedAt() != null && closedAt != null && m.getResolvedAt().compareTo(closedAt) > 0) continue;
            recoveredNow.add(m);
        }
        Set<Long> recoveredIds = new java.util.HashSet<>();
        for (AlertEvent m : recoveredNow) if (m.getId() != null) recoveredIds.add(m.getId());
        List<AlertEvent> downNow = stillDown.stream().filter(m -> m.getId() == null || !recoveredIds.contains(m.getId())).toList();
        if (!recoveredNow.isEmpty()) sendStormRecovery(legacy, recoveredNow, downNow);
        log.info("🌩 Eski kapsamlı fırtına #{} emekliye ayrıldı ({}) — {} üye takım fırtınasına taşındı, {} üye bildirildi "
                        + "sayılıp çözüldü, {} kurtulan için takım bazlı toplu çözüm",
                legacy.getId(), legacy.getScopeKey(), moved, released, recoveredNow.size());
    }

    /**
     * Farklı HEDEF sayısı (2026-09-29, O-4): fırtına "çok hedef birden" demektir. Aynı host'un birden çok alarm türü
     * (ACCESSIBILITY + PORT_DOWN + DNS_FAILURE, aynı host'taki HTTP/İçerik URL'leri) tek hedef sayılır. Hedef = URL'nin
     * host'u, değilse alarmın alan adı/adı (küçük harf).
     */
    static int distinctTargets(java.util.Collection<AlertEvent> events) {
        Set<String> keys = new java.util.HashSet<>();
        if (events != null) for (AlertEvent e : events) if (e != null) keys.add(targetKey(e));
        return keys.size();
    }

    static String targetKey(AlertEvent e) {
        String d = e.getDomain() == null ? "" : e.getDomain().trim();
        int scheme = d.indexOf("://");
        if (scheme > 0) {
            String rest = d.substring(scheme + 3);
            int end = rest.length();
            for (char c : new char[]{'/', '?', '#'}) { int i = rest.indexOf(c); if (i >= 0 && i < end) end = i; }
            String hostPort = rest.substring(0, end);
            int at = hostPort.lastIndexOf('@');
            if (at >= 0) hostPort = hostPort.substring(at + 1);
            int colon = hostPort.startsWith("[") ? hostPort.indexOf("]:") + 1 : hostPort.lastIndexOf(':');
            d = colon > 0 ? hostPort.substring(0, colon) : hostPort;
        }
        return d.toLowerCase(java.util.Locale.ROOT);
    }

    // ── Eşik / pencere / denominatör ──────────────────────────────────────────────

    /** Eşik (FARKLI HEDEF sayısı): COUNT → max(2, value); PERCENT → max(3, ceil(value/100 × toplamAktifMonitör)).
     *  Round kuralı: ceil + taban (edge: %10 × 5 monitör = ceil(0.5)=1 → max(3,1)=3 — O-4).
     *  Kuruluş toplamıyla — yalnız ayar ekranı önizlemesi; fırtına kararı {@link #computeThreshold(Long)} kullanır. */
    public int computeThreshold() {
        return computeThreshold(null);
    }

    /**
     * Takım kapsamlı eşik (2026-09-29): YÜZDE paydası YALNIZ o takımın aktif izlemeleri — başka takımın filosu bu
     * takımın eşiğini büyütüp küçültmez. {@code teamId == null} → kuruluş toplamı (ayar ekranı önizlemesi).
     */
    public int computeThreshold(Long teamId) {
        String unit = appSettings.getString(KEY_UNIT, "COUNT");
        int value = appSettings.getInt(KEY_VALUE, 5);
        if ("PERCENT".equalsIgnoreCase(unit)) {
            long total = teamId == null ? totalActiveMonitors() : totalActiveMonitorsForTeam(teamId);
            int t = (int) Math.ceil((value / 100.0) * total);
            return Math.max(PERCENT_MIN_TARGETS, t);   // O-4: küçük takımda yüzde 1–2'ye inmesin
        }
        return Math.max(MIN_THRESHOLD, value);
    }

    private String windowSince() {
        int windowMin = clamp(appSettings.getInt(KEY_WINDOW, 5), 1, 15);
        return ISO.format(Instant.now().minusSeconds(windowMin * 60L));
    }

    public long totalActiveMonitors() {
        long nowMs = System.currentTimeMillis();
        if (cachedTotal > 0 && nowMs - cachedTotalAt < 60_000) return cachedTotal;
        long total;
        try {
            total = inventoryRepo.countByActiveTrue()
                    + httpRepo.countByActiveTrue()
                    + keywordRepo.countByActiveTrue()
                    + pingRepo.countByActiveTrue()
                    + domainRepo.countByActiveTrue()
                    + portRepo.countByStandaloneTrueAndActiveTrue()   // cert-türevi satırları çift saymamak için standalone
                    + dnsRepo.countByStandaloneTrueAndActiveTrue()
                    + (pageRepo != null ? pageRepo.countByActiveTrue() : 0)      // 9. tür (field-inject; test'te null → 0)
                    // 10. tür (sentetik). ATLANMIŞTI: SCRIPTED_FAIL storm ÜYESİ olabiliyor
                    // (DOWN_ALERT_TYPES içinde) ama paydada yoktu; yüzde eşiği olduğundan küçük
                    // çıkıyor, fırtına erken ilan ediliyor ve bireysel alarmlar erken bastırılıyordu.
                    + (scriptedRepo != null ? scriptedRepo.countByActiveTrue() : 0)
                    // Sayfa hızı: PAGESPEED_DOWN storm ÜYESİ (DOWN_ALERT_TYPES içinde) → paydada da olmalı.
                    // Payda eksik kalırsa yüzde olduğundan büyük çıkar, fırtına erken ilan edilir ve
                    // bireysel alarmlar gereksiz yere bastırılır.
                    + (pageSpeedRepo != null ? pageSpeedRepo.countByActiveTrue() : 0);
        } catch (Exception e) {
            return cachedTotal > 0 ? cachedTotal : 0;
        }
        cachedTotal = total;
        cachedTotalAt = nowMs;
        return total;
    }

    /**
     * Bir takımın aktif izleme sayısı — {@link #totalActiveMonitors} ile AYNI on kaynak (payda kapısı
     * {@code StormServiceTest.everyStormMemberTypeHasADenominatorSource}), yalnız o takımın satırları. Envanter-türevi
     * Port/DNS çift sayılmasın diye (kuruluş paydasındaki gibi) yalnız bağımsız satırlar; envanter satırı SY takımıyla.
     * 60 sn önbellekli (takım başına).
     */
    public long totalActiveMonitorsForTeam(Long teamId) {
        if (teamId == null) return totalActiveMonitors();
        long nowMs = System.currentTimeMillis();
        long[] c = teamTotalCache.get(teamId);
        if (c != null && nowMs - c[1] < 60_000) return c[0];
        long total;
        try {
            total = inventoryRepo.countByTeamIdAndActiveTrue(teamId)
                    + httpRepo.countByTeamIdAndActiveTrue(teamId)
                    + keywordRepo.countByTeamIdAndActiveTrue(teamId)
                    + pingRepo.countByTeamIdAndActiveTrue(teamId)
                    + domainRepo.countByTeamIdAndActiveTrue(teamId)
                    + portRepo.countByStandaloneTrueAndActiveTrueAndTeamId(teamId)
                    + dnsRepo.countByStandaloneTrueAndActiveTrueAndTeamId(teamId)
                    + (pageRepo != null ? pageRepo.countByTeamIdAndActiveTrue(teamId) : 0)
                    + (scriptedRepo != null ? scriptedRepo.countByTeamIdAndActiveTrue(teamId) : 0)
                    + (pageSpeedRepo != null ? pageSpeedRepo.countByTeamIdAndActiveTrue(teamId) : 0);
        } catch (Exception e) {
            return c != null ? c[0] : 0;
        }
        teamTotalCache.put(teamId, new long[]{total, nowMs});
        return total;
    }

    // ── Terfi / bağlama yardımcıları ──────────────────────────────────────────────

    /** Açılış anlık görüntüsü — fırtına satırına terfi INSERT'inde dondurulur (gözlem ekranı; 2026-10-01). */
    record OpenSnapshot(Long teamId, String group, int threshold, int targets, Long triggerEventId) {}

    /**
     * Scope-başına-tek-aktif kısmi UNIQUE indekse dayalı atomik terfi. rows==1 → biz oluşturduk (kazanan).
     * Anlık görüntü aynı cümlede yazılır (2026-10-01): eskiden ayrı bir UPDATE'ti — arada koşan yaşam döngüsü turu
     * fırtınayı okuyup {@code save} ile geri yazınca takım / eşik / tetikleyen kolonları NULL'a dönebiliyordu; ek bir
     * gidiş-dönüş de alarm gönderimini geciktiriyordu. İlk 7 bağ değişkeni (scope … last_member_at) sırası korunur.
     */
    private boolean insertStormIfAbsent(String scopeKey, String scopeType, List<AlertEvent> peers, OpenSnapshot snap) {
        String nowIso = now();
        String rootCause = commonRootCause(peers);
        try {
            int rows = jdbcTemplate.update(
                    "INSERT INTO alert_storms(scope_key, scope_type, resolved, member_count, root_cause, created_at, last_re_alert_at, last_member_at, "
                  + "team_id, group_name, threshold_unit, threshold_value, threshold_effective, window_minutes, quiet_minutes, "
                  + "targets_at_open, peak_targets, trigger_event_id) "
                  + "VALUES(?, ?, false, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (scope_key) WHERE resolved = false DO NOTHING",
                    scopeKey, scopeType, peers.size(), rootCause, nowIso, nowIso, nowIso,
                    snap.teamId(), snap.group(), thresholdUnit(), thresholdValue(), snap.threshold(), windowMinutes(), quietMinutes(),
                    snap.targets(), snap.targets(), snap.triggerEventId());
            return rows == 1;
        } catch (Exception e) {
            log.warn("Storm terfi INSERT hatası ({}): {}", scopeKey, e.getMessage());
            return false;
        }
    }

    /** Penceredeki açık DOWN eşleri storm'a bağla — çağıranın kaydettiği mevcut event ({@code skipId}) hariç. */
    private void linkPeers(List<AlertEvent> peers, Long stormId, Long skipId) {
        for (AlertEvent p : peers) {
            if (Objects.equals(p.getId(), skipId)) continue;                 // mevcut event → çağıran kaydeder
            if (Objects.equals(p.getStormId(), stormId)) continue;           // zaten bağlı
            alertEventRepo.linkToStormIfOpen(p.getId(), stormId);            // koşullu — çözülmüş peer'ı diriltmez (M6)
        }
    }

    // ── Gözlem kaydı (2026-09-30) — hepsi null-güvenli ve try/catch'li: bildirim kararını asla düşürmez ─────────────
    /** Kapanış nedeni kodları ({@code alert_storms.resolve_reason}); ön yüz {@code sf.reason.*} ile etiketler. */
    public static final String RESOLVE_FLOOR = "FLOOR", RESOLVE_SEALED = "SEALED",
            RESOLVE_DISABLED = "DISABLED", RESOLVE_LEGACY = "LEGACY_RETIRE";

    /**
     * Takım başına AKTİF izleme sayısı — TEK sorgu (2026-10-01, fırtına durum ekranı). {@link #totalActiveMonitorsForTeam}
     * takım başına 10 COUNT çalıştırır; durum ekranı 30 sn'de bir tüm takımlar için soruyordu (40 takım → 400 sorgu).
     * Kaynaklar ve kurallar {@code totalActiveMonitorsForTeam} ile birebir (Port/DNS yalnız bağımsız satır). 60 sn önbellek.
     */
    public static final String SQL_ACTIVE_BY_TEAM = "SELECT team_id, SUM(n) FROM ("
            + "SELECT team_id, COUNT(*) AS n FROM certificate_inventory WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM http_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM keyword_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM ping_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM domain_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM port_monitors WHERE standalone = true AND active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM dns_monitors WHERE standalone = true AND active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM page_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM scripted_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM pagespeed_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + ") x GROUP BY team_id";
    private volatile Map<Long, Long> activeByTeam;
    private volatile long activeByTeamAtMs;

    public Map<Long, Long> activeMonitorsByTeam() {
        Map<Long, Long> cur = activeByTeam;
        long nowMs = System.currentTimeMillis();
        if (cur != null && nowMs - activeByTeamAtMs < 60_000) return cur;
        synchronized (this) {   // tek-uçuş: süresi dolunca eşzamanlı ekranlar aynı anda yeniden hesaplamaz
            cur = activeByTeam;
            if (cur != null && System.currentTimeMillis() - activeByTeamAtMs < 60_000) return cur;
            try {
                Map<Long, Long> out = new java.util.HashMap<>();
                jdbcTemplate.query(SQL_ACTIVE_BY_TEAM, rs -> {
                    long team = rs.getLong(1);
                    if (!rs.wasNull()) out.put(team, rs.getLong(2));
                });
                activeByTeam = java.util.Collections.unmodifiableMap(out);
                activeByTeamAtMs = System.currentTimeMillis();
                return activeByTeam;
            } catch (Exception e) {
                log.debug("Takım başına aktif izleme sayısı okunamadı: {}", e.getMessage());
                return cur != null ? cur : Map.of();
            }
        }
    }

    /** Etkin eşik — takım toplamı DIŞARIDAN verilir (durum ekranı; {@link #computeThreshold(Long)} ile aynı kural). */
    public int thresholdForTotal(long teamTotal) {
        if ("PERCENT".equals(thresholdUnit())) {
            return Math.max(PERCENT_MIN_TARGETS, (int) Math.ceil((thresholdValue() / 100.0) * teamTotal));
        }
        return Math.max(MIN_THRESHOLD, thresholdValue());
    }

    /** Açık fırtınanın toplu tekrar postası aralığı (saat) — yaşam döngüsü ve durum ekranı aynı değeri kullanır. */
    public static final int RE_ALERT_HOURS = 24;

    /** Grup bazlı kapsam (takım + bildirim grubu) açık mı — durum ekranı kuralları anlatır. */
    public boolean perGroup() { return appSettings.getBoolean(KEY_PER_GROUP, false); }

    /** Sayım penceresi (dk, 1–15) — durum ekranı aynı değeri gösterir. */
    public int windowMinutes() { return clamp(appSettings.getInt(KEY_WINDOW, 5), 1, 15); }
    /** Eşik birimi (COUNT | PERCENT) — durum ekranı. */
    public String thresholdUnit() { return "PERCENT".equalsIgnoreCase(appSettings.getString(KEY_UNIT, "COUNT")) ? "PERCENT" : "COUNT"; }
    /** Eşik ayar değeri (adet ya da yüzde) — durum ekranı. */
    public int thresholdValue() { return appSettings.getInt(KEY_VALUE, 5); }

    // Üyelik SQL'i (2026-10-01, performans): eskiden üye başına exists + INSERT + commit (açılışta 2N gidiş-dönüş, 100
    // üyeli fırtınada ilk bildirim ~200 ms gecikiyordu) ve kapanışta üye başına UPDATE + commit vardı. Artık açılış tek
    // JDBC batch, katılım tek cümle, duyuru tek UPDATE (IN parçaları), kapanış tek batch. Çakışma (aynı fırtına + alarm)
    // UNIQUE ile sessizce yutulur — H2 (PostgreSQL modu) ve PostgreSQL ortak sözdizimi.
    public static final String SQL_MEMBER_INSERT = "INSERT INTO alert_storm_members(storm_id, alert_event_id, joined_at, join_kind) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
    public static final String SQL_MEMBER_LEFT = "UPDATE alert_storm_members SET left_at = ?, leave_kind = ? "
            + "WHERE storm_id = ? AND alert_event_id = ? AND left_at IS NULL";
    public static final String SQL_MEMBER_ANNOUNCED_PREFIX = "UPDATE alert_storm_members SET announced_at = ? "
            + "WHERE storm_id = ? AND announced_at IS NULL AND alert_event_id IN (";
    private static final int IN_CHUNK = 500;

    /** Tek üye (fırtına sürerken katılım) — tek cümle, çakışma yutulur. */
    private void recordMember(Long stormId, Long eventId, String joinKind) {
        if (stormId == null || eventId == null) return;
        try {
            jdbcTemplate.update(SQL_MEMBER_INSERT, stormId, eventId, now(), joinKind);
        } catch (Exception e) {
            log.debug("Storm #{} üyelik kaydı yazılamadı (alarm {}): {}", stormId, eventId, e.getMessage());
        }
    }

    /** Açılış / taşıma üyeleri — TEK batch: {@code triggerId} olan alarm TRIGGER, diğerleri {@code kind}. */
    private void recordMembers(Long stormId, List<AlertEvent> events, Long triggerId, String kind) {
        if (stormId == null || events == null || events.isEmpty()) return;
        String at = now();
        List<Object[]> rows = new ArrayList<>(events.size());
        Set<Long> seen = new java.util.HashSet<>();
        for (AlertEvent e : events) {
            if (e == null || e.getId() == null || !seen.add(e.getId())) continue;
            rows.add(new Object[]{stormId, e.getId(), at,
                    triggerId != null && triggerId.equals(e.getId()) ? AlertStormMember.JOIN_TRIGGER : kind});
        }
        if (rows.isEmpty()) return;
        try {
            jdbcTemplate.batchUpdate(SQL_MEMBER_INSERT, rows);
        } catch (Exception e) {
            log.debug("Storm #{} üyelik kayıtları yazılamadı ({} üye): {}", stormId, rows.size(), e.getMessage());
        }
    }

    /** Toplu posta gitti: listelenen üyelerin ilk duyuru anı — tek UPDATE (IN {@value #IN_CHUNK}'lük parçalar). */
    private void markAnnounced(Long stormId, List<AlertEvent> events, String at) {
        if (stormId == null || events == null || events.isEmpty()) return;
        List<Long> ids = new ArrayList<>(events.size());
        for (AlertEvent e : events) if (e != null && e.getId() != null) ids.add(e.getId());
        String stamp = at != null ? at : now();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            List<Long> part = ids.subList(i, Math.min(ids.size(), i + IN_CHUNK));
            Object[] args = new Object[part.size() + 2];
            args[0] = stamp; args[1] = stormId;
            for (int j = 0; j < part.size(); j++) args[j + 2] = part.get(j);
            try {
                jdbcTemplate.update(SQL_MEMBER_ANNOUNCED_PREFIX + String.join(",", java.util.Collections.nCopies(part.size(), "?")) + ")", args);
            } catch (Exception e) {
                log.debug("Storm #{} duyuru damgası yazılamadı: {}", stormId, e.getMessage());
            }
        }
    }

    /** Ayrılış kaydı biriktirici — kapanış yollarında üye başına satır, sonunda TEK batch ({@link #flushLeaves}). */
    private static void leave(List<Object[]> acc, Long stormId, Long eventId, String at, String kind) {
        if (stormId != null && eventId != null) acc.add(new Object[]{at, kind, stormId, eventId});
    }

    private void flushLeaves(Long stormId, List<Object[]> acc) {
        if (acc.isEmpty()) return;
        try {
            jdbcTemplate.batchUpdate(SQL_MEMBER_LEFT, acc);
        } catch (Exception e) {
            log.debug("Storm #{} üye ayrılışları yazılamadı ({} satır): {}", stormId, acc.size(), e.getMessage());
        }
    }

    /**
     * Üye sayacı — KOŞULLU atomik UPDATE (2026-09-29, D-14). Eskiden okunan varlığın tamamı kaydediliyordu: kilitsiz
     * {@code evaluate} ile kilitli {@code lifecycleSweep} yarışırsa çözülmüş fırtına {@code resolved=false} ile geri
     * yazılabiliyor, ikinci toplu çözüm postası gidebiliyordu.
     */
    private void bumpMemberCount(AlertStorm storm) {
        try {
            // last_member_at: sessiz pencere saati her katılımda yeniden başlar (2026-09-30).
            jdbcTemplate.update("UPDATE alert_storms SET member_count = COALESCE(member_count, 0) + 1, last_member_at = ? "
                    + "WHERE id = ? AND resolved = false", now(), storm.getId());
        } catch (Exception e) {
            log.debug("Storm #{} üye sayacı güncellenemedi: {}", storm.getId(), e.getMessage());
        }
    }

    private String commonRootCause(List<AlertEvent> peers) {
        String first = null;
        for (AlertEvent e : peers) {
            if (e.getAlertType() == null) continue;
            if (first == null) first = e.getAlertType();
            else if (!first.equals(e.getAlertType())) return "MIXED";
        }
        return first != null ? first : "MIXED";
    }

    /** Per-group modda monitörün grubunu lazy çözer (tip → uygun repo). Grupsuz/cert → null. */
    private String resolveGroup(AlertEvent event, Map<String, Object> ctx) {
        try {
            String d = event.getDomain();
            return switch (event.getAlertType()) {
                case EscalationService.TYPE_HTTP_DOWN ->
                        httpRepo.findFirstByUrlOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null);
                case EscalationService.TYPE_PAGE_DOWN, EscalationService.TYPE_PAGE_INTEGRITY ->
                        pageRepo != null ? pageRepo.findFirstByUrlOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null) : null;
                case EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW ->
                        scriptedRepo != null ? scriptedRepo.findFirstByNameOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null) : null;
                case EscalationService.TYPE_PAGESPEED_DOWN, EscalationService.TYPE_PAGESPEED_SLOW ->
                        pageSpeedRepo != null ? pageSpeedRepo.findFirstByUrlOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null) : null;
                // Yavaşlık da AYNI monitörden gelir: türü ayrı tutup grubu çözmemek alarmı
                // "Grupsuz" fırtına kovasına düşürürdü (Sayfa/Sentetik aileleri gibi çiftli).
                case EscalationService.TYPE_PING_DOWN, EscalationService.TYPE_PING_SLOW ->
                        pingRepo.findFirstByHostOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null);
                case EscalationService.TYPE_DNS_FAILURE ->
                        dnsRepo.findFirstByDomainOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null);
                case EscalationService.TYPE_PORT_DOWN -> {
                    Integer port = intFromCtx(ctx, "port");
                    yield port != null
                            ? portRepo.findFirstByHostAndPortOrderByIdAsc(d, port).map(m -> m.getGroupName()).orElse(null)
                            : null;
                }
                case EscalationService.TYPE_KEYWORD -> {
                    Object kw = ctx != null ? ctx.get("keyword") : null;
                    yield kw != null
                            ? keywordRepo.findFirstByUrlAndKeywordOrderByIdAsc(d, kw.toString()).map(m -> m.getGroupName()).orElse(null)
                            : null;
                }
                default -> null;   // ACCESSIBILITY → cert envanteri (grup yok)
            };
        } catch (Exception e) {
            log.debug("Storm grup çözümü başarısız: {} [{}] — {}", event.getDomain(), event.getAlertType(), e.getMessage());
            return null;
        }
    }

    // ── Toplu bildirim (e-posta + webhook) ────────────────────────────────────────

    private void sendStormAlert(AlertStorm storm, List<AlertEvent> downMembers, String trigger) {
        try {
            List<TeamDispatch> dispatches = resolveDispatches(downMembers);
            String prefix = "DAILY_REALERT".equals(trigger) ? "[RE-ALERT] " : "";

            Set<String> teamNames = new LinkedHashSet<>();
            Map<String, String> sentWebhooks = new LinkedHashMap<>();
            boolean anyEmail = false;

            for (TeamDispatch d : dispatches) {
                if (d.teamName() != null) teamNames.add(d.teamName());
                // TAKIM YALITIMI (2026-09-29): sayı, kapsam etiketi, kök-neden ve liste YALNIZ bu takımın üyelerinden.
                // Eskiden sayı hesap geneliydi ve etiket "Tüm monitörler"di: bir üyesi olan her takım kuruluşun toplam
                // arızasını kendi krizi sanıyordu (prod push'u: "15 monitör birden erişilemez - Tüm monitörler").
                // "+ N monitör daha" da takımın kendi üyelerinden (B6).
                int count = d.members().size();
                String label = dispatchLabel(d, storm);
                String rootCauseLabel = rootCauseLabel(commonRootCause(d.members()));
                String subject = prefix + "[Site Monitor 🌩 ALARM FIRTINASI] " + count + " monitör birden erişilemez — " + label;
                String whText = count + " monitör birden erişilemez (" + label + "). Kök-neden: " + rootCauseLabel + ".";
                List<String> targets = sampleTargets(d.members());
                int extra = Math.max(0, count - targets.size());
                // D-c7 (2026-09-29): fırtına seviyesi = takımın üyelerinin EN YÜKSEK seviyesi — push ({@link #stormPushLevel})
                // ile aynı kural ve tek sözlük; e-posta rozeti ve webhook eskiden sabit "KRİTİK"/"CRITICAL"di.
                String level = stormPushLevel(d.members());

                String mailStatus = "SKIPPED: alıcı yok";
                String html = null;
                if (!d.emails().isEmpty()) {
                    html = emailService.buildStormAlertHtml(
                            count, label, rootCauseLabel, storm.getCreatedAt(), targets, extra, level);
                    String text = emailService.buildStormAlertText(
                            count, label, rootCauseLabel, storm.getCreatedAt(), targets, extra, level);
                    mailStatus = emailService.sendHtml(d.emails().toArray(new String[0]), null, subject, html, text, List.of(), false, null);
                    anyEmail = true;
                }
                // Üye alarmların bildirim günlüğü (2026-09-30): fırtına postası her üyenin "Bildirimler" bölümünde ve SMTP
                // günlüğünde görünür — eskiden hiçbir kayıt yoktu.
                logStormMail(d, storm, "DAILY_REALERT".equals(trigger) ? TRIGGER_STORM_REALERT : TRIGGER_STORM_INITIAL,
                        subject, html, mailStatus);

                // PUSH — e-postanın eşleniği. Kanal bağımsız: mail_disabled push'u susturmaz. Kanal kapıları
                // (takım/tür/izleme bayrağı/sessiz saat/tekrar ayarı) ve günlük tekrar anahtarı UserPushService'te.
                enqueueStormPush(storm, d, trigger,
                        count + " monitör birden erişilemez — " + label + " · kök-neden: " + rootCauseLabel);

                // Webhook (Teams/Slack) — URL bazında dedup: aynı kanal iki kez mesaj almasın.
                for (Map.Entry<String, String> w : d.webhooks().entrySet()) {
                    if (sentWebhooks.putIfAbsent(w.getKey(), w.getValue()) != null) continue;
                    try { webhookService.send(w.getValue(), w.getKey(), subject, whText, level); }
                    catch (Exception ex) {
                        // Fırtına en kritik olaydır; teslim hatası DEBUG'da (prod=INFO) hiçbir yere
                        // yazılmıyordu. Adres maskelenir — webhook URL'inin kendisi kimlik bilgisidir.
                        log.warn("Storm #{} webhook başarısız [{}]: {}",
                                storm.getId(), WebhookService.maskUrl(w.getKey()), ex.getMessage());
                    }
                }
            }
            if (!anyEmail) log.warn("Storm #{} toplu alarm — alıcı yok, e-posta atlandı", storm.getId());

            // 7/24: takım e-postalarından BAĞIMSIZ; fırtına başına tek NOC e-postası (tekilleştirme serviste). Yalnız
            // izlemede açık onay (noc_notify) verilmiş üyeler — takım yalıtımında fırtına zaten tek takımın kümesidir.
            if (nocNotifications != null) {
                try { nocNotifications.onStormDispatched(storm, downMembers, scopeLabel(storm), rootCauseLabel(storm.getRootCause())); }
                catch (Exception ex) { log.warn("Storm #{} 7/24 bildirimi atlandı: {}", storm.getId(), ex.toString()); }
            }

            storm.setNotifiedTeams(String.join(", ", teamNames));
            storm.setMemberCount(downMembers.size());
            storm.setLastReAlertAt(now());
            stormRepo.save(storm);
            markAnnounced(storm.getId(), downMembers, storm.getLastReAlertAt());
        } catch (Exception e) {
            log.warn("Storm #{} toplu alarm gönderilemedi: {}", storm.getId(), e.getMessage());
        }
    }

    /**
     * Fırtına push seviyesi = takımın fırtına üyelerinin EN YÜKSEK alarm seviyesi (2026-09-29; eskiden sabit CRITICAL).
     *
     * <p>Push alıcıları seviyeyle çözülür (rol grubu asgari seviyesi: Yönetici HIGH+, Bölüm Başkanı / C-Level CRITICAL).
     * İzleme alarmları varsayılan WARNING açılır; fırtına sabit CRITICAL gidince bireysel alarmı HİÇ almayacak kademeler
     * de fırtına push'unu alıyordu — prod olayında 15 WARNING sentetik arıza takımların tüm kademelerine push oldu.
     * Fırtına yalnız BİLDİRİMİ gruplar; kitlesi bireysel alarmlarınkinden geniş olamaz. Açılış, tekrar ve çözüm aynı
     * kuralla (2026-09-28 simetrisi korunur; çözüm alıcıları zaten açılışı alanlardır — resolvePrior).
     */
    public static String stormPushLevel(List<AlertEvent> members) {
        int rank = 0;
        if (members != null) {
            for (AlertEvent m : members) {
                String lvl = m == null || m.getAlertLevel() == null ? null : m.getAlertLevel().toUpperCase(java.util.Locale.ROOT);
                rank = Math.max(rank, UserPushRecipientResolver.levelValue(lvl));
            }
        }
        return rank >= 3 ? "CRITICAL" : rank == 2 ? "HIGH" : "WARNING";
    }

    /**
     * Fırtına push'u — teslim hatası bildirimin geri kalanını ASLA düşürmesin.
     *
     * <p>Yalnız SY takımına (takımın SY olduğu üyelerle): bireysel push da yalnız SY takımına gider — push
     * çözümleyicisi SY takım-kapsamlıdır (EscalationService.resolveTeamFallback). Eskiden fırtına push'u UG
     * takımına da gidiyordu; bireysel alarmda push almayan UG üyeleri toplu alarmda alıyordu. E-posta/webhook UG'ye
     * gitmeye devam eder (bireysel e-posta da gider).
     */
    private void enqueueStormPush(AlertStorm storm, TeamDispatch d, String stormTrigger, String message) {
        if (userPushService == null || d.teamId() == null || d.pushMembers().isEmpty()) return;
        try {
            if (storm.getLegacyStormId() == null)
                userPushService.enqueueStormNotice(storm.getId(), d.teamId(), stormTrigger, stormPushLevel(d.pushMembers()),
                        d.pushMembers(), message);
            else   // O-3 sessiz taşıma: açılış push'u eski fırtınayla gitti — çözüm onun alıcılarını da sayar (D-b6)
                userPushService.enqueueStormNotice(storm.getId(), storm.getLegacyStormId(), d.teamId(), stormTrigger,
                        stormPushLevel(d.pushMembers()), d.pushMembers(), message);
        } catch (Exception e) {
            log.warn("Storm push'u gönderilemedi (takım {}): {}", d.teamId(), e.toString());
        }
    }

    private void sendStormRecovery(AlertStorm storm, List<AlertEvent> recoveredIn, List<AlertEvent> stillDown) {
        // O-b2 (2026-09-29): SESSİZ kapanan üye (izleme silindi / duraklatıldı / türün bildirimleri kapatıldı / envanter
        // pasif) KURTULMADI, susturuldu — toplu "N monitör kurtarıldı" e-postası/push'u/webhook'u/7-24 çözümü ONU saymaz.
        // Tek giriş noktası: resolveStorm, disband ve eski fırtınanın emekliye ayrılması buradan geçer. Kurtulan üyelerin
        // TAMAMI sessiz kapandıysa fırtına bildirimsiz kapanır.
        List<AlertEvent> recovered = recoveredIn.stream().filter(e -> !Boolean.TRUE.equals(e.getResolvedSilently())).toList();
        if (recovered.isEmpty()) {
            log.info("🌩 Storm #{}: kurtulan üyelerin tamamı sessiz kapandı ({} üye) — toplu çözüm bildirimi gönderilmedi",
                    storm.getId(), recoveredIn.size());
            return;
        }
        try {
            // Recovery alıcıları = tüm etkilenen üyeler (kurtulan + hâlâ-down) → herkes durumu görsün.
            List<AlertEvent> all = new ArrayList<>(recovered);
            all.addAll(stillDown);
            List<TeamDispatch> dispatches = resolveDispatches(all);

            Set<Long> recoveredIds = new java.util.HashSet<>();
            for (AlertEvent e : recovered) if (e.getId() != null) recoveredIds.add(e.getId());
            Map<String, String> sentWebhooks = new LinkedHashMap<>();

            for (TeamDispatch d : dispatches) {
                // Takıma özel bölme: kurtulan/hâlâ-down listeleri yalnız BU takımın üyelerinden.
                List<AlertEvent> mineRecovered = new ArrayList<>();
                List<AlertEvent> mineStillDown = new ArrayList<>();
                for (AlertEvent m : d.members()) {
                    if (m.getId() != null && recoveredIds.contains(m.getId())) mineRecovered.add(m);
                    else mineStillDown.add(m);
                }
                // TAKIM YALITIMI (2026-09-29): hiçbir üyesi kurtulmamış takıma "fırtına sona erdi" gitmez — eskiden
                // hesap geneli "N monitör kurtarıldı" sayısıyla gidiyordu. Hâlâ-down üyeleri fırtınadan çözülür ve
                // bireysel alarm hattına döner (hiçbir şey sessizce kaybolmaz).
                if (mineRecovered.isEmpty()) continue;
                String scopeLabel = dispatchLabel(d, storm);
                String subject = "[Site Monitor ✅ ÇÖZÜLDÜ] Alarm fırtınası sona erdi — "
                        + mineRecovered.size() + " monitör kurtarıldı — " + scopeLabel;
                List<String> targets = sampleTargets(mineRecovered);
                int extra = Math.max(0, mineRecovered.size() - targets.size());   // takım kapsamlı (bkz. alarm yolu)
                // Hâlâ-down üyeler ADLARIYLA listelenir: eskiden yalnız sayı ("2 hâlâ izlemede")
                // gidiyor, hangileri olduğu ne mailde ne webhook'ta söyleniyordu.
                List<String> stillDownTargets = sampleTargets(mineStillDown);

                String mailStatus = "SKIPPED: alıcı yok";
                String html = null;
                if (!d.emails().isEmpty()) {
                    // "Hâlâ erişilemeyen" sayacı da TAKIM kapsamlı: listenin kendisi öyle ve
                    // webhook partı (aşağıda) zaten mineStillDown kullanıyordu — e-posta hesap
                    // geneli sayı geçtiği için aynı olay iki kanalda farklı rakam veriyordu.
                    html = emailService.buildStormRecoveryHtml(
                            mineRecovered.size(), mineStillDown.size(), scopeLabel,
                            storm.getCreatedAt(), storm.getResolvedAt(), targets, extra, stillDownTargets);
                    String text = emailService.buildStormRecoveryText(
                            mineRecovered.size(), mineStillDown.size(), scopeLabel,
                            storm.getCreatedAt(), storm.getResolvedAt(), targets, extra, stillDownTargets);
                    mailStatus = emailService.sendHtml(d.emails().toArray(new String[0]), null, subject, html, text, List.of(), false, null);
                }
                // Çözüm postası yalnız KURTULAN üyelerin günlüğüne (hâlâ-down üye için bu bir çözüm değildir).
                logStormMail(new TeamDispatch(d.teamId(), d.teamName(), d.emails(), d.webhooks(), mineRecovered, List.of()),
                        storm, TRIGGER_STORM_RESOLVE, subject, html, mailStatus);

                enqueueStormPush(storm, d, "RESOLVE",
                        mineRecovered.size() + " monitör kurtarıldı — " + scopeLabel
                                + (mineStillDown.isEmpty() ? "" : " · hâlâ erişilemeyen: " + mineStillDown.size()));

                // Webhook (Teams/Slack) — açılışın AYNASI. Eskiden yalnız e-posta gidiyordu: aynı kişi
                // Teams'te "🌩 12 monitör birden erişilemez" görüyor, "✅ fırtına sona erdi" mesajını
                // hiç almıyordu. Kanal, olayın yalnız yarısını anlatıyordu.
                String whText = mineRecovered.size() + " monitör kurtarıldı (" + scopeLabel + ")."
                        + (mineStillDown.isEmpty() ? "" : " Hâlâ erişilemeyen: " + mineStillDown.size()
                            + " (" + String.join(", ", stillDownTargets) + ").");
                for (Map.Entry<String, String> w : d.webhooks().entrySet()) {
                    if (sentWebhooks.putIfAbsent(w.getKey(), w.getValue()) != null) continue;
                    try { webhookService.send(w.getValue(), w.getKey(), subject, whText, "INFO"); }
                    catch (Exception ex) {
                        log.warn("Storm #{} recovery webhook başarısız [{}]: {}",
                                storm.getId(), WebhookService.maskUrl(w.getKey()), ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Storm #{} toplu recovery gönderilemedi: {}", storm.getId(), e.getMessage());
        }
        // 7/24: takım yolundaki bir hatadan BAĞIMSIZ — açılışı NOC'a gitmiş kurtulanlar için tek "ÇÖZÜLDÜ".
        if (nocNotifications != null) {
            try { nocNotifications.onStormRecovered(storm, recovered, stillDown); }
            catch (Exception ex) { log.warn("Storm #{} 7/24 çözüm bildirimi atlandı: {}", storm.getId(), ex.toString()); }
        }
    }

    private List<TeamDispatch> resolveDispatches(List<AlertEvent> members) {
        Map<Long, TeamInfo> teamCache = new HashMap<>();
        Map<Long, Dispatch> byTeam = new LinkedHashMap<>();

        // Fırtına, tanım gereği onlarca-yüzlerce açık alarmın aynı anda toplandığı andır: alıcı
        // çözümü DB'nin en yüklü olduğu dakikada koşar. Envanter ve kontak sorguları da takımlar
        // gibi önbelleklenir — eskiden yalnız teamCache vardı, diğer ikisi üye başına tekil
        // SELECT atıyordu.
        Map<String, java.util.Optional<com.sitemonitor.model.CertificateInventory>> invCache = new HashMap<>();
        Map<String, List<EscalationContact>> contactCache = new HashMap<>();

        for (AlertEvent m : members) {
            // Bireysel yolla AYNI karar (prod kapısı 2026-09-25, O-1): bağlamdaki team_id damgası da bağımsız
            // izleme sayılır (PORT/DNS); damgalı alarm envanterin UG takımını almaz (açılış yolu ugTeamId=null).
            boolean stamped = EscalationService.hasTeamStamp(m);
            boolean teamOnly = EscalationService.teamOnlyRecipients(m);
            Long teamId = m.getTeamId();
            Long ugTeamId = null;
            // Bağımsız izleme (tür ya da açılış bağlamındaki damga) envanterden takım/UG ALMAZ (2026-09-28): host başka
            // takımın envanterindeyse toplu posta o takıma gidiyordu. Takım yalnız olayın kendi damgasından.
            if (!teamOnly && !EscalationService.isStandaloneEvent(m)) {
                var inv = invCache.computeIfAbsent(String.valueOf(m.getDomain()),
                        k -> inventoryRepo.findByDomain(m.getDomain()));
                if (inv.isPresent()) {
                    if (teamId == null) teamId = inv.get().getTeamId();
                    if (!stamped) ugTeamId = inv.get().getUgTeamId();
                }
            }
            boolean mailOff = mailDisabled(m);
            for (Long tid : new Long[]{teamId, ugTeamId}) {
                if (tid == null) continue;
                Dispatch d = byTeam.computeIfAbsent(tid, id -> {
                    TeamInfo info = teamCache.computeIfAbsent(id, x -> teamRepo.findById(x)
                            .map(t -> new TeamInfo(resolveTeamEmails(x, t.getEmail()), t.getName()))
                            .orElse(TeamInfo.EMPTY));
                    return new Dispatch(id, info);
                });
                d.members.add(m);
                if (!mailOff) d.anyMailEligible = true;
                // "Her sahip takım kendi kişisi" (2026-09-28): takımın dağıtımına YALNIZ o takımın kişileri girer.
                // Eskiden SY takımının kişileri UG takımının postasına da ekleniyordu (UG'nin kendi kişileri hiç).
                List<EscalationContact> contacts = teamOnly ? List.of()
                        : contactCache.computeIfAbsent(m.getAlertLevel() + "|" + tid,
                                k -> contactsForLevel(m.getAlertLevel(), tid));
                for (EscalationContact c : contacts) {
                    if (c.getEmail() != null && !c.getEmail().isBlank()) d.contactEmails.add(c.getEmail().trim());
                    if (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank())
                        d.webhooks.putIfAbsent(c.getWebhookUrl(), c.getWebhookType());
                }
            }
            // Push yalnız SY takımına (bkz. enqueueStormPush) — üye, SY takımının push listesine girer.
            if (teamId != null) byTeam.get(teamId).pushMembers.add(m);
        }

        List<TeamDispatch> out = new ArrayList<>();
        Set<String> seenEmail = new LinkedHashSet<>();
        for (Dispatch d : byTeam.values()) {
            List<String> emails = new ArrayList<>();
            if (d.anyMailEligible) {
                for (String e : d.info.emails()) if (seenEmail.add(e.toLowerCase())) emails.add(e);
                for (String e : d.contactEmails) if (seenEmail.add(e.toLowerCase())) emails.add(e);
            }
            String name = d.info.name() != null && !d.info.name().isBlank() ? d.info.name().trim() : null;
            out.add(new TeamDispatch(d.teamId, name, emails, d.webhooks, d.members, d.pushMembers));
        }
        return out;
    }

    /** İzlemenin "E-posta" kanalı kapalı mı — olayın alarm-anı ctx damgasından. */
    private boolean mailDisabled(AlertEvent m) {
        String json = m.getContextJson();
        if (json == null || json.isBlank() || objectMapper == null) return false;
        try {
            Map<?, ?> ctx = objectMapper.readValue(json, Map.class);
            return Boolean.TRUE.equals(ctx.get("mail_disabled"));
        } catch (Exception e) {
            return false;   // bozuk ctx bastırma SAYILMAZ — bildirim kaybetmektense fazladan gönder
        }
    }

    /** resolveDispatches iç birikteci (takım başına). */
    private static final class Dispatch {
        final Long teamId; final TeamInfo info;
        final List<AlertEvent> members = new ArrayList<>();
        /** Takımın SY takımı olduğu üyeler — push yalnız bunlar için (UG üyeliği push üretmez). */
        final List<AlertEvent> pushMembers = new ArrayList<>();
        final Set<String> contactEmails = new LinkedHashSet<>();
        final Map<String, String> webhooks = new LinkedHashMap<>();
        boolean anyMailEligible = false;
        Dispatch(Long teamId, TeamInfo info) { this.teamId = teamId; this.info = info; }
    }

    /**
     * Bir takimi toplu-kesinti alicilarina ekler.
     *
     * <p><b>Damga KULLANILMAZ:</b> storm postasi bircok monitoru TEK maile topluyor; iclerinden
     * birinin bildirim grubunu secmek keyfi olurdu. Bu yolda zincir yalnizca
     * "takimin varsayilan grubu -> {@code Team.email}" seklindedir.
     *
     * <p>Cozum takim onbellegi icinde yapilir: tur basina onlarca uye olabilir, takim basina TEK
     * sorgu kalir (N+1 yok).
     */
    private void addTeam(Long teamId, Map<Long, TeamInfo> cache,
                         Set<String> seenEmail, List<String> emails, Set<String> teamNames) {
        if (teamId == null) return;
        TeamInfo info = cache.computeIfAbsent(teamId, id -> teamRepo.findById(id)
                .map(t -> new TeamInfo(resolveTeamEmails(id, t.getEmail()), t.getName()))
                .orElse(TeamInfo.EMPTY));
        boolean added = false;
        for (String e : info.emails()) {
            if (seenEmail.add(e.toLowerCase())) { emails.add(e); added = true; }
        }
        if (added && info.name != null && !info.name.isBlank()) teamNames.add(info.name.trim());
    }

    /** Grup devredeyse grubun adresleri, degilse {@code Team.email} (bugunku davranis). */
    private List<String> resolveTeamEmails(Long teamId, String teamEmail) {
        NotificationGroupService.Override ov = notificationGroups.overrideFor(teamId);
        if (ov != null && ov.applies()) return ov.emails();
        String e = teamEmail != null ? teamEmail.trim() : "";
        return e.isBlank() ? List.of() : List.of(e);
    }

    /**
     * Bireysel yolla AYNI kapsam ({@link EscalationContactScope}, 2026-09-28): YALNIZ verilen takımın kontakları;
     * takım yoksa kimse (takımsız kontak hiçbir yolda alıcı değil). Çağıran her dağıtım takımı (SY ve UG) için ayrı
     * sorar — "her sahip takım kendi kişisi". Eskiden buradaki kopya da takımda kontak yoksa takım
     * süzgeçsiz sorgulara düşüyor, toplu kesinti postasına TÜM takımların müdürlerini ekliyordu.
     */
    private List<EscalationContact> contactsForLevel(String level, Long teamId) {
        return EscalationContactScope.forLevel(contactRepo, level, teamId);
    }

    // isTeamOnly kaldırıldı (Y4): EscalationService.teamOnlyRecipients tek doğruluk kaynağı.
    // Buradaki 3-tipli kopya PAGE/SCRIPTED/PAGESPEED tiplerini kaçırıyor, geniş kesintide bu
    // monitörlerin storm'una müdürleri ekliyordu.

    private List<String> sampleTargets(List<AlertEvent> members) {
        final int MAX = 12;
        List<String> out = new ArrayList<>();
        for (AlertEvent m : members) {
            if (out.size() >= MAX) break;
            if (m.getDomain() != null) out.add(m.getDomain());
        }
        return out;
    }

    /**
     * Fırtınanın kapsam etiketi (7/24 postası) — sahibi takımın ADI (+ grup kipinde grup). "Tüm monitörler" YOK
     * (2026-09-29). Eski (takımsız) kapsam yalnız dağıtılırken görülür.
     */
    private String scopeLabel(AlertStorm storm) {
        Long teamId = teamOfScope(storm.getScopeKey());
        if (teamId == null) return "Eski fırtına kapsamı (" + storm.getScopeKey() + ")";
        String team = teamRepo.findById(teamId).map(t -> t.getName())
                .filter(n -> n != null && !n.isBlank()).map(String::trim).orElse("Takım #" + teamId);
        String group = groupOfScope(storm.getScopeKey());
        return group == null ? team : team + " · " + (UNGROUPED.equals(group) ? "Grupsuz" : group);
    }

    /** Bir takım dağıtımının kapsam etiketi — O takımın adı (+ grup kipinde üyelerin grubu). Başka takımın adı YOK. */
    private static String dispatchLabel(TeamDispatch d, AlertStorm storm) {
        String team = d.teamName() != null ? d.teamName() : "Takım #" + d.teamId();
        if (!SCOPE_TYPE_TEAM_GROUP.equals(storm.getScopeType())) return team;
        for (AlertEvent m : d.members()) {
            String g = m.getGroupName();
            if (g != null && !g.isBlank()) return team + " · " + (UNGROUPED.equals(g) ? "Grupsuz" : g);
        }
        return team;
    }

    /** Takım kapsamlı anahtar; kolon (200) taşarsa kırpılır + özet eklenir (aynı grup her zaman aynı anahtar). */
    static String teamScopeKey(Long teamId, String group) {
        String key = TEAM_SCOPE_PREFIX + teamId + (group != null ? GROUP_SCOPE_SEP + group : "");
        if (key.length() <= SCOPE_KEY_MAX) return key;
        String digest = Integer.toHexString(key.hashCode());
        return key.substring(0, SCOPE_KEY_MAX - digest.length() - 1) + "#" + digest;
    }

    /** Anahtardaki takım kimliği; eski (ACCOUNT / takımsız grup) anahtarda null. */
    static Long teamOfScope(String scopeKey) {
        if (scopeKey == null || !scopeKey.startsWith(TEAM_SCOPE_PREFIX)) return null;
        int i = TEAM_SCOPE_PREFIX.length(), j = i;
        while (j < scopeKey.length() && Character.isDigit(scopeKey.charAt(j))) j++;
        if (j == i) return null;
        try { return Long.valueOf(scopeKey.substring(i, j)); } catch (NumberFormatException e) { return null; }
    }

    private static String groupOfScope(String scopeKey) {
        int at = scopeKey == null ? -1 : scopeKey.indexOf(GROUP_SCOPE_SEP);
        return at < 0 ? null : scopeKey.substring(at + GROUP_SCOPE_SEP.length());
    }

    /** Takım kapsamlı (2026-09-29 sonrası) fırtına mı — değilse yaşam döngüsü onu dağıtır. */
    static boolean isTeamScoped(AlertStorm storm) {
        return storm != null && teamOfScope(storm.getScopeKey()) != null;
    }

    /**
     * Fırtınanın kök-neden etiketi (toplu alarm e-postasının ve webhook metninin başlığı).
     *
     * <p>Kaynak küme {@link EscalationService#DOWN_ALERT_TYPES} — fırtınaya girebilen TEK küme.
     * Burada altı tip yazılıydı; o kümeye sonradan eklenen {@code PAGE_DOWN},
     * {@code SCRIPTED_FAIL} ve {@code PAGESPEED_DOWN} atlanmıştı ve {@code default} dalına
     * düşüyorlardı: beş Sayfa Hızı monitörü aynı anda çöktüğünde toplu alarm "Kök-neden:
     * Kesinti." diyordu — hangi izleme ailesinin gittiği hiçbir yerde yazmıyordu, oysa
     * HTTP/Port/Ping/DNS fırtınalarında yazıyor. {@code StormServiceTest} bu eşlemeyi
     * DOWN_ALERT_TYPES üzerinden gezip pinliyor, yani 11. tip de kapıya takılır.
     */
    String rootCauseLabel(String rootCause) {   // paket-özel: kapı testi doğrudan çağırır
        return switch (rootCause != null ? rootCause : "") {
            case EscalationService.TYPE_ACCESSIBILITY -> "Erişim Kesintisi";
            case EscalationService.TYPE_HTTP_DOWN     -> "HTTP/Website Erişilemez";
            case EscalationService.TYPE_PORT_DOWN     -> "Port Kesintisi";
            case EscalationService.TYPE_PING_DOWN     -> "Ping Yanıtsız";
            case EscalationService.TYPE_DNS_FAILURE   -> "DNS Çözümleme Hatası";
            case EscalationService.TYPE_KEYWORD       -> "İçerik Doğrulama";
            case EscalationService.TYPE_PAGE_DOWN     -> "Sayfa Erişilemez";
            case EscalationService.TYPE_SCRIPTED_FAIL -> "Sentetik Senaryo Başarısız";
            case EscalationService.TYPE_PAGESPEED_DOWN-> "Sayfa Hızı Erişilemez";
            case "MIXED"                              -> "Karışık (çok tipli)";
            default                                   -> "Kesinti";
        };
    }

    public boolean isEnabled() {
        return appSettings.getBoolean(KEY_ENABLED, true);   // default AÇIK
    }

    // ── Küçük yardımcılar ─────────────────────────────────────────────────────────

    /** Bir takıma yapılacak fırtına bildirimi: adresler, webhook'lar, O TAKIMA ait üyeler ve SY olduğu üyeler (push). */
    private record TeamDispatch(Long teamId, String teamName, List<String> emails,
                                Map<String, String> webhooks, List<AlertEvent> members,
                                List<AlertEvent> pushMembers) {}
    private record TeamInfo(List<String> emails, String name) {
        static final TeamInfo EMPTY = new TeamInfo(List.of(), null);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static Integer intFromCtx(Map<String, Object> ctx, String key) {
        Object v = ctx != null ? ctx.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        try { return v != null ? Integer.parseInt(v.toString()) : null; } catch (Exception e) { return null; }
    }

    private String now() { return ISO.format(Instant.now()); }

    // ── Dağıtık kilit (StormService lifecycle tek-yazar) — SchedulerService pattern'inin kopyası
    //    (oraya inject etmek döngüsel bağımlılık yaratır: EscalationService→StormService→SchedulerService→EscalationService). ──
    private boolean tryLock() {
        String lockName = "storm-sweep";
        try {
            String nowIso = now();
            String until = ISO.format(Instant.now().plusSeconds(LOCK_TTL_SECONDS));
            jdbcTemplate.update("DELETE FROM scheduler_lock WHERE name = ? AND locked_until < ?", lockName, nowIso);
            jdbcTemplate.update("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)",
                    lockName, INSTANCE_ID, until);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;   // başka pod yönetiyor — tipli yakalama, mesaj metnine bağımlılık yok
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // Kilit TABLOSU yok (ilk açılışta ddl-auto henüz koşmadı) — bilinçli tek-pod geri düşüşü.
            log.warn("Storm kilit tablosu erişilemez (HA degraded): {}", e.getMessage());
            return true;
        } catch (Exception e) {
            if (isUniqueViolation(e)) return false;   // sürücü tipli istisna vermediyse metin yedeği
            // SchedulerService.tryAcquireSchedulerLock ile aynı kural: geçici DB hatasında (deadlock,
            // statement-timeout, bağlantı kopması) fail-OPEN çok-pod'da ÇİFT lifecycle sweep = çift
            // fırtına mail/webhook demekti. Güvenli taraf bu turu ATLAMAK — 30 sn sonra tekrar denenir.
            log.warn("Storm kilidi alınamadı — bu tur atlanıyor (güvenli taraf): {}", e.getMessage());
            return false;
        }
    }

    static boolean isUniqueViolation(Exception e) {
        String m = e.getMessage();
        return m != null && (m.contains("UNIQUE") || m.contains("unique") || m.contains("duplicate"));
    }

    private void releaseLock() {
        try {
            jdbcTemplate.update("DELETE FROM scheduler_lock WHERE name = ? AND locked_by = ?", "storm-sweep", INSTANCE_ID);
        } catch (Exception e) {
            log.debug("Storm kilidi bırakılamadı: {}", e.getMessage());
        }
    }

    private static String resolveHostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }
}
