package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScriptedCheckRepository extends JpaRepository<ScriptedCheck, Long> {
    List<ScriptedCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<ScriptedCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    @Query("SELECT r FROM ScriptedCheck r WHERE r.monitorId = :id ORDER BY r.checkedAt DESC LIMIT :limit")
    List<ScriptedCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM ScriptedCheck r WHERE r.monitorId = :id AND r.checkedAt >= :since ORDER BY r.checkedAt DESC LIMIT :limit")
    List<ScriptedCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol — LATERAL (monitör başına tek index-seek; N+1 önleme). */
    @Query(value = "SELECT c.* FROM scripted_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM scripted_checks r WHERE r.monitor_id = m.id ORDER BY r.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<ScriptedCheck> findLatestPerMonitor();

    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Süre trend serisi: [checked_at, duration_ms, ok] — aralık + cap. */
    @Query("SELECT r.checkedAt, r.durationMs, r.ok FROM ScriptedCheck r "
         + "WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "ORDER BY r.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);

    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.ok = true THEN 1L ELSE 0L END), AVG(r.durationMs) "
         + "FROM ScriptedCheck r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);
}
