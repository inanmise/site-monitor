package com.certmonitor.dto;

import com.certmonitor.model.LatestCheck;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class CertificateDto {

    private String domain;
    private String subject;
    private String issuer;

    @JsonProperty("issuer_cn")
    private String issuerCn;

    @JsonProperty("not_before")
    private String notBefore;

    @JsonProperty("not_after")
    private String notAfter;

    @JsonProperty("days_remaining")
    private Integer daysRemaining;

    private Boolean warning;
    private String status;
    private String error;

    @JsonProperty("alert_level")
    private String alertLevel;
    private List<String> san;

    @JsonProperty("checked_at")
    private String checkedAt;

    private String fingerprint;

    @JsonProperty("chain_status")
    private String chainStatus;

    @JsonProperty("deployment_status")
    private String deploymentStatus;

    @JsonProperty("intermediate_expiry")
    private String intermediateExpiry;

    @JsonProperty("intermediate_days_remaining")
    private Integer intermediateDaysRemaining;

    @JsonProperty("revocation_status")
    private String revocationStatus;

    // Extended certificate metadata
    @JsonProperty("serial_number")
    private String serialNumber;

    @JsonProperty("signature_algorithm")
    private String signatureAlgorithm;

    @JsonProperty("public_key_algorithm")
    private String publicKeyAlgorithm;

    @JsonProperty("public_key_size")
    private Integer publicKeySize;

    @JsonProperty("subject_dn")
    private String subjectDn;

    @JsonProperty("issuer_dn")
    private String issuerDn;

    @JsonProperty("key_usage")
    private List<String> keyUsage;

    @JsonProperty("ext_key_usage")
    private List<String> extKeyUsage;

    @JsonProperty("is_ca")
    private Boolean isCa;

    @JsonProperty("ocsp_url")
    private String ocspUrl;

    @JsonProperty("crl_url")
    private String crlUrl;

    /** Criticality tier from inventory (1–4, null = unclassified) */
    private Integer tier;

    public static CertificateDto from(LatestCheck c, List<String> sanList,
                                      List<String> keyUsageList, List<String> extKeyUsageList) {
        CertificateDto dto = new CertificateDto();
        dto.domain = c.getDomain();
        dto.subject = c.getSubject();
        dto.issuer = c.getIssuer();
        dto.issuerCn = c.getIssuerCn();
        dto.notBefore = c.getNotBefore();
        dto.notAfter = c.getNotAfter();
        dto.daysRemaining = c.getDaysRemaining();
        dto.warning = c.getWarning();
        dto.status = c.getStatus();
        dto.error = c.getError();
        dto.san = sanList;
        dto.checkedAt = c.getCheckedAt();
        dto.fingerprint = c.getFingerprint();
        dto.chainStatus = c.getChainStatus();
        dto.deploymentStatus = c.getDeploymentStatus();
        dto.intermediateExpiry = c.getIntermediateExpiry();
        dto.intermediateDaysRemaining = c.getIntermediateDaysRemaining();
        dto.revocationStatus = c.getRevocationStatus();
        dto.serialNumber = c.getSerialNumber();
        dto.signatureAlgorithm = c.getSignatureAlgorithm();
        dto.publicKeyAlgorithm = c.getPublicKeyAlgorithm();
        dto.publicKeySize = c.getPublicKeySize();
        dto.subjectDn = c.getSubjectDn();
        dto.issuerDn = c.getIssuerDn();
        dto.keyUsage = keyUsageList;
        dto.extKeyUsage = extKeyUsageList;
        dto.isCa = c.getIsCa();
        dto.ocspUrl = c.getOcspUrl();
        dto.crlUrl = c.getCrlUrl();
        return dto;
    }
}
