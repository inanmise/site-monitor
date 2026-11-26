package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nöbet rutini bildirim sahiplenmesi (2026-11-26, {@code oncall_weekly_notices} deseni): satır EKLEMEK sahiplenmedir (takım ×
 * tür × anahtar benzersiz), sonrası yalnız koşullu UPDATE. Yarıda kalan SENDING satırı UNKNOWN olur, yeniden gönderilmez.
 */
@Entity
@Table(name = "oncall_routine_notices",
        uniqueConstraints = @UniqueConstraint(name = "uk_orn_team_kind_ref", columnNames = {"team_id", "kind", "ref_key"}),
        indexes = @Index(name = "idx_orn_status_claimed", columnList = "status, claimed_at"))
@Data
@NoArgsConstructor
public class OnCallRoutineNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** COMPLETE | REMINDER | SECTION | DUE | HANDOVER. */
    @Column(length = 16, nullable = false)
    private String kind;

    @Column(name = "ref_key", length = 80, nullable = false)
    private String refKey;

    /** SENDING | SENT | PARTIAL | SKIPPED | FAILED | UNKNOWN. */
    @Column(length = 12, nullable = false)
    private String status;

    @Column(length = 32)
    private String reason;

    @JsonIgnore
    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "mail_status", length = 255)
    private String mailStatus;

    /** delivered | skipped | failed | off. */
    @Column(name = "push_status", length = 16)
    private String pushStatus;

    @Column(name = "push_count")
    private Integer pushCount;

    @Column(nullable = false)
    private Integer attempts = 1;

    @Column(name = "claimed_at", length = 19)
    private String claimedAt;

    @Column(name = "claimed_by", length = 120)
    private String claimedBy;

    @Column(name = "finished_at", length = 19)
    private String finishedAt;

    @Column(name = "mail_log_id")
    private Long mailLogId;

    @Column(name = "error_class", length = 120)
    private String errorClass;

    @Column(name = "created_at", length = 19)
    private String createdAt;
}
