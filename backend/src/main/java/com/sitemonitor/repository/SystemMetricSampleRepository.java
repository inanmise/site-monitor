package com.sitemonitor.repository;

import com.sitemonitor.model.SystemMetricSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * SiteMonitor'ün kendi dakikalık metrik örnekleri (Sistem Sağlığı → Metrikler). Okumalar ENTITY listesi yüklemez:
 * kovalar SQL'de toplanır ({@code sampled_at} metninin önek uzunluğu kovayı seçer — 16 dakika, 15 on dakika, 13 saat).
 * Kova türetilmiş tabloda bir kez hesaplanır, dış sorgu onun SÜTUNUNA göre gruplar: aynı parametre GROUP BY'da ikinci
 * kez bağlanınca PostgreSQL ifadeleri farklı sayar, H2 ise {@code GROUP BY 1, 2} sıra numarasını kabul etmez — türetilmiş
 * sütun ikisinde de çalışır (H2 testi + {@code SystemMetricSamplePostgresTest}).
 */
public interface SystemMetricSampleRepository extends JpaRepository<SystemMetricSample, Long> {

    /**
     * Pod × kova toplamı. Sütun sırası {@code SystemMetricsQueryService.point} ile aynı:
     * [instance_id, bucket, samples, avg/max cpu_process, avg cpu_system, avg/max heap_used, max heap_max, avg non_heap,
     *  avg/max threads, sum gc_pause, sum gc_count, avg/max db_active, avg db_idle, max db_pending, max db_max,
     *  avg/max exec_active, max exec_max, avg/max exec_queue, max exec_queue_cap, sum exec_completed, sum caller_runs,
     *  sum http_requests, sum http_errors, sum http_sum_ms, max http_p95, max http_max, sum log_warn, sum log_error,
     *  sum cache_hits, sum cache_misses, max uptime, sum http_minutes, sum delta_minutes (yeni sütunlar hep SONA eklenir)].
     */
    @Query(value = """
            SELECT x.instance_id, x.bucket, COUNT(*),
                   AVG(x.cpu_process), MAX(x.cpu_process), AVG(x.cpu_system),
                   AVG(x.heap_used_mb), MAX(x.heap_used_mb), MAX(x.heap_max_mb), AVG(x.non_heap_mb), AVG(x.threads), MAX(x.threads),
                   SUM(x.gc_pause_ms_delta), SUM(x.gc_count_delta),
                   AVG(x.db_active), MAX(x.db_active), AVG(x.db_idle), MAX(x.db_pending), MAX(x.db_max),
                   AVG(x.exec_active), MAX(x.exec_active), MAX(x.exec_max), AVG(x.exec_queue), MAX(x.exec_queue), MAX(x.exec_queue_cap),
                   SUM(x.exec_completed_delta), SUM(x.exec_caller_runs_delta),
                   SUM(x.http_requests), SUM(x.http_errors), SUM(x.http_sum_ms), MAX(x.http_p95_ms), MAX(x.http_max_ms),
                   SUM(x.log_warn_delta), SUM(x.log_error_delta), SUM(x.cache_hits_delta), SUM(x.cache_misses_delta), MAX(x.uptime_s),
                   SUM(x.http_minutes), SUM(x.delta_minutes)
              FROM (SELECT s.*, SUBSTRING(s.sampled_at, 1, :len) AS bucket
                      FROM system_metric_samples s
                     WHERE s.sampled_at >= :from AND s.sampled_at <= :to
                       AND s.shutdown_marker IS NULL
                       AND (CAST(:instance AS VARCHAR(80)) IS NULL OR s.instance_id = :instance)) x
             GROUP BY x.instance_id, x.bucket
             ORDER BY x.bucket, x.instance_id
            """, nativeQuery = true)
    List<Object[]> aggregate(@Param("from") String from, @Param("to") String to, @Param("instance") String instance,
                             @Param("len") int len);

    /** Bu andan sonra örnek yazmış podlar: [instance_id, pod_name, son örnek, kapanış işareti var mı (1/0)]. */
    @Query("""
            SELECT s.instanceId, MAX(s.podName), MAX(s.sampledAt),
                   MAX(CASE WHEN s.shutdownMarker = true THEN 1 ELSE 0 END)
              FROM SystemMetricSample s
             WHERE s.sampledAt >= :since
             GROUP BY s.instanceId
            """)
    List<Object[]> podsSince(@Param("since") String since);

    /**
     * Verilen andan sonraki ÖLÇÜM örnekleri (son dakikaların anlık değerleri — küçük küme: pod sayısı × birkaç dakika).
     * Kapanış işareti satırı hariç: canlı sayılan bir podun "son örneği" boş işaret satırı olmasın.
     */
    List<SystemMetricSample> findBySampledAtGreaterThanEqualAndShutdownMarkerIsNullOrderBySampledAtDesc(String since);
}
