package com.sitemonitor.repository;

import com.sitemonitor.model.PageCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageCheckRepository extends JpaRepository<PageCheck, Long> {
    List<PageCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<PageCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** History detay listesi — SQL-LIMIT'li: tüm geçmişi JVM'e çekmeden en yeni :limit satır. */
    @Query("SELECT r FROM PageCheck r WHERE r.monitorId = :id ORDER BY r.checkedAt DESC LIMIT :limit")
    List<PageCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM PageCheck r WHERE r.monitorId = :id AND r.checkedAt >= :since ORDER BY r.checkedAt DESC LIMIT :limit")
    List<PageCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol — tek toplu sorgu (N+1 önleme). */
    // LATERAL join: monitör başına tek index-seek (full-scan yerine).
    @Query(value = "SELECT c.* FROM page_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM page_checks r WHERE r.monitor_id = m.id ORDER BY r.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<PageCheck> findLatestPerMonitor();

    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Kırık-kaynak zaman serisi için ham veri: [checked_at, broken_resources, ok] — aralık + cap. */
    @Query("SELECT r.checkedAt, r.brokenResources, r.ok FROM PageCheck r "
         + "WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "ORDER BY r.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI, ort_yanıt_ms] — ids ∩ [from,to]; başarı = ok=true. */
    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.ok = true THEN 1L ELSE 0L END), AVG(r.responseMs) "
         + "FROM PageCheck r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);
}
