package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Olay kaydının postmortem raporu (2026-10-11, kullanıcı isteği "postmortem şablonu ve aksiyon takibi") — olay başına
 * EN ÇOK bir satır. Suçlamasız (blameless) şablonun bölümleri markdown metin; durum TASLAK → İNCELEMEDE → YAYIMLANDI.
 * Eşzamanlı düzenleme {@link Version} ile korunur (eski sürümle kayıt 409 CONCURRENT_MODIFICATION). Zaman damgaları
 * ISO-8601 UTC metin (olay kaydı konvansiyonu).
 */
@Entity
@Table(name = "incident_postmortems",
        uniqueConstraints = @UniqueConstraint(name = "uk_incident_postmortem_incident", columnNames = "incident_id"))
@Data
@NoArgsConstructor
public class IncidentPostmortem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    /** DRAFT | IN_REVIEW | PUBLISHED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT") private String summary;
    @Column(columnDefinition = "TEXT") private String impact;
    @Column(name = "root_cause", columnDefinition = "TEXT") private String rootCause;
    /** "trigger" SQL anahtar sözcüğü — kolon adı ayrı. */
    @Column(name = "trigger_text", columnDefinition = "TEXT") private String triggerText;
    @Column(columnDefinition = "TEXT") private String detection;
    @Column(columnDefinition = "TEXT") private String resolution;
    @Column(name = "went_well", columnDefinition = "TEXT") private String wentWell;
    @Column(name = "went_wrong", columnDefinition = "TEXT") private String wentWrong;
    @Column(columnDefinition = "TEXT") private String lucky;
    @Column(columnDefinition = "TEXT") private String lessons;

    @Column(name = "created_by", length = 100) private String createdBy;
    @Column(name = "created_at", length = 30) private String createdAt;
    @Column(name = "updated_by", length = 100) private String updatedBy;
    @Column(name = "updated_at", length = 30) private String updatedAt;
    @Column(name = "published_by", length = 100) private String publishedBy;
    @Column(name = "published_at", length = 30) private String publishedAt;

    @Version
    private Long version;
}
