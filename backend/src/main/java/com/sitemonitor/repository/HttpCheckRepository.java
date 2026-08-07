package com.sitemonitor.repository;

import com.sitemonitor.model.HttpCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HttpCheckRepository extends JpaRepository<HttpCheck, Long> {

    // ── Kontrol Geçmişi v2: server-side sayfalı aralık + hata filtresi + yoğunluk histogramı ──
    Page<HttpCheck> findByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p);
    Page<HttpCheck> findByMonitorIdAndOkFalseAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p);
    long countByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to);
    long countByMonitorIdAndOkFalseAndCheckedAtBetween(Long monitorId, String from, String to);

    /** Yoğunluk şeridi: [bucketKey, toplam, hata] — SUBSTRING prefix kovası (dakika 16 / saat 13 / gün 10). */
    @Query("SELECT SUBSTRING(c.checkedAt,1,:len), COUNT(c), SUM(CASE WHEN c.ok = false THEN 1L ELSE 0L END) "
         + "FROM HttpCheck c WHERE c.monitorId = :id AND c.checkedAt >= :from AND c.checkedAt <= :to "
         + "GROUP BY SUBSTRING(c.checkedAt,1,:len) ORDER BY SUBSTRING(c.checkedAt,1,:len)")
    List<Object[]> historyHistogram(@Param("id") Long id, @Param("from") String from,
                                    @Param("to") String to, @Param("len") int len);
    List<HttpCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<HttpCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** History detay listesi — SQL-LIMIT'li: tüm geçmişi JVM'e çekmeden en yeni :limit satır. */
    @Query("SELECT r FROM HttpCheck r WHERE r.monitorId = :id ORDER BY r.checkedAt DESC LIMIT :limit")
    List<HttpCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM HttpCheck r WHERE r.monitorId = :id AND r.checkedAt >= :since ORDER BY r.checkedAt DESC LIMIT :limit")
    List<HttpCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol — tek toplu sorgu (N+1 önleme). */
    // LATERAL join: monitör başına tek index-seek (full-scan yerine).
    @Query(value = "SELECT c.* FROM http_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM http_checks r WHERE r.monitor_id = m.id ORDER BY r.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<HttpCheck> findLatestPerMonitor();

    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Yanıt-süresi grafiği için ham veri: [checked_at, response_ms (null olabilir), ok] — aralık + cap. */
    @Query("SELECT r.checkedAt, r.responseMs, r.ok FROM HttpCheck r "
         + "WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "ORDER BY r.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI, ort_yanıt_ms] — ids ∩ [from,to]; başarı = ok=true. */
    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.ok = true THEN 1L ELSE 0L END), AVG(r.responseMs) "
         + "FROM HttpCheck r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);
}
