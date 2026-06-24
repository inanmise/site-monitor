package com.certmonitor.repository;

import com.certmonitor.model.KeywordResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KeywordResultRepository extends JpaRepository<KeywordResult, Long> {
    List<KeywordResult> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<KeywordResult> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** Her monitör için en güncel kontrol — tek toplu sorgu (N+1 önleme). */
    @Query("SELECT r FROM KeywordResult r WHERE r.id IN "
         + "(SELECT MAX(r2.id) FROM KeywordResult r2 GROUP BY r2.monitorId)")
    List<KeywordResult> findLatestPerMonitor();

    List<KeywordResult> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Yanıt-süresi grafiği için ham veri: [checked_at, response_ms (null olabilir), ok] — aralık + cap
     *  (en yeni :limit). Null filtresi YOK: down/error kovaları için tüm kayıtlar gelir, istatistik
     *  Java'da null'sız hesaplanır. idx_kwr_monitor_checked üzerinden. */
    @Query("SELECT r.checkedAt, r.responseMs, r.ok FROM KeywordResult r "
         + "WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "ORDER BY r.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);
}
