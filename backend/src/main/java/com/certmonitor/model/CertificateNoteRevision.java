package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Append-only revision log for {@link CertificateNote}.
 * Captures every CREATE / EDIT / DELETE / RESTORE event so the
 * full lifecycle of a note remains auditable even after edits or soft-delete.
 */
@Entity
@Table(
    name = "certificate_note_revisions",
    indexes = {
        @Index(name = "idx_cnr_note_seq", columnList = "noteId,sequenceNo"),
        @Index(name = "idx_cnr_edited",  columnList = "editedAt")
    }
)
@Data
@NoArgsConstructor
public class CertificateNoteRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long noteId;

    /** 0 = CREATE, 1, 2, ... incremented per edit/delete/restore. */
    @Column(nullable = false)
    private Integer sequenceNo;

    /** CREATE / EDIT / DELETE / RESTORE */
    @Column(nullable = false, length = 16)
    private String eventType;

    /** Snapshot of the note body at this revision. NULL for DELETE/RESTORE events. */
    @Column(columnDefinition = "TEXT")
    private String body;

    /** Snapshot of the category at this revision. */
    @Column(length = 16)
    private String category;

    @Column(nullable = false)
    private String editedAt;

    @Column(nullable = false)
    private String editedBy;

    private String editedByName;

    /** Optional free-text reason (e.g. why an edit or delete was performed). */
    @Column(columnDefinition = "TEXT")
    private String reason;
}
