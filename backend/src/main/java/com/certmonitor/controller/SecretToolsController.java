package com.certmonitor.controller;

import com.certmonitor.service.AuditService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.SecretToolsService;
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
import java.util.List;
import java.util.Map;

/**
 * Admin-only Settings → Anahtar Çözümleme. Verilen aday CERT_MONITOR_SECRET_KEY ile DB'de şifreli
 * duran alanları (SMTP/LDAP parolaları) çözüp gösterir — anahtar kurtarma/doğrulama için. Yalnız
 * local bootstrap admin (username "admin"). Hassas: audit alınır; anahtar/plaintext loglanmaz.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/secret-tools")
@RequiredArgsConstructor
public class SecretToolsController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final SecretToolsService service;
    private final AuditService auditService;
    private final PermissionService permissionService;

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(HttpSession session) {
        requireSettingsAccess(session, "settings.secrets", "execute");
        return ok(Map.of(
                "dev_default_key", service.devDefaultKey(),
                "secret_key_set", service.isSecretKeyConfigured()));
    }

    @PostMapping("/decrypt")
    public ResponseEntity<Map<String, Object>> decrypt(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.secrets", "execute");
        String key = body.get("key") == null ? "" : String.valueOf(body.get("key")).trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Çözümleme için anahtar gerekli");
        List<Map<String, Object>> data = service.decryptWithKey(key);
        long ok = data.stream().filter(d -> Boolean.TRUE.equals(d.get("ok"))).count();
        // Anahtar veya çözülen plaintext ASLA loglanmaz — yalnız alan sayısı + başarı adedi.
        auditService.recordAction("SECRET_DECRYPT", session, request,
                "SETTINGS", "secret-tools", "{\"fields\":" + data.size() + ",\"ok\":" + ok + "}");
        return ok(Map.of("data", data));
    }

    /** Konfigüre bootstrap admin (cert.monitor.username) HER ZAMAN erişir (kilitlenme-güvenli fallback —
     *  login'de set edilen 'bootstrapAdmin' bayrağı); aksi halde matris izni (settings.secrets/execute). */
    private void requireSettingsAccess(HttpSession session, String key, String action) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        permissionService.require(session, key, action);
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }
}
