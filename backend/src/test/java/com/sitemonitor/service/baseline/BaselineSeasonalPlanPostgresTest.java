package com.sitemonitor.service.baseline;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mevsimsel taban okumasının planı GERÇEK PostgreSQL'de (performans 2026-11-17) — {@code mvn -Ppostgres-it test}. Laboratuvar
 * şemasında üretime benzer veri (20 izleme × 14 gün × dakikada bir örnek, zaman sırasıyla iç içe): aralık ifadesinin
 * ({@link BaselineSampleStore#seasonalRangesSql}) her dalı {@code idx_mbs_monitor}'un DİZİN KOŞULUDUR (eşitlik + checked_ms
 * aralığı) — tablo taranmaz, dairesel süzgeç yok; özel ve genel planda. Kıyas: eski ifade süzgeçli geriye taramadır.
 */
@PostgresIntegration
class BaselineSeasonalPlanPostgresTest {

    private static final String LAB = "mbs_plan_" + UUID.randomUUID().toString().substring(0, 8);
    private static final long T0 = 1_800_000_000_000L;
    private static final long DAY = BaselineSampleStore.DAY_MS, TOL = BaselineSampleStore.SEASON_TOLERANCE_MIN_MS;

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @BeforeAll
    static void lab() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
        String live = jdbc().queryForObject("SELECT current_schema()", String.class);
        String table = BaselineSampleStore.TABLE;
        jdbc().execute("CREATE SCHEMA " + LAB);
        jdbc().execute("CREATE TABLE " + LAB + "." + table + " (LIKE " + live + "." + table
                + " INCLUDING DEFAULTS INCLUDING IDENTITY)");
        for (String def : jdbc().queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
                String.class, live, table)) {
            jdbc().execute(def.replace(" ON " + live + "." + table + " ", " ON " + LAB + "." + table + " "));
        }
        jdbc().execute("INSERT INTO " + LAB + "." + table + " (monitor_type, monitor_id, checked_ms, value_ms, breach, created_ms)"
                + " SELECT 'http', m, " + (T0 - 14 * DAY) + " + s * 60000, 100 + (s % 37), (s % 41) = 0, 0"
                + " FROM generate_series(0, 20159) s CROSS JOIN generate_series(1, 20) m ORDER BY s, m");
        jdbc().execute("VACUUM (ANALYZE) " + LAB + "." + table);
    }

    @AfterAll
    static void dropLab() {
        jdbc().execute("DROP SCHEMA IF EXISTS " + LAB + " CASCADE");
    }

    private static String explain(String statement) {
        PostgresIt.Config cfg = PostgresIt.config();
        String url = cfg.url() + (cfg.url().contains("?") ? "&" : "?") + "preferQueryMode=simple";
        try (Connection c = DriverManager.getConnection(url, cfg.user(), cfg.password());
             Statement st = c.createStatement()) {
            st.execute("SET search_path TO " + LAB);
            StringBuilder plan = new StringBuilder();
            try (ResultSet rs = st.executeQuery(statement)) {
                while (rs.next()) plan.append(rs.getString(1)).append('\n');
            }
            return plan.toString();
        } catch (SQLException e) {
            throw new IllegalStateException("plan okunamadı: " + statement, e);
        }
    }

    private static String literal(String sql, List<Object> values) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch != '?') { out.append(ch); continue; }
            Object v = values.get(n++);
            out.append(v instanceof Number ? v.toString() : "'" + v.toString().replace("'", "''") + "'");
        }
        assertThat(n).isEqualTo(values.size());
        return out.toString();
    }

    private static String positional(String sql) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') out.append('$').append(++n); else out.append(ch);
        }
        return out.toString();
    }

    @Test
    @DisplayName("PostgreSQL: aralık okumasının her dalı idx_mbs_monitor dizin koşulu (özel + genel plan) — taramasız, süzgeçsiz")
    void rangeBranchesUseMonitorIndex() {
        long before = T0;
        long nowPos = Math.floorMod(before, DAY);
        long upper = before - DAY + TOL;
        long k = Math.floorDiv(upper + TOL - nowPos, DAY);
        int ranges = BaselineSampleStore.SEASON_BATCH_FIRST;
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < ranges; i++, k--) {
            long target = nowPos + k * DAY;
            values.addAll(List.of("http", 7L, target - TOL, Math.min(target + TOL, upper), 30));
        }
        values.add(30);
        String sql = BaselineSampleStore.seasonalRangesSql(ranges);
        String custom = explain("EXPLAIN (ANALYZE, BUFFERS) " + literal(sql, values));
        String generic = explain("EXPLAIN (GENERIC_PLAN) " + positional(sql));
        for (String plan : List.of(custom, generic)) {
            assertThat(plan).as("tablo taranmaz:%n%s", plan).doesNotContain("Seq Scan on " + BaselineSampleStore.TABLE);
            assertThat(plan).as("dal dizin koşulu:%n%s", plan).contains("idx_mbs_monitor")
                    .containsPattern("Index Cond:[^\\n]*checked_ms >=");
            assertThat(plan).as("dairesel süzgeç yok:%n%s", plan).doesNotContainPattern("(?i)Filter:[^\\n]*mod\\(");
        }
        assertThat(custom).as("dizin koşulunun dışında satır elenmez:%n%s", custom).doesNotContain("Rows Removed by Filter");
        // Kıyas (bilgi): eski dairesel ifade süzgeçle geriye tarar
        String old = "SELECT value_ms, breach FROM " + BaselineSampleStore.TABLE + " WHERE monitor_type = 'http'"
                + " AND monitor_id = 7 AND checked_ms <= " + upper + " AND LEAST(ABS(MOD(checked_ms, " + DAY + ") - " + nowPos
                + "), " + DAY + " - ABS(MOD(checked_ms, " + DAY + ") - " + nowPos + ")) <= " + TOL
                + " ORDER BY checked_ms DESC, id DESC LIMIT 30";
        assertThat(explain("EXPLAIN (ANALYZE) " + old)).contains("Rows Removed by Filter");
    }

    @Test
    @DisplayName("PostgreSQL: aralık okuması eski dairesel ifadeyle aynı satırları döner (gerçek veri, günlük + haftalık)")
    void sameRowsOnPostgres() {
        BaselineSampleStore store = PostgresIt.app().bean(BaselineSampleStore.class);
        long id = 900_000_000L + (long) (Math.random() * 1_000_000);
        try {
            for (int d = 0; d < 10; d++) {
                for (int m = 0; m < 90; m += 5) store.append(BaselineTypes.HTTP, id, T0 - d * DAY - m * 60_000L, d * 100 + m, m % 2 == 0);
            }
            for (long period : new long[] { DAY, 7 * DAY }) {
                long before = T0 + 20 * 60_000L;
                long nowPos = Math.floorMod(before, period);
                long upper = Math.min(before - 1, before - period + TOL);
                List<String> oracle = jdbc().query("SELECT value_ms, breach FROM " + BaselineSampleStore.TABLE
                                + " WHERE monitor_type = ? AND monitor_id = ? AND checked_ms <= ? AND LEAST(ABS(MOD(checked_ms, ?) - ?),"
                                + " ? - ABS(MOD(checked_ms, ?) - ?)) <= ? ORDER BY checked_ms DESC, id DESC LIMIT ?",
                        (rs, n) -> rs.getLong(1) + (rs.getBoolean(2) ? "!" : ""),
                        BaselineTypes.HTTP, id, upper, period, nowPos, period, period, nowPos, TOL, 50);
                List<String> got = new ArrayList<>();
                for (BaselineMath.Sample s : store.readSeasonal(BaselineTypes.HTTP, id, before, 50, period, nowPos, TOL).samples()) {
                    got.add((long) s.value() + (s.breach() ? "!" : ""));
                }
                assertThat(got).as("periyot %d", period).isEqualTo(oracle).isNotEmpty();
            }
        } finally {
            store.deleteForMonitor(BaselineTypes.HTTP, id);
        }
    }
}
