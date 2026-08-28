package com.sitemonitor.controller;

import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    MockMvc mvc;

    /** Bildirim gruplari: dilim baglami icin gerekli; stub YOK -> "hic grup yok" (birinci yasa). */
    @MockitoBean
    com.sitemonitor.repository.UserPushDeliveryRepository userPushDeliveryRepo;

    @MockitoBean com.sitemonitor.repository.NotificationGroupRepository notificationGroupRepo;
    @MockitoBean
    RememberMeService rememberMeService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    AuthController authController;

    @MockitoBean
    com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean
    CertificateInventoryRepository inventoryRepo;

    @MockitoBean
    AlertThresholdRepository thresholdRepo;

    @MockitoBean
    EscalationContactRepository contactRepo;

    @MockitoBean
    AlertEventRepository alertEventRepo;

    @MockitoBean
    EscalationService escalationService;

    @MockitoBean
    NotificationLogRepository notificationLogRepo;

    @MockitoBean
    com.sitemonitor.repository.LatestCheckRepository latestCheckRepo;

    @MockitoBean
    com.sitemonitor.repository.CertificateCheckRepository certificateCheckRepo;

    @MockitoBean
    AuditService auditService;

    @MockitoBean
    com.sitemonitor.service.MonitorHistoryService monitorHistory;

    @MockitoBean
    com.sitemonitor.service.SsrfGuard ssrfGuard;

    @MockitoBean
    CertificateNoteRepository noteRepo;

    @MockitoBean
    com.sitemonitor.repository.CertificateNoteRevisionRepository noteRevisionRepo;

    @MockitoBean
    AppUserRepository userRepo;

    @MockitoBean
    com.sitemonitor.repository.TeamRepository teamRepo;

    @MockitoBean
    com.sitemonitor.service.EmailNotificationService emailNotificationService;

    @MockitoBean
    com.sitemonitor.service.ConnectionDiagnosticsService diagnosticsService;

    @MockitoBean
    com.sitemonitor.service.OpensslDiagnosticsService opensslDiagnosticsService;

    @MockitoBean
    com.sitemonitor.service.NetworkDiagnosticsService networkDiagnosticsService;

    @MockitoBean
    com.sitemonitor.service.HstsDiagnosticsService hstsDiagnosticsService;

    @MockitoBean
    com.sitemonitor.service.DiagnosticHistoryService diagnosticHistoryService;

    @MockitoBean
    com.sitemonitor.service.DomainExpiryDiagnosticsService domainExpiryDiagnosticsService;

    @MockitoBean
    com.sitemonitor.service.DomainExpiryRefreshService domainExpiryRefreshService;

    @MockitoBean
    com.sitemonitor.service.ProxyCaExportService proxyCaExportService;

    @MockitoBean
    com.sitemonitor.service.PublicSuffixService publicSuffixService;

    @MockitoBean
    com.sitemonitor.service.ClientIpResolver clientIpResolver;

    @MockitoBean
    com.sitemonitor.service.PermissionService permissionService;

    @MockitoBean
    com.sitemonitor.service.MonitoringGroupService monitoringGroupService;

    @MockitoBean
    com.sitemonitor.service.SchedulerService schedulerService;

    @BeforeEach
    void setup() {
        when(userService.listTeams()).thenReturn(java.util.Collections.emptyList());
        // Default stub: any user lookup returns a generic AppUser with id=arg and team=1.
        // ADMIN session bypasses team scoping; individual tests can override as needed.
        when(userRepo.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            AppUser u = new AppUser();
            u.setId(id);
            u.setUsername("stub" + id);
            u.setTeamId(1L);
            return Optional.of(u);
        });
        // createUser/updateUser persist AD profile fields via a follow-up save → echo the entity.
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        // isTeamAdmin(session) artık permissionService.allows(system.team_admin/global_admin) ile çözülür
        // (eski systemRole-attribute fallback'i yok) → rol-bazlı stub: ADMIN+TEAM_ADMIN team_admin'e,
        // yalnız ADMIN global_admin'e sahip. Diğer izin kontrolleri (require) mock'ta no-op.
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("system.team_admin"), eq("execute")))
                .thenAnswer(inv -> {
                    Object r = ((jakarta.servlet.http.HttpSession) inv.getArgument(0)).getAttribute("systemRole");
                    return "ADMIN".equals(r) || "TEAM_ADMIN".equals(r);
                });
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("system.global_admin"), eq("execute")))
                .thenAnswer(inv -> "ADMIN".equals(((jakarta.servlet.http.HttpSession) inv.getArgument(0)).getAttribute("systemRole")));
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
    @DisplayName("GET /api/admin/inventory/by-domain returns the record for an existing domain")
    void getInventoryByDomain_returns200() throws Exception {
        when(inventoryRepo.findByDomain("example.com")).thenReturn(Optional.of(inventory("example.com")));

        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.domain").value("example.com"));
    }

    @Test
    @DisplayName("GET /api/admin/inventory/by-domain returns no record for an unknown domain")
    void getInventoryByDomain_unknownDomain_returnsNoRecord() throws Exception {
        when(inventoryRepo.findByDomain("nope.com")).thenReturn(Optional.empty());

        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "nope.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.domain").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/admin/inventory/by-domain without auth returns 401")
    void getInventoryByDomain_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "example.com"))
                .andExpect(status().isUnauthorized());
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
    @DisplayName("PUT /api/admin/inventory/{id} deactivating (active true→false) closes open alerts")
    void updateInventory_deactivate_closesAlerts() throws Exception {
        CertificateInventory existing = inventory("dom.com"); // active=true
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(escalationService.closeAlertsOnDeactivate("dom.com")).thenReturn(4);

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"dom.com\",\"port\":443,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsClosed").value(4));

        org.mockito.Mockito.verify(escalationService).closeAlertsOnDeactivate("dom.com");
    }

    @Test
    @DisplayName("PUT /api/admin/inventory/{id} staying active does NOT close alerts")
    void updateInventory_stayActive_noAlertClose() throws Exception {
        CertificateInventory existing = inventory("dom2.com"); // active=true
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"dom2.com\",\"port\":443,\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsClosed").value(0));

        org.mockito.Mockito.verify(escalationService, org.mockito.Mockito.never())
                .closeAlertsOnDeactivate(org.mockito.ArgumentMatchers.anyString());
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
    @DisplayName("PUT /api/admin/inventory/{id} persists tls_mode override")
    void updateInventory_tlsMode_persisted() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"old.com\",\"port\":443,\"active\":true,\"tls_mode\":\"default\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tls_mode").value("default"));
    }

    // ── Sorumlu Ekipler ───────────────────────────────────────────────────────

    /**
     * SETTER TUZAGI — CLAUDE.md'deki 1 numarali envanter bug'i.
     *
     * <p>{@code updateInventory}'de setter unutulursa istek 200 doner ve form "kaydedildi" der,
     * ama deger entity'ye HIC yazilmaz: sayfa yenilendiginde alan bos gelir. Bu test yaniti degil
     * KAYDEDILEN NESNEYI dogrular; dort setterdan biri silinirse kirmiziya doner.
     */
    @Test
    @DisplayName("PUT inventory: dort sorumlu ekip alani da ENTITY'ye yazilir (setter tuzagi)")
    void updateInventory_contacts_persistedOnEntity() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"old.com\",\"port\":443,\"active\":true,"
                               + "\"svc_mgmt_contact\":\"Ad Soyad - ad.soyad@example.com\","
                               + "\"app_dev_contact\":\"ekip@example.com\","
                               + "\"iis_admin_contact\":\"iis@example.com\","
                               + "\"waf_admin_contact\":\"waf@example.com\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CertificateInventory> cap = ArgumentCaptor.forClass(CertificateInventory.class);
        verify(inventoryRepo, atLeastOnce()).save(cap.capture());
        CertificateInventory saved = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(saved.getSvcMgmtContact()).isEqualTo("Ad Soyad - ad.soyad@example.com");
        assertThat(saved.getAppDevContact()).isEqualTo("ekip@example.com");
        assertThat(saved.getIisAdminContact()).isEqualTo("iis@example.com");
        assertThat(saved.getWafAdminContact()).isEqualTo("waf@example.com");
    }

    @Test
    @DisplayName("Toplu atama: YALNIZ gonderilen alan yazilir, otekilere DOKUNULMAZ")
    void bulkSetContacts_onlyTouchesSuppliedFields() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        existing.setSvcMgmtContact("onceki@example.com");
        existing.setAppDevContact("dokunma@example.com");
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"action\":\"set-contacts\","
                               + "\"svc_mgmt_contact\":\"yeni@example.com\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CertificateInventory> cap = ArgumentCaptor.forClass(CertificateInventory.class);
        verify(inventoryRepo, atLeastOnce()).save(cap.capture());
        CertificateInventory saved = cap.getValue();
        assertThat(saved.getSvcMgmtContact()).isEqualTo("yeni@example.com");
        // Govdede GONDERILMEYEN alan silinmez: "yalniz IISAdmin'i doldur, 200 kayda uygula"
        // istegi otekileri sessizce bosaltsaydi bu bir veri kaybi olurdu.
        assertThat(saved.getAppDevContact()).isEqualTo("dokunma@example.com");
    }

    /**
     * BAGLAMA KAPISI — envanter ucu {@code @RequestBody CertificateInventory} ile bagliyor ve
     * Jackson {@code spring.jackson.property-naming-strategy=SNAKE_CASE} altinda calisiyor.
     * Dolayisiyla JSON anahtarlari SNAKE_CASE olmak zorunda.
     *
     * <p>Bu kapi neden gerekliydi: frontend uc alani camelCase gonderiyordu
     * ({@code expectedFingerprint}, {@code expectedSubject}, {@code notificationGroupId}).
     * Jackson bilinmeyen anahtari SESSIZCE atiyor, alan null bagli kaliyor ve
     * {@code updateInventory} bunu mevcut kaydin UZERINE yaziyordu. Yani alanlar formdan hic
     * kaydedilemiyor, ustelik her duzenlemede mevcut degeri SILINIYORDU. Frontend testi payload
     * SEKLINI dogruluyordu ama ucun onu KABUL ETTIGINI dogrulamiyordu; bu yuzden hata gorunmedi.
     *
     * <p>Monitor uclari bu kurala TABI DEGIL: onlar {@code @RequestBody Map} alip anahtari duz
     * okuyor ({@code body.get("notificationGroupId")}), orada camelCase dogrudur.
     */
    @Test
    @DisplayName("PUT inventory: snake_case anahtarlar BAGLANIR (camelCase sessizce dusuyordu)")
    void updateInventory_snakeCaseKeys_bind() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        existing.setTeamId(7L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"old.com\",\"port\":443,\"active\":true,"
                               + "\"expected_fingerprint\":\"AA:BB:CC\","
                               + "\"expected_subject\":\"CN=old.com\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CertificateInventory> cap = ArgumentCaptor.forClass(CertificateInventory.class);
        verify(inventoryRepo, atLeastOnce()).save(cap.capture());
        CertificateInventory saved = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(saved.getExpectedFingerprint()).isEqualTo("AA:BB:CC");
        assertThat(saved.getExpectedSubject()).isEqualTo("CN=old.com");
    }

    @Test
    @DisplayName("PUT inventory: camelCase anahtar BAGLANMAZ — kural belgelenir, sessiz kalmaz")
    void updateInventory_camelCaseKeys_doNotBind() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"old.com\",\"port\":443,\"active\":true,"
                               + "\"expectedFingerprint\":\"CAMEL\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CertificateInventory> cap = ArgumentCaptor.forClass(CertificateInventory.class);
        verify(inventoryRepo, atLeastOnce()).save(cap.capture());
        // Bu davranis Jackson'in kendisi; testin amaci onu DEGISTIRMEK degil, bir daha kimse
        // "camelCase de calisiyordur" varsayimina dusmesin diye YAZILI hale getirmek.
        assertThat(cap.getAllValues().get(cap.getAllValues().size() - 1).getExpectedFingerprint()).isNull();
    }

    @Test
    @DisplayName("PUT inventory: bildirim grubu snake_case ile baglanir ve SAHIPLIK dogrulanir")
    void updateInventory_notificationGroup_bindsAndValidatesOwnership() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        existing.setTeamId(7L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.sitemonitor.model.NotificationGroup g = new com.sitemonitor.model.NotificationGroup();
        g.setId(50L); g.setTeamId(7L); g.setActive(true); g.setName("Nobet");
        when(notificationGroupRepo.findById(50L)).thenReturn(Optional.of(g));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"old.com\",\"port\":443,\"active\":true,"
                               + "\"notification_group_id\":50}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CertificateInventory> cap = ArgumentCaptor.forClass(CertificateInventory.class);
        verify(inventoryRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(cap.getAllValues().size() - 1).getNotificationGroupId()).isEqualTo(50L);
    }

    @Test
    @DisplayName("POST inventory: BASKA takimin bildirim grubu kaydedilmez (olusturma yolu)")
    void addInventory_foreignNotificationGroup_isDropped() throws Exception {
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        com.sitemonitor.model.NotificationGroup foreign = new com.sitemonitor.model.NotificationGroup();
        foreign.setId(99L); foreign.setTeamId(42L); foreign.setActive(true); foreign.setName("Baska");
        when(notificationGroupRepo.findById(99L)).thenReturn(Optional.of(foreign));

        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"yeni.example.com\",\"port\":443,\"active\":true,"
                               + "\"team_id\":7,\"notification_group_id\":99}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CertificateInventory> cap = ArgumentCaptor.forClass(CertificateInventory.class);
        verify(inventoryRepo, atLeastOnce()).save(cap.capture());
        // Gonderim aninda ikinci bir kapi daha var, ama gecersiz deger KAYDEDILMEMELI: arayuzde
        // "alarmlar su gruba gidiyor" diye yanlis gorunurdu.
        assertThat(cap.getAllValues().get(0).getNotificationGroupId()).isNull();
    }

    @Test
    @DisplayName("Toplu atama KENDI denetim adiyla yazilir (silme gibi gorunmez)")
    void bulkSetContacts_auditActionIsNotDelete() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"action\":\"set-contacts\","
                               + "\"svc_mgmt_contact\":\"ekip@example.com\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> act = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(act.capture(), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class),
                ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
        // Denetim kaydinin YANLIS olmasi, hic olmamasindan kotudur: eskiden bu eylem
        // DOMAIN_BULK_DELETE olarak yaziliyor ve olmamis bir silme raporlaniyordu.
        assertThat(act.getValue()).isEqualTo("DOMAIN_BULK_SET_CONTACTS");
    }

    @Test
    @DisplayName("Toplu atama: bilinmeyen action 400 doner")
    void bulk_unknownAction_returns400() throws Exception {
        mvc.perform(post("/api/admin/inventory/bulk")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"action\":\"set-everything\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/admin/inventory/{id} with invalid tls_mode returns 400")
    void updateInventory_invalidTlsMode_returns400() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"old.com\",\"port\":443,\"tls_mode\":\"bogus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── Connection diagnostics ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/admin/diagnostics without auth returns 401")
    void runDiagnostics_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/api/admin/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"example.com\",\"port\":443}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/admin/client-ip-debug: USER 403, ADMIN 200 + resolved")
    void clientIpDebug_adminOnly() throws Exception {
        mvc.perform(get("/api/admin/client-ip-debug").session(userSession()))
                .andExpect(status().isForbidden());

        when(clientIpResolver.debugInfo(any())).thenReturn(Map.of(
                "remote_addr", "172.16.0.51", "resolved", "10.0.0.7"));
        mvc.perform(get("/api/admin/client-ip-debug").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolved").value("10.0.0.7"));
    }

    @Test
    @DisplayName("POST /api/admin/diagnostics as USER returns 403")
    void runDiagnostics_asUser_returns403() throws Exception {
        mvc.perform(post("/api/admin/diagnostics")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"example.com\",\"port\":443}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/diagnostics as ADMIN returns combo matrix")
    void runDiagnostics_asAdmin_returns200() throws Exception {
        when(diagnosticsService.diagnose("example.com", 443)).thenReturn(Map.of(
                "domain", "example.com",
                "port", 443,
                "proxy_configured", false,
                "dns", Map.of("ips", List.of("93.184.216.34"), "elapsed_ms", 5),
                "combos", List.of(Map.of("id", "direct+browser", "status", "ok"))
        ));

        mvc.perform(post("/api/admin/diagnostics")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"example.com\",\"port\":443}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.combos").isArray())
                .andExpect(jsonPath("$.data.combos[0].id").value("direct+browser"));
    }

    @Test
    @DisplayName("POST /api/admin/diagnostics as USER on MONITORED domain returns 200 (izlenen domain açık)")
    void runDiagnostics_asUser_monitoredDomain_returns200() throws Exception {
        com.sitemonitor.model.CertificateInventory monitored = new com.sitemonitor.model.CertificateInventory();
        monitored.setDomain("example.com");
        when(inventoryRepo.findByDomain("example.com")).thenReturn(Optional.of(monitored));
        when(diagnosticsService.diagnose("example.com", 443)).thenReturn(Map.of(
                "domain", "example.com", "port", 443,
                "combos", List.of(Map.of("id", "direct+browser", "status", "ok"))
        ));

        mvc.perform(post("/api/admin/diagnostics")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"example.com\",\"port\":443}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.combos[0].id").value("direct+browser"));
    }

    @Test
    @DisplayName("POST /api/admin/diagnostics with invalid domain returns 400")
    void runDiagnostics_invalidDomain_returns400() throws Exception {
        mvc.perform(post("/api/admin/diagnostics")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"not a domain!\",\"port\":443}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/admin/diagnostics/domain-expiry: URL formatlı girdi host'a normalize edilip kabul edilir")
    void runDomainExpiryDiagnostics_urlInput_normalizedAccepted() throws Exception {
        when(domainExpiryDiagnosticsService.diagnose("www.wingscard.com.tr"))
                .thenReturn(Map.of("domain", "wingscard.com.tr", "steps", List.of()));

        mvc.perform(post("/api/admin/diagnostics/domain-expiry")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"https://www.wingscard.com.tr/\"}"))
                .andExpect(status().isOk());

        // Şema/path soyuldu, subdomain korundu (registrable indirgeme servis içinde yapılır)
        verify(domainExpiryDiagnosticsService).diagnose("www.wingscard.com.tr");
    }

    /**
     * KAYIT sorgusu hedefin ÇÖZÜLMESİNİ gerektirmez.
     *
     * <p>Süre-bitişi tanılaması hedefe BAĞLANMAZ: PSL → IANA bootstrap → registry RDAP →
     * WHOIS zinciriyle registry sunucularına sorar. Bir alan adının A/AAAA kaydı olmayabilir
     * ama KAYDI vardır (apex yayınlanmamış, yalnız {@code www} var — kurumsal alan adlarında
     * çok yaygın). Eskiden SSRF hedef doğrulaması burada da koşuyor ve bu alan adları
     * "çözümlenemeyen host" diye REDDEDİLİYORDU: uyarı çıkıyor ama teşhis hiç yapılmıyordu.
     */
    @Test
    @DisplayName("domain-expiry: A kaydı OLMAYAN alan adı yine de tanılanır (registry sorgusu)")
    void runDomainExpiryDiagnostics_unresolvableDomainStillDiagnosed() throws Exception {
        // Biçimi geçerli ama .invalid TLD'si gereği ASLA çözülmeyen ad — burada REDDEDİLMEMELİ.
        final String UNRESOLVABLE_DOMAIN = "cozulmeyen-host.invalid";
        // KAPI VAKUM OLMASIN: bu dilimde SsrfGuard mock'lu ve validate() varsayılan olarak
        // hiçbir şey yapmaz — kural kaldırılsa bile test yeşil kalırdı (mutasyonla ölçüldü).
        // Muhafız burada ÇAĞRILIRSA patlayacak şekilde kuruluyor: uç onu çağırmamalı.
        org.mockito.Mockito.doThrow(new com.sitemonitor.service.SsrfGuard.UnresolvableHostException(
                        "çözümlenemeyen host: " + UNRESOLVABLE_DOMAIN))
                .when(ssrfGuard).validate(UNRESOLVABLE_DOMAIN);
        when(domainExpiryDiagnosticsService.diagnose(UNRESOLVABLE_DOMAIN))
                .thenReturn(Map.of("domain", UNRESOLVABLE_DOMAIN,
                        "source", "RDAP", "expiry_date", "2030-01-01T00:00:00Z", "steps", List.of()));

        mvc.perform(post("/api/admin/diagnostics/domain-expiry")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"" + UNRESOLVABLE_DOMAIN + "\"}"))
                .andExpect(status().isOk());

        verify(domainExpiryDiagnosticsService).diagnose(UNRESOLVABLE_DOMAIN);
    }

    @Test
    @DisplayName("POST /api/admin/diagnostics/openssl as ADMIN returns probe; USER 403")
    void runOpenssl_adminAndUser() throws Exception {
        when(opensslDiagnosticsService.probe("example.com", 443)).thenReturn(Map.of(
                "available", true, "version", "OpenSSL 3.0",
                "protocols", List.of(Map.of("proto", "TLSv1.0", "supported", true, "risk", "HIGH"))
        ));

        mvc.perform(post("/api/admin/diagnostics/openssl")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"example.com\",\"port\":443}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.protocols[0].proto").value("TLSv1.0"));

        mvc.perform(post("/api/admin/diagnostics/openssl")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"example.com\",\"port\":443}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/diagnostics/network as ADMIN returns checks; USER 403")
    void runNetwork_adminAndUser() throws Exception {
        when(networkDiagnosticsService.analyze("example.com", 443)).thenReturn(Map.of(
                "domain", "example.com", "os", "linux", "ok_count", 5, "total", 8,
                "checks", List.of(Map.of("key", "tcp", "label", "TCP 443", "status", "ok",
                        "summary", "Port açık (12 ms)"))
        ));

        mvc.perform(post("/api/admin/diagnostics/network")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"example.com\",\"port\":443}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checks[0].key").value("tcp"))
                .andExpect(jsonPath("$.data.checks[0].status").value("ok"));

        mvc.perform(post("/api/admin/diagnostics/network")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"example.com\",\"port\":443}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/diagnostics/history returns records for domain")
    void diagnosticsHistory_returnsList() throws Exception {
        com.sitemonitor.model.DiagnosticRun d = new com.sitemonitor.model.DiagnosticRun();
        d.setId(3L); d.setDomain("example.com"); d.setRunType("OPENSSL");
        d.setExecutedBy("admin"); d.setExecutedAt("2026-06-12T10:00:00");
        d.setSourceIp("10.0.0.5"); d.setSuccess(true); d.setSummary("Zayıf protokol yok");
        when(diagnosticHistoryService.history("example.com")).thenReturn(List.of(d));

        mvc.perform(get("/api/admin/diagnostics/history?domain=example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].run_type").value("OPENSSL"))
                .andExpect(jsonPath("$.data[0].executed_by").value("admin"))
                .andExpect(jsonPath("$.data[0].source_ip").value("10.0.0.5"))
                .andExpect(jsonPath("$.data[0].success").value(true));
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

    // ── Bulk inventory actions ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/admin/inventory/bulk deactivate → counts + closes alerts (not delete)")
    void bulkInventory_deactivate_returns200() throws Exception {
        CertificateInventory a = inventory("a.com"); a.setId(1L);
        CertificateInventory b = inventory("b.com"); b.setId(2L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(a));
        when(inventoryRepo.findById(2L)).thenReturn(Optional.of(b));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(escalationService.closeAlertsOnDeactivate("a.com")).thenReturn(2);
        when(escalationService.closeAlertsOnDeactivate("b.com")).thenReturn(0);

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deactivate\",\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.processed").value(2))
                .andExpect(jsonPath("$.data.skipped").value(0))
                .andExpect(jsonPath("$.data.alertsClosed").value(2));

        org.mockito.Mockito.verify(inventoryRepo, org.mockito.Mockito.times(2)).save(any());
        // Pasife alma alarmları kapatır ama domain'i SİLMEZ (soft-delete tetiklenmez)
        org.mockito.Mockito.verify(escalationService).closeAlertsOnDeactivate("a.com");
        org.mockito.Mockito.verify(escalationService).closeAlertsOnDeactivate("b.com");
        org.mockito.Mockito.verify(escalationService, org.mockito.Mockito.never())
                .closeAlertsOnInventoryDelete(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("POST /api/admin/inventory/bulk activate skips soft-deleted rows")
    void bulkInventory_activate_skipsDeleted() throws Exception {
        CertificateInventory live = inventory("live.com"); live.setId(1L);
        CertificateInventory gone = inventory("gone.com"); gone.setId(2L); gone.setDeletedAt("2026-06-01T00:00:00");
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(live));
        when(inventoryRepo.findById(2L)).thenReturn(Optional.of(gone));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"activate\",\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    @Test
    @DisplayName("POST /api/admin/inventory/bulk delete closes alerts and reports count")
    void bulkInventory_delete_closesAlerts() throws Exception {
        CertificateInventory a = inventory("d1.com"); a.setId(1L);
        CertificateInventory b = inventory("d2.com"); b.setId(2L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(a));
        when(inventoryRepo.findById(2L)).thenReturn(Optional.of(b));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(escalationService.closeAlertsOnInventoryDelete("d1.com")).thenReturn(2);
        when(escalationService.closeAlertsOnInventoryDelete("d2.com")).thenReturn(1);

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"delete\",\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(2))
                .andExpect(jsonPath("$.data.alertsClosed").value(3));

        org.mockito.Mockito.verify(escalationService).closeAlertsOnInventoryDelete("d1.com");
        org.mockito.Mockito.verify(escalationService).closeAlertsOnInventoryDelete("d2.com");
    }

    @Test
    @DisplayName("POST /api/admin/inventory/bulk invalid action → 400")
    void bulkInventory_invalidAction_returns400() throws Exception {
        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"frobnicate\",\"ids\":[1]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/admin/inventory/bulk empty ids → 400")
    void bulkInventory_emptyIds_returns400() throws Exception {
        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deactivate\",\"ids\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/admin/inventory/bulk as TEAM_ADMIN skips out-of-scope rows")
    void bulkInventory_asTeamAdmin_skipsOutOfScope() throws Exception {
        CertificateInventory own   = inventory("own.com");   own.setId(1L);   own.setTeamId(2L);  // managed
        CertificateInventory other = inventory("other.com"); other.setId(2L); other.setTeamId(7L); // not managed
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(own));
        when(inventoryRepo.findById(2L)).thenReturn(Optional.of(other));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk").session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deactivate\",\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    @Test
    @DisplayName("POST /api/admin/inventory/bulk as USER → 403")
    void bulkInventory_asUser_returns403() throws Exception {
        mvc.perform(post("/api/admin/inventory/bulk").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deactivate\",\"ids\":[1]}"))
                .andExpect(status().isForbidden());
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
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    @DisplayName("GET /api/admin/alerts?alertType=ACCESSIBILITY filters by type and returns type_counts")
    void listAlerts_alertTypeFilter_passedToQueryWithCounts() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(),
                eq("ACCESSIBILITY"), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class))).thenReturn(empty);
        when(alertEventRepo.countFilteredByType(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(
                        new Object[]{"EXPIRY", 8L},
                        new Object[]{"ACCESSIBILITY", 2L}));

        mvc.perform(get("/api/admin/alerts?alertType=ACCESSIBILITY").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.type_counts.EXPIRY").value(8))
                .andExpect(jsonPath("$.type_counts.ACCESSIBILITY").value(2));

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(),
                eq("ACCESSIBILITY"), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    // ── Alarm sekmesinin TIP KAPSAMI (alertTypes) ────────────────────────────────────────
    //
    // Izleme modallarindaki "Alarmlar" sekmesi yalniz domain'e gore suzuluyordu: ayni URL'i
    // izleyen HER monitorun alarmi oraya dusuyordu (Sayfa Hizi modalinde HTTP'nin SSL alarmi ve
    // Sayfa Butunlugu alarmi gorunuyordu). Suzme SUNUCUDA yapilmak ZORUNDA -- istemcide suzmek
    // yalniz acik sayfayi suzer; sayfalama, tip cipleri ve seviye sayaclari yanlis kalirdi.

    @Test
    @DisplayName("GET /api/admin/alerts?alertTypes=A,B → sorguya TIP KAPSAMI gecer")
    void listAlerts_alertTypes_scopesQuery() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts?alertTypes=PAGESPEED_DOWN,PAGESPEED_SLOW").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(),
                eq(true), eq(List.of("PAGESPEED_DOWN", "PAGESPEED_SLOW")),
                any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
        // Tip cipleri de AYNI kapsamda sayilmali; aksi halde ekranda gorunmeyen bir tip icin
        // "SSL Sertifika Sorunu: 1" cipi cikardi.
        org.mockito.Mockito.verify(alertEventRepo).countFilteredByType(any(), any(), any(), any(), any(), any(),
                eq(true), eq(List.of("PAGESPEED_DOWN", "PAGESPEED_SLOW")),
                any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("alertTypes VERILMEZSE tip kapsami UYGULANMAZ (bagimsiz Alarm Gecmisi ekrani)")
    void listAlerts_noAlertTypes_noTypeScope() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts").session(authSession())).andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(),
                eq(false), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("alertTypes SERBEST METIN degil: gecersiz jetonlar elenir, gecerliler kalir")
    void listAlerts_alertTypes_sanitised() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        // Bosluk/kucuk harf normalize edilir; tirnak-noktali virgul tasiyan jeton ATILIR.
        mvc.perform(get("/api/admin/alerts?alertTypes= page_down , DROP;TABLE ,PAGE_DOWN")
                        .session(authSession()))
                .andExpect(status().isOk());

        // Tekrar eden PAGE_DOWN bir kez; "DROP;TABLE" elendi.
        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(),
                eq(true), eq(List.of("PAGE_DOWN")),
                any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("alertTypes VERILIP hicbir gecerli tip kalmazsa sonuc BOS doner (fail-closed)")
    void listAlerts_alertTypes_allInvalid_failsClosed() throws Exception {
        // ONEMLI: kapsam parametrenin VERILIP VERILMEDIGINE bakar, dogrulamadan kacinin sag
        // ciktigina DEGIL. Aksi halde gecersiz bir tip adi (yeniden adlandirma, yazim hatasi)
        // suzgeci SESSIZCE dusurur ve modal yine kendi uretmedigi alarmlari gosterir -- yani
        // kullanicinin bildirdigi hata geri gelir. Gorunur bir bos liste, sessiz bir sizintidan
        // iyidir.
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts?alertTypes=DROP;TABLE").session(authSession()))
                .andExpect(status().isOk());

        // typeScoped=TRUE + hicbir seye uymayan sentinel liste → sorgu bos doner.
        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(),
                eq(true), eq(List.of("-")),
                any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/admin/alerts without alertType passes null to query")
    void listAlerts_noAlertType_passesNull() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts").session(authSession()))
                .andExpect(status().isOk());

        // alertType + dort YENI filtre (q/level/acknowledged/teamId) verilmediginde hepsi NULL
        // gitmeli: bos string ya da "" gecerse sorgu her seyi eler ve ekran bos gorunur.
        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(),
                isNull(), anyBoolean(), any(), isNull(), isNull(), isNull(), isNull(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/admin/alerts?onlyOpen=true returns only open alerts")
    void listAlerts_onlyOpen_returnsOpenAlerts() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(eq(Boolean.FALSE), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
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

        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
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

        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
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

    /** O8: takım yöneticisi id deneyerek HERHANGİ takımdaki kullanıcının fotoğrafını çekebiliyordu. */
    @Test
    @DisplayName("O8 IDOR: GET /users/{id}/photo — kapsam dışı kullanıcıya 404, kapsam içine 200")
    void userPhoto_scopedByViewTeams() throws Exception {
        com.sitemonitor.model.AppUser target = new com.sitemonitor.model.AppUser();
        target.setId(9L); target.setUsername("n00001"); target.setTeamId(2L);
        // 1x1 JPEG'e gerek yok — photoResponse yalnız base64 decode eder.
        target.setPhotoBase64(java.util.Base64.getEncoder().encodeToString(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
        org.mockito.Mockito.when(userRepo.findById(9L)).thenReturn(java.util.Optional.of(target));

        // Kapsamı takım 1 olan takım yöneticisi → hedef takım 2 → 404 (403 varlık sızdırırdı).
        org.springframework.mock.web.MockHttpSession scoped = teamAdminSession();
        scoped.setAttribute("viewTeamIds", java.util.List.of(1L));
        mvc.perform(get("/api/admin/users/9/photo").session(scoped))
                .andExpect(status().isNotFound());

        // Kapsamına takım 2 girince → 200.
        org.springframework.mock.web.MockHttpSession inScope = teamAdminSession();
        inScope.setAttribute("viewTeamIds", java.util.List.of(1L, 2L));
        mvc.perform(get("/api/admin/users/9/photo").session(inScope))
                .andExpect(status().isOk());
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
        when(escalationService.acknowledge(anyLong(), any(), any())).thenReturn(event);

        mvc.perform(post("/api/admin/alerts/1/acknowledge")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"planlı bakım kapsamında kapatıldı\"}"))
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
        when(escalationService.resolve(eq(2L), any(), any())).thenReturn(event);

        mvc.perform(post("/api/admin/alerts/2/resolve").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"düzeltme devrede doğrulandı\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── Bulk alert actions ────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/admin/alerts/bulk resolve → processed count + resolve called per id")
    void bulkAlert_resolve_returns200() throws Exception {
        AlertEvent ev = new AlertEvent(); ev.setId(1L); ev.setResolved(true);
        when(escalationService.resolve(anyLong(), any(), any())).thenReturn(ev);

        mvc.perform(post("/api/admin/alerts/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"resolve\",\"ids\":[1,2],\"note\":\"toplu çözüm gerekçesi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.processed").value(2))
                .andExpect(jsonPath("$.data.skipped").value(0))
                .andExpect(jsonPath("$.data.failed").value(0));

        org.mockito.Mockito.verify(escalationService).resolve(eq(1L), any(), any());
        org.mockito.Mockito.verify(escalationService).resolve(eq(2L), any(), any());
    }

    @Test
    @DisplayName("POST /api/admin/alerts/bulk acknowledge → acknowledge called per id")
    void bulkAlert_acknowledge_returns200() throws Exception {
        AlertEvent ev = new AlertEvent(); ev.setId(3L);
        when(escalationService.acknowledge(anyLong(), any(), any())).thenReturn(ev);

        mvc.perform(post("/api/admin/alerts/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"acknowledge\",\"ids\":[3],\"note\":\"bilinen sorun takip ediliyor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1));
        org.mockito.Mockito.verify(escalationService).acknowledge(eq(3L), any(), any());
    }

    @Test
    @DisplayName("POST /api/admin/alerts/bulk one failing id → counted as failed, batch continues")
    void bulkAlert_partialFailure_counted() throws Exception {
        AlertEvent ev = new AlertEvent(); ev.setId(1L); ev.setResolved(true);
        when(escalationService.resolve(eq(1L), any(), any())).thenReturn(ev);
        when(escalationService.resolve(eq(2L), any(), any())).thenThrow(new java.util.NoSuchElementException("gone"));

        mvc.perform(post("/api/admin/alerts/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"resolve\",\"ids\":[1,2],\"note\":\"toplu çözüm gerekçesi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1))
                .andExpect(jsonPath("$.data.failed").value(1));
    }

    @Test
    @DisplayName("POST /api/admin/alerts/bulk invalid action → 400")
    void bulkAlert_invalidAction_returns400() throws Exception {
        mvc.perform(post("/api/admin/alerts/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"frobnicate\",\"ids\":[1]}"))
                .andExpect(status().isBadRequest());
    }

    // ── Zorunlu gerekçe notu ──────────────────────────────────────────────────
    //
    // Kural SUNUCUDA. Yalnız arayüzde dursaydı kozmetik kalırdı: aşağıdaki istekler tarayıcıdan
    // geçmiyor, doğrudan uca gidiyor — yani "her manuel onayın gerekçesi vardır" garantisini
    // ancak bu testler kilitleyebilir.

    @Test
    @DisplayName("Onay: notsuz istek 400 ve alarma DOKUNULMAZ")
    void acknowledge_withoutNote_returns400_andDoesNotTouchAlert() throws Exception {
        mvc.perform(post("/api/admin/alerts/1/acknowledge").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // Gövde hiç yollanmasa da aynı sonuç.
        mvc.perform(post("/api/admin/alerts/1/acknowledge").session(authSession()))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(escalationService, org.mockito.Mockito.never())
                .acknowledge(anyLong(), any(), any());
    }

    @Test
    @DisplayName("Onay: kuralı geçiştiren not 400 döner (a b c / iki kelime)")
    void acknowledge_weakNote_returns400() throws Exception {
        for (String bad : new String[]{ "a b c", "planlı bakım", "   ", "ok ok ok" }) {
            mvc.perform(post("/api/admin/alerts/1/acknowledge").session(authSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"note\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verify(escalationService, org.mockito.Mockito.never())
                .acknowledge(anyLong(), any(), any());
    }

    @Test
    @DisplayName("Onay: geçerli not SERVİSE iletilir (kırpılmış)")
    void acknowledge_validNote_isPassedToService() throws Exception {
        AlertEvent ev = new AlertEvent(); ev.setId(1L); ev.setDomain("example.com");
        when(escalationService.acknowledge(anyLong(), any(), any())).thenReturn(ev);

        mvc.perform(post("/api/admin/alerts/1/acknowledge").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"  bilinen sorun takip ediliyor  \"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(escalationService)
                .acknowledge(eq(1L), any(), eq("bilinen sorun takip ediliyor"));
    }

    @Test
    @DisplayName("Çözüm: notsuz istek 400")
    void resolve_withoutNote_returns400() throws Exception {
        mvc.perform(post("/api/admin/alerts/2/resolve").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(escalationService, org.mockito.Mockito.never())
                .resolve(anyLong(), any(), any());
    }

    @Test
    @DisplayName("TOPLU onay notsuz 400 — zorunluluğun kaçış yolu kapalı")
    void bulkAcknowledge_withoutNote_returns400() throws Exception {
        // Tek alarmı seçip "toplu onayla" demek, tekli akıştaki zorunluluğu delen en kolay yoldu.
        mvc.perform(post("/api/admin/alerts/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"acknowledge\",\"ids\":[1]}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(escalationService, org.mockito.Mockito.never())
                .acknowledge(anyLong(), any(), any());
    }

    @Test
    @DisplayName("TOPLU onay: TEK not seçilen HER alarma yazılır")
    void bulkAcknowledge_sameNoteWrittenToEveryAlert() throws Exception {
        AlertEvent ev = new AlertEvent(); ev.setId(1L);
        when(escalationService.acknowledge(anyLong(), any(), any())).thenReturn(ev);

        mvc.perform(post("/api/admin/alerts/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"acknowledge\",\"ids\":[1,2,3],"
                               + "\"note\":\"fırtına sonrası toplu kapatma\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(3));

        for (long id : new long[]{ 1L, 2L, 3L }) {
            org.mockito.Mockito.verify(escalationService)
                    .acknowledge(eq(id), any(), eq("fırtına sonrası toplu kapatma"));
        }
    }

    @Test
    @DisplayName("TEKRAR BİLDİR toplu işlemi not İSTEMEZ — orada alarm kapatılmıyor")
    void bulkRenotify_doesNotRequireNote() throws Exception {
        when(escalationService.reNotify(anyLong())).thenReturn(java.util.Map.of("ok", true));

        mvc.perform(post("/api/admin/alerts/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"re-notify\",\"ids\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1));
    }

    @Test
    @DisplayName("Denetim ayrıntısı TIRNAKLI notta da GEÇERLİ JSON üretir")
    void auditDetail_isValidJson_evenWithQuotesInNote() throws Exception {
        // Ayrıntı eskiden elle birleştiriliyordu ("{\"domain\":\"" + domain + "\"}"); gerekçe
        // cümlesi yazan kullanıcı tırnak kullanır ve ilk tırnakta bozuk JSON üretilirdi.
        AlertEvent ev = new AlertEvent(); ev.setId(1L); ev.setDomain("example.com");
        when(escalationService.acknowledge(anyLong(), any(), any())).thenReturn(ev);

        mvc.perform(post("/api/admin/alerts/1/acknowledge").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"\\\"planlı bakım\\\" nedeniyle susturuldu\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<String> detail = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(auditService).recordAction(
                eq("ALERT_ACKNOWLEDGE"), any(), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("ALERT_EVENT"), eq("1"), detail.capture());

        // Ayrıştırılabiliyorsa geçerli; elle birleştirmede burası patlardı.
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(detail.getValue());
        assertThat(parsed.get("domain").asText()).isEqualTo("example.com");
        assertThat(parsed.get("note").asText()).isEqualTo("\"planlı bakım\" nedeniyle susturuldu");
    }

    @Test
    @DisplayName("POST /api/admin/alerts/bulk empty ids → 400")
    void bulkAlert_emptyIds_returns400() throws Exception {
        mvc.perform(post("/api/admin/alerts/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"resolve\",\"ids\":[]}"))
                .andExpect(status().isBadRequest());
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
        ev.setTeamId(2L);   // USER'ın görüntüleme kapsamındaki takım → erişebilir
        when(alertEventRepo.findById(1L)).thenReturn(java.util.Optional.of(ev));
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.empty());
        when(escalationService.acknowledge(eq(1L), any(), any())).thenReturn(ev);

        mvc.perform(post("/api/admin/alerts/1/acknowledge").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"bilinen sorun takip ediliyor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/admin/alerts/{id}/resolve as USER başka takımın alarmı (IDOR) → 403")
    void resolveAlert_asUser_otherTeam_returns403() throws Exception {
        AlertEvent ev = new AlertEvent();
        ev.setId(9L);
        ev.setDomain("orphan.example.com");
        ev.setTeamId(99L);   // USER kapsamı [2] dışı
        when(alertEventRepo.findById(9L)).thenReturn(java.util.Optional.of(ev));
        when(inventoryRepo.findByDomain("orphan.example.com")).thenReturn(java.util.Optional.empty());

        mvc.perform(post("/api/admin/alerts/9/resolve").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/alerts as USER → sorguya takım kapsamı (scoped=true, scope=[2]) geçer")
    void listAlerts_asUser_passesScope() throws Exception {
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(alertEventRepo.countFilteredByType(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(Collections.emptyList());

        mvc.perform(get("/api/admin/alerts").session(userSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                eq(true), eq(java.util.List.of(2L)), any(Pageable.class));
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

    // ── TEAM_ADMIN role gating ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/admin/inventory as TEAM_ADMIN can add to a team it manages")
    void addInventory_asTeamAdmin_inScopeTeam_returns200() throws Exception {
        when(inventoryRepo.save(any())).thenAnswer(inv -> {
            CertificateInventory i = inv.getArgument(0);
            i.setId(99L);
            return i;
        });

        // Faz 3b: PO/TEAM_ADMIN formdan yönetebildiği bir takım seçer (manageTeamIds=[2]).
        mvc.perform(post("/api/admin/inventory")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"x.com\",\"port\":443,\"team_id\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.team_id").value(2));
    }

    @Test
    @DisplayName("POST /api/admin/inventory as TEAM_ADMIN on a team it does NOT manage returns 403")
    void addInventory_asTeamAdmin_outOfScopeTeam_returns403() throws Exception {
        // Faz 3b: yönetim kapsamı dışındaki takıma (999) ekleme reddedilir.
        mvc.perform(post("/api/admin/inventory")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"x.com\",\"port\":443,\"team_id\":999}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/inventory/{id} as TEAM_ADMIN on other-team resource returns 403")
    void updateInventory_asTeamAdmin_otherTeam_returns403() throws Exception {
        CertificateInventory existing = inventory("x.com");
        existing.setId(1L);
        existing.setTeamId(7L);  // belongs to team 7, not the team-admin's team 2
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"x.com\",\"port\":443}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/inventory/{id} as TEAM_ADMIN on own-team resource returns 200")
    void updateInventory_asTeamAdmin_ownTeam_returns200() throws Exception {
        CertificateInventory existing = inventory("x.com");
        existing.setId(1L);
        existing.setTeamId(2L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"x.com\",\"port\":443}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/admin/users as TEAM_ADMIN with ADMIN role payload returns 403")
    void createUser_asTeamAdmin_withAdminRole_returns403() throws Exception {
        mvc.perform(post("/api/admin/users")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"pw\",\"system_role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/users as TEAM_ADMIN with USER role creates in own team")
    void createUser_asTeamAdmin_withUserRole_returns200() throws Exception {
        AppUser created = new AppUser();
        created.setId(7L);
        created.setUsername("newbie");
        created.setTeamId(2L);
        created.setSystemRole("USER");
        when(userService.createUser(any(), any(), any(), any(), any(), eq("USER"), eq(java.util.List.of(2L)), any()))
                .thenReturn(created);

        mvc.perform(post("/api/admin/users")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        // payload team_id=99 must be overridden to 2
                        .content("{\"username\":\"newbie\",\"password\":\"pw\",\"system_role\":\"USER\",\"team_id\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(2));
    }

    @Test
    @DisplayName("PUT /api/admin/teams/{id} as TEAM_ADMIN on other team returns 403")
    void updateTeam_asTeamAdmin_otherTeam_returns403() throws Exception {
        mvc.perform(put("/api/admin/teams/99")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/teams/{id} as TEAM_ADMIN on own team returns 200")
    void updateTeam_asTeamAdmin_ownTeam_returns200() throws Exception {
        Team updated = new Team();
        updated.setId(2L);
        updated.setName("Renamed");
        when(userService.updateTeam(eq(2L), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(updated);

        mvc.perform(put("/api/admin/teams/2")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2));
    }

    // ── Haftalık e-posta anahtarları: takım ÜYELERİNE açık dar uç ─────────────
    // teams.update yetkisi olmayan sıradan USER kendi takımının iki anahtarını çevirebilmeli,
    // ama BAŞKA takımınkini çevirememeli (IDOR) ve ad/e-posta gibi alanlara dokunamamalı.

    private void stubWeeklyToggle() {
        Team t = new Team();
        t.setId(2L);
        t.setName("Dijital");
        t.setWeeklyReminderEnabled(true);
        when(userService.updateTeamWeeklyNotifications(anyLong(), any(), any())).thenReturn(t);
    }

    /** Oturumdaki kullanıcıyı verilen takım(lar)ın üyesi yapar (dar uç app_users'tan doğruluyor). */
    private void stubMembership(long userId, Long primaryTeam, Long... alsoMemberOf) {
        AppUser u = new AppUser();
        u.setId(userId);
        u.setUsername("member" + userId);
        u.setTeamId(primaryTeam);
        u.setTeamIds(new java.util.LinkedHashSet<>(java.util.List.of(alsoMemberOf)));
        when(userRepo.findById(userId)).thenReturn(Optional.of(u));
    }

    @Test
    @DisplayName("PUT /teams/{id}/weekly-notifications: ÜYE olan USER kendi takımının anahtarını çevirir → 200")
    void weeklyNotifications_asMember_returns200() throws Exception {
        stubWeeklyToggle();
        stubMembership(42L, 2L);

        mvc.perform(put("/api/admin/teams/2/weekly-notifications")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_reminder_enabled\":true}"))
                .andExpect(status().isOk());

        verify(userService).updateTeamWeeklyNotifications(eq(2L), eq(Boolean.TRUE), isNull());
    }

    @Test
    @DisplayName("PUT /teams/{id}/weekly-notifications: ÇOK takımlı üye ikincil takımı için de çevirebilir → 200")
    void weeklyNotifications_secondaryMembership_returns200() throws Exception {
        stubWeeklyToggle();
        stubMembership(42L, 2L, 7L);   // birincil 2, ayrıca 7 numaralı takımın da üyesi

        mvc.perform(put("/api/admin/teams/7/weekly-notifications")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_availability_enabled\":false}"))
                .andExpect(status().isOk());

        verify(userService).updateTeamWeeklyNotifications(eq(7L), isNull(), eq(Boolean.FALSE));
    }

    @Test
    @DisplayName("PUT /teams/{id}/weekly-notifications: ÜYESİ OLMADIĞI takım → 403 (IDOR)")
    void weeklyNotifications_foreignTeam_returns403() throws Exception {
        stubWeeklyToggle();
        stubMembership(42L, 2L);   // yalnız 2 numaralı takımın üyesi

        mvc.perform(put("/api/admin/teams/9/weekly-notifications")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_reminder_enabled\":true}"))
                .andExpect(status().isForbidden());

        verify(userService, never()).updateTeamWeeklyNotifications(anyLong(), any(), any());
    }

    @Test
    @DisplayName("PUT /teams/{id}/weekly-notifications: global ADMIN her takım için çevirebilir → 200")
    void weeklyNotifications_asAdmin_anyTeam_returns200() throws Exception {
        stubWeeklyToggle();

        mvc.perform(put("/api/admin/teams/9/weekly-notifications")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_reminder_enabled\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /teams/{id}/weekly-notifications: gövdedeki ad/e-posta/aktiflik YOK SAYILIR (dar uç sızdırmaz)")
    void weeklyNotifications_ignoresAdminOnlyFields() throws Exception {
        stubWeeklyToggle();
        stubMembership(42L, 2L);

        mvc.perform(put("/api/admin/teams/2/weekly-notifications")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_reminder_enabled\":true,\"name\":\"Hacked\",\"active\":false,\"email\":\"x@y.com\"}"))
                .andExpect(status().isOk());

        verify(userService).updateTeamWeeklyNotifications(eq(2L), eq(Boolean.TRUE), isNull());
        verify(userService, never()).updateTeam(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /api/admin/teams as TEAM_ADMIN returns 403 (create stays admin-only)")
    void createTeam_asTeamAdmin_returns403() throws Exception {
        mvc.perform(post("/api/admin/teams")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NewTeam\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/users/{id} as ADMIN changing own role returns 403")
    void updateUser_asAdmin_changingOwnRole_returns403() throws Exception {
        AppUser self = new AppUser();
        self.setId(50L);
        self.setUsername("testuser");
        self.setTeamId(1L);
        self.setSystemRole("ADMIN");
        self.setActive(true);
        when(userRepo.findById(50L)).thenReturn(Optional.of(self));

        MockHttpSession s = authSession();
        s.setAttribute("userId", 50L);

        mvc.perform(put("/api/admin/users/50")
                        .session(s)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system_role\":\"USER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/users/{id} as ADMIN deactivating self returns 403")
    void updateUser_asAdmin_deactivatingSelf_returns403() throws Exception {
        AppUser self = new AppUser();
        self.setId(50L);
        self.setUsername("testuser");
        self.setTeamId(1L);
        self.setSystemRole("ADMIN");
        self.setActive(true);
        when(userRepo.findById(50L)).thenReturn(Optional.of(self));

        MockHttpSession s = authSession();
        s.setAttribute("userId", 50L);

        mvc.perform(put("/api/admin/users/50")
                        .session(s)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/admin/users/{id} as ADMIN deleting self returns 403")
    void deleteUser_asAdmin_deletingSelf_returns403() throws Exception {
        MockHttpSession s = authSession();
        s.setAttribute("userId", 50L);

        mvc.perform(delete("/api/admin/users/50").session(s))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/users/{id} demoting last active ADMIN returns 403")
    void updateUser_demotingLastActiveAdmin_returns403() throws Exception {
        AppUser target = new AppUser();
        target.setId(10L); target.setUsername("admin"); target.setTeamId(1L);
        target.setSystemRole("ADMIN"); target.setActive(true);
        when(userRepo.findById(10L)).thenReturn(Optional.of(target));
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(1L);

        mvc.perform(put("/api/admin/users/10")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system_role\":\"USER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/users/{id} demoting one of two ADMINs returns 200")
    void updateUser_demotingOneOfTwoAdmins_returns200() throws Exception {
        AppUser target = new AppUser();
        target.setId(11L); target.setUsername("admin2"); target.setTeamId(1L);
        target.setSystemRole("ADMIN"); target.setActive(true);
        when(userRepo.findById(11L)).thenReturn(Optional.of(target));
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(2L);

        AppUser updated = new AppUser();
        updated.setId(11L); updated.setUsername("admin2"); updated.setSystemRole("USER");
        when(userService.updateUser(eq(11L), any(), any(), any(), eq("USER"), any(), any(), any()))
                .thenReturn(updated);

        mvc.perform(put("/api/admin/users/11")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system_role\":\"USER\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /api/admin/users/{id} deactivating last active ADMIN returns 403")
    void updateUser_deactivatingLastActiveAdmin_returns403() throws Exception {
        AppUser target = new AppUser();
        target.setId(10L); target.setUsername("admin"); target.setTeamId(1L);
        target.setSystemRole("ADMIN"); target.setActive(true);
        when(userRepo.findById(10L)).thenReturn(Optional.of(target));
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(1L);

        mvc.perform(put("/api/admin/users/10")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/admin/users/{id} deleting last active ADMIN returns 403")
    void deleteUser_lastActiveAdmin_returns403() throws Exception {
        AppUser target = new AppUser();
        target.setId(10L); target.setUsername("admin"); target.setTeamId(1L);
        target.setSystemRole("ADMIN"); target.setActive(true);
        when(userRepo.findById(10L)).thenReturn(Optional.of(target));
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(1L);

        mvc.perform(delete("/api/admin/users/10").session(authSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/admin/users/{id} non-admin user still works")
    void deleteUser_nonAdmin_returns200() throws Exception {
        AppUser target = new AppUser();
        target.setId(20L); target.setUsername("normal"); target.setTeamId(1L);
        target.setSystemRole("USER"); target.setActive(true);
        when(userRepo.findById(20L)).thenReturn(Optional.of(target));

        mvc.perform(delete("/api/admin/users/20").session(authSession()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /api/admin/users/{id} as ADMIN can promote target to ADMIN role")
    void updateUser_asAdmin_promotesToAdmin_returns200() throws Exception {
        AppUser target = new AppUser();
        target.setId(7L);
        target.setUsername("safiye");
        target.setTeamId(1L);
        when(userRepo.findById(7L)).thenReturn(Optional.of(target));

        AppUser promoted = new AppUser();
        promoted.setId(7L);
        promoted.setUsername("safiye");
        promoted.setSystemRole("ADMIN");
        promoted.setTeamId(1L);
        when(userService.updateUser(eq(7L), any(), any(), any(), eq("ADMIN"), any(), any(), any()))
                .thenReturn(promoted);

        mvc.perform(put("/api/admin/users/7")
                        .session(authSession())   // ADMIN session
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system_role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.system_role").value("ADMIN"));
    }

    @Test
    @DisplayName("PUT /users/{id}: ADMIN için boş takım (team_id:null) kullanıcıyı takımdan düşürür")
    void updateUser_admin_clearsTeam() throws Exception {
        // Boş takım → updateUser([]) servis içinde teamId'yi null'lar; mock bunu yansıtsın.
        AppUser updated = new AppUser();
        updated.setId(7L); updated.setUsername("adm"); updated.setSystemRole("ADMIN");
        when(userService.updateUser(eq(7L), any(), any(), any(), eq("ADMIN"), eq(java.util.List.of()), any(), any()))
                .thenReturn(updated);

        mvc.perform(put("/api/admin/users/7")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system_role\":\"ADMIN\",\"email\":\"a@b.com\",\"team_id\":null}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(userRepo)
                .save(org.mockito.ArgumentMatchers.<AppUser>argThat(u -> u.getTeamId() == null));
    }

    @Test
    @DisplayName("PUT /users/{id}: USER için boş takım reddedilir (400, takım zorunlu)")
    void updateUser_user_clearTeam_rejected() throws Exception {
        AppUser updated = new AppUser();
        updated.setId(8L); updated.setUsername("u"); updated.setSystemRole("USER"); updated.setTeamId(5L);
        when(userService.updateUser(eq(8L), any(), any(), any(), eq("USER"), any(), any(), any()))
                .thenReturn(updated);

        mvc.perform(put("/api/admin/users/8")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"system_role\":\"USER\",\"email\":\"a@b.com\",\"team_id\":null}"))
                .andExpect(status().isBadRequest());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // ── Users: filtreli + sayfalı arama (/users/search) ─────────────────────────

    @Test
    @DisplayName("GET /users/search: ADMIN sayfalı yanıt + filtreler repo'ya geçer + size 200'e cap")
    void searchUsers_adminPagedAndFilters() throws Exception {
        AppUser u = new AppUser();
        u.setId(5L); u.setUsername("ali"); u.setSystemRole("USER"); u.setTeamId(3L);
        Page<AppUser> pg = new PageImpl<>(List.of(u),
                org.springframework.data.domain.PageRequest.of(0, 200), 1);
        when(userRepo.findFiltered(any(), any(), any(), any(), any())).thenReturn(pg);
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(2L);

        mvc.perform(get("/api/admin/users/search")
                        .param("q", "Ali").param("systemRole", "USER").param("orgRole", "PO")
                        .param("teamId", "3").param("size", "999").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].username").value("ali"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.active_admin_count").value(2));

        // q lowercased + %..%, role/orgRole/teamId aynen, size 200'e cap
        org.mockito.Mockito.verify(userRepo).findFiltered(eq("%ali%"), eq("USER"), eq("PO"), eq(3L),
                org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == 200));
    }

    @Test
    @DisplayName("GET /users/search: TEAM_ADMIN kendi takımına sabitli (client teamId yok sayılır)")
    void searchUsers_teamAdminForcedTeam() throws Exception {
        when(userRepo.findFiltered(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(1L);

        mvc.perform(get("/api/admin/users/search").param("teamId", "99").session(teamAdminSession()))
                .andExpect(status().isOk());

        // client teamId=99 yok sayılır; oturum takımı (2) zorlanır; diğer filtreler null
        org.mockito.Mockito.verify(userRepo).findFiltered(isNull(), isNull(), isNull(), eq(2L), any());
    }

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
        // Faz 3b: USER görür yalnız kendi takımını; yönetim yok.
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(java.util.List.of(2L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<Long>());
        return s;
    }

    private MockHttpSession teamAdminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "teamadmin");
        s.setAttribute("userId", 99L);
        s.setAttribute("teamId", 2L);
        s.setAttribute("systemRole", "TEAM_ADMIN");
        // Faz 3b: TEAM_ADMIN (PO) liderlik ettiği takım(lar)ı görür + yönetir.
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(java.util.List.of(2L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(java.util.List.of(2L)));
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

    // ── Alarm Geçmişi: arama + yeni filtreler (2026-08-16) ────────────────────
    //
    // Bu filtreler SUNUCU tarafında olmak zorunda: istemci tarafı süzme yalnız açık sayfayı
    // süzer, sayfalamayla "3 sonuç" derken aslında 90 sonuç olur — yanıltıcı.

    private void stubEmptyAlerts() {
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(Collections.emptyList()));
    }

    @Test
    @DisplayName("Arama: kısmi + büyük/küçük harf duyarsız — sorguya %küçük harf% olarak gider")
    void listAlerts_search_isLowercasedAndWrapped() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts?q=Example").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq("%example%"), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("Arama JOKERLERİ kaçışlanır — tek bir '%' aramayı sessizce filtresiz bırakmasın")
    void listAlerts_search_escapesWildcards() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts").param("q", "%_a").session(authSession()))
                .andExpect(status().isOk());

        // '%' ve '_' kullanıcı verisidir, joker DEĞİL: kaçışlanmazsa "%" araması TÜM kayıtları getirir
        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq("%!%!_a%"), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("Boş/boşluk arama param üretmez (filtresiz sorgu, boş sonuç değil)")
    void listAlerts_blankSearch_passesNull() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts").param("q", "   ").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                isNull(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("Seviye filtresi BÜYÜK HARFE çevrilir (critical → CRITICAL)")
    void listAlerts_levelIsUppercased() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts?level=critical").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), eq("CRITICAL"), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("acknowledged ve teamId filtreleri sorguya AYNEN geçer")
    void listAlerts_acknowledgedAndTeamPassThrough() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts?acknowledged=false&teamId=7").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), eq(Boolean.FALSE), eq(7L), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("Tip SAYAÇLARI yeni filtreleri dikkate alır ama tip filtresinden BAĞIMSIZ kalır")
    void listAlerts_typeCountsHonourNewFiltersButNotType() throws Exception {
        stubEmptyAlerts();
        when(alertEventRepo.countFilteredByType(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn(List.<Object[]>of(new Object[]{"EXPIRY", 3L}));

        mvc.perform(get("/api/admin/alerts?alertType=EXPIRY&q=ak&level=HIGH").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type_counts.EXPIRY").value(3));

        // Sayaç sorgusunda alertType YOK (imzada zaten yok) ama q/level VAR:
        // aksi halde arama yapınca rozet sayıları toplamla çelişirdi.
        org.mockito.Mockito.verify(alertEventRepo).countFilteredByType(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq("%ak%"), eq("HIGH"), any(), any(), anyBoolean(), any());
    }


    // ── CSV disa aktarim ─────────────────────────────────────────────────────

    @Test
    @DisplayName("CSV FORMUL ENJEKSIYONU: =,+,-,@ ile baslayan hucre tek tirnakla metinlestirilir")
    void csvCell_neutralisesFormulaInjection() {
        // Excel bu karakterlerle baslayan hucreyi FORMUL sayar. domain ve message dis veriden
        // besleniyor: =cmd|'...'!A1 gibi bir deger, dosyayi acan kisinin makinesinde komut
        // calistirma denemesine donusebilir.
        assertThat(AdminController.csvCell("=cmd|'/c calc'!A1")).startsWith("'=");
        assertThat(AdminController.csvCell("+1+1")).startsWith("'+");
        assertThat(AdminController.csvCell("-2+3")).startsWith("'-");
        assertThat(AdminController.csvCell("@SUM(A1)")).startsWith("'@");
        // Zararsiz degerler DOKUNULMADAN gecer
        assertThat(AdminController.csvCell("www.example.com")).isEqualTo("www.example.com");
        assertThat(AdminController.csvCell("EXPIRY")).isEqualTo("EXPIRY");
    }

    @Test
    @DisplayName("CSV kacislamasi: virgul, tirnak, noktali virgul ve satir sonu tirnak icine alinir")
    void csvCell_escapesSeparators() {
        assertThat(AdminController.csvCell("a,b")).isEqualTo("\"a,b\"");
        assertThat(AdminController.csvCell("a;b")).isEqualTo("\"a;b\"");
        assertThat(AdminController.csvCell("a\nb")).isEqualTo("\"a\nb\"");
        // Ic tirnak IKIYE katlanir (RFC 4180) — aksi halde sutun sinirlari kayar
        assertThat(AdminController.csvCell("de\"me")).isEqualTo("\"de\"\"me\"");
        assertThat(AdminController.csvCell(null)).isEmpty();
        assertThat(AdminController.csvCell("")).isEmpty();
    }
}
