package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alarm akışının merkezi sisteme AÇIK olarak bildirdiği olaylar (2026-10-30).
 *
 * <p><b>Neden.</b> Merkezi sistem olay anahtarını gelen satırdan kurar ({@code kaynak|||RCI|||SEVERITY}). Bir alarmın satırı
 * ömrü boyunca farklı anahtarlarla gidebilir: seviye değişir (Warning → Critical), alan adı / izleme yeniden adlandırılır,
 * seviye eşlemesi ya da kaynak metni değişir. Merkezi sistemde AÇIK görünen her anahtar mutlaka bir "çözüldü" almalı —
 * aksi halde olay sonsuza dek açık kalır. Bu tablo GÖNDERİLEN anahtarı ve satırın o anki kopyasını ({@code snapshot}) tutar;
 * anahtar değişince, alarm çözülünce, kapsamdan çıkınca ya da silinince kapanış GÖNDERİLMİŞ değerlerle kurulur.
 *
 * <p>Kapanış, çözülme penceresi boyunca, en az bir kez teslim edilene kadar ({@code closure_sent_at}) ve ilk teslimden
 * sonra bir pencere daha akışta kalır (yanıt kaybolsa da merkezi sistem alır) — akış
 * kapalıyken / kimse çağırmazken kapanan alarm da bildirilir (RESOLVEDTIME gerçek kapanış anıdır). Çok pod: (alarm, anahtar
 * özeti) tekil; kapanış ve teslim işaretleri koşullu.
 */
@Entity
@Table(name = "alarm_feed_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_afe_alert_key", columnNames = {"alert_event_id", "key_hash"}),
        indexes = {
                @Index(name = "idx_afe_closed", columnList = "closed_at"),
                @Index(name = "idx_afe_closure_sent", columnList = "closure_sent_at")
        })
@Data
@NoArgsConstructor
public class AlarmFeedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_event_id", nullable = false)
    private Long alertEventId;

    /** SHA-256(FILTERSOURCE|||RCI|||SEVERITY) — gönderilen anahtar. */
    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    /** Gönderildiği andaki SiteMonitor seviyesi (CRITICAL | HIGH | WARNING). */
    @Column(nullable = false, length = 16)
    private String level;

    /** İlk gönderim anı (UTC ISO). */
    @Column(name = "sent_at", length = 30)
    private String sentAt;

    /** Kapanış anı (UTC ISO); null = hâlâ açık bildiriliyor. Çözülen alarmda alarmın GERÇEK çözülme anı. */
    @Column(name = "closed_at", length = 30)
    private String closedAt;

    /** RESOLVED | LEVEL_CHANGE | KEY_CHANGE | SCOPE | GONE. */
    @Column(name = "closed_reason", length = 16)
    private String closedReason;

    /** Kapanışın en az bir yanıtta gönderildiği an (UTC ISO); null = henüz gönderilmedi. */
    @Column(name = "closure_sent_at", length = 30)
    private String closureSentAt;

    /**
     * Kapanışı fark ettiğimiz an (UTC; {@code closed_at} gerçek çözülme anıdır, eski olabilir). Hiç teslim edilemeyen
     * kapanış bundan 7 gün sonra bırakılır — eski bir çözülme fark edilir edilmez silinmesin.
     */
    @Column(name = "closed_noted_at", length = 30)
    private String closedNotedAt;

    /** Gönderilen AÇIK satırın kopyası (JSON; ek iç alanlar {@code _target}, {@code _problem}). */
    @Column(columnDefinition = "TEXT")
    private String snapshot;
}
