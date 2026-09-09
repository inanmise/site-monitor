package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateNoteRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CertificateNoteRevisionRepository extends JpaRepository<CertificateNoteRevision, Long> {

    List<CertificateNoteRevision> findByNoteIdOrderBySequenceNoAsc(Long noteId);

    /** Kalıcı purge: notların revizyonlarını da sil (öksüz revizyon kalmasın). */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM CertificateNoteRevision r WHERE r.noteId IN :noteIds")
    int deleteByNoteIdIn(@org.springframework.data.repository.query.Param("noteIds") java.util.Collection<Long> noteIds);

    @Query("SELECT MAX(r.sequenceNo) FROM CertificateNoteRevision r WHERE r.noteId = :noteId")
    Integer findMaxSequenceNo(@Param("noteId") Long noteId);

    long countByNoteId(Long noteId);
}
