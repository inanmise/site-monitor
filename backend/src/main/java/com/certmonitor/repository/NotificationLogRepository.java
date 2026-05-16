package com.certmonitor.repository;

import com.certmonitor.model.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByAlertEventIdOrderBySentAtDesc(Long alertEventId);
}
