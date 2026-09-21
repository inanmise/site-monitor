package com.sitemonitor.service;

import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.repository.PortCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.UptimeCheckRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.KeywordResultRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PingCheckRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.HttpCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.DomainCheckRepository;
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
import java.util.Optional;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.PortMonitor;

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
import static org.mockito.Mockito.mock;

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
    @Mock MonitorHistoryBackfillService historyBackfill;
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
    @Mock PageCheckerService pageCheckerService;
    @Mock com.sitemonitor.repository.PageMonitorRepository pageMonitorRepo;
    @Mock com.sitemonitor.repository.PageCheckRepository pageCheckRepo;
    @Mock com.sitemonitor.repository.PageResourceIssueRepository pageResourceIssueRepo;
    @Mock PageSpeedCheckerService pageSpeedCheckerService;
    @Mock com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedMonitorRepo;
    @Mock com.sitemonitor.repository.PageSpeedCheckRepository pageSpeedCheckRepo;
    @Mock com.sitemonitor.repository.PageSpeedResourceRepository pageSpeedResourceRepo;
    @Mock com.sitemonitor.service.ScriptedCheckerService scriptedCheckerService;
    @Mock com.sitemonitor.repository.ScriptedMonitorRepository scriptedMonitorRepo;
    @Mock com.sitemonitor.repository.ScriptedCheckRepository scriptedCheckRepo;
    @Mock com.sitemonitor.service.retention.RetentionService retentionService;
    @Mock com.sitemonitor.service.report.CertificateInventoryReportService certificateInventoryReportService;

    SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SchedulerService(
                checkerService, certService, emailService, escalationService, maintenanceService,
                inventoryRepo, latestCheckRepo, thresholdRepo, jdbcTemplate,
                userService, permissionService, historyBackfill, dataSource, eventPublisher,
                portCheckerService, portMonitorRepo, portCheckRepo,
                dnsCheckerService, dnsMonitorRepo, dnsRecordRepo,
                uptimeHttpCheckerService, uptimeCheckRepo, monitoringOutageService,
                keywordCheckerService, keywordMonitorRepo, keywordResultRepo,
                pingCheckerService, pingMonitorRepo, pingCheckRepo,
                httpCheckerService, httpMonitorRepo, httpCheckRepo, rdapDomainExpiryService, activityLog, auditService,
                failedLoginAnomalyIncidentService,
                domainMonitorRepo, domainCheckRepo, domainCheckerService,
                networkOutageRepo,
                weeklyReportReminderService, weeklyAvailabilityReportService,
                certificateInventoryReportService, incidentService, appSettings,
                retentionService);
        ReflectionTestUtils.setField(scheduler, "certCheckExecutor", certCheckExecutor);
        ReflectionTestUtils.setField(scheduler, "domainExpiryRefreshService", domainExpiryRefreshService);
        ReflectionTestUtils.setField(scheduler, "pageCheckerService", pageCheckerService);
        ReflectionTestUtils.setField(scheduler, "pageMonitorRepo", pageMonitorRepo);
        ReflectionTestUtils.setField(scheduler, "pageCheckRepo", pageCheckRepo);
        ReflectionTestUtils.setField(scheduler, "pageResourceIssueRepo", pageResourceIssueRepo);
        ReflectionTestUtils.setField(scheduler, "pageSpeedCheckerService", pageSpeedCheckerService);
        ReflectionTestUtils.setField(scheduler, "pageSpeedMonitorRepo", pageSpeedMonitorRepo);
        ReflectionTestUtils.setField(scheduler, "pageSpeedCheckRepo", pageSpeedCheckRepo);
        ReflectionTestUtils.setField(scheduler, "pageSpeedResourceRepo", pageSpeedResourceRepo);
        ReflectionTestUtils.setField(scheduler, "scriptedCheckerService", scriptedCheckerService);
        ReflectionTestUtils.setField(scheduler, "scriptedMonitorRepo", scriptedMonitorRepo);
        ReflectionTestUtils.setField(scheduler, "scriptedCheckRepo", scriptedCheckRepo);
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

    // NOT: safeDeleteBatched testi RetentionServiceTest'e taşındı — dilimli silme artık
    // RetentionService içinde, RetentionCatalog politikalarından üretiliyor (2026-08).

    @Test
    @DisplayName("rollupDailyStats: 8 tip (port/ping/keyword/http/page/scripted/pagespeed/uptime) için upsert çalıştırır")
    void rollupDailyStats_runsAllTypes() {
        when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(3);
        scheduler.rollupDailyStats();
        // Yeni bir izleme türü eklenip rollup satırı unutulursa bu sayı tutmaz: o türün uptime'ı
        // günlük özete hiç girmez ve haftalık raporlarda sessizce yok sayılırdı.
        verify(jdbcTemplate, times(8)).update(anyString(), anyString(), anyString());   // 8 tip (sql, from, to)
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
    @DisplayName("nextDueAfter (GRID): normal ilerleme, çok-interval catch-up tek gelecek değer, tam sınır")
    void nextDueAfter_gridSemantics() {
        // Normal: vade geçti → bir interval ileri
        assertThat(SchedulerService.nextDueAfter(1000L, 1000L, 300L)).isEqualTo(1300L);   // tam sınır (nextDue==now) ilerler
        assertThat(SchedulerService.nextDueAfter(1000L, 1100L, 300L)).isEqualTo(1300L);
        // Catch-up: uzun kapalılık → burst YOK, gelecekteki İLK grid noktası
        assertThat(SchedulerService.nextDueAfter(1000L, 2500L, 300L)).isEqualTo(2800L);
        // Vade gelecekte ise değişmez
        assertThat(SchedulerService.nextDueAfter(2000L, 1000L, 300L)).isEqualTo(2000L);
    }

    @Test
    @DisplayName("runUptimeChecks dispatches SweepItems to MonitoringOutageService")
    void runUptimeChecks_dispatchesSweep() {
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
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
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
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
        com.sitemonitor.model.PortMonitor a = new com.sitemonitor.model.PortMonitor();
        a.setId(11L); a.setHost("a.example.com"); a.setPort(80); a.setProtocol("TCP"); a.setStandalone(true);
        com.sitemonitor.model.PortMonitor b = new com.sitemonitor.model.PortMonitor();
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

    // ── Envanteri pasifleşen monitörün alarmı takılı kalmamalı (2026-08-05, prod log denetimi) ──

    @Test
    @DisplayName("Port sweep: envanteri pasif monitör atlanır VE açık alarmı sessizce kapatılır (recovery hiç gelmez)")
    void portSweep_inventoryInactive_skippedAlarmResolved() {
        com.sitemonitor.model.PortMonitor gone = new com.sitemonitor.model.PortMonitor();
        gone.setId(41L); gone.setHost("eski.example.com"); gone.setPort(443); gone.setProtocol("TCP");
        gone.setStandalone(false);   // envanter-türevi → envanter pasifleşince kontrol edilmez
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(gone));
        when(portMonitorRepo.findAll()).thenReturn(List.of(gone));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());   // envanterde yok

        scheduler.runPortChecks();

        verify(portCheckerService, org.mockito.Mockito.never()).check(any());   // kontrol edilmiyor
        verify(escalationService).resolveOpenAlertsSilently(eq("eski.example.com"),
                eq(java.util.Set.of(EscalationService.TYPE_PORT_DOWN, EscalationService.TYPE_PORT_SLOW)),
                org.mockito.ArgumentMatchers.contains("envanterde aktif değil"));
    }

    @Test
    @DisplayName("Port sweep: aynı host'u izleyen AKTİF monitör varsa alarm kapatılmaz")
    void portSweep_sameHostStillChecked_alarmKept() {
        com.sitemonitor.model.PortMonitor skipped = new com.sitemonitor.model.PortMonitor();
        skipped.setId(42L); skipped.setHost("paylasik.example.com"); skipped.setPort(8443); skipped.setStandalone(false);
        com.sitemonitor.model.PortMonitor live = new com.sitemonitor.model.PortMonitor();
        live.setId(43L); live.setHost("paylasik.example.com"); live.setPort(443); live.setProtocol("TCP"); live.setStandalone(true);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(skipped, live));
        when(portMonitorRepo.findAll()).thenReturn(List.of(skipped, live));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        when(portCheckerService.check(live)).thenReturn(Map.of("open", true, "response_ms", 4L));

        scheduler.runPortChecks();

        verify(escalationService, org.mockito.Mockito.never())
                .resolveOpenAlertsSilently(eq("paylasik.example.com"), org.mockito.ArgumentMatchers.anySet(), anyString());
    }

    @Test
    @DisplayName("DNS sweep: envanteri pasif monitör atlanır VE açık DNS alarmları sessizce kapatılır")
    void dnsSweep_inventoryInactive_skippedAlarmResolved() {
        com.sitemonitor.model.DnsMonitor gone = new com.sitemonitor.model.DnsMonitor();
        gone.setId(51L); gone.setDomain("eski.example.com"); gone.setRecordType("A"); gone.setStandalone(false);
        when(dnsMonitorRepo.findByActiveTrue()).thenReturn(List.of(gone));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());

        scheduler.runDnsChecks();

        verify(dnsCheckerService, org.mockito.Mockito.never()).check(anyString(), anyString());
        verify(escalationService).resolveOpenAlertsSilently(eq("eski.example.com"),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.contains("envanterde aktif değil"));
    }

    // ── Yapılandırma hatası kesinti alarmı üretmemeli (2026-08-04, şemasız URL sahte alarmı) ──

    @Test
    @DisplayName("Page sweep: CONFIG_ERROR → SweepItem'lar up=true (alarm yok) ama kontrol kaydı YAZILIR")
    @SuppressWarnings("unchecked")
    void pageSweep_configError_noDownAlarm_butPersists() {
        com.sitemonitor.model.PageMonitor m = new com.sitemonitor.model.PageMonitor();
        m.setId(21L); m.setName("axess"); m.setUrl("www.axess.com.tr"); m.setMode("SINGLE_PAGE"); m.setActive(true);
        when(pageMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(pageCheckerService.check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageCheckerService.PageCheckResult("CONFIG_ERROR", false, null, 0L,
                        0, 0, 0, 0, 0, null, null, com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG, List.of()));

        scheduler.runPageChecks();

        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleSweepResults(eq(EscalationService.TYPE_PAGE_DOWN), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        MonitoringOutageService.SweepItem item = captor.getValue().get(0);
        assertThat(item.up()).isTrue();        // kesinti DEĞİL → teyit zinciri başlamaz, e-posta gitmez
        assertThat(item.error()).isNull();
        // Bütünlük alarmı da açılmaz
        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> integ =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleSweepResults(eq(EscalationService.TYPE_PAGE_INTEGRITY), integ.capture());
        assertThat(integ.getValue().get(0).up()).isTrue();

        // Kayıt kaybolmuyor: page_checks satırı hata mesajıyla yazılır (kullanıcı sorunu görebilsin).
        org.mockito.ArgumentCaptor<com.sitemonitor.model.PageCheck> pc =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PageCheck.class);
        verify(pageCheckRepo).save(pc.capture());
        assertThat(pc.getValue().getStatus()).isEqualTo("CONFIG_ERROR");
        assertThat(pc.getValue().getOk()).isFalse();
        assertThat(pc.getValue().getError()).isEqualTo(com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG);
    }

    @Test
    @DisplayName("NEGATİF KONTROL — Page sweep: GERÇEK kesinti (DOWN) hâlâ up=false alarm item'ı üretir")
    @SuppressWarnings("unchecked")
    void pageSweep_realOutage_stillAlarms() {
        com.sitemonitor.model.PageMonitor m = new com.sitemonitor.model.PageMonitor();
        m.setId(22L); m.setName("example"); m.setUrl("https://www.example.com/"); m.setMode("SINGLE_PAGE"); m.setActive(true);
        when(pageMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(pageCheckerService.check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageCheckerService.PageCheckResult("DOWN", false, null, 40L,
                        0, 0, 0, 0, 1, null, null, "ana sayfa alınamadı", List.of()));

        scheduler.runPageChecks();

        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleSweepResults(eq(EscalationService.TYPE_PAGE_DOWN), captor.capture());
        MonitoringOutageService.SweepItem item = captor.getValue().get(0);
        assertThat(item.up()).isFalse();
        assertThat(item.error()).isEqualTo("ana sayfa alınamadı");
    }

    @Test
    @DisplayName("HTTP sweep: config_error → up=true (alarm yok); gerçek hata hâlâ down")
    @SuppressWarnings("unchecked")
    void httpSweep_configError_noAlarm_realErrorStillDown() {
        com.sitemonitor.model.HttpMonitor m = new com.sitemonitor.model.HttpMonitor();
        m.setId(31L); m.setName("web"); m.setUrl("www.axess.com.tr"); m.setMethod("GET"); m.setActive(true);
        when(httpMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(httpCheckerService.check(anyString(), any(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(Map.of("ok", false, "config_error", true,
                        "error", com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG));

        scheduler.runHttpChecks();

        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleSweepResults(eq(EscalationService.TYPE_HTTP_DOWN), captor.capture());
        assertThat(captor.getValue().get(0).up()).isTrue();
        assertThat(captor.getValue().get(0).error()).isNull();
        verify(httpCheckRepo).save(any());          // kontrol kaydı yine yazıldı
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
    @DisplayName("warnOnOrphanedRecords: öksüz kaydı SAYAR ama atamaz — mutasyon yok, rastgele takım seçilmez")
    void warnOnOrphanedRecords_countsOnly_noAutoAssign() {
        when(jdbcTemplate.queryForObject(contains("certificate_inventory"), eq(Long.class))).thenReturn(2L);
        when(jdbcTemplate.queryForObject(contains("escalation_contacts"), eq(Long.class))).thenReturn(1L);

        ReflectionTestUtils.invokeMethod(scheduler, "warnOnOrphanedRecords");

        // Salt-okunur: hiçbir "ilk takım seç" (listTeams) veya team_id UPDATE'i çalışmaz.
        verify(userService, never()).listTeams();
        verify(jdbcTemplate, never()).update(contains("SET team_id"), any(Object[].class));
    }

    @Test
    @DisplayName("warnOnOrphanedRecords: öksüz yokken de hiçbir mutasyon yapmaz (sessiz)")
    void warnOnOrphanedRecords_zero_noMutation() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        ReflectionTestUtils.invokeMethod(scheduler, "warnOnOrphanedRecords");

        verify(userService, never()).listTeams();
        verify(jdbcTemplate, never()).update(contains("SET team_id"), any(Object[].class));
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
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setDomain("x.example.com");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));

        com.sitemonitor.model.PortMonitor inInv = new com.sitemonitor.model.PortMonitor();
        inInv.setId(1L); inInv.setHost("x.example.com"); inInv.setPort(8443);
        inInv.setProtocol("TCP"); inInv.setTimeoutMs(5000);
        com.sitemonitor.model.PortMonitor notInInv = new com.sitemonitor.model.PortMonitor();
        notInInv.setId(2L); notInInv.setHost("ghost.example.com"); notInInv.setPort(80);
        notInInv.setProtocol("TCP"); notInInv.setTimeoutMs(5000);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(inInv, notInInv));
        when(portCheckerService.check(any(com.sitemonitor.model.PortMonitor.class)))
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

    /**
     * Gecmise donuk veri duzeltmesi: sahte DNS "DEGISTI" damgalari.
     *
     * <p>WHERE'in DAR olmasi kritik: yalnizca bir tarafi BOS olan karsilastirmalar temizlenir.
     * Gercek bir degisiklik iki DOLU kume gerektirdiginden bu cumle hicbir gercek degisikligi
     * silemez; test bunu SQL metni uzerinde pinler (mantik veritabaninda calisiyor).
     */
    @Test
    @DisplayName("applySchemaPatches: sahte DNS 'degisti' damgasi YALNIZ bir tarafi bos satirlarda temizlenir")
    void cleanupFalseDnsChangeFlags_narrowWhere() {
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.update(sql.capture())).thenReturn(2);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(scheduler, "cleanupFalseDnsChangeFlags");

        // Not: SQL tek satir olarak kuruluyor (string birlestirme, satir sonu yok) — normalizasyon
        // gereksiz. Ters-bolulu bir regex yazmaktan da bilerek kacinildi.
        String q = sql.getValue();
        assertThat(q).startsWith("UPDATE dns_records SET changed = FALSE");
        // Yalniz damgalı satirlar taranir ve YALNIZ bir tarafi bos olanlar temizlenir.
        assertThat(q).contains("changed = TRUE");
        assertThat(q).contains("COALESCE(previous_value, '') = ''");
        assertThat(q).contains("COALESCE(value, '') = ''");
        // rotated DOKUNULMAZ: bos tarafla kesisim daima bos olur, ROTATED uretilemez.
        assertThat(q).doesNotContain("rotated");
    }

    @Test
    @DisplayName("applySchemaPatches: temizlik hatasi ACILISI DURDURMAZ")
    void cleanupFalseDnsChangeFlags_failureIsSwallowed() {
        when(jdbcTemplate.update(anyString())).thenThrow(new RuntimeException("db kapali"));
        // Firlatmamali: kozmetik bir gecmis duzeltmesi izlemenin acilisini bloke edemez.
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(scheduler, "cleanupFalseDnsChangeFlags");
    }

    /**
     * Araya girmeden dogan sahte sertifika "degisti" damgalari — DNS temizliginin kardesi.
     *
     * <p>Kapsam DAR: yalniz ilgili parmak izinin O DOMAIN'de UNTRUSTED gozlendigi satirlar. Yeni
     * guven kapisi (CertificateService.applyAutoPin) bu gozlemi zaten sabitlemezdi, yani temizlik
     * gecmisi guncel mantikla tutarli hale getirir. Gercek bir yenileme iki GUVENILIR gozlem
     * gerektirdiginden bu WHERE hicbir gercek degisimi silemez.
     */
    // ── Ping yavaslik alarmi (goreli esik) ────────────────────────────────────
    //
    // Esik SABIT bir ms degeri degil: host'un KENDI son N dakikalik ortalamasi. Sabit esik yerel
    // bir sunucuda (2 ms) her dalgalanmada oter, denizasiri bir host'ta (180 ms) hic otmez.
    // Asagidaki testler hukmun UC sessizlik dalini da pinler: kapali ozellik, olcum yoklugu ve
    // yetersiz taban cizgisi. Ucunde de alarm URETILMEZ.

    private com.sitemonitor.model.PingMonitor pingMon() {
        com.sitemonitor.model.PingMonitor m = new com.sitemonitor.model.PingMonitor();
        m.setId(7L);
        m.setHost("host.example.com");
        m.setSlowResponseEnabled(true);
        m.setSlowBaselineWindowMinutes(10);
        m.setSlowThresholdPercent(20);
        return m;
    }

    /** slowBaseline(...) -> [ortalama, ornek_sayisi] */
    private void baseline(Double avg, long samples) {
        when(pingCheckRepo.slowBaseline(eq(7L), anyString(), anyString()))
                .thenReturn(java.util.List.<Object[]>of(new Object[]{ avg, samples }));
    }

    @Test
    @DisplayName("ping yavaslik: olcum taban cizgisinin %20 ustundeyse DOWN (alarm)")
    void pingSlow_aboveBaselineIsDown() {
        baseline(100.0, 8);

        Map<String, Object> v = scheduler.pingSlowVerdict(pingMon(), 130L, null);

        assertThat(v.get("status")).isEqualTo("down");
        assertThat(v.get("baseline_ms")).isEqualTo(100L);
        assertThat(v.get("limit_ms")).isEqualTo(120L);
    }

    @Test
    @DisplayName("ping yavaslik: esigin TAM sinirinda alarm YOK (kesin buyuk olmali)")
    void pingSlow_atTheLimitIsUp() {
        baseline(100.0, 8);

        assertThat(scheduler.pingSlowVerdict(pingMon(), 120L, null).get("status")).isEqualTo("up");
    }

    @Test
    @DisplayName("ping yavaslik: taban cizgisi 3 ornekten azsa SESSIZ — gurultu alarma cevrilmez")
    void pingSlow_thinBaselineStaysSilent() {
        baseline(10.0, 2);

        Map<String, Object> v = scheduler.pingSlowVerdict(pingMon(), 900L, null);

        assertThat(v.get("status")).isEqualTo("up");
        assertThat(v).doesNotContainKey("limit_ms");
    }

    @Test
    @DisplayName("ping yavaslik: OLCUM YOKSA sessiz — erisilemezlik PING_DOWN'in isi, cift alarm olmaz")
    void pingSlow_noMeasurementStaysSilent() {
        Map<String, Object> v = scheduler.pingSlowVerdict(pingMon(), null, null);

        assertThat(v.get("status")).isEqualTo("up");
        // Taban cizgisi sorgusu hic kosmamali: olcum yokken kiyaslanacak bir sey de yok.
        verify(pingCheckRepo, never()).slowBaseline(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("ping yavaslik: ozellik KAPALIYKEN hicbir sey degerlendirilmez (opt-in)")
    void pingSlow_disabledStaysSilent() {
        com.sitemonitor.model.PingMonitor m = pingMon();
        m.setSlowResponseEnabled(false);

        assertThat(scheduler.pingSlowVerdict(m, 5000L, null).get("status")).isEqualTo("up");
        verify(pingCheckRepo, never()).slowBaseline(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("ping yavaslik: taban cizgisi okunamazsa SESSIZ kalinir (olcemedigimiz sey alarm olmaz)")
    void pingSlow_baselineErrorStaysSilent() {
        when(pingCheckRepo.slowBaseline(eq(7L), anyString(), anyString()))
                .thenThrow(new RuntimeException("db kapali"));

        assertThat(scheduler.pingSlowVerdict(pingMon(), 900L, null).get("status")).isEqualTo("up");
    }

    @Test
    @DisplayName("ping yavaslik: esik ve pencere IZLEMEDEN gelir, sabit degil")
    void pingSlow_usesPerMonitorSettings() {
        com.sitemonitor.model.PingMonitor m = pingMon();
        m.setSlowThresholdPercent(50);
        m.setSlowBaselineWindowMinutes(30);
        baseline(100.0, 8);

        Map<String, Object> v = scheduler.pingSlowVerdict(m, 140L, null);

        // %50 esikte 140 ms hala esigin altinda (limit 150) — %20 olsaydi alarm verirdi.
        assertThat(v.get("status")).isEqualTo("up");
        assertThat(v.get("limit_ms")).isEqualTo(150L);
        assertThat(v.get("threshold_percent")).isEqualTo(50);
        assertThat(v.get("baseline_window_minutes")).isEqualTo(30);
    }

    @Test
    @DisplayName("applySchemaPatches: sahte sertifika damgasi YALNIZ UNTRUSTED gozlemli satirlarda temizlenir")
    void cleanupInterceptedCertPins_narrowWhere() {
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.update(sql.capture())).thenReturn(1);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(scheduler, "cleanupInterceptedCertPins");

        // Iki ayri hasar, iki ayri cumle: kirlenmis PIN sifirlanir, sahte DAMGA silinir.
        assertThat(sql.getAllValues()).hasSize(2);
        String poisoned = sql.getAllValues().get(0);
        String stamps = sql.getAllValues().get(1);

        // (1) Pin'in KENDISI araya giren sertifikadan geliyorsa pin tamamen sifirlanir (TOFU yeniden kurar).
        assertThat(poisoned).startsWith("UPDATE latest_checks lc SET pinned_fingerprint = NULL");
        assertThat(poisoned).contains("cc.fingerprint = lc.pinned_fingerprint");
        assertThat(poisoned).contains("cc.trust_status = 'UNTRUSTED'");
        // Farkli domainlerin ayni parmak izini tasimasi satirlari birbirine baglamamali.
        assertThat(poisoned).contains("cc.domain = lc.domain");

        // (2) Yalniz ONCEKI taraf kirliyse pin dogrudur; sadece sahte damga silinir.
        assertThat(stamps).startsWith("UPDATE latest_checks lc SET previous_fingerprint = NULL");
        assertThat(stamps).contains("cc.fingerprint = lc.previous_fingerprint");
        assertThat(stamps).contains("cc.trust_status = 'UNTRUSTED'");
        assertThat(stamps).contains("lc.fingerprint_changed_at IS NOT NULL");
        // Pin'e DOKUNMAZ: gercek sertifika geri donmusse sabitlenen dogrudur.
        assertThat(stamps).doesNotContain("SET pinned_fingerprint");
    }

    @Test
    @DisplayName("applySchemaPatches: sertifika pin temizligi hatasi ACILISI DURDURMAZ")
    void cleanupInterceptedCertPins_failureIsSwallowed() {
        when(jdbcTemplate.update(anyString())).thenThrow(new RuntimeException("db kapali"));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(scheduler, "cleanupInterceptedCertPins");
    }

    @Test
    @DisplayName("runDnsChecks: CHANGED only on success vs last successful record; failure emits no change")
    void runDnsChecks_changeDetection_andFailureRegression() {
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setDomain("x.example.com");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));

        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(7L); m.setDomain("x.example.com"); m.setRecordType("A");
        when(dnsMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));

        // 1) Başarılı sorgu + son başarılı kayıt farklı (ayrık) → CHANGED
        com.sitemonitor.model.DnsRecord prevOk = new com.sitemonitor.model.DnsRecord();
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
        org.mockito.ArgumentCaptor<com.sitemonitor.model.DnsRecord> recCaptor =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.DnsRecord.class);
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

        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
        m.setId(42L); m.setDomain("standalone.example.com"); m.setRecordType("A");
        m.setStandalone(true); m.setTeamId(9L);
        when(dnsMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(dnsCheckerService.check("standalone.example.com", "A"))
                .thenReturn(Map.of("success", true, "values", List.of("1.2.3.4"), "response_ms", 10L));

        scheduler.runDnsChecks();

        // Atlanmadı: DnsRecord yazıldı.
        verify(dnsRecordRepo).save(org.mockito.ArgumentMatchers.any(com.sitemonitor.model.DnsRecord.class));
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
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setDomain("locked.example.com");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));

        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
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
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setDomain("prop.example.com");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));

        com.sitemonitor.model.DnsMonitor m = new com.sitemonitor.model.DnsMonitor();
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

    private com.sitemonitor.model.DomainCheck latestCheck(long monitorId, Integer days) {
        com.sitemonitor.model.DomainCheck c = new com.sitemonitor.model.DomainCheck();
        c.setMonitorId(monitorId); c.setDaysRemaining(days);
        return c;
    }
    private com.sitemonitor.model.DomainMonitor activeDomain(long id, String domain, Long teamId) {
        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
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
        com.sitemonitor.model.DomainMonitor crit = activeDomain(1L, "crit.example.com", 7L);
        com.sitemonitor.model.DomainMonitor safe = activeDomain(2L, "safe.example.com", 7L);
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
        com.sitemonitor.model.DomainMonitor m = activeDomain(1L, "renewed.example.com", 7L);
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

    // ── Yeni koruma aileleri (transfer kilidi / kara liste) ──────────────────

    @SuppressWarnings("unchecked")
    private List<MonitoringOutageService.SweepItem> captureSweep(String type) {
        org.mockito.ArgumentCaptor<List<MonitoringOutageService.SweepItem>> cap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService).handleSweepResults(eq(type), cap.capture());
        return cap.getValue();
    }

    private Map<String, Object> protectionResult(String lock, String blacklist) {
        Map<String, Object> r = checkResult("OK", 400);
        r.put("transfer_lock", lock);
        r.put("blacklist_status", blacklist);
        return r;
    }

    /**
     * DOĞRULANAMADI ≠ SORUN. Kilit yalnız RDAP'ta doğrulanabiliyor, kara liste sorgusu kurumsal
     * ağdan reddedilebiliyor. İkisini de "sorun" saymak, .tr envanterinin ve DNSBL'e çıkamayan
     * her kurulumun TAMAMINI sahte alarma boğardı.
     */
    @Test
    @DisplayName("UNKNOWN kilit ve UNKNOWN kara liste ALARM ÜRETMEZ (up=true)")
    void unknownProtectionSignalsDoNotAlarm() {
        com.sitemonitor.model.DomainMonitor m = activeDomain(1L, "example.com", 7L);
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of(latestCheck(1L, 3)));
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(domainCheckerService.check(m)).thenReturn(protectionResult("UNKNOWN", "UNKNOWN"));

        scheduler.runCriticalDomainChecks();

        assertThat(captureSweep(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK).get(0).up()).isTrue();
        assertThat(captureSweep(EscalationService.TYPE_DOMAINMON_BLACKLIST).get(0).up()).isTrue();
    }

    @Test
    @DisplayName("Kilit KESİN yok + anahtar açık → TRANSFER_LOCK alarmı (down)")
    void missingLockAlarms() {
        com.sitemonitor.model.DomainMonitor m = activeDomain(1L, "example.com", 7L);
        m.setTransferLockAlert(true);
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of(latestCheck(1L, 3)));
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(domainCheckerService.check(m)).thenReturn(protectionResult("NONE", "CLEAN"));

        scheduler.runCriticalDomainChecks();

        assertThat(captureSweep(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK).get(0).up()).isFalse();
        assertThat(captureSweep(EscalationService.TYPE_DOMAINMON_BLACKLIST).get(0).up()).isTrue();
    }

    @Test
    @DisplayName("Kilit anahtarı KAPALIYSA kilit yok olsa da alarm ÜRETİLMEZ")
    void lockAlertOffSuppressesAlarm() {
        com.sitemonitor.model.DomainMonitor m = activeDomain(1L, "example.com", 7L);
        m.setTransferLockAlert(false);
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of(latestCheck(1L, 3)));
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(domainCheckerService.check(m)).thenReturn(protectionResult("NONE", "SKIPPED"));

        scheduler.runCriticalDomainChecks();

        assertThat(captureSweep(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK).get(0).up()).isTrue();
    }

    @Test
    @DisplayName("LISTED → BLACKLIST alarmı ve kanıt bağlamda taşınır")
    void listedAlarmsWithEvidence() {
        com.sitemonitor.model.DomainMonitor m = activeDomain(1L, "example.com", 7L);
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of(latestCheck(1L, 3)));
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        Map<String, Object> r = protectionResult("BOTH", "LISTED");
        r.put("blacklist_detail", "zen.spamhaus.org=1.2.3.4");
        r.put("blacklist_hits", 1);
        when(domainCheckerService.check(m)).thenReturn(r);

        scheduler.runCriticalDomainChecks();

        var item = captureSweep(EscalationService.TYPE_DOMAINMON_BLACKLIST).get(0);
        assertThat(item.up()).isFalse();
        // Kanıt olmadan alarmı alan kişi hangi listeden çıkacağını bilemez.
        assertThat(item.ctxExtra()).containsEntry("blacklist_detail", "zen.spamhaus.org=1.2.3.4");
    }

    @Test
    @DisplayName("Değişiklik anahtarı KAPALIYSA CHANGED alarmı üretilmez")
    void changeAlertOffSuppressesChanged() {
        com.sitemonitor.model.DomainMonitor m = activeDomain(1L, "example.com", 7L);
        m.setChangeAlert(false);
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of(latestCheck(1L, 3)));
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        Map<String, Object> r = protectionResult("BOTH", "CLEAN");
        r.put("changed", true);
        when(domainCheckerService.check(m)).thenReturn(r);

        scheduler.runCriticalDomainChecks();

        assertThat(captureSweep(EscalationService.TYPE_DOMAINMON_CHANGED)).isEmpty();
    }

    @Test
    @DisplayName("runCriticalDomainChecks: critical-check-enabled=false → hiç çalışmaz")
    void runCriticalDomainChecks_disabled_noop() {
        when(appSettings.getBoolean("site.monitor.domain.critical-check-enabled", true)).thenReturn(false);
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
    @DisplayName("forceReleaseLock() BIRAKILAN kilidin durumunu döner (denetim 'kim tutuyordu'yu yazabilsin)")
    void forceReleaseLock_returnsLockStateBeforeRelease() {
        // Kilit satırı silindikten sonra "kimin koşumu takılmıştı" sorusu cevapsız kalıyordu;
        // denetim satırı da bu yüzden detaysız yazılıyordu.
        when(jdbcTemplate.queryForList(contains("SELECT * FROM scheduler_lock"), any(Object[].class)))
                .thenReturn(java.util.List.of(java.util.Map.of(
                        "name", "cert-check", "instance_id", "pod-7", "acquired_at", "2026-09-02T10:00:00")));

        Map<String, Object> before = scheduler.forceReleaseLock();

        assertThat(before).containsEntry("held", true)
                .containsEntry("instance_id", "pod-7")
                .containsKey("acquired_at")
                .containsKey("run_id_before");
    }

    @Test
    @DisplayName("forceReleaseLock(): kilit YOKKEN de serbest bırakma çalışır, held=false döner")
    void forceReleaseLock_noLockHeld() {
        when(jdbcTemplate.queryForList(contains("SELECT * FROM scheduler_lock"), any(Object[].class)))
                .thenReturn(java.util.List.of());

        assertThat(scheduler.forceReleaseLock()).containsEntry("held", false);
        verify(jdbcTemplate).update(contains("DELETE FROM scheduler_lock"), any(Object[].class));
    }

    @Test
    @DisplayName("forceReleaseLock(): kilit OKUNAMASA bile serbest bırakma yapılır (tanı işlemin önüne geçmez)")
    void forceReleaseLock_readFailureStillReleases() {
        when(jdbcTemplate.queryForList(contains("SELECT * FROM scheduler_lock"), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));

        assertThat(scheduler.forceReleaseLock()).containsEntry("held", "unknown");
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
    @DisplayName("cleanupOldLogs: rollup ÖNCE, sonra RetentionService — silme mantığı artık katalogda")
    void cleanupOldLogs_delegatesToRetentionService() {
        var empty = new com.sitemonitor.service.retention.RetentionService.RunResult(
                1L, "2026-08-08T03:30:00", "2026-08-08T03:30:05", false, false, 0, 0, 5, java.util.List.of());
        when(retentionService.runCleanup()).thenReturn(empty);
        // holdActive()/cutoffFor() stub'ları KALDIRILDI: yalnızca silme öncesi JSONL arşivleme
        // adımı onları çağırıyordu, o adım da kaldırıldı (bkz. RetentionNoFileArchiveTest).

        scheduler.cleanupOldLogs();

        // Ham seriler günlük özete alınmadan silinmemeli → rollup purge'den ÖNCE.
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .update(contains("INSERT INTO monitor_check_daily"), anyString(), anyString());
        verify(retentionService).runCleanup();
        // Silme SQL'leri artık burada üretilmiyor.
        verify(jdbcTemplate, never()).update(contains("DELETE FROM login_issue_reports"), any(Object[].class));
    }

    @Test
    @DisplayName("cleanupOldLogs: legal hold açıkken HOLD kaydı yazılır, purge kaydı yazılmaz")
    void cleanupOldLogs_legalHold_recordsHoldNotPurge() {
        // Eski ad "…SkipsArchive" idi; silme öncesi arşivleme kaldırıldığı için o yarısı düştü.
        // Kalan sözleşme aynen geçerli: hold altında purge denetim kaydı ÜRETİLMEZ — aksi halde
        // hiçbir şey silinmemişken "silindi" diyen bir iz kalırdı.
        var held = new com.sitemonitor.service.retention.RetentionService.RunResult(
                2L, "2026-08-08T03:30:00", "2026-08-08T03:30:00", false, true, 0, 0, 1, java.util.List.of());
        when(retentionService.runCleanup()).thenReturn(held);

        scheduler.cleanupOldLogs();

        verify(auditService).recordSystemEvent(eq("RETENTION_HOLD_ACTIVE"), anyString(), anyString(), anyString());
        verify(auditService, never()).recordSystemEvent(eq("AUDIT_RETENTION_PURGE"), anyString(), anyString(), anyString());
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

    @Test
    @DisplayName("issueReportDigest: ayar KAPALIYKEN (varsayılan) hiçbir şey yapılmaz")
    void issueReportDigest_disabledByDefault_noop() {
        var mail = org.mockito.Mockito.mock(LoginIssueMailService.class);
        var repo = org.mockito.Mockito.mock(com.sitemonitor.repository.LoginIssueReportRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "loginIssueMailService", mail);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "loginIssueReportRepo", repo);

        scheduler.scheduledIssueReportDigest();

        org.mockito.Mockito.verifyNoInteractions(mail, repo);
    }

    @Test
    @DisplayName("issueReportDigest: ayar AÇIK + son 24s USER_REPORT var → tek özet mail dispatch edilir")
    void issueReportDigest_enabled_dispatchesSummary() {
        when(appSettings.getBoolean("site.monitor.issue-reports.daily-digest", false)).thenReturn(true);
        when(appSettings.getString("site.monitor.system-admin.email", "")).thenReturn("admin@x.com");
        var mail = org.mockito.Mockito.mock(LoginIssueMailService.class);
        var repo = org.mockito.Mockito.mock(com.sitemonitor.repository.LoginIssueReportRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "loginIssueMailService", mail);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "loginIssueReportRepo", repo);
        com.sitemonitor.model.LoginIssueReport r = new com.sitemonitor.model.LoginIssueReport();
        r.setId(5L); r.setReportedAt("2026-08-07T01:00:00"); r.setUsername("N1"); r.setMessage("özet mesajı");
        when(repo.findBySourceAndReportedAtGreaterThanEqualOrderByReportedAtDesc(eq("USER_REPORT"), anyString()))
                .thenReturn(java.util.List.of(r));

        scheduler.scheduledIssueReportDigest();

        verify(mail).dispatchDigest(eq("admin@x.com"),
                org.mockito.ArgumentMatchers.argThat((java.util.List<Map<String, String>> items) ->
                        items.size() == 1 && "LIR-2026-000005".equals(items.get(0).get("refCode"))
                        && "N1".equals(items.get(0).get("username"))),
                anyString());
    }

    @Test
    @DisplayName("oneLine: çok satırlı senaryo hatası alarm başlığına tek satır olarak sığar, sebep kaybolmaz")
    void oneLine_collapsesMultilineScriptedError() {
        // summarizeError'ın ürettiği biçim: 1. satır etiket, 2. satır asıl sebep, gerisi kod çerçevesi.
        String error = "script çalışma-zamanı hatası (çıkış 107):\n"
                + "SyntaxError: script: Unexpected token (46:29)\n"
                + "  44 |       try {\n"
                + "> 46 |         const content = body?.choices?.[0]?.message?.content || '';\n"
                + "     |                              ^";

        String detail = SchedulerService.oneLine(error);

        assertThat(detail.lines()).hasSize(1);
        // Yalnız ilk satır alınsaydı alarm hatanın NE olduğunu hiç söylemezdi — ikinci satır şart.
        assertThat(detail).contains("çıkış 107").contains("Unexpected token (46:29)");
        assertThat(detail).endsWith("…");                 // kırpıldığı gizlenmiyor
        assertThat(detail).doesNotContain("44 |");         // kod çerçevesi alarm başlığına girmiyor

        // tek satırlık hata olduğu gibi geçer, sonuna "…" eklenmez
        assertThat(SchedulerService.oneLine("Süre aşımı — süreç sonlandırıldı"))
                .isEqualTo("Süre aşımı — süreç sonlandırıldı");

        // tek ama çok uzun satır da tavanlanır (alarm/mail başlığı patlamasın)
        assertThat(SchedulerService.oneLine("x".repeat(500))).hasSize(302).endsWith(" …");
    }

    // ── Sentetik sweep ───────────────────────────────────────────────────────────────────────
    // Bu yol simdiye kadar TESTSIZDI: SKIPPED erken-cikisi, timeout'ta future.cancel(true) ve
    // ensureProbed() kapisi yalnizca uretimde kosuyordu. Ucu de sessiz veri/alarm hatasi sinifi.

    private com.sitemonitor.model.ScriptedMonitor scriptedMon(long id, String name) {
        var m = new com.sitemonitor.model.ScriptedMonitor();
        m.setId(id); m.setName(name); m.setActive(true); m.setIntervalSeconds(60);
        m.setScript("export default function () {}");
        return m;
    }

    private static ScriptedCheckerService.ScriptedResult scriptedResult(String status, boolean ok, Long durMs) {
        return new ScriptedCheckerService.ScriptedResult(status, ok, durMs, ok ? 0 : 1,
                ok ? 1 : 0, ok ? 0 : 1, null, null, null, null, null, ok ? null : "hata",
                false, ScriptedCheckerService.Phases.EMPTY);
    }

    @SuppressWarnings("unchecked")
    private static java.util.concurrent.Future<ScriptedCheckerService.ScriptedResult> future(
            ScriptedCheckerService.ScriptedResult r) throws Exception {
        var f = (java.util.concurrent.Future<ScriptedCheckerService.ScriptedResult>)
                mock(java.util.concurrent.Future.class);
        when(f.get(anyLong(), any())).thenReturn(r);
        return f;
    }

    @Test
    @DisplayName("Sentetik sweep: k6 sondasi basarisizsa HIC kontrol kosmaz (monitor sorgusu bile yapilmaz)")
    void scriptedSweep_ensureProbedGate() {
        when(scriptedCheckerService.ensureProbed()).thenReturn(false);
        when(scriptedMonitorRepo.countByActiveTrue()).thenReturn(3L);

        scheduler.runScriptedChecks();

        // Kapi acilmadan monitor listesi cekilmemeli: cekilirse sweep gercekten baslamis demektir.
        verify(scriptedMonitorRepo, never()).findByActiveTrue();
        verify(scriptedCheckerService, never()).submit(any());
    }

    @Test
    @DisplayName("Sentetik sweep: SKIPPED sonuc KAYIT YAZMAZ ve alarm zincirine GIRMEZ (havuz darligi ariza degil)")
    void scriptedSweep_skippedWritesNothing() throws Exception {
        var m = scriptedMon(7L, "login-akisi");
        when(scriptedMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        var f = future(scriptedResult("SKIPPED", false, null));
        when(scriptedCheckerService.submit(m)).thenReturn(f);

        ReflectionTestUtils.invokeMethod(scheduler, "runScriptedChecksLocked");

        verify(scriptedCheckRepo, never()).save(any());
        // Iki pipeline da BOS listeyle cagrilir: "hepsi up" sanip acik alarmi kapatmamali diye
        // handleSweepResults yine cagrilir, ama icinde hicbir item olmamalidir.
        var cap = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService, times(2)).handleSweepResults(anyString(), cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(l -> assertThat(l).isEmpty());
    }

    @Test
    @DisplayName("Sentetik sweep: sonuc zamaninda gelmezse future IPTAL edilir (k6 sureci ve permit sizmaz)")
    void scriptedSweep_timeoutCancelsFuture() throws Exception {
        var m = scriptedMon(8L, "yavas-senaryo");
        @SuppressWarnings("unchecked")
        var f = (java.util.concurrent.Future<ScriptedCheckerService.ScriptedResult>)
                mock(java.util.concurrent.Future.class);
        when(f.get(anyLong(), any())).thenThrow(new java.util.concurrent.TimeoutException("gelmedi"));
        when(scriptedMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(scriptedCheckerService.submit(m)).thenReturn(f);

        ReflectionTestUtils.invokeMethod(scheduler, "runScriptedChecksLocked");

        verify(f).cancel(true);          // iptal edilmezse k6 sureci permit'i tutmaya devam eder
        verify(scriptedCheckRepo, never()).save(any());
    }

    @Test
    @DisplayName("Sentetik sweep: GECEN ama esikten yavas kosum SCRIPTED_SLOW uretir, kesinti alarmi uretmez")
    void scriptedSweep_slowItemRaisedOnlyWhenEnabled() throws Exception {
        var m = scriptedMon(9L, "yavas-ama-gecen");
        m.setSlowResponseEnabled(true);
        m.setSlowThresholdMs(2000);
        when(scriptedMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        var f = future(scriptedResult("PASS", true, 5000L));
        when(scriptedCheckerService.submit(m)).thenReturn(f);

        ReflectionTestUtils.invokeMethod(scheduler, "runScriptedChecksLocked");

        var type = org.mockito.ArgumentCaptor.forClass(String.class);
        var items = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService, times(2)).handleSweepResults(type.capture(), items.capture());

        int failIdx = type.getAllValues().indexOf(EscalationService.TYPE_SCRIPTED_FAIL);
        int slowIdx = type.getAllValues().indexOf(EscalationService.TYPE_SCRIPTED_SLOW);
        assertThat(failIdx).isNotNegative();
        assertThat(slowIdx).isNotNegative();

        var failItem = (MonitoringOutageService.SweepItem) items.getAllValues().get(failIdx).get(0);
        var slowItem = (MonitoringOutageService.SweepItem) items.getAllValues().get(slowIdx).get(0);
        assertThat(failItem.up()).isTrue();       // kosum GECTI — kesinti yok
        assertThat(slowItem.up()).isFalse();      // ama esigi asti — yavaslik var
        assertThat(slowItem.detail()).isEqualTo("5000 ms");
        assertThat(slowItem.ctxExtra()).containsEntry("threshold_ms", 2000);
    }

    @Test
    @DisplayName("Sentetik sweep: yavaslik alarmi KAPALIYSA esik asilsa bile SLOW item 'up' kalir (asili alarm kurtarilir)")
    void scriptedSweep_slowSuppressedWhenDisabled() throws Exception {
        var m = scriptedMon(10L, "hizli-olmasi-onemsiz");
        m.setSlowResponseEnabled(false);
        m.setSlowThresholdMs(1000);
        when(scriptedMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        var f = future(scriptedResult("PASS", true, 90_000L));
        when(scriptedCheckerService.submit(m)).thenReturn(f);

        ReflectionTestUtils.invokeMethod(scheduler, "runScriptedChecksLocked");

        var type = org.mockito.ArgumentCaptor.forClass(String.class);
        var items = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService, times(2)).handleSweepResults(type.capture(), items.capture());
        int slowIdx = type.getAllValues().indexOf(EscalationService.TYPE_SCRIPTED_SLOW);
        var slowItem = (MonitoringOutageService.SweepItem) items.getAllValues().get(slowIdx).get(0);
        assertThat(slowItem.up()).isTrue();
    }

    @Test
    @DisplayName("Sentetik sweep: DUSEN kosum SLOW alarmi ACMAZ (tek olayda iki alarm bagirmasin)")
    void scriptedSweep_failedRunDoesNotAlsoRaiseSlow() throws Exception {
        var m = scriptedMon(11L, "dusen-senaryo");
        m.setSlowResponseEnabled(true);
        m.setSlowThresholdMs(1000);
        when(scriptedMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        var f = future(scriptedResult("TIMEOUT", false, 60_000L));
        when(scriptedCheckerService.submit(m)).thenReturn(f);

        ReflectionTestUtils.invokeMethod(scheduler, "runScriptedChecksLocked");

        var type = org.mockito.ArgumentCaptor.forClass(String.class);
        var items = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(monitoringOutageService, times(2)).handleSweepResults(type.capture(), items.capture());
        int failIdx = type.getAllValues().indexOf(EscalationService.TYPE_SCRIPTED_FAIL);
        int slowIdx = type.getAllValues().indexOf(EscalationService.TYPE_SCRIPTED_SLOW);
        var failItem = (MonitoringOutageService.SweepItem) items.getAllValues().get(failIdx).get(0);
        var slowItem = (MonitoringOutageService.SweepItem) items.getAllValues().get(slowIdx).get(0);
        assertThat(failItem.up()).isFalse();      // kesinti alarmi bunu anlatir
        assertThat(slowItem.up()).isTrue();       // yavaslik alarmi ustune ikinci kez bagirmaz
    }

    // ── runWithSchedulerLock: dağıtık kilidin BIRAKILMASI ────────────────────────
    // Kilit TTL boyunca (varsayılan dakikalar) tutulur. İş fırlatıp kilit finally'de bırakılmazsa
    // görev HİÇBİR pod'da tekrar çalışmaz — uygulama sağlıklı görünür, log tek satırdır, aylık
    // rapor / temizlik sessizce ölür. Bu yüzden buradaki asıl iddia "hata yutuldu" değil,
    // "hata olsa DA kilit bırakıldı".

    private static final String LOCK_RELEASE_SQL = "DELETE FROM scheduler_lock WHERE name = ? AND locked_by = ?";

    @Test
    @DisplayName("İş FIRLATSA da kilit finally'de bırakılır (aksi halde görev TTL boyunca hiçbir pod'da koşmaz)")
    void runWithSchedulerLock_releasesLockWhenTaskThrows() {
        assertThatCode(() -> scheduler.runWithSchedulerLock("aylik-rapor",
                () -> { throw new IllegalStateException("SMTP down"); }))
                .as("scheduler thread'i ölmemeli; hata yutulup loglanır")
                .doesNotThrowAnyException();

        verify(jdbcTemplate).update(eq(LOCK_RELEASE_SQL), eq("aylik-rapor"), anyString());
    }

    @Test
    @DisplayName("Normal akışta da kilit bırakılır ve iş bir kez çalışır")
    void runWithSchedulerLock_happyPath() {
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();

        scheduler.runWithSchedulerLock("aylik-rapor", runs::incrementAndGet);

        assertThat(runs.get()).isEqualTo(1);
        verify(jdbcTemplate).update(eq(LOCK_RELEASE_SQL), eq("aylik-rapor"), anyString());
    }

    @Test
    @DisplayName("Kilit BAŞKA pod'daysa iş HİÇ çalışmaz ve o pod'un kilidi bırakılmaz")
    void runWithSchedulerLock_skipsWhenLockHeldElsewhere() {
        // INSERT unique-constraint ihlali = kilit başka pod'da (gerçek sürücü mesajı taklit ediliyor)
        // lenient: tryAcquire önce süresi dolmuş kilidi SİLER; katı mod o eşleşmeyen çağrıda
        // PotentialStubbingProblem fırlatır, o da tryAcquire'ın catch'ine düşüp "tablo yok" fallback'ini
        // tetikler — yani testin taklit ettiği durumu bozardı.
        lenient().when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("UNIQUE constraint failed: scheduler_lock.name"));
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();

        scheduler.runWithSchedulerLock("aylik-rapor", runs::incrementAndGet);

        assertThat(runs.get()).isZero();
        // Kilidi TUTAN pod'un kaydını silmeye çalışmamalı (locked_by eşleşmese de niyet yanlış olurdu)
        verify(jdbcTemplate, never()).update(eq(LOCK_RELEASE_SQL), anyString(), anyString());
    }

    @Test
    @DisplayName("Kilit tablosu ERİŞİLEMEZSE tek-pod fallback: iş yine de çalışır (HA bozulur, işlev ölmez)")
    void runWithSchedulerLock_runsWhenLockTableUnavailable() {
        // D2: fixture GERÇEK istisna tipini kullanır — Spring, olmayan tablo için
        // BadSqlGrammarException atar. Eskiden ham RuntimeException'dı ve kilit edinimi de
        // "her istisnada fail-open" olduğu için geçiyordu; artık YALNIZ bu tip fail-open,
        // geçici hatalar (deadlock/timeout) turu ATLATIR. Aşağıdaki ikinci test onu pinliyor.
        lenient().when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                        "insert", "INSERT INTO scheduler_lock",
                        new java.sql.SQLException("relation \"scheduler_lock\" does not exist")));
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();

        scheduler.runWithSchedulerLock("aylik-rapor", runs::incrementAndGet);

        assertThat(runs.get()).isEqualTo(1);
    }

    /**
     * D2: kilit ediniminde fail-open YALNIZ "tablo yok" halinde olmalı. Eskiden mesaj metninde
     * "unique" geçmeyen HER istisna (deadlock, statement-timeout, bağlantı kopması) true
     * dönüyordu → çok-pod'da geçici bir DB hatasında İKİ pod aynı sweep'i koşar, çift alarm
     * ve çift mail üretirdi. Sweep periyodiktir: bir turu atlamak, çift koşmaktan ucuzdur.
     */
    @Test
    @DisplayName("D2: geçici DB hatasında kilit alınamaz — tur ATLANIR (fail-open değil)")
    void runWithSchedulerLock_transientDbError_skipsRun() {
        lenient().when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("statement timeout"));
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();

        scheduler.runWithSchedulerLock("aylik-rapor", runs::incrementAndGet);

        assertThat(runs.get()).as("geçici hatada çift koşmaktansa turu atla").isZero();
    }

    // ── Sayfa Hızı: kaynak kırılımı saklama kararının KAPISI ───────────────────────────────
    //
    // Karar (2026-08): özet her ölçümde seriye yazılır; AĞIR kırılım yalnız SON ölçüm için tutulur
    // (LATEST — üzerine yazılır) + eşik ihlali anlarında delil olarak dondurulur (BREACH — kalıcı).
    // Bu kural bozulursa tablo kontrol sayısıyla büyür: 100 sayfa × 500 kaynak × yarım saatte bir
    // günde milyonlarca satır eder ve tek pod'da her şeyi yavaşlatır. Hata SESSİZDİR — ekranda
    // hiçbir şey değişmez, yalnız disk ve sorgular şişer.

    private com.sitemonitor.model.PageSpeedMonitor psMonitor() {
        var m = new com.sitemonitor.model.PageSpeedMonitor();
        m.setId(7L);
        m.setName("odeme sayfasi");
        m.setUrl("https://x.com/odeme");
        m.setTeamId(1L);
        return m;
    }

    private PageSpeedCheckerService.Result psResult(java.util.List<String> breached) {
        var res = new PageSpeedCheckerService.Measured(
                "https://x.com/a.js", "JS", 5000L, 120L, 200, false, false, false);
        return new PageSpeedCheckerService.Result(
                breached.isEmpty() ? "OK" : "SLOW", 200, 40L, 90L, 300L,
                20000L, 3, 0, false, false, breached, null, java.util.List.of(res));
    }

    @SuppressWarnings("unchecked")
    private java.util.List<com.sitemonitor.model.PageSpeedResource> captureSavedResources() {
        var cap = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(pageSpeedResourceRepo).saveAll(cap.capture());
        return (java.util.List<com.sitemonitor.model.PageSpeedResource>) cap.getValue();
    }

    @Test
    @DisplayName("İhlal YOKken kırılım yalnız LATEST yazılır ve önceki LATEST silinir (tablo büyümez)")
    void resourceBreakdown_cleanCheck_onlyLatest() {
        when(pageSpeedCheckerService.check(any())).thenReturn(psResult(java.util.List.of()));
        when(pageSpeedCheckRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageSpeedCheck c = i.getArgument(0); c.setId(100L); return c; });

        scheduler.triggerPageSpeedCheck(psMonitor());

        // Önceki ölçümün LATEST satırları SİLİNMELİ — silinmezse her ölçüm satır ekler.
        verify(pageSpeedResourceRepo).deleteByMonitorIdAndKeepReason(7L,
                com.sitemonitor.model.PageSpeedResource.KEEP_LATEST);
        var saved = captureSavedResources();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getKeepReason()).isEqualTo(com.sitemonitor.model.PageSpeedResource.KEEP_LATEST);
        assertThat(saved.get(0).getBytes()).isEqualTo(5000L);
        assertThat(saved.get(0).getCheckId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("İhlal BAŞLADIĞINDA kırılım BREACH kopyasıyla dondurulur (delil kalıcı)")
    void resourceBreakdown_breach_freezesEvidence() {
        // Önceki ölçüm TEMİZ → bu, ihlalin başladığı an.
        when(pageSpeedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(anyLong()))
                .thenReturn(java.util.Optional.empty());
        when(pageSpeedCheckerService.check(any())).thenReturn(psResult(java.util.List.of("SIZE")));
        when(pageSpeedCheckRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageSpeedCheck c = i.getArgument(0); c.setId(101L); return c; });

        scheduler.triggerPageSpeedCheck(psMonitor());

        var saved = captureSavedResources();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(com.sitemonitor.model.PageSpeedResource::getKeepReason)
                .containsExactlyInAnyOrder(com.sitemonitor.model.PageSpeedResource.KEEP_LATEST,
                        com.sitemonitor.model.PageSpeedResource.KEEP_BREACH);
    }

    @Test
    @DisplayName("Kirpma bayragi HEM olcum ozetine HEM kaynak satirina yazilir")
    void truncationFlagIsPersistedOnBothTables() {
        var res = new PageSpeedCheckerService.Measured(
                "https://x.com/dev.bin", "OTHER", 10L * 1024 * 1024, 900L, 200, false, false, true);
        when(pageSpeedCheckerService.check(any())).thenReturn(new PageSpeedCheckerService.Result(
                "OK", 200, 40L, 90L, 300L, 11L * 1024 * 1024, 2, 0, false, true,
                java.util.List.of(), null, java.util.List.of(res)));
        when(pageSpeedCheckRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageSpeedCheck c = i.getArgument(0); c.setId(200L); return c; });

        var out = scheduler.triggerPageSpeedCheck(psMonitor());

        var cap = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PageSpeedCheck.class);
        verify(pageSpeedCheckRepo).save(cap.capture());
        assertThat(cap.getValue().getBytesTruncated()).isTrue();
        assertThat(captureSavedResources()).allMatch(com.sitemonitor.model.PageSpeedResource::getTruncated);
        // Arayuz bandi bu anahtari okuyor.
        assertThat(out.get("bytes_truncated")).isEqualTo(true);
    }

    @Test
    @DisplayName("Sayfa ALINAMAZSA son iyi kırılım SİLİNMEZ (kesinti anında tablo boşalmaz)")
    void resourceBreakdown_unreachable_keepsLastGoodBreakdown() {
        // Once silip sonra "kirilim bos" diye donmek, tek bir basarisiz kontrolde son iyi kirilimi
        // KALICI olarak siliyordu — yani kullanici "bozulmadan once sayfa neye benziyordu" diye
        // baktigi ANDA tablo bosaliyordu.
        when(pageSpeedCheckerService.check(any())).thenReturn(new PageSpeedCheckerService.Result(
                "DOWN", null, 0L, 0L, 120L, 0L, 1, 1, false, false,
                java.util.List.of(), "sayfa alınamadı", java.util.List.of()));
        when(pageSpeedCheckRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageSpeedCheck c = i.getArgument(0); c.setId(103L); return c; });

        scheduler.triggerPageSpeedCheck(psMonitor());

        verify(pageSpeedResourceRepo, never()).deleteByMonitorIdAndKeepReason(anyLong(), anyString());
        verify(pageSpeedResourceRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("İhlal SÜRERKEN delil TEKRAR dondurulmaz — tablo kontrol sayısıyla büyümez")
    void resourceBreakdown_ongoingBreach_doesNotRefreeze() {
        // Kalıcı yavaş bir sayfa 30 dk'da bir 500 kalıcı satır yazsaydı günde ~24.000 satır,
        // 90 günlük saklamayla tek izleme için milyonlarca satır ederdi. Delil BOZULMA ANINDA
        // alınır; ihlal sürerken kırılım LATEST'te canlı duruyor.
        var prev = new com.sitemonitor.model.PageSpeedCheck();
        prev.setBreachedMetrics("SIZE");                 // önceki ölçüm de ihlalliydi
        when(pageSpeedCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(anyLong()))
                .thenReturn(java.util.Optional.of(prev));
        when(pageSpeedCheckerService.check(any())).thenReturn(psResult(java.util.List.of("SIZE")));
        when(pageSpeedCheckRepo.save(any())).thenAnswer(i -> {
            com.sitemonitor.model.PageSpeedCheck c = i.getArgument(0); c.setId(102L); return c; });

        scheduler.triggerPageSpeedCheck(psMonitor());

        var saved = captureSavedResources();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getKeepReason()).isEqualTo(com.sitemonitor.model.PageSpeedResource.KEEP_LATEST);
        assertThat(saved).noneMatch(r -> com.sitemonitor.model.PageSpeedResource.KEEP_BREACH.equals(r.getKeepReason()));
    }

    @Test
    @DisplayName("Eşik aşımı ok=true kalır — yavaş sayfa uptime'ı DÜŞÜRMEZ, ihlal ayrı kolonda durur")
    void breachDoesNotMarkCheckAsFailed() {
        when(pageSpeedCheckerService.check(any())).thenReturn(psResult(java.util.List.of("LOAD", "SIZE")));
        when(pageSpeedCheckRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        scheduler.triggerPageSpeedCheck(psMonitor());

        var cap = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PageSpeedCheck.class);
        verify(pageSpeedCheckRepo).save(cap.capture());
        assertThat(cap.getValue().getOk()).isTrue();                       // KESİNTİ DEĞİL
        assertThat(cap.getValue().getBreachedMetrics()).isEqualTo("LOAD,SIZE");
        assertThat(cap.getValue().getResponseMs()).isEqualTo(300);
        assertThat(cap.getValue().getTtfbMs()).isEqualTo(40);
    }

    @Test
    @DisplayName("Sayfa alınamazsa ok=false (kesinti) ve kırılım hiç yazılmaz")
    void unreachablePageIsAnOutageWithoutBreakdown() {
        when(pageSpeedCheckerService.check(any())).thenReturn(new PageSpeedCheckerService.Result(
                "DOWN", null, 0L, 0L, 50L, 0L, 1, 1, false, false,
                java.util.List.of(), "sayfa alinamadi", java.util.List.of()));
        when(pageSpeedCheckRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        var out = scheduler.triggerPageSpeedCheck(psMonitor());

        var cap = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.PageSpeedCheck.class);
        verify(pageSpeedCheckRepo).save(cap.capture());
        assertThat(cap.getValue().getOk()).isFalse();
        assertThat(cap.getValue().getBreachedMetrics()).isNull();
        verify(pageSpeedResourceRepo, never()).saveAll(any());
        assertThat(out.get("reachable")).isEqualTo(false);
    }

    @Test
    @DisplayName("Yapılandırma hatası kesinti DEĞİL: config_error bayrağı çıkar, alarm yolu 'up' okur")
    void configErrorIsNotAnOutage() {
        when(pageSpeedCheckerService.check(any())).thenReturn(new PageSpeedCheckerService.Result(
                "CONFIG_ERROR", null, 0L, 0L, 0L, 0L, 0, 0, false, false,
                java.util.List.of(), "url gecersiz", java.util.List.of()));
        when(pageSpeedCheckRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        var out = scheduler.triggerPageSpeedCheck(psMonitor());

        assertThat(out.get("config_error")).isEqualTo(true);
    }

    // -- B2: kanal bayraklarinin ctx'e DAMGALANMASI -------------------------
    // Mutasyon turu bu boslugu ortaya cikardi: EscalationServiceTest ctx'i ELLE kuruyor,
    // yani "damga basiliyor mu" sorusunu hicbir test sormuyordu. mailCtx tamamen etkisiz
    // hale getirilse bile suit yesil kaliyordu -- ozellik olu, testler mutlu.

    @Test
    @DisplayName("B2: notifyEmail=false ise ctx'e mail_disabled damgasi BASILIR")
    void mailCtx_stampsWhenDisabled() {
        var out = SchedulerService.mailCtx(new java.util.LinkedHashMap<>(java.util.Map.of("a", 1)), false);
        assertThat(out).containsEntry("mail_disabled", true).containsEntry("a", 1);
    }

    @Test
    @DisplayName("B2: notifyEmail true/null ise ctx'e DOKUNULMAZ (eski satirlar mail almaya devam eder)")
    void mailCtx_untouchedWhenEnabledOrNull() {
        var base = new java.util.LinkedHashMap<String, Object>(java.util.Map.of("a", 1));
        assertThat(SchedulerService.mailCtx(base, true)).isSameAs(base);
        // Kolon sonradan eklendi: ESKI satirlar null tasir ve susturulmamali.
        assertThat(SchedulerService.mailCtx(base, null)).isSameAs(base);
    }

    @Test
    @DisplayName("B2: iki kanal bayragi BIRBIRINDEN bagimsiz damgalanir")
    void chanCtx_stampsIndependently() {
        var only = SchedulerService.chanCtx(new java.util.LinkedHashMap<>(), false, true);
        assertThat(only).containsEntry("mail_disabled", true).doesNotContainKey("push_disabled");

        var push = SchedulerService.chanCtx(new java.util.LinkedHashMap<>(), true, false);
        assertThat(push).containsEntry("push_disabled", true).doesNotContainKey("mail_disabled");

        var both = SchedulerService.chanCtx(new java.util.LinkedHashMap<>(), false, false);
        assertThat(both).containsEntry("mail_disabled", true).containsEntry("push_disabled", true);
    }

    // -- B3: domainItem ESIK vs GECICI ayrimi --------------------------------
    // Kendi degisikligimde yakalanan hata: confirmCtx sarmalayicisi domainItem'in BILINCLI
    // "aninda alarm" ayarini (0 deneme) eziyordu. Esik durumunda ("30 gun kaldi") 30 sn sonra
    // tekrar sormak cevabi degistirmez, yalnizca alarmi 90 sn geciktirir. UNKNOWN farklidir:
    // sorgu BASARISIZ demektir ve gercekten gecici olabilir.

    private com.sitemonitor.model.DomainMonitor domainMon() {
        var m = new com.sitemonitor.model.DomainMonitor();
        m.setId(1L); m.setName("ornek"); m.setDomain("ornek.example.com"); m.setTeamId(5L);
        m.setConfirmAttempts(4); m.setConfirmIntervalSeconds(45);
        m.setRecoveryChecks(2); m.setRecoveryIntervalSeconds(15);
        return m;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> itemCtx(Object item) {
        return (java.util.Map<String, Object>) org.springframework.test.util.ReflectionTestUtils
                .invokeGetterMethod(item, "ctxExtra");
    }

    // ── Alarm seviyesi izlemeden (2026-09-19): varsayılan WARNING, HIGH/CRITICAL seçilebilir; hesaplanan kademe ezilmez ──
    @Test
    @DisplayName("chanCtx(ctx, izleme): alert_level damgası — null → WARNING, seçili → aynen; ctx'te hazır seviye EZİLMEZ")
    void chanCtx_stampsMonitorAlertLevel() {
        com.sitemonitor.model.HttpMonitor m = new com.sitemonitor.model.HttpMonitor();
        m.setNotifyEmail(true); m.setNotifyWebhook(true);
        assertThat(SchedulerService.chanCtx(new java.util.LinkedHashMap<>(), m).get("alert_level")).isEqualTo("WARNING");
        m.setAlertLevel("CRITICAL");
        assertThat(SchedulerService.chanCtx(new java.util.LinkedHashMap<>(), m).get("alert_level")).isEqualTo("CRITICAL");
        java.util.Map<String, Object> computed = new java.util.LinkedHashMap<>(java.util.Map.of("alert_level", "HIGH"));
        assertThat(SchedulerService.chanCtx(computed, m).get("alert_level")).isEqualTo("HIGH");   // hesaplanan kademe korunur
    }

    @Test
    @DisplayName("domainItem: EXPIRY hesaplanan seviyeyi taşır; CHANGED/TRANSFER_LOCK/BLACKLIST/UNKNOWN izlemenin seviyesini (varsayılan WARNING)")
    void domainItem_levelPolicy() {
        var mon = domainMon();
        var expiry = itemCtx(org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                scheduler, "domainItem", EscalationService.TYPE_DOMAINMON_EXPIRY, mon, java.util.Map.of("days_remaining", 3), false, "CRITICAL"));
        assertThat(expiry.get("alert_level")).isEqualTo("CRITICAL");
        var changed = itemCtx(org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                scheduler, "domainItem", EscalationService.TYPE_DOMAINMON_CHANGED, mon, java.util.Map.of(), false, null));
        assertThat(changed.get("alert_level")).isEqualTo("WARNING");
        mon.setAlertLevel("HIGH");
        var changedHigh = itemCtx(org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                scheduler, "domainItem", EscalationService.TYPE_DOMAINMON_CHANGED, mon, java.util.Map.of(), false, null));
        assertThat(changedHigh.get("alert_level")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("B3: ESIK alarmi (EXPIRY) ANINDA acilir - izlemenin dogrulama ayari UYGULANMAZ")
    void domainItem_thresholdType_immediate() {
        Object item = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                scheduler, "domainItem", EscalationService.TYPE_DOMAINMON_EXPIRY,
                domainMon(), java.util.Map.of("days_remaining", 30), false, "WARNING");
        var ctx = itemCtx(item);
        assertThat(((Number) ctx.get("monitor_confirm_attempts")).intValue()).isZero();
        assertThat(((Number) ctx.get("monitor_recovery_checks")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("B3: GECICI alarm (UNKNOWN) izlemenin dogrulama ayarini KULLANIR")
    void domainItem_transientType_usesMonitorSettings() {
        Object item = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                scheduler, "domainItem", EscalationService.TYPE_DOMAINMON_UNKNOWN,
                domainMon(), java.util.Map.of(), false, "WARNING");
        var ctx = itemCtx(item);
        assertThat(((Number) ctx.get("monitor_confirm_attempts")).intValue()).isEqualTo(4);
        assertThat(((Number) ctx.get("monitor_confirm_interval_ms")).longValue()).isEqualTo(45_000L);
        assertThat(((Number) ctx.get("monitor_recovery_checks")).intValue()).isEqualTo(2);
    }

    // Regression: ISSUE-001 — teyit zinciri hedef canlılığı (silinen/duraklatılan monitör alarm açmasın)
    // Found by /qa on 2026-09-10 · Report: .gstack/qa-reports/qa-report-localhost-2026-09-10.md

    private static MonitoringOutageService.SweepItem sweepItem(String type, String domain, Map<String, Object> ctx) {
        return new MonitoringOutageService.SweepItem(type, domain, "d", false, "timeout", ctx, java.util.Map::of);
    }

    @Test
    @DisplayName("ISSUE-001: envanter silinmiş/pasif → ACCESSIBILITY zinciri için hedef izlenmiyor")
    void isStillMonitored_inventoryDeleted_false() {
        CertificateInventory gone = new CertificateInventory();
        gone.setDomain("gone.example.com"); gone.setActive(false); gone.setDeletedAt("2026-09-10T00:00:00");
        lenient().when(inventoryRepo.findByDomain("gone.example.com")).thenReturn(Optional.of(gone));
        CertificateInventory live = new CertificateInventory();
        live.setDomain("live.example.com"); live.setActive(true);
        lenient().when(inventoryRepo.findByDomain("live.example.com")).thenReturn(Optional.of(live));

        assertThat(scheduler.isStillMonitored(sweepItem(EscalationService.TYPE_ACCESSIBILITY, "gone.example.com", Map.of()))).isFalse();
        assertThat(scheduler.isStillMonitored(sweepItem(EscalationService.TYPE_ACCESSIBILITY, "live.example.com", Map.of()))).isTrue();
        assertThat(scheduler.isStillMonitored(sweepItem(EscalationService.TYPE_ACCESSIBILITY, "unknown.example.com", Map.of())))
                .as("envanterde hiç yok = silinmiş/purge edilmiş").isFalse();
    }

    @Test
    @DisplayName("ISSUE-001: HTTP monitörü silinmiş ya da duraklatılmış → izlenmiyor; aktifse ve kimlik yoksa → izleniyor")
    void isStillMonitored_httpMonitorStates() {
        HttpMonitor paused = new HttpMonitor(); paused.setId(7L); paused.setActive(false);
        HttpMonitor active = new HttpMonitor(); active.setId(8L); active.setActive(true);
        lenient().when(httpMonitorRepo.findById(7L)).thenReturn(Optional.of(paused));
        lenient().when(httpMonitorRepo.findById(8L)).thenReturn(Optional.of(active));
        lenient().when(httpMonitorRepo.findById(9L)).thenReturn(Optional.empty());

        assertThat(scheduler.isStillMonitored(sweepItem(EscalationService.TYPE_HTTP_DOWN, "https://p/", Map.of("monitor_id", 7L)))).isFalse();
        assertThat(scheduler.isStillMonitored(sweepItem(EscalationService.TYPE_HTTP_DOWN, "https://a/", Map.of("monitor_id", 8L)))).isTrue();
        assertThat(scheduler.isStillMonitored(sweepItem(EscalationService.TYPE_HTTP_DOWN, "https://x/", Map.of("monitor_id", 9L)))).isFalse();
        assertThat(scheduler.isStillMonitored(sweepItem(EscalationService.TYPE_HTTP_DOWN, "https://old/", Map.of())))
                .as("kimliksiz kalem: eski davranış korunur").isTrue();
    }

    @Test
    @DisplayName("ISSUE-001: envanter-türevi PORT satırı aktif ama envanteri silinmişse izlenmiyor; standalone ise envantere bakılmaz")
    void isStillMonitored_derivedPortFollowsInventory() {
        PortMonitor derived = new PortMonitor(); derived.setId(3L); derived.setActive(true); derived.setStandalone(false); derived.setHost("gone.example.com");
        PortMonitor standalone = new PortMonitor(); standalone.setId(4L); standalone.setActive(true); standalone.setStandalone(true); standalone.setHost("gone.example.com");
        lenient().when(portMonitorRepo.findById(3L)).thenReturn(Optional.of(derived));
        lenient().when(portMonitorRepo.findById(4L)).thenReturn(Optional.of(standalone));
        lenient().when(inventoryRepo.findByDomain("gone.example.com")).thenReturn(Optional.empty());

        assertThat(scheduler.isStillMonitored(sweepItem(EscalationService.TYPE_PORT_DOWN, "gone.example.com", Map.of("monitor_id", 3L)))).isFalse();
        assertThat(scheduler.isStillMonitored(sweepItem(EscalationService.TYPE_PORT_DOWN, "gone.example.com", Map.of("monitor_id", 4L)))).isTrue();
    }
    // ── Alan başına kontrol sıklığı (2026-09-12) ─────────────────────────────────────────────

    private static Map<String, Object> invRow(String domain, Integer intervalHours) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("domain", domain);
        if (intervalHours != null) m.put("check_interval_hours", intervalHours);
        return m;
    }

    private static java.util.Optional<com.sitemonitor.model.LatestCheck> lastCheckedAgo(java.time.Duration ago) {
        com.sitemonitor.model.LatestCheck lc = new com.sitemonitor.model.LatestCheck();
        lc.setCheckedAt(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now().minus(ago)));
        return java.util.Optional.of(lc);
    }

    @Test
    @DisplayName("2026-09-12: sıklık boş/1 saat → her süpürmede; 24 saat → 3 saat önce kontrol edildiyse atlanır")
    void dueForScheduledSweep_skipsDomainsWhoseOwnIntervalIsNotDue() {
        when(latestCheckRepo.findById("daily.example.com")).thenReturn(lastCheckedAgo(java.time.Duration.ofHours(3)));
        List<Map<String, Object>> due = scheduler.dueForScheduledSweep(List.of(
                invRow("global.example.com", null),
                invRow("hourly.example.com", 1),
                invRow("daily.example.com", 24)));
        assertThat(due).extracting(m -> m.get("domain"))
                .containsExactly("global.example.com", "hourly.example.com");
        // boş/1 için repo'ya hiç sorulmaz (sıcak yol ucuz kalır)
        verify(latestCheckRepo, never()).findById("global.example.com");
        verify(latestCheckRepo, never()).findById("hourly.example.com");
    }

    @Test
    @DisplayName("2026-09-12: vadesi gelen (25 sa önce), hiç kontrol edilmemiş ve tarihi bozuk alanlar 24 saatlikte de girer")
    void dueForScheduledSweep_includesDueNeverCheckedAndUnparsable() {
        when(latestCheckRepo.findById("old.example.com")).thenReturn(lastCheckedAgo(java.time.Duration.ofHours(25)));
        when(latestCheckRepo.findById("never.example.com")).thenReturn(java.util.Optional.empty());
        com.sitemonitor.model.LatestCheck broken = new com.sitemonitor.model.LatestCheck();
        broken.setCheckedAt("not-a-date");
        when(latestCheckRepo.findById("broken.example.com")).thenReturn(java.util.Optional.of(broken));
        List<Map<String, Object>> due = scheduler.dueForScheduledSweep(List.of(
                invRow("old.example.com", 24), invRow("never.example.com", 24), invRow("broken.example.com", 24)));
        assertThat(due).extracting(m -> m.get("domain"))
                .containsExactly("old.example.com", "never.example.com", "broken.example.com");
    }

    @Test
    @DisplayName("2026-09-12: 5 dk tolerans — 23 sa 57 dk önce kontrol edilen günlük alan bu turu KAÇIRMAZ; 23 sa 50 dk ise bekler")
    void dueForScheduledSweep_fiveMinuteToleranceAroundCronBoundary() {
        when(latestCheckRepo.findById("edge.example.com")).thenReturn(lastCheckedAgo(java.time.Duration.ofHours(24).minusMinutes(3)));
        assertThat(scheduler.dueForScheduledSweep(List.of(invRow("edge.example.com", 24)))).hasSize(1);
        when(latestCheckRepo.findById("wait.example.com")).thenReturn(lastCheckedAgo(java.time.Duration.ofHours(24).minusMinutes(10)));
        assertThat(scheduler.dueForScheduledSweep(List.of(invRow("wait.example.com", 24)))).isEmpty();
    }

    @Test
    @DisplayName("2026-09-12: envanter satırı check_interval_hours taşır (dolu ise), timeout gibi")
    void loadDomainsFromInventory_carriesCheckIntervalHours() {
        CertificateInventory a = new CertificateInventory(); a.setDomain("weekly.example.com"); a.setActive(true); a.setCheckIntervalHours(168);
        CertificateInventory b = new CertificateInventory(); b.setDomain("plain.example.com"); b.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a, b));
        List<Map<String, Object>> rows = scheduler.loadDomainsFromInventory();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("check_interval_hours", 168);
        assertThat(rows.get(1)).doesNotContainKey("check_interval_hours");
    }
    @Test
    @DisplayName("2026-09-12: alan başına sıradaki kontrol — haftalık alan 2 gün önce kontrol edildiyse ~5 gün sonrası; boş sıklık = genel süpürme")
    void nextCertificateSweepAt_perDomainRespectsOwnInterval() {
        String global = scheduler.nextCertificateSweepAt();
        assertThat(scheduler.nextCertificateSweepAt("plain.example.com", null)).isEqualTo(global);
        assertThat(scheduler.nextCertificateSweepAt("plain.example.com", 1)).isEqualTo(global);

        when(latestCheckRepo.findById("weekly.example.com")).thenReturn(lastCheckedAgo(java.time.Duration.ofDays(2)));
        String next = scheduler.nextCertificateSweepAt("weekly.example.com", 168);
        java.time.Instant nextI = java.time.LocalDateTime.parse(next).toInstant(java.time.ZoneOffset.UTC);
        java.time.Instant dueFrom = java.time.Instant.now().plus(java.time.Duration.ofDays(5)).minusSeconds(5 * 60);
        assertThat(nextI).isAfterOrEqualTo(dueFrom.minusSeconds(2));
        assertThat(nextI).isBefore(dueFrom.plus(java.time.Duration.ofHours(1)).plusSeconds(2));

        // vadesi çoktan geçmiş (sıklık 24 sa, 3 gün önce) → sıradaki genel süpürme
        when(latestCheckRepo.findById("late.example.com")).thenReturn(lastCheckedAgo(java.time.Duration.ofDays(3)));
        assertThat(scheduler.nextCertificateSweepAt("late.example.com", 24)).isEqualTo(global);
    }
    @Test
    @DisplayName("2026-09-12: stale süpürmesi de sıklığa uyar — 3 sa'dır kontrolsüz GÜNLÜK alan yeniden kontrol EDİLMEZ, saatlik olan edilir")
    void checkStaleInventory_honoursPerDomainInterval() {
        CertificateInventory daily = new CertificateInventory(); daily.setDomain("daily.example.com"); daily.setPort(443); daily.setActive(true); daily.setCheckIntervalHours(24);
        CertificateInventory hourly = new CertificateInventory(); hourly.setDomain("hourly.example.com"); hourly.setPort(443); hourly.setActive(true); hourly.setTimeoutSeconds(7);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(daily, hourly));
        // 65 dk penceresinde hiçbiri taze değil (her ikisi de 3 sa önce kontrol edildi)
        when(latestCheckRepo.findByCheckedAtGreaterThanEqual(anyString())).thenReturn(java.util.Set.of());
        when(latestCheckRepo.findById("daily.example.com")).thenReturn(lastCheckedAgo(java.time.Duration.ofHours(3)));
        Map<String, Object> ok = Map.of("domain", "hourly.example.com", "status", "valid");
        when(checkerService.checkAsync(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(ok));

        scheduler.checkStaleInventory();

        // Yalnız saatlik alan gider; alan başına timeout_seconds artık stale süpürmesinde de taşınır.
        verify(checkerService).checkAsync("hourly.example.com", 443, false, null, 7);
        verify(checkerService, never()).checkAsync(org.mockito.ArgumentMatchers.eq("daily.example.com"), anyInt(), anyBoolean(), any(), any());
    }
}
