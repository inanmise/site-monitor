package com.sitemonitor.repository;

import com.sitemonitor.model.K6CompatScan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * k6 uyumluluk taramaları (2026-10-29). Durum geçişleri koşullu UPDATE'tir — koşan pod'un ilerleme yazımı bir iptal
 * isteğini (CANCELLING) ezemez, iki pod aynı taramayı iki kez bitiremez.
 */
public interface K6CompatScanRepository extends JpaRepository<K6CompatScan, Long> {

    List<K6CompatScan> findTop20ByOrderByIdDesc();

    Optional<K6CompatScan> findFirstByStateInOrderByIdDesc(Collection<String> states);

    @Query("SELECT s.state FROM K6CompatScan s WHERE s.id = :id")
    Optional<String> stateOf(@Param("id") Long id);

    /** İptal isteği: yalnız RUNNING iken. Döner: değişen satır sayısı (0 = zaten bitmiş / iptal edilmiş). */
    @Modifying
    @Transactional
    @Query("UPDATE K6CompatScan s SET s.state = 'CANCELLING' WHERE s.id = :id AND s.state = 'RUNNING'")
    int requestCancel(@Param("id") Long id);

    /** İlerleme + sinyal — durum sütununa DOKUNMAZ (iptal isteği ezilmez). */
    @Modifying
    @Transactional
    @Query("UPDATE K6CompatScan s SET s.heartbeatAt = :at, s.total = :total, s.doneCount = :done, "
            + "s.regressions = :regressions, s.improvements = :improvements, s.unchanged = :unchanged, "
            + "s.noBaseline = :noBaseline, s.changedCount = :changed, s.notRun = :notRun, s.newFail = :newFail "
            + "WHERE s.id = :id")
    int progress(@Param("id") Long id, @Param("at") String at, @Param("total") int total, @Param("done") int done,
                 @Param("regressions") int regressions, @Param("improvements") int improvements,
                 @Param("unchanged") int unchanged, @Param("noBaseline") int noBaseline,
                 @Param("changed") int changed, @Param("notRun") int notRun, @Param("newFail") int newFail);

    /** Bitiş — yalnız açık (RUNNING / CANCELLING) tarama. Döner: değişen satır (0 = başka yol bitirmiş). */
    @Modifying
    @Transactional
    @Query("UPDATE K6CompatScan s SET s.state = :state, s.finishedAt = :at, s.heartbeatAt = :at, s.failure = :failure "
            + "WHERE s.id = :id AND s.state IN ('RUNNING', 'CANCELLING')")
    int finish(@Param("id") Long id, @Param("state") String state, @Param("at") String at,
               @Param("failure") String failure);
}
