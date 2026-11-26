package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nöbet rutini alan geçmişi (2026-11-26): kim, ne zaman, ne yaptı (işaret, not, sorun, tamamlama, devralma …). Ayrıntı
 * yalnız başlık / neden / önem / sayı taşır — bağlantı adresi ASLA. Gün sütunu {@code routine_day}.
 */
@Entity
@Table(name = "oncall_routine_events",
        indexes = @Index(name = "idx_ore_team_day", columnList = "team_id, routine_day, id"))
@Data
@NoArgsConstructor
public class OnCallRoutineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "routine_day", length = 10)
    private String routineDay;

    @Column(length = 24, nullable = false)
    private String kind;

    @Column(name = "task_id", length = 40)
    private String taskId;

    @Column(name = "problem_id")
    private Long problemId;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", length = 200)
    private String actorName;

    @Column(length = 30, nullable = false)
    private String at;

    @Column(name = "detail_json", length = 4000)
    private String detailJson;
}
