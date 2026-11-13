package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Eskalasyon kişileri toplu uçları (2026-11-13, yönetim zenginleştirmesi (d)/(h)) — kapsam SATIR SATIR uygulanır:
 * kapsamlı müdür yalnız YÖNETTİĞİ takımların kişilerini değiştirir; görüş kapsamındaki ama yönetmediği satır
 * {@code out_of_scope}, görüş kapsamında bile olmayan {@code not_found} (varlığı sızmaz) — hiçbirine yazılmaz.
 */
@WebMvcTest(ContactBulkController.class)
class ContactBulkControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean EscalationContactRepository contactRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean AppUserRepository userRepo;
    @MockitoBean AuditService auditService;
    @MockitoBean PermissionService permissionService;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private final List<EscalationContact> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store.clear();
        store.add(contact(1L, 2L, "WARNING", true));    // müdürün yönettiği takım
        store.add(contact(2L, 2L, "HIGH", false));
        store.add(contact(3L, 3L, "WARNING", true));    // görüş kapsamında, yönetmiyor
        store.add(contact(4L, 9L, "WARNING", true));    // görüş kapsamı dışı
        store.add(contact(5L, null, "WARNING", false)); // takımsız (eski veri)
        when(contactRepo.findAllById(anyIterable())).thenAnswer(inv -> {
            List<Long> ids = new ArrayList<>();
            ((Iterable<?>) inv.getArgument(0)).forEach(o -> ids.add((Long) o));
            return store.stream().filter(c -> ids.contains(c.getId())).toList();
        });
        when(contactRepo.save(any())).thenAnswer(inv -> {
            EscalationContact c = inv.getArgument(0);
            if (c.getId() == null) c.setId(100L + store.size());
            return c;
        });
        when(teamRepo.findAll()).thenReturn(List.of(team(2L, "Ödeme"), team(3L, "Kanal"), team(9L, "Başka"), team(4L, "Hedef")));
        when(teamRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(teamOf(inv.getArgument(0))));
        when(teamRepo.findAllById(anyIterable())).thenAnswer(inv -> {
            List<Team> out = new ArrayList<>();
            ((Iterable<?>) inv.getArgument(0)).forEach(o -> { Team t = teamOf((Long) o); if (t != null) out.add(t); });
            return out;
        });
    }

    private static Team teamOf(Long id) {
        return switch (id.intValue()) {
            case 2 -> team(2L, "Ödeme");
            case 3 -> team(3L, "Kanal");
            case 4 -> team(4L, "Hedef");
            case 9 -> team(9L, "Başka");
            default -> null;
        };
    }

    private static Team team(Long id, String name) {
        Team t = new Team(); t.setId(id); t.setName(name); return t;
    }

    private static EscalationContact contact(Long id, Long teamId, String level, boolean active) {
        EscalationContact c = new EscalationContact();
        c.setId(id); c.setTeamId(teamId); c.setMinAlertLevel(level); c.setActive(active);
        c.setName("Kişi " + id); c.setEmail("k" + id + "@example.com"); c.setRole("TECH");
        return c;
    }

    private static MockHttpSession global() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    /** Kapsamlı müdür: görüş 2 + 3, yönetim yalnız 2. */
    private static MockHttpSession mudur() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "mudur");
        s.setAttribute("userId", 77L);
        s.setAttribute("teamId", 2L);
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(2L, 3L)));
        s.setAttribute("manageTeamIds", new ArrayList<>(List.of(2L)));
        return s;
    }

    private static MockHttpSession teamAdmin() {
        MockHttpSession s = mudur();
        s.setAttribute("systemRole", "TEAM_ADMIN");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(2L)));
        return s;
    }

    private static MockHttpSession user() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "uye");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(2L)));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        return s;
    }

    private static AppUser appUser(Long id, Long teamId, boolean active, String email) {
        AppUser u = new AppUser();
        u.setId(id); u.setUsername("U" + id); u.setDisplayName("Ad Soyad " + id); u.setTeamId(teamId);
        u.setActive(active); u.setEmail(email);
        return u;
    }

    // ── /bulk ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kapsamlı müdür: yönettiği takımın kişisi işlenir; görüp yönetmediği out_of_scope, görmediği not_found — yazma yok")
    void scopedAdmin_perRowScope() throws Exception {
        mvc.perform(post("/api/admin/contacts/bulk").session(mudur()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deactivate\",\"ids\":[1,3,4,999]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.done").value(1))
                .andExpect(jsonPath("$.data.skipped").value(3))
                .andExpect(jsonPath("$.data.results[0].status").value("done"))
                .andExpect(jsonPath("$.data.results[1].reason").value("out_of_scope"))
                .andExpect(jsonPath("$.data.results[1].name").value("Kişi 3"))
                .andExpect(jsonPath("$.data.results[2].reason").value("not_found"))
                .andExpect(jsonPath("$.data.results[2].name").doesNotExist())
                .andExpect(jsonPath("$.data.results[3].reason").value("not_found"));
        ArgumentCaptor<EscalationContact> cap = ArgumentCaptor.forClass(EscalationContact.class);
        verify(contactRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(1L);
        assertThat(cap.getValue().getActive()).isFalse();
        assertThat(store.get(2).getActive()).isTrue();   // kapsam dışı satır değişmedi
        verify(auditService).recordAction(eq("CONTACT_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("ESCALATION_CONTACT"), eq("1"), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
        verify(auditService).recordAction(eq("CONTACT_BULK_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("ESCALATION_CONTACT"), eq("bulk"), anyString());
    }

    @Test
    @DisplayName("değişmeyen satır unchanged (yazma + satır denetimi yok); takımsız kişi etkinleştirilmez (no_team)")
    void unchangedAndNoTeam() throws Exception {
        mvc.perform(post("/api/admin/contacts/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"activate\",\"ids\":[1,2,5]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reason").value("unchanged"))
                .andExpect(jsonPath("$.data.results[1].reason").value("done"))
                .andExpect(jsonPath("$.data.results[2].reason").value("no_team"));
        verify(contactRepo, times(1)).save(any());
        assertThat(store.get(1).getActive()).isTrue();
        assertThat(store.get(4).getActive()).isFalse();
        verify(auditService, times(1)).recordAction(eq("CONTACT_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                anyString(), anyString(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
    }

    @Test
    @DisplayName("set_level: seviye yazılır; geçersiz seviye 400 alan hatası (min_alert_level)")
    void setLevel() throws Exception {
        mvc.perform(post("/api/admin/contacts/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_level\",\"ids\":[1,2],\"min_alert_level\":\"critical\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.done").value(2));
        assertThat(store.get(0).getMinAlertLevel()).isEqualTo("CRITICAL");
        mvc.perform(post("/api/admin/contacts/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_level\",\"ids\":[1],\"min_alert_level\":\"LOW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("min_alert_level"));
    }

    @Test
    @DisplayName("set_team: global yönetici takımsız kişiyi takıma atar; kapsamlı müdür yönetmediği takıma taşıyamaz → 403, yazma yok")
    void setTeam() throws Exception {
        mvc.perform(post("/api/admin/contacts/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_team\",\"ids\":[5,1],\"team_id\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.done").value(2))
                .andExpect(jsonPath("$.data.results[0].team_name").value("Hedef"));
        assertThat(store.get(4).getTeamId()).isEqualTo(4L);

        store.get(0).setTeamId(2L);
        mvc.perform(post("/api/admin/contacts/bulk").session(mudur()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_team\",\"ids\":[1],\"team_id\":3}"))
                .andExpect(status().isForbidden());
        assertThat(store.get(0).getTeamId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("set_team: TEAM_ADMIN kişiyi başka takıma taşıyamaz (tekil düzenlemeyle aynı kural) → 403")
    void setTeam_teamAdminRefused() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("system.team_admin"), eq("execute"))).thenReturn(true);
        mvc.perform(post("/api/admin/contacts/bulk").session(teamAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_team\",\"ids\":[1],\"team_id\":2}"))
                .andExpect(status().isForbidden());
        verify(contactRepo, never()).save(any());
    }

    @Test
    @DisplayName("USER (yönetim kapsamı boş) toplu işlem yapamaz → 403; hatalı eylem / boş ve aşırı seçim 400")
    void gatesAndValidation() throws Exception {
        mvc.perform(post("/api/admin/contacts/bulk").session(user()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"activate\",\"ids\":[1]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/contacts/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"delete\",\"ids\":[1]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("action"));
        mvc.perform(post("/api/admin/contacts/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"activate\",\"ids\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 201; i++) { if (i > 1) many.append(','); many.append(i); }
        mvc.perform(post("/api/admin/contacts/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"activate\",\"ids\":[" + many + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));
        verify(contactRepo, never()).save(any());
    }

    // ── /bulk-create ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("bulk-create: her takıma bir kişi; aynı kullanıcının kaydı olan takım atlanır (already_exists), yok takım not_found")
    void bulkCreate_global() throws Exception {
        when(userRepo.findById(50L)).thenReturn(Optional.of(appUser(50L, 2L, true, "kisi@example.com")));
        EscalationContact prior = contact(7L, 3L, "HIGH", true); prior.setUserId(50L);
        when(contactRepo.findByUserId(50L)).thenReturn(List.of(prior));
        mvc.perform(post("/api/admin/contacts/bulk-create").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_ids\":[2,3,77],\"user_id\":50,\"role\":\"MANAGER\",\"min_alert_level\":\"WARNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.done").value(1))
                .andExpect(jsonPath("$.data.results[0].reason").value("done"))
                .andExpect(jsonPath("$.data.results[1].reason").value("already_exists"))
                .andExpect(jsonPath("$.data.results[1].existing_level").value("HIGH"))
                .andExpect(jsonPath("$.data.results[2].reason").value("not_found"));
        ArgumentCaptor<EscalationContact> cap = ArgumentCaptor.forClass(EscalationContact.class);
        verify(contactRepo).save(cap.capture());
        EscalationContact c = cap.getValue();
        assertThat(c.getTeamId()).isEqualTo(2L);
        assertThat(c.getUserId()).isEqualTo(50L);
        assertThat(c.getRole()).isEqualTo("MANAGER");
        assertThat(c.getEmail()).isEqualTo("kisi@example.com");
        assertThat(c.getActive()).isTrue();
        verify(auditService).recordAction(eq("CONTACT_CREATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("ESCALATION_CONTACT"), any(), any(), any());
        verify(auditService).recordAction(eq("CONTACT_BULK_CREATE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("ESCALATION_CONTACT"), eq("bulk"), anyString());
    }

    @Test
    @DisplayName("bulk-create kapsamlı müdür: yönetmediği takım out_of_scope (kişi açılmaz); kapsam dışı kullanıcı 400 user_id")
    void bulkCreate_scoped() throws Exception {
        when(userRepo.findById(50L)).thenReturn(Optional.of(appUser(50L, 2L, true, "kisi@example.com")));
        when(userRepo.findById(60L)).thenReturn(Optional.of(appUser(60L, 9L, true, "yabanci@example.com")));
        when(contactRepo.findByUserId(any())).thenReturn(List.of());
        mvc.perform(post("/api/admin/contacts/bulk-create").session(mudur()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_ids\":[2,3,9],\"user_id\":50,\"role\":\"TECH\",\"min_alert_level\":\"WARNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reason").value("done"))
                .andExpect(jsonPath("$.data.results[1].reason").value("out_of_scope"))
                .andExpect(jsonPath("$.data.results[2].reason").value("not_found"));
        verify(contactRepo, times(1)).save(any());

        mvc.perform(post("/api/admin/contacts/bulk-create").session(mudur()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_ids\":[2],\"user_id\":60}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("user_id"));
        verify(contactRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("bulk-create: pasif ya da adressiz kullanıcı 400 user_id; geçersiz rol 400 role")
    void bulkCreate_userValidation() throws Exception {
        when(userRepo.findById(51L)).thenReturn(Optional.of(appUser(51L, 2L, false, "p@example.com")));
        when(userRepo.findById(52L)).thenReturn(Optional.of(appUser(52L, 2L, true, " ")));
        mvc.perform(post("/api/admin/contacts/bulk-create").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_ids\":[2],\"user_id\":51}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("user_id"));
        mvc.perform(post("/api/admin/contacts/bulk-create").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_ids\":[2],\"user_id\":52}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("user_id"));
        mvc.perform(post("/api/admin/contacts/bulk-create").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_ids\":[2],\"user_id\":51,\"role\":\"BOSS\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("role"));
        verify(contactRepo, never()).save(any());
        verify(auditService, never()).recordAction(eq("CONTACT_BULK_CREATE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), any());
        verify(permissionService, atLeastOnce()).require(any(jakarta.servlet.http.HttpSession.class), eq("contacts.crud"), eq("edit"));
    }

    @Test
    @DisplayName("satır hatası istisna metnini sızdırmaz; diğer satırlar sürer")
    void rowFailureIsSafe() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("org.hibernate.exception.JDBCConnectionException: jdbc:postgresql://db:5432 host"))
                .when(contactRepo).save(ArgumentMatchers.argThat(c -> c != null && Long.valueOf(1L).equals(c.getId())));
        mvc.perform(post("/api/admin/contacts/bulk").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_level\",\"ids\":[1,2],\"min_alert_level\":\"CRITICAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.done").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("failed"))
                .andExpect(jsonPath("$.data.results[0].message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc"))));
        // özet yine yazılır (yapılan satır kayıtlı kalır)
        verify(auditService).recordAction(eq("CONTACT_BULK_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("ESCALATION_CONTACT"), eq("bulk"), anyString());
    }
}
