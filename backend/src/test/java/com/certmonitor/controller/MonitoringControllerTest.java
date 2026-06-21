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

    @BeforeEach
    void stubTeamMap() {
        // İzleme uçları artık domain→takım map'ini buradan alıyor; boş map yeterli (team_name=null).
        when(certificateService.domainTeamNameMap()).thenReturn(java.util.Map.of());
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
}
