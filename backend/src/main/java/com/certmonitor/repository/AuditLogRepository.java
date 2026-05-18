package com.certmonitor.repository;

import com.certmonitor.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT COUNT(a) > 0 FROM AuditLog a WHERE a.actor = :actor AND a.ipAddress = :ip AND a.outcome = 'SUCCESS' AND a.eventType = 'LOGIN'")
    boolean existsSuccessfulLoginFromIp(@Param("actor") String actor, @Param("ip") String ip);

    @Query("SELECT a FROM AuditLog a WHERE a.actor = :actor AND a.eventType = 'LOGIN' AND a.outcome = 'SUCCESS' AND a.eventTime > :since ORDER BY a.eventTime DESC")
    List<AuditLog> findRecentSuccessfulLogins(@Param("actor") String actor, @Param("since") String since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.actor = :actor AND a.eventType = 'LOGIN_FAILED' AND a.eventTime > :since")
    long countRecentFailedLogins(@Param("actor") String actor, @Param("since") String since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.eventTime > :since")
    long countEventsSince(@Param("since") String since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.anomalyFlags IS NOT NULL AND a.anomalyFlags <> '' AND a.eventTime > :since")
    long countAnomaliesSince(@Param("since") String since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.eventType = 'LOGIN_FAILED' AND a.eventTime > :since")
    long countFailedLoginsSince(@Param("since") String since);

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:actor IS NULL OR LOWER(a.actor) LIKE LOWER(CONCAT('%', :actor, '%'))) AND " +
           "(:eventType IS NULL OR a.eventType = :eventType) AND " +
           "(:outcome IS NULL OR a.outcome = :outcome) AND " +
           "(:since IS NULL OR a.eventTime >= :since) AND " +
           "(:until IS NULL OR a.eventTime <= :until) AND " +
           "(:anomalyOnly = false OR (a.anomalyFlags IS NOT NULL AND a.anomalyFlags <> '')) " +
           "ORDER BY a.eventTime DESC")
    Page<AuditLog> findFiltered(
        @Param("actor") String actor,
        @Param("eventType") String eventType,
        @Param("outcome") String outcome,
        @Param("since") String since,
        @Param("until") String until,
        @Param("anomalyOnly") boolean anomalyOnly,
        Pageable pageable);
}
