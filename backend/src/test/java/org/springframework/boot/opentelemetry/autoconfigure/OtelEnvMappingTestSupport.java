package org.springframework.boot.opentelemetry.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;

import java.util.Map;

/**
 * Test dikişi (2026-10-21): Spring Boot 4.1'in GERÇEK {@code OTEL_*} → {@code management.*} eşleme işlemcisini sahte bir
 * ortam değişkeni haritasıyla kurar. Boot'un paket-özel yapıcıları ({@code OpenTelemetryEnvironmentVariables}) yalnız
 * bu paketten erişilebilir; üretim kodu bu sınıfı kullanmaz.
 */
public final class OtelEnvMappingTestSupport {

    private OtelEnvMappingTestSupport() {
    }

    public static EnvironmentPostProcessor mapping(DeferredLogFactory logFactory, Map<String, String> env) {
        return new OpenTelemetryEnvironmentVariableEnvironmentPostProcessor(logFactory,
                new OpenTelemetryEnvironmentVariables(logFactory, env::get));
    }
}
