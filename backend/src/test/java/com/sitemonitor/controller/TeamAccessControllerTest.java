package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TeamAccessGrant;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamAccessGrantRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.access.TeamAccessGrantService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Takım erişim paylaşımı uçları (2026-10-12): liste kapsamı (global → hepsi, takım yöneticisi → yönettikleri, USER → boş),
 * yazma kapısı (izin + paylaşan takımı yönetmek), alan doğrulaması (400 + field), mükerrer 409, güncelleme YALNIZ gönderilen
 * alanı değiştirir, silme + denetim, "benimle paylaşılanlar" yalnız kişinin kendi paylaşımları. Gerçek servis, sahte depo.
 */
@WebMvcTest(TeamAccessController.class)
@Import(TeamAccessGrantService.class)
class TeamAccessControllerTest {

    private static final long A = 1L, B = 2L, C = 3L;

    @Autowired MockMvc mvc;
    @Autowired TeamAccessGrantService grants;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean TeamAccessGrantRepository repo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean AppUserRepository userRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;

    private final List<TeamAccessGrant> rows = new ArrayList<>();

    private static Team team(long id) {
        Team t = new Team();
        t.setId(id);
        t.setName("Takım " + (char) ('A' + id - 1));
        t.setActive(true);
        return t;
    }

    private static AppUser user(long id, String dn) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("u" + id);
        u.setDisplayName(dn);
        u.setActive(true);
        return u;
    }

    private TeamAccessGrant row(long id, long owner, String type, Long gTeam, Long gUser, String level) {
        TeamAccessGrant g = new TeamAccessGrant();
        g.setId(id);
        g.setOwnerTeamId(owner);
        g.setGranteeType(type);
        g.setGranteeTeamId(gTeam);
        g.setGranteeUserId(gUser);
        g.setGranteeKey(TeamAccessGrant.keyOf(type, gTeam, gUser));
        g.setLevel(level);
        g.setNote("ilk not");
        rows.add(g);
        return g;
    }

    @BeforeEach
    void setUp() {
        grants.invalidate();
        when(permissionService.allows(any(HttpSession.class), eq("teams.sharing"), anyString())).thenReturn(true);
        when(repo.findAllByOrderByIdAsc()).thenAnswer(inv -> List.copyOf(rows));
        when(repo.findByOwnerTeamIdInOrderByIdAsc(anyCollection())).thenAnswer(inv -> {
            var owners = (java.util.Collection<?>) inv.getArgument(0);
            return rows.stream().filter(g -> owners.contains(g.getOwnerTeamId())).toList();
        });
        when(repo.findById(any())).thenAnswer(inv -> rows.stream().filter(g -> g.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByOwnerTeamIdAndGranteeKey(any(), anyString())).thenAnswer(inv -> rows.stream()
                .filter(g -> g.getOwnerTeamId().equals(inv.getArgument(0)) && g.getGranteeKey().equals(inv.getArgument(1))).findFirst());
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            TeamAccessGrant g = inv.getArgument(0);
            if (g.getId() == null) { g.setId(900L + rows.size()); rows.add(g); }
            return g;
        });
        when(teamRepo.findById(any())).thenAnswer(inv -> Optional.of(team((Long) inv.getArgument(0))));
        when(teamRepo.findAllById(any())).thenAnswer(inv -> {
            List<Team> out = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) out.add(team((Long) o));
            return out;
        });
        when(teamRepo.findAll()).thenReturn(List.of(team(A), team(B), team(C)));
        when(userRepo.findById(any())).thenAnswer(inv -> Optional.of(user((Long) inv.getArgument(0), "Kişi " + inv.getArgument(0))));
        when(userRepo.findAllById(any())).thenAnswer(inv -> {
            List<AppUser> out = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) out.add(user((Long) o, "Kişi " + o));
            return out;
        });
        when(userRepo.findActiveMemberRowsOfTeams(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{50L, "u50", null, null, null, B, null, "USER"}));
    }

    private static MockHttpSession session(String role, Long team, List<Long> manage, long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "user" + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", role);
        if (team != null) {
            s.setAttribute("teamId", team);
            s.setAttribute("viewTeamIds", List.of(team));
            s.setAttribute("memberTeamIds", List.of(team));
        }
        if (manage != null) s.setAttribute("manageTeamIds", manage);
        return s;
    }

    private static MockHttpSession globalAdmin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("userId", 1L);
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    @Test
    @DisplayName("liste: global hepsi; takım yöneticisi yalnız yönettiği takımın; USER boş; can_edit = yönetim")
    void listScope() throws Exception {
        row(1, A, "TEAM", B, null, "VIEW");
        row(2, C, "USER", null, 50L, "EDIT");
        mvc.perform(get("/api/admin/team-access").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants.length()").value(2))
                .andExpect(jsonPath("$.data.grants[0].can_edit").value(true))
                .andExpect(jsonPath("$.data.manageable_teams.length()").value(3));
        mvc.perform(get("/api/admin/team-access").session(session("TEAM_ADMIN", A, List.of(A), 10L)))
                .andExpect(jsonPath("$.data.grants.length()").value(1))
                .andExpect(jsonPath("$.data.grants[0].owner_team_id").value(A))
                .andExpect(jsonPath("$.data.grants[0].grantee_name").value("Takım B"))
                .andExpect(jsonPath("$.data.grants[0].can_edit").value(true))
                .andExpect(jsonPath("$.data.manageable_teams.length()").value(1));
        mvc.perform(get("/api/admin/team-access").session(session("USER", A, List.of(), 11L)))
                .andExpect(jsonPath("$.data.grants.length()").value(0))
                .andExpect(jsonPath("$.data.manageable_teams.length()").value(0));
    }

    @Test
    @DisplayName("satır: owner_active / grantee_active her satırda dolu — pasif paylaşan takım ya da pasif alıcı takım false")
    void activeFlags() throws Exception {
        row(1, A, "TEAM", B, null, "VIEW");
        row(2, C, "USER", null, 50L, "EDIT");
        org.mockito.Mockito.doAnswer(inv -> {
            List<Team> out = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) {
                Team t = team((Long) o);
                if (t.getId() == C || t.getId() == B) t.setActive(false);
                out.add(t);
            }
            return out;
        }).when(teamRepo).findAllById(any());
        mvc.perform(get("/api/admin/team-access").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants[0].owner_active").value(true))
                .andExpect(jsonPath("$.data.grants[0].grantee_active").value(false))   // alıcı takım B pasif
                .andExpect(jsonPath("$.data.grants[1].owner_active").value(false))     // paylaşan takım C pasif
                .andExpect(jsonPath("$.data.grants[1].grantee_active").value(true));
    }

    @Test
    @DisplayName("izin yoksa 403")
    void needsPermission() throws Exception {
        doThrow(new SecurityException("izin yok")).when(permissionService).require(any(HttpSession.class), eq("teams.sharing"), eq("view"));
        mvc.perform(get("/api/admin/team-access").session(globalAdmin())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ekleme: paylaşan takımı yönetmeyen 403 (kayıt yok); aynı takım 400 + field; başarı + denetim; mükerrer 409")
    void create() throws Exception {
        mvc.perform(post("/api/admin/team-access").session(session("TEAM_ADMIN", A, List.of(A), 10L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_team_id\":3,\"grantee_type\":\"TEAM\",\"grantee_team_id\":2,\"level\":\"VIEW\"}"))
                .andExpect(status().isForbidden());
        verify(repo, never()).saveAndFlush(any());
        mvc.perform(post("/api/admin/team-access").session(session("TEAM_ADMIN", A, List.of(A), 10L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_team_id\":1,\"grantee_type\":\"TEAM\",\"grantee_team_id\":1,\"level\":\"VIEW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("grantee_team_id"));
        mvc.perform(post("/api/admin/team-access").session(session("TEAM_ADMIN", A, List.of(A), 10L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_team_id\":1,\"grantee_type\":\"USER\",\"grantee_user_id\":50,\"level\":\"EXECUTE\",\"note\":\"7/24 desteği\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.level").value("EXECUTE"))
                .andExpect(jsonPath("$.data.grantee_name").value("Kişi 50"))
                .andExpect(jsonPath("$.data.note").value("7/24 desteği"));
        verify(auditService).recordAction(eq("TEAM_ACCESS_GRANT_CREATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("1"),
                containsStringArg("\"grantee_user_id\":50"));
        mvc.perform(post("/api/admin/team-access").session(session("TEAM_ADMIN", A, List.of(A), 10L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_team_id\":1,\"grantee_type\":\"USER\",\"grantee_user_id\":50,\"level\":\"VIEW\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GRANT_EXISTS"));
    }

    private static String containsStringArg(String part) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.contains(part));
    }

    @Test
    @DisplayName("güncelleme: YALNIZ gönderilen alan değişir (not korunur); yönetmeyen 403; boş tarih süresiz yapar")
    void updateOnlySentFields() throws Exception {
        TeamAccessGrant g = row(5, A, "TEAM", B, null, "VIEW");
        g.setExpiresAt("2027-01-01T21:00:00");
        // Listede göremeyeceği satır 404 (kimlik taranamaz); listede gören ama yönetemeyen (AUDIT) 403
        mvc.perform(put("/api/admin/team-access/5").session(session("USER", A, List.of(), 11L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"EDIT\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/team-access/5").session(session("AUDIT", null, null, 12L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"EDIT\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/team-access/5").session(session("TEAM_ADMIN", C, List.of(C), 13L)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/team-access/5").session(session("TEAM_ADMIN", A, List.of(A), 10L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"EDIT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.level").value("EDIT"))
                .andExpect(jsonPath("$.data.note").value("ilk not"))
                .andExpect(jsonPath("$.data.expires_on").value("2027-01-01"));
        mvc.perform(put("/api/admin/team-access/5").session(session("TEAM_ADMIN", A, List.of(A), 10L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expires_on\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expires_on").doesNotExist())
                .andExpect(jsonPath("$.data.level").value("EDIT"));
        assertThat(g.getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("silme: denetim + paylaşım listeden düşer; olmayan kayıt 404")
    void deleteGrant() throws Exception {
        row(7, A, "TEAM", B, null, "VIEW");
        org.mockito.Mockito.doAnswer(inv -> { rows.remove((TeamAccessGrant) inv.getArgument(0)); return null; }).when(repo).delete(any());
        mvc.perform(delete("/api/admin/team-access/7").session(session("TEAM_ADMIN", A, List.of(A), 10L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
        verify(auditService).recordAction(eq("TEAM_ACCESS_GRANT_DELETE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("1"), anyString());
        assertThat(rows).isEmpty();
        mvc.perform(delete("/api/admin/team-access/77").session(session("TEAM_ADMIN", A, List.of(A), 10L)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("benimle paylaşılanlar: kişiye ve üyesi olduğu takıma verilenler; başkasınınki görünmez")
    void sharedWithMe() throws Exception {
        row(1, A, "TEAM", B, null, "VIEW");      // u50 B üyesi → görür
        row(2, C, "USER", null, 50L, "EDIT");    // u50'ye doğrudan
        row(3, A, "USER", null, 60L, "DELETE");  // başkası
        MockHttpSession s = session("USER", B, List.of(), 50L);
        mvc.perform(get("/api/me/shared-with-me").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants.length()").value(2))
                .andExpect(jsonPath("$.data.grants[?(@.owner_team_id==1)].via").value("TEAM"))
                .andExpect(jsonPath("$.data.grants[?(@.owner_team_id==3)].level").value("EDIT"));
        MockHttpSession nobody = session("USER", C, List.of(), 70L);
        mvc.perform(get("/api/me/shared-with-me").session(nobody)).andExpect(jsonPath("$.data.grants.length()").value(0));
    }
}
