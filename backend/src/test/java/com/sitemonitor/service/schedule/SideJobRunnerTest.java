package com.sitemonitor.service.schedule;

import com.sitemonitor.config.ShutdownDeadline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3-1 3e (2026-10-23): pod başına yan iş koşucusu — iş başına birleştirme, ertelenen deneme ve ön denetimi,
 * {@code ContextClosedEvent} sonrası kabul yok, {@code stopAndWait} ortak kapanış süresiyle bekler ve sonra keser, durum
 * satırı veritabanı okumaz.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SideJobRunnerTest {

    final List<SideJobRunner> runners = new ArrayList<>();

    SideJobRunner runner(int threads) {
        SideJobRunner r = new SideJobRunner(threads, System::currentTimeMillis);
        runners.add(r);
        return r;
    }

    @AfterEach
    void stop() {
        ShutdownDeadline.activate(null);
        runners.forEach(SideJobRunner::stopAndWait);
    }

    static SideJobRunner.Status.Job job(SideJobRunner r, String name) {
        return r.status().jobs().stream().filter(j -> j.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Gönderilen iş koşucu iş parçacığında koşar ('side-job-N'), durum satırı sayar")
    void submitRunsOnSideThread() throws Exception {
        SideJobRunner r = runner(2);
        AtomicReference<String> thread = new AtomicReference<>();
        assertThat(r.submit("cert-hourly", () -> thread.set(Thread.currentThread().getName()))).isTrue();
        assertThat(r.awaitIdle(5_000)).isTrue();
        assertThat(thread.get()).startsWith("side-job-");
        SideJobRunner.Status.Job j = job(r, "cert-hourly");
        assertThat(j.runs()).isEqualTo(1);
        assertThat(j.lastOutcome()).isEqualTo(SideJobRunner.OUTCOME_OK);
        assertThat(j.state()).isEqualTo(SideJobRunner.STATE_IDLE);
        assertThat(r.status().threads()).isEqualTo(2);
        assertThat(r.status().accepting()).isTrue();
    }

    @Test
    @DisplayName("Birleştirme: aynı adlı iş koşarken ikinci gönderim atlanır (sayılır); başka adlı iş paralel koşar")
    void coalescedPerName() throws Exception {
        SideJobRunner r = runner(3);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Runnable slow = () -> {
            runs.incrementAndGet();
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        assertThat(r.submit("nightly-cleanup", slow)).isTrue();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(job(r, "nightly-cleanup").state()).isEqualTo(SideJobRunner.STATE_RUNNING);
        assertThat(r.submit("nightly-cleanup", slow)).as("ikinci tetik birleştirildi").isFalse();
        AtomicBoolean other = new AtomicBoolean();
        assertThat(r.submit("cert-stale", () -> other.set(true))).isTrue();
        release.countDown();
        assertThat(r.awaitIdle(5_000)).isTrue();
        assertThat(runs).hasValue(1);
        assertThat(other).isTrue();
        assertThat(job(r, "nightly-cleanup").skippedCount()).isEqualTo(1);
        assertThat(r.submit("nightly-cleanup", () -> runs.incrementAndGet())).as("bitince yeniden kabul").isTrue();
        assertThat(r.awaitIdle(5_000)).isTrue();
        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("Ertelenen deneme: gecikmeyle koşar; ön denetim 'yapıldı' derse gövde koşmaz (atlandı), okunamazsa gövde koşar")
    void deferredAttempt() throws Exception {
        SideJobRunner r = runner(2);
        AtomicInteger body = new AtomicInteger();
        assertThat(r.defer("weekly-availability", 50, () -> false, body::incrementAndGet)).isTrue();
        assertThat(job(r, "weekly-availability").state()).isEqualTo(SideJobRunner.STATE_QUEUED);
        assertThat(job(r, "weekly-availability").deferredCount()).isEqualTo(1);
        long deadline = System.currentTimeMillis() + 5_000;
        while (body.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertThat(body).as("gerçek gecikmeyle koştu").hasValue(1);
        assertThat(r.awaitIdle(5_000)).isTrue();

        assertThat(r.defer("weekly-availability", 60_000, () -> true, body::incrementAndGet)).isTrue();
        assertThat(r.runDeferredNow()).isEqualTo(1);
        assertThat(body).as("ön denetim: başka pod yaptı").hasValue(1);
        assertThat(job(r, "weekly-availability").lastOutcome()).isEqualTo(SideJobRunner.OUTCOME_SKIPPED);
        assertThat(job(r, "weekly-availability").skippedCount()).isEqualTo(1);

        assertThat(r.defer("weekly-availability", 60_000, () -> { throw new IllegalStateException("db"); },
                body::incrementAndGet)).isTrue();
        assertThat(r.runDeferredNow()).isEqualTo(1);
        assertThat(body).as("ön denetim okunamadı → gövde (kilit / tur karar verir)").hasValue(2);
        assertThat(job(r, "weekly-availability").deferredCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Başarısız gövde yutulur (koşucu yaşar), sonuç 'failed'")
    void failureIsRecorded() {
        SideJobRunner r = runner(1);
        assertThat(r.submit("x", () -> { throw new IllegalStateException("boom"); })).isTrue();
        assertThat(r.awaitIdle(5_000)).isTrue();
        assertThat(job(r, "x").lastOutcome()).isEqualTo(SideJobRunner.OUTCOME_FAILED);
        AtomicBoolean ran = new AtomicBoolean();
        assertThat(r.submit("x", () -> ran.set(true))).isTrue();
        assertThat(r.awaitIdle(5_000)).isTrue();
        assertThat(ran).isTrue();
    }

    @Test
    @DisplayName("ContextClosedEvent sonrası: yeni iş kabul edilmez, kuyrukta / ertelenmiş bekleyen başlamaz")
    void closedAfterContextClosed() {
        SideJobRunner r = runner(2);
        AtomicInteger body = new AtomicInteger();
        assertThat(r.defer("cert-hourly", 60_000, null, body::incrementAndGet)).isTrue();
        r.onContextClosed(null);
        assertThat(r.accepting()).isFalse();
        assertThat(r.canOffload()).isFalse();
        assertThat(r.submit("cert-stale", body::incrementAndGet)).isFalse();
        assertThat(r.defer("nightly-cleanup", 0, null, body::incrementAndGet)).isFalse();
        assertThat(r.runDeferredNow()).isEqualTo(1);
        assertThat(body).as("kapanıştan sonra hiçbir gövde başlamadı").hasValue(0);
        assertThat(r.status().accepting()).isFalse();
    }

    @Test
    @DisplayName("Satır içi kip (threads=0): kuyruk / erteleme yok — çağıran gövdeyi kendisi koşturur")
    void inlineMode() {
        SideJobRunner r = SideJobRunner.inline();
        runners.add(r);
        assertThat(r.isInline()).isTrue();
        assertThat(r.canOffload()).isFalse();
        assertThat(r.submit("x", () -> { })).isFalse();
        assertThat(r.defer("x", 0, null, () -> { })).isFalse();
        assertThat(r.status().threads()).isZero();
        assertThat(new SideJobRunner().isInline()).as("P0 iskeletiyle uyumlu argümansız kurucu").isTrue();
    }

    @Test
    @DisplayName("stopAndWait: ertelenmiş denemeleri iptal eder, koşanı ortak kapanış süresi (ShutdownDeadline) kadar bekler, sonra keser")
    void stopAndWaitUsesSharedDeadlineThenInterrupts() throws Exception {
        SideJobRunner r = runner(2);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger deferredBody = new AtomicInteger();
        assertThat(r.submit("tls-profile", () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        })).isTrue();
        assertThat(r.defer("nightly-cleanup", 60_000, null, deferredBody::incrementAndGet)).isTrue();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        ShutdownDeadline d = new ShutdownDeadline();
        d.remainingMs(0, System.nanoTime() - (ShutdownDeadline.DESTROY_BUDGET_MS - 200) * 1_000_000L);   // bütçenin 200 ms'si kaldı
        ShutdownDeadline.activate(d);
        long t0 = System.nanoTime();
        r.stopAndWait();
        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        assertThat(tookMs).as("tavan 5 sn değil, ortak süreden kalan ~200 ms beklendi").isLessThan(2_000L);
        long deadline = System.currentTimeMillis() + 5_000;
        while (!interrupted.get() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertThat(interrupted).as("süre dolunca kesildi (interrupt)").isTrue();
        assertThat(deferredBody).as("ertelenmiş deneme iptal").hasValue(0);
        assertThat(r.accepting()).isFalse();
        r.stopAndWait();                                         // idempotent
    }

    @Test
    @DisplayName("Durum satırı: yerleşim ve liderlik ipucu bağlı; veritabanı okumaz (canlı pod gözlemi önbellekten)")
    void statusCarriesPlacement() {
        SideJobRunner r = runner(1);
        PodLiveness never = new PodLiveness(null, "me", System::currentTimeMillis);   // jdbc yok: okunsa NPE → bilinmiyor
        r.setPlacement(new SideJobPlacement(() -> true, never, 20_000));
        r.bindLeaderHint(() -> true);
        r.noteYielded("cert-stale");
        SideJobRunner.Status s = r.status();
        assertThat(s.leaderYieldEnabled()).isTrue();
        assertThat(s.leaderHint()).isTrue();
        assertThat(s.otherLivePod()).as("hiç okunmadı → bilinmiyor").isNull();
        assertThat(job(r, "cert-stale").yieldedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("KAPI: koşucunun beklemesi ortak yok etme süresini okur")
    void sourceUsesSharedDeadline() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/sitemonitor/service/schedule/SideJobRunner.java"),
                StandardCharsets.UTF_8);
        assertThat(src).contains("ShutdownDeadline.waitMs(SHUTDOWN_WAIT_CAP_MS)").contains(".awaitTermination(waitMs");
        assertThat(SideJobRunner.SHUTDOWN_WAIT_CAP_MS).isLessThan(ShutdownDeadline.DESTROY_BUDGET_MS);
    }

    // ── FX-SIDE #1 (inceleme 2026-10-23): tek pod zamanlaması ve sıraya girme ───────────────────────────────────────

    @Test
    @DisplayName("FX-SIDE #1: 3 uzun iş koşucunun BÜTÜN uzun iş parçacıklarını tutarken liderin ertelediği KISA cron denemesi "
            + "zamanında başlar (kısa cron şeridi) — uzun işlerin arkasında beklemez")
    void deferredShortCronAttemptStartsOnTimeWhileThreeLongJobsRun() throws Exception {
        SideJobRunner r = runner(3);
        CountDownLatch longStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        Runnable longJob = () -> {
            longStarted.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            assertThat(r.submit("nightly-cleanup", longJob)).isTrue();
            assertThat(r.submit("cert-hourly", longJob)).isTrue();
            assertThat(r.submit("tls-profile", longJob)).isTrue();
            assertThat(longStarted.await(5, TimeUnit.SECONDS)).as("3 uzun iş 3 iş parçacığını tutuyor").isTrue();

            CountDownLatch shortRan = new CountDownLatch(1);
            AtomicReference<String> thread = new AtomicReference<>();
            long t0 = System.nanoTime();
            assertThat(r.defer("incident-action-reminder", 50, false, null, () -> {
                thread.set(Thread.currentThread().getName());
                shortRan.countDown();
            })).isTrue();
            assertThat(shortRan.await(3, TimeUnit.SECONDS)).as("kısa deneme uzun işler bitmeden koştu").isTrue();
            long tookMs = (System.nanoTime() - t0) / 1_000_000L;
            assertThat(tookMs).as("gecikmesi (50 ms) dolunca başladı").isLessThan(2_000L);
            assertThat(thread.get()).startsWith("side-job-short-");
            assertThat(job(r, "nightly-cleanup").state()).as("uzun işler hâlâ koşuyor").isEqualTo(SideJobRunner.STATE_RUNNING);
        } finally {
            release.countDown();
        }
        assertThat(r.awaitIdle(5_000)).isTrue();
    }

    @Test
    @DisplayName("FX-SIDE #1: liderin ertelediği UZUN cron denemesi uzun iş şeridinde (eski 4 bağımsız değişkenli defer aynen)")
    void deferredHeavyAttemptRunsOnTheLongLane() throws Exception {
        SideJobRunner r = runner(2);
        AtomicReference<String> thread = new AtomicReference<>();
        assertThat(r.defer("cert-hourly", 10, () -> false, () -> thread.set(Thread.currentThread().getName()))).isTrue();
        long deadline = System.currentTimeMillis() + 5_000;
        while (thread.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertThat(thread.get()).startsWith("side-job-").doesNotStartWith("side-job-short-");
    }

    @Test
    @DisplayName("FX-SIDE #1: üretimde uzun iş şeridi en az 8 iş parçacığı ve en az zamanlayıcı havuzu boyu (ayar daha küçükse "
            + "yükseltilir); 0 = satır içi; üst sınır 32")
    void productionThreadFloor() {
        assertThat(SideJobRunner.effectiveThreads(3, 8)).isEqualTo(8);
        assertThat(SideJobRunner.effectiveThreads(1, 8)).isEqualTo(8);
        assertThat(SideJobRunner.effectiveThreads(12, 8)).isEqualTo(12);
        assertThat(SideJobRunner.effectiveThreads(3, 10)).as("zamanlayıcı havuzu büyütülmüşse o kadar").isEqualTo(10);
        assertThat(SideJobRunner.effectiveThreads(3, 2)).as("havuz küçültülse de en az 8").isEqualTo(8);
        assertThat(SideJobRunner.effectiveThreads(0, 8)).as("0 = satır içi").isZero();
        assertThat(SideJobRunner.effectiveThreads(-1, 8)).isZero();
        assertThat(SideJobRunner.effectiveThreads(100, 8)).isEqualTo(SideJobRunner.MAX_THREADS);
        assertThat(SideJobRunner.effectiveThreads(3, 100)).isEqualTo(SideJobRunner.MAX_THREADS);
        SideJobRunner bean = new SideJobRunner(3, 8);                // Spring kurucusu (application.properties: 3)
        runners.add(bean);
        assertThat(bean.threads()).isEqualTo(SideJobRunner.MIN_THREADS);
        SideJobRunner one = new SideJobRunner(3);
        runners.add(one);
        assertThat(one.threads()).isEqualTo(SideJobRunner.MIN_THREADS);
        assertThat(SideJobRunner.MIN_THREADS).as("20.167.0'ın zamanlayıcı havuzu (spring.task.scheduling.pool.size)").isEqualTo(8);
    }

    @Test
    @DisplayName("FX-SIDE #1: sabit gecikmeli iş son BİTİŞTEN gecikme kadar sonra başlar (tık + gecikme değil); bekleyen / koşan "
            + "işin tıkı sessizce düşer (atlandı sayılmaz); saat geri gittiyse en çok bir gecikme beklenir")
    void submitAfterGap_holdsUntilFinishPlusGap() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        SideJobRunner r = new SideJobRunner(2, clock::get);
        runners.add(r);
        long gap = 600_000L;
        AtomicInteger runs = new AtomicInteger();
        Runnable sevenMinutes = () -> {
            runs.incrementAndGet();
            clock.addAndGet(7 * 60_000L);                              // tur 7 dk sürer
        };
        long t0 = clock.get();
        assertThat(r.submitAfterGap("tls-profile", gap, sevenMinutes)).as("geçmiş yok → hemen").isTrue();
        assertThat(r.awaitIdle(5_000)).isTrue();
        assertThat(runs).hasValue(1);
        assertThat(job(r, "tls-profile").lastFinishedAtMs()).isEqualTo(t0 + 7 * 60_000L);

        clock.set(t0 + gap);                                           // Spring'in tıkı: gönderimden 10 dk sonra
        assertThat(r.submitAfterGap("tls-profile", gap, sevenMinutes)).as("bitiş + 10 dk'ya dek bekletildi").isTrue();
        assertThat(runs).as("bitişten 3 dk sonra koşmadı").hasValue(1);
        assertThat(job(r, "tls-profile").state()).isEqualTo(SideJobRunner.STATE_QUEUED);
        assertThat(r.submitAfterGap("tls-profile", gap, sevenMinutes)).as("bekleyen işin tıkı düştü").isFalse();
        assertThat(job(r, "tls-profile").skippedCount()).as("sabit gecikmeli tık atlandı sayılmaz").isZero();
        assertThat(job(r, "tls-profile").deferredCount()).as("lider ertelemesi değil").isZero();

        clock.set(t0 + 7 * 60_000L + gap);                             // bekleme zamanlayıcısı bitiş + 10 dk'da çalar
        assertThat(r.runDeferredNow()).isEqualTo(1);
        assertThat(runs).hasValue(2);

        clock.set(job(r, "tls-profile").lastFinishedAtMs() + gap);     // tam bitiş + gecikme → beklemeden
        assertThat(r.submitAfterGap("tls-profile", gap, sevenMinutes)).isTrue();
        assertThat(r.awaitIdle(5_000)).isTrue();
        assertThat(runs).hasValue(3);

        clock.addAndGet(-3_600_000L);                                  // saat bir saat geri gitti
        assertThat(r.submitAfterGap("tls-profile", gap, sevenMinutes)).isTrue();
        assertThat(job(r, "tls-profile").state()).as("en çok bir gecikme bekler (koşmaz, sonsuz da beklemez)")
                .isEqualTo(SideJobRunner.STATE_QUEUED);
        assertThat(r.runDeferredNow()).isEqualTo(1);
        assertThat(runs).hasValue(4);
    }

    @Test
    @DisplayName("FX-SIDE #1: kapanışta iki şerit de ortak süreyle beklenir; ertelenmiş kısa ve sabit gecikme beklemeleri iptal")
    void stopAndWaitCoversBothLanes() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        SideJobRunner r = new SideJobRunner(1, clock::get);
        runners.add(r);
        AtomicInteger body = new AtomicInteger();
        assertThat(r.submitAfterGap("cert-stale", 300_000L, body::incrementAndGet)).isTrue();
        assertThat(r.awaitIdle(5_000)).isTrue();
        assertThat(r.submitAfterGap("cert-stale", 300_000L, body::incrementAndGet)).as("bekletildi").isTrue();
        assertThat(r.defer("ca-pin-refresh", 60_000, false, null, body::incrementAndGet)).isTrue();
        long t0 = System.nanoTime();
        r.stopAndWait();
        assertThat((System.nanoTime() - t0) / 1_000_000L).as("koşan iş yok → beklemeden").isLessThan(2_000L);
        assertThat(r.runDeferredNow()).as("iptal edildiler").isZero();
        assertThat(body).hasValue(1);
    }

    @Test
    @DisplayName("Sistem Sağlığı sözleşmesi (paket B): status() iş adına göre sıralı, zamanlar epoch ms, yerleşim / ipucu fırlatsa da "
            + "fırlatmaz ve veritabanı okumaz")
    void statusContractForSystemHealth() {
        long before = System.currentTimeMillis();
        SideJobRunner r = runner(2);
        PodLiveness boom = org.mockito.Mockito.mock(PodLiveness.class);
        org.mockito.Mockito.when(boom.cachedAnotherLivePod()).thenThrow(new IllegalStateException("db"));
        r.setPlacement(new SideJobPlacement(() -> { throw new IllegalStateException("settings"); }, boom, 0));
        r.bindLeaderHint(() -> { throw new IllegalStateException("hint"); });
        assertThat(r.submit("weekly-availability", () -> { })).isTrue();
        assertThat(r.submit("cert-hourly", () -> { })).isTrue();
        r.noteYielded("tls-profile");
        assertThat(r.awaitIdle(5_000)).isTrue();

        SideJobRunner.Status s = r.status();
        assertThat(s.jobs()).extracting(SideJobRunner.Status.Job::name)
                .containsExactly("cert-hourly", "tls-profile", "weekly-availability");
        SideJobRunner.Status.Job j = job(r, "cert-hourly");
        assertThat(j.startedAtMs()).as("epoch ms").isBetween(before, System.currentTimeMillis());
        assertThat(j.lastFinishedAtMs()).isGreaterThanOrEqualTo(j.startedAtMs());
        assertThat(job(r, "tls-profile").startedAtMs()).as("hiç koşmadı").isNull();
        assertThat(s.leaderHint()).as("ipucu fırlattı → false").isFalse();
        assertThat(s.leaderYieldEnabled()).as("ayar okunamadı → varsayılan").isEqualTo(SideJob.LEADER_YIELD_DEFAULT);
        assertThat(s.otherLivePod()).as("gözlem okunamadı → bilinmiyor").isNull();
        org.mockito.Mockito.verify(boom, org.mockito.Mockito.never()).otherLivePods();
        org.mockito.Mockito.verify(boom, org.mockito.Mockito.never()).anotherLivePod();
    }
}
