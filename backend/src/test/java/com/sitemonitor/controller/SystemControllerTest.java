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

import java.util.List;
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
    @DisplayName("GET /api/admin/system/user-activity as USER returns 200 (2026-09-19: Sistem Sağlığı her kademeye açık, salt-okuma)")
    void userActivity_asUser_returns200() throws Exception {
        mvc.perform(get("/api/admin/system/user-activity").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("user-activity: sicil (employee_id) yalnız global ADMIN'e döner — USER payload'ında hiç yok (arayüz maskesi yetmez)")
    void userActivity_masksEmployeeIdForNonGlobalAdmin() throws Exception {
        when(userActivityService.getOverview()).thenReturn(Map.of("summary", Map.of("active_count", 1),
                "active_users", List.of(Map.of("username", "u1", "employee_id", "12345", "email", "u1@example.com")),
                // 2026-09-20: kullanıcı dizini (login_status) da sicil taşır → aynı maske
                "login_status", List.of(Map.of("username", "u1", "employee_id", "12345"), Map.of("username", "u2", "employee_id", "67890"))));
        mvc.perform(get("/api/admin/system/user-activity").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_users[0].username").value("u1"))
                .andExpect(jsonPath("$.data.active_users[0].employee_id").doesNotExist())
                .andExpect(jsonPath("$.data.login_status[0].username").value("u1"))
                .andExpect(jsonPath("$.data.login_status[0].employee_id").doesNotExist())
                .andExpect(jsonPath("$.data.login_status[1].employee_id").doesNotExist());
        mvc.perform(get("/api/admin/system/user-activity").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_users[0].employee_id").value("12345"))
                .andExpect(jsonPath("$.data.login_status[1].employee_id").value("67890"));
    }

    @Test
    @DisplayName("user-activity: giriş damgası IP'leri (son / önceki / başarısız) yalnız global ADMIN'e ve kişinin KENDİ satırında döner")
    void userActivity_masksLoginStampIpsForNonGlobalAdmin() throws Exception {
        Map<String, Object> other = new java.util.HashMap<>(Map.of("username", "u1", "last_login_ip", "192.0.2.10",
                "prev_login_ip", "192.0.2.11", "last_failed_ip", "198.51.100.7", "last_login_method", "LDAP"));
        Map<String, Object> self = new java.util.HashMap<>(Map.of("username", "regularuser", "last_login_ip", "203.0.113.5",
                "prev_login_ip", "203.0.113.6", "last_failed_ip", "203.0.113.7"));
        when(userActivityService.getOverview()).thenReturn(Map.of("summary", Map.of("active_count", 1),
                "active_users", List.of(other),
                "login_status", List.of(other, self)));
        mvc.perform(get("/api/admin/system/user-activity").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.login_status[0].last_login_method").value("LDAP"))
                .andExpect(jsonPath("$.data.login_status[0].last_login_ip").doesNotExist())
                .andExpect(jsonPath("$.data.login_status[0].prev_login_ip").doesNotExist())
                .andExpect(jsonPath("$.data.login_status[0].last_failed_ip").doesNotExist())
                .andExpect(jsonPath("$.data.active_users[0].last_login_ip").doesNotExist())
                // kişinin kendi satırı tam kalır
                .andExpect(jsonPath("$.data.login_status[1].last_login_ip").value("203.0.113.5"))
                .andExpect(jsonPath("$.data.login_status[1].last_failed_ip").value("203.0.113.7"));
        mvc.perform(get("/api/admin/system/user-activity").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.login_status[0].last_login_ip").value("192.0.2.10"))
                .andExpect(jsonPath("$.data.login_status[0].prev_login_ip").value("192.0.2.11"))
                .andExpect(jsonPath("$.data.login_status[0].last_failed_ip").value("198.51.100.7"));
    }

    /** Önceki maskenin kaçırdığı yapıların tamamı (2026-09-28c, B1): top_sources, details.*, Map olan anomalies,
     *  heatmaps[].cells — tel biçiminde (snake_case), RFC 5737 belgeleme IP'leriyle. */
    private static Map<String, Object> nestedTraceOverview() {
        Map<String, Object> ev = Map.of("time", "2026-09-28T08:00:00", "actor", "u1", "ip", "198.51.100.21",
                "city", "Kent A", "country", "TR", "org", "Example ISP", "user_agent", "Mozilla/5.0 GateBrowser/1.0",
                "outcome", "SUCCESS");
        return Map.of(
                "summary", Map.of("active_count", 1, "logins_24h", 1),
                "top_sources", List.of(Map.of("ip", "198.51.100.21", "reverse_dns", "host-a.example.com",
                        "org", "Example ISP", "users", List.of("u1", "u2"), "total", 3)),
                "details", Map.of("logins", List.of(ev), "failed", List.of(ev), "anomalies", List.of(ev)),
                "anomalies", Map.of("counts", Map.of("UNUSUAL_IP", 1), "total", 1,
                        "recent", List.of(Map.of("id", 7, "actor", "u1", "ip", "198.51.100.21", "city", "Kent A"))),
                "heatmaps", List.of(Map.of("matrix", List.of(List.of(1)),
                        "cells", Map.of("0-8", List.of(Map.of("actor", "u1", "ip", "198.51.100.21", "city", "Kent A"))))));
    }

    @Test
    @DisplayName("user-activity: USER / kapsamlı müdür — top_sources, details.*, anomalies.recent, heatmaps.cells'te kimlik izi YOK; sayılar kalır")
    void userActivity_masksEveryNestedTraceForNonGlobal() throws Exception {
        when(userActivityService.getOverview()).thenReturn(nestedTraceOverview());
        for (MockHttpSession s : List.of(userSession(), scopedAdminSession(), teamAdminSession())) {
            String body = mvc.perform(get("/api/admin/system/user-activity").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.identity_masked").value(true))
                    .andExpect(jsonPath("$.data.top_sources").doesNotExist())
                    .andExpect(jsonPath("$.data.details.logins[0].ip").doesNotExist())
                    .andExpect(jsonPath("$.data.details.logins[0].user_agent").doesNotExist())
                    .andExpect(jsonPath("$.data.details.failed[0].org").doesNotExist())
                    .andExpect(jsonPath("$.data.details.anomalies[0].city").doesNotExist())
                    .andExpect(jsonPath("$.data.details.logins[0].actor").value("u1"))
                    .andExpect(jsonPath("$.data.anomalies.recent[0].ip").doesNotExist())
                    .andExpect(jsonPath("$.data.anomalies.recent[0].id").value(7))
                    .andExpect(jsonPath("$.data.anomalies.counts.UNUSUAL_IP").value(1))
                    .andExpect(jsonPath("$.data.heatmaps[0].cells['0-8'][0].ip").doesNotExist())
                    .andExpect(jsonPath("$.data.heatmaps[0].matrix[0][0]").value(1))
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body)
                    .doesNotContain("198.51.100.21").doesNotContain("host-a.example.com").doesNotContain("GateBrowser");
        }
        for (MockHttpSession s : List.of(adminSession(), auditSession())) {
            mvc.perform(get("/api/admin/system/user-activity").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.identity_masked").value(false))
                    .andExpect(jsonPath("$.data.top_sources[0].reverse_dns").value("host-a.example.com"))
                    .andExpect(jsonPath("$.data.details.logins[0].ip").value("198.51.100.21"))
                    .andExpect(jsonPath("$.data.anomalies.recent[0].ip").value("198.51.100.21"))
                    .andExpect(jsonPath("$.data.heatmaps[0].cells['0-8'][0].city").value("Kent A"));
        }
    }

    @Test
    @DisplayName("user-activity/user/{u}: başkasının zaman çizelgesi USER'a izsiz; kişi kendi çizelgesini tam görür")
    void userTimeline_masksOthersButNotSelf() throws Exception {
        Map<String, Object> tl = Map.of("username", "bob", "logins", 2L, "distinct_ips", 1L,
                "events", List.of(Map.of("id", 1, "ip", "192.0.2.40", "user_agent", "GateBrowser/2.0", "outcome", "SUCCESS")),
                "anomalies", List.of(Map.of("id", 1, "ip", "192.0.2.40", "flags", "UNUSUAL_IP")));
        when(userActivityService.userTimeline(eq("bob"), anyInt())).thenReturn(tl);
        when(userActivityService.userTimeline(eq("regularuser"), anyInt())).thenReturn(tl);
        mvc.perform(get("/api/admin/system/user-activity/user/bob").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identity_masked").value(true))
                .andExpect(jsonPath("$.data.events[0].ip").doesNotExist())
                .andExpect(jsonPath("$.data.events[0].user_agent").doesNotExist())
                .andExpect(jsonPath("$.data.anomalies[0].ip").doesNotExist())
                .andExpect(jsonPath("$.data.logins").value(2));
        mvc.perform(get("/api/admin/system/user-activity/user/regularuser").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identity_masked").value(false))
                .andExpect(jsonPath("$.data.events[0].ip").value("192.0.2.40"));
        mvc.perform(get("/api/admin/system/user-activity/user/bob").session(scopedAdminSession()))
                .andExpect(jsonPath("$.data.events[0].ip").doesNotExist());
        mvc.perform(get("/api/admin/system/user-activity/user/bob").session(auditSession()))
                .andExpect(jsonPath("$.data.events[0].ip").value("192.0.2.40"));
    }

    @Test
    @DisplayName("db-analytics: SQL metni / hata iletisi / kullanıcı adı USER ve kapsamlı müdüre gitmez; sayılar + hata sınıfı kalır; admin/AUDIT tam görür")
    void dbAnalytics_masksSqlTextForNonGlobal() throws Exception {
        Map<String, Object> overview = Map.of(
                "summary", Map.of("queries", 3, "user_count", 2),
                "recent_queries", List.of(Map.of("time", "2026-09-28T08:00:00", "username", "db.kisi.a",
                        "sql", "SELECT * FROM app_users WHERE email = 'kisi.a@example.com'", "duration_ms", 12, "success", true)),
                "failed", List.of(Map.of("time", "2026-09-28T08:01:00", "username", "db.kisi.a",
                        "sql", "SELECT secret_col FROM t", "error", "ERROR: permission denied for table t [SQLSTATE: 42501]")),
                "top_sql", List.of(Map.of("sql", "SELECT $1 FROM app_users", "calls", 9, "avg_ms", 1.5)),
                "slowest_sql", List.of(Map.of("sql", "SELECT pg_sleep_free()", "duration_ms", 900, "username", "db.kisi.a")),
                "top_users", List.of(Map.of("username", "db.kisi.a", "queries", 3, "failed", 1)));
        when(dbAnalyticsService.getOverview(anyInt())).thenReturn(overview);
        for (MockHttpSession s : List.of(userSession(), scopedAdminSession(), teamAdminSession())) {
            String body = mvc.perform(get("/api/admin/system/db-analytics?days=7").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sql_masked").value(true))
                    .andExpect(jsonPath("$.data.recent_queries[0].sql").doesNotExist())
                    .andExpect(jsonPath("$.data.recent_queries[0].username").doesNotExist())
                    .andExpect(jsonPath("$.data.recent_queries[0].duration_ms").value(12))
                    .andExpect(jsonPath("$.data.failed[0].error").doesNotExist())
                    .andExpect(jsonPath("$.data.failed[0].error_kind").value("denied"))
                    .andExpect(jsonPath("$.data.failed[0].sql_state").value("42501"))
                    .andExpect(jsonPath("$.data.top_sql[0].sql").doesNotExist())
                    .andExpect(jsonPath("$.data.top_sql[0].calls").value(9))
                    .andExpect(jsonPath("$.data.slowest_sql[0].username").doesNotExist())
                    .andExpect(jsonPath("$.data.top_users[0].username").doesNotExist())
                    .andExpect(jsonPath("$.data.top_users[0].queries").value(3))
                    .andExpect(jsonPath("$.data.summary.user_count").value(2))
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body)
                    .doesNotContain("db.kisi.a").doesNotContain("kisi.a@example.com").doesNotContain("permission denied");
        }
        for (MockHttpSession s : List.of(adminSession(), auditSession())) {
            mvc.perform(get("/api/admin/system/db-analytics?days=7").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sql_masked").value(false))
                    .andExpect(jsonPath("$.data.recent_queries[0].username").value("db.kisi.a"))
                    .andExpect(jsonPath("$.data.failed[0].error").value("ERROR: permission denied for table t [SQLSTATE: 42501]"))
                    .andExpect(jsonPath("$.data.top_sql[0].sql").value("SELECT $1 FROM app_users"));
        }
    }

    @Test
    @DisplayName("POST anomaly ack as USER returns 403 (yazma: admin/AUDIT'te kalır)")
    void ackAnomaly_asUser_returns403() throws Exception {
        mvc.perform(post("/api/admin/system/user-activity/anomalies/7/ack").session(userSession())
                        .contentType("application/json").content("{\"acknowledge\":true}"))
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
    @DisplayName("GET /api/admin/system/db-analytics as USER returns 200 (2026-09-19: her kademe, salt-okuma)")
    void dbAnalytics_asUser_returns200() throws Exception {
        mvc.perform(get("/api/admin/system/db-analytics?days=7").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** Atıl hesaplar tam listesi (2026-10-09): servisin döndürdüğü {rows, meta} aynen; kimlik izi her zaman düşer. */
    private static Map<String, Object> dormantPayload() {
        Map<String, Object> row = new java.util.LinkedHashMap<>(Map.of("username", "u1", "inactive_days", 120,
                "team_id", 5, "has_email", true));
        row.put("ip", "198.51.100.44");   // sızan bir alan olsa bile maske düşürür (savunma derinliği)
        return Map.of("rows", List.of(row), "meta", Map.of("total", 5001, "cap", 5000, "truncated", true, "threshold_days", 30),
                "generated_at", "2026-10-09T09:00:00");
    }

    @Test
    @DisplayName("GET /user-activity/dormant: global ADMIN, AUDIT, USER ve kapsamlı müdür okur (özetle aynı kapı); rows + meta aynen")
    void dormantAccounts_readGateLikeOverview() throws Exception {
        when(userActivityService.getDormantAccounts()).thenReturn(dormantPayload());
        for (MockHttpSession s : List.of(adminSession(), auditSession(), userSession(), scopedAdminSession())) {
            mvc.perform(get("/api/admin/system/user-activity/dormant").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.rows[0].username").value("u1"))
                    .andExpect(jsonPath("$.data.rows[0].inactive_days").value(120))
                    .andExpect(jsonPath("$.data.meta.total").value(5001))
                    .andExpect(jsonPath("$.data.meta.cap").value(5000))
                    .andExpect(jsonPath("$.data.meta.truncated").value(true));
        }
        verify(permissionService, org.mockito.Mockito.atLeast(4))
                .require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.read"), eq("view"));
    }

    @Test
    @DisplayName("GET /user-activity/dormant: oturumsuz 401, system_health.read yoksa 403 (servis çağrılmaz)")
    void dormantAccounts_deniedWithoutPermission() throws Exception {
        mvc.perform(get("/api/admin/system/user-activity/dormant")).andExpect(status().isUnauthorized());
        org.mockito.Mockito.doThrow(new SecurityException("no permission"))
                .when(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.read"), eq("view"));
        mvc.perform(get("/api/admin/system/user-activity/dormant").session(userSession())).andExpect(status().isForbidden());
        verify(userActivityService, never()).getDormantAccounts();
    }

    @Test
    @DisplayName("GET /user-activity/dormant: kimlik izi maskesi — global olmayanlarda identity_masked=true ve iz alanı yok; global/AUDIT'te görünür")
    void dormantAccounts_masksIdentityTrace() throws Exception {
        when(userActivityService.getDormantAccounts()).thenReturn(dormantPayload());
        String body = mvc.perform(get("/api/admin/system/user-activity/dormant").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identity_masked").value(true))
                .andExpect(jsonPath("$.data.rows[0].ip").doesNotExist())
                .andExpect(jsonPath("$.data.rows[0].username").value("u1"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("198.51.100.44");
        mvc.perform(get("/api/admin/system/user-activity/dormant").session(auditSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identity_masked").value(false));
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
    @DisplayName("GET /user-activity/user/{username} AUDIT ve USER görebilir (2026-09-19); limit 100'e kırpılır")
    void userTimeline_asAudit() throws Exception {
        when(userActivityService.userTimeline(eq("bob"), anyInt())).thenReturn(Map.of("username", "bob", "logins", 3L));
        mvc.perform(get("/api/admin/system/user-activity/user/bob?limit=500").session(auditSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logins").value(3));
        verify(userActivityService).userTimeline("bob", 100);
        mvc.perform(get("/api/admin/system/user-activity/user/bob").session(userSession())).andExpect(status().isOk());   // 2026-09-19: her kademe (salt-okuma)
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

    // ── İstek Gezgini (2026-09-28): /http-metrics/overview + /http-metrics top_endpoints ─────────

    @Test
    @DisplayName("GET /http-metrics/overview: system_health.read reddedilirse 403 — servis HIC cagrilmaz")
    void httpOverview_permissionDenied_returns403() throws Exception {
        denySystemHealthRead();
        mvc.perform(get("/api/admin/system/http-metrics/overview")
                        .param("from", "2026-06-18T09:00:00").param("to", "2026-06-18T10:00:00")
                        .session(userSession()))
                .andExpect(status().isForbidden());
        verify(httpMetricsQueryService, never()).overview(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /http-metrics/overview: izinli USER 200; parametreler servise aynen gider, yanit snake_case")
    void httpOverview_withPermission_passesParams() throws Exception {
        when(httpMetricsQueryService.overview("2026-06-18T09:00:00", "2026-06-18T10:00:00", "GET /api/x", "GET,POST", null))
                .thenReturn(Map.of("endpoints_total", 1, "status_codes", List.of(Map.of("code", 200, "count", 3))));
        mvc.perform(get("/api/admin/system/http-metrics/overview")
                        .param("from", "2026-06-18T09:00:00").param("to", "2026-06-18T10:00:00")
                        .param("endpoint", "GET /api/x").param("method", "GET,POST")
                        .session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endpoints_total").value(1))
                .andExpect(jsonPath("$.data.status_codes[0].code").value(200));
        verify(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.read"), eq("view"));
    }

    @Test
    @DisplayName("GET /http-metrics/overview: gecersiz aralik (IllegalArgument) 400 doner, 500 degil")
    void httpOverview_badRange_returns400() throws Exception {
        when(httpMetricsQueryService.overview(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid date"));
        mvc.perform(get("/api/admin/system/http-metrics/overview")
                        .param("from", "yesterday").param("to", "2026-06-18T10:00:00")
                        .session(adminSession()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /http-metrics: top_endpoints eklenir; sorgu servisi COKERSE ozet yine 200 (alan null)")
    void httpMetrics_topEndpoints_optional() throws Exception {
        when(httpMetricsService.getSummary()).thenReturn(Map.of("total_requests", 5));
        when(httpMetricsService.getHistory()).thenReturn(List.of());
        when(httpMetricsQueryService.topEndpoints()).thenReturn(Map.of("window_hours", 24, "slowest", List.of(), "errors", List.of()));
        mvc.perform(get("/api/admin/system/http-metrics").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.top_endpoints.window_hours").value(24))
                .andExpect(jsonPath("$.data.summary.total_requests").value(5));

        when(httpMetricsQueryService.topEndpoints()).thenThrow(new RuntimeException("db down"));
        mvc.perform(get("/api/admin/system/http-metrics").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.total_requests").value(5))
                .andExpect(jsonPath("$.data.top_endpoints").doesNotExist());
    }

    // Regresyon: Sistem Sagligi sekmesi TUM rollere acik (Nav `show: true`) ve
    // `system_health.read` USER varsayilanlarinda VAR — kapi eklendi diye siradan
    // kullanicinin ekrani kirilmamali. Dordu de izin varken 200 donmeli.

    @Test
    @DisplayName("Uc sistem ucu: izin varken USER icin 200 (ekran kirilmadi) — smtp-logs ayri (A3, asagida)")
    void systemReadEndpoints_asUserWithPermission_return200() throws Exception {
        when(extendedHealthService.getTableStats()).thenReturn(java.util.List.of());
        when(metricsService.getHistory()).thenReturn(java.util.List.of());
        when(httpMetricsService.getSummary()).thenReturn(Map.of("count", 0));
        when(httpMetricsService.getHistory()).thenReturn(java.util.List.of());

        for (String path : java.util.List.of("/db-stats", "/metrics", "/http-metrics")) {
            mvc.perform(get("/api/admin/system" + path).session(userSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
        // Kapi dogru kaynak/eylem ciftiyle soruldu (yanlis anahtar = sessizce her zaman gecen kapi).
        verify(permissionService, org.mockito.Mockito.times(3))
                .require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.read"), eq("view"));
    }

    // ── smtp-logs: yalniz GLOBAL gorucu (A3, 2026-09-28) ───────────────────────
    // Uc takim suzgecsiz TUM posta gunlugunu donduruyordu (her takimin alici adresi + alarm govdesi);
    // arayuz artik /api/admin/smtp-log/* (satir bazinda takim kapsami) kullaniyor. system_health.read
    // USER/TEAM_ADMIN varsayilaninda oldugu icin izin kapisi tek basina SIZINTIYI durdurmuyordu.

    private MockHttpSession teamAdminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "po");
        s.setAttribute("systemRole", "TEAM_ADMIN");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(5L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(List.of(5L)));
        return s;
    }

    /** AD-kaynaklı kapsamlı müdür: rol ADMIN ama takım 2'ye sınırlı — global DEĞİL. */
    private MockHttpSession scopedAdminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "mudur");
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(2L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(List.of(2L)));
        return s;
    }

    @Test
    @DisplayName("A3 smtp-logs: USER / TEAM_ADMIN / kapsamli mudur izinle bile 403 — servis HIC cagrilmaz")
    void smtpLogs_nonGlobal_returns403() throws Exception {
        for (MockHttpSession s : List.of(userSession(), teamAdminSession(), scopedAdminSession())) {
            mvc.perform(get("/api/admin/system/smtp-logs").session(s))
                    .andExpect(status().isForbidden());
        }
        verify(extendedHealthService, never()).getSmtpFailures(anyInt());
    }

    @Test
    @DisplayName("A3 smtp-logs: global admin ve AUDIT (global gorucu) 200 — gun tavani korunur")
    void smtpLogs_globalViewer_returns200() throws Exception {
        when(extendedHealthService.getSmtpFailures(anyInt())).thenReturn(java.util.List.of());
        for (MockHttpSession s : List.of(adminSession(), auditSession())) {
            mvc.perform(get("/api/admin/system/smtp-logs?days=999").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.days").value(365));
        }
        verify(extendedHealthService, org.mockito.Mockito.times(2)).getSmtpFailures(365);
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
    @Test
    @DisplayName("7/24 operatörü (2026-10-04) AUDIT DEĞİLDİR: denetçi/yönetici uçları (anomali onayı, zamanlayıcı kilidi, oturum sonlandırma) 403")
    void nocOperator_cannotUseAuditOrAdminSystemEndpoints() throws Exception {
        MockHttpSession op = userSession();
        op.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        mvc.perform(post("/api/admin/system/user-activity/anomalies/5/ack").session(op)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/system/scheduler-lock").session(op)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/system/terminate-session").session(op)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"username\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
