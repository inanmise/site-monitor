package com.sitemonitor.model;

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

    /**
     * Heartbeat'i yazan pod'un örnek kimliği ({@code SchedulerService.instanceId()}; 2026-10-23, çok pod R2b, MP-35).
     * NULL = bu sürümden önce yazılmış satır (pod'u bilinmiyor): küme zaman çizelgesinde sayılır, pod listesine girmez.
     */
    @Column(name = "instance_id", length = 120)
    private String instanceId;

    /**
     * Düzgün kapanış işareti (TRUE; normal heartbeat'te NULL) — pod "durdu" (dağıtım / ölçekleme, uyarı değil) ile "sessiz"
     * (çöktü / takıldı, uyarı) ayrımı. Sayımlara KATILMAZ.
     */
    @Column(name = "shutdown_marker")
    private Boolean shutdownMarker;

    /** Eski iki alanlı kurucu (testler, eski çağrılar) — pod bilinmiyor, işaret yok. */
    public SystemHeartbeat(Long id, LocalDateTime recordedAt) {
        this.id = id;
        this.recordedAt = recordedAt;
    }
}
