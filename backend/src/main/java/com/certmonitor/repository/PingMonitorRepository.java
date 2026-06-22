package com.certmonitor.repository;

import com.certmonitor.model.PingMonitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PingMonitorRepository extends JpaRepository<PingMonitor, Long> {
    List<PingMonitor> findByActiveTrue();
    List<PingMonitor> findAllByOrderByNameAsc();
    Optional<PingMonitor> findFirstByHostOrderByIdAsc(String host);
}
