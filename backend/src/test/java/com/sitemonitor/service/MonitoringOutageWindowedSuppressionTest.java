package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Ağ kesintisi bastırması KAYAN PENCEREDEN (SOAK-1, 2026-10-22) ve paylaşılan olayda en yüksek seviye (ALERTS-11).
 *
 * <p>Gerçek iki pod'lu dayanıklılık testinde bir tur, aynı anda oluşturulmuş 9 port izlemesini taşıyordu ve 7'si hatalıydı
 * (0,78): toplu bastırma bunu ağ kesintisi sandı, 5 GERÇEK port kesintisi ~5 dk alarm üretmedi; öbür tur her 30 sn'de
 * şüpheyi kaldırıp {@code network_outage_events}'e her dakika açılış / kapanış satırı yazdırdı. Oran artık türün son
 * {@code max(3 × tur aralığı, 5 dk)} içindeki alan adı başına son sonucundan; giriş ≥ min hata + oran ≥ eşik, çıkış oran
 * eşiğin altına inince (histerezis).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringOutageWindowedSuppressionTest {

    @Mock AlertEventRepository alertEventRepo;
    @Mock EscalationService escalationService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock DnsMonitorRepository dnsMonitorRepo;
    @Mock AppSettingsService appSettings;
    @Mock com.sitemonitor.repository.NetworkOutageEventRepository networkOutageRepo;

    private MonitoringOutageService service;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final List<Runnable> pending = new ArrayList<>();

    /** Teyit denemelerini BEKLETEN yürütücü — test istediği anda koşturur (zincir bastırma açıldıktan sonra biter). */
    private ScheduledThreadPoolExecutor deferredExecutor() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                pending.add(command);
                return null;
            }
        };
    }

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
        service = new MonitoringOutageService(alertEventRepo, escalationService, jdbcTemplate, dnsRecordRepo, dnsMonitorRepo,
                appSettings, networkOutageRepo);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        ReflectionTestUtils.setField(service, "portAlertEnabled", true);
        ReflectionTestUtils.setField(service, "uptimeAlertEnabled", true);
        ReflectionTestUtils.setField(service, "confirmAttempts", 3);
        ReflectionTestUtils.setField(service, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(service, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(service, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(service, "confirmExecutor", immediateExecutor());
        ReflectionTestUtils.setField(service, "suppressionClock", (java.util.function.LongSupplier) clock::get);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
    }

    private void advance(long ms) { clock.addAndGet(ms); }

    private static Map<String, Object> downResult() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "down");
        m.put("error", "Connect timed out");
        return m;
    }

    private static MonitoringOutageService.SweepItem port(String host, boolean up, AtomicInteger rechecks) {
        Supplier<Map<String, Object>> recheck = () -> { rechecks.incrementAndGet(); return downResult(); };
        return new MonitoringOutageService.SweepItem(EscalationService.TYPE_PORT_DOWN, host, "443/TCP", up,
                up ? null : "Connect timed out", Map.of(), recheck);
    }

    /** {@code n} alan adlı tur, ilk {@code failing}'i ağ hatasıyla düşük. */
    private static List<MonitoringOutageService.SweepItem> tick(String prefix, int n, int failing, AtomicInteger rechecks) {
        List<MonitoringOutageService.SweepItem> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(port(prefix + i + ".example.com", i >= failing, rechecks));
        return out;
    }

    @Test
    @DisplayName("SAHA (SOAK-1): 21 sağlıklı izleme penceredeyken 7/9 hatalı tur → bastırma YOK, gerçek kesintiler teyide girer; tekrar eden turlar satır üretmez")
    void smallFailingTick_amongHealthy_doesNotSuppress() {
        AtomicInteger rechecks = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("healthy", 21, 0, rechecks));
        for (int round = 0; round < 4; round++) {
            advance(30_000);
            service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("burst", 9, 7, rechecks));
            advance(30_000);
            service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("healthy", 21, 0, rechecks));
        }

        assertThat(service.outageSuppressed(EscalationService.TYPE_PORT_DOWN)).isFalse();
        verify(networkOutageRepo, never()).save(any());
        assertThat(rechecks.get()).as("gerçek kesintiler teyit zincirine girdi").isPositive();
        verify(escalationService, atLeastOnce()).processConfirmedOutage(eq("burst0.example.com"),
                eq(EscalationService.TYPE_PORT_DOWN), anyString(), any());
    }

    @Test
    @DisplayName("Sürekli kesinti iki ayrı turda (her ikisi de hatalı) → TEK ONGOING kayıt, tur tur açılış / kapanış YOK")
    void sustainedOutageAcrossTicks_oneRowNoFlap() {
        AtomicInteger rechecks = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("x", 5, 5, rechecks));
        for (int round = 0; round < 5; round++) {
            advance(30_000);
            service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("y", 6, 6, rechecks));
            advance(30_000);
            service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("x", 5, 5, rechecks));
        }

        assertThat(service.outageSuppressed(EscalationService.TYPE_PORT_DOWN)).isTrue();
        // yalnız duruma girerken: tur tur kapanıp yeniden açılsaydı her girişte yeni ONGOING satırı yazılırdı
        verify(networkOutageRepo, times(1)).save(any());
        assertThat(rechecks.get()).isZero();
        verifyNoInteractions(escalationService);
    }

    @Test
    @DisplayName("Histerezis: şüphe açıkken hata sayısı asgarinin altına inse de oran eşikteyse SÜRER; oran düşünce kalkar")
    void hysteresis_staysUntilRateDrops() {
        AtomicInteger rechecks = new AtomicInteger();
        // a, b, c ağ hatası + d sağlıklı → 3/4 → giriş
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                port("a.example.com", false, rechecks), port("b.example.com", false, rechecks),
                port("c.example.com", false, rechecks), port("d.example.com", true, rechecks)));
        assertThat(service.outageSuppressed(EscalationService.TYPE_PORT_DOWN)).isTrue();

        // c düzeldi → 2/4 = 0,50: girişin asgari sayısı (3) tutmaz ama oran eşikte → şüphe SÜRER (her turda kapanıp açılmaz)
        advance(30_000);
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(port("c.example.com", true, rechecks)));
        assertThat(service.outageSuppressed(EscalationService.TYPE_PORT_DOWN)).isTrue();

        // b de düzeldi → 1/4 → şüphe kalkar
        advance(30_000);
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(port("b.example.com", true, rechecks)));
        assertThat(service.outageSuppressed(EscalationService.TYPE_PORT_DOWN)).isFalse();
        verify(networkOutageRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("Pencere dışı sonuçlar budanır: 5 dk'dan eski sağlıklı sonuçlar oranı seyreltmez")
    void staleOutcomesArePruned() {
        AtomicInteger rechecks = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("old", 20, 0, rechecks));
        for (int i = 0; i < 12; i++) {   // 30 sn arayla 6 dk: eski 20 sağlıklı sonuç pencereden düşer
            advance(30_000);
            service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("live", 4, i >= 10 ? 4 : 0, rechecks));
        }
        assertThat(service.outageSuppressed(EscalationService.TYPE_PORT_DOWN)).isTrue();
    }

    @Test
    @DisplayName("Teyit zinciri şüpheden ÖNCE başladıysa ve bittiğinde tür için ağ kesintisi şüphesi açıksa alarm AÇILMAZ")
    void confirmationFinishingDuringSuppression_doesNotOpen() {
        ReflectionTestUtils.setField(service, "confirmExecutor", deferredExecutor());
        AtomicInteger rechecks = new AtomicInteger();
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("b", 12, 0, rechecks));
        advance(30_000);
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("a", 9, 9, rechecks));   // 9/21 → şüphe yok, zincir başlar
        assertThat(pending).isNotEmpty();
        advance(30_000);
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, tick("b", 12, 12, rechecks)); // 21/21 → şüphe açılır
        assertThat(service.outageSuppressed(EscalationService.TYPE_PORT_DOWN)).isTrue();

        for (int guard = 0; guard < 100 && !pending.isEmpty(); guard++) pending.remove(0).run();

        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
    }

    // ── ALERTS-11 ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ALERTS-11: açık paylaşılan olayda iki sahibin kalemi düşük (443 WARNING + 8443 CRITICAL) → bağlam EN YÜKSEK seviyeyle")
    void sharedOpenEvent_usesHighestLevelItem() {
        AlertEvent open = new AlertEvent();
        open.setId(9L); open.setDomain("host.example.com"); open.setAlertType(EscalationService.TYPE_PORT_DOWN);
        open.setAlertLevel("WARNING"); open.setResolved(false); open.setAcknowledged(false);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));
        Supplier<Map<String, Object>> recheck = MonitoringOutageWindowedSuppressionTest::downResult;
        service.handleSweepResults(EscalationService.TYPE_PORT_DOWN, List.of(
                new MonitoringOutageService.SweepItem(EscalationService.TYPE_PORT_DOWN, "host.example.com", "443/TCP", false,
                        "kapalı", Map.of("port", 443, "alert_level", "WARNING"), recheck),
                new MonitoringOutageService.SweepItem(EscalationService.TYPE_PORT_DOWN, "host.example.com", "8443/TCP", false,
                        "kapalı", Map.of("port", 8443, "alert_level", "CRITICAL"), recheck)));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map> ctx = ArgumentCaptor.forClass(Map.class);
        verify(escalationService).processConfirmedOutage(eq("host.example.com"), eq(EscalationService.TYPE_PORT_DOWN), anyString(),
                ctx.capture());
        assertThat(ctx.getValue()).containsEntry("alert_level", "CRITICAL").containsEntry("port", 8443);
    }

    @Test
    @DisplayName("ALERTS-11: highestLevelItem eşitlikte ilk kalemi seçer; seviyesiz kalem en düşük sayılır")
    void highestLevelItem_rules() {
        Supplier<Map<String, Object>> r = MonitoringOutageWindowedSuppressionTest::downResult;
        var a = new MonitoringOutageService.SweepItem("PORT_DOWN", "h", "1", false, "x", Map.of("alert_level", "HIGH"), r);
        var b = new MonitoringOutageService.SweepItem("PORT_DOWN", "h", "2", false, "x", Map.of("alert_level", "HIGH"), r);
        var c = new MonitoringOutageService.SweepItem("PORT_DOWN", "h", "3", false, "x", Map.of(), r);
        assertThat(MonitoringOutageService.highestLevelItem(List.of(c, a, b))).isSameAs(a);
        assertThat(MonitoringOutageService.highestLevelItem(List.of(c))).isSameAs(c);
    }
}
