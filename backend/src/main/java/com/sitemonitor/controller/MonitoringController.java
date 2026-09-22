package com.sitemonitor.controller;

import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.CheckHistoryService;
import com.sitemonitor.service.CheckHistoryService.CsvColumn;
import com.sitemonitor.service.DnsCheckerService;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.MonitorHistoryService;
import com.sitemonitor.service.PageSpeedCheckerService;
import com.sitemonitor.service.DomainExpiryReminderService;
import com.sitemonitor.service.PortCheckerService;
import com.sitemonitor.service.KeywordCheckerService;
import com.sitemonitor.service.PingCheckerService;
import com.sitemonitor.service.HttpCheckerService;
import com.sitemonitor.service.DomainCheckerService;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.MonitoringGroupService;
import com.sitemonitor.util.MonitorUrls;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.springframework.data.domain.PageRequest;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final LatestCheckRepository latestCheckRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final CertificateCheckRepository certCheckRepo;
    private final UptimeCheckRepository uptimeCheckRepo;
    private final MonitoringGroupService monitoringGroupService;

    private final PortMonitorRepository portMonitorRepo;
    private final PortCheckRepository portCheckRepo;
    private final PortCheckerService portChecker;

    private final DnsMonitorRepository dnsMonitorRepo;
    private final DnsRecordRepository dnsRecordRepo;
    private final DnsCheckerService dnsChecker;

    private final KeywordMonitorRepository keywordMonitorRepo;
    private final KeywordResultRepository keywordResultRepo;
    private final KeywordCheckerService keywordChecker;

    private final PingMonitorRepository pingMonitorRepo;
    private final PingCheckRepository pingCheckRepo;
    private final PingCheckerService pingChecker;

    private final HttpMonitorRepository httpMonitorRepo;
    private final HttpCheckRepository httpCheckRepo;
    private final HttpCheckerService httpChecker;

    private final DomainMonitorRepository domainMonitorRepo;
    private final DomainCheckRepository domainCheckRepo;
    /** Hatırlatma izleri (2026-09-22) — alan enjeksiyonu: @WebMvcTest bağlamında mock'lanmadan da yüklensin (schedulerService deseni). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.DomainExpiryReminderRepository domainReminderRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DomainExpiryReminderService domainReminders;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.DomainRenewalPlanService domainRenewalPlans;   // yenileme planı (2026-09-22, H)
    private final DomainCheckerService domainChecker;
    private final PublicSuffixService publicSuffixService;
    private final ActivityLogService activityLog;   // birleşik aktivite akışı (yaşam döngüsü olayları, best-effort)
    private final AuditService auditService;         // denetim (kim ne yaptı) — kurcalanamaz kayıt
    private final MonitorHistoryService monitorHistory;   // ürün-görünür değişiklik geçmişi (audit'in YANINA yazar)
    private final com.sitemonitor.repository.MonitorChangeLogRepository changeLogRepo;

    /**
     * Kullanıcının yazdığı opsiyonel "değişiklik nedeni" — yalnız geçmiş satırına girer, monitörü
     * DEĞİŞTİRMEZ. Zorunlu değildir; boşsa zaman çizelgesinde hiç gösterilmez.
     */
    private static String changeNote(Map<String, Object> body) {
        Object v = body == null ? null : body.get("changeNote");
        String s = v == null ? null : v.toString().trim();
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * Yapılandırma değişikliğini Activity akışına da düşer ({@code CONFIG_CHANGED}).
     *
     * <p>Neden iki yere yazıyoruz: Activity akışı "bu monitörde ne oldu" sorusunun TEK zaman
     * çizelgesi — kontroller, alarmlar ve yaşam döngüsü orada yan yana duruyor. Ayar değişikliği
     * orada görünmezse "sabah 9'da alarm başladı" ile "8:55'te eşik değişti" arasındaki bağ
     * kurulamaz. Satır yalnız ÖZET taşır; tam eski→yeni farkı değişiklik geçmişindedir.
     *
     * <p>Geçmiş satırı yazılmadıysa (hiçbir alan değişmemiş) burada da olay üretilmez.
     */
    private void noteConfigChanged(com.sitemonitor.model.MonitorChangeLog row, String activityType,
                                   String target, HttpSession session) {
        if (row == null || !MonitorHistoryService.UPDATE.equals(row.getEventType())) return;
        var fields = MonitorHistoryService.changedFields(row.getChanges());
        if (fields.isEmpty()) return;
        activityLog.recordLifecycle(activityType, row.getResourceId(), row.getResourceName(), target,
                row.getTeamId(), "CONFIG_CHANGED", actor(session), String.join(", ", fields));
    }

    /** İzleme güncellemelerinde before/after diff için snapshot alınacak alanlar (tür-üstü superset; olmayan getter → null, gürültü yaratmaz). */
    private static final String[] MON_FIELDS = {
        "name", "host", "port", "url", "domain", "recordType", "keyword", "expectedValue", "expect",
        "expectedStatus", "method", "matchOperator", "matchCount", "active", "teamId", "groupName", "tags", "alertLevel",
        "intervalSeconds", "timeoutMs", "warningDays", "criticalDays", "protocol", "verifySsl", "followRedirects", "useProxy",
        "mode", "crawlDepth", "crawlMaxPages", "excludePatterns", "slowResourceMs", "alertThirdParty", "alertMixedContent", "alertTimeout", "resourceConcurrency",
        "notificationGroupId",
        "transferLockAlert", "blacklistEnabled", "changeAlert", "notifyEmail", "notifyWebhook", "renewalPlannedAt", "renewalPlannedNote",
        // Teyit/kurtarma ayarlari: 6 noktanin 5'inde vardi (patch, create, update, gosterim, form)
        // ama DIFF'te yoktu. Yalniz bu alanlari degistiren bir duzenleme AuditDiff'te bos donuyor,
        // noteConfigChanged erken cikiyor ve denetim kaydi / izleme gecmisi / aktivite akisi
        // HICBIR iz tasimiyordu.
        "confirmAttempts", "confirmIntervalSeconds", "recoveryChecks", "recoveryIntervalSeconds",
        // Kimlik alani degisince envanter bagi kopar (bkz. detachIfIdentityChanged) — bu, kullaniciya
        // gorunen bir davranis degisikligi oldugu icin audit ve degisiklik gecmisinde de yer almali.
        "standalone"
    };

    /**
     * Monitorun bildirim grubu alani (null = takim varsayilani -> Team.email).
     *
     * <p>Iki kural burada birlesiyor:
     * <ul>
     *   <li><b>Sahiplik:</b> baska takimin grubu SECILEMEZ. Kabul edilseydi bir takim, bir
     *       monitoru digerinin nobetci listesine yonlendirebilirdi -- hem yanlis yonlendirme
     *       hem de o takimin adres listesini dolayli ogrenme yolu.</li>
     *   <li><b>Takim degisimi:</b> monitor baska takima tasinirsa gruba DOKUNULMAZSA damga eski
     *       takimda kalirdi ve alarmlar artik ilgisiz bir ekibe giderdi. Govdede grup gelmediyse
     *       mevcut deger takimla uyumlulugu acisindan SUZULUR; uymuyorsa null'a duser (takim
     *       varsayilani), sessiz bir yanlis yonlendirme yerine.</li>
     * </ul>
     */
    /*
     * ÇAĞRI SIRASI ZORUNLU: bu metot NİHAİ takımla çağrılmalıdır.
     *
     * Dört create ucunda (keyword/http/domain/ping) çağrı `m.setTeamId(teamId)`'den ÖNCE
     * geliyordu; `m` yeni nesne olduğu için `m.getTeamId()` null'dı, `ownedByTeam` daima false
     * kalıyordu ve aşağıdaki "başka takımın grubu" dalı fırlıyordu. Sonuç: bildirim grubu SEÇEREK
     * izleme oluşturmak her seferinde 400 veriyordu — üstelik mesaj gerçek dışıydı, grup
     * kullanıcının kendi takımınındı. Diğer beş tür doğru sıradaydı.
     *
     * Dokuz update ucunda ise çağrı `resolveTeamChange`'den ÖNCEydi: grup ESKİ takıma göre
     * doğrulanıp izleme sonra yeni takıma taşınıyordu, yani {teamId: B, group: A'nın grubu} tek
     * istekte kabul ediliyordu — tam da aşağıdaki yorumun yasakladığı şey.
     *
     * Kapı: NotificationGroupOrderingTest kaynağı tarar; her iki satırı da içeren bir metotta
     * setTeamId önce gelmezse kırılır.
     */
    private Long applyNotificationGroup(Map<String, Object> body, Long teamId, Long current) {
        boolean supplied = body != null && body.containsKey("notificationGroupId");
        Object raw = supplied ? body.get("notificationGroupId") : null;
        Long requested = supplied
                ? (raw instanceof Number n2 ? n2.longValue() : null)
                : current;
        if (requested == null) return null;
        var g = notificationGroupRepo.findById(requested).orElse(null);
        boolean ownedByTeam = g != null && teamId != null && teamId.equals(g.getTeamId());

        if (ownedByTeam && Boolean.TRUE.equals(g.getActive())) return requested;

        // BASKA TAKIMIN grubu: gercek bir hata -- acikca reddet. Kabul edilseydi bir takim, bir
        // monitoru digerinin nobetci listesine yonlendirebilirdi.
        //
        // Grup HIC YOKSA (g == null) reddetmiyoruz: gruplar artik KALICI siliniyor, dolayisiyla
        // "form acikken grup silindi" normal bir yaris hali. Kullaniciya 400 vermek, silinen
        // grubu tasiyan monitorun butun duzenlemelerini kilitlerdi.
        if (supplied && g != null && !ownedByTeam) {
            throw new IllegalArgumentException("Secilen bildirim grubu bu takima ait degil");
        }

        // KENDI takiminin SILINMIS grubu: kullanici hatasi degil, normal yasam dongusu. Grup
        // silindiginde onu kullanan monitorler kaliyor ve formda "(silinmis)" rozetiyle gorunuyor.
        // Burada 400 firlatmak, grubu silen kisinin o monitorlerin BUTUN duzenlemelerini
        // kilitlemesi olurdu (interval degistirmek bile imkansizlasirdi). Zarifce zincirin
        // kalanina duseriz: takim varsayilani -> Team.email.
        log.info("Bildirim grubu {} artik gecerli degil (takim {}) - izleme takim varsayilanina dusuruldu",
                requested, teamId);
        return null;
    }

    private final TeamRepository teamRepo;
    /** Monitore secilen bildirim grubunun sahipligini dogrulamak icin (baska takimin grubu REDDEDILIR). */
    private final com.sitemonitor.repository.NotificationGroupRepository notificationGroupRepo;
    private final AlertEventRepository alertEventRepo;

    /** domain/host → sorumlu takım adı (izleme ekranlarında takım gösterimi/filtresi). */
    private final CertificateService certificateService;
    private final PermissionService permissionService;
    private final EscalationService escalationService;
    private final AppSettingsService appSettings;
    /** Saklama süreleri tek kaynaktan (RetentionCatalog) — history kırpması onunla senkron. */
    private final com.sitemonitor.service.retention.RetentionService retentionService;
    /** Canlı teyit durumu ("Teyit denemesi X/N") — detay modalı 30sn'de bir poll eder. */
    private final MonitoringOutageService monitoringOutageService;

    /** Manuel domain "Şimdi Kontrol Et" sonrası alarm değerlendirmesi için (sweep ile aynı mantık).
     *  @Lazy: SchedulerService ağır bean; olası wiring döngüsünü kır (ExtendedHealthService ile aynı desen). */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private SchedulerService schedulerService;

    /** Sayfa-bütünlüğü (9. tür) — @RequiredArgsConstructor'ı büyütmemek için alan enjeksiyonu (schedulerService deseni). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.PageMonitorRepository pageMonitorRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.PageCheckRepository pageCheckRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.PageResourceIssueRepository pageResourceIssueRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.service.PageCheckerService pageChecker;
    /** Sayfa Hızı — aynı desen (alan enjeksiyonu). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedMonitorRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.PageSpeedCheckRepository pageSpeedCheckRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.PageSpeedResourceRepository pageSpeedResourceRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.service.PageSpeedCheckerService pageSpeedChecker;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.ScriptedMonitorRepository scriptedMonitorRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.ScriptedCheckRepository scriptedCheckRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.service.ScriptedCheckerService scriptedChecker;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.service.ProxySettings proxySettings;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.ProxyPolicyService proxyPolicy;   // HTTP/Keyword/Sayfa vekil kararı (2026-09-21)
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.service.SsrfGuard ssrfGuard;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.ScriptedScriptVersionRepository scriptedVersionRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.repository.ScriptedDraftRepository scriptedDraftRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.service.SecretCipher secretCipher;
    /** Kontrol Geçmişi v2 ortak motoru (sayfalı aralık + filtre + histogram + alarm eşleme + CSV). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.sitemonitor.service.CheckHistoryService checkHistoryService;

    /** Manuel sayfa-kontrol tetikleri için per-monitör cooldown zamanı (H1c rate-limit; in-memory, monitör sayısıyla sınırlı). */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> pageManualTriggerAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> scriptedManualTriggerAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> pageSpeedManualTriggerAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** "Şimdi Dene" bekleme damgası — OTURUM niteliğinde tutulur (harita tutmak sızıntı olurdu). */
    private static final String PAGESPEED_TEST_AT = "pagespeedTestAt";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }

    /** Yeni monitör formları için per-tip VARSAYILAN kontrol aralığı (sn) + request timeout (ms).
     *  Genel Ayarlar → Kontrol Sıklığı'ndan canlı ayarlanır; create-modal'lar bunu ön-doldurur. */
    @GetMapping("/defaults")
    public ResponseEntity<Map<String, Object>> monitorDefaults(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        return ok(Map.of(
            "ping",    Map.of("intervalSeconds", appSettings.getInt("site.monitor.ping.default-interval-seconds", 60),
                              "timeoutMs",        appSettings.getInt("site.monitor.ping.default-timeout-ms", 5000)),
            "keyword", Map.of("intervalSeconds", appSettings.getInt("site.monitor.keyword.default-interval-seconds", 60),
                              "timeoutMs",        appSettings.getInt("site.monitor.keyword.default-timeout-ms", 10000),
                              "slowThresholdMs",  appSettings.getInt("site.monitor.keyword.default-slow-ms", 3000),
                              "caseSensitive",    false),
            "port",    Map.of("intervalSeconds", appSettings.getInt("site.monitor.port.default-interval-seconds", 300),
                              "timeoutMs",        appSettings.getInt("site.monitor.port.default-timeout-ms", 5000),
                              "slowThresholdMs",  appSettings.getInt("site.monitor.port.default-slow-ms", 3000)),
            "dns",     Map.of("intervalSeconds", appSettings.getInt("site.monitor.dns.default-interval-seconds", 300)),
            "http",    Map.of("intervalSeconds", appSettings.getInt("site.monitor.http.default-interval-seconds", 300),
                              "timeoutMs",        appSettings.getInt("site.monitor.http.default-timeout-ms", 10000)),
            "domain",  Map.of("intervalSeconds", appSettings.getInt("site.monitor.domain.default-interval-seconds", 86400),
                              "warningDays",      appSettings.getInt("site.monitor.domain.default-warning-days", 30),
                              "criticalDays",     appSettings.getInt("site.monitor.domain.default-critical-days", 7),
                              "thresholds",       appSettings.getString("site.monitor.domain.default-thresholds", "60,30,14,7,3,1")),
            "page",    Map.of("intervalSeconds",     appSettings.getInt("site.monitor.page.default-interval-seconds", 300),
                              "timeoutMs",           appSettings.getInt("site.monitor.page.default-timeout-ms", 4000),
                              "slowResourceMs",      appSettings.getInt("site.monitor.page.default-slow-ms", 2000),
                              "resourceConcurrency", appSettings.getInt("site.monitor.page.resource-concurrency", 5),
                              "crawlDepth",          appSettings.getInt("site.monitor.page.default-crawl-depth", 2),
                              "crawlMaxPages",       appSettings.getInt("site.monitor.page.default-crawl-max-pages", 50)),
            "scripted", Map.of("intervalSeconds", appSettings.getInt("site.monitor.scripted.default-interval-seconds", 300),
                              "timeoutSeconds",   appSettings.getInt("site.monitor.scripted.default-timeout-seconds", 60)),
            "pagespeed", Map.of("intervalSeconds",     appSettings.getInt("site.monitor.pagespeed.default-interval-seconds", 1800),
                              "timeoutMs",           appSettings.getInt("site.monitor.pagespeed.default-timeout-ms", 10000),
                              "resourceConcurrency", appSettings.getInt("site.monitor.pagespeed.resource-concurrency", 5),
                              // Aralık tabanı: form bunun altına inemez (sunucu da uygular).
                              "minIntervalSeconds",  com.sitemonitor.model.PageSpeedMonitor.MIN_INTERVAL_SECONDS)
        ));
    }

    /** Write endpoints are admin-only — USER role gets a 403 via GlobalExceptionHandler. */
    private void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException("Admin access required");
        }
    }

    // Read-scope guard for history endpoints — takım-kapsamlı görüntüleme (BOLA/IDOR önler). İzin yoksa 403 gövdesi, aksi null.
    private ResponseEntity<Map<String, Object>> denyIfNotViewable(HttpSession session, Long teamId) {
        return SessionScope.canView(session, teamId) ? null : forbidden("Bu izlemeyi görüntüleme yetkiniz yok");
    }

    /**
     * History aralığının üst sınırı = o türün GERÇEK saklama süresi. Artık burada sabit yok:
     * değer {@code RetentionCatalog}'dan okunur, yani ekranın kırpması ile gece temizliğinin sildiği
     * tek kaynaktan gelir. (Eskiden çoğu tür 180'e sabitti ve "cleanup ile elle senkron tutulur"
     * notuyla sürüklenmeye açıktı — ayar değişince ekran eski davranmaya devam ediyordu.)
     */
    private int historyRetentionDays(String kind) {
        return retentionService.historyRetentionDays(kind, 180);
    }

    /** Eski/serbest {@code days} paramını TOLERANSLI çevirir: sayı değilse ("custom", "", "abc")
     *  null döner → days sugar devre dışı, from/to veya varsayılan aralık kullanılır. Sıkı
     *  {@code Integer} bağlama, "Özel Aralık" akışında days=custom gelince 500 üretiyordu (2026-08). */
    private static Integer parseDaysSafe(String days) {
        if (days == null || days.isBlank()) return null;
        try {
            int d = Integer.parseInt(days.trim());
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Kontrol Geçmişi v2 ortak yürütücüsü: izin + takım denetimi + resolve + (JSON zarfı | CSV akışı).
     *  Not: history'ler artık response-series ile aynı require("monitoring.read") kapısını da taşır. */
    private <T> ResponseEntity<?> runHistory(HttpSession session, Long teamId,
            com.sitemonitor.service.CheckHistoryService.Source<T> src, String kind,
            String alertKey, Set<String> alertTypes,
            String from, String to, String days, String status, int page, int size, String format,
            String csvBase, List<com.sitemonitor.service.CheckHistoryService.CsvColumn<T>> csvCols,
            jakarta.servlet.http.HttpServletResponse response) {
        permissionService.require(session, "monitoring.read", "view");
        var deny = denyIfNotViewable(session, teamId);
        if (deny != null) return deny;
        var r = checkHistoryService.resolve(
                new com.sitemonitor.service.CheckHistoryService.Query(from, to, parseDaysSafe(days), status, page, size),
                historyRetentionDays(kind));
        if ("csv".equalsIgnoreCase(format)) {
            try {
                checkHistoryService.writeCsv(src, r, csvBase, csvCols, response);
                return null;   // yanıt yazıldı — HttpEntityMethodProcessor null'u "tamamlandı" sayar
            } catch (java.io.IOException e) {
                throw new RuntimeException("CSV yazımı başarısız", e);
            }
        }
        return ok(checkHistoryService.execute(src, r, alertKey, alertTypes));
    }

    // ── Değişiklik geçmişi (kim, ne zaman, hangi IP'den, neyi değiştirdi) ────────────────────

    /**
     * Bir kaynağın geçmişi. Kapsam: {@code monitoring.read} + kaynağın takımına
     * {@link SessionScope#canView}.
     *
     * <p>Takım SON geçmiş satırından okunur, canlı kayıttan DEĞİL. İki sebep: (1) silinmiş bir
     * kaynağın geçmişi de okunabilmeli — canlı satır yok; (2) her takım değişimi zaten bir geçmiş
     * satırı üretiyor, yani son satırın takımı güncel takımdır. Hiç satır yoksa yetkilendirilecek
     * bir şey de yok: boş liste döner (varlık sızdırmaz).
     */
    @GetMapping("/changes/{kind}/{id}")
    public ResponseEntity<Map<String, Object>> resourceChanges(
            @PathVariable String kind, @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        permissionService.require(session, "monitoring.read", "view");
        String resolved = MonitorHistoryService.KIND_BY_PATH.get(kind == null ? "" : kind.toLowerCase(Locale.ROOT));
        if (resolved == null) return badRequest("Bilinmeyen kaynak türü: " + kind);

        var last = changeLogRepo.findTopByResourceKindAndResourceIdOrderByCreatedAtDesc(resolved, id);
        if (last.isEmpty()) return ok(Map.of("changes", List.of(), "total", 0));
        if (!SessionScope.canView(session, last.get().getTeamId())) {
            // IDOR: yabancı takımın geçmişi 404 döner (403 "var ama giremezsin" bilgisini sızdırır).
            auditService.recordSecurityEvent("CHANGE_LOG_DENIED", request, session, "MONITOR_CHANGE",
                    resolved + ":" + id, "Yetkisiz geçmiş erişimi");
            return notFound("Kayıt bulunamadı");
        }

        var pg = changeLogRepo.findByResourceKindAndResourceIdOrderByCreatedAtDescIdDesc(
                resolved, id, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))));
        Map<String, Object> out = new LinkedHashMap<>();
        // Liste yanıtı snapshot TAŞIMAZ: satır başı 1-2 KB, 25 satırda yanıtı gereksiz şişirirdi.
        out.put("changes", pg.getContent().stream().map(r -> changeRow(r, false)).toList());
        out.put("total", pg.getTotalElements());
        out.put("page", pg.getNumber());
        out.put("size", pg.getSize());
        return ok(out);
    }

    /** Tek olayın TAM detayı — snapshot dâhil ("şu tarihte bu izleme nasıldı"). */
    @GetMapping("/changes/{kind}/{id}/{seq}")
    public ResponseEntity<Map<String, Object>> changeDetail(
            @PathVariable String kind, @PathVariable Long id, @PathVariable Integer seq, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        String resolved = MonitorHistoryService.KIND_BY_PATH.get(kind == null ? "" : kind.toLowerCase(Locale.ROOT));
        if (resolved == null) return badRequest("Bilinmeyen kaynak türü: " + kind);

        return changeLogRepo.findByResourceKindAndResourceIdAndSeq(resolved, id, seq)
                .filter(r -> SessionScope.canView(session, r.getTeamId()))
                .map(r -> ok(changeRow(r, true)))
                .orElse(notFound("Kayıt bulunamadı"));
    }

    /** Geri döndürülebilir türler → (kayıt bul, kaydet, snapshot alanları). Diğerleri 400 alır. */
    private java.util.Optional<?> findRestorable(String kind, Long id) {
        return switch (kind) {
            case MonitorHistoryService.PORT -> portMonitorRepo.findById(id);
            case MonitorHistoryService.DNS -> dnsMonitorRepo.findById(id);
            case MonitorHistoryService.KEYWORD -> keywordMonitorRepo.findById(id);
            case MonitorHistoryService.HTTP -> httpMonitorRepo.findById(id);
            case MonitorHistoryService.PAGE -> pageMonitorRepo.findById(id);
            case MonitorHistoryService.PAGESPEED -> pageSpeedMonitorRepo.findById(id);
            case MonitorHistoryService.SCRIPTED -> scriptedMonitorRepo.findById(id);
            case MonitorHistoryService.DOMAIN -> domainMonitorRepo.findById(id);
            case MonitorHistoryService.PING -> pingMonitorRepo.findById(id);
            default -> java.util.Optional.empty();
        };
    }

    private void saveRestored(String kind, Object entity) {
        switch (kind) {
            case MonitorHistoryService.PORT -> portMonitorRepo.save((com.sitemonitor.model.PortMonitor) entity);
            case MonitorHistoryService.DNS -> dnsMonitorRepo.save((com.sitemonitor.model.DnsMonitor) entity);
            case MonitorHistoryService.KEYWORD -> keywordMonitorRepo.save((com.sitemonitor.model.KeywordMonitor) entity);
            case MonitorHistoryService.HTTP -> httpMonitorRepo.save((com.sitemonitor.model.HttpMonitor) entity);
            case MonitorHistoryService.PAGE -> pageMonitorRepo.save((com.sitemonitor.model.PageMonitor) entity);
            case MonitorHistoryService.PAGESPEED -> pageSpeedMonitorRepo.save((com.sitemonitor.model.PageSpeedMonitor) entity);
            case MonitorHistoryService.SCRIPTED -> scriptedMonitorRepo.save((com.sitemonitor.model.ScriptedMonitor) entity);
            case MonitorHistoryService.DOMAIN -> domainMonitorRepo.save((com.sitemonitor.model.DomainMonitor) entity);
            case MonitorHistoryService.PING -> pingMonitorRepo.save((com.sitemonitor.model.PingMonitor) entity);
            default -> throw new IllegalArgumentException("geri alınamaz tür: " + kind);
        }
    }

    /**
     * Bir izlemeyi geçmişteki bir andaki ayarlarına geri döndürür (K6).
     *
     * <p><b>Geçmiş EZİLMEZ:</b> eski satırlar durur, işlemin kendisi {@code RESTORE} olaylı YENİ
     * bir satır olarak eklenir. "Geri alma"nın da bir değişiklik olduğu ve kimin yaptığının
     * kaydedilmesi gerektiği için — sentetik script sürümlerindeki RESTORE deseninin aynısı.
     *
     * <p>Takım ve kimlik alanlarına DOKUNULMAZ: takım taşımak ayrı bir yetki kararıdır ve yanlışlıkla
     * "eski hâline dön" ile yapılmamalıdır. Maskeli (gizli) alanlar da geri yazılmaz; atlananlar
     * yanıtta bildirilir.
     */
    @PostMapping("/changes/{kind}/{id}/{seq}/restore")
    public ResponseEntity<Map<String, Object>> restoreChange(
            @PathVariable String kind, @PathVariable Long id, @PathVariable Integer seq,
            @RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        String resolved = MonitorHistoryService.KIND_BY_PATH.get(kind == null ? "" : kind.toLowerCase(Locale.ROOT));
        if (resolved == null) return badRequest("Bilinmeyen kaynak türü: " + kind);

        var rowOpt = changeLogRepo.findByResourceKindAndResourceIdAndSeq(resolved, id, seq);
        if (rowOpt.isEmpty()) return notFound("Kayıt bulunamadı");
        var row = rowOpt.get();
        if (!SessionScope.canManage(session, row.getTeamId())) return forbidden("Bu izleme üzerinde yetkiniz yok");
        if (row.getSnapshot() == null || row.getSnapshot().isBlank())
            return badRequest("Bu olayda geri yüklenecek bir durum kaydı yok");

        var entityOpt = findRestorable(resolved, id);
        if (entityOpt.isEmpty()) return notFound("İzleme bulunamadı ya da bu tür geri alınamıyor");
        Object entity = entityOpt.get();

        String[] fields = MonitorHistoryService.SCRIPTED.equals(resolved) ? SCRIPTED_FIELDS
                : MonitorHistoryService.PAGESPEED.equals(resolved) ? PAGESPEED_FIELDS : MON_FIELDS;
        Map<String, Object> before = AuditDiff.snapshot(entity, fields);
        Map<String, Object> snapshot;
        try {
            snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readValue(row.getSnapshot(), Map.class);
        } catch (Exception e) {
            return badRequest("Durum kaydı okunamadı");
        }
        // teamId: takım taşımak ayrı bir karar. groupName: grup kaydı silinmiş olabilir.
        // standalone: KİMLİK/YETKİ alanı — envanter-türevi DNS'i düzenlemek/silmek requireAdmin
        // isterken restore yalnız canManage istiyor. Geri yüklenebilseydi bir TEAM_ADMIN monitörün
        // yetki sınıfını çevirebilir (ve kısmi unique index ile çakışıp 500 üretebilirdi).
        var result = MonitorHistoryService.applySnapshot(entity, snapshot, fields,
                java.util.Set.of("teamId", "groupName", "standalone"));
        List<String> maskedSkipped = result.get(1);

        // "Yazılan alan" ile "DEĞİŞEN alan" aynı şey değil: snapshot güncel değerin aynısını
        // taşıyorsa applySnapshot onu yine yazar. Karar farka bakmalı — aksi halde hiçbir şeyin
        // değişmediği bir geri alma da RESTORE satırı üretir ve kullanıcıya "N alan döndü"
        // denirdi. Entity bu noktada DETACHED (findById kendi kısa transaction'ında kapandı),
        // yani kaydetmeden dönmek veritabanına hiçbir şey yazmaz.
        Map<String, Object> after = AuditDiff.snapshot(entity, fields);
        String effective = AuditDiff.diff(before, after);
        if (effective == null)
            return badRequest("Geri yüklenecek bir fark yok — ayarlar zaten o andaki gibi");
        List<String> applied = MonitorHistoryService.changedFields(effective);

        // Geri alma da bir GÜNCELLEMEDİR: kaydın "son değiştiren/son değişiklik" künyesi
        // güncellenmezse kart, geri almayı yapan kişiyi ve zamanı hiç göstermez.
        touchUpdated(entity, session);
        saveRestored(resolved, entity);
        auditService.recordAction("MONITOR_RESTORE", session, resolved + "_MONITOR", String.valueOf(id),
                row.getResourceName(), effective);
        monitorHistory.record(resolved, id, row.getResourceName(), row.getTeamId(),
                MonitorHistoryService.RESTORE, before, after,
                "#" + seq + " numaralı kayda geri döndürüldü" + noteSuffix(body), session);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restored", true);
        out.put("fields", applied);
        // Atlananlar SESSİZ kalmamalı: kullanıcı parolanın eski değerine dönmediğini bilmeli.
        out.put("skipped_masked", maskedSkipped);
        return ok(out);
    }

    /** Geri almada kimlik + zaman künyesi. {@code setUpdatedAt} olmayan türde sessizce geçer. */
    private void touchUpdated(Object entity, HttpSession session) {
        monitorHistory.stampUpdated(entity, session);
        try {
            entity.getClass().getMethod("setUpdatedAt", String.class)
                    .invoke(entity, ISO.format(Instant.now()));
        } catch (Exception ignored) {
            // kolon yok → künye yok; geri alma yine tamamlanır
        }
    }

    private static String noteSuffix(Map<String, Object> body) {
        String note = changeNote(body);
        return note == null ? "" : " — " + note;
    }

    /**
     * Tüm izlemelerin değişiklikleri — yönetici konsolunun tek noktadan sayfalanan akışı.
     *
     * <p>Global admin/AUDIT her şeyi görür; diğer roller {@code viewTeamIds} kesişimiyle sınırlanır.
     * Ekran 2026-08-27'de TÜM takım kullanıcılarına açıldı — kapsam mantığı bu gün için baştan
     * doğru yazılmıştı, uç yeniden yazılmadı.
     *
     * <p><b>{@code teamId} bir SÜZGEÇTİR, kapsam değil.</b> Kapsam koruması ({@code teamScopeAll} /
     * {@code teamIds}) sorguda ayrıca AND'lendiği için yabancı bir takım id'si zaten veri
     * sızdıramaz; yine de sessizce BOŞ liste dönmek yanlış cevap olurdu — kullanıcı süzgecin
     * çalıştığını sanır, o takımda hiç değişiklik olmadığı sonucuna varırdı. Kapsam dışı id
     * açıkça 403 alır.
     *
     * <p>Süzgeç ÜÇ sorguya birden geçer (liste + özet şerit + tür kartları). Yalnız listeye
     * geçseydi yönetici bir takım seçtiğinde rakamlar listeyle çelişirdi.
     */
    @GetMapping("/changes/recent")
    public ResponseEntity<Map<String, Object>> recentChanges(
            @RequestParam(required = false) String kind, @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actor, @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");

        // Kapsam DIŞI takım süzgeci: 403. Burada 404 kullanılmaz — takımın varlığı zaten
        // GET /teams ile bilinen bir şey, gizlenecek bir varlık yok (resourceChanges'teki
        // varlık-gizleme gerekçesi bu uçta geçerli değil).
        if (teamId != null && !SessionScope.canView(session, teamId))
            return forbidden("Bu takımın değişikliklerini görme yetkiniz yok");

        boolean all = SessionScope.isGlobalViewer(session);
        List<Long> view = SessionScope.viewTeamIds(session);
        // Boş IN listesi bazı sağlayıcılarda sözdizimi hatası verir — kukla değerle koru
        // (ScriptedTemplateController.readableTeamTemplates'teki aynı tuzak).
        List<Long> scope = (view == null || view.isEmpty()) ? List.of(-1L) : view;
        if (!all && (view == null || view.isEmpty())) return ok(Map.of("changes", List.of(), "total", 0));

        String kindKey = kind == null || kind.isBlank() ? null
                : MonitorHistoryService.KIND_BY_PATH.getOrDefault(kind.toLowerCase(Locale.ROOT), kind.toUpperCase(Locale.ROOT));
        var pg = changeLogRepo.search(kindKey, blankToNull(eventType), blankToNull(actor), teamId,
                blankToNull(from), blankToNull(to), blankToNull(q), all, scope,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))));

        Map<String, Object> counts = new LinkedHashMap<>();
        for (Object[] row : changeLogRepo.countByEventType(blankToNull(from), blankToNull(to), teamId, all, scope)) {
            counts.put(String.valueOf(row[0]), row[1]);
        }
        // Tür kartları: (tür → olay → adet). Sayfalanan listeden türetilemez (o yalnız görünen
        // sayfayı taşır); kartlar seçili zaman penceresinin TAMAMINI özetler.
        Map<String, Map<String, Object>> byKind = new LinkedHashMap<>();
        for (Object[] row : changeLogRepo.countByKindAndEventType(blankToNull(from), blankToNull(to), teamId, all, scope)) {
            byKind.computeIfAbsent(String.valueOf(row[0]), k -> new LinkedHashMap<>())
                  .put(String.valueOf(row[1]), row[2]);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changes", pg.getContent().stream().map(r -> changeRow(r, false)).toList());
        out.put("total", pg.getTotalElements());
        out.put("page", pg.getNumber());
        out.put("size", pg.getSize());
        out.put("event_counts", counts);
        out.put("kind_counts", byKind);
        return ok(out);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Geçmiş satırının API biçimi. {@code changes}/{@code snapshot} HAM JSON metni olarak taşınır. */
    private Map<String, Object> changeRow(com.sitemonitor.model.MonitorChangeLog r, boolean withSnapshot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", r.getSeq());
        m.put("kind", r.getResourceKind());
        m.put("resource_id", r.getResourceId());
        m.put("resource_name", r.getResourceName());
        m.put("event_type", r.getEventType());
        m.put("team_id", r.getTeamId());
        m.put("team_name", r.getTeamId() == null ? null : teamNameMap().get(r.getTeamId()));
        m.put("actor", r.getActor());
        m.put("actor_id", r.getActorId());
        m.put("actor_name", r.getActorName());
        m.put("ip_address", r.getIpAddress());
        m.put("user_agent", r.getUserAgent());
        m.put("changes", r.getChanges());
        m.put("note", r.getNote());
        m.put("at", r.getCreatedAt());
        if (withSnapshot) m.put("snapshot", r.getSnapshot());
        return m;
    }

    /** Oturum sahibinin kendi takımı (session "teamId"). */
    private static Long sessionTeamId(HttpSession session) {
        Object v = session != null ? session.getAttribute("teamId") : null;
        if (v instanceof Number n) return n.longValue();
        if (v != null) { try { return Long.valueOf(v.toString().trim()); } catch (Exception ignored) {} }
        return null;
    }

    /** Serbest-form izleme (keyword/ping) üzerinde yazma/çalıştırma kapsamı:
     *  global admin → her takım; TEAM_ADMIN → yönetim kapsamındaki takımlar; USER → kendi takımı. */
    private boolean canOperateTeam(HttpSession session, Long teamId) {
        if (SessionScope.isGlobalAdmin(session)) return true;
        if (teamId == null) return false;
        if (SessionScope.canManage(session, teamId)) return true;   // TEAM_ADMIN yönetim kapsamı
        // USER: ÜYESİ olduğu HER takım (2026-09-18) — eskiden yalnız birincil takımdı; çok takımlı
        // kullanıcı ikincil takımına izleme ekleyemiyor, seçtiği takım sessizce birincile düşüyordu.
        // memberTeamIds eski oturumda birincile geri düşer (rolling deploy), yani daralma yok.
        return SessionScope.isMemberOf(session, teamId) || teamId.equals(sessionTeamId(session));
    }

    /**
     * Bir izlemenin ETKİN takımı — yetki ve gösterim için TEK doğruluk kaynağı.
     *
     * <p>Envanter-türevi satırın ({@code standalone != true}) takımı ENVANTERİN takımıdır.
     * Satırdaki {@code team_id} yalnız lazy-provision anında kopyalanır ve bir daha ASLA
     * tazelenmez: ne {@code transferInventory}, ne {@code updateInventory}, ne
     * {@code MonitoringGroupBackfill} (yalnız NULL doldurur), ne de bir zamanlanmış iş dokunur.
     *
     * <p>Sonuç, envanter başka takıma taşındığında ikiye bölünen bir gerçeklikti: liste satırı
     * YENİ takımın adını yazıyordu (ad domain→envanter eşlemesinden geliyor) ama {@code team_id}
     * ESKİ takımdı. Yeni takım kendi kaydını düzenleyemiyor (403, kartta düğme çizilmiyor —
     * dba22f1a'nın düzelttiği şikâyetin aynısı), eski takım ise listede göremediği satırı id ile
     * yönetmeye devam edebiliyordu.
     *
     * <p>Ad ile kimliği AYNI kaynaktan beslemek bu ayrışmayı yapısal olarak imkânsız kılar:
     * {@code enrich*} bu değeri {@code team_id} olarak döndürür, yetki kapıları da aynı değeri
     * kullanır, dolayısıyla frontend'in {@code isOwnTeam} karşılaştırması kendiliğinden doğrular.
     *
     * <p>Standalone satır envanterden bağımsızdır; onun takımı kendi alanıdır. Envanter kaydı
     * yoksa (silinmiş/pasif) saklanan değere düşülür — kapsamı daraltmak yetkiyi kaybettirirdi.
     */
    private Long effectiveTeam(String domain, Boolean standalone, Long storedTeamId) {
        if (Boolean.TRUE.equals(standalone) || domain == null) return storedTeamId;
        return inventoryRepo.findByDomain(domain)
                .map(CertificateInventory::getTeamId)
                .orElse(storedTeamId);
    }

    /**
     * Duraklatma (active true→false) açık alarmları SESSİZCE kapatır — envanterin
     * {@code closeAlertsOnDeactivate} eşleniği. Dokuz izleme türünde yoktu: sweep'ler
     * {@code findByActiveTrue} yüklediğinden duraklatılan monitörün ne kurtarması ne re-alert'i
     * koşuyor, açık olay alarm geçmişi/olaylar/haftalık kesinti rollup'ında süresi büyüyerek
     * SONSUZA kadar açık kalıyordu.
     */
    private void closeAlertsOnPause(Boolean wasActive, Object nextActive, String key, Set<String> types) {
        if (Boolean.TRUE.equals(wasActive) && Boolean.FALSE.equals(nextActive) && key != null) {
            escalationService.resolveOpenAlertsSilently(key, types, "Sistem (izleme duraklatıldı)");
        }
    }

    /** Oluştururken hedef takımı çözer: global admin istediğini (veya takımsız) atar; diğerleri
     *  yalnız iş görebildikleri bir takıma — değilse kendi takımlarına düşer. */
    private Long resolveWriteTeam(HttpSession session, Map<String, Object> body) {
        Long requested = body.get("teamId") instanceof Number n ? n.longValue() : null;
        if (SessionScope.isGlobalAdmin(session)) return requested;
        if (requested != null && canOperateTeam(session, requested)) return requested;
        // Açıkça istenen ama iş görülemeyen takım: SESSİZCE birincile düşmek yerine reddet (2026-09-18).
        // Kullanıcı "X takımına ekledim" sanıp Y'de bulurdu; eski istemciler teamId göndermez → etkilenmez.
        if (requested != null) throw new SecurityException("Bu takıma izleme ekleme yetkiniz yok");
        return sessionTeamId(session);
    }

    /** Güncellemede takım değişimini çözer: admin serbest; diğerleri yalnız iş görebildikleri
     *  bir takıma taşıyabilir — yetkisiz/null hedef yok sayılır (mevcut takım korunur). */
    private Long resolveTeamChange(HttpSession session, Long current, Object requestedRaw) {
        Long requested = requestedRaw instanceof Number n ? n.longValue() : null;
        // Takım ZORUNLU: admin bile null'a çekemez → null gelirse mevcut takım korunur.
        if (SessionScope.isGlobalAdmin(session)) return requested != null ? requested : current;
        if (requested != null && canOperateTeam(session, requested)) return requested;
        return current;
    }

    /** Grup createdBy / audit için oturum kullanıcı adı. */
    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private ResponseEntity<Map<String, Object>> notFound(String msg) {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", msg));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", msg));
    }

    private ResponseEntity<Map<String, Object>> forbidden(String msg) {
        return ResponseEntity.status(403).body(Map.of("success", false, "error", msg));
    }

    /**
     * Grup + etiket zorunlu (2026-09-18, ürün kararı): "grup bilgisi olmayan izleme olmamalı, etiketi
     * olmayan izleme olmamalı". Dokuz tür + envanter aynı kuralı uygular. Oluşturmada alan YOK ya da
     * boş → 400; güncellemede yalnız GÖNDERİLİP boş bırakılmışsa 400 (kısmi PUT'lar — ör. excludePatterns —
     * anahtarı hiç taşımaz, onlara dokunulmaz). Sunucu tarafı kapı: form doğrulaması atlanabilir.
     */
    private ResponseEntity<Map<String, Object>> requireGroupAndTags(Map<String, Object> body, boolean create) {
        boolean groupMissing = create ? blank(body.get("groupName")) : (body.containsKey("groupName") && blank(body.get("groupName")));
        if (groupMissing) return badRequest("Grup seçimi zorunludur; izleme kaydedilemez.");
        boolean tagsMissing = create ? blank(body.get("tags")) : (body.containsKey("tags") && blank(body.get("tags")));
        if (tagsMissing) return badRequest("En az bir etiket zorunludur; izleme kaydedilemez.");
        return null;
    }

    private static boolean blank(Object o) {
        return o == null || o.toString().isBlank();
    }

    /** URL alan türlerde (sayfa/http/keyword) host çıkarılamayan girdi reddedilir — şemasız URL
     *  kontrol edilemez ve eskiden sessizce "kesinti" alarmı üretiyordu (2026-08-04). */
    private static final String INVALID_URL_MSG =
            "Geçersiz URL: geçerli bir host yok (örn. https://example.com)";

    // ── İzleme Grupları — TAKIM + izleme TÜRÜ bazlı; kullanıcı yalnız KENDİ takım(lar)ının gruplarını görür/rename eder ──

    /** Kapsam-filtreli grup listesi (form autocomplete + yönetim). teamId/type ile daraltılır — server-side team filtresi. */
    @GetMapping("/groups")
    public ResponseEntity<Map<String, Object>> listGroups(
            @RequestParam(required = false) Long teamId, @RequestParam(required = false) String type, HttpSession session) {
        permissionService.require(session, "monitoring.group", "view");
        List<Long> scope = SessionScope.viewTeamIds(session);   // null = global admin (tüm takımlar)
        if (teamId != null && !SessionScope.isGlobalViewer(session) && (scope == null || !scope.contains(teamId)))
            return forbidden("Bu takımın gruplarını görme yetkiniz yok");
        return ok(monitoringGroupService.listForScope(scope, teamId, blank(type) ? null : type.trim()));
    }

    /** Bir grubu (registry id) yeniden adlandırır — yalnız o türün monitörleri + o türün alarm geçmişi (takım-scope). */
    @PutMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> renameGroup(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.group", "edit");
        String newName = body.get("new_name") == null ? "" : body.get("new_name").toString();
        int affected = monitoringGroupService.rename(id, newName, session);   // 403/409/400 GlobalExceptionHandler'dan
        return ok(Map.of("affected", affected));
    }

    private static final Set<String> KW_OPERATORS = Set.of("GTE", "LTE", "EQ", "GT", "LT");
    private static final Set<String> DNS_RECORD_TYPES = Set.of("A", "AAAA", "CNAME", "MX", "TXT", "NS");
    /** Aktif-alarm rozeti + domain-rename temizliği için DNS alarm tipleri. */
    private static final Set<String> DNS_ALERT_TYPES = Set.of(
            EscalationService.TYPE_DNS_FAILURE, EscalationService.TYPE_DNS_CHANGED, EscalationService.TYPE_DNS_SLOW,
            EscalationService.TYPE_DNS_UNEXPECTED, EscalationService.TYPE_DNS_INCONSISTENT);

    /** Keyword adet koşulunu (operator + matchCount) body'den uygular; legacy 'condition' desteklenir;
     *  alertCondition (NOT NULL) operatörden türetilir. */
    /** Per-monitor teyit parametreleri için makul sınırlar (kullanıcı girişi). */
    private static int clampAttempts(int v) { return Math.max(0, Math.min(10, v)); }   // 0 = immediate (teyitsiz)
    private static int clampInterval(int v) { return Math.max(10, Math.min(600, v)); }
    private static int clampRecovery(int v) { return Math.max(1, Math.min(20, v)); }   // 1 = ilk up'ta kapat
    /** DNS_SLOW per-monitor eşiği (ms) — boş/geçersiz = null (global kullanılır); makul aralığa (100..60000) kırpılır. */
    private static Integer clampSlow(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            int v = o instanceof Number n ? n.intValue() : Integer.parseInt(o.toString().trim());
            return Math.max(100, Math.min(60000, v));
        } catch (Exception e) { return null; }
    }

    /** Port kontrol tipi — gecerli degilse TCP'ye duser. */
    private static final Set<String> PORT_TYPES = Set.of("TCP", "TLS", "HTTP", "BANNER", "UDP");
    private static String normalizePortType(Object o) {
        if (o == null) return "TCP";
        String s = o.toString().trim().toUpperCase();
        return PORT_TYPES.contains(s) ? s : "TCP";
    }

    /** HTTP metodu — geçerli değilse GET'e düşer. */
    private static final Set<String> HTTP_METHODS = Set.of("GET", "HEAD", "POST");
    private static String normalizeHttpMethod(Object o) {
        if (o == null) return "GET";
        String s = o.toString().trim().toUpperCase();
        return HTTP_METHODS.contains(s) ? s : "GET";
    }

    private void applyKeywordCondition(KeywordMonitor m, Map<String, Object> body) {
        if (body.get("operator") != null) {
            String op = body.get("operator").toString().toUpperCase();
            m.setMatchOperator(KW_OPERATORS.contains(op) ? op : "GTE");
        }
        if (body.get("matchCount") instanceof Number n) m.setMatchCount(Math.max(0, n.intValue()));
        else if (body.get("operator") == null && body.get("condition") != null) {
            if ("CONTAINS".equals(body.get("condition").toString())) { m.setMatchOperator("LTE"); m.setMatchCount(0); }
            else { m.setMatchOperator("GTE"); m.setMatchCount(1); }
        }
        String op = m.getMatchOperator() != null ? m.getMatchOperator() : "GTE";
        int n = m.getMatchCount() != null ? m.getMatchCount() : 1;
        boolean absent = ("LTE".equals(op) || "EQ".equals(op) || "LT".equals(op)) && n == 0;
        m.setAlertCondition(absent ? "CONTAINS" : "NOT_CONTAINS");
    }

    // ── Uptime Overview ───────────────────────────────────────────────────────

    @GetMapping("/uptime/overview")
    public ResponseEntity<Map<String, Object>> uptimeOverview(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        List<LatestCheck> latestChecks = latestCheckRepo.findAllByOrderByDomainAsc();
        Map<String, LatestCheck> checkMap = latestChecks.stream()
                .collect(Collectors.toMap(LatestCheck::getDomain, lc -> lc));

        // IDOR: envanter satirlari takim kapsamina TABI (listPort/listDns ile ayni kapi).
        // Bu ucun dondurdugu satir da host/port + takim adi + SSL bitisi + kesinti sayaclari
        // tasiyor; kardesleri suzulurken burasi atlanmisti (bkz. inventoryViewable).
        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc().stream()
                .filter(inv -> inventoryViewable(session, inv)).toList();
        Map<String, String> teamMap = certificateService.domainTeamNameMap();

        String cutoff30d = ISO.format(Instant.now().minus(30, ChronoUnit.DAYS));
        String cutoff15d = ISO.format(Instant.now().minus(15, ChronoUnit.DAYS));
        String cutoff7d  = ISO.format(Instant.now().minus(7,  ChronoUnit.DAYS));
        String cutoff1d  = ISO.format(Instant.now().minus(1,  ChronoUnit.DAYS));
        String cutoff24h = ISO.format(Instant.now().minus(24, ChronoUnit.HOURS));

        // Son 24 saat HTTP-OK: domain başına [total, upCount] SQL agregasyonu — 24h TÜM satırları
        // JVM'e yüklemek yerine (500 domain'de ~144k satır heap yükü giderildi). httpOk = up==total.
        Map<String, Boolean> httpOkByDomain = new HashMap<>();
        for (Object[] row : uptimeCheckRepo.aggregateHttpOkSince(cutoff24h)) {
            long total = ((Number) row[1]).longValue();
            long up    = ((Number) row[2]).longValue();
            httpOkByDomain.put((String) row[0], up == total);   // GROUP BY → total ≥ 1
        }

        // En güncel uptime kontrolü (domain:port) — tek sorgu (eski: domain başına findTop... → N+1).
        Map<String, UptimeCheck> latestUptime = uptimeCheckRepo.findLatestPerDomainPort().stream()
                .collect(Collectors.toMap(u -> u.getDomain() + ":" + u.getPort(), u -> u, (a, b) -> a));

        // Uptime % / incident özetleri — domain başına tüm-tablo taraması yerine iki gruplu DB sorgusu.
        Map<String, long[]> agg30 = aggregateByDomain(certCheckRepo.aggregateStatusCountsSince(cutoff30d));
        Map<String, long[]> agg15 = aggregateByDomain(certCheckRepo.aggregateStatusCountsSince(cutoff15d));
        Map<String, long[]> agg7  = aggregateByDomain(certCheckRepo.aggregateStatusCountsSince(cutoff7d));
        Map<String, long[]> agg1  = aggregateByDomain(certCheckRepo.aggregateStatusCountsSince(cutoff1d));

        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            String domain = inv.getDomain();
            LatestCheck lc = checkMap.get(domain);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("domain", domain);
            item.put("port",   inv.getPort());
            item.put("team_name", teamMap.get(domain));
            // Grup / etiket / kademe (2026-09-18): Durum İzleme grup-etiket filtresi + kart çipleri.
            item.put("group_name", inv.getGroupName());
            item.put("tags", inv.getTags());
            item.put("tier", inv.getTier());

            int port = inv.getPort() != null ? inv.getPort() : 443;
            Optional<UptimeCheck> uc = Optional.ofNullable(latestUptime.get(domain + ":" + port));

            // HTTP-OK: son 24h kontrolleri varsa hepsi "up" mı? (kayıt yoksa null → gösterme)
            Boolean httpOk = httpOkByDomain.get(domain);   // domain 24h'te yoksa null (aynı semantik)
            item.put("http_ok", httpOk);

            if (lc == null) {
                item.put("status",           uc.map(UptimeCheck::getStatus).orElse("unknown"));
                item.put("response_ms",      uc.map(UptimeCheck::getResponseMs).orElse(null));
                item.put("uptime_checked_at",uc.map(UptimeCheck::getCheckedAt).orElse(null));
                item.put("ssl_checked_at",   null);
                item.put("ssl_valid_days",   null);
                item.put("ssl_not_after",    null);
                item.put("uptime_7d",        null);
                item.put("uptime_30d",       null);
                item.put("incidents_1d",     0);
                item.put("incidents_7d",     0);
                item.put("incidents_15d",    0);
                item.put("incidents_30d",    0);
                result.add(item);
                continue;
            }

            String sslStatus = "error".equals(lc.getStatus()) ? "down" : "up";
            item.put("status",           uc.map(UptimeCheck::getStatus).orElse(sslStatus));
            item.put("response_ms",      uc.map(UptimeCheck::getResponseMs).orElse(null));
            item.put("uptime_checked_at",uc.map(UptimeCheck::getCheckedAt).orElse(null));
            item.put("ssl_checked_at",   lc.getCheckedAt());
            item.put("ssl_valid_days",   lc.getDaysRemaining());
            item.put("ssl_not_after",    lc.getNotAfter());

            // Uptime % — önceden hesaplanan domain-bazlı toplam/hata sayılarından (kayıt yoksa 100% / 0 olay).
            long[] s30 = agg30.get(domain);
            long[] s15 = agg15.get(domain);
            long[] s7  = agg7.get(domain);
            long[] s1  = agg1.get(domain);
            item.put("uptime_7d",  s7  == null ? 100.0 : uptimePct(s7[0],  s7[1]));
            item.put("uptime_30d", s30 == null ? 100.0 : uptimePct(s30[0], s30[1]));
            item.put("incidents_1d",  s1  == null ? 0L : s1[1]);
            item.put("incidents_7d",  s7  == null ? 0L : s7[1]);
            item.put("incidents_15d", s15 == null ? 0L : s15[1]);
            item.put("incidents_30d", s30 == null ? 0L : s30[1]);

            result.add(item);
        }

        return ok(result);
    }

    /** aggregateStatusCountsSince satırlarını domain → [toplam, hata] map'ine indeksle. */
    private static Map<String, long[]> aggregateByDomain(List<Object[]> rows) {
        Map<String, long[]> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put((String) r[0], new long[]{ ((Number) r[1]).longValue(), ((Number) r[2]).longValue() });
        }
        return m;
    }

    /**
     * toplam/hata → uptime yüzdesi ("error" dışı = up).
     *
     * <p>Formül {@code WeeklyAvailabilityReportService}'teki kardeşiyle BİREBİR aynı olmak
     * zorunda: aynı monitör için iki yüzey farklı sayı gösteriyordu. Burası 1 ondalıkla
     * yuvarlıyor ve korumasızdı — 2000 kontrolde 1 hata {@code 999.5 → Math.round → 1000 → 100.0}
     * veriyordu, yani izleme listesinde AYNI satır {@code uptime_30d: 100.0} ile
     * {@code incidents_30d: 1} gösteriyor, haftalık rapor ise aynı monitör için 99.95 diyordu.
     *
     * <p>İki değişiklik: 2 ondalık hassasiyet (kardeşle aynı) ve "hiç down örneği varsa asla
     * 100 gösterme" koruması. Arayüz bu alanı ham basıyor (UptimePage {@code {item.uptime_7d}%}),
     * ek bir yuvarlama katmanı yok — yani 99.99 ekrana da 99.99 olarak çıkar.
     */
    private static double uptimePct(long total, long errors) {
        if (total == 0) return 100.0;
        long up = total - errors;
        double pct = Math.round(up * 10000.0 / total) / 100.0;
        if (pct >= 100.0 && up < total) pct = 99.99;
        return pct;
    }

    /** Normalize date/datetime strings to full ISO-8601 (19 chars) for "from" range end. */
    private static String normalizeFrom(String s) {
        if (s == null) return s;
        if (s.length() == 10) return s + "T00:00:00";   // date only  → start of day
        if (s.length() == 16) return s + ":00";          // HH:MM      → :00 seconds
        return s;
    }

    /** Normalize date/datetime strings to full ISO-8601 (19 chars) for "to" range end. */
    private static String normalizeTo(String s) {
        if (s == null) return s;
        if (s.length() == 10) return s + "T23:59:59";   // date only  → end of day
        if (s.length() == 16) return s + ":59";          // HH:MM      → :59 seconds
        return s;
    }

    /** Saatlik uptime çubukları. Kardeş uçlar (/http-history, /ssl-history) gibi takım denetimi yapar
     *  ve {@code hours} kırpılır: eskiden imzada HttpSession bile yoktu (başka takımın geçmişi okunabiliyordu)
     *  ve hours sınırsızdı — {@code hours=200000} tek worker'ı dakikalarca meşgul eden 10⁹ karşılaştırma
     *  üretiyordu (aşağıdaki döngü saat başına tüm listeyi tarıyor). */
    private static final int UPTIME_HISTORY_MAX_HOURS = 24 * 90;   // 90 gün: grafik önayarlarının tavanı

    @GetMapping("/uptime/{domain}/history")
    public ResponseEntity<Map<String, Object>> uptimeHistory(
            @PathVariable String domain, HttpSession session,
            @RequestParam(defaultValue = "24") int hours) {

        var deny = denyIfDomainNotViewable(session, domain);
        if (deny != null) return deny;
        hours = Math.max(1, Math.min(hours, UPTIME_HISTORY_MAX_HOURS));

        String cutoff = ISO.format(Instant.now().minus(hours, ChronoUnit.HOURS));
        String nowIso = ISO.format(Instant.now());
        // Domain-kapsamlı sorgu (idx_cc_domain_ts) — tüm cert_checks tablosunu yükleyip
        // in-memory domain filtrelemek yerine; tek domain'in geçmişi.
        List<CertificateCheck> checks = certCheckRepo.findByDomainAndDateRange(domain, cutoff, nowIso, 5000).stream()
                .sorted(Comparator.comparing(CertificateCheck::getCheckedAt))
                .toList();

        // Build hourly bars
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = hours - 1; i >= 0; i--) {
            LocalDateTime hourStart = now.minusHours(i).truncatedTo(ChronoUnit.HOURS);
            LocalDateTime hourEnd   = hourStart.plusHours(1);
            String startStr = hourStart.atZone(ZoneOffset.UTC).format(ISO);
            String endStr   = hourEnd.atZone(ZoneOffset.UTC).format(ISO);

            List<CertificateCheck> hourChecks = checks.stream()
                    .filter(c -> c.getCheckedAt() != null
                            && c.getCheckedAt().compareTo(startStr) >= 0
                            && c.getCheckedAt().compareTo(endStr) < 0)
                    .toList();

            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("hour", startStr);
            if (hourChecks.isEmpty()) {
                bar.put("status", "unknown");
                bar.put("response_ms", null);
            } else {
                boolean anyError = hourChecks.stream().anyMatch(c -> "error".equals(c.getStatus()));
                bar.put("status", anyError ? "down" : "up");
                bar.put("response_ms", null);
            }
            bars.add(bar);
        }

        // Response time series (individual check points)
        List<Map<String, Object>> responseTimes = checks.stream()
                .map(c -> {
                    Map<String, Object> pt = new LinkedHashMap<>();
                    pt.put("ts", c.getCheckedAt());
                    pt.put("status", c.getStatus());
                    return pt;
                })
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", domain);
        data.put("hours",  hours);
        data.put("bars",   bars);
        data.put("response_times", responseTimes);

        return ok(data);
    }

    // ── HTTP Uptime History ───────────────────────────────────────────────────

    @GetMapping("/uptime/{domain}/http-history")
    public ResponseEntity<?> uptimeHttpHistory(
            @PathVariable String domain, HttpSession session,
            @RequestParam(defaultValue = "443") int port,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        // Eskiden session bile almıyordu (BOLA) — artık envanter takımı üzerinden görüntüleme denetimi var.
        var deny = denyIfDomainNotViewable(session, domain);
        if (deny != null) return deny;
        var src = new CheckHistoryService.Source<UptimeCheck>() {
            public org.springframework.data.domain.Page<UptimeCheck> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? uptimeCheckRepo.findByDomainAndPortAndStatusNotAndCheckedAtBetween(domain, port, "up", f, t, p)
                            : uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetween(domain, port, f, t, p);
            }
            public long total(String f, String t) { return uptimeCheckRepo.countByDomainAndPortAndCheckedAtBetween(domain, port, f, t); }
            public long fail(String f, String t) { return uptimeCheckRepo.countByDomainAndPortAndStatusNotAndCheckedAtBetween(domain, port, "up", f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return uptimeCheckRepo.historyHistogram(domain, port, f, t, len); }
            public List<Object[]> bounds() { return uptimeCheckRepo.historyBounds(domain, port); }
        };
        var r = checkHistoryService.resolve(new CheckHistoryService.Query(from, to, parseDaysSafe(days), status, page, size),
                historyRetentionDays("uptime"));
        if ("csv".equalsIgnoreCase(format)) {
            try {
                checkHistoryService.writeCsv(src, r, "uptime-http-" + domain, List.of(
                        new CsvColumn<>("checked_at", UptimeCheck::getCheckedAt),
                        new CsvColumn<>("status", UptimeCheck::getStatus),
                        new CsvColumn<>("response_ms", UptimeCheck::getResponseMs),
                        new CsvColumn<>("error", UptimeCheck::getError)), response);
                return null;
            } catch (java.io.IOException e) { throw new RuntimeException("CSV yazımı başarısız", e); }
        }
        return ok(checkHistoryService.execute(src, r, domain, Set.of(EscalationService.TYPE_ACCESSIBILITY)));
    }

    // ── SSL Certificate History ───────────────────────────────────────────────

    @GetMapping("/uptime/{domain}/ssl-history")
    public ResponseEntity<?> uptimeSslHistory(
            @PathVariable String domain, HttpSession session,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        var deny = denyIfDomainNotViewable(session, domain);
        if (deny != null) return deny;
        var src = new CheckHistoryService.Source<CertificateCheck>() {
            public org.springframework.data.domain.Page<CertificateCheck> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? certCheckRepo.findByDomainAndStatusAndCheckedAtBetween(domain, "error", f, t, p)
                            : certCheckRepo.findByDomainAndCheckedAtBetween(domain, f, t, p);
            }
            public long total(String f, String t) { return certCheckRepo.countByDomainAndCheckedAtBetween(domain, f, t); }
            public long fail(String f, String t) { return certCheckRepo.countByDomainAndStatusAndCheckedAtBetween(domain, "error", f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return certCheckRepo.historyHistogram(domain, f, t, len); }
            public List<Object[]> bounds() { return certCheckRepo.historyBounds(domain); }
        };
        var r = checkHistoryService.resolve(new CheckHistoryService.Query(from, to, parseDaysSafe(days), status, page, size),
                historyRetentionDays("ssl"));
        if ("csv".equalsIgnoreCase(format)) {
            try {
                checkHistoryService.writeCsv(src, r, "uptime-ssl-" + domain, List.of(
                        new CsvColumn<>("checked_at", CertificateCheck::getCheckedAt),
                        new CsvColumn<>("status", CertificateCheck::getStatus),
                        new CsvColumn<>("days_remaining", CertificateCheck::getDaysRemaining),
                        new CsvColumn<>("error", CertificateCheck::getError)), response);
                return null;
            } catch (java.io.IOException e) { throw new RuntimeException("CSV yazımı başarısız", e); }
        }
        return ok(checkHistoryService.execute(src, r, domain, EscalationService.CERT_ALERT_TYPES));
    }

    /**
     * Sertifika yanıt süresi grafiği — diğer yedi türle AYNI huni (resolveRange → responseSeriesRaw →
     * buildResponseSeries). İki seri taşır: ana seri kontrol süresi (ms, response_ms kolonu yeni olduğu
     * için ileriye dönük dolar) + yardımcı seri "days" (kalan gün, 180 günlük geçmişten dolu gelir).
     * Yetki BİLE BİLE ssl-history ile aynı: yalnız denyIfDomainNotViewable. permissionService.require(
     * "monitoring.read") EKLENMEZ — sertifikayı görüp geçmişini açabilen kullanıcı grafikte 403 almamalı.
     * Yol adı "/ssl/response-series": sözleşme testi "/response-series" ile biten uçları sayar.
     */
    @GetMapping("/uptime/{domain}/ssl/response-series")
    public ResponseEntity<?> uptimeSslResponseSeries(
            @PathVariable String domain, HttpSession session,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days) {
        var deny = denyIfDomainNotViewable(session, domain);
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        // Geçmiş sekmesiyle tutarlılık: seri de retention penceresine kırpılır, aksi halde 180 gün
        // öncesi özel aralık seçildiğinde sessizce boş grafik çıkardı (geçmiş sekmesi uyarı basıyor).
        String clampedFrom = clampToRetention(range[0], historyRetentionDays("ssl"));
        return ok(buildResponseSeries(
                certCheckRepo.responseSeriesRaw(domain, clampedFrom, range[1], SERIES_RAW_CAP),
                clampedFrom, range[1], "days"));
    }

    /** Domain-anahtarlı uptime/ssl geçmişi için takım denetimi: envanter kaydının teamId VEYA ugTeamId'si
     *  görüntülenebilir olmalı (izleme monitörlerindeki denyIfNotViewable'ın domain karşılığı). */
    private ResponseEntity<Map<String, Object>> denyIfDomainNotViewable(HttpSession session, String domain) {
        var inv = inventoryRepo.findByDomain(domain).orElse(null);
        if (inv == null) return notFound("Domain envanterde bulunamadı");
        if (inventoryViewable(session, inv)) return null;
        return forbidden("Bu domain'in geçmişini görüntüleme yetkiniz yok");
    }

    /**
     * Envanter satırı bu oturumun görüş kapsamında mı — sorumlu (SY) VEYA uç gözetim (UG) takımı.
     *
     * <p>{@link #denyIfDomainNotViewable}'ın LISTE karşılığı. {@code listPort}/{@code listDns}
     * envanter döngüsü bu kapıyı atladığı için {@code monitoring.read} yetkisi olan HERKES tüm
     * envanter alan adlarını (host:port, takım adı, son kontrol, açık alarm) görüyordu — oysa aynı
     * metodun standalone döngüsü ve diğer yedi liste ucu süzüyordu. Kapı artık TEK yerde.
     */
    private boolean inventoryViewable(HttpSession session, CertificateInventory inv) {
        if (inv == null) return false;
        if (SessionScope.canView(session, inv.getTeamId())) return true;
        return inv.getUgTeamId() != null && SessionScope.canView(session, inv.getUgTeamId());
    }

    /**
     * Lazy-provision yarışından sonra port monitör haritasını DB'den tazeler.
     *
     * <p>Kaybeden istekte transaction geri alınır: bizim eklediğimiz satırların HİÇBİRİ kalmaz.
     * Bu yüzden haritayı sıfırdan kurmak şart — kısmi güncelleme yapsaydık kazananın yarattığı
     * satırlar eksik kalır ve liste o domainleri hiç göstermezdi.
     */
    private void reloadPortMonitors(Map<String, PortMonitor> monitorByKey) {
        monitorByKey.clear();
        for (PortMonitor m : portMonitorRepo.findAll()) {
            if (Boolean.TRUE.equals(m.getStandalone())) continue;
            monitorByKey.merge(m.getHost() + ":" + m.getPort(), m, (a, b) -> a.getId() <= b.getId() ? a : b);
        }
    }

    /** {@link #reloadPortMonitors} ile aynı gerekçe — DNS tarafı. */
    private void reloadDnsMonitors(Map<String, DnsMonitor> monitorByDomain) {
        monitorByDomain.clear();
        for (DnsMonitor m : dnsMonitorRepo.findAll()) {
            if (Boolean.TRUE.equals(m.getStandalone())) continue;
            monitorByDomain.merge(m.getDomain(), m, (a, b) -> a.getId() <= b.getId() ? a : b);
        }
    }

    // ── Port Monitors ─────────────────────────────────────────────────────────

    @GetMapping("/port")
    public ResponseEntity<Map<String, Object>> listPort(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        String now = ISO.format(Instant.now());

        // Tüm monitörleri tek sorguda yükle, host:port ile indeksle (en küçük id = findFirst...OrderByIdAsc).
        Map<String, PortMonitor> monitorByKey = new HashMap<>();
        for (PortMonitor m : portMonitorRepo.findAll()) {
            if (Boolean.TRUE.equals(m.getStandalone())) continue;   // standalone'lar envantere bağlı değil — ayrı işlenir
            monitorByKey.merge(m.getHost() + ":" + m.getPort(), m, (a, b) -> a.getId() <= b.getId() ? a : b);
        }

        // Eksik domainler için monitörleri tek batch insert'te lazy-provision et (eski: döngüde tek tek save).
        List<PortMonitor> toCreate = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            int invPort = inv.getPort() != null ? inv.getPort() : 443;
            String key = inv.getDomain() + ":" + invPort;
            if (!monitorByKey.containsKey(key)) {
                PortMonitor m = new PortMonitor();
                m.setName(inv.getDomain());
                m.setHost(inv.getDomain());
                m.setTeamId(inv.getTeamId());   // envanter-türevi: takım envanter domain'inden (takım zorunlu)
                m.setPort(invPort);
                m.setProtocol("TCP");
                m.setActive(true);
                m.setIntervalSeconds(300);
                m.setTimeoutMs(5000);
                m.setCreatedAt(now);
                m.setUpdatedAt(now);
                monitorByKey.put(key, m);
                toCreate.add(m);
            }
        }
        if (!toCreate.isEmpty()) {
            // Benzersizlik kısıtı (uq_pm_host_port) artık DB'de: eşzamanlı iki istek aynı domaini
            // görürse biri çakışır. Kaybeden istek 500 almaz — tabloyu YENİDEN OKUR ve kazananın
            // satırlarını kullanır. Kısıt olmadan bu yol sessizce N mükerrer monitör üretiyordu.
            try {
                portMonitorRepo.saveAll(toCreate);
            } catch (org.springframework.dao.DataIntegrityViolationException race) {
                log.debug("Port monitör lazy-provision yarışı — yeniden okunuyor: {}", race.getMessage());
                reloadPortMonitors(monitorByKey);
            }
        }

        // Her monitör için en güncel kontrol — tek toplu sorgu (eski: monitör başına findTop... → N+1).
        Map<Long, PortCheck> latestByMonitor = portCheckRepo.findLatestPerMonitor().stream()
                .filter(pc -> pc.getMonitorId() != null)
                .collect(Collectors.toMap(PortCheck::getMonitorId, pc -> pc, (a, b) -> a));

        Map<String, String> teamMap = certificateService.domainTeamNameMap();
        Map<Long, String> teamById = teamNameMap();
        List<PortMonitor> standaloneMonitors = portMonitorRepo.findByStandaloneTrueAndActiveTrue();
        // Açık PORT_DOWN alarmları host→AlertEvent (envanter + standalone host'ları) — liste rozeti, tek sorgu.
        // IDOR: yalnız görüş kapsamındaki envanter satırları listelenir (bkz. inventoryViewable).
        List<CertificateInventory> visibleInventory = inventory.stream()
                .filter(inv -> inventoryViewable(session, inv)).toList();
        Set<String> alarmHosts = new HashSet<>();
        for (CertificateInventory inv : visibleInventory) alarmHosts.add(inv.getDomain());
        for (PortMonitor m : standaloneMonitors) if (m.getHost() != null) alarmHosts.add(m.getHost());
        Map<String, AlertEvent> portAlarms = openAlarmsByDomain(alarmHosts, EscalationService.TYPE_PORT_DOWN);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : visibleInventory) {
            int invPort = inv.getPort() != null ? inv.getPort() : 443;
            PortMonitor monitor = monitorByKey.get(inv.getDomain() + ":" + invPort);
            // Yarış sonrası hâlâ eksikse SATIRI ATLA. Eskiden burada monitor DAİMA dolu varsayılıyordu
            // ve provision başarısız olsa NPE → 500 olurdu: tüm liste, tek bir domain yüzünden ölürdü.
            if (monitor == null) continue;
            PortCheck latest = monitor.getId() != null ? latestByMonitor.get(monitor.getId()) : null;
            result.add(enrichPort(monitor, latest, teamMap, teamById, portAlarms.get(monitor.getHost())));
        }
        // Standalone (envanterden bağımsız) port monitörleri — envanter döngüsünde yok; takım görüşüne göre ekle.
        for (PortMonitor m : standaloneMonitors) {
            if (!SessionScope.canView(session, m.getTeamId())) continue;
            PortCheck latest = m.getId() != null ? latestByMonitor.get(m.getId()) : null;
            result.add(enrichPort(m, latest, teamMap, teamById, portAlarms.get(m.getHost())));
        }
        return ok(result);
    }

    @PostMapping("/port")
    public ResponseEntity<Map<String, Object>> createPort(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, true); if (_gt != null) return _gt; }   // grup + etiket zorunlu (2026-09-18)
        if (blank(body.get("host"))) return badRequest("host zorunlu");
        if (!(body.get("port") instanceof Number)) return badRequest("port zorunlu");
        String host = body.get("host").toString().trim().toLowerCase(java.util.Locale.ROOT);   // envanterle aynı anahtar (küçük harf)
        int port = ((Number) body.get("port")).intValue();
        if (port < 1 || port > 65535) return badRequest("port 1-65535 aralığında olmalı");
        if (!blank(body.get("sendData"))) requireAdmin(session);   // ham payload → yalnız admin (iç-servis SSRF payload'u)
        // Aynı host:port zaten AKTİF izleniyorsa tekrar ekleme (otomatik :443 kayıtlarıyla çakışmayı da önler).
        // findFirst…ByIdAsc EN ESKİ satırı döndürüyordu: o satır soft-delete (active=false) ise daha
        // yeni aktif kopya guard'a görünmüyor, üçüncü aktif kopya oluşuyordu (iki kontrol, iki alarm
        // akışı). "Herhangi bir aktif var mı?" sorusu doğrudan DB'de.
        if (portMonitorRepo.existsByHostAndPortAndActiveTrue(host, port))
            return badRequest("Bu host:port zaten izleniyor");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null)
            return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        String now = ISO.format(Instant.now());
        PortMonitor m = new PortMonitor();
        m.setName(blank(body.get("name")) ? host : body.get("name").toString().trim());
        m.setHost(host);
        m.setPort(port);
        m.setProtocol(normalizePortType(body.get("protocol")));
        m.setExpect(blank(body.get("expect")) ? null : body.get("expect").toString().trim());
        m.setSendData(blank(body.get("sendData")) ? null : body.get("sendData").toString());
        m.setActive(true);                                            // varsayılan: yeni izleme aktif
        if (body.get("active") instanceof Boolean b) m.setActive(b);  // Kopyala: pasif kaynağın kopyası da pasif doğsun
        m.setStandalone(true);          // kullanıcı-eklediği → envanterden bağımsız; her zaman listelenir + kontrol edilir
        m.setTeamId(teamId);
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
        if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
        if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
        if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
        applyPortFeatureFields(m, body);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        monitorHistory.stampCreated(m, session);
        PortMonitor saved = portMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.PORT, saved.getId(), saved.getName(),
                saved.getHost() + ":" + saved.getPort(), teamId, "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "PORT_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        // İLK DEĞERLER: denetim kaydı create'te changes=null geçiyor (bilinçli, güvenlik kaydı
        // "ne oldu"yu yazar). Ürün geçmişi ise "hangi değerlerle doğdu" sorusunu cevaplamak
        // zorunda — snapshot BURADA alınır.
        monitorHistory.record(MonitorHistoryService.PORT, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
        return ok(enrichPort(saved, null, certificateService.domainTeamNameMap(), teamNameMap(),
                alertEventRepo.findOpenAlert(saved.getHost(), EscalationService.TYPE_PORT_DOWN).orElse(null)));
    }

    @PutMapping("/port/{id}")
    public ResponseEntity<Map<String, Object>> updatePort(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, false); if (_gt != null) return _gt; }   // gönderilip boş bırakılmışsa 400 (2026-09-18)
        java.util.Map<String, Object> _before = portMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return portMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, effectiveTeam(m.getHost(), m.getStandalone(), m.getTeamId())))
                return forbidden("Bu izleme üzerinde yetkiniz yok");
            final String  _prevHost = m.getHost();
            final Integer _prevPort = m.getPort();
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("host")            != null) m.setHost(((String) body.get("host")).trim().toLowerCase(java.util.Locale.ROOT));
            if (body.get("port")            != null) {
                int np = ((Number) body.get("port")).intValue();
                if (np < 1 || np > 65535) return badRequest("port 1-65535 aralığında olmalı");
                m.setPort(np);
            }
            // Mükerrer koruması: createPort ve updateDns bu guard'ı taşıyor, updatePort taşımıyordu →
            // kullanıcı mevcut bir monitörün host/port'unu başkasınınkiyle aynı yapabiliyordu.
            // DB kısıtı da yakalamaz (uq_pm_host_port … WHERE standalone IS NOT TRUE).
            if (!eqHost(_prevHost, m.getHost()) || !Objects.equals(_prevPort, m.getPort())) {
                boolean dup = portMonitorRepo.existsByHostAndPortAndActiveTrueAndIdNot(m.getHost(), m.getPort(), id);
                if (dup) return badRequest("Bu host:port zaten izleniyor");
            }
            if (body.get("protocol")        != null) m.setProtocol(normalizePortType(body.get("protocol")));
            if (body.containsKey("expect"))    m.setExpect(blank(body.get("expect")) ? null : body.get("expect").toString().trim());
            if (body.containsKey("sendData")) {
                if (!blank(body.get("sendData"))) requireAdmin(session);   // ham payload → yalnız admin (iç-servis SSRF payload'u)
                m.setSendData(blank(body.get("sendData")) ? null : body.get("sendData").toString());
            }
            closeAlertsOnPause(m.getActive(), body.get("active"), m.getHost(), Set.of(EscalationService.TYPE_PORT_DOWN, EscalationService.TYPE_PORT_SLOW));
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.containsKey("teamId"))    m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            applyPortFeatureFields(m, body);
            boolean detached = detachIfIdentityChanged(m, _prevHost, _prevPort);
            m.setUpdatedAt(ISO.format(Instant.now()));
            monitorHistory.stampUpdated(m, session);
            PortMonitor saved = portMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "PORT_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            // Aynı before/after çifti geçmişe de gider — audit çağrısına DOKUNULMAZ.
            var changeRow = monitorHistory.record(MonitorHistoryService.PORT, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
            noteConfigChanged(changeRow, ActivityLogService.PORT, saved.getHost() + ":" + saved.getPort(), session);
            Map<String, Object> item = enrichPort(saved, portCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null),
                    certificateService.domainTeamNameMap(), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getHost(), EscalationService.TYPE_PORT_DOWN).orElse(null));
            item.put("detached_from_inventory", detached);
            return ok(item);
        }).orElse(notFound("Port monitor not found"));
    }

    @DeleteMapping("/port/{id}")
    public ResponseEntity<Map<String, Object>> deletePort(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        // Silme ÖNCESİ durum: aşağıda active=false yapılıyor, sonra almak farkı kaybettirirdi.
        Map<String, Object> _before = portMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return portMonitorRepo.findById(id).map(m -> {
            // Silme kapısı kardeşlerin yedisiyle aynı: canManage (TEAM_ADMIN+). Port, DNS ile
            // birlikte canOperateTeam kullanan iki aykırıydı; arayüz zaten USER'a silme
            // göstermiyordu, dolayısıyla bu yalnız API-only boşluğu kapatır.
            Long team = effectiveTeam(m.getHost(), m.getStandalone(), m.getTeamId());
            if (!SessionScope.canManage(session, team)) return forbidden("Bu izleme üzerinde yetkiniz yok");
            m.setActive(false);
            m.setUpdatedAt(ISO.format(Instant.now()));
            monitorHistory.stampUpdated(m, session);
            portMonitorRepo.save(m);
            activityLog.recordLifecycle(ActivityLogService.PORT, m.getId(), m.getName(),
                    m.getHost() + ":" + m.getPort(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "PORT_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            monitorHistory.record(MonitorHistoryService.PORT, m.getId(), m.getName(), m.getTeamId(),
                    MonitorHistoryService.DELETE, _before, AuditDiff.snapshot(m, MON_FIELDS), null, session);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Port monitor not found"));
    }

    @GetMapping("/port/{id}/history")
    public ResponseEntity<?> portHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        PortMonitor mon = portMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Port monitor not found");
        var src = new CheckHistoryService.Source<PortCheck>() {
            public org.springframework.data.domain.Page<PortCheck> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? portCheckRepo.findByMonitorIdAndOpenFalseAndCheckedAtBetween(id, f, t, p)
                            : portCheckRepo.findByMonitorIdAndCheckedAtBetween(id, f, t, p);
            }
            public long total(String f, String t) { return portCheckRepo.countByMonitorIdAndCheckedAtBetween(id, f, t); }
            public long fail(String f, String t) { return portCheckRepo.countByMonitorIdAndOpenFalseAndCheckedAtBetween(id, f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return portCheckRepo.historyHistogram(id, f, t, len); }
            public List<Object[]> bounds() { return portCheckRepo.historyBounds(id); }
        };
        return runHistory(session, effectiveTeam(mon.getHost(), mon.getStandalone(), mon.getTeamId()), src, "port",
                mon.getHost(), Set.of(EscalationService.TYPE_PORT_DOWN, EscalationService.TYPE_PORT_SLOW),
                from, to, days, status, page, size, format, "port-history-" + id, List.of(
                new CsvColumn<>("checked_at", PortCheck::getCheckedAt),
                new CsvColumn<>("open", PortCheck::getOpen),
                new CsvColumn<>("response_ms", PortCheck::getResponseMs),
                new CsvColumn<>("error", PortCheck::getError)), response);
    }

    @PostMapping("/port/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerPort(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return portMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, effectiveTeam(m.getHost(), m.getStandalone(), m.getTeamId())))
                return forbidden("Bu izleme üzerinde yetkiniz yok");
            Map<String, Object> r = portChecker.check(m);
            String now = ISO.format(Instant.now());
            PortCheck check = new PortCheck();
            check.setMonitorId(m.getId());
            check.setOpen((Boolean) r.getOrDefault("open", false));
            check.setResponseMs(r.get("response_ms") != null ? ((Number) r.get("response_ms")).longValue() : null);
            check.setError((String) r.get("error"));
            check.setCheckedAt(now);
            portCheckRepo.save(check);
            auditService.recordAction("MONITOR_TRIGGER", session, "PORT_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            // ...ve ARDINDAN zamanlayıcıyla AYNI değerlendirme hattı ASENKRON başlar: hata
            // doğrulama denemelerinden geçer, teyit edilirse alarm açılır; düzelme kurtarma
            // sayacından geçer. Eskiden manuel çalıştırma tek kontrol yapıp bırakıyordu —
            // ekranda "hata" görünüyor ama alarm hiç açılmıyordu (iki farklı gerçek).
            schedulerService.evaluatePortNow(m, r);   // AYNI sonuç — ikinci kontrol/kayıt YOK
            return ok(enrichPort(m, check, certificateService.domainTeamNameMap(), teamNameMap(),
                    alertEventRepo.findOpenAlert(m.getHost(), EscalationService.TYPE_PORT_DOWN).orElse(null)));
        }).orElse(notFound("Port monitor not found"));
    }

    /** Kaydetmeden canli kontrol — formdaki tip/expect/sendData ile bir kez calistirir; ne dondugunu + alarm
     *  kosulunun (open) saglanip saglanmadigini doner. Kayit olusturmaz/guncellemez. */
    @PostMapping("/port/test")
    public ResponseEntity<Map<String, Object>> testPort(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        if (blank(body.get("host"))) return badRequest("host zorunlu");
        if (!(body.get("port") instanceof Number)) return badRequest("port zorunlu");
        String host = body.get("host").toString().trim();
        int port = ((Number) body.get("port")).intValue();
        if (port < 1 || port > 65535) return badRequest("port 1-65535 araliginda olmali");
        int timeoutMs = body.get("timeoutMs") instanceof Number tn ? tn.intValue() : 5000;
        String type = normalizePortType(body.get("protocol"));
        String send = blank(body.get("sendData")) ? null : body.get("sendData").toString();
        if (send != null) requireAdmin(session);   // ham payload (BANNER/UDP arbitrary bayt) → yalnız admin (iç-servis SSRF payload'u)
        String expect = blank(body.get("expect")) ? null : body.get("expect").toString().trim();
        // Denetim: dış bağlantıdan ÖNCE. Bu uç sunucudan keyfi host:port'a el sıkışması açtırır;
        // "kim sunucuya neye bağlanmasını söyledi" sorusu, çağrı timeout'a düşse de cevaplanmalı.
        // Payload İÇERİĞİ yazılmaz (iç-servis komutu olabilir) — yalnız varlığı.
        auditService.recordAction("MONITOR_TEST", session, "PORT_MONITOR", "test",
                AuditDetail.of("host", host, "port", port, "protocol", type,
                        "send_data", send != null, "expect", expect != null), null);
        String ipVersion = body.get("ipVersion") != null && java.util.Set.of("v4", "v6", "auto").contains(body.get("ipVersion").toString())
                ? body.get("ipVersion").toString() : "auto";
        Map<String, Object> r = portChecker.check(host, port, timeoutMs, type, send, expect, ipVersion);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type",          type);
        out.put("open",          Boolean.TRUE.equals(r.get("open")));   // kontrol gecti mi (acik/eslesti)
        out.put("condition_met", Boolean.TRUE.equals(r.get("open")));   // alarm kosulu = kontrolun gecmesi
        out.put("response_ms",   r.get("response_ms"));
        out.put("detail",        r.get("detail"));                      // donen: HTTP durum / banner / TLS surumu
        out.put("error",         r.get("error"));
        out.put("expect",        expect);
        return ok(out);
    }

    private Map<String, Object> enrichPort(PortMonitor m, PortCheck latest, Map<String, String> teamMap, Map<Long, String> teamById, AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",              m.getId());
        item.put("name",            m.getName());
        item.put("host",            m.getHost());
        // Manuel eklenen kayıt takımı teamId'den; otomatik üretilen (teamId=null) kayıt domain→takım haritasından.
        // team_id ile team_name AYNI kaynaktan (effectiveTeam) — bkz. enrichDns.
        item.put("team_id",         effectiveTeam(m.getHost(), m.getStandalone(), m.getTeamId()));
        item.put("notification_group_id",         m.getNotificationGroupId());
        item.put("team_name",       m.getTeamId() != null ? teamById.get(m.getTeamId()) : teamMap.get(m.getHost()));
        item.put("group_name",      m.getGroupName());
        item.put("port",            m.getPort());
        item.put("protocol",        m.getProtocol());
        item.put("expect",          m.getExpect());
        item.put("send_data",       m.getSendData());
        item.put("active",          m.getActive());
        item.put("interval_seconds",m.getIntervalSeconds());
        item.put("timeout_ms",      m.getTimeoutMs());
        item.put("confirm_attempts",          m.getConfirmAttempts());
        item.put("confirm_interval_seconds",  m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("tags",                      m.getTags());
        item.put("alert_level",     com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
        item.put("notify_email",              m.getNotifyEmail());
        item.put("notify_webhook",              m.getNotifyWebhook());
        item.put("slow_response_enabled",     m.getSlowResponseEnabled());
        item.put("slow_threshold_ms",         m.getSlowThresholdMs());
        item.put("ip_version",                m.getIpVersion());
        item.put("standalone",      m.getStandalone());
        item.put("created_at",      m.getCreatedAt());
        item.put("updated_at",      m.getUpdatedAt());
        item.put("active_alarm",       openAlarm != null);
        item.put("alarm_level",        openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
        if (latest != null) {
            item.put("status",      latest.getOpen() ? "open" : "closed");
            item.put("response_ms", latest.getResponseMs());
            item.put("checked_at",  latest.getCheckedAt());
            item.put("error",       latest.getError());
        } else {
            item.put("status",      "unknown");
            item.put("response_ms", null);
            item.put("checked_at",  null);
            item.put("error",       null);
        }
        return item;
    }

    /** Ortak: port feature alanlarını (tags/notifyEmail/slow-response/ipVersion) body'den uygular. */
    /**
     * Envanter-turevi bir monitorun KIMLIK alani degistiyse envanter bagini kopar.
     *
     * <p><b>Neden sart.</b> {@code listPort}/{@code listDns} envanter-turevi monitoru
     * {@code host:port} (DNS'te {@code domain}) DEGERININ KENDISIYLE eslestiriyor. Kullanici hedefi
     * degistirince o anahtar ortadan kalkar, liste envanter domain'ini "eksik" sanar ve ESKI hedefle
     * yepyeni bir monitor uretip kaydeder. Kullanicinin duzenlemesi geri donmus gorunur (sessiz veri
     * kaybi) ve her duzenleme bir satir daha dogurur.
     *
     * <p>Kullanici hedefi envanterdekinden baska bir yere cevirdiyse o satir artik envantere bagli
     * degildir — {@code standalone}'un tanimi zaten budur. Envanter domain'i bir sonraki listede
     * yeniden turetilir: bu DOGRU davranis, cunku envanterde duran domain'in kapsamasi surmelidir.
     * Fark su ki artik kullanicinin kaydini EZEREK degil, yaninda oluyor.
     *
     * @return baglantinin bu istekte koparilmis olup olmadigi (arayuz kullaniciyi bilgilendirir)
     */
    /** Host/alan adı karşılaştırması — DNS harf duyarsızdır. */
    private static boolean eqHost(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static boolean detachIfIdentityChanged(PortMonitor m, String prevHost, Integer prevPort) {
        if (Boolean.TRUE.equals(m.getStandalone())) return false;
        // equalsIgnoreCase: "Example.com" → "example.com" AYNI hedeftir. Duyarlı karşılaştırma
        // yalnız harf kasası değişen bir düzenlemede envanter bağını koparıp mükerrer satır
        // doğuruyordu — düzeltmenin engellemeyi amaçladığı sınıfın ta kendisi. updateDns'in
        // guard'ı (:1608) aynı soruyu zaten equalsIgnoreCase ile soruyor.
        if (eqHost(prevHost, m.getHost()) && Objects.equals(prevPort, m.getPort())) return false;
        m.setStandalone(true);
        return true;
    }

    /** {@link #detachIfIdentityChanged(PortMonitor, String, Integer)} DNS ikizi — kimlik = domain. */
    private static boolean detachIfIdentityChanged(DnsMonitor m, String prevDomain) {
        if (Boolean.TRUE.equals(m.getStandalone())) return false;
        if (eqHost(prevDomain, m.getDomain())) return false;
        m.setStandalone(true);
        return true;
    }

    private void applyPortFeatureFields(PortMonitor m, Map<String, Object> body) {
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
        if (body.get("notifyEmail")         instanceof Boolean b) m.setNotifyEmail(b);
        if (body.get("notifyWebhook")          instanceof Boolean b) m.setNotifyWebhook(b);
        if (body.get("slowResponseEnabled") instanceof Boolean b) m.setSlowResponseEnabled(b);
        if (body.get("slowThresholdMs")     instanceof Number n)  m.setSlowThresholdMs(Math.max(1, n.intValue()));
        if (body.get("ipVersion") != null) {
            String v = body.get("ipVersion").toString().trim();
            m.setIpVersion(java.util.Set.of("v4", "v6", "auto").contains(v) ? v : "auto");
        }
    }

    // ── DNS Monitors ──────────────────────────────────────────────────────────

    @GetMapping("/dns")
    public ResponseEntity<Map<String, Object>> listDns(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        String now = ISO.format(Instant.now());

        // Tüm monitörleri tek sorguda yükle, domain ile indeksle (en küçük id = findFirst...OrderByIdAsc).
        Map<String, DnsMonitor> monitorByDomain = new HashMap<>();
        for (DnsMonitor m : dnsMonitorRepo.findAll()) {
            if (Boolean.TRUE.equals(m.getStandalone())) continue;   // standalone'lar envantere bağlı değil — ayrı işlenir
            monitorByDomain.merge(m.getDomain(), m, (a, b) -> a.getId() <= b.getId() ? a : b);
        }

        // Eksik domainler için monitörleri tek batch insert'te lazy-provision et (eski: döngüde tek tek save).
        List<DnsMonitor> toCreate = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            if (!monitorByDomain.containsKey(inv.getDomain())) {
                DnsMonitor m = new DnsMonitor();
                m.setName(inv.getDomain());
                m.setDomain(inv.getDomain());
                m.setTeamId(inv.getTeamId());   // envanter-türevi: takım envanter domain'inden (takım zorunlu)
                m.setRecordType("A");
                m.setActive(true);
                m.setIntervalSeconds(300);
                m.setCreatedAt(now);
                m.setUpdatedAt(now);
                monitorByDomain.put(inv.getDomain(), m);
                toCreate.add(m);
            }
        }
        if (!toCreate.isEmpty()) {
            try {
                dnsMonitorRepo.saveAll(toCreate);
            } catch (org.springframework.dao.DataIntegrityViolationException race) {
                log.debug("DNS monitör lazy-provision yarışı — yeniden okunuyor: {}", race.getMessage());
                reloadDnsMonitors(monitorByDomain);
            }
        }

        // Her monitör için en güncel kayıt — tek toplu sorgu (eski: monitör başına findTop... → N+1).
        Map<Long, DnsRecord> latestByMonitor = dnsRecordRepo.findLatestPerMonitor().stream()
                .filter(r -> r.getMonitorId() != null)
                .collect(Collectors.toMap(DnsRecord::getMonitorId, r -> r, (a, b) -> a));

        Map<String, String> teamMap = certificateService.domainTeamNameMap();
        Map<Long, String> teamById = teamNameMap();
        List<DnsMonitor> standaloneMonitors = dnsMonitorRepo.findByStandaloneTrueAndActiveTrue();
        // Açık DNS alarmlarını tek sorguda çek → satırlarda aktif-alarm rozeti (envanter + standalone domainleri).
        // IDOR: yalnız görüş kapsamındaki envanter satırları listelenir (bkz. inventoryViewable).
        List<CertificateInventory> visibleInventory = inventory.stream()
                .filter(inv -> inventoryViewable(session, inv)).toList();
        Set<String> alarmDomains = new HashSet<>();
        for (CertificateInventory inv : visibleInventory) alarmDomains.add(inv.getDomain());
        for (DnsMonitor m : standaloneMonitors) if (m.getDomain() != null) alarmDomains.add(m.getDomain());
        Map<String, AlertEvent> dnsAlarms = openDnsAlarmsByDomain(alarmDomains);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : visibleInventory) {
            DnsMonitor monitor = monitorByDomain.get(inv.getDomain());
            if (monitor == null) continue;   // yarış sonrası eksikse satırı atla (bkz. port yolu)
            DnsRecord latest = monitor.getId() != null ? latestByMonitor.get(monitor.getId()) : null;
            result.add(enrichDns(monitor, latest, teamMap, teamById, dnsAlarms.get(inv.getDomain())));
        }
        // Standalone (sertifikadan bağımsız) monitörler — envanter döngüsünde yok; takım görüş kapsamına göre ekle.
        for (DnsMonitor m : standaloneMonitors) {
            if (!SessionScope.canView(session, m.getTeamId())) continue;
            DnsRecord latest = m.getId() != null ? latestByMonitor.get(m.getId()) : null;
            result.add(enrichDns(m, latest, teamMap, teamById, dnsAlarms.get(m.getDomain())));
        }
        return ok(result);
    }

    /** DNS sayfasından STANDALONE (sertifikadan bağımsız) monitör oluşturur. monitoring.crud yetkili
     *  kullanıcı ekler; monitör + alarm resolveWriteTeam ile kullanıcının takımına atanır. */
    @PostMapping("/dns")
    public ResponseEntity<Map<String, Object>> createDns(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, true); if (_gt != null) return _gt; }   // grup + etiket zorunlu (2026-09-18)
        if (blank(body.get("domain")))     return badRequest("domain zorunludur");
        if (blank(body.get("recordType"))) return badRequest("recordType zorunludur");
        String domain = body.get("domain").toString().trim().toLowerCase(java.util.Locale.ROOT);   // envanterle aynı anahtar
        String recordType = body.get("recordType").toString().trim().toUpperCase();
        if (!DNS_RECORD_TYPES.contains(recordType)) return badRequest("Geçersiz DNS kayıt tipi: " + recordType);
        // Aynı (domain, recordType) standalone monitör zaten varsa hata dön; sessizce mevcut kaydı
        // dönmek "kaydedildi" izlenimi verip Kopyala akışını fark edilmeden boşa düşürüyordu (Ping/Port ile aynı davranış).
        // PASİF satır engel DEĞİLDİR, CANLANDIRILIR. Silme artık her zaman pasifleştirme olduğu
        // için (bkz. deleteDns) satır tabloda kalıyor; guard "aktif mi" diye bakmasaydı kullanıcı
        // sildiği domain'i bir daha ekleyemez ve sebebini anlamadığı bir "zaten var" hatası alırdı.
        //
        // Neden yeni satır değil de canlandırma: standalone satırlarda uq_dnsm_domain YOK (o indeks
        // "WHERE standalone IS NOT TRUE"), yani ikinci bir satır DB'ce engellenmez ve o andan sonra
        // findFirst... hangi satırı döndüreceği belirsiz hâle gelir — düzenleme guard'ı yanlış
        // satırı yakalayabilirdi. Canlandırma tek satırı korur, geçmişi ve id'yi de saklar.
        DnsMonitor revived = dnsMonitorRepo.findFirstByDomainAndRecordTypeAndStandaloneTrue(domain, recordType)
                .orElse(null);
        if (revived != null && Boolean.TRUE.equals(revived.getActive()))
            return badRequest("Bu (domain, kayıt tipi) için zaten bir izleme var; mükerrer DNS monitörü oluşturulamaz.");
        String now = ISO.format(Instant.now());
        DnsMonitor m = revived != null ? revived : new DnsMonitor();
        m.setName(blank(body.get("name")) ? domain : body.get("name").toString().trim());
        m.setDomain(domain);
        m.setRecordType(recordType);
        m.setActive(true);                                            // varsayılan: yeni izleme aktif
        if (body.get("active") instanceof Boolean b) m.setActive(b);  // Kopyala: pasif kaynağın kopyası da pasif doğsun
        m.setStandalone(true);                          // sertifikadan bağımsız → envanter-skip'i baypas eder
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null)
            return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        m.setTeamId(teamId);   // alarm yönlendirme + liste kapsamı için takım
        Object ev = body.get("expectedValue");          // beklenen-değer kilidi (opsiyonel)
        m.setExpectedValue(ev != null && !ev.toString().isBlank() ? ev.toString().trim() : null);
        m.setPropagationCheck(Boolean.TRUE.equals(body.get("propagationCheck")));   // çoklu-resolver tutarlılık (opt-in)
        m.setSlowThresholdMs(clampSlow(body.get("slowThresholdMs")));               // per-monitor yavaş eşiği (boş=global)
        if (body.get("dnsChangeAlertEnabled") != null)                              // DNS_CHANGED aç/kapa (null=açık)
            m.setDnsChangeAlertEnabled(Boolean.TRUE.equals(body.get("dnsChangeAlertEnabled")));
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));   // mantıksal grup (serbest-form)
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        // Kanal bayraklari burada HIC okunmuyordu: "Kopyala" akisinda e-posta/webhook KAPALI bir
        // izlemenin kopyasi ACIK doguyordu — kullanicinin bilincli tercihi sessizce kayboluyordu.
        if (body.get("notifyEmail")   instanceof Boolean nb) m.setNotifyEmail(nb);
        if (body.get("notifyWebhook") instanceof Boolean wb) m.setNotifyWebhook(wb);
        // KIRPMA sunucuda: diger yedi tur clampAttempts/clampInterval/clampRecovery kullaniyor,
        // DNS ve Domain'de sinir yalnizca FORMDA vardi. API'ye dogrudan
        // confirmIntervalSeconds=2000000000 gonderilirse teyit zinciri pratikte sonsuza ertelenir
        // ve o izlemenin kesinti alarmi HIC acilmaz — hicbir hata satiri da dusmez.
        if (body.get("confirmAttempts")         instanceof Number cn) m.setConfirmAttempts(clampAttempts(cn.intValue()));
        if (body.get("confirmIntervalSeconds")  instanceof Number cn) m.setConfirmIntervalSeconds(clampInterval(cn.intValue()));
        if (body.get("recoveryChecks")          instanceof Number cn) m.setRecoveryChecks(clampRecovery(cn.intValue()));
        if (body.get("recoveryIntervalSeconds") instanceof Number cn) m.setRecoveryIntervalSeconds(clampInterval(cn.intValue()));
        // Canlandırılan satırda özgün oluşturma tarihi KORUNUR (yalnız yeni satırda yazılır).
        if (m.getCreatedAt() == null) m.setCreatedAt(now);
        m.setUpdatedAt(now);
        DnsMonitor saved = dnsMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.DNS, saved.getId(), saved.getName(),
                saved.getDomain() + " " + saved.getRecordType(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "DNS_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        // İLK DEĞERLER: denetim create'te changes=null geçiyor (güvenlik kaydı "ne oldu"yu yazar);
        // ürün geçmişi "hangi değerlerle doğdu" sorusunu cevaplamak zorunda.
        monitorHistory.record(MonitorHistoryService.DNS, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
        return ok(enrichDns(saved, null, certificateService.domainTeamNameMap(), teamNameMap(), openDnsAlarm(saved.getDomain())));
    }

    @PutMapping("/dns/{id}")
    public ResponseEntity<Map<String, Object>> updateDns(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, false); if (_gt != null) return _gt; }   // gönderilip boş bırakılmışsa 400 (2026-09-18)
        java.util.Map<String, Object> _before = dnsMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return dnsMonitorRepo.findById(id).map(m -> {
            // Kapı, sekiz kardeş türle AYNI: izlemenin takımı üzerinde yetki (bkz. updatePort).
            //
            // Eskiden envanter-türevi satır requireAdmin istiyordu ve DNS bunu yapan TEK türdü.
            // Koruyucu bir değeri yoktu: envanter-türevi DNS kaydı takımını ENVANTERDEN alıyor
            // (lazy-provision: setTeamId(inv.getTeamId())), yani aynı takım yöneticisi zaten aynı
            // domainin Port izlemesini ve envanter kaydının KENDİSİNİ yönetebiliyordu. Tek ürettiği
            // sonuç, arayüzde açıklamasız bir çıkmazdı: kart üzerindeki bütün düğmeler kayboluyor,
            // kullanıcı bunu yetki kuralı değil ARIZA sanıyordu.
            if (!canOperateTeam(session, effectiveTeam(m.getDomain(), m.getStandalone(), m.getTeamId())))
                return forbidden("Bu monitörü düzenleme yetkiniz yok");
            final String _prevDomain = m.getDomain();
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("domain") != null) {
                String newDomain = ((String) body.get("domain")).trim().toLowerCase(java.util.Locale.ROOT);
                if (m.getDomain() != null && !m.getDomain().equalsIgnoreCase(newDomain)) {
                    String finalType = body.get("recordType") != null
                            ? ((String) body.get("recordType")).trim().toUpperCase() : m.getRecordType();
                    // (domain, kayıt tipi) mükerrer guard — yalnız standalone (envanter-türevinde domain envanterle bağlı)
                    if (Boolean.TRUE.equals(m.getStandalone())
                            && dnsMonitorRepo.findFirstByDomainAndRecordTypeAndStandaloneTrue(newDomain, finalType)
                                 .filter(x -> !x.getId().equals(id)).isPresent())
                        return badRequest("Bu (domain, kayıt tipi) için zaten bir monitör var");
                    // Domain DEĞİŞTİ → eski domain'in açık DNS alarmlarını sessizce kapat: aksi halde recovery yeni
                    // domain'e döner, eski-domain alarmı öksüz kalır ve asla resolve edilmez (takılı alarm).
                    escalationService.resolveOpenAlertsSilently(m.getDomain(), DNS_ALERT_TYPES, "Sistem (domain değişti)");
                }
                m.setDomain(newDomain);
            }
            if (body.get("recordType")      != null) m.setRecordType(((String) body.get("recordType")).toUpperCase());
            closeAlertsOnPause(m.getActive(), body.get("active"), m.getDomain(), DNS_ALERT_TYPES);
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("notifyEmail")   instanceof Boolean b) m.setNotifyEmail(b);
            if (body.get("notifyWebhook")   instanceof Boolean b) m.setNotifyWebhook(b);
            // KIRPMA: createDns kirpiyor, updateDns kirpmiyordu — ayni sinifin son halkasi.
            if (body.get("confirmAttempts")         instanceof Number cn) m.setConfirmAttempts(clampAttempts(cn.intValue()));
            if (body.get("confirmIntervalSeconds")  instanceof Number cn) m.setConfirmIntervalSeconds(clampInterval(cn.intValue()));
            if (body.get("recoveryChecks")          instanceof Number cn) m.setRecoveryChecks(clampRecovery(cn.intValue()));
            if (body.get("recoveryIntervalSeconds") instanceof Number cn) m.setRecoveryIntervalSeconds(clampInterval(cn.intValue()));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (Boolean.TRUE.equals(m.getStandalone()) && body.containsKey("teamId"))
                m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.containsKey("expectedValue")) {   // beklenen-değer kilidi (boş = kilit kapalı)
                Object ev = body.get("expectedValue");
                m.setExpectedValue(ev != null && !ev.toString().isBlank() ? ev.toString().trim() : null);
            }
            if (body.containsKey("propagationCheck"))   // çoklu-resolver tutarlılık (opt-in)
                m.setPropagationCheck(Boolean.TRUE.equals(body.get("propagationCheck")));
            if (body.containsKey("slowThresholdMs"))    // per-monitor yavaş eşiği (boş=global)
                m.setSlowThresholdMs(clampSlow(body.get("slowThresholdMs")));
            if (body.containsKey("dnsChangeAlertEnabled"))   // DNS_CHANGED aç/kapa (null=açık)
                m.setDnsChangeAlertEnabled(Boolean.TRUE.equals(body.get("dnsChangeAlertEnabled")));
            if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
            m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
            boolean detached = detachIfIdentityChanged(m, _prevDomain);
            m.setUpdatedAt(ISO.format(Instant.now()));
            monitorHistory.stampUpdated(m, session);
            DnsMonitor saved = dnsMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "DNS_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            // Aynı before/after çifti geçmişe de gider — audit çağrısına DOKUNULMAZ.
            var changeRow = monitorHistory.record(MonitorHistoryService.DNS, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
            noteConfigChanged(changeRow, ActivityLogService.DNS, saved.getDomain() + " " + saved.getRecordType(), session);
            Map<String, Object> item = enrichDns(saved, dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null),
                    certificateService.domainTeamNameMap(), teamNameMap(), openDnsAlarm(saved.getDomain()));
            item.put("detached_from_inventory", detached);
            return ok(item);
        }).orElse(notFound("DNS monitor not found"));
    }

    @DeleteMapping("/dns/{id}")
    public ResponseEntity<Map<String, Object>> deleteDns(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        // Silme ÖNCESİ durum: aşağıda active=false yapılıyor, sonra almak farkı kaybettirirdi.
        Map<String, Object> _before = dnsMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return dnsMonitorRepo.findById(id).map(m -> {
            // KAPI: silme, kardeşlerin YEDİSİNDE olduğu gibi canManage — yani TEAM_ADMIN ve üstü.
            // dba22f1a bunu canOperateTeam'e çekmişti; o, sıradan USER'ı da kendi takımı için
            // geçirir ve silme sütununu 9 türün 7'sinden ayırırdı. USER kaybetmiyor: kendi satırını
            // düzenleyebiliyor ve formdaki "aktif" anahtarıyla duraklatabiliyor.
            Long team = effectiveTeam(m.getDomain(), m.getStandalone(), m.getTeamId());
            if (!SessionScope.canManage(session, team)) return forbidden("Bu monitörü silme yetkiniz yok");
            // HER ZAMAN pasifleştirme (deletePort ile simetri). Eskiden dal standalone'a bakıyordu
            // ve KALICI silme oradan geliyordu; ama standalone, isteği yapanın AYNI AKIŞTA
            // çevirebildiği bir alan: updateDns'teki detachIfIdentityChanged bir türev satırın
            // domain'i değişince onu standalone yapıyor. İki çağrı (önce domain düzenle, sonra sil)
            // geri alınabilir bir duraklatmayı kalıcı silmeye yükseltiyordu — üstelik kart düğmesi
            // türev satırda "İzlemeyi durdur (envanter-türevi kayıt silinmez)" vaadini veriyordu.
            // Kalıcılığı artık kullanıcının çevirebildiği bir alan belirlemiyor.
            m.setActive(false);
            m.setUpdatedAt(ISO.format(Instant.now()));
            dnsMonitorRepo.save(m);
            activityLog.recordLifecycle(ActivityLogService.DNS, m.getId(), m.getName(),
                    m.getDomain() + " " + m.getRecordType(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "DNS_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            monitorHistory.record(MonitorHistoryService.DNS, m.getId(), m.getName(), m.getTeamId(),
                    MonitorHistoryService.DELETE, _before, AuditDiff.snapshot(m, MON_FIELDS), null, session);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("DNS monitor not found"));
    }

    @GetMapping("/dns/{id}/history")
    public ResponseEntity<?> dnsHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "false") boolean changedOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        DnsMonitor mon = dnsMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("DNS monitor not found");
        String effStatus = changedOnly ? "changed" : status;   // eski changedOnly paramı geriye-uyum sugar'ı
        var src = new CheckHistoryService.Source<DnsRecord>() {
            public org.springframework.data.domain.Page<DnsRecord> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? dnsRecordRepo.findChangedByMonitorIdBetween(id, f, t, p)
                            : dnsRecordRepo.findByMonitorIdAndCheckedAtBetween(id, f, t, p);
            }
            public long total(String f, String t) { return dnsRecordRepo.countByMonitorIdAndCheckedAtBetween(id, f, t); }
            public long fail(String f, String t) { return dnsRecordRepo.countChangedByMonitorIdBetween(id, f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return dnsRecordRepo.historyHistogram(id, f, t, len); }
            public List<Object[]> bounds() { return dnsRecordRepo.historyBounds(id); }
        };
        return runHistory(session, effectiveTeam(mon.getDomain(), mon.getStandalone(), mon.getTeamId()), src, "dns",
                mon.getDomain(), Set.of(EscalationService.TYPE_DNS_FAILURE, EscalationService.TYPE_DNS_CHANGED,
                        EscalationService.TYPE_DNS_SLOW, EscalationService.TYPE_DNS_UNEXPECTED,
                        EscalationService.TYPE_DNS_INCONSISTENT),
                from, to, days, effStatus, page, size, format, "dns-history-" + id, List.of(
                new CsvColumn<>("checked_at", DnsRecord::getCheckedAt),
                new CsvColumn<>("record_type", DnsRecord::getRecordType),
                new CsvColumn<>("value", DnsRecord::getValue),
                new CsvColumn<>("changed", DnsRecord::getChanged),
                new CsvColumn<>("rotated", DnsRecord::getRotated),
                new CsvColumn<>("ttl", DnsRecord::getTtl),
                new CsvColumn<>("response_ms", DnsRecord::getResponseMs)), response);
    }

    @PostMapping("/dns/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerDns(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return dnsMonitorRepo.findById(id).map(m -> {
            // Kapı, sekiz kardeş türle AYNI: izlemenin takımı üzerinde yetki. Burada eskiden
            // requireAdmin vardı ve DNS bu konuda dokuz türün TEK istisnasıydı; hiçbir yerde
            // gerekçesi yazılı değildi. "Şimdi kontrol et" salt-okunur bir işlemdir (DNS sorgusu
            // atıp sonucu kaydeder) ve aynı kullanıcı aynı domainin Port izlemesini zaten
            // tetikleyebiliyordu. Zararı da ölçülmüştü: takım yöneticisi toplu kontrole bastığında
            // her satır 403 dönüyor ve GlobalExceptionHandler her biri için ACCESS_DENIED denetim
            // kaydı yazıyordu — 40 monitörlük sayfada tek tıklama 40 sahte güvenlik olayı.
            if (!canOperateTeam(session, effectiveTeam(m.getDomain(), m.getStandalone(), m.getTeamId())))
                return forbidden("Bu izleme üzerinde yetkiniz yok");
            Map<String, Object> r = dnsChecker.check(m.getDomain(), m.getRecordType());
            String now = ISO.format(Instant.now());

            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) r.getOrDefault("values", List.of());
            String valueStr = String.join("\n", values);

            // Degisiklik tespiti SADECE basarili sorguda ve son BASARILI kayda karsi yapilir.
            //
            // ONCEDEN: son kayit (findTopByMonitorId...) alINIyordu -- basarisiz kayitlar DAHIL.
            // Cozumleme bir kez basarisiz olunca o satirin degeri "" olarak yaziliyor; bir sonraki
            // BASARILI elle kontrol bos kumeyle karsilastirilinca kumeler AYRIK oluyor ve satir
            // "DEGISTI!" damgasini yiyordu -- oysa IP hic degismemisti, arada sadece bir cozumleme
            // hatasi vardi. Tersi de olurdu: dolu -> bos(hata) gecisi de sahte CHANGED uretirdi,
            // yani her gecici DNS hatasi IKI sahte degisiklik doguruyordu.
            //
            // SchedulerService bu duzeltmeyi zaten tasiyordu (bkz. ayni gerekce oradaki yorumda);
            // elle tetikleme yolu ikizi olmasina ragmen guncellenmemisti. Iki yol artik birebir.
            boolean dnsOk = Boolean.TRUE.equals(r.get("success"));
            DnsRecord prev = dnsOk
                    ? dnsRecordRepo.findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(m.getId(), "").orElse(null)
                    : null;
            String prevValue = prev != null ? prev.getValue() : null;
            DnsCheckerService.ChangeKind kind = dnsOk
                    ? DnsCheckerService.detectChange(prevValue, valueStr)
                    : DnsCheckerService.ChangeKind.NONE;

            DnsRecord record = new DnsRecord();
            record.setMonitorId(m.getId());
            record.setRecordType(m.getRecordType());
            record.setValue(valueStr);
            record.setChanged(kind == DnsCheckerService.ChangeKind.CHANGED);
            record.setRotated(kind == DnsCheckerService.ChangeKind.ROTATED);
            record.setPreviousValue(prevValue);
            record.setCheckedAt(now);
            record.setTtl(r.get("ttl") instanceof Number n ? n.longValue() : null);
            record.setResponseMs(r.get("response_ms") instanceof Number rn ? rn.longValue() : null);
            dnsRecordRepo.save(record);
            auditService.recordAction("MONITOR_TRIGGER", session, "DNS_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            // ...ve ARDINDAN zamanlayiciyla AYNI degerlendirme hatti ASENKRON baslar
            // (birincil cozumleme-hatasi alarmi: dogrulama denemeleri + kurtarma sayaci).
            schedulerService.evaluateDnsNow(m, r);   // AYNI sonuç — ikinci çözümleme YOK
            return ok(enrichDns(m, record, certificateService.domainTeamNameMap(), teamNameMap(), openDnsAlarm(m.getDomain())));
        }).orElse(notFound("DNS monitor not found"));
    }

    /** Domain için tüm temel kayıt tipleri + SOA + authoritative NS — detail modal'da kullanılır. */
    @GetMapping("/dns/{id}/details")
    public ResponseEntity<Map<String, Object>> dnsDetails(@PathVariable Long id, HttpSession session) {
        // IDOR: bu uc oturum parametresi bile tasimadan findById donuyordu — id artirarak baska
        // takimin DNS yapilandirmasi + her cagride CANLI DNS sorgusu + resolver yapilandirmasi
        // okunabiliyordu. Kardes uclar (/history, /response-series, /check) hep korumaliydi.
        permissionService.require(session, "monitoring.read", "view");
        return dnsMonitorRepo.findById(id).map(m -> {
            var deny = denyIfNotViewable(session, effectiveTeam(m.getDomain(), m.getStandalone(), m.getTeamId()));   // IDOR (H3)
            if (deny != null) return deny;
            Map<String, Object> data = new LinkedHashMap<>(dnsChecker.enrichedQuery(m.getDomain()));
            data.put("monitor", enrichDns(m, dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(m.getId()).orElse(null), certificateService.domainTeamNameMap(), teamNameMap(), openDnsAlarm(m.getDomain())));
            data.put("slow_threshold_ms", m.getSlowThresholdMs() != null ? m.getSlowThresholdMs()
                    : appSettings.getInt("site.monitor.dns.slow-threshold-ms", 1500));   // per-monitor ?? global — grafik eşik çizgisi
            // Çözümleyici şeffaflığı: sorguların hangi DNS sunucularına gittiği (OS zinciri, sıralı) +
            // propagation modunda ayrıca sorgulanan public resolver listesi. Per-yanıt atıf mümkün değil
            // (ExtendedResolver) — yapılandırma raporlanır.
            Map<String, Object> resolverCfg = new LinkedHashMap<>(dnsChecker.resolverConfigInfo());
            resolverCfg.put("propagation_enabled", Boolean.TRUE.equals(m.getPropagationCheck()));
            resolverCfg.put("propagation_resolvers", java.util.Arrays.stream(
                            appSettings.getString("site.monitor.dns.resolvers", "8.8.8.8,1.1.1.1,9.9.9.9").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
            data.put("resolver_config", resolverCfg);
            return ResponseEntity.ok(Map.of("success", true, "data", data));
        }).orElse(notFound("DNS monitor not found"));
    }

    private Map<String, Object> enrichDns(DnsMonitor m, DnsRecord latest,
                                          Map<String, String> teamMap, Map<Long, String> teamById,
                                          AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        boolean standalone = Boolean.TRUE.equals(m.getStandalone());
        item.put("id",              m.getId());
        item.put("name",            m.getName());
        item.put("domain",          m.getDomain());
        item.put("standalone",      standalone);
        // team_id ile team_name AYNI kaynaktan gelir (effectiveTeam): türev satırda ikisi de
        // envanterin takımı. Eskiden ad tazeydi ama kimlik bayattı; frontend isOwnTeam
        // karşılaştırması bu yüzden transferden sonra yanlış sonuç veriyordu.
        item.put("team_id",         effectiveTeam(m.getDomain(), m.getStandalone(), m.getTeamId()));
        item.put("notification_group_id",         m.getNotificationGroupId());
        item.put("expected_value",  m.getExpectedValue());
        item.put("propagation_check", Boolean.TRUE.equals(m.getPropagationCheck()));
        item.put("dns_change_alert_enabled", !Boolean.FALSE.equals(m.getDnsChangeAlertEnabled()));   // etkin değer (null=açık)
        item.put("group_name",      m.getGroupName());
        item.put("tags",            m.getTags());
        item.put("alert_level",     com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
        // Standalone monitör takımını teamId'den çöz (envantere bağlı değil); envanter-türevi domain→envanter eşlemesinden.
        item.put("team_name",       standalone && m.getTeamId() != null
                ? teamById.get(m.getTeamId()) : teamMap.get(m.getDomain()));
        item.put("record_type",     m.getRecordType());
        item.put("active",          m.getActive());
        item.put("notify_email",   m.getNotifyEmail());
        item.put("notify_webhook", m.getNotifyWebhook());
        item.put("confirm_attempts",          m.getConfirmAttempts());
        item.put("confirm_interval_seconds",  m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("interval_seconds",m.getIntervalSeconds());
        item.put("created_at",      m.getCreatedAt());
        item.put("updated_at",      m.getUpdatedAt());
        item.put("slow_threshold_ms",  m.getSlowThresholdMs());     // null = global eşik
        item.put("active_alarm",       openAlarm != null);
        item.put("alarm_level",        openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
        if (latest != null) {
            item.put("value",        latest.getValue());
            item.put("changed",      latest.getChanged());
            item.put("rotated",      Boolean.TRUE.equals(latest.getRotated()));
            item.put("checked_at",   latest.getCheckedAt());
            item.put("ttl",          latest.getTtl());
            item.put("response_ms",  latest.getResponseMs());
        } else {
            item.put("value",        null);
            item.put("changed",      false);
            item.put("rotated",      false);
            item.put("checked_at",   null);
            item.put("ttl",          null);
            item.put("response_ms",  null);
        }
        return item;
    }

    // ── Keyword Monitors (serbest-form) ───────────────────────────────────────

    @GetMapping("/keyword")
    public ResponseEntity<Map<String, Object>> listKeyword(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, KeywordResult> latest = keywordResultRepo.findLatestPerMonitor().stream()
                .filter(r -> r.getMonitorId() != null)
                .collect(Collectors.toMap(KeywordResult::getMonitorId, r -> r, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        // IDOR (H2): yalnız görüntülenebilir takımların monitörleri (global admin → hepsi).
        List<KeywordMonitor> monitors = keywordMonitorRepo.findAllByOrderByNameAsc().stream()
                .filter(m -> SessionScope.canView(session, m.getTeamId())).toList();
        Map<String, AlertEvent> alarms = openAlarmsByDomain(
                monitors.stream().map(KeywordMonitor::getUrl).collect(Collectors.toSet()),
                EscalationService.TYPE_KEYWORD);
        List<Map<String, Object>> result = monitors.stream()
                .map(m -> enrichKeyword(m, latest.get(m.getId()), teams, alarms.get(m.getUrl()))).toList();
        return ok(result);
    }

    @PostMapping("/keyword")
    public ResponseEntity<Map<String, Object>> createKeyword(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, true); if (_gt != null) return _gt; }   // grup + etiket zorunlu (2026-09-18)
        if (blank(body.get("url")) || blank(body.get("keyword"))) return badRequest("url ve keyword zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        // Mükerrer koruması (diğer türlerle aynı desen): aynılık anahtarı url + keyword + takım —
        // aynı URL'i FARKLI kelimeyle izlemek meşrudur, engellenmez.
        // Şemasız URL kontrol edilemez (host çıkarılamaz) → mükerrer kontrolünden ÖNCE normalize et,
        // aksi halde "x.com" ile mevcut "https://x.com" eşleşmez ve kayıttan sonra ikisi aynı URL olur.
        String kwUrl = MonitorUrls.normalize(body.get("url").toString());
        if (!MonitorUrls.isCheckable(kwUrl)) return badRequest(INVALID_URL_MSG);
        String kwWord = body.get("keyword").toString().trim();
        if (keywordMonitorRepo.existsDuplicate(kwUrl, kwWord, teamId, null))
            return badRequest("Bu URL ve anahtar kelime bu takımda zaten izleniyor; mükerrer keyword monitörü oluşturulamaz.");
        String now = ISO.format(Instant.now());
        KeywordMonitor m = new KeywordMonitor();
        m.setName((String) body.get("name"));
        m.setUrl(kwUrl);
        m.setKeyword((String) body.get("keyword"));
        if (body.get("customHeaders") != null) m.setCustomHeaders((String) body.get("customHeaders"));
        if (body.containsKey("useProxy")) m.setUseProxy(com.sitemonitor.service.ProxyPolicyService.normalizeMode(body.get("useProxy")));
        applyKeywordCondition(m, body);
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        m.setTeamId(teamId);
        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
        m.setActive(true);                                            // varsayılan: yeni izleme aktif
        if (body.get("active") instanceof Boolean b) m.setActive(b);  // Kopyala: pasif kaynağın kopyası da pasif doğsun
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
        if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
        if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
        if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
        applyKeywordFeatureFields(m, body);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        KeywordMonitor saved = keywordMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.KEYWORD, saved.getId(), saved.getName(),
                saved.getUrl(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "KEYWORD_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        // İLK DEĞERLER: denetim create'te changes=null geçiyor (güvenlik kaydı "ne oldu"yu yazar);
        // ürün geçmişi "hangi değerlerle doğdu" sorusunu cevaplamak zorunda.
        monitorHistory.record(MonitorHistoryService.KEYWORD, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
        return ok(enrichKeyword(saved, null, teamNameMap(), null));
    }

    @PutMapping("/keyword/{id}")
    public ResponseEntity<Map<String, Object>> updateKeyword(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, false); if (_gt != null) return _gt; }   // gönderilip boş bırakılmışsa 400 (2026-09-18)
        java.util.Map<String, Object> _before = keywordMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return keywordMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            // Edit ile mükerrere dönüşmeyi de engelle (ping PUT'undaki desen; excludeId = kendisi).
            String intendedUrl  = body.get("url")     != null ? MonitorUrls.normalize(body.get("url").toString()) : m.getUrl();
            String intendedWord = body.get("keyword") != null ? body.get("keyword").toString().trim() : m.getKeyword();
            Long intendedTeam   = body.containsKey("teamId")
                    ? resolveTeamChange(session, m.getTeamId(), body.get("teamId")) : m.getTeamId();
            if (body.get("url") != null && !MonitorUrls.isCheckable(intendedUrl)) return badRequest(INVALID_URL_MSG);
            if (intendedUrl != null && intendedWord != null
                    && keywordMonitorRepo.existsDuplicate(intendedUrl, intendedWord, intendedTeam, id))
                return badRequest("Bu URL ve anahtar kelime bu takımda zaten izleniyor; mükerrer keyword monitörü oluşturulamaz.");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("url")             != null) m.setUrl(intendedUrl);
            if (body.get("keyword")         != null) m.setKeyword((String) body.get("keyword"));
            if (body.containsKey("customHeaders"))    m.setCustomHeaders((String) body.get("customHeaders"));
            if (body.containsKey("useProxy"))         m.setUseProxy(com.sitemonitor.service.ProxyPolicyService.normalizeMode(body.get("useProxy")));
            if (body.get("operator") != null || body.get("matchCount") != null || body.get("condition") != null) applyKeywordCondition(m, body);
            if (body.containsKey("groupName"))       m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
            closeAlertsOnPause(m.getActive(), body.get("active"), m.getUrl(), Set.of(EscalationService.TYPE_KEYWORD, EscalationService.TYPE_KEYWORD_SLOW, EscalationService.TYPE_KEYWORD_SSL, EscalationService.TYPE_KEYWORD_DOMAIN_EXPIRY));
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            applyKeywordFeatureFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            monitorHistory.stampUpdated(m, session);
            KeywordMonitor saved = keywordMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "KEYWORD_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            // Aynı before/after çifti geçmişe de gider — audit çağrısına DOKUNULMAZ.
            var changeRow = monitorHistory.record(MonitorHistoryService.KEYWORD, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
            noteConfigChanged(changeRow, ActivityLogService.KEYWORD, saved.getUrl(), session);
            return ok(enrichKeyword(saved, keywordResultRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getUrl(), EscalationService.TYPE_KEYWORD).orElse(null)));
        }).orElse(notFound("Keyword monitor not found"));
    }

    @DeleteMapping("/keyword/{id}")
    public ResponseEntity<Map<String, Object>> deleteKeyword(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        // Silme ÖNCESİ durum: aşağıda active=false yapılıyor, sonra almak farkı kaybettirirdi.
        Map<String, Object> _before = keywordMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return keywordMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            // Silme kaynaklı kapanma: açık alarmı sessizce resolved'a geçir (çözüldü maili YOK).
            escalationService.resolveOpenAlertsSilently(m.getUrl(),
                    Set.of(EscalationService.TYPE_KEYWORD), "Sistem (izleme silindi)");
            keywordMonitorRepo.delete(m);   // hard delete — "Sil" listeden kaldırır ("Aktif" toggle ayrı)
            activityLog.recordLifecycle(ActivityLogService.KEYWORD, m.getId(), m.getName(),
                    m.getUrl(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "KEYWORD_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            monitorHistory.record(MonitorHistoryService.KEYWORD, m.getId(), m.getName(), m.getTeamId(),
                    MonitorHistoryService.DELETE, _before, AuditDiff.snapshot(m, MON_FIELDS), null, session);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Keyword monitor not found"));
    }

    @GetMapping("/keyword/{id}/history")
    public ResponseEntity<?> keywordHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        KeywordMonitor mon = keywordMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Keyword monitor not found");
        var src = new CheckHistoryService.Source<KeywordResult>() {
            public org.springframework.data.domain.Page<KeywordResult> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? keywordResultRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t, p)
                            : keywordResultRepo.findByMonitorIdAndCheckedAtBetween(id, f, t, p);
            }
            public long total(String f, String t) { return keywordResultRepo.countByMonitorIdAndCheckedAtBetween(id, f, t); }
            public long fail(String f, String t) { return keywordResultRepo.countByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return keywordResultRepo.historyHistogram(id, f, t, len); }
            public List<Object[]> bounds() { return keywordResultRepo.historyBounds(id); }
        };
        return runHistory(session, mon.getTeamId(), src, "keyword",
                mon.getUrl(), Set.of(EscalationService.TYPE_KEYWORD, EscalationService.TYPE_KEYWORD_SLOW,
                        EscalationService.TYPE_KEYWORD_SSL, EscalationService.TYPE_KEYWORD_DOMAIN_EXPIRY),
                from, to, days, status, page, size, format, "keyword-history-" + id, List.of(
                new CsvColumn<>("checked_at", KeywordResult::getCheckedAt),
                new CsvColumn<>("ok", KeywordResult::getOk),
                new CsvColumn<>("found", KeywordResult::getFound),
                new CsvColumn<>("occurrences", KeywordResult::getOccurrences),
                new CsvColumn<>("http_status", KeywordResult::getHttpStatus),
                new CsvColumn<>("response_ms", KeywordResult::getResponseMs),
                new CsvColumn<>("error", KeywordResult::getError)), response);
    }

    @PostMapping("/keyword/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerKeyword(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return keywordMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            Map<String, Object> r = keywordChecker.check(m.getUrl(), m.getKeyword(),
                    m.getTimeoutMs() != null ? m.getTimeoutMs() : 10000, m.getCustomHeaders(), Boolean.TRUE.equals(m.getCaseSensitive()),
                    viaProxy(m.getUrl(), m.getUseProxy()));
            boolean found = Boolean.TRUE.equals(r.getOrDefault("found", false));
            int count = r.get("count") instanceof Number cn ? cn.intValue() : (found ? 1 : 0);
            int threshold = m.getMatchCount() != null ? m.getMatchCount() : 1;
            boolean ok = r.get("error") == null && KeywordCheckerService.evaluate(count, m.getMatchOperator(), threshold);
            KeywordResult res = new KeywordResult();
            res.setMonitorId(m.getId());
            res.setFound(found);
            res.setOccurrences(count);
            res.setOk(ok);
            res.setHttpStatus(r.get("http_status") instanceof Number n ? n.intValue() : null);
            res.setResponseMs(r.get("response_ms") instanceof Number n ? n.longValue() : null);
            res.setSnippet((String) r.get("snippet"));
            res.setError((String) r.get("error"));
            res.setCheckedAt(ISO.format(Instant.now()));
            keywordResultRepo.save(res);
            auditService.recordAction("MONITOR_TRIGGER", session, "KEYWORD_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            // ...ve ARDINDAN zamanlayiciyla AYNI degerlendirme hatti ASENKRON baslar
            // (dogrulama denemeleri + kurtarma sayaci).
            schedulerService.evaluateKeywordNow(m, r);   // AYNI sonuç — ikinci kontrol/kayıt YOK
            return ok(enrichKeyword(m, res, teamNameMap(),
                    alertEventRepo.findOpenAlert(m.getUrl(), EscalationService.TYPE_KEYWORD).orElse(null)));
        }).orElse(notFound("Keyword monitor not found"));
    }

    /**
     * Canlı koşul testi — kaydetmeden, formdaki değerlerle (url/keyword/operator/matchCount)
     * URL'yi çekip koşulun sağlanıp sağlanmadığını döndürür. "Test" butonu kullanır.
     */
    @PostMapping("/keyword/test")
    public ResponseEntity<Map<String, Object>> testKeyword(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        String url = body.get("url") != null ? MonitorUrls.normalize(body.get("url").toString()) : "";
        String keyword = body.get("keyword") != null ? body.get("keyword").toString() : "";
        if (url.isEmpty() || keyword.isEmpty()) return badRequest("url ve keyword zorunlu");
        if (!MonitorUrls.isCheckable(url)) return badRequest(INVALID_URL_MSG);
        String op = body.get("operator") != null ? body.get("operator").toString() : "GTE";
        if (!KW_OPERATORS.contains(op)) op = "GTE";
        int threshold = body.get("matchCount") instanceof Number mn ? mn.intValue() : 1;
        int timeoutMs = body.get("timeoutMs") instanceof Number tn ? tn.intValue() : 10000;
        String customHeaders = body.get("customHeaders") != null ? body.get("customHeaders").toString() : null;
        boolean caseSensitive = Boolean.TRUE.equals(body.get("caseSensitive"));
        // Başlıkların İÇERİĞİ yazılmaz: Authorization taşıyabiliyor. Hedef URL'in query'si de düşer.
        auditService.recordAction("MONITOR_TEST", session, "KEYWORD_MONITOR", "test",
                AuditDetail.of("url", AuditDetail.safeTarget(url), "operator", op,
                        "match_count", threshold, "custom_headers", customHeaders != null), null);
        com.sitemonitor.service.ProxyPolicyService.Decision kpd = proxyDecision(url, body.get("useProxy"));
        Map<String, Object> r = keywordChecker.check(url, keyword, timeoutMs, customHeaders, caseSensitive, kpd.viaProxy());
        int count = r.get("count") instanceof Number cn ? cn.intValue() : 0;
        boolean met = r.get("error") == null && KeywordCheckerService.evaluate(count, op, threshold);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("occurrences",   count);
        out.put("condition_met", met);
        out.put("http_status",   r.get("http_status"));
        out.put("response_ms",   r.get("response_ms"));
        out.put("snippet",       r.get("snippet"));
        out.put("error",         r.get("error"));
        out.put("via",           r.getOrDefault("via", kpd.via()));
        out.put("proxy_source",  kpd.source());
        out.put("phrase",        KeywordCheckerService.opPhrase(op, threshold));
        return ok(out);
    }

    /**
     * Ad-hoc ping testi — kaydetmeden, formdaki host/parametrelerle bir kez ping atar; ping atılabildi mi (sent)
     * ve koşul (erişilebilirlik = up) sağlandı mı döndürür. "Test" butonu kullanır.
     */
    @PostMapping("/ping/test")
    public ResponseEntity<Map<String, Object>> testPing(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        String host = body.get("host") != null ? body.get("host").toString().trim() : "";
        if (host.isEmpty()) return badRequest("host zorunlu");
        String ipVersion = body.get("ipVersion") != null ? body.get("ipVersion").toString() : "auto";
        int count     = body.get("packetCount") instanceof Number cn ? cn.intValue() : 4;
        int timeoutMs = body.get("timeoutMs")   instanceof Number tn ? tn.intValue() : 5000;
        auditService.recordAction("MONITOR_TEST", session, "PING_MONITOR", "test",
                AuditDetail.of("host", host, "ip_version", ipVersion, "packet_count", count), null);
        Map<String, Object> r = pingChecker.check(host, ipVersion, Math.max(1, Math.min(count, 10)), timeoutMs);
        boolean na = Boolean.TRUE.equals(r.get("na"));
        boolean up = Boolean.TRUE.equals(r.get("up"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent",          !na);          // ping atılabildi mi (ICMP ortamı uygun mu)
        out.put("up",            up);
        out.put("condition_met", up);           // ping koşulu = host erişilebilir
        out.put("rtt_ms",        r.get("rtt_ms"));
        out.put("packet_loss",   r.get("packet_loss"));
        out.put("na",            na);
        out.put("error",         r.get("error"));
        return ok(out);
    }

    /**
     * Ad-hoc DNS testi — kaydetmeden, formdaki domain/recordType ile bir kez çözümler; değer/ttl/response_ms,
     * yavaş mı (eşik) ve beklenen-değere göre beklenmeyen değer var mı döndürür. "Test" butonu kullanır.
     * URL girilirse toHostname ile çıplak host ayıklanır (P0). KAYIT OLUŞTURMAZ.
     */
    @PostMapping("/dns/test")
    public ResponseEntity<Map<String, Object>> testDns(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        if (blank(body.get("domain"))) return badRequest("domain zorunlu");
        String domain = body.get("domain").toString().trim();
        String recordType = body.get("recordType") != null ? body.get("recordType").toString().trim().toUpperCase() : "A";
        if (!DNS_RECORD_TYPES.contains(recordType)) return badRequest("Geçersiz DNS kayıt tipi: " + recordType);
        auditService.recordAction("MONITOR_TEST", session, "DNS_MONITOR", "test",
                AuditDetail.of("domain", domain, "record_type", recordType), null);
        Map<String, Object> r = dnsChecker.check(domain, recordType);
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) r.getOrDefault("values", List.of());
        boolean success = Boolean.TRUE.equals(r.get("success"));
        long responseMs = r.get("response_ms") instanceof Number rn ? rn.longValue() : 0L;
        Integer slowThr = clampSlow(body.get("slowThresholdMs"));
        int effSlow = slowThr != null ? slowThr : appSettings.getInt("site.monitor.dns.slow-threshold-ms", 1500);
        Object ev = body.get("expectedValue");
        List<String> unexpected = success
                ? DnsCheckerService.unexpectedValues(ev != null ? ev.toString() : null, values) : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success",     success);
        out.put("host",        DnsCheckerService.toHostname(domain));   // çözümlenen çıplak host (URL girildiyse ayıklanmış)
        out.put("values",      values);
        out.put("ttl",         r.get("ttl"));
        out.put("response_ms", responseMs);
        out.put("slow",        success && responseMs > effSlow);
        out.put("unexpected",  unexpected);
        out.put("error",       r.get("error"));
        return ok(out);
    }

    // ── Yanıt-süresi / RTT grafiği (detay modalı "Süre Grafiği" sekmesi) ─────
    private static final int SERIES_RAW_CAP = 50_000;   // heap koruması (tek-pod): 200k→50k; grafik p95 için yeterli
    private static final DateTimeFormatter LDT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @GetMapping("/keyword/{id}/response-series")
    public ResponseEntity<Map<String, Object>> keywordResponseSeries(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        KeywordMonitor kmon = keywordMonitorRepo.findById(id).orElse(null);
        if (kmon == null) return notFound("Keyword monitor not found");
        var deny = denyIfNotViewable(session, kmon.getTeamId());   // IDOR (H3)
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        return ok(buildResponseSeries(keywordResultRepo.responseSeriesRaw(id, range[0], range[1], SERIES_RAW_CAP),
                range[0], range[1], false));
    }

    @GetMapping("/ping/{id}/response-series")
    public ResponseEntity<Map<String, Object>> pingResponseSeries(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        PingMonitor pmon = pingMonitorRepo.findById(id).orElse(null);
        if (pmon == null) return notFound("Ping monitor not found");
        var deny = denyIfNotViewable(session, pmon.getTeamId());   // IDOR (H3)
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        return ok(buildResponseSeries(pingCheckRepo.responseSeriesRaw(id, range[0], range[1], SERIES_RAW_CAP),
                range[0], range[1], true));
    }

    @GetMapping("/port/{id}/response-series")
    public ResponseEntity<Map<String, Object>> portResponseSeries(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        PortMonitor pomon = portMonitorRepo.findById(id).orElse(null);
        if (pomon == null) return notFound("Port monitor not found");
        // Yazma kardeşleri (PUT/check/DELETE) effectiveTeam kullanır: envanter-türevi satırın team_id'si
        // transferde tazelenmez → eski takım geçmişi okumaya devam ediyor, yeni takım 403 alıyordu.
        var deny = denyIfNotViewable(session, effectiveTeam(pomon.getHost(), pomon.getStandalone(), pomon.getTeamId()));   // IDOR (H3)
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        return ok(buildResponseSeries(portCheckRepo.responseSeriesRaw(id, range[0], range[1], SERIES_RAW_CAP),
                range[0], range[1], false));   // withLoss=false — port'ta paket kaybı yok
    }

    @GetMapping("/dns/{id}/response-series")
    public ResponseEntity<Map<String, Object>> dnsResponseSeries(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        DnsMonitor dmon = dnsMonitorRepo.findById(id).orElse(null);
        if (dmon == null) return notFound("DNS monitor not found");
        var deny = denyIfNotViewable(session, effectiveTeam(dmon.getDomain(), dmon.getStandalone(), dmon.getTeamId()));   // IDOR (H3)
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        return ok(buildResponseSeries(dnsRecordRepo.responseSeriesRaw(id, range[0], range[1], SERIES_RAW_CAP),
                range[0], range[1], false));   // withLoss=false — DNS'te paket kaybı yok
    }

    /** from/to verilmişse onları (to normalize), yoksa son `days` günü kullan. */
    private String[] resolveRange(String from, String to, int days) {
        String toIso   = (to != null && !to.isBlank())    ? normalizeTo(to) : ISO.format(Instant.now());
        String fromIso = (from != null && !from.isBlank()) ? from
                : ISO.format(Instant.now().minus(Math.max(1, Math.min(days, 365)), ChronoUnit.DAYS));
        return new String[]{ fromIso, toIso };
    }

    /** Retention clamp — {@code CheckHistoryService.resolve} içindeki kuralın aynısı: {@code from},
     *  saklama penceresinin gerisine inemez. Geçmiş sekmesi bunu zaten uyguluyor; seri ucu da uygulasın
     *  ki aynı özel aralıkta tablo dolu / grafik boş gibi bir tutarsızlık çıkmasın. */
    private static String clampToRetention(String from, int retentionDays) {
        String minFrom = ISO.format(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        return (from != null && from.compareTo(minFrom) < 0) ? minFrom : from;
    }

    /** Aralık genişliğine göre kova anahtarı uzunluğu: ≤48s → 10-dk(15), ≤31g → saat(13), üstü → gün(10). */
    private static int bucketKeyLen(String from, String to) {
        try {
            long hours = java.time.Duration.between(LocalDateTime.parse(from, LDT), LocalDateTime.parse(to, LDT)).toHours();
            // <= 6 saat: DAKİKA hassasiyeti — her kontrol kendi noktası olur. 10 dakikalık kovada
            // 10 dakikada bir koşan bir izlemenin ölçümleri ortalamaya karışıyor ve kullanıcı
            // "hangi anda ne oldu" sorusunu grafikten cevaplayamıyordu.
            if (hours <= 6) return 16;
            if (hours <= 48) return 15;
            if (hours <= 31 * 24) return 13;
            return 10;
        } catch (Exception e) { return 13; }
    }

    /** Kova anahtarını (kısaltılmış ISO) tam ISO timestamp'e açar (grafik x-ekseni). */
    private static String bucketIso(String key, int keyLen) {
        return switch (keyLen) {
            case 16 -> key + ":00";         // ...THH:mm → ...THH:mm:00
            case 15 -> key + "0:00";        // ...THH:m → ...THH:m0:00
            case 13 -> key + ":00:00";      // ...THH   → ...THH:00:00
            default -> key + "T00:00:00";   // yyyy-MM-dd → ...T00:00:00
        };
    }

    /** Ham [checkedAt, süre, durum(up/ok)[, paket kaybı]] satırlarını kovalar:
     *  her kovada avg/min/max/p95/count/down[+loss]. Null süreler istatistiğe katılmaz; down durumdan sayılır. */
    private Map<String, Object> buildResponseSeries(List<Object[]> rows, String from, String to, boolean withLoss) {
        return buildResponseSeries(rows, from, to, withLoss ? "loss" : null);
    }

    /** {@code auxKey}: 4. ham kolonun çıktı adı — ping'de "loss" (paket kaybı), sertifikada "days"
     *  (kalan gün). null ⇒ yardımcı seri yok. Boolean imza bunu "loss" ile çağırır (yedi çağıran aynı). */
    private Map<String, Object> buildResponseSeries(List<Object[]> rows, String from, String to, String auxKey) {
        boolean withLoss = auxKey != null;
        int keyLen = bucketKeyLen(from, to);
        Map<String, List<Long>> values = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> downs = new HashMap<>();
        Map<String, long[]> loss = withLoss ? new HashMap<>() : null;
        for (Object[] r : rows) {
            String ts = (String) r[0];
            if (ts == null || ts.length() < keyLen) continue;
            String key = ts.substring(0, keyLen);
            counts.merge(key, 1, Integer::sum);
            if (r[1] instanceof Number v) values.computeIfAbsent(key, k -> new ArrayList<>()).add(v.longValue());
            if (!Boolean.TRUE.equals(r[2])) downs.merge(key, 1, Integer::sum);
            if (withLoss && r.length > 3 && r[3] instanceof Number pl) {
                long[] a = loss.computeIfAbsent(key, k -> new long[2]);
                a[0] += pl.longValue(); a[1]++;
            }
        }
        List<Map<String, Object>> series = new ArrayList<>();
        long downTotal = 0;
        for (String key : new TreeSet<>(counts.keySet())) {
            List<Long> vs = values.get(key);
            int down = downs.getOrDefault(key, 0);
            downTotal += down;
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("ts",    bucketIso(key, keyLen));
            pt.put("count", counts.getOrDefault(key, 0));
            pt.put("down",  down);
            if (vs != null && !vs.isEmpty()) {
                List<Long> sorted = vs.stream().sorted().toList();
                pt.put("avg", Math.round(sorted.stream().mapToLong(Long::longValue).average().orElse(0)));
                pt.put("min", sorted.get(0));
                pt.put("max", sorted.get(sorted.size() - 1));
                pt.put("p95", sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1));
            } else {
                pt.put("avg", null); pt.put("min", null); pt.put("max", null); pt.put("p95", null);
            }
            if (withLoss) {
                long[] a = loss.get(key);
                pt.put(auxKey, a != null && a[1] > 0 ? Math.round((double) a[0] / a[1]) : null);
            }
            series.add(pt);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series",     series);
        out.put("bucket",     keyLen == 16 ? "minute" : keyLen == 15 ? "10m" : keyLen == 13 ? "hour" : "day");
        out.put("unit",       "ms");
        out.put("from",       from);
        out.put("to",         to);
        out.put("total",      rows.size());
        out.put("down_total", downTotal);
        out.put("capped",     rows.size() >= SERIES_RAW_CAP);
        return out;
    }

    private Map<String, Object> enrichKeyword(KeywordMonitor m, KeywordResult latest, Map<Long, String> teams, AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",               m.getId());
        item.put("name",             m.getName());
        item.put("url",              m.getUrl());
        item.put("keyword",          m.getKeyword());
        putProxyFields(item, m.getUrl(), m.getUseProxy());
        item.put("condition",        m.getAlertCondition());
        item.put("operator",         m.getMatchOperator());
        item.put("match_count",      m.getMatchCount());
        item.put("group_name",       m.getGroupName());
        item.put("team_id",          m.getTeamId());
        item.put("notification_group_id",          m.getNotificationGroupId());
        item.put("team_name",        m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",           m.getActive());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("timeout_ms",       m.getTimeoutMs());
        item.put("confirm_attempts",         m.getConfirmAttempts());
        item.put("confirm_interval_seconds", m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("custom_headers",            m.getCustomHeaders());
        item.put("case_sensitive",            m.getCaseSensitive());
        item.put("tags",                      m.getTags());
        item.put("alert_level",     com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
        item.put("notify_email",              m.getNotifyEmail());
        item.put("notify_webhook",              m.getNotifyWebhook());
        item.put("slow_response_enabled",     m.getSlowResponseEnabled());
        item.put("slow_threshold_ms",         m.getSlowThresholdMs());
        item.put("check_ssl_errors",          m.getCheckSslErrors());
        item.put("ssl_expiry_reminders",      m.getSslExpiryReminders());
        item.put("domain_expiry_reminders",   m.getDomainExpiryReminders());
        item.put("ssl_reminder_days",         m.getSslReminderDays());
        item.put("domain_reminder_days",      m.getDomainReminderDays());
        item.put("active_alarm",       openAlarm != null);
        item.put("alarm_level",        openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
        if (latest != null) {
            item.put("status",      latest.getError() != null ? "error" : (Boolean.TRUE.equals(latest.getOk()) ? "up" : "down"));
            item.put("found",       latest.getFound());
            item.put("occurrences", latest.getOccurrences());
            item.put("ok",          latest.getOk());
            item.put("http_status", latest.getHttpStatus());
            item.put("response_ms", latest.getResponseMs());
            item.put("snippet",     latest.getSnippet());
            item.put("error",       latest.getError());
            item.put("checked_at",  latest.getCheckedAt());
        } else {
            item.put("status", "unknown");
            item.put("found", null); item.put("occurrences", null); item.put("ok", null); item.put("http_status", null);
            item.put("response_ms", null); item.put("snippet", null); item.put("error", null); item.put("checked_at", null);
        }
        return item;
    }

    /** Ortak: keyword feature alanlarını (caseSensitive/tags/notify/slow/SSL-Domain toggle'ları + gün eşikleri) body'den uygular. */
    private void applyKeywordFeatureFields(KeywordMonitor m, Map<String, Object> body) {
        if (body.get("caseSensitive")         instanceof Boolean b) m.setCaseSensitive(b);
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
        if (body.get("notifyEmail")           instanceof Boolean b) m.setNotifyEmail(b);
        if (body.get("notifyWebhook")            instanceof Boolean b) m.setNotifyWebhook(b);
        if (body.get("slowResponseEnabled")   instanceof Boolean b) m.setSlowResponseEnabled(b);
        if (body.get("slowThresholdMs")       instanceof Number n)  m.setSlowThresholdMs(Math.max(1, n.intValue()));
        if (body.get("checkSslErrors")        instanceof Boolean b) m.setCheckSslErrors(b);
        if (body.get("sslExpiryReminders")    instanceof Boolean b) m.setSslExpiryReminders(b);
        if (body.get("domainExpiryReminders") instanceof Boolean b) m.setDomainExpiryReminders(b);
        if (!blank(body.get("sslReminderDays")))    m.setSslReminderDays(body.get("sslReminderDays").toString().trim());
        if (!blank(body.get("domainReminderDays"))) m.setDomainReminderDays(body.get("domainReminderDays").toString().trim());
    }

    // ── HTTP / Website Monitors (serbest-form) ────────────────────────────────

    @GetMapping("/http")
    public ResponseEntity<Map<String, Object>> listHttp(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, HttpCheck> latest = httpCheckRepo.findLatestPerMonitor().stream()
                .filter(c -> c.getMonitorId() != null)
                .collect(Collectors.toMap(HttpCheck::getMonitorId, c -> c, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        // IDOR (H2): yalnız görüntülenebilir takımların monitörleri (global admin → hepsi).
        List<HttpMonitor> monitors = httpMonitorRepo.findAllByOrderByNameAsc().stream()
                .filter(m -> SessionScope.canView(session, m.getTeamId())).toList();
        Map<String, AlertEvent> alarms = openAlarmsByDomain(
                monitors.stream().map(HttpMonitor::getUrl).collect(Collectors.toSet()),
                EscalationService.TYPE_HTTP_DOWN);
        List<Map<String, Object>> result = monitors.stream()
                .map(m -> enrichHttp(m, latest.get(m.getId()), teams, alarms.get(m.getUrl()))).toList();
        return ok(result);
    }

    @PostMapping("/http")
    public ResponseEntity<Map<String, Object>> createHttp(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, true); if (_gt != null) return _gt; }   // grup + etiket zorunlu (2026-09-18)
        if (blank(body.get("url"))) return badRequest("url zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        String url = MonitorUrls.normalize(body.get("url").toString());   // şemasız girdiye https:// eklenir
        if (!MonitorUrls.isCheckable(url)) return badRequest(INVALID_URL_MSG);
        if (httpMonitorRepo.existsDuplicate(url, teamId, null))           // mükerrer kontrolü normalize edilmiş değerle
            return badRequest("Bu URL bu takımda zaten izleniyor; mükerrer HTTP monitörü oluşturulamaz.");
        String now = ISO.format(Instant.now());
        HttpMonitor m = new HttpMonitor();
        m.setName(blank(body.get("name")) ? url : body.get("name").toString());
        m.setUrl(url);
        m.setMethod(normalizeHttpMethod(body.get("method")));
        if (!blank(body.get("expectedStatus"))) m.setExpectedStatus(body.get("expectedStatus").toString().trim());
        if (body.get("followRedirects") instanceof Boolean b) m.setFollowRedirects(b);
        if (body.get("verifySsl")       instanceof Boolean b) m.setVerifySsl(b);
        if (body.containsKey("useProxy"))  m.setUseProxy(com.sitemonitor.service.ProxyPolicyService.normalizeMode(body.get("useProxy")));
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        m.setTeamId(teamId);
        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
        m.setActive(true);                                                // varsayılan: yeni izleme aktif
        if (body.get("active") instanceof Boolean ab) m.setActive(ab);    // Kopyala: pasif kaynağın kopyası da pasif doğsun
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
        if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
        if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
        if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
        applyHttpFeatureFields(m, body);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        HttpMonitor saved = httpMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.HTTP, saved.getId(), saved.getName(),
                saved.getUrl(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "HTTP_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        // İLK DEĞERLER: denetim create'te changes=null geçiyor (güvenlik kaydı "ne oldu"yu yazar);
        // ürün geçmişi "hangi değerlerle doğdu" sorusunu cevaplamak zorunda.
        monitorHistory.record(MonitorHistoryService.HTTP, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
        return ok(enrichHttp(saved, null, teamNameMap(), null));
    }

    @PutMapping("/http/{id}")
    public ResponseEntity<Map<String, Object>> updateHttp(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, false); if (_gt != null) return _gt; }   // gönderilip boş bırakılmışsa 400 (2026-09-18)
        java.util.Map<String, Object> _before = httpMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return httpMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("url")             != null) {
                String u = MonitorUrls.normalize(body.get("url").toString());
                if (!MonitorUrls.isCheckable(u)) return badRequest(INVALID_URL_MSG);
                m.setUrl(u);
            }
            if (body.get("method")          != null) m.setMethod(normalizeHttpMethod(body.get("method")));
            if (!blank(body.get("expectedStatus"))) m.setExpectedStatus(body.get("expectedStatus").toString().trim());
            if (body.get("followRedirects") instanceof Boolean b) m.setFollowRedirects(b);
            if (body.get("verifySsl")       instanceof Boolean b) m.setVerifySsl(b);
            if (body.containsKey("useProxy"))  m.setUseProxy(com.sitemonitor.service.ProxyPolicyService.normalizeMode(body.get("useProxy")));
            if (body.containsKey("groupName"))       m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
            closeAlertsOnPause(m.getActive(), body.get("active"), m.getUrl(), Set.of(EscalationService.TYPE_HTTP_DOWN, EscalationService.TYPE_HTTP_SSL, EscalationService.TYPE_DOMAIN_EXPIRY));
            if (body.get("active")          instanceof Boolean b) m.setActive(b);
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            applyHttpFeatureFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            monitorHistory.stampUpdated(m, session);
            HttpMonitor saved = httpMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "HTTP_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            // Aynı before/after çifti geçmişe de gider — audit çağrısına DOKUNULMAZ.
            var changeRow = monitorHistory.record(MonitorHistoryService.HTTP, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
            noteConfigChanged(changeRow, ActivityLogService.HTTP, saved.getUrl(), session);
            return ok(enrichHttp(saved, httpCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getUrl(), EscalationService.TYPE_HTTP_DOWN).orElse(null)));
        }).orElse(notFound("HTTP monitor not found"));
    }

    @DeleteMapping("/http/{id}")
    public ResponseEntity<Map<String, Object>> deleteHttp(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        // Silme ÖNCESİ durum: aşağıda active=false yapılıyor, sonra almak farkı kaybettirirdi.
        Map<String, Object> _before = httpMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return httpMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            escalationService.resolveOpenAlertsSilently(m.getUrl(),
                    Set.of(EscalationService.TYPE_HTTP_DOWN, EscalationService.TYPE_HTTP_SSL, EscalationService.TYPE_DOMAIN_EXPIRY),
                    "Sistem (izleme silindi)");
            httpMonitorRepo.delete(m);
            activityLog.recordLifecycle(ActivityLogService.HTTP, m.getId(), m.getName(),
                    m.getUrl(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "HTTP_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            monitorHistory.record(MonitorHistoryService.HTTP, m.getId(), m.getName(), m.getTeamId(),
                    MonitorHistoryService.DELETE, _before, AuditDiff.snapshot(m, MON_FIELDS), null, session);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("HTTP monitor not found"));
    }

    @GetMapping("/http/{id}/history")
    public ResponseEntity<?> httpHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        HttpMonitor mon = httpMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("HTTP monitor not found");
        var src = new CheckHistoryService.Source<HttpCheck>() {
            public org.springframework.data.domain.Page<HttpCheck> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? httpCheckRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t, p)
                            : httpCheckRepo.findByMonitorIdAndCheckedAtBetween(id, f, t, p);
            }
            public long total(String f, String t) { return httpCheckRepo.countByMonitorIdAndCheckedAtBetween(id, f, t); }
            public long fail(String f, String t) { return httpCheckRepo.countByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return httpCheckRepo.historyHistogram(id, f, t, len); }
            public List<Object[]> bounds() { return httpCheckRepo.historyBounds(id); }
        };
        return runHistory(session, mon.getTeamId(), src, "http",
                mon.getUrl(), Set.of(EscalationService.TYPE_HTTP_DOWN, EscalationService.TYPE_HTTP_SSL,
                        EscalationService.TYPE_DOMAIN_EXPIRY),
                from, to, days, status, page, size, format, "http-history-" + id, List.of(
                new CsvColumn<>("checked_at", HttpCheck::getCheckedAt),
                new CsvColumn<>("ok", HttpCheck::getOk),
                new CsvColumn<>("http_status", HttpCheck::getHttpStatus),
                new CsvColumn<>("response_ms", HttpCheck::getResponseMs),
                new CsvColumn<>("error", HttpCheck::getError)), response);
    }

    @PostMapping("/http/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerHttp(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return httpMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            // İLK kontrol burada koşar: kullanıcı sonucu ANINDA görsün (kart boş dönmesin).
            Map<String, Object> r = httpChecker.check(m.getUrl(), m.getMethod(), m.getExpectedStatus(),
                    m.getTimeoutMs() != null ? m.getTimeoutMs() : 10000,
                    Boolean.TRUE.equals(m.getVerifySsl()), !Boolean.FALSE.equals(m.getFollowRedirects()), viaProxy(m.getUrl(), m.getUseProxy()));
            HttpCheck res = new HttpCheck();
            res.setMonitorId(m.getId());
            res.setOk(Boolean.TRUE.equals(r.get("ok")));
            res.setHttpStatus(r.get("http_status") instanceof Number n ? n.intValue() : null);
            res.setResponseMs(r.get("response_ms") instanceof Number n ? n.longValue() : null);
            res.setError((String) r.get("error"));
            res.setCheckedAt(ISO.format(Instant.now()));
            httpCheckRepo.save(res);
            auditService.recordAction("MONITOR_TRIGGER", session, "HTTP_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            // ...ve ARDINDAN zamanlayıcıyla AYNI değerlendirme hattı ASENKRON başlar: hata
            // doğrulama denemelerinden geçer, teyit edilirse alarm açılır; düzelme kurtarma
            // sayacından geçer. Eskiden manuel çalıştırma tek kontrol yapıp bırakıyordu —
            // ekranda "hata" görünüyor ama alarm hiç açılmıyordu (iki farklı gerçek).
            // Senkron beklenemez: doğrulama varsayılan 3 × 30 sn sürer.
            schedulerService.evaluateHttpNow(m, r);   // AYNI sonuç — ikinci kontrol/kayıt YOK
            return ok(enrichHttp(m, res, teamNameMap(),
                    alertEventRepo.findOpenAlert(m.getUrl(), EscalationService.TYPE_HTTP_DOWN).orElse(null)));
        }).orElse(notFound("HTTP monitor not found"));
    }

    /** Ad-hoc HTTP testi — kaydetmeden, formdaki url/method/expectedStatus ile bir kez istek atar. */
    @PostMapping("/http/test")
    public ResponseEntity<Map<String, Object>> testHttp(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        String url = body.get("url") != null ? MonitorUrls.normalize(body.get("url").toString()) : "";
        if (url.isEmpty()) return badRequest("url zorunlu");
        if (!MonitorUrls.isCheckable(url)) return badRequest(INVALID_URL_MSG);
        String method = normalizeHttpMethod(body.get("method"));
        String expected = !blank(body.get("expectedStatus")) ? body.get("expectedStatus").toString().trim() : "200-399";
        int timeoutMs = body.get("timeoutMs") instanceof Number tn ? tn.intValue() : 10000;
        boolean verifySsl = Boolean.TRUE.equals(body.get("verifySsl"));
        boolean followRedirects = !Boolean.FALSE.equals(body.get("followRedirects"));
        com.sitemonitor.service.ProxyPolicyService.Decision pd = proxyDecision(url, body.get("useProxy"));
        // `verify_ssl=false` denetimde AÇIKÇA görünür: doğrulamayı kapatarak yapılan bir prob,
        // güvenlik incelemesinde diğerlerinden farklı bir sorudur.
        auditService.recordAction("MONITOR_TEST", session, "HTTP_MONITOR", "test",
                AuditDetail.of("url", AuditDetail.safeTarget(url), "method", method,
                        "expected", expected, "verify_ssl", verifySsl,
                        "follow_redirects", followRedirects, "via", pd.via()), null);
        Map<String, Object> r = httpChecker.check(url, method, expected, timeoutMs, verifySsl, followRedirects, pd.viaProxy());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("via",             r.getOrDefault("via", pd.via()));
        out.put("proxy_source",    pd.source());
        out.put("http_status",     r.get("http_status"));
        out.put("response_ms",     r.get("response_ms"));
        out.put("condition_met",   Boolean.TRUE.equals(r.get("ok")));
        out.put("expected_status", expected);
        out.put("error",           r.get("error"));
        return ok(out);
    }

    @GetMapping("/http/{id}/response-series")
    public ResponseEntity<Map<String, Object>> httpResponseSeries(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        HttpMonitor hmon = httpMonitorRepo.findById(id).orElse(null);
        if (hmon == null) return notFound("HTTP monitor not found");
        var deny = denyIfNotViewable(session, hmon.getTeamId());   // IDOR (H3)
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        return ok(buildResponseSeries(httpCheckRepo.responseSeriesRaw(id, range[0], range[1], SERIES_RAW_CAP),
                range[0], range[1], false));
    }

    /** Ortak: HTTP feature alanlarını (tags, notify, SSL/Domain toggle'ları + gün eşikleri) body'den uygular. */
    /** Vekil kararı (2026-09-21) — bean yoksa (test bağlamı) doğrudan. */
    private com.sitemonitor.service.ProxyPolicyService.Decision proxyDecision(String url, Object mode) {
        if (proxyPolicy == null) return com.sitemonitor.service.ProxyPolicyService.Decision.direct("none");
        return proxyPolicy.decide(url, com.sitemonitor.service.ProxyPolicyService.normalizeMode(mode));
    }
    private boolean viaProxy(String url, String mode) { return proxyDecision(url, mode).viaProxy(); }

    /** Liste satırına vekil alanları: tercih, etkin karar ve kaynağı (kart rozeti + form ipucu). */
    private void putProxyFields(Map<String, Object> item, String url, String mode) {
        putProxyFieldsNormalized(item, url, com.sitemonitor.service.ProxyPolicyService.normalizeMode(mode));
    }

    /**
     * @param normalizedMode çağıranın kuralıyla normalize edilmiş kip (HTTP/Keyword/Sayfa: null→AUTO; Sayfa Hızı: null→OFF)
     */
    private void putProxyFieldsNormalized(Map<String, Object> item, String url, String normalizedMode) {
        com.sitemonitor.service.ProxyPolicyService.Decision d = proxyPolicy == null
                ? com.sitemonitor.service.ProxyPolicyService.Decision.direct("none")
                : proxyPolicy.decide(url, normalizedMode);
        item.put("use_proxy",        normalizedMode);
        item.put("proxy_effective",  d.via());
        item.put("proxy_source",     d.source());
        item.put("proxy_bypassed",   d.bypassed());
    }

    /** Sayfa Hızı satırı: varsayılan OFF (2026-09-21) — mevcut kayıtlar (null) doğrudan görünür. */
    private void putPageSpeedProxyFields(Map<String, Object> item, com.sitemonitor.model.PageSpeedMonitor m) {
        putProxyFieldsNormalized(item, m.getUrl(), com.sitemonitor.service.ProxyPolicyService.normalizeModeDefaultOff(m.getUseProxy()));
    }

    private void applyHttpFeatureFields(HttpMonitor m, Map<String, Object> body) {
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
        if (body.get("notifyEmail")           instanceof Boolean b) m.setNotifyEmail(b);
        if (body.get("notifyWebhook")            instanceof Boolean b) m.setNotifyWebhook(b);
        if (body.get("checkSslErrors")        instanceof Boolean b) m.setCheckSslErrors(b);
        if (body.get("sslExpiryReminders")    instanceof Boolean b) m.setSslExpiryReminders(b);
        if (body.get("domainExpiryReminders") instanceof Boolean b) m.setDomainExpiryReminders(b);
        if (!blank(body.get("sslReminderDays")))    m.setSslReminderDays(body.get("sslReminderDays").toString().trim());
        if (!blank(body.get("domainReminderDays"))) m.setDomainReminderDays(body.get("domainReminderDays").toString().trim());
    }

    private Map<String, Object> enrichHttp(HttpMonitor m, HttpCheck latest, Map<Long, String> teams, AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",               m.getId());
        item.put("name",             m.getName());
        item.put("url",              m.getUrl());
        item.put("method",           m.getMethod());
        item.put("expected_status",  m.getExpectedStatus());
        item.put("follow_redirects", m.getFollowRedirects());
        item.put("verify_ssl",       m.getVerifySsl());
        putProxyFields(item, m.getUrl(), m.getUseProxy());
        item.put("group_name",       m.getGroupName());
        item.put("team_id",          m.getTeamId());
        item.put("notification_group_id",          m.getNotificationGroupId());
        item.put("team_name",        m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",           m.getActive());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("timeout_ms",       m.getTimeoutMs());
        item.put("confirm_attempts",         m.getConfirmAttempts());
        item.put("confirm_interval_seconds", m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("tags",                      m.getTags());
        item.put("alert_level",     com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
        item.put("notify_email",              m.getNotifyEmail());
        item.put("notify_webhook",              m.getNotifyWebhook());
        item.put("check_ssl_errors",          m.getCheckSslErrors());
        item.put("ssl_expiry_reminders",      m.getSslExpiryReminders());
        item.put("domain_expiry_reminders",   m.getDomainExpiryReminders());
        item.put("ssl_reminder_days",         m.getSslReminderDays());
        item.put("domain_reminder_days",      m.getDomainReminderDays());
        item.put("active_alarm",       openAlarm != null);
        item.put("alarm_level",        openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
        if (latest != null) {
            item.put("status",      latest.getError() != null ? "error" : (Boolean.TRUE.equals(latest.getOk()) ? "up" : "down"));
            item.put("ok",          latest.getOk());
            item.put("http_status", latest.getHttpStatus());
            item.put("response_ms", latest.getResponseMs());
            item.put("error",       latest.getError());
            item.put("checked_at",  latest.getCheckedAt());
        } else {
            item.put("status", "unknown");
            item.put("ok", null); item.put("http_status", null);
            item.put("response_ms", null); item.put("error", null); item.put("checked_at", null);
        }
        return item;
    }

    /** Canlı teyit zincirleri — "Teyit denemesi X/N" (tüm izleme türleri; domain= ile filtrelenebilir).
     *  Detay modalları 30sn'de bir poll eder; yazma yok, in-memory durumun anlık görüntüsü. */
    @GetMapping("/confirmations")
    public ResponseEntity<Map<String, Object>> confirmations(
            @RequestParam(required = false) String domain, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        return ok(monitoringOutageService.activeConfirmations(
                domain != null && !domain.isBlank() ? domain.trim() : null));
    }

    // ── Sayfa Bütünlüğü (Page Integrity) Monitors — 9. tür (serbest-form) ─────
    @GetMapping("/page")
    public ResponseEntity<Map<String, Object>> listPage(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, com.sitemonitor.model.PageCheck> latest = pageCheckRepo.findLatestPerMonitor().stream()
                .filter(c -> c.getMonitorId() != null)
                .collect(Collectors.toMap(com.sitemonitor.model.PageCheck::getMonitorId, c -> c, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        // IDOR (H2): yalnız oturumun görüntüleyebildiği takımların monitörleri (global admin → hepsi).
        List<com.sitemonitor.model.PageMonitor> monitors = pageMonitorRepo.findAllByOrderByNameAsc().stream()
                .filter(m -> SessionScope.canView(session, m.getTeamId())).toList();
        Set<String> urls = monitors.stream().map(com.sitemonitor.model.PageMonitor::getUrl).collect(Collectors.toSet());
        Map<String, AlertEvent> down = openAlarmsByDomain(urls, EscalationService.TYPE_PAGE_DOWN);
        Map<String, AlertEvent> integ = openAlarmsByDomain(urls, EscalationService.TYPE_PAGE_INTEGRITY);
        List<Map<String, Object>> result = monitors.stream()
                .map(m -> enrichPage(m, latest.get(m.getId()), teams,
                        down.getOrDefault(m.getUrl(), integ.get(m.getUrl())))).toList();
        return ok(result);
    }

    @PostMapping("/page")
    public ResponseEntity<Map<String, Object>> createPage(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, true); if (_gt != null) return _gt; }   // grup + etiket zorunlu (2026-09-18)
        if (blank(body.get("url"))) return badRequest("url zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        String url = MonitorUrls.normalize(body.get("url").toString());   // şemasız girdiye https:// eklenir
        if (!MonitorUrls.isCheckable(url)) return badRequest(INVALID_URL_MSG);
        if (pageMonitorRepo.existsDuplicate(url, teamId, null))           // mükerrer kontrolü normalize edilmiş değerle
            return badRequest("Bu URL bu takımda zaten izleniyor; mükerrer sayfa monitörü oluşturulamaz.");
        String now = ISO.format(Instant.now());
        com.sitemonitor.model.PageMonitor m = new com.sitemonitor.model.PageMonitor();
        m.setName(blank(body.get("name")) ? url : body.get("name").toString());
        m.setUrl(url);
        m.setTeamId(teamId);
        m.setActive(true);                                            // varsayılan: yeni izleme aktif
        if (body.get("active") instanceof Boolean b) m.setActive(b);  // Kopyala: pasif kaynağın kopyası da pasif doğsun
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
        if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
        if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
        if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
        applyPageFeatureFields(m, body);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        com.sitemonitor.model.PageMonitor saved = pageMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.PAGE, saved.getId(), saved.getName(),
                saved.getUrl(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "PAGE_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        // İLK DEĞERLER: denetim create'te changes=null geçiyor (güvenlik kaydı "ne oldu"yu yazar);
        // ürün geçmişi "hangi değerlerle doğdu" sorusunu cevaplamak zorunda.
        monitorHistory.record(MonitorHistoryService.PAGE, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
        return ok(enrichPage(saved, null, teamNameMap(), null));
    }

    @PutMapping("/page/{id}")
    public ResponseEntity<Map<String, Object>> updatePage(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, false); if (_gt != null) return _gt; }   // gönderilip boş bırakılmışsa 400 (2026-09-18)
        java.util.Map<String, Object> _before = pageMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return pageMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("url")             != null) {
                String u = MonitorUrls.normalize(body.get("url").toString());
                if (!MonitorUrls.isCheckable(u)) return badRequest(INVALID_URL_MSG);
                m.setUrl(u);
            }
            if (body.containsKey("groupName"))       m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
            closeAlertsOnPause(m.getActive(), body.get("active"), m.getUrl(), Set.of(EscalationService.TYPE_PAGE_DOWN, EscalationService.TYPE_PAGE_INTEGRITY));
            if (body.get("active")          instanceof Boolean b) m.setActive(b);
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            applyPageFeatureFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            com.sitemonitor.model.PageMonitor saved = pageMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "PAGE_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            // Aynı before/after çifti geçmişe de gider — audit çağrısına DOKUNULMAZ.
            var changeRow = monitorHistory.record(MonitorHistoryService.PAGE, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
            noteConfigChanged(changeRow, ActivityLogService.PAGE, saved.getUrl(), session);
            return ok(enrichPage(saved, pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getUrl(), EscalationService.TYPE_PAGE_DOWN)
                            .or(() -> alertEventRepo.findOpenAlert(saved.getUrl(), EscalationService.TYPE_PAGE_INTEGRITY)).orElse(null)));
        }).orElse(notFound("Sayfa monitörü bulunamadı"));
    }

    @DeleteMapping("/page/{id}")
    public ResponseEntity<Map<String, Object>> deletePage(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        // Silme ÖNCESİ durum: aşağıda active=false yapılıyor, sonra almak farkı kaybettirirdi.
        Map<String, Object> _before = pageMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return pageMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            escalationService.resolveOpenAlertsSilently(m.getUrl(),
                    Set.of(EscalationService.TYPE_PAGE_DOWN, EscalationService.TYPE_PAGE_INTEGRITY),
                    "Sistem (izleme silindi)");
            pageMonitorRepo.delete(m);
            activityLog.recordLifecycle(ActivityLogService.PAGE, m.getId(), m.getName(),
                    m.getUrl(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "PAGE_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            monitorHistory.record(MonitorHistoryService.PAGE, m.getId(), m.getName(), m.getTeamId(),
                    MonitorHistoryService.DELETE, _before, AuditDiff.snapshot(m, MON_FIELDS), null, session);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Sayfa monitörü bulunamadı"));
    }

    @GetMapping("/page/{id}/history")
    public ResponseEntity<?> pageHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        com.sitemonitor.model.PageMonitor mon = pageMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sayfa monitörü bulunamadı");
        var src = new CheckHistoryService.Source<com.sitemonitor.model.PageCheck>() {
            public org.springframework.data.domain.Page<com.sitemonitor.model.PageCheck> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? pageCheckRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t, p)
                            : pageCheckRepo.findByMonitorIdAndCheckedAtBetween(id, f, t, p);
            }
            public long total(String f, String t) { return pageCheckRepo.countByMonitorIdAndCheckedAtBetween(id, f, t); }
            public long fail(String f, String t) { return pageCheckRepo.countByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return pageCheckRepo.historyHistogram(id, f, t, len); }
            public List<Object[]> bounds() { return pageCheckRepo.historyBounds(id); }
        };
        return runHistory(session, mon.getTeamId(), src, "page",
                mon.getUrl(), Set.of(EscalationService.TYPE_PAGE_DOWN, EscalationService.TYPE_PAGE_INTEGRITY),
                from, to, days, status, page, size, format, "page-history-" + id, List.of(
                new CsvColumn<>("checked_at", com.sitemonitor.model.PageCheck::getCheckedAt),
                new CsvColumn<>("ok", com.sitemonitor.model.PageCheck::getOk),
                new CsvColumn<>("status", com.sitemonitor.model.PageCheck::getStatus),
                new CsvColumn<>("http_status", com.sitemonitor.model.PageCheck::getHttpStatus),
                new CsvColumn<>("response_ms", com.sitemonitor.model.PageCheck::getResponseMs),
                new CsvColumn<>("total_resources", com.sitemonitor.model.PageCheck::getTotalResources),
                new CsvColumn<>("broken_resources", com.sitemonitor.model.PageCheck::getBrokenResources),
                new CsvColumn<>("timeout_count", com.sitemonitor.model.PageCheck::getTimeoutCount),
                new CsvColumn<>("mixed_content_count", com.sitemonitor.model.PageCheck::getMixedContentCount),
                new CsvColumn<>("error", com.sitemonitor.model.PageCheck::getError)), response);
    }

    /** Sorunlu-kaynak listesi — pencere içindeki ÇOK kontrolü (yalnız son değil) checked_at DESC + SQL-LIMIT'li
     *  döndürür; frontend'deki "Zaman" kolonu + kontrol-arası ayraç bunları ayrıştırır. Filtre: issueType, tarih. */
    @GetMapping("/page/{id}/issues")
    public ResponseEntity<Map<String, Object>> pageIssues(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String issueType, @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "500") int limit) {
        com.sitemonitor.model.PageMonitor mon = pageMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sayfa monitörü bulunamadı");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        String since = (days != null && days > 0) ? ISO.format(Instant.now().minus(days, ChronoUnit.DAYS)) : null;
        String type = blank(issueType) ? null : issueType.toString().trim().toUpperCase();
        int cap = Math.max(1, Math.min(limit, 5000));
        return ok(pageResourceIssueRepo.findFiltered(id, type, since, cap));
    }

    @PostMapping("/page/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerPage(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return pageMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            // H1c: per-monitör cooldown — sayfa kontrolü (main + N kaynak) pahalıdır; art arda tetik request-thread'i tüketmesin.
            long nowMs = System.currentTimeMillis();
            long cooldownMs = appSettings.getInt("site.monitor.page.manual-cooldown-seconds", 20) * 1000L;
            Long prev = pageManualTriggerAt.get(id);
            if (prev != null && nowMs - prev < cooldownMs) {
                return ResponseEntity.status(429).body(Map.<String, Object>of("success", false,
                        "error", "Bu monitör için çok sık manuel kontrol; " + (cooldownMs / 1000) + " sn bekleyin."));
            }
            pageManualTriggerAt.put(id, nowMs);
            Map<String, Object> pageResult = schedulerService.triggerPageCheck(m);   // tam kontrol + persist
            auditService.recordAction("MONITOR_TRIGGER", session, "PAGE_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            // Değerlendirme (doğrulama denemeleri → alarm) koşumun KENDİ içinde yapılır
            // (triggerScriptedCheckAsync): ayrı bir evaluate* çağrısı ikinci bir k6 koşumu
            // başlatıyor ve iki permit yiyordu.
            schedulerService.evaluatePageNow(m, pageResult);   // AYNI sonuç — ikinci TAM TARAMA yok
            return ok(enrichPage(m, pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(m.getUrl(), EscalationService.TYPE_PAGE_DOWN)
                            .or(() -> alertEventRepo.findOpenAlert(m.getUrl(), EscalationService.TYPE_PAGE_INTEGRITY)).orElse(null)));
        }).orElse(notFound("Sayfa monitörü bulunamadı"));
    }

    /** Ad-hoc sayfa testi — kaydetmeden, formdaki url ile tek SINGLE_PAGE bütünlük kontrolü. */
    @PostMapping("/page/test")
    public ResponseEntity<Map<String, Object>> testPage(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        String url = body.get("url") != null ? MonitorUrls.normalize(body.get("url").toString()) : "";
        if (url.isEmpty()) return badRequest("url zorunlu");
        if (!MonitorUrls.isCheckable(url)) return badRequest(INVALID_URL_MSG);
        int timeoutMs = body.get("timeoutMs") instanceof Number tn ? tn.intValue() : 4000;
        com.sitemonitor.service.ProxyPolicyService.Decision ppd = proxyDecision(url, body.get("useProxy"));
        auditService.recordAction("MONITOR_TEST", session, "PAGE_MONITOR", "test",
                AuditDetail.of("url", AuditDetail.safeTarget(url), "via", ppd.via()), null);
        com.sitemonitor.service.PageCheckerService.PageCheckResult r = pageChecker.test(url, timeoutMs, ppd.viaProxy());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("via",                 ppd.via());
        out.put("proxy_source",        ppd.source());
        out.put("status",              r.status());
        out.put("main_reachable",      r.mainReachable());
        out.put("http_status",         r.httpStatus());
        out.put("response_ms",         r.responseMs());
        out.put("total_resources",     r.totalResources());
        out.put("broken_resources",    r.brokenResources());
        out.put("timeout_count",       r.timeoutResources());
        out.put("mixed_content_count", r.mixedContentCount());
        out.put("error",               r.error());
        List<Map<String, Object>> issues = new ArrayList<>();
        for (var i : r.issues()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("resource_url", i.resourceUrl()); im.put("resource_type", i.resourceType());
            im.put("issue_type", i.issueType()); im.put("first_party", i.firstParty());
            im.put("http_status", i.httpStatus()); im.put("duration_ms", i.durationMs());
            issues.add(im);
        }
        out.put("issues", issues);
        return ok(out);
    }

    @GetMapping("/page/{id}/response-series")
    public ResponseEntity<Map<String, Object>> pageResponseSeries(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        com.sitemonitor.model.PageMonitor mon = pageMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sayfa monitörü bulunamadı");
        var deny = denyIfNotViewable(session, mon.getTeamId());   // IDOR (H3): başka takımın serisi okunamaz
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        // Seri değeri = kırık kaynak sayısı (buildResponseSeries yeniden kullanılır; frontend "kırık kaynak" etiketler).
        return ok(buildResponseSeries(pageCheckRepo.responseSeriesRaw(id, range[0], range[1], SERIES_RAW_CAP),
                range[0], range[1], false));
    }


    // ── Sayfa Hızı (Page Speed) Monitors ─────────────────────────────────────
    //
    // Snapshot alanları: MON_FIELDS ortak alanları KAPSAMAZ (bu türe özgü eşikler/gelişmiş alanlar var).
    // basicAuthPassEnc ve customHeadersEnc listede BİLİNÇLİ duruyor: AuditDiff.isSensitive onları
    // maskeliyor (SecretMask "_pass_"/"cipher" segmentleri), böylece geçmişte "parola değişti" görünür
    // ama DEĞERİ görünmez. Listeden çıkarmak değişikliği tamamen görünmez yapardı.
    private static final String[] PAGESPEED_FIELDS = {
            "name", "url", "active", "teamId", "groupName", "intervalSeconds", "timeoutMs",
            "maxLoadMs", "maxTtfbMs", "maxPageKb", "maxRequests",
            "userAgent", "sendDnt", "useProxy", "excludeTrackers", "trackerPatterns",
            "basicAuthUser", "basicAuthPassEnc", "customHeadersEnc", "resourceConcurrency",
            "confirmAttempts", "confirmIntervalSeconds", "recoveryChecks", "recoveryIntervalSeconds",
            "tags", "notifyEmail", "notifyWebhook" };

    @GetMapping("/pagespeed")
    public ResponseEntity<Map<String, Object>> listPageSpeed(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, com.sitemonitor.model.PageSpeedCheck> latest = pageSpeedCheckRepo.findLatestPerMonitor().stream()
                .filter(c -> c.getMonitorId() != null)
                .collect(Collectors.toMap(com.sitemonitor.model.PageSpeedCheck::getMonitorId, c -> c, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        // IDOR: yalnız oturumun görüntüleyebildiği takımların izlemeleri (global admin → hepsi).
        List<com.sitemonitor.model.PageSpeedMonitor> monitors = pageSpeedMonitorRepo.findAllByOrderByNameAsc().stream()
                .filter(m -> SessionScope.canView(session, m.getTeamId())).toList();
        Set<String> urls = monitors.stream().map(com.sitemonitor.model.PageSpeedMonitor::getUrl).collect(Collectors.toSet());
        Map<String, AlertEvent> down = openAlarmsByDomain(urls, EscalationService.TYPE_PAGESPEED_DOWN);
        Map<String, AlertEvent> slow = openAlarmsByDomain(urls, EscalationService.TYPE_PAGESPEED_SLOW);
        boolean admin = SessionScope.isGlobalAdmin(session);
        List<Map<String, Object>> result = monitors.stream()
                .map(m -> enrichPageSpeed(m, latest.get(m.getId()), teams,
                        down.getOrDefault(m.getUrl(), slow.get(m.getUrl())), admin)).toList();
        return ok(result);
    }

    @PostMapping("/pagespeed")
    public ResponseEntity<Map<String, Object>> createPageSpeed(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, true); if (_gt != null) return _gt; }   // grup + etiket zorunlu (2026-09-18)
        if (blank(body.get("url"))) return badRequest("url zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        String url = MonitorUrls.normalize(body.get("url").toString());   // şemasız girdiye https:// eklenir
        if (!MonitorUrls.isCheckable(url)) return badRequest(INVALID_URL_MSG);
        if (pageSpeedMonitorRepo.existsDuplicate(url, teamId, null))
            return badRequest("Bu URL bu takımda zaten hız açısından izleniyor; mükerrer izleme oluşturulamaz.");
        String now = ISO.format(Instant.now());
        com.sitemonitor.model.PageSpeedMonitor m = new com.sitemonitor.model.PageSpeedMonitor();
        m.setName(blank(body.get("name")) ? url : body.get("name").toString());
        m.setUrl(url);
        m.setTeamId(teamId);
        m.setActive(true);                                            // varsayılan: yeni izleme aktif
        if (body.get("active") instanceof Boolean b) m.setActive(b);  // Kopyala: pasif kaynağın kopyası da pasif doğsun
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
        applyPageSpeedFields(m, body, session);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        com.sitemonitor.model.PageSpeedMonitor saved = pageSpeedMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.PAGESPEED, saved.getId(), saved.getName(),
                saved.getUrl(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "PAGESPEED_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        monitorHistory.record(MonitorHistoryService.PAGESPEED, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, PAGESPEED_FIELDS), changeNote(body), session);
        return ok(enrichPageSpeed(saved, null, teamNameMap(), null, SessionScope.isGlobalAdmin(session)));
    }

    @PutMapping("/pagespeed/{id}")
    public ResponseEntity<Map<String, Object>> updatePageSpeed(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, false); if (_gt != null) return _gt; }   // gönderilip boş bırakılmışsa 400 (2026-09-18)
        Map<String, Object> _before = pageSpeedMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, PAGESPEED_FIELDS)).orElse(null);
        return pageSpeedMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name") != null) m.setName((String) body.get("name"));
            if (body.get("url")  != null) {
                String u = MonitorUrls.normalize(body.get("url").toString());
                if (!MonitorUrls.isCheckable(u)) return badRequest(INVALID_URL_MSG);
                m.setUrl(u);
            }
            if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId"))    m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
            closeAlertsOnPause(m.getActive(), body.get("active"), m.getUrl(), Set.of(EscalationService.TYPE_PAGESPEED_DOWN, EscalationService.TYPE_PAGESPEED_SLOW));
            if (body.get("active") instanceof Boolean b) m.setActive(b);
            applyPageSpeedFields(m, body, session);
            m.setUpdatedAt(ISO.format(Instant.now()));
            com.sitemonitor.model.PageSpeedMonitor saved = pageSpeedMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "PAGESPEED_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, PAGESPEED_FIELDS)));
            var changeRow = monitorHistory.record(MonitorHistoryService.PAGESPEED, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, PAGESPEED_FIELDS), changeNote(body), session);
            noteConfigChanged(changeRow, ActivityLogService.PAGESPEED, saved.getUrl(), session);
            return ok(enrichPageSpeed(saved, pageSpeedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    openPageSpeedAlarm(saved.getUrl()), SessionScope.isGlobalAdmin(session)));
        }).orElse(notFound("Sayfa hızı izlemesi bulunamadı"));
    }

    @DeleteMapping("/pagespeed/{id}")
    public ResponseEntity<Map<String, Object>> deletePageSpeed(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        Map<String, Object> _before = pageSpeedMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, PAGESPEED_FIELDS)).orElse(null);
        return pageSpeedMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            escalationService.resolveOpenAlertsSilently(m.getUrl(),
                    Set.of(EscalationService.TYPE_PAGESPEED_DOWN, EscalationService.TYPE_PAGESPEED_SLOW),
                    "Sistem (izleme silindi)");
            // Ölçüm serisi ve kaynak kırılımı da gider — yoksa aynı id yeniden kullanıldığında
            // yeni izlemeye eski izlemenin geçmişi yapışırdı.
            pageSpeedResourceRepo.deleteByMonitorId(m.getId());
            pageSpeedCheckRepo.deleteByMonitorId(m.getId());
            pageSpeedMonitorRepo.delete(m);
            activityLog.recordLifecycle(ActivityLogService.PAGESPEED, m.getId(), m.getName(),
                    m.getUrl(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "PAGESPEED_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            monitorHistory.record(MonitorHistoryService.PAGESPEED, m.getId(), m.getName(), m.getTeamId(),
                    MonitorHistoryService.DELETE, _before, AuditDiff.snapshot(m, PAGESPEED_FIELDS), null, session);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Sayfa hızı izlemesi bulunamadı"));
    }

    @GetMapping("/pagespeed/{id}/history")
    public ResponseEntity<?> pageSpeedHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        com.sitemonitor.model.PageSpeedMonitor mon = pageSpeedMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sayfa hızı izlemesi bulunamadı");
        var src = new CheckHistoryService.Source<com.sitemonitor.model.PageSpeedCheck>() {
            public org.springframework.data.domain.Page<com.sitemonitor.model.PageSpeedCheck> page(
                    String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? pageSpeedCheckRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t, p)
                            : pageSpeedCheckRepo.findByMonitorIdAndCheckedAtBetween(id, f, t, p);
            }
            public long total(String f, String t) { return pageSpeedCheckRepo.countByMonitorIdAndCheckedAtBetween(id, f, t); }
            public long fail(String f, String t) { return pageSpeedCheckRepo.countByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return pageSpeedCheckRepo.historyHistogram(id, f, t, len); }
            public List<Object[]> bounds() { return pageSpeedCheckRepo.historyBounds(id); }
        };
        // "Hata" filtresi ok=false demektir: eşik aşımı (SLOW) BURAYA GİRMEZ — o bir kesinti değil,
        // ayrı bir kolonda (breached_metrics) taşınır.
        return runHistory(session, mon.getTeamId(), src, "pagespeed",
                mon.getUrl(), Set.of(EscalationService.TYPE_PAGESPEED_DOWN, EscalationService.TYPE_PAGESPEED_SLOW),
                from, to, days, status, page, size, format, "pagespeed-history-" + id, List.of(
                new CsvColumn<>("checked_at", com.sitemonitor.model.PageSpeedCheck::getCheckedAt),
                new CsvColumn<>("ok", com.sitemonitor.model.PageSpeedCheck::getOk),
                new CsvColumn<>("http_status", com.sitemonitor.model.PageSpeedCheck::getStatusCode),
                new CsvColumn<>("ttfb_ms", com.sitemonitor.model.PageSpeedCheck::getTtfbMs),
                new CsvColumn<>("response_ms", com.sitemonitor.model.PageSpeedCheck::getResponseMs),
                new CsvColumn<>("total_bytes", com.sitemonitor.model.PageSpeedCheck::getTotalBytes),
                new CsvColumn<>("request_count", com.sitemonitor.model.PageSpeedCheck::getRequestCount),
                new CsvColumn<>("failed_count", com.sitemonitor.model.PageSpeedCheck::getFailedCount),
                new CsvColumn<>("bytes_truncated", com.sitemonitor.model.PageSpeedCheck::getBytesTruncated),
                new CsvColumn<>("breached_metrics", com.sitemonitor.model.PageSpeedCheck::getBreachedMetrics),
                // İhlal delili: hangi eşik neydi, ölçülen neydi — dışa aktarımda da taşınmalı,
                // yoksa CSV'ye bakan kişi "eşik aşıldı"nın sebebini yine göremez.
                new CsvColumn<>("breach_detail", com.sitemonitor.model.PageSpeedCheck::getBreachDetail),
                new CsvColumn<>("dns_ms", com.sitemonitor.model.PageSpeedCheck::getDnsMs),
                new CsvColumn<>("connect_ms", com.sitemonitor.model.PageSpeedCheck::getConnectMs),
                new CsvColumn<>("tls_ms", com.sitemonitor.model.PageSpeedCheck::getTlsMs),
                new CsvColumn<>("server_ms", com.sitemonitor.model.PageSpeedCheck::getServerMs),
                new CsvColumn<>("error", com.sitemonitor.model.PageSpeedCheck::getErrorMessage)), response);
    }

    @PostMapping("/pagespeed/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerPageSpeed(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return pageSpeedMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            // Per-monitör cooldown: bir ölçüm ana sayfa + onlarca kaynak isteği demek; art arda tetik
            // request-thread'lerini tüketir (sayfa bütünlüğündeki H1c ile aynı gerekçe).
            long nowMs = System.currentTimeMillis();
            long cooldownMs = appSettings.getInt("site.monitor.pagespeed.manual-cooldown-seconds", 30) * 1000L;
            Long prev = pageSpeedManualTriggerAt.get(id);
            if (prev != null && nowMs - prev < cooldownMs) {
                return ResponseEntity.status(429).body(Map.<String, Object>of("success", false,
                        "error", "Bu izleme için çok sık manuel ölçüm; " + (cooldownMs / 1000) + " sn bekleyin."));
            }
            pageSpeedManualTriggerAt.put(id, nowMs);
            Map<String, Object> speedResult = schedulerService.triggerPageSpeedCheck(m);   // tam ölçüm + persist
            auditService.recordAction("MONITOR_TRIGGER", session, "PAGESPEED_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            // Değerlendirme (doğrulama denemeleri → alarm) koşumun KENDİ içinde yapılır
            // (triggerScriptedCheckAsync): ayrı bir evaluate* çağrısı ikinci bir k6 koşumu
            // başlatıyor ve iki permit yiyordu.
            schedulerService.evaluatePageSpeedNow(m, speedResult);   // AYNI sonuç — ikinci ölçüm yok
            return ok(enrichPageSpeed(m, pageSpeedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null),
                    teamNameMap(), openPageSpeedAlarm(m.getUrl()), SessionScope.isGlobalAdmin(session)));
        }).orElse(notFound("Sayfa hızı izlemesi bulunamadı"));
    }

    /** Kaydetmeden canlı deneme — formdaki değerlerle tek ölçüm; DB'ye hiçbir şey yazmaz. */
    @PostMapping("/pagespeed/test")
    public ResponseEntity<Map<String, Object>> testPageSpeed(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        String url = body.get("url") != null ? MonitorUrls.normalize(body.get("url").toString()) : "";
        if (url.isEmpty()) return badRequest("url zorunlu");
        if (!MonitorUrls.isCheckable(url)) return badRequest(INVALID_URL_MSG);
        // OTURUM BAZLI bekleme. Kayıtlı ölçümün ("/pagespeed/{id}/check") cooldown'u var ama bu ucun
        // yoktu — oysa AYNI işi yapıyor: ana sayfa + yüzlerce alt kaynak GET'i, gövdeler dahil (kardeş
        // "/page/test" yalnız HEAD attığı için ucuz, bu değil). Tek pod 100 eşzamanlı kullanıcıya
        // hizmet ediyor; "Şimdi Dene"ye üst üste basmak istek thread'lerini ve bant genişliğini
        // tüketebiliyordu. Anahtar oturumun KENDİSİNDE tutulur: harita yok → sızıntı da yok.
        long nowMs = System.currentTimeMillis();
        // Kayitli olcumun 30 sn'sinden AYRI ve daha kisa: burasi form doldururken kullaniliyor,
        // kullanici URL'i duzeltip tekrar denemek istiyor. 10 sn ust uste tiklamayi keser ama
        // mesru kullanimi engellemez.
        long testCooldownMs = appSettings.getInt("site.monitor.pagespeed.test-cooldown-seconds", 10) * 1000L;
        Object prevTest = session.getAttribute(PAGESPEED_TEST_AT);
        if (prevTest instanceof Long p && nowMs - p < testCooldownMs) {
            return ResponseEntity.status(429).body(Map.<String, Object>of("success", false,
                    "error", "Çok sık deneme; " + (testCooldownMs / 1000) + " sn bekleyin."));
        }
        session.setAttribute(PAGESPEED_TEST_AT, nowMs);
        com.sitemonitor.model.PageSpeedMonitor draft = new com.sitemonitor.model.PageSpeedMonitor();
        draft.setUrl(url);
        applyPageSpeedFields(draft, body, session);
        // Denemede eşik DEĞERLENDİRİLMEZ (checker null eşikle çağrılır) — kullanıcı önce ham ölçümü görsün,
        // eşiği ona bakarak koysun.
        auditService.recordAction("MONITOR_TEST", session, "PAGESPEED_MONITOR", "test",
                AuditDetail.of("url", AuditDetail.safeTarget(url)), null);
        com.sitemonitor.service.ProxyPolicyService.Decision psd = proxyPolicy == null
                ? com.sitemonitor.service.ProxyPolicyService.Decision.direct("none")
                : proxyPolicy.decide(url, com.sitemonitor.service.ProxyPolicyService.normalizeModeDefaultOff(draft.getUseProxy()));
        PageSpeedCheckerService.Result r = pageSpeedChecker.test(draft);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("via",            psd.via());
        out.put("proxy_source",   psd.source());
        out.put("status",         r.status());
        out.put("reachable",      r.reachable());
        out.put("http_status",    r.statusCode());
        out.put("ttfb_ms",        r.ttfbMs());
        out.put("html_ms",        r.htmlMs());
        out.put("response_ms",    r.totalMs());
        out.put("total_bytes",    r.totalBytes());
        out.put("request_count",  r.requestCount());
        out.put("failed_count",   r.failedCount());
        out.put("capped",         r.capped());
        out.put("bytes_truncated", r.bytesTruncated());
        out.put("error",          r.error());
        List<Map<String, Object>> rows = new ArrayList<>();
        List<PageSpeedCheckerService.Measured> heavy = new ArrayList<>(r.resources());
        heavy.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
        for (PageSpeedCheckerService.Measured x : heavy.subList(0, Math.min(heavy.size(), 25))) {
            rows.add(measuredToMap(x));
        }
        out.put("resources", rows);
        return ok(out);
    }

    /**
     * Kaynak kırılımı: son ölçüm (LATEST) ya da bir eşik-ihlali anının donmuş delili (checkId ile).
     * Ayrıca hangi ihlal anlarının delili olduğu listelenir ki arayüz "o güne bak" diyebilsin.
     */
    @GetMapping("/pagespeed/{id}/resources")
    public ResponseEntity<Map<String, Object>> pageSpeedResources(@PathVariable Long id,
            @RequestParam(required = false) Long checkId,
            @RequestParam(defaultValue = "50") int limit, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        com.sitemonitor.model.PageSpeedMonitor mon = pageSpeedMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sayfa hızı izlemesi bulunamadı");
        var deny = denyIfNotViewable(session, mon.getTeamId());   // IDOR: başka takımın kırılımı okunamaz
        if (deny != null) return deny;
        int cap = Math.max(1, Math.min(limit, 500));
        List<com.sitemonitor.model.PageSpeedResource> rows = checkId != null
                ? pageSpeedResourceRepo.findByCheck(checkId, cap)
                : pageSpeedResourceRepo.findHeaviest(id, com.sitemonitor.model.PageSpeedResource.KEEP_LATEST, cap);
        // Başka bir izlemenin checkId'si ile veri sızmasın: dönen satırlar bu izlemeye ait olmalı.
        rows = rows.stream().filter(r -> id.equals(r.getMonitorId())).toList();
        // Kirpma GORUNUR olmali: 50 satir, 300 kaynakli bir sayfanin TAMAMI sanilirsa kullanici
        // agirligin nereden geldigini yanlis okur.
        long total = checkId != null
                ? pageSpeedResourceRepo.countByMonitorIdAndCheckId(id, checkId)
                : pageSpeedResourceRepo.countByMonitorIdAndKeepReason(id, com.sitemonitor.model.PageSpeedResource.KEEP_LATEST);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resources", rows.stream().map(this::resourceToMap).toList());
        out.put("total", total);
        out.put("breaches", pageSpeedResourceRepo.breachSnapshots(id, 20).stream()
                .map(a -> Map.of("check_id", a[0], "checked_at", a[1])).toList());
        return ok(out);
    }

    /**
     * Grafik serisi — {@code metric} ile hangi metriğin çizileceği seçilir.
     * Sunucu satırları TEK sorguda okur ve istenen kolonu projekte eder; metrik değiştirmek yeni bir
     * tablo taraması üretmez.
     */
    @GetMapping("/pagespeed/{id}/response-series")
    public ResponseEntity<Map<String, Object>> pageSpeedSeries(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "load") String metric, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        com.sitemonitor.model.PageSpeedMonitor mon = pageSpeedMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sayfa hızı izlemesi bulunamadı");
        var deny = denyIfNotViewable(session, mon.getTeamId());   // IDOR
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        int col = switch (metric == null ? "" : metric.toLowerCase(java.util.Locale.ROOT)) {
            case "ttfb" -> 2;
            case "size" -> 3;       // bayt → arayüz KB'ye çevirir
            case "requests" -> 4;
            default -> 1;           // load (toplam yükleme süresi)
        };
        // seriesRaw: [checkedAt, responseMs, ttfbMs, totalBytes, requestCount, ok]
        List<Object[]> raw = pageSpeedCheckRepo.seriesRaw(id, range[0], range[1], SERIES_RAW_CAP);
        List<Object[]> projected = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            // BAŞARISIZ ölçümün değeri seriye GİRMEZ (null geçilir, buildResponseSeries onu atlar).
            // Alınamayan bir sayfa 0 bayt / 0 istek olarak kaydediliyor; bunları çizmek grafiği
            // aşağı çekip "sayfa hafifledi" gibi okutur. Kesinti zaten AYRI kırmızı işaretle
            // gösteriliyor (üçüncü kolon ok bayrağı), yani bilgi kaybolmuyor — yalnız yanlış
            // yere, ortalamanın içine karışmıyor.
            boolean up = Boolean.TRUE.equals(r[5]);
            projected.add(new Object[]{ r[0], up ? r[col] : null, r[5] });
        }
        Map<String, Object> out = new LinkedHashMap<>(buildResponseSeries(projected, range[0], range[1], false));
        out.put("metric", metric);
        return ok(out);
    }

    /** Ortak: sayfa hızına özgü alanları clamp'li uygular. Şifreli alanlar write-only desende yazılır. */
    private void applyPageSpeedFields(com.sitemonitor.model.PageSpeedMonitor m, Map<String, Object> body, HttpSession session) {
        // Aralık tabanı SUNUCUDA da uygulanır: form atlanabilir, uç atlanamaz.
        if (body.get("intervalSeconds") instanceof Number n)
            m.setIntervalSeconds(com.sitemonitor.service.page.PageSpeedRules.clampInterval(n.intValue()));
        if (body.get("timeoutMs") instanceof Number n) m.setTimeoutMs(Math.max(1000, Math.min(120000, n.intValue())));

        // Eşikler: 4'ü de opsiyonel. Açıkça null gönderilirse eşik KALDIRILIR (kullanıcı vazgeçebilmeli).
        applyThreshold(body, "maxLoadMs",   m::setMaxLoadMs);
        applyThreshold(body, "maxTtfbMs",   m::setMaxTtfbMs);
        applyThreshold(body, "maxPageKb",   m::setMaxPageKb);
        applyThreshold(body, "maxRequests", m::setMaxRequests);

        if (body.containsKey("userAgent")) m.setUserAgent(blank(body.get("userAgent")) ? null : body.get("userAgent").toString().trim());
        if (body.get("sendDnt") instanceof Boolean b) m.setSendDnt(b);
        // Vekil kipi (2026-09-21): anahtar yoksa dokunma (mevcut kayıt korunur); null/boş/bilinmeyen → OFF (varsayılan doğrudan)
        if (body.containsKey("useProxy")) m.setUseProxy(com.sitemonitor.service.ProxyPolicyService.normalizeModeDefaultOff(body.get("useProxy")));
        if (body.get("excludeTrackers") instanceof Boolean b) m.setExcludeTrackers(b);
        if (body.containsKey("trackerPatterns")) m.setTrackerPatterns(blank(body.get("trackerPatterns")) ? null : body.get("trackerPatterns").toString());
        if (body.get("resourceConcurrency") instanceof Number n) m.setResourceConcurrency(Math.max(1, Math.min(20, n.intValue())));

        if (body.get("confirmAttempts") instanceof Number n)         m.setConfirmAttempts(clampAttempts(n.intValue()));
        if (body.get("confirmIntervalSeconds") instanceof Number n)  m.setConfirmIntervalSeconds(clampInterval(n.intValue()));
        if (body.get("recoveryChecks") instanceof Number n)          m.setRecoveryChecks(clampRecovery(n.intValue()));
        if (body.get("recoveryIntervalSeconds") instanceof Number n) m.setRecoveryIntervalSeconds(clampInterval(n.intValue()));
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
        if (body.get("notifyEmail") instanceof Boolean b) m.setNotifyEmail(b);
        if (body.get("notifyWebhook")  instanceof Boolean b) m.setNotifyWebhook(b);

        if (body.containsKey("basicAuthUser")) m.setBasicAuthUser(blank(body.get("basicAuthUser")) ? null : body.get("basicAuthUser").toString().trim());
        // Write-only sır deseni (scripted env emsali): alan hiç gelmediyse dokunma, BOŞ geldiyse mevcut
        // şifreli değeri KORU, dolu geldiyse şifreleyip değiştir. Aksi halde her form kaydı parolayı silerdi.
        if (body.containsKey("basicAuthPass") && !blank(body.get("basicAuthPass"))) {
            m.setBasicAuthPassEnc(secretCipher.encrypt(body.get("basicAuthPass").toString()));
        }
        // Kullanıcı adı temizlendiyse parola da anlamsız kalır — birlikte düşsünler.
        if (m.getBasicAuthUser() == null) m.setBasicAuthPassEnc(null);
        if (Boolean.TRUE.equals(body.get("clearBasicAuthPass"))) m.setBasicAuthPassEnc(null);

        // Özel başlıklar YALNIZ global admin: serbest başlık iç servislere yetki/SSRF yüzeyi açar.
        // Admin olmayan kullanıcının gönderdiği alan sessizce YOK SAYILIR (hata değil) — mevcut değer korunur,
        // böylece takım kullanıcısı formu kaydettiğinde admin'in koyduğu başlıklar silinmez.
        if (body.containsKey("customHeaders") && SessionScope.isGlobalAdmin(session)) {
            String raw = blank(body.get("customHeaders")) ? null : body.get("customHeaders").toString();
            m.setCustomHeadersEnc(raw == null ? null : secretCipher.encrypt(raw));
        }
    }

    /** Eşik alanı: sayı → uygula (negatif 0'a kırpılır), açık null → eşiği kaldır, yoksa dokunma. */
    private void applyThreshold(Map<String, Object> body, String key, java.util.function.Consumer<Integer> setter) {
        if (!body.containsKey(key)) return;
        Object v = body.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) { setter.accept(null); return; }
        if (v instanceof Number n) setter.accept(Math.max(0, n.intValue()));
    }

    private AlertEvent openPageSpeedAlarm(String url) {
        return alertEventRepo.findOpenAlert(url, EscalationService.TYPE_PAGESPEED_DOWN)
                .or(() -> alertEventRepo.findOpenAlert(url, EscalationService.TYPE_PAGESPEED_SLOW)).orElse(null);
    }

    private Map<String, Object> measuredToMap(PageSpeedCheckerService.Measured x) {
        Map<String, Object> im = new LinkedHashMap<>();
        im.put("url", x.url());
        im.put("type", PageSpeedCheckerService.normalizeType(x.type()));
        im.put("bytes", x.bytes());
        im.put("duration_ms", x.durationMs());
        im.put("http_status", x.statusCode());
        im.put("third_party", x.thirdParty());
        im.put("failed", x.failed());
        im.put("truncated", x.truncated());
        return im;
    }

    private Map<String, Object> resourceToMap(com.sitemonitor.model.PageSpeedResource r) {
        Map<String, Object> im = new LinkedHashMap<>();
        im.put("url", r.getUrl());
        im.put("type", r.getType());
        im.put("bytes", r.getBytes());
        im.put("duration_ms", r.getDurationMs());
        im.put("http_status", r.getStatusCode());
        im.put("third_party", r.getThirdParty());
        im.put("truncated", Boolean.TRUE.equals(r.getTruncated()));
        im.put("checked_at", r.getCheckedAt());
        return im;
    }

    private Map<String, Object> enrichPageSpeed(com.sitemonitor.model.PageSpeedMonitor m,
                                                com.sitemonitor.model.PageSpeedCheck latest,
                                                Map<Long, String> teams, AlertEvent openAlarm, boolean admin) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",                   m.getId());
        item.put("name",                 m.getName());
        item.put("url",                  m.getUrl());
        item.put("group_name",           m.getGroupName());
        item.put("team_id",              m.getTeamId());
        item.put("notification_group_id",              m.getNotificationGroupId());
        item.put("team_name",            m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",               m.getActive());
        item.put("interval_seconds",     m.getIntervalSeconds());
        item.put("timeout_ms",           m.getTimeoutMs());
        item.put("max_load_ms",          m.getMaxLoadMs());
        item.put("max_ttfb_ms",          m.getMaxTtfbMs());
        item.put("max_page_kb",          m.getMaxPageKb());
        item.put("max_requests",         m.getMaxRequests());
        item.put("user_agent",           m.getUserAgent());
        item.put("send_dnt",             m.getSendDnt());
        item.put("exclude_trackers",     m.getExcludeTrackers());
        item.put("tracker_patterns",     m.getTrackerPatterns());
        item.put("resource_concurrency", m.getResourceConcurrency());
        putPageSpeedProxyFields(item, m);
        item.put("basic_auth_user",      m.getBasicAuthUser());
        // Parola ASLA (şifreli hâli bile) dönmez — arayüz yalnız "kayıtlı mı" bilgisine ihtiyaç duyar.
        item.put("has_basic_auth_pass",  m.getBasicAuthPassEnc() != null && !m.getBasicAuthPassEnc().isBlank());
        item.put("has_custom_headers",   m.getCustomHeadersEnc() != null && !m.getCustomHeadersEnc().isBlank());
        // Başlık ADLARI yalnız admin'e ve yalnız AD olarak — değerler jeton taşıyabilir.
        item.put("custom_header_names",  admin ? customHeaderNames(m) : List.of());
        item.put("confirm_attempts",         m.getConfirmAttempts());
        item.put("confirm_interval_seconds", m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("tags",                      m.getTags());
        item.put("alert_level",     com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
        item.put("notify_email",              m.getNotifyEmail());
        item.put("notify_webhook",              m.getNotifyWebhook());
        item.put("created_at",                m.getCreatedAt());
        item.put("created_by_name",           m.getCreatedByName());
        item.put("active_alarm",       openAlarm != null);
        item.put("alarm_level",        openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
        if (latest != null) {
            item.put("status",         Boolean.FALSE.equals(latest.getOk()) ? "DOWN"
                                       : (blank(latest.getBreachedMetrics()) ? "OK" : "SLOW"));
            item.put("ok",             latest.getOk());
            item.put("http_status",    latest.getStatusCode());
            item.put("ttfb_ms",        latest.getTtfbMs());
            item.put("html_ms",        latest.getHtmlMs());
            item.put("response_ms",    latest.getResponseMs());
            item.put("total_bytes",    latest.getTotalBytes());
            item.put("request_count",  latest.getRequestCount());
            item.put("failed_count",   latest.getFailedCount());
            item.put("capped",         latest.getCapped());
            item.put("bytes_truncated", Boolean.TRUE.equals(latest.getBytesTruncated()));
            item.put("breached_metrics", blank(latest.getBreachedMetrics()) ? List.of()
                                       : List.of(latest.getBreachedMetrics().split(",")));
            item.put("error",          latest.getErrorMessage());
            item.put("last_check",     latest.getCheckedAt());
        }
        return item;
    }

    /** Şifreli başlık bloğundan yalnız AD listesi (değer yok). Çözülemezse boş liste. */
    private List<String> customHeaderNames(com.sitemonitor.model.PageSpeedMonitor m) {
        if (m.getCustomHeadersEnc() == null || m.getCustomHeadersEnc().isBlank()) return List.of();
        try {
            return List.copyOf(com.sitemonitor.service.page.PageSpeedRules
                    .parseHeaders(secretCipher.decrypt(m.getCustomHeadersEnc())).keySet());
        } catch (Exception e) {
            return List.of();
        }
    }
    /** Ortak: sayfa-özel alanları (mode, crawl derinlik/limit, exclude, slow, alertThirdParty, concurrency,
     *  tags, notifyEmail) body'den clamp'li uygular. */
    private void applyPageFeatureFields(com.sitemonitor.model.PageMonitor m, Map<String, Object> body) {
        if (!blank(body.get("mode"))) {
            String mode = body.get("mode").toString().trim().toUpperCase();
            m.setMode("SITE_CRAWL".equals(mode) ? "SITE_CRAWL" : "SINGLE_PAGE");
        }
        if (body.get("crawlDepth")    instanceof Number n) m.setCrawlDepth(Math.max(0, Math.min(5, n.intValue())));
        if (body.get("crawlMaxPages") instanceof Number n) m.setCrawlMaxPages(Math.max(1, Math.min(500, n.intValue())));
        if (body.containsKey("excludePatterns")) m.setExcludePatterns(blank(body.get("excludePatterns")) ? null : body.get("excludePatterns").toString());
        if (body.containsKey("useProxy")) m.setUseProxy(com.sitemonitor.service.ProxyPolicyService.normalizeMode(body.get("useProxy")));
        if (body.get("slowResourceMs") instanceof Number n) m.setSlowResourceMs(Math.max(100, n.intValue()));
        if (body.get("alertThirdParty") instanceof Boolean b) m.setAlertThirdParty(b);
        if (body.get("alertMixedContent") instanceof Boolean b) m.setAlertMixedContent(b);
        if (body.get("alertTimeout") instanceof Boolean b) m.setAlertTimeout(b);
        if (body.get("resourceConcurrency") instanceof Number n) m.setResourceConcurrency(Math.max(1, Math.min(20, n.intValue())));
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
        if (body.get("notifyEmail") instanceof Boolean b) m.setNotifyEmail(b);
        if (body.get("notifyWebhook")  instanceof Boolean b) m.setNotifyWebhook(b);
    }

    private Map<String, Object> enrichPage(com.sitemonitor.model.PageMonitor m, com.sitemonitor.model.PageCheck latest,
                                           Map<Long, String> teams, AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",                   m.getId());
        item.put("name",                 m.getName());
        item.put("url",                  m.getUrl());
        item.put("mode",                 m.getMode());
        item.put("crawl_depth",          m.getCrawlDepth());
        item.put("crawl_max_pages",      m.getCrawlMaxPages());
        item.put("exclude_patterns",     m.getExcludePatterns());
        putProxyFields(item, m.getUrl(), m.getUseProxy());
        item.put("slow_resource_ms",     m.getSlowResourceMs());
        item.put("alert_third_party",    m.getAlertThirdParty());
        item.put("alert_mixed_content",  m.getAlertMixedContent());
        item.put("alert_timeout",        m.getAlertTimeout());
        item.put("resource_concurrency", m.getResourceConcurrency());
        item.put("group_name",           m.getGroupName());
        item.put("team_id",              m.getTeamId());
        item.put("notification_group_id",              m.getNotificationGroupId());
        item.put("team_name",            m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",               m.getActive());
        item.put("interval_seconds",     m.getIntervalSeconds());
        item.put("timeout_ms",           m.getTimeoutMs());
        item.put("confirm_attempts",         m.getConfirmAttempts());
        item.put("confirm_interval_seconds", m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("tags",                      m.getTags());
        item.put("alert_level",     com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
        item.put("notify_email",              m.getNotifyEmail());
        item.put("notify_webhook",              m.getNotifyWebhook());
        item.put("active_alarm",       openAlarm != null);
        item.put("alarm_level",        openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
        if (latest != null) {
            item.put("status",              latest.getStatus());   // OK | DEGRADED | DOWN
            item.put("ok",                  latest.getOk());
            item.put("http_status",         latest.getHttpStatus());
            item.put("response_ms",         latest.getResponseMs());
            item.put("total_resources",     latest.getTotalResources());
            item.put("broken_resources",    latest.getBrokenResources());
            item.put("timeout_count",       latest.getTimeoutCount());   // null = 2026-08-04 öncesi kayıt (kırığa dahildi)
            item.put("mixed_content_count", latest.getMixedContentCount());
            item.put("pages_crawled",       latest.getPagesCrawled());
            item.put("error",               latest.getError());
            item.put("checked_at",          latest.getCheckedAt());
        } else {
            item.put("status", "unknown");
            item.put("ok", null); item.put("http_status", null); item.put("response_ms", null);
            item.put("total_resources", null); item.put("broken_resources", null);
            item.put("timeout_count", null);
            item.put("mixed_content_count", null); item.put("pages_crawled", null);
            item.put("error", null); item.put("checked_at", null);
        }
        return item;
    }

    // ── Senaryo İzleme (Scripted Check / k6) — 10. tür (serbest-form) ──────────
    private static final com.fasterxml.jackson.databind.ObjectMapper SCRIPTED_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    @GetMapping("/scripted")
    public ResponseEntity<Map<String, Object>> listScripted(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, com.sitemonitor.model.ScriptedCheck> latest = scriptedCheckRepo.findLatestPerMonitor().stream()
                .filter(c -> c.getMonitorId() != null)
                .collect(Collectors.toMap(com.sitemonitor.model.ScriptedCheck::getMonitorId, c -> c, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        List<com.sitemonitor.model.ScriptedMonitor> monitors = scriptedMonitorRepo.findAllByOrderByNameAsc().stream()
                .filter(m -> SessionScope.canView(session, m.getTeamId())).toList();
        Set<String> names = monitors.stream().map(com.sitemonitor.model.ScriptedMonitor::getName).collect(Collectors.toSet());
        Map<String, AlertEvent> open = openAlarmsByDomain(names, EscalationService.TYPE_SCRIPTED_FAIL);
        // Yavas kosum alarmi kartta da gorunsun; FAIL (kesinti) varsa O oncelikli — iki rozet
        // yerine tek ve en severe olani gosterilir (DNS rozetindeki karar ile ayni).
        Map<String, AlertEvent> slowOpen = openAlarmsByDomain(names, EscalationService.TYPE_SCRIPTED_SLOW);
        // "Hiç başarılı olmamış" monitörler — tek toplu sorgu (monitör başına sorgu YOK).
        // Bu ayrım arıza ile yapılandırma kusurunu ayırır: 288 koşumun 288'i düşen bir monitör
        // "bir şey bozuldu" değil "hiç çalışmadı" demektir (2026-08 saha vakası).
        Set<Long> everPassed = new java.util.HashSet<>(scriptedCheckRepo.monitorIdsWithSuccess());
        List<Map<String, Object>> result = monitors.stream()
                .map(m -> {
                    AlertEvent al = open.getOrDefault(m.getName(), slowOpen.get(m.getName()));
                    Map<String, Object> item = enrichScripted(m, latest.get(m.getId()), teams, al);
                    // Hiç koşmamış monitör "hiç başarılı olmamış" SAYILMAZ — henüz denenmedi.
                    item.put("never_succeeded", latest.get(m.getId()) != null && !everPassed.contains(m.getId()));
                    return item;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monitors", result);
        out.put("k6_available", scriptedChecker.isAvailable());
        out.put("k6_version", scriptedChecker.version());
        // Vekilin ETKİN durumu — "AUTO seçtim, demek ki vekilden geçiyor" varsayımı yanlış olabiliyor:
        // Go, NO_PROXY listesindeki her girdiyi SONEK olarak uygular (`akbank.com` ⇒ tüm alt alanlar),
        // yani eşleşen hedefler AUTO'da bile doğrudan çıkar. Bu iki alan, formda kararın gerçekte ne
        // olacağını gösterebilmek için var (2026-08: dört sürüm boyunca yanlış teşhise sebep oldu).
        out.put("proxy_configured", proxySettings.enabled());
        out.put("no_proxy", proxySettings.noProxyList());
        boolean canManage = permissionService.allows((String) session.getAttribute("systemRole"), "monitoring.scripted", "edit");
        out.put("can_manage", canManage);
        return ok(out);
    }

    @PostMapping("/scripted")
    public ResponseEntity<Map<String, Object>> createScripted(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.scripted", "edit");   // ADMIN + TEAM_ADMIN (PO) — k6 = keyfi kod
        { var _gt = requireGroupAndTags(body, true); if (_gt != null) return _gt; }   // grup + etiket zorunlu (2026-09-18)
        if (blank(body.get("name"))) return badRequest("ad zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        if (blank(body.get("groupName"))) return badRequest("Grup seçimi zorunludur; izleme oluşturulamıyor.");
        String name = body.get("name").toString().trim();
        if (scriptedMonitorRepo.existsDuplicate(name, teamId, null))
            return badRequest("Bu ad bu takımda zaten kullanılıyor; mükerrer izleme oluşturulamaz.");
        String scanErr = scanScriptOrError(body.get("script"));
        if (scanErr != null) return badRequest(scanErr);
        var diag = validateScripted(body.get("script"), body.get("env"), body.get("timeoutSeconds"));
        if (diag.blocked()) return badRequest(diag.blocking());
        String now = ISO.format(Instant.now());
        com.sitemonitor.model.ScriptedMonitor m = new com.sitemonitor.model.ScriptedMonitor();
        m.setName(name);
        m.setTeamId(teamId);
        m.setActive(true);                                            // varsayılan: yeni izleme aktif
        if (body.get("active") instanceof Boolean b) m.setActive(b);  // Kopyala: pasif kaynağın kopyası da pasif doğsun
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
        if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
        if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
        if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
        applyScriptedFields(m, body, null);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        monitorHistory.stampCreated(m, session);
        com.sitemonitor.model.ScriptedMonitor saved = scriptedMonitorRepo.save(m);
        // İlk sürüm: 1.0.0 (seq 0). Etiket monitör satırına da yazılır ki liste/rozet sürüm
        // tablosunu sorgulamak zorunda kalmasın.
        saved.setScriptVersion(writeScriptVersion(saved, "CREATE", null, str(body.get("versionNote")), session));
        saved = scriptedMonitorRepo.save(saved);
        clearDraft(session, String.valueOf(saved.getId()));
        clearDraft(session, "new");                 // "yeni monitör" taslağı artık gerçek kayda dönüştü
        activityLog.recordLifecycle(ActivityLogService.SCRIPTED, saved.getId(), saved.getName(), saved.getName(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "SCRIPTED_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                AuditDiff.diff(null, AuditDiff.snapshot(saved, SCRIPTED_FIELDS)));
        monitorHistory.record(MonitorHistoryService.SCRIPTED, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, SCRIPTED_FIELDS), changeNote(body), session);
        // Uyarılar YANITTA taşınır, istekte değil: kaydetme payload'ının şekli değişmez
        // (frontend testi create payload'ını tam eşitlikle pinliyor).
        Map<String, Object> out = new LinkedHashMap<>(enrichScripted(saved, null, teamNameMap(), null));
        if (!diag.warnings().isEmpty()) out.put("warnings", diag.warnings());
        return ok(out);
    }

    @PutMapping("/scripted/{id}")
    public ResponseEntity<Map<String, Object>> updateScripted(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.scripted", "edit");
        { var _gt = requireGroupAndTags(body, false); if (_gt != null) return _gt; }   // gönderilip boş bırakılmışsa 400 (2026-09-18)
        java.util.Map<String, Object> _before = scriptedMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, SCRIPTED_FIELDS)).orElse(null);
        String scanErr = scanScriptOrError(body.get("script"));
        if (scanErr != null) return badRequest(scanErr);
        // Uyarılar CREATE'te olduğu gibi UPDATE yanıtında da taşınmalı: kullanıcı script'ini
        // düzenlerken (asıl düzeltme anı) "__ENV tanımsız", "doğrulama atlandı" gibi uyarıları
        // görmezse o uyarılar pratikte hiç görünmez.
        var diag = body.get("script") != null
                ? validateScripted(body.get("script"), body.get("env"), body.get("timeoutSeconds"))
                : new com.sitemonitor.service.ScriptedCheckerService.ScriptDiagnostics(null, java.util.List.of());
        if (diag.blocked()) return badRequest(diag.blocking());
        return scriptedMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.containsKey("groupName") && blank(body.get("groupName"))) return badRequest("Grup seçimi zorunludur.");
            String oldName = m.getName();
            if (body.get("name")   != null) m.setName(body.get("name").toString().trim());
            // Rename: scripted alarm anahtarı monitör ADI (diğer türlerde gerçek hedef URL/host) — ad
            // değişirse açık SCRIPTED_FAIL alarmının bağı kopar (delete akışı 'resolveOpenAlertsSilently'
            // ile telafi ediyor ama rename etmiyordu). Açık alarmı yeni ada taşı.
            if (oldName != null && !oldName.equals(m.getName())) {
                for (String t : List.of(EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW)) {
                    alertEventRepo.findOpenAlert(oldName, t).ifPresent(a -> {
                        a.setDomain(m.getName());
                        alertEventRepo.save(a);
                    });
                }
            }
            if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId"))    m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
            if (body.get("active") instanceof Boolean b) {
                // Kullanıcı izlemeyi YENİDEN AÇIYORSA anomali kapatmasının sebebi düşer: uyarı,
                // düzeltilmiş bir izlemenin üstünde sonsuza kadar asılı kalmamalı. Kapatma kararı
                // aktivite akışında ve denetim kaydında zaten kalıcıdır.
                if (b && Boolean.FALSE.equals(m.getActive()) && m.getDisabledReason() != null) {
                    log.info("Anomali ile kapatılan izleme kullanıcı tarafından yeniden açıldı: {} ({})",
                            m.getName(), actor(session));
                    m.setDisabledReason(null);
                    m.setDisabledAt(null);
                }
                closeAlertsOnPause(m.getActive(), body.get("active"), m.getName(), Set.of(EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW));
                m.setActive(b);
            }
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            String oldScript = m.getScript();
            String oldEnv = m.getEnvJson();
            applyScriptedFields(m, body, m.getEnvJson());
            m.setUpdatedAt(ISO.format(Instant.now()));
            monitorHistory.stampUpdated(m, session);
            com.sitemonitor.model.ScriptedMonitor saved = scriptedMonitorRepo.save(m);
            // Sürüm YALNIZ içerik (script/env) değişince yazılır: aralık/timeout gibi ayar
            // düzenlemeleri sürüm geçmişini gereksiz satırlarla şişirmemeli.
            if (contentChanged(oldScript, oldEnv, saved)) {
                String ev = blank(body.get("restoredFrom")) ? "EDIT" : "RESTORE";
                String note = blank(body.get("restoredFrom")) ? str(body.get("versionNote"))
                        : str(body.get("restoredFrom")) + " sürümünden geri yüklendi";
                saved.setScriptVersion(writeScriptVersion(saved, ev, str(body.get("bumpType")), note, session));
                saved = scriptedMonitorRepo.save(saved);
            }
            clearDraft(session, String.valueOf(saved.getId()));
            auditService.recordAction("MONITOR_UPDATE", session, "SCRIPTED_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, SCRIPTED_FIELDS)));
            // Script SÜRÜM geçmişi (scripted_script_versions) ile bu AYRI şeylerdir: orası script
            // gövdesinin sürümlerini, burası yapılandırmanın (aralık/eşik/takım/aktiflik) geçmişini
            // tutar. İkisi de aynı monitörde ama farklı sorulara cevap verir.
            var changeRow = monitorHistory.record(MonitorHistoryService.SCRIPTED, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, SCRIPTED_FIELDS), changeNote(body), session);
            noteConfigChanged(changeRow, ActivityLogService.SCRIPTED, saved.getName(), session);
            Map<String, Object> out = new LinkedHashMap<>(enrichScripted(saved,
                    scriptedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getName(), EscalationService.TYPE_SCRIPTED_FAIL).orElse(null)));
            if (!diag.warnings().isEmpty()) out.put("warnings", diag.warnings());
            return ok(out);
        }).orElse(notFound("Sentetik izleme bulunamadı"));
    }

    // ── Sürüm geçmişi ────────────────────────────────────────────────────────

    /** Sürüm listesi — script GÖVDESİ hariç (yüzlerce sürümde yanıt şişmesin); önizleme ayrı uçtan. */
    @GetMapping("/scripted/{id}/versions")
    public ResponseEntity<Map<String, Object>> scriptedVersions(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        var mon = scriptedMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sentetik izleme bulunamadı");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        // Sürüm başına koşum karnesi TEK sorguyla, döngüden ÖNCE: satır başına sayım N+1 olurdu.
        Map<String, long[]> stats = versionRunStats(scriptedCheckRepo.runStatsByScriptVersion(id));
        List<Map<String, Object>> rows = scriptedVersionRepo.findByMonitorIdOrderBySequenceNoDesc(id).stream()
                .map(v -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", v.getId());
                    r.put("version", v.getVersion());
                    r.put("sequence_no", v.getSequenceNo());
                    r.put("event_type", v.getEventType());
                    r.put("note", v.getNote());
                    r.put("created_at", v.getCreatedAt());
                    r.put("created_by", v.getCreatedBy());
                    r.put("script_chars", v.getScript() == null ? 0 : v.getScript().length());
                    r.put("current", v.getVersion() != null && v.getVersion().equals(mon.getScriptVersion()));
                    // "Bu sürüm sahada ne yaptı?" — bozuk sürüm listede kırmızı görünsün.
                    long[] st = stats.get(v.getVersion());
                    r.put("run_count",  st == null ? 0L : st[0]);
                    r.put("fail_count", st == null ? 0L : st[1]);
                    return r;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", rows);
        out.put("current_version", mon.getScriptVersion());
        return ok(out);
    }

    /**
     * {@code [sürüm, koşum, hata]} satırlarını sürüm→sayaç haritasına çevirir.
     *
     * <p>Saf/statik: Spring context'i olmadan test edilir. Sürüm dizeleri monitör başına
     * benzersizdir ({@code nextVersion} kesin artan), o yüzden dizeyle anahtarlamak güvenli.
     * Null sürüm atılır (sürümleme öncesi kayıtlar hiçbir sürümün karnesine yazılmamalı).
     */
    static Map<String, long[]> versionRunStats(List<Object[]> rows) {
        Map<String, long[]> out = new LinkedHashMap<>();
        if (rows == null) return out;
        for (Object[] r : rows) {
            if (r == null || r.length < 3 || r[0] == null) continue;
            long total = r[1] instanceof Number n ? n.longValue() : 0L;
            long fail  = r[2] instanceof Number n ? n.longValue() : 0L;
            out.put(String.valueOf(r[0]), new long[]{ total, fail });
        }
        return out;
    }

    /** Tek sürümün gövdesi — önizleme ve "editöre yükle" için. */
    @GetMapping("/scripted/{id}/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> scriptedVersionDetail(@PathVariable Long id, @PathVariable Long versionId,
                                                                     HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        var mon = scriptedMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sentetik izleme bulunamadı");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        var v = scriptedVersionRepo.findById(versionId).orElse(null);
        // Başka monitörün sürüm id'siyle içerik çekilememeli (yetki sınırı monitör üzerinden kuruluyor).
        if (v == null || !id.equals(v.getMonitorId())) return notFound("Sürüm bulunamadı");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", v.getId());
        out.put("version", v.getVersion());
        out.put("event_type", v.getEventType());
        out.put("note", v.getNote());
        out.put("created_at", v.getCreatedAt());
        out.put("created_by", v.getCreatedBy());
        out.put("script", v.getScript());
        out.put("env", envForClient(v.getEnvJson()));   // secret değerler düz metin DÖNMEZ
        return ok(out);
    }

    // ── Otomatik taslak ──────────────────────────────────────────────────────

    /**
     * Taslak upsert — otomatik kaydetme buraya gelir.
     *
     * <p>DİKKAT: {@code PUT /scripted/{id}} KULLANILAMAZ; o uç her çağrıda {@code validateScripted}
     * ile bir k6 alt süreci başlatıyor. 30 saniyede bir otomatik kayıt bunu tetikleseydi k6 havuzu
     * (pool-size 2) tükenir ve gerçek izleme koşumları sıraya girerdi. Bu uç DOĞRULAMA YAPMAZ:
     * taslak yarım/bozuk script içerebilir, amaç yalnız kaybolmamasıdır.
     */
    // POST da kabul edilir: sekme kapanırken `navigator.sendBeacon` YALNIZ POST atabiliyor ve
    // beacon, fetch'in iptal edildiği o anda taslağı kurtaran son şans.
    @RequestMapping(value = "/scripted/draft", method = { RequestMethod.PUT, RequestMethod.POST })
    public ResponseEntity<Map<String, Object>> saveScriptedDraft(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.scripted", "edit");
        String owner = actor(session);
        String key = draftKey(body.get("monitorKey"));
        var draft = scriptedDraftRepo.findByOwnerAndMonitorKey(owner, key)
                .orElseGet(com.sitemonitor.model.ScriptedDraft::new);
        draft.setOwner(owner);
        draft.setMonitorKey(key);
        draft.setMonitorId("new".equals(key) ? null : Long.valueOf(key));
        draft.setMonitorName(str(body.get("monitorName")));
        draft.setFormJson(str(body.get("formJson")));
        draft.setUpdatedAt(ISO.format(Instant.now()));
        scriptedDraftRepo.save(draft);
        return ok(Map.of("monitor_key", key, "updated_at", draft.getUpdatedAt()));
    }

    /** Kullanıcının KENDİ taslakları — "devam et" şeridi ve düzenleme modalı bunu okur. */
    @GetMapping("/scripted/drafts")
    public ResponseEntity<Map<String, Object>> myScriptedDrafts(HttpSession session) {
        permissionService.require(session, "monitoring.scripted", "edit");
        List<Map<String, Object>> rows = scriptedDraftRepo.findByOwnerOrderByUpdatedAtDesc(actor(session)).stream()
                .map(d -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("monitor_key", d.getMonitorKey());
                    r.put("monitor_id", d.getMonitorId());
                    r.put("monitor_name", d.getMonitorName());
                    r.put("form_json", d.getFormJson());
                    r.put("updated_at", d.getUpdatedAt());
                    return r;
                }).toList();
        return ok(Map.of("drafts", rows));
    }

    /**
     * Kullanıcının açıkça istediği silme — sessiz DEĞİL.
     *
     * <p>Silinemezse 500 döner ki arayüz "taslak silindi" deyip uyarıyı geri getirmesin
     * (yaşanan hata: türetilmiş silme sorgusu tx'siz düşüyordu, {@link #clearDraft} yutuyordu,
     * kullanıcı her açılışta aynı taslak uyarısını görüyordu).
     */
    @DeleteMapping("/scripted/draft/{monitorKey}")
    public ResponseEntity<Map<String, Object>> deleteScriptedDraft(@PathVariable String monitorKey, HttpSession session) {
        permissionService.require(session, "monitoring.scripted", "edit");
        // Kalıcı silme; taslak k6 script gövdesi taşıyabilir. Gövde denetime YAZILMAZ (sırlar
        // env'de olabilir), ne silindiği yazılır.
        auditService.recordAction("SCRIPTED_DRAFT_DELETE", session, "SCRIPTED_MONITOR",
                draftKey(monitorKey), AuditDetail.of("monitor_key", draftKey(monitorKey)), null);
        scriptedDraftRepo.deleteByOwnerAndMonitorKey(actor(session), draftKey(monitorKey));
        return ok(Map.of("deleted", true));
    }

    /** Gövdeden metin okuma — null/boş güvenli (JSON alanları Object olarak geliyor). */
    private static String str(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** {@code "new"} ya da sayısal monitör id'si; başka her şey {@code "new"}e düşer (yol parametresi güvenliği). */
    private static String draftKey(Object raw) {
        String s = raw == null ? "" : raw.toString().trim();
        return s.matches("\\d+") ? s : "new";
    }

    /**
     * Kayıt BAŞARILI olduktan sonra taslağı düşürür — sessiz, çünkü asıl işlem (monitör kaydı)
     * bitti ve onu geri almak taslaktan daha kötü. Kullanıcının açıkça bastığı silme bu yoldan
     * GEÇMEZ ({@link #deleteScriptedDraft} hatayı yüzeye çıkarır).
     *
     * <p>Log seviyesi WARN: buranın düşmesi artık bir arıza belirtisidir. DEBUG'ta iken tam da bu
     * gizlendi — silme tx'siz koştuğu için her seferinde patlıyordu ve kimse görmüyordu.
     */
    private void clearDraft(HttpSession session, String monitorKey) {
        try {
            scriptedDraftRepo.deleteByOwnerAndMonitorKey(actor(session), monitorKey);
        } catch (Exception e) {
            log.warn("Taslak silinemedi ({}): {}", monitorKey, e.toString());
        }
    }

    @DeleteMapping("/scripted/{id}")
    public ResponseEntity<Map<String, Object>> deleteScripted(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.scripted", "edit");
        // HARD delete: satır silindikten SONRA snapshot alınamaz, şimdi al.
        Map<String, Object> _before = scriptedMonitorRepo.findById(id)
                .map(x -> AuditDiff.snapshot(x, SCRIPTED_FIELDS)).orElse(null);
        return scriptedMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            escalationService.resolveOpenAlertsSilently(m.getName(),
                    Set.of(EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW),
                    "Sistem (izleme silindi)");
            scriptedMonitorRepo.delete(m);
            // Taslaklar monitörle birlikte düşer (öksüz taslak "devam et" şeridinde hayalet üretirdi).
            // Sürüm geçmişi BİLİNÇLİ olarak silinmez: silinen bir monitörün script'i denetim değeri
            // taşır; öksüz satırlar retention kuralıyla temizlenir.
            try { scriptedDraftRepo.deleteByMonitorId(m.getId()); }
            catch (Exception e) { log.warn("Monitör taslakları silinemedi: {}", e.toString()); }
            activityLog.recordLifecycle(ActivityLogService.SCRIPTED, m.getId(), m.getName(), m.getName(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "SCRIPTED_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            // HARD delete: satır gitti ama geçmişi KALIR — silinen bir izlemenin neye benzediği
            // denetim değeri taşır (sürüm geçmişindeki aynı karar). Öksüz satırlar retention'la gider.
            monitorHistory.record(MonitorHistoryService.SCRIPTED, m.getId(), m.getName(), m.getTeamId(),
                    MonitorHistoryService.DELETE, _before, null, null, session);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Sentetik izleme bulunamadı"));
    }

    @GetMapping("/scripted/{id}/history")
    public ResponseEntity<?> scriptedHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        // İzin denetimi varlık denetiminden ÖNCE (scriptedResponseSeries ile aynı sıra): tersi
        // yapılınca yetkisiz çağıran var olan id'ye 403, olmayana 404 alıp id sayımı yapabiliyordu.
        permissionService.require(session, "monitoring.read", "view");
        com.sitemonitor.model.ScriptedMonitor mon = scriptedMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sentetik izleme bulunamadı");
        var src = new CheckHistoryService.Source<com.sitemonitor.model.ScriptedCheck>() {
            public org.springframework.data.domain.Page<com.sitemonitor.model.ScriptedCheck> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? scriptedCheckRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t, p)
                            : scriptedCheckRepo.findByMonitorIdAndCheckedAtBetween(id, f, t, p);
            }
            public long total(String f, String t) { return scriptedCheckRepo.countByMonitorIdAndCheckedAtBetween(id, f, t); }
            public long fail(String f, String t) { return scriptedCheckRepo.countByMonitorIdAndOkFalseAndCheckedAtBetween(id, f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return scriptedCheckRepo.historyHistogram(id, f, t, len); }
            public List<Object[]> bounds() { return scriptedCheckRepo.historyBounds(id); }
        };
        return runHistory(session, mon.getTeamId(), src, "scripted",
                mon.getName(), Set.of(EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW),
                from, to, days, status, page, size, format, "scripted-history-" + id, List.of(
                new CsvColumn<>("checked_at", com.sitemonitor.model.ScriptedCheck::getCheckedAt),
                new CsvColumn<>("ok", com.sitemonitor.model.ScriptedCheck::getOk),
                new CsvColumn<>("status", com.sitemonitor.model.ScriptedCheck::getStatus),
                new CsvColumn<>("duration_ms", com.sitemonitor.model.ScriptedCheck::getDurationMs),
                new CsvColumn<>("checks_passed", com.sitemonitor.model.ScriptedCheck::getChecksPassed),
                new CsvColumn<>("checks_failed", com.sitemonitor.model.ScriptedCheck::getChecksFailed),
                // Son üç sürümde eklenen sinyaller CSV'ye girmiyordu; dışa aktarım tabloda duran
                // veriyi taşımalı — dışarıda analiz eden kullanıcı faz kırılımını ve hangi script
                // sürümüyle koşulduğunu göremiyordu (PAGE aynı sürümde alan başına kolon almıştı).
                new CsvColumn<>("exit_code", com.sitemonitor.model.ScriptedCheck::getExitCode),
                new CsvColumn<>("script_version", com.sitemonitor.model.ScriptedCheck::getScriptVersion),
                new CsvColumn<>("via_proxy", com.sitemonitor.model.ScriptedCheck::getViaProxy),
                new CsvColumn<>("req_blocked_ms", com.sitemonitor.model.ScriptedCheck::getReqBlockedMs),
                new CsvColumn<>("req_connecting_ms", com.sitemonitor.model.ScriptedCheck::getReqConnectingMs),
                new CsvColumn<>("req_tls_ms", com.sitemonitor.model.ScriptedCheck::getReqTlsMs),
                new CsvColumn<>("req_waiting_ms", com.sitemonitor.model.ScriptedCheck::getReqWaitingMs),
                new CsvColumn<>("data_sent", com.sitemonitor.model.ScriptedCheck::getDataSent),
                new CsvColumn<>("data_received", com.sitemonitor.model.ScriptedCheck::getDataReceived),
                new CsvColumn<>("error", com.sitemonitor.model.ScriptedCheck::getError)), response);
    }

    @PostMapping("/scripted/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerScripted(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.scripted", "execute");
        return scriptedMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            long nowMs = System.currentTimeMillis();
            long cooldownMs = appSettings.getInt("site.monitor.scripted.manual-cooldown-seconds", 20) * 1000L;
            Long prev = scriptedManualTriggerAt.get(id);
            if (prev != null && nowMs - prev < cooldownMs) {
                return ResponseEntity.status(429).body(Map.<String, Object>of("success", false,
                        "error", "Bu monitör için çok sık manuel çalıştırma; " + (cooldownMs / 1000) + " sn bekleyin."));
            }
            scriptedManualTriggerAt.put(id, nowMs);
            auditService.recordAction("MONITOR_TRIGGER", session, "SCRIPTED_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            // Değerlendirme (doğrulama denemeleri → alarm) koşumun KENDİ içinde yapılır
            // (triggerScriptedCheckAsync): ayrı bir evaluate* çağrısı ikinci bir k6 koşumu
            // başlatıyor ve iki permit yiyordu.

            // Kontrol istek thread'inin DIŞINDA koşar; burada yalnız SINIRLI süre beklenir.
            // Senaryo kontrolü script timeout'u kadar sürebiliyor (tavan 180 sn) ve senkron
            // beklemek ters-vekilin 504'üne yakalanıyordu: kullanıcı hata görüyor, oysa kontrol
            // koşup kaydediliyor. Hızlı script'lerde (çoğunluk) davranış aynı kalsın diye tamamen
            // asenkron da yapılmadı — kısa bekleme sonucu yine anında döndürür.
            var fut = schedulerService.triggerScriptedCheckAsync(m);
            int waitSecs = Math.max(1, appSettings.getInt("site.monitor.scripted.manual-wait-seconds", 25));
            boolean queued = false;
            String skippedReason = null;
            try {
                Map<String, Object> r = fut.get(waitSecs, java.util.concurrent.TimeUnit.SECONDS);
                // Kontrol YÜRÜTÜLEMEDİYSE kayıt yazılmaz; sebebi söylemezsek kullanıcı ekranda ESKİ
                // sonucu görür ve "çalıştır"a bastığını sanır. Hata değil bilgi: hedefte sorun yok.
                if (r != null && Boolean.TRUE.equals(r.get("skipped")))
                    skippedReason = String.valueOf(r.get("error"));
            } catch (java.util.concurrent.TimeoutException te) {
                queued = true;              // koşum sürüyor; iptal ETME — arka planda tamamlansın
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                queued = true;
            } catch (java.util.concurrent.ExecutionException ee) {
                log.warn("Manuel senaryo kontrolü hata verdi (id={}): {}", id, String.valueOf(ee.getCause()));
            }

            Map<String, Object> out = new LinkedHashMap<>(enrichScripted(m,
                    scriptedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(m.getName(), EscalationService.TYPE_SCRIPTED_FAIL).orElse(null)));
            if (queued) out.put("queued", true);
            if (skippedReason != null) { out.put("skipped", true); out.put("skipped_reason", skippedReason); }
            return ok(out);
        }).orElse(notFound("Sentetik izleme bulunamadı"));
    }

    /** Ad-hoc test — kaydetmeden, formdaki script + env ile tek çalıştırma; sonucu + (maskeli) çıktıyı döndürür. */
    @PostMapping("/scripted/test")
    public ResponseEntity<Map<String, Object>> testScripted(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.scripted", "execute");
        if (!scriptedChecker.isAvailable())
            return badRequest("k6 bulunamadı — Sentetik İzleme devre dışı (bu ortamda k6 binary'si yok).");
        String script = body.get("script") != null ? body.get("script").toString() : "";
        if (script.isBlank()) return badRequest("script zorunlu");
        Integer timeout = body.get("timeoutSeconds") instanceof Number tn ? tn.intValue() : null;
        // Test env: frontend ham gönderir (secret değerler düz; henüz şifreli değil) → checker.test decrypt=false ile alır.
        String envJson = testEnvJson(body.get("env"));
        // MONITOR_TRIGGER değil MONITOR_TEST: tetikleme KAYITLI bir monitörü zorla koşturur (kontrol
        // satırı yazar, alarm açabilir); ad-hoc test hiçbir şey yazmaz ama sunucudan dışarı bağlantı
        // açar. İkisini tek türde tutup yalnız resource_id="test" ile ayırmak, hiçbir filtrenin
        // göstermediği bir konvansiyona bel bağlamaktı. Script GÖVDESİ yazılmaz, ölçüsü yazılır.
        auditService.recordAction("MONITOR_TEST", session, "SCRIPTED_MONITOR", "test",
                AuditDetail.of("kind", "ad-hoc", "script_len", script == null ? 0 : script.length(),
                        "use_proxy", normalizeUseProxy(body.get("useProxy"))), null);
        // Test koşumu da monitörün vekil tercihini kullanır: aksi halde "Test Çalıştır" yeşil,
        // kaydedilmiş koşum kırmızı olur ve fark teşhis edilemez.
        com.sitemonitor.service.ScriptedCheckerService.ScriptedResult r =
                scriptedChecker.test(script, envJson, timeout, normalizeUseProxy(body.get("useProxy")));
        return ok(scriptedResultMap(r));
    }

    @GetMapping("/scripted/{id}/response-series")
    public ResponseEntity<Map<String, Object>> scriptedResponseSeries(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        com.sitemonitor.model.ScriptedMonitor mon = scriptedMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sentetik izleme bulunamadı");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        // Diğer 6 türle aynı huni: ham [ts, durationMs, ok] satırları buildResponseSeries'ten geçer.
        // Ham Object[] dönüşü frontend'te s.ts=undefined yapıp ResponseTimeChart'ı çökertiyordu.
        return ok(buildResponseSeries(scriptedCheckRepo.responseSeriesRaw(id, range[0], range[1], SERIES_RAW_CAP),
                range[0], range[1], false));
    }

    /**
     * BAĞLANTI TEŞHİSİ — "Java çekebiliyor ama k6 çekemiyor" ayrımını ÖLÇEREK kapatır.
     *
     * <p>Sahadaki tıkanıklık: bir monitör 288 koşumun 288'inde {@code request timeout} verirken
     * aynı pod hedefin sertifikasını sorunsuz alabiliyordu. Farkın nerede oluştuğu — vekil kararı
     * mı, kurumsal CA mı, TLS'in kendisi mi — hiçbir ekrandan görülemiyordu ve teşhis dört sürüm
     * boyunca tahmine kaldı. Bu uç üç değişkeni TEK TEK oynatıp faz kırılımlarını yan yana koyar:
     * <ul>
     *   <li>k6 · vekilsiz · CA'lı  → bugünkü etkin davranış (NO_PROXY eşleşen hedeflerde)</li>
     *   <li>k6 · vekilli  · CA'lı  → vekil zorlandığında değişiyor mu</li>
     *   <li>k6 · vekilsiz · CA'sız → kurumsal CA fark yaratıyor mu (araya giren TLS cihazı testi)</li>
     * </ul>
     * Bacaklar SIRAYLA ve AYRI bir semaforla koşar; izleme havuzunu tüketmez.
     *
     * <p>Hedef: gövdedeki {@code url}, yoksa script'ten çıkarılan ilk adres.
     */
    @PostMapping("/scripted/{id}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnoseScripted(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.scripted", "execute");
        com.sitemonitor.model.ScriptedMonitor m = scriptedMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound("Sentetik izleme bulunamadı");
        if (!canOperateTeam(session, m.getTeamId())) return forbidden("Bu izleme üzerinde yetkiniz yok");
        if (!scriptedChecker.isAvailable())
            return badRequest("k6 bulunamadı — Sentetik İzleme devre dışı (bu ortamda k6 binary'si yok).");

        List<String> candidates = scriptedChecker.targetUrls(m);
        String url = body != null && body.get("url") != null ? body.get("url").toString().trim() : null;
        if (url == null || url.isBlank()) url = candidates.isEmpty() ? null : candidates.get(0);
        if (url == null || url.isBlank())
            return badRequest("Hedef adres script'ten çıkarılamadı — teşhis edilecek URL'i elle girin.");
        // Kullanıcı serbest URL verebiliyor ⇒ SSRF yüzeyi: koşum yolundaki AYNI guard'dan geçir.
        try {
            java.net.URI u = java.net.URI.create(url);
            if (u.getHost() == null) return badRequest("Geçersiz URL: " + url);
            ssrfGuard.validate(u.getHost());
        } catch (com.sitemonitor.service.SsrfGuard.BlockedException be) {
            return badRequest("Hedef adres teşhis edilemez: " + be.getMessage());
        } catch (IllegalArgumentException iae) {
            return badRequest("Geçersiz URL: " + url);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        // Aday adresler secret env DEĞERLERİ içerebilir (BASE_URL bir secret olabilir) — maskele.
        out.put("candidates", candidates.stream()
                .map(com.sitemonitor.service.SecretMask::maskUrlQuery).toList());
        out.put("proxy_configured", proxySettings.enabled());
        out.put("no_proxy", proxySettings.noProxyList());
        out.put("k6_version", scriptedChecker.version());
        List<Map<String, Object>> legs = new ArrayList<>();
        legs.add(diagLeg("k6-direct-ca", "k6 · doğrudan · kurumsal CA", url, false, true));
        if (proxySettings.enabled())
            legs.add(diagLeg("k6-proxy-ca", "k6 · vekil · kurumsal CA", url, true, true));
        legs.add(diagLeg("k6-direct-noca", "k6 · doğrudan · CA'sız", url, false, false));
        out.put("legs", legs);
        auditService.recordAction("MONITOR_DIAGNOSE", session, "SCRIPTED_MONITOR",
                String.valueOf(m.getId()), m.getName(), null);
        return ok(out);
    }

    /** Tek teşhis bacağı → {key, label, status, error, faz kırılımı}. */
    private Map<String, Object> diagLeg(String key, String label, String url, boolean viaProxy, boolean withCa) {
        var r = scriptedChecker.probe(url, viaProxy, withCa);
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("key", key);
        leg.put("label", label);
        leg.put("status", r.status());
        leg.put("ok", r.ok());
        leg.put("duration_ms", r.durationMs());
        leg.put("error", r.error());
        leg.put("via_proxy", r.viaProxy());
        leg.put("output_tail", r.outputTail());
        var ph = r.phases();
        leg.put("phases", ph == null ? null : phaseMap(ph.blockedMs(), ph.connectingMs(), ph.tlsMs(),
                ph.sendingMs(), ph.waitingMs(), ph.receivingMs(), ph.dataSent(), ph.dataReceived()));
        return leg;
    }

    // ── Senaryo yardımcıları ──────────────────────────────────────────────────

    private static final String[] SCRIPTED_FIELDS = {
            "name", "description", "script", "timeoutSeconds", "intervalSeconds",
            "confirmAttempts", "recoveryChecks", "active", "groupName", "notifyEmail", "notifyWebhook",
            "slowResponseEnabled", "slowThresholdMs" };

    /** Script gövde desen taraması → BLOCK politikasında hit varsa hata mesajı, aksi halde null (WARN sadece bilgi). */
    private String scanScriptOrError(Object script) {
        if (script == null) return null;
        List<String> hits = com.sitemonitor.service.ScriptedCheckerService.scanHardcodedSecrets(script.toString());
        if (hits.isEmpty()) return null;
        String policy = appSettings.getString("site.monitor.scripted.hardcoded-secret-policy", "WARN");
        if ("BLOCK".equalsIgnoreCase(policy))
            return "Script gövdesinde sabit-kodlu gizli değer tespit edildi (" + String.join(", ", hits)
                    + "). Bunları ortam değişkeni (secret) olarak tanımlayın ve script'te __ENV üzerinden kullanın.";
        return null;   // WARN: kaydı engelleme (frontend uyarısı gösterir)
    }

    /**
     * Kaydetme öncesi script doğrulaması: `k6 archive` ile derleme + __ENV referans denetimi.
     * Kesin sözdizimi hatasında (satır/sütun çıkarılabiliyorsa) kaydı ENGELLER; belirsiz durumda
     * (timeout, uzak import indirilemedi) kaydeder ve uyarı döndürür.
     */
    private com.sitemonitor.service.ScriptedCheckerService.ScriptDiagnostics validateScripted(Object script, Object envRaw) {
        return validateScripted(script, envRaw, null);
    }

    /**
     * @param timeoutRaw kaydedilmek üzere olan süreç bütçesi (gövdeden). Ters bütçe uyarısı
     *                   ("istek timeout'u ≥ süreç bütçesi") ancak bu bilinirse üretilebilir —
     *                   sahada 289 koşumluk sessiz başarısızlığın sebebi tam olarak buydu.
     */
    private com.sitemonitor.service.ScriptedCheckerService.ScriptDiagnostics validateScripted(
            Object script, Object envRaw, Object timeoutRaw) {
        List<String> envNames = new ArrayList<>();
        if (envRaw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> e && e.get("name") != null) envNames.add(e.get("name").toString());
            }
        }
        return scriptedChecker.validateScript(script == null ? null : script.toString(), envNames,
                asPositiveInt(timeoutRaw));
    }

    /** Gövdeden gelen sayı ("60", 60, 60.0) → pozitif int; ayrıştırılamazsa null (denetim atlanır). */
    private static Integer asPositiveInt(Object raw) {
        if (raw == null) return null;
        try {
            int v = (raw instanceof Number n) ? n.intValue() : Integer.parseInt(raw.toString().trim());
            return v > 0 ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void applyScriptedFields(com.sitemonitor.model.ScriptedMonitor m, Map<String, Object> body, String existingEnvJson) {
        if (body.containsKey("description")) m.setDescription(blank(body.get("description")) ? null : body.get("description").toString());
        if (body.get("script") != null) m.setScript(body.get("script").toString());
        if (body.get("timeoutSeconds") instanceof Number n)
            m.setTimeoutSeconds(Math.max(5, Math.min(180, n.intValue())));
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
        if (body.get("notifyEmail") instanceof Boolean b) m.setNotifyEmail(b);
        if (body.get("notifyWebhook")  instanceof Boolean b) m.setNotifyWebhook(b);
        // Vekil tercihi: yalnız bilinen üç değer kabul edilir; tanınmayan girdi AUTO'ya düşer
        // (koşum tarafı da null'ı AUTO sayıyor — iki uçta aynı varsayılan).
        // Yavas kosum alarmi (SCRIPTED_SLOW) — opt-in. Esik makul araliga kirpilir: 500 ms altinda
        // her kosum yavas sayilirdi (k6 sureci baslamasi tek basina ~100-300 ms), tavan ise mutlak
        // timeout tavani (180 sn): esik timeout'un ustundeyse alarm HIC acilamaz, sessiz olu ayar olurdu.
        if (body.get("slowResponseEnabled") instanceof Boolean sb) m.setSlowResponseEnabled(sb);
        if (body.get("slowThresholdMs") instanceof Number sn)
            m.setSlowThresholdMs(Math.max(500, Math.min(180_000, sn.intValue())));
        if (body.containsKey("useProxy")) m.setUseProxy(normalizeUseProxy(body.get("useProxy")));
        if (body.containsKey("env")) m.setEnvJson(buildEnvJson(existingEnvJson, body.get("env")));
    }

    // ── k6 script sürümleme ──────────────────────────────────────────────────

    private static final String FIRST_VERSION = com.sitemonitor.service.VersionLabels.FIRST_VERSION;

    /**
     * {@code 1.0.2} → bump türüne göre bir sonraki sürüm.
     *
     * <p>Gerçek uygulama {@link com.sitemonitor.service.VersionLabels}'a taşındı (2026-08-20):
     * şablon kütüphanesi AYNI sürümleme sözleşmesini kullanıyor ve ikinci bir kopya iki
     * davranışın zamanla ayrışmasını garanti ederdi. Bu metot delege olarak KALIYOR — sözleşmeyi
     * pinleyen {@code ScriptedVersioningTest} tek satır değişmeden geçerliliğini sürdürsün.
     */
    static String nextVersion(String current, String bumpType) {
        return com.sitemonitor.service.VersionLabels.nextVersion(current, bumpType);
    }

    /**
     * Sürüm satırı yazar ve monitörün güncel sürüm etiketini döndürür.
     *
     * <p>{@code CertificateNoteRevision} deseni: yazım try/catch ile YUTULUR — sürüm geçmişi
     * kaydedilemedi diye kullanıcının kaydı düşmez (geçmiş yardımcı bir kayıttır, ana işlem değil).
     */
    private String writeScriptVersion(com.sitemonitor.model.ScriptedMonitor m, String eventType,
                                      String bumpType, String note, HttpSession session) {
        try {
            var last = scriptedVersionRepo.findTopByMonitorIdOrderBySequenceNoDesc(m.getId());
            String version = last.map(v -> nextVersion(v.getVersion(), bumpType)).orElse(FIRST_VERSION);
            var row = new com.sitemonitor.model.ScriptedScriptVersion();
            row.setMonitorId(m.getId());
            row.setSequenceNo(last.map(v -> v.getSequenceNo() + 1).orElse(0));
            row.setVersion(version);
            row.setEventType(eventType);
            row.setScript(m.getScript());
            row.setEnvJson(m.getEnvJson());       // secret DEĞERLER şifreli hâliyle taşınır
            row.setNote(blank(note) ? null : note.toString().trim());
            row.setCreatedAt(ISO.format(Instant.now()));
            row.setCreatedBy(actor(session));
            row.setCreatedByName(actor(session));
            scriptedVersionRepo.save(row);
            return version;
        } catch (Exception e) {
            log.warn("Script sürümü yazılamadı (monitor={}): {}", m.getId(), e.toString());
            return m.getScriptVersion();
        }
    }

    /** Script veya env değişti mi — sürüm YALNIZ içerik değişince yazılır (ayar düzenlemesi sürüm üretmez). */
    private static boolean contentChanged(String oldScript, String oldEnv, com.sitemonitor.model.ScriptedMonitor now) {
        return !java.util.Objects.equals(oldScript, now.getScript())
                || !java.util.Objects.equals(oldEnv, now.getEnvJson());
    }

    /** {@code AUTO|ON|OFF} dışındaki her girdi (null dâhil) AUTO'ya düşer. */
    private static String normalizeUseProxy(Object raw) {
        String v = raw == null ? "" : raw.toString().trim().toUpperCase(java.util.Locale.ROOT);
        return ("ON".equals(v) || "OFF".equals(v)) ? v : "AUTO";
    }

    /**
     * Gelen env dizisini kalıcı JSON'a çevirir: secret değerler şifrelenir; secret değeri boş
     * gelirse eski şifreli değer KORUNUR (yazma-yalnız sözleşmesi — form sır alanlarını boş getirir).
     *
     * <p>BOZUK KAYIT SESSİZCE SIR SİLMEZ. Ayrıştırma hatası eskiden yutuluyordu
     * ({@code catch (Exception ignored)}); {@code existingSecrets} boş kalınca "değer değişmedi"
     * dalı {@code getOrDefault(name, "")} ile boş dize yazıyordu. Sonuç: kullanıcı monitörün
     * yalnızca ADINI değiştirip kaydettiğinde tüm secret env değişkenleri kalıcı olarak "" oluyor,
     * hiçbir hata ve hiçbir log satırı çıkmıyor, k6 koşumu bir sonraki turda kimlik doğrulama
     * hatasıyla düşüyor ve sebebi görünmüyordu. Artık: hata loglanır ve KORUNMASI GEREKEN bir
     * değer varsa istek 409 ile reddedilir — kullanıcı sırrı yeniden girerek satırı onarabilir.
     * Gelen istek tüm sırların değerini taşıyorsa korunacak bir şey yoktur ve kayıt normal işler.
     */
    private String buildEnvJson(String existingJson, Object incoming) {
        Map<String, String> existingSecrets = new LinkedHashMap<>();
        boolean existingUnreadable = false;
        try {
            if (existingJson != null && !existingJson.isBlank()) {
                com.fasterxml.jackson.databind.JsonNode arr = SCRIPTED_MAPPER.readTree(existingJson);
                if (arr.isArray()) for (var n : arr)
                    if (n.path("secret").asBoolean(false)) existingSecrets.put(n.path("name").asText(""), n.path("value").asText(""));
            }
        } catch (Exception ex) {
            existingUnreadable = true;
            log.warn("scripted env_json ayrıştırılamadı — kayıtlı sırlar korunamıyor: {}", ex.toString());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (incoming instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> e)) continue;
                Object nm = e.get("name");
                if (nm == null || nm.toString().isBlank()) continue;
                String name = nm.toString().trim();
                boolean secret = Boolean.TRUE.equals(e.get("secret")) || "true".equals(String.valueOf(e.get("secret")));
                Object val = e.get("value");
                String stored;
                if (secret) {
                    if (val != null && !val.toString().isBlank()) stored = secretCipher.encrypt(val.toString());
                    else if (existingUnreadable)
                        throw new IllegalStateException("Kayıtlı ortam değişkenleri okunamadı (bozuk kayıt): '"
                                + name + "' sırrı korunamaz. Değeri yeniden girip kaydedin.");
                    else stored = existingSecrets.getOrDefault(name, "");   // değer değişmedi → eski enc'i koru
                } else {
                    stored = val == null ? "" : val.toString();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name); row.put("value", stored == null ? "" : stored); row.put("secret", secret);
                out.add(row);
            }
        }
        try { return SCRIPTED_MAPPER.writeValueAsString(out); } catch (Exception e) { return "[]"; }
    }

    /** Test env (ham, şifresiz) → checker.test'in beklediği envJson (secret değerleri düz saklanır, decrypt=false). */
    private String testEnvJson(Object incoming) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (incoming instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> e)) continue;
                Object nm = e.get("name");
                if (nm == null || nm.toString().isBlank()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", nm.toString().trim());
                row.put("value", e.get("value") == null ? "" : e.get("value").toString());
                row.put("secret", false);   // test yolunda çözme yapılmaz
                out.add(row);
            }
        }
        try { return SCRIPTED_MAPPER.writeValueAsString(out); } catch (Exception e) { return "[]"; }
    }

    private Map<String, Object> scriptedResultMap(com.sitemonitor.service.ScriptedCheckerService.ScriptedResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status());
        out.put("ok", r.ok());
        out.put("duration_ms", r.durationMs());
        out.put("exit_code", r.exitCode());
        out.put("checks_passed", r.checksPassed());
        out.put("checks_failed", r.checksFailed());
        out.put("iteration_ms", r.iterationMs());
        out.put("http_req_avg_ms", r.httpReqAvgMs());
        out.put("http_req_p95_ms", r.httpReqP95Ms());
        out.put("checks_json", r.checksJson());
        out.put("output_tail", r.outputTail());
        out.put("error", r.error());
        out.put("via_proxy", r.viaProxy());
        var ph = r.phases();
        out.put("phases", ph == null ? null : phaseMap(ph.blockedMs(), ph.connectingMs(), ph.tlsMs(),
                ph.sendingMs(), ph.waitingMs(), ph.receivingMs(), ph.dataSent(), ph.dataReceived()));
        return out;
    }

    /**
     * İsteğin faz kırılımı — "nerede takıldı?" panelinin tek veri kaynağı.
     *
     * <p>Anahtarlar HER ZAMAN konur (null olsa bile): frontend "faz ölçülmedi" ile "alan gelmedi"yi
     * ayırabilsin. Hepsi null ise koşum hiç istek yapamamıştır. {@code null} dönmez —
     * çağıran "faz bilgisi yok" demek istiyorsa map'in tamamını null geçirir.
     */
    private static Map<String, Object> phaseMap(Long blocked, Long connecting, Long tls, Long sending,
                                                Long waiting, Long receiving, Long sent, Long received) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("blocked_ms", blocked);
        p.put("connecting_ms", connecting);
        p.put("tls_ms", tls);
        p.put("sending_ms", sending);
        p.put("waiting_ms", waiting);
        p.put("receiving_ms", receiving);
        p.put("data_sent", sent);
        p.put("data_received", received);
        return p;
    }

    /** env'i ekrana güvenli çevirir: secret → {name,secret:true,value_set}; non-secret → {name,secret:false,value}. */
    private List<Map<String, Object>> envForClient(String envJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (envJson == null || envJson.isBlank()) return out;
        try {
            com.fasterxml.jackson.databind.JsonNode arr = SCRIPTED_MAPPER.readTree(envJson);
            if (arr.isArray()) for (var n : arr) {
                Map<String, Object> row = new LinkedHashMap<>();
                String name = n.path("name").asText("");
                boolean secret = n.path("secret").asBoolean(false);
                row.put("name", name);
                row.put("secret", secret);
                if (secret) row.put("value_set", !n.path("value").asText("").isBlank());
                else row.put("value", n.path("value").asText(""));
                out.add(row);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private Map<String, Object> enrichScripted(com.sitemonitor.model.ScriptedMonitor m, com.sitemonitor.model.ScriptedCheck latest,
                                               Map<Long, String> teams, AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",   m.getId());
        item.put("name", m.getName());
        item.put("description", m.getDescription());
        item.put("script", m.getScript());
        item.put("env", envForClient(m.getEnvJson()));
        item.put("timeout_seconds", m.getTimeoutSeconds());
        item.put("group_name", m.getGroupName());
        item.put("team_id", m.getTeamId());
        item.put("notification_group_id", m.getNotificationGroupId());
        item.put("team_name", m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active", m.getActive());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("confirm_attempts", m.getConfirmAttempts());
        item.put("confirm_interval_seconds", m.getConfirmIntervalSeconds());
        item.put("recovery_checks", m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("tags", m.getTags());
        item.put("alert_level",     com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
        item.put("notify_email", m.getNotifyEmail());
        item.put("notify_webhook", m.getNotifyWebhook());
        item.put("use_proxy", m.getUseProxy() == null ? "AUTO" : m.getUseProxy());
        item.put("slow_response_enabled", Boolean.TRUE.equals(m.getSlowResponseEnabled()));
        item.put("slow_threshold_ms", m.getSlowThresholdMs());
        item.put("script_version", m.getScriptVersion());
        // Anomali guard'ı kapattıysa SEBEP arayüzde kalıcı uyarı olarak durur; kullanıcının kendi
        // kapattığı izlemeden ayrılır (ikisinde de active=false).
        item.put("disabled_reason", m.getDisabledReason());
        item.put("disabled_at", m.getDisabledAt());
        item.put("active_alarm", openAlarm != null);
        item.put("alarm_level", openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
        if (latest != null) {
            item.put("status", latest.getStatus());      // PASS | FAIL | ERROR | TIMEOUT
            item.put("ok", latest.getOk());
            item.put("duration_ms", latest.getDurationMs());
            item.put("exit_code", latest.getExitCode());
            item.put("checks_passed", latest.getChecksPassed());
            item.put("checks_failed", latest.getChecksFailed());
            item.put("http_req_avg_ms", latest.getHttpReqAvgMs());
            item.put("checks_json", latest.getChecksJson());
            item.put("output_tail", latest.getOutputTail());
            item.put("error", latest.getError());
            item.put("via_proxy", latest.getViaProxy());
            item.put("phases", phaseMap(latest.getReqBlockedMs(), latest.getReqConnectingMs(),
                    latest.getReqTlsMs(), latest.getReqSendingMs(), latest.getReqWaitingMs(),
                    latest.getReqReceivingMs(), latest.getDataSent(), latest.getDataReceived()));
            // Koşumun sürümü ile monitörün GÜNCEL sürümü ayrı alanlar: "bu arıza son değişiklikle
            // mi başladı?" sorusu ancak ikisi yan yana görülünce cevaplanır.
            item.put("run_script_version", latest.getScriptVersion());
            item.put("checked_at", latest.getCheckedAt());
        } else {
            // "Hiç koşmadı" dalı — anahtarlar EKSİK değil NULL olmalı (keyword enrich deseni):
            // eksik anahtar frontend'te `?? 0` ile "0✓/0✗" gibi yanıltıcı değerlere düşüyordu.
            item.put("status", "unknown");
            item.put("ok", null); item.put("duration_ms", null); item.put("checked_at", null);
            item.put("exit_code", null); item.put("checks_passed", null); item.put("checks_failed", null);
            item.put("error", null); item.put("phases", null);
        }
        return item;
    }

    // ── Domain (Alan Adı) Monitors (serbest-form) ─────────────────────────────

    @GetMapping("/domain")
    public ResponseEntity<Map<String, Object>> listDomain(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, DomainCheck> latest = domainCheckRepo.findLatestPerMonitor().stream()
                .filter(c -> c.getMonitorId() != null)
                .collect(Collectors.toMap(DomainCheck::getMonitorId, c -> c, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        Map<String, AlertEvent> alarms = new HashMap<>();   // domain başına en şiddetli açık DOMAINMON_* alarmı
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (EscalationService.isDomainMon(e.getAlertType()) && e.getDomain() != null) alarms.putIfAbsent(e.getDomain(), e);
        }
        // IDOR (H2): yalnız görüntülenebilir takımların monitörleri (global admin → hepsi).
        List<Map<String, Object>> result = domainMonitorRepo.findAllByOrderByNameAsc().stream()
                .filter(m -> SessionScope.canView(session, m.getTeamId()))
                .map(m -> enrichDomain(m, latest.get(m.getId()), teams, alarms.get(m.getDomain()))).toList();
        return ok(result);
    }

    @PostMapping("/domain")
    public ResponseEntity<Map<String, Object>> createDomain(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, true); if (_gt != null) return _gt; }   // grup + etiket zorunlu (2026-09-18)
        if (blank(body.get("domain"))) return badRequest("domain zorunlu");
        String reg = publicSuffixService.registrableDomain(body.get("domain").toString());
        if (reg == null || reg.isBlank()) return badRequest("Geçersiz/çözümlenemeyen alan adı");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        if (domainMonitorRepo.existsDuplicate(reg, teamId, null))
            return badRequest("Bu alan adı bu takımda zaten izleniyor.");
        String now = ISO.format(Instant.now());
        DomainMonitor m = new DomainMonitor();
        m.setName(blank(body.get("name")) ? reg : normalizeMonitorName(body.get("name").toString()));
        m.setDomain(reg);
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
        m.setTeamId(teamId);
        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
        m.setActive(true);                                            // varsayılan: yeni izleme aktif
        if (body.get("active") instanceof Boolean b) m.setActive(b);  // Kopyala: pasif kaynağın kopyası da pasif doğsun
        applyDomainFields(m, body);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        DomainMonitor saved = domainMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.DOMAIN, saved.getId(), saved.getName(),
                saved.getDomain(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "DOMAIN_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        // İLK DEĞERLER: denetim create'te changes=null geçiyor (güvenlik kaydı "ne oldu"yu yazar);
        // ürün geçmişi "hangi değerlerle doğdu" sorusunu cevaplamak zorunda.
        monitorHistory.record(MonitorHistoryService.DOMAIN, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
        return ok(enrichDomain(saved, null, teamNameMap(), null));
    }

    @PutMapping("/domain/{id}")
    public ResponseEntity<Map<String, Object>> updateDomain(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, false); if (_gt != null) return _gt; }   // gönderilip boş bırakılmışsa 400 (2026-09-18)
        java.util.Map<String, Object> _before = domainMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return domainMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name") != null) m.setName(normalizeMonitorName((String) body.get("name")));
            if (!blank(body.get("domain"))) {
                String reg = publicSuffixService.registrableDomain(body.get("domain").toString());
                if (reg != null && !reg.isBlank()) m.setDomain(reg);
            }
            if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
            if (body.containsKey("teamId")) m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
            closeAlertsOnPause(m.getActive(), body.get("active"), m.getDomain(), Set.of(EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_UNKNOWN, EscalationService.TYPE_DOMAINMON_STATUS, EscalationService.TYPE_DOMAINMON_CHANGED, EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, EscalationService.TYPE_DOMAINMON_BLACKLIST));
            if (body.get("active") instanceof Boolean b) m.setActive(b);
            applyDomainFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            monitorHistory.stampUpdated(m, session);
            DomainMonitor saved = domainMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "DOMAIN_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            // Aynı before/after çifti geçmişe de gider — audit çağrısına DOKUNULMAZ.
            var changeRow = monitorHistory.record(MonitorHistoryService.DOMAIN, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
            noteConfigChanged(changeRow, ActivityLogService.DOMAIN, saved.getDomain(), session);
            return ok(enrichDomain(saved, domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null),
                    teamNameMap(), openDomainMonAlarm(saved.getDomain())));
        }).orElse(notFound("Domain monitor not found"));
    }

    /** İsim alanına URL yapıştırılmışsa host'a indirger (https://www.x.com.tr/ → www.x.com.tr);
     *  kullanıcının bilinçli verdiği serbest metin isimler dokunulmadan (trim'lenerek) korunur. */
    static String normalizeMonitorName(String name) {
        if (name == null) return null;
        String t = name.trim();
        if (t.contains("://") || t.contains("/")) {
            String host = com.sitemonitor.service.PublicSuffixService.extractHost(t);
            if (host != null && !host.isBlank()) return host;
        }
        return t;
    }

    @DeleteMapping("/domain/{id}")
    public ResponseEntity<Map<String, Object>> deleteDomain(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        // Silme ÖNCESİ durum: aşağıda active=false yapılıyor, sonra almak farkı kaybettirirdi.
        Map<String, Object> _before = domainMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return domainMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            escalationService.resolveOpenAlertsSilently(m.getDomain(),
                    Set.of(EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_UNKNOWN,
                           EscalationService.TYPE_DOMAINMON_STATUS, EscalationService.TYPE_DOMAINMON_CHANGED),
                    "Sistem (izleme silindi)");
            if (domainReminderRepo != null) domainReminderRepo.deleteByMonitorId(m.getId());   // hatırlatma izleri de gider (2026-09-22)
            domainMonitorRepo.delete(m);
            activityLog.recordLifecycle(ActivityLogService.DOMAIN, m.getId(), m.getName(),
                    m.getDomain(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "DOMAIN_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            monitorHistory.record(MonitorHistoryService.DOMAIN, m.getId(), m.getName(), m.getTeamId(),
                    MonitorHistoryService.DELETE, _before, AuditDiff.snapshot(m, MON_FIELDS), null, session);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Domain monitor not found"));
    }

    @GetMapping("/domain/{id}/history")
    public ResponseEntity<?> domainHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        DomainMonitor mon = domainMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Domain monitor not found");
        // Hata sayacı artık DB count'tan — eski sürüm 500'lük KESİK listeden sayıyordu (bug).
        var src = new CheckHistoryService.Source<DomainCheck>() {
            public org.springframework.data.domain.Page<DomainCheck> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? domainCheckRepo.findByMonitorIdAndStatusNotAndCheckedAtBetween(id, "OK", f, t, p)
                            : domainCheckRepo.findByMonitorIdAndCheckedAtBetween(id, f, t, p);
            }
            public long total(String f, String t) { return domainCheckRepo.countByMonitorIdAndCheckedAtBetween(id, f, t); }
            public long fail(String f, String t) { return domainCheckRepo.countByMonitorIdAndStatusNotAndCheckedAtBetween(id, "OK", f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return domainCheckRepo.historyHistogram(id, f, t, len); }
            public List<Object[]> bounds() { return domainCheckRepo.historyBounds(id); }
        };
        return runHistory(session, mon.getTeamId(), src, "domain",
                mon.getDomain(), Set.of(EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_UNKNOWN,
                        EscalationService.TYPE_DOMAINMON_STATUS, EscalationService.TYPE_DOMAINMON_CHANGED),
                from, to, days, status, page, size, format, "domain-history-" + id, List.of(
                new CsvColumn<>("checked_at", DomainCheck::getCheckedAt),
                new CsvColumn<>("status", DomainCheck::getStatus),
                new CsvColumn<>("days_remaining", DomainCheck::getDaysRemaining),
                new CsvColumn<>("expiry_date", DomainCheck::getExpiryDate),
                new CsvColumn<>("registrar", DomainCheck::getRegistrar),
                new CsvColumn<>("source", DomainCheck::getSource),
                new CsvColumn<>("error", DomainCheck::getError)), response);
    }

    /**
     * Kalan-gün trendi (2026-09-22, alan adı denetimi madde I): kontrol geçmişindeki "kesinti çizelgesi" bir alan adı
     * kaydı için anlamsızdı — soru "kaç gün kaldı, ne zaman yenilendi, kayıt ne zaman değişti". Günlük seri: her gün
     * için o günün SON kontrolü (kalan gün, bitiş, registrar, kaynak) + o gün tespit edilen değişiklik(ler). Sayfalı
     * geçmişten türetilmez (bir sayfa = 50 satır); aralık tek sorguda okunur, gün başına indirgenir.
     */
    @GetMapping("/domain/{id}/trend")
    public ResponseEntity<Map<String, Object>> domainTrend(@PathVariable Long id,
            @RequestParam(defaultValue = "90") int days, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        DomainMonitor mon = domainMonitorRepo.findById(id).orElse(null);
        if (mon == null || !SessionScope.canView(session, mon.getTeamId())) return notFound("Domain monitor not found");
        int d = Math.max(1, Math.min(days, 730));
        String to = ISO.format(java.time.Instant.now());
        String from = ISO.format(java.time.Instant.now().minus(java.time.Duration.ofDays(d)));
        var pageable = org.springframework.data.domain.PageRequest.of(0, 5000,
                org.springframework.data.domain.Sort.by("checkedAt").ascending());
        List<DomainCheck> rows = domainCheckRepo.findByMonitorIdAndCheckedAtBetween(id, from, to, pageable).getContent();
        // Gün → o günün son kontrolü; değişiklik detayları gün içinde birleştirilir (aynı gün iki değişiklik kaybolmasın).
        Map<String, Map<String, Object>> byDay = new java.util.TreeMap<>();
        for (DomainCheck c : rows) {
            if (c.getCheckedAt() == null || c.getCheckedAt().length() < 10) continue;
            String day = c.getCheckedAt().substring(0, 10);
            Map<String, Object> pt = byDay.computeIfAbsent(day, k -> new LinkedHashMap<>());
            pt.put("day", day);
            pt.put("checked_at", c.getCheckedAt());
            pt.put("days_remaining", c.getDaysRemaining());
            pt.put("expiry_date", c.getExpiryDate());
            pt.put("registrar", c.getRegistrar());
            pt.put("source", c.getSource());
            pt.put("status", c.getStatus());
            if (Boolean.TRUE.equals(c.getChanged())) {
                pt.put("changed", true);
                String prev = (String) pt.get("change_detail");
                String cur = c.getChangeDetail() == null ? "" : c.getChangeDetail();
                pt.put("change_detail", prev == null || prev.isBlank() ? cur : (cur.isBlank() || prev.contains(cur) ? prev : prev + " " + cur));
            } else if (!pt.containsKey("changed")) {
                pt.put("changed", false);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("from", from);
        out.put("to", to);
        out.put("warning_days", mon.getWarningDays());
        out.put("critical_days", mon.getCriticalDays());
        out.put("points", new ArrayList<>(byDay.values()));
        return ok(out);
    }

    /** Gönderilen süre-bitişi hatırlatmaları (2026-09-22, madde E) — detay penceresi Domain Kaydı sekmesi. */
    @GetMapping("/domain/{id}/reminders")
    public ResponseEntity<Map<String, Object>> domainReminders(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        DomainMonitor mon = domainMonitorRepo.findById(id).orElse(null);
        if (mon == null || !SessionScope.canView(session, mon.getTeamId())) return notFound("Domain monitor not found");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("thresholds", DomainExpiryReminderService.parseThresholds(mon.getThresholdsCsv()));
        out.put("items", domainReminders == null ? List.of() : domainReminders.history(id));
        return ok(out);
    }

    /** Yenileme planı koy/güncelle (2026-09-22, H) — karttaki "Planla"; yetki: izlemeyi yönetebilen. */
    @PostMapping("/domain/{id}/renewal-plan")
    public ResponseEntity<Map<String, Object>> planDomainRenewal(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        if (domainRenewalPlans == null) return badRequest("renewal plan unavailable");
        return domainMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canView(session, m.getTeamId())) return notFound("Domain monitor not found");
            if (!canOperateTeam(session, m.getTeamId())) return forbidden("Bu takımın izlemesini yönetemezsiniz");
            String date = body.get("date") == null ? "" : String.valueOf(body.get("date")).trim();
            if (!date.matches("^\\d{4}-\\d{2}-\\d{2}$")) return badRequest("date: YYYY-MM-DD");
            String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
            String currentExpiry = domainCheckRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(id, "NONE").map(DomainCheck::getExpiryDate).orElse(null);
            DomainMonitor saved = domainRenewalPlans.plan(m, date, note, currentExpiry, session);
            return ok(enrichDomain(saved, domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(), null));
        }).orElse(notFound("Domain monitor not found"));
    }

    @DeleteMapping("/domain/{id}/renewal-plan")
    public ResponseEntity<Map<String, Object>> unplanDomainRenewal(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        if (domainRenewalPlans == null) return badRequest("renewal plan unavailable");
        return domainMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canView(session, m.getTeamId())) return notFound("Domain monitor not found");
            if (!canOperateTeam(session, m.getTeamId())) return forbidden("Bu takımın izlemesini yönetemezsiniz");
            DomainMonitor saved = domainRenewalPlans.unplan(m, session);
            return ok(enrichDomain(saved, domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(), null));
        }).orElse(notFound("Domain monitor not found"));
    }

    @PostMapping("/domain/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerDomain(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return domainMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            Map<String, Object> r = domainChecker.check(m);   // DomainCheck persist eder
            if (domainReminders != null) domainReminders.evaluate(m, r);   // elle kontrol de eşik hatırlatmasını tetikler (2026-09-22, E)
            if (domainRenewalPlans != null) domainRenewalPlans.onCheckResult(m, r);   // yenileme görüldüyse plan kapanır (H)
            // Manuel kontrol de alarm üretsin/çözsün (sweep'in günlük checkDue geciktirmesini bekleme):
            // WARNING/CRITICAL/UNKNOWN görülürse alarm + e-posta anında; düzeldiyse açık alarm kapanır.
            try { schedulerService.evaluateDomainAlarmsNow(m, r); }
            catch (Exception e) { log.warn("Manuel domain alarm değerlendirmesi başarısız: {} — {}", m.getDomain(), e.getMessage()); }
            auditService.recordAction("MONITOR_TRIGGER", session, "DOMAIN_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(enrichDomain(m, domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null),
                    teamNameMap(), openDomainMonAlarm(m.getDomain())));
        }).orElse(notFound("Domain monitor not found"));
    }

    /** Domain Kaydı (registration) — DB'deki son bilgi; {@code live=true} ise anlık RDAP/WHOIS sorgusu
     *  (persist + alarm değerlendirmesi). Registrar+IANA ID, tarihler, nameserver, IP+hostname, EPP durum, DNSSEC. */
    @GetMapping("/domain/{id}/registration")
    public ResponseEntity<Map<String, Object>> domainRegistration(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean live, HttpSession session) {
        permissionService.require(session, "domain.registration.view", "view");
        return domainMonitorRepo.findById(id).map(m -> {
            // IDOR: izin USER'a varsayilan acik ama kaynak TAKIMA ait — kapsam disina 404
            // (varlik sizdirmamak icin 403 degil; resourceChanges ile ayni gerekce).
            if (!SessionScope.canView(session, m.getTeamId()))
                return notFound("Domain monitor not found");
            if (live) {
                // live=true SALT OKUMA DEGIL: dis RDAP/WHOIS sorgusu + persist + alarm
                // degerlendirmesi tetikler — yazma/calistirma yetkisi ister.
                if (!canOperateTeam(session, m.getTeamId()))
                    return forbidden("Bu izleme üzerinde canlı sorgu yetkiniz yok");
                Map<String, Object> r = domainChecker.check(m);   // taze RDAP/WHOIS + persist
                try { schedulerService.evaluateDomainAlarmsNow(m, r); }
                catch (Exception e) { log.warn("Registration live alarm değerlendirmesi başarısız: {} — {}", m.getDomain(), e.getMessage()); }
            }
            return ok(enrichDomain(m, domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null),
                    teamNameMap(), openDomainMonAlarm(m.getDomain())));
        }).orElse(notFound("Domain monitor not found"));
    }

    /** Ad-hoc domain testi — kaydetmeden RDAP/WHOIS ile bir kez sorgular (persist YOK). */
    @PostMapping("/domain/test")
    public ResponseEntity<Map<String, Object>> testDomain(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        if (blank(body.get("domain"))) return badRequest("domain zorunlu");
        int warn = body.get("warningDays") instanceof Number n ? n.intValue() : 30;
        int crit = body.get("criticalDays") instanceof Number n ? n.intValue() : 7;
        String domain = body.get("domain").toString();
        auditService.recordAction("MONITOR_TEST", session, "DOMAIN_MONITOR", "test",
                AuditDetail.of("domain", domain), null);
        return ok(domainChecker.test(domain, warn, crit));
    }

    /** Domain form alanlarını (thresholds/warning/critical/interval) body'den uygular. */
    private void applyDomainFields(DomainMonitor m, Map<String, Object> body) {
        if (!blank(body.get("thresholdsCsv"))) m.setThresholdsCsv(body.get("thresholdsCsv").toString().trim());
        if (body.get("warningDays") instanceof Number n) m.setWarningDays(Math.max(1, n.intValue()));
        if (body.get("criticalDays") instanceof Number n) m.setCriticalDays(Math.max(1, n.intValue()));
        if (body.get("intervalSeconds") instanceof Number n) m.setIntervalSeconds(Math.max(3600, n.intValue()));
        if (body.get("transferLockAlert") instanceof Boolean b) m.setTransferLockAlert(b);
        if (body.get("blacklistEnabled")  instanceof Boolean b) m.setBlacklistEnabled(b);
        if (body.get("changeAlert")       instanceof Boolean b) m.setChangeAlert(b);
        if (body.get("notifyEmail")     instanceof Boolean b) m.setNotifyEmail(b);
        if (body.get("notifyWebhook")     instanceof Boolean b) m.setNotifyWebhook(b);
        // KIRPMA sunucuda: diger yedi tur clampAttempts/clampInterval/clampRecovery kullaniyor,
        // DNS ve Domain'de sinir yalnizca FORMDA vardi. API'ye dogrudan
        // confirmIntervalSeconds=2000000000 gonderilirse teyit zinciri pratikte sonsuza ertelenir
        // ve o izlemenin kesinti alarmi HIC acilmaz — hicbir hata satiri da dusmez.
        if (body.get("confirmAttempts")         instanceof Number cn) m.setConfirmAttempts(clampAttempts(cn.intValue()));
        if (body.get("confirmIntervalSeconds")  instanceof Number cn) m.setConfirmIntervalSeconds(clampInterval(cn.intValue()));
        if (body.get("recoveryChecks")          instanceof Number cn) m.setRecoveryChecks(clampRecovery(cn.intValue()));
        if (body.get("recoveryIntervalSeconds") instanceof Number cn) m.setRecoveryIntervalSeconds(clampInterval(cn.intValue()));
        // RDAP kontrol timeout'u (ms) — boş/null = global ayar; girilirse 1–30 sn'ye kısılır.
        if (body.containsKey("checkTimeoutMs")) {
            Object v = body.get("checkTimeoutMs");
            if (v == null || (v instanceof String s && s.isBlank())) m.setCheckTimeoutMs(null);
            else if (v instanceof Number n) m.setCheckTimeoutMs(Math.max(1000, Math.min(30000, n.intValue())));
        }
    }

    private AlertEvent openDomainMonAlarm(String domain) {
        for (String t : List.of(EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_UNKNOWN,
                EscalationService.TYPE_DOMAINMON_STATUS, EscalationService.TYPE_DOMAINMON_CHANGED,
                EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, EscalationService.TYPE_DOMAINMON_BLACKLIST)) {
            var a = alertEventRepo.findOpenAlert(domain, t);
            if (a.isPresent()) return a.get();
        }
        return null;
    }

    private Map<String, Object> enrichDomain(DomainMonitor m, DomainCheck latest, Map<Long, String> teams, AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",               m.getId());
        item.put("name",             m.getName());
        item.put("domain",           m.getDomain());
        item.put("group_name",       m.getGroupName());
        item.put("tags",            m.getTags());
        item.put("alert_level",     com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
        item.put("team_id",          m.getTeamId());
        item.put("notification_group_id",          m.getNotificationGroupId());
        item.put("team_name",        m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",           m.getActive());
        item.put("notify_email",   m.getNotifyEmail());
        item.put("notify_webhook", m.getNotifyWebhook());
        item.put("confirm_attempts",          m.getConfirmAttempts());
        item.put("confirm_interval_seconds",  m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("check_timeout_ms", m.getCheckTimeoutMs());
        item.put("thresholds_csv",   m.getThresholdsCsv());
        item.put("warning_days",     m.getWarningDays());
        item.put("critical_days",    m.getCriticalDays());
        item.put("active_alarm",       openAlarm != null);
        item.put("alarm_level",        openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
        item.put("transfer_lock_alert", !Boolean.FALSE.equals(m.getTransferLockAlert()));
        item.put("blacklist_enabled",   Boolean.TRUE.equals(m.getBlacklistEnabled()));
        item.put("change_alert",        !Boolean.FALSE.equals(m.getChangeAlert()));
        // Planlanan yenileme (2026-09-22, H): kart rozeti + plan modalı; gecikmiş = plan tarihi geçti, plan hâlâ açık
        item.put("renewal_planned_at",   m.getRenewalPlannedAt());
        item.put("renewal_planned_by",   m.getRenewalPlannedByName());
        item.put("renewal_planned_note", m.getRenewalPlannedNote());
        item.put("renewal_overdue",      m.getRenewalPlannedAt() != null && m.getRenewalPlannedAt().compareTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()) < 0);
        if (latest != null) {
            item.put("status",            latest.getStatus());
            item.put("source",            latest.getSource());
            item.put("whois_provider",    latest.getWhoisProvider());
            item.put("days_remaining",    latest.getDaysRemaining());
            item.put("expiry_date",       latest.getExpiryDate());
            item.put("registration_date", latest.getRegistrationDate());
            item.put("last_changed",      latest.getLastChanged());
            item.put("registrar",         latest.getRegistrar());
            item.put("registrar_iana_id", latest.getRegistrarIanaId());
            item.put("dnssec",            latest.getDnssec());
            item.put("status_codes",      csvList(latest.getStatusCodes()));
            item.put("nameservers",       csvList(latest.getNameservers()));
            item.put("resolved_ips",      csvList(latest.getResolvedIps()));
            item.put("hostnames",         csvList(latest.getHostnames()));
            item.put("ns_resolves",       latest.getNsResolves());
            item.put("changed",           latest.getChanged());
            item.put("change_detail",     latest.getChangeDetail());
            item.put("transfer_lock",     latest.getTransferLock());
            item.put("blacklist_status",  latest.getBlacklistStatus());
            item.put("blacklist_detail",  latest.getBlacklistDetail());
            item.put("error",             latest.getError());
            item.put("checked_at",        latest.getCheckedAt());
        } else {
            item.put("status", "UNKNOWN"); item.put("source", null); item.put("whois_provider", null); item.put("days_remaining", null);
            item.put("expiry_date", null); item.put("registration_date", null); item.put("last_changed", null);
            item.put("registrar", null); item.put("registrar_iana_id", null); item.put("dnssec", null);
            item.put("status_codes", List.of()); item.put("nameservers", List.of());
            item.put("resolved_ips", List.of()); item.put("hostnames", List.of());
            item.put("ns_resolves", null); item.put("changed", false); item.put("change_detail", null);
            // Kontrol YOKKEN "kilitli" ya da "temiz" demek yok — ikisi de doğrulanamadı.
            item.put("transfer_lock", "UNKNOWN"); item.put("blacklist_status", "UNKNOWN");
            item.put("blacklist_detail", null);
            item.put("error", null); item.put("checked_at", null);
        }
        return item;
    }

    private static List<String> csvList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) { s = s.trim(); if (!s.isEmpty()) out.add(s); }
        return out;
    }

    // ── Ping Monitors (serbest-form) ──────────────────────────────────────────

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> listPing(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, PingCheck> latest = pingCheckRepo.findLatestPerMonitor().stream()
                .filter(c -> c.getMonitorId() != null)
                .collect(Collectors.toMap(PingCheck::getMonitorId, c -> c, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        // IDOR (H2): yalnız görüntülenebilir takımların monitörleri (global admin → hepsi).
        List<PingMonitor> monitors = pingMonitorRepo.findAllByOrderByNameAsc().stream()
                .filter(m -> SessionScope.canView(session, m.getTeamId())).toList();
        Map<String, AlertEvent> alarms = openAlarmsByDomain(
                monitors.stream().map(PingMonitor::getHost).collect(Collectors.toSet()),
                EscalationService.TYPE_PING_DOWN);
        List<Map<String, Object>> result = monitors.stream()
                .map(m -> enrichPing(m, latest.get(m.getId()), teams, alarms.get(m.getHost()))).toList();
        return ok(result);
    }

    @PostMapping("/ping")
    public ResponseEntity<Map<String, Object>> createPing(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, true); if (_gt != null) return _gt; }   // grup + etiket zorunlu (2026-09-18)
        if (blank(body.get("host"))) return badRequest("host zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        String host = ((String) body.get("host")).trim();
        if (pingMonitorRepo.existsDuplicate(host, teamId, null))
            return badRequest("Bu host bu takımda zaten izleniyor; mükerrer ping monitörü oluşturulamaz.");
        String now = ISO.format(Instant.now());
        PingMonitor m = new PingMonitor();
        m.setName((String) body.get("name"));
        m.setHost(host);
        String ipv = body.get("ipVersion") != null ? body.get("ipVersion").toString() : "auto";
        m.setIpVersion(Set.of("v4", "v6", "auto").contains(ipv) ? ipv : "auto");
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
        m.setTeamId(teamId);
        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
        m.setActive(true);                                            // varsayılan: yeni izleme aktif
        if (body.get("active") instanceof Boolean b) m.setActive(b);  // Kopyala: pasif kaynağın kopyası da pasif doğsun
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        if (body.get("packetCount")     != null) m.setPacketCount(((Number) body.get("packetCount")).intValue());
        if (body.get("notifyEmail")   instanceof Boolean b) m.setNotifyEmail(b);
        if (body.get("notifyWebhook")   instanceof Boolean b) m.setNotifyWebhook(b);
        if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
        if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
        if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
        if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
        applyPingSlowFields(m, body);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        PingMonitor saved = pingMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.PING, saved.getId(), saved.getName(),
                saved.getHost(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "PING_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        // İLK DEĞERLER: denetim create'te changes=null geçiyor (güvenlik kaydı "ne oldu"yu yazar);
        // ürün geçmişi "hangi değerlerle doğdu" sorusunu cevaplamak zorunda.
        monitorHistory.record(MonitorHistoryService.PING, saved.getId(), saved.getName(), saved.getTeamId(),
                MonitorHistoryService.CREATE, null, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
        return ok(enrichPing(saved, null, teamNameMap(), null));
    }

    @PutMapping("/ping/{id}")
    public ResponseEntity<Map<String, Object>> updatePing(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        { var _gt = requireGroupAndTags(body, false); if (_gt != null) return _gt; }   // gönderilip boş bırakılmışsa 400 (2026-09-18)
        java.util.Map<String, Object> _before = pingMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return pingMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            // Mükerrer guard: nihai host + takım ile (kendisi hariç) — host mutasyonu/alarm yan etkisinden ÖNCE.
            String intendedHost = body.get("host") != null ? ((String) body.get("host")).trim() : m.getHost();
            Long intendedTeam = body.containsKey("teamId") ? resolveTeamChange(session, m.getTeamId(), body.get("teamId")) : m.getTeamId();
            if (intendedHost != null && pingMonitorRepo.existsDuplicate(intendedHost, intendedTeam, id))
                return badRequest("Bu host bu takımda zaten izleniyor; mükerrer ping monitörü oluşturulamaz.");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("host")            != null) {
                String newHost = (String) body.get("host");
                if (m.getHost() != null && !m.getHost().equals(newHost)) {
                    // Host DEĞİŞTİ → eski host'un açık alarmını sessizce kapat. Aksi halde recovery yeni host
                    // ile arar, "domain=eskiHost" alarmı öksüz kalır ve asla resolve edilmez (BUG: takılı PING_DOWN).
                    escalationService.resolveOpenAlertsSilently(m.getHost(),
                            Set.of(EscalationService.TYPE_PING_DOWN, EscalationService.TYPE_PING_SLOW),
                            "Sistem (host değişti)");
                }
                m.setHost(newHost);
            }
            if (body.get("ipVersion")       != null) { String v = body.get("ipVersion").toString(); m.setIpVersion(Set.of("v4","v6","auto").contains(v) ? v : "auto"); }
            if (body.containsKey("groupName"))       m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.containsKey("alertLevel")) m.setAlertLevel(com.sitemonitor.model.MonitorAlertPrefs.normalize(body.get("alertLevel")));   // alarm seviyesi (2026-09-19)
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), m.getNotificationGroupId()));
            closeAlertsOnPause(m.getActive(), body.get("active"), m.getHost(), Set.of(EscalationService.TYPE_PING_DOWN, EscalationService.TYPE_PING_SLOW));
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("notifyEmail")   instanceof Boolean b) m.setNotifyEmail(b);
            if (body.get("notifyWebhook")   instanceof Boolean b) m.setNotifyWebhook(b);
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("packetCount")     != null) m.setPacketCount(((Number) body.get("packetCount")).intValue());
            if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            applyPingSlowFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            monitorHistory.stampUpdated(m, session);
            PingMonitor saved = pingMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "PING_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            // Aynı before/after çifti geçmişe de gider — audit çağrısına DOKUNULMAZ.
            var changeRow = monitorHistory.record(MonitorHistoryService.PING, saved.getId(), saved.getName(), saved.getTeamId(),
                    MonitorHistoryService.UPDATE, _before, AuditDiff.snapshot(saved, MON_FIELDS), changeNote(body), session);
            noteConfigChanged(changeRow, ActivityLogService.PING, saved.getHost(), session);
            return ok(enrichPing(saved, pingCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getHost(), EscalationService.TYPE_PING_DOWN).orElse(null)));
        }).orElse(notFound("Ping monitor not found"));
    }

    @DeleteMapping("/ping/{id}")
    public ResponseEntity<Map<String, Object>> deletePing(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        // Silme ÖNCESİ durum: aşağıda active=false yapılıyor, sonra almak farkı kaybettirirdi.
        Map<String, Object> _before = pingMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return pingMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            // Silme kaynaklı kapanma: açık alarmı sessizce resolved'a geçir (çözüldü maili YOK).
            escalationService.resolveOpenAlertsSilently(m.getHost(),
                    Set.of(EscalationService.TYPE_PING_DOWN, EscalationService.TYPE_PING_SLOW),
                    "Sistem (izleme silindi)");
            pingMonitorRepo.delete(m);   // hard delete — "Sil" listeden kaldırır ("Aktif" toggle ayrı)
            activityLog.recordLifecycle(ActivityLogService.PING, m.getId(), m.getName(),
                    m.getHost(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "PING_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            monitorHistory.record(MonitorHistoryService.PING, m.getId(), m.getName(), m.getTeamId(),
                    MonitorHistoryService.DELETE, _before, AuditDiff.snapshot(m, MON_FIELDS), null, session);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Ping monitor not found"));
    }

    @GetMapping("/ping/{id}/history")
    public ResponseEntity<?> pingHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String days,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String format,
            jakarta.servlet.http.HttpServletResponse response) {
        PingMonitor mon = pingMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Ping monitor not found");
        var src = new CheckHistoryService.Source<PingCheck>() {
            public org.springframework.data.domain.Page<PingCheck> page(String f, String t, boolean fail, org.springframework.data.domain.Pageable p) {
                return fail ? pingCheckRepo.findByMonitorIdAndUpFalseAndCheckedAtBetween(id, f, t, p)
                            : pingCheckRepo.findByMonitorIdAndCheckedAtBetween(id, f, t, p);
            }
            public long total(String f, String t) { return pingCheckRepo.countByMonitorIdAndCheckedAtBetween(id, f, t); }
            public long fail(String f, String t) { return pingCheckRepo.countByMonitorIdAndUpFalseAndCheckedAtBetween(id, f, t); }
            public List<Object[]> histogram(String f, String t, int len) { return pingCheckRepo.historyHistogram(id, f, t, len); }
            public List<Object[]> bounds() { return pingCheckRepo.historyBounds(id); }
        };
        return runHistory(session, mon.getTeamId(), src, "ping",
                mon.getHost(), Set.of(EscalationService.TYPE_PING_DOWN),
                from, to, days, status, page, size, format, "ping-history-" + id, List.of(
                new CsvColumn<>("checked_at", PingCheck::getCheckedAt),
                new CsvColumn<>("up", PingCheck::getUp),
                new CsvColumn<>("rtt_ms", PingCheck::getRttMs),
                new CsvColumn<>("packet_loss", PingCheck::getPacketLoss),
                new CsvColumn<>("error", PingCheck::getError)), response);
    }

    @PostMapping("/ping/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerPing(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return pingMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            Map<String, Object> r = pingChecker.check(m.getHost(), m.getIpVersion(),
                    m.getPacketCount() != null ? m.getPacketCount() : 4,
                    m.getTimeoutMs() != null ? m.getTimeoutMs() : 5000);
            PingCheck check = new PingCheck();
            check.setMonitorId(m.getId());
            check.setUp(Boolean.TRUE.equals(r.getOrDefault("up", false)));
            check.setRttMs(r.get("rtt_ms") instanceof Number n ? n.longValue() : null);
            check.setPacketLoss(r.get("packet_loss") instanceof Number n ? n.intValue() : null);
            check.setError((String) r.get("error"));
            check.setCheckedAt(ISO.format(Instant.now()));
            pingCheckRepo.save(check);
            auditService.recordAction("MONITOR_TRIGGER", session, "PING_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            // ...ve ARDINDAN zamanlayıcıyla AYNI değerlendirme hattı ASENKRON başlar: hata
            // doğrulama denemelerinden geçer, teyit edilirse alarm açılır; düzelme kurtarma
            // sayacından geçer. Eskiden manuel çalıştırma tek kontrol yapıp bırakıyordu —
            // ekranda "hata" görünüyor ama alarm hiç açılmıyordu (iki farklı gerçek).
            schedulerService.evaluatePingNow(m, r);   // AYNI sonuç — ikinci kontrol/kayıt YOK
            return ok(enrichPing(m, check, teamNameMap(),
                    alertEventRepo.findOpenAlert(m.getHost(), EscalationService.TYPE_PING_DOWN).orElse(null)));
        }).orElse(notFound("Ping monitor not found"));
    }

    /**
     * Ping yavaşlık alanlarını gövdeden uygular — create ve update TEK yerden.
     *
     * <p>İki kopya olsaydı klasik sonuç: alan create'te işlenir, update'te unutulur; kullanıcı
     * değeri değiştirir, kaydeder, form eski değeri geri okur ("kaydedilmiyor" hatası).
     *
     * <p>Sınırlar burada: pencere 1–1440 dk (bir günden uzun taban çizgisi "şu an yavaş mı"
     * sorusunu cevaplamaz), sapma 1–1000%. Eşik 0 olsaydı her dalgalanma alarm olurdu.
     */
    private void applyPingSlowFields(PingMonitor m, Map<String, Object> body) {
        if (body.get("slowResponseEnabled") instanceof Boolean b) m.setSlowResponseEnabled(b);
        if (body.get("slowBaselineWindowMinutes") instanceof Number n)
            m.setSlowBaselineWindowMinutes(Math.max(1, Math.min(1440, n.intValue())));
        if (body.get("slowThresholdPercent") instanceof Number n)
            m.setSlowThresholdPercent(Math.max(1, Math.min(1000, n.intValue())));
    }

    private Map<String, Object> enrichPing(PingMonitor m, PingCheck latest, Map<Long, String> teams, AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",               m.getId());
        item.put("name",             m.getName());
        item.put("host",             m.getHost());
        item.put("ip_version",       m.getIpVersion());
        item.put("group_name",       m.getGroupName());
        item.put("tags",            m.getTags());
        item.put("alert_level",     com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
        item.put("team_id",          m.getTeamId());
        item.put("notification_group_id",          m.getNotificationGroupId());
        item.put("team_name",        m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",           m.getActive());
        item.put("notify_email",   m.getNotifyEmail());
        item.put("notify_webhook", m.getNotifyWebhook());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("timeout_ms",       m.getTimeoutMs());
        item.put("packet_count",     m.getPacketCount());
        item.put("confirm_attempts",         m.getConfirmAttempts());
        item.put("confirm_interval_seconds", m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("slow_response_enabled",        m.getSlowResponseEnabled());
        item.put("slow_baseline_window_minutes", m.getSlowBaselineWindowMinutes());
        item.put("slow_threshold_percent",       m.getSlowThresholdPercent());
        item.put("active_alarm",       openAlarm != null);
        item.put("alarm_level",        openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
        if (latest != null) {
            boolean up = Boolean.TRUE.equals(latest.getUp());
            boolean na = !up && latest.getError() != null && latest.getError().contains("ICMP bu ortamda");
            item.put("status",      up ? "up" : (na ? "na" : "down"));
            item.put("up",          latest.getUp());
            item.put("rtt_ms",      latest.getRttMs());
            item.put("packet_loss", latest.getPacketLoss());
            item.put("error",       latest.getError());
            item.put("checked_at",  latest.getCheckedAt());
        } else {
            item.put("status", "unknown");
            item.put("up", null); item.put("rtt_ms", null); item.put("packet_loss", null);
            item.put("error", null); item.put("checked_at", null);
        }
        return item;
    }

    private Map<Long, String> teamNameMap() {
        // 60s cache'li (CertificateService.teamNamesById) — her liste isteğinde teamRepo.findAll() yok.
        return certificateService.teamNamesById();
    }

    /** Açık (resolved=false) alarmları domain→AlertEvent map'ine indir (verilen tip için).
     *  Liste uçlarında toplu kart-alarm işareti için tek sorgu (N+1 yok). */
    private Map<String, AlertEvent> openAlarmsByDomain(java.util.Collection<String> domains, String alertType) {
        if (domains.isEmpty()) return Map.of();
        return alertEventRepo.findOpenByDomainIn(domains).stream()
                .filter(e -> alertType.equals(e.getAlertType()))
                .collect(Collectors.toMap(AlertEvent::getDomain, e -> e, (a, b) -> a));
    }

    /** DNS: açık (resolved=false) alarmları domain→AlertEvent (en YÜKSEK seviye) map'ine indir — tüm DNS tipleri.
     *  Bir domainde birden çok açık DNS alarmı olabilir (ör. SLOW+CHANGED); rozet için en severe olan seçilir. */
    private Map<String, AlertEvent> openDnsAlarmsByDomain(java.util.Collection<String> domains) {
        if (domains == null || domains.isEmpty()) return Map.of();
        return alertEventRepo.findOpenByDomainIn(domains).stream()
                .filter(e -> DNS_ALERT_TYPES.contains(e.getAlertType()))
                .collect(Collectors.toMap(AlertEvent::getDomain, e -> e, (a, b) -> sev(a) >= sev(b) ? a : b));
    }

    /** Tek domain için açık DNS alarmlarından en yüksek seviyeli (rozet/enrich için). */
    private AlertEvent openDnsAlarm(String domain) {
        if (domain == null) return null;
        return alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain, DNS_ALERT_TYPES).stream()
                .max(java.util.Comparator.comparingInt(MonitoringController::sev)).orElse(null);
    }

    private static int sev(AlertEvent e) {
        if (e == null || e.getAlertLevel() == null) return 0;
        return switch (e.getAlertLevel()) { case "CRITICAL" -> 3; case "HIGH" -> 2; case "WARNING" -> 1; default -> 0; };
    }
}
