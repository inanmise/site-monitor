package com.certmonitor.repository;

import com.certmonitor.model.PortCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PortCheckRepository extends JpaRepository<PortCheck, Long> {
    List<PortCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<PortCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(Long monitorId, String since);

    /** Gün-aralığı filtresi (detay ekranı) — en yeni önce + özet sayıları. */
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOpenFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);
}
