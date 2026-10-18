package com.sitemonitor.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP sunucu gecikmesi HİSTOGRAMI (2026-10-18, Grafana panosu: p95 gecikme). Boot'un {@code http_server_requests_seconds}
 * ölçümü histogram kovası taşımaz (yalnız count / sum / max) — açmak uç başına ~70 kova demekti. Bu Timer UÇ ETİKETİ
 * TAŞIMAZ; yalnız sonuç sınıfı ({@code outcome}: SUCCESS / REDIRECTION / CLIENT_ERROR / SERVER_ERROR / INFORMATIONAL /
 * UNKNOWN) ve sabit SLO kovaları — pod başına 6 × 13 seri. Panoda:
 * {@code histogram_quantile(0.95, sum by (le) (rate(sitemonitor_http_server_duration_seconds_bucket[5m])))}.
 *
 * <p>Besleyen: {@code HttpMetricsInterceptor.afterCompletion} (aynı ölçüm noktası; {@code /metrics} hariç).
 */
@Component
public class HttpLatencyMetrics {

    /** SLO kova sınırları — tek kaynak (pano da bunlardan "≤ 500 ms oranı" çizer). */
    static final Duration[] SLOS = {
            Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofMillis(1000), Duration.ofMillis(2500), Duration.ofMillis(5000),
            Duration.ofMillis(10000), Duration.ofMillis(30000)};

    public enum Outcome { INFORMATIONAL, SUCCESS, REDIRECTION, CLIENT_ERROR, SERVER_ERROR, UNKNOWN }

    private final Map<Outcome, Timer> timers = new EnumMap<>(Outcome.class);

    public HttpLatencyMetrics(MeterRegistry registry) {
        for (Outcome o : Outcome.values()) {
            timers.put(o, Timer.builder(MonitoringMetricNames.HTTP_DURATION)
                    .description("HTTP server request duration without endpoint tag (SLO buckets)")
                    .tag("outcome", o.name())
                    .serviceLevelObjectives(SLOS)
                    .register(registry));
        }
    }

    /** Durum kodu → sonuç sınıfı (Spring'in {@code Outcome.forStatus} kuralı). */
    static Outcome outcomeOf(int status) {
        if (status >= 100 && status < 200) return Outcome.INFORMATIONAL;
        if (status >= 200 && status < 300) return Outcome.SUCCESS;
        if (status >= 300 && status < 400) return Outcome.REDIRECTION;
        if (status >= 400 && status < 500) return Outcome.CLIENT_ERROR;
        if (status >= 500 && status < 600) return Outcome.SERVER_ERROR;
        return Outcome.UNKNOWN;
    }

    public void record(int status, long durationMs) {
        if (durationMs < 0) return;
        timers.get(outcomeOf(status)).record(durationMs, TimeUnit.MILLISECONDS);
    }
}
