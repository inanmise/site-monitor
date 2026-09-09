package com.sitemonitor.controller;

import com.sitemonitor.service.DatabaseInfoService;
import com.sitemonitor.service.PermissionService;
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

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(HttpSession session) {
        requireSettingsAccess(session, "settings.database", "view");
        return ok(Map.of("data", service.getInfo()));
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
