package com.certmonitor.controller;

import com.certmonitor.service.DbAnalyticsService;
import com.certmonitor.service.ExtendedHealthService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.MetricsService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.SchedulerService;
import com.certmonitor.service.UserActivityService;
import com.certmonitor.service.UserService;
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
    private final com.certmonitor.service.HttpMetricsQueryService httpMetricsQueryService;
    private final ExtendedHealthService extendedHealthService;
    private final UserActivityService   userActivityService;
    private final DbAnalyticsService    dbAnalyticsService;
    private final UserService           userService;
    private final RememberMeService     rememberMeService;
    private final com.certmonitor.service.PermissionService permissionService;
    private final com.certmonitor.service.WeeklyAvailabilityReportService weeklyAvailabilityReportService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping
    public ResponseEntity<Map<String, Object>> getHealth(HttpSession session) {
        Map<String, Object> data = new LinkedHashMap<>(schedulerService.getSystemHealth());
        data.put("smtp",      extendedHealthService.getSmtpStats());
        data.put("db_ms",     extendedHealthService.measureDbResponseMs());
        data.put("heartbeat", extendedHealthService.getHeartbeatStatus());
        data.put("network",   extendedHealthService.getNetworkStatus());
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", now()));
    }

    @GetMapping("/smtp-logs")
    public ResponseEntity<Map<String, Object>> getSmtpLogs(
            @RequestParam(defaultValue = "30") int days,
            HttpSession session) {
        int d = Math.max(1, Math.min(days, 365));
        return ResponseEntity.ok(Map.of(
                "success",   true,
                "data",      extendedHealthService.getSmtpFailures(d),
                "days",      d,
                "timestamp", now()));
    }

    @GetMapping("/db-stats")
    public ResponseEntity<Map<String, Object>> getDbStats(HttpSession session) {
        return ResponseEntity.ok(Map.of("success", true, "data", extendedHealthService.getTableStats(), "timestamp", now()));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(HttpSession session) {
        return ResponseEntity.ok(Map.of("success", true, "data", metricsService.getHistory(), "timestamp", now()));
    }

    @GetMapping("/http-metrics")
    public ResponseEntity<Map<String, Object>> getHttpMetrics(HttpSession session) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("summary", httpMetricsService.getSummary());
        data.put("history", httpMetricsService.getHistory());
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", now()));
    }

    /** Kalıcı HTTP metrikleri — aralıktaki endpoint listesi (seçici + özet). from/to UTC ISO. */
    @GetMapping("/http-metrics/endpoints")
    public ResponseEntity<Map<String, Object>> httpMetricEndpoints(
            @RequestParam String from, @RequestParam String to, HttpSession session) {
        requireSystemRead(session);
        permissionService.require(session, "system_health.read", "view");
        return ResponseEntity.ok(Map.of("success", true,
                "data", httpMetricsQueryService.endpoints(from, to), "timestamp", now()));
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
        return ResponseEntity.ok(Map.of("success", true,
                "data", httpMetricsQueryService.series(from, to, endpoint, granularity), "timestamp", now()));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> triggerHeartbeat(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        extendedHealthService.recordHeartbeat();
        return ResponseEntity.ok(Map.of("success", true, "data", extendedHealthService.getHeartbeatStatus(), "timestamp", now()));
    }

    @GetMapping("/heartbeat-timeline")
    public ResponseEntity<Map<String, Object>> heartbeatTimeline(
            @RequestParam(defaultValue = "1") int days,
            HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        return ResponseEntity.ok(Map.of(
            "success",   true,
            "data",      extendedHealthService.getHeartbeatTimeline(days),
            "timestamp", now()));
    }

    @DeleteMapping("/scheduler-lock")
    public ResponseEntity<Map<String, Object>> forceReleaseLock(HttpSession session) {
        requireAdmin(session);   // sistem-geneli yıkıcı işlem → admin-only (defense-in-depth)
        permissionService.require(session, "system_health.scheduler_lock", "execute"); // dedike + sensitive (matriste görünür)
        schedulerService.forceReleaseLock();
        return ResponseEntity.ok(Map.of("success", true, "message", "Scheduler lock released", "timestamp", now()));
    }

    /** Kullanıcı / oturum izleme — aktif oturumlar, login serileri, top/anomali/peak (tek payload).
     *  Sayfa görünürlüğüyle aynı kitle: global admin VEYA AUDIT (salt-okuma denetçi). */
    @GetMapping("/user-activity")
    public ResponseEntity<Map<String, Object>> getUserActivity(HttpSession session) {
        requireSystemRead(session);
        permissionService.require(session, "system_health.read", "view");
        return ResponseEntity.ok(Map.of(
                "success",   true,
                "data",      userActivityService.getOverview(),
                "timestamp", now()));
    }

    /** Veritabanı analitiği — top kullanıcı/SQL, yavaş sorgular, tablolar, seri, bağlantılar (tek payload).
     *  days = pencere (1/7/30). */
    @GetMapping("/db-analytics")
    public ResponseEntity<Map<String, Object>> dbAnalytics(
            @RequestParam(defaultValue = "7") int days, HttpSession session) {
        requireSystemRead(session);
        permissionService.require(session, "system_health.read", "view");
        return ResponseEntity.ok(Map.of(
                "success",   true,
                "data",      dbAnalyticsService.getOverview(days),
                "timestamp", now()));
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
        return ResponseEntity.ok(Map.of(
                "success",   true,
                "data",      userActivityService.getLoginSeries(from, to, granularity),
                "timestamp", now()));
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
        return ResponseEntity.ok(Map.of("success", true, "username", username, "timestamp", now()));
    }

    /** Admin: haftalık erişilebilirlik raporunu ŞİMDİ tetikle (Pazartesi'yi beklemeden test/önizleme).
     *  force=true → idempotency atlanır; geçen tam hafta penceresiyle ilgili takımlara mail gider. */
    @PostMapping("/weekly-availability/run")
    public ResponseEntity<Map<String, Object>> runWeeklyAvailability(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        var result = weeklyAvailabilityReportService.sendWeeklyReports(true);
        return ResponseEntity.ok(Map.of("success", true, "data", result, "timestamp", now()));
    }

    /** Admin: haftalık erişilebilirlik durum kartı (genel anahtar + cron + raporlanan hafta + mail kitlesi). */
    @GetMapping("/weekly-availability/status")
    public ResponseEntity<Map<String, Object>> weeklyAvailabilityStatus(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");   // peer uçlarla tutarlı (admin-only)
        var data = weeklyAvailabilityReportService.status();
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", now()));
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
            return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", now()));
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
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", now()));
    }

    /** Admin: tek bir arşiv kaydının tam içeriği (saklanan HTML — önizleme modal'ında gösterilir). */
    @GetMapping("/weekly-availability/history/{id}")
    public ResponseEntity<Map<String, Object>> weeklyAvailabilityHistoryItem(
            @PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "system_health.actions", "execute");
        try {
            var data = weeklyAvailabilityReportService.historyItem(id);
            return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", now()));
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
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("enabled", enabled), "timestamp", now()));
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

    private String now() {
        return ISO.format(Instant.now());
    }
}
