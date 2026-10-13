package com.sitemonitor.repository;

import com.sitemonitor.model.TechErrorReportImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Teknik hata raporu grafik görselleri (2026-10-13). Liste uçları baytsız {@link Meta} okur. */
public interface TechErrorReportImageRepository extends JpaRepository<TechErrorReportImage, Long> {

    interface Meta {
        Long getId();
        Long getReportId();
        String getSlotId();
        String getContentType();
        String getFileName();
        int getWidth();
        int getHeight();
        long getSizeBytes();
        Instant getUploadedAt();
        Long getUploadedById();
    }

    List<Meta> findByReportIdOrderByIdAsc(Long reportId);

    /** PDF için baytlarla — rapor başına en çok yuva sayısı kadar (≤ 40). */
    List<TechErrorReportImage> findAllByReportIdOrderByIdAsc(Long reportId);

    Optional<TechErrorReportImage> findByReportIdAndSlotId(Long reportId, String slotId);

    @Query("SELECT i.reportId, COUNT(i), COALESCE(SUM(i.sizeBytes), 0) FROM TechErrorReportImage i "
            + "WHERE i.reportId IN :ids GROUP BY i.reportId")
    List<Object[]> countsByReport(@Param("ids") Collection<Long> reportIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportImage i WHERE i.reportId = :reportId")
    int deleteAllForReport(@Param("reportId") Long reportId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportImage i WHERE i.reportId IN :reportIds")
    int deleteAllForReports(@Param("reportIds") Collection<Long> reportIds);

    /** Şablonun son hâli uygulanınca artık yuvası olmayan görseller. {@code keep} boş olmamalı (çağıran ayırır). */
    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportImage i WHERE i.reportId = :reportId AND i.slotId NOT IN :keep")
    int deleteSlotsNotIn(@Param("reportId") Long reportId, @Param("keep") Collection<String> keep);

    @Modifying
    @Transactional
    @Query("DELETE FROM TechErrorReportImage i WHERE i.reportId = :reportId AND i.slotId = :slotId")
    int deleteSlot(@Param("reportId") Long reportId, @Param("slotId") String slotId);
}
