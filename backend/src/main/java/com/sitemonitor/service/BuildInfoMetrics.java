package com.sitemonitor.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Prometheus'a sürüm/dağıtım kimliği (K11, 2026-09-10) — {@link DbGrowthMetrics} deseni.
 *
 * <ul>
 *   <li>{@code sitemonitor_build_info{version,commit,environment} 1} — etiketler süreç ömrünce sabit
 *       (kardinalite 1). Grafana anotasyonu / alarm: {@code changes(sitemonitor_build_info[10m]) > 0}.</li>
 *   <li>{@code sitemonitor_deployment_started_seconds} — JVM başlangıcı (epoch sn); DORA dağıtım
 *       sıklığı ve "bu pod ne zamandır ayakta" bundan okunur.</li>
 * </ul>
 * Belge: {@code docs/GRAFANA_SURUM_ANOTASYON.md}.
 */
@Component
@RequiredArgsConstructor
public class BuildInfoMetrics {

    private final MeterRegistry registry;
    private final BuildInfo buildInfo;

    @PostConstruct
    void register() {
        BuildInfo.Snapshot s = buildInfo.get();
        Gauge.builder("sitemonitor.build.info", () -> 1)
                .tag("version", blankToUnknown(s.version()))
                .tag("commit", blankToUnknown(s.commitShort()))
                .tag("environment", blankToUnknown(s.environment()))
                .description("Build identity of the running instance (constant 1)")
                .register(registry);
        Gauge.builder("sitemonitor.deployment.started.seconds", () -> s.jvmStartEpochMillis() / 1000.0)
                .description("JVM start time of this instance, seconds since epoch")
                .register(registry);
    }

    static String blankToUnknown(String v) {
        return v == null || v.isBlank() ? "unknown" : v;
    }
}
