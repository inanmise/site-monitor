package com.sitemonitor.service.schedule;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Küme zamanlama kaydının atomikliği GERÇEK PostgreSQL'de (2026-10-09) — {@code mvn -Ppostgres-it test}. Tablo açılış
 * yamasıyla kurulur ({@code SchedulerService.applySchemaPatches} → {@link ClusterScheduleService#DDL}); burada READ
 * COMMITTED altında eşzamanlı {@code INSERT … ON CONFLICT DO NOTHING} (ilk görüş) ve koşullu UPDATE (her aralık)
 * yarışlarının TEK kazananı olduğu, ikinci kazananın olmadığı kanıtlanır. Anahtarlar rastgele: paylaşılan bağlamdaki
 * başka testlerle çakışmaz.
 */
@PostgresIntegration
class ClusterSchedulePostgresTest {

    private static final long MIN = 60_000L;

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static LongUnaryOperator grid(long nowMs, long intervalMs) {
        return prev -> {
            if (prev > nowMs) return nowMs + intervalMs;
            long next = prev;
            while (next <= nowMs) next += intervalMs;
            return next;
        };
    }

    @Test
    @DisplayName("PostgreSQL: 8 pod eşzamanlı yarışır — ilk görüşte ve her aralıkta TEK sahiplenen")
    void concurrentClaims_singleWinnerPerWindow() throws Exception {
        JdbcTemplate jdbc = jdbc();
        int pods = 8;
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            for (int k = 0; k < 10; k++) {
                String key = "it-port:" + UUID.randomUUID();
                for (int round = 0; round < 3; round++) {
                    long now = t0 + round * MIN;
                    Long known = round == 0 ? null : now;
                    CyclicBarrier start = new CyclicBarrier(pods);
                    List<Future<Boolean>> results = new ArrayList<>();
                    for (int p = 0; p < pods; p++) {
                        ClusterScheduleService pod = new ClusterScheduleService(jdbc, "it-pod-" + p);
                        results.add(pool.submit(() -> {
                            start.await(10, TimeUnit.SECONDS);
                            return pod.claim(key, now, MIN, known, grid(now, MIN)).due();
                        }));
                    }
                    int winners = 0;
                    for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) winners++;
                    assertThat(winners).as("anahtar %s tur %d", key, round).isEqualTo(1);
                }
                assertThat(jdbc.queryForObject("SELECT next_due_at FROM monitor_check_schedule WHERE monitor_key = ?",
                        Long.class, key)).isEqualTo(t0 + 3 * MIN);
                jdbc.update("DELETE FROM monitor_check_schedule WHERE monitor_key = ?", key);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("PostgreSQL: 8 pod süresi dolmuş (ve hiç olmayan) tarama kirasına aynı anda uzanır → her seferinde TEK lider")
    void concurrentLeaseTakeover_singleLeader() throws Exception {
        JdbcTemplate jdbc = jdbc();
        int pods = 8;
        long ttl = 3 * MIN;
        String name = "it-leader-" + UUID.randomUUID();           // uygulamanın kendi "sweep-leader" kirasına dokunma
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            for (int round = 0; round < 5; round++) {
                long now = t0 + round * (ttl + 1);
                CyclicBarrier start = new CyclicBarrier(pods);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int p = 0; p < pods; p++) {
                    ClusterScheduleService pod = new ClusterScheduleService(jdbc, "it-pod-" + round + "-" + p);
                    results.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return pod.acquireLease(name, now, ttl);
                    }));
                }
                int winners = 0;
                for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) winners++;
                assertThat(winners).as("tur %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM monitor_check_schedule WHERE monitor_key = ?", ClusterScheduleService.LEASE_PREFIX + name);
        }
    }

    @Test
    @DisplayName("PostgreSQL: budama yalnız canlı kümede olmayan izleme satırlarını siler, tur satırlarına dokunmaz")
    void prune_onPostgres() {
        JdbcTemplate jdbc = jdbc();
        ClusterScheduleService pod = new ClusterScheduleService(jdbc, "it-pod");
        long now = System.currentTimeMillis();
        String live = "it-live:" + UUID.randomUUID();
        String dead = "it-dead:" + UUID.randomUUID();
        String round = ClusterScheduleService.ROUND_PREFIX + "it-" + UUID.randomUUID();
        for (String k : List.of(live, dead, round)) pod.claim(k, now, MIN, null, grid(now, MIN));

        // Canlı küme = tablodaki diğer satırlar + live (paylaşılan bağlamdaki başka satırlar silinmesin).
        java.util.Set<String> liveKeys = new java.util.HashSet<>(
                jdbc.queryForList("SELECT monitor_key FROM monitor_check_schedule", String.class));
        liveKeys.remove(dead);
        pod.prune(liveKeys);

        assertThat(jdbc.queryForList("SELECT monitor_key FROM monitor_check_schedule WHERE monitor_key IN (?, ?, ?)",
                String.class, live, dead, round)).containsExactlyInAnyOrder(live, round);
        jdbc.update("DELETE FROM monitor_check_schedule WHERE monitor_key IN (?, ?)", live, round);
    }
}
