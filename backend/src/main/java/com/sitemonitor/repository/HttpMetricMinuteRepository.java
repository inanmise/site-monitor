package com.sitemonitor.repository;

import com.sitemonitor.model.HttpMetricMinute;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.stream.Stream;

/**
 * Dakika × uç HTTP metrik satırları. 2026-09-28: aralığı ENTITY LİSTESİ olarak yükleyen türetilmiş sorgular
 * ({@code findByBucketMinuteBetween}…) kaldırıldı — 7 günlük "tümü" sorgusu ~400 bin entity'yi (kalıcılık bağlamı
 * anlık görüntüsüyle) yığına alıyordu. Okumalar artık {@link #streamRange} akışı ya da GROUP BY toplamıdır.
 */
public interface HttpMetricMinuteRepository extends JpaRepository<HttpMetricMinute, Long> {

    /**
     * Dropdown/özet için endpoint başına toplam:
     * [endpoint, ΣreqCount, ΣerrorCount, ΣsumMs, MAXmaxMs, MAXbucketMinute (son görülme, UTC)].
     */
    @Query("""
            SELECT m.endpoint, SUM(m.reqCount), SUM(m.errorCount), SUM(m.sumMs), MAX(m.maxMs), MAX(m.bucketMinute)
              FROM HttpMetricMinute m
             WHERE m.bucketMinute >= :from AND m.bucketMinute <= :to
             GROUP BY m.endpoint
            """)
    List<Object[]> aggregateByEndpoint(@Param("from") String from, @Param("to") String to);

    /**
     * Aralıktaki satırlar AKIŞ olarak, entity değil yalnız gereken kolonlar (2026-09-28, İstek Gezgini):
     * [bucketMinute, endpoint, reqCount, errorCount, sumMs, maxMs, minMs, hist, statusCodes].
     *
     * <p>Neden akış: 7 günlük aralık dakika × endpoint ≈ 400 bin satır. Liste olarak (üstelik entity + kalıcılık
     * bağlamı anlık görüntüsüyle) yüklemek tek pod'un 1 GB'lık yığınında onlarca MB geçici bellek demekti; akış
     * satırları {@code fetchSize}'lık dilimlerle çeker, toplayıcı sabit bellekte kalır. ÇAĞIRAN bir okuma
     * transaction'ı içinde tüketmeli ve akışı kapatmalı (try-with-resources) — {@code HttpMetricsQueryService}.
     */
    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "1000"))
    @Query("""
            SELECT m.bucketMinute, m.endpoint, m.reqCount, m.errorCount, m.sumMs, m.maxMs, m.minMs, m.hist, m.statusCodes
              FROM HttpMetricMinute m
             WHERE m.bucketMinute >= :from AND m.bucketMinute <= :to
            """)
    Stream<Object[]> streamRange(@Param("from") String from, @Param("to") String to);

    /** {@link #streamRange} tek endpoint için ({@code idx_hmm_ep_bucket} dizini). */
    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "1000"))
    @Query("""
            SELECT m.bucketMinute, m.endpoint, m.reqCount, m.errorCount, m.sumMs, m.maxMs, m.minMs, m.hist, m.statusCodes
              FROM HttpMetricMinute m
             WHERE m.endpoint = :endpoint AND m.bucketMinute >= :from AND m.bucketMinute <= :to
            """)
    Stream<Object[]> streamRangeForEndpoint(@Param("endpoint") String endpoint,
                                            @Param("from") String from, @Param("to") String to);
}
