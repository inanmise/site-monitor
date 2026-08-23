package com.sitemonitor.model;

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

    /** Kontrolün toplam süresi (TLS el sıkışma dahil), ms. Checker bunu her zaman ölçüyordu
     *  (elapsed_ms) ama saklanmıyordu → sertifika için yanıt süresi grafiği kurulamıyordu.
     *  Geriye dönük veri YOK: kolon eklendiği andan itibaren dolar. */
    private Integer responseMs;
    private Boolean warning;
    private String status;
    private String error;

    @Column(columnDefinition = "TEXT")
    private String san;

    // Security chain fields (previously missing from history)
    private String fingerprint;
    private String chainStatus;
    private String revocationStatus;
    /** TRUSTED / UNTRUSTED / UNKNOWN — zincir cacerts veya admin CA paketiyle güven köküne bağlanıyor mu. */
    private String trustStatus;
    private String deploymentStatus;
    private String intermediateExpiry;
    private Integer intermediateDaysRemaining;

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

    /** Anlaşılan TLS sürümü / cipher suite — sağlık değerlendirmesinin kaynağı (bkz. LatestCheck). */
    @Column(length = 20)
    private String tlsVersion;

    @Column(length = 100)
    private String cipherSuite;

    private String runId;
    private String checkedAt;
    private String createdAt;

    /** Error classification tag: DNS, NETWORK, SSL, CERT, UNKNOWN — null for non-error checks. */
    @Column(length = 32)
    private String errorClass;

    /** Kontrol anında domain bakım penceresindeyse true — dashboard uptime % hesabından hariç tutulur. */
    @Column
    private Boolean maintenance = false;
}
