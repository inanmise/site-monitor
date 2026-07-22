package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "alert_events",
    indexes = {
        @Index(name = "idx_ae_domain",           columnList = "domain"),
        @Index(name = "idx_ae_resolved",         columnList = "resolved"),
        @Index(name = "idx_ae_alert_level",      columnList = "alertLevel"),
        @Index(name = "idx_ae_domain_type_open", columnList = "domain,alertType,resolved"),
        @Index(name = "idx_ae_storm_id",         columnList = "stormId")
    }
)
@Data
@NoArgsConstructor
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String domain;

    /** WARNING, HIGH, CRITICAL */
    @Column(nullable = false)
    private String alertLevel;

    /** EXPIRY, CHAIN_BROKEN, REVOKED, MISMATCH */
    @Column(nullable = false)
    private String alertType;

    @Column(columnDefinition = "TEXT")
    private String message;

    private Integer daysRemaining;

    /** Sorumlu takım — özellikle serbest-form izleme (keyword/ping) çözüm bildiriminde alıcıyı
     *  (yalnız takım) buradan bulmak için (url/host envanterde olmadığından). Oluşturulurken doldurulur. */
    private Long teamId;

    @Column(columnDefinition = "TEXT")
    private String notifiedContacts;

    /** Alarm anı context snapshot'ı (JSON) — özellikle keyword/ping çözüldü e-postasında
     *  hangi kelime/koşul/ne bulundu detayını göstermek için (url/host envanterde yok). */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(nullable = false)
    private Boolean acknowledged = false;

    private String acknowledgedBy;
    private String acknowledgedAt;

    @Column(nullable = false)
    private Boolean resolved = false;

    private String resolvedAt;
    private String resolvedBy;

    private String createdAt;
    private String lastReAlertAt;

    /** Kaç kez re-alert gönderildi (ilk alarm hariç) — e-postadaki "Bu Hatırlatma (#N)" için. */
    @Column(name = "realert_count")
    private Integer realertCount = 0;

    /** Alarm fırtınası (storm) bağı — bu incident bir toplu storm'un parçasıysa storm id'si; değilse null.
     *  Set edildiğinde bireysel bildirim bastırılır (toplu alarm/recovery storm üzerinden gider). */
    private Long stormId;

    /** Monitörün grubu (group_name) — per-group storm scoping için oluşturulurken damgalanır (yalnız
     *  per-group modu açıkken StormService tarafından çözülür); cert-envanteri/grupsuz izlemede null. */
    @Column(name = "group_name")
    private String groupName;

    // ── Transient enrichment (populated by AdminController, not persisted) ──
    @Transient private String  syTeamName;
    @Transient private String  ugTeamName;
    @Transient private Integer certTier;
    @Transient private Long    emailSentCount;
    @Transient private Long    emailFailedCount;
}
