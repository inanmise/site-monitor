package com.sitemonitor.repository;

import com.sitemonitor.model.PortCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PortCheckRepository extends JpaRepository<PortCheck, Long> {

    // ── Kontrol Geçmişi v2: server-side sayfalı aralık + hata filtresi + yoğunluk histogramı ──
    // Performans 2026-11-06: tek izleme sorguları "monitorId IN (:id, :id) + ORDER BY monitorId, checkedAt" biçiminde
    // (MonitorIndexSort) — eşitlikte PostgreSQL tekil checked_at indeksini seçip monitor_id'yi süzgeçle eliyordu.
    default Page<PortCheck> findByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p) {
        return historyPage(monitorId, from, to, MonitorIndexSort.leading(p, "monitorId"));
    }
    default Page<PortCheck> findByMonitorIdAndOpenFalseAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p) {
        return historyFailedPage(monitorId, from, to, MonitorIndexSort.leading(p, "monitorId"));
    }
    @Query(value = "SELECT c FROM PortCheck c WHERE c.monitorId IN (:id, :id) AND c.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_C,
           countQuery = "SELECT COUNT(c.monitorId) FROM PortCheck c WHERE c.monitorId = :id AND c.checkedAt >= :from AND c.checkedAt <= :to")
    Page<PortCheck> historyPage(@Param("id") Long id, @Param("from") String from, @Param("to") String to, Pageable p);
    @Query(value = "SELECT c FROM PortCheck c WHERE c.monitorId IN (:id, :id) AND c.open = false "
         + "AND c.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_C,
           countQuery = "SELECT COUNT(c.monitorId) FROM PortCheck c WHERE c.monitorId = :id AND c.open = false "
         + "AND c.checkedAt >= :from AND c.checkedAt <= :to")
    Page<PortCheck> historyFailedPage(@Param("id") Long id, @Param("from") String from, @Param("to") String to, Pageable p);
    @Query("SELECT COUNT(c.monitorId) FROM PortCheck c WHERE c.monitorId = :id AND c.checkedAt >= :from AND c.checkedAt <= :to")
    long countByMonitorIdAndCheckedAtBetween(@Param("id") Long monitorId, @Param("from") String from, @Param("to") String to);
    @Query("SELECT COUNT(c.monitorId) FROM PortCheck c WHERE c.monitorId = :id AND c.open = false AND c.checkedAt >= :from AND c.checkedAt <= :to")
    long countByMonitorIdAndOpenFalseAndCheckedAtBetween(@Param("id") Long monitorId, @Param("from") String from, @Param("to") String to);

    /** Yoğunluk şeridi: [bucketKey, toplam, hata] — substr prefix kovası (dakika 16 / saat 13 / gün 10).
     *  NATIVE + TÜRETİLMİŞ TABLO: JPQL'de :len SELECT/GROUP BY'da AYRI placeholder'lara bağlanıyor,
     *  Postgres ifade eşitliğini kanıtlayamayıp 42803 atıyordu (2026-08 test ortamı). Alt sorguda
     *  parametre TEK kez geçer; dış GROUP BY gerçek bir kolona (t.bucket) bakar → her motorda geçerli. */
    @Query(value = "SELECT t.bucket, COUNT(*), SUM(t.fail) FROM ("
         + "  SELECT substr(c.checked_at,1,:len) AS bucket, "
         + "         CASE WHEN c.open = false THEN 1 ELSE 0 END AS fail "
         + "    FROM port_checks c WHERE c.monitor_id = :id "
         + "     AND c.checked_at >= :from AND c.checked_at <= :to"
         + ") t GROUP BY t.bucket ORDER BY t.bucket", nativeQuery = true)
    List<Object[]> historyHistogram(@Param("id") Long id, @Param("from") String from,
                                    @Param("to") String to, @Param("len") int len);
    List<PortCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    @Query("SELECT pc FROM PortCheck pc WHERE pc.monitorId IN (:id, :id) ORDER BY pc.monitorId DESC, pc.checkedAt DESC LIMIT 1")
    Optional<PortCheck> findTopByMonitorIdOrderByCheckedAtDesc(@Param("id") Long monitorId);

    /** History detay listesi — SQL-LIMIT'li: tüm geçmişi JVM'e çekmeden en yeni :limit satır. */
    @Query("SELECT pc FROM PortCheck pc WHERE pc.monitorId IN (:id, :id) ORDER BY pc.monitorId DESC, pc.checkedAt DESC LIMIT :limit")
    List<PortCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT pc FROM PortCheck pc WHERE pc.monitorId IN (:id, :id) AND pc.checkedAt >= :since "
         + "ORDER BY pc.monitorId DESC, pc.checkedAt DESC LIMIT :limit")
    List<PortCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol. Eski "id IN (SELECT MAX(id) GROUP BY monitor_id)" ve
     *  DISTINCT ON TÜM tabloyu tarıyordu (898k satırda ~3.5 sn). LATERAL join küçük monitör tablosunu
     *  gezip her monitör için (monitor_id, checked_at) index'iyle tek backward-seek yapar → ~0.2 ms.
     *  <p>Performans 2026-11-06: {@code ORDER BY checked_at DESC LIMIT 1} biçiminde planlayıcı tekil {@code checked_at}
     *  indexini geri tarayıp {@code monitor_id} süzgeciyle satır eliyordu (yerel 2,3 M satırda 1,9 sn — son kontrolü eski,
     *  duraklatılmış izlemelerde yüz binlerce satır). {@code MAX(checked_at)} bileşik indexte index-only taramayla bulunur,
     *  satır iki sütunlu eşitlikle alınır (≈ 1 ms); aynı saniyede iki satır → büyük id. Aynı biçim bütün türlerde
     *  ({@code LatestPerMonitorQueryGateTest}). */
    @Query(value = "SELECT c.* FROM port_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM port_checks pc WHERE pc.monitor_id = m.id AND pc.checked_at = "
         + "(SELECT MAX(x.checked_at) FROM port_checks x WHERE x.monitor_id = m.id) ORDER BY pc.id DESC LIMIT 1) c",
           nativeQuery = true)
    List<PortCheck> findLatestPerMonitor();
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(Long monitorId, String since);

    /** Gün-aralığı filtresi (detay ekranı) — en yeni önce + özet sayıları. */
    List<PortCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOpenFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Yanıt-süresi grafiği için ham veri: [checked_at, response_ms (null olabilir), open] — aralık + cap
     *  (en yeni :limit). Null filtresi YOK: down/error kovaları için tüm kayıtlar gelir, istatistik
     *  Java'da null'sız hesaplanır (keyword/ping responseSeriesRaw ile aynı desen). */
    @Query("SELECT pc.checkedAt, pc.responseMs, pc.open FROM PortCheck pc "
         + "WHERE pc.monitorId IN (:id, :id) AND pc.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_PC + " "
         + "ORDER BY pc.monitorId DESC, pc.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI] — ids ∩ [from,to]; başarı = open=true. */
    @Query("SELECT pc.monitorId, COUNT(pc), SUM(CASE WHEN pc.open = true THEN 1L ELSE 0L END) "
         + "FROM PortCheck pc WHERE pc.monitorId IN :ids AND pc.checkedAt >= :from AND pc.checkedAt <= :to "
         + "GROUP BY pc.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);

    /** Saklama seffafligi: bu izlemenin elde TUTULAN en eski ve en yeni kaydi ([min, max]).
     *  Kullanici Kontrol Gecmisi'nde "veri su tarihten itibaren tutuluyor" bilgisini gorur.
     *  Zaman kolonu indexli oldugundan MIN/MAX index-seek'tir (tablo taramasi yok). */
    @Query("SELECT MIN(c.checkedAt), MAX(c.checkedAt) FROM PortCheck c WHERE c.monitorId = :id")
    List<Object[]> historyBounds(@Param("id") Long id);
}
