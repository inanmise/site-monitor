package com.certmonitor.controller;

import com.certmonitor.model.AlertComment;
import com.certmonitor.model.AlertEvent;
import com.certmonitor.repository.*;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IncidentsController.class)
class IncidentsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AlertEventRepository alertEventRepo;
    @MockitoBean AlertCommentRepository commentRepo;
    @MockitoBean HttpMonitorRepository httpMonitorRepo;
    @MockitoBean PortMonitorRepository portMonitorRepo;
    @MockitoBean KeywordMonitorRepository keywordMonitorRepo;
    @MockitoBean PingMonitorRepository pingMonitorRepo;
    @MockitoBean DnsMonitorRepository dnsMonitorRepo;
    @MockitoBean DomainMonitorRepository domainMonitorRepo;
    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean ScriptedMonitorRepository scriptedMonitorRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    // Auth + metrics interceptor bağımlılıkları (WebMvc slice)
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.certmonitor.service.HttpMetricsService httpMetricsService;

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);   // ADMIN + viewTeamIds YOK → global admin/viewer
        return s;
    }

    private static AlertEvent httpDown500() {
        AlertEvent e = new AlertEvent();
        e.setId(1L);
        e.setDomain("https://x.example.com");
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("CRITICAL");
        e.setResolved(false);
        e.setCreatedAt("2026-07-10T10:00:00");
        e.setContextJson("{\"http_status\":500}");
        return e;
    }

    @Test
    @DisplayName("GET /incidents: en yeni türler entegre — SCRIPTED_FAIL→SCENARIO/down/tab=scripted, PAGE_INTEGRITY→INTEGRITY/content/tab=page (unknown/cert'e düşmez)")
    void list_mapsNewestMonitorTypes() throws Exception {
        AlertEvent scripted = new AlertEvent();
        scripted.setId(2L); scripted.setDomain("Login akışı"); scripted.setAlertType("SCRIPTED_FAIL");
        scripted.setAlertLevel("CRITICAL"); scripted.setResolved(false); scripted.setCreatedAt("2026-07-10T10:00:00");
        AlertEvent pageInt = new AlertEvent();
        pageInt.setId(3L); pageInt.setDomain("https://x/campaign"); pageInt.setAlertType("PAGE_INTEGRITY");
        pageInt.setAlertLevel("WARNING"); pageInt.setResolved(false); pageInt.setCreatedAt("2026-07-10T11:00:00");
        when(alertEventRepo.findIncidents(any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(scripted, pageInt)));
        when(alertEventRepo.countIncidentsByType(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(commentRepo.countByAlertIds(any())).thenReturn(List.of());
        com.certmonitor.model.ScriptedMonitor sm = new com.certmonitor.model.ScriptedMonitor();
        sm.setId(20L); sm.setName("Login akışı");
        when(scriptedMonitorRepo.findAll()).thenReturn(List.of(sm));
        com.certmonitor.model.PageMonitor pm = new com.certmonitor.model.PageMonitor();
        pm.setId(30L); pm.setName("Kampanya"); pm.setUrl("https://x/campaign");
        when(pageMonitorRepo.findAll()).thenReturn(List.of(pm));

        mvc.perform(get("/api/monitoring/incidents").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].root_cause.code").value("SCENARIO"))
                .andExpect(jsonPath("$.data[0].root_cause.category").value("down"))
                .andExpect(jsonPath("$.data[0].monitor.tab").value("scripted"))
                .andExpect(jsonPath("$.data[0].monitor.monitor_id").value(20))
                .andExpect(jsonPath("$.data[1].root_cause.code").value("INTEGRITY"))
                .andExpect(jsonPath("$.data[1].root_cause.category").value("content"))
                .andExpect(jsonPath("$.data[1].monitor.tab").value("page"))
                .andExpect(jsonPath("$.data[1].monitor.monitor_id").value(30));
    }

    @Test
    @DisplayName("GET /incidents: HTTP_DOWN + http_status=500 → root_cause 500/server_error, status ongoing, monitor=domain")
    void list_returnsIncidentDto() throws Exception {
        when(alertEventRepo.findIncidents(any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(httpDown500())));
        when(alertEventRepo.countIncidentsByType(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(commentRepo.countByAlertIds(any())).thenReturn(List.of());
        when(httpMonitorRepo.findAll()).thenReturn(List.of());   // eşleşen monitör yok → ad = domain

        mvc.perform(get("/api/monitoring/incidents").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ongoing"))
                .andExpect(jsonPath("$.data[0].root_cause.code").value("500"))
                .andExpect(jsonPath("$.data[0].root_cause.category").value("server_error"))
                .andExpect(jsonPath("$.data[0].monitor.name").value("https://x.example.com"))
                .andExpect(jsonPath("$.data[0].monitor.tab").value("http"))
                .andExpect(jsonPath("$.data[0].comment_count").value(0));
    }

    @Test
    @DisplayName("DELETE /incidents/{id}: ADMIN olmayan → 403, silme çağrılmaz")
    void delete_nonAdmin_forbidden() throws Exception {
        mvc.perform(delete("/api/monitoring/incidents/1").session(session("USER")))
                .andExpect(status().isForbidden());
        verify(alertEventRepo, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("DELETE /incidents/{id}: ADMIN → 200, alarm + yorumları silinir")
    void delete_admin_ok() throws Exception {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(httpDown500()));
        when(commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        mvc.perform(delete("/api/monitoring/incidents/1").session(session("ADMIN")))
                .andExpect(status().isOk());
        verify(alertEventRepo).deleteById(1L);
    }

    @Test
    @DisplayName("POST /incidents/{id}/comments: yorum eklenir (kaydedilir)")
    void addComment_saves() throws Exception {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(httpDown500()));
        when(commentRepo.save(any(AlertComment.class))).thenAnswer(inv -> { AlertComment c = inv.getArgument(0); c.setId(7L); return c; });
        mvc.perform(post("/api/monitoring/incidents/1/comments").session(session("ADMIN"))
                        .contentType("application/json").content("{\"body\":\"needs attention\"}"))
                .andExpect(status().isOk());
        verify(commentRepo).save(any(AlertComment.class));
    }

    @Test
    @DisplayName("POST /incidents/{id}/comments: boş gövde → 400")
    void addComment_empty_rejected() throws Exception {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(httpDown500()));
        mvc.perform(post("/api/monitoring/incidents/1/comments").session(session("ADMIN"))
                        .contentType("application/json").content("{\"body\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(commentRepo, never()).save(any());
    }
}
