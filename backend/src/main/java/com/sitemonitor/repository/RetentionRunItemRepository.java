package com.sitemonitor.repository;

import com.sitemonitor.model.RetentionRunItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RetentionRunItemRepository extends JpaRepository<RetentionRunItem, Long> {

    List<RetentionRunItem> findByRunIdOrderByIdAsc(Long runId);

    List<RetentionRunItem> findByRunIdInOrderByIdAsc(List<Long> runIds);
}
