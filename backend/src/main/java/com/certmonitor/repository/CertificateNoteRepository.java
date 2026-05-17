package com.certmonitor.repository;

import com.certmonitor.model.CertificateNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CertificateNoteRepository extends JpaRepository<CertificateNote, Long> {
    List<CertificateNote> findByDomainOrderByCreatedAtDesc(String domain);
    List<CertificateNote> findByDomainAndTeamIdOrderByCreatedAtDesc(String domain, Long teamId);
}
