package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Singleton (id=1) row holding the runtime-editable outbound SMTP / mail
 * configuration — the full set the mail pipeline currently sources from
 * {@code spring.mail.*} / {@code site.monitor.email.*}. Edited from the
 * admin-only Settings page.
 *
 * <p>Phase 1: this row is displayed/edited and used by the "Test connection"
 * and "Send test email" diagnostics only. Real notification emails still go
 * through the env-configured {@code JavaMailSender} until activation (next phase).
 *
 * <p>The SMTP password is stored AES-GCM encrypted in {@code passwordEnc} and is
 * never returned to the frontend.
 */
@Entity
@Table(name = "smtp_settings")
@Data
@NoArgsConstructor
public class SmtpSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /** Master on/off (mirrors site.monitor.email.enabled). */
    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(length = 255)
    private String host;

    private Integer port = 587;

    @Column(length = 255)
    private String username;

    /** AES-GCM encrypted SMTP password. Never serialized to the client. */
    @ToString.Exclude   // şifreli de olsa parola toString/log'a sızmasın
    @Column(name = "password_enc", columnDefinition = "TEXT")
    private String passwordEnc;

    @Column(name = "from_address", length = 255)
    private String fromAddress;

    @Column(name = "from_name", length = 255)
    private String fromName;

    @Column(name = "auth_enabled", nullable = false)
    private Boolean authEnabled = true;

    @Column(name = "start_tls_enable", nullable = false)
    private Boolean startTlsEnable = true;

    @Column(name = "start_tls_required", nullable = false)
    private Boolean startTlsRequired = true;

    /** mail.smtp.ssl.trust — host(s) to trust; "*" trusts all (skip verification). */
    @Column(name = "ssl_trust", length = 255)
    private String sslTrust;

    @Column(name = "connection_timeout_ms")
    private Integer connectionTimeoutMs = 10000;

    @Column(name = "read_timeout_ms")
    private Integer readTimeoutMs = 15000;

    @Column(name = "write_timeout_ms")
    private Integer writeTimeoutMs = 15000;

    /** Delay before retrying a transient 421 rate-limit (mail.send.retry-delay-ms). */
    @Column(name = "retry_delay_ms")
    private Integer retryDelayMs = 90000;

    /** Delay between successive contacts within one alert (mail.send.inter-contact-delay-ms). */
    @Column(name = "inter_contact_delay_ms")
    private Integer interContactDelayMs = 5000;

    /** Delay between domains during catch-up (mail.catch-up.inter-domain-delay-ms). */
    @Column(name = "inter_domain_delay_ms")
    private Integer interDomainDelayMs = 3000;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
