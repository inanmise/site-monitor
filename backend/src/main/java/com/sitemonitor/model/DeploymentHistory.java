package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Dağıtım kaydı — "hangi sürüm hangi ortamda ne zaman DEVREYE alındı" (Sürüm & Dağıtım Geçmişi, 2026-09-10).
 *
 * <p>Uygulama HER pod açılışında kendi satırını yazar ({@code source=STARTUP}); prod dağıtımı elle
 * {@code helm upgrade} olduğundan (CI'da deploy adımı yok) tek güvenilir kaynak budur — elle upgrade'i,
 * geri almayı ve çöküp yeniden başlamayı da görür. Geçmişe dönük satırlar denetimdeki sürümsüz
 * SCHEMA_PATCH izinden gelir ({@code source=BACKFILL, version=NULL} — dürüstlük: tahmin edilmez);
 * bilinen geçişleri yönetici elle girer ({@code source=MANUAL}).
 *
 * <p>Geçiş TÜRÜ (yükseltme/geri alma/yeniden başlatma) SAKLANMAZ, ortam bazında sıralı kayıtlardan
 * türetilir ({@code DeploymentHistoryService.deriveKinds}) — sonradan eklenen MANUAL/BACKFILL satırları
 * türü bayatlatamaz. Zaman damgaları ISO-8601 UTC dize (RetentionRun emsali; backfill audit
 * {@code event_time}'ı dönüşümsüz kopyalar). Sır taşımaz: yalnız sürüm/commit/build/imaj/helm/ortam/
 * düğüm/pod/instance. Asla silinmez (RetentionCatalog BOUNDED); yalnız MANUAL satır yönetici tarafından.
 */
@Data
@Entity
@Table(name = "deployment_history", indexes = {
        @Index(name = "idx_deploy_env_started", columnList = "environment, started_at"),
        @Index(name = "idx_deploy_version", columnList = "version")
})
public class DeploymentHistory {

    public static final String SOURCE_STARTUP  = "STARTUP";
    public static final String SOURCE_BACKFILL = "BACKFILL";
    public static final String SOURCE_MANUAL   = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISO-8601 UTC. STARTUP = JVM başlangıcı; BACKFILL = audit event_time; MANUAL = girilen an. */
    @Column(name = "started_at", nullable = false, length = 30)
    private String startedAt;

    @Column(name = "recorded_at", nullable = false, length = 30)
    private String recordedAt;

    /** dev | staging | prod | local | unknown (Helm APP_ENVIRONMENT). */
    @Column(name = "environment", nullable = false, length = 40)
    private String environment;

    /** NULL = sürüm bilinmiyor (yalnız BACKFILL). */
    @Column(name = "version", length = 64)
    private String version;

    @Column(name = "image_version", length = 64)
    private String imageVersion;

    @Column(name = "git_commit", length = 64)
    private String gitCommit;

    @Column(name = "build_time", length = 30)
    private String buildTime;

    @Column(name = "image_ref", length = 300)
    private String imageRef;

    @Column(name = "helm_release", length = 120)
    private String helmRelease;

    @Column(name = "helm_revision")
    private Integer helmRevision;

    @Column(name = "helm_chart_version", length = 64)
    private String helmChartVersion;

    @Column(name = "config_checksum", length = 80)
    private String configChecksum;

    @Column(name = "instance_id", length = 120)
    private String instanceId;

    @Column(name = "hostname", length = 120)
    private String hostname;

    @Column(name = "pod_name", length = 120)
    private String podName;

    @Column(name = "node_name", length = 120)
    private String nodeName;

    @Column(name = "java_version", length = 40)
    private String javaVersion;

    /** ACCEPTING_TRAFFIC anı (bootstrap bitti). */
    @Column(name = "ready_at", length = 30)
    private String readyAt;

    /** Heartbeat ile 60 sn'de bir tazelenir; hard-kill'de ended_at yoksa ekran bunu kullanır. */
    @Column(name = "last_seen_at", length = 30)
    private String lastSeenAt;

    @Column(name = "ended_at", length = 30)
    private String endedAt;

    /** graceful | crash | failed-start | unknown */
    @Column(name = "end_reason", length = 40)
    private String endReason;

    /** STARTUP | BACKFILL | MANUAL */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** BACKFILL: kaynak audit_log.id (idempotency; kısmi tekil indeks applySchemaPatches'te). */
    @Column(name = "audit_ref")
    private Long auditRef;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "note", length = 500)
    private String note;
}
