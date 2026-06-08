package com.certmonitor.controller;

import com.certmonitor.service.ExtendedHealthService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.MetricsService;
import com.certmonitor.service.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

    @MockBean
    SchedulerService schedulerService;

    @MockBean
    MetricsService metricsService;

    @MockBean
    HttpMetricsService httpMetricsService;

    @MockBean
    ExtendedHealthService extendedHealthService;

    @MockBean
    com.certmonitor.service.RememberMeService rememberMeService;

    @MockBean
    com.certmonitor.service.UserService userService;

    @MockBean
    AuthController authController;

    @BeforeEach
    void setup() {
        when(schedulerService.getSystemHealth()).thenReturn(Map.of("scheduler", "OK"));
        when(extendedHealthService.getSmtpStats()).thenReturn(Map.of("success_rate", 100));
        when(extendedHealthService.measureDbResponseMs()).thenReturn(5L);
        when(extendedHealthService.getHeartbeatStatus()).thenReturn(Map.of("ok", true));
        when(extendedHealthService.getNetworkStatus()).thenReturn(Map.of("alarm", false));
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
}
