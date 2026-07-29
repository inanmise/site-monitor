package com.certmonitor.repository;

import com.certmonitor.model.PingCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PingCheckRepository extends JpaRepository<PingCheck, Long> {
    List<PingCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<PingCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** History detay listesi — SQL-LIMIT'li: tüm geçmişi JVM'e çekmeden en yeni :limit satır. */
    @Query("SELECT c FROM PingCheck c WHERE c.monitorId = :id ORDER BY c.checkedAt DESC LIMIT :limit")
    List<PingCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT c FROM PingCheck c WHERE c.monitorId = :id AND c.checkedAt >= :since ORDER BY c.checkedAt DESC LIMIT :limit")
    List<PingCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol — tek toplu sorgu (N+1 önleme). */
    // LATERAL join: küçük monitör tablosu × monitör başına tek index-seek (eski MAX(id)+GROUP BY /
    // DISTINCT ON full-scan yerine — milyonlarca satırda ~3.5sn → ~0.2ms).
    @Query(value = "SELECT c.* FROM ping_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM ping_checks pc WHERE pc.monitor_id = m.id ORDER BY pc.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<PingCheck> findLatestPerMonitor();

    List<PingCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndUpFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** RTT grafiği için ham veri: [checked_at, rtt_ms (null olabilir), up, packet_loss] — aralık + cap
     *  (en yeni :limit). Null filtresi YOK: down kovaları için tüm kayıtlar gelir. */
    @Query("SELECT c.checkedAt, c.rttMs, c.up, c.packetLoss FROM PingCheck c "
         + "WHERE c.monitorId = :id AND c.checkedAt >= :from AND c.checkedAt <= :to "
         + "ORDER BY c.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI, ort_rtt_ms] — ids ∩ [from,to]; başarı = up=true. */
    @Query("SELECT c.monitorId, COUNT(c), SUM(CASE WHEN c.up = true THEN 1L ELSE 0L END), AVG(c.rttMs) "
         + "FROM PingCheck c WHERE c.monitorId IN :ids AND c.checkedAt >= :from AND c.checkedAt <= :to "
         + "GROUP BY c.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);
}
