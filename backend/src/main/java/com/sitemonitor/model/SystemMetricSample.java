package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SiteMonitor'ün KENDİ çalışma metrikleri — pod başına dakikada bir örnek (Sistem Sağlığı → Metrikler, 2026-10-13, kullanıcı
 * isteği: "site monitör metriklerini detaylı izlemek istiyorum"). Her pod kendi satırını yazar ({@code instance_id}); okuma
 * SQL'de kovalara toplanır. Anlık değerler (gauge) o dakikanın değeridir; {@code *_delta} alanları önceki örnekten bu yana
 * artıştır (ilk örnek taban kurar → null). Bilinmeyen değer null (asla 0 diye uydurulmaz).
 */
@Entity
@Table(name = "system_metric_samples", indexes = {
        @Index(name = "idx_sms_sampled", columnList = "sampled_at"),
        @Index(name = "idx_sms_instance_sampled", columnList = "instance_id,sampled_at")
})
@Data
@NoArgsConstructor
public class SystemMetricSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Pod kimliği ({@code SchedulerService.instanceId()}: makine adı + rastgele ek). */
    @Column(name = "instance_id", nullable = false, length = 80)
    private String instanceId;

    /** Gösterim adı (POD_NAME ya da makine adı). */
    @Column(name = "pod_name", length = 120)
    private String podName;

    /** Dakika — ISO-8601 UTC "yyyy-MM-dd'T'HH:mm:00" (sıralanabilir metin; kovalar önek uzunluğuyla). */
    @Column(name = "sampled_at", nullable = false, length = 25)
    private String sampledAt;

    @Column(name = "uptime_s") private Long uptimeS;

    // ── JVM / süreç ──
    @Column(name = "cpu_process") private Double cpuProcess;
    @Column(name = "cpu_system") private Double cpuSystem;
    @Column(name = "heap_used_mb") private Integer heapUsedMb;
    @Column(name = "heap_max_mb") private Integer heapMaxMb;
    @Column(name = "non_heap_mb") private Integer nonHeapMb;
    @Column(name = "threads") private Integer threads;
    @Column(name = "gc_pause_ms_delta") private Long gcPauseMsDelta;
    @Column(name = "gc_count_delta") private Long gcCountDelta;

    // ── Veritabanı bağlantı havuzu (Hikari) ──
    @Column(name = "db_active") private Integer dbActive;
    @Column(name = "db_idle") private Integer dbIdle;
    @Column(name = "db_pending") private Integer dbPending;
    @Column(name = "db_max") private Integer dbMax;

    // ── Kontrol görev havuzu (certCheckExecutor) ──
    @Column(name = "exec_active") private Integer execActive;
    @Column(name = "exec_pool") private Integer execPool;
    @Column(name = "exec_max") private Integer execMax;
    @Column(name = "exec_queue") private Integer execQueue;
    @Column(name = "exec_queue_cap") private Integer execQueueCap;
    @Column(name = "exec_completed_delta") private Long execCompletedDelta;
    @Column(name = "exec_caller_runs_delta") private Long execCallerRunsDelta;

    // ── HTTP (bu podun /api istekleri, biten dakika) ──
    @Column(name = "http_requests") private Long httpRequests;
    @Column(name = "http_errors") private Long httpErrors;
    @Column(name = "http_sum_ms") private Long httpSumMs;
    @Column(name = "http_p95_ms") private Long httpP95Ms;
    @Column(name = "http_max_ms") private Long httpMaxMs;
    /** Bu örneğin kapsadığı HTTP dakikası (önceki tüketilen dakikadan en yeniye; sessiz dakikalar dahil); istek/dk = istek ÷ bu. */
    @Column(name = "http_minutes") private Integer httpMinutes;
    /** Önceki örnekten bu yana geçen dakika (taban örnekte null) — sayaç oranı = artış ÷ bu sayı (geciken tick sıçratmaz). */
    @Column(name = "delta_minutes") private Integer deltaMinutes;
    /** Düzgün kapanışta yazılan son satır: pod durdu (dağıtım / ölçekleme) — sessiz ya da takılı pod değil. */
    @Column(name = "shutdown_marker") private Boolean shutdownMarker;

    // ── Günlük olayları + önbellek ──
    @Column(name = "log_warn_delta") private Long logWarnDelta;
    @Column(name = "log_error_delta") private Long logErrorDelta;
    @Column(name = "cache_hits_delta") private Long cacheHitsDelta;
    @Column(name = "cache_misses_delta") private Long cacheMissesDelta;

    /**
     * Saat farkı (2026-10-23, çok pod R2b, MP-37c): bu podun saati − veritabanı saati (ms; pozitif = pod ileride), dakikalık
     * örnekte tek {@code SELECT CURRENT_TIMESTAMP} gidiş-dönüşünün ortasından. Ölçülemedi / gidiş-dönüş çok uzun → null.
     * Kira ve kilit SQL'i pod saatiyle karşılaştırır — kendi kendini izleme {@code clock_skew} kontrolü bunu okur.
     * {@code SystemMetricsSchemaPatch} ekler; eski pod'un satırlarında null.
     */
    @Column(name = "clock_skew_ms") private Long clockSkewMs;

    /**
     * Bu pod'un açılışında BAŞARISIZ olan şema yaması sayısı (2026-10-23, çok pod R2b, inceleme W-3) — kendi kendini izleme
     * veritabanı kontrolü "herhangi bir canlı pod'da başarısız yama" kararını buradan verir. Yamalar henüz koşmadı → null.
     */
    @Column(name = "schema_failed_patches") private Integer schemaFailedPatches;
}
