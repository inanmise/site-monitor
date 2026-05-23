package com.certmonitor.repository;

import com.certmonitor.model.PortMonitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PortMonitorRepository extends JpaRepository<PortMonitor, Long> {
    List<PortMonitor> findByActiveTrue();
    List<PortMonitor> findAllByOrderByNameAsc();
}
