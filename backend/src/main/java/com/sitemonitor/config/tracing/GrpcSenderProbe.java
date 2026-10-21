package com.sitemonitor.config.tracing;

import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Sınıf yolunda bir OTLP gRPC göndericisi var mı (2026-10-21)? OTLP gRPC dışa aktarıcısı kurulurken
 * ({@code OtlpGrpcSpanExporter.builder().build()}) OpenTelemetry {@code ServiceLoader} ile bir
 * {@code GrpcSenderProvider} gerçeklemesi arar; yoksa "No GrpcSenderProvider found on classpath" fırlatır ve Spring
 * bağlamı düşer. Bu proje bilinçli olarak yalnız JDK HTTP göndericisini taşır (OkHttp + Kotlin imaja girmesin), yani
 * cevap normalde "yok"tur.
 *
 * <p>Arayüz adı OpenTelemetry 1.5x'te taşındı: önce yenisi ({@code io.opentelemetry.sdk.common.export}, 1.62'nin
 * {@code SenderUtil}'inin aradığı), o sınıf yoksa (eski OpenTelemetry) eskisi denenir. Bozuk bir sağlayıcı kaydı
 * ({@link ServiceConfigurationError}) "yok" sayılır: dışa aktarıcı da onunla kurulamazdı.
 */
final class GrpcSenderProbe {

    static final List<String> SPI_INTERFACES = List.of(
            "io.opentelemetry.sdk.common.export.GrpcSenderProvider",
            "io.opentelemetry.exporter.internal.grpc.GrpcSenderProvider");

    private GrpcSenderProbe() {
    }

    static boolean present(ClassLoader classLoader) {
        return present(classLoader, SPI_INTERFACES);
    }

    static boolean present(ClassLoader classLoader, List<String> spiInterfaces) {
        for (String name : spiInterfaces) {
            Class<?> spi;
            try {
                spi = Class.forName(name, false, classLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            try {
                // stream(): sağlayıcı sınıfı bulunur ama ÖRNEKLENMEZ.
                return ServiceLoader.load(spi, classLoader).stream().findAny().isPresent();
            } catch (ServiceConfigurationError e) {
                return false;
            }
        }
        return false;
    }
}
