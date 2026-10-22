package com.sitemonitor.service.lockout;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.lockout.RateLimitCounterService.Limit;
import com.sitemonitor.service.lockout.RateLimitCounterService.State;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ortak oran sınırı GERÇEK PostgreSQL'de (2026-10-23, çok pod Sürüm 2a — MP-31) — {@code mvn -Ppostgres-it test}. Tablo
 * ddl-auto + {@code RateLimitCounterSchemaPatch} ile kurulur. Tek ifadelik {@code INSERT … ON CONFLICT … DO UPDATE …
 * RETURNING}: READ COMMITTED altında eşzamanlı yazımlar sayımı KAYBETMEZ, pencere / blok kuralları H2'nin taşınabilir
 * yoluyla aynı sonucu verir. Anahtarlar rastgele: uygulamanın kendi satırlarıyla çakışmaz.
 */
@PostgresIntegration
class RateLimitCounterPostgresTest {

    static final Limit LOGIN = new Limit(60_000L, 10, 30_000L);

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @Test
    @DisplayName("PostgreSQL: tek ifadelik upsert seçilir; 5 + 5 hata iki örnekte → blok; RETURNING sayımı ve bitişi doğru")
    void upsertBlocksAcrossInstances() {
        String key = RateLimitCounterService.key("it-login-ip", UUID.randomUUID().toString());
        AtomicLong clock = new AtomicLong(1_800_000_000_000L);
        RateLimitCounterService a = new RateLimitCounterService(jdbc(), clock::get);
        RateLimitCounterService b = new RateLimitCounterService(jdbc(), clock::get);
        try {
            for (int i = 0; i < 5; i++) assertThat(a.hit(key, LOGIN).memory()).isFalse();
            for (int i = 0; i < 4; i++) b.hit(key, LOGIN);
            assertThat(a.blockedSeconds(key)).isZero();
            State tenth = b.hit(key, LOGIN);
            assertThat(tenth.attempts()).isEqualTo(10);
            assertThat(tenth.blockedUntil()).isEqualTo(clock.get() + 30_000L);
            assertThat(a.blockedSeconds(key)).isEqualTo(30L);

            clock.addAndGet(30_000L);   // blok bitti → yeni pencere
            State fresh = a.hit(key, LOGIN);
            assertThat(fresh.attempts()).isEqualTo(1);
            assertThat(fresh.blockedUntil()).isZero();

            clock.addAndGet(60_001L);   // pencere doldu → yeniden 1
            assertThat(b.hit(key, LOGIN).attempts()).isEqualTo(1);

            a.clear(key);
            assertThat(jdbc().queryForObject("SELECT count(*) FROM rate_limit_counters WHERE limit_key = ?", Integer.class, key))
                    .isZero();
        } finally {
            jdbc().update("DELETE FROM rate_limit_counters WHERE limit_key = ?", key);
        }
    }

    @Test
    @DisplayName("PostgreSQL: 8 iş parçacığı × 5 hata aynı anahtara (ilk satır dahil) → sayım KAYIPSIZ 40")
    void concurrentUpsert_noLostUpdate() throws Exception {
        String key = RateLimitCounterService.key("it-login-ip", UUID.randomUUID().toString());
        AtomicLong clock = new AtomicLong(1_800_000_000_000L);
        Limit wide = new Limit(60_000L, 1_000, 30_000L);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier start = new CyclicBarrier(threads);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                RateLimitCounterService pod = new RateLimitCounterService(jdbc(), clock::get);
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    boolean allDb = true;
                    for (int i = 0; i < 5; i++) allDb &= !pod.hit(key, wide).memory();
                    return allDb;
                }));
            }
            for (Future<Boolean> f : futures) assertThat(f.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc().queryForObject("SELECT attempts FROM rate_limit_counters WHERE limit_key = ?", Integer.class, key))
                    .isEqualTo(threads * 5);
        } finally {
            pool.shutdownNow();
            jdbc().update("DELETE FROM rate_limit_counters WHERE limit_key = ?", key);
        }
    }
}
