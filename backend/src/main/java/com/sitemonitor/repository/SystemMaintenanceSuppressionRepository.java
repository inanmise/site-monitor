package com.sitemonitor.repository;

import com.sitemonitor.model.SystemMaintenanceSuppression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Sistem bakımında susturulan alarm bildirimleri ({@code system_maintenance_suppressions}, 2026-10-02) — bakım bitişindeki
 * telafinin listesi. Satır (bakım, alarm) başına tektir (UNIQUE); tekrarlar {@link #touch} ile sayılır.
 */
public interface SystemMaintenanceSuppressionRepository extends JpaRepository<SystemMaintenanceSuppression, Long> {

    Optional<SystemMaintenanceSuppression> findByWindowIdAndAlertEventId(Long windowId, Long alertEventId);

    /** Telafi adayları: açılışı susturulmuş, henüz karara bağlanmamış satırlar. */
    List<SystemMaintenanceSuppression> findByWindowIdAndCaughtUpAtIsNull(Long windowId);

    long countByWindowId(Long windowId);

    /** Bir bakımın tüm susturma satırları (ayrıntı penceresinin telafi dökümü). */
    List<SystemMaintenanceSuppression> findByWindowId(Long windowId);

    /** Var olan satırın tekrar sayacı + son an (+ açılış bayrağı yükseltmesi: bir kez açılış olan hep açılıştır). */
    @Modifying
    @Transactional
    @Query("UPDATE SystemMaintenanceSuppression s SET s.suppressedCount = COALESCE(s.suppressedCount, 0) + 1, "
         + "s.lastAt = :at, s.opening = CASE WHEN :opening = true THEN true ELSE s.opening END "
         + "WHERE s.windowId = :windowId AND s.alertEventId = :alertEventId")
    int touch(@Param("windowId") Long windowId, @Param("alertEventId") Long alertEventId,
              @Param("at") String at, @Param("opening") boolean opening);

    /** Telafi kararı (toplu). */
    @Modifying
    @Transactional
    @Query("UPDATE SystemMaintenanceSuppression s SET s.outcome = :outcome, s.caughtUpAt = :at "
         + "WHERE s.windowId = :windowId AND s.alertEventId IN :ids AND s.caughtUpAt IS NULL")
    int markOutcome(@Param("windowId") Long windowId, @Param("ids") Collection<Long> ids,
                    @Param("outcome") String outcome, @Param("at") String at);
}
