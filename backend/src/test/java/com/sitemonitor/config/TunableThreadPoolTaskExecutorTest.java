package com.sitemonitor.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Havuz + kuyruk boyutu initialize SONRASI canlı değişir ve alttaki ThreadPoolExecutor'a
 * gerçekten yansır (Spring'in düz setQueueCapacity'si yalnız alanı günceller — kuyruk sabit kalırdı).
 */
class TunableThreadPoolTaskExecutorTest {

    private TunableThreadPoolTaskExecutor ex;

    private TunableThreadPoolTaskExecutor build(int core, int max, int queue) {
        ex = new TunableThreadPoolTaskExecutor();
        ex.setCorePoolSize(core);
        ex.setMaxPoolSize(max);
        ex.setQueueCapacity(queue);
        ex.setThreadNamePrefix("tune-test-");
        ex.setRejectedExecutionHandler((r, e) -> { /* test: taşanı düşür */ });
        ex.initialize();
        return ex;
    }

    @AfterEach
    void tearDown() {
        if (ex != null) ex.shutdown();
    }

    @Test
    @DisplayName("büyütme (20/50 → 60/80): core > eski max olsa da IllegalArgumentException YOK, JDK havuzu yeni değerleri taşır")
    void grow_orderSafe() {
        build(20, 50, 100);
        ex.applyPoolSizes(60, 80);
        assertThat(ex.getThreadPoolExecutor().getCorePoolSize()).isEqualTo(60);
        assertThat(ex.getThreadPoolExecutor().getMaximumPoolSize()).isEqualTo(80);
        assertThat(ex.getCorePoolSize()).isEqualTo(60);
        assertThat(ex.getMaxPoolSize()).isEqualTo(80);
    }

    @Test
    @DisplayName("küçültme (20/50 → 5/10): yeni max < eski core olsa da sıra-güvenli")
    void shrink_orderSafe() {
        build(20, 50, 100);
        ex.applyPoolSizes(5, 10);
        assertThat(ex.getThreadPoolExecutor().getCorePoolSize()).isEqualTo(5);
        assertThat(ex.getThreadPoolExecutor().getMaximumPoolSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("geçersiz havuz (core > max, core < 1) ve kuyruk (< 1) reddedilir, mevcut değer korunur")
    void invalid_rejected() {
        build(20, 50, 100);
        assertThatThrownBy(() -> ex.applyPoolSizes(60, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.applyPoolSizes(0, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ex.applyQueueCapacity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ex.getThreadPoolExecutor().getCorePoolSize()).isEqualTo(20);
        assertThat(ex.getThreadPoolExecutor().getMaximumPoolSize()).isEqualTo(50);
        assertThat(ex.getQueueCapacity()).isEqualTo(100);
        assertThat(ex.getLiveQueueCapacity()).isEqualTo(100);
    }

    @Test
    @DisplayName("kuyruk tavanı canlı: 1 → 3 büyütünce daha önce reddedilen görevler kuyruğa girer")
    void queueCapacity_live() throws Exception {
        build(1, 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        Runnable blocker = () -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} };
        Runnable task = ran::incrementAndGet;
        try {
            ex.execute(blocker);                       // tek thread'i tut
            // Kuyruk 1: ilk görev sığar, ikincisi reddedilir (handler düşürür)
            Thread.sleep(50);
            ex.execute(task);
            ex.execute(task);
            assertThat(ex.getThreadPoolExecutor().getQueue().size()).isEqualTo(1);

            ex.applyQueueCapacity(3);
            assertThat(ex.getQueueCapacity()).as("Spring alanı (health kartı)").isEqualTo(3);
            assertThat(ex.getLiveQueueCapacity()).as("gerçek kuyruk").isEqualTo(3);
            ex.execute(task);
            ex.execute(task);
            assertThat(ex.getThreadPoolExecutor().getQueue().size()).isEqualTo(3);
            assertThat(ex.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
        } finally {
            release.countDown();
        }
        // Spring'in ex.shutdown()'ı varsayılanda shutdownNow → kuyruğu BOŞALTIR; kuyruğa alınan üç
        // görevin gerçekten koştuğunu görmek için JDK havuzunu nazikçe kapat (kuyruk tüketilir).
        ex.getThreadPoolExecutor().shutdown();
        assertThat(ex.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isEqualTo(3);
    }
}
