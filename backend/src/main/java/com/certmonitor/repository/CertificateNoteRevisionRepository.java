package com.certmonitor.repository;

import com.certmonitor.model.CertificateNoteRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CertificateNoteRevisionRepository extends JpaRepository<CertificateNoteRevision, Long> {

    List<CertificateNoteRevision> findByNoteIdOrderBySequenceNoAsc(Long noteId);

    @Query("SELECT MAX(r.sequenceNo) FROM CertificateNoteRevision r WHERE r.noteId = :noteId")
    Integer findMaxSequenceNo(@Param("noteId") Long noteId);

    long countByNoteId(Long noteId);
}
