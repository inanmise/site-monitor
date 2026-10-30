package com.sitemonitor.repository;

import com.sitemonitor.model.AlarmFeedRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Alarm akışı çağrı günlüğü (2026-10-30). Yazım yalnız {@code save} (Spring Data'nın kendi işlemi). */
public interface AlarmFeedRequestRepository extends JpaRepository<AlarmFeedRequest, Long> {

    /**
     * Süzgeçli liste — en yeni önce, eşitlikte kimlikle kararlı (CLAUDE.md "Offset paging needs a unique tie-breaker").
     * {@code ip} önek eşleşmesi ({@code null} = hepsi); {@code from} dahil, {@code to} hariç (UTC ISO).
     */
    @Query("SELECT r FROM AlarmFeedRequest r WHERE (:outcome IS NULL OR r.outcome = :outcome) "
            + "AND (:ip IS NULL OR r.clientIp LIKE :ip) "
            + "AND (:from IS NULL OR r.requestedAt >= :from) AND (:to IS NULL OR r.requestedAt < :to) "
            + "ORDER BY r.requestedAt DESC, r.id DESC")
    Page<AlarmFeedRequest> search(@Param("outcome") String outcome, @Param("ip") String ipPrefixLike,
                                  @Param("from") String from, @Param("to") String to, Pageable pageable);

    /** Son 24 saat özeti: [outcome, adet, ortalama süre ms]. */
    @Query("SELECT r.outcome, COUNT(r), AVG(r.durationMs) FROM AlarmFeedRequest r WHERE r.requestedAt >= :since GROUP BY r.outcome")
    List<Object[]> outcomeStatsSince(@Param("since") String since);

    @Query("SELECT COUNT(DISTINCT r.clientIp) FROM AlarmFeedRequest r WHERE r.requestedAt >= :since")
    long distinctIpsSince(@Param("since") String since);

    /** En son çağrı ({@code requested_at} NOT NULL — sıralamada NULL tuzağı yok). */
    AlarmFeedRequest findTopByOrderByRequestedAtDescIdDesc();
}
