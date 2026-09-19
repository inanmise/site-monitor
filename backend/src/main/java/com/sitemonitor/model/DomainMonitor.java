package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Serbest-form alan adı (domain) süre-bitişi izleme monitörü: kayıtlı domain'in registrar kayıt
 * bitişi RDAP (birincil) veya WHOIS (env-gated fallback) ile sorgulanır. Expiry + registrar + EPP
 * status kodları + nameserver'lar izlenir; 4-seviyeli durum (OK/WARNING/CRITICAL/UNKNOWN).
 * Envantere ve HTTP/TLS izlemesine bağlı değildir; takım {@code teamId} ile atanır.
 *
 * NOT: TLS sertifika bitişi (CertificateCheckerService) ve HTTP monitörünün domainExpiryReminders
 * toggle'ından (RdapDomainExpiryService, DOMAIN_EXPIRY) BAĞIMSIZDIR — bu tip DOMAINMON_* alarmları üretir.
 */
@Entity
@Table(name = "domain_monitors")
@Data
@NoArgsConstructor
public class DomainMonitor implements MonitorAlertPrefs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** İzlenen kayıtlı domain (registrable / eTLD+1). URL/subdomain girilirse backend indirger. */
    @Column(nullable = false)
    private String domain;

    /** Mantıksal grup (ör. "X Sistemleri") — filtreleme/gruplama; serbest-form. */
    @Column(name = "group_name")
    private String groupName;

    /** Serbest etiketler — virgülle ayrılmış (2026-09-18: her izlemede zorunlu; Http/Port ile aynı biçim). */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** Sorumlu takım — alarm yönlendirmesi. */
    @Column(name = "team_id")
    private Long teamId;

    /** Per-monitor teyit: alarm öncesi doğrulama denemesi sayısı (vars. 3; 0 = anında alarm).
     *
     *  <p>Bu tür bu alanları HİÇ taşımıyordu; davranış GLOBAL varsayılana
     *  ({@code site.monitor.uptime.confirm-attempts}, 3) sabitliydi ve izleme bazında
     *  ayarlanamıyordu. Diğer yedi türde bu soru formda soruluyor, burada sorulmuyordu.
     *  Kolon sonradan eklendi → eski satırlar {@code null} taşır ve global varsayılana düşer,
     *  yani mevcut izlemelerin davranışı DEĞİŞMEZ. */
    @Column(name = "confirm_attempts")
    private Integer confirmAttempts = 3;

    /** Per-monitor teyit: denemeler arası saniye (vars. 30). */
    @Column(name = "confirm_interval_seconds")
    private Integer confirmIntervalSeconds = 30;

    /** Kurtarma: alarmın otomatik kapanması için gereken ardışık BAŞARILI kontrol sayısı (vars. 3). */
    @Column(name = "recovery_checks")
    private Integer recoveryChecks = 3;

    /** Kurtarma aktif re-check aralığı (sn). */
    @Column(name = "recovery_interval_seconds")
    private Integer recoveryIntervalSeconds = 30;

    /** E-posta bildirimi açık mı (vars. true). {@code notifyWebhook} ile SİMETRİK: iki kanal
     *  ayrı ayrı kapatılabilir. Kolon sonradan eklendiği için ESKİ satırlar {@code null} taşır ve
     *  null-güvenli okuma sayesinde mail almaya devam eder — kimsenin maili sessizce kesilmez. */
    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    /** Kişi-webhook (push) bildirimi açık mı (vars. true — üst katmanlar zaten vars. KAPALI, çifte emniyet). */
    @Column(name = "notify_webhook")
    private Boolean notifyWebhook = true;

    /** Alarm seviyesi (2026-09-19): WARNING (varsayılan, null) | HIGH | CRITICAL — süre-bitişi dışındaki tüm
     *  alarmlar bu seviyede açılır; HIGH/CRITICAL eskalasyon kontaklarını alıcıya ekler. Bkz. MonitorAlertPrefs. */
    @Column(name = "alert_level", length = 16)
    private String alertLevel;

    @Column(nullable = false)
    private Boolean active = true;

    /** Kontrol aralığı (sn) — domain bitişi nadiren değişir; varsayılan günde 1 (86400). */
    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 86400;

    /** RDAP istek timeout'u (ms) — bu monitör için override. null = global ayar
     *  ({@code site.monitor.domain.rdap-timeout-ms}, vars. 6000) kullanılır. */
    @Column(name = "check_timeout_ms")
    private Integer checkTimeoutMs;

    /** Hatırlatma gün eşikleri (CSV, bitişe kala) — bilgi/rapor amaçlı; varsayılan 60/30/14/7/3/1. */
    @Column(name = "thresholds_csv")
    private String thresholdsCsv = "60,30,14,7,3,1";

    /** WARNING durumu eşiği: kalan gün ≤ bu → WARNING (varsayılan 30). */
    @Column(name = "warning_days")
    private Integer warningDays = 30;

    /** CRITICAL durumu eşiği: kalan gün ≤ bu → CRITICAL (varsayılan 7). */
    @Column(name = "critical_days")
    private Integer criticalDays = 7;

    /**
     * Transfer kilidi alarmı. Varsayılan AÇIK: kilit yokluğu bugün de (EPP uyarısına OR'lanarak)
     * alarm üretiyordu — ayrı tipe taşınırken kullanıcının fiilî korumasını kapatmak olmaz.
     */
    @Column(name = "transfer_lock_alert")
    private Boolean transferLockAlert = true;

    /**
     * Kara liste (DNSBL) izleme. Varsayılan KAPALI: her kontrolde dış DNS sorgusu üretir;
     * bunun bilinçli açılması gerekir. Kurumsal ağda çözüm reddedilirse sonuç "Doğrulanamadı"
     * olur, ASLA yanlış alarm değil.
     */
    @Column(name = "blacklist_enabled")
    private Boolean blacklistEnabled = false;

    /** Kayıt değişikliği alarmı (registrar / NS / EPP / DNSSEC). Varsayılan AÇIK — bugünkü davranış. */
    @Column(name = "change_alert")
    private Boolean changeAlert = true;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    // ── Kimlik künyesi ────────────────────────────────────────────────────────────────────
    // "Bu izlemeyi kim kurdu?" sorusu geçmiş tablosuna gitmeden de cevaplanabilsin (kart künyesi
    // bunu okur). monitor_change_log'dan BAĞIMSIZ: biri retention ile temizlense de diğeri kalır.
    // Eski kayıtlarda null'dır — arayüz o zaman künyeyi hiç göstermez.
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_ip", length = 50)
    private String createdIp;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_by_name")
    private String updatedByName;


    /**
     * Bu izlemenin alarmlarinin gidecegi Bildirim Grubu — NULL ise zincirin kalani islet:
     * takimin varsayilan grubu, o da yoksa {@code Team.email} (bugunku davranis).
     */
    @jakarta.persistence.Column(name = "notification_group_id")
    private Long notificationGroupId;
}
