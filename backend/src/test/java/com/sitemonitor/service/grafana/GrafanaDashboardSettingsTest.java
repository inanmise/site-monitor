package com.sitemonitor.service.grafana;

import com.sitemonitor.service.AppSettingsCatalog;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.metrics.MetricsExportSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Grafana panosu ayarları (2026-10-18): etiket eşleyicisi doğrulaması (doğrusal tarama, süslü ayraç yok), bağlantı kökü,
 * uid, bölümler, sayı aralıkları; kayıtlı ayarlardan seçenekler (geçersiz eski değer varsayılana düşer, boş kök genel
 * uygulama adresine düşer); katalog ve GLOBAL_ONLY kaydı; aktarıcı ayarlarının sınırları.
 */
class GrafanaDashboardSettingsTest {

    @Test
    @DisplayName("etiket eşleyicisi: geçerli biçimler kabul, bozuklar reddedilir; uzun girdi önce uzunlukla düşer")
    void selector() {
        for (String ok : List.of("", "namespace=\"site-monitor-prod\"", "job=\"site-monitor\", namespace=~\"sm-.*\"",
                "app!=\"x\"", "env!~\"te\\\"st\"", "  a = \"b\" ")) {
            assertThat(GrafanaDashboardSettings.selectorProblem(ok)).as(ok).isNull();
        }
        for (String bad : List.of("namespace=site", "{job=\"x\"}", "job=\"x\",", "=\"x\"", "9job=\"x\"", "job==\"x\"", "job=\"x",
                "job=\"x\" namespace=\"y\"", "jöb=\"x\"", "job=\"a\nb\"")) {
            assertThat(GrafanaDashboardSettings.selectorProblem(bad)).as(bad).isNotNull();
        }
        assertThat(GrafanaDashboardSettings.selectorProblem("a=\"" + "x".repeat(400) + "\"")).isNotNull();
    }

    @Test
    @DisplayName("bağlantı kökü: http(s) şart, sorgu / parça / boşluk / tırnak yok; sondaki / atılır")
    void url() {
        assertThat(GrafanaDashboardSettings.urlProblem("https://sm.example.org")).isNull();
        assertThat(GrafanaDashboardSettings.urlProblem("http://10.0.0.1:8080/sitemonitor")).isNull();
        for (String bad : List.of("ftp://x", "https://", "https://x.org/?tab=a", "https://x.org/#a", "https://x y", "javascript:alert(1)",
                "https://x.org/\"", "https:///x")) {
            assertThat(GrafanaDashboardSettings.urlProblem(bad)).as(bad).isNotNull();
        }
        assertThat(GrafanaDashboardSettings.trimUrl("https://x.org///")).isEqualTo("https://x.org");
    }

    @Test
    @DisplayName("uid, bölümler, İlk N, başlık, dil / zaman / yenileme seçenekleri")
    void otherFields() {
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_UID, "sitemonitor-overview")).isNull();
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_UID, "x".repeat(41))).isNotNull();
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_SECTIONS, "alerts,overview")).isNull();
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_SECTIONS, "alerts,bogus")).isNotNull();
        assertThat(GrafanaDashboardSettings.parseSections("runtime, alerts ,overview")).containsExactly("overview", "alerts", "runtime");
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_TOP_N, "100")).isNull();
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_TOP_N, "101")).isNotNull();
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_TITLE, "x".repeat(121))).isNotNull();
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_LANGUAGE, "en")).isNull();
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_TIME_FROM, "now-1y")).isNotNull();
        assertThat(GrafanaDashboardSettings.problem(GrafanaDashboardSettings.KEY_REFRESH, "5m")).isNull();
        assertThatThrownBy(() -> GrafanaDashboardSettings.validate(GrafanaDashboardSettings.KEY_UID, "a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricsExportSettings.validate(MetricsExportSettings.KEY_MAX_ENTITIES, "50"))
                .isInstanceOf(IllegalArgumentException.class);
        MetricsExportSettings.validate(MetricsExportSettings.KEY_REFRESH_SECONDS, "120");
    }

    @Test
    @DisplayName("kayıtlı ayarlardan seçenekler: varsayılanlar, geçersiz eski değer → varsayılan, boş kök → genel uygulama adresi")
    void currentOptions() {
        Map<String, String> stored = new HashMap<>();
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getString(anyString(), anyString())).thenAnswer(i -> stored.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(s.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> stored.containsKey(i.<String>getArgument(0))
                ? Boolean.parseBoolean(stored.get(i.<String>getArgument(0))) : i.getArgument(1));
        when(s.getInt(anyString(), anyInt())).thenAnswer(i -> stored.containsKey(i.<String>getArgument(0))
                ? Integer.parseInt(stored.get(i.<String>getArgument(0))) : i.getArgument(1));
        GrafanaDashboardOptions d = GrafanaDashboardSettings.current(s, "1.0.0");
        assertThat(d.uid()).isEqualTo("sitemonitor-overview");
        assertThat(d.sections()).isEqualTo(GrafanaDashboardGenerator.SECTIONS);
        assertThat(d.linkBaseUrl()).isEmpty();
        assertThat(d.perMonitor()).isTrue();
        assertThat(d.topN()).isEqualTo(10);
        stored.put(GrafanaDashboardSettings.KEY_APP_BASE_URL, "https://sm.example.org/");
        stored.put(GrafanaDashboardSettings.KEY_UID, "bad uid");
        stored.put(GrafanaDashboardSettings.KEY_SELECTOR, "broken");
        stored.put(GrafanaDashboardSettings.KEY_TIME_FROM, "now-99y");
        stored.put(GrafanaDashboardSettings.KEY_TOP_N, "9999");
        stored.put(GrafanaDashboardSettings.KEY_LANGUAGE, "en");
        GrafanaDashboardOptions o = GrafanaDashboardSettings.current(s, "1.0.0");
        assertThat(o.linkBaseUrl()).isEqualTo("https://sm.example.org");
        assertThat(o.uid()).isEqualTo("sitemonitor-overview");
        assertThat(o.selector()).isEmpty();
        assertThat(o.timeFrom()).isEqualTo("now-24h");
        assertThat(o.topN()).isEqualTo(100);
        assertThat(o.english()).isTrue();
        assertThat(o.appVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("katalog: her anahtar grup 'grafana' ve GLOBAL_ONLY; ENUM seçenekleri ayar sınıfıyla aynı")
    void catalog() {
        for (String key : List.of(GrafanaDashboardSettings.KEY_TITLE, GrafanaDashboardSettings.KEY_UID, GrafanaDashboardSettings.KEY_LINK_BASE_URL,
                GrafanaDashboardSettings.KEY_SELECTOR, GrafanaDashboardSettings.KEY_TIME_FROM, GrafanaDashboardSettings.KEY_REFRESH,
                GrafanaDashboardSettings.KEY_LANGUAGE, GrafanaDashboardSettings.KEY_SECTIONS, GrafanaDashboardSettings.KEY_PER_MONITOR,
                GrafanaDashboardSettings.KEY_DEFAULT_TEAM, GrafanaDashboardSettings.KEY_TOP_N, MetricsExportSettings.KEY_ENABLED,
                MetricsExportSettings.KEY_PER_MONITOR, MetricsExportSettings.KEY_MAX_ENTITIES, MetricsExportSettings.KEY_REFRESH_SECONDS)) {
            AppSettingsCatalog.Setting setting = AppSettingsCatalog.byKey(key);
            assertThat(setting).as("katalogda yok: " + key).isNotNull();
            assertThat(setting.group()).isEqualTo("grafana");
            assertThat(AppSettingsCatalog.isGlobalOnly(key)).as("GLOBAL_ONLY değil: " + key).isTrue();
        }
        assertThat(AppSettingsCatalog.byKey(GrafanaDashboardSettings.KEY_TIME_FROM).enumOptions())
                .isEqualTo(GrafanaDashboardSettings.TIME_FROM_OPTIONS);
        assertThat(GrafanaDashboardSettings.KEYS).allMatch(GrafanaDashboardSettings::isKey);
        assertThat(MetricsExportSettings.KEYS).allMatch(MetricsExportSettings::isKey);
    }

    @Test
    @DisplayName("aktarıcı ayarları: tavan ve aralık sınırlanır")
    void exporterLimits() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getInt(MetricsExportSettings.KEY_MAX_ENTITIES, MetricsExportSettings.MAX_ENTITIES_DEFAULT)).thenReturn(1);
        when(s.getInt(MetricsExportSettings.KEY_REFRESH_SECONDS, MetricsExportSettings.REFRESH_DEFAULT)).thenReturn(99999);
        assertThat(MetricsExportSettings.maxEntities(s)).isEqualTo(MetricsExportSettings.MAX_ENTITIES_MIN);
        assertThat(MetricsExportSettings.refreshSeconds(s)).isEqualTo(MetricsExportSettings.REFRESH_MAX);
        assertThat(MetricsExportSettings.enabled(null)).isTrue();
    }
}
