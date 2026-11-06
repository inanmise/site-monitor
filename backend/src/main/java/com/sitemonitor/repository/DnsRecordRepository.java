package com.sitemonitor.repository;

import com.sitemonitor.model.DnsRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DnsRecordRepository extends JpaRepository<DnsRecord, Long> {
    List<DnsRecord> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    @Query("SELECT c FROM DnsRecord c WHERE c.monitorId IN (:id, :id) ORDER BY c.monitorId DESC, c.checkedAt DESC LIMIT 1")
    Optional<DnsRecord> findTopByMonitorIdOrderByCheckedAtDesc(@Param("id") Long monitorId);

    // ── Kontrol Geçmişi v2: server-side sayfalı aralık + "Değişenler" filtresi + yoğunluk histogramı ──
    // DNS'te ok/down yok; filtre boyutu changed VEYA rotated (mevcut changedOnly semantiği).
    default org.springframework.data.domain.Page<DnsRecord> findByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p) {
        return historyPage(monitorId, from, to, MonitorIndexSort.leading(p, "monitorId"));
    }
    @Query(value = "SELECT c FROM DnsRecord c WHERE c.monitorId IN (:id, :id) "
         + "AND c.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_C,
           countQuery = "SELECT COUNT(c.monitorId) FROM DnsRecord c WHERE c.monitorId = :id "
         + "AND c.checkedAt >= :from AND c.checkedAt <= :to")
    org.springframework.data.domain.Page<DnsRecord> historyPage(@Param("id") Long id, @Param("from") String from, @Param("to") String to, Pageable p);
    default org.springframework.data.domain.Page<DnsRecord> findChangedByMonitorIdBetween(Long id, String from, String to, Pageable p) {
        return historyChangedPage(id, from, to, MonitorIndexSort.leading(p, "monitorId"));
    }
    /** "Değişenler" süzgeci — PostgreSQL'de {@code idx_dnsr_changed} (kısmi, changed VEYA rotated) sırayı da verir. */
    @Query(value = "SELECT r FROM DnsRecord r WHERE r.monitorId IN (:id, :id) AND r.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_R + " "
         + "AND (r.changed = true OR r.rotated = true)",
           countQuery = "SELECT COUNT(r.monitorId) FROM DnsRecord r WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "AND (r.changed = true OR r.rotated = true)")
    org.springframework.data.domain.Page<DnsRecord> historyChangedPage(@Param("id") Long id,
            @Param("from") String from, @Param("to") String to, Pageable p);
    @Query("SELECT COUNT(c.monitorId) FROM DnsRecord c WHERE c.monitorId = :id AND c.checkedAt >= :from AND c.checkedAt <= :to")
    long countByMonitorIdAndCheckedAtBetween(@Param("id") Long monitorId, @Param("from") String from, @Param("to") String to);
    @Query("SELECT COUNT(r.monitorId) FROM DnsRecord r WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "AND (r.changed = true OR r.rotated = true)")
    long countChangedByMonitorIdBetween(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    // ── Hata teşhisi (2026-10-05): "Başarısız sorgu" süzgeci + sayacı. Başarısızlık ölçütü DEĞİŞMEDİ: boş değer
    // (çözümleme başarısız) — responseSeriesRaw ve weeklyStatsByMonitor ile aynı kural. "Değişenler" süzgeci aynen kalır.
    default org.springframework.data.domain.Page<DnsRecord> findFailedByMonitorIdBetween(Long id, String from, String to, Pageable p) {
        return historyFailedPage(id, from, to, MonitorIndexSort.leading(p, "monitorId"));
    }
    @Query(value = "SELECT r FROM DnsRecord r WHERE r.monitorId IN (:id, :id) AND r.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_R + " "
         + "AND (r.value IS NULL OR r.value = '')",
           countQuery = "SELECT COUNT(r.monitorId) FROM DnsRecord r WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "AND (r.value IS NULL OR r.value = '')")
    org.springframework.data.domain.Page<DnsRecord> historyFailedPage(@Param("id") Long id,
            @Param("from") String from, @Param("to") String to, Pageable p);
    @Query("SELECT COUNT(r.monitorId) FROM DnsRecord r WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "AND (r.value IS NULL OR r.value = '')")
    long countFailedByMonitorIdBetween(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    /** Yoğunluk şeridi: [bucketKey, toplam, değişen] — substr prefix kovası (dakika 16 / saat 13 / gün 10).
     *  NATIVE + TÜRETİLMİŞ TABLO: JPQL'de :len SELECT/GROUP BY'da AYRI placeholder'lara bağlanıyor,
     *  Postgres ifade eşitliğini kanıtlayamayıp 42803 atıyordu (2026-08 test ortamı). Alt sorguda
     *  parametre TEK kez geçer; dış GROUP BY gerçek bir kolona (t.bucket) bakar → her motorda geçerli. */
    @Query(value = "SELECT t.bucket, COUNT(*), SUM(t.fail) FROM ("
         + "  SELECT substr(r.checked_at,1,:len) AS bucket, "
         + "         CASE WHEN r.changed = true OR r.rotated = true THEN 1 ELSE 0 END AS fail "
         + "    FROM dns_records r WHERE r.monitor_id = :id "
         + "     AND r.checked_at >= :from AND r.checked_at <= :to"
         + ") t GROUP BY t.bucket ORDER BY t.bucket", nativeQuery = true)
    List<Object[]> historyHistogram(@Param("id") Long id, @Param("from") String from,
                                    @Param("to") String to, @Param("len") int len);

    /** History detay listesi — SQL-LIMIT'li: tüm geçmişi JVM'e çekmeden en yeni :limit satır. */
    @Query("SELECT r FROM DnsRecord r WHERE r.monitorId IN (:id, :id) ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<DnsRecord> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM DnsRecord r WHERE r.monitorId IN (:id, :id) AND r.checkedAt >= :since ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<DnsRecord> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kayıt — DNS listesinde monitör başına sorgu yerine tek toplu sorgu. */
    // LATERAL join: monitör başına tek index-seek (full-scan yerine).
    @Query(value = "SELECT c.* FROM dns_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM dns_records r WHERE r.monitor_id = m.id AND r.checked_at = "
         + "(SELECT MAX(x.checked_at) FROM dns_records x WHERE x.monitor_id = m.id) ORDER BY r.id DESC LIMIT 1) c",
           nativeQuery = true)
    List<DnsRecord> findLatestPerMonitor();
    List<DnsRecord> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(Long monitorId, String since);
    List<DnsRecord> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);

    /** Son BAŞARILI (boş olmayan) kayıt — değişiklik tespiti hata satırlarıyla
     *  değil son geçerli değerle kıyaslanır ("" geçilir). */
    @Query("SELECT r FROM DnsRecord r WHERE r.monitorId IN (:id, :id) AND r.value <> :value "
         + "ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT 1")
    Optional<DnsRecord> findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(@Param("id") Long monitorId, @Param("value") String value);

    /**
     * Zamanlanmış değişiklik tespitinin TABANI (2026-09-29): son BAŞARILI (değeri boş olmayan) ve ELLE OLMAYAN kayıt.
     * Elle kontrol kaydı (manual=true) taban olsaydı sweep yeni değeri "zaten bilinen" sayar, DNS_CHANGED hiç açılmazdı.
     */
    @Query("SELECT r FROM DnsRecord r WHERE r.monitorId IN (:monitorId, :monitorId) AND r.value <> '' "
            + "AND (r.manual IS NULL OR r.manual = false) ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT 1")
    Optional<DnsRecord> findLatestScheduledSuccessful(@Param("monitorId") Long monitorId);

    /**
     * Domain'in son changed=true kaydı — DNS_CHANGED günlük re-alert context'i için (PageRequest.of(0,1) ile çağrılır).
     * <p>Performans 2026-11-06 (PF1 madde 3): tek sorgu ({@code changed AND monitor_id IN (alan adının izlemeleri) ORDER BY
     * checked_at DESC LIMIT}) birden çok izlemeyi kapsadığından sıralamayı hiçbir izleme dizini veremiyordu; planlayıcı tekil
     * checked_at dizinini geriye tarayıp süzgeçle eliyordu — hiç değişmemiş alanda tüm tablo (yerel 120 ms, her DNS turunda açık
     * alarm başına). Artık alan adının izlemeleri okunur, her biri için {@link #findChangedByMonitorId} (kısmi
     * {@code idx_dnsr_changed}, sıralı) en yeni satırlarını verir, sonuç zamana göre birleştirilir — aynı satırlar, aynı sıra.
     */
    default List<DnsRecord> findChangedByDomain(String domain, Pageable pageable) {
        Pageable perMonitor = pageable == null || pageable.isUnpaged() ? Pageable.unpaged()
                : org.springframework.data.domain.PageRequest.of(0, (int) Math.min(Integer.MAX_VALUE,
                        pageable.getOffset() + pageable.getPageSize()));
        List<DnsRecord> all = new java.util.ArrayList<>();
        for (Long id : monitorIdsForDomain(domain)) all.addAll(findChangedByMonitorId(id, perMonitor));
        all.sort(java.util.Comparator.comparing(DnsRecord::getCheckedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.<String>reverseOrder())));
        if (pageable == null || pageable.isUnpaged()) return all;
        int from = (int) Math.min(all.size(), pageable.getOffset());
        return new java.util.ArrayList<>(all.subList(from, Math.min(all.size(), from + pageable.getPageSize())));
    }

    /** Alan adının DNS izleme kimlikleri ({@link #findChangedByDomain}). */
    @Query("SELECT m.id FROM DnsMonitor m WHERE m.domain = :domain")
    List<Long> monitorIdsForDomain(@Param("domain") String domain);

    /** İzlemenin son changed=true kaydı (D-b2) — DNS_CHANGED günlük yeniden uyarısı olayı AÇAN izlemenin kaydını
     *  kullanır (aynı alan adında birden çok DNS izlemesi varsa başkasının değişikliği anlatılmasın). */
    @Query("SELECT r FROM DnsRecord r WHERE r.changed = true AND r.monitorId IN (:monitorId, :monitorId) ORDER BY r.monitorId DESC, r.checkedAt DESC")
    List<DnsRecord> findChangedByMonitorId(@Param("monitorId") Long monitorId, Pageable pageable);

    /** Yanıt-süresi grafiği için ham veri: [checked_at, response_ms (null olabilir), başarı-bayrağı] — aralık + cap.
     *  DNS'te up/down bool yok → başarı = değer dolu (boş value = çözümleme başarısız). buildResponseSeries ile paylaşımlı. */
    @Query("SELECT r.checkedAt, r.responseMs, CASE WHEN r.value IS NULL OR r.value = '' THEN false ELSE true END FROM DnsRecord r "
         + "WHERE r.monitorId IN (:id, :id) AND r.checkedAt >= :from AND " + MonitorIndexSort.UNTIL_R + " "
         + "ORDER BY r.monitorId DESC, r.checkedAt DESC LIMIT :limit")
    List<Object[]> responseSeriesRaw(@Param("id") Long id, @Param("from") String from,
                                     @Param("to") String to, @Param("limit") int limit);

    /** Haftalık izleme özeti: [monitorId, toplam, BAŞARILI, değişim_sayısı] — ids ∩ [from,to];
     *  başarı = value dolu (çözümleme başarılı); değişim = changed=true (CHANGED/ROTATED olayı). */
    @Query("SELECT r.monitorId, COUNT(r), SUM(CASE WHEN r.value IS NULL OR r.value = '' THEN 0L ELSE 1L END), "
         + "SUM(CASE WHEN r.changed = true THEN 1L ELSE 0L END) "
         + "FROM DnsRecord r WHERE r.monitorId IN :ids AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "GROUP BY r.monitorId")
    List<Object[]> weeklyStatsByMonitor(@Param("ids") java.util.Collection<Long> ids,
                                        @Param("from") String from, @Param("to") String to);

    /** Saklama seffafligi: bu izlemenin elde TUTULAN en eski ve en yeni kaydi ([min, max]).
     *  Kullanici Kontrol Gecmisi'nde "veri su tarihten itibaren tutuluyor" bilgisini gorur.
     *  Zaman kolonu indexli oldugundan MIN/MAX index-seek'tir (tablo taramasi yok). */
    @Query("SELECT MIN(c.checkedAt), MAX(c.checkedAt) FROM DnsRecord c WHERE c.monitorId = :id")
    List<Object[]> historyBounds(@Param("id") Long id);
}
