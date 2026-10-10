package com.sitemonitor.repository;

import com.sitemonitor.model.ExecutiveSummaryTeamReport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Takım yönetici özeti gönderim kayıtları (takım × ay başına tek satır). */
public interface ExecutiveSummaryTeamReportRepository extends JpaRepository<ExecutiveSummaryTeamReport, Long> {

    Optional<ExecutiveSummaryTeamReport> findByTeamIdAndReportYearAndReportMonth(Long teamId, Integer reportYear,
                                                                               Integer reportMonth);

    /** Takımın geçmişi — en yeni ay önce. */
    List<ExecutiveSummaryTeamReport> findByTeamIdOrderByReportYearDescReportMonthDesc(Long teamId, Pageable pageable);

    /** Bir ayın bütün takım kayıtları (ayar listesindeki "son gönderim" sütunu — tek sorgu). */
    List<ExecutiveSummaryTeamReport> findByReportYearAndReportMonthAndTeamIdIn(Integer reportYear, Integer reportMonth,
                                                                               Collection<Long> teamIds);

    /** Koşullu YENİDEN TALEP — {@code ExecutiveSummaryReportRepository#reclaim} ile aynı sözleşme. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ExecutiveSummaryTeamReport r
               SET r.status = 'SENDING', r.attempts = :nextAttempts, r.claimedAt = :claimedAt,
                   r.triggerKind = :triggerKind, r.actor = :actor
             WHERE r.id = :id AND r.status = :expectedStatus AND r.attempts = :expectedAttempts
            """)
    int reclaim(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                @Param("expectedAttempts") Integer expectedAttempts, @Param("nextAttempts") Integer nextAttempts,
                @Param("claimedAt") String claimedAt, @Param("triggerKind") String triggerKind,
                @Param("actor") String actor);

    /**
     * Seçilebilir pencereden eski kayıtlar ({@code yıl × 12 + ay < cutoff}). Zamanlanmış gönderim her koşuda çağırır —
     * tablo takım × 25 aydan fazla büyümez. Dönen: silinen satır sayısı.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ExecutiveSummaryTeamReport r WHERE (r.reportYear * 12 + r.reportMonth) < :cutoff")
    int deleteOlderThan(@Param("cutoff") int cutoff);
}
