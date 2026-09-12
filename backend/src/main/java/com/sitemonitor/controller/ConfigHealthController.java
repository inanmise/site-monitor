package com.sitemonitor.controller;

import com.sitemonitor.service.ConfigHealthService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Yapılandırma sağlığı kartı (2026-09-12, #25) — Ayarlar sayfasının üstü. Yalnız global admin (ayar yüzeyi). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class ConfigHealthController {

    private final ConfigHealthService configHealthService;
    private final PermissionService permissionService;

    @GetMapping("/config-health")
    public ResponseEntity<Map<String, Object>> configHealth(HttpSession session) {
        permissionService.require(session, "settings.general", "edit");
        SessionScope.requireNotScopedAdmin(session, "settings.general");
        return ResponseEntity.ok(Map.of("success", true, "data", configHealthService.build()));
    }
}
