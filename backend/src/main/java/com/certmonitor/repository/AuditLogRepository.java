package com.certmonitor.repository;

import com.certmonitor.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * APPEND-ONLY denetim deposu. Bilerek {@code JpaRepository} DEĞİL, çıplak {@link Repository} genişletir —
 * böylece {@code delete*} / genel {@code saveAll}/update yüzeyi uygulamaya HİÇ AÇILMAZ (kurcalanamazlık).
 * İzin verilenler: yeni kayıt {@link #save} (insert), okuma sorguları, ve YALNIZ geo/PTR kolonlarını güncelleyen
 * dar {@link #updateGeo} (çekirdek/hash alanlarına dokunamaz → hash zinciri kırılmaz). Retention silmesi
 * uygulama repo'sundan değil, {@code SchedulerService.cleanupOldLogs} JDBC'sinden yapılır.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    // ── Yazma: yalnız insert + dar geo update ────────────────────────────────────
    AuditLog save(AuditLog entry);

    Optional<AuditLog> findById(Long id);

    /** Geo/PTR zenginleştirmesi — hash'e girmeyen kolonlar; async backfill. Çekirdek alanlara dokunmaz. */
    @Modifying
    @Transactional
    @Query("UPDATE AuditLog a SET a.ipCountry = :country, a.ipCity = :city, a.ipOrg = :org, " +
           "a.ipReverseHost = :host WHERE a.id = :id")
    int updateGeo(@Param("id") Long id, @Param("country") String country, @Param("city") String city,
                  @Param("org") String org, @Param("host") String host);

    // ── Hash zinciri ─────────────────────────────────────────────────────────────
    /** Zincirin ucu (en yüksek seq) — persist() bir sonraki seq/prev_hash'i buradan alır. */
    Optional<AuditLog> findTopByOrderBySeqDesc();

    /** Doğrulama için zincirlenmiş (seq'i olan) satırları seq sırasıyla sayfalı okur (legacy null-seq hariç). */
    List<AuditLog> findBySeqNotNullOrderBySeqAsc(Pageable pageable);

    // ── Anomali/analitik okuma (mevcut) ──────────────────────────────────────────
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

    @Query("SELECT a FROM AuditLog a WHERE a.eventType IN :types AND a.eventTime >= :since ORDER BY a.eventTime ASC")
    List<AuditLog> findLoginEventsSince(@Param("types") List<String> types, @Param("since") String since);

    @Query("SELECT a FROM AuditLog a WHERE a.eventType IN :types AND a.eventTime >= :from AND a.eventTime <= :to ORDER BY a.eventTime ASC")
    List<AuditLog> findLoginEventsBetween(@Param("types") List<String> types,
                                          @Param("from") String from, @Param("to") String to);

    Optional<AuditLog> findTopByActorAndSessionIdOrderByEventTimeDesc(String actor, String sessionId);

    Optional<AuditLog> findTopByActorAndEventTypeAndOutcomeOrderByEventTimeDesc(
            String actor, String eventType, String outcome);

    // ── Filtreli okuma (admin) ───────────────────────────────────────────────────
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

    // ── Gelişmiş filtre + kaynak/aktör geçmişi (Batch C) ─────────────────────────

    /** Zengin filtre: aktör(LIKE)/actorId(exact)/çoklu-eventType/kaynak-tür+id/outcome/ip/tarih/anomali/serbest-metin. */
    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:actor IS NULL OR LOWER(a.actor) LIKE :actor) AND " +
           "(:actorId IS NULL OR a.actorId = :actorId) AND " +
           "(:typeFilter = FALSE OR a.eventType IN :types) AND " +
           "(:resourceType IS NULL OR a.resourceType = :resourceType) AND " +
           "(:resourceId IS NULL OR a.resourceId = :resourceId) AND " +
           "(:outcome IS NULL OR a.outcome = :outcome) AND " +
           "(:ip IS NULL OR a.ipAddress = :ip) AND " +
           "(:since IS NULL OR a.eventTime >= :since) AND " +
           "(:until IS NULL OR a.eventTime <= :until) AND " +
           "(:anomalyOnly = false OR (a.anomalyFlags IS NOT NULL AND a.anomalyFlags <> '')) AND " +
           "(:q IS NULL OR LOWER(a.actor) LIKE :q OR LOWER(a.resourceId) LIKE :q OR LOWER(a.detail) LIKE :q OR LOWER(a.changes) LIKE :q) " +
           "ORDER BY a.eventTime DESC")
    Page<AuditLog> findAdvanced(
        @Param("actor") String actor, @Param("actorId") Long actorId,
        @Param("typeFilter") boolean typeFilter, @Param("types") List<String> types,
        @Param("resourceType") String resourceType, @Param("resourceId") String resourceId,
        @Param("outcome") String outcome, @Param("ip") String ip,
        @Param("since") String since, @Param("until") String until,
        @Param("anomalyOnly") boolean anomalyOnly, @Param("q") String q, Pageable pageable);

    /** Bir kaynağın tüm geçmişi ("bu izlemeye kim ne yaptı"). */
    List<AuditLog> findByResourceTypeAndResourceIdOrderByEventTimeDesc(String resourceType, String resourceId, Pageable pageable);

    /** Bir kullanıcının tüm eylemleri. */
    List<AuditLog> findByActorIdOrderByEventTimeDesc(Long actorId, Pageable pageable);

    /** Aynı istekten doğan ilişkili olaylar. */
    List<AuditLog> findByCorrelationIdOrderBySeqAsc(String correlationId);

    /** Özet: pencere içi olay-türü dağılımı. */
    @Query("SELECT a.eventType, COUNT(a) FROM AuditLog a WHERE a.eventTime > :since GROUP BY a.eventType ORDER BY COUNT(a) DESC")
    List<Object[]> countByEventTypeSince(@Param("since") String since);

    /** Özet: pencere içi sonuç (SUCCESS/FAILURE/BLOCKED) dağılımı. */
    @Query("SELECT a.outcome, COUNT(a) FROM AuditLog a WHERE a.eventTime > :since GROUP BY a.outcome")
    List<Object[]> countByOutcomeSince(@Param("since") String since);

    /** Özet: en aktif aktörler. */
    @Query("SELECT a.actor, COUNT(a) FROM AuditLog a WHERE a.eventTime > :since AND a.actor IS NOT NULL GROUP BY a.actor ORDER BY COUNT(a) DESC")
    List<Object[]> topActorsSince(@Param("since") String since, Pageable pageable);

    /** Özet: günlük olay yoğunluğu (zaman-yoğunluğu grafiği). eventTime ISO string → ilk 10 karakter = gün (yyyy-MM-dd). */
    @Query("SELECT SUBSTRING(a.eventTime, 1, 10), COUNT(a) FROM AuditLog a WHERE a.eventTime > :since GROUP BY SUBSTRING(a.eventTime, 1, 10) ORDER BY SUBSTRING(a.eventTime, 1, 10)")
    List<Object[]> countByDaySince(@Param("since") String since);

    /**
     * findFiltered ile aynı ama actor tam (case-insensitive) eşleşme — bir kullanıcı adı başkasının
     * alt-dizesi olsa bile karışmasın. /api/me/audit'i besler.
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
