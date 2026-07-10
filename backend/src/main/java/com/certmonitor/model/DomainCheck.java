package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Bir domain süre-bitişi kontrolünün sonucu (geçmiş + trend + değişiklik tespiti bunun üstünden çalışır). */
@Entity
@Table(name = "domain_checks", indexes = {
    @Index(name = "idx_dc_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_dc_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class DomainCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Veri kaynağı: RDAP | WHOIS | NONE (sorgu başarısız). */
    private String source;

    /** 4-seviyeli durum: OK | WARNING | CRITICAL | UNKNOWN. */
    @Column(nullable = false)
    private String status = "UNKNOWN";

    @Column(name = "days_remaining")
    private Integer daysRemaining;

    @Column(name = "expiry_date")
    private String expiryDate;

    @Column(name = "registration_date")
    private String registrationDate;

    @Column(name = "last_changed")
    private String lastChanged;

    private String registrar;

    /** EPP status kodları (virgülle) — redemptionPeriod, clientTransferProhibited vb. */
    @Column(name = "status_codes", columnDefinition = "TEXT")
    private String statusCodes;

    /** Nameserver'lar (virgülle). */
    @Column(columnDefinition = "TEXT")
    private String nameservers;

    /** DNS çapraz doğrulama: domain'in NS kayıtları çözülüyor mu. */
    @Column(name = "ns_resolves")
    private Boolean nsResolves;

    /** Bu kontrolde registrar/NS/status değişikliği tespit edildi mi (hijack sinyali). */
    private Boolean changed = false;

    /** Ham yanıt özeti (UI/denetim — kısaltılmış). */
    @Column(name = "raw_summary", columnDefinition = "TEXT")
    private String rawSummary;

    private String error;

    @Column(name = "checked_at")
    private String checkedAt;
}
