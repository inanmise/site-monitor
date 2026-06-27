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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

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
    @Mock NetworkOutageEventRepository networkOutageRepo;
    @Mock WeeklyReportReminderService weeklyReportReminderService;
    @Mock WeeklyAvailabilityReportService weeklyAvailabilityReportService;
    @Mock IncidentService incidentService;
    @Mock AppSettingsService appSettings;
    @Mock ThreadPoolTaskExecutor certCheckExecutor;

    SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SchedulerService(
                checkerService, certService, emailService, escalationService,
                inventoryRepo, latestCheckRepo, thresholdRepo, jdbcTemplate,
                userService, permissionService, dataSource, eventPublisher,
                portCheckerService, portMonitorRepo, portCheckRepo,
                dnsCheckerService, dnsMonitorRepo, dnsRecordRepo,
                uptimeHttpCheckerService, uptimeCheckRepo, monitoringOutageService,
                keywordCheckerService, keywordMonitorRepo, keywordResultRepo,
                pingCheckerService, pingMonitorRepo, pingCheckRepo,
                networkOutageRepo,
                weeklyReportReminderService, weeklyAvailabilityReportService, incidentService, appSettings);
        ReflectionTestUtils.setField(scheduler, "certCheckExecutor", certCheckExecutor);
        lenient().when(inventoryRepo.countByActiveTrue()).thenReturn(0L);
        // AppSettings: override yok → fallback (ikinci argüman) döner
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
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
        when(portCheckerService.check(eq("x.example.com"), eq(8443), anyInt()))
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
