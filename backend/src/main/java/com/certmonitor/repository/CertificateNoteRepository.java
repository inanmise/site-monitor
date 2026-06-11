package com.certmonitor.repository;

import com.certmonitor.model.CertificateNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CertificateNoteRepository extends JpaRepository<CertificateNote, Long> {

    /** Returns ALL notes for a domain (including soft-deleted), newest first.
     *  Audit-grade view: deleted notes remain visible with deletion metadata. */
    @Query("SELECT n FROM CertificateNote n WHERE n.domain = :domain ORDER BY n.createdAt DESC")
    List<CertificateNote> findByDomainOrderByCreatedAtDesc(@Param("domain") String domain);

    @Query("SELECT n FROM CertificateNote n WHERE n.domain = :domain AND n.teamId = :teamId ORDER BY n.createdAt DESC")
    List<CertificateNote> findByDomainAndTeamIdOrderByCreatedAtDesc(@Param("domain") String domain, @Param("teamId") Long teamId);

    /** Domain rename: notları yeni domain'e taşı.
     *  Caller'da @Transactional zorunlu. */
    @Modifying
    @Query("UPDATE CertificateNote n SET n.domain = :newDomain WHERE n.domain = :oldDomain")
    int renameDomain(@Param("oldDomain") String oldDomain, @Param("newDomain") String newDomain);
}
