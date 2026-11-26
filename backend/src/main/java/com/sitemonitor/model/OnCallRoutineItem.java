package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Nöbet rutini görevinin bir dönemdeki örneği (2026-11-26). Satır ilk dokunuşta (işaret, not, sorun, gün tamamlama, kapanış)
 * "maddeleşir": başlık / açıklama / bağlantılar / sinyaller / zorunluluk / pencere o andaki şablondan KOPYALANIR (geçmiş
 * yeniden yazılmaz). Takım × görev × dönem benzersiz. Yazmalar yalnız hedefli koşullu SQL.
 */
@Entity
@DynamicUpdate
@Table(name = "oncall_routine_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_ori_task_period",
                columnNames = {"team_id", "task_id", "period_kind", "period_key"}),
        indexes = {
                @Index(name = "idx_ori_period", columnList = "team_id, period_kind, period_key"),
                @Index(name = "idx_ori_due", columnList = "team_id, due_day")
        })
@Data
@NoArgsConstructor
public class OnCallRoutineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "task_id", length = 40, nullable = false)
    private String taskId;

    @Column(name = "section_id", length = 40)
    private String sectionId;

    /** DAY | WEEK | MONTH. */
    @Column(name = "period_kind", length = 8, nullable = false)
    private String periodKind;

    @Column(name = "period_key", length = 10, nullable = false)
    private String periodKey;

    @Column(name = "due_day", length = 10, nullable = false)
    private String dueDay;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(name = "section_title", length = 200)
    private String sectionTitle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "links_json", columnDefinition = "TEXT")
    private String linksJson;

    @Column(length = 200)
    private String signals;

    @Column(nullable = false)
    private Boolean required = true;

    @Column(length = 8, nullable = false)
    private String frequency;

    @Column(name = "window_from", length = 5)
    private String windowFrom;

    @Column(name = "window_to", length = 5)
    private String windowTo;

    /** PENDING | DONE | SKIPPED | MISSED. */
    @Column(length = 10, nullable = false)
    private String state;

    @JsonIgnore
    @Column(name = "state_by_id")
    private Long stateById;

    @Column(name = "state_by_name", length = 200)
    private String stateByName;

    @Column(name = "state_at", length = 30)
    private String stateAt;

    @Column(name = "skip_reason", length = 300)
    private String skipReason;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "note_version", nullable = false)
    private Integer noteVersion = 0;

    @Column(name = "note_by_name", length = 200)
    private String noteByName;

    @Column(name = "note_at", length = 30)
    private String noteAt;

    /** TOUCH | COMPLETE | CLOSE. */
    @Column(length = 8)
    private String source;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;
}
