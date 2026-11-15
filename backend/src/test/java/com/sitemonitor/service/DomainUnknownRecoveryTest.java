package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A6 (2026-11-15, canlı şikâyet): wingscard.com.tr'nin DOMAINMON_UNKNOWN alarmı veri 4 gün üst üste geldiği hâlde açık kaldı.
 * İzlemenin kurtarma aralığı NULL (eski kayıt) → pasif kurtarma: "Recovery sürüyor … 1/3"; sayaç pod belleğinde, alan adı turu
 * günlük, her yeniden başlatma sayacı sıfırlar → eşik hiç dolmaz. Düzeltme: DOMAINMON_* için varsayılan AKTİF aralık
 * ({@code site.monitor.domain.recovery-interval-ms}, 60 sn); 0 = bugünkü pasif davranış.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DomainUnknownRecoveryTest {

    @Mock AlertEventRepository alertEventRepo;
    @Mock EscalationService escalationService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock DnsMonitorRepository dnsMonitorRepo;
    @Mock AppSettingsService appSettings;
    @Mock com.sitemonitor.repository.NetworkOutageEventRepository networkOutageRepo;

    private static final String DOMAIN = "wingscard.com.tr";
    private static final String TYPE = EscalationService.TYPE_DOMAINMON_UNKNOWN;

    /** schedule() anında koşan, gecikmeleri kaydeden yürütücü — aktif zincir senkron tamamlanır. */
    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {
        final List<Long> delaysMs = new ArrayList<>();
        RecordingExecutor() { super(1); }
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delaysMs.add(unit.toMillis(delay));
            command.run();
            return null;
        }
    }

    /** Bir pod: kendi belleği olan yeni bir MOS örneği (yeniden başlatma = yeni örnek). */
    private MonitoringOutageService pod(Integer settingMs, RecordingExecutor rec) {
        MonitoringOutageService s = new MonitoringOutageService(alertEventRepo, escalationService, jdbcTemplate,
                dnsRecordRepo, dnsMonitorRepo, appSettings, networkOutageRepo);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        if (settingMs != null) when(appSettings.getInt(eq(MonitoringOutageService.DOMAIN_RECOVERY_INTERVAL_KEY), anyInt())).thenReturn(settingMs);
        ReflectionTestUtils.setField(s, "confirmAttempts", 3);
        ReflectionTestUtils.setField(s, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(s, "recoveryExecutor", rec);
        ReflectionTestUtils.setField(s, "confirmExecutor", rec);
        return s;
    }

    private void alarmOpen() {
        AlertEvent e = new AlertEvent();
        e.setId(77L);
        e.setDomain(DOMAIN);
        e.setAlertType(TYPE);
        e.setResolved(false);
        e.setContextJson("{\"monitor_id\":5}");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
    }

    /** SchedulerService.domainItem'in UNKNOWN kalemi — izlemenin kurtarma ARALIĞI YOK (eski kayıt), 3 ardışık sağlıklı gerekli. */
    private MonitoringOutageService.SweepItem healthyUnknownItem(AtomicInteger rechecks) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_id", 5L);
        ctx.put("monitor_recovery_checks", 3);
        ctx.put("monitor_confirm_attempts", 3);
        return new MonitoringOutageService.SweepItem(TYPE, DOMAIN, DOMAIN, true, null, ctx, () -> {
            rechecks.incrementAndGet();
            Map<String, Object> r = new HashMap<>();
            r.put("status", "up");
            return r;
        });
    }

    @Test
    @DisplayName("0 = bugünkü PASİF davranış: 4 günlük sağlıklı tur, her biri yeniden başlatılmış podda → alarm hiç kapanmaz (1/3 sonsuza dek)")
    void passive_settingZero_neverCloses_acrossRestarts() {
        alarmOpen();
        AtomicInteger rechecks = new AtomicInteger();
        for (int day = 1; day <= 4; day++) {
            RecordingExecutor rec = new RecordingExecutor();
            MonitoringOutageService pod = pod(0, rec);   // her gün yeniden başlatılmış pod (sayaç bellekte)
            pod.handleSweepResults(TYPE, List.of(healthyUnknownItem(rechecks)));
            assertThat(rec.delaysMs).as("pasif kurtarmada aktif zincir yok").isEmpty();
        }
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        assertThat(rechecks.get()).isZero();
    }

    @Test
    @DisplayName("Varsayılan AKTİF aralık (60 sn): ilk sağlıklı zamanlanmış turdan sonra 2 yeniden kontrolle (≈ 2 × 60 sn) kapanır")
    void active_default_closesWithinRequiredMinusOneIntervals() {
        alarmOpen();
        AtomicInteger rechecks = new AtomicInteger();
        RecordingExecutor rec = new RecordingExecutor();
        MonitoringOutageService pod = pod(null, rec);   // ayar yok → varsayılan 60000
        pod.handleSweepResults(TYPE, List.of(healthyUnknownItem(rechecks)));

        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(DOMAIN, TYPE);
        assertThat(rechecks.get()).as("tetikleyici tur = 1, 2 aktif yeniden kontrol = 3 ardışık sağlıklı").isEqualTo(2);
        assertThat(rec.delaysMs).containsExactly(60_000L, 60_000L);
    }

    @Test
    @DisplayName("Ayar sınırlara kırpılır: 5 sn → 10 sn, 2 sa → 1 sa; izlemenin KENDİ aralığı varsa o kullanılır")
    void clampAndMonitorOverride() {
        assertThat(MonitoringOutageService.domainRecoveryIntervalMs(0)).isNull();
        assertThat(MonitoringOutageService.domainRecoveryIntervalMs(-1)).isNull();
        assertThat(MonitoringOutageService.domainRecoveryIntervalMs(5_000)).isEqualTo(10_000L);
        assertThat(MonitoringOutageService.domainRecoveryIntervalMs(7_200_000)).isEqualTo(3_600_000L);
        assertThat(MonitoringOutageService.domainRecoveryIntervalMs(60_000)).isEqualTo(60_000L);

        alarmOpen();
        AtomicInteger rechecks = new AtomicInteger();
        RecordingExecutor rec = new RecordingExecutor();
        MonitoringOutageService pod = pod(0, rec);   // genel ayar pasif — ama izlemenin kendi aralığı (30 sn) kazanır
        MonitoringOutageService.SweepItem base = healthyUnknownItem(rechecks);
        Map<String, Object> ctx = new LinkedHashMap<>(base.ctxExtra());
        ctx.put("monitor_recovery_interval_ms", 30_000L);
        pod.handleSweepResults(TYPE, List.of(new MonitoringOutageService.SweepItem(TYPE, DOMAIN, DOMAIN, true, null, ctx, base.recheck())));
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(DOMAIN, TYPE);
        assertThat(rec.delaysMs).containsExactly(30_000L, 30_000L);
    }
}
