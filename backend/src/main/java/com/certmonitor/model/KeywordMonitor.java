package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Serbest-form anahtar kelime izleme monitörü: bir URL'nin HTTP yanıt gövdesinde
 * {@code keyword} aranır (case-insensitive). {@code alertCondition}:
 * NOT_CONTAINS → kelime bulunmazsa alarm; CONTAINS → kelime bulunursa alarm.
 * Envantere bağlı değildir; takım {@code teamId} ile açıkça atanır (alarm yönlendirmesi).
 */
@Entity
@Table(name = "keyword_monitors")
@Data
@NoArgsConstructor
public class KeywordMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String keyword;

    /** Eski binary koşul (legacy) — operatör/adet modelinden create/update'te türetilir. */
    @Column(name = "alert_condition", nullable = false)
    private String alertCondition = "NOT_CONTAINS";

    /** Adet koşulu operatörü: GTE (≥) | LTE (≤) | EQ (=) | GT (>) | LT (<).
     *  Sağlıklı = geçişAdedi <operatör> matchCount. */
    @Column(name = "match_operator")
    private String matchOperator = "GTE";

    /** Operatörle karşılaştırılan eşik adet (N). */
    @Column(name = "match_count")
    private Integer matchCount = 1;

    /** Mantıksal grup (ör. "X Sistemleri") — filtreleme/gruplama; serbest-form. */
    @Column(name = "group_name")
    private String groupName;

    /** Sorumlu takım — alarm yönlendirmesi (envanterden bağımsız). */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 60;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 10000;

    /** Per-monitor teyit: alarm öncesi doğrulama denemesi sayısı (varsayılan 3). */
    @Column(name = "confirm_attempts")
    private Integer confirmAttempts = 3;

    /** Per-monitor teyit: denemeler arası saniye (varsayılan 30). */
    @Column(name = "confirm_interval_seconds")
    private Integer confirmIntervalSeconds = 30;

    /** Recovery period: alarmın otomatik kapanması için gereken ardışık başarılı kontrol sayısı
     *  (varsayılan 1 = ilk başarılı kontrolde kapat). */
    @Column(name = "recovery_checks")
    private Integer recoveryChecks = 1;

    /** Cache busting: satır başına "Name: Value" özel HTTP header'ları (ör. Cache-Control: no-cache).
     *  URL'de {timestamp} placeholder'ı her kontrolde güncel Unix saniye ile değiştirilir. */
    @Column(name = "custom_headers", columnDefinition = "TEXT")
    private String customHeaders;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;
}
