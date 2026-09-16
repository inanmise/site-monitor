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

    /** Logout/oturum sonu: kullanıcının elindeki tüm düzenleme kilitlerini serbest bırak. */
    @Modifying
    @Query("UPDATE WeeklyReport r SET r.editingBy = null, r.editingUserId = null,"
            + " r.editingHeartbeat = null WHERE r.editingUserId = :userId")
    int clearLocksByUser(@Param("userId") Long userId);
}
