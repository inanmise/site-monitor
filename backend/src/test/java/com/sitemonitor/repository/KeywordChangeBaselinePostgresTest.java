package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.KeywordChangeBaseline;
import com.sitemonitor.service.keyword.content.ContentMasker;
import com.sitemonitor.service.keyword.content.KeywordChangeBaselineService;
import com.sitemonitor.service.keyword.content.KeywordChangeDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İçerik değişimi temeli GERÇEK PostgreSQL'de (kw sözleşmesi §3.3, be2 Aşama 2) — {@code mvn -Ppostgres-it test}. Otomatik alım
 * {@code INSERT … ON CONFLICT (monitor_id) DO NOTHING}: iki pod aynı anda alırsa tek satır ve en çok bir "ekledim"; elle alım
 * {@code ON CONFLICT DO UPDATE} (sürüm artar); silme. İzleme kimlikleri uygulamanın kendi satırlarıyla çakışmayacak kadar büyük.
 */
@PostgresIntegration
class KeywordChangeBaselinePostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static KeywordChangeDetector.Snapshot snap(String... lines) {
        return KeywordChangeDetector.snapshot(List.of(lines), EnumSet.noneOf(ContentMasker.Mask.class), List.of());
    }

    private static long freshId() {
        return 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
    }

    @Test
    @DisplayName("PostgreSQL: otomatik alım insert-if-absent — 10 tur iki iş parçacığı → tek satır, en çok bir 'ekledim'; elle alım upsert")
    void autoInsertRaceAndManualUpsert() throws Exception {
        KeywordChangeBaselineService svc = PostgresIt.app().bean(KeywordChangeBaselineService.class);
        List<Long> ids = new ArrayList<>();
        try {
            for (int round = 0; round < 10; round++) {
                long id = freshId();
                ids.add(id);
                CyclicBarrier barrier = new CyclicBarrier(2);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    List<Future<Boolean>> fs = new ArrayList<>();
                    for (int t = 0; t < 2; t++) {
                        String line = "pod-" + t;
                        fs.add(pool.submit(() -> {
                            barrier.await(10, TimeUnit.SECONDS);
                            return svc.takeAuto(id, snap(line), 10, "spec");
                        }));
                    }
                    int inserted = 0;
                    for (Future<Boolean> f : fs) if (f.get(30, TimeUnit.SECONDS)) inserted++;
                    assertThat(inserted).as("tur " + round).isLessThanOrEqualTo(1);
                    assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM keyword_change_baselines WHERE monitor_id = ?",
                            Integer.class, id)).isEqualTo(1);
                } finally {
                    pool.shutdownNow();
                }
            }
            long id = ids.get(0);
            KeywordChangeBaseline before = svc.find(id).orElseThrow();
            KeywordChangeBaseline after = svc.takeManual(id, snap("elle", "temel"), 99, "spec2", "ayse", "Ayşe Yılmaz");
            assertThat(after.getSource()).isEqualTo("MANUAL");
            assertThat(after.getText()).isEqualTo("elle\ntemel");
            assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
            assertThat(svc.takeAuto(id, snap("otomatik"), 1, "spec2")).as("var → dokunmaz").isFalse();
            assertThat(svc.delete(id)).isTrue();
            assertThat(svc.find(id)).isEmpty();
        } finally {
            for (Long id : ids) jdbc().update("DELETE FROM keyword_change_baselines WHERE monitor_id = ?", id);
        }
    }
}
