package com.certmonitor.controller;

import com.certmonitor.model.*;
import com.certmonitor.repository.*;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.EscalationService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

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
    CertificateInventoryRepository inventoryRepo;

    @MockBean
    AlertThresholdRepository thresholdRepo;

    @MockBean
    EscalationContactRepository contactRepo;

    @MockBean
    AlertEventRepository alertEventRepo;

    @MockBean
    EscalationService escalationService;

    @MockBean
    NotificationLogRepository notificationLogRepo;

    @MockBean
    com.certmonitor.repository.LatestCheckRepository latestCheckRepo;

    @MockBean
    AuditService auditService;

    @MockBean
    CertificateNoteRepository noteRepo;

    @MockBean
    com.certmonitor.repository.CertificateNoteRevisionRepository noteRevisionRepo;

    @MockBean
    AppUserRepository userRepo;

    @MockBean
    com.certmonitor.repository.TeamRepository teamRepo;

    @MockBean
    com.certmonitor.service.EmailNotificationService emailNotificationService;

    @BeforeEach
    void setup() {
        when(userService.listTeams()).thenReturn(java.util.Collections.emptyList());
    }

    // ── Auth guard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/admin/inventory without auth returns 401")
    void listInventory_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/admin/inventory"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/admin/contacts without auth returns 401")
    void listContacts_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/admin/contacts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/admin/alerts without auth returns 401")
    void listAlerts_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/admin/alerts"))
                .andExpect(status().isUnauthorized());
    }

    // ── Inventory ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/admin/inventory returns 200 with sorted domain list")
    void listInventory_authenticated_returns200() throws Exception {
        CertificateInventory inv = inventory("example.com");
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of(inv));

        mvc.perform(get("/api/admin/inventory").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].domain").value("example.com"));
    }

    @Test
    @DisplayName("POST /api/admin/inventory creates new inventory item")
    void addInventory_authenticated_returns200() throws Exception {
        CertificateInventory saved = inventory("newdomain.com");
        saved.setId(1L);
        when(inventoryRepo.save(any())).thenReturn(saved);

        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"newdomain.com\",\"port\":443,\"team_id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.domain").value("newdomain.com"));
    }

    @Test
    @DisplayName("PUT /api/admin/inventory/{id} updates existing item")
    void updateInventory_authenticated_returns200() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"updated.com\",\"port\":443,\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PUT /api/admin/inventory/{id} with unknown id returns 404")
    void updateInventory_unknownId_returns404() throws Exception {
        when(inventoryRepo.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(put("/api/admin/inventory/999")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"x.com\",\"port\":443}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("DELETE /api/admin/inventory/{id} soft-deletes the inventory item")
    void deleteInventory_authenticated_returns200() throws Exception {
        CertificateInventory inv = inventory("example.com");
        inv.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inv));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(delete("/api/admin/inventory/1").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.alertsClosed").value(0));

        org.mockito.Mockito.verify(inventoryRepo).save(any());
    }

    @Test
    @DisplayName("DELETE /api/admin/inventory/{id} closes open alerts and returns count")
    void deleteInventory_withOpenAlerts_closesAndReturnsCount() throws Exception {
        CertificateInventory inv = inventory("stuck.example.com");
        inv.setId(7L);
        when(inventoryRepo.findById(7L)).thenReturn(Optional.of(inv));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(escalationService.closeAlertsOnInventoryDelete("stuck.example.com")).thenReturn(3);

        mvc.perform(delete("/api/admin/inventory/7").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.alertsClosed").value(3));

        org.mockito.Mockito.verify(escalationService).closeAlertsOnInventoryDelete("stuck.example.com");
    }

    // ── Thresholds ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/admin/thresholds returns 200 with threshold list")
    void getThresholds_authenticated_returns200() throws Exception {
        when(thresholdRepo.findAll()).thenReturn(List.of(defaultThreshold()));

        mvc.perform(get("/api/admin/thresholds").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("PUT /api/admin/thresholds/{id} updates threshold")
    void updateThreshold_authenticated_returns200() throws Exception {
        AlertThreshold existing = defaultThreshold();
        when(thresholdRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(thresholdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/thresholds/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warningDays\":25,\"highDays\":12,\"criticalDays\":5,\"reAlertIntervalHours\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── Contacts ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/admin/contacts returns only active contacts")
    void listContacts_authenticated_returns200() throws Exception {
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(
                List.of(contact("po@test.com", "PO")));

        mvc.perform(get("/api/admin/contacts").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].email").value("po@test.com"));
    }

    @Test
    @DisplayName("GET /api/admin/contacts/all returns all contacts including inactive")
    void listAllContacts_authenticated_returns200() throws Exception {
        when(contactRepo.findAll()).thenReturn(
                List.of(contact("a@test.com", "PO"), contact("b@test.com", "TECH")));

        mvc.perform(get("/api/admin/contacts/all").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("POST /api/admin/contacts creates new contact")
    void addContact_authenticated_returns200() throws Exception {
        EscalationContact saved = contact("new@test.com", "TECH");
        saved.setId(1L);
        when(contactRepo.save(any())).thenReturn(saved);

        mvc.perform(post("/api/admin/contacts")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test User\",\"email\":\"new@test.com\",\"role\":\"TECH\",\"minAlertLevel\":\"WARNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PUT /api/admin/contacts/{id} updates existing contact")
    void updateContact_authenticated_returns200() throws Exception {
        EscalationContact existing = contact("old@test.com", "PO");
        existing.setId(1L);
        when(contactRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(contactRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/contacts/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\",\"email\":\"updated@test.com\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("DELETE /api/admin/contacts/{id} returns 200")
    void deleteContact_authenticated_returns200() throws Exception {
        EscalationContact c = contact("del@test.com", "PO");
        c.setId(1L);
        when(contactRepo.findById(1L)).thenReturn(Optional.of(c));

        mvc.perform(delete("/api/admin/contacts/1").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── Alert Events ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/admin/alerts returns paginated alerts by default")
    void listAlerts_allAlerts_returns200() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    @DisplayName("GET /api/admin/alerts?onlyOpen=true returns only open alerts")
    void listAlerts_onlyOpen_returnsOpenAlerts() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(eq(Boolean.FALSE), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts?onlyOpen=true").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/admin/alerts populates SY/UG team, tier and mail counts")
    void listAlerts_enrichmentPopulatesTransientFields() throws Exception {
        AlertEvent ev = new AlertEvent();
        ev.setId(101L);
        ev.setDomain("foo.example.com");
        ev.setAlertType("EXPIRY");
        ev.setAlertLevel("CRITICAL");
        ev.setCreatedAt("2026-06-01T00:00:00");

        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("foo.example.com");
        inv.setTeamId(7L);
        inv.setUgTeamId(8L);
        inv.setTier(1);

        Team sy = new Team(); sy.setId(7L); sy.setName("SY-Team-A");
        Team ug = new Team(); ug.setId(8L); ug.setName("UG-Team-B");

        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ev)));
        when(inventoryRepo.findByDomainIn(any())).thenReturn(List.of(inv));
        when(teamRepo.findAllById(any())).thenReturn(List.of(sy, ug));
        when(notificationLogRepo.countByAlertIds(any()))
                .thenReturn(List.<Object[]>of(new Object[]{ 101L, 3L, 1L }));

        mvc.perform(get("/api/admin/alerts").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sy_team_name").value("SY-Team-A"))
                .andExpect(jsonPath("$.data[0].ug_team_name").value("UG-Team-B"))
                .andExpect(jsonPath("$.data[0].cert_tier").value(1))
                .andExpect(jsonPath("$.data[0].email_sent_count").value(3))
                .andExpect(jsonPath("$.data[0].email_failed_count").value(1));
    }

    @Test
    @DisplayName("GET /api/admin/alerts leaves enrichment fields null when no inventory match")
    void listAlerts_noInventoryMatch_returnsZeroCounts() throws Exception {
        AlertEvent ev = new AlertEvent();
        ev.setId(202L);
        ev.setDomain("orphan.example.com");
        ev.setAlertType("EXPIRY");
        ev.setAlertLevel("WARNING");

        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ev)));
        when(inventoryRepo.findByDomainIn(any())).thenReturn(Collections.emptyList());
        when(notificationLogRepo.countByAlertIds(any())).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/admin/alerts").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sy_team_name").doesNotExist())
                .andExpect(jsonPath("$.data[0].cert_tier").doesNotExist())
                .andExpect(jsonPath("$.data[0].email_sent_count").value(0))
                .andExpect(jsonPath("$.data[0].email_failed_count").value(0));
    }

    @Test
    @DisplayName("POST /api/admin/users/{id}/auto-reset-password without admin_password returns 400")
    void autoResetPassword_missingAdminPassword_returns400() throws Exception {
        mvc.perform(post("/api/admin/users/7/auto-reset-password")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/admin/users/{id}/auto-reset-password with valid admin_password returns 200 + email_status")
    void autoResetPassword_validAdmin_returns200WithEmailStatus() throws Exception {
        when(userService.adminAutoResetPassword(eq(7L), any(), any())).thenReturn("TempPwd12X");
        AppUser target = new AppUser();
        target.setId(7L);
        target.setUsername("bob");
        target.setEmail("bob@example.com");
        target.setDisplayName("Bob");
        when(userRepo.findById(7L)).thenReturn(Optional.of(target));
        when(emailNotificationService.sendPasswordResetEmail(any(), any(), any(), any()))
                .thenReturn("SENT");

        mvc.perform(post("/api/admin/users/7/auto-reset-password")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admin_password\":\"rightpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.email_status").value("SENT"));
    }

    @Test
    @DisplayName("POST /api/admin/users/{id}/auto-reset-password with wrong admin_password returns 403")
    void autoResetPassword_wrongAdmin_returns403() throws Exception {
        org.mockito.Mockito.doThrow(new SecurityException("Invalid admin password"))
                .when(userService).adminAutoResetPassword(eq(7L), any(), any());

        mvc.perform(post("/api/admin/users/7/auto-reset-password")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admin_password\":\"wrong\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/alerts/{id}/acknowledge returns 200")
    void acknowledgeAlert_authenticated_returns200() throws Exception {
        AlertEvent event = new AlertEvent();
        event.setId(1L);
        event.setDomain("example.com");
        event.setAlertType("EXPIRY");
        event.setAlertLevel("WARNING");
        event.setAcknowledged(true);
        event.setAcknowledgedBy("admin");
        event.setResolved(false);
        when(escalationService.acknowledge(anyLong(), any())).thenReturn(event);

        mvc.perform(post("/api/admin/alerts/1/acknowledge")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledged_by\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/admin/alerts/{id}/resolve returns 200")
    void resolveAlert_authenticated_returns200() throws Exception {
        AlertEvent event = new AlertEvent();
        event.setId(2L);
        event.setDomain("example.com");
        event.setAlertType("EXPIRY");
        event.setAlertLevel("WARNING");
        event.setResolved(true);
        when(escalationService.resolve(eq(2L), any())).thenReturn(event);

        mvc.perform(post("/api/admin/alerts/2/resolve").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/admin/alerts/{id}/notifications returns 200 with log list")
    void getAlertNotifications_authenticated_returns200() throws Exception {
        when(notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(1L))
                .thenReturn(Collections.emptyList());

        mvc.perform(get("/api/admin/alerts/1/notifications").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("POST /api/admin/alerts/{id}/re-notify returns 200")
    void reNotifyAlert_authenticated_returns200() throws Exception {
        AlertEvent evt = new AlertEvent();
        evt.setId(1L);
        Map<String, Object> notifyResult = new java.util.LinkedHashMap<>();
        notifyResult.put("alert", evt);
        notifyResult.put("contacts_attempted", 1);
        notifyResult.put("notifications", Collections.emptyList());
        when(escalationService.reNotify(1L)).thenReturn(notifyResult);

        mvc.perform(post("/api/admin/alerts/1/re-notify").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/admin/users returns 200 with user list")
    void listUsers_authenticated_returns200() throws Exception {
        when(userService.listUsers()).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/admin/users").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("POST /api/admin/users with org_role returns 200")
    void addUser_withOrgRole_returns200() throws Exception {
        AppUser saved = new AppUser();
        saved.setId(5L);
        saved.setUsername("carol");
        saved.setSystemRole("USER");
        saved.setOrgRole("PO");
        saved.setTeamId(1L);
        when(userService.createUser(any(), any(), any(), any(), any(), any(), any(), eq("PO")))
                .thenReturn(saved);

        mvc.perform(post("/api/admin/users")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"pass1234\",\"email\":\"c@test.com\",\"org_role\":\"PO\",\"team_id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PUT /api/admin/users/{id} with org_role returns 200")
    void updateUser_withOrgRole_returns200() throws Exception {
        AppUser updated = new AppUser();
        updated.setId(1L);
        updated.setUsername("alice");
        updated.setSystemRole("USER");
        updated.setOrgRole("MANAGER");
        updated.setTeamId(1L);
        when(userService.updateUser(eq(1L), any(), any(), any(), any(), any(), any(), eq("MANAGER")))
                .thenReturn(updated);

        mvc.perform(put("/api/admin/users/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"Alice\",\"org_role\":\"MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/admin/contacts with user_id populates name/email from user")
    void addContact_withUserId_populatesNameEmailFromUser() throws Exception {
        AppUser linkedUser = new AppUser();
        linkedUser.setId(10L);
        linkedUser.setUsername("dana");
        linkedUser.setDisplayName("Dana Smith");
        linkedUser.setEmail("dana@test.com");
        when(userRepo.findById(10L)).thenReturn(Optional.of(linkedUser));

        EscalationContact saved = new EscalationContact();
        saved.setId(1L);
        saved.setName("Dana Smith");
        saved.setEmail("dana@test.com");
        saved.setRole("TECH");
        saved.setMinAlertLevel("WARNING");
        saved.setActive(true);
        when(contactRepo.save(any())).thenReturn(saved);

        mvc.perform(post("/api/admin/contacts")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":10,\"role\":\"TECH\",\"min_alert_level\":\"WARNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(userRepo).findById(10L);
    }

    @Test
    @DisplayName("POST /api/admin/contacts with unknown user_id still saves contact")
    void addContact_withUnknownUserId_stillSaves() throws Exception {
        when(userRepo.findById(999L)).thenReturn(Optional.empty());

        EscalationContact saved = new EscalationContact();
        saved.setId(2L);
        saved.setRole("PO");
        saved.setMinAlertLevel("HIGH");
        saved.setActive(true);
        when(contactRepo.save(any())).thenReturn(saved);

        mvc.perform(post("/api/admin/contacts")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":999,\"role\":\"PO\",\"min_alert_level\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── USER role gating (alert endpoints + team-scoped listings) ─────────────

    @Test
    @DisplayName("POST /api/admin/alerts/{id}/acknowledge as USER returns 200")
    void acknowledgeAlert_asUser_returns200() throws Exception {
        AlertEvent ev = new AlertEvent();
        ev.setId(1L);
        ev.setDomain("example.com");
        when(escalationService.acknowledge(eq(1L), any())).thenReturn(ev);

        mvc.perform(post("/api/admin/alerts/1/acknowledge").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/admin/teams as USER returns only own team")
    void listTeams_asUser_returnsOnlyOwnTeam() throws Exception {
        Team t1 = new Team(); t1.setId(1L); t1.setName("Alpha");
        Team t2 = new Team(); t2.setId(2L); t2.setName("Beta");
        Team t3 = new Team(); t3.setId(3L); t3.setName("Gamma");
        when(userService.listTeams()).thenReturn(List.of(t1, t2, t3));

        mvc.perform(get("/api/admin/teams").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(2));
    }

    @Test
    @DisplayName("GET /api/admin/users as USER returns only team members")
    void listUsers_asUser_returnsOnlyTeamMembers() throws Exception {
        AppUser u1 = new AppUser(); u1.setUsername("a"); u1.setTeamId(1L);
        AppUser u2 = new AppUser(); u2.setUsername("b"); u2.setTeamId(2L);
        AppUser u3 = new AppUser(); u3.setUsername("c"); u3.setTeamId(2L);
        AppUser u4 = new AppUser(); u4.setUsername("d"); u4.setTeamId(null);
        when(userService.listUsers()).thenReturn(List.of(u1, u2, u3, u4));

        mvc.perform(get("/api/admin/users").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].username").value("b"))
                .andExpect(jsonPath("$.data[1].username").value("c"));
    }

    @Test
    @DisplayName("GET /api/admin/teams as ADMIN returns all teams (regression)")
    void listTeams_asAdmin_returnsAll() throws Exception {
        Team t1 = new Team(); t1.setId(1L); t1.setName("Alpha");
        Team t2 = new Team(); t2.setId(2L); t2.setName("Beta");
        when(userService.listTeams()).thenReturn(List.of(t1, t2));

        mvc.perform(get("/api/admin/teams").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MockHttpSession authSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "testuser");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private MockHttpSession userSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "regularuser");
        s.setAttribute("userId", 42L);
        s.setAttribute("teamId", 2L);
        s.setAttribute("systemRole", "USER");
        return s;
    }

    private CertificateInventory inventory(String domain) {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(domain);
        inv.setPort(443);
        inv.setActive(true);
        return inv;
    }

    private AlertThreshold defaultThreshold() {
        AlertThreshold t = new AlertThreshold();
        t.setId(1L);
        t.setWarningDays(30);
        t.setHighDays(15);
        t.setCriticalDays(7);
        t.setReAlertIntervalHours(24);
        t.setActive(true);
        return t;
    }

    private EscalationContact contact(String email, String role) {
        EscalationContact c = new EscalationContact();
        c.setName("Test " + role);
        c.setEmail(email);
        c.setRole(role);
        c.setMinAlertLevel("WARNING");
        c.setActive(true);
        return c;
    }
}
