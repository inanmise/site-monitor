package com.sitemonitor.controller;

import com.sitemonitor.repository.AppSettingRepository;
import com.sitemonitor.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ayarlar → Genel Ayarlar → "Ortam adı" uçtan uca (2026-09-29): GERÇEK AppSettingsService + BuildInfo, iki
 * denetleyici. Kaydedince {@code /api/system/version} bir SONRAKİ istekte yeni adı döner (yeniden başlatma yok);
 * boşaltınca Helm/APP_ENVIRONMENT'e ({@code staging}) döner. Yalnız GLOBAL yönetici yazar: kapsamlı müdür (AD
 * ADMIN, takım kapsamlı) ve matris izni olmayan kullanıcı 403; geçersiz ad 400 + iletisi.
 */
@WebMvcTest({GeneralSettingsController.class, SystemInfoController.class})
@Import({AppSettingsService.class, BuildInfo.class})
@TestPropertySource(properties = "site.monitor.environment=staging")
class EnvironmentNameSettingWebTest {

    @Autowired MockMvc mvc;
    @Autowired BuildInfo buildInfo;
    @Autowired AppSettingsService settingsService;

    @MockitoBean AppSettingRepository repo;
    @MockitoBean ReleaseIndexService releaseIndex;
    @MockitoBean DeploymentHistoryService deployments;
    @MockitoBean AuditService auditService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean SchedulerService schedulerService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean HttpMetricsService httpMetricsService;

    private static final String BODY_PROD = "{\"values\":{\"site.monitor.environment\":\"prod\"}}";

    @BeforeEach
    void setUp() {
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findBySettingKey(anyString())).thenReturn(Optional.empty());
        when(releaseIndex.find(any())).thenReturn(Optional.empty());
        when(releaseIndex.meta()).thenReturn(Map.of("loaded", false));
        when(deployments.current(anyString()))
                .thenReturn(new DeploymentHistoryService.Current(null, null, null, null, 0, null, null));
        // Gerçek servis bağlamla birlikte önbelleklenir: önceki testin bellek override'ı sızmasın (istek bağlamı
        // yok = sistem çağrısı, GLOBAL_ONLY kapısı geçer). Ardından sayaçlar sıfırlanır.
        settingsService.save(Map.of("values", Map.of(BuildInfo.ENV_KEY, "")), "test-reset");
        clearInvocations(repo);
    }

    private static MockHttpSession session(String user, String role, List<Long> teams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", user);
        s.setAttribute("systemRole", role);
        if (teams != null) { s.setAttribute("viewTeamIds", teams); s.setAttribute("manageTeamIds", teams); }
        return s;
    }

    private static MockHttpSession globalAdmin() { return session("yonetici", "ADMIN", null); }
    private static MockHttpSession scopedAdmin() { return session("mudur", "ADMIN", List.of(2L)); }
    private static MockHttpSession plainUser()   { return session("kullanici", "USER", List.of(2L)); }

    @Test
    @DisplayName("global yönetici kaydeder → 200; /version bir sonraki istekte yeni ad; katalog kalemi effective/effective_source (snake)")
    void globalAdmin_save_isLive() throws Exception {
        mvc.perform(get("/api/system/version").session(plainUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environment").value("staging"));

        mvc.perform(put("/api/admin/general/settings").session(globalAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_PROD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[?(@.key=='site.monitor.environment')].effective").value(hasItem("prod")))
                .andExpect(jsonPath("$.data[?(@.key=='site.monitor.environment')].effective_source").value(hasItem("setting")))
                .andExpect(jsonPath("$.data[?(@.key=='site.monitor.environment')].default").value(hasItem("staging")));

        mvc.perform(get("/api/system/version").session(plainUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environment").value("prod"));
        verify(deployments, atLeastOnce()).current("prod");
        verify(repo).save(argThat(r -> "site.monitor.environment".equals(r.getSettingKey())
                && "prod".equals(r.getValue()) && "yonetici".equals(r.getUpdatedBy())));
    }

    @Test
    @DisplayName("denetim: kim + ham diff (boş/Helm → prod) + ETKİN ad (staging → prod, env → setting)")
    void save_isAudited_withEffectiveNames() throws Exception {
        mvc.perform(put("/api/admin/general/settings").session(globalAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_PROD))
                .andExpect(status().isOk());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> diff = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("GENERAL_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("SETTINGS"), eq("general"),
                detail.capture(), diff.capture());
        assertThat(detail.getValue()).contains("staging → prod").contains("env → setting");
        assertThat(diff.getValue()).contains("site.monitor.environment").contains("\"to\":\"prod\"");
    }

    @Test
    @DisplayName("boşaltınca Helm/APP_ENVIRONMENT'e döner (kaynak env)")
    void clear_fallsBackToEnvVariable() throws Exception {
        mvc.perform(put("/api/admin/general/settings").session(globalAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(BODY_PROD)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/general/settings").session(globalAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.environment\":\"\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.key=='site.monitor.environment')].effective").value(hasItem("staging")))
                .andExpect(jsonPath("$.data[?(@.key=='site.monitor.environment')].effective_source").value(hasItem("env")));
        mvc.perform(get("/api/system/version").session(plainUser()))
                .andExpect(jsonPath("$.data.environment").value("staging"));
    }

    @Test
    @DisplayName("geçersiz ad → 400 + iletisi (istek diline göre); hiçbir şey yazılmaz, ad değişmez")
    void invalidName_400() throws Exception {
        mvc.perform(put("/api/admin/general/settings").session(globalAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.environment\":\"Prod_EU\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(containsString("Ortam adı geçersiz")));
        mvc.perform(put("/api/admin/general/settings").session(globalAdmin()).header("X-Lang", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"site.monitor.environment\":\"prod eu\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Invalid environment name")));
        verify(repo, never()).save(any());
        assertThat(buildInfo.get().environment()).isEqualTo("staging");
    }

    @Test
    @DisplayName("kapsamlı müdür (AD ADMIN, takım kapsamlı) → 403; ad değişmez")
    void scopedAdmin_403() throws Exception {
        mvc.perform(put("/api/admin/general/settings").session(scopedAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_PROD))
                .andExpect(status().isForbidden());
        verify(repo, never()).save(any());
        assertThat(buildInfo.get().environment()).isEqualTo("staging");
    }

    @Test
    @DisplayName("kapsamlı müdür katalogda kalemi SALT OKUNUR görür (read_only) ama okuyabilir")
    void scopedAdmin_seesReadOnly() throws Exception {
        mvc.perform(get("/api/admin/general/settings").session(scopedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.key=='site.monitor.environment')].read_only").value(hasItem(true)))
                .andExpect(jsonPath("$.data[?(@.key=='site.monitor.environment')].effective").value(hasItem("staging")));
    }

    @Test
    @DisplayName("matris izni olmayan kullanıcı → 403 (Genel Ayarlar'a hiç giremez); sürüm penceresini okur")
    void plainUser_403_butCanReadVersion() throws Exception {
        doThrow(new SecurityException("no perm")).when(permissionService)
                .require(any(jakarta.servlet.http.HttpSession.class), eq("settings.general"), anyString());
        mvc.perform(put("/api/admin/general/settings").session(plainUser())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_PROD))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/system/version").session(plainUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environment").value("staging"));
        verify(repo, never()).save(any());
    }
}
