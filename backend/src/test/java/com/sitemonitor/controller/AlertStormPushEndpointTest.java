package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.StormPushCoverageService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.noc.NocCallLogService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code GET /api/admin/alerts/{id}/storm-push} (2026-10-04) — alarm detayının "Fırtına push'u" bölümü. Kapı alarmın push
 * bölümüyle ({@code /push-deliveries}) AYNI: {@code alerts.read/view} + takım kapsamı; 7/24 operatörü okur, başka takımın
 * üyesi 403, olmayan alarm 404, izin yoksa 403. Sertifika alarmında (olayda takım yok) envanterin SY takımı tahmin yedeği.
 * Bean listesi {@link AlertListNocCallTest} ile aynı dilim + {@link StormPushCoverageService} mock'u.
 */
@WebMvcTest(AdminController.class)
class AlertStormPushEndpointTest {

    private static final long TEAM_A = 1L, TEAM_B = 2L;

    @Autowired MockMvc mvc;

    @MockitoBean StormPushCoverageService stormPushCoverage;
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

        AlertEvent alertA = new AlertEvent();
        alertA.setId(50L); alertA.setTeamId(TEAM_A); alertA.setDomain("a.example.com"); alertA.setAlertType("HTTP_DOWN");
        alertA.setAlertLevel("WARNING"); alertA.setCreatedAt("2026-10-04T08:00:00"); alertA.setStormId(7L);
        when(alertEventRepo.findById(50L)).thenReturn(Optional.of(alertA));
        // Sertifika alarmı: olayda takım YOK, sahip envanterden (SY = A)
        AlertEvent cert = new AlertEvent();
        cert.setId(60L); cert.setDomain("cert.example.com"); cert.setAlertType("EXPIRY"); cert.setAlertLevel("HIGH");
        cert.setCreatedAt("2026-10-04T08:00:00");
        when(alertEventRepo.findById(60L)).thenReturn(Optional.of(cert));
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setDomain("cert.example.com"); inv.setTeamId(TEAM_A);
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
        when(inventoryRepo.findByDomain("cert.example.com")).thenReturn(Optional.of(inv));
        when(alertEventRepo.findById(99L)).thenReturn(Optional.empty());

        when(nocCallLog.seesAllAlerts(any())).thenAnswer(i -> isNoc(i.getArgument(0)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alert_id", 50L);
        payload.put("handed_over", true);
        payload.put("items", List.of(Map.of("storm_id", 7, "trigger", "INITIAL", "sent", 2, "inferred", false,
                "recipients", List.of(Map.of("username", "N00001", "status", "SENT")))));
        payload.put("pending", List.of());
        when(stormPushCoverage.alarmDetail(any(), any())).thenReturn(payload);
    }

    @Test
    @DisplayName("takım A üyesi kendi alarmının fırtına push'unu okur: kapsayan push + alıcı durumu")
    void memberOfOwningTeam_reads() throws Exception {
        mvc.perform(get("/api/admin/alerts/50/storm-push").session(memberA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].storm_id").value(7))
                .andExpect(jsonPath("$.data.items[0].recipients[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.handed_over").value(true));
        verify(permissionService, atLeastOnce()).require(any(HttpSession.class), eq("alerts.read"), eq("view"));
        verify(stormPushCoverage).alarmDetail(argThat(e -> e.getId() == 50L), isNull());
    }

    @Test
    @DisplayName("kapsam: başka takımın üyesi 403 (servis hiç çağrılmaz); 7/24 operatörü okur; olmayan alarm 404")
    void scopeGate() throws Exception {
        mvc.perform(get("/api/admin/alerts/50/storm-push").session(memberB)).andExpect(status().isForbidden());
        verify(stormPushCoverage, never()).alarmDetail(any(), any());
        mvc.perform(get("/api/admin/alerts/50/storm-push").session(nocB)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/alerts/99/storm-push").session(nocB)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("izin: alerts.read yoksa 403 — kapsam ve servis koşmaz")
    void permissionGate() throws Exception {
        doThrow(new SecurityException("yetki yok")).when(permissionService).require(any(HttpSession.class), eq("alerts.read"), eq("view"));
        mvc.perform(get("/api/admin/alerts/50/storm-push").session(memberA)).andExpect(status().isForbidden());
        verify(stormPushCoverage, never()).alarmDetail(any(), any());
    }

    @Test
    @DisplayName("sertifika alarmı (olayda takım yok): envanterin SY takımı tahmin yedeği olarak geçer")
    void certAlarm_inventoryFallbackTeam() throws Exception {
        mvc.perform(get("/api/admin/alerts/60/storm-push").session(memberA)).andExpect(status().isOk());
        verify(stormPushCoverage).alarmDetail(argThat(e -> e.getId() == 60L), eq(TEAM_A));
    }
}
