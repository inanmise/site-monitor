package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "uptime_checks", indexes = {
    @Index(name = "idx_uc_domain_port", columnList = "domain,port"),
    @Index(name = "idx_uc_checked_at",  columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class UptimeCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String domain;

    @Column(nullable = false)
    private Integer port;

    private String status;      // "up" or "down"

    @Column(name = "response_ms")
    private Long responseMs;

    private String error;

    @Column(name = "checked_at")
    private String checkedAt;   // ISO-8601

    /** Kontrol anında domain bakım penceresindeyse true — uptime % / availability hesabından hariç tutulur. */
    @Column
    private Boolean maintenance = false;
}
