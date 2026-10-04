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
import org.springframework.transaction.annotation.Transactional;

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
                Integer domainTier = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTier).orElse(null);
                String alertLevel = determineAlertLevel(result, alertType, thresholds.forTier(domainTier));
                if (alertLevel == null) continue;

                Long domainTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
                Long ugTeamId     = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null);

                Integer daysRemaining = toInt(result.get("days_remaining"));
                String message = buildMessage(domain, alertType, alertLevel, daysRemaining)
                        + securityEvidence(alertType, result);

                Optional<AlertEvent> existing = Optional.ofNullable(openAlertByKey.get(domain + "|" + alertType));

                if (existing.isEmpty()) {
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
                    alertEventRepo.save(event);

                } else {
                    AlertEvent event = existing.get();
                    if (event.getTeamId() == null && domainTeamId != null) {
                        event.setTeamId(domainTeamId);          // eski (damgasız) açık olayları tek seferlik geri doldur
                        alertEventRepo.save(event);
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
                        event.setLastReAlertAt(now());
                        event = alertEventRepo.save(event);

                        sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType, message,
                                "", event.getId(), "ESCALATION", daysRemaining, result);

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
                            List<EscalationContact> contacts = recipientsNow(sendLevel, domainTeamId, ugTeamId, event.getId());
                            sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, sendLevel, alertType,
                                    sendMessage, "", event.getId(), "INITIAL", daysRemaining, result);
                            event.setNotifiedContacts(serializeContacts(contacts));
                            event.setLastReAlertAt(now());
                            event.setDaysRemaining(daysRemaining);
                            if (notAfterOf(result) != null) event.setNotAfter(notAfterOf(result));
                            event.setMessage(sendMessage);
                            alertEventRepo.save(event);
                            log.warn("🔴 Yarıda kalmış ilk sertifika bildirimi tamamlandı: {} [{}] — alarm {} tarihinde açılmıştı",
                                    domain, alertType, event.getCreatedAt());
                            continue;
                        }
                        String lastAlertTime = event.getLastReAlertAt() != null
                                ? event.getLastReAlertAt() : event.getCreatedAt();
                        if (reAlertDueFor(alertType, lastAlertTime, now(), reAlertIv)) {
                            List<EscalationContact> contacts = recipientsNow(sendLevel, domainTeamId, ugTeamId, event.getId());
                            sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, sendLevel, alertType,
                                    "[RE-ALERT] " + sendMessage, "[RE-ALERT] ",
                                    event.getId(), "DAILY_REALERT", daysRemaining, result);

                            event.setLastReAlertAt(now());
                            event.setRealertCount((event.getRealertCount() == null ? 0 : event.getRealertCount()) + 1);
                            event.setDaysRemaining(daysRemaining);
                            if (notAfterOf(result) != null) event.setNotAfter(notAfterOf(result));
                            event.setMessage(sendMessage);
                            alertEventRepo.save(event);
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

    @Transactional
    /** Geriye uyumlu: notsuz onay (eski çağrılar / not gerektirmeyen yollar). */
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
        event.setAcknowledged(true);
        event.setAcknowledgedBy(acknowledgedBy);
        event.setAcknowledgedAt(now());
        if (note != null && !note.isBlank()) event.setAcknowledgedNote(note.trim());
        return alertEventRepo.save(event);
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
        return out;
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

        // Count actual recipients (team emails + contacts, deduped, exclusions applied) — same logic as sendCombinedAlert
        List<String> teamEmails = collectTeamEmails(domainTeamId, ugTeamId, targets.stampedGroupId());
        Set<String> seen = new HashSet<>();
        int recipientCount = 0;
        for (String e : teamEmails) {
            if (e != null && !e.isBlank() && !excluded.contains(e.trim().toLowerCase())
                    && seen.add(e.trim().toLowerCase())) recipientCount++;   // D8
        }
        for (EscalationContact c : contacts) {
            if (c.getEmail() != null && !c.getEmail().isBlank()
                    && seen.add(c.getEmail().trim().toLowerCase())) recipientCount++;
        }
        if (recipientCount == 0 && !excluded.isEmpty()) {
            // Kullanıcı pop-up'ta TÜM alıcıları çıkardıysa DB'ye yazmadan reddet (→ 400).
            // Doğal sıfır-alıcı durumu (kontak/takım maili hiç yok) ESKİ davranışında kalır:
            // queued döner, sendCombinedAlert boş listeyle gönderimi zaten atlar.
            throw new IllegalArgumentException("Tüm alıcılar hariç tutuldu — en az bir alıcı seçin");
        }

        // Quick DB write — commits before async dispatch
        event.setNotifiedContacts(serializeContacts(contacts));
        event.setLastReAlertAt(now());
        alertEventRepo.save(event);

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
     * Background SMTP + webhook dispatch. Runs on certCheckExecutor pool.
     * Does not hold DB connection from the originating HTTP request.
     */
    @Async("certCheckExecutor")
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
            Map<String, Object> certContext;
            if (isDomainMon(alertType)) {
                certContext = reconstructDomainContext(domain);
            } else if (TYPE_DNS_CHANGED.equals(alertType)) {
                // D-b2: olayı AÇAN izlemenin kaydı (günlük yeniden uyarıyla aynı seçim).
                Long opener = alertEventId == null ? null
                        : alertEventRepo.findById(alertEventId).map(EscalationService::contextMonitorId).orElse(null);
                certContext = DnsCheckerService.changeCtxOf(
                        DnsCheckerService.lastChangedRecord(dnsRecordRepo, domain, opener));
                if (certContext.isEmpty()) certContext = null;
            } else if (MONITORING_ALERT_TYPES.contains(alertType)) {
                certContext = null;
            } else {
                certContext = latestCheckRepo.findById(domain).map(this::latestToCertContext).orElse(null);
            }
            Integer freshDays     = certContext != null ? toInt(certContext.get("days_remaining")) : null;
            Integer effectiveDays = freshDays != null ? freshDays : daysRemainingFallback;
            String  freshMessage  = TYPE_DNS_CHANGED.equals(alertType) && certContext != null
                    ? monitoringMessage(domain, alertType, alertLevel, certContext)   // eski→yeni değerli zengin mesaj
                    : buildMessage(domain, alertType, alertLevel, effectiveDays);
            sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                    freshMessage, "[RE-ALERT] ", alertEventId, "MANUAL",
                    effectiveDays, certContext, excludeEmails == null ? Set.of() : excludeEmails,
                    excludeUsernames == null ? Set.of() : excludeUsernames);
        } catch (Exception e) {
            log.error("Async reNotify failed for alertEventId={}: {}", alertEventId, e.getMessage(), e);
        }
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
        for (AlertEvent event : openAlerts) {
            // İzleme tiplerinin kadansının sahibi ilgili sweep'lerdir; restart
            // sonrası ilk sweep doğrulamadan bayat re-alert atılmasın.
            if (MONITORING_ALERT_TYPES.contains(event.getAlertType())) continue;
            // E9: ilk bildirimi yarıda kalmış alarm (processResults'taki kurtarmanın açılış eşleniği) —
            // aralık beklenmez, INITIAL olarak BİR kez gider (damga aşağıda atılır).
            boolean initialMissing = initialNotificationMissing(event, now());
            String lastAlertTime = event.getLastReAlertAt() != null
                    ? event.getLastReAlertAt() : event.getCreatedAt();
            if (!initialMissing && !reAlertDueFor(event.getAlertType(), lastAlertTime, now(), reAlertIv)) {
                log.debug("Catch-up: {} re-alert interval not elapsed, skipping", event.getDomain());
                continue;
            }
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
            event.setLastReAlertAt(now());
            event.setDaysRemaining(effectiveDays);
            if (notAfterOf(certContext) != null) event.setNotAfter(notAfterOf(certContext));
            alertEventRepo.save(event);
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
        // D-A3-4 (2026-09-29): elle çözüm de ATOMİK kapıdan geçer — otomatik kapanışla aynı saniyede gelen "Çöz",
        // yukarıdaki oku-kontrol-yaz penceresinde resolvedBy/At'i eziyor ve İKİNCİ bir çözüm e-postası (MANUAL_RESOLVE)
        // üretiyordu. Koşullu UPDATE 0 dönerse yarışı otomatik yol kazanmış demektir: kapanmış olay olduğu gibi döner,
        // bildirim yok (yukarıdaki idempotent kuralla aynı sonuç).
        if (event.getId() != null && alertEventRepo.markResolvedIfOpen(event.getId(), at, by) == 0) {
            log.info("Elle çözüm atlandı — alarm aynı anda otomatik kapandı (yarış): {} [{}] #{}",
                    event.getDomain(), event.getAlertType(), event.getId());
            return alertEventRepo.findById(eventId).orElse(event);
        }
        event.setResolved(true);
        event.setResolvedAt(at);
        event.setResolvedBy(by);
        if (note != null && !note.isBlank()) event.setResolvedNote(note.trim());
        AlertEvent saved = alertEventRepo.save(event);
        // D-A3-3 (2026-09-29): fırtına üyesi elle çözülünce de bireysel ÇÖZÜLDÜ e-postası GİTMEZ — otomatik yolla aynı
        // kural (resolveOpenAlertsForDomain): tek toplu kurtarma fırtına dağılınca gider; aksi hâlde takım aynı üyeyi
        // önce bireysel, sonra toplu çözümde iki kez alıyordu. Push simetrisi korunur (açılışta SENT olanlara).
        if (saved.getStormId() != null && stormService != null && stormService.isActive(saved.getStormId())) {
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
            if (event.getId() != null
                    && alertEventRepo.markResolvedIfOpen(event.getId(), now(), "system") == 0) {
                log.debug("Alarm zaten çözülmüş (yarış) — otomatik kapanış atlandı: {} [{}]",
                        domain, event.getAlertType());
                continue;
            }
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy("system");
            AlertEvent saved = alertEventRepo.save(event);
            // Storm üyesi + storm hâlâ aktif → bireysel çözüm e-postası GÖNDERME
            // (TEK toplu recovery, fırtına dağıldığında storm sweep'inden gider). Incident yine kapandı.
            if (saved.getStormId() != null && stormService.isActive(saved.getStormId())) {
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
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy(by);
            if (markSilenced) event.setResolvedSilently(true);   // O-b2: fırtına çözümü bunu "kurtarıldı" saymaz (O-c1: bakımda değil)
            AlertEvent saved = alertEventRepo.save(event);   // mail YOK — sendResolutionNotification çağrılmaz
            // Push AÇILIŞTA gittiyse çözümü de gitsin (simetri kuralı enqueueResolve içinde): bakım
            // penceresinde kurtarma / silme / duraklatma mail'siz kapanır ama telefon "DÜZELDİ"yi
            // hiç görmüyordu — açık alarm bildirimi ekranda sonsuza kadar kalıyordu.
            enqueueResolvePushQuietly(saved != null ? saved : event);
            // O-A3-3 (2026-09-29): bakım penceresinde GERÇEKTEN kurtulan alarm (işaretsiz dal) — 7/24 (NOC) ÇÖZÜLDÜ postası
            // takımın e-postasından BAĞIMSIZDIR: açılış NOC'a gittiyse çözüm de gider (karar serviste; açılış gitmediyse
            // hiçbir şey göndermez, çift gönderim yok). Eskiden yalnız sendResolutionNotification çağırıyordu; push-tek
            // takımda / e-postası kapalı izlemede NOC "kesinti sürüyor" sanıyordu. Susturma (silme/duraklatma) dalı değil.
            if (!markSilenced) notifyNocResolved(saved != null ? saved : event);
            log.info("✅ Alarm sessizce kapatıldı (izleme silindi, mail yok): {} [{}]", domain, event.getAlertType());
        }
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

    static boolean closableBy(AlertEvent e, Map<String, Object> ownerCtx) {
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

        String message = monitoringMessage(domain, alertType, alertLevel, outageContext);
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
                alertEventRepo.save(event);
                // İZ (2026-09-30, prod olayı): bu dal eskiden hiçbir kanal çalıştırmıyor ve hiçbir kayıt da bırakmıyordu —
                // alarm penceresi "0 bildirim", push "önce bildirim gitmemişti" diyor, takım neden haber almadığını
                // göremiyordu. Bildirim günlüğü satırı: "bireysel e-posta (ayar kapalıysa push da) fırtına #N'e devredildi".
                // PUSH FIRTINAYA DEVREDİLMEZ (2026-10-03, kullanıcı kararı, varsayılan): e-posta fırtınada kalır, bu alarmın
                // push'u ŞİMDİ bireysel gider — alarmlar sweep'te açıldıkça kuyruğa girer, yani teslim sırası açılış sırasıdır.
                boolean pushIndividual = stormPushIndividual();
                recordStormSuppression(event, domainTeamId, ugTeamId, pushIndividual);
                if (pushIndividual) pushStormMember(event, "INITIAL", domainTeamId, outageContext);
                log.info("🌩 İzleme alarmı storm'a eklendi (bireysel e-posta yok, push {}): {} [{}] → storm #{}",
                        pushIndividual ? "bireysel" : "fırtınada", domain, alertType, event.getStormId());
            } else {
                // Gecikmeli eskalasyon kişisi ilk bildirime girmez (yeni olay → sorgu yok; adım EscalationStepService'ten).
                List<EscalationContact> contacts = teamOnly ? List.of()
                        : EscalationDelay.immediateOnly(getContactsForLevel(alertLevel, domainTeamId, ugTeamId));
                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                        message, "", event.getId(), "INITIAL", null, outageContext);

                event.setNotifiedContacts(serializeContacts(contacts));
                event.setLastReAlertAt(now());
                alertEventRepo.save(event);
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
                event.setAlertLevel(alertLevel);
                event.setMessage(message);
                if (acked) {
                    event.setAcknowledged(false);
                    event.setAcknowledgedAt(null);
                    event.setAcknowledgedBy(null);
                }
                // KALICILASTIR: asagidaki "re-alert vakti gelmedi" dali save cagirmiyor ve sinifta
                // @Transactional da yok — dirty-checking kurtarmiyordu. Terfi bellekte kalip
                // kayboluyor, araya giren bir cozum bildirimini ESKI (dusuk) seviyeyle gonderiyor
                // ve mudur atlanabiliyordu (Y5'in alt-dali).
                alertEventRepo.save(event);
                boolean stormMember = event.getStormId() != null && stormService.isActive(event.getStormId());
                if (!stormMember) {
                    List<EscalationContact> contacts = teamOnly ? List.of()
                            : recipientsNow(alertLevel, domainTeamId, ugTeamId, event.getId());
                    sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                            message, "", event.getId(), "ESCALATION", null, outageContext);
                    event.setNotifiedContacts(serializeContacts(contacts));
                    event.setLastReAlertAt(now());
                    alertEventRepo.save(event);
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
            if (event.getLastReAlertAt() == null) {
                List<EscalationContact> contacts = teamOnly ? List.of()
                        : recipientsNow(alertLevel, domainTeamId, ugTeamId, event.getId());
                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                        message, "", event.getId(), "INITIAL", null, outageContext);
                event.setNotifiedContacts(serializeContacts(contacts));
                event.setLastReAlertAt(now());
                event.setMessage(message);
                alertEventRepo.save(event);
                log.warn("🔴 Yarıda kalmış ilk bildirim tamamlandı: {} [{}] — alarm {} tarihinde açılmıştı",
                        domain, alertType, event.getCreatedAt());
                return;
            }
            String lastAlertTime = event.getLastReAlertAt();
            if (reAlertDueFor(alertType, lastAlertTime, now(), reAlertIntervalHours())) {
                List<EscalationContact> contacts = teamOnly ? List.of()
                        : recipientsNow(alertLevel, domainTeamId, ugTeamId, event.getId());
                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                        "[RE-ALERT] " + message, "[RE-ALERT] ",
                        event.getId(), "DAILY_REALERT", null, outageContext);

                event.setLastReAlertAt(now());
                event.setRealertCount((event.getRealertCount() == null ? 0 : event.getRealertCount()) + 1);
                event.setMessage(message);
                alertEventRepo.save(event);
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
        Long teamId = ctxTeam instanceof Number n ? n.longValue()
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
                e.getAlertLevel() != null ? e.getAlertLevel() : levelWordSafe(ctx), ctx);
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
     * spamming contacts with a "resolved" email would be noise.
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
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy(resolvedBy);
            event.setResolvedSilently(true);   // O-b2: envanter silindi/pasifleşti — kurtulmadı, susturuldu
            AlertEvent saved = alertEventRepo.save(event);
            enqueueResolvePushQuietly(saved != null ? saved : event);   // resolveOpenAlertsSilently ile aynı gerekçe
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

    /**
     * Startup safety net — closes any alarms that are still open on domains
     * already soft-deleted from inventory. Legacy state from before the live
     * inventory-delete close hook shipped (v18.9.0) is cleaned up automatically
     * on next application start. Silent (no resolution email), idempotent.
     */
    public int catchUpAlertsOnDeletedDomains() {
        int closed = 0;
        for (AlertEvent event : alertEventRepo.findOpenAlertsOnSoftDeletedDomains()) {
            if (!closableByInventory(event, "envanter silindi — açılış telafisi")) continue;   // Y-A3-1: bağımsız olay dokunulmaz
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy("inventory_delete");
            event.setResolvedSilently(true);   // O-b2
            alertEventRepo.save(event);
            log.info("Startup catch-up: closed stale alarm {} [{}] for soft-deleted domain {}",
                    event.getId(), event.getAlertType(), event.getDomain());
            closed++;
        }
        if (closed > 0) {
            log.info("Startup catch-up complete — closed {} stale alarm(s) on soft-deleted domains", closed);
        }
        return closed;
    }

    @Async("certCheckExecutor")
    public void sendResolutionNotificationAsync(AlertEvent event, String resolvedBy, String trigger) {
        try {
            sendResolutionNotification(event, resolvedBy, trigger);
        } catch (Exception e) {
            log.error("Async resolution notification failed for alertEventId={}: {}",
                    event != null ? event.getId() : null, e.getMessage(), e);
        }
    }

    private void sendResolutionNotification(AlertEvent event, String resolvedBy, String trigger) {
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
                    try { alertEventRepo.save(event); } catch (Exception ignore) { /* damga best-effort */ }
                }
            }
            // Sahipsiz kayıt (2026-09-28): açılışla AYNI kapı — takımı olmayan alarmın çözümü de hiçbir kanaldan gitmez
            // (e-posta, kontak webhook'u, push). 7/24 çözüm tetiği yukarıda: yalnız açılışı NOC'a gitmiş alarmda gönderir.
            if (domainTeamId == null && ugTeamId == null) {
                log.warn("Sahipsiz kayıt — çözüm bildirimi gönderilmedi: olay={} alan={} tür={}",
                        event.getId(), event.getDomain(), event.getAlertType());
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
            // Bulgu 13: izlemede "E-posta" kapalıysa AÇILIŞ maili gitmiyor ama ÇÖZÜLDÜ maili
            // gidiyordu — bastırma paritesi kırıktı. Damga artık contextJson'a kalıcılaşıyor
            // (snapshotContext), böylece çözüm yolu da aynı kararı okuyabiliyor.
            // certContext aşağıda kurulur; damga olayın KENDİ contextJson'ında saklı olduğu için
            // burada erkenden okunabilir (cert alarmlarında contextJson yok → null → bugünkü davranış).
            Map<String, Object> earlyCtx = deserializeContext(event.getContextJson());
            boolean mailDisabled = earlyCtx != null && Boolean.TRUE.equals(earlyCtx.get("mail_disabled"));
            String typeTr = resolvedTypeLabel(event.getAlertType());
            String subject = "[Site Monitor ✅ ÇÖZÜLDÜ] " + event.getDomain()
                    + " — " + typeTr + " sorunu giderildi";
            if (mailDisabled || allEmails.isEmpty()) {
                log.info("Çözüm bildirimi — mail atlandı ({}): {}",
                        mailDisabled ? "izlemede e-posta kapalı" : "alıcı yok", event.getDomain());
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
            log.warn("Çözüm bildirimi gönderilemedi: {} — {}", event.getDomain(), e.getMessage());
        }
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
                (String) result.get("domain"), san, (String) result.get("trust_status"));
    }

    private String securityAlertType(Map<String, Object> result) {
        Object sanRaw = result.get("san");
        java.util.List<String> san = sanRaw instanceof java.util.List<?> l
                ? l.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList()
                : java.util.List.of();
        java.util.List<String> flags = CertificateHealthRules.securityFlags(
                (String) result.get("domain"), san, (String) result.get("trust_status"));
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
            emailStatus = mailDisabled ? "SKIPPED: e-posta kanalı kapalı"
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
                                 "integrity_scope", "problem_pages");

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
}
