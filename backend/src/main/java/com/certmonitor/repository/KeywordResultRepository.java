package com.certmonitor.repository;

import com.certmonitor.model.KeywordResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
