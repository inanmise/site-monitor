package com.certmonitor.controller;

import com.certmonitor.service.DatabaseInfoService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DatabaseInfoController.class)
class DatabaseInfoControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean DatabaseInfoService service;
    @MockitoBean PermissionService permissionService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        // Bootstrap admin ("admin") require'ı atlar; non-bootstrap için matris izni reddini simüle et → 403.
        org.mockito.Mockito.doThrow(new SecurityException("no perm")).when(permissionService)
                .require(org.mockito.ArgumentMatchers.any(jakarta.servlet.http.HttpSession.class),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET info oturumsuz → 401")
    void info_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/admin/database/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET info admin olmayan → 403")
    void info_nonAdmin_403() throws Exception {
        mvc.perform(get("/api/admin/database/info").session(session("sre1")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET info bootstrap admin → 200 + data (db adı/kullanıcı/havuz)")
    void info_admin_200() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", "certmonitor");
        data.put("user", "certuser");
        data.put("version", "PostgreSQL 16.2");
        data.put("pool", Map.of("name", "CertMonitorPool", "active", 1, "max_size", 10));
        when(service.getInfo()).thenReturn(data);

        mvc.perform(get("/api/admin/database/info").session(session("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.database").value("certmonitor"))
                .andExpect(jsonPath("$.data.user").value("certuser"))
                .andExpect(jsonPath("$.data.pool.max_size").value(10));
    }

    private MockHttpSession session(String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "admin".equals(username) ? "ADMIN" : "USER");
        return s;
    }
}
