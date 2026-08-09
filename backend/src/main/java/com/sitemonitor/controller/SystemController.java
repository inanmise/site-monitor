package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.DbAnalyticsService;
import com.sitemonitor.service.ExtendedHealthService;
import com.sitemonitor.service.HttpMetricsQueryService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.MetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.UserActivityService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.WeeklyAvailabilityReportService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
public class SystemController {

    private final SchedulerService      schedulerService;
    private final MetricsService        metricsService;
    private final HttpMetricsService    httpMetricsService;
    private final HttpMetricsQueryService httpMetricsQueryService;
    private final ExtendedHealthService extendedHealthService;
    private final UserActivityService   userActivityService;
    private final DbAnalyticsService    dbAnalyticsService;
    private final UserService           userService;
    private final RememberMeService     rememberMeService;
    private final PermissionService permissionService;
    private final WeeklyAvailabilityReportService weeklyAvailabilityReportService;
    private final com.sitemonitor.service.report.CertificateInventoryReportService certificateInventoryReportService;
    private final com.sitemonitor.service.AppSettingsService appSettingsService;
    private final AuditService auditService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping
    public ResponseEntity<Map<String, Object>> getHealth(HttpSession session) {
        Map<String, Object> data = new LinkedHashMap<>(schedulerService.getSystemHealth());
        data.put("smtp",      extendedHealthService.getSmtpStats());
        data.put("db_ms",     extendedHealthService.measureDbResponseMs());
        data.put("heartbeat", extendedHealthService.getHeartbeatStatus());
        data.put("network",   extendedHealthService.getNetworkStatus());
        data.put("domain_expiry", extendedHealthService.getDomainExpirySourceStatus());
        // Gece temizliği kendi kendini izler: "N saattir çalışmadı" / hatalı politika sinyali.
        data.put("cleanup",   extendedHealthService.getCleanupStatus());
        return ok(Map.of("data", data));
    }

    @GetMapping("/smtp-logs")
    public ResponseEntity<Map<String, Object>> getSmtpLogs(
            @RequestParam(defaultValue = "30") int days,
            HttpSession session) {
        int d = Math.max(1, Math.min(days, 365));
        return ok(Map.of(
                "data", extendedHealthService.getSmtpFailures(d),
                "days", d));
    }

    @GetMapping("/db-stats")
    public ResponseEntity<Map<String, Object>> getDbStats(HttpSession session) {
        return ok(Map.of("data", extendedHealthService.getTableStats()));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(HttpSession session) {
        return ok(Map.of("data", metricsService.getHistory()));
    }

    @GetMapping("/http-metrics")
    public ResponseEntity<Map<String, Object>> getHttpMetrics(HttpSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", httpMetricsService.getSummary());
        data.put("history", httpMetricsService.getHistory());
        return ok(Map.of("data", data));
    }

    /** Kalıcı HTTP metrikleri — aralıktaki endpoint listesi (seçici + özet). from/to UTC ISO. */
    @GetMapping("/http-metrics/endpoints")
    public ResponseEntity<Map<String, Object>> httpMetricEndpoints(
            @RequestParam String from, @RequestParam String to, HttpSession session) {
        requireSystemRead(session);
        permissionService.require(session, "system_health.read", "view");
        return ok(Map.of("data", httpMetricsQueryService.endpoints(from, to)));
    }

    /** Kalıcı HTTP metrikleri — zaman serisi + özet (count/errors/avg/p50/p95/p99). from/to UTC ISO;
     *  endpoint boş → tüm endpoint'ler ("Tümü"); granularity = minute|hour (boş → aralığa göre otomatik). */
    @GetMapping("/http-metrics/series")
    public ResponseEntity<Map<String, Object>> httpMetricSeries(
            @RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) String granularity,
            HttpSession session) {
        requireSystemRead(session);
        permissionService.require(session, "system_health.read", "view");
        return ok(Map.of("data", httpMetricsQueryService.series(from, to, endpoint, granularity)));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> triggerHeartbeat(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        extendedHealthService.recordHeartbeat();
        return ok(Map.of("data", extendedHealthService.getHeartbeatStatus()));
    }

    @GetMapping("/heartbeat-timeline")
    public ResponseEntity<Map<String, Object>> heartbeatTimeline(
            @RequestParam(defaultValue = "1") int days,
            HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        return ok(Map.of(
            "data", extendedHealthService.getHeartbeatTimeline(days)));
    }

    @DeleteMapping("/scheduler-lock")
    public ResponseEntity<Map<String, Object>> forceReleaseLock(HttpSession session) {
        requireAdmin(session);   // sistem-geneli yıkıcı işlem → admin-only (defense-in-depth)
        permissionService.require(session, "system_health.scheduler_lock", "execute"); // dedike + sensitive (matriste görünür)
        schedulerService.forceReleaseLock();
        auditService.recordAction("SCHEDULER_LOCK_RELEASE", session, "SCHEDULER", "lock", null, null);
        return ok(Map.of("message", "Scheduler lock released"));
    }

    /** Kullanıcı / oturum izleme — aktif oturumlar, login serileri, top/anomali/peak (tek payload).
     *  Sayfa görünürlüğüyle aynı kitle: global admin VEYA AUDIT (salt-okuma denetçi). */
    @GetMapping("/user-activity")
    public ResponseEntity<Map<String, Object>> getUserActivity(HttpSession session) {
        requireSystemRead(session);
        permissionService.require(session, "system_health.read", "view");
        return ok(Map.of(
                "data", userActivityService.getOverview()));
    }

    /** Veritabanı analitiği — top kullanıcı/SQL, yavaş sorgular, tablolar, seri, bağlantılar (tek payload).
     *  days = pencere (1/7/30). */
    @GetMapping("/db-analytics")
    public ResponseEntity<Map<String, Object>> dbAnalytics(
            @RequestParam(defaultValue = "7") int days, HttpSession session) {
        requireSystemRead(session);
        permissionService.require(session, "system_health.read", "view");
        return ok(Map.of(
                "data", dbAnalyticsService.getOverview(days)));
    }

    /** Esnek login serisi — grafik aralık seçimi (1g/7g/30g), gün-navigasyonu ve zoom için.
     *  from/to UTC ISO; granularity = day|hour|minute. */
    @GetMapping("/user-activity/series")
    public ResponseEntity<Map<String, Object>> loginSeries(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "day") String granularity,
            HttpSession session) {
        requireSystemRead(session);
        permissionService.require(session, "system_health.read", "view");
        return ok(Map.of(
                "data", userActivityService.getLoginSeries(from, to, granularity)));
    }

    /** Admin: bir kullanıcının aktif oturumunu uzaktan sonlandır (kick) + remember-me token'larını iptal. */
    @PostMapping("/terminate-session")
    public ResponseEntity<Map<String, Object>> terminateSession(
            @RequestBody Map<String, String> body, HttpSession session) {
        requireAdmin(session);   // sistem-geneli yıkıcı işlem → admin-only (defense-in-depth)
        permissionService.require(session, "system_health.terminate", "execute"); // dedike + sensitive (matriste görünür)
        String username = body != null ? body.get("username") : null;
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "username required", "timestamp", now()));
        }
        userService.terminateActiveSession(username);
        rememberMeService.invalidateAllForUser(username);
        auditService.recordAction("SESSION_TERMINATE", session, "USER", username, username, null);
        return ok(Map.of("username", username));
    }

    /** Admin: haftalık erişilebilirlik raporunu ŞİMDİ tetikle (Pazartesi'yi beklemeden test/önizleme).
     *  force=true → idempotency atlanır; geçen tam hafta penceresiyle ilgili takımlara mail gider. */
    @PostMapping("/weekly-availability/run")
    public ResponseEntity<Map<String, Object>> runWeeklyAvailability(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        var result = weeklyAvailabilityReportService.sendWeeklyReports(true);
        auditService.recordAction("WEEKLY_AVAILABILITY_RUN", session, "REPORT", "weekly-availability", null, null);
        return ok(Map.of("data", result));
    }

    /** Admin: haftalık erişilebilirlik durum kartı (genel anahtar + cron + raporlanan hafta + mail kitlesi). */
    @GetMapping("/weekly-availability/status")
    public ResponseEntity<Map<String, Object>> weeklyAvailabilityStatus(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");   // peer uçlarla tutarlı (admin-only)
        var data = weeklyAvailabilityReportService.status();
        return ok(Map.of("data", data));
    }

    /** Admin: bir takımın geçen haftalık raporunu GÖNDERMEDEN önizle (executive HTML + çözülmüş alıcılar). */
    @GetMapping("/weekly-availability/preview")
    public ResponseEntity<Map<String, Object>> weeklyAvailabilityPreview(
            @RequestParam Long teamId,
            @RequestParam(required = false) Integer weekOffset, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        try {
            var data = weeklyAvailabilityReportService.preview(teamId, weekOffset);
            return ok(Map.of("data", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", e.getMessage(), "timestamp", now()));
        }
    }

    /** Admin: seçilen takımın raporunu yalnız verilen test adresine gönder (toplu gönderim DEĞİL). */
    @PostMapping("/weekly-availability/send-test")
    public ResponseEntity<Map<String, Object>> weeklyAvailabilitySendTest(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        Object teamIdRaw = body != null ? body.get("teamId") : null;
        String email = body != null && body.get("email") != null ? body.get("email").toString().trim() : "";
        if (teamIdRaw == null || email.isBlank() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "teamId ve geçerli email gerekli", "timestamp", now()));
        }
        Long teamId = Long.valueOf(teamIdRaw.toString());
        try {
            String status = weeklyAvailabilityReportService.sendTest(teamId, email);
            boolean ok = status == null || !status.startsWith("FAILED");
            auditService.recordAction("WEEKLY_AVAILABILITY_TEST", session, "REPORT", String.valueOf(teamId),
                    "test → " + email, null);
            return ResponseEntity.ok(Map.of("success", ok, "data", Map.of("status", status == null ? "" : status),
                    "message", status == null ? "" : status, "timestamp", now()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", e.getMessage(), "timestamp", now()));
        }
    }

    /** Admin: arşivlenmiş giden haftalık erişilebilirlik mailleri (geçmişe dönük inceleme). */
    @GetMapping("/weekly-availability/history")
    public ResponseEntity<Map<String, Object>> weeklyAvailabilityHistory(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean includeTest,
            HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        var data = weeklyAvailabilityReportService.history(limit, includeTest);
        return ok(Map.of("data", data));
    }

    /** Admin: tek bir arşiv kaydının tam içeriği (saklanan HTML — önizleme modal'ında gösterilir). */
    @GetMapping("/weekly-availability/history/{id}")
    public ResponseEntity<Map<String, Object>> weeklyAvailabilityHistoryItem(
            @PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        try {
            var data = weeklyAvailabilityReportService.historyItem(id);
            return ok(Map.of("data", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", e.getMessage(), "timestamp", now()));
        }
    }

    /** Admin: haftalık erişilebilirlik e-postasını genel olarak aç/kapa (duraklat). */
    @PutMapping("/weekly-availability/enabled")
    public ResponseEntity<Map<String, Object>> weeklyAvailabilitySetEnabled(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        boolean enabled = body != null && Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        String actor = session != null ? (String) session.getAttribute("username") : null;
        weeklyAvailabilityReportService.setEnabled(enabled, actor != null ? actor : "admin");
        auditService.recordAction("WEEKLY_AVAILABILITY_TOGGLE", session, "REPORT", "weekly-availability",
                enabled ? "enabled" : "disabled", null);
        return ok(Map.of("data", Map.of("enabled", enabled)));
    }

    // ── Aylık sertifika envanteri raporu ──────────────────────────────────────
    // Yetki: haftalık erişilebilirlik uçlarıyla AYNI (requireAdmin + system_health.actions).
    // Yeni bir izin anahtarı eklenmedi — PermissionCatalog'da anahtar eklemek mevcut grant'leri
    // yeniden seed'lemediği için ayrı bir anahtar sessizce "kimsede yok" durumuna düşerdi.

    /** Admin: aylık envanter raporu durum kartı (aç/kapa, alıcılar, sonraki çalışma, son gönderim). */
    @GetMapping("/cert-inventory-report/status")
    public ResponseEntity<Map<String, Object>> certInventoryStatus(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        return ok(Map.of("data", certificateInventoryReportService.status()));
    }

    /** Admin: raporu GÖNDERMEDEN önizle (gönderimle aynı üreticiden HTML). */
    @GetMapping("/cert-inventory-report/preview")
    public ResponseEntity<Map<String, Object>> certInventoryPreview(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        return ok(Map.of("data", Map.of("html", certificateInventoryReportService.preview())));
    }

    /** Admin: raporu ŞİMDİ gönder (son cumayı beklemeden). force=true → idempotency atlanır. */
    @PostMapping("/cert-inventory-report/run")
    public ResponseEntity<Map<String, Object>> certInventoryRun(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        var result = certificateInventoryReportService.sendMonthlyReport(true);
        auditService.recordAction("CERT_INVENTORY_REPORT_RUN", session, "REPORT", "cert-inventory-report",
                result.rows() + " kayıt · " + result.findings() + " bulgu", null);
        return ok(Map.of("data", result));
    }

    /** Admin: raporu yalnız verilen test adresine gönder (aylık idempotency kaydı YAZILMAZ). */
    @PostMapping("/cert-inventory-report/send-test")
    public ResponseEntity<Map<String, Object>> certInventorySendTest(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        String email = body != null && body.get("email") != null ? body.get("email").toString().trim() : "";
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "Geçerli bir e-posta adresi gerekli", "timestamp", now()));
        }
        var result = certificateInventoryReportService.sendTest(email);
        boolean okStatus = result.status() == null || !result.status().startsWith("FAILED");
        auditService.recordAction("CERT_INVENTORY_REPORT_TEST", session, "REPORT", "cert-inventory-report",
                "test → " + email, null);
        return ResponseEntity.ok(Map.of("success", okStatus, "data", result,
                "message", result.status() == null ? "" : result.status(), "timestamp", now()));
    }

    /** Admin: aylık gönderim arşivi (yıl/ay, durum, kayıt ve bulgu sayısı). */
    @GetMapping("/cert-inventory-report/history")
    public ResponseEntity<Map<String, Object>> certInventoryHistory(
            @RequestParam(defaultValue = "24") int limit, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        return ok(Map.of("data", certificateInventoryReportService.history(limit)));
    }

    /** Admin: aylık raporu aç/kapa ve alıcıları güncelle. */
    @PutMapping("/cert-inventory-report/settings")
    public ResponseEntity<Map<String, Object>> certInventorySaveSettings(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        String actor = session != null ? (String) session.getAttribute("username") : null;
        String who = actor != null ? actor : "admin";

        Map<String, String> values = new java.util.LinkedHashMap<>();
        if (body != null && body.containsKey("enabled")) {
            values.put(com.sitemonitor.service.report.CertificateInventoryReportService.ENABLED_KEY,
                    String.valueOf(Boolean.parseBoolean(String.valueOf(body.get("enabled")))));
        }
        // "recipients" artık EK adreslerdir — asıl alıcılar sertifika sahibi takımlardan türetilir.
        if (body != null && body.containsKey("recipients")) {
            values.put(com.sitemonitor.service.report.CertificateInventoryReportService.EXTRA_TO_KEY,
                    String.valueOf(body.get("recipients")).trim());
        }
        if (body != null && body.containsKey("cc")) {
            values.put(com.sitemonitor.service.report.CertificateInventoryReportService.CC_KEY,
                    String.valueOf(body.get("cc")).trim());
        }
        // Zamanlama ayrı yoldan kaydedilir: geçersiz cron reddedilmeli (sessizce hiç çalışmayan
        // bir tetikleyici, aylarca fark edilmeyen bir arıza olurdu).
        if (body != null && body.containsKey("cron")) {
            try {
                certificateInventoryReportService.setCron(String.valueOf(body.get("cron")), who);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false, "error", e.getMessage(), "timestamp", now()));
            }
        }
        if (!values.isEmpty()) appSettingsService.save(Map.of("values", values), who);
        auditService.recordAction("CERT_INVENTORY_REPORT_SETTINGS", session, "REPORT", "cert-inventory-report",
                String.join(", ", values.keySet()), null);
        return ok(Map.of("data", certificateInventoryReportService.status()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException("Admin access required");
        }
    }

    /** Salt-okuma izleme: global admin veya AUDIT (System Health sekmesini gören kitle). */
    private void requireSystemRead(HttpSession session) {
        String role = session != null ? (String) session.getAttribute("systemRole") : null;
        if (!SessionScope.isGlobalAdmin(session) && !"AUDIT".equals(role)) {
            throw new SecurityException("Admin or audit access required");
        }
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", now());
        return ResponseEntity.ok(response);
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
