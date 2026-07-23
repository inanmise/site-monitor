package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "system_heartbeat",
       indexes = @Index(name = "idx_shb_recorded_at", columnList = "recordedAt"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
}
