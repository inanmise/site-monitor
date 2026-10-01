package com.sitemonitor.repository;

import com.sitemonitor.model.AlertStorm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertStormRepository extends JpaRepository<AlertStorm, Long> {

    /** Yaşam döngüsü sweep'i — yönetilecek aktif (açık) storm'lar. */
    List<AlertStorm> findByResolvedFalse();

    /** Bir scope için aktif storm (kısmi UNIQUE indeks → en fazla bir tane). */
    Optional<AlertStorm> findByScopeKeyAndResolvedFalse(String scopeKey);

    // ── Gözlem / analiz (2026-09-30) ──
    /** Belirli andan sonra AÇILMIŞ fırtınalar (analiz penceresi), yeniden eskiye. */
    java.util.List<AlertStorm> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(String since);

    /** Takım süzgeçli geçmiş: {@code teamIds} boş → süzgeç yok. Eski (team_id NULL) satırlar yalnız süzgeçsiz listede. */
    @org.springframework.data.jpa.repository.Query("""
        SELECT s FROM AlertStorm s
        WHERE (:noTeamFilter = TRUE OR s.teamId IN :teamIds)
          AND (:since IS NULL OR s.createdAt >= :since)
          AND (:until IS NULL OR s.createdAt <= :until)
          AND (:onlyResolved = FALSE OR s.resolved = TRUE)
        ORDER BY s.createdAt DESC
        """)
    org.springframework.data.domain.Page<AlertStorm> findHistory(
            @org.springframework.data.repository.query.Param("noTeamFilter") boolean noTeamFilter,
            @org.springframework.data.repository.query.Param("teamIds") java.util.Collection<Long> teamIds,
            @org.springframework.data.repository.query.Param("since") String since,
            @org.springframework.data.repository.query.Param("until") String until,
            @org.springframework.data.repository.query.Param("onlyResolved") boolean onlyResolved,
            org.springframework.data.domain.Pageable pageable);

    /** Analiz penceresi, takım süzgeci SQL'de (2026-10-01). */
    java.util.List<AlertStorm> findByTeamIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(Long teamId, String since);

    /**
     * Açık fırtınaların üyeleri — DAR izdüşüm, TEK sorgu (2026-10-01): durum ekranı 30 sn'de bir her açık fırtına için
     * TEXT kolonlu tam {@code AlertEvent} yüklüyordu. Satır: [stormId, id, domain, alertType, alertLevel, createdAt, resolved].
     */
    @org.springframework.data.jpa.repository.Query("SELECT e.stormId, e.id, e.domain, e.alertType, e.alertLevel, e.createdAt, e.resolved "
            + "FROM AlertEvent e WHERE e.stormId IN :stormIds")
    java.util.List<Object[]> liveMembers(@org.springframework.data.repository.query.Param("stormIds") java.util.Collection<Long> stormIds);

    /** Takım başına son fırtına açılış anı (durum kartı "son fırtına"). */
    @org.springframework.data.jpa.repository.Query("SELECT s.teamId, MAX(s.createdAt), COUNT(s) FROM AlertStorm s WHERE s.teamId IS NOT NULL AND s.createdAt >= :since GROUP BY s.teamId")
    java.util.List<Object[]> lastStormPerTeam(@org.springframework.data.repository.query.Param("since") String since);
}
