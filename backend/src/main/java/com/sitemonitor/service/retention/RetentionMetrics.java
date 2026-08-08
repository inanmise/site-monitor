package com.sitemonitor.service.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Temizliğin Prometheus metrikleri. Bir izleme ürününün kendi temizlik job'ını izlememesi ironisini
 * kapatır: sessizce çöken bir cleanup disk dolduğunda değil, ertesi sabah grafikte fark edilmeli.
 *
 * <p>Adlandırma {@code DbGrowthMetrics}'teki {@code db.table.rows} deseniyle uyumludur:
 * {@code retention_rows_deleted_total{policy,table}}, {@code retention_run_duration_seconds},
 * {@code retention_last_success_epoch}, {@code retention_failed_policies}.
 */
@Slf4j
@Component
public class RetentionMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicLong lastSuccessEpoch = new AtomicLong(0);
    private final AtomicLong lastFailedCount = new AtomicLong(0);
    private final AtomicLong lastDeletedTotal = new AtomicLong(0);
    private final Timer runTimer;

    public RetentionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.runTimer = Timer.builder("retention.run.duration")
                .description("Gece temizliği toplam süresi")
                .register(registry);
        registry.gauge("retention.last.success.epoch", lastSuccessEpoch, AtomicLong::doubleValue);
        registry.gauge("retention.failed.policies", lastFailedCount, AtomicLong::doubleValue);
        registry.gauge("retention.last.deleted.rows", lastDeletedTotal, AtomicLong::doubleValue);
    }

    /** Gerçek (dry-run olmayan) bir çalışma bittiğinde çağrılır. */
    public void recordRun(RetentionService.RunResult run) {
        try {
            runTimer.record(run.durationMs(), TimeUnit.MILLISECONDS);
            lastSuccessEpoch.set(Instant.now().getEpochSecond());
            lastFailedCount.set(run.failedCount());
            lastDeletedTotal.set(run.totalRows());
            for (RetentionService.ItemResult it : run.items()) {
                if (it.rows() <= 0) continue;
                counters.computeIfAbsent(it.policyId(), id -> Counter.builder("retention.rows.deleted")
                                .description("Saklama politikasınca silinen satır sayısı")
                                .tag("policy", id)
                                .tag("table", it.table())
                                .register(registry))
                        .increment(it.rows());
            }
        } catch (Exception e) {
            log.debug("Retention metrikleri yazılamadı: {}", e.getMessage());
        }
    }

    /** Test/sağlık için: son başarılı çalışmanın epoch saniyesi (0 = hiç çalışmadı). */
    public long lastSuccessEpoch() {
        return lastSuccessEpoch.get();
    }
}
