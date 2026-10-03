package com.sitemonitor.service.otp;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SmtpSettingsService;
import com.sitemonitor.service.UserPushService;
import lombok.RequiredArgsConstructor;
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
 * </ul>
 */
@Service
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

    /** Sayfanın yönettiği anahtarların TAMAMI (hepsi GLOBAL_ONLY — SettingsScopedAdminGateTest). */
    public static final List<String> KEYS = List.of(KEY_LDAP, KEY_PUSH_ENABLED, KEY_EMAIL_ENABLED, KEY_PUSH_TTL,
            KEY_EMAIL_TTL, KEY_MAX_ATTEMPTS, KEY_COOLDOWN, KEY_MAX_PER_USER, KEY_MAX_PER_IP, KEY_MAX_FAILED,
            KEY_ALLOW_GLOBAL_ADMINS);

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

    /** Aralık tablosu (kayıt doğrulaması + arayüz sınırları). */
    public static final Map<String, Range> RANGES = Map.of(
            KEY_PUSH_TTL, TTL, KEY_EMAIL_TTL, TTL, KEY_MAX_ATTEMPTS, ATTEMPTS, KEY_COOLDOWN, COOLDOWN,
            KEY_MAX_PER_USER, PER_USER, KEY_MAX_PER_IP, PER_IP, KEY_MAX_FAILED, FAILED);

    private final AppSettingsService appSettings;
    private final UserPushService userPushService;
    private final SmtpSettingsService smtpSettings;

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
        m.put("window_minutes", WINDOW_SECONDS / 60);
        return m;
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
