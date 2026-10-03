package com.sitemonitor.service.otp;

import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SmtpSettingsService;
import com.sitemonitor.service.UserPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Giriş yöntemleri ayarları (2026-10-02, kullanıcı isteği) — Ayarlar → Güvenlik → "Giriş Yöntemleri". Değerler
 * {@link AppSettingsService} üzerinden CANLI okunur (çok pod: 10 sn içinde yansır) ve sunucuda aralığa kırpılır
 * (yönetici ekranı sınır dışı değeri zaten reddeder; API'den gelen bozuk bir değer güvenlik eşiğini gevşetmesin).
 *
 * <ul>
 *   <li>{@code ldap-enabled} (vars. AÇIK): LDAP hesaplarının parola (AD bind) girişi. Mevcut LDAP entegrasyonu (dizin,
 *       eşitleme, provizyon) bu anahtardan BAĞIMSIZ. Yerel hesaplar ve kurulumdaki bootstrap admin etkilenmez.</li>
 *   <li>{@code otp.push.enabled} / {@code otp.email.enabled} (vars. KAPALI): kodla giriş yöntemleri. Push "kullanılabilir"
 *       yalnız kurumsal push ağ geçidi de yapılandırılmışsa ({@link UserPushService#gatewayConfigured()}).</li>
 *   <li>Süre (kanal başına 30–300 sn, vars. 45), challenge başına deneme (1–10, vars. 3), yeniden gönderme bekleme
 *       (10–300 sn, vars. 30), 15 dakikalık pencerede kullanıcı başına istek (vars. 5) / IP başına istek (vars. 20) /
 *       kullanıcı başına başarısız doğrulama (vars. 5), global yöneticilere kodla giriş (vars. KAPALI).</li>
 *   <li>Kişi bilgisi doğrulaması (2026-10-03, kullanıcı isteği): {@code otp.push.require-phone} /
 *       {@code otp.email.require-email} (vars. AÇIK) — istekte kayıtlı cep telefonu / e-posta da sorulur, kod yalnız
 *       kullanıcı adıyla EŞLEŞİRSE gider ({@link OtpContactMatcher}); 15 dk'da kullanıcı başına eşleşmeyen deneme sınırı
 *       {@code otp.max-contact-mismatches} (1–20, vars. 5). Anahtar KAPALIYKEN davranış birebir önceki gibidir.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LoginMethodsService {

    public static final String KEY_LDAP = "site.monitor.login.ldap-enabled";
    public static final String KEY_PUSH_ENABLED = "site.monitor.login.otp.push.enabled";
    public static final String KEY_EMAIL_ENABLED = "site.monitor.login.otp.email.enabled";
    public static final String KEY_PUSH_TTL = "site.monitor.login.otp.push.ttl-seconds";
    public static final String KEY_EMAIL_TTL = "site.monitor.login.otp.email.ttl-seconds";
    public static final String KEY_MAX_ATTEMPTS = "site.monitor.login.otp.max-attempts";
    public static final String KEY_COOLDOWN = "site.monitor.login.otp.resend-cooldown-seconds";
    public static final String KEY_MAX_PER_USER = "site.monitor.login.otp.max-requests-per-user";
    public static final String KEY_MAX_PER_IP = "site.monitor.login.otp.max-requests-per-ip";
    public static final String KEY_MAX_FAILED = "site.monitor.login.otp.max-failed-verifications";
    public static final String KEY_ALLOW_GLOBAL_ADMINS = "site.monitor.login.otp.allow-global-admins";
    /** 2026-10-03: push ile kod isteğinde kayıtlı cep telefonu da sorulsun (vars. AÇIK). */
    public static final String KEY_PUSH_REQUIRE_PHONE = "site.monitor.login.otp.push.require-phone";
    /** 2026-10-03: e-posta ile kod isteğinde kayıtlı e-posta adresi de sorulsun (vars. AÇIK). */
    public static final String KEY_EMAIL_REQUIRE_EMAIL = "site.monitor.login.otp.email.require-email";
    /** 2026-10-03: 15 dk'da kullanıcı başına eşleşmeyen kişi bilgisi sınırı — aşılınca kod isteği sessizce bastırılır. */
    public static final String KEY_MAX_CONTACT_MISMATCHES = "site.monitor.login.otp.max-contact-mismatches";

    /** Sayfanın yönettiği anahtarların TAMAMI (hepsi GLOBAL_ONLY — SettingsScopedAdminGateTest). */
    public static final List<String> KEYS = List.of(KEY_LDAP, KEY_PUSH_ENABLED, KEY_EMAIL_ENABLED, KEY_PUSH_TTL,
            KEY_EMAIL_TTL, KEY_MAX_ATTEMPTS, KEY_COOLDOWN, KEY_MAX_PER_USER, KEY_MAX_PER_IP, KEY_MAX_FAILED,
            KEY_ALLOW_GLOBAL_ADMINS, KEY_PUSH_REQUIRE_PHONE, KEY_EMAIL_REQUIRE_EMAIL, KEY_MAX_CONTACT_MISMATCHES);

    /** Sayaç pencereleri (IP / kullanıcı isteği, başarısız doğrulama) — sabit 15 dakika. */
    public static final int WINDOW_SECONDS = 15 * 60;

    /** Aralıklar — sunucu doğrulaması (kayıt) ve okuma kırpması aynı tablodan. */
    public record Range(int min, int max, int def) {
        int clamp(int v) { return Math.max(min, Math.min(max, v)); }
    }

    public static final Range TTL = new Range(30, 300, 45);
    public static final Range ATTEMPTS = new Range(1, 10, 3);
    public static final Range COOLDOWN = new Range(10, 300, 30);
    public static final Range PER_USER = new Range(1, 20, 5);
    public static final Range PER_IP = new Range(1, 500, 20);
    public static final Range FAILED = new Range(1, 20, 5);
    public static final Range CONTACT_MISMATCHES = new Range(1, 20, 5);

    /** Aralık tablosu (kayıt doğrulaması + arayüz sınırları). */
    public static final Map<String, Range> RANGES = Map.of(
            KEY_PUSH_TTL, TTL, KEY_EMAIL_TTL, TTL, KEY_MAX_ATTEMPTS, ATTEMPTS, KEY_COOLDOWN, COOLDOWN,
            KEY_MAX_PER_USER, PER_USER, KEY_MAX_PER_IP, PER_IP, KEY_MAX_FAILED, FAILED,
            KEY_MAX_CONTACT_MISMATCHES, CONTACT_MISMATCHES);

    private final AppSettingsService appSettings;
    private final UserPushService userPushService;
    private final SmtpSettingsService smtpSettings;
    private final AppUserRepository userRepo;

    // ── Anahtarlar ─────────────────────────────────────────────────────────────

    /** LDAP hesaplarının parola (AD bind) girişi açık mı (vars. AÇIK — bugünkü davranış). */
    public boolean ldapLoginEnabled() {
        return appSettings.getBoolean("site.monitor.login.ldap-enabled", true);
    }

    public boolean pushEnabled() {
        return appSettings.getBoolean("site.monitor.login.otp.push.enabled", false);
    }

    public boolean emailEnabled() {
        return appSettings.getBoolean("site.monitor.login.otp.email.enabled", false);
    }

    /** Push ağ geçidi yapılandırılmış mı — değilse push yöntemi açık olsa da kullanılamaz. */
    public boolean pushGatewayConfigured() {
        try {
            return userPushService.gatewayConfigured();
        } catch (Exception e) {
            return false;
        }
    }

    /** Push ile kod: ayar AÇIK ve ağ geçidi yapılandırılmış. */
    public boolean pushAvailable() {
        return pushEnabled() && pushGatewayConfigured();
    }

    /** E-posta ile kod: ayar AÇIK (SMTP kurulumu durum kartında gösterilir; gönderim hatası denetime düşer). */
    public boolean emailAvailable() {
        return emailEnabled();
    }

    public boolean available(LoginOtpService.Channel ch) {
        return ch == LoginOtpService.Channel.PUSH ? pushAvailable() : ch == LoginOtpService.Channel.EMAIL && emailAvailable();
    }

    public int pushTtlSeconds() {
        return TTL.clamp(appSettings.getInt("site.monitor.login.otp.push.ttl-seconds", TTL.def()));
    }

    public int emailTtlSeconds() {
        return TTL.clamp(appSettings.getInt("site.monitor.login.otp.email.ttl-seconds", TTL.def()));
    }

    public int ttlSeconds(LoginOtpService.Channel ch) {
        return ch == LoginOtpService.Channel.PUSH ? pushTtlSeconds() : emailTtlSeconds();
    }

    public int maxAttempts() {
        return ATTEMPTS.clamp(appSettings.getInt("site.monitor.login.otp.max-attempts", ATTEMPTS.def()));
    }

    public int resendCooldownSeconds() {
        return COOLDOWN.clamp(appSettings.getInt("site.monitor.login.otp.resend-cooldown-seconds", COOLDOWN.def()));
    }

    public int maxRequestsPerUser() {
        return PER_USER.clamp(appSettings.getInt("site.monitor.login.otp.max-requests-per-user", PER_USER.def()));
    }

    public int maxRequestsPerIp() {
        return PER_IP.clamp(appSettings.getInt("site.monitor.login.otp.max-requests-per-ip", PER_IP.def()));
    }

    public int maxFailedVerifications() {
        return FAILED.clamp(appSettings.getInt("site.monitor.login.otp.max-failed-verifications", FAILED.def()));
    }

    public boolean allowGlobalAdmins() {
        return appSettings.getBoolean("site.monitor.login.otp.allow-global-admins", false);
    }

    /** Push ile kod isteğinde kayıtlı cep telefonu da sorulur mu (vars. AÇIK). */
    public boolean pushRequiresPhone() {
        return appSettings.getBoolean("site.monitor.login.otp.push.require-phone", true);
    }

    /** E-posta ile kod isteğinde kayıtlı e-posta adresi de sorulur mu (vars. AÇIK). */
    public boolean emailRequiresEmail() {
        return appSettings.getBoolean("site.monitor.login.otp.email.require-email", true);
    }

    /** Bu kanalın isteği kişi bilgisi (push: telefon, e-posta: adres) doğrulaması gerektiriyor mu. */
    public boolean requiresContact(LoginOtpService.Channel ch) {
        return ch == LoginOtpService.Channel.PUSH ? pushRequiresPhone() : ch == LoginOtpService.Channel.EMAIL && emailRequiresEmail();
    }

    /** 15 dk'da kullanıcı başına eşleşmeyen kişi bilgisi sınırı (1–20, vars. 5). */
    public int maxContactMismatches() {
        return CONTACT_MISMATCHES.clamp(appSettings.getInt("site.monitor.login.otp.max-contact-mismatches", CONTACT_MISMATCHES.def()));
    }

    // ── Görünümler ─────────────────────────────────────────────────────────────

    /**
     * Giriş sayfasının PUBLIC görünümü — yalnız yapılandırma; kişi / IP / sayaç YOK. Push "açık" yalnız ağ geçidi de
     * yapılandırılmışsa.
     */
    public Map<String, Object> publicView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ldap", ldapLoginEnabled());
        m.put("otp_push", pushAvailable());
        m.put("otp_email", emailAvailable());
        m.put("push_ttl", pushTtlSeconds());
        m.put("email_ttl", emailTtlSeconds());
        m.put("resend_cooldown", resendCooldownSeconds());
        // 2026-10-03 (eklemeli): istek adımında kayıtlı telefon / e-posta alanı gösterilsin mi — yalnız YAPILANDIRMA
        // (kimin hangi bilgiye sahip olduğu değil). Eski istemci bu anahtarları tanımaz ve alanı çizmez.
        m.put("push_requires_phone", pushRequiresPhone());
        m.put("email_requires_email", emailRequiresEmail());
        return m;
    }

    /** Yönetici sayfasının ayar değerleri (etkin, kırpılmış). */
    public Map<String, Object> settingsView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ldap_enabled", ldapLoginEnabled());
        m.put("push_enabled", pushEnabled());
        m.put("email_enabled", emailEnabled());
        m.put("push_ttl_seconds", pushTtlSeconds());
        m.put("email_ttl_seconds", emailTtlSeconds());
        m.put("max_attempts", maxAttempts());
        m.put("resend_cooldown_seconds", resendCooldownSeconds());
        m.put("max_requests_per_user", maxRequestsPerUser());
        m.put("max_requests_per_ip", maxRequestsPerIp());
        m.put("max_failed_verifications", maxFailedVerifications());
        m.put("allow_global_admins", allowGlobalAdmins());
        m.put("push_require_phone", pushRequiresPhone());
        m.put("email_require_email", emailRequiresEmail());
        m.put("max_contact_mismatches", maxContactMismatches());
        return m;
    }

    /** Arayüz sınırları (alan doğrulaması sunucuyla AYNI tablodan). */
    public static Map<String, Object> limitsView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ttl", List.of(TTL.min(), TTL.max()));
        m.put("max_attempts", List.of(ATTEMPTS.min(), ATTEMPTS.max()));
        m.put("resend_cooldown", List.of(COOLDOWN.min(), COOLDOWN.max()));
        m.put("max_requests_per_user", List.of(PER_USER.min(), PER_USER.max()));
        m.put("max_requests_per_ip", List.of(PER_IP.min(), PER_IP.max()));
        m.put("max_failed_verifications", List.of(FAILED.min(), FAILED.max()));
        m.put("max_contact_mismatches", List.of(CONTACT_MISMATCHES.min(), CONTACT_MISMATCHES.max()));
        m.put("window_minutes", WINDOW_SECONDS / 60);
        return m;
    }

    /**
     * Kişi bilgisi KAPSAMI (2026-10-03) — ayar sayfasının "aktif kullanıcıların N / M'inde kayıtlı telefon / e-posta var"
     * ipucu: {@code {active_users, with_phone, with_email}}. TEK toplu sorgu ({@link AppUserRepository#contactCoverage});
     * kullanıcı başına döngü yok, kişi bilgisi dönmez. Okunamazsa sıfırlar (sayfa yine açılır).
     */
    public Map<String, Object> contactCoverage() {
        long active = 0;
        long phone = 0;
        long email = 0;
        try {
            List<Object[]> rows = userRepo.contactCoverage();
            Object[] r = rows == null || rows.isEmpty() ? null : rows.get(0);
            if (r != null && r.length >= 3) {
                active = num(r[0]);
                phone = num(r[1]);
                email = num(r[2]);
            }
        } catch (Exception e) {
            // kapsam bilgisi olmadan da sayfa çalışır (sıfırlar döner) — ama sessiz kalmasın
            log.warn("Giriş yöntemleri: kişi bilgisi kapsamı okunamadı — {}", e.toString());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active_users", active);
        m.put("with_phone", phone);
        m.put("with_email", email);
        return m;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** SMTP durum özeti (sır YOK): sunucu adı ve alarm e-postası anahtarı. */
    public Map<String, Object> smtpStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            var s = smtpSettings.getOrDefaults();
            String host = s == null ? null : s.getHost();
            m.put("host", host == null || host.isBlank() ? null : host);
            m.put("configured", host != null && !host.isBlank());
            m.put("alarm_mail_enabled", s != null && Boolean.TRUE.equals(s.getEnabled()));
        } catch (Exception e) {
            m.put("host", null);
            m.put("configured", false);
            m.put("alarm_mail_enabled", false);
        }
        return m;
    }
}
