package com.sitemonitor.controller;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.CertificateCheckerService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @MockitoBean
    RememberMeService rememberMeService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    AuthController authController;

    @MockitoBean
    com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean
    CertificateService certService;

    @MockitoBean
    CertificateCheckerService checkerService;

    @MockitoBean
    SchedulerService schedulerService;

    @MockitoBean
    AlertEventRepository alertEventRepo;

    @MockitoBean
    NetworkOutageEventRepository networkOutageRepo;

    @MockitoBean
    com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo;

    @MockitoBean
    com.sitemonitor.service.ExtendedHealthService extendedHealthService;

    @MockitoBean
    com.sitemonitor.service.PermissionService permissionService;

    @MockitoBean
    com.sitemonitor.service.AuditService auditService;

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
        when(certService.getAllLatestForTeams(null)).thenReturn(List.of());

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
        when(certService.getAllLatestForTeams(null)).thenReturn(List.of(dto));

        mvc.perform(get("/api/certificates").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("example.com"));
    }

    @Test
    @DisplayName("GET /api/warnings returns 200 with warning list")
    void getWarnings_authenticated_returns200() throws Exception {
        when(certService.getWarningsForTeams(null)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/warnings").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("GET /api/stats returns 200 with stats map")
    void getStats_authenticated_returns200() throws Exception {
        when(certService.getStatsForTeams(null)).thenReturn(Map.of("total_certificates", 5));

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
        when(certService.getRenewalAdviceForTeams(null)).thenReturn(Collections.emptyList());

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
        when(checkerService.check("example.com", 443, false, null)).thenReturn(new java.util.LinkedHashMap<>(checkResult));
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.of(invOf("example.com", null)));

        mvc.perform(get("/api/check/example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.domain").value("example.com"));

        // Envanter kaydi ZATEN var; ensureInInventory cagrilmamali (rastgele domain ekleme yolu kapandi).
        org.mockito.Mockito.verify(certService, org.mockito.Mockito.never())
                .ensureInInventory(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("GET /api/check/{domain} forwards inventory tls_mode override to checker")
    void checkDomain_inventoryTlsMode_forwardedToChecker() throws Exception {
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setDomain("example.com");
        inv.setTlsMode("default");
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.of(inv));
        when(checkerService.check("example.com", 443, false, "default"))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of(
                        "domain", "example.com", "status", "valid", "days_remaining", 90)));

        mvc.perform(get("/api/check/example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(checkerService).check("example.com", 443, false, "default");
    }

    @Test
    @DisplayName("GET /api/history/{domain} returns 200 with history list")
    void getHistory_authenticated_returns200() throws Exception {
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.of(invOf("example.com", null)));
        when(certService.getHistory("example.com", 30)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/history/example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.domain").value("example.com"))
                .andExpect(jsonPath("$.data").isArray());
    }

    // NOT: eski /api/activity (yalnız sertifika) testleri kaldırıldı — endpoint yeni birleşik
    // ActivityController'a taşındı (bkz. ActivityControllerTest: izolasyon + sayfalama).

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
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.of(invOf("example.com", null)));
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
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    // ── Domain-anahtarlı uçların takım denetimi ─────────────────────────────────
    // Bu üç uç (check / history / history-alerts) eskiden HİÇBİR yetki kontrolü yapmıyordu:
    // her oturumlu kullanıcı başka takımın geçmişini okuyabiliyor, /check ile rastgele bir host
    // için dış bağlantı açtırıp envantere kalıcı kayıt ekletebiliyordu.

    private static com.sitemonitor.model.CertificateInventory invOf(String domain, Long teamId) {
        com.sitemonitor.model.CertificateInventory i = new com.sitemonitor.model.CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setActive(true); i.setTeamId(teamId);
        return i;
    }

    /** Yalnız takım 5'i gören sıradan kullanıcı. */
    private MockHttpSession scopedSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u5");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(5L)));
        return s;
    }

    @Test
    @DisplayName("GÜVENLİK: /check envanterde OLMAYAN domain'de 404 — kontrol koşmaz, envantere kayıt EKLENMEZ")
    void checkDomain_notInInventory_returns404_andDoesNotProbe() throws Exception {
        when(inventoryRepo.findByDomain("rastgele.example.com")).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/check/rastgele.example.com").session(authSession()))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verify(checkerService, org.mockito.Mockito.never())
                .check(anyString(), anyInt(), anyBoolean(), any());
        org.mockito.Mockito.verify(certService, org.mockito.Mockito.never())
                .ensureInInventory(anyString(), anyInt(), any());
        org.mockito.Mockito.verify(certService, org.mockito.Mockito.never()).evictAllCaches();
    }

    @Test
    @DisplayName("IDOR: /check başka takımın domain'inde 403 — dış bağlantı açılmaz")
    void checkDomain_foreignTeam_returns403() throws Exception {
        when(inventoryRepo.findByDomain("baska.example.com")).thenReturn(java.util.Optional.of(invOf("baska.example.com", 9L)));

        mvc.perform(get("/api/check/baska.example.com").session(scopedSession()))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(checkerService, org.mockito.Mockito.never())
                .check(anyString(), anyInt(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Kendi takımının domain'inde /check çalışmaya devam eder (dashboard ▶ butonu)")
    void checkDomain_ownTeam_returns200() throws Exception {
        when(inventoryRepo.findByDomain("benim.example.com")).thenReturn(java.util.Optional.of(invOf("benim.example.com", 5L)));
        when(checkerService.check("benim.example.com", 443, false, null))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("domain", "benim.example.com", "status", "valid")));

        mvc.perform(get("/api/check/benim.example.com").session(scopedSession()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("IDOR: /history ve /history/alerts başka takımın domain'inde 403")
    void history_foreignTeam_returns403() throws Exception {
        when(inventoryRepo.findByDomain("baska.example.com")).thenReturn(java.util.Optional.of(invOf("baska.example.com", 9L)));

        mvc.perform(get("/api/history/baska.example.com").session(scopedSession()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/history/baska.example.com/alerts").session(scopedSession()))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(certService, org.mockito.Mockito.never()).getHistory(anyString(), anyInt());
    }
}
