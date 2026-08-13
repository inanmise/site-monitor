package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedScriptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScriptedScriptVersionRepository extends JpaRepository<ScriptedScriptVersion, Long> {

    /** Sürüm listesi — en yeni üstte. */
    List<ScriptedScriptVersion> findByMonitorIdOrderBySequenceNoDesc(Long monitorId);

    /** Bir sonraki sıra numarası için; hiç sürüm yoksa boş döner. */
    @Query("SELECT MAX(v.sequenceNo) FROM ScriptedScriptVersion v WHERE v.monitorId = :monitorId")
    Optional<Integer> findMaxSequenceNo(@Param("monitorId") Long monitorId);

    /** En güncel sürüm (bir sonraki numarayı ve etiketi türetmek için). */
    Optional<ScriptedScriptVersion> findTopByMonitorIdOrderBySequenceNoDesc(Long monitorId);

    long countByMonitorId(Long monitorId);
}
