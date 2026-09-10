package com.sitemonitor.controller;

import com.sitemonitor.util.Msg;
import com.sitemonitor.model.LdapSettings;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.LdapDirectoryService;
import com.sitemonitor.service.LdapSettingsService;
import com.sitemonitor.service.PermissionService;
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
    private final PermissionService permissionService;

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireSettingsAccess(session, "settings.ldap", "edit");
        LdapSettings s = settingsService.getOrDefaults();
        return ok(Map.of(
                "data", settingsService.toClientMap(s),
                "configured", settingsService.isConfigured(),
                "secret_key_set", settingsService.isSecretKeyConfigured()));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.ldap", "edit");
        LdapSettings saved = settingsService.save(body, actor(session));
        auditService.recordAction("LDAP_SETTINGS_SAVE", session, request,
                "LDAP", "settings",
                "{\"enabled\":" + saved.getEnabled()
                        + ",\"host\":\"" + safe(saved.getHost()) + "\""
                        + ",\"port\":" + saved.getPort()
                        + ",\"useLdaps\":" + saved.getUseLdaps() + "}");
        return ok(Map.of(
                "data", settingsService.toClientMap(saved),
                "message", Msg.t("LDAP ayarları kaydedildi (yeniden başlatma gerekmez)", "LDAP settings saved (no restart needed)")));
    }

    /** {@code verify=true}: kayıtlı "doğrulamayı atla" ayarı DEĞİŞMEDEN, sertifika doğrulaması açık
     *  bir bağlantı denenir — admin ayarı kapatmadan önce güvenle sınayabilsin diye. */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @RequestParam(name = "verify", defaultValue = "false") boolean verify,
            HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.ldap", "edit");
        Map<String, Object> result = directoryService.testConnection(verify);
        auditService.recordAction("LDAP_TEST", session, request,
                "LDAP", "test", "{\"success\":" + result.get("success") + ",\"verify\":" + verify + "}");
        Map<String, Object> resp = new LinkedHashMap<>(result);
        resp.putIfAbsent("timestamp", now());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/query-user")
    public ResponseEntity<Map<String, Object>> queryUser(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.ldap", "edit");
        // Accept {value, attr}; fall back to legacy {username}.
        Object rawVal = body.get("value") != null ? body.get("value") : body.get("username");
        String value = rawVal != null ? rawVal.toString().trim() : "";
        String attr = body.get("attr") != null ? body.get("attr").toString().trim() : null;
        auditService.recordAction("LDAP_QUERY_USER", session, request,
                "LDAP", value, attr != null ? "{\"attr\":\"" + attr + "\"}" : null);
        try {
            Map<String, Object> result = directoryService.queryUser(value, attr);
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

    /** Konfigüre bootstrap admin (site.monitor.username) HER ZAMAN erişir (kilitlenme-güvenli fallback —
     *  login'de set edilen 'bootstrapAdmin' bayrağı); aksi halde matris izni (settings.ldap/edit). */
    private void requireSettingsAccess(HttpSession session, String key, String action) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        SessionScope.requireNotScopedAdmin(session, key);   // kapsamlı müdür (AD ADMIN) geçemez
        permissionService.require(session, key, action);
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
