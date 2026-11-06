package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.AlertForward;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TeamAccessGrant;
import com.sitemonitor.repository.AlertForwardRepository;
import com.sitemonitor.repository.TeamAccessGrantRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionCatalog;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.access.TeamAccessGrantService;
import com.sitemonitor.service.access.TeamAccessLevel;
import com.sitemonitor.service.forward.AlertForwardInfoNotifier;
import com.sitemonitor.service.forward.AlertForwardService;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.service.userref.UserPublicIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
 * Alarm iletimi uçları (2026-11-05). Değişmezler: liste yalnız sahip takımı KENDİ kapsamında görülen iletimleri döndürür
 * (erişim paylaşımı / 7/24 açmaz); alıcı taraf "Bize iletilenler"de salt okunur görür; yazma = izin + sahip takımı yönetmek;
 * görünmeyen iletim 404, görünen ama yönetilemeyen 403; kullanıcı kimlikleri opak; değişmeyen PUT denetim yazmaz; görüntüleme
 * erişimi yetkisi yoksa 403 + alan; adres listesi dönmez.
 */
@WebMvcTest(AlertForwardController.class)
class AlertForwardControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AlertForwardService forwards;
    @MockitoBean AlertForwardRepository repo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean AlertForwardInfoNotifier notifier;
    @MockitoBean TeamAccessGrantService grants;
    @MockitoBean TeamAccessGrantRepository grantRepo;
    @MockitoBean NocMonitorDirectory directory;
    @MockitoBean UserPublicIds userPublicIds;
    @MockitoBean CertificateService certificateService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    /** İzin süzgeci: rol varsayılanları ({@link PermissionCatalog}) eksi {@link #denied}. */
    final Set<String> denied = new HashSet<>();
    final List<AlertForward> rows = new ArrayList<>();

    static Team team(long id, String name) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setActive(true);
        return t;
    }

    static AlertForward fwd(long id, long owner, String scope, String types, String monitors, String kind, long rt, String users) {
        AlertForward f = new AlertForward();
        f.setId(id);
        f.setVersion(2L);
        f.setOwnerTeamId(owner);
        f.setScopeKind(scope);
        f.setScopeTypes(types);
        f.setScopeMonitors(monitors);
        f.setRecipientKind(kind);
        f.setRecipientTeamId(rt);
        f.setRecipientUserIds(users);
        f.setEnabled(true);
        f.setCreatedAt("2026-11-05T08:00:00");
        f.setCreatedBy("yonetici");
        return f;
    }

    @BeforeEach
    void setUp() {
        when(permissionService.allows(any(HttpSession.class), anyString(), anyString())).thenAnswer(i -> allowed(i.getArgument(0),
                i.getArgument(1), i.getArgument(2)));
        doAnswer(i -> {
            if (!allowed(i.getArgument(0), i.getArgument(1), i.getArgument(2))) throw new SecurityException("Bu işlem için yetkiniz yok");
            return null;
        }).when(permissionService).require(any(HttpSession.class), anyString(), anyString());
        when(repo.findAllByOrderByIdAsc()).thenAnswer(i -> new ArrayList<>(rows));
        when(repo.findById(anyLong())).thenAnswer(i -> rows.stream().filter(f -> f.getId().equals(i.getArgument(0))).findFirst());
        List<Team> teams = List.of(team(1, "Sipariş Sistemleri (Dijital Kanallar Uygulama Geliştirme)"),
                team(2, "Ağ Operasyon"), team(3, "Güvenlik"), team(4, "Ödeme"));
        when(teamRepo.findAllById(any())).thenAnswer(i -> {
            Set<Object> ids = new HashSet<>();
            for (Object o : (Iterable<?>) i.getArgument(0)) ids.add(o);
            return teams.stream().filter(t -> ids.contains(t.getId())).toList();
        });
        when(teamRepo.findAll()).thenReturn(List.of(team(1, "Sipariş"), team(2, "Ağ"), team(3, "Güvenlik"), team(4, "Ödeme")));
        when(forwards.addressCount(anyLong())).thenReturn(2);
        when(forwards.members(any())).thenReturn(Map.of(
                50L, new AlertForwardService.Member(50L, "ayse@example.com", true, Set.of(2L), "Ayşe Yılmaz (Ağ / Uzman)", "ayse"),
                51L, new AlertForwardService.Member(51L, "pasif@example.com", false, Set.of(2L), "Pasif Kişi", "pasif")));
        when(userPublicIds.publicIdOf(anyLong())).thenAnswer(i -> "uuid-" + i.getArgument(0));
        when(notifier.notifyAdded(any(), anyBoolean(), any(), any())).thenReturn(new AlertForwardInfoNotifier.Info(3, 2));
    }

    boolean allowed(HttpSession s, String resource, String action) {
        if (denied.contains(resource + ":" + action)) return false;
        String role = s == null ? null : (String) s.getAttribute("systemRole");
        if (role == null) return false;
        Map<String, Boolean> m = PermissionCatalog.defaultsFor(role).getOrDefault(resource, Map.of());
        return Boolean.TRUE.equals(m.get(action));
    }

    static MockHttpSession session(String role, Long userId, List<Long> view, List<Long> manage, List<Long> member) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u" + userId);
        s.setAttribute("displayName", "Kişi " + userId);
        s.setAttribute("systemRole", role);
        s.setAttribute("userId", userId);
        if (view != null) s.setAttribute("viewTeamIds", view);
        if (manage != null) s.setAttribute("manageTeamIds", manage);
        if (member != null) s.setAttribute("memberTeamIds", member);
        return s;
    }

    static MockHttpSession admin() { return session("ADMIN", 1L, null, null, null); }
    static MockHttpSession teamAdmin1() { return session("TEAM_ADMIN", 10L, List.of(1L), List.of(1L), List.of(1L)); }
    static MockHttpSession user2() { return session("USER", 50L, List.of(2L), List.of(), List.of(2L)); }
    static MockHttpSession audit() { return session("AUDIT", 70L, null, null, null); }

    @Test
    @DisplayName("liste izni yoksa 403")
    void listNeedsPermission() throws Exception {
        mvc.perform(get("/api/alert-forwards").session(session("NOBODY", 9L, List.of(1L), List.of(1L), List.of(1L))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("kapsamlı yönetici: kendi takımının iletimleri düzenlenebilir; takımına gelen iletim 'Bize iletilenler'de salt okunur; başkası yok")
    void teamAdminScope() throws Exception {
        rows.add(fwd(1, 1, "ALL", null, null, "TEAM", 2, null));
        rows.add(fwd(2, 3, "TYPES", "http,cert", null, "TEAM", 1, null));   // gelen
        rows.add(fwd(3, 3, "ALL", null, null, "TEAM", 4, null));            // başkasının
        mvc.perform(get("/api/alert-forwards").session(teamAdmin1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].id").value(1))
                .andExpect(jsonPath("$.data.items[0].can_edit").value(true))
                .andExpect(jsonPath("$.data.items[0].state").value("active"))
                .andExpect(jsonPath("$.data.items[0].recipient_address_count").value(2))
                .andExpect(jsonPath("$.data.items[0].owner_team_name").value("Sipariş Sistemleri (Dijital Kanallar Uygulama Geliştirme)"))
                .andExpect(jsonPath("$.data.incoming", hasSize(1)))
                .andExpect(jsonPath("$.data.incoming[0].id").value(2))
                .andExpect(jsonPath("$.data.incoming[0].can_edit").value(false))
                .andExpect(jsonPath("$.data.incoming[0].scope_types", contains("cert", "http")))
                .andExpect(jsonPath("$.data.manageable_teams[*].id", contains(1)))
                .andExpect(jsonPath("$.data.can_grant").value(true))
                .andExpect(jsonPath("$.data.limits.per_team").value(100))
                .andExpect(jsonPath("$.data.items[0].emails").doesNotExist());
    }

    @Test
    @DisplayName("USER salt okunur: kendi takımının iletimi can_edit=false, ekleyemez; kendisine (seçilen üye) gelen listelenir; kimlik opak")
    void userReadOnlyAndOpaque() throws Exception {
        rows.add(fwd(1, 2, "ALL", null, null, "TEAM", 3, null));
        rows.add(fwd(2, 4, "ALL", null, null, "MEMBERS", 3, "50,51"));      // kişi seçilmiş (takım 3'te olmasa da gelir)
        mvc.perform(get("/api/alert-forwards").session(user2()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].can_edit").value(false))
                .andExpect(jsonPath("$.data.manageable_teams", hasSize(0)))
                .andExpect(jsonPath("$.data.can_grant").value(false))
                .andExpect(jsonPath("$.data.incoming[0].id").value(2))
                .andExpect(jsonPath("$.data.incoming[0].recipient_members[*].user_id", contains("uuid-50", "uuid-51")))
                .andExpect(jsonPath("$.data.incoming[0].recipient_members[0].display_name").value("Ayşe Yılmaz (Ağ / Uzman)"))
                .andExpect(jsonPath("$.data.incoming[0].recipient_members[0].in_team").value(false))
                .andExpect(jsonPath("$.data.incoming[0].state").value("no_recipients"));
        // global yönetici sayısal kimlik görür
        mvc.perform(get("/api/alert-forwards").session(admin()))
                .andExpect(jsonPath("$.data.items[1].recipient_members[*].user_id", contains(50, 51)))
                .andExpect(jsonPath("$.data.items", hasSize(2)));
        // USER yazamaz
        mvc.perform(post("/api/alert-forwards").session(user2()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_team_id\":2,\"scope_kind\":\"ALL\",\"recipient_kind\":\"TEAM\",\"recipient_team_id\":3}"))
                .andExpect(status().isForbidden());
        verify(forwards, never()).create(anyMap(), any(), any());
    }

    @Test
    @DisplayName("erişim paylaşımıyla görülen takımın iletimleri görünmez; düzenleme / silme 404 (kimlik taranamaz)")
    void grantOnlyIsInvisible() throws Exception {
        rows.add(fwd(1, 1, "ALL", null, null, "TEAM", 4, null));
        MockHttpSession grantee = session("USER", 60L, List.of(3L), List.of(), List.of(3L));
        grantee.setAttribute(SessionScope.ATTR_GRANT_VIEW, List.of(1L));
        grantee.setAttribute(SessionScope.ATTR_GRANT_EDIT, List.of(1L));
        mvc.perform(get("/api/alert-forwards").session(grantee))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.incoming", hasSize(0)));
        MockHttpSession grantAdmin = session("TEAM_ADMIN", 61L, List.of(3L), List.of(3L), List.of(3L));
        grantAdmin.setAttribute(SessionScope.ATTR_GRANT_EDIT, List.of(1L));
        mvc.perform(put("/api/alert-forwards/1").session(grantAdmin).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/alert-forwards/1").session(grantAdmin)).andExpect(status().isNotFound());
        verify(forwards, never()).update(anyLong(), anyMap(), any(), any());
        verify(forwards, never()).delete(anyLong());
    }

    @Test
    @DisplayName("AUDIT her şeyi görür ama yazamaz (listede can_edit=false; PUT 403)")
    void auditReadOnly() throws Exception {
        rows.add(fwd(1, 1, "ALL", null, null, "TEAM", 4, null));
        mvc.perform(get("/api/alert-forwards").session(audit()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].can_edit").value(false))
                .andExpect(jsonPath("$.data.manageable_teams", hasSize(0)));
        mvc.perform(put("/api/alert-forwards/1").session(audit()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("oluşturma: başka takım 403; sahip yok 400; başarı → denetim (üye SAYISI, ad yok) + bilgi sayıları; opak üye kimliği çözülür")
    void create() throws Exception {
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_team_id\":2,\"scope_kind\":\"ALL\",\"recipient_kind\":\"TEAM\",\"recipient_team_id\":3}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_kind\":\"ALL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("owner_team_id"));

        AlertForward saved = fwd(9, 1, "ALL", null, null, "MEMBERS", 2, "50");
        when(userPublicIds.resolve("uuid-50", false)).thenReturn(50L);
        when(forwards.create(anyMap(), any(), any())).thenReturn(new AlertForwardService.Saved(saved, null, true, false, List.of(50L)));
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_team_id\":1,\"scope_kind\":\"ALL\",\"recipient_kind\":\"MEMBERS\",\"recipient_team_id\":2,"
                                + "\"recipient_user_ids\":[\"uuid-50\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.can_edit").value(true))
                .andExpect(jsonPath("$.grant").value(nullValue()))
                .andExpect(jsonPath("$.info.emails").value(3))
                .andExpect(jsonPath("$.info.push").value(2));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(forwards).create(anyMap(), ids.capture(), eq("u10"));
        assertThat(ids.getValue()).containsExactly(50L);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("ALERT_FORWARD_CREATE"), any(HttpSession.class), any(HttpServletRequest.class), eq("TEAM"), eq("1"), detail.capture());
        assertThat(detail.getValue()).contains("\"member_count\":1").contains("\"recipient_team\":\"Ağ Operasyon\"")
                .doesNotContain("Ayşe").doesNotContain("ayse@");
        verify(notifier).notifyAdded(saved, false, List.of(50L), "Kişi 10");
    }

    @Test
    @DisplayName("servis alan hatası 400 + alan; FORWARD_EXISTS 409 kodlu")
    void serviceErrors() throws Exception {
        when(forwards.create(anyMap(), any(), any()))
                .thenThrow(new GlobalExceptionHandler.FieldValidationException("recipient_team_id", "Takım seçin"))
                .thenThrow(new GlobalExceptionHandler.CodedConflictException("FORWARD_EXISTS", "Zaten var"));
        String body = "{\"owner_team_id\":1,\"scope_kind\":\"ALL\",\"recipient_kind\":\"TEAM\",\"recipient_team_id\":1}";
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("recipient_team_id"));
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FORWARD_EXISTS"));
        verify(auditService, never()).recordAction(eq("ALERT_FORWARD_CREATE"), any(HttpSession.class), any(HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("grant_view: paylaşım izni yoksa 403 + field=grant_view ve iletim KAYDEDİLMEZ")
    void grantViewRefused() throws Exception {
        denied.add("teams.sharing:edit");
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_team_id\":1,\"scope_kind\":\"ALL\",\"recipient_kind\":\"TEAM\",\"recipient_team_id\":2,\"grant_view\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.field").value("grant_view"));
        verify(forwards, never()).create(anyMap(), any(), any());
    }

    @Test
    @DisplayName("grant_view: TEAM → T:<alıcı> VIEW paylaşımı (iletimin bitişiyle, denetimli); var olan paylaşıma dokunulmaz; tavan → failed + ileti")
    void grantViewCreates() throws Exception {
        AlertForward saved = fwd(9, 1, "ALL", null, null, "TEAM", 2, null);
        saved.setExpiresAt("2026-12-31T21:00:00");
        when(forwards.create(anyMap(), any(), any())).thenReturn(new AlertForwardService.Saved(saved, null, true, true, List.of()));
        when(grantRepo.findByOwnerTeamIdAndGranteeKey(1L, "T:2")).thenReturn(Optional.empty());
        TeamAccessGrantService.Input in = new TeamAccessGrantService.Input(1L, "TEAM", 2L, null, TeamAccessLevel.VIEW, "2026-12-31T21:00:00", "n");
        when(grants.validate(1L, "TEAM", 2L, null, "VIEW", "2026-12-31", "Alarm iletimiyle birlikte verildi")).thenReturn(in);
        TeamAccessGrant g = new TeamAccessGrant();
        g.setId(77L); g.setGranteeType("TEAM"); g.setGranteeTeamId(2L); g.setLevel("VIEW");
        when(grants.create(in, "u10")).thenReturn(g);
        String body = "{\"owner_team_id\":1,\"scope_kind\":\"ALL\",\"recipient_kind\":\"TEAM\",\"recipient_team_id\":2,\"grant_view\":true}";
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grant.requested").value(true))
                .andExpect(jsonPath("$.grant.created").value(1))
                .andExpect(jsonPath("$.grant.existing").value(0));
        verify(auditService).recordAction(eq("TEAM_ACCESS_GRANT_CREATE"), any(HttpSession.class), any(HttpServletRequest.class), eq("TEAM"), eq("1"), anyString());

        // zaten paylaşım var → dokunma
        TeamAccessGrant live = new TeamAccessGrant();
        live.setId(5L);
        when(grantRepo.findByOwnerTeamIdAndGranteeKey(1L, "T:2")).thenReturn(Optional.of(live));
        when(grants.expired(live)).thenReturn(false);
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.grant.existing").value(1)).andExpect(jsonPath("$.grant.created").value(0));

        // tavan dolu → iletim yine kaydedildi, failed + ileti
        when(grantRepo.findByOwnerTeamIdAndGranteeKey(1L, "T:2")).thenReturn(Optional.empty());
        when(grants.create(in, "u10")).thenThrow(new GlobalExceptionHandler.CodedConflictException("GRANT_LIMIT", "Tavan doldu"));
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grant.failed").value(1))
                .andExpect(jsonPath("$.grant.message").value("Tavan doldu"))
                .andExpect(jsonPath("$.data.id").value(9));
    }

    @Test
    @DisplayName("düzenleme: değişmeyen gövde denetim / bilgi yazmaz; değişen → ALERT_FORWARD_UPDATE + değişen alanlar")
    void update() throws Exception {
        AlertForward cur = fwd(1, 1, "ALL", null, null, "TEAM", 2, null);
        rows.add(cur);
        when(forwards.update(eq(1L), anyMap(), any(), any())).thenReturn(new AlertForwardService.Saved(cur, cur, false, false, List.of()));
        mvc.perform(put("/api/alert-forwards/1").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(1));
        verify(auditService, never()).recordAction(eq("ALERT_FORWARD_UPDATE"), any(HttpSession.class), any(HttpServletRequest.class), anyString(), anyString(), anyString());
        verify(notifier, never()).notifyAdded(any(), anyBoolean(), any(), any());

        AlertForward after = fwd(1, 1, "TYPES", "http", null, "TEAM", 3, null);
        when(forwards.update(eq(1L), anyMap(), any(), any())).thenReturn(new AlertForwardService.Saved(after, cur, true, true, List.of()));
        mvc.perform(put("/api/alert-forwards/1").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_kind\":\"TYPES\",\"scope_types\":[\"http\"],\"recipient_team_id\":3}"))
                .andExpect(status().isOk());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("ALERT_FORWARD_UPDATE"), any(HttpSession.class), any(HttpServletRequest.class), eq("TEAM"), eq("1"), detail.capture());
        assertThat(detail.getValue()).contains("\"changes\":{").contains("\"scope_kind\":{\"from\":\"ALL\",\"to\":\"TYPES\"}")
                .contains("\"recipient_team_id\":{\"from\":2,\"to\":3}");
        verify(notifier).notifyAdded(after, true, List.of(), "Kişi 10");
    }

    @Test
    @DisplayName("silme: denetimli; yönetemeyen 403")
    void deleteForward() throws Exception {
        rows.add(fwd(1, 1, "ALL", null, null, "TEAM", 2, null));
        mvc.perform(delete("/api/alert-forwards/1").session(user2())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/alert-forwards/1").session(teamAdmin1()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
        verify(forwards).delete(1L);
        verify(auditService).recordAction(eq("ALERT_FORWARD_DELETE"), any(HttpSession.class), any(HttpServletRequest.class), eq("TEAM"), eq("1"), anyString());
    }

    @Test
    @DisplayName("izleme için: uygulanan iletimler (ALL, TYPES ∋ tür, MONITORS ∋ izleme); sahip takımı görmeyen hidden; izleme yoksa 404")
    void forMonitor() throws Exception {
        NocMonitorDirectory.Row row = new NocMonitorDirectory.Row(NocType.HTTP, 5, "Vitrin", "https://shop", 1L, null, true, false, null, false);
        when(directory.find(NocType.HTTP, 5L)).thenReturn(row);
        when(repo.findByOwnerTeamIdInOrderByIdAsc(any())).thenReturn(List.of(
                fwd(1, 1, "ALL", null, null, "TEAM", 2, null),
                fwd(2, 1, "TYPES", "ping", null, "TEAM", 2, null),
                fwd(3, 1, "TYPES", "http", null, "TEAM", 3, null),
                fwd(4, 1, "MONITORS", null, "http:5,http:6", "TEAM", 4, null),
                fwd(5, 1, "MONITORS", null, "http:6", "TEAM", 4, null)));
        mvc.perform(get("/api/alert-forwards/for-monitor").param("type", "http").param("id", "5").session(teamAdmin1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(1, 3, 4)))
                .andExpect(jsonPath("$.data.can_add").value(true))
                .andExpect(jsonPath("$.data.monitor.name").value("Vitrin"))
                .andExpect(jsonPath("$.data.owner_teams[0].id").value(1));
        mvc.perform(get("/api/alert-forwards/for-monitor").param("type", "http").param("id", "5").session(user2()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hidden").value(true));
        mvc.perform(get("/api/alert-forwards/for-monitor").param("type", "http").param("id", "99").session(teamAdmin1()))
                .andExpect(status().isNotFound());
        // cert: alan adıyla; UG sahibi de sahiptir
        NocMonitorDirectory.Row cert = new NocMonitorDirectory.Row(NocType.SSL, 40, "shop.example.com", "shop.example.com", 3L, 2L, true, false, null, false);
        when(directory.forAlert(NocType.SSL, "shop.example.com", null)).thenReturn(cert);
        when(repo.findByOwnerTeamIdInOrderByIdAsc(any())).thenReturn(List.of(fwd(7, 2, "MONITORS", null, "cert:40", "TEAM", 4, null)));
        mvc.perform(get("/api/alert-forwards/for-monitor").param("type", "cert").param("domain", "shop.example.com").session(user2()))
                .andExpect(jsonPath("$.data.items[0].id").value(7))
                .andExpect(jsonPath("$.data.can_add").value(false));
    }

    @Test
    @DisplayName("izleme seçicisi: takım kendi kapsamda olmalı; SY ya da UG takımın izlemeleri, tür sırası sonra ad")
    void monitors() throws Exception {
        when(directory.all()).thenReturn(List.of(
                new NocMonitorDirectory.Row(NocType.PING, 2, "Zeta", "10.0.0.1", 1L, null, true, false, null, false),
                new NocMonitorDirectory.Row(NocType.HTTP, 3, "Beta", "https://b", 1L, null, true, false, null, false),
                new NocMonitorDirectory.Row(NocType.SSL, 4, "a.example.com", "a.example.com", 9L, 1L, true, false, null, false),
                new NocMonitorDirectory.Row(NocType.HTTP, 5, "Başka", "https://x", 2L, null, true, false, null, false)));
        mvc.perform(get("/api/alert-forwards/monitors").param("team", "1").session(teamAdmin1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].type", contains("cert", "http", "ping")))
                .andExpect(jsonPath("$.data.items[*].id", contains(4, 3, 2)));
        mvc.perform(get("/api/alert-forwards/monitors").param("team", "2").session(teamAdmin1()))
                .andExpect(status().isForbidden());
    }

    // ── 2026-11-06 inceleme düzeltmeleri ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("izleme için: iki sahipli kayıtta yalnız GÖRÜLEN sahibin iletimleri; owner_teams = görülenler + can_manage; can_add buna göre")
    void forMonitorOnlyVisibleOwners() throws Exception {
        NocMonitorDirectory.Row cert = new NocMonitorDirectory.Row(NocType.SSL, 40, "shop.example.com", "shop.example.com", 3L, 2L,
                true, false, null, false);
        when(directory.forAlert(NocType.SSL, "shop.example.com", null)).thenReturn(cert);
        List<AlertForward> all = List.of(fwd(7, 2, "ALL", null, null, "TEAM", 4, null),      // UG'nin (görülen)
                fwd(8, 3, "ALL", null, null, "TEAM", 1, null));                              // SY'nin (görülmeyen)
        List<Collection<?>> asked = new ArrayList<>();
        when(repo.findByOwnerTeamIdInOrderByIdAsc(any())).thenAnswer(i -> {
            Collection<?> owners = i.getArgument(0);
            asked.add(List.copyOf(owners));
            return all.stream().filter(f -> owners.contains(f.getOwnerTeamId())).toList();
        });
        mvc.perform(get("/api/alert-forwards/for-monitor").param("type", "cert").param("domain", "shop.example.com").session(user2()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", contains(7)))
                .andExpect(jsonPath("$.data.owner_teams", hasSize(1)))
                .andExpect(jsonPath("$.data.owner_teams[0].id").value(2))
                .andExpect(jsonPath("$.data.owner_teams[0].name").value("Ağ Operasyon"))
                .andExpect(jsonPath("$.data.owner_teams[0].can_manage").value(false))
                .andExpect(jsonPath("$.data.can_add").value(false));
        assertThat(asked).containsExactly(List.of(2L));
        // UG'yi yöneten, SY'yi yalnız gören kapsamlı yönetici: iki sahip de görünür, can_manage sahip başına
        MockHttpSession mgr = session("TEAM_ADMIN", 11L, List.of(2L, 3L), List.of(2L), List.of(2L));
        mvc.perform(get("/api/alert-forwards/for-monitor").param("type", "cert").param("domain", "shop.example.com").session(mgr))
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(7, 8)))
                .andExpect(jsonPath("$.data.owner_teams[*].id", contains(3, 2)))
                .andExpect(jsonPath("$.data.owner_teams[0].can_manage").value(false))
                .andExpect(jsonPath("$.data.owner_teams[1].can_manage").value(true))
                .andExpect(jsonPath("$.data.can_add").value(true));
        // hiçbir sahibi görmeyen: hidden, depo okunmaz
        MockHttpSession stranger = session("USER", 90L, List.of(4L), List.of(), List.of(4L));
        asked.clear();
        mvc.perform(get("/api/alert-forwards/for-monitor").param("type", "cert").param("domain", "shop.example.com").session(stranger))
                .andExpect(jsonPath("$.data.hidden").value(true))
                .andExpect(jsonPath("$.data.owner_teams").doesNotExist());
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("hedef adres her satırda maskeli (gelen iletim dahil): sorgudaki gizli değer alıcıya gitmez")
    void monitorTargetMasked() throws Exception {
        rows.add(fwd(2, 3, "MONITORS", null, "http:5", "TEAM", 1, null));   // takım 1'e gelen
        when(directory.all()).thenReturn(List.of(new NocMonitorDirectory.Row(NocType.HTTP, 5, "Ödeme API",
                "https://pay.example.com/health?token=s3cr3t-deger&lang=tr", 3L, null, true, false, null, false)));
        mvc.perform(get("/api/alert-forwards").session(teamAdmin1()))
                .andExpect(jsonPath("$.data.incoming[0].scope_monitors[0].name").value("Ödeme API"))
                .andExpect(jsonPath("$.data.incoming[0].scope_monitors[0].target")
                        .value("https://pay.example.com/health?token=*****&lang=tr"));
        mvc.perform(get("/api/alert-forwards").session(admin()))
                .andExpect(jsonPath("$.data.items[0].scope_monitors[0].target")
                        .value("https://pay.example.com/health?token=*****&lang=tr"));
    }

    @Test
    @DisplayName("bilgi: duraklatılmış oluşturma bilgi göndermez (requested=false); etkinleştirmede TÜM alıcılar; bakım susturması muted")
    void infoFollowsEnabled() throws Exception {
        AlertForward paused = fwd(9, 1, "ALL", null, null, "MEMBERS", 2, "50,51");
        paused.setEnabled(false);
        when(forwards.create(anyMap(), any(), any())).thenReturn(new AlertForwardService.Saved(paused, null, true, false, List.of(50L, 51L)));
        mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner_team_id\":1,\"scope_kind\":\"ALL\",\"recipient_kind\":\"TEAM\",\"recipient_team_id\":2,\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.requested").value(false))
                .andExpect(jsonPath("$.info.muted").value(false))
                .andExpect(jsonPath("$.info.emails").value(0))
                .andExpect(jsonPath("$.info.push").value(0));
        verify(notifier, never()).notifyAdded(any(), anyBoolean(), any(), any());

        // duraklatılmış → etkin: o anki TÜM üyeler (yeni üye yokken de)
        rows.add(paused);
        AlertForward active = fwd(9, 1, "ALL", null, null, "MEMBERS", 2, "50,51");
        when(forwards.update(eq(9L), anyMap(), any(), any()))
                .thenReturn(new AlertForwardService.Saved(active, paused, true, false, List.of(), true));
        mvc.perform(put("/api/alert-forwards/9").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.requested").value(true))
                .andExpect(jsonPath("$.info.emails").value(3));
        verify(notifier).notifyAdded(active, false, List.of(50L, 51L), "Kişi 10");

        // etkin kalan iletimde yalnız not değişti → bilgi yok
        org.mockito.Mockito.clearInvocations(notifier);
        when(forwards.update(eq(9L), anyMap(), any(), any()))
                .thenReturn(new AlertForwardService.Saved(active, active, true, false, List.of(), false));
        mvc.perform(put("/api/alert-forwards/9").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(jsonPath("$.info.requested").value(false));
        verify(notifier, never()).notifyAdded(any(), anyBoolean(), any(), any());

        // sistem bakımı susturdu → muted
        when(notifier.notifyAdded(any(), anyBoolean(), any(), any())).thenReturn(new AlertForwardInfoNotifier.Info(true, 0, 0, true));
        when(forwards.update(eq(9L), anyMap(), any(), any()))
                .thenReturn(new AlertForwardService.Saved(active, paused, true, false, List.of(), true));
        mvc.perform(put("/api/alert-forwards/9").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.info.requested").value(true))
                .andExpect(jsonPath("$.info.muted").value(true))
                .andExpect(jsonPath("$.info.emails").value(0));
    }

    // ── hata avı 2026-11-06 ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("H2#8: seçilen izlemeler kapsamında izleme dizini okunamazsa durum 'unknown' (yeşil 'active' değil); öbür kapsamlar etkin")
    void monitorsUnreadableStateUnknown() throws Exception {
        rows.add(fwd(1, 1, "MONITORS", null, "http:5", "TEAM", 2, null));
        rows.add(fwd(2, 1, "ALL", null, null, "TEAM", 3, null));
        when(directory.all()).thenThrow(new RuntimeException("dizin okunamadı"));
        mvc.perform(get("/api/alert-forwards").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].state").value("unknown"))
                .andExpect(jsonPath("$.data.items[0].scope_monitors[0].missing").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].state").value("active"));
    }

    @Test
    @DisplayName("H3#4: izleme için — pasif sahip takımda can_manage false, can_add false (servis pasif sahibe iletim ekletmez)")
    void forMonitorPassiveOwnerCannotAdd() throws Exception {
        Team passiveOwner = team(1, "Sipariş Sistemleri (Dijital Kanallar Uygulama Geliştirme)");
        passiveOwner.setActive(false);
        org.mockito.Mockito.doReturn(List.of(passiveOwner)).when(teamRepo).findAllById(any());
        when(directory.find(NocType.HTTP, 5L)).thenReturn(
                new NocMonitorDirectory.Row(NocType.HTTP, 5, "Vitrin", "https://shop", 1L, null, true, false, null, false));
        when(repo.findByOwnerTeamIdInOrderByIdAsc(any())).thenReturn(List.of());
        mvc.perform(get("/api/alert-forwards/for-monitor").param("type", "http").param("id", "5").session(teamAdmin1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.owner_teams[0].id").value(1))
                .andExpect(jsonPath("$.data.owner_teams[0].can_manage").value(false))
                .andExpect(jsonPath("$.data.can_add").value(false));
    }

    @Test
    @DisplayName("H2#5: görüntüleme erişimi teknik hatayla verilemezse ileti TEKNİK METİN taşımaz (açıklayıcı yedek)")
    void grantViewTechnicalErrorIsNotLeaked() throws Exception {
        AlertForward saved = fwd(9, 1, "ALL", null, null, "TEAM", 2, null);
        when(forwards.create(anyMap(), any(), any())).thenReturn(new AlertForwardService.Saved(saved, null, true, true, List.of()));
        when(grantRepo.findByOwnerTeamIdAndGranteeKey(1L, "T:2"))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement [duplicate key value violates unique constraint]"));
        String body = "{\"owner_team_id\":1,\"scope_kind\":\"ALL\",\"recipient_kind\":\"TEAM\",\"recipient_team_id\":2,\"grant_view\":true}";
        String json = mvc.perform(post("/api/alert-forwards").session(teamAdmin1()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grant.failed").value(1))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("could not execute").doesNotContain("duplicate key").contains("Görüntüleme erişimi verilemedi");
    }
}
