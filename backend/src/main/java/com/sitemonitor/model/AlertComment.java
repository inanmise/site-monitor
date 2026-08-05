package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir alarma (AlertEvent = incident) bağlı serbest yorum — Incidents ekranında yorum dizisi olarak gösterilir.
 * Soft-delete (kayıt kaybolmaz). Author/team/soft-delete alanları MonitorNote deseniyle aynıdır.
 */
@Entity
@Table(name = "alert_comments", indexes = {
    @Index(name = "idx_acmt_alert",   columnList = "alert_event_id"),
    @Index(name = "idx_acmt_deleted", columnList = "deleted_at")
})
@Data
@NoArgsConstructor
public class AlertComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bağlı olduğu AlertEvent (incident) id'si. */
    @Column(name = "alert_event_id", nullable = false)
    private Long alertEventId;

    /** Yorum metni (markdown). */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

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

    @Column(name = "deleted_at")
    private String deletedAt;

    @Column(name = "deleted_by")
    private String deletedBy;
}
