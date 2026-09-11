package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çalışan örneğin build/dağıtım kimliği — Helm/Dockerfile env'lerinden BOŞ-toleranslı çözülür.
 * Compose/yerel koşumda hiçbir değer yoktur; hiçbir yer çökmez, alanlar boş dize kalır (null değil).
 */
class BuildInfoTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private static MockEnvironment full() {
        return new MockEnvironment()
                .withProperty("site.monitor.version", "20.54.0")
                .withProperty("site.monitor.build.commit", SHA)
                .withProperty("site.monitor.build.time", "2026-09-10T18:00:00Z")
                .withProperty("site.monitor.build.image-version", "20.54.0")
                .withProperty("site.monitor.image-ref", "ghcr.io/example/site-monitor:v20.54.0")
                .withProperty("site.monitor.environment", "prod")
                .withProperty("site.monitor.helm.release", "sitemonitor")
                .withProperty("site.monitor.helm.revision", "42")
                .withProperty("site.monitor.helm.chart-version", "20.54.0")
                .withProperty("site.monitor.config-checksum", "abc123");
    }

    @Test
    @DisplayName("tüm env'ler dolu → alanlar birebir, helm revizyonu sayı, JVM başlangıcı ISO-UTC")
    void resolve_fullEnvironment() {
        BuildInfo.Snapshot s = BuildInfo.resolve(full());
        assertThat(s.version()).isEqualTo("20.54.0");
        assertThat(s.versionSource()).isEqualTo("env");
        assertThat(s.commit()).isEqualTo(SHA);
        assertThat(s.commitShort()).isEqualTo("01234567");
        assertThat(s.buildTime()).isEqualTo("2026-09-10T18:00:00Z");
        assertThat(s.imageVersion()).isEqualTo("20.54.0");
        assertThat(s.imageRef()).isEqualTo("ghcr.io/example/site-monitor:v20.54.0");
        assertThat(s.environment()).isEqualTo("prod");
        assertThat(s.helmRelease()).isEqualTo("sitemonitor");
        assertThat(s.helmRevision()).isEqualTo(42);
        assertThat(s.helmChartVersion()).isEqualTo("20.54.0");
        assertThat(s.configChecksum()).isEqualTo("abc123");
        assertThat(s.mismatch()).isFalse();
        assertThat(s.jvmStartedAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(s.jvmStartEpochMillis()).isPositive();
        assertThat(s.uptimeSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(s.instanceId()).isNotBlank();
        assertThat(s.hostname()).isNotBlank();
        assertThat(s.javaVersion()).isNotBlank();
    }

    @Test
    @DisplayName("boş env: alanlar BOŞ DİZE (null değil), helm revizyonu null, mismatch false — çökmez")
    void resolve_blankTolerant() {
        BuildInfo.Snapshot s = BuildInfo.resolve(new MockEnvironment()
                .withProperty("site.monitor.version", "1.0.0")
                .withProperty("site.monitor.build.commit", "   "));
        assertThat(s.commit()).isEmpty();
        assertThat(s.commitShort()).isEmpty();
        assertThat(s.buildTime()).isEmpty();
        assertThat(s.imageVersion()).isEmpty();
        assertThat(s.imageRef()).isEmpty();
        assertThat(s.helmRelease()).isEmpty();
        assertThat(s.helmRevision()).isNull();
        assertThat(s.helmChartVersion()).isEmpty();
        assertThat(s.configChecksum()).isEmpty();
        assertThat(s.mismatch()).isFalse();
    }

    @Test
    @DisplayName("ortam boşsa: POD_NAME yoksa 'local', varsa 'unknown' (Helm env eksik ama pod'dayız)")
    void resolve_environmentFallback() {
        BuildInfo.Snapshot s = BuildInfo.resolve(new MockEnvironment().withProperty("site.monitor.version", "1.0.0"));
        String pod = System.getenv("POD_NAME");
        String expected = (pod == null || pod.isBlank()) ? "local" : "unknown";
        assertThat(s.environment()).isEqualTo(expected);
        assertThat(s.podName()).isEqualTo(pod == null ? "" : pod);

        // Açıkça verilen ortam kırpılarak taşınır
        assertThat(BuildInfo.resolve(new MockEnvironment().withProperty("site.monitor.environment", " staging ")).environment())
                .isEqualTo("staging");
    }

    @Test
    @DisplayName("helm revizyonu sayı değilse null (çökmez); kırpılmış sayı kabul")
    void resolve_helmRevisionNonNumeric() {
        assertThat(BuildInfo.resolve(new MockEnvironment().withProperty("site.monitor.helm.revision", "abc")).helmRevision()).isNull();
        assertThat(BuildInfo.resolve(new MockEnvironment().withProperty("site.monitor.helm.revision", " 7 ")).helmRevision()).isEqualTo(7);
        assertThat(BuildInfo.resolve(new MockEnvironment().withProperty("site.monitor.helm.revision", "")).helmRevision()).isNull();
    }

    @Test
    @DisplayName("mismatch(): VERSION dosyası ile imaj etiketi ayrışınca true; biri boşsa false")
    void mismatch() {
        MockEnvironment env = full().withProperty("site.monitor.build.image-version", "20.53.9");
        assertThat(BuildInfo.resolve(env).mismatch()).isTrue();
        assertThat(BuildInfo.resolve(full().withProperty("site.monitor.build.image-version", "")).mismatch()).isFalse();
    }

    @Test
    @DisplayName("commitShort(): 8 karakter; kısa commit olduğu gibi")
    void commitShort() {
        assertThat(BuildInfo.resolve(full()).commitShort()).hasSize(8);
        assertThat(BuildInfo.resolve(full().withProperty("site.monitor.build.commit", "abc")).commitShort()).isEqualTo("abc");
    }

    @Test
    @DisplayName("bean: yapıcı açılışta bir kez çözer, get() aynı anlık görüntüyü döner")
    void beanSnapshotIsStable() {
        BuildInfo b = new BuildInfo(full());
        assertThat(b.get()).isSameAs(b.get());
        assertThat(b.get().version()).isEqualTo("20.54.0");
    }
}
