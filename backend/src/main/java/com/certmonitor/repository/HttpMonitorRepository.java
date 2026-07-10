package com.certmonitor.repository;

import com.certmonitor.model.HttpMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HttpMonitorRepository extends JpaRepository<HttpMonitor, Long> {
    List<HttpMonitor> findByActiveTrue();
    List<HttpMonitor> findAllByOrderByNameAsc();
    Optional<HttpMonitor> findFirstByUrlOrderByIdAsc(String url);

    /** Aynı URL (case-insensitive) + takım için (kendisi hariç) başka bir HTTP monitörü var mı. */
    @Query("SELECT COUNT(m) > 0 FROM HttpMonitor m WHERE LOWER(m.url) = LOWER(:url) AND "
         + "((:teamId IS NULL AND m.teamId IS NULL) OR m.teamId = :teamId) AND "
         + "(:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsDuplicate(@Param("url") String url, @Param("teamId") Long teamId, @Param("excludeId") Long excludeId);
}
