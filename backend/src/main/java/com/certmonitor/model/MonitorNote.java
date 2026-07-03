package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hedefe (ping=host, keyword=url) bağlı yapılandırılmış sorun-çözüm notu. Her sorun yaşandığında elle eklenir.
 * Alanlar markdown: Sorun (zorunlu) / Yapılan işlem / Kök neden / Bakılacak yerler. Soft-delete (kayıt kaybolmaz).
 */
@Entity
@Table(name = "monitor_notes", indexes = {
    @Index(name = "idx_mnote_target",  columnList = "monitor_type,target"),
    @Index(name = "idx_mnote_deleted", columnList = "deleted_at")
})
@Data
@NoArgsConstructor
public class MonitorNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** KEYWORD | PING */
    @Column(name = "monitor_type", nullable = false, length = 20)
    private String monitorType;

    /** ping = host, keyword = url */
    @Column(nullable = false, length = 500)
    private String target;

    /** Sorun (zorunlu) — markdown */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String problem;

    /** Yapılan işlem — markdown */
    @Column(name = "action_taken", columnDefinition = "TEXT")
    private String actionTaken;

    /** Kök neden — markdown */
    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    /** Bakılacak yerler — markdown ("references" SQL rezerve olduğu için kolon adı 'refs'). */
    @Column(columnDefinition = "TEXT")
    private String refs;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "author_username")
    private String authorUsername;

    @Column(name = "author_name")
    private String authorName;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "deleted_at")
    private String deletedAt;

    @Column(name = "deleted_by")
    private String deletedBy;
}
