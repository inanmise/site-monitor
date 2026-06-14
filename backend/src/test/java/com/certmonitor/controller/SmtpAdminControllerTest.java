package com.certmonitor.controller;

import com.certmonitor.model.SmtpSettings;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.SmtpMailService;
import com.certmonitor.service.SmtpSettingsService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SmtpAdminController.class)
class SmtpAdminControllerTest {

    @Autowired MockMvc mvc;

    @MockBean SmtpSettingsService settingsService;
    @MockBean SmtpMailService mailService;
    @MockBean AuditService auditService;

    // Beans pulled in by WebConfig / AuthInterceptor / HttpMetricsInterceptor.
    @MockBean RememberMeService rememberMeService;
    @MockBean UserService userService;
    @MockBean AuthController authController;
    @MockBean HttpMetricsService httpMetricsService;

    @BeforeEach
    void setUp() {
        when(settingsService.getOrDefaults()).thenReturn(new SmtpSettings());
        when(settingsService.isConfigured()).thenReturn(false);
        when(settingsService.toClientMap(any()))
                .thenReturn(Map.of("enabled", true, "host", "smtp.gmail.com", "password_set", true));
    }

    @Test
    @DisplayName("GET /settings unauthenticated → 401")
    void getSettings_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/admin/smtp/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /settings as non-bootstrap ADMIN → 403")
    void getSettings_nonBootstrapAdmin_403() throws Exception {
        mvc.perform(get("/api/admin/smtp/settings").session(adminRoleButNotBootstrap()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /settings as bootstrap admin → 200, no password leaked")
    void getSettings_bootstrapAdmin_200() throws Exception {
        mvc.perform(get("/api/admin/smtp/settings").session(bootstrapAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.password_set").value(true))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    @DisplayName("PUT /settings as bootstrap admin → 200")
    void saveSettings_bootstrapAdmin_200() throws Exception {
        when(settingsService.save(any(), eq("admin"))).thenReturn(new SmtpSettings());
        mvc.perform(put("/api/admin/smtp/settings")
                        .session(bootstrapAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"host\":\"smtp.gmail.com\",\"port\":587}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /test returns the connection result")
    void testConnection_returnsResult() throws Exception {
        when(mailService.testConnection()).thenReturn(Map.of("success", true, "message", "ok"));
        mvc.perform(post("/api/admin/smtp/test").session(bootstrapAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"));
    }

    @Test
    @DisplayName("POST /test-email surfaces failures inline (200, success=false)")
    void sendTest_failureInline() throws Exception {
        when(mailService.sendTest("a@b.com"))
                .thenReturn(Map.of("success", false, "error", "Connection refused"));
        mvc.perform(post("/api/admin/smtp/test-email")
                        .session(bootstrapAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"a@b.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Connection refused"));
    }

    @Test
    @DisplayName("POST /test-email as non-bootstrap ADMIN → 403")
    void sendTest_nonBootstrapAdmin_403() throws Exception {
        mvc.perform(post("/api/admin/smtp/test-email")
                        .session(adminRoleButNotBootstrap())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"a@b.com\"}"))
                .andExpect(status().isForbidden());
    }

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
        s.setAttribute("username", "erdi");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }
}
