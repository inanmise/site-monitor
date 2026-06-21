package com.certmonitor.controller;

import com.certmonitor.service.ExtendedHealthService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.MetricsService;
import com.certmonitor.service.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
class SystemControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SchedulerService schedulerService;

    @MockitoBean
    MetricsService metricsService;

    @MockitoBean
    HttpMetricsService httpMetricsService;

    @MockitoBean
    ExtendedHealthService extendedHealthService;

    @MockitoBean
    com.certmonitor.service.RememberMeService rememberMeService;

    @MockitoBean
    com.certmonitor.service.UserService userService;

    @MockitoBean
    com.certmonitor.service.UserActivityService userActivityService;

    @MockitoBean
    AuthController authController;

    @BeforeEach
    void setup() {
        when(schedulerService.getSystemHealth()).thenReturn(Map.of("scheduler", "OK"));
        when(extendedHealthService.getSmtpStats()).thenReturn(Map.of("success_rate", 100));
        when(extendedHealthService.measureDbResponseMs()).thenReturn(5L);
        when(extendedHealthService.getHeartbeatStatus()).thenReturn(Map.of("ok", true));
        when(extendedHealthService.getNetworkStatus()).thenReturn(Map.of("alarm", false));
        when(userActivityService.getOverview()).thenReturn(Map.of("summary", Map.of("active_count", 1)));
    }

    @Test
    @DisplayName("GET /api/admin/system without session returns 401")
    void getHealth_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/admin/system"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/admin/system as USER returns 200 (read-only access opened)")
    void getHealth_asUser_returns200() throws Exception {
        mvc.perform(get("/api/admin/system").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/admin/system as ADMIN returns 200 (regression)")
    void getHealth_asAdmin_returns200() throws Exception {
        mvc.perform(get("/api/admin/system").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/admin/system/heartbeat as USER returns 403 (mutating, admin-only)")
    void triggerHeartbeat_asUser_returns403() throws Exception {
        mvc.perform(post("/api/admin/system/heartbeat").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/admin/system/scheduler-lock as USER returns 403 (mutating, admin-only)")
    void forceReleaseLock_asUser_returns403() throws Exception {
        mvc.perform(delete("/api/admin/system/scheduler-lock").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/system/heartbeat as ADMIN returns 200 (regression)")
    void triggerHeartbeat_asAdmin_returns200() throws Exception {
        mvc.perform(post("/api/admin/system/heartbeat").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/admin/system/user-activity as ADMIN returns 200")
    void userActivity_asAdmin_returns200() throws Exception {
        mvc.perform(get("/api/admin/system/user-activity").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/admin/system/user-activity as USER returns 403 (admin/audit-only)")
    void userActivity_asUser_returns403() throws Exception {
        mvc.perform(get("/api/admin/system/user-activity").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/system/user-activity as AUDIT returns 200 (read-only viewer)")
    void userActivity_asAudit_returns200() throws Exception {
        mvc.perform(get("/api/admin/system/user-activity").session(auditSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/admin/system/terminate-session as AUDIT returns 403 (mutating, admin-only)")
    void terminateSession_asAudit_returns403() throws Exception {
        mvc.perform(post("/api/admin/system/terminate-session")
                        .session(auditSession())
                        .contentType("application/json")
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/system/terminate-session as ADMIN returns 200")
    void terminateSession_asAdmin_returns200() throws Exception {
        mvc.perform(post("/api/admin/system/terminate-session")
                        .session(adminSession())
                        .contentType("application/json")
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/admin/system/terminate-session as USER returns 403 (admin-only)")
    void terminateSession_asUser_returns403() throws Exception {
        mvc.perform(post("/api/admin/system/terminate-session")
                        .session(userSession())
                        .contentType("application/json")
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession userSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "regularuser");
        s.setAttribute("systemRole", "USER");
        return s;
    }

    private MockHttpSession adminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private MockHttpSession auditSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "auditor");
        s.setAttribute("systemRole", "AUDIT");
        return s;
    }
}
