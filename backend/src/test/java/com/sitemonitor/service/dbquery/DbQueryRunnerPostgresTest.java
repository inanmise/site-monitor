package com.sitemonitor.service.dbquery;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SsrfGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gerçek PostgreSQL'de çalıştırıcı: salt-okur oturum, güvenlik denetimini ATLATAN bir yazmayı (INSERT … RETURNING, nextval)
 * veritabanının kendisinin reddettiğini, {@code statement_timeout} / {@code lock_timeout}'un DB_TIMEOUT verdiğini, satır
 * sınırını, plan okumayı, {@code standard_conforming_strings} ayarını ve kimlik / ulaşılamazlık sınıflandırmasını kanıtlar.
 * Testin kendi nesneleri ayrı şemadadır ({@code dbq_it}) ve sonunda silinir.
 */
@PostgresIntegration
class DbQueryRunnerPostgresTest {

    private static PostgresIt.Config cfg;
    private static String host;
    private static int port;
    private static String db;
    private static DbQueryRunner runner;

    @BeforeAll
    static void setUp() throws Exception {
        cfg = PostgresIt.config();
        URI u = URI.create(cfg.url().substring("jdbc:".length()));
        host = u.getHost() == null || u.getHost().equals("localhost") ? "127.0.0.1" : u.getHost();
        port = u.getPort() < 0 ? 5432 : u.getPort();
        String path = u.getPath() == null ? "" : u.getPath();
        db = path.startsWith("/") ? path.substring(1) : path;
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(eq("site.monitor.monitoring.allow-internal-targets"), anyBoolean())).thenReturn(true);
        when(settings.getBoolean(eq("site.monitor.monitoring.allow-loopback-targets"), anyBoolean())).thenReturn(true);
        runner = new DbQueryRunner(new SsrfGuard(settings));
        try (Connection c = direct(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS dbq_it CASCADE");
            s.execute("CREATE SCHEMA dbq_it");
            s.execute("CREATE TABLE dbq_it.t (id int PRIMARY KEY, note text)");
            s.execute("INSERT INTO dbq_it.t SELECT g, 'not ' || g FROM generate_series(1, 50) g");
            s.execute("CREATE SEQUENCE dbq_it.s");
            s.execute("CREATE TABLE dbq_it.secret (note text)");
            s.execute("INSERT INTO dbq_it.secret VALUES ('TOPSECRET')");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (cfg == null) return;
        try (Connection c = direct(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS dbq_it CASCADE");
        }
    }

    private static Connection direct() throws Exception {
        return DriverManager.getConnection(cfg.url(), cfg.user(), cfg.password());
    }

    private static DbConnectionSpec spec() {
        return spec(cfg.user());
    }

    private static DbConnectionSpec spec(String user) {
        return new DbConnectionSpec(DbType.POSTGRESQL, host, port, db, null, null, user, cfg.password(), "disable",
                false, false, 5_000);
    }

    private static long count() throws Exception {
        try (Connection c = direct(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM dbq_it.t")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void readsRowsAndCapsAtMaxRows() {
        DbQueryRunner.QueryResult r = runner.run(spec(), "SELECT id, note FROM dbq_it.t ORDER BY id;", 10, 10);
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.rowCount()).isEqualTo(10);
        assertThat(r.rowCountCapped()).isTrue();
        assertThat(r.rows()).hasSize(10);
        assertThat(r.rows().get(0).get(0)).isEqualTo(java.math.BigDecimal.ONE);
        assertThat(r.rows().get(0).get(1)).isEqualTo("not 1");
        assertThat(r.columns()).extracting(DbQueryRunner.Column::name).containsExactly("id", "note");
        assertThat(r.serverVersion()).startsWith("PostgreSQL");
        DbQueryRunner.QueryResult all = runner.run(spec(), "SELECT id FROM dbq_it.t", 10, 1000);
        assertThat(all.rowCount()).isEqualTo(50);
        assertThat(all.rowCountCapped()).isFalse();
    }

    @Test
    void readOnlySessionRejectsWritesThatBypassTheGuard() throws Exception {
        long before = count();
        DbQueryRunner.QueryResult ins = runner.run(spec(), "INSERT INTO dbq_it.t VALUES (999, 'x') RETURNING id", 10, 10);
        assertThat(ins.ok()).isFalse();
        assertThat(ins.failureReason()).isEqualTo("DB_SQL_ERROR");
        assertThat(ins.error()).containsIgnoringCase("read-only");
        DbQueryRunner.QueryResult seq = runner.run(spec(), "SELECT nextval('dbq_it.s')", 10, 10);
        assertThat(seq.ok()).isFalse();
        assertThat(seq.error()).containsIgnoringCase("read-only");
        DbQueryRunner.QueryResult ddl = runner.run(spec(), "WITH x AS (DELETE FROM dbq_it.t RETURNING 1) SELECT count(*) FROM x", 10, 10);
        assertThat(ddl.ok()).isFalse();
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void statementTimeoutIsDbTimeout() {
        long t0 = System.nanoTime();
        DbQueryRunner.QueryResult r = runner.run(spec(), "SELECT pg_sleep(30)", 1, 10);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).isEqualTo("DB_TIMEOUT");
        assertThat(ms).isLessThan(10_000);
    }

    @Test
    void lockTimeoutIsDbTimeoutAndDoesNotWait() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), done = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (Connection c = direct()) {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    s.execute("LOCK TABLE dbq_it.t IN ACCESS EXCLUSIVE MODE");
                    locked.countDown();
                    done.await(30, TimeUnit.SECONDS);
                }
                c.rollback();
            } catch (Exception ignored) {
                locked.countDown();
            }
        });
        holder.start();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long t0 = System.nanoTime();
            DbQueryRunner.QueryResult r = runner.run(spec(), "SELECT count(*) FROM dbq_it.t", 20, 10);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.ok()).isFalse();
            assertThat(r.failureReason()).isEqualTo("DB_TIMEOUT");
            assertThat(ms).isLessThan(8_000);                       // lock_timeout 2 sn, sorgu zaman aşımı 20 sn değil
        } finally {
            done.countDown();
            holder.join(10_000);
        }
    }

    @Test
    void standardConformingStringsMatchTheGuard() {
        // Güvenlik denetimi düz '…' içinde ters bölüyü kaçış SAYMAZ; oturum da öyle okumalı
        DbQueryRunner.QueryResult r = runner.run(spec(), "SELECT '\\' AS b, E'\\'' AS q", 10, 10);
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.rows().get(0).get(0)).isEqualTo("\\");
        assertThat(r.rows().get(0).get(1)).isEqualTo("'");
        assertThat(DbQuerySqlGuard.check("SELECT '\\' AS b, E'\\'' AS q", DbType.POSTGRESQL).ok()).isTrue();
    }

    @Test
    void errorMessagesCarryNoQueryValues() {
        // Bağımsız inceleme Y1: değer hata iletisine gömülerek sızdırılamaz
        for (String sql : new String[] {
                "SELECT CAST((SELECT note FROM dbq_it.secret) AS integer)",
                "SELECT CAST((SELECT 'TOP' || 'SECRET') AS integer)",
                "SELECT current_setting((SELECT note FROM dbq_it.secret))",
                // tırnağı erken kapatıp metin enjekte etme denemesi
                "SELECT current_setting((SELECT 'x\" ' || note || ' \"y' FROM dbq_it.secret))",
                "SELECT to_date((SELECT note FROM dbq_it.secret), 'YYYY-MM-DD')",
                "SELECT (SELECT note FROM dbq_it.secret)::uuid"}) {
            assertThat(DbQuerySqlGuard.check(sql, DbType.POSTGRESQL).ok()).as(sql).isTrue();
            DbQueryRunner.QueryResult r = runner.run(spec(), sql, 10, 10);
            assertThat(r.ok()).as(sql).isFalse();
            assertThat(r.failureReason()).as(sql).isEqualTo("DB_SQL_ERROR");
            assertThat(r.error()).as(sql).doesNotContain("TOPSECRET").doesNotContain("SECRET").contains("SQLSTATE");
        }
        assertThat(runner.run(spec(), "SELECT CAST((SELECT note FROM dbq_it.secret) AS integer)", 10, 10).error())
                .contains("22P02").contains("integer");
        // kullanıcının yazdığı tanımlayıcı okunur kalır
        DbQueryRunner.QueryResult col = runner.run(spec(), "SELECT musteri_no FROM dbq_it.t", 10, 10);
        assertThat(col.error()).contains("column \"musteri_no\" does not exist").contains("42703");
        // plan aşamasında sabit katlama değeri değerlendirir: veri hatası sınıfında kendi sabiti de gösterilmez
        DbQueryRunner.PlanResult p = runner.plan(spec(), "SELECT CAST('TOPSECRET' AS integer)", 10);
        assertThat(p.ok()).isFalse();
        assertThat(p.error()).doesNotContain("TOPSECRET").contains("22P02");
    }

    @Test
    void recursiveCteEndsAtMaxRowsOrTimeout() {
        String rows = "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) SELECT n FROM r";
        assertThat(DbQuerySqlGuard.check(rows, DbType.POSTGRESQL).ok()).isTrue();
        long t0 = System.nanoTime();
        DbQueryRunner.QueryResult r = runner.run(spec(), rows, 2, 50);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(20_000);
        if (r.ok()) {
            assertThat(r.rowCount()).isEqualTo(50);
            assertThat(r.rowCountCapped()).isTrue();
        } else {
            assertThat(r.failureReason()).isEqualTo("DB_TIMEOUT");
        }
        // satır döndürmeyen sonsuz özyineleme: statement_timeout keser
        String agg = "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) SELECT count(*) FROM r";
        long t1 = System.nanoTime();
        DbQueryRunner.QueryResult a = runner.run(spec(), agg, 2, 50);
        assertThat((System.nanoTime() - t1) / 1_000_000).isLessThan(20_000);
        assertThat(a.ok()).isFalse();
        assertThat(a.failureReason()).isEqualTo("DB_TIMEOUT");
    }

    @Test
    void pinnedSocketFactoryWorksWithTheRealDriver() throws Exception {
        // Bağımsız inceleme O2: URL'de çözülemeyen bir ad olsa da gerçek sürücü kilitli IP'ye bağlanır
        java.util.Properties p = new java.util.Properties();
        p.setProperty("user", cfg.user());
        if (cfg.password() != null) p.setProperty("password", cfg.password());
        p.setProperty("sslmode", "disable");
        p.setProperty("connectTimeout", "5");
        p.setProperty("socketFactory", PinnedAddressSocketFactory.class.getName());
        p.setProperty(PinnedAddressSocketFactory.ARG, host);
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://pinned.invalid:" + port + "/" + db, p);
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 41 + 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(42);
        }
    }

    @Test
    void planConnectionTestAndFailures() {
        DbQueryRunner.PlanResult p = runner.plan(spec(), "SELECT * FROM dbq_it.t WHERE id > 5", 10);
        assertThat(p.supported()).isTrue();
        assertThat(p.ok()).as(p.error()).isTrue();
        assertThat(p.cost()).isPositive();
        assertThat(p.rows()).isNotNull();
        DbQueryRunner.ConnectionTest t = runner.test(spec());
        assertThat(t.ok()).as(t.error()).isTrue();
        assertThat(t.serverVersion()).startsWith("PostgreSQL");
        DbQueryRunner.ConnectionTest auth = runner.test(spec("dbq_no_such_role"));
        assertThat(auth.ok()).isFalse();
        assertThat(auth.failureReason()).isEqualTo("DB_AUTH");
        assertThat(auth.error()).doesNotContain("dbq_no_such_role");
        DbQueryRunner.QueryResult missing = runner.run(spec(), "SELECT * FROM dbq_it.yok", 10, 10);
        assertThat(missing.failureReason()).isEqualTo("DB_SQL_ERROR");
        DbConnectionSpec closed = new DbConnectionSpec(DbType.POSTGRESQL, host, 1, db, null, null, cfg.user(),
                cfg.password(), "disable", false, false, 1_000);
        assertThat(runner.test(closed).failureReason()).isEqualTo("DB_UNREACHABLE");
    }
}
