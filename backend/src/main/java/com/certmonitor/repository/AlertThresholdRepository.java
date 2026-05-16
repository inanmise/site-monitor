package com.certmonitor.repository;

import com.certmonitor.model.AlertThreshold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlertThresholdRepository extends JpaRepository<AlertThreshold, Long> {
    Optional<AlertThreshold> findFirstByActiveTrue();
}
