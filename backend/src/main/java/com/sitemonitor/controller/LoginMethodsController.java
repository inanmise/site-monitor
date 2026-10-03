package com.sitemonitor.controller;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.LdapSettingsService;
import com.sitemonitor.service.otp.LoginMethodsService;
import com.sitemonitor.service.otp.OtpCodes;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Giriş Yöntemleri (2026-10-02, kullanıcı isteği) — Ayarlar → Güvenlik → "Giriş Yöntemleri".
 *
 * <ul>
 *   <li>{@code GET /api/public/login-methods} — giriş sayfası (OTURUMSUZ, {@code AuthInterceptor} PUBLIC): yalnız
 *       yapılandırma {@code {ldap, otp_push, otp_email, push_ttl, email_ttl, resend_cooldown}} — kişi / IP / sayaç YOK.
 *       {@code /api} yanıtları genel {@code no-store} başlığını taşır (WebConfig). Yolda "status" geçmez (Durum sayfası
 *       kapısı: PUBLIC yolları "status" içeremez).</li>
 *   <li>{@code GET /api/admin/login-methods} — ayarlar, sınırlar, kanal durumları (push ağ geçidi, SMTP, LDAP entegrasyonu,
 *       gizli anahtar), giriş ekranı önizleme verisi, kişi bilgisi kapsamı ({@code coverage}: aktif kullanıcı / kayıtlı
 *       telefonu / e-postası olan — 2026-10-03) ve son 20 kodla giriş olayı.</li>
 *   <li>{@code PUT /api/admin/login-methods} — kayıt; sunucu aralık doğrulaması alan adıyla döner (400 + {@code field},
 *       arayüz alanın altında gösterir). Denetim {@code LOGIN_METHODS_SETTINGS_SAVE} + alan farkı.</li>
 * </ul>
 * <b>Kapı:</b> yönetim uçlarının TAMAMI (okuma dahil) YALNIZ global yönetici — kapsamlı müdür, TEAM_ADMIN, AUDIT ve USER
 * 403 (anahtarlar ayrıca {@code AppSettingsCatalog.GLOBAL_ONLY}: hangi denetleyiciden gelirse gelsin müdür yazamaz).
 */
@RestController
@RequiredArgsConstructor
public class LoginMethodsController {

    /** Son etkinlik listesinin boyu. */
    static final int ACTIVITY_SIZE = 20;

    /** Etkinlik listesinin olay türleri (+ kodla yapılmış LOGIN ve doğru koddan sonra reddedilen LOGIN_FAILED, sorguda). */
    public static final List<String> OTP_EVENT_TYPES = List.of(
            "LOGIN_OTP_REQUESTED", "LOGIN_OTP_DELIVERY_FAILED", "LOGIN_OTP_VERIFY_FAILED",
            "LOGIN_OTP_EXPIRED", "LOGIN_OTP_LOCKED");

    /** Telde alan adı → ayar anahtarı (kayıt gövdesi ve görünüm aynı adları kullanır). */
    static final Map<String, String> FIELDS = linked(
            "ldap_enabled", LoginMethodsService.KEY_LDAP,
            "push_enabled", LoginMethodsService.KEY_PUSH_ENABLED,
            "email_enabled", LoginMethodsService.KEY_EMAIL_ENABLED,
            "push_ttl_seconds", LoginMethodsService.KEY_PUSH_TTL,
            "email_ttl_seconds", LoginMethodsService.KEY_EMAIL_TTL,
            "max_attempts", LoginMethodsService.KEY_MAX_ATTEMPTS,
            "resend_cooldown_seconds", LoginMethodsService.KEY_COOLDOWN,
            "max_requests_per_user", LoginMethodsService.KEY_MAX_PER_USER,
            "max_requests_per_ip", LoginMethodsService.KEY_MAX_PER_IP,
            "max_failed_verifications", LoginMethodsService.KEY_MAX_FAILED,
            "allow_global_admins", LoginMethodsService.KEY_ALLOW_GLOBAL_ADMINS,
            // 2026-10-03: kişi bilgisi doğrulaması (telefon / e-posta da sorulsun) + eşleşmeme sınırı
            "push_require_phone", LoginMethodsService.KEY_PUSH_REQUIRE_PHONE,
            "email_require_email", LoginMethodsService.KEY_EMAIL_REQUIRE_EMAIL,
            "max_contact_mismatches", LoginMethodsService.KEY_MAX_CONTACT_MISMATCHES);

    private final LoginMethodsService methods;
    private final AppSettingsService settingsService;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepo;
    private final OtpCodes otpCodes;

    /** LDAP entegrasyonu (dizin) açık mı — bilgi amaçlı; isteğe bağlı (dilim testlerinde yok). */
    @Autowired(required = false)
    private LdapSettingsService ldapSettings;

    // ── Giriş sayfası (PUBLIC) ──────────────────────────────────────────────────

    @GetMapping("/api/public/login-methods")
    public ResponseEntity<Map<String, Object>> publicMethods() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.putAll(methods.publicView());
        return ResponseEntity.ok(body);
    }

    // ── Yönetim (yalnız global yönetici) ────────────────────────────────────────

    @GetMapping("/api/admin/login-methods")
    public ResponseEntity<Map<String, Object>> get(HttpSession session) {
        requireGlobalAdmin(session);
        return ok(view(session), null);
    }

    @PutMapping("/api/admin/login-methods")
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        requireGlobalAdmin(session);
        Map<String, Object> in = body == null ? Map.of() : body;
        @SuppressWarnings("unchecked")
        Map<String, Object> src = in.get("settings") instanceof Map<?, ?> m ? (Map<String, Object>) m : in;

        // Önce HEPSİ doğrulanır (alan adıyla), sonra tek kayıt — yarım kayıt yok.
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> before = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : FIELDS.entrySet()) {
            if (!src.containsKey(f.getKey())) continue;
            Object raw = src.get(f.getKey());
            String key = f.getValue();
            LoginMethodsService.Range range = LoginMethodsService.RANGES.get(key);
            String val;
            if (range == null) {
                val = String.valueOf(asBool(f.getKey(), raw));
            } else {
                int n = asInt(f.getKey(), raw);
                if (n < range.min() || n > range.max()) {
                    throw new FieldException(f.getKey(), Msg.t(
                            "Değer " + range.min() + "–" + range.max() + " aralığında olmalı",
                            "The value must be between " + range.min() + " and " + range.max()));
                }
                val = String.valueOf(n);
            }
            values.put(key, val);
            before.put(key, settingsService.getString(key, null));
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(Msg.t("Kaydedilecek ayar yok.", "There is nothing to save."));
        }
        settingsService.save(Map.of("values", values), actor(session));
        auditService.recordAction("LOGIN_METHODS_SETTINGS_SAVE", session, request, "SETTINGS", "login-methods",
                AuditDetail.of("keys", values.size(), "ldap_enabled", methods.ldapLoginEnabled(),
                        "otp_push", methods.pushEnabled(), "otp_email", methods.emailEnabled()),
                AuditDiff.diff(before, values));
        return ok(view(session), Msg.t("Giriş yöntemleri kaydedildi (yeniden başlatma gerekmez)", "Sign-in methods saved (no restart needed)"));
    }

    /** Alan-bazlı doğrulama hatası → 400 + {@code field}. */
    @ExceptionHandler(FieldException.class)
    public ResponseEntity<Map<String, Object>> fieldError(FieldException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", e.getMessage());
        body.put("field", e.field());
        return ResponseEntity.status(400).body(body);
    }

    /** Alan adlı doğrulama hatası. */
    public static class FieldException extends IllegalArgumentException {
        private final String field;
        public FieldException(String field, String message) { super(message); this.field = field; }
        public String field() { return field; }
    }

    // ── Görünüm ─────────────────────────────────────────────────────────────────

    private Map<String, Object> view(HttpSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settings", methods.settingsView());
        data.put("limits", LoginMethodsService.limitsView());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("push_gateway_configured", methods.pushGatewayConfigured());
        status.put("smtp", methods.smtpStatus());
        status.put("ldap_integration_enabled", ldapIntegrationEnabled());
        status.put("secret_key_ephemeral", otpCodes.ephemeralKey());
        data.put("status", status);
        data.put("public", methods.publicView());   // giriş ekranı önizlemesi sunucunun gerçek kararından çizilir
        // Kişi bilgisi kapsamı (2026-10-03): {active_users, with_phone, with_email} — TEK toplu sorgu, kişi bilgisi yok.
        data.put("coverage", methods.contactCoverage());
        // Kimlik izi (IP) diğer uçlardaki gibi TEK kaynaktan maskelenir (IdentityMaskGateTest): uç bugün yalnız global
        // yöneticiye açık olduğundan değer değişmez; yetki ileride gevşerse IP'ler kendiliğinden düşer.
        Object user = session != null ? session.getAttribute("username") : null;
        Map<String, Object> masked = IdentityMask.apply(Map.of("items", activity()), IdentityMask.visibleTo(session),
                user != null ? user.toString() : null);
        data.put("activity", masked.get("items"));
        data.put("activity_types", OTP_EVENT_TYPES);
        return data;
    }

    /** Son kodla giriş olayları (ham) — çağıran IdentityMask'ten geçirir; KOD hiçbir satırda yoktur. */
    private List<Map<String, Object>> activity() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (AuditLog a : auditLogRepo.findRecentOtpActivity(OTP_EVENT_TYPES, PageRequest.of(0, ACTIVITY_SIZE))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getId());
                m.put("event_type", a.getEventType());
                m.put("event_time", a.getEventTime());
                m.put("actor", a.getActor());
                m.put("outcome", a.getOutcome());
                m.put("ip_address", a.getIpAddress());
                m.put("failure_reason", a.getFailureReason());
                m.put("detail", a.getDetail());
                out.add(m);
            }
        } catch (Exception e) {
            // Etkinlik listesi okunamazsa sayfa yine açılır (boş liste).
        }
        return out;
    }

    private boolean ldapIntegrationEnabled() {
        try {
            var s = ldapSettings == null ? null : ldapSettings.getOrDefaults();
            return s != null && Boolean.TRUE.equals(s.getEnabled());
        } catch (Exception e) {
            return false;
        }
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────

    static void requireGlobalAdmin(HttpSession session) {
        SessionScope.requireNotScopedAdmin(session, "login-methods");
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Giriş yöntemleri yalnız global yöneticiye açıktır.",
                    "Sign-in methods are available to global administrators only."));
        }
    }

    private static boolean asBool(String field, Object v) {
        if (v instanceof Boolean b) return b;
        if (v != null) {
            String s = v.toString().trim();
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        throw new FieldException(field, Msg.t("Açık/kapalı değeri bekleniyor", "An on/off value is expected"));
    }

    private static int asInt(String field, Object v) {
        if (v instanceof Number n) {
            if (n.doubleValue() != Math.rint(n.doubleValue())) {
                throw new FieldException(field, Msg.t("Tam sayı girin", "Enter a whole number"));
            }
            return n.intValue();
        }
        try {
            return Integer.parseInt(v == null ? "" : v.toString().trim());
        } catch (NumberFormatException e) {
            throw new FieldException(field, Msg.t("Tam sayı girin", "Enter a whole number"));
        }
    }

    private static String actor(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? "anonymous" : u.toString();
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        if (message != null) body.put("message", message);
        return ResponseEntity.ok(body);
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
