package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bildirim havuzu kapanışta kuyruğunu BİTİRİR (2026-10-21, çok pod R1 / MP-07). Eskiden çözüm postası ve elle yeniden
 * gönderim {@code certCheckExecutor}'daydı: kapanışta beklemiyor, reddi sessizce yutuyordu — her yayılımda "ÇÖZÜLDÜ"
 * postaları kayboluyordu.
 */
class NotifyExecutorShutdownTest {

    @Test
    @DisplayName("havuz ayarları: kapanışta bekler (20 sn), bildirim işi certCheckExecutor'dan ayrı")
    void poolSettings() {
        ThreadPoolTaskExecutor ex = new WebConfig().notifyExecutor();
        try {
            assertThat(ex.getCorePoolSize()).isEqualTo(2);
            assertThat(ex.getMaxPoolSize()).isEqualTo(4);
            assertThat(ex.getQueueCapacity()).isEqualTo(1000);
            assertThat(ex.getThreadNamePrefix()).isEqualTo("notify-");
        } finally {
            ex.shutdown();
        }
    }

    @Test
    @DisplayName("bağlam kapanırken KUYRUKTAKİ çözüm postası işi koşar (yok etme sırasından bağımsız, ContextClosedEvent'te boşaltılır)")
    void contextCloseRunsQueuedResolutionTask() throws Exception {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("notifyExecutor", ThreadPoolTaskExecutor.class, () -> new WebConfig().notifyExecutor());
        ctx.registerBean("loginIssueMailExecutor", ThreadPoolTaskExecutor.class, () -> new WebConfig().loginIssueMailExecutor());
        ctx.register(NotifyExecutorDrain.class);
        ctx.refresh();
        ThreadPoolTaskExecutor ex = ctx.getBean("notifyExecutor", ThreadPoolTaskExecutor.class);
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch busy = new CountDownLatch(1);
        // 2 iş parçacığını meşgul et, arkasına 5 "çözüm postası" kuyruğa girsin
        for (int i = 0; i < 2; i++) {
            ex.execute(() -> {
                try { busy.await(300, TimeUnit.MILLISECONDS); } catch (InterruptedException ignore) { }
                ran.incrementAndGet();
            });
        }
        for (int i = 0; i < 5; i++) ex.execute(ran::incrementAndGet);
        ctx.close();
        assertThat(ran.get()).as("kapanışta kuyruktaki işler bitti").isEqualTo(7);
    }

    @Test
    @DisplayName("boşaltma süreyle sınırlı: bitmeyen iş sayısını döner, döngü sonsuz değil")
    void drainIsBounded() throws Exception {
        ThreadPoolTaskExecutor ex = new WebConfig().notifyExecutor();
        CountDownLatch hold = new CountDownLatch(1);
        try {
            ex.execute(() -> { try { hold.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) { } });
            long t0 = System.nanoTime();
            int left = NotifyExecutorDrain.drain(List.of(ex), 200);
            assertThat(left).isEqualTo(1);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(2_000);
        } finally {
            hold.countDown();
            ex.shutdown();
        }
        assertThat(NotifyExecutorDrain.drain(List.of(), 1_000)).isZero();
    }

    @Test
    @DisplayName("kapanmış havuz reddi SESSİZ değil ama fırlatmaz (WARN; çözüm postası kalıcı işaretle başka pod'dan gider)")
    void rejectedAfterShutdownDoesNotThrow() {
        ThreadPoolTaskExecutor ex = new WebConfig().notifyExecutor();
        ex.shutdown();
        AtomicInteger ran = new AtomicInteger();
        ex.getThreadPoolExecutor().getRejectedExecutionHandler().rejectedExecution(ran::incrementAndGet, ex.getThreadPoolExecutor());
        assertThat(ran.get()).isZero();
    }

    @Test
    @DisplayName("inceleme N3: kapanış boşaltması push worker'ını ÖNCE durdurur, sonra süren turunu bildirim havuzlarıyla AYNI süre içinde bekler")
    void contextCloseStopsAndDrainsThePushWorker() {
        com.sitemonitor.service.UserPushService push = org.mockito.Mockito.mock(com.sitemonitor.service.UserPushService.class);
        org.mockito.Mockito.when(push.inFlightBatches()).thenReturn(1, 1, 0);
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("notifyExecutor", ThreadPoolTaskExecutor.class, () -> new WebConfig().notifyExecutor());
        ctx.registerBean("loginIssueMailExecutor", ThreadPoolTaskExecutor.class, () -> new WebConfig().loginIssueMailExecutor());
        ctx.registerBean(com.sitemonitor.service.UserPushService.class, () -> push);
        ctx.register(NotifyExecutorDrain.class);
        ctx.refresh();
        ctx.close();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(push);
        order.verify(push).beginShutdown();
        order.verify(push, org.mockito.Mockito.atLeast(3)).inFlightBatches();
    }

    @Test
    @DisplayName("inceleme N3: push turu bitmezse boşaltma yine süreyle sınırlı (ek süre yok); push okunamazsa beklenmez")
    void pushDrainIsBounded() {
        long t0 = System.nanoTime();
        assertThat(NotifyExecutorDrain.drain(List.of(), () -> 1, 200)).isEqualTo(1);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(2_000);
        assertThat(NotifyExecutorDrain.drain(List.of(), () -> { throw new IllegalStateException("kapandı"); }, 1_000)).isZero();
    }
}
