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
 * Alarm akışının bir alarm için merkezi sisteme BİLDİRDİĞİ seviyeler (2026-10-30).
 *
 * <p><b>Neden.</b> Merkezi sistem olay anahtarını {@code kaynak|||RCI|||SEVERITY} diye kurar. Alarmın seviyesi değişince (ör.
 * sertifika süresi: Warning → Major → Critical) merkezi sistemde YENİ bir olay doğar; eski seviyenin olayına "çözüldü"
 * gönderilmezse sonsuza dek açık kalır. Bu tablo hangi (alarm, seviye) çiftinin AÇIK bildirildiğini tutar: seviye değişince,
 * alarm çözülünce ya da alarm akış kapsamından çıkınca eski çift kapatılır ({@code closed_at} + neden) ve çözülme penceresi
 * boyunca o seviyeyle STATUS=RESOLVED satırı döner.
 *
 * <p>Yalnız akış yanıtı ÜRETİLİRKEN yazılır (kimse çağırmadıysa kimse bir şey görmemiştir). Çok pod: (alarm, seviye) tekil;
 * kapanış koşullu ({@code closed_at IS NULL}).
 */
@Entity
@Table(name = "alarm_feed_severities",
        uniqueConstraints = @UniqueConstraint(name = "uk_afs_alert_level", columnNames = {"alert_event_id", "level"}),
        indexes = @Index(name = "idx_afs_closed", columnList = "closed_at"))
@Data
@NoArgsConstructor
public class AlarmFeedSeverity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_event_id", nullable = false)
    private Long alertEventId;

    /** SiteMonitor seviyesi: CRITICAL | HIGH | WARNING (eşleme ayarı değişse de anlam sabit kalsın diye ham değer). */
    @Column(nullable = false, length = 16)
    private String level;

    /** Bu seviyenin ilk bildirildiği an (UTC ISO). */
    @Column(name = "sent_at", length = 30)
    private String sentAt;

    /** Kapanış anı (UTC ISO); null = hâlâ açık bildiriliyor. */
    @Column(name = "closed_at", length = 30)
    private String closedAt;

    /** LEVEL_CHANGE | RESOLVED | SCOPE | GONE. */
    @Column(name = "closed_reason", length = 16)
    private String closedReason;
}
