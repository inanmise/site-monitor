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
public class ScriptedMonitor {

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

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;
}
