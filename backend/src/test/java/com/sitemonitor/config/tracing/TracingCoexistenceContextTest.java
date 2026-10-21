package com.sitemonitor.config.tracing;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OtelEnvMappingTestSupport;
import org.springframework.boot.opentelemetry.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prod açılış hatası (2026-10-21, v20.161.0) — GERÇEK Boot 4.1 otomatik yapılandırması + GERÇEK {@code OTEL_*} eşleme
 * işlemcisi (sahte ortam haritasıyla, {@link OtelEnvMappingTestSupport}) üzerinde:
 * <ol>
 *   <li>Operatörün enjekte ettiği ortam (gRPC, uç nokta, OTEL_TRACES_EXPORTER=otlp) bu düzeltme olmadan bağlamı
 *       "No GrpcSenderProvider found on classpath" ile DÜŞÜRÜR (hata yeniden üretilir).</li>
 *   <li>Düzeltmeyle ajan varken bağlam açılır, Boot'un OTLP dışa aktarıcısı / SDK'sı yoktur, Micrometer kendi span'ını
 *       açmaz (ajanın o anki span'ını gösterir), Boot W3C yaymaz.</li>
 *   <li>Ajan yokken gRPC dışa aktarımı UYARI ile kapanır (açılış düşmez); HTTP kurulumu birebir eskisi gibidir.</li>
 * </ol>
 * Her senaryo iki sırada koşar: bu işlemci Boot'un eşlemesinden ÖNCE (fat jar'da olan) ve SONRA.
 */
class TracingCoexistenceContextTest {

    /** OpenTelemetry Operator'ün Java ajanıyla eklediği tipik ortam (kullanıcının prod günlüğündeki gibi gRPC). */
    static final Map<String, String> OPERATOR_GRPC = Map.of(
            "OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4317",
            "OTEL_EXPORTER_OTLP_PROTOCOL", "grpc",
            "OTEL_TRACES_EXPORTER", "otlp",
            "OTEL_LOGS_EXPORTER", "otlp",
            "OTEL_METRICS_EXPORTER", "otlp",
            "OTEL_PROPAGATORS", "tracecontext,baggage",
            "OTEL_TRACES_SAMPLER", "parentbased_traceidratio",
            "OTEL_TRACES_SAMPLER_ARG", "1",
            "OTEL_SERVICE_NAME", "sitemonitor");

    static final Map<String, String> PLAIN_HTTP = Map.of(
            "OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318",
            "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf",
            "OTEL_TRACES_EXPORTER", "otlp");

    static final String AGENT_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String AGENT_SPAN = "00f067aa0ba902b7";

    private final List<String> warns = Collections.synchronizedList(new ArrayList<>());
    private final List<String> infos = Collections.synchronizedList(new ArrayList<>());
    private final DeferredLogFactory logFactory = destination -> recordingLog();

    // ── hazırlık ─────────────────────────────────────────────────────────────────────────────────────────────────

    private EnvironmentPostProcessor bootMapping(Map<String, String> otelEnv) {
        return OtelEnvMappingTestSupport.mapping(logFactory, otelEnv);
    }

    /** Helm ajan bloğunun ortamı (istemci enstrümantasyonu kapalı, url.query gizleme listesi). */
    static Map<String, String> helmAgentEnv() {
        Map<String, String> env = new HashMap<>(OPERATOR_GRPC);
        env.put("OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED", "false");
        env.put("OTEL_INSTRUMENTATION_HTTP_URL_CONNECTION_ENABLED", "false");
        env.put("OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS",
                String.join(",", OpenTelemetryAgentProbe.SENSITIVE_QUERY_PARAMETERS));
        return env;
    }

    private TracingCoexistenceEnvironmentPostProcessor ours(Map<String, String> otelEnv, boolean agent) {
        OpenTelemetryAgentProbe probe = new OpenTelemetryAgentProbe(() -> agent, k -> null, otelEnv::get, List::of);
        return new TracingCoexistenceEnvironmentPostProcessor(logFactory, probe,
                () -> GrpcSenderProbe.present(getClass().getClassLoader()),
                factory -> OtelEnvMappingTestSupport.mapping(factory, otelEnv));
    }

    /** Boot'un açılışındaki ortamı kurar: uygulama ayarları + (sırayla) eşleme ve bu işlemci. */
    private ConfigurableEnvironment prepare(Map<String, String> otelEnv, Map<String, Object> appProps, boolean agent,
                                            boolean withFix, boolean oursFirst) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> app = new HashMap<>(appProps);
        app.put("spring.application.name", "site-monitor");
        env.getPropertySources().addLast(new MapPropertySource("applicationConfig", app));
        SpringApplication application = new SpringApplication();
        EnvironmentPostProcessor boot = bootMapping(otelEnv);
        if (!withFix) {
            boot.postProcessEnvironment(env, application);
        } else if (oursFirst) {
            ours(otelEnv, agent).postProcessEnvironment(env, application);
            boot.postProcessEnvironment(env, application);
        } else {
            boot.postProcessEnvironment(env, application);
            ours(otelEnv, agent).postProcessEnvironment(env, application);
        }
        return env;
    }

    private static ApplicationContextRunner runner(ConfigurableEnvironment prepared) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ObservationAutoConfiguration.class,
                        OpenTelemetrySdkAutoConfiguration.class,
                        MicrometerTracingAutoConfiguration.class,
                        NoopTracerAutoConfiguration.class,
                        OpenTelemetryTracingAutoConfiguration.class,
                        OtlpTracingAutoConfiguration.class,
                        OpenTelemetryLoggingAutoConfiguration.class,
                        OtlpLoggingAutoConfiguration.class))
                .withUserConfiguration(AgentTracingConfiguration.class)
                .withInitializer(ctx -> copySources(prepared, ctx.getEnvironment()));
    }

    /** Hazırlanan kaynakları (sistem kaynakları hariç) aynı sırayla bağlamın ortamının başına taşır. */
    private static void copySources(ConfigurableEnvironment from, ConfigurableEnvironment to) {
        List<PropertySource<?>> list = new ArrayList<>();
        for (PropertySource<?> ps : from.getPropertySources()) {
            if (!to.getPropertySources().contains(ps.getName())) list.add(ps);
        }
        for (int i = list.size() - 1; i >= 0; i--) to.getPropertySources().addFirst(list.get(i));
    }

    // ── senaryolar ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HATA YENİDEN ÜRETİLİR: düzeltme olmadan operatör ortamı (gRPC) bağlamı düşürür")
    void crashReproducedWithoutFix() {
        runner(prepare(OPERATOR_GRPC, Map.of(), true, false, false)).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("No GrpcSenderProvider found on classpath");
        });
    }

    @ParameterizedTest(name = "bu işlemci Boot eşlemesinden önce={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("ajan var: bağlam açılır, Boot'un OTLP dışa aktarıcısı / SDK'sı yok, OpenTelemetry no-op, tek INFO")
    void agentPresentDefers(boolean oursFirst) {
        ConfigurableEnvironment env = prepare(OPERATOR_GRPC, Map.of(), true, true, oursFirst);
        assertThat(env.getPropertySources().iterator().next().getName())
                .isEqualTo(TracingCoexistenceEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        if (oursFirst) {
            assertThat(env.getPropertySources().contains(TracingCoexistenceEnvironmentPostProcessor.BOOT_MAPPING_SOURCE))
                    .as("Boot'un eşlemesi sonra koşunca hiçbir şey eklemez").isFalse();
        }
        runner(env).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(OtlpGrpcSpanExporter.class);
            assertThat(ctx).doesNotHaveBean(OtlpHttpSpanExporter.class);
            assertThat(ctx).doesNotHaveBean(SpanExporter.class);
            assertThat(ctx).doesNotHaveBean(LogRecordExporter.class);
            assertThat(ctx).doesNotHaveBean(SdkTracerProvider.class);
            assertThat(ctx.getBean(OpenTelemetry.class)).isSameAs(OpenTelemetry.noop());
            assertThat(ctx).hasSingleBean(Tracer.class);
            assertThat(ctx.getBean(Propagator.class).fields()).as("Boot W3C yaymaz; yayılım ajanın").isEmpty();
        });
        assertThat(infos).containsExactly(TracingCoexistence.AGENT_INFO);
        assertThat(warns).as("operatör ortamında istemci enstrümantasyonu açık ve url.query gizlenmiyor").hasSize(2);
        assertThat(warns.get(0)).contains("traceparent");
        assertThat(warns.get(1)).contains("url.query").contains("token");
    }

    @Test
    @DisplayName("ajan kipi: Micrometer YENİ span açmaz — ajanın o anki span'ını gösterir (günlükteki traceId = ajanınki)")
    void agentModeMicrometerIsPassThrough() {
        runner(prepare(OPERATOR_GRPC, Map.of(), true, true, true)).run(ctx -> {
            Tracer tracer = ctx.getBean(Tracer.class);
            try (Scope ignored = agentSpan().makeCurrent()) {
                io.micrometer.tracing.Span span = tracer.nextSpan().name("x").start();
                assertThat(span.context().traceId()).isEqualTo(AGENT_TRACE);
                assertThat(span.context().spanId()).isEqualTo(AGENT_SPAN);
                span.end();
            }
        });
    }

    @Test
    @DisplayName("karşılaştırma: ajansız kurulumda Micrometer kendi alt span'ını açar — Boot'un SDK'sı yerinde")
    void ownSdkOpensChildSpan() {
        // Uç nokta yok → dışa aktarıcı yok, test ağa çıkmaz.
        runner(prepare(Map.of(), Map.of(), false, true, true)).run(ctx -> {
            Tracer tracer = ctx.getBean(Tracer.class);
            try (Scope ignored = agentSpan().makeCurrent()) {
                io.micrometer.tracing.Span span = tracer.nextSpan().name("x").start();
                assertThat(span.context().traceId()).isEqualTo(AGENT_TRACE);
                assertThat(span.context().spanId()).isNotEqualTo(AGENT_SPAN);
                span.end();
            }
            assertThat(ctx.getBean(Propagator.class).fields()).contains("traceparent");
        });
    }

    @ParameterizedTest(name = "bu işlemci Boot eşlemesinden önce={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("ajan yok + gRPC + gönderici yok: dışa aktarım UYARI ile kapanır, bağlam açılır, SDK (traceId) yerinde")
    void noAgentGrpcIsDisabledNotCrashed(boolean oursFirst) {
        runner(prepare(OPERATOR_GRPC, Map.of(), false, true, oursFirst)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SpanExporter.class);
            assertThat(ctx).doesNotHaveBean(LogRecordExporter.class);
            assertThat(ctx).hasSingleBean(SdkTracerProvider.class);
            assertThat(ctx.getBean(OpenTelemetry.class)).isNotSameAs(OpenTelemetry.noop());
        });
        assertThat(warns).singleElement().asString()
                .contains("izler ve günlükler").contains("OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf").contains("4318");
        assertThat(infos).isEmpty();
    }

    @Test
    @DisplayName("Helm config.otel.endpoint yoluyla gRPC taşıması (OTEL_* yok) da korunur")
    void springPropertyGrpcIsGuarded() {
        runner(prepare(Map.of(), Map.of(
                "management.opentelemetry.tracing.export.otlp.endpoint", "http://collector:4317",
                "management.opentelemetry.tracing.export.otlp.transport", "grpc"), false, true, true)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SpanExporter.class);
        });
        assertThat(warns).singleElement().asString().contains("izler").doesNotContain("günlükler");
    }

    @ParameterizedTest(name = "bu işlemci Boot eşlemesinden önce={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("ajan yok + OTLP/HTTP: değişiklik yok — dışa aktarıcı kurulur, ortama kaynak eklenmez, günlük yok")
    void noAgentHttpUnchanged(boolean oursFirst) {
        ConfigurableEnvironment env = prepare(PLAIN_HTTP, Map.of(), false, true, oursFirst);
        assertThat(env.getPropertySources().contains(TracingCoexistenceEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
        runner(env).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(OtlpHttpSpanExporter.class);
            assertThat(ctx.getBean(OtlpHttpSpanExporter.class).toString()).contains("4318/v1/traces");
        });
        assertThat(warns).isEmpty();
        assertThat(infos).isEmpty();
    }

    @Test
    @DisplayName("ajan var ama defer-to-agent=false: iki sistem uyarısı; gRPC yine korunur, bağlam açılır")
    void agentPresentSwitchOff() {
        runner(prepare(OPERATOR_GRPC, Map.of(TracingCoexistence.DEFER_PROPERTY, "false"), true, true, true)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SpanExporter.class);
            assertThat(ctx).hasSingleBean(SdkTracerProvider.class);
        });
        assertThat(warns).hasSize(4);
        assertThat(String.join("\n", warns))
                .as("gRPC koruması iz gönderimini kapattı → \"gönderiyor\" denmez")
                .doesNotContain("iki ayrı iz sistemi").contains("gönderilmiyor")
                .contains("traceparent").contains("url.query").contains("http/protobuf");
    }

    @Test
    @DisplayName("ajan var ama defer-to-agent=false + HTTP: Boot'un kendi dışa aktarıcısı kurulur (bilinçli iki sistem)")
    void agentPresentSwitchOffHttp() {
        runner(prepare(PLAIN_HTTP, Map.of(TracingCoexistence.DEFER_PROPERTY, "false"), true, true, true)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(OtlpHttpSpanExporter.class);
        });
    }

    @Test
    @DisplayName("Helm ajan bloğu (MAP_ENVIRONMENT_VARIABLES=false + istemci enstrümantasyonu kapalı): açılır, uyarı yok")
    void helmAgentBlock() {
        runner(prepare(helmAgentEnv(), Map.of(TracingCoexistence.MAP_ENV_PROPERTY, "false"), true, true, true)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SpanExporter.class);
            assertThat(ctx.getBean(OpenTelemetry.class)).isSameAs(OpenTelemetry.noop());
        });
        assertThat(warns).isEmpty();
        assertThat(infos).containsExactly(TracingCoexistence.AGENT_INFO);
    }

    @ParameterizedTest(name = "bu işlemci Boot eşlemesinden önce={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("ajansız, değişiklik yok: Boot'un geçersiz-değer uyarısı TEK kez yazılır, ortama kaynak eklenmez")
    void bootMappingWarningsLoggedOnce(boolean oursFirst) {
        Map<String, String> env = new HashMap<>(PLAIN_HTTP);
        env.put("OTEL_EXPORTER_OTLP_COMPRESSION", "zstd");
        ConfigurableEnvironment prepared = prepare(env, Map.of(), false, true, oursFirst);
        assertThat(prepared.getPropertySources().contains(TracingCoexistenceEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
        assertThat(prepared.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://127.0.0.1:4318/v1/traces");
        assertThat(warns).as("Boot'un eşleme uyarıları: " + warns)
                .filteredOn(w -> w.contains("OTEL_EXPORTER_OTLP_COMPRESSION")).hasSize(3);   // metrik + iz + günlük, birer kez
    }

    @ParameterizedTest(name = "bu işlemci Boot eşlemesinden önce={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("ajansız + gRPC koruması: eşleme gerçek ortama bir kez, uyarılarıyla bir kez uygulanır")
    void guardAppliesBootMappingOnce(boolean oursFirst) {
        Map<String, String> env = new HashMap<>(OPERATOR_GRPC);
        env.put("OTEL_EXPORTER_OTLP_COMPRESSION", "zstd");
        ConfigurableEnvironment prepared = prepare(env, Map.of(), false, true, oursFirst);
        assertThat(prepared.getPropertySources().contains(TracingCoexistenceEnvironmentPostProcessor.BOOT_MAPPING_SOURCE))
                .isTrue();
        assertThat(prepared.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("false");
        assertThat(warns).filteredOn(w -> w.contains("OTEL_EXPORTER_OTLP_COMPRESSION")).hasSize(3);
    }

    @Test
    @DisplayName("ajan kipi: geçersiz (sıfır) span bağlamı MDC'ye yazılmaz; geçerli bağlam yazılır")
    void agentModeSkipsInvalidSpanInMdc() {
        runner(prepare(OPERATOR_GRPC, Map.of(), true, true, true)).run(ctx -> {
            Slf4JEventListener listener = ctx.getBean(Slf4JEventListener.class);
            assertThat(listener).isInstanceOf(AgentTracingConfiguration.ValidSpanSlf4JEventListener.class);
            try {
                MDC.put("traceId", "stale");
                listener.onEvent(new EventPublishingContextWrapper.ScopeAttachedEvent(
                        Context.root().with(Span.getInvalid())));
                assertThat(MDC.get("traceId")).isNull();
                assertThat(MDC.get("spanId")).isNull();
                listener.onEvent(new EventPublishingContextWrapper.ScopeAttachedEvent(Context.root().with(agentSpan())));
                assertThat(MDC.get("traceId")).isEqualTo(AGENT_TRACE);
                assertThat(MDC.get("spanId")).isEqualTo(AGENT_SPAN);
                listener.onEvent(new EventPublishingContextWrapper.ScopeRestoredEvent(
                        Context.root().with(Span.getInvalid())));
                assertThat(MDC.get("traceId")).isNull();
                listener.onEvent(new EventPublishingContextWrapper.ScopeRestoredEvent(Context.root().with(agentSpan())));
                listener.onEvent(new EventPublishingContextWrapper.ScopeClosedEvent());
                assertThat(MDC.get("traceId")).isNull();
            } finally {
                MDC.clear();
            }
            // Micrometer'ın no-op izleyicisi o an span yokken geçersiz bağlamı sarar — kaynağı budur.
            io.micrometer.tracing.Span orphan = ctx.getBean(Tracer.class).nextSpan().start();
            assertThat(orphan.context().traceId()).isEqualTo("00000000000000000000000000000000");
            orphan.end();
        });
        runner(prepare(PLAIN_HTTP, Map.of(), false, true, true)).run(ctx ->
                assertThat(ctx.getBean(Slf4JEventListener.class))
                        .as("ajansız: Boot'un kendi dinleyicisi").isNotInstanceOf(
                                AgentTracingConfiguration.ValidSpanSlf4JEventListener.class));
    }

    @Test
    @DisplayName("beklenmeyen hata açılışı düşürmez: UYARI yazılır, ortama dokunulmaz")
    void unexpectedFailureNeverBreaksStartup() {
        OpenTelemetryAgentProbe broken = new OpenTelemetryAgentProbe(() -> {
            throw new IllegalStateException("boom");
        }, k -> null, k -> null, List::of);
        StandardEnvironment env = new StandardEnvironment();
        int before = env.getPropertySources().size();
        new TracingCoexistenceEnvironmentPostProcessor(logFactory, broken, () -> false,
                factory -> OtelEnvMappingTestSupport.mapping(factory, Map.of()))
                .postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getPropertySources().size()).isEqualTo(before);
        assertThat(warns).singleElement().asString().contains("İzleme uyum denetimi yapılamadı").contains("boom");
    }

    @Test
    @DisplayName("Boot'un eşleme kaynağının adı sabitle aynı (Boot sürüm yükseltmesinde ad değişirse burası kırılır)")
    void bootMappingSourceName() {
        StandardEnvironment env = new StandardEnvironment();
        bootMapping(PLAIN_HTTP).postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getPropertySources().contains(TracingCoexistenceEnvironmentPostProcessor.BOOT_MAPPING_SOURCE))
                .isTrue();
        assertThat(env.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://127.0.0.1:4318/v1/traces");
    }

    @Test
    @DisplayName("spring.factories'te kayıtlı ve Boot'un yapıcı imzasıyla (DeferredLogFactory) örneklenebilir")
    void registeredInSpringFactories() throws Exception {
        boolean found = false;
        var urls = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (urls.hasMoreElements()) {
            Properties p = new Properties();
            try (var in = urls.nextElement().openStream()) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String v = p.getProperty("org.springframework.boot.EnvironmentPostProcessor", "");
            if (v.contains(TracingCoexistenceEnvironmentPostProcessor.class.getName())) found = true;
        }
        assertThat(found).isTrue();
        EnvironmentPostProcessor epp = TracingCoexistenceEnvironmentPostProcessor.class
                .getConstructor(DeferredLogFactory.class).newInstance(logFactory);
        StandardEnvironment env = new StandardEnvironment();
        epp.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getPropertySources().contains(TracingCoexistenceEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .as("test JVM'inde ajan yok, gRPC yok → ortam değişmez").isFalse();
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────────────────────────────────

    private static Span agentSpan() {
        return Span.wrap(SpanContext.create(AGENT_TRACE, AGENT_SPAN, TraceFlags.getSampled(), TraceState.getDefault()));
    }

    private Log recordingLog() {
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[]{Log.class}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "warn" -> warns.add(String.valueOf(args[0]));
                case "info" -> infos.add(String.valueOf(args[0]));
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "toString" -> {
                    return "RecordingLog";
                }
                default -> {
                }
            }
            return m.getReturnType() == boolean.class ? Boolean.TRUE : null;
        });
    }
}
