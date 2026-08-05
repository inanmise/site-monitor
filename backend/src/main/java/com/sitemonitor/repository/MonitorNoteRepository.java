package com.sitemonitor.repository;

import com.sitemonitor.model.MonitorNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MonitorNoteRepository extends JpaRepository<MonitorNote, Long> {
    List<MonitorNote> findByMonitorTypeAndTargetAndDeletedAtIsNullOrderByCreatedAtDesc(String monitorType, String target);
}
