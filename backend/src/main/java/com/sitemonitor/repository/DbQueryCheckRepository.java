package com.sitemonitor.repository;

import com.sitemonitor.model.DbQueryCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DbQueryCheckRepository extends JpaRepository<DbQueryCheck, Long> {

    Optional<DbQueryCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** Değişim kuralının "önceki çalıştırması": son ZAMANLANMIŞ ve sorgusu çalışmış (ok) satır. */
    @Query("SELECT c FROM DbQueryCheck c WHERE c.monitorId = :id AND c.ok = true "
         + "AND (c.manual IS NULL OR c.manual = false) ORDER BY c.checkedAt DESC, c.id DESC LIMIT 1")
    Optional<DbQueryCheck> findPreviousScheduledOk(@Param("id") Long monitorId);

    // Kontrol geçmişi: sunucu tarafı sayfalı aralık + "sorunlu" süzgeci (status <> 'OK' = ihlal ya da hata)
    Page<DbQueryCheck> findByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p);
    Page<DbQueryCheck> findByMonitorIdAndStatusNotAndCheckedAtBetween(Long monitorId, String status, String from, String to, Pageable p);
    Page<DbQueryCheck> findByMonitorIdAndOkFalseAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p);
    long countByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to);
    long countByMonitorIdAndStatusNotAndCheckedAtBetween(Long monitorId, String status, String from, String to);
    long countByMonitorIdAndOkFalseAndCheckedAtBetween(Long monitorId, String from, String to);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Her izleme için en güncel çalıştırma — tek toplu sorgu (N+1 önleme). */
    @Query(value = "SELECT c.* FROM dbquery_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM dbquery_checks r WHERE r.monitor_id = m.id ORDER BY r.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<DbQueryCheck> findLatestPerMonitor();

    /** Grafik serisi: [checkedAt, durationMs, rowCount, metricValue, ok, status]. */
    @Query("SELECT r.checkedAt, r.durationMs, r.rowCount, r.metricValue, r.ok, r.status FROM DbQueryCheck r "
         + "WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "ORDER BY r.checkedAt DESC LIMIT :limit")
    List<Object[]> seriesRaw(@Param("id") Long id, @Param("from") String from,
                             @Param("to") String to, @Param("limit") int limit);

    /** Yoğunluk şeridi: [bucketKey, toplam, sorunlu] — substr önek kovası (dakika 16 / saat 13 / gün 10). */
    @Query(value = "SELECT t.bucket, COUNT(*), SUM(t.fail) FROM ("
         + "  SELECT substr(c.checked_at,1,:len) AS bucket, "
         + "         CASE WHEN c.status <> 'OK' OR c.ok = false THEN 1 ELSE 0 END AS fail "
         + "    FROM dbquery_checks c WHERE c.monitor_id = :id "
         + "     AND c.checked_at >= :from AND c.checked_at <= :to"
         + ") t GROUP BY t.bucket ORDER BY t.bucket", nativeQuery = true)
    List<Object[]> historyHistogram(@Param("id") Long id, @Param("from") String from,
                                    @Param("to") String to, @Param("len") int len);

    /** Saklama şeffaflığı: elde TUTULAN en eski ve en yeni kayıt ([min, max]). */
    @Query("SELECT MIN(c.checkedAt), MAX(c.checkedAt) FROM DbQueryCheck c WHERE c.monitorId = :id")
    List<Object[]> historyBounds(@Param("id") Long id);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI (ok), ort_süre_ms, ÖLÇÜMLÜ_satır]. */
    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.ok = true THEN 1L ELSE 0L END), AVG(r.durationMs), "
         + "SUM(CASE WHEN r.durationMs IS NOT NULL THEN 1L ELSE 0L END) "
         + "FROM DbQueryCheck r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);

    /** İzleme silinince çalıştırma geçmişini de temizle. */
    @Transactional
    @Modifying
    int deleteByMonitorId(Long monitorId);
}
