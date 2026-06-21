package com.certmonitor.controller;

import com.certmonitor.service.DatabaseInfoService;
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
    private final com.certmonitor.service.PermissionService permissionService;

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(HttpSession session) {
        requireSettingsAccess(session, "settings.database", "view");
        return ok(Map.of("data", service.getInfo()));
    }

    /** Bootstrap admin ("admin") HER ZAMAN erişir (fallback); aksi halde matris izni (settings.database/view). */
    private void requireSettingsAccess(HttpSession session, String key, String action) {
        Object u = session != null ? session.getAttribute("username") : null;
        if ("admin".equals(u)) return;
        permissionService.require(session, key, action);
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }
}
