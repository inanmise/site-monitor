package com.certmonitor.service;

import com.certmonitor.model.AlertEvent;
import com.certmonitor.model.DnsRecord;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.DnsRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringOutageServiceTest {

    @Mock AlertEventRepository alertEventRepo;
    @Mock EscalationService escalationService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock AppSettingsService appSettings;

    private MonitoringOutageService service;

    /** schedule() çağrısını anında, aynı thread'de çalıştıran executor —
     *  teyit zinciri deterministik şekilde senkron tamamlanır. */
    private static ScheduledThreadPoolExecutor immediateExecutor() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                command.run();
                return null;
            }
        };
    }

    @BeforeEach
    void setUp() {
        service = new MonitoringOutageService(alertEventRepo, escalationService, jdbcTemplate, dnsRecordRepo, appSettings);
        // AppSettings override yok → fallback (alan değeri) döner
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        ReflectionTestUtils.setField(service, "uptimeAlertEnabled", true);
        ReflectionTestUtils.setField(service, "portAlertEnabled", true);
        ReflectionTestUtils.setField(service, "dnsAlertEnabled", true);
        ReflectionTestUtils.setField(service, "confirmAttempts", 3);
        ReflectionTestUtils.setField(service, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(service, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(service, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(service, "confirmExecutor", immediateExecutor());
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
    }

    private static Map<String, Object> up() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "up");
        return m;
    }

    private static Map<String, Object> down(String error) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "down");
        m.put("error", error);
        return m;
    }

    /** İlk N-1 çağrıda down, sonra up dönen sayaçlı recheck supplier'ı. */
    private static Supplier<Map<String, Object>> downThenUp(int downCount, AtomicInteger calls) {
        return () -> calls.incrementAndGet() <= downCount ? down("timeout") : up();
    }

    private static MonitoringOutageService.SweepItem item(String type, String domain, String detail,
                                                          boolean isUp, Map<String, Object> extra,
                                                          Supplier<Map<String, Object>> recheck) {
        return new MonitoringOutageService.SweepItem(type, domain, detail, isUp,
                isUp ? null : "timeout", extra, recheck);
    }

    private static AlertEvent openAlert(String domain, String type) {
        AlertEvent e = new AlertEvent();
        e.setId(9L);
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        e.setResolved(false);
        return e;
    }

    @Test
    @DisplayName("ACCESSIBILITY: 3/3 doğrulama başarısız → CRITICAL outage 3 denemeli context ile bir kez")
    void accessibility_allFail_confirmsOnce() {
        AtomicInteger calls = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_ACCESSIBILITY, List.of(
                item(EscalationService.TYPE_ACCESSIBILITY, "down.example.com", "443", false,
                        Map.of("port", 443), downThenUp(99, calls)),
                item(EscalationService.TYPE_ACCESSIBILITY, "ok.example.com", "443", true, Map.of(), MonitoringOutageServiceTest::up)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(escalationService, times(1)).processConfirmedOutage(
                eq("down.example.com"), eq(EscalationService.TYPE_ACCESSIBILITY), eq("CRITICAL"), ctx.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) ctx.getValue().get("confirm_attempts");
        assertThat(attempts).hasSize(3);
        assertThat(ctx.getValue().get("port")).isEqualTo(443);
        assertThat(ctx.getValue().get("detail")).isEqualTo("443");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("PORT_DOWN: 2. doğrulamada port açılırsa alarm üretilmez (geçici dalgalanma)")
    void port_recoversMidway_noAlert() {
        AtomicInteger calls = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                item(EscalationService.TYPE_PORT_DOWN, "blip.example.com", "8443/TCP", false,
                        Map.of("port", 8443, "protocol", "TCP"), downThenUp(1, calls))));

        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("DNS_FAILURE: 3/3 başarısız → CRITICAL, ctx record_type taşır")
    void dnsFailure_allFail_confirms() {
        AtomicInteger calls = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_DNS_FAILURE, List.of(
                item(EscalationService.TYPE_DNS_FAILURE, "down.example.com", "A", false,
                        Map.of("record_type", "A"), downThenUp(99, calls))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(escalationService).processConfirmedOutage(
                eq("down.example.com"), eq(EscalationService.TYPE_DNS_FAILURE), eq("CRITICAL"), ctx.capture());
        assertThat(ctx.getValue().get("record_type")).isEqualTo("A");
    }

    @Test
    @DisplayName("DNS_CHANGED: 3× teyit sonrası HIGH alarm; ctx eski/yeni değerleri taşır")
    void dnsChange_confirmsAfterRechecks() {
        AtomicInteger calls = new AtomicInteger();
        service.handleDnsSweep(
                List.of(item(EscalationService.TYPE_DNS_FAILURE, "changed.example.com", "A", true,
                        Map.of("record_type", "A"), downThenUp(99, new AtomicInteger()))),
                List.of(),
                List.of(new MonitoringOutageService.DnsChange(
                        "changed.example.com", "A", "1.2.3.4\n5.6.7.8", "9.9.9.9", "2026-06-11T10:00:00", null,
                        downThenUp(99, calls))),   // değişiklik kalıcı → 3 recheck de "down" → teyit edilir
                List.of(),
                List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(escalationService).processConfirmedOutage(
                eq("changed.example.com"), eq(EscalationService.TYPE_DNS_CHANGED), eq("HIGH"), ctx.capture());
        assertThat(ctx.getValue().get("old_values")).isEqualTo(List.of("1.2.3.4", "5.6.7.8"));
        assertThat(ctx.getValue().get("new_values")).isEqualTo(List.of("9.9.9.9"));
        assertThat(calls.get()).isEqualTo(3);   // 3 ardışık teyit denemesi
    }

    @Test
    @DisplayName("DNS_CHANGED: recheck baseline'a döner (geçici/rotasyon) → alarm ÜRETİLMEZ")
    void dnsChange_revertsMidway_noAlert() {
        AtomicInteger calls = new AtomicInteger();
        service.handleDnsSweep(
                List.of(),
                List.of(),
                List.of(new MonitoringOutageService.DnsChange(
                        "flap.example.com", "A", "1.2.3.4", "9.9.9.9", "2026-06-11T10:00:00", null,
                        () -> { calls.incrementAndGet(); return up(); })),   // ilk recheck'te baseline'a döndü
                List.of(),
                List.of());

        verify(escalationService, never()).processConfirmedOutage(
                anyString(), eq(EscalationService.TYPE_DNS_CHANGED), anyString(), any());
        assertThat(calls.get()).isEqualTo(1);   // ilk denemede "up" → iptal
    }

    @Test
    @DisplayName("DNS_CHANGED günlük re-alert: açık unacked alarm + bu sweep'te değişiklik yok → ctx yeniden kurulur")
    void dnsChanged_dailyReAlert_reconstructsCtx() {
        AlertEvent open = openAlert("stale.example.com", EscalationService.TYPE_DNS_CHANGED);
        open.setAlertLevel("HIGH");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));
        DnsRecord changedRow = new DnsRecord();
        changedRow.setRecordType("A");
        changedRow.setPreviousValue("1.1.1.1");
        changedRow.setValue("2.2.2.2");
        changedRow.setCheckedAt("2026-06-10T09:00:00");
        when(dnsRecordRepo.findChangedByDomain(eq("stale.example.com"), any(Pageable.class)))
                .thenReturn(List.of(changedRow));

        service.handleDnsSweep(
                List.of(item(EscalationService.TYPE_DNS_FAILURE, "stale.example.com", "A", true,
                        Map.of("record_type", "A"), MonitoringOutageServiceTest::up)),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(escalationService).processConfirmedOutage(
                eq("stale.example.com"), eq(EscalationService.TYPE_DNS_CHANGED), eq("HIGH"), ctx.capture());
        assertThat(ctx.getValue().get("new_values")).isEqualTo(List.of("2.2.2.2"));
    }

    @Test
    @DisplayName("Agregasyon any-down: 2 monitörden 1 down + açık alarm → re-alert yolu, resolve YOK")
    void aggregation_anyDown_reAlertsNotResolves() {
        when(alertEventRepo.findOpenByDomainIn(anyCollection()))
                .thenReturn(List.of(openAlert("multi.example.com", EscalationService.TYPE_PORT_DOWN)));

        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                item(EscalationService.TYPE_PORT_DOWN, "multi.example.com", "443/TCP", true,
                        Map.of("port", 443, "protocol", "TCP"), MonitoringOutageServiceTest::up),
                item(EscalationService.TYPE_PORT_DOWN, "multi.example.com", "8443/TCP", false,
                        Map.of("port", 8443, "protocol", "TCP"), MonitoringOutageServiceTest::up)));

        verify(escalationService).processConfirmedOutage(
                eq("multi.example.com"), eq(EscalationService.TYPE_PORT_DOWN), eq("CRITICAL"), any());
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    @Test
    @DisplayName("Agregasyon all-up: tüm monitörler up + açık alarm → tek resolve")
    void aggregation_allUp_resolvesOnce() {
        when(alertEventRepo.findOpenByDomainIn(anyCollection()))
                .thenReturn(List.of(openAlert("recovered.example.com", EscalationService.TYPE_PORT_DOWN)));

        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                item(EscalationService.TYPE_PORT_DOWN, "recovered.example.com", "443/TCP", true,
                        Map.of(), MonitoringOutageServiceTest::up),
                item(EscalationService.TYPE_PORT_DOWN, "recovered.example.com", "8443/TCP", true,
                        Map.of(), MonitoringOutageServiceTest::up)));

        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(
                "recovered.example.com", EscalationService.TYPE_PORT_DOWN);
        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Açık alarm farklı tipte ise bu tip için teyit zinciri yine başlar (tip izolasyonu)")
    void aggregation_openAlertOfOtherType_doesNotBlockConfirmation() {
        when(alertEventRepo.findOpenByDomainIn(anyCollection()))
                .thenReturn(List.of(openAlert("x.example.com", EscalationService.TYPE_ACCESSIBILITY)));
        AtomicInteger calls = new AtomicInteger();

        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                item(EscalationService.TYPE_PORT_DOWN, "x.example.com", "443/TCP", false,
                        Map.of("port", 443, "protocol", "TCP"), downThenUp(99, calls))));

        verify(escalationService).processConfirmedOutage(
                eq("x.example.com"), eq(EscalationService.TYPE_PORT_DOWN), eq("CRITICAL"), any());
    }

    @Test
    @DisplayName("Devam eden teyit varken ikinci tetikleme çift zincir başlatmaz (in-flight guard)")
    void startConfirmation_inFlight_noDoubleSchedule() {
        AtomicInteger scheduled = new AtomicInteger();
        ScheduledThreadPoolExecutor recording = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                scheduled.incrementAndGet();
                return null; // çalıştırma — zincir asılı kalsın
            }
        };
        ReflectionTestUtils.setField(service, "confirmExecutor", recording);
        MonitoringOutageService.SweepItem it = item(EscalationService.TYPE_PORT_DOWN,
                "down.example.com", "443/TCP", false, Map.of(), MonitoringOutageServiceTest::up);

        service.startConfirmation(it);
        service.startConfirmation(it);

        assertThat(scheduled.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Bulk failure (tüm domainler down) → alarm pipeline'ı bastırılır")
    void sweep_bulkFailure_suppressed() {
        AtomicInteger calls = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                item(EscalationService.TYPE_PORT_DOWN, "a.example.com", "443/TCP", false, Map.of(), downThenUp(99, calls)),
                item(EscalationService.TYPE_PORT_DOWN, "b.example.com", "443/TCP", false, Map.of(), downThenUp(99, calls)),
                item(EscalationService.TYPE_PORT_DOWN, "c.example.com", "443/TCP", false, Map.of(), downThenUp(99, calls))));

        assertThat(calls.get()).isZero();
        verifyNoInteractions(escalationService);
    }

    @Test
    @DisplayName("Kilit başka replikada (unique violation) → aksiyon sessizce atlanır")
    void withLock_uniqueViolation_skipsAction() {
        when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), any(), any(), any()))
                .thenThrow(new RuntimeException("UNIQUE constraint failed: scheduler_lock.name"));
        when(alertEventRepo.findOpenByDomainIn(anyCollection()))
                .thenReturn(List.of(openAlert("recovered.example.com", EscalationService.TYPE_ACCESSIBILITY)));

        service.handleSweepResults(EscalationService.TYPE_ACCESSIBILITY, List.of(
                item(EscalationService.TYPE_ACCESSIBILITY, "recovered.example.com", "443", true,
                        Map.of(), MonitoringOutageServiceTest::up)));

        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    @Test
    @DisplayName("port.alert-enabled=false → port pipeline'ı devre dışı, uptime etkilenmez")
    void portAlertingDisabled_noop() {
        ReflectionTestUtils.setField(service, "portAlertEnabled", false);
        AtomicInteger calls = new AtomicInteger();

        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                item(EscalationService.TYPE_PORT_DOWN, "down.example.com", "443/TCP", false,
                        Map.of(), downThenUp(99, calls))));
        assertThat(calls.get()).isZero();
        verifyNoInteractions(escalationService);

        // uptime hâlâ aktif
        service.handleSweepResults(EscalationService.TYPE_ACCESSIBILITY, List.of(
                item(EscalationService.TYPE_ACCESSIBILITY, "down.example.com", "443",
                        false, Map.of("port", 443), downThenUp(99, calls))));
        verify(escalationService).processConfirmedOutage(
                eq("down.example.com"), eq(EscalationService.TYPE_ACCESSIBILITY), eq("CRITICAL"), any());
    }

    @Test
    @DisplayName("dns.alert-enabled=false → hem DNS_FAILURE hem DNS_CHANGED bastırılır")
    void dnsAlertingDisabled_noop() {
        ReflectionTestUtils.setField(service, "dnsAlertEnabled", false);
        AtomicInteger calls = new AtomicInteger();

        service.handleDnsSweep(
                List.of(item(EscalationService.TYPE_DNS_FAILURE, "down.example.com", "A", false,
                        Map.of(), downThenUp(99, calls))),
                List.of(),
                List.of(new MonitoringOutageService.DnsChange("c.example.com", "A", "1.1.1.1", "2.2.2.2", "now", null, () -> up())),
                List.of(),
                List.of());

        assertThat(calls.get()).isZero();
        verifyNoInteractions(escalationService);
    }

    @Test
    @DisplayName("levelFor: DNS_CHANGED → HIGH, diğerleri CRITICAL")
    void levelFor_mapping() {
        assertThat(MonitoringOutageService.levelFor(EscalationService.TYPE_DNS_CHANGED)).isEqualTo("HIGH");
        assertThat(MonitoringOutageService.levelFor(EscalationService.TYPE_PORT_DOWN)).isEqualTo("CRITICAL");
        assertThat(MonitoringOutageService.levelFor(EscalationService.TYPE_DNS_FAILURE)).isEqualTo("CRITICAL");
        assertThat(MonitoringOutageService.levelFor(EscalationService.TYPE_ACCESSIBILITY)).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("Aktif recovery (keyword): recoveryIntervalSeconds set + ardışık UP re-check → resolve bir kez")
    void activeRecovery_allUp_resolvesOnce() {
        ReflectionTestUtils.setField(service, "keywordAlertEnabled", true);
        ReflectionTestUtils.setField(service, "recoveryExecutor", immediateExecutor());
        when(alertEventRepo.findOpenByDomainIn(anyCollection()))
                .thenReturn(List.of(openAlert("kw.example.com", EscalationService.TYPE_KEYWORD)));
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> extra = Map.of("monitor_recovery_checks", 3, "monitor_recovery_interval_ms", 20000L);

        service.handleSweepResults(EscalationService.TYPE_KEYWORD, List.of(
                item(EscalationService.TYPE_KEYWORD, "kw.example.com", "keyword", true, extra,
                        () -> { calls.incrementAndGet(); return up(); })));

        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(
                "kw.example.com", EscalationService.TYPE_KEYWORD);
        assertThat(calls.get()).isEqualTo(2); // tetikleyici sweep=1 + 2 aktif re-check = 3 ardışık başarılı
    }

    @Test
    @DisplayName("Aktif recovery: doğrulama sırasında yeniden DOWN → alarm kapanmaz (resolve YOK)")
    void activeRecovery_downMidway_noResolve() {
        ReflectionTestUtils.setField(service, "keywordAlertEnabled", true);
        ReflectionTestUtils.setField(service, "recoveryExecutor", immediateExecutor());
        when(alertEventRepo.findOpenByDomainIn(anyCollection()))
                .thenReturn(List.of(openAlert("kw.example.com", EscalationService.TYPE_KEYWORD)));
        AtomicInteger calls = new AtomicInteger();
        // 1. re-check up, 2. re-check down → aktif döngü kesilir, alarm açık kalır
        Supplier<Map<String, Object>> upThenDown = () -> calls.incrementAndGet() <= 1 ? up() : down("timeout");
        Map<String, Object> extra = Map.of("monitor_recovery_checks", 3, "monitor_recovery_interval_ms", 20000L);

        service.handleSweepResults(EscalationService.TYPE_KEYWORD, List.of(
                item(EscalationService.TYPE_KEYWORD, "kw.example.com", "keyword", true, extra, upThenDown)));

        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        assertThat(calls.get()).isEqualTo(2); // n=1 up, n=2 down → dur
    }
}
