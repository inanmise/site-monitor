package com.certmonitor.controller;

import com.certmonitor.service.ExtendedHealthService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.MetricsService;
import com.certmonitor.service.SchedulerService;
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

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping
    public ResponseEntity<Map<String, Object>> getHealth(HttpSession session) {
        requireAdmin(session);
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
        requireAdmin(session);
        int d = Math.max(1, Math.min(days, 365));
        return ResponseEntity.ok(Map.of(
                "success",   true,
                "data",      extendedHealthService.getSmtpFailures(d),
                "days",      d,
                "timestamp", now()));
    }

    @GetMapping("/db-stats")
    public ResponseEntity<Map<String, Object>> getDbStats(HttpSession session) {
        requireAdmin(session);
        return ResponseEntity.ok(Map.of("success", true, "data", extendedHealthService.getTableStats(), "timestamp", now()));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(HttpSession session) {
        requireAdmin(session);
        return ResponseEntity.ok(Map.of("success", true, "data", metricsService.getHistory(), "timestamp", now()));
    }

    @GetMapping("/http-metrics")
    public ResponseEntity<Map<String, Object>> getHttpMetrics(HttpSession session) {
        requireAdmin(session);
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

    @DeleteMapping("/scheduler-lock")
    public ResponseEntity<Map<String, Object>> forceReleaseLock(HttpSession session) {
        requireAdmin(session);
        schedulerService.forceReleaseLock();
        return ResponseEntity.ok(Map.of("success", true, "message", "Scheduler lock released", "timestamp", now()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requireAdmin(HttpSession session) {
        if (!"ADMIN".equals(session.getAttribute("systemRole"))) {
            throw new SecurityException("Admin access required");
        }
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
