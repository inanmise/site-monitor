package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fırtına push'u ↔ üye alarm BAĞI (2026-10-04, kullanıcı isteği: "push fırtınaya devredilse bile fırtına ile giden push
 * mesajı ilgili alarmla ilişkilendirilsin; alarmın geçmişinden fırtına push'unun ne zaman iletildiğini göreyim").
 *
 * <p>Fırtına push'u ({@code user_push_deliveries}, anahtar {@code storm:<id>:INITIAL} / {@code storm:<id>:DAILY_REALERT:<gün>}
 * / {@code storm-resolved:<id>}) olaysızdır — satırda {@code alert_event_id} yoktur. Bu tablo, o bildirimin KAPSADIĞI üye
 * alarmları kalıcı kılar: bildirim kararı verildiği anda (gönderim satırları ya da kanal karar satırı yazıldıktan sonra)
 * takımın push üyesi başına bir satır, TEK JDBC batch ile ({@code StormPushCoverageService.SQL_INSERT}, çakışma yutulur).
 * Açılış ve günlük tekrar o anda düşük olan üyeleri, çözüm yalnız KURTULAN üyeleri kapsar (çözüm postasının günlük
 * satırıyla aynı küme).
 *
 * <p>Yazımlar yalnız JDBC'dir (alarm yolu, O(1) gidiş-dönüş); bu sınıf şema (ddl-auto) ve retention kapsamı içindir.
 * Fırtına ya da alarm silinince satır yetim kalır — {@code storm-push-coverage-orphan}.
 */
@Entity
@Table(name = "storm_push_coverage",
       uniqueConstraints = @UniqueConstraint(name = "ux_spc_key_event", columnNames = {"push_key", "alert_event_id"}),
       indexes = {
               @Index(name = "idx_spc_event", columnList = "alert_event_id"),
               @Index(name = "idx_spc_storm", columnList = "storm_id")
       })
@Data
@NoArgsConstructor
public class StormPushCoverage {

    /** Bildirim tetikleri (fırtına e-postasının tetiğiyle aynı adlar). */
    public static final String TRIGGER_INITIAL = "INITIAL";
    public static final String TRIGGER_DAILY_REALERT = "DAILY_REALERT";
    public static final String TRIGGER_RESOLVE = "RESOLVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storm_id", nullable = false)
    private Long stormId;

    /** Bildirimin gittiği takım (fırtına push'u SY takımına gider) — teslimat satırlarının {@code team_id}'si. */
    @Column(name = "team_id")
    private Long teamId;

    /** Fırtına push'unun tekilleştirme anahtarı = teslimat satırlarının {@code dedupe_key}'i. */
    @Column(name = "push_key", nullable = false, length = 60)
    private String pushKey;

    /** INITIAL / DAILY_REALERT / RESOLVE. */
    @Column(name = "notice_trigger", nullable = false, length = 16)
    private String noticeTrigger;

    @Column(name = "alert_event_id", nullable = false)
    private Long alertEventId;

    /** UTC ISO (yyyy-MM-dd'T'HH:mm:ss) — bildirim kararının anı. */
    @Column(name = "created_at", nullable = false, length = 30)
    private String createdAt;
}
