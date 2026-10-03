package com.sitemonitor.controller;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.LdapSettingsService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.loginstats.LoginStatsService;
import com.sitemonitor.service.otp.LoginMethodsService;
import com.sitemonitor.service.otp.OtpCodes;
import com.sitemonitor.service.otp.OtpPushTemplate;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 *       arayüz alanın altında gösterir). Denetim {@code LOGIN_METHODS_SETTINGS_SAVE} + alan farkı. 2026-10-03: push metni
 *       alanları ({@code push_title_tr|en}, {@code push_message_tr|en}) {@link OtpPushTemplate} kurallarıyla.</li>
 *   <li>{@code POST /api/admin/login-methods/push-test} — taslak push metnini yöneticinin KENDİSİNE gönderir (2026-10-03).</li>
 *   <li>{@code GET /api/admin/login-methods/stats[/users[/{username}]]} — giriş istatistikleri (kanal / sonuç / neden,
 *       kurum geneli + kullanıcı bazlı; {@link LoginStatsService}) (2026-10-03).</li>
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
            "max_contact_mismatches", LoginMethodsService.KEY_MAX_CONTACT_MISMATCHES,
            // 2026-10-03: kodla giriş push metni (TR / EN) — metin alanları (OtpPushTemplate doğrular)
            "push_title_tr", LoginMethodsService.KEY_PUSH_TITLE_TR,
            "push_title_en", LoginMethodsService.KEY_PUSH_TITLE_EN,
            "push_message_tr", LoginMethodsService.KEY_PUSH_MESSAGE_TR,
            "push_message_en", LoginMethodsService.KEY_PUSH_MESSAGE_EN);

    /** "Kendime test gönder" tavanı: yönetici başına dakikada en çok bu kadar test push'u. */
    static final int PUSH_TEST_PER_MINUTE = 3;
    /** Test push başlık öneki — telefonda gerçek giriş kodundan ayırt edilsin. */
    static final String PUSH_TEST_PREFIX = "[TEST] ";

    /** Yönetici → son test gönderim anları (ms) — bellek içi; pod yeniden başlarsa sıfırlanır (bilinçli). */
    private final Map<String, java.util.Deque<Long>> pushTestTimes = new java.util.concurrent.ConcurrentHashMap<>();

    private final LoginMethodsService methods;
    private final LoginStatsService loginStats;
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
        int pushLimit = pushLimit();
        for (Map.Entry<String, String> f : FIELDS.entrySet()) {
            if (!src.containsKey(f.getKey())) continue;
            Object raw = src.get(f.getKey());
            String key = f.getValue();
            LoginMethodsService.Range range = LoginMethodsService.RANGES.get(key);
            String val;
            if (LoginMethodsService.PUSH_TEXT_FIELDS.containsKey(f.getKey())) {
                val = pushText(f.getKey(), raw, pushLimit);
            } else if (range == null) {
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

    // ── Giriş istatistikleri (2026-10-03) — salt okuma, YALNIZ global yönetici ───────────────

    /**
     * Kurum geneli giriş istatistiği: {@code days} 1 / 7 / 30 / 90 (diğer değer → 7); {@code fresh=1} 30 sn önbelleği atlar
     * (sunucu en sık 5 sn'de bir yeniden hesaplar). IP / kişi izi TAŞIMAZ.
     */
    @GetMapping("/api/admin/login-methods/stats")
    public ResponseEntity<Map<String, Object>> stats(@RequestParam(required = false) Integer days,
                                                     @RequestParam(required = false) String fresh, HttpSession session) {
        requireGlobalAdmin(session);
        return ok(loginStats.summary(LoginStatsService.normalizeDays(days), truthy(fresh)), null);
    }

    /** Kullanıcı satırları: {@code q}, {@code channel}, {@code sort} (logins|failures|last|name), {@code page} (1-tabanlı), {@code size} (≤ 100). */
    @GetMapping("/api/admin/login-methods/stats/users")
    public ResponseEntity<Map<String, Object>> statsUsers(@RequestParam(required = false) Integer days,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(required = false) String channel,
                                                          @RequestParam(required = false) String sort,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size,
                                                          @RequestParam(required = false) String fresh,
                                                          @RequestParam(name = "export", required = false) String export,
                                                          HttpSession session) {
        requireGlobalAdmin(session);
        int d = LoginStatsService.normalizeDays(days);
        int p = page == null ? 1 : page;
        int s = size == null ? LoginStatsService.PAGE_SIZE_DEFAULT : size;
        // export=1: CSV — süzülmüş TÜM satırlar tek yanıtta (tavanlı); sayfa döngüsü istemcide YOK
        return ok(truthy(export) ? loginStats.users(d, q, channel, sort, 1, s, truthy(fresh), true)
                : loginStats.users(d, q, channel, sort, p, s, truthy(fresh)), null);
    }

    /** Tek kullanıcının giriş istatistiği + son olaylar (IP / konum / cihaz {@link IdentityMask}'ten geçer). */
    @GetMapping("/api/admin/login-methods/stats/users/{username}")
    public ResponseEntity<Map<String, Object>> statsUser(@PathVariable String username,
                                                         @RequestParam(required = false) Integer days, HttpSession session) {
        requireGlobalAdmin(session);
        Object viewer = session == null ? null : session.getAttribute("username");
        return ok(loginStats.user(username, LoginStatsService.normalizeDays(days), IdentityMask.visibleTo(session),
                viewer == null ? null : viewer.toString()), null);
    }

    private static boolean truthy(String v) {
        return v != null && ("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()));
    }

    /**
     * "Kendime test gönder" (2026-10-03) — KAYDEDİLMEMİŞ taslak ({@code {lang, title, message}}) kayıtla AYNI kurallarla
     * doğrulanır (400 + {@code field}), örnek kod {@code 123456} ile doldurulur ve YALNIZ oturumdaki yöneticinin KENDİ
     * kullanıcı adına {@code sendDirect} ile gider (başlık "[TEST] " önekli; kanal süzgeci sendDirect'te). Yönetici başına
     * dakikada {@value #PUSH_TEST_PER_MINUTE} (429). Sonuç her zaman 200 gövdesinde: {@code success} + {@code code}
     * ({@code OK} / {@code NOT_CONFIGURED} / {@code NO_TARGET} / {@code HTTP nnn} / istisna sınıfı) + {@code Msg.t} iletisi.
     * Denetim {@code LOGIN_METHODS_PUSH_TEST} (dil + sonuç; metin YOK).
     */
    @PostMapping("/api/admin/login-methods/push-test")
    public ResponseEntity<Map<String, Object>> pushTest(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpSession session, HttpServletRequest request) {
        requireGlobalAdmin(session);
        Map<String, Object> in = body == null ? Map.of() : body;
        boolean en = "en".equalsIgnoreCase(String.valueOf(in.get("lang")).trim());
        String lang = en ? "en" : "tr";
        int limit = pushLimit();
        String title = pushText("push_title_" + lang, in.get("title"), limit);
        String message = pushText("push_message_" + lang, in.get("message"), limit);
        String effTitle = title.isBlank() ? OtpPushTemplate.defaultTitle(en) : title;
        String effMessage = message.isBlank() ? OtpPushTemplate.defaultMessage(en) : message;
        String target = actor(session);

        if (!methods.pushGatewayConfigured()) {
            auditService.recordAction("LOGIN_METHODS_PUSH_TEST", session, request, "SETTINGS", "login-methods",
                    AuditDetail.of("lang", lang, "result", "NOT_CONFIGURED"));
            return pushTestResult(false, "NOT_CONFIGURED", target);
        }
        if (!allowPushTest(target)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", "RATE_LIMITED");
            err.put("retry_after", 60);
            err.put("error", Msg.t("Dakikada en çok " + PUSH_TEST_PER_MINUTE + " test bildirimi gönderilebilir. Biraz sonra yeniden deneyin.",
                    "At most " + PUSH_TEST_PER_MINUTE + " test notifications can be sent per minute. Please try again shortly."));
            return ResponseEntity.status(429).body(err);
        }
        String filled = OtpPushTemplate.fill(effMessage, OtpPushTemplate.SAMPLE_CODE,
                String.valueOf(methods.pushTtlSeconds()), OtpPushTemplate.clock(null));
        UserPushService.DirectResult r;
        try {
            r = methods.sendTestPush(target, PUSH_TEST_PREFIX + effTitle, filled);
        } catch (Exception e) {
            r = new UserPushService.DirectResult(false, null, e.getClass().getSimpleName());
        }
        boolean ok = r != null && r.ok();
        String code = ok ? "OK" : (r == null || r.error() == null || r.error().isBlank() ? "UNKNOWN" : r.error());
        auditService.recordAction("LOGIN_METHODS_PUSH_TEST", session, request, "SETTINGS", "login-methods",
                AuditDetail.of("lang", lang, "result", code, "http_status", r == null ? null : r.httpStatus(),
                        "custom_title", !title.isBlank(), "custom_message", !message.isBlank()));
        return pushTestResult(ok, code, target);
    }

    /** Push mesaj tavanı (Webhook Push ayarı; sunucu 80–320'ye kırpar) — okunamazsa kanal varsayılanı 200. */
    private int pushLimit() {
        int n = methods.pushMessageLimit();
        return n > 0 ? n : 200;
    }

    /** Yönetici başına dakikalık test tavanı — kayan pencere. */
    private boolean allowPushTest(String admin) {
        long now = System.currentTimeMillis();
        java.util.Deque<Long> q = pushTestTimes.computeIfAbsent(admin, k -> new java.util.ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= 60_000L) q.pollFirst();
            if (q.size() >= PUSH_TEST_PER_MINUTE) return false;
            q.addLast(now);
            return true;
        }
    }

    private static ResponseEntity<Map<String, Object>> pushTestResult(boolean ok, String code, String target) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", ok);
        out.put("code", code);
        out.put("target", target);
        String message;
        if (ok) {
            message = Msg.t("Test bildirimi " + target + " kullanıcısına gönderildi. Telefonunuzu kontrol edin.",
                    "Test notification sent to " + target + ". Check your phone.");
        } else if ("NOT_CONFIGURED".equals(code)) {
            message = Msg.t("Push ağ geçidi yapılandırılmamış; önce Webhook Push ayarlarında adresi girin.",
                    "The push gateway is not configured; enter its address in the Webhook Push settings first.");
        } else if ("NO_TARGET".equals(code)) {
            message = Msg.t("Hesabınızın push kimliği (kullanıcı adı) boş; test gönderilemedi.",
                    "Your account has no push identity (username); the test could not be sent.");
        } else if (code.startsWith("HTTP ")) {
            message = Msg.t("Push ağ geçidi " + code + " döndürdü; test bildirimi teslim edilmedi.",
                    "The push gateway returned " + code + "; the test notification was not delivered.");
        } else {
            message = Msg.t("Push ağ geçidine ulaşılamadı (" + code + ").", "The push gateway could not be reached (" + code + ").");
        }
        if (ok) out.put("message", message); else out.put("error", message);
        return ResponseEntity.ok(out);
    }

    /**
     * Push metni alanı: trim + {@link OtpPushTemplate} doğrulaması (hata → 400 + alan adı); yerleşik varsayılanla aynı
     * metin BOŞ kaydedilir (varsayılan ileride değişirse o geçerli olsun).
     */
    static String pushText(String field, Object raw, int limit) {
        String s = raw == null ? "" : raw.toString().strip();
        boolean title = field.startsWith("push_title_");
        boolean en = field.endsWith("_en");
        OtpPushTemplate.Problem p = title ? OtpPushTemplate.validateTitle(s) : OtpPushTemplate.validateMessage(s, limit);
        if (p != null) throw new FieldException(field, Msg.t(p.tr(), p.en()));
        String def = title ? OtpPushTemplate.defaultTitle(en) : OtpPushTemplate.defaultMessage(en);
        return s.equals(def) ? "" : s;
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
        // Push metni düzenleyicisi (2026-10-03): varsayılanlar, yer tutucular, sınırlar, kanal karakter kümesi.
        data.put("push_template", methods.pushTemplateView());
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
