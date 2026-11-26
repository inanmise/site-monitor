package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Nöbet rutini kontrolünde bulunan sorun (2026-11-26): not + önem + durum; isteğe bağlı bir göreve bağlı. Açık sorunlar
 * çözülene dek sonraki günlere ve sonraki nöbetçiye taşınır. Sürüm CAS'ı ile güncellenir. Gün sütunu {@code routine_day}.
 */
@Entity
@DynamicUpdate
@Table(name = "oncall_routine_problems",
        indexes = {
                @Index(name = "idx_orp_team_status", columnList = "team_id, status"),
                @Index(name = "idx_orp_team_day", columnList = "team_id, routine_day")
        })
@Data
@NoArgsConstructor
public class OnCallRoutineProblem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "routine_day", length = 10, nullable = false)
    private String routineDay;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "task_id", length = 40)
    private String taskId;

    @Column(name = "task_title", length = 200)
    private String taskTitle;

    @Column(name = "section_title", length = 200)
    private String sectionTitle;

    /** LOW | MEDIUM | HIGH. */
    @Column(length = 8, nullable = false)
    private String severity;

    /** OPEN | RESOLVED. */
    @Column(length = 10, nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String note;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "incident_record_id")
    private Long incidentRecordId;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "updated_by_name", length = 200)
    private String updatedByName;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @JsonIgnore
    @Column(name = "resolved_by_id")
    private Long resolvedById;

    @Column(name = "resolved_by_name", length = 200)
    private String resolvedByName;

    @Column(name = "resolved_at", length = 30)
    private String resolvedAt;

    @Column(name = "resolved_day", length = 10)
    private String resolvedDay;

    @Column(nullable = false)
    private Integer version = 0;
}
