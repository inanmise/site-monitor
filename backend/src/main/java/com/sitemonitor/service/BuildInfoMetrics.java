package com.sitemonitor.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Prometheus'a sürüm/dağıtım kimliği (K11, 2026-09-10) — {@link DbGrowthMetrics} deseni.
 *
 * <ul>
 *   <li>{@code sitemonitor_build_info{version,commit,environment} 1} — kardinalite 1. {@code version}/{@code commit}
 *       süreç ömrünce sabit; {@code environment} Ayarlar → Genel Ayarlar'daki ortam adını izler (2026-09-29).
 *       Grafana anotasyonu / alarm: {@code changes(sitemonitor_build_info[10m]) > 0}.</li>
 *   <li>{@code sitemonitor_deployment_started_seconds} — JVM başlangıcı (epoch sn); DORA dağıtım
 *       sıklığı ve "bu pod ne zamandır ayakta" bundan okunur.</li>
 * </ul>
 *
 * <p><b>Ortam adı değişince etiket neden değişiyor:</b> bir Micrometer gauge'unun etiketi sonradan değiştirilemez;
 * açılış değerinde bırakmak, uygulama "prod" derken panoların {@code environment="unknown"} süzmesi demekti (bir
 * sonraki yeniden başlatmaya kadar). {@link MultiGauge} satırı {@code overwrite=true} ile yeniden kaydedilir: eski
 * seri silinir, yeni etiketli seri doğar — kardinalite yine 1. Bedeli: adlandırma anında yeni seri doğduğu için
 * {@code changes()} anotasyonu BİR KEZ tetiklenir (belge: {@code docs/GRAFANA_SURUM_ANOTASYON.md} §5).
 * Belge: {@code docs/GRAFANA_SURUM_ANOTASYON.md}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildInfoMetrics {

    private final MeterRegistry registry;
    private final BuildInfo buildInfo;

    private MultiGauge buildInfoGauge;
    private volatile String registeredEnv;

    @PostConstruct
    void register() {
        BuildInfo.Snapshot s = buildInfo.get();
        buildInfoGauge = MultiGauge.builder("sitemonitor.build.info")
                .description("Build identity of the running instance (constant 1)")
                .register(registry);
        registerBuildInfoRow(s);
        Gauge.builder("sitemonitor.deployment.started.seconds", () -> s.jvmStartEpochMillis() / 1000.0)
                .description("JVM start time of this instance, seconds since epoch")
                .register(registry);
    }

    /** Ortam adı ayarı değişti (bu pod'da kayıt ya da başka pod'un kaydı ~10 sn tazelemeyle) → etiketi yenile. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onSettingsChanged(AppSettingsChangedEvent ev) {
        if (ev == null || !ev.touches(BuildInfo.ENV_KEY)) return;
        refresh();
    }

    /** Etiket canlı ortam adından farklıysa satırı yeniden kaydeder; aynıysa hiçbir şey yapmaz. */
    synchronized void refresh() {
        if (buildInfoGauge == null) return;
        try {
            BuildInfo.Snapshot s = buildInfo.get();
            if (blankToUnknown(s.environment()).equals(registeredEnv)) return;
            registerBuildInfoRow(s);
            log.info("sitemonitor_build_info environment label is now '{}'", registeredEnv);
        } catch (Exception e) {
            log.warn("build_info environment label refresh failed: {}", e.toString());
        }
    }

    private void registerBuildInfoRow(BuildInfo.Snapshot s) {
        String env = blankToUnknown(s.environment());
        buildInfoGauge.register(List.of(MultiGauge.Row.of(Tags.of(
                "version", blankToUnknown(s.version()),
                "commit", blankToUnknown(s.commitShort()),
                "environment", env), 1)), true);
        registeredEnv = env;
    }

    static String blankToUnknown(String v) {
        return v == null || v.isBlank() ? "unknown" : v;
    }
}
