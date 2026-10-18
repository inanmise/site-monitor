package com.sitemonitor.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * İş ölçümlerinin AYRI kayıt defteri (2026-10-18, bağımsız inceleme). {@code sitemonitor_monitor*}, alarm, sertifika / alan adı,
 * bildirim / fırtına / push ve aktarıcının kendi ölçümleri BURAYA yazılır — Boot'un küresel kayıt defterine (kimliksiz
 * {@code /metrics}) DEĞİL. Yalnız {@code /metrics/sitemonitor} ucu taşıyıcı belirteçle ({@link MetricsExportToken}) kazır.
 *
 * <p><b>Bilinçli olarak Spring {@link MeterRegistry} bean'i DEĞİL:</b> bean olsaydı Boot'un bileşik kayıt defteri onu da
 * içerir, {@code MeterRegistryCustomizer} / bağlayıcılar (JVM, HTTP) buraya da yazar ve Prometheus otomatik yapılandırması
 * ikinci bir {@code PrometheusMeterRegistry} gördüğünde geri çekilirdi. Burada yalnız aktarıcının ölçümleri vardır.
 */
@Component
public class BusinessMetricsRegistry {

    /** Prometheus metin biçimi (0.0.4) — her Prometheus sürümü okur. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    /** Aktarıcının yazdığı kayıt defteri. */
    public MeterRegistry meters() {
        return registry;
    }

    /**
     * Kazıma çıktısı. {@code includedNames} null → tümü; değilse yalnız bu Prometheus adlarının aileleri (aktarıcı bayatken iş
     * değerleri gizlenir — {@link MonitoringMetricsExporter#visibleNames()}).
     */
    public String scrape(Set<String> includedNames) {
        return includedNames == null ? registry.scrape(CONTENT_TYPE) : registry.scrape(CONTENT_TYPE, includedNames);
    }

    @PreDestroy
    public void close() {
        registry.close();
    }
}
