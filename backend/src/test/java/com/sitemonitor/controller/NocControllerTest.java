package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.*;
import com.sitemonitor.service.noc.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 7/24 (NOC) uçlarının YETKİ MATRİSİ — global yönetici, kapsamlı yönetici (AD müdürü: rol ADMIN ama takım
 * kapsamlı), takım yöneticisi, kullanıcı, başka takım. Kapsamlı müdürün global sanılması projede yaşanmış bir
 * kritik bulgu (v20.50.29); yönetim uçları onu açıkça reddetmeli, okumada e-postaları göstermemeli.
 */
@WebMvcTest(controllers = {NocController.class, NocAdminController.class})
class NocControllerTest {

    private static final long TEAM_A = 1L, TEAM_B = 2L;

    @Autowired MockMvc mvc;

    @MockitoBean NocNotificationGroupRepository groupRepo;
    @MockitoBean NocCoverageService coverage;
    @MockitoBean NocMonitorService monitors;
    @MockitoBean NocCallListService callLists;
    @MockitoBean NocConfigService configService;
    @MockitoBean NocGroupService groupService;
    @MockitoBean NocMonitorDirectory directory;
    @MockitoBean NocNotificationService notifications;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean MonitorHistoryService monitorHistory;
    @MockitoBean ActivityLogService activityLog;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private MockHttpSession global, scoped, teamAdmin, userA, userB, audit;

    private static MockHttpSession session(String user, long userId, String role, List<Long> view, List<Long> manage, List<Long> member) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", user);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", role);
        if (!member.isEmpty()) s.setAttribute("teamId", member.get(0));
        if (view != null) s.setAttribute("viewTeamIds", view);
        if (manage != null) s.setAttribute("manageTeamIds", manage);
        s.setAttribute("memberTeamIds", member);
        return s;
    }

    @BeforeEach
    void setUp() {
        global = session("admin", 100, "ADMIN", null, null, List.of());
        scoped = session("mudur", 101, "ADMIN", List.of(TEAM_A), List.of(TEAM_A), List.of(TEAM_A));
        teamAdmin = session("po", 102, "TEAM_ADMIN", List.of(TEAM_A), List.of(TEAM_A), List.of(TEAM_A));
        userA = session("kisia", 103, "USER", List.of(TEAM_A), List.of(), List.of(TEAM_A));
        userB = session("kisib", 104, "USER", List.of(TEAM_B), List.of(), List.of(TEAM_B));
        audit = session("denetci", 105, "AUDIT", null, List.of(), List.of());

        Team a = new Team(); a.setId(TEAM_A); a.setName("Takım A");
        Team b = new Team(); b.setId(TEAM_B); b.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, b));
        when(teamRepo.findById(TEAM_A)).thenReturn(Optional.of(a));
        when(teamRepo.findById(TEAM_B)).thenReturn(Optional.of(b));
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), anyString(), anyString())).thenReturn(true);

        when(configService.get()).thenReturn(new NocConfigService.Config(Set.of(), "CRITICAL", true, "", null, null));
        when(configService.save(any(), any(), any())).thenReturn(new NocConfigService.Config(Set.of(), "HIGH", true, "", null, null));
        NocNotificationGroup g = new NocNotificationGroup();
        g.setId(5L); g.setName("NOC Ana"); g.setEmails("noc@example.com"); g.setActive(true); g.setIsDefault(true);
        when(groupService.list()).thenReturn(List.of(g));
        when(groupRepo.findById(5L)).thenReturn(Optional.of(g));
        when(groupRepo.findAllByOrderByNameAsc()).thenReturn(List.of(g));
        when(groupService.validate(any())).thenReturn(new NocGroupService.GroupInput("NOC Ana", null, List.of("noc@example.com"), true, true));
        when(groupService.create(any(), any(), any())).thenReturn(g);
        when(groupService.update(any(), any(), any(), any())).thenReturn(g);
        when(groupService.deleteAndDetach(any())).thenReturn(3);
        when(notifications.sendTest(any())).thenReturn(new NocNotificationService.TestResult(1, List.of()));
        when(directory.all()).thenReturn(List.of());
        when(coverage.compute(any(), any(), any(), any(), any())).thenReturn(Map.of("summary", Map.of(), "items", List.of()));
        when(coverage.item(any(), anyBoolean(), any())).thenReturn(Map.of("type", "PING", "id", 1));
    }

    // ── Yönetim: yapılandırma ────────────────────────────────────────────────

    @Test
    @DisplayName("GET config: global/kapsamlı yönetici/denetçi okur; takım yöneticisi ve kullanıcı 403")
    void configRead() throws Exception {
        for (MockHttpSession s : List.of(global, scoped, audit))
            mvc.perform(get("/api/admin/noc/config").session(s)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.min_level").value("CRITICAL"))
                    .andExpect(jsonPath("$.data.enabled_types.PING").value(true));
        for (MockHttpSession s : List.of(teamAdmin, userA))
            mvc.perform(get("/api/admin/noc/config").session(s)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT config: YALNIZ global yönetici — kapsamlı müdür (rol ADMIN) 403")
    void configWrite() throws Exception {
        String body = "{\"minLevel\":\"HIGH\"}";
        mvc.perform(put("/api/admin/noc/config").session(global).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.min_level").value("HIGH"));
        verify(auditService).recordAction(eq("NOC_CONFIG_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("NOC"), eq("config"), any(), any());
        for (MockHttpSession s : List.of(scoped, teamAdmin, userA, audit))
            mvc.perform(put("/api/admin/noc/config").session(s).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        verify(configService, times(1)).save(any(), any(), any());
    }

    // ── Yönetim: gruplar ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET groups: global e-postaları görür; kapsamlı müdür/denetçi GÖRMEZ (maskeli); kullanıcı 403")
    void groupsRead() throws Exception {
        mvc.perform(get("/api/admin/noc/groups").session(global)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].emails[0]").value("noc@example.com"))
                .andExpect(jsonPath("$.data[0].emails_hidden").value(false));
        for (MockHttpSession s : List.of(scoped, audit))
            mvc.perform(get("/api/admin/noc/groups").session(s)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].emails").isEmpty())
                    .andExpect(jsonPath("$.data[0].email_count").value(1))
                    .andExpect(jsonPath("$.data[0].emails_hidden").value(true));
        mvc.perform(get("/api/admin/noc/groups").session(userA)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/noc/groups").session(teamAdmin)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST/PUT/DELETE/test grup: yalnız global yönetici; silme affected_monitors döner")
    void groupsWrite() throws Exception {
        String body = "{\"name\":\"NOC Ana\",\"emails\":[\"noc@example.com\"],\"isDefault\":true}";
        mvc.perform(post("/api/admin/noc/groups").session(global).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("NOC Ana"));
        mvc.perform(put("/api/admin/noc/groups/5").session(global).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/admin/noc/groups/5").session(global)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected_monitors").value(3));
        mvc.perform(post("/api/admin/noc/groups/5/test").session(global)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(1)).andExpect(jsonPath("$.data.failed").isEmpty());
        mvc.perform(delete("/api/admin/noc/groups/999").session(global)).andExpect(status().isNotFound());
        for (MockHttpSession s : List.of(scoped, teamAdmin, userA)) {
            mvc.perform(post("/api/admin/noc/groups").session(s).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/admin/noc/groups/5").session(s)).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/noc/groups/5/test").session(s)).andExpect(status().isForbidden());
        }
        verify(groupService, times(1)).deleteAndDetach(any());
        verify(notifications, times(1)).sendTest(any());
    }

    // ── Herkes: seçenekler + kapsam ──────────────────────────────────────────

    @Test
    @DisplayName("grup seçenekleri kullanıcıya açık, e-posta TAŞIMAZ; yöneticinin kapattığı türleri de söyler")
    void optionsHaveNoEmails() throws Exception {
        when(configService.get()).thenReturn(new NocConfigService.Config(Set.of(NocType.PING), "CRITICAL", true, "", null, null));
        mvc.perform(get("/api/noc/groups/options").session(userB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].name").value("NOC Ana"))
                .andExpect(jsonPath("$.data.groups[0].is_default").value(true))
                .andExpect(jsonPath("$.data.groups[0].emails").doesNotExist())
                .andExpect(jsonPath("$.data.disabled_types[0]").value("PING"));
    }

    @Test
    @DisplayName("kapsam: görüş dışı takım süzgeci 403; bilinmeyen tür 400; kendi kapsamı 200")
    void coverageScope() throws Exception {
        mvc.perform(get("/api/noc/coverage").param("team_id", String.valueOf(TEAM_B)).session(userA)).andExpect(status().isForbidden());
        mvc.perform(get("/api/noc/coverage").param("type", "FTP").session(userA)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/noc/coverage").param("team_id", String.valueOf(TEAM_A)).param("type", "ping").session(userA))
                .andExpect(status().isOk());
        verify(coverage).compute(eq(TEAM_A), eq(NocType.PING), any(), any(), any());
    }

    @Test
    @DisplayName("kapsam görünürlük yüklemi: kendi takımı + envanter kökenlide UG takımı; başka takım görünmez")
    void visibilityPredicate() {
        var own = new NocMonitorDirectory.Row(NocType.PING, 1, "a", "a", TEAM_A, null, true, true, null, false);
        var other = new NocMonitorDirectory.Row(NocType.PING, 2, "b", "b", TEAM_B, null, true, true, null, false);
        var derivedUg = new NocMonitorDirectory.Row(NocType.PORT, 3, "c", "c:443", TEAM_B, TEAM_A, true, true, null, true);
        org.assertj.core.api.Assertions.assertThat(NocController.visible(userA, own)).isTrue();
        org.assertj.core.api.Assertions.assertThat(NocController.visible(userA, other)).isFalse();
        org.assertj.core.api.Assertions.assertThat(NocController.visible(userA, derivedUg)).isTrue();
        org.assertj.core.api.Assertions.assertThat(NocController.visible(global, other)).isTrue();
    }

    // ── Aç/kapa ──────────────────────────────────────────────────────────────

    private void ping(long id, long team) {
        PingMonitor m = new PingMonitor();
        m.setId(id); m.setTeamId(team); m.setName("p" + id); m.setHost("h" + id);
        when(monitors.load(NocType.PING, id)).thenReturn(m);
        when(monitors.row(NocType.PING, id)).thenReturn(
                new NocMonitorDirectory.Row(NocType.PING, id, "p" + id, "h" + id, team, null, true, false, null, false));
    }

    @Test
    @DisplayName("aç/kapa: izlemenin KENDİ güncelleme kapısı — kendi takımının kullanıcısı 200, başka takım 403, matris izni yoksa 403")
    void toggleUsesMonitorUpdateGate() throws Exception {
        ping(1, TEAM_A);
        String on = "{\"enabled\":true}";
        mvc.perform(put("/api/noc/monitors/PING/1").session(userA).contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isOk());
        mvc.perform(put("/api/noc/monitors/ping/1").session(teamAdmin).contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isOk());
        mvc.perform(put("/api/noc/monitors/PING/1").session(userB).contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isForbidden());
        doThrow(new SecurityException("izin yok")).when(permissionService)
                .require(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.crud"), eq("edit"));
        mvc.perform(put("/api/noc/monitors/PING/1").session(userA).contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("aç/kapa: gövde doğrulaması (enabled zorunlu), bilinmeyen tür 400, izleme yok 404")
    void toggleValidation() throws Exception {
        mvc.perform(put("/api/noc/monitors/PING/1").session(global).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/noc/monitors/FTP/1").session(global).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/noc/monitors/PING/77").session(global).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("aç/kapa (envanter kökenli Port): takım ENVANTERDEN gelir — saklı team_id başka takım olsa da yetki envanter takımına göre")
    void toggleDerivedUsesInventoryTeam() throws Exception {
        com.sitemonitor.model.PortMonitor pm = new com.sitemonitor.model.PortMonitor();
        pm.setId(9L); pm.setTeamId(TEAM_B); pm.setHost("h9"); pm.setPort(443);   // bayat kopya
        when(monitors.load(NocType.PORT, 9L)).thenReturn((com.sitemonitor.model.NocTarget) (Object) pm);
        when(monitors.row(NocType.PORT, 9L)).thenReturn(
                new NocMonitorDirectory.Row(NocType.PORT, 9, "h9", "h9:443", TEAM_A, null, true, false, null, true));
        String on = "{\"enabled\":true}";
        mvc.perform(put("/api/noc/monitors/PORT/9").session(userA).contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isOk());
        mvc.perform(put("/api/noc/monitors/PORT/9").session(userB).contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("aç/kapa (SSL/envanter): envanter yazma kapısı (canWriteInventory) + inventory.crud")
    void toggleInventory() throws Exception {
        CertificateInventory inv = new CertificateInventory();
        inv.setId(3L); inv.setDomain("www.example.com"); inv.setTeamId(TEAM_A);
        when(monitors.load(NocType.SSL, 3L)).thenReturn(inv);
        when(monitors.row(NocType.SSL, 3L)).thenReturn(
                new NocMonitorDirectory.Row(NocType.SSL, 3, "www.example.com", "www.example.com", TEAM_A, null, true, false, null, false));
        String on = "{\"enabled\":true,\"groupIds\":[5]}";
        mvc.perform(put("/api/noc/monitors/SSL/3").session(userA).contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isOk());
        mvc.perform(put("/api/noc/monitors/SSL/3").session(userB).contentType(MediaType.APPLICATION_JSON).content(on))
                .andExpect(status().isForbidden());
        verify(permissionService, atLeastOnce()).require(any(jakarta.servlet.http.HttpSession.class), eq("inventory.crud"), eq("edit"));
    }

    @Test
    @DisplayName("toplu: yetkisiz/yok/geçersiz satırlar nedeniyle atlanır, kalanlar güncellenir; tek denetim kaydı")
    void bulk() throws Exception {
        ping(1, TEAM_A);
        ping(2, TEAM_B);
        String body = "{\"enabled\":true,\"items\":[{\"type\":\"PING\",\"id\":1},{\"type\":\"PING\",\"id\":2},"
                + "{\"type\":\"PING\",\"id\":404},{\"type\":\"FTP\",\"id\":1}]}";
        mvc.perform(post("/api/noc/monitors/bulk").session(userA).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skipped[?(@.id == 2)].reason").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.skipped[?(@.id == 404)].reason").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data.skipped[?(@.type == 'FTP')].reason").value("INVALID"));
        verify(auditService).recordAction(eq("NOC_MONITOR_BULK"), any(jakarta.servlet.http.HttpSession.class),
                eq("NOC"), eq("bulk"), contains("\"updated\":1"), isNull());
    }

    // ── Arama listesi ────────────────────────────────────────────────────────

    @Test
    @DisplayName("arama listesi OKUMA: takım görüş kapsamında; başka takım 403; bilinmeyen takım 404")
    void callListRead() throws Exception {
        when(callLists.callListDto(TEAM_A)).thenReturn(List.of(Map.of("user_id", 7, "display_name", "Kişi A", "has_phone", true)));
        mvc.perform(get("/api/noc/teams/1/call-list").session(userA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].has_phone").value(true))
                .andExpect(jsonPath("$.data[0].phone").doesNotExist());
        mvc.perform(get("/api/noc/teams/1/call-list").session(userB)).andExpect(status().isForbidden());
        mvc.perform(get("/api/noc/teams/1/members").session(userB)).andExpect(status().isForbidden());
        mvc.perform(get("/api/noc/teams/99/call-list").session(global)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("arama listesi YAZMA: takım yöneticisi/kapsamlı müdür/global/Takım Müdürü; kullanıcı ve başka takım 403")
    void callListWrite() throws Exception {
        String body = "{\"userIds\":[7,8]}";
        for (MockHttpSession s : List.of(teamAdmin, scoped, global))
            mvc.perform(put("/api/noc/teams/1/call-list").session(s).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        mvc.perform(put("/api/noc/teams/1/call-list").session(userA).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/noc/teams/1/call-list").session(userB).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // Takıma elle atanmış müdür (Team.managerId) — yönetim kapsamı olmasa da yazar.
        when(callLists.isTeamManagerOrLeader(TEAM_A, 103L)).thenReturn(true);
        mvc.perform(put("/api/noc/teams/1/call-list").session(userA).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(put("/api/noc/teams/1/call-list").session(teamAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[\"x\"]}"))
                .andExpect(status().isBadRequest());
        verify(callLists, times(4)).replace(eq(TEAM_A), eq(List.of(7L, 8L)), anyString());
        verify(auditService, times(4)).recordAction(eq("NOC_CALL_LIST_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("TEAM"), eq("1"), any(), anyString());
    }

    // ── Yayın öncesi inceleme (2026-09-27) ──────────────────────────────────

    @Test
    @DisplayName("grup yazan kişinin görünen adı oturumdaki displayName'den (AuthController bunu yazar)")
    void actorNameFromDisplayName() throws Exception {
        global.setAttribute("displayName", "Kişi Y");
        mvc.perform(post("/api/admin/noc/groups").session(global).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NOC Ana\",\"emails\":[\"noc@example.com\"]}"))
                .andExpect(status().isOk());
        verify(groupService).create(any(), eq("admin"), eq("Kişi Y"));
    }

    @Test
    @DisplayName("SENTETİK izlemede aç/kapa, izlemenin KENDİ güncelleme izni monitoring.scripted/edit ister")
    void scriptedToggleUsesScriptedPermission() throws Exception {
        com.sitemonitor.model.ScriptedMonitor sm = new com.sitemonitor.model.ScriptedMonitor();
        sm.setId(4L); sm.setTeamId(TEAM_A); sm.setName("Senaryo");
        when(monitors.load(NocType.SCRIPTED, 4L)).thenReturn(sm);
        when(monitors.row(NocType.SCRIPTED, 4L)).thenReturn(
                new NocMonitorDirectory.Row(NocType.SCRIPTED, 4, "Senaryo", "Senaryo", TEAM_A, null, true, false, null, false));
        doThrow(new SecurityException("izin yok")).when(permissionService)
                .require(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.scripted"), eq("edit"));
        mvc.perform(put("/api/noc/monitors/SCRIPTED/4").session(userA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        verify(permissionService, never()).require(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.crud"), eq("edit"));
    }

    @Test
    @DisplayName("görüş kapsamı DIŞINDAKİ takımın elle atanmış müdürü arama listesini okur (yazabildiği listeyi); başka kullanıcı 403")
    void managerOutsideViewScopeReadsCallList() throws Exception {
        MockHttpSession outsideManager = session("mudurx", 200, "USER", List.of(TEAM_B), List.of(), List.of(TEAM_B));
        when(callLists.isTeamManagerOrLeader(TEAM_A, 200L)).thenReturn(true);
        when(callLists.callListDto(TEAM_A)).thenReturn(List.of(Map.of("user_id", 7, "display_name", "Kişi A", "has_phone", true)));
        when(callLists.membersDto(TEAM_A)).thenReturn(List.of(Map.of("user_id", 7, "display_name", "Kişi A", "has_phone", true)));
        mvc.perform(get("/api/noc/teams/1/call-list").session(outsideManager)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].has_phone").value(true))
                .andExpect(jsonPath("$.data[0].phone").doesNotExist());
        mvc.perform(get("/api/noc/teams/1/members").session(outsideManager)).andExpect(status().isOk());
        mvc.perform(put("/api/noc/teams/1/call-list").session(outsideManager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[7]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/noc/teams/1/call-list").session(userB)).andExpect(status().isForbidden());
        mvc.perform(get("/api/noc/teams/1/members").session(userB)).andExpect(status().isForbidden());
    }
}
