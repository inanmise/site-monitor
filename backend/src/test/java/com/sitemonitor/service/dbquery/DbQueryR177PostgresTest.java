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
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * r177 gerçek PostgreSQL'de: hesap yetki denetimi (salt-okur hesap → bulgu yok; tabloya yazabilen hesap → CAN_WRITE_TABLES;
 * süper kullanıcı → SUPERUSER), denetimin salt-okur işlemi bozmaması (kayıt noktaları — ardından kullanıcının sorgusu
 * çalışır), BLOCK kipinde yazabilen hesapla sorgunun hiç çalışmaması, veritabanı saati + tazelik yaşı (saat dilimli ve
 * dilimsiz kolon), DBA izi (application_name + sorgu yorumu). Testin nesneleri {@code dbq_r177} şemasında, roller sonunda
 * silinir.
 */
@PostgresIntegration
class DbQueryR177PostgresTest {

    private static final String RO = "dbq_r177_ro", RW = "dbq_r177_rw";
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
            dropAll(s);
            s.execute("CREATE SCHEMA dbq_r177");
            s.execute("CREATE TABLE dbq_r177.olay (id int PRIMARY KEY, ts timestamptz NOT NULL, ts_yerel timestamp NOT NULL)");
            s.execute("INSERT INTO dbq_r177.olay VALUES (1, now() - interval '10 minutes', localtimestamp - interval '10 minutes'), "
                    + "(2, now() - interval '3 hours', localtimestamp - interval '3 hours')");
            // Kendi parolalarıyla: CI'daki PostgreSQL parola denetler (yerel küme trust kipindeydi)
            s.execute("CREATE ROLE " + RO + " LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
            s.execute("CREATE ROLE " + RW + " LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
            for (String r : List.of(RO, RW)) {
                s.execute("GRANT CONNECT ON DATABASE \"" + db + "\" TO " + r);
                s.execute("GRANT USAGE ON SCHEMA dbq_r177 TO " + r);
                s.execute("GRANT SELECT ON dbq_r177.olay TO " + r);
            }
            s.execute("GRANT INSERT ON dbq_r177.olay TO " + RW);
        }
    }

    private static void dropAll(Statement s) throws Exception {
        s.execute("DROP SCHEMA IF EXISTS dbq_r177 CASCADE");
        for (String r : List.of(RO, RW)) {
            s.execute("DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + r + "') THEN "
                    + "EXECUTE 'DROP OWNED BY " + r + "'; EXECUTE 'DROP ROLE " + r + "'; END IF; END $$");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (cfg == null) return;
        try (Connection c = direct(); Statement s = c.createStatement()) {
            dropAll(s);
        }
    }

    private static Connection direct() throws Exception {
        return DriverManager.getConnection(cfg.url(), cfg.user(), cfg.password());
    }

    /** Test rollerinin parolası (yalnız bu geçici rollerde; yönetici hesabı kendi parolasıyla bağlanır). */
    private static final String ROLE_PASSWORD = "dbq-r177-it";

    private static DbConnectionSpec spec(String user) {
        String password = RO.equals(user) || RW.equals(user) ? ROLE_PASSWORD : cfg.password();
        return new DbConnectionSpec(DbType.POSTGRESQL, host, port, db, null, null, user, password, "disable",
                false, false, 5_000);
    }

    @Test
    void readOnlyAccountHasNoFindings() {
        DbQueryRunner.ConnectionTest t = runner.test(spec(RO), true);
        assertThat(t.ok()).isTrue();
        assertThat(t.privileges().checked()).isTrue();
        assertThat(t.privileges().readOnly()).isTrue();
        assertThat(t.privileges().findings()).isEmpty();
        // denetim istenmediyse yapılmaz
        assertThat(runner.test(spec(RO), false).privileges().checked()).isFalse();
    }

    @Test
    void writableAndSuperuserAccountsAreNamed() {
        DbAccountPrivileges.Result rw = runner.test(spec(RW), true).privileges();
        assertThat(rw.readOnly()).isFalse();
        assertThat(rw.findings()).extracting(DbAccountPrivileges.Finding::code).contains(DbAccountPrivileges.CAN_WRITE_TABLES);
        assertThat(rw.findings()).extracting(DbAccountPrivileges.Finding::detail).contains("dbq_r177.olay");
        DbAccountPrivileges.Result su = runner.test(spec(cfg.user()), true).privileges();
        assertThat(su.readOnly()).isFalse();
        assertThat(su.findings()).extracting(DbAccountPrivileges.Finding::code).contains(DbAccountPrivileges.SUPERUSER);
    }

    @Test
    void privilegeCheckKeepsTheReadOnlySessionUsable() {
        // salt-okur hesap: denetim + kullanıcının sorgusu aynı oturumda — işlem bozulmaz
        DbQueryRunner.QueryResult ok = runner.run(spec(RO), "SELECT count(*) AS n FROM dbq_r177.olay", 10, 10,
                new DbQueryRunner.RunOptions(Set.of(), true, true));
        assertThat(ok.ok()).as(String.valueOf(ok.error())).isTrue();
        assertThat(ok.privileges().readOnly()).isTrue();
        assertThat(ok.dbClock()).isNotNull();
        // yazabilen hesap BLOCK kipinde: sorgu ÇALIŞMAZ
        DbQueryRunner.QueryResult refused = runner.run(spec(RW), "SELECT count(*) AS n FROM dbq_r177.olay", 10, 10,
                new DbQueryRunner.RunOptions(Set.of(), false, true));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.failureReason()).isEqualTo(DbQueryErrors.DB_ACCOUNT_WRITABLE);
        assertThat(refused.rows()).isEmpty();
    }

    @Test
    void databaseClockAndFreshnessAgeOnRealColumns() {
        DbQueryRunner.QueryResult r = runner.run(spec(RO), "SELECT max(ts) AS ts, max(ts_yerel) AS ts_yerel FROM dbq_r177.olay",
                10, 10, new DbQueryRunner.RunOptions(Set.of(), true, false));
        assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
        assertThat(Duration.between(r.dbClock().now(), Instant.now()).abs().getSeconds()).isLessThan(30);
        List<DbQueryRules.Rule> rules = DbQueryRules.fromApi(List.of(
                Map.of("id", "a", "kind", "FRESHNESS", "column", "ts", "operator", "GT", "warning", 3600),
                Map.of("id", "b", "kind", "FRESHNESS", "column", "ts_yerel", "operator", "GT", "warning", 3600))).rules();
        DbQueryEvaluator.Outcome o = DbQueryEvaluator.evaluate(rules, "ANY", r, DbQueryEvaluator.Baselines.NONE, "tr");
        for (DbQueryEvaluator.RuleResult rr : o.results()) {
            assertThat(rr.evaluated()).as(rr.id() + " " + rr.note()).isTrue();
            assertThat(rr.measure()).as(rr.id()).isBetween(570.0, 660.0);   // ≈ 10 dk
        }
        assertThat(o.status()).isEqualTo("OK");
    }

    @Test
    void dbaCanSeeWhichMonitorRanTheQuery() {
        DbQueryRunner.QueryResult r = runner.run(spec(RO).withAppName("SiteMonitor dbq#77"),
                "SELECT current_setting('application_name') AS app, query AS q FROM pg_stat_activity WHERE pid = pg_backend_pid()",
                10, 10);
        assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
        assertThat(r.rows().get(0).get(0)).isEqualTo("SiteMonitor dbq#77");
        assertThat(String.valueOf(r.rows().get(0).get(1))).startsWith("/* SiteMonitor dbq#77 */ SELECT");
    }
}
