package com.sitemonitor.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Collection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code sitemonitor_build_info} — kardinalite 1 ve {@code environment} etiketi Genel Ayarlar'daki ortam adını
 * izler (2026-09-29): ad değişince eski seri SİLİNİR, yeni etiketli tek seri kalır (panolar yeni adla süzer).
 */
class BuildInfoMetricsTest {

    private static MockEnvironment env() {
        return new MockEnvironment()
                .withProperty("site.monitor.version", "20.91.0")
                .withProperty("site.monitor.build.commit", "0123456789abcdef")
                .withProperty("site.monitor.environment", "staging");
    }

    private static Collection<Gauge> buildInfo(SimpleMeterRegistry r) {
        return r.find("sitemonitor.build.info").gauges();
    }

    @Test
    @DisplayName("açılış: tek seri, sabit 1, etiketler version/commit/environment")
    void register_singleSeries() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        BuildInfoMetrics m = new BuildInfoMetrics(r, new BuildInfo(env()));
        m.register();
        assertThat(buildInfo(r)).hasSize(1);
        Gauge g = buildInfo(r).iterator().next();
        assertThat(g.value()).isEqualTo(1.0);
        assertThat(g.getId().getTag("version")).isEqualTo("20.91.0");
        assertThat(g.getId().getTag("commit")).isEqualTo("01234567");
        assertThat(g.getId().getTag("environment")).isEqualTo("staging");
        assertThat(r.find("sitemonitor.deployment.started.seconds").gauge()).isNotNull();
    }

    @Test
    @DisplayName("ortam adı ayarı değişince etiket yenilenir: eski seri gider, yeni ad tek seri; ilgisiz ayar dokunmaz")
    void settingsChange_relabels() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        AppSettingsService settings = mock(AppSettingsService.class);
        BuildInfo b = new BuildInfo(env());
        b.setSettings(settings);
        BuildInfoMetrics m = new BuildInfoMetrics(r, b);
        m.register();

        when(settings.getOverride(BuildInfo.ENV_KEY)).thenReturn("prod");
        m.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.app.base-url"), "save"));
        assertThat(buildInfo(r)).extracting(g -> g.getId().getTag("environment")).containsExactly("staging");

        m.onSettingsChanged(new AppSettingsChangedEvent(Set.of(BuildInfo.ENV_KEY), "save"));
        assertThat(buildInfo(r)).hasSize(1);
        Gauge g = buildInfo(r).iterator().next();
        assertThat(g.getId().getTag("environment")).isEqualTo("prod");
        assertThat(g.getId().getTag("version")).isEqualTo("20.91.0");
        assertThat(g.value()).isEqualTo(1.0);

        // Başka pod'un kaydı tazelemeyle gelir (boş anahtar kümesi = "hepsi olabilir"); boşaltma env'e döner
        when(settings.getOverride(BuildInfo.ENV_KEY)).thenReturn(null);
        m.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        assertThat(buildInfo(r)).extracting(x -> x.getId().getTag("environment")).containsExactly("staging");
    }
}
