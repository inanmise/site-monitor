package com.sitemonitor.repository;

import com.sitemonitor.model.PasswordHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {
    List<PasswordHistory> findByUserIdOrderByCreatedAtDesc(Long userId);
}
