package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.LdapMembershipService;
import com.sitemonitor.service.LdapProvisioningService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Üyelik kaynağı / AD karşılaştırma / yeniden eşitleme uçlarının kapıları ve denetimi (2026-09-26).
 * AD'ye giden uçlar YALNIZ global yönetici; kapsamlı müdür (ADMIN + dolu viewTeamIds) geçemez.
 */
@WebMvcTest(LdapMembershipController.class)
class LdapMembershipControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean LdapMembershipService membershipService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;

    // WebConfig / AuthInterceptor / HttpMetricsInterceptor bağımlılıkları.
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean HttpMetricsService httpMetricsService;

    private AppUser target;

    @BeforeEach
    void setUp() {
        target = new AppUser();
        target.setId(3L);
        target.setUsername("KULLANICI_X");
        target.setAuthSource("LDAP");
        target.setTeamId(5L);
        target.setTeamIds(new LinkedHashSet<>(List.of(5L)));
        when(membershipService.requireUser(3L)).thenReturn(target);
        when(membershipService.membership(any())).thenReturn(Map.of("user_id", 3L, "memberships", List.of()));
        when(membershipService.check(any())).thenReturn(Map.of("found", true));
    }

    private static MockHttpSession session(String role, List<Long> viewTeamIds) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "yonetici");
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        return s;
    }

    private static MockHttpSession globalAdmin() { return session("ADMIN", null); }
    private static MockHttpSession scopedAdmin() { return session("ADMIN", List.of(5L)); }

    @Test
    @DisplayName("kimliksiz istek → 401")
    void unauthenticated_401() throws Exception {
        mvc.perform(get("/api/admin/users/3/ldap-check")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET ldap-check: global yönetici → 200; kapsamlı müdür → 403 ve AD'ye gidilmez")
    void check_gate() throws Exception {
        mvc.perform(get("/api/admin/users/3/ldap-check").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.found").value(true));
        org.mockito.Mockito.clearInvocations(membershipService);
        mvc.perform(get("/api/admin/users/3/ldap-check").session(scopedAdmin()))
                .andExpect(status().isForbidden());
        verify(membershipService, never()).check(any());
    }

    @Test
    @DisplayName("POST ldap-resync: global yönetici → uygular + USER_LDAP_RESYNC (önce/sonra); kapsamlı müdür → 403, denetim/yazma yok")
    void resync_gateAndAudit() throws Exception {
        AppUser after = new AppUser();
        after.setId(3L);
        after.setUsername("KULLANICI_X");
        after.setManagerSicil("100004");
        when(membershipService.resync(target)).thenReturn(
                new LdapProvisioningService.SyncResult(after, false, List.of(5L), List.of(), "100004", 9L));

        mvc.perform(post("/api/admin/users/3/ldap-resync").session(scopedAdmin()))
                .andExpect(status().isForbidden());
        verify(membershipService, never()).resync(any());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), any(HttpServletRequest.class),
                anyString(), anyString(), anyString());

        mvc.perform(post("/api/admin/users/3/ldap-resync").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teams_before[0]").value(5))
                .andExpect(jsonPath("$.data.teams_after").isEmpty());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("USER_LDAP_RESYNC"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("USER"), eq("3"), detail.capture());
        assertThat(detail.getValue()).contains("teams_before").contains("[5]").contains("manager_id_before");
    }

    @Test
    @DisplayName("GET team-membership: kapsamlı rol yalnız görüş kapsamındaki takımın üyesini görür (dışı 404)")
    void membership_scope() throws Exception {
        mvc.perform(get("/api/admin/users/3/team-membership").session(session("TEAM_ADMIN", List.of(5L))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/users/3/team-membership").session(session("TEAM_ADMIN", List.of(6L))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/users/3/team-membership").session(globalAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST teams/{id}/ldap-resync: üye başına USER_LDAP_RESYNC + özet TEAM_LDAP_RESYNC; kapsamlı müdür → 403")
    void teamResync_auditsSummaryAndPerUser() throws Exception {
        when(membershipService.resyncTeam(5L)).thenReturn(List.of(
                Map.of("user_id", 3L, "username", "KULLANICI_X", "status", "OK", "teams_before", List.of(5L),
                        "teams_after", List.of(), "still_member", false),
                Map.of("user_id", 4L, "username", "KILITLI", "status", "SKIPPED_LOCKED")));

        mvc.perform(post("/api/admin/teams/5/ldap-resync").session(scopedAdmin()))
                .andExpect(status().isForbidden());
        verify(membershipService, never()).resyncTeam(any());

        mvc.perform(post("/api/admin/teams/5/ldap-resync").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.removed_from_team").value(1));
        verify(auditService).recordAction(eq("USER_LDAP_RESYNC"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("USER"), eq("3"), anyString());
        verify(auditService).recordAction(eq("TEAM_LDAP_RESYNC"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("TEAM"), eq("5"), anyString());
    }

    @Test
    @DisplayName("GET teams/{id}/ldap-check: yalnız global yönetici")
    void teamCheck_gate() throws Exception {
        when(membershipService.checkTeam(5L)).thenReturn(Map.of("team_id", 5L, "members", List.of()));
        mvc.perform(get("/api/admin/teams/5/ldap-check").session(globalAdmin())).andExpect(status().isOk());
        mvc.perform(get("/api/admin/teams/5/ldap-check").session(session("TEAM_ADMIN", List.of(5L))))
                .andExpect(status().isForbidden());
    }
}
