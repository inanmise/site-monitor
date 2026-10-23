package com.sitemonitor.repository;

import com.sitemonitor.model.SystemHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SystemHeartbeatRepository extends JpaRepository<SystemHeartbeat, Long> {
    Optional<SystemHeartbeat> findTopByOrderByRecordedAtDesc();
    List<SystemHeartbeat> findTop5ByOrderByRecordedAtDesc();
    List<SystemHeartbeat> findByRecordedAtAfterOrderByRecordedAtAsc(LocalDateTime cutoff);

    /**
     * Zaman çizelgesi için hafif izdüşüm (2026-10-23, çok pod R2b, MP-35): {@code [recordedAt, instanceId]} — kapanış
     * işaretleri hariç. 30 günlük pencerede N pod × 43 200 satır varlık olarak yüklenmesin.
     */
    @Query("SELECT h.recordedAt, h.instanceId FROM SystemHeartbeat h "
            + "WHERE h.recordedAt > :cutoff AND h.shutdownMarker IS NULL ORDER BY h.recordedAt ASC")
    List<Object[]> findBeatsAfter(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Pod başına özet: {@code [instanceId, MIN(recordedAt), MAX(recordedAt), kapanış işareti sayısı]} — pod'u bilinen
     * (instance_id dolu) satırlar, {@code since}'ten bu yana.
     */
    @Query("SELECT h.instanceId, MIN(h.recordedAt), MAX(h.recordedAt), "
            + "SUM(CASE WHEN h.shutdownMarker = TRUE THEN 1 ELSE 0 END) "
            + "FROM SystemHeartbeat h WHERE h.recordedAt >= :since AND h.instanceId IS NOT NULL GROUP BY h.instanceId")
    List<Object[]> podSummariesSince(@Param("since") LocalDateTime since);
}
