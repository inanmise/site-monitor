package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "port_checks", indexes = {
    @Index(name = "idx_portc_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_portc_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class PortCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    @Column(nullable = false)
    private Boolean open = false;

    @Column(name = "response_ms")
    private Long responseMs;

    @Column(name = "checked_at")
    private String checkedAt;

    private String error;
}
