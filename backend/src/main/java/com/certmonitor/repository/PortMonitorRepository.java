package com.certmonitor.repository;

import com.certmonitor.model.PortMonitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PortMonitorRepository extends JpaRepository<PortMonitor, Long> {
    List<PortMonitor> findByActiveTrue();
    List<PortMonitor> findByStandaloneTrueAndActiveTrue();
    /** Storm denominatörü — cert-türevi (envanter) satırları çift saymamak için yalnız standalone aktifler. */
    long countByStandaloneTrueAndActiveTrue();
    List<PortMonitor> findAllByOrderByNameAsc();
    Optional<PortMonitor> findFirstByHostAndPortOrderByIdAsc(String host, int port);
}
