package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.ThemeCatalog;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ayarlar → Görünüm → Temalar (2026-10-05): okuma kapısı (Ayarlar'a giren herkes, kapsamlı müdür salt okunur), yazma
 * kapısı (yalnız global yönetici), alan adlı doğrulama (400 + field), kayıt yolu (AppSettingsService) ve denetim.
 */
@WebMvcTest(ThemeSettingsController.class)
class ThemeSettingsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppSettingsService settingsService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        // Matris izni: yalnız ADMIN rolündeki oturumlar (global ya da kapsamlı) geçer; USER 403.
        doThrow(new SecurityException("no perm")).when(permissionService)
                .require(org.mockito.ArgumentMatchers.<jakarta.servlet.http.HttpSession>argThat(
                        s -> s == null || !"ADMIN".equals(s.getAttribute("systemRole"))), anyString(), anyString());
        lenient().when(settingsService.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    @DisplayName("GET: oturumsuz 401, USER 403, global yönetici 200 (yazılabilir), kapsamlı müdür 200 SALT OKUNUR")
    void get_gates() throws Exception {
        mvc.perform(get("/api/admin/themes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/themes").session(session("USER", false))).andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/themes").session(session("ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(false))
                .andExpect(jsonPath("$.data.enabled.length()").value(8))
                .andExpect(jsonPath("$.data.default").value("system"))
                .andExpect(jsonPath("$.data.themes.length()").value(8))
                .andExpect(jsonPath("$.data.themes[2].id").value("blueprint"))
                .andExpect(jsonPath("$.data.themes[2].scheme").value("dark"))
                .andExpect(jsonPath("$.data.defaults.default").value("system"));

        mvc.perform(get("/api/admin/themes").session(session("ADMIN", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(true));
    }

    @Test
    @DisplayName("GET: depodaki politika (kanonik sıra) ve varsayılan yansır")
    void get_reflectsStoredPolicy() throws Exception {
        when(settingsService.getString(eq(ThemeCatalog.KEY_ENABLED), any())).thenReturn("crucible,light");
        when(settingsService.getString(eq(ThemeCatalog.KEY_DEFAULT), any())).thenReturn("crucible");
        mvc.perform(get("/api/admin/themes").session(session("ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled[0]").value("light"))
                .andExpect(jsonPath("$.data.enabled[1]").value("crucible"))
                .andExpect(jsonPath("$.data.default").value("crucible"));
    }

    @Test
    @DisplayName("PUT global yönetici: kanonik CSV + varsayılan AppSettingsService'ten kaydedilir, THEME_SETTINGS_SAVE denetimi")
    void put_globalAdmin_saves() throws Exception {
        mvc.perform(put("/api/admin/themes").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":[\"crucible\",\"dark\",\"light\"],\"default\":\"crucible\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(settingsService).save(argThat(body -> {
            Map<?, ?> values = (Map<?, ?>) body.get("values");
            return "light,dark,crucible".equals(values.get(ThemeCatalog.KEY_ENABLED))
                    && "crucible".equals(values.get(ThemeCatalog.KEY_DEFAULT));
        }), eq("root"));
        verify(auditService).recordAction(eq("THEME_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class),
                eq("SETTINGS"), eq("themes"), anyString(), anyString());
    }

    @Test
    @DisplayName("PUT: CSV metni de kabul edilir")
    void put_acceptsCsvString() throws Exception {
        mvc.perform(put("/api/admin/themes").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":\"dark, light\",\"default\":\"system\"}"))
                .andExpect(status().isOk());
        verify(settingsService).save(argThat(body ->
                "light,dark".equals(((Map<?, ?>) body.get("values")).get(ThemeCatalog.KEY_ENABLED))), anyString());
    }

    @Test
    @DisplayName("PUT kapsamlı müdür ve USER → 403; hiçbir şey kaydedilmez")
    void put_nonGlobal_forbidden() throws Exception {
        String body = "{\"enabled\":[\"light\",\"dark\"],\"default\":\"system\"}";
        mvc.perform(put("/api/admin/themes").session(session("ADMIN", true)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/themes").session(session("USER", false)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(settingsService, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("doğrulama 400 + field: bilinmeyen kimlik, boş liste, kapalı varsayılan, system Açık/Koyu'suz, liste olmayan değer")
    void put_validation() throws Exception {
        expectField("{\"enabled\":[\"light\",\"sepia\"],\"default\":\"light\"}", "enabled");
        expectField("{\"enabled\":[],\"default\":\"system\"}", "enabled");
        expectField("{\"enabled\":[\"light\",\"dark\"],\"default\":\"crucible\"}", "default");
        expectField("{\"enabled\":[\"light\",\"blueprint\"],\"default\":\"system\"}", "default");
        expectField("{\"enabled\":[\"dark\",\"alloy\"]}", "default");   // varsayılan verilmezse system
        expectField("{\"enabled\":[1,2],\"default\":\"system\"}", "enabled");
        expectField("{\"enabled\":{\"a\":1},\"default\":\"system\"}", "enabled");
        verify(settingsService, never()).save(any(), anyString());
    }

    private void expectField(String body, String field) throws Exception {
        mvc.perform(put("/api/admin/themes").session(session("ADMIN", false)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.field").value(field))
                .andExpect(jsonPath("$.error").isNotEmpty());
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
