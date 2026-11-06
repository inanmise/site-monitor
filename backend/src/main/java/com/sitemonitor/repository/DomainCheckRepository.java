package com.sitemonitor.repository;

import com.sitemonitor.model.DomainCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DomainCheckRepository extends JpaRepository<DomainCheck, Long> {

    // ── Kontrol Geçmişi v2: server-side sayfalı aralık + hata filtresi + yoğunluk histogramı ──
    // Hata = status <> 'OK' (WARNING/CRITICAL/UNKNOWN/ERROR). Eski down sayacı kesik 500'lük
    // listeden hesaplanıyordu (bug); artık DB count.
    default Page<DomainCheck> findByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p) {
        return historyPage(monitorId, from, to, MonitorIndexSort.leading(p, "monitorId"));
    }
    @Query(value = "SELECT c FROM DomainCheck c WHERE c.monitorId IN (:id, :id) "
         + "AND c.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_C,
           countQuery = "SELECT COUNT(c.monitorId) FROM DomainCheck c WHERE c.monitorId = :id "
         + "AND c.checkedAt >= :from AND c.checkedAt <= :to")
    Page<DomainCheck> historyPage(@Param("id") Long id, @Param("from") String from, @Param("to") String to, Pageable p);
    default Page<DomainCheck> findByMonitorIdAndStatusNotAndCheckedAtBetween(Long monitorId, String status, String from, String to, Pageable p) {
        return historyStatusNotPage(monitorId, status, from, to, MonitorIndexSort.leading(p, "monitorId"));
    }
    @Query(value = "SELECT c FROM DomainCheck c WHERE c.monitorId IN (:id, :id) AND c.status <> :status "
         + "AND c.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_C,
           countQuery = "SELECT COUNT(c.monitorId) FROM DomainCheck c WHERE c.monitorId = :id AND c.status <> :status "
         + "AND c.checkedAt >= :from AND c.checkedAt <= :to")
    Page<DomainCheck> historyStatusNotPage(@Param("id") Long id, @Param("status") String status, @Param("from") String from, @Param("to") String to, Pageable p);
    @Query("SELECT COUNT(c.monitorId) FROM DomainCheck c WHERE c.monitorId = :id AND c.checkedAt >= :from AND c.checkedAt <= :to")
    long countByMonitorIdAndCheckedAtBetween(@Param("id") Long monitorId, @Param("from") String from, @Param("to") String to);
    @Query("SELECT COUNT(c.monitorId) FROM DomainCheck c WHERE c.monitorId = :id AND c.status <> :status AND c.checkedAt >= :from AND c.checkedAt <= :to")
    long countByMonitorIdAndStatusNotAndCheckedAtBetween(@Param("id") Long monitorId, @Param("status") String status, @Param("from") String from, @Param("to") String to);

    /** Yoğunluk şeridi: [bucketKey, toplam, hata] — substr prefix kovası (dakika 16 / saat 13 / gün 10).
     *  NATIVE + TÜRETİLMİŞ TABLO: JPQL'de :len SELECT/GROUP BY'da AYRI placeholder'lara bağlanıyor,
     *  Postgres ifade eşitliğini kanıtlayamayıp 42803 atıyordu (2026-08 test ortamı). Alt sorguda
     *  parametre TEK kez geçer; dış GROUP BY gerçek bir kolona (t.bucket) bakar → her motorda geçerli. */
    @Query(value = "SELECT t.bucket, COUNT(*), SUM(t.fail) FROM ("
         + "  SELECT substr(c.checked_at,1,:len) AS bucket, "
         + "         CASE WHEN c.status <> 'OK' THEN 1 ELSE 0 END AS fail "
         + "    FROM domain_checks c WHERE c.monitor_id = :id "
         + "     AND c.checked_at >= :from AND c.checked_at <= :to"
         + ") t GROUP BY t.bucket ORDER BY t.bucket", nativeQuery = true)
    List<Object[]> historyHistogram(@Param("id") Long id, @Param("from") String from,
                                    @Param("to") String to, @Param("len") int len);
    List<DomainCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    @Query("SELECT c FROM DomainCheck c WHERE c.monitorId IN (:id, :id) ORDER BY c.monitorId DESC, c.checkedAt DESC LIMIT 1")
    Optional<DomainCheck> findTopByMonitorIdOrderByCheckedAtDesc(@Param("id") Long monitorId);

    /** Son BAŞARILI kontrol (kaynak NONE değil) — değişiklik tespiti bunun registrar/NS/status'una karşı çalışır. */
    @Query("SELECT d FROM DomainCheck d WHERE d.monitorId IN (:id, :id) AND d.source <> :source "
         + "ORDER BY d.monitorId DESC, d.checkedAt DESC LIMIT 1")
    Optional<DomainCheck> findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(@Param("id") Long monitorId, @Param("source") String source);

    /**
     * Zamanlanmış DOMAINMON_CHANGED tespitinin TABANI (2026-09-29): veri taşıyan ({@code source <> 'NONE'}) ve ELLE
     * OLMAYAN en yeni satır — DNS'teki {@code DnsRecordRepository.findLatestScheduledSuccessful} eşleniği.
     */
    @Query("SELECT d FROM DomainCheck d WHERE d.monitorId IN (:monitorId, :monitorId) AND d.source <> 'NONE' "
            + "AND (d.manual IS NULL OR d.manual = false) ORDER BY d.monitorId DESC, d.checkedAt DESC LIMIT 1")
    Optional<DomainCheck> findLatestScheduledWithData(@Param("monitorId") Long monitorId);

    /** History detay listesi — SQL-LIMIT'li: en yeni :limit satır. */
    @Query("SELECT r FROM DomainCheck r WHERE r.monitorId IN (:id, :id) ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<DomainCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM DomainCheck r WHERE r.monitorId IN (:id, :id) AND r.checkedAt >= :since ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<DomainCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol — tek toplu sorgu (N+1 önleme). */
    // LATERAL join: monitör başına tek index-seek (full-scan yerine).
    @Query(value = "SELECT c.* FROM domain_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM domain_checks r WHERE r.monitor_id = m.id AND r.checked_at = "
         + "(SELECT MAX(x.checked_at) FROM domain_checks x WHERE x.monitor_id = m.id) ORDER BY r.id DESC LIMIT 1) c",
           nativeQuery = true)
    List<DomainCheck> findLatestPerMonitor();

    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI] — ids ∩ [from,to]; başarı = source<>'NONE' (sorgu başarılı). */
    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.source <> 'NONE' THEN 1L ELSE 0L END) "
         + "FROM DomainCheck r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);

    /** Saklama seffafligi: bu izlemenin elde TUTULAN en eski ve en yeni kaydi ([min, max]).
     *  Kullanici Kontrol Gecmisi'nde "veri su tarihten itibaren tutuluyor" bilgisini gorur.
     *  Zaman kolonu indexli oldugundan MIN/MAX index-seek'tir (tablo taramasi yok). */
    @Query("SELECT MIN(c.checkedAt), MAX(c.checkedAt) FROM DomainCheck c WHERE c.monitorId = :id")
    List<Object[]> historyBounds(@Param("id") Long id);
}
