package com.sitemonitor.controller;

import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Yetki matrisi ve IDOR kapıları.
 *
 * <p><b>Durum kodu değişmezi:</b> okunamayan grup her fiil için <b>404</b> — id'ler küçük ve
 * tahmin edilebilir, başka takımın grubunun VAR OLDUĞU bilgisi bile sızmamalı. Okunabilir ama
 * yazılamazsa <b>403</b>.
 *
 * <p><b>USER kapısı:</b> USER'ın {@code manageTeamIds}'i BOŞTUR. Alarmı kimin alacağı takımın
 * nöbet düzenidir, yönetici işlemi değil (K2) — bu yüzden yazma {@code memberTeamIds} ile de
 * açılır. Aşağıdaki "USER kendi takımının grubunu düzenler" testi tam olarak bunu pinler:
 * mekanizma {@code canManage}'e daraltılırsa kırmızıya döner.
 */
@WebMvcTest(NotificationGroupController.class)
class NotificationGroupControllerTest {

    private static final long TEAM_A = 1L;
    private static final long TEAM_B = 2L;
    private static final long GROUP_A = 10L;
    private static final long GROUP_B = 20L;

    @Autowired MockMvc mvc;

    @MockitoBean NotificationGroupRepository groupRepo;
    @MockitoBean NotificationGroupService groupService;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private MockHttpSession userOfA;
    private MockHttpSession userOfB;

    @BeforeEach
    void setUp() {
        when(teamRepo.findAll()).thenReturn(List.of(team(TEAM_A, "Takım A"), team(TEAM_B, "Takım B")));
        when(groupRepo.findById(GROUP_A)).thenReturn(Optional.of(group(GROUP_A, TEAM_A)));
        when(groupRepo.findById(GROUP_B)).thenReturn(Optional.of(group(GROUP_B, TEAM_B)));
        when(groupRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(groupService.validate(any(), any(), anyBoolean()))
                .thenReturn(new NotificationGroupService.GroupInput("Nöbet", List.of("n@x.com"), false));
        when(groupService.create(anyLong(), any(), any(), any())).thenReturn(group(GROUP_A, TEAM_A));
        when(groupService.update(any(), any(), any(), any())).thenReturn(group(GROUP_A, TEAM_A));
        when(groupService.softDelete(any(), any(), any())).thenReturn(group(GROUP_A, TEAM_A));
        when(groupService.makeDefault(any())).thenReturn(group(GROUP_A, TEAM_A));

        userOfA = session("ayse", TEAM_A);
        userOfB = session("burak", TEAM_B);
    }

    /** USER: view kapsamı KENDİ takımı, manage kapsamı BOŞ (K2 kapısının tam olarak sınandığı hâl). */
    private static MockHttpSession session(String username, long teamId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", teamId);
        s.setAttribute("viewTeamIds", List.of(teamId));
        s.setAttribute("manageTeamIds", List.of());
        s.setAttribute("memberTeamIds", List.of(teamId));
        return s;
    }

    private static Team team(long id, String name) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setEmail("takim" + id + "@akbank.com");
        return t;
    }

    private static NotificationGroup group(long id, long teamId) {
        NotificationGroup g = new NotificationGroup();
        g.setId(id);
        g.setTeamId(teamId);
        g.setName("Nöbet " + id);
        g.setEmails("n" + id + "@akbank.com");
        g.setActive(true);
        g.setIsDefault(false);
        return g;
    }

    // ── Yazma yetkisi (K2) ───────────────────────────────────────────────────

    @Test
    @DisplayName("USER kendi TAKIMININ grubunu düzenler — manage kapsamı boş olsa bile")
    void user_editsOwnTeamGroup() throws Exception {
        mvc.perform(put("/api/notification-groups/{id}", GROUP_A)
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nöbet\",\"emails\":[\"n@x.com\"]}"))
                .andExpect(status().isOk());

        verify(groupService).update(any(), any(), eq("ayse"), any());
        verify(auditService).recordAction(eq("NOTIFICATION_GROUP_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("NOTIFICATION_GROUP"), eq(String.valueOf(GROUP_A)),
                ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
    }

    @Test
    @DisplayName("USER kendi takımına grup oluşturur")
    void user_createsOwnTeamGroup() throws Exception {
        mvc.perform(post("/api/notification-groups")
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":1,\"name\":\"Nöbet\",\"emails\":[\"n@x.com\"]}"))
                .andExpect(status().isOk());

        verify(groupService).create(eq(TEAM_A), any(), eq("ayse"), any());
    }

    // ── IDOR kapıları ────────────────────────────────────────────────────────

    @Test
    @DisplayName("IDOR: başka takımın grubunu GÜNCELLEME 404 — varlığı bile sızmaz")
    void foreignGroup_update_404() throws Exception {
        mvc.perform(put("/api/notification-groups/{id}", GROUP_B)
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ele geçir\",\"emails\":[\"x@x.com\"]}"))
                .andExpect(status().isNotFound());

        verify(groupService, never()).update(any(), any(), any(), any());
        // Asiri yuklu recordAction: 3. parametre String mi HttpServletRequest mi belirsiz kalmasin.
        verify(auditService, never()).recordAction(eq("NOTIFICATION_GROUP_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
    }

    @Test
    @DisplayName("IDOR: başka takımın grubunu SİLME 404 ve HİÇBİR silme yapılmaz")
    void foreignGroup_delete_404() throws Exception {
        mvc.perform(delete("/api/notification-groups/{id}", GROUP_A).session(userOfB))
                .andExpect(status().isNotFound());

        verify(groupService, never()).softDelete(any(), any(), any());
    }

    @Test
    @DisplayName("IDOR: başka takımı VARSAYILAN yapma 404")
    void foreignGroup_makeDefault_404() throws Exception {
        mvc.perform(post("/api/notification-groups/{id}/make-default", GROUP_B).session(userOfA))
                .andExpect(status().isNotFound());

        verify(groupService, never()).makeDefault(any());
    }

    @Test
    @DisplayName("IDOR: başka takıma grup OLUŞTURMA 403")
    void foreignTeam_create_403() throws Exception {
        mvc.perform(post("/api/notification-groups")
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":2,\"name\":\"Nöbet\",\"emails\":[\"n@x.com\"]}"))
                .andExpect(status().isForbidden());

        verify(groupService, never()).create(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("IDOR: başka takımın gruplarını LİSTELEME 404")
    void foreignTeam_list_404() throws Exception {
        mvc.perform(get("/api/notification-groups").param("teamId", "2").session(userOfA))
                .andExpect(status().isNotFound());

        verify(groupRepo, never()).findByTeamIdAndActiveTrueOrderByNameAsc(TEAM_B);
    }

    // ── Yetki anahtarı ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Yetkisi olmayan HİÇBİR uca giremez (403) ve yazma çalışmaz")
    void withoutPermission_403() throws Exception {
        doThrow(new SecurityException("Bu işlem için yetkiniz yok: notification.groups/edit"))
                .when(permissionService).require(any(jakarta.servlet.http.HttpSession.class),
                        eq("notification.groups"), eq("edit"));

        mvc.perform(delete("/api/notification-groups/{id}", GROUP_A).session(userOfA))
                .andExpect(status().isForbidden());

        verify(groupService, never()).softDelete(any(), any(), any());
    }

    // ── Doğrulama ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Silinmiş grup VARSAYILAN yapılamaz (400)")
    void inactiveGroup_makeDefault_400() throws Exception {
        NotificationGroup inactive = group(GROUP_A, TEAM_A);
        inactive.setActive(false);
        when(groupRepo.findById(GROUP_A)).thenReturn(Optional.of(inactive));

        mvc.perform(post("/api/notification-groups/{id}/make-default", GROUP_A).session(userOfA))
                .andExpect(status().isBadRequest());

        verify(groupService, never()).makeDefault(any());
    }

    @Test
    @DisplayName("Doğrulama hatası 400'e çevrilir ve hiçbir şey kaydedilmez")
    void validationError_400() throws Exception {
        when(groupService.validate(any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("Grup en az bir e-posta adresi içermelidir"));

        mvc.perform(post("/api/notification-groups")
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":1,\"name\":\"Boş\",\"emails\":[]}"))
                .andExpect(status().isBadRequest());

        verify(groupService, never()).create(anyLong(), any(), any(), any());
    }

    // ── Listeleme ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Liste kendi takımının gruplarını ve DÜRÜST BOŞ DURUM için takım adresini döner")
    void list_ownTeam() throws Exception {
        when(groupRepo.findByTeamIdAndActiveTrueOrderByNameAsc(TEAM_A))
                .thenReturn(List.of(group(GROUP_A, TEAM_A)));
        when(groupService.toDto(any())).thenReturn(new java.util.LinkedHashMap<>(
                java.util.Map.of("id", GROUP_A, "name", "Nöbet 10")));

        mvc.perform(get("/api/notification-groups").param("teamId", "1").session(userOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].id").value((int) GROUP_A))
                .andExpect(jsonPath("$.data.groups[0].can_write").value(true))
                .andExpect(jsonPath("$.data.team_emails.1").value("takim1@akbank.com"));
    }
}
