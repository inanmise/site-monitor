package com.sitemonitor.repository;

import com.sitemonitor.model.ExecutiveSummaryReport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Aylık yönetici özeti gönderim kayıtları (ay başına tek satır). */
public interface ExecutiveSummaryReportRepository extends JpaRepository<ExecutiveSummaryReport, Long> {

    Optional<ExecutiveSummaryReport> findByReportYearAndReportMonth(Integer reportYear, Integer reportMonth);

    /** Geçmiş listesi — en yeni ay önce. */
    List<ExecutiveSummaryReport> findAllByOrderByReportYearDescReportMonthDesc(Pageable pageable);

    /**
     * Koşullu YENİDEN TALEP: satır hâlâ okunan durumda ve okunan deneme sayısındaysa SENDING'e çekilir. İki pod aynı anda
     * denerse yalnız biri 1 satır günceller (diğeri 0 görür ve gönderim yapmaz). Dönen değer güncellenen satır sayısı.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ExecutiveSummaryReport r
               SET r.status = 'SENDING', r.attempts = :nextAttempts, r.claimedAt = :claimedAt,
                   r.triggerKind = :triggerKind, r.actor = :actor
             WHERE r.id = :id AND r.status = :expectedStatus AND r.attempts = :expectedAttempts
            """)
    int reclaim(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                @Param("expectedAttempts") Integer expectedAttempts, @Param("nextAttempts") Integer nextAttempts,
                @Param("claimedAt") String claimedAt, @Param("triggerKind") String triggerKind,
                @Param("actor") String actor);
}
