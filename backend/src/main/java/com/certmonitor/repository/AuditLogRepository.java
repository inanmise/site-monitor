package com.certmonitor.repository;

import com.certmonitor.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    // ── Kullanıcı/oturum izleme (UserActivityService) — okuma, şema değişikliği yok ──
    /** Verilen tipteki login olaylarını pencere içinde çeker; seri/top/anomali/heatmap Java'da kümelenir. */
    @Query("SELECT a FROM AuditLog a WHERE a.eventType IN :types AND a.eventTime >= :since ORDER BY a.eventTime ASC")
    List<AuditLog> findLoginEventsSince(@Param("types") List<String> types, @Param("since") String since);

    /** Esnek aralık (from–to) içindeki login olayları — grafik aralık seçimi/zoom/gün-navigasyonu için. */
    @Query("SELECT a FROM AuditLog a WHERE a.eventType IN :types AND a.eventTime >= :from AND a.eventTime <= :to ORDER BY a.eventTime ASC")
    List<AuditLog> findLoginEventsBetween(@Param("types") List<String> types,
                                          @Param("from") String from, @Param("to") String to);

    /** Bir oturuma ait en güncel audit satırı — aktif kullanıcının login zamanı + IP/konum/tarayıcısı. */
    Optional<AuditLog> findTopByActorAndSessionIdOrderByEventTimeDesc(String actor, String sessionId);

    /** Fallback: oturum eşleşmezse kullanıcının en güncel başarılı LOGIN'i. */
    Optional<AuditLog> findTopByActorAndEventTypeAndOutcomeOrderByEventTimeDesc(
            String actor, String eventType, String outcome);

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:actor IS NULL OR LOWER(a.actor) LIKE :actor) AND " +
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

    /**
     * Same shape as findFiltered, but actor is an exact (case-insensitive)
     * match so a user cannot incidentally see another user whose name is a
     * substring of theirs. Powers /api/me/audit.
     */
    @Query("SELECT a FROM AuditLog a WHERE " +
           "LOWER(a.actor) = :actor AND " +
           "(:eventType IS NULL OR a.eventType = :eventType) AND " +
           "(:outcome IS NULL OR a.outcome = :outcome) AND " +
           "(:since IS NULL OR a.eventTime >= :since) AND " +
           "(:until IS NULL OR a.eventTime <= :until) AND " +
           "(:anomalyOnly = false OR (a.anomalyFlags IS NOT NULL AND a.anomalyFlags <> '')) " +
           "ORDER BY a.eventTime DESC")
    Page<AuditLog> findOwnFiltered(
        @Param("actor") String actor,
        @Param("eventType") String eventType,
        @Param("outcome") String outcome,
        @Param("since") String since,
        @Param("until") String until,
        @Param("anomalyOnly") boolean anomalyOnly,
        Pageable pageable);
}
