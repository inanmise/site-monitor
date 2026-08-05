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
    Optional<DnsRecord> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** History detay listesi — SQL-LIMIT'li: tüm geçmişi JVM'e çekmeden en yeni :limit satır. */
    @Query("SELECT r FROM DnsRecord r WHERE r.monitorId = :id ORDER BY r.checkedAt DESC LIMIT :limit")
    List<DnsRecord> findRecentByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM DnsRecord r WHERE r.monitorId = :id AND r.checkedAt >= :since ORDER BY r.checkedAt DESC LIMIT :limit")
    List<DnsRecord> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** "Sadece Değişenler" filtresi — changed VEYA rotated satırlar (diff taşıyanlar), SQL-LIMIT'li.
     *  Sunucu tarafında filtrelenir ki 5000-satır cap'inde TÜM aralığın değişen kayıtları dönebilsin. */
    @Query("SELECT r FROM DnsRecord r WHERE r.monitorId = :id AND (r.changed = true OR r.rotated = true) "
         + "ORDER BY r.checkedAt DESC LIMIT :limit")
    List<DnsRecord> findRecentChangedByMonitorId(@Param("id") Long id, @Param("limit") int limit);

    @Query("SELECT r FROM DnsRecord r WHERE r.monitorId = :id AND r.checkedAt >= :since "
         + "AND (r.changed = true OR r.rotated = true) ORDER BY r.checkedAt DESC LIMIT :limit")
    List<DnsRecord> findRecentChangedByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);

    /** Her monitör için en güncel kayıt — DNS listesinde monitör başına sorgu yerine tek toplu sorgu. */
    // LATERAL join: monitör başına tek index-seek (full-scan yerine).
    @Query(value = "SELECT c.* FROM dns_monitors m CROSS JOIN LATERAL "
         + "(SELECT * FROM dns_records r WHERE r.monitor_id = m.id ORDER BY r.checked_at DESC LIMIT 1) c",
           nativeQuery = true)
    List<DnsRecord> findLatestPerMonitor();
    List<DnsRecord> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(Long monitorId, String since);
    List<DnsRecord> findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(Long monitorId, String since);

    /** Son BAŞARILI (boş olmayan) kayıt — değişiklik tespiti hata satırlarıyla
     *  değil son geçerli değerle kıyaslanır ("" geçilir). */
    Optional<DnsRecord> findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(Long monitorId, String value);

    /** Domain'in son changed=true kaydı — DNS_CHANGED günlük re-alert context'i
     *  için (PageRequest.of(0,1) ile çağrılır). */
    @Query("""
            SELECT r FROM DnsRecord r
             WHERE r.changed = true
               AND r.monitorId IN (SELECT m.id FROM DnsMonitor m WHERE m.domain = :domain)
             ORDER BY r.checkedAt DESC
            """)
    List<DnsRecord> findChangedByDomain(@Param("domain") String domain, Pageable pageable);

    /** Yanıt-süresi grafiği için ham veri: [checked_at, response_ms (null olabilir), başarı-bayrağı] — aralık + cap.
     *  DNS'te up/down bool yok → başarı = değer dolu (boş value = çözümleme başarısız). buildResponseSeries ile paylaşımlı. */
    @Query("SELECT r.checkedAt, r.responseMs, CASE WHEN r.value IS NULL OR r.value = '' THEN false ELSE true END FROM DnsRecord r "
         + "WHERE r.monitorId = :id AND r.checkedAt >= :from AND r.checkedAt <= :to "
         + "ORDER BY r.checkedAt DESC LIMIT :limit")
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
}
