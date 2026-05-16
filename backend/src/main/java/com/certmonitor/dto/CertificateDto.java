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

    public static CertificateDto from(LatestCheck c, List<String> sanList) {
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
        return dto;
    }
}
