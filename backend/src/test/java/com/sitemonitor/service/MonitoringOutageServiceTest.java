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
import static org.assertj.core.api.Assertions.assertThatCode;
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
    @Mock com.sitemonitor.repository.NetworkOutageEventRepository networkOutageRepo;

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
        service = new MonitoringOutageService(alertEventRepo, escalationService, jdbcTemplate, dnsRecordRepo, dnsMonitorRepo, appSettings, networkOutageRepo);
        // AppSettings override yok → fallback (alan değeri) döner
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        ReflectionTestUtils.setField(service, "uptimeAlertEnabled", true);
        ReflectionTestUtils.setField(service, "portAlertEnabled", true);
        ReflectionTestUtils.setField(service, "dnsAlertEnabled", true);
        // keyword/ping @Value alanları manuel kurulumda false kalıyor ve alertEnabled() sweep'i
        // en başta düşürüyordu — bu tiplerin testleri "sessizce hiç koşmamış" olurdu.
        ReflectionTestUtils.setField(service, "keywordAlertEnabled", true);
        ReflectionTestUtils.setField(service, "pingAlertEnabled", true);
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
                eq("down.example.com"), eq(EscalationService.TYPE_ACCESSIBILITY), eq("WARNING"), ctx.capture());
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
                eq("down.example.com"), eq(EscalationService.TYPE_DNS_FAILURE), eq("WARNING"), ctx.capture());
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
                        "changed.example.com", "A", "1.2.3.4\n5.6.7.8", "9.9.9.9", "2026-06-11T10:00:00", null, null,
                        downThenUp(99, calls))),   // değişiklik kalıcı → 3 recheck de "down" → teyit edilir
                List.of(),
                List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(escalationService).processConfirmedOutage(
                eq("changed.example.com"), eq(EscalationService.TYPE_DNS_CHANGED), eq("WARNING"), ctx.capture());
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
                        "flap.example.com", "A", "1.2.3.4", "9.9.9.9", "2026-06-11T10:00:00", null, null,
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
                eq("stale.example.com"), eq(EscalationService.TYPE_DNS_CHANGED), eq("WARNING"), ctx.capture());
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
        changedRow.setValue("192.168.1.10");
        changedRow.setCheckedAt("2026-06-10T09:00:00");
        when(dnsRecordRepo.findChangedByDomain(eq("flip.example.com"), any(Pageable.class)))
                .thenReturn(List.of(changedRow));
        com.sitemonitor.model.DnsMonitor mon = new com.sitemonitor.model.DnsMonitor();
        mon.setExpectedValue("192.168.1.10\n217.169.196.197");   // her iki bilinen IP sabitli
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
                eq("multi.example.com"), eq(EscalationService.TYPE_PORT_DOWN), eq("WARNING"), any());
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
                eq("x.example.com"), eq(EscalationService.TYPE_PORT_DOWN), eq("WARNING"), any());
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

    /**
     * ASIL KAPI. PAGESPEED_SLOW'un detail'ini {@code pageSpeedBreachDetail} üretir ve ÖLÇÜLEN
     * değeri taşır ("TTFB 2955 ms"). Teyit anahtarı detail'i içerirken her sweep farklı bir
     * anahtar doğuruyor, çift-zincir guard'ı hiç tutmuyordu: ihlal süren bir izleme için her
     * sweep 3 denemelik YENİ bir zincir açıyordu ve her deneme tam bir sayfa indirmesi
     * (yüzlerce istek, on MB'lar). Sentetikte çözülen kusurun aynısı.
     */
    @Test
    @DisplayName("PAGESPEED_SLOW: ölçülen değer DEĞİŞSE de aynı izlemeye ikinci zincir başlamaz")
    void pageSpeedSlow_detailChanges_stillOneChain() {
        AtomicInteger scheduled = new AtomicInteger();
        ScheduledThreadPoolExecutor recording = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                scheduled.incrementAndGet();
                return null;
            }
        };
        ReflectionTestUtils.setField(service, "confirmExecutor", recording);

        // Aynı izleme, ardışık iki sweep — yalnız ölçülen değer farklı.
        service.startConfirmation(item(EscalationService.TYPE_PAGESPEED_SLOW,
                "https://x.example.com/", "TTFB 2750 ms", false, Map.of(),
                MonitoringOutageServiceTest::up));
        service.startConfirmation(item(EscalationService.TYPE_PAGESPEED_SLOW,
                "https://x.example.com/", "TTFB 2955 ms", false, Map.of(),
                MonitoringOutageServiceTest::up));

        assertThat(scheduled.get())
                .as("ölçülen değer anahtara girerse her sweep yeni bir zincir açar")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("PAGESPEED_DOWN detail'e göre ayrışmaya DEVAM eder (farklı arıza = farklı zincir)")
    void pageSpeedDown_keepsDetailInKey() {
        AtomicInteger scheduled = new AtomicInteger();
        ScheduledThreadPoolExecutor recording = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                scheduled.incrementAndGet();
                return null;
            }
        };
        ReflectionTestUtils.setField(service, "confirmExecutor", recording);

        // DOWN'da detail ölçülen bir sayı değil ARIZA TÜRÜDÜR; ayrı tutmak doğrudur.
        service.startConfirmation(item(EscalationService.TYPE_PAGESPEED_DOWN,
                "https://x.example.com/", "sayfa HTTP 503", false, Map.of(),
                MonitoringOutageServiceTest::up));
        service.startConfirmation(item(EscalationService.TYPE_PAGESPEED_DOWN,
                "https://x.example.com/", "bağlantı zaman aşımı", false, Map.of(),
                MonitoringOutageServiceTest::up));

        assertThat(scheduled.get()).isEqualTo(2);
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
                eq("down.example.com"), eq(EscalationService.TYPE_ACCESSIBILITY), eq("WARNING"), any());
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
                List.of(new MonitoringOutageService.DnsChange("c.example.com", "A", "1.1.1.1", "2.2.2.2", "now", null, null, () -> up())),
                List.of(),
                List.of());

        assertThat(calls.get()).isZero();
        verifyNoInteractions(escalationService);
    }

    // 2026-09-19 ürün kararı: süre-bitişi dışındaki HER izleme alarmı varsayılan WARNING; seviye izlemeden
    // (sweep ctx alert_level) gelir, eskalasyon kontakları yalnız HIGH/CRITICAL'de eklenir.
    @Test
    @DisplayName("levelFor: bağlamsız yedek her tip için WARNING (eski HIGH/CRITICAL sabitleri kalktı)")
    void levelFor_mapping() {
        for (String t : List.of(EscalationService.TYPE_DNS_CHANGED, EscalationService.TYPE_PORT_DOWN,
                EscalationService.TYPE_DNS_FAILURE, EscalationService.TYPE_ACCESSIBILITY, EscalationService.TYPE_HTTP_DOWN,
                EscalationService.TYPE_PING_DOWN, EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW))
            assertThat(MonitoringOutageService.levelFor(t)).as(t).isEqualTo("WARNING");
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

    // ── SCRIPTED_SLOW ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("izleme seviyesi bağlamdan geçer: ctx alert_level=CRITICAL ise processConfirmedOutage o seviyeyle çağrılır (yedek WARNING ezilir)")
    void monitorLevelFromContextWins() {
        service.handleSweepResults(EscalationService.TYPE_SCRIPTED_FAIL, List.of(
                item(EscalationService.TYPE_SCRIPTED_FAIL, "lvl.example.com", "k6",
                        false, Map.of("alert_level", "CRITICAL", "team_id", 5L), downThenUp(99, new java.util.concurrent.atomic.AtomicInteger()))));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(escalationService).processConfirmedOutage(eq("lvl.example.com"), eq(EscalationService.TYPE_SCRIPTED_FAIL), any(), ctx.capture());
        assertThat(ctx.getValue().get("alert_level")).isEqualTo("CRITICAL");   // processConfirmedOutage ctx seviyesini öncelikler
    }

    @Test
    @DisplayName("SCRIPTED_SLOW teyit anahtarı da DETAIL içermez — detail ölçülen süre, her sweep'te değişir")
    void scriptedSlowConfirmKeyIgnoresMeasuredDuration() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<String, Object>> neverReturns = () -> { calls.incrementAndGet(); return down("x"); };
        ReflectionTestUtils.setField(service, "confirmExecutor", new ScheduledThreadPoolExecutor(1));

        service.handleSweepResults(EscalationService.TYPE_SCRIPTED_SLOW, List.of(
                item(EscalationService.TYPE_SCRIPTED_SLOW, "Login Akisi", "8123 ms", false, Map.of(), neverReturns)));
        service.handleSweepResults(EscalationService.TYPE_SCRIPTED_SLOW, List.of(
                item(EscalationService.TYPE_SCRIPTED_SLOW, "Login Akisi", "9004 ms", false, Map.of(), neverReturns)));

        assertThat(service.activeConfirmations("Login Akisi")).hasSize(1);
    }

    @Test
    @DisplayName("SCRIPTED_SLOW: yeniden ölçüm YÜRÜTÜLEMEDİ ⇒ zincir iptal, yavaşlık alarmı AÇILMAZ")
    void scriptedSlowSkippedRecheckAbortsChain() {
        Supplier<Map<String, Object>> alwaysSkipped = MonitoringOutageServiceTest::skipped;

        service.handleSweepResults(EscalationService.TYPE_SCRIPTED_SLOW, List.of(
                item(EscalationService.TYPE_SCRIPTED_SLOW, "Odeme Akisi", "9000 ms", false,
                        Map.of(), alwaysSkipped)));

        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), anyMap());
        assertThat(service.activeConfirmations("Odeme Akisi")).isEmpty();
    }

    // ── Ağ-sınıfı ayrımı: yapılandırma hatası "ağ kesintisi" sayılmasın ─────────
    //
    // 2026-08 saha bulgusu: bastırma "düşük olan her monitörü" sayıyordu. Canlıda üç keyword
    // monitörünün üçü de AĞ YÜZÜNDEN DEĞİL düşüktü (ikisinin URL'inde boşluk vardı — iki aydır
    // her turda `Illegal character in path`; üçüncüsünü SsrfGuard bilerek reddediyordu) ve sezgi
    // her sweep'te tüm alarmları bastırıyordu. Yani bozuk yapılandırma, SAĞLAM monitörlerin
    // gerçek kesintisini süresiz maskeleyebiliyordu.

    /** Hata metnini serbest verebilen item — sınıflandırma testleri için (error null ⇒ up). */
    private static MonitoringOutageService.SweepItem itemErr(String type, String domain, String error,
                                                             Supplier<Map<String, Object>> recheck) {
        return new MonitoringOutageService.SweepItem(type, domain, "443", error == null, error, Map.of(), recheck);
    }

    @Test
    @DisplayName("Hata sınıflandırması: taşıma/DNS hataları kesinti kanıtı, yapılandırma hataları DEĞİL")
    void errorClassification() {
        // Ağ — canlı veritabanından birebir alınmış metinler
        assertThat(MonitoringOutageService.isOutageClass("Connect timed out", "a.com")).isTrue();
        assertThat(MonitoringOutageService.isOutageClass("Connection refused: getsockopt", "a.com")).isTrue();
        assertThat(MonitoringOutageService.isOutageClass("request timed out", "a.com")).isTrue();
        assertThat(MonitoringOutageService.isOutageClass("ConnectException", "a.com")).isTrue();
        assertThat(MonitoringOutageService.isOutageClass("Remote host terminated the handshake", "a.com")).isTrue();
        // DNS
        assertThat(MonitoringOutageService.isOutageClass("çözümlenemeyen host: www.x.com", "www.x.com")).isTrue();
        assertThat(MonitoringOutageService.isOutageClass("Ping request could not find host 1.2.3.4", "x")).isTrue();
        // UnknownHostException.getMessage() SADECE host adını döndürür — canlıda uptime_checks'te
        // bu biçimde 1000+ satır var. Bu dal olmadan gerçek DNS kesintilerinin çoğu elenirdi.
        assertThat(MonitoringOutageService.isOutageClass("www.example.com", "www.example.com")).isTrue();

        // Yapılandırma / politika — ASLA kesinti kanıtı değil
        assertThat(MonitoringOutageService.isOutageClass(
                "Illegal character in path at index 29: http://localhost:8080/health- duplicate", "localhost")).isFalse();
        assertThat(MonitoringOutageService.isOutageClass(
                "izin verilmeyen hedef localhost → 127.0.0.1 (loopback/any-local)", "localhost")).isFalse();
        // Sebep bilinmiyorsa kanıt da yok
        assertThat(MonitoringOutageService.isOutageClass(null, "a.com")).isFalse();
        assertThat(MonitoringOutageService.isOutageClass("  ", "a.com")).isFalse();
        // Tanınmayan hata ağ SAYILMAZ → bastırma olmaz → alarm çıkar (güvenli taraf)
        assertThat(MonitoringOutageService.isOutageClass("kelime bulunamadı", "a.com")).isFalse();
    }

    @Test
    @DisplayName("SAHA VAKASI: 3/3 düşük ama hepsi YAPILANDIRMA hatası → bastırma YOK, teyit başlar")
    void configErrorsDoNotSuppress() {
        AtomicInteger calls = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_KEYWORD, List.of(
                itemErr(EscalationService.TYPE_KEYWORD, "a.example.com",
                        "Illegal character in path at index 29: http://x/ y", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_KEYWORD, "b.example.com",
                        "Illegal character in path at index 29: http://x/ z", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_KEYWORD, "c.example.com",
                        "izin verilmeyen hedef localhost → 127.0.0.1 (loopback/any-local)", downThenUp(99, calls))));

        assertThat(calls.get())
                .as("bastırma kalkınca teyit zinciri koşmalı — eskiden hiç koşmuyordu")
                .isPositive();
        verify(networkOutageRepo, never()).save(any());   // ağ kesintisi olayı ÜRETİLMEZ
    }

    @Test
    @DisplayName("Yapılandırma hatası oranı şişiremez: 2 bozuk + 1 gerçek kesinti → bastırma YOK")
    void brokenConfigCannotMaskRealOutage() {
        AtomicInteger calls = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_KEYWORD, List.of(
                itemErr(EscalationService.TYPE_KEYWORD, "bozuk1.example.com",
                        "Illegal character in path at index 5: http://a b", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_KEYWORD, "bozuk2.example.com",
                        "izin verilmeyen hedef localhost → 127.0.0.1 (loopback/any-local)", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_KEYWORD, "gercek.example.com",
                        "Connect timed out", downThenUp(99, calls))));

        // 1 ağ-sınıfı DOWN < bulkMinErrors(3) → bastırma yok, GERÇEK kesinti teyide girer
        assertThat(calls.get()).isPositive();
    }

    @Test
    @DisplayName("Gerçek ağ kesintisinde bastırma SÜRER ve artık GÖRÜNÜR (olay kaydı üretilir)")
    void networkOutageIsSuppressedAndRecorded() {
        AtomicInteger calls = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                itemErr(EscalationService.TYPE_PORT_DOWN, "a.example.com", "Connect timed out", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_PORT_DOWN, "b.example.com", "Connect timed out", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_PORT_DOWN, "c.example.com", "Connection refused: getsockopt", downThenUp(99, calls))));

        assertThat(calls.get()).isZero();
        verifyNoInteractions(escalationService);
        ArgumentCaptor<com.sitemonitor.model.NetworkOutageEvent> ev =
                ArgumentCaptor.forClass(com.sitemonitor.model.NetworkOutageEvent.class);
        verify(networkOutageRepo).save(ev.capture());
        assertThat(ev.getValue().getStatus()).isEqualTo("ONGOING");
        assertThat(ev.getValue().getNetworkErrors()).isEqualTo(3);
        assertThat(ev.getValue().getTotalChecks()).isEqualTo(3);
        // Kaynak imzası ŞART: aynı tabloyu sertifika sweep'i de kullanıyor, ayrılmazsa karışır
        assertThat(ev.getValue().getSource()).isEqualTo(EscalationService.TYPE_PORT_DOWN);
    }

    @Test
    @DisplayName("Bastırma tekrarında olay ÇOĞALTILMAZ (log seli ve mükerrer kayıt biter)")
    void repeatedSuppressionRecordsOnce() {
        AtomicInteger calls = new AtomicInteger();
        List<MonitoringOutageService.SweepItem> allDown = List.of(
                itemErr(EscalationService.TYPE_PORT_DOWN, "a.example.com", "Connect timed out", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_PORT_DOWN, "b.example.com", "Connect timed out", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_PORT_DOWN, "c.example.com", "Connect timed out", downThenUp(99, calls)));

        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, allDown);
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, allDown);
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, allDown);

        verify(networkOutageRepo, times(1)).save(any());   // yalnız DURUMA GİRERKEN
    }

    @Test
    @DisplayName("Kesinti geçince açık olay RESOLVED'a çekilir (kaynağıyla eşleşen kayıt)")
    void suppressionResolvesWhenNetworkRecovers() {
        AtomicInteger calls = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                itemErr(EscalationService.TYPE_PORT_DOWN, "a.example.com", "Connect timed out", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_PORT_DOWN, "b.example.com", "Connect timed out", downThenUp(99, calls)),
                itemErr(EscalationService.TYPE_PORT_DOWN, "c.example.com", "Connect timed out", downThenUp(99, calls))));

        com.sitemonitor.model.NetworkOutageEvent open = new com.sitemonitor.model.NetworkOutageEvent();
        open.setDetectedAt("2026-08-16T00:00:00");
        open.setStatus("ONGOING");
        open.setSource(EscalationService.TYPE_PORT_DOWN);
        when(networkOutageRepo.findFirstBySourceAndStatusOrderByIdDesc(EscalationService.TYPE_PORT_DOWN, "ONGOING"))
                .thenReturn(java.util.Optional.of(open));

        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                itemErr(EscalationService.TYPE_PORT_DOWN, "a.example.com", null, MonitoringOutageServiceTest::up),
                itemErr(EscalationService.TYPE_PORT_DOWN, "b.example.com", null, MonitoringOutageServiceTest::up),
                itemErr(EscalationService.TYPE_PORT_DOWN, "c.example.com", null, MonitoringOutageServiceTest::up)));

        assertThat(open.getStatus()).isEqualTo("RESOLVED");
        assertThat(open.getResolvedAt()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Immediate (teyit=0) yolunda in-flight sızıntısı — 2026-08-20 bellek denetimi.
    //
    // startConfirmation'ın N-denemeli yolu (runConfirmAttempt) escalation istisnasını
    // catch'te yakalayıp anahtarı siliyordu; immediate yol ise remove'u try/finally'siz,
    // düz akışta çağırıyordu. withLock da yalnız KİLİT bırakmayı finally'ye alır,
    // action.run()'ı sarmaz → istisna dışarı çıkıp remove'u atlıyordu.
    //
    // İki ayrı sonucu var, ikisi de aşağıda pinlenir:
    //  1) putIfAbsent yeniden-giriş guard'ı olduğundan anahtar kalıcı ölür → o monitörün
    //     kesinti teyidi bir daha HİÇ başlamaz (restart'a kadar sessiz alarm körlüğü).
    //  2) startConfirmation sweep'in domain döngüsünden çağrıldığı için istisna o
    //     sweep'in KALAN domain'lerini de düşürür.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Immediate mod: escalation fırlatsa bile in-flight anahtarı TEMİZLENİR (yoksa monitör kalıcı sağır kalır)")
    void immediate_escalationThrows_inFlightCleared() {
        doThrow(new RuntimeException("escalation patladi"))
                .when(escalationService).processConfirmedOutage(anyString(), anyString(), anyString(), anyMap());

        MonitoringOutageService.SweepItem it = item(EscalationService.TYPE_PORT_DOWN,
                "immediate.example.com", "443/TCP", false,
                Map.of("monitor_confirm_attempts", 0), MonitoringOutageServiceTest::up);

        // runConfirmAttempt ile simetrik: istisna log'lanıp yutulur, çağıran akış devam eder.
        assertThatCode(() -> service.startConfirmation(it)).doesNotThrowAnyException();

        assertThat(service.activeConfirmations(null)).isEmpty();
    }

    @Test
    @DisplayName("Immediate mod: ilk domain'in escalation'ı patlarsa sweep'in KALAN domain'leri yine işlenir")
    void immediate_firstDomainThrows_sweepContinues() {
        doThrow(new RuntimeException("ilk domain patladi"))
                .when(escalationService).processConfirmedOutage(
                        eq("ilk.example.com"), anyString(), anyString(), anyMap());

        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                item(EscalationService.TYPE_PORT_DOWN, "ilk.example.com", "443/TCP", false,
                        Map.of("monitor_confirm_attempts", 0), MonitoringOutageServiceTest::up),
                item(EscalationService.TYPE_PORT_DOWN, "ikinci.example.com", "443/TCP", false,
                        Map.of("monitor_confirm_attempts", 0), MonitoringOutageServiceTest::up)));

        verify(escalationService).processConfirmedOutage(
                eq("ikinci.example.com"), eq(EscalationService.TYPE_PORT_DOWN), anyString(), anyMap());
        assertThat(service.activeConfirmations(null)).isEmpty();
    }

    // ── A4 kapisi: her alarm tipi DOGRU `*.alert-enabled` anahtarina bagli mi ────────────
    //
    // Domain dali switch'te ELLE dort tip sayiyordu, oysa EscalationService ALTI DOMAINMON_*
    // sabiti tanimliyor. TRANSFER_LOCK ve BLACKLIST `default` dalina, yani UPTIME anahtarina
    // dusuyordu: yonetici "alan adi alarmlarini sustur" dediginde bu iki tip YINE gonderiyor,
    // uptime'i kapattiginda ise domain alarmlari acikken SESSIZCE susuyorlardi. Hicbir test
    // bu eslemeye bakmiyordu.

    /** Alarm tipi → beklenen ayar anahtari. Katalogdaki HER tip burada karsiligini bulmali. */
    private static String expectedKeyFor(String type, String alertType) {
        // ACCESSIBILITY katalogda "http" altinda ama alarmi UPTIME suzgeci uretiyor
        // (SchedulerService sertifika envanteri uzerinden) — anahtari da uptime'dir.
        if ("ACCESSIBILITY".equals(alertType)) return "site.monitor.uptime.alert-enabled";
        return "site.monitor." + type + ".alert-enabled";
    }

    @Test
    @DisplayName("SOZLESME: katalogdaki her alarm tipi kendi turunun alert-enabled anahtarina bagli")
    void alertEnabled_everyCatalogAlertType_readsItsOwnSettingKey() {
        java.util.Map<String, String> wrong = new java.util.LinkedHashMap<>();

        MonitorTypeCatalog.ALERT_TYPES.forEach((type, alertTypes) -> {
            // cert alarmlari bu servisten GECMEZ (ayri sertifika hatti) — kapsam disi.
            if ("cert".equals(type)) return;
            for (String alertType : alertTypes) {
                String expected = expectedKeyFor(type, alertType);
                reset(appSettings);
                // Beklenen anahtar KAPALI, digerleri ACIK: dogru anahtar okunuyorsa false doner.
                when(appSettings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
                when(appSettings.getBoolean(eq(expected), anyBoolean())).thenReturn(false);

                if (service.alertEnabled(alertType)) {
                    wrong.put(alertType, "beklenen anahtar okunmadi: " + expected);
                }
            }
        });

        assertThat(wrong).as("yanlis ayar anahtarina bagli alarm tipleri").isEmpty();
    }

    @Test
    @DisplayName("A4: domain anahtari kapaliyken ALTI DOMAINMON tipinin hepsi susar")
    void alertEnabled_domainDisabled_silencesAllSixDomainTypes() {
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(appSettings.getBoolean(eq("site.monitor.domain.alert-enabled"), anyBoolean())).thenReturn(false);

        assertThat(MonitorTypeCatalog.ALERT_TYPES.get("domain"))
                .as("katalog alti domain tipi tanimlamali")
                .hasSize(6);

        for (String t : MonitorTypeCatalog.ALERT_TYPES.get("domain")) {
            assertThat(service.alertEnabled(t)).as("susmali: " + t).isFalse();
        }
    }

    @Test
    @DisplayName("A4: uptime anahtari kapaliyken domain alarmlari ETKILENMEZ")
    void alertEnabled_uptimeDisabled_doesNotSilenceDomainTypes() {
        // Ters yon: TRANSFER_LOCK/BLACKLIST `default` dalina dustugu icin uptime kapatilinca
        // domain alarmi acik olmasina ragmen susuyordu.
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(appSettings.getBoolean(eq("site.monitor.uptime.alert-enabled"), anyBoolean())).thenReturn(false);

        for (String t : MonitorTypeCatalog.ALERT_TYPES.get("domain")) {
            assertThat(service.alertEnabled(t)).as("acik kalmali: " + t).isTrue();
        }
    }

    // ── Kod incelemesi 2026-09-09 ─────────────────────────────────────────────

    @Test
    @DisplayName("Kilit alınırken GEÇİCİ DB hatası (bağlantı kopması) → aksiyon ATLANIR (fail-closed; çift alarm yerine bir tur kaçırılır)")
    void withLock_transientDbError_skipsAction() {
        when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), any(), any(), any()))
                .thenThrow(new RuntimeException("connection reset by peer"));
        when(alertEventRepo.findOpenByDomainIn(anyCollection()))
                .thenReturn(List.of(openAlert("recovered2.example.com", EscalationService.TYPE_ACCESSIBILITY)));

        service.handleSweepResults(EscalationService.TYPE_ACCESSIBILITY, List.of(
                item(EscalationService.TYPE_ACCESSIBILITY, "recovered2.example.com", "443", true,
                        Map.of(), MonitoringOutageServiceTest::up)));

        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    @Test
    @DisplayName("DNS_CHANGED ctx'i monitörün E-posta/Webhook bayraklarını taşır (diğer 8 kalemle parite)")
    void dnsChange_ctxCarriesChannelFlags() {
        service.handleDnsSweep(
                List.of(),
                List.of(),
                List.of(new MonitoringOutageService.DnsChange(
                        "flags.example.com", "A", "1.2.3.4", "9.9.9.9", "2026-06-11T10:00:00", 3L, null,
                        downThenUp(99, new AtomicInteger()), false, false)),
                List.of(),
                List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(escalationService).processConfirmedOutage(
                eq("flags.example.com"), eq(EscalationService.TYPE_DNS_CHANGED), eq("WARNING"), ctx.capture());
        assertThat(ctx.getValue().get("mail_disabled")).isEqualTo(true);
        assertThat(ctx.getValue().get("push_disabled")).isEqualTo(true);
        assertThat(ctx.getValue().get("team_id")).isEqualTo(3L);
    }

    // Regression: ISSUE-001 — silinen/duraklatılan hedef için teyit zinciri alarm açıyordu
    // Found by /qa on 2026-09-10 · Report: .gstack/qa-reports/qa-report-localhost-2026-09-10.md

    @Test
    @DisplayName("ISSUE-001: hedef artık izlenmiyorsa (silindi/duraklatıldı) teyit zinciri iptal, alarm AÇILMAZ, recheck koşmaz")
    void confirmChain_targetNoLongerMonitored_cancelsWithoutAlarm() {
        AtomicInteger calls = new AtomicInteger();
        service.setStillMonitored(item -> false);

        service.handleSweepResults(EscalationService.TYPE_ACCESSIBILITY, List.of(
                item(EscalationService.TYPE_ACCESSIBILITY, "deleted.example.com", "443", false,
                        Map.of("port", 443), downThenUp(99, calls))));

        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
        assertThat(calls.get()).as("silinen hedef için ağ re-check'i de harcanmaz").isZero();
        assertThat(service.activeConfirmations(null)).isEmpty();
    }

    @Test
    @DisplayName("ISSUE-001: kanca kayıtlı değilse (null) eski davranış — zincir tamamlanır ve alarm açılır")
    void confirmChain_noLivenessHook_behavesAsBefore() {
        AtomicInteger calls = new AtomicInteger();
        service.setStillMonitored(null);

        service.handleSweepResults(EscalationService.TYPE_ACCESSIBILITY, List.of(
                item(EscalationService.TYPE_ACCESSIBILITY, "live.example.com", "443", false,
                        Map.of("port", 443), downThenUp(99, calls))));

        verify(escalationService).processConfirmedOutage(
                eq("live.example.com"), eq(EscalationService.TYPE_ACCESSIBILITY), eq("WARNING"), any());
    }
    // ── O12 (2026-09-23): aktif recovery zincirinde KUŞAK yarışı ───────────────

    private static MonitoringOutageService.SweepItem upItem(String domain) {
        return new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_ACCESSIBILITY, domain, null, true, null, java.util.Map.of(),
                () -> java.util.Map.of("status", "up"));
    }

    @Test
    @DisplayName("O12: İPTAL EDİLMİŞ recovery zinciri alarmı kapatamaz — eski kuşak sessizce düşer")
    void cancelledRecoveryChainCannotResolveTheAlert() {
        ReflectionTestUtils.setField(service, "recoveryExecutor", immediateExecutor());
        String key = EscalationService.TYPE_ACCESSIBILITY + ":x.example.com";

        // 1. zincir başlar (kuşak 1), sonra sweep DOWN görüp iptal eder (kuşak 2'ye çıkar).
        long stale = (Long) ReflectionTestUtils.invokeMethod(service, "bumpRecoveryGeneration", key);
        ReflectionTestUtils.invokeMethod(service, "bumpRecoveryGeneration", key);

        // 1. zincirin kuyrukta bekleyen görevi ŞİMDİ ateşlenir. Eskiden guard yalnız
        // recoveryInFlight.contains(key) idi: sonraki sweep UP görüp anahtarı YENİDEN eklediğinde
        // bu eski görev canlanıyor ve kendi n sayacıyla alarmı kapatıyordu — arada gerçek bir DOWN
        // görülmüş olmasına rağmen.
        ReflectionTestUtils.invokeMethod(service, "runRecoveryAttempt",
                key, stale, EscalationService.TYPE_ACCESSIBILITY, "x.example.com",
                java.util.List.of(upItem("x.example.com")), 2, 1L, 5);

        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    @Test
    @DisplayName("O12: GEÇERLİ kuşak alarmı kapatır — düzeltme zinciri sağlam bırakmalı")
    void currentGenerationStillResolves() {
        ReflectionTestUtils.setField(service, "recoveryExecutor", immediateExecutor());
        String key = EscalationService.TYPE_ACCESSIBILITY + ":y.example.com";
        long gen = (Long) ReflectionTestUtils.invokeMethod(service, "bumpRecoveryGeneration", key);

        ReflectionTestUtils.invokeMethod(service, "runRecoveryAttempt",
                key, gen, EscalationService.TYPE_ACCESSIBILITY, "y.example.com",
                java.util.List.of(upItem("y.example.com")), 2, 1L, 1);

        verify(escalationService).resolveMonitoringAlertsForDomain("y.example.com", EscalationService.TYPE_ACCESSIBILITY);
    }
}
