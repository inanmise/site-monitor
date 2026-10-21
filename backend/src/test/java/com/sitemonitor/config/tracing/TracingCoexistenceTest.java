package com.sitemonitor.config.tracing;

import com.sitemonitor.config.tracing.TracingCoexistence.Decision;
import com.sitemonitor.config.tracing.TracingCoexistence.Inputs;
import com.sitemonitor.config.tracing.TracingCoexistence.Level;
import com.sitemonitor.config.tracing.TracingCoexistence.Message;
import com.sitemonitor.config.tracing.TracingCoexistence.Signal;
import com.sitemonitor.config.tracing.TracingCoexistence.SignalState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ajan / gRPC uyum kararı (2026-10-21) — doğruluk tablosu: ajan var/yok × {@code defer-to-agent} × taşıma grpc/http ×
 * gRPC göndericisi var/yok. Anahtar adları Boot 4.1'in dışa aktarım koşullarıyla birebir (bkz. sınıf belgesi).
 */
class TracingCoexistenceTest {

    private static SignalState exporting(String transport) {
        return new SignalState(true, true, null, transport);
    }

    private static Inputs in(boolean agent, boolean defer, boolean sender, String transport) {
        SignalState s = transport == null ? SignalState.NONE : exporting(transport);
        return new Inputs(agent, defer, sender, s, s, false, false);
    }

    /**
     * agent, defer, sender, transport (- = dışa aktarım yok) → agentMode, kapatılan sinyal sayısı, uyarı sayısı.
     * Ajan + anahtar açık: taşıma / gönderici ne olursa olsun ajan kipi, gRPC koruması gerekmez (her şey kapalı).
     */
    @ParameterizedTest(name = "ajan={0} defer={1} gönderici={2} taşıma={3} → ajanKipi={4} kapatılan={5} uyarı={6}")
    @CsvSource({
            // ajan var, izi ajana bırak
            "true,  true,  false, GRPC, true,  0, 0",
            "true,  true,  false, HTTP, true,  0, 0",
            "true,  true,  true,  GRPC, true,  0, 0",
            "true,  true,  false, -,    true,  0, 0",
            // ajan var ama anahtar kapalı: iki iz sistemi uyarısı + gRPC koruması
            "true,  false, false, GRPC, false, 2, 2",
            "true,  false, false, HTTP, false, 0, 1",
            "true,  false, true,  GRPC, false, 0, 1",
            // ajan yok
            "false, true,  false, GRPC, false, 2, 1",
            "false, false, false, grpc, false, 2, 1",
            "false, true,  true,  GRPC, false, 0, 0",
            "false, true,  false, HTTP, false, 0, 0",
            "false, true,  false, -,    false, 0, 0",
            "false, true,  true,  HTTP, false, 0, 0",
    })
    void truthTable(boolean agent, boolean defer, boolean sender, String transport,
                    boolean agentMode, int disabled, int warnings) {
        Decision d = TracingCoexistence.decide(in(agent, defer, sender, "-".equals(transport) ? null : transport));
        assertThat(d.agentMode()).isEqualTo(agentMode);
        assertThat(d.disabled()).hasSize(disabled);
        assertThat(d.messages().stream().filter(m -> m.level() == Level.WARN)).hasSize(warnings);
        if (!agentMode && disabled == 0) {
            assertThat(d.overrides()).as("değişiklik yoksa ortama dokunulmaz").isEmpty();
        }
    }

    @Test
    @DisplayName("ajan kipi: Boot'un iz + günlük dışa aktarımı, SDK'sı ve OTEL_* eşlemesi kapanır; tek INFO satırı")
    void agentModeOverrides() {
        Decision d = TracingCoexistence.decide(in(true, true, false, "GRPC"));
        assertThat(d.overrides()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "management.opentelemetry.map-environment-variables", "false",
                "management.tracing.export.enabled", "false",
                "management.tracing.export.otlp.enabled", "false",
                "management.logging.export.enabled", "false",
                "management.logging.export.otlp.enabled", "false",
                "management.opentelemetry.enabled", "false",
                "site.monitor.tracing.agent-active", "true"));
        assertThat(d.messages()).containsExactly(new Message(Level.INFO,
                TracingCoexistence.AGENT_INFO));
        assertThat(TracingCoexistence.AGENT_INFO).startsWith(
                "OpenTelemetry Java ajanı algılandı; uygulamanın kendi OTLP gönderimi kapatıldı, izleri ajan gönderiyor");
    }

    @Test
    @DisplayName("ajanın HTTP istemci enstrümantasyonu açıksa traceparent uyarısı (ajan kipinde de)")
    void clientInstrumentationWarning() {
        Decision on = TracingCoexistence.decide(
                new Inputs(true, true, false, SignalState.NONE, SignalState.NONE, true, false));
        assertThat(on.messages()).extracting(Message::level).containsExactly(Level.INFO, Level.WARN);
        assertThat(on.messages().get(1).text())
                .contains("traceparent")
                .contains("OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED=false")
                .contains("OTEL_INSTRUMENTATION_HTTP_URL_CONNECTION_ENABLED=false");
        Decision noAgent = TracingCoexistence.decide(
                new Inputs(false, true, false, SignalState.NONE, SignalState.NONE, true, true));
        assertThat(noAgent.messages()).as("ajan yoksa enstrümantasyon da yok").isEmpty();
    }

    @Test
    @DisplayName("gRPC koruması: yalnız ilgili sinyalin dışa aktarıcısı kapanır, eşleme sabitlenir, uyarı düzeltmeyi söyler")
    void grpcGuardOverrides() {
        Inputs onlyTraces = new Inputs(false, true, false, exporting("GRPC"), exporting("HTTP"), false, false);
        Decision d = TracingCoexistence.decide(onlyTraces);
        assertThat(d.disabled()).containsExactly(Signal.TRACES);
        assertThat(d.overrides()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "management.tracing.export.otlp.enabled", "false",
                "management.opentelemetry.map-environment-variables", "false"));
        String warn = d.messages().get(0).text();
        assertThat(warn).contains("izler").doesNotContain("günlükler")
                .contains("OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf").contains("4318")
                .contains("config.otel.endpoint").contains("/v1/traces");

        Decision logs = TracingCoexistence.decide(
                new Inputs(false, true, false, SignalState.NONE, exporting("grpc"), false, false));
        assertThat(logs.disabled()).containsExactly(Signal.LOGS);
        assertThat(logs.overrides()).containsEntry("management.logging.export.otlp.enabled", "false");
    }

    @ParameterizedTest(name = "uç={0} özel={1} genel={2} → aktif={3}")
    @CsvSource({
            "true,  ,      ,      true",
            "true,  true,  false, true",
            "true,  false, true,  false",
            "true,  ,      false, false",
            "true,  ,      true,  true",
            "false, true,  true,  false",
    })
    @DisplayName("dışa aktarım aktif mi: Boot koşuluyla aynı öncelik (özel anahtar > genel > açık) + uç nokta şart")
    void exportActivePrecedence(boolean endpoint, Boolean exporter, Boolean global, boolean active) {
        SignalState s = new SignalState(endpoint, exporter, global, "GRPC");
        assertThat(s.exportActive()).isEqualTo(active);
        Decision d = TracingCoexistence.decide(new Inputs(false, true, false, s, SignalState.NONE, false, false));
        assertThat(d.disabled()).hasSize(active ? 1 : 0);
    }

    @Test
    @DisplayName("taşıma karşılaştırması büyük/küçük harf ve boşluk duyarsız; tanınmayan değer gRPC değildir")
    void transportMatching() {
        assertThat(new SignalState(true, null, null, " grpc ").grpc()).isTrue();
        assertThat(new SignalState(true, null, null, "GRPC").grpc()).isTrue();
        assertThat(new SignalState(true, null, null, "HTTP").grpc()).isFalse();
        assertThat(new SignalState(true, null, null, null).grpc()).isFalse();
        assertThat(new SignalState(true, null, null, "grpcx").grpc()).isFalse();
    }

    @Test
    @DisplayName("ajan url.query'de onay belirtecini gizlemiyorsa UYARI: ad ve tam ortam değişkeni değeri yazılır")
    void queryCredentialWarning() {
        Decision d = TracingCoexistence.decide(
                new Inputs(true, true, false, SignalState.NONE, SignalState.NONE, false, true));
        assertThat(d.messages()).extracting(Message::level).containsExactly(Level.INFO, Level.WARN);
        assertThat(d.messages().get(1).text()).contains("url.query").contains("token")
                .contains("OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS="
                        + String.join(",", OpenTelemetryAgentProbe.SENSITIVE_QUERY_PARAMETERS))
                .contains("YERİNE");
        Decision off = TracingCoexistence.decide(
                new Inputs(true, false, true, SignalState.NONE, SignalState.NONE, false, true));
        assertThat(off.messages()).extracting(Message::text).anyMatch(t -> t.contains("url.query"));
    }

    @Test
    @DisplayName("anahtar kapalı: ileti GERÇEK durumu söyler — gRPC koruması kapattıysa \"gönderiyor\" denmez")
    void switchOffMessageMatchesState() {
        Decision exporting = TracingCoexistence.decide(in(true, false, false, "HTTP"));
        assertThat(exporting.messages()).extracting(Message::text).containsExactly(TracingCoexistence.BOTH_SYSTEMS_WARN);

        Decision guarded = TracingCoexistence.decide(in(true, false, false, "GRPC"));
        assertThat(guarded.messages()).extracting(Message::text)
                .doesNotContain(TracingCoexistence.BOTH_SYSTEMS_WARN)
                .contains(TracingCoexistence.OWN_SDK_NOT_EXPORTING_WARN);
        assertThat(TracingCoexistence.OWN_SDK_NOT_EXPORTING_WARN).contains("gönderilmiyor");

        Decision noEndpoint = TracingCoexistence.decide(in(true, false, false, null));
        assertThat(noEndpoint.messages()).extracting(Message::text)
                .containsExactly(TracingCoexistence.OWN_SDK_NOT_EXPORTING_WARN);
    }

    @Test
    @DisplayName("iki sinyal birden: tek uyarı satırı ikisini de adlandırır")
    void bothSignalsOneWarning() {
        Decision d = TracingCoexistence.decide(in(false, true, false, "GRPC"));
        assertThat(d.disabled()).containsExactly(Signal.TRACES, Signal.LOGS);
        List<Message> warns = d.messages();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).text()).contains("izler ve günlükler");
    }
}
