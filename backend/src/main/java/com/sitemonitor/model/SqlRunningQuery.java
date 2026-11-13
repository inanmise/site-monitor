package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Çalışan SQL Playground sorgusu (2026-11-13, A2) — başka bir pod'a düşen "durdur" isteği sorguyu bu satırdan bulur
 * ({@code SqlRunRegistry}). Satır sorgu başlarken yazılır, bitince silinir; ölen pod'un kalıntısı 3 dakika sonra temizlenir.
 * Yalnız JDBC ile yazılır; varlık şemayı kurar.
 */
@Entity
@Table(name = "sql_running_queries", indexes = @Index(name = "idx_srq_started", columnList = "startedMs"))
@Getter @Setter @NoArgsConstructor
public class SqlRunningQuery {

    /** İstemcinin ürettiği çalıştırma kimliği ({@code [A-Za-z0-9-]{8,64}}). */
    @Id
    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "owner_username", nullable = false, length = 100)
    private String ownerUsername;

    /** Sorguyu çalıştıran pod örneği ({@code SchedulerService.instanceId()}). */
    @Column(name = "instance_id", length = 100)
    private String instanceId;

    @Column(name = "pod", length = 100)
    private String pod;

    /** PostgreSQL arka uç pid'i (H2'de null). */
    @Column(name = "backend_pid")
    private Integer backendPid;

    /** İşlem başına {@code SET LOCAL application_name} etiketi — pid geri dönüşümüne karşı eşleşme anahtarı. */
    @Column(name = "app_name", length = 64)
    private String appName;

    /** execute | explain | export. */
    @Column(name = "kind", length = 16)
    private String kind;

    @Column(name = "sql_fingerprint", length = 32)
    private String sqlFingerprint;

    @Column(name = "started_ms")
    private Long startedMs;

    @Column(name = "started_at", length = 30)
    private String startedAt;

    @Column(name = "cancel_requested")
    private Boolean cancelRequested;
}
