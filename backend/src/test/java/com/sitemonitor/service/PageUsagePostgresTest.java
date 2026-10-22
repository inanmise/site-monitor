package com.sitemonitor.service;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sayfa kullanımı yazımı GERÇEK PostgreSQL'de (2026-10-23, çok pod R2a · D2 — MP-46): {@code INSERT … ON CONFLICT DO UPDATE}
 * yolu seçilir; 6 pod aynı (gün, kullanıcı, sekme) satırına aynı anda ilk kez yazar → pings toplanır, hiçbiri düşmez,
 * {@code last_seen} en yeni değerdir (geri gitmez), {@code first_seen} ilk değer kalır.
 */
@PostgresIntegration
class PageUsagePostgresTest {

    @Test
    @DisplayName("PostgreSQL: 6 pod aynı satıra aynı anda yazar → pings toplamı, last_seen geri gitmez")
    void concurrentUpsert_countsAddUp() throws Exception {
        JdbcTemplate jdbc = PostgresIt.app().jdbc();
        String user = "it-" + UUID.randomUUID().toString().substring(0, 8);
        int pods = 6;
        List<PageUsageService> svc = new ArrayList<>();
        for (int p = 0; p < pods; p++) {
            PageUsageService s = new PageUsageService(jdbc);
            for (int i = 0; i <= p; i++) s.record(user, "overview");   // pod p: p+1 ping
            svc.add(s);
        }
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            CyclicBarrier start = new CyclicBarrier(pods);
            List<Future<?>> fs = new ArrayList<>();
            for (PageUsageService s : svc) fs.add(pool.submit(() -> { start.await(10, TimeUnit.SECONDS); s.flush(); return null; }));
            for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
            for (PageUsageService s : svc) assertThat(s.buffered()).isZero();
            Long pings = jdbc.queryForObject("SELECT SUM(pings) FROM page_usage_daily WHERE username = ?", Long.class, user);
            assertThat(pings).isEqualTo(21L);   // 1+2+…+6
            Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM page_usage_daily WHERE username = ?", Integer.class, user);
            assertThat(rows).isEqualTo(1);

            // last_seen geri gitmez: eski damgalı bir ekleme yeni damgayı ezmez
            String last = jdbc.queryForObject("SELECT last_seen FROM page_usage_daily WHERE username = ?", String.class, user);
            String day = jdbc.queryForObject("SELECT day FROM page_usage_daily WHERE username = ?", String.class, user);
            jdbc.update(PageUsageService.SQL_UPSERT_PG, day, user, "overview", 1L, "2000-01-01T00:00:00", "2000-01-01T00:00:00");
            assertThat(jdbc.queryForObject("SELECT last_seen FROM page_usage_daily WHERE username = ?", String.class, user))
                    .isEqualTo(last);
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM page_usage_daily WHERE username = ?", user);
        }
    }
}
