package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A contact that receives certificate alerts.
 * minAlertLevel controls at which severity this contact starts receiving alerts:
 *   WARNING  → receives WARNING, HIGH, CRITICAL
 *   HIGH     → receives HIGH, CRITICAL
 *   CRITICAL → receives CRITICAL only
 */
@Entity
@Table(name = "escalation_contacts")
@Data
@NoArgsConstructor
public class EscalationContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Link to an AppUser — when set, name/email are derived from the user on save */
    @Column(name = "user_id")
    private Long userId;

    private String name;

    private String email;

    /** Organizational role: PO, TECH, MANAGER, CLEVEL */
    @Column(nullable = false)
    private String role;

    /** Minimum alert level to trigger notification: WARNING, HIGH, CRITICAL */
    @Column(nullable = false)
    private String minAlertLevel = "WARNING";

    /** Optional webhook URL for Teams or Slack */
    private String webhookUrl;

    /** TEAMS or SLACK */
    private String webhookType;

    /** Team this contact belongs to — alerts only fire for certs in the same team */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    private String createdAt;

    /**
     * Zamana bağlı eskalasyon adımı (2026-10-01, opt-in). {@code null} ya da {@code 0} = BUGÜNKÜ davranış: kişi alarm
     * açılır açılmaz (ve seviye artışı / günlük hatırlatma / çözümde) bilgilendirilir. Değer (1–1440 dk) verilirse kişi
     * ANLIK bildirimlere girmez; alarm AÇIK ve ONAYSIZ olarak bu kadar dakika beklerse {@code EscalationStepService} ona
     * BİR kez "eskalasyon adımı" gönderir — adım gittikten sonra o alarmın normal alıcısıdır. Kapsam/seviye kuralları
     * ({@code EscalationContactScope}) aynen geçerlidir. Şema: {@code SchedulerService.applySchemaPatches}.
     */
    @Column(name = "delay_minutes")
    private Integer delayMinutes;
}
