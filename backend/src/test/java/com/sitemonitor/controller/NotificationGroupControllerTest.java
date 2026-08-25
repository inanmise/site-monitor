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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    @MockitoBean com.sitemonitor.service.NotificationGroupUsageService usageService;
    @MockitoBean com.sitemonitor.service.NotificationGroupHistoryService historyService;
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
        when(groupService.makeDefault(any())).thenReturn(group(GROUP_A, TEAM_A));

        when(usageService.usage(anyLong()))
                .thenReturn(new com.sitemonitor.service.NotificationGroupUsageService.Usage(
                        0, java.util.Map.of(), List.of()));

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
        t.setEmail("takim" + id + "@example.com");
        return t;
    }

    private static NotificationGroup group(long id, long teamId) {
        NotificationGroup g = new NotificationGroup();
        g.setId(id);
        g.setTeamId(teamId);
        g.setName("Nöbet " + id);
        g.setEmails("n" + id + "@example.com");
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

        verify(groupService, never()).deletePermanently(any());
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

        verify(groupService, never()).deletePermanently(any());
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

    // ── Kullanım / silme kapısı / toplu taşıma ───────────────────────────────

    private com.sitemonitor.service.NotificationGroupUsageService.Usage usageOf(int total) {
        return new com.sitemonitor.service.NotificationGroupUsageService.Usage(
                total, java.util.Map.of("ping", total),
                List.of(new com.sitemonitor.service.NotificationGroupUsageService.Ref("ping", 1L, "GW")));
    }

    /**
     * Kullanımdaki grubu silmek, o izlemelerin alarm yönlendirmesini kullanıcı GÖRMEDEN
     * değiştirirdi. 409 döner ve yanıt kullanım özetini TAŞIR — arayüz modali bu özetten
     * besleniyor, ayrı bir istek atmak zorunda kalmıyor.
     */
    @Test
    @DisplayName("KULLANIMDAKİ grup silinemez: 409 ve yanıt kullanım özetini taşır")
    void delete_inUse_returns409WithUsage() throws Exception {
        when(usageService.usage(GROUP_A)).thenReturn(usageOf(3));

        mvc.perform(delete("/api/notification-groups/{id}", GROUP_A).session(userOfA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.usage.total").value(3))
                .andExpect(jsonPath("$.usage.items[0].name").value("GW"))
                .andExpect(jsonPath("$.usage.by_type.ping").value(3));

        verify(groupService, never()).deletePermanently(any());
    }

    @Test
    @DisplayName("KullanımDA DEĞİLSE silme normal işler")
    void delete_notInUse_ok() throws Exception {
        mvc.perform(delete("/api/notification-groups/{id}", GROUP_A).session(userOfA))
                .andExpect(status().isOk());

        verify(groupService).deletePermanently(any());
    }

    @Test
    @DisplayName("Kullanım ucu: kendi takımının grubu için özet döner")
    void usage_ownTeam_ok() throws Exception {
        when(usageService.usage(GROUP_A)).thenReturn(usageOf(2));

        mvc.perform(get("/api/notification-groups/{id}/usage", GROUP_A).session(userOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.truncated").value(true));
    }

    @Test
    @DisplayName("IDOR: BAŞKA takımın grubunun kullanımı sorulamaz (404)")
    void usage_foreignTeam_404() throws Exception {
        mvc.perform(get("/api/notification-groups/{id}/usage", GROUP_B).session(userOfA))
                .andExpect(status().isNotFound());

        verify(usageService, never()).usage(GROUP_B);
    }

    @Test
    @DisplayName("Taşıma: hedef AYNI takımın aktif grubu olmalı, aksi halde 400")
    void reassign_foreignTarget_400() throws Exception {
        mvc.perform(post("/api/notification-groups/{id}/reassign", GROUP_A)
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_group_id\":20}"))          // GROUP_B → TEAM_B
                .andExpect(status().isBadRequest());

        // Toplu taşıma, tek tek yapılması engellenen şeyin (başka takıma yönlendirme)
        // toptan yolu OLMAMALI.
        verify(usageService, never()).reassign(anyLong(), any());
    }

    @Test
    @DisplayName("Taşıma: SİLİNMİŞ gruba taşınamaz (400)")
    void reassign_deletedTarget_400() throws Exception {
        NotificationGroup deleted = group(30L, TEAM_A);
        deleted.setActive(false);
        when(groupRepo.findById(30L)).thenReturn(Optional.of(deleted));

        mvc.perform(post("/api/notification-groups/{id}/reassign", GROUP_A)
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_group_id\":30}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Taşıma: hedef kaynakla AYNI olamaz (400)")
    void reassign_sameTarget_400() throws Exception {
        mvc.perform(post("/api/notification-groups/{id}/reassign", GROUP_A)
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_group_id\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Taşıma: hedef null → grup seçimi kaldırılır (takım varsayılanı) + audit")
    void reassign_toTeamDefault_ok() throws Exception {
        when(usageService.reassign(GROUP_A, null)).thenReturn(java.util.Map.of("ping", 2));

        mvc.perform(post("/api/notification-groups/{id}/reassign", GROUP_A)
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_group_id\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.moved").value(2));

        verify(usageService).reassign(GROUP_A, null);
        verify(auditService).recordAction(eq("NOTIFICATION_GROUP_REASSIGN"),
                any(jakarta.servlet.http.HttpSession.class),
                ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
    }

    @Test
    @DisplayName("IDOR: BAŞKA takımın grubundan taşıma yapılamaz (404)")
    void reassign_foreignSource_404() throws Exception {
        mvc.perform(post("/api/notification-groups/{id}/reassign", GROUP_B)
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_group_id\":null}"))
                .andExpect(status().isNotFound());

        verify(usageService, never()).reassign(anyLong(), any());
    }

    // ── Değişiklik geçmişi ───────────────────────────────────────────────────

    private static com.sitemonitor.model.AuditLog auditRow(long id, String type, String groupId) {
        com.sitemonitor.model.AuditLog a = new com.sitemonitor.model.AuditLog();
        a.setId(id);
        a.setEventType(type);
        a.setEventTime("2026-08-25T10:00:00");
        a.setActor("ayse");
        a.setResourceId(groupId);
        a.setDetail("Nöbet");
        a.setChanges("{\"name\":{\"from\":\"Eski\",\"to\":\"Nöbet\"}}");
        return a;
    }

    /**
     * Geçmiş, ekranı açan yetkiyle ({@code notification.groups}) gelir — denetim uçlarının
     * istediği {@code audit_log.read} ARANMAZ. Aksi halde kendi takımının grubunu yönetebilen
     * kullanıcı, o grubu kimin değiştirdiğini göremezdi.
     */
    @Test
    @DisplayName("Geçmiş: kendi görüş kapsamıyla sınırlı olarak döner")
    void history_returnsScopedRows() throws Exception {
        when(historyService.history(any(), any(), anyInt())).thenReturn(
                new com.sitemonitor.service.NotificationGroupHistoryService.History(
                        List.of(new com.sitemonitor.service.NotificationGroupHistoryService.Entry(
                                auditRow(1L, "NOTIFICATION_GROUP_UPDATE", "10"), TEAM_A)),
                        false, 0));

        mvc.perform(get("/api/notification-groups/history").session(userOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.data.items[0].actor").value("ayse"))
                .andExpect(jsonPath("$.data.items[0].group_id").value("10"))
                .andExpect(jsonPath("$.data.items[0].team_id").value(1))
                .andExpect(jsonPath("$.data.hidden").value(0));

        // Kapsam servise AKTARILMALI: burada boş/null geçmek, tüm takımların geçmişini sızdırırdı.
        verify(historyService).history(argThat(ids -> ids != null && ids.contains(TEAM_A)), isNull(), anyInt());
    }

    @Test
    @DisplayName("Geçmiş: kesilme ve gizlenen sayısı yanıtta AÇIKÇA taşınır")
    void history_reportsTruncationHonestly() throws Exception {
        when(historyService.history(any(), any(), anyInt())).thenReturn(
                new com.sitemonitor.service.NotificationGroupHistoryService.History(List.of(), true, 3));

        mvc.perform(get("/api/notification-groups/history").session(userOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.hidden").value(3));
    }

    @Test
    @DisplayName("Geçmiş: groupId süzgeci servise geçer")
    void history_passesGroupFilter() throws Exception {
        when(historyService.history(any(), any(), anyInt())).thenReturn(
                new com.sitemonitor.service.NotificationGroupHistoryService.History(List.of(), false, 0));

        mvc.perform(get("/api/notification-groups/history").param("groupId", "10").session(userOfA))
                .andExpect(status().isOk());

        verify(historyService).history(any(), eq(GROUP_A), anyInt());
    }

    /**
     * OLUŞTURMA kaydı anlık görüntü taşımalı: "neyi ekledi" sorusunun cevabı başka hiçbir yerde
     * kalmıyor — ayrıca grup sonradan silinince kimlik→takım eşlemesi bu kayıttan kuruluyor.
     */
    @Test
    @DisplayName("Oluşturma denetimi İÇERİĞİ kaydeder (adresler, varsayılan, takım)")
    void create_recordsSnapshot() throws Exception {
        mvc.perform(post("/api/notification-groups")
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":1,\"name\":\"Nöbet\",\"emails\":[\"n@example.com\"]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> changes = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("NOTIFICATION_GROUP_CREATE"),
                any(jakarta.servlet.http.HttpSession.class), eq("NOTIFICATION_GROUP"),
                ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(), changes.capture());

        assertThat(changes.getValue()).isNotNull();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(changes.getValue()).get("teamId").asLong()).isEqualTo(TEAM_A);
    }

    /** Silme kaydı AYRIŞTIRILABİLİR olmalı: JSON değilse geçmiş ekranı onu okuyamaz. */
    @Test
    @DisplayName("Silme denetimi GEÇERLİ JSON anlık görüntü yazar")
    void delete_recordsParsableSnapshot() throws Exception {
        when(usageService.usage(GROUP_A)).thenReturn(
                new com.sitemonitor.service.NotificationGroupUsageService.Usage(0, java.util.Map.of(), List.of()));

        mvc.perform(delete("/api/notification-groups/{id}", GROUP_A).session(userOfA))
                .andExpect(status().isOk());

        ArgumentCaptor<String> changes = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("NOTIFICATION_GROUP_DELETE"),
                any(jakarta.servlet.http.HttpSession.class), eq("NOTIFICATION_GROUP"),
                ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(), changes.capture());

        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(changes.getValue());
        assertThat(node.get("teamId").asLong()).isEqualTo(TEAM_A);
        assertThat(node.get("name").asText()).isNotBlank();
    }

    // ── Gövde ayrıştırma ve kapsam çözümü ────────────────────────────────────

    @Test
    @DisplayName("POST: team_id verilmezse YAZILABİLİR ilk takıma düşer")
    void create_withoutTeamId_fallsBackToWritableTeam() throws Exception {
        mvc.perform(post("/api/notification-groups")
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nöbet\",\"emails\":[\"n@example.com\"]}"))
                .andExpect(status().isOk());

        verify(groupService).create(eq(TEAM_A), any(), eq("ayse"), any());
    }

    @Test
    @DisplayName("POST: yazılabilir takımı OLMAYAN kullanıcı 400 alır (403 değil — istek geçersiz)")
    void create_withNoWritableTeam_returns400() throws Exception {
        MockHttpSession noTeam = new MockHttpSession();
        noTeam.setAttribute("authenticated", Boolean.TRUE);
        noTeam.setAttribute("username", "cem");
        noTeam.setAttribute("systemRole", "USER");
        noTeam.setAttribute("viewTeamIds", List.of());
        noTeam.setAttribute("manageTeamIds", List.of());
        noTeam.setAttribute("memberTeamIds", List.of());

        mvc.perform(post("/api/notification-groups")
                        .session(noTeam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nöbet\",\"emails\":[\"n@example.com\"]}"))
                .andExpect(status().isBadRequest());

        verify(groupService, never()).create(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("POST: adresler DİZİ yerine tek dize olarak da gönderilebilir")
    void create_acceptsEmailsAsPlainString() throws Exception {
        // Belgelenmiş bir kabul: kopyala-yapıştır tek alana virgüllü liste bırakır.
        mvc.perform(post("/api/notification-groups")
                        .session(userOfA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":1,\"name\":\"Nöbet\",\"emails\":\"a@example.com, b@example.com\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<List<String>> cap = ArgumentCaptor.forClass(List.class);
        verify(groupService).validate(any(), cap.capture(), anyBoolean());
        assertThat(cap.getValue()).containsExactly("a@example.com, b@example.com");
    }

    @Test
    @DisplayName("Liste: includeInactive=true SİLİNMİŞ grupları da döner (formdaki rozet buna dayanıyor)")
    void list_includeInactive_returnsSoftDeleted() throws Exception {
        NotificationGroup deleted = group(GROUP_A, TEAM_A);
        deleted.setActive(false);
        when(groupRepo.findByTeamIdOrderByNameAsc(TEAM_A)).thenReturn(List.of(deleted));
        when(groupService.toDto(any())).thenReturn(new java.util.LinkedHashMap<>(
                java.util.Map.of("id", GROUP_A, "active", false)));

        mvc.perform(get("/api/notification-groups")
                        .param("teamId", "1").param("includeInactive", "true")
                        .session(userOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].active").value(false));

        // Varsayılan (aktif-only) sorgu bu yolda HİÇ kullanılmamalı.
        verify(groupRepo, never()).findByTeamIdAndActiveTrueOrderByNameAsc(TEAM_A);
    }

    @Test
    @DisplayName("Varsayılan yapma: kendi takımının aktif grubunda 200 ve servis çağrılır")
    void makeDefault_ownTeam_ok() throws Exception {
        mvc.perform(post("/api/notification-groups/{id}/make-default", GROUP_A).session(userOfA))
                .andExpect(status().isOk());

        verify(groupService).makeDefault(any());
    }

    @Test
    @DisplayName("Silme: kendi takımının KULLANILMAYAN grubu KALICI silinir")
    void delete_ownTeam_deletesPermanently() throws Exception {
        mvc.perform(delete("/api/notification-groups/{id}", GROUP_A).session(userOfA))
                .andExpect(status().isOk());

        verify(groupService).deletePermanently(any());
    }

    @Test
    @DisplayName("Liste TEK toplu sorgu kullanır (takım başına sorgu N+1 üretiyordu)")
    void list_batchesTeamQueries() throws Exception {
        // Global admin: kapsam TÜM takımlar. Eskiden takım başına ayrı sorgu atiliyor ve yorumda
        // "kullanıcı birkaç takımın üyesidir" yaziyordu — bu ekranı tam olarak adminler açıyor.
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("authenticated", Boolean.TRUE);
        admin.setAttribute("username", "admin");
        admin.setAttribute("systemRole", "ADMIN");     // viewTeamIds YOK -> global

        when(groupRepo.findByTeamIdInAndActiveTrueOrderByTeamIdAscNameAsc(any()))
                .thenReturn(List.of(group(GROUP_A, TEAM_A), group(GROUP_B, TEAM_B)));
        when(groupService.toDto(any())).thenReturn(new java.util.LinkedHashMap<>(
                java.util.Map.of("id", GROUP_A)));

        mvc.perform(get("/api/notification-groups").session(admin))
                .andExpect(status().isOk());

        verify(groupRepo).findByTeamIdInAndActiveTrueOrderByTeamIdAscNameAsc(any());
        // Takım başına sorgu HİÇ atılmamalı.
        verify(groupRepo, never()).findByTeamIdAndActiveTrueOrderByNameAsc(anyLong());
    }

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
                .andExpect(jsonPath("$.data.team_emails.1").value("takim1@example.com"));
    }
}
