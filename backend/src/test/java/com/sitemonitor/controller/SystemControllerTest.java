package com.sitemonitor.controller;

import com.sitemonitor.service.ExtendedHealthService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.MetricsService;
import com.sitemonitor.service.SchedulerService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
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
    com.sitemonitor.service.HttpMetricsQueryService httpMetricsQueryService;

    @MockitoBean
    ExtendedHealthService extendedHealthService;

    @MockitoBean
    com.sitemonitor.service.RememberMeService rememberMeService;

    @MockitoBean
    com.sitemonitor.service.UserService userService;

    @MockitoBean
    com.sitemonitor.service.UserActivityService userActivityService;

    @MockitoBean
    com.sitemonitor.service.DbAnalyticsService dbAnalyticsService;

    @MockitoBean
    AuthController authController;

    @MockitoBean
    com.sitemonitor.service.PermissionService permissionService;

    @MockitoBean
    com.sitemonitor.service.WeeklyAvailabilityReportService weeklyAvailabilityReportService;

    @MockitoBean
    com.sitemonitor.service.report.CertificateInventoryReportService certificateInventoryReportService;

    @MockitoBean
    com.sitemonitor.service.AppSettingsService appSettingsService;

    @MockitoBean
    com.sitemonitor.service.AuditService auditService;

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

    // ── Kullanıcı etkinliği zenginleştirmesi (2026-09-13): zaman çizelgesi, anomali onayı, kendi oturumunu kapatma ──

    @Test
    @DisplayName("POST /terminate-session kendi oturumu için 400 (self-guard) ve gerekçe denetim satırına yazılır")
    void terminateSession_selfGuardAndReason() throws Exception {
        mvc.perform(post("/api/admin/system/terminate-session").session(adminSession())
                        .contentType("application/json").content("{\"username\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/system/terminate-session").session(adminSession())
                        .contentType("application/json").content("{\"username\":\"bob\",\"reason\":\"stale VPN\"}"))
                .andExpect(status().isOk());
        verify(auditService).recordAction(eq("SESSION_TERMINATE"), any(), eq("USER"), eq("bob"), eq("bob — stale VPN"), any());
    }

    @Test
    @DisplayName("GET /user-activity/user/{username} AUDIT görebilir; limit 100'e kırpılır")
    void userTimeline_asAudit() throws Exception {
        when(userActivityService.userTimeline(eq("bob"), anyInt())).thenReturn(Map.of("username", "bob", "logins", 3L));
        mvc.perform(get("/api/admin/system/user-activity/user/bob?limit=500").session(auditSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logins").value(3));
        verify(userActivityService).userTimeline("bob", 100);
        mvc.perform(get("/api/admin/system/user-activity/user/bob").session(userSession())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /user-activity/anomalies/{id}/ack onaylar + LOGIN_ANOMALY_ACK denetimi; acknowledge=false kaldırır")
    void anomalyAck() throws Exception {
        when(userActivityService.acknowledgeAnomaly(eq(42L), eq("admin"), eq("seen"), eq(true))).thenReturn(Map.of("by", "admin"));
        mvc.perform(post("/api/admin/system/user-activity/anomalies/42/ack").session(adminSession())
                        .contentType("application/json").content("{\"note\":\"seen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(true))
                .andExpect(jsonPath("$.ack.by").value("admin"));
        verify(auditService).recordAction(eq("LOGIN_ANOMALY_ACK"), any(), eq("AUDIT_LOG"), eq("42"), eq("acknowledged: seen"), any());
        mvc.perform(post("/api/admin/system/user-activity/anomalies/42/ack").session(adminSession())
                        .contentType("application/json").content("{\"acknowledge\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acknowledged").value(false));
        verify(userActivityService).acknowledgeAnomaly(42L, "admin", null, false);
    }

    // ── Haftalık erişilebilirlik e-postası (Ayarlar sayfası uçları) ──

    @Test
    @DisplayName("GET /weekly-availability/status as ADMIN returns 200")
    void weeklyAvailStatus_asAdmin_returns200() throws Exception {
        when(weeklyAvailabilityReportService.status()).thenReturn(
                new com.sitemonitor.service.WeeklyAvailabilityReportService.StatusResult(
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
                new com.sitemonitor.service.WeeklyAvailabilityReportService.PreviewResult(
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
                new com.sitemonitor.service.WeeklyAvailabilityReportService.ArchivedMailDetail(
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

    // ── system_health.read izin kapisi (A1) ────────────────────────────────────
    //
    // Bu dort uc `HttpSession` parametresini ALIYOR ama hic kullanmiyordu: AuthInterceptor
    // yalniz kimlik dogruluyor ve `/api/admin/**` onekinin rol kapisi yok, yani izin
    // matrisinden `system_health.read` geri alinsa bile ucler veri dondurmeye devam ediyordu.
    // En agiri `smtp-logs`: 365 gune kadar HER takimin alici e-postasi, konusu ve alarm
    // govdesi. Asagidaki dortlu, kapinin varligini uctan uca pinler — kapi silinirse kirmizi.

    @Test
    @DisplayName("GET /smtp-logs: system_health.read reddedilirse 403 (izin kapisi)")
    void smtpLogs_permissionDenied_returns403() throws Exception {
        denySystemHealthRead();
        mvc.perform(get("/api/admin/system/smtp-logs?days=365").session(userSession()))
                .andExpect(status().isForbidden());
        // Kapi ACTUALLY calisti mi: reddedilen istek servise HIC ulasmamali.
        verify(extendedHealthService, never()).getSmtpFailures(anyInt());
    }

    @Test
    @DisplayName("GET /db-stats: system_health.read reddedilirse 403 (izin kapisi)")
    void dbStats_permissionDenied_returns403() throws Exception {
        denySystemHealthRead();
        mvc.perform(get("/api/admin/system/db-stats").session(userSession()))
                .andExpect(status().isForbidden());
        verify(extendedHealthService, never()).getTableStats();
    }

    @Test
    @DisplayName("GET /metrics: system_health.read reddedilirse 403 (izin kapisi)")
    void metrics_permissionDenied_returns403() throws Exception {
        denySystemHealthRead();
        mvc.perform(get("/api/admin/system/metrics").session(userSession()))
                .andExpect(status().isForbidden());
        verify(metricsService, never()).getHistory();
    }

    @Test
    @DisplayName("GET /http-metrics: system_health.read reddedilirse 403 (izin kapisi)")
    void httpMetrics_permissionDenied_returns403() throws Exception {
        denySystemHealthRead();
        mvc.perform(get("/api/admin/system/http-metrics").session(userSession()))
                .andExpect(status().isForbidden());
        verify(httpMetricsService, never()).getSummary();
    }

    // Regresyon: Sistem Sagligi sekmesi TUM rollere acik (Nav `show: true`) ve
    // `system_health.read` USER varsayilanlarinda VAR — kapi eklendi diye siradan
    // kullanicinin ekrani kirilmamali. Dordu de izin varken 200 donmeli.

    @Test
    @DisplayName("Dort sistem ucu: izin varken USER icin 200 (ekran kirilmadi)")
    void systemReadEndpoints_asUserWithPermission_return200() throws Exception {
        when(extendedHealthService.getSmtpFailures(anyInt())).thenReturn(java.util.List.of());
        when(extendedHealthService.getTableStats()).thenReturn(java.util.List.of());
        when(metricsService.getHistory()).thenReturn(java.util.List.of());
        when(httpMetricsService.getSummary()).thenReturn(Map.of("count", 0));
        when(httpMetricsService.getHistory()).thenReturn(java.util.List.of());

        for (String path : java.util.List.of("/smtp-logs", "/db-stats", "/metrics", "/http-metrics")) {
            mvc.perform(get("/api/admin/system" + path).session(userSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
        // Kapi dogru kaynak/eylem ciftiyle soruldu (yanlis anahtar = sessizce her zaman gecen kapi).
        verify(permissionService, org.mockito.Mockito.times(4))
                .require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.read"), eq("view"));
    }

    /** `system_health.read` reddi: PermissionService.require SecurityException atar → 403. */
    private void denySystemHealthRead() {
        org.mockito.Mockito.doThrow(new SecurityException("Bu islem icin yetkiniz yok: system_health.read/view"))
                .when(permissionService)
                .require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.read"), eq("view"));
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
