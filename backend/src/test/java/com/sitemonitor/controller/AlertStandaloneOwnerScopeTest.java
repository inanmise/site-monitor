package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.noc.NocCallLogService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Bağımsız izleme alarmında EYLEM yalnız sahibi takımın (2026-10-09, kullanıcı onayı — {@code AlertOwnership}).
 *
 * <p>Senaryo: A takımı {@code api.example.com}'un sertifikasını envanterde tutuyor (SY = A, UG = C). B takımının aynı
 * host'ta bağımsız bir Ping izlemesi var. Eskiden alarm kapısı alan adı → envanter takımlarını HER alarm türüne
 * ekliyordu: A, B'nin Ping alarmını çözebiliyor (B'ye yanlış "çözüldü" postası) ve B'nin alıcılarını önizleyebiliyordu.
 * Kural yönlendirmeyle AYNI yüklem ({@code EscalationService.isStandaloneEvent}): bağımsız alarm → yalnız damgalı takım;
 * envanter gibi yönlenen alarm (sertifika, ACCESSIBILITY, envanter türevi Port/DNS) → eskisi gibi damga + SY + UG.
 * OKUMA kapsamı (liste, tekil alarm, teslimat günlüğü) bilerek DEĞİŞMEDİ — liste sorgusuyla aynı kalır.
 * Bean listesi {@code AlertListNocCallTest} ile aynı dilim.
 */
@WebMvcTest(AdminController.class)
class AlertStandaloneOwnerScopeTest {

    private static final long TEAM_A = 1L, TEAM_B = 2L, TEAM_C = 3L, TEAM_D = 4L;
    private static final String HOST = "api.example.com";
    private static final String NOTE = "{\"note\":\"bakıyoruz, kök neden araştırılıyor\"}";

    @Autowired MockMvc mvc;

    @MockitoBean NocCallLogService nocCallLog;
    @MockitoBean com.sitemonitor.service.noc.NocAlertFacts nocAlertFacts;

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
    @MockitoBean com.sitemonitor.repository.LatestCheckRepository latestCheckRepo;
    @MockitoBean com.sitemonitor.repository.CertificateCheckRepository certificateCheckRepo;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.service.MonitorHistoryService monitorHistory;
    @MockitoBean com.sitemonitor.service.SsrfGuard ssrfGuard;
    @MockitoBean CertificateNoteRepository noteRepo;
    @MockitoBean com.sitemonitor.repository.CertificateNoteRevisionRepository noteRevisionRepo;
    @MockitoBean AppUserRepository userRepo;
    @MockitoBean com.sitemonitor.repository.TeamRepository teamRepo;
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

    private MockHttpSession memberA, memberB, memberC, globalAdmin, nocD, nocB;
    /** B'nin bağımsız Ping alarmı — A'nın envanter host'unda (tür listesinden bağımsız). */
    private AlertEvent pingB;
    /** B'nin kullanıcı eklediği bağımsız Port alarmı — bağlamda team_id + standalone işareti (damgadan bağımsız). */
    private AlertEvent portStandaloneB;
    /** Sertifika alarmı (damgasız) — envanter gibi yönlenir: SY = A, UG = C. */
    private AlertEvent certExpiry;
    /** ACCESSIBILITY (damgasız) — envanter gibi yönlenir. */
    private AlertEvent accessibility;
    /** Envanter TÜREVİ Port alarmı — bağlam bilerek damgasız (SchedulerService.alarmTeamOf). */
    private AlertEvent derivedPort;
    /** A'nın kendi bağımsız Ping alarmı. */
    private AlertEvent pingA;

    private static MockHttpSession session(String user, String role, List<Long> view) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", user);
        s.setAttribute("systemRole", role);
        if (view != null) s.setAttribute("viewTeamIds", new ArrayList<>(view));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        return s;
    }

    private static boolean isNoc(HttpSession s) {
        Object u = s == null ? null : s.getAttribute("username");
        return "nocd".equals(u) || "nocb".equals(u);
    }

    private AlertEvent alert(long id, String type, Long teamId, String contextJson) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain(HOST); e.setAlertType(type); e.setTeamId(teamId); e.setContextJson(contextJson);
        e.setAlertLevel("CRITICAL"); e.setResolved(false); e.setAcknowledged(false); e.setCreatedAt("2026-10-09T03:00:00");
        when(alertEventRepo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    @BeforeEach
    void setUp() {
        memberA = session("kisia", "USER", List.of(TEAM_A));
        memberB = session("kisib", "USER", List.of(TEAM_B));
        memberC = session("kisic", "USER", List.of(TEAM_C));   // envanterin UG takımı
        globalAdmin = session("admin", "ADMIN", null);
        nocD = session("nocd", "USER", List.of(TEAM_D));      // 7/24 operatörü, ilgisiz takım
        nocB = session("nocb", "USER", List.of(TEAM_B));      // 7/24 operatörü, sahibi takımın üyesi

        pingB = alert(60L, "PING_DOWN", TEAM_B, "{\"team_id\":2,\"monitor_id\":11}");
        portStandaloneB = alert(61L, "PORT_DOWN", TEAM_B, "{\"team_id\":2,\"standalone\":true,\"monitor_id\":12}");
        certExpiry = alert(62L, "EXPIRY", null, null);
        accessibility = alert(63L, "ACCESSIBILITY", null, "{\"monitor_id\":13}");
        derivedPort = alert(64L, "PORT_DOWN", null, "{\"monitor_id\":14}");
        pingA = alert(65L, "PING_DOWN", TEAM_A, "{\"team_id\":1,\"monitor_id\":15}");

        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(HOST); inv.setTeamId(TEAM_A); inv.setUgTeamId(TEAM_C);
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
        when(inventoryRepo.findByDomain(HOST)).thenReturn(Optional.of(inv));
        when(inventoryRepo.findByDomainIn(any())).thenReturn(List.of(inv));
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                any(), any(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pingB, portStandaloneB, certExpiry, accessibility, derivedPort, pingA)));
        when(nocCallLog.seesAllAlerts(any())).thenAnswer(i -> isNoc(i.getArgument(0)));
        when(nocCallLog.canWrite(any())).thenAnswer(i -> isNoc(i.getArgument(0)));
        when(notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(anyLong())).thenReturn(List.of());
        when(escalationService.previewReNotify(anyLong())).thenReturn(List.of());
        when(escalationService.acknowledge(anyLong(), any(), any()))
                .thenAnswer(i -> alertEventRepo.findById(i.getArgument(0)).orElse(null));
        when(escalationService.resolve(anyLong(), any(), any()))
                .thenAnswer(i -> alertEventRepo.findById(i.getArgument(0)).orElse(null));
        when(userPushService.preview(anyLong(), any())).thenReturn(
                new com.sitemonitor.service.UserPushService.PushPreview(true, null, List.of()));
    }

    // ── (a) başka takımın BAĞIMSIZ alarmı, host benim envanterimde → her eylem 403 ─────────────────────────────

    @Test
    @DisplayName("(a) A üyesi, B'nin bağımsız Ping / Port alarmı (host A'nın envanterinde): sahiplen/çöz/yeniden bildir/önizleme 403")
    void foreignStandaloneOnMyInventoryHost_everyActionForbidden() throws Exception {
        for (long id : List.of(60L, 61L)) {
            mvc.perform(post("/api/admin/alerts/" + id + "/acknowledge").session(memberA)
                            .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error", containsString("bağımsız")));
            mvc.perform(post("/api/admin/alerts/" + id + "/resolve").session(memberA)
                            .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/alerts/" + id + "/re-notify").session(memberA)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/alerts/" + id + "/re-notify/preview").session(memberA))
                    .andExpect(status().isForbidden());   // B'nin alıcıları A'ya görünmez
        }
        // Envanterin UG takımı (C) de bağımsız alarmın sahibi değildir.
        mvc.perform(post("/api/admin/alerts/60/resolve").session(memberC)
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isForbidden());
        verifyNoInteractions(escalationService);
        verifyNoInteractions(userPushService);
    }

    @Test
    @DisplayName("(a) toplu işlem: B'nin bağımsız alarmları ATLANIR, A'nın envanter alarmı işlenir")
    void bulk_skipsForeignStandalone_processesInventoryAlarm() throws Exception {
        for (String action : List.of("acknowledge", "resolve", "re-notify")) {
            mvc.perform(post("/api/admin/alerts/bulk").session(memberA).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"" + action + "\",\"ids\":[60,61,62],\"note\":\"toplu işlem notu yazıldı\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.processed").value(1))
                    .andExpect(jsonPath("$.data.skipped").value(2));
        }
        verify(escalationService).acknowledge(eq(62L), any(), any());
        verify(escalationService).resolve(eq(62L), any(), any());
        verify(escalationService).reNotify(62L);
        verify(escalationService, never()).acknowledge(eq(60L), any(), any());
        verify(escalationService, never()).acknowledge(eq(61L), any(), any());
        verify(escalationService, never()).resolve(eq(60L), any(), any());
        verify(escalationService, never()).resolve(eq(61L), any(), any());
        verify(escalationService, never()).reNotify(60L);
        verify(escalationService, never()).reNotify(61L);
    }

    // ── (b) envanter gibi yönlenen alarm (sertifika / ACCESSIBILITY / türev Port) → eskisi gibi SY + UG ──────────

    @Test
    @DisplayName("(b) sertifika, ACCESSIBILITY ve envanter türevi Port alarmı: SY (A) ve UG (C) eskisi gibi işlem yapar")
    void inventoryRoutedAlarm_ownersUnchanged() throws Exception {
        for (long id : List.of(62L, 63L, 64L)) {
            mvc.perform(post("/api/admin/alerts/" + id + "/acknowledge").session(memberA)
                            .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/admin/alerts/" + id + "/re-notify/preview").session(memberA))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/admin/alerts/" + id + "/resolve").session(memberC)
                            .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                    .andExpect(status().isOk());
        }
        // İlgisiz takım hâlâ 403.
        mvc.perform(post("/api/admin/alerts/62/acknowledge").session(memberB)
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isForbidden());
    }

    // ── (c) kendi bağımsız alarmım → izinli ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) sahibi takım kendi bağımsız alarmında işlem yapar (B → #60/#61, A → #65)")
    void ownStandalone_allowed() throws Exception {
        mvc.perform(post("/api/admin/alerts/60/resolve").session(memberB)
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/alerts/61/re-notify/preview").session(memberB)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/alerts/65/acknowledge").session(memberA)
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk());
        verify(escalationService).resolve(eq(60L), any(), any());
        verify(escalationService).previewReNotify(61L);
        verify(escalationService).acknowledge(eq(65L), any(), any());
    }

    // ── (d) global yönetici değişmedi ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(d) global yönetici her alarmda işlem yapar; listede act_scope alanı YAZILMAZ (kısıt yok)")
    void globalAdmin_unchanged() throws Exception {
        mvc.perform(post("/api/admin/alerts/60/resolve").session(globalAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/alerts").session(globalAdmin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(60))
                .andExpect(jsonPath("$.data[0].act_scope").doesNotExist());
    }

    // ── (e) 7/24 operatörü: okuma geniş, yazma kendi takımı ─────────────────────────────────────────────────────

    @Test
    @DisplayName("(e) 7/24 operatörü: ilgisiz takımdan tüm alarmları OKUR, yazamaz; sahibi takımın üyesi operatör yazabilir")
    void nocOperator_rulesUnchanged() throws Exception {
        mvc.perform(get("/api/admin/alerts/60").session(nocD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.act_scope").value(false));
        mvc.perform(get("/api/admin/alerts/60/notifications").session(nocD)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/alerts/60/acknowledge").session(nocD)
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/alerts/60/acknowledge").session(nocB)
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk());
        verify(escalationService, times(1)).acknowledge(eq(60L), any(), any());
    }

    // ── OKUMA kapsamı bilerek değişmedi + satır bayrağı ────────────────────────────────────────────────────────

    @Test
    @DisplayName("okuma değişmedi: A üyesi B'nin bağımsız alarmını listede ve detayda görür (liste sorgusuyla aynı), ama act_scope=false")
    void readScopeUnchanged_actScopeFlagFollowsOwnerRule() throws Exception {
        mvc.perform(get("/api/admin/alerts").session(memberA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(60)).andExpect(jsonPath("$.data[0].act_scope").value(false))
                .andExpect(jsonPath("$.data[1].id").value(61)).andExpect(jsonPath("$.data[1].act_scope").value(false))
                .andExpect(jsonPath("$.data[2].id").value(62)).andExpect(jsonPath("$.data[2].act_scope").value(true))
                .andExpect(jsonPath("$.data[3].id").value(63)).andExpect(jsonPath("$.data[3].act_scope").value(true))
                .andExpect(jsonPath("$.data[4].id").value(64)).andExpect(jsonPath("$.data[4].act_scope").value(true))
                .andExpect(jsonPath("$.data[5].id").value(65)).andExpect(jsonPath("$.data[5].act_scope").value(true));
        mvc.perform(get("/api/admin/alerts").session(memberC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].act_scope").value(false))   // UG takımı: bağımsız alarm onun değil
                .andExpect(jsonPath("$.data[2].act_scope").value(true))    // sertifika: UG eskisi gibi sahip
                .andExpect(jsonPath("$.data[5].act_scope").value(false));
        mvc.perform(get("/api/admin/alerts").session(memberB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].act_scope").value(true))
                .andExpect(jsonPath("$.data[2].act_scope").value(false));

        mvc.perform(get("/api/admin/alerts/60").session(memberA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.act_scope").value(false));
        mvc.perform(get("/api/admin/alerts/60/notifications").session(memberA)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/alerts/60/push-deliveries").session(memberA)).andExpect(status().isOk());
        // Hiçbir ilişkisi olmayan takım okuyamaz da (değişmedi).
        mvc.perform(get("/api/admin/alerts/60").session(session("kisid", "USER", List.of(TEAM_D))))
                .andExpect(status().isForbidden());
    }
}
