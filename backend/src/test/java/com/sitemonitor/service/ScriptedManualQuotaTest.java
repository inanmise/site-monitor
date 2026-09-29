package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ELLE k6 KOTASI (2026-09-29, D-10) + sıra bütçesi (O-b3).
 *
 * <p>Elle ve zamanlanmış sentetik koşumlar aynı k6 havuzunu (varsayılan 2 izin) paylaşıyordu; toplu "Şimdi Kontrol Et"
 * iki izni de koşum boyunca tutabiliyor, zamanlanmış koşumlar izin bekleyip SKIPPED'e düşüyor ve o pencerede gerçek bir
 * kesintinin alarmı gecikiyordu. Kural: elle koşum (ve elle kontrolün başlattığı kurtarma zinciri) havuzun SON iznini
 * almaz; zamanlanmış koşumlara her zaman en az bir izin kalır. Elle koşumun sıra beklemesi tetik ucunun beklemesinden
 * KISA — kota doluysa "yürütülmedi" yanıtı uca yetişir.
 *
 * <p><b>Zamanlamaya dayanmaz</b> (D-b19): k6 alt süreci taklit edilir ({@code runProcess}); "başladı" bir mandalla
 * bildirilir (yoklama / uyku YOK), koşum test mandalı bırakana dek "sürer". Ayar taklidi 0 döndüğü için elle sıra
 * bütçesi 0 sn'dir: kota/havuz doluysa sonuç HEMEN döner — süre iddiası yok. Mandal beklemelerindeki üst sınır yalnız
 * "hiç gelmedi" durumunda testi asılı bırakmamak içindir.
 */
class ScriptedManualQuotaTest {

    /** Yalnız "sinyal hiç gelmedi" üst sınırı — bir süre iddiası DEĞİL. */
    private static final long SIGNAL_SEC = 30;

    /** k6 sürecini taklit eden servis: başlayan script'i mandalla bildirir, test mandalı bırakınca PASS döner. */
    static final class FakeK6 extends ScriptedCheckerService {
        final Map<String, CountDownLatch> startedLatch = new ConcurrentHashMap<>();
        final Set<String> started = ConcurrentHashMap.newKeySet();
        final CountDownLatch release = new CountDownLatch(1);

        FakeK6(AppSettingsService settings) {
            super(mock(SsrfGuard.class), mock(SecretCipher.class), settings, new SimpleMeterRegistry(), mock(ProxySettings.class));
        }

        CountDownLatch startOf(String script) {
            return startedLatch.computeIfAbsent(script, k -> new CountDownLatch(1));
        }

        @Override
        ScriptedResult runProcess(String script, List<EnvVar> env, int timeoutSec, ProxyUse viaProxy) {
            started.add(script);
            startOf(script).countDown();
            try { release.await(SIGNAL_SEC, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new ScriptedResult("PASS", true, 10L, 0, 1, 0, null, null, null, null, null, null, false, Phases.EMPTY);
        }
    }

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private FakeK6 svc;

    @AfterEach
    void tearDown() {
        if (svc != null) svc.release.countDown();
        pool.shutdownNow();
    }

    private FakeK6 service(int poolSize) {
        FakeK6 s = new FakeK6(settings);
        ReflectionTestUtils.setField(s, "permits", new Semaphore(poolSize));
        ReflectionTestUtils.setField(s, "manualSlots", new Semaphore(Math.max(1, poolSize - 1)));
        ReflectionTestUtils.setField(s, "poolSize", poolSize);
        ReflectionTestUtils.setField(s, "k6Available", true);
        ReflectionTestUtils.setField(s, "skipped", new SimpleMeterRegistry().counter("scripted.k6.skipped"));
        return s;
    }

    private static ScriptedMonitor mon(long id, String script) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(id); m.setName("Senaryo " + id); m.setScript(script); m.setTimeoutSeconds(5);
        return m;
    }

    private static void awaitStarted(FakeK6 s, String script) throws InterruptedException {
        assertThat(s.startOf(script).await(SIGNAL_SEC, TimeUnit.SECONDS)).as(script + " başlamalıydı").isTrue();
    }

    /**
     * {@code field} semaforunda bekleyen bir iş parçacığı belirirse k6 mandalını bırakır. "Elle koşum sıraya girdi mi?"
     * sorusunu ZAMANLAMASIZ ayırt eder: doğru davranışta sıraya kimse girmez, sonuç hemen "yürütülmedi" döner (yardımcı
     * iptal edilir); sıraya girseydi yardımcı havuzu boşaltır, elle koşum izni alıp KOŞARDI → iddia kızarır.
     */
    private Future<?> releaseIfSomeoneQueuesOn(String field) {
        Semaphore s = (Semaphore) ReflectionTestUtils.getField(svc, field);
        return pool.submit(() -> {
            while (!s.hasQueuedThreads()) {
                if (Thread.currentThread().isInterrupted()) return;
                Thread.onSpinWait();
            }
            svc.release.countDown();
        });
    }

    private static void assertBusy(ScriptedCheckerService.ScriptedResult r) {
        assertThat(ScriptedCheckerService.isSkipped(r)).isTrue();
        assertThat(r.error()).isEqualTo(ScriptedCheckerService.MANUAL_POOL_BUSY);
    }

    private static void assertPoolRestored(FakeK6 s, int poolSize) {
        assertThat(((Semaphore) ReflectionTestUtils.getField(s, "permits")).availablePermits()).isEqualTo(poolSize);
        assertThat(((Semaphore) ReflectionTestUtils.getField(s, "manualSlots")).availablePermits())
                .isEqualTo(Math.max(1, poolSize - 1));
        assertThat(s.queuedChecks()).as("sıra sayacı sızmaz").isZero();
        assertThat(s.activeProcesses()).isZero();
    }

    @Test
    @DisplayName("Toplu elle koşum sürerken ZAMANLANMIŞ koşum izin alır ve başlar — SKIPPED olmaz; ikinci elle koşum son izni ALMAZ")
    void bulkManual_leavesPermitForScheduled() throws Exception {
        svc = service(2);
        Future<ScriptedCheckerService.ScriptedResult> m1 = pool.submit(() -> svc.runManual(mon(1, "ELLE-1")));
        awaitStarted(svc, "ELLE-1");

        // Toplu kuyruğun ikinci elle koşumu: kota (pool-1 = 1) dolu → son izni almaz, "yürütülmedi" HEMEN döner.
        assertBusy(svc.runManual(mon(2, "ELLE-2")));
        assertThat(svc.started).doesNotContain("ELLE-2");

        Future<ScriptedCheckerService.ScriptedResult> sched = pool.submit(() -> svc.run(mon(3, "ZAMANLANMIS")));
        awaitStarted(svc, "ZAMANLANMIS");

        svc.release.countDown();
        assertThat(sched.get(SIGNAL_SEC, TimeUnit.SECONDS).status()).isEqualTo("PASS");
        assertThat(m1.get(SIGNAL_SEC, TimeUnit.SECONDS).status()).isEqualTo("PASS");
        assertPoolRestored(svc, 2);
    }

    @Test
    @DisplayName("Elle kontrolün başlattığı kurtarma zinciri (withManualQuota) ELLE kotadan koşar — kota doluyken BEKLEMEDEN atlanır")
    void manualOriginChain_countsAgainstManualQuota() throws Exception {
        svc = service(2);
        Future<?> m1 = pool.submit(() -> svc.runManual(mon(1, "ELLE-1")));
        awaitStarted(svc, "ELLE-1");
        // Elle sıra bütçesi SIFIRDAN büyük olsa bile zincir kota için BEKLEMEZ (2 iş parçacıklı kurtarma yürütücüsü tıkanmaz).
        when(settings.getInt(ScriptedCheckerService.MANUAL_WAIT_KEY, ScriptedCheckerService.MANUAL_WAIT_DEFAULT_SEC)).thenReturn(25);
        Future<?> releaser = releaseIfSomeoneQueuesOn("manualSlots");

        ScriptedCheckerService.ScriptedResult chain =
                ScriptedCheckerService.withManualQuota(() -> svc.run(mon(4, "ZINCIR")));
        releaser.cancel(true);

        assertBusy(chain);
        assertThat(svc.started).as("elle kökenli zincir son izni almaz").doesNotContain("ZINCIR");
        assertThat(ScriptedCheckerService.manualQuotaActive()).as("ThreadLocal geri döner").isFalse();
        svc.release.countDown();
        m1.get(SIGNAL_SEC, TimeUnit.SECONDS);
        assertPoolRestored(svc, 2);
    }

    @Test
    @DisplayName("Zamanlanmış koşumlar havuzu doldurmuşken elle koşum kotası boş olsa da sıra BEKLEMEZ → 'yürütülmedi' uca yetişir")
    void scheduledFillPool_manualReportsBusyInsteadOfQueueing() throws Exception {
        svc = service(2);
        Future<?> s1 = pool.submit(() -> svc.run(mon(5, "ZAM-1")));
        Future<?> s2 = pool.submit(() -> svc.run(mon(6, "ZAM-2")));
        awaitStarted(svc, "ZAM-1");
        awaitStarted(svc, "ZAM-2");
        Future<?> releaser = releaseIfSomeoneQueuesOn("permits");

        ScriptedCheckerService.ScriptedResult r = svc.runManual(mon(1, "ELLE-1"));
        releaser.cancel(true);

        assertBusy(r);
        assertThat(svc.started).doesNotContain("ELLE-1");
        svc.release.countDown();
        s1.get(SIGNAL_SEC, TimeUnit.SECONDS);
        s2.get(SIGNAL_SEC, TimeUnit.SECONDS);
        assertPoolRestored(svc, 2);
    }

    @Test
    @DisplayName("Tek izinli havuz: zamanlanmış koşum sürerken elle koşum 'havuz meşgul' SKIPPED döner (kuyrukta geçmez)")
    void singlePermitPool_manualRefusedWhileScheduledRuns() throws Exception {
        svc = service(1);
        Future<?> sched = pool.submit(() -> svc.run(mon(3, "ZAMANLANMIS")));
        awaitStarted(svc, "ZAMANLANMIS");
        Future<?> releaser = releaseIfSomeoneQueuesOn("permits");

        ScriptedCheckerService.ScriptedResult r = svc.runManual(mon(1, "ELLE-1"));
        releaser.cancel(true);

        assertBusy(r);
        svc.release.countDown();
        sched.get(SIGNAL_SEC, TimeUnit.SECONDS);
        assertPoolRestored(svc, 1);
    }

    @Test
    @DisplayName("O-b3: elle sıra bütçesi tetik ucunun beklemesinden HER ZAMAN kısa (≥ 5 sn pay) ve koşum payını aşmaz")
    void queueBudget_isShorterThanEndpointWait() {
        svc = service(2);
        for (int endpointWait : new int[]{1, 3, 5, 6, 25, 60, 200}) {
            when(settings.getInt(ScriptedCheckerService.MANUAL_WAIT_KEY, ScriptedCheckerService.MANUAL_WAIT_DEFAULT_SEC))
                    .thenReturn(endpointWait);
            for (int timeout : new int[]{5, 60, 180}) {
                int budget = svc.manualQueueWaitSeconds(timeout);
                assertThat(budget).as("uç=%d timeout=%d", endpointWait, timeout)
                        .isGreaterThanOrEqualTo(0)
                        .isLessThanOrEqualTo(Math.max(0, endpointWait - ScriptedCheckerService.MANUAL_REPORT_MARGIN_SEC))
                        .isLessThanOrEqualTo(timeout + 30);
            }
        }
        when(settings.getInt(ScriptedCheckerService.MANUAL_WAIT_KEY, ScriptedCheckerService.MANUAL_WAIT_DEFAULT_SEC)).thenReturn(25);
        assertThat(svc.manualQueueWaitSeconds(60)).as("varsayılan: 25 sn uç → 20 sn sıra").isEqualTo(20);
    }
}
