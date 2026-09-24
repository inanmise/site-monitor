package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "port_monitors")
@Data
@NoArgsConstructor
public class PortMonitor implements MonitorAlertPrefs, MonitorSchedule {

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
    private Integer intervalSeconds = 300;

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

    /** Serbest etiketler — virgülle ayrılmış (organizasyon/filtreleme). */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** E-posta bildirimi açık mı (varsayılan true). SMS/Voice/Push UI'da devre dışı. */
    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    /** Kişi-webhook (push) bildirimi açık mı (vars. true — üst katmanlar zaten vars. KAPALI, çifte emniyet). */
    @Column(name = "notify_webhook")
    private Boolean notifyWebhook = true;

    /** Alarm seviyesi (2026-09-19): WARNING (varsayılan, null) | HIGH | CRITICAL — süre-bitişi dışındaki tüm
     *  alarmlar bu seviyede açılır; HIGH/CRITICAL eskalasyon kontaklarını alıcıya ekler. Bkz. MonitorAlertPrefs. */
    @Column(name = "alert_level", length = 16)
    private String alertLevel;

    /** Yavaş yanıt alarmı açık mı: açıksa response_ms eşiği aşılınca PORT_SLOW. */
    @Column(name = "slow_response_enabled")
    private Boolean slowResponseEnabled = false;

    /** Yavaş yanıt eşiği (ms). */
    @Column(name = "slow_threshold_ms")
    private Integer slowThresholdMs = 3000;

    /** "auto" | "v4" | "v6" — soket bağlantısı IP sürümü kısıtı (Ping ile aynı model). */
    @Column(name = "ip_version", nullable = false)
    @ColumnDefault("'auto'")   // ddl-auto ADD COLUMN'a DEFAULT ekler → mevcut satırlı tabloda "not null" boot hatası olmaz
    private String ipVersion = "auto";

    /**
     * Kurumsal vekil tercihi (2026-09-24): {@code AUTO} | {@code ON} | {@code OFF}. HTTP'den FARKLI olarak null =
     * <b>OFF</b> (Sayfa Hızı kuralı, {@code ProxyPolicyService.normalizeModeDefaultOff}): port kontrolleri bugüne kadar
     * hep pod'dan doğrudan gidiyordu — mevcut kayıtların yolu değişmesin. Vekil yolu {@code CONNECT host:port} tüneli;
     * UDP HTTP vekilinden geçemez → her zaman doğrudan. Nullable: dolu tabloya ddl-auto sorunsuz ekler.
     */
    @Column(name = "use_proxy", length = 10)
    private String useProxy;

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

    // MonitorSchedule (2026-09-19): "Sizin için — bugün" bayat-izleme kartı
    @Override public String scheduleType() { return "PORT"; }
    @Override public boolean scheduleStandalone() { return Boolean.TRUE.equals(standalone); }
    @Override public String scheduleTarget() { return host + ":" + port; }
}
