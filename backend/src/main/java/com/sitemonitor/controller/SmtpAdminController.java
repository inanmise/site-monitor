package com.sitemonitor.controller;

import com.sitemonitor.util.Msg;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.SmtpMailService;
import com.sitemonitor.service.SmtpSettingsService;
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
 * Admin-only Settings → SMTP / outbound mail.
 *
 * <p>Gated to the local bootstrap admin (username == "admin"). All mutations are
 * audited. Phase 1: view/edit the (live-seeded) mail config + test it; real
 * notifications still use the env-configured sender until activation.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/smtp")
@RequiredArgsConstructor
public class SmtpAdminController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final SmtpSettingsService settingsService;
    private final SmtpMailService mailService;
    private final AuditService auditService;
    private final PermissionService permissionService;

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireSettingsAccess(session, "settings.smtp", "edit");
        SmtpSettings s = settingsService.getOrDefaults();
        return ok(Map.of(
                "data", settingsService.toClientMap(s),
                "configured", settingsService.isConfigured(),
                "secret_key_set", settingsService.isSecretKeyConfigured()));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.smtp", "edit");
        SmtpSettings saved = settingsService.save(body, actor(session));
        auditService.recordAction("SMTP_SETTINGS_SAVE", session, request,
                "SMTP", "settings",
                "{\"enabled\":" + saved.getEnabled()
                        + ",\"host\":\"" + safe(saved.getHost()) + "\""
                        + ",\"port\":" + saved.getPort() + "}");
        return ok(Map.of(
                "data", settingsService.toClientMap(saved),
                "message", Msg.t("SMTP ayarları kaydedildi (yeniden başlatma gerekmez)", "SMTP settings saved (no restart needed)")));
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.smtp", "edit");
        Map<String, Object> result = mailService.testConnection();
        auditService.recordAction("SMTP_TEST", session, request,
                "SMTP", "test", "{\"success\":" + result.get("success") + "}");
        Map<String, Object> resp = new LinkedHashMap<>(result);
        resp.putIfAbsent("timestamp", now());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/test-email")
    public ResponseEntity<Map<String, Object>> sendTest(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.smtp", "edit");
        String recipient = body.get("recipient") != null ? body.get("recipient").toString().trim() : "";
        Map<String, Object> result = mailService.sendTest(recipient);
        auditService.recordAction("SMTP_TEST_EMAIL", session, request,
                "SMTP", recipient, "{\"success\":" + result.get("success") + "}");
        Map<String, Object> resp = new LinkedHashMap<>(result);
        resp.putIfAbsent("timestamp", now());
        return ResponseEntity.ok(resp);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Yerel bootstrap admin ("admin") HER ZAMAN erişir (güvenlik fallback'i); aksi halde matris
     *  izni gerekir (ör. settings.smtp/edit). Böylece SMTP ayarları yetkilendirilebilir olur. */
    private void requireSettingsAccess(HttpSession session, String key, String action) {
        // Konfigüre bootstrap admin (site.monitor.username) HER ZAMAN erişir — login'de set edilen
        // 'bootstrapAdmin' bayrağı (literal "admin" değil → admin yeniden adlandırılırsa kilitlenmez,
        // "admin" adlı başka kullanıcı bypass alamaz). Aksi halde matris izni gerekir.
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
}
