package com.certmonitor.repository;

import com.certmonitor.model.SystemHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SystemHeartbeatRepository extends JpaRepository<SystemHeartbeat, Long> {
    Optional<SystemHeartbeat> findTopByOrderByRecordedAtDesc();
    List<SystemHeartbeat> findTop5ByOrderByRecordedAtDesc();
}
