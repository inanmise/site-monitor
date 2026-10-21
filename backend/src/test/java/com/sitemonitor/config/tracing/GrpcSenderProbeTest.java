package com.sitemonitor.config.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gRPC göndericisi yoklaması (2026-10-21): bu projenin sınıf yolunda gRPC göndericisi YOK (yalnız JDK HTTP göndericisi —
 * kasıtlı); ServiceLoader kaydı olan bir arayüz "var" der; olmayan arayüz atlanır ve sıradaki denenir.
 */
class GrpcSenderProbeTest {

    /** Kaydı olmayan SPI. */
    public interface UnregisteredSpi {
    }

    private final ClassLoader cl = getClass().getClassLoader();

    @Test
    @DisplayName("gerçek sınıf yolu: OpenTelemetry gRPC göndericisi yok (OkHttp hariç tutuldu)")
    void projectHasNoGrpcSender() {
        assertThat(GrpcSenderProbe.present(cl)).isFalse();
        assertThat(classPresent("io.opentelemetry.sdk.common.export.GrpcSenderProvider"))
                .as("1.62'nin aradığı SPI arayüzü sınıf yolunda — yoklama doğru adı arıyor").isTrue();
        assertThat(classPresent("io.opentelemetry.sdk.common.export.HttpSenderProvider")).isTrue();
    }

    @Test
    @DisplayName("kayıtlı sağlayıcı varsa true; arayüz yoksa sıradakine geçilir; kayıtsız arayüz false")
    void serviceLoaderLookup() {
        assertThat(GrpcSenderProbe.present(cl, List.of(FakeGrpcSenderSpi.class.getName()))).isTrue();
        assertThat(GrpcSenderProbe.present(cl, List.of("com.example.Missing", FakeGrpcSenderSpi.class.getName()))).isTrue();
        assertThat(GrpcSenderProbe.present(cl, List.of(UnregisteredSpi.class.getName(), FakeGrpcSenderSpi.class.getName())))
                .as("ilk VAR olan arayüz belirleyicidir").isFalse();
        assertThat(GrpcSenderProbe.present(cl, List.of("com.example.Missing"))).isFalse();
    }

    private boolean classPresent(String name) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
