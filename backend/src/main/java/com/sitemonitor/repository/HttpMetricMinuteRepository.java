package com.sitemonitor.repository;

import com.sitemonitor.model.HttpMetricMinute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HttpMetricMinuteRepository extends JpaRepository<HttpMetricMinute, Long> {

    /** Aralıktaki tüm endpoint satırları (UTC bucketMinute string >= / <=). */
    List<HttpMetricMinute> findByBucketMinuteBetween(String from, String to);

    /** Tek endpoint için aralık. */
    List<HttpMetricMinute> findByEndpointAndBucketMinuteBetween(String endpoint, String from, String to);

    /** Dropdown/özet için endpoint başına toplam: [endpoint, ΣreqCount, ΣerrorCount, ΣsumMs, MAXmaxMs]. */
    @Query("""
            SELECT m.endpoint, SUM(m.reqCount), SUM(m.errorCount), SUM(m.sumMs), MAX(m.maxMs)
              FROM HttpMetricMinute m
             WHERE m.bucketMinute >= :from AND m.bucketMinute <= :to
             GROUP BY m.endpoint
            """)
    List<Object[]> aggregateByEndpoint(@Param("from") String from, @Param("to") String to);
}
