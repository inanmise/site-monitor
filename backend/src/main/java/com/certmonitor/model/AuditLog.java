package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "audit_log", indexes = {
    @Index(name = "idx_audit_actor",      columnList = "actor"),
    @Index(name = "idx_audit_event_time", columnList = "event_time"),
    @Index(name = "idx_audit_event_type", columnList = "event_type"),
    @Index(name = "idx_audit_ip",         columnList = "ip_address"),
    @Index(name = "idx_audit_outcome",    columnList = "outcome")
})
@Data
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "event_time", nullable = false, length = 30)
    private String eventTime;

    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_team_id")
    private Long actorTeamId;

    @Column(name = "actor_role", length = 20)
    private String actorRole;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "ip_country", length = 100)
    private String ipCountry;

    @Column(name = "ip_city", length = 100)
    private String ipCity;

    @Column(name = "ip_org", length = 200)
    private String ipOrg;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_id", length = 200)
    private String resourceId;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "outcome", length = 20)
    private String outcome;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    /** Comma-separated flags: OFF_HOURS, UNUSUAL_IP, GEO_VELOCITY, BRUTE_FORCE, RATE_LIMITED */
    @Column(name = "anomaly_flags", length = 200)
    private String anomalyFlags;

    /** Login anında reverse-DNS (PTR) ile çözünen hostname — varsa. Gösterimde tekrar nslookup
     *  yapılmaz; null ise yalnız IP gösterilir. */
    @Column(name = "ip_reverse_host", length = 255)
    private String ipReverseHost;
}
