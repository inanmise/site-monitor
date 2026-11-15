package com.sitemonitor.repository;

import com.sitemonitor.model.OnCallSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Takım nöbet çizelgeleri (2026-10-11) — takım başına bir satır. Yazma {@code save} / {@code delete} ile. */
public interface OnCallScheduleRepository extends JpaRepository<OnCallSchedule, Long> {

    Optional<OnCallSchedule> findByTeamId(Long teamId);

    List<OnCallSchedule> findByTeamIdIn(Collection<Long> teamIds);

    List<OnCallSchedule> findByEnabledTrue();

    // ── Haftalık nöbet bildirimi (2026-11-15) ──────────────────────────────────────────────

    /** Ucuz ön kapı: hiçbir takım açmadıysa haftalık bildirim turu kilit almaz, başka sorgu koşmaz. */
    boolean existsByWeeklyNoticeEnabledTrue();

    /** Bildirimi açık çizelgeler (takım sayısıyla sınırlı). */
    List<OnCallSchedule> findByWeeklyNoticeEnabledTrue();

    // ── Haftalık bildirim kolonları: YALNIZ hedefli, tek kolonlu UPDATE (inceleme 2026-11-15) ──────────────────────
    // Kolonlar varlıkta {@code updatable = false}: çizelgenin tam kaydı (bayat okunmuş varlık) onları asla geri yazmaz; iki
    // yöneticinin eşzamanlı farklı alan değişikliği (biri gün, öteki saat) birbirini ezmez. 0 satır = çizelge yok.

    @Modifying
    @Transactional
    @Query("UPDATE OnCallSchedule s SET s.weeklyNoticeEnabled = :v WHERE s.teamId = :teamId")
    int setWeeklyNoticeEnabled(@Param("teamId") Long teamId, @Param("v") Boolean v);

    @Modifying
    @Transactional
    @Query("UPDATE OnCallSchedule s SET s.weeklyNoticeMail = :v WHERE s.teamId = :teamId")
    int setWeeklyNoticeMail(@Param("teamId") Long teamId, @Param("v") Boolean v);

    @Modifying
    @Transactional
    @Query("UPDATE OnCallSchedule s SET s.weeklyNoticePush = :v WHERE s.teamId = :teamId")
    int setWeeklyNoticePush(@Param("teamId") Long teamId, @Param("v") Boolean v);

    @Modifying
    @Transactional
    @Query("UPDATE OnCallSchedule s SET s.weeklyNoticeDay = :v WHERE s.teamId = :teamId")
    int setWeeklyNoticeDay(@Param("teamId") Long teamId, @Param("v") Integer v);

    @Modifying
    @Transactional
    @Query("UPDATE OnCallSchedule s SET s.weeklyNoticeTime = :v WHERE s.teamId = :teamId")
    int setWeeklyNoticeTime(@Param("teamId") Long teamId, @Param("v") String v);

    @Modifying
    @Transactional
    @Query("UPDATE OnCallSchedule s SET s.weeklyNoticeSince = :v WHERE s.teamId = :teamId")
    int setWeeklyNoticeSince(@Param("teamId") Long teamId, @Param("v") String v);

    /** Yalnız-bildirim kaydında kim / ne zaman (çizelgenin geri kalanına dokunmaz). */
    @Modifying
    @Transactional
    @Query("UPDATE OnCallSchedule s SET s.updatedAt = :updatedAt, s.updatedBy = :updatedBy WHERE s.teamId = :teamId")
    int touchUpdated(@Param("teamId") Long teamId, @Param("updatedAt") String updatedAt, @Param("updatedBy") String updatedBy);
}
