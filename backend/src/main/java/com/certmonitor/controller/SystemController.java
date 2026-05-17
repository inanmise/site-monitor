package com.certmonitor.controller;

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
import java.util.Map;

@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
public class SystemController {

    private final SchedulerService  schedulerService;
    private final MetricsService    metricsService;
    private final HttpMetricsService httpMetricsService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping
    public ResponseEntity<Map<String, Object>> getHealth(HttpSession session) {
        requireAdmin(session);
        Map<String, Object> data = schedulerService.getSystemHealth();
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", now()));
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
