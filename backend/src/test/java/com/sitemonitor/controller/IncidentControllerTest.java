package com.sitemonitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
    /** Alarm bağı doğrulaması (2026-10-01) — bu dosyanın senaryoları alert_event_id göndermez; davranışı IncidentAlertLinkControllerTest'te. */
    @MockitoBean com.sitemonitor.service.IncidentAlertLinkService alertLinks;
    @MockitoBean com.sitemonitor.service.incident.IncidentPostmortemService postmortems;
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
    @DisplayName("Takım erişim paylaşımı (2026-10-12): paylaşımla OKUNAN kayıt can_edit=false; kendi kaydında alan YAZILMAZ")
    void sharedIncidentIsReadOnly() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        IncidentRecord shared = sample();
        shared.setTeamId(9L);
        IncidentRecord own = sample();
        own.setId(2L);
        own.setTeamId(5L);
        when(service.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(shared, own)));
        when(service.get(1L)).thenReturn(shared);
        MockHttpSession s = userSessionTeam(5L);
        s.setAttribute(SessionScope.ATTR_GRANT_VIEW, new java.util.ArrayList<>(List.of(9L)));
        mvc.perform(get("/api/incidents").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].can_edit").value(false))
                .andExpect(jsonPath("$.data[1].can_edit").doesNotExist());
        mvc.perform(get("/api/incidents/1").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(false));
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.manage"), eq("edit"))).thenReturn(true);
        mvc.perform(put("/api/incidents/1").session(s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isForbidden());
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

    // ── Taslak görsel okuma (prod kapısı 2026-09-25, Y-1) ───────────────────────────────────
    // Olaya bağlanmamış (taslak) görsel takım kapısından hiç geçmiyordu: incidents.view sahibi
    // herkes id taramasıyla başkasının taslağını okuyabiliyordu.

    private static com.sitemonitor.model.IncidentImage draftBy(String createdBy) {
        com.sitemonitor.model.IncidentImage img = new com.sitemonitor.model.IncidentImage();
        img.setId(9L);
        img.setIncidentId(null);
        img.setContentType("image/png");
        img.setData(new byte[]{7, 8, 9});
        img.setCreatedBy(createdBy);
        return img;
    }

    @Test
    @DisplayName("Y-1: BAŞKA kullanıcının taslak görseli → 404 (varlığı da sızmaz)")
    void serveImage_draftOfAnotherUser_404() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        when(service.getImage(9L)).thenReturn(draftBy("baska-kullanici"));

        mvc.perform(get("/api/incidents/images/9").session(userSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Y-1: taslağı YÜKLEYEN kullanıcı (harf duyarsız) okur → 200 + baytlar")
    void serveImage_draftByUploader_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        when(service.getImage(9L)).thenReturn(draftBy("SRE1"));   // oturum "sre1"

        mvc.perform(get("/api/incidents/images/9").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{7, 8, 9}));
    }

    @Test
    @DisplayName("Y-1: global görücü başkasının taslağını okur (davranış korunur)")
    void serveImage_draft_globalViewer_200() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        when(service.getImage(9L)).thenReturn(draftBy("baska-kullanici"));

        mvc.perform(get("/api/incidents/images/9").session(adminSession()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("2026-10-12: ayrıntı ucu da postmortem özetini taşır (bağlantıyla açılan kart 'postmortem yok' demesin); okunduğu işaretlenir")
    void getCarriesPostmortemSummary() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        when(service.get(1L)).thenReturn(sample());
        when(postmortems.summaries(any())).thenReturn(java.util.Map.of(1L, new java.util.LinkedHashMap<>(java.util.Map.of(
                "postmortem_status", "PUBLISHED", "actions_open", 2, "actions_total", 3))));
        mvc.perform(get("/api/incidents/1").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postmortem_status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.actions_open").value(2))
                .andExpect(jsonPath("$.data.pm_summary").value(true));
        // Özet okunamazsa ayrıntı yine döner, işaret YOK (istemci "yok" demez, "açın" der)
        when(postmortems.summaries(any())).thenThrow(new IllegalStateException("db"));
        mvc.perform(get("/api/incidents/1").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pm_summary").doesNotExist());
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

    // -- POST/PUT gövdesindeki team_id (A2, 2026-09-28) -------------------------------------
    // Yalnız /transfer hedefi doğruluyordu; create/update gövdedeki team_id'yi olduğu gibi yazıyordu.
    // incidents.manage USER'da açık → Takım B'nin ledger'ına sahte olay + (send_notification) B'ye mail.

    /** AD-kaynaklı kapsamlı müdür: rol ADMIN ama takım 2'ye sınırlı — global görücü DEĞİL. */
    private MockHttpSession scopedAdminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "mudur");
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(java.util.List.of(2L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(java.util.List.of(2L)));
        return s;
    }

    @Test
    @DisplayName("A2 create: USER yabancı team_id (+send_notification) → 403; kayıt ve mail YOK")
    void create_foreignTeamId_user_403() throws Exception {
        allowManage();
        mvc.perform(post("/api/incidents").session(userSessionTeam(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Sahte\",\"occurred_at\":\"2026-09-28T10:00:00\",\"team_id\":42,"
                                + "\"team_name\":\"Takim B\",\"send_notification\":true}"))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any(), any(), any(), any());
        verify(notificationService, never()).notifyIncident(any(), any());
    }

    @Test
    @DisplayName("A2 create: kapsamlı müdür (ADMIN + viewTeamIds) yabancı team_id → 403 (rol ADMIN ≠ global)")
    void create_foreignTeamId_scopedAdmin_403() throws Exception {
        allowManage();
        mvc.perform(post("/api/incidents").session(scopedAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"occurred_at\":\"2026-09-28T10:00:00\",\"team_id\":9}"))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A2 create: kendi takımı (form değeri metin \"7\") → 200; global admin her takıma → 200")
    void create_ownTeamOrGlobal_200() throws Exception {
        allowManage();
        when(service.create(any(), any(), any(), any())).thenReturn(sample());
        mvc.perform(post("/api/incidents").session(userSessionTeam(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"occurred_at\":\"2026-09-28T10:00:00\",\"team_id\":\"7\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"occurred_at\":\"2026-09-28T10:00:00\",\"team_id\":42}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A2 update: kendi olayını YABANCI takıma taşıma → 403, güncelleme yazılmaz")
    void update_moveToForeignTeam_403() throws Exception {
        allowManage();
        when(service.get(1L)).thenReturn(recOfTeam(1L, 7L));
        mvc.perform(put("/api/incidents/1").session(userSessionTeam(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":42,\"send_notification\":true}"))
                .andExpect(status().isForbidden());
        verify(service, never()).update(any(), any(), any());
        verify(notificationService, never()).notifyIncident(any(), any());
    }

    @Test
    @DisplayName("A2 update: AYNI team_id'yi geri göndermek taşıma değil → 200 (yalnız oluşturan takım kapsamındaki kayıt dâhil)")
    void update_sameTeamIdResent_200() throws Exception {
        allowManage();
        IncidentRecord rec = recOfTeam(1L, 99L);   // sahibi takım 99; çağıran onu OLUŞTURAN takım (7) üzerinden görüyor
        rec.setCreatedByTeamId(7L);
        when(service.get(1L)).thenReturn(rec);
        when(service.update(eq(1L), any(), any())).thenReturn(rec);
        mvc.perform(put("/api/incidents/1").session(userSessionTeam(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"duzeltme\",\"team_id\":\"99\"}"))
                .andExpect(status().isOk());
        verify(service).update(eq(1L), any(), any());
    }
    // -- 7/24 izleme ekibi operatörü (2026-10-04): olay kayıtlarını TÜM takımlar için okur, yazamaz -----------------

    private MockHttpSession nocOperatorSession() {
        MockHttpSession s = userSessionTeam(5L);
        s.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        return s;
    }

    @Test
    @DisplayName("7/24 operatörü: başka takımın olay kaydını okur (sıradan üye 403); düzenleme/aktarma 403 — yazma sınırı KENDİ takımı")
    void nocOperator_readsForeignIncidentButCannotWrite() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        allowManage();
        IncidentRecord rec = sample();
        rec.setTeamId(99L);
        when(service.get(1L)).thenReturn(rec);

        mvc.perform(get("/api/incidents/1").session(nocOperatorSession())).andExpect(status().isOk());
        mvc.perform(get("/api/incidents/1").session(userSessionTeam(5L))).andExpect(status().isForbidden());

        mvc.perform(put("/api/incidents/1").session(nocOperatorSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/incidents/transfer").session(nocOperatorSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"team_id\":5,\"team_name\":\"Takim A\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).transfer(anyList(), any(), any(), any());
        assertThat(IncidentController.canReadIncident(nocOperatorSession(), 99L, null)).isTrue();
        assertThat(IncidentController.inOwnIncidentScope(nocOperatorSession(), 99L, null)).isFalse();
    }

    // -- Kesinti alanları (2026-11-20): tech_error_count / outage_related / outage_id ---------------------------------
    // Üçü de isteğe bağlı; DTO anahtarları HER ZAMAN taşır (girilmemiş = null — "bilinmiyor", "Hayır" değil).

    @Test
    @DisplayName("Kesinti alanları: DTO üç anahtarı her zaman taşır — girilmemişse null, girilmişse değeriyle (liste + ayrıntı)")
    void outageFields_dtoKeysAlwaysPresent() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        IncidentRecord unset = sample();
        IncidentRecord yes = sample();
        yes.setId(2L);
        yes.setTechErrorCount(1_000_000_000_000_000L);
        yes.setOutageRelated(Boolean.TRUE);
        yes.setOutageId("INC-2026-0915");
        IncidentRecord no = sample();
        no.setId(3L);
        no.setTechErrorCount(0L);
        no.setOutageRelated(Boolean.FALSE);
        when(service.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(unset, yes, no)));

        mvc.perform(get("/api/incidents").session(adminSession()))
                .andExpect(status().isOk())
                // girilmemiş: anahtar VAR, değer null
                .andExpect(jsonPath("$.data[0].tech_error_count").hasJsonPath())
                .andExpect(jsonPath("$.data[0].tech_error_count").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].outage_related").hasJsonPath())
                .andExpect(jsonPath("$.data[0].outage_related").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].outage_id").hasJsonPath())
                .andExpect(jsonPath("$.data[0].outage_id").value(org.hamcrest.Matchers.nullValue()))
                // Evet + kimlik + üst sınırdaki adet (JSON sayısı — metin değil)
                .andExpect(jsonPath("$.data[1].tech_error_count").value(1_000_000_000_000_000L))
                .andExpect(jsonPath("$.data[1].outage_related").value(true))
                .andExpect(jsonPath("$.data[1].outage_id").value("INC-2026-0915"))
                // Hayır + 0: ikisi de "girilmiş" değerdir (null değil)
                .andExpect(jsonPath("$.data[2].tech_error_count").value(0))
                .andExpect(jsonPath("$.data[2].outage_related").value(false))
                .andExpect(jsonPath("$.data[2].outage_id").hasJsonPath())
                .andExpect(jsonPath("$.data[2].outage_id").value(org.hamcrest.Matchers.nullValue()));

        when(service.get(2L)).thenReturn(yes);
        mvc.perform(get("/api/incidents/2").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tech_error_count").value(1_000_000_000_000_000L))
                .andExpect(jsonPath("$.data.outage_related").value(true))
                .andExpect(jsonPath("$.data.outage_id").value("INC-2026-0915"));
        when(service.get(1L)).thenReturn(unset);
        mvc.perform(get("/api/incidents/1").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tech_error_count").hasJsonPath())
                .andExpect(jsonPath("$.data.outage_related").hasJsonPath())
                .andExpect(jsonPath("$.data.outage_related").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.outage_id").hasJsonPath());
    }

    @Test
    @DisplayName("Kesinti alanları: servis alan hatası → 400 VALIDATION_FAILED + field + fields (POST ve PUT); denetim kaydı ve mail YOK")
    void outageFields_fieldErrors_400() throws Exception {
        allowManage();
        when(service.get(1L)).thenReturn(sample());
        for (String field : List.of("tech_error_count", "outage_related", "outage_id")) {
            String message = "Alan hatası: " + field + " — değeri düzeltip yeniden kaydedin.";
            var ex = new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException(field, message);
            // doThrow: when(...).thenThrow yeniden kurulurken ÖNCEKİ turun istisnasını fırlatırdı
            doThrow(ex).when(service).create(any(), any(), any(), any());
            doThrow(ex).when(service).update(eq(1L), any(), any());

            mvc.perform(post("/api/incidents").session(adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"x\",\"occurred_at\":\"2026-11-20T10:00:00\",\"send_notification\":true,"
                                    + "\"tech_error_count\":\"abc\",\"outage_related\":\"evet\",\"outage_id\":7}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value(field))
                    .andExpect(jsonPath("$.fields." + field).value(message))
                    .andExpect(jsonPath("$.error").value(message));
            mvc.perform(put("/api/incidents/1").session(adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"send_notification\":true,\"tech_error_count\":-1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value(field))
                    .andExpect(jsonPath("$.fields." + field).value(message));
        }
        verify(auditService, never()).recordAction(eq("INCIDENT_CREATE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), any());
        verify(auditService, never()).recordAction(eq("INCIDENT_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), any(), any(), any());
        verify(notificationService, never()).notifyIncident(any(), any());
    }

    @Test
    @DisplayName("Kesinti alanları: gövde servise OLDUĞU GİBİ gider — gönderilen anahtar (açık null dâhil) var, gönderilmeyen yok")
    @SuppressWarnings("unchecked")
    void outageFields_bodyReachesServiceAsSent() throws Exception {
        allowManage();
        when(service.create(any(), any(), any(), any())).thenReturn(sample());
        when(service.get(1L)).thenReturn(sample());
        when(service.update(eq(1L), any(), any())).thenReturn(sample());

        mvc.perform(post("/api/incidents").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"occurred_at\":\"2026-11-20T10:00:00\","
                                + "\"tech_error_count\":1250,\"outage_related\":true,\"outage_id\":\"INC-1\"}"))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> created = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(service).create(created.capture(), any(), any(), any());
        assertThat(created.getValue())
                .containsEntry("tech_error_count", 1250)
                .containsEntry("outage_related", Boolean.TRUE)
                .containsEntry("outage_id", "INC-1");

        // Açık null = "temizle": anahtar haritada null değerle VAR; gönderilmeyen anahtar haritada YOK (= dokunma)
        mvc.perform(put("/api/incidents/1").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"tech_error_count\":null,\"outage_related\":null}"))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> updated = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(service).update(eq(1L), updated.capture(), any());
        assertThat(updated.getValue()).containsKey("tech_error_count").containsKey("outage_related").doesNotContainKey("outage_id");
        assertThat(updated.getValue().get("tech_error_count")).isNull();
        assertThat(updated.getValue().get("outage_related")).isNull();
    }

    @Test
    @DisplayName("Kesinti alanları: bildirime verilen DTO üç anahtarı taşır (posta satırları buradan okunur); yanıt da aynı değerleri döner")
    @SuppressWarnings("unchecked")
    void outageFields_notificationDtoCarriesThem() throws Exception {
        allowManage();
        IncidentRecord saved = sample();
        saved.setTechErrorCount(1250L);
        saved.setOutageRelated(Boolean.TRUE);
        saved.setOutageId("INC-2026-0915");
        when(service.create(any(), any(), any(), any())).thenReturn(saved);

        mvc.perform(post("/api/incidents").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"occurred_at\":\"2026-11-20T10:00:00\",\"send_notification\":true,"
                                + "\"tech_error_count\":1250,\"outage_related\":true,\"outage_id\":\"INC-2026-0915\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tech_error_count").value(1250))
                .andExpect(jsonPath("$.data.outage_related").value(true))
                .andExpect(jsonPath("$.data.outage_id").value("INC-2026-0915"));
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> dto = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(notificationService).notifyIncident(dto.capture(), eq("NEW"));
        assertThat(dto.getValue())
                .containsEntry("tech_error_count", 1250L)
                .containsEntry("outage_related", Boolean.TRUE)
                .containsEntry("outage_id", "INC-2026-0915");

        // Girilmemiş kayıtta anahtarlar yine VAR (null) — posta bu durumda satır basmaz
        when(service.create(any(), any(), any(), any())).thenReturn(sample());
        mvc.perform(post("/api/incidents").session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"occurred_at\":\"2026-11-20T10:00:00\",\"send_notification\":true}"))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> plain = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(notificationService, times(2)).notifyIncident(plain.capture(), eq("NEW"));
        java.util.Map<String, Object> last = plain.getAllValues().get(1);
        assertThat(last).containsKey("tech_error_count").containsKey("outage_related").containsKey("outage_id");
        assertThat(last.get("tech_error_count")).isNull();
        assertThat(last.get("outage_related")).isNull();
        assertThat(last.get("outage_id")).isNull();
    }
}
