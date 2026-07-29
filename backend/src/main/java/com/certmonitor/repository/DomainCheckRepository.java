package com.certmonitor.repository;

import com.certmonitor.model.DomainCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DomainCheckRepository extends JpaRepository<DomainCheck, Long> {
    List<DomainCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<DomainCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** Son BAŞARILI kontrol (kaynak NONE değil) — değişiklik tespiti bunun registrar/NS/status'una karşı çalışır. */
    Optional<DomainCheck> findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(Long monitorId, String source);

    /** History detay listesi — SQL-LIMIT'li: en yeni :limit satır. */
    @Query("SELECT r FROM DomainCheck r WHERE r.monitorId = :id ORDER BY r.checkedAt DESC LIMIT :limit")
    List<DomainCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM DomainCheck r WHERE r.monitorId = :id AND r.checkedAt >= :since ORDER BY r.checkedAt DESC LIMIT :limit")
    List<DomainCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol — tek toplu sorgu (N+1 önleme). */
    // LATERAL join: monitör başına tek index-seek (full-scan yerine).
    @Query(value = "SELECT c.* FROM domain_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM domain_checks r WHERE r.monitor_id = m.id ORDER BY r.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<DomainCheck> findLatestPerMonitor();

    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI] — ids ∩ [from,to]; başarı = source<>'NONE' (sorgu başarılı). */
    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.source <> 'NONE' THEN 1L ELSE 0L END) "
         + "FROM DomainCheck r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);
}
