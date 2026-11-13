package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Veritabanı büyümesi GERÇEK PostgreSQL'de (2026-11-13): boyut / istatistik sorguları, {@code pg_stats.histogram_bounds}
 * okuması (ANALYZE sonrası), kuralın kendi WHERE'iyle zaman aşımlı COUNT, MIN, vacuum / kullanılmayan dizin / şişme okumaları,
 * son 24 sa GROUP BY ve görüntünün {@code ON CONFLICT DO NOTHING} yazımı.
 */
@PostgresIntegration
class DbGrowthPostgresTest {

    @Test
    @DisplayName("veri kaynağı: tür, boyut, tablolar, histogram, MIN, COUNT, vacuum, dizinler, sıcak izleme — hepsi PostgreSQL'de koşar")
    void sourceOnPostgres() {
        PgDbSizeSource src = PostgresIt.app().bean(PgDbSizeSource.class);
        JdbcTemplate jdbc = PostgresIt.app().jdbc();
        assertThat(src.supported()).isTrue();
        assertThat(src.databaseBytes()).isPositive();
        jdbc.update("DELETE FROM ping_checks WHERE monitor_id = 987654");
        Instant now = Instant.now();
        for (int i = 0; i < 200; i++) {
            jdbc.update("INSERT INTO ping_checks(monitor_id, up, checked_at) VALUES (987654, TRUE, ?)",
                    now.minus(i, ChronoUnit.HOURS).toString().substring(0, 19));
        }
        jdbc.execute("ANALYZE ping_checks");
        List<DbSizeSource.TableSize> tables = src.tables();
        assertThat(tables).extracting(DbSizeSource.TableSize::name).contains("ping_checks", "db_size_daily");
        RetentionPolicy ping = RetentionCatalog.byId("series-ping").orElseThrow();
        Map<String, List<String>> h = src.histograms(List.<String[]>of(new String[]{"ping_checks", "checked_at"}));
        assertThat(h.get("ping_checks.checked_at")).as("ANALYZE sonrası histogram").isNotEmpty();
        Double frac = DbGrowthForecast.histogramFractionBefore(h.get("ping_checks.checked_at"), now.minus(100, ChronoUnit.HOURS));
        assertThat(frac).isNotNull().isBetween(0.0, 1.0);
        assertThat(src.oldest(ping)).isNotNull();
        String cutoff = DbGrowthService.cutoffFor(ping, 1, now);
        assertThat(src.countOlder(ping, cutoff)).isGreaterThanOrEqualTo(170L);
        assertThat(src.vacuumStats()).extracting(DbSizeSource.VacuumStat::table).contains("ping_checks");
        assertThat(src.unusedIndexes(1L << 40)).isEmpty();
        assertThat(src.bloat(List.of("ping_checks"))).isNotNull();
        List<long[]> hot = src.hotMonitors("ping_checks", now.minus(1, ChronoUnit.DAYS).toString().substring(0, 19), 5);
        assertThat(hot).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(987654L);
            assertThat(r[1]).isBetween(23L, 25L);
        });
        jdbc.update("DELETE FROM ping_checks WHERE monitor_id = 987654");
    }

    @Test
    @DisplayName("görüntü: ON CONFLICT DO NOTHING gün × tablo tekil; görünüm PostgreSQL'de hesaplanır")
    void snapshotOnPostgres() {
        DbGrowthService svc = PostgresIt.app().bean(DbGrowthService.class);
        JdbcTemplate jdbc = PostgresIt.app().jdbc();
        jdbc.update("DELETE FROM db_size_daily");
        Instant now = Instant.now();
        int n = svc.writeSnapshot(now);
        assertThat(n).isGreaterThan(10);
        svc.writeSnapshot(now);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM db_size_daily", Integer.class)).isEqualTo(n);
        assertThat(svc.snapshotWritten(now)).isTrue();
        svc.invalidate();
        Map<String, Object> v = svc.overview(30, true);
        assertThat(v.get("supported")).isEqualTo(true);
        assertThat(v.get("history_days")).isEqualTo(1L);
        jdbc.update("DELETE FROM db_size_daily");
    }

    @Test
    @DisplayName("analiz PostgreSQL'de bütçe içinde biter ve saklanır")
    void analysisOnPostgres() {
        DbHealthAnalysisService svc = PostgresIt.app().bean(DbHealthAnalysisService.class);
        Map<String, Object> r = svc.run("it").orElseThrow();
        assertThat(r.get("status")).isIn("OK", "PARTIAL");
        assertThat(svc.latest()).isNotNull();
        PostgresIt.app().jdbc().update("DELETE FROM db_health_analysis_runs");
    }
}
