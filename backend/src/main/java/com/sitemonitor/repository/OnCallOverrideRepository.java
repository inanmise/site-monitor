package com.sitemonitor.repository;

import com.sitemonitor.model.OnCallOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** Nöbet yer değiştirmeleri (2026-10-11). */
public interface OnCallOverrideRepository extends JpaRepository<OnCallOverride, Long> {

    /** Bir takımın bitmemiş (ya da {@code since}'ten sonra biten) yer değiştirmeleri, başlangıca göre. */
    List<OnCallOverride> findByTeamIdAndEndsAtGreaterThanOrderByStartsAtAscIdAsc(Long teamId, String since);

    /** Birden çok takım için aynı (alarm bildirim yolu — tek sorgu). */
    List<OnCallOverride> findByTeamIdInAndEndsAtGreaterThan(Collection<Long> teamIds, String since);

    /** Bitişinden uzun süre geçmiş satırların budanması (servis kaydederken). */
    @Modifying
    @Transactional
    @Query("DELETE FROM OnCallOverride o WHERE o.endsAt < :before")
    int deleteEndedBefore(@Param("before") String before);

    /** Takım silinince (TeamAdminService) — takımın tüm yer değiştirmeleri. */
    @Modifying
    @Transactional
    @Query("DELETE FROM OnCallOverride o WHERE o.teamId = :teamId")
    int deleteByTeam(@Param("teamId") Long teamId);
}
