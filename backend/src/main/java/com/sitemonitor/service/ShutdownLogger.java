package com.sitemonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShutdownLogger {

    private final SchedulerService   schedulerService;
    private final HttpMetricsService httpMetricsService;
    private final MetricsService     metricsService;

    // Bir çökme (crash) kapanmayı tetiklerse UncaughtExceptionHandler tarafından ayarlanır
    private static final AtomicReference<String> CRASH_REASON = new AtomicReference<>();
    private final AtomicBoolean logged = new AtomicBoolean(false);

    // ── Başlatma ──────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        installUncaughtExceptionHandler();
        installJvmShutdownHook();
    }

    // ── Spring yaşam döngüsü olayları ─────────────────────────────────────────

    /** Nazik (graceful) kapanışta tetiklenir (SIGTERM, Spring context close). Bean'ler hâlâ canlıdır. */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        String reason = CRASH_REASON.get() != null
                ? "crash — " + CRASH_REASON.get()
                : "graceful shutdown (SIGTERM / context close)";
        writeShutdownLog(reason, true);
    }

    /** Uygulama başlatılamadığında tetiklenir. */
    @EventListener(ApplicationFailedEvent.class)
    public void onApplicationFailed(ApplicationFailedEvent event) {
        Throwable cause = event.getException();
        log.error("═══ APPLICATION STARTUP FAILED ═══ ts={}", Instant.now());
        log.error("[STARTUP-FAIL] {}: {}", cause.getClass().getName(), cause.getMessage(), cause);
    }

    // ── Dahili ────────────────────────────────────────────────────────────────

    private void installUncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            String reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            CRASH_REASON.compareAndSet(null, reason);

            log.error("═══ UNCAUGHT EXCEPTION ═══ thread='{}' ts={}", thread.getName(), Instant.now());
            log.error("[CRASH] {}", reason, ex);
            logJvmState();   // hafif — Spring bean'i yok

            if (previous != null) previous.uncaughtException(thread, ex);
        });
    }

    private void installJvmShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // ContextClosedEvent nazik kapanışı zaten ele aldı — burada yalnız sert (hard) çıkışlar için çalışır
            if (!logged.get()) {
                String reason = CRASH_REASON.get() != null
                        ? "crash — " + CRASH_REASON.get()
                        : "System.exit() or JVM termination (no Spring event received)";
                writeShutdownLog(reason, false);
            }
        }, "shutdown-logger"));
    }

    /**
     * @param springAlive ContextClosedEvent'ten çağrıldığında true (Spring bean'leri kullanılabilir),
     *                    JVM shutdown hook'undan çağrıldığında false.
     */
    private void writeShutdownLog(String reason, boolean springAlive) {
        if (!logged.compareAndSet(false, true)) return;

        log.warn("═══ APPLICATION SHUTTING DOWN ═══ reason=\"{}\" ts={}", reason, Instant.now());

        // Scheduler — AtomicRef alanları, her zaman güvenli
        try {
            Map<String, Object> sched = schedulerService.getShutdownSnapshot();
            log.warn("[SHUTDOWN] Scheduler: instance={} running={} lastRun={} runId={}",
                    sched.get("instance"), sched.get("running"),
                    sched.get("last_run"), sched.get("run_id"));
        } catch (Exception e) {
            log.warn("[SHUTDOWN] Scheduler state unavailable: {}", e.getMessage());
        }

        // HTTP metrikleri — tamamen bellek-içi, her zaman güvenli
        try {
            Map<String, Object> http = httpMetricsService.getSummary();
            log.warn("[SHUTDOWN] HTTP (24h): requests={} errors={} errorRate={}% avgMs={} maxMs={}",
                    http.get("total_requests"), http.get("total_errors"),
                    http.get("error_rate_pct"), http.get("avg_ms"), http.get("max_ms"));
        } catch (Exception e) {
            log.warn("[SHUTDOWN] HTTP metrics unavailable: {}", e.getMessage());
        }

        // Son JVM/CPU örneği — bellek-içi, her zaman güvenli
        try {
            var history = metricsService.getHistory();
            if (!history.isEmpty()) {
                var last = history.get(history.size() - 1);
                log.warn("[SHUTDOWN] Last metrics sample: ts={} cpu={}% heap={}% heapUsed={}MB threads={}",
                        last.get("ts"), last.get("cpu_process"),
                        last.get("heap_pct"), last.get("heap_used_mb"), last.get("threads"));
            }
        } catch (Exception e) {
            log.warn("[SHUTDOWN] Metrics sample unavailable: {}", e.getMessage());
        }

        logJvmState();

        log.warn("═══ END SHUTDOWN LOG ═══");
    }

    /** Tamamen static — Spring bean'i yok, her thread'den her an güvenli. */
    private static void logJvmState() {
        try {
            Runtime rt      = Runtime.getRuntime();
            long usedMb     = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMb      = rt.maxMemory() / (1024 * 1024);
            int  usedPct    = maxMb > 0 ? (int)(usedMb * 100 / maxMb) : 0;
            int  threads    = ManagementFactory.getThreadMXBean().getThreadCount();
            long gcTotalMs  = ManagementFactory.getGarbageCollectorMXBeans()
                    .stream().mapToLong(gc -> gc.getCollectionTime()).sum();

            log.warn("[SHUTDOWN] JVM: heapUsed={}MB heapMax={}MB pct={}% threads={} gcTotalMs={}",
                    usedMb, maxMb, usedPct, threads, gcTotalMs);
        } catch (Exception e) {
            log.warn("[SHUTDOWN] JVM state unavailable: {}", e.getMessage());
        }
    }
}
