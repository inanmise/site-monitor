package com.sitemonitor.repository;

import com.sitemonitor.model.DbQueryTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DbQueryTemplateRepository extends JpaRepository<DbQueryTemplate, Long> {

    List<DbQueryTemplate> findByTeamIdOrderByNameAsc(Long teamId);
    List<DbQueryTemplate> findByTeamIdInOrderByNameAsc(Collection<Long> teamIds);
    long countByTeamId(Long teamId);

    @Query("SELECT COUNT(t) > 0 FROM DbQueryTemplate t WHERE LOWER(t.name) = LOWER(:name) AND t.teamId = :teamId "
         + "AND (:excludeId IS NULL OR t.id <> :excludeId)")
    boolean existsDuplicate(@Param("name") String name, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    /** Takım taşıma (TeamAdminService.moveAll). Çağıranda @Transactional. */
    @Modifying
    @Query("UPDATE DbQueryTemplate t SET t.teamId = :to WHERE t.teamId = :from")
    int moveTeam(@Param("from") Long from, @Param("to") Long to);
}
