package com.certmonitor.repository;

import com.certmonitor.model.NotificationLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByAlertEventIdOrderBySentAtDesc(Long alertEventId);

    /** Trigger'a göre arşiv (en yeni üstte) — haftalık erişilebilirlik giden mail geçmişi için. */
    List<NotificationLog> findByTriggerInOrderBySentAtDesc(Collection<String> triggers, Pageable pageable);

    /**
     * Bulk count: returns [alertEventId, sentCount, failedCount] rows.
     * "SENT" is a single canonical string; "FAILED..." may carry a reason suffix.
     */
    @Query("""
       SELECT n.alertEventId,
              SUM(CASE WHEN n.emailStatus = 'SENT' THEN 1 ELSE 0 END),
              SUM(CASE WHEN n.emailStatus LIKE 'FAILED%' THEN 1 ELSE 0 END)
       FROM NotificationLog n
       WHERE n.alertEventId IN :ids
       GROUP BY n.alertEventId
    """)
    List<Object[]> countByAlertIds(@Param("ids") Collection<Long> ids);

    @Query("SELECT COUNT(n) FROM NotificationLog n WHERE (n.emailStatus = 'SENT' OR n.emailStatus LIKE 'FAILED%') AND n.sentAt >= :cutoff")
    long countAttemptedSince(@Param("cutoff") String cutoff);

    @Query("SELECT COUNT(n) FROM NotificationLog n WHERE n.emailStatus = 'SENT' AND n.sentAt >= :cutoff")
    long countSentSince(@Param("cutoff") String cutoff);

    @Query("SELECT COUNT(n) FROM NotificationLog n WHERE n.sentAt >= :cutoff")
    long countAllSince(@Param("cutoff") String cutoff);

    @Query("SELECT n FROM NotificationLog n WHERE n.emailStatus LIKE 'FAILED%' AND n.sentAt >= :cutoff ORDER BY n.sentAt DESC")
    List<NotificationLog> findFailedSince(@Param("cutoff") String cutoff);

    @Query("SELECT n FROM NotificationLog n WHERE n.emailStatus <> 'SENT' AND n.sentAt >= :cutoff ORDER BY n.sentAt DESC")
    List<NotificationLog> findNonSentSince(@Param("cutoff") String cutoff);

    @Query("SELECT n FROM NotificationLog n WHERE n.sentAt >= :cutoff ORDER BY n.sentAt DESC")
    List<NotificationLog> findAllSince(@Param("cutoff") String cutoff);
}
