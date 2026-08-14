package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
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
    @Mock DnsMonitorRepository dnsMonitorRepo;
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
        service = new MonitoringOutageService(alertEventRepo, escalationService, jdbcTemplate, dnsRecordRepo, dnsMonitorRepo, appSettings);
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
    @DisplayName("DNS_CHANGED günlük re-alert: monitörde değişiklik alarmı KAPALI → re-alert atlanır")
    void dnsChanged_dailyReAlert_suppressedWhenAlertDisabled() {
        AlertEvent open = openAlert("muted.example.com", EscalationService.TYPE_DNS_CHANGED);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));
        DnsRecord changedRow = new DnsRecord();
        changedRow.setMonitorId(42L);
        changedRow.setRecordType("A");
        changedRow.setValue("2.2.2.2");
        changedRow.setCheckedAt("2026-06-10T09:00:00");
        when(dnsRecordRepo.findChangedByDomain(eq("muted.example.com"), any(Pageable.class)))
                .thenReturn(List.of(changedRow));
        com.sitemonitor.model.DnsMonitor mon = new com.sitemonitor.model.DnsMonitor();
        mon.setDnsChangeAlertEnabled(false);   // monitör bazlı kapalı
        when(dnsMonitorRepo.findById(42L)).thenReturn(java.util.Optional.of(mon));

        service.handleDnsSweep(
                List.of(item(EscalationService.TYPE_DNS_FAILURE, "muted.example.com", "A", true,
                        Map.of("record_type", "A"), MonitoringOutageServiceTest::up)),
                List.of(), List.of(), List.of(), List.of());

        verify(escalationService, never()).processConfirmedOutage(
                eq("muted.example.com"), eq(EscalationService.TYPE_DNS_CHANGED), anyString(), any());
    }

    @Test
    @DisplayName("DNS_CHANGED günlük re-alert: son değişen değerler beklenen sette (iç/dış IP flip) → re-alert atlanır")
    void dnsChanged_dailyReAlert_suppressedWhenExpectedFlip() {
        AlertEvent open = openAlert("flip.example.com", EscalationService.TYPE_DNS_CHANGED);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));
        DnsRecord changedRow = new DnsRecord();
        changedRow.setMonitorId(43L);
        changedRow.setRecordType("A");
        changedRow.setValue("192.168.10.249");
        changedRow.setCheckedAt("2026-06-10T09:00:00");
        when(dnsRecordRepo.findChangedByDomain(eq("flip.example.com"), any(Pageable.class)))
                .thenReturn(List.of(changedRow));
        com.sitemonitor.model.DnsMonitor mon = new com.sitemonitor.model.DnsMonitor();
        mon.setExpectedValue("192.168.10.249\n217.169.196.197");   // her iki bilinen IP sabitli
        when(dnsMonitorRepo.findById(43L)).thenReturn(java.util.Optional.of(mon));

        service.handleDnsSweep(
                List.of(item(EscalationService.TYPE_DNS_FAILURE, "flip.example.com", "A", true,
                        Map.of("record_type", "A"), MonitoringOutageServiceTest::up)),
                List.of(), List.of(), List.of(), List.of());

        verify(escalationService, never()).processConfirmedOutage(
                eq("flip.example.com"), eq(EscalationService.TYPE_DNS_CHANGED), anyString(), any());
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
    @DisplayName("activeConfirmations: zincir başlarken attempt=0/total görünür; deneme koşarken X/N'e ilerler; bitince boşalır")
    void activeConfirmations_exposesLiveState() {
        // Recording executor: schedule ÇALIŞTIRMAZ → zincir asılı, durum gözlemlenebilir.
        ScheduledThreadPoolExecutor recording = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) { return null; }
        };
        ReflectionTestUtils.setField(service, "confirmExecutor", recording);
        MonitoringOutageService.SweepItem it = item(EscalationService.TYPE_PAGE_INTEGRITY,
                "https://x.example.com/", "1 kırık", false,
                Map.of("monitor_confirm_attempts", 3, "monitor_confirm_interval_ms", 30000L),
                MonitoringOutageServiceTest::up);

        service.startConfirmation(it);

        var states = service.activeConfirmations("https://x.example.com/");
        assertThat(states).hasSize(1);
        assertThat(states.get(0).get("attempt")).isEqualTo(0);
        assertThat(states.get(0).get("total_attempts")).isEqualTo(3);
        assertThat(states.get(0).get("alert_type")).isEqualTo(EscalationService.TYPE_PAGE_INTEGRITY);
        assertThat(states.get(0)).containsKeys("started_at", "next_attempt_at");
        // Domain filtresi: eşleşmeyen → boş
        assertThat(service.activeConfirmations("baska.example.com")).isEmpty();

        // Deneme #1 koşarken (recheck "up" döner → zincir biter): durum önce 1/3'e ilerler, sonra temizlenir.
        service.runConfirmAttempt(EscalationService.TYPE_PAGE_INTEGRITY + ":https://x.example.com/:1 kırık",
                it, "2026-08-03T20:00:00", new java.util.ArrayList<>(), 1);
        assertThat(service.activeConfirmations(null)).isEmpty();   // up → remove edildi
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

    // ── Sentetik: teyit anahtarı + "yürütülemedi" cevabı ────────────────────────────────────

    /** Kontrol YÜRÜTÜLEMEDİ cevabı (k6 havuzu dolu / k6 yok). */
    private static Map<String, Object> skipped() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "skipped");
        m.put("error", "k6 havuzu dolu — kontrol atlandı");
        return m;
    }

    @Test
    @DisplayName("Sentetik teyit anahtarı DETAIL içermez — hata metni değişince MÜKERRER zincir başlamaz")
    void scriptedConfirmKeyIgnoresVolatileDetail() {
        // scriptedDetail check sayaçlarını + hata metnini taşır ("FAIL — 0✓/2✗ · request timeout")
        // ve her sweep'te değişebilir. Anahtara girdiğinde putIfAbsent guard'ı tutmuyor, aynı
        // monitör için paralel zincirler başlıyor ve her biri ayrı bir k6 permit'i yiyordu.
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<String, Object>> neverReturns = () -> { calls.incrementAndGet(); return down("x"); };
        ReflectionTestUtils.setField(service, "confirmExecutor", new ScheduledThreadPoolExecutor(1));  // ASENKRON: zincir açık kalsın

        service.handleSweepResults(EscalationService.TYPE_SCRIPTED_FAIL, List.of(
                item(EscalationService.TYPE_SCRIPTED_FAIL, "Login Akisi",
                        "FAIL — 0✓/2✗ · request timeout", false, Map.of(), neverReturns)));
        // AYNI monitör, FARKLI detail (bir sonraki sweep'in ürettiği metin)
        service.handleSweepResults(EscalationService.TYPE_SCRIPTED_FAIL, List.of(
                item(EscalationService.TYPE_SCRIPTED_FAIL, "Login Akisi",
                        "FAIL — 1✓/1✗ · connection refused", false, Map.of(), neverReturns)));

        assertThat(service.activeConfirmations("Login Akisi")).hasSize(1);   // İKİ değil
    }

    @Test
    @DisplayName("Doğrulama YÜRÜTÜLEMEDİ → zincir iptal, alarm AÇILMAZ (altyapı darlığı kesinti değildir)")
    void skippedRecheckAbortsChainWithoutAlarm() {
        // Aksi hâlde k6 havuzu dolduğunda hedefte hiçbir sorun yokken KRİTİK alarm açılırdı.
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<String, Object>> alwaysSkipped = () -> { calls.incrementAndGet(); return skipped(); };

        service.handleSweepResults(EscalationService.TYPE_SCRIPTED_FAIL, List.of(
                item(EscalationService.TYPE_SCRIPTED_FAIL, "Odeme Akisi", "FAIL", false,
                        Map.of(), alwaysSkipped)));

        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), anyMap());
        assertThat(calls.get()).isEqualTo(1);                       // 3 deneme değil: ilk "skipped"te durur
        assertThat(service.activeConfirmations("Odeme Akisi")).isEmpty();   // zincir temizlendi
    }

    @Test
    @DisplayName("Sentetik OLMAYAN türlerde detail anahtarın PARÇASI kalır (port 443 vs 8443 ayrı kesinti)")
    void nonScriptedKeyStillIncludesDetail() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<String, Object>> stuck = () -> { calls.incrementAndGet(); return down("x"); };
        ReflectionTestUtils.setField(service, "confirmExecutor", new ScheduledThreadPoolExecutor(1));

        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                item(EscalationService.TYPE_PORT_DOWN, "host.example.com", "443", false, Map.of(), stuck),
                item(EscalationService.TYPE_PORT_DOWN, "host.example.com", "8443", false, Map.of(), stuck)));

        assertThat(service.activeConfirmations("host.example.com")).hasSize(2);
    }
}
