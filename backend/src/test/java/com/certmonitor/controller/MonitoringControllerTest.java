package com.certmonitor.controller;

import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.model.UptimeCheck;
import com.certmonitor.repository.*;
import com.certmonitor.service.CertificateService;
import com.certmonitor.service.DnsCheckerService;
import com.certmonitor.service.PortCheckerService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MonitoringController.class)
class MonitoringControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.certmonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean LatestCheckRepository latestCheckRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean CertificateCheckRepository certCheckRepo;
    @MockitoBean UptimeCheckRepository uptimeCheckRepo;
    @MockitoBean PortMonitorRepository portMonitorRepo;
    @MockitoBean PortCheckRepository portCheckRepo;
    @MockitoBean PortCheckerService portChecker;
    @MockitoBean DnsMonitorRepository dnsMonitorRepo;
    @MockitoBean DnsRecordRepository dnsRecordRepo;
    @MockitoBean DnsCheckerService dnsChecker;
    @MockitoBean CertificateService certificateService;
    @MockitoBean com.certmonitor.service.PermissionService permissionService;

    @MockitoBean KeywordMonitorRepository keywordMonitorRepo;
    @MockitoBean KeywordResultRepository keywordResultRepo;
    @MockitoBean com.certmonitor.service.KeywordCheckerService keywordChecker;
    @MockitoBean PingMonitorRepository pingMonitorRepo;
    @MockitoBean PingCheckRepository pingCheckRepo;
    @MockitoBean com.certmonitor.service.PingCheckerService pingChecker;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean com.certmonitor.service.EscalationService escalationService;
    @MockitoBean com.certmonitor.service.AppSettingsService appSettings;

    @BeforeEach
    void stubTeamMap() {
        // İzleme uçları artık domain→takım map'ini buradan alıyor; boş map yeterli (team_name=null).
        when(certificateService.domainTeamNameMap()).thenReturn(java.util.Map.of());
        // teamNameMap() artık CertificateService.teamNamesById()'e (cache'li) delege ediyor.
        when(certificateService.teamNamesById()).thenReturn(java.util.Map.of());
    }

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        return s;
    }

    private static CertificateInventory inv(String domain) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setActive(true);
        return i;
    }

    private static LatestCheck lc(String domain, String status) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain); c.setStatus(status); c.setDaysRemaining(40);
        c.setNotAfter("2026-12-31T00:00:00"); c.setCheckedAt("2026-06-13T00:00:00");
        return c;
    }

    private static UptimeCheck uchk(String domain, String status) {
        UptimeCheck u = new UptimeCheck();
        u.setDomain(domain); u.setPort(443); u.setStatus(status);
        u.setCheckedAt("2099-01-01T00:00:00");
        return u;
    }

    @Test
    @DisplayName("uptimeOverview: uptime% domain-bazlı toplu sorgudan (4 toplam/1 hata → %75), lc'siz domain → 'unknown'")
    void uptimeOverview_computesUptimeAndHandlesMissingCheck() throws Exception {
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(lc("a.com", "valid")));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.com"), inv("b.com")));
        // P1/N1: artık domain başına findByCheckedAtAfter (tüm tablo) yerine tek gruplu sorgu.
        when(certCheckRepo.aggregateStatusCountsSince(anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"a.com", 4L, 1L}));

        mvc.perform(get("/api/monitoring/uptime/overview").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("a.com"))
                .andExpect(jsonPath("$.data[0].uptime_30d").value(75.0))
                .andExpect(jsonPath("$.data[0].incidents_30d").value(1))
                .andExpect(jsonPath("$.data[1].domain").value("b.com"))
                .andExpect(jsonPath("$.data[1].status").value("unknown"))
                .andExpect(jsonPath("$.data[1].uptime_30d").doesNotExist()); // null

        // Regresyon: domain başına tüm-tablo taraması bir daha yapılmamalı (P1).
        org.mockito.Mockito.verify(certCheckRepo, org.mockito.Mockito.never()).findByCheckedAtAfter(anyString());
        org.mockito.Mockito.verify(uptimeCheckRepo, org.mockito.Mockito.never())
                .findTopByDomainAndPortOrderByIdDesc(anyString(), anyInt());
    }

    @Test
    @DisplayName("uptimeOverview: kontrol kaydı yoksa boş veri, %100 değil null uptime — hata vermez")
    void uptimeOverview_empty() throws Exception {
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of());
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        mvc.perform(get("/api/monitoring/uptime/overview").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("uptimeOverview: http_ok — 24h hep up→true, biri down→false, kayıt yok→null")
    void uptimeOverview_httpOk() throws Exception {
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(lc("a.com", "valid"), lc("b.com", "valid")));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.com"), inv("b.com"), inv("c.com")));
        when(uptimeCheckRepo.findTopByDomainAndPortOrderByIdDesc(anyString(), anyInt())).thenReturn(Optional.empty());
        when(certCheckRepo.findByCheckedAtAfter(anyString())).thenReturn(List.of());
        when(uptimeCheckRepo.findByCheckedAtGreaterThanEqual(anyString())).thenReturn(List.of(
                uchk("a.com", "up"), uchk("a.com", "up"),
                uchk("b.com", "up"), uchk("b.com", "down")));

        mvc.perform(get("/api/monitoring/uptime/overview").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("a.com"))
                .andExpect(jsonPath("$.data[0].http_ok").value(true))
                .andExpect(jsonPath("$.data[1].domain").value("b.com"))
                .andExpect(jsonPath("$.data[1].http_ok").value(false))
                .andExpect(jsonPath("$.data[2].domain").value("c.com"))
                .andExpect(jsonPath("$.data[2].http_ok").doesNotExist()); // null → kayıt yok
    }

    @Test
    @DisplayName("DELETE /port/{id}: USER 403 (admin-only write)")
    void deletePort_forbiddenForUser() throws Exception {
        mvc.perform(delete("/api/monitoring/port/1").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /keyword/test: canlı koşul testi (occurrences/condition_met/phrase)")
    void testKeyword_returnsResult() throws Exception {
        java.util.Map<String, Object> cr = new java.util.HashMap<>();
        cr.put("count", 5); cr.put("http_status", 200); cr.put("response_ms", 12L);
        when(keywordChecker.check(eq("https://x.example.com"), eq("akbank"), anyInt(), any())).thenReturn(cr);

        mvc.perform(post("/api/monitoring/keyword/test").session(session("USER"))
                .contentType("application/json")
                .content("{\"url\":\"https://x.example.com\",\"keyword\":\"akbank\",\"operator\":\"GTE\",\"matchCount\":3,\"timeoutMs\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.occurrences").value(5))
                .andExpect(jsonPath("$.data.condition_met").value(true))   // 5 >= 3
                .andExpect(jsonPath("$.data.phrase").value("en az 3 kez"));
    }

    @Test
    @DisplayName("POST /keyword/test: url/keyword boş → 400")
    void testKeyword_blank_returns400() throws Exception {
        mvc.perform(post("/api/monitoring/keyword/test").session(session("USER"))
                .contentType("application/json").content("{\"url\":\"\",\"keyword\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /keyword/{id}/response-series: kovalar avg/min/max/p95/down döner")
    void keywordResponseSeries_buckets() throws Exception {
        when(keywordMonitorRepo.existsById(5L)).thenReturn(true);
        // Aynı saat kovasında 3 kayıt (100/200/300 ms), biri down (ok=false)
        List<Object[]> rows = List.of(
                new Object[]{ "2026-06-24T10:05:00", 100L, true },
                new Object[]{ "2026-06-24T10:25:00", 300L, true },
                new Object[]{ "2026-06-24T10:45:00", 200L, false });
        when(keywordResultRepo.responseSeriesRaw(eq(5L), anyString(), anyString(), anyInt())).thenReturn(rows);

        mvc.perform(get("/api/monitoring/keyword/5/response-series?days=7").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bucket").value("hour"))
                .andExpect(jsonPath("$.data.series[0].count").value(3))
                .andExpect(jsonPath("$.data.series[0].down").value(1))
                .andExpect(jsonPath("$.data.series[0].avg").value(200))
                .andExpect(jsonPath("$.data.series[0].min").value(100))
                .andExpect(jsonPath("$.data.series[0].max").value(300))
                .andExpect(jsonPath("$.data.series[0].p95").value(300));
    }

    @Test
    @DisplayName("GET /ping/{id}/response-series: RTT ortalaması + paket kaybı + down")
    void pingResponseSeries_withLoss() throws Exception {
        when(pingMonitorRepo.existsById(9L)).thenReturn(true);
        List<Object[]> rows = List.of(
                new Object[]{ "2026-06-24T10:05:00", 10L, true,  0 },
                new Object[]{ "2026-06-24T10:25:00", 30L, false, 100 });
        when(pingCheckRepo.responseSeriesRaw(eq(9L), anyString(), anyString(), anyInt())).thenReturn(rows);

        mvc.perform(get("/api/monitoring/ping/9/response-series?days=7").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series[0].down").value(1))
                .andExpect(jsonPath("$.data.series[0].avg").value(20))
                .andExpect(jsonPath("$.data.series[0].loss").value(50));
    }
}
