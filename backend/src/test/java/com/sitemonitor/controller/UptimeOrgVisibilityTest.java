package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.DnsCheckerService;
import com.sitemonitor.service.InventoryVisibility;
import com.sitemonitor.service.PortCheckerService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Durum İzleme (Sertifikalar → Durum İzleme) org geneli görünürlük (2026-09-26, kapsam genişletmesi): aynı ayar,
 * aynı kararlar. Özet {@code scope=all} ile başka takımların kartlarını salt okunur verir (kart başına
 * {@code can_manage} + {@code team_id}); kart detayının okuma uçları (HTTP/SSL geçmişi, saatlik çubuklar, SSL
 * yanıt serisi) başka takımın alan adında da açılır ama ALARM KATMANI verilmez (alarmlar takım kapsamlı). Diğer
 * izleme sayfalarının geçmişi takım kapsamında kalır. Kartın tek eylemi "Tanıla" dış prob başlattığı için özgün
 * görüş kapsamında kalır — {@code InventoryOrgVisibilityAdminTest.diagnostics_foreignRejected}.
 */
@WebMvcTest(MonitoringController.class)
@Import({com.sitemonitor.service.CheckHistoryService.class, InventoryVisibility.class})
class UptimeOrgVisibilityTest {

    @Autowired MockMvc mvc;

    @MockitoBean com.sitemonitor.service.retention.RetentionService retentionService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean com.sitemonitor.service.ActivityLogService activityLog;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.MonitorHistoryService monitorHistory;
    @MockitoBean MonitorChangeLogRepository changeLogRepo;
    @MockitoBean LatestCheckRepository latestCheckRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean CertificateCheckRepository certCheckRepo;
    @MockitoBean UptimeCheckRepository uptimeCheckRepo;
    @MockitoBean com.sitemonitor.service.MonitoringGroupService monitoringGroupService;
    @MockitoBean PortMonitorRepository portMonitorRepo;
    @MockitoBean PortCheckRepository portCheckRepo;
    @MockitoBean PortCheckerService portChecker;
    @MockitoBean DnsMonitorRepository dnsMonitorRepo;
    @MockitoBean DnsRecordRepository dnsRecordRepo;
    @MockitoBean DnsCheckerService dnsChecker;
    @MockitoBean CertificateService certificateService;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;
    @MockitoBean KeywordMonitorRepository keywordMonitorRepo;
    @MockitoBean com.sitemonitor.service.KeywordHeaderSecrets keywordHeaderSecrets;
    @MockitoBean KeywordResultRepository keywordResultRepo;
    @MockitoBean com.sitemonitor.service.KeywordCheckerService keywordChecker;
    @MockitoBean PingMonitorRepository pingMonitorRepo;
    @MockitoBean PingCheckRepository pingCheckRepo;
    @MockitoBean com.sitemonitor.service.PingCheckerService pingChecker;
    @MockitoBean HttpMonitorRepository httpMonitorRepo;
    @MockitoBean HttpCheckRepository httpCheckRepo;
    @MockitoBean com.sitemonitor.service.HttpCheckerService httpChecker;
    @MockitoBean DomainMonitorRepository domainMonitorRepo;
    @MockitoBean DomainCheckRepository domainCheckRepo;
    @MockitoBean com.sitemonitor.service.DomainExpiryReminderService domainReminders;
    @MockitoBean com.sitemonitor.service.DomainRenewalPlanService domainRenewalPlans;
    @MockitoBean com.sitemonitor.service.MonitoringTagService monitoringTags;
    @MockitoBean com.sitemonitor.service.DomainCheckerService domainChecker;
    @MockitoBean com.sitemonitor.service.PublicSuffixService publicSuffixService;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean com.sitemonitor.service.EscalationService escalationService;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;
    @MockitoBean com.sitemonitor.service.MonitoringOutageService monitoringOutageService;
    @MockitoBean AlertEventRepository alertEventRepo;
    @MockitoBean AppUserRepository appUserRepo;
    @MockitoBean com.sitemonitor.service.SchedulerService schedulerService;
    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean PageCheckRepository pageCheckRepo;
    @MockitoBean PageResourceIssueRepository pageResourceIssueRepo;
    @MockitoBean com.sitemonitor.service.PageCheckerService pageChecker;
    @MockitoBean PageSpeedMonitorRepository pageSpeedMonitorRepo;
    @MockitoBean PageSpeedCheckRepository pageSpeedCheckRepo;
    @MockitoBean PageSpeedResourceRepository pageSpeedResourceRepo;
    @MockitoBean com.sitemonitor.service.PageSpeedCheckerService pageSpeedChecker;
    @MockitoBean ScriptedMonitorRepository scriptedMonitorRepo;
    @MockitoBean ScriptedCheckRepository scriptedCheckRepo;
    @MockitoBean ScriptedScriptVersionRepository scriptedVersionRepo;
    @MockitoBean ScriptedDraftRepository scriptedDraftRepo;
    @MockitoBean com.sitemonitor.service.ScriptedCheckerService scriptedChecker;
    @MockitoBean com.sitemonitor.service.ProxySettings proxySettings;
    @MockitoBean com.sitemonitor.service.SsrfGuard ssrfGuard;
    @MockitoBean com.sitemonitor.service.SecretCipher secretCipher;
    @MockitoBean NotificationGroupRepository notificationGroupRepo;

    private static final long OWN_TEAM = 5L, FOREIGN_TEAM = 9L;

    private static MockHttpSession user() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u1");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", OWN_TEAM);
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(OWN_TEAM)));
        return s;
    }

    /** Kapsamlı AD ADMIN (müdür): rol ADMIN, görüş = yönetim = kendi + ast takımı; GLOBAL DEĞİL. */
    private static MockHttpSession scopedAdmin() {
        MockHttpSession s = user();
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(OWN_TEAM, 6L)));
        s.setAttribute("manageTeamIds", new ArrayList<>(List.of(OWN_TEAM, 6L)));
        return s;
    }

    private static CertificateInventory inv(String domain, long teamId) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setTeamId(teamId); i.setPort(443); i.setActive(true);
        return i;
    }

    private static LatestCheck lc(String domain) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain); c.setStatus("valid"); c.setDaysRemaining(40);
        c.setNotAfter("2099-12-31T00:00:00"); c.setCheckedAt("2099-01-01T00:00:00");
        return c;
    }

    private static <T> org.springframework.data.domain.Page<T> empty() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
    }

    private void switchOn(boolean on) {
        when(appSettings.getBoolean(eq(InventoryVisibility.SETTING_KEY), anyBoolean())).thenReturn(on);
    }

    @BeforeEach
    void setUp() {
        switchOn(true);
        when(permissionService.allows(any(HttpSession.class), eq("inventory.list"), eq("view"))).thenReturn(true);
        org.mockito.Mockito.lenient().when(retentionService.historyRetentionDays(anyString(), anyInt())).thenReturn(180);
        when(certificateService.domainTeamNameMap()).thenReturn(Map.of(
                "own.example.com", "Takım A", "foreign.example.com", "Takım B"));
        when(certificateService.teamNamesById()).thenReturn(Map.of());
        CertificateInventory own = inv("own.example.com", OWN_TEAM);
        CertificateInventory foreign = inv("foreign.example.com", FOREIGN_TEAM);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(foreign, own));
        when(inventoryRepo.findByDomain("own.example.com")).thenReturn(Optional.of(own));
        when(inventoryRepo.findByDomain("foreign.example.com")).thenReturn(Optional.of(foreign));
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(lc("foreign.example.com"), lc("own.example.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetween(anyString(), anyInt(), anyString(), anyString(), any())).thenReturn(empty());
        when(certCheckRepo.findByDomainAndCheckedAtBetween(anyString(), anyString(), anyString(), any())).thenReturn(empty());
        when(uptimeCheckRepo.historyHistogram(anyString(), anyInt(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(certCheckRepo.historyHistogram(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(alertEventRepo.findOverlappingForHistory(anyString(), any(), anyString(), anyString())).thenReturn(List.of());
    }

    // ══ Özet ═════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("özet scope=all → başka takımın kartı da gelir; kart başına team_id + can_manage")
    void overviewAll_includesForeignCards() throws Exception {
        mvc.perform(get("/api/monitoring/uptime/overview").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all"))
                .andExpect(jsonPath("$.visible_to_all").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].domain").value("foreign.example.com"))
                .andExpect(jsonPath("$.data[0].team_id").value(9))
                .andExpect(jsonPath("$.data[0].team_name").value("Takım B"))
                .andExpect(jsonPath("$.data[0].can_manage").value(false))
                .andExpect(jsonPath("$.data[0].ssl_valid_days").value(40))
                .andExpect(jsonPath("$.data[1].domain").value("own.example.com"))
                .andExpect(jsonPath("$.data[1].can_manage").value(true));
    }

    @Test
    @DisplayName("özet scope=mine (varsayılan) BUGÜNKÜ kapsam; kapsamlı müdür de yalnız kendi + ast takımını görür")
    void overviewMine_unchanged() throws Exception {
        mvc.perform(get("/api/monitoring/uptime/overview").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mine"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].domain").value("own.example.com"))
                .andExpect(jsonPath("$.data[0].can_manage").value(true));
        mvc.perform(get("/api/monitoring/uptime/overview").session(scopedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/monitoring/uptime/overview").param("scope", "all").session(scopedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].can_manage").value(false))
                .andExpect(jsonPath("$.data[1].can_manage").value(true));
    }

    @Test
    @DisplayName("ayar KAPALI → özet scope=all isteğinde de kendi kapsamında kalır")
    void overviewAll_switchOff_behavesLikeMine() throws Exception {
        switchOn(false);
        mvc.perform(get("/api/monitoring/uptime/overview").param("scope", "all").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("mine"))
                .andExpect(jsonPath("$.visible_to_all").value(false))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ══ Kart detayı — okuma uçları ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("kart detayı: başka takımın HTTP/SSL geçmişi, saatlik çubukları ve SSL serisi OKUNUR — alarm katmanı SORULMAZ")
    void detailReads_foreignAllowed_withoutAlertLayer() throws Exception {
        mvc.perform(get("/api/monitoring/uptime/foreign.example.com/http-history").param("days", "1").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alerts.length()").value(0));
        mvc.perform(get("/api/monitoring/uptime/foreign.example.com/ssl-history").param("days", "1").session(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alerts.length()").value(0));
        mvc.perform(get("/api/monitoring/uptime/foreign.example.com/history").session(user()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/monitoring/uptime/foreign.example.com/ssl/response-series").session(user()))
                .andExpect(status().isOk());
        verify(alertEventRepo, never()).findOverlappingForHistory(anyString(), any(), anyString(), anyString());

        // Kendi kaydında alarm katmanı yine sorulur (davranış değişmedi).
        mvc.perform(get("/api/monitoring/uptime/own.example.com/ssl-history").param("days", "1").session(user()))
                .andExpect(status().isOk());
        verify(alertEventRepo).findOverlappingForHistory(eq("own.example.com"), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("ayar KAPALI → kart detayı başka takımın alan adında bugünkü gibi 403")
    void detailReads_switchOff_rejected() throws Exception {
        switchOn(false);
        mvc.perform(get("/api/monitoring/uptime/foreign.example.com/http-history").param("days", "1").session(user()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/uptime/foreign.example.com/ssl-history").param("days", "1").session(user()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/uptime/foreign.example.com/history").session(user()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/uptime/foreign.example.com/ssl/response-series").session(user()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("diğer izleme sayfaları takım kapsamlı kalır: başka takımın port izlemesinin geçmişi ayar açıkken de 403")
    void otherMonitoringPages_stayTeamScoped() throws Exception {
        var port = new com.sitemonitor.model.PortMonitor();
        port.setHost("foreign.example.com"); port.setTeamId(FOREIGN_TEAM); port.setStandalone(true);
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(port));
        mvc.perform(get("/api/monitoring/port/1/history").param("days", "1").session(user()))
                .andExpect(status().isForbidden());
    }
}
