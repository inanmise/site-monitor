package com.certmonitor.controller;

import com.certmonitor.model.LdapSettings;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.LdapDirectoryService;
import com.certmonitor.service.LdapSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only Settings → LDAP / Active Directory.
 *
 * <p>Gated to the local bootstrap admin (username == "admin") specifically — not
 * merely the ADMIN role — per the requirement that only that account manages
 * application-wide settings. All mutations are audited; changes apply live
 * (no restart) via {@link LdapSettingsService}.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ldap")
@RequiredArgsConstructor
public class LdapAdminController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final LdapSettingsService settingsService;
    private final LdapDirectoryService directoryService;
    private final AuditService auditService;

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireBootstrapAdmin(session);
        LdapSettings s = settingsService.getOrDefaults();
        return ok(Map.of(
                "data", settingsService.toClientMap(s),
                "configured", settingsService.isConfigured()));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireBootstrapAdmin(session);
        LdapSettings saved = settingsService.save(body, actor(session));
        auditService.recordAction("LDAP_SETTINGS_SAVE", session, request,
                "LDAP", "settings",
                "{\"enabled\":" + saved.getEnabled()
                        + ",\"host\":\"" + safe(saved.getHost()) + "\""
                        + ",\"port\":" + saved.getPort()
                        + ",\"useLdaps\":" + saved.getUseLdaps() + "}");
        return ok(Map.of(
                "data", settingsService.toClientMap(saved),
                "message", "LDAP ayarları kaydedildi (yeniden başlatma gerekmez)"));
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(HttpSession session, HttpServletRequest request) {
        requireBootstrapAdmin(session);
        Map<String, Object> result = directoryService.testConnection();
        auditService.recordAction("LDAP_TEST", session, request,
                "LDAP", "test", "{\"success\":" + result.get("success") + "}");
        Map<String, Object> resp = new LinkedHashMap<>(result);
        resp.putIfAbsent("timestamp", now());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/query-user")
    public ResponseEntity<Map<String, Object>> queryUser(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireBootstrapAdmin(session);
        String username = body.get("username") != null ? body.get("username").toString().trim() : "";
        auditService.recordAction("LDAP_QUERY_USER", session, request,
                "LDAP", username, null);
        try {
            Map<String, Object> result = directoryService.queryUser(username);
            return ok(Map.of("data", result));
        } catch (Exception e) {
            // Diagnostic tool: surface the failure inline rather than as a generic 500.
            Map<String, Object> body2 = new LinkedHashMap<>();
            body2.put("success", false);
            body2.put("error", rootMessage(e));
            body2.put("timestamp", now());
            return ResponseEntity.ok(body2);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Only the local bootstrap admin (username "admin") may touch app settings. */
    private void requireBootstrapAdmin(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        if (!"admin".equals(u)) {
            log.warn("Settings access denied for user={} (bootstrap admin required)", u);
            throw new SecurityException("Bu sayfaya yalnızca yönetici (admin) hesabı erişebilir");
        }
    }

    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
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

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cur.getClass().getSimpleName();
    }
}
