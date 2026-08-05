package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SecretToolsService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SecretToolsController.class)
class SecretToolsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean SecretToolsService service;
    @MockitoBean AuditService auditService;
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
    @DisplayName("POST decrypt oturumsuz → 401")
    void decrypt_unauthenticated_401() throws Exception {
        mvc.perform(post("/api/admin/secret-tools/decrypt")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST decrypt admin olmayan → 403")
    void decrypt_nonAdmin_403() throws Exception {
        mvc.perform(post("/api/admin/secret-tools/decrypt").session(session("sre1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST decrypt bootstrap admin → 200 + data")
    void decrypt_admin_200() throws Exception {
        when(service.decryptWithKey("mykey")).thenReturn(List.of(
                Map.of("label", "SMTP Parolası", "column", "smtp_settings.password_enc",
                        "present", true, "ok", true, "value", "s3cret")));
        mvc.perform(post("/api/admin/secret-tools/decrypt").session(session("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"mykey\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].ok").value(true));
    }

    private MockHttpSession session(String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "admin".equals(username) ? "ADMIN" : "USER");
        s.setAttribute("bootstrapAdmin", "admin".equals(username));   // settings gate bayrağı (literal username yerine)
        return s;
    }
}
