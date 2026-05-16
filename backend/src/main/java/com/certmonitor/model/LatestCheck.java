package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "latest_checks")
@Data
@NoArgsConstructor
public class LatestCheck {

    @Id
    private String domain;

    private String subject;
    private String issuer;
    private String issuerCn;
    private String notBefore;
    private String notAfter;
    private Integer daysRemaining;
    private Boolean warning;
    private String status;
    private String error;

    @Column(columnDefinition = "TEXT")
    private String san;

    /** SHA-256 hex fingerprint of the leaf (endpoint-served) certificate */
    private String fingerprint;

    /** VALID / BROKEN / REVOKED / UNKNOWN — full chain health */
    private String chainStatus;

    /** OK / INCOMPLETE / UNKNOWN — served cert vs inventory expected fingerprint */
    private String deploymentStatus;

    /** ISO date of the soonest-expiring non-leaf cert (intermediate or root) */
    private String intermediateExpiry;
    private Integer intermediateDaysRemaining;

    /** VALID / REVOKED / UNKNOWN — OCSP/CRL result for leaf cert */
    private String revocationStatus;

    /** JSON array — full chain details [{position, subject, not_after, days_remaining, is_root}] */
    @Column(columnDefinition = "TEXT")
    private String chainDetails;

    private String checkedAt;
    private String updatedAt;
}
