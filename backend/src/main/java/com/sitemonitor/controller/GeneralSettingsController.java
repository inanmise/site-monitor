package com.sitemonitor.controller;

import com.sitemonitor.util.Msg;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.BuildInfo;
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
        // Ortam adı: ham diff override'ı gösterir (boş → prod); denetim ETKİN adı da taşısın
        // (otomatik "unknown" → "prod") — sürüm penceresinde görülen değişiklik budur (2026-09-29).
        BuildInfo.EnvName envBefore = values.containsKey(BuildInfo.ENV_KEY) ? settingsService.environmentName() : null;

        settingsService.save(body, actor(session));

        String detail = AuditDetail.of("keys", values.size());
        BuildInfo.EnvName envAfter = envBefore != null ? settingsService.environmentName() : null;
        if (envAfter != null && !envAfter.name().equals(envBefore.name())) {
            detail = AuditDetail.of("keys", values.size(),
                    "environment", envBefore.name() + " → " + envAfter.name(),
                    "environmentSource", envBefore.source().wire() + " → " + envAfter.source().wire());
        }
        // Hassas anahtarların değeri AuditDiff tarafından maskelenir (tek kara-liste).
        auditService.recordAction("GENERAL_SETTINGS_SAVE", session, request,
                "SETTINGS", "general", detail,
                AuditDiff.diff(before, values));
        return ok(Map.of(
                "data", settingsService.getCatalogForClient(),
                "message", Msg.t("Ayarlar kaydedildi (yeniden başlatma gerekmez)", "Settings saved (no restart needed)")));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Konfigüre bootstrap admin (site.monitor.username) HER ZAMAN erişir (kilitlenme-güvenli fallback —
     *  login'de set edilen 'bootstrapAdmin' bayrağı); aksi halde matris izni (settings.general/edit). */
    private void requireSettingsAccess(HttpSession session, String key, String action) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        // 2026-09-10: kapsamlı müdür (AD ADMIN) artık GİRER — operasyonel ayarları düzenler.
        // GLOBAL_ONLY anahtarlar (SSRF/CA/k6/push URL/base-url/CORS/log) AppSettingsService.save'de
        // sunucu tarafında reddedilir; katalog kalemleri read_only ile UI'da kilitlenir.
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
