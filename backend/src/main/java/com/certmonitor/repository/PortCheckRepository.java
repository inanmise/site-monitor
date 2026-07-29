package com.certmonitor.repository;

import com.certmonitor.model.PortCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PortCheckRepository extends JpaRepository<PortCheck, Long> {
    List<PortCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<PortCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** History detay listesi — SQL-LIMIT'li: tüm geçmişi JVM'e çekmeden en yeni :limit satır. */
    @Query("SELECT pc FROM PortCheck pc WHERE pc.monitorId = :id ORDER BY pc.checkedAt DESC LIMIT :limit")
    List<PortCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT pc FROM PortCheck pc WHERE pc.monitorId = :id AND pc.checkedAt >= :since ORDER BY pc.checkedAt DESC LIMIT :limit")
    List<PortCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol. Eski "id IN (SELECT MAX(id) GROUP BY monitor_id)" ve
     *  DISTINCT ON TÜM tabloyu tarıyordu (898k satırda ~3.5 sn). LATERAL join küçük monitör tablosunu
     *  gezip her monitör için (monitor_id, checked_at) index'iyle tek backward-seek yapar → ~0.2 ms. */
    @Query(value = "SELECT c.* FROM port_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM port_checks pc WHERE pc.monitor_id = m.id ORDER BY pc.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<PortCheck> findLatestPerMonitor();
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(Long monitorId, String since);

    /** Gün-aralığı filtresi (detay ekranı) — en yeni önce + özet sayıları. */
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOpenFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Yanıt-süresi grafiği için ham veri: [checked_at, response_ms (null olabilir), open] — aralık + cap
     *  (en yeni :limit). Null filtresi YOK: down/error kovaları için tüm kayıtlar gelir, istatistik
     *  Java'da null'sız hesaplanır (keyword/ping responseSeriesRaw ile aynı desen). */
    @Query("SELECT pc.checkedAt, pc.responseMs, pc.open FROM PortCheck pc "
         + "WHERE pc.monitorId = :id AND pc.checkedAt >= :from AND pc.checkedAt <= :to "
         + "ORDER BY pc.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI] — ids ∩ [from,to]; başarı = open=true. */
    @Query("SELECT pc.monitorId, COUNT(pc), SUM(CASE WHEN pc.open = true THEN 1L ELSE 0L END) "
         + "FROM PortCheck pc WHERE pc.monitorId IN :ids AND pc.checkedAt >= :from AND pc.checkedAt <= :to "
         + "GROUP BY pc.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);
}
