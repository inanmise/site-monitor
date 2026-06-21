package com.certmonitor.controller;

import com.certmonitor.service.AppSettingsService;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GeneralSettingsController.class)
class GeneralSettingsControllerTest {

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
        // Bootstrap admin ("admin") require'ı atlar (erken dönüş); non-bootstrap için matris izni
        // reddini simüle et → 403. Bootstrap happy-path'ler require çağırmadığından etkilenmez.
        org.mockito.Mockito.doThrow(new SecurityException("no perm")).when(permissionService)
                .require(org.mockito.ArgumentMatchers.any(jakarta.servlet.http.HttpSession.class),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET oturumsuz → 401")
    void get_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/admin/general/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET admin olmayan oturum → 403")
    void get_nonAdmin_403() throws Exception {
        mvc.perform(get("/api/admin/general/settings").session(userSession("sre1")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET bootstrap admin → 200 + data")
    void get_admin_200() throws Exception {
        when(settingsService.getCatalogForClient()).thenReturn(List.of(
                Map.of("key", "cert.monitor.app.base-url", "group", "general", "type", "STRING", "value", "http://x")));
        mvc.perform(get("/api/admin/general/settings").session(userSession("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].key").value("cert.monitor.app.base-url"));
    }

    @Test
    @DisplayName("PUT bootstrap admin → 200")
    void put_admin_200() throws Exception {
        when(settingsService.getCatalogForClient()).thenReturn(List.of());
        mvc.perform(put("/api/admin/general/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"cert.monitor.app.base-url\":\"https://prod.example.com\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PUT admin olmayan → 403")
    void put_nonAdmin_403() throws Exception {
        mvc.perform(put("/api/admin/general/settings").session(userSession("sre1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{}}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession userSession(String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "admin".equals(username) ? "ADMIN" : "USER");
        return s;
    }
}
