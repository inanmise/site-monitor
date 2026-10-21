package com.sitemonitor.config.tracing;

import com.sitemonitor.config.tracing.TracingCoexistence.Decision;
import com.sitemonitor.config.tracing.TracingCoexistence.Inputs;
import com.sitemonitor.config.tracing.TracingCoexistence.Message;
import com.sitemonitor.config.tracing.TracingCoexistence.Signal;
import com.sitemonitor.config.tracing.TracingCoexistence.SignalState;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetryEnvironmentVariableEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * OpenTelemetry Java ajanı / OTLP gRPC uyumu (2026-10-21) — kararı {@link TracingCoexistence} verir, bu işlemci ortamı
 * okuyup kararı EN ÖNCELİKLİ özellik kaynağı ({@value #PROPERTY_SOURCE_NAME}) olarak ekler ve iletileri Boot'un
 * ertelenmiş günlüğüyle yazar (günlük sistemi henüz kurulmadı; asla {@code System.out}). Kayıt:
 * {@code META-INF/spring.factories} ({@code org.springframework.boot.EnvironmentPostProcessor}).
 *
 * <p><b>Boot'un eşleme işlemcisine göre sıra önemsiz.</b> Boot'un {@code OpenTelemetryEnvironmentVariable…}
 * işlemcisi de en düşük öncelikte koşar ve kendi kaynağını {@code addFirst} ile ekler; eşit öncelikte sırayı
 * {@code spring.factories} yükleme sırası belirler (uygulamanınki önce gelir). Bu yüzden:
 * <ul>
 *   <li>Ajan kipinde eşleme hiç gerekmez; eklenen kaynak {@code map-environment-variables=false} taşır → Boot'un
 *       işlemcisi sonra koşarsa hiçbir şey eklemez, önce koştuysa kaynağı bizimkinin ALTINDA kalır.</li>
 *   <li>Diğer durumlarda karar Boot'un eşlenmiş değerlerine bakmalıdır: eşleme henüz uygulanmadıysa (kaynak yok ve
 *       eşleme açık) burada Boot'un KENDİ işlemcisi çalıştırılır (tek doğruluk kaynağı Boot'un eşlemesi). Bir şey
 *       değiştirilecekse kaynak yine {@code map-environment-variables=false} taşır; hiçbir şey değişmiyorsa ortama
 *       dokunulmaz ve Boot'un işlemcisi sonra aynı kaynağı aynı içerikle yeniden yazar.</li>
 * </ul>
 * Beklenmeyen bir hata açılışı ASLA düşürmez: bir UYARI yazılır ve Boot'un yapılandırması değiştirilmeden kullanılır.
 */
public class TracingCoexistenceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "siteMonitorTracingCoexistence";
    /** Boot 4.1'in eşleme kaynağının adı ({@code OpenTelemetryEnvironmentVariableEnvironmentPostProcessor}). */
    static final String BOOT_MAPPING_SOURCE = "openTelemetryEnvironmentVariables";

    private final Log log;
    private final OpenTelemetryAgentProbe agent;
    private final BooleanSupplier grpcSenderPresent;
    private final Supplier<EnvironmentPostProcessor> bootMapping;

    public TracingCoexistenceEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this(logFactory, OpenTelemetryAgentProbe.jvm(),
                () -> GrpcSenderProbe.present(TracingCoexistenceEnvironmentPostProcessor.class.getClassLoader()),
                () -> new OpenTelemetryEnvironmentVariableEnvironmentPostProcessor(logFactory));
    }

    /** Test dikişi: ajan, gRPC göndericisi ve Boot eşlemesi (sahte {@code OTEL_*} ortamıyla) dışarıdan verilir. */
    TracingCoexistenceEnvironmentPostProcessor(DeferredLogFactory logFactory, OpenTelemetryAgentProbe agent,
                                               BooleanSupplier grpcSenderPresent,
                                               Supplier<EnvironmentPostProcessor> bootMapping) {
        this.log = logFactory.getLog(TracingCoexistenceEnvironmentPostProcessor.class);
        this.agent = agent;
        this.grpcSenderPresent = grpcSenderPresent;
        this.bootMapping = bootMapping;
    }

    @Override
    public int getOrder() {
        // ConfigData'dan sonra (application.properties + profiller görünür); Boot'un eşlemesine göre sıra önemsiz.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            Decision decision = decide(environment, application);
            if (!decision.overrides().isEmpty()) {
                environment.getPropertySources()
                        .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, decision.overrides()));
            }
            for (Message m : decision.messages()) {
                if (m.level() == TracingCoexistence.Level.WARN) log.warn(m.text());
                else log.info(m.text());
            }
        } catch (RuntimeException | LinkageError e) {
            log.warn("İzleme uyum denetimi yapılamadı (" + e
                    + "); Spring Boot'un izleme yapılandırması değiştirilmeden kullanılıyor");
        }
    }

    Decision decide(ConfigurableEnvironment environment, SpringApplication application) {
        boolean agentPresent = agent.present();
        boolean defer = bool(environment, TracingCoexistence.DEFER_PROPERTY, true);
        boolean agentMode = agentPresent && defer;
        if (!agentMode) applyBootMappingOnce(environment, application);
        Inputs in = new Inputs(agentPresent, defer,
                !agentMode && grpcSenderPresent.getAsBoolean(),
                agentMode ? SignalState.NONE : read(environment, Signal.TRACES),
                agentMode ? SignalState.NONE : read(environment, Signal.LOGS),
                agentPresent && agent.instrumentsHttpClients());
        return TracingCoexistence.decide(in);
    }

    /** Boot'un {@code OTEL_*} eşlemesi henüz uygulanmadıysa (ve kapatılmadıysa) şimdi uygula — karar onu görmeli. */
    void applyBootMappingOnce(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(BOOT_MAPPING_SOURCE)) return;
        if (!bool(environment, TracingCoexistence.MAP_ENV_PROPERTY, true)) return;
        bootMapping.get().postProcessEnvironment(environment, application);
    }

    static SignalState read(ConfigurableEnvironment environment, Signal signal) {
        return new SignalState(
                StringUtils.hasText(string(environment, signal.endpointKey)),
                boolOrNull(environment, signal.exporterEnabledKey),
                boolOrNull(environment, signal.globalEnabledKey),
                string(environment, signal.transportKey));
    }

    private static String string(ConfigurableEnvironment environment, String key) {
        try {
            return environment.getProperty(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Boolean boolOrNull(ConfigurableEnvironment environment, String key) {
        try {
            return environment.getProperty(key, Boolean.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean bool(ConfigurableEnvironment environment, String key, boolean fallback) {
        Boolean v = boolOrNull(environment, key);
        return v != null ? v : fallback;
    }
}
