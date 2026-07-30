package com.certmonitor.controller;

import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.model.UptimeCheck;
import com.certmonitor.repository.*;
import com.certmonitor.service.CertificateService;
import com.certmonitor.service.DnsCheckerService;
import com.certmonitor.service.PortCheckerService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
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
class MonitoringControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.certmonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean com.certmonitor.service.ActivityLogService activityLog;
    @MockitoBean com.certmonitor.service.AuditService auditService;

    @MockitoBean LatestCheckRepository latestCheckRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean CertificateCheckRepository certCheckRepo;
    @MockitoBean UptimeCheckRepository uptimeCheckRepo;
    @MockitoBean com.certmonitor.service.MonitoringGroupService monitoringGroupService;
    @MockitoBean PortMonitorRepository portMonitorRepo;
    @MockitoBean PortCheckRepository portCheckRepo;
    @MockitoBean PortCheckerService portChecker;
    @MockitoBean DnsMonitorRepository dnsMonitorRepo;
    @MockitoBean DnsRecordRepository dnsRecordRepo;
    @MockitoBean DnsCheckerService dnsChecker;
    @MockitoBean CertificateService certificateService;
    @MockitoBean com.certmonitor.service.PermissionService permissionService;

    @MockitoBean KeywordMonitorRepository keywordMonitorRepo;
    @MockitoBean KeywordResultRepository keywordResultRepo;
    @MockitoBean com.certmonitor.service.KeywordCheckerService keywordChecker;
    @MockitoBean PingMonitorRepository pingMonitorRepo;
    @MockitoBean PingCheckRepository pingCheckRepo;
    @MockitoBean com.certmonitor.service.PingCheckerService pingChecker;
    @MockitoBean HttpMonitorRepository httpMonitorRepo;
    @MockitoBean HttpCheckRepository httpCheckRepo;
    @MockitoBean com.certmonitor.service.HttpCheckerService httpChecker;
    @MockitoBean DomainMonitorRepository domainMonitorRepo;
    @MockitoBean DomainCheckRepository domainCheckRepo;
    @MockitoBean com.certmonitor.service.DomainCheckerService domainChecker;
    @MockitoBean com.certmonitor.service.PublicSuffixService publicSuffixService;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean com.certmonitor.service.EscalationService escalationService;
    @MockitoBean com.certmonitor.service.AppSettingsService appSettings;
    @MockitoBean AlertEventRepository alertEventRepo;

    // 9. tür (sayfa-bütünlüğü) — controller alan-enjekte eder → @WebMvcTest slice'ında mock zorunlu.
    @MockitoBean com.certmonitor.service.SchedulerService schedulerService;
    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean PageCheckRepository pageCheckRepo;
    @MockitoBean PageResourceIssueRepository pageResourceIssueRepo;
    @MockitoBean com.certmonitor.service.PageCheckerService pageChecker;

    @BeforeEach
    void stubTeamMap() {
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
        com.certmonitor.model.AlertEvent slow = openDnsEvent("a.com", com.certmonitor.service.EscalationService.TYPE_DNS_SLOW, "HIGH");
        com.certmonitor.model.AlertEvent fail = openDnsEvent("a.com", com.certmonitor.service.EscalationService.TYPE_DNS_FAILURE, "CRITICAL");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(slow, fail));

        mvc.perform(get("/api/monitoring/dns").session(session("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].active_alarm").value(true))
                .andExpect(jsonPath("$.data[0].alarm_level").value("CRITICAL"));   // SLOW+FAILURE → en severe
    }

    private static com.certmonitor.model.AlertEvent openDnsEvent(String domain, String type, String level) {
        com.certmonitor.model.AlertEvent e = new com.certmonitor.model.AlertEvent();
        e.setDomain(domain); e.setAlertType(type); e.setAlertLevel(level);
        e.setResolved(false); e.setAcknowledged(false);
        return e;
    }

    private MockHttpSession sessionWithTeam(String role, Long teamId) {
        MockHttpSession s = session(role);
        s.setAttribute("teamId", teamId);
        return s;
    }

    private static com.certmonitor.model.PageMonitor pageMon(Long id, String url, Long teamId) {
        com.certmonitor.model.PageMonitor m = new com.certmonitor.model.PageMonitor();
        m.setId(id); m.setName(url); m.setUrl(url); m.setTeamId(teamId); m.setActive(true);
        return m;
    }

    // ── 9. tür: Sayfa Bütünlüğü ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /page: açık PAGE_DOWN alarmı → active_alarm=true + seviye; kontrolsüz monitör status=unknown")
    void listPage_marksAlarm() throws Exception {
        when(pageCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(pageMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(pageMon(1L, "https://x.com", 1L)));
        com.certmonitor.model.AlertEvent down = openDnsEvent("https://x.com",
                com.certmonitor.service.EscalationService.TYPE_PAGE_DOWN, "CRITICAL");
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
        when(appSettings.getInt(eq("cert.monitor.page.manual-cooldown-seconds"), anyInt())).thenReturn(20);
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
            com.certmonitor.model.PageMonitor m = i.getArgument(0); m.setId(1L); return m; });

        mvc.perform(post("/api/monitoring/page").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.com\",\"teamId\":1,\"alertMixedContent\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alert_mixed_content").value(false))
                .andExpect(jsonPath("$.data.alert_third_party").value(false));   // varsayılan false
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
        com.certmonitor.model.PortMonitor m = new com.certmonitor.model.PortMonitor();
        m.setId(1L); m.setHost("x"); m.setPort(443); m.setTeamId(999L); m.setActive(true);
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(m));
        mvc.perform(delete("/api/monitoring/port/1").session(session("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /port: aynı host:port AKTİF varken → 400 (zaten izleniyor)")
    void createPort_duplicateActive_rejected() throws Exception {
        com.certmonitor.model.PortMonitor existing = new com.certmonitor.model.PortMonitor();
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
        when(portMonitorRepo.save(any(com.certmonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.certmonitor.model.PortMonitor p = a.getArgument(0); p.setId(7L); return p; });
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
        when(portMonitorRepo.save(any(com.certmonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.certmonitor.model.PortMonitor p = a.getArgument(0); p.setId(7L); return p; });
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
        when(pingMonitorRepo.save(any(com.certmonitor.model.PingMonitor.class)))
                .thenAnswer(a -> { com.certmonitor.model.PingMonitor p = a.getArgument(0); p.setId(7L); return p; });
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
        when(portMonitorRepo.save(any(com.certmonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.certmonitor.model.PortMonitor p = a.getArgument(0); p.setId(9L); return p; });
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
        when(portMonitorRepo.save(any(com.certmonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.certmonitor.model.PortMonitor p = a.getArgument(0); p.setId(12L); return p; });
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
                .save(any(com.certmonitor.model.PortMonitor.class));
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
        com.certmonitor.model.KeywordMonitor km = new com.certmonitor.model.KeywordMonitor(); km.setId(5L);
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
    @DisplayName("GET /ping/{id}/response-series: RTT ortalaması + paket kaybı + down")
    void pingResponseSeries_withLoss() throws Exception {
        com.certmonitor.model.PingMonitor pm = new com.certmonitor.model.PingMonitor(); pm.setId(9L);
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
        when(keywordMonitorRepo.save(any(com.certmonitor.model.KeywordMonitor.class)))
                .thenAnswer(a -> { com.certmonitor.model.KeywordMonitor k = a.getArgument(0); k.setId(11L); return k; });

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
        com.certmonitor.model.KeywordMonitor m = new com.certmonitor.model.KeywordMonitor();
        m.setId(7L); m.setUrl("https://x.example.com"); m.setKeyword("foo"); m.setActive(true);
        when(keywordMonitorRepo.findById(7L)).thenReturn(Optional.of(m));
        when(keywordMonitorRepo.save(any(com.certmonitor.model.KeywordMonitor.class)))
                .thenAnswer(a -> a.getArgument(0));
        when(keywordResultRepo.findTopByMonitorIdOrderByCheckedAtDesc(7L)).thenReturn(Optional.empty());

        mvc.perform(put("/api/monitoring/keyword/7").session(session("ADMIN"))
                .contentType("application/json")
                .content("{\"recoveryIntervalSeconds\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recovery_interval_seconds").value(600)); // clampInterval(999) → 600
    }

    @Test
    @DisplayName("GET /ping: açık PING_DOWN alarmı olan host → active_alarm=true + alarm_level + acknowledged")
    void listPing_withOpenAlarm_marksActiveAlarm() throws Exception {
        com.certmonitor.model.PingMonitor m = new com.certmonitor.model.PingMonitor();
        m.setId(3L); m.setName("m"); m.setHost("alarm.example.com"); m.setActive(true);
        when(pingMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(m));
        when(pingCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        com.certmonitor.model.AlertEvent ev = new com.certmonitor.model.AlertEvent();
        ev.setDomain("alarm.example.com");
        ev.setAlertType(com.certmonitor.service.EscalationService.TYPE_PING_DOWN);
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
        com.certmonitor.model.PingMonitor m = new com.certmonitor.model.PingMonitor();
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
        com.certmonitor.model.KeywordMonitor m = new com.certmonitor.model.KeywordMonitor();
        m.setId(5L); m.setUrl("https://k.example.com"); m.setKeyword("foo"); m.setActive(true);
        when(keywordMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(m));
        when(keywordResultRepo.findLatestPerMonitor()).thenReturn(List.of());
        com.certmonitor.model.AlertEvent ev = new com.certmonitor.model.AlertEvent();
        ev.setDomain("https://k.example.com");
        ev.setAlertType(com.certmonitor.service.EscalationService.TYPE_KEYWORD);
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
                new com.certmonitor.service.MonitoringGroupService.GroupInfo(1L, 3L, "SY-A", "dns", "deneme", 4)));
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
}
