package com.certmonitor.repository;

import com.certmonitor.model.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByAlertEventIdOrderBySentAtDesc(Long alertEventId);

    @Query("SELECT COUNT(n) FROM NotificationLog n WHERE (n.emailStatus = 'SENT' OR n.emailStatus LIKE 'FAILED%') AND n.sentAt >= :cutoff")
    long countAttemptedSince(@Param("cutoff") String cutoff);

    @Query("SELECT COUNT(n) FROM NotificationLog n WHERE n.emailStatus = 'SENT' AND n.sentAt >= :cutoff")
    long countSentSince(@Param("cutoff") String cutoff);

    @Query("SELECT COUNT(n) FROM NotificationLog n WHERE n.sentAt >= :cutoff")
    long countAllSince(@Param("cutoff") String cutoff);

    @Query("SELECT n FROM NotificationLog n WHERE n.emailStatus <> 'SENT' AND n.sentAt >= :cutoff ORDER BY n.sentAt DESC")
    List<NotificationLog> findNonSentSince(@Param("cutoff") String cutoff);
}
