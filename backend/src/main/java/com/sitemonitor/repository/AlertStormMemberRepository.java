package com.sitemonitor.repository;

import com.sitemonitor.model.AlertStormMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Fırtına üyeliği (2026-09-30) — bkz. {@link AlertStormMember}. Yazan türetilmiş metotlar {@code @Transactional} taşır. */
public interface AlertStormMemberRepository extends JpaRepository<AlertStormMember, Long> {

    List<AlertStormMember> findByStormIdOrderByJoinedAtAsc(Long stormId);

    List<AlertStormMember> findByAlertEventIdOrderByJoinedAtDesc(Long alertEventId);

    boolean existsByStormIdAndAlertEventId(Long stormId, Long alertEventId);

    /** Fırtına başına üye / kurtulan sayısı (geçmiş listesi tek sorguda). Satır: [stormId, total, recovered]. */
    @Query("""
        SELECT m.stormId, COUNT(m), SUM(CASE WHEN m.leaveKind = 'RECOVERED' THEN 1 ELSE 0 END)
        FROM AlertStormMember m WHERE m.stormId IN :stormIds GROUP BY m.stormId
        """)
    List<Object[]> countByStorms(@Param("stormIds") Collection<Long> stormIds);

    // Yazımlar (katılım / duyuru / ayrılış) StormService'te JDBC toplu cümlelerle yapılır (2026-10-01, performans):
    // StormService.SQL_MEMBER_INSERT / SQL_MEMBER_ANNOUNCED_PREFIX / SQL_MEMBER_LEFT — StormObservabilityQueriesTest H2'de sınar.
}
