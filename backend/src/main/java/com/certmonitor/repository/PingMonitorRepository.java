package com.certmonitor.repository;

import com.certmonitor.model.PingMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PingMonitorRepository extends JpaRepository<PingMonitor, Long> {
    List<PingMonitor> findByActiveTrue();
    long countByActiveTrue();
    List<PingMonitor> findAllByOrderByNameAsc();
    Optional<PingMonitor> findFirstByHostOrderByIdAsc(String host);

    /** Aynı host (case-insensitive) + takım için (kendisi hariç) başka bir ping monitörü var mı. */
    @Query("SELECT COUNT(p) > 0 FROM PingMonitor p WHERE LOWER(p.host) = LOWER(:host) AND "
         + "((:teamId IS NULL AND p.teamId IS NULL) OR p.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR p.id <> :excludeId)")
    boolean existsDuplicate(@Param("host") String host, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM PingMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE PingMonitor m SET m.groupName = :newName WHERE m.groupName = :oldName AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);
}
