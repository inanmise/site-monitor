package com.certmonitor.repository;

import com.certmonitor.model.ScriptedMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScriptedMonitorRepository extends JpaRepository<ScriptedMonitor, Long> {
    List<ScriptedMonitor> findByActiveTrue();
    long countByActiveTrue();
    List<ScriptedMonitor> findAllByOrderByNameAsc();
    Optional<ScriptedMonitor> findFirstByNameOrderByIdAsc(String name);

    /** Aynı ad (case-insensitive) + takım için (kendisi hariç) başka bir senaryo var mı. */
    @Query("SELECT COUNT(m) > 0 FROM ScriptedMonitor m WHERE LOWER(m.name) = LOWER(:name) AND "
         + "((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicate(@Param("name") String name, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM ScriptedMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    @Modifying
    @Query("UPDATE ScriptedMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);
}
