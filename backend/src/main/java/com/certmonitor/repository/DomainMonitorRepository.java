package com.certmonitor.repository;

import com.certmonitor.model.DomainMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DomainMonitorRepository extends JpaRepository<DomainMonitor, Long> {
    List<DomainMonitor> findByActiveTrue();
    long countByActiveTrue();
    List<DomainMonitor> findAllByOrderByNameAsc();
    Optional<DomainMonitor> findFirstByDomainOrderByIdAsc(String domain);

    /** Aynı domain (case-insensitive) + takım için (kendisi hariç) başka bir domain monitörü var mı. */
    @Query("SELECT COUNT(m) > 0 FROM DomainMonitor m WHERE LOWER(m.domain) = LOWER(:domain) AND "
         + "((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicate(@Param("domain") String domain, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);

    /** [teamId, grup adı, sayı] — TAKIM-bazlı grup listesi (boş/null hariç); satır çekmeden DB-side GROUP BY. */
    @Query("SELECT m.teamId, m.groupName, COUNT(m) FROM DomainMonitor m WHERE m.groupName IS NOT NULL AND m.groupName <> '' GROUP BY m.teamId, m.groupName")
    List<Object[]> groupCountsByTeam();

    /** Bir TAKIMIN grup adını yeniden adlandır (yalnız o takımın monitörleri). Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE DomainMonitor m SET m.groupName = :newName WHERE LOWER(m.groupName) = LOWER(:oldName) AND ((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId)")
    int renameGroupForTeam(@Param("teamId") Long teamId, @Param("oldName") String oldName, @Param("newName") String newName);
}
