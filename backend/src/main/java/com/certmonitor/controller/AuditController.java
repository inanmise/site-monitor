package com.certmonitor.controller;

import com.certmonitor.model.AuditLog;
import com.certmonitor.repository.AuditLogRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepo;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> listAudit(
            @RequestParam(defaultValue = "0")     int     page,
            @RequestParam(defaultValue = "50")    int     size,
            @RequestParam(required = false)       String  actor,
            @RequestParam(required = false)       String  eventType,
            @RequestParam(required = false)       String  outcome,
            @RequestParam(required = false)       String  since,
            @RequestParam(required = false)       String  until,
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            HttpSession session) {
        requireAdmin(session);

        size = Math.min(size, 200);
        Page<AuditLog> result = auditLogRepo.findFiltered(
                nil(actor), nil(eventType), nil(outcome), nil(since), nil(until), anomalyOnly,
                PageRequest.of(page, size));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("data", result.getContent());
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("total_pages", result.getTotalPages());
        resp.put("timestamp", now());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/audit/stats")
    public ResponseEntity<Map<String, Object>> auditStats(HttpSession session) {
        requireAdmin(session);

        String last24h = ISO.format(Instant.now().minusSeconds(86_400));
        String last7d  = ISO.format(Instant.now().minusSeconds(7 * 86_400L));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_24h",        auditLogRepo.countEventsSince(last24h));
        stats.put("anomalies_24h",    auditLogRepo.countAnomaliesSince(last24h));
        stats.put("failed_logins_24h",auditLogRepo.countFailedLoginsSince(last24h));
        stats.put("total_7d",         auditLogRepo.countEventsSince(last7d));
        stats.put("anomalies_7d",     auditLogRepo.countAnomaliesSince(last7d));
        stats.put("failed_logins_7d", auditLogRepo.countFailedLoginsSince(last7d));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("data", stats);
        resp.put("timestamp", now());
        return ResponseEntity.ok(resp);
    }

    private void requireAdmin(HttpSession session) {
        if (!"ADMIN".equals(session.getAttribute("systemRole")))
            throw new SecurityException("Admin access required");
    }

    private String nil(String v) { return (v == null || v.isBlank()) ? null : v; }
    private String now()         { return ISO.format(Instant.now()); }
}
