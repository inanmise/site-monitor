package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Serbest-form HTTP / Website izleme monitörü: bir URL'ye periyodik HTTP isteği atılır;
 * yanıt durum kodu {@code expectedStatus} pattern'ine uyuyorsa (ör. "200", "2xx", "200-399")
 * SAĞLIKLI (up), aksi halde down. Erişilebilirlik (uptime) ölçer; keyword içerik kontrolünden
 * ayrıdır. Envantere bağlı değildir; takım {@code teamId} ile açıkça atanır (alarm yönlendirmesi).
 *
 * SSL/Domain kontrolleri (opsiyonel) sıcak uptime döngüsünden AYRI, yavaş bir döngüde işlenir:
 * {@code checkSslErrors}/{@code sslExpiryReminders} → TLS sertifikası (CertificateCheckerService),
 * {@code domainExpiryReminders} → registrar WHOIS/RDAP (RdapDomainExpiryService).
 */
@Entity
@Table(name = "http_monitors")
@Data
@NoArgsConstructor
public class HttpMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    /** HTTP metodu: GET | HEAD | POST (varsayılan GET). */
    @Column(name = "method")
    private String method = "GET";

    /** Sağlıklı sayılan durum kodu pattern'i: "200", "2xx", "200-399", CSV karışık (varsayılan 200-399). */
    @Column(name = "expected_status")
    private String expectedStatus = "200-399";

    /** 3xx yönlendirmeleri takip et (varsayılan true). */
    @Column(name = "follow_redirects")
    private Boolean followRedirects = true;

    /** Sıcak döngüde TLS sertifikasını doğrula: true → sertifika hatası down sayılır;
     *  false → yalnız erişilebilirlik ölçülür (sertifika geçerliliği ayrı SSL döngüsünde izlenir). */
    @Column(name = "verify_ssl")
    private Boolean verifySsl = false;

    /** Mantıksal grup (ör. "X Sistemleri") — filtreleme/gruplama; serbest-form. */
    @Column(name = "group_name")
    private String groupName;

    /** Sorumlu takım — alarm yönlendirmesi (envanterden bağımsız). */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 300;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 10000;

    /** Per-monitor teyit: alarm öncesi doğrulama denemesi sayısı (varsayılan 3; 0 = anında). */
    @Column(name = "confirm_attempts")
    private Integer confirmAttempts = 3;

    /** Per-monitor teyit: denemeler arası saniye (varsayılan 30). */
    @Column(name = "confirm_interval_seconds")
    private Integer confirmIntervalSeconds = 30;

    /** Recovery period: alarmın otomatik kapanması için gereken ardışık başarılı kontrol sayısı (varsayılan 3). */
    @Column(name = "recovery_checks")
    private Integer recoveryChecks = 3;

    /** Recovery aktif re-check aralığı (sn): recoveryChecks denemesi bu süre arayla yapılır (keyword/ping ile aynı). */
    @Column(name = "recovery_interval_seconds")
    private Integer recoveryIntervalSeconds = 30;

    /** Serbest etiketler — virgülle ayrılmış (organizasyon/filtreleme). */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** E-posta bildirimi açık mı (varsayılan true). Şimdilik tek gerçek kanal; SMS/Voice/Push UI'da devre dışı. */
    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    /** SSL hata kontrolü: TLS zinciri/geçerliliği bozuksa alarm (yavaş döngü). */
    @Column(name = "check_ssl_errors")
    private Boolean checkSslErrors = false;

    /** SSL son-kullanım hatırlatması: sertifika bitişine {@code sslReminderDays} kala alarm. */
    @Column(name = "ssl_expiry_reminders")
    private Boolean sslExpiryReminders = false;

    /** Domain (registrar/WHOIS) son-kullanım hatırlatması: kayıt bitişine {@code domainReminderDays} kala alarm. */
    @Column(name = "domain_expiry_reminders")
    private Boolean domainExpiryReminders = false;

    /** SSL bitişi öncesi hatırlatma gün eşikleri (CSV, ör. "30,14,7"). */
    @Column(name = "ssl_reminder_days")
    private String sslReminderDays = "30,14,7";

    /** Domain bitişi öncesi hatırlatma gün eşikleri (CSV, ör. "30,14,7"). */
    @Column(name = "domain_reminder_days")
    private String domainReminderDays = "30,14,7";

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;
}
