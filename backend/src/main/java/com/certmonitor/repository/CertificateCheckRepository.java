package com.certmonitor.repository;

import com.certmonitor.model.CertificateCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CertificateCheckRepository extends JpaRepository<CertificateCheck, Long> {

    @Query("SELECT c FROM CertificateCheck c WHERE c.domain = :domain ORDER BY c.checkedAt DESC LIMIT :limit")
    List<CertificateCheck> findTopByDomainOrderByCheckedAtDesc(@Param("domain") String domain, @Param("limit") int limit);

    /** All checks after the given ISO cutoff string, newest first — used for activity log. */
    @Query("SELECT c FROM CertificateCheck c WHERE c.checkedAt >= :cutoff ORDER BY c.checkedAt DESC")
    List<CertificateCheck> findByCheckedAtAfter(@Param("cutoff") String cutoff);

    // (findByCheckedAtAfterLimited + aktivite projeksiyonu kaldırıldı — getActivityRlog ölü koddu, silindi.)

    /** Domain başına özet: [domain, toplam, hata_sayısı] — cutoff'tan beri.
     *  uptime/overview için tablo-başı-döngü (domain × tüm-tablo) yerine tek gruplu sorgu. */
    @Query("SELECT c.domain, COUNT(c), SUM(CASE WHEN c.status = 'error' THEN 1L ELSE 0L END) "
         + "FROM CertificateCheck c WHERE c.checkedAt >= :cutoff AND (c.maintenance = false OR c.maintenance IS NULL) GROUP BY c.domain")
    List<Object[]> aggregateStatusCountsSince(@Param("cutoff") String cutoff);

    /** Haftalık izleme özeti: [domain, toplam, BAŞARILI] — domains ∩ [from,to]; başarı = status<>'error' (bakım hariç). */
    @Query("SELECT c.domain, COUNT(c), SUM(CASE WHEN c.status <> 'error' THEN 1L ELSE 0L END) "
         + "FROM CertificateCheck c WHERE c.domain IN :domains AND c.checkedAt >= :from AND c.checkedAt <= :to "
         + "AND (c.maintenance = false OR c.maintenance IS NULL) GROUP BY c.domain")
    List<Object[]> weeklyStatsByDomain(@Param("domains") java.util.Collection<String> domains,
                                       @Param("from") String from, @Param("to") String to);

    @Query("SELECT c FROM CertificateCheck c WHERE c.domain = :domain AND c.checkedAt >= :from AND c.checkedAt <= :to ORDER BY c.checkedAt DESC LIMIT :limit")
    List<CertificateCheck> findByDomainAndDateRange(
        @Param("domain") String domain,
        @Param("from")   String from,
        @Param("to")     String to,
        @Param("limit")  int    limit
    );

    /** Domain rename: tüm geçmiş kontrol kayıtlarını yeni domain'e taşı.
     *  Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE CertificateCheck c SET c.domain = :newDomain WHERE c.domain = :oldDomain")
    int renameDomain(@Param("oldDomain") String oldDomain, @Param("newDomain") String newDomain);

    /** Kalıcı silme (purge): bir domain'in tüm geçmiş kontrol kayıtlarını sil.
     *  Caller'da @Transactional zorunlu. Döndürülen değer silinen satır sayısı. */
    @Modifying
    @Query("DELETE FROM CertificateCheck c WHERE c.domain = :domain")
    int deleteByDomain(@Param("domain") String domain);
}
