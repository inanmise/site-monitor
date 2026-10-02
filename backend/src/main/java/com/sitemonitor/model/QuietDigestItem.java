package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Sessiz saat özetine ertelenen alarm (2026-10-01, onaylı öneri 15) — "bu takımın bu alarmla ilgili bildirimi bu
 * pencerede ertelendi; pencere bitince özet e-postasında yer alacak" kaydı ve tekilleştirme kilidi.
 *
 * <p><b>Tekillik:</b> {@code (alert_event_id, team_id, window_key)} UNIQUE — aynı pencerede aynı alarmın ikinci, üçüncü
 * ertelemesi (ör. kısa aralıklı yeniden uyarı) yeni satır açmaz; özet her (takım, pencere, alarm) için en fazla BİR kez
 * gönderilir, pod/yeniden başlatma fark etmeksizin.
 *
 * <p><b>Yaşam döngüsü:</b> {@code deferred_at} (erteleme) → isteğe bağlı {@code superseded_at} (takım pencere içinde bu
 * alarm için ertelenmeyen bir bildirim aldı — YÜKSEK/KRİTİK seviye artışı, elle yeniden gönderim ya da normal çözüm
 * postası; özet onu tekrar etmez) → {@code digest_sent_at} + {@code digest_status} (özet işi sahiplendi / gönderdi).
 * {@code digest_sent_at IS NULL} = bekleyen kayıt; çözüm postası "açılışı ertelenmiş ve özeti gitmemiş" alarmı
 * buradan tanır ({@link #openingDeferred}).
 *
 * <p>Saklama: {@code RetentionCatalog} "quiet-digest-items" (bildirim geçmişi penceresi; bekleyen satır silinmez).
 */
@Entity
@Table(name = "quiet_digest_items",
       uniqueConstraints = @UniqueConstraint(name = "ux_qdi_event_team_window",
               columnNames = {"alert_event_id", "team_id", "window_key"}),
       indexes = {
               @Index(name = "idx_qdi_pending", columnList = "digest_sent_at,window_end"),
               @Index(name = "idx_qdi_deferred_at", columnList = "deferred_at")
       })
@Getter @Setter @NoArgsConstructor
public class QuietDigestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_event_id", nullable = false)
    private Long alertEventId;

    /** Bildirimi ertelenen takım (alarmın SY ya da UG sahibi; eskalasyon adımında kişinin takımı). */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** Pencerenin yerel başlangıcı (Europe/Istanbul), ör. {@code 2026-10-01T22:00}. */
    @Column(name = "window_key", nullable = false, length = 20)
    private String windowKey;

    /** Pencerenin bitişi, UTC {@code yyyy-MM-ddTHH:mm:ss} — özet bu andan sonra gönderilir. */
    @Column(name = "window_end", nullable = false, length = 30)
    private String windowEnd;

    /** İlk ertelemedeki alarm seviyesi (özet güncel seviyeyi olaydan okur). */
    @Column(name = "alert_level", length = 16)
    private String alertLevel;

    /** İlk ertelenen bildirimin tetiği: INITIAL / ESCALATION / DAILY_REALERT / ESCALATION_STEP. */
    @Column(name = "first_trigger", length = 30)
    private String firstTrigger;

    /** Ertelenen ilk bildirim alarmın AÇILIŞI mıydı? true ise takım alarmı hiç duymadı → çözümü de özete katlanır. */
    @Column(name = "opening_deferred")
    private Boolean openingDeferred;

    /** Erteleme anı, UTC {@code yyyy-MM-ddTHH:mm:ss}. */
    @Column(name = "deferred_at", nullable = false, length = 30)
    private String deferredAt;

    /** Takım pencere içinde bu alarm için ertelenmeyen bir bildirim aldı (özet tekrar etmez). */
    @Column(name = "superseded_at", length = 30)
    private String supersededAt;

    /** Özet işinin sahiplendiği/gönderdiği an — null = bekliyor. */
    @Column(name = "digest_sent_at", length = 30)
    private String digestSentAt;

    /** SENT / FAILED: … / SKIPPED: … — sahiplenme sırasında geçici {@code SENDING:<belirteç>}. */
    @Column(name = "digest_status", length = 300)
    private String digestStatus;
}
