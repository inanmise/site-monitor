package com.sitemonitor.config.tracing;

/**
 * {@link GrpcSenderProbeTest} için sahte SPI; sağlayıcısı
 * {@code src/test/resources/META-INF/services/com.sitemonitor.config.tracing.FakeGrpcSenderSpi} altında kayıtlı.
 */
public interface FakeGrpcSenderSpi {

    class Provider implements FakeGrpcSenderSpi {
        public Provider() {
        }
    }
}
