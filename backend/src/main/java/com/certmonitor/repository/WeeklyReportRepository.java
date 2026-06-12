package com.certmonitor.repository;

import com.certmonitor.model.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    /** Yıl filtresi dropdown'ı — rapor bulunan yıllar (yeni → eski). */
    @Query("select distinct r.reportYear from WeeklyReport r"
            + " where (:teamId is null or r.teamId = :teamId) order by r.reportYear desc")
    List<Integer> findDistinctYears(@Param("teamId") Long teamId);

    List<WeeklyReport> findByTeamIdAndReportYearOrderByWeekNoDesc(Long teamId, Integer reportYear);

    /** ADMIN görünümü — tüm takımlar. */
    List<WeeklyReport> findByReportYearOrderByTeamIdAscWeekNoDesc(Integer reportYear);

    Optional<WeeklyReport> findByTeamIdAndReportYearAndWeekNo(Long teamId, Integer reportYear, Integer weekNo);

    /** Yeni hafta şablonunun kaynağı — takımın en güncel raporu. */
    Optional<WeeklyReport> findFirstByTeamIdOrderByReportYearDescWeekNoDesc(Long teamId);
}
