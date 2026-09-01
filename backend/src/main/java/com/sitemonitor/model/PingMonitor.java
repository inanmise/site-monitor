package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

/**
 * Serbest-form ICMP ping izleme monitörü: bir host'a (IPv4/IPv6) ping atılır;
 * yanıt yoksa alarm. Envantere bağlı değildir; takım {@code teamId} ile atanır.
 */
@Entity
@Table(name = "ping_monitors")
@Data
@NoArgsConstructor
public class PingMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String host;

    /** "auto" | "v4" | "v6" — ping komutu IP sürümü kısıtı. */
    @Column(name = "ip_version", nullable = false)
    @ColumnDefault("'auto'")   // ddl-auto ADD COLUMN'a DEFAULT ekler → mevcut satırlı tabloda "not null" boot hatası olmaz
    private String ipVersion = "auto";

    /** Mantıksal grup (ör. "X Sistemleri") — filtreleme/gruplama; serbest-form. */
    @Column(name = "group_name")
    private String groupName;

    /** Sorumlu takım — alarm yönlendirmesi. */
    @Column(name = "team_id")
    private Long teamId;

    /** E-posta bildirimi açık mı (vars. true). {@code notifyWebhook} ile SİMETRİK: iki kanal
     *  ayrı ayrı kapatılabilir. Kolon sonradan eklendiği için ESKİ satırlar {@code null} taşır ve
     *  null-güvenli okuma sayesinde mail almaya devam eder — kimsenin maili sessizce kesilmez. */
    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    /** Kişi-webhook (push) bildirimi açık mı (vars. true — üst katmanlar zaten vars. KAPALI, çifte emniyet). */
    @Column(name = "notify_webhook")
    private Boolean notifyWebhook = true;

    /**
     * Yavaşlık alarmı açık mı (vars. KAPALI — opt-in).
     *
     * <p>Port izlemesindeki {@code slowResponseEnabled}'ın GÖRECELİ kardeşi: orada eşik sabit bir
     * ms değeri, burada host'un KENDİ son N dakikalık ortalaması. Ping gecikmesi hatta, mesafeye
     * ve donanıma göre 5 ms ile 250 ms arasında normal olabilir; sabit bir ms eşiği ya her hostta
     * yanlış alarm üretir ya da hiç ötmez. Taban çizgisi host başına kendiliğinden oluşur.
     */
    @Column(name = "slow_response_enabled")
    private Boolean slowResponseEnabled = false;

    /** Taban çizgisi penceresi (dk): son bu kadar dakikanın BAŞARILI ping ortalaması. */
    @Column(name = "slow_baseline_window_minutes")
    private Integer slowBaselineWindowMinutes = 10;

    /** Sapma eşiği (%): ölçüm, taban çizgisinin bu yüzde kadar üstündeyse YAVAŞ sayılır. */
    @Column(name = "slow_threshold_percent")
    private Integer slowThresholdPercent = 20;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 60;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 5000;

    @Column(name = "packet_count")
    private Integer packetCount = 4;

    /** Per-monitor teyit: alarm öncesi doğrulama denemesi sayısı (varsayılan 3). */
    @Column(name = "confirm_attempts")
    private Integer confirmAttempts = 3;

    /** Per-monitor teyit: denemeler arası saniye (varsayılan 30). */
    @Column(name = "confirm_interval_seconds")
    private Integer confirmIntervalSeconds = 30;

    /** Recovery period: alarmın otomatik kapanması için gereken ardışık başarılı kontrol sayısı
     *  (varsayılan 1 = ilk başarılı kontrolde kapat). */
    @Column(name = "recovery_checks")
    private Integer recoveryChecks = 3;

    /** Recovery aktif re-check aralığı (sn): recoveryChecks denemesi bu süre arayla yapılır.
     *  Set ise recovery aktif döngüyle yürür; null → pasif (kontrol aralığında sayım). */
    @Column(name = "recovery_interval_seconds")
    private Integer recoveryIntervalSeconds = 30;

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
