package com.sitemonitor.repository;

import com.sitemonitor.model.OnCallWeeklyNotice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Haftalık nöbet bildirimi gönderim kayıtları (2026-11-15). Satır EKLEMEK sahiplenmedir (takım × hafta benzersiz); sonrası
 * yalnız koşullu UPDATE — bir pod başka pod'un sahiplendiği satırı kapatamaz, yarıda kalan satır yeniden gönderilmez.
 */
public interface OnCallWeeklyNoticeRepository extends JpaRepository<OnCallWeeklyNotice, Long> {

    /** Turun baktığı haftaların (önceki + bu) satırları — takım sayısı × 2 ile sınırlı. */
    List<OnCallWeeklyNotice> findByWeekKeyIn(Collection<String> weekKeys);

    Optional<OnCallWeeklyNotice> findByTeamIdAndWeekKey(Long teamId, String weekKey);

    /** Pencerede "son sonuç" satırı. Hafta anahtarı boş olamaz (NULLS FIRST tuzağı yok). */
    Optional<OnCallWeeklyNotice> findTopByTeamIdOrderByWeekKeyDescIdDesc(Long teamId);

    /** Yarıda kalmış olabilecek satırlar (sahiplenme damgası eşikten eski). */
    List<OnCallWeeklyNotice> findTop200ByStatusAndClaimedAtLessThanOrderByIdAsc(String status, String before);

    /** Sahiplenmeyi kapatır — yalnız bu sahiplenme (durum + damga) hâlâ geçerliyse. */
    @Modifying
    @Transactional
    @Query("UPDATE OnCallWeeklyNotice n SET n.status = :status, n.reason = :reason, n.mailStatus = :mailStatus, "
            + "n.pushCount = :pushCount, n.errorClass = :errorClass, n.finishedAt = :finishedAt "
            + "WHERE n.id = :id AND n.status = 'SENDING' AND n.claimedAt = :claimedAt")
    int finish(@Param("id") Long id, @Param("claimedAt") String claimedAt, @Param("status") String status,
               @Param("reason") String reason, @Param("mailStatus") String mailStatus, @Param("pushCount") Integer pushCount,
               @Param("errorClass") String errorClass, @Param("finishedAt") String finishedAt);

    /** Yarıda kalan sahiplenme → UNKNOWN (yeniden gönderilmez). Koşullu: bu arada sonuçlanan satır ezilmez. */
    @Modifying
    @Transactional
    @Query("UPDATE OnCallWeeklyNotice n SET n.status = 'UNKNOWN', n.reason = 'INTERRUPTED', n.finishedAt = :finishedAt "
            + "WHERE n.id = :id AND n.status = 'SENDING' AND n.claimedAt = :claimedAt")
    int markUnknown(@Param("id") Long id, @Param("claimedAt") String claimedAt, @Param("finishedAt") String finishedAt);

    /** FAILED satırı yeniden sahiplenir (deneme sayısı CAS) — iki pod aynı yeniden denemeyi iki kez yapamaz. */
    @Modifying
    @Transactional
    @Query("UPDATE OnCallWeeklyNotice n SET n.status = 'SENDING', n.claimedAt = :claimedAt, n.claimedBy = :claimedBy, "
            + "n.attempts = n.attempts + 1, n.finishedAt = NULL "
            + "WHERE n.id = :id AND n.status = 'FAILED' AND n.attempts = :seenAttempts")
    int reclaimFailed(@Param("id") Long id, @Param("seenAttempts") Integer seenAttempts, @Param("claimedAt") String claimedAt,
                      @Param("claimedBy") String claimedBy);

    /** Takım silinince (OnCallService.deleteForTeam). */
    @Modifying
    @Transactional
    @Query("DELETE FROM OnCallWeeklyNotice n WHERE n.teamId = :teamId")
    int deleteByTeam(@Param("teamId") Long teamId);
}
