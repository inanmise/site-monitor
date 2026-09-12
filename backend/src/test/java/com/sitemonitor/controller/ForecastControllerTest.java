package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Vade takvimi uçları (2026-09-12): tek gövde + ortam etiketi; plan yaz/sil kapsam + denetim. */
@WebMvcTest(ForecastController.class)
class ForecastControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RenewalForecastService forecastService;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean MonitorHistoryService monitorHistory;
    @MockitoBean CertificateService certificateService;
    @MockitoBean BuildInfo buildInfo;

    private static MockHttpSession session(String role, Long team, boolean manage) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u1");
        s.setAttribute("displayName", "User One");
        s.setAttribute("systemRole", role);
        if (team != null) {
            s.setAttribute("teamId", team);
            s.setAttribute("viewTeamIds", List.of(team));
            s.setAttribute("manageTeamIds", manage ? List.of(team) : List.of());
        }
        return s;
    }

    private static BuildInfo.Snapshot snap() {
        return new BuildInfo.Snapshot("1.0.0", "file", "", "", "", "", "test-env", "", null, "",
                "", "inst", "host", "", "", "25", "2026-09-12T00:00:00Z", System.currentTimeMillis());
    }

    @Test
    @DisplayName("GET /api/forecast: servis gövdesi + ortam etiketi; görünür takım kapsamı servise geçer")
    void forecast() throws Exception {
        when(buildInfo.get()).thenReturn(snap());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("certs", List.of(Map.of("domain", "a.example.com")));
        body.put("thresholds", Map.of("warning", 30, "high", 15, "critical", 7));
        when(forecastService.build(any(), any())).thenReturn(body);
        mvc.perform(get("/api/forecast").session(session("TEAM_ADMIN", 5L, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environment").value("test-env"))
                .andExpect(jsonPath("$.data.certs[0].domain").value("a.example.com"))
                .andExpect(jsonPath("$.data.thresholds.critical").value(7));
        verify(forecastService).build(eq(List.of(5L)), any());
        verify(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("inventory.list"), eq("view"));
    }

    @Test
    @DisplayName("POST plan: tarih doğrulanır, kayıt yazılır, geçmiş + CERT_RENEWAL_PLANNED denetimi; DELETE temizler")
    void planAndUnplan() throws Exception {
        CertificateInventory inv = new CertificateInventory(); inv.setId(3L); inv.setDomain("a.example.com"); inv.setTeamId(5L);
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(inv));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/forecast/a.example.com/plan").session(session("TEAM_ADMIN", 5L, true)).contentType("application/json")
                        .content("{\"date\":\"2026-10-01\",\"note\":\"vendor ticket 42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.renewal_planned_at").value("2026-10-01"))
                .andExpect(jsonPath("$.data.renewal_planned_by").value("User One"));
        verify(auditService).recordAction(eq("CERT_RENEWAL_PLANNED"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("CERTIFICATE"), eq("a.example.com"), contains("2026-10-01"));
        verify(monitorHistory).record(eq(MonitorHistoryService.INVENTORY), eq(3L), eq("a.example.com"), eq(5L), eq(MonitorHistoryService.UPDATE), anyMap(), anyMap(), eq("renewal-plan"), any());

        mvc.perform(post("/api/forecast/a.example.com/plan").session(session("TEAM_ADMIN", 5L, true)).contentType("application/json")
                        .content("{\"date\":\"next week\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/forecast/a.example.com/plan").session(session("TEAM_ADMIN", 5L, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.renewal_planned_at").isEmpty());
        verify(auditService).recordAction(eq("CERT_RENEWAL_PLAN_CLEARED"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("CERTIFICATE"), eq("a.example.com"), anyString());
    }

    @Test
    @DisplayName("POST plan: yabancı takımın kaydı → 403 (IDOR); bilinmeyen domain → 400")
    void planScope() throws Exception {
        CertificateInventory inv = new CertificateInventory(); inv.setId(3L); inv.setDomain("f.example.com"); inv.setTeamId(9L);
        when(inventoryRepo.findByDomain("f.example.com")).thenReturn(Optional.of(inv));
        mvc.perform(post("/api/forecast/f.example.com/plan").session(session("TEAM_ADMIN", 5L, true)).contentType("application/json")
                        .content("{\"date\":\"2026-10-01\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/forecast/nope.example.com/plan").session(session("ADMIN", null, true)).contentType("application/json")
                        .content("{\"date\":\"2026-10-01\"}"))
                .andExpect(status().isBadRequest());
        verify(inventoryRepo, never()).save(any());
    }
}
