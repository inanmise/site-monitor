package com.certmonitor.repository;

import com.certmonitor.model.CertificateInventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CertificateInventoryRepository extends JpaRepository<CertificateInventory, Long> {
    List<CertificateInventory> findByActiveTrueOrderByDomainAsc();
    List<CertificateInventory> findByTeamIdAndActiveTrueOrderByDomainAsc(Long teamId);
    List<CertificateInventory> findByTeamIdOrderByDomainAsc(Long teamId);
    Optional<CertificateInventory> findByDomain(String domain);
    boolean existsByDomain(String domain);
    boolean existsByTeamIdAndActiveTrue(Long teamId);
    long countByActiveTrue();
}
