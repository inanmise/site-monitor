package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "latest_checks",
    indexes = {
        @Index(name = "idx_lc_warning",    columnList = "warning"),
        @Index(name = "idx_lc_status",     columnList = "status"),
        @Index(name = "idx_lc_checked_at", columnList = "checkedAt")
    }
)
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

    private String fingerprint;
    private String chainStatus;
    private String deploymentStatus;
    private String intermediateExpiry;
    private Integer intermediateDaysRemaining;
    private String revocationStatus;

    @Column(columnDefinition = "TEXT")
    private String chainDetails;

    private String checkedAt;
    private String updatedAt;
}
