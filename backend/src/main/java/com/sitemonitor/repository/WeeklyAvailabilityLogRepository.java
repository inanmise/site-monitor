package com.sitemonitor.repository;

import com.sitemonitor.model.WeeklyAvailabilityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface WeeklyAvailabilityLogRepository extends JpaRepository<WeeklyAvailabilityLog, Long> {
    Optional<WeeklyAvailabilityLog> findByTeamIdAndReportYearAndWeekNo(Long teamId, Integer reportYear, Integer weekNo);

    /** En son zamanlanmış gönderim kaydı (scheduler kartı "son çalışma" için). sentAt UTC ISO → leksikografik sıralanabilir. */
    Optional<WeeklyAvailabilityLog> findTopByOrderBySentAtDesc();

    /** Belirli (yıl, hafta) için tüm takım kayıtları — son çalışmanın özetini (gönderilen/hatalı) çıkarmak için. */
    List<WeeklyAvailabilityLog> findByReportYearAndWeekNo(Integer reportYear, Integer weekNo);

    // ── Takım × hafta SAHİPLENMESİ (2026-10-21, çok pod MP-40) ──────────────────────────────────────────────────────
    // Satır (uk_wal_team_week) postadan ÖNCE SENDING olarak alınır; yeni satır INSERT ile (benzersizlik ikinci pod'u
    // durdurur), var olan satır okunan durum + damga üzerinden koşullu UPDATE ile. Sahiplenme damgası sent_at'tedir
    // (SENDING iken = sahiplenme anı). Bitiş yalnız AYNI sahiplenme hâlâ geçerliyse yazılır.

    /** Var olan satırı SENDING'e çevirir — okunan durum ve damga hâlâ aynıysa (CAS; null = "-"). */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyAvailabilityLog w SET w.status = 'SENDING', w.sentAt = :claimedAt"
            + " WHERE w.teamId = :teamId AND w.reportYear = :year AND w.weekNo = :week"
            + " AND COALESCE(w.status, '-') = :expectedStatus AND COALESCE(w.sentAt, '-') = :expectedSentAt")
    int claimExisting(@Param("teamId") Long teamId, @Param("year") Integer year, @Param("week") Integer week,
                      @Param("expectedStatus") String expectedStatus, @Param("expectedSentAt") String expectedSentAt,
                      @Param("claimedAt") String claimedAt);

    /** Sahiplenmeyi son duruma çevirir (SENT / FAILED / SKIPPED / NO_RECIPIENT) — yalnız bu sahiplenme hâlâ geçerliyse. */
    @Modifying
    @Transactional
    @Query("UPDATE WeeklyAvailabilityLog w SET w.status = :status, w.sentAt = :finishedAt"
            + " WHERE w.teamId = :teamId AND w.reportYear = :year AND w.weekNo = :week"
            + " AND w.status = 'SENDING' AND w.sentAt = :claimedAt")
    int finishClaim(@Param("teamId") Long teamId, @Param("year") Integer year, @Param("week") Integer week,
                    @Param("claimedAt") String claimedAt, @Param("status") String status,
                    @Param("finishedAt") String finishedAt);
}
