package com.sitemonitor.controller;

import com.sitemonitor.repository.LoginAnomalyIncidentRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LoginAnomalyController.class)
class LoginAnomalyControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppSettingsService settingsService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean EmailNotificationService emailService;
    @MockitoBean LoginAnomalyIncidentRepository incidentRepo;
    @MockitoBean NotificationLogRepository notificationLogRepo;
    // İnterceptor/hata-işleyici slice bağımlılıkları
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.doThrow(new SecurityException("no perm")).when(permissionService)
                .require(any(jakarta.servlet.http.HttpSession.class), anyString(), anyString());
        when(settingsService.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settingsService.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(settingsService.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(settingsService.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(settingsService.getCsv(anyString(), anyString())).thenReturn(java.util.List.of());
        when(incidentRepo.findAllByOrderByOpenedAtDesc(any())).thenReturn(Page.empty());
        when(emailService.sendSystemAdminLoginAnomalyAlert(any(), any(), any())).thenReturn("SENT");
        when(emailService.senderAddress()).thenReturn("from@x");
    }

    @Test
    @DisplayName("GET settings oturumsuz → 401")
    void get_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/admin/login-anomaly/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET settings admin olmayan → 403")
    void get_nonAdmin_403() throws Exception {
        mvc.perform(get("/api/admin/login-anomaly/settings").session(userSession("sre1")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET settings bootstrap admin → 200 + varsayılan eşikler")
    void get_admin_200() throws Exception {
        mvc.perform(get("/api/admin/login-anomaly/settings").session(userSession("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.threshold_total").value(20))
                .andExpect(jsonPath("$.data.cooldown_minutes").value(60));
    }

    @Test
    @DisplayName("PUT settings geçerli → 200")
    void put_valid_200() throws Exception {
        mvc.perform(put("/api/admin/login-anomaly/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"window_minutes\":10,\"threshold_total\":20,\"cooldown_minutes\":60,\"relative_multiplier\":3.0,\"alert_recipients\":\"ops@x, ops@x, sec@x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PUT settings geçersiz cooldown (0) → 400")
    void put_invalidCooldown_400() throws Exception {
        mvc.perform(put("/api/admin/login-anomaly/settings").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cooldown_minutes\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT settings admin olmayan → 403")
    void put_nonAdmin_403() throws Exception {
        mvc.perform(put("/api/admin/login-anomaly/settings").session(userSession("sre1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST test-email admin → 200 sent=true")
    void testEmail_admin_200() throws Exception {
        mvc.perform(post("/api/admin/login-anomaly/test-email").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"ops@x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.sent").value(true));
    }

    @Test
    @DisplayName("POST test-email alıcısız → 400")
    void testEmail_noRecipient_400() throws Exception {
        mvc.perform(post("/api/admin/login-anomaly/test-email").session(userSession("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipient\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET incidents admin → 200")
    void incidents_admin_200() throws Exception {
        mvc.perform(get("/api/admin/login-anomaly/incidents").session(userSession("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value(0));
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
