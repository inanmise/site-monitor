package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorReportIncident;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Anomali konusu ↔ olay kaydı bağları (2026-11-20). Satırlar raporun satır kilidi altında okunur / yazılır; silmeler toplu
 * JPQL (varlık başına silme yok).
 */
public interface TechErrorReportIncidentRepository extends JpaRepository<TechErrorReportIncident, Long> {

    /** Raporun bağları (bir raporda en çok konu sayısı kadar — bölüm başına 30). */
    List<TechErrorReportIncident> findByReportIdOrderByIdAsc(Long reportId);

    /** Olayın bağlı olduğu raporların bağları — en yeni rapor önce; çağıran sayfa boyutuyla sınırlar. */
    @Query("SELECT l FROM TechErrorReportIncident l WHERE l.incidentId = :incidentId ORDER BY l.reportId DESC, l.id DESC")
    List<TechErrorReportIncident> findByIncident(@Param("incidentId") Long incidentId, Pageable page);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportIncident l WHERE l.reportId = :reportId")
    int deleteForReport(@Param("reportId") Long reportId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportIncident l WHERE l.reportId IN :reportIds")
    int deleteForReports(@Param("reportIds") Collection<Long> reportIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportIncident l WHERE l.id IN :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
