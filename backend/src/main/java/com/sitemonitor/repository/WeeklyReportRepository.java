package com.sitemonitor.repository;

import com.sitemonitor.model.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long> {

    /** Ayar ekranı: takım → rapor sayısı (modül kapatılırken "veri var mı" uyarısı için). */
    @org.springframework.data.jpa.repository.Query("SELECT r.teamId, COUNT(r) FROM WeeklyReport r GROUP BY r.teamId")
    java.util.List<Object[]> countByTeam();

    /** Yıl filtresi dropdown'ı — rapor bulunan yıllar (yeni → eski). */
    @Query("SELECT DISTINCT r.reportYear FROM WeeklyReport r"
            + " WHERE (:teamId IS NULL OR r.teamId = :teamId) ORDER BY r.reportYear DESC")
    List<Integer> findDistinctYears(@Param("teamId") Long teamId);

    List<WeeklyReport> findByTeamIdAndReportYearOrderByWeekNoDesc(Long teamId, Integer reportYear);

    /** ADMIN görünümü — tüm takımlar. */
    List<WeeklyReport> findByReportYearOrderByTeamIdAscWeekNoDesc(Integer reportYear);

    Optional<WeeklyReport> findByTeamIdAndReportYearAndWeekNo(Long teamId, Integer reportYear, Integer weekNo);

    /** E-posta ile hızlı onay — token ile rapor bulma. */
    Optional<WeeklyReport> findByApprovalToken(String approvalToken);

    /** Yeni hafta şablonunun kaynağı — takımın en güncel raporu. */
    Optional<WeeklyReport> findFirstByTeamIdOrderByReportYearDescWeekNoDesc(Long teamId);

    /**
     * Takım kümesinin her biri için EN GÜNCEL rapor (yıl/hafta) — TEK sorgu (2026-10-01, performans: takım başına
     * {@link #findFirstByTeamIdOrderByReportYearDescWeekNoDesc} yerine). Taşınabilir JPQL (PostgreSQL {@code DISTINCT ON}
     * H2'de yok): {@code (takım, yıl, hafta)} tekil olduğundan ({@code ux_weekly_report_team_week}) takım başına en çok
     * bir satır döner. {@code yıl*100 + hafta} sırası (yıl, hafta) sırasıyla aynıdır (hafta ≤ 53).
     */
    @Query("SELECT r FROM WeeklyReport r WHERE r.teamId IN :teamIds"
            + " AND (r.reportYear * 100 + r.weekNo) = (SELECT MAX(r2.reportYear * 100 + r2.weekNo)"
            + " FROM WeeklyReport r2 WHERE r2.teamId = r.teamId)")
    List<WeeklyReport> findLatestPerTeam(@Param("teamIds") java.util.Collection<Long> teamIds);

    /** Logout/oturum sonu: kullanıcının elindeki tüm düzenleme kilitlerini serbest bırak. */
    @Modifying
    @Query("UPDATE WeeklyReport r SET r.editingBy = null, r.editingUserId = null,"
            + " r.editingHeartbeat = null WHERE r.editingUserId = :userId")
    int clearLocksByUser(@Param("userId") Long userId);
}
