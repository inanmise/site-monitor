package com.sitemonitor.controller;

import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.ScriptedFileReadAudit;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sentetik betik güvenliği — dosya okuma raporu (2026-10-01, onaylı öneri 1).
 *
 * <p>{@code GET /api/admin/scripted/file-read-report}: dosya okuyan mevcut betikler ve geçerli politika. Global yönetici
 * (kapsamlı müdür değil) + {@code system_health.read}. Salt okunur.
 */
@RestController
@RequestMapping("/api/admin/scripted")
@RequiredArgsConstructor
public class ScriptedSecurityController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final String PERMISSION = "system_health.read";

    private final ScriptedFileReadAudit audit;
    private final PermissionService permissionService;

    @GetMapping("/file-read-report")
    public ResponseEntity<Map<String, Object>> fileReadReport(HttpSession session) {
        SessionScope.requireNotScopedAdmin(session, PERMISSION);
        permissionService.require(session, PERMISSION, "view");
        List<ScriptedFileReadAudit.Finding> findings = audit.scan();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("policy", audit.policy());
        body.put("count", findings.size());
        body.put("data", findings.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id());
            m.put("name", f.name());
            m.put("team_id", f.teamId());
            m.put("active", f.active());
            m.put("hits", f.hits());
            return m;
        }).toList());
        body.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(body);
    }
}
