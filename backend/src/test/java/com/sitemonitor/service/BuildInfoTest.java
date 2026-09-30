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

    // ── Ortam adı: Genel Ayarlar > APP_ENVIRONMENT > otomatik (2026-09-29) ──────────────────────────

    @Test
    @DisplayName("öncelik: DB ayarı > APP_ENVIRONMENT > otomatik (pod'da unknown, pod dışında local)")
    void resolveEnvironment_priority() {
        assertThat(BuildInfo.resolveEnvironment("prod", "staging", "pod-1"))
                .isEqualTo(new BuildInfo.EnvName("prod", BuildInfo.EnvSource.SETTING));
        assertThat(BuildInfo.resolveEnvironment(null, "staging", "pod-1"))
                .isEqualTo(new BuildInfo.EnvName("staging", BuildInfo.EnvSource.ENV));
        assertThat(BuildInfo.resolveEnvironment("  ", " staging ", ""))
                .isEqualTo(new BuildInfo.EnvName("staging", BuildInfo.EnvSource.ENV));
        assertThat(BuildInfo.resolveEnvironment("", "", "pod-1"))
                .isEqualTo(new BuildInfo.EnvName("unknown", BuildInfo.EnvSource.AUTO));
        assertThat(BuildInfo.resolveEnvironment(null, null, null))
                .isEqualTo(new BuildInfo.EnvName("local", BuildInfo.EnvSource.AUTO));
        assertThat(BuildInfo.EnvSource.SETTING.wire()).isEqualTo("setting");
        assertThat(BuildInfo.EnvSource.AUTO.wire()).isEqualTo("auto");
    }

    @Test
    @DisplayName("biçimi bozuk DB değeri (elle yazılmış) yok sayılır → APP_ENVIRONMENT'e düşer")
    void resolveEnvironment_invalidSettingIgnored() {
        assertThat(BuildInfo.resolveEnvironment("Prod", "staging", "").name()).isEqualTo("staging");
        assertThat(BuildInfo.resolveEnvironment("a_b", "", "pod-1").name()).isEqualTo("unknown");
        assertThat(BuildInfo.resolveEnvironment("x".repeat(41), "dev", "").name()).isEqualTo("dev");
        assertThat(BuildInfo.resolveEnvironment("x".repeat(40), "dev", "").source()).isEqualTo(BuildInfo.EnvSource.SETTING);
    }

    @Test
    @DisplayName("bean CANLI: ayar değişince get() yeni adı döner, diğer alanlar aynı; boşaltınca APP_ENVIRONMENT'e döner")
    void bean_environmentIsLive() {
        AppSettingsService settings = org.mockito.Mockito.mock(AppSettingsService.class);
        BuildInfo b = new BuildInfo(full());           // APP_ENVIRONMENT = prod
        b.setSettings(settings);

        BuildInfo.Snapshot first = b.get();
        assertThat(first.environment()).isEqualTo("prod");
        assertThat(b.environmentName().source()).isEqualTo(BuildInfo.EnvSource.ENV);

        org.mockito.Mockito.when(settings.getOverride(BuildInfo.ENV_KEY)).thenReturn("staging");
        BuildInfo.Snapshot second = b.get();
        assertThat(second.environment()).isEqualTo("staging");
        assertThat(b.environmentName()).isEqualTo(new BuildInfo.EnvName("staging", BuildInfo.EnvSource.SETTING));
        assertThat(second).usingRecursiveComparison().ignoringFields("environment").isEqualTo(first);
        assertThat(b.get()).as("ad değişmedikçe aynı örnek").isSameAs(second);

        org.mockito.Mockito.when(settings.getOverride(BuildInfo.ENV_KEY)).thenReturn(null);
        assertThat(b.get().environment()).isEqualTo("prod");
        assertThat(b.environmentName().source()).isEqualTo(BuildInfo.EnvSource.ENV);
    }

    @Test
    @DisplayName("ayar servisi patlarsa get() çökmez — env/otomatik değer döner")
    void bean_settingsFailure_fallsBack() {
        AppSettingsService settings = org.mockito.Mockito.mock(AppSettingsService.class);
        org.mockito.Mockito.when(settings.getOverride(BuildInfo.ENV_KEY)).thenThrow(new IllegalStateException("boom"));
        BuildInfo b = new BuildInfo(full());
        b.setSettings(settings);
        assertThat(b.get().environment()).isEqualTo("prod");
    }
}
