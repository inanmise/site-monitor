package com.certmonitor.repository;

import com.certmonitor.model.SystemHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SystemHeartbeatRepository extends JpaRepository<SystemHeartbeat, Long> {
    Optional<SystemHeartbeat> findTopByOrderByRecordedAtDesc();
}
