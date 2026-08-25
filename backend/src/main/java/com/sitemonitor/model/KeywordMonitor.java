package com.sitemonitor.model;

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
    private Integer recoveryChecks = 3;

    /** Recovery aktif re-check aralığı (sn): recoveryChecks denemesi bu süre arayla yapılır (keyword/ping).
     *  Set ise recovery aktif döngüyle yürür; null → pasif (kontrol aralığında sayım). */
    @Column(name = "recovery_interval_seconds")
    private Integer recoveryIntervalSeconds = 30;

    /** Cache busting: satır başına "Name: Value" özel HTTP header'ları (ör. Cache-Control: no-cache).
     *  URL'de {timestamp} placeholder'ı her kontrolde güncel Unix saniye ile değiştirilir. */
    @Column(name = "custom_headers", columnDefinition = "TEXT")
    private String customHeaders;

    /** Büyük/küçük harf DUYARLI eşleşme (varsayılan false = duyarsız). */
    @Column(name = "case_sensitive")
    private Boolean caseSensitive = false;

    /** Serbest etiketler — virgülle ayrılmış (organizasyon/filtreleme). */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** E-posta bildirimi açık mı (varsayılan true). SMS/Voice/Push UI'da devre dışı. */
    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    /** Yavaş yanıt alarmı açık mı: açıksa response_ms eşiği aşılınca KEYWORD_SLOW. */
    @Column(name = "slow_response_enabled")
    private Boolean slowResponseEnabled = false;

    /** Yavaş yanıt eşiği (ms). */
    @Column(name = "slow_threshold_ms")
    private Integer slowThresholdMs = 3000;

    /** SSL hata kontrolü (URL host'unun TLS zinciri/geçerliliği) — yavaş döngü → KEYWORD_SSL. */
    @Column(name = "check_ssl_errors")
    private Boolean checkSslErrors = false;

    /** SSL son-kullanım hatırlatması — KEYWORD_SSL. */
    @Column(name = "ssl_expiry_reminders")
    private Boolean sslExpiryReminders = false;

    /** Domain (registrar/WHOIS) son-kullanım hatırlatması — KEYWORD_DOMAIN_EXPIRY. */
    @Column(name = "domain_expiry_reminders")
    private Boolean domainExpiryReminders = false;

    /** SSL bitişi öncesi hatırlatma gün eşikleri (CSV). */
    @Column(name = "ssl_reminder_days")
    private String sslReminderDays = "30,14,7";

    /** Domain bitişi öncesi hatırlatma gün eşikleri (CSV). */
    @Column(name = "domain_reminder_days")
    private String domainReminderDays = "30,14,7";

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
