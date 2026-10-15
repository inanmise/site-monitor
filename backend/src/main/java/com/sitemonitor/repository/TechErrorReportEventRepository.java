package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorReportEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Teknik hata raporu değişiklik geçmişi (2026-10-14). Okuma anahtar kümesi sayfalı (id azalan). */
public interface TechErrorReportEventRepository extends JpaRepository<TechErrorReportEvent, Long> {

    /** En yeni olaylar (ilk sayfa). */
    List<TechErrorReportEvent> findByReportIdOrderByIdDesc(Long reportId, Pageable page);

    /** {@code beforeId}'den eski olaylar (sonraki sayfa — anahtar kümesi). */
    List<TechErrorReportEvent> findByReportIdAndIdLessThanOrderByIdDesc(Long reportId, Long beforeId, Pageable page);

    long countByReportId(Long reportId);

    /** Raporun ilk kayıtlı olayının anı (geçmişin ne zamandan beri tutulduğu); hiç yoksa null. */
    @Query("SELECT MIN(e.eventAt) FROM TechErrorReportEvent e WHERE e.reportId = :reportId")
    Instant firstEventAt(@Param("reportId") Long reportId);

    /** Budama eşiği: en yeni {@code page.offset} olaydan sonraki ilk olayın kimliği. */
    @Query("SELECT e.id FROM TechErrorReportEvent e WHERE e.reportId = :reportId ORDER BY e.id DESC")
    List<Long> idsNewestFirst(@Param("reportId") Long reportId, Pageable page);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportEvent e WHERE e.reportId = :reportId AND e.id <= :maxId")
    int deleteUpTo(@Param("reportId") Long reportId, @Param("maxId") Long maxId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportEvent e WHERE e.reportId = :reportId")
    int deleteAllForReport(@Param("reportId") Long reportId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportEvent e WHERE e.reportId IN :reportIds")
    int deleteAllForReports(@Param("reportIds") Collection<Long> reportIds);
}
