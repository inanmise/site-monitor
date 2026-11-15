package com.sitemonitor.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kural geri testi sayfa sorgusunun planı GERÇEK PostgreSQL'de (kapı incelemesi 2026-11-15) — {@code mvn -Ppostgres-it test}.
 * {@code DbQueryCheckRepository.backtestPageDesc} en yeniden geriye sayfalanır (üst sınır DÜZ {@code checked_at <= :upper};
 * {@code MonitorIndexSort.UNTIL_*}'ın VEYA biçimi genel planda üst sınırı süzgece çevirir ve anahtar kümeli sayfalamayı
 * O(n²) yapardı). Kanıtlanan: Hibernate'in ÜRETTİĞİ SQL hem gerçek değerli özel planda hem {@code EXPLAIN (GENERIC_PLAN)}
 * genel planında bileşik {@code idx_dbqc_monitor_checked} dizininden, {@code monitor_id} süzgeçsiz, sıralamasız okunur —
 * üretime benzer bir LABORATUVAR şemasında (zaman sırasıyla iç içe 18 izleme × 6000 kontrol; tekil {@code checked_at}
 * dizini de var). Şema sonda düşürülür.
 */
@PostgresIntegration
class DbQueryBacktestPostgresTest {

    private static final String LAB = "dbq_bt_" + UUID.randomUUID().toString().substring(0, 8);
    private static final long MONITOR = 907L;
    private static final String FROM = "2026-09-01T00:00:00";
    private static final String UPPER = "2026-09-15T00:00:00";
    private static final Pattern MONITOR_FILTER = Pattern.compile("Filter:[^\\n]*\\bmonitor_id\\b");

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @BeforeAll
    static void lab() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
        String live = jdbc().queryForObject("SELECT current_schema()", String.class);
        jdbc().execute("CREATE SCHEMA " + LAB);
        String table = "dbquery_checks";
        jdbc().execute("CREATE TABLE " + LAB + "." + table + " (LIKE " + live + "." + table
                + " INCLUDING DEFAULTS INCLUDING IDENTITY)");
        for (String col : jdbc().queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = ? AND is_nullable = 'NO' AND column_name <> 'id'", String.class, LAB, table)) {
            jdbc().execute("ALTER TABLE " + LAB + "." + table + " ALTER COLUMN \"" + col + "\" DROP NOT NULL");
        }
        for (String def : jdbc().queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
                String.class, live, table)) {
            jdbc().execute(def.replace(" ON " + live + "." + table + " ", " ON " + LAB + "." + table + " "));
        }
        for (DeferredIndexBuilder.Spec s : DeferredIndexBuilder.CATALOG) {
            if (!s.table().equals(table)) continue;
            jdbc().execute(s.postgresDdl().replaceFirst("(?i)\\s+CONCURRENTLY\\s+", " ")
                    .replace(" ON " + table + "(", " ON " + LAB + "." + table + "("));
        }
        String at = "to_char(timestamp '2026-08-01' + s * interval '600 second', 'YYYY-MM-DD\"T\"HH24:MI:SS')";
        jdbc().execute("INSERT INTO " + LAB + ".dbquery_checks (monitor_id, checked_at, ok, status, sql_hash, rule_results_json,"
                + " manual, recheck) SELECT m, " + at + ", true, CASE WHEN (s + m) % 41 = 0 THEN 'BREACH' ELSE 'OK' END,"
                + " 'h', '[]', CASE WHEN s % 97 = 0 THEN true END, NULL"
                + " FROM generate_series(0, 5999) s CROSS JOIN generate_series(901, 918) m ORDER BY s, m");
        jdbc().execute("VACUUM (ANALYZE) " + LAB + ".dbquery_checks");
    }

    @AfterAll
    static void dropLab() {
        jdbc().execute("DROP SCHEMA IF EXISTS " + LAB + " CASCADE");
    }

    /** Çağrının BU iş parçacığında Hibernate'e ürettirdiği SQL'ler ({@code org.hibernate.SQL} DEBUG). */
    private static List<String> sqlOf(Runnable call) {
        Logger sql = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level prev = sql.getLevel();
        boolean additive = sql.isAdditive();
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        sql.addAppender(app);
        sql.setAdditive(false);
        sql.setLevel(Level.DEBUG);
        String thread = Thread.currentThread().getName();
        try {
            call.run();
        } finally {
            sql.setLevel(prev);
            sql.setAdditive(additive);
            sql.detachAppender(app);
        }
        return app.list.stream().filter(e -> thread.equals(e.getThreadName())).map(ILoggingEvent::getFormattedMessage).toList();
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

    private static String genericPlan(String sql) {
        StringBuilder positional = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') positional.append('$').append(++n); else positional.append(ch);
        }
        return explain("EXPLAIN (GENERIC_PLAN) " + positional);
    }

    private static String customPlan(String sql, Object... values) {
        StringBuilder literal = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch != '?') { literal.append(ch); continue; }
            if (n >= values.length) throw new AssertionError("değer eksik (" + values.length + "): " + sql);
            Object v = values[n++];
            literal.append(v instanceof Number ? v.toString() : "'" + v.toString().replace("'", "''") + "'");
        }
        if (n != values.length) throw new AssertionError("fazla değer (" + values.length + " / " + n + "): " + sql);
        return explain("EXPLAIN " + literal);
    }

    private static void assertKeyed(String what, String plan) {
        assertThat(plan).as("%s — monitor_id süzgeçle elenmemeli:%n%s", what, plan).doesNotContainPattern(MONITOR_FILTER);
        assertThat(plan).as("%s — tablo taranmamalı:%n%s", what, plan).doesNotContain("Seq Scan on dbquery_checks");
        assertThat(plan).as("%s — bileşik dizin:%n%s", what, plan).contains("idx_dbqc_monitor_checked");
        assertThat(plan).as("%s — sıra dizinden gelir (sıralama yok):%n%s", what, plan).doesNotContain("Sort Key");
        assertThat(plan).as("%s — üst sınır süzgeç değil, dizin koşulu:%n%s", what, plan)
                .doesNotContainPattern("Filter:[^\\n]*checked_at");
    }

    @Test
    @DisplayName("PostgreSQL: geri test sayfa sorgusu özel ve genel planda bileşik dizinden, süzgeçsiz ve sıralamasız okunur")
    void pagePlanUsesMonitorIndex() {
        DbQueryCheckRepository repo = PostgresIt.app().bean(DbQueryCheckRepository.class);
        List<String> sqls = sqlOf(() -> repo.backtestPageDesc(MONITOR, FROM, UPPER, 500));
        String sql = sqls.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains("dbquery_checks")).findFirst()
                .orElseThrow(() -> new AssertionError("SQL yakalanmadı: " + sqls));
        long marks = sql.chars().filter(c -> c == '?').count();
        Object[] values = marks == 5 ? new Object[]{ MONITOR, MONITOR, FROM, UPPER, 500 }
                : new Object[]{ MONITOR, MONITOR, FROM, UPPER };   // LIMIT değişmez olarak gömülürse
        assertKeyed("özel plan", customPlan(sql, values));
        assertKeyed("genel plan", genericPlan(sql));
        // Gerçek sonuç: üst sınır dahil, en yeni önce, elle / yeniden denetim satırları yok
        List<Object[]> rows = repo.backtestPageDesc(MONITOR, FROM, UPPER, 5);
        assertThat(rows).as("canlı şemada bu izlemenin satırı yok").isEmpty();
    }
}
