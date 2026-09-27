package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 7/24 İzleme Ekibi (NOC) teslim izi (2026-09-27) — "bu alarmın/fırtınanın açılışı NOC'a gitti mi?" sorusunun
 * TEK cevabı. İki kural buna dayanır:
 * <ul>
 *   <li>Alarm başına EN FAZLA BİR açılış e-postası: {@code dedupe_key} tekil ({@code alert:<id>:OPEN},
 *       {@code storm:<id>:OPEN} …). Günlük tekrar alarmı ve eskalasyon ikinci NOC e-postası üretmez.</li>
 *   <li>"ÇÖZÜLDÜ" yalnız açılış GİTTİYSE gider: açılış satırının durumu {@code SENT}/{@code QUEUED_RETRY} olmalı.</li>
 * </ul>
 * Fırtına e-postasına giren her üye için de alarm satırı yazılır — üye sonradan tek başına çözülünce NOC,
 * açılışını fırtına e-postasında gördüğü alarmın kapanışını da alır.
 */
@Entity
@Table(name = "noc_deliveries",
       uniqueConstraints = @UniqueConstraint(name = "ux_noc_delivery_key", columnNames = "dedupe_key"),
       indexes = {
           @Index(name = "idx_noc_delivery_alert", columnList = "alert_event_id"),
           @Index(name = "idx_noc_delivery_created", columnList = "created_at")
       })
@Getter @Setter @NoArgsConstructor
public class NocDelivery {

    public static final String OPEN = "OPEN";
    public static final String RESOLVE = "RESOLVE";
    /** Fırtına sürerken katılan kapsanan üyeler için toplu güncelleme (anahtar {@code storm:<id>:UPD:<5dk-dilimi>}). */
    public static final String UPDATE = "UPDATE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedupe_key", nullable = false, length = 80)
    private String dedupeKey;

    @Column(name = "alert_event_id")
    private Long alertEventId;

    @Column(name = "storm_id")
    private Long stormId;

    /** OPEN | RESOLVE */
    @Column(nullable = false, length = 10)
    private String phase;

    /** SENT | QUEUED_RETRY… | FAILED: … | SKIPPED_DISABLED | SENT_VIA_STORM */
    @Column(columnDefinition = "TEXT")
    private String status;

    @Column(name = "monitor_type", length = 16)
    private String monitorType;

    @Column(name = "monitor_id")
    private Long monitorId;

    @Column(name = "team_id")
    private Long teamId;

    /** Gönderilen grup kimlikleri (virgüllü) — çözüm e-postası AÇILIŞLA AYNI gruplara gider. */
    @Column(name = "group_ids", length = 500)
    private String groupIds;

    @Column(name = "group_names", length = 500)
    private String groupNames;

    @Column(name = "recipient_count")
    private Integer recipientCount;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(name = "created_at", nullable = false, length = 30)
    private String createdAt;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;
}
