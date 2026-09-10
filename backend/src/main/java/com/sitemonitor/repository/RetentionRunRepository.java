package com.sitemonitor.repository;

import com.sitemonitor.model.RetentionRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RetentionRunRepository extends JpaRepository<RetentionRun, Long> {

    /** Sağlık sinyali: en son GERÇEK (dry-run olmayan) çalışma. */
    Optional<RetentionRun> findFirstByDryRunFalseOrderByStartedAtDesc();

    /** Ekrandaki çalışma geçmişi (dry-run'lar dahil). */
    Page<RetentionRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    /**
     * Sunucu-taraflı süzgeç + sayfalama (Veri Saklama → Son çalışmalar). {@code kind}:
     * all | real (dry=false, hold=false) | dry | hold. Nullable metin param'ları CAST'li
     * (RepositoryNullableParamCastTest). Sıralama Pageable'dan gelir (controller beyaz-listeler).
     */
    @org.springframework.data.jpa.repository.Query("""
           SELECT r FROM RetentionRun r
           WHERE (:kind = 'all'
                  OR (:kind = 'real' AND r.dryRun = false AND r.holdActive = false)
                  OR (:kind = 'dry'  AND r.dryRun = true)
                  OR (:kind = 'hold' AND r.holdActive = true))
             AND (:failedOnly = FALSE OR r.failedCount > 0)
             AND (:since IS NULL OR r.startedAt >= :since)
             AND (:until IS NULL OR r.startedAt <= :until)
             AND (:q IS NULL
                  OR LOWER(COALESCE(r.triggeredBy, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                  OR LOWER(COALESCE(r.instanceId, ''))  LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
             AND (:policyId IS NULL OR EXISTS (SELECT 1 FROM RetentionRunItem i
                                                WHERE i.runId = r.id AND i.policyId = CAST(:policyId AS string)))
           """)
    Page<RetentionRun> search(@org.springframework.data.repository.query.Param("kind") String kind,
                              @org.springframework.data.repository.query.Param("failedOnly") boolean failedOnly,
                              @org.springframework.data.repository.query.Param("since") String since,
                              @org.springframework.data.repository.query.Param("until") String until,
                              @org.springframework.data.repository.query.Param("q") String q,
                              @org.springframework.data.repository.query.Param("policyId") String policyId,
                              Pageable pageable);
}
