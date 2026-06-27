package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir domain'in otorite/delegasyon anlık görüntüsü — DNS hijack tespiti için
 * (authoritative NS seti + SOA serial + SOA primary-NS). Yalnız DEĞİŞİMDE append
 * edilir: hem otorite-değişim geçmişi hem de bir sonraki taramada karşılaştırma kaynağı.
 */
@Entity
@Table(name = "dns_authority_snapshots",
       indexes = @Index(name = "idx_dns_authority_domain", columnList = "domain"))
@Data
@NoArgsConstructor
public class DnsAuthoritySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String domain;

    /** Authoritative NS seti — sıralı, satır (\n) ayrılmış. */
    @Column(name = "ns_values", columnDefinition = "TEXT")
    private String nsValues;

    @Column(name = "soa_serial")
    private Long soaSerial;

    @Column(name = "soa_primary_ns")
    private String soaPrimaryNs;

    @Column(name = "checked_at")
    private String checkedAt;
}
