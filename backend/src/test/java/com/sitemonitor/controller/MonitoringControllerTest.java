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
import static org.assertj.core.api.Assertions.assertThat;
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
    @MockitoBean com.sitemonitor.service.MonitorHistoryService monitorHistory;
    @MockitoBean com.sitemonitor.repository.MonitorChangeLogRepository changeLogRepo;

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
    @MockitoBean com.sitemonitor.service.DomainExpiryReminderService domainReminders;   // hatırlatmalar (2026-09-22, E)
    @MockitoBean com.sitemonitor.service.DomainRenewalPlanService domainRenewalPlans;   // yenileme planı (2026-09-22, H)
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
    @MockitoBean com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedMonitorRepo;
    @MockitoBean com.sitemonitor.repository.PageSpeedCheckRepository pageSpeedCheckRepo;
    @MockitoBean com.sitemonitor.repository.PageSpeedResourceRepository pageSpeedResourceRepo;
    @MockitoBean com.sitemonitor.service.PageSpeedCheckerService pageSpeedChecker;
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
    /** Bildirim gruplari: dilim baglami icin gerekli; stub YOK -> "hic grup yok" (birinci yasa). */
    @MockitoBean com.sitemonitor.repository.NotificationGroupRepository notificationGroupRepo;

    @BeforeEach
    void stubTeamMap() {
        // Senaryo kaydetme yolu artık kaydetmeden önce script doğrulaması çağırıyor; mock varsayılanı
        // null döner ve NPE'ye yol açar. Zararsız (engellemeyen, uyarısız) bir sonuç stub'la.
        // ÜÇ argümanlı aşırı yükleme: süreç bütçesi (timeoutSeconds) de geçiliyor — ters bütçe
        // uyarısı ("istek timeout'u ≥ süreç bütçesi") ancak bu bilinirse üretilebiliyor.
        org.mockito.Mockito.lenient().when(scriptedChecker.validateScript(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.sitemonitor.service.ScriptedCheckerService.ScriptDiagnostics(null, java.util.List.of()));
        // Retention mock'u stub'sız 0 döner; geçmiş VE seri uçları "from"u saklama penceresine kırptığı
        // için 0 gün, istenen aralığı sıfıra indirip kova genişliğini bozardı. Gerçek varsayılan: 180.
        org.mockito.Mockito.lenient().when(retentionService.historyRetentionDays(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(180);
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
    @DisplayName("A6: 2000 kontrolde 1 hata → %100 DEGIL 99.95 (kesinti varken asla 100 gosterilmez)")
    void uptimeOverview_singleFailureInLargeSample_neverShows100() throws Exception {
        // Eski formul 1 ondalikla yuvarliyordu ve korumasizdi: up*1000/total = 999.5 →
        // Math.round → 1000 → 100.0. Sonuc, AYNI satirda `uptime_30d: 100.0` ve
        // `incidents_30d: 1` — kendi kendisiyle celisen bir satir; haftalik rapor ise ayni
        // monitor icin 99.95 diyordu. Formul artik kardes yuzeyle (WeeklyAvailabilityReportService)
        // birebir ayni: 2 ondalik + "hic down ornegi varsa asla 100" korumasi.
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(lc("a.com", "valid")));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.com")));
        when(certCheckRepo.aggregateStatusCountsSince(anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"a.com", 2000L, 1L}));

        mvc.perform(get("/api/monitoring/uptime/overview").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].uptime_30d").value(99.95))
                .andExpect(jsonPath("$.data[0].incidents_30d").value(1));
    }

    @Test
    @DisplayName("A6: yuvarlama 100'e cikarsa 99.99'a kirpilir; GERCEKTEN hatasizsa 100.0 kalir")
    void uptimeOverview_clampsRoundedHundred_butKeepsTrue100() throws Exception {
        // 20000/1: 2 ondalikta bile 99.995 → yuvarlama 100.00 verir. Koruma burada devreye girer.
        when(latestCheckRepo.findAllByOrderByDomainAsc())
                .thenReturn(List.of(lc("a.com", "valid"), lc("b.com", "valid")));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc())
                .thenReturn(List.of(inv("a.com"), inv("b.com")));
        when(certCheckRepo.aggregateStatusCountsSince(anyString()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"a.com", 20000L, 1L},     // yuvarlama 100 → kirpilir
                        new Object[]{"b.com", 500L, 0L}));     // gercekten hatasiz → 100.0

        mvc.perform(get("/api/monitoring/uptime/overview").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].uptime_30d").value(99.99))
                .andExpect(jsonPath("$.data[1].uptime_30d").value(100.0));
    }

    @Test
    @DisplayName("uptimeOverview: uptime% domain-bazlı toplu sorgudan (4 toplam/1 hata → %75), lc'siz domain → 'unknown'")
    void uptimeOverview_computesUptimeAndHandlesMissingCheck() throws Exception {
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(lc("a.com", "valid")));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.com"), inv("b.com")));
        // P1/N1: artık domain başına findByCheckedAtAfter (tüm tablo) yerine tek gruplu sorgu.
        when(certCheckRepo.aggregateStatusCountsSince(anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"a.com", 4L, 1L}));

        // ADMIN (global gorus): envanter satirlari artik takim kapsamina TABI (IDOR kapisi) ve
        // bu fixture'daki inv(...) takimsiz. Testin konusu uptime/http_ok hesabi; kapsam
        // ayri testte pinli (uptimeOverview_hidesOtherTeamsInventory).
        mvc.perform(get("/api/monitoring/uptime/overview").session(session("ADMIN")))
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

    /**
     * ELLE TETIKLEME de degisiklik tespitini son BASARILI kayda karsi yapmali.
     *
     * <p>Kullanici bildirimi: kayit saatlerdir ayni IP'ye cozumleniyordu, bir tur basarisiz oldu
     * (deger "" yazildi), sonraki basarili elle kontrol AYNI IP'yi dondurdu ve satir "DEGISTI!"
     * damgasi yedi. Sebep: bu uc son kaydi (basarisizlar DAHIL) aliyordu; bos kume ile dolu kume
     * AYRIK gorunuyor, ayriklik da CHANGED demek.
     *
     * <p>SchedulerService bu duzeltmeyi zaten tasiyordu (SchedulerServiceTest'te pinli); ikiz olan
     * elle tetikleme yolu guncellenmemisti -- duzeltmenin cagri yerlerinden birine uygulanmamasi
     * sinifi. Bu test o ikizi pinler.
     */
    @Test
    @DisplayName("POST /dns/{id}/check: son BASARILI kayda karsi karsilastirir — cozumleme kurtulusu sahte DEGISTI uretmez")
    void triggerDns_comparesAgainstLastSuccessfulRecord() throws Exception {
        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(7L); m.setDomain("x.example.com"); m.setRecordType("A");
        when(dnsMonitorRepo.findById(7L)).thenReturn(java.util.Optional.of(m));

        // Son BASARILI kayit ayni IP'yi tasiyor; arada bir basarisiz ("" degerli) tur var.
        com.sitemonitor.model.DnsRecord prevOk = new com.sitemonitor.model.DnsRecord();
        prevOk.setValue("217.169.192.122");
        when(dnsRecordRepo.findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(7L, ""))
                .thenReturn(java.util.Optional.of(prevOk));
        when(dnsChecker.check("x.example.com", "A")).thenReturn(
                java.util.Map.of("success", true, "values", List.of("217.169.192.122"),
                                 "ttl", 300L, "response_ms", 12L));

        mvc.perform(post("/api/monitoring/dns/7/check").session(session("ADMIN")))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.DnsRecord> rec =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.DnsRecord.class);
        verify(dnsRecordRepo).save(rec.capture());
        assertThat(rec.getValue().getChanged()).isFalse();
        assertThat(rec.getValue().getRotated()).isFalse();
        assertThat(rec.getValue().getPreviousValue()).isEqualTo("217.169.192.122");
        // Son kayit (basarisizlar dahil) sorgusu ARTIK KULLANILMAZ.
        verify(dnsRecordRepo, never()).findTopByMonitorIdOrderByCheckedAtDesc(anyLong());
    }

    @Test
    @DisplayName("POST /dns/{id}/check: basarisiz sorguda degisiklik HIC hesaplanmaz")
    void triggerDns_failureEmitsNoChange() throws Exception {
        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(8L); m.setDomain("y.example.com"); m.setRecordType("A");
        when(dnsMonitorRepo.findById(8L)).thenReturn(java.util.Optional.of(m));
        when(dnsChecker.check("y.example.com", "A"))
                .thenReturn(java.util.Map.of("success", false, "error", "no answer"));

        mvc.perform(post("/api/monitoring/dns/8/check").session(session("ADMIN")))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.DnsRecord> rec =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.DnsRecord.class);
        verify(dnsRecordRepo).save(rec.capture());
        assertThat(rec.getValue().getChanged()).isFalse();
        // Basarisiz turda son-basarili-kayit lookup'i bile yapilmaz.
        verify(dnsRecordRepo, never())
                .findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(anyLong(), anyString());
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

        // ADMIN (global gorus): envanter satirlari artik takim kapsamina tabi ve bu fixture'da
        // inv("a.com") takimsiz. Testin konusu ALARM ROZETI; kapsam ayri testlerde pinli.
        mvc.perform(get("/api/monitoring/dns").session(session("ADMIN")))
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

    /** Y1: bu uç oturum parametresi bile taşımadan dönüyordu — canlı DNS sorgusu dahil. */
    @Test
    @DisplayName("Y1 IDOR: GET /dns/{id}/details başka takımda 403 ve canlı DNS sorgusu HİÇ koşmaz")
    void dnsDetails_foreignTeam_forbidden() throws Exception {
        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(9L); m.setDomain("x.example.com"); m.setRecordType("A"); m.setTeamId(2L); m.setActive(true);
        when(dnsMonitorRepo.findById(9L)).thenReturn(Optional.of(m));
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));

        mvc.perform(get("/api/monitoring/dns/9/details").session(s))
                .andExpect(status().isForbidden());

        // Sızıntının asıl bedeli her çağrıda CANLI sorguydu — reddedilen istekte hiç koşmamalı.
        org.mockito.Mockito.verify(dnsChecker, org.mockito.Mockito.never()).enrichedQuery(anyString());
    }

    /** Y2: izin (domain.registration.view) USER'a açık ama kaynak TAKIMA ait. */
    @Test
    @DisplayName("Y2 IDOR: GET /domain/{id}/registration başka takımda 404 (varlık sızdırmaz)")
    void domainRegistration_foreignTeam_notFound() throws Exception {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(9L); m.setDomain("x.example.com"); m.setTeamId(2L); m.setActive(true);
        when(domainMonitorRepo.findById(9L)).thenReturn(Optional.of(m));
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));

        mvc.perform(get("/api/monitoring/domain/9/registration").session(s))
                .andExpect(status().isNotFound());
    }

    /** Y2b: live=true SALT OKUMA DEĞİL (dış sorgu + persist + alarm) — görüntüleme yetkisi yetmez. */
    @Test
    @DisplayName("Y2 IDOR: registration?live=true görüntüleyebilen ama YÖNETEMEYEN kullanıcıya 403")
    void domainRegistrationLive_viewOnlyUser_forbidden() throws Exception {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(9L); m.setDomain("x.example.com"); m.setTeamId(2L); m.setActive(true);
        when(domainMonitorRepo.findById(9L)).thenReturn(Optional.of(m));
        // Takım 2'yi GÖREBİLİYOR (view scope) ama üyesi/yöneticisi değil.
        MockHttpSession s = sessionWithTeam("USER", 1L);
        s.setAttribute("viewTeamIds", java.util.List.of(1L, 2L));

        mvc.perform(get("/api/monitoring/domain/9/registration?live=true").session(s))
                .andExpect(status().isForbidden());
    }

    /** Y3: kardeş 7 uç denyIfNotViewable taşırken port/dns existsById ile desenden sapmıştı. */
    @Test
    @DisplayName("Y3 IDOR: GET /port/{id}/response-series başka takımda 403")
    void portResponseSeries_foreignTeam_forbidden() throws Exception {
        com.sitemonitor.model.PortMonitor m = new com.sitemonitor.model.PortMonitor();
        m.setId(9L); m.setHost("x"); m.setPort(443); m.setTeamId(2L); m.setActive(true);
        when(portMonitorRepo.findById(9L)).thenReturn(Optional.of(m));
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));

        mvc.perform(get("/api/monitoring/port/9/response-series").session(s))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Y3 IDOR: GET /dns/{id}/response-series başka takımda 403")
    void dnsResponseSeries_foreignTeam_forbidden() throws Exception {
        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(9L); m.setDomain("x.example.com"); m.setRecordType("A"); m.setTeamId(2L); m.setActive(true);
        when(dnsMonitorRepo.findById(9L)).thenReturn(Optional.of(m));
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));

        mvc.perform(get("/api/monitoring/dns/9/response-series").session(s))
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
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://x.com\",\"teamId\":1,\"alertMixedContent\":false}"))
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
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://x.com\",\"teamId\":1,\"alertTimeout\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alert_timeout").value(false))
                .andExpect(jsonPath("$.data.alert_mixed_content").value(true));  // varsayılan true
    }

    @Test
    @DisplayName("POST /page: takımsız kullanıcı takım çözemez → 400 (takım zorunlu), kayıt yok")
    void createPage_requiresTeam() throws Exception {
        mvc.perform(post("/api/monitoring/page").session(session("USER"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://x.com\"}"))
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

        // ADMIN (global gorus): envanter satirlari artik takim kapsamina TABI (IDOR kapisi) ve
        // bu fixture'daki inv(...) takimsiz. Testin konusu uptime/http_ok hesabi; kapsam
        // ayri testte pinli (uptimeOverview_hidesOtherTeamsInventory).
        mvc.perform(get("/api/monitoring/uptime/overview").session(session("ADMIN")))
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
        when(portMonitorRepo.existsByHostAndPortAndActiveTrue("x.example.com", 8443)).thenReturn(true);
        mvc.perform(post("/api/monitoring/port").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"x.example.com\",\"port\":8443}"))
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
                .content("{\"host\":\"1.2.3.4\",\"port\":25,\"teamId\":3,\"groupName\":\"mail\",\"tags\":\"t1\"}"))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"1.2.3.4\",\"port\":25,\"teamId\":3}"))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"x.example.com\",\"teamId\":3}"))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"1.2.3.4\",\"teamId\":3}"))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"svc.local\",\"port\":8080,\"teamId\":3,\"protocol\":\"http\",\"expect\":\"2xx\",\"sendData\":\"/health\"}"))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"svc.local\",\"port\":9000,\"teamId\":3,\"confirmAttempts\":5,\"recoveryChecks\":2}"))
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
        when(keywordChecker.check(eq("https://x.example.com"), eq("example"), anyInt(), any(), anyBoolean(), anyBoolean())).thenReturn(cr);   // viaProxy (2026-09-21)

        mvc.perform(post("/api/monitoring/keyword/test").session(session("USER"))
                .contentType("application/json")
                .content("{\"url\":\"https://x.example.com\",\"keyword\":\"example\",\"operator\":\"GTE\",\"matchCount\":3,\"timeoutMs\":5000}"))
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
                0L, 41L, 0L, 0L, 0L, 0L, 281L, 316L, 1, 1L);
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

    // ── A10: bozuk env_json SESSIZCE sir silmemeli ─────────────────────────────
    //
    // buildEnvJson'daki `catch (Exception ignored)` yutulunca existingSecrets bos kaliyor ve
    // "deger degismedi" dali getOrDefault(name, "") ile BOS DIZE yaziyordu. Kullanici monitorun
    // yalnizca ADINI degistirip kaydettiginde (form sir alanlarini bos getirir — yazma-yalniz
    // sozlesmesi) tum secret env degiskenleri kalici olarak "" oluyordu: hicbir hata, hicbir log,
    // k6 kosumu bir sonraki turda kimlik dogrulama hatasiyla dusuyor ve sebebi gorunmuyordu.

    @Test
    @DisplayName("A10: env_json BOZUKKEN bos gelen sir, sessizce silinmez — 409 ve kayit YAZILMAZ")
    void scriptedUpdate_corruptEnvJson_refusesInsteadOfBlankingSecret() throws Exception {
        com.sitemonitor.model.ScriptedMonitor m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(51L); m.setName("bozuk-env");
        m.setEnvJson("{bu gecerli JSON degil");          // yarim yazma / elle SQL duzeltmesi
        when(scriptedMonitorRepo.findById(51L)).thenReturn(Optional.of(m));
        when(scriptedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(51L)).thenReturn(Optional.empty());

        // Form sir alanini BOS getirir = "bu degeri degistirmedim" → eski enc korunmali.
        mvc.perform(put("/api/monitoring/scripted/51").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"env\":[{\"name\":\"TOKEN\",\"secret\":true,\"value\":\"\"}]}"))
                .andExpect(status().isConflict());

        // En onemlisi: bozuk kayit UZERINE yazilmadi — sir hala kurtarilabilir durumda.
        verify(scriptedMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("A10: env_json bozuk olsa da DEGER verilen sir kaydedilir (gereksiz yere engellenmez)")
    void scriptedUpdate_corruptEnvJson_stillSavesWhenValueSupplied() throws Exception {
        // Korunacak bir sey yoksa istek reddedilmemeli: kullanici degeri yeniden girerek
        // bozuk satiri ONARABILMELI.
        com.sitemonitor.model.ScriptedMonitor m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(52L); m.setName("bozuk-env-2");
        m.setEnvJson("{bu gecerli JSON degil");
        when(scriptedMonitorRepo.findById(52L)).thenReturn(Optional.of(m));
        when(scriptedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scriptedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(52L)).thenReturn(Optional.empty());
        when(secretCipher.encrypt(anyString())).thenReturn("ENC(yeni)");

        mvc.perform(put("/api/monitoring/scripted/52").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"env\":[{\"name\":\"TOKEN\",\"secret\":true,\"value\":\"yeni-token\"}]}"))
                .andExpect(status().isOk());

        // updateScripted save'i IKI kez cagirir (surum defteri sonrasi yeniden kaydeder) —
        // burada onemli olan kaydedilen env'in yeni sifreli degeri tasimasi.
        verify(scriptedMonitorRepo, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(
                sm -> sm.getEnvJson() != null && sm.getEnvJson().contains("ENC(yeni)")));
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
        var psm = new com.sitemonitor.model.PageSpeedMonitor(); psm.setId(1L);
        // [checkedAt, responseMs, ttfbMs, totalBytes, requestCount, ok]
        List<Object[]> rawPageSpeed = List.of(
                new Object[]{ "2026-08-06T10:05:00", 100, 30, 2048L, 12, true  },
                new Object[]{ "2026-08-06T10:25:00", 0,   0,  0L,    0,  false });

        // Tür → (mock hazırlığı, URL). YENİ monitör türü eklerken buraya bir satır ekle — aşağıdaki
        // refleksiyon kilidi eklemeyi ZORLAR (2026-08 scripted çökmesi: kopyala-yapıştır ham dönüş).
        // URL de spec'te taşınıyor: sertifika serisi domain-anahtarlı ("/uptime/{domain}/ssl/response-series"),
        // diğerleri id-anahtarlı → tek kalıba sığmıyor.
        // expectedAvg: kova ortalamasi. Varsayilan 50 = (100+0)/2, yani BASARISIZ kontrolun
        // 0 ms'i de ortalamaya katilir (tum turlerde mevcut davranis). Sayfa Hizi bilincli
        // olarak AYRILIR — asagida gerekcesiyle.
        record Spec(Runnable setup, String url, int expectedAvg) {
            Spec(Runnable setup, String url) { this(setup, url, 50); }
        }
        java.util.Map<String, Spec> specs = new java.util.LinkedHashMap<>();
        specs.put("keyword",  new Spec(() -> { when(keywordMonitorRepo.findById(1L)).thenReturn(Optional.of(kw));
            when(keywordResultRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); },
            "/api/monitoring/keyword/1/response-series"));
        specs.put("ping",     new Spec(() -> { when(pingMonitorRepo.findById(1L)).thenReturn(Optional.of(pg));
            when(pingCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); },
            "/api/monitoring/ping/1/response-series"));
        com.sitemonitor.model.PortMonitor pom = new com.sitemonitor.model.PortMonitor();
        pom.setId(1L); pom.setHost("x"); pom.setPort(443); pom.setTeamId(1L); pom.setActive(true);
        com.sitemonitor.model.DnsMonitor dmo = new com.sitemonitor.model.DnsMonitor();
        dmo.setId(1L); dmo.setDomain("x.example.com"); dmo.setRecordType("A"); dmo.setTeamId(1L); dmo.setActive(true);
        specs.put("port",     new Spec(() -> { when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(pom));
            when(portCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); },
            "/api/monitoring/port/1/response-series"));
        specs.put("dns",      new Spec(() -> { when(dnsMonitorRepo.findById(1L)).thenReturn(Optional.of(dmo));
            when(dnsRecordRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); },
            "/api/monitoring/dns/1/response-series"));
        specs.put("http",     new Spec(() -> { when(httpMonitorRepo.findById(1L)).thenReturn(Optional.of(hm));
            when(httpCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); },
            "/api/monitoring/http/1/response-series"));
        specs.put("page",     new Spec(() -> { when(pageMonitorRepo.findById(1L)).thenReturn(Optional.of(pm));
            when(pageCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); },
            "/api/monitoring/page/1/response-series"));
        specs.put("scripted", new Spec(() -> { when(scriptedMonitorRepo.findById(1L)).thenReturn(Optional.of(sm));
            when(scriptedCheckRepo.responseSeriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(raw); },
            "/api/monitoring/scripted/1/response-series"));
        // Sayfa hızı serisi TEK sorguda dört metrik döner ([ts, load, ttfb, bytes, requests, ok]) ve
        // controller istenen kolonu projekte eder → ham satır 6 elemanlı, zarf yine aynı huniden geçer.
        // Sayfa Hizi: BASARISIZ olcumun degeri seriye HIC girmez (null gecilir) → ortalama 100,
        // 50 degil. Alinamayan bir sayfa 0 bayt / 0 istek olarak kaydediliyor; onlari cizmek
        // grafigi asagi cekip "sayfa hafifledi" gibi okuturdu. Kesinti AYRI kirmizi isaretle
        // gosterildigi icin bilgi kaybolmuyor (down sayaci asagida yine 1).
        specs.put("pagespeed", new Spec(() -> { when(pageSpeedMonitorRepo.findById(1L)).thenReturn(Optional.of(psm));
            when(pageSpeedCheckRepo.seriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(rawPageSpeed); },
            "/api/monitoring/pagespeed/1/response-series", 100));
        specs.put("ssl",      new Spec(() -> { when(inventoryRepo.findByDomain("a.com")).thenReturn(Optional.of(inv("a.com")));
            when(certCheckRepo.responseSeriesRaw(eq("a.com"), anyString(), anyString(), anyInt())).thenReturn(raw); },
            "/api/monitoring/uptime/a.com/ssl/response-series"));

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
            e.getValue().setup().run();
            mvc.perform(get(e.getValue().url() + "?days=7").session(session("ADMIN")))
                    .andExpect(status().isOk())
                    // Zarf alanları var; series elemanı ham DİZİ değil, ts'li OBJE.
                    .andExpect(jsonPath("$.data.bucket").value("hour"))
                    .andExpect(jsonPath("$.data.unit").value("ms"))
                    .andExpect(jsonPath("$.data.series[0].ts").isString())
                    .andExpect(jsonPath("$.data.series[0].count").value(2))
                    .andExpect(jsonPath("$.data.series[0].down").value(1))
                    .andExpect(jsonPath("$.data.series[0].avg").value(e.getValue().expectedAvg()));
        }
    }

    // ── Sertifika yanıt süresi serisi (dashboard kartı → Grafik sekmesi) ──────────

    @Test
    @DisplayName("IDOR + DoS: /uptime/{domain}/history yabancı takımda 403; hours 90 güne kırpılır")
    void uptimeHistory_scopedAndClamped() throws Exception {
        CertificateInventory foreign = inv("a.com");
        foreign.setTeamId(2L);
        when(inventoryRepo.findByDomain("a.com")).thenReturn(Optional.of(foreign));
        MockHttpSession scoped = session("USER");
        scoped.setAttribute("viewTeamIds", List.of(1L));

        // Eskiden imzada HttpSession bile yoktu → başka takımın geçmişi serbestçe okunuyordu.
        mvc.perform(get("/api/monitoring/uptime/a.com/history").param("hours", "24").session(scoped))
                .andExpect(status().isForbidden());

        // hours sınırsızdı: 200000 saat → saat başına tüm listeyi tarayan döngüde 10⁹ karşılaştırma.
        // Kırpma sonrası yanıt 90 günü (2160 çubuk) aşmamalı.
        when(certCheckRepo.findByDomainAndDateRange(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        mvc.perform(get("/api/monitoring/uptime/a.com/history").param("hours", "200000").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bars.length()").value(24 * 90));
    }

    @Test
    @DisplayName("GET /uptime/{domain}/ssl/response-series: ms serisinin YANINDA kalan gün (days) yardımcı serisi döner")
    void sslResponseSeries_carriesDaysAuxSeries() throws Exception {
        when(inventoryRepo.findByDomain("a.com")).thenReturn(Optional.of(inv("a.com")));
        // [checkedAt, responseMs, up, daysRemaining] — 4. kolon yardımcı seri.
        when(certCheckRepo.responseSeriesRaw(eq("a.com"), anyString(), anyString(), anyInt())).thenReturn(List.of(
                new Object[]{ "2026-08-06T10:05:00", 120L, true, 40 },
                new Object[]{ "2026-08-06T10:25:00", 180L, true, 40 }));

        mvc.perform(get("/api/monitoring/uptime/a.com/ssl/response-series?days=7").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unit").value("ms"))              // ana seri süre
                .andExpect(jsonPath("$.data.series[0].avg").value(150))      // (120+180)/2
                .andExpect(jsonPath("$.data.series[0].days").value(40))      // yardımcı seri
                .andExpect(jsonPath("$.data.series[0].loss").doesNotExist()); // ping'e özel alan sızmaz
    }

    @Test
    @DisplayName("GET /uptime/{domain}/ssl/response-series: ms kolonu boşken bile kalan gün serisi döner (yeni kolon senaryosu)")
    void sslResponseSeries_worksWithoutMsData() throws Exception {
        when(inventoryRepo.findByDomain("a.com")).thenReturn(Optional.of(inv("a.com")));
        // Tek elemanlı List.of(Object[]) varargs olarak açılır → tip parametresi açıkça verilir.
        when(certCheckRepo.responseSeriesRaw(eq("a.com"), anyString(), anyString(), anyInt())).thenReturn(
                List.<Object[]>of(new Object[]{ "2026-08-06T10:05:00", null, true, 33 }));

        mvc.perform(get("/api/monitoring/uptime/a.com/ssl/response-series?days=7").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series[0].avg").doesNotExist())   // ms yok → istatistik null
                .andExpect(jsonPath("$.data.series[0].days").value(33));
    }

    @Test
    @DisplayName("GET /uptime/{domain}/ssl/response-series: envanterde olmayan domain → 404")
    void sslResponseSeries_unknownDomain_returns404() throws Exception {
        when(inventoryRepo.findByDomain("yok.com")).thenReturn(Optional.empty());

        mvc.perform(get("/api/monitoring/uptime/yok.com/ssl/response-series").session(session("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("IDOR: /uptime/{domain}/ssl/response-series başka takımın domain'inde 403 (geçmiş ucuyla aynı denetim)")
    void sslResponseSeries_foreignTeam_returns403() throws Exception {
        CertificateInventory foreign = inv("a.com");
        foreign.setTeamId(2L);
        when(inventoryRepo.findByDomain("a.com")).thenReturn(Optional.of(foreign));
        MockHttpSession scoped = session("USER");
        scoped.setAttribute("viewTeamIds", List.of(1L));   // yalnız takım 1'i görür

        mvc.perform(get("/api/monitoring/uptime/a.com/ssl/response-series").session(scoped))
                .andExpect(status().isForbidden());
        // Aynı oturum geçmiş ucunda da 403 alıyor → iki uç aynı denetimi paylaşıyor.
        mvc.perform(get("/api/monitoring/uptime/a.com/ssl-history").param("days", "1").session(scoped))
                .andExpect(status().isForbidden());
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
        // Y3: uç artık monitörü yüklüyor (takım kapsamı için) — stub findById'ye geçti.
        com.sitemonitor.model.PortMonitor pm7 = new com.sitemonitor.model.PortMonitor();
        pm7.setId(7L); pm7.setHost("x"); pm7.setPort(443); pm7.setTeamId(1L); pm7.setActive(true);
        when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(pm7));
        // Aynı saat kovasında 3 kayıt (100/200/300 ms), biri down (open=false)
        List<Object[]> rows = List.of(
                new Object[]{ "2026-06-24T10:05:00", 100L, true },
                new Object[]{ "2026-06-24T10:25:00", 300L, true },
                new Object[]{ "2026-06-24T10:45:00", 200L, false });
        when(portCheckRepo.responseSeriesRaw(eq(7L), anyString(), anyString(), anyInt())).thenReturn(rows);

        mvc.perform(get("/api/monitoring/port/7/response-series?days=7").session(session("ADMIN")))
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
        // Y3: uç artık monitörü yüklüyor (takım kapsamı için) — stub findById'ye geçti.
        com.sitemonitor.model.DnsMonitor dm3 = new com.sitemonitor.model.DnsMonitor();
        dm3.setId(3L); dm3.setDomain("x.example.com"); dm3.setRecordType("A"); dm3.setTeamId(1L); dm3.setActive(true);
        when(dnsMonitorRepo.findById(3L)).thenReturn(Optional.of(dm3));
        List<Object[]> rows = List.of(
                new Object[]{ "2026-06-24T10:05:00", 20L, true },
                new Object[]{ "2026-06-24T10:25:00", 60L, true },
                new Object[]{ "2026-06-24T10:45:00", null, false });   // çözümleme başarısız → down, süre null
        when(dnsRecordRepo.responseSeriesRaw(eq(3L), anyString(), anyString(), anyInt())).thenReturn(rows);

        mvc.perform(get("/api/monitoring/dns/3/response-series?days=7").session(session("ADMIN")))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://x.example.com\",\"keyword\":\"foo\",\"operator\":\"GTE\",\"matchCount\":1,\"teamId\":3," +
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

    // ── Bildirim grubu YAŞAM DÖNGÜSÜ: silinmiş grup vs başka takımın grubu ──

    private com.sitemonitor.model.KeywordMonitor keywordWithGroup() {
        com.sitemonitor.model.KeywordMonitor m = new com.sitemonitor.model.KeywordMonitor();
        m.setId(7L); m.setUrl("https://x.example.com"); m.setKeyword("foo"); m.setActive(true);
        m.setTeamId(5L); m.setNotificationGroupId(2L);
        when(keywordMonitorRepo.findById(7L)).thenReturn(Optional.of(m));
        when(keywordMonitorRepo.save(any(com.sitemonitor.model.KeywordMonitor.class)))
                .thenAnswer(a -> a.getArgument(0));
        when(keywordResultRepo.findTopByMonitorIdOrderByCheckedAtDesc(7L)).thenReturn(Optional.empty());
        return m;
    }

    private com.sitemonitor.model.NotificationGroup grp(long id, long teamId, boolean active) {
        com.sitemonitor.model.NotificationGroup g = new com.sitemonitor.model.NotificationGroup();
        g.setId(id); g.setTeamId(teamId); g.setActive(active); g.setName("G" + id);
        return g;
    }

    /**
     * Grup SİLİNDİĞİNDE onu kullanan monitörler kalır ve formda "(silinmiş)" rozetiyle görünür.
     * Form her kaydetmede anahtarı GÖNDERDİĞİ için, silinmiş grubu reddetmek o monitörlerin
     * BÜTÜN düzenlemelerini kilitlerdi — interval değiştirmek bile imkânsızlaşırdı. Üstelik
     * "bu takıma ait değil" mesajı gerçek dışıydı: grup o takımın, yalnızca silinmiş.
     */
    @Test
    @DisplayName("PUT: SİLİNMİŞ grup düzenlemeyi ENGELLEMEZ — takım varsayılanına düşer")
    void updateKeyword_deletedGroup_fallsBackInsteadOfBlocking() throws Exception {
        keywordWithGroup();
        when(notificationGroupRepo.findById(2L)).thenReturn(Optional.of(grp(2L, 5L, false)));

        mvc.perform(put("/api/monitoring/keyword/7").session(session("ADMIN"))
                        .contentType("application/json")
                        .content("{\"teamId\":5,\"notificationGroupId\":2,\"recoveryChecks\":3}"))
                .andExpect(status().isOk())
                // Zincirin kalanı işler: null = takım varsayılanı → Team.email
                .andExpect(jsonPath("$.data.notification_group_id").doesNotExist());
    }

    @Test
    @DisplayName("PUT: BAŞKA takımın grubu HÂLÂ reddedilir (400) — bu bir yönlendirme sızıntısı olurdu")
    void updateKeyword_foreignGroup_stillRejected() throws Exception {
        keywordWithGroup();
        when(notificationGroupRepo.findById(9L)).thenReturn(Optional.of(grp(9L, 42L, true)));

        mvc.perform(put("/api/monitoring/keyword/7").session(session("ADMIN"))
                        .contentType("application/json")
                        .content("{\"teamId\":5,\"notificationGroupId\":9,\"recoveryChecks\":3}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT: AKTİF ve kendi takımının grubu normal şekilde kaydedilir")
    void updateKeyword_activeOwnGroup_persists() throws Exception {
        keywordWithGroup();
        when(notificationGroupRepo.findById(2L)).thenReturn(Optional.of(grp(2L, 5L, true)));

        mvc.perform(put("/api/monitoring/keyword/7").session(session("ADMIN"))
                        .contentType("application/json")
                        .content("{\"teamId\":5,\"notificationGroupId\":2,\"recoveryChecks\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notification_group_id").value(2));
    }

    // ── Keyword mükerrer koruması: aynılık anahtarı url + keyword + takım (ping desenin ikizi) ──

    @Test
    @DisplayName("POST /keyword: aynı URL+kelime+takım zaten varken → 400 (mükerrer engellenir, kayıt oluşmaz)")
    void createKeyword_duplicate_returns400() throws Exception {
        when(keywordMonitorRepo.existsDuplicate(anyString(), anyString(), any(), isNull())).thenReturn(true);

        mvc.perform(post("/api/monitoring/keyword").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://x.example.com\",\"keyword\":\"OK\",\"teamId\":3}"))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://x.example.com\",\"keyword\":\"BASKA\",\"teamId\":3}"))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://x.example.com\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/monitoring/ping").session(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"1.2.3.4\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/monitoring/keyword").session(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://x.example.com\",\"keyword\":\"example\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/monitoring/domain").session(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"example.org\"}"))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://kopya.example.com\",\"teamId\":3,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        org.mockito.ArgumentCaptor<com.sitemonitor.model.HttpMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.HttpMonitor.class);
        verify(httpMonitorRepo).save(cap.capture());
        org.assertj.core.api.Assertions.assertThat(cap.getValue().getActive()).isFalse();
    }

    @Test
    @DisplayName("POST /http: useProxy ON/OFF/AUTO saklanır, bilinmeyen değer AUTO'ya düşer; liste use_proxy + proxy_effective taşır (vekil bileşeni yoksa direct)")
    void createHttp_useProxyPersistedAndEnriched() throws Exception {
        when(httpMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.HttpMonitor h = a.getArgument(0); h.setId(32L); return h; });
        mvc.perform(post("/api/monitoring/http").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://vekil.example.com\",\"teamId\":3,\"useProxy\":\"on\"}"))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<com.sitemonitor.model.HttpMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.HttpMonitor.class);
        verify(httpMonitorRepo).save(cap.capture());
        org.assertj.core.api.Assertions.assertThat(cap.getValue().getUseProxy()).isEqualTo("ON");

        // bilinmeyen değer → AUTO (sentetik izlemeyle aynı kural)
        org.assertj.core.api.Assertions.assertThat(com.sitemonitor.service.ProxyPolicyService.normalizeMode("maybe")).isEqualTo("AUTO");

        // liste: vekil alanları (test bağlamında ProxyPolicyService bean'i yok → doğrudan, kaynak none)
        com.sitemonitor.model.HttpMonitor hm = cap.getValue(); hm.setTeamId(3L);
        when(httpMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(hm));
        mvc.perform(get("/api/monitoring/http").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].use_proxy").value("ON"))
                .andExpect(jsonPath("$.data[0].proxy_effective").value("direct"))
                .andExpect(jsonPath("$.data[0].proxy_source").value("none"));
    }

    @Test
    @DisplayName("POST /http: active gönderilmezse varsayılan AKTİF kalır (regresyon koruması)")
    void createHttp_activeOmitted_defaultsTrue() throws Exception {
        when(httpMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.HttpMonitor h = a.getArgument(0); h.setId(32L); return h; });
        mvc.perform(post("/api/monitoring/http").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://yeni.example.com\",\"teamId\":3}"))
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"10.0.0.9\",\"teamId\":3,\"active\":false}"))
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
                .content("{\"name\":\"OIDC Login (Kopya)\",\"teamId\":3,\"groupName\":\"Senaryolar\",\"tags\":\"t1\","
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
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"svc.local\",\"port\":9090,\"teamId\":3,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    @DisplayName("POST /dns: aynı (domain, kayıt tipi) standalone varken → 400; sessiz no-op YOK, kayıt oluşmaz")
    void createDns_duplicateDomainRecordType_returns400() throws Exception {
        com.sitemonitor.model.DnsMonitor existing = new com.sitemonitor.model.DnsMonitor();
        existing.setId(8L); existing.setDomain("www.example.com"); existing.setRecordType("A"); existing.setStandalone(true);
        when(dnsMonitorRepo.findFirstByDomainAndRecordTypeAndStandaloneTrue("www.example.com", "A"))
                .thenReturn(Optional.of(existing));

        mvc.perform(post("/api/monitoring/dns").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"www.example.com\",\"recordType\":\"A\",\"teamId\":3,\"name\":\"example (Kopya)\"}"))
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
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"  www.axess.com.tr  \",\"teamId\":1}"))
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
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"www.axess.com.tr\",\"teamId\":1}"))
                .andExpect(status().isBadRequest());
        verify(pageMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("POST /page: host'suz URL (https://) → 400 'geçerli bir host yok', kayıt yok")
    void createPage_hostlessUrl_returns400() throws Exception {
        mvc.perform(post("/api/monitoring/page").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://\",\"teamId\":1}"))
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
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"www.axess.com.tr\",\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://www.axess.com.tr"));

        // İç servis 443'te olmayabilir → kullanıcının açık http:// tercihi asla https'e taşınmaz.
        mvc.perform(post("/api/monitoring/http").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"http://internal.host:8080/health\",\"teamId\":3}"))
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
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"  x.example.com/a?t={timestamp}  \",\"keyword\":\"example\",\"teamId\":3}"))
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
        when(pageChecker.test(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyBoolean())).thenReturn(
                new com.sitemonitor.service.PageCheckerService.PageCheckResult(
                        "OK", true, 200, 12L, 3, 0, 0, 0, 1, null, null, null, List.of()));
        mvc.perform(post("/api/monitoring/page/test").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"www.axess.com.tr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.via").value("direct"));   // vekil bileşeni test bağlamında yok → doğrudan
        verify(pageChecker).test(eq("https://www.axess.com.tr"), org.mockito.ArgumentMatchers.anyInt(), eq(false));
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
        // Beklenen pencere AÇIKÇA stub'lanır: test eskiden stub'sız mock'un 0 döndürmesine yaslanıyordu,
        // yani "clamp çalışıyor" iddiasını sıfır pencereyle kanıtlıyordu (her from bugüne kırpılırdı).
        when(retentionService.historyRetentionDays(anyString(), anyInt())).thenReturn(90);
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

    // ── Taslak ve surum uclari ───────────────────────────────────────────────────────────────
    // Bu alti ucun HICBIRI test edilmiyordu: taslak yolu otomatik kaydetme (30 sn'de bir) ve
    // veri kurtarma icin var, surum yolu ise "dun calisiyordu" vakasinin tek kanitidir.

    private com.sitemonitor.model.ScriptedScriptVersion ver(long id, long monitorId, int seq, String label) {
        var v = new com.sitemonitor.model.ScriptedScriptVersion();
        v.setId(id); v.setMonitorId(monitorId); v.setSequenceNo(seq); v.setVersion(label);
        v.setEventType("EDIT"); v.setScript("export default function () {}");
        v.setCreatedAt("2026-08-0" + seq + "T10:00:00"); v.setCreatedBy("tester");
        return v;
    }

    @Test
    @DisplayName("GET /scripted/{id}/versions: en yeni ustte, GOVDE yok, guncel surum isaretli")
    void scriptedVersions_listsWithoutBody() throws Exception {
        var m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(40L); m.setName("surumlu"); m.setScriptVersion("1.1.0");
        when(scriptedMonitorRepo.findById(40L)).thenReturn(Optional.of(m));
        when(scriptedVersionRepo.findByMonitorIdOrderBySequenceNoDesc(40L))
                .thenReturn(List.of(ver(2L, 40L, 2, "1.1.0"), ver(1L, 40L, 1, "1.0.0")));

        mvc.perform(get("/api/monitoring/scripted/40/versions").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_version").value("1.1.0"))
                .andExpect(jsonPath("$.data.versions[0].version").value("1.1.0"))
                .andExpect(jsonPath("$.data.versions[0].current").value(true))
                .andExpect(jsonPath("$.data.versions[1].current").value(false))
                // Yanit sismesin: liste ucu script GOVDESINI donmez, yalniz uzunlugunu.
                .andExpect(jsonPath("$.data.versions[0].script").doesNotExist())
                .andExpect(jsonPath("$.data.versions[0].script_chars").value("export default function () {}".length()));
    }

    @Test
    @DisplayName("GET /scripted/{id}/versions: monitor yoksa 404")
    void scriptedVersions_missingMonitor() throws Exception {
        when(scriptedMonitorRepo.findById(41L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/monitoring/scripted/41/versions").session(session("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /scripted/{id}/versions/{versionId}: BASKA monitorun surumu 404 (yetki siniri monitor uzerinden)")
    void scriptedVersionDetail_crossMonitorIsNotFound() throws Exception {
        var m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(42L); m.setName("kendi");
        when(scriptedMonitorRepo.findById(42L)).thenReturn(Optional.of(m));
        when(scriptedVersionRepo.findById(99L)).thenReturn(Optional.of(ver(99L, 43L, 1, "1.0.0")));

        mvc.perform(get("/api/monitoring/scripted/42/versions/99").session(session("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /scripted/{id}/versions/{versionId}: govde doner, secret env DUZ METIN donmez")
    void scriptedVersionDetail_returnsBodyWithMaskedSecrets() throws Exception {
        var m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(44L); m.setName("gizli-env");
        var v = ver(7L, 44L, 1, "1.0.0");
        v.setEnvJson("[{\"name\":\"TOKEN\",\"value\":\"cok-gizli\",\"secret\":true}]");
        when(scriptedMonitorRepo.findById(44L)).thenReturn(Optional.of(m));
        when(scriptedVersionRepo.findById(7L)).thenReturn(Optional.of(v));

        mvc.perform(get("/api/monitoring/scripted/44/versions/7").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.script").value("export default function () {}"))
                .andExpect(jsonPath("$.data.env[0].name").value("TOKEN"))
                .andExpect(jsonPath("$.data.env[0].secret").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cok-gizli"))));
    }

    @Test
    @DisplayName("PUT /scripted/draft: yeni monitor taslagi 'new' anahtariyla kaydedilir (monitor_id NULL)")
    void scriptedDraft_upsertNew() throws Exception {
        when(scriptedDraftRepo.findByOwnerAndMonitorKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(scriptedDraftRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/scripted/draft").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"monitorKey\":\"yeni\",\"monitorName\":\"ad\",\"formJson\":\"{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitor_key").value("new"));

        var cap = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.ScriptedDraft.class);
        org.mockito.Mockito.verify(scriptedDraftRepo).save(cap.capture());
        assertThat(cap.getValue().getMonitorKey()).isEqualTo("new");
        assertThat(cap.getValue().getMonitorId()).isNull();
    }

    @Test
    @DisplayName("POST /scripted/draft: sayisal anahtar monitor_id'ye cevrilir (sendBeacon POST yolu)")
    void scriptedDraft_postWithNumericKey() throws Exception {
        when(scriptedDraftRepo.findByOwnerAndMonitorKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(scriptedDraftRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/monitoring/scripted/draft").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"monitorKey\":\"12\",\"formJson\":\"{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitor_key").value("12"));

        var cap = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.ScriptedDraft.class);
        org.mockito.Mockito.verify(scriptedDraftRepo).save(cap.capture());
        assertThat(cap.getValue().getMonitorId()).isEqualTo(12L);
    }

    @Test
    @DisplayName("GET /scripted/drafts: YALNIZ cagiranin kendi taslaklari sorgulanir")
    void scriptedDrafts_scopedToOwner() throws Exception {
        var d = new com.sitemonitor.model.ScriptedDraft();
        d.setMonitorKey("new"); d.setMonitorName("yarim"); d.setFormJson("{}");
        d.setUpdatedAt("2026-08-10T10:00:00");
        when(scriptedDraftRepo.findByOwnerOrderByUpdatedAtDesc("u")).thenReturn(List.of(d));

        mvc.perform(get("/api/monitoring/scripted/drafts").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.drafts[0].monitor_key").value("new"))
                .andExpect(jsonPath("$.data.drafts[0].monitor_name").value("yarim"));

        org.mockito.Mockito.verify(scriptedDraftRepo).findByOwnerOrderByUpdatedAtDesc("u");
    }

    @Test
    @DisplayName("DELETE /scripted/draft/{key}: bozuk anahtar 'new'e duser (yol parametresi guvenligi)")
    void scriptedDraft_deleteSanitizesKey() throws Exception {
        mvc.perform(delete("/api/monitoring/scripted/draft/..%2Fetc").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));

        org.mockito.Mockito.verify(scriptedDraftRepo).deleteByOwnerAndMonitorKey("u", "new");
    }

    // ── İZİN KAPILARI ────────────────────────────────────────────────────────────
    // permissionService @MockitoBean ve require(...) VOID: stub'lanmadıkça mock hiçbir şey yapmaz →
    // kapı testte HER ZAMAN açıktı. Bu controller'daki 70 require() satırı silinse tek test bile
    // kırmızıya dönmezdi. Geçen 403 testleri SessionScope takım kontrolünden geliyordu, izin
    // matrisinden değil — yani "USER izleme silemez" gibi iddialar hiç kanıtlanmamıştı.
    // Aşağıdaki tablo her (anahtar, aksiyon) çifti için TEMSİLCİ bir uç tutar.

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> permissionGates() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("monitoring.read",   "view",    "GET",  "/api/monitoring/port", ""),
            org.junit.jupiter.params.provider.Arguments.of("monitoring.crud",   "edit",    "POST", "/api/monitoring/port", "{\"host\":\"h\",\"port\":443}"),
            org.junit.jupiter.params.provider.Arguments.of("monitoring.trigger","execute", "POST", "/api/monitoring/port/1/check", ""),
            org.junit.jupiter.params.provider.Arguments.of("monitoring.trigger","execute", "POST", "/api/monitoring/http/1/check", ""),
            org.junit.jupiter.params.provider.Arguments.of("monitoring.scripted","edit",   "POST", "/api/monitoring/scripted", "{\"name\":\"x\"}"),
            org.junit.jupiter.params.provider.Arguments.of("monitoring.scripted","execute","POST", "/api/monitoring/scripted/1/check", ""),
            org.junit.jupiter.params.provider.Arguments.of("monitoring.scripted","execute","POST", "/api/monitoring/scripted/1/diagnose", ""),
            org.junit.jupiter.params.provider.Arguments.of("monitoring.group",  "view",    "GET",  "/api/monitoring/groups", ""),
            org.junit.jupiter.params.provider.Arguments.of("monitoring.group",  "edit",    "PUT",  "/api/monitoring/groups/1", "{\"new_name\":\"x\"}")
        );
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}/{1} reddedilince {2} {3} → 403")
    @org.junit.jupiter.params.provider.MethodSource("permissionGates")
    @DisplayName("İZİN KAPISI: ilgili anahtar reddedilince uç 403 döner (kapı gerçekten bağlı)")
    void permissionGate_denied_returns403(String key, String action, String method, String url, String body) throws Exception {
        org.mockito.Mockito.doThrow(new SecurityException("izin yok"))
                .when(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq(key), eq(action));

        var req = switch (method) {
            case "POST" -> post(url);
            case "PUT"  -> put(url);
            default     -> get(url);
        };
        if (!body.isEmpty()) req.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);

        mvc.perform(req.session(session("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("İZİN KAPISI: BAŞKA bir anahtar reddedilse de uç çalışır (kapı doğru anahtara bağlı)")
    void permissionGate_unrelatedKeyDenied_stillWorks() throws Exception {
        // Yanlış anahtara bağlanmış bir kapı yukarıdaki testte de yeşil görünürdü; bu test onu ayırır.
        org.mockito.Mockito.doThrow(new SecurityException("izin yok"))
                .when(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("incidents.manage"), anyString());
        when(portMonitorRepo.findAll()).thenReturn(List.of());
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/port").session(session("ADMIN")))
                .andExpect(status().isOk());
    }

    // ── Lazy-provision yarışı (mükerrer monitör) ─────────────────────────────────
    //
    // GET /port ve /dns eksik monitörleri istek anında yaratıyor (check-then-act). Tabloda
    // benzersizlik kısıtı olmadığı sürece 100 kullanıcı ekranı aynı anda açtığında aynı domain
    // için N satır oluşuyordu → N kat kontrol trafiği ve raporlarda çift sayım. Artık kısıt var
    // (uq_pm_host_port / uq_dnsm_domain); bu testler kaybeden isteğin 500 ATMADIĞINI ve listenin
    // yine dolu döndüğünü pinler.

    @Test
    @DisplayName("Port lazy-provision çakışırsa istek 500 ATMAZ — tablo yeniden okunur, liste döner")
    void portProvisionRace_recoversFromConflict() throws Exception {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("yaris.example.com")));
        com.sitemonitor.model.PortMonitor winner = new com.sitemonitor.model.PortMonitor();
        winner.setId(77L); winner.setHost("yaris.example.com"); winner.setPort(443);
        winner.setName("yaris.example.com"); winner.setActive(true);
        // 1. okuma: boş (biz de yaratmaya karar veriyoruz) · 2. okuma: kazananın satırı
        when(portMonitorRepo.findAll()).thenReturn(List.of(), List.of(winner));
        when(portMonitorRepo.saveAll(anyList()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_pm_host_port"));

        mvc.perform(get("/api/monitoring/port").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].host").value("yaris.example.com"));
    }

    @Test
    @DisplayName("Yarış sonrası satır HÂLÂ yoksa o domain atlanır — tüm liste tek kayıt yüzünden ölmez")
    void portProvisionRace_missingRowIsSkippedNotFatal() throws Exception {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc())
                .thenReturn(List.of(inv("kayip.example.com")));
        when(portMonitorRepo.findAll()).thenReturn(List.of());   // her iki okumada da boş
        when(portMonitorRepo.saveAll(anyList()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_pm_host_port"));

        // Eskiden monitor DAİMA dolu varsayılıyordu → NPE → 500; artık boş liste ile 200.
        mvc.perform(get("/api/monitoring/port").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("DNS lazy-provision çakışması da aynı şekilde kurtarılır")
    void dnsProvisionRace_recoversFromConflict() throws Exception {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("yaris.example.com")));
        com.sitemonitor.model.DnsMonitor winner = new com.sitemonitor.model.DnsMonitor();
        winner.setId(88L); winner.setDomain("yaris.example.com"); winner.setRecordType("A");
        winner.setName("yaris.example.com"); winner.setActive(true);
        when(dnsMonitorRepo.findAll()).thenReturn(List.of(), List.of(winner));
        when(dnsMonitorRepo.saveAll(anyList()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_dnsm_domain"));

        mvc.perform(get("/api/monitoring/dns").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("yaris.example.com"));
    }

    // ── IDOR: yazma uçlarında takım sınırı ───────────────────────────────────────
    // Buraya kadar çapraz-takım yazma testi yalnız page ve port'ta vardı; kalan altı türün
    // PUT ve /check uçları test edilmiyordu. Bir guard yanlışlıkla kaldırılsa (ya da yeni tür
    // eklenirken unutulsa) başka takımın monitörü düzenlenebilir/tetiklenebilir hale gelirdi.

    /** Her tür için: yabancı takıma (2) ait monitörü döndüren stub + PUT gövdesi. */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> writeEndpoints() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("http",    "/api/monitoring/http/5"),
            org.junit.jupiter.params.provider.Arguments.of("keyword", "/api/monitoring/keyword/5"),
            org.junit.jupiter.params.provider.Arguments.of("ping",    "/api/monitoring/ping/5"),
            org.junit.jupiter.params.provider.Arguments.of("domain",  "/api/monitoring/domain/5"),
            org.junit.jupiter.params.provider.Arguments.of("port",    "/api/monitoring/port/5"),
            org.junit.jupiter.params.provider.Arguments.of("dns",     "/api/monitoring/dns/5")
        );
    }

    /** Yabancı takıma (teamId=2) ait bir monitörü ilgili repo'dan döndürür. */
    private void stubForeignMonitor(String type) {
        switch (type) {
            case "http" -> {
                var m = new com.sitemonitor.model.HttpMonitor();
                m.setId(5L); m.setUrl("https://x.com"); m.setTeamId(2L);
                when(httpMonitorRepo.findById(5L)).thenReturn(Optional.of(m));
            }
            case "keyword" -> {
                var m = new com.sitemonitor.model.KeywordMonitor();
                m.setId(5L); m.setUrl("https://x.com"); m.setKeyword("k"); m.setTeamId(2L);
                when(keywordMonitorRepo.findById(5L)).thenReturn(Optional.of(m));
            }
            case "ping" -> {
                var m = new com.sitemonitor.model.PingMonitor();
                m.setId(5L); m.setHost("h"); m.setTeamId(2L);
                when(pingMonitorRepo.findById(5L)).thenReturn(Optional.of(m));
            }
            case "domain" -> {
                var m = new com.sitemonitor.model.DomainMonitor();
                m.setId(5L); m.setDomain("d.com"); m.setTeamId(2L);
                when(domainMonitorRepo.findById(5L)).thenReturn(Optional.of(m));
            }
            case "port" -> {
                var m = new com.sitemonitor.model.PortMonitor();
                m.setId(5L); m.setHost("h"); m.setPort(443); m.setTeamId(2L);
                when(portMonitorRepo.findById(5L)).thenReturn(Optional.of(m));
            }
            case "dns" -> {
                var m = new com.sitemonitor.model.DnsMonitor();
                m.setId(5L); m.setDomain("d.com"); m.setRecordType("A"); m.setTeamId(2L);
                when(dnsMonitorRepo.findById(5L)).thenReturn(Optional.of(m));
            }
            default -> throw new IllegalArgumentException(type);
        }
    }

    private void verifyNothingSaved(String type) {
        switch (type) {
            case "http"    -> verify(httpMonitorRepo, never()).save(any());
            case "keyword" -> verify(keywordMonitorRepo, never()).save(any());
            case "ping"    -> verify(pingMonitorRepo, never()).save(any());
            case "domain"  -> verify(domainMonitorRepo, never()).save(any());
            case "port"    -> verify(portMonitorRepo, never()).save(any());
            case "dns"     -> verify(dnsMonitorRepo, never()).save(any());
            default -> { }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "PUT {0}: yabancı takım → 403, kayıt DEĞİŞMEZ")
    @org.junit.jupiter.params.provider.MethodSource("writeEndpoints")
    @DisplayName("IDOR: her türün PUT ucu başka takımın monitörünü düzenletmez")
    void update_foreignTeam_forbidden(String type, String url) throws Exception {
        stubForeignMonitor(type);

        mvc.perform(put(url).session(sessionWithTeam("USER", 1L))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ele-gecirildi\"}"))
                .andExpect(status().isForbidden());

        verifyNothingSaved(type);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "POST {0}/check: yabancı takım → 403")
    @org.junit.jupiter.params.provider.MethodSource("writeEndpoints")
    @DisplayName("IDOR: her türün elle kontrol ucu başka takımın monitörünü TETİKLEMEZ")
    void triggerCheck_foreignTeam_forbidden(String type, String url) throws Exception {
        stubForeignMonitor(type);

        mvc.perform(post(url + "/check").session(sessionWithTeam("USER", 1L)))
                .andExpect(status().isForbidden());
    }

    // ══ Değişiklik geçmişi uçları ═════════════════════════════════════════════

    /**
     * "Kim, ne zaman, hangi IP'den, neyi değiştirdi" uçlarının KAPSAM sözleşmesi.
     *
     * <p>Bu uçlar takım kullanıcısına açık — denetim konsolunun aksine admin kapısı YOK. O yüzden
     * asıl risk yetkilendirme: başka takımın izlemesinin geçmişi (IP adresleri ve eski değerler
     * dâhil) sızarsa, denetim kaydını admin'e ayırmış olmanın bir anlamı kalmaz.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("Değişiklik geçmişi")
    class ChangeHistoryEndpoints {

        private com.sitemonitor.model.MonitorChangeLog row(Long teamId) {
            com.sitemonitor.model.MonitorChangeLog r = new com.sitemonitor.model.MonitorChangeLog();
            r.setId(1L); r.setResourceKind("PORT"); r.setResourceId(7L); r.setResourceName("Ödeme portu");
            r.setSeq(0); r.setEventType("CREATE"); r.setTeamId(teamId);
            r.setActor("N23456"); r.setActorName("Ada Lovelace"); r.setIpAddress("10.20.30.40");
            r.setSnapshot("{\"name\":\"Odeme portu\"}"); r.setCreatedAt("2026-08-22T10:00:00");
            return r;
        }

        private MockHttpSession memberOf(Long teamId) {
            MockHttpSession s = sessionWithTeam("USER", teamId);
            s.setAttribute("viewTeamIds", List.of(teamId));
            return s;
        }

        @Test
        @DisplayName("Kendi takımının geçmişi okunur; liste yanıtı SNAPSHOT taşımaz")
        void ownTeam_listed_withoutSnapshot() throws Exception {
            when(changeLogRepo.findTopByResourceKindAndResourceIdOrderByCreatedAtDesc("PORT", 7L))
                    .thenReturn(Optional.of(row(5L)));
            when(changeLogRepo.findByResourceKindAndResourceIdOrderByCreatedAtDescIdDesc(
                    eq("PORT"), eq(7L), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row(5L))));

            mvc.perform(get("/api/monitoring/changes/port/7").session(memberOf(5L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.changes[0].event_type").value("CREATE"))
                    .andExpect(jsonPath("$.data.changes[0].ip_address").value("10.20.30.40"))
                    .andExpect(jsonPath("$.data.changes[0].actor_name").value("Ada Lovelace"))
                    // Snapshot yalnız TEK olay ucunda döner — liste yanıtını şişirmez.
                    .andExpect(jsonPath("$.data.changes[0].snapshot").doesNotExist());
        }

        @Test
        @DisplayName("IDOR: yabancı takımın geçmişi 404 döner ve güvenlik olayı yazılır")
        void foreignTeam_notFound_andAudited() throws Exception {
            when(changeLogRepo.findTopByResourceKindAndResourceIdOrderByCreatedAtDesc("PORT", 7L))
                    .thenReturn(Optional.of(row(9L)));

            mvc.perform(get("/api/monitoring/changes/port/7").session(memberOf(1L)))
                    .andExpect(status().isNotFound());

            // Satırlar HİÇ okunmamalı: yetki kararı sorgudan ÖNCE verilir.
            verify(changeLogRepo, never())
                    .findByResourceKindAndResourceIdOrderByCreatedAtDescIdDesc(anyString(), anyLong(), any());
            verify(auditService).recordSecurityEvent(eq("CHANGE_LOG_DENIED"), any(), any(), anyString(),
                    anyString(), anyString());
        }

        @Test
        @DisplayName("Hiç kayıt yoksa boş liste döner — varlık/yokluk bilgisi sızmaz")
        void noRows_emptyList() throws Exception {
            when(changeLogRepo.findTopByResourceKindAndResourceIdOrderByCreatedAtDesc(anyString(), anyLong()))
                    .thenReturn(Optional.empty());

            mvc.perform(get("/api/monitoring/changes/port/7").session(memberOf(1L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0));
        }

        @Test
        @DisplayName("Bilinmeyen kaynak türü 400 — yol parametresi sessizce yutulmaz")
        void unknownKind_badRequest() throws Exception {
            mvc.perform(get("/api/monitoring/changes/telepati/7").session(memberOf(1L)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Tek olay ucu snapshot'ı VERİR; yabancı takımda 404")
        void detail_returnsSnapshot_andBlocksForeign() throws Exception {
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(row(5L)));

            mvc.perform(get("/api/monitoring/changes/port/7/0").session(memberOf(5L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.snapshot").value("{\"name\":\"Odeme portu\"}"));

            mvc.perform(get("/api/monitoring/changes/port/7/0").session(memberOf(1L)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Toplu akış: admin TÜM takımları görür (kapsam süzgeci uygulanmaz)")
        void recent_globalAdmin_seesAll() throws Exception {
            when(changeLogRepo.search(any(), any(), any(), any(), any(), any(), any(),
                    eq(true), any(), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row(9L))));
            when(changeLogRepo.countByEventType(any(), any(), any(), eq(true), any()))
                    .thenReturn(List.<Object[]>of(new Object[]{"CREATE", 4L}));

            mvc.perform(get("/api/monitoring/changes/recent").session(session("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.changes[0].resource_name").value("Ödeme portu"))
                    .andExpect(jsonPath("$.data.event_counts.CREATE").value(4));
        }

        @Test
        @DisplayName("Toplu akış: takım kullanıcısı KENDİ kapsamıyla sorgular")
        void recent_teamUser_isScoped() throws Exception {
            when(changeLogRepo.search(any(), any(), any(), any(), any(), any(), any(),
                    eq(false), eq(List.of(5L)), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row(5L))));
            when(changeLogRepo.countByEventType(any(), any(), any(), eq(false), eq(List.of(5L))))
                    .thenReturn(List.<Object[]>of());

            mvc.perform(get("/api/monitoring/changes/recent").session(memberOf(5L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.changes[0].team_id").value(5));
        }

        @Test
        @DisplayName("Kapsamı BOŞ kullanıcı hiçbir şey görmez — sorgu bile açılmaz")
        void recent_emptyScope_returnsNothing() throws Exception {
            MockHttpSession s = sessionWithTeam("USER", 1L);
            s.setAttribute("viewTeamIds", List.of());

            mvc.perform(get("/api/monitoring/changes/recent").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0));

            verify(changeLogRepo, never()).search(any(), any(), any(), any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("kind yol anahtarı BÜYÜK/küçük harften bağımsız çözülür")
        void recent_kindIsCaseInsensitive() throws Exception {
            when(changeLogRepo.search(eq("SCRIPTED"), any(), any(), any(), any(), any(), any(),
                    eq(true), any(), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
            when(changeLogRepo.countByEventType(any(), any(), any(), eq(true), any()))
                    .thenReturn(List.<Object[]>of());

            mvc.perform(get("/api/monitoring/changes/recent").param("kind", "Scripted")
                            .session(session("ADMIN")))
                    .andExpect(status().isOk());

            verify(changeLogRepo).search(eq("SCRIPTED"), any(), any(), any(), any(), any(), any(),
                    eq(true), any(), any());
        }

        // ── Takım süzgeci (ekran tüm takım kullanıcılarına açıldı) ──────────

        /**
         * {@code teamId} bir SÜZGEÇTİR, kapsam değil. Kapsam koruması sorguda ayrıca AND'lendiği
         * için yabancı bir id veri sızdıramaz — ama sessizce BOŞ liste dönmek yanlış cevap olur:
         * kullanıcı süzgecin çalıştığını sanıp "o takımda hiç değişiklik olmamış" sonucuna varır.
         */
        @Test
        @DisplayName("Kapsam DIŞI takım süzgeci 403 — sessiz boş liste değil")
        void teamFilter_outOfScope_isForbidden() throws Exception {
            mvc.perform(get("/api/monitoring/changes/recent").param("teamId", "9")
                            .session(memberOf(5L)))
                    .andExpect(status().isForbidden());

            verify(changeLogRepo, never()).search(any(), any(), any(), any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        }

        /**
         * Süzgeç ÜÇ sorguya birden geçmeli. Yalnız listeye geçseydi yönetici bir takım seçtiğinde
         * liste daralır ama özet şerit ve tür kartları tüm takımları saymaya devam eder — rakamlar
         * ekranda listeyle çelişirdi.
         */
        @Test
        @DisplayName("Kapsam İÇİ takım süzgeci listeye VE iki sayıma birden geçer")
        void teamFilter_inScope_reachesCountsToo() throws Exception {
            when(changeLogRepo.search(any(), any(), any(), eq(5L), any(), any(), any(),
                    eq(false), eq(List.of(5L)), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row(5L))));

            mvc.perform(get("/api/monitoring/changes/recent").param("teamId", "5")
                            .session(memberOf(5L)))
                    .andExpect(status().isOk());

            verify(changeLogRepo).countByEventType(any(), any(), eq(5L), eq(false), eq(List.of(5L)));
            verify(changeLogRepo).countByKindAndEventType(any(), any(), eq(5L), eq(false), eq(List.of(5L)));
        }

        @Test
        @DisplayName("Global yönetici herhangi bir takımı süzebilir")
        void teamFilter_globalViewer_anyTeam() throws Exception {
            when(changeLogRepo.search(any(), any(), any(), eq(9L), any(), any(), any(),
                    eq(true), any(), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row(9L))));

            mvc.perform(get("/api/monitoring/changes/recent").param("teamId", "9")
                            .session(session("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.changes[0].team_id").value(9));

            verify(changeLogRepo).countByEventType(any(), any(), eq(9L), eq(true), any());
        }

        /** Süzgeç VERİLMEZSE sayımlar da süzgeçsiz kalmalı — kapsam yine uygulanır. */
        @Test
        @DisplayName("Takım süzgeci yokken sayımlara null geçer (kapsam yine uygulanır)")
        void noTeamFilter_passesNullToCounts() throws Exception {
            when(changeLogRepo.search(any(), any(), any(), any(), any(), any(), any(),
                    eq(false), eq(List.of(5L)), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row(5L))));

            mvc.perform(get("/api/monitoring/changes/recent").session(memberOf(5L)))
                    .andExpect(status().isOk());

            verify(changeLogRepo).countByEventType(any(), any(), org.mockito.ArgumentMatchers.isNull(),
                    eq(false), eq(List.of(5L)));
        }

        // ── Geri döndürme (K6) ──────────────────────────────────────────────

        private MockHttpSession managerOf(Long teamId) {
            MockHttpSession s = sessionWithTeam("USER", teamId);
            s.setAttribute("viewTeamIds", List.of(teamId));
            s.setAttribute("manageTeamIds", List.of(teamId));
            return s;
        }

        private com.sitemonitor.model.MonitorChangeLog snapRow(Long teamId, String snapshot) {
            com.sitemonitor.model.MonitorChangeLog r = row(teamId);
            r.setSnapshot(snapshot);
            return r;
        }

        private com.sitemonitor.model.PortMonitor liveMonitor() {
            com.sitemonitor.model.PortMonitor m = new com.sitemonitor.model.PortMonitor();
            m.setId(7L); m.setName("Ödeme portu"); m.setHost("odeme.local"); m.setPort(8443);
            m.setTeamId(5L); m.setActive(false); m.setIntervalSeconds(60);
            return m;
        }

        @Test
        @DisplayName("Snapshot'taki değerler geri yazılır ve RESTORE olaylı YENİ satır eklenir")
        void restore_appliesSnapshot_andAppendsRestoreRow() throws Exception {
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(snapRow(5L,
                            "{\"name\":\"Ödeme portu\",\"port\":443,\"active\":true,\"intervalSeconds\":300}")));
            com.sitemonitor.model.PortMonitor live = liveMonitor();
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(live));

            mvc.perform(post("/api/monitoring/changes/port/7/0/restore").session(managerOf(5L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.restored").value(true));

            // Değerler GERÇEKTEN geri yazıldı (yanıt "başarılı" derken kayıt değişmemiş olmasın).
            assertThat(live.getPort()).isEqualTo(443);
            assertThat(live.getActive()).isTrue();
            assertThat(live.getIntervalSeconds()).isEqualTo(300);
            verify(portMonitorRepo).save(live);
            // Geçmiş EZİLMEZ: geri alma da bir olaydır ve kendi satırını yazar.
            verify(monitorHistory).record(eq("PORT"), eq(7L), anyString(), eq(5L),
                    eq("RESTORE"), any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("Maskeli alan geri YAZILMAZ — gerçek sır '***' ile ezilmez, atlandığı bildirilir")
        void restore_neverWritesMaskedValues() throws Exception {
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(snapRow(5L, "{\"port\":443,\"expect\":\"***\"}")));
            com.sitemonitor.model.PortMonitor live = liveMonitor();
            live.setExpect("gerçek-imza");
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(live));

            mvc.perform(post("/api/monitoring/changes/port/7/0/restore").session(managerOf(5L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.skipped_masked[0]").value("expect"));

            assertThat(live.getExpect()).isEqualTo("gerçek-imza");
        }

        @Test
        @DisplayName("Takım alanı geri alma ile DEĞİŞMEZ — takım taşımak ayrı bir yetki kararı")
        void restore_neverMovesTeam() throws Exception {
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(snapRow(5L, "{\"port\":443,\"teamId\":99}")));
            com.sitemonitor.model.PortMonitor live = liveMonitor();
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(live));

            mvc.perform(post("/api/monitoring/changes/port/7/0/restore").session(managerOf(5L)))
                    .andExpect(status().isOk());

            assertThat(live.getTeamId()).isEqualTo(5L);
        }

        @Test
        @DisplayName("Yalnız GÖRME yetkisi geri almaya yetmez → 403, kayıt kaydedilmez")
        void restore_requiresManageScope() throws Exception {
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(snapRow(5L, "{\"port\":443}")));

            mvc.perform(post("/api/monitoring/changes/port/7/0/restore").session(memberOf(5L)))
                    .andExpect(status().isForbidden());

            verify(portMonitorRepo, never()).save(any());
        }

        @Test
        @DisplayName("Ayarlar zaten o andaki gibiyse 400 — boş bir RESTORE satırı üretilmez")
        void restore_noDifference_isRejected() throws Exception {
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(snapRow(5L, "{\"bilinmeyenAlan\":1}")));
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(liveMonitor()));

            mvc.perform(post("/api/monitoring/changes/port/7/0/restore").session(managerOf(5L)))
                    .andExpect(status().isBadRequest());

            verify(portMonitorRepo, never()).save(any());
        }

        @Test
        @DisplayName("Snapshot'sız olay geri alınamaz (liste yanıtından gelen satır gibi)")
        void restore_withoutSnapshot_isRejected() throws Exception {
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(snapRow(5L, null)));

            mvc.perform(post("/api/monitoring/changes/port/7/0/restore").session(managerOf(5L)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Geri alınamayan tür (bakım penceresi) açıkça reddedilir")
        void restore_unsupportedKind_isRejected() throws Exception {
            var r = snapRow(5L, "{\"name\":\"gece bakımı\"}");
            r.setResourceKind("MAINTENANCE");
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("MAINTENANCE", 7L, 0))
                    .thenReturn(Optional.of(r));

            mvc.perform(post("/api/monitoring/changes/maintenance/7/0/restore").session(managerOf(5L)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Snapshot GÜNCEL durumun aynısıysa reddedilir: boş RESTORE satırı üretilmez")
        void restore_identicalSnapshot_isRejected() throws Exception {
            com.sitemonitor.model.PortMonitor live = liveMonitor();
            // Canlı kayıtla birebir aynı degerler: applySnapshot yazar ama FARK yoktur.
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(snapRow(5L,
                            "{\"name\":\"Ödeme portu\",\"port\":8443,\"active\":false,\"intervalSeconds\":60}")));
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(live));

            mvc.perform(post("/api/monitoring/changes/port/7/0/restore").session(managerOf(5L)))
                    .andExpect(status().isBadRequest());

            verify(portMonitorRepo, never()).save(any());
            verify(monitorHistory, never()).record(anyString(), anyLong(), any(), any(),
                    eq("RESTORE"), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Geri alma kaydın SON DEĞİŞTİREN künyesini de günceller")
        void restore_stampsUpdatedIdentity() throws Exception {
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(snapRow(5L, "{\"port\":443}")));
            com.sitemonitor.model.PortMonitor live = liveMonitor();
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(live));

            mvc.perform(post("/api/monitoring/changes/port/7/0/restore").session(managerOf(5L)))
                    .andExpect(status().isOk());

            verify(monitorHistory).stampUpdated(eq(live), any());
            assertThat(live.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Yanıttaki alan listesi GERÇEKTEN değişenleri sayar, yazılanları değil")
        void restore_reportsOnlyChangedFields() throws Exception {
            // name zaten aynı, yalnız port farklı → yanıt tek alan bildirmeli.
            when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("PORT", 7L, 0))
                    .thenReturn(Optional.of(snapRow(5L, "{\"name\":\"Ödeme portu\",\"port\":443}")));
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(liveMonitor()));

            mvc.perform(post("/api/monitoring/changes/port/7/0/restore").session(managerOf(5L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.fields.length()").value(1))
                    .andExpect(jsonPath("$.data.fields[0]").value("port"));
        }

        // ── Activity akışına düşen CONFIG_CHANGED (K9) ──────────────────────
        //
        // Bu bağ HİÇ test edilmemişti ve sessizce ölü kalabilirdi: noteConfigChanged, geçmiş
        // satırının DÖNÜŞ değerine bakıyor; @WebMvcTest'te monitorHistory bir mock olduğu için
        // varsayılan null döner ve olay hiç üretilmez. Yani "update testleri geçiyor" demek
        // bu kablonun çalıştığı anlamına GELMİYORDU.

        private com.sitemonitor.model.MonitorChangeLog changeRow(String changesJson) {
            com.sitemonitor.model.MonitorChangeLog r = row(5L);
            r.setEventType("UPDATE");
            r.setChanges(changesJson);
            return r;
        }

        private com.sitemonitor.model.PortMonitor livePort() {
            com.sitemonitor.model.PortMonitor m = new com.sitemonitor.model.PortMonitor();
            m.setId(7L); m.setName("Ödeme portu"); m.setHost("odeme.local"); m.setPort(8443);
            m.setTeamId(5L); m.setActive(true); m.setIntervalSeconds(300);
            return m;
        }

        @Test
        @DisplayName("Güncelleme Activity akışına CONFIG_CHANGED düşer, özetinde DEĞİŞEN ALANLAR olur")
        void update_emitsConfigChangedWithFieldSummary() throws Exception {
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(livePort()));
            when(portMonitorRepo.save(any(com.sitemonitor.model.PortMonitor.class)))
                    .thenAnswer(a -> a.getArgument(0));
            when(monitorHistory.record(eq("PORT"), eq(7L), any(), any(), eq("UPDATE"),
                    any(), any(), any(), any()))
                    .thenReturn(changeRow("{\"intervalSeconds\":{\"from\":300,\"to\":60},"
                            + "\"timeoutMs\":{\"from\":5000,\"to\":9000}}"));

            mvc.perform(put("/api/monitoring/port/7").session(managerOf(5L))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"intervalSeconds\":60,\"timeoutMs\":9000}"))
                    .andExpect(status().isOk());

            verify(activityLog).recordLifecycle(eq("PORT"), eq(7L), anyString(), anyString(),
                    eq(5L), eq("CONFIG_CHANGED"), any(), eq("intervalSeconds, timeoutMs"));
        }

        @Test
        @DisplayName("Hiçbir alan değişmediyse CONFIG_CHANGED üretilmez (akış gürültüyle dolmasın)")
        void update_noChange_emitsNoActivity() throws Exception {
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(livePort()));
            when(portMonitorRepo.save(any(com.sitemonitor.model.PortMonitor.class)))
                    .thenAnswer(a -> a.getArgument(0));
            // Geçmiş servisi "değişiklik yok" dediğinde null döner — sözleşmesi bu.
            when(monitorHistory.record(anyString(), anyLong(), any(), any(), eq("UPDATE"),
                    any(), any(), any(), any())).thenReturn(null);

            mvc.perform(put("/api/monitoring/port/7").session(managerOf(5L))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"intervalSeconds\":300}"))
                    .andExpect(status().isOk());

            verify(activityLog, never()).recordLifecycle(anyString(), anyLong(), any(), any(),
                    any(), eq("CONFIG_CHANGED"), any(), any());
        }

        @Test
        @DisplayName("Diff BOŞ gelirse de olay üretilmez (satır var ama alan yok)")
        void update_emptyDiff_emitsNoActivity() throws Exception {
            when(portMonitorRepo.findById(7L)).thenReturn(Optional.of(livePort()));
            when(portMonitorRepo.save(any(com.sitemonitor.model.PortMonitor.class)))
                    .thenAnswer(a -> a.getArgument(0));
            when(monitorHistory.record(anyString(), anyLong(), any(), any(), eq("UPDATE"),
                    any(), any(), any(), any())).thenReturn(changeRow(null));

            mvc.perform(put("/api/monitoring/port/7").session(managerOf(5L))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"intervalSeconds\":60}"))
                    .andExpect(status().isOk());

            verify(activityLog, never()).recordLifecycle(anyString(), anyLong(), any(), any(),
                    any(), eq("CONFIG_CHANGED"), any(), any());
        }
    }

    // ── Sayfa Hızı ─────────────────────────────────────────────────────────────

    private static com.sitemonitor.model.PageSpeedMonitor psMon(Long id, String url, Long teamId) {
        com.sitemonitor.model.PageSpeedMonitor m = new com.sitemonitor.model.PageSpeedMonitor();
        m.setId(id); m.setName(url); m.setUrl(url); m.setTeamId(teamId); m.setActive(true);
        return m;
    }

    @Test
    @DisplayName("IDOR: GET /pagespeed yalnız görüntülenebilir takımın izlemesini döndürür")
    void listPageSpeed_scopesToViewableTeams() throws Exception {
        when(pageSpeedCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(pageSpeedMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(
                psMon(1L, "https://a.com", 1L), psMon(2L, "https://b.com", 2L)));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));

        mvc.perform(get("/api/monitoring/pagespeed").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].url").value("https://a.com"));
    }

    @Test
    @DisplayName("IDOR: başka takımın serisi ve kaynak kırılımı 403")
    void pageSpeedReads_foreignTeam_forbidden() throws Exception {
        when(pageSpeedMonitorRepo.findById(9L)).thenReturn(Optional.of(psMon(9L, "https://x.com", 2L)));
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));

        mvc.perform(get("/api/monitoring/pagespeed/9/response-series").session(s))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/pagespeed/9/resources").session(s))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SIR: parola API'den ASLA dönmez — yalnız 'kayıtlı mı' bayrağı görünür")
    void pageSpeedResponseNeverLeaksPassword() throws Exception {
        var m = psMon(1L, "https://x.com", 1L);
        m.setBasicAuthUser("kadir");
        m.setBasicAuthPassEnc("SIFRELI-DEGER");
        m.setCustomHeadersEnc("SIFRELI-BASLIKLAR");
        when(pageSpeedCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(pageSpeedMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(m));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        when(secretCipher.decrypt("SIFRELI-BASLIKLAR")).thenReturn("X-Api-Key: cok-gizli-jeton");

        String body = mvc.perform(get("/api/monitoring/pagespeed").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].has_basic_auth_pass").value(true))
                .andExpect(jsonPath("$.data[0].has_custom_headers").value(true))
                // Admin başlık ADLARINI görür — DEĞERLERİNİ değil.
                .andExpect(jsonPath("$.data[0].custom_header_names[0]").value("X-Api-Key"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("SIFRELI-DEGER");
        assertThat(body).doesNotContain("cok-gizli-jeton");
        assertThat(body).doesNotContain("basic_auth_pass_enc");
    }

    @Test
    @DisplayName("KAPASITE: /pagespeed/test ust uste cagrilamaz (ayni oturumda ikinci istek 429)")
    void pageSpeedTest_hasPerSessionCooldown() throws Exception {
        // Kayitli olcumun cooldown'u vardi ama bu ucun YOKTU — oysa AYNI isi yapiyor: ana sayfa +
        // yuzlerce alt kaynak GET'i, GOVDELER dahil (kardes /page/test yalniz HEAD attigi icin ucuz,
        // bu degil). Tek pod 100 eszamanli kullaniciya hizmet ediyor; "Simdi Dene"ye ust uste basmak
        // istek thread'lerini ve bant genisligini tuketebiliyordu.
        // appSettings @MockitoBean → getInt varsayilan 0 doner (fallback DEGIL); acikca kur.
        when(appSettings.getInt(eq("site.monitor.pagespeed.test-cooldown-seconds"), anyInt())).thenReturn(10);
        when(pageSpeedChecker.test(any())).thenReturn(new com.sitemonitor.service.PageSpeedCheckerService.Result(
                "OK", 200, 40L, 90L, 300L, 20000L, 3, 0, false, false,
                java.util.List.of(), null, java.util.List.of()));
        var s = session("ADMIN");

        mvc.perform(post("/api/monitoring/pagespeed/test").session(s)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.com\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/monitoring/pagespeed/test").session(s)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x.com\"}"))
                .andExpect(status().is(429));

        // ASIL KANIT: ikinci istek olcumu HIC baslatmadi.
        verify(pageSpeedChecker, org.mockito.Mockito.times(1)).test(any());
    }

    @Test
    @DisplayName("SIR: BOŞ parola gönderimi mevcut şifreli değeri KORUR (her kayıt parolayı silmesin)")
    void blankPasswordKeepsExistingSecret() throws Exception {
        var existing = psMon(5L, "https://x.com", 1L);
        existing.setBasicAuthUser("kadir");
        existing.setBasicAuthPassEnc("ESKI-SIFRELI");
        when(pageSpeedMonitorRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(pageSpeedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/pagespeed/5").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"yeni ad\",\"basicAuthUser\":\"kadir\",\"basicAuthPass\":\"\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.PageSpeedMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PageSpeedMonitor.class);
        verify(pageSpeedMonitorRepo).save(cap.capture());
        assertThat(cap.getValue().getBasicAuthPassEnc()).isEqualTo("ESKI-SIFRELI");
    }

    @Test
    @DisplayName("SIR: kullanıcı adı temizlenirse parola da düşer (yetim şifreli değer kalmaz)")
    void clearingUserAlsoClearsPassword() throws Exception {
        var existing = psMon(5L, "https://x.com", 1L);
        existing.setBasicAuthUser("kadir");
        existing.setBasicAuthPassEnc("ESKI-SIFRELI");
        when(pageSpeedMonitorRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(pageSpeedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/pagespeed/5").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"basicAuthUser\":\"\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.PageSpeedMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PageSpeedMonitor.class);
        verify(pageSpeedMonitorRepo).save(cap.capture());
        assertThat(cap.getValue().getBasicAuthPassEnc()).isNull();
    }

    @Test
    @DisplayName("YETKİ: admin OLMAYAN kullanıcının gönderdiği özel başlık YOK SAYILIR (mevcut değer korunur)")
    void customHeadersAreAdminOnlyAndNonAdminEditIsIgnored() throws Exception {
        var existing = psMon(5L, "https://x.com", 1L);
        existing.setCustomHeadersEnc("ADMIN-IN-KOYDUGU");
        when(pageSpeedMonitorRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(pageSpeedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        MockHttpSession s = session("USER");
        s.setAttribute("teamId", 1L);
        s.setAttribute("viewTeamIds", java.util.List.of(1L));

        mvc.perform(put("/api/monitoring/pagespeed/5").session(s)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"customHeaders\":\"X-Evil: 1\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.PageSpeedMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PageSpeedMonitor.class);
        verify(pageSpeedMonitorRepo).save(cap.capture());
        // Ne yazıldı ne silindi: takım kullanıcısı formu kaydettiğinde admin'in ayarı olduğu gibi kalır.
        assertThat(cap.getValue().getCustomHeadersEnc()).isEqualTo("ADMIN-IN-KOYDUGU");
    }

    @Test
    @DisplayName("Aralık TABANI sunucuda uygulanır — form atlansa da 60 sn kaydedilemez")
    void intervalFloorIsEnforcedServerSide() throws Exception {
        when(pageSpeedMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(pageSpeedMonitorRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageSpeedMonitor m = i.getArgument(0); m.setId(1L); return m; });

        mvc.perform(post("/api/monitoring/pagespeed").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://x.com\",\"teamId\":1,\"intervalSeconds\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interval_seconds")
                        .value(com.sitemonitor.model.PageSpeedMonitor.MIN_INTERVAL_SECONDS));
    }

    @Test
    @DisplayName("Eşik AÇIKÇA null gönderilirse kaldırılır; alan hiç gelmezse dokunulmaz")
    void thresholdsCanBeClearedButAreNotTouchedWhenAbsent() throws Exception {
        var existing = psMon(5L, "https://x.com", 1L);
        existing.setMaxLoadMs(3000);
        existing.setMaxTtfbMs(500);
        when(pageSpeedMonitorRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(pageSpeedMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/pagespeed/5").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"maxLoadMs\":null}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.PageSpeedMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PageSpeedMonitor.class);
        verify(pageSpeedMonitorRepo).save(cap.capture());
        assertThat(cap.getValue().getMaxLoadMs()).isNull();       // açıkça temizlendi
        assertThat(cap.getValue().getMaxTtfbMs()).isEqualTo(500); // gönderilmedi → korundu
    }

    @Test
    @DisplayName("Şemasız URL normalize edilir; host'suz URL 400 döner")
    void urlIsNormalisedAndValidated() throws Exception {
        when(pageSpeedMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(pageSpeedMonitorRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageSpeedMonitor m = i.getArgument(0); m.setId(1L); return m; });

        mvc.perform(post("/api/monitoring/pagespeed").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"www.x.com\",\"teamId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://www.x.com"));

        mvc.perform(post("/api/monitoring/pagespeed").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"://\",\"teamId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Manuel ölçüm cooldown'ı: ikinci tetik 429")
    void triggerPageSpeed_cooldownReturns429() throws Exception {
        when(appSettings.getInt(eq("site.monitor.pagespeed.manual-cooldown-seconds"), anyInt())).thenReturn(30);
        when(pageSpeedMonitorRepo.findById(3L)).thenReturn(Optional.of(psMon(3L, "https://x.com", 1L)));
        MockHttpSession s = session("ADMIN");

        mvc.perform(post("/api/monitoring/pagespeed/3/check").session(s)).andExpect(status().isOk());
        mvc.perform(post("/api/monitoring/pagespeed/3/check").session(s)).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Kaynak kırılımı BAŞKA izlemenin checkId'siyle sızdırılamaz")
    void resourcesCannotLeakAcrossMonitorsViaCheckId() throws Exception {
        when(pageSpeedMonitorRepo.findById(1L)).thenReturn(Optional.of(psMon(1L, "https://a.com", 1L)));
        var foreign = new com.sitemonitor.model.PageSpeedResource();
        foreign.setMonitorId(99L);           // BAŞKA izlemenin satırı
        foreign.setUrl("https://gizli/x.js");
        when(pageSpeedResourceRepo.findByCheck(eq(77L), anyInt())).thenReturn(List.of(foreign));
        when(pageSpeedResourceRepo.breachSnapshots(eq(1L), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/pagespeed/1/resources?checkId=77").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resources.length()").value(0));
    }

    @Test
    @DisplayName("Seri metriği seçilebilir: ?metric=ttfb TTFB kolonunu çizer, varsayılan yükleme süresi")
    void seriesMetricSelectsTheProjectedColumn() throws Exception {
        when(pageSpeedMonitorRepo.findById(1L)).thenReturn(Optional.of(psMon(1L, "https://a.com", 1L)));
        // [checkedAt, responseMs, ttfbMs, totalBytes, requestCount, ok]
        when(pageSpeedCheckRepo.seriesRaw(eq(1L), anyString(), anyString(), anyInt())).thenReturn(
                // List.<Object[]>of: tek dizi argümanı varargs sanılıp List<Object> çıkarımına düşer.
                List.<Object[]>of(new Object[]{ "2026-08-23T10:00:00", 900, 120, 4096L, 30, true }));

        mvc.perform(get("/api/monitoring/pagespeed/1/response-series?days=7").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metric").value("load"))
                .andExpect(jsonPath("$.data.series[0].avg").value(900));

        mvc.perform(get("/api/monitoring/pagespeed/1/response-series?days=7&metric=ttfb").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metric").value("ttfb"))
                .andExpect(jsonPath("$.data.series[0].avg").value(120));

        mvc.perform(get("/api/monitoring/pagespeed/1/response-series?days=7&metric=requests").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series[0].avg").value(30));
    }

    // -- B3: DNS/Domain'de dogrulama-kurtarma + kanal bayraklari -------------
    // Bu iki turde confirm/recovery alanlari HIC yoktu (tek anlik hata dogrudan alarm aciyordu)
    // ve createDns kanal bayraklarini HIC okumuyordu -- "Kopyala" ile e-posta KAPALI bir izlemenin
    // kopyasi ACIK doguyordu.

    @Test
    @DisplayName("B3: createDns dogrulama/kurtarma alanlarini VE kanal bayraklarini KAYDEDER")
    void createDns_persistsConfirmAndChannelFlags() throws Exception {
        when(dnsMonitorRepo.findFirstByDomainAndRecordTypeAndStandaloneTrue(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(dnsMonitorRepo.save(any())).thenAnswer(inv -> {
            com.sitemonitor.model.DnsMonitor d = inv.getArgument(0);
            if (d.getId() == null) d.setId(99L);
            return d;
        });

        mvc.perform(post("/api/monitoring/dns").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"kayit.example.com\",\"recordType\":\"A\",\"teamId\":3,"
                        + "\"notifyEmail\":false,\"notifyWebhook\":false,"
                        + "\"confirmAttempts\":5,\"confirmIntervalSeconds\":45,"
                        + "\"recoveryChecks\":2,\"recoveryIntervalSeconds\":15}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.DnsMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.DnsMonitor.class);
        verify(dnsMonitorRepo).save(cap.capture());
        var saved = cap.getValue();
        assertThat(saved.getNotifyEmail()).isFalse();
        assertThat(saved.getNotifyWebhook()).isFalse();
        assertThat(saved.getConfirmAttempts()).isEqualTo(5);
        assertThat(saved.getConfirmIntervalSeconds()).isEqualTo(45);
        assertThat(saved.getRecoveryChecks()).isEqualTo(2);
        assertThat(saved.getRecoveryIntervalSeconds()).isEqualTo(15);
    }

    @Test
    @DisplayName("B3: gonderilmeyen alanlar VARSAYILANDA kalir (3/30/3/30) - sessiz sifirlama yok")
    void createDns_absentFields_keepDefaults() throws Exception {
        when(dnsMonitorRepo.findFirstByDomainAndRecordTypeAndStandaloneTrue(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(dnsMonitorRepo.save(any())).thenAnswer(inv -> {
            com.sitemonitor.model.DnsMonitor d = inv.getArgument(0);
            if (d.getId() == null) d.setId(98L);
            return d;
        });

        mvc.perform(post("/api/monitoring/dns").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"vars.example.com\",\"recordType\":\"A\",\"teamId\":3}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.DnsMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.DnsMonitor.class);
        verify(dnsMonitorRepo).save(cap.capture());
        var saved = cap.getValue();
        assertThat(saved.getConfirmAttempts()).isEqualTo(3);
        assertThat(saved.getRecoveryChecks()).isEqualTo(3);
        assertThat(saved.getNotifyEmail()).isTrue();
        assertThat(saved.getNotifyWebhook()).isTrue();
    }

    // ── D: envanter-türevi monitörde KİMLİK değişimi ───────────────────────────
    //
    // listPort/listDns envanter-türevi monitörü host:port (DNS'te domain) DEĞERİNİN KENDİSİYLE
    // eşleştiriyor. Kullanıcı hedefi değiştirdiğinde o anahtar kaybolur; satır ne envanter
    // döngüsünde ne standalone listesinde yer alır → EKRANDAN TAMAMEN KAYBOLUR, üstelik liste
    // envanter alan adını eksik sanıp ESKİ hedefle yeni bir monitör üretir. Kullanıcıya
    // "kaydettim, eski haline döndü" olarak görünen sessiz veri kaybı buydu.

    private com.sitemonitor.model.PortMonitor inventoryPort(long id, String host, Long teamId) {
        com.sitemonitor.model.PortMonitor m = new com.sitemonitor.model.PortMonitor();
        m.setId(id); m.setHost(host); m.setPort(443); m.setTeamId(teamId);
        m.setActive(true); m.setStandalone(false);   // envanter-türevi
        return m;
    }

    @Test
    @DisplayName("PUT /port/{id}: host değişince envanter bağı kopar (standalone=true) ve yanıt bunu bildirir")
    void updatePort_hostChanged_detachesFromInventory() throws Exception {
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(inventoryPort(1L, "eski.example.com", 3L)));
        when(portMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/port/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"yeni.example.com\",\"port\":443,\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.host").value("yeni.example.com"))
                .andExpect(jsonPath("$.data.standalone").value(true))
                .andExpect(jsonPath("$.data.detached_from_inventory").value(true));

        org.mockito.ArgumentCaptor<com.sitemonitor.model.PortMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PortMonitor.class);
        verify(portMonitorRepo).save(cap.capture());
        assertThat(cap.getValue().getHost()).isEqualTo("yeni.example.com");
        assertThat(cap.getValue().getStandalone()).isTrue();
    }

    @Test
    @DisplayName("PUT /port/{id}: yalnız port değişse de bağ kopar (anahtar host:port'un TAMAMI)")
    void updatePort_portChanged_detachesFromInventory() throws Exception {
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(inventoryPort(1L, "a.example.com", 3L)));
        when(portMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/port/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"port\":8443,\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detached_from_inventory").value(true));
    }

    @Test
    @DisplayName("PUT /port/{id}: kimlik DEĞİŞMEDEN yapılan düzenleme envanter bağını KOPARMAZ")
    void updatePort_identityUnchanged_keepsInventoryLink() throws Exception {
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(inventoryPort(1L, "a.example.com", 3L)));
        when(portMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/port/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"a.example.com\",\"port\":443,\"intervalSeconds\":600,\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.standalone").value(false))
                .andExpect(jsonPath("$.data.detached_from_inventory").value(false));
    }

    @Test
    @DisplayName("PUT /port/{id}: zaten standalone olan monitörde koptu bildirimi ÜRETİLMEZ")
    void updatePort_alreadyStandalone_noDetachNotice() throws Exception {
        com.sitemonitor.model.PortMonitor m = inventoryPort(1L, "a.example.com", 3L);
        m.setStandalone(true);   // kullanıcı-eklediği monitör
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(m));
        when(portMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/port/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"b.example.com\",\"port\":443,\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.standalone").value(true))
                .andExpect(jsonPath("$.data.detached_from_inventory").value(false));
    }

    @Test
    @DisplayName("GET /port: koparılmış monitör listede DURUR; envanter alan adı ayrıca yeniden türetilir")
    void listPort_detachedMonitorStaysVisible() throws Exception {
        // Düzeltmeden SONRAKİ dünya: düzenlenen satır standalone damgalı.
        com.sitemonitor.model.PortMonitor edited = inventoryPort(1L, "yeni.example.com", 3L);
        edited.setStandalone(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("eski.example.com")));
        when(portMonitorRepo.findAll()).thenReturn(List.of(edited));
        when(portMonitorRepo.findByStandaloneTrueAndActiveTrue()).thenReturn(List.of(edited));
        when(portMonitorRepo.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(portCheckRepo.findLatestPerMonitor()).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/port").session(session("ADMIN")))
                .andExpect(status().isOk())
                // envanter alan adı için yeniden türetilen satır + kullanıcının düzenlediği satır
                .andExpect(jsonPath("$.data[?(@.host=='eski.example.com')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.host=='yeni.example.com')]").isNotEmpty());
    }

    @Test
    @DisplayName("GET /port: standalone damgası OLMAYAN, envanterle eşleşmeyen monitör görünmez — damga bu yüzden şart")
    void listPort_undetachedOrphanIsInvisible() throws Exception {
        // Düzeltme OLMASAYDI oluşacak durum: host değişmiş ama satır hâlâ envanter-türevi sayılıyor.
        com.sitemonitor.model.PortMonitor orphan = inventoryPort(1L, "yeni.example.com", 3L);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("eski.example.com")));
        when(portMonitorRepo.findAll()).thenReturn(List.of(orphan));
        when(portMonitorRepo.findByStandaloneTrueAndActiveTrue()).thenReturn(List.of());
        when(portMonitorRepo.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(portCheckRepo.findLatestPerMonitor()).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/port").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.host=='yeni.example.com')]").isEmpty());
    }

    @Test
    @DisplayName("PUT /dns/{id}: domain değişince envanter bağı kopar (standalone=true)")
    void updateDns_domainChanged_detachesFromInventory() throws Exception {
        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(1L); m.setDomain("eski.example.com"); m.setRecordType("A");
        m.setTeamId(3L); m.setActive(true); m.setStandalone(false);
        when(dnsMonitorRepo.findById(1L)).thenReturn(Optional.of(m));
        when(dnsMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/dns/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"domain\":\"yeni.example.com\",\"recordType\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("yeni.example.com"))
                .andExpect(jsonPath("$.data.standalone").value(true))
                .andExpect(jsonPath("$.data.detached_from_inventory").value(true));
    }

    @Test
    @DisplayName("PUT /dns/{id}: domain DEĞİŞMEDEN yapılan düzenleme envanter bağını KOPARMAZ")
    void updateDns_domainUnchanged_keepsInventoryLink() throws Exception {
        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(1L); m.setDomain("a.example.com"); m.setRecordType("A");
        m.setTeamId(3L); m.setActive(true); m.setStandalone(false);
        when(dnsMonitorRepo.findById(1L)).thenReturn(Optional.of(m));
        when(dnsMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/dns/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"domain\":\"a.example.com\",\"recordType\":\"A\",\"intervalSeconds\":600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.standalone").value(false))
                .andExpect(jsonPath("$.data.detached_from_inventory").value(false));
    }

    // ── Denetim 5. tur, bulgu 1: envanter satırları takım kapsamına tabi ───────
    //
    // listPort/listDns'in envanter döngüsü hiç süzmüyordu: monitoring.read yetkisi olan HERKES
    // tüm envanter alan adlarını (host:port, takım adı, son kontrol, açık alarm) görüyordu —
    // oysa AYNI metodun standalone döngüsü ve diğer yedi liste ucu süzüyordu.

    /** viewTeamIds ile kapsamlı USER oturumu — SessionScope.canView bu listeye bakar. */
    private MockHttpSession sessionScoped(Long... teamIds) {
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(teamIds));
        return s;
    }

    private static CertificateInventory invT(String domain, Long teamId) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setActive(true); i.setTeamId(teamId);
        return i;
    }

    @Test
    @DisplayName("GET /port: BAŞKA takımın envanter satırı listede GÖRÜNMEZ (IDOR)")
    void listPort_hidesOtherTeamsInventory() throws Exception {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc())
                .thenReturn(List.of(invT("benim.example.com", 1L), invT("baskasi.example.com", 99L)));
        when(portMonitorRepo.findAll()).thenReturn(List.of());
        when(portMonitorRepo.findByStandaloneTrueAndActiveTrue()).thenReturn(List.of());
        when(portMonitorRepo.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(portCheckRepo.findLatestPerMonitor()).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/port").session(sessionScoped(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.host=='benim.example.com')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.host=='baskasi.example.com')]").isEmpty());
    }

    @Test
    @DisplayName("GET /dns: BAŞKA takımın envanter satırı listede GÖRÜNMEZ (IDOR)")
    void listDns_hidesOtherTeamsInventory() throws Exception {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc())
                .thenReturn(List.of(invT("benim.example.com", 1L), invT("baskasi.example.com", 99L)));
        when(dnsMonitorRepo.findAll()).thenReturn(List.of());
        when(dnsMonitorRepo.findByStandaloneTrueAndActiveTrue()).thenReturn(List.of());
        when(dnsMonitorRepo.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(dnsRecordRepo.findLatestPerMonitor()).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/dns").session(sessionScoped(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.domain=='benim.example.com')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.domain=='baskasi.example.com')]").isEmpty());
    }

    @Test
    @DisplayName("GET /port: UG (uç gözetim) takımı da görebilir — kapı iki takımı da sayar")
    void listPort_ugTeamCanView() throws Exception {
        CertificateInventory inv = invT("ug.example.com", 99L);
        inv.setUgTeamId(1L);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        when(portMonitorRepo.findAll()).thenReturn(List.of());
        when(portMonitorRepo.findByStandaloneTrueAndActiveTrue()).thenReturn(List.of());
        when(portMonitorRepo.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(portCheckRepo.findLatestPerMonitor()).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/port").session(sessionScoped(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.host=='ug.example.com')]").isNotEmpty());
    }

    @Test
    @DisplayName("GET /port: global görüş (ADMIN) tüm envanteri görmeye DEVAM eder")
    void listPort_adminStillSeesAll() throws Exception {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc())
                .thenReturn(List.of(invT("a.example.com", 1L), invT("b.example.com", 99L)));
        when(portMonitorRepo.findAll()).thenReturn(List.of());
        when(portMonitorRepo.findByStandaloneTrueAndActiveTrue()).thenReturn(List.of());
        when(portMonitorRepo.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(portCheckRepo.findLatestPerMonitor()).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/port").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.host=='a.example.com')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.host=='b.example.com')]").isNotEmpty());
    }

    // ── Denetim 5. tur, bulgu 17 + 18: kimlik karşılaştırması ve mükerrer ──────

    @Test
    @DisplayName("Bulgu 17: yalnız HARF KASASI değişimi envanter bağını KOPARMAZ")
    void updatePort_caseOnlyChange_keepsInventoryLink() throws Exception {
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(inventoryPort(1L, "a.Example.com", 3L)));
        when(portMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(portMonitorRepo.findFirstByHostAndPortOrderByIdAsc(anyString(), anyInt())).thenReturn(Optional.empty());

        mvc.perform(put("/api/monitoring/port/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"a.example.com\",\"port\":443,\"teamId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detached_from_inventory").value(false))
                .andExpect(jsonPath("$.data.standalone").value(false));
    }

    @Test
    @DisplayName("Bulgu 17: DNS'te de harf kasası değişimi bağı koparmaz")
    void updateDns_caseOnlyChange_keepsInventoryLink() throws Exception {
        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(1L); m.setDomain("a.Example.com"); m.setRecordType("A");
        m.setTeamId(3L); m.setActive(true); m.setStandalone(false);
        when(dnsMonitorRepo.findById(1L)).thenReturn(Optional.of(m));
        when(dnsMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/dns/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"domain\":\"a.example.com\",\"recordType\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detached_from_inventory").value(false));
    }

    @Test
    @DisplayName("Bulgu 18: PUT /port/{id} host:port'u BAŞKA aktif monitörünkiyle aynı yapamaz")
    void updatePort_rejectsDuplicateHostPort() throws Exception {
        com.sitemonitor.model.PortMonitor other = inventoryPort(2L, "baska.example.com", 3L);
        other.setStandalone(true);
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(inventoryPort(1L, "benim.example.com", 3L)));
        when(portMonitorRepo.existsByHostAndPortAndActiveTrueAndIdNot("baska.example.com", 443, 1L))
                .thenReturn(true);

        mvc.perform(put("/api/monitoring/port/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"baska.example.com\",\"port\":443,\"teamId\":3}"))
                .andExpect(status().isBadRequest());

        verify(portMonitorRepo, never()).save(any());
    }

    @Test
    @DisplayName("Bulgu 18: kimlik DEĞİŞMEDEN yapılan düzenleme mükerrer kontrolüne TAKILMAZ")
    void updatePort_sameIdentity_skipsDuplicateCheck() throws Exception {
        when(portMonitorRepo.findById(1L)).thenReturn(Optional.of(inventoryPort(1L, "a.example.com", 3L)));
        when(portMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/port/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"host\":\"a.example.com\",\"port\":443,\"intervalSeconds\":600,\"teamId\":3}"))
                .andExpect(status().isOk());

        // Kendisiyle karşılaştırma sorgusu hiç yapılmamalı (gereksiz DB turu + yanlış 400 riski).
        verify(portMonitorRepo, never()).existsByHostAndPortAndActiveTrueAndIdNot(anyString(), anyInt(), any());
    }

    // ── Denetim 5. tur, bulgu 8: restore YETKI alanini yazamaz ─────────────────
    //
    // updateDns envanter-turevi bir monitor icin requireAdmin isterken restoreChange yalniz
    // canManage istiyor. standalone bu turda MON_FIELDS'e eklendigi icin restore ile
    // yazilabilir hale gelmisti: bir TEAM_ADMIN monitorun YETKI SINIFINI cevirebiliyordu
    // (standalone -> yalniz takim yonetimi + gercek silme kapsami) ve kismi unique index ile
    // cakisip 500 uretebiliyordu.

    @Test
    @DisplayName("Bulgu 8: restore 'standalone' alanini GERI YUKLEMEZ (yetki sinifi degismez)")
    void restoreChange_doesNotWriteStandalone() throws Exception {
        com.sitemonitor.model.DnsMonitor live = new com.sitemonitor.model.DnsMonitor();
        live.setId(4L); live.setDomain("yeni.example.com"); live.setRecordType("A");
        live.setTeamId(3L); live.setActive(true); live.setStandalone(true);   // koparilmis
        when(dnsMonitorRepo.findById(4L)).thenReturn(Optional.of(live));
        when(dnsMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        com.sitemonitor.model.MonitorChangeLog row = new com.sitemonitor.model.MonitorChangeLog();
        row.setId(1L); row.setResourceKind("DNS"); row.setResourceId(4L); row.setSeq(1);
        row.setTeamId(3L);
        // Eski anlik goruntu: envanter-turevi hali.
        row.setSnapshot("{\"domain\":\"eski.example.com\",\"standalone\":false,\"recordType\":\"A\"}");
        when(changeLogRepo.findByResourceKindAndResourceIdAndSeq("DNS", 4L, 1))
                .thenReturn(Optional.of(row));

        mvc.perform(post("/api/monitoring/changes/dns/4/1/restore").session(session("ADMIN")))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.DnsMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.DnsMonitor.class);
        verify(dnsMonitorRepo).save(cap.capture());
        assertThat(cap.getValue().getStandalone())
                .as("standalone geri yuklenirse monitorun yetki sinifi restore ile cevrilebilir")
                .isTrue();
        assertThat(cap.getValue().getDomain())
                .as("kimlik DISI alanlar normal sekilde geri yuklenmeye devam etmeli")
                .isEqualTo("eski.example.com");
    }

    // ── Regresyon turu (2026-08-30): UCUNCU envanter donguu de suzulur ─────────
    //
    // listPort/listDns'e IDOR kapisi eklendi ama uptimeOverview atlandi — ayni veri (host/port,
    // takim adi, SSL bitisi, kesinti sayaclari) oradan akmaya devam ediyordu. Bu, projenin
    // tekrar eden "kanonik zincirin son halkasi" sinifi.

    @Test
    @DisplayName("REGRESYON KAPISI: /uptime/overview BASKA takimin envanterini gostermez")
    void uptimeOverview_hidesOtherTeamsInventory() throws Exception {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc())
                .thenReturn(List.of(invT("benim.example.com", 1L), invT("baskasi.example.com", 99L)));
        when(latestCheckRepo.findAllByOrderByDomainAsc()).thenReturn(List.of());
        when(certCheckRepo.aggregateStatusCountsSince(anyString())).thenReturn(List.of());

        mvc.perform(get("/api/monitoring/uptime/overview").session(sessionScoped(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.domain=='benim.example.com')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.domain=='baskasi.example.com')]").isEmpty());
    }

    @Test
    @DisplayName("REGRESYON KAPISI: envanteri listeleyen UC ucun UCU de ayni kapiyi kullanir")
    void allInventoryLoopsAreScoped() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/sitemonitor/controller/MonitoringController.java"));
        // findByActiveTrueOrderByDomainAsc() cagiran her uc, sonucu inventoryViewable ile
        // suzmeli. Sayilar degisebilir; kural "her cagrinin yakininda suzgec var" olmali.
        int calls = countOccurrences(src, "inventoryRepo.findByActiveTrueOrderByDomainAsc()");
        int filters = countOccurrences(src, "inventoryViewable(session, inv)");
        assertThat(filters)
                .as("envanter listeleyen %d ucun hepsi suzulmeli; suzgec sayisi: %d", calls, filters)
                .isGreaterThanOrEqualTo(3);
    }

    /** Duz metin sayimi — regex kacislari kaynakta gurultu yaratmasin. */
    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    @Test
    @DisplayName("KAPI: teyit/kurtarma alanlari SUNUCUDA kirpilir — sinir yalniz formda kalmaz")
    void confirmFieldsAreClampedServerSide() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/sitemonitor/controller/MonitoringController.java"));
        // Ham setter (kirpmasiz) yasak: API'ye dogrudan confirmIntervalSeconds=2000000000
        // gonderilirse teyit zinciri pratikte sonsuza ertelenir ve o izlemenin kesinti alarmi
        // HIC acilmaz — hicbir hata satiri da dusmez. Dokuz turde tek yardimci seti.
        java.util.List<String> raw = new java.util.ArrayList<>();
        for (String field : List.of("setConfirmAttempts", "setConfirmIntervalSeconds",
                                    "setRecoveryChecks", "setRecoveryIntervalSeconds")) {
            if (src.contains(field + "(cn.intValue())")) raw.add(field);
            if (src.contains(field + "(((Number) body.get")) raw.add(field + " (cast)");
        }
        assertThat(raw).as("kirpmasiz teyit/kurtarma setter'i").isEmpty();
    }

    // ── DNS silme kapısı ve semantiği (kod incelemesi B1 + B3) ────────────────────────────

    /** Takımı 5 olan standalone DNS izlemesi; sessions aşağıda o takıma bağlanır. */
    private com.sitemonitor.model.DnsMonitor standaloneDns(long id) {
        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(id); m.setDomain("own.example.com"); m.setRecordType("A");
        m.setTeamId(5L); m.setStandalone(true); m.setActive(true);
        return m;
    }

    private MockHttpSession teamSession(String role, Long teamId, Long... manageTeams) {
        MockHttpSession s = session(role);
        s.setAttribute("teamId", teamId);
        s.setAttribute("manageTeamIds", java.util.List.of(manageTeams));
        s.setAttribute("viewTeamIds", java.util.List.of(teamId));
        return s;
    }

    @Test
    @DisplayName("DELETE /dns/{id}: sıradan USER kendi standalone izlemesini SİLEMEZ (kardeş politikası)")
    void deleteDns_plainUser_forbidden() throws Exception {
        // Kardeşlerin YEDİSİ silmede canManage kullanıyor (deleteKeyword/Http/Ping/...); arayüz de
        // "silme: TEAM_ADMIN/ADMIN" diyor. dba22f1a bunu canOperateTeam'e çekmişti ve sıradan USER
        // kendi takımı için geçiyordu — silme sütununu 9 türün 7'sinden ayıran tek yer orasıydı.
        when(dnsMonitorRepo.findById(31L)).thenReturn(java.util.Optional.of(standaloneDns(31L)));

        mvc.perform(delete("/api/monitoring/dns/31").session(teamSession("USER", 5L)))
                .andExpect(status().isForbidden());

        verify(dnsMonitorRepo, never()).save(any());
        verify(dnsMonitorRepo, never()).delete(any());
    }

    @Test
    @DisplayName("DELETE /dns/{id}: TEAM_ADMIN siler — ama KALICI DEĞİL, satır pasifleşir")
    void deleteDns_teamAdmin_deactivatesInsteadOfHardDelete() throws Exception {
        // B3: eskiden standalone dalı dnsMonitorRepo.delete(m) çağırıyordu. standalone, isteği
        // yapanın AYNI akışta çevirebildiği bir alan (updateDns → detachIfIdentityChanged), yani
        // iki çağrı geri alınabilir bir duraklatmayı KALICI silmeye yükseltiyordu.
        com.sitemonitor.model.DnsMonitor m = standaloneDns(32L);
        when(dnsMonitorRepo.findById(32L)).thenReturn(java.util.Optional.of(m));
        when(dnsMonitorRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(delete("/api/monitoring/dns/32").session(teamSession("TEAM_ADMIN", 5L, 5L)))
                .andExpect(status().isOk());

        verify(dnsMonitorRepo, never()).delete(any());   // satır TABLODA KALIR
        org.mockito.ArgumentCaptor<com.sitemonitor.model.DnsMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.DnsMonitor.class);
        verify(dnsMonitorRepo).save(cap.capture());
        assertThat(cap.getValue().getActive()).isFalse();
    }

    @Test
    @DisplayName("POST /dns: PASİFLEŞTİRİLMİŞ domain+tip yeniden eklenebilir (satır canlandırılır)")
    void createDns_revivesDeactivatedRow() throws Exception {
        // Silme artık pasifleştirme olduğu için satır tabloda kalıyor. Mükerrer guard "aktif mi"
        // diye bakmasaydı kullanıcı sildiği domain'i bir daha ekleyemez ve sebebini anlamadığı bir
        // "zaten var" hatası alırdı. Yeni satır DEĞİL canlandırma: standalone'da uq_dnsm_domain yok.
        com.sitemonitor.model.DnsMonitor dead = standaloneDns(33L);
        dead.setActive(false);
        dead.setCreatedAt("2026-01-01T00:00:00");
        when(dnsMonitorRepo.findFirstByDomainAndRecordTypeAndStandaloneTrue("own.example.com", "A"))
                .thenReturn(java.util.Optional.of(dead));
        when(dnsMonitorRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/monitoring/dns").session(teamSession("TEAM_ADMIN", 5L, 5L))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"domain\":\"own.example.com\",\"recordType\":\"A\",\"teamId\":5}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.sitemonitor.model.DnsMonitor> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.DnsMonitor.class);
        verify(dnsMonitorRepo).save(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(33L);              // AYNI satır
        assertThat(cap.getValue().getActive()).isTrue();                 // canlandı
        assertThat(cap.getValue().getCreatedAt()).isEqualTo("2026-01-01T00:00:00");  // özgün tarih korundu
    }

    @Test
    @DisplayName("Envanter TRANSFERİNDEN sonra türev DNS'i YENİ takım yönetir, ESKİ takım yönetemez")
    void updateDns_derivedRow_followsInventoryTeam() throws Exception {
        // B2: türev satırın team_id'si lazy-provision'da kopyalanır ve bir daha tazelenmez.
        // effectiveTeam olmasaydı: satır ekranda YENİ takımın adıyla görünür ama yetki ESKİ
        // takımda kalırdı — yeni takım kendi kaydını düzenleyemez, eski takım listede görmediği
        // satırı yönetmeye devam ederdi.
        com.sitemonitor.model.DnsMonitor derived = new com.sitemonitor.model.DnsMonitor();
        derived.setId(34L); derived.setDomain("moved.example.com"); derived.setRecordType("A");
        derived.setTeamId(5L);              // BAYAT: transfer öncesi takım
        derived.setStandalone(false);
        derived.setActive(true);
        when(dnsMonitorRepo.findById(34L)).thenReturn(java.util.Optional.of(derived));
        when(dnsMonitorRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CertificateInventory moved = inv("moved.example.com");
        moved.setTeamId(9L);                // envanter ARTIK 9 numaralı takımda
        when(inventoryRepo.findByDomain("moved.example.com")).thenReturn(java.util.Optional.of(moved));

        // Yeni takım (9) düzenleyebilir
        mvc.perform(put("/api/monitoring/dns/34").session(teamSession("TEAM_ADMIN", 9L, 9L))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"yeni ad\"}"))
                .andExpect(status().isOk());

        // Eski takım (5) artık düzenleyemez
        mvc.perform(put("/api/monitoring/dns/34").session(teamSession("TEAM_ADMIN", 5L, 5L))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"eski takim\"}"))
                .andExpect(status().isForbidden());
    }

    // ── Kod incelemesi 2026-09-09 ─────────────────────────────────────────────

    @Test
    @DisplayName("PUT /http/{id} active:false (duraklatma) açık alarmları SESSİZCE kapatır — envanterin closeAlertsOnDeactivate eşleniği")
    void pauseHttpMonitor_closesOpenAlertsSilently() throws Exception {
        var m = new com.sitemonitor.model.HttpMonitor();
        m.setId(1L); m.setUrl("https://pause.example.com/"); m.setActive(true); m.setTeamId(3L);
        when(httpMonitorRepo.findById(1L)).thenReturn(Optional.of(m));
        when(httpMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/http/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
                .andExpect(status().isOk());

        verify(escalationService).resolveOpenAlertsSilently(eq("https://pause.example.com/"), anySet(), contains("duraklat"));
    }

    @Test
    @DisplayName("PUT /http/{id} active:true (zaten aktif) alarm kapatmaz")
    void updateHttpMonitor_stayActive_noAlertClose() throws Exception {
        var m = new com.sitemonitor.model.HttpMonitor();
        m.setId(1L); m.setUrl("https://stay.example.com/"); m.setActive(true); m.setTeamId(3L);
        when(httpMonitorRepo.findById(1L)).thenReturn(Optional.of(m));
        when(httpMonitorRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/monitoring/http/1").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
                .andExpect(status().isOk());

        verify(escalationService, never()).resolveOpenAlertsSilently(any(), anySet(), any());
    }

    @Test
    @DisplayName("POST /port host'u küçük harfe normalize eder (envanter anahtarıyla aynı; mükerrer guard harf-duyarsız)")
    void createPort_lowercasesHost() throws Exception {
        when(portMonitorRepo.save(any(com.sitemonitor.model.PortMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.PortMonitor p = a.getArgument(0); p.setId(9L); return p; });
        mvc.perform(post("/api/monitoring/port").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"host\":\"MAIL.Example.com\",\"port\":25,\"teamId\":3}"))
                .andExpect(status().isOk());
        verify(portMonitorRepo).existsByHostAndPortAndActiveTrue("mail.example.com", 25);
        org.mockito.ArgumentCaptor<com.sitemonitor.model.PortMonitor> cap = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PortMonitor.class);
        verify(portMonitorRepo).save(cap.capture());
        assertThat(cap.getValue().getHost()).isEqualTo("mail.example.com");
    }

    // ── Çok takımlı kullanıcı izleme eklerken takımını seçebilir (2026-09-18, kullanıcı isteği) ──
    // Eskiden canOperateTeam USER için yalnız BİRİNCİL takımı kabul ediyor, ikincil takım istendiğinde
    // resolveWriteTeam SESSİZCE birincile düşüyordu — kullanıcı "X takımına ekledim" sanıp izlemeyi Y'de
    // buluyordu. Artık üyesi olduğu her takım kabul; üye olmadığı takım 403.

    private MockHttpSession multiTeamUser(Long primary, Long... members) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", primary);
        s.setAttribute("viewTeamIds", java.util.List.of(members));
        s.setAttribute("memberTeamIds", java.util.List.of(members));
        return s;
    }

    @Test
    @DisplayName("POST /http çok takımlı: ikincil takım (üye) → izleme O takıma yazılır, birincile düşmez")
    void multiTeam_secondaryMemberTeam_isHonoured() throws Exception {
        when(httpMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.HttpMonitor h = a.getArgument(0); h.setId(41L); return h; });
        mvc.perform(post("/api/monitoring/http").session(multiTeamUser(1L, 1L, 2L))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://iki.example.com\",\"teamId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(2));
    }

    @Test
    @DisplayName("POST /http çok takımlı: üye OLMADIĞI takım → 403 (sessizce birincile düşmez)")
    void multiTeam_nonMemberTeam_isRejected() throws Exception {
        mvc.perform(post("/api/monitoring/http").session(multiTeamUser(1L, 1L, 2L))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://uc.example.com\",\"teamId\":3}"))
                .andExpect(status().isForbidden());
        verify(httpMonitorRepo, never()).save(any(com.sitemonitor.model.HttpMonitor.class));
    }

    @Test
    @DisplayName("POST /http çok takımlı: teamId gönderilmezse (eski istemci) birincil takım — geriye uyum")
    void multiTeam_noTeamId_fallsBackToPrimary() throws Exception {
        when(httpMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.HttpMonitor h = a.getArgument(0); h.setId(42L); return h; });
        mvc.perform(post("/api/monitoring/http").session(multiTeamUser(1L, 1L, 2L))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://bir.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(1));
    }

    @Test
    @DisplayName("POST /http eski oturum (memberTeamIds YOK): birincil takım yine kabul — rolling deploy geri düşüşü")
    void multiTeam_legacySession_primaryStillWorks() throws Exception {
        when(httpMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.HttpMonitor h = a.getArgument(0); h.setId(43L); return h; });
        MockHttpSession legacy = new MockHttpSession();
        legacy.setAttribute("authenticated", Boolean.TRUE);
        legacy.setAttribute("username", "u");
        legacy.setAttribute("systemRole", "USER");
        legacy.setAttribute("teamId", 1L);
        legacy.setAttribute("viewTeamIds", java.util.List.of(1L));
        mvc.perform(post("/api/monitoring/http").session(legacy)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://eski.example.com\",\"teamId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_id").value(1));
    }

    // ── Grup + etiket zorunlu (2026-09-18, ürün kararı): dokuz türün HEPSİNDE oluşturma kapısı ──
    // Tek test, tür başına bir istek: kapı permissionService.require'ın hemen ardında, tür-özel
    // doğrulamalardan ÖNCE koşar; gövdeye yalnız hedef alanı koymak yeter.
    @org.junit.jupiter.params.ParameterizedTest(name = "POST /{0}: grup yoksa 400")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http", "domain", "port", "dns", "keyword", "ping", "page", "pagespeed", "scripted"})
    void create_missingGroup_returns400(String type) throws Exception {
        mvc.perform(post("/api/monitoring/" + type).session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"tags\":\"t1\",\"name\":\"x\",\"url\":\"https://x.example.com\",\"host\":\"x.example.com\",\"domain\":\"x.example.com\",\"port\":443,\"recordType\":\"A\",\"keyword\":\"k\",\"teamId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Grup")));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "POST /{0}: etiket yoksa 400")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http", "domain", "port", "dns", "keyword", "ping", "page", "pagespeed", "scripted"})
    void create_missingTags_returns400(String type) throws Exception {
        mvc.perform(post("/api/monitoring/" + type).session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"name\":\"x\",\"url\":\"https://x.example.com\",\"host\":\"x.example.com\",\"domain\":\"x.example.com\",\"port\":443,\"recordType\":\"A\",\"keyword\":\"k\",\"teamId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("etiket")));
    }

    @Test
    @DisplayName("PUT /http: grup/etiket anahtarı HİÇ gönderilmezse (kısmi PUT) kapı devreye girmez")
    void update_partialBodyWithoutGroupOrTags_isAccepted() throws Exception {
        com.sitemonitor.model.HttpMonitor m = new com.sitemonitor.model.HttpMonitor();
        m.setId(5L); m.setUrl("https://x.example.com"); m.setTeamId(1L); m.setGroupName("Grup A"); m.setTags("t1"); m.setActive(true);
        when(httpMonitorRepo.findById(5L)).thenReturn(Optional.of(m));
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class))).thenAnswer(a -> a.getArgument(0));
        mvc.perform(put("/api/monitoring/http/5").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
                .andExpect(status().isOk());
        assertThat(m.getGroupName()).isEqualTo("Grup A");
        assertThat(m.getTags()).isEqualTo("t1");
    }

    @Test
    @DisplayName("PUT /http: etiket BOŞ gönderilirse 400 — mevcut etiket silinmez")
    void update_blankTags_returns400() throws Exception {
        com.sitemonitor.model.HttpMonitor m = new com.sitemonitor.model.HttpMonitor();
        m.setId(6L); m.setUrl("https://x.example.com"); m.setTeamId(1L); m.setGroupName("Grup A"); m.setTags("t1"); m.setActive(true);
        when(httpMonitorRepo.findById(6L)).thenReturn(Optional.of(m));
        mvc.perform(put("/api/monitoring/http/6").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(httpMonitorRepo, never()).save(any(com.sitemonitor.model.HttpMonitor.class));
        assertThat(m.getTags()).isEqualTo("t1");
    }

    @Test
    @DisplayName("POST /domain + /ping + /dns: tags artık kalıcı ve yanıtta döner (eskiden alan yoktu)")
    void createDomainPingDns_tagsPersistedAndReturned() throws Exception {
        when(domainMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(publicSuffixService.registrableDomain(anyString())).thenReturn("x.example.com");
        when(domainMonitorRepo.save(any(com.sitemonitor.model.DomainMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.DomainMonitor d = a.getArgument(0); d.setId(71L); return d; });
        mvc.perform(post("/api/monitoring/domain").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"prod, kritik\",\"domain\":\"x.example.com\",\"teamId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags").value("prod, kritik"));
        when(pingMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(pingMonitorRepo.save(any(com.sitemonitor.model.PingMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.PingMonitor d = a.getArgument(0); d.setId(72L); return d; });
        mvc.perform(post("/api/monitoring/ping").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"edge\",\"host\":\"x.example.com\",\"teamId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags").value("edge"));
    }

    // ── Alarm seviyesi (2026-09-19): formdan seçilir, normalize edilir, yanıtta alert_level döner ──
    @Test
    @DisplayName("POST /http: alertLevel 'high' → HIGH; bilinmeyen/boş → WARNING (varsayılan)")
    void createHttp_alertLevelNormalized() throws Exception {
        when(httpMonitorRepo.existsDuplicate(anyString(), any(), any())).thenReturn(false);
        when(httpMonitorRepo.save(any(com.sitemonitor.model.HttpMonitor.class)))
                .thenAnswer(a -> { com.sitemonitor.model.HttpMonitor h = a.getArgument(0); h.setId(91L); return h; });
        mvc.perform(post("/api/monitoring/http").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://lvl.example.com\",\"teamId\":1,\"alertLevel\":\"high\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alert_level").value("HIGH"));
        mvc.perform(post("/api/monitoring/http").session(session("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"groupName\":\"Grup A\",\"tags\":\"t1\",\"url\":\"https://lvl2.example.com\",\"teamId\":1,\"alertLevel\":\"bogus\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alert_level").value("WARNING"));
    }

    // ── Kalan-gün trendi (2026-09-22, alan adı denetimi madde I) ─────────────────────────────

    private static com.sitemonitor.model.DomainCheck trendCheck(String at, Integer days, boolean changed, String detail) {
        com.sitemonitor.model.DomainCheck c = new com.sitemonitor.model.DomainCheck();
        c.setMonitorId(7L); c.setCheckedAt(at); c.setDaysRemaining(days); c.setStatus("OK");
        c.setExpiryDate("2027-03-28T10:41:00Z"); c.setRegistrar("Registrar A"); c.setSource("RDAP");
        c.setChanged(changed); c.setChangeDetail(detail);
        return c;
    }

    @Test
    @DisplayName("GET /domain/{id}/trend: gün başına SON kontrol, aynı günün iki değişikliği birleşir, eşikler izlemeden")
    void domainTrend_collapsesPerDay_mergesChanges() throws Exception {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(7L); m.setDomain("a.example.com"); m.setTeamId(1L); m.setWarningDays(45); m.setCriticalDays(10);
        when(domainMonitorRepo.findById(7L)).thenReturn(Optional.of(m));
        when(domainCheckRepo.findByMonitorIdAndCheckedAtBetween(eq(7L), anyString(), anyString(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(
                        trendCheck("2026-09-20T01:00:00", 190, false, null),
                        trendCheck("2026-09-20T13:00:00", 189, true, "registrar: A → B;"),
                        trendCheck("2026-09-20T22:00:00", 189, true, "nameserver seti değişti;"),
                        trendCheck("2026-09-21T02:00:00", 188, false, null))));

        mvc.perform(get("/api/monitoring/domain/7/trend?days=30").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(30))
                .andExpect(jsonPath("$.data.warning_days").value(45))
                .andExpect(jsonPath("$.data.critical_days").value(10))
                .andExpect(jsonPath("$.data.points.length()").value(2))
                .andExpect(jsonPath("$.data.points[0].day").value("2026-09-20"))
                .andExpect(jsonPath("$.data.points[0].days_remaining").value(189))     // günün SON kontrolü
                .andExpect(jsonPath("$.data.points[0].checked_at").value("2026-09-20T22:00:00"))
                .andExpect(jsonPath("$.data.points[0].changed").value(true))
                .andExpect(jsonPath("$.data.points[0].change_detail").value("registrar: A → B; nameserver seti değişti;"))
                .andExpect(jsonPath("$.data.points[1].changed").value(false));
    }

    @Test
    @DisplayName("GET /domain/{id}/trend: days 1..730 arasına kırpılır")
    void domainTrend_clampsDays() throws Exception {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(7L); m.setDomain("a.example.com"); m.setTeamId(1L);
        when(domainMonitorRepo.findById(7L)).thenReturn(Optional.of(m));
        when(domainCheckRepo.findByMonitorIdAndCheckedAtBetween(eq(7L), anyString(), anyString(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        mvc.perform(get("/api/monitoring/domain/7/trend?days=99999").session(session("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days").value(730))
                .andExpect(jsonPath("$.data.points.length()").value(0));
        mvc.perform(get("/api/monitoring/domain/7/trend?days=0").session(session("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days").value(1));
    }

    @Test
    @DisplayName("GET /domain/{id}/trend IDOR: başka takımın izlemesi ve olmayan kayıt 404 (varlık sızdırmaz)")
    void domainTrend_foreignTeam_notFound() throws Exception {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(9L); m.setDomain("x.example.com"); m.setTeamId(2L);
        when(domainMonitorRepo.findById(9L)).thenReturn(Optional.of(m));
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));
        mvc.perform(get("/api/monitoring/domain/9/trend").session(s)).andExpect(status().isNotFound());
        when(domainMonitorRepo.findById(10L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/monitoring/domain/10/trend").session(session("ADMIN"))).andExpect(status().isNotFound());
    }

    // ── Süre-bitişi hatırlatmaları (2026-09-22, madde E) ─────────────────────────────

    @Test
    @DisplayName("GET /domain/{id}/reminders: izlemenin eşikleri + gönderilenler; yabancı takım 404")
    void domainReminders_listsThresholdsAndItems() throws Exception {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(7L); m.setDomain("a.example.com"); m.setTeamId(1L); m.setThresholdsCsv("30,7");
        when(domainMonitorRepo.findById(7L)).thenReturn(Optional.of(m));
        when(domainReminders.history(7L)).thenReturn(List.of(java.util.Map.of("id", 1L, "threshold_days", 30, "status", "SENT")));

        mvc.perform(get("/api/monitoring/domain/7/reminders").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thresholds[0]").value(30))
                .andExpect(jsonPath("$.data.thresholds[1]").value(7))
                .andExpect(jsonPath("$.data.items[0].status").value("SENT"));

        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(2L));
        mvc.perform(get("/api/monitoring/domain/7/reminders").session(s)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /domain/{id}/check: elle kontrol de hatırlatma değerlendirmesini tetikler")
    void domainCheck_evaluatesReminders() throws Exception {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(7L); m.setDomain("a.example.com"); m.setTeamId(1L); m.setActive(true);
        when(domainMonitorRepo.findById(7L)).thenReturn(Optional.of(m));
        java.util.Map<String, Object> r = new java.util.HashMap<>(java.util.Map.of("status", "OK", "days_remaining", 12, "expiry_date", "2026-10-04T00:00:00Z"));
        when(domainChecker.check(any())).thenReturn(r);

        mvc.perform(post("/api/monitoring/domain/7/check").session(session("ADMIN"))).andExpect(status().isOk());
        verify(domainReminders).evaluate(eq(m), eq(r));
    }

    // ── Yenileme planı (2026-09-22, madde H) ──────────────────────────────────────────

    @Test
    @DisplayName("POST /domain/{id}/renewal-plan: tarih doğrulanır, servis plan anındaki bitişle çağrılır; satır plan alanlarını döner")
    void domainRenewalPlan_post() throws Exception {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(7L); m.setDomain("a.example.com"); m.setName("A"); m.setTeamId(1L); m.setActive(true);
        when(domainMonitorRepo.findById(7L)).thenReturn(Optional.of(m));
        com.sitemonitor.model.DomainCheck last = new com.sitemonitor.model.DomainCheck();
        last.setMonitorId(7L); last.setSource("RDAP"); last.setExpiryDate("2026-11-22T00:00:00Z"); last.setStatus("OK");
        when(domainCheckRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(7L, "NONE")).thenReturn(Optional.of(last));
        when(domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(7L)).thenReturn(Optional.of(last));
        when(domainRenewalPlans.plan(any(), eq("2026-10-15"), eq("not"), eq("2026-11-22T00:00:00Z"), any())).thenAnswer(i -> {
            com.sitemonitor.model.DomainMonitor x = i.getArgument(0);
            x.setRenewalPlannedAt("2026-10-15"); x.setRenewalPlannedByName("Ops"); x.setRenewalPlannedNote("not");
            return x;
        });

        mvc.perform(post("/api/monitoring/domain/7/renewal-plan").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"date\":\"2026-10-15\",\"note\":\"not\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.renewal_planned_at").value("2026-10-15"))
                .andExpect(jsonPath("$.data.renewal_planned_by").value("Ops"))
                .andExpect(jsonPath("$.data.renewal_overdue").value(false));

        mvc.perform(post("/api/monitoring/domain/7/renewal-plan").session(session("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"date\":\"15.10.2026\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /domain/{id}/renewal-plan: yabancı takım 404; görüntüleyen ama yönetemeyen 403")
    void domainRenewalPlan_scope() throws Exception {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(9L); m.setDomain("x.example.com"); m.setTeamId(2L);
        when(domainMonitorRepo.findById(9L)).thenReturn(Optional.of(m));
        MockHttpSession s = session("USER");
        s.setAttribute("viewTeamIds", java.util.List.of(1L));
        mvc.perform(delete("/api/monitoring/domain/9/renewal-plan").session(s)).andExpect(status().isNotFound());

        MockHttpSession v = sessionWithTeam("USER", 1L);
        v.setAttribute("viewTeamIds", java.util.List.of(1L, 2L));
        mvc.perform(delete("/api/monitoring/domain/9/renewal-plan").session(v)).andExpect(status().isForbidden());
    }
}
