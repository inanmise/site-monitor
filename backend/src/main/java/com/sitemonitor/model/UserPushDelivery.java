package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kişi-bazlı webhook bildirimi — outbox VE teslimat günlüğü tek tabloda.
 *
 * <p>Satır önce PENDING yazılır (DB-outbox), tek-worker gönderir, sonuç aynı satıra işlenir.
 * Bellekte kuyruk YOK: pod yeniden başlasa da PENDING satırlar durur ve sınırsız in-memory
 * retry'ın OOM riski hiç doğmaz.
 *
 * <p><b>Anti-loop DB seviyesinde:</b> {@code UNIQUE(alert_event_id, dedupe_key, username)} —
 * aynı olayın aynı fazı aynı kişiye kod hatasında bile iki kez yazılamaz. {@code dedupeKey}:
 * OPEN/RESOLVE'da tetik adı (olay başına 1×); RE_ALERT'te {@code RE_ALERT:<gün>} (günde 1× —
 * mail'in günlük dedupe aynası); RESEND/TEST'te tetik+batchId (kasıtlı tekrar serbest).
 *
 * <p><b>Gizlilik:</b> sicil ({@code username}) yalnız bu tabloda ve arayüzde yaşar; sunucu
 * logları yalnız ADET yazar. {@code displayName} gönderim ANINDAKİ ad — kişi silinse de günlük
 * okunur kalır.
 */
@Entity
@Table(name = "user_push_deliveries",
        uniqueConstraints = @UniqueConstraint(name = "ux_push_event_phase_user",
                columnNames = {"alert_event_id", "dedupe_key", "username"}),
        indexes = {
                @Index(name = "idx_push_created", columnList = "created_at"),
                @Index(name = "idx_push_username", columnList = "username"),
                @Index(name = "idx_push_team", columnList = "team_id"),
                @Index(name = "idx_push_monitor", columnList = "monitor_type, monitor_id"),
                @Index(name = "idx_push_status", columnList = "status"),
        })
@Data
@NoArgsConstructor
public class UserPushDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TEST satırlarında null (olay yok) — dedupe orada batchId ile. */
    @Column(name = "alert_event_id")
    private Long alertEventId;

    /** OPEN / RE_ALERT / RESOLVE / RESEND / TEST. */
    @Column(name = "push_trigger", nullable = false, length = 20)
    private String trigger;

    /** UNIQUE kısıtın faz bileşeni — sınıf javadoc'undaki kurala göre üretilir. */
    @Column(name = "dedupe_key", nullable = false, length = 60)
    private String dedupeKey;

    @Column(name = "monitor_type", length = 20)
    private String monitorType;

    @Column(name = "monitor_id")
    private Long monitorId;

    @Column(name = "monitor_name", length = 300)
    private String monitorName;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "alert_level", length = 20)
    private String alertLevel;

    /** Alıcının sicili (K1: AppUser.username, N-biçimli). */
    @Column(nullable = false, length = 100)
    private String username;

    /** Gönderim anındaki ad-soyad — kişi silinse de günlük okunur kalsın. */
    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(length = 200)
    private String title;

    /** Gönderilen metin AYNEN (kırpılmışsa kırpılmış hâli). */
    @Column(columnDefinition = "TEXT")
    private String message;

    /** PENDING / SENT / FAILED / SKIPPED_* / RATE_LIMITED / CIRCUIT_OPEN. */
    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(columnDefinition = "TEXT")
    private String error;

    private Integer attempts = 0;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "sent_at")
    private String sentAt;

    /** Aynı toplu isteğin alt satırlarını bağlar (tek istek → tek batchId). */
    @Column(name = "batch_id", length = 40)
    private String batchId;

    /**
     * API yanıtındaki {@code notificationId} — kanıt zinciri: bu push API'ye ulaştı ve API ona
     * bu numarayı verdi; kurum tarafında iz sürerken tek anahtar. Tek toplu istek olduğundan
     * batch'in TÜM alt satırlarına aynı değer yazılır. Ayrıştırılamazsa null kalır — gönderim
     * yine SENT'tir (kimlik alınamadı diye başarı FAILED'a çevrilmez).
     */
    @Column(name = "notification_id", length = 60)
    private String notificationId;

    /** Ham API yanıtının ilk ~500 karakteri — error alanını başarı yolunda kirletmemek için ayrı. */
    @Column(name = "raw_response", length = 600)
    private String rawResponse;
}
