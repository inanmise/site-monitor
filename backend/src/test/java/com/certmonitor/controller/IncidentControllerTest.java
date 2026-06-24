package com.certmonitor.controller;

import com.certmonitor.model.IncidentRecord;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.IncidentService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IncidentController.class)
class IncidentControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean IncidentService service;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean com.certmonitor.service.IncidentNotificationService notificationService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @Test
    @DisplayName("GET /api/incidents oturumsuz → 401")
    void list_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/incidents incidents.view yoksa → 403")
    void list_noView_403() throws Exception {
        // permissionService.allows default false → requireView fırlatır
        mvc.perform(get("/api/incidents").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/incidents view varsa → 200 + data")
    void list_view_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        when(service.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(sample())));

        mvc.perform(get("/api/incidents").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].title").value("DB pool tükendi"))
                .andExpect(jsonPath("$.data[0].channel").value("Bireysel İnternet Şubesi"))
                .andExpect(jsonPath("$.data[0].sla_breached").value(true));
    }

    @Test
    @DisplayName("DELETE: incidents.manage var ama incidents.delete yoksa → 403")
    void delete_noDeletePerm_403() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.manage"), eq("edit"))).thenReturn(true);
        mvc.perform(delete("/api/incidents/1").session(adminSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE: incidents.delete varsa → 200")
    void delete_withPerm_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.delete"), eq("execute"))).thenReturn(true);
        when(service.delete(1L)).thenReturn(sample());
        mvc.perform(delete("/api/incidents/1").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/incidents/options view varsa → 200 + liste")
    void options_view_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        when(service.listOptions(eq("CHANNEL"), any(), anyBoolean())).thenReturn(List.of("IVR", "ATM"));
        mvc.perform(get("/api/incidents/options").param("type", "CHANNEL").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("IVR"))
                .andExpect(jsonPath("$.data[1]").value("ATM"));
    }

    @Test
    @DisplayName("POST /api/incidents/options manage varsa → 200 + eklenen değer")
    void addOption_manage_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.manage"), eq("edit"))).thenReturn(true);
        when(service.addOption(eq("CHANNEL"), eq("Şube TV"), any(), any())).thenReturn("Şube TV");
        mvc.perform(post("/api/incidents/options").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHANNEL\",\"value\":\"Şube TV\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("Şube TV"));
    }

    @Test
    @DisplayName("POST /api/incidents/options manage yoksa → 403")
    void addOption_noManage_403() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        mvc.perform(post("/api/incidents/options").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHANNEL\",\"value\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/incidents manage yoksa → 403")
    void create_noManage_403() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        mvc.perform(post("/api/incidents").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/incidents manage varsa → 200")
    void create_manage_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.manage"), eq("edit"))).thenReturn(true);
        when(service.create(any(), any(), any(), any())).thenReturn(sample());

        mvc.perform(post("/api/incidents").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"DB pool tükendi\",\"occurred_at\":\"2026-06-14T10:00:00\",\"severity\":\"CRITICAL\",\"status\":\"RESOLVED\",\"category\":\"DATABASE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.severity").value("CRITICAL"));
    }

    @Test
    @DisplayName("GET /api/incidents/trends view varsa → 200")
    void trends_view_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        when(service.trends(any(), any())).thenReturn(java.util.Map.of(
                "daily", List.of(), "by_severity", java.util.Map.of(), "summary", java.util.Map.of("total", 0L)));
        mvc.perform(get("/api/incidents/trends").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private IncidentRecord sample() {
        IncidentRecord e = new IncidentRecord();
        e.setId(1L);
        e.setTitle("DB pool tükendi");
        e.setOccurredAt("2026-06-14T10:00:00");
        e.setSeverity("CRITICAL");
        e.setStatus("RESOLVED");
        e.setCategory("DATABASE");
        e.setService("payment-gateway");
        e.setChannel("Bireysel İnternet Şubesi");
        e.setSlaBreached(true);
        e.setErrorBudgetBurnPct(12.0);
        return e;
    }

    private MockHttpSession userSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "sre1");
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
