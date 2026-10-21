package com.sitemonitor.config.tracing;

import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;
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
 *
 * <p><b>Geçersiz bağlam MDC'ye yazılmaz.</b> O an ajan span'ı yoksa (ör. ajanın izlemediği bir zamanlanmış iş) no-op
 * izleyici GEÇERSİZ bağlamı sarar; Boot'un {@code Slf4JEventListener}'ı bunu {@code traceId=000…0} /
 * {@code spanId=000…0} olarak MDC'ye koyardı ve JSON günlük (prod) sahte kimlik basardı — ayrıca ajanın MDC anahtarı
 * {@code traceId}'ye çevrildiğinde (Helm) ajan, anahtar dolu diye kendi kimliğini EKLEMEZDİ. Bu kipte Boot'un dinleyicisi
 * yerine ({@code @ConditionalOnMissingBean}) {@link ValidSpanSlf4JEventListener} kurulur.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(TracingCoexistence.AGENT_ACTIVE_PROPERTY)
public class AgentTracingConfiguration {

    @Bean
    OpenTelemetry openTelemetry() {
        return OpenTelemetry.noop();
    }

    @Bean
    Slf4JEventListener agentModeSlf4JEventListener() {
        return new ValidSpanSlf4JEventListener();
    }

    /**
     * Micrometer'ın MDC dinleyicisi — geçersiz (tamamı sıfır) span bağlamında anahtarları YAZMAZ, kaldırır (kapsam
     * dışındaki bir değer de kalmasın). Geçerli bağlam ve kapsam kapanışı Micrometer'ın kendi davranışıdır.
     */
    static final class ValidSpanSlf4JEventListener extends Slf4JEventListener {

        static final String TRACE_ID_KEY = "traceId";
        static final String SPAN_ID_KEY = "spanId";

        @Override
        public void onEvent(Object event) {
            Span span = null;
            boolean scoped = false;
            if (event instanceof EventPublishingContextWrapper.ScopeAttachedEvent attached) {
                span = attached.getSpan();
                scoped = true;
            } else if (event instanceof EventPublishingContextWrapper.ScopeRestoredEvent restored) {
                span = restored.getSpan();
                scoped = true;
            }
            if (scoped && span != null && !span.getSpanContext().isValid()) {
                MDC.remove(TRACE_ID_KEY);
                MDC.remove(SPAN_ID_KEY);
                return;
            }
            super.onEvent(event);
        }
    }
}
