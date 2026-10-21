package org.springframework.boot.opentelemetry.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.logging.DeferredLogFactory;

import java.util.Map;

/**
 * Test dikişi (2026-10-21): Spring Boot'un GERÇEK {@code OTEL_*} → {@code management.*} eşleme işlemcisini sahte bir
 * ortam değişkeni haritasıyla kurar. Üretim kodu bu sınıfı kullanmaz.
 *
 * <p><b>KIRILGAN — bilerek.</b> Boot 4.1.0'da ortamı dışarıdan vermenin PUBLIC yolu yok: genel yapıcı
 * {@code System.getenv}'i okur; sahte ortam yalnız paket-özel {@code OpenTelemetryEnvironmentVariables(DeferredLogFactory,
 * Function)} yapıcısıyla verilebilir (bu yüzden sınıf Boot'un paketinde). Boot yükseltmesinde bu yapıcı ya da işlemcinin
 * paket-özel iki argümanlı yapıcısı değişirse test DERLENMEZ (ya da çalışma anında aşağıdaki iletiyle düşer): o zaman
 * Boot'un yeni sürümündeki eşleme sınıfına bakıp bu dikişi uyarlayın — düzeltmenin kendisi (TracingCoexistence…)
 * yalnız Boot'un GENEL yapıcısını kullanır ve etkilenmez.
 */
public final class OtelEnvMappingTestSupport {

    private OtelEnvMappingTestSupport() {
    }

    public static EnvironmentPostProcessor mapping(DeferredLogFactory logFactory, Map<String, String> env) {
        try {
            return new OpenTelemetryEnvironmentVariableEnvironmentPostProcessor(logFactory,
                    new OpenTelemetryEnvironmentVariables(logFactory, env::get));
        } catch (LinkageError e) {
            throw new AssertionError("OtelEnvMappingTestSupport Spring Boot 4.1.0'ın paket-özel"
                    + " OpenTelemetryEnvironmentVariables(DeferredLogFactory, Function) yapıcısına dayanır; çalışan Boot "
                    + SpringBootVersion.getVersion() + " bunu değiştirmiş — test dikişini yeni sürüme uyarlayın", e);
        }
    }
}
