package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Senaryo İzleme (Scripted Check) monitörü: kullanıcı tanımlı bir k6 scriptini periyodik olarak TEK İTERASYON
 * çalıştırır ve sonucuna göre sağlık kararı üretir — çok adımlı akışların (OIDC/Keycloak login, API zincirleri)
 * uçtan uca izlenmesi. k6 GÖMÜLMEZ; her kontrolde kısa ömürlü, sıkı sandboxlu bir alt süreç olarak koşar.
 *
 * <p>Diğer "free-form" türlerin (özellikle {@link PageMonitor}) iskeletini birebir izler: ISO String zaman,
 * {@code teamId} sahiplik, soft-delete YOK, eşik/recovery state entity'de {@code confirm*}/{@code recovery*}
 * olarak taşınır, LatestCheck KULLANILMAZ (LATERAL "latest per monitor"). Kimlik alanı = {@code name}.
 */
@Entity
@Table(name = "scripted_monitors")
@Data
@NoArgsConstructor
public class ScriptedMonitor implements MonitorAlertPrefs, MonitorSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Serbest açıklama (senaryonun ne izlediği). */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** k6 script gövdesi (JavaScript). Her kontrolde geçici dosyaya yazılıp tek iterasyon çalıştırılır. */
    @Column(columnDefinition = "TEXT")
    private String script;

    /** Ortam değişkeni tanımları — JSON dizi: [{"name":..,"value":<düz|enc:v1:..>,"secret":bool}].
     *  secret=true değerler {@code SecretCipher} ile şifreli saklanır; API/ekranda asla düz metin dönmez. */
    @Column(name = "env_json", columnDefinition = "TEXT")
    private String envJson;

    /** Süreç timeout'u (sn) — varsayılan 60, servis katmanında mutlak tavana (180) clamp'lenir. */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 60;

    /**
     * Koşum kurumsal çıkış vekilinden (proxy) geçsin mi: {@code AUTO} | {@code ON} | {@code OFF}.
     *
     * <p>{@code AUTO} (varsayılan, null da AUTO sayılır): vekil yapılandırılmışsa kullanılır —
     * Java tarafındaki sertifika/RDAP çıkışlarıyla aynı davranış. {@code OFF}: hedef iç ağdaysa ve
     * vekile sokulmaması gerekiyorsa. {@code ON}: vekil zorunlu; yapılandırılmamışsa kaydetmede uyarılır.
     *
     * <p>Neden var: k6 alt süreci vekil ayarlarını HİÇ almıyordu; vekil zorunlu ağda her koşum
     * {@code request timeout} ile düşüyordu (2026-08 saha teşhisi).
     */
    @Column(name = "use_proxy", length = 10)
    private String useProxy = "AUTO";

    /** Script'in güncel sürüm etiketi ({@code 1.0.0}). Geçmiş {@code scripted_script_versions}'ta;
     *  bu alan liste/rozet gösteriminin sürüm tablosunu sorgulamak zorunda kalmaması için. */
    @Column(name = "script_version", length = 20)
    private String scriptVersion;

    /** Mantıksal grup (filtreleme/gruplama; serbest-form). */
    @Column(name = "group_name")
    private String groupName;

    /** Sorumlu takım — alarm yönlendirmesi + sahiplik izolasyonu. */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 300;

    /** Per-monitor teyit: alarm öncesi ardışık başarısızlık (doğrulama denemesi) sayısı — "N ardışık başarısızlık" eşiği. */
    @Column(name = "confirm_attempts")
    private Integer confirmAttempts = 3;

    @Column(name = "confirm_interval_seconds")
    private Integer confirmIntervalSeconds = 30;

    /** Recovery: alarmın otomatik kapanması için gereken ardışık başarılı (PASS) kontrol sayısı. */
    @Column(name = "recovery_checks")
    private Integer recoveryChecks = 3;

    @Column(name = "recovery_interval_seconds")
    private Integer recoveryIntervalSeconds = 30;

    /**
     * Yavaş koşum alarmı ({@code SCRIPTED_SLOW}) açık mı. Kapalıyken eşik hiç değerlendirilmez —
     * senaryonun "ne kadar sürdüğü" her zaman kaydedilir ama kimse çağrılmaz.
     *
     * <p>Diğer türlerdeki (PORT_SLOW/KEYWORD_SLOW) opt-in deseniyle aynıdır: kapalıysa sweep
     * sentetik bir "up" üretir, böylece daha önce açılmış bir SLOW alarmı asılı kalmaz.
     */
    @Column(name = "slow_response_enabled")
    private Boolean slowResponseEnabled = false;

    /** {@code SCRIPTED_SLOW} eşiği (ms): koşum SÜRESİ bunu aşarsa yavaş sayılır. Ölçülen süre k6
     *  sürecinin toplam duvar saati süresidir (kontrol geçmişindeki "Süre" sütunuyla aynı değer). */
    @Column(name = "slow_threshold_ms")
    private Integer slowThresholdMs = 15000;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    /** Kişi-webhook (push) bildirimi açık mı (vars. true — üst katmanlar zaten vars. KAPALI, çifte emniyet). */
    @Column(name = "notify_webhook")
    private Boolean notifyWebhook = true;

    /** Alarm seviyesi (2026-09-19): WARNING (varsayılan, null) | HIGH | CRITICAL — süre-bitişi dışındaki tüm
     *  alarmlar bu seviyede açılır; HIGH/CRITICAL eskalasyon kontaklarını alıcıya ekler. Bkz. MonitorAlertPrefs. */
    @Column(name = "alert_level", length = 16)
    private String alertLevel;

    /**
     * Otomatik devre dışı bırakma SEBEBİ — doluysa izleme sistem tarafından kapatılmıştır.
     *
     * <p>{@code active=false} tek başına yetmez: kullanıcının kendi kapattığı bir izleme ile
     * sistemin anomali yüzünden kapattığı ayırt edilemezdi. Bu alan ekranda kalıcı bir uyarı
     * olarak gösterilir ve kullanıcı izlemeyi yeniden açtığında TEMİZLENİR.
     */
    @Column(name = "disabled_reason", columnDefinition = "TEXT")
    private String disabledReason;

    /** Otomatik kapatmanın zamanı (ISO). {@link #disabledReason} ile birlikte yazılır/silinir. */
    @Column(name = "disabled_at")
    private String disabledAt;

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
    @Override public String scheduleType() { return "SCRIPTED"; }
    @Override public String scheduleTarget() { return null; }
}
