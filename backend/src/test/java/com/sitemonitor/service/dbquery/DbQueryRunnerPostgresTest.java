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
