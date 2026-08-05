package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "network_outage_events",
    indexes = {
        @Index(name = "idx_noe_detected",  columnList = "detectedAt"),
        @Index(name = "idx_noe_status",    columnList = "status")
    }
)
@Data
@NoArgsConstructor
public class NetworkOutageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String detectedAt;

    private String resolvedAt;

    private Integer networkErrors;
    private Integer totalChecks;
    private Double  errorRate;
    private Double  threshold;
    private Long    durationMs;

    /** ONGOING or RESOLVED */
    @Column(nullable = false, length = 16)
    private String status;
}
