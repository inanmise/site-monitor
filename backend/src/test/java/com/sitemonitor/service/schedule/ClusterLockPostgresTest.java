package com.sitemonitor.service.schedule;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yenilenen küme kilidi GERÇEK PostgreSQL'de (2026-10-21, çok pod R1-A, MP-02) — {@code mvn -Ppostgres-it test}. Tablo
 * açılış yamasıyla kurulur ({@code SchedulerService.applySchemaPatches}). READ COMMITTED altında eşzamanlı "süresi dolmuşu
 * sil + INSERT" yarışının TEK kazananı olduğu, yenilemenin süreyi uzattığı ve başka sahibin satırına dokunmadığı kanıtlanır.
 * Kilit adları rastgele: uygulamanın kendi işleriyle çakışmaz.
 */
@PostgresIntegration
class ClusterLockPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @Test
    @DisplayName("PostgreSQL: 8 sahip süresi dolmuş (ve hiç olmayan) kilide aynı anda uzanır → her seferinde TEK kazanan")
    void concurrentAcquire_singleWinner() throws Exception {
        JdbcTemplate jdbc = jdbc();
        int pods = 8;
        String name = "it-lock-" + UUID.randomUUID();
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            for (int round = 0; round < 4; round++) {
                CyclicBarrier start = new CyclicBarrier(pods);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int p = 0; p < pods; p++) {
                    ClusterLockService pod = new ClusterLockService(jdbc, "it-lock-pod-" + round + "-" + p, clock::get, false);
                    results.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return pod.tryAcquire(name, Duration.ofMinutes(3), Duration.ofHours(3)).isPresent();
                    }));
                }
                int winners = 0;
                for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) winners++;
                assertThat(winners).as("tur %d", round).isEqualTo(1);
                clock.addAndGet(Duration.ofMinutes(3).toMillis() + 2_000L);   // kazanan yenilemedi → kilit doldu
            }
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM scheduler_lock WHERE name = ?", name);
        }
    }

    @Test
    @DisplayName("PostgreSQL: yenileme süreyi uzatır (diğer sahip alamaz); başka sahibe geçen satırı yenileme ELE GEÇİRMEZ (LOST)")
    void renewal_onPostgres() {
        JdbcTemplate jdbc = jdbc();
        String name = "it-lock-" + UUID.randomUUID();
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        ClusterLockService a = new ClusterLockService(jdbc, "it-renew-a", clock::get, false);
        ClusterLockService b = new ClusterLockService(jdbc, "it-renew-b", clock::get, false);
        try {
            assertThat(a.tryAcquire(name)).isPresent();
            for (int i = 0; i < 12; i++) {
                clock.addAndGet(30_000L);
                assertThat(a.renewAll()).isEqualTo(1);
                assertThat(b.tryAcquire(name)).isEmpty();
            }
            clock.addAndGet(Duration.ofMinutes(3).toMillis() + 2_000L);   // a yenilemedi
            assertThat(b.tryAcquire(name)).isPresent();
            assertThat(a.renewAll()).isZero();
            assertThat(a.held(name)).isFalse();
            assertThat(jdbc.queryForObject("SELECT locked_by FROM scheduler_lock WHERE name = ?", String.class, name))
                    .isEqualTo("it-renew-b");
            a.release(name);
            assertThat(jdbc.queryForObject("SELECT locked_by FROM scheduler_lock WHERE name = ?", String.class, name))
                    .as("eski sahibin bırakması yeni sahibin satırını silmez").isEqualTo("it-renew-b");
            assertThat(b.releaseAllOwn()).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM scheduler_lock WHERE name = ?", name);
        }
    }
}
