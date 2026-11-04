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

    /**
     * Değişim kuralının tabanları (2026-10-31 inceleme Y1): son İKİ zamanlanmış ve sorgusu çalışmış satır, yeniden yeniye.
     * [0] = zamanlanmış çalıştırmanın tabanı; [1] = son zamanlanmış çalıştırmanın KULLANDIĞI taban — teyit / kurtarma
     * yeniden kontrolü ve elle "Şimdi kontrol et" onu okur (aksi hâlde ihlal eden satırın kendisiyle kıyaslanıp "değişim
     * yok" görünürdü). Elle ve yeniden kontrol satırları bant dışıdır.
     */
    @Query("SELECT c FROM DbQueryCheck c WHERE c.monitorId = :id AND c.ok = true "
         + "AND (c.manual IS NULL OR c.manual = false) AND (c.recheck IS NULL OR c.recheck = false) "
         + "ORDER BY c.checkedAt DESC, c.id DESC LIMIT 2")
    List<DbQueryCheck> findRecentScheduledOk(@Param("id") Long monitorId);

    /**
     * Son ZAMANLANMIŞ satır (sorgusu çalışmış ya da çalışamamış; elle / yeniden kontrol hariç) — elle kontrolün değişim
     * tabanında "tetikleyen" (2026-10-31 ikinci tur O-2) ve CRON planı sıfırlanınca geri bakışın tabanı (D-1: o tetik
     * zaten koştuysa yeniden koşmasın).
     */
    @Query("SELECT c FROM DbQueryCheck c WHERE c.monitorId = :id "
         + "AND (c.manual IS NULL OR c.manual = false) AND (c.recheck IS NULL OR c.recheck = false) "
         + "ORDER BY c.checkedAt DESC, c.id DESC LIMIT 1")
    Optional<DbQueryCheck> findLatestScheduled(@Param("id") Long monitorId);

    /**
     * Çapadan (zinciri başlatan satır: {@code at} + {@code beforeId}) ÖNCEKİ en yeni zamanlanmış ok satırı — teyit /
     * kurtarmanın değişim tabanı zincir boyunca sabit kalsın (2026-10-31 ikinci tur O-2). Aynı saniyedeki satırlar
     * kimlikle ayrılır; kimlik bilinmiyorsa {@code beforeId} = -1 (yalnız daha önceki saniyeler).
     */
    @Query("SELECT c FROM DbQueryCheck c WHERE c.monitorId = :id AND c.ok = true "
         + "AND (c.manual IS NULL OR c.manual = false) AND (c.recheck IS NULL OR c.recheck = false) "
         + "AND (c.checkedAt < :at OR (c.checkedAt = :at AND c.id < :beforeId)) "
         + "ORDER BY c.checkedAt DESC, c.id DESC LIMIT 1")
    Optional<DbQueryCheck> findScheduledOkBefore(@Param("id") Long monitorId, @Param("at") String at,
                                                 @Param("beforeId") Long beforeId);

    /**
     * Mevsimsel değişim tabanı adayları (r177): {@code [from, to]} aralığındaki zamanlanmış ve sorgusu çalışmış satırlar (en çok
     * 50, zamana göre) — elle ve yeniden kontrol satırları bant dışıdır.
     */
    @Query("SELECT c FROM DbQueryCheck c WHERE c.monitorId = :id AND c.ok = true "
         + "AND (c.manual IS NULL OR c.manual = false) AND (c.recheck IS NULL OR c.recheck = false) "
         + "AND c.checkedAt >= :from AND c.checkedAt <= :to ORDER BY c.checkedAt ASC, c.id ASC LIMIT 50")
    List<DbQueryCheck> findScheduledOkBetween(@Param("id") Long monitorId, @Param("from") String from, @Param("to") String to);

    /**
     * Geriye dönük eşik denemesi (r177): zamanlanmış satırların hafif izdüşümü — {@code [id, checkedAt, ok, status, sqlHash,
     * ruleResultsJson]}, anahtar küme sayfalı ({@code id > :afterId}, kimlik sırası). Satır verisi yok (kontrol satırında hiç yok).
     */
    @Query("SELECT c.id, c.checkedAt, c.ok, c.status, c.sqlHash, c.ruleResultsJson FROM DbQueryCheck c "
         + "WHERE c.monitorId = :id AND c.checkedAt >= :from AND c.id > :afterId "
         + "AND (c.manual IS NULL OR c.manual = false) AND (c.recheck IS NULL OR c.recheck = false) "
         + "ORDER BY c.id ASC LIMIT :limit")
    List<Object[]> backtestPage(@Param("id") Long monitorId, @Param("from") String from, @Param("afterId") Long afterId,
                                @Param("limit") int limit);

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

    // ── dbq2 (2026-11-05): bağlantı sağlığı, değer eğilimi, alarm postası geçmişi ─────────────────────────────────────

    /**
     * Bağlantı sağlık kartı: verilen izlemelerin SON çalıştırma durumu — {@code [monitorId, status]} (hiç çalışmamışsa
     * status null). SELECT listesinde ilişkili alt sorgu: izleme başına tek dizin araması
     * ({@code idx_dbqc_monitor_checked}), H2 ve PostgreSQL'de aynı (H2 {@code LATERAL} tanımaz).
     */
    @Query(value = "SELECT m.id, (SELECT r.status FROM dbquery_checks r WHERE r.monitor_id = m.id "
         + "ORDER BY r.checked_at DESC, r.id DESC LIMIT 1) FROM dbquery_monitors m "
         + "WHERE m.id IN (:ids)", nativeQuery = true)
    List<Object[]> latestStatusFor(@Param("ids") java.util.Collection<Long> ids);

    /** Bağlantı sağlık kartı: {@code since}'ten bu yana TÜM çalıştırmalar — {@code [toplam, ok=false, ort. süre ms]}. */
    @Query("SELECT COUNT(c), SUM(CASE WHEN c.ok = false THEN 1L ELSE 0L END), AVG(c.durationMs) FROM DbQueryCheck c "
         + "WHERE c.monitorId IN :ids AND c.checkedAt >= :since")
    List<Object[]> connectionRunStats(@Param("ids") java.util.Collection<Long> ids, @Param("since") String since);

    /**
     * Bağlantının son kullanım hatasının KODU: sağlık kolonundaki zaman ({@code last_error_at}) çalıştırma satırının
     * {@code checked_at}'iyle aynıdır — dizinli eşitlik ({@code monitor_id, checked_at}), tarama yok.
     */
    @Query("SELECT c.failureReason FROM DbQueryCheck c WHERE c.monitorId IN :ids AND c.checkedAt = :at AND c.ok = false")
    List<String> failureReasonsAt(@Param("ids") java.util.Collection<Long> ids, @Param("at") String at);

    /**
     * Kart değer eğilimi: {@code [monitorId, saat kovası (yyyy-MM-ddTHH, UTC), ortalama metric_value]} — {@code from}'dan bu
     * yana ZAMANLANMIŞ (elle / yeniden kontrol değil) ve sorgusu çalışmış satırlar; görünen izlemeler için TEK gruplama.
     * Kova sütunu türetilmiş tabloda adlandırılır (H2 {@code GROUP BY 1, 2}'yi reddeder).
     */
    @Query(value = "SELECT t.monitor_id, t.bucket, AVG(t.v) FROM ("
         + "  SELECT c.monitor_id AS monitor_id, substr(c.checked_at,1,13) AS bucket, c.metric_value AS v "
         + "    FROM dbquery_checks c WHERE c.monitor_id IN (:ids) AND c.checked_at >= :from "
         + "     AND c.ok = true AND c.metric_value IS NOT NULL "
         + "     AND (c.manual IS NULL OR c.manual = false) AND (c.recheck IS NULL OR c.recheck = false)"
         + ") t GROUP BY t.monitor_id, t.bucket", nativeQuery = true)
    List<Object[]> valueTrendBuckets(@Param("ids") java.util.Collection<Long> ids, @Param("from") String from);

    /**
     * Alarm postasının "son değerler" / "kaç çalıştırmadır" bölümü: son ZAMANLANMIŞ çalıştırmalar, yeniden eskiye —
     * {@code [checkedAt, status, metricValue, ok, breachLevel, failureReason, slow]}. Satır VERİSİ yok (kontrol satırında
     * hiç yok).
     */
    @Query("SELECT c.checkedAt, c.status, c.metricValue, c.ok, c.breachLevel, c.failureReason, c.slow FROM DbQueryCheck c "
         + "WHERE c.monitorId = :id AND (c.manual IS NULL OR c.manual = false) AND (c.recheck IS NULL OR c.recheck = false) "
         + "ORDER BY c.checkedAt DESC, c.id DESC LIMIT :limit")
    List<Object[]> recentScheduledSummary(@Param("id") Long monitorId, @Param("limit") int limit);

    /** İzleme silinince çalıştırma geçmişini de temizle. */
    @Transactional
    @Modifying
    int deleteByMonitorId(Long monitorId);
}
