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
        @Index(name = "idx_ae_domain_type_open", columnList = "domain,alertType,resolved")
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

    // ── Transient enrichment (populated by AdminController, not persisted) ──
    @Transient private String  syTeamName;
    @Transient private String  ugTeamName;
    @Transient private Integer certTier;
    @Transient private Long    emailSentCount;
    @Transient private Long    emailFailedCount;
}
