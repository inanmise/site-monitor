package com.certmonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dakika × endpoint HTTP metrik agregatı — kalıcı zaman serisi (Sistem Sağlığı HTTP paneli).
 * Her satır bir dakikalık pencerede tek bir endpoint'in (method + route şablonu) toplamıdır.
 * Histogram (kova sayıları) sayesinde p50/p95/p99 herhangi bir aralık/granularite için birleştirilebilir.
 * HA: her pod kendi sayımını yazar → aynı (bucketMinute, endpoint) için birden çok satır olabilir;
 * sorguda toplanır (additive), bu yüzden unique-constraint YOKTUR.
 */
@Entity
@Table(name = "http_metric_minute", indexes = {
        @Index(name = "idx_hmm_bucket",    columnList = "bucket_minute"),
        @Index(name = "idx_hmm_ep_bucket", columnList = "endpoint,bucket_minute")
})
@Data
@NoArgsConstructor
public class HttpMetricMinute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Dakika kovası — ISO-8601 UTC "yyyy-MM-dd'T'HH:mm:ss" (sıralanabilir string). */
    @Column(name = "bucket_minute", nullable = false, length = 25)
    private String bucketMinute;

    /** method + route şablonu, ör. "GET /api/incidents/{id}". */
    @Column(nullable = false, length = 200)
    private String endpoint;

    @Column(name = "req_count")  private long reqCount;
    @Column(name = "error_count") private long errorCount;
    @Column(name = "sum_ms")     private long sumMs;
    @Column(name = "max_ms")     private long maxMs;
    @Column(name = "min_ms")     private long minMs;

    /** Gecikme histogramı — HISTOGRAM_BOUNDS + overflow kova sayıları, virgülle ayrılmış. */
    @Column(columnDefinition = "TEXT")
    private String hist;
}
