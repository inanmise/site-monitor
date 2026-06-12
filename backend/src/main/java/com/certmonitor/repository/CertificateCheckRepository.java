package com.certmonitor.repository;

import com.certmonitor.model.CertificateCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CertificateCheckRepository extends JpaRepository<CertificateCheck, Long> {

    @Query("SELECT c FROM CertificateCheck c WHERE c.domain = :domain ORDER BY c.checkedAt DESC LIMIT :limit")
    List<CertificateCheck> findTopByDomainOrderByCheckedAtDesc(@Param("domain") String domain, @Param("limit") int limit);

    /** All checks after the given ISO cutoff string, newest first — used for activity log. */
    @Query("SELECT c FROM CertificateCheck c WHERE c.checkedAt >= :cutoff ORDER BY c.checkedAt DESC")
    List<CertificateCheck> findByCheckedAtAfter(@Param("cutoff") String cutoff);

    /** Aynı sorgu, satır tavanıyla (OOM koruması) — en yeni :limit kayıt. */
    @Query("SELECT c FROM CertificateCheck c WHERE c.checkedAt >= :cutoff ORDER BY c.checkedAt DESC LIMIT :limit")
    List<CertificateCheck> findByCheckedAtAfterLimited(@Param("cutoff") String cutoff, @Param("limit") int limit);

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
}
