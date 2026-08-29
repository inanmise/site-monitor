package com.sitemonitor.controller;

import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.IncidentService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IncidentController.class)
class IncidentControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean IncidentService service;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.service.IncidentNotificationService notificationService;
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
        when(service.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
    @DisplayName("PUT: USER kendi takımının olayını düzenler (manageTeamIds boş olsa da) → 200")
    void update_userOwnTeam_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.manage"), eq("edit"))).thenReturn(true);
        IncidentRecord rec = sample();
        rec.setTeamId(5L);
        when(service.get(1L)).thenReturn(rec);
        when(service.update(eq(1L), any(), any())).thenReturn(rec);
        mvc.perform(put("/api/incidents/1").session(userSessionTeam(5L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PUT: USER başka takımın olayını düzenleyemez (IDOR) → 403")
    void update_userOtherTeam_403() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.manage"), eq("edit"))).thenReturn(true);
        IncidentRecord rec = sample();
        rec.setTeamId(99L);
        when(service.get(1L)).thenReturn(rec);
        mvc.perform(put("/api/incidents/1").session(userSessionTeam(5L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isForbidden());
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
    @DisplayName("POST /api/incidents: send_notification kontrolü — bayraksız mail YOK, true ise VAR")
    void create_sendNotificationGate() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.manage"), eq("edit"))).thenReturn(true);
        when(service.create(any(), any(), any(), any())).thenReturn(sample());

        // bayrak yok → mail GİTMEZ
        mvc.perform(post("/api/incidents").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"occurred_at\":\"2026-06-14T10:00:00\"}"))
                .andExpect(status().isOk());
        verify(notificationService, never()).notifyIncident(any(), any());

        // send_notification=true → mail GİDER (kind=NEW)
        mvc.perform(post("/api/incidents").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"occurred_at\":\"2026-06-14T10:00:00\",\"send_notification\":true}"))
                .andExpect(status().isOk());
        verify(notificationService).notifyIncident(any(), eq("NEW"));
    }

    @Test
    @DisplayName("GET /api/incidents/trends view varsa → 200")
    void trends_view_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        when(service.trends(any(), any(), any())).thenReturn(java.util.Map.of(
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

    /** USER oturumu, görüntüleme kapsamı = [teamId], yönetim kapsamı BOŞ (USER gerçeği). */
    private MockHttpSession userSessionTeam(Long teamId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "sre1");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(java.util.List.of(teamId)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<Long>());
        return s;
    }

    // -- POST /api/incidents/transfer takim kapsami (O9) ----------------------
    // Bu uc yalniz incidents.manage istiyordu; o yetki USER'a acik oldugundan bir kullanici
    // BASKA takimin olayini kendi takimina, ya da kendi olayini yabanci bir takima tasiyabiliyordu.
    // Kardes uclar (update/delete/uploadImage) requireIncidentWrite kullaniyordu - burada eksikti.

    private static IncidentRecord recOfTeam(Long id, Long teamId) {
        IncidentRecord e = new IncidentRecord();
        e.setId(id);
        e.setTitle("kayit-" + id);
        e.setTeamId(teamId);
        return e;
    }

    private void allowManage() {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class),
                eq("incidents.manage"), eq("edit"))).thenReturn(true);
    }

    @Test
    @DisplayName("transfer: KENDI takiminin kaydi, KENDI takimina -> 200")
    void transfer_ownRecordOwnTeam_200() throws Exception {
        allowManage();
        when(service.get(1L)).thenReturn(recOfTeam(1L, 7L));
        when(service.transfer(anyList(), eq(7L), any(), any())).thenReturn(1);

        mvc.perform(post("/api/incidents/transfer").session(userSessionTeam(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"team_id\":7,\"team_name\":\"Takim A\"}"))
                .andExpect(status().isOk());
        verify(service).transfer(anyList(), eq(7L), any(), any());
    }

    @Test
    @DisplayName("transfer: YABANCI takimin kaydi -> 403 ve HICBIR kayit tasinmaz")
    void transfer_foreignRecord_403() throws Exception {
        allowManage();
        when(service.get(1L)).thenReturn(recOfTeam(1L, 99L));   // baska takimin olayi

        mvc.perform(post("/api/incidents/transfer").session(userSessionTeam(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"team_id\":7,\"team_name\":\"Takim A\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).transfer(anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("transfer: HEDEF takim kapsam disi -> 403 (kayit gorus alanindan cikarilamaz)")
    void transfer_targetTeamOutOfScope_403() throws Exception {
        allowManage();
        when(service.get(1L)).thenReturn(recOfTeam(1L, 7L));    // kaynak kendi takimi

        mvc.perform(post("/api/incidents/transfer").session(userSessionTeam(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"team_id\":42,\"team_name\":\"Takim B\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).transfer(anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("transfer: KARISIK toplu secimde KISMI basari YOK - tek yetkisiz kayit hepsini durdurur")
    void transfer_mixedBatch_allOrNothing() throws Exception {
        allowManage();
        when(service.get(1L)).thenReturn(recOfTeam(1L, 7L));    // kendi
        when(service.get(2L)).thenReturn(recOfTeam(2L, 99L));   // yabanci

        mvc.perform(post("/api/incidents/transfer").session(userSessionTeam(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2],\"team_id\":7,\"team_name\":\"Takim A\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).transfer(anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("transfer: global admin her takima tasiyabilir (davranis korunur)")
    void transfer_admin_unrestricted() throws Exception {
        allowManage();
        when(service.get(1L)).thenReturn(recOfTeam(1L, 99L));
        when(service.transfer(anyList(), eq(42L), any(), any())).thenReturn(1);

        mvc.perform(post("/api/incidents/transfer").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"team_id\":42,\"team_name\":\"Takim B\"}"))
                .andExpect(status().isOk());
        verify(service).transfer(anyList(), eq(42L), any(), any());
    }
}
