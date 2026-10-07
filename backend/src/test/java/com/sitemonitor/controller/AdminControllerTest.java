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
import org.springframework.data.domain.Sort;
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
    @MockitoBean com.sitemonitor.service.ThresholdPreviewService thresholdPreviewService;   // tier eşik önizleme (2026-09-20)
    @MockitoBean com.sitemonitor.service.AdminHistoryService adminHistoryService;             // sekme değişiklik geçmişi (2026-09-20)
    @MockitoBean com.sitemonitor.service.UserPushRecipientResolver userPushRecipientResolver;
    @MockitoBean com.sitemonitor.service.WebhookService webhookService;
    @MockitoBean com.sitemonitor.service.TeamAdminService teamAdminService;
    @MockitoBean com.sitemonitor.service.AdminOverviewService adminOverviewService;
    @MockitoBean com.sitemonitor.service.DerivedMonitorTeamSync derivedMonitorTeamSync;
    @MockitoBean com.sitemonitor.service.TourStateService tourStateService;   // ürün turu (2026-09-13)
    // AdminController "Tekrar Bildir" onizlemesinde webhook alicilarini da cozuyor (A2).
    @MockitoBean com.sitemonitor.service.UserPushService userPushService;
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

    /** Bağımsız alan adı izlemeleri — alan adı tanılamasının kapsamı (2026-10-05). Stub yoksa boş liste (eski kural). */
    @MockitoBean
    com.sitemonitor.repository.DomainMonitorRepository domainMonitorRepo;

    /** Elle yüklenen sertifika sürümleri (2026-10-06). Stub yoksa boş liste — ağ kayıtlarının yanıtı değişmez. */
    @MockitoBean
    com.sitemonitor.repository.ManualCertificateVersionRepository manualVersionRepo;

    /** Kalıcı silme (2026-10-07) — tablo düzeyindeki silme PermanentDeletionServiceTest'te (H2) sınanır. */
    @MockitoBean
    com.sitemonitor.service.PermanentDeletionService permanentDeletion;

    /** Silme servisinin yanıtı: {@code alerts} kapanan alarm, 5 kontrol satırı. */
    static com.sitemonitor.service.PermanentDeletionService.InventoryDeletion deletionOf(CertificateInventory c, int alerts) {
        return new com.sitemonitor.service.PermanentDeletionService.InventoryDeletion(
                c.getId(), c.getDomain(), c.getTeamId(), alerts, 0, Map.of("certificate_checks", 5, "certificate_inventory", 1));
    }

    @BeforeEach
    void setup() {
        when(permanentDeletion.deleteInventory(any())).thenAnswer(i -> deletionOf(i.getArgument(0), 0));
        when(userService.listTeams()).thenReturn(java.util.Collections.emptyList());
        // Takım varlık denetimi (2026-09-28, O3): varsayılan 'takım var'; olmayan takım testleri kendi stub'ını verir.
        when(teamRepo.existsById(anyLong())).thenReturn(true);
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
    @DisplayName("GET /api/admin/inventory: satırlar latest_checks ile zenginleşir (cert_status / kalan gün / son kontrol) — envanter #3")
    void listInventory_enrichedWithLatestCheck() throws Exception {
        CertificateInventory inv = inventory("example.com");
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of(inv, inventory("nocheck.example.com")));
        com.sitemonitor.model.LatestCheck lc = new com.sitemonitor.model.LatestCheck();
        lc.setDomain("example.com"); lc.setStatus("warning"); lc.setDaysRemaining(12);
        lc.setCheckedAt("2026-09-12T10:00:00"); lc.setNotAfter("2026-09-24T23:59:59"); lc.setIssuerCn("Test CA");
        when(latestCheckRepo.findAll()).thenReturn(List.of(lc));

        mvc.perform(get("/api/admin/inventory").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cert_status").value("warning"))
                .andExpect(jsonPath("$.data[0].cert_days_remaining").value(12))
                .andExpect(jsonPath("$.data[0].cert_checked_at").value("2026-09-12T10:00:00"))
                .andExpect(jsonPath("$.data[0].cert_issuer").value("Test CA"))
                .andExpect(jsonPath("$.data[1].cert_status").doesNotExist());
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"newdomain.com\",\"port\":443,\"team_id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.domain").value("newdomain.com"));
    }

    @Test
    @DisplayName("O-5: POST /inventory sunucunun yönettiği alanları gövdeden ALMAZ (mass assignment)")
    void addInventory_ignoresServerManagedFields() throws Exception {
        when(inventoryRepo.save(any())).thenAnswer(a -> { CertificateInventory i = a.getArgument(0); i.setId(1L); return i; });

        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"sahte.example.com\",\"port\":443,\"team_id\":1,"
                                + "\"deleted_at\":\"2026-01-01T00:00:00\",\"domain_expiry\":\"2099-01-01\","
                                + "\"domain_registrar\":\"Sahte Kayitci\",\"domain_expiry_checked_at\":\"2026-01-01T00:00:00\","
                                + "\"updated_by\":\"N99999\",\"updated_by_name\":\"Baska Biri\","
                                + "\"renewal_planned_at\":\"2026-12-01\",\"renewal_planned_by\":\"N99999\","
                                + "\"renewal_planned_by_name\":\"Baska Biri\",\"renewal_planned_note\":\"sahte plan\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<CertificateInventory> cap = org.mockito.ArgumentCaptor.forClass(CertificateInventory.class);
        verify(inventoryRepo).save(cap.capture());
        CertificateInventory saved = cap.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getDomain()).isEqualTo("sahte.example.com");   // gövde yine işlendi
        org.assertj.core.api.Assertions.assertThat(java.util.Arrays.asList(
                saved.getDeletedAt(), saved.getDomainExpiry(), saved.getDomainRegistrar(), saved.getDomainExpiryCheckedAt(),
                saved.getUpdatedBy(), saved.getUpdatedByName(), saved.getRenewalPlannedAt(), saved.getRenewalPlannedBy(),
                saved.getRenewalPlannedByName(), saved.getRenewalPlannedNote()))
                .as("istemcinin gönderdiği sunucu alanları kayda geçmemeli")
                .containsOnlyNulls();
    }

    // ── Grup + etiket zorunlu (2026-09-18): envanter kaydı da bir izleme ──
    @Test
    @DisplayName("POST /inventory: grup yoksa 400 — kayıt açılmaz")
    void addInventory_missingGroup_returns400() throws Exception {
        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":\"t1\",\"domain\":\"grupsuz.example.com\",\"port\":443,\"team_id\":1}"))
                .andExpect(status().isBadRequest());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("POST /inventory: etiket yoksa 400 — kayıt açılmaz")
    void addInventory_missingTags_returns400() throws Exception {
        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"domain\":\"etiketsiz.example.com\",\"port\":443,\"team_id\":1}"))
                .andExpect(status().isBadRequest());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("PUT /inventory: etiket boş gönderilirse 400 — eskiden mevcut etiketleri SİLİYORDU")
    void updateInventory_blankTags_returns400_doesNotWipe() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        existing.setTags("prod");
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"  \",\"domain\":\"old.com\",\"port\":443,\"active\":true}"))
                .andExpect(status().isBadRequest());
        verify(inventoryRepo, never()).save(any());
        assertThat(existing.getTags()).isEqualTo("prod");
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"updated.com\",\"port\":443,\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // 2026-09-25 regresyon R4 incelemesi: düzenleme ucu takım değişikliğinde yalnız KAYNAK takımı denetliyordu.
    @Test
    @DisplayName("PUT inventory: kapsamlı müdür kaydı yönetim kapsamı DIŞINDAKİ takıma taşıyamaz (403, yazılmaz); kendi takımında düzenler")
    void updateInventory_scopedAdmin_cannotMoveToForeignTeam() throws Exception {
        CertificateInventory existing = inventory("kendi.example.com");
        existing.setId(1L);
        existing.setTeamId(2L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        String body = "{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"kendi.example.com\",\"port\":443,\"active\":true,\"team_id\":%d}";

        mvc.perform(put("/api/admin/inventory/1").session(scopedAdminSession())
                        .contentType(MediaType.APPLICATION_JSON).content(String.format(body, 9)))
                .andExpect(status().isForbidden());
        verify(inventoryRepo, org.mockito.Mockito.never()).save(any());

        mvc.perform(put("/api/admin/inventory/1").session(scopedAdminSession())
                        .contentType(MediaType.APPLICATION_JSON).content(String.format(body, 2)))
                .andExpect(status().isOk());
    }

    // ── BO9 (bug regresyon 2026-09-27): ug_team_id mass assignment ─────────────
    // requireInventoryWriter yalnız kaydın KENDİ takımına bakıyor; gövdedeki ug_team_id olduğu gibi yazılınca
    // takım üyesi USER kaydı başka takımın görünürlüğüne + alarm e-postalarına açıp admin kapılı transfer-ug'yi
    // atlıyordu.

    private static final String INV_BODY =
            "{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"ug.example.com\",\"port\":443,\"active\":true,"
                    + "\"team_id\":2,\"ug_team_id\":%s}";

    @Test
    @DisplayName("BO9: PUT inventory gövdesiyle UG takımı BAŞKA takıma çevrilemez (USER de global admin de 403; yazılmaz)")
    void updateInventory_foreignUgTeam_forbidden() throws Exception {
        for (MockHttpSession s : new MockHttpSession[]{userSession(), teamAdminSession(), scopedAdminSession(), authSession()}) {
            CertificateInventory existing = inventory("ug.example.com");
            existing.setId(1L);
            existing.setTeamId(2L);
            existing.setUgTeamId(null);
            when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));

            mvc.perform(put("/api/admin/inventory/1").session(s)
                            .contentType(MediaType.APPLICATION_JSON).content(String.format(INV_BODY, "9")))
                    .andExpect(status().isForbidden());
            assertThat(existing.getUgTeamId()).as((String) s.getAttribute("username")).isNull();
        }
        verify(inventoryRepo, never()).save(any());
        verify(latestCheckRepo, never()).renameDomain(any(), any());
    }

    @Test
    @DisplayName("BO9: PUT inventory — UG temizleme (null, tek takıma yakınsama) ve AYNI değer serbest")
    void updateInventory_ugClearOrSame_allowed() throws Exception {
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CertificateInventory existing = inventory("ug.example.com");
        existing.setId(1L);
        existing.setTeamId(2L);
        existing.setUgTeamId(5L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));

        mvc.perform(put("/api/admin/inventory/1").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON).content(String.format(INV_BODY, "5")))
                .andExpect(status().isOk());
        assertThat(existing.getUgTeamId()).isEqualTo(5L);

        mvc.perform(put("/api/admin/inventory/1").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON).content(String.format(INV_BODY, "null")))
                .andExpect(status().isOk());
        assertThat(existing.getUgTeamId()).as("ön uç her düzenlemede null gönderir — temizleme korunur").isNull();
    }

    @Test
    @DisplayName("BO9: POST inventory gövdesiyle UG başka takım olamaz (403, kayıt açılmaz); boş ya da kendi takımı serbest")
    void addInventory_foreignUgTeam_forbidden() throws Exception {
        String body = "{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"yeni-ug.example.com\",\"port\":443,"
                + "\"team_id\":2,\"ug_team_id\":%s}";
        mvc.perform(post("/api/admin/inventory").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON).content(String.format(body, "9")))
                .andExpect(status().isForbidden());
        verify(inventoryRepo, never()).save(any());

        when(inventoryRepo.save(any())).thenAnswer(a -> { CertificateInventory i = a.getArgument(0); i.setId(1L); return i; });
        for (String ok : new String[]{"null", "2"}) {
            mvc.perform(post("/api/admin/inventory").session(userSession())
                            .contentType(MediaType.APPLICATION_JSON).content(String.format(body, ok)))
                    .andExpect(status().isOk());
        }
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"dom.com\",\"port\":443,\"active\":false}"))
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"dom2.com\",\"port\":443,\"active\":true}"))
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"x.com\",\"port\":443}"))
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"old.com\",\"port\":443,\"active\":true,\"tls_mode\":\"default\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tls_mode").value("default"));
    }

    // ── Alan başına kontrol sıklığı (2026-09-12) ─────────────────────────────

    @Test
    @DisplayName("2026-09-12: PUT persists check_interval_hours (24) and echoes it back")
    void updateInventory_checkInterval_persisted() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"old.com\",\"port\":443,\"active\":true,\"check_interval_hours\":24}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.check_interval_hours").value(24));
        assertThat(existing.getCheckIntervalHours()).isEqualTo(24);
    }

    @Test
    @DisplayName("2026-09-12: PUT with an unsupported interval (5) falls back to null = global schedule")
    void updateInventory_checkInterval_unsupportedBecomesNull() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        existing.setCheckIntervalHours(168);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"old.com\",\"port\":443,\"active\":true,\"check_interval_hours\":5}"))
                .andExpect(status().isOk());
        assertThat(existing.getCheckIntervalHours()).isNull();
    }

    @Test
    @DisplayName("2026-09-12: normalizeInterval accepts only 1/6/12/24/168")
    void normalizeInterval_whitelist() {
        assertThat(AdminController.normalizeInterval(null)).isNull();
        for (int h : new int[] {1, 6, 12, 24, 168}) assertThat(AdminController.normalizeInterval(h)).isEqualTo(h);
        for (int h : new int[] {0, -1, 2, 5, 48, 720}) assertThat(AdminController.normalizeInterval(h)).isNull();
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"old.com\",\"port\":443,\"active\":true,"
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"old.com\",\"port\":443,\"active\":true,"
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"old.com\",\"port\":443,\"active\":true,"
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"old.com\",\"port\":443,\"active\":true,"
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"yeni.example.com\",\"port\":443,\"active\":true,"
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"old.com\",\"port\":443,\"tls_mode\":\"bogus\"}"))
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
    @DisplayName("2026-09-11: USER BAŞKA takımın izlediği domain'e tanılama koşturamaz (403) — yetki açıldı, kapsam uçta")
    void runDiagnostics_asUser_otherTeamDomain_returns403() throws Exception {
        com.sitemonitor.model.CertificateInventory otherTeam = new com.sitemonitor.model.CertificateInventory();
        otherTeam.setDomain("example.org");
        otherTeam.setTeamId(999L);                      // oturumun görüş kapsamında OLMAYAN takım
        when(inventoryRepo.findByDomain("example.org")).thenReturn(Optional.of(otherTeam));

        mvc.perform(post("/api/admin/diagnostics")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"example.org\",\"port\":443}"))
                .andExpect(status().isForbidden());
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
    @DisplayName("KALICI SİLME (2026-10-07): DELETE /inventory/{id} kaydı servisle kalıcı siler — yumuşak silme yazılmaz; geçmiş + denetim")
    void deleteInventory_authenticated_permanent() throws Exception {
        CertificateInventory inv = inventory("example.com");
        inv.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(inv));

        mvc.perform(delete("/api/admin/inventory/1").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.permanent").value(true))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.domain").value("example.com"))   // arayüz iyimser kaldırma
                .andExpect(jsonPath("$.alertsClosed").value(0))
                .andExpect(jsonPath("$.checksDeleted").value(5));

        verify(permanentDeletion).deleteInventory(inv);
        verify(inventoryRepo, never()).save(any());   // deleted_at / active=false YAZILMAZ — satır gider
        assertThat(inv.getDeletedAt()).isNull();
        // Ürün geçmişi: DELETE satırı (silme anının tam görüntüsü) + "kalıcı silme" notu; denetim DOMAIN_DELETE permanent.
        verify(monitorHistory).record(eq(com.sitemonitor.service.MonitorHistoryService.INVENTORY), eq(1L), eq("example.com"),
                any(), eq(com.sitemonitor.service.MonitorHistoryService.DELETE), any(), any(), eq("kalıcı silme"), any());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("DOMAIN_DELETE"), any(), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("CERTIFICATE"), eq("example.com"), detail.capture());
        assertThat(detail.getValue()).contains("\"permanent\":true").contains("\"certificate_checks\":5");
        verify(auditService, never()).recordAction(eq("DOMAIN_SOFT_DELETE"), any(), any(jakarta.servlet.http.HttpServletRequest.class),
                any(), any(), any());
    }

    @Test
    @DisplayName("DELETE /api/admin/inventory/{id}: kapanan alarm sayısı servisten döner")
    void deleteInventory_withOpenAlerts_closesAndReturnsCount() throws Exception {
        CertificateInventory inv = inventory("stuck.example.com");
        inv.setId(7L);
        when(inventoryRepo.findById(7L)).thenReturn(Optional.of(inv));
        when(permanentDeletion.deleteInventory(inv)).thenReturn(deletionOf(inv, 3));

        mvc.perform(delete("/api/admin/inventory/7").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.alertsClosed").value(3));
    }

    @Test
    @DisplayName("KALICI SİLME: yok olan kayıt 404; çöp kutusu uçları KALDIRILDI (restore / permanent / purge-deleted)")
    void deleteInventory_missing404_binEndpointsGone() throws Exception {
        when(inventoryRepo.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(delete("/api/admin/inventory/99").session(authSession())).andExpect(status().isNotFound());
        verify(permanentDeletion, never()).deleteInventory(any());

        CertificateInventory inv = inventory("x.example.com"); inv.setId(3L);
        when(inventoryRepo.findById(3L)).thenReturn(Optional.of(inv));
        // Eşlenmemiş yol/yöntem: POST /restore ve /purge-deleted → 404; DELETE /{id}/permanent → 404 (eşleme yok).
        org.springframework.test.web.servlet.MvcResult r1 = mvc.perform(post("/api/admin/inventory/3/restore").session(authSession())).andReturn();
        org.springframework.test.web.servlet.MvcResult r2 = mvc.perform(delete("/api/admin/inventory/3/permanent").session(authSession())).andReturn();
        org.springframework.test.web.servlet.MvcResult r3 = mvc.perform(post("/api/admin/inventory/purge-deleted").session(authSession())).andReturn();
        assertThat(List.of(r1.getResponse().getStatus(), r2.getResponse().getStatus(), r3.getResponse().getStatus()))
                .allMatch(s -> s == 404 || s == 405);
        verify(permanentDeletion, never()).deleteInventory(any());
        verify(inventoryRepo, never()).save(any());
        verify(inventoryRepo, never()).delete(any());
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
    @DisplayName("KALICI SİLME: POST /inventory/bulk delete — her kayıt servisle kalıcı silinir, alarm sayıları toplanır, denetimde alan adları")
    void bulkInventory_delete_permanent_closesAlerts() throws Exception {
        CertificateInventory a = inventory("d1.com"); a.setId(1L);
        CertificateInventory b = inventory("d2.com"); b.setId(2L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(a));
        when(inventoryRepo.findById(2L)).thenReturn(Optional.of(b));
        when(permanentDeletion.deleteInventory(a)).thenReturn(deletionOf(a, 2));
        when(permanentDeletion.deleteInventory(b)).thenReturn(deletionOf(b, 1));

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"delete\",\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(2))
                .andExpect(jsonPath("$.data.permanent").value(true))
                .andExpect(jsonPath("$.data.alertsClosed").value(3));

        verify(permanentDeletion).deleteInventory(a);
        verify(permanentDeletion).deleteInventory(b);
        verify(inventoryRepo, never()).save(any());   // yumuşak silme yazılmaz
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("DOMAIN_BULK_DELETE"), any(), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("CERTIFICATE"), eq("2 domain"), detail.capture());
        assertThat(detail.getValue()).contains("\"permanent\":true").contains("\"domains\":[\"d1.com\",\"d2.com\"]");
    }

    @Test
    @DisplayName("KALICI SİLME: yanıt gerçekten silinen alan adlarını taşır (arayüz iyimser kaldırma) — atlanan kayıt listede yok")
    void bulkInventory_delete_returnsDeletedDomains() throws Exception {
        CertificateInventory own   = inventory("own.com");   own.setId(1L);   own.setTeamId(2L);
        CertificateInventory other = inventory("other.com"); other.setId(2L); other.setTeamId(7L);   // kapsam dışı
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(own));
        when(inventoryRepo.findById(2L)).thenReturn(Optional.of(other));
        mvc.perform(post("/api/admin/inventory/bulk").session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"delete\",\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted_domains.length()").value(1))
                .andExpect(jsonPath("$.data.deleted_domains[0]").value("own.com"))
                .andExpect(jsonPath("$.data.skipped").value(1));
        verify(permanentDeletion, never()).deleteInventory(other);
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

    // ── Tüm Sertifikalar toplu işlemleri (2026-09-13): alan adıyla çözüm + set-tier / set-team ──

    @Test
    @DisplayName("bulk: `domains` listesi envanter kimliğine çözülür (bilinmeyen alan atlanır), set-tier kademeyi yazar")
    void bulk_domainsResolveToIds_setTier() throws Exception {
        CertificateInventory a = inventory("a.example.com"); a.setId(11L); a.setTeamId(1L); a.setTier(3);
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(Optional.of(a));
        when(inventoryRepo.findByDomain("yok.example.com")).thenReturn(Optional.empty());
        when(inventoryRepo.findById(11L)).thenReturn(Optional.of(a));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-tier\",\"domains\":[\"A.example.com\",\"yok.example.com\"],\"tier\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1));
        assertThat(a.getTier()).isEqualTo(1);
        ArgumentCaptor<String> act = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(act.capture(), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class),
                ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
        assertThat(act.getValue()).isEqualTo("DOMAIN_BULK_SET_TIER");
    }

    @Test
    @DisplayName("bulk set-tier: 0 ya da 5 → 400; tier verilmezse kademe KALDIRILIR")
    void bulk_setTier_validatesRange_andClears() throws Exception {
        CertificateInventory a = inventory("a.example.com"); a.setId(11L); a.setTeamId(1L); a.setTier(2);
        when(inventoryRepo.findById(11L)).thenReturn(Optional.of(a));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-tier\",\"ids\":[11],\"tier\":5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-tier\",\"ids\":[11]}"))
                .andExpect(status().isOk());
        assertThat(a.getTier()).isNull();
    }

    @Test
    @DisplayName("bulk set-team: GLOBAL admin takımı yazar ve türev izlemeleri senkronlar; TEAM_ADMIN → 403; team_id yoksa 400")
    void bulk_setTeam_globalAdminOnly_syncsDerived() throws Exception {
        Team t9 = new Team(); t9.setId(9L); when(teamRepo.findById(9L)).thenReturn(Optional.of(t9));   // takım var (2026-09-28)
        CertificateInventory a = inventory("a.example.com"); a.setId(11L); a.setTeamId(1L);
        when(inventoryRepo.findById(11L)).thenReturn(Optional.of(a));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-team\",\"ids\":[11],\"team_id\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1));
        assertThat(a.getTeamId()).isEqualTo(9L);
        verify(derivedMonitorTeamSync).syncTeam("a.example.com", 9L);
        verify(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("inventory.transfer"), eq("execute"));

        mvc.perform(post("/api/admin/inventory/bulk").session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-team\",\"ids\":[11],\"team_id\":9}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-team\",\"ids\":[11]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/tour-reset: admin sıfırlar (USER_TOUR_RESET); TEAM_ADMIN başka takımın kullanıcısı → 403; USER → 403")
    void tourReset_scopedAndAudited() throws Exception {
        AppUser target = new AppUser(); target.setId(77L); target.setUsername("newbie"); target.setTeamId(9L); target.setActive(true);
        when(userRepo.findById(77L)).thenReturn(Optional.of(target));

        mvc.perform(post("/api/admin/users/77/tour-reset").session(authSession()))
                .andExpect(status().isOk());
        verify(tourStateService).apply(eq(target), any(), eq(true));
        verify(auditService).recordAction(eq("USER_TOUR_RESET"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("USER"), eq("77"),
                org.mockito.ArgumentMatchers.contains("newbie"));

        mvc.perform(post("/api/admin/users/77/tour-reset").session(teamAdminSession()))
                .andExpect(status().isForbidden());   // takım 2'nin admini, kullanıcı takım 9'da
        mvc.perform(post("/api/admin/users/77/tour-reset").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/team-unlock: admin takım kilidini kaldırır (USER_TEAM_UNLOCK); TEAM_ADMIN başka takım → 403; USER → 403")
    void teamUnlock_scopedAndAudited() throws Exception {
        AppUser target = new AppUser(); target.setId(78L); target.setUsername("multi"); target.setTeamId(9L); target.setActive(true);
        target.setTeamIds(new java.util.LinkedHashSet<>(java.util.List.of(9L, 4L)));
        when(userRepo.findById(78L)).thenReturn(Optional.of(target));

        mvc.perform(post("/api/admin/users/78/team-unlock").session(authSession()))
                .andExpect(status().isOk());
        verify(userService).unlockTeams(78L);
        verify(auditService).recordAction(eq("USER_TEAM_UNLOCK"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("USER"), eq("78"),
                org.mockito.ArgumentMatchers.contains("multi"));

        mvc.perform(post("/api/admin/users/78/team-unlock").session(teamAdminSession()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/78/team-unlock").session(userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/push-snooze/clear (2026-10-04): global admin ve kişinin takımını yöneten kapsamlı yönetici susturmayı kaldırır (PUSH_SNOOZE_CLEAR); başka takımın yöneticisi / USER → 403")
    void pushSnoozeClear_scopedAndAudited() throws Exception {
        AppUser inTeam2 = new AppUser(); inTeam2.setId(81L); inTeam2.setUsername("snoozer"); inTeam2.setTeamId(2L); inTeam2.setActive(true);
        inTeam2.setPushSnoozeUntil("2099-01-01T08:00:00");
        AppUser inTeam9 = new AppUser(); inTeam9.setId(82L); inTeam9.setUsername("other"); inTeam9.setTeamId(9L); inTeam9.setActive(true);
        inTeam9.setPushSnoozeUntil("2099-01-01T08:00:00");
        when(userRepo.findById(81L)).thenReturn(Optional.of(inTeam2));
        when(userRepo.findById(82L)).thenReturn(Optional.of(inTeam9));
        when(userService.savePushSnooze(any(AppUser.class), any(), any())).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            u.setPushSnoozeUntil(i.getArgument(1));
            return u;
        });

        mvc.perform(post("/api/admin/users/81/push-snooze/clear").session(teamAdminSession()))   // takım 2'nin yöneticisi
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.had_snooze").value(true));
        assertThat(inTeam2.getPushSnoozeUntil()).as("susturma kaldırıldı").isNull();
        verify(userService).savePushSnooze(org.mockito.ArgumentMatchers.argThat(u -> u.getId() == 81L), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        verify(auditService).recordAction(eq("PUSH_SNOOZE_CLEAR"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("USER"), eq("81"),
                ArgumentMatchers.argThat(d -> d.contains("snoozer") && d.contains("\"had_snooze\":true")));

        mvc.perform(post("/api/admin/users/82/push-snooze/clear").session(teamAdminSession()))   // takım 9 — kapsam dışı
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/81/push-snooze/clear").session(userSession()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/82/push-snooze/clear").session(authSession()))       // global admin
                .andExpect(status().isOk());
        verify(userService, org.mockito.Mockito.times(2)).savePushSnooze(any(AppUser.class), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/field-unlock: YALNIZ global admin (TEAM_ADMIN/USER → 403); 200 + USER_FIELD_UNLOCK denetimi; bilinmeyen alan → 400")
    void fieldUnlock_globalAdminOnly_andAudited() throws Exception {
        AppUser target = new AppUser(); target.setId(79L); target.setUsername("ldapuser"); target.setTeamId(2L); target.setActive(true);
        target.setAuthSource("LDAP"); target.setLockedFields("email,title");
        when(userRepo.findById(79L)).thenReturn(Optional.of(target));
        when(userService.unlockField(79L, "title")).thenReturn(true);

        // Hedef takım 2'de → kapsamlı takım yöneticisi bile 403 (kilit kaldırma global yöneticiye özel)
        mvc.perform(post("/api/admin/users/79/field-unlock").session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"field\":\"title\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/79/field-unlock").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"field\":\"title\"}"))
                .andExpect(status().isForbidden());
        verify(userService, never()).unlockField(anyLong(), any());

        mvc.perform(post("/api/admin/users/79/field-unlock").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"field\":\"title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.had_lock").value(true))
                .andExpect(jsonPath("$.data.username").value("ldapuser"))
                .andExpect(jsonPath("$.data.locked_field_keys").isArray());
        verify(userService).unlockField(79L, "title");
        verify(auditService).recordAction(eq("USER_FIELD_UNLOCK"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("USER"), eq("79"),
                ArgumentMatchers.argThat(d -> d.contains("ldapuser") && d.contains("\"field\":\"title\"") && d.contains("had_lock")));

        mvc.perform(post("/api/admin/users/79/field-unlock").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"field\":\"password\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/users/79/field-unlock").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(userService, never()).unlockField(eq(79L), eq("password"));
    }

    @Test
    @DisplayName("POST /api/admin/inventory/bulk as USER → 403")
    void bulkInventory_asUser_returns403() throws Exception {
        mvc.perform(post("/api/admin/inventory/bulk").session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deactivate\",\"ids\":[1]}"))
                .andExpect(status().isForbidden());
    }

    // ── Toplu envanter işlemi → İzleme Değişiklikleri (2026-09-28) ─────────────────────────────
    // Eskiden toplu yol ürün geçmişine HİÇ yazmıyordu: toplu silinen kayıt İzleme Değişiklikleri'nde görünmüyor,
    // "silinmiş" rozetini (findDeletedAmong = kaynağın son olayı DELETE) alamıyordu.

    @Test
    @DisplayName("bulk delete: işlenen HER kayıt için tekil silmeyle aynı DELETE satırı (tür/kimlik/ad/takım + not) ve kalıcı silme; kapsam dışı ve bilinmeyen kimlik için satır YOK")
    @SuppressWarnings("unchecked")
    void bulkDelete_writesDeleteHistoryForEachProcessedRecord() throws Exception {
        CertificateInventory a = inventory("d1.example.com"); a.setId(1L); a.setTeamId(2L);
        CertificateInventory b = inventory("d2.example.com"); b.setId(2L); b.setTeamId(2L);
        CertificateInventory gone = inventory("gone.example.com"); gone.setId(3L); gone.setTeamId(2L);
        gone.setDeletedAt("2026-06-01T00:00:00"); gone.setActive(false);
        CertificateInventory other = inventory("other.example.com"); other.setId(4L); other.setTeamId(7L);   // kapsam dışı (IDOR)
        for (CertificateInventory r : List.of(a, b, gone, other)) when(inventoryRepo.findById(r.getId())).thenReturn(Optional.of(r));
        when(inventoryRepo.findById(99L)).thenReturn(Optional.empty());
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk").session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"delete\",\"ids\":[1,2,3,4,99]}"))
                .andExpect(status().isOk())
                // Silme KALICI (2026-10-07): eski sürümden kalmış çöp satırı (gone) da kalıcı silinir; yalnız kapsam
                // dışı (other) ve yok olan (99) atlanır.
                .andExpect(jsonPath("$.data.processed").value(3))
                .andExpect(jsonPath("$.data.skipped").value(2));

        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(monitorHistory).record(eq("INVENTORY"), eq(1L), eq("d1.example.com"), eq(2L), eq("DELETE"),
                before.capture(), after.capture(), eq("toplu silme (kalıcı silme)"), any(jakarta.servlet.http.HttpSession.class));
        // Silme anının tam görüntüsü: canlı kayıt (yumuşak silme alanları yazılmaz — satır gider)
        assertThat(before.getValue()).containsEntry("deletedAt", null).containsEntry("active", true);
        assertThat(after.getValue()).containsEntry("deletedAt", null).containsEntry("active", true);
        verify(monitorHistory).record(eq("INVENTORY"), eq(2L), eq("d2.example.com"), eq(2L), eq("DELETE"),
                any(), any(), eq("toplu silme (kalıcı silme)"), any(jakarta.servlet.http.HttpSession.class));
        verify(monitorHistory, org.mockito.Mockito.times(3)).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(permanentDeletion).deleteInventory(a);
        verify(permanentDeletion).deleteInventory(b);
        verify(permanentDeletion).deleteInventory(gone);
        verify(permanentDeletion, never()).deleteInventory(other);   // IDOR: kapsam dışı kayda dokunulmaz
        verify(monitorHistory, never()).stampUpdated(any(), any());   // yumuşak silme damgası yok
    }

    @Test
    @DisplayName("bulk activate/set-tier/set-contacts: tekil düzenlemeyle aynı UPDATE satırı yalnız DEĞİŞEN kayda (değişmeyen kayıt boş satır üretmez)")
    void bulkUpdateActions_writeUpdateHistoryOnlyWhenChanged() throws Exception {
        CertificateInventory live = inventory("live.example.com"); live.setId(1L); live.setTeamId(1L); live.setTier(2);
        CertificateInventory paused = inventory("paused.example.com"); paused.setId(2L); paused.setTeamId(1L); paused.setActive(false);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(live));
        when(inventoryRepo.findById(2L)).thenReturn(Optional.of(paused));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"activate\",\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(2));
        verify(monitorHistory).record(eq("INVENTORY"), eq(2L), eq("paused.example.com"), eq(1L), eq("UPDATE"),
                any(), any(), eq("toplu etkinleştirme"), any(jakarta.servlet.http.HttpSession.class));
        verify(monitorHistory, never()).record(any(), eq(1L), any(), any(), any(), any(), any(), any(), any());   // zaten aktifti

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-tier\",\"ids\":[1],\"tier\":1}"))
                .andExpect(status().isOk());
        verify(monitorHistory).record(eq("INVENTORY"), eq(1L), eq("live.example.com"), eq(1L), eq("UPDATE"),
                any(), any(), eq("toplu kademe ataması"), any(jakarta.servlet.http.HttpSession.class));

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-contacts\",\"ids\":[2],\"svc_mgmt_contact\":\"Ad Soyad - ad.soyad@example.com\"}"))
                .andExpect(status().isOk());
        verify(monitorHistory).record(eq("INVENTORY"), eq(2L), eq("paused.example.com"), eq(1L), eq("UPDATE"),
                any(), any(), eq("toplu sorumlu ekip ataması"), any(jakarta.servlet.http.HttpSession.class));
    }

    @Test
    @DisplayName("bulk set-team + tekil transfer: takım değişikliği UPDATE satırı yeni takımla yazılır (PUT düzenlemesiyle aynı olay)")
    void teamChange_writesUpdateHistory_bulkAndSingleTransfer() throws Exception {
        Team t9 = new Team(); t9.setId(9L); when(teamRepo.findById(9L)).thenReturn(Optional.of(t9));   // takım var (2026-09-28)
        CertificateInventory a = inventory("a.example.com"); a.setId(11L); a.setTeamId(1L);
        CertificateInventory b = inventory("b.example.com"); b.setId(12L); b.setTeamId(1L);
        when(inventoryRepo.findById(11L)).thenReturn(Optional.of(a));
        when(inventoryRepo.findById(12L)).thenReturn(Optional.of(b));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-team\",\"ids\":[11],\"team_id\":9}"))
                .andExpect(status().isOk());
        verify(monitorHistory).record(eq("INVENTORY"), eq(11L), eq("a.example.com"), eq(9L), eq("UPDATE"),
                any(), any(), eq("toplu takım aktarımı"), any(jakarta.servlet.http.HttpSession.class));

        mvc.perform(post("/api/admin/inventory/12/transfer").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":9}"))
                .andExpect(status().isOk());
        verify(monitorHistory).record(eq("INVENTORY"), eq(12L), eq("b.example.com"), eq(9L), eq("UPDATE"),
                any(), any(), isNull(), any(jakarta.servlet.http.HttpSession.class));
    }

    @Test
    @DisplayName("2026-09-28: OLMAYAN takıma aktarım / toplu takım / kişi / envanter ekleme + PUT takım değişimi 400 — kayıt sahipsiz kalmaz")
    void nonexistentTeam_rejectedEverywhere() throws Exception {
        CertificateInventory b = inventory("b.example.com"); b.setId(12L); b.setTeamId(1L);
        when(inventoryRepo.findById(12L)).thenReturn(Optional.of(b));
        when(teamRepo.existsById(77L)).thenReturn(false);
        mvc.perform(post("/api/admin/inventory/12/transfer").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":77}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-team\",\"ids\":[12],\"team_id\":77}"))
                .andExpect(status().isBadRequest());
        assertThat(b.getTeamId()).isEqualTo(1L);
        verify(inventoryRepo, never()).save(any());

        EscalationContact existing = contact("po@example.com", "PO"); existing.setId(5L); existing.setTeamId(1L);
        when(contactRepo.findById(5L)).thenReturn(Optional.of(existing));
        mvc.perform(put("/api/admin/contacts/5").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"PO\",\"team_id\":77}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/contacts").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kişi\",\"role\":\"TECH\",\"team_id\":77}"))
                .andExpect(status().isBadRequest());
        verify(contactRepo, never()).save(any());

        // O3: envanter EKLEME ve PUT ile takım değişimi de olmayan takıma yazamaz
        mvc.perform(post("/api/admin/inventory").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"yeni.example.com\",\"port\":443,\"team_id\":77}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("takım bulunamadı")));
        mvc.perform(put("/api/admin/inventory/12").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"b.example.com\",\"port\":443,\"active\":true,\"team_id\":77}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("takım bulunamadı")));
        assertThat(b.getTeamId()).isEqualTo(1L);
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("2026-09-28: SY aktarımı takımsız istenirse 400 — kayıt ve türev izlemeler sahipsiz kalmaz")
    void transfer_withoutTeam_returns400_nothingChanges() throws Exception {
        CertificateInventory b = inventory("b.example.com"); b.setId(12L); b.setTeamId(1L);
        when(inventoryRepo.findById(12L)).thenReturn(Optional.of(b));
        mvc.perform(post("/api/admin/inventory/12/transfer").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        assertThat(b.getTeamId()).isEqualTo(1L);
        verify(inventoryRepo, never()).save(any());
        verify(derivedMonitorTeamSync, never()).syncTeam(any(), any());
    }

    @Test
    @DisplayName("transfer-ug: UG takımı değişikliği UPDATE satırı (fark YALNIZ ugTeamId); aynı değere aktarımda record fark almaz")
    @SuppressWarnings("unchecked")
    void transferUg_writesUpdateHistoryWithUgTeamDiff() throws Exception {
        CertificateInventory a = inventory("ug.example.com"); a.setId(21L); a.setTeamId(1L); a.setUgTeamId(3L);
        when(inventoryRepo.findById(21L)).thenReturn(Optional.of(a));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/inventory/21/transfer-ug").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ug_team_id\":8}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(monitorHistory).record(eq("INVENTORY"), eq(21L), eq("ug.example.com"), eq(1L), eq("UPDATE"),
                before.capture(), after.capture(), isNull(), any(jakarta.servlet.http.HttpSession.class));
        assertThat(before.getValue()).containsEntry("ugTeamId", 3L);
        assertThat(after.getValue()).containsEntry("ugTeamId", 8L);
        assertThat(com.sitemonitor.service.MonitorHistoryService.changedFields(
                com.sitemonitor.service.AuditDiff.diff(before.getValue(), after.getValue()))).containsExactly("ugTeamId");
    }

    @Test
    @DisplayName("Ek 3/5: transfer-ug OLMAYAN takıma 400 (kayıt değişmez); null = UG takımını kaldır, izinli")
    void transferUg_nonexistentTeam_400_nullClears() throws Exception {
        CertificateInventory a = inventory("ug3.example.com"); a.setId(23L); a.setTeamId(1L); a.setUgTeamId(3L);
        when(inventoryRepo.findById(23L)).thenReturn(Optional.of(a));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(teamRepo.existsById(77L)).thenReturn(false);   // takım yok (varsayılan: var — setUp)
        mvc.perform(post("/api/admin/inventory/23/transfer-ug").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ug_team_id\":77}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        assertThat(a.getUgTeamId()).isEqualTo(3L);
        verify(inventoryRepo, never()).save(any());
        verify(monitorHistory, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(jakarta.servlet.http.HttpSession.class));

        mvc.perform(post("/api/admin/inventory/23/transfer-ug").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ug_team_id\":null}"))
                .andExpect(status().isOk());
        assertThat(a.getUgTeamId()).isNull();
    }

    @Test
    @DisplayName("Silme KALICI (2026-10-07): eski sürümden kalmış çöp satırına SY / UG aktarımı 404; 'restore:true' geri yüklemez")
    void transfer_legacyBinRow_404_noRestore() throws Exception {
        CertificateInventory d = inventory("gone.example.com"); d.setId(31L); d.setTeamId(1L); d.setUgTeamId(3L);
        d.setDeletedAt("2026-09-20T10:00:00"); d.setActive(false);
        when(inventoryRepo.findById(31L)).thenReturn(Optional.of(d));
        mvc.perform(post("/api/admin/inventory/31/transfer").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team_id\":9,\"restore\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/inventory/31/transfer-ug").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ug_team_id\":9}"))
                .andExpect(status().isNotFound());
        assertThat(d.getTeamId()).isEqualTo(1L);
        assertThat(d.getUgTeamId()).isEqualTo(3L);
        assertThat(d.getDeletedAt()).as("geri yükleme yolu yok").isNotNull();
        verify(inventoryRepo, never()).save(any());
        verify(derivedMonitorTeamSync, never()).syncTeam(any(), any());
        verify(monitorHistory, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any(jakarta.servlet.http.HttpSession.class));
        verify(auditService, never()).recordAction(eq("DOMAIN_RESTORE"), any(), any(jakarta.servlet.http.HttpServletRequest.class),
                any(), any(), any());
    }

    @Test
    @DisplayName("tekil silme @Transactional — geçmiş satırı + alarm kapanışı + kalıcı silme tek işlem")
    void deleteInventory_isTransactional() throws Exception {
        java.lang.reflect.Method m = AdminController.class.getMethod("deleteInventory",
                Long.class, jakarta.servlet.http.HttpSession.class, jakarta.servlet.http.HttpServletRequest.class);
        assertThat(m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    @Test
    @DisplayName("ugTeamId anlık görüntüde: UG takımına DOKUNMAYAN toplu işlemde sahte 'UG takımı değişti' farkı YOK")
    @SuppressWarnings("unchecked")
    void bulkTier_withUgTeam_noSpuriousUgDiff() throws Exception {
        CertificateInventory a = inventory("ug2.example.com"); a.setId(22L); a.setTeamId(1L); a.setUgTeamId(3L); a.setTier(2);
        when(inventoryRepo.findById(22L)).thenReturn(Optional.of(a));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/inventory/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-tier\",\"ids\":[22],\"tier\":1}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(monitorHistory).record(eq("INVENTORY"), eq(22L), any(), any(), eq("UPDATE"),
                before.capture(), after.capture(), any(), any(jakarta.servlet.http.HttpSession.class));
        assertThat(before.getValue()).containsEntry("ugTeamId", 3L);
        assertThat(after.getValue()).containsEntry("ugTeamId", 3L);
        assertThat(com.sitemonitor.service.MonitorHistoryService.changedFields(
                com.sitemonitor.service.AuditDiff.diff(before.getValue(), after.getValue()))).containsExactly("tier");
    }

    @Test
    @DisplayName("bulk: geçmiş satırı silmeyle AYNI işlemde — uç @Transactional (geri alınan toplu işlem 'silindi' izi bırakmaz)")
    void bulkInventoryAction_isTransactional() throws Exception {
        java.lang.reflect.Method m = AdminController.class.getMethod("bulkInventoryAction",
                Map.class, jakarta.servlet.http.HttpSession.class, jakarta.servlet.http.HttpServletRequest.class);
        assertThat(m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
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
                        .content("{\"warning_days\":25,\"high_days\":12,\"critical_days\":5,\"re_alert_interval_hours\":12}"))   // tel biçimi SNAKE_CASE
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /overview: global admin sınırsız kapsamla servise gider")
    void adminOverview() throws Exception {
        when(adminOverviewService.overview(isNull())).thenReturn(Map.of("counts", Map.of("teams", 3), "warnings", List.of()));
        mvc.perform(get("/api/admin/overview").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.teams").value(3));
    }

    // ── Takım sayaçları / etki / taşıma / üyelik (2026-09-20) ────────────────────

    @Test
    @DisplayName("GET /teams/stats: takım id anahtarlı sayaçlar")
    void teamStats() throws Exception {
        when(teamAdminService.stats()).thenReturn(new java.util.LinkedHashMap<>(Map.of(7L, Map.of("members", 3, "domains", 12))));
        mvc.perform(get("/api/admin/teams/stats").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['7'].members").value(3));
    }

    @Test
    @DisplayName("GET /teams/{id}/impact + POST /teams/{id}/move: etki listesi; taşıma servise gider ve denetlenir")
    void teamImpactAndMove() throws Exception {
        com.sitemonitor.model.Team a = new com.sitemonitor.model.Team(); a.setId(7L); a.setName("Takım A");
        com.sitemonitor.model.Team b = new com.sitemonitor.model.Team(); b.setId(9L); b.setName("Takım B");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(a));
        when(teamRepo.findById(9L)).thenReturn(Optional.of(b));
        when(teamAdminService.impact(7L)).thenReturn(Map.of("open_alerts", 2L, "empty", false));
        mvc.perform(get("/api/admin/teams/7/impact").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.open_alerts").value(2));

        when(teamAdminService.moveAll(7L, 9L)).thenReturn(new java.util.LinkedHashMap<>(Map.of("domains", 4, "monitors", 2, "users", 1, "contacts", 0, "groups", 1)));
        mvc.perform(post("/api/admin/teams/7/move").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target_team_id\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domains").value(4));
        verify(auditService).recordAction(eq("TEAM_MOVE_ASSETS"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("7"), any());

        mvc.perform(post("/api/admin/teams/7/move").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("2026-09-28: bağlı kaydı olan takım silinmez (409, izlemeler sahipsiz kalmaz); etki boşsa silinir")
    void deleteTeam_withLinkedRecords_returns409() throws Exception {
        when(teamAdminService.impact(7L)).thenReturn(Map.of("monitors_by_type", Map.of("http", 1), "empty", false));
        mvc.perform(delete("/api/admin/teams/7").session(authSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
        verify(userService, never()).deleteTeam(7L);

        when(teamAdminService.impact(8L)).thenReturn(Map.of("empty", true));
        mvc.perform(delete("/api/admin/teams/8").session(authSession()))
                .andExpect(status().isOk());
        verify(userService).deleteTeam(8L);
    }

    @Test
    @DisplayName("POST/DELETE /teams/{id}/members: üyelik kümesi updateUser ile yazılır; son takım çıkarılamaz (409)")
    void teamMembers() throws Exception {
        AppUser u = new AppUser(); u.setId(42L); u.setUsername("ali"); u.setSystemRole("USER"); u.setTeamId(9L);
        u.setTeamIds(new java.util.LinkedHashSet<>(List.of(9L)));
        when(userRepo.findById(42L)).thenReturn(Optional.of(u));
        when(userService.updateUser(eq(42L), isNull(), isNull(), isNull(), isNull(), any(), isNull(), isNull())).thenReturn(u);

        mvc.perform(post("/api/admin/teams/7/members").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(true));
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(userService).updateUser(eq(42L), isNull(), isNull(), isNull(), isNull(), ids.capture(), isNull(), isNull());
        assertThat(ids.getValue()).containsExactly(9L, 7L);
        verify(auditService).recordAction(eq("TEAM_MEMBER_ADD"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("7"), any());

        // tek takımı 9 → çıkarılamaz
        mvc.perform(delete("/api/admin/teams/9/members/42").session(authSession()))
                .andExpect(status().isConflict());
        // üyesi olmadığı takımdan çıkarma no-op
        mvc.perform(delete("/api/admin/teams/7/members/42").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(false));
    }

    // ── "Kim bilgilendirilir?" + webhook testi + son teslimat (2026-09-20) ───────

    @Test
    @DisplayName("GET /recipients/simulate: servise takım/seviye/tür geçer, takım adı ve push ayağı eklenir")
    void simulateRecipients_ok() throws Exception {
        when(escalationService.simulateRecipients(1L, "HIGH", true, null)).thenReturn(new java.util.LinkedHashMap<>(Map.of("level", "HIGH", "email_total", 2L)));
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team(); team.setId(1L); team.setName("Takım A");
        when(teamRepo.findById(1L)).thenReturn(Optional.of(team));
        when(userPushRecipientResolver.explain(1L, "HIGH")).thenReturn(List.of());

        mvc.perform(get("/api/admin/recipients/simulate").param("teamId", "1").param("level", "HIGH").param("kind", "MONITOR")
                        .session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_name").value("Takım A"))
                .andExpect(jsonPath("$.data.push").isArray());
    }

    @Test
    @DisplayName("GET /recipients/simulate?ugTeamId: UG servise geçer; kapsamlı kullanıcı görüş alanı dışındaki UG'yi soramaz (404)")
    void simulateRecipients_ugTeam_scoped() throws Exception {
        when(escalationService.simulateRecipients(2L, "HIGH", false, null, 5L))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("level", "HIGH", "email_total", 3L)));
        when(userPushRecipientResolver.explain(2L, "HIGH")).thenReturn(List.of());
        mvc.perform(get("/api/admin/recipients/simulate").param("teamId", "2").param("ugTeamId", "5").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email_total").value(3));
        verify(escalationService).simulateRecipients(2L, "HIGH", false, null, 5L);

        mvc.perform(get("/api/admin/recipients/simulate").param("teamId", "2").param("ugTeamId", "5")
                        .session(scopedAdminSession()))
                .andExpect(status().isNotFound());   // UG = başka takım → kişileri sızmasın
        verify(escalationService, org.mockito.Mockito.times(1)).simulateRecipients(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyBoolean(), any(), anyLong());
    }

    /** Push ayağı (2026-09-28): kişi satırları görünürlüğe göre; tel biçimi snake_case, telefon/adres yok. */
    private void stubPushScenario() {
        when(escalationService.simulateRecipients(eq(2L), eq("HIGH"), anyBoolean(), isNull()))
                .thenAnswer(inv -> new java.util.LinkedHashMap<>(Map.of("level", "HIGH", "email_total", 0L)));
        when(userPushRecipientResolver.explain(2L, "HIGH")).thenReturn(List.of(
                new com.sitemonitor.service.UserPushRecipientResolver.Explanation("N00001", "Kişi A", "Uzman", "TECH", true,
                        "uzman", true, "WARNING", false, "RECIPIENT"),
                new com.sitemonitor.service.UserPushRecipientResolver.Explanation("regularuser", "Kişi B", "Uzman", "PO", true,
                        "po", false, "WARNING", false, "GROUP_DISABLED"),
                new com.sitemonitor.service.UserPushRecipientResolver.Explanation("N00003", "Kişi C", null, null, true,
                        null, null, null, true, "NO_ORG_ROLE")));
        java.util.Map<String, Object> channel = new java.util.LinkedHashMap<>();
        channel.put("enabled", false); channel.put("configured", true); channel.put("block_reason", "CHANNEL_DISABLED");
        when(userPushService.scenarioChannel(2L, "HIGH", false)).thenReturn(channel);
    }

    @Test
    @DisplayName("Push ayağı: global yönetici ve takımı YÖNETEN müdür herkesi (alır + almaz) görür; kanal durumu snake_case")
    void simulateRecipients_pushLeg_fullForManagers() throws Exception {
        stubPushScenario();
        for (MockHttpSession s : List.of(authSession(), scopedAdminSession(), teamAdminSession())) {
            mvc.perform(get("/api/admin/recipients/simulate").param("teamId", "2").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.push_access").value("FULL"))
                    .andExpect(jsonPath("$.data.push.length()").value(3))
                    .andExpect(jsonPath("$.data.push[1].decision").value("GROUP_DISABLED"))
                    .andExpect(jsonPath("$.data.push[1].display_name").value("Kişi B"))
                    .andExpect(jsonPath("$.data.push[2].opt_out").value(true))
                    .andExpect(jsonPath("$.data.push_channel.block_reason").value("CHANNEL_DISABLED"))
                    .andExpect(jsonPath("$.data.push[0].phone").doesNotExist())
                    .andExpect(jsonPath("$.data.push[0].webhook_url").doesNotExist());
        }
        mvc.perform(get("/api/admin/recipients/simulate").param("teamId", "2").session(scopedAdminSession()))
                .andExpect(jsonPath("$.data.push_access_reason").value("TEAM_MANAGER"))
                .andExpect(jsonPath("$.data.push_settings").value("LIMITED"));
        mvc.perform(get("/api/admin/recipients/simulate").param("teamId", "2").session(authSession()))
                .andExpect(jsonPath("$.data.push_access_reason").value("GLOBAL_ADMIN"))
                .andExpect(jsonPath("$.data.push_settings").value("FULL"));
    }

    @Test
    @DisplayName("Push ayağı: üye (USER) yalnız KENDİ satırını; üyesi olmayan AUDIT hiç satır görmez ama nedenini alır")
    void simulateRecipients_pushLeg_selfAndNone() throws Exception {
        stubPushScenario();
        mvc.perform(get("/api/admin/recipients/simulate").param("teamId", "2").session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.push_access").value("SELF"))
                .andExpect(jsonPath("$.data.push_access_reason").value("MEMBER_SELF"))
                .andExpect(jsonPath("$.data.push.length()").value(1))
                .andExpect(jsonPath("$.data.push[0].username").value("regularuser"))
                .andExpect(jsonPath("$.data.push[0].decision").value("GROUP_DISABLED"))
                .andExpect(jsonPath("$.data.push_settings").value("NONE"));

        MockHttpSession audit = new MockHttpSession();
        audit.setAttribute("authenticated", Boolean.TRUE);
        audit.setAttribute("username", "auditor");
        audit.setAttribute("systemRole", "AUDIT");
        mvc.perform(get("/api/admin/recipients/simulate").param("teamId", "2").session(audit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.push_access").value("NONE"))
                .andExpect(jsonPath("$.data.push_access_reason").value("NOT_MEMBER"))
                .andExpect(jsonPath("$.data.push").doesNotExist())
                .andExpect(jsonPath("$.data.push_channel.enabled").value(false));
    }

    @Test
    @DisplayName("POST /contacts/{id}/webhook-test: gönderir, notification_log'a WEBHOOK_TEST yazar, denetler; webhook'suz kişi 400")
    void contactWebhookTest() throws Exception {
        EscalationContact c = contact("po@test.com", "PO"); c.setId(5L); c.setTeamId(1L);
        c.setWebhookUrl("https://hooks.example.com/services/T/B/x"); c.setWebhookType("SLACK");
        when(contactRepo.findById(5L)).thenReturn(Optional.of(c));

        mvc.perform(post("/api/admin/contacts/5/webhook-test").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"));
        verify(webhookService).send(eq("SLACK"), eq("https://hooks.example.com/services/T/B/x"), any(), any(), eq("INFO"));
        ArgumentCaptor<NotificationLog> logCap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo).save(logCap.capture());
        assertThat(logCap.getValue().getTrigger()).isEqualTo("WEBHOOK_TEST");
        assertThat(logCap.getValue().getAlertEventId()).isEqualTo(0L);   // NOT NULL kolon: alarmsız kayıt sentinel 0 (üretimde insert düşüyordu)
        assertThat(logCap.getValue().getWebhookStatus()).isEqualTo("SENT");
        verify(auditService).recordAction(eq("CONTACT_WEBHOOK_TEST"), any(), eq("ESCALATION_CONTACT"), eq("5"), eq("Test PO"), any());

        // gönderim düşerse FAILED + hata metni, 200 (kullanıcı sonucu görür)
        org.mockito.Mockito.doThrow(new RuntimeException("404 Not Found")).when(webhookService).send(any(), any(), any(), any(), any());
        mvc.perform(post("/api/admin/contacts/5/webhook-test").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.error").value("404 Not Found"));

        EscalationContact noHook = contact("x@test.com", "TECH"); noHook.setId(6L); noHook.setTeamId(1L);
        when(contactRepo.findById(6L)).thenReturn(Optional.of(noHook));
        mvc.perform(post("/api/admin/contacts/6/webhook-test").session(authSession()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /contacts/webhook-status: adres başına son teslimat, FAILED ayrıntısı ayrılır")
    void contactWebhookStatus() throws Exception {
        NotificationLog ok = new NotificationLog(); ok.setRecipientEmail("PO@Test.com"); ok.setWebhookStatus("SENT"); ok.setSentAt("2026-09-20T10:00:00"); ok.setTrigger("INITIAL");
        NotificationLog bad = new NotificationLog(); bad.setRecipientEmail("mgr@test.com"); bad.setWebhookStatus("FAILED: 404"); bad.setSentAt("2026-09-19T10:00:00"); bad.setTrigger("WEBHOOK_TEST");
        when(notificationLogRepo.findLatestWebhookPerRecipient()).thenReturn(List.of(ok, bad));
        mvc.perform(get("/api/admin/contacts/webhook-status").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['po@test.com'].status").value("SENT"))
                .andExpect(jsonPath("$.data['mgr@test.com'].status").value("FAILED"))
                .andExpect(jsonPath("$.data['mgr@test.com'].detail").value("404"));
    }

    // ── Eskalasyon kişileri — kapsamlı müdür (bug regresyon 2026-09-28, F1/F2/F8) ──

    /** Müdür: rol ADMIN, görüş/yönetim kapsamı takım 2 + 3, birincil takım 2 → global DEĞİL. */
    private MockHttpSession scopedAdminTwoTeams() {
        MockHttpSession s = scopedAdminSession();
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(2L, 3L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(List.of(2L, 3L)));
        return s;
    }

    /** Alfabetik İLK takım başka bir takım (1) — eski hata kişiyi buraya yazıyordu. */
    private void stubFirstTeamIsForeign() {
        Team first = new Team(); first.setId(1L); first.setName("Takım A");
        when(userService.listTeams()).thenReturn(List.of(first));
        when(contactRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("F1: kapsamlı müdürün team_id'si (kapsam içi) KULLANILIR — kişi alfabetik ilk takıma düşmez")
    void addContact_scopedAdmin_honoursTeamIdInScope() throws Exception {
        stubFirstTeamIsForeign();
        mvc.perform(post("/api/admin/contacts").session(scopedAdminTwoTeams())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kişi\",\"email\":\"kisi@example.com\",\"role\":\"TECH\",\"team_id\":3,"
                                + "\"webhook_url\":\"https://hooks.example.com/x\",\"webhook_type\":\"TEAMS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(3));
        ArgumentCaptor<EscalationContact> cap = ArgumentCaptor.forClass(EscalationContact.class);
        verify(contactRepo).save(cap.capture());
        assertThat(cap.getValue().getTeamId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("F1: kapsamlı müdür YÖNETMEDİĞİ takıma kişi (e-posta + webhook) ekleyemez → 403, kayıt yok")
    void addContact_scopedAdmin_teamOutsideScope_returns403() throws Exception {
        stubFirstTeamIsForeign();
        mvc.perform(post("/api/admin/contacts").session(scopedAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kişi\",\"email\":\"kisi@example.com\",\"role\":\"TECH\",\"team_id\":99,"
                                + "\"webhook_url\":\"https://hooks.example.com/x\"}"))
                .andExpect(status().isForbidden());
        verify(contactRepo, never()).save(any());
    }

    @Test
    @DisplayName("F1: kapsamlı müdür team_id vermezse BİRİNCİL takımı (2) yazılır — ilk takım (1) DEĞİL")
    void addContact_scopedAdmin_noTeam_usesPrimaryTeam() throws Exception {
        stubFirstTeamIsForeign();
        mvc.perform(post("/api/admin/contacts").session(scopedAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kişi\",\"email\":\"kisi@example.com\",\"role\":\"TECH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(2));
    }

    @Test
    @DisplayName("F1: birincil takımı yönetim kapsamında olmayan müdür takımsız ekleyemez → 403 (ilk takıma düşme YOK)")
    void addContact_scopedAdmin_primaryOutsideScope_returns403() throws Exception {
        stubFirstTeamIsForeign();
        MockHttpSession s = scopedAdminSession();
        s.setAttribute("teamId", 5L);   // birincil 5, yönetim kapsamı [2]
        mvc.perform(post("/api/admin/contacts").session(s)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kişi\",\"email\":\"kisi@example.com\",\"role\":\"TECH\"}"))
                .andExpect(status().isForbidden());
        verify(contactRepo, never()).save(any());
    }

    @Test
    @DisplayName("F1: TEAM_ADMIN'in gövdedeki team_id'si ezilir — kendi takımı (2) yazılır (eski davranış korunur)")
    void addContact_teamAdmin_forcedToOwnTeam() throws Exception {
        stubFirstTeamIsForeign();
        mvc.perform(post("/api/admin/contacts").session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kişi\",\"email\":\"kisi@example.com\",\"role\":\"TECH\",\"team_id\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(2));
    }

    @Test
    @DisplayName("2026-09-28: global yönetici takımsız kişi ekleyemez → 400 gerekçeli, kayıt yok (alfabetik ilk takıma DÜŞMEZ)")
    void addContact_globalAdmin_noTeam_returns400() throws Exception {
        stubFirstTeamIsForeign();
        mvc.perform(post("/api/admin/contacts").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kişi\",\"email\":\"kisi@example.com\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("takım")));
        verify(contactRepo, never()).save(any());
    }

    @Test
    @DisplayName("F1: kapsamlı müdür kişiyi YÖNETMEDİĞİ takıma taşıyamaz (403, kayıt yok); yönettiği takıma taşır")
    void updateContact_scopedAdmin_moveRespectsManageScope() throws Exception {
        EscalationContact existing = contact("po@example.com", "PO"); existing.setId(9L); existing.setTeamId(2L);
        when(contactRepo.findById(9L)).thenReturn(Optional.of(existing));
        when(contactRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/contacts/9").session(scopedAdminTwoTeams())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PO\",\"email\":\"po@example.com\",\"team_id\":99}"))
                .andExpect(status().isForbidden());
        verify(contactRepo, never()).save(any());
        assertThat(existing.getTeamId()).isEqualTo(2L);

        mvc.perform(put("/api/admin/contacts/9").session(scopedAdminTwoTeams())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PO\",\"email\":\"po@example.com\",\"team_id\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(3));
    }

    @Test
    @DisplayName("F2: webhook-status kapsamlı görüntüleyiciye (müdür/TEAM_ADMIN/USER) yalnız GÖRDÜĞÜ kişilerin adreslerini döner")
    void contactWebhookStatus_scopedViewer_onlyContactsInViewScope() throws Exception {
        NotificationLog mine = new NotificationLog(); mine.setRecipientEmail("po@example.com"); mine.setWebhookStatus("SENT"); mine.setSentAt("2026-09-20T10:00:00"); mine.setTrigger("INITIAL");
        NotificationLog foreign = new NotificationLog(); foreign.setRecipientEmail("baska@example.com"); foreign.setWebhookStatus("FAILED: 500 gizli ayrıntı"); foreign.setSentAt("2026-09-20T11:00:00"); foreign.setTrigger("INITIAL");
        when(notificationLogRepo.findLatestWebhookPerRecipient()).thenReturn(List.of(mine, foreign));
        EscalationContact own = contact("PO@Example.com", "PO"); own.setTeamId(2L);   // harf farkı eşleşmeyi bozmaz
        when(contactRepo.findByTeamIdInOrderByRoleAsc(List.of(2L))).thenReturn(List.of(own));

        for (MockHttpSession s : List.of(scopedAdminSession(), teamAdminSession(), userSession())) {
            mvc.perform(get("/api/admin/contacts/webhook-status").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data['po@example.com'].status").value("SENT"))
                    .andExpect(jsonPath("$.data['baska@example.com']").doesNotExist());
        }
        // Global yönetici hepsini görür (süzgeç yalnız kapsamlı görüntüleyicide).
        mvc.perform(get("/api/admin/contacts/webhook-status").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['baska@example.com'].status").value("FAILED"));
    }

    @Test
    @DisplayName("F8: webhook testi YÖNETİM kapsamı ister — kişiyi yalnız GÖREN USER 403; başka takımın kişisi müdüre 403; kendi takımı 200")
    void contactWebhookTest_requiresManageScope() throws Exception {
        EscalationContact c = contact("po@example.com", "PO"); c.setId(5L); c.setTeamId(2L);
        c.setWebhookUrl("https://hooks.example.com/services/T/B/x"); c.setWebhookType("SLACK");
        when(contactRepo.findById(5L)).thenReturn(Optional.of(c));
        EscalationContact foreign = contact("baska@example.com", "PO"); foreign.setId(6L); foreign.setTeamId(7L);
        foreign.setWebhookUrl("https://hooks.example.com/services/T/B/y"); foreign.setWebhookType("SLACK");
        when(contactRepo.findById(6L)).thenReturn(Optional.of(foreign));

        mvc.perform(post("/api/admin/contacts/5/webhook-test").session(userSession()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/contacts/6/webhook-test").session(scopedAdminSession()))
                .andExpect(status().isForbidden());
        verify(webhookService, never()).send(any(), any(), any(), any(), any());

        mvc.perform(post("/api/admin/contacts/5/webhook-test").session(scopedAdminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"));
        verify(webhookService).send(eq("SLACK"), eq("https://hooks.example.com/services/T/B/x"), any(), any(), eq("INFO"));
    }

    // ── Yönetim Paneli değişiklik geçmişi (2026-09-20) ────────────────────────

    @Test
    @DisplayName("GET /history?resource=TEAM: satırlar eylem öneki kırpılmış, takım adı çözülmüş döner")
    void adminHistory_team() throws Exception {
        com.sitemonitor.model.AuditLog row = new com.sitemonitor.model.AuditLog();
        row.setId(11L); row.setEventType("TEAM_UPDATE"); row.setEventTime("2026-09-20T10:00:00");
        row.setActor("admin"); row.setResourceType("TEAM"); row.setResourceId("7"); row.setDetail("Takım A");
        row.setChanges("{\"name\":{\"from\":\"A\",\"to\":\"Takım A\"}}");
        when(adminHistoryService.history(eq("TEAM"), isNull(), isNull(), isNull(), eq(0), eq(25)))
                .thenReturn(new com.sitemonitor.service.AdminHistoryService.History(
                        List.of(new com.sitemonitor.service.AdminHistoryService.Entry(row, 7L)), 1, 0, 25, false, 0));
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team(); team.setId(7L); team.setName("Takım A");
        when(teamRepo.findAll()).thenReturn(List.of(team));

        mvc.perform(get("/api/admin/history").param("resource", "TEAM").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.items[0].team_name").value("Takım A"))
                .andExpect(jsonPath("$.items[0].name").value("Takım A"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.total_pages").value(1))
                .andExpect(jsonPath("$.types[0]").value("TEAM_CREATE"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    @DisplayName("GET /history (2026-09-28c): eylemi yapanın IP'si kimlik izi — kapsamlı müdür / USER'da düşer (satır işaretli); global admin / AUDIT görür")
    void adminHistory_masksActorIpForNonGlobal() throws Exception {
        com.sitemonitor.model.AuditLog row = new com.sitemonitor.model.AuditLog();
        row.setId(12L); row.setEventType("TEAM_UPDATE"); row.setEventTime("2026-09-20T10:00:00");
        row.setActor("baskasi"); row.setResourceType("TEAM"); row.setResourceId("2"); row.setDetail("Takım A");
        row.setIpAddress("203.0.113.88");
        when(adminHistoryService.history(eq("TEAM"), isNull(), isNull(), any(), eq(0), eq(25)))
                .thenReturn(new com.sitemonitor.service.AdminHistoryService.History(
                        List.of(new com.sitemonitor.service.AdminHistoryService.Entry(row, 2L)), 1, 0, 25, false, 0));
        for (MockHttpSession s : List.of(scopedAdminSession(), userSession())) {
            String body = mvc.perform(get("/api/admin/history").param("resource", "TEAM").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].ip").doesNotExist())
                    .andExpect(jsonPath("$.items[0].identity_masked").value(true))
                    .andExpect(jsonPath("$.items[0].actor").value("baskasi"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("203.0.113.88");
        }
        MockHttpSession audit = authSession();
        audit.setAttribute("systemRole", "AUDIT");
        for (MockHttpSession s : List.of(authSession(), audit)) {
            mvc.perform(get("/api/admin/history").param("resource", "TEAM").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].ip").value("203.0.113.88"))
                    .andExpect(jsonPath("$.identity_masked").value(false));
        }
    }

    @Test
    @DisplayName("Geçmiş satırı adı: düz metin aynen; JSON detail'den name/team çekilir; anahtarsız JSON → null")
    void historyNameExtraction() {
        assertThat(AdminController.historyName("Takım A")).isEqualTo("Takım A");
        assertThat(AdminController.historyName("{\"name\":\"Takım A\",\"leaderId\":5}")).isEqualTo("Takım A");
        assertThat(AdminController.historyName("{\"team\":\"Takim A\",\"enabled\":false}")).isEqualTo("Takim A");
        assertThat(AdminController.historyName("{\"enabled\":false}")).isNull();
        assertThat(AdminController.historyName(null)).isNull();
    }

    @Test
    @DisplayName("GET /history bilinmeyen kaynak → 400")
    void adminHistory_unknownResource() throws Exception {
        mvc.perform(get("/api/admin/history").param("resource", "MONITOR").session(authSession()))
                .andExpect(status().isBadRequest());
    }

    // ── Tier bazlı eşikler (2026-09-20) ───────────────────────────────────────

    @Test
    @DisplayName("POST /thresholds tier=1: tier satırı yaratılır, ad boşsa tier-1 olur")
    void createTierThreshold_ok() throws Exception {
        when(thresholdRepo.findByTierOrderByIdAsc(1)).thenReturn(List.of());
        when(thresholdRepo.save(any())).thenAnswer(inv -> { AlertThreshold t = inv.getArgument(0); t.setId(9L); return t; });

        mvc.perform(post("/api/admin/thresholds").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":1,\"warning_days\":60,\"high_days\":30,\"critical_days\":14}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tier").value(1))
                .andExpect(jsonPath("$.data.name").value("tier-1"));
        verify(auditService).recordAction(eq("THRESHOLD_CREATE"), any(), eq("ALERT_THRESHOLD"), eq("9"), eq("tier-1"), any());
    }

    @Test
    @DisplayName("POST /thresholds aynı tier'a ikinci satır → 409; ters sıra (kritik > uyarı) → 400; tier 7 → 400")
    void createTierThreshold_validation() throws Exception {
        AlertThreshold t1 = defaultThreshold(); t1.setTier(1);
        when(thresholdRepo.findByTierOrderByIdAsc(1)).thenReturn(List.of(t1));
        mvc.perform(post("/api/admin/thresholds").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":1,\"warning_days\":60,\"high_days\":30,\"critical_days\":14}"))
                .andExpect(status().isConflict());

        when(thresholdRepo.findByTierOrderByIdAsc(2)).thenReturn(List.of());
        mvc.perform(post("/api/admin/thresholds").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":2,\"warning_days\":10,\"high_days\":30,\"critical_days\":40}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/admin/thresholds").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":7,\"warning_days\":60,\"high_days\":30,\"critical_days\":14}"))
                .andExpect(status().isBadRequest());
        verify(thresholdRepo, never()).save(any());
    }

    @Test
    @DisplayName("PUT /thresholds kısmi gövde mevcut değerlerle birleşip sıra denetiminden geçer; bozuk sıra 400")
    void updateThreshold_orderValidation() throws Exception {
        AlertThreshold existing = defaultThreshold();   // 30/15/7
        when(thresholdRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(thresholdRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(put("/api/admin/thresholds/1").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"critical_days\":20}"))     // 20 > yüksek 15 → bozuk
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/thresholds/1").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"critical_days\":10}"))     // 10 ≤ 15 ≤ 30
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /thresholds/{id}: tier satırı silinir + denetim; VARSAYILAN satır 409")
    void deleteThreshold() throws Exception {
        AlertThreshold tier = defaultThreshold(); tier.setId(5L); tier.setTier(2); tier.setName("tier-2");
        when(thresholdRepo.findById(5L)).thenReturn(Optional.of(tier));
        mvc.perform(delete("/api/admin/thresholds/5").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
        verify(thresholdRepo).delete(tier);
        verify(auditService).recordAction(eq("THRESHOLD_DELETE"), any(), eq("ALERT_THRESHOLD"), eq("5"), eq("tier-2"), any());
        verify(thresholdPreviewService, atLeastOnce()).afterThresholdChange();   // kart seviyeleri eşikten türer → cache boşalır

        when(thresholdRepo.findById(1L)).thenReturn(Optional.of(defaultThreshold()));
        mvc.perform(delete("/api/admin/thresholds/1").session(authSession()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /thresholds/preview servise tier + günleri geçirir; bozuk sıra 400")
    void previewThreshold() throws Exception {
        when(thresholdPreviewService.preview(1, 60, 30, 14)).thenReturn(Map.of("scope_total", 3));
        mvc.perform(get("/api/admin/thresholds/preview").session(authSession())
                        .param("tier", "1").param("warning", "60").param("high", "30").param("critical", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope_total").value(3));
        mvc.perform(get("/api/admin/thresholds/preview").session(authSession())
                        .param("warning", "5").param("high", "30").param("critical", "14"))
                .andExpect(status().isBadRequest());
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
        Team t1 = new Team(); t1.setId(1L); when(teamRepo.findById(1L)).thenReturn(Optional.of(t1));   // takım var (2026-09-28)
        EscalationContact saved = contact("new@test.com", "TECH");
        saved.setId(1L);
        when(contactRepo.save(any())).thenReturn(saved);

        mvc.perform(post("/api/admin/contacts")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test User\",\"email\":\"new@test.com\",\"role\":\"TECH\",\"minAlertLevel\":\"WARNING\",\"team_id\":1}"))
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

    // ── Zamana bağlı eskalasyon adımı: kişi gecikmesi (2026-10-01, opt-in) ─────────────────────

    @Test
    @DisplayName("PUT contact: delay_minutes 30 kaydedilir; anahtar gönderilmezse DOKUNULMAZ; boş/0 gecikmeyi kaldırır")
    void updateContact_delayMinutes_setKeptCleared() throws Exception {
        EscalationContact existing = contact("mgr@test.com", "MANAGER");
        existing.setId(1L);
        existing.setTeamId(1L);
        when(contactRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(contactRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/contacts/1").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"M\",\"email\":\"mgr@test.com\",\"role\":\"MANAGER\",\"delay_minutes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delay_minutes").value(30));
        assertThat(existing.getDelayMinutes()).isEqualTo(30);

        // Eski istemci / gecikmesiz form: anahtar YOK → değer korunur.
        mvc.perform(put("/api/admin/contacts/1").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"M\",\"email\":\"mgr@test.com\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk());
        assertThat(existing.getDelayMinutes()).isEqualTo(30);

        mvc.perform(put("/api/admin/contacts/1").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"M\",\"email\":\"mgr@test.com\",\"role\":\"MANAGER\",\"delay_minutes\":null}"))
                .andExpect(status().isOk());
        assertThat(existing.getDelayMinutes()).isNull();

        mvc.perform(put("/api/admin/contacts/1").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"M\",\"email\":\"mgr@test.com\",\"role\":\"MANAGER\",\"delay_minutes\":0}"))
                .andExpect(status().isOk());
        assertThat(existing.getDelayMinutes()).isNull();
    }

    @Test
    @DisplayName("Kişi gecikmesi 1–1440 dışında → 400 (kullanıcının dilinde mesaj), kayıt yapılmaz")
    void contact_delayMinutes_outOfRange_returns400() throws Exception {
        EscalationContact existing = contact("mgr@test.com", "MANAGER");
        existing.setId(1L);
        existing.setTeamId(1L);
        when(contactRepo.findById(1L)).thenReturn(Optional.of(existing));

        mvc.perform(put("/api/admin/contacts/1").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lang", "en")
                        .content("{\"name\":\"M\",\"email\":\"mgr@test.com\",\"role\":\"MANAGER\",\"delay_minutes\":1441}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("between 1 and 1440")));
        mvc.perform(post("/api/admin/contacts").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"M\",\"email\":\"mgr@test.com\",\"role\":\"MANAGER\",\"team_id\":1,\"delay_minutes\":\"abc\"}"))
                .andExpect(status().isBadRequest());
        verify(contactRepo, never()).save(any());
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
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0));
    }

    // ── Sütun sıralaması (2026-10-01): sort + dir beyaz listeli, varsayılan açılış DESC ─────────────────

    private List<Sort.Order> alertSortFor(String query) throws Exception {
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        mvc.perform(get("/api/admin/alerts" + query).session(authSession())).andExpect(status().isOk());
        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), cap.capture());
        return cap.getValue().getSort().toList();
    }

    @Test
    @DisplayName("GET /api/admin/alerts → varsayılan sıralama createdAt DESC; kapalı görünümde resolvedAt DESC + createdAt DESC")
    void listAlerts_defaultSort() throws Exception {
        List<Sort.Order> o = alertSortFor("");
        assertThat(o).hasSize(1);
        assertThat(o.get(0).getProperty()).isEqualTo("createdAt");
        assertThat(o.get(0).getDirection()).isEqualTo(Sort.Direction.DESC);

        org.mockito.Mockito.clearInvocations(alertEventRepo);
        List<Sort.Order> c = alertSortFor("?resolved=true");
        assertThat(c.stream().map(Sort.Order::getProperty).toList()).containsExactly("resolvedAt", "createdAt");
        assertThat(c).allMatch(x -> x.getDirection() == Sort.Direction.DESC);
    }

    @Test
    @DisplayName("GET /api/admin/alerts?sort=level&dir=asc → seviye CASE sırası (asc) + createdAt DESC eşitlik bozucu")
    void listAlerts_sortLevel_usesCaseRank() throws Exception {
        List<Sort.Order> o = alertSortFor("?sort=level&dir=asc");
        assertThat(o).hasSize(2);
        assertThat(o.get(0).getProperty()).isEqualTo(AdminController.AlertSort.LEVEL_RANK);
        assertThat(o.get(0).getProperty()).contains("CRITICAL").contains("HIGH").contains("WARNING");
        assertThat(o.get(0).getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(o.get(1).getProperty()).isEqualTo("createdAt");
        assertThat(o.get(1).getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("GET /api/admin/alerts?sort=<bilinmeyen>&dir=<bilinmeyen> → beyaz liste dışı anahtar varsayılana düşer (enjeksiyon yok)")
    void listAlerts_unknownSort_fallsBackToDefault() throws Exception {
        List<Sort.Order> o = alertSortFor("?sort=e.domain;DROP&dir=sideways");
        assertThat(o).hasSize(1);
        assertThat(o.get(0).getProperty()).isEqualTo("createdAt");
        assertThat(o.get(0).getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("GET /api/admin/alerts?sort=team|domain|type|opened|resolved → beyaz listedeki her anahtar kendi özelliğine gider (team → takım ADI ifadesi)")
    void listAlerts_whitelistedSortKeys_mapToProperties() throws Exception {
        Map<String, String> expect = Map.of("team", AdminController.AlertSort.TEAM_NAME, "domain", "domain", "type", "alertType", "opened", "createdAt", "resolved", "resolvedAt");
        for (Map.Entry<String, String> e : expect.entrySet()) {
            org.mockito.Mockito.clearInvocations(alertEventRepo);
            List<Sort.Order> o = alertSortFor("?sort=" + e.getKey() + "&dir=desc");
            assertThat(o.get(0).getProperty()).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(o.get(0).getDirection()).isEqualTo(Sort.Direction.DESC);
        }
    }

    @Test
    @DisplayName("GET /api/admin/alerts → damgalı takımın adı team_name olarak gelir (Takım sütunu)")
    void listAlerts_enrichment_stampedTeamName() throws Exception {
        AlertEvent ev = new AlertEvent();
        ev.setId(303L); ev.setDomain("mon.example.com"); ev.setAlertType("HTTP_DOWN"); ev.setAlertLevel("HIGH");
        ev.setTeamId(9L);
        Team t9 = new Team(); t9.setId(9L); t9.setName("Ops");
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ev)));
        when(inventoryRepo.findByDomainIn(any())).thenReturn(Collections.emptyList());
        when(teamRepo.findAllById(any())).thenReturn(List.of(t9));

        mvc.perform(get("/api/admin/alerts").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].team_id").value(9))
                .andExpect(jsonPath("$.data[0].team_name").value("Ops"))
                .andExpect(jsonPath("$.data[0].sy_team_name").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/admin/alerts?alertType=ACCESSIBILITY filters by type and returns type_counts")
    void listAlerts_alertTypeFilter_passedToQueryWithCounts() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(),
                eq("ACCESSIBILITY"), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class))).thenReturn(empty);
        when(alertEventRepo.countFilteredByType(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(
                        new Object[]{"EXPIRY", 8L},
                        new Object[]{"ACCESSIBILITY", 2L}));

        mvc.perform(get("/api/admin/alerts?alertType=ACCESSIBILITY").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.type_counts.EXPIRY").value(8))
                .andExpect(jsonPath("$.type_counts.ACCESSIBILITY").value(2));

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(),
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
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts?alertTypes=PAGESPEED_DOWN,PAGESPEED_SLOW").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(),
                eq(true), eq(List.of("PAGESPEED_DOWN", "PAGESPEED_SLOW")),
                any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
        // Tip cipleri de AYNI kapsamda sayilmali; aksi halde ekranda gorunmeyen bir tip icin
        // "SSL Sertifika Sorunu: 1" cipi cikardi.
        org.mockito.Mockito.verify(alertEventRepo).countFilteredByType(any(), any(), any(), any(), any(), any(), any(),
                eq(true), eq(List.of("PAGESPEED_DOWN", "PAGESPEED_SLOW")),
                any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("alertTypes VERILMEZSE tip kapsami UYGULANMAZ (bagimsiz Alarm Gecmisi ekrani)")
    void listAlerts_noAlertTypes_noTypeScope() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts").session(authSession())).andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(),
                eq(false), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("alertTypes SERBEST METIN degil: gecersiz jetonlar elenir, gecerliler kalir")
    void listAlerts_alertTypes_sanitised() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        // Bosluk/kucuk harf normalize edilir; tirnak-noktali virgul tasiyan jeton ATILIR.
        mvc.perform(get("/api/admin/alerts?alertTypes= page_down , DROP;TABLE ,PAGE_DOWN")
                        .session(authSession()))
                .andExpect(status().isOk());

        // Tekrar eden PAGE_DOWN bir kez; "DROP;TABLE" elendi.
        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(),
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
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts?alertTypes=DROP;TABLE").session(authSession()))
                .andExpect(status().isOk());

        // typeScoped=TRUE + hicbir seye uymayan sentinel liste → sorgu bos doner.
        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(),
                eq(true), eq(List.of("-")),
                any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/admin/alerts without alertType passes null to query")
    void listAlerts_noAlertType_passesNull() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(empty);

        mvc.perform(get("/api/admin/alerts").session(authSession()))
                .andExpect(status().isOk());

        // alertType + dort YENI filtre (q/level/acknowledged/teamId) verilmediginde hepsi NULL
        // gitmeli: bos string ya da "" gecerse sorgu her seyi eler ve ekran bos gorunur.
        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(),
                isNull(), anyBoolean(), any(), isNull(), isNull(), isNull(), isNull(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/admin/alerts?onlyOpen=true returns only open alerts")
    void listAlerts_onlyOpen_returnsOpenAlerts() throws Exception {
        Page<AlertEvent> empty = new PageImpl<>(Collections.emptyList());
        when(alertEventRepo.findFiltered(eq(Boolean.FALSE), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
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

        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
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

        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
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

    /**
     * 7/24 (NOC) satırı alarmı gören HERKESE açık: gövdede arama listesinin telefonları, alıcıda 7/24 grubunun adresleri
     * sızmamalı (yayın öncesi inceleme 2026-09-27). Eski/elle yazılmış MASKESİZ bir satır bile okuma yüzeyinde maskelenir.
     */
    @Test
    @DisplayName("GET /alerts/{id}/notifications: 7/24 satırında telefon ve adres YOK — takım üyesi, kapsamlı müdür, denetçi")
    void getAlertNotifications_nocRowIsRedactedForEveryViewer() throws Exception {
        AlertEvent ev = new AlertEvent();
        ev.setId(1L); ev.setTeamId(2L); ev.setDomain("www.example.com");
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(ev));
        com.sitemonitor.model.NotificationLog noc = new com.sitemonitor.model.NotificationLog();
        noc.setId(10L); noc.setAlertEventId(1L); noc.setRecipientRole("NOC"); noc.setTrigger("NOC_OPEN");
        noc.setRecipientEmail("noc@example.com, yedek@example.com");   // eski biçim: adresler
        noc.setMessage("<p>Kişi A <a href=\"tel:+905550000012\" target=\"_blank\">+90 555 000 00 12</a></p>");
        com.sitemonitor.model.NotificationLog team = new com.sitemonitor.model.NotificationLog();
        team.setId(11L); team.setAlertEventId(1L); team.setRecipientRole("COMBINED");
        team.setRecipientEmail("takim@example.com"); team.setMessage("<p>takım</p>");
        when(notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(1L)).thenReturn(List.of(noc, team));

        MockHttpSession audit = new MockHttpSession();
        audit.setAttribute("authenticated", Boolean.TRUE);
        audit.setAttribute("username", "denetci");
        audit.setAttribute("systemRole", "AUDIT");
        for (MockHttpSession s : List.of(userSession(), scopedAdminSession(), audit)) {
            String body = mvc.perform(get("/api/admin/alerts/1/notifications").session(s))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("noc@example.com").doesNotContain("yedek@example.com")
                    .doesNotContain("tel:+905550000012").doesNotContain("555 000 00 12").doesNotContain("5550000012")
                    .contains("2 adres").contains("takim@example.com");   // takım satırı olduğu gibi
        }
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
        Team t1 = new Team(); t1.setId(1L); when(teamRepo.findById(1L)).thenReturn(Optional.of(t1));   // takım var (2026-09-28)
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
                        .content("{\"user_id\":10,\"role\":\"TECH\",\"min_alert_level\":\"WARNING\",\"team_id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(userRepo).findById(10L);
    }

    @Test
    @DisplayName("POST /api/admin/contacts with unknown user_id still saves contact")
    void addContact_withUnknownUserId_stillSaves() throws Exception {
        Team t1 = new Team(); t1.setId(1L); when(teamRepo.findById(1L)).thenReturn(Optional.of(t1));   // takım var (2026-09-28)
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
                        .content("{\"user_id\":999,\"role\":\"PO\",\"min_alert_level\":\"HIGH\",\"team_id\":1}"))
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
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(alertEventRepo.countFilteredByType(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(Collections.emptyList());

        mvc.perform(get("/api/admin/alerts").session(userSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"x.com\",\"port\":443,\"team_id\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.team_id").value(2));
    }

    // ── "Domain Ekle" her kullanıcı seviyesinde (2026-09-18): USER kendi takımına ekler, başkasına 403 ──
    @Test
    @DisplayName("POST /inventory as USER on OWN team → 200 (üyelik kapısı)")
    void addInventory_asUser_ownTeam_returns200() throws Exception {
        CertificateInventory saved = inventory("uye.example.com"); saved.setId(5L); saved.setTeamId(2L);
        when(inventoryRepo.save(any())).thenReturn(saved);
        mvc.perform(post("/api/admin/inventory")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"uye.example.com\",\"port\":443,\"team_id\":2}"))
                .andExpect(status().isOk());
        verify(inventoryRepo).save(any());
    }

    @Test
    @DisplayName("POST /inventory as USER on ANOTHER team → 403 (üyesi değil)")
    void addInventory_asUser_otherTeam_returns403() throws Exception {
        mvc.perform(post("/api/admin/inventory")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"yabanci.example.com\",\"port\":443,\"team_id\":9}"))
                .andExpect(status().isForbidden());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("PUT /inventory as USER on OWN team → 200; gönderilen team_id YOK SAYILIR (aktarım yok)")
    void updateInventory_asUser_ownTeam_editsButCannotTransfer() throws Exception {
        CertificateInventory existing = inventory("uye.example.com"); existing.setId(6L); existing.setTeamId(2L);
        when(inventoryRepo.findById(6L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(put("/api/admin/inventory/6")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"uye.example.com\",\"port\":8443,\"active\":true,\"team_id\":9}"))
                .andExpect(status().isOk());
        assertThat(existing.getPort()).isEqualTo(8443);
        assertThat(existing.getTeamId()).isEqualTo(2L);   // takım 9'a aktarılmadı
    }

    @Test
    @DisplayName("PUT /inventory as USER on ANOTHER team → 403; DELETE kendi takımında bile 403 (silme yönetici işi)")
    void updateInventory_asUser_otherTeam_403_deleteStillAdmin() throws Exception {
        CertificateInventory other = inventory("yabanci.example.com"); other.setId(7L); other.setTeamId(9L);
        when(inventoryRepo.findById(7L)).thenReturn(Optional.of(other));
        mvc.perform(put("/api/admin/inventory/7")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"yabanci.example.com\",\"port\":443,\"active\":true}"))
                .andExpect(status().isForbidden());
        CertificateInventory own = inventory("uye.example.com"); own.setId(8L); own.setTeamId(2L);
        when(inventoryRepo.findById(8L)).thenReturn(Optional.of(own));
        mvc.perform(delete("/api/admin/inventory/8").session(userSession()))
                .andExpect(status().isForbidden());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("POST /api/admin/inventory as TEAM_ADMIN on a team it does NOT manage returns 403")
    void addInventory_asTeamAdmin_outOfScopeTeam_returns403() throws Exception {
        // Faz 3b: yönetim kapsamı dışındaki takıma (999) ekleme reddedilir.
        mvc.perform(post("/api/admin/inventory")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"x.com\",\"port\":443,\"team_id\":999}"))
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"x.com\",\"port\":443}"))
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
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"x.com\",\"port\":443}"))
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

    @Test
    @DisplayName("PUT /teams/{id} sessiz saat (2026-10-01): anahtarlar varsa normalize edilip yazılır; yoksa dokunulmaz")
    void updateTeam_quietHours_appliedOnlyWhenPresent() throws Exception {
        Team updated = new Team();
        updated.setId(2L);
        updated.setName("Ödeme");
        when(userService.updateTeam(eq(2L), any(), any(), any(), any(), any(), any(), any())).thenReturn(updated);
        when(userService.updateTeamQuietHours(eq(2L), any())).thenReturn(updated);

        mvc.perform(put("/api/admin/teams/2")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ödeme\",\"quiet_start\":\"22:00\",\"quiet_end\":\"07:00\","
                                + "\"quiet_days\":[\"FRI\",\"MON\"],\"quiet_min_level\":\"\"}"))
                .andExpect(status().isOk());
        verify(userService).updateTeamQuietHours(2L,
                new com.sitemonitor.service.QuietHours.Config("22:00", "07:00", "MON,FRI", null));

        org.mockito.Mockito.clearInvocations(userService);
        mvc.perform(put("/api/admin/teams/2")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ödeme\"}"))
                .andExpect(status().isOk());
        verify(userService, never()).updateTeamQuietHours(anyLong(), any());
    }

    @Test
    @DisplayName("PUT /teams/{id} hatalı sessiz saat → 400 ve HİÇBİR alan yazılmaz (doğrulama önce)")
    void updateTeam_invalidQuietHours_400_nothingSaved() throws Exception {
        mvc.perform(put("/api/admin/teams/2")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Yeni Ad\",\"quiet_start\":\"22:00\",\"quiet_end\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(userService, never()).updateTeam(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(userService, never()).updateTeamQuietHours(anyLong(), any());
    }

    @Test
    @DisplayName("POST /teams/bulk (2026-09-20): deactivate her takım ayrı geçer, kapsam dışı satır düşer; set_manager manager_id doğrular; bilinmeyen işlem 400")
    void bulkTeams() throws Exception {
        when(userService.updateTeam(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> { Team tm = new Team(); tm.setId(inv.getArgument(0)); tm.setName("T" + inv.getArgument(0)); return tm; });
        when(userService.updateTeamManager(anyLong(), any()))
                .thenAnswer(inv -> { Team tm = new Team(); tm.setId(inv.getArgument(0)); tm.setName("T" + inv.getArgument(0)); return tm; });
        // TEAM_ADMIN yalnız 2 numaralı takımı yönetir → 3 kapsam dışı (satır düşer, diğerleri sürer)
        mvc.perform(post("/api/admin/teams/bulk").session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deactivate\",\"ids\":[2,3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(1))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.results[0].ok").value(true))
                .andExpect(jsonPath("$.data.results[1].ok").value(false));
        verify(userService).updateTeam(eq(2L), isNull(), isNull(), isNull(), eq(false), isNull(), isNull(), isNull());
        verify(auditService).recordAction(eq("TEAM_BULK_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("bulk"), any());

        when(userRepo.existsById(77L)).thenReturn(true);
        mvc.perform(post("/api/admin/teams/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_manager\",\"ids\":[2],\"manager_id\":77}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(1));
        verify(userService).updateTeamManager(2L, 77L);

        mvc.perform(post("/api/admin/teams/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set_manager\",\"ids\":[2],\"manager_id\":404}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/teams/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"frobnicate\",\"ids\":[2]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/teams/bulk").session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"weekly_reminder_on\",\"ids\":[]}"))
                .andExpect(status().isBadRequest());
    }

    // ── Haftalık e-posta anahtarları: takım ÜYELERİNE açık dar uç ─────────────
    // teams.update yetkisi olmayan sıradan USER kendi takımının iki anahtarını çevirebilmeli,
    // ama BAŞKA takımınkini çevirememeli (IDOR) ve ad/e-posta gibi alanlara dokunamamalı.

    private void stubWeeklyToggle() {
        Team t = new Team();
        t.setId(2L);
        t.setName("Dijital");
        t.setWeeklyReminderEnabled(true);
        when(userService.updateTeamWeeklyNotifications(anyLong(), any(), any(), any())).thenReturn(t);
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

        verify(userService).updateTeamWeeklyNotifications(eq(2L), eq(Boolean.TRUE), isNull(), isNull());
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

        verify(userService).updateTeamWeeklyNotifications(eq(7L), isNull(), eq(Boolean.FALSE), isNull());
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

        verify(userService, never()).updateTeamWeeklyNotifications(anyLong(), any(), any(), any());
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

        verify(userService).updateTeamWeeklyNotifications(eq(2L), eq(Boolean.TRUE), isNull(), isNull());
        verify(userService, never()).updateTeam(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PUT weekly-notifications: weekly_channels listesi temizlenip JSON dizi olur; boş liste şablonu kaldırır (\"\"); alan yoksa null")
    void weeklyNotifications_channelsTemplate() throws Exception {
        stubWeeklyToggle();
        stubMembership(42L, 2L);

        mvc.perform(put("/api/admin/teams/2/weekly-notifications")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_channels\":[\" Mobil \",\"\",\"Mobil\",\"Şube\"]}"))
                .andExpect(status().isOk());
        verify(userService).updateTeamWeeklyNotifications(eq(2L), isNull(), isNull(), eq("[\"Mobil\",\"Şube\"]"));

        mvc.perform(put("/api/admin/teams/2/weekly-notifications")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_channels\":[]}"))
                .andExpect(status().isOk());
        verify(userService).updateTeamWeeklyNotifications(eq(2L), isNull(), isNull(), eq(""));
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

    // ── Users: uyuyan hesap süzgeci + toplu işlem (2026-09-20) ─────────────────

    @Test
    @DisplayName("GET /users/search?dormantDays=90: uyuyan süzgeci ayrı sorguya gider, kesim ISO-UTC")
    void searchUsers_dormant() throws Exception {
        when(userRepo.findFilteredDormant(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));
        when(userRepo.countBySystemRoleAndActiveTrue("ADMIN")).thenReturn(1L);
        mvc.perform(get("/api/admin/users/search").param("dormantDays", "90").session(authSession()))
                .andExpect(status().isOk());
        ArgumentCaptor<String> cut = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(userRepo).findFilteredDormant(isNull(), isNull(), isNull(), isNull(), cut.capture(), eq(false), any());
        assertThat(cut.getValue()).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");
        // hiç girmemiş
        mvc.perform(get("/api/admin/users/search").param("neverLoggedIn", "true").session(authSession()))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(userRepo).findFilteredDormant(isNull(), isNull(), isNull(), isNull(), isNull(), eq(true), any());
        org.mockito.Mockito.verify(userRepo, never()).findFiltered(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /users/bulk deactivate: her kullanıcı ayrı geçer; kendini pasifleştirme satırı düşer, diğerleri sürer; özet denetimi")
    void bulkUsers_deactivate() throws Exception {
        when(userService.updateUser(anyLong(), any(), any(), any(), any(), any(), eq(false), any()))
                .thenAnswer(inv -> { AppUser u = new AppUser(); u.setId(inv.getArgument(0)); u.setUsername("u" + inv.getArgument(0)); return u; });
        MockHttpSession s = authSession();
        s.setAttribute("userId", 50L);
        AppUser self = new AppUser(); self.setId(50L); self.setUsername("me"); self.setSystemRole("ADMIN"); self.setActive(true); self.setTeamId(1L);
        when(userRepo.findById(50L)).thenReturn(Optional.of(self));

        mvc.perform(post("/api/admin/users/bulk").session(s)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deactivate\",\"ids\":[7,50,8]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(2))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.results[1].ok").value(false));
        verify(auditService).recordAction(eq("USER_BULK_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("USER"), eq("bulk"), any());

        mvc.perform(post("/api/admin/users/bulk").session(s)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"frobnicate\",\"ids\":[7]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/users/bulk").session(s)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"assign_team\",\"ids\":[7]}"))
                .andExpect(status().isBadRequest());   // team_id yok
    }

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
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(Collections.emptyList()));
    }

    @Test
    @DisplayName("Arama: kısmi + büyük/küçük harf duyarsız — sorguya %küçük harf% olarak gider")
    void listAlerts_search_isLowercasedAndWrapped() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts?q=Example").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq("%example%"), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("Arama JOKERLERİ kaçışlanır — tek bir '%' aramayı sessizce filtresiz bırakmasın")
    void listAlerts_search_escapesWildcards() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts").param("q", "%_a").session(authSession()))
                .andExpect(status().isOk());

        // '%' ve '_' kullanıcı verisidir, joker DEĞİL: kaçışlanmazsa "%" araması TÜM kayıtları getirir
        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq("%!%!_a%"), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("Boş/boşluk arama param üretmez (filtresiz sorgu, boş sonuç değil)")
    void listAlerts_blankSearch_passesNull() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts").param("q", "   ").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                isNull(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("Seviye filtresi BÜYÜK HARFE çevrilir (critical → CRITICAL)")
    void listAlerts_levelIsUppercased() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts?level=critical").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), eq("CRITICAL"), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("acknowledged ve teamId filtreleri sorguya AYNEN geçer")
    void listAlerts_acknowledgedAndTeamPassThrough() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts?acknowledged=false&teamId=7").session(authSession()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), eq(Boolean.FALSE), eq(7L), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("Tip SAYAÇLARI yeni filtreleri dikkate alır ama tip filtresinden BAĞIMSIZ kalır")
    void listAlerts_typeCountsHonourNewFiltersButNotType() throws Exception {
        stubEmptyAlerts();
        when(alertEventRepo.countFilteredByType(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn(List.<Object[]>of(new Object[]{"EXPIRY", 3L}));

        mvc.perform(get("/api/admin/alerts?alertType=EXPIRY&q=ak&level=HIGH").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type_counts.EXPIRY").value(3));

        // Sayaç sorgusunda alertType YOK (imzada zaten yok) ama q/level VAR:
        // aksi halde arama yapınca rozet sayıları toplamla çelişirdi.
        org.mockito.Mockito.verify(alertEventRepo).countFilteredByType(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                eq("%ak%"), eq("HIGH"), any(), any(), anyBoolean(), any());
    }

    // ── "Aralıkta aktif olanlar" kipi (2026-09-28, regresyon B3) ──────────────
    //
    // Haftalık e-posta "Haftanın alarmları"nı hafta içinde AÇIK olan tüm alarmlar olarak sayar (önceki haftadan
    // devredenler dahil) ve Alarm Geçmişi'ni range=active ile açar. Kip YALNIZ tarih yüklemini değiştirir: since
    // açılış alt sınırı olmaktan çıkar, aktiflik alt sınırı (activeFrom) olur; until açılış üst sınırı kalır. Yüklemin
    // kendisi gerçek SQL'de AlertActiveRangeQueryTest'te sınanır.

    private static final String WEEK_FROM = "2026-09-20T21:00:00", WEEK_TO = "2026-09-27T20:59:59";

    @Test
    @DisplayName("range=active: since → aktiflik sınırı (açılış süzgeci null), until açılışta kalır; liste + tip + faset sayaçları aynı kipte")
    void listAlerts_rangeActive_usesOverlapWindow() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts").param("since", WEEK_FROM).param("until", WEEK_TO).param("range", "active")
                        .session(authSession()))
                .andExpect(status().isOk());

        verify(alertEventRepo).findFiltered(any(), isNull(), eq(WEEK_TO), any(), any(), eq(WEEK_FROM), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
        verify(alertEventRepo).countFilteredByType(any(), isNull(), eq(WEEK_TO), any(), any(), eq(WEEK_FROM), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(alertEventRepo).countFacets(any(), isNull(), eq(WEEK_TO), any(), any(), eq(WEEK_FROM), any(), any(),
                anyBoolean(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("range YOK / tanınmayan değer: eski davranış birebir — since açılış alt sınırı, aktiflik süzgeci null")
    void listAlerts_defaultRange_isOpenedInRange() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts").param("since", WEEK_FROM).param("until", WEEK_TO).session(authSession()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/alerts").param("since", WEEK_FROM).param("until", WEEK_TO).param("range", "opened")
                        .session(authSession()))
                .andExpect(status().isOk());

        verify(alertEventRepo, org.mockito.Mockito.times(2)).findFiltered(any(), eq(WEEK_FROM), eq(WEEK_TO), any(), any(), isNull(),
                any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
        verify(alertEventRepo, never()).findFiltered(any(), any(), any(), any(), any(), eq(WEEK_FROM),
                any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("range=active kapsamı GEVŞETMEZ: kapsamlı müdür (ADMIN + viewTeamIds=[2]) yine yalnız takım 2 ile sorgular; e-postanın team= süzgeci kapsamı genişletmez")
    void listAlerts_rangeActive_keepsScopedAdminScope() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts").param("since", WEEK_FROM).param("until", WEEK_TO).param("range", "active")
                        .param("teamId", "7").session(scopedAdminSession()))
                .andExpect(status().isOk());

        verify(alertEventRepo).findFiltered(any(), isNull(), eq(WEEK_TO), any(), any(), eq(WEEK_FROM), any(), any(),
                anyBoolean(), any(), any(), any(), any(), eq(7L), eq(true), eq(List.of(2L)), any(Pageable.class));
    }

    @Test
    @DisplayName("CSV dışa aktarım ekranla AYNI kipte: range=active → aktiflik sınırı, açılış süzgeci null")
    void exportAlerts_rangeActive_sameWindowAsList() throws Exception {
        stubEmptyAlerts();

        mvc.perform(get("/api/admin/alerts/export").param("since", WEEK_FROM).param("until", WEEK_TO).param("range", "active")
                        .session(authSession()))
                .andExpect(status().isOk());

        verify(alertEventRepo).findFiltered(any(), isNull(), eq(WEEK_TO), any(), any(), eq(WEEK_FROM), any(), any(),
                anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(Pageable.class));
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

    // ── Kod incelemesi 2026-09-09: kapsamlı müdür kullanıcı yönetimi ──────────

    /** AD-kaynaklı ADMIN (müdür): rol ADMIN ama görüş/yönetim kapsamı takım 2 ile sınırlı → global DEĞİL. */
    private MockHttpSession scopedAdminSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "mudur");
        s.setAttribute("userId", 77L);
        s.setAttribute("teamId", 2L);
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(java.util.List.of(2L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(java.util.List.of(2L)));
        return s;
    }

    @Test
    @DisplayName("KRİTİK: kapsamlı müdür system_role=ADMIN ile kullanıcı yaratamaz (global admin üretimi) → 403")
    void createUser_asScopedAdmin_withAdminRole_returns403() throws Exception {
        mvc.perform(post("/api/admin/users")
                        .session(scopedAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"pw\",\"system_role\":\"ADMIN\",\"team_ids\":[]}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .createUser(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Kapsamlı müdür yönetim kapsamı DIŞINDAKİ takıma kullanıcı yazamaz → 403")
    void createUser_asScopedAdmin_teamOutsideScope_returns403() throws Exception {
        mvc.perform(post("/api/admin/users")
                        .session(scopedAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"y\",\"password\":\"pw\",\"system_role\":\"USER\",\"team_id\":99}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Kapsamlı müdür kendi takımına USER yaratabilir → 200")
    void createUser_asScopedAdmin_inScope_returns200() throws Exception {
        AppUser created = new AppUser();
        created.setId(8L); created.setUsername("z"); created.setTeamId(2L); created.setSystemRole("USER");
        when(userService.createUser(any(), any(), any(), any(), any(), eq("USER"), eq(java.util.List.of(2L)), any()))
                .thenReturn(created);
        mvc.perform(post("/api/admin/users")
                        .session(scopedAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"z\",\"password\":\"pw\",\"system_role\":\"USER\",\"team_id\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(2));
    }

    @Test
    @DisplayName("TEAM_ADMIN kendi takımındaki GLOBAL ADMIN hesabını düzenleyemez/parolasını sıfırlayamaz → 403")
    void updateUser_asTeamAdmin_onAdminTarget_returns403() throws Exception {
        AppUser target = new AppUser();
        target.setId(5L); target.setUsername("globaladmin"); target.setSystemRole("ADMIN"); target.setTeamId(2L); target.setActive(true);
        when(userRepo.findById(5L)).thenReturn(Optional.of(target));

        mvc.perform(put("/api/admin/users/5")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"ele gecirildi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/5/auto-reset-password")
                        .session(teamAdminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admin_password\":\"pw\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/inventory/{id} domain'i oluşturma yoluyla aynı normalize eder (küçük harf)")
    void updateInventory_normalizesDomainCase() throws Exception {
        CertificateInventory existing = inventory("old.com");
        existing.setId(1L);
        when(inventoryRepo.findById(1L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/admin/inventory/1")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"OLD.COM\",\"port\":443,\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("old.com"));
        // Harf farkı rename DEĞİLDİR: geçmiş tabloları taşınmaz, satır aynı anahtarla kalır.
        org.mockito.Mockito.verify(latestCheckRepo, org.mockito.Mockito.never()).renameDomain(any(), any());
    }

    // ── 2026-09-10: kapalı alarm kartı gerçek bitişi okur; eski satırlara LatestCheck yedeği ──

    @Test
    @DisplayName("GET /alerts: not_after damgasız EXPIRY olayına LatestCheck.not_after yedeği, current_not_after her sertifika olayına yazılır")
    void listAlerts_enrichesNotAfterFromLatestCheck() throws Exception {
        com.sitemonitor.model.AlertEvent stamped = new com.sitemonitor.model.AlertEvent();
        stamped.setId(1L); stamped.setDomain("a.example.com"); stamped.setAlertType("EXPIRY"); stamped.setAlertLevel("WARNING");
        stamped.setResolved(true); stamped.setNotAfter("2026-09-22T23:59:59"); stamped.setCreatedAt("2026-09-01T00:00:00");
        com.sitemonitor.model.AlertEvent legacy = new com.sitemonitor.model.AlertEvent();
        legacy.setId(2L); legacy.setDomain("b.example.com"); legacy.setAlertType("EXPIRY"); legacy.setAlertLevel("WARNING");
        legacy.setResolved(true); legacy.setCreatedAt("2026-09-01T00:00:00");
        com.sitemonitor.model.LatestCheck la = new com.sitemonitor.model.LatestCheck(); la.setDomain("a.example.com"); la.setNotAfter("2026-12-31T23:59:59");
        com.sitemonitor.model.LatestCheck lb = new com.sitemonitor.model.LatestCheck(); lb.setDomain("b.example.com"); lb.setNotAfter("2026-10-05T10:00:00");
        when(latestCheckRepo.findByDomainIn(any())).thenReturn(java.util.List.of(la, lb));
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(stamped, legacy)));

        mvc.perform(get("/api/admin/alerts?resolved=true").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].not_after").value("2026-09-22T23:59:59"))
                .andExpect(jsonPath("$.data[0].current_not_after").value("2026-12-31T23:59:59"))
                .andExpect(jsonPath("$.data[1].not_after").value("2026-10-05T10:00:00"))
                .andExpect(jsonPath("$.data[1].current_not_after").value("2026-10-05T10:00:00"));
    }

    // ── Tanılama erişim düzeltmeleri (2026-10-05) ──────────────────────────────────────────────

    private static com.sitemonitor.model.DomainMonitor domainMonitor(String domain, Long teamId) {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(77L);
        m.setDomain(domain);
        m.setTeamId(teamId);
        return m;
    }

    @Test
    @DisplayName("domain-expiry (2026-10-05): USER kendi takımının BAĞIMSIZ alan adı izlemesini tanılar (envanterde olmasa da)")
    void domainExpiry_userOwnDomainMonitor_allowed() throws Exception {
        when(domainMonitorRepo.findByDomain("own-registry.example.test"))
                .thenReturn(List.of(domainMonitor("own-registry.example.test", 2L)));
        when(domainExpiryDiagnosticsService.diagnose("own-registry.example.test"))
                .thenReturn(Map.of("domain", "own-registry.example.test", "source", "RDAP", "steps", List.of()));
        mvc.perform(post("/api/admin/diagnostics/domain-expiry")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"own-registry.example.test\"}"))
                .andExpect(status().isOk());
        verify(domainExpiryDiagnosticsService).diagnose("own-registry.example.test");
        verify(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("diagnostics.run"), eq("execute"));
    }

    @Test
    @DisplayName("domain-expiry (2026-10-05): başka takımın alan adı izlemesi / izlenmeyen alan adı → 403 (yetki genişlemez)")
    void domainExpiry_otherTeamOrUnknown_forbidden() throws Exception {
        when(domainMonitorRepo.findByDomain("other-team.example.test"))
                .thenReturn(List.of(domainMonitor("other-team.example.test", 9L)));
        mvc.perform(post("/api/admin/diagnostics/domain-expiry")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"other-team.example.test\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/diagnostics/domain-expiry")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"nobody-monitors.example.test\"}"))
                .andExpect(status().isForbidden());
        verify(domainExpiryDiagnosticsService, never()).diagnose(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Durum/Sertifika tanılaması (2026-10-05 doğrulama): USER yalnız kendi takımının envanter kaydında; başka takımınki 403")
    void connectionDiagnostics_teamScopedInventory() throws Exception {
        com.sitemonitor.model.CertificateInventory own = new com.sitemonitor.model.CertificateInventory();
        own.setDomain("own-inventory.example.test");
        own.setTeamId(2L);
        com.sitemonitor.model.CertificateInventory other = new com.sitemonitor.model.CertificateInventory();
        other.setDomain("other-inventory.example.test");
        other.setTeamId(9L);
        when(inventoryRepo.findByDomain("own-inventory.example.test")).thenReturn(Optional.of(own));
        when(inventoryRepo.findByDomain("other-inventory.example.test")).thenReturn(Optional.of(other));
        when(diagnosticsService.diagnose("own-inventory.example.test", 443)).thenReturn(Map.of(
                "domain", "own-inventory.example.test", "port", 443, "combos", List.of(Map.of("id", "direct+browser", "status", "ok"))));
        mvc.perform(post("/api/admin/diagnostics")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"own-inventory.example.test\",\"port\":443}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/diagnostics")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"other-inventory.example.test\",\"port\":443}"))
                .andExpect(status().isForbidden());
        verify(diagnosticsService, never()).diagnose("other-inventory.example.test", 443);
        // İzin kapısı da çalışır: diagnostics.run reddedilirse kendi takımında bile 403
        org.mockito.Mockito.doThrow(new SecurityException("izin yok"))
                .when(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("diagnostics.run"), eq("execute"));
        mvc.perform(post("/api/admin/diagnostics")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"own-inventory.example.test\",\"port\":443}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("tanılama geçmişi kaydı (2026-10-05): izleme tanılamaları (ping/port/DNS/HTTP/keyword) bu uçtan kimlikle bile açılmaz")
    void diagnosticsHistoryDetail_refusesMonitorRuns() throws Exception {
        for (String[] r : new String[][]{ {"11", "ping-monitor:4", "PING_DIAG"}, {"12", "port-monitor:4", "PORT_DIAG"},
                {"13", "dns-monitor:4", "DNS_DIAG"}, {"14", "http-monitor:4", "HTTP_DIAG"}, {"15", "keyword-monitor:4", "KEYWORD_DIAG"},
                {"17", "page-monitor:4", "PAGE_DIAG"}, {"18", "pagespeed-monitor:4", "PAGESPEED_DIAG"} }) {
            com.sitemonitor.model.DiagnosticRun d = new com.sitemonitor.model.DiagnosticRun();
            d.setId(Long.valueOf(r[0]));
            d.setDomain(r[1]);
            d.setRunType(r[2]);
            d.setResultJson("{}");
            when(diagnosticHistoryService.get(Long.valueOf(r[0]))).thenReturn(d);
            mvc.perform(get("/api/admin/diagnostics/history/" + r[0]).session(authSession()))
                    .andExpect(status().isNotFound());
        }
        com.sitemonitor.model.DiagnosticRun cert = new com.sitemonitor.model.DiagnosticRun();
        cert.setId(16L);
        cert.setDomain("example.com");
        cert.setRunType("CONNECTION");
        when(diagnosticHistoryService.get(16L)).thenReturn(cert);
        mvc.perform(get("/api/admin/diagnostics/history/16").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_type").value("CONNECTION"));
    }

    // ── Envanter ekleme KARAKTERİZASYONU (2026-10-06) ─────────────────────────────────────────────────────────
    // Manuel sertifika takibi, ekleme kapılarını paylaşmak için addInventory'yi ortak bir yönteme ayırdı. Bu testler
    // ağ kaydı yolunun SIRASINI ve yan etkilerini sabitler: kapı sırası, damga → kayıt → DOMAIN_ADD → geçmiş CREATE →
    // anında tek-domain kontrolü. Yeniden düzenleme bunlardan birini değiştirirse kırmızıya döner.

    @Test
    @DisplayName("karakterizasyon: ağ kaydı ekleme — damga → kayıt → DOMAIN_ADD → geçmiş CREATE → anında kontrol, bu sırayla")
    void addInventory_characterization_sideEffectOrder() throws Exception {
        when(inventoryRepo.save(any())).thenAnswer(a -> { CertificateInventory i = a.getArgument(0); i.setId(5L); return i; });
        when(monitoringGroupService.getOrCreate(any(), eq("cert"), any(), any())).thenAnswer(a -> a.getArgument(2));

        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"Sira.Example.com\",\"port\":8443,"
                                + "\"team_id\":1,\"use_proxy\":true,\"tls_mode\":\"BROWSER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("sira.example.com"))
                .andExpect(jsonPath("$.data.cert_source").doesNotExist());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(monitorHistory, inventoryRepo, auditService, schedulerService);
        order.verify(monitorHistory).stampCreated(any(), any());
        order.verify(inventoryRepo).save(any());
        order.verify(auditService).recordAction(eq("DOMAIN_ADD"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("CERTIFICATE"), eq("sira.example.com"),
                eq("{\"port\":8443,\"teamId\":1}"));
        order.verify(monitorHistory).record(eq(com.sitemonitor.service.MonitorHistoryService.INVENTORY), eq(5L),
                eq("sira.example.com"), eq(1L), eq(com.sitemonitor.service.MonitorHistoryService.CREATE),
                isNull(), any(), isNull(), any());
        order.verify(schedulerService).checkSingleDomainAsync("sira.example.com", 8443, true, "browser");
    }

    @Test
    @DisplayName("karakterizasyon: kapı sırası — takım yoksa önce takım iletisi, sonra grup/etiket")
    void addInventory_characterization_guardOrder() throws Exception {
        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"takimsiz.example.com\",\"port\":443}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A team must be selected for the certificate"));
        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"grupsuz.example.com\",\"port\":443,\"team_id\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Grup seçimi zorunludur; kayıt kaydedilemez."));
        verify(inventoryRepo, never()).save(any());
        verify(schedulerService, never()).checkSingleDomainAsync(any(), org.mockito.ArgumentMatchers.anyInt(), anyBoolean(), any());
    }

    @Test
    @DisplayName("karakterizasyon: mükerrer alan adı (harf farkı dahil) 409 DOMAIN_EXISTS — kayıt ve kontrol yok")
    void addInventory_characterization_duplicate409() throws Exception {
        CertificateInventory clash = inventory("dup.example.com");
        clash.setId(9L);
        clash.setTeamId(1L);
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc("dup.example.com")).thenReturn(Optional.of(clash));
        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"DUP.example.com\",\"port\":443,\"team_id\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_EXISTS"))
                .andExpect(jsonPath("$.existing.inventory_id").value(9))
                // Silme KALICI (2026-10-07): çöp kutusu alanları / geri yükleme yolu yok
                .andExpect(jsonPath("$.existing.deleted").doesNotExist())
                .andExpect(jsonPath("$.existing.deleted_at").doesNotExist())
                .andExpect(jsonPath("$.existing.can_restore").doesNotExist());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("Silme KALICI (2026-10-07): silinen adın yeniden eklenmesi serbest — eski çöp satırı çakışma sayılmaz, önce kalıcı silinir")
    void addInventory_sameNameAsLegacyBinRow_succeeds() throws Exception {
        CertificateInventory bin = inventory("old.example.com");
        bin.setId(12L); bin.setTeamId(7L); bin.setDeletedAt("2026-09-01T00:00:00"); bin.setActive(false);
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc("old.example.com")).thenReturn(Optional.of(bin));
        when(inventoryRepo.save(any())).thenAnswer(a -> { CertificateInventory i = a.getArgument(0); i.setId(13L); return i; });

        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"old.example.com\",\"port\":443,\"team_id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(13));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(permanentDeletion, inventoryRepo);
        order.verify(permanentDeletion).purgeLegacyBinRows("old.example.com");   // aynı adlı eski satır ÖNCE gider
        order.verify(inventoryRepo).save(any());
    }

    @Test
    @DisplayName("Silme KALICI (2026-10-07): yeniden adlandırma eski çöp satırının adına engellenmez")
    void updateInventory_renameToLegacyBinName_succeeds() throws Exception {
        CertificateInventory existing = inventory("cur.example.com");
        existing.setId(20L); existing.setTeamId(1L); existing.setGroupName("Grup A"); existing.setTags("t1");
        CertificateInventory bin = inventory("was.example.com");
        bin.setId(21L); bin.setTeamId(1L); bin.setDeletedAt("2026-09-01T00:00:00");
        when(inventoryRepo.findById(20L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc("was.example.com")).thenReturn(Optional.of(bin));
        when(inventoryRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        mvc.perform(put("/api/admin/inventory/20")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"was.example.com\",\"port\":443,\"team_id\":1}"))
                .andExpect(status().isOk());
        verify(permanentDeletion).purgeLegacyBinRows("was.example.com");
        assertThat(existing.getDomain()).isEqualTo("was.example.com");
    }

    @Test
    @DisplayName("manuel sertifika: ağ ekleme gövdesindeki cert_source YOK SAYILIR (READ_ONLY) — kayıt ağ kaydı kalır")
    void addInventory_certSourceInBody_isIgnored() throws Exception {
        when(inventoryRepo.save(any())).thenAnswer(a -> { CertificateInventory i = a.getArgument(0); i.setId(6L); return i; });
        mvc.perform(post("/api/admin/inventory")
                        .session(authSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"src.example.com\",\"port\":443,"
                                + "\"team_id\":1,\"cert_source\":\"MANUAL\"}"))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<CertificateInventory> cap = org.mockito.ArgumentCaptor.forClass(CertificateInventory.class);
        verify(inventoryRepo).save(cap.capture());
        org.assertj.core.api.Assertions.assertThat(cap.getValue().getCertSource()).isNull();
        org.assertj.core.api.Assertions.assertThat(cap.getValue().isManual()).isFalse();
        verify(schedulerService).checkSingleDomainAsync("src.example.com", 443, false, null);
    }

    // ── Elle yüklenen sertifika kayıtları (2026-10-06) ───────────────────────────────────────────────────────────

    private CertificateInventory manualInventory(Long id, String key) {
        CertificateInventory inv = inventory(key);
        inv.setId(id);
        inv.setTeamId(1L);
        inv.setGroupName("Grup A");
        inv.setTags("t1");
        inv.setCertSource(CertificateInventory.SOURCE_MANUAL);
        return inv;
    }

    private static com.sitemonitor.model.ManualCertificateVersion manualVersion(Long invId, int v) {
        com.sitemonitor.model.ManualCertificateVersion mv = new com.sitemonitor.model.ManualCertificateVersion();
        mv.setInventoryId(invId);
        mv.setVersion(v);
        mv.setCurrent(true);
        mv.setUploadedAt("2026-10-05T09:00:00");
        return mv;
    }

    @Test
    @DisplayName("manuel: silme de KALICI (2026-10-07) — aynı uç, aynı servis (sürümler servis içinde gider: PermanentDeletionServiceTest)")
    void delete_manualRow_permanent() throws Exception {
        CertificateInventory manual = manualInventory(3L, "api-takip");
        when(inventoryRepo.findById(3L)).thenReturn(Optional.of(manual));
        mvc.perform(delete("/api/admin/inventory/3").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permanent").value(true));
        verify(permanentDeletion).deleteInventory(manual);
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("manuel: envanter listesi cert_source + manual_version + manual_uploaded_at (TEK sorgu); ağ satırında alan YOK")
    void listInventory_manualFields() throws Exception {
        CertificateInventory net = inventory("net.example.com");
        net.setId(1L);
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of(net, manualInventory(4L, "api-takip")));
        when(manualVersionRepo.findByInventoryIdInAndCurrentTrue(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(manualVersion(4L, 3)));
        mvc.perform(get("/api/admin/inventory").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cert_source").doesNotExist())
                .andExpect(jsonPath("$.data[0].manual_version").doesNotExist())
                .andExpect(jsonPath("$.data[1].cert_source").value("MANUAL"))
                .andExpect(jsonPath("$.data[1].manual_version").value(3))
                .andExpect(jsonPath("$.data[1].manual_uploaded_at").value("2026-10-05T09:00:00"))
                .andExpect(jsonPath("$.data[1].manual").doesNotExist());
        verify(manualVersionRepo, org.mockito.Mockito.times(1))
                .findByInventoryIdInAndCurrentTrue(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    @DisplayName("manuel: envanter listesinde manuel satır yoksa sürüm deposu HİÇ sorgulanmaz")
    void listInventory_noManualRows_noVersionQuery() throws Exception {
        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of(inventory("net.example.com")));
        mvc.perform(get("/api/admin/inventory").session(authSession())).andExpect(status().isOk());
        org.mockito.Mockito.verifyNoInteractions(manualVersionRepo);
    }

    @Test
    @DisplayName("manuel: tanılama uçları 409 MANUAL_CERT (ağ adresi yok) — tanılama servisleri çağrılmaz")
    void diagnostics_manualTarget_409() throws Exception {
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc("ocp_truststore"))
                .thenReturn(Optional.of(manualInventory(8L, "ocp_truststore")));
        for (String path : List.of("/api/admin/diagnostics", "/api/admin/diagnostics/openssl", "/api/admin/diagnostics/network",
                "/api/admin/diagnostics/hsts", "/api/admin/diagnostics/domain-expiry")) {
            mvc.perform(post(path).session(authSession()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"domain\":\"ocp_truststore\",\"port\":443}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MANUAL_CERT"));
        }
        mvc.perform(post("/api/admin/diagnostics/proxy-ca-chain").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"ocp_truststore\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANUAL_CERT"));
        verify(diagnosticsService, never()).diagnose(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("manuel: düzenlemede DEĞİŞMEYEN anahtar DomainNames'e girmez (alt çizgi geçerli), ağ alanları boş tutulur")
    void updateInventory_manualRow_unchangedKey() throws Exception {
        CertificateInventory existing = manualInventory(6L, "ocp_truststore");
        when(inventoryRepo.findById(6L)).thenReturn(Optional.of(existing));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        mvc.perform(put("/api/admin/inventory/6").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"ocp_truststore\",\"port\":8443,"
                                + "\"use_proxy\":true,\"tls_mode\":\"browser\",\"tier\":2}"))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<CertificateInventory> cap = org.mockito.ArgumentCaptor.forClass(CertificateInventory.class);
        verify(inventoryRepo).save(cap.capture());
        CertificateInventory saved = cap.getValue();
        assertThat(saved.getDomain()).isEqualTo("ocp_truststore");
        assertThat(saved.isManual()).isTrue();
        assertThat(saved.getPort()).isEqualTo(443);
        assertThat(saved.getUseProxy()).isNull();
        assertThat(saved.getTlsMode()).isNull();
        assertThat(saved.getTier()).isEqualTo(2);
        verify(latestCheckRepo, never()).renameDomain(any(), any());
    }

    @Test
    @DisplayName("manuel: anahtar değişirse manuel kuralla doğrulanır (geçersiz 400, geçerli yeniden adlandırılır)")
    void updateInventory_manualRow_changedKey() throws Exception {
        when(inventoryRepo.findById(6L)).thenReturn(Optional.of(manualInventory(6L, "eski-ad")));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        mvc.perform(put("/api/admin/inventory/6").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"Yeni Ad\"}"))
                .andExpect(status().isBadRequest());
        verify(inventoryRepo, never()).save(any());

        when(inventoryRepo.findById(6L)).thenReturn(Optional.of(manualInventory(6L, "eski-ad")));
        mvc.perform(put("/api/admin/inventory/6").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"yeni_ad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("yeni_ad"));
        verify(latestCheckRepo).renameDomain("eski-ad", "yeni_ad");
    }

    @Test
    @DisplayName("ağ kaydı: geçersiz domain biçimi düzenlemede BUGÜNKÜ 400 gövdesiyle reddedilir (fields.domain)")
    void updateInventory_networkRow_invalidDomain_unchanged400() throws Exception {
        CertificateInventory existing = inventory("old.example.com");
        existing.setId(7L);
        when(inventoryRepo.findById(7L)).thenReturn(Optional.of(existing));
        mvc.perform(put("/api/admin/inventory/7").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"a_b.example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.domain").value("Geçersiz domain formatı"))
                .andExpect(jsonPath("$.error").value("Geçersiz alan(lar): domain"));
        verify(inventoryRepo, never()).save(any());
        // Kayıt yoksa da (biçim hatası) yine 400 — eskiden olduğu gibi kayıt okunmadan önce reddedilir.
        mvc.perform(put("/api/admin/inventory/99").session(authSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"a_b.example.com\"}"))
                .andExpect(status().isBadRequest());
    }
}
