package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Nöbet rutini — takım başına TEK satır: kontrol listesi şablonu (JSON) + takım ayarları (JSON) (2026-11-26). Satır yalnız
 * hedefli koşullu SQL ile yazılır ({@code OnCallRoutineStore}); okunan nesne ASLA {@code save} edilmez. {@code enabled} =
 * ayardaki "etkin" VE en az bir etkin görev — işin ucuz kapısı.
 */
@Entity
@DynamicUpdate
@Table(name = "oncall_routine_teams",
        uniqueConstraints = @UniqueConstraint(name = "uk_ort_team", columnNames = "team_id"))
@Data
@NoArgsConstructor
public class OnCallRoutineTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "template_json", columnDefinition = "TEXT")
    private String templateJson;

    @Column(name = "template_version", nullable = false)
    private Integer templateVersion = 0;

    @Column(name = "tasks_active", nullable = false)
    private Integer tasksActive = 0;

    @Column(name = "template_updated_at", length = 30)
    private String templateUpdatedAt;

    @JsonIgnore
    @Column(name = "template_updated_by_id")
    private Long templateUpdatedById;

    @Column(name = "template_updated_by_name", length = 200)
    private String templateUpdatedByName;

    @Column(name = "settings_json", columnDefinition = "TEXT")
    private String settingsJson;

    @Column(name = "settings_version", nullable = false)
    private Integer settingsVersion = 0;

    @Column(name = "settings_updated_at", length = 30)
    private String settingsUpdatedAt;

    @Column(name = "settings_updated_by_name", length = 200)
    private String settingsUpdatedByName;

    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(name = "enabled_since", length = 10)
    private String enabledSince;

    @Column(name = "paused_since", length = 10)
    private String pausedSince;

    /**
     * Kapatılmış son ISO hafta (Pazartesi {@code yyyy-MM-dd}) / ay ({@code yyyy-MM}) — dönem kapanışı en eskiden yeniye
     * koşar, bu işaretin altındaki dönem bir daha kapatılmaz (yeniden başlatma / başka pod kapanıştan sonra PENDING'e çekilen
     * görevi yeniden MISSED yapmasın). Yalnız koşullu, tekdüze (artan) UPDATE yazar.
     */
    @Column(name = "week_closed_through", length = 10)
    private String weekClosedThrough;

    @Column(name = "month_closed_through", length = 7)
    private String monthClosedThrough;

    @Column(name = "created_at", length = 30, nullable = false)
    private String createdAt;
}
