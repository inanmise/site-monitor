package com.sitemonitor.controller;

import com.sitemonitor.util.Msg;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SecretRotationService;
import com.sitemonitor.service.SecretToolsService;
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
 * Admin-only Settings → Anahtar Çözümleme. Verilen aday SITE_MONITOR_SECRET_KEY ile DB'de şifreli
 * duran alanları (SMTP/LDAP parolaları) çözüp gösterir — anahtar kurtarma/doğrulama için. Yalnız
 * local bootstrap admin (username "admin"). Hassas: audit alınır; anahtar/plaintext loglanmaz.
 *
 * <p><b>Anahtar döndürme (2026-10-11):</b> {@code GET /rotation} şifreli değerlerin hangi anahtarda durduğunu sayar,
 * {@code POST /rotation/reencrypt} önceki anahtardakileri güncel anahtarla yeniden yazar ({@link SecretRotationService}).
 * Aynı kapı (settings.secrets/execute; kapsamlı müdür 403). Denetim {@code SECRET_REENCRYPT} yalnız sayıları taşır.
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
    private final SecretRotationService rotation;
    private final SecretCipher cipher;

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(HttpSession session) {
        requireSettingsAccess(session, "settings.secrets", "execute");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dev_default_key", service.devDefaultKey());
        body.put("secret_key_set", service.isSecretKeyConfigured());
        body.put("previous_key_set", cipher.isPreviousKeyConfigured());
        return ok(body);
    }

    /** Döndürme durumu: anahtar yuvaları + konum başına güncel / önceki / okunamayan değer sayısı. Yazma yok. */
    @GetMapping("/rotation")
    public ResponseEntity<Map<String, Object>> rotationStatus(HttpSession session) {
        requireSettingsAccess(session, "settings.secrets", "execute");
        return ok(Map.of("data", rotation.status()));
    }

    /**
     * Önceki anahtarla duran değerleri güncel anahtarla yeniden yazar. Gövde {@code {"confirm": true}} ister (yanlışlıkla
     * tetiklenmesin). Ret → 409 + kod (SECRET_KEY_NOT_CONFIGURED / PREVIOUS_KEY_NOT_CONFIGURED / REENCRYPT_RUNNING).
     */
    @PostMapping("/rotation/reencrypt")
    public ResponseEntity<Map<String, Object>> reencrypt(@RequestBody(required = false) Map<String, Object> body,
                                                         HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.secrets", "execute");
        Object confirm = body == null ? null : body.get("confirm");
        if (!Boolean.TRUE.equals(confirm) && !"true".equals(String.valueOf(confirm))) {
            throw new IllegalArgumentException(Msg.t(
                    "Yeniden şifreleme onayı eksik: isteği onay kutusuyla gönderin.",
                    "Re-encryption confirmation is missing: send the request with the confirmation."));
        }
        String actor = session == null ? null : String.valueOf(session.getAttribute("username"));
        Map<String, Object> result;
        try {
            result = rotation.reencrypt(actor);
        } catch (SecretRotationService.RotationRefused e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", e.code());
            err.put("error", e.getMessage());
            err.put("timestamp", ISO.format(Instant.now()));
            return ResponseEntity.status(409).body(err);
        }
        // Yalnız sayılar — değer, anahtar ya da kayıt içeriği denetime yazılmaz
        auditService.recordAction("SECRET_REENCRYPT", session, request, "SETTINGS", "secret-tools",
                "{\"values\":" + result.get("values_rewritten") + ",\"rows\":" + result.get("rows_rewritten")
                        + ",\"changed_meanwhile\":" + result.get("changed_meanwhile")
                        + ",\"unreadable\":" + result.get("unreadable") + "}");
        return ok(Map.of("data", result));
    }

    @PostMapping("/decrypt")
    public ResponseEntity<Map<String, Object>> decrypt(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session, "settings.secrets", "execute");
        String key = body.get("key") == null ? "" : String.valueOf(body.get("key")).trim();
        if (key.isEmpty()) throw new IllegalArgumentException(Msg.t("Çözümleme için anahtar gerekli", "A key is required to decrypt"));
        List<Map<String, Object>> data = service.decryptWithKey(key);
        long ok = data.stream().filter(d -> Boolean.TRUE.equals(d.get("ok"))).count();
        // Anahtar veya çözülen plaintext ASLA loglanmaz — yalnız alan sayısı + başarı adedi.
        auditService.recordAction("SECRET_DECRYPT", session, request,
                "SETTINGS", "secret-tools", "{\"fields\":" + data.size() + ",\"ok\":" + ok + "}");
        return ok(Map.of("data", data));
    }

    /** Konfigüre bootstrap admin (site.monitor.username) HER ZAMAN erişir (kilitlenme-güvenli fallback —
     *  login'de set edilen 'bootstrapAdmin' bayrağı); aksi halde matris izni (settings.secrets/execute). */
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
