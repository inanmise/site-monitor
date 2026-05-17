package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "certificate_checks",
    indexes = {
        @Index(name = "idx_cc_domain",      columnList = "domain"),
        @Index(name = "idx_cc_checked_at",  columnList = "checkedAt"),
        @Index(name = "idx_cc_run_id",      columnList = "runId"),
        @Index(name = "idx_cc_domain_ts",   columnList = "domain,checkedAt")
    }
)
@Data
@NoArgsConstructor
public class CertificateCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
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

    private String runId;
    private String checkedAt;
    private String createdAt;
}
