package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScriptedCheckRepository extends JpaRepository<ScriptedCheck, Long> {

    // ── Kontrol Geçmişi v2: server-side sayfalı aralık + hata filtresi + yoğunluk histogramı ──
    default Page<ScriptedCheck> findByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p) {
        return historyPage(monitorId, from, to, MonitorIndexSort.leading(p, "monitorId"));
    }
    @Query(value = "SELECT c FROM ScriptedCheck c WHERE c.monitorId IN (:id, :id) "
         + "AND c.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_C,
           countQuery = "SELECT COUNT(c.monitorId) FROM ScriptedCheck c WHERE c.monitorId = :id "
         + "AND c.checkedAt >= :from AND c.checkedAt <= :to")
    Page<ScriptedCheck> historyPage(@Param("id") Long id, @Param("from") String from, @Param("to") String to, Pageable p);
    default Page<ScriptedCheck> findByMonitorIdAndOkFalseAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p) {
        return historyFailedPage(monitorId, from, to, MonitorIndexSort.leading(p, "monitorId"));
    }
    @Query(value = "SELECT c FROM ScriptedCheck c WHERE c.monitorId IN (:id, :id) AND c.ok = false "
         + "AND c.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_C,
           countQuery = "SELECT COUNT(c.monitorId) FROM ScriptedCheck c WHERE c.monitorId = :id AND c.ok = false "
         + "AND c.checkedAt >= :from AND c.checkedAt <= :to")
    Page<ScriptedCheck> historyFailedPage(@Param("id") Long id, @Param("from") String from, @Param("to") String to, Pageable p);
    @Query("SELECT COUNT(c.monitorId) FROM ScriptedCheck c WHERE c.monitorId = :id AND c.checkedAt >= :from AND c.checkedAt <= :to")
    long countByMonitorIdAndCheckedAtBetween(@Param("id") Long monitorId, @Param("from") String from, @Param("to") String to);
    @Query("SELECT COUNT(c.monitorId) FROM ScriptedCheck c WHERE c.monitorId = :id AND c.ok = false AND c.checkedAt >= :from AND c.checkedAt <= :to")
    long countByMonitorIdAndOkFalseAndCheckedAtBetween(@Param("id") Long monitorId, @Param("from") String from, @Param("to") String to);

    /** Yoğunluk şeridi: [bucketKey, toplam, hata] — substr prefix kovası (dakika 16 / saat 13 / gün 10).
     *  NATIVE + TÜRETİLMİŞ TABLO: JPQL'de :len SELECT/GROUP BY'da AYRI placeholder'lara bağlanıyor,
     *  Postgres ifade eşitliğini kanıtlayamayıp 42803 atıyordu (2026-08 test ortamı). Alt sorguda
     *  parametre TEK kez geçer; dış GROUP BY gerçek bir kolona (t.bucket) bakar → her motorda geçerli. */
    @Query(value = "SELECT t.bucket, COUNT(*), SUM(t.fail) FROM ("
         + "  SELECT substr(c.checked_at,1,:len) AS bucket, "
         + "         CASE WHEN c.ok = false THEN 1 ELSE 0 END AS fail "
         + "    FROM scripted_checks c WHERE c.monitor_id = :id "
         + "     AND c.checked_at >= :from AND c.checked_at <= :to"
         + ") t GROUP BY t.bucket ORDER BY t.bucket", nativeQuery = true)
    List<Object[]> historyHistogram(@Param("id") Long id, @Param("from") String from,
                                    @Param("to") String to, @Param("len") int len);
    /**
     * En az bir kez BAŞARILI olmuş monitörlerin id'leri — "hiç yeşile dönmedi" rozeti için.
     * Tek toplu sorgu: liste endpoint'i monitör başına ayrı sorgu açmasın.
     * <p>Performans 2026-11-06 (PF2 madde 9): {@code SELECT DISTINCT monitor_id … WHERE ok = true} bütün başarılı koşumları
     * (kısmi {@code idx_sc_success_monitor} dizininin tamamını) okuyordu — liste her yoklamada. İzleme başına {@code EXISTS}
     * kısmi dizinde tek arama yapar. Sonuç: VAR OLAN izlemelerden en az bir başarılısı olanlar (izleme silinince kontrolleri de
     * silinir — liste yalnız var olan izlemeleri sorar).
     */
    @Query("SELECT m.id FROM ScriptedMonitor m WHERE EXISTS (SELECT 1 FROM ScriptedCheck r WHERE r.monitorId = m.id AND r.ok = true)")
    List<Long> monitorIdsWithSuccess();

    List<ScriptedCheck> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    @Query("SELECT c FROM ScriptedCheck c WHERE c.monitorId IN (:id, :id) ORDER BY c.monitorId DESC, c.checkedAt DESC LIMIT 1")
    Optional<ScriptedCheck> findTopByMonitorIdOrderByCheckedAtDesc(@Param("id") Long monitorId);

    @Query("SELECT r FROM ScriptedCheck r WHERE r.monitorId IN (:id, :id) ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<ScriptedCheck> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    /**
     * Yalnız ZAMANLANMIŞ koşumlar (manual NULL/false), en yeni önce — anomali guard'ının zaman aşımı serisi (2026-09-29).
     * Teyit / kurtarma zincirinin yeniden kontrolleri ({@code recheck = true}, 2026-10-31 k6r inceleme #1) de DIŞARIDA: alarm
     * zincirin sonunda açılır; zincir adımları seriye girerse alarm açılmadan seri dolup izleme kapanırdı.
     */
    @Query("SELECT r FROM ScriptedCheck r WHERE r.monitorId IN (:id, :id) AND (r.manual IS NULL OR r.manual = false) "
            + "AND (r.recheck IS NULL OR r.recheck = false) ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<ScriptedCheck> findRecentScheduledByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM ScriptedCheck r WHERE r.monitorId IN (:id, :id) AND r.checkedAt >= :since ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<ScriptedCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kontrol — LATERAL (monitör başına tek index-seek; N+1 önleme). */
    @Query(value = "SELECT c.* FROM scripted_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM scripted_checks r WHERE r.monitor_id = m.id AND r.checked_at = "
         + "(SELECT MAX(x.checked_at) FROM scripted_checks x WHERE x.monitor_id = m.id) ORDER BY r.id DESC LIMIT 1) c",
           nativeQuery = true)
    List<ScriptedCheck> findLatestPerMonitor();

    long countByMonitorIdAndCheckedAtGreaterThanEqual(Long monitorId, String since);
    long countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(Long monitorId, String since);

    /** Süre trend serisi: [checked_at, duration_ms, ok] — aralık + cap. */
    @Query("SELECT r.checkedAt, r.durationMs, r.ok FROM ScriptedCheck r "
         + "WHERE r.monitorId IN (:id, :id) AND r.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_R + " "
         + "ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);

    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.ok = true THEN 1L ELSE 0L END), AVG(r.durationMs) "
         + "FROM ScriptedCheck r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);

    /** Saklama seffafligi: bu izlemenin elde TUTULAN en eski ve en yeni kaydi ([min, max]).
     *  Kullanici Kontrol Gecmisi'nde "veri su tarihten itibaren tutuluyor" bilgisini gorur.
     *  Zaman kolonu indexli oldugundan MIN/MAX index-seek'tir (tablo taramasi yok). */
    @Query("SELECT MIN(c.checkedAt), MAX(c.checkedAt) FROM ScriptedCheck c WHERE c.monitorId = :id")
    List<Object[]> historyBounds(@Param("id") Long id);

    /**
     * Sürüm başına koşum sonucu — Sürümler sekmesindeki "8 koşum / 8 hata" rozeti.
     *
     * <p>TEK toplu sorgu: sürüm listesi yüzlerce satır olabilir, satır başına sayım N+1 olurdu.
     * {@code monitor_id} indeksli ve retention hacmi sınırlıyor.
     *
     * <p>{@code script_version} NULL olan eski kayıtlar DIŞARIDA: onlar sürümleme öncesine ait,
     * hiçbir sürüm satırına karşılık gelmiyorlar ve bir sürümün karnesine yazılmaları yanlış olur.
     */
    @Query("SELECT c.scriptVersion, COUNT(c), SUM(CASE WHEN c.ok = false THEN 1L ELSE 0L END) "
         + "FROM ScriptedCheck c WHERE c.monitorId = :id AND c.scriptVersion IS NOT NULL "
         + "GROUP BY c.scriptVersion")
    List<Object[]> runStatsByScriptVersion(@Param("id") Long id);

    /**
     * Adım trendi (2026-10-31, k6 inceleme D1): [checked_at, steps_json] — yalnız ZAMANLANMIŞ koşumlar (elle koşum ve
     * teyit / kurtarma yeniden kontrolü trendi bozmasın), adım verisi olanlar, yeniden eskiye, tavanlı (çağıran tavan + 1 ister → "kırpıldı" bilgisi).
     */
    @Query("SELECT r.checkedAt, r.stepsJson FROM ScriptedCheck r "
         + "WHERE r.monitorId IN (:id, :id) AND r.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_R + " "
         + "AND (r.manual IS NULL OR r.manual = false) AND (r.recheck IS NULL OR r.recheck = false) "
         + "AND r.stepsJson IS NOT NULL ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<Object[]> stepSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                 @Param("to") String to, @Param("limit") int limit);
}
