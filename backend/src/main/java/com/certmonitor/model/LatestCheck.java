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
    /** TRUSTED / UNTRUSTED / UNKNOWN — zincir cacerts veya admin CA paketiyle güven köküne bağlanıyor mu. */
    private String trustStatus;

    @Column(columnDefinition = "TEXT")
    private String chainDetails;

    // Extended certificate metadata
    private String serialNumber;
    private String signatureAlgorithm;
    private String publicKeyAlgorithm;
    private Integer publicKeySize;

    @Column(columnDefinition = "TEXT")
    private String subjectDn;

    @Column(columnDefinition = "TEXT")
    private String issuerDn;

    @Column(columnDefinition = "TEXT")
    private String keyUsage;

    @Column(columnDefinition = "TEXT")
    private String extKeyUsage;

    private Boolean isCa;
    private String ocspUrl;
    private String crlUrl;

    /** Check transport metadata — path ("proxy"/"direct") and TLS mode the
     *  final attempt used. Null on rows written before v18.53. */
    private String via;
    private String tlsModeUsed;

    private String checkedAt;
    private String updatedAt;
}
