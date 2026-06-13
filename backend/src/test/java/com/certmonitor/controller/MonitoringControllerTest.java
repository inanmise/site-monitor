package com.certmonitor.controller;

import com.certmonitor.model.CertificateCheck;
import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.model.UptimeCheck;
import com.certmonitor.repository.*;
import com.certmonitor.service.DnsCheckerService;
import com.certmonitor.service.PortCheckerService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

    @MockBean RememberMeService rememberMeService;
    @MockBean UserService userService;
    @MockBean AuthController authController;
    @MockBean com.certmonitor.service.HttpMetricsService httpMetricsService;

    @MockBean LatestCheckRepository latestCheckRepo;
    @MockBean CertificateInventoryRepository inventoryRepo;
    @MockBean CertificateCheckRepository certCheckRepo;
    @MockBean UptimeCheckRepository uptimeCheckRepo;
    @MockBean PortMonitorRepository portMonitorRepo;
    @MockBean PortCheckRepository portCheckRepo;
    @MockBean PortCheckerService portChecker;
    @MockBean DnsMonitorRepository dnsMonitorRepo;
    @MockBean DnsRecordRepository dnsRecordRepo;
    @MockBean DnsCheckerService dnsChecker;

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

    private static CertificateCheck chk(String domain, String status) {
        CertificateCheck c = new CertificateCheck();
        c.setDomain(domain); c.setStatus(status);
        c.setCheckedAt("2099-01-01T00:00:00"); // her cutoff'tan yeni → 7d ve 30d'ye dahil
        return c;
    }

    private static UptimeCheck uchk(String domain, String status) {
        UptimeCheck u = new UptimeCheck();
        u.setDomain(domain); u.setPort(443); u.setStatus(status);
        u.setCheckedAt("2099-01-01T00:00:00");
        return u;
    }

    @Test
    @DisplayName("uptimeOverview: calcUptime (3 ok / 1 error → %75), lc'siz domain → 'unknown'")
    void uptimeOverview_computesUptimeAndHandlesMissingCheck() throws Exception {
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(lc("a.com", "valid")));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.com"), inv("b.com")));
        when(uptimeCheckRepo.findTopByDomainAndPortOrderByIdDesc(anyString(), anyInt())).thenReturn(Optional.empty());
        when(certCheckRepo.findByCheckedAtAfter(anyString())).thenReturn(List.of(
                chk("a.com", "valid"), chk("a.com", "valid"), chk("a.com", "valid"), chk("a.com", "error")));

        mvc.perform(get("/api/monitoring/uptime/overview").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("a.com"))
                .andExpect(jsonPath("$.data[0].uptime_30d").value(75.0))
                .andExpect(jsonPath("$.data[0].incidents_30d").value(1))
                .andExpect(jsonPath("$.data[1].domain").value("b.com"))
                .andExpect(jsonPath("$.data[1].status").value("unknown"))
                .andExpect(jsonPath("$.data[1].uptime_30d").doesNotExist()); // null
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
