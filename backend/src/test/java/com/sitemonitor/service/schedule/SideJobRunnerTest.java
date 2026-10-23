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
}
