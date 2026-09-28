package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Uyarı listesi / tekil uyarı ↔ 7/24 arama kaydı (2026-09-27): 7/24 operatörü (başka takımdan) TÜM uyarıları görür
 * (liste kapsamsız sorgulanır, tekil uyarı ve teslimat günlüğü açılır) ama YAZMA eylemlerinin kapsamı genişlemez;
 * sayfa satırları {@code noc_call_count}/{@code noc_last_call} taşır (tek decorate çağrısı), yanıt {@code noc_can_write}.
 * Bean listesi {@code AdminControllerTest} ile aynı dilim + {@link NocCallLogService} mock'u.
 */
@WebMvcTest(AdminController.class)
class AlertListNocCallTest {

    private static final long TEAM_A = 1L, TEAM_B = 2L;

    @Autowired MockMvc mvc;

    @MockitoBean NocCallLogService nocCallLog;

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

    private MockHttpSession nocB, memberA, memberB;
    private AlertEvent alertA;

    private static MockHttpSession session(String user, String role, List<Long> view) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", user);
        s.setAttribute("systemRole", role);
        s.setAttribute("viewTeamIds", new ArrayList<>(view));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        return s;
    }

    private static boolean isNoc(HttpSession s) {
        return "noc2".equals(s.getAttribute("username"));
    }

    @BeforeEach
    void setUp() {
        nocB = session("noc2", "TEAM_ADMIN", List.of(TEAM_B));
        memberA = session("kisia", "USER", List.of(TEAM_A));
        memberB = session("kisib", "USER", List.of(TEAM_B));

        alertA = new AlertEvent();
        alertA.setId(50L); alertA.setTeamId(TEAM_A); alertA.setDomain("a.example.com"); alertA.setAlertType("PING_DOWN");
        alertA.setAlertLevel("CRITICAL"); alertA.setCreatedAt("2026-01-01T03:00:00");
        when(alertEventRepo.findById(50L)).thenReturn(Optional.of(alertA));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
        when(alertEventRepo.findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                any(), any(), anyBoolean(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(alertA)));

        when(nocCallLog.seesAllAlerts(any())).thenAnswer(i -> isNoc(i.getArgument(0)));
        when(nocCallLog.canWrite(any())).thenAnswer(i -> isNoc(i.getArgument(0)));
        doAnswer(i -> {
            List<AlertEvent> rows = i.getArgument(0);
            for (AlertEvent a : rows) {
                a.setNocCallCount(2L);
                a.setNocLastCall(new LinkedHashMap<>(Map.of("contacted_name", "Kişi A", "outcome", "REACHED",
                        "contacted_at", "2026-01-01T03:12:00")));
            }
            return null;
        }).when(nocCallLog).decorate(anyList());
    }

    @Test
    @DisplayName("liste: 7/24 operatörü (takım B) kapsamsız sorgular; satır özeti + noc_can_write=true")
    void nocOperatorSeesAllWithSummary() throws Exception {
        mvc.perform(get("/api/admin/alerts").session(nocB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.noc_can_write").value(true))
                .andExpect(jsonPath("$.data[0].noc_call_count").value(2))
                .andExpect(jsonPath("$.data[0].noc_last_call.contacted_name").value("Kişi A"))
                .andExpect(jsonPath("$.data[0].noc_last_call.outcome").value("REACHED"))
                .andExpect(jsonPath("$.data[0].noc_last_call.contacted_at").value("2026-01-01T03:12:00"));
        verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                any(), any(), eq(false), any(), any(Pageable.class));
        verify(nocCallLog, times(1)).decorate(anyList());
    }

    @Test
    @DisplayName("liste: takım üyesi kendi kapsamıyla sorgular; noc_can_write=false")
    void memberStaysScoped() throws Exception {
        mvc.perform(get("/api/admin/alerts").session(memberA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.noc_can_write").value(false));
        verify(alertEventRepo).findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(),
                any(), any(), eq(true), eq(List.of(TEAM_A)), any(Pageable.class));
    }

    @Test
    @DisplayName("tekil uyarı + teslimat günlüğü: 7/24 operatörü başka takımın uyarısını açar; kapsam dışı üye 403")
    void singleAlertAndDeliveries() throws Exception {
        mvc.perform(get("/api/admin/alerts/50").session(nocB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(50))
                .andExpect(jsonPath("$.data.noc_call_count").value(2))
                .andExpect(jsonPath("$.noc_can_write").value(true));
        mvc.perform(get("/api/admin/alerts/50").session(memberA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.noc_can_write").value(false));
        mvc.perform(get("/api/admin/alerts/50").session(memberB)).andExpect(status().isForbidden());
        when(notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(50L)).thenReturn(List.of());
        mvc.perform(get("/api/admin/alerts/50/notifications").session(nocB)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/alerts/50/notifications").session(memberB)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/alerts/50/push-deliveries").session(nocB)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("YAZMA eylemleri genişlemez: 7/24 operatörü başka takımın uyarısını sahiplenemez/çözemez (403)")
    void writeActionsStayScoped() throws Exception {
        String note = "{\"note\":\"arandı ve bakıyor şu an\"}";
        mvc.perform(post("/api/admin/alerts/50/acknowledge").session(nocB).contentType(MediaType.APPLICATION_JSON).content(note))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/alerts/50/resolve").session(nocB).contentType(MediaType.APPLICATION_JSON).content(note))
                .andExpect(status().isForbidden());
        verifyNoInteractions(escalationService);
    }

    // ── 2026-09-28: Olaylar org geneli salt okunur — olayın ALARM eylemleri başka ekipte kapalı kalır ─────────────

    @Test
    @DisplayName("başka ekibin olayı (takım A'nın uyarısı), takım B üyesi: sahiplen/çöz/yeniden bildir/önizleme 403; toplu işlem ATLAR")
    void foreignIncident_everyAlertWriteForbidden() throws Exception {
        String note = "{\"note\":\"başka ekibin olayına müdahale denemesi\"}";
        mvc.perform(post("/api/admin/alerts/50/acknowledge").session(memberB).contentType(MediaType.APPLICATION_JSON).content(note))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/alerts/50/resolve").session(memberB).contentType(MediaType.APPLICATION_JSON).content(note))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/alerts/50/re-notify").session(memberB).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/alerts/50/re-notify/preview").session(memberB))
                .andExpect(status().isForbidden());
        for (String action : List.of("acknowledge", "resolve", "re-notify")) {
            mvc.perform(post("/api/admin/alerts/bulk").session(memberB).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"" + action + "\",\"ids\":[50],\"note\":\"toplu müdahale denemesi\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.processed").value(0))
                    .andExpect(jsonPath("$.data.skipped").value(1));
        }
        verifyNoInteractions(escalationService);
        verifyNoInteractions(userPushService);
    }

    @Test
    @DisplayName("AUDIT + noc_calls.write (7/24 operatörü): tüm uyarıları görür, can_act=false; sahiplen/çöz 403 (alerts.actions yok)")
    void auditNocOperator_logsCallsButCannotAct() throws Exception {
        MockHttpSession audit = new MockHttpSession();
        audit.setAttribute("authenticated", Boolean.TRUE);
        audit.setAttribute("username", "noc1");
        audit.setAttribute("systemRole", "AUDIT");   // viewTeamIds YOK → global görüntüleyici
        doReturn(true).when(nocCallLog).canWrite(any());   // when(...) mevcut cevabı null oturumla çağırırdı
        when(permissionService.allows(any(HttpSession.class), eq("alerts.actions"), eq("execute")))
                .thenAnswer(i -> !"AUDIT".equals(((HttpSession) i.getArgument(0)).getAttribute("systemRole")));
        doThrow(new SecurityException("Bu işlem için yetkiniz yok: alerts.actions/execute")).when(permissionService)
                .require(argThat((HttpSession s) -> s != null && "AUDIT".equals(s.getAttribute("systemRole"))),
                        eq("alerts.actions"), eq("execute"));

        mvc.perform(get("/api/admin/alerts").session(audit)).andExpect(status().isOk())
                .andExpect(jsonPath("$.noc_can_write").value(true))
                .andExpect(jsonPath("$.can_act").value(false));
        mvc.perform(get("/api/admin/alerts/50").session(audit)).andExpect(status().isOk())
                .andExpect(jsonPath("$.noc_can_write").value(true))
                .andExpect(jsonPath("$.can_act").value(false));
        mvc.perform(get("/api/admin/alerts").session(memberA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.can_act").value(true));

        String note = "{\"note\":\"arandı, ekip bakıyor\"}";
        mvc.perform(post("/api/admin/alerts/50/acknowledge").session(audit).contentType(MediaType.APPLICATION_JSON).content(note))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/alerts/50/resolve").session(audit).contentType(MediaType.APPLICATION_JSON).content(note))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/alerts/50/re-notify").session(audit).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(escalationService);
    }
}
