package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Bir ping kontrolünün sonucu (geçmiş). */
@Entity
@Table(name = "ping_checks", indexes = {
    @Index(name = "idx_pingc_monitor_id", columnList = "monitor_id"),
    @Index(name = "idx_pingc_checked_at", columnList = "checked_at")
})
@Data
@NoArgsConstructor
public class PingCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Host yanıt verdi mi (packet_loss < 100). */
    @Column(nullable = false)
    private Boolean up = false;

    /** Ortalama gidiş-dönüş süresi (ms). */
    @Column(name = "rtt_ms")
    private Long rttMs;

    /** Paket kaybı yüzdesi (0–100). */
    @Column(name = "packet_loss")
    private Integer packetLoss;

    private String error;

    @Column(name = "checked_at")
    private String checkedAt;
}
