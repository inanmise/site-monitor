package com.sitemonitor.service.cache;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Önbellek veri yolu GERÇEK PostgreSQL'de (2026-10-22, çok pod R2-A, MP-30) — {@code mvn -Ppostgres-it test}. Tablo açılış
 * yamasıyla ({@link CacheBusSchemaPatch}) kurulur; 8 "pod" READ COMMITTED altında aynı adları eşzamanlı yayınlar: her yayın
 * sayılır, kilitlenme (40P01) yok (önce {@code _global} satırı kilitlenir), eşzamanlı ilk ekleme yinelenen anahtarla
 * düşmez. Adlar rastgele: paylaşılan bağlamın kendi veri yoluyla çakışmaz.
 */
@PostgresIntegration
class CacheBusPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static CacheBus pod() {
        return new CacheBus(jdbc(), PostgresIt.app().context().getBeanProvider(PlatformTransactionManager.class));
    }

    private static long gen(String name) {
        List<Long> l = jdbc().queryForList("SELECT generation FROM cache_bus WHERE name = ?", Long.class, name);
        return l.isEmpty() ? -1 : l.get(0);
    }

    private static String name() {
        return "it-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("PostgreSQL: açılış yaması tabloyu kurdu; uygulamanın kendi veri yolu çalışıyor ve tabloyu görüyor")
    void tableAndRunningBus() {
        Integer n = jdbc().queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'cache_bus'",
                Integer.class);
        assertThat(n).isEqualTo(1);
        CacheBus app = PostgresIt.app().bean(CacheBus.class);
        assertThat(app.pollNow()).isTrue();
        assertThat(app.status()).containsEntry("table_missing", false).containsEntry("running", true);
    }

    @Test
    @DisplayName("PostgreSQL: 8 pod × 25 eşzamanlı yayın — her biri sayılır, kilitlenme ve yinelenen anahtar hatası yok")
    void concurrentBumps_noDeadlock() throws Exception {
        String shared1 = name(), shared2 = name();
        long globalBefore = Math.max(0, gen(CacheBus.GLOBAL));
        int pods = 8, rounds = 25;
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            CyclicBarrier start = new CyclicBarrier(pods);
            List<Future<Integer>> results = new ArrayList<>();
            for (int p = 0; p < pods; p++) {
                CacheBus bus = pod();
                String own = name();
                boolean reversed = p % 2 == 1;
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    int ok = 0;
                    for (int i = 0; i < rounds; i++) {
                        Set<String> names = reversed ? new java.util.LinkedHashSet<>(List.of(shared2, own, shared1))
                                : new java.util.LinkedHashSet<>(List.of(shared1, shared2, own));
                        if (bus.bumpNow(names) != null) ok++;
                    }
                    return ok;
                }));
            }
            for (Future<Integer> f : results) assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(rounds);
        } finally {
            pool.shutdownNow();
        }
        assertThat(gen(shared1)).isEqualTo((long) pods * rounds);
        assertThat(gen(shared2)).isEqualTo((long) pods * rounds);
        assertThat(gen(CacheBus.GLOBAL) - globalBefore).as("her yayın genel satırı bir kez artırır (başka testler de artırabilir)")
                .isGreaterThanOrEqualTo((long) pods * rounds);
    }

    @Test
    @DisplayName("PostgreSQL: A yayınlar → B bir yoklamada boşaltır; B yeniden yayın yapmaz; yazdığını-oku bekleyişi tek yoklama")
    void bumpOnA_evictsOnB() {
        String n = name();
        CacheBus a = pod(), b = pod();
        AtomicInteger onB = new AtomicInteger();
        b.register(n, onB::incrementAndGet);
        a.pollNow();
        b.pollNow();
        Long g = a.bumpNow(Set.of(n));
        assertThat(g).isNotNull();
        assertThat(b.awaitGeneration(g)).isTrue();
        assertThat(onB.get()).isEqualTo(1);
        b.pollNow();
        assertThat(onB.get()).isEqualTo(1);
        assertThat(gen(n)).isEqualTo(1);
    }
}
