package com.certmonitor.repository;

import com.certmonitor.model.PasswordHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {
    List<PasswordHistory> findByUserIdOrderByCreatedAtDesc(Long userId);
}
