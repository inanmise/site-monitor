package com.certmonitor.repository;

import com.certmonitor.model.DnsAuthoritySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DnsAuthoritySnapshotRepository extends JpaRepository<DnsAuthoritySnapshot, Long> {
    /** Bir domain için en güncel otorite anlık görüntüsü (karşılaştırma referansı). */
    Optional<DnsAuthoritySnapshot> findTopByDomainOrderByIdDesc(String domain);
}
