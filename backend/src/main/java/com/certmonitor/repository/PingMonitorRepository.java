package com.certmonitor.repository;

import com.certmonitor.model.PingMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PingMonitorRepository extends JpaRepository<PingMonitor, Long> {
    List<PingMonitor> findByActiveTrue();
    List<PingMonitor> findAllByOrderByNameAsc();
    Optional<PingMonitor> findFirstByHostOrderByIdAsc(String host);

    /** Aynı host (case-insensitive) + takım için (kendisi hariç) başka bir ping monitörü var mı. */
    @Query("SELECT COUNT(p) > 0 FROM PingMonitor p WHERE LOWER(p.host) = LOWER(:host) AND "
         + "((:teamId IS NULL AND p.teamId IS NULL) OR p.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR p.id <> :excludeId)")
    boolean existsDuplicate(@Param("host") String host, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);
}
