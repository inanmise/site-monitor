package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fırtına ÜYELİĞİ — kalıcı kayıt (2026-09-30, takım bazlı fırtına gözlem ekranı).
 *
 * <p>{@code alert_events.storm_id} yalnız fırtına AÇIKKEN bağdır: kapanışta hâlâ-düşük üyeler bireysel hatta döner ve
 * {@code storm_id} sıfırlanır, eski fırtınadan taşınan üyeler yeni fırtınaya geçer. "Bu fırtınada kim vardı, ne zaman
 * katıldı, duyuruldu mu, nasıl ayrıldı" sorusu ancak buradan cevaplanır. Satır fırtına açılışında (tetikleyen + pencere
 * eşleri), her katılımda ve eski fırtınadan taşımada yazılır; toplu posta gidince {@code announcedAt}, ayrılışta
 * {@code leftAt}/{@code leaveKind} dolar. Fırtına satırı silinince (retention) yetim kalır — {@code alert-storm-members-orphan}.
 */
@Entity
@Table(name = "alert_storm_members",
       uniqueConstraints = @UniqueConstraint(name = "ux_asm_storm_event", columnNames = {"storm_id", "alert_event_id"}),
       // storm_id tek başına indekslenmez: UNIQUE(storm_id, alert_event_id) önde storm_id ile aynı işi görür (2026-10-01)
       indexes = { @Index(name = "idx_asm_event", columnList = "alert_event_id") })
@Data
@NoArgsConstructor
public class AlertStormMember {

    /** Katılım türü. */
    public static final String JOIN_TRIGGER = "TRIGGER";   // eşiği aşmayı tetikleyen alarm
    public static final String JOIN_PEER    = "PEER";      // açılışta penceredeki diğer açık DOWN'lar
    public static final String JOIN_ATTACH  = "ATTACH";    // fırtına sürerken gelen alarm (bireysel bildirim bastırıldı)
    public static final String JOIN_LEGACY  = "LEGACY";    // eski kuruluş geneli fırtınadan sessizce taşındı

    /** Ayrılış türü. */
    public static final String LEAVE_RECOVERED = "RECOVERED";   // fırtına sürerken/kapanırken düzeldi (toplu çözüm postası)
    public static final String LEAVE_NOTIFIED  = "NOTIFIED";    // kapanışta hâlâ düşük, fırtına postasında duyurulmuştu → bildirildi sayıldı
    public static final String LEAVE_UNLINKED  = "UNLINKED";    // kapanışta hâlâ düşük, hiç duyurulmamıştı → bireysel ilk bildirim
    public static final String LEAVE_MOVED     = "MOVED";       // eski fırtınadan takım fırtınasına taşındı
    public static final String LEAVE_RELEASED  = "RELEASED";    // eski fırtına emekliliğinde bildirildi sayılıp bırakıldı

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storm_id", nullable = false)
    private Long stormId;

    @Column(name = "alert_event_id", nullable = false)
    private Long alertEventId;

    /** UTC ISO (yyyy-MM-dd'T'HH:mm:ss). */
    @Column(name = "joined_at", nullable = false, length = 30)
    private String joinedAt;

    @Column(name = "join_kind", nullable = false, length = 12)
    private String joinKind;

    /** Üyeyi listeleyen İLK fırtına postasının anı (açılış ya da günlük tekrar); NULL = hiç duyurulmadı. */
    @Column(name = "announced_at", length = 30)
    private String announcedAt;

    @Column(name = "left_at", length = 30)
    private String leftAt;

    @Column(name = "leave_kind", length = 16)
    private String leaveKind;

    public AlertStormMember(Long stormId, Long alertEventId, String joinedAt, String joinKind) {
        this.stormId = stormId;
        this.alertEventId = alertEventId;
        this.joinedAt = joinedAt;
        this.joinKind = joinKind;
    }
}
