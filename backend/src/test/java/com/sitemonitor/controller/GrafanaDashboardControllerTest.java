package com.sitemonitor.controller;

import com.sitemonitor.model.Team;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.grafana.GrafanaDashboardGenerator;
import com.sitemonitor.service.grafana.GrafanaDashboardSettings;
import com.sitemonitor.service.metrics.MetricsExportSettings;
import com.sitemonitor.service.metrics.MonitoringMetricsExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ayarlar → Grafana panosu uçları (2026-10-18): okuma kapısı (ADMIN; kapsamlı müdür SALT OKUNUR, takım dizini almaz), yazma
 * ve indirme kapısı (yalnız global yönetici), yalnız gönderilen + değişen alanların yazılması, alan adlı doğrulama (400
 * {@code VALIDATION_FAILED} + field), ön izleme (kayıt yok), indirme başlıkları (attachment + application/json) ve denetim.
 */
@WebMvcTest(GrafanaDashboardController.class)
class GrafanaDashboardControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppSettingsService settings;
    @MockitoBean AuditService auditService;
    @MockitoBean TeamRepository teams;
    @MockitoBean MonitoringMetricsExporter exporter;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    final Map<String, String> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        stored.clear();
        stored.put(GrafanaDashboardSettings.KEY_APP_BASE_URL, "https://sitemonitor.example.org");
        // application.properties varsayılanları (Environment) — gerçek getString bunları çözer
        stored.put(GrafanaDashboardSettings.KEY_UID, "sitemonitor-overview");
        stored.put(GrafanaDashboardSettings.KEY_TIME_FROM, "now-24h");
        stored.put(GrafanaDashboardSettings.KEY_REFRESH, "1m");
        stored.put(GrafanaDashboardSettings.KEY_LANGUAGE, "tr");
        stored.put(GrafanaDashboardSettings.KEY_SECTIONS, String.join(",", GrafanaDashboardGenerator.SECTIONS));
        stored.put(GrafanaDashboardSettings.KEY_PER_MONITOR, "true");
        stored.put(GrafanaDashboardSettings.KEY_TOP_N, "10");
        stored.put(MetricsExportSettings.KEY_ENABLED, "true");
        stored.put(MetricsExportSettings.KEY_PER_MONITOR, "true");
        stored.put(MetricsExportSettings.KEY_MAX_ENTITIES, "5000");
        stored.put(MetricsExportSettings.KEY_REFRESH_SECONDS, "60");
        lenient().when(settings.getString(anyString(), any())).thenAnswer(i -> stored.getOrDefault(i.getArgument(0), i.getArgument(1)));
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            String v = stored.get(i.<String>getArgument(0));
            return v == null ? i.getArgument(1) : Boolean.parseBoolean(v);
        });
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> {
            String v = stored.get(i.<String>getArgument(0));
            return v == null ? i.getArgument(1) : Integer.parseInt(v);
        });
        Team t = new Team(); t.setId(14L); t.setName("Dijital Kanallar");
        lenient().when(teams.findAll()).thenReturn(List.of(t));
        lenient().when(exporter.tokenConfigured()).thenReturn(true);
        lenient().when(exporter.lastStatus()).thenReturn(new MonitoringMetricsExporter.Status(true, true, "2026-10-18T08:00:00", 120, 900,
                Map.of("monitors", 640), Map.of("overview", true, "alerts", false), List.of(), 60, 5000));
    }

    @Test
    @DisplayName("GET: oturumsuz 401, USER 403, global yönetici 200 (takım dizini + özet + aktarıcı), kapsamlı müdür SALT OKUNUR ve takım dizinsiz")
    void getGates() throws Exception {
        mvc.perform(get("/api/admin/grafana/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/grafana/settings").session(session("USER", false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/grafana/settings").session(session("ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(false))
                .andExpect(jsonPath("$.data.values.uid").value("sitemonitor-overview"))
                .andExpect(jsonPath("$.data.values.per_monitor").value(true))
                .andExpect(jsonPath("$.data.values.sections.length()").value(GrafanaDashboardGenerator.SECTIONS.size()))
                .andExpect(jsonPath("$.data.effective_link_base_url").value("https://sitemonitor.example.org"))
                .andExpect(jsonPath("$.data.teams[0]").value("Dijital Kanallar"))
                .andExpect(jsonPath("$.data.summary.panels").isNumber())
                .andExpect(jsonPath("$.data.summary.file_name").value("sitemonitor-grafana-dashboard-local.json"))
                .andExpect(jsonPath("$.data.exporter.state").value("running"))
                .andExpect(jsonPath("$.data.exporter.token_configured").value(true))
                .andExpect(jsonPath("$.data.metrics_path").value("/metrics/sitemonitor"))
                .andExpect(jsonPath("$.data.runtime_metrics_path").value("/metrics"))
                .andExpect(jsonPath("$.data.token_env").value("METRICS_EXPORT_TOKEN"))
                .andExpect(jsonPath("$.data.exporter.sources[?(@.source == 'alerts')].up").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.data.revision").value(GrafanaDashboardGenerator.DASHBOARD_REVISION))
                .andExpect(jsonPath("$.data.metrics[0].name").value("sitemonitor_monitor_state"));
        mvc.perform(get("/api/admin/grafana/settings").session(session("ADMIN", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(true))
                .andExpect(jsonPath("$.data.teams.length()").value(0));
    }

    @Test
    @DisplayName("GET: aktarıcı kapalı → disabled; hiç tur koşmadı ya da son tur kapalıyken koştu → waiting (canlı sayı yok)")
    void exporterStates() throws Exception {
        when(exporter.lastStatus()).thenReturn(null);
        mvc.perform(get("/api/admin/grafana/settings").session(session("ADMIN", false)))
                .andExpect(jsonPath("$.data.exporter.state").value("waiting"));
        when(exporter.lastStatus()).thenReturn(new MonitoringMetricsExporter.Status(false, true, "2026-10-18T08:00:00", 3, 2,
                Map.of(), Map.of(), List.of(), 60, 5000));
        mvc.perform(get("/api/admin/grafana/settings").session(session("ADMIN", false)))
                .andExpect(jsonPath("$.data.exporter.state").value("waiting"))
                .andExpect(jsonPath("$.data.exporter.series_total").doesNotExist());
        stored.put(MetricsExportSettings.KEY_ENABLED, "false");
        mvc.perform(get("/api/admin/grafana/settings").session(session("ADMIN", false)))
                .andExpect(jsonPath("$.data.exporter.state").value("disabled"));
    }

    @Test
    @DisplayName("GET: belirteç yok → no_token, yalnız EVET / HAYIR (değer asla); canlı sayılar yok; yanıtta belirteç değeri geçmez")
    void exporterWithoutToken() throws Exception {
        when(exporter.tokenConfigured()).thenReturn(false);
        MvcResult r = mvc.perform(get("/api/admin/grafana/settings").session(session("ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exporter.state").value("no_token"))
                .andExpect(jsonPath("$.data.exporter.token_configured").value(false))
                .andExpect(jsonPath("$.data.exporter.series_total").doesNotExist())
                .andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("site.monitor.metrics.export.token").doesNotContain("\"token\":");
    }

    @Test
    @DisplayName("PUT: kapsamlı müdür 403 (yazmaz, denetlenmez); global yönetici yalnız DEĞİŞEN alanları yazar ve denetler")
    void saveWritesOnlyChangedFields() throws Exception {
        mvc.perform(put("/api/admin/grafana/settings").session(session("ADMIN", true)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"uid\":\"x\"}")).andExpect(status().isForbidden());
        verify(settings, never()).save(any(), anyString());

        mvc.perform(put("/api/admin/grafana/settings").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":\"sitemonitor-overview\",\"selector\":\"namespace=\\\"site-monitor-prod\\\"\",\"per_monitor\":false,"
                                + "\"sections\":[\"alerts\",\"overview\"],\"top_n\":20,\"default_team\":\"Dijital Kanallar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(settings).save(argThat(body -> {
            @SuppressWarnings("unchecked") Map<String, Object> v = (Map<String, Object>) body.get("values");
            return !v.containsKey(GrafanaDashboardSettings.KEY_UID)                       // değişmedi → yazılmaz
                    && "namespace=\"site-monitor-prod\"".equals(v.get(GrafanaDashboardSettings.KEY_SELECTOR))
                    && "false".equals(v.get(GrafanaDashboardSettings.KEY_PER_MONITOR))
                    && "alerts,overview".equals(v.get(GrafanaDashboardSettings.KEY_SECTIONS))
                    && "20".equals(v.get(GrafanaDashboardSettings.KEY_TOP_N))
                    && "Dijital Kanallar".equals(v.get(GrafanaDashboardSettings.KEY_DEFAULT_TEAM));
        }), eq("root"));
        verify(auditService).recordAction(eq("GRAFANA_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("SETTINGS"), eq("grafana"), anyString(), anyString());
    }

    @Test
    @DisplayName("PUT: değişiklik yoksa yazma ve denetim yok")
    void emptyChangeWritesNothing() throws Exception {
        mvc.perform(put("/api/admin/grafana/settings").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"uid\":\"sitemonitor-overview\"}")).andExpect(status().isOk());
        verify(settings, never()).save(any(), anyString());
        verify(auditService, never()).recordAction(eq("GRAFANA_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("PUT: alan hataları 400 VALIDATION_FAILED + field — uid, adres, eşleyici, bölüm, sayı, takım, tür")
    void validation() throws Exception {
        Object[][] cases = {
                {"{\"uid\":\"bad uid!\"}", "uid"},
                {"{\"link_base_url\":\"ftp://x\"}", "link_base_url"},
                {"{\"link_base_url\":\"https://sm.example.org/?tab=x\"}", "link_base_url"},
                {"{\"selector\":\"namespace=site\"}", "selector"},
                {"{\"selector\":\"{job=\\\"x\\\"}\"}", "selector"},
                {"{\"sections\":[]}", "sections"},
                {"{\"sections\":[\"nope\"]}", "sections"},
                {"{\"sections\":\"overview\"}", "sections"},
                {"{\"top_n\":1}", "top_n"},
                {"{\"top_n\":2.5}", "top_n"},
                {"{\"per_monitor\":\"yes\"}", "per_monitor"},
                {"{\"default_team\":\"Yok Böyle Takım\"}", "default_team"},
                {"{\"export_max_entities\":10}", "export_max_entities"},
                {"{\"export_refresh_seconds\":5}", "export_refresh_seconds"},
                {"{\"time_from\":\"now-3y\"}", "time_from"},
                {"{\"language\":\"de\"}", "language"},
                {"{\"title\":\"a\\nb\"}", "title"},
        };
        for (Object[] c : cases) {
            mvc.perform(put("/api/admin/grafana/settings").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                            .content((String) c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value((String) c[1]));
        }
        verify(settings, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("PUT: kayıtlı (artık geçersiz) değer aynen gelirse yeniden doğrulanmaz — diğer alanların kaydını kilitlemez")
    void unchangedInvalidValueIsNotRevalidated() throws Exception {
        stored.put(GrafanaDashboardSettings.KEY_DEFAULT_TEAM, "Silinmiş Takım");
        mvc.perform(put("/api/admin/grafana/settings").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"default_team\":\"Silinmiş Takım\",\"top_n\":5}"))
                .andExpect(status().isOk());
        verify(settings).save(argThat(body -> {
            @SuppressWarnings("unchecked") Map<String, Object> v = (Map<String, Object>) body.get("values");
            return v.size() == 1 && "5".equals(v.get(GrafanaDashboardSettings.KEY_TOP_N));
        }), anyString());
    }

    @Test
    @DisplayName("ön izleme: kapsamlı müdür de görür; kayıt ve denetim yok; kaydedilmemiş değerler özete yansır")
    void preview() throws Exception {
        mvc.perform(post("/api/admin/grafana/dashboard/preview").session(session("ADMIN", true)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\":[\"alerts\"],\"per_monitor\":false,\"link_base_url\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows").value(1))
                .andExpect(jsonPath("$.data.panels_by_section.alerts").isNumber())
                .andExpect(jsonPath("$.data.links").value(true))
                .andExpect(jsonPath("$.data.link_base_url").value("https://sitemonitor.example.org"));
        mvc.perform(post("/api/admin/grafana/dashboard/preview").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":\"bad uid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("uid"));
        mvc.perform(post("/api/admin/grafana/dashboard/preview").session(session("USER", false)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        verify(settings, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("indirme: yalnız global yönetici; attachment + application/json; içerik içe aktarılabilir pano; denetim GRAFANA_DASHBOARD_EXPORT")
    void download() throws Exception {
        mvc.perform(get("/api/admin/grafana/dashboard").session(session("ADMIN", true))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/grafana/dashboard").session(session("USER", false))).andExpect(status().isForbidden());
        MvcResult r = mvc.perform(get("/api/admin/grafana/dashboard").session(session("ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("sitemonitor-grafana-dashboard-local.json")))
                .andReturn();
        String body = new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"__inputs\"").contains("DS_PROMETHEUS").contains("\"templating\"").contains("https://sitemonitor.example.org/?tab=");
        verify(auditService).recordAction(eq("GRAFANA_DASHBOARD_EXPORT"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("SETTINGS"), eq("sitemonitor-overview"), anyString());
    }

    @Test
    @DisplayName("değişiklik karşılaştırması alan türüne göre: büyük/küçük harf farkı başlık / eşleyicide değişiklik; bölüm sırası ve bool yazımı değil")
    void sameIsFieldAware() {
        assertThat(GrafanaDashboardController.same("title", "Panel, A", "panel, a")).isFalse();
        assertThat(GrafanaDashboardController.same("selector", "job=\"X\", ns=\"y\"", "job=\"x\", ns=\"y\"")).isFalse();
        assertThat(GrafanaDashboardController.same("title", " Pano ", "Pano")).isTrue();
        assertThat(GrafanaDashboardController.same("sections", "overview,alerts", "alerts, overview")).isTrue();
        assertThat(GrafanaDashboardController.same("sections", "overview,alerts", "overview")).isFalse();
        assertThat(GrafanaDashboardController.same("per_monitor", "TRUE", "true")).isTrue();
        assertThat(GrafanaDashboardController.same("per_monitor", "true", "false")).isFalse();
    }

    private static MockHttpSession session(String role, boolean scoped) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "ADMIN".equals(role) && !scoped ? "root" : "someone");
        s.setAttribute("systemRole", role);
        if (scoped) s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(7L)));
        return s;
    }
}
