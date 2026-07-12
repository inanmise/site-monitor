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

    /** History detay listesi — SQL-LIMIT'li: tüm geçmişi JVM'e çekmeden en yeni :limit satır
     *  (eski: findByMonitorIdOrderByCheckedAtDesc(id).stream().limit(cap) → aylarca satır yüklüyordu). */
    @Query("SELECT r FROM KeywordResult r WHERE r.monitorId = :id ORDER BY r.checkedAt DESC LIMIT :limit")
    List<KeywordResult> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM KeywordResult r WHERE r.monitorId = :id AND r.checkedAt >= :since ORDER BY r.checkedAt DESC LIMIT :limit")
    List<KeywordResult> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

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

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI] — ids ∩ [from,to]; başarı = ok=true. */
    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.ok = true THEN 1L ELSE 0L END) "
         + "FROM KeywordResult r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);
}
