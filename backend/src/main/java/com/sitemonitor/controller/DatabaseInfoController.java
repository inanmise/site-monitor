package com.sitemonitor.controller;

import com.sitemonitor.service.DatabaseHealthService;
import com.sitemonitor.service.DatabaseInfoService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only Settings → Veritabanı Bilgileri. Bağlı PostgreSQL örneğine ait salt-okunur
 * temel meta verileri (db adı, kullanıcı, host/port, sürüm, boyut, havuz) döndürür. Yalnız
 * local bootstrap admin (username "admin") — diğer Settings sayfalarıyla aynı kapı.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/database")
@RequiredArgsConstructor
public class DatabaseInfoController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DatabaseInfoService service;
    private final PermissionService permissionService;

    /**
     * Dış izleme ucunun ({@code GET /api/public/health/db}) AYNI sonucu (2026-10-09, Ayarlar → Veritabanı yeniden
     * tasarımı): sayfa ayrı bir istek atmadan durumu (UP/DEGRADED/DOWN), sorgu/bağlantı süresini, yazılabilirliği,
     * havuz ve şema denetimini gösterir. Pod başına 5 sn önbellekli, 4 sn sınırlı — ek yük yok. Dilim testlerinde bean
     * yoksa blok eklenmez (davranış öncekiyle aynı).
     */
    @Autowired(required = false)
    private DatabaseHealthService databaseHealth;

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(HttpSession session) {
        requireSettingsAccess(session, "settings.database", "view");
        Map<String, Object> data = new LinkedHashMap<>(service.getInfo());
        Map<String, Object> health = healthBlock();
        if (health != null) data.put("health", health);
        return ok(Map.of("data", data));
    }

    /** Sağlık özeti — alınamazsa null (sayfanın geri kalanı yine gösterilir; istisna metni istemciye gitmez). */
    private Map<String, Object> healthBlock() {
        if (databaseHealth == null) return null;
        try {
            DatabaseHealthService.Snapshot s = databaseHealth.current();
            return s == null || s.body() == null ? null : s.body();
        } catch (Exception e) {
            log.debug("Veritabanı sağlık özeti alınamadı: {}", e.toString());
            return null;
        }
    }

    /** Konfigüre bootstrap admin (site.monitor.username) HER ZAMAN erişir (kilitlenme-güvenli fallback —
     *  login'de set edilen 'bootstrapAdmin' bayrağı); aksi halde matris izni (settings.database/view). */
    private void requireSettingsAccess(HttpSession session, String key, String action) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        SessionScope.requireNotScopedAdmin(session, key);   // kapsamlı müdür (AD ADMIN) geçemez
        permissionService.require(session, key, action);
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }
}
