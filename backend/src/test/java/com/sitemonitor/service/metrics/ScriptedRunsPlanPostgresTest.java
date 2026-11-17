package com.sitemonitor.service.metrics;

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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grafana dışa aktarıcısının 24 saatlik sentetik koşum sayımı ({@link MonitoringMetricsExporter#SQL_SCRIPTED_RUNS}, her turda)
 * GERÇEK PostgreSQL'de kapsayan {@code idx_sc_checked_cover} dizininden YALNIZ-DİZİN taramasıyla okunur (performans 2026-11-17)
 * — {@code mvn -Ppostgres-it test}. Laboratuvar şeması: canlı dizinler + ertelenmiş katalog, 30 izleme × 30 gün × 10 dk.
 */
@PostgresIntegration
class ScriptedRunsPlanPostgresTest {

    private static final String LAB = "sc_plan_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TABLE = "scripted_checks";

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @BeforeAll
    static void lab() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
        String live = jdbc().queryForObject("SELECT current_schema()", String.class);
        jdbc().execute("CREATE SCHEMA " + LAB);
        jdbc().execute("CREATE TABLE " + LAB + "." + TABLE + " (LIKE " + live + "." + TABLE
                + " INCLUDING DEFAULTS INCLUDING IDENTITY)");
        for (String col : jdbc().queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = ? AND is_nullable = 'NO' AND column_name <> 'id'", String.class, LAB, TABLE)) {
            jdbc().execute("ALTER TABLE " + LAB + "." + TABLE + " ALTER COLUMN \"" + col + "\" DROP NOT NULL");
        }
        for (String def : jdbc().queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
                String.class, live, TABLE)) {
            if (def.contains("idx_sc_checked_cover")) continue;   // katalogdan aşağıda kurulur
            jdbc().execute(def.replace(" ON " + live + "." + TABLE + " ", " ON " + LAB + "." + TABLE + " "));
        }
        for (DeferredIndexBuilder.Spec s : DeferredIndexBuilder.CATALOG) {
            if (!s.table().equals(TABLE)) continue;
            jdbc().execute(s.postgresDdl().replaceFirst("(?i)\\s+CONCURRENTLY\\s+", " ")
                    .replace(" ON " + TABLE + "(", " ON " + LAB + "." + TABLE + "("));
        }
        String at = "to_char(timestamp '2026-10-01' + s * interval '600 second', 'YYYY-MM-DD\"T\"HH24:MI:SS')";
        jdbc().execute("INSERT INTO " + LAB + "." + TABLE + " (monitor_id, checked_at, ok, status, k6_version, manual, recheck)"
                + " SELECT m, " + at + ", (s + m) % 9 <> 0, CASE WHEN (s + m) % 9 = 0 THEN 'FAIL' ELSE 'PASS' END,"
                + " CASE WHEN m % 3 = 0 THEN 'v2.3.0' ELSE 'v2.4.0' END,"
                + " CASE WHEN (s + m) % 50 = 0 THEN true END, CASE WHEN (s + m) % 70 = 0 THEN true END"
                + " FROM generate_series(0, 4319) s CROSS JOIN generate_series(1, 30) m ORDER BY s, m");
        jdbc().execute("VACUUM (ANALYZE) " + LAB + "." + TABLE);
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

    @Test
    @DisplayName("PostgreSQL: 24 saatlik sentetik koşum sayımı kapsayan dizinden yalnız-dizin taramasıyla (tablo taranmaz)")
    void scriptedRunsIndexOnly() {
        String sql = MonitoringMetricsExporter.SQL_SCRIPTED_RUNS.replace("?", "'2026-10-30T00:00:00'");
        String plan = explain("EXPLAIN (ANALYZE, BUFFERS) " + sql);
        assertThat(plan).as("kapsayan dizin, yalnız-dizin:%n%s", plan).contains("Index Only Scan using idx_sc_checked_cover");
        assertThat(plan).as("tablo taranmaz:%n%s", plan).doesNotContain("Seq Scan on " + TABLE);
    }
}
