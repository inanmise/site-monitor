package com.sitemonitor.repository;

import com.sitemonitor.model.SystemMaintenanceWindow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sistem Bakım Modu pencereleri ({@code system_maintenance_windows}, 2026-10-02). Satırlar silinmez (RetentionCatalog
 * {@code system-maintenance-windows}, BOUNDED); türetilmiş silme yöntemi YOK.
 *
 * <p>Sayaçlar ve yan iş damgaları YALNIZ buradaki hedefli UPDATE'lerle yazılır (varlıkta {@code updatable=false}) —
 * yöneticinin bayat varlık kaydı bunları ezemez. Sahiplenme sorguları ({@code claim*}) koşullu UPDATE'tir: 1 = bu çağrı
 * kazandı, 0 = başka pod / önceki tur zaten yaptı (tam bir kez).
 */
public interface SystemMaintenanceWindowRepository extends JpaRepository<SystemMaintenanceWindow, Long> {

    /** İptal edilmemiş ve henüz bitmemiş pencereler (başlangıca göre) — pod önbelleğinin tek sorgusu. */
    List<SystemMaintenanceWindow> findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(String nowIso);

    /** Yan işleri bitmemiş pencereler (iş turu). */
    @Query("SELECT w FROM SystemMaintenanceWindow w WHERE w.jobsDone IS NULL OR w.jobsDone = false ORDER BY w.id ASC")
    List<SystemMaintenanceWindow> findPendingJobs();

    @Query("SELECT COUNT(w) > 0 FROM SystemMaintenanceWindow w WHERE w.jobsDone IS NULL OR w.jobsDone = false")
    boolean existsPendingJobs();

    /** Geçmiş — en yeni önce (sayfalı). */
    Page<SystemMaintenanceWindow> findAllByOrderByStartAtDescIdDesc(Pageable pageable);

    // ── Atomik sayaçlar ────────────────────────────────────────────────────────────────────────────

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET sessions_ended = COALESCE(sessions_ended, 0) + 1 WHERE id = :id",
            nativeQuery = true)
    int incrementSessionsEnded(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET logins_blocked = COALESCE(logins_blocked, 0) + 1 WHERE id = :id",
            nativeQuery = true)
    int incrementLoginsBlocked(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET notifications_suppressed = COALESCE(notifications_suppressed, 0) + 1 "
            + "WHERE id = :id", nativeQuery = true)
    int incrementNotificationsSuppressed(@Param("id") Long id);

    // ── Yan iş sahiplenmeleri (tam bir kez) ────────────────────────────────────────────────────────

    /** Duyuru e-postasını sahiplenir — yalnız henüz gönderilmemişse. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET announce_mail_at = :at, mailed_revision = :rev "
            + "WHERE id = :id AND announce_mail_at IS NULL", nativeQuery = true)
    int claimAnnounceMail(@Param("id") Long id, @Param("at") String at, @Param("rev") int rev);

    /** Düzeltme e-postasını sahiplenir — yalnız son gönderilen sürüm bu sürümden ESKİYSE. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET mailed_revision = :rev, correction_mail_at = :at "
            + "WHERE id = :id AND announce_mail_at IS NOT NULL AND COALESCE(mailed_revision, 0) < :rev", nativeQuery = true)
    int claimCorrectionMail(@Param("id") Long id, @Param("at") String at, @Param("rev") int rev);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET announce_mail_status = :status, announce_mail_count = :count "
            + "WHERE id = :id", nativeQuery = true)
    int recordAnnounceMail(@Param("id") Long id, @Param("status") String status, @Param("count") int count);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET correction_mail_count = COALESCE(correction_mail_count, 0) + 1 "
            + "WHERE id = :id", nativeQuery = true)
    int incrementCorrectionMails(@Param("id") Long id);

    /**
     * "Bakım tamamlandı" e-postasını sahiplenir (2026-10-02, kullanıcı isteği) — yalnız henüz gönderilmemişse; bitişte tek
     * pod, tek kez.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET end_mail_at = :at WHERE id = :id AND end_mail_at IS NULL",
            nativeQuery = true)
    int claimEndMail(@Param("id") Long id, @Param("at") String at);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET end_mail_status = :status, end_mail_count = :count WHERE id = :id",
            nativeQuery = true)
    int recordEndMail(@Param("id") Long id, @Param("status") String status, @Param("count") int count);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET start_logged_at = :at WHERE id = :id AND start_logged_at IS NULL",
            nativeQuery = true)
    int claimStartLog(@Param("id") Long id, @Param("at") String at);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET end_logged_at = :at WHERE id = :id AND end_logged_at IS NULL",
            nativeQuery = true)
    int claimEndLog(@Param("id") Long id, @Param("at") String at);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET catch_up_at = :at WHERE id = :id AND catch_up_at IS NULL",
            nativeQuery = true)
    int claimCatchUp(@Param("id") Long id, @Param("at") String at);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET caught_up_count = :count WHERE id = :id", nativeQuery = true)
    int recordCaughtUp(@Param("id") Long id, @Param("count") int count);

    @Modifying
    @Transactional
    @Query(value = "UPDATE system_maintenance_windows SET jobs_done = TRUE WHERE id = :id", nativeQuery = true)
    int markJobsDone(@Param("id") Long id);
}
