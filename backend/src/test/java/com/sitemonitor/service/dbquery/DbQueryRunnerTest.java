package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.SsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JDBC çalıştırıcı (sahte sürücüyle): URL alanlardan kurulur, salt-okur oturum ifadeleri, ifade sınırları (kaçış işleme kapalı,
 * zaman aşımı, satır sınırı), hücre normalleştirme, maskeleme, bekçi, her durumda rollback + kapat, hata sınıflandırma.
 */
class DbQueryRunnerTest {

    private SsrfGuard ssrf;
    private Connection conn;
    private Statement setup;
    private Statement query;
    private final AtomicReference<String> url = new AtomicReference<>();
    private final AtomicReference<Properties> props = new AtomicReference<>();
    private DbQueryRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        ssrf = mock(SsrfGuard.class);
        when(ssrf.validate("db.example")).thenReturn(List.of(InetAddress.getByName("10.0.0.5")));
        conn = mock(Connection.class);
        setup = mock(Statement.class);
        query = mock(Statement.class);
        when(conn.createStatement()).thenReturn(setup);
        when(conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)).thenReturn(query);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getDatabaseProductVersion()).thenReturn("16.4");
        when(conn.getMetaData()).thenReturn(md);
        runner = new DbQueryRunner(ssrf, (u, p) -> {
            url.set(u);
            props.set(p);
            return conn;
        });
    }

    private static DbConnectionSpec spec(DbType type, String db) {
        return new DbConnectionSpec(type, "db.example", type.defaultPort(), db, "SERVICE", null, "appuser",
                "S3cr3t!pw", null, false, false, 5_000);
    }

    private void returns(FakeJdbc.Rs rs) throws SQLException {
        when(query.executeQuery(anyString())).thenReturn(rs.resultSet());
    }

    @Test
    void postgresUrlPropsAndReadOnlySession() throws Exception {
        returns(FakeJdbc.resultSet(List.of(new FakeJdbc.Col("n", Types.INTEGER, 10)), List.<Object[]>of(new Object[] {7})));
        DbQueryRunner.QueryResult r = runner.run(spec(DbType.POSTGRESQL, "app"), "SELECT 7 AS n;", 30, 100);
        assertThat(r.ok()).isTrue();
        assertThat(url.get()).isEqualTo("jdbc:postgresql://10.0.0.5:5432/app");
        Properties p = props.get();
        assertThat(p.getProperty("user")).isEqualTo("appuser");
        assertThat(p.getProperty("readOnly")).isEqualTo("true");
        assertThat(p.getProperty("socketTimeout")).isEqualTo("40");
        assertThat(p.getProperty("connectTimeout")).isEqualTo("5");
        assertThat(p.getProperty("ApplicationName")).isEqualTo("SiteMonitor");
        assertThat(p.getProperty("sslmode")).isEqualTo("prefer");
        InOrder o = inOrder(conn, setup);
        o.verify(conn).setReadOnly(true);
        o.verify(conn).setAutoCommit(false);
        o.verify(setup).execute("SET TRANSACTION READ ONLY");
        o.verify(setup).execute("SET LOCAL statement_timeout = 30000");
        o.verify(setup).execute("SET LOCAL lock_timeout = '2s'");
        o.verify(setup).execute("SET LOCAL standard_conforming_strings = on");
        verify(query).setEscapeProcessing(false);
        verify(query).setQueryTimeout(30);
        verify(query).setMaxRows(101);
        verify(query).setFetchSize(101);
        verify(query).executeQuery("SELECT 7 AS n");          // sondaki ';' silinir
        verify(conn).rollback();
        verify(conn).close();
        assertThat(r.rows().get(0).get(0)).isEqualTo(BigDecimal.valueOf(7));
        assertThat(r.serverVersion()).isEqualTo("PostgreSQL 16.4");
    }

    @Test
    void mssqlAndOracleSessionStatements() throws Exception {
        returns(FakeJdbc.resultSet(List.of(new FakeJdbc.Col("n", Types.INTEGER, 10)), List.of()));
        DbQueryRunner.QueryResult r = runner.run(spec(DbType.MSSQL, "app"), "SELECT 1 FROM t WITH (NOLOCK)", 10, 5);
        assertThat(r.ok()).isTrue();
        assertThat(url.get()).isEqualTo("jdbc:sqlserver://");
        assertThat(props.get().getProperty("serverName")).isEqualTo("10.0.0.5");
        assertThat(props.get().getProperty("databaseName")).isEqualTo("app");
        assertThat(props.get().getProperty("applicationIntent")).isEqualTo("ReadOnly");
        assertThat(props.get().getProperty("socketTimeout")).isEqualTo("20000");
        verify(conn).setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        verify(setup).execute("SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED");
        verify(setup).execute("SET LOCK_TIMEOUT 2000");
        verify(setup).execute("SET DEADLOCK_PRIORITY LOW");

        Connection oc = mock(Connection.class);
        Statement os = mock(Statement.class), oq = mock(Statement.class);
        when(oc.createStatement()).thenReturn(os);
        when(oc.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)).thenReturn(oq);
        when(oq.executeQuery(anyString())).thenReturn(FakeJdbc.resultSet(List.of(new FakeJdbc.Col("n", Types.INTEGER, 1)),
                List.of()).resultSet());
        DbQueryRunner ora = new DbQueryRunner(ssrf, (u, p) -> { url.set(u); return oc; });
        assertThat(ora.run(spec(DbType.ORACLE, "ORCLPDB1"), "SELECT 1 FROM dual;", 10, 5).ok()).isTrue();
        assertThat(url.get()).isEqualTo("jdbc:oracle:thin:@(DESCRIPTION=(CONNECT_TIMEOUT=5)(ADDRESS=(PROTOCOL=TCP)"
                + "(HOST=10.0.0.5)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=ORCLPDB1)))");
        verify(oc, never()).setReadOnly(true);                   // Oracle'da yalnız açık ifade (ORA-01453)
        verify(os).execute("SET TRANSACTION READ ONLY");
        verify(oq).executeQuery("SELECT 1 FROM dual");
    }

    @Test
    void urlBuilderRejectsInjectionAndUsesHostNameForTls() throws Exception {
        InetAddress ip = InetAddress.getByName("10.0.0.5");
        for (String bad : new String[] {"app;user=sa", "app?x=1", "a b", "app&x", "x)(HOST=evil", "", "a{b}"}) {
            DbConnectionSpec s = new DbConnectionSpec(DbType.POSTGRESQL, "h", 5432, bad, null, null, "u", "p", null,
                    false, false, 1_000);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DbJdbcUrls.build(s, ip, 10),
                    bad);
        }
        DbConnectionSpec badInstance = new DbConnectionSpec(DbType.MSSQL, "h", 1433, "app", null, "SQL;EXPRESS", "u", "p",
                null, false, false, 1_000);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DbJdbcUrls.build(badInstance, ip, 10));
        DbConnectionSpec vf = new DbConnectionSpec(DbType.POSTGRESQL, "pg.example", 5432, "app", null, null, "u", "p",
                "verify-full", false, false, 1_000);
        assertThat(DbJdbcUrls.build(vf, ip, 10).url()).isEqualTo("jdbc:postgresql://pg.example:5432/app");
        DbConnectionSpec ms = new DbConnectionSpec(DbType.MSSQL, "sql.example", 1433, "app", null, "SQLEXPRESS", "u", "p",
                null, true, false, 1_000);
        Properties p = DbJdbcUrls.build(ms, ip, 10).props();
        assertThat(p.getProperty("hostNameInCertificate")).isEqualTo("sql.example");
        assertThat(p.getProperty("instanceName")).isEqualTo("SQLEXPRESS");
        assertThat(p.getProperty("encrypt")).isEqualTo("true");
        DbConnectionSpec sid = new DbConnectionSpec(DbType.ORACLE, "o", 1521, "XE", "SID", null, "u", "p", null, false,
                false, 1_000);
        assertThat(DbJdbcUrls.build(sid, InetAddress.getByName("::1"), 10).url()).contains("(SID=XE)").contains("(HOST=0:0:0:0:0:0:0:1)");
        assertThat(DbJdbcUrls.hostLiteral(InetAddress.getByName("::1"))).isEqualTo("[0:0:0:0:0:0:0:1]");
        assertThat(DbJdbcUrls.build(vf, ip, 10).toString()).doesNotContain("p\"").doesNotContain("password");
        // geçersiz ad → bağlanılmaz, DB_DRIVER
        DbQueryRunner.QueryResult r = runner.run(spec(DbType.POSTGRESQL, "app;x=1"), "SELECT 1", 10, 5);
        assertThat(r.failureReason()).isEqualTo("DB_DRIVER");
        assertThat(url.get()).isNull();
    }

    @Test
    void ssrfDecisions() throws Exception {
        when(ssrf.validate("meta")).thenThrow(new SsrfGuard.BlockedException("izin verilmeyen hedef"));
        when(ssrf.validate("nohost")).thenThrow(new SsrfGuard.UnresolvableHostException(SsrfGuard.UNRESOLVABLE_PREFIX + "nohost"));
        DbConnectionSpec meta = new DbConnectionSpec(DbType.POSTGRESQL, "meta", 5432, "app", null, null, "u", "p", null,
                false, false, 1_000);
        DbConnectionSpec nohost = new DbConnectionSpec(DbType.POSTGRESQL, "nohost", 5432, "app", null, null, "u", "p", null,
                false, false, 1_000);
        assertThat(runner.run(meta, "SELECT 1", 10, 5).failureReason()).isEqualTo("DB_BLOCKED");
        assertThat(runner.run(nohost, "SELECT 1", 10, 5).failureReason()).isEqualTo("DB_DNS");
        assertThat(runner.test(nohost).failureReason()).isEqualTo("DB_DNS");
        assertThat(url.get()).isNull();
    }

    @Test
    void readsCapsAndNormalizesCells() throws Exception {
        List<FakeJdbc.Col> cols = List.of(new FakeJdbc.Col("id", Types.INTEGER, 10),
                new FakeJdbc.Col("ad", Types.VARCHAR, 50), new FakeJdbc.Col("aktif", Types.BOOLEAN, 1),
                new FakeJdbc.Col("ts", Types.TIMESTAMP, 26), new FakeJdbc.Col("bin", Types.VARBINARY, 16),
                new FakeJdbc.Col("not", Types.CLOB, 0), new FakeJdbc.Col("kart", Types.VARCHAR, 20));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            rows.add(new Object[] {i, "satır" + i, i % 2 == 0, Timestamp.valueOf("2026-11-02 10:00:0" + i),
                    new byte[] {1, 2, 3, 4}, i == 0 ? "x".repeat(5_000) : null, "4111-1111-" + i});
        FakeJdbc.Rs rs = FakeJdbc.resultSet(cols, rows);
        returns(rs);
        DbQueryRunner.QueryResult r = runner.run(spec(DbType.POSTGRESQL, "app"), "SELECT *", 10, 3, Set.of("KART"));
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.rowCountCapped()).isTrue();
        assertThat(r.valuesTruncated()).isTrue();                 // 5000 karakterlik CLOB kısaltıldı
        assertThat(r.columns()).extracting(DbQueryRunner.Column::name)
                .containsExactly("id", "ad", "aktif", "ts", "bin", "not", "kart");
        List<Object> first = r.rows().get(0);
        assertThat(first.get(0)).isEqualTo(BigDecimal.valueOf(0));
        assertThat(first.get(1)).isEqualTo("satır0");
        assertThat(first.get(2)).isEqualTo(true);
        assertThat(first.get(3)).isEqualTo("2026-11-02T10:00");
        assertThat(first.get(4)).isEqualTo("<binary 4 bytes>");
        assertThat(((String) first.get(5))).hasSize(DbQueryRunner.MAX_CELL_CHARS).endsWith("…");
        assertThat(first.get(6)).isNull();                       // atılan kolon hiç okunmaz
        assertThat(rs.readColumns()).doesNotContain(7);
        assertThat(r.rows().get(1).get(5)).isNull();
    }

    @Test
    void sqlErrorIsMaskedAndConnectionAlwaysClosed() throws Exception {
        when(query.executeQuery(anyString())).thenThrow(new SQLException(
                "ERROR: permission denied for user appuser\n  connection password=S3cr3t!pw;", "42501"));
        DbQueryRunner.QueryResult r = runner.run(spec(DbType.POSTGRESQL, "app"), "SELECT 1", 10, 5);
        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).isEqualTo("DB_SQL_ERROR");
        assertThat(r.error()).doesNotContain("S3cr3t!pw").doesNotContain("appuser").doesNotContain("\n")
                .contains("permission denied");
        verify(conn).rollback();
        verify(conn).close();
    }

    @Test
    void sessionSetupFailureClosesConnection() throws Exception {
        when(setup.execute("SET TRANSACTION READ ONLY")).thenThrow(new SQLException("boom", "XX000"));
        DbQueryRunner.QueryResult r = runner.run(spec(DbType.POSTGRESQL, "app"), "SELECT 1", 10, 5);
        assertThat(r.ok()).isFalse();
        verify(query, never()).executeQuery(anyString());
        verify(conn).close();
    }

    @Test
    void watchdogCancelsHungQuery() throws Exception {
        runner.setWatchdogGraceForTests(150, 5_000);
        CountDownLatch cancelled = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> { cancelled.countDown(); return null; }).when(query).cancel();
        when(query.executeQuery(anyString())).thenAnswer(inv -> {
            if (!cancelled.await(5, TimeUnit.SECONDS)) throw new AssertionError("bekçi iptal etmedi");
            throw new SQLException("canceling statement due to user request", "57014");
        });
        long t0 = System.nanoTime();
        DbQueryRunner.QueryResult r = runner.run(spec(DbType.POSTGRESQL, "app"), "SELECT pg_sleep(100)", 1, 5);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.failureReason()).isEqualTo("DB_TIMEOUT");
        assertThat(r.error()).contains("süre sınırını");
        assertThat(ms).isLessThan(4_000);
        verify(query, times(1)).cancel();
        verify(conn).close();
    }

    @Test
    void connectionTestAndPlans() throws Exception {
        when(setup.executeQuery("SELECT 1")).thenReturn(FakeJdbc.resultSet(List.of(new FakeJdbc.Col("a", Types.INTEGER, 1)),
                List.<Object[]>of(new Object[] {1})).resultSet());
        DbQueryRunner.ConnectionTest t = runner.test(spec(DbType.POSTGRESQL, "app"));
        assertThat(t.ok()).isTrue();
        assertThat(t.serverVersion()).isEqualTo("PostgreSQL 16.4");

        DbQueryRunner.PlanResult pg = DbQueryRunner.parsePgPlan(
                "[{\"Plan\": {\"Node Type\": \"Seq Scan\", \"Total Cost\": 1234.5, \"Plan Rows\": 99}}]");
        assertThat(pg.ok()).isTrue();
        assertThat(pg.cost()).isEqualTo(1234.5);
        assertThat(pg.rows()).isEqualTo(99.0);
        DbQueryRunner.PlanResult ms = DbQueryRunner.parseShowplan("<ShowPlanXML><StmtSimple StatementSubTreeCost=\"0.5\" "
                + "StatementEstRows=\"10\"/><StmtSimple StatementSubTreeCost=\"12.75\" StatementEstRows=\"3\"/></ShowPlanXML>");
        assertThat(ms.cost()).isEqualTo(12.75);
        assertThat(ms.rows()).isEqualTo(10.0);
        assertThat(DbQueryRunner.parsePgPlan("bozuk").ok()).isFalse();
        assertThat(runner.plan(spec(DbType.ORACLE, "X"), "SELECT 1 FROM dual", 10).supported()).isFalse();

        when(setup.executeQuery("EXPLAIN (FORMAT JSON) SELECT 1"))
                .thenReturn(FakeJdbc.resultSet(List.of(new FakeJdbc.Col("QUERY PLAN", Types.VARCHAR, 100)),
                        List.<Object[]>of(new Object[] {"[{\"Plan\":{\"Total Cost\":3.5,\"Plan Rows\":1}}]"})).resultSet());
        DbQueryRunner.PlanResult live = runner.plan(spec(DbType.POSTGRESQL, "app"), "SELECT 1;", 10);
        assertThat(live.ok()).isTrue();
        assertThat(live.cost()).isEqualTo(3.5);
    }

    @Test
    void realDriversAcceptTheGeneratedTargets() throws Exception {
        // Gerçek sürücüler, kapalı yerel porta: sürücü adres / özellikleri KABUL etmeli → "ulaşılamıyor" (DB_DRIVER değil)
        SsrfGuard local = mock(SsrfGuard.class);
        when(local.validate("127.0.0.1")).thenReturn(List.of(InetAddress.getByName("127.0.0.1")));
        DbQueryRunner real = new DbQueryRunner(local);
        for (DbType type : DbType.values()) {
            DbConnectionSpec s = new DbConnectionSpec(type, "127.0.0.1", 1, type == DbType.ORACLE ? "XE" : "app",
                    "SERVICE", null, "u", "p", null, false, true, 1_000);
            DbQueryRunner.ConnectionTest t = real.test(s);
            assertThat(t.ok()).isFalse();
            assertThat(t.failureReason()).as(type.name() + ": " + t.error()).isEqualTo("DB_UNREACHABLE");
        }
    }
}
