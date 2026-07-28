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
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    com.certmonitor.service.HttpMetricsQueryService httpMetricsQueryService;

    @MockitoBean
    ExtendedHealthService extendedHealthService;

    @MockitoBean
    com.certmonitor.service.RememberMeService rememberMeService;

    @MockitoBean
    com.certmonitor.service.UserService userService;

    @MockitoBean
    com.certmonitor.service.UserActivityService userActivityService;

    @MockitoBean
    com.certmonitor.service.DbAnalyticsService dbAnalyticsService;

    @MockitoBean
    AuthController authController;

    @MockitoBean
    com.certmonitor.service.PermissionService permissionService;

    @MockitoBean
    com.certmonitor.service.WeeklyAvailabilityReportService weeklyAvailabilityReportService;

    @MockitoBean
    com.certmonitor.service.AuditService auditService;

    @BeforeEach
    void setup() {
        when(schedulerService.getSystemHealth()).thenReturn(Map.of("scheduler", "OK"));
        when(extendedHealthService.getSmtpStats()).thenReturn(Map.of("success_rate", 100));
        when(extendedHealthService.measureDbResponseMs()).thenReturn(5L);
        when(extendedHealthService.getHeartbeatStatus()).thenReturn(Map.of("ok", true));
        when(extendedHealthService.getNetworkStatus()).thenReturn(Map.of("alarm", false));
        when(userActivityService.getOverview()).thenReturn(Map.of("summary", Map.of("active_count", 1)));
        when(dbAnalyticsService.getOverview(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Map.of("summary", Map.of("queries", 0)));
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
    @DisplayName("GET /api/admin/system/db-analytics as ADMIN returns 200")
    void dbAnalytics_asAdmin_returns200() throws Exception {
        mvc.perform(get("/api/admin/system/db-analytics?days=7").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/admin/system/db-analytics as USER returns 403 (admin/audit-only)")
    void dbAnalytics_asUser_returns403() throws Exception {
        mvc.perform(get("/api/admin/system/db-analytics?days=7").session(userSession()))
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
        // DENETİM: oturum sonlandırma SESSION_TERMINATE olarak kaydedilir (3. arg String → belirsizlik yok)
        verify(auditService).recordAction(eq("SESSION_TERMINATE"), any(), eq("USER"), eq("bob"), any(), any());
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

    // ── Haftalık erişilebilirlik e-postası (Ayarlar sayfası uçları) ──

    @Test
    @DisplayName("GET /weekly-availability/status as ADMIN returns 200")
    void weeklyAvailStatus_asAdmin_returns200() throws Exception {
        when(weeklyAvailabilityReportService.status()).thenReturn(
                new com.certmonitor.service.WeeklyAvailabilityReportService.StatusResult(
                        true, "0 0 10 ? * MON", "9–15 Haziran 2026", java.util.List.of(), java.util.List.of()));
        mvc.perform(get("/api/admin/system/weekly-availability/status").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /weekly-availability/status as USER returns 403")
    void weeklyAvailStatus_asUser_returns403() throws Exception {
        mvc.perform(get("/api/admin/system/weekly-availability/status").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /weekly-availability/preview as ADMIN returns 200")
    void weeklyAvailPreview_asAdmin_returns200() throws Exception {
        when(weeklyAvailabilityReportService.preview(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.certmonitor.service.WeeklyAvailabilityReportService.PreviewResult(
                        "<html></html>", "Dijital", "9–15 Haziran 2026",
                        java.util.List.of("a@b.com"), java.util.List.of(), 1, false));
        mvc.perform(get("/api/admin/system/weekly-availability/preview?teamId=5").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /weekly-availability/preview as USER returns 403")
    void weeklyAvailPreview_asUser_returns403() throws Exception {
        mvc.perform(get("/api/admin/system/weekly-availability/preview?teamId=5").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /weekly-availability/send-test as ADMIN returns 200")
    void weeklyAvailSendTest_asAdmin_returns200() throws Exception {
        when(weeklyAvailabilityReportService.sendTest(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn("SENT");
        mvc.perform(post("/api/admin/system/weekly-availability/send-test").session(adminSession())
                        .contentType("application/json").content("{\"teamId\":5,\"email\":\"a@b.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /weekly-availability/send-test as USER returns 403")
    void weeklyAvailSendTest_asUser_returns403() throws Exception {
        mvc.perform(post("/api/admin/system/weekly-availability/send-test").session(userSession())
                        .contentType("application/json").content("{\"teamId\":5,\"email\":\"a@b.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /weekly-availability/enabled as ADMIN returns 200")
    void weeklyAvailEnabled_asAdmin_returns200() throws Exception {
        mvc.perform(put("/api/admin/system/weekly-availability/enabled").session(adminSession())
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PUT /weekly-availability/enabled as USER returns 403")
    void weeklyAvailEnabled_asUser_returns403() throws Exception {
        mvc.perform(put("/api/admin/system/weekly-availability/enabled").session(userSession())
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /weekly-availability/history as ADMIN returns 200")
    void weeklyAvailHistory_asAdmin_returns200() throws Exception {
        when(weeklyAvailabilityReportService.history(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(java.util.List.of());
        mvc.perform(get("/api/admin/system/weekly-availability/history?limit=50&includeTest=false").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /weekly-availability/history as USER returns 403")
    void weeklyAvailHistory_asUser_returns403() throws Exception {
        mvc.perform(get("/api/admin/system/weekly-availability/history").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /weekly-availability/history/{id} as ADMIN returns 200")
    void weeklyAvailHistoryItem_asAdmin_returns200() throws Exception {
        when(weeklyAvailabilityReportService.historyItem(org.mockito.ArgumentMatchers.anyLong())).thenReturn(
                new com.certmonitor.service.WeeklyAvailabilityReportService.ArchivedMailDetail(
                        7L, "2026-06-15T08:00:00", "Dijital", "a@b.com", null,
                        "konu", "SENT", "WEEKLY_AVAILABILITY", "<html></html>"));
        mvc.perform(get("/api/admin/system/weekly-availability/history/7").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /weekly-availability/history/{id} as USER returns 403")
    void weeklyAvailHistoryItem_asUser_returns403() throws Exception {
        mvc.perform(get("/api/admin/system/weekly-availability/history/7").session(userSession()))
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
