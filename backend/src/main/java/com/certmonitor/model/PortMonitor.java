package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "port_monitors")
@Data
@NoArgsConstructor
public class PortMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private Integer port;

    // Kontrol tipi: TCP (connect) | TLS (handshake) | HTTP (durum kodu) | BANNER (yanit eslestirme) | UDP
    @Column(nullable = false)
    private String protocol = "TCP";

    // HTTP -> beklenen durum kodu kalibi (or. "200", "2xx", "200-399"); BANNER -> beklenen yanit alt-dizgesi.
    @Column(name = "expect")
    private String expect;

    // HTTP -> istek yolu (path, vars. "/"); BANNER/UDP -> gonderilecek veri (opsiyonel, \r\n kacis destekli).
    @Column(name = "send_data")
    private String sendData;

    @Column(nullable = false)
    private Boolean active = true;

    // Takım kapsamı (ping/keyword gibi) — null: sertifika envanterinden otomatik üretilen kayıtlar
    // (takım, domain→takım haritasından türetilir). Manuel eklenenlerde set edilir.
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "group_name")
    private String groupName;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 60;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 5000;

    /** true = kullanıcının Port sayfasından eklediği, sertifika envanterine bağlı OLMAYAN monitör.
     *  Envanter-skip'i baypas eder (her zaman listelenir + kontrol edilir). null/false = envanter-türevi. */
    @Column(name = "standalone")
    private Boolean standalone = false;

    /** Per-monitor teyit: alarm öncesi doğrulama denemesi sayısı (varsayılan 3) — ping/keyword ile aynı. */
    @Column(name = "confirm_attempts")
    private Integer confirmAttempts = 3;

    /** Per-monitor teyit: denemeler arası saniye (varsayılan 30). */
    @Column(name = "confirm_interval_seconds")
    private Integer confirmIntervalSeconds = 30;

    /** Recovery period: alarmın otomatik kapanması için gereken ardışık başarılı kontrol sayısı (varsayılan 3). */
    @Column(name = "recovery_checks")
    private Integer recoveryChecks = 3;

    /** Recovery aktif re-check aralığı (sn): set ise recovery aktif döngüyle yürür; null → pasif. */
    @Column(name = "recovery_interval_seconds")
    private Integer recoveryIntervalSeconds = 30;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;
}
