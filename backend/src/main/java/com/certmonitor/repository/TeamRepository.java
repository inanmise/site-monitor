package com.certmonitor.repository;

import com.certmonitor.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findByActiveTrueOrderByNameAsc();
    Optional<Team> findByName(String name);
    boolean existsByName(String name);
}
