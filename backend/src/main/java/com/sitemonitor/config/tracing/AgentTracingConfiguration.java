package com.sitemonitor.config.tracing;

import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ajan kipi (2026-10-21): {@link TracingCoexistenceEnvironmentPostProcessor} OpenTelemetry Java ajanını algılayıp izi ona
 * bıraktığında ({@value TracingCoexistence#AGENT_ACTIVE_PROPERTY}=true) uygulamanın OpenTelemetry'si no-op olur.
 *
 * <p><b>Neden.</b> Boot'un {@code OpenTelemetrySdkAutoConfiguration}'ı {@code @ConditionalOnMissingBean(OpenTelemetry)}
 * ile geri çekilir; Micrometer'ın {@code OtelTracer}'ı bu no-op izleyiciyle çalışır. No-op izleyici YENİ span açmaz:
 * ebeveynin (o anki) span bağlamını sarar. Ajan uygulamanın OpenTelemetry API bağlamını kendi bağlamına köprülediği
 * için "o anki span" ajanın span'ıdır → günlükteki Micrometer {@code traceId}/{@code spanId} ajanınkiyle aynıdır, ajanın
 * JDBC / Hibernate span'ları ajanın sunucu span'ının çocuğu kalır ve dışa aktarılmayan ikinci bir span ağacı oluşmaz.
 * Ajan yokken bu sınıf hiç devreye girmez (Boot'un SDK'sı eskisi gibi).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(TracingCoexistence.AGENT_ACTIVE_PROPERTY)
public class AgentTracingConfiguration {

    @Bean
    OpenTelemetry openTelemetry() {
        return OpenTelemetry.noop();
    }
}
