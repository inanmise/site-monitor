package com.certmonitor.repository;

import com.certmonitor.model.DnsRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DnsRecordRepository extends JpaRepository<DnsRecord, Long> {
    List<DnsRecord> findByMonitorIdOrderByCheckedAtDesc(Long monitorId);
    Optional<DnsRecord> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);

    /** Her monitör için en güncel kayıt — DNS listesinde monitör başına sorgu yerine tek toplu sorgu. */
    @Query("SELECT r FROM DnsRecord r WHERE r.id IN "
         + "(SELECT MAX(r2.id) FROM DnsRecord r2 GROUP BY r2.monitorId)")
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
}
