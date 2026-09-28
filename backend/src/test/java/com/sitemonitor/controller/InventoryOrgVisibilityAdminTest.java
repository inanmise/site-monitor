package com.sitemonitor.controller;

import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.InventoryVisibility;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Org geneli envanter görünürlüğü (2026-09-26, kullanıcı kararı) — AdminController yüzeyi.
 *
 * <p>İki yönlü sözleşme: (1) OKUMA genişler — ayar açıkken {@code scope=all} başka takımların silinmemiş
 * kayıtlarını, by-domain başka takımın kaydını TAM döner; her satır {@code can_manage} taşır. (2) YAZMA
 * genişlemez — başka takımın kaydına dokunan HER uç (düzenle, sil, geri yükle, kalıcı sil, aktar, toplu,
 * kontrol kaydını sil, not ekle/düzenle/sil, "yeniden ekleyerek devralma") 403/409 ile reddeder ve kendi
 * takımında çalışmaya devam eder.
 *
 * <p>Oturumlar gerçek {@code AuthController.applyTeamScope} biçiminde kurulur: USER (görüş = üyelik, yönetim
 * boş), TEAM_ADMIN (görüş = yönetim = liderlik + üyelik), kapsamlı AD ADMIN (görüş = yönetim = kendi + ast
 * takımları; {@code viewTeamIds} dolu olduğu için GLOBAL DEĞİL), global ADMIN ve AUDIT (görüş kapsamı yok).
 */
@WebMvcTest(AdminController.class)
@Import(InventoryVisibility.class)
class InventoryOrgVisibilityAdminTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Autowired MockMvc mvc;

    /** Ayar kaynağı — InventoryVisibility bunu canlı okur; @BeforeEach açık kurar, testler kapatabilir. */
    @MockitoBean AppSettingsService appSettings;

    @MockitoBean com.sitemonitor.repository.UserPushDeliveryRepository userPushDeliveryRepo;
    @MockitoBean com.sitemonitor.repository.NotificationGroupRepository notificationGroupRepo;
    @MockitoBean com.sitemonitor.service.ThresholdPreviewService thresholdPreviewService;
    @MockitoBean com.sitemonitor.service.AdminHistoryService adminHistoryService;
    @MockitoBean com.sitemonitor.service.UserPushRecipientResolver userPushRecipientResolver;
    @MockitoBean com.sitemonitor.service.WebhookService webhookService;
    @MockitoBean com.sitemonitor.service.TeamAdminService teamAdminService;
    @MockitoBean com.sitemonitor.service.AdminOverviewService adminOverviewService;
    @MockitoBean com.sitemonitor.service.DerivedMonitorTeamSync derivedMonitorTeamSync;
    @MockitoBean com.sitemonitor.service.TourStateService tourStateService;
    @MockitoBean com.sitemonitor.service.UserPushService userPushService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean AlertThresholdRepository thresholdRepo;
    @MockitoBean EscalationContactRepository contactRepo;
    @MockitoBean AlertEventRepository alertEventRepo;
    @MockitoBean EscalationService escalationService;
    @MockitoBean NotificationLogRepository notificationLogRepo;
    @MockitoBean LatestCheckRepository latestCheckRepo;
    @MockitoBean CertificateCheckRepository certificateCheckRepo;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.service.MonitorHistoryService monitorHistory;
    @MockitoBean com.sitemonitor.service.SsrfGuard ssrfGuard;
    @MockitoBean CertificateNoteRepository noteRepo;
    @MockitoBean CertificateNoteRevisionRepository noteRevisionRepo;
    @MockitoBean AppUserRepository userRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean com.sitemonitor.service.EmailNotificationService emailNotificationService;
    @MockitoBean com.sitemonitor.service.ConnectionDiagnosticsService diagnosticsService;
    @MockitoBean com.sitemonitor.service.OpensslDiagnosticsService opensslDiagnosticsService;
    @MockitoBean com.sitemonitor.service.NetworkDiagnosticsService networkDiagnosticsService;
    @MockitoBean com.sitemonitor.service.HstsDiagnosticsService hstsDiagnosticsService;
    @MockitoBean com.sitemonitor.service.DiagnosticHistoryService diagnosticHistoryService;
    @MockitoBean com.sitemonitor.service.DomainExpiryDiagnosticsService domainExpiryDiagnosticsService;
    @MockitoBean com.sitemonitor.service.DomainExpiryRefreshService domainExpiryRefreshService;
    @MockitoBean com.sitemonitor.service.ProxyCaExportService proxyCaExportService;
    @MockitoBean com.sitemonitor.service.PublicSuffixService publicSuffixService;
    @MockitoBean com.sitemonitor.service.ClientIpResolver clientIpResolver;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;
    @MockitoBean com.sitemonitor.service.MonitoringGroupService monitoringGroupService;
    @MockitoBean com.sitemonitor.service.SchedulerService schedulerService;

    private static final long OWN_TEAM = 5L, SUB_TEAM = 6L, FOREIGN_TEAM = 9L;

    private CertificateInventory own, foreign, goneForeign, goneOwn;

    // ── Oturumlar (AuthController.applyTeamScope biçimi) ──────────────────────────────────────────

    private static MockHttpSession base(String role, String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("displayName", username);
        s.setAttribute("systemRole", role);
        return s;
    }

    static MockHttpSession user() {
        MockHttpSession s = base("USER", "u1");
        s.setAttribute("teamId", OWN_TEAM);
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        return s;
    }

    static MockHttpSession teamAdmin() {
        MockHttpSession s = base("TEAM_ADMIN", "u1");
        s.setAttribute("teamId", OWN_TEAM);
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        s.setAttribute("manageTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        return s;
    }

    /** AD kaynaklı ADMIN (müdür): rol ADMIN ama takım kapsamlı — kendi + astının takımı. */
    static MockHttpSession scopedAdmin() {
        MockHttpSession s = base("ADMIN", "u1");
        s.setAttribute("teamId", OWN_TEAM);
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(OWN_TEAM, SUB_TEAM)));
        s.setAttribute("manageTeamIds", new ArrayList<>(List.of(OWN_TEAM, SUB_TEAM)));
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        return s;
    }

    static MockHttpSession globalAdmin() {
        MockHttpSession s = base("ADMIN", "root");
        s.setAttribute("memberTeamIds", new ArrayList<Long>());
        return s;
    }

    static MockHttpSession audit() {
        MockHttpSession s = base("AUDIT", "denetci");
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        s.setAttribute("memberTeamIds", new ArrayList<Long>());
        return s;
    }

    /** Takım-kapsamlı üç rol: org geneli okumayı ALIR, yazmayı ALMAZ. */
    static Stream<Arguments> scopedRoles() {
        return Stream.of(
                Arguments.of("USER", (Supplier<MockHttpSession>) InventoryOrgVisibilityAdminTest::user),
                Arguments.of("TEAM_ADMIN", (Supplier<MockHttpSession>) InventoryOrgVisibilityAdminTest::teamAdmin),
                Arguments.of("scoped AD ADMIN", (Supplier<MockHttpSession>) InventoryOrgVisibilityAdminTest::scopedAdmin));
    }

    /** Yazma yetkisi olan iki takım-kapsamlı rol (TEAM_ADMIN + kapsamlı müdür). */
    static Stream<Arguments> managerRoles() {
        return Stream.of(
                Arguments.of("TEAM_ADMIN", (Supplier<MockHttpSession>) InventoryOrgVisibilityAdminTest::teamAdmin),
                Arguments.of("scoped AD ADMIN", (Supplier<MockHttpSession>) InventoryOrgVisibilityAdminTest::scopedAdmin));
    }

    private static CertificateInventory inv(long id, String domain, Long teamId) {
        CertificateInventory i = new CertificateInventory();
        i.setId(id); i.setDomain(domain); i.setTeamId(teamId); i.setPort(443); i.setActive(true);
        i.setGroupName("Grup A"); i.setTags("prod");
        return i;
    }

    private void switchOn(boolean on) {
        when(appSettings.getBoolean(eq(InventoryVisibility.SETTING_KEY), anyBoolean())).thenReturn(on);
    }

    @BeforeEach
    void setUp() {
        when(teamRepo.existsById(anyLong())).thenReturn(true);   // O3 varlık denetimi: varsayılan 'takım var'
        own = inv(1L, "own.example.com", OWN_TEAM);
        own.setCreatedIp("10.0.0.1");
        foreign = inv(2L, "foreign.example.com", FOREIGN_TEAM);
        foreign.setCreatedIp("10.0.0.2");
        foreign.setSvcMgmtContact("Takım B - destek@example.com");
        foreign.setPlatform("IIS");
        foreign.setDescription("Takım B ödeme sitesi");
        goneForeign = inv(3L, "gone.example.com", FOREIGN_TEAM);
        goneForeign.setDeletedAt("2026-09-01T00:00:00");
        goneForeign.setActive(false);
        goneOwn = inv(4L, "gone-own.example.com", OWN_TEAM);
        goneOwn.setDeletedAt("2026-09-01T00:00:00");
        goneOwn.setActive(false);

        switchOn(true);
        when(permissionService.allows(any(HttpSession.class), eq("inventory.list"), eq("view"))).thenReturn(true);
        // AdminControllerTest ile aynı rol stub'ları: isTeamAdmin() izin matrisinden çözülür.
        when(permissionService.allows(any(HttpSession.class), eq("system.team_admin"), eq("execute")))
                .thenAnswer(i -> {
                    Object r = ((HttpSession) i.getArgument(0)).getAttribute("systemRole");
                    return "ADMIN".equals(r) || "TEAM_ADMIN".equals(r);
                });
        when(permissionService.allows(any(HttpSession.class), eq("system.global_admin"), eq("execute")))
                .thenAnswer(i -> "ADMIN".equals(((HttpSession) i.getArgument(0)).getAttribute("systemRole")));

        Team a = new Team(); a.setId(OWN_TEAM); a.setName("Takım A");
        Team b = new Team(); b.setId(FOREIGN_TEAM); b.setName("Takım B");
        when(userService.listTeams()).thenReturn(List.of(a, b));

        when(inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc()).thenReturn(List.of(foreign, own));
        when(inventoryRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(foreign, goneForeign, goneOwn, own));
        when(inventoryRepo.findByTeamIdInAndDeletedAtIsNullOrderByDomainAsc(anyList())).thenAnswer(i -> {
            List<?> teams = i.getArgument(0);
            return teams.contains(OWN_TEAM) ? List.of(own) : List.of();
        });
        for (CertificateInventory r : List.of(own, foreign, goneForeign, goneOwn)) {
            when(inventoryRepo.findById(r.getId())).thenReturn(Optional.of(r));
            when(inventoryRepo.findByDomain(r.getDomain())).thenReturn(Optional.of(r));
        }
        // Mükerrer kapısı (2026-09-28) çakışan KAYDI okur (sahibi takımı 409'da adıyla söylemek için).
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc(anyString())).thenAnswer(i -> {
            String d = i.getArgument(0);
            return java.util.stream.Stream.of(own, foreign, goneForeign, goneOwn)
                    .filter(x -> x.getDomain().equalsIgnoreCase(d)).findFirst();
        });
        when(inventoryRepo.save(any(CertificateInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(latestCheckRepo.findAll()).thenReturn(List.of());
        when(monitoringGroupService.getOrCreate(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(2));
        when(noteRepo.save(any(CertificateNote.class))).thenAnswer(i -> {
            CertificateNote n = i.getArgument(0);
            if (n.getId() == null) n.setId(99L);
            return n;
        });
    }

    // ══ OKUMA: liste ═══════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopedRoles")
    @DisplayName("scope=all + ayar açık → başka takımın silinmemiş kaydı da gelir; can_manage satır başına; yabancı satırda created_ip yok")
    void listAll_includesForeignRows_withPerRowCanManage(String role, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(get("/api/admin/inventory").param("scope", "all").session(session.get()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all"))
                .andExpect(jsonPath("$.visible_to_all").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].domain").value("foreign.example.com"))
                .andExpect(jsonPath("$.data[0].can_manage").value(false))
                .andExpect(jsonPath("$.data[0].team_name").value("Takım B"))
                .andExpect(jsonPath("$.data[0].svc_mgmt_contact").value("Takım B - destek@example.com"))
                .andExpect(jsonPath("$.data[0].created_ip").doesNotExist())
                .andExpect(jsonPath("$.data[1].domain").value("own.example.com"))
                .andExpect(jsonPath("$.data[1].can_manage").value(true))
                .andExpect(jsonPath("$.data[1].created_ip").value("10.0.0.1"));
        // Silinmiş kayıt org geneli okunmaz: sorgu silinmemişleri çeker, showDeleted yolu HİÇ kullanılmaz.
        verify(inventoryRepo, never()).findAllByOrderByDomainAsc();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopedRoles")
    @DisplayName("scope=mine (varsayılan) BUGÜNKÜ davranış: yalnız görüş kapsamındaki takımın kaydı")
    void listMine_isUnchanged(String role, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(get("/api/admin/inventory").session(session.get()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mine"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].domain").value("own.example.com"))
                .andExpect(jsonPath("$.data[0].can_manage").value(true));
        verify(inventoryRepo, never()).findByDeletedAtIsNullOrderByDomainAsc();
    }

    @Test
    @DisplayName("ayar KAPALI → scope=all, mine gibi davranır (scope=mine, visible_to_all=false)")
    void listAll_switchOff_behavesLikeMine() throws Exception {
        switchOn(false);
        mvc.perform(get("/api/admin/inventory").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mine"))
                .andExpect(jsonPath("$.visible_to_all").value(false))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].domain").value("own.example.com"));
        verify(inventoryRepo, never()).findByDeletedAtIsNullOrderByDomainAsc();
    }

    @Test
    @DisplayName("inventory.list/view izni yoksa scope=all genişlemez (matris envanter okumasını kapatmışsa)")
    void listAll_withoutListPermission_fallsBackToMine() throws Exception {
        when(permissionService.allows(any(HttpSession.class), eq("inventory.list"), eq("view"))).thenReturn(false);
        mvc.perform(get("/api/admin/inventory").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mine"))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("global ADMIN her satırı yönetir (can_manage=true); AUDIT hepsini görür ama hiçbirini yönetmez")
    void canManage_globalAdminAndAudit() throws Exception {
        mvc.perform(get("/api/admin/inventory").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].can_manage").value(true))
                .andExpect(jsonPath("$.data[1].can_manage").value(true))
                .andExpect(jsonPath("$.data[0].created_ip").value("10.0.0.2"));
        mvc.perform(get("/api/admin/inventory").session(audit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].can_manage").value(false))
                .andExpect(jsonPath("$.data[1].can_manage").value(false));
    }

    // ══ OKUMA: by-domain ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopedRoles")
    @DisplayName("by-domain: başka takımın kaydı TAM ve salt okunur döner (can_manage=false, takım adı, sorumlu, platform)")
    void byDomain_foreignRecord_fullReadOnly(String role, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "foreign.example.com").session(session.get()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("foreign.example.com"))
                .andExpect(jsonPath("$.data.team_id").value(9))
                .andExpect(jsonPath("$.data.team_name").value("Takım B"))
                .andExpect(jsonPath("$.data.svc_mgmt_contact").value("Takım B - destek@example.com"))
                .andExpect(jsonPath("$.data.platform").value("IIS"))
                .andExpect(jsonPath("$.data.description").value("Takım B ödeme sitesi"))
                .andExpect(jsonPath("$.data.can_manage").value(false))
                .andExpect(jsonPath("$.data.created_ip").doesNotExist());
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "own.example.com").session(session.get()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_manage").value(true))
                .andExpect(jsonPath("$.data.created_ip").value("10.0.0.1"));
    }

    @Test
    @DisplayName("by-domain: ayar kapalıyken başka takımın kaydı null (bugünkü davranış); silinmiş yabancı kayıt her zaman null")
    void byDomain_switchOffOrDeleted_returnsNull() throws Exception {
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "gone.example.com").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").doesNotExist());
        switchOn(false);
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "foreign.example.com").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").doesNotExist());
    }

    // ══ OKUMA: bildirim grubunun ADI (2026-09-28) ═══════════════════════════════════════════════
    // Envanter detayı eskiden yalnız notification_group_id alıyordu. Ad YALNIZ grup kayıt için gerçekten uygulanıyorsa
    // (aktif + kaydın takımına ait) ve çağıran onu okuyabiliyorsa (notification.groups/view + takım görüş kapsamı) yazılır.

    private static NotificationGroup group(long id, long teamId, String name, boolean active) {
        NotificationGroup g = new NotificationGroup();
        g.setId(id); g.setTeamId(teamId); g.setName(name); g.setActive(active);
        return g;
    }

    private void groupsReadable(boolean allowed, NotificationGroup... groups) {
        when(permissionService.allows(any(HttpSession.class), eq("notification.groups"), eq("view"))).thenReturn(allowed);
        when(notificationGroupRepo.findAllById(any())).thenReturn(List.of(groups));
    }

    @Test
    @DisplayName("by-domain: kendi kaydının grubu ADIYLA döner; org geneli okunan başka takımın grubu adsız (kimlik kalır) — global görücü ikisini de görür")
    void byDomain_notificationGroupName_onlyForReadableGroups() throws Exception {
        own.setNotificationGroupId(21L);
        foreign.setNotificationGroupId(22L);
        groupsReadable(true, group(21L, OWN_TEAM, "Nöbet A", true), group(22L, FOREIGN_TEAM, "Nöbet B", true));

        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "own.example.com").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notification_group_id").value(21))
                .andExpect(jsonPath("$.data.notification_group_name").value("Nöbet A"));
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "foreign.example.com").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notification_group_id").value(22))
                .andExpect(jsonPath("$.data.notification_group_name").doesNotExist());
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "foreign.example.com").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notification_group_name").value("Nöbet B"));
    }

    @Test
    @DisplayName("by-domain: pasif grup, kaydın takımına AİT OLMAYAN grup ve izin yokluğu → ad yazılmaz (izin yoksa grup sorgusu da yok)")
    void byDomain_notificationGroupName_hiddenWhenNotApplied() throws Exception {
        own.setNotificationGroupId(21L);
        groupsReadable(true, group(21L, OWN_TEAM, "Nöbet A", false));
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "own.example.com").session(globalAdmin()))
                .andExpect(jsonPath("$.data.notification_group_name").doesNotExist());

        groupsReadable(true, group(21L, FOREIGN_TEAM, "Nöbet B", true));   // takım aktarımından kalan yabancı grup
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "own.example.com").session(globalAdmin()))
                .andExpect(jsonPath("$.data.notification_group_name").doesNotExist());

        org.mockito.Mockito.clearInvocations(notificationGroupRepo);
        groupsReadable(false, group(21L, OWN_TEAM, "Nöbet A", true));
        mvc.perform(get("/api/admin/inventory/by-domain").param("domain", "own.example.com").session(user()))
                .andExpect(jsonPath("$.data.notification_group_id").value(21))
                .andExpect(jsonPath("$.data.notification_group_name").doesNotExist());
        verify(notificationGroupRepo, never()).findAllById(any());
    }

    @Test
    @DisplayName("liste: grup adları TEK sorguda çözülür (satır başına sorgu yok); grup seçilmemiş listede sorgu hiç yok")
    void list_notificationGroupNames_singleQuery() throws Exception {
        own.setNotificationGroupId(21L);
        foreign.setNotificationGroupId(22L);
        groupsReadable(true, group(21L, OWN_TEAM, "Nöbet A", true), group(22L, FOREIGN_TEAM, "Nöbet B", true));

        mvc.perform(get("/api/admin/inventory").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("foreign.example.com"))
                .andExpect(jsonPath("$.data[0].notification_group_name").doesNotExist())
                .andExpect(jsonPath("$.data[1].domain").value("own.example.com"))
                .andExpect(jsonPath("$.data[1].notification_group_name").value("Nöbet A"));
        verify(notificationGroupRepo, times(1)).findAllById(any());
        verify(notificationGroupRepo, never()).findById(any());

        own.setNotificationGroupId(null);
        foreign.setNotificationGroupId(null);
        org.mockito.Mockito.clearInvocations(notificationGroupRepo);
        mvc.perform(get("/api/admin/inventory").param("scope", "all").session(user()))
                .andExpect(status().isOk());
        verify(notificationGroupRepo, never()).findAllById(any());
    }

    // ══ YAZMA: her yol başka takımın kaydını reddeder (ayar AÇIK iken) ════════════════════════════

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopedRoles")
    @DisplayName("PUT: başka takımın kaydı 403 (kayıt yazılmaz) — kendi takımının kaydı 200")
    void update_foreignRejected_ownAllowed(String role, Supplier<MockHttpSession> session) throws Exception {
        String body = "{\"group_name\":\"Grup A\",\"tags\":\"prod\",\"domain\":\"%s\",\"port\":443,\"active\":true,\"team_id\":%d}";
        mvc.perform(put("/api/admin/inventory/2").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(body, "foreign.example.com", OWN_TEAM)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        verify(inventoryRepo, never()).save(any());
        assertThat(foreign.getTeamId()).isEqualTo(FOREIGN_TEAM);

        mvc.perform(put("/api/admin/inventory/1").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(body, "own.example.com", OWN_TEAM)))
                .andExpect(status().isOk());
        verify(inventoryRepo, times(1)).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoles")
    @DisplayName("DELETE / restore / kalıcı sil: başka takımın kaydı 403 — kendi takımında 200")
    void deleteRestorePurge_foreignRejected(String role, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(delete("/api/admin/inventory/2").session(session.get())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/3/restore").session(session.get())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/inventory/3/permanent").session(session.get())).andExpect(status().isForbidden());
        verify(inventoryRepo, never()).save(any());
        verify(inventoryRepo, never()).delete(any());
        assertThat(foreign.getDeletedAt()).isNull();
        assertThat(goneForeign.getDeletedAt()).isNotNull();

        mvc.perform(delete("/api/admin/inventory/1").session(session.get())).andExpect(status().isOk());
        mvc.perform(post("/api/admin/inventory/4/restore").session(session.get())).andExpect(status().isOk());
        assertThat(own.getDeletedAt()).isNotNull();
        assertThat(goneOwn.getDeletedAt()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoles")
    @DisplayName("SY/UG aktarımı: takım kapsamlı roller için 403 (yalnız global admin aktarır)")
    void transfer_rejectedForScopedRoles(String role, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(post("/api/admin/inventory/2/transfer").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":5}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/2/transfer-ug").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ug_team_id\":5}"))
                .andExpect(status().isForbidden());
        verify(inventoryRepo, never()).save(any());
        assertThat(foreign.getTeamId()).isEqualTo(FOREIGN_TEAM);
        assertThat(foreign.getUgTeamId()).isNull();
    }

    /**
     * Toplu işlem SÖZLEŞMESİ (değişmedi, belgelendi): yabancı kimlik partiyi DÜŞÜRMEZ, o satır ATLANIR
     * ({@code skipped}) — her kimlik tek tek denetlenir, yalnız ilki değil. Alan adıyla verilen yabancı kayıt
     * da aynı kapıdan geçer.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoles")
    @DisplayName("toplu işlem: yabancı kimlik ATLANIR (skipped), kendi kaydı işlenir — her kimlik ayrı denetlenir")
    void bulk_foreignIdSkipped_ownProcessed(String role, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(post("/api/admin/inventory/bulk").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deactivate\",\"ids\":[2,1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1));
        assertThat(foreign.getActive()).isTrue();
        assertThat(own.getActive()).isFalse();
        verify(inventoryRepo, times(1)).save(any());

        mvc.perform(post("/api/admin/inventory/bulk").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-tier\",\"tier\":1,\"domains\":[\"foreign.example.com\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(0))
                .andExpect(jsonPath("$.data.skipped").value(1));
        assertThat(foreign.getTier()).isNull();

        mvc.perform(post("/api/admin/inventory/bulk").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set-team\",\"team_id\":5,\"ids\":[2]}"))
                .andExpect(status().isForbidden());
        assertThat(foreign.getTeamId()).isEqualTo(FOREIGN_TEAM);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopedRoles")
    @DisplayName("ekle: başka takımda (ya da çöp kutusunda) VAR olan alan adını kendi takımına 'yeniden eklemek' 409 — devralma yok")
    void add_existingForeignDomain_conflictNoTakeover(String role, Supplier<MockHttpSession> session) throws Exception {
        String body = "{\"group_name\":\"Grup A\",\"tags\":\"prod\",\"domain\":\"%s\",\"port\":443,\"team_id\":5}";
        mvc.perform(post("/api/admin/inventory").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(body, "foreign.example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/api/admin/inventory").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(body, "Foreign.Example.COM")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/inventory").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(body, "gone.example.com")))
                .andExpect(status().isConflict());
        verify(inventoryRepo, never()).save(any());
        assertThat(foreign.getTeamId()).isEqualTo(FOREIGN_TEAM);

        mvc.perform(post("/api/admin/inventory").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(body, "new.example.com")))
                .andExpect(status().isOk());
        verify(inventoryRepo, times(1)).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoles")
    @DisplayName("kontrol kaydını sil (DELETE /certificates/{domain}): başka takımın alan adı 403")
    void deleteCheck_foreignRejected(String role, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(delete("/api/admin/certificates/foreign.example.com").session(session.get()))
                .andExpect(status().isForbidden());
        verify(latestCheckRepo, never()).deleteById(anyString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoles")
    @DisplayName("tanılama (dış prob): başka takımın alan adı ayar açıkken de 403 — görünürlük tanılamayı açmaz")
    void diagnostics_foreignRejected(String role, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(post("/api/admin/diagnostics").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"foreign.example.com\",\"port\":443}"))
                .andExpect(status().isForbidden());
        verify(diagnosticsService, never()).diagnose(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ══ Notlar ═══════════════════════════════════════════════════════════════════════════════════

    private static CertificateNote note(long id, String domain, Long teamId, String author, String deletedAt) {
        CertificateNote n = new CertificateNote();
        n.setId(id); n.setDomain(domain); n.setTeamId(teamId); n.setAuthorUsername(author); n.setAuthorName(author);
        n.setNote("not " + id); n.setCategory("NOTE"); n.setCreatedAt(ISO.format(Instant.now())); n.setDeletedAt(deletedAt);
        return n;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoles")
    @DisplayName("not ekle/düzenle/sil: başka takımın alan adında 403 (yazan kendisi olsa da) — kendi alan adında 200")
    void notes_foreignDomainRejected_ownAllowed(String role, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(post("/api/admin/notes/foreign.example.com").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"müdahale\",\"category\":\"NOTE\"}"))
                .andExpect(status().isForbidden());
        // Eski bir not: çağıranın takımı yazmış (bugünkü kurallarla mümkündü) — alan adı başka takımın.
        CertificateNote legacy = note(50L, "foreign.example.com", OWN_TEAM, "u1", null);
        when(noteRepo.findById(50L)).thenReturn(Optional.of(legacy));
        mvc.perform(put("/api/admin/notes/foreign.example.com/50").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"değişti\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/notes/foreign.example.com/50").session(session.get()))
                .andExpect(status().isForbidden());
        verify(noteRepo, never()).save(any());
        assertThat(legacy.getNote()).isEqualTo("not 50");
        assertThat(legacy.getDeletedAt()).isNull();

        mvc.perform(post("/api/admin/notes/own.example.com").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"kendi notum\",\"category\":\"NOTE\"}"))
                .andExpect(status().isOk());
        CertificateNote mine = note(51L, "own.example.com", OWN_TEAM, "u1", null);
        when(noteRepo.findById(51L)).thenReturn(Optional.of(mine));
        mvc.perform(put("/api/admin/notes/own.example.com/51").session(session.get()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"güncel\"}"))
                .andExpect(status().isOk());
        assertThat(mine.getNote()).isEqualTo("güncel");
    }

    @Test
    @DisplayName("notları oku: ayar açık → alan adının silinmemiş TÜM notları; kapalı → bugünkü takım kapsamı")
    void notes_readOrgWide() throws Exception {
        when(noteRepo.findByDomainOrderByCreatedAtDesc("foreign.example.com")).thenReturn(List.of(
                note(60L, "foreign.example.com", FOREIGN_TEAM, "b1", null),
                note(61L, "foreign.example.com", FOREIGN_TEAM, "b2", "2026-09-02T00:00:00"),
                note(62L, "foreign.example.com", null, "root", null)));
        mvc.perform(get("/api/admin/notes/foreign.example.com").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(60))
                .andExpect(jsonPath("$.data[1].id").value(62));

        CertificateNote n60 = note(60L, "foreign.example.com", FOREIGN_TEAM, "b1", null);
        when(noteRepo.findById(60L)).thenReturn(Optional.of(n60));
        mvc.perform(get("/api/admin/notes/foreign.example.com/60/revisions").session(user()))
                .andExpect(status().isOk());

        switchOn(false);
        mvc.perform(get("/api/admin/notes/foreign.example.com").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        verify(noteRepo).findByDomainAndTeamIdInOrderByCreatedAtDesc(eq("foreign.example.com"), anyList());
        mvc.perform(get("/api/admin/notes/foreign.example.com/60/revisions").session(user()))
                .andExpect(status().isForbidden());
    }

    // ══ Mükerrer alan adı 409 (2026-09-28): sahibi takım ADIYLA + yapısal `existing` ═════════════════════════

    /** `existing` beyaz listesi — bunun DIŞINDA anahtar yanıtta olamaz (sorumlu kişi, açıklama, IP, platform…). */
    private static final java.util.Set<String> EXISTING_KEYS = java.util.Set.of(
            "domain", "inventory_id", "team_id", "team_name", "ug_team_id", "ug_team_name", "deleted", "deleted_at",
            "same_team", "can_view", "can_restore", "can_transfer");

    private static final String ADD_BODY = "{\"group_name\":\"Grup A\",\"tags\":\"prod\",\"domain\":\"%s\",\"port\":443,\"team_id\":5}";

    private org.springframework.test.web.servlet.ResultActions add(MockHttpSession s, String domain, String lang) throws Exception {
        return mvc.perform(post("/api/admin/inventory").session(s).header("X-Lang", lang)
                .contentType(MediaType.APPLICATION_JSON).content(String.format(ADD_BODY, domain)));
    }

    @SuppressWarnings("unchecked")
    private static void assertWhitelisted(String body) {
        java.util.Map<String, Object> json = com.jayway.jsonpath.JsonPath.parse(body).read("$", java.util.Map.class);
        assertThat(json.keySet()).containsExactlyInAnyOrder("success", "error", "code", "existing");
        java.util.Map<String, Object> ex = (java.util.Map<String, Object>) json.get("existing");
        assertThat(ex.keySet()).isSubsetOf(EXISTING_KEYS)
                .contains("domain", "inventory_id", "team_id", "team_name", "deleted", "same_team",
                        "can_view", "can_restore", "can_transfer");
        // Kaydın iç/kişisel alanları (sorumlu e-postası, açıklama, IP, platform) 409 yanıtına SIZMAZ.
        assertThat(body).doesNotContain("destek@example.com", "ödeme sitesi", "10.0.0.2", "IIS", "svc_mgmt", "created_ip");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopedRoles")
    @DisplayName("mükerrer (ekle): başka takımın kaydı → 409 + code=DOMAIN_EXISTS + existing.team_name; ileti ekibi ADIYLA söyler; beyaz liste")
    void duplicateAdd_foreign_namesOwnerTeam(String role, Supplier<MockHttpSession> session) throws Exception {
        String body = add(session.get(), "Foreign.Example.COM", "tr")   // harf duyarsız
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("DOMAIN_EXISTS"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("'Takım B' ekibinin envanterinde kayıtlı")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("aktarılması (transfer) gerekir")))
                .andExpect(jsonPath("$.existing.domain").value("foreign.example.com"))
                .andExpect(jsonPath("$.existing.inventory_id").value(2))
                .andExpect(jsonPath("$.existing.team_id").value(9))
                .andExpect(jsonPath("$.existing.team_name").value("Takım B"))
                .andExpect(jsonPath("$.existing.deleted").value(false))
                .andExpect(jsonPath("$.existing.same_team").value(false))
                .andExpect(jsonPath("$.existing.can_view").value(true))        // org geneli okuma açık
                .andExpect(jsonPath("$.existing.can_restore").value(false))
                .andExpect(jsonPath("$.existing.can_transfer").value(false))   // takım kapsamlı rol aktaramaz
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertWhitelisted(body);
        verify(inventoryRepo, never()).save(any());
        assertThat(foreign.getTeamId()).isEqualTo(FOREIGN_TEAM);
    }

    @Test
    @DisplayName("mükerrer (ekle): İngilizce ileti doğal ve takımı adıyla söyler; ayar kapalıyken can_view=false ama takım ADI yine söylenir")
    void duplicateAdd_englishMessage_andSwitchOff() throws Exception {
        add(user(), "foreign.example.com", "en")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "This domain is already registered in the 'Takım B' team's inventory. A duplicate record can't be "
                        + "created; if the domain should belong to your team, the record needs to be transferred to it."));
        switchOn(false);
        add(user(), "foreign.example.com", "tr")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existing.team_name").value("Takım B"))
                .andExpect(jsonPath("$.existing.can_view").value(false));
    }

    @Test
    @DisplayName("mükerrer (ekle): ÇÖP KUTUSUNDAKİ başka takımın kaydı → deleted=true + deleted_at, geri yükleme/aktarım ileti; USER geri yükleyemez")
    void duplicateAdd_foreignInBin() throws Exception {
        String body = add(user(), "gone.example.com", "tr")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_EXISTS"))
                .andExpect(jsonPath("$.existing.deleted").value(true))
                .andExpect(jsonPath("$.existing.deleted_at").value("2026-09-01T00:00:00"))
                .andExpect(jsonPath("$.existing.team_name").value("Takım B"))
                .andExpect(jsonPath("$.existing.can_view").value(false))       // çöp kutusu org geneli okunmaz
                .andExpect(jsonPath("$.existing.can_restore").value(false))
                .andExpect(jsonPath("$.error").value(
                        "Bu alan adı çöp kutusunda ('Takım B' ekibinin kaydı). Mükerrer kayıt oluşturulamaz; kaydın geri "
                        + "yüklenmesi ya da ekibinize aktarılması gerekir."))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertWhitelisted(body);
    }

    @Test
    @DisplayName("mükerrer (ekle): AYNI takım — kayıt varsa 'mevcut kaydı düzenleyin', çöp kutusundaysa 'geri yükleyin' + can_restore (yönetici)")
    void duplicateAdd_sameTeam() throws Exception {
        when(permissionService.allows(any(HttpSession.class), eq("inventory.crud"), eq("edit"))).thenReturn(true);
        add(user(), "own.example.com", "tr")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existing.same_team").value(true))
                .andExpect(jsonPath("$.existing.team_name").value("Takım A"))
                .andExpect(jsonPath("$.existing.can_view").value(true))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                        "seçtiğiniz 'Takım A' ekibinin envanterinde zaten kayıtlı. Mükerrer kayıt oluşturulamaz; mevcut kaydı açıp düzenleyin")));
        add(teamAdmin(), "gone-own.example.com", "tr")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existing.same_team").value(true))
                .andExpect(jsonPath("$.existing.deleted").value(true))
                .andExpect(jsonPath("$.existing.can_restore").value(true))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("seçtiğiniz 'Takım A' ekibinin çöp kutusunda")));
        add(user(), "gone-own.example.com", "tr")   // USER restore kapısından (takım yönetimi) geçmez
                .andExpect(jsonPath("$.existing.can_restore").value(false));
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("mükerrer: can_transfer YALNIZ global admin + inventory.transfer — rolü ADMIN olan kapsamlı müdür değil (tuzak)")
    void duplicateAdd_canTransfer_onlyGlobalAdminWithPermission() throws Exception {
        add(globalAdmin(), "foreign.example.com", "tr").andExpect(jsonPath("$.existing.can_transfer").value(false));
        when(permissionService.allows(any(HttpSession.class), eq("inventory.transfer"), eq("execute"))).thenReturn(true);
        add(globalAdmin(), "foreign.example.com", "tr")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existing.can_transfer").value(true))
                .andExpect(jsonPath("$.existing.can_view").value(true));
        add(scopedAdmin(), "foreign.example.com", "tr").andExpect(jsonPath("$.existing.can_transfer").value(false));
        add(teamAdmin(), "foreign.example.com", "tr").andExpect(jsonPath("$.existing.can_transfer").value(false));
    }

    @Test
    @DisplayName("mükerrer (yeniden adlandır, PUT): aynı 409 + existing; alan adı taşıma (renameDomain) HİÇ çalışmaz")
    void duplicateRename_sameStructuredConflict() throws Exception {
        String body = mvc.perform(put("/api/admin/inventory/1").session(teamAdmin()).header("X-Lang", "tr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_name\":\"Grup A\",\"tags\":\"prod\",\"domain\":\"FOREIGN.example.com\",\"port\":443,\"active\":true,\"team_id\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_EXISTS"))
                .andExpect(jsonPath("$.existing.team_name").value("Takım B"))
                .andExpect(jsonPath("$.existing.inventory_id").value(2))
                .andExpect(jsonPath("$.existing.same_team").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("'Takım B' ekibinin envanterinde kayıtlı")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertWhitelisted(body);
        verify(latestCheckRepo, never()).renameDomain(anyString(), anyString());
        verify(inventoryRepo, never()).save(any());
        assertThat(own.getDomain()).isEqualTo("own.example.com");
    }

    @Test
    @DisplayName("aktarım ucu kapısı DEĞİŞMEDİ: USER 403; global admin (+izin) aktarır — mükerrer akışının 'Ekibime aktar' düğmesi bu uca gider")
    void transferEndpoint_permissionsUnchanged() throws Exception {
        com.sitemonitor.model.Team own = new com.sitemonitor.model.Team(); own.setId(OWN_TEAM);
        when(teamRepo.findById(OWN_TEAM)).thenReturn(Optional.of(own));   // takım var (2026-09-28)
        mvc.perform(post("/api/admin/inventory/2/transfer").session(user()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":5}"))
                .andExpect(status().isForbidden());
        assertThat(foreign.getTeamId()).isEqualTo(FOREIGN_TEAM);
        mvc.perform(post("/api/admin/inventory/2/transfer").session(globalAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_id\":5}"))
                .andExpect(status().isOk());
        assertThat(foreign.getTeamId()).isEqualTo(OWN_TEAM);
        verify(derivedMonitorTeamSync).syncTeam("foreign.example.com", OWN_TEAM);
    }
}
