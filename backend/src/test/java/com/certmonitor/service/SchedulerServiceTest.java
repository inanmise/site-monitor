package com.certmonitor.service;

import com.certmonitor.repository.AlertThresholdRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.DnsMonitorRepository;
import com.certmonitor.repository.DnsRecordRepository;
import com.certmonitor.repository.LatestCheckRepository;
import com.certmonitor.repository.NetworkOutageEventRepository;
import com.certmonitor.repository.PortCheckRepository;
import com.certmonitor.repository.PortMonitorRepository;
import com.certmonitor.repository.UptimeCheckRepository;
import com.certmonitor.repository.KeywordMonitorRepository;
import com.certmonitor.repository.KeywordResultRepository;
import com.certmonitor.repository.PingMonitorRepository;
import com.certmonitor.repository.PingCheckRepository;
import com.certmonitor.repository.HttpMonitorRepository;
import com.certmonitor.repository.HttpCheckRepository;
import com.certmonitor.repository.DomainMonitorRepository;
import com.certmonitor.repository.DomainCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Baseline coverage for SchedulerService. The service has a 16-dependency
 * constructor and lots of side-effects, so the full happy-path is exercised by
 * the live application; here we cover the simple, hot-path getters and the
 * forceReleaseLock SQL contract.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    @Mock CertificateCheckerService checkerService;
    @Mock CertificateService certService;
    @Mock EmailNotificationService emailService;
    @Mock EscalationService escalationService;
    @Mock MaintenanceService maintenanceService;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock UserService userService;
    @Mock PermissionService permissionService;
    @Mock DataSource dataSource;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PortCheckerService portCheckerService;
    @Mock PortMonitorRepository portMonitorRepo;
    @Mock PortCheckRepository portCheckRepo;
    @Mock DnsCheckerService dnsCheckerService;
    @Mock DnsMonitorRepository dnsMonitorRepo;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock UptimeHttpCheckerService uptimeHttpCheckerService;
    @Mock UptimeCheckRepository uptimeCheckRepo;
    @Mock MonitoringOutageService monitoringOutageService;
    @Mock KeywordCheckerService keywordCheckerService;
    @Mock KeywordMonitorRepository keywordMonitorRepo;
    @Mock KeywordResultRepository keywordResultRepo;
    @Mock PingCheckerService pingCheckerService;
    @Mock PingMonitorRepository pingMonitorRepo;
    @Mock PingCheckRepository pingCheckRepo;
    @Mock HttpCheckerService httpCheckerService;
    @Mock HttpMonitorRepository httpMonitorRepo;
    @Mock HttpCheckRepository httpCheckRepo;
    @Mock RdapDomainExpiryService rdapDomainExpiryService;
    @Mock ActivityLogService activityLog;
    @Mock AuditService auditService;
    @Mock FailedLoginAnomalyIncidentService failedLoginAnomalyIncidentService;
    @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock DomainCheckRepository domainCheckRepo;
    @Mock DomainCheckerService domainCheckerService;
    @Mock NetworkOutageEventRepository networkOutageRepo;
    @Mock WeeklyReportReminderService weeklyReportReminderService;
    @Mock WeeklyAvailabilityReportService weeklyAvailabilityReportService;
    @Mock IncidentService incidentService;
    @Mock AppSettingsService appSettings;
    @Mock ThreadPoolTaskExecutor certCheckExecutor;
    @Mock DomainExpiryRefreshService domainExpiryRefreshService;

    SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SchedulerService(
                checkerService, certService, emailService, escalationService, maintenanceService,
                inventoryRepo, latestCheckRepo, thresholdRepo, jdbcTemplate,
                userService, permissionService, dataSource, eventPublisher,
                portCheckerService, portMonitorRepo, portCheckRepo,
                dnsCheckerService, dnsMonitorRepo, dnsRecordRepo,
                uptimeHttpCheckerService, uptimeCheckRepo, monitoringOutageService,
                keywordCheckerService, keywordMonitorRepo, keywordResultRepo,
                pingCheckerService, pingMonitorRepo, pingCheckRepo,
                httpCheckerService, httpMonitorRepo, httpCheckRepo, rdapDomainExpiryService, activityLog, auditService,
                failedLoginAnomalyIncidentService,
                domainMonitorRepo, domainCheckRepo, domainCheckerService,
                networkOutageRepo,
                weeklyReportReminderService, weeklyAvailabilityReportService, incidentService, appSettings);
        ReflectionTestUtils.setField(scheduler, "certCheckExecutor", certCheckExecutor);
        ReflectionTestUtils.setField(scheduler, "domainExpiryRefreshService", domainExpiryRefreshService);
        // startNetworkCheck: mock executor task'ı düşürürse join asılı kalır → inline koştur (deterministik).
        lenient().doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
                .when(certCheckExecutor).execute(any(Runnable.class));
        // @Value alanı manuel kurulumda 0 kalır → gerçek varsayılanla doldur (orphan gate testi için).
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
        lenient().when(inventoryRepo.countByActiveTrue()).thenReturn(0L);
        // AppSettings: override yok → fallback (ikinci argüman) döner
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
    }

    @Test
    @DisplayName("safeDeleteBatched: batch<size dönene dek döngü, sonra ANALYZE (toplam doğru)")
    void safeDeleteBatched_loopsUntilDrainedThenAnalyze() {
        // batch = getInt(purge-batch-size, 10000) → 10000 (fallback). Dilimler: 10000, 10000, 3000 → dur.
        when(jdbcTemplate.update(anyString(), (Object[]) any())).thenReturn(10000, 10000, 3000);
        int total = scheduler.safeDeleteBatched("port_checks", "checked_at < ?", "2020-01-01T00:00:00");
        assertThat(total).isEqualTo(23000);
        verify(jdbcTemplate, times(3)).update(anyString(), (Object[]) any());
        verify(jdbcTemplate).execute("ANALYZE port_checks");
    }

    @Test
    @DisplayName("rollupDailyStats: 5 tip (port/ping/keyword/http/uptime) için upsert çalıştırır")
    void rollupDailyStats_runsAllTypes() {
        when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(3);
        scheduler.rollupDailyStats();
        verify(jdbcTemplate, times(5)).update(anyString(), anyString(), anyString());   // 5 monitör tipi (sql, from, to)
    }

    @Test
    @DisplayName("pruneMonitorCheckState: canlı sette olmayan 'type:id' anahtarları atılır, olanlar kalır")
    @SuppressWarnings("unchecked")
    void pruneMonitorCheckState_removesStaleKeys() {
        var map = (java.util.concurrent.ConcurrentHashMap<String, Long>)
                ReflectionTestUtils.getField(scheduler, "lastMonitorCheckAt");
        map.put("http:1", 1L);
        map.put("http:2", 2L);      // silinmiş monitör — canlı sette yok
        map.put("domain:5", 3L);

        int pruned = scheduler.pruneMonitorCheckState(java.util.Set.of("http:1", "domain:5"));

        assertThat(pruned).isEqualTo(1);
        assertThat(map).containsOnlyKeys("http:1", "domain:5");
    }

    @Test
    @DisplayName("isRunning() starts false on a fresh instance")
    void isRunning_initiallyFalse() {
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    @DisplayName("runUptimeChecks dispatches SweepItems to MonitoringOutageService")
    void runUptimeChecks_dispatchesSweep() {
        com.certmonitor.model.CertificateInventory inv = new com.certmonitor.model.CertificateInventory();
        inv.setDomain("x.example.com");
        inv.setPort(443);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        when(uptimeHttpCheckerService.check(eq("x.example.com"), eq(443), anyInt()))
                .thenReturn(Map.of("status", "down", "error", "timeout"));

        scheduler.runUptimeChecks();

        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleSweepResults(
                eq(EscalationService.TYPE_ACCESSIBILITY), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        MonitoringOutageService.SweepItem item = captor.getValue().get(0);
        assertThat(item.domain()).isEqualTo("x.example.com");
        assertThat(item.up()).isFalse();
        assertThat(item.error()).isEqualTo("timeout");
        assertThat(item.detail()).isEqualTo("443");
        // recheck supplier'ı canlı: çağrılınca check + persist yapar
        item.recheck().get();
        verify(uptimeHttpCheckerService, org.mockito.Mockito.times(2)).check(eq("x.example.com"), eq(443), anyInt());
    }

    @Test
    @DisplayName("runUptimeChecks: outage processing failure does not break the sweep")
    void runUptimeChecks_outageFailure_sweepSurvives() {
        com.certmonitor.model.CertificateInventory inv = new com.certmonitor.model.CertificateInventory();
        inv.setDomain("x.example.com");
        inv.setPort(443);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        when(uptimeHttpCheckerService.check(eq("x.example.com"), eq(443), anyInt()))
                .thenReturn(Map.of("status", "up", "response_ms", 12L));
        doThrow(new RuntimeException("boom"))
                .when(monitoringOutageService).handleSweepResults(anyString(), anyList());

        assertThatCode(() -> scheduler.runUptimeChecks()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("F1 paralel sweep: ilk monitörün check exception'ı ikincinin item üretmesini engellemez")
    void portSweep_firstCheckThrows_secondStillProcessed() {
        com.certmonitor.model.PortMonitor a = new com.certmonitor.model.PortMonitor();
        a.setId(11L); a.setHost("a.example.com"); a.setPort(80); a.setProtocol("TCP"); a.setStandalone(true);
        com.certmonitor.model.PortMonitor b = new com.certmonitor.model.PortMonitor();
        b.setId(12L); b.setHost("b.example.com"); b.setPort(81); b.setProtocol("TCP"); b.setStandalone(true);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(a, b));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        when(portCheckerService.check(a)).thenThrow(new RuntimeException("conn boom"));
        when(portCheckerService.check(b)).thenReturn(Map.of("open", true, "response_ms", 5L));

        assertThatCode(() -> scheduler.runPortChecks()).doesNotThrowAnyException();

        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleSweepResults(eq(EscalationService.TYPE_PORT_DOWN), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).domain()).isEqualTo("b.example.com");
    }

    @Test
    @DisplayName("F5: öksüz-alarm temizliği art arda sweep'lerde yalnız 1 kez koşar (5 dk gate)")
    void orphanCleanup_gatedAcrossSweeps() {
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of());
        when(portMonitorRepo.findAll()).thenReturn(List.of());

        scheduler.runPortChecks();
        scheduler.runPortChecks();

        verify(escalationService, org.mockito.Mockito.times(1)).resolveOrphanedPortAlerts(any());
    }

    @Test
    @DisplayName("F4: triggerManualCheck raw Thread yerine certCheckExecutor'a atar")
    void triggerManualCheck_usesExecutor() {
        // Bu testte inline stub'ı devre dışı bırak — runCheck'in kendisi koşmasın, yalnız submit doğrulansın.
        org.mockito.Mockito.doAnswer(inv -> null).when(certCheckExecutor).execute(any(Runnable.class));

        scheduler.triggerManualCheck();

        verify(certCheckExecutor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("runPortChecks dispatches PORT_DOWN SweepItems and skips non-inventory hosts")
    void runPortChecks_dispatchesSweep() {
        com.certmonitor.model.CertificateInventory inv = new com.certmonitor.model.CertificateInventory();
        inv.setDomain("x.example.com");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));

        com.certmonitor.model.PortMonitor inInv = new com.certmonitor.model.PortMonitor();
        inInv.setId(1L); inInv.setHost("x.example.com"); inInv.setPort(8443);
        inInv.setProtocol("TCP"); inInv.setTimeoutMs(5000);
        com.certmonitor.model.PortMonitor notInInv = new com.certmonitor.model.PortMonitor();
        notInInv.setId(2L); notInInv.setHost("ghost.example.com"); notInInv.setPort(80);
        notInInv.setProtocol("TCP"); notInInv.setTimeoutMs(5000);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(inInv, notInInv));
        when(portCheckerService.check(any(com.certmonitor.model.PortMonitor.class)))
                .thenReturn(Map.of("open", false, "error", "refused"));

        scheduler.runPortChecks();

        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleSweepResults(
                eq(EscalationService.TYPE_PORT_DOWN), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        MonitoringOutageService.SweepItem item = captor.getValue().get(0);
        assertThat(item.domain()).isEqualTo("x.example.com");
        assertThat(item.detail()).isEqualTo("8443/TCP");
        assertThat(item.up()).isFalse();
        assertThat(item.ctxExtra().get("port")).isEqualTo(8443);
    }

    @Test
    @DisplayName("runPingChecks: sweep kilidi başka instance'da (UNIQUE) → tüm sweep atlanır, hiç probe/kayıt yok")
    void runPingChecks_lockHeldByOther_skipsSweep() {
        // tryAcquireSchedulerLock: INSERT scheduler_lock UNIQUE ihlali fırlatırsa kilit başka
        // pod'da demektir → false döner → sweep gövdesi (probe + geçmiş kaydı + alarm) hiç koşmaz.
        // 3 ayrı vararg matcher'ı: INSERT'in tam aritesi (name, locked_by, locked_until). lenient()
        // şart: bu sınıf STRICT_STUBS; eşleşmeyen DELETE çağrıları (acquire+release) aksi halde
        // PotentialStubbingProblem fırlatır, onu da tryAcquireSchedulerLock'un catch'i yutup degrade
        // ile true döner (kilit alınmış sayılır). lenient → DELETE'ler varsayılan 0 döner, INSERT atar.
        lenient().when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), any(), any(), any()))
                .thenThrow(new RuntimeException("UNIQUE constraint failed: scheduler_lock.name"));

        scheduler.runPingChecks();

        // Kilit alınamadı → izleme deposuna, checker'a ve alarm pipeline'ına hiç dokunulmaz (mükerrer iş yok).
        org.mockito.Mockito.verifyNoInteractions(pingMonitorRepo, pingCheckerService, monitoringOutageService);
    }

    @Test
    @DisplayName("runDnsChecks: CHANGED only on success vs last successful record; failure emits no change")
    void runDnsChecks_changeDetection_andFailureRegression() {
        com.certmonitor.model.CertificateInventory inv = new com.certmonitor.model.CertificateInventory();
        inv.setDomain("x.example.com");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));

        com.certmonitor.model.DnsMonitor m = new com.certmonitor.model.DnsMonitor();
        m.setId(7L); m.setDomain("x.example.com"); m.setRecordType("A");
        when(dnsMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));

        // 1) Başarılı sorgu + son başarılı kayıt farklı (ayrık) → CHANGED
        com.certmonitor.model.DnsRecord prevOk = new com.certmonitor.model.DnsRecord();
        prevOk.setValue("5.6.7.8");
        when(dnsRecordRepo.findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(7L, ""))
                .thenReturn(java.util.Optional.of(prevOk));
        when(dnsCheckerService.check("x.example.com", "A"))
                .thenReturn(Map.of("success", true, "values", List.of("1.2.3.4")));

        scheduler.runDnsChecks();

        org.mockito.ArgumentCaptor<List<MonitoringOutageService.DnsChange>> changeCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleDnsSweep(anyList(), anyList(), changeCaptor.capture(), anyList(), anyList());
        assertThat(changeCaptor.getValue()).hasSize(1);
        assertThat(changeCaptor.getValue().get(0).previousValue()).isEqualTo("5.6.7.8");
        assertThat(changeCaptor.getValue().get(0).newValue()).isEqualTo("1.2.3.4");

        // 2) Regresyon: başarısız sorgu → DnsChange YOK, kayıt changed=false
        org.mockito.Mockito.reset(monitoringOutageService, dnsRecordRepo);
        // İki AYRI sweep'i simüle et: checkDue son-kontrol durumunu temizle (aksi halde 2. çağrı aynı 60s içinde atlanır).
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(scheduler, "lastMonitorCheckAt")).clear();
        when(dnsCheckerService.check("x.example.com", "A"))
                .thenReturn(Map.of("success", false, "error", "no answer"));

        scheduler.runDnsChecks();

        org.mockito.ArgumentCaptor<List<MonitoringOutageService.DnsChange>> changeCaptor2 =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleDnsSweep(anyList(), anyList(), changeCaptor2.capture(), anyList(), anyList());
        assertThat(changeCaptor2.getValue()).isEmpty();
        org.mockito.ArgumentCaptor<com.certmonitor.model.DnsRecord> recCaptor =
                org.mockito.ArgumentCaptor.forClass(com.certmonitor.model.DnsRecord.class);
        verify(dnsRecordRepo).save(recCaptor.capture());
        assertThat(recCaptor.getValue().getChanged()).isFalse();
        // Başarısız sorguda son-başarılı-kayıt lookup'ı bile yapılmaz
        verify(dnsRecordRepo, org.mockito.Mockito.never())
                .findTopByMonitorIdAndValueNotOrderByCheckedAtDesc(anyLong(), anyString());
    }

    @Test
    @DisplayName("runDnsChecks: standalone monitör envanterde olmasa da kontrol edilir + ctx team_id taşır")
    void runDnsChecks_standaloneBypassesInventorySkip() {
        // Envanter BOŞ — domain envanterde yok; envanter-türevi olsa atlanırdı.
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());

        com.certmonitor.model.DnsMonitor m = new com.certmonitor.model.DnsMonitor();
        m.setId(42L); m.setDomain("standalone.example.com"); m.setRecordType("A");
        m.setStandalone(true); m.setTeamId(9L);
        when(dnsMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(dnsCheckerService.check("standalone.example.com", "A"))
                .thenReturn(Map.of("success", true, "values", List.of("1.2.3.4"), "response_ms", 10L));

        scheduler.runDnsChecks();

        // Atlanmadı: DnsRecord yazıldı.
        verify(dnsRecordRepo).save(org.mockito.ArgumentMatchers.any(com.certmonitor.model.DnsRecord.class));
        // Failure sweep'te yer aldı, up=true (başarılı) ve ctx team_id taşıyor → alarm takıma yönlenir.
        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> failCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleDnsSweep(failCaptor.capture(), anyList(), anyList(), anyList(), anyList());
        assertThat(failCaptor.getValue()).hasSize(1);
        MonitoringOutageService.SweepItem fail = failCaptor.getValue().get(0);
        assertThat(fail.domain()).isEqualTo("standalone.example.com");
        assertThat(fail.up()).isTrue();
        assertThat(fail.ctxExtra().get("team_id")).isEqualTo(9L);
    }

    @Test
    @DisplayName("runDnsChecks: beklenmeyen değer (expectedValue kilidi) → DNS_UNEXPECTED sweep down + team_id")
    void runDnsChecks_unexpectedValue() {
        com.certmonitor.model.CertificateInventory inv = new com.certmonitor.model.CertificateInventory();
        inv.setDomain("locked.example.com");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));

        com.certmonitor.model.DnsMonitor m = new com.certmonitor.model.DnsMonitor();
        m.setId(21L); m.setDomain("locked.example.com"); m.setRecordType("A"); m.setTeamId(3L);
        m.setExpectedValue("1.2.3.4\n5.6.7.8");
        when(dnsMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(dnsCheckerService.check("locked.example.com", "A"))
                .thenReturn(Map.of("success", true, "values", List.of("9.9.9.9"), "response_ms", 5L));

        scheduler.runDnsChecks();

        // handleDnsSweep'in 4. argümanı (unexpectedItems): down + ctx unexpected/team
        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> unexpCap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleDnsSweep(anyList(), anyList(), anyList(), unexpCap.capture(), anyList());
        assertThat(unexpCap.getValue()).hasSize(1);
        MonitoringOutageService.SweepItem it = unexpCap.getValue().get(0);
        assertThat(it.domain()).isEqualTo("locked.example.com");
        assertThat(it.up()).isFalse();   // beklenmeyen değer çözümleniyor → down
        assertThat(it.ctxExtra().get("team_id")).isEqualTo(3L);
        @SuppressWarnings("unchecked")
        List<String> unexp = (List<String>) it.ctxExtra().get("unexpected_values");
        assertThat(unexp).containsExactly("9.9.9.9");
    }

    @Test
    @DisplayName("runDnsChecks: propagationCheck açık + resolver'lar tutarsız → DNS_INCONSISTENT sweep down + team_id")
    void runDnsChecks_propagationInconsistent() {
        com.certmonitor.model.CertificateInventory inv = new com.certmonitor.model.CertificateInventory();
        inv.setDomain("prop.example.com");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));

        com.certmonitor.model.DnsMonitor m = new com.certmonitor.model.DnsMonitor();
        m.setId(31L); m.setDomain("prop.example.com"); m.setRecordType("A"); m.setTeamId(2L);
        m.setPropagationCheck(true);
        when(dnsMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(dnsCheckerService.check("prop.example.com", "A"))
                .thenReturn(Map.of("success", true, "values", List.of("1.2.3.4"), "response_ms", 5L));
        when(dnsCheckerService.checkPropagation(eq("prop.example.com"), eq("A"), anyList()))
                .thenReturn(Map.of("inconsistent", true,
                        "per_resolver", Map.of("8.8.8.8", "1.2.3.4"), "ok_count", 2));

        scheduler.runDnsChecks();

        // handleDnsSweep'in 5. argümanı (inconsistentItems): down + ctx resolver_detail/team
        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> incCap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleDnsSweep(anyList(), anyList(), anyList(), anyList(), incCap.capture());
        assertThat(incCap.getValue()).hasSize(1);
        MonitoringOutageService.SweepItem it = incCap.getValue().get(0);
        assertThat(it.domain()).isEqualTo("prop.example.com");
        assertThat(it.up()).isFalse();   // resolver'lar arası tutarsız → down
        assertThat(it.ctxExtra().get("team_id")).isEqualTo(2L);
        assertThat(it.ctxExtra().get("resolver_detail")).asString().contains("8.8.8.8");
    }

    // ── Kritik domain İKİNCİ günlük kontrolü (runCriticalDomainChecks) ─────────

    private com.certmonitor.model.DomainCheck latestCheck(long monitorId, Integer days) {
        com.certmonitor.model.DomainCheck c = new com.certmonitor.model.DomainCheck();
        c.setMonitorId(monitorId); c.setDaysRemaining(days);
        return c;
    }
    private com.certmonitor.model.DomainMonitor activeDomain(long id, String domain, Long teamId) {
        com.certmonitor.model.DomainMonitor m = new com.certmonitor.model.DomainMonitor();
        m.setId(id); m.setDomain(domain); m.setTeamId(teamId); m.setActive(true);
        return m;
    }
    private Map<String, Object> checkResult(String status, int days) {
        Map<String, Object> r = new java.util.HashMap<>();
        r.put("status", status); r.put("days_remaining", days);
        return r;
    }

    @Test
    @DisplayName("runCriticalDomainChecks: son kontrol days > eşik → domain SEÇİLMEZ (ikinci kontrol yok)")
    void runCriticalDomainChecks_aboveThreshold_notSelected() {
        // Eşik 7 (varsayılan). 25 günlük domain WARNING ama KRİTİK değil → ikinci kontrole girmez.
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of(latestCheck(1L, 25)));

        scheduler.runCriticalDomainChecks();

        verify(domainMonitorRepo, never()).findByActiveTrue();   // aday yok → aktif liste bile çekilmez
        verify(domainCheckerService, never()).check(any());
    }

    @Test
    @DisplayName("runCriticalDomainChecks: son kontrol days ≤ eşik → domain SEÇİLİR (yeniden kontrol + alarm değerlendir)")
    void runCriticalDomainChecks_belowThreshold_selectedAndRechecked() {
        when(domainCheckRepo.findLatestPerMonitor())
                .thenReturn(List.of(latestCheck(1L, 5), latestCheck(2L, 25)));   // yalnız #1 kritik
        com.certmonitor.model.DomainMonitor crit = activeDomain(1L, "crit.example.com", 7L);
        com.certmonitor.model.DomainMonitor safe = activeDomain(2L, "safe.example.com", 7L);
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(crit, safe));
        when(domainCheckerService.check(crit)).thenReturn(checkResult("CRITICAL", 5));

        scheduler.runCriticalDomainChecks();

        verify(domainCheckerService, times(1)).check(crit);
        verify(domainCheckerService, never()).check(safe);       // eşik üstü → dokunulmaz
        // evaluateDomainAlarmsNow çalıştı (dedupe AYNI pipeline'da → ikinci bildirim üretmez, yalnız değerlendirir)
        verify(monitoringOutageService).handleSweepResults(eq(EscalationService.TYPE_DOMAINMON_EXPIRY), anyList());
    }

    @Test
    @DisplayName("runCriticalDomainChecks: kritik domain gün içinde YENİLENDİ → EXPIRY sweep item up=true (alarm kapanır)")
    void runCriticalDomainChecks_renewal_expiryItemUp() {
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of(latestCheck(1L, 3)));
        com.certmonitor.model.DomainMonitor m = activeDomain(1L, "renewed.example.com", 7L);
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(domainCheckerService.check(m)).thenReturn(checkResult("OK", 400));   // yenilenmiş → warn(30) üstü → up

        scheduler.runCriticalDomainChecks();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> cap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleSweepResults(eq(EscalationService.TYPE_DOMAINMON_EXPIRY), cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).up()).isTrue();          // yenilenme = recovery → açık alarm kapanır
    }

    @Test
    @DisplayName("runCriticalDomainChecks: critical-check-enabled=false → hiç çalışmaz")
    void runCriticalDomainChecks_disabled_noop() {
        when(appSettings.getBoolean("cert.monitor.domain.critical-check-enabled", true)).thenReturn(false);
        scheduler.runCriticalDomainChecks();
        verify(domainCheckRepo, never()).findLatestPerMonitor();
        verify(domainCheckerService, never()).check(any());
    }

    @Test
    @DisplayName("Network outage state starts inactive on a fresh instance")
    void networkOutage_initiallyInactive() {
        assertThat(scheduler.isNetworkOutageActive()).isFalse();
        assertThat(scheduler.getNetworkOutageDetectedAt()).isNull();
        assertThat(scheduler.getNetworkOutageResolvedAt()).isNull();
        assertThat(scheduler.getNetworkLastTotal()).isZero();
        assertThat(scheduler.getNetworkLastNetworkErrors()).isZero();
    }

    @Test
    @DisplayName("getStatus() returns the expected status map shape")
    void getStatus_returnsMapWithBootstrappedKeys() {
        when(inventoryRepo.countByActiveTrue()).thenReturn(42L);

        Map<String, Object> status = scheduler.getStatus();

        assertThat(status)
                .containsEntry("active_domains", 42L)
                .containsEntry("running", false)
                .containsEntry("last_run", "Not yet run")
                .containsKey("instance_id")
                .containsKey("current_run_id")
                .containsKey("last_run_id");
        assertThat((String) status.get("instance_id")).isNotBlank();
    }

    @Test
    @DisplayName("forceReleaseLock() deletes the cert-check row via jdbcTemplate")
    void forceReleaseLock_executesDeleteSql() {
        scheduler.forceReleaseLock();

        verify(jdbcTemplate).update(contains("DELETE FROM scheduler_lock"), any(Object[].class));
    }

    @Test
    @DisplayName("runDomainExpiryRefresh: kilit alınır (degrade-true) → DomainExpiryRefreshService.refreshAll çağrılır")
    void runDomainExpiryRefresh_callsRefresh() {
        when(domainExpiryRefreshService.refreshAll()).thenReturn(3);
        scheduler.runDomainExpiryRefresh();
        verify(domainExpiryRefreshService).refreshAll();
    }

    @Test
    @DisplayName("cleanupOldLogs: login_issue retention — yalnız RESOLVED, önce görseller (FK) sonra kayıtlar")
    void cleanupOldLogs_prunesResolvedLoginIssues() {
        scheduler.cleanupOldLogs();

        // FK sırası: önce görseller (RESOLVED alt-sorgu), sonra kayıtlar; ikisi de yalnız RESOLVED.
        verify(jdbcTemplate).update(contains("DELETE FROM login_issue_report_images"), any(Object[].class));
        verify(jdbcTemplate).update(
                contains("DELETE FROM login_issue_reports WHERE status = 'RESOLVED'"), any(Object[].class));
    }

    @Test
    @DisplayName("getShutdownSnapshot() returns running/instance/last_run keys")
    void getShutdownSnapshot_keysPresent() {
        Map<String, Object> snap = scheduler.getShutdownSnapshot();

        assertThat(snap)
                .containsKey("running")
                .containsKey("instance")
                .containsKey("last_run");
        assertThat(snap.get("running")).isEqualTo(false);
    }
}
