package com.certmonitor.repository;

import com.certmonitor.model.DnsMonitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DnsMonitorRepository extends JpaRepository<DnsMonitor, Long> {
    List<DnsMonitor> findByActiveTrue();
    List<DnsMonitor> findAllByOrderByNameAsc();
    /** Storm denominatörü — cert-türevi (envanter) satırları çift saymamak için yalnız standalone aktifler. */
    long countByStandaloneTrueAndActiveTrue();
    Optional<DnsMonitor> findFirstByDomainOrderByIdAsc(String domain);
    List<DnsMonitor> findByStandaloneTrueAndActiveTrue();
    Optional<DnsMonitor> findFirstByDomainAndRecordTypeAndStandaloneTrue(String domain, String recordType);
}
