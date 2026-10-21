package com.sitemonitor.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ortak yok etme süresi (2026-10-21, çok pod R1 inceleme D-1): sıralı havuz beklemeleri toplamda
 * {@link ShutdownDeadline#DESTROY_BUDGET_MS}'i aşmaz; JVM kapanmıyorken (testler, elle kurulan nesneler) her bekleme kendi
 * tavanıyla — davranış eskisiyle aynı.
 */
class ShutdownDeadlineTest {

    @AfterEach
    void reset() {
        ShutdownDeadline.activate(null);
    }

    @Test
    @DisplayName("etkin değilken kendi tavanı; etkinken ortak süre ilk beklemeyle başlar ve paylaşılır")
    void sharedBudget() {
        assertThat(ShutdownDeadline.waitMs(20_000)).isEqualTo(20_000);
        ShutdownDeadline d = new ShutdownDeadline();
        long t0 = 1_000_000_000L;
        assertThat(d.remainingMs(20_000, t0)).as("ilk bekleme bütçeyle sınırlı").isEqualTo(ShutdownDeadline.DESTROY_BUDGET_MS);
        assertThat(d.remainingMs(5_000, t0 + 7_000_000_000L)).as("7 sn sonra kalan 3 sn").isEqualTo(3_000);
        assertThat(d.remainingMs(2_000, t0 + 7_000_000_000L)).as("tavan daha küçükse tavan").isEqualTo(2_000);
        assertThat(d.remainingMs(10_000, t0 + 12_000_000_000L)).as("bütçe bitti").isZero();
        assertThat(d.remainingMs(-1, t0)).isZero();
    }

    @Test
    @DisplayName("JVM kapanmıyorken ContextClosedEvent ortak süreyi etkinleştirmez")
    void inactiveOutsideJvmShutdown() {
        assertThat(ShutdownDeadline.jvmShuttingDown()).isFalse();
        new ShutdownDeadline().onContextClosed();
        assertThat(ShutdownDeadline.waitMs(15_000)).isEqualTo(15_000);
    }

    @Test
    @DisplayName("havuz yok etme anında ortak süreyi okur: bütçe bitmişse beklemeden kapanır (süren iş yine biter)")
    void deadlineAwareExecutorUsesRemainingBudget() throws Exception {
        ShutdownDeadline d = new ShutdownDeadline();
        ShutdownDeadline.activate(d);
        d.remainingMs(0, System.nanoTime() - 20_000_000_000L);   // bütçe 20 sn önce başladı → kalan 0
        ThreadPoolTaskExecutor ex = new WebConfig().notifyExecutor();
        CountDownLatch release = new CountDownLatch(1);
        ex.execute(() -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) { } });
        long t0 = System.nanoTime();
        ex.shutdown();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("20 sn beklemedi").isLessThan(2_000);
        release.countDown();
    }
}
