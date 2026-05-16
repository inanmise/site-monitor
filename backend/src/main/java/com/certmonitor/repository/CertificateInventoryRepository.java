package com.certmonitor.repository;

import com.certmonitor.model.CertificateInventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CertificateInventoryRepository extends JpaRepository<CertificateInventory, Long> {
    List<CertificateInventory> findByActiveTrueOrderByDomainAsc();
    Optional<CertificateInventory> findByDomain(String domain);
    boolean existsByDomain(String domain);
}
