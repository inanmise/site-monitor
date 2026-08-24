package com.sitemonitor.repository;

import com.sitemonitor.model.PageSpeedCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PageSpeedCheckRepository extends JpaRepository<PageSpeedCheck, Long> {

    Optional<PageSpeedCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    // ── Kontrol geçmişi: server-side sayfalı aralık + hata filtresi ──
    Page<PageSpeedCheck> findByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p);
    Page<PageSpeedCheck> findByMonitorIdAndOkFalseAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Her monitör için en güncel ölçüm — tek toplu sorgu (N+1 önleme); monitör başına tek index-seek. */
    @Query(value = "SELECT c.* FROM pagespeed_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM pagespeed_checks r WHERE r.monitor_id = m.id ORDER BY r.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<PageSpeedCheck> findLatestPerMonitor();

    /**
     * Grafik serisi — TEK sorguda DÖRT metrik: [checkedAt, responseMs, ttfbMs, totalBytes, requestCount, ok].
     *
     * <p>Metrik seçimi sunucuda PROJEKSİYONLA yapılır, ayrı ayrı dört sorguyla değil: satırlar zaten
     * okunuyor, dört sayısal kolon taşımak ölçülebilir bir maliyet eklemiyor; buna karşılık kullanıcı
     * metrik değiştirdiğinde DB'ye yeni bir tarama gitmiyor.
     */
    @Query("SELECT r.checkedAt, r.responseMs, r.ttfbMs, r.totalBytes, r.requestCount, r.ok FROM PageSpeedCheck r "
         + "WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "ORDER BY r.checkedAt DESC LIMIT :limit")
    List<Object[]> seriesRaw(@Param("id") Long id, @Param("from") String from,
                             @Param("to") String to, @Param("limit") int limit);

    /**
     * Haftalık rapor: [monitorId, ölçüm sayısı, ortalama yükleme ms, eşik ihlali yaşayan ölçüm sayısı].
     * İhlal sayacı {@code breached_metrics} DOLU olan satırları sayar — hangi eşik olduğu burada önemsiz.
     */
    @Query("SELECT r.monitorId, COUNT(r), AVG(r.responseMs), "
         + "SUM(CASE WHEN r.breachedMetrics IS NOT NULL AND r.breachedMetrics <> '' THEN 1 ELSE 0 END) "
         + "FROM PageSpeedCheck r WHERE r.monitorId IN :ids AND r.ok = true "
         + "AND r.checkedAt >= :from AND r.checkedAt <= :to GROUP BY r.monitorId")
    List<Object[]> weeklySummary(@Param("ids") List<Long> ids, @Param("from") String from, @Param("to") String to);

    long countByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to);
    long countByMonitorIdAndOkFalseAndCheckedAtBetween(Long monitorId, String from, String to);

    /** Yoğunluk şeridi: [bucketKey, toplam, hata] — substr prefix kovası (dakika 16 / saat 13 / gün 10).
     *  NATIVE + türetilmiş tablo: JPQL'de :len iki ayrı placeholder'a bağlanıyor ve Postgres ifade
     *  eşitliğini kanıtlayamayıp 42803 atıyor (page_checks'te yaşanmış). */
    @Query(value = "SELECT t.bucket, COUNT(*), SUM(t.fail) FROM ("
         + "  SELECT substr(c.checked_at,1,:len) AS bucket, "
         + "         CASE WHEN c.ok = false THEN 1 ELSE 0 END AS fail "
         + "    FROM pagespeed_checks c WHERE c.monitor_id = :id "
         + "     AND c.checked_at >= :from AND c.checked_at <= :to"
         + ") t GROUP BY t.bucket ORDER BY t.bucket", nativeQuery = true)
    List<Object[]> historyHistogram(@Param("id") Long id, @Param("from") String from,
                                    @Param("to") String to, @Param("len") int len);

    /** Saklama şeffaflığı: elde TUTULAN en eski ve en yeni kayıt ([min, max]). */
    @Query("SELECT MIN(c.checkedAt), MAX(c.checkedAt) FROM PageSpeedCheck c WHERE c.monitorId = :id")
    List<Object[]> historyBounds(@Param("id") Long id);

    /** İzleme silinince ölçüm geçmişini de temizle. */
    @Transactional
    @Modifying
    int deleteByMonitorId(Long monitorId);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI, ort_yanıt_ms] — ids ∩ [from,to]; başarı = ok=true. */
    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.ok = true THEN 1L ELSE 0L END), AVG(r.responseMs) "
         + "FROM PageSpeedCheck r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);
}
