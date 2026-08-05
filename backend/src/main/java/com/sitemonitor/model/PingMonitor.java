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
}
