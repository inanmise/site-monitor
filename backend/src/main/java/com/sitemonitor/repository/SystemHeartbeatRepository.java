package com.sitemonitor.repository;

import com.sitemonitor.model.SystemHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SystemHeartbeatRepository extends JpaRepository<SystemHeartbeat, Long> {
    Optional<SystemHeartbeat> findTopByOrderByRecordedAtDesc();
    List<SystemHeartbeat> findTop5ByOrderByRecordedAtDesc();
    List<SystemHeartbeat> findByRecordedAtAfterOrderByRecordedAtAsc(LocalDateTime cutoff);
}
