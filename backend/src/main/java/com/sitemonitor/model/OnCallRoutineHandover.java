package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Nöbet rutini devri (2026-11-26) — takım × devir dönemi: "sonraki nöbetçiye not", dönem sonunda iş tarafından yazılan
 * özet ve "Devraldım" onayı (koşullu {@code WHERE ack_at IS NULL}).
 */
@Entity
@DynamicUpdate
@Table(name = "oncall_routine_handovers",
        uniqueConstraints = @UniqueConstraint(name = "uk_orh_team_period", columnNames = {"team_id", "period_key"}))
@Data
@NoArgsConstructor
public class OnCallRoutineHandover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "period_key", length = 10, nullable = false)
    private String periodKey;

    @Column(name = "period_start", length = 30)
    private String periodStart;

    @Column(name = "period_end", length = 30)
    private String periodEnd;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "note_version", nullable = false)
    private Integer noteVersion = 0;

    @Column(name = "note_by_name", length = 200)
    private String noteByName;

    @Column(name = "note_at", length = 30)
    private String noteAt;

    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    @Column(name = "closed_at", length = 30)
    private String closedAt;

    @JsonIgnore
    @Column(name = "ack_by_id")
    private Long ackById;

    @Column(name = "ack_by_name", length = 200)
    private String ackByName;

    @Column(name = "ack_at", length = 30)
    private String ackAt;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;
}
