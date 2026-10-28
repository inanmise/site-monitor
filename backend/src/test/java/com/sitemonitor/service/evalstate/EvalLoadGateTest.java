package com.sitemonitor.service.evalstate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Çok pod Sürüm 3-2 B — {@link EvalLoadGate} (tasarım R3-2 §3.2 / §3.3): hızlı yol, iç içe derinlik, LOADING bekleyişi,
 * zaman aşımı geçişi (rapor tek kez), uçuştaki değerlendirmeyi bekleyen uygulama, araya sızan değerlendirmenin sayımı,
 * HANDOVER beklemesi ≤ 1 sn, bekleyen sayısı / en uzun bekleme. Her test zaman sınırlı (bekleme takılırsa düşer).
 */
class EvalLoadGateTest {

    static final long T0 = 1_800_000_000_000L;

    final AtomicLong clock = new AtomicLong(T0);
    final List<EvalLoadGate.Ended> expired = new CopyOnWriteArrayList<>();
    final EvalLoadGate gate = new EvalLoadGate(clock::get, expired::add);
    final List<Thread> started = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (Thread t : started) {
            t.interrupt();
            t.join(2_000);
        }
    }

    /** Ayrı iş parçacığında kapıdan girer, {@code hold} açılana dek içeride kalır. */
    Thread enterOn(CountDownLatch entered, CountDownLatch hold) {
        Thread t = new Thread(() -> {
            try (EvalLoadGate.Ticket ignored = gate.enter()) {
                entered.countDown();
                if (hold != null) hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "gate-test");
        t.setDaemon(true);
        started.add(t);
        t.start();
        return t;
    }

    static void awaitTrue(java.util.function.BooleanSupplier c) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("koşul 5 sn içinde sağlanmadı");
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("NONE: giriş sayılır ve çıkışta düşer — kilit / bekleme yok")
    void noneFastPath() {
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);
        try (EvalLoadGate.Ticket t = gate.enter()) {
            assertThat(gate.inFlight()).isEqualTo(1);
        }
        assertThat(gate.inFlight()).isZero();
        assertThat(gate.loadingToken()).isZero();
        assertThat(expired).isEmpty();
    }

    @Test
    @DisplayName("iç içe giriş (handleDnsSweep → handleSweepResults) LOADING'de beklemez, yeniden sayılmaz")
    void nestedEnterNeitherWaitsNorRecounts() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (EvalLoadGate.Ticket outer = gate.enter()) {
                gate.arm(1L, T0 + 20_000);
                try (EvalLoadGate.Ticket inner = gate.enter()) {
                    assertThat(gate.inFlight()).isEqualTo(1);
                    try (EvalLoadGate.Ticket inner2 = gate.enter()) {
                        assertThat(gate.inFlight()).isEqualTo(1);
                    }
                }
                assertThat(gate.inFlight()).isEqualTo(1);
            }
            assertThat(gate.inFlight()).isZero();
        });
    }

    @Test
    @DisplayName("LOADING: giriş yükleme bitene dek bekler; bekleyen sayısı ve en uzun bekleme bitişte ölçülür")
    void loadingEntryWaitsUntilSkip() throws Exception {
        gate.arm(7L, T0 + 20_000);
        assertThat(gate.loadingToken()).isEqualTo(7L);
        CountDownLatch entered = new CountDownLatch(1);
        enterOn(entered, null);
        awaitTrue(() -> gate.waiters() == 1);
        assertThat(entered.await(200, TimeUnit.MILLISECONDS)).as("kapı LOADING — giriş bekler").isFalse();

        clock.addAndGet(700);
        EvalLoadGate.Ended e = gate.skip(7L);
        assertThat(e).isNotNull();
        assertThat(e.token()).isEqualTo(7L);
        assertThat(e.reason()).isNull();
        assertThat(e.waiters()).isEqualTo(1);
        assertThat(e.maxWaitMs()).isEqualTo(700L);
        assertThat(entered.await(2, TimeUnit.SECONDS)).as("kapı açıldı — giriş geçer").isTrue();
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);
        assertThat(gate.skip(7L)).as("aynı belirteç ikinci kez bitmez").isNull();
        assertThat(expired).isEmpty();
    }

    @Test
    @DisplayName("LOADING son tarihi geçince bekleyen girer, kapı NONE olur, zaman aşımı TEK kez bildirilir; geç uygulama reddedilir")
    void timeoutReleasesWaitersOnce() throws Exception {
        gate.arm(3L, T0 + 20_000);
        CountDownLatch a = new CountDownLatch(1);
        CountDownLatch b = new CountDownLatch(1);
        enterOn(a, null);
        enterOn(b, null);
        awaitTrue(() -> gate.waiters() == 2);
        clock.addAndGet(20_001);
        assertThat(a.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(b.await(2, TimeUnit.SECONDS)).isTrue();
        awaitTrue(() -> !expired.isEmpty());
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).reason()).isEqualTo(EvalLoadReport.REASON_TIMEOUT);
        assertThat(expired.get(0).waiters()).isEqualTo(2);
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);

        EvalLoadGate.ApplyOutcome late = gate.apply(3L, () -> true, () -> null);
        assertThat(late.stale()).as("zaman aşımı zaten bildirildi").isTrue();
        assertThat(late.applied()).isFalse();
        assertThat(late.reason()).isEqualTo(EvalLoadReport.REASON_TIMEOUT);
        assertThat(expired).hasSize(1);
    }

    @Test
    @DisplayName("belirteç okuması son tarihte yüklemeyi bitirir (kimse girmese de) — bildirim izleyici DIŞINDA, bir kez")
    void loadingTokenExpiresAtDeadline() {
        gate.arm(4L, T0 + 20_000);
        clock.addAndGet(19_999);
        assertThat(gate.loadingToken()).isEqualTo(4L);
        clock.addAndGet(1);
        assertThat(gate.loadingToken()).isZero();
        gate.expireIfDue();
        assertThat(expired).extracting(EvalLoadGate.Ended::token).containsExactly(4L);
        assertThat(Thread.holdsLock(gate)).isFalse();
    }

    @Test
    @DisplayName("enjekte saat takılsa da gerçek zamanlı üst sınır yüklemeyi bitirir (sonsuz bekleme yok)")
    void hardCapEndsLoadingWithStuckClock() throws Exception {
        gate.arm(5L, T0 + 50);   // saat ilerlemiyor; gerçek üst sınır = 50 + 1000 ms
        Thread.sleep(1_200);
        assertThat(gate.loadingToken()).isZero();
        assertThat(expired).extracting(EvalLoadGate.Ended::reason).containsExactly(EvalLoadReport.REASON_TIMEOUT);
    }

    @Test
    @DisplayName("uygulama gövdesi izleyici altında ve sıfır uçuşla koşar; LOADING'de bekleyen giriş sayılmaz, uygulamadan sonra geçer")
    void applyBodyRunsUnderMonitorWithZeroInFlight() throws Exception {
        gate.arm(8L, T0 + 20_000);
        CountDownLatch entered = new CountDownLatch(1);
        enterOn(entered, null);                       // kurulumdan SONRA geldi — bekler, sayılmaz
        awaitTrue(() -> gate.waiters() == 1);
        AtomicLong inFlightAtBody = new AtomicLong(-1);
        AtomicBoolean heldLock = new AtomicBoolean();
        EvalLoadGate.ApplyOutcome out = gate.apply(8L, () -> true, () -> {
            inFlightAtBody.set(gate.inFlight());
            heldLock.set(Thread.holdsLock(gate));
            return null;
        });
        assertThat(out.applied()).isTrue();
        assertThat(out.stale()).isFalse();
        assertThat(out.waiters()).isEqualTo(1);
        assertThat(inFlightAtBody.get()).isZero();
        assertThat(heldLock).isTrue();
        assertThat(entered.await(2, TimeUnit.SECONDS)).as("uygulamadan sonra bekleyen geçer").isTrue();
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);
    }

    @Test
    @DisplayName("uygulama araya sızan (kurulumdan önce giren) değerlendirmeyi bekler, sonra yükü atar (evaluated_before_load) — gövde koşmaz")
    void applyWaitsForInFlightSlipInThenDiscards() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        enterOn(entered, hold);                       // kapı NONE iken girdi (kurulumdan önce — araya sızma)
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        gate.arm(9L, T0 + 20_000);
        AtomicReference<EvalLoadGate.ApplyOutcome> out = new AtomicReference<>();
        AtomicBoolean bodyRan = new AtomicBoolean();
        Thread applier = new Thread(() -> out.set(gate.apply(9L, () -> true, () -> {
            bodyRan.set(true);
            return null;
        })));
        applier.setDaemon(true);
        started.add(applier);
        applier.start();
        Thread.sleep(150);
        assertThat(out.get()).as("araya sızan değerlendirme sürüyor — uygulama bekler").isNull();
        hold.countDown();
        applier.join(3_000);
        assertThat(bodyRan).isFalse();
        assertThat(out.get().applied()).isFalse();
        assertThat(out.get().stale()).isFalse();
        assertThat(out.get().reason()).isEqualTo(EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD);
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);
    }

    @Test
    @DisplayName("L-3: araya sızan değerlendirme belleğe hiçbir şey yazmadan çıksa da yük atılır (bellek boşluğu yetmez)")
    void slipInThatLeftNoTraceStillDiscards() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        Thread t = enterOn(entered, hold);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        gate.arm(19L, T0 + 20_000);
        hold.countDown();                              // değerlendirme yalnız SİLDİ / hiçbir şey yazmadı ve çıktı
        t.join(2_000);
        assertThat(gate.inFlight()).isZero();
        AtomicBoolean bodyRan = new AtomicBoolean();
        EvalLoadGate.ApplyOutcome out = gate.apply(19L, () -> true, () -> {
            bodyRan.set(true);
            return null;                               // gövde boş belleği kabul ederdi
        });
        assertThat(out.applied()).isFalse();
        assertThat(out.reason()).isEqualTo(EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD);
        assertThat(bodyRan).isFalse();
        // Sonraki kurulumda uçuşta değerlendirme yok → uygulanır.
        gate.arm(20L, T0 + 20_000);
        assertThat(gate.apply(20L, () -> true, () -> null).applied()).isTrue();
    }

    @Test
    @DisplayName("C-1: uygulama uçuştakini beklerken son tarih gelirse timeout (evaluation_busy değil) — gövde koşmaz")
    void applyWaitEndedByDeadlineIsTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        enterOn(entered, hold);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        gate.arm(10L, T0 + 20_000);
        AtomicReference<EvalLoadGate.ApplyOutcome> out = new AtomicReference<>();
        AtomicBoolean bodyRan = new AtomicBoolean();
        Thread applier = new Thread(() -> out.set(gate.apply(10L, () -> true, () -> {
            bodyRan.set(true);
            return null;
        })));
        applier.setDaemon(true);
        started.add(applier);
        applier.start();
        awaitTrue(() -> applier.getState() == Thread.State.TIMED_WAITING);   // uygulama uçuştakini bekliyor
        clock.addAndGet(20_000);   // son tarih uygulama beklerken geldi
        applier.join(3_000);
        assertThat(out.get().reason()).isEqualTo(EvalLoadReport.REASON_TIMEOUT);
        assertThat(out.get().applied()).isFalse();
        assertThat(out.get().stale()).isFalse();
        assertThat(bodyRan).isFalse();
        assertThat(expired).as("bitiş uygulamanın sonucunda — ayrıca bildirilmez").isEmpty();
        hold.countDown();
    }

    @Test
    @DisplayName("C-1: uçuştaki değerlendirme durulma sınırında (son tarih değil) bitmezse evaluation_busy — gövde koşmaz")
    void applyEvaluationBusyAtQuiesceCap() throws Exception {
        EvalLoadGate g = new EvalLoadGate(clock::get, expired::add, null, 150L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try (EvalLoadGate.Ticket ignored = g.enter()) {
                entered.countDown();
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.setDaemon(true);
        started.add(t);
        t.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        g.arm(11L, T0 + 20_000);
        AtomicBoolean bodyRan = new AtomicBoolean();
        EvalLoadGate.ApplyOutcome out = g.apply(11L, () -> true, () -> {
            bodyRan.set(true);
            return null;
        });
        assertThat(out.reason()).isEqualTo(EvalLoadReport.REASON_EVALUATION_BUSY);
        assertThat(out.applied()).isFalse();
        assertThat(bodyRan).isFalse();
        assertThat(g.state()).isEqualTo(EvalLoadGate.State.NONE);
        hold.countDown();
    }

    @Test
    @DisplayName("L-1: kurulumdan sonra (gövdeden önce ya da gövde sürerken) liderlik olayı başladıysa leadership_changed — uygulandı sayılmaz")
    void leadershipChangeDuringApplyIsNotApplied() {
        AtomicLong seq = new AtomicLong();
        EvalLoadGate g = new EvalLoadGate(clock::get, expired::add, seq::get);
        g.arm(1L, T0 + 20_000);
        seq.incrementAndGet();                         // olay başladı (dinleyici henüz sökmedi)
        AtomicBoolean bodyRan = new AtomicBoolean();
        EvalLoadGate.ApplyOutcome before = g.apply(1L, () -> true, () -> {
            bodyRan.set(true);
            return null;
        });
        assertThat(before.applied()).isFalse();
        assertThat(before.stale()).isFalse();
        assertThat(before.reason()).isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
        assertThat(bodyRan).as("gövde koşmaz").isFalse();
        assertThat(g.state()).isEqualTo(EvalLoadGate.State.NONE);

        g.arm(2L, T0 + 20_000);
        EvalLoadGate.ApplyOutcome during = g.apply(2L, () -> true, () -> {
            seq.incrementAndGet();                     // olay gövde sürerken başladı
            return null;
        });
        assertThat(during.applied()).isFalse();
        assertThat(during.reason()).isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);

        g.arm(3L, T0 + 20_000);
        assertThat(g.apply(3L, () -> true, () -> null).applied()).as("sıra değişmedi → uygulanır").isTrue();
        EvalLoadGate broken = new EvalLoadGate(clock::get, null, () -> { throw new IllegalStateException("x"); });
        broken.arm(4L, T0 + 20_000);
        assertThat(broken.apply(4L, () -> true, () -> null).reason()).as("sıra okunamadı = değişti (güvenli taraf)")
                .isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
    }

    @Test
    @DisplayName("uygulama nedenleri: eski belirteç (liderlik değişti), son tarih, ayar kapandı, ayar okunamadı, gövde fırlattı, gövde reddetti")
    void applyDiscardReasons() {
        gate.arm(1L, T0 + 20_000);
        EvalLoadGate.Ended prev = gate.arm(2L, T0 + 20_000);
        assertThat(prev.token()).isEqualTo(1L);
        assertThat(prev.reason()).isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
        EvalLoadGate.ApplyOutcome stale = gate.apply(1L, () -> true, () -> null);
        assertThat(stale.stale()).isTrue();
        assertThat(stale.reason()).isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
        assertThat(gate.state()).as("eski belirteç güncel yüklemeyi bitirmez").isEqualTo(EvalLoadGate.State.LOADING);

        clock.addAndGet(20_000);
        EvalLoadGate.ApplyOutcome late = gate.apply(2L, () -> true, () -> null);
        assertThat(late.stale()).isFalse();
        assertThat(late.reason()).isEqualTo(EvalLoadReport.REASON_TIMEOUT);
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);

        gate.arm(3L, clock.get() + 20_000);
        assertThat(gate.apply(3L, () -> false, () -> null).reason()).isEqualTo(EvalLoadReport.REASON_SETTING_TURNED_OFF);
        gate.arm(4L, clock.get() + 20_000);
        assertThat(gate.apply(4L, () -> { throw new IllegalStateException("x"); }, () -> null).reason())
                .as("ayar okunamadı = kapalı").isEqualTo(EvalLoadReport.REASON_SETTING_TURNED_OFF);
        gate.arm(5L, clock.get() + 20_000);
        assertThat(gate.apply(5L, () -> true, () -> { throw new IllegalStateException("x"); }).reason())
                .isEqualTo(EvalLoadReport.REASON_READ_FAILED);
        gate.arm(6L, clock.get() + 20_000);
        EvalLoadGate.ApplyOutcome rejected = gate.apply(6L, () -> true, () -> EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD);
        assertThat(rejected.applied()).isFalse();
        assertThat(rejected.stale()).isFalse();
        assertThat(rejected.reason()).isEqualTo(EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD);
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);
        assertThat(gate.apply(0L, () -> true, () -> null).stale()).isTrue();
        assertThat(gate.skip(0L)).isNull();
    }

    @Test
    @DisplayName("sökme LOADING'i leadership_changed ile bitirir ve bekleyenleri bırakır; uygulama beklerken sökülürse eski sayılır")
    void disarmEndsLoadingAndWakesWaiters() throws Exception {
        assertThat(gate.disarm()).as("LOADING değilken sökme bir şey bitirmez").isNull();
        gate.arm(11L, T0 + 20_000);
        CountDownLatch entered = new CountDownLatch(1);
        enterOn(entered, null);
        awaitTrue(() -> gate.waiters() == 1);
        EvalLoadGate.Ended e = gate.disarm();
        assertThat(e.token()).isEqualTo(11L);
        assertThat(e.reason()).isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        CountDownLatch entered2 = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        enterOn(entered2, hold);
        assertThat(entered2.await(2, TimeUnit.SECONDS)).isTrue();
        gate.arm(12L, T0 + 20_000);
        AtomicReference<EvalLoadGate.ApplyOutcome> out = new AtomicReference<>();
        Thread applier = new Thread(() -> out.set(gate.apply(12L, () -> true, () -> null)));
        applier.setDaemon(true);
        started.add(applier);
        applier.start();
        Thread.sleep(100);
        gate.disarm();
        applier.join(3_000);
        assertThat(out.get().stale()).isTrue();
        assertThat(out.get().reason()).isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
        hold.countDown();
    }

    @Test
    @DisplayName("kaydedici L-1: HANDOVER giriş beklemesi devir bütçesinden (durulma + boşaltma) en az 1 sn uzun")
    void handoverWaitOutlastsHandoverBudget() {
        assertThat(EvalLoadGate.HANDOVER_WAIT_MS)
                .isGreaterThanOrEqualTo(EvalStateSink.HANDOVER_BUDGET_MS + 1_000L);
        assertThat(EvalLoadGate.HARD_CAP_SLACK_MS).as("LOADING üst sınır payı ayrı (1 sn)").isEqualTo(1_000L);
    }

    @Test
    @DisplayName("HANDOVER: yeni giriş en çok HANDOVER_WAIT_MS bekler (gerçek zaman) ya da devir bitince hemen geçer; LOADING'de devir başlamaz")
    void handoverWaitIsBounded() throws Exception {
        gate.beginHandover();
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.HANDOVER);
        long start = System.nanoTime();
        CountDownLatch entered = new CountDownLatch(1);
        enterOn(entered, null);
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(waitedMs).isBetween(EvalLoadGate.HANDOVER_WAIT_MS - 100, EvalLoadGate.HANDOVER_WAIT_MS + 1_000);
        assertThat(gate.state()).as("geç kalan giriş devri bitirmez").isEqualTo(EvalLoadGate.State.HANDOVER);

        gate.endHandover();
        gate.endHandover();   // tekrar çağrılabilir
        gate.beginHandover();
        CountDownLatch entered2 = new CountDownLatch(1);
        enterOn(entered2, null);
        Thread.sleep(100);
        gate.endHandover();
        assertThat(entered2.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);

        gate.arm(20L, T0 + 20_000);
        gate.beginHandover();
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.LOADING);
        gate.disarm();
        gate.beginHandover();
        assertThat(gate.disarm()).isNull();
        assertThat(gate.state()).as("sökme devri de bitirir").isEqualTo(EvalLoadGate.State.NONE);
    }

    @Test
    @DisplayName("durulma beklemesi: uçuşta değerlendirme yoksa true; sürüyorsa süre dolunca false; bitince true")
    void awaitQuiescent() throws Exception {
        assertThat(gate.awaitQuiescent(10)).isTrue();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        enterOn(entered, hold);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        gate.beginHandover();
        long start = System.nanoTime();
        assertThat(gate.awaitQuiescent(120)).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1_000);
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            hold.countDown();
        }).start();
        assertThat(gate.awaitQuiescent(2_000)).isTrue();
        gate.endHandover();
    }

    @Test
    @DisplayName("M-1 yol 2: sürmekte olan zincir adımı durulmayı engeller; bitince durulur — tek bütçe, girişlerle birlikte")
    void chainStepBlocksQuiescence() throws Exception {
        EvalLoadGate.Ticket step = gate.enterChainStep();
        assertThat(step).as("NONE'da adım sayılır, bilet döner").isNotNull();
        assertThat(gate.chainStepsInFlight()).isEqualTo(1);
        assertThat(gate.inFlight()).as("giriş sayacı ayrı").isZero();
        gate.beginHandover();
        long start = System.nanoTime();
        assertThat(gate.awaitQuiescent(120)).as("yeniden kontrol sürüyor — işaret yazılmamalı").isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1_000);
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            step.close();
        });
        closer.setDaemon(true);
        started.add(closer);
        closer.start();
        assertThat(gate.awaitQuiescent(2_000)).isTrue();
        assertThat(gate.chainStepsInFlight()).isZero();

        // giriş VE adım birlikte: ikisi de bitmeden durulmaz
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        gate.endHandover();
        enterOn(entered, hold);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        EvalLoadGate.Ticket step2 = gate.enterChainStep();
        gate.beginHandover();
        hold.countDown();
        awaitTrue(() -> gate.inFlight() == 0);
        assertThat(gate.awaitQuiescent(100)).as("giriş bitti ama adım sürüyor").isFalse();
        step2.close();
        assertThat(gate.awaitQuiescent(100)).isTrue();
        gate.endHandover();
    }

    @Test
    @DisplayName("M-1 yol 2: HANDOVER'da başlayan zincir adımı sayılmaz ve null döner (belleğe dokunmadan çıkar); LOADING / NONE'da sayılır")
    void chainStepRefusedDuringHandover() {
        gate.beginHandover();
        assertThat(gate.enterChainStep()).isNull();
        assertThat(gate.chainStepsInFlight()).isZero();
        assertThat(gate.awaitQuiescent(10)).isTrue();
        gate.endHandover();
        EvalLoadGate.Ticket t = gate.enterChainStep();
        assertThat(t).isNotNull();
        t.close();
        gate.arm(1L, T0 + 20_000);
        EvalLoadGate.Ticket loading = gate.enterChainStep();
        assertThat(loading).as("LOADING'de eski dönemin adımı kendi denetimiyle düşer; kapı reddetmez").isNotNull();
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.LOADING);
        loading.close();
        assertThat(gate.chainStepsInFlight()).isZero();
        gate.disarm();
        gate.beginHandover();
        assertThat(gate.enterChainStep()).isNull();
        gate.disarm();
        assertThat(gate.enterChainStep()).as("RELEASED'in sökmesi devri bitirir").isNotNull();
    }

    @Test
    @DisplayName("L-2: zaman aşımı bildirimi Error fırlatsa da giriş sayımı ve iş parçacığı derinliği geri alınır; sonraki giriş LOADING'de bekler")
    void expiredNotificationErrorDoesNotLeak() throws Exception {
        EvalLoadGate g = new EvalLoadGate(clock::get, e -> { throw new AssertionError("bildirim çöktü (test)"); });
        g.arm(1L, T0 + 20_000);
        Thread advancer = new Thread(() -> {
            awaitTrue(() -> g.waiters() == 1);
            clock.addAndGet(20_001);
        });
        advancer.setDaemon(true);
        started.add(advancer);
        advancer.start();
        // Bu (test) iş parçacığında girilir — derinlik iş parçacığı yereldir; beklemeler kapının üst sınırıyla sınırlı.
        AssertionError err = null;
        try {
            g.enter();
        } catch (AssertionError e) {
            err = e;
        }
        assertThat(err).as("Error yutulmaz").isNotNull();
        advancer.join(2_000);
        assertThat(g.inFlight()).as("sayım geri alındı").isZero();
        assertThat(g.state()).isEqualTo(EvalLoadGate.State.NONE);

        // Aynı iş parçacığı: derinlik sıfır → yeni LOADING'de BEKLER (iç içe sayılıp geçmez).
        g.arm(2L, clock.get() + 20_000);
        AtomicReference<EvalLoadGate.Ended> skipped = new AtomicReference<>();
        Thread skipper = new Thread(() -> {
            awaitTrue(() -> g.waiters() == 1);
            skipped.set(g.skip(2L));
        });
        skipper.setDaemon(true);
        started.add(skipper);
        skipper.start();
        try (EvalLoadGate.Ticket t = g.enter()) {
            assertThat(g.inFlight()).isEqualTo(1);
        }
        skipper.join(2_000);
        assertThat(skipped.get()).as("giriş gerçekten bekledi (iç içe sayılıp geçmedi)").isNotNull();
        assertThat(skipped.get().waiters()).isEqualTo(1);
        assertThat(g.inFlight()).isZero();
    }

    @Test
    @DisplayName("bekleyen giriş kesilirse (interrupt) sayılarak geçer, bayrak korunur — uygulama onu bekler")
    void interruptedWaiterProceedsCounted() throws Exception {
        gate.arm(30L, T0 + 20_000);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean flag = new AtomicBoolean();
        Thread t = new Thread(() -> {
            try (EvalLoadGate.Ticket ignored = gate.enter()) {
                flag.set(Thread.interrupted());   // bayrak geri konmuş olmalı (okuyup temizler — sonraki bekleme sürsün)
                entered.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        t.setDaemon(true);
        started.add(t);
        t.start();
        awaitTrue(() -> gate.waiters() == 1);
        t.interrupt();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(flag).isTrue();
        assertThat(gate.inFlight()).isEqualTo(1);
        assertThat(gate.state()).as("kesme yüklemeyi bitirmez").isEqualTo(EvalLoadGate.State.LOADING);
        hold.countDown();
        t.join(2_000);
        assertThat(gate.inFlight()).isZero();
    }

    @Test
    @DisplayName("uygulama beklerken kesilirse recorder_closing (bayrak korunur); durulma beklemesi kesilirse false döner")
    void interruptedApplyAndQuiescence() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        enterOn(entered, hold);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        gate.arm(40L, T0 + 20_000);
        AtomicReference<EvalLoadGate.ApplyOutcome> out = new AtomicReference<>();
        AtomicBoolean applierFlag = new AtomicBoolean();
        Thread applier = new Thread(() -> {
            out.set(gate.apply(40L, () -> true, () -> null));
            applierFlag.set(Thread.currentThread().isInterrupted());
        });
        applier.setDaemon(true);
        started.add(applier);
        applier.start();
        Thread.sleep(100);
        applier.interrupt();
        applier.join(3_000);
        assertThat(out.get().reason()).isEqualTo(EvalLoadReport.REASON_RECORDER_CLOSING);
        assertThat(out.get().stale()).isFalse();
        assertThat(applierFlag).isTrue();
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);

        AtomicReference<Boolean> quiet = new AtomicReference<>();
        AtomicBoolean waiterFlag = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            quiet.set(gate.awaitQuiescent(5_000));
            waiterFlag.set(Thread.currentThread().isInterrupted());
        });
        waiter.setDaemon(true);
        started.add(waiter);
        waiter.start();
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(3_000);
        assertThat(quiet.get()).as("değerlendirme hâlâ uçuşta").isFalse();
        assertThat(waiterFlag).isTrue();
        hold.countDown();
    }

    @Test
    @DisplayName("zaman aşımı bildirimi istisnası yutulur; boş bilet hiçbir şey yapmaz")
    void listenerExceptionSwallowedAndNoTicket() {
        EvalLoadGate g = new EvalLoadGate(clock::get, e -> { throw new IllegalStateException("x"); });
        g.arm(1L, T0 + 10);
        clock.addAndGet(10);
        assertThat(g.loadingToken()).isZero();
        EvalLoadGate.NO_TICKET.close();
        assertThat(g.inFlight()).isZero();
        EvalLoadGate noClock = new EvalLoadGate(null, null);
        noClock.arm(2L, System.currentTimeMillis() + 60_000);
        assertThat(noClock.loadingToken()).isEqualTo(2L);
        assertThat(noClock.currentToken()).isEqualTo(2L);
    }
}
