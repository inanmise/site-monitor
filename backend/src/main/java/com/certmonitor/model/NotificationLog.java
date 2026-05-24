package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_logs")
@Data
@NoArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long alertEventId;

    private String sentAt;
    private String recipientName;
    private String recipientEmail;
    private String recipientRole;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String message;

    /** SENT / SKIPPED_DISABLED / FAILED:... */
    private String emailStatus;

    /** SENT / SKIPPED / FAILED:... */
    private String webhookStatus;

    /** INITIAL / ESCALATION / DAILY_REALERT / MANUAL / RESOLUTION */
    private String trigger;

    /** Configured from-address used when sending this email */
    private String emailFrom;

    /** Comma-separated CC addresses, null if none */
    private String cc;
}
