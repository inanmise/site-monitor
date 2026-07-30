package com.certmonitor.controller;

import com.certmonitor.model.*;
import com.certmonitor.repository.*;
import com.certmonitor.service.CertificateService;
import com.certmonitor.service.DnsCheckerService;
import com.certmonitor.service.ActivityLogService;
import com.certmonitor.service.AuditDiff;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.PortCheckerService;
import com.certmonitor.service.KeywordCheckerService;
import com.certmonitor.service.PingCheckerService;
import com.certmonitor.service.HttpCheckerService;
import com.certmonitor.service.DomainCheckerService;
import com.certmonitor.service.PublicSuffixService;
import com.certmonitor.service.AppSettingsService;
import com.certmonitor.service.EscalationService;
import com.certmonitor.service.SchedulerService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.MonitoringGroupService;
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
    private final DomainCheckerService domainChecker;
    private final PublicSuffixService publicSuffixService;
    private final ActivityLogService activityLog;   // birleşik aktivite akışı (yaşam döngüsü olayları, best-effort)
    private final AuditService auditService;         // denetim (kim ne yaptı) — kurcalanamaz kayıt

    /** İzleme güncellemelerinde before/after diff için snapshot alınacak alanlar (tür-üstü superset; olmayan getter → null, gürültü yaratmaz). */
    private static final String[] MON_FIELDS = {
        "name", "host", "port", "url", "domain", "recordType", "keyword", "expectedValue", "expect",
        "expectedStatus", "method", "matchOperator", "matchCount", "active", "teamId", "groupName",
        "intervalSeconds", "timeoutMs", "warningDays", "criticalDays", "protocol", "verifySsl", "followRedirects",
        "mode", "crawlDepth", "crawlMaxPages", "excludePatterns", "slowResourceMs", "alertThirdParty", "alertMixedContent", "resourceConcurrency"
    };

    private final TeamRepository teamRepo;
    private final AlertEventRepository alertEventRepo;

    /** domain/host → sorumlu takım adı (izleme ekranlarında takım gösterimi/filtresi). */
    private final CertificateService certificateService;
    private final PermissionService permissionService;
    private final EscalationService escalationService;
    private final AppSettingsService appSettings;

    /** Manuel domain "Şimdi Kontrol Et" sonrası alarm değerlendirmesi için (sweep ile aynı mantık).
     *  @Lazy: SchedulerService ağır bean; olası wiring döngüsünü kır (ExtendedHealthService ile aynı desen). */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private SchedulerService schedulerService;

    /** Sayfa-bütünlüğü (9. tür) — @RequiredArgsConstructor'ı büyütmemek için alan enjeksiyonu (schedulerService deseni). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.certmonitor.repository.PageMonitorRepository pageMonitorRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.certmonitor.repository.PageCheckRepository pageCheckRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.certmonitor.repository.PageResourceIssueRepository pageResourceIssueRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private com.certmonitor.service.PageCheckerService pageChecker;

    /** Manuel sayfa-kontrol tetikleri için per-monitör cooldown zamanı (H1c rate-limit; in-memory, monitör sayısıyla sınırlı). */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> pageManualTriggerAt = new java.util.concurrent.ConcurrentHashMap<>();

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
            "ping",    Map.of("intervalSeconds", appSettings.getInt("cert.monitor.ping.default-interval-seconds", 60),
                              "timeoutMs",        appSettings.getInt("cert.monitor.ping.default-timeout-ms", 5000)),
            "keyword", Map.of("intervalSeconds", appSettings.getInt("cert.monitor.keyword.default-interval-seconds", 60),
                              "timeoutMs",        appSettings.getInt("cert.monitor.keyword.default-timeout-ms", 10000),
                              "slowThresholdMs",  appSettings.getInt("cert.monitor.keyword.default-slow-ms", 3000),
                              "caseSensitive",    false),
            "port",    Map.of("intervalSeconds", appSettings.getInt("cert.monitor.port.default-interval-seconds", 300),
                              "timeoutMs",        appSettings.getInt("cert.monitor.port.default-timeout-ms", 5000),
                              "slowThresholdMs",  appSettings.getInt("cert.monitor.port.default-slow-ms", 3000)),
            "dns",     Map.of("intervalSeconds", appSettings.getInt("cert.monitor.dns.default-interval-seconds", 300)),
            "http",    Map.of("intervalSeconds", appSettings.getInt("cert.monitor.http.default-interval-seconds", 300),
                              "timeoutMs",        appSettings.getInt("cert.monitor.http.default-timeout-ms", 10000)),
            "domain",  Map.of("intervalSeconds", appSettings.getInt("cert.monitor.domain.default-interval-seconds", 86400),
                              "warningDays",      appSettings.getInt("cert.monitor.domain.default-warning-days", 30),
                              "criticalDays",     appSettings.getInt("cert.monitor.domain.default-critical-days", 7),
                              "thresholds",       appSettings.getString("cert.monitor.domain.default-thresholds", "60,30,14,7,3,1")),
            "page",    Map.of("intervalSeconds",     appSettings.getInt("cert.monitor.page.default-interval-seconds", 300),
                              "timeoutMs",           appSettings.getInt("cert.monitor.page.default-timeout-ms", 10000),
                              "slowResourceMs",      appSettings.getInt("cert.monitor.page.default-slow-ms", 2000),
                              "resourceConcurrency", appSettings.getInt("cert.monitor.page.resource-concurrency", 5),
                              "crawlDepth",          appSettings.getInt("cert.monitor.page.default-crawl-depth", 2),
                              "crawlMaxPages",       appSettings.getInt("cert.monitor.page.default-crawl-max-pages", 50))
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
        return teamId.equals(sessionTeamId(session));               // USER kendi takımı
    }

    /** Oluştururken hedef takımı çözer: global admin istediğini (veya takımsız) atar; diğerleri
     *  yalnız iş görebildikleri bir takıma — değilse kendi takımlarına düşer. */
    private Long resolveWriteTeam(HttpSession session, Map<String, Object> body) {
        Long requested = body.get("teamId") instanceof Number n ? n.longValue() : null;
        if (SessionScope.isGlobalAdmin(session)) return requested;
        if (requested != null && canOperateTeam(session, requested)) return requested;
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

    private static boolean blank(Object o) {
        return o == null || o.toString().isBlank();
    }

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

        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
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

    /** toplam/hata → uptime yüzdesi (eski calcUptime ile aynı yuvarlama; "error" dışı = up). */
    private static double uptimePct(long total, long errors) {
        if (total == 0) return 100.0;
        long up = total - errors;
        return Math.round((up * 1000.0 / total)) / 10.0;
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

    @GetMapping("/uptime/{domain}/history")
    public ResponseEntity<Map<String, Object>> uptimeHistory(
            @PathVariable String domain,
            @RequestParam(defaultValue = "24") int hours) {

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
    public ResponseEntity<Map<String, Object>> uptimeHttpHistory(
            @PathVariable String domain,
            @RequestParam(defaultValue = "443") int port,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "500") int limit) {

        String fromStr = normalizeFrom(from != null ? from : ISO.format(Instant.now().minus(1, ChronoUnit.DAYS)));
        String toStr   = normalizeTo  (to   != null ? to   : ISO.format(Instant.now()));

        int cap = Math.max(1, Math.min(limit, 10_000));
        List<UptimeCheck> checks = uptimeCheckRepo.findByDomainAndPortAndDateRange(domain, port, fromStr, toStr, cap);
        List<Map<String, Object>> result = checks.stream().map(c -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("checked_at",  c.getCheckedAt());
            item.put("status",      c.getStatus());
            item.put("response_ms", c.getResponseMs());
            item.put("error",       c.getError());
            return item;
        }).toList();
        return ok(result);
    }

    // ── SSL Certificate History ───────────────────────────────────────────────

    @GetMapping("/uptime/{domain}/ssl-history")
    public ResponseEntity<Map<String, Object>> uptimeSslHistory(
            @PathVariable String domain,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "500") int limit) {

        String fromStr = normalizeFrom(from != null ? from : ISO.format(Instant.now().minus(1, ChronoUnit.DAYS)));
        String toStr   = normalizeTo  (to   != null ? to   : ISO.format(Instant.now()));

        int cap = Math.max(1, Math.min(limit, 10_000));
        List<CertificateCheck> checks = certCheckRepo.findByDomainAndDateRange(domain, fromStr, toStr, cap);
        List<Map<String, Object>> result = checks.stream().map(c -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("checked_at",     c.getCheckedAt());
            item.put("status",         c.getStatus());
            item.put("days_remaining", c.getDaysRemaining());
            item.put("error",          c.getError());
            return item;
        }).toList();
        return ok(result);
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
        if (!toCreate.isEmpty()) portMonitorRepo.saveAll(toCreate);

        // Her monitör için en güncel kontrol — tek toplu sorgu (eski: monitör başına findTop... → N+1).
        Map<Long, PortCheck> latestByMonitor = portCheckRepo.findLatestPerMonitor().stream()
                .filter(pc -> pc.getMonitorId() != null)
                .collect(Collectors.toMap(PortCheck::getMonitorId, pc -> pc, (a, b) -> a));

        Map<String, String> teamMap = certificateService.domainTeamNameMap();
        Map<Long, String> teamById = teamNameMap();
        List<PortMonitor> standaloneMonitors = portMonitorRepo.findByStandaloneTrueAndActiveTrue();
        // Açık PORT_DOWN alarmları host→AlertEvent (envanter + standalone host'ları) — liste rozeti, tek sorgu.
        Set<String> alarmHosts = new HashSet<>();
        for (PortMonitor m : monitorByKey.values()) alarmHosts.add(m.getHost());
        for (PortMonitor m : standaloneMonitors) if (m.getHost() != null) alarmHosts.add(m.getHost());
        Map<String, AlertEvent> portAlarms = openAlarmsByDomain(alarmHosts, EscalationService.TYPE_PORT_DOWN);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            int invPort = inv.getPort() != null ? inv.getPort() : 443;
            PortMonitor monitor = monitorByKey.get(inv.getDomain() + ":" + invPort);
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
        if (blank(body.get("host"))) return badRequest("host zorunlu");
        if (!(body.get("port") instanceof Number)) return badRequest("port zorunlu");
        String host = body.get("host").toString().trim();
        int port = ((Number) body.get("port")).intValue();
        if (port < 1 || port > 65535) return badRequest("port 1-65535 aralığında olmalı");
        if (!blank(body.get("sendData"))) requireAdmin(session);   // ham payload → yalnız admin (iç-servis SSRF payload'u)
        // Aynı host:port zaten AKTİF izleniyorsa tekrar ekleme (otomatik :443 kayıtlarıyla çakışmayı da önler).
        if (portMonitorRepo.findFirstByHostAndPortOrderByIdAsc(host, port)
                .filter(ex -> Boolean.TRUE.equals(ex.getActive())).isPresent())
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
        m.setActive(true);
        m.setStandalone(true);          // kullanıcı-eklediği → envanterden bağımsız; her zaman listelenir + kontrol edilir
        m.setTeamId(teamId);
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
        if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
        if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
        if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
        applyPortFeatureFields(m, body);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        PortMonitor saved = portMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.PORT, saved.getId(), saved.getName(),
                saved.getHost() + ":" + saved.getPort(), teamId, "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "PORT_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        return ok(enrichPort(saved, null, certificateService.domainTeamNameMap(), teamNameMap(),
                alertEventRepo.findOpenAlert(saved.getHost(), EscalationService.TYPE_PORT_DOWN).orElse(null)));
    }

    @PutMapping("/port/{id}")
    public ResponseEntity<Map<String, Object>> updatePort(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        java.util.Map<String, Object> _before = portMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return portMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) return forbidden("Bu izleme üzerinde yetkiniz yok");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("host")            != null) m.setHost(((String) body.get("host")).trim());
            if (body.get("port")            != null) {
                int np = ((Number) body.get("port")).intValue();
                if (np < 1 || np > 65535) return badRequest("port 1-65535 aralığında olmalı");
                m.setPort(np);
            }
            if (body.get("protocol")        != null) m.setProtocol(normalizePortType(body.get("protocol")));
            if (body.containsKey("expect"))    m.setExpect(blank(body.get("expect")) ? null : body.get("expect").toString().trim());
            if (body.containsKey("sendData")) {
                if (!blank(body.get("sendData"))) requireAdmin(session);   // ham payload → yalnız admin (iç-servis SSRF payload'u)
                m.setSendData(blank(body.get("sendData")) ? null : body.get("sendData").toString());
            }
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.containsKey("teamId"))    m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            applyPortFeatureFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            PortMonitor saved = portMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "PORT_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            return ok(enrichPort(saved, portCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null),
                    certificateService.domainTeamNameMap(), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getHost(), EscalationService.TYPE_PORT_DOWN).orElse(null)));
        }).orElse(notFound("Port monitor not found"));
    }

    @DeleteMapping("/port/{id}")
    public ResponseEntity<Map<String, Object>> deletePort(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return portMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) return forbidden("Bu izleme üzerinde yetkiniz yok");
            m.setActive(false);
            m.setUpdatedAt(ISO.format(Instant.now()));
            portMonitorRepo.save(m);
            activityLog.recordLifecycle(ActivityLogService.PORT, m.getId(), m.getName(),
                    m.getHost() + ":" + m.getPort(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "PORT_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Port monitor not found"));
    }

    @GetMapping("/port/{id}/history")
    public ResponseEntity<Map<String, Object>> portHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "100") int limit) {
        PortMonitor mon = portMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Port monitor not found");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        List<PortCheck> checks;
        long total, down;
        if (days != null && days > 0) {
            String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
            checks = portCheckRepo.findRecentByMonitorIdSince(id, cutoff, 500);   // SQL-LIMIT; özet DB count'tan
            total = portCheckRepo.countByMonitorIdAndCheckedAtGreaterThanEqual(id, cutoff);
            down  = portCheckRepo.countByMonitorIdAndOpenFalseAndCheckedAtGreaterThanEqual(id, cutoff);
        } else {
            int cap = Math.max(1, Math.min(limit, 10_000));
            checks = portCheckRepo.findRecentByMonitorId(id, cap);   // SQL-LIMIT
            total = checks.size();
            down  = checks.stream().filter(c -> !Boolean.TRUE.equals(c.getOpen())).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("total", total);
        out.put("down", down);
        return ok(out);
    }

    @PostMapping("/port/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerPort(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return portMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) return forbidden("Bu izleme üzerinde yetkiniz yok");
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
        item.put("team_id",         m.getTeamId());
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
        item.put("notify_email",              m.getNotifyEmail());
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
    private void applyPortFeatureFields(PortMonitor m, Map<String, Object> body) {
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.get("notifyEmail")         instanceof Boolean b) m.setNotifyEmail(b);
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
        if (!toCreate.isEmpty()) dnsMonitorRepo.saveAll(toCreate);

        // Her monitör için en güncel kayıt — tek toplu sorgu (eski: monitör başına findTop... → N+1).
        Map<Long, DnsRecord> latestByMonitor = dnsRecordRepo.findLatestPerMonitor().stream()
                .filter(r -> r.getMonitorId() != null)
                .collect(Collectors.toMap(DnsRecord::getMonitorId, r -> r, (a, b) -> a));

        Map<String, String> teamMap = certificateService.domainTeamNameMap();
        Map<Long, String> teamById = teamNameMap();
        List<DnsMonitor> standaloneMonitors = dnsMonitorRepo.findByStandaloneTrueAndActiveTrue();
        // Açık DNS alarmlarını tek sorguda çek → satırlarda aktif-alarm rozeti (envanter + standalone domainleri).
        Set<String> alarmDomains = new HashSet<>();
        for (CertificateInventory inv : inventory) alarmDomains.add(inv.getDomain());
        for (DnsMonitor m : standaloneMonitors) if (m.getDomain() != null) alarmDomains.add(m.getDomain());
        Map<String, AlertEvent> dnsAlarms = openDnsAlarmsByDomain(alarmDomains);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            DnsMonitor monitor = monitorByDomain.get(inv.getDomain());
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
        if (blank(body.get("domain")))     return badRequest("domain zorunludur");
        if (blank(body.get("recordType"))) return badRequest("recordType zorunludur");
        String domain = body.get("domain").toString().trim();
        String recordType = body.get("recordType").toString().trim().toUpperCase();
        if (!DNS_RECORD_TYPES.contains(recordType)) return badRequest("Geçersiz DNS kayıt tipi: " + recordType);
        // Aynı (domain, recordType) standalone monitör zaten varsa onu dön — tekrar oluşturma.
        var dup = dnsMonitorRepo.findFirstByDomainAndRecordTypeAndStandaloneTrue(domain, recordType);
        if (dup.isPresent()) {
            return ok(enrichDns(dup.get(),
                    dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(dup.get().getId()).orElse(null),
                    certificateService.domainTeamNameMap(), teamNameMap(), openDnsAlarm(dup.get().getDomain())));
        }
        String now = ISO.format(Instant.now());
        DnsMonitor m = new DnsMonitor();
        m.setName(blank(body.get("name")) ? domain : body.get("name").toString().trim());
        m.setDomain(domain);
        m.setRecordType(recordType);
        m.setActive(true);
        m.setStandalone(true);                          // sertifikadan bağımsız → envanter-skip'i baypas eder
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null)
            return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        m.setTeamId(teamId);   // alarm yönlendirme + liste kapsamı için takım
        Object ev = body.get("expectedValue");          // beklenen-değer kilidi (opsiyonel)
        m.setExpectedValue(ev != null && !ev.toString().isBlank() ? ev.toString().trim() : null);
        m.setPropagationCheck(Boolean.TRUE.equals(body.get("propagationCheck")));   // çoklu-resolver tutarlılık (opt-in)
        m.setSlowThresholdMs(clampSlow(body.get("slowThresholdMs")));               // per-monitor yavaş eşiği (boş=global)
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));   // mantıksal grup (serbest-form)
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        DnsMonitor saved = dnsMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.DNS, saved.getId(), saved.getName(),
                saved.getDomain() + " " + saved.getRecordType(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "DNS_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        return ok(enrichDns(saved, null, certificateService.domainTeamNameMap(), teamNameMap(), openDnsAlarm(saved.getDomain())));
    }

    @PutMapping("/dns/{id}")
    public ResponseEntity<Map<String, Object>> updateDns(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        java.util.Map<String, Object> _before = dnsMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return dnsMonitorRepo.findById(id).map(m -> {
            // Envanter-türevi monitör admin gerektirir; standalone'u sorumlu takımı yönetebilir.
            if (Boolean.TRUE.equals(m.getStandalone())) {
                if (!canOperateTeam(session, m.getTeamId())) return forbidden("Bu monitörü düzenleme yetkiniz yok");
            } else {
                requireAdmin(session);
            }
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("domain") != null) {
                String newDomain = ((String) body.get("domain")).trim();
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
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
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
            if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            m.setUpdatedAt(ISO.format(Instant.now()));
            DnsMonitor saved = dnsMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "DNS_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            return ok(enrichDns(saved, dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null),
                    certificateService.domainTeamNameMap(), teamNameMap(), openDnsAlarm(saved.getDomain())));
        }).orElse(notFound("DNS monitor not found"));
    }

    @DeleteMapping("/dns/{id}")
    public ResponseEntity<Map<String, Object>> deleteDns(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return dnsMonitorRepo.findById(id).map(m -> {
            if (Boolean.TRUE.equals(m.getStandalone())) {
                if (!canOperateTeam(session, m.getTeamId())) return forbidden("Bu monitörü silme yetkiniz yok");
                dnsMonitorRepo.delete(m);   // standalone → gerçek silme (envanterle bağı yok)
            } else {
                requireAdmin(session);
                m.setActive(false);         // envanter-türevi → soft-delete (envanter senkronu yeniden açabilir)
                m.setUpdatedAt(ISO.format(Instant.now()));
                dnsMonitorRepo.save(m);
            }
            activityLog.recordLifecycle(ActivityLogService.DNS, m.getId(), m.getName(),
                    m.getDomain() + " " + m.getRecordType(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "DNS_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("DNS monitor not found"));
    }

    @GetMapping("/dns/{id}/history")
    public ResponseEntity<Map<String, Object>> dnsHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "5000") int limit) {
        DnsMonitor mon = dnsMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("DNS monitor not found");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        int cap = Math.max(1, Math.min(limit, 10_000));
        List<DnsRecord> records;
        if (days != null && days > 0) {
            int d = Math.min(days, 90);
            String cutoff = ISO.format(Instant.now().minus(d, ChronoUnit.DAYS));
            records = dnsRecordRepo.findRecentByMonitorIdSince(id, cutoff, cap);   // SQL-LIMIT
        } else {
            records = dnsRecordRepo.findRecentByMonitorId(id, cap);   // SQL-LIMIT
        }
        return ok(records);
    }

    @PostMapping("/dns/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerDns(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "monitoring.trigger", "execute");
        return dnsMonitorRepo.findById(id).map(m -> {
            Map<String, Object> r = dnsChecker.check(m.getDomain(), m.getRecordType());
            String now = ISO.format(Instant.now());

            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) r.getOrDefault("values", List.of());
            String valueStr = String.join("\n", values);

            // Smart change detection: distinguishes rotation (round-robin) from real changes.
            DnsRecord prev = dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(m.getId()).orElse(null);
            String prevValue = prev != null ? prev.getValue() : null;
            DnsCheckerService.ChangeKind kind = DnsCheckerService.detectChange(prevValue, valueStr);

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
            return ok(enrichDns(m, record, certificateService.domainTeamNameMap(), teamNameMap(), openDnsAlarm(m.getDomain())));
        }).orElse(notFound("DNS monitor not found"));
    }

    /** Domain için tüm temel kayıt tipleri + SOA + authoritative NS — detail modal'da kullanılır. */
    @GetMapping("/dns/{id}/details")
    public ResponseEntity<Map<String, Object>> dnsDetails(@PathVariable Long id) {
        return dnsMonitorRepo.findById(id).map(m -> {
            Map<String, Object> data = new LinkedHashMap<>(dnsChecker.enrichedQuery(m.getDomain()));
            data.put("monitor", enrichDns(m, dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(m.getId()).orElse(null), certificateService.domainTeamNameMap(), teamNameMap(), openDnsAlarm(m.getDomain())));
            data.put("slow_threshold_ms", m.getSlowThresholdMs() != null ? m.getSlowThresholdMs()
                    : appSettings.getInt("cert.monitor.dns.slow-threshold-ms", 1500));   // per-monitor ?? global — grafik eşik çizgisi
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
        item.put("team_id",         m.getTeamId());
        item.put("expected_value",  m.getExpectedValue());
        item.put("propagation_check", Boolean.TRUE.equals(m.getPropagationCheck()));
        item.put("group_name",      m.getGroupName());
        // Standalone monitör takımını teamId'den çöz (envantere bağlı değil); envanter-türevi domain→envanter eşlemesinden.
        item.put("team_name",       standalone && m.getTeamId() != null
                ? teamById.get(m.getTeamId()) : teamMap.get(m.getDomain()));
        item.put("record_type",     m.getRecordType());
        item.put("active",          m.getActive());
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
        if (blank(body.get("url")) || blank(body.get("keyword"))) return badRequest("url ve keyword zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        String now = ISO.format(Instant.now());
        KeywordMonitor m = new KeywordMonitor();
        m.setName((String) body.get("name"));
        m.setUrl((String) body.get("url"));
        m.setKeyword((String) body.get("keyword"));
        if (body.get("customHeaders") != null) m.setCustomHeaders((String) body.get("customHeaders"));
        applyKeywordCondition(m, body);
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        m.setTeamId(teamId);
        m.setActive(true);
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
        return ok(enrichKeyword(saved, null, teamNameMap(), null));
    }

    @PutMapping("/keyword/{id}")
    public ResponseEntity<Map<String, Object>> updateKeyword(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        java.util.Map<String, Object> _before = keywordMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return keywordMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("url")             != null) m.setUrl((String) body.get("url"));
            if (body.get("keyword")         != null) m.setKeyword((String) body.get("keyword"));
            if (body.containsKey("customHeaders"))    m.setCustomHeaders((String) body.get("customHeaders"));
            if (body.get("operator") != null || body.get("matchCount") != null || body.get("condition") != null) applyKeywordCondition(m, body);
            if (body.containsKey("groupName"))       m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            applyKeywordFeatureFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            KeywordMonitor saved = keywordMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "KEYWORD_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            return ok(enrichKeyword(saved, keywordResultRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getUrl(), EscalationService.TYPE_KEYWORD).orElse(null)));
        }).orElse(notFound("Keyword monitor not found"));
    }

    @DeleteMapping("/keyword/{id}")
    public ResponseEntity<Map<String, Object>> deleteKeyword(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return keywordMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            // Silme kaynaklı kapanma: açık alarmı sessizce resolved'a geçir (çözüldü maili YOK).
            escalationService.resolveOpenAlertsSilently(m.getUrl(),
                    Set.of(EscalationService.TYPE_KEYWORD), "Sistem (izleme silindi)");
            keywordMonitorRepo.delete(m);   // hard delete — "Sil" listeden kaldırır ("Aktif" toggle ayrı)
            activityLog.recordLifecycle(ActivityLogService.KEYWORD, m.getId(), m.getName(),
                    m.getUrl(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "KEYWORD_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Keyword monitor not found"));
    }

    @GetMapping("/keyword/{id}/history")
    public ResponseEntity<Map<String, Object>> keywordHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "100") int limit) {
        KeywordMonitor mon = keywordMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Keyword monitor not found");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        List<KeywordResult> checks;
        long total, down;
        if (days != null && days > 0) {
            String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
            checks = keywordResultRepo.findRecentByMonitorIdSince(id, cutoff, 500);   // SQL-LIMIT
            total = keywordResultRepo.countByMonitorIdAndCheckedAtGreaterThanEqual(id, cutoff);
            down  = keywordResultRepo.countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(id, cutoff);
        } else {
            int cap = Math.max(1, Math.min(limit, 10_000));
            checks = keywordResultRepo.findRecentByMonitorId(id, cap);   // SQL-LIMIT
            total = checks.size();
            down  = checks.stream().filter(c -> !Boolean.TRUE.equals(c.getOk())).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("total", total);
        out.put("down", down);
        return ok(out);
    }

    @PostMapping("/keyword/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerKeyword(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return keywordMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            Map<String, Object> r = keywordChecker.check(m.getUrl(), m.getKeyword(),
                    m.getTimeoutMs() != null ? m.getTimeoutMs() : 10000, m.getCustomHeaders(), Boolean.TRUE.equals(m.getCaseSensitive()));
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
        String url = body.get("url") != null ? body.get("url").toString().trim() : "";
        String keyword = body.get("keyword") != null ? body.get("keyword").toString() : "";
        if (url.isEmpty() || keyword.isEmpty()) return badRequest("url ve keyword zorunlu");
        String op = body.get("operator") != null ? body.get("operator").toString() : "GTE";
        if (!KW_OPERATORS.contains(op)) op = "GTE";
        int threshold = body.get("matchCount") instanceof Number mn ? mn.intValue() : 1;
        int timeoutMs = body.get("timeoutMs") instanceof Number tn ? tn.intValue() : 10000;
        String customHeaders = body.get("customHeaders") != null ? body.get("customHeaders").toString() : null;
        boolean caseSensitive = Boolean.TRUE.equals(body.get("caseSensitive"));
        Map<String, Object> r = keywordChecker.check(url, keyword, timeoutMs, customHeaders, caseSensitive);
        int count = r.get("count") instanceof Number cn ? cn.intValue() : 0;
        boolean met = r.get("error") == null && KeywordCheckerService.evaluate(count, op, threshold);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("occurrences",   count);
        out.put("condition_met", met);
        out.put("http_status",   r.get("http_status"));
        out.put("response_ms",   r.get("response_ms"));
        out.put("snippet",       r.get("snippet"));
        out.put("error",         r.get("error"));
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
        Map<String, Object> r = dnsChecker.check(domain, recordType);
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) r.getOrDefault("values", List.of());
        boolean success = Boolean.TRUE.equals(r.get("success"));
        long responseMs = r.get("response_ms") instanceof Number rn ? rn.longValue() : 0L;
        Integer slowThr = clampSlow(body.get("slowThresholdMs"));
        int effSlow = slowThr != null ? slowThr : appSettings.getInt("cert.monitor.dns.slow-threshold-ms", 1500);
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
        if (!portMonitorRepo.existsById(id)) return notFound("Port monitor not found");
        String[] range = resolveRange(from, to, days);
        return ok(buildResponseSeries(portCheckRepo.responseSeriesRaw(id, range[0], range[1], SERIES_RAW_CAP),
                range[0], range[1], false));   // withLoss=false — port'ta paket kaybı yok
    }

    @GetMapping("/dns/{id}/response-series")
    public ResponseEntity<Map<String, Object>> dnsResponseSeries(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "30") int days, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        if (!dnsMonitorRepo.existsById(id)) return notFound("DNS monitor not found");
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

    /** Aralık genişliğine göre kova anahtarı uzunluğu: ≤48s → 10-dk(15), ≤31g → saat(13), üstü → gün(10). */
    private static int bucketKeyLen(String from, String to) {
        try {
            long hours = java.time.Duration.between(LocalDateTime.parse(from, LDT), LocalDateTime.parse(to, LDT)).toHours();
            if (hours <= 48) return 15;
            if (hours <= 31 * 24) return 13;
            return 10;
        } catch (Exception e) { return 13; }
    }

    /** Kova anahtarını (kısaltılmış ISO) tam ISO timestamp'e açar (grafik x-ekseni). */
    private static String bucketIso(String key, int keyLen) {
        return switch (keyLen) {
            case 15 -> key + "0:00";        // ...THH:m → ...THH:m0:00
            case 13 -> key + ":00:00";      // ...THH   → ...THH:00:00
            default -> key + "T00:00:00";   // yyyy-MM-dd → ...T00:00:00
        };
    }

    /** Ham [checkedAt, süre, durum(up/ok)[, paket kaybı]] satırlarını kovalar:
     *  her kovada avg/min/max/p95/count/down[+loss]. Null süreler istatistiğe katılmaz; down durumdan sayılır. */
    private Map<String, Object> buildResponseSeries(List<Object[]> rows, String from, String to, boolean withLoss) {
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
                pt.put("loss", a != null && a[1] > 0 ? Math.round((double) a[0] / a[1]) : null);
            }
            series.add(pt);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series",     series);
        out.put("bucket",     keyLen == 15 ? "10m" : keyLen == 13 ? "hour" : "day");
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
        item.put("condition",        m.getAlertCondition());
        item.put("operator",         m.getMatchOperator());
        item.put("match_count",      m.getMatchCount());
        item.put("group_name",       m.getGroupName());
        item.put("team_id",          m.getTeamId());
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
        item.put("notify_email",              m.getNotifyEmail());
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
        if (body.get("notifyEmail")           instanceof Boolean b) m.setNotifyEmail(b);
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
        if (blank(body.get("url"))) return badRequest("url zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        String url = body.get("url").toString().trim();
        if (httpMonitorRepo.existsDuplicate(url, teamId, null))
            return badRequest("Bu URL bu takımda zaten izleniyor; mükerrer HTTP monitörü oluşturulamaz.");
        String now = ISO.format(Instant.now());
        HttpMonitor m = new HttpMonitor();
        m.setName(blank(body.get("name")) ? url : body.get("name").toString());
        m.setUrl(url);
        m.setMethod(normalizeHttpMethod(body.get("method")));
        if (!blank(body.get("expectedStatus"))) m.setExpectedStatus(body.get("expectedStatus").toString().trim());
        if (body.get("followRedirects") instanceof Boolean b) m.setFollowRedirects(b);
        if (body.get("verifySsl")       instanceof Boolean b) m.setVerifySsl(b);
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        m.setTeamId(teamId);
        m.setActive(true);
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
        return ok(enrichHttp(saved, null, teamNameMap(), null));
    }

    @PutMapping("/http/{id}")
    public ResponseEntity<Map<String, Object>> updateHttp(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        java.util.Map<String, Object> _before = httpMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return httpMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("url")             != null) m.setUrl(body.get("url").toString().trim());
            if (body.get("method")          != null) m.setMethod(normalizeHttpMethod(body.get("method")));
            if (!blank(body.get("expectedStatus"))) m.setExpectedStatus(body.get("expectedStatus").toString().trim());
            if (body.get("followRedirects") instanceof Boolean b) m.setFollowRedirects(b);
            if (body.get("verifySsl")       instanceof Boolean b) m.setVerifySsl(b);
            if (body.containsKey("groupName"))       m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.get("active")          instanceof Boolean b) m.setActive(b);
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            applyHttpFeatureFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            HttpMonitor saved = httpMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "HTTP_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            return ok(enrichHttp(saved, httpCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getUrl(), EscalationService.TYPE_HTTP_DOWN).orElse(null)));
        }).orElse(notFound("HTTP monitor not found"));
    }

    @DeleteMapping("/http/{id}")
    public ResponseEntity<Map<String, Object>> deleteHttp(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return httpMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            escalationService.resolveOpenAlertsSilently(m.getUrl(),
                    Set.of(EscalationService.TYPE_HTTP_DOWN, EscalationService.TYPE_HTTP_SSL, EscalationService.TYPE_DOMAIN_EXPIRY),
                    "Sistem (izleme silindi)");
            httpMonitorRepo.delete(m);
            activityLog.recordLifecycle(ActivityLogService.HTTP, m.getId(), m.getName(),
                    m.getUrl(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "HTTP_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("HTTP monitor not found"));
    }

    @GetMapping("/http/{id}/history")
    public ResponseEntity<Map<String, Object>> httpHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "100") int limit) {
        HttpMonitor mon = httpMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("HTTP monitor not found");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        List<HttpCheck> checks;
        long total, down;
        if (days != null && days > 0) {
            String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
            checks = httpCheckRepo.findRecentByMonitorIdSince(id, cutoff, 500);
            total = httpCheckRepo.countByMonitorIdAndCheckedAtGreaterThanEqual(id, cutoff);
            down  = httpCheckRepo.countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(id, cutoff);
        } else {
            int cap = Math.max(1, Math.min(limit, 10_000));
            checks = httpCheckRepo.findRecentByMonitorId(id, cap);
            total = checks.size();
            down  = checks.stream().filter(c -> !Boolean.TRUE.equals(c.getOk())).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("total", total);
        out.put("down", down);
        return ok(out);
    }

    @PostMapping("/http/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerHttp(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return httpMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            Map<String, Object> r = httpChecker.check(m.getUrl(), m.getMethod(), m.getExpectedStatus(),
                    m.getTimeoutMs() != null ? m.getTimeoutMs() : 10000,
                    Boolean.TRUE.equals(m.getVerifySsl()), !Boolean.FALSE.equals(m.getFollowRedirects()));
            HttpCheck res = new HttpCheck();
            res.setMonitorId(m.getId());
            res.setOk(Boolean.TRUE.equals(r.get("ok")));
            res.setHttpStatus(r.get("http_status") instanceof Number n ? n.intValue() : null);
            res.setResponseMs(r.get("response_ms") instanceof Number n ? n.longValue() : null);
            res.setError((String) r.get("error"));
            res.setCheckedAt(ISO.format(Instant.now()));
            httpCheckRepo.save(res);
            auditService.recordAction("MONITOR_TRIGGER", session, "HTTP_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(enrichHttp(m, res, teamNameMap(),
                    alertEventRepo.findOpenAlert(m.getUrl(), EscalationService.TYPE_HTTP_DOWN).orElse(null)));
        }).orElse(notFound("HTTP monitor not found"));
    }

    /** Ad-hoc HTTP testi — kaydetmeden, formdaki url/method/expectedStatus ile bir kez istek atar. */
    @PostMapping("/http/test")
    public ResponseEntity<Map<String, Object>> testHttp(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        String url = body.get("url") != null ? body.get("url").toString().trim() : "";
        if (url.isEmpty()) return badRequest("url zorunlu");
        String method = normalizeHttpMethod(body.get("method"));
        String expected = !blank(body.get("expectedStatus")) ? body.get("expectedStatus").toString().trim() : "200-399";
        int timeoutMs = body.get("timeoutMs") instanceof Number tn ? tn.intValue() : 10000;
        boolean verifySsl = Boolean.TRUE.equals(body.get("verifySsl"));
        boolean followRedirects = !Boolean.FALSE.equals(body.get("followRedirects"));
        Map<String, Object> r = httpChecker.check(url, method, expected, timeoutMs, verifySsl, followRedirects);
        Map<String, Object> out = new LinkedHashMap<>();
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
    private void applyHttpFeatureFields(HttpMonitor m, Map<String, Object> body) {
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.get("notifyEmail")           instanceof Boolean b) m.setNotifyEmail(b);
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
        item.put("group_name",       m.getGroupName());
        item.put("team_id",          m.getTeamId());
        item.put("team_name",        m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",           m.getActive());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("timeout_ms",       m.getTimeoutMs());
        item.put("confirm_attempts",         m.getConfirmAttempts());
        item.put("confirm_interval_seconds", m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("tags",                      m.getTags());
        item.put("notify_email",              m.getNotifyEmail());
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

    // ── Sayfa Bütünlüğü (Page Integrity) Monitors — 9. tür (serbest-form) ─────
    @GetMapping("/page")
    public ResponseEntity<Map<String, Object>> listPage(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, com.certmonitor.model.PageCheck> latest = pageCheckRepo.findLatestPerMonitor().stream()
                .filter(c -> c.getMonitorId() != null)
                .collect(Collectors.toMap(com.certmonitor.model.PageCheck::getMonitorId, c -> c, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        // IDOR (H2): yalnız oturumun görüntüleyebildiği takımların monitörleri (global admin → hepsi).
        List<com.certmonitor.model.PageMonitor> monitors = pageMonitorRepo.findAllByOrderByNameAsc().stream()
                .filter(m -> SessionScope.canView(session, m.getTeamId())).toList();
        Set<String> urls = monitors.stream().map(com.certmonitor.model.PageMonitor::getUrl).collect(Collectors.toSet());
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
        if (blank(body.get("url"))) return badRequest("url zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null) return badRequest("Takım seçimi zorunludur; izleme oluşturulamıyor.");
        String url = body.get("url").toString().trim();
        if (pageMonitorRepo.existsDuplicate(url, teamId, null))
            return badRequest("Bu URL bu takımda zaten izleniyor; mükerrer sayfa monitörü oluşturulamaz.");
        String now = ISO.format(Instant.now());
        com.certmonitor.model.PageMonitor m = new com.certmonitor.model.PageMonitor();
        m.setName(blank(body.get("name")) ? url : body.get("name").toString());
        m.setUrl(url);
        m.setTeamId(teamId);
        m.setActive(true);
        if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, teamId, body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
        if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
        if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
        if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
        applyPageFeatureFields(m, body);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        com.certmonitor.model.PageMonitor saved = pageMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.PAGE, saved.getId(), saved.getName(),
                saved.getUrl(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "PAGE_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        return ok(enrichPage(saved, null, teamNameMap(), null));
    }

    @PutMapping("/page/{id}")
    public ResponseEntity<Map<String, Object>> updatePage(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        java.util.Map<String, Object> _before = pageMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return pageMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("url")             != null) m.setUrl(body.get("url").toString().trim());
            if (body.containsKey("groupName"))       m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.get("active")          instanceof Boolean b) m.setActive(b);
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("confirmAttempts") != null)         m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null)  m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)          m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            applyPageFeatureFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            com.certmonitor.model.PageMonitor saved = pageMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "PAGE_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            return ok(enrichPage(saved, pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getUrl(), EscalationService.TYPE_PAGE_DOWN)
                            .or(() -> alertEventRepo.findOpenAlert(saved.getUrl(), EscalationService.TYPE_PAGE_INTEGRITY)).orElse(null)));
        }).orElse(notFound("Sayfa monitörü bulunamadı"));
    }

    @DeleteMapping("/page/{id}")
    public ResponseEntity<Map<String, Object>> deletePage(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return pageMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            escalationService.resolveOpenAlertsSilently(m.getUrl(),
                    Set.of(EscalationService.TYPE_PAGE_DOWN, EscalationService.TYPE_PAGE_INTEGRITY),
                    "Sistem (izleme silindi)");
            pageMonitorRepo.delete(m);
            activityLog.recordLifecycle(ActivityLogService.PAGE, m.getId(), m.getName(),
                    m.getUrl(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "PAGE_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Sayfa monitörü bulunamadı"));
    }

    @GetMapping("/page/{id}/history")
    public ResponseEntity<Map<String, Object>> pageHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) Integer days, @RequestParam(defaultValue = "100") int limit) {
        com.certmonitor.model.PageMonitor mon = pageMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sayfa monitörü bulunamadı");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        List<com.certmonitor.model.PageCheck> checks;
        long total, down;
        if (days != null && days > 0) {
            String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
            checks = pageCheckRepo.findRecentByMonitorIdSince(id, cutoff, 500);
            total = pageCheckRepo.countByMonitorIdAndCheckedAtGreaterThanEqual(id, cutoff);
            down  = pageCheckRepo.countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(id, cutoff);
        } else {
            int cap = Math.max(1, Math.min(limit, 10_000));
            checks = pageCheckRepo.findRecentByMonitorId(id, cap);
            total = checks.size();
            down  = checks.stream().filter(c -> !Boolean.TRUE.equals(c.getOk())).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("total", total);
        out.put("down", down);
        return ok(out);
    }

    /** Sorunlu-kaynak listesi — pencere içindeki ÇOK kontrolü (yalnız son değil) checked_at DESC + SQL-LIMIT'li
     *  döndürür; frontend'deki "Zaman" kolonu + kontrol-arası ayraç bunları ayrıştırır. Filtre: issueType, tarih. */
    @GetMapping("/page/{id}/issues")
    public ResponseEntity<Map<String, Object>> pageIssues(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) String issueType, @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "500") int limit) {
        com.certmonitor.model.PageMonitor mon = pageMonitorRepo.findById(id).orElse(null);
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
            long cooldownMs = appSettings.getInt("cert.monitor.page.manual-cooldown-seconds", 20) * 1000L;
            Long prev = pageManualTriggerAt.get(id);
            if (prev != null && nowMs - prev < cooldownMs) {
                return ResponseEntity.status(429).body(Map.<String, Object>of("success", false,
                        "error", "Bu monitör için çok sık manuel kontrol; " + (cooldownMs / 1000) + " sn bekleyin."));
            }
            pageManualTriggerAt.put(id, nowMs);
            schedulerService.triggerPageCheck(m);   // tam kontrol + persist (page_checks + issues + activity)
            auditService.recordAction("MONITOR_TRIGGER", session, "PAGE_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(enrichPage(m, pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(m.getUrl(), EscalationService.TYPE_PAGE_DOWN)
                            .or(() -> alertEventRepo.findOpenAlert(m.getUrl(), EscalationService.TYPE_PAGE_INTEGRITY)).orElse(null)));
        }).orElse(notFound("Sayfa monitörü bulunamadı"));
    }

    /** Ad-hoc sayfa testi — kaydetmeden, formdaki url ile tek SINGLE_PAGE bütünlük kontrolü. */
    @PostMapping("/page/test")
    public ResponseEntity<Map<String, Object>> testPage(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        String url = body.get("url") != null ? body.get("url").toString().trim() : "";
        if (url.isEmpty()) return badRequest("url zorunlu");
        int timeoutMs = body.get("timeoutMs") instanceof Number tn ? tn.intValue() : 10000;
        com.certmonitor.service.PageCheckerService.PageCheckResult r = pageChecker.test(url, timeoutMs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status",              r.status());
        out.put("main_reachable",      r.mainReachable());
        out.put("http_status",         r.httpStatus());
        out.put("response_ms",         r.responseMs());
        out.put("total_resources",     r.totalResources());
        out.put("broken_resources",    r.brokenResources());
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
        com.certmonitor.model.PageMonitor mon = pageMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Sayfa monitörü bulunamadı");
        var deny = denyIfNotViewable(session, mon.getTeamId());   // IDOR (H3): başka takımın serisi okunamaz
        if (deny != null) return deny;
        String[] range = resolveRange(from, to, days);
        // Seri değeri = kırık kaynak sayısı (buildResponseSeries yeniden kullanılır; frontend "kırık kaynak" etiketler).
        return ok(buildResponseSeries(pageCheckRepo.responseSeriesRaw(id, range[0], range[1], SERIES_RAW_CAP),
                range[0], range[1], false));
    }

    /** Ortak: sayfa-özel alanları (mode, crawl derinlik/limit, exclude, slow, alertThirdParty, concurrency,
     *  tags, notifyEmail) body'den clamp'li uygular. */
    private void applyPageFeatureFields(com.certmonitor.model.PageMonitor m, Map<String, Object> body) {
        if (!blank(body.get("mode"))) {
            String mode = body.get("mode").toString().trim().toUpperCase();
            m.setMode("SITE_CRAWL".equals(mode) ? "SITE_CRAWL" : "SINGLE_PAGE");
        }
        if (body.get("crawlDepth")    instanceof Number n) m.setCrawlDepth(Math.max(0, Math.min(5, n.intValue())));
        if (body.get("crawlMaxPages") instanceof Number n) m.setCrawlMaxPages(Math.max(1, Math.min(500, n.intValue())));
        if (body.containsKey("excludePatterns")) m.setExcludePatterns(blank(body.get("excludePatterns")) ? null : body.get("excludePatterns").toString());
        if (body.get("slowResourceMs") instanceof Number n) m.setSlowResourceMs(Math.max(100, n.intValue()));
        if (body.get("alertThirdParty") instanceof Boolean b) m.setAlertThirdParty(b);
        if (body.get("alertMixedContent") instanceof Boolean b) m.setAlertMixedContent(b);
        if (body.get("resourceConcurrency") instanceof Number n) m.setResourceConcurrency(Math.max(1, Math.min(20, n.intValue())));
        if (body.containsKey("tags")) m.setTags(blank(body.get("tags")) ? null : body.get("tags").toString().trim());
        if (body.get("notifyEmail") instanceof Boolean b) m.setNotifyEmail(b);
    }

    private Map<String, Object> enrichPage(com.certmonitor.model.PageMonitor m, com.certmonitor.model.PageCheck latest,
                                           Map<Long, String> teams, AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",                   m.getId());
        item.put("name",                 m.getName());
        item.put("url",                  m.getUrl());
        item.put("mode",                 m.getMode());
        item.put("crawl_depth",          m.getCrawlDepth());
        item.put("crawl_max_pages",      m.getCrawlMaxPages());
        item.put("exclude_patterns",     m.getExcludePatterns());
        item.put("slow_resource_ms",     m.getSlowResourceMs());
        item.put("alert_third_party",    m.getAlertThirdParty());
        item.put("alert_mixed_content",  m.getAlertMixedContent());
        item.put("resource_concurrency", m.getResourceConcurrency());
        item.put("group_name",           m.getGroupName());
        item.put("team_id",              m.getTeamId());
        item.put("team_name",            m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",               m.getActive());
        item.put("interval_seconds",     m.getIntervalSeconds());
        item.put("timeout_ms",           m.getTimeoutMs());
        item.put("confirm_attempts",         m.getConfirmAttempts());
        item.put("confirm_interval_seconds", m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
        item.put("tags",                      m.getTags());
        item.put("notify_email",              m.getNotifyEmail());
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
            item.put("mixed_content_count", latest.getMixedContentCount());
            item.put("pages_crawled",       latest.getPagesCrawled());
            item.put("error",               latest.getError());
            item.put("checked_at",          latest.getCheckedAt());
        } else {
            item.put("status", "unknown");
            item.put("ok", null); item.put("http_status", null); item.put("response_ms", null);
            item.put("total_resources", null); item.put("broken_resources", null);
            item.put("mixed_content_count", null); item.put("pages_crawled", null);
            item.put("error", null); item.put("checked_at", null);
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
        m.setTeamId(teamId);
        m.setActive(true);
        applyDomainFields(m, body);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        DomainMonitor saved = domainMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.DOMAIN, saved.getId(), saved.getName(),
                saved.getDomain(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "DOMAIN_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        return ok(enrichDomain(saved, null, teamNameMap(), null));
    }

    @PutMapping("/domain/{id}")
    public ResponseEntity<Map<String, Object>> updateDomain(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        java.util.Map<String, Object> _before = domainMonitorRepo.findById(id).map(x -> AuditDiff.snapshot(x, MON_FIELDS)).orElse(null);
        return domainMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name") != null) m.setName(normalizeMonitorName((String) body.get("name")));
            if (!blank(body.get("domain"))) {
                String reg = publicSuffixService.registrableDomain(body.get("domain").toString());
                if (reg != null && !reg.isBlank()) m.setDomain(reg);
            }
            if (body.containsKey("groupName")) m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId")) m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.get("active") instanceof Boolean b) m.setActive(b);
            applyDomainFields(m, body);
            m.setUpdatedAt(ISO.format(Instant.now()));
            DomainMonitor saved = domainMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "DOMAIN_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
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
            String host = com.certmonitor.service.PublicSuffixService.extractHost(t);
            if (host != null && !host.isBlank()) return host;
        }
        return t;
    }

    @DeleteMapping("/domain/{id}")
    public ResponseEntity<Map<String, Object>> deleteDomain(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return domainMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            escalationService.resolveOpenAlertsSilently(m.getDomain(),
                    Set.of(EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_UNKNOWN,
                           EscalationService.TYPE_DOMAINMON_STATUS, EscalationService.TYPE_DOMAINMON_CHANGED),
                    "Sistem (izleme silindi)");
            domainMonitorRepo.delete(m);
            activityLog.recordLifecycle(ActivityLogService.DOMAIN, m.getId(), m.getName(),
                    m.getDomain(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "DOMAIN_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Domain monitor not found"));
    }

    @GetMapping("/domain/{id}/history")
    public ResponseEntity<Map<String, Object>> domainHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) Integer days, @RequestParam(defaultValue = "100") int limit) {
        DomainMonitor mon = domainMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Domain monitor not found");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        List<DomainCheck> checks;
        long total;
        if (days != null && days > 0) {
            String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
            checks = domainCheckRepo.findRecentByMonitorIdSince(id, cutoff, 500);
            total = domainCheckRepo.countByMonitorIdAndCheckedAtGreaterThanEqual(id, cutoff);
        } else {
            int cap = Math.max(1, Math.min(limit, 10_000));
            checks = domainCheckRepo.findRecentByMonitorId(id, cap);
            total = checks.size();
        }
        long problems = checks.stream().filter(c -> !"OK".equals(c.getStatus())).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("total", total);
        out.put("down", problems);
        return ok(out);
    }

    @PostMapping("/domain/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerDomain(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return domainMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            Map<String, Object> r = domainChecker.check(m);   // DomainCheck persist eder
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
            if (live) {
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
        return ok(domainChecker.test(body.get("domain").toString(), warn, crit));
    }

    /** Domain form alanlarını (thresholds/warning/critical/interval) body'den uygular. */
    private void applyDomainFields(DomainMonitor m, Map<String, Object> body) {
        if (!blank(body.get("thresholdsCsv"))) m.setThresholdsCsv(body.get("thresholdsCsv").toString().trim());
        if (body.get("warningDays") instanceof Number n) m.setWarningDays(Math.max(1, n.intValue()));
        if (body.get("criticalDays") instanceof Number n) m.setCriticalDays(Math.max(1, n.intValue()));
        if (body.get("intervalSeconds") instanceof Number n) m.setIntervalSeconds(Math.max(3600, n.intValue()));
        // RDAP kontrol timeout'u (ms) — boş/null = global ayar; girilirse 1–30 sn'ye kısılır.
        if (body.containsKey("checkTimeoutMs")) {
            Object v = body.get("checkTimeoutMs");
            if (v == null || (v instanceof String s && s.isBlank())) m.setCheckTimeoutMs(null);
            else if (v instanceof Number n) m.setCheckTimeoutMs(Math.max(1000, Math.min(30000, n.intValue())));
        }
    }

    private AlertEvent openDomainMonAlarm(String domain) {
        for (String t : List.of(EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_UNKNOWN,
                EscalationService.TYPE_DOMAINMON_STATUS, EscalationService.TYPE_DOMAINMON_CHANGED)) {
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
        item.put("team_id",          m.getTeamId());
        item.put("team_name",        m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",           m.getActive());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("check_timeout_ms", m.getCheckTimeoutMs());
        item.put("thresholds_csv",   m.getThresholdsCsv());
        item.put("warning_days",     m.getWarningDays());
        item.put("critical_days",    m.getCriticalDays());
        item.put("active_alarm",       openAlarm != null);
        item.put("alarm_level",        openAlarm != null ? openAlarm.getAlertLevel() : null);
        item.put("alarm_acknowledged", openAlarm != null ? openAlarm.getAcknowledged() : null);
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
            item.put("error",             latest.getError());
            item.put("checked_at",        latest.getCheckedAt());
        } else {
            item.put("status", "UNKNOWN"); item.put("source", null); item.put("whois_provider", null); item.put("days_remaining", null);
            item.put("expiry_date", null); item.put("registration_date", null); item.put("last_changed", null);
            item.put("registrar", null); item.put("registrar_iana_id", null); item.put("dnssec", null);
            item.put("status_codes", List.of()); item.put("nameservers", List.of());
            item.put("resolved_ips", List.of()); item.put("hostnames", List.of());
            item.put("ns_resolves", null); item.put("changed", false); item.put("error", null); item.put("checked_at", null);
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
        m.setTeamId(teamId);
        m.setActive(true);
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        if (body.get("packetCount")     != null) m.setPacketCount(((Number) body.get("packetCount")).intValue());
        if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
        if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
        if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
        if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        PingMonitor saved = pingMonitorRepo.save(m);
        activityLog.recordLifecycle(ActivityLogService.PING, saved.getId(), saved.getName(),
                saved.getHost(), saved.getTeamId(), "CREATED", actor(session));
        auditService.recordAction("MONITOR_CREATE", session, "PING_MONITOR", String.valueOf(saved.getId()), saved.getName(), null);
        return ok(enrichPing(saved, null, teamNameMap(), null));
    }

    @PutMapping("/ping/{id}")
    public ResponseEntity<Map<String, Object>> updatePing(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
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
                            Set.of(EscalationService.TYPE_PING_DOWN), "Sistem (host değişti)");
                }
                m.setHost(newHost);
            }
            if (body.get("ipVersion")       != null) { String v = body.get("ipVersion").toString(); m.setIpVersion(Set.of("v4","v6","auto").contains(v) ? v : "auto"); }
            if (body.containsKey("groupName"))       m.setGroupName(monitoringGroupService.getOrCreateFor(m, m.getTeamId(), body.get("groupName") == null ? null : body.get("groupName").toString(), actor(session)));
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("packetCount")     != null) m.setPacketCount(((Number) body.get("packetCount")).intValue());
            if (body.get("confirmAttempts") != null)        m.setConfirmAttempts(clampAttempts(((Number) body.get("confirmAttempts")).intValue()));
            if (body.get("confirmIntervalSeconds") != null) m.setConfirmIntervalSeconds(clampInterval(((Number) body.get("confirmIntervalSeconds")).intValue()));
            if (body.get("recoveryChecks") != null)         m.setRecoveryChecks(clampRecovery(((Number) body.get("recoveryChecks")).intValue()));
            if (body.get("recoveryIntervalSeconds") != null) m.setRecoveryIntervalSeconds(clampInterval(((Number) body.get("recoveryIntervalSeconds")).intValue()));
            m.setUpdatedAt(ISO.format(Instant.now()));
            PingMonitor saved = pingMonitorRepo.save(m);
            auditService.recordAction("MONITOR_UPDATE", session, "PING_MONITOR", String.valueOf(saved.getId()), saved.getName(),
                    AuditDiff.diff(_before, AuditDiff.snapshot(saved, MON_FIELDS)));
            return ok(enrichPing(saved, pingCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap(),
                    alertEventRepo.findOpenAlert(saved.getHost(), EscalationService.TYPE_PING_DOWN).orElse(null)));
        }).orElse(notFound("Ping monitor not found"));
    }

    @DeleteMapping("/ping/{id}")
    public ResponseEntity<Map<String, Object>> deletePing(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return pingMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            // Silme kaynaklı kapanma: açık alarmı sessizce resolved'a geçir (çözüldü maili YOK).
            escalationService.resolveOpenAlertsSilently(m.getHost(),
                    Set.of(EscalationService.TYPE_PING_DOWN), "Sistem (izleme silindi)");
            pingMonitorRepo.delete(m);   // hard delete — "Sil" listeden kaldırır ("Aktif" toggle ayrı)
            activityLog.recordLifecycle(ActivityLogService.PING, m.getId(), m.getName(),
                    m.getHost(), m.getTeamId(), "DELETED", actor(session));
            auditService.recordAction("MONITOR_DELETE", session, "PING_MONITOR", String.valueOf(m.getId()), m.getName(), null);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Ping monitor not found"));
    }

    @GetMapping("/ping/{id}/history")
    public ResponseEntity<Map<String, Object>> pingHistory(@PathVariable Long id, HttpSession session,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "100") int limit) {
        PingMonitor mon = pingMonitorRepo.findById(id).orElse(null);
        if (mon == null) return notFound("Ping monitor not found");
        var deny = denyIfNotViewable(session, mon.getTeamId());
        if (deny != null) return deny;
        List<PingCheck> checks;
        long total, down;
        if (days != null && days > 0) {
            String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
            checks = pingCheckRepo.findRecentByMonitorIdSince(id, cutoff, 500);   // SQL-LIMIT
            total = pingCheckRepo.countByMonitorIdAndCheckedAtGreaterThanEqual(id, cutoff);
            down  = pingCheckRepo.countByMonitorIdAndUpFalseAndCheckedAtGreaterThanEqual(id, cutoff);
        } else {
            int cap = Math.max(1, Math.min(limit, 10_000));
            checks = pingCheckRepo.findRecentByMonitorId(id, cap);   // SQL-LIMIT
            total = checks.size();
            down  = checks.stream().filter(c -> !Boolean.TRUE.equals(c.getUp())).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("total", total);
        out.put("down", down);
        return ok(out);
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
            return ok(enrichPing(m, check, teamNameMap(),
                    alertEventRepo.findOpenAlert(m.getHost(), EscalationService.TYPE_PING_DOWN).orElse(null)));
        }).orElse(notFound("Ping monitor not found"));
    }

    private Map<String, Object> enrichPing(PingMonitor m, PingCheck latest, Map<Long, String> teams, AlertEvent openAlarm) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",               m.getId());
        item.put("name",             m.getName());
        item.put("host",             m.getHost());
        item.put("ip_version",       m.getIpVersion());
        item.put("group_name",       m.getGroupName());
        item.put("team_id",          m.getTeamId());
        item.put("team_name",        m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",           m.getActive());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("timeout_ms",       m.getTimeoutMs());
        item.put("packet_count",     m.getPacketCount());
        item.put("confirm_attempts",         m.getConfirmAttempts());
        item.put("confirm_interval_seconds", m.getConfirmIntervalSeconds());
        item.put("recovery_checks",           m.getRecoveryChecks());
        item.put("recovery_interval_seconds", m.getRecoveryIntervalSeconds());
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
