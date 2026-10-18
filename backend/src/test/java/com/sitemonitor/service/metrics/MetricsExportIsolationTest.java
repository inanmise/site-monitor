package com.sitemonitor.service.metrics;

import com.sitemonitor.service.AppSettingsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KAPI — iş verisi kimliksiz {@code /metrics}'e SIZMAZ (2026-10-18, bağımsız inceleme: izleme adları, hedefler, takımlar ve
 * alarm kimlikleri Boot'un küresel kayıt defterinden kimliksiz {@code /metrics}'te kurum ağına açıktı).
 *
 * <ol>
 *   <li>Aktarıcının Spring kurucusu küresel {@link MeterRegistry}'yi ALMAZ — yalnız {@link BusinessMetricsRegistry}.</li>
 *   <li>Kaynakta iş kataloğu ({@link MonitoringMetricNames}) yalnız aktarıcıda Micrometer'a gider (HTTP gecikmesi yalnız
 *       {@code HTTP_DURATION}); katalog adları başka Micrometer dosyasında dize olarak geçmez.</li>
 *   <li>Davranış: küresel kayıt defteri (= {@code /metrics}) ile iş kayıt defteri ayrı kazınır — küreselde
 *       {@code sitemonitor_monitor}, {@code target=}, takım adı YOK; iş kazımasında var. HTTP gecikme histogramı (yalnız
 *       {@code outcome} etiketi) {@code /metrics}'te kalır.</li>
 * </ol>
 */
class MetricsExportIsolationTest {

    static final Path MAIN = Path.of("src", "main", "java", "com", "sitemonitor");

    @Test
    @DisplayName("Spring kurucusu küresel MeterRegistry almaz; iş ölçümleri yalnız BusinessMetricsRegistry'de")
    void springConstructorTakesOnlyTheBusinessRegistry() {
        List<Constructor<?>> autowired = Arrays.stream(MonitoringMetricsExporter.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(Autowired.class)).toList();
        assertThat(autowired).hasSize(1);
        List<Class<?>> params = Arrays.asList(autowired.get(0).getParameterTypes());
        assertThat(params).contains(BusinessMetricsRegistry.class, MetricsExportToken.class);
        assertThat(params.stream().noneMatch(MeterRegistry.class::isAssignableFrom)).isTrue();
        // BusinessMetricsRegistry bir Spring MeterRegistry bean'i değil (Boot onu bileşik kayıt defterine katmasın)
        assertThat(MeterRegistry.class.isAssignableFrom(BusinessMetricsRegistry.class)).isFalse();
    }

    @Test
    @DisplayName("kaynak taraması: iş kataloğu yalnız aktarıcıda Micrometer'a gider; katalog adları başka Micrometer dosyasında yok")
    void businessMetersAreRegisteredOnlyByTheExporter() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString();
                String src = Files.readString(f, StandardCharsets.UTF_8);
                boolean micrometer = src.contains("io.micrometer");
                // Micrometer kullanıp iş kataloğuna dokunan tek dosyalar: aktarıcı (iş kayıt defteri) ve HTTP gecikmesi
                // (küresel; yalnız uç etiketsiz HTTP_DURATION Timer'ı)
                if (micrometer && src.contains("MonitoringMetricNames.") && !name.equals("MonitoringMetricsExporter.java")
                        && !name.equals("MonitoringMetricNames.java")) {
                    assertThat(name).as("iş kataloğunu Micrometer ile kullanan beklenmeyen dosya: " + f).isEqualTo("HttpLatencyMetrics.java");
                    assertThat(src.replace("MonitoringMetricNames.HTTP_DURATION", "")).as("HttpLatencyMetrics yalnız HTTP_DURATION")
                            .doesNotContain("MonitoringMetricNames.");
                }
                if (micrometer && !name.equals("MonitoringMetricsExporter.java") && !name.equals("MonitoringMetricNames.java")
                        && !name.equals("BusinessMetricsRegistry.java")) {
                    for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
                        assertThat(src).as("iş ölçümü küresel kayıt defterine yazılıyor olabilir: " + d.name() + " → " + f)
                                .doesNotContain("\"" + d.name() + "\"");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("davranış: küresel kazımada (/metrics) iş verisi yok — iş kazımasında (/metrics/sitemonitor) var")
    void globalScrapeHasNoBusinessData() {
        PrometheusMeterRegistry global = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new HttpLatencyMetrics(global).record(200, 12);
        BusinessMetricsRegistry business = new BusinessMetricsRegistry();
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        MonitoringMetricsExporter exporter = new MonitoringMetricsExporter(business, settings,
                MetricsExportToken.of("isolation-token-0123456789-0123456789"));

        MonitoringMetricsModel.Families f = new MonitoringMetricsModel.Families();
        f.add(MonitoringMetricNames.MONITOR_STATE, 2, "type", "http", "team", "Dijital Kanallar", "team_id", "14", "group", "Ödeme",
                "monitor", "Ödeme sayfası", "monitor_id", "7", "target", "https://pay.example.com/odeme");
        f.add(MonitoringMetricNames.MONITORS, 1, "type", "http", "team", "Dijital Kanallar", "team_id", "14", "state", "down");
        exporter.apply(f);

        String metrics = global.scrape();
        assertThat(metrics).contains("sitemonitor_http_server_duration_seconds_bucket");
        assertThat(metrics).doesNotContain("sitemonitor_monitor").doesNotContain("target=").doesNotContain("Dijital Kanallar")
                .doesNotContain("pay.example.com").doesNotContain("sitemonitor_exporter");
        String inventory = business.scrape(null);
        assertThat(inventory).contains("sitemonitor_monitor_state{").contains("target=\"https://pay.example.com/odeme\"")
                .contains("Dijital Kanallar").doesNotContain("sitemonitor_http_server_duration");
        business.close();
    }
}
