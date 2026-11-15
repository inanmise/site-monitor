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

    /**
     * Yalnız bildirim ayarı değişti: çizelgenin GERİ KALANINA dokunmadan bildirim kolonlarını yazar (eşzamanlı bir çizelge
     * kaydı ezilmez). 0 satır = çizelge yok.
     */
    @Modifying
    @Transactional
    @Query("UPDATE OnCallSchedule s SET s.weeklyNoticeEnabled = :enabled, s.weeklyNoticeMail = :mail, "
            + "s.weeklyNoticePush = :push, s.weeklyNoticeDay = :day, s.weeklyNoticeTime = :time, "
            + "s.weeklyNoticeSince = :since, s.updatedAt = :updatedAt, s.updatedBy = :updatedBy WHERE s.teamId = :teamId")
    int updateWeeklyNotice(@Param("teamId") Long teamId, @Param("enabled") Boolean enabled, @Param("mail") Boolean mail,
                           @Param("push") Boolean push, @Param("day") Integer day, @Param("time") String time,
                           @Param("since") String since, @Param("updatedAt") String updatedAt, @Param("updatedBy") String updatedBy);
}
