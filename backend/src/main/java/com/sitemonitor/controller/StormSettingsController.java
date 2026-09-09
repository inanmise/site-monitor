package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.StormService;
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
 * Alarm fırtınası (alert storm) ayarları — "Alert Settings" bölümü. Admin (bootstrap admin veya
 * settings.general/edit izni) storm eşiğini/penceresini/scope'unu okur ve yazar; değişiklik
 * {@link AppSettingsService} ile CANLI yansır (yeniden başlatma gerekmez). Sunucu-tarafı aralık
 * doğrulaması (unit/threshold/window) yapılır; kalıcılık aynı {@code app_settings} tablosuna
 * {@code site.monitor.storm.*} key'leriyle gider.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring/storm")
@RequiredArgsConstructor
public class StormSettingsController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppSettingsService settingsService;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final StormService stormService;

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireSettingsAccess(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled",         settingsService.getBoolean(StormService.KEY_ENABLED, true));
        data.put("threshold_unit",  settingsService.getString(StormService.KEY_UNIT, "COUNT"));
        data.put("threshold_value", settingsService.getInt(StormService.KEY_VALUE, 5));
        data.put("window_minutes",  settingsService.getInt(StormService.KEY_WINDOW, 5));
        data.put("per_group",       settingsService.getBoolean(StormService.KEY_PER_GROUP, false));
        // UI önizlemesi: yüzde → yaklaşık monitör sayısı gösterebilsin.
        data.put("total_active_monitors", stormService.totalActiveMonitors());
        data.put("effective_threshold",   stormService.computeThreshold());
        return ok(Map.of("data", data));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session);

        boolean enabled  = asBool(body.get("enabled"), true);
        String  unit     = asStr(body.get("threshold_unit"), "COUNT").toUpperCase();
        int     value    = asInt(body.get("threshold_value"), 5);
        int     window   = asInt(body.get("window_minutes"), 5);
        boolean perGroup = asBool(body.get("per_group"), false);

        // ── Sunucu-tarafı aralık doğrulaması ──
        if (!"COUNT".equals(unit) && !"PERCENT".equals(unit)) {
            throw new IllegalArgumentException("Geçersiz eşik birimi: COUNT veya PERCENT olmalı");
        }
        if ("PERCENT".equals(unit)) {
            if (value < 1 || value > 100) throw new IllegalArgumentException("Yüzde eşiği 1–100 aralığında olmalı");
        } else {
            if (value < 2) throw new IllegalArgumentException("Sayı eşiği en az 2 olmalı (1'lik storm anlamsız)");
        }
        if (window < 1 || window > 15) throw new IllegalArgumentException("Zaman penceresi 1–15 dakika aralığında olmalı");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(StormService.KEY_ENABLED,   String.valueOf(enabled));
        values.put(StormService.KEY_UNIT,      unit);
        values.put(StormService.KEY_VALUE,     String.valueOf(value));
        values.put(StormService.KEY_WINDOW,    String.valueOf(window));
        values.put(StormService.KEY_PER_GROUP, String.valueOf(perGroup));
        settingsService.save(Map.of("values", values), actor(session));

        auditService.recordAction("STORM_SETTINGS_SAVE", session, request, "SETTINGS", "storm",
                "{\"enabled\":" + enabled + ",\"unit\":\"" + unit + "\",\"value\":" + value
                        + ",\"window\":" + window + ",\"per_group\":" + perGroup + "}");

        return getSettings(session);   // güncel efektif değerleri (effective_threshold dahil) geri döndür
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void requireSettingsAccess(HttpSession session) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        SessionScope.requireNotScopedAdmin(session, "settings.general");   // kapsamlı müdür (AD ADMIN) geçemez
        permissionService.require(session, "settings.general", "edit");
    }

    private String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "anonymous";
    }

    private static boolean asBool(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        return Boolean.parseBoolean(v.toString().trim());
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return def;
        try { return Integer.parseInt(v.toString().trim()); }
        catch (Exception e) { throw new IllegalArgumentException("Sayısal değer bekleniyor: " + v); }
    }

    private static String asStr(Object v, String def) {
        return v != null ? v.toString().trim() : def;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }
}
