package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "alert_events")
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

    /** JSON array of notified contact names/emails */
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
}
