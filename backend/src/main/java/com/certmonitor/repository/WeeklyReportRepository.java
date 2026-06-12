package com.certmonitor.repository;

import com.certmonitor.model.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    List<WeeklyReport> findByTeamIdAndReportYearOrderByWeekNoDesc(Long teamId, Integer reportYear);

    /** ADMIN görünümü — tüm takımlar. */
    List<WeeklyReport> findByReportYearOrderByTeamIdAscWeekNoDesc(Integer reportYear);

    Optional<WeeklyReport> findByTeamIdAndReportYearAndWeekNo(Long teamId, Integer reportYear, Integer weekNo);

    /** Yeni hafta şablonunun kaynağı — takımın en güncel raporu. */
    Optional<WeeklyReport> findFirstByTeamIdOrderByReportYearDescWeekNoDesc(Long teamId);
}
