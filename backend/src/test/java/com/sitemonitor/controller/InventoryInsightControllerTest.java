package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.InventoryImportService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.report.InventoryHygieneService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Envanter hijyen ucu + içe aktarma ucu (2026-09-12): kapsam, kuru koşu varsayılanı, denetim. */
@WebMvcTest(InventoryInsightController.class)
class InventoryInsightControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean com.sitemonitor.service.RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;
    @MockitoBean InventoryHygieneService hygieneService;
    @MockitoBean InventoryImportService importService;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }
    private static MockHttpSession teamAdmin(Long team) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "po");
        s.setAttribute("systemRole", "TEAM_ADMIN");
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", List.of(team));
        return s;
    }
    private static MockHttpSession user(Long team) {
        MockHttpSession s = teamAdmin(team);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("manageTeamIds", List.of());
        return s;
    }

    @Test
    @DisplayName("hijyen: global admin tüm silinmemiş kayıtlar; gruplar kod listesiyle döner")
    void hygiene_admin() throws Exception {
        CertificateInventory a = new CertificateInventory(); a.setDomain("a.example.com");
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of(a));
        var g = new InventoryHygieneService.Group("missing", "Envanter bilgisi eksik", 1,
                List.of(new InventoryHygieneService.Finding("a.example.com", "takım atanmamış", List.of("no_team"))));
        when(hygieneService.analyze(anyList(), eq(Integer.MAX_VALUE))).thenReturn(new InventoryHygieneService.Result(List.of(g), 1));

        mvc.perform(get("/api/admin/inventory/hygiene").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.scanned").value(1))
                .andExpect(jsonPath("$.data.groups[0].key").value("missing"))
                .andExpect(jsonPath("$.data.groups[0].findings[0].codes[0]").value("no_team"));
        verify(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("inventory.crud"), eq("edit"));
    }

    @Test
    @DisplayName("hijyen: takım yöneticisi yalnız görünür takımlarının kayıtlarını analiz eder (IDOR)")
    void hygiene_scoped() throws Exception {
        when(inventoryRepo.findByTeamIdInAndDeletedAtIsNullOrderByDomainAsc(List.of(5L))).thenReturn(List.of());
        when(hygieneService.analyze(anyList(), anyInt())).thenReturn(new InventoryHygieneService.Result(List.of(), 0));
        mvc.perform(get("/api/admin/inventory/hygiene").session(teamAdmin(5L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(inventoryRepo).findByTeamIdInAndDeletedAtIsNullOrderByDomainAsc(List.of(5L));
        verify(inventoryRepo, never()).findByDeletedAtIsNullOrderByDomainAsc();
    }

    @Test
    @DisplayName("içe aktarma: dry_run verilmezse KURU koşu (plan), denetim yazılmaz")
    void import_defaultsToDryRun() throws Exception {
        when(importService.plan(anyList(), any(), eq("admin"), any()))
                .thenReturn(new InventoryImportService.Result(true, 1, 0, 0, 0,
                        List.of(new InventoryImportService.RowResult(2, "a.example.com", "create", null, List.of("domain")))));
        mvc.perform(post("/api/admin/inventory/import").session(admin()).contentType("application/json")
                        .content("{\"rows\":[{\"domain\":\"a.example.com\",\"team\":\"5\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dry_run").value(true))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.rows[0].action").value("create"));
        verify(importService, never()).commit(anyList(), any(), any(), any());
        verify(auditService, never()).recordAction(anyString(), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("içe aktarma: dry_run=false → commit + DOMAIN_IMPORT denetimi (sayımlar ve domainler)")
    void import_commitAudits() throws Exception {
        when(importService.commit(anyList(), any(), eq("admin"), any()))
                .thenReturn(new InventoryImportService.Result(false, 1, 1, 0, 0, List.of(
                        new InventoryImportService.RowResult(2, "a.example.com", "create", null, List.of("domain")),
                        new InventoryImportService.RowResult(3, "b.example.com", "update", null, List.of("tier")))));
        mvc.perform(post("/api/admin/inventory/import").session(admin()).contentType("application/json")
                        .content("{\"dry_run\":false,\"rows\":[{\"domain\":\"a.example.com\"},{\"domain\":\"b.example.com\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dry_run").value(false))
                .andExpect(jsonPath("$.data.updated").value(1));
        verify(auditService).recordAction(eq("DOMAIN_IMPORT"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("CERTIFICATE"), eq("1+1 domain"),
                argThat((String d) -> d.contains("\"created\":1") && d.contains("a.example.com") && d.contains("b.example.com")));
    }

    @Test
    @DisplayName("içe aktarma: USER (yönettiği takım yok) → 403; boş rows → 400")
    void import_guards() throws Exception {
        mvc.perform(post("/api/admin/inventory/import").session(user(5L)).contentType("application/json")
                        .content("{\"rows\":[{\"domain\":\"a.example.com\"}]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/import").session(admin()).contentType("application/json")
                        .content("{\"rows\":[]}"))
                .andExpect(status().isBadRequest());
        verify(importService, never()).plan(anyList(), any(), any(), any());
    }
}
