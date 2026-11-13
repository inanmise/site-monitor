package com.sitemonitor.controller;

import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bildirim grupları toplu ucu (2026-11-13, yönetim zenginleştirmesi (d)). Kapsam satır satır (tekil uçlarla aynı K2
 * kuralı: yazma = global yönetici / yönetim / üyelik); görülmeyen grup {@code not_found}, görülüp yazılamayan
 * {@code out_of_scope}. Kullanılan ya da varsayılan grup silinmez / taşınmaz (izlemelerin yönlendirmesi sessizce
 * değişirdi); taşımada hedef takımda aynı adlı grup varsa {@code name_taken}.
 */
@WebMvcTest(NotificationGroupController.class)
class NotificationGroupBulkTest {

    private static final long TEAM_A = 1L;
    private static final long TEAM_B = 2L;
    private static final long TEAM_C = 3L;

    @Autowired MockMvc mvc;

    @MockitoBean NotificationGroupRepository groupRepo;
    @MockitoBean NotificationGroupService groupService;
    @MockitoBean NotificationGroupUsageService usageService;
    @MockitoBean NotificationGroupHistoryService historyService;
    @MockitoBean InactiveRecipientGuard inactiveGuard;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private final List<NotificationGroup> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store.clear();
        store.add(group(10L, TEAM_A, "Nöbet", false, true, "a@example.com"));    // kullanılmıyor
        store.add(group(11L, TEAM_A, "Ödeme", false, true, "b@example.com"));    // kullanılıyor (3)
        store.add(group(12L, TEAM_A, "Ana", true, true, "c@example.com"));       // varsayılan
        store.add(group(20L, TEAM_B, "Nöbet", false, true, "d@example.com"));    // B takımı (A kullanıcısı görmez)
        store.add(group(13L, TEAM_A, "Adressiz", false, true, "asdf"));          // gönderilebilir adres yok
        store.add(group(14L, TEAM_A, "Eski", false, false, "e@example.com"));    // eski yumuşak silinmiş
        when(groupRepo.findAllById(anyIterable())).thenAnswer(inv -> {
            List<Long> ids = new ArrayList<>();
            ((Iterable<?>) inv.getArgument(0)).forEach(o -> ids.add((Long) o));
            return store.stream().filter(g -> ids.contains(g.getId())).toList();
        });
        when(groupRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(groupService.makeDefault(any())).thenAnswer(i -> { NotificationGroup g = i.getArgument(0); g.setIsDefault(true); return g; });
        when(teamRepo.findAll()).thenReturn(List.of(team(TEAM_A, "Takım A"), team(TEAM_B, "Takım B"), team(TEAM_C, "Takım C")));
        when(teamRepo.existsById(anyLong())).thenAnswer(i -> List.of(TEAM_A, TEAM_B, TEAM_C).contains((Long) i.getArgument(0)));
        when(usageService.usageCounts()).thenReturn(Map.of(11L, 3));
        when(usageService.usage(anyLong())).thenAnswer(i -> new NotificationGroupUsageService.Usage(
                ((Long) i.getArgument(0)) == 11L ? 3 : 0, Map.of(), List.of()));
    }

    private static Team team(long id, String name) {
        Team t = new Team(); t.setId(id); t.setName(name); return t;
    }

    private static NotificationGroup group(long id, long teamId, String name, boolean def, boolean active, String emails) {
        NotificationGroup g = new NotificationGroup();
        g.setId(id); g.setTeamId(teamId); g.setName(name); g.setIsDefault(def); g.setActive(active); g.setEmails(emails);
        return g;
    }

    /** USER: görüş + üyelik A (yazma K2 ile açık), yönetim yok. */
    private static MockHttpSession userOfA() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "ayse");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", TEAM_A);
        s.setAttribute("viewTeamIds", List.of(TEAM_A, TEAM_C));
        s.setAttribute("manageTeamIds", List.of());
        s.setAttribute("memberTeamIds", List.of(TEAM_A));
        return s;
    }

    private static MockHttpSession global() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    @Test
    @DisplayName("delete: yalnız kullanılmayan, varsayılan olmayan grup silinir; kullanılan in_use, varsayılan is_default, başka takımınki not_found")
    void delete_rules() throws Exception {
        mvc.perform(post("/api/notification-groups/bulk").session(userOfA()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"delete\",\"ids\":[10,11,12,20,14]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.done").value(2))
                .andExpect(jsonPath("$.data.results[0].reason").value("done"))
                .andExpect(jsonPath("$.data.results[1].reason").value("in_use"))
                .andExpect(jsonPath("$.data.results[1].usage").value(3))
                .andExpect(jsonPath("$.data.results[2].reason").value("is_default"))
                .andExpect(jsonPath("$.data.results[3].reason").value("not_found"))
                .andExpect(jsonPath("$.data.results[3].name").doesNotExist())
                .andExpect(jsonPath("$.data.results[4].reason").value("done"));   // eski yumuşak silinmiş, kullanılmıyor
        ArgumentCaptor<NotificationGroup> cap = ArgumentCaptor.forClass(NotificationGroup.class);
        verify(groupService, times(2)).deletePermanently(cap.capture());
        assertThat(cap.getAllValues()).extracting(NotificationGroup::getId).containsExactly(10L, 14L);
        verify(auditService, times(2)).recordAction(eq("NOTIFICATION_GROUP_DELETE"), any(jakarta.servlet.http.HttpSession.class),
                eq("NOTIFICATION_GROUP"), anyString(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
    }

    @Test
    @DisplayName("delete: toplu sayımda 0 görünse de silmeden önce yeniden denetlenen kullanım varsa in_use (yarış)")
    void delete_rechecksUsage() throws Exception {
        when(usageService.usageCounts()).thenReturn(Map.of());
        when(usageService.usage(10L)).thenReturn(new NotificationGroupUsageService.Usage(1, Map.of(), List.of()));
        mvc.perform(post("/api/notification-groups/bulk").session(userOfA()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"delete\",\"ids\":[10]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reason").value("in_use"));
        verify(groupService, never()).deletePermanently(any());
    }

    @Test
    @DisplayName("set_team: kullanılmayan grup taşınır; aynı ad hedefte varsa name_taken; yazılamayan hedef takım 403")
    void setTeam_rules() throws Exception {
        when(groupRepo.existsByTeamAndName(eq(TEAM_B), eq("Nöbet"), any())).thenReturn(true);
        mvc.perform(post("/api/notification-groups/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_team\",\"ids\":[10,11,12,13,14],\"team_id\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reason").value("name_taken"))
                .andExpect(jsonPath("$.data.results[1].reason").value("in_use"))
                .andExpect(jsonPath("$.data.results[2].reason").value("is_default"))
                .andExpect(jsonPath("$.data.results[3].reason").value("done"))
                .andExpect(jsonPath("$.data.results[3].team_name").value("Takım B"))
                .andExpect(jsonPath("$.data.results[4].reason").value("inactive"));
        assertThat(store.get(4).getTeamId()).isEqualTo(TEAM_B);
        assertThat(store.get(0).getTeamId()).isEqualTo(TEAM_A);
        verify(auditService).recordAction(eq("NOTIFICATION_GROUP_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("NOTIFICATION_GROUP"), eq("13"), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());

        // USER A, C'yi görür ama C'ye grup yazamaz → istek tümden 403
        mvc.perform(post("/api/notification-groups/bulk").session(userOfA()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_team\",\"ids\":[10],\"team_id\":3}"))
                .andExpect(status().isForbidden());
        assertThat(store.get(0).getTeamId()).isEqualTo(TEAM_A);
    }

    @Test
    @DisplayName("make_default: takım başına bir grup; adressiz no_emails, aynı takımdan ikincisi duplicate_team, zaten varsayılan unchanged")
    void makeDefault_rules() throws Exception {
        mvc.perform(post("/api/notification-groups/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"make_default\",\"ids\":[13,10,11,20,12]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reason").value("no_emails"))
                .andExpect(jsonPath("$.data.results[1].reason").value("done"))
                .andExpect(jsonPath("$.data.results[2].reason").value("duplicate_team"))
                .andExpect(jsonPath("$.data.results[3].reason").value("done"))
                .andExpect(jsonPath("$.data.results[4].reason").value("duplicate_team"));
        verify(groupService, times(2)).makeDefault(any());
        verify(auditService, times(2)).recordAction(eq("NOTIFICATION_GROUP_DEFAULT"), any(jakarta.servlet.http.HttpSession.class),
                eq("NOTIFICATION_GROUP"), anyString(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
        verify(usageService, never()).usageCounts();
    }

    @Test
    @DisplayName("geçersiz eylem / boş seçim 400 alan hatası; etkinleştirme eylemi YOK (active=false eski yumuşak silmedir)")
    void validation() throws Exception {
        mvc.perform(post("/api/notification-groups/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"activate\",\"ids\":[10]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("action"));
        mvc.perform(post("/api/notification-groups/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"delete\",\"ids\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));
        mvc.perform(post("/api/notification-groups/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_team\",\"ids\":[10]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("team_id"));
        mvc.perform(post("/api/notification-groups/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_team\",\"ids\":[10],\"team_id\":99}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("team_id"));
        verify(groupService, never()).deletePermanently(any());
    }
}
