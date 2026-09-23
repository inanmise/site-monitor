package com.sitemonitor.repository;

import com.sitemonitor.model.TodayPanelSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface TodayPanelSnapshotRepository extends JpaRepository<TodayPanelSnapshot, Long> {

    /** {@code at} anına kadarki EN YENİ görüntü — "dün bu saatte" için {@code at = şimdi − 24 sa}. */
    Optional<TodayPanelSnapshot> findFirstByTakenAtLessThanEqualOrderByTakenAtDesc(String at);

    /** Son kayıt — saatlik iş yeniden başlatma sonrası aynı saate ikinci satır yazmasın. */
    Optional<TodayPanelSnapshot> findFirstByOrderByTakenAtDesc();

    /**
     * Sınır: eskileri sil. {@code @Transactional} ŞART — {@code open-in-view=false}, Spring Data
     * {@code @Modifying} sorgusuna tx sarmaz (bkz. {@code RepositoryWriteTransactionGuardTest}).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM TodayPanelSnapshot s WHERE s.takenAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") String cutoff);
}
