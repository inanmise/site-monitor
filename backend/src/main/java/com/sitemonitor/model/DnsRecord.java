package com.sitemonitor.model;

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

    /**
     * True when the new value differs from the previous one only because of
     * DNS round-robin / GeoDNS rotation — i.e. the two sets of records still
     * intersect but the order or subset changed. Distinct from {@code changed},
     * which is reserved for actual end-to-end value changes (no intersection).
     *
     * Stored nullable so Hibernate ddl-auto=update can add the column to an
     * existing table without conflict. Pre-existing rows stay null; the
     * controller-side serializer normalizes null → false.
     */
    @Column
    private Boolean rotated;

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

    /**
     * ELLE kontrol kaydı mı ("Şimdi kontrol et", 2026-09-29) — NULL/false = zamanlanmış. Geçmişte ikisi de görünür;
     * zamanlanmış değişiklik tespitinin TABANI yalnız zamanlanmış kayıtlardan okunur
     * ({@code DnsRecordRepository.findLatestScheduledSuccessful}) — elle kontrol DNS_CHANGED algısını tüketmez.
     */
    @Column(name = "manual")
    private Boolean manual;

    // ── Hata teşhisi (2026-10-05): başarısız sorgu eskiden yalnız {@code value=''} olarak yazılıyor, çözümleyicinin
    // NXDOMAIN / SERVFAIL / yanıt yok / zaman aşımı bilgisi ATILIYORDU. Üçü de NULL'lanabilir ve YALNIZ başarısız
    // sorguda dolu; başarı ölçütü ({@code value <> ''}), değişiklik tespiti tabanı
    // ({@code DnsRecordRepository.findLatestScheduledSuccessful}) ve alarm kararı DEĞİŞMEDİ.

    /** Çözümleyicinin hata metni (ör. {@code NXDOMAIN}, {@code no answer}, istisna iletisi). */
    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /** Neden kodu ({@code CheckFailureReason}). */
    @Column(name = "failure_reason", length = 48)
    private String failureReason;

    /** Kompakt JSON ayrıntı (evre, rcode, kayıt türü, sorgu süresi / zaman aşımı …), ≤ 4000 karakter. */
    @Column(name = "failure_detail", columnDefinition = "TEXT")
    private String failureDetail;
}
