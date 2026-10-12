package com.sitemonitor.config;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenTelemetry izleme (2026-10-11): iz (trace) her zaman üretilir (traceId günlükte), ama OTLP dışa aktarımı YALNIZ uç
 * nokta verilince kurulur — uç noktasız kurulum hiçbir yere bağlanmaya çalışmaz. Gönderici JDK HttpClient'tır (OkHttp
 * sınıf yolunda yok; exporter yine de kurulabilmeli).
 */
class TracingExportConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    OpenTelemetrySdkAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=site-monitor");

    @Test
    @DisplayName("uç nokta yok → izleyici var, OTLP span ihracatçısı YOK")
    void noEndpointNoExport() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(Tracer.class);
            assertThat(ctx).doesNotHaveBean(OtlpHttpSpanExporter.class);
        });
    }

    @Test
    @DisplayName("uç nokta verilince OTLP/HTTP ihracatçısı JDK göndericiyle kurulur")
    void endpointEnablesExport() {
        runner.withPropertyValues("management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(OtlpHttpSpanExporter.class);
                    assertThat(ctx.getBeansOfType(SpanExporter.class)).isNotEmpty();
                });
        assertThat(classPresent("okhttp3.OkHttpClient")).as("OkHttp imaja girmemeli").isFalse();
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, TracingExportConfigTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
