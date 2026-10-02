package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Zamana bağlı eskalasyon adımı kararı (2026-10-01) — "bu alarmın bu seviyesi için bu gecikmeli kişiye adım gitti mi /
 * neden gitmedi?" sorusunun TEK cevabı ve tekilleştirme kilidi.
 *
 * <p><b>Tekillik:</b> {@code (alert_event_id, contact_id, alert_level)} UNIQUE — adım her (alarm, kişi, seviye) için en
 * fazla BİR kez karara bağlanır, pod/yeniden başlatma fark etmeksizin: satır gönderimden ÖNCE sahiplenilir
 * ({@code saveAndFlush}, çakışan pod {@code DataIntegrityViolationException} alır ve vazgeçer). Seviye anahtarda: onay
 * yüzünden atlanan UYARI adımı, alarm KRİTİK'e çıkıp onay düştüğünde yeni seviye için yeniden değerlendirilebilsin.
 *
 * <p><b>Sonuç</b> ({@link #outcome}): {@link #SENDING} → {@link #SENT} (adım gönderildi; kanal sonuçları
 * {@code notification_logs}'ta {@code ESCALATION_STEP} tetiğiyle), {@link #SKIPPED} (onaylı / fırtına / bakım / toplu
 * kesinti / çözüldü — nedeni {@link #reason}'da ve bildirim günlüğünde), {@link #PRIOR} (kişi bu alarmı gecikme
 * tanımlanmadan önce zaten ANLIK almıştı — adım gereksiz, günlüğe yazılmaz). SKIPPED dışındaki her satır kişiyi o
 * alarmın "döngüsüne" alır: yeniden uyarı, seviye artışı, çözüm ve elle yeniden gönderim artık ona da gider.
 *
 * <p>Saklama: {@code RetentionCatalog} "alert-escalation-steps" (bildirim geçmişi penceresi; AÇIK alarmın satırı silinmez).
 */
@Entity
@Table(name = "alert_escalation_steps",
       uniqueConstraints = @UniqueConstraint(name = "ux_aes_event_contact_level",
               columnNames = {"alert_event_id", "contact_id", "alert_level"}),
       indexes = @Index(name = "idx_aes_sent_at", columnList = "sent_at"))
@Getter @Setter @NoArgsConstructor
public class AlertEscalationStep {

    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String SKIPPED = "SKIPPED";
    public static final String PRIOR = "PRIOR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_event_id", nullable = false)
    private Long alertEventId;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    /** Kararın verildiği alarm seviyesi (WARNING / HIGH / CRITICAL). */
    @Column(name = "alert_level", nullable = false, length = 16)
    private String alertLevel;

    /** Karar anındaki kişi gecikmesi (dk) — sonradan değişse de iz kalsın. */
    @Column(name = "delay_minutes")
    private Integer delayMinutes;

    /** Kişinin takımı (karar anında). */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(length = 300)
    private String reason;

    /** Karar (sahiplenme) anı, UTC {@code yyyy-MM-ddTHH:mm:ss}. */
    @Column(name = "sent_at", nullable = false, length = 30)
    private String sentAt;
}
