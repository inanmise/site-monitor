package com.sitemonitor.service.metrics;

import com.sitemonitor.service.AppSettingsService;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prometheus kazıma adları (2026-10-18): katalogdaki HER ölçüm {@code /metrics} çıktısında {@link MonitoringMetricNames.Def#prom}
 * adıyla görünür — Prometheus istemcisi ayrılmış son ekleri ({@code _total}, {@code _info}, {@code _count} …) gauge adından
 * kesseydi pano sessizce "veri yok" gösterirdi. HTTP gecikme histogramı {@code _bucket}/{@code _count}/{@code _sum} satırlarıyla
 * yayılır. Boş etiket değeri ({@code ug_team=""}) kazımayı bozmaz.
 */
class MonitoringMetricsScrapeTest {

    @Test
    @DisplayName("her katalog ölçümü beklenen Prometheus adıyla kazınır; histogram kovaları var; boş etiket değeri kabul")
    void everyCatalogMetricScrapesUnderItsName() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        MonitoringMetricsExporter exporter = new MonitoringMetricsExporter(registry, settings);
        HttpLatencyMetrics latency = new HttpLatencyMetrics(registry);
        latency.record(200, 42);
        latency.record(503, 1200);

        // Her ölçüme tanımındaki etiketlerle birer satır (bir kısmı boş değerli)
        MonitoringMetricsModel.Families f = new MonitoringMetricsModel.Families();
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            String[] kv = new String[d.labels().size() * 2];
            for (int i = 0; i < d.labels().size(); i++) {
                kv[2 * i] = d.labels().get(i);
                kv[2 * i + 1] = d.labels().get(i).startsWith("ug_") ? "" : "v" + i;
            }
            f.add(d.name(), 1, kv);
        }
        exporter.apply(f);
        String scrape = registry.scrape();

        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?m)^([a-zA-Z_:][a-zA-Z0-9_:]*)[{ ]").matcher(scrape);
        while (m.find()) names.add(m.group(1));
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            assertThat(names).as("kazımada yok: " + d.prom()).contains(d.prom());
        }
        String timer = MonitoringMetricNames.promTimer(MonitoringMetricNames.HTTP_DURATION);
        assertThat(names).contains(timer + "_bucket", timer + "_count", timer + "_sum");
        assertThat(scrape).containsPattern(timer + "_bucket\\{(?=[^}]*le=\"0\\.5\")(?=[^}]*outcome=\"SUCCESS\")[^}]*\\} 1");
        assertThat(scrape).contains("ug_team=\"\"");
        // Gauge son ekleri korunur (ör. *_24h, *_seconds), adlar katalogla birebir
        assertThat(names).contains("sitemonitor_monitor_checks_24h", "sitemonitor_alert_open_since_timestamp_seconds",
                "sitemonitor_storms_active_all", "sitemonitor_teams");
        assertThat(Map.of("ok", List.of())).isNotEmpty();
    }

    @Test
    @DisplayName("sonuç sınıfı: Spring Outcome kuralı")
    void outcome() {
        assertThat(HttpLatencyMetrics.outcomeOf(204)).isEqualTo(HttpLatencyMetrics.Outcome.SUCCESS);
        assertThat(HttpLatencyMetrics.outcomeOf(302)).isEqualTo(HttpLatencyMetrics.Outcome.REDIRECTION);
        assertThat(HttpLatencyMetrics.outcomeOf(404)).isEqualTo(HttpLatencyMetrics.Outcome.CLIENT_ERROR);
        assertThat(HttpLatencyMetrics.outcomeOf(500)).isEqualTo(HttpLatencyMetrics.Outcome.SERVER_ERROR);
        assertThat(HttpLatencyMetrics.outcomeOf(0)).isEqualTo(HttpLatencyMetrics.Outcome.UNKNOWN);
        assertThat(HttpLatencyMetrics.outcomeOf(101)).isEqualTo(HttpLatencyMetrics.Outcome.INFORMATIONAL);
    }
}
