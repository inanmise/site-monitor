package com.certmonitor.repository;

import com.certmonitor.model.DnsMonitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DnsMonitorRepository extends JpaRepository<DnsMonitor, Long> {
    List<DnsMonitor> findByActiveTrue();
    List<DnsMonitor> findAllByOrderByNameAsc();
    Optional<DnsMonitor> findFirstByDomainOrderByIdAsc(String domain);
}
