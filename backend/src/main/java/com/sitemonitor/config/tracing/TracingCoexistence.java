package com.sitemonitor.config.tracing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenTelemetry Java ajanı ile uygulamanın kendi izlemesinin bir arada çalışma kararı (2026-10-21, prod açılış hatası:
 * OpenTelemetry Operator'ün {@code instrumentation.opentelemetry.io/inject-java} ek açıklaması pod'a ajanı ve
 * {@code OTEL_*} ortam değişkenlerini ekledi; Spring Boot 4.1 bu değişkenleri KENDİ dışa aktarıcısına eşledi
 * ({@code OTEL_EXPORTER_OTLP_PROTOCOL=grpc} → gRPC span dışa aktarıcısı) ve sınıf yolunda gRPC göndericisi
 * olmadığı için bağlam "No GrpcSenderProvider found on classpath" ile düştü).
 *
 * <p>Saf karar (G/Ç yok, Spring yok): {@link Inputs} → {@link Decision} (ilk sıraya eklenecek özellikler + günlük
 * iletileri). Ortamı okuma ve özellik kaynağını ekleme {@link TracingCoexistenceEnvironmentPostProcessor}'ın işi.
 *
 * <p><b>Ajan var ve {@value #DEFER_PROPERTY} açık (varsayılan):</b> TEK iz sistemi ajanınki olur. Uygulama kendi OTLP
 * gönderimini (iz + günlük) kapatır, kendi OpenTelemetry SDK'sını kurmaz ({@code management.opentelemetry.enabled=false})
 * ve Micrometer'ın izleyicisi no-op bir OpenTelemetry üzerinde çalışır ({@link AgentTracingConfiguration}): Micrometer
 * kendi span'ını AÇMAZ, ajanın o anki span'ını olduğu gibi gösterir (günlükteki {@code traceId} = ajanın iz kimliği,
 * ajanın JDBC / Hibernate span'ları ajanın sunucu span'ına bağlı kalır). {@code management.tracing.export.enabled=false}
 * Boot'un W3C yayılımını da kapatır (yayılımı ajan yapar). Boot'un {@code OTEL_*} eşlemesi de kapatılır
 * ({@code management.opentelemetry.map-environment-variables=false}): değişkenler ajana aittir.
 *
 * <p><b>Ajan yok (ya da anahtar kapalı):</b> davranış BİREBİR öncekidir; tek istisna gRPC koruması: Boot'un etkin
 * taşıması {@code grpc} ise, dışa aktarım gerçekten kurulacaksa (uç nokta var, dışa aktarım açık) ve sınıf yolunda gRPC
 * göndericisi yoksa o sinyalin dışa aktarımı kapatılır ve TEK bir UYARI düzeltmeyi söyler — açılış ASLA düşmez,
 * protokol / port asla sessizce değiştirilmez.
 *
 * <p>Özellik kaynağı her eklendiğinde {@code management.opentelemetry.map-environment-variables=false} de taşır: Boot'un
 * eşleme işlemcisi bu işlemciden SONRA koşarsa kendi kaynağını yeniden en başa koyup kararı ezemesin (işlemci eşlemeyi
 * karar vermeden önce zaten uygulamıştır — bkz. işlemci).
 */
public final class TracingCoexistence {

    /** Ajan varken izi ajana bırak (varsayılan true; env {@code SITE_MONITOR_TRACING_DEFER_TO_AGENT}). */
    public static final String DEFER_PROPERTY = "site.monitor.tracing.defer-to-agent";
    /** İç işaret: ajan kipi seçildi → {@link AgentTracingConfiguration} no-op OpenTelemetry'yi kurar. Elle verilmez. */
    public static final String AGENT_ACTIVE_PROPERTY = "site.monitor.tracing.agent-active";
    /** Boot'un {@code OTEL_*} → {@code management.*} eşleme anahtarı. */
    public static final String MAP_ENV_PROPERTY = "management.opentelemetry.map-environment-variables";

    static final String TRACING_EXPORT = "management.tracing.export.enabled";
    static final String TRACING_EXPORT_OTLP = "management.tracing.export.otlp.enabled";
    static final String LOGGING_EXPORT = "management.logging.export.enabled";
    static final String LOGGING_EXPORT_OTLP = "management.logging.export.otlp.enabled";
    static final String OTEL_SDK_ENABLED = "management.opentelemetry.enabled";

    /** Boot'un OTLP dışa aktarıcılarını kuran iki sinyal: anahtar adları Boot 4.1 koşullarıyla birebir. */
    public enum Signal {
        TRACES("izler",
                "management.opentelemetry.tracing.export.otlp.endpoint",
                "management.opentelemetry.tracing.export.otlp.transport",
                TRACING_EXPORT_OTLP, TRACING_EXPORT),
        LOGS("günlükler",
                "management.opentelemetry.logging.export.otlp.endpoint",
                "management.opentelemetry.logging.export.otlp.transport",
                LOGGING_EXPORT_OTLP, LOGGING_EXPORT);

        final String label;
        public final String endpointKey;
        public final String transportKey;
        public final String exporterEnabledKey;
        public final String globalEnabledKey;

        Signal(String label, String endpointKey, String transportKey, String exporterEnabledKey, String globalEnabledKey) {
            this.label = label;
            this.endpointKey = endpointKey;
            this.transportKey = transportKey;
            this.exporterEnabledKey = exporterEnabledKey;
            this.globalEnabledKey = globalEnabledKey;
        }
    }

    /**
     * Boot'un bir sinyal için etkin ayarı (eşleme uygulandıktan sonra okunur). {@code exporterEnabled} /
     * {@code globalEnabled} null = ayarlanmamış.
     */
    public record SignalState(boolean endpointSet, Boolean exporterEnabled, Boolean globalEnabled, String transport) {

        public static final SignalState NONE = new SignalState(false, null, null, null);

        /**
         * Boot dışa aktarıcıyı kuracak mı — {@code OnEnabledTracingExportCondition} /
         * {@code OnEnabledLoggingExportCondition} ile aynı öncelik: dışa aktarıcıya özgü anahtar > genel anahtar >
         * varsayılan açık; ek olarak uç nokta özelliği olmalı ({@code @ConditionalOnProperty(...endpoint)}).
         */
        public boolean exportActive() {
            if (!endpointSet) return false;
            if (exporterEnabled != null) return exporterEnabled;
            if (globalEnabled != null) return globalEnabled;
            return true;
        }

        /** Boot taşıması gRPC mi (ikilik değer "GRPC"/"grpc" — Boot'un {@code havingValue} karşılaştırması gibi). */
        public boolean grpc() {
            return transport != null && "grpc".equals(transport.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Karar girdileri.
     *
     * @param agentPresent             OpenTelemetry Java ajanı bu JVM'de etkin
     * @param deferToAgent             {@value #DEFER_PROPERTY}
     * @param grpcSenderPresent        sınıf yolunda bir {@code GrpcSenderProvider} gerçeklemesi var
     * @param traces                   iz dışa aktarımının etkin ayarı
     * @param logs                     günlük dışa aktarımının etkin ayarı
     * @param agentInstrumentsHttpClients ajanın java.net.http / HttpURLConnection istemci enstrümantasyonu açık
     *                                 (izlenen sitelere giden kontrollere {@code traceparent} eklenir)
     */
    public record Inputs(boolean agentPresent, boolean deferToAgent, boolean grpcSenderPresent,
                         SignalState traces, SignalState logs, boolean agentInstrumentsHttpClients) {
    }

    public enum Level { INFO, WARN }

    public record Message(Level level, String text) {
    }

    /**
     * @param agentMode  izi ajan taşıyor (Boot dışa aktarımı ve SDK'sı kapalı)
     * @param overrides  ilk sıradaki özellik kaynağına yazılacaklar (boş = ortama dokunulmaz)
     * @param disabled   gRPC koruması yüzünden kapatılan sinyaller
     * @param messages   günlüğe yazılacak iletiler (sırasıyla)
     */
    public record Decision(boolean agentMode, Map<String, Object> overrides, List<Signal> disabled,
                           List<Message> messages) {
    }

    static final String AGENT_INFO =
            "OpenTelemetry Java ajanı algılandı; uygulamanın kendi OTLP gönderimi kapatıldı, izleri ajan gönderiyor"
                    + " (geri almak için " + DEFER_PROPERTY + "=false / SITE_MONITOR_TRACING_DEFER_TO_AGENT=false)";

    static final String CLIENT_WARN =
            "OpenTelemetry Java ajanı java.net.http / HttpURLConnection istemcilerini izliyor: izlenen sitelere,"
                    + " OCSP/CRL'ye, webhook ve push servislerine giden isteklere traceparent başlığı eklenir ve her"
                    + " kontrol için istemci span'ı üretilir. Kapatmak için pod ortamına"
                    + " OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED=false ve"
                    + " OTEL_INSTRUMENTATION_HTTP_URL_CONNECTION_ENABLED=false verin (Helm: config.otel.agent.enabled=true"
                    + " bunları ekler)";

    static final String BOTH_SYSTEMS_WARN =
            "OpenTelemetry Java ajanı algılandı ama " + DEFER_PROPERTY + "=false: uygulama kendi izlerini de üretip"
                    + " gönderiyor — iki ayrı iz sistemi (iki iz kimliği) oluşur";

    private TracingCoexistence() {
    }

    public static Decision decide(Inputs in) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        List<Message> messages = new ArrayList<>();
        List<Signal> disabled = new ArrayList<>();

        if (in.agentPresent() && in.deferToAgent()) {
            overrides.put(MAP_ENV_PROPERTY, "false");
            overrides.put(TRACING_EXPORT, "false");
            overrides.put(TRACING_EXPORT_OTLP, "false");
            overrides.put(LOGGING_EXPORT, "false");
            overrides.put(LOGGING_EXPORT_OTLP, "false");
            overrides.put(OTEL_SDK_ENABLED, "false");
            overrides.put(AGENT_ACTIVE_PROPERTY, "true");
            messages.add(new Message(Level.INFO, AGENT_INFO));
            if (in.agentInstrumentsHttpClients()) messages.add(new Message(Level.WARN, CLIENT_WARN));
            return new Decision(true, Collections.unmodifiableMap(overrides), List.of(), List.copyOf(messages));
        }

        if (in.agentPresent()) {
            messages.add(new Message(Level.WARN, BOTH_SYSTEMS_WARN));
            if (in.agentInstrumentsHttpClients()) messages.add(new Message(Level.WARN, CLIENT_WARN));
        }

        if (!in.grpcSenderPresent()) {
            guard(Signal.TRACES, in.traces(), overrides, disabled);
            guard(Signal.LOGS, in.logs(), overrides, disabled);
        }
        if (!disabled.isEmpty()) {
            // Eşleme işlemcisi bundan sonra koşarsa kendi kaynağını en başa koyup kararı ezmesin.
            overrides.put(MAP_ENV_PROPERTY, "false");
            messages.add(new Message(Level.WARN, grpcWarning(disabled)));
        }
        return new Decision(false, Collections.unmodifiableMap(overrides), List.copyOf(disabled),
                List.copyOf(messages));
    }

    private static void guard(Signal signal, SignalState state, Map<String, Object> overrides, List<Signal> disabled) {
        if (state != null && state.exportActive() && state.grpc()) {
            overrides.put(signal.exporterEnabledKey, "false");
            disabled.add(signal);
        }
    }

    static String grpcWarning(List<Signal> disabled) {
        StringBuilder which = new StringBuilder();
        for (Signal s : disabled) {
            if (!which.isEmpty()) which.append(" ve ");
            which.append(s.label);
        }
        return "OTLP gRPC taşıması istendi (OTEL_EXPORTER_OTLP_PROTOCOL=grpc ya da "
                + "management.opentelemetry.*.export.otlp.transport=grpc) ama sınıf yolunda gRPC göndericisi yok;"
                + " uygulamanın kendi OTLP gönderimi (" + which + ") KAPATILDI — açılış düşmesin diye. Düzeltmek için"
                + " OTLP/HTTP kullanın: OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf ve toplayıcının 4318 portu"
                + " (OTEL_EXPORTER_OTLP_ENDPOINT=http://<toplayıcı>:4318), ya da Helm config.otel.endpoint="
                + "http://<toplayıcı>:4318/v1/traces (tam iz URL'si). OpenTelemetry Operator ile ajan kullanıyorsanız"
                + " Helm config.otel.agent.enabled=true";
    }
}
