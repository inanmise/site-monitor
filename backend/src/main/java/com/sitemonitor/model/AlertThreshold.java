package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "alert_thresholds")
@Data
@NoArgsConstructor
public class AlertThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name = "default";

    /** Days remaining at which WARNING alert fires */
    private Integer warningDays = 30;

    /** Days remaining at which HIGH alert fires */
    private Integer highDays = 15;

    /** Days remaining at which CRITICAL alert fires */
    private Integer criticalDays = 7;

    /** Hours to wait before re-alerting on unacknowledged alert */
    private Integer reAlertIntervalHours = 24;

    @Column(nullable = false)
    private Boolean active = true;
}
