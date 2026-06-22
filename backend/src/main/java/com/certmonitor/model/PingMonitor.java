package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Serbest-form ICMP ping izleme monitörü: bir host'a (IPv4/IPv6) ping atılır;
 * yanıt yoksa alarm. Envantere bağlı değildir; takım {@code teamId} ile atanır.
 */
@Entity
@Table(name = "ping_monitors")
@Data
@NoArgsConstructor
public class PingMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String host;

    /** "auto" | "v4" | "v6" — ping komutu IP sürümü kısıtı. */
    @Column(name = "ip_version", nullable = false)
    private String ipVersion = "auto";

    /** Sorumlu takım — alarm yönlendirmesi. */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 60;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 5000;

    @Column(name = "packet_count")
    private Integer packetCount = 4;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;
}
