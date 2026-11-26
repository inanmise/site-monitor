package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Nöbet rutini — takım × gün satırı (2026-11-26): durum, sorumlu anlık görüntüsü, tamamlanma sayaçları, günün notu. Gün
 * sütununun adı {@code routine_day} ({@code DAY} H2'de ayrılmış sözcük; telde anahtar yine {@code date}). Yazmalar yalnız
 * hedefli koşullu SQL ({@code OnCallRoutineStore}); görev satırlarından ÖNCE kilitlenir (kilit sırası gün → görev).
 */
@Entity
@DynamicUpdate
@Table(name = "oncall_routine_days",
        uniqueConstraints = @UniqueConstraint(name = "uk_ord_team_day", columnNames = {"team_id", "routine_day"}),
        indexes = {
                @Index(name = "idx_ord_team_status", columnList = "team_id, status"),
                @Index(name = "idx_ord_completed", columnList = "completed_at")
        })
@Data
@NoArgsConstructor
public class OnCallRoutineDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "routine_day", length = 10, nullable = false)
    private String routineDay;

    @Column(name = "week_key", length = 10, nullable = false)
    private String weekKey;

    @Column(name = "handover_key", length = 10, nullable = false)
    private String handoverKey;

    @Column(length = 20, nullable = false)
    private String status;

    @JsonIgnore
    @Column(name = "responsible_user_id")
    private Long responsibleUserId;

    @Column(name = "responsible_name", length = 200)
    private String responsibleName;

    @Column(name = "responsible_source", length = 16)
    private String responsibleSource;

    @Column(name = "required_count")
    private Integer requiredCount;

    @Column(name = "done_count")
    private Integer doneCount;

    @Column(name = "skipped_count")
    private Integer skippedCount;

    @Column(name = "pending_count")
    private Integer pendingCount;

    @Column(name = "problems_opened")
    private Integer problemsOpened;

    @JsonIgnore
    @Column(name = "completed_by_id")
    private Long completedById;

    @Column(name = "completed_by_name", length = 200)
    private String completedByName;

    @Column(name = "completed_at", length = 30)
    private String completedAt;

    @Column(name = "completed_late")
    private Boolean completedLate;

    @Column(name = "completion_seq", nullable = false)
    private Integer completionSeq = 0;

    @Column(name = "notified_seq", nullable = false)
    private Integer notifiedSeq = 0;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "note_version", nullable = false)
    private Integer noteVersion = 0;

    @Column(name = "note_by_name", length = 200)
    private String noteByName;

    @Column(name = "note_at", length = 30)
    private String noteAt;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(name = "closed_at", length = 30)
    private String closedAt;

    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;
}
