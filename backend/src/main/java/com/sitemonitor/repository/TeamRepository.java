package com.sitemonitor.repository;

import com.sitemonitor.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findByActiveTrueOrderByNameAsc();
    Optional<Team> findByName(String name);
    boolean existsByName(String name);
    boolean existsByNameIgnoreCase(String name);
    List<Team> findByLeaderId(Long leaderId);   // Faz 3b: PO'nun liderlik ettiği takımlar
    /** Elle müdür atanmış takımlar — kullanıcı silinince {@code manager_id} temizlenir (2026-09-29, A1-D3). */
    List<Team> findByManagerId(Long managerId);

    /** Sessiz saat tanımlı takımlar (2026-10-01) — TeamQuietHoursService önbelleğinin TEK sorgusu (dakikada en çok bir). */
    @org.springframework.data.jpa.repository.Query(
            "SELECT t FROM Team t WHERE t.quietStart IS NOT NULL AND t.quietEnd IS NOT NULL")
    List<Team> findQuietConfigured();
}
