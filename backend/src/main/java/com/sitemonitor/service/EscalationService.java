package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EscalationService {

    private final AlertEventRepository alertEventRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final EscalationContactRepository contactRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final EmailNotificationService emailService;
    private final WeeklyAvailabilityReportService weeklyAvailability;   // recovery uptime özeti (döngü yok)
    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final NotificationLogRepository notificationLogRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final TeamRepository teamRepo;
    private final SmtpSettingsService smtpSettings;
    private final MaintenanceService maintenanceService;
    private final StormService stormService;
    // Kişi-webhook (push) kanalı — mail hattından TAMAMEN bağımsız; tetik her zaman try/catch
    // zarfında ve enqueue kendi içinde de istisna yutar (kanal bağımsızlığı sözleşmesi).
    private final UserPushService userPushService;
    // Domain monitör alarmlarının resend/çözüm mailine EN GÜNCEL kayıt bağlamını (bitiş/registrar/EPP) kurmak için.
    private final com.sitemonitor.repository.DomainMonitorRepository domainMonitorRepo;
    private final com.sitemonitor.repository.DomainCheckRepository domainCheckRepo;
    // DNS_CHANGED manuel re-notify'ında eski/yeni değer ctx'ini son changed kayıttan kurmak için (günlük re-alert paritesi).
    private final com.sitemonitor.repository.DnsRecordRepository dnsRecordRepo;
    // Sayfa çözüm mailinde "güncel durum" satırı için son PageCheck (2026-08-04 — çözüm maili detayları).
    private final com.sitemonitor.repository.PageCheckRepository pageCheckRepo;
    /**
     * Takim alicilarinin GRUP bileseni. Yalniz "gecerli bir grup var mi" sorusunu yanitlar;
     * yoksa asagidaki metotlar KENDI mevcut {@code Team.email} kollarini aynen isletir.
     */
    private final NotificationGroupService notificationGroups;
    /** Güven alarmlarının aç/kapa anahtarları CANLI okunur (trust.ca-bundle-pem deseni). */
    private final AppSettingsService appSettings;

    // Self-injection (@Lazy avoids circular dep) — needed to invoke @Async methods via proxy
    @Autowired @Lazy
    private EscalationService self;

    /**
     * 7/24 İzleme Ekibi (NOC) — takım e-postasından BAĞIMSIZ ikinci hedef (2026-09-27). Karar ve teslim TEK
     * serviste ({@code NocNotificationService}); bu sınıf yalnız açılış ve çözüm anında haber verir. Alan
     * enjeksiyonu + isteğe bağlı: testler servisi elle kuruyor, yokken (null) NOC yolu hiç koşmaz.
     */
    @Autowired(required = false)
    private com.sitemonitor.service.noc.NocNotificationService nocNotifications;

    /**
     * Nöbet çizelgesi (2026-10-11, kullanıcı kararı: "takım seçerse nöbetçiye de"). Takım çizelgesinde "nöbetçiye de
     * bildir" açıksa YÜKSEK / KRİTİK alarm bildirimi (açılış, eskalasyon, günlük tekrar, elle gönderim, çözüm) o anki
     * nöbetçiye de gider (e-posta + push). Mevcut alıcılar değişmez; seçenek kapalıyken / çizelge yokken çıktı bayt bayt
     * aynı. Alan enjeksiyonu + isteğe bağlı (NOC deseni): testler servisi elle kuruyor, yokken (null) hiç okunmaz.
     */
    @Autowired(required = false)
    private com.sitemonitor.service.oncall.OnCallService onCallService;

    /**
     * Zamana bağlı eskalasyon adımı kararları (2026-10-01) — anlık yolların "bu gecikmeli kişi alarmın döngüsünde mi?"
     * sorusu. Alan enjeksiyonu + isteğe bağlı (NOC deseni): testler servisi elle kuruyor. YALNIZ alıcı listesinde gecikmeli
     * kişi varken okunur; gecikme tanımsız kurulumda hiç dokunulmaz. Yokken (null) gecikmeli kişiler anlık listeden düşer.
     */
    @Autowired(required = false)
    private com.sitemonitor.repository.AlertEscalationStepRepository escalationStepRepo;

    /**
     * Runbook notu (2026-10-01) — hedefin "Rehber &amp; Notlar" rehberini alarm e-postasının / webhook mesajının sonuna
     * ekler. Alan enjeksiyonu + isteğe bağlı (NOC deseni): testler servisi elle kuruyor; yokken (null) not hiç
     * eklenmez ve bildirimler bugünküyle bayt bayt aynıdır.
     */
    @Autowired(required = false)
    private RunbookNoteService runbookNotes;

    /**
     * Takım sessiz saatleri (2026-10-01, onaylı öneri 15) — opt-in erteleme kararı. Alan enjeksiyonu + isteğe bağlı (NOC
     * deseni): testler servisi elle kuruyor; yokken (null) ya da hiçbir takımda pencere tanımlı değilken gönderim yolu
     * bugünküyle bayt bayt aynıdır (karar bellek-içi önbellekten — gönderim başına sorgu yok).
     */
    @Autowired(required = false)
    private TeamQuietHoursService quietHours;

    /**
     * Pasif kullanıcı süzgeci (2026-10-02, kullanıcı kararı: pasif kullanıcıya hiçbir bildirim gitmez) — {@code user_id}'si
     * pasif kullanıcıya bağlı eskalasyon kişisi {@link #getContactsForLevel}'te düşer (e-postası VE webhook'u). Alan
     * enjeksiyonu + isteğe bağlı: elle kurulan testlerde yoktur; hiç pasif kullanıcı yokken liste AYNI nesne döner.
     */
    @Autowired(required = false)
    private InactiveRecipientGuard inactiveGuard;

    /** Test kancası. */
    void setInactiveGuard(InactiveRecipientGuard guard) { this.inactiveGuard = guard; }

    /**
     * Sistem Bakım Modu (2026-10-02, kullanıcı kararı) — "Bildirimler bakım boyunca sussun" açık bakım AKTİFKEN alarm
     * bildirimleri (e-posta, kontak webhook'u, kişi push'u, 7/24) gönderilmez, her biri iz bırakır. Alan enjeksiyonu +
     * isteğe bağlı (sessiz saat / pasif süzgeç deseni): elle kurulan testlerde yoktur; yokken ya da bakım yokken / anahtar
     * kapalıyken gönderim yolu bayt bayt bugünküdür (karar pod önbelleğinden — gönderim başına sorgu yok).
     */
    @Autowired(required = false)
    private SystemMaintenanceService systemMaintenance;

    /**
     * Çok pod alarm sahiplenmeleri (2026-10-21, çok pod R1 / MP-03, MP-07): sertifika alarmı AÇILIŞININ anahtar kilidi
     * ({@code scheduler_lock} "mon-alert:&lt;tür&gt;:&lt;alan&gt;"), yeniden uyarı / eskalasyon / telafi damgasının CAS
     * sahiplenmesi ve çözüm bildiriminin kalıcı işareti ({@code alert_events.resolution_notify}). Alan enjeksiyonu + isteğe
     * bağlı: elle kurulan birim testlerinde yoktur ve davranış bugünküyle aynıdır (kilit yok, sahiplenme hep kazanır, işaret
     * yazılmaz).
     */
    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** Kilit sahibi kimliği — üretimde pod'un kimliği ({@code SchedulerService.instanceId()}); test iki "pod"u ayırır. */
    private String lockOwner = SchedulerService.instanceId();

    /** Test dikişi. */
    void setJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Test dikişi. */
    void setLockOwner(String lockOwner) {
        this.lockOwner = lockOwner;
    }

    /**
     * Kapanışların KOŞULLU yazımı (2026-10-23, çok pod R2b · F): sessiz kapanış, envanter silme / pasifleştirme ve koşullu
     * kapanışı (CAS) kazanmış çözümün ardından okunan alarm varlığı BÜTÜNÜYLE kaydedilmez ({@code AlertEvent}'te
     * {@code @Version} / {@code @DynamicUpdate} yok — bayat kopyanın kaydı okumadan sonra yazılmış onayı, yeniden uyarı
     * damgasını, başka yolun çözümünü geri alıyor, kapanmış alarmın {@code resolved_by}'ını ezip ikinci çözüm push'unu
     * deniyordu). Alan enjeksiyonu + isteğe bağlı: elle kurulan birim testlerinde yoktur ve yollar bugünküyle aynıdır.
     */
    @Autowired(required = false)
    private SilentAlarmCloser silentAlarmCloser;

    /** Test dikişi. */
    void setSilentAlarmCloser(SilentAlarmCloser silentAlarmCloser) {
        this.silentAlarmCloser = silentAlarmCloser;
    }

    /**
     * Gönderim sonrası / damga / terfi / onay kayıtlarının HEDEFLİ yazımı (2026-10-23, çok pod R2b · F): okunan alarm bütünüyle
     * kaydedilmez, yalnız yolun değiştirdiği kolonlar yazılır ({@link AlertEventFieldWriter}; kapanış kolonlarını hiç yazmaz).
     * Okuma ile kayıt arasında (çoğu zaman SMTP gönderimi) kapanan alarm YENİDEN AÇILMAZ, arada yazılmış onay / damga / fırtına
     * bağı geri alınmaz. Alan enjeksiyonu + isteğe bağlı: yokken (elle kurulan birim testleri) bugünkü {@code save}.
     */
    @Autowired(required = false)
    private AlertEventFieldWriter fieldWriter;

    /** Test dikişi. */
    void setFieldWriter(AlertEventFieldWriter fieldWriter) {
        this.fieldWriter = fieldWriter;
    }

    /**
     * {@code props} kolonlarını bellekteki değerlerle hedefli yazar ve {@code event}'i döndürür; yazıcı yokken ya da kimliksiz
     * satırda eski yol ({@code save} — dönüşü kullanılır).
     */
    private AlertEvent persist(AlertEvent event, String... props) {
        AlertEventFieldWriter writer = fieldWriter;
        if (writer == null || event == null || event.getId() == null) return alertEventRepo.save(event);
        writer.write(event, false, props);
        return event;
    }

    /** {@link #persist} — yalnız alarm hâlâ AÇIKSA; {@code false} = arada kapandı, hiçbir şey yazılmadı. Yazıcı yokken eski kayıt. */
    private boolean persistIfOpen(AlertEvent event, String... props) {
        AlertEventFieldWriter writer = fieldWriter;
        if (writer == null || event == null || event.getId() == null) {
            alertEventRepo.save(event);
            return true;
        }
        return writer.write(event, true, props) > 0;
    }

    /**
     * Fırtınaya bağlanan YENİ alarm, bağ yazılmadan önce kapandı (elle çözüm — inceleme AL-4): üye değildir. Bellekteki bağ
     * düşer (bağlanma doğrulaması atlanır) ve gözlem satırı "düzeldi" ile kapanır (yoksa üye listesinde süresiz katılmış görünürdü).
     */
    private void leftStormClosedMeanwhile(AlertEvent event) {
        Long stormId = event.getStormId();
        event.setStormId(null);
        log.info("🌩 Alarm fırtınaya bağlanırken kapandı — üye yapılmadı: {} [{}] #{} (storm #{})", event.getDomain(),
                event.getAlertType(), event.getId(), stormId);
        if (stormId == null || jdbcTemplate == null) return;
        try {
            jdbcTemplate.update(StormService.SQL_MEMBER_LEFT, now(), com.sitemonitor.model.AlertStormMember.LEAVE_RECOVERED,
                    stormId, event.getId());
        } catch (Exception e) {
            log.debug("Fırtına üyelik ayrılışı yazılamadı (storm #{}, olay {}): {}", stormId, event.getId(), e.getMessage());
        }
    }

    /** Test kancası. */
    void setSystemMaintenance(SystemMaintenanceService s) { this.systemMaintenance = s; }

    /** Bu tetikteki bildirim sistem bakımı nedeniyle susturulsun mu? Elle gönderim (MANUAL) operatör iradesidir — susmaz. */
    private boolean systemMaintenanceMuted(String trigger) {
        if (systemMaintenance == null || TRIGGER_MANUAL.equals(trigger)) return false;
        try {
            return systemMaintenance.notificationsMuted();
        } catch (Exception e) {
            return false;   // durum okunamazsa bildirim bugünkü gibi gider (sessiz kayıp yok)
        }
    }

    static final String TRIGGER_MANUAL = "MANUAL";
    /** Elle gönderimde e-posta alıcılarının hepsi onay penceresinde çıkarıldı (yalnız push / webhook gitti). */
    static final String STATUS_SKIPPED_EMAIL_DESELECTED = "SKIPPED: e-posta alıcısı seçilmedi (elle gönderim — yalnız push / webhook)";

    /**
     * Sistem bakımı susturmasının İZİ ("never silent"): alarmın bildirim günlüğüne {@code SYSTEM_MAINTENANCE /
     * SKIPPED: sistem bakımı} satırı, push kararına {@code SKIPPED_SYSTEM_MAINTENANCE}, bakım kaydına sayaç + telafi satırı
     * (açılış INITIAL/ESCALATION ise bakım bitince BİR kez telafi edilir). Hata bildirim hattına yayılmaz.
     */
    void recordSystemMaintenanceSuppression(Long alertEventId, Long syTeamId, Long ugTeamId, String domain,
                                            String level, String alertType, String trigger, String message) {
        log.info("Sistem bakımı — alarm bildirimi susturuldu: olay={} alan={} tür={} seviye={} tetik={} "
                + "(e-posta, webhook, push ve 7/24 atlandı)", alertEventId, domain, alertType, level, trigger);
        if (alertEventId != null) {
            saveLog(alertEventId, ownerTeamNames(syTeamId, ugTeamId), "",
                    "Sistem bakımı — " + (trigger == null ? "" : trigger) + " bildirimi susturuldu",
                    message != null ? message : "", SystemMaintenanceService.STATUS_SKIPPED, "SKIPPED",
                    SystemMaintenanceService.TRIGGER);
            try {
                alertEventRepo.findById(alertEventId).ifPresent(e ->
                        userPushService.recordSuppressedFor(e, trigger, SystemMaintenanceService.PUSH_SKIPPED));
            } catch (Exception e) {
                log.warn("Sistem bakımı push kararı yazılamadı (olay {}): {}", alertEventId, e.getMessage());
            }
        }
        try {
            systemMaintenance.noteSuppressed(alertEventId, trigger);
        } catch (Exception e) {
            log.warn("Sistem bakımı susturma kaydı yazılamadı (olay {}): {}", alertEventId, e.getMessage());
        }
    }

    // Inter-domain catch-up pacing now comes from the DB-backed SMTP settings
    // (admin Settings → SMTP → Gelişmiş), falling back to the env default.
    private long interDomainDelayMs() {
        Integer v = smtpSettings.getOrDefaults().getInterDomainDelayMs();
        return v != null ? v.longValue() : 3000L;
    }

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Sabit-genişlik ISO string'i LocalDateTime'a geri ayrıştırmak için (re-alert kadans matematiği). */
    private static final DateTimeFormatter LDT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final Map<String, Integer> LEVEL_ORDER = Map.of(
            "WARNING", 1, "HIGH", 2, "CRITICAL", 3);

    /** HTTP erişilebilirlik kesintisi alarmı — uptime sweep'i tarafından yönetilir. */
    public static final String TYPE_ACCESSIBILITY = "ACCESSIBILITY";

    /** Port kesintisi alarmı — port sweep'i tarafından yönetilir. */
    public static final String TYPE_PORT_DOWN = "PORT_DOWN";

    /** Port yavaş yanıt alarmı — port sweep'i tarafından yönetilir (PORT_DOWN'dan AYRI; aynı envanter-yönlendirmesi). */
    public static final String TYPE_PORT_SLOW = "PORT_SLOW";

    /** DNS çözümleme hatası alarmı — DNS sweep'i tarafından yönetilir. */
    public static final String TYPE_DNS_FAILURE = "DNS_FAILURE";

    /** DNS kayıt değişikliği alarmı (YÜKSEK) — teyitsiz, otomatik kapanmaz. */
    public static final String TYPE_DNS_CHANGED = "DNS_CHANGED";

    /** DNS yavaş/timeout'lu çözümleme alarmı (YÜKSEK) — çözüm başarılı ama response süresi eşiği aşıyor
     *  (primary DNS timeout → fallback). DNS sweep teyit zinciriyle doğrular. */
    public static final String TYPE_DNS_SLOW = "DNS_SLOW";

    /** DNS beklenen-değer kilidi alarmı (YÜKSEK) — canlı sonuçta, monitöre sabitlenen "beklenen değer"de
     *  OLMAYAN bir değer çözümlenir (esnek/hijack-odaklı). State alarmı: değer beklenene dönünce oto-kapanır. */
    public static final String TYPE_DNS_UNEXPECTED = "DNS_UNEXPECTED";

    /** DNS çoklu-resolver tutarsızlığı alarmı (YÜKSEK) — domain public resolver'lar (8.8.8.8/1.1.1.1/...)
     *  arasında farklı cevap döndürür (propagation gecikmesi / split-DNS / poisoning). State; oto-kapanır. */
    public static final String TYPE_DNS_INCONSISTENT = "DNS_INCONSISTENT";

    /** Keyword monitor alarmı — keyword sweep'i tarafından yönetilir. */
    public static final String TYPE_KEYWORD = "KEYWORD";

    /** Keyword monitör yan alarmları (yavaş yanıt / URL host'unun SSL / domain bitişi) — HTTP tiplerinden AYRI. */
    public static final String TYPE_KEYWORD_SLOW = "KEYWORD_SLOW";
    public static final String TYPE_KEYWORD_SSL = "KEYWORD_SSL";
    public static final String TYPE_KEYWORD_DOMAIN_EXPIRY = "KEYWORD_DOMAIN_EXPIRY";
    public static boolean isKeywordAux(String t) {
        return TYPE_KEYWORD_SLOW.equals(t) || TYPE_KEYWORD_SSL.equals(t) || TYPE_KEYWORD_DOMAIN_EXPIRY.equals(t);
    }

    /** Ping (ICMP) kesintisi alarmı — ping sweep'i tarafından yönetilir. */
    public static final String TYPE_PING_DOWN = "PING_DOWN";

    /** Ping yavaşlık — host yanıt veriyor ama KENDİ taban çizgisinin üstünde (göreli eşik). */
    public static final String TYPE_PING_SLOW = "PING_SLOW";

    /** HTTP/Website erişilebilirlik kesintisi alarmı — HTTP sweep'i tarafından yönetilir. */
    public static final String TYPE_HTTP_DOWN = "HTTP_DOWN";

    /** HTTP monitörü TLS sertifika hatası/bitişi alarmı — yavaş SSL döngüsü tarafından yönetilir. */
    public static final String TYPE_HTTP_SSL = "HTTP_SSL";

    /** HTTP monitörü yavaş yanıt alarmı (2026-10-01, onaylı öneri 9) — OPT-IN (slowResponseEnabled); KEYWORD_SLOW'un
     *  ikizi. Kesinti DEĞİLDİR: fırtına sayımına girmez (DOWN_ALERT_TYPES dışında). */
    public static final String TYPE_HTTP_SLOW = "HTTP_SLOW";

    /** Domain (registrar/WHOIS) kayıt bitişi alarmı — yavaş domain döngüsü tarafından yönetilir. */
    public static final String TYPE_DOMAIN_EXPIRY = "DOMAIN_EXPIRY";

    /** Bağımsız Domain izleme tipi alarmları (DOMAINMON_* — HTTP'nin DOMAIN_EXPIRY'sinden AYRI, domain-anahtarlı). */
    public static final String TYPE_DOMAINMON_EXPIRY  = "DOMAINMON_EXPIRY";
    public static final String TYPE_DOMAINMON_UNKNOWN = "DOMAINMON_UNKNOWN";
    public static final String TYPE_DOMAINMON_STATUS  = "DOMAINMON_STATUS";
    public static final String TYPE_DOMAINMON_CHANGED = "DOMAINMON_CHANGED";
    /** Transfer kilidi yok — eskiden STATUS'a warn olarak karışıyordu. "autoRenewPeriod" ile
     *  "kilit yok" operasyonel olarak farklı işlerdir; tek alarmda ayırt edilemiyordu. */
    public static final String TYPE_DOMAINMON_TRANSFER_LOCK = "DOMAINMON_TRANSFER_LOCK";
    /** Alan adı / IP'leri e-posta kara listesinde. */
    public static final String TYPE_DOMAINMON_BLACKLIST = "DOMAINMON_BLACKLIST";
    public static boolean isDomainMon(String t) {
        return TYPE_DOMAINMON_EXPIRY.equals(t) || TYPE_DOMAINMON_UNKNOWN.equals(t)
            || TYPE_DOMAINMON_STATUS.equals(t) || TYPE_DOMAINMON_CHANGED.equals(t)
            || TYPE_DOMAINMON_TRANSFER_LOCK.equals(t) || TYPE_DOMAINMON_BLACKLIST.equals(t);
    }

    /** Sayfa-bütünlüğü (9. tür) alarmları: DOWN = ana sayfa alınamıyor (CRITICAL); INTEGRITY = kırık kaynak /
     *  mixed content (DEGRADED, HIGH). İkisi de Page sweep'i tarafından, ayrı confirmation/recovery yaşam
     *  döngüleriyle yönetilir (KEYWORD'ün çok-alarm-tipli deseniyle aynı). */
    public static final String TYPE_PAGE_DOWN = "PAGE_DOWN";
    public static final String TYPE_PAGE_INTEGRITY = "PAGE_INTEGRITY";
    public static boolean isPage(String t) {
        return TYPE_PAGE_DOWN.equals(t) || TYPE_PAGE_INTEGRITY.equals(t);
    }

    /** Senaryo İzleme (k6): FAIL = PASS değil (kesinti); SLOW = koşum süresi opt-in eşiği aştı (kesinti DEĞİL). */
    public static final String TYPE_SCRIPTED_FAIL = "SCRIPTED_FAIL";
    public static final String TYPE_SCRIPTED_SLOW = "SCRIPTED_SLOW";
    public static boolean isScripted(String t) {
        return TYPE_SCRIPTED_FAIL.equals(t) || TYPE_SCRIPTED_SLOW.equals(t);
    }

    /** Sayfa Hızı (10. tür): DOWN = sayfa hiç alınamadı (kesinti, CRITICAL); SLOW = bir eşik aşıldı
     *  (performans olayı, kesinti DEĞİL — uptime'a işlemez). Ayrımı korumak şart: yavaş sayfa çökmüş
     *  sayfayla aynı kovaya girerse uptime rakamları anlamsızlaşır. */
    public static final String TYPE_PAGESPEED_DOWN = "PAGESPEED_DOWN";
    public static final String TYPE_PAGESPEED_SLOW = "PAGESPEED_SLOW";
    public static boolean isPageSpeed(String t) {
        return TYPE_PAGESPEED_DOWN.equals(t) || TYPE_PAGESPEED_SLOW.equals(t);
    }
    /** Veritabanı sorgu izlemesi (11. tür, 2026-10-31): FAIL = sorgu çalıştırılamadı (kesinti), THRESHOLD = kural ihlali
     *  (iş alarmı, kesinti değil), SLOW = sorgu süresi eşiği. Adlar kalıcıdır. */
    public static final String TYPE_DBQUERY_FAIL = "DBQUERY_FAIL";
    public static final String TYPE_DBQUERY_THRESHOLD = "DBQUERY_THRESHOLD";
    public static final String TYPE_DBQUERY_SLOW = "DBQUERY_SLOW";
    public static boolean isDbQuery(String t) {
        return TYPE_DBQUERY_FAIL.equals(t) || TYPE_DBQUERY_THRESHOLD.equals(t) || TYPE_DBQUERY_SLOW.equals(t);
    }

    /** İzleme kaynaklı alarm tipleri — kadanslarının sahibi ilgili sweep'lerdir;
     *  cert sweep'inin auto-resolve'u ve startup catch-up bunlara dokunmaz. */
    public static final Set<String> MONITORING_ALERT_TYPES =
            Set.of(TYPE_ACCESSIBILITY, TYPE_PORT_DOWN, TYPE_DNS_FAILURE, TYPE_DNS_CHANGED,
                   TYPE_DNS_SLOW, TYPE_DNS_UNEXPECTED, TYPE_DNS_INCONSISTENT,
                   TYPE_KEYWORD, TYPE_PING_DOWN, TYPE_HTTP_DOWN, TYPE_HTTP_SSL, TYPE_HTTP_SLOW, TYPE_DOMAIN_EXPIRY,
                   TYPE_DOMAINMON_EXPIRY, TYPE_DOMAINMON_UNKNOWN, TYPE_DOMAINMON_STATUS, TYPE_DOMAINMON_CHANGED,
                   TYPE_DOMAINMON_TRANSFER_LOCK, TYPE_DOMAINMON_BLACKLIST,
                   TYPE_KEYWORD_SLOW, TYPE_KEYWORD_SSL, TYPE_KEYWORD_DOMAIN_EXPIRY, TYPE_PORT_SLOW, TYPE_PING_SLOW,
                   TYPE_PAGE_DOWN, TYPE_PAGE_INTEGRITY, TYPE_SCRIPTED_FAIL, TYPE_SCRIPTED_SLOW,
                   TYPE_PAGESPEED_DOWN, TYPE_PAGESPEED_SLOW);

    /** İzleme türlerinden süre-bitişi ailesi ("N gün kaldı" anlamlı) — {@link #isDurationAlert}. */
    public static final Set<String> EXPIRY_MONITORING_TYPES =
            Set.of(TYPE_DOMAINMON_EXPIRY, TYPE_DOMAIN_EXPIRY, TYPE_KEYWORD_DOMAIN_EXPIRY);

    /** Sertifika kaynaklı alarm tipleri — cert sweep'inin auto-resolve kapsamı.
     *  İzleme tipleri bilinçli olarak DIŞINDA: sertifika kontrolünün düzelmesi
     *  site erişiminin/portun/DNS'in düzeldiği anlamına gelmez (ve tersi). */
    /** İstenen host sertifikada kapsanmıyor (tarayıcı: ERR_CERT_COMMON_NAME_INVALID). */
    public static final String TYPE_HOSTNAME_MISMATCH = "HOSTNAME_MISMATCH";
    /** Zincir hiçbir güven köküne bağlanmıyor (tarayıcı: ERR_CERT_AUTHORITY_INVALID). */
    public static final String TYPE_UNTRUSTED_CA = "UNTRUSTED_CA";

    public static final Set<String> CERT_ALERT_TYPES =
            Set.of("EXPIRY", "CHAIN_BROKEN", "REVOKED", "MISMATCH",
                   TYPE_HOSTNAME_MISMATCH, TYPE_UNTRUSTED_CA);

    /** "Erişilemez/çöktü" (DOWN) alarm tipleri — alarm fırtınası (storm) toplaması YALNIZ bunları sayar.
     *  Slow/SSL/expiry/changed/domainmon/cert bilinçli DIŞINDA (bunlar kesinti değildir). */
    public static final Set<String> DOWN_ALERT_TYPES =
            Set.of(TYPE_ACCESSIBILITY, TYPE_HTTP_DOWN, TYPE_PORT_DOWN, TYPE_PING_DOWN, TYPE_DNS_FAILURE, TYPE_KEYWORD,
                   // PAGESPEED_SLOW bilinçli DIŞARIDA: yavaşlık kesinti değildir, storm sayımına girmemeli.
                   TYPE_PAGE_DOWN, TYPE_SCRIPTED_FAIL, TYPE_PAGESPEED_DOWN);

    /**
     * Sertifika sweep'inin YALNIZ KAPANIŞ yolu (prod 2026-09-29, K1'in sertifika eşleniği).
     *
     * <p>Ağ kesintisi şüphesinde (≥%50 ağ-sınıfı hata) ya da sertifika alarmları kapalıyken
     * ({@code expiry.alert-enabled=false}) SchedulerService {@link #processResults}'u tümüyle atlıyordu: yeni alarm
     * açılmaması doğru, ama DOĞRULANMIŞ (status≠error) sonucu artık sorun göstermeyen alan adının açık sertifika alarmı
     * da kapanmıyordu. Burada processResults'taki kapanış kuralının AYNISI uygulanır (bu turda üretilmeyen sertifika
     * türleri kapanır; status=error hiçbir şeyi doğrulamadığı için dokunulmaz); açılış, eskalasyon, yeniden uyarı YOK.
     *
     * @return kapanış değerlendirilen alan adı sayısı
     */
    public int resolveVerifiedStaleCertAlerts(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) return 0;
        List<String> verified = results.stream()
                .filter(r -> r.get("domain") != null && !"error".equals(r.get("status")))
                .map(r -> (String) r.get("domain")).distinct().toList();
        if (verified.isEmpty()) return 0;
        Set<String> openCertKeys = alertEventRepo.findOpenByDomainIn(verified).stream()
                .filter(e -> CERT_ALERT_TYPES.contains(e.getAlertType()))
                .map(e -> e.getDomain() + "|" + e.getAlertType()).collect(java.util.stream.Collectors.toSet());
        Set<String> withOpenCert = openCertKeys.stream().map(k -> k.substring(0, k.lastIndexOf('|')))
                .collect(java.util.stream.Collectors.toSet());
        // D-1 (2026-09-29): sertifika alarm bildirimleri KAPALIYSA kapanış da SESSİZ (e-posta/webhook/7-24 yok; push
        // simetrisi resolveOpenAlertsSilently'de korunur). Kesinti şüphesinde (bildirimler açık) normal çözüm yolu.
        boolean silent = !appSettings.getBoolean("site.monitor.expiry.alert-enabled", true);
        int n = 0;
        for (Map<String, Object> result : results) {
            String domain = (String) result.get("domain");
            if (domain == null || "error".equals(result.get("status")) || !withOpenCert.contains(domain)) continue;
            try {
                Set<String> stale = new LinkedHashSet<>(CERT_ALERT_TYPES);
                stale.removeAll(determineAlertTypes(result));
                if (stale.isEmpty()) continue;
                closeStaleCertTypes(domain, stale, result, silent, t -> openCertKeys.contains(domain + "|" + t));
                n++;
            } catch (Exception e) {
                log.warn("Sertifika kapanış uzlaştırması atlandı: {} — {}", domain, e.getMessage());
            }
        }
        return n;
    }

    public void processResults(List<Map<String, Object>> results) {
        // Tier bazlı eşik (2026-09-20): tablo BİR kez okunur, alan başına envanter tier'ıyla çözülür.
        ThresholdResolution thresholds = ThresholdResolution.load(thresholdRepo, defaultThreshold());
        AlertThreshold threshold = thresholds.defaultThreshold();
        int reAlertIv = threshold.getReAlertIntervalHours() != null ? threshold.getReAlertIntervalHours() : 24;

        // ── BATCH ÖN YÜKLEME (N+1 önleme) ──────────────────────────────────
        // Sweep'te 1000 result × 2 query = 2000 round-trip yerine: 2 query toplam.
        List<String> allDomains = results.stream()
                .map(r -> (String) r.get("domain"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, com.sitemonitor.model.CertificateInventory> invByDomain = allDomains.isEmpty()
                ? Map.of()
                : inventoryRepo.findByDomainIn(allDomains).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            com.sitemonitor.model.CertificateInventory::getDomain,
                            inv -> inv,
                            (a, b) -> a));
        // (domain, alertType) -> AlertEvent (sadece resolved=false olanlar)
        Map<String, AlertEvent> openAlertByKey = allDomains.isEmpty()
                ? Map.of()
                : alertEventRepo.findOpenByDomainIn(allDomains).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getDomain() + "|" + e.getAlertType(),
                            e -> e,
                            (a, b) -> a.getCreatedAt() != null && b.getCreatedAt() != null
                                    && a.getCreatedAt().compareTo(b.getCreatedAt()) >= 0 ? a : b));

        for (Map<String, Object> result : results) {
            String domain = (String) result.get("domain");
            List<String> alertTypes = determineAlertTypes(result);

            // Bu turda ÜRETİLMEYEN cert tipleri kapanır — SADECE cert tipleri; açık bir
            // ACCESSIBILITY alarmı uptime sweep'inin sorumluluğundadır. Eskiden bu kapanış yalnız
            // "hiç alarm yok" dalında çalışıyordu; bir tip başka bir tipi maskelediğinde eski
            // olay asılı kalıyordu.
            Set<String> stale = new LinkedHashSet<>(CERT_ALERT_TYPES);
            stale.removeAll(alertTypes);
            // status=error (timeout/ağ) HİÇBİR şeyi doğrulamamıştır: determineAlertTypes yalnız [EXPIRY]
            // döndüğünden açık REVOKED/MISMATCH/CHAIN_BROKEN/HOSTNAME_MISMATCH/UNTRUSTED_CA "stale"
            // sayılıp "✅ sorun giderildi" maili+push'uyla kapanıyor, bir sonraki temiz turda INITIAL
            // olarak yeniden açılıyordu (tek zaman aşımı = dört mail + bir yalancı yeşil).
            boolean unverified = "error".equals(result.get("status"));
            if (!stale.isEmpty() && !unverified)
                closeStaleCertTypes(domain, stale, result, false, t -> openAlertByKey.containsKey(domain + "|" + t));
            if (alertTypes.isEmpty()) continue;

            for (String alertType : alertTypes) {
                // Route alert to the team that owns this cert — batch'ten lookup
                var inventoryOpt  = Optional.ofNullable(invByDomain.get(domain));
                // Elle yüklenen sertifika (2026-10-06): takip adı host adı değil → ad uyuşmazlığı alarmı YOK (ikinci kapı;
                // birincisi determineAlertTypes'taki manual işareti). Ağ kaydında isManual() false — dal hiç girilmez.
                if (TYPE_HOSTNAME_MISMATCH.equals(alertType)
                        && inventoryOpt.map(com.sitemonitor.model.CertificateInventory::isManual).orElse(false)) continue;
                Integer domainTier = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTier).orElse(null);
                String alertLevel = determineAlertLevel(result, alertType, thresholds.forTier(domainTier));
                if (alertLevel == null) continue;

                Long domainTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
                Long ugTeamId     = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null);

                Integer daysRemaining = toInt(result.get("days_remaining"));
                String message = buildMessage(domain, alertType, alertLevel, daysRemaining)
                        + securityEvidence(alertType, result);

                Optional<AlertEvent> existing = Optional.ofNullable(openAlertByKey.get(domain + "|" + alertType));

                // ÇOK POD (2026-10-21, R1 / MP-03): tur başındaki toplu anlık görüntüde açık alarm yoksa açılış ANAHTAR
                // KİLİDİ altında yapılır ve satır TAZE okunur. processResults sertifika süpürmesinden, yeni envanterin anında
                // kontrolünden (checkSingleDomainAsync, herhangi bir pod) ve elle yüklenen sertifika yolundan çağrılır —
                // anlık görüntüden sonra başka bir yol / pod aynı alarmı açmışsa ikinci bir olay + ikinci INITIAL gidiyordu
                // (farklı olay kimliği: push tekilleştirmesi de yakalamaz). Kilit başkasındaysa bu alan adının bu türü bu
                // turda ATLANIR (o yol açıyor; sonraki tur mevcut-olay dalından devam eder).
                String heldKeyLock = null;
                if (existing.isEmpty() && jdbcTemplate != null) {
                    String keyLock = alertKeyLockName(alertType, domain);
                    KeyLock kl = acquireAlertKeyLock(keyLock);
                    if (kl == KeyLock.BUSY) {
                        log.debug("Sertifika alarm kilidi başka yolda/pod'da: {} — bu tur atlandı", keyLock);
                        continue;
                    }
                    if (kl == KeyLock.ACQUIRED) heldKeyLock = keyLock;
                    try {
                        existing = alertEventRepo.findOpenAlert(domain, alertType);
                    } catch (RuntimeException ex) {
                        if (heldKeyLock != null) releaseAlertKeyLock(heldKeyLock);
                        log.warn("Sertifika alarmı taze okunamadı — {} [{}] bu tur atlandı (güvenli taraf): {}",
                                domain, alertType, ex.getMessage());
                        continue;
                    }
                    if (existing.isPresent() && heldKeyLock != null) {
                        releaseAlertKeyLock(heldKeyLock);   // olay zaten açık — açılış yok, kilit gereksiz
                        heldKeyLock = null;
                    }
                }

                if (existing.isEmpty()) {
                    try {
                        AlertEvent event = newEvent(domain, alertLevel, alertType, message, daysRemaining);
                        event.setNotAfter(notAfterOf(result));   // gerçek bitiş anı; kart hesaplamaz, okur
                        // Sertifika olayına takım DAMGALANMIYORDU: açılış push'u syTeamId yedeğiyle gidiyor,
                        // çözüm push'u ise yalnız event.teamId okuyor → alıcı yok → SKIPPED_NO_RECIPIENTS.
                        // Telefon "KRİTİK: doluyor"u alıyor, "DÜZELDİ"yi hiç almıyordu (izleme yolu :889 ile aynı).
                        event.setTeamId(domainTeamId);
                        // K5: envanterin bildirim grubu alarma DAMGALANIR -- cozum ve yeniden-gonderim
                        // ayni aliciya gitsin diye (canli okuma yapilsaydi grup degisiminde saparlardi).
                        event.setNotificationGroupId(inventoryOpt
                                .map(com.sitemonitor.model.CertificateInventory::getNotificationGroupId)
                                .orElse(null));
                        event = alertEventRepo.save(event);

                        // Gecikmeli eskalasyon kişisi ilk bildirime girmez (yeni olay → henüz döngüde kimse yok; sorgu yok).
                        List<EscalationContact> contacts = EscalationDelay.immediateOnly(
                                getContactsForLevel(alertLevel, domainTeamId, ugTeamId));
                        sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType, message,
                                "", event.getId(), "INITIAL", daysRemaining, result);

                        event.setNotifiedContacts(serializeContacts(contacts));
                        event.setLastReAlertAt(now());
                        persist(event, AlertEventFieldWriter.NOTIFIED);   // R2b · F: gönderim sırasında gelen kapanış / onay geri alınmaz
                    } finally {
                        if (heldKeyLock != null) releaseAlertKeyLock(heldKeyLock);
                    }

                } else {
                    AlertEvent event = existing.get();
                    if (event.getTeamId() == null && domainTeamId != null) {
                        event.setTeamId(domainTeamId);          // eski (damgasız) açık olayları tek seferlik geri doldur
                        persist(event, "teamId");
                    }
                    // E10: alarm AÇIKKEN envanter başka takıma devredilirse ESCALATION / RE-ALERT e-postası,
                    // kontaklar ve 7/24 arama listesi CANLI envanter takımına (Takım B) gidiyor, push ve çözüm
                    // ise damgaya (Takım A) — aynı alarmın bildirimleri iki takıma bölünüyordu. İzleme yolu (O5),
                    // çözüm ve tekrar-bildir ile aynı kural: damga doluysa takım damgadan okunur.
                    if (event.getTeamId() != null) {
                        domainTeamId = event.getTeamId();
                    }
                    boolean escalated = levelValue(alertLevel) > levelValue(event.getAlertLevel());

                    if (escalated) {
                        // ÇOK POD (MP-03): damga GÖNDERİMDEN ÖNCE sahiplenilir — aynı olayı aynı anda işleyen ikinci yol / pod
                        // (yeni envanterin anında kontrolü, açılış telafisi) ikinci ESCALATION göndermez.
                        String seenStamp = event.getLastReAlertAt();
                        String claimAt = now();
                        EscalationUndo undo = EscalationUndo.of(event);
                        if (!claimEscalation(event.getId(), seenStamp, event.getAlertLevel(), alertLevel, claimAt)) continue;
                        // Sahiplenme YENİ seviyeyi yazdı: bundan sonraki HER adım (alıcı sorgusu, kayıt, gönderim) düşerse
                        // terfi geri alınır (inceleme N1 — havuz tükenmesinde kayıt / alıcı sorgusu düşünce seviye yükselmiş
                        // kalıyor, sonraki tur terfiyi "yapılmış" görüyor ve ESCALATION hiç gitmiyordu).
                        AlertEvent claimed = event;
                        try {
                            event.setAlertLevel(alertLevel);
                            event.setMessage(message);
                            event.setDaysRemaining(daysRemaining);
                            if (notAfterOf(result) != null) event.setNotAfter(notAfterOf(result));
                            // ÜÇ alan birden sıfırlanır (izleme yolu :1056-1058 ile aynı). Yalnız
                            // acknowledged düşürülünce acknowledgedAt/By bayat kalıyordu: WARNING'de
                            // onaylanıp KRİTİK'e tırmanan olay, olay ekranında ve mail/incidentMeta
                            // sunumunda hâlâ "X tarafından <eski tarih> onaylandı" taşıyor, yani
                            // tırmanma sonrası kimse onaylamamışken "ele alınmış" görünüyordu.
                            event.setAcknowledged(false);
                            event.setAcknowledgedAt(null);
                            event.setAcknowledgedBy(null);

                            List<EscalationContact> contacts = recipientsNow(alertLevel, domainTeamId, ugTeamId, event.getId());
                            // Terfi ÖNCE kalıcılaşır, SONRA gönderilir (INITIAL dalıyla aynı sıra). Kişi-webhook tetiği
                            // (UserPushService.enqueueAlert) olayı DB'den yeniden yükleyip alıcıyı event.alertLevel ile
                            // çözer; save gönderimden sonra kaldığında eski seviye (WARNING) okunuyor ve ESCALATION
                            // push'u "bu seviyede kimse yok" diye atlanıyordu — e-posta HIGH giderken (prod, 2026-09-07:
                            // eskalasyon push'u SKIPPED, 13 saat sonraki elle yeniden gönderim 5 kişiye SENT).
                            event.setNotifiedContacts(serializeContacts(contacts));
                            event.setLastReAlertAt(claimAt);
                            event = persist(event, AlertEventFieldWriter.CERT_ESCALATION);   // geri almanın kolonlarıyla aynı

                            sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType, message,
                                    "", event.getId(), "ESCALATION", daysRemaining, result);
                        } catch (RuntimeException ex) {
                            // Gönderilemedi: seviye / onay / damga birlikte GERİ alınır → sonraki tur terfiyi yeniden görür ve
                            // ESCALATION'ı gönderir (yalnız damga geri alınsaydı terfi "yapılmış" sayılır, bildirim hiç gitmezdi).
                            revertEscalation(event != null ? event : claimed, undo, claimAt, ex);
                            throw ex;
                        }

                    } else if (!Boolean.TRUE.equals(event.getAcknowledged())) {   // NULL-güvenli (O6)
                        // E11: status=error (zaman aşımı/ağ) turu DOĞRULANMIŞ bir alarm hakkında hiçbir şey
                        // söylemez. KRİTİK (5 gün) alarmın re-alert vakti geçici bir timeout'a denk gelince
                        // seviye UYARI'ya iniyor, mail yalnız UYARI alıcılarına gidiyordu (müdür yok, gün yok);
                        // olay UYARI mesajı + daysRemaining=null ile kaydediliyor, lastReAlertAt sıfırlandığı
                        // için DOĞRU re-alert 24 saat daha kayıyordu (push ise KRİTİK'ten çözülüyordu).
                        // Kapanış korumasıyla (yukarıdaki unverified) aynı ilke: böyle bir tur bildirim
                        // ÜRETMEZ ve olayın hiçbir alanına DOKUNMAZ; ilk doğrulanmış turda gider. Alarmın
                        // kendisi bir "erişilemedi" alarmıysa (gün yok, seviye düşmüyor) bu tur onu zaten
                        // doğrular → günlük hatırlatma eskisi gibi sürer.
                        if (unverified && (event.getDaysRemaining() != null
                                || levelValue(alertLevel) < levelValue(event.getAlertLevel()))) {
                            log.debug("Doğrulanmamış tur (status=error) — açık alarm bildirimi ertelendi: {} [{}]",
                                    domain, alertType);
                            continue;
                        }
                        // E11: alıcı seviyesi olayın seviyesinin ALTINA inmez (push ve çözüm olay seviyesinden
                        // çözülür; mail daha düşük seviyeyle giderse müdür düşer).
                        String sendLevel = levelValue(alertLevel) < levelValue(event.getAlertLevel())
                                ? event.getAlertLevel() : alertLevel;
                        String sendMessage = sendLevel.equals(alertLevel) ? message
                                : buildMessage(domain, alertType, sendLevel, daysRemaining) + securityEvidence(alertType, result);
                        // E9: İLK bildirim hiç tamamlanmadıysa onu ŞİMDİ gönder (izleme yolunun 2026-08-24
                        // aynası). INITIAL dalı olayı kaydedip gönderir, lastReAlertAt'i gönderimden SONRA
                        // damgalar; arada süreç ölürse (deploy/restart/OOM) alarm açık görünür ama hiçbir kanal
                        // duyurmamıştır. Eskiden createdAt'e düşülüp bir re-alert aralığı (24 saat) susuluyordu.
                        if (initialNotificationMissing(event, now())) {
                            // ÇOK POD (MP-03): yarıda kalmış İLK bildirimi tek yol tamamlar (açılış telafisi ile süpürme yarışı)
                            String claimAt = now();
                            if (!claimReAlert(event.getId(), null, claimAt, "INITIAL")) continue;
                            List<EscalationContact> contacts = recipientsNow(sendLevel, domainTeamId, ugTeamId, event.getId());
                            try {
                                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, sendLevel, alertType,
                                        sendMessage, "", event.getId(), "INITIAL", daysRemaining, result);
                            } catch (RuntimeException ex) {
                                releaseReAlert(event.getId(), claimAt, null);
                                throw ex;
                            }
                            event.setNotifiedContacts(serializeContacts(contacts));
                            event.setLastReAlertAt(claimAt);
                            event.setDaysRemaining(daysRemaining);
                            if (notAfterOf(result) != null) event.setNotAfter(notAfterOf(result));
                            event.setMessage(sendMessage);
                            saveUnlessClosedMeanwhile(event, "notifiedContacts", "lastReAlertAt", "daysRemaining", "notAfter",
                                    "message");
                            log.warn("🔴 Yarıda kalmış ilk sertifika bildirimi tamamlandı: {} [{}] — alarm {} tarihinde açılmıştı",
                                    domain, alertType, event.getCreatedAt());
                            continue;
                        }
                        String lastAlertTime = event.getLastReAlertAt() != null
                                ? event.getLastReAlertAt() : event.getCreatedAt();
                        if (reAlertDueFor(alertType, lastAlertTime, now(), reAlertIv)) {
                            // ÇOK POD (MP-03): günlük hatırlatma damgası GÖNDERİMDEN ÖNCE sahiplenilir (StormService
                            // .claimDailyRealert deseni) — açılış telafisi / ikinci yol aynı hatırlatmayı ikinci kez göndermez.
                            String seenStamp = event.getLastReAlertAt();
                            String claimAt = now();
                            if (!claimReAlert(event.getId(), seenStamp, claimAt, "DAILY_REALERT")) continue;
                            List<EscalationContact> contacts = recipientsNow(sendLevel, domainTeamId, ugTeamId, event.getId());
                            try {
                                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, sendLevel, alertType,
                                        "[RE-ALERT] " + sendMessage, "[RE-ALERT] ",
                                        event.getId(), "DAILY_REALERT", daysRemaining, result);
                            } catch (RuntimeException ex) {
                                releaseReAlert(event.getId(), claimAt, seenStamp);
                                throw ex;
                            }

                            event.setLastReAlertAt(claimAt);
                            event.setRealertCount((event.getRealertCount() == null ? 0 : event.getRealertCount()) + 1);
                            event.setDaysRemaining(daysRemaining);
                            if (notAfterOf(result) != null) event.setNotAfter(notAfterOf(result));
                            event.setMessage(sendMessage);
                            saveUnlessClosedMeanwhile(event, "lastReAlertAt", "realertCount", "daysRemaining", "notAfter",
                                    "message");
                            log.info("Re-alert sent: {} [{}] — previous day: {}",
                                    domain, sendLevel, lastAlertTime.substring(0, 10));
                        } else {
                            log.debug("Alert already sent today, skipping: {} [{}] — last: {}",
                                    domain, alertLevel, lastAlertTime.substring(0, 10));
                        }
                    }
                }
            }   // tip döngüsü (bir sonuç birden fazla alarm tipi doğurabilir)
        }
    }

    /**
     * Geriye uyumlu: notsuz onay (eski çağrılar / not gerektirmeyen yollar). R2b · F: işlem AÇMAZ (eskiden {@code @Transactional}
     * idi — yarışı kaybeden onayın taze okuması işlem içindeki önbellekten bayat satırı döndürüyordu); 3 parametreli yolla aynı.
     */
    public AlertEvent acknowledge(Long eventId, String acknowledgedBy) {
        return acknowledge(eventId, acknowledgedBy, null);
    }

    /**
     * Alarmı onaylar; {@code note} verilirse NEDEN onaylandığı da kaydedilir.
     *
     * <p>Not burada DOĞRULANMAZ — doğrulama uçta yapılır ({@link AlertActionNote}) çünkü zorunluluk
     * yalnız manuel akışlar için geçerli; servis katmanı notsuz da çağrılabilmeli.
     */
    public AlertEvent acknowledge(Long eventId, String acknowledgedBy, String note) {
        AlertEvent event = alertEventRepo.findById(eventId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + eventId));
        AlertEventFieldWriter writer = fieldWriter;
        if (writer != null && event.getId() != null) return acknowledgeIfOpen(writer, event, acknowledgedBy, note);
        event.setAcknowledged(true);
        event.setAcknowledgedBy(acknowledgedBy);
        event.setAcknowledgedAt(now());
        if (note != null && !note.isBlank()) event.setAcknowledgedNote(note.trim());
        return alertEventRepo.save(event);
    }

    /**
     * Onayın KOŞULLU yazımı (2026-10-23, çok pod R2b · F): yalnız onay kolonları, yalnız hâlâ AÇIK satıra. Eskiden okunan varlık
     * bütünüyle kaydediliyordu — onay ile kapanış yarışınca (silme / duraklatma / otomatik çözüm / başka pod) bayat kopya
     * {@code resolved = false}'u geri yazıp alarmı YENİDEN AÇIYORDU. 0 satır = alarm bu arada kapandı: onay yazılmaz, alarm kapalı
     * kalır, taze satır döner (yanıt ve denetim sözleşmesi aynı — çağıran olayı alır). Okunan varlık değiştirilmez (işlem içi
     * çağıranda kirli kontrol bütün satırı yazardı); başarıda onay alanları dolu KOPYA döner.
     */
    /**
     * Onay yazılamadı: alarm kapalı (okumadan sonra kapandı ya da zaten kapalıydı) — 409 {@link #CODE} (2026-10-23, inceleme
     * AL-1). Tekil uç 409 + açık metin döner ve denetim kaydı YAZMAZ; toplu uç bu kimliği "atlandı" sayar.
     */
    public static class AlertClosedMeanwhileException extends com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException {
        public static final String CODE = "ALERT_ALREADY_RESOLVED";

        public AlertClosedMeanwhileException() {
            super(CODE, com.sitemonitor.util.Msg.t(
                    "Alarm bu arada kapandı — onaylanacak bir şey kalmadı. Listeyi yenileyin.",
                    "The alert was closed meanwhile — there is nothing to acknowledge. Refresh the list."));
        }
    }

    private AlertEvent acknowledgeIfOpen(AlertEventFieldWriter writer, AlertEvent event, String by, String note) {
        String at = now();
        String trimmed = note != null && !note.isBlank() ? note.trim() : null;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("acknowledged", Boolean.TRUE);
        values.put("acknowledgedBy", by);
        values.put("acknowledgedAt", at);
        if (trimmed != null) values.put("acknowledgedNote", trimmed);
        if (writer.writeValues(event.getId(), values, true) == 0) {
            // İnceleme AL-1: onay YAZILMADI — çağıran "onaylandı" demesin, denetim kaydı yazmasın, toplu işlem atlandı saysın.
            if (alertEventRepo.findById(event.getId()).isEmpty())
                throw new NoSuchElementException("Alert not found: " + event.getId());
            log.info("Onay atlandı — alarm aynı anda kapandı (yarış), yeniden açılmadı: {} [{}] #{}", event.getDomain(),
                    event.getAlertType(), event.getId());
            throw new AlertClosedMeanwhileException();
        }
        AlertEvent out = new AlertEvent();
        org.springframework.beans.BeanUtils.copyProperties(event, out);
        out.setAcknowledged(true);
        out.setAcknowledgedBy(by);
        out.setAcknowledgedAt(at);
        if (trimmed != null) out.setAcknowledgedNote(trimmed);
        return out;
    }

    /**
     * Trigger immediate re-notification.
     * Quick: validates, reads contacts, persists last-realert timestamp, queues async send.
     * Returns immediately so HTTP request does not hold DB connection during SMTP I/O.
     */
    public Map<String, Object> reNotify(Long alertId) {
        return reNotify(alertId, Set.of());   // toplu (bulk) yol ve eski çağrılar — hariç tutma yok
    }

    /** Manuel re-notify hedefleri: takımlar + (varsa) eskalasyon kontakları. */
    private record ReNotifyTargets(Long domainTeamId, Long ugTeamId, List<EscalationContact> contacts,
                                   Long stampedGroupId) {}

    /**
     * Sahipsiz alarm (SY/UG takımı yok) için elle bildirim YOK — açılış/çözüm kapısıyla aynı kural (ürün kararı
     * 2026-09-28). Sessiz "kuyruğa alındı, 0 alıcı" yerine açık 409: önizleme de gönderim de aynı gerekçeyi verir.
     */
    private static void requireOwned(AlertEvent event, ReNotifyTargets targets) {
        if (targets.domainTeamId() != null || targets.ugTeamId() != null) return;
        log.warn("Sahipsiz kayıt — elle bildirim reddedildi: olay={} alan={} tür={}",
                event.getId(), event.getDomain(), event.getAlertType());
        throw new IllegalStateException(com.sitemonitor.util.Msg.t(
                "Sahipsiz alarm — takımı olmadığı için bildirim gönderilmez.",
                "This alert has no owning team, so no notification is sent."));
    }

    /** Onay pop-up'ında gösterilen tek alıcı satırı. kind: TEAM | CONTACT. */
    public record ReNotifyRecipient(String email, String name, String role, String kind) {}

    /**
     * Alıcı çözümü çözüm/ilk-alarm bildirimiyle AYNI olmalı: standalone izleme (domain/keyword/ping/http) takımı
     * cert envanterinden DEĞİL AlertEvent.teamId'den bulunur (aksi halde envanterde olmayan domain monitörü global
     * müdüre düşer). KRİTİK domain alarmında müdür (eskalasyon kontağı) da eklenir; diğer standalone → yalnız takım.
     * reNotify ve previewReNotify BUNU paylaşır — önizleme ile gerçek gönderim asla sapamaz.
     */
    private ReNotifyTargets resolveReNotifyTargets(AlertEvent event) {
        boolean standalone = isStandaloneMon(event.getAlertType());
        if (standalone) {
            // Gecikmeli kişi yalnız "döngüdeyse" (adımı gitmiş) elle gönderime de girer — önizleme aynı listeyi gösterir.
            List<EscalationContact> contacts = includeManagerContacts(event.getAlertType(), event.getAlertLevel())
                    ? recipientsNow(event.getAlertLevel(), event.getTeamId(), null, event.getId())
                    : List.of();
            return new ReNotifyTargets(event.getTeamId(), null, contacts, event.getNotificationGroupId());
        }
        // Bağımsız olay (açılış bağlamında damga) envanterden takım ALMAZ (2026-09-28) — host başka takımın envanterinde olabilir.
        var inventoryOpt = isStandaloneEvent(event)
                ? Optional.<com.sitemonitor.model.CertificateInventory>empty() : inventoryRepo.findByDomain(event.getDomain());
        // Çözüm yoluyla AYNI kural: damgalanmış takım önceliklidir (bkz. sendResolutionNotification).
        Long invTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
        Long domainTeamId = event.getTeamId() != null ? event.getTeamId() : invTeamId;
        Long ugTeamId     = includeInventoryUgTeam(event, invTeamId)
                ? inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null)
                : null;
        return new ReNotifyTargets(domainTeamId, ugTeamId,
                dueNow(contactsFor(isStandaloneEvent(event), event.getAlertLevel(), domainTeamId, ugTeamId),   // O-1 + her sahip kendi kişisi
                        event.getId()),
                event.getNotificationGroupId());
    }

    /**
     * "Tekrar Bildir" onay pop-up'ı için alıcı önizlemesi — sendCombinedAlert sırasıyla (önce takım
     * e-postaları, sonra kontaklar) dedupe'lu liste döner. HİÇBİR yazma yapmaz (notifiedContacts /
     * lastReAlertAt / log / async gönderim yok); hata semantiği reNotify ile birebir aynı.
     */
    /**
     * Webhook onizlemesinin kullanacagi FALLBACK takim — gercek gonderimin
     * ({@code reNotifyAsync} -> {@code sendCombinedAlert} -> {@code enqueueAlert}) gectigi
     * {@code domainTeamId} ile AYNI kaynak. Ayri hesaplansaydi onizleme "gidecek" deyip
     * gonderim baska bir takima cozerdi.
     */
    public Long reNotifyFallbackTeamId(Long alertId) {
        return alertEventRepo.findById(alertId)
                .map(ev -> resolveReNotifyTargets(ev).domainTeamId())
                .orElse(null);
    }

    /**
     * "Kim bilgilendirilir?" simülatörü (2026-09-20, Yönetim Paneli): takım + seviye (+ izleme grubu) için
     * alarm gitmeden alıcı zinciri. Gerçek gönderimle AYNI kararlar: takım e-postaları
     * ({@code collectTeamRecipients}: izleme grubu → takım varsayılan grubu → takım adresi), eskalasyon
     * kişileri ({@code includeManagerContacts} + {@link EscalationContactScope} — YALNIZ takımın kendi kişileri,
     * yoksa hiç; 2026-09-28) ve kişi webhook'ları. HİÇBİR yazma yapmaz.
     *
     * @param standaloneMonitor izleme alarmı mı (HTTP/ping/… — bugün seviye kuralı sertifikayla aynı)
     */
    public Map<String, Object> simulateRecipients(Long teamId, String level, boolean standaloneMonitor, Long groupId) {
        return simulateRecipients(teamId, level, standaloneMonitor, groupId, null);
    }

    /**
     * @param ugTeamId sertifika / envanter türevli alarmda envanterin UG takımı (isteğe bağlı). Gönderimle aynı: UG
     *                 takımının adresi e-postaya eklenir ve UG YALNIZ KENDİ kişilerini getirir ("her sahip takım
     *                 kendi kişisi", 2026-09-28). Bağımsız izleme alarmında UG yoktur — yok sayılır.
     */
    public Map<String, Object> simulateRecipients(Long teamId, String level, boolean standaloneMonitor, Long groupId,
                                                  Long ugTeamId) {
        Long ug = standaloneMonitor || (ugTeamId != null && ugTeamId.equals(teamId)) ? null : ugTeamId;
        String lvl = level == null ? "HIGH" : level.trim().toUpperCase(Locale.ROOT);
        if (!LEVEL_ORDER.containsKey(lvl)) throw new IllegalArgumentException("Bilinmeyen seviye: " + level);
        // Gerçek gönderimle AYNI karar (prod kapısı 2026-09-25, O-1): eskiden kontaklar YALNIZ seviyeye bakılarak
        // kapatılıyordu ve WARNING'de hiç kontak gösterilmiyordu — oysa sertifika / envanter türevli izleme
        // alarmında (bağımsız OLMAYAN) WARNING eşikli kontaklar gerçekte ekleniyor. Yönetici eksik liste görüyordu.
        boolean managers = !teamOnly(standaloneMonitor, lvl);

        List<Map<String, Object>> emails = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] team : collectTeamRecipients(teamId, ug, groupId)) {
            if (!seen.add(team[0].toLowerCase())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email", team[0]); m.put("team", team[1]); m.put("source", team[2]); m.put("kind", "TEAM");
            emails.add(m);
        }
        // Gönderimle AYNI kapsam (EscalationContactScope, 2026-09-28): takımın kendi kontakları; yedek yok. Eski
        // "contacts_fallback_global" bayrağı kalktı — başka takımın kişilerini "global" diye gösteriyordu.
        List<EscalationContact> contacts = managers
                ? withoutInactiveQuiet(EscalationContactScope.forOwners(contactRepo, lvl, teamId, ug)) : List.of();
        // Takım BAŞINA durum: kontak eklenecek seviyede takımda uyan kişi yoksa ekran o takım için "eskalasyon kişisi
        // tanımlı değil / bu seviyeye uyan yok — yalnız takım alıcılarına gider" der. "Tanımlı" = takımda HERHANGİ
        // bir etkin kişi var (eşiği yüksek). Birleşim listesinden değil takımın KENDİ sorgusundan hesaplanır: e-posta
        // tekilleştirmesi bir takımın kişisini diğerinin satırına katlasa bile o takım "tanımlı" sayılır.
        List<Map<String, Object>> owners = new ArrayList<>();
        for (Long owner : java.util.Arrays.asList(teamId, ug)) {   // null üye (UG yok) atlanır
            if (owner == null) continue;
            List<EscalationContact> own = managers ? withoutInactiveQuiet(EscalationContactScope.forLevel(contactRepo, lvl, owner)) : List.of();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("team_id", owner);
            o.put("role", owner.equals(teamId) ? "SY" : "UG");
            o.put("team_name", teamRepo.findById(owner).map(com.sitemonitor.model.Team::getName).orElse(null));
            o.put("contacts_missing", managers && own.isEmpty());
            o.put("contacts_defined", !own.isEmpty()
                    || !contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(owner).isEmpty());
            owners.add(o);
        }
        Map<String, Object> sy = owners.isEmpty() ? Map.of() : owners.get(0);
        boolean teamContactsMissing = Boolean.TRUE.equals(sy.get("contacts_missing"));
        boolean teamContactsDefined = Boolean.TRUE.equals(sy.get("contacts_defined"));
        List<Map<String, Object>> contactRows = new ArrayList<>();
        List<Map<String, Object>> webhooks = new ArrayList<>();
        for (EscalationContact c : contacts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId()); m.put("name", c.getName()); m.put("email", c.getEmail());
            m.put("role", c.getRole()); m.put("min_level", c.getMinAlertLevel()); m.put("team_id", c.getTeamId());
            // Zamana bağlı eskalasyon adımı (2026-10-01): gecikmeli kişi ilk e-postaya GİRMEZ — satırı "N dk sonra (onaysızsa)"
            // diye işaretlenir, e-posta toplamına ve tekilleştirmeye katılmaz. Gecikme yoksa satır bugünküyle aynı.
            boolean delayed = EscalationDelay.isDelayed(c);
            boolean dup = !delayed && c.getEmail() != null && !seen.add(c.getEmail().trim().toLowerCase());
            m.put("email_duplicate", dup);   // takım adresiyle aynıysa tek mail gider
            if (delayed) m.put("delay_minutes", c.getDelayMinutes());
            contactRows.add(m);
            if (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank()) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("id", c.getId()); w.put("name", c.getName()); w.put("type", c.getWebhookType());
                w.put("target", WebhookService.maskUrl(c.getWebhookUrl()));
                if (delayed) w.put("delay_minutes", c.getDelayMinutes());
                webhooks.add(w);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team_id", teamId);
        out.put("level", lvl);
        out.put("standalone_monitor", standaloneMonitor);
        out.put("managers_included", managers);
        out.put("team_emails", emails);
        out.put("contacts", contactRows);
        out.put("team_contacts_missing", teamContactsMissing);
        out.put("team_contacts_defined", teamContactsDefined);
        out.put("ug_team_id", ug);
        out.put("owners", owners);
        out.put("webhooks", webhooks);
        out.put("email_total", emails.size() + contactRows.stream().filter(r -> !Boolean.TRUE.equals(r.get("email_duplicate"))
                && !r.containsKey("delay_minutes")
                && r.get("email") != null && !String.valueOf(r.get("email")).isBlank()).count());
        return out;
    }

    public List<ReNotifyRecipient> previewReNotify(Long alertId) {
        AlertEvent event = alertEventRepo.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + alertId));
        if (Boolean.TRUE.equals(event.getResolved())) {
            throw new IllegalStateException("Alert is already resolved");
        }
        ReNotifyTargets targets = resolveReNotifyTargets(event);
        requireOwned(event, targets);
        // C2 (son inceleme 2026-10-22): izlemenin "E-posta" kanalı alarm anında kapalıysa gönderim e-postayı atlar
        // (reNotifyAsync → sendCombinedAlert, alarm anı damgası) — önizleme de e-posta alıcısı listelemez (önizleme = gönderim).
        if (reNotifyMailDisabled(event)) return List.of();
        List<ReNotifyRecipient> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] team : collectTeamRecipients(targets.domainTeamId(), targets.ugTeamId(),
                targets.stampedGroupId())) {
            // role alani takim satirinda K9 kaynak etiketini tasir ("Grup: X" / "Takim maili").
            if (seen.add(team[0].toLowerCase())) out.add(new ReNotifyRecipient(team[0], team[1], team[2], "TEAM"));
        }
        for (EscalationContact c : targets.contacts()) {
            if (c.getEmail() != null && !c.getEmail().isBlank()
                    && seen.add(c.getEmail().trim().toLowerCase()))
                out.add(new ReNotifyRecipient(c.getEmail().trim(), c.getName(), c.getRole(), "CONTACT"));
        }
        // Nöbetçi (2026-10-11): gönderimde eklenecek kişi önizlemede de görünür (kaldırılabilir)
        for (var p : oncallFor(targets.domainTeamId(), targets.ugTeamId(), event.getAlertLevel())) {
            if (p.email() != null && !p.email().isBlank() && seen.add(p.email().trim().toLowerCase())) {
                out.add(new ReNotifyRecipient(p.email().trim(), p.displayName() != null ? p.displayName() : p.username(),
                        com.sitemonitor.util.Msg.t("Nöbetçi", "On call"), "ONCALL"));
            }
        }
        return out;
    }

    /**
     * Elle yeniden gönderimde eklenecek nöbetçiler (2026-10-12) — push önizlemesi için; gönderimle AYNI hedef çözümü
     * ({@link #resolveReNotifyTargets}). Alarm yoksa / çözülmüşse boş.
     */
    public List<com.sitemonitor.service.oncall.OnCallService.OnCallPerson> reNotifyOnCall(Long alertId) {
        try {
            AlertEvent event = alertEventRepo.findById(alertId).orElse(null);
            if (event == null || Boolean.TRUE.equals(event.getResolved())) return List.of();
            ReNotifyTargets targets = resolveReNotifyTargets(event);
            return oncallFor(targets.domainTeamId(), targets.ugTeamId(), event.getAlertLevel());
        } catch (Exception e) {
            log.debug("Nöbetçi önizlemesi atlandı: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Elle yeniden gönderimde e-posta kanalı KAPALI mı (C2, son inceleme 2026-10-22): izleme türlerinde gönderim bağlamı
     * alarm anı anlık görüntüsünü taşır ({@link #reNotifyAsync}) ve {@code sendCombinedAlert} onun {@code mail_disabled}
     * damgasıyla e-postayı atlar (push'un {@code push_disabled} kuralıyla aynı — izleme bayrağı alarm anında karar verir).
     * Önizleme ve kuyruk sayısı BUNU okur; sertifika alarmının bağlamı {@code latest_checks}'ten gelir, damga okunmaz.
     */
    public boolean reNotifyMailDisabled(Long alertId) {
        try {
            return alertId != null && alertEventRepo.findById(alertId).map(this::reNotifyMailDisabled).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean reNotifyMailDisabled(AlertEvent event) {
        if (event == null) return false;
        String type = event.getAlertType();
        if (!MONITORING_ALERT_TYPES.contains(type) && !isDomainMon(type)) return false;
        Map<String, Object> snapshot = deserializeContext(event.getContextJson());
        return snapshot != null && Boolean.TRUE.equals(snapshot.get("mail_disabled"));
    }

    /**
     * Manuel re-notify — {@code excludeEmails}: kullanıcının onay pop-up'ında listeden çıkardığı
     * adresler (case-insensitive). Hariç tutulan KONTAĞIN e-postası da webhook'u da gönderilmez
     * (tek filtrelenmiş contacts listesi ikisini de besler). Send, alıcıları CANLI yeniden çözer;
     * önizleme ile gönderim arasında EKLENEN kontak maili alır (kabul edilen yarış durumu).
     */
    public Map<String, Object> reNotify(Long alertId, Set<String> excludeEmails) {
        return reNotify(alertId, excludeEmails, Set.of());
    }

    /**
     * Onay pop-up'indan gelen haric tutmalar KANAL KANAL: {@code excludeEmails} mail alicilarini,
     * {@code excludeUsernames} webhook (push) alicilarini cikarir. Kullanici "bu kisiye mail
     * gitmesin ama push gitsin" diyebilmeli — iki kanal ayri kararlar.
     */
    public Map<String, Object> reNotify(Long alertId, Set<String> excludeEmails, Set<String> excludeUsernames) {
        AlertEvent event = alertEventRepo.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + alertId));
        if (Boolean.TRUE.equals(event.getResolved())) {
            throw new IllegalStateException("Alert is already resolved");
        }
        Set<String> excluded = excludeEmails == null ? Set.of()
                : excludeEmails.stream().filter(Objects::nonNull)
                    .map(e -> e.trim().toLowerCase()).collect(java.util.stream.Collectors.toSet());

        ReNotifyTargets targets = resolveReNotifyTargets(event);
        requireOwned(event, targets);
        Long domainTeamId = targets.domainTeamId(), ugTeamId = targets.ugTeamId();
        // Kontak filtresi serializeContacts + webhook'tan ÖNCE — hariç tutulan kontak hiçbir kanaldan bildirilmez.
        List<EscalationContact> contacts = targets.contacts().stream()
                .filter(c -> c.getEmail() == null || c.getEmail().isBlank()
                        || !excluded.contains(c.getEmail().trim().toLowerCase()))
                .toList();

        // Count actual recipients (team emails + contacts, deduped, exclusions applied) — same logic as sendCombinedAlert.
        // C2: izlemenin e-posta kanalı alarm anında kapalıysa gönderim e-postayı atlar → sayı 0 (önizleme de boş liste döner).
        boolean mailOff = reNotifyMailDisabled(event);
        List<String> teamEmails = mailOff ? List.of() : collectTeamEmails(domainTeamId, ugTeamId, targets.stampedGroupId());
        Set<String> seen = new HashSet<>();
        int recipientCount = 0;
        for (String e : teamEmails) {
            if (e != null && !e.isBlank() && !excluded.contains(e.trim().toLowerCase())
                    && seen.add(e.trim().toLowerCase())) recipientCount++;   // D8
        }
        for (EscalationContact c : mailOff ? List.<EscalationContact>of() : contacts) {
            if (c.getEmail() != null && !c.getEmail().isBlank()
                    && seen.add(c.getEmail().trim().toLowerCase())) recipientCount++;
        }
        for (var p : mailOff ? List.<com.sitemonitor.service.oncall.OnCallService.OnCallPerson>of()
                : oncallFor(domainTeamId, ugTeamId, event.getAlertLevel())) {
            if (p.email() != null && !p.email().isBlank() && !excluded.contains(p.email().trim().toLowerCase())
                    && seen.add(p.email().trim().toLowerCase())) recipientCount++;
        }
        if (recipientCount == 0 && !excluded.isEmpty()
                && !otherChannelLeft(event, targets, contacts, excludeUsernames)) {
            // Kullanıcı pop-up'ta TÜM alıcıları çıkardıysa DB'ye yazmadan reddet (→ 400).
            // Doğal sıfır-alıcı durumu (kontak/takım maili hiç yok) ESKİ davranışında kalır:
            // queued döner, sendCombinedAlert boş listeyle gönderimi zaten atlar.
            // UIM-8 / K13 (2026-10-22): yalnız e-postalar çıkarıldı ama push (ya da kontak webhook'u) alıcısı kaldıysa
            // gönderim KABUL edilir — "e-posta gitmesin, push gitsin" onay penceresinin sunduğu seçenek. Metin UI dilinde.
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                    "Tüm alıcılar hariç tutuldu — en az bir e-posta ya da push alıcısı seçin.",
                    "Every recipient was excluded — select at least one e-mail or push recipient."));
        }

        // Quick DB write — commits before async dispatch. R2b · F: hedefli (yalnız bu iki kolon) — okumadan sonra gelen
        // kapanış alarmı yeniden açmaz, onay geri alınmaz.
        event.setNotifiedContacts(serializeContacts(contacts));
        event.setLastReAlertAt(now());
        persist(event, AlertEventFieldWriter.NOTIFIED);

        // Fire-and-forget async (self-proxy needed for @Async to engage)
        self.reNotifyAsync(event.getId(), domainTeamId, ugTeamId, contacts,
                           event.getDomain(), event.getAlertLevel(), event.getAlertType(),
                           event.getDaysRemaining(), excluded,
                           excludeUsernames == null ? Set.of() : excludeUsernames);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alert_id",          event.getId());
        result.put("recipients_queued", recipientCount);
        result.put("contacts_queued",   contacts.size());
        result.put("status",            "queued");
        return result;
    }

    /**
     * Background SMTP + webhook dispatch. Runs on the notifyExecutor pool (2026-10-21, çok pod R1 / MP-07 — eskiden
     * certCheckExecutor'da kontrol işlerinin arkasındaydı ve kapanışta sessizce düşüyordu; bu havuz kapanışta kuyruğunu bitirir).
     * Does not hold DB connection from the originating HTTP request.
     */
    @Async("notifyExecutor")
    public void reNotifyAsync(Long alertEventId, Long domainTeamId, Long ugTeamId,
                              List<EscalationContact> contacts, String domain,
                              String alertLevel, String alertType, Integer daysRemainingFallback,
                              Set<String> excludeEmails, Set<String> excludeUsernames) {
        try {
            // İçerik bağlamı (mail detay tablosu): domain monitörü → EN GÜNCEL domain_checks (bitiş/registrar/EPP);
            // DNS_CHANGED → son changed dns_records kaydından eski/yeni değerler (günlük re-alert ile aynı kaynak;
            // yoksa mail ESKİ/YENİ kutuları boş gidiyordu — 2026-08-03 bug'ı); diğer serbest-form izleme → yok
            // (tipe özel şablon; ctx reconstruction follow-up); sertifika → latest_check.
            // NOT: monitörün dns_change_alert_enabled=false olması MANUEL Tekrar Bildir'i ENGELLEMEZ —
            // bastırma yalnız otomatik günlük re-alert içindir, manuel gönderim operatör iradesidir.
            // ALERTS-1 (2026-10-22): izleme türlerinde içerik ALARM ANI anlık görüntüsünden ve olayın kendi iletisinden
            // kurulur. Eskiden bağlam null'dı ve ileti sertifika şablonundan üretiliyordu: KEYWORD / PING_* "sertifika
            // bilgileri alınamadı", BLACKLIST / TRANSFER_LOCK "sertifikanın süresi 245 gün içinde doluyor" + konu
            // "245 GÜN KALDI" diyordu; diğer türler URL / port / anahtar kelime / izleme adını ve "e-posta kapalı"
            // (mail_disabled) damgasını kaybediyordu. Push aynı olayın iletisini okuduğu için e-posta ile çelişiyordu.
            AlertEvent ev = alertEventId == null ? null : alertEventRepo.findById(alertEventId).orElse(null);
            Map<String, Object> snapshot = ev == null ? null : deserializeContext(ev.getContextJson());
            boolean monitoring = MONITORING_ALERT_TYPES.contains(alertType);
            boolean freshCtx = false;   // bağlam taze kaynaktan mı (ileti ondan yeniden kurulur)
            Map<String, Object> certContext;
            if (isDomainMon(alertType)) {
                Map<String, Object> rebuilt = reconstructDomainContext(domain);
                freshCtx = rebuilt != null && reconstructedDomainCtxFresh(alertType);
                certContext = withSnapshot(rebuilt, snapshot);
            } else if (TYPE_DNS_CHANGED.equals(alertType)) {
                // D-b2: olayı AÇAN izlemenin kaydı (günlük yeniden uyarıyla aynı seçim).
                Long opener = ev == null ? null : contextMonitorId(ev);
                Map<String, Object> change = DnsCheckerService.changeCtxOf(
                        DnsCheckerService.lastChangedRecord(dnsRecordRepo, domain, opener));
                freshCtx = change != null && !change.isEmpty();
                certContext = freshCtx ? withSnapshot(change, snapshot) : copyOf(snapshot);
                // Taze kayıt bakım SONRASI bir değişiklik olabilir: "bakımda algılandı" notu yalnız olayın kendi iletisinde kalır.
                if (freshCtx) certContext.remove(CTX_DETECTED_IN_MAINTENANCE);
            } else if (monitoring) {
                certContext = copyOf(snapshot);
            } else {
                certContext = latestCheckRepo.findById(domain).map(this::latestToCertContext).orElse(null);
            }
            // "N gün kaldı" yalnız süre-bitişi türlerinde (sertifika + alan adı/kayıt bitişi); diğer izleme türlerinde gün yok.
            Integer effectiveDays = null;
            if (!monitoring || isDurationAlert(alertType)) {
                Integer freshDays = certContext != null ? toInt(certContext.get("days_remaining")) : null;
                effectiveDays = freshDays != null ? freshDays : daysRemainingFallback;
            }
            String storedMessage = ev == null ? null : ev.getMessage();
            String freshMessage;
            if (!monitoring) {
                freshMessage = buildMessage(domain, alertType, alertLevel, effectiveDays);
            } else if (freshCtx || storedMessage == null || storedMessage.isBlank()) {
                freshMessage = monitoringMessage(domain, alertType, alertLevel, certContext);   // taze / tek kaynak bağlamı
            } else {
                freshMessage = withLevelWord(storedMessage, alertLevel);   // alarmın kendi iletisi (Alarm Geçmişi + push ile aynı)
            }
            sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                    freshMessage, "[RE-ALERT] ", alertEventId, "MANUAL",
                    effectiveDays, certContext, excludeEmails == null ? Set.of() : excludeEmails,
                    excludeUsernames == null ? Set.of() : excludeUsernames);
        } catch (Exception e) {
            log.error("Async reNotify failed for alertEventId={}: {}", alertEventId, e.getMessage(), e);
        }
    }

    /**
     * E-posta alıcısı kalmayan elle gönderimde başka kanal kaldı mı (UIM-8 / K13): hariç tutulmamış bir kontak webhook'u ya da
     * onay penceresinin gösterdiği push önizlemesinde ({@link UserPushService#preview}) gidecek (PENDING) ve hariç tutulmamış
     * bir kullanıcı. Yalnız ret kararı verilirken çağrılır (normal yolda ek sorgu yok). Önizleme okunamazsa push yok sayılır.
     */
    private boolean otherChannelLeft(AlertEvent event, ReNotifyTargets targets, List<EscalationContact> contacts,
                                     Set<String> excludeUsernames) {
        for (EscalationContact c : contacts) {
            if (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank()) return true;
        }
        if (userPushService == null || event.getId() == null) return false;
        try {
            Set<String> skipUsers = excludeUsernames == null ? Set.of() : excludeUsernames;
            var preview = userPushService.preview(event.getId(), targets.domainTeamId(),
                    oncallFor(targets.domainTeamId(), targets.ugTeamId(), event.getAlertLevel()));
            if (preview == null || preview.recipients() == null) return false;
            return preview.recipients().stream().anyMatch(r -> r != null && "PENDING".equals(r.status())
                    && r.username() != null && !skipUsers.contains(r.username()));
        } catch (Exception e) {
            log.debug("Elle gönderim push önizlemesi okunamadı (olay {}): {}", event.getId(), e.toString());
            return false;
        }
    }

    /** Taze bağlam + alarm anı damgaları ({@code mail_disabled}, {@code team_id}, {@code monitor_id} …) — taze anahtar ezilmez. */
    private static Map<String, Object> withSnapshot(Map<String, Object> fresh, Map<String, Object> snapshot) {
        if (fresh == null) return copyOf(snapshot);
        Map<String, Object> out = new LinkedHashMap<>(fresh);
        if (snapshot != null) snapshot.forEach(out::putIfAbsent);
        return out;
    }

    private static Map<String, Object> copyOf(Map<String, Object> m) {
        return m == null ? null : new LinkedHashMap<>(m);
    }

    /**
     * Elle yeniden gönderimde iletisi EN GÜNCEL alan adı kaydından yeniden kurulan alan adı türleri: bitiş (kalan gün) ve
     * EPP durum kodları güncel kayıtta tamdır. Kara liste / transfer kilidi / veri yok / değişiklik iletisinin kanıtı
     * (liste adları, hata, değişiklik ayrıntısı) güncel kayıtta YOKTUR — olayın kendi iletisi kullanılır.
     */
    private static boolean reconstructedDomainCtxFresh(String alertType) {
        return TYPE_DOMAINMON_EXPIRY.equals(alertType) || TYPE_DOMAINMON_STATUS.equals(alertType);
    }

    public void catchUpMissedDailyAlerts() {
        int reAlertIv = reAlertIntervalHours();
        List<AlertEvent> openAlerts = alertEventRepo
                .findByResolvedFalseAndAcknowledgedFalseOrderByCreatedAtDesc();
        // N+1 önleme: re-alert adayı domain'lerin inventory + latest_check'ini TEK sorguda topla
        // (önceden döngü içinde her alarm için ayrı findByDomain + findById çalışıyordu).
        Set<String> candidateDomains = openAlerts.stream()
                .filter(e -> !MONITORING_ALERT_TYPES.contains(e.getAlertType()))
                .map(AlertEvent::getDomain).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, com.sitemonitor.model.CertificateInventory> invByDomain = candidateDomains.isEmpty()
                ? Map.of()
                : inventoryRepo.findByDomainIn(candidateDomains).stream().collect(
                    java.util.stream.Collectors.toMap(com.sitemonitor.model.CertificateInventory::getDomain, i -> i, (a, b) -> a));
        Map<String, com.sitemonitor.model.LatestCheck> latestByDomain = candidateDomains.isEmpty()
                ? Map.of()
                : latestCheckRepo.findByDomainIn(candidateDomains).stream().collect(
                    java.util.stream.Collectors.toMap(com.sitemonitor.model.LatestCheck::getDomain, l -> l, (a, b) -> a));
        int sent = 0;
        for (AlertEvent listed : openAlerts) {
            // İzleme tiplerinin kadansının sahibi ilgili sweep'lerdir; restart
            // sonrası ilk sweep doğrulamadan bayat re-alert atılmasın.
            if (MONITORING_ALERT_TYPES.contains(listed.getAlertType())) continue;
            // Ucuz ön süzgeç liste anlık görüntüsünden — vakti gelmeyen alarm için DB turu yok.
            if (!catchUpDue(listed, reAlertIv)) {
                log.debug("Catch-up: {} re-alert interval not elapsed, skipping", listed.getDomain());
                continue;
            }
            // BAYAT ANLIK GÖRÜNTÜ KORUMASI (2026-10-09): liste döngü başında BİR kez yüklendi ve her gönderimden sonra
            // aralık kadar bekleniyor — o arada kullanıcı alarmı çözmüş/onaylamış ya da başka bir yol (sweep, başka pod)
            // bildirmiş olabilir. Göndermeden hemen önce TAZE satır okunur ve karar ona göre yeniden verilir.
            AlertEvent event = listed.getId() == null ? null : alertEventRepo.findById(listed.getId()).orElse(null);
            if (event == null || Boolean.TRUE.equals(event.getResolved()) || Boolean.TRUE.equals(event.getAcknowledged())) {
                log.info("Catch-up: {} [{}] #{} artık açık/onaysız değil — bildirim atlandı",
                        listed.getDomain(), listed.getAlertType(), listed.getId());
                continue;
            }
            if (!catchUpDue(event, reAlertIv)) {
                log.debug("Catch-up: {} bu arada bildirildi, skipping", event.getDomain());
                continue;
            }
            // E9: ilk bildirimi yarıda kalmış alarm (processResults'taki kurtarmanın açılış eşleniği) —
            // aralık beklenmez, INITIAL olarak BİR kez gider (damga aşağıda atılır).
            boolean initialMissing = initialNotificationMissing(event, now());
            String lastAlertTime = event.getLastReAlertAt() != null
                    ? event.getLastReAlertAt() : event.getCreatedAt();
            var inventoryOpt  = Optional.ofNullable(invByDomain.get(event.getDomain()));
            // E10: damgalanmış takım önceliklidir (processResults, çözüm ve tekrar-bildir ile aynı kural).
            Long domainTeamId = event.getTeamId() != null ? event.getTeamId()
                    : inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
            Long ugTeamId     = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null);
            List<EscalationContact> contacts = recipientsNow(event.getAlertLevel(), domainTeamId, ugTeamId, event.getId());
            Map<String, Object> certContext = Optional.ofNullable(latestByDomain.get(event.getDomain()))
                    .map(this::latestToCertContext).orElse(null);
            Integer freshDays     = certContext != null ? toInt(certContext.get("days_remaining")) : null;
            Integer effectiveDays = freshDays != null ? freshDays : event.getDaysRemaining();
            String  freshMessage  = buildMessage(event.getDomain(), event.getAlertType(),
                                                 event.getAlertLevel(), effectiveDays);
            // ÇOK POD (2026-10-21, R1 / MP-03): damga GÖNDERİMDEN ÖNCE sahiplenilir. Telafi açılan her pod'da ve sertifika
            // süpürmesinden FARKLI bir kilitle koşar; süpürme bu alarmı tur başında yükleyip telafi damgalamadan önce
            // gönderebiliyordu (iki DAILY_REALERT). Kaybeden yol göndermez.
            String seenStamp = event.getLastReAlertAt();
            String claimAt = now();
            if (!claimReAlert(event.getId(), seenStamp, claimAt, initialMissing ? "INITIAL" : "DAILY_REALERT")) continue;
            try {
                if (initialMissing) {
                    sendCombinedAlert(domainTeamId, ugTeamId, contacts, event.getDomain(), event.getAlertLevel(), event.getAlertType(),
                            freshMessage, "", event.getId(), "INITIAL", effectiveDays, certContext);
                    event.setNotifiedContacts(serializeContacts(contacts));
                } else {
                    sendCombinedAlert(domainTeamId, ugTeamId, contacts, event.getDomain(), event.getAlertLevel(), event.getAlertType(),
                            "[RE-ALERT] " + freshMessage, "[RE-ALERT] ",
                            event.getId(), "DAILY_REALERT", effectiveDays, certContext);
                    event.setRealertCount((event.getRealertCount() == null ? 0 : event.getRealertCount()) + 1);
                }
            } catch (RuntimeException ex) {
                releaseReAlert(event.getId(), claimAt, seenStamp);
                throw ex;
            }
            event.setLastReAlertAt(claimAt);
            event.setDaysRemaining(effectiveDays);
            if (notAfterOf(certContext) != null) event.setNotAfter(notAfterOf(certContext));
            // Hedefli UPDATE (entity save DEĞİL): yalnız gönderimin değiştirdiği alanlar, yalnız hâlâ açık satırda —
            // gönderim sürerken gelen çözüm/onay geri alınmaz.
            alertEventRepo.stampNotificationSentIfOpen(event.getId(), event.getLastReAlertAt(), event.getRealertCount(),
                    event.getDaysRemaining(), event.getNotAfter(), event.getNotifiedContacts());
            sent++;
            log.info("Startup catch-up: {} sent for {} [{}] — last was: {}",
                    initialMissing ? "interrupted INITIAL" : "alert", event.getDomain(), event.getAlertLevel(),
                    lastAlertTime != null && lastAlertTime.length() >= 10 ? lastAlertTime.substring(0, 10) : "-");
            // Pace between domain batches to avoid flooding the SMTP gateway
            try { Thread.sleep(interDomainDelayMs()); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Startup catch-up complete — {} missed notification(s) sent", sent);
    }

    /**
     * Gönderim SONRASI kayıt (sertifika turu, E9 INITIAL + DAILY_REALERT): olay tur başında toplu yüklendi ve arada
     * SMTP gönderimi koştu. {@code AlertEvent}'te {@code @Version} yok — bayat kopyanın {@code save}'i tüm kolonları
     * yazar ve o arada kullanıcının yaptığı çözümü/onayı geri alırdı (çözülen alarm yeniden açık görünür). Taze satır
     * artık açık/onaysız değilse entity YAZILMAZ: bildirim gitti, ama kullanıcının kararı korunur. Satır okunamazsa
     * (yok/arıza) eski davranış: kaydedilir.
     */
    private void saveUnlessClosedMeanwhile(AlertEvent event, String... props) {
        if (event.getId() != null) {
            try {
                AlertEvent fresh = alertEventRepo.findById(event.getId()).orElse(null);
                if (fresh != null && (Boolean.TRUE.equals(fresh.getResolved()) || Boolean.TRUE.equals(fresh.getAcknowledged()))) {
                    log.info("Bildirim sonrası kayıt atlandı: {} [{}] #{} gönderim sürerken çözüldü/onaylandı",
                            event.getDomain(), event.getAlertType(), event.getId());
                    return;
                }
            } catch (Exception e) {
                log.debug("Taze alarm satırı okunamadı (#{}), kaydediliyor: {}", event.getId(), e.toString());
            }
        }
        // R2b · F: taze okuma ile kayıt arasındaki pencerede de kapanış geri alınmasın — yalnız yolun değiştirdiği kolonlar.
        persist(event, props);
    }

    /** Catch-up adayı mı: ilk bildirimi yarıda kalmış (E9) ya da tür re-alert aralığı dolmuş. */
    private boolean catchUpDue(AlertEvent e, int reAlertIv) {
        if (initialNotificationMissing(e, now())) return true;
        String last = e.getLastReAlertAt() != null ? e.getLastReAlertAt() : e.getCreatedAt();
        return reAlertDueFor(e.getAlertType(), last, now(), reAlertIv);
    }

    // DB save is sync (atomic + fast), mail notification is dispatched async on certCheckExecutor.
    // HTTP response returns in <1s even if SMTP times out.
    /** Geriye uyumlu: notsuz çözüm. KENDİLİĞİNDEN kurtarma bu yolu kullanmaz (doğrudan setResolved). */
    public AlertEvent resolve(Long eventId, String resolvedBy) {
        return resolve(eventId, resolvedBy, null);
    }

    /** {@code note} verilirse alarmın NASIL çözüldüğü de kaydedilir (manuel çözüm). */
    public AlertEvent resolve(Long eventId, String resolvedBy, String note) {
        AlertEvent event = alertEventRepo.findById(eventId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + eventId));
        // İdempotent: zaten çözülmüş bir alarmı yeniden çözme — çift "çözüldü" e-postası gönderme ve
        // resolvedAt/resolvedBy'ı ezme (çift tık / manuel-çözüm ile oto-recovery yarışı). reNotify'ın aynası.
        if (Boolean.TRUE.equals(event.getResolved())) {
            log.debug("Alarm zaten çözülmüş, tekrar çözülmüyor (idempotent): {} [{}]", event.getDomain(), event.getAlertType());
            return event;
        }
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "admin";
        String at = now();
        // Fırtına üyesi kararı KAPANIŞTAN ÖNCE (bir kez): çözüm bildirimi işareti yalnız bireysel çözüm postası gidecekse yazılır.
        boolean stormMember = event.getStormId() != null && stormService != null && stormService.isActive(event.getStormId());
        // D-A3-4 (2026-09-29): elle çözüm de ATOMİK kapıdan geçer — otomatik kapanışla aynı saniyede gelen "Çöz",
        // yukarıdaki oku-kontrol-yaz penceresinde resolvedBy/At'i eziyor ve İKİNCİ bir çözüm e-postası (MANUAL_RESOLVE)
        // üretiyordu. Koşullu UPDATE 0 dönerse yarışı otomatik yol kazanmış demektir: kapanmış olay olduğu gibi döner,
        // bildirim yok (yukarıdaki idempotent kuralla aynı sonuç).
        if (event.getId() != null && markResolvedIfOpenNotify(event, at, by, !stormMember) == 0) {
            log.info("Elle çözüm atlandı — alarm aynı anda otomatik kapandı (yarış): {} [{}] #{}",
                    event.getDomain(), event.getAlertType(), event.getId());
            return alertEventRepo.findById(eventId).orElse(event);
        }
        event.setResolved(true);
        event.setResolvedAt(at);
        event.setResolvedBy(by);
        if (note != null && !note.isBlank()) event.setResolvedNote(note.trim());
        AlertEvent saved = saveAfterCloseCas(event, at, by, note != null && !note.isBlank() ? note.trim() : null);
        keepResolutionMarker(event, saved);   // salt okunur işaret kayıt dönüşünde de bilinsin (F3)
        // D-A3-3 (2026-09-29): fırtına üyesi elle çözülünce de bireysel ÇÖZÜLDÜ e-postası GİTMEZ — otomatik yolla aynı
        // kural (resolveOpenAlertsForDomain): tek toplu kurtarma fırtına dağılınca gider; aksi hâlde takım aynı üyeyi
        // önce bireysel, sonra toplu çözümde iki kez alıyordu. Push simetrisi korunur (açılışta SENT olanlara).
        if (stormMember) {
            enqueueResolvePushQuietly(saved);
            log.info("✅ Alarm elle çözüldü (fırtına üyesi — bireysel çözüm maili yok, toplu çözüm fırtınadan): {} [{}]",
                    saved.getDomain(), saved.getAlertType());
            return saved;
        }
        self.sendResolutionNotificationAsync(saved, by, "MANUAL_RESOLVE");
        return saved;
    }

    private void resolveOpenAlertsForDomain(String domain, Collection<String> types) {
        // Bakım penceresinde recovery: alarm kapanır ama çözüm e-postası GÖNDERİLMEZ (tam sessizlik).
        //
        // ASİMETRİ DÜZELTMESİ: bakım bastırması yalnız BURADA ve processConfirmedOutage'da var;
        // SERTİFİKA sweep'i (processResults) isUnderMaintenance'a hiç bakmıyor. Yani bakım
        // penceresinde sertifika değiştirilirken CHAIN_BROKEN/UNTRUSTED_CA alarmı AÇILIYOR ve
        // mail + push gidiyor, iş bitip zincir düzelince pencere hâlâ açık olduğu için çözüm
        // sessizce kapanıyordu: takım açılışı alıyor, kapanışı ALMIYOR, alarm posta kutusunda
        // sonsuza dek açık görünüyordu. Kural: açılış bildirimi GİTTİYSE çözüm de gider.
        if (maintenanceService.isUnderMaintenance(domain)) {
            List<AlertEvent> open = alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain, types);
            List<String> notified = new ArrayList<>();
            for (AlertEvent e : open) {
                // 7/24 (NOC) satırı TAKIMIN bildirimi değildir: yalnız NOC'a gitmiş bir açılış için takıma "ÇÖZÜLDÜ"
                // gönderilirse takım, hiç almadığı bir alarmın kapanışını alırdı (NOC çözümü kendi yolundan gider).
                if (e.getId() != null && notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(e.getId()).stream()
                        .anyMatch(n -> !"NOC".equals(n.getRecipientRole())))
                    notified.add(e.getAlertType());
            }
            if (!notified.isEmpty()) {
                // Bildirimi gitmiş tipler NORMAL yoldan kapanır (çözüm maili gider); kalanlar sessiz.
                List<String> silent = new ArrayList<>(types);
                silent.removeAll(notified);
                if (!silent.isEmpty())
                    resolveOpenAlertsQuietlyRecovered(domain, silent, "Sistem (bakım penceresi — sessiz kapanış)");   // O-c1
                types = notified;
            } else {
                resolveOpenAlertsQuietlyRecovered(domain, types, "Sistem (bakım penceresi — sessiz kapanış)");   // O-c1
                return;
            }
        }
        List<AlertEvent> openAlerts = alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain, types);
        for (AlertEvent event : openAlerts) {
            // D9: sorgu ile save arasında biri ELLE resolve etmiş olabilir — save körlemesine
            // yazarsa resolvedBy ezilir ve İKİNCİ bir "çözüldü" maili gider. Atomik koşullu
            // UPDATE (resolved=false iken) yarışın kazananını tek yapar; kaybeden sessizce geçer.
            // id null = henüz kaydedilmemiş entity (üretimde olmaz; repo'dan yükleniyor). O durumda
            // atomik guard uygulanamaz — eski davranışa düş, alarm kapansın (guard bir YARIŞ
            // koruması; kimliksiz satırda yarış da yoktur).
            // Fırtına üyesi kararı KAPANIŞTAN ÖNCE ve TEK sefer (2026-10-21, MP-07): kalıcı çözüm işareti yalnız bireysel çözüm
            // postası gidecekse yazılır; karar kapanıştan sonra yeniden verilseydi arada kapanan fırtına işaretli ama
            // gönderilmeyen satır bırakır, süpürücü üyeye bireysel posta atardı.
            boolean stormMember = event.getStormId() != null && stormService.isActive(event.getStormId());
            // Kapanış anı TEK: koşullu kapanışın yazdığı değer bellekteki kopyayla aynı (R2b · F — sonradan bütün varlık
            // kaydedilmediği için ikinci bir now() satıra hiç yazılmaz).
            String closedAt = now();
            if (event.getId() != null
                    && markResolvedIfOpenNotify(event, closedAt, "system", !stormMember) == 0) {
                log.debug("Alarm zaten çözülmüş (yarış) — otomatik kapanış atlandı: {} [{}]",
                        domain, event.getAlertType());
                continue;
            }
            event.setResolved(true);
            event.setResolvedAt(closedAt);
            event.setResolvedBy("system");
            AlertEvent saved = saveAfterCloseCas(event, closedAt, "system", null);
            keepResolutionMarker(event, saved);   // salt okunur işaret kayıt dönüşünde de bilinsin (F3)
            // Storm üyesi + storm hâlâ aktif → bireysel çözüm e-postası GÖNDERME
            // (TEK toplu recovery, fırtına dağıldığında storm sweep'inden gider). Incident yine kapandı.
            if (stormMember) {
                // MAİL bastırılır (tek toplu recovery var) ama PUSH bastırılamaz: push'un toplu
                // karşılığı YOK. Eskiden bu dal sendResolutionNotificationAsync'i tümüyle
                // atladığı için telefondaki alarm sonsuza dek açık kalıyordu — hem de tam olarak
                // şu senaryoda: monitör tek başına düşüp OPEN push'u ALDIKTAN sonra fırtınaya
                // bağlanmışsa (linkPeers), kullanıcı "düştü" mesajını görmüş, "düzeldi"yi hiç
                // görmemiş oluyordu.
                //
                // enqueueResolve kendi simetri kuralını taşıyor: açılışı push'lanmamış bir olayın
                // çözümü zaten push'lanmaz. 2026-10-03'ten beri varsayılan kipte (storm.push-individual) üyenin
                // açılış push'u bireysel gittiği için çözümü de burada bireysel gider; ayar KAPALIYSA ve fırtına
                // en baştan bastırdıysa burada da sessiz (SKIPPED_NO_PRIOR).
                userPushService.enqueueResolve(saved, deserializeContext(saved.getContextJson()), resolveTeamFallback(saved));
                log.info("✅ Alarm çözüldü (storm üyesi — bireysel çözüm maili yok, push simetrik): {} [{}]",
                        domain, event.getAlertType());
                continue;
            }
            self.sendResolutionNotificationAsync(saved, "Sistem (otomatik)", "RESOLUTION");
            log.info("✅ Alarm çözüldü: {} [{}] — sorun giderildi, otomatik kapatıldı",
                    domain, event.getAlertType());
        }
    }

    /** İzleme recovery'si — SADECE verilen tipin alarmlarını kapatır (çözüm maili ile). */
    public void resolveMonitoringAlertsForDomain(String domain, String alertType) {
        resolveOpenAlertsForDomain(domain, Set.of(alertType));
    }

    /**
     * İzleme SİLİNDİĞİNDE açık alarmları SESSİZCE kapatır — resolve/çözüldü maili
     * GÖNDERİLMEZ (kapanma silme kaynaklı; kullanıcı bildirim istemiyor). Alarm
     * geçmişinde resolved (kapalı) görünür, resolvedBy = silme nedenidir.
     */
    public void resolveOpenAlertsSilently(String domain, Collection<String> types, String resolvedBy) {
        resolveOpenAlertsSilently(domain, types, resolvedBy, null);
    }

    /**
     * D-b1 (Y-1 kardeşi, 2026-09-29): İZLEMEYE ÖZGÜ sessiz kapanış — silme / duraklatma / anahtar (host, alan adı)
     * değişikliği YALNIZ bu izlemenin olayını kapatır. Anahtar (alan adı + tür) takımlar ve izlemeler arasında
     * paylaşılabilir; eskiden bir takımın izlemesini silmek ya da duraklatmak AYNI anahtardaki başka takımın açık olayını
     * da sessizce kapatıyordu (o takım "düzeldi" sanıyor, kesinti sürüyordu). {@code ownerCtx} = izlemenin sahiplik
     * bağlamı ({@code monitor_id}, bağımsızsa {@code team_id} / {@code standalone}); null → eski davranış (tümü).
     * Kural {@link #closableBy}.
     */
    public void resolveOpenAlertsSilently(String domain, Collection<String> types, String resolvedBy,
                                          Map<String, Object> ownerCtx) {
        closeQuietly(domain, types, resolvedBy, ownerCtx, true);
    }

    /**
     * O-c1 (2026-09-29): bakım penceresinde GERÇEKTEN düzelen alarmın sessiz kapanışı — e-posta yok (bakım sessizliği) ama
     * {@code resolvedSilently} İŞARETSİZ: alarm susturulmadı, kurtuldu. Fırtına üyesiyse toplu "fırtına sona erdi" çözümü
     * onu "kurtarıldı" sayar (O-b2'nin sessiz-üye süzgeci yalnız susturma nedenlerini — silindi / duraklatıldı / host
     * değişti / tür bildirimleri kapalı — hedefler). Eskiden bakım dalı da işaret yazıyor, bakımda düzelen fırtınanın çözüm
     * e-postası / push'u / webhook'u / 7-24 ÇÖZÜLDÜ postası hiç gitmiyordu.
     */
    void resolveOpenAlertsQuietlyRecovered(String domain, Collection<String> types, String resolvedBy) {
        closeQuietly(domain, types, resolvedBy, null, false);
    }

    private void closeQuietly(String domain, Collection<String> types, String resolvedBy,
                              Map<String, Object> ownerCtx, boolean markSilenced) {
        if (domain == null || types == null || types.isEmpty()) return;
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (izleme silindi)";
        List<AlertEvent> openAlerts = alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain, types);
        for (AlertEvent event : openAlerts) {
            if (ownerCtx != null && !closableBy(event, ownerCtx)) {
                log.info("Sessiz kapanış atlandı: {} [{}] #{} başka izlemenin/sahibin olayı ({}) — kendi kurtarma yolu kapatır",
                        domain, event.getAlertType(), event.getId(), by);
                continue;
            }
            // O-b2: susturma işareti — fırtına çözümü bunu "kurtarıldı" saymaz (O-c1: bakımda değil). Mail YOK —
            // sendResolutionNotification çağrılmaz. R2b · F: koşullu kapanış; başka yolda kapanmışsa push / 7-24 de yok.
            AlertEvent closed = closeSilentlyIfOpen(event, by, markSilenced);
            if (closed == null) continue;
            // Push AÇILIŞTA gittiyse çözümü de gitsin (simetri kuralı enqueueResolve içinde): bakım
            // penceresinde kurtarma / silme / duraklatma mail'siz kapanır ama telefon "DÜZELDİ"yi
            // hiç görmüyordu — açık alarm bildirimi ekranda sonsuza kadar kalıyordu.
            enqueueResolvePushQuietly(closed);
            // O-A3-3 (2026-09-29): bakım penceresinde GERÇEKTEN kurtulan alarm (işaretsiz dal) — 7/24 (NOC) ÇÖZÜLDÜ postası
            // takımın e-postasından BAĞIMSIZDIR: açılış NOC'a gittiyse çözüm de gider (karar serviste; açılış gitmediyse
            // hiçbir şey göndermez, çift gönderim yok). Eskiden yalnız sendResolutionNotification çağırıyordu; push-tek
            // takımda / e-postası kapalı izlemede NOC "kesinti sürüyor" sanıyordu. Susturma (silme/duraklatma) dalı değil.
            if (!markSilenced) notifyNocResolved(closed);
            log.info("✅ Alarm sessizce kapatıldı (izleme silindi, mail yok): {} [{}]", domain, event.getAlertType());
        }
    }

    /**
     * Sessiz kapanışın yazımı (2026-10-23, çok pod R2b · F). {@link SilentAlarmCloser} varken TEK koşullu UPDATE (yalnız
     * kapanış kolonları, yalnız hâlâ AÇIK satır): okunan varlık bütünüyle kaydedilmez — okumadan sonra yazılmış onay / yeniden
     * uyarı damgası / başka yolun çözümü ezilmez, başka yolda kapanmış alarm {@code null} döner (push / 7-24 yok, satıra
     * dokunulmadı). {@code markSilenced=false} {@code resolved_silently}'yi DEĞİŞTİRMEZ. Okunan varlık değiştirilmez
     * (çağıranın işleminde yönetiliyor olabilir); dönen değer kapanmış kopyadır. Yokken (elle kurulan testler) eski yol.
     */
    private AlertEvent closeSilentlyIfOpen(AlertEvent event, String by, boolean markSilenced) {
        SilentAlarmCloser closer = silentAlarmCloser;
        if (closer != null && event.getId() != null) return closer.closeCopyIfOpen(event, by, markSilenced);
        event.setResolved(true);
        event.setResolvedAt(now());
        event.setResolvedBy(by);
        if (markSilenced) event.setResolvedSilently(true);
        AlertEvent saved = alertEventRepo.save(event);
        return saved != null ? saved : event;
    }

    /**
     * Koşullu kapanışı (CAS — {@link #markResolvedIfOpenNotify}) KAZANMIŞ çözümün ardından (2026-10-23, çok pod R2b · F):
     * CAS {@code resolved / resolved_at / resolved_by}'ı zaten yazdı; bütün varlık kaydı okumadan sonra yazılmış onayı,
     * yeniden uyarı damgasını, fırtına bağını geri alıyordu. {@link SilentAlarmCloser} varken yalnız (varsa) not yazılır ve
     * bellekteki varlık döner; yokken (elle kurulan testler) ya da kimliksiz satırda eski yol (kayıt).
     */
    private AlertEvent saveAfterCloseCas(AlertEvent event, String at, String by, String note) {
        SilentAlarmCloser closer = silentAlarmCloser;
        if (closer == null || event.getId() == null) return alertEventRepo.save(event);
        if (note != null && !closer.writeResolvedNote(event.getId(), at, by, note)) {
            // İnceleme AL-3: yazılmayan not yanıtta / çözüm postasında "kaydedildi" gibi görünmesin.
            log.warn("Çözüm notu yazılamadı (satır bu kapanışla eşleşmedi — başka yol kapanışı yeniden yazdı): {} [{}] #{}",
                    event.getDomain(), event.getAlertType(), event.getId());
            event.setResolvedNote(null);
        }
        return event;
    }

    /**
     * D-b1: bu izleme ({@code ownerCtx}) olayı silme/duraklatma ile kapatabilir mi? Olayı AÇAN izleme biliniyorsa
     * yalnız o izleme kapatır (aynı takımın kardeş izlemesi de dâhil başkası kapatamaz — kardeş kendi kurtarmasıyla
     * kapatır). Açan bilinmiyorsa (eski olay, kimliksiz bağlam) sahiplik anahtarı aynıysa ({@link #sameOwner}).
     */
    /**
     * O-c2: bu DNS_CHANGED bağlamı açık olayı AÇANDAN BAŞKA bir izlemenin taze değişikliği mi? Bağlam izleme kimliği
     * taşımıyorsa (günlük yeniden uyarı — kayıt açanın değilse kimlik düşürülür) hayır. Açan biliniyorsa kimlik farkı;
     * bilinmiyorsa (eski olay) sahiplik anahtarı farkı.
     */
    static boolean isOtherMonitorsChange(AlertEvent e, Map<String, Object> ctx) {
        Object mid = ctx == null ? null : ctx.get("monitor_id");
        if (!(mid instanceof Number n)) return false;
        Long opener = contextMonitorId(e);
        if (opener != null) return opener != n.longValue();
        return !sameOwner(e, TYPE_DNS_CHANGED, ctx);
    }

    public static boolean closableBy(AlertEvent e, Map<String, Object> ownerCtx) {
        Object mid = ownerCtx == null ? null : ownerCtx.get("monitor_id");
        Long opener = contextMonitorId(e);
        if (opener != null && mid instanceof Number n) return opener == n.longValue();
        return sameOwner(e, e.getAlertType(), ownerCtx);
    }

    /** Sessiz kapanışlarda çözüm push'u — push katmanı hatası kapanışı ASLA geri almasın. */
    private void enqueueResolvePushQuietly(AlertEvent event) {
        try {
            if (userPushService != null)
                userPushService.enqueueResolve(event, deserializeContext(event.getContextJson()), resolveTeamFallback(event));
        } catch (Exception e) {
            log.debug("Sessiz kapanış çözüm push'u atlandı: {}", e.toString());
        }
    }

    /**
     * Çözüm push satırı için takım yedeği: olay damgası, yoksa envanterin SY takımı. Eski sertifika
     * olayları (takım damgası eklenmeden önce açılanlar) çözülürken damga null kalıyor, e-posta
     * envanterden takımı bulurken push bulamıyordu (SKIPPED_NO_RECIPIENTS). UG takımı bilinçli
     * dışarıda: push çözümleyicisi SY takım-kapsamlıdır.
     */
    private Long resolveTeamFallback(AlertEvent event) {
        if (event == null) return null;
        if (event.getTeamId() != null) return event.getTeamId();
        if (event.getDomain() == null) return null;
        try {
            return inventoryRepo.findByDomain(event.getDomain())
                    .map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Öksüz ping alarmı temizliği — aktif/pasif HİÇBİR ping monitör host'una karşılık gelmeyen açık
     * PING_DOWN alarmlarını SESSİZCE kapatır. Host rename (updatePing eski host'u değiştirir) veya eski
     * kayıt sonrası recovery bu alarmı asla resolve edemez: kapatma domain=host ile aranır, ama o host
     * artık hiçbir monitörde yok → alarm süresiz açık kalır. Mevcut host kümesinde olmayan domain'ler öksüz.
     * @param existingHosts aktif + pasif tüm ping monitörlerinin host'ları (silinen/yeniden adlandırılan hariç)
     * @return kapatılan öksüz domain sayısı
     */
    public int resolveOrphanedPingAlerts(Set<String> existingHosts) {
        if (existingHosts == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!TYPE_PING_DOWN.equals(e.getAlertType()) && !TYPE_PING_SLOW.equals(e.getAlertType())) continue;
            if (e.getDomain() == null || existingHosts.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_PING_DOWN, TYPE_PING_SLOW), "Sistem (öksüz alarm — eşleşen ping izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz ping alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz keyword alarmı temizliği — hiçbir keyword monitör URL'ine karşılık gelmeyen açık KEYWORD
     *  alarmlarını sessizce kapatır (url rename/silme sonrası recovery'nin asla kapatamadığı askıda alarm).
     *  {@code resolveOrphanedPingAlerts} ile birebir; kimlik = url. */
    public int resolveOrphanedKeywordAlerts(Set<String> existingUrls) {
        if (existingUrls == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!TYPE_KEYWORD.equals(e.getAlertType()) && !isKeywordAux(e.getAlertType())) continue;
            if (e.getDomain() == null || existingUrls.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_KEYWORD, TYPE_KEYWORD_SLOW, TYPE_KEYWORD_SSL, TYPE_KEYWORD_DOMAIN_EXPIRY),
                    "Sistem (öksüz alarm — eşleşen keyword izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz keyword alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz HTTP alarmı temizliği — hiçbir HTTP monitör URL'sine karşılık gelmeyen açık
     *  HTTP_DOWN/HTTP_SSL/DOMAIN_EXPIRY alarmlarını sessizce kapatır (url rename/silme sonrası). Kimlik = url. */
    public int resolveOrphanedHttpAlerts(Set<String> existingUrls) {
        if (existingUrls == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!TYPE_HTTP_DOWN.equals(e.getAlertType()) && !TYPE_HTTP_SSL.equals(e.getAlertType())
                    && !TYPE_HTTP_SLOW.equals(e.getAlertType())
                    && !TYPE_DOMAIN_EXPIRY.equals(e.getAlertType())) continue;
            if (e.getDomain() == null || existingUrls.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_HTTP_DOWN, TYPE_HTTP_SSL, TYPE_HTTP_SLOW, TYPE_DOMAIN_EXPIRY),
                    "Sistem (öksüz alarm — eşleşen HTTP izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz HTTP alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz sayfa-bütünlüğü alarmı temizliği — hiçbir page monitör URL'sine karşılık gelmeyen açık
     *  PAGE_DOWN/PAGE_INTEGRITY alarmlarını sessizce kapatır (url rename/silme sonrası). Kimlik = url. */
    public int resolveOrphanedPageAlerts(Set<String> existingUrls) {
        if (existingUrls == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!isPage(e.getAlertType())) continue;
            if (e.getDomain() == null || existingUrls.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_PAGE_DOWN, TYPE_PAGE_INTEGRITY),
                    "Sistem (öksüz alarm — eşleşen sayfa izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz sayfa alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz sayfa-hızı alarmı temizliği — hiçbir sayfa hızı monitörüne karşılık gelmeyen açık
     *  PAGESPEED_* alarmlarını sessizce kapatır (URL değişimi/silme sonrası). Kimlik = URL. */
    public int resolveOrphanedPageSpeedAlerts(Set<String> existingUrls) {
        if (existingUrls == null) return 0;
        Set<String> orphans = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!isPageSpeed(e.getAlertType())) continue;
            if (e.getDomain() == null || existingUrls.contains(e.getDomain())) continue;
            orphans.add(e.getDomain());
        }
        for (String d : orphans) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_PAGESPEED_DOWN, TYPE_PAGESPEED_SLOW),
                    "Sistem (öksüz alarm — eşleşen sayfa hızı izlemesi yok)");
        }
        if (!orphans.isEmpty()) log.info("🧹 Öksüz sayfa hızı alarmı temizlendi: {} URL {}", orphans.size(), orphans);
        return orphans.size();
    }

    /** Öksüz senaryo alarmı temizliği — hiçbir senaryo monitörüne karşılık gelmeyen açık SCRIPTED_FAIL
     *  alarmlarını sessizce kapatır (ad rename/silme sonrası). Kimlik = senaryo adı. */
    public int resolveOrphanedScriptedAlerts(Set<String> existingNames) {
        if (existingNames == null) return 0;
        Set<String> orphans = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!isScripted(e.getAlertType())) continue;
            if (e.getDomain() == null || existingNames.contains(e.getDomain())) continue;
            orphans.add(e.getDomain());
        }
        for (String d : orphans) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_SCRIPTED_FAIL, TYPE_SCRIPTED_SLOW),
                    "Sistem (öksüz alarm — eşleşen sentetik izleme yok)");
        }
        if (!orphans.isEmpty()) log.info("🧹 Öksüz senaryo alarmı temizlendi: {} senaryo {}", orphans.size(), orphans);
        return orphans.size();
    }

    /** Öksüz Domain-izleme alarmı temizliği — hiçbir domain monitörüne karşılık gelmeyen açık
     *  DOMAINMON_* alarmlarını sessizce kapatır (domain rename/silme sonrası). Kimlik = kayıtlı domain. */
    public int resolveOrphanedDomainMonAlerts(Set<String> existingDomains) {
        if (existingDomains == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!isDomainMon(e.getAlertType())) continue;
            if (e.getDomain() == null || existingDomains.contains(e.getDomain())) continue;
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_DOMAINMON_EXPIRY, TYPE_DOMAINMON_UNKNOWN, TYPE_DOMAINMON_STATUS,
                            TYPE_DOMAINMON_CHANGED, TYPE_DOMAINMON_TRANSFER_LOCK, TYPE_DOMAINMON_BLACKLIST),
                    "Sistem (öksüz alarm — eşleşen domain izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz Domain alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz port alarmı temizliği — hiçbir port monitör host'una karşılık gelmeyen açık PORT_DOWN
     *  alarmlarını sessizce kapatır. Host birden çok port monitörünce paylaşılıyorsa host kümesinde
     *  kalır → alarm kapatılmaz (yalnız o host'un HİÇ monitörü kalmayınca öksüz). Kimlik = host. */
    public int resolveOrphanedPortAlerts(Set<String> existingHosts) {
        if (existingHosts == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!TYPE_PORT_DOWN.equals(e.getAlertType()) && !TYPE_PORT_SLOW.equals(e.getAlertType())) continue;
            if (e.getDomain() == null || existingHosts.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_PORT_DOWN, TYPE_PORT_SLOW), "Sistem (öksüz alarm — eşleşen port izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz port alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /**
     * MonitoringOutageService teyit zinciri tamamlandığında (ya da kesinti
     * sürerken her sweep'te / DNS_CHANGED'de anında) çağırır. processResults'un
     * INITIAL / DAILY_REALERT dallarının izleme aynası — seviye sabit
     * (CRITICAL ya da DNS_CHANGED için HIGH) olduğundan escalation dalı yoktur.
     */
    public void processConfirmedOutage(String domain, String alertType, String alertLevel,
                                       Map<String, Object> outageContext) {
        processConfirmedOutage(domain, alertType, alertLevel, outageContext, null);
    }

    /**
     * @param presetMessage doluysa ileti bağlamdan YENİDEN KURULMAZ (ALERTS-2): bakımda açılmış değişiklik olayının ertelenmiş
     *                      INITIAL'ı açılışta saklanan zengin iletiyi (eski → yeni değerler, "bakım penceresinde algılandı")
     *                      kullanır; eski anlık görüntüde bu anahtarlar yoktu ve ileti genel metne dönüp olayın iletisini eziyordu.
     */
    private void processConfirmedOutage(String domain, String alertType, String alertLevel,
                                        Map<String, Object> outageContext, String presetMessage) {
        // Bakım penceresi: atanan monitör bakımdaysa alarm AÇILMAZ + hiçbir kanaldan bildirim gitmez
        // (açılış + günlük re-alert + DNS_CHANGED hepsi bu tek noktadan geçer; save + sendCombinedAlert'ten ÖNCE).
        if (maintenanceService.isUnderMaintenance(domain)) {
            // O-A3-5 (2026-09-29, ürün kararı): KENAR TETİKLİ değişiklik alarmları (DNS_CHANGED / DOMAINMON_CHANGED) bakımda
            // YUTULMAZ — değişiklik yalnız görüldüğü turda vardır (tur tabanı ilerler, bir daha algılanmaz) ve alan adı /
            // NS kaydının değişmesi bakım kapsamı değildir (olası ele geçirme). Olay BİLDİRİMSİZ açılır (lastReAlertAt null
            // = "yarım ilk bildirim"), notu "bakım penceresinde algılandı"; pencere bitince ilk turda
            // (MonitoringOutageService.notifyChangeAlertsDeferredByMaintenance / DNS günlük yeniden uyarı döngüsü)
            // INITIAL TEK sefer gider. Açık olay zaten varsa dokunulmaz (bakımda yeniden uyarı yok — mevcut kural).
            if (MonitoringOutageService.MANUAL_CLOSE_TYPES.contains(alertType)) {
                openDeferredChangeAlert(domain, alertType, alertLevel, outageContext);
                return;
            }
            log.debug("🔧 Bakım penceresi aktif — alarm/bildirim bastırıldı: {} [{}]", domain, alertType);
            return;
        }
        // Sweep ctx'i açık bir alert_level taşıyorsa onu kullan (domain izlemesi değişken şiddet — WARNING/CRITICAL).
        if (outageContext != null && outageContext.get("alert_level") instanceof String lvl && !lvl.isBlank()) alertLevel = lvl;
        // Serbest-form izleme (keyword/ping) takımı outageContext.team_id'den gelir
        // (envantere bağlı değil); uptime/port/dns ise domain→envanter eşlemesinden.
        Long domainTeamId, ugTeamId;
        Object ctxTeam = outageContext != null ? outageContext.get("team_id") : null;
        if (ctxTeam instanceof Number teamNum) {
            domainTeamId = teamNum.longValue();
            ugTeamId = null;
        } else if (isStandalone(alertType, outageContext)) {
            // Bağımsız izleme (tür listesi ya da bağlamdaki "standalone" işareti) takımını ENVANTERDEN ALMAZ
            // (2026-09-28): host başka takımın envanterindeyse alarm o takıma (SY/UG + kişileri) gidiyordu. Takım
            // yalnız izlemenin kendi damgasından; o da yoksa kayıt sahipsizdir (sendCombinedAlert kapısı).
            domainTeamId = null;
            ugTeamId = null;
        } else {
            var inventoryOpt = inventoryRepo.findByDomain(domain);
            domainTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
            ugTeamId     = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null);
        }
        // Standalone izleme (keyword/ping/http/domain) takım-özeldir; AMA KRİTİK domain alarmında müdür de eklenir.
        // Bağlamda team_id damgası = bağımsız izleme (PORT/DNS dâhil — O-1).
        boolean teamOnly = teamOnly(isStandalone(alertType, outageContext), alertLevel);

        String message = presetMessage != null && !presetMessage.isBlank()
                ? withLevelWord(presetMessage, alertLevel)
                : monitoringMessage(domain, alertType, alertLevel, outageContext);
        Optional<AlertEvent> existing = alertEventRepo.findOpenAlert(domain, alertType);

        if (existing.isEmpty()) {
            AlertEvent event = newEvent(domain, alertLevel, alertType, message, null);
            event.setTeamId(domainTeamId);   // çözüm bildiriminde takımı buradan bul (özellikle keyword/ping)
            // K5: monitorun bildirim grubu -- sweep ctx'e koyar, teamId ile ayni yoldan gelir.
            // Envanter-turevli alarmda (ctx'te yok) envanterin grubuna duselim ki iki kaynak da calissin.
            event.setNotificationGroupId(resolveStampFromContext(outageContext, domain));
            event.setContextJson(snapshotContext(outageContext));   // çözüldü mailinde keyword/koşul detayı için
            event = alertEventRepo.save(event);

            // Alarm fırtınası hunisi (bakım + ≥%50 geçitlerinin ALTINDA): eşik+pencere aşıldıysa bireysel
            // bildirim bastırılır → TEK toplu alarm storm üzerinden gider. Storm KAPALI / eşik altı ise
            // SEND_INDIVIDUAL (sıfır gecikme, bugünkü davranış). stormId/groupName evaluate'te event'e
            // damgalanır; aşağıdaki save onu kalıcılaştırır.
            StormService.StormAction stormAction = stormService.evaluate(event, outageContext);
            if (stormAction == StormService.StormAction.SUPPRESSED) {
                event.setLastReAlertAt(now());
                // R2b · F: fırtına değerlendirmesinin damgaladığı bağ + grup ve yeniden uyarı damgası — başka kolon değil; yalnız
                // alarm hâlâ AÇIKSA (inceleme AL-4): arada elle çözülen alarm üye YAPILMAZ — çözüm kararı onu "fırtına üyesi değil"
                // görüp bireysel çözüm postasını gönderdi; bağ yazılsaydı fırtına kurtarması aynı alarmı ikinci kez "düzeldi" sayardı.
                if (!persistIfOpen(event, "lastReAlertAt", "stormId", "groupName")) leftStormClosedMeanwhile(event);
                // İZ (2026-09-30, prod olayı): bu dal eskiden hiçbir kanal çalıştırmıyor ve hiçbir kayıt da bırakmıyordu —
                // alarm penceresi "0 bildirim", push "önce bildirim gitmemişti" diyor, takım neden haber almadığını
                // göremiyordu. Bildirim günlüğü satırı: "bireysel e-posta (ayar kapalıysa push da) fırtına #N'e devredildi".
                // PUSH FIRTINAYA DEVREDİLMEZ (2026-10-03, kullanıcı kararı, varsayılan): e-posta fırtınada kalır, bu alarmın
                // push'u ŞİMDİ bireysel gider — alarmlar sweep'te açıldıkça kuyruğa girer, yani teslim sırası açılış sırasıdır.
                boolean pushIndividual = stormPushIndividual();
                recordStormSuppression(event, domainTeamId, ugTeamId, pushIndividual);
                if (pushIndividual) pushStormMember(event, "INITIAL", domainTeamId, outageContext);
                Long joinedStorm = event.getStormId();
                log.info("🌩 İzleme alarmı storm'a eklendi (bireysel e-posta yok, push {}): {} [{}] → storm #{}",
                        pushIndividual ? "bireysel" : "fırtınada", domain, alertType, joinedStorm);
                // KAPANIŞ YARIŞI (2026-10-09): storm_id yukarıdaki save ile yazıldı; fırtına bu arada kapandıysa (yaşam
                // döngüsü üye listesini bu kayıttan önce okudu) bağ HEDEFLİ koşullu UPDATE ile koparılır, lastReAlertAt
                // sıfırlanır → sonraki tur bireysel İLK bildirimi gönderir (kapanışta duyurulmamış üyenin yolu; fırtına
                // devri satırı bu yolda da durur). Bundan sonra olay varlığı KAYDEDİLMEZ — bayat varlık bağı geri yazardı.
                if (stormService.closedAfterAttach(event)) {
                    log.warn("🌩 Storm #{} bağlanma sırasında kapandı — {} [{}] fırtınadan ayrıldı, ilk bildirim sonraki turda bireysel",
                            joinedStorm, domain, alertType);
                }
            } else {
                // Gecikmeli eskalasyon kişisi ilk bildirime girmez (yeni olay → sorgu yok; adım EscalationStepService'ten).
                List<EscalationContact> contacts = teamOnly ? List.of()
                        : EscalationDelay.immediateOnly(getContactsForLevel(alertLevel, domainTeamId, ugTeamId));
                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                        message, "", event.getId(), "INITIAL", null, outageContext);

                event.setNotifiedContacts(serializeContacts(contacts));
                event.setLastReAlertAt(now());
                // R2b · F: grup adı fırtına değerlendirmesinden (grup kipi); fırtına bağına DOKUNULMAZ (bu yol bağlamadı — eşzamanlı
                // terfi bu olayı eş olarak bağlamış olabilir).
                persist(event, "notifiedContacts", "lastReAlertAt", "groupName");
                log.warn("🔴 İzleme alarmı oluşturuldu: {} [{}] {} — takım bilgilendirildi",
                        domain, alertType,
                        outageContext != null ? outageContext.getOrDefault("detail", "") : "");
            }

        } else {
            AlertEvent event = existing.get();
            // Y-1 (2026-09-29): anahtar (alan adı + tür) takımlar arasında paylaşılabilir — bağımsız + envanter türevi
            // aynı host, iki takımın aynı URL'si, iki takımda aynı adlı senaryo. Açık olay BAŞKA bir sahibin (takım /
            // envanter) izlemesine aitse bu bağlamın arızası o olayın yeniden uyarısı / terfisi / telafisi OLAMAZ:
            // bildirim yanlış takıma gider, ileti yabancı izlemenin bağlamından kurulurdu. Bu izlemenin alarmı, açık
            // olay kapandıktan sonraki ilk DOWN turunda kendi takımıyla açılır. Bağlam izleme kimliği taşımıyorsa
            // (DNS_CHANGED yeniden uyarısı) sahiplik bilinmez → eski davranış.
            // O-c2 (2026-09-29): DNS_CHANGED kenar tetiklidir (değişiklik tek turda görülür, tur tabanı ilerler — bir daha
            // algılanmaz) ve elle kapanır (açık olay günlerce durur). Açık olayı BAŞKA bir izleme açtıysa bu izlemenin taze
            // değişikliği aşağıdaki sahiplik kapısında sessizce düşüyor ve KALICI yutuluyordu (aynı takımda bile). Değişiklik
            // olayları her değişikliği bildirir: olaya dokunmadan, bağlamın SAHİBİNE (bağımsızsa kendi takımı, envanter
            // türeviyse envanterin takımı — yukarıda çözüldü) olaysız tekil bildirim gider; başka takıma sızma yok.
            if (TYPE_DNS_CHANGED.equals(alertType) && isOtherMonitorsChange(event, outageContext)) {
                // Olaysız tekil bildirim: adımı olmayan gecikmeli kişi buraya da girmez.
                List<EscalationContact> contacts = teamOnly ? List.of()
                        : EscalationDelay.immediateOnly(getContactsForLevel(alertLevel, domainTeamId, ugTeamId));
                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                        message, "", null, "INITIAL", null, outageContext);
                log.warn("DNS değişikliği bildirildi (açık olay #{} başka izlemenin — olaysız tekil bildirim, bağlamın sahibine "
                        + "{}): {} izleme {}", event.getId(), ownerKeyOf(alertType, outageContext), domain,
                        outageContext.get("monitor_id"));
                return;
            }
            if (!sameOwner(event, alertType, outageContext)) {
                log.info("Açık olay başka sahibin izlemesine ait — bu bağlam yeniden uyarı üretmez: {} [{}] olay={} "
                        + "(olay sahibi {}, bağlam sahibi {})", domain, alertType, event.getId(),
                        ownerKeyOf(event), ownerKeyOf(alertType, outageContext));
                return;
            }
            // O5: alarm AÇIKKEN monitör başka takıma atanırsa çözüm bildirimi event.teamId'den
            // gider (damga) — re-alert canlı ctx'ten giderse iki yol FARKLI takıma düşer.
            // Üç yol da (ilk/re-alert/çözüm) aynı damgayı kullansın; ctx yalnız damga boşken.
            if (event.getTeamId() != null) {
                domainTeamId = event.getTeamId();
            }
            // D-A3-1 (2026-09-29): gönderim seviyesi olayın seviyesinin ALTINA inmez (sertifika yolundaki E11 kuralı).
            // İzlemenin seviyesi alarm açıkken DÜŞÜRÜLÜRSE yeniden uyarı düşük seviyeyle ve kişisiz gidiyor, olay ve push
            // ise yüksek seviyede kalıyordu — açılışı alan eskalasyon kişisi yeniden uyarıyı almıyordu. Terfi (yukarı)
            // aşağıdaki dalda ayrıca ele alınır.
            if (levelValue(alertLevel) < levelValue(event.getAlertLevel())) {
                alertLevel = event.getAlertLevel();
                message = monitoringMessage(domain, alertType, alertLevel, outageContext);
                teamOnly = teamOnly(isStandalone(alertType, outageContext), alertLevel);
            }
            // Bağımsızlık OLAYDAN da okunur (2026-09-28): DNS_CHANGED günlük yeniden uyarısı bağlamı
            // reconstructChangeCtx'ten kurar ve team_id taşımaz → bağımsız bir DNS izlemesinin alarmı envanter yoluna
            // düşüp host'un envanterdeki takımının UG'sine ve kişilerine gidiyordu. Açılışta bağımsız olan olay
            // yeniden uyarı / eskalasyon / telafide de bağımsızdır: UG yok, takım-özel seviye kuralı.
            if (isStandaloneEvent(event)) {
                ugTeamId = null;
                teamOnly = teamOnly(true, alertLevel);
            }
            // NULL-güvenli unbox (O6): acknowledged nullable Boolean — NULL satırda unbox NPE'si
            // sweep'in KALAN domain'lerinin alarm işlemesini de iptal ediyordu.
            boolean acked = Boolean.TRUE.equals(event.getAcknowledged());
            // Y5: ctx seviyesi damgadan YÜKSEKSE terfi KALICI olsun — yoksa kritik re-alert alan
            // müdür, çözüm bildirimini alamaz (includeManagerContacts event seviyesine bakar) ve
            // çözüm maili seviyeyi yanlış gösterir. processResults'taki escalation davranışının
            // izleme-yolu eşleniği.
            //
            // Terfi ACK KAPISINDAN ÖNCE değerlendirilir. Eskiden bu blok "ack'li değilse" dalının
            // içindeydi: 30 günde UYARI açılıp ack'lenen DOMAINMON_EXPIRY ≤7 günde KRİTİK'e çıkınca
            // dal tümüyle atlanıyor, olay UYARI'da kalıyor, müdür (KRİTİK kapısı) hiç aranmıyor ve
            // acknowledgedAt hiçbir yerde okunmadığından ack alarmı SONSUZA kadar susturuyordu.
            // Sertifika yolu (processResults) ile aynı kural: terfi ack'i düşürür ve ESCALATION gönderir.
            if (levelValue(alertLevel) > levelValue(event.getAlertLevel())) {
                boolean stormMember = event.getStormId() != null && stormService.isActive(event.getStormId());
                // ÇOK POD (MP-03 / inceleme F2): terfi bildirimi damgası seviye YAZILMADAN ÖNCE sahiplenilir (sertifika yolu
                // gibi). Eskiden seviye önce kaydediliyordu: sahiplenme veritabanı hatasıyla düşünce sonraki tur terfiyi
                // "yapılmış" görüyor, ESCALATION hiç gitmiyor ve onay da sessizce düşmüş oluyordu. Sahiplenme kaybedilirse
                // (başka pod / yol) ya da yazılamazsa seviye ve onay DEĞİŞMEZ → sonraki tur yeniden dener.
                String seenStamp = event.getLastReAlertAt();
                String claimAt = now();
                EscalationUndo undo = EscalationUndo.of(event);
                if (!stormMember && !claimEscalation(event.getId(), seenStamp, event.getAlertLevel(), alertLevel, claimAt)) return;
                // Sahiplenme YENİ seviyeyi yazdı: kayıt, alıcı sorgusu ve gönderimden HERHANGİ biri düşerse terfi geri alınır
                // (inceleme N1 — eskiden yalnız gönderim korunuyordu; havuz tükenmesinde kayıt düşünce seviye yükselmiş kalıyor,
                // ESCALATION hiç gitmiyor, onaylı alarm sonsuza dek susuyordu).
                List<EscalationContact> contacts = List.of();
                try {
                    event.setAlertLevel(alertLevel);
                    event.setMessage(message);
                    if (acked) {
                        event.setAcknowledged(false);
                        event.setAcknowledgedAt(null);
                        event.setAcknowledgedBy(null);
                    }
                    // Varlık kaydı sahiplenilen damgayı GERİ yazmasın (kayıt tüm kolonları yazar)
                    if (!stormMember) event.setLastReAlertAt(claimAt);
                    // KALICILASTIR: asagidaki "re-alert vakti gelmedi" dali save cagirmiyor ve sinifta
                    // @Transactional da yok — dirty-checking kurtarmiyordu. Terfi bellekte kalip
                    // kayboluyor, araya giren bir cozum bildirimini ESKI (dusuk) seviyeyle gonderiyor
                    // ve mudur atlanabiliyordu (Y5'in alt-dali).
                    // R2b · F: yalnız terfinin değiştirdiği kolonlar — onay yalnız düşürüldüyse, damga yalnız sahiplenildiyse.
                    List<String> cols = new ArrayList<>(List.of("alertLevel", "message"));
                    if (acked) cols.addAll(List.of(AlertEventFieldWriter.ACK));
                    if (!stormMember) cols.add("lastReAlertAt");
                    persist(event, cols.toArray(new String[0]));
                    if (!stormMember) {
                        contacts = teamOnly ? List.of() : recipientsNow(alertLevel, domainTeamId, ugTeamId, event.getId());
                        sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                                message, "", event.getId(), "ESCALATION", null, outageContext);
                    }
                } catch (RuntimeException ex) {
                    if (!stormMember) revertEscalation(event, undo, claimAt, ex);   // sonraki tur terfiyi yeniden görür ve gönderir
                    throw ex;
                }
                if (!stormMember) {
                    event.setNotifiedContacts(serializeContacts(contacts));
                    event.setLastReAlertAt(claimAt);
                    persist(event, AlertEventFieldWriter.NOTIFIED);
                    log.warn("⬆ İzleme alarmı {} seviyesine yükseltildi: {} [{}] — bildirim gönderildi{}",
                            alertLevel, domain, alertType, acked ? " (ack düşürüldü)" : "");
                } else if (stormPushIndividual()) {
                    // Fırtına üyesinin seviye artışı (2026-10-03): e-posta fırtınada kalır (bugünkü gibi), push bireysel
                    // ESCALATION olarak gider — seviye başına bir kez (UserPushService tekilleştirmesi).
                    pushStormMember(event, "ESCALATION", domainTeamId, outageContext);
                    log.info("⬆ Fırtına üyesi {} seviyesine yükseldi: {} [{}] — e-posta fırtınada, push bireysel",
                            alertLevel, domain, alertType);
                }
                return;
            }
            if (acked) return;   // ack'li ve terfi yok → sessiz (mevcut davranış)
            // Storm üyesi + storm hâlâ aktif → bireysel günlük re-alert YOK (toplu re-alert storm sweep'inden gider).
            if (event.getStormId() != null && stormService.isActive(event.getStormId())) {
                log.debug("İzleme alarmı storm üyesi — bireysel re-alert atlandı: {} [{}]", domain, alertType);
                return;
            }
            // İLK bildirim hiç tamamlanmadıysa (lastReAlertAt null) onu ŞİMDİ gönder.
            //
            // lastReAlertAt iki başarı yolunun İKİSİNDE de damgalanıyor (storm'a eklendi ve
            // bireysel gönderim), yani null OLMASI "ilk bildirim yarıda kaldı" demektir: alarm
            // satırı kaydedildikten SONRA, bildirim gitmeden önce süreç ölmüş (deploy, restart,
            // OOM). Eskiden bu durumda createdAt'e düşülüyordu ve kod sanki bildirim oluşturma
            // aninda gitmis gibi davraniyordu — alarm bir re-alert araligi (varsayilan 24 saat)
            // boyunca SESSIZ kaliyor, hicbir yerde de isaret birakmiyordu.
            //
            // 2026-08-24'te goruldu: PAGESPEED_SLOW alarmi olustu, tam o anda backend yeniden
            // baslatildi; alarm ekranda "acik" gorunuyor ama bildirim gecmisi bos ve sistem
            // "bugun zaten gonderildi" diyordu.
            //
            // PAY (2026-10-21, çok pod R1 / MP-19): sertifika yolunun INITIAL_SEND_GRACE kuralı burada da — olay
            // 5 dk'dan gençse ilk bildirim hâlâ SÜRÜYOR olabilir (yavaş SMTP + kontak webhook'ları izleme kilidinin 120 sn'sini
            // aşınca kilidi devralan yeni lider damgasız olayı görüp İKİNCİ bir INITIAL gönderiyordu). Bu tur bekler; pay
            // dolunca (hâlâ damgasızsa) telafi gider. Damga yokken aşağıdaki hatırlatma dalına DÜŞÜLMEZ.
            if (event.getLastReAlertAt() == null) {
                if (!initialNotificationMissing(event, now())) {
                    log.debug("İlk bildirim henüz sürüyor olabilir (olay {} sn'den genç) — telafi bekliyor: {} [{}]",
                            INITIAL_SEND_GRACE.toSeconds(), domain, alertType);
                    return;
                }
                String claimAt = now();
                if (!claimReAlert(event.getId(), null, claimAt, "INITIAL")) return;
                List<EscalationContact> contacts = teamOnly ? List.of()
                        : recipientsNow(alertLevel, domainTeamId, ugTeamId, event.getId());
                try {
                    sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                            message, "", event.getId(), "INITIAL", null, outageContext);
                } catch (RuntimeException ex) {
                    releaseReAlert(event.getId(), claimAt, null);
                    throw ex;
                }
                event.setNotifiedContacts(serializeContacts(contacts));
                event.setLastReAlertAt(claimAt);
                event.setMessage(message);
                persist(event, "notifiedContacts", "lastReAlertAt", "message");
                log.warn("🔴 Yarıda kalmış ilk bildirim tamamlandı: {} [{}] — alarm {} tarihinde açılmıştı",
                        domain, alertType, event.getCreatedAt());
                return;
            }
            String lastAlertTime = event.getLastReAlertAt();
            if (reAlertDueFor(alertType, lastAlertTime, now(), reAlertIntervalHours())) {
                String claimAt = now();
                if (!claimReAlert(event.getId(), lastAlertTime, claimAt, "DAILY_REALERT")) return;   // MP-03
                List<EscalationContact> contacts = teamOnly ? List.of()
                        : recipientsNow(alertLevel, domainTeamId, ugTeamId, event.getId());
                try {
                    sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                            "[RE-ALERT] " + message, "[RE-ALERT] ",
                            event.getId(), "DAILY_REALERT", null, outageContext);
                } catch (RuntimeException ex) {
                    releaseReAlert(event.getId(), claimAt, lastAlertTime);
                    throw ex;
                }

                event.setLastReAlertAt(claimAt);
                event.setRealertCount((event.getRealertCount() == null ? 0 : event.getRealertCount()) + 1);
                event.setMessage(message);
                persist(event, "lastReAlertAt", "realertCount", "message");
                log.info("İzleme re-alert gönderildi: {} [{}] — önceki gün: {}",
                        domain, alertType, lastAlertTime.substring(0, 10));
            } else {
                log.debug("İzleme alarmı bugün zaten gönderildi, atlanıyor: {} [{}]", domain, alertType);
            }
        }
        // acknowledged açık alarm → sessiz (expiry semantiğiyle aynı)
    }

    /** Bağlam anahtarı: olay bakım penceresinde algılandı, bildirimi pencere bitince gönderildi/gönderilecek (O-A3-5). */
    public static final String CTX_DETECTED_IN_MAINTENANCE = "detected_in_maintenance";

    /**
     * O-A3-5: bakım penceresinde görülen değişikliği BİLDİRİMSİZ olay olarak kaydeder — yalnız açık olay YOKSA. Bağlam
     * anlık görüntüsüne {@link #CTX_DETECTED_IN_MAINTENANCE} damgası girer (ileti notu + ertelenmiş INITIAL'ın bağlamı).
     */
    private void openDeferredChangeAlert(String domain, String alertType, String alertLevel, Map<String, Object> outageContext) {
        if (alertEventRepo.findOpenAlert(domain, alertType).isPresent()) {
            log.debug("🔧 Bakım penceresi aktif — değişiklik alarmı zaten açık, bildirim bastırıldı: {} [{}]", domain, alertType);
            return;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (outageContext != null) ctx.putAll(outageContext);
        ctx.put(CTX_DETECTED_IN_MAINTENANCE, now());
        if (ctx.get("alert_level") instanceof String lvl && !lvl.isBlank()) alertLevel = lvl;
        Object ctxTeam = ctx.get("team_id");
        // İki dal da REFERANS (Long.valueOf): `long : Long` koşulu sayısal koşula döner ve null dalı kutudan
        // çıkarırken NPE atar (javac'ta da; ECJ farkı için CLAUDE.md "Things that bite").
        Long teamId = ctxTeam instanceof Number n ? Long.valueOf(n.longValue())
                : isStandalone(alertType, ctx) ? null
                : inventoryRepo.findByDomain(domain).map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
        AlertEvent event = newEvent(domain, alertLevel, alertType, monitoringMessage(domain, alertType, alertLevel, ctx), null);
        event.setTeamId(teamId);
        event.setNotificationGroupId(resolveStampFromContext(ctx, domain));
        event.setContextJson(snapshotContext(ctx));
        event.setNotifiedContacts("[]");
        // lastReAlertAt BİLEREK null: "ilk bildirim yarıda kaldı" dalı pencere bitince INITIAL'ı gönderir.
        AlertEvent saved = alertEventRepo.save(event);
        log.warn("🔧 Değişiklik alarmı bakım penceresinde açıldı (bildirim pencere bitince): {} [{}] olay #{}",
                domain, alertType, saved == null ? null : saved.getId());
    }

    /**
     * O-A3-5: ilk bildirimi hiç gitmemiş (bakımda açılmış) açık değişiklik olayının INITIAL'ını bağlam anlık
     * görüntüsüyle tamamlar — bakım hâlâ sürüyorsa {@link #processConfirmedOutage} kapısı yine bastırır; gönderilince
     * {@code lastReAlertAt} damgalanır (bir daha çağrılmaz). Çağıran kilidi tutar (MonitoringOutageService.withLock).
     */
    public void completeDeferredInitialNotification(AlertEvent e) {
        if (e == null || e.getDomain() == null || e.getAlertType() == null) return;
        if (!MonitoringOutageService.MANUAL_CLOSE_TYPES.contains(e.getAlertType())) return;
        if (!initialNotificationMissing(e, now())) return;   // gönderim sürüyor olabilir (E9 payı) ya da zaten gitti
        Map<String, Object> ctx = deserializeContext(e.getContextJson());
        if (ctx == null) ctx = new LinkedHashMap<>();
        processConfirmedOutage(e.getDomain(), e.getAlertType(),
                e.getAlertLevel() != null ? e.getAlertLevel() : levelWordSafe(ctx), ctx, e.getMessage());
    }

    private static String levelWordSafe(Map<String, Object> ctx) {
        return ctx.get("alert_level") instanceof String lvl && !lvl.isBlank() ? lvl : com.sitemonitor.model.MonitorAlertPrefs.LEVEL_WARNING;
    }

    /** Alarm seviyesinin Türkçe sözcüğü — Alarm Geçmişi rozeti ve push metniyle aynı (UYARI / YÜKSEK / KRİTİK / BİLGİ). */
    public static String levelWordTr(String level) {
        return switch (level == null ? "" : level.toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH" -> "YÜKSEK";
            case "INFO", "LOW" -> "BİLGİ";
            default -> "UYARI";
        };
    }

    private static final java.util.regex.Pattern LEADING_LEVEL_WORD = java.util.regex.Pattern.compile(
            "^(KRİTİK|YÜKSEK|UYARI|ORTA|BİLGİ)\\s*:\\s*");

    /**
     * İzleme alarm iletisinin baştaki seviye sözcüğünü alarmın GERÇEK seviyesine çevirir (prod 2026-09-29).
     *
     * <p>İleti şablonları seviyeyi SABİT yazıyordu ("KRİTİK: … portuna erişilemiyor", "YÜKSEK: … DNS kaydı değişti");
     * 2026-09-19'dan beri izleme alarmları varsayılan WARNING açıldığı için Alarm Geçmişi aynı satırda "UYARI" rozeti
     * ile "KRİTİK:" metnini yan yana gösteriyordu (e-posta konusu ve push ise seviyeden türediği için doğruydu). Kural
     * TEK yerde: şablon başındaki etiket ne olursa olsun seviyeden yeniden yazılır. Yalnız izleme türlerine uygulanır;
     * sertifika iletilerinin seviye dışı etiketleri (DAĞITIM EKSİK / ZİNCİR SORUNU) ve kendi kademeleri değişmez.
     */
    static String withLevelWord(String message, String alertLevel) {
        if (message == null) return null;
        java.util.regex.Matcher m = LEADING_LEVEL_WORD.matcher(message);
        return m.find() ? levelWordTr(alertLevel) + ": " + message.substring(m.end()) : message;
    }

    /** İzleme alarm mesajı — ctx alanları varsa zenginleştirilir, yoksa buildMessage'a düşer. Seviye sözcüğü seviyeden. */
    private String monitoringMessage(String domain, String alertType, String alertLevel,
                                     Map<String, Object> ctx) {
        String body = withLevelWord(monitoringMessageBody(domain, alertType, alertLevel, ctx), alertLevel);
        // O-A3-5: bakım penceresinde algılanan değişiklik — olay notu (Alarm Geçmişi + e-posta gövdesi) bunu söyler.
        Object seen = ctx == null ? null : ctx.get(CTX_DETECTED_IN_MAINTENANCE);
        if (seen != null) {
            body = body + " Bakım penceresinde algılandı (" + formatIstanbulShort(String.valueOf(seen))
                    + "); bildirim pencere bitince gönderildi.";
        }
        return body;
    }

    /** UTC ISO damgayı "gg.aa SS:dd" (Europe/Istanbul) kısa biçime çevirir; bozuksa olduğu gibi. */
    private static String formatIstanbulShort(String iso) {
        try {
            return LocalDateTime.parse(iso, LDT).atOffset(java.time.ZoneOffset.UTC)
                    .atZoneSameInstant(java.time.ZoneId.of("Europe/Istanbul"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
        } catch (Exception e) {
            return iso;
        }
    }

    private String monitoringMessageBody(String domain, String alertType, String alertLevel,
                                         Map<String, Object> ctx) {
        if (ctx == null || ctx.isEmpty()) return buildMessage(domain, alertType, alertLevel, null);
        switch (alertType) {
            case TYPE_PORT_DOWN -> {
                Object port = ctx.get("port");
                Object proto = ctx.getOrDefault("protocol", "TCP");
                if (port != null) {
                    return "KRİTİK: " + domain + " üzerindeki " + port + "/" + proto +
                            " portuna erişilemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Port yeniden açıldığında alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_PORT_SLOW -> {
                Object port = ctx.get("port");
                Object proto = ctx.getOrDefault("protocol", "TCP");
                Object ms = ctx.get("response_ms");
                Object th = ctx.get("threshold_ms");
                return "YÜKSEK: " + domain + " üzerindeki " + port + "/" + proto +
                        " portu yanıt süresi eşiğini aştı" +
                        (ms != null ? " — " + ms + " ms" : "") + (th != null ? " (eşik " + th + " ms)" : "") + ". " +
                        "Yanıt süresi eşiğin altına indiğinde alarm otomatik kapanır.";
            }
            case TYPE_DNS_FAILURE -> {
                Object rt = ctx.get("record_type");
                if (rt != null) {
                    return "KRİTİK: " + domain + " için " + rt +
                            " DNS sorgusu çözümlenemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Çözümleme düzeldiğinde alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_DNS_SLOW -> {
                Object rt = ctx.get("record_type");
                Object ms = ctx.get("response_ms");
                Object thr = ctx.get("slow_threshold_ms");
                if (rt != null) {
                    return "YÜKSEK: " + domain + " için " + rt + " DNS sorgusu çözümleniyor ANCAK YAVAŞ — "
                            + (ms != null ? ms + " ms" : "yanıt süresi yüksek")
                            + (thr != null ? " (eşik " + thr + " ms)" : "")
                            + ". DNS sunucusunda timeout/gecikme olabilir. Yanıt hızlandığında alarm otomatik kapanır.";
                }
            }
            case TYPE_DNS_CHANGED -> {
                Object rt = ctx.get("record_type");
                String olds = joinValues(ctx.get("old_values"));
                String news = joinValues(ctx.get("new_values"));
                if (rt != null && !news.isEmpty()) {
                    return "YÜKSEK: " + domain + " için " + rt + " kaydı değişti. " +
                            "Eski değer(ler): " + (olds.isEmpty() ? "—" : olds) +
                            " → Yeni değer(ler): " + news + ". " +
                            "Bu alarm otomatik kapanmaz; değişiklik planlı ise alarmı onaylayıp manuel kapatınız.";
                }
            }
            case TYPE_DNS_UNEXPECTED -> {
                Object rt = ctx.get("record_type");
                String unexp = joinValues(ctx.get("unexpected_values"));
                String exp = joinValues(ctx.get("expected_values"));
                if (rt != null && !unexp.isEmpty()) {
                    return "YÜKSEK: " + domain + " için " + rt + " kaydında BEKLENMEYEN değer çözümleniyor: "
                            + unexp + ". Beklenen (sabitlenen): " + (exp.isEmpty() ? "—" : exp) + ". "
                            + "Olası hijack/yanlış yönlendirme — doğrulayın. Değer beklenene dönünce alarm otomatik kapanır.";
                }
            }
            case TYPE_DNS_INCONSISTENT -> {
                Object rt = ctx.get("record_type");
                Object detail = ctx.get("resolver_detail");
                if (rt != null) {
                    return "YÜKSEK: " + domain + " için " + rt + " kaydı public RESOLVER'LAR ARASINDA TUTARSIZ"
                            + (detail != null ? " — " + detail : "") + ". "
                            + "DNS propagation gecikmesi / split-DNS / olası cache poisoning. "
                            + "Resolver'lar aynı cevabı dönünce alarm otomatik kapanır.";
                }
            }
            case TYPE_KEYWORD -> {
                Object url = ctx.get("url");
                Object kw = ctx.get("keyword");
                if (url != null && kw != null) {
                    String op = ctx.get("operator") != null ? ctx.get("operator").toString() : "GTE";
                    int n = ctx.get("match_count") instanceof Number mn ? mn.intValue() : 1;
                    Object occ = ctx.get("occurrences");
                    String occPart = occ != null ? " Şu an " + occ + " kez bulundu." : "";
                    return "KRİTİK: " + url + " sayfasında \"" + kw + "\" " +
                            KeywordCheckerService.opPhrase(op, n) + " bulunmalı; koşul sağlanmıyor." + occPart +
                            " Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Koşul yeniden sağlandığında alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_KEYWORD_SLOW -> {
                Object url = ctx.getOrDefault("url", domain);
                Object ms = ctx.get("response_ms");
                Object th = ctx.get("threshold_ms");
                return "YÜKSEK: " + url + " içerik izlemesinde yanıt süresi eşiği aşıldı" +
                        (ms != null ? " — " + ms + " ms" : "") + (th != null ? " (eşik " + th + " ms)" : "") + ". " +
                        "Yanıt süresi eşiğin altına indiğinde alarm otomatik kapanır.";
            }
            case TYPE_KEYWORD_SSL -> {
                Object url = ctx.getOrDefault("url", domain);
                Object days = ctx.get("ssl_days_remaining");
                Object detail = ctx.get("detail");
                return "YÜKSEK: " + url + " içerik izlemesi host'unun TLS sertifikasında sorun" +
                        (days != null ? " — bitişe " + days + " gün" : (detail != null ? " — " + detail : "")) + ". " +
                        "Sertifika yenilendiğinde/düzeldiğinde alarm otomatik kapanır.";
            }
            case TYPE_KEYWORD_DOMAIN_EXPIRY -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object days = ctx.get("domain_days_remaining");
                return "YÜKSEK: " + dom + " (içerik izlemesi) domain kaydının süresi" +
                        (days != null ? " " + days + " gün içinde doluyor" : " dolmak üzere") + ". " +
                        "Kayıt yenilendiğinde alarm otomatik kapanır.";
            }
            case TYPE_PING_SLOW -> {
                Object host = ctx.getOrDefault("host", domain);
                Object ms = ctx.get("rtt_ms");
                Object base = ctx.get("baseline_ms");
                Object pct = ctx.get("threshold_percent");
                Object win = ctx.get("baseline_window_minutes");
                return "YÜKSEK: " + host + " ping yanıt süresi kendi taban çizgisinin üstüne çıktı" +
                        (ms != null ? " — " + ms + " ms" : "") +
                        (base != null ? " (son " + (win != null ? win : "?") + " dk ortalaması " + base + " ms" +
                                (pct != null ? ", eşik +%" + pct : "") + ")" : "") + ". " +
                        "Ardışık doğrulama ölçümleri de eşiğin üstünde kaldı. " +
                        "Süre taban çizgisine döndüğünde alarm otomatik kapanır.";
            }
            case TYPE_PING_DOWN -> {
                Object host = ctx.get("host");
                if (host != null) {
                    return "KRİTİK: " + host + " ICMP ping'e yanıt vermiyor. " +
                            "Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Host yeniden yanıt verdiğinde alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_HTTP_DOWN -> {
                Object url = ctx.getOrDefault("url", domain);
                Object status = ctx.get("http_status");
                // JSON doğrulaması (2026-10-01): yanıt GELDİ ama gövde doğrulamadan geçmedi — "istek başarısız" yanıltır.
                if (Boolean.TRUE.equals(ctx.get("json_assertion_failed"))) {
                    Object why = ctx.get("last_error");
                    return "KRİTİK: " + url + " yanıtı JSON doğrulamasından geçmedi" +
                            (status != null ? " (durum " + status + ")" : "") +
                            (why != null ? " — " + why : "") + ". " +
                            "Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Yanıt yeniden doğrulandığında alarm otomatik kapanacaktır.";
                }
                return "KRİTİK: " + url + " adresine HTTP isteği başarısız" +
                        (status != null ? " (durum " + status + ")" : "") + ". " +
                        "Ardışık doğrulama denemeleri başarısız oldu. " +
                        "Erişim geri geldiğinde alarm otomatik kapanacaktır.";
            }
            case TYPE_HTTP_SSL -> {
                Object url = ctx.getOrDefault("url", domain);
                Object days = ctx.get("ssl_days_remaining");
                Object detail = ctx.get("detail");
                return "YÜKSEK: " + url + " için TLS sertifikası sorunu" +
                        (days != null ? " — bitişe " + days + " gün" : (detail != null ? " — " + detail : "")) + ". " +
                        "Sertifika yenilendiğinde/düzeldiğinde alarm otomatik kapanır.";
            }
            case TYPE_HTTP_SLOW -> {
                Object url = ctx.getOrDefault("url", domain);
                Object ms = ctx.get("response_ms");
                Object th = ctx.get("threshold_ms");
                return "YÜKSEK: " + url + " HTTP izlemesinde yanıt süresi eşiği aşıldı" +
                        (ms != null ? " — " + ms + " ms" : "") + (th != null ? " (eşik " + th + " ms)" : "") + ". " +
                        "Bu bir kesinti değildir; yanıt süresi eşiğin altına indiğinde alarm otomatik kapanır.";
            }
            case TYPE_PAGE_DOWN -> {
                Object url = ctx.getOrDefault("url", domain);
                Object status = ctx.get("http_status");
                return "KRİTİK: " + url + " sayfası yüklenemiyor" +
                        (status != null ? " (durum " + status + ")" : "") + ". " +
                        "Ardışık doğrulama denemeleri başarısız oldu. " +
                        "Sayfa yeniden yüklendiğinde alarm otomatik kapanacaktır.";
            }
            case TYPE_PAGE_INTEGRITY -> {
                Object url = ctx.getOrDefault("url", domain);
                Object detail = ctx.get("detail");
                return "YÜKSEK: " + url + " sayfasında bütünlük sorunu tespit edildi" +
                        (detail != null ? " — " + detail : " (kırık kaynak / mixed content)") + ". " +
                        "Sorunlu kaynaklar giderildiğinde alarm otomatik kapanır.";
            }
            case TYPE_PAGESPEED_DOWN -> {
                Object url = ctx.getOrDefault("url", domain);
                Object status = ctx.get("http_status");
                return "KRİTİK: " + url + " sayfası hız ölçümü için hiç alınamadı" +
                        (status != null ? " (durum " + status + ")" : "") + ". " +
                        "Ardışık doğrulama denemeleri başarısız oldu. " +
                        "Sayfa yeniden yüklendiğinde alarm otomatik kapanacaktır.";
            }
            case TYPE_PAGESPEED_SLOW -> {
                Object url = ctx.getOrDefault("url", domain);
                Object detail = ctx.get("detail");
                return "YÜKSEK: " + url + " sayfası performans eşiğini aştı" +
                        (detail != null ? " — " + detail : "") + ". " +
                        "Bu bir KESİNTİ DEĞİLDİR: sayfa çalışıyor ancak hedeflenenden ağır/yavaş. " +
                        "Ölçüm eşiğin altına indiğinde alarm otomatik kapanır.";
            }
            case TYPE_SCRIPTED_SLOW -> {
                Object ms = ctx.get("duration_ms");
                Object th = ctx.get("threshold_ms");
                return "YÜKSEK: " + domain + " senaryosu çalışıyor ANCAK YAVAŞ" +
                        (ms != null ? " — " + ms + " ms" : "") + (th != null ? " (eşik " + th + " ms)" : "") + ". " +
                        "Koşum süresi eşiğin altına indiğinde alarm otomatik kapanır.";
            }
            case TYPE_SCRIPTED_FAIL -> {
                Object detail = ctx.get("detail");
                return "KRİTİK: " + domain + " sentetik testi başarısız" +
                        (detail != null ? " — " + detail : "") + ". " +
                        "Ardışık doğrulama denemeleri başarısız oldu. Test yeniden geçtiğinde alarm otomatik kapanır.";
            }
            case TYPE_DOMAIN_EXPIRY -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object days = ctx.get("domain_days_remaining");
                return "YÜKSEK: " + dom + " domain kaydının (registrar) süresi" +
                        (days != null ? " " + days + " gün içinde doluyor" : " dolmak üzere") + ". " +
                        "Kayıt yenilendiğinde alarm otomatik kapanır.";
            }
            case TYPE_DOMAINMON_EXPIRY -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object days = ctx.get("days");
                Object exp = ctx.get("expiry_date");
                Object reg = ctx.get("registrar");
                // D7: iki kollu etiket WARNING'de "YÜKSEK" yazıyordu → çelişki. Baştaki etiket artık
                // monitoringMessage → withLevelWord ile seviyeden yeniden yazılır (WARNING → "UYARI").
                return ("CRITICAL".equals(alertLevel) ? "KRİTİK"
                        : "HIGH".equals(alertLevel) ? "YÜKSEK" : "ORTA") + ": " + dom + " alan adının kaydı" +
                        (days != null ? " " + days + " gün içinde doluyor" : " dolmak üzere") +
                        (exp != null ? " (bitiş: " + exp + ")" : "") + (reg != null ? ", registrar: " + reg : "") +
                        ". Önerilen aksiyon: registrar üzerinden yenileyin. Yenilenince alarm otomatik kapanır.";
            }
            case TYPE_DOMAINMON_UNKNOWN -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object err = ctx.get("last_error");
                return "UYARI: " + dom + " alan adının kayıt bilgisi ALINAMADI (RDAP/WHOIS yanıt vermedi ya da tarih ayrıştırılamadı)" +
                        (err != null ? " — " + err : "") + ". \"Veri yok\" bir sorun DEĞİL ama körlük yaratır: " +
                        "erişim/proxy/TLD desteğini doğrulayın. Veri gelince alarm otomatik kapanır.";
            }
            case TYPE_DOMAINMON_STATUS -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object codes = ctx.get("status_codes");
                // Transfer kilidi önerisi BURADAN ÇIKTI: artık kendi alarmı var ve iki farklı iş
                // (EPP durumu ile kilit) tek mailde birleşince ikisi de gölgeleniyordu.
                return ("CRITICAL".equals(alertLevel) ? "KRİTİK" : "YÜKSEK") + ": " + dom + " alan adında dikkat gerektiren EPP durum kodları: " +
                        (codes != null && !codes.toString().isBlank() ? codes : "—") +
                        ". redemptionPeriod/pendingDelete/hold → derhal müdahale gerekir.";
            }
            case TYPE_DOMAINMON_TRANSFER_LOCK -> {
                Object dom = ctx.getOrDefault("domain", domain);
                return "YÜKSEK: " + dom + " alan adında TRANSFER KİLİDİ YOK. Kilitsiz bir alan adı, "
                        + "registrar hesabı ele geçirilirse başka bir registrar'a taşınabilir. "
                        + "Önerilen aksiyon: registrar panelinden clientTransferProhibited kilidini etkinleştirin "
                        + "(mümkünse registry seviyesinde serverTransferProhibited de). "
                        + "Kilit görüldüğünde alarm otomatik kapanır.";
            }
            case TYPE_DOMAINMON_BLACKLIST -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object det = ctx.get("blacklist_detail");
                Object hits = ctx.get("blacklist_hits");
                StringBuilder sb = new StringBuilder("YÜKSEK: " + dom + " alan adı ya da IP'leri e-posta KARA LİSTESİNDE"
                        + (hits != null ? " (" + hits + " liste)" : "") + ".");
                // Kanıt maile GİRER: "hangi liste, hangi IP" olmadan alarmı alan kişi hiçbir şey yapamaz.
                for (var e : DnsblCheckerService.parseDetail(det == null ? null : det.toString()).entrySet()) {
                    sb.append(" ").append(e.getKey()).append(" → ").append(e.getValue());
                    String url = DnsblCheckerService.delistUrl(e.getKey());
                    if (url != null) sb.append(" (çıkarma: ").append(url).append(")");
                    sb.append(";");
                }
                Object delta = ctx.get("blacklist_delta");
                // Delist ilerlemesi: "hâlâ 2 listede" / "çıktı: bl.spamcop.net".
                if (delta != null && !delta.toString().isBlank()) sb.append(" Durum: ").append(delta).append(".");
                sb.append(" Listeden çıkınca alarm otomatik kapanır.");
                return sb.toString();
            }
            case TYPE_DOMAINMON_CHANGED -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object det = ctx.get("change_detail");
                return "YÜKSEK: " + dom + " alan adının kayıt bilgisinde DEĞİŞİKLİK tespit edildi" + (det != null ? " — " + det : "") +
                        ". Olası istenmeyen transfer/hijack — doğrulayın. Bu alarm otomatik kapanmaz; inceleyip onaylayın.";
            }
            default -> { /* ACCESSIBILITY → buildMessage */ }
        }
        return buildMessage(domain, alertType, alertLevel, null);
    }

    @SuppressWarnings("unchecked")
    private static String joinValues(Object v) {
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return v != null ? String.valueOf(v) : "";
    }

    /**
     * Silent close for inventory delete path — does NOT trigger resolution email.
     * Admin is intentionally decommissioning the domain; monitoring stops, so
     * spamming contacts with a "resolved" email would be noise. Since 2026-10-07 the delete is
     * PERMANENT ({@link PermanentDeletionService}); this runs first, in the same transaction.
     */
    public int closeAlertsOnInventoryDelete(String domain) {
        return closeOpenAlerts(domain, "inventory_delete", "envanter silindi");
    }

    /**
     * Silent close for inventory deactivation (active=false). Pasife alınan domain
     * artık taranmaz; açık alarmları otomatik çözülemeyeceğinden burada sessizce
     * (resolution maili olmadan) kapatılır. Delete'ten farkı: kayıt silinmez,
     * resolvedBy = "inventory_deactivate".
     */
    public int closeAlertsOnDeactivate(String domain) {
        return closeOpenAlerts(domain, "inventory_deactivate", "domain pasife alındı");
    }

    private int closeOpenAlerts(String domain, String resolvedBy, String reason) {
        int closed = 0;
        for (AlertEvent event : alertEventRepo.findByDomainAndResolvedFalse(domain)) {
            if (!closableByInventory(event, reason)) continue;
            // O-b2: envanter silindi/pasifleşti — kurtulmadı, susturuldu. R2b · F: koşullu kapanış; başka yolda kapanmış
            // alarm sayılmaz, push'u da gitmez.
            AlertEvent saved = closeSilentlyIfOpen(event, resolvedBy, true);
            if (saved == null) continue;
            enqueueResolvePushQuietly(saved);   // resolveOpenAlertsSilently ile aynı gerekçe
            log.info("Alarm kapatıldı ({}): {} [{}]", reason, domain, event.getAlertType());
            closed++;
        }
        return closed;
    }

    /**
     * Y-A3-1 (2026-09-29, D-b1'in envanter yolu): envanter silme / pasifleştirme YALNIZ envanterin KENDİ sahip anahtarındaki
     * ({@code INV}: sertifika türleri, envanter türevi Port/DNS, ACCESSIBILITY) olayları kapatır. Alarm anahtarı (alan adı +
     * tür) takımlar arasında paylaşılır: başka takımın BAĞIMSIZ Port/Ping/DNS/alan adı izlemesinin açık olayı (damgalı ya da
     * tür listesiyle bağımsız) envantere ait değildir — eskiden o da "inventory_deactivate" ile sessizce kapanıyor, o takımın
     * nöbetçisine hedef hâlâ düşükken "DÜZELDİ" push'u gidiyor ve bir sonraki turda yeni INITIAL açılıyordu.
     */
    static boolean closableByInventory(AlertEvent event, String reason) {
        if (!isStandaloneEvent(event)) return true;
        log.info("Envanter kapanışı atlandı ({}): {} [{}] #{} bağımsız izlemenin olayı (sahip {}) — kendi izlemesi kapatır",
                reason, event.getDomain(), event.getAlertType(), event.getId(), ownerKeyOf(event));
        return false;
    }

    // 2026-10-07: catchUpAlertsOnDeletedDomains (açılışta yumuşak silinmiş envanterin açık alarmlarını kapatan emniyet ağı)
    // KALDIRILDI — envanter silmesi artık KALICI (PermanentDeletionService alarmları silme anında kapatır) ve eski çöp kutusu
    // tek seferlik temizlikte (DeletedRecordsPurge) alarmlarıyla birlikte kapandı; yumuşak silinmiş satır kalmaz.

    /**
     * Çözüm bildirimi (eşzamansız, {@code notifyExecutor}). Kalıcı işaret varsa önce SAHİPLENİR (PENDING → SENDING): kapanışta
     * düşen ya da hiç başlamayan iş işareti PENDING bırakır ve {@code ResolutionNotifySweeper} başka pod'dan BİR kez gönderir;
     * süpürücü önce sahiplendiyse bu iş göndermez. İşaretsiz satır (eski pod, işaret yazılamadı) eski davranış: gönderilir.
     */
    @Async("notifyExecutor")
    public void sendResolutionNotificationAsync(AlertEvent event, String resolvedBy, String trigger) {
        try {
            // İşareti bu kapanış yazdıysa (inceleme F3) sahiplenme hatasında GÖNDERİLMEZ — süpürücü BİR kez gönderir; işaret
            // durumu bilinmeden göndermek süpürücünün ikinci postasına yol açıyordu.
            boolean marked = event != null && RESOLUTION_NOTIFY_PENDING.equals(event.getResolutionNotify());
            ResolutionClaim claim = claimResolutionNotify(event != null ? event.getId() : null, marked);
            if (!claim.send()) {
                log.info("Çözüm bildirimi başka yolda/pod'da sahiplenilmiş ya da süpürücüye bırakıldı — bu iş göndermedi: olay {}",
                        event != null ? event.getId() : null);
                return;
            }
            if (claim.at() == null) {
                sendResolutionNotification(event, resolvedBy, trigger);   // işaretsiz satır: eski davranış
            } else {
                deliverClaimedResolution(event, resolvedBy, trigger, claim.at());
            }
        } catch (Exception e) {
            log.error("Async resolution notification failed for alertEventId={}: {}",
                    event != null ? event.getId() : null, e.getMessage(), e);
        }
    }

    private void sendResolutionNotification(AlertEvent event, String resolvedBy, String trigger) {
        sendResolutionNotification(event, resolvedBy, trigger, false);
    }

    /**
     * {@code propagate}: gövdedeki istisna yutulmaz, çağırana gider (inceleme S-5) — sahiplenilmiş çözüm postası ({@link
     * #deliverClaimedResolution}) sonucu FAILED olarak kaydedebilsin; eski yol (işaretsiz) istisnayı günlüğe yazıp yutar.
     */
    private void sendResolutionNotification(AlertEvent event, String resolvedBy, String trigger, boolean propagate) {
        // SİSTEM BAKIMI (2026-10-02, kullanıcı kararı): "Bildirimler bakım boyunca sussun" açık bakım AKTİFKEN çözüm de
        // hiçbir kanaldan gitmez (e-posta, webhook, push, 7/24) — iz SYSTEM_MAINTENANCE satırı. Bakım içinde kapanan alarm
        // için bakım sonrasında da bir şey gönderilmez (telafi yalnız hâlâ AÇIK alarmların açılışıdır).
        if (event != null && systemMaintenanceMuted(trigger)) {
            recordSystemMaintenanceSuppression(event.getId(), event.getTeamId(), null, event.getDomain(),
                    event.getAlertLevel(), event.getAlertType(), trigger, "✅ " + event.getDomain() + " — sorun giderildi");
            return;
        }
        // 7/24 İzleme Ekibi: takımın çözüm e-postasından BAĞIMSIZ (aşağıdaki "alıcı yok / e-posta kapalı" erken
        // dönüşünden ÖNCE); açılışı NOC'a gitmediyse servis hiçbir şey göndermez.
        notifyNocResolved(event);
        try {
            // Çözüm bildirimi alarmla AYNI alıcılara gitmeli: standalone izleme takımı AlertEvent.teamId'den (envanter
            // değil); KRİTİK domain alarmında müdür de dahildi → çözümü de alır. Diğer standalone → yalnız takım.
            boolean standalone = isStandaloneMon(event.getAlertType());
            Long domainTeamId, ugTeamId;
            List<EscalationContact> contacts;
            if (standalone) {
                domainTeamId = event.getTeamId();
                ugTeamId = null;
                // Gecikmeli kişi çözümü YALNIZ adımını aldıysa alır (açılışı hiç görmediği alarmın "çözüldü"sü gitmez).
                contacts = includeManagerContacts(event.getAlertType(), event.getAlertLevel())
                        ? recipientsNow(event.getAlertLevel(), domainTeamId, null, event.getId())
                        : List.of();
            } else {
                // Bağımsız olay envanterden takım ALMAZ (2026-09-28; açılış ve tekrar bildir ile aynı kural).
                var inventoryOpt = isStandaloneEvent(event)
                        ? Optional.<com.sitemonitor.model.CertificateInventory>empty() : inventoryRepo.findByDomain(event.getDomain());
                // DAMGALANMIŞ takım önceliklidir. PORT/DNS alarmları isStandaloneMon listesinde
                // olmadığı için buraya düşüyor; envanterde OLMAYAN bir host'ta (ör. kullanıcı Port
                // izlemesinin host'unu düzenledi → detachIfIdentityChanged) teamId null kalıyor ve
                // çözüm bildirimi KİMSEYE gitmiyordu. Açılışta takım event'e yazılmıştı; onu okumak
                // üç yolu (açılış / çözüm / tekrar-bildir) tek doğruluk kaynağına bağlar.
                Long invTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
                domainTeamId = event.getTeamId() != null ? event.getTeamId() : invTeamId;
                ugTeamId     = includeInventoryUgTeam(event, invTeamId)
                        ? inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null)
                        : null;
                // Açılışla AYNI kontak kararı (O-1): bağımsız PORT/DNS izlemesinde (bağlamda team_id) WARNING yalnız takım.
                contacts = dueNow(contactsFor(isStandaloneEvent(event), event.getAlertLevel(), domainTeamId, ugTeamId),
                        event.getId());
                // Damgasız eski olayı çözümde tek seferlik damgala: push satırı ve "tekrar bildir"
                // aynı takımı görsün (açılış yolundaki geri doldurmanın çözüm eşleniği).
                if (event.getTeamId() == null && invTeamId != null) {
                    event.setTeamId(invTeamId);
                    try { persist(event, "teamId"); } catch (Exception ignore) { /* damga best-effort */ }
                }
            }
            // Sahipsiz kayıt (2026-09-28): açılışla AYNI kapı — takımı olmayan alarmın çözümü de hiçbir kanaldan gitmez
            // (e-posta, kontak webhook'u, push). 7/24 çözüm tetiği yukarıda: yalnız açılışı NOC'a gitmiş alarmda gönderir.
            if (domainTeamId == null && ugTeamId == null) {
                log.warn("Sahipsiz kayıt — çözüm bildirimi gönderilmedi: olay={} alan={} tür={}",
                        event.getId(), event.getDomain(), event.getAlertType());
                // İZ (L-E4, 2026-10-22): açılışın "SKIPPED: takım yok" satırının çözüm eşleniği — her karar günlükte.
                saveLog(event.getId(), "-", "", resolutionSubject(event), "", STATUS_NO_TEAM, "SKIPPED", trigger);
                return;
            }

            // SESSİZ SAAT (2026-10-01, onaylı öneri 15): açılışı sessiz saat özetine ertelenmiş ve özeti HENÜZ gitmemiş alarmın
            // çözümü o takıma ayrı "çözüldü" postası olarak GİTMEZ — takım hiç duymadığı alarmın çözümünü almaz; özet onu
            // "çözüldü" diye gösterir. Açılışı ertelenmemiş (takım duymuş) alarmda çözüm bugünkü gibi gider. Sorgu yalnız sahip
            // takımda pencere TANIMLIYSA atılır (tanımsız kurulumda sıfır ek sorgu).
            Long mailSy = domainTeamId, mailUg = ugTeamId;
            QuietFold fold = quietResolutionFold(event, domainTeamId, ugTeamId);
            if (!fold.folded().isEmpty()) {
                recordQuietResolutionFold(event, fold.folded(), domainTeamId);
                boolean allFolded = (domainTeamId == null || fold.folded().contains(domainTeamId))
                        && (ugTeamId == null || fold.folded().contains(ugTeamId));
                if (allFolded) {
                    log.info("Sessiz saat — çözüm bildirimi özete katlandı: olay={} alan={}", event.getId(), event.getDomain());
                    // Push simetri kuralı aynen: açılış push'u ertelendiyse SENT yok → SKIPPED_NO_PRIOR (bugünkü karar satırı).
                    try {
                        userPushService.enqueueResolve(event, deserializeContext(event.getContextJson()), domainTeamId);
                    } catch (Exception ex) {
                        log.warn("user-push çözüm tetiği atlandı (sessiz saat dalı): {}", ex.toString());
                    }
                    return;
                }
                if (domainTeamId != null && fold.folded().contains(domainTeamId)) mailSy = null;
                if (ugTeamId != null && fold.folded().contains(ugTeamId)) mailUg = null;
                Set<Long> foldedTeams = fold.folded();
                contacts = contacts.stream()
                        .filter(c -> c.getTeamId() == null || !foldedTeams.contains(c.getTeamId()))
                        .toList();
            }
            // Açılışı duyulmuş ama pencerede bir hatırlatması ertelenmiş takım çözümü ŞİMDİ normal alır → özet tekrar etmez.
            if (!fold.supersede().isEmpty()) {
                try {
                    quietHours.supersede(event.getId(), fold.supersede(), quietHours.now());
                } catch (Exception ex) {
                    log.warn("Sessiz saat özet kaydı geçersizleştirilemedi (olay {}): {}", event.getId(), ex.toString());
                }
            }

            // Build combined TO: team emails + contact emails (deduped)
            // Cozum bildirimi alarmin DAMGASINI kullanir: alarm surerken monitorun grubu
            // degistiyse bile kapanis, acilisi ogrenen ekibe gider.
            List<String> teamEmails = collectTeamEmails(mailSy, mailUg, event.getNotificationGroupId());
            Set<String> seen = new HashSet<>();
            List<String> allEmails = new ArrayList<>();
            for (String e : teamEmails) {
                if (e != null && seen.add(e.trim().toLowerCase())) allEmails.add(e.trim());   // D8: kontak tarafıyla aynı normalizasyon
            }
            for (EscalationContact c : contacts) {
                if (c.getEmail() != null && !c.getEmail().isBlank()
                        && seen.add(c.getEmail().trim().toLowerCase()))
                    allEmails.add(c.getEmail().trim());
            }
            // Nöbetçi (2026-10-11 → 2026-10-12): çözüm, alarmı nöbetçi olarak ALMIŞ adreslere gider — o anki nöbetçiye değil.
            // Devirden sonra yeni nöbetçi görmediği alarmın "çözüldü"sünü almaz; açılışı alan eski nöbetçi kapanışı öğrenir.
            for (String e : oncallMailedFor(event)) {
                if (seen.add(e.toLowerCase())) allEmails.add(e);
            }
            // Bulgu 13: izlemede "E-posta" kapalıysa AÇILIŞ maili gitmiyor ama ÇÖZÜLDÜ maili
            // gidiyordu — bastırma paritesi kırıktı. Damga artık contextJson'a kalıcılaşıyor
            // (snapshotContext), böylece çözüm yolu da aynı kararı okuyabiliyor.
            // certContext aşağıda kurulur; damga olayın KENDİ contextJson'ında saklı olduğu için
            // burada erkenden okunabilir (cert alarmlarında contextJson yok → null → bugünkü davranış).
            Map<String, Object> earlyCtx = deserializeContext(event.getContextJson());
            boolean mailDisabled = earlyCtx != null && Boolean.TRUE.equals(earlyCtx.get("mail_disabled"));
            String subject = resolutionSubject(event);
            if (mailDisabled || allEmails.isEmpty()) {
                log.info("Çözüm bildirimi — mail atlandı ({}): {}",
                        mailDisabled ? "izlemede e-posta kapalı" : "alıcı yok", event.getDomain());
                // İZ (L-Q2, 2026-10-22): atlanan çözüm postası da tek satırla günlüğe girer (açılış yoluyla aynı metinler).
                saveLog(event.getId(), ownerTeamNames(mailSy, mailUg), "", subject, "",
                        mailDisabled ? "SKIPPED: e-posta kanalı kapalı" : "SKIPPED: alıcı yok", "SKIPPED", trigger);
                // Kontak webhook'u da mailden BAĞIMSIZ (O-2): açılış yolu mail atlansa da webhook'u gönderiyor.
                sendResolutionWebhooks(event, contacts, subject, trigger);
                // Kanal BAĞIMSIZLIĞI: e-posta alıcısı yoksa push da düşmemeli. Açılış yolunda bu
                // düzeltilmişti (N10), çözüm yolunda erken dönüş enqueueResolve'dan ÖNCE olduğu için
                // duruyordu: e-postasız takım açılış push'unu alıp "DÜZELDİ" push'unu ASLA almıyor,
                // telefonda alarm sonsuza dek açık kalıyordu.
                try {
                    userPushService.enqueueResolve(event, earlyCtx, domainTeamId);
                } catch (Exception ex) {
                    log.warn("user-push çözüm tetiği atlandı (mail-dışı dal): {}", ex.toString());
                }
                return;
            }

            // İzleme çözüm mailleri süreyi createdAt→resolvedAt'ten hesaplar;
            // sertifika context'i alakasız olduğundan geçilmez.
            Map<String, Object> certContext;
            if (isDomainMon(event.getAlertType())) {   // domain → en güncel kayıt (yenilenmiş bitiş/registrar)
                certContext = reconstructDomainContext(event.getDomain());
            } else if (standalone) {        // keyword/ping/sayfa — alarm anı snapshot'ından detay
                certContext = deserializeContext(event.getContextJson());
                // Sayfa çözümünde CANLI güncel durum: son PageCheck → mail "ne çözüldü / şu an sağlıklı" gösterir
                // (snapshot alarm anını, resolved_* anahtarları çözüm anını taşır; hata olursa zenginleştirme atlanır).
                if (isPage(event.getAlertType())) {
                    try {
                        Long monId = certContext != null && certContext.get("monitor_id") instanceof Number n
                                ? n.longValue() : null;
                        if (monId != null) {
                            var latest = pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(monId).orElse(null);
                            if (latest != null) {
                                Map<String, Object> enriched = new LinkedHashMap<>(certContext);
                                enriched.put("resolved_page_status",     latest.getStatus());
                                enriched.put("resolved_total_resources", latest.getTotalResources());
                                enriched.put("resolved_broken",          latest.getBrokenResources());
                                enriched.put("resolved_timeout",         latest.getTimeoutCount());
                                enriched.put("resolved_mixed",           latest.getMixedContentCount());
                                enriched.put("resolved_checked_at",      latest.getCheckedAt());
                                certContext = enriched;
                            }
                        }
                    } catch (Exception ignore) { /* zenginleştirilemezse mail yine sade hâliyle gönderilir */ }
                }
            } else if (MONITORING_ALERT_TYPES.contains(event.getAlertType())) {
                certContext = null;
            } else {
                certContext = latestCheckRepo.findById(event.getDomain()).map(this::latestToCertContext).orElse(null);
            }
            // Çözüm postasında da olay kimliği taşınır (aksiyon butonları için); ctx null olabildiği
            // için kopya map'e sarılır — MONITORING tiplerinde yukarıda bilerek null'a çekiliyor.
            certContext = withAlertEventId(certContext, event.getId());
            String teamNames = collectTeamNames(mailSy, mailUg, event.getNotificationGroupId());
            // Recovery erişilebilirlik özeti — yalnız HTTP uptime örneği olan tipte (ACCESSIBILITY); veri yoksa null.
            EmailNotificationService.UptimeSummary uptime = null;
            if (TYPE_ACCESSIBILITY.equals(event.getAlertType())) {
                try {
                    EmailNotificationService.AvailabilityRow r24 = weeklyAvailability.availabilityLastHours(event.getDomain(), 24);
                    if (r24.availabilityPct() != null) {
                        EmailNotificationService.AvailabilityRow r7 = weeklyAvailability.availabilityLastHours(event.getDomain(), 24L * 7);
                        uptime = new EmailNotificationService.UptimeSummary(
                                r24.availabilityPct(), r24.outageCount(), r7.availabilityPct(), r7.outageCount());
                    }
                } catch (Exception ignore) { /* özet üretilemezse mail yine gönderilir */ }
            }
            String htmlBody = emailService.buildResolutionEmailHtml(
                    event.getDomain(), event.getAlertType(), event.getAlertLevel(),
                    event.getDaysRemaining(), resolvedBy, event.getResolvedAt(),
                    event.getCreatedAt(), certContext, teamNames, uptime);
            String status = emailService.sendResolutionAlert(
                    allEmails.toArray(new String[0]), subject,
                    event.getDomain(), event.getAlertType(), event.getAlertLevel(),
                    event.getDaysRemaining(), resolvedBy, event.getResolvedAt(),
                    event.getCreatedAt(), certContext, teamNames, uptime);
            saveLog(event.getId(), teamNames, String.join(", ", allEmails), subject, htmlBody, status, "SKIPPED", trigger);
            log.info("Çözüm bildirimi → {} alıcı status={}", allEmails.size(), status);   // adres değil sayı (P4-1)
            sendResolutionWebhooks(event, contacts, subject, trigger);
            // Kişi-webhook çözüm push'u — mail sonucundan bağımsız (kanal bağımsızlığı sözleşmesi).
            try {
                userPushService.enqueueResolve(event, certContext, domainTeamId);
            } catch (Exception ex) {
                log.warn("user-push çözüm tetiği atlandı (mail yolu etkilenmedi): {}", ex.toString());
            }
        } catch (Exception e) {
            if (propagate) throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            log.warn("Çözüm bildirimi gönderilemedi: {} — {}", event.getDomain(), e.getMessage());
        }
    }

    /** Çözüm postasının konusu (tek yer: gönderim, atlama izi ve sahipsiz kayıt izi aynı konuyu yazar). */
    private static String resolutionSubject(AlertEvent event) {
        return "[Site Monitor ✅ ÇÖZÜLDÜ] " + event.getDomain() + " — " + resolvedTypeLabel(event.getAlertType()) + " sorunu giderildi";
    }

    /**
     * Çözümde kontak webhook'ları (Teams/Slack) — açılışın AYNASI (prod kapısı 2026-09-25, O-2).
     *
     * <p>Açılış, eskalasyon ve günlük tekrar {@code sendCombinedAlert} üzerinden her kontak webhook'una gidiyordu;
     * bireysel çözüm yolu ise yalnız e-posta + kişi push'u gönderiyordu (log satırındaki webhook durumu sabit
     * "SKIPPED"). Yalnız webhook'u olan bir kontak (Teams kanalı) "🔴 alarm"ı alıp "✅ çözüldü"yü hiç almıyor,
     * kanal her olayı sonsuza dek açık gösteriyordu. Fırtına yolunda aynı kusur düzeltilmişti
     * ({@code StormService} toplu recovery webhook'u); bireysel yol çok daha sık çalışıyor. Kontak listesi
     * açılışla AYNI karardan gelir ({@link #contactsFor}); aynı adrese tek mesaj gider.
     */
    private void sendResolutionWebhooks(AlertEvent event, List<EscalationContact> contacts, String subject, String trigger) {
        if (contacts == null || contacts.isEmpty()) return;
        String text = "✅ " + event.getDomain() + " — " + resolvedTypeLabel(event.getAlertType()) + " sorunu giderildi"
                + (event.getResolvedAt() != null ? " (" + event.getResolvedAt() + " UTC)" : "") + ".";
        Set<String> sent = new HashSet<>();
        for (EscalationContact c : contacts) {
            if (c.getWebhookUrl() == null || c.getWebhookUrl().isBlank()) continue;
            if (!sent.add(c.getWebhookUrl().trim())) continue;
            String webhookStatus;
            try {
                webhookService.send(c.getWebhookType(), c.getWebhookUrl(), subject, text, "INFO");
                webhookStatus = "SENT";
            } catch (Exception e) {
                webhookStatus = "FAILED: " + e.getMessage();
                log.warn("Çözüm webhook'u başarısız [{}]: {}", WebhookService.maskUrl(c.getWebhookUrl()), e.getMessage());
            }
            saveLog(event.getId(), c, subject, text, "SKIPPED", webhookStatus, trigger);
        }
    }

    /**
     * Bir sertifika sonucundan doğan alarm tipleri — <b>birden fazla olabilir</b>.
     *
     * <p><b>Neden liste.</b> Eskiden tek tip dönülüyordu ve güvenlik hükmü {@code EXPIRY}'nin
     * önündeydi. Hostname uyuşmazlığı — REVOKED/CHAIN_BROKEN'ın aksine — <b>kalıcı</b> bir durum
     * olabilir (sertifika yalnız {@code www.x.com} kapsıyor ama izleme {@code x.com}). Böyle bir
     * domainde her taramada güvenlik tipi dönüyor, sertifikanın süresi dolsa bile EXPIRY alarmı
     * HİÇ açılmıyordu; üstelik önceden açılmış bir EXPIRY olayı da anahtar eşleşmediği için
     * kapanmıyor, "açık ama sessiz" kalıyordu. Bir sertifika izleme aracının birincil vaadi buydu.
     *
     * <p><b>Sıra ve dışlama.</b> REVOKED / MISMATCH / CHAIN_BROKEN birbirini dışlar ve — bugünkü
     * davranış aynen korunarak — tek başlarına dönerler: sertifikanın kendisi bozukken süreyi
     * ayrıca alarma bağlamak gürültüdür. Güvenlik ve süre ise birbirinden bağımsızdır, ikisi de
     * doğruysa İKİSİ birden döner.
     */
    private List<String> determineAlertTypes(Map<String, Object> result) {
        String revocationStatus = (String) result.get("revocation_status");
        String deploymentStatus = (String) result.get("deployment_status");
        String chainStatus = (String) result.get("chain_status");
        Boolean warning = (Boolean) result.get("warning");
        String status = (String) result.get("status");

        if ("REVOKED".equals(revocationStatus)) return List.of("REVOKED");
        if ("INCOMPLETE".equals(deploymentStatus)) return List.of("MISMATCH");
        if ("BROKEN".equals(chainStatus)) return List.of("CHAIN_BROKEN");

        List<String> types = new ArrayList<>(2);
        String security = securityAlertType(result);
        if (security != null) types.add(security);
        if (Boolean.TRUE.equals(warning) || "error".equals(status)) types.add("EXPIRY");
        return types;
    }

    /**
     * Güvenlik bayrakları → alarm tipi; her biri kendi ayarıyla kapılı.
     *
     * <p><b>Varsayılanlar neden farklı.</b> Hostname uyuşmazlığı meşru olarak neredeyse hiç
     * olmaz → AÇIK. Güvenilmeyen CA ise kurumsal gerçekliğe bağlı: {@code trust.ca-bundle-pem}
     * boşsa (helm varsayılanı) iç host'ların TÜMU UNTRUSTED görünür ve açık gelseydi yayın
     * anında alarm seli olurdu → KAPALI, admin CA paketini doldurduktan sonra açar.
     *
     * <p>Ayarlar yalnız ALARM ÜRETİMİNİ yönetir; rozet ve sağlık satırları koşulsuz doğruyu söyler.
     */
    /**
     * Bu turda artık üretilmeyen sertifika türlerini kapatır. D-7 (2026-09-29): bayrağı HÂLÂ duran ama alarm türü AYARDAN
     * kapatılmış (HOSTNAME_MISMATCH / UNTRUSTED_CA) tür "✅ sorun giderildi" diye DEĞİL, SESSİZCE kapanır — sorun
     * giderilmedi, yalnız alarmı istenmiyor. {@code silentAll}: sertifika bildirimleri tümden kapalı (D-1).
     */
    private void closeStaleCertTypes(String domain, Set<String> stale, Map<String, Object> result, boolean silentAll,
                                     java.util.function.Predicate<String> hasOpen) {
        Set<String> quiet = new LinkedHashSet<>();
        if (silentAll) quiet.addAll(stale);
        else for (String t : settingDisabledCertTypes(result)) if (stale.contains(t)) quiet.add(t);
        Set<String> loud = new LinkedHashSet<>(stale);
        loud.removeAll(quiet);
        // D-b17 (tek pod maliyeti): "ayardan kapalı" sessiz küme D-7 ile alan adı başına İKİNCİ bir sorgu ekledi — güvenilmeyen
        // CA alarmı varsayılan kapalı ve kurumsal CA paketi boşken iç host'ların tamamı bayraklı olduğundan her turda yüzlerce
        // boş sorgu. Turun başındaki toplu açık-alarm haritasında AÇIK olmayan tür için kapanış sorgusu atılmaz.
        if (!silentAll && hasOpen != null) quiet.removeIf(t -> !hasOpen.test(t));
        if (!quiet.isEmpty()) resolveOpenAlertsSilently(domain, quiet, silentAll
                ? "Sistem (otomatik — sertifika alarm bildirimleri kapalı)"
                : "Sistem (alarm türü ayardan kapatıldı — sorun sürüyor olabilir)");
        if (!loud.isEmpty()) resolveOpenAlertsForDomain(domain, loud);
    }

    /** Güvenlik bayrağı sonuçta DURAN ama alarmı ayardan KAPALI sertifika türleri (D-7). */
    private Set<String> settingDisabledCertTypes(Map<String, Object> result) {
        java.util.List<String> flags = securityFlagsOf(result);
        Set<String> out = new LinkedHashSet<>();
        if (flags.contains(CertificateHealthRules.FLAG_HOSTNAME_MISMATCH)
                && !appSettings.getBoolean(SETTING_ALERT_HOSTNAME_MISMATCH, true)) out.add(TYPE_HOSTNAME_MISMATCH);
        if (flags.contains(CertificateHealthRules.FLAG_UNTRUSTED_CA)
                && !appSettings.getBoolean(SETTING_ALERT_UNTRUSTED, false)) out.add(TYPE_UNTRUSTED_CA);
        return out;
    }

    private static java.util.List<String> securityFlagsOf(Map<String, Object> result) {
        Object sanRaw = result.get("san");
        java.util.List<String> san = sanRaw instanceof java.util.List<?> l
                ? l.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList()
                : java.util.List.of();
        return CertificateHealthRules.securityFlags(
                (String) result.get("domain"), san, (String) result.get("trust_status"), isManualResult(result));
    }

    /**
     * Elle yüklenen sertifikanın çevrim-dışı değerlendirme sonucu mu (2026-10-06,
     * {@code ManualCertificateEvaluationService}: {@code manual=true}). Takip adı bir host adı değildir →
     * HOSTNAME_MISMATCH bu sonuçlarda HİÇ üretilmez. Ağ kontrolü bu anahtarı üretmez: davranış birebir aynı.
     */
    static boolean isManualResult(Map<String, Object> result) {
        return result != null && Boolean.TRUE.equals(result.get("manual"));
    }

    private String securityAlertType(Map<String, Object> result) {
        Object sanRaw = result.get("san");
        java.util.List<String> san = sanRaw instanceof java.util.List<?> l
                ? l.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList()
                : java.util.List.of();
        java.util.List<String> flags = CertificateHealthRules.securityFlags(
                (String) result.get("domain"), san, (String) result.get("trust_status"), isManualResult(result));
        if (flags.contains(CertificateHealthRules.FLAG_HOSTNAME_MISMATCH)
                && appSettings.getBoolean(SETTING_ALERT_HOSTNAME_MISMATCH, true)) {
            return TYPE_HOSTNAME_MISMATCH;
        }
        if (flags.contains(CertificateHealthRules.FLAG_UNTRUSTED_CA)
                && appSettings.getBoolean(SETTING_ALERT_UNTRUSTED, false)) {
            return TYPE_UNTRUSTED_CA;
        }
        return null;
    }

    public static final String SETTING_ALERT_HOSTNAME_MISMATCH = "site.monitor.trust.alert-hostname-mismatch";
    public static final String SETTING_ALERT_UNTRUSTED = "site.monitor.trust.alert-untrusted";

    /** Sertifikaya erişilemediğini / sertifika bilgilerinin alınamadığını gösteren,
     *  ağ veya firewall kaynaklı ulaşılabilirlik hata sınıfları. Bunlar gerçek bir
     *  sertifika kusuru değildir; gerçek kusurlar REVOKED/MISMATCH/CHAIN_BROKEN ve
     *  eşik tabanlı (gün) son kullanma alarmlarıdır. */
    private static final Set<String> REACHABILITY_ERROR_CLASSES = Set.of("NETWORK", "DNS");

    private String determineAlertLevel(Map<String, Object> result, String alertType,
                                        AlertThreshold threshold) {
        // Doğrulanmış sertifika kusurları → her zaman KRİTİK (müdüre eskalasyon haklı).
        if ("REVOKED".equals(alertType) || "MISMATCH".equals(alertType)
                || "CHAIN_BROKEN".equals(alertType)
                || TYPE_HOSTNAME_MISMATCH.equals(alertType) || TYPE_UNTRUSTED_CA.equals(alertType)) {
            return "CRITICAL";
        }
        // Sertifikaya erişilemedi / bilgileri alınamadı (status=error). Ağ veya firewall
        // kaynaklı bir ulaşılabilirlik hatası gerçek bir sertifika sorunu değildir →
        // müdürü KRİTİK ile rahatsız etmemek için UYARI seviyesinde tut; takım bildirimi
        // yeterli (UYARI seviyesinde yalnız WARNING kontağı alır, müdür HIGH/CRITICAL
        // kontağıdır → otomatik hariç). Müdür yalnız eşik tabanlı YÜKSEK/KRİTİK
        // (yaklaşan son kullanma) ve doğrulanmış kusurlarda bilgilendirilir.
        if ("error".equals(result.get("status"))) {
            String errorClass = result.get("error_class") instanceof String s ? s : null;
            if (errorClass != null && REACHABILITY_ERROR_CLASSES.contains(errorClass)) {
                return "WARNING";
            }
            // SSL/UNKNOWN — olası gerçek TLS/sertifika kusuru, mevcut davranış (KRİTİK) korunur.
            return "CRITICAL";
        }
        Integer days = toInt(result.get("days_remaining"));
        if (days == null) return "CRITICAL";
        // Eşik alanları nullable ve createThreshold gövdeden gelen açık null'ı doğrulamıyor. Çıplak
        // karşılaştırma unboxing NPE'si atardı; NPE sweep'in en dışına kadar çıkıp O TURUN TÜM
        // sonuçlarını düşürürdü (alarm/e-posta üretilmez). Varsayılanlar defaultThreshold ile aynı.
        int crit = threshold.getCriticalDays() != null ? threshold.getCriticalDays() : 7;
        int high = threshold.getHighDays()     != null ? threshold.getHighDays()     : 15;
        int warn = threshold.getWarningDays()  != null ? threshold.getWarningDays()  : 30;
        if (days <= crit) return "CRITICAL";
        if (days <= high) return "HIGH";
        if (days <= warn) return "WARNING";
        return null;
    }

    /**
     * Alarmın eskalasyon kontakları — kural {@link EscalationContactScope} (ürün kararı 2026-09-28): takımlı alarmda
     * YALNIZ o takımın kontakları; takımsız (team_id IS NULL) kontak hiçbir yolda alıcı değil, takımsız alarm hiç
     * bildirim üretmez ({@code sendCombinedAlert} sahipsiz kayıt kapısı).
     *
     * <p>Eski kusur: takımda kontak yoksa "global"e düşülüyordu ama sorgular team_id'yi süzmüyordu → kontaksız
     * takımın KRİTİK alarmı (ör. HOSTNAME_MISMATCH) TÜM takımların müdürlerine gidiyordu. Artık yedek YOK: kontaksız
     * takımın alarmı yalnız takımın kendi alıcılarına (takım adresi / bildirim grubu / push) gider.
     */
    /** Önizleme (simülatör) için sessiz pasif süzgeci — gönderimle AYNI karar, günlüğe yazmaz. */
    private List<EscalationContact> withoutInactiveQuiet(List<EscalationContact> contacts) {
        if (inactiveGuard == null || contacts == null || contacts.isEmpty()) return contacts;
        List<EscalationContact> out = contacts.stream().filter(c -> !inactiveGuard.isInactiveContact(c)).toList();
        return out.size() == contacts.size() ? contacts : out;
    }

    private List<EscalationContact> getContactsForLevel(String level, Long teamId, Long ugTeamId) {
        // Her sahip takım (SY + UG) yalnız KENDİ kişilerini getirir; birleşim, e-postaya göre tekil (2026-09-28).
        List<EscalationContact> contacts = EscalationContactScope.forOwners(contactRepo, level, teamId, ugTeamId);
        // Pasif kullanıcıya bağlı kişi düşer (2026-10-02) — açılış, yeniden uyarı, seviye artışı, çözüm, elle gönderim ve
        // simülatör hep buradan geçer. Düşen varsa WARN + liste sayıyı taşır (sendCombinedAlert "pasif kullanıcı" izi).
        if (inactiveGuard != null) {
            contacts = inactiveGuard.withoutInactive(contacts, "takım " + teamId + (ugTeamId != null ? " / UG " + ugTeamId : "")
                    + " seviye " + level);
        }
        if (contacts.isEmpty() && (teamId != null || ugTeamId != null)) {
            log.warn("Takım {} (UG {}) için {} seviyesinde eskalasyon kontağı yok — alarm yalnız takım alıcılarına "
                    + "gidiyor (başka takımın ya da takımsız kontak EKLENMEZ)", teamId, ugTeamId, level);
        }
        return contacts;
    }

    // ── Zamana bağlı eskalasyon adımı (2026-10-01, opt-in) ───────────────────────────────────────────
    //
    // Ürün güvencesi: "Adım tanımlanmadıkça kimseye yeni bildirim gitmez." Hiçbir kişide gecikme yoksa aşağıdaki süzgeç
    // listeyi AYNI NESNE olarak döndürür ve hiçbir sorgu atmaz — anlık yolların alıcıları, iletileri ve günlük satırları
    // bugünküyle birebir aynıdır (kapı: EscalationStepRegressionTest). Gecikmeli kişinin adımı EscalationStepService'ten
    // gider; adım gittikten sonra kişi o alarmın normal alıcısıdır (yeniden uyarı, seviye artışı, çözüm, elle gönderim).

    /** Bildirim günlüğü tetiği: gecikmeli eskalasyon kişisine giden ya da (nedeniyle) atlanan adım. */
    public static final String TRIGGER_ESCALATION_STEP = "ESCALATION_STEP";

    /** Anlık bildirim kişileri: {@link #getContactsForLevel} + gecikmeli kişi süzgeci ({@link #dueNow}). */
    private List<EscalationContact> recipientsNow(String level, Long teamId, Long ugTeamId, Long alertEventId) {
        return dueNow(getContactsForLevel(level, teamId, ugTeamId), alertEventId);
    }

    /**
     * Gecikmeli kişilerden yalnız bu alarmın "döngüsünde" olanlar kalır (adımı gitmiş ya da alarmı gecikme tanımlanmadan
     * önce zaten almış). Listede gecikmeli kişi YOKSA aynı liste döner ve adım tablosu hiç okunmaz.
     */
    List<EscalationContact> dueNow(List<EscalationContact> contacts, Long alertEventId) {
        if (!EscalationDelay.anyDelayed(contacts)) return contacts;
        return EscalationDelay.withoutPending(contacts, loopContactIds(alertEventId));
    }

    private Set<Long> loopContactIds(Long alertEventId) {
        if (alertEventId == null || escalationStepRepo == null) return Set.of();
        try {
            List<Long> ids = escalationStepRepo.findNotifiedContactIds(alertEventId);
            return ids == null ? Set.of() : new HashSet<>(ids);
        } catch (Exception e) {
            log.warn("Eskalasyon adımı kayıtları okunamadı (olay {}) — gecikmeli kişiler bu gönderime girmiyor: {}",
                    alertEventId, e.getMessage());
            return Set.of();
        }
    }

    /** Adım işinin sahip takımları — {@link #resolveReNotifyTargets} ile AYNI kural, envanter önceden yüklenmiş. */
    public record StepOwners(Long syTeamId, Long ugTeamId) {}

    /**
     * Alarmın sahip takımları (SY + UG) — elle yeniden gönderim / çözüm yoluyla birebir aynı karar: bağımsız izleme türü
     * ya da bağlam damgası → takım YALNIZ olaydan, UG yok; envanter türevi → damga önce, yoksa envanterin SY'si; UG yalnız
     * bağımsız işareti yokken. {@code inventory}: alan adının envanter satırı (çağıran toplu yükler; yoksa null). Sorgu atmaz.
     */
    public StepOwners stepOwners(AlertEvent event, com.sitemonitor.model.CertificateInventory inventory) {
        if (event == null) return new StepOwners(null, null);
        if (isStandaloneMon(event.getAlertType())) return new StepOwners(event.getTeamId(), null);
        com.sitemonitor.model.CertificateInventory inv = isStandaloneEvent(event) ? null : inventory;
        Long invTeamId = inv != null ? inv.getTeamId() : null;
        Long sy = event.getTeamId() != null ? event.getTeamId() : invTeamId;
        Long ug = inv != null && includeInventoryUgTeam(event, invTeamId) ? inv.getUgTeamId() : null;
        return new StepOwners(sy, ug);
    }

    /** Adım e-postasının ve webhook'unun başındaki açıklama — "neden bana geldi?" sorusunun cevabı. */
    static String escalationStepNote(int delayMinutes) {
        return "ESKALASYON ADIMI: Bu alarm " + delayMinutes + " dakikadır kimse tarafından onaylanmadı (sahiplenilmedi). "
                + "Eskalasyon kişisi olarak bilgilendiriliyorsunuz — alarmı inceleyip onaylayın ya da sorumlu ekiple "
                + "iletişime geçin.";
    }

    /** Adım konusu: "[ESKALASYON · 30 dk onaysız] [Site Monitor] KRİTİK · ad · tür". */
    static String escalationStepSubject(int delayMinutes, String level, String alertType,
                                        Map<String, Object> ctx, String domain) {
        return "[ESKALASYON · " + delayMinutes + " dk onaysız] [Site Monitor] " + levelWordTr(level) + " · "
                + subjectDisplayName(ctx, domain) + " · " + resolvedTypeLabel(alertType);
    }

    /**
     * Zamana bağlı eskalasyon adımını TEK kişiye gönderir — kişinin kendi kanalları: e-posta ve (varsa) Teams/Slack
     * webhook'u. Takım adresi, 7/24 (NOC) ve kişi push'u bu adımın parçası DEĞİLDİR (onlar alarm açılışında
     * bilgilendirildi). İzlemede "E-posta" kanalı kapalıysa e-posta atlanır, webhook gider (anlık yolla aynı kural).
     * Sonuç tek bir {@link #TRIGGER_ESCALATION_STEP} satırıyla bildirim günlüğüne yazılır. İstisna yaymaz.
     *
     * @return e-posta durumu (SENT / FAILED… / SKIPPED…)
     */
    public String sendEscalationStep(AlertEvent event, EscalationContact contact, int delayMinutes,
                                     Long syTeamId, Long ugTeamId) {
        if (event == null || contact == null) return "SKIPPED";
        String domain = event.getDomain();
        String type = event.getAlertType();
        String level = event.getAlertLevel();
        Map<String, Object> snapshot = deserializeContext(event.getContextJson());
        boolean mailDisabled = snapshot != null && Boolean.TRUE.equals(snapshot.get("mail_disabled"));
        Map<String, Object> ctx;
        try {
            if (isDomainMon(type)) ctx = reconstructDomainContext(domain);
            else if (MONITORING_ALERT_TYPES.contains(type)) ctx = snapshot;
            else ctx = domain == null ? null : latestCheckRepo.findById(domain).map(this::latestToCertContext).orElse(null);
        } catch (Exception e) {
            ctx = snapshot;   // zenginleştirme düşerse adım alarm anı bağlamıyla yine gider
        }
        Map<String, Object> mailCtx = new LinkedHashMap<>();
        if (ctx != null) mailCtx.putAll(ctx);
        try {
            String teamNames = collectTeamNames(syTeamId, ugTeamId, event.getNotificationGroupId());
            if (teamNames != null && !teamNames.isBlank()) mailCtx.putIfAbsent("team_name", teamNames);
        } catch (Exception ignore) { /* takım adı yalnız etiket */ }
        if (event.getCreatedAt() != null) mailCtx.putIfAbsent("first_alert_at", event.getCreatedAt());
        if (event.getId() != null) mailCtx.put("alert_event_id", event.getId());
        Integer freshDays = toInt(mailCtx.get("days_remaining"));
        Integer days = freshDays != null ? freshDays : event.getDaysRemaining();

        String subject = escalationStepSubject(delayMinutes, level, type, mailCtx, domain);
        String body = event.getMessage() != null && !event.getMessage().isBlank()
                ? event.getMessage() : buildMessage(domain, type, level, days);
        String message = escalationStepNote(delayMinutes) + "\n\n" + body;
        // Runbook notu (2026-10-01) — anlık yolla (sendCombinedAlert) aynı kural: rehber varsa e-posta ve webhook
        // sonuna "Ne yapılmalı"; yoksa ikisi de bugünküyle aynı. Adım başına tek sorgu.
        String runbook = runbookNotes != null ? runbookNotes.plainGuide(type, domain, mailCtx) : null;
        mailCtx = withRunbook(mailCtx, runbook);
        String webhookMessage = com.sitemonitor.service.mail.RunbookNote.webhookMessage(message, runbook);

        String email = contact.getEmail() != null ? contact.getEmail().trim() : "";
        String emailStatus;
        String htmlBody = message;
        if (mailDisabled) {
            emailStatus = "SKIPPED: e-posta kanalı kapalı";
        } else if (email.isEmpty()) {
            emailStatus = "SKIPPED: alıcı yok";
        } else {
            try {
                htmlBody = emailService.buildAlertEmailHtml(subject, message, domain, level, type, days, mailCtx);
            } catch (Exception ignore) { /* günlük gövdesi düz metin kalır */ }
            try {
                emailStatus = emailService.sendAlert(new String[]{email}, subject, message, domain, level, type, days, mailCtx);
            } catch (Exception e) {
                emailStatus = "FAILED: " + e.getMessage();
            }
        }
        String webhookStatus = "SKIPPED";
        if (contact.getWebhookUrl() != null && !contact.getWebhookUrl().isBlank()) {
            try {
                webhookService.send(contact.getWebhookType(), contact.getWebhookUrl(), subject, webhookMessage, level);
                webhookStatus = "SENT";
            } catch (Exception e) {
                webhookStatus = "FAILED: " + e.getMessage();
            }
        }
        saveLog(event.getId(), contact, subject, htmlBody, emailStatus, webhookStatus, TRIGGER_ESCALATION_STEP);
        log.warn("⏫ Eskalasyon adımı: olay #{} {} [{}] {} → kişi #{} ({} dk onaysız) e-posta={} webhook={}",
                event.getId(), domain, type, level, contact.getId(), delayMinutes, emailStatus, webhookStatus);
        return emailStatus;
    }

    /**
     * Atlanan adımın İZİ (CLAUDE.md: gönderilmeyen bildirim nedenini {@code notification_logs}'ta söyler) —
     * {@link #TRIGGER_ESCALATION_STEP} tetikli, {@code SKIPPED: <neden>} durumlu tek satır. İstisna yaymaz.
     */
    public void recordEscalationStepSkipped(AlertEvent event, EscalationContact contact, int delayMinutes, String reason) {
        if (event == null || event.getId() == null || contact == null) return;
        String why = reason == null || reason.isBlank() ? "bilinmeyen neden" : reason;
        saveLog(event.getId(), contact, "",
                "Eskalasyon adımı (" + delayMinutes + " dk) gönderilmedi — " + why,
                "SKIPPED: " + why, "SKIPPED", TRIGGER_ESCALATION_STEP);
    }

    private List<Map<String, String>> sendCombinedAlert(
                                                          Long syTeamId, Long ugTeamId,
                                                          List<EscalationContact> contacts,
                                                          String domain, String level,
                                                          String alertType, String message,
                                                          String subjectPrefix,
                                                          Long alertEventId, String trigger,
                                                          Integer daysRemaining,
                                                          Map<String, Object> certContext) {
        // INITIAL/ESCALATION/DAILY_REALERT yolları hariç tutmasız — mevcut imza korunur.
        return sendCombinedAlert(syTeamId, ugTeamId, contacts, domain, level, alertType, message,
                subjectPrefix, alertEventId, trigger, daysRemaining, certContext, Set.of(), Set.of());
    }

    /**
     * Alarm e-postasının bağlamı + runbook notu. Not yoksa {@code ctx}'in KENDİSİ döner (kopya bile yok → şablon
     * bugünküyle aynı girdiyi görür); varsa kopyaya {@link com.sitemonitor.service.mail.RunbookNote#CTX_KEY} eklenir
     * (e-posta tavanına kırpılmış). Çağıranın haritası değişmez — push/NOC ona bakmaya devam eder.
     */
    static Map<String, Object> withRunbook(Map<String, Object> ctx, String plainGuide) {
        if (plainGuide == null || plainGuide.isBlank()) return ctx;
        Map<String, Object> out = new LinkedHashMap<>();
        if (ctx != null) out.putAll(ctx);
        out.put(com.sitemonitor.service.mail.RunbookNote.CTX_KEY,
                com.sitemonitor.service.mail.RunbookNote.truncate(plainGuide, com.sitemonitor.service.mail.RunbookNote.EMAIL_MAX));
        return out;
    }

    /** E-posta bağlamı anahtarı: manuel sertifikanın kaynak satırı ({@code EmailTemplateBuilder} "Sertifika Kaynağı" kartı). */
    public static final String CTX_MANUAL_SOURCE = "inv_manual_source";

    /** Manuel sertifika sürümleri — isteğe bağlı (dilimli test bağlamında yok → kaynak satırı eklenmez). */
    @Autowired(required = false)
    private com.sitemonitor.repository.ManualCertificateVersionRepository manualVersionRepo;

    /**
     * "Kaynak: Manuel yükleme (sürüm N, dosya …, yükleyen …, tarih …) — yenilemek için yeni sürümü yükleyin".
     * Geçerli sürüm okunamazsa sürüm ayrıntısı olmadan yazılır.
     */
    String manualSourceLine(com.sitemonitor.model.CertificateInventory inv) {
        com.sitemonitor.model.ManualCertificateVersion v = null;
        if (manualVersionRepo != null && inv.getId() != null) {
            try {
                v = manualVersionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(inv.getId()).orElse(null);
            } catch (Exception e) {
                log.debug("Manuel sertifika sürümü okunamadı ({}): {}", inv.getDomain(), e.toString());
            }
        }
        return manualSourceLine(v);
    }

    static String manualSourceLine(com.sitemonitor.model.ManualCertificateVersion v) {
        StringBuilder sb = new StringBuilder("Kaynak: Manuel yükleme");
        if (v != null) {
            List<String> parts = new ArrayList<>(4);
            if (v.getVersion() != null) parts.add("sürüm " + v.getVersion());
            if (v.getFileName() != null && !v.getFileName().isBlank()) parts.add("dosya " + v.getFileName());
            String who = v.getUploadedByName() != null && !v.getUploadedByName().isBlank()
                    ? v.getUploadedByName() : v.getUploadedBy();
            if (who != null && !who.isBlank()) parts.add("yükleyen " + who);
            if (v.getUploadedAt() != null && v.getUploadedAt().length() >= 10) {
                String d = v.getUploadedAt();
                parts.add("tarih " + d.substring(8, 10) + "." + d.substring(5, 7) + "." + d.substring(0, 4));
            }
            if (!parts.isEmpty()) sb.append(" (").append(String.join(", ", parts)).append(')');
        }
        return sb.append(" — yenilemek için yeni sürümü yükleyin").toString();
    }

    /** Subject'te görünen hedef adı: monitör adı > şema-soyulmuş adres. Çıplak http(s):// subject'e girmez. */
    static String subjectDisplayName(Map<String, Object> ctx, String domain) {
        if (ctx != null) {
            Object n = ctx.get("monitor_name");
            if (n != null && !String.valueOf(n).isBlank() && !"null".equals(String.valueOf(n))) {
                return String.valueOf(n).trim();
            }
        }
        if (domain == null) return "";
        return domain.replaceFirst("(?i)^https?://", "").trim();
    }

    private List<Map<String, String>> sendCombinedAlert(
                                                          Long syTeamId, Long ugTeamId,
                                                          List<EscalationContact> contacts,
                                                          String domain, String level,
                                                          String alertType, String message,
                                                          String subjectPrefix,
                                                          Long alertEventId, String trigger,
                                                          Integer daysRemaining,
                                                          Map<String, Object> certContext,
                                                          Set<String> excludeEmails,
                                                          Set<String> excludeUsernames) {
        // 0. SAHİPSİZ KAYIT KAPISI (ürün kararı 2026-09-28): tüm sahiplik çözümlemesinden SONRA (çağıran: envanter SY/UG,
        // izleme damgası, envanter türevli Port/DNS için alan adı → envanter) hiçbir takım yoksa alarm HİÇBİR kanaldan
        // bildirim üretmez — e-posta, kontak webhook'u, kişi push'u ve 7/24 (NOC) dahil. Takım zorunlu olduğundan bu bir
        // veri anomalisidir; savunma amaçlı: eskiden bu durumda TÜM takımların kontaklarına gidiliyordu. Olay kaydı ve
        // çağıranın damgaları (lastReAlertAt / notifiedContacts=[]) aynen sürer — yalnız gönderim yok, sessiz de değil.
        if (syTeamId == null && ugTeamId == null) {
            log.warn("Sahipsiz kayıt — bildirim gönderilmedi: olay={} alan={} tür={} seviye={} tetik={} "
                    + "(SY/UG takımı yok; e-posta, webhook, push ve 7/24 atlandı)", alertEventId, domain, alertType, level, trigger);
            // İZ (2026-09-30): sessiz karar da günlüğe girer — alarm penceresi "takım yok" nedenini gösterir.
            if (alertEventId != null) {
                saveLog(alertEventId, "-", "", "", message != null ? message : "", STATUS_NO_TEAM, "SKIPPED", trigger);
                try {
                    alertEventRepo.findById(alertEventId).ifPresent(e -> userPushService.recordSuppressed(e, PUSH_SKIPPED_NO_TEAM));
                } catch (Exception e) {
                    log.warn("Sahipsiz kayıt push kararı yazılamadı (olay {}): {}", alertEventId, e.getMessage());
                }
            }
            return List.of();
        }
        // 0.2. SİSTEM BAKIMI (2026-10-02, kullanıcı kararı) — "Bildirimler bakım boyunca sussun" açık bakım AKTİFKEN hiçbir
        // kanal çalışmaz (e-posta, kontak webhook'u, kişi push'u, 7/24 — NOC çağrısı aşağıda olduğu için o da atlanır);
        // kontroller ve alarm kayıtları sürer. İz: SYSTEM_MAINTENANCE / "SKIPPED: sistem bakımı" + push kararı + bakımın
        // telafi listesi (açılış susturulduysa bakım bitince INITIAL bir kez gider). Elle gönderim (MANUAL) susmaz.
        // Bakım yoksa / anahtar kapalıysa bu dal hiç girilmez ve akış bayt bayt bugünküdür.
        if (systemMaintenanceMuted(trigger)) {
            recordSystemMaintenanceSuppression(alertEventId, syTeamId, ugTeamId, domain, level, alertType, trigger, message);
            return List.of();
        }
        // 1. TO listesi: takım email'leri + kontaklar (dedup). excludeEmails (lowercase) — manuel
        // re-notify onay pop-up'ında kullanıcının çıkardığı adresler; takım e-postaları burada
        // çözüldüğünden filtre de burada uygulanır (kontaklar reNotify'da zaten filtrelenmiş gelir).
        // K5 damgasi: alarm ACILIRKEN yazilan grup. Canli monitor degeri DEGIL damga okunur --
        // aksi halde alarm surerken grup degisirse ilk bildirim bir gruba, cozum baskasina giderdi.
        // Ayni okuma asagidaki ctx zenginlestirmesini de besler (eskiden ayri bir findById vardi).
        AlertEvent stampEvent = alertEventId != null
                ? alertEventRepo.findById(alertEventId).orElse(null) : null;
        Long stampedGroupId = stampEvent != null ? stampEvent.getNotificationGroupId() : null;

        // 7/24 İzleme Ekibi: takım e-postasının atlanıp atlanmayacağından BAĞIMSIZ (aşağıdaki skipMail erken
        // dönüşünden ÖNCE). Kurallar/tekilleştirme/bakım NocNotificationService'te; istisna buraya taşmaz.
        // 7/24 ekibi takım sessiz saatinden ETKİLENMEZ (kendi kuralları var; takımın penceresi onun değil).
        notifyNocOpen(stampEvent, syTeamId, domain, level, alertType, trigger, certContext);

        // 0.5. SESSİZ SAAT (2026-10-01, onaylı öneri 15) — opt-in. Pencere tanımlı değilse (servis yok / takımda ayar yok /
        // pencere dışı / KRİTİK / elle gönderim) harita BOŞ döner ve aşağıdaki akış bayt bayt bugünküdür (karar bellek-içi
        // önbellekten — ek sorgu yok). Doluysa: ertelenen takımın alıcıları (takım adresi / grup, o takımın kişileri, push)
        // bu bildirimi ŞİMDİ almaz; alarm takımın özetine yazılır ve günlüğe QUIET_HOURS satırı düşer (sessiz karar yok).
        Map<Long, QuietHours.Occurrence> quietDeferred = quietDeferrals(syTeamId, ugTeamId, level, trigger, alertEventId);
        Long mailSy = syTeamId, mailUg = ugTeamId;
        List<EscalationContact> sendContacts = contacts;
        boolean pushQuiet = false;
        if (!quietDeferred.isEmpty()) {
            recordQuietDeferrals(quietDeferred, alertEventId, syTeamId, stampedGroupId, contacts, level, trigger, message);
            Long pushTeam = stampEvent != null && stampEvent.getTeamId() != null ? stampEvent.getTeamId() : syTeamId;
            pushQuiet = pushTeam != null && quietDeferred.containsKey(pushTeam);
            boolean allDeferred = (syTeamId == null || quietDeferred.containsKey(syTeamId))
                    && (ugTeamId == null || quietDeferred.containsKey(ugTeamId));
            if (allDeferred) {
                log.info("Sessiz saat — bildirim özete ertelendi: olay={} alan={} seviye={} tetik={} takım(lar)={}",
                        alertEventId, domain, level, trigger, quietDeferred.keySet());
                // Push kanalı da kararını kendi günlüğüne yazar (SKIPPED_TEAM_QUIET, doğru tetik/dedupe ile).
                triggerUserPush(alertEventId, trigger, syTeamId, quietCtx(certContext), excludeUsernames);
                return List.of();
            }
            // Kısmi (SY/UG'den yalnız biri pencerede): ertelenen takımın adresleri ve kişileri düşer, diğer takım bugünkü gibi alır.
            if (syTeamId != null && quietDeferred.containsKey(syTeamId)) mailSy = null;
            if (ugTeamId != null && quietDeferred.containsKey(ugTeamId)) mailUg = null;
            Set<Long> deferredTeams = quietDeferred.keySet();
            sendContacts = contacts.stream()
                    .filter(c -> c.getTeamId() == null || !deferredTeams.contains(c.getTeamId()))
                    .toList();
        }

        List<String> teamEmails = collectTeamEmails(mailSy, mailUg, stampedGroupId);
        Set<String> seen = new HashSet<>();
        List<String> allEmails = new ArrayList<>();
        for (String e : teamEmails) {
            if (excludeEmails.contains(e.trim().toLowerCase())) continue;
            if (seen.add(e.toLowerCase())) allEmails.add(e);
        }
        for (EscalationContact c : sendContacts) {
            if (c.getEmail() != null && !c.getEmail().isBlank()
                    && !excludeEmails.contains(c.getEmail().trim().toLowerCase())
                    && seen.add(c.getEmail().trim().toLowerCase()))
                allEmails.add(c.getEmail().trim());
        }
        // 1.1 NÖBETÇİ (2026-10-11): takım "nöbetçiye de bildir" seçtiyse YÜKSEK / KRİTİK alarm o anki nöbetçiye de gider.
        // Sessiz saatte ertelenen takımın (mailSy/mailUg null) nöbetçisi de ertelenir. Elle gönderimde hariç tutulan adres atlanır.
        List<com.sitemonitor.service.oncall.OnCallService.OnCallPerson> oncall = oncallFor(mailSy, mailUg, level);
        List<String> oncallMailed = new ArrayList<>();
        for (var p : oncall) {
            if (p.email() == null || p.email().isBlank()) continue;
            String e = p.email().trim();
            if (!excludeEmails.contains(e.toLowerCase()) && seen.add(e.toLowerCase())) {
                allEmails.add(e);
                oncallMailed.add(e);
            }
        }
        // İzlemenin "E-posta" kanalı KAPALI mı (notifyEmail=false → mailCtx damgası). Bayrak
        // bugüne kadar hiçbir yerde okunmuyordu; kutu süstü, kapatmak maili durdurmuyordu.
        boolean mailDisabled = certContext != null && Boolean.TRUE.equals(certContext.get("mail_disabled"));
        boolean skipMail = mailDisabled || allEmails.isEmpty();
        boolean anyContactWebhook = sendContacts.stream()
                .anyMatch(c -> c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank());
        if (skipMail) {
            // KANAL BAĞIMSIZLIĞI: mailin atlanması webhook'u DÜŞÜRMEZ.
            //
            // Bu yorum burada zaten yazılıydı ama YARIM uygulanmıştı: dal yalnız triggerUserPush
            // (KİŞİ push'u) çağırıp return ediyordu, aşağıdaki KONTAK webhook döngüsüne (Teams/Slack)
            // hiç ulaşılmıyordu. İki gerçek senaryoyu sessizce öldürüyordu: (a) takımın e-postası
            // tanımsız + kontaklar webhook-only → alarm hiçbir kanaldan gitmiyor; (b) kullanıcı
            // monitörde yalnız "E-posta"yı kapatıyor (notifyWebhook açık) → Teams bildirimi de kesiliyor.
            //
            // Artık yalnız mail adımı atlanır; kontak webhook'u olan hiçbir alarm düşmez.
            if (mailDisabled) log.info("E-posta kanalı kapalı ({} [{}]) — yalnız webhook/push", domain, level);
            else log.warn("E-posta alıcısı yok ({} [{}]) — yalnız webhook/push", domain, level);
            // 2026-09-30: erken dönüş KALDIRILDI — akış aşağıda sürer ve atlanan e-posta da günlüğe yazılır
            // ("SKIPPED: alıcı yok" / "SKIPPED: e-posta kanalı kapalı"). Eskiden kontak webhook'u yoksa burada
            // dönülüyor, hiçbir satır kalmıyor, alarm penceresi "0 bildirim" gösteriyordu.
        }

        // 1.5. ctx zenginleştirme — şablonun timeline/takım satırları için (mevcut anahtarlar EZİLMEZ).
        // realert_count: sayaç call-site'ta gönderim SONRASI artırıldığından, bu mailin numarası
        // DAILY_REALERT tetiklemesinde saklanan değerin +1'idir.
        Map<String, Object> enrichedCtx = new LinkedHashMap<>();
        if (certContext != null) enrichedCtx.putAll(certContext);
        String teamNames = collectTeamNames(mailSy, mailUg, stampedGroupId);
        if (teamNames != null && !teamNames.isBlank()) enrichedCtx.putIfAbsent("team_name", teamNames);
        if (stampEvent != null) {
            if (stampEvent.getCreatedAt() != null)
                enrichedCtx.putIfAbsent("first_alert_at", stampEvent.getCreatedAt());
            int shown = (stampEvent.getRealertCount() == null ? 0 : stampEvent.getRealertCount())
                    + ("DAILY_REALERT".equals(trigger) ? 1 : 0);
            if (shown > 0) enrichedCtx.putIfAbsent("realert_count", shown);
        }
        // Olay kimliği: e-postadaki "Olay detayını görüntüle / Olaya yorum yap" derin linklerini besler.
        // put (putIfAbsent DEĞİL): saklanmış eski bir anlık görüntüdeki bayat kimlik canlı olayı ezmesin.
        if (alertEventId != null) enrichedCtx.put("alert_event_id", alertEventId);
        // Envanter bağlamı — YALNIZ sertifika alarmlarında. Sertifikayı kimin nasıl yenileyeceğini
        // belirleyen operasyonel bayraklar (Netscaler/WAF/sunucuda değiştirilecek...) ve takımın kendi
        // yazdığı değişiklik süreci maile taşınır; alarmı alan kişi envanteri açmadan ne yapacağını görür.
        // Ek sorgu kabul edildi: alarm maili düşük hacimlidir ve findByDomain indeksli tekil okumadır.
        if (EmailTemplateBuilder.isCert(alertType) && domain != null) {
            inventoryRepo.findByDomain(domain).ifPresent(inv -> {
                List<String> ops = CertificateInventoryOps.enabledLabels(inv);
                if (!ops.isEmpty()) enrichedCtx.putIfAbsent("inv_ops", ops);
                String desc = inv.getChangeDescription();
                if (desc != null && !desc.isBlank()) enrichedCtx.putIfAbsent("inv_change_desc", desc);
                // Sorumlu Ekipler — "bu sertifikayı kim yenileyecek". Aynı envanter okumasına
                // biniyor, EK SORGU YOK. Bu tek nokta ilk uyarı / escalation / günlük re-alert /
                // yeniden-gönderim yollarının DÖRDÜNÜ birden besler: hepsi sendCombinedAlert'ten
                // geçer ve içerik her gönderimde envanterden YENİDEN okunur (bayat kopya yok).
                var invContacts = CertificateInventoryContacts.filled(inv);
                if (!invContacts.isEmpty()) enrichedCtx.putIfAbsent("inv_contacts", invContacts);
                // Elle yüklenen sertifika (2026-10-06): alıcı sertifikanın sunucudan değil DOSYADAN izlendiğini ve
                // yenilemenin yeni sürüm yüklemek olduğunu bilmeli. Ağ kaydında isManual() false → hiçbir şey eklenmez
                // (e-posta bugünküyle bayt bayt aynı).
                if (inv.isManual()) {
                    String src = manualSourceLine(inv);
                    if (src != null) enrichedCtx.putIfAbsent(CTX_MANUAL_SOURCE, src);
                }
            });
        }
        certContext = enrichedCtx;

        // 2. Subject — "[Site Monitor] SEVERITY · domain · özet" (executive format; EmailTemplateBuilder ile aynı severity etiketi).
        // D-2 (2026-09-29): TEK seviye sözlüğü levelWordTr — WARNING konu/rozet/gövde/push/arayüzde "UYARI" (eskiden
        // konu ve rozet "ORTA", gövde ve arayüz "UYARI" diyordu).
        String levelTr = levelWordTr(level);
        String typeTr = switch (alertType != null ? alertType : "") {
            case "REVOKED"          -> "İptal Edildi";
            case "MISMATCH"         -> "Dağıtım Eksik";
            case "CHAIN_BROKEN"     -> "Zincir Sorunu";
            case TYPE_HOSTNAME_MISMATCH -> "Alan Adı Uyuşmazlığı";
            case TYPE_UNTRUSTED_CA      -> "Güvenilmeyen Sertifika";
            case TYPE_ACCESSIBILITY -> "Erişim Kesintisi";
            case TYPE_PORT_DOWN     -> "Port Kesintisi";
            case TYPE_PORT_SLOW     -> "Port Yavaş Yanıt";
            case TYPE_DNS_FAILURE   -> "DNS Çözümleme Hatası";
            case TYPE_DNS_SLOW      -> "DNS Yavaş/Timeout";
            case TYPE_DNS_UNEXPECTED -> "DNS Beklenmeyen Değer";
            case TYPE_DNS_INCONSISTENT -> "DNS Tutarsızlığı";
            case TYPE_DNS_CHANGED   -> "DNS Değişikliği";
            case TYPE_KEYWORD       -> "İçerik Doğrulama";
            case TYPE_KEYWORD_SLOW  -> "İçerik Yavaş Yanıt";
            case TYPE_KEYWORD_SSL   -> "İçerik SSL Sorunu";
            case TYPE_KEYWORD_DOMAIN_EXPIRY -> "İçerik Domain Bitişi";
            case TYPE_PING_DOWN     -> "Erişilebilirlik (Ping)";
            case TYPE_PING_SLOW     -> "Ping Yavaş Yanıt";
            case TYPE_HTTP_DOWN     -> "HTTP/Website Erişilemez";
            case TYPE_HTTP_SSL      -> "SSL Sertifika Sorunu";
            case TYPE_HTTP_SLOW     -> "HTTP Yavaş Yanıt";
            case TYPE_PAGE_DOWN     -> "Sayfa Yüklenemiyor";
            case TYPE_PAGE_INTEGRITY -> "Sayfa Bütünlüğü Sorunu";
            case TYPE_SCRIPTED_FAIL -> "Sentetik Test Başarısız";
            case TYPE_SCRIPTED_SLOW -> "Sentetik Yavaş Koşum";
            case TYPE_PAGESPEED_DOWN -> "Sayfa Hızı Ölçülemiyor";
            case TYPE_PAGESPEED_SLOW -> "Sayfa Hızı Eşiği Aşıldı";
            case TYPE_DOMAIN_EXPIRY -> "Domain Süre Bitişi";
            case TYPE_DOMAINMON_EXPIRY  -> "Alan Adı Süre Bitişi";
            case TYPE_DOMAINMON_UNKNOWN -> "Alan Adı Veri Yok";
            case TYPE_DOMAINMON_STATUS  -> "Alan Adı Durum Kodu";
            case TYPE_DOMAINMON_CHANGED -> "Alan Adı Değişikliği";
            // Bu iki tip sonradan eklenmiş ve switch güncellenmemişti: default dalına düşüp
            // konuya "Sertifika Süre Bitişi" yazıyorlardı. Mail GÖVDESİ ise doğruydu
            // (EmailTemplateBuilder.heroLabel), yani tek mailin konusu ile başlığı çelişiyordu.
            case TYPE_DOMAINMON_TRANSFER_LOCK -> "Alan Adı Transfer Kilidi";
            case TYPE_DOMAINMON_BLACKLIST     -> "Alan Adı Kara Liste";
            default                 -> daysRemaining != null ? "Sertifika Süre Bitişi (" + daysRemaining + " gün kaldı)" : "Sertifika Süre Bitişi";
        };
        // Konu için doğal-dil özet (typeTr'e göre daha okunur); expiry tiplerinde gün ifadesi.
        String summaryTr = switch (alertType != null ? alertType : "") {
            case TYPE_DOMAINMON_EXPIRY, TYPE_DOMAIN_EXPIRY -> daysRemaining != null ? "Alan adı " + daysRemaining + " gün içinde doluyor" : "Alan adı süre bitişi";
            case TYPE_DOMAINMON_TRANSFER_LOCK -> "Alan adı transfer kilidi kapalı";
            case TYPE_DOMAINMON_BLACKLIST     -> "Alan adı kara listede";
            case TYPE_PAGE_DOWN     -> "Sayfa yüklenemiyor";
            case TYPE_PAGE_INTEGRITY -> "Sayfada kırık kaynak / mixed content";
            case TYPE_SCRIPTED_FAIL -> "Sentetik test (k6) başarısız";
            case TYPE_SCRIPTED_SLOW -> "Sentetik test (k6) yavaş";
            case TYPE_PAGESPEED_DOWN -> "Sayfa hızı ölçülemiyor";
            case TYPE_PAGESPEED_SLOW -> "Sayfa performans eşiğini aştı";
            case TYPE_DOMAINMON_UNKNOWN -> "Alan adı kayıt verisi alınamadı";
            case TYPE_DOMAINMON_STATUS  -> "Alan adı durum kodu uyarısı";
            case TYPE_DOMAINMON_CHANGED -> "Alan adı kaydı değişti";
            case TYPE_ACCESSIBILITY -> "Erişim kesintisi";
            case TYPE_PORT_DOWN     -> "Port kesintisi";
            case TYPE_HTTP_DOWN     -> "HTTP/Website erişilemez";
            default -> (alertType == null || alertType.isBlank() || "REVOKED".equals(alertType)
                        || "MISMATCH".equals(alertType) || "CHAIN_BROKEN".equals(alertType))
                    ? (daysRemaining != null ? "Sertifika " + daysRemaining + " gün içinde doluyor" : "Sertifika süre bitişi")
                    : typeTr;
        };
        // Süre-bitişi ailesinde severity yerine kalan gün öne çıkar: "15 GÜN KALDI" / "ACİL 2 GÜN KALDI".
        String daysSeg = (daysRemaining == null || !isDurationAlert(alertType)) ? levelTr
                : (daysRemaining <= 3 ? "ACİL " + daysRemaining + " GÜN KALDI" : daysRemaining + " GÜN KALDI");
        // Subject standardı: [Site Monitor] SEVERITY · monitör ADI · kısa sorun — çıplak URL subject'e
        // girmez (ctx.monitor_name; yoksa domain'in şema-soyulmuş hali). AlertEvent.domain (yönlendirme/
        // dedupe anahtarı) DEĞİŞMEZ; bu yalnız görünen metin.
        String subject = subjectPrefix + "[Site Monitor] " + daysSeg + " · "
                + subjectDisplayName(certContext, domain) + " · " + summaryTr;

        // 2.5. Runbook notu (2026-10-01): hedefin "Rehber & Notlar" rehberi varsa e-postanın ve Teams/Slack mesajının
        // SONUNA "Ne yapılmalı" eklenir. Gönderim başına TEK sorgu — sonuç aşağıdaki tüm e-posta/webhook alıcılarında
        // yeniden kullanılır. Rehber yoksa (yaygın durum) mailCtx == certContext ve webhookMessage == message: çıktı
        // bayt bayt bugünküyle aynı. Kişi push'u ve 7/24 (NOC) bu notu ALMAZ (kendi uzunluk sözleşmeleri var).
        String runbook = runbookNotes != null ? runbookNotes.plainGuide(alertType, domain, certContext) : null;
        Map<String, Object> mailCtx = withRunbook(certContext, runbook);
        String webhookMessage = com.sitemonitor.service.mail.RunbookNote.webhookMessage(message, runbook);

        // 3. Tek email — tüm alıcılara. skipMail ise gövde YİNE kurulur: webhook satırlarının
        // saveLog'u ve Bildirim Geçmişi önizlemesi aynı gövdeyi kullanıyor.
        String[] toArr     = allEmails.toArray(new String[0]);
        String htmlBody    = emailService.buildAlertEmailHtml(
                subject, message, domain, level, alertType, daysRemaining, mailCtx);
        String emailStatus;
        if (skipMail) {
            // Pasif kullanıcı izi (2026-10-02): alıcı listesi pasif kişiler düştüğü için boşaldıysa neden "alıcı yok" değil
            // "pasif kullanıcı"dır (getContactsForLevel'in döndürdüğü liste düşen sayıyı taşır; pasif yoksa 0 → bugünkü yol).
            // Elle gönderimde kullanıcı onay penceresinde TÜM e-posta alıcılarını çıkardıysa (yalnız push / webhook — K13) neden
            // "alıcı yok" değil, bilinçli seçimdir (son inceleme 2026-10-22).
            emailStatus = mailDisabled ? "SKIPPED: e-posta kanalı kapalı"
                    : TRIGGER_MANUAL.equals(trigger) && !excludeEmails.isEmpty() && allEmails.isEmpty()
                        ? STATUS_SKIPPED_EMAIL_DESELECTED
                    : InactiveRecipientGuard.droppedCount(contacts) > 0 ? InactiveRecipientGuard.STATUS_SKIPPED
                    : "SKIPPED: alıcı yok";
            // İZ (2026-09-30): atlanan e-posta da tek satırla günlüğe girer — neden gitmediği ekranda okunur.
            // Takım adı adresten BAĞIMSIZ (collectTeamNames yalnız adresi olan takımı sayar; burada adres yok).
            String skipName = teamNames != null && !teamNames.isBlank() ? teamNames : ownerTeamNames(mailSy, mailUg);
            saveLog(alertEventId, skipName, String.join(", ", allEmails), subject, htmlBody, emailStatus, "SKIPPED", trigger);
        } else {
            emailStatus = emailService.sendAlert(
                    toArr, subject, message, domain, level, alertType, daysRemaining, mailCtx);
            // 4. Email log — tek kayıt (teamNames yukarıda ctx zenginleştirmesinde hesaplandı)
            saveLog(alertEventId, teamNames, String.join(", ", allEmails), subject, htmlBody, emailStatus, "SKIPPED", trigger);
            // L-E5 (2026-10-22): nöbetçi adresi yalnız posta GERÇEKTEN gittiyse (SENT / 421 kuyruğu) damgalanır — başarısız ya da
            // pasif süzgecinde atlanmış postanın nöbetçisi çözüm postasını (açılışı hiç almadığı alarm için) almasın.
            if (mailDelivered(emailStatus)) rememberOncallMailed(alertEventId, oncallMailed);
        }

        // 5. Webhook — kontaklara ayrı ayrı
        List<Map<String, String>> details = new ArrayList<>();
        // Aynı webhook adresine TEK mesaj (2026-09-28, D3): SY + UG birleşiminde iki takımın kişisi aynı Teams/Slack
        // kanalını gösterebilir. Çözüm (sendResolutionWebhooks) ve fırtına zaten adrese göre tekilleştiriyordu; açılış
        // iki kez gönderiyordu. Tekrar eden adres günlüğe "SKIPPED: aynı webhook" olarak yazılır (denetim izi kalır).
        Set<String> sentWebhookUrls = new HashSet<>();
        for (EscalationContact c : sendContacts) {
            String webhookStatus = "SKIPPED";
            if (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank()
                    && !sentWebhookUrls.add(c.getWebhookUrl().trim())) {
                webhookStatus = "SKIPPED: aynı webhook";
                saveLog(alertEventId, c, subject, htmlBody, "SKIPPED", webhookStatus, trigger);
            } else if (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank()) {
                try {
                    webhookService.send(c.getWebhookType(), c.getWebhookUrl(), subject, webhookMessage, level);
                    webhookStatus = "SENT";
                } catch (Exception e) {
                    webhookStatus = "FAILED: " + e.getMessage();
                }
                saveLog(alertEventId, c, subject, htmlBody, "SKIPPED", webhookStatus, trigger);
            }
            Map<String, String> d = new LinkedHashMap<>();
            d.put("name",           c.getName());
            d.put("email",          c.getEmail());
            d.put("role",           c.getRole());
            d.put("subject",        subject);
            d.put("email_status",   emailStatus);
            d.put("webhook_status", webhookStatus);
            details.add(d);
        }

        // Alıcı ADRESLERİ günlüğe yazılmaz — tam liste INFO seviyesinde 30 gün saklanıyordu ve
        // kubectl logs ile okunabiliyordu. Adresler zaten notification_log'da; burada sayı yeter.
        log.info("Combined alert: {} [{}] → alıcı={} | webhooks={} | olay={} | trigger={}",
                domain, level, allEmails.size(), sendContacts.size(), alertEventId, trigger);

        triggerUserPush(alertEventId, trigger, syTeamId, pushQuiet ? quietCtx(certContext) : certContext, excludeUsernames);
        // Nöbetçi listesi zaten yalnız sessiz saatte OLMAYAN takımlardan kurulur (oncallFor(mailSy, mailUg)): SY penceredeyken
        // UG'nin nöbetçisi e-postayı aldığı gibi push'u da alır (2026-10-12; eskiden SY'nin sessizliği ikisini de kesiyordu).
        pushOnCall(alertEventId, trigger, oncall, excludeUsernames);
        // Sessiz saat: pencere İÇİNDEKİ takım bu alarm için ertelenmeyen bir bildirim aldı (YÜKSEK/KRİTİK, elle gönderim) →
        // bekleyen özet kaydı geçersizleşir (özet tekrar etmez, çözüm de normal yoldan gider). Pencere dışı = sorgu yok.
        supersedeQuietItems(alertEventId, syTeamId, ugTeamId, quietDeferred.keySet());
        return details;
    }

    // ── Sessiz saat yardımcıları (2026-10-01, onaylı öneri 15) ──────────────────────────────────────────────────

    /**
     * Bu gönderimde ERTELENEN sahip takımlar → pencere oluşumu. Boş harita = bugünkü yol. Ertelenmez: servis yoksa,
     * olaysız tekil bildirimde (özete yazılacak olay yok), ertelenemez tetikte (MANUAL — elle gönderim bilinçli
     * eylemdir), takımın penceresi yoksa/dışındaysa ya da seviye ertelenemezse (KRİTİK asla). Hata = erteleme yok.
     */
    private Map<Long, QuietHours.Occurrence> quietDeferrals(Long syTeamId, Long ugTeamId, String level, String trigger,
                                                           Long alertEventId) {
        if (quietHours == null || alertEventId == null || trigger == null
                || !TeamQuietHoursService.DEFERRABLE_TRIGGERS.contains(trigger)) return Map.of();
        try {
            if (!quietHours.isConfigured(syTeamId) && !quietHours.isConfigured(ugTeamId)) return Map.of();
            Instant now = quietHours.now();
            Map<Long, QuietHours.Occurrence> out = new LinkedHashMap<>();
            for (Long team : new LinkedHashSet<>(Arrays.asList(syTeamId, ugTeamId))) {
                if (team == null) continue;
                QuietHours.Occurrence occ = quietHours.deferral(team, level, now);
                if (occ != null) out.put(team, occ);
            }
            return out;
        } catch (Exception e) {
            log.warn("Sessiz saat kararı verilemedi — bildirim normal gönderiliyor (olay {}): {}", alertEventId, e.toString());
            return Map.of();
        }
    }

    /**
     * Ertelemenin İZİ: takım başına özet kaydı ({@code quiet_digest_items}, pencere başına tek) + bildirim günlüğüne
     * {@code QUIET_HOURS / SKIPPED: sessiz saat (özete eklendi)} satırı — alıcı alanı ertelenen adresleri taşır. Her
     * ertelenen gönderim bir satır bırakır ("ertelenen her bildirim kayda geçer"). Hata bildirim hattını etkilemez.
     */
    private void recordQuietDeferrals(Map<Long, QuietHours.Occurrence> deferred, Long alertEventId, Long syTeamId,
                                      Long stampedGroupId, List<EscalationContact> contacts, String level,
                                      String trigger, String message) {
        Instant now = quietHours.now();
        for (Map.Entry<Long, QuietHours.Occurrence> d : deferred.entrySet()) {
            Long teamId = d.getKey();
            QuietHours.Occurrence occ = d.getValue();
            try {
                quietHours.recordDeferral(alertEventId, teamId, occ, level, trigger, now);
            } catch (Exception e) {
                log.warn("Sessiz saat özet kaydı yazılamadı (olay {} takım {}): {}", alertEventId, teamId, e.toString());
            }
            try {
                List<String> emails = new ArrayList<>();
                String[] teamName = {"-"};
                teamRepo.findById(teamId).ifPresent(team -> {
                    if (team.getName() != null && !team.getName().isBlank()) teamName[0] = team.getName().trim();
                    emails.addAll(teamRecipientEmails(teamId, syTeamId, stampedGroupId, team));
                });
                for (EscalationContact c : contacts) {
                    if (!teamId.equals(c.getTeamId()) || c.getEmail() == null || c.getEmail().isBlank()) continue;
                    String e = c.getEmail().trim();
                    if (emails.stream().noneMatch(x -> x.equalsIgnoreCase(e))) emails.add(e);
                }
                String note = quietTriggerLabel(trigger) + " sessiz saat nedeniyle ertelendi: " + teamName[0]
                        + " takımının penceresi " + occ.label() + " (Europe/Istanbul). Alarm, pencere bitiminde ("
                        + occ.endLabel() + ") gönderilecek sessiz saat özetine eklendi.";
                saveLog(alertEventId, teamName[0], String.join(", ", emails), "",
                        note + (message != null && !message.isBlank() ? "\n\n" + message : ""),
                        STATUS_QUIET_DEFERRED, "SKIPPED", TRIGGER_QUIET_HOURS);
            } catch (Exception e) {
                log.warn("Sessiz saat ertelemesi günlüğe yazılamadı (olay {} takım {}): {}", alertEventId, teamId, e.toString());
            }
        }
    }

    private static String quietTriggerLabel(String trigger) {
        return switch (trigger == null ? "" : trigger) {
            case "INITIAL" -> "İlk bildirim";
            case "ESCALATION" -> "Seviye artışı bildirimi";
            case "DAILY_REALERT" -> "Günlük hatırlatma";
            case TeamQuietHoursService.STEP_TRIGGER -> "Eskalasyon adımı";
            default -> "Bildirim";
        };
    }

    /** Push tetiğine giden bağlamın KOPYASI + sessiz saat işareti (çağıranın haritası değişmez). Fırtınanın bireysel push'u da kullanır. */
    static Map<String, Object> quietCtx(Map<String, Object> ctx) {
        Map<String, Object> m = ctx == null ? new LinkedHashMap<>() : new LinkedHashMap<>(ctx);
        m.put(CTX_QUIET_DEFERRED, true);
        return m;
    }

    /** Çözüm kararı: {@code folded} = açılışı ertelenmiş (çözüm özete katlanır); {@code supersede} = duymuş ama bir hatırlatması bekleyen. */
    record QuietFold(Set<Long> folded, Set<Long> supersede) {
        static final QuietFold NONE = new QuietFold(Set.of(), Set.of());
    }

    /** Sahip takımlardan biri pencere TANIMLAMIŞSA bekleyen özet kayıtlarını okur (tek sorgu); değilse sorgusuz NONE. */
    private QuietFold quietResolutionFold(AlertEvent event, Long syTeamId, Long ugTeamId) {
        if (quietHours == null || event == null || event.getId() == null) return QuietFold.NONE;
        try {
            if (!quietHours.isConfigured(syTeamId) && !quietHours.isConfigured(ugTeamId)) return QuietFold.NONE;
            Set<Long> owners = new HashSet<>();
            if (syTeamId != null) owners.add(syTeamId);
            if (ugTeamId != null) owners.add(ugTeamId);
            Set<Long> folded = new LinkedHashSet<>(), supersede = new LinkedHashSet<>();
            for (com.sitemonitor.model.QuietDigestItem i : quietHours.pendingForEvent(event.getId())) {
                if (!owners.contains(i.getTeamId())) continue;
                if (Boolean.TRUE.equals(i.getOpeningDeferred())) folded.add(i.getTeamId());
                else supersede.add(i.getTeamId());
            }
            supersede.removeAll(folded);
            return folded.isEmpty() && supersede.isEmpty() ? QuietFold.NONE : new QuietFold(folded, supersede);
        } catch (Exception e) {
            log.warn("Sessiz saat çözüm kararı verilemedi — çözüm normal gönderiliyor (olay {}): {}", event.getId(), e.toString());
            return QuietFold.NONE;
        }
    }

    /** Katlanan çözümün izi: takım başına {@code QUIET_HOURS / SKIPPED: sessiz saat (çözüm özete eklendi)} satırı. */
    private void recordQuietResolutionFold(AlertEvent event, Set<Long> teams, Long syTeamId) {
        for (Long teamId : teams) {
            try {
                List<String> emails = new ArrayList<>();
                String[] teamName = {"-"};
                teamRepo.findById(teamId).ifPresent(team -> {
                    if (team.getName() != null && !team.getName().isBlank()) teamName[0] = team.getName().trim();
                    emails.addAll(teamRecipientEmails(teamId, syTeamId, event.getNotificationGroupId(), team));
                });
                saveLog(event.getId(), teamName[0], String.join(", ", emails), "",
                        "Çözüm bildirimi ayrı gönderilmedi: alarmın açılışı " + teamName[0] + " takımının sessiz saatinde "
                                + "ertelenmişti ve özet henüz gitmedi. Alarm, sessiz saat özetinde \"çözüldü\" olarak yer alacak.",
                        STATUS_QUIET_RESOLUTION_FOLDED, "SKIPPED", TRIGGER_QUIET_HOURS);
            } catch (Exception e) {
                log.warn("Sessiz saat çözüm katlaması günlüğe yazılamadı (olay {} takım {}): {}", event.getId(), teamId, e.toString());
            }
        }
    }

    /** Pencere içindeki (ertelenmeyen) sahip takımların bekleyen özet kayıtlarını geçersizleştirir. */
    private void supersedeQuietItems(Long alertEventId, Long syTeamId, Long ugTeamId, Set<Long> deferredTeams) {
        if (quietHours == null || alertEventId == null) return;
        try {
            if (!quietHours.isConfigured(syTeamId) && !quietHours.isConfigured(ugTeamId)) return;
            Instant now = quietHours.now();
            List<Long> reached = new ArrayList<>();
            for (Long team : new LinkedHashSet<>(Arrays.asList(syTeamId, ugTeamId))) {
                if (team == null || deferredTeams.contains(team)) continue;
                if (quietHours.activeWindow(team, now) != null) reached.add(team);
            }
            if (!reached.isEmpty()) quietHours.supersede(alertEventId, reached, now);
        } catch (Exception e) {
            log.warn("Sessiz saat özet kaydı geçersizleştirilemedi (olay {}): {}", alertEventId, e.toString());
        }
    }

    /**
     * Eskalasyon adımı sessiz saat yüzünden pencere sonuna BEKLETİLDİ (2026-10-01) — iz satırı (adım başına, ilk
     * beklemede bir kez; adım işi tekrarlarda yazmaz). Adım pencere bitip özet gittikten sonra gecikmesiyle yeniden değerlendirilir.
     */
    public void recordQuietStepHold(AlertEvent event, EscalationContact contact, int delayMinutes, QuietHours.Occurrence occ) {
        if (event == null || event.getId() == null || contact == null || occ == null) return;
        saveLog(event.getId(), contact, "",
                "Eskalasyon adımı (" + delayMinutes + " dk) sessiz saat nedeniyle bekletiliyor: takımın penceresi "
                        + occ.label() + " (Europe/Istanbul). Alarm sessiz saat özetine eklendi; adım özetten sonra gecikmesiyle "
                        + "yeniden değerlendirilir.",
                STATUS_QUIET_DEFERRED, "SKIPPED", TRIGGER_QUIET_HOURS);
    }

    /**
     * Kişi-webhook (push) tetiği — K8: mail neyi gönderiyorsa webhook da.
     *
     * <p>Tetik mail hunisinde durduğu için bakım/toplu-kesinti bastırmaları kendiliğinden miras kalır
     * (bastırılan olay buraya hiç gelmez). Fırtına İSTİSNADIR (2026-10-03): fırtınaya bağlanan alarmın e-postası
     * fırtınada kalır ama push'u varsayılan olarak {@code pushStormMember} ile yine buradan, bireysel gider.
     * Mail SONUCUNDAN bağımsız: mail FAILED olsa da, hiç alıcı olmasa da, kanal kapalı olsa da koşar. İstisna yayılamaz — mail yolu bu
     * kanalın hiçbir arızasından etkilenmez.
     */
    /** 7/24 (NOC) açılış tetiği — hiçbir hatası takım alarmına yayılmaz. */
    private void notifyNocOpen(AlertEvent event, Long syTeamId, String domain, String level, String alertType,
                               String trigger, Map<String, Object> ctx) {
        if (nocNotifications == null) return;
        try {
            nocNotifications.onAlertDispatched(event, syTeamId, domain, level, alertType, trigger, ctx);
        } catch (Exception e) {
            log.warn("7/24 tetiği atlandı (takım alarmı etkilenmedi): {}", e.toString());
        }
    }

    /** 7/24 (NOC) çözüm tetiği — yalnız açılışı NOC'a gitmiş alarmda e-posta üretir (karar serviste). */
    private void notifyNocResolved(AlertEvent event) {
        if (nocNotifications == null) return;
        try {
            nocNotifications.onAlertResolved(event);
        } catch (Exception e) {
            log.warn("7/24 çözüm tetiği atlandı: {}", e.toString());
        }
    }

    /**
     * Bildirim nöbetçisi (2026-10-11): yalnız YÜKSEK / KRİTİK ve yalnız "nöbetçiye de bildir" açık takımlar. Çizelge
     * yoksa ya da servis yoksa boş — çağıran yollar bugünküyle aynı. Hata bildirimi durdurmaz (servis WARN yazar).
     */
    List<com.sitemonitor.service.oncall.OnCallService.OnCallPerson> oncallFor(Long syTeamId, Long ugTeamId, String level) {
        if (onCallService == null || (syTeamId == null && ugTeamId == null)) return List.of();
        String lv = level == null ? "" : level.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"HIGH".equals(lv) && !"CRITICAL".equals(lv)) return List.of();
        List<Long> teams = new ArrayList<>();
        if (syTeamId != null) teams.add(syTeamId);
        if (ugTeamId != null && !ugTeamId.equals(syTeamId)) teams.add(ugTeamId);
        try {
            return new ArrayList<>(onCallService.notifyTargets(teams).values());
        } catch (Exception e) {
            log.warn("Nöbetçi alıcısı eklenemedi (bildirim nöbetçisiz sürer): {}", e.toString());
            return List.of();
        }
    }

    /** E-posta gönderimi alıcıya ulaştı / kuyrukta (SENT, QUEUED_RETRY…) mı — FAILED / SKIPPED değil. */
    static boolean mailDelivered(String emailStatus) {
        return emailStatus != null && (emailStatus.equals("SENT") || emailStatus.startsWith("SENT ")
                || emailStatus.startsWith("QUEUED"));
    }

    /** Nöbetçi olarak e-posta alan adresleri alarma ekler (en çok 20, tekil) — çözüm e-postası bunlara gider. Hata bildirimi durdurmaz. */
    private void rememberOncallMailed(Long alertEventId, List<String> mailed) {
        if (alertEventId == null || mailed == null || mailed.isEmpty()) return;
        try {
            Set<String> keys = new HashSet<>();
            List<String> merged = new ArrayList<>();
            String prev = alertEventRepo.findOncallEmails(alertEventId);
            List<String> all = new ArrayList<>();
            if (prev != null && !prev.isBlank()) all.addAll(java.util.Arrays.asList(prev.split(",")));
            all.addAll(mailed);
            for (String e : all) {
                String v = e == null ? "" : e.trim();
                if (!v.isEmpty() && merged.size() < 20 && keys.add(v.toLowerCase(java.util.Locale.ROOT))) merged.add(v);
            }
            alertEventRepo.setOncallEmails(alertEventId, String.join(",", merged));
        } catch (Exception e) {
            log.warn("Nöbetçi alıcıları alarma yazılamadı (olay {}; çözüm e-postası nöbetçiye gitmeyebilir): {}", alertEventId, e.toString());
        }
    }

    /** Alarmı nöbetçi olarak almış adresler (varlıkta salt okunur sütun). */
    static List<String> oncallMailedFor(AlertEvent event) {
        String v = event == null ? null : event.getOncallEmails();
        if (v == null || v.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String e : v.split(",")) if (!e.isBlank()) out.add(e.trim());
        return out;
    }

    /** Nöbetçi push'u — kişi başına; takım push'unu zaten alan kişiye ikinci satır yazılmaz (UserPushService). */
    private void pushOnCall(Long alertEventId, String trigger,
                            List<com.sitemonitor.service.oncall.OnCallService.OnCallPerson> oncall, Set<String> excludeUsernames) {
        if (oncall == null || oncall.isEmpty()) return;
        Set<Long> done = new HashSet<>();
        for (var p : oncall) {
            if (p.userId() == null || !done.add(p.userId())) continue;
            try {
                userPushService.enqueueOnCall(alertEventId, trigger, p.teamId(), p.userId(), excludeUsernames);
            } catch (Exception e) {
                log.warn("Nöbetçi push'u atlandı (e-posta etkilenmedi): {}", e.toString());
            }
        }
    }

    private void triggerUserPush(Long alertEventId, String trigger, Long syTeamId,
                                 Map<String, Object> certContext, Set<String> excludeUsernames) {
        try {
            userPushService.enqueueAlert(alertEventId, trigger, syTeamId, certContext, excludeUsernames);
        } catch (Exception e) {
            log.warn("user-push tetiği atlandı (mail yolu etkilenmedi): {}", e.toString());
        }
    }

    private void saveLog(Long alertEventId, EscalationContact c,
                         String subject, String message,
                         String emailStatus, String webhookStatus, String trigger) {
        try {
            NotificationLog entry = new NotificationLog();
            entry.setAlertEventId(alertEventId);
            entry.setSentAt(now());
            entry.setRecipientName(c.getName());
            entry.setRecipientEmail(c.getEmail());
            entry.setRecipientRole(c.getRole());
            entry.setSubject(subject);
            entry.setMessage(message);
            entry.setEmailStatus(emailStatus);
            entry.setWebhookStatus(webhookStatus);
            entry.setTrigger(trigger);
            entry.setEmailFrom(emailService.getEmailFrom());
            notificationLogRepo.save(entry);
        } catch (Exception e) {
            log.warn("Bildirim logu kaydedilemedi: {}", e.getMessage());
        }
    }

    /** Bildirim günlüğü tetik adı: bireysel bildirim fırtınaya devredildi (satır, e-posta değil, KARARdır). */
    public static final String TRIGGER_STORM_SUPPRESSED = "STORM";
    /** Bildirim günlüğü e-posta durumu ön eki — fırtına devri. Ön yüz {@code SKIPPED:} ile başlayanı "atlandı" tonuyla çizer. */
    public static final String STATUS_STORM_PREFIX = "SKIPPED: fırtına #";
    /** Fırtına devri satırının son eki — push da fırtınaya devredildi ({@code site.monitor.storm.push-individual} KAPALI; 2026-10-02'ye kadarki metin). */
    public static final String STATUS_STORM_SUFFIX = " — bireysel bildirim yerine toplu fırtına bildirimi";
    /**
     * Fırtına devri satırının son eki — YALNIZ e-posta devredildi, push bireysel gitti (2026-10-03, varsayılan). Ön ek
     * ({@link #STATUS_STORM_PREFIX}) aynı kalır: fırtına durum ekranının "devredilen alarm" sayımı ve ön yüzün fırtına no
     * çözümlemesi ona bakar; ön yüz "(push tek tek)" işaretinden zaman çizelgesi metnini seçer.
     */
    public static final String STATUS_STORM_MAIL_ONLY_SUFFIX = " — bireysel e-posta yerine toplu fırtına e-postası (push tek tek)";
    public static final String STATUS_NO_TEAM = "SKIPPED: takım yok";
    /** Push karar satırı nedenleri (2026-09-30). */
    public static final String PUSH_SKIPPED_STORM = "SKIPPED_STORM";
    public static final String PUSH_SKIPPED_NO_TEAM = "SKIPPED_NO_TEAM";

    // ── Sessiz saatler (2026-10-01, onaylı öneri 15) ─────────────────────────────────────────────────────────
    /** Bildirim günlüğü tetiği: bildirim takımın sessiz saat özetine devredildi (satır e-posta değil, KARARdır). */
    public static final String TRIGGER_QUIET_HOURS = "QUIET_HOURS";
    /** Bildirim günlüğü tetiği: pencere sonunda giden sessiz saat özeti e-postası (alarm başına bir satır). */
    public static final String TRIGGER_QUIET_DIGEST = "QUIET_DIGEST";
    /** Ertelenen alarm bildiriminin durumu — ön yüz {@code SKIPPED:} ile başlayanı "atlandı" tonuyla çizer. */
    public static final String STATUS_QUIET_DEFERRED = "SKIPPED: sessiz saat (özete eklendi)";
    /** Açılışı ertelenmiş alarmın çözümü ayrı posta yerine özete katlandı. */
    public static final String STATUS_QUIET_RESOLUTION_FOLDED = "SKIPPED: sessiz saat (çözüm özete eklendi)";
    /** Push kararı: takımın sessiz saati — push da özete devredildi. */
    public static final String PUSH_SKIPPED_TEAM_QUIET = "SKIPPED_TEAM_QUIET";
    /** Push tetiğine giden bağlam işareti (ctx kopyasında; çağıranın haritası değişmez). */
    public static final String CTX_QUIET_DEFERRED = "quiet_deferred";

    /**
     * Fırtına devrinin izi (2026-09-30): olayın bildirim günlüğüne {@code STORM} tetikli, {@code SKIPPED: fırtına #N}
     * durumlu bir satır. Bu satır "e-posta gönderilmedi"nin nedenidir; alarm penceresinin zaman çizelgesi ve Bildirimler
     * bölümü buradan okur. Hata bildirim hattını etkilemez.
     *
     * <p>{@code pushIndividual} (2026-10-03, {@code site.monitor.storm.push-individual}; 2026-10-04 kullanıcı kararıyla varsayılan KAPALI): yalnız e-posta
     * devredildi — satır metni bunu söyler ({@link #STATUS_STORM_MAIL_ONLY_SUFFIX}) ve push karar satırı YAZILMAZ (push
     * çağıranda bireysel gider; teslimat satırı kendisi izdir). KAPALI: 2026-10-02'ye kadarki iz bayt bayt — eski metin ve
     * push kararı {@code SKIPPED_STORM}.
     */
    void recordStormSuppression(AlertEvent event, Long syTeamId, Long ugTeamId, boolean pushIndividual) {
        if (event == null || event.getId() == null) return;
        try {
            List<String> emails = collectTeamEmails(syTeamId, ugTeamId, event.getNotificationGroupId());
            String status = STATUS_STORM_PREFIX + (event.getStormId() != null ? event.getStormId() : "?")
                    + (pushIndividual ? STATUS_STORM_MAIL_ONLY_SUFFIX : STATUS_STORM_SUFFIX);
            saveLog(event.getId(), ownerTeamNames(syTeamId, ugTeamId),
                    String.join(", ", emails), "", event.getMessage() != null ? event.getMessage() : "",
                    status, "SKIPPED", TRIGGER_STORM_SUPPRESSED);
        } catch (Exception e) {
            log.warn("Fırtına devri günlüğe yazılamadı (olay {}): {}", event.getId(), e.getMessage());
        }
        if (pushIndividual) return;
        try {
            userPushService.recordSuppressed(event, PUSH_SKIPPED_STORM);
        } catch (Exception e) {
            log.warn("Fırtına devri push kararı yazılamadı (olay {}): {}", event.getId(), e.getMessage());
        }
    }

    /**
     * Push fırtınaya devredilmesin mi ({@link StormService#KEY_PUSH_INDIVIDUAL}, varsayılan KAPALI — 2026-10-04 kullanıcı
     * kararı: "push'un fırtınaya devredilmesi default olsun"). Ayar servisi yokken (elle kurulan eski testler) varsayılan geçerlidir.
     */
    boolean stormPushIndividual() {
        try {
            return appSettings != null && appSettings.getBoolean(StormService.KEY_PUSH_INDIVIDUAL, false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fırtına üyesinin BİREYSEL push'u (2026-10-03, kullanıcı kararı: "alarm fırtınası durumunda push üzerinden teker teker
     * bildirimleri sırasıyla bildirelim"). E-posta fırtınada kalır; push, bireysel hattın {@code sendCombinedAlert}'te
     * aldığı kararın AYNISIYLA kuyruğa girer: alıcı / seviye / kanal kapıları / hatırlatma ayarı / sistem bakımı / kişi başına
     * saatlik tavan ({@code RATE_LIMITED}) / tekilleştirme {@link UserPushService#enqueueAlert}'te; takımın sessiz saati
     * push'un takımı (olay damgası, yoksa SY) pencerede ve seviye ertelenebilirse işaretli bağlam kopyasıyla
     * ({@code SKIPPED_TEAM_QUIET}). Sessiz saat ÖZET kaydı yazılmaz — fırtına e-postası postayı zaten taşıyor. Hata alarm
     * hattına yayılmaz.
     */
    private void pushStormMember(AlertEvent event, String mailTrigger, Long teamFallback, Map<String, Object> ctx) {
        if (event == null || event.getId() == null) return;
        Long pushTeam = event.getTeamId() != null ? event.getTeamId() : teamFallback;
        boolean quiet = false;
        try {
            quiet = quietHours != null && quietHours.defersPush(pushTeam, event.getAlertLevel(), mailTrigger);
        } catch (Exception e) {
            log.warn("Sessiz saat push kararı verilemedi — push gönderiliyor (olay {}): {}", event.getId(), e.toString());
        }
        triggerUserPush(event.getId(), mailTrigger, teamFallback, quiet ? quietCtx(ctx) : ctx, Set.of());
    }

    /** Sahip takım adları — adresten BAĞIMSIZ (atlanan/devredilen bildirim satırının "alıcı" etiketi); yoksa "-". */
    private String ownerTeamNames(Long syTeamId, Long ugTeamId) {
        List<String> names = new ArrayList<>();
        for (Long teamId : List.of(syTeamId != null ? syTeamId : -1L, ugTeamId != null ? ugTeamId : -1L)) {
            if (teamId < 0) continue;
            try {
                teamRepo.findById(teamId).ifPresent(team -> {
                    String name = team.getName() != null ? team.getName().trim() : "";
                    if (!name.isBlank() && !names.contains(name)) names.add(name);
                });
            } catch (Exception ignore) { /* ad yalnız etiket — sorgu düşerse "-" */ }
        }
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    private void saveLog(Long alertEventId, String recipientName, String recipientEmail,
                         String subject, String message,
                         String emailStatus, String webhookStatus, String trigger) {
        try {
            NotificationLog entry = new NotificationLog();
            entry.setAlertEventId(alertEventId);
            entry.setSentAt(now());
            entry.setRecipientName(recipientName);
            entry.setRecipientEmail(recipientEmail);
            entry.setRecipientRole("COMBINED");
            entry.setSubject(subject);
            entry.setMessage(message);
            entry.setEmailStatus(emailStatus);
            entry.setWebhookStatus(webhookStatus);
            entry.setTrigger(trigger);
            entry.setEmailFrom(emailService.getEmailFrom());
            notificationLogRepo.save(entry);
        } catch (Exception e) {
            log.warn("Bildirim logu kaydedilemedi: {}", e.getMessage());
        }
    }

    /** ctx'e olay kimliğini ekler (ctx null olabilir → yeni map). E-postadaki olay aksiyon
     *  butonlarının derin linkleri bu kimlikten üretilir. */
    private static Map<String, Object> withAlertEventId(Map<String, Object> ctx, Long id) {
        if (id == null) return ctx;
        Map<String, Object> m = (ctx == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(ctx);
        m.put("alert_event_id", id);
        return m;
    }

    /**
     * Mailin "takim" satirinda gorunen adlar. Bir takim ancak GERCEKTEN alici uretiyorsa
     * listelenir; grup devredeyse takim adi degismez -- degisen alicidir, sahip degil.
     */
    private String collectTeamNames(Long syTeamId, Long ugTeamId, Long stampedGroupId) {
        List<String> names = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        for (Long teamId : List.of(
                syTeamId != null ? syTeamId : -1L,
                ugTeamId != null ? ugTeamId : -1L)) {
            if (teamId < 0) continue;
            teamRepo.findById(teamId).ifPresent(team -> {
                boolean fresh = false;
                for (String e : teamRecipientEmails(teamId, syTeamId, stampedGroupId, team)) {
                    if (seenEmails.add(e.toLowerCase())) fresh = true;
                }
                if (fresh) {
                    String name = team.getName() != null ? team.getName().trim() : "";
                    if (!name.isBlank()) names.add(name);
                }
            });
        }
        return String.join(", ", names);
    }

    /**
     * Bir takimin alarm alicilari: grup devredeyse grubun adresleri, degilse {@code Team.email}.
     *
     * <p>Damga YALNIZ izlemenin sahibi takima ({@code syTeamId}) uygulanir: monitor formundaki
     * grup secimi o takimin kararidir; cift-takimli sertifika alarmlarinda UG takimi kendi
     * varsayilanindan cozulur.
     */
    private List<String> teamRecipientEmails(Long teamId, Long syTeamId, Long stampedGroupId,
                                             com.sitemonitor.model.Team team) {
        Long stamp = teamId.equals(syTeamId) ? stampedGroupId : null;
        NotificationGroupService.Override ov = notificationGroups.overrideFor(teamId, stamp);
        if (ov != null && ov.applies()) return ov.emails();
        String email = team.getEmail() != null ? team.getEmail().trim() : "";
        return email.isBlank() ? List.of() : List.of(email);
    }

    /* teamAlertEmails(teamId) KALDIRILDI (2026-09-23). Tek çağıranı ScriptedAnomalyGuard'dı ve
       javadoc'u "aynı kişilere gitmeli" diyordu, ama collectTeamEmails(teamId, null, NULL) ile
       monitörün kendi bildirim grubunu yok sayıyordu: otomatik kapatma bildirimi, o monitörün
       normal alarmlarından BAŞKA bir adres kümesine gidiyordu. Çağıran artık aşağıdaki
       teamEmailsForMonitor'ü kullanıyor — javadoc'un tarif ettiği davranışın kendisi. */

    /**
     * Bir izlemenin bildirim alıcıları (2026-09-22): bildirim grubu → takım varsayılan grubu → takım e-postası — alarm
     * maillerinin kullandığı zincirin AYNISI. Alarm dışı bildirimler (alan adı hatırlatması) de bu zinciri kullansın ki
     * "hatırlatma başka adrese gitti" olmasın.
     */
    public List<String> teamEmailsForMonitor(Long teamId, Long notificationGroupId) {
        return collectTeamEmails(teamId, null, notificationGroupId);
    }

    private List<String> collectTeamEmails(Long syTeamId, Long ugTeamId, Long stampedGroupId) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Long teamId : List.of(
                syTeamId != null ? syTeamId : -1L,
                ugTeamId != null ? ugTeamId : -1L)) {
            if (teamId < 0) continue;
            teamRepo.findById(teamId).ifPresent(team -> {
                for (String email : teamRecipientEmails(teamId, syTeamId, stampedGroupId, team)) {
                    if (seen.add(email.toLowerCase())) result.add(email);
                }
            });
        }
        return result;
    }

    /**
     * Önizleme için takım alıcıları: [email, takım adı, kaynak etiketi] üçlüleri
     * (collectTeamEmails ile aynı sıra/dedupe). Üçüncü alan K9 etiketidir: grup devredeyse
     * "Grup: X", değilse "Takım maili" -- kullanıcı postanın NEREDEN yönlendirildiğini onay
     * pop-up'ında görür.
     */
    private List<String[]> collectTeamRecipients(Long syTeamId, Long ugTeamId, Long stampedGroupId) {
        List<String[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Long teamId : List.of(
                syTeamId != null ? syTeamId : -1L,
                ugTeamId != null ? ugTeamId : -1L)) {
            if (teamId < 0) continue;
            teamRepo.findById(teamId).ifPresent(team -> {
                Long stamp = teamId.equals(syTeamId) ? stampedGroupId : null;
                NotificationGroupService.Override ov = notificationGroups.overrideFor(teamId, stamp);
                String source = (ov != null && ov.applies()) ? ov.label() : "Takım maili";
                String teamName = team.getName() != null ? team.getName().trim() : "";
                for (String email : teamRecipientEmails(teamId, syTeamId, stampedGroupId, team)) {
                    if (seen.add(email.toLowerCase()))
                        result.add(new String[]{ email, teamName, source });
                }
            });
        }
        return result;
    }

    private Map<String, Object> latestToCertContext(LatestCheck l) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("revocation_status",  l.getRevocationStatus()  != null ? l.getRevocationStatus()  : "UNKNOWN");
        ctx.put("chain_status",       l.getChainStatus()       != null ? l.getChainStatus()       : "UNKNOWN");
        ctx.put("deployment_status",  l.getDeploymentStatus()  != null ? l.getDeploymentStatus()  : "UNKNOWN");
        ctx.put("days_remaining",     l.getDaysRemaining());
        ctx.put("not_after",          l.getNotAfter());
        ctx.put("not_before",         l.getNotBefore());
        ctx.put("issuer",             l.getIssuer());
        ctx.put("issuer_cn",          l.getIssuerCn());
        ctx.put("subject",            l.getSubject());
        ctx.put("fingerprint",        l.getFingerprint());
        ctx.put("checked_at",         l.getCheckedAt());
        return ctx;
    }

    /**
     * Güvenlik alarmına KANIT ekler: çözümlenen IP ve sunulan sertifikanın CN'i.
     *
     * <p>NXDOMAIN-hijack vakasında kullanıcının tek bakışta görmesi gereken şey buydu:
     * alan adı bir ev modeminin iç IP'sine çözülüyor ve modemin kendi paneli sunuluyor.
     *
     * <p><b>Tek başına ASLA alarm değildir</b> — kurumda bir iç host'un 10.x.x.x'e çözülmesi
     * tamamen normaldir. Yalnız zaten açılmış bir güvenlik alarmını açıklar.
     */
    static String securityEvidence(String alertType, Map<String, Object> result) {
        if (!TYPE_HOSTNAME_MISMATCH.equals(alertType) && !TYPE_UNTRUSTED_CA.equals(alertType)) return "";
        if (result == null) return "";
        String ip = result.get("resolved_ip") instanceof String v && !v.isBlank() ? v : null;
        String cn = result.get("subject") instanceof String v && !v.isBlank() ? v : null;
        if (ip == null && cn == null) return "";
        StringBuilder sb = new StringBuilder(" [");
        if (ip != null) sb.append("çözümlenen IP: ").append(ip).append(isInternalIp(ip) ? " (iç ağ)" : "");
        if (ip != null && cn != null) sb.append("; ");
        if (cn != null) sb.append("sunulan CN: ").append(cn);
        return sb.append("]").toString();
    }

    /** Metin zaten bir IP olduğu için {@code getByName} DNS'e gitmez; ad verilirse sessizce false. */
    private static boolean isInternalIp(String ip) {
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(ip);
            return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress();
        } catch (Exception e) { return false; }
    }

    /**
     * Bu alarm tipi SÜRE-BİTİŞİ ailesinden mi — yani "N gün kaldı" ifadesi anlamlı mı?
     *
     * <p>Sertifika kusurları (iptal / dağıtım / zincir / alan adı uyuşmazlığı / güvenilmeyen CA)
     * süreden BAĞIMSIZDIR: 1775 gün geçerli bir sertifika da bu host için kabul edilemez olabilir.
     * Bu ayrım yapılmadığında konu satırı "[Site Monitor] 1775 GÜN KALDI · host · Alan Adı
     * Uyuşmazlığı" oluyordu — KRİTİK etiketi kayboluyor ve konu, alarmın gerekçesinin tam tersini
     * söylüyordu. Konu satırı ve e-posta hero'su bu tek yüklemden karar verir.
     */
    static boolean isDurationAlert(String alertType) {
        if (alertType == null || alertType.isBlank()) return true;   // eski/bilinmeyen: bugünkü davranış
        // İzleme türlerinde YALNIZ kayıt / alan adı bitişi süre ailesidir (ALERTS-1, 2026-10-22): DOMAINMON_UNKNOWN /
        // STATUS / CHANGED / TRANSFER_LOCK / BLACKLIST bağlamı da kalan günü taşır (en güncel alan adı kaydından) ve konu
        // "245 GÜN KALDI · … · Alan adı kara listede", eskalasyon adımının hero'su "245 gün kaldı" oluyordu.
        if (MONITORING_ALERT_TYPES.contains(alertType)) return EXPIRY_MONITORING_TYPES.contains(alertType);
        return !("REVOKED".equals(alertType) || "MISMATCH".equals(alertType)
                || "CHAIN_BROKEN".equals(alertType)
                || TYPE_HOSTNAME_MISMATCH.equals(alertType) || TYPE_UNTRUSTED_CA.equals(alertType));
    }

    private String buildMessage(String domain, String alertType, String alertLevel, Integer days) {
        String body = buildMessageBody(domain, alertType, alertLevel, days);
        // İzleme türlerinde seviye sözcüğü seviyeden (withLevelWord); sertifika iletileri olduğu gibi.
        return alertType != null && MONITORING_ALERT_TYPES.contains(alertType) ? withLevelWord(body, alertLevel) : body;
    }

    private String buildMessageBody(String domain, String alertType, String alertLevel, Integer days) {
        return switch (alertType) {
            case TYPE_ACCESSIBILITY -> "KRİTİK: " + domain +
                    " adresine erişilemiyor. Ardışık doğrulama denemeleri başarısız oldu — " +
                    "site erişilemez durumda. Erişim geri geldiğinde alarm otomatik kapanacaktır.";
            case TYPE_PORT_DOWN -> "KRİTİK: " + domain +
                    " üzerinde izlenen porta erişilemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                    "Port yeniden açıldığında alarm otomatik kapanacaktır.";
            case TYPE_PORT_SLOW -> "YÜKSEK: " + domain +
                    " üzerinde izlenen port yanıt süresi eşiğini aştı (yavaş). Yanıt hızlandığında alarm otomatik kapanır.";
            case TYPE_DNS_FAILURE -> "KRİTİK: " + domain +
                    " için DNS sorgusu çözümlenemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                    "Çözümleme düzeldiğinde alarm otomatik kapanacaktır.";
            case TYPE_DNS_SLOW -> "YÜKSEK: " + domain +
                    " için DNS sorgusu çözümleniyor ANCAK YAVAŞ (yanıt süresi eşiği aşıyor). " +
                    "DNS sunucusunda timeout/gecikme olabilir. Yanıt hızlandığında alarm otomatik kapanır.";
            case TYPE_DNS_CHANGED -> "YÜKSEK: " + domain +
                    " için izlenen DNS kaydı değişti. Bu alarm otomatik kapanmaz; " +
                    "değişiklik planlı ise alarmı onaylayıp manuel kapatınız.";
            case TYPE_DNS_UNEXPECTED -> "YÜKSEK: " + domain +
                    " için izlenen DNS kaydında beklenmeyen (sabitlenen değerde olmayan) bir değer çözümleniyor — " +
                    "olası hijack/yanlış yönlendirme. Doğrulayın; değer beklenene dönünce alarm otomatik kapanır.";
            case TYPE_DNS_INCONSISTENT -> "YÜKSEK: " + domain +
                    " için DNS kaydı public resolver'lar arasında tutarsız — propagation gecikmesi / split-DNS / " +
                    "olası poisoning. Resolver'lar aynılaşınca alarm otomatik kapanır.";
            case "REVOKED" -> "KRİTİK: " + domain +
                    " adresindeki sertifika İPTAL EDİLMİŞTİR. Trafik derhal yönlendirilmelidir.";
            case "MISMATCH" -> "DAĞITIM EKSİK: " + domain +
                    " için yenilenmiş bir sertifika mevcut ancak uç nokta eski sertifikayı sunmaya devam ediyor.";
            case "CHAIN_BROKEN" -> "ZİNCİR SORUNU: " + domain +
                    " sertifika zincirindeki bir ara veya kök CA sertifikası süresi dolmuş ya da geçersiz.";
            case TYPE_HOSTNAME_MISMATCH -> "KRİTİK: " + domain +
                    " adresinde sunulan sertifika BU ALAN ADINI KAPSAMIYOR. " +
                    "Tarayıcılar bağlantıyı reddeder; yanlış yönlendirme ya da DNS ele geçirme olabilir.";
            case TYPE_UNTRUSTED_CA -> "KRİTİK: " + domain +
                    " adresindeki sertifika güvenilir bir kök CA'ya bağlanmıyor. " +
                    "Kurumsal CA ise Genel Ayarlar'daki güven paketine ekleyin; değilse trafik doğrulanmalıdır.";
            case TYPE_HTTP_DOWN -> "KRİTİK: " + domain +
                    " adresine HTTP isteği başarısız — site erişilemez durumda. " +
                    "Erişim geri geldiğinde alarm otomatik kapanacaktır.";
            case TYPE_PAGE_DOWN -> "KRİTİK: " + domain +
                    " sayfası yüklenemiyor — ana içerik alınamadı. " +
                    "Sayfa yeniden yüklendiğinde alarm otomatik kapanacaktır.";
            case TYPE_PAGE_INTEGRITY -> "YÜKSEK: " + domain +
                    " sayfasında bütünlük sorunu (kırık kaynak / mixed content) tespit edildi. " +
                    "Sorunlu kaynaklar giderildiğinde alarm otomatik kapanır.";
            case TYPE_SCRIPTED_FAIL -> "KRİTİK: " + domain +
                    " sentetik testi (k6) başarısız — ardışık doğrulama denemeleri geçmedi. " +
                    "Test yeniden geçtiğinde alarm otomatik kapanacaktır.";
            case TYPE_SCRIPTED_SLOW -> "YÜKSEK: " + domain +
                    " senaryosu çalışıyor ancak koşum süresi eşiği aştı. " +
                    "Süre eşiğin altına indiğinde alarm otomatik kapanacaktır.";
            case TYPE_PAGESPEED_DOWN -> "KRİTİK: " + domain +
                    " sayfası hız ölçümü için alınamadı. " +
                    "Sayfa yeniden yüklendiğinde alarm otomatik kapanacaktır.";
            case TYPE_PAGESPEED_SLOW -> "YÜKSEK: " + domain +
                    " sayfası performans eşiğini aştı — bu bir kesinti değildir, sayfa çalışıyor ancak ağır/yavaş. " +
                    "Ölçüm eşiğin altına indiğinde alarm otomatik kapanacaktır.";
            case TYPE_HTTP_SSL -> "YÜKSEK: " + domain +
                    " için TLS sertifikası hata veriyor ya da süresi dolmak üzere. " +
                    "Sertifika düzeldiğinde alarm otomatik kapanır.";
            case TYPE_HTTP_SLOW -> "YÜKSEK: " + domain +
                    " HTTP izlemesinde yanıt süresi eşiği aşıldı (yavaş). Yanıt hızlandığında alarm otomatik kapanır.";
            case TYPE_DOMAIN_EXPIRY -> "YÜKSEK: " + domain +
                    " domain kaydının (registrar) süresi dolmak üzere. Kayıt yenilendiğinde alarm otomatik kapanır.";
            case TYPE_KEYWORD_SLOW -> "YÜKSEK: " + domain +
                    " içerik izlemesinde yanıt süresi eşiği aşıldı (yavaş). Yanıt hızlandığında alarm otomatik kapanır.";
            case TYPE_KEYWORD_SSL -> "YÜKSEK: " + domain +
                    " içerik izlemesi host'unun TLS sertifikası hata veriyor ya da süresi dolmak üzere. " +
                    "Sertifika düzeldiğinde alarm otomatik kapanır.";
            case TYPE_KEYWORD_DOMAIN_EXPIRY -> "YÜKSEK: " + domain +
                    " (içerik izlemesi) domain kaydının süresi dolmak üzere. Kayıt yenilendiğinde alarm otomatik kapanır.";
            case TYPE_DOMAINMON_EXPIRY -> "YÜKSEK: " + domain + " alan adının kaydı dolmak üzere. Registrar üzerinden yenileyin.";
            case TYPE_DOMAINMON_UNKNOWN -> "UYARI: " + domain + " alan adının kayıt bilgisi alınamadı (veri yok). Erişim/TLD desteğini doğrulayın.";
            case TYPE_DOMAINMON_STATUS -> "YÜKSEK: " + domain + " alan adında dikkat gerektiren EPP durum kodları var.";
            case TYPE_DOMAINMON_CHANGED -> "YÜKSEK: " + domain + " alan adının kayıt bilgisi değişti (olası hijack). İnceleyip onaylayın.";
            // ALERTS-1 (2026-10-22): bu beş tür eksikti ve default'a (SERTİFİKA metni) düşüyordu — bağlamsız yeniden
            // gönderimde "sertifika bilgileri alınamadı" / "sertifikanın süresi 245 gün içinde doluyor" yazıyordu.
            case TYPE_KEYWORD -> "KRİTİK: " + domain +
                    " içerik doğrulaması başarısız — izlenen sayfada aranan içerik koşulu sağlanmıyor. " +
                    "Ardışık doğrulama denemeleri başarısız oldu. Koşul yeniden sağlandığında alarm otomatik kapanacaktır.";
            case TYPE_PING_DOWN -> "KRİTİK: " + domain + " ICMP ping'e yanıt vermiyor. " +
                    "Ardışık doğrulama denemeleri başarısız oldu. Host yeniden yanıt verdiğinde alarm otomatik kapanacaktır.";
            case TYPE_PING_SLOW -> "YÜKSEK: " + domain + " ping yanıt süresi kendi taban çizgisinin üstüne çıktı. " +
                    "Süre taban çizgisine döndüğünde alarm otomatik kapanır.";
            case TYPE_DOMAINMON_TRANSFER_LOCK -> "YÜKSEK: " + domain + " alan adında TRANSFER KİLİDİ YOK. " +
                    "Registrar panelinden clientTransferProhibited kilidini etkinleştirin. Kilit görüldüğünde alarm otomatik kapanır.";
            case TYPE_DOMAINMON_BLACKLIST -> "YÜKSEK: " + domain + " alan adı ya da IP'leri e-posta KARA LİSTESİNDE. " +
                    "Listeden çıkınca alarm otomatik kapanır.";
            default -> {
                String lvl = switch (alertLevel != null ? alertLevel : "") {
                    case "CRITICAL" -> "KRİTİK";
                    case "HIGH"     -> "YÜKSEK";
                    default         -> "UYARI";
                };
                yield days != null
                    ? lvl + ": " + domain + " adresindeki sertifikanın süresi " + days + " gün içinde doluyor."
                    : lvl + ": " + domain + " adresindeki sertifikaya erişilemediği için sertifika bilgileri alınamadı (ağ/firewall kaynaklı olabilir).";
            }
        };
    }

    /**
     * Acilan izleme alarmina damgalanacak bildirim grubu.
     *
     * <p>Once sweep'in ctx'e koydugu monitor grubu; yoksa (envanter-turevli DNS/Port alarmlarinda
     * ctx monitor tasimaz) domainin envanter kaydinin grubu. Ikisi de yoksa null -- cozumleme
     * zincirin kalanina, yani takimin varsayilanina ve {@code Team.email}'e duser.
     */
    private Long resolveStampFromContext(Map<String, Object> ctx, String domain) {
        Object v = ctx != null ? ctx.get("notification_group_id") : null;
        if (v instanceof Number n) return n.longValue();
        return inventoryRepo.findByDomain(domain)
                .map(com.sitemonitor.model.CertificateInventory::getNotificationGroupId)
                .orElse(null);
    }

    private AlertEvent newEvent(String domain, String level, String type, String message, Integer days) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertLevel(level);
        e.setAlertType(type);
        e.setMessage(message);
        e.setDaysRemaining(days);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setCreatedAt(now());
        return e;
    }

    /**
     * ÇÖZÜM maili konusundaki tip etiketi. Alarm konusuyla (aşağıdaki typeTr switch'i) ayrı
     * tutulmuştu ve K2'de eklenen iki alan-adı tipi yalnız alarm tarafına girmişti: kara liste
     * temizlenince "Sertifika Süre Bitişi sorunu giderildi" yazıyordu. Kapı:
     * EscalationServiceTest.resolvedTypeLabel_coversEveryAlertType.
     */
    static String resolvedTypeLabel(String alertType) {
        return switch (alertType != null ? alertType : "") {

                case "REVOKED"          -> "İptal";
                case "MISMATCH"         -> "Dağıtım Eksik";
                case "CHAIN_BROKEN"     -> "Zincir Sorunu";
                case TYPE_HOSTNAME_MISMATCH -> "Alan Adı Uyuşmazlığı";
                case TYPE_UNTRUSTED_CA      -> "Güvenilmeyen Sertifika";
                case TYPE_ACCESSIBILITY -> "Erişim Kesintisi";
                case TYPE_PORT_DOWN     -> "Port Kesintisi";
                case TYPE_PORT_SLOW     -> "Port Yavaş Yanıt";
                case TYPE_DNS_FAILURE   -> "DNS Çözümleme Hatası";
                case TYPE_DNS_SLOW      -> "DNS Yavaş/Timeout";
                case TYPE_DNS_UNEXPECTED -> "DNS Beklenmeyen Değer";
                case TYPE_DNS_INCONSISTENT -> "DNS Tutarsızlığı";
                case TYPE_DNS_CHANGED   -> "DNS Değişikliği";
                case TYPE_KEYWORD       -> "İçerik Doğrulama";
                case TYPE_KEYWORD_SLOW  -> "İçerik Yavaş Yanıt";
                case TYPE_KEYWORD_SSL   -> "İçerik SSL Sorunu";
                case TYPE_KEYWORD_DOMAIN_EXPIRY -> "İçerik Domain Bitişi";
                case TYPE_PING_DOWN     -> "Erişilebilirlik (Ping)";
                case TYPE_PING_SLOW     -> "Ping Yavaş Yanıt";
                case TYPE_HTTP_DOWN     -> "HTTP/Website Erişilemez";
                case TYPE_HTTP_SSL      -> "SSL Sertifika Sorunu";
                case TYPE_HTTP_SLOW     -> "HTTP Yavaş Yanıt";
                case TYPE_PAGE_DOWN     -> "Sayfa Yüklenemiyor";
                case TYPE_PAGE_INTEGRITY -> "Sayfa Bütünlüğü";
                case TYPE_SCRIPTED_FAIL -> "Sentetik İzleme";
                case TYPE_SCRIPTED_SLOW -> "Sentetik Yavaş Koşum";
                case TYPE_PAGESPEED_DOWN -> "Sayfa Hızı Ölçülemiyor";
                case TYPE_PAGESPEED_SLOW -> "Sayfa Hızı Eşiği Aşıldı";
                case TYPE_DOMAIN_EXPIRY -> "Domain Süre Bitişi";
                case TYPE_DOMAINMON_EXPIRY  -> "Alan Adı Süre Bitişi";
                case TYPE_DOMAINMON_UNKNOWN -> "Alan Adı Veri Yok";
                case TYPE_DOMAINMON_STATUS  -> "Alan Adı Durum Kodu";
                case TYPE_DOMAINMON_CHANGED -> "Alan Adı Değişikliği";
                                case TYPE_DOMAINMON_TRANSFER_LOCK -> "Alan Adı Transfer Kilidi";
                case TYPE_DOMAINMON_BLACKLIST     -> "Alan Adı Kara Liste";
                default                 -> "Sertifika Süre Bitişi";
        };
    }

    /** Kontrol sonucundaki gerçek son geçerlilik anı (UTC ISO) — yoksa null. */
    static String notAfterOf(Map<String, Object> result) {
        Object v = result != null ? result.get("not_after") : null;
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** Çözüldü e-postasında detay için alarm anı context'inin küçük JSON snapshot'ı.
     *  Sayfa anahtarları 2026-08-04'te eklendi ("sorun neydi" detayı için) — daha ESKİ açık alarmların
     *  snapshot'ında yoklar; çözüm maili o durumda zarifçe sade düzene düşer. */
    /**
     * Çözüm e-postasının okuyabileceği alarm-anı bağlam anahtarları (snapshot whitelist'i).
     *
     * <p>Tek doğruluk kaynağı: {@code snapshotContext} bunu yazıyor,
     * {@code EmailTemplateBuilder.buildResolvedHtml/Text} bunu okuyor. İkisi ayrıştığında şablonun
     * o satırı SESSİZCE ölü koda dönüşüyor — anahtar üretici tarafta yazılsa bile snapshot'a hiç
     * girmediği için çözüm mailinde hiçbir zaman görünmüyor (2026-09-23'te duration_ms ve
     * failed_checks tam olarak böyle kaybolmuştu). Kapı: {@code ResolvedMailContextKeysTest}.
     */
    public static final java.util.List<String> RESOLVED_CONTEXT_KEYS = List.of("keyword", "operator", "match_count", "occurrences",
                                 "url", "host", "ip_version", "monitor_id", "condition",
                                 "http_status", "last_error", "response_ms", "threshold_ms", "port", "protocol",
                                 // team_id: AÇILIŞ yolu "ctx'te team_id varsa ugTeamId = null" diyor
                                 // (bağımsız izleme takım-özeldir). Damga snapshot'a girmezse çözüm ve
                                 // "tekrar bildir" yolları aynı kararı veremiyor ve envanterin UG
                                 // takımına, alarmı HİÇ görmemiş olmasına rağmen "ÇÖZÜLDÜ" gidiyordu.
                                 "team_id", "standalone",
                                 // Sayfa Bütünlüğü (PAGE_DOWN/PAGE_INTEGRITY) — çözüm maili "sorun neydi" bloğu
                                 "page_status", "page_mode", "broken_resources", "timeout_count",
                                 "mixed_content_count", "total_resources",
                                 "problem_resources", "problem_rows", "problem_total", "detail",
                                 // Kanal bastirma damgalari: cozum yolu ctx'i olaydan geri okuyor;
                                 // bu anahtarlar kalicilastirilmazsa "e-postayi kapattim ama COZULDU
                                 // maili geliyor" paritesizligi olusuyordu.
                                 "mail_disabled", "push_disabled",
                                 // Sentetik (SCRIPTED_SLOW/FAIL) çözüm maili — EmailTemplateBuilder
                                 // bu ikisini okuyor (HTML :460-473, düz metin :537-549) ve üretici
                                 // taraf yazıyor (SchedulerService:4066, :4089) ama whitelist'te
                                 // olmadıkları için snapshot'a HİÇ girmiyorlardı: "Süre (alarm anı)"
                                 // ve "Düşen Doğrulamalar" satırları ölü koddu. Kapı:
                                 // ResolvedMailContextKeysTest — şablonun okuduğu her anahtar burada.
                                 // "error" da aynı kapıdan geçti: SchedulerService ctx'e yazıyor ve
                                 // şablon firstNonNull(error, last_error) okuyor. last_error yedeği
                                 // satırı ayakta tutuyordu ama birincil anahtar hiç snapshot'a
                                 // girmiyordu; "error" daha zengin olduğunda bilgi kaybediliyordu.
                                 "duration_ms", "failed_checks", "error",
                                 // O-b4: sayfa bütünlüğü bulgusunun KAYNAĞI (HOME / CRAWL) ve crawl'daki sorunlu
                                 // sayfalar — kurtarma doğrulaması aynı kaynağı yeniden ölçer (SchedulerService).
                                 "integrity_scope", "problem_pages",
                                 // ALERTS-2 (2026-10-22): bakım penceresinde algılanan DNS / alan adı DEĞİŞİKLİĞİ pencere bitince
                                 // bu anlık görüntüden bildiriliyor — anahtarlar yokken ileti eski/yeni değersiz genel metne
                                 // dönüyor, e-postanın ESKİ / YENİ kutuları boş gidiyordu.
                                 "record_type", "old_values", "new_values", "change_detail", CTX_DETECTED_IN_MAINTENANCE,
                                 // ALERTS-1: elle yeniden gönderim / eskalasyon adımı konusunda izlemenin ADI (çıplak URL değil).
                                 "monitor_name");

    private String snapshotContext(Map<String, Object> ctx) {
        if (ctx == null) return null;
        Map<String, Object> snap = new LinkedHashMap<>();
        for (String k : RESOLVED_CONTEXT_KEYS) {
            if (ctx.get(k) != null) snap.put(k, ctx.get(k));
        }
        if (snap.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(snap); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeContext(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, Map.class); } catch (Exception e) { return null; }
    }

    /**
     * O-b4: bu anahtarın (alan adı + tür) EN YENİ açık alarmının alarm-anı bağlamı; açık alarm yoksa null, bağlamsız
     * (eski) alarmda boş harita. Kaynak-duyarlı kapanış kararı için (sayfa bütünlüğü — SchedulerService). Salt okuma.
     */
    public Map<String, Object> openAlertContext(String domain, String alertType) {
        return alertEventRepo.findOpenAlert(domain, alertType).map(e -> {
            Map<String, Object> c = deserializeContext(e.getContextJson());
            return c != null ? c : Map.<String, Object>of();
        }).orElse(null);
    }

    /** Takımı cert envanterinden DEĞİL AlertEvent.teamId'den (alarm anında damgalanan) bulunan standalone izleme tipi mi?
     *  Serbest-form izleme (keyword/ping/http) + domain monitör alarmları böyledir. */
    /**
     * Envanterin UG takımı bu olayın bildirimlerine eklenmeli mi? AÇILIŞ yolunun kuralının aynası.
     *
     * <p>{@code processConfirmedOutage} ctx'te {@code team_id} damgası görünce {@code ugTeamId = null}
     * yapıyor — bağımsız izleme takım-özeldir. Çözüm ve "tekrar bildir" yolları ise bu kararı
     * {@code isStandaloneMon} tip listesinden veriyordu ve PORT / DNS / PING_SLOW tipleri o
     * listede YOK (yıldızlı kısaltma yazmayın: javadoc içinde yorumu erken kapatır):
     * host'u cert envanterinde de bulunan bağımsız bir Port izlemesi düştüğünde alarm yalnız Port
     * takımına gidiyor, "✅ ÇÖZÜLDÜ" maili ise alarmı hiç görmemiş UG takımına DA gidiyordu.
     *
     * <p>Karar YALNIZ açık bağımsız işaretine dayanır ({@code team_id} ya da {@code standalone} — bağlam anlık
     * görüntüsünde), yeniden uyarı / eskalasyon yolundaki {@code isStandaloneEvent} ile AYNI (2026-09-28, D4). Eski
     * sezgi ("damga ≠ envanter SY ise bağımsız") SY aktarımından sonra sertifika alarmının UG'sini çözümde düşürüyordu:
     * UG yeniden uyarıyı alıp "ÇÖZÜLDÜ"yü almıyordu. İşaretsiz ESKİ bağımsız Port/DNS olayları açılışta
     * {@link DerivedMonitorAlertRouting} tarafından işaretlenir; sezgiye gerek kalmadı.
     */
    private boolean includeInventoryUgTeam(AlertEvent event, Long invTeamId) {
        Map<String, Object> ctx = deserializeContext(event.getContextJson());
        return !standaloneMark(ctx);
    }

    private static boolean isStandaloneMon(String alertType) {
        // PING_SLOW (prod kapısı 2026-09-25, O-1): ping izlemesi HER ZAMAN bağımsızdır; PING_DOWN listedeydi,
        // yavaşlık alarmı değildi — WARNING yavaşlık maili takım kontağı yoksa GLOBAL kontaklara düşüyordu.
        return TYPE_KEYWORD.equals(alertType) || TYPE_PING_DOWN.equals(alertType) || TYPE_PING_SLOW.equals(alertType)
                || TYPE_HTTP_DOWN.equals(alertType) || TYPE_HTTP_SSL.equals(alertType) || TYPE_HTTP_SLOW.equals(alertType)
                || TYPE_DOMAIN_EXPIRY.equals(alertType)
                || isDomainMon(alertType) || isKeywordAux(alertType) || isPage(alertType) || isScripted(alertType)
                || isPageSpeed(alertType);
    }

    /**
     * Bağımsız izleme alarmı mı (prod kapısı 2026-09-25, O-1) — tip listesi VEYA alarm bağlamında {@code team_id}
     * damgası. PORT ve DNS ÇİFT kaynaklıdır: bağımsız izlemede ({@code standalone=true}) sweep bağlama takımı damgalar,
     * envanter türevlisinde damga YOKTUR — satır takımı envanterden kopyalamış olsa bile ({@code SchedulerService.alarmTeamOf},
     * 2026-09-28). Türev alarm böylece sertifika alarmıyla aynı yönlenir: SY + UG adresleri, her takımın kendi kişileri,
     * aynı seviye kapıları. {@link #includeInventoryUgTeam} ile aynı ölçüt.
     */
    public static boolean isStandalone(String alertType, Map<String, Object> ctx) {
        return isStandaloneMon(alertType) || standaloneMark(ctx);
    }

    /** Bağlamda bağımsız izleme işareti: {@code team_id} damgası ya da {@code standalone: true} (takımı boş bağımsız satır). */
    static boolean standaloneMark(Map<String, Object> ctx) {
        return ctx != null && (ctx.get("team_id") instanceof Number || Boolean.TRUE.equals(ctx.get("standalone")));
    }

    private static final ObjectMapper CTX_JSON = new ObjectMapper();

    /** {@link #isStandalone(String, Map)} — olayın kalıcı bağlamından ({@code contextJson}). */
    public static boolean isStandaloneEvent(AlertEvent e) {
        return e != null && (isStandaloneMon(e.getAlertType()) || hasTeamStamp(e));
    }

    /** Alarm bağlamında {@code team_id} damgası var mı (sweep bağımsız izlemede takımı damgalar). */
    @SuppressWarnings("unchecked")
    public static boolean hasTeamStamp(AlertEvent e) {
        String json = e == null ? null : e.getContextJson();
        if (json == null || json.isBlank()) return false;
        try {
            Map<String, Object> ctx = CTX_JSON.readValue(json, Map.class);
            return standaloneMark(ctx);
        } catch (Exception ignore) {
            return false;   // bozuk bağlam: tip listesine düş
        }
    }

    // ── Y-1 (2026-09-29): paylaşılan anahtarda SAHİPLİK ─────────────────────────────────────────

    /**
     * Bir izleme bağlamının sahiplik anahtarı: bağımsız izleme (tür listesi ya da bağlam işareti) → {@code "T:<takım>"}
     * (takımı boş bağımsız satırda {@code "T:null"}); envanter türevi (Port/DNS türev satırı, ACCESSIBILITY) →
     * {@code "INV"} — takımı alan adı → envanterden çözülür. Aynı anahtarı (alan adı + tür) paylaşan iki izlemenin aynı
     * olayı "sahiplenip sahiplenemeyeceği" buna bakar.
     */
    public static String ownerKeyOf(String alertType, Map<String, Object> ctx) {
        if (!isStandalone(alertType, ctx)) return "INV";
        Object t = ctx == null ? null : ctx.get("team_id");
        return "T:" + (t instanceof Number n ? n.longValue() : null);
    }

    /** Açık olayın sahiplik anahtarı — bağımsız olayda takım damgası, aksi hâlde {@code "INV"}. */
    public static String ownerKeyOf(AlertEvent e) {
        if (e == null) return null;
        return isStandaloneEvent(e) ? "T:" + e.getTeamId() : "INV";
    }

    /** Olayı AÇAN izlemenin kimliği (bağlam anlık görüntüsündeki {@code monitor_id}) — yoksa null. */
    @SuppressWarnings("unchecked")
    public static Long contextMonitorId(AlertEvent e) {
        String json = e == null ? null : e.getContextJson();
        if (json == null || json.isBlank()) return null;
        try {
            Object v = CTX_JSON.readValue(json, Map.class).get("monitor_id");
            return v instanceof Number n ? n.longValue() : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Bu bağlam (izleme sonucu) açık olayın SAHİBİ mi? Bağlam izleme kimliği taşımıyorsa sahiplik bilinemez → true
     * (eski davranış; DNS_CHANGED yeniden uyarısı, kimliksiz ACCESSIBILITY). Olayı açan izlemenin kendisiyse → true
     * (izleme olay açıkken başka takıma taşınmış olsa bile kendi olayının sahibidir). Aksi hâlde sahiplik anahtarları
     * aynı olmalı: aynı takımın iki izlemesi (ör. aynı host'ta 443 ve 8443) aynı olayı paylaşır; başka takımınki ya da
     * bağımsız ↔ envanter türevi paylaşmaz.
     */
    public static boolean sameOwner(AlertEvent e, String alertType, Map<String, Object> ctx) {
        if (e == null) return true;
        Object mid = ctx == null ? null : ctx.get("monitor_id");
        if (!(mid instanceof Number n)) return true;
        Long opener = contextMonitorId(e);
        if (opener != null && opener == n.longValue()) return true;
        return java.util.Objects.equals(ownerKeyOf(e), ownerKeyOf(alertType, ctx));
    }

    /**
     * TEK alıcı kararı (O-1): bağımsız izlemede WARNING → yalnız takım; aksi hâlde seviye eşikli eskalasyon
     * kontakları ({@code getContactsForLevel} → {@link EscalationContactScope}: YALNIZ takımın kendi kontakları,
     * takımda yoksa hiç — başka takımın/takımsız kontağa düşülmez). Açılış, çözüm, tekrar bildir, fırtına ve
     * "Kim bilgilendirilir?" simülatörü BUNU kullanır.
     */
    static boolean teamOnly(boolean standalone, String level) {
        return standalone && !includeManagerContacts(null, level);
    }

    private List<EscalationContact> contactsFor(boolean standalone, String level, Long teamId, Long ugTeamId) {
        return teamOnly(standalone, level) ? List.of() : getContactsForLevel(level, teamId, ugTeamId);
    }


    /** Domain süre-bitişi alarmında müdür (eskalasyon kontağı) da eklensin mi? Kullanıcı politikası:
     *  YALNIZ KRİTİK domain alarmında müdür bilgilendirilir; ORTA/WARNING'de yalnız takım. Diğer standalone
     *  izleme (keyword/ping/http) her zaman yalnız takım. */
    private static boolean includeManagerContacts(String alertType, String level) {
        // 2026-09-19 ürün kararı: seviye eşiği TÜM izleme türleri için tek kural — WARNING yalnız takım;
        // HIGH/CRITICAL eskalasyon kontakları (kendi eşiklerine göre) eklenir. Kullanıcı seviyeyi izleme
        // formundan yükseltir; süre-bitişi alarmlarında seviye gün kademesinden gelir.
        return !"WARNING".equals(level);
    }

    /**
     * "Bu alarmın alıcıları yalnız TAKIM mı?" — bireysel yol ({@code processConfirmedOutage}) ile
     * fırtına dağıtımının ({@code StormService.resolveRecipients}) PAYLAŞTIĞI tek karar.
     *
     * <p>Neden public: StormService bu mantığın 3-tipli bir kopyasını taşıyordu (KEYWORD/PING/HTTP)
     * ve PAGE/SCRIPTED/PAGESPEED tiplerini kaçırıyordu — geniş kesintide storm'a terfi eden bu
     * monitörler, bireysel alarmda ASLA mail almayacak müdürlere toplu alarm + toplu "düzeldi"
     * gönderiyordu. Kopya yerine tek kaynak: DOMAINMON-KRİTİK müdür istisnası da otomatik doğru gelir.
     */
    public static boolean teamOnlyRecipients(String alertType, String level) {
        return teamOnly(isStandaloneMon(alertType), level);
    }

    /** Olay farkındalıklı sürüm: bağlamdaki {@code team_id} damgasını da sayar (PORT/DNS bağımsız izleme, O-1). */
    public static boolean teamOnlyRecipients(AlertEvent e) {
        return teamOnly(isStandaloneEvent(e), e.getAlertLevel());
    }

    /** Domain monitör alarmı (DOMAINMON_*) için e-posta detay bağlamını EN GÜNCEL DomainCheck'ten kurar.
     *  Manuel resend + çözüm bildirimi zengin içerik (bitiş tarihi/registrar/kaynak/EPP kodları) göstersin diye:
     *  latestCheckRepo SERTİFİKA verisidir, domain monitörü orada yoktur → doğru kaynak domain_checks tablosudur. */
    private Map<String, Object> reconstructDomainContext(String domain) {
        if (domain == null) return null;
        var monitorOpt = domainMonitorRepo.findFirstByDomainOrderByIdAsc(domain);
        if (monitorOpt.isEmpty()) return null;
        var monitor = monitorOpt.get();
        var checkOpt = domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(monitor.getId());
        if (checkOpt.isEmpty()) return null;
        var c = checkOpt.get();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("domain", domain);
        if (monitor.getTeamId() != null) ctx.put("team_id", monitor.getTeamId());
        if (c.getDaysRemaining() != null) { ctx.put("days", c.getDaysRemaining()); ctx.put("days_remaining", c.getDaysRemaining()); }
        if (c.getExpiryDate() != null)  ctx.put("expiry_date", c.getExpiryDate());
        if (c.getRegistrar() != null)   ctx.put("registrar", c.getRegistrar());
        if (c.getSource() != null)      ctx.put("source", c.getSource());
        // DB'de "," ile bitişik saklanır — şablon/plain-text için ", " normalize edilir.
        if (c.getStatusCodes() != null && !c.getStatusCodes().isBlank())
            ctx.put("status_codes", normalizeCsv(c.getStatusCodes()));
        if (c.getNameservers() != null && !c.getNameservers().isBlank())
            ctx.put("nameservers", normalizeCsv(c.getNameservers()));
        if (c.getCheckedAt() != null)   ctx.put("checked_at", c.getCheckedAt());
        return ctx;
    }

    /** "a,b" / "a, b" karışık CSV'yi ", " ayraçlı normalize eder. */
    private static String normalizeCsv(String csv) {
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));
    }

    /** Alarmın "kime bildirildi" denetim izi. DİKKAT: Map.of null DEĞER kabul etmez (NPE) — name/email
     *  nullable kolonlar (yalnız role NOT NULL). Eskiden e-postası girilmemiş TEK bir kontak bile tüm
     *  listeyi "[]" yapıyordu (NPE catch'e düşüyordu) ve olay incelemesinde "bu alarm kime gitti?"
     *  sorusu sessizce cevapsız kalıyordu. Null-güvenli HashMap + boş alan yerine "" kullanılıyor. */
    private String serializeContacts(List<EscalationContact> contacts) {
        try {
            List<Map<String, String>> list = contacts.stream()
                    .map(c -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("name",  c.getName()  != null ? c.getName()  : "");
                        m.put("email", c.getEmail() != null ? c.getEmail() : "");
                        m.put("role",  c.getRole()  != null ? c.getRole()  : "");
                        return m;
                    })
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("Kontak listesi serileştirilemedi (notified_contacts boş kalacak): {}", e.toString());
            return "[]";
        }
    }

    private AlertThreshold defaultThreshold() {
        AlertThreshold t = new AlertThreshold();
        t.setWarningDays(30);
        t.setHighDays(15);
        t.setCriticalDays(7);
        t.setReAlertIntervalHours(24);
        return t;
    }

    /**
     * Re-alert kadans kararı (saf/test edilebilir): son alarmdan bu yana >= intervalHours saat geçti mi?
     * Eski davranış "farklı UTC takvim günü" idi → 23:59'da açılan bir alarm 00:00'da hemen tekrar alarmlıyordu,
     * ve admin'in {@code reAlertIntervalHours} ayarı hiç okunmuyordu. Artık rolling-saat penceresi (M10).
     */
    static boolean reAlertDue(String lastAlertIso, String nowIso, int intervalHours) {
        int iv = Math.max(1, intervalHours);
        try {
            LocalDateTime last = LocalDateTime.parse(lastAlertIso, LDT);
            LocalDateTime now  = LocalDateTime.parse(nowIso, LDT);
            return !now.isBefore(last.plusHours(iv));
        } catch (Exception e) {
            return true;   // ayrıştırılamazsa re-alert'e izin ver (bayat alarmın süresiz susmasını önle)
        }
    }

    /** {@link #reAlertDue}'nun DAKİKA eşi (tür bazlı geçersiz kılma, 2026-10-01; saf). Ayrıştırılamazsa true (aynı tercih). */
    static boolean reAlertDueMinutes(String lastAlertIso, String nowIso, int intervalMinutes) {
        int iv = Math.max(1, intervalMinutes);
        try {
            LocalDateTime last = LocalDateTime.parse(lastAlertIso, LDT);
            LocalDateTime now  = LocalDateTime.parse(nowIso, LDT);
            return !now.isBefore(last.plusMinutes(iv));
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Tür bazlı yeniden uyarı kararı (2026-10-01, opt-in): alarm tipinin ailesi için
     * {@code site.monitor.realert.<aile>-minutes} 0 ise (varsayılan) karar bugünkü {@link #reAlertDue} çağrısının
     * KENDİSİDİR — aynı argümanlarla, genel saat aralığıyla. Değer verilmişse yalnız o ailenin alarmı o dakika aralığıyla
     * hatırlatılır; diğer aileler etkilenmez. Kapı: {@code ReAlertIntervalOverrideTest}.
     */
    boolean reAlertDueFor(String alertType, String lastAlertIso, String nowIso, int globalHours) {
        int minutes = familyReAlertMinutes(alertType);
        return minutes > 0 ? reAlertDueMinutes(lastAlertIso, nowIso, minutes)
                           : reAlertDue(lastAlertIso, nowIso, globalHours);
    }

    /** Ailenin etkin yeniden uyarı aralığı (dk); 0 = geçersiz kılma yok (genel aralık). Ayar okunamazsa 0. */
    int familyReAlertMinutes(String alertType) {
        String key = ReAlertIntervals.keyForAlertType(alertType);
        if (key == null || appSettings == null) return 0;
        try {
            return ReAlertIntervals.effective(appSettings.getInt(key, 0));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * E9: İLK bildirimin yarıda kaldığına ancak olay bu kadar eskiyse hükmedilir. INITIAL gönderimi
     * (SMTP bağlan/oku/yaz + kontak webhook zaman aşımları) sürerken paralel bir yol — açılış catch-up'ı,
     * yeni envanterin anında kontrolü, rolling deploy'da hâlâ çalışan eski pod — aynı olayı damgasız
     * görüp İKİNCİ bir INITIAL göndermesin.
     */
    static final java.time.Duration INITIAL_SEND_GRACE = java.time.Duration.ofMinutes(5);

    /**
     * E9 (saf/test edilebilir): açık sertifika alarmının İLK bildirimi hiç tamamlanmadı mı?
     * {@code lastReAlertAt} INITIAL / ESCALATION / DAILY_REALERT / manuel gönderimin HEPSİNDE damgalanır;
     * null olması "olay kaydedildi ama hiçbir kanal duyurmadı" demektir (izleme yolu, 2026-08-24).
     * Olay {@link #INITIAL_SEND_GRACE}'ten gençse gönderim hâlâ sürüyor olabilir → false.
     */
    static boolean initialNotificationMissing(AlertEvent e, String nowIso) {
        if (e.getLastReAlertAt() != null) return false;
        if (e.getCreatedAt() == null) return true;
        try {
            LocalDateTime created = LocalDateTime.parse(e.getCreatedAt(), LDT);
            LocalDateTime now     = LocalDateTime.parse(nowIso, LDT);
            return !now.isBefore(created.plus(INITIAL_SEND_GRACE));
        } catch (Exception ex) {
            return true;   // ayrıştırılamazsa gönder (reAlertDue ile aynı tercih: süresiz susma yok)
        }
    }

    /** Aktif eşikteki re-alert aralığı (saat). Ayar okunmadığı için "ölü"ydü; M10 canlandırır. Yoksa 24. */
    private int reAlertIntervalHours() {
        return thresholdRepo.findFirstByActiveTrue()
                .map(t -> t.getReAlertIntervalHours() != null ? t.getReAlertIntervalHours() : 24)
                .orElse(24);
    }

    private int levelValue(String level) {
        return LEVEL_ORDER.getOrDefault(level, 0);
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    // ── Çok pod sahiplenmeleri (2026-10-21, R1 / MP-03, MP-07) ────────────────────────────────────────────────────

    /** Sertifika alarmı açılış kilidinin süresi — izleme yolunun ({@code MonitoringOutageService.withLock}) aynısı. */
    static final int ALERT_KEY_LOCK_TTL_SECONDS = 120;

    /** Açılış kilidi adı — izleme yoluyla AYNI biçim ({@code mon-alert:<tür>:<alan>}); sertifika türleri onunla çakışmaz. */
    static String alertKeyLockName(String alertType, String domain) {
        return "mon-alert:" + alertType + ":" + domain;
    }

    /** Açılış kilidi sonucu: alındı / başkasında (ya da geçici DB hatası → güvenli taraf: atla) / tablo yok (eski davranış). */
    enum KeyLock { ACQUIRED, BUSY, UNAVAILABLE }

    /**
     * {@code scheduler_lock} satırını alır ({@code SchedulerService.tryAcquireSchedulerLock} kuralları): süresi dolmuş satır
     * silinir, INSERT tekil anahtarla yarışı çözer. Tablo yoksa ({@code BadSqlGrammarException}, ilk açılış) kilitsiz devam
     * (tek pod geri düşüşü); başka her hata bu turu ATLATIR — çift alarm, bir turu kaçırmaktan pahalıdır.
     */
    KeyLock acquireAlertKeyLock(String name) {
        if (jdbcTemplate == null) return KeyLock.UNAVAILABLE;
        try {
            Instant n = Instant.now();
            jdbcTemplate.update("DELETE FROM scheduler_lock WHERE name = ? AND locked_until < ?", name, ISO.format(n));
            jdbcTemplate.update("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)",
                    name, lockOwner, ISO.format(n.plusSeconds(ALERT_KEY_LOCK_TTL_SECONDS)));
            return KeyLock.ACQUIRED;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return KeyLock.BUSY;
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            log.warn("Alarm anahtar kilidi tablosu yok (HA degraded): {}", e.getMessage());
            return KeyLock.UNAVAILABLE;
        } catch (Exception e) {
            if (!StormService.isUniqueViolation(e)) {
                log.warn("Alarm anahtar kilidi alınamadı — {} bu tur atlanıyor (güvenli taraf): {}", name, e.getMessage());
            }
            return KeyLock.BUSY;
        }
    }

    void releaseAlertKeyLock(String name) {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.update("DELETE FROM scheduler_lock WHERE name = ? AND locked_by = ?", name, lockOwner);
        } catch (Exception e) {
            log.warn("Alarm anahtar kilidi bırakılamadı '{}': {}", name, e.getMessage());
        }
    }

    /** Yeniden uyarı / eskalasyon / telafi sahiplenmesi — damga hâlâ görülen değerdeyse ve olay açıksa ileri alınır. */
    static final String SQL_REALERT_CLAIM =
            "UPDATE alert_events SET last_re_alert_at = ? WHERE id = ? AND resolved = FALSE AND last_re_alert_at = ?";
    /** Damgası hiç yazılmamış olay için aynı sahiplenme (ayrı ifade: PostgreSQL'de {@code ? IS NULL} tür belirsiz). */
    static final String SQL_REALERT_CLAIM_UNSTAMPED =
            "UPDATE alert_events SET last_re_alert_at = ? WHERE id = ? AND resolved = FALSE AND last_re_alert_at IS NULL";

    /**
     * Gönderimden ÖNCE damgayı sahiplenir ({@code StormService.claimDailyRealert} deseni). {@code true} = bu yol gönderir;
     * {@code false} = başka yol / pod sahiplendi, olay kapandı ya da yazılamadı — GÖNDERİLMEZ (yazılamadıysa hiçbir şey
     * gitmedi; sonraki tur yeniden dener). Veritabanı yoksa (birim testi) ya da kimliksiz olayda her zaman true.
     */
    boolean claimReAlert(Long id, String seen, String at, String what) {
        if (jdbcTemplate == null || id == null) return true;
        int rows;
        try {
            rows = seen == null
                    ? jdbcTemplate.update(SQL_REALERT_CLAIM_UNSTAMPED, at, id)
                    : jdbcTemplate.update(SQL_REALERT_CLAIM, at, id, seen);
        } catch (Exception e) {
            log.warn("Alarm #{} {} bildirimi sahiplenilemedi — bu tur gönderilmiyor (sonraki tur yeniden dener): {}",
                    id, what, e.getMessage());
            return false;
        }
        if (rows != 1) {
            log.info("Alarm #{} {} bildirimi gönderilmedi — damga bu arada ilerledi ya da olay kapandı (başka yol/pod)", id, what);
            return false;
        }
        return true;
    }

    /** Terfi sahiplenmesi: damga VE seviye tek koşullu yazımda (inceleme F2). */
    static final String SQL_ESCALATION_CLAIM = "UPDATE alert_events SET last_re_alert_at = ?, alert_level = ? "
            + "WHERE id = ? AND resolved = FALSE AND last_re_alert_at = ? AND alert_level = ?";
    static final String SQL_ESCALATION_CLAIM_UNSTAMPED = "UPDATE alert_events SET last_re_alert_at = ?, alert_level = ? "
            + "WHERE id = ? AND resolved = FALSE AND last_re_alert_at IS NULL AND alert_level = ?";

    /**
     * Terfi (ESCALATION) sahiplenmesi — {@link #claimReAlert} gibi, ama YENİ seviye de aynı koşullu yazımda yazılır ve görülen
     * seviye koşula girer. Yalnız damgayla sahiplenmede ikinci pod, birincinin damgasını okuyup (seviye henüz kaydedilmemişken)
     * kendi damgasıyla yeniden sahiplenebiliyor ve ikinci ESCALATION'ı gönderiyordu. Hata / kayıp → false: seviye, onay ve damga
     * değişmez, sonraki tur yeniden dener. Görülen seviye bilinmiyorsa (NULL) yalnız damga sahiplenmesi.
     */
    boolean claimEscalation(Long id, String seenStamp, String seenLevel, String newLevel, String at) {
        if (jdbcTemplate == null || id == null) return true;
        if (seenLevel == null || newLevel == null) return claimReAlert(id, seenStamp, at, "ESCALATION");
        int rows;
        try {
            rows = seenStamp == null
                    ? jdbcTemplate.update(SQL_ESCALATION_CLAIM_UNSTAMPED, at, newLevel, id, seenLevel)
                    : jdbcTemplate.update(SQL_ESCALATION_CLAIM, at, newLevel, id, seenStamp, seenLevel);
        } catch (Exception e) {
            log.warn("Alarm #{} ESCALATION bildirimi sahiplenilemedi — seviye değişmedi, sonraki tur yeniden dener: {}",
                    id, e.getMessage());
            return false;
        }
        if (rows != 1) {
            log.info("Alarm #{} ESCALATION bildirimi gönderilmedi — damga / seviye bu arada değişti ya da olay kapandı (başka yol/pod)", id);
            return false;
        }
        return true;
    }

    /** Terfi öncesi değerler — terfi bildirimi istisnayla düşerse geri yüklenir (inceleme F2 / S-6). */
    record EscalationUndo(String level, String message, Boolean acknowledged, String acknowledgedAt, String acknowledgedBy,
                          String lastReAlertAt, Integer daysRemaining, String notAfter, String notifiedContacts) {
        static EscalationUndo of(AlertEvent e) {
            return new EscalationUndo(e.getAlertLevel(), e.getMessage(), e.getAcknowledged(), e.getAcknowledgedAt(),
                    e.getAcknowledgedBy(), e.getLastReAlertAt(), e.getDaysRemaining(), e.getNotAfter(), e.getNotifiedContacts());
        }

        void applyTo(AlertEvent e) {
            e.setAlertLevel(level);
            e.setMessage(message);
            e.setAcknowledged(acknowledged);
            e.setAcknowledgedAt(acknowledgedAt);
            e.setAcknowledgedBy(acknowledgedBy);
            e.setLastReAlertAt(lastReAlertAt);
            e.setDaysRemaining(daysRemaining);
            e.setNotAfter(notAfter);
            e.setNotifiedContacts(notifiedContacts);
        }
    }

    /**
     * Terfi bildirimi gönderilemedi: seviye, onay ve damga terfi ÖNCESİNE döner — KOŞULLU (yalnız bu sahiplenmenin damgası hâlâ
     * duruyorsa ve olay açıksa; arada başka yol ilerlettiyse dokunulmaz). Sonraki tur terfiyi yeniden görür ve ESCALATION'ı
     * gönderir. Veritabanı yoksa (birim testi) bugünkü davranış: yalnız istisna yayılır.
     */
    boolean revertEscalation(AlertEvent event, EscalationUndo undo, String claimedAt) {
        return revertEscalation(event, undo, claimedAt, null);
    }

    /** Geri alma da düşen terfinin izi (inceleme N1) — {@code notification_logs} ESCALATION satırı; sınıf adı eklenir. */
    static final String STATUS_ESCALATION_REVERT_FAILED =
            "FAILED: terfi bildirimi gönderilemedi ve terfi geri alınamadı — seviye yükseltilmiş kaldı, ESCALATION gitmedi";

    /**
     * Terfiyi geri alır (koşullu — yalnız bu sahiplenmenin damgası duruyorsa). {@code cause}: terfiyi düşüren hata. Geri alma
     * da düşerse (veritabanı erişilemiyor) sessiz kalmaz: WARN + {@code notification_logs} FAILED satırı
     * ({@link #STATUS_ESCALATION_REVERT_FAILED}) — seviye yükseltilmiş kaldığı için sonraki tur terfiyi göremez, bildirim gitmez.
     * Geri alındıysa true; damga bu arada ilerlediyse (başka yol / pod sahiplendi) ya da olay kapandıysa false (iz yok).
     */
    boolean revertEscalation(AlertEvent event, EscalationUndo undo, String claimedAt, Exception cause) {
        if (jdbcTemplate == null || event == null || event.getId() == null || undo == null) return false;
        try {
            int n = jdbcTemplate.update("UPDATE alert_events SET alert_level = ?, message = ?, acknowledged = ?, acknowledged_at = ?, "
                    + "acknowledged_by = ?, last_re_alert_at = ?, days_remaining = ?, not_after = ?, notified_contacts = ? "
                    + "WHERE id = ? AND resolved = FALSE AND last_re_alert_at = ?",
                    undo.level(), undo.message(), Boolean.TRUE.equals(undo.acknowledged()), undo.acknowledgedAt(),
                    undo.acknowledgedBy(), undo.lastReAlertAt(), undo.daysRemaining(), undo.notAfter(), undo.notifiedContacts(),
                    event.getId(), claimedAt);
            if (n == 1) undo.applyTo(event);
            return n == 1;
        } catch (Exception e) {
            log.warn("Alarm #{} terfisi geri alınamadı — seviye yükseltilmiş kaldı, ESCALATION gitmedi ve sonraki tur yeniden "
                    + "göndermeyecek: {}", event.getId(), e.getMessage());
            String why = cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName();
            try {
                saveLog(event.getId(), "-", "", "[Site Monitor ⬆ ESKALASYON] " + event.getDomain() + " — " + event.getAlertType(),
                        "", NotificationLog.fit(STATUS_ESCALATION_REVERT_FAILED + " (" + why + ")", NotificationLog.VARCHAR_MAX),
                        "SKIPPED", "ESCALATION");
            } catch (Exception logEx) {
                log.error("Alarm #{} terfi bildirimi kaybı günlüğe de yazılamadı: {}", event.getId(), logEx.getMessage());
            }
            return false;
        }
    }

    /** Gönderim istisnayla düştü: sahiplenilen damga geri alınır (koşullu — arada başkası ilerlettiyse dokunulmaz). */
    void releaseReAlert(Long id, String claimedAt, String seen) {
        if (jdbcTemplate == null || id == null) return;
        try {
            if (seen == null) {
                jdbcTemplate.update("UPDATE alert_events SET last_re_alert_at = NULL WHERE id = ? AND last_re_alert_at = ?",
                        id, claimedAt);
            } else {
                jdbcTemplate.update("UPDATE alert_events SET last_re_alert_at = ? WHERE id = ? AND last_re_alert_at = ?",
                        seen, id, claimedAt);
            }
        } catch (Exception e) {
            log.warn("Alarm #{} bildirim sahiplenmesi geri alınamadı: {}", id, e.getMessage());
        }
    }

    // ── Kalıcı çözüm bildirimi işareti (MP-07) ──────────────────────────────────────────────────────────────────────

    public static final String RESOLUTION_NOTIFY_PENDING = "PENDING";
    public static final String RESOLUTION_NOTIFY_SENDING = "SENDING";
    public static final String RESOLUTION_NOTIFY_DONE = "DONE";
    public static final String RESOLUTION_NOTIFY_UNKNOWN = "UNKNOWN";
    public static final String RESOLUTION_NOTIFY_EXPIRED = "EXPIRED";
    /** Sahiplenilmiş gönderim uygulama hatasıyla düştü (inceleme S-5) — yeniden denenmez, iz {@code notification_logs}'ta. */
    public static final String RESOLUTION_NOTIFY_FAILED = "FAILED";

    /** Süpürücü PENDING satıra bu kadar dokunmaz — normal eşzamansız iş saniyeler içinde gönderir. */
    static final java.time.Duration RESOLUTION_SWEEP_MIN_AGE = java.time.Duration.ofMinutes(2);
    /** Bundan eski PENDING artık gönderilmez (geç "çözüldü" gürültüdür) — EXPIRED + iz. */
    static final java.time.Duration RESOLUTION_SWEEP_MAX_AGE = java.time.Duration.ofHours(24);
    /** SENDING bu kadar eskiyse gönderen yarıda durmuştur — sonuç bilinmez: UNKNOWN + iz, yeniden gönderim YOK. */
    static final java.time.Duration RESOLUTION_SENDING_STALE = java.time.Duration.ofMinutes(10);
    /** EXPIRED / UNKNOWN taramasının geriye bakışı ({@code idx_ae_resolved_at} aralığı). */
    static final java.time.Duration RESOLUTION_SWEEP_LOOKBACK = java.time.Duration.ofDays(7);
    /** Tur başına en çok satır (her adım için). */
    static final int RESOLUTION_SWEEP_BATCH = 100;

    static final String STATUS_RESOLUTION_UNKNOWN =
            "FAILED: sonuç bilinmiyor — çözüm bildirimi gönderilirken pod durdu; çift gönderim olmasın diye yeniden gönderilmedi";
    static final String STATUS_RESOLUTION_EXPIRED =
            "SKIPPED: çözüm bildirimi 24 saat içinde gönderilemedi (pod'lar kapalıydı) — artık gönderilmiyor";

    /**
     * Çözüm kapısı + kalıcı işaret TEK koşullu UPDATE'te ({@code notify} = bireysel çözüm postası gidecek). İşaret kolonu
     * henüz yoksa ({@code BadSqlGrammarException}) ya da veritabanı yoksa (birim testi) / bildirim yoksa bugünkü
     * {@link AlertEventRepository#markResolvedIfOpen}. 1 = bu çağrı kapattı, 0 = başkası kapatmıştı.
     */
    int markResolvedIfOpenNotify(AlertEvent event, String at, String by, boolean notify) {
        Long id = event.getId();
        if (notify && jdbcTemplate != null) {
            try {
                int n = jdbcTemplate.update("UPDATE alert_events SET resolved = TRUE, resolved_at = ?, resolved_by = ?, "
                        + "resolution_notify = 'PENDING', resolution_notify_at = ? WHERE id = ? AND resolved = FALSE",
                        at, by, at, id);
                // Bellekteki nesne de işaretli: eşzamansız iş işaretin BU kapanışça yazıldığını bilir (inceleme F3). Salt okunur
                // kolon — varlık kaydı onu yazmaz.
                if (n == 1) event.setResolutionNotify(RESOLUTION_NOTIFY_PENDING);
                return n;
            } catch (org.springframework.jdbc.BadSqlGrammarException e) {
                log.debug("Çözüm bildirimi işareti yazılamadı (kolon yok?) — işaretsiz kapanış: {}", e.getMessage());
            }
        }
        return alertEventRepo.markResolvedIfOpen(id, at, by);
    }

    /** Kapanışın yazdığı işaret kaydın döndürdüğü nesneye de taşınır (salt okunur kolon; birleştirme kopyası olabilir). */
    private static void keepResolutionMarker(AlertEvent from, AlertEvent to) {
        if (from != null && to != null && to != from && from.getResolutionNotify() != null) {
            to.setResolutionNotify(from.getResolutionNotify());
        }
    }

    /** Sahiplenme sonucu: {@code send} = gönder; {@code at} dolu = işaret SENDING'e alındı (bitince DONE yazılır). */
    record ResolutionClaim(boolean send, String at) {
        static final ResolutionClaim LEGACY = new ResolutionClaim(true, null);
        static final ResolutionClaim LOST = new ResolutionClaim(false, null);
    }

    /**
     * PENDING → SENDING. Kazanırsa gönder; satır işaretsizse (NULL — eski pod / işaret yazılamadı) eski davranış: gönder;
     * işaret başka durumdaysa (SENDING / DONE / UNKNOWN / EXPIRED / FAILED) başkası sahiplenmiş: GÖNDERME. Okuma/yazma
     * hatasında: işaretin YAZILDIĞI biliniyorsa ({@code marked} — bu kapanış yazdı, ya da süpürücü) GÖNDERİLMEZ, PENDING kalır
     * ve süpürücü 2 dk sonra BİR kez gönderir (inceleme F3: eskiden işaretsiz gönderiliyor, süpürücü ikinci kez gönderiyordu);
     * bilinmiyorsa eski davranış (en az bir kez).
     */
    ResolutionClaim claimResolutionNotify(Long id, boolean marked) {
        if (jdbcTemplate == null || id == null) return ResolutionClaim.LEGACY;
        String at = claimToken();
        try {
            int n = jdbcTemplate.update("UPDATE alert_events SET resolution_notify = 'SENDING', resolution_notify_at = ? "
                    + "WHERE id = ? AND resolution_notify = 'PENDING'", at, id);
            if (n == 1) return new ResolutionClaim(true, at);
            List<String> cur = jdbcTemplate.queryForList("SELECT resolution_notify FROM alert_events WHERE id = ?", String.class, id);
            if (cur.isEmpty() || cur.get(0) == null) return ResolutionClaim.LEGACY;
            return ResolutionClaim.LOST;
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            return ResolutionClaim.LEGACY;
        } catch (Exception e) {
            // Yazım veritabanında GEÇTİ ama istemci hatayı gördüyse (yanıt kayboldu) işaret bizim damgamızla SENDING'dir — yeniden
            // okunur ve sahiplenme bizimse gönderilir (inceleme D4: eskiden satır 10 dk sonra "pod durdu" UNKNOWN oluyor, posta
            // kayboluyordu). Damga benzersiz ({@link #claimToken}) — aynı saniyede başka pod'un sahiplenmesiyle karışmaz.
            ResolutionClaim mine = ownResolutionClaim(id, at);
            if (mine != null) return mine;
            if (marked) {
                log.warn("Çözüm bildirimi işareti sahiplenilemedi (olay {}) — gönderim süpürücüye bırakıldı: {}", id, e.getMessage());
                return ResolutionClaim.LOST;
            }
            log.warn("Çözüm bildirimi işareti sahiplenilemedi (olay {}) — işaretsiz gönderiliyor: {}", id, e.getMessage());
            return ResolutionClaim.LEGACY;
        }
    }

    /**
     * Benzersiz sahiplenme damgası: ISO saniye + milisaniye + rastgele ek ({@code 2026-10-22T10:00:00.123#a1b2}, ≤ 30) — sözlük
     * sırası saniyeye kadar zamanla aynı (süpürücünün {@code resolution_notify_at < ?} karşılaştırması bozulmaz).
     */
    private String claimToken() {
        Instant t = Instant.now();
        return ISO.format(t) + String.format(java.util.Locale.ROOT, ".%03d#%04x", t.toEpochMilli() % 1000,
                java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000));
    }

    /** Sahiplenme yazımı hata verdikten sonra: satır bu damgayla SENDING ise sahiplenme bizimdir (yazım aslında geçti). */
    private ResolutionClaim ownResolutionClaim(Long id, String at) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT resolution_notify, resolution_notify_at FROM alert_events WHERE id = ?", id);
            if (!rows.isEmpty() && RESOLUTION_NOTIFY_SENDING.equals(rows.get(0).get("resolution_notify"))
                    && at.equals(rows.get(0).get("resolution_notify_at"))) {
                log.info("Çözüm bildirimi sahiplenmesi hata verdi ama yazılmıştı (olay {}) — gönderiliyor", id);
                return new ResolutionClaim(true, at);
            }
        } catch (Exception ignore) {
            // okunamadı — çağıran mevcut kurala düşer (işaret biliniyorsa süpürücü)
        }
        return null;
    }

    /** Uygulama hatasıyla biten çözüm postası — iz metni (sınıf adı eklenir). */
    static final String STATUS_RESOLUTION_FAILED =
            "FAILED: çözüm bildirimi yarıda kaldı (uygulama hatası) — gitmemiş olabilir, otomatik yeniden denenmez";

    /**
     * Sahiplenilmiş çözüm postasını gönderir ve işareti SONUÇLANDIRIR (inceleme S-5 — eşzamansız iş ve süpürücü AYNI kural):
     * normal biterse DONE; gövde istisnayla düşerse FAILED + {@code notification_logs} izi ({@link #STATUS_RESOLUTION_FAILED}).
     * Eskiden eşzamansız yolda istisna SENDING bırakıyor (10 dk sonra yanlış "pod durdu" UNKNOWN'u), süpürücü yolunda DONE
     * yazıyordu. FAILED yeniden denenmez (uygulama hatası tekrarlanırdı — sonsuz döngü yok); iz neden gitmediğini söyler.
     */
    boolean deliverClaimedResolution(AlertEvent event, String resolvedBy, String trigger, String claimAt) {
        try {
            sendResolutionNotification(event, resolvedBy, trigger, true);
        } catch (RuntimeException e) {
            log.error("Çözüm bildirimi yarıda kaldı (olay {}): {}", event.getId(), e.toString());
            failResolutionNotify(event.getId(), claimAt, e);
            return false;
        }
        finishResolutionNotify(event.getId(), claimAt);
        return true;
    }

    /** SENDING → FAILED + iz (yalnız bu sahiplenmenin satırı). */
    void failResolutionNotify(Long id, String claimedAt, Exception cause) {
        if (jdbcTemplate == null || id == null || claimedAt == null) return;
        try {
            if (jdbcTemplate.update("UPDATE alert_events SET resolution_notify = 'FAILED', resolution_notify_at = ? "
                    + "WHERE id = ? AND resolution_notify = 'SENDING' AND resolution_notify_at = ?", now(), id, claimedAt) == 1) {
                traceResolutionDecision(id, NotificationLog.fit(STATUS_RESOLUTION_FAILED + " (" + cause.getClass().getSimpleName() + ")",
                        NotificationLog.VARCHAR_MAX));
            }
        } catch (Exception e) {
            log.warn("Çözüm bildirimi işareti FAILED yazılamadı (olay {}): {}", id, e.getMessage());
        }
    }

    /** SENDING → DONE (yalnız bu sahiplenmenin satırı). */
    void finishResolutionNotify(Long id, String claimedAt) {
        if (jdbcTemplate == null || id == null || claimedAt == null) return;
        try {
            jdbcTemplate.update("UPDATE alert_events SET resolution_notify = 'DONE', resolution_notify_at = ? "
                    + "WHERE id = ? AND resolution_notify = 'SENDING' AND resolution_notify_at = ?", now(), id, claimedAt);
        } catch (Exception e) {
            log.warn("Çözüm bildirimi işareti DONE yazılamadı (olay {}): {}", id, e.getMessage());
        }
    }

    /**
     * {@code ResolutionNotifySweeper}'ın gövdesi (kümede tek pod, kilit altında). Üç adım, her biri en çok
     * {@link #RESOLUTION_SWEEP_BATCH} satır, hepsi {@code resolved_at} aralığıyla ({@code idx_ae_resolved_at}):
     * <ol>
     *   <li>{@link #RESOLUTION_SENDING_STALE}'den eski SENDING → UNKNOWN + {@code notification_logs} izi (yeniden gönderim YOK —
     *       gönderen pod postayı atıp damgalamadan ölmüş olabilir; çift posta, sonucu bilinmeyen tek postadan kötü).</li>
     *   <li>{@link #RESOLUTION_SWEEP_MAX_AGE}'den eski PENDING → EXPIRED + iz.</li>
     *   <li>{@link #RESOLUTION_SWEEP_MIN_AGE} – {@link #RESOLUTION_SWEEP_MAX_AGE} arası PENDING → sahiplen, gönder, DONE (BİR kez:
     *       sahiplenme koşullu).</li>
     * </ol>
     * Gönderilen çözüm bildirimi sayısını döner.
     */
    public int deliverPendingResolutions(Instant now) {
        if (jdbcTemplate == null) return 0;
        String lookback = ISO.format(now.minus(RESOLUTION_SWEEP_LOOKBACK));
        String maxAge = ISO.format(now.minus(RESOLUTION_SWEEP_MAX_AGE));
        String minAge = ISO.format(now.minus(RESOLUTION_SWEEP_MIN_AGE));
        String staleBefore = ISO.format(now.minus(RESOLUTION_SENDING_STALE));
        String nowIso = ISO.format(now);

        List<Long> stale = jdbcTemplate.queryForList("SELECT id FROM alert_events WHERE resolved_at >= ? "
                + "AND resolution_notify = 'SENDING' AND resolution_notify_at < ? ORDER BY id LIMIT " + RESOLUTION_SWEEP_BATCH,
                Long.class, lookback, staleBefore);
        for (Long id : stale) {
            if (jdbcTemplate.update("UPDATE alert_events SET resolution_notify = 'UNKNOWN', resolution_notify_at = ? "
                    + "WHERE id = ? AND resolution_notify = 'SENDING' AND resolution_notify_at < ?", nowIso, id, staleBefore) == 1) {
                traceResolutionDecision(id, STATUS_RESOLUTION_UNKNOWN);
                log.warn("Çözüm bildirimi yarıda kalmış (olay {}) — sonuç bilinmiyor, yeniden gönderilmedi", id);
            }
        }

        List<Long> expired = jdbcTemplate.queryForList("SELECT id FROM alert_events WHERE resolved_at >= ? AND resolved_at < ? "
                + "AND resolution_notify = 'PENDING' ORDER BY id LIMIT " + RESOLUTION_SWEEP_BATCH, Long.class, lookback, maxAge);
        for (Long id : expired) {
            if (jdbcTemplate.update("UPDATE alert_events SET resolution_notify = 'EXPIRED', resolution_notify_at = ? "
                    + "WHERE id = ? AND resolution_notify = 'PENDING'", nowIso, id) == 1) {
                traceResolutionDecision(id, STATUS_RESOLUTION_EXPIRED);
                log.warn("Çözüm bildirimi 24 saat içinde gönderilemedi (olay {}) — artık gönderilmiyor", id);
            }
        }

        List<Long> due = jdbcTemplate.queryForList("SELECT id FROM alert_events WHERE resolved_at >= ? AND resolved_at <= ? "
                + "AND resolution_notify = 'PENDING' ORDER BY id LIMIT " + RESOLUTION_SWEEP_BATCH, Long.class, maxAge, minAge);
        int sent = 0;
        for (Long id : due) {
            ResolutionClaim claim = claimResolutionNotify(id, true);
            if (claim.at() == null) continue;   // yalnız GERÇEK sahiplenme gönderir (işaretsiz / kaybedilmiş satır değil)
            AlertEvent e;
            try {
                e = alertEventRepo.findById(id).orElse(null);
            } catch (RuntimeException ex) {
                failResolutionNotify(id, claim.at(), ex);
                continue;
            }
            if (e == null || !Boolean.TRUE.equals(e.getResolved())) {
                finishResolutionNotify(id, claim.at());   // satır yok / yeniden açıldı — gönderilecek bir şey yok
                continue;
            }
            String by = e.getResolvedBy();
            boolean manual = by != null && !by.isBlank() && !"system".equalsIgnoreCase(by.trim());
            if (deliverClaimedResolution(e, manual ? by : "Sistem (otomatik)", manual ? "MANUAL_RESOLVE" : "RESOLUTION", claim.at())) {
                sent++;
                log.info("Çözüm bildirimi kalıcı işaretten gönderildi (önceki iş düşmüştü): olay {} {} [{}]",
                        id, e.getDomain(), e.getAlertType());
            }
        }
        return sent;
    }

    /** Süpürücü kararının izi — olayın bildirim günlüğüne tek satır (çözüm konusuyla). */
    private void traceResolutionDecision(Long id, String status) {
        try {
            AlertEvent e = alertEventRepo.findById(id).orElse(null);
            String subject = e == null ? "" : "[Site Monitor ✅ ÇÖZÜLDÜ] " + e.getDomain()
                    + " — " + resolvedTypeLabel(e.getAlertType()) + " sorunu giderildi";
            saveLog(id, "-", "", subject, "", status, "SKIPPED", "RESOLUTION");
        } catch (Exception ex) {
            log.warn("Çözüm bildirimi kararı günlüğe yazılamadı (olay {}): {}", id, ex.getMessage());
        }
    }
}
