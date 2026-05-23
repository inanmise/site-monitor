package com.certmonitor.repository;

import com.certmonitor.model.PingCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PingCheckRepository extends JpaRepository<PingCheck, Long> {
    List<PingCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<PingCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    List<PingCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(Long monitorId, String since);
}
