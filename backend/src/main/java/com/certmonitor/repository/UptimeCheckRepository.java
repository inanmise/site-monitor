package com.certmonitor.repository;

import com.certmonitor.model.UptimeCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UptimeCheckRepository extends JpaRepository<UptimeCheck, Long> {
    Optional<UptimeCheck> findTopByDomainAndPortOrderByIdDesc(String domain, int port);
}
