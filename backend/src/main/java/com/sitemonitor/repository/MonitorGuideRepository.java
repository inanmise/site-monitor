package com.sitemonitor.repository;

import com.sitemonitor.model.MonitorGuide;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MonitorGuideRepository extends JpaRepository<MonitorGuide, Long> {
    Optional<MonitorGuide> findByMonitorTypeAndTarget(String monitorType, String target);
}
