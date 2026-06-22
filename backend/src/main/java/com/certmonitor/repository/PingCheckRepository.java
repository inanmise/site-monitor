package com.certmonitor.repository;

import com.certmonitor.model.PingCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PingCheckRepository extends JpaRepository<PingCheck, Long> {
    List<PingCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<PingCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** Her monitör için en güncel kontrol — tek toplu sorgu (N+1 önleme). */
    @Query("SELECT pc FROM PingCheck pc WHERE pc.id IN "
         + "(SELECT MAX(pc2.id) FROM PingCheck pc2 GROUP BY pc2.monitorId)")
    List<PingCheck> findLatestPerMonitor();

    List<PingCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndUpFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);
}
