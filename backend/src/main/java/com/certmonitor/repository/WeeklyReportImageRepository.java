package com.certmonitor.repository;

import com.certmonitor.model.WeeklyReportImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WeeklyReportImageRepository extends JpaRepository<WeeklyReportImage, Long> {

    List<WeeklyReportImage> findByReportIdOrderByIdAsc(Long reportId);

    /** Meta-only (data hariç) — liste/detayda byte[] yüklemeyi önler. */
    List<WeeklyReportImageMetaView> findProjectedByReportIdOrderByIdAsc(Long reportId);

    long countByReportId(Long reportId);

    void deleteByReportId(Long reportId);
}
