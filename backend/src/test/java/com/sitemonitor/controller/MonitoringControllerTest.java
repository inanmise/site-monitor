package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.UptimeCheck;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.DnsCheckerService;
import com.sitemonitor.service.PortCheckerService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MonitoringController.class)
// Kontrol Geçmişi v2 zarfı (resolve/clamp/envelope) GERÇEK servisle test edilir — mock'lanırsa
// kontrat testi mock'u test etmiş olur. Tek bağımlılığı AlertEventRepository zaten @MockitoBean.
@org.springframework.context.annotation.Import(com.sitemonitor.service.CheckHistoryService.class)
class MonitoringControllerTest {

    @Autowired MockMvc mvc;

    /** Saklama süreleri katalogdan okunur; kontrat testinde mock yeter (varsayılan 0 → fallback kullanılır). */
    @MockitoBean com.sitemonitor.service.retention.RetentionService retentionService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean com.sitemonitor.service.ActivityLogService activityLog;
    @MockitoBean com.sitemonitor.service.AuditService auditService;

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
    @MockitoBean com.sitemonitor.service.DomainCheckerService domainChecker;
    @MockitoBean com.sitemonitor.service.PublicSuffixService publicSuffixService;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean com.sitemonitor.service.EscalationService escalationService;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;
    @MockitoBean com.sitemonitor.service.MonitoringOutageService monitoringOutageService;   // canlı teyit endpoint'i (2026-08-03)
    @MockitoBean AlertEventRepository alertEventRepo;

    // 9. tür (sayfa-bütünlüğü) — controller alan-enjekte eder → @WebMvcTest slice'ında mock zorunlu.
    @MockitoBean com.sitemonitor.service.SchedulerService schedulerService;
    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean PageCheckRepository pageCheckRepo;
    @MockitoBean PageResourceIssueRepository pageResourceIssueRepo;
    @MockitoBean com.sitemonitor.service.PageCheckerService pageChecker;
    // 10. tür (senaryo/k6) — aynı desen: controller alan-enjekte eder → mock zorunlu.
    @MockitoBean com.sitemonitor.repository.ScriptedMonitorRepository scriptedMonitorRepo;
    @MockitoBean com.sitemonitor.repository.ScriptedCheckRepository scriptedCheckRepo;
    // k6 script sürüm geçmişi + otomatik taslak (controller alan enjeksiyonuyla kullanıyor).
    @MockitoBean com.sitemonitor.repository.ScriptedScriptVersionRepository scriptedVersionRepo;
    @MockitoBean com.sitemonitor.repository.ScriptedDraftRepository scriptedDraftRepo;
    @MockitoBean com.sitemonitor.service.ScriptedCheckerService scriptedChecker;
    @MockitoBean com.sitemonitor.service.ProxySettings proxySettings;
    @MockitoBean com.sitemonitor.service.SsrfGuard ssrfGuard;
    @MockitoBean com.sitemonitor.service.SecretCipher secretCipher;

    @BeforeEach
    void stubTeamMap() {
        // Senaryo kaydetme yolu artık kaydetmeden önce script doğrulaması çağırıyor; mock varsayılanı
        // null döner ve NPE'ye yol açar. Zararsız (engellemeyen, uyarısız) bir sonuç stub'la.
        org.mockito.Mockito.lenient().when(scriptedChecker.validateScript(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.sitemonitor.service.ScriptedCheckerService.ScriptDiagnostics(null, java.util.List.of()));
        // İzleme uçları artık domain→takım map'ini buradan alıyor; boş map yeterli (team_name=null).
        when(certificateService.domainTeamNameMap()).thenReturn(java.util.Map.of());
        // teamNameMap() artık CertificateService.teamNamesById()'e (cache'li) delege ediyor.
        when(certificateService.teamNamesById()).thenReturn(java.util.Map.of());
        // Grup get-or-create artık merkezi servise gidiyor; testte ham adı (kanonik) geri döndür.
        when(monitoringGroupService.getOrCreateFor(any(), any(), any(), any()))
                .thenAnswer(i -> i.getArgument(2));
    }

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        return s;
    }

    private static CertificateInventory inv(String domain) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setActive(true);
        return i;
    }

    private static LatestCheck lc(String domain, String status) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain); c.setStatus(status); c.setDaysRemaining(40);
        c.setNotAfter("2026-12-31T00:00:00"); c.setCheckedAt("2026-06-13T00:00:00");
        return c;
    }

    private static UptimeCheck uchk(String domain, String status) {
        UptimeCheck u = new UptimeCheck();
        u.setDomain(domain); u.setPort(443); u.setStatus(status);
        u.setCheckedAt("2099-01-01T00:00:00");
        return u;
    }

    @Test
    @DisplayName("uptimeOverview: uptime% domain-bazlı toplu sorgudan (4 toplam/1 hata → %75), lc'siz domain → 'unknown'")
    void uptimeOverview_computesUptimeAndHandlesMissingCheck() throws Exception {
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(lc("a.com", "valid")));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.com"), inv("b.com")));
        // P1/N1: artık domain başına findByCheckedAtAfter (tüm tablo) yerine tek gruplu sorgu.
        when(certCheckRepo.aggregateStatusCountsSince(anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"a.com", 4L, 1L}));

        mvc.perform(get("/api/monitoring/uptime/overview").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("a.com"))
                .andExpect(jsonPath("$.data[0].uptime_30d").value(75.0))
                .andExpect(jsonPath("$.data[0].incidents_30d").value(1))
                .andExpect(jsonPath("$.data[1].domain").value("b.com"))
                .andExpect(jsonPath("$.data[1].status").value("unknown"))
                .andExpect(jsonPath("$.data[1].uptime_30d").doesNotExist()); // null

        // Regresyon: domain başına tüm-tablo taraması bir daha yapılmamalı (P1).
        org.mockito.Mockito.verify(certCheckRepo, org.mockito.Mockito.never()).findByCheckedAtAfter(anyString());
        org.mockito.Mockito.verify(uptimeCheckRepo, org.mockito.Mockito.never())
                .findTopByDomainAndPortOrderByIdDesc(anyString(), anyInt());
    }

    @Test
    @DisplayName("POST /dns/test: çözümler ama KAYIT OLUŞTURMAZ; response_ms eşiği aşınca slow=true")
    void testDns_runsWithoutSaving() throws Exception {
        when(dnsChecker.check(eq("x.com"), eq("A"))).thenReturn(
                java.util.Map.of("success", true, "values", List.of("1.2.3.4"), "ttl", 300L, "response_ms", 2000L));

        mvc.perform(post("/api/monitoring/dns/test").session(session("USER"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"x.com\",\"recordType\":\"A\",\"slowThresholdMs\":1500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.slow").value(true))
                .andExpect(jsonPath("$.data.values[0]").value("1.2.3.4"));

        org.mockito.Mockito.verify(dnsMonitorRepo, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(dnsRecordRepo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("GET /dns: domain'de açık DNS alarmı varsa active_alarm=true + en yüksek seviye (CRITICAL)")
    void listDns_marksActiveAlarm() throws Exception {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.com")));
        when(dnsMonitorRepo.findAll()).thenReturn(List.of());
        when(dnsRecordRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(dnsMonitorRepo.findByStandaloneTrueAndActiveTrue()).thenReturn(List.of());
        com.sitemonitor.model.AlertEvent slow = openDnsEvent("a.com", com.sitemonitor.service.EscalationService.TYPE_DNS_SLOW, "HIGH");
        com.sitemonitor.model.AlertEvent fail = openDnsEvent("a.com", com.sitemonitor.service.EscalationService.TYPE_DNS_FAILURE, "CRITICAL");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(slow, fail));

        mvc.perform(get("/api/monitoring/dns").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].active_alarm").value(true))
                .andExpect(jsonPath("$.data[0].alarm_level").value("CRITICAL"));   // SLOW+FAILURE → en severe
    }

    private static com.sitemonitor.model.AlertEvent openDnsEvent(String domain, String type, String level) {
        com.sitemonitor.model.AlertEvent e = new com.sitemonitor.model.AlertEvent();
        e.setDomain(domain); e.setAlertType(type); e.setAlertLevel(level);
        e.setResolved(false); e.setAcknowledged(false);
        return e;
    }

    private MockHttpSession sessionWithTeam(String role, Long teamId) {
        MockHttpSession s = session(role);
        s.setAttribute("teamId", teamId);
        return s;
    }

    private static com.sitemonitor.model.PageMonitor pageMon(Long id, String url, Long teamId) {
        com.sitemonitor.model.PageMonitor m = new com.sitemonitor.model.PageMonitor();
        m.setId(id); m.setName(url); m.setUrl(url); m.setTeamId(teamId); m.setActive(true);
        return m;
    }

    // ── 9. tür: Sayfa Bütünlüğü ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /page: açık PAGE_DOWN alarmı → active_alarm=true + seviye; kontrolsüz monitör status=unknown")
    void listPage_marksAlarm() throws Exception {
        when(pageCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(pageMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(pageMon(1L, "https://x.com", 1L)));
        com.sitemonitor.model.AlertEvent down = openDnsEvent("https://x.com",
                com.sitemonitor.service.EscalationService.TYPE_PAGE_DOWN, "CRITICAL");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(down));

        // ADMIN (global görücü) → tüm takımları görür (H2 filtresi global admin'i etkilemez).
        mvc.perform(get("/api/monitoring/page").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].active_alarm").value(true))
                .andExpect(jsonPath("$.data[0].alarm_level").value("CRITICAL"))
                .andExpect(jsonPath("$.data[0].status").value("unknown"));
    }

    @Test
    @DisplayName("H2 IDOR: GET /page yalnız görüntülenebilir takımın monitörünü döndürür (başka takım sızmaz)")
    void listPage_scopesToViewableTeams() throws Exception {
        when(pageCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(pageMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(
                pageMon(1L, "https://a.com", 1L), pageMon(2L, "https://b.com", 2L)));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));   // yalnız takım 1'i görebilir

        mvc.perform(get("/api/monitoring/page").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].url").value("https://a.com"));   // takım 2 (b.com) SIZMAZ
    }

    @Test
    @DisplayName("H3 IDOR: GET /page/{id}/response-series başka takımda 403")
    void pageResponseSeries_foreignTeam_forbidden() throws Exception {
        when(pageMonitorRepo.findById(9L)).thenReturn(Optional.of(pageMon(9L, "https://x.com", 2L)));
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));   // takım 2'yi göremez

        mvc.perform(get("/api/monitoring/page/9/response-series").session(s))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("H1c: POST /page/{id}/check per-monitör cooldown içinde ikinci tetik → 429")
    void triggerPage_cooldownReturns429() throws Exception {
        when(appSettings.getInt(eq("site.monitor.page.manual-cooldown-seconds"), anyInt())).thenReturn(20);
        when(pageMonitorRepo.findById(3L)).thenReturn(Optional.of(pageMon(3L, "https://x.com", 1L)));
        MockHttpSession s = session("ADMIN");

        mvc.perform(post("/api/monitoring/page/3/check").session(s)).andExpect(status().isOk());
        mvc.perform(post("/api/monitoring/page/3/check").session(s)).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("POST /page: alertMixedContent=false kaydedilir + enrich'te alert_mixed_content=false döner")
    void createPage_persistsAlertMixedContent() throws Exception {
        when(pageMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(pageMonitorRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageMonitor m = i.getArgument(0); m.setId(1L); return m; });

        mvc.perform(post("/api/monitoring/page").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.com\",\"teamId\":1,\"alertMixedContent\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alert_mixed_content").value(false))
                .andExpect(jsonPath("$.data.alert_third_party").value(false))    // varsayılan false
                .andExpect(jsonPath("$.data.alert_timeout").value(true));        // varsayılan true (mevcut davranış)
    }

    @Test
    @DisplayName("POST /page: alertTimeout=false kaydedilir + enrich'te alert_timeout=false döner")
    void createPage_persistsAlertTimeout() throws Exception {
        when(pageMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(pageMonitorRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageMonitor m = i.getArgument(0); m.setId(1L); return m; });

        mvc.perform(post("/api/monitoring/page").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.com\",\"teamId\":1,\"alertTimeout\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alert_timeout").value(false))
                .andExpect(jsonPath("$.data.alert_mixed_content").value(true));  // varsayılan true
    }

    @Test
    @DisplayName("POST /page: takımsız kullanıcı takım çözemez → 400 (takım zorunlu), kayıt yok")
    void createPage_requiresTeam() throws Exception {
        mvc.perform(post("/api/monitoring/page").session(session("USER"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.com\"}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(pageMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("PUT /page/{id}: BAŞKA takımın monitörünü düzenleme → 403 (IDOR guard), kayıt yok")
    void updatePage_foreignTeam_forbidden() throws Exception {
        when(pageMonitorRepo.findById(5L)).thenReturn(Optional.of(pageMon(5L, "https://x.com", 2L)));

        mvc.perform(put("/api/monitoring/page/5").session(sessionWithTeam("USER", 1L))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hack\"}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verify(pageMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("uptimeOverview: kontrol kaydı yoksa boş veri, %100 değil null uptime — hata vermez")
    void uptimeOverview_empty() throws Exception {
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of());
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        mvc.perform(get("/api/monitoring/uptime/overview").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("uptimeOverview: http_ok — 24h hep up→true, biri down→false, kayıt yok→null")
    void uptimeOverview_httpOk() throws Exception {
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(lc("a.com", "valid"), lc("b.com", "valid")));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.com"), inv("b.com"), inv("c.com")));
        when(uptimeCheckRepo.findTopByDomainAndPortOrderByIdDesc(anyString(), anyInt())).thenReturn(Optional.empty());
        when(certCheckRepo.findByCheckedAtAfter(anyString())).thenReturn(List.of());
        // http_ok artık domain başına [domain, total, upCount] SQL agregasyonundan gelir (upCount==total).
        when(uptimeCheckRepo.aggregateHttpOkSince(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"a.com", 2L, 2L},    // 2 kontrol, 2 up → http_ok true
                new Object[]{"b.com", 2L, 1L}));  // 2 kontrol, 1 up → http_ok false (c.com yok → null)

        mvc.perform(get("/api/monitoring/uptime/overview").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("a.com"))
                .andExpect(jsonPath("$.data[0].http_ok").value(true))
                .andExpect(jsonPath("$.data[1].domain").value("b.com"))
                .andExpect(jsonPath("$.data[1].http_ok").value(false))
                .andExpect(jsonPath("$.data[2].domain").value("c.com"))
                .andExpect(jsonPath("$.data[2].http_ok").doesNotExist()); // null → kayıt yok
    }

    @Test
    @DisplayName("DELETE /port/{id}: başka takımın monitörü → 403 (canOperateTeam)")
    void deletePort_forbiddenForOtherTeam() throws Exception {
        com.sitemonitor.model.PortMonitor m = new com.sitemonitor.model.PortMonitor();
        m.setId(1L); m.setHost("x"); m.setPort(443); m.setTeamId(999L); m.setActive(true);
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(m));
        mvc.perform(delete("/api/monitoring/port/1").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /port: aynı host:port AKTİF varken → 400 (zaten izleniyor)")
    void createPort_duplicateActive_rejected() throws Exception {
        com.sitemonitor.model.PortMonitor existing = new com.sitemonitor.model.PortMonitor();
        existing.setId(5L); existing.setHost("x.example.com"); existing.setPort(8443); existing.setActive(true);
        when(portMonitorRepo.findFirstByHostAndPortOrderByIdAsc("x.example.com", 8443)).thenReturn(Optional.of(existing));
        mvc.perform(post("/api/monitoring/port").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"x.example.com\",\"port\":8443}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /port: ADMIN yeni host:port ekler → team_id/group_name döner, 200")
    void createPort_admin_success() throws Exception {
        when(portMonitorRepo.findFirstByHostAndPortOrderByIdAsc(anyString(), anyInt())).thenReturn(Optional.empty());
        when(portMonitorRepo.save(any(com.sitemonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.PortMonitor p = a.getArgument(0); p.setId(7L); return p; });
        mvc.perform(post("/api/monitoring/port").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"1.2.3.4\",\"port\":25,\"teamId\":3,\"groupName\":\"mail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.host").value("1.2.3.4"))
                .andExpect(jsonPath("$.data.port").value(25))
                .andExpect(jsonPath("$.data.team_id").value(3))
                .andExpect(jsonPath("$.data.group_name").value("mail"));
    }

    @Test
    @DisplayName("DENETİM: port oluşturma MONITOR_CREATE audit kaydı üretir (kim ne yaptı)")
    void createPort_writesAuditRecord() throws Exception {
        when(portMonitorRepo.findFirstByHostAndPortOrderByIdAsc(anyString(), anyInt())).thenReturn(Optional.empty());
        when(portMonitorRepo.save(any(com.sitemonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.PortMonitor p = a.getArgument(0); p.setId(7L); return p; });
        mvc.perform(post("/api/monitoring/port").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"1.2.3.4\",\"port\":25,\"teamId\":3}"))
                .andExpect(status().isOk());
        // 3. arg String literal → belirsizlik yok (request-overload'ın 3. parametresi HttpServletRequest).
        verify(auditService).recordAction(eq("MONITOR_CREATE"), any(), eq("PORT_MONITOR"), eq("7"), any(), any());
    }

    @Test
    @DisplayName("POST /ping: aynı host+takım zaten varken → 400 (mükerrer engellenir)")
    void createPing_duplicate_rejected() throws Exception {
        when(pingMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(true);
        mvc.perform(post("/api/monitoring/ping").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"x.example.com\",\"teamId\":3}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /ping: mükerrer yoksa ADMIN ekler → 200")
    void createPing_admin_success() throws Exception {
        when(pingMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(pingMonitorRepo.save(any(com.sitemonitor.model.PingMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.PingMonitor p = a.getArgument(0); p.setId(7L); return p; });
        mvc.perform(post("/api/monitoring/ping").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"1.2.3.4\",\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.host").value("1.2.3.4"));
    }

    @Test
    @DisplayName("POST /port: HTTP tipi (küçük harf) normalize edilir + expect/send_data persist")
    void createPort_httpType_persistsCheckConfig() throws Exception {
        when(portMonitorRepo.findFirstByHostAndPortOrderByIdAsc(anyString(), anyInt())).thenReturn(Optional.empty());
        when(portMonitorRepo.save(any(com.sitemonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.PortMonitor p = a.getArgument(0); p.setId(9L); return p; });
        mvc.perform(post("/api/monitoring/port").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"svc.local\",\"port\":8080,\"teamId\":3,\"protocol\":\"http\",\"expect\":\"2xx\",\"sendData\":\"/health\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.protocol").value("HTTP"))
                .andExpect(jsonPath("$.data.expect").value("2xx"))
                .andExpect(jsonPath("$.data.send_data").value("/health"));
    }

    @Test
    @DisplayName("POST /port: confirm/recovery alanları taşınır + standalone=true (ping/keyword alarm paritesi)")
    void createPort_confirmRecovery_standalone() throws Exception {
        when(portMonitorRepo.findFirstByHostAndPortOrderByIdAsc(anyString(), anyInt())).thenReturn(Optional.empty());
        when(portMonitorRepo.save(any(com.sitemonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.PortMonitor p = a.getArgument(0); p.setId(12L); return p; });
        mvc.perform(post("/api/monitoring/port").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"svc.local\",\"port\":9000,\"teamId\":3,\"confirmAttempts\":5,\"recoveryChecks\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirm_attempts").value(5))
                .andExpect(jsonPath("$.data.recovery_checks").value(2))
                .andExpect(jsonPath("$.data.standalone").value(true));
    }

    @Test
    @DisplayName("POST /port/test: kaydetmeden kontrol çalıştırır; sonuç + condition_met döner, kayıt OLUŞMAZ")
    void testPort_runsCheckWithoutSaving() throws Exception {
        when(portChecker.check(eq("svc.local"), eq(8080), anyInt(), eq("HTTP"), any(), eq("2xx"), anyString()))
                .thenReturn(java.util.Map.of("open", true, "response_ms", 12L, "detail", "HTTP 200"));
        mvc.perform(post("/api/monitoring/port/test").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"svc.local\",\"port\":8080,\"protocol\":\"HTTP\",\"expect\":\"2xx\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.open").value(true))
                .andExpect(jsonPath("$.data.condition_met").value(true))
                .andExpect(jsonPath("$.data.detail").value("HTTP 200"))
                .andExpect(jsonPath("$.data.type").value("HTTP"));
        org.mockito.Mockito.verify(portMonitorRepo, org.mockito.Mockito.never())
                .save(any(com.sitemonitor.model.PortMonitor.class));
    }

    @Test
    @DisplayName("POST /keyword/test: canlı koşul testi (occurrences/condition_met/phrase)")
    void testKeyword_returnsResult() throws Exception {
        java.util.Map<String, Object> cr = new java.util.HashMap<>();
        cr.put("count", 5); cr.put("http_status", 200); cr.put("response_ms", 12L);
        when(keywordChecker.check(eq("https://x.example.com"), eq("akbank"), anyInt(), any(), anyBoolean())).thenReturn(cr);

        mvc.perform(post("/api/monitoring/keyword/test").session(session("USER"))
                .contentType("application/json")
                .content("{\"url\":\"https://x.example.com\",\"keyword\":\"akbank\",\"operator\":\"GTE\",\"matchCount\":3,\"timeoutMs\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.occurrences").value(5))
                .andExpect(jsonPath("$.data.condition_met").value(true))   // 5 >= 3
                .andExpect(jsonPath("$.data.phrase").value("en az 3 kez"));
    }

    @Test
    @DisplayName("POST /keyword/test: url/keyword boş → 400")
    void testKeyword_blank_returns400() throws Exception {
        mvc.perform(post("/api/monitoring/keyword/test").session(session("USER"))
                .contentType("application/json").content("{\"url\":\"\",\"keyword\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /keyword/{id}/response-series: kovalar avg/min/max/p95/down döner")
    void keywordResponseSeries_buckets() throws Exception {
        com.sitemonitor.model.KeywordMonitor km = new com.sitemonitor.model.KeywordMonitor(); km.setId(5L);
        when(keywordMonitorRepo.findById(5L)).thenReturn(Optional.of(km));   // IDOR guard artık findById + denyIfNotViewable
        // Aynı saat kovasında 3 kayıt (100/200/300 ms), biri down (ok=false)
        List<Object[]> rows = List.of(
                new Object[]{ "2026-06-24T10:05:00", 100L, true },
                new Object[]{ "2026-06-24T10:25:00", 300L, true },
                new Object[]{ "2026-06-24T10:45:00", 200L, false });
        when(keywordResultRepo.responseSeriesRaw(eq(5L), anyString(), anyString(), anyInt())).thenReturn(rows);

        mvc.perform(get("/api/monitoring/keyword/5/response-series?days=7").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bucket").value("hour"))
                .andExpect(jsonPath("$.data.series[0].count").value(3))
                .andExpect(jsonPath("$.data.series[0].down").value(1))
                .andExpect(jsonPath("$.data.series[0].avg").value(200))
                .andExpect(jsonPath("$.data.series[0].min").value(100))
                .andExpect(jsonPath("$.data.series[0].max").value(300))
                .andExpect(jsonPath("$.data.series[0].p95").value(300));
    }

    @Test
    @DisplayName("GET /scripted: hiç koşmamış monitörde checks/exit/duration anahtarları EKSİK değil NULL (0✓/0✗ yanılgısı)")
    void scriptedList_neverRun_returnsExplicitNullKeys() throws Exception {
        com.sitemonitor.model.ScriptedMonitor m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(3L); m.setName("hic-kosmadi");
        when(scriptedMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(m));
        when(scriptedCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(alertEventRepo.findOpenByDomainIn(any())).thenReturn(List.of());
        when(scriptedChecker.isAvailable()).thenReturn(true);

        mvc.perform(get("/api/monitoring/scripted").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitors[0].status").value("unknown"))
                // Anahtar VAR ve null — frontend '—' gösterir; anahtar eksik olsaydı `?? 0` "0✓/0✗" üretirdi.
                .andExpect(jsonPath("$.data.monitors[0].duration_ms").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.monitors[0].checks_passed").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.monitors[0].checks_failed").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.monitors[0].exit_code").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.monitors[0].error").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("POST /scripted/{id}/diagnose: bacaklar faz kırılımıyla döner; hedef script'ten çıkarılır")
    void scriptedDiagnose_returnsLegsWithPhases() throws Exception {
        // "Java çekiyor, k6 çekmiyor" ayrımını ölçen uç — sahada 288 koşumluk teşhis tıkanıklığı.
        com.sitemonitor.model.ScriptedMonitor m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(31L); m.setName("diag-monitor");
        m.setScript("export default function () { http.get('https://hedef.example/x'); }");
        when(scriptedMonitorRepo.findById(31L)).thenReturn(Optional.of(m));
        when(scriptedChecker.isAvailable()).thenReturn(true);
        when(scriptedChecker.version()).thenReturn("v0.49.0");
        when(scriptedChecker.targetUrls(any())).thenReturn(List.of("https://hedef.example/x"));
        var phases = new com.sitemonitor.service.ScriptedCheckerService.Phases(
                0L, 41L, 0L, 0L, 0L, 0L, 281L, 316L, 1);
        when(scriptedChecker.probe(anyString(), anyBoolean(), anyBoolean())).thenReturn(
                new com.sitemonitor.service.ScriptedCheckerService.ScriptedResult(
                        "FAIL", false, 15000L, 0, null, null, null, null, null, null,
                        "tail", "Request Failed — request timeout", false, phases));

        mvc.perform(post("/api/monitoring/scripted/31/diagnose")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}")
                        .session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://hedef.example/x"))
                // Faz kırılımı bacağın İÇİNDE: teşhisin tamamı buna dayanıyor
                .andExpect(jsonPath("$.data.legs[0].phases.connecting_ms").value(41))
                .andExpect(jsonPath("$.data.legs[0].phases.tls_ms").value(0))
                .andExpect(jsonPath("$.data.legs[0].phases.data_received").value(316))
                .andExpect(jsonPath("$.data.legs[0].status").value("FAIL"));
    }

    @Test
    @DisplayName("POST /scripted/{id}/diagnose: hedef çıkarılamıyor ve verilmediyse 400 (sessiz yanlış sonda YOK)")
    void scriptedDiagnose_noTarget_returns400() throws Exception {
        com.sitemonitor.model.ScriptedMonitor m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(32L); m.setName("hedefsiz");
        m.setScript("export default function () { const u = base + '/x'; http.get(u); }");
        when(scriptedMonitorRepo.findById(32L)).thenReturn(Optional.of(m));
        when(scriptedChecker.isAvailable()).thenReturn(true);
        when(scriptedChecker.targetUrls(any())).thenReturn(List.of());

        mvc.perform(post("/api/monitoring/scripted/32/diagnose")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}")
                        .session(session("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /scripted/{id}/diagnose: k6 yoksa 400 — sonda koşmuş gibi görünmesin")
    void scriptedDiagnose_noK6_returns400() throws Exception {
        com.sitemonitor.model.ScriptedMonitor m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(33L); m.setName("k6siz");
        when(scriptedMonitorRepo.findById(33L)).thenReturn(Optional.of(m));
        when(scriptedChecker.isAvailable()).thenReturn(false);

        mvc.perform(post("/api/monitoring/scripted/33/diagnose")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}")
                        .session(session("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /scripted/{id} rename: açık SCRIPTED_FAIL alarmının domain bağı YENİ ada taşınır")
    void scriptedRename_movesOpenAlarmToNewName() throws Exception {
        com.sitemonitor.model.ScriptedMonitor m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(4L); m.setName("eski-ad");
        when(scriptedMonitorRepo.findById(4L)).thenReturn(Optional.of(m));
        when(scriptedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scriptedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(4L)).thenReturn(Optional.empty());
        com.sitemonitor.model.AlertEvent open = new com.sitemonitor.model.AlertEvent();
        open.setDomain("eski-ad"); open.setAlertType(com.sitemonitor.service.EscalationService.TYPE_SCRIPTED_FAIL);
        when(alertEventRepo.findOpenAlert(eq("eski-ad"), eq(com.sitemonitor.service.EscalationService.TYPE_SCRIPTED_FAIL)))
                .thenReturn(Optional.of(open));
        when(alertEventRepo.findOpenAlert(eq("yeni-ad"), eq(com.sitemonitor.service.EscalationService.TYPE_SCRIPTED_FAIL)))
                .thenReturn(Optional.empty());

        mvc.perform(put("/api/monitoring/scripted/4").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"yeni-ad\"}"))
                .andExpect(status().isOk());

        // Alarm kaydının domain'i yeni ada çekilip kaydedildi (rename'de bağ kopmaz — delete akışının simetriği).
        verify(alertEventRepo).save(org.mockito.ArgumentMatchers.argThat(
                (com.sitemonitor.model.AlertEvent a) -> "yeni-ad".equals(a.getDomain())));
    }

    @Test
    @DisplayName("SÖZLEŞME: TÜM response-series endpoint'leri kova zarfı döner (ham Object[] yasak) + endpoint sayısı kilidi")
    void responseSeries_contract_allEndpoints() throws Exception {
        List<Object[]> raw = List.of(
                new Object[]{ "2026-08-06T10:05:00", 100L, true  },
                new Object[]{ "2026-08-06T10:25:00", 0L,   false });

        var kw = new com.sitemonitor.model.KeywordMonitor();  kw.setId(1L);
        var pg = new com.sitemonitor.model.PingMonitor();     pg.setId(1L);
        var hm = new com.sitemonitor.model.HttpMonitor();     hm.setId(1L);
        var pm = new com.sitemonitor.model.PageMonitor();     pm.setId(1L);
        var sm = new com.sitemonitor.model.ScriptedMonitor(); sm.setId(1L);

        // Tür → mock hazırlığı. YENİ monitör türü eklerken buraya bir satır ekle — aşağıdaki
        // refleksiyon kilidi eklemeyi ZORLAR (2026-08 scripted çökmesi: kopyala-yapıştır ham dönüş).
        java.util.Map<String, Runnable> specs = new java.util.LinkedHashMap<>();
        specs.put("keyword",  () -> { when(keywordMonitorRepo.findById(1L)).thenReturn(Optional.of(kw));
            when(keywordResultRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); });
        specs.put("ping",     () -> { when(pingMonitorRepo.findById(1L)).thenReturn(Optional.of(pg));
            when(pingCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); });
        specs.put("port",     () -> { when(portMonitorRepo.existsById(1L)).thenReturn(true);
            when(portCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); });
        specs.put("dns",      () -> { when(dnsMonitorRepo.existsById(1L)).thenReturn(true);
            when(dnsRecordRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); });
        specs.put("http",     () -> { when(httpMonitorRepo.findById(1L)).thenReturn(Optional.of(hm));
            when(httpCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); });
        specs.put("page",     () -> { when(pageMonitorRepo.findById(1L)).thenReturn(Optional.of(pm));
            when(pageCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); });
        specs.put("scripted", () -> { when(scriptedMonitorRepo.findById(1L)).thenReturn(Optional.of(sm));
            when(scriptedCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); });

        // KİLİT: controller'daki "/response-series" GetMapping sayısı == bu testteki tür sayısı.
        long mappedCount = java.util.Arrays.stream(MonitoringController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(a -> java.util.Arrays.stream(a.value()))
                .filter(p -> p.endsWith("/response-series"))
                .count();
        org.junit.jupiter.api.Assertions.assertEquals(specs.size(), mappedCount,
                "Yeni bir response-series endpoint'i eklendi ama bu sözleşme testine eklenmedi. "
                + "Dönüşü MUTLAKA buildResponseSeries hunisinden geçir ve specs map'ine türünü ekle.");

        for (var e : specs.entrySet()) {
            e.getValue().run();
            mvc.perform(get("/api/monitoring/" + e.getKey() + "/1/response-series?days=7").session(session("ADMIN")))
                    .andExpect(status().isOk())
                    // Zarf alanları var; series elemanı ham DİZİ değil, ts'li OBJE.
                    .andExpect(jsonPath("$.data.bucket").value("hour"))
                    .andExpect(jsonPath("$.data.unit").value("ms"))
                    .andExpect(jsonPath("$.data.series[0].ts").isString())
                    .andExpect(jsonPath("$.data.series[0].count").value(2))
                    .andExpect(jsonPath("$.data.series[0].down").value(1))
                    // (100+0)/2 — down satırın 0 ms süresi de ortalamaya katılır (mevcut davranış).
                    .andExpect(jsonPath("$.data.series[0].avg").value(50));
        }
    }

    @Test
    @DisplayName("GET /scripted/{id}/response-series: ham Object[] DEĞİL, ts alanlı kova zarfı döner (2026-08 chart çökme regresyonu)")
    void scriptedResponseSeries_goesThroughBucketBuilder() throws Exception {
        com.sitemonitor.model.ScriptedMonitor sm = new com.sitemonitor.model.ScriptedMonitor(); sm.setId(7L);
        when(scriptedMonitorRepo.findById(7L)).thenReturn(Optional.of(sm));
        List<Object[]> rows = List.of(
                new Object[]{ "2026-08-06T21:05:00", 0L,   false },   // bug senaryosu: Hata + 0 ms
                new Object[]{ "2026-08-06T21:25:00", 340L, true  });
        when(scriptedCheckRepo.responseSeriesRaw(eq(7L), anyString(), anyString(), anyInt())).thenReturn(rows);

        mvc.perform(get("/api/monitoring/scripted/7/response-series?days=7").session(session("ADMIN")))
                .andExpect(status().isOk())
                // Zarf: bucket/unit/capped var; series elemanları DİZİ değil, ts'li OBJE
                .andExpect(jsonPath("$.data.bucket").value("hour"))
                .andExpect(jsonPath("$.data.unit").value("ms"))
                .andExpect(jsonPath("$.data.capped").value(false))
                .andExpect(jsonPath("$.data.series[0].ts").isString())
                .andExpect(jsonPath("$.data.series[0].count").value(2))
                .andExpect(jsonPath("$.data.series[0].down").value(1))
                .andExpect(jsonPath("$.data.series[0].avg").value(170));
    }

    @Test
    @DisplayName("GET /ping/{id}/response-series: RTT ortalaması + paket kaybı + down")
    void pingResponseSeries_withLoss() throws Exception {
        com.sitemonitor.model.PingMonitor pm = new com.sitemonitor.model.PingMonitor(); pm.setId(9L);
        when(pingMonitorRepo.findById(9L)).thenReturn(Optional.of(pm));   // IDOR guard artık findById + denyIfNotViewable
        List<Object[]> rows = List.of(
                new Object[]{ "2026-06-24T10:05:00", 10L, true,  0 },
                new Object[]{ "2026-06-24T10:25:00", 30L, false, 100 });
        when(pingCheckRepo.responseSeriesRaw(eq(9L), anyString(), anyString(), anyInt())).thenReturn(rows);

        mvc.perform(get("/api/monitoring/ping/9/response-series?days=7").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series[0].down").value(1))
                .andExpect(jsonPath("$.data.series[0].avg").value(20))
                .andExpect(jsonPath("$.data.series[0].loss").value(50));
    }

    @Test
    @DisplayName("GET /port/{id}/response-series: kovalar avg/min/max/p95/down döner (open=up bayrağı)")
    void portResponseSeries_buckets() throws Exception {
        when(portMonitorRepo.existsById(7L)).thenReturn(true);
        // Aynı saat kovasında 3 kayıt (100/200/300 ms), biri down (open=false)
        List<Object[]> rows = List.of(
                new Object[]{ "2026-06-24T10:05:00", 100L, true },
                new Object[]{ "2026-06-24T10:25:00", 300L, true },
                new Object[]{ "2026-06-24T10:45:00", 200L, false });
        when(portCheckRepo.responseSeriesRaw(eq(7L), anyString(), anyString(), anyInt())).thenReturn(rows);

        mvc.perform(get("/api/monitoring/port/7/response-series?days=7").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bucket").value("hour"))
                .andExpect(jsonPath("$.data.series[0].count").value(3))
                .andExpect(jsonPath("$.data.series[0].down").value(1))
                .andExpect(jsonPath("$.data.series[0].avg").value(200))
                .andExpect(jsonPath("$.data.series[0].min").value(100))
                .andExpect(jsonPath("$.data.series[0].max").value(300))
                .andExpect(jsonPath("$.data.series[0].p95").value(300));
    }

    @Test
    @DisplayName("GET /port/{id}/response-series: monitör yok → 404")
    void portResponseSeries_notFound() throws Exception {
        when(portMonitorRepo.existsById(999L)).thenReturn(false);
        mvc.perform(get("/api/monitoring/port/999/response-series?days=7").session(session("USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /dns/{id}/response-series: kovalar avg/down döner (value boş=down, süre null istatistiğe girmez)")
    void dnsResponseSeries_buckets() throws Exception {
        when(dnsMonitorRepo.existsById(3L)).thenReturn(true);
        List<Object[]> rows = List.of(
                new Object[]{ "2026-06-24T10:05:00", 20L, true },
                new Object[]{ "2026-06-24T10:25:00", 60L, true },
                new Object[]{ "2026-06-24T10:45:00", null, false });   // çözümleme başarısız → down, süre null
        when(dnsRecordRepo.responseSeriesRaw(eq(3L), anyString(), anyString(), anyInt())).thenReturn(rows);

        mvc.perform(get("/api/monitoring/dns/3/response-series?days=7").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bucket").value("hour"))
                .andExpect(jsonPath("$.data.series[0].count").value(3))
                .andExpect(jsonPath("$.data.series[0].down").value(1))
                .andExpect(jsonPath("$.data.series[0].avg").value(40));
    }

    @Test
    @DisplayName("POST /keyword: yanıt custom_headers + recovery_checks + recovery_interval_seconds taşır; interval clamp (5→10)")
    void createKeyword_returnsHeadersAndRecoveryFields_withClamp() throws Exception {
        when(keywordMonitorRepo.save(any(com.sitemonitor.model.KeywordMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.KeywordMonitor k = a.getArgument(0); k.setId(11L); return k; });

        mvc.perform(post("/api/monitoring/keyword").session(session("ADMIN"))
                .contentType("application/json")
                .content("{\"url\":\"https://x.example.com\",\"keyword\":\"foo\",\"operator\":\"GTE\",\"matchCount\":1,\"teamId\":3," +
                        "\"customHeaders\":\"Cache-Control: no-cache\",\"recoveryChecks\":4,\"recoveryIntervalSeconds\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.custom_headers").value("Cache-Control: no-cache"))
                .andExpect(jsonPath("$.data.recovery_checks").value(4))
                .andExpect(jsonPath("$.data.recovery_interval_seconds").value(10)); // clampInterval(5) → 10
    }

    @Test
    @DisplayName("PUT /keyword/{id}: recoveryIntervalSeconds üst sınıra clamp (999→600), yanıtta görünür")
    void updateKeyword_clampsRecoveryIntervalHigh() throws Exception {
        com.sitemonitor.model.KeywordMonitor m = new com.sitemonitor.model.KeywordMonitor();
        m.setId(7L); m.setUrl("https://x.example.com"); m.setKeyword("foo"); m.setActive(true);
        when(keywordMonitorRepo.findById(7L)).thenReturn(Optional.of(m));
        when(keywordMonitorRepo.save(any(com.sitemonitor.model.KeywordMonitor.class)))
                .thenAnswer(a -> a.getArgument(0));
        when(keywordResultRepo.findTopByMonitorIdOrderByCheckedAtDesc(7L)).thenReturn(Optional.empty());

        mvc.perform(put("/api/monitoring/keyword/7").session(session("ADMIN"))
                .contentType("application/json")
                .content("{\"recoveryIntervalSeconds\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recovery_interval_seconds").value(600)); // clampInterval(999) → 600
    }

    // ── Keyword mükerrer koruması: aynılık anahtarı url + keyword + takım (ping desenin ikizi) ──

    @Test
    @DisplayName("POST /keyword: aynı URL+kelime+takım zaten varken → 400 (mükerrer engellenir, kayıt oluşmaz)")
    void createKeyword_duplicate_returns400() throws Exception {
        when(keywordMonitorRepo.existsDuplicate(anyString(), anyString(), any(), isNull())).thenReturn(true);

        mvc.perform(post("/api/monitoring/keyword").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://x.example.com\",\"keyword\":\"OK\",\"teamId\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("zaten izleniyor")));

        verify(keywordMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("POST /keyword: aynı URL FARKLI kelime meşrudur → 200 + kayıt oluşur")
    void createKeyword_differentKeywordSameUrl_ok() throws Exception {
        when(keywordMonitorRepo.existsDuplicate(anyString(), anyString(), any(), isNull())).thenReturn(false);
        when(keywordMonitorRepo.save(any(com.sitemonitor.model.KeywordMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.KeywordMonitor k = a.getArgument(0); k.setId(12L); return k; });

        mvc.perform(post("/api/monitoring/keyword").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://x.example.com\",\"keyword\":\"BASKA\",\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://x.example.com"))
                .andExpect(jsonPath("$.data.keyword").value("BASKA"));

        verify(keywordMonitorRepo).save(any(com.sitemonitor.model.KeywordMonitor.class));
    }

    @Test
    @DisplayName("PUT /keyword/{id}: edit ile mükerrere dönüşme → 400 (excludeId=kendisi), kayıt güncellenmez")
    void updateKeyword_wouldBecomeDuplicate_returns400() throws Exception {
        com.sitemonitor.model.KeywordMonitor m = new com.sitemonitor.model.KeywordMonitor();
        m.setId(8L); m.setUrl("https://a.example.com"); m.setKeyword("foo"); m.setActive(true);
        when(keywordMonitorRepo.findById(8L)).thenReturn(Optional.of(m));
        when(keywordMonitorRepo.existsDuplicate(anyString(), anyString(), any(), eq(8L))).thenReturn(true);

        mvc.perform(put("/api/monitoring/keyword/8").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://b.example.com\",\"keyword\":\"foo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("zaten izleniyor")));

        verify(keywordMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("GET /ping: açık PING_DOWN alarmı olan host → active_alarm=true + alarm_level + acknowledged")
    void listPing_withOpenAlarm_marksActiveAlarm() throws Exception {
        com.sitemonitor.model.PingMonitor m = new com.sitemonitor.model.PingMonitor();
        m.setId(3L); m.setName("m"); m.setHost("alarm.example.com"); m.setActive(true);
        when(pingMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(m));
        when(pingCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        com.sitemonitor.model.AlertEvent ev = new com.sitemonitor.model.AlertEvent();
        ev.setDomain("alarm.example.com");
        ev.setAlertType(com.sitemonitor.service.EscalationService.TYPE_PING_DOWN);
        ev.setAlertLevel("CRITICAL"); ev.setAcknowledged(false); ev.setResolved(false);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(ev));

        mvc.perform(get("/api/monitoring/ping").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].host").value("alarm.example.com"))
                .andExpect(jsonPath("$.data[0].active_alarm").value(true))
                .andExpect(jsonPath("$.data[0].alarm_level").value("CRITICAL"))
                .andExpect(jsonPath("$.data[0].alarm_acknowledged").value(false));
    }

    @Test
    @DisplayName("GET /ping: açık alarm yoksa active_alarm=false")
    void listPing_noOpenAlarm_activeAlarmFalse() throws Exception {
        com.sitemonitor.model.PingMonitor m = new com.sitemonitor.model.PingMonitor();
        m.setId(4L); m.setHost("ok.example.com"); m.setActive(true);
        when(pingMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(m));
        when(pingCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/ping").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].active_alarm").value(false));
    }

    @Test
    @DisplayName("GET /keyword: açık KEYWORD alarmı (domain=url) → active_alarm=true; farklı tip alarm sayılmaz")
    void listKeyword_withOpenAlarm_marksActiveAlarm() throws Exception {
        com.sitemonitor.model.KeywordMonitor m = new com.sitemonitor.model.KeywordMonitor();
        m.setId(5L); m.setUrl("https://k.example.com"); m.setKeyword("foo"); m.setActive(true);
        when(keywordMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(m));
        when(keywordResultRepo.findLatestPerMonitor()).thenReturn(List.of());
        com.sitemonitor.model.AlertEvent ev = new com.sitemonitor.model.AlertEvent();
        ev.setDomain("https://k.example.com");
        ev.setAlertType(com.sitemonitor.service.EscalationService.TYPE_KEYWORD);
        ev.setAlertLevel("HIGH"); ev.setAcknowledged(true); ev.setResolved(false);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(ev));

        mvc.perform(get("/api/monitoring/keyword").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].active_alarm").value(true))
                .andExpect(jsonPath("$.data[0].alarm_level").value("HIGH"))
                .andExpect(jsonPath("$.data[0].alarm_acknowledged").value(true));
    }

    // ── /groups endpoint'leri: liste + rename hata eşlemesi (GlobalExceptionHandler) ──

    @Test
    @DisplayName("GET /groups: kapsam-filtreli liste (team_name + type + count) döner")
    void listGroups_returnsScopedList() throws Exception {
        when(monitoringGroupService.listForScope(any(), any(), any())).thenReturn(List.of(
                new com.sitemonitor.service.MonitoringGroupService.GroupInfo(1L, 3L, "SY-A", "dns", "deneme", 4)));
        mvc.perform(get("/api/monitoring/groups").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("deneme"))
                .andExpect(jsonPath("$.data[0].type").value("dns"))
                .andExpect(jsonPath("$.data[0].team_name").value("SY-A"))
                .andExpect(jsonPath("$.data[0].count").value(4));
    }

    @Test
    @DisplayName("PUT /groups/{id}: başarılı rename → affected döner")
    void renameGroup_success_returnsAffected() throws Exception {
        when(monitoringGroupService.rename(eq(5L), eq("yeni"), any())).thenReturn(7);
        mvc.perform(put("/api/monitoring/groups/5").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"new_name\":\"yeni\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected").value(7));
    }

    @Test
    @DisplayName("PUT /groups/{id}: aynı takım+türde ad çakışması (IllegalStateException) → 409")
    void renameGroup_conflict_returns409() throws Exception {
        when(monitoringGroupService.rename(anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("zaten var"));
        mvc.perform(put("/api/monitoring/groups/5").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"new_name\":\"taken\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PUT /groups/{id}: başka takımın grubu (SecurityException) → 403")
    void renameGroup_otherTeam_returns403() throws Exception {
        when(monitoringGroupService.rename(anyLong(), anyString(), any()))
                .thenThrow(new SecurityException("yetki yok"));
        mvc.perform(put("/api/monitoring/groups/5").session(session("USER"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"new_name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /groups/{id}: boş yeni ad (IllegalArgumentException) → 400")
    void renameGroup_blank_returns400() throws Exception {
        when(monitoringGroupService.rename(anyLong(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("boş olamaz"));
        mvc.perform(put("/api/monitoring/groups/5").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"new_name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── BUG1 regresyon kilidi: teamsiz create → 400 (global admin bypass'ı kaldırıldı) ──
    @Test
    @DisplayName("POST create: teamId'siz → 400 global admin dahil (http/ping/keyword/domain) + kayıt oluşmaz")
    void teamlessCreate_rejected400_forFixedTypes() throws Exception {
        when(publicSuffixService.registrableDomain(anyString())).thenReturn("example.org");
        var admin = session("ADMIN");   // global admin: viewTeamIds set edilmemiş → isGlobalAdmin=true (eski bypass senaryosu)
        mvc.perform(post("/api/monitoring/http").session(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://x.example.com\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/monitoring/ping").session(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"1.2.3.4\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/monitoring/keyword").session(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://x.example.com\",\"keyword\":\"akbank\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/monitoring/domain").session(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"domain\":\"example.org\"}"))
                .andExpect(status().isBadRequest());
        // Takımsız hiçbir kayıt oluşmamalı.
        verify(httpMonitorRepo, never()).save(any());
        verify(pingMonitorRepo, never()).save(any());
        verify(keywordMonitorRepo, never()).save(any());
        verify(domainMonitorRepo, never()).save(any());
    }

    // ── İsim normalizasyonu: URL yapıştırılmış isimler host'a iner, serbest metin korunur ──
    @Test
    @DisplayName("normalizeMonitorName: URL → host; serbest metin dokunulmaz; trim uygulanır")
    void normalizeMonitorName_urlToHost_freeTextKept() {
        org.assertj.core.api.Assertions.assertThat(
                MonitoringController.normalizeMonitorName("https://www.wingscard.com.tr/"))
                .isEqualTo("www.wingscard.com.tr");
        org.assertj.core.api.Assertions.assertThat(
                MonitoringController.normalizeMonitorName("http://x.example.com/path?q=1"))
                .isEqualTo("x.example.com");
        org.assertj.core.api.Assertions.assertThat(
                MonitoringController.normalizeMonitorName("  Wings Kart Sitesi  "))
                .isEqualTo("Wings Kart Sitesi");
        org.assertj.core.api.Assertions.assertThat(
                MonitoringController.normalizeMonitorName(null)).isNull();
    }

    // ── Kopyala (duplicate) sadakati: pasif bir izlemenin kopyası da PASİF doğmalı ──
    // Eskiden create uçları active'i yok sayıp her zaman true yazıyordu; duraklatılmış
    // monitörün kopyası anında kontrole/alarma giriyordu (sessiz sapma).

    @Test
    @DisplayName("POST /http: gövdedeki active:false saygı görür (Kopyala: pasif kaynağın kopyası pasif doğar)")
    void createHttp_activeFalse_respected() throws Exception {
        when(httpMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.HttpMonitor h = a.getArgument(0); h.setId(31L); return h; });
        mvc.perform(post("/api/monitoring/http").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://kopya.example.com\",\"teamId\":3,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        org.mockito.ArgumentCaptor<com.sitemonitor.model.HttpMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.HttpMonitor.class);
        verify(httpMonitorRepo).save(cap.capture());
        org.assertj.core.api.Assertions.assertThat(cap.getValue().getActive()).isFalse();
    }

    @Test
    @DisplayName("POST /http: active gönderilmezse varsayılan AKTİF kalır (regresyon koruması)")
    void createHttp_activeOmitted_defaultsTrue() throws Exception {
        when(httpMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.HttpMonitor h = a.getArgument(0); h.setId(32L); return h; });
        mvc.perform(post("/api/monitoring/http").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://yeni.example.com\",\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @DisplayName("POST /ping: gövdedeki active:false saygı görür")
    void createPing_activeFalse_respected() throws Exception {
        when(pingMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(pingMonitorRepo.save(any(com.sitemonitor.model.PingMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.PingMonitor p = a.getArgument(0); p.setId(33L); return p; });
        mvc.perform(post("/api/monitoring/ping").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"10.0.0.9\",\"teamId\":3,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    @DisplayName("POST /scripted: gövdedeki active:false saygı görür")
    void createScripted_activeFalse_respected() throws Exception {
        when(scriptedMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(scriptedMonitorRepo.save(any(com.sitemonitor.model.ScriptedMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.ScriptedMonitor s = a.getArgument(0); s.setId(34L); return s; });
        mvc.perform(post("/api/monitoring/scripted").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"OIDC Login (Kopya)\",\"teamId\":3,\"groupName\":\"Senaryolar\","
                        + "\"script\":\"export default function(){}\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    @DisplayName("POST /port: gövdedeki active:false saygı görür")
    void createPort_activeFalse_respected() throws Exception {
        when(portMonitorRepo.findFirstByHostAndPortOrderByIdAsc(anyString(), anyInt())).thenReturn(Optional.empty());
        when(portMonitorRepo.save(any(com.sitemonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.PortMonitor p = a.getArgument(0); p.setId(35L); return p; });
        mvc.perform(post("/api/monitoring/port").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"svc.local\",\"port\":9090,\"teamId\":3,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    @DisplayName("POST /dns: aynı (domain, kayıt tipi) standalone varken → 400; sessiz no-op YOK, kayıt oluşmaz")
    void createDns_duplicateDomainRecordType_returns400() throws Exception {
        com.sitemonitor.model.DnsMonitor existing = new com.sitemonitor.model.DnsMonitor();
        existing.setId(8L); existing.setDomain("www.akbank.com"); existing.setRecordType("A"); existing.setStandalone(true);
        when(dnsMonitorRepo.findFirstByDomainAndRecordTypeAndStandaloneTrue("www.akbank.com", "A"))
                .thenReturn(Optional.of(existing));

        mvc.perform(post("/api/monitoring/dns").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"domain\":\"www.akbank.com\",\"recordType\":\"A\",\"teamId\":3,\"name\":\"akbank (Kopya)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("zaten bir izleme var")));

        verify(dnsMonitorRepo, never()).save(any());
    }

    // ── Şemasız URL sahte alarmı (2026-08-04) — giriş normalizasyonu ──
    // "www.axess.com.tr" kaydedilebiliyordu; kontrol motoru host çıkaramadığı için sonuç DOWN oluyor
    // ve takıma KRİTİK "sayfa yüklenemiyor" e-postası gidiyordu. Artık girişte https:// ekleniyor.

    @Test
    @DisplayName("POST /page: şemasız URL https:// ile normalize edilerek kaydedilir")
    void createPage_schemalessUrl_normalizedToHttps() throws Exception {
        when(pageMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(pageMonitorRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageMonitor m = i.getArgument(0); m.setId(41L); return m; });

        mvc.perform(post("/api/monitoring/page").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"  www.axess.com.tr  \",\"teamId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://www.axess.com.tr"));

        org.mockito.ArgumentCaptor<com.sitemonitor.model.PageMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PageMonitor.class);
        verify(pageMonitorRepo).save(cap.capture());
        org.assertj.core.api.Assertions.assertThat(cap.getValue().getUrl()).isEqualTo("https://www.axess.com.tr");
    }

    @Test
    @DisplayName("POST /page: mükerrer kontrolü NORMALİZE edilmiş URL ile yapılır → 400, kayıt yok")
    void createPage_duplicateAfterNormalize_returns400() throws Exception {
        when(pageMonitorRepo.existsDuplicate(eq("https://www.axess.com.tr"), any(), any())).thenReturn(true);
        mvc.perform(post("/api/monitoring/page").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"www.axess.com.tr\",\"teamId\":1}"))
                .andExpect(status().isBadRequest());
        verify(pageMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("POST /page: host'suz URL (https://) → 400 'geçerli bir host yok', kayıt yok")
    void createPage_hostlessUrl_returns400() throws Exception {
        mvc.perform(post("/api/monitoring/page").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://\",\"teamId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("host yok")));
        verify(pageMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("POST /http: şemasız URL normalize edilir; http:// tercihi KORUNUR")
    void createHttp_schemalessNormalized_httpSchemeKept() throws Exception {
        when(httpMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.HttpMonitor h = a.getArgument(0); h.setId(42L); return h; });

        mvc.perform(post("/api/monitoring/http").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"www.axess.com.tr\",\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://www.axess.com.tr"));

        // İç servis 443'te olmayabilir → kullanıcının açık http:// tercihi asla https'e taşınmaz.
        mvc.perform(post("/api/monitoring/http").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://internal.host:8080/health\",\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("http://internal.host:8080/health"));
    }

    @Test
    @DisplayName("POST /keyword: şemasız URL normalize edilir; {timestamp} yer tutucusu bozulmaz")
    void createKeyword_schemalessNormalized_timestampSurvives() throws Exception {
        when(keywordMonitorRepo.existsDuplicate(anyString(), anyString(), any(), any())).thenReturn(false);
        when(keywordMonitorRepo.save(any(com.sitemonitor.model.KeywordMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.KeywordMonitor k = a.getArgument(0); k.setId(43L); return k; });

        mvc.perform(post("/api/monitoring/keyword").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"  x.example.com/a?t={timestamp}  \",\"keyword\":\"akbank\",\"teamId\":3}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.KeywordMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.KeywordMonitor.class);
        verify(keywordMonitorRepo).save(cap.capture());
        // Eskiden ham (trim'siz) kaydediliyordu; URI tabanlı doğrulama da {timestamp}'te patlardı.
        org.assertj.core.api.Assertions.assertThat(cap.getValue().getUrl())
                .isEqualTo("https://x.example.com/a?t={timestamp}");
    }

    @Test
    @DisplayName("POST /page/test: checker'a NORMALİZE edilmiş URL gider (ad-hoc test de sahte hata vermez)")
    void testPage_normalizesBeforeChecker() throws Exception {
        when(pageChecker.test(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                new com.sitemonitor.service.PageCheckerService.PageCheckResult(
                        "OK", true, 200, 12L, 3, 0, 0, 0, 1, null, null, null, List.of()));
        mvc.perform(post("/api/monitoring/page/test").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"www.axess.com.tr\"}"))
                .andExpect(status().isOk());
        verify(pageChecker).test(eq("https://www.axess.com.tr"), org.mockito.ArgumentMatchers.anyInt());
    }

    // ═══════════ Kontrol Geçmişi v2 — kontrat + izolasyon + clamp + CSV ═══════════
    // Zarf: items/page/size/total/counts/range/buckets/alerts. Gerçek CheckHistoryService (@Import) koşar;
    // yeni bir izleme türü history eklerken bu kontrat setine kayıt EKLENMELİDİR.

    private static <T> org.springframework.data.domain.Page<T> histPage(List<T> items, long total) {
        return new org.springframework.data.domain.PageImpl<>(items,
                org.springframework.data.domain.PageRequest.of(0, 50), total);
    }

    /** 10 endpoint'in tamamı için page/count/histogram stub'ları — hepsi boş ama geçerli veri döner. */
    private void stubAllHistoryRepos() {
        when(portCheckRepo.findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(portCheckRepo.findByMonitorIdAndOpenFalseAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(pingCheckRepo.findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(pingCheckRepo.findByMonitorIdAndUpFalseAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(keywordResultRepo.findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(keywordResultRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(httpCheckRepo.findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(httpCheckRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(pageCheckRepo.findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(pageCheckRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(scriptedCheckRepo.findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(scriptedCheckRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(domainCheckRepo.findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(domainCheckRepo.findByMonitorIdAndStatusNotAndCheckedAtBetween(anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(dnsRecordRepo.findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(dnsRecordRepo.findChangedByMonitorIdBetween(anyLong(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetween(anyString(), anyInt(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(uptimeCheckRepo.findByDomainAndPortAndStatusNotAndCheckedAtBetween(anyString(), anyInt(), anyString(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(certCheckRepo.findByDomainAndCheckedAtBetween(anyString(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        when(certCheckRepo.findByDomainAndStatusAndCheckedAtBetween(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(histPage(List.of(), 0));
        // count/histogram stub'ları — long dönüşleri Mockito default 0; histogramlar boş liste
        when(portCheckRepo.historyHistogram(anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(pingCheckRepo.historyHistogram(anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(keywordResultRepo.historyHistogram(anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(httpCheckRepo.historyHistogram(anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(pageCheckRepo.historyHistogram(anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(scriptedCheckRepo.historyHistogram(anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(domainCheckRepo.historyHistogram(anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(dnsRecordRepo.historyHistogram(anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(uptimeCheckRepo.historyHistogram(anyString(), anyInt(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(certCheckRepo.historyHistogram(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(alertEventRepo.findOverlappingForHistory(anyString(), any(), anyString(), anyString())).thenReturn(List.of());
    }

    /** Monitör stub'ları — hepsi teamId=null (global ADMIN görür); anahtar alanlar dolu (alarm sorgusu koşsun). */
    private void stubAllHistoryMonitors() {
        var port = new com.sitemonitor.model.PortMonitor(); port.setHost("h1"); when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(port));
        var ping = new com.sitemonitor.model.PingMonitor(); ping.setHost("h2"); when(pingMonitorRepo.findById(1L)).thenReturn(Optional.of(ping));
        var kw = new com.sitemonitor.model.KeywordMonitor(); kw.setUrl("https://k"); when(keywordMonitorRepo.findById(1L)).thenReturn(Optional.of(kw));
        var http = new com.sitemonitor.model.HttpMonitor(); http.setUrl("https://h"); when(httpMonitorRepo.findById(1L)).thenReturn(Optional.of(http));
        var pg = new com.sitemonitor.model.PageMonitor(); pg.setUrl("https://p"); when(pageMonitorRepo.findById(1L)).thenReturn(Optional.of(pg));
        var sc = new com.sitemonitor.model.ScriptedMonitor(); sc.setName("s1"); when(scriptedMonitorRepo.findById(1L)).thenReturn(Optional.of(sc));
        var dom = new com.sitemonitor.model.DomainMonitor(); dom.setDomain("d.com"); when(domainMonitorRepo.findById(1L)).thenReturn(Optional.of(dom));
        var dns = new com.sitemonitor.model.DnsMonitor(); dns.setDomain("d.com"); when(dnsMonitorRepo.findById(1L)).thenReturn(Optional.of(dns));
        when(inventoryRepo.findByDomain("a.com")).thenReturn(Optional.of(inv("a.com")));
    }

    private static final String[] HISTORY_URLS = {
            "/api/monitoring/port/1/history", "/api/monitoring/ping/1/history",
            "/api/monitoring/keyword/1/history", "/api/monitoring/http/1/history",
            "/api/monitoring/page/1/history", "/api/monitoring/scripted/1/history",
            "/api/monitoring/domain/1/history", "/api/monitoring/dns/1/history",
            "/api/monitoring/uptime/a.com/http-history", "/api/monitoring/uptime/a.com/ssl-history"
    };

    @Test
    @DisplayName("Kontrol Geçmişi v2 kontratı: 10 endpoint de tek tip zarf döner (days sugar dahil) — 500 literal'i öldü")
    void history_contract_allEndpoints() throws Exception {
        stubAllHistoryMonitors();
        stubAllHistoryRepos();
        for (String url : HISTORY_URLS) {
            mvc.perform(get(url).param("days", "7").session(session("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(50))
                    .andExpect(jsonPath("$.data.total").isNumber())
                    .andExpect(jsonPath("$.data.counts.total").isNumber())
                    .andExpect(jsonPath("$.data.counts.fail").isNumber())
                    .andExpect(jsonPath("$.data.range.from").isString())
                    .andExpect(jsonPath("$.data.range.to").isString())
                    .andExpect(jsonPath("$.data.buckets").isArray())
                    .andExpect(jsonPath("$.data.alerts").isArray());
        }
    }

    @Test
    @DisplayName("status=fail: hata-filtreli sorgu koşar (tam liste sorgusu değil) ve total filtreli sayıdır")
    void history_statusFail_usesFailQueryAndFilteredTotal() throws Exception {
        stubAllHistoryMonitors();
        stubAllHistoryRepos();
        var failRow = new com.sitemonitor.model.PingCheck();
        failRow.setMonitorId(1L); failRow.setUp(false); failRow.setCheckedAt("2026-08-07T10:00:00");
        // PageImpl, offset+pageSize > total durumunda total'ı content.size()'a kırpar — tutarlı stub: 1 satır/1 toplam.
        when(pingCheckRepo.findByMonitorIdAndUpFalseAndCheckedAtBetween(anyLong(), anyString(), anyString(), any()))
                .thenReturn(histPage(List.of(failRow), 1));
        when(pingCheckRepo.countByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString())).thenReturn(120L);
        when(pingCheckRepo.countByMonitorIdAndUpFalseAndCheckedAtBetween(anyLong(), anyString(), anyString())).thenReturn(1L);

        mvc.perform(get("/api/monitoring/ping/1/history").param("days", "7").param("status", "fail")
                        .session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))          // FİLTRELİ toplam — sayfalama bununla
                .andExpect(jsonPath("$.data.counts.total").value(120)) // chip'ler yine TÜM aralığı gösterir
                .andExpect(jsonPath("$.data.counts.fail").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        verify(pingCheckRepo, never()).findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Takım izolasyonu: kapsam dışı takım → 403 (uptime http/ssl DAHİL — eskiden session bile yoktu)")
    void history_teamIsolation_deniesOutOfScope() throws Exception {
        var port = new com.sitemonitor.model.PortMonitor(); port.setHost("h1"); port.setTeamId(2L);
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(port));
        CertificateInventory i = inv("a.com"); i.setTeamId(2L);
        when(inventoryRepo.findByDomain("a.com")).thenReturn(Optional.of(i));

        MockHttpSession scoped = session("USER");
        scoped.setAttribute("viewTeamIds", List.of(1L));   // yalnız takım 1'i görür

        mvc.perform(get("/api/monitoring/port/1/history").param("days", "1").session(scoped))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/uptime/a.com/http-history").param("days", "1").session(scoped))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/uptime/a.com/ssl-history").param("days", "1").session(scoped))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Retention clamp: DNS'te 90 günden eski from istenirse range.from kırpılmış döner (sessiz kırpma yok)")
    void history_retentionClamp_dns() throws Exception {
        stubAllHistoryMonitors();
        stubAllHistoryRepos();
        String minFrom = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now().minus(90, java.time.temporal.ChronoUnit.DAYS));

        var res = mvc.perform(get("/api/monitoring/dns/1/history")
                        .param("from", "2020-01-01").param("to", "2026-08-07")
                        .session(session("ADMIN")))
                .andExpect(status().isOk())
                .andReturn();
        String from = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.range.from");
        org.assertj.core.api.Assertions.assertThat(from).isGreaterThanOrEqualTo(minFrom.substring(0, 10));
    }

    @Test
    @DisplayName("Domain hata sayacı DB count'tan gelir — 500'lük kesik listeden sayma bug'ı regresyon kilidi")
    void history_domainFailCount_fromDbCount() throws Exception {
        stubAllHistoryMonitors();
        stubAllHistoryRepos();
        when(domainCheckRepo.countByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString())).thenReturn(700L);
        when(domainCheckRepo.countByMonitorIdAndStatusNotAndCheckedAtBetween(anyLong(), anyString(), anyString(), anyString())).thenReturn(650L);
        mvc.perform(get("/api/monitoring/domain/1/history").param("days", "365").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.total").value(700))
                .andExpect(jsonPath("$.data.counts.fail").value(650));   // 500'de kesilmiyor
    }

    @Test
    @DisplayName("days=custom (veya çöp değer) 500 DEĞİL 200 döner — sıkı Integer bağlama regresyonu (2026-08)")
    void history_daysNonNumeric_toleratedNot500() throws Exception {
        stubAllHistoryMonitors();
        stubAllHistoryRepos();
        // "Özel Aralık" seçilip tarih uygulanmadan gelen istek (ve eski ?range=custom linkleri)
        // days=custom gönderiyordu → MethodArgumentTypeMismatch → 500. Artık sugar sessizce atlanır.
        for (String bad : new String[]{ "custom", "abc", "" }) {
            mvc.perform(get("/api/monitoring/ping/1/history").param("days", bad).session(session("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.range.from").isString());
        }
    }

    @Test
    @DisplayName("format=csv: text/csv attachment akar, başlık satırı kolonları taşır")
    void history_csv_streamsAttachment() throws Exception {
        stubAllHistoryMonitors();
        stubAllHistoryRepos();
        var row = new com.sitemonitor.model.PingCheck();
        row.setMonitorId(1L); row.setUp(true); row.setRttMs(12L); row.setCheckedAt("2026-08-07T10:00:00");
        when(pingCheckRepo.findByMonitorIdAndCheckedAtBetween(anyLong(), anyString(), anyString(), any()))
                .thenReturn(histPage(List.of(row), 1));

        mvc.perform(get("/api/monitoring/ping/1/history")
                        .param("days", "1").param("format", "csv").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("checked_at")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-08-07T10:00:00")));
    }
}
