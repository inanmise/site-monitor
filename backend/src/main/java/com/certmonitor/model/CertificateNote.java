package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "certificate_notes",
    indexes = {
        @Index(name = "idx_note_domain", columnList = "domain"),
        @Index(name = "idx_note_team",   columnList = "teamId")
    }
)
@Data
@NoArgsConstructor
public class CertificateNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String domain;

    /** Team that owns this note. */
    private Long teamId;

    private String authorUsername;
    private String authorName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String note;

    private String createdAt;
}
