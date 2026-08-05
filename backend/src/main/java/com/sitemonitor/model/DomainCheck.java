package com.sitemonitor.model;

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

    /** source=WHOIS iken .tr yanıtını HANGİ kaynak verdi: isimtescil | trabis | trabis43 (kart bunu gösterir). */
    @Column(name = "whois_provider")
    private String whoisProvider;

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

    /** Registrar'ın IANA ID'si (RDAP entity.publicIds "IANA Registrar ID"). */
    @Column(name = "registrar_iana_id")
    private String registrarIanaId;

    /** DNSSEC durumu: "signed" | "unsigned" | null (bilinmiyor). RDAP secureDNS.delegationSigned / WHOIS DNSSEC satırı. */
    private String dnssec;

    /** EPP status kodları (virgülle) — redemptionPeriod, clientTransferProhibited vb. */
    @Column(name = "status_codes", columnDefinition = "TEXT")
    private String statusCodes;

    /** Nameserver'lar (virgülle). */
    @Column(columnDefinition = "TEXT")
    private String nameservers;

    /** Domain'in A/AAAA'dan çözülen IP adresleri (virgülle). */
    @Column(name = "resolved_ips", columnDefinition = "TEXT")
    private String resolvedIps;

    /** Çözülen IP'lerin reverse-DNS (PTR) host adları (virgülle). */
    @Column(columnDefinition = "TEXT")
    private String hostnames;

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
