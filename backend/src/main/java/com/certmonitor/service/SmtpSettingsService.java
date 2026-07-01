package com.certmonitor.service;

import com.certmonitor.model.SmtpSettings;
import com.certmonitor.repository.SmtpSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads/persists the singleton {@link SmtpSettings} row. When nothing has been
 * saved yet, {@link #getOrDefaults()} seeds the view from the application's
 * <em>live</em> mail configuration ({@code spring.mail.*} / {@code cert.monitor.email.*})
 * so the admin sees the current effective state on the screen. Changes apply to
 * the cache immediately (no restart). The password is encrypted at rest and
 * never returned to the client.
 */
@Slf4j
@Service
public class SmtpSettingsService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final SmtpSettingsRepository repo;
    private final SecretCipher cipher;

    private volatile SmtpSettings cached;

    // ── Live (env / application.properties) mail config — shown as current state ──
    @Value("${cert.monitor.email.enabled:false}") boolean envEnabled;
    @Value("${spring.mail.host:smtp.gmail.com}") String envHost;
    @Value("${spring.mail.port:587}") Integer envPort;
    @Value("${spring.mail.username:}") String envUsername;
    @Value("${spring.mail.password:}") String envPassword;
    @Value("${cert.monitor.email.from:noreply@certmonitor}") String envFrom;
    @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean envAuth;
    @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean envStartTlsEnable;
    @Value("${spring.mail.properties.mail.smtp.starttls.required:true}") boolean envStartTlsRequired;
    @Value("${spring.mail.properties.mail.smtp.ssl.trust:}") String envSslTrust;
    @Value("${spring.mail.properties.mail.smtp.connectiontimeout:10000}") int envConnTimeout;
    @Value("${spring.mail.properties.mail.smtp.timeout:15000}") int envReadTimeout;
    @Value("${spring.mail.properties.mail.smtp.writetimeout:15000}") int envWriteTimeout;
    @Value("${mail.send.retry-delay-ms:90000}") int envRetryDelay;
    @Value("${mail.send.inter-contact-delay-ms:5000}") int envInterContact;
    @Value("${mail.catch-up.inter-domain-delay-ms:3000}") int envInterDomain;

    public SmtpSettingsService(SmtpSettingsRepository repo, SecretCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    @PostConstruct
    void load() {
        this.cached = repo.findById(SmtpSettings.SINGLETON_ID).orElse(null);
        if (cached != null) {
            log.info("SMTP settings loaded (enabled={}, host={}:{})",
                    cached.getEnabled(), cached.getHost(), cached.getPort());
        }
    }

    /** Çok-pod tutarlılığı: başka bir instance kaydettiyse (updated_at farklı) cache'i DB'den tazele. */
    @Scheduled(fixedDelayString = "${cert.monitor.settings.refresh-ms:10000}", initialDelayString = "15000")
    void refreshFromDb() {
        try {
            SmtpSettings db = repo.findById(SmtpSettings.SINGLETON_ID).orElse(null);
            String dbUa  = db     != null ? db.getUpdatedAt()     : null;
            String curUa = cached != null ? cached.getUpdatedAt() : null;
            if (!java.util.Objects.equals(dbUa, curUa)) {
                this.cached = db;
                log.info("SMTP settings cache refreshed from DB (updated by another instance)");
            }
        } catch (Exception e) {
            log.debug("SMTP settings refresh skipped: {}", e.getMessage());
        }
    }

    /** Stored row, or the live env-configured mail settings when nothing is saved yet. */
    public SmtpSettings getOrDefaults() {
        if (cached != null) return cached;
        SmtpSettings d = new SmtpSettings();
        d.setEnabled(envEnabled);
        d.setHost(envHost);
        d.setPort(envPort);
        d.setUsername(emptyToNull(envUsername));
        d.setFromAddress(envFrom);
        d.setFromName(null);
        d.setAuthEnabled(envAuth);
        d.setStartTlsEnable(envStartTlsEnable);
        d.setStartTlsRequired(envStartTlsRequired);
        d.setSslTrust(emptyToNull(envSslTrust));
        d.setConnectionTimeoutMs(envConnTimeout);
        d.setReadTimeoutMs(envReadTimeout);
        d.setWriteTimeoutMs(envWriteTimeout);
        d.setRetryDelayMs(envRetryDelay);
        d.setInterContactDelayMs(envInterContact);
        d.setInterDomainDelayMs(envInterDomain);
        return d;
    }

    public boolean isConfigured() {
        return cached != null;
    }

    /** Gerçek bir şifreleme anahtarı (CERT_MONITOR_SECRET_KEY) ayarlı mı — UI uyarısı için. */
    public boolean isSecretKeyConfigured() {
        return cipher.isKeyConfigured();
    }

    /** True when an effective password exists (stored row, or live env password). */
    public boolean isPasswordSet() {
        if (cached != null && cached.getPasswordEnc() != null && !cached.getPasswordEnc().isBlank()) {
            return true;
        }
        return cached == null && envPassword != null && !envPassword.isBlank();
    }

    /** Effective password for the diagnostics: stored (decrypted) or the live env password. */
    public String effectivePassword() {
        if (cached != null && cached.getPasswordEnc() != null && !cached.getPasswordEnc().isBlank()) {
            return cipher.decrypt(cached.getPasswordEnc());
        }
        return emptyToNull(envPassword);
    }

    public synchronized SmtpSettings save(Map<String, Object> body, String actor) {
        SmtpSettings s = (cached != null) ? cached : new SmtpSettings();
        s.setId(SmtpSettings.SINGLETON_ID);

        s.setEnabled(boolVal(body, "enabled", s.getEnabled()));
        s.setHost(trimToNull(strVal(body, "host", s.getHost())));
        s.setPort(intVal(body, "port", s.getPort()));
        s.setUsername(trimToNull(strVal(body, "username", s.getUsername())));

        Object pw = body.get("password");
        if (pw != null && !pw.toString().isBlank()) {
            s.setPasswordEnc(cipher.encrypt(pw.toString()));
        }

        s.setFromAddress(trimToNull(strVal(body, "from_address", s.getFromAddress())));
        s.setFromName(trimToNull(strVal(body, "from_name", s.getFromName())));
        s.setAuthEnabled(boolVal(body, "auth_enabled", s.getAuthEnabled()));
        s.setStartTlsEnable(boolVal(body, "start_tls_enable", s.getStartTlsEnable()));
        s.setStartTlsRequired(boolVal(body, "start_tls_required", s.getStartTlsRequired()));
        s.setSslTrust(trimToNull(strVal(body, "ssl_trust", s.getSslTrust())));
        s.setConnectionTimeoutMs(intVal(body, "connection_timeout_ms", s.getConnectionTimeoutMs()));
        s.setReadTimeoutMs(intVal(body, "read_timeout_ms", s.getReadTimeoutMs()));
        s.setWriteTimeoutMs(intVal(body, "write_timeout_ms", s.getWriteTimeoutMs()));
        s.setRetryDelayMs(intVal(body, "retry_delay_ms", s.getRetryDelayMs()));
        s.setInterContactDelayMs(intVal(body, "inter_contact_delay_ms", s.getInterContactDelayMs()));
        s.setInterDomainDelayMs(intVal(body, "inter_domain_delay_ms", s.getInterDomainDelayMs()));

        s.setUpdatedAt(ISO.format(Instant.now()));
        s.setUpdatedBy(actor);

        this.cached = repo.save(s);
        log.info("SMTP settings saved by {} (enabled={}, host={}:{})",
                actor, s.getEnabled(), s.getHost(), s.getPort());
        return cached;
    }

    /** Client-facing view: omits the password, exposes a "set" flag. */
    public Map<String, Object> toClientMap(SmtpSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", bool(s.getEnabled()));
        m.put("host", s.getHost());
        m.put("port", s.getPort());
        m.put("username", s.getUsername());
        m.put("password_set", isPasswordSet());
        m.put("from_address", s.getFromAddress());
        m.put("from_name", s.getFromName());
        m.put("auth_enabled", bool(s.getAuthEnabled()));
        m.put("start_tls_enable", bool(s.getStartTlsEnable()));
        m.put("start_tls_required", bool(s.getStartTlsRequired()));
        m.put("ssl_trust", s.getSslTrust());
        m.put("connection_timeout_ms", s.getConnectionTimeoutMs());
        m.put("read_timeout_ms", s.getReadTimeoutMs());
        m.put("write_timeout_ms", s.getWriteTimeoutMs());
        m.put("retry_delay_ms", s.getRetryDelayMs());
        m.put("inter_contact_delay_ms", s.getInterContactDelayMs());
        m.put("inter_domain_delay_ms", s.getInterDomainDelayMs());
        m.put("updated_at", s.getUpdatedAt());
        m.put("updated_by", s.getUpdatedBy());
        return m;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static boolean bool(Boolean b) { return Boolean.TRUE.equals(b); }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String strVal(Map<String, Object> body, String key, String fallback) {
        Object v = body.get(key);
        return v != null ? v.toString() : fallback;
    }

    private static boolean boolVal(Map<String, Object> body, String key, Boolean fallback) {
        Object v = body.get(key);
        if (v == null) return Boolean.TRUE.equals(fallback);
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static Integer intVal(Map<String, Object> body, String key, Integer fallback) {
        Object v = body.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return fallback; }
    }
}
