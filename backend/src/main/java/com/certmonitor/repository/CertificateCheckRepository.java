package com.certmonitor.repository;

import com.certmonitor.model.CertificateCheck;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
