package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.noc.NocCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Yeniden gönderim önizlemesi — alarm iletimi satırları (2026-11-05): e-posta satırında {@code kind=FORWARD} ise
 * {@code team_name} (= satırın adı, alıcı takım); push satırında iletim satırı {@code kind} + {@code team_name} taşır. Diğer
 * satırlar bugünkü biçimde kalır (alanlar YOK — eski ön yüz bozulmaz). Bean listesi {@code AlertStandaloneOwnerScopeTest} ile
 * aynı dilim.
 */
@WebMvcTest(AdminController.class)
class ReNotifyPreviewForwardRowsTest {

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
    @MockitoBean com.sitemonitor.service.oncall.OnCallService onCallService;

    private MockHttpSession admin;

    @BeforeEach
    void setUp() {
        admin = new MockHttpSession();
        admin.setAttribute("authenticated", Boolean.TRUE);
        admin.setAttribute("username", "admin");
        admin.setAttribute("systemRole", "ADMIN");
        AlertEvent e = new AlertEvent();
        e.setId(70L); e.setDomain("odeme.example.com"); e.setAlertType("HTTP_DOWN"); e.setTeamId(1L);
        e.setAlertLevel("HIGH"); e.setResolved(false); e.setAcknowledged(false); e.setCreatedAt("2026-11-05T08:00:00");
        when(alertEventRepo.findById(70L)).thenReturn(Optional.of(e));
        when(escalationService.reNotifyFallbackTeamId(70L)).thenReturn(1L);
        when(escalationService.reNotifyOnCall(70L)).thenReturn(List.of());
        when(escalationService.previewReNotify(70L)).thenReturn(List.of(
                new EscalationService.ReNotifyRecipient("odeme@example.com", "Ödeme Sistemleri", "Takım maili", "TEAM"),
                new EscalationService.ReNotifyRecipient("destek@example.com", "Çağrı Merkezi Destek", "İletim: Çağrı Merkezi Destek", "FORWARD")));
        when(userPushService.preview(eq(70L), any(), any())).thenReturn(new UserPushService.PushPreview(true, null, List.of(
                new UserPushService.PushPreviewRow("N10001", "Ad Soyad", "PENDING"),
                new UserPushService.PushPreviewRow("N20001", "Ad İkinci", "PENDING", null,
                        UserPushService.PREVIEW_KIND_FORWARD, "Çağrı Merkezi Destek"))));
    }

    @Test
    @DisplayName("e-posta ve push iletim satırları kind=FORWARD + team_name; sahip satırları bugünkü biçimde (team_name / kind yok)")
    void forwardRowsCarryKindAndTeam() throws Exception {
        mvc.perform(get("/api/admin/alerts/70/re-notify/preview").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipients[0].kind").value("TEAM"))
                .andExpect(jsonPath("$.data.recipients[0].team_name").doesNotExist())
                .andExpect(jsonPath("$.data.recipients[1].kind").value("FORWARD"))
                .andExpect(jsonPath("$.data.recipients[1].team_name").value("Çağrı Merkezi Destek"))
                .andExpect(jsonPath("$.data.webhook.recipients[0].username").value("N10001"))
                .andExpect(jsonPath("$.data.webhook.recipients[0].kind").doesNotExist())
                .andExpect(jsonPath("$.data.webhook.recipients[0].team_name").doesNotExist())
                .andExpect(jsonPath("$.data.webhook.recipients[1].kind").value("FORWARD"))
                .andExpect(jsonPath("$.data.webhook.recipients[1].team_name").value("Çağrı Merkezi Destek"))
                .andExpect(jsonPath("$.data.webhook.recipients[1].status").value("PENDING"));
    }
}
