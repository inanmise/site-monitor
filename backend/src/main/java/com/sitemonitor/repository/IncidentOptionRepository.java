package com.sitemonitor.repository;

import com.sitemonitor.model.IncidentOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IncidentOptionRepository extends JpaRepository<IncidentOption, Long> {

    /** Tüm seçenekler (admin listesi — tüm takımlar + global). */
    List<IncidentOption> findByTypeOrderByValueAsc(String type);

    Optional<IncidentOption> findFirstByTypeAndValueIgnoreCase(String type, String value);

    /** Takım kapsamı: global (team_id IS NULL) + verilen takım. teamId null ise yalnız global. */
    @Query("SELECT o FROM IncidentOption o WHERE o.type = :type AND (o.teamId IS NULL OR o.teamId = :teamId) ORDER BY o.value")
    List<IncidentOption> findByTypeForTeam(@Param("type") String type, @Param("teamId") Long teamId);

    /** Ekleme dedup'u: değer kapsamda (global VEYA bu takım) zaten var mı? */
    @Query("SELECT o FROM IncidentOption o WHERE o.type = :type AND LOWER(o.value) = LOWER(:value) AND (o.teamId IS NULL OR o.teamId = :teamId)")
    List<IncidentOption> findScoped(@Param("type") String type, @Param("value") String value, @Param("teamId") Long teamId);

    /** Admin silme: type+value eşleşen tüm satırlar (global/herhangi takım). */
    @Query("SELECT o FROM IncidentOption o WHERE o.type = :type AND LOWER(o.value) = LOWER(:value)")
    List<IncidentOption> findByTypeAndValue(@Param("type") String type, @Param("value") String value);

    /** Takım silme: yalnız o takıma ait satır (global/başka takım silinemez). */
    @Query("SELECT o FROM IncidentOption o WHERE o.type = :type AND LOWER(o.value) = LOWER(:value) AND o.teamId = :teamId")
    List<IncidentOption> findByTypeAndValueAndTeam(@Param("type") String type, @Param("value") String value, @Param("teamId") Long teamId);
}
