package com.sitemonitor.controller;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.service.dbquery.DbQueryMonitorSchemaPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Veritabanı sorgu izlemesi adının TÜM takımlarda tekilliği GERÇEK PostgreSQL'de (inceleme 2026-10-31, K1) —
 * {@code mvn -Ppostgres-it test}. {@code DbQueryMonitorSchemaPatch} açılışta {@code ux_dbqm_name_lower} ({@code LOWER(name)})
 * tekil indeksini kurar; iki takımdan aynı ad (yalnız büyük-küçük harfle farklı) eşzamanlı kaydedilince biri reddedilir ve
 * denetleyicinin tanıma kuralı ({@link MonitoringController#dbqNameViolation}) ihlali ad çakışması sayar (409
 * {@code DUPLICATE_RECORD}, alan {@code name}). Adlar rastgele: uygulamanın kendi satırlarıyla çakışmaz.
 */
@PostgresIntegration
class DbQueryNamePostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static DbQueryMonitor monitor(String name, long team) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setName(name);
        m.setTeamId(team);
        m.setActive(true);
        m.setSqlText("SELECT 1");
        return m;
    }

    @Test
    @DisplayName("PostgreSQL: ux_dbqm_name_lower açılıştan sonra yerinde (yama idempotent); başka takımdan büyük-küçük harf farkıyla aynı ad reddedilir")
    void indexInPlaceAndRejectsCaseVariantAcrossTeams() {
        assertThat(jdbc().queryForObject("SELECT to_regclass('ux_dbqm_name_lower') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(new DbQueryMonitorSchemaPatch(jdbc()).apply()).as("ikinci uygulama hiçbir şey yapmaz").isFalse();
        DbQueryMonitorRepository repo = PostgresIt.app().bean(DbQueryMonitorRepository.class);
        String base = "it-dbq-" + UUID.randomUUID();
        try {
            repo.save(monitor(base, 1L));
            assertThat(repo.existsDuplicateName(base.toUpperCase(java.util.Locale.ROOT), null)).isTrue();
            assertThatThrownBy(() -> repo.save(monitor(base.toUpperCase(java.util.Locale.ROOT), 2L)))
                    .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                            e -> assertThat(MonitoringController.dbqNameViolation(e)).as("ihlal ad çakışması sayılır").isTrue());
        } finally {
            jdbc().update("DELETE FROM dbquery_monitors WHERE LOWER(name) = LOWER(?)", base);
        }
    }

    @Test
    @DisplayName("PostgreSQL: iki pod aynı adı AYNI ANDA kaydeder (uygulama denetimi ikisinde de geçmiş) → biri yazılır, öteki tekil ihlali")
    void concurrentInsertsOneWins() throws Exception {
        DbQueryMonitorRepository repo = PostgresIt.app().bean(DbQueryMonitorRepository.class);
        String base = "it-dbq-race-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            List<Future<Boolean>> results = new ArrayList<>();
            for (long team : new long[]{1L, 2L}) {
                String name = team == 1L ? base : base.toUpperCase(java.util.Locale.ROOT);
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        repo.save(monitor(name, team));
                        return true;
                    } catch (DataIntegrityViolationException e) {
                        assertThat(MonitoringController.dbqNameViolation(e)).isTrue();
                        return false;
                    }
                }));
            }
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> f : results) outcomes.add(f.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM dbquery_monitors WHERE LOWER(name) = LOWER(?)",
                    Integer.class, base)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            jdbc().update("DELETE FROM dbquery_monitors WHERE LOWER(name) = LOWER(?)", base);
        }
    }
}
