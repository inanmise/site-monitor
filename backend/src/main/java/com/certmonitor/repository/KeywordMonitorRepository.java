package com.certmonitor.repository;

import com.certmonitor.model.KeywordMonitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KeywordMonitorRepository extends JpaRepository<KeywordMonitor, Long> {
    List<KeywordMonitor> findByActiveTrue();
    List<KeywordMonitor> findAllByOrderByNameAsc();
    Optional<KeywordMonitor> findFirstByUrlAndKeywordOrderByIdAsc(String url, String keyword);
}
