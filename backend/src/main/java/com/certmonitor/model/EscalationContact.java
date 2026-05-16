package com.certmonitor.model;

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

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
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

    @Column(nullable = false)
    private Boolean active = true;

    private String createdAt;
}
