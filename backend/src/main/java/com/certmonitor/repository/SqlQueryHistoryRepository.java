package com.certmonitor.repository;

import com.certmonitor.model.SqlQueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SqlQueryHistoryRepository extends JpaRepository<SqlQueryHistory, Long> {

    List<SqlQueryHistory> findTop50ByOrderByExecutedAtDesc();

    List<SqlQueryHistory> findTop50ByExecutedByOrderByExecutedAtDesc(String executedBy);
}
