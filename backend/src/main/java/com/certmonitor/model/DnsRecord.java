package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dns_records", indexes = {
    @Index(name = "idx_dnsr_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_dnsr_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class DnsRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    @Column(name = "record_type")
    private String recordType;

    @Column(columnDefinition = "TEXT")
    private String value;

    @Column(nullable = false)
    private Boolean changed = false;

    @Column(name = "previous_value", columnDefinition = "TEXT")
    private String previousValue;

    @Column(name = "checked_at")
    private String checkedAt;

    /** TTL of the record in seconds (smallest among returned RRs). */
    @Column
    private Long ttl;

    /** Wall-clock time spent on the DNS lookup, in milliseconds. */
    @Column(name = "response_ms")
    private Long responseMs;

    /** JSON array of authoritative NS hostnames; populated on manual /check or /details. */
    @Column(name = "authoritative_servers", columnDefinition = "TEXT")
    private String authoritativeServers;

    /** JSON object with SOA fields (primary_ns, admin_email, serial, refresh, retry, expire, minimum_ttl). */
    @Column(name = "soa_info", columnDefinition = "TEXT")
    private String soaInfo;
}
