package com.certmonitor.controller;

import com.certmonitor.service.AppSettingsService;
import com.certmonitor.service.AuditService;
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
 * Admin-only Settings → Genel Ayarlar. Küratörlü, tipli config'leri (key/value) okur/yazar;
 * değişiklik {@link AppSettingsService} ile CANLI yansır (yeniden başlatma gerekmez).
 * LDAP/SMTP ile aynı gate: yalnız local bootstrap admin (username == "admin").
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/general")
@RequiredArgsConstructor
public class GeneralSettingsController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppSettingsService settingsService;
    private final AuditService auditService;
    private final com.certmonitor.service.PermissionService permissionService;

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireSettingsAccess(session, "settings.general", "edit");
        return ok(Map.of("data", settingsService.getCatalogForClient()));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.general", "edit");
        settingsService.save(body, actor(session));
        auditService.recordAction("GENERAL_SETTINGS_SAVE", session, request,
                "SETTINGS", "general", "{\"keys\":" + (body.get("values") != null
                        ? ((Map<?, ?>) body.get("values")).keySet().size() : 0) + "}");
        return ok(Map.of(
                "data", settingsService.getCatalogForClient(),
                "message", "Ayarlar kaydedildi (yeniden başlatma gerekmez)"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Konfigüre bootstrap admin (cert.monitor.username) HER ZAMAN erişir (kilitlenme-güvenli fallback —
     *  login'de set edilen 'bootstrapAdmin' bayrağı); aksi halde matris izni (settings.general/edit). */
    private void requireSettingsAccess(HttpSession session, String key, String action) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        permissionService.require(session, key, action);
    }

    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }
}
