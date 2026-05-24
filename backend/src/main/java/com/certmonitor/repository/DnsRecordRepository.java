package com.certmonitor.repository;

import com.certmonitor.model.DnsRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DnsRecordRepository extends JpaRepository<DnsRecord, Long> {
    List<DnsRecord> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<DnsRecord> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    List<DnsRecord> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(Long monitorId, String since);
}
