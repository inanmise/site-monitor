package com.certmonitor.controller;

import com.certmonitor.model.LdapSettings;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.LdapDirectoryService;
import com.certmonitor.service.LdapSettingsService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LdapAdminController.class)
class LdapAdminControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean LdapSettingsService settingsService;
    @MockitoBean LdapDirectoryService directoryService;
    @MockitoBean AuditService auditService;

    // Beans pulled in by WebConfig / AuthInterceptor / HttpMetricsInterceptor.
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean HttpMetricsService httpMetricsService;

    @BeforeEach
    void setUp() {
        when(settingsService.getOrDefaults()).thenReturn(new LdapSettings());
        when(settingsService.isConfigured()).thenReturn(false);
        when(settingsService.toClientMap(any()))
                .thenReturn(Map.of("enabled", true, "bind_password_set", false));
    }

    @Test
    @DisplayName("GET /settings unauthenticated → 401")
    void getSettings_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/admin/ldap/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /settings as non-bootstrap ADMIN → 403")
    void getSettings_nonBootstrapAdmin_403() throws Exception {
        mvc.perform(get("/api/admin/ldap/settings").session(adminRoleButNotBootstrap()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /settings as bootstrap admin → 200, no bind password leaked")
    void getSettings_bootstrapAdmin_200() throws Exception {
        mvc.perform(get("/api/admin/ldap/settings").session(bootstrapAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.bind_password_set").value(false))
                .andExpect(jsonPath("$.data.bind_password").doesNotExist());
    }

    @Test
    @DisplayName("PUT /settings as bootstrap admin → 200 and persists")
    void saveSettings_bootstrapAdmin_200() throws Exception {
        when(settingsService.save(any(), eq("admin"))).thenReturn(new LdapSettings());
        mvc.perform(put("/api/admin/ldap/settings")
                        .session(bootstrapAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"host\":\"h\",\"port\":3269}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /test returns the directory service result")
    void test_returnsResult() throws Exception {
        when(directoryService.testConnection())
                .thenReturn(Map.of("success", true, "message", "ok"));
        mvc.perform(post("/api/admin/ldap/test").session(bootstrapAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"));
    }

    @Test
    @DisplayName("POST /query-user returns attributes on success")
    void queryUser_success() throws Exception {
        when(directoryService.queryUser("erdi", null))
                .thenReturn(Map.of("found", true, "dn", "CN=Erdi"));
        mvc.perform(post("/api/admin/ldap/query-user")
                        .session(bootstrapAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"erdi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.found").value(true));
    }

    @Test
    @DisplayName("POST /query-user surfaces failures inline (200, success=false)")
    void queryUser_failureInline() throws Exception {
        when(directoryService.queryUser("bad", null))
                .thenThrow(new IllegalStateException("LDAP unreachable"));
        mvc.perform(post("/api/admin/ldap/query-user")
                        .session(bootstrapAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("LDAP unreachable"));
    }

    @Test
    @DisplayName("POST /query-user as non-bootstrap ADMIN → 403")
    void queryUser_nonBootstrapAdmin_403() throws Exception {
        mvc.perform(post("/api/admin/ldap/query-user")
                        .session(adminRoleButNotBootstrap())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"erdi\"}"))
                .andExpect(status().isForbidden());
    }

    // ── session builders ─────────────────────────────────────────────────────

    private MockHttpSession bootstrapAdmin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private MockHttpSession adminRoleButNotBootstrap() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "erdi"); // ADMIN role but NOT the local bootstrap "admin"
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }
}
