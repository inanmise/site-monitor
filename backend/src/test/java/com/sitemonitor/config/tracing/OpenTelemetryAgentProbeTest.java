package com.sitemonitor.config.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenTelemetry Java ajanı algılama (2026-10-21): önyükleme sınıfı, {@code otel.javaagent.enabled}, yedek
 * {@code -javaagent:} argümanı ve ajanın istemci enstrümantasyonu ayarları (ajanın kendi okuma kuralı).
 */
class OpenTelemetryAgentProbeTest {

    private static OpenTelemetryAgentProbe probe(boolean bootstrap, Map<String, String> sys, Map<String, String> env,
                                                 List<String> args) {
        return new OpenTelemetryAgentProbe(() -> bootstrap, sys::get, env::get, () -> args);
    }

    @Test
    @DisplayName("önyükleme sınıfı varsa ajan var; otel.javaagent.enabled=false (özellik ya da env) ise yok")
    void bootstrapClassAndEnabledFlag() {
        assertThat(probe(true, Map.of(), Map.of(), List.of()).present()).isTrue();
        assertThat(probe(false, Map.of(), Map.of(), List.of()).present()).isFalse();
        assertThat(probe(true, Map.of("otel.javaagent.enabled", "false"), Map.of(), List.of()).present()).isFalse();
        assertThat(probe(true, Map.of(), Map.of("OTEL_JAVAAGENT_ENABLED", "false"), List.of()).present()).isFalse();
        assertThat(probe(true, Map.of(), Map.of("OTEL_JAVAAGENT_ENABLED", "true"), List.of()).present()).isTrue();
        // ajanın kuralı: sistem özelliği env'i ezer
        assertThat(probe(true, Map.of("otel.javaagent.enabled", "true"), Map.of("OTEL_JAVAAGENT_ENABLED", "false"),
                List.of()).present()).isTrue();
    }

    @Test
    @DisplayName("yedek işaret: operatörün yolu ve Maven yolu eşleşir; JaCoCo / başka ajanlar eşleşmez")
    void javaagentArgumentFallback() {
        assertThat(OpenTelemetryAgentProbe.hasOtelJavaagentArgument(List.of(
                "-javaagent:/otel-auto-instrumentation-java-sitemonitor-chart/javaagent.jar"))).isTrue();
        assertThat(OpenTelemetryAgentProbe.hasOtelJavaagentArgument(List.of(
                "-Xmx1g", "-javaagent:C:\\m2\\io\\opentelemetry\\javaagent\\opentelemetry-javaagent-2.32.0.jar=x=y")))
                .isTrue();
        assertThat(OpenTelemetryAgentProbe.hasOtelJavaagentArgument(List.of(
                "-javaagent:/root/.m2/repository/org/jacoco/org.jacoco.agent/0.8.13/org.jacoco.agent-0.8.13-runtime.jar"
                        + "=destfile=/tmp/jacoco.exec"))).isFalse();
        assertThat(OpenTelemetryAgentProbe.hasOtelJavaagentArgument(List.of("-javaagent:/opt/hotel/agent.jar")))
                .isFalse();
        assertThat(OpenTelemetryAgentProbe.hasOtelJavaagentArgument(List.of("-Dotel.foo=bar"))).isFalse();
        assertThat(OpenTelemetryAgentProbe.hasOtelJavaagentArgument(null)).isFalse();
        assertThat(probe(false, Map.of(), Map.of(),
                List.of("-javaagent:/otel-auto-instrumentation-java/javaagent.jar")).present()).isTrue();
    }

    @Test
    @DisplayName("istemci enstrümantasyonu: ikisi de kapalıysa false; biri açıksa true; ortak varsayılan kapalıysa false")
    void clientInstrumentationResolution() {
        Map<String, String> env = new HashMap<>();
        assertThat(probe(true, Map.of(), env, List.of()).instrumentsHttpClients()).as("varsayılan açık").isTrue();
        env.put("OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED", "false");
        assertThat(probe(true, Map.of(), env, List.of()).instrumentsHttpClients()).as("HttpURLConnection açık").isTrue();
        env.put("OTEL_INSTRUMENTATION_HTTP_URL_CONNECTION_ENABLED", "false");
        assertThat(probe(true, Map.of(), env, List.of()).instrumentsHttpClients()).isFalse();
        assertThat(probe(true, Map.of("otel.instrumentation.http-url-connection.enabled", "true"), env, List.of())
                .instrumentsHttpClients()).as("sistem özelliği env'i ezer").isTrue();
        assertThat(probe(true, Map.of(), Map.of("OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED", "false"), List.of())
                .instrumentsHttpClients()).isFalse();
        assertThat(probe(true, Map.of(), Map.of("OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED", "false",
                "OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED", "true"), List.of()).instrumentsHttpClients()).isTrue();
    }

    @Test
    @DisplayName("ortam değişkeni adı ajanın kuralıyla: nokta ve tire alt çizgi, büyük harf")
    void envName() {
        assertThat(OpenTelemetryAgentProbe.envName("otel.instrumentation.java-http-client.enabled"))
                .isEqualTo("OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED");
        assertThat(OpenTelemetryAgentProbe.enabled(" ", true)).isTrue();
        assertThat(OpenTelemetryAgentProbe.enabled("yes", true)).as("ajan yalnız 'true'yu açık sayar").isFalse();
        assertThat(OpenTelemetryAgentProbe.enabled("TRUE", false)).isTrue();
    }

    @Test
    @DisplayName("test JVM'inde (JaCoCo ajanıyla) gerçek algılama: ajan YOK")
    void realJvmHasNoAgent() {
        assertThat(OpenTelemetryAgentProbe.bootstrapClassLoadable()).isFalse();
        assertThat(OpenTelemetryAgentProbe.jvm().present()).isFalse();
    }
}
