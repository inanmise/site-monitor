package com.sitemonitor.repository;

import com.sitemonitor.model.SqlQueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SqlQueryHistoryRepository extends JpaRepository<SqlQueryHistory, Long> {

    List<SqlQueryHistory> findTop50ByOrderByExecutedAtDesc();

    List<SqlQueryHistory> findTop50ByExecutedByOrderByExecutedAtDesc(String executedBy);

    /** DB analitiği — pencere içindeki sorgu geçmişi (Java'da kullanıcı/SQL/zaman kümelemesi için). */
    List<SqlQueryHistory> findByExecutedAtGreaterThanEqualOrderByExecutedAtAsc(String since);
}
