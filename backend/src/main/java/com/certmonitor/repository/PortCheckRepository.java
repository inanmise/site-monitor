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

    /** Her monitör için en güncel kontrol — port listesinde monitör başına sorgu yerine tek toplu sorgu. */
    @Query("SELECT pc FROM PortCheck pc WHERE pc.id IN "
         + "(SELECT MAX(pc2.id) FROM PortCheck pc2 GROUP BY pc2.monitorId)")
    List<PortCheck> findLatestPerMonitor();
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(Long monitorId, String since);

    /** Gün-aralığı filtresi (detay ekranı) — en yeni önce + özet sayıları. */
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOpenFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);
}
