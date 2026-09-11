package com.sitemonitor.repository;

import com.sitemonitor.model.DeploymentHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DeploymentHistoryRepository extends JpaRepository<DeploymentHistory, Long> {

    /** Geçiş türetimi için ortamın TÜM kayıtları — started_at, id sırasıyla (aynı sn'de iki pod: id kırar). */
    List<DeploymentHistory> findByEnvironmentOrderByStartedAtAscIdAsc(String environment);

    /** Bu ortamda sürümü bilinen son kayıt (koşan sürüm). */
    Optional<DeploymentHistory> findTopByEnvironmentAndVersionIsNotNullOrderByStartedAtDescIdDesc(String environment);

    boolean existsByAuditRef(Long auditRef);

    @Query("SELECT DISTINCT d.environment FROM DeploymentHistory d ORDER BY d.environment")
    List<String> findDistinctEnvironments();

    /**
     * Sunucu-taraflı süzgeç + sayfalama (Sistem Sağlığı → Sürüm & Dağıtım tablosu). Nullable metin
     * param'ları CAST'li (RepositoryNullableParamCastTest). Sıralama Pageable'dan gelir (controller
     * beyaz-listeler). Geçiş türü (UPGRADE/RESTART/…) DB'de yok — servis sayfa üzerinde türetir.
     */
    @Query("""
           SELECT d FROM DeploymentHistory d
           WHERE (:env IS NULL OR d.environment = CAST(:env AS string))
             AND (:source IS NULL OR d.source = CAST(:source AS string))
             AND (:since IS NULL OR d.startedAt >= :since)
             AND (:until IS NULL OR d.startedAt <= :until)
             AND (:q IS NULL
                  OR LOWER(COALESCE(d.version, ''))    LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                  OR LOWER(COALESCE(d.gitCommit, ''))  LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                  OR LOWER(COALESCE(d.podName, ''))    LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                  OR LOWER(COALESCE(d.note, ''))       LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
           """)
    Page<DeploymentHistory> search(@Param("env") String env,
                                   @Param("source") String source,
                                   @Param("since") String since,
                                   @Param("until") String until,
                                   @Param("q") String q,
                                   Pageable pageable);

    /** Heartbeat: last_seen_at tazeleme (60 sn). Yazan metod → @Transactional + int (RepositoryWriteTransactionGuardTest). */
    @Transactional
    @Modifying
    @Query("UPDATE DeploymentHistory d SET d.lastSeenAt = :at WHERE d.id = :id")
    int touch(@Param("id") Long id, @Param("at") String at);

    @Transactional
    @Modifying
    @Query("UPDATE DeploymentHistory d SET d.readyAt = :at WHERE d.id = :id AND d.readyAt IS NULL")
    int markReady(@Param("id") Long id, @Param("at") String at);

    @Transactional
    @Modifying
    @Query("UPDATE DeploymentHistory d SET d.endedAt = :at, d.endReason = :reason WHERE d.id = :id AND d.endedAt IS NULL")
    int markEnded(@Param("id") Long id, @Param("at") String at, @Param("reason") String reason);

    /** Yalnız MANUAL satır silinebilir (K10) — servis kaynağı doğrular, burada da güvence. */
    @Transactional
    @Modifying
    @Query("DELETE FROM DeploymentHistory d WHERE d.id = :id AND d.source = 'MANUAL'")
    int deleteManual(@Param("id") Long id);
}
