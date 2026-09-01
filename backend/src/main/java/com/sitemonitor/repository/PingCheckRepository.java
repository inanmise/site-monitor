package com.sitemonitor.repository;

import com.sitemonitor.model.PingCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PingCheckRepository extends JpaRepository<PingCheck, Long> {

    // ── Kontrol Geçmişi v2: server-side sayfalı aralık + hata filtresi + yoğunluk histogramı ──
    Page<PingCheck> findByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p);
    Page<PingCheck> findByMonitorIdAndUpFalseAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p);
    long countByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to);
    long countByMonitorIdAndUpFalseAndCheckedAtBetween(Long monitorId, String from, String to);

    /** Yoğunluk şeridi: [bucketKey, toplam, hata] — ISO string sözlüksel=kronolojik olduğundan
     *  substr prefix'i dakika(16)/saat(13)/gün(10) kovası üretir; satırlar JVM'e taşınmaz.
     *  NATIVE + TÜRETİLMİŞ TABLO: JPQL'de :len SELECT/GROUP BY'da AYRI placeholder'lara bağlanıyor,
     *  Postgres ifade eşitliğini kanıtlayamayıp 42803 atıyordu (2026-08 test ortamı). Alt sorguda
     *  parametre TEK kez geçer; dış GROUP BY gerçek bir kolona (t.bucket) bakar → her motorda geçerli. */
    @Query(value = "SELECT t.bucket, COUNT(*), SUM(t.fail) FROM ("
         + "  SELECT substr(c.checked_at,1,:len) AS bucket, "
         + "         CASE WHEN c.up = false THEN 1 ELSE 0 END AS fail "
         + "    FROM ping_checks c WHERE c.monitor_id = :id "
         + "     AND c.checked_at >= :from AND c.checked_at <= :to"
         + ") t GROUP BY t.bucket ORDER BY t.bucket", nativeQuery = true)
    List<Object[]> historyHistogram(@Param("id") Long id, @Param("from") String from,
                                    @Param("to") String to, @Param("len") int len);
    List<PingCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<PingCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** History detay listesi — SQL-LIMIT'li: tüm geçmişi JVM'e çekmeden en yeni :limit satır. */
    @Query("SELECT c FROM PingCheck c WHERE c.monitorId = :id ORDER BY c.checkedAt DESC LIMIT :limit")
    List<PingCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT c FROM PingCheck c WHERE c.monitorId = :id AND c.checkedAt >= :since ORDER BY c.checkedAt DESC LIMIT :limit")
    List<PingCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol — tek toplu sorgu (N+1 önleme). */
    // LATERAL join: küçük monitör tablosu × monitör başına tek index-seek (eski MAX(id)+GROUP BY /
    // DISTINCT ON full-scan yerine — milyonlarca satırda ~3.5sn → ~0.2ms).
    @Query(value = "SELECT c.* FROM ping_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM ping_checks pc WHERE pc.monitor_id = m.id ORDER BY pc.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<PingCheck> findLatestPerMonitor();

    List<PingCheck> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);
    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndUpFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** RTT grafiği için ham veri: [checked_at, rtt_ms (null olabilir), up, packet_loss] — aralık + cap
     *  (en yeni :limit). Null filtresi YOK: down kovaları için tüm kayıtlar gelir. */
    @Query("SELECT c.checkedAt, c.rttMs, c.up, c.packetLoss FROM PingCheck c "
         + "WHERE c.monitorId = :id AND c.checkedAt >= :from AND c.checkedAt <= :to "
         + "ORDER BY c.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI, ort_rtt_ms] — ids ∩ [from,to]; başarı = up=true. */
    @Query("SELECT c.monitorId, COUNT(c), SUM(CASE WHEN c.up = true THEN 1L ELSE 0L END), AVG(c.rttMs) "
         + "FROM PingCheck c WHERE c.monitorId IN :ids AND c.checkedAt >= :from AND c.checkedAt <= :to "
         + "GROUP BY c.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);

    /**
     * Yavaşlık taban çizgisi: [ortalama_rtt, örnek_sayısı] — pencere içindeki BAŞARILI ölçümler.
     *
     * <p>Yalnız {@code up=true} ve {@code rtt_ms} dolu satırlar sayılır: başarısız bir kontrolün
     * rtt'si yoktur, sıfır sayılsaydı taban çizgisi düşer ve kurtulan host "yavaşladı" diye
     * alarm üretirdi. Örnek sayısı da dönüyor — tek ölçümlük bir tabana göre yüzde kıyaslaması
     * gürültüyü alarma çevirir, çağıran alt sınır uygular.
     */
    @Query("SELECT AVG(c.rttMs), COUNT(c.rttMs) FROM PingCheck c WHERE c.monitorId = :id "
         + "AND c.up = true AND c.rttMs IS NOT NULL AND c.checkedAt >= :since AND c.checkedAt < :until")
    List<Object[]> slowBaseline(@Param("id") Long id, @Param("since") String since, @Param("until") String until);

    /** Saklama seffafligi: bu izlemenin elde TUTULAN en eski ve en yeni kaydi ([min, max]).
     *  Kullanici Kontrol Gecmisi'nde "veri su tarihten itibaren tutuluyor" bilgisini gorur.
     *  Zaman kolonu indexli oldugundan MIN/MAX index-seek'tir (tablo taramasi yok). */
    @Query("SELECT MIN(c.checkedAt), MAX(c.checkedAt) FROM PingCheck c WHERE c.monitorId = :id")
    List<Object[]> historyBounds(@Param("id") Long id);
}
