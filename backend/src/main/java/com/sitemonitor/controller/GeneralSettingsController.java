package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
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
    private final PermissionService permissionService;

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireSettingsAccess(session, "settings.general", "edit");
        return ok(Map.of("data", settingsService.getCatalogForClient()));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.general", "edit");
        // Eskiden yalnız anahtar SAYISI yazılıyordu ("3 ayar değişti") — hangi ayarın neyden neye
        // geçtiği hiçbir yerde yoktu. Değerler save'DEN ÖNCE okunmalı: AppSettingsService.save
        // void döner ve override önbelleğini anında tazeler.
        @SuppressWarnings("unchecked")
        Map<String, Object> values = body.get("values") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        for (String k : values.keySet()) before.put(k, settingsService.getString(k, null));

        settingsService.save(body, actor(session));

        // Hassas anahtarların değeri AuditDiff tarafından maskelenir (tek kara-liste).
        auditService.recordAction("GENERAL_SETTINGS_SAVE", session, request,
                "SETTINGS", "general", AuditDetail.of("keys", values.size()),
                AuditDiff.diff(before, values));
        return ok(Map.of(
                "data", settingsService.getCatalogForClient(),
                "message", "Ayarlar kaydedildi (yeniden başlatma gerekmez)"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Konfigüre bootstrap admin (site.monitor.username) HER ZAMAN erişir (kilitlenme-güvenli fallback —
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
