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
 *
 * <p><b>PUSH FIRTINAYA DEVREDİLMEYEBİLİR (2026-10-03, {@link #KEY_PUSH_INDIVIDUAL}; 2026-10-04 kullanıcı kararıyla varsayılan KAPALI = push fırtınaya devredilir; AÇIKKEN aşağıdaki kip).</b> Fırtına
 * yalnız e-postayı (ve webhook / 7-24 postasını) gruplar; kişi push'u her üye alarm için tek tek, alarmların açılış
 * sırasıyla gider ({@code EscalationService} fırtına dalı: açılış + seviye artışı; buradaki günlük tekrar: üye başına
 * hatırlatma). Toplu fırtına push'u yalnız ayar KAPALIYKEN üretilir (ya da ayar fırtına sürerken açıldıysa, önceden toplu
 * push almış olanlara çözüm simetrisi için).
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

    /** Veritabanı sorgu türü (11. tür, 2026-10-31) — alan enjeksiyonu, isteğe bağlı (elle kurulan testte null → 0). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.DbQueryMonitorRepository dbQueryRepo;

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

    /**
     * Sistem Bakım Modu (2026-10-02, kullanıcı kararı) — "Bildirimler bakım boyunca sussun" açık bakım AKTİFKEN: yeni fırtına
     * KURULMAZ ve fırtınaya üye BAĞLANMAZ (alarm bireysel hatta gider, orada susturulup bakım sonunda telafi edilir); süren
     * fırtınaların toplu postaları (tekrar / çözüm; e-posta, webhook, push, 7/24) gönderilmez, her üyenin günlüğüne
     * {@code SKIPPED: sistem bakımı} satırı düşer. Alan enjeksiyonu + isteğe bağlı: yokken davranış bayt bayt bugünkü.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SystemMaintenanceService systemMaintenance;

    /** Test kancası. */
    void setSystemMaintenance(SystemMaintenanceService s) { this.systemMaintenance = s; }

    private boolean systemMaintenanceMuted() {
        try {
            return systemMaintenance != null && systemMaintenance.notificationsMuted();
        } catch (Exception e) {
            return false;
        }
    }

    /** Susturulan fırtına postasının izi: takım başına üye alarmların günlüğüne SKIPPED satırı + bakım sayacı. */
    private void recordStormMaintenanceSkip(AlertStorm storm, List<AlertEvent> members, String trigger) {
        try {
            for (TeamDispatch d : resolveDispatches(members)) {
                logStormMail(d, storm, trigger, "Sistem bakımı — fırtına #" + storm.getId() + " bildirimi susturuldu", null,
                        SystemMaintenanceService.STATUS_SKIPPED);
            }
        } catch (Exception e) {
            log.warn("Storm #{} sistem bakımı izi yazılamadı: {}", storm.getId(), e.getMessage());
        }
        try { systemMaintenance.noteSuppressed(null, trigger); } catch (Exception ignored) { /* sayaç best-effort */ }
        log.info("🌩 Storm #{} {} postası sistem bakımı nedeniyle susturuldu ({} üye)", storm.getId(), trigger,
                members == null ? 0 : members.size());
    }

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

    /** Pasif kullanıcı süzgeci (2026-10-02): pasif kullanıcıya bağlı kişi fırtına postasına / webhook'una girmez.
     *  Alan enjeksiyonu, null-güvenli — yokken liste aynen döner. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InactiveRecipientGuard inactiveGuard;

    void setInactiveGuard(InactiveRecipientGuard guard) { this.inactiveGuard = guard; }

    /**
     * Takım sessiz saati (2026-10-03) — yalnız bireysel push kipinde ({@link #KEY_PUSH_INDIVIDUAL}) üyelerin günlük
     * hatırlatma push'u için: bireysel hattın kuralıyla aynı karar ({@link TeamQuietHoursService#defersPush}), özet kaydı
     * yazılmaz (e-postayı fırtına postası taşır). Alan enjeksiyonu + isteğe bağlı: yokken pencere yok sayılır.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TeamQuietHoursService quietHours;

    /** Test kancası. */
    void setQuietHours(TeamQuietHoursService q) { this.quietHours = q; }


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
    /**
     * Push fırtınaya DEVREDİLMESİN (2026-10-03; 2026-10-04 kullanıcı kararıyla varsayılan KAPALI — açıkken): fırtına yalnız E-POSTAYI gruplar —
     * üye alarmların push'u alarm başına, açılış sırasıyla bireysel gider (açılış / seviye artışı / günlük hatırlatma /
     * çözüm); toplu fırtına push'u (açılış / tekrar) gönderilmez. KAPALI = 2026-10-02'ye kadarki davranış bayt bayt
     * ({@code SKIPPED_STORM} karar satırı + toplu fırtına push'u).
     */
    public static final String KEY_PUSH_INDIVIDUAL = "site.monitor.storm.push-individual";

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

    /**
     * Kilit sahibi (2026-10-21, inceleme CORE-4): süreç başına TEKİL — {@code SchedulerService.instanceId()} (host adı +
     * rastgele ek). Eskiden {@code hostname + "-storm"} idi ve host adı okunamayınca "unknown-storm"a düşüyordu: iki pod
     * birbirinin kilidini tazeleyip bırakabiliyordu. Statik çağrı — SchedulerService bean'ine bağımlılık yok (döngü olurdu).
     */
    static final String INSTANCE_ID = SchedulerService.instanceId() + "-storm";
    private static final int LOCK_TTL_SECONDS = 90;
    static final String LOCK_NAME = "storm-sweep";
    /** Kilit sahibi kimliği — pod başına; iki pod'u tek JVM'de canlandıran test ayrı kimlik verir. */
    String lockOwner = INSTANCE_ID;

    /**
     * Yenilenen küme kilidi (2026-10-21, çok pod R1-A / inceleme R-2): varsa fırtına kilidi BURADAN alınır — arka planda
     * yenilenir, kayıp {@code held()} ile görülür, kapanışta yeni kilit verilmez ve tutulan bırakılır. Yoksa (bağlamsız birim
     * testleri) bu sınıfın kendi SQL'i (aynı satır, aynı anlam).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.sitemonitor.service.schedule.ClusterLockService lockService;

    /** Kapanış başladı ({@code ContextClosedEvent}) — yeni fırtına işi başlamaz (inceleme R-2). Geri alınmaz. */
    private volatile boolean shuttingDown;

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void onContextClosed() {
        shuttingDown = true;
    }

    /** Pod kapanıyor mu: kendi bayrağı ya da küme kilidi servisi yeni kilit vermiyor. */
    boolean shuttingDown() {
        return shuttingDown || (lockService != null && lockService.acquiringStopped());
    }
    /** Tur içinde kilit en geç bu kadar ms'de bir tazelenir (TTL'in üçte biri); test 0 verip her fırtınada tazeletir. */
    long lockRenewEveryMs = LOCK_TTL_SECONDS * 1000L / 3;

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
        // Sistem bakımı (2026-10-02): susturulan bakımda fırtına kurulmaz / üye bağlanmaz — bireysel hat susturur ve iz bırakır.
        if (systemMaintenanceMuted()) return StormAction.SEND_INDIVIDUAL;
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
                if (storm.getCloseState() != null) {
                    log.info("🌩 Storm #{} kapanıyor — {} [{}] bireysel gönderiliyor", storm.getId(), event.getDomain(),
                            event.getAlertType());
                    return StormAction.SEND_INDIVIDUAL;
                }
                if (isSealed(storm, now())) {
                    log.info("🌩 Storm #{} mühürlü (son üye {} — sessiz pencere {} dk doldu) — {} [{}] bireysel gönderiliyor",
                            storm.getId(), memberClock(storm), quietMinutes(), event.getDomain(), event.getAlertType());
                    return StormAction.SEND_INDIVIDUAL;
                }
                // KAPANIŞ YARIŞI (2026-10-09): sayaç KOŞULLU (resolved=false). 0 satır = fırtına bulunduğu an ile bağlanma
                // anı arasında yaşam döngüsü onu kapattı (FLOOR/SEALED). Bağlansaydı kapanışın üye listesinde olmayan alarm
                // ne duyurulur ne bağı koparılırdı; lastReAlertAt=now damgasıyla bir yeniden uyarı aralığı (~24 sa) sessiz
                // kalırdı. Bağlanma YOK, stormId damgalanmaz → çağıran bireysel İLK bildirimi şimdi gönderir.
                if (bumpMemberCount(storm) <= 0) {
                    event.setStormId(null);
                    log.info("🌩 Storm #{} bağlanma anında kapanmış — {} [{}] fırtınaya bağlanmadı, bireysel gönderiliyor",
                            storm.getId(), event.getDomain(), event.getAlertType());
                    return StormAction.SEND_INDIVIDUAL;
                }
                event.setStormId(storm.getId());
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
            if (!created && storm.getCloseState() != null) {
                // Terfi yarışını kaybettik ama bulunan fırtına kapanıyor (iki aşamalı kapanış): yutma.
                log.info("🌩 Storm #{} kapanıyor — {} [{}] bireysel gönderiliyor", storm.getId(), event.getDomain(), event.getAlertType());
                return StormAction.SEND_INDIVIDUAL;
            }
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
            } else {
                // Terfi yarışını KAYBEDEN yol da üyelik satırını yazar (ALERTS-4, 2026-10-22): bu alarm (ve kazananın
                // listesinde olmayan eşler) kazananın açılış postasında YOK; satırı duyurusuz kalır → kapanışta bağı koparılır
                // ve bireysel İLK bildirim gider. Kazananın yazdığı satırlar ON CONFLICT ile korunur.
                recordMembers(storm.getId(), peers, null, AlertStormMember.JOIN_ATTACH);
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
            return stormRepo.findById(stormId).map(StormService::openForMembers).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fırtına üyeler için hâlâ AÇIK mı: çözülmemiş VE kapanışı başlamamış (2026-10-21, iki aşamalı kapanış). Kapanışı
     * sahiplenilmiş (CLOSING) fırtına, kapanış anından sonra çözülen üyenin bireysel çözüm postasını bastırmaz ve yeni
     * üye almaz — kurtarma postası yalnız kapanış anına kadar çözülenleri sayar.
     */
    static boolean openForMembers(AlertStorm s) {
        return s != null && !Boolean.TRUE.equals(s.getResolved()) && s.getCloseState() == null;
    }

    /**
     * Bağlanma SONRASI doğrulama (2026-10-09, kapanış yarışı) — çağıran {@code SUPPRESSED} kararından sonra olayı
     * {@code storm_id} ile KAYDETTİKTEN sonra çağırır. Fırtına bu arada kapandıysa (yaşam döngüsü üye listesini bu
     * kayıttan önce okudu, {@code resolved=true}'yu ise sonra yazdı) olay kapanışın listesinde yoktu: ne duyuruldu ne
     * bağı koparıldı, {@code lastReAlertAt=now} ile bir yeniden uyarı aralığı (~24 sa) sessiz kalırdı. Bu durumda bağ
     * HEDEFLİ KOŞULLU UPDATE ile koparılır ({@code storm_id=NULL}, {@code last_re_alert_at=NULL}; varlık save'i değil —
     * bayat varlık başka alanları geri sarabilir) → sonraki tur bireysel İLK bildirimi gönderir; bu, kapanışta
     * duyurulmamış üyenin ({@link #resolveStorm}) yoludur, bildirim günlüğündeki fırtına devri satırı da aynı kalır.
     * Yarışın öbür yüzü (kayıt kapanıştan ÖNCE düştü ama listede yoktu) kapanışın {@link #unlinkLateJoiners} adımıdır.
     *
     * <p>Olumsuz adlı (true = kapanmış, bağ koparıldı) — taklit servis false döner ve bugünkü yol değişmez. Tek okuma
     * (birincil anahtar); yazma yalnız yarışta. Hata alarm hattına yayılmaz (false).
     */
    public boolean closedAfterAttach(AlertEvent event) {
        if (event == null || event.getId() == null || event.getStormId() == null) return false;
        Long stormId = event.getStormId();
        try {
            boolean active = stormRepo.findById(stormId).map(StormService::openForMembers).orElse(false);
            if (active) return false;
            int rows = alertEventRepo.unlinkFromStormIfLinked(event.getId(), stormId);
            event.setStormId(null);
            event.setLastReAlertAt(null);
            if (rows == 1) {
                List<Object[]> leaves = new ArrayList<>(1);
                leave(leaves, stormId, event.getId(), now(), AlertStormMember.LEAVE_UNLINKED);
                flushLeaves(stormId, leaves);
            }
            log.info("🌩 Storm #{} bağlanma sırasında kapandı — {} [{}] olay #{} fırtınadan ayrıldı ({}), sonraki turda bireysel ilk bildirim",
                    stormId, event.getDomain(), event.getAlertType(), event.getId(),
                    rows == 1 ? "bağ koparıldı" : "bağ zaten koparılmıştı");
            return true;
        } catch (Exception e) {
            log.warn("Storm #{} bağlanma doğrulaması yapılamadı (olay {}): {}", stormId, event.getId(), e.getMessage());
            return false;
        }
    }

    // ── Yaşam döngüsü sweep'i (çözülme / günlük re-alert / toggle-off) ────────────

    @Scheduled(fixedDelayString = "${site.monitor.storm.sweep-ms:30000}", initialDelay = 45000)
    public void lifecycleSweep() {
        if (shuttingDown()) return;   // kapanış (inceleme R-2): yeni fırtına işi yok — yarıda kalan kapanışı başka pod bitirir
        // Ucuz ön bakış: aktif fırtına da yarıda kalmış kapanış da yoksa kilit tablosuna hiç yazılmaz (her pod 30 sn'de bir).
        try {
            if (stormRepo.findByResolvedFalse().isEmpty() && closingIds().isEmpty()) return;
        } catch (Exception e) {
            return;   // tablo henüz yok / DB erişilemez — sessiz
        }

        boolean enabled = isEnabled();
        LockGrant grant = tryLock();
        if (grant == null) return;   // HA: yalnız bir pod yönetir
        long lockAt = System.currentTimeMillis();
        try {
            // Liste KİLİTTEN SONRA okunur (2026-10-21, çok pod — MP-04): kilitten önce okunan liste, kilidi o arada bırakan
            // pod'un az önce kapattığı fırtınayı "açık" gösteriyor, kapanış (posta / webhook / 7-24 / push) ikinci kez
            // gidiyordu. Kapanışın kendisi de koşullu (claimClose) — bayat varlık ikinci kez kapatamaz.
            List<AlertStorm> active;
            try {
                // Yarıda kalmış kapanışlar (resolved=true, close_state=CLOSING) önce — sonra açık fırtınalar.
                active = new ArrayList<>();
                for (Long id : closingIds()) stormRepo.findById(id).ifPresent(active::add);
                for (AlertStorm s : stormRepo.findByResolvedFalse()) {
                    if (s != null && active.stream().noneMatch(x -> java.util.Objects.equals(x.getId(), s.getId()))) active.add(s);
                }
            } catch (Exception e) {
                return;
            }
            boolean first = true;
            for (AlertStorm storm : active) {
                if (shuttingDown()) {
                    log.info("Storm yaşam döngüsü: pod kapanıyor — kalan fırtınalar bırakıldı (başka pod sürdürür)");
                    return;
                }
                // Kilit fırtınalar ARASINDA denetlenir (90 sn TTL; yavaş SMTP / webhook'la tur uzayabilir). Kilit artık bu
                // pod'un değilse (başka pod süresi dolan kilidi aldı ya da DB hatası) tur DURUR — kalan fırtınaları kilidin
                // sahibi işler.
                if (!first) {
                    if (!grant.stillHeld(lockAt)) {
                        log.warn("Storm yaşam döngüsü kilidi kaybedildi — tur durduruldu (kalan fırtınaları kilidin sahibi işler)");
                        return;
                    }
                    if (grant.renewedLegacy()) lockAt = System.currentTimeMillis();
                }
                first = false;
                try {
                    // İki aşamalı kapanış (2026-10-21): yarıda kalmış kapanış (pod öldü / hata) bayatsa bitirilir.
                    if (CLOSE_CLOSING.equals(storm.getCloseState())) { finishStaleClose(storm); continue; }
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
            releaseLock(grant);
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
            // GÖNDERMEDEN ÖNCE SAHİPLEN (2026-10-09, sonsuz döngü düzeltmesi): damga eskiden gönderimden SONRA yazılıyordu;
            // gönderim ile damga arasında bir istisna (ör. damga UPDATE'i düştü) 30 sn sonraki tur için tekrarı yine "vakti
            // gelmiş" bırakıyor, aynı toplu posta / webhook / push her turda yeniden gidiyordu. Koşullu UPDATE tek kazanır;
            // gönderim düşse de sahiplenme durur (en çok bir kez — alarm yollarıyla aynı). Sonraki tekrar 24 sa sonra.
            if (claimDailyRealert(storm)) {
                List<AlertEvent> stillDown = members.stream()
                        .filter(m -> !Boolean.TRUE.equals(m.getResolved())).toList();
                sendStormAlert(storm, stillDown, "DAILY_REALERT");
                log.info("🌩 Storm #{} günlük toplu re-alert gönderildi — {} monitör hâlâ down", storm.getId(), stillDown.size());
            }
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
        // Önce SAHİPLEN (2026-10-21, çok pod): kapanışın bütün yan etkileri yalnız fırtınayı gerçekten kapatan tura aittir.
        CloseRun run = claimClose(storm, reasonCode, reason);
        if (run == null) return;
        try {
            jdbcTemplate.update(SQL_RESOLVE_STATS, storm.getMemberCount() == null ? members.size() : storm.getMemberCount(),
                    storm.getPeakTargets() == null ? 0 : storm.getPeakTargets(), storm.getId());
        } catch (Exception e) {
            log.debug("Storm #{} kapanış sayaçları yazılamadı: {}", storm.getId(), e.getMessage());
        }
        runClose(run);
    }

    /**
     * Histerezis / mühür kapanışının üye adımı. Üyeler kapanış SAHİPLENDİKTEN sonra yeniden okunur: kurtulan = kapanış anına
     * kadar çözülen (sonra çözülen bireysel çözüm postasını aldı — fırtına artık üyeler için kapalı), hâlâ-down = açık
     * üyeler. Her üye adımı kendi try/catch'inde — biri düşerse diğerleri sürer, sayılır, kapanış CLOSING kalır ve sonraki
     * tur yeniden dener (adımlar koşullu UPDATE, tekrarı zararsız).
     */
    private CloseWork resolveClose(CloseRun run) {
        AlertStorm storm = run.storm();
        String closingAt = run.closingAt();
        List<AlertEvent> members = alertEventRepo.findByStormId(storm.getId());
        List<AlertEvent> recovered = new ArrayList<>();
        List<AlertEvent> lateRecovered = new ArrayList<>();
        List<AlertEvent> stillDown = new ArrayList<>();
        for (AlertEvent m : members) {
            if (!Boolean.TRUE.equals(m.getResolved())) stillDown.add(m);
            else if (resolvedBy(m, closingAt)) recovered.add(m);
            else lateRecovered.add(m);
        }

        String announcedAt = storm.getLastReAlertAt();
        Map<Long, String> memberRows = announcedRows(storm.getId());   // ALERTS-4: "duyuruldu" üyelik satırından
        List<Object[]> leaves = new ArrayList<>(members.size());
        int announced = 0, unannounced = 0, failures = 0;
        for (AlertEvent e : stillDown) {
            try {
                switch (releaseStillDown(storm, e, closingAt, announcedAt, memberRows, leaves)) {
                    case 1 -> announced++;
                    case 2 -> unannounced++;
                    default -> { /* bağ bu arada değişti — sayılmaz */ }
                }
            } catch (Exception ex) {
                failures++;
                log.warn("Storm #{} kapanışı: üye #{} bırakılamadı (sonraki tur yeniden dener): {}", storm.getId(), e.getId(),
                        ex.getMessage());
            }
        }
        for (AlertEvent e : recovered) {
            leave(leaves, storm.getId(), e.getId(), e.getResolvedAt() != null ? e.getResolvedAt() : closingAt, AlertStormMember.LEAVE_RECOVERED);
        }
        for (AlertEvent e : lateRecovered) {
            leave(leaves, storm.getId(), e.getId(), e.getResolvedAt() != null ? e.getResolvedAt() : closingAt, AlertStormMember.LEAVE_RECOVERED);
        }
        flushLeaves(storm.getId(), leaves);
        unlinkLateJoiners(storm, members);   // liste okunduktan sonra bağlanan üye (kapanış yarışı, 2026-10-09)
        log.info("🌩✅ Storm #{} çözüldü ({}) — {} kurtuldu, {} hâlâ down ({} duyurulmuş → bildirildi sayıldı, {} duyurulmamış → bireysel ilk bildirim)",
                storm.getId(), run.reason(), recovered.size(), stillDown.size(), announced, unannounced);
        return new CloseWork(recovered, stillDown, failures);
    }

    /**
     * Kapanışta hâlâ düşük TEK üyenin bırakılması — {@link #resolveClose} ve {@link #disbandClose} AYNI kuralı kullanır
     * (2026-10-22, ALERTS-4 / ALERTS-5).
     *
     * <ul>
     *   <li>Kapanış sürerken bireysel bildirildi ({@link #notifiedAfter}) → bildirildi sayılır, KENDİ damgası kalır.</li>
     *   <li>Fırtına postasında DUYURULDU → bildirildi sayılır (günlük tekrar kadansı fırtına postasından sürer, ikinci İLK
     *       yok). "Duyuruldu" kararı üyelik satırından ({@code alert_storm_members.announced_at IS NOT NULL} — postada
     *       gerçekten listelenen üyeye {@code markAnnounced} yazar). Açılış postası GÖNDERİLİRKEN katılan üye
     *       ({@code createdAt} damgadan önce, postada yok) eskiden "duyuruldu" sayılıp hiç bildirilmiyordu. Üyelik satırı
     *       OLMAYAN eski olaylarda (2026-09-30 öncesi) ve satırlar okunamazsa eski kural: {@code createdAt ≤ lastReAlertAt}.</li>
     *   <li>Duyurulmadı → bağ koparılır, {@code lastReAlertAt=null} → sonraki tur bireysel İLK bildirim.</li>
     * </ul>
     *
     * @return 1 = bildirildi sayıldı, 2 = bağı koparıldı (duyurulmamış), 0 = bağ bu arada değişmişti (yalnız ayrılış)
     */
    private int releaseStillDown(AlertStorm storm, AlertEvent e, String closingAt, String announcedAt,
                                 Map<Long, String> memberRows, List<Object[]> leaves) {
        if (notifiedAfter(e, closingAt)) {
            // Kapanış SÜRERKEN bireysel bildirildi (fırtına üyeler için kapalı): bağ kopar, KENDİ damgası kalır —
            // damgayı sıfırlamak ek INITIAL, fırtına postasına geri sarmak erken tekrar demekti (ikinci inceleme D1).
            if (alertEventRepo.releaseFromStormAsNotified(e.getId(), storm.getId(), e.getLastReAlertAt()) == 1) {
                leave(leaves, storm.getId(), e.getId(), closingAt, AlertStormMember.LEAVE_NOTIFIED);
                return 1;
            }
            return 0;
        }
        if (announcedInStorm(e, announcedAt, memberRows)) {
            // Damga: fırtınanın son postası; fırtına damgasızsa üyenin kendi duyuru anı (günlük tekrar kadansı ondan sürer).
            String stamp = announcedAt != null ? announcedAt
                    : memberRows != null && e.getId() != null ? memberRows.get(e.getId()) : null;
            if (alertEventRepo.releaseFromStormAsNotified(e.getId(), storm.getId(), stamp) == 1) {
                leave(leaves, storm.getId(), e.getId(), closingAt, AlertStormMember.LEAVE_NOTIFIED);
                return 1;
            }
            alertEventRepo.unlinkFromStorm(e.getId(), storm.getId());   // bağ değişmişse (yarış) güvenli taraf: bireysel İLK — yalnız BU fırtınadaysa (2026-10-09)
            leave(leaves, storm.getId(), e.getId(), closingAt, AlertStormMember.LEAVE_UNLINKED);
            return 0;
        }
        alertEventRepo.unlinkFromStorm(e.getId(), storm.getId());   // koşullu — çözülmüş üyeyi diriltmeden, yalnız BU fırtınanın bağını kaldır (M6, 2026-10-09)
        leave(leaves, storm.getId(), e.getId(), closingAt, AlertStormMember.LEAVE_UNLINKED);
        return 2;
    }

    /** Fırtınanın üyelik satırları: alarm kimliği → {@code announced_at} (satır var, duyurulmadı = null değer). */
    static final String SQL_MEMBER_ANNOUNCED_READ =
            "SELECT alert_event_id, announced_at FROM alert_storm_members WHERE storm_id = ?";

    /**
     * Kapanışın TEK okuması — üye başına sorgu yok. Okunamazsa {@code null}: çağıran eski {@code createdAt} kuralına düşer
     * (gözlem tablosunun hatası kapanışı düşürmez).
     */
    Map<Long, String> announcedRows(Long stormId) {
        if (stormId == null) return null;
        try {
            Map<Long, String> out = new java.util.HashMap<>();
            for (Map<String, Object> row : jdbcTemplate.queryForList(SQL_MEMBER_ANNOUNCED_READ, stormId)) {
                Object id = row.get("alert_event_id");
                if (id == null) id = row.get("ALERT_EVENT_ID");
                if (!(id instanceof Number n)) continue;
                Object at = row.containsKey("announced_at") ? row.get("announced_at") : row.get("ANNOUNCED_AT");
                out.put(n.longValue(), at == null ? null : String.valueOf(at));
            }
            return out;
        } catch (Exception ex) {
            log.warn("Storm #{} üyelik satırları okunamadı — duyuru kararı açılış zamanından verilecek: {}", stormId, ex.getMessage());
            return null;
        }
    }

    /**
     * Üye fırtınanın postasında duyuruldu mu — üyelik satırı varsa YALNIZ ona göre ({@code announced_at} dolu), satırı yoksa
     * (eski olay) ya da satırlar okunamadıysa açılış zamanı kuralı.
     */
    static boolean announcedInStorm(AlertEvent e, String stormLastReAlertAt, Map<Long, String> memberRows) {
        if (e == null) return false;
        if (memberRows != null && e.getId() != null && memberRows.containsKey(e.getId())) {
            return memberRows.get(e.getId()) != null;
        }
        return announcedInStorm(e, stormLastReAlertAt);
    }

    /**
     * Üye kapanış SAHİPLENMESİNDEN SONRA bireysel bildirildi mi (kendi {@code lastReAlertAt}'i kapanış anından yeni) — fırtına
     * üyeler için kapalıyken tırmanma / tekrar bireysel gider; üye adımı o damgayı korur (ikinci inceleme D1).
     */
    static boolean notifiedAfter(AlertEvent e, String closingAt) {
        String own = e == null ? null : e.getLastReAlertAt();
        return own != null && closingAt != null && own.compareTo(closingAt) > 0;
    }

    /** "Bildirildi" bırakmasının damgası: kapanış sürerken bireysel bildirilen üyenin kendi damgası, yoksa fırtına postası. */
    static String stampFor(AlertEvent e, String closingAt, String stormNotifiedAt) {
        return notifiedAfter(e, closingAt) ? e.getLastReAlertAt() : stormNotifiedAt;
    }

    /** Üye kapanış anına kadar mı çözüldü (damgasız çözüm de sayılır — D-b7a kuralıyla aynı). */
    static boolean resolvedBy(AlertEvent m, String closingAt) {
        return m.getResolvedAt() == null || closingAt == null || m.getResolvedAt().compareTo(closingAt) <= 0;
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
        // SAHİPLEN (2026-10-21, çok pod — MP-04 / iki aşamalı kapanış): bağ koparma, ayrılış kaydı ve kurtarma YALNIZ
        // kapanışı kazanan turda; yarıda kalırsa yaşam döngüsü bitirir.
        CloseRun run = claimClose(storm, RESOLVE_DISABLED, reason);
        if (run == null) return;
        runClose(run);
    }

    /** Dağıtmanın üye adımı (bkz. {@link #resolveClose} — aynı kurallar: yeniden okuma, kesim anı, üye başına try/catch). */
    private CloseWork disbandClose(CloseRun run) {
        AlertStorm storm = run.storm();
        String closingAt = run.closingAt();
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
            if (e.getId() != null && !stillDownIds.contains(e.getId()) && resolvedBy(e, closingAt)) recovered.add(e);

        List<Object[]> leaves = new ArrayList<>(stillDown.size() + recovered.size());
        int failures = 0;
        // ALERTS-5 (2026-10-22): kapanışla AYNI kural. Eskiden duyurulmuş hâlâ-düşük üyenin de bağı koparılıp
        // lastReAlertAt=null yapılıyordu → özellik kapatılınca her üye için ikinci bir INITIAL (30 üyeli fırtına = 30 posta).
        String announcedAt = storm.getLastReAlertAt();
        Map<Long, String> memberRows = announcedRows(storm.getId());
        for (AlertEvent e : stillDown) {
            try {
                releaseStillDown(storm, e, closingAt, announcedAt, memberRows, leaves);
            } catch (Exception ex) {
                failures++;
                log.warn("Storm #{} dağıtma: üye #{} bağı koparılamadı (sonraki tur yeniden dener): {}", storm.getId(), e.getId(),
                        ex.getMessage());
            }
        }
        for (AlertEvent e : recovered) {
            leave(leaves, storm.getId(), e.getId(), e.getResolvedAt() != null ? e.getResolvedAt() : closingAt, AlertStormMember.LEAVE_RECOVERED);
        }
        flushLeaves(storm.getId(), leaves);
        List<AlertEvent> known = new ArrayList<>(stillDown);
        known.addAll(recovered);
        unlinkLateJoiners(storm, known);   // liste okunduktan sonra bağlanan üye (kapanış yarışı, 2026-10-09)
        log.info("🌩 Storm #{} kapatıldı ({}) — {} kurtulan için toplu çözüm, "
                + "{} üye bireysel alarmlamaya döndü", storm.getId(), run.reason(), recovered.size(), stillDown.size());
        return new CloseWork(recovered, stillDown, failures);
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
        // SAHİPLEN (2026-10-21, çok pod — MP-04 / iki aşamalı kapanış): taşıma, bırakma ve kurtarma yalnız eski fırtınayı
        // GERÇEKTEN kapatan turda. Kapanış anı = sahiplenme anı: bundan sonra çözülen üye bireysel çözüm yolundan gider
        // (fırtına üyeler için kapalı) ve aşağıdaki kurtulanlar okumasında sayılmaz (D-b7a kuralı aynı).
        CloseRun run = claimClose(legacy, RESOLVE_LEGACY, "eski kapsam emekliliği");
        if (run == null) return;
        runClose(run);
    }

    /** Eski kapsamlı fırtınanın emeklilik adımı (taşıma / bırakma koşullu → yarıda kalırsa tekrarı zararsız). */
    private CloseWork legacyClose(CloseRun run) {
        AlertStorm legacy = run.storm();
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
        int moved = 0, released = 0, failures = 0;
        List<Object[]> leaves = new ArrayList<>(stillDown.size());
        for (Map.Entry<String, List<AlertEvent>> e : byScope.entrySet()) {
            String key = e.getKey();
            List<AlertEvent> ms = e.getValue();
            AlertStorm target = null;
            try {
                int targetsNow = distinctTargets(ms);
                int thresholdNow = computeThreshold(teamOfScope(key));
                if (targetsNow >= thresholdNow) {
                    boolean created = insertStormIfAbsent(key,
                            key.contains(GROUP_SCOPE_SEP) ? SCOPE_TYPE_TEAM_GROUP : SCOPE_TYPE_TEAM, ms,
                            new OpenSnapshot(teamOfScope(key), groupOfScope(key), thresholdNow, targetsNow, null));
                    target = stormRepo.findByScopeKeyAndResolvedFalse(key).orElse(null);
                    if (target != null && target.getCloseState() != null) target = null;   // kapanan fırtınaya taşınmaz
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
            } catch (Exception ex) {
                target = null;   // hedef kurulamadı: üyeler "bildirildi" sayılıp bırakılır (aşağıda)
                log.warn("Storm #{} emekliliği: {} takım fırtınası kurulamadı — üyeler bildirildi sayılıp bırakılıyor: {}",
                        legacy.getId(), key, ex.getMessage());
            }
            String movedAt = now();
            for (AlertEvent m : ms) {
                try {
                    if (target != null && alertEventRepo.moveToStormIfOpen(m.getId(), legacy.getId(), target.getId()) == 1) {
                        moved++;
                        leave(leaves, legacy.getId(), m.getId(), movedAt, AlertStormMember.LEAVE_MOVED);
                    } else {
                        alertEventRepo.releaseFromStormAsNotified(m.getId(), legacy.getId(), stampFor(m, run.closingAt(), notifiedAt));
                        released++;
                        leave(leaves, legacy.getId(), m.getId(), movedAt, AlertStormMember.LEAVE_RELEASED);
                    }
                } catch (Exception ex) {
                    failures++;
                    log.warn("Storm #{} emekliliği: üye #{} taşınamadı (sonraki tur yeniden dener): {}", legacy.getId(), m.getId(),
                            ex.getMessage());
                }
            }
        }
        for (AlertEvent m : ownerless) {
            try {
                alertEventRepo.releaseFromStormAsNotified(m.getId(), legacy.getId(), stampFor(m, run.closingAt(), notifiedAt));
                released++;
                leave(leaves, legacy.getId(), m.getId(), now(), AlertStormMember.LEAVE_RELEASED);
            } catch (Exception ex) {
                failures++;
                log.warn("Storm #{} emekliliği: sahipsiz üye #{} bırakılamadı: {}", legacy.getId(), m.getId(), ex.getMessage());
            }
        }
        flushLeaves(legacy.getId(), leaves);
        // D-b7(a) (2026-09-29): kurtulanlar işlemin SONUNDA yeniden okunur. Üye listesi baştan okunduktan sonra, taşıma /
        // çözme sürerken izleme turu bir üyeyi çözebilir: bireysel çözüm e-postası "fırtına aktif" diye bastırılmıştı, üye
        // ise baştaki okumada "hâlâ düşük" göründüğü için kurtulanlara girmiyordu → hiçbir çözüm bildirimi gitmiyordu.
        // Taşıma ve çözme yalnız AÇIK satırda koştuğundan geç çözülen üye eski fırtınaya bağlı kalır ve burada görünür.
        // Fırtına kapandıktan SONRA çözülen üye bireysel çözüm yolundan gider (fırtına üyeler için kapalı) — çift bildirim
        // olmasın diye yalnız kapanış anına kadar çözülenler sayılır. Sessiz kapanan üye sendStormRecovery'de ayrıca elenir.
        String closedAt = run.closingAt();
        List<AlertEvent> recoveredNow = new ArrayList<>();
        for (AlertEvent m : alertEventRepo.findByStormId(legacy.getId())) {
            if (!Boolean.TRUE.equals(m.getResolved())) continue;
            if (!resolvedBy(m, closedAt)) continue;
            recoveredNow.add(m);
        }
        Set<Long> recoveredIds = new java.util.HashSet<>();
        for (AlertEvent m : recoveredNow) if (m.getId() != null) recoveredIds.add(m.getId());
        List<AlertEvent> downNow = stillDown.stream().filter(m -> m.getId() == null || !recoveredIds.contains(m.getId())).toList();
        log.info("🌩 Eski kapsamlı fırtına #{} emekliye ayrıldı ({}) — {} üye takım fırtınasına taşındı, {} üye bildirildi "
                        + "sayılıp çözüldü, {} kurtulan için takım bazlı toplu çözüm",
                legacy.getId(), legacy.getScopeKey(), moved, released, recoveredNow.size());
        return new CloseWork(recoveredNow, downNow, failures);
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
            total = inventoryRepo.countNetworkActive()   // dosyadan yüklenen sertifikalar hariç (fırtına üyesi olamaz)
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
                    + (pageSpeedRepo != null ? pageSpeedRepo.countByActiveTrue() : 0)
                    // Veritabanı sorgusu: DBQUERY_FAIL storm ÜYESİ (DOWN_ALERT_TYPES içinde) → paydada da olmalı.
                    + (dbQueryRepo != null ? dbQueryRepo.countByActiveTrue() : 0);
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
            total = inventoryRepo.countNetworkActiveByTeam(teamId)   // dosyadan yüklenen sertifikalar hariç
                    + httpRepo.countByTeamIdAndActiveTrue(teamId)
                    + keywordRepo.countByTeamIdAndActiveTrue(teamId)
                    + pingRepo.countByTeamIdAndActiveTrue(teamId)
                    + domainRepo.countByTeamIdAndActiveTrue(teamId)
                    + portRepo.countByStandaloneTrueAndActiveTrueAndTeamId(teamId)
                    + dnsRepo.countByStandaloneTrueAndActiveTrueAndTeamId(teamId)
                    + (pageRepo != null ? pageRepo.countByTeamIdAndActiveTrue(teamId) : 0)
                    + (scriptedRepo != null ? scriptedRepo.countByTeamIdAndActiveTrue(teamId) : 0)
                    + (pageSpeedRepo != null ? pageSpeedRepo.countByTeamIdAndActiveTrue(teamId) : 0)
                    + (dbQueryRepo != null ? dbQueryRepo.countByTeamIdAndActiveTrue(teamId) : 0);
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
            + "SELECT team_id, COUNT(*) AS n FROM certificate_inventory WHERE active = true AND team_id IS NOT NULL"
            + " AND (cert_source IS NULL OR cert_source <> 'MANUAL') GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM http_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM keyword_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM ping_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM domain_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM port_monitors WHERE standalone = true AND active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM dns_monitors WHERE standalone = true AND active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM page_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM scripted_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM pagespeed_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
            + " UNION ALL SELECT team_id, COUNT(*) FROM dbquery_monitors WHERE active = true AND team_id IS NOT NULL GROUP BY team_id"
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

    /** Push alarm başına mı gider ({@link #KEY_PUSH_INDIVIDUAL}, varsayılan KAPALI, 2026-10-04) — durum ekranı da aynı değeri gösterir. */
    public boolean pushIndividual() { return appSettings.getBoolean(KEY_PUSH_INDIVIDUAL, false); }

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
     * Kapanışta GEÇ KATILAN üyeleri bireysel hatta döndürür (2026-10-09, kapanış yarışı). Kapanış üye listesini
     * {@code resolved=true} yazılmadan ÖNCE okur; o okuma ile kapanış arasında bağlanan alarm ({@code evaluate}'in koşullu
     * sayacı fırtınayı henüz aktif gördü, çağıran {@code storm_id}'yi listeden sonra kaydetti) listede yoktur: ne
     * duyurulur ne bağı koparılır, kapalı fırtınayı gösterir ve {@code lastReAlertAt=now} ile bir yeniden uyarı aralığı
     * (~24 sa) sessiz kalırdı. Kapanış YAZILDIKTAN sonra TEK okuma: hâlâ bu fırtınaya bağlı açık ve listede olmayan olay
     * geç katılandır → koşullu ayırma ({@code storm_id=NULL}, {@code last_re_alert_at=NULL}; sonraki tur bireysel İLK) +
     * ayrılış kaydı ({@code UNLINKED}). Kayıt kapanıştan SONRA düştüyse çağıranın {@link #closedAfterAttach}
     * denetimi yakalar. Yarış yoksa okuma boş döner, hiçbir şey yazılmaz. Hata kapanışı düşürmez.
     */
    private void unlinkLateJoiners(AlertStorm storm, List<AlertEvent> known) {
        if (storm == null || storm.getId() == null) return;
        try {
            Set<Long> knownIds = new java.util.HashSet<>();
            if (known != null) for (AlertEvent e : known) if (e != null && e.getId() != null) knownIds.add(e.getId());
            List<Object[]> leaves = new ArrayList<>();
            String at = now();
            for (AlertEvent e : alertEventRepo.findByStormIdAndResolvedFalse(storm.getId())) {
                if (e == null || e.getId() == null || knownIds.contains(e.getId())) continue;   // listedeki üye işlendi
                if (alertEventRepo.unlinkFromStormIfLinked(e.getId(), storm.getId()) == 1) {
                    leave(leaves, storm.getId(), e.getId(), at, AlertStormMember.LEAVE_UNLINKED);
                    log.info("🌩 Storm #{} kapanırken geç katılan üye ayrıldı: {} [{}] olay #{} — sonraki turda bireysel ilk bildirim",
                            storm.getId(), e.getDomain(), e.getAlertType(), e.getId());
                }
            }
            flushLeaves(storm.getId(), leaves);
        } catch (Exception ex) {
            log.warn("Storm #{} geç katılan üye denetimi yapılamadı: {}", storm.getId(), ex.getMessage());
        }
    }

    /**
     * Üye sayacı — KOŞULLU atomik UPDATE (2026-09-29, D-14). Eskiden okunan varlığın tamamı kaydediliyordu: kilitsiz
     * {@code evaluate} ile kilitli {@code lifecycleSweep} yarışırsa çözülmüş fırtına {@code resolved=false} ile geri
     * yazılabiliyor, ikinci toplu çözüm postası gidebiliyordu.
     *
     * <p>Dönüş (2026-10-09): güncellenen satır sayısı. {@code 0} = fırtına bu arada kapandı (koşul tutmadı) → çağıran
     * bağlamaz, bireysel gönderir. {@code -1} = yazılamadı (DB hatası) — fırtına mantığının genel kuralı gibi güvenli
     * taraf: bireysel gönderim.
     */
    private int bumpMemberCount(AlertStorm storm) {
        try {
            // last_member_at: sessiz pencere saati her katılımda yeniden başlar (2026-09-30).
            // close_state IS NULL (2026-10-21): kapanışı başlamış fırtınaya üye bağlanmaz (bireysel gönderilir).
            return jdbcTemplate.update("UPDATE alert_storms SET member_count = COALESCE(member_count, 0) + 1, last_member_at = ? "
                    + "WHERE id = ? AND resolved = false AND close_state IS NULL", now(), storm.getId());
        } catch (Exception e) {
            log.warn("Storm #{} üye sayacı güncellenemedi — alarm bireysel gönderilecek: {}", storm.getId(), e.getMessage());
            return -1;
        }
    }

    /*
     * İKİ AŞAMALI KAPANIŞ (2026-10-21, çok pod — inceleme S-1 / F1). İlk sürüm kapanışı resolved=true ile sahipleniyor, üye
     * bağlarını ve kurtarma postasını SONRA yapıyordu: arada bir DB hatası ya da pod ölümü olunca kurtarma (posta / webhook /
     * 7-24 / push) kayboluyor, duyurulmamış hâlâ-down üyeler kapanmış fırtınaya bağlı kalıp ilk bildirimlerini ~24 sa geç
     * alıyordu — iz bırakmadan. Şimdi:
     *   1. claimClose: resolved=true + close_state=CLOSING (+ sahiplenme anı / sahip / deneme 1, recovery_state=PENDING,
     *      resolved_at = kapanış anı). resolved=true HEMEN (2026-10-21 ikinci inceleme D1/D2): takımın kapsamı
     *      (ux_alert_storms_active, resolved=false) sahiplenme anında boşalır — takılı bir kapanış yeni fırtınayı ~20-25 dk
     *      engellemez; eski sürüm pod'ları (resolved'a bakar) kapanan fırtınayı açık sanıp üyenin bireysel çözümünü
     *      bastırmaz ya da kendi yoluyla ikinci kez kapatmaz. Yarıda kalan kapanışı yaşam döngüsü close_state='CLOSING' ile
     *      bulur. CLOSING fırtına üyeler için kapalıdır (openForMembers): yeni üye almaz, kapanış anından sonra çözülen üyenin
     *      bireysel çözümünü bastırmaz; kapanış SÜRERKEN bireysel bildirilen üyenin damgasına üye adımı dokunmaz
     *      (notifiedAfter — ek INITIAL / geri sarma yok).
     *   2. Üye adımları (bırak / bağı kopar / taşı) — her biri kendi try/catch'inde, koşullu UPDATE (tekrarı zararsız).
     *   3. Kurtarma TEK kez: recovery_state PENDING → SENDING (koşullu) → gönder → SENT. Gönderim sırasında ölen pod'un
     *      SENDING'i bitiren pod'ca UNKNOWN olur ve izi notification_logs'a yazılır — yeniden GÖNDERİLMEZ (çift posta olmasın).
     *   4. En son close_state=DONE. Üye adımı düştüyse kapanış CLOSING kalır; CLOSE_STALE_MS sonra herhangi bir
     *      pod sahiplenmeyi koşullu yeniler (close_claimed_at CAS) ve adımları tekrarlar; CLOSE_MAX_ATTEMPTS denemede bitmezse
     *      kapanış yine DONE yazılır ve bağı koparılamayan her üye için notification_logs izi bırakılır.
     * Eski tek aşamalı yedek yol KALDIRILDI (ikinci inceleme D5): AlertStorm close_* sütunlarını eşler — sütunlar yoksa
     * fırtına okumaları zaten düşer (yaşam döngüsü sessizce atlar), sahiplenmeye hiç gelinmez; ddl-auto ve
     * StormCloseSchemaPatch sütunları ekler.
     */
    static final String CLOSE_CLOSING = "CLOSING";
    static final String CLOSE_DONE = "DONE";
    static final String RECOVERY_PENDING = "PENDING";
    static final String RECOVERY_SENDING = "SENDING";
    static final String RECOVERY_SENT = "SENT";
    static final String RECOVERY_NONE = "NONE";
    static final String RECOVERY_UNKNOWN = "UNKNOWN";
    /** Yarıda kalmış kapanış bu kadar sonra başka tur / pod'ca bitirilir (sahibi hâlâ çalışıyorsa çakışmasın). */
    static final long CLOSE_STALE_MS = 5L * 60_000;
    /** Bu kadar denemede bitmeyen kapanış izle birlikte DONE yazılır (sonsuz tekrar yok). */
    static final int CLOSE_MAX_ATTEMPTS = 5;

    static final String SQL_CLOSE_CLAIM = "UPDATE alert_storms SET resolved = true, close_state = 'CLOSING', close_claimed_at = ?, "
            + "close_owner = ?, close_attempts = 1, recovery_state = 'PENDING', resolved_at = ?, resolve_reason = ? "
            + "WHERE id = ? AND resolved = false AND close_state IS NULL";
    static final String SQL_CLOSE_RECLAIM = "UPDATE alert_storms SET close_claimed_at = ?, close_owner = ?, "
            + "close_attempts = COALESCE(close_attempts, 1) + 1 "
            + "WHERE id = ? AND close_state = 'CLOSING' AND COALESCE(close_claimed_at, '') = ?";
    /** Bitiş — {@code resolved = true} yine yazılır: bu sürümden önceki CLOSING satırı (resolved=false) da kapansın. */
    static final String SQL_CLOSE_DONE =
            "UPDATE alert_storms SET resolved = true, close_state = 'DONE' WHERE id = ? AND close_state = 'CLOSING'";
    /** Yarıda kalmış kapanışlar (yaşam döngüsü bitirir) — küçük tablo, sınırlı. */
    static final String SQL_CLOSING_IDS =
            "SELECT id FROM alert_storms WHERE close_state = 'CLOSING' ORDER BY id LIMIT 50";
    static final String SQL_RECOVERY_CLAIM =
            "UPDATE alert_storms SET recovery_state = 'SENDING' WHERE id = ? AND recovery_state = 'PENDING'";
    static final String SQL_RECOVERY_FINISH =
            "UPDATE alert_storms SET recovery_state = ? WHERE id = ? AND recovery_state = 'SENDING'";
    static final String SQL_RECOVERY_NONE =
            "UPDATE alert_storms SET recovery_state = 'NONE' WHERE id = ? AND recovery_state = 'PENDING'";
    static final String SQL_RECOVERY_GIVE_UP =
            "UPDATE alert_storms SET recovery_state = 'UNKNOWN' WHERE id = ? AND recovery_state = 'PENDING'";

    /** Kapanışta üye sayacı + tepe hedef (eski tam varlık kaydının yazdığı iki alan) — hedefli, kapanışı sahiplenen yazar. */
    static final String SQL_RESOLVE_STATS =
            "UPDATE alert_storms SET member_count = ?, peak_targets = GREATEST(COALESCE(peak_targets, 0), ?) WHERE id = ?";

    /**
     * Bir kapanış turu. {@code closingAt} = kapanış anı (ilk sahiplenmede yazılır, bitiren tur aynısını kullanır — kurtarma
     * yalnız bu ana kadar çözülenleri sayar).
     */
    record CloseRun(AlertStorm storm, String closingAt, int attempt, String reason) {}

    /** Üye adımının sonucu: kurtarma postasının kurtulan / hâlâ-down listeleri ve düşen üye adımı sayısı. */
    record CloseWork(List<AlertEvent> recovered, List<AlertEvent> stillDown, int failures) {}

    /** Kapanışı sahiplenenin kimliği (küme kilidi varsa onun sahibi). */
    private String closeOwner() {
        return lockService != null ? lockService.owner() : lockOwner;
    }

    /**
     * Kapanışı sahiplenir (resolved=true + CLOSING). {@code null} = başka pod / tur sahiplendi ya da yazılamadı → hiçbir yan
     * etki yok (yazılamadıysa sonraki tur dener).
     */
    CloseRun claimClose(AlertStorm storm, String reasonCode, String reason) {
        String closingAt = now();
        String owner = closeOwner();
        int rows;
        try {
            rows = jdbcTemplate.update(SQL_CLOSE_CLAIM, closingAt, owner, closingAt, reasonCode, storm.getId());
        } catch (Exception e) {
            log.warn("Storm #{} kapanışı sahiplenilemedi — bu tur kapatmıyor (sonraki tur yeniden dener): {}",
                    storm.getId(), e.getMessage());
            return null;
        }
        if (rows != 1) {
            log.info("Storm #{} zaten kapatılmış ya da kapanıyor (başka pod / tur) — kapanış bu turdan yürütülmüyor", storm.getId());
            return null;
        }
        storm.setResolved(true);
        storm.setCloseState(CLOSE_CLOSING);
        storm.setCloseClaimedAt(closingAt);
        storm.setCloseOwner(owner);
        storm.setCloseAttempts(1);
        storm.setRecoveryState(RECOVERY_PENDING);
        storm.setResolvedAt(closingAt);
        storm.setResolveReason(reasonCode);
        return new CloseRun(storm, closingAt, 1, reason);
    }

    /**
     * Sahiplenilmiş kapanışı yürütür: üye adımı (türüne göre) → kurtarma (tek kez) → DONE. Üye adımı düştüyse ya da
     * kurtarma sahiplenilemediyse kapanış CLOSING kalır (bayatlayınca bitirilir); deneme tavanında izle birlikte DONE.
     */
    void runClose(CloseRun run) {
        AlertStorm storm = run.storm();
        String code = storm.getResolveReason();
        CloseWork work = null;
        try {
            work = RESOLVE_LEGACY.equals(code) ? legacyClose(run)
                    : RESOLVE_DISABLED.equals(code) ? disbandClose(run)
                    : resolveClose(run);
        } catch (RuntimeException e) {
            log.warn("Storm #{} kapanışı yarıda kaldı (deneme {}): {} — fırtına CLOSING kaldı, sonraki tur bitirir",
                    storm.getId(), run.attempt(), e.getMessage());
        }
        boolean recoveryOk = work != null && deliverRecovery(run, work);
        boolean complete = work != null && work.failures() == 0 && recoveryOk;
        if (!complete && run.attempt() < CLOSE_MAX_ATTEMPTS) {
            log.warn("Storm #{} kapanışı tamamlanamadı (deneme {} / {}) — {} dk sonra yeniden denenecek", storm.getId(),
                    run.attempt(), CLOSE_MAX_ATTEMPTS, CLOSE_STALE_MS / 60_000);
            return;
        }
        if (!complete) giveUpClose(run, work);
        markCloseDone(storm);
    }

    /**
     * Kurtarma bildirimi TEK kez (posta / webhook / 7-24 / push — {@link #sendStormRecovery}). {@code false} = sahiplenme
     * yazılamadı (kapanış CLOSING kalsın, sonraki deneme göndersin).
     */
    private boolean deliverRecovery(CloseRun run, CloseWork work) {
        AlertStorm storm = run.storm();
        String state = storm.getRecoveryState();
        if (RECOVERY_SENDING.equals(state)) {
            // Önceki sahip gönderirken durdu: postanın gidip gitmediği bilinmiyor — yeniden GÖNDERİLMEZ, iz bırakılır.
            try {
                if (jdbcTemplate.update(SQL_RECOVERY_FINISH, RECOVERY_UNKNOWN, storm.getId()) == 1) {
                    storm.setRecoveryState(RECOVERY_UNKNOWN);
                    traceMembers(storm, work.recovered(), TRIGGER_STORM_RESOLVE,
                            "FAILED: sonuç bilinmiyor — fırtına #" + storm.getId() + " kurtarma postası gönderilirken kapanışı "
                                    + "yürüten pod durdu; çift posta olmasın diye yeniden gönderilmedi");
                    log.warn("Storm #{} kurtarma postası yarıda kalmış (pod durdu) — UNKNOWN, yeniden gönderilmedi", storm.getId());
                }
            } catch (Exception e) {
                log.warn("Storm #{} kurtarma durumu yazılamadı: {}", storm.getId(), e.getMessage());
                return false;
            }
            return true;
        }
        if (state != null && !RECOVERY_PENDING.equals(state)) return true;   // SENT / NONE / UNKNOWN: karar verilmiş
        try {
            if (work.recovered().isEmpty()) {
                jdbcTemplate.update(SQL_RECOVERY_NONE, storm.getId());
                storm.setRecoveryState(RECOVERY_NONE);
                return true;
            }
            if (jdbcTemplate.update(SQL_RECOVERY_CLAIM, storm.getId()) != 1) return true;   // başka tur / pod sahiplendi
        } catch (Exception e) {
            log.warn("Storm #{} kurtarma postası sahiplenilemedi — sonraki deneme gönderir: {}", storm.getId(), e.getMessage());
            return false;
        }
        storm.setRecoveryState(RECOVERY_SENDING);
        String outcome = RECOVERY_SENT;
        try {
            sendStormRecovery(storm, work.recovered(), work.stillDown());
        } catch (RuntimeException e) {
            // Gönderim yarıda düştü: hangi kanalın gittiği bilinmiyor — yeniden GÖNDERİLMEZ (çift posta olmasın), iz bırakılır.
            outcome = RECOVERY_UNKNOWN;
            log.warn("Storm #{} kurtarma bildirimi yarıda düştü — UNKNOWN, yeniden gönderilmeyecek: {}", storm.getId(), e.toString());
        }
        try {
            if (jdbcTemplate.update(SQL_RECOVERY_FINISH, outcome, storm.getId()) == 1) storm.setRecoveryState(outcome);
        } catch (Exception e) {
            log.warn("Storm #{} kurtarma durumu yazılamadı (SENDING kaldı — tekrar GÖNDERİLMEZ, bitiren tur UNKNOWN yazar): {}",
                    storm.getId(), e.getMessage());
        }
        if (RECOVERY_UNKNOWN.equals(outcome)) {
            traceMembers(storm, work.recovered(), TRIGGER_STORM_RESOLVE,
                    "FAILED: sonuç bilinmiyor — fırtına #" + storm.getId() + " kurtarma bildirimi gönderilirken hata oluştu; "
                            + "çift posta olmasın diye yeniden gönderilmedi");
        }
        return true;
    }

    /**
     * Yarıda kalmış kapanışı (CLOSING, sahiplenmesi {@link #CLOSE_STALE_MS}'den eski) bitirir — herhangi bir pod; sahiplenme
     * koşullu yenilenir (close_claimed_at CAS), yarışı kaybeden dokunmaz. {@code true} = bu tur yürüttü.
     */
    boolean finishStaleClose(AlertStorm storm) {
        if (storm == null || !CLOSE_CLOSING.equals(storm.getCloseState())) return false;
        String claimedAt = storm.getCloseClaimedAt() == null ? "" : storm.getCloseClaimedAt();
        String staleBefore = ISO.format(Instant.now().minusMillis(CLOSE_STALE_MS));
        if (!claimedAt.isEmpty() && claimedAt.compareTo(staleBefore) > 0) return false;   // sahibi hâlâ çalışıyor olabilir
        String at = now();
        String owner = closeOwner();
        int rows;
        try {
            rows = jdbcTemplate.update(SQL_CLOSE_RECLAIM, at, owner, storm.getId(), claimedAt);
        } catch (Exception e) {
            log.warn("Storm #{} yarıda kalan kapanışı sahiplenilemedi: {}", storm.getId(), e.getMessage());
            return false;
        }
        if (rows != 1) return false;   // başka tur / pod aldı ya da bitirdi
        int attempt = (storm.getCloseAttempts() == null ? 1 : storm.getCloseAttempts()) + 1;
        String previousOwner = storm.getCloseOwner();
        storm.setCloseClaimedAt(at);
        storm.setCloseOwner(owner);
        storm.setCloseAttempts(attempt);
        String closingAt = storm.getResolvedAt() != null ? storm.getResolvedAt() : at;
        log.warn("Storm #{} kapanışı yarıda kalmış (sahibi {}, {}) — bu tur tamamlıyor (deneme {})", storm.getId(),
                previousOwner, claimedAt.isEmpty() ? "-" : claimedAt, attempt);
        runClose(new CloseRun(storm, closingAt, attempt, "yarıda kalan kapanış"));
        return true;
    }

    private void markCloseDone(AlertStorm storm) {
        try {
            if (jdbcTemplate.update(SQL_CLOSE_DONE, storm.getId()) == 1) {
                storm.setResolved(true);
                storm.setCloseState(CLOSE_DONE);
            }
        } catch (Exception e) {
            log.warn("Storm #{} kapanışı DONE yazılamadı — fırtına CLOSING kaldı, sonraki tur bitirir: {}", storm.getId(), e.getMessage());
        }
    }

    /**
     * Deneme tavanı: kapanış bitirilemedi — fırtına yine kapatılır (sonsuz tekrar yok), etkilenen her üye alarmına iz
     * (checklist 8: gönderilmeyen bildirim nedenini söyler). Bağı koparılamayan hâlâ-down üye fırtınaya bağlı kalır; ilk
     * bireysel bildirimi gecikebilir (günlük tekrar). Gönderilemeyen kurtarma UNKNOWN olur.
     */
    private void giveUpClose(CloseRun run, CloseWork work) {
        AlertStorm storm = run.storm();
        List<AlertEvent> stranded = List.of();
        try {
            stranded = alertEventRepo.findByStormIdAndResolvedFalse(storm.getId());
        } catch (Exception e) {
            log.warn("Storm #{} bağlı kalan üyeler okunamadı: {}", storm.getId(), e.getMessage());
        }
        if (!stranded.isEmpty()) {
            traceMembers(storm, stranded, EscalationService.TRIGGER_STORM_SUPPRESSED,
                    "FAILED: fırtına #" + storm.getId() + " kapanışı " + run.attempt() + " denemede tamamlanamadı — üyenin "
                            + "fırtına bağı koparılamadı; bireysel bildirimi gecikebilir (günlük tekrar)");
        }
        try {
            if (jdbcTemplate.update(SQL_RECOVERY_GIVE_UP, storm.getId()) == 1 && work != null) {
                traceMembers(storm, work.recovered(), TRIGGER_STORM_RESOLVE,
                        "FAILED: fırtına #" + storm.getId() + " kurtarma postası " + run.attempt() + " denemede gönderilemedi");
            }
        } catch (Exception e) {
            log.debug("Storm #{} kurtarma vazgeçme durumu yazılamadı: {}", storm.getId(), e.getMessage());
        }
        if (stranded.isEmpty() && (work == null || work.recovered().isEmpty())) {
            traceMembers(storm, null, EscalationService.TRIGGER_STORM_SUPPRESSED,
                    "FAILED: fırtına #" + storm.getId() + " kapanışı " + run.attempt() + " denemede tamamlanamadı");
        }
        log.error("Storm #{} kapanışı {} denemede tamamlanamadı — kapatıldı, {} üye fırtınaya bağlı kaldı (iz notification_logs'ta)",
                storm.getId(), run.attempt(), stranded.size());
    }

    /** Fırtına kararı izi: üye başına bir satır (üye yoksa alertEventId 0 ile tek satır). Hata yutulur. */
    private void traceMembers(AlertStorm storm, List<AlertEvent> members, String trigger, String status) {
        if (notificationLogRepo == null) return;
        List<Long> ids = new ArrayList<>();
        if (members != null) for (AlertEvent m : members) if (m != null && m.getId() != null) ids.add(m.getId());
        if (ids.isEmpty()) ids.add(0L);
        String stamp = now();
        for (Long id : ids) {
            try {
                com.sitemonitor.model.NotificationLog entry = new com.sitemonitor.model.NotificationLog();
                entry.setAlertEventId(id);
                entry.setSentAt(stamp);
                entry.setRecipientName("-");
                entry.setRecipientEmail("-");
                entry.setRecipientRole("COMBINED");
                entry.setSubject("Alarm fırtınası #" + storm.getId() + " kapanışı");
                entry.setMessage(status);
                entry.setEmailStatus(status);
                entry.setWebhookStatus("SKIPPED");
                entry.setTrigger(trigger);
                entry.setEmailFrom(emailService.getEmailFrom());
                notificationLogRepo.save(entry);
            } catch (Exception ex) {
                log.warn("Storm #{} kapanış izi yazılamadı (olay {}): {}", storm.getId(), id, ex.getMessage());
            }
        }
    }

    /** Günlük toplu tekrarın sahiplenmesi (2026-10-09): damga hâlâ OKUNAN değerdeyse ileri alınır — tek yazar kazanır. */
    static final String SQL_REALERT_CLAIM =
            "UPDATE alert_storms SET last_re_alert_at = ? WHERE id = ? AND resolved = false AND last_re_alert_at = ?";
    /**
     * Damgası hiç yazılmamış fırtına (açılış damgası düştü) için aynı sahiplenme. Ayrı ifade: PostgreSQL'de
     * {@code ? IS NULL} türü belirsiz parametre hatası verir; {@code IS NOT DISTINCT FROM} de taşınabilir değil.
     */
    static final String SQL_REALERT_CLAIM_UNSTAMPED =
            "UPDATE alert_storms SET last_re_alert_at = ? WHERE id = ? AND resolved = false AND last_re_alert_at IS NULL";

    /**
     * Günlük toplu tekrarı GÖNDERMEDEN ÖNCE sahiplenir (2026-10-09, sonsuz döngü düzeltmesi). {@code true} = bu tur
     * gönderir (damga ileri alındı); {@code false} = başka tur/pod sahiplendi, fırtına kapandı ya da yazılamadı — bu tur
     * GÖNDERMEZ. Yazılamadıysa hiçbir şey gitmedi; sonraki tur (30 sn) yeniden dener — gönderimsiz tekrar döngü değildir.
     */
    boolean claimDailyRealert(AlertStorm storm) {
        String seen = storm.getLastReAlertAt();
        String at = now();
        int rows;
        try {
            rows = seen == null
                    ? jdbcTemplate.update(SQL_REALERT_CLAIM_UNSTAMPED, at, storm.getId())
                    : jdbcTemplate.update(SQL_REALERT_CLAIM, at, storm.getId(), seen);
        } catch (Exception e) {
            log.warn("Storm #{} günlük toplu tekrarı sahiplenilemedi — bu tur gönderilmiyor (sonraki tur yeniden dener): {}",
                    storm.getId(), e.getMessage());
            return false;
        }
        if (rows != 1) {
            log.info("Storm #{} günlük toplu tekrarı gönderilmedi — damga bu arada ilerledi ya da fırtına kapandı (sahiplenme {} satır)",
                    storm.getId(), rows);
            return false;
        }
        storm.setLastReAlertAt(at);
        return true;
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
            // L-S1 (2026-10-22): önce bağlamdaki izleme KİMLİĞİ — URL / ad / host yalnız takım içinde tekildir; iki takım aynı
            // hedefi izliyorsa kuruluş geneli "ilk kayıt" araması B takımının fırtınasına A takımının grup adını yazıyordu.
            Long mid = ctx != null && ctx.get("monitor_id") instanceof Number n ? Long.valueOf(n.longValue()) : null;
            if (mid != null) {
                Optional<String> byId = groupById(event.getAlertType(), mid);
                if (byId != null) return byId.orElse(null);
            }
            String d = event.getDomain();
            return switch (event.getAlertType()) {
                case EscalationService.TYPE_HTTP_DOWN, EscalationService.TYPE_HTTP_SLOW ->
                        httpRepo.findFirstByUrlOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null);
                case EscalationService.TYPE_PAGE_DOWN, EscalationService.TYPE_PAGE_INTEGRITY ->
                        pageRepo != null ? pageRepo.findFirstByUrlOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null) : null;
                case EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW ->
                        scriptedRepo != null ? scriptedRepo.findFirstByNameOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null) : null;
                case EscalationService.TYPE_PAGESPEED_DOWN, EscalationService.TYPE_PAGESPEED_SLOW ->
                        pageSpeedRepo != null ? pageSpeedRepo.findFirstByUrlOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null) : null;
                case EscalationService.TYPE_DBQUERY_FAIL, EscalationService.TYPE_DBQUERY_THRESHOLD, EscalationService.TYPE_DBQUERY_SLOW ->
                        dbQueryRepo != null ? dbQueryRepo.findFirstByNameOrderByIdAsc(d).map(m -> m.getGroupName()).orElse(null) : null;
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

    /**
     * İzleme kimliğiyle grup adı. Dönüş: {@code null} = bu tür kimlikle çözülmez ya da izleme bulunamadı (eski aramaya düş);
     * boş Optional = izleme bulundu ama grubu yok (kimlik doğrudur, başka takımın kaydına düşülmez).
     */
    private Optional<String> groupById(String alertType, Long id) {
        return switch (alertType) {
            case EscalationService.TYPE_HTTP_DOWN, EscalationService.TYPE_HTTP_SLOW ->
                    httpRepo.findById(id).map(m -> Optional.ofNullable(m.getGroupName())).orElse(null);
            case EscalationService.TYPE_PAGE_DOWN, EscalationService.TYPE_PAGE_INTEGRITY -> pageRepo == null ? null
                    : pageRepo.findById(id).map(m -> Optional.ofNullable(m.getGroupName())).orElse(null);
            case EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW -> scriptedRepo == null ? null
                    : scriptedRepo.findById(id).map(m -> Optional.ofNullable(m.getGroupName())).orElse(null);
            case EscalationService.TYPE_PAGESPEED_DOWN, EscalationService.TYPE_PAGESPEED_SLOW -> pageSpeedRepo == null ? null
                    : pageSpeedRepo.findById(id).map(m -> Optional.ofNullable(m.getGroupName())).orElse(null);
            case EscalationService.TYPE_DBQUERY_FAIL, EscalationService.TYPE_DBQUERY_THRESHOLD, EscalationService.TYPE_DBQUERY_SLOW ->
                    dbQueryRepo == null ? null
                    : dbQueryRepo.findById(id).map(m -> Optional.ofNullable(m.getGroupName())).orElse(null);
            case EscalationService.TYPE_PING_DOWN, EscalationService.TYPE_PING_SLOW ->
                    pingRepo.findById(id).map(m -> Optional.ofNullable(m.getGroupName())).orElse(null);
            case EscalationService.TYPE_DNS_FAILURE ->
                    dnsRepo.findById(id).map(m -> Optional.ofNullable(m.getGroupName())).orElse(null);
            case EscalationService.TYPE_PORT_DOWN ->
                    portRepo.findById(id).map(m -> Optional.ofNullable(m.getGroupName())).orElse(null);
            case EscalationService.TYPE_KEYWORD ->
                    keywordRepo.findById(id).map(m -> Optional.ofNullable(m.getGroupName())).orElse(null);
            default -> null;
        };
    }

    // ── Toplu bildirim (e-posta + webhook) ────────────────────────────────────────

    private void sendStormAlert(AlertStorm storm, List<AlertEvent> downMembers, String trigger) {
        if (systemMaintenanceMuted()) {
            // Sistem bakımı (2026-10-02): toplu posta gitmez (e-posta/webhook/push/7-24). Damga ilerler (tekrar her turda
            // denenmesin) ama üyeler "duyuruldu" İŞARETLENMEZ — fırtına kapanırken duyurulmamış üye bireysel INITIAL alır.
            recordStormMaintenanceSkip(storm, downMembers,
                    "DAILY_REALERT".equals(trigger) ? TRIGGER_STORM_REALERT : TRIGGER_STORM_INITIAL);
            try {
                // Hedefli + koşullu (2026-10-21, çok pod): varlığın tamamı kaydedilirse arada başka pod'un kapattığı fırtına
                // bayat resolved=false ile yeniden AÇILIYORDU.
                String at = now();
                storm.setLastReAlertAt(at);
                jdbcTemplate.update("UPDATE alert_storms SET last_re_alert_at = ? WHERE id = ? AND resolved = false",
                        at, storm.getId());
            } catch (Exception e) {
                log.warn("Storm #{} damgası yazılamadı (sistem bakımı): {}", storm.getId(), e.getMessage());
            }
            return;
        }
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
                // Bireysel push kipinde (2026-10-03, varsayılan) toplu push yok: tekrar üye başına gider (enqueueStormPush).
                enqueueStormPush(storm, d, trigger, new UserPushService.LocalizedText(
                        count + " monitör birden erişilemez — " + label + " · kök-neden: " + rootCauseLabel,
                        count + " monitors unreachable at once — " + label + " · root cause: " + rootCauseLabel));

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
            // HEDEFLİ UPDATE (2026-10-09; varlık save'i DEĞİL): SMTP/webhook gönderimi sürerken katılan üyenin
            // last_member_at damgası (sessiz pencere) ve sayacı bayat varlıkla geri sarılıyor, aynı anda kapanan fırtına
            // resolved=false ile yeniden açılabiliyordu. Yalnız bu yolun değiştirdiği alanlar, yalnız açık fırtınada.
            jdbcTemplate.update("UPDATE alert_storms SET notified_teams = ?, member_count = ?, last_re_alert_at = ? "
                    + "WHERE id = ? AND resolved = false",
                    storm.getNotifiedTeams(), storm.getMemberCount(), storm.getLastReAlertAt(), storm.getId());
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
    private void enqueueStormPush(AlertStorm storm, TeamDispatch d, String stormTrigger, UserPushService.LocalizedText message) {
        if (userPushService == null || d.teamId() == null || d.pushMembers().isEmpty()) return;
        // BİREYSEL PUSH KİPİ (2026-10-03, kullanıcı kararı: "push bildirimlerini alarm fırtınasına devretmeyelim"): fırtına
        // yalnız e-postayı gruplar. Açılış: üyelerin push'u alarm başına zaten gitti (EscalationService fırtına dalı;
        // eşikten önce düşenler bireysel hattan) → toplu açılış push'u YOK. Günlük tekrar: toplu push yerine hâlâ açık ve
        // onaysız her üyeye bireysel hatırlatma push'u, açılış sırasıyla. Çözüm: her üyenin çözüm push'u kendi kapanışında
        // gider (enqueueResolve, simetri açılış push'unu bulur) → toplu çözüm push'u yalnız bu fırtına (ya da taşındığı eski
        // fırtına) için ÖNCEDEN toplu push gitmişse gider — ayar fırtına sürerken açıldıysa "düştü"yü toplu alan "düzeldi"yi
        // de alsın. Karar satırı yazılmaz: toplu push bilinçli olarak üretilmedi, bireysel satırlar teslimatın kendisidir.
        if (pushIndividual()) {
            if ("DAILY_REALERT".equals(stormTrigger)) {
                pushMembersIndividually(d.teamId(), d.pushMembers());
                return;
            }
            if (!"RESOLVE".equals(stormTrigger)) return;
            boolean noticeSent;
            try {
                noticeSent = userPushService.stormNoticeSent(storm.getId(), storm.getLegacyStormId(), d.teamId());
            } catch (Exception e) {
                noticeSent = false;
            }
            if (!noticeSent) return;
        }
        try {
            // İki dilli metin (2026-10-04, öneri 5): push kişinin dilinde; Türkçe metin bugünküyle aynı. Eski fırtına kimliği
            // (O-3 sessiz taşıma: açılış push'u eski fırtınayla gitti — çözüm onun alıcılarını da sayar, D-b6) yoksa null.
            userPushService.enqueueStormNoticeLocalized(storm.getId(), storm.getLegacyStormId(), d.teamId(), stormTrigger,
                    stormPushLevel(d.pushMembers()), d.pushMembers(), message);
        } catch (Exception e) {
            log.warn("Storm push'u gönderilemedi (takım {}): {}", d.teamId(), e.toString());
        }
    }

    /**
     * Bireysel push kipinde fırtınanın günlük tekrarı (2026-10-03): hâlâ AÇIK ve ONAYSIZ her üyeye kendi hatırlatma push'u
     * (e-posta tetiği {@code DAILY_REALERT} → push {@code RE_ALERT}), açılış sırasıyla ({@code createdAt}, sonra kimlik) —
     * outbox kimlik sırasıyla boşaldığından kuyruk sırası teslim sırasıdır. Bireysel hattın kuralları aynen
     * {@link UserPushService#enqueueAlert}'te: hatırlatma ayarı ({@code SKIPPED_REALERT_OFF}), sistem bakımı, tür / takım /
     * izleme bayrağı, kişisel ve genel sessiz saat, kişi başına saatlik tavan ({@code RATE_LIMITED}) ve olay + gün başına
     * tekilleştirme. Takım sessiz saati bireysel hattaki gibi uygulanır ({@code SKIPPED_TEAM_QUIET}). Onaylı üye bireysel
     * hatta da hatırlatma almaz. Bir üyenin hatası diğerlerini düşürmez.
     */
    private void pushMembersIndividually(Long teamId, List<AlertEvent> members) {
        List<AlertEvent> due = new ArrayList<>();
        for (AlertEvent m : members) {
            if (m == null || m.getId() == null || Boolean.TRUE.equals(m.getResolved())
                    || Boolean.TRUE.equals(m.getAcknowledged())) continue;
            due.add(m);
        }
        due.sort(java.util.Comparator.comparing(AlertEvent::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder()))
                .thenComparing(AlertEvent::getId));
        for (AlertEvent m : due) {
            try {
                Map<String, Object> ctx = memberContext(m);
                Long pushTeam = m.getTeamId() != null ? m.getTeamId() : teamId;
                if (quietHours != null && quietHours.defersPush(pushTeam, m.getAlertLevel(), "DAILY_REALERT"))
                    ctx = EscalationService.quietCtx(ctx);
                userPushService.enqueueAlert(m.getId(), "DAILY_REALERT", teamId, ctx);
            } catch (Exception e) {
                log.warn("Fırtına üyesinin hatırlatma push'u atlandı (olay {}): {}", m.getId(), e.toString());
            }
        }
        if (!due.isEmpty())
            log.info("🌩 Fırtına günlük tekrarı — push alarm başına: {} üye (takım {})", due.size(), teamId);
    }

    /** Üye alarmın alarm-anı bağlam anlık görüntüsü (push şablonu + izleme bayrağı); okunamazsa null. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> memberContext(AlertEvent m) {
        String json = m.getContextJson();
        if (json == null || json.isBlank() || objectMapper == null) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Bir takım gönderiminin nöbetçi planı: takım postasına katılan adresler + yalnız kendi üyelerini listeleyen ayrı postalar. */
    record OncallPlan(List<String> joinTeam, List<OncallMail> separate) { }

    /** Nöbetçiye ayrı çözüm postası: yalnız bu adreslerin AÇILIŞINI aldığı üyeler. */
    record OncallMail(List<String> to, List<AlertEvent> recovered, List<AlertEvent> stillDown) { }

    /**
     * Fırtına çözümünde nöbetçi alıcıları (inceleme 4, 2026-10-12). Üyenin damgası ({@code alert_events.oncall_emails})
     * açılışı GERÇEKTEN almış nöbetçi adresleridir; SY + UG ortak üyesinde iki takımın nöbetçisini birden taşır. Eskiden
     * damgadaki her adres takımın TOPLU postasına ekleniyordu: B'nin nöbetçisi A'nın gönderimindeki A'ya özel hedef
     * adlarını da görüyordu (takım yalıtımı) ve ortak üye yüzünden ilk gönderimde "alındı" sayılıp kendi takımının
     * gönderimindeki üyelerin çözümünü kaçırıyordu. Şimdi:
     * <ul>
     *   <li>adres bu gönderimin BÜTÜN üyelerinin (kurtulan + hâlâ-down) açılışını aldıysa takım postasına katılır (içerik
     *       zaten onun gördüğü kadardır);</li>
     *   <li>aksi hâlde yalnız kendi aldığı üyeleri listeleyen AYRI bir posta alır (aynı üye kümesini alan adresler tek
     *       postada);</li>
     *   <li>bir üyenin çözümü bir adrese tek kez bildirilir ({@code reported}: adres → bildirilen üye kimlikleri, bütün
     *       gönderimler boyunca). Çağıran, GERÇEKTEN gönderilecek takım postalarının adreslerini o postanın kurtulan
     *       üyeleriyle ÖNCEDEN tohumlar ({@link #seedTeamMail}) — bir takımın adresi başka takımın nöbetçisi de olsa o
     *       takıma ait üyelerin çözümünü kaçırmaz (inceleme 5: eskiden her takım adresi nöbetçi ekinden tümden düşüyordu).</li>
     * </ul>
     */
    static OncallPlan planOncall(List<AlertEvent> mineRecovered, List<AlertEvent> mineStillDown,
                                 Map<String, Set<Long>> reported) {
        Map<String, String> display = new LinkedHashMap<>();
        Map<String, List<AlertEvent>> rec = new LinkedHashMap<>();
        for (AlertEvent m : mineRecovered == null ? List.<AlertEvent>of() : mineRecovered) {
            for (String e : EscalationService.oncallMailedFor(m)) {
                String k = e.toLowerCase(java.util.Locale.ROOT);
                if (m.getId() != null && reported.getOrDefault(k, Set.of()).contains(m.getId())) continue;
                display.putIfAbsent(k, e);
                List<AlertEvent> l = rec.computeIfAbsent(k, x -> new ArrayList<>());
                if (!l.contains(m)) l.add(m);
            }
        }
        if (rec.isEmpty()) return new OncallPlan(List.of(), List.of());
        Map<String, List<AlertEvent>> down = new LinkedHashMap<>();
        for (AlertEvent m : mineStillDown == null ? List.<AlertEvent>of() : mineStillDown) {
            for (String e : EscalationService.oncallMailedFor(m)) {
                String k = e.toLowerCase(java.util.Locale.ROOT);
                if (!rec.containsKey(k)) continue;
                List<AlertEvent> l = down.computeIfAbsent(k, x -> new ArrayList<>());
                if (!l.contains(m)) l.add(m);
            }
        }
        int recTotal = mineRecovered.size();
        int downTotal = mineStillDown == null ? 0 : mineStillDown.size();
        List<String> joinTeam = new ArrayList<>();
        Map<String, OncallMail> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<AlertEvent>> en : rec.entrySet()) {
            String k = en.getKey();
            List<AlertEvent> r = en.getValue();
            List<AlertEvent> d = down.getOrDefault(k, List.of());
            Set<Long> done = reported.computeIfAbsent(k, x -> new java.util.HashSet<>());
            for (AlertEvent m : r) if (m.getId() != null) done.add(m.getId());
            if (r.size() == recTotal && d.size() == downTotal) {
                joinTeam.add(display.get(k));
            } else {
                String key = idsOf(r) + "|" + idsOf(d);
                groups.computeIfAbsent(key, x -> new OncallMail(new ArrayList<>(), r, d)).to().add(display.get(k));
            }
        }
        return new OncallPlan(joinTeam, List.copyOf(groups.values()));
    }

    /** Gönderilecek takım postasının alıcıları o postadaki kurtulan üyeleri ZATEN alır — nöbetçi planına tohum. */
    static void seedTeamMail(Map<String, Set<Long>> reported, List<String> teamEmails, List<AlertEvent> recovered) {
        if (teamEmails == null || recovered == null || recovered.isEmpty()) return;
        for (String e : teamEmails) {
            if (e == null || e.isBlank()) continue;
            Set<Long> s = reported.computeIfAbsent(e.trim().toLowerCase(java.util.Locale.ROOT), x -> new java.util.HashSet<>());
            for (AlertEvent m : recovered) if (m.getId() != null) s.add(m.getId());
        }
    }

    private static String idsOf(List<AlertEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (AlertEvent e : events) sb.append(e.getId() != null ? e.getId() : System.identityHashCode(e)).append(',');
        return sb.toString();
    }

    /** Takım adresleri + eklenecek adresler — büyük/küçük harf duyarsız tekil, sıra korunur, boşluklar kırpılır. */
    static List<String> mergeAddresses(List<String> teamEmails, List<String> extra) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (List<String> src : List.of(teamEmails == null ? List.<String>of() : teamEmails, extra == null ? List.<String>of() : extra)) {
            for (String e : src) {
                if (e != null && !e.isBlank() && seen.add(e.trim().toLowerCase(java.util.Locale.ROOT))) out.add(e.trim());
            }
        }
        return out;
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
        if (systemMaintenanceMuted()) {   // sistem bakımı (2026-10-02): toplu çözüm postası susturuldu — kurtulanların günlüğüne iz
            recordStormMaintenanceSkip(storm, recovered, TRIGGER_STORM_RESOLVE);
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
            // Nöbetçi ekleri (planOncall): bir üyenin çözümü bir adrese bütün gönderimler boyunca TEK kez bildirilir. Takım
            // postası GİDECEK gönderimlerin adresleri o postanın kurtulan üyeleriyle önceden tohumlanır (seedTeamMail).
            Map<String, Set<Long>> oncallReported = new HashMap<>();
            for (TeamDispatch d : dispatches) {
                List<AlertEvent> rec = new ArrayList<>();
                for (AlertEvent m : d.members()) if (m.getId() != null && recoveredIds.contains(m.getId())) rec.add(m);
                seedTeamMail(oncallReported, d.emails(), rec);
            }

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

                // Kurtulan üyeyi NÖBETÇİ olarak almış adresler de çözümü öğrenir (2026-10-12; bireysel çözüm yoluyla aynı kural —
                // EscalationService.oncallMailedFor). Yalnız gönderimin bütün üyelerini almış nöbetçi takım postasına katılır;
                // diğerleri kendi üyelerini listeleyen ayrı posta alır (planOncall — takım yalıtımı).
                OncallPlan oncallPlan = planOncall(mineRecovered, mineStillDown, oncallReported);
                List<String> mailTo = mergeAddresses(d.emails(), oncallPlan.joinTeam());
                String mailStatus = "SKIPPED: alıcı yok";
                String html = null;
                if (!mailTo.isEmpty()) {
                    // "Hâlâ erişilemeyen" sayacı da TAKIM kapsamlı: listenin kendisi öyle ve
                    // webhook partı (aşağıda) zaten mineStillDown kullanıyordu — e-posta hesap
                    // geneli sayı geçtiği için aynı olay iki kanalda farklı rakam veriyordu.
                    html = emailService.buildStormRecoveryHtml(
                            mineRecovered.size(), mineStillDown.size(), scopeLabel,
                            storm.getCreatedAt(), storm.getResolvedAt(), targets, extra, stillDownTargets);
                    String text = emailService.buildStormRecoveryText(
                            mineRecovered.size(), mineStillDown.size(), scopeLabel,
                            storm.getCreatedAt(), storm.getResolvedAt(), targets, extra, stillDownTargets);
                    mailStatus = emailService.sendHtml(mailTo.toArray(new String[0]), null, subject, html, text, List.of(), false, null);
                }
                // Çözüm postası yalnız KURTULAN üyelerin günlüğüne (hâlâ-down üye için bu bir çözüm değildir).
                logStormMail(new TeamDispatch(d.teamId(), d.teamName(), mailTo, d.webhooks(), mineRecovered, List.of()),
                        storm, TRIGGER_STORM_RESOLVE, subject, html, mailStatus);
                for (OncallMail om : oncallPlan.separate()) sendOncallStormRecovery(storm, d, scopeLabel, om);

                enqueueStormPush(storm, d, "RESOLVE", new UserPushService.LocalizedText(
                        mineRecovered.size() + " monitör kurtarıldı — " + scopeLabel
                                + (mineStillDown.isEmpty() ? "" : " · hâlâ erişilemeyen: " + mineStillDown.size()),
                        mineRecovered.size() + " monitors recovered — " + scopeLabel
                                + (mineStillDown.isEmpty() ? "" : " · still unreachable: " + mineStillDown.size())));

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

    /** Nöbetçiye ayrı çözüm postası — yalnız açılışını aldığı üyeler; her kurtulan üyenin günlüğüne bir satır. */
    private void sendOncallStormRecovery(AlertStorm storm, TeamDispatch d, String scopeLabel, OncallMail om) {
        String subject = "[Site Monitor ✅ ÇÖZÜLDÜ] Alarm fırtınası sona erdi — "
                + om.recovered().size() + " monitör kurtarıldı — " + scopeLabel;
        String html = null;
        String status;
        try {
            List<String> targets = sampleTargets(om.recovered());
            int extra = Math.max(0, om.recovered().size() - targets.size());
            List<String> stillDownTargets = sampleTargets(om.stillDown());
            html = emailService.buildStormRecoveryHtml(om.recovered().size(), om.stillDown().size(), scopeLabel,
                    storm.getCreatedAt(), storm.getResolvedAt(), targets, extra, stillDownTargets);
            String text = emailService.buildStormRecoveryText(om.recovered().size(), om.stillDown().size(), scopeLabel,
                    storm.getCreatedAt(), storm.getResolvedAt(), targets, extra, stillDownTargets);
            status = emailService.sendHtml(om.to().toArray(new String[0]), null, subject, html, text, List.of(), false, null);
        } catch (Exception e) {
            log.warn("Storm #{} nöbetçi çözüm postası gönderilemedi: {}", storm.getId(), e.toString());
            status = "FAILED: " + e.getClass().getSimpleName();
        }
        logStormMail(new TeamDispatch(d.teamId(), d.teamName(), om.to(), Map.of(), om.recovered(), List.of()),
                storm, TRIGGER_STORM_RESOLVE, subject, html, status);
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
            // UG = SY ise üye bir kez sayılır (inceleme 5; EscalationContactScope ile aynı): aynı gönderime iki kez girip
            // "2 monitör kurtarıldı" ve çift günlük satırı üretiyordu
            if (ugTeamId != null && ugTeamId.equals(teamId)) ugTeamId = null;
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
        // Zamana bağlı eskalasyon adımı (2026-10-01): gecikmeli kişi toplu fırtına postasına GİRMEZ — fırtına üyesi alarm
        // adım üretmez ("fırtınaya devredilen alarm eskale olmaz"). Gecikme tanımsızsa liste aynen döner.
        List<EscalationContact> contacts = EscalationContactScope.forLevel(contactRepo, level, teamId);
        if (inactiveGuard != null) contacts = inactiveGuard.withoutInactive(contacts, "fırtına takım " + teamId + " seviye " + level);
        return EscalationDelay.immediateOnly(contacts);
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
            case EscalationService.TYPE_DBQUERY_FAIL  -> "Veritabanı Sorgusu Çalıştırılamıyor";
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

    // ── Dağıtık kilit (StormService lifecycle tek-yazar) ──
    //    Küme kilidi servisi varsa (üretim) ondan alınır: arka planda yenilenir, kayıp held() ile görülür, kapanışta yeni kilit
    //    verilmez (inceleme R-2) ve tutulan bırakılır. Yoksa (bağlamsız birim testleri) SchedulerService desenini izleyen
    //    kendi SQL'i — aynı satır, aynı anlam (SchedulerService'i enjekte etmek döngü yaratır:
    //    EscalationService→StormService→SchedulerService→EscalationService).

    /** Bu turun kilidi: küme kilidi tutuşu ({@code hold}) ya da eski kendi SQL'i ({@code hold == null}, elle tazelenir). */
    final class LockGrant {
        private final com.sitemonitor.service.schedule.ClusterLockService.Hold hold;
        private boolean renewed;

        LockGrant(com.sitemonitor.service.schedule.ClusterLockService.Hold hold) {
            this.hold = hold;
        }

        com.sitemonitor.service.schedule.ClusterLockService.Hold hold() {
            return hold;
        }

        /** Kilit hâlâ bu pod'da mı; eski yolda tazeleme vakti geldiyse tazeler ({@link #renewedLegacy}). */
        boolean stillHeld(long lockAt) {
            renewed = false;
            if (hold != null) return lockService == null || lockService.held(LOCK_NAME);
            if (System.currentTimeMillis() - lockAt < lockRenewEveryMs) return true;
            renewed = true;
            return renewLock();
        }

        boolean renewedLegacy() {
            return renewed;
        }
    }

    private LockGrant tryLock() {
        if (shuttingDown()) return null;
        if (lockService != null) {
            return lockService.tryAcquire(LOCK_NAME, java.time.Duration.ofSeconds(Math.max(LOCK_TTL_SECONDS, 120)),
                            com.sitemonitor.service.schedule.ClusterLockService.hardCapFor(LOCK_NAME))
                    .map(LockGrant::new).orElse(null);
        }
        String lockName = LOCK_NAME;
        try {
            String nowIso = now();
            String until = ISO.format(Instant.now().plusSeconds(LOCK_TTL_SECONDS));
            jdbcTemplate.update("DELETE FROM scheduler_lock WHERE name = ? AND locked_until < ?", lockName, nowIso);
            jdbcTemplate.update("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)",
                    lockName, lockOwner, until);
            return new LockGrant(null);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return null;   // başka pod yönetiyor — tipli yakalama, mesaj metnine bağımlılık yok
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // Kilit TABLOSU yok (ilk açılışta ddl-auto henüz koşmadı) — bilinçli tek-pod geri düşüşü.
            log.warn("Storm kilit tablosu erişilemez (HA degraded): {}", e.getMessage());
            return new LockGrant(null);
        } catch (Exception e) {
            if (isUniqueViolation(e)) return null;   // sürücü tipli istisna vermediyse metin yedeği
            // SchedulerService.tryAcquireSchedulerLock ile aynı kural: geçici DB hatasında (deadlock,
            // statement-timeout, bağlantı kopması) fail-OPEN çok-pod'da ÇİFT lifecycle sweep = çift
            // fırtına mail/webhook demekti. Güvenli taraf bu turu ATLAMAK — 30 sn sonra tekrar denenir.
            log.warn("Storm kilidi alınamadı — bu tur atlanıyor (güvenli taraf): {}", e.getMessage());
            return null;
        }
    }

    static boolean isUniqueViolation(Exception e) {
        String m = e.getMessage();
        return m != null && (m.contains("UNIQUE") || m.contains("unique") || m.contains("duplicate"));
    }

    private void releaseLock(LockGrant grant) {
        if (grant != null && grant.hold() != null && lockService != null) {
            lockService.release(grant.hold());
            return;
        }
        try {
            jdbcTemplate.update("DELETE FROM scheduler_lock WHERE name = ? AND locked_by = ?", LOCK_NAME, lockOwner);
        } catch (Exception e) {
            log.debug("Storm kilidi bırakılamadı: {}", e.getMessage());
        }
    }

    /** Yarıda kalmış kapanışların kimlikleri ({@link #SQL_CLOSING_IDS}); okunamazsa istisna (çağıran sessizce atlar). */
    List<Long> closingIds() {
        List<Long> ids = jdbcTemplate.queryForList(SQL_CLOSING_IDS, Long.class);
        return ids == null ? List.of() : ids;
    }

    /** Kilit tazeleme — yalnız hâlâ BU pod'unsa (2026-10-21, çok pod — MP-04). */
    static final String SQL_LOCK_RENEW = "UPDATE scheduler_lock SET locked_until = ? WHERE name = ? AND locked_by = ?";

    /**
     * Kilidi tazeler; {@code false} = artık bu pod'un değil (süresi doldu ve başka pod aldı) ya da yazılamadı → çağıran
     * turu durdurur. Kilit TABLOSU yoksa (tek pod geri düşüşü, bkz. {@link #tryLock}) tazeleme de sorunsuz sayılır.
     */
    boolean renewLock() {
        try {
            String until = ISO.format(Instant.now().plusSeconds(LOCK_TTL_SECONDS));
            return jdbcTemplate.update(SQL_LOCK_RENEW, until, LOCK_NAME, lockOwner) == 1;
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            return true;
        } catch (Exception e) {
            log.warn("Storm kilidi tazelenemedi — tur durduruluyor (güvenli taraf): {}", e.getMessage());
            return false;
        }
    }

}
