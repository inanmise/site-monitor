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
    private final ExtendedHealthService extendedHealthService;
    private final UserActivityService   userActivityService;
    private final DbAnalyticsService    dbAnalyticsService;
    private final UserService           userService;
    private final RememberMeService     rememberMeService;

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

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> triggerHeartbeat(HttpSession session) {
        requireAdmin(session);
        extendedHealthService.recordHeartbeat();
        return ResponseEntity.ok(Map.of("success", true, "data", extendedHealthService.getHeartbeatStatus(), "timestamp", now()));
    }

    @GetMapping("/heartbeat-timeline")
    public ResponseEntity<Map<String, Object>> heartbeatTimeline(
            @RequestParam(defaultValue = "1") int days,
            HttpSession session) {
        return ResponseEntity.ok(Map.of(
            "success",   true,
            "data",      extendedHealthService.getHeartbeatTimeline(days),
            "timestamp", now()));
    }

    @DeleteMapping("/scheduler-lock")
    public ResponseEntity<Map<String, Object>> forceReleaseLock(HttpSession session) {
        requireAdmin(session);
        schedulerService.forceReleaseLock();
        return ResponseEntity.ok(Map.of("success", true, "message", "Scheduler lock released", "timestamp", now()));
    }

    /** Kullanıcı / oturum izleme — aktif oturumlar, login serileri, top/anomali/peak (tek payload).
     *  Sayfa görünürlüğüyle aynı kitle: global admin VEYA AUDIT (salt-okuma denetçi). */
    @GetMapping("/user-activity")
    public ResponseEntity<Map<String, Object>> getUserActivity(HttpSession session) {
        requireSystemRead(session);
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
        return ResponseEntity.ok(Map.of(
                "success",   true,
                "data",      userActivityService.getLoginSeries(from, to, granularity),
                "timestamp", now()));
    }

    /** Admin: bir kullanıcının aktif oturumunu uzaktan sonlandır (kick) + remember-me token'larını iptal. */
    @PostMapping("/terminate-session")
    public ResponseEntity<Map<String, Object>> terminateSession(
            @RequestBody Map<String, String> body, HttpSession session) {
        requireAdmin(session);
        String username = body != null ? body.get("username") : null;
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "username required", "timestamp", now()));
        }
        userService.terminateActiveSession(username);
        rememberMeService.invalidateAllForUser(username);
        return ResponseEntity.ok(Map.of("success", true, "username", username, "timestamp", now()));
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
