package com.certmonitor.repository;

import com.certmonitor.model.PortCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PortCheckRepository extends JpaRepository<PortCheck, Long> {
    List<PortCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<PortCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** Her monitör için en güncel kontrol — port listesinde monitör başına sorgu yerine tek toplu sorgu. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT pc FROM PortCheck pc WHERE pc.id IN "
      + "(SELECT MAX(pc2.id) FROM PortCheck pc2 GROUP BY pc2.monitorId)")
    List<PortCheck> findLatestPerMonitor();
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(Long monitorId, String since);

    /** Gün-aralığı filtresi (detay ekranı) — en yeni önce + özet sayıları. */
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOpenFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);
}
