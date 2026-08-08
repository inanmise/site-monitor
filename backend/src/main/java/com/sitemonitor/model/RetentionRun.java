package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Gece temizliğinin (veya elle tetiklenen çalışmanın) özeti. Daha önce silinen satır sayısı
 * HİÇBİR YERDE saklanmıyordu — sessizce çöken bir temizlik ancak disk dolunca fark edilirdi.
 * Bu tablo hem "N saattir çalışmadı" sağlık sinyalini hem ekrandaki çalışma geçmişini besler.
 */
@Entity
@Table(name = "retention_run", indexes = {
        @Index(name = "idx_retrun_started", columnList = "started_at")
})
@Data
public class RetentionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISO-8601 UTC. */
    @Column(name = "started_at", nullable = false, length = 30)
    private String startedAt;

    @Column(name = "finished_at", length = 30)
    private String finishedAt;

    /** true → hiçbir satır silinmedi, yalnız sayım yapıldı. */
    @Column(name = "dry_run", nullable = false)
    private Boolean dryRun = false;

    /** Legal hold açıkken çalışma kaydedilir ama hiçbir silme yapılmaz. */
    @Column(name = "hold_active", nullable = false)
    private Boolean holdActive = false;

    /** Elle tetikleyen kullanıcı; gece işinde null. */
    @Column(name = "triggered_by", length = 100)
    private String triggeredBy;

    @Column(name = "total_deleted")
    private Long totalDeleted = 0L;

    @Column(name = "failed_count")
    private Integer failedCount = 0;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "instance_id", length = 120)
    private String instanceId;
}
