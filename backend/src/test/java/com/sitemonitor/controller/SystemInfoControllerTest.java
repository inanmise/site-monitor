package com.sitemonitor.controller;

import com.sitemonitor.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kimlik doğrulamalı HERKESE açık sürüm yüzeyi (Nav sürüm çipi + Yardım → Yenilikler). Yanıt şekli
 * ön yüz sözleşmesidir; yayın indeksi yokken alanlar null'a düşer ama uç 200 döner.
 */
@WebMvcTest(SystemInfoController.class)
class SystemInfoControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean BuildInfo buildInfo;
    @MockitoBean ReleaseIndexService releaseIndex;
    @MockitoBean DeploymentHistoryService deployments;

    // Web altyapısı (interceptor/filter bağımlılıkları)
    @MockitoBean AppSettingsService settingsService;
    @MockitoBean AuditService auditService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean SchedulerService schedulerService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean HttpMetricsService httpMetricsService;

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private static BuildInfo.Snapshot snapshot() {
        return new BuildInfo.Snapshot("20.54.0", "file", SHA, "2026-09-10T18:00:00Z", "20.54.0",
                "ghcr.io/example/site-monitor:v20.54.0", "prod", "sitemonitor", 42, "20.54.0", "cfg",
                "inst-1", "host-1", "pod-1", "node-1", "25", "2026-09-11T08:00:00Z",
                Instant.parse("2026-09-11T08:00:00Z").toEpochMilli());
    }

    private static MockHttpSession user() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "user1");
        s.setAttribute("systemRole", "USER");
        return s;
    }

    @BeforeEach
    void setUp() {
        when(buildInfo.get()).thenReturn(snapshot());
        when(releaseIndex.find(any())).thenReturn(Optional.empty());
        when(releaseIndex.meta()).thenReturn(Map.of("loaded", false, "count", 0));
        when(deployments.current(anyString()))
                .thenReturn(new DeploymentHistoryService.Current(null, null, null, null, 0, null, null));
    }

    @Test
    @DisplayName("GET /version: sürüm/commit/ortam/helm/instance alanları; dağıtım ve yayın yoksa live/release null (200)")
    void version_shapeWithoutHistory() throws Exception {
        mvc.perform(get("/api/system/version").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.version").value("20.54.0"))
                .andExpect(jsonPath("$.data.versionSource").value("file"))
                .andExpect(jsonPath("$.data.commit").value(SHA))
                .andExpect(jsonPath("$.data.commitShort").value("01234567"))
                .andExpect(jsonPath("$.data.environment").value("prod"))
                .andExpect(jsonPath("$.data.mismatch").value(false))
                .andExpect(jsonPath("$.data.helm.revision").value(42))
                .andExpect(jsonPath("$.data.instance.pod").value("pod-1"))
                .andExpect(jsonPath("$.data.startedAt").value("2026-09-11T08:00:00Z"))
                .andExpect(jsonPath("$.data.live").value(nullValue()))
                .andExpect(jsonPath("$.data.release").value(nullValue()))
                .andExpect(jsonPath("$.data.releaseLagSeconds").value(nullValue()))
                .andExpect(jsonPath("$.data.releaseIndex.loaded").value(false));
    }

    @Test
    @DisplayName("GET /version: boş build alanları null'a normalize edilir (boş dize sızmaz)")
    void version_blankFieldsBecomeNull() throws Exception {
        when(buildInfo.get()).thenReturn(new BuildInfo.Snapshot("1.0.0", "file", "", "", "", "", "local", "", null, "",
                "", "inst", "host", "", "", "25", "2026-09-11T08:00:00Z", 0L));
        mvc.perform(get("/api/system/version").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commit").value(nullValue()))
                .andExpect(jsonPath("$.data.commitShort").value(nullValue()))
                .andExpect(jsonPath("$.data.imageRef").value(nullValue()))
                .andExpect(jsonPath("$.data.helm.release").value(nullValue()))
                .andExpect(jsonPath("$.data.instance.pod").value(nullValue()))
                .andExpect(jsonPath("$.data.environment").value("local"));
    }

    @Test
    @DisplayName("GET /version: dağıtım + yayın varsa live bloğu, release öne çıkanları (feat önce, ≤3) ve yayın gecikmesi")
    void version_withHistoryAndRelease() throws Exception {
        DeploymentHistoryService.Current c = new DeploymentHistoryService.Current(
                "20.54.0", "2026-09-11T08:00:00Z", DeploymentHistoryService.Kind.UPGRADE, "20.53.2", 1, "2026-09-11T09:00:00Z", 5L);
        when(deployments.current("prod")).thenReturn(c);
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("version", "20.54.0"); live.put("since", "2026-09-11T08:00:00Z"); live.put("kind", "UPGRADE");
        when(deployments.currentToMap(c)).thenReturn(live);
        when(deployments.leadTimeSeconds("20.54.0", "2026-09-11T08:00:00Z")).thenReturn(3600L);
        when(releaseIndex.find("20.54.0")).thenReturn(Optional.of(new ReleaseIndexService.Release(
                "20.54.0", "v20.54.0", "2026-09-11T07:00:00Z", SHA, "20.53.2", "minor", false,
                Map.of("feat", 1, "fix", 2, "docs", 1),
                List.of(new ReleaseIndexService.Change("docs", null, false, "aaaaaaaa", "belge"),
                        new ReleaseIndexService.Change("fix", "cache", false, "bbbbbbbb", "düzeltme 1"),
                        new ReleaseIndexService.Change("feat", "deploy", false, "cccccccc", "yeni özellik"),
                        new ReleaseIndexService.Change("fix", null, false, "dddddddd", "düzeltme 2")),
                false, 0)));

        mvc.perform(get("/api/system/version").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.live.version").value("20.54.0"))
                .andExpect(jsonPath("$.data.live.kind").value("UPGRADE"))
                .andExpect(jsonPath("$.data.release.version").value("20.54.0"))
                .andExpect(jsonPath("$.data.release.bump").value("minor"))
                .andExpect(jsonPath("$.data.release.counts.fix").value(2))
                .andExpect(jsonPath("$.data.release.highlights.length()").value(3))
                .andExpect(jsonPath("$.data.release.highlights[0].type").value("feat"))
                .andExpect(jsonPath("$.data.release.highlights[0].subject").value("yeni özellik"))
                .andExpect(jsonPath("$.data.release.highlights[1].type").value("fix"))
                .andExpect(jsonPath("$.data.release.highlights[2].type").value("fix"))
                .andExpect(jsonPath("$.data.releaseLagSeconds").value(3600));
    }

    @Test
    @DisplayName("GET /version: oturumsuz istek 401 (sürüm/commit giriş öncesi sızmaz)")
    void version_requiresSession() throws Exception {
        mvc.perform(get("/api/system/version")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /releases: size 100'e kırpılır, page ≥ 1, bilinmeyen density 'deployed'e düşer; koşan ortam kullanılır")
    void releases_capsAndNormalises() throws Exception {
        when(deployments.releases(anyString(), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn(Map.of("items", List.of(), "total", 0));

        mvc.perform(get("/api/system/releases?page=0&size=999&density=bogus&type=feat&q=x").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(0));
        ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> density = ArgumentCaptor.forClass(String.class);
        verify(deployments).releases(eq("prod"), density.capture(), eq("feat"), eq("x"), page.capture(), size.capture());
        assertThat(page.getValue()).isEqualTo(1);
        assertThat(size.getValue()).isEqualTo(DeploymentHistoryService.MAX_PAGE);
        assertThat(density.getValue()).isEqualTo("deployed");

        mvc.perform(get("/api/system/releases?density=all&size=0").session(user())).andExpect(status().isOk());
        verify(deployments).releases(eq("prod"), eq("all"), eq("all"), isNull(), eq(1), eq(1));
    }

    @Test
    @DisplayName("GET /releases/notes: since parametresi servise geçer, currentVersion + items döner")
    void notes_since() throws Exception {
        when(deployments.notesSince("20.53.0")).thenReturn(List.of(Map.of("version", "20.54.0"), Map.of("version", "20.53.1")));
        mvc.perform(get("/api/system/releases/notes?since=20.53.0").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value("20.54.0"))
                .andExpect(jsonPath("$.data.since").value("20.53.0"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].version").value("20.54.0"));

        when(deployments.notesSince(null)).thenReturn(List.of());
        mvc.perform(get("/api/system/releases/notes").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.since").value(nullValue()))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }
}
