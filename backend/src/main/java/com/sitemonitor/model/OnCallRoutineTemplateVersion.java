package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Nöbet rutini şablon geçmişi (2026-11-26) — her kayıt bir satır; takım başına en çok 200 (en eskiler silinir). Gün kapanışı
 * "o günün sonunda geçerli şablon"u buradan okur ({@code templateAt}).
 */
@Entity
@DynamicUpdate
@Table(name = "oncall_routine_template_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_ortv_team_version", columnNames = {"team_id", "version"}),
        indexes = @Index(name = "idx_ortv_team_saved", columnList = "team_id, saved_at"))
@Data
@NoArgsConstructor
public class OnCallRoutineTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "template_json", columnDefinition = "TEXT", nullable = false)
    private String templateJson;

    @Column(name = "summary_json", length = 2000)
    private String summaryJson;

    /** EDITOR | IMPORT | SAMPLE | RESTORE. */
    @Column(length = 12)
    private String source;

    @Column(name = "saved_at", length = 30, nullable = false)
    private String savedAt;

    @Column(name = "saved_by_name", length = 200)
    private String savedByName;
}
