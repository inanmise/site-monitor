package com.certmonitor.controller;

import com.certmonitor.dto.CertificateDto;
import com.certmonitor.model.AlertEvent;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.service.CertificateCheckerService;
import com.certmonitor.service.CertificateService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.SchedulerService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CertificateController.class)
class CertificateControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    RememberMeService rememberMeService;

    @MockBean
    UserService userService;

    @MockBean
    AuthController authController;

    @MockBean
    com.certmonitor.service.HttpMetricsService httpMetricsService;

    @MockBean
    CertificateService certService;

    @MockBean
    CertificateCheckerService checkerService;

    @MockBean
    SchedulerService schedulerService;

    @MockBean
    AlertEventRepository alertEventRepo;

    // ── Auth guard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/certificates without session returns 401")
    void getCertificates_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/certificates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/warnings without session returns 401")
    void getWarnings_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/warnings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/stats without session returns 401")
    void getStats_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/stats"))
                .andExpect(status().isUnauthorized());
    }

    // ── Authenticated endpoints ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/certificates with auth returns 200 and success:true")
    void getCertificates_authenticated_returns200() throws Exception {
        when(certService.getAllLatestForTeam(null)).thenReturn(List.of());

        mvc.perform(get("/api/certificates").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/certificates returns all certs from service")
    void getCertificates_returnsCertList() throws Exception {
        CertificateDto dto = new CertificateDto();
        dto.setDomain("example.com");
        when(certService.getAllLatestForTeam(null)).thenReturn(List.of(dto));

        mvc.perform(get("/api/certificates").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("example.com"));
    }

    @Test
    @DisplayName("GET /api/warnings returns 200 with warning list")
    void getWarnings_authenticated_returns200() throws Exception {
        when(certService.getWarningsForTeam(null)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/warnings").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("GET /api/stats returns 200 with stats map")
    void getStats_authenticated_returns200() throws Exception {
        when(certService.getStatsForTeam(null)).thenReturn(Map.of("total_certificates", 5));

        mvc.perform(get("/api/stats").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total_certificates").value(5));
    }

    @Test
    @DisplayName("POST /api/scheduler/run returns 200 and starts check")
    void runScheduler_authenticated_returns200() throws Exception {
        mvc.perform(post("/api/scheduler/run").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/scheduler/status returns 200")
    void schedulerStatus_authenticated_returns200() throws Exception {
        when(schedulerService.getStatus()).thenReturn(Map.of("running", false));

        mvc.perform(get("/api/scheduler/status").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/renewal-advice returns 200 with advice list")
    void getRenewalAdvice_authenticated_returns200() throws Exception {
        when(certService.getRenewalAdviceForTeam(null)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/renewal-advice").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("GET /api/check/{domain} returns 200 with check result and auto-adds to inventory")
    void checkDomain_authenticated_returns200() throws Exception {
        Map<String, Object> checkResult = Map.of(
                "domain", "example.com",
                "status", "valid",
                "days_remaining", 90
        );
        when(checkerService.check("example.com", 443)).thenReturn(new java.util.LinkedHashMap<>(checkResult));

        mvc.perform(get("/api/check/example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.domain").value("example.com"));

        // ensureInInventory is called — certService is a mock so the call succeeds silently
        org.mockito.Mockito.verify(certService).ensureInInventory("example.com", 443, null);
    }

    @Test
    @DisplayName("GET /api/history/{domain} returns 200 with history list")
    void getHistory_authenticated_returns200() throws Exception {
        when(certService.getHistory("example.com", 30)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/history/example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.domain").value("example.com"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/activity returns 200 with run list (default 24h)")
    void getActivityLog_authenticated_returns200() throws Exception {
        when(certService.getActivityLog(24, null)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/activity").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/activity?hours=6 passes correct hours param")
    void getActivityLog_customHours() throws Exception {
        when(certService.getActivityLog(6, null)).thenReturn(List.of(
                Map.of("run_id", "abc123", "run_time", "2026-05-16T10:00:00",
                        "total", 5, "ok", 4, "warning", 1, "error", 0, "entries", List.of())
        ));

        mvc.perform(get("/api/activity?hours=6").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].run_id").value("abc123"))
                .andExpect(jsonPath("$.data[0].total").value(5));
    }

    @Test
    @DisplayName("GET /api/activity without session returns 401")
    void getActivityLog_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/activity"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/certificates/list returns 200 with paginated data")
    void getCertificatesList_authenticated_returns200() throws Exception {
        when(certService.getPaginated(anyInt(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of(
                        "data", Collections.emptyList(),
                        "pagination", Map.of("total", 0, "page", 1)
                ));

        mvc.perform(get("/api/certificates/list").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/history/{domain}/alerts returns 200 with alert list")
    void getDomainAlerts_authenticated_returns200() throws Exception {
        AlertEvent alert = new AlertEvent();
        alert.setId(1L);
        alert.setDomain("example.com");
        alert.setAlertType("EXPIRY");
        alert.setAlertLevel("WARNING");
        when(alertEventRepo.findByDomainOrderByCreatedAtDesc("example.com"))
                .thenReturn(List.of(alert));

        mvc.perform(get("/api/history/example.com/alerts").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.domain").value("example.com"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].alert_type").value("EXPIRY"));
    }

    @Test
    @DisplayName("GET /api/history/{domain}/alerts without session returns 401")
    void getDomainAlerts_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/history/example.com/alerts"))
                .andExpect(status().isUnauthorized());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private MockHttpSession authSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "testuser");
        return s;
    }
}
