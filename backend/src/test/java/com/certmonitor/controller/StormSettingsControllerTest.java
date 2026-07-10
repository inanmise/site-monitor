package com.certmonitor.controller;

import com.certmonitor.service.AppSettingsService;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.StormService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StormSettingsController.class)
class StormSettingsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppSettingsService settingsService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean StormService stormService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        // Bootstrap-olmayan admin için matris izni reddi → 403.
        org.mockito.Mockito.doThrow(new SecurityException("no perm")).when(permissionService)
                .require(any(jakarta.servlet.http.HttpSession.class), anyString(), anyString());
        // GET/PUT-happy path'lerin okuduğu efektif değerler.
        when(settingsService.getBoolean(eq(StormService.KEY_ENABLED), anyBoolean())).thenReturn(true);
        when(settingsService.getString(eq(StormService.KEY_UNIT), anyString())).thenReturn("COUNT");
        when(settingsService.getInt(eq(StormService.KEY_VALUE), anyInt())).thenReturn(5);
        when(settingsService.getInt(eq(StormService.KEY_WINDOW), anyInt())).thenReturn(5);
        when(settingsService.getBoolean(eq(StormService.KEY_PER_GROUP), anyBoolean())).thenReturn(false);
        when(stormService.totalActiveMonitors()).thenReturn(42L);
        when(stormService.computeThreshold()).thenReturn(5);
    }

    @Test
    @DisplayName("GET oturumsuz → 401")
    void get_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/monitoring/storm/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET admin olmayan → 403")
    void get_nonAdmin_403() throws Exception {
        mvc.perform(get("/api/monitoring/storm/settings").session(userSession("sre1")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET bootstrap admin → 200 + storm config")
    void get_admin_200() throws Exception {
        mvc.perform(get("/api/monitoring/storm/settings").session(userSession("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.threshold_unit").value("COUNT"))
                .andExpect(jsonPath("$.data.effective_threshold").value(5))
                .andExpect(jsonPath("$.data.total_active_monitors").value(42));
    }

    @Test
    @DisplayName("PUT bootstrap admin (geçerli) → 200")
    void put_admin_valid_200() throws Exception {
        mvc.perform(put("/api/monitoring/storm/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"threshold_unit\":\"COUNT\",\"threshold_value\":5,\"window_minutes\":5,\"per_group\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PUT geçersiz zaman penceresi (20) → 400")
    void put_invalidWindow_400() throws Exception {
        mvc.perform(put("/api/monitoring/storm/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"threshold_unit\":\"COUNT\",\"threshold_value\":5,\"window_minutes\":20,\"per_group\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT geçersiz COUNT eşiği (1) → 400")
    void put_invalidCount_400() throws Exception {
        mvc.perform(put("/api/monitoring/storm/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"threshold_unit\":\"COUNT\",\"threshold_value\":1,\"window_minutes\":5,\"per_group\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT admin olmayan → 403")
    void put_nonAdmin_403() throws Exception {
        mvc.perform(put("/api/monitoring/storm/settings").session(userSession("sre1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"threshold_unit\":\"COUNT\",\"threshold_value\":5,\"window_minutes\":5,\"per_group\":false}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession userSession(String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "admin".equals(username) ? "ADMIN" : "USER");
        s.setAttribute("bootstrapAdmin", "admin".equals(username));
        return s;
    }
}
