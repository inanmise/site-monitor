package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.SsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bağlantı denemesinin ADIM ADIM teşhisi (2026-11-05, dbq2 A3) — sahte sürücü + sahte TCP: her adımın durumu / kodu, ilk
 * başarısızlıktan sonra {@code skip}, üst düzey alanların (ok, failure_reason) eskisiyle aynı kalması, parola / kullanıcının
 * hiçbir adım ayrıntısına girmemesi; gerçek soketle TCP ret / açık port.
 */
class DbQueryRunnerStepsTest {

    private SsrfGuard ssrf;
    private Connection conn;
    private Statement setup;
    private final AtomicInteger connects = new AtomicInteger();
    private final List<String> tcpCalls = new ArrayList<>();
    private DbQueryRunner.Connector connector;

    @BeforeEach
    void setUp() throws Exception {
        ssrf = mock(SsrfGuard.class);
        when(ssrf.validate("db.example")).thenReturn(List.of(InetAddress.getByName("10.0.0.5"), InetAddress.getByName("10.0.0.6")));
        conn = mock(Connection.class);
        setup = mock(Statement.class);
        when(conn.createStatement()).thenReturn(setup);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getDatabaseProductVersion()).thenReturn("16.4");
        when(conn.getMetaData()).thenReturn(md);
        connector = (u, p) -> {
            connects.incrementAndGet();
            return conn;
        };
    }

    private DbQueryRunner runner(DbQueryRunner.TcpProbe tcp) {
        return new DbQueryRunner(ssrf, connector, (ip, port, ms) -> {
            tcpCalls.add(ip.getHostAddress() + ":" + port + "/" + ms);
            tcp.connect(ip, port, ms);
        });
    }

    private static DbConnectionSpec spec(String ssl) {
        return new DbConnectionSpec(DbType.POSTGRESQL, "db.example", 5432, "app", null, null, "appuser",
                "S3cr3t!pw", ssl, false, false, 7_000);
    }

    private void selectOneReturns() throws SQLException {
        when(setup.executeQuery("/* SiteMonitor */ SELECT 1")).thenReturn(FakeJdbc.resultSet(
                List.of(new FakeJdbc.Col("a", Types.INTEGER, 1)), List.<Object[]>of(new Object[] {1})).resultSet());
    }

    private static DbConnectionSteps.Step step(DbQueryRunner.ConnectionTest t, String key) {
        return t.steps().stream().filter(s -> key.equals(s.key())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("D8: yetki denetimi fırlatırsa deneme yine başarılı, adımlar tam altı (query bir kez ok, privileges warn CHECK_FAILED)")
    void privilegeCheckThrows() throws Exception {
        selectOneReturns();
        DbQueryRunner r = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN);
        r.privilegeCheck = (c, type) -> { throw new IllegalStateException("izleyici kesti"); };
        DbQueryRunner.ConnectionTest t = r.test(spec("require"), true);
        assertThat(t.ok()).isTrue();
        assertThat(t.steps()).extracting(DbConnectionSteps.Step::key)
                .containsExactly("dns", "tcp", "tls", "login", "query", "privileges");
        assertThat(step(t, "query").status()).isEqualTo("ok");
        assertThat(step(t, "privileges").status()).isEqualTo("warn");
        assertThat(step(t, "privileges").code()).isEqualTo("CHECK_FAILED");
        assertThat(t.privileges().checked()).isTrue();
        assertThat(t.privileges().readOnly()).isNull();

        r.privilegeCheck = (c, type) -> { throw new AssertionError("beklenmedik"); };   // Error da yakalanır
        assertThat(r.test(spec("require"), true).steps()).hasSize(6);
    }

    @Test
    @DisplayName("başarı: altı adım sırayla; DNS IP'yi (+ek adres sayısı), TCP IP:port'u söyler; TLS yapılandırmadan (prefer → uyarı); yetki kapalı → skip NOT_CHECKED")
    void successSteps() throws Exception {
        selectOneReturns();
        DbQueryRunner.ConnectionTest t = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN).test(spec("prefer"), false);
        assertThat(t.ok()).isTrue();
        assertThat(t.failureReason()).isNull();
        assertThat(t.serverVersion()).isEqualTo("PostgreSQL 16.4");
        assertThat(t.steps()).extracting(DbConnectionSteps.Step::key)
                .containsExactly("dns", "tcp", "tls", "login", "query", "privileges");
        assertThat(t.steps()).extracting(DbConnectionSteps.Step::status)
                .containsExactly("ok", "ok", "warn", "ok", "ok", "skip");
        assertThat(step(t, "dns").detail()).isEqualTo("10.0.0.5 (+1)");
        assertThat(step(t, "tcp").detail()).isEqualTo("10.0.0.5:5432");
        assertThat(step(t, "tls").code()).isEqualTo("SSL_PREFER_DOWNGRADE");
        assertThat(step(t, "login").detail()).isEqualTo("PostgreSQL 16.4");
        assertThat(step(t, "privileges").code()).isEqualTo("NOT_CHECKED");
        assertThat(step(t, "dns").ms()).isNotNull();
        assertThat(step(t, "tls").ms()).isNull();
        assertThat(tcpCalls).containsExactly("10.0.0.5:5432/7000");   // SSRF'nin döndürdüğü İLK adres, bağlantı zaman aşımı
        assertThat(String.valueOf(t.steps())).doesNotContain("S3cr3t!pw").doesNotContain("appuser");
    }

    @Test
    @DisplayName("DNS: çözülemeyen ad → dns fail DB_DNS, kalanı skip, TCP / sürücü hiç denenmez; engelli hedef → DB_BLOCKED")
    void dnsFailures() throws Exception {
        when(ssrf.validate("nohost")).thenThrow(new SsrfGuard.UnresolvableHostException(SsrfGuard.UNRESOLVABLE_PREFIX + "nohost"));
        when(ssrf.validate("meta")).thenThrow(new SsrfGuard.BlockedException("izin verilmeyen hedef"));
        DbQueryRunner r = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN);
        DbQueryRunner.ConnectionTest nohost = r.test(new DbConnectionSpec(DbType.POSTGRESQL, "nohost", 5432, "app", null, null,
                "u", "p", null, false, false, 1_000), true);
        assertThat(nohost.ok()).isFalse();
        assertThat(nohost.failureReason()).isEqualTo("DB_DNS");
        assertThat(nohost.steps()).extracting(DbConnectionSteps.Step::status).containsExactly("fail", "skip", "skip", "skip", "skip", "skip");
        assertThat(step(nohost, "dns").code()).isEqualTo("DB_DNS");
        assertThat(step(nohost, "dns").detail()).isEqualTo(nohost.error());
        DbQueryRunner.ConnectionTest meta = r.test(new DbConnectionSpec(DbType.POSTGRESQL, "meta", 5432, "app", null, null,
                "u", "p", null, false, false, 1_000), true);
        assertThat(meta.failureReason()).isEqualTo("DB_BLOCKED");
        assertThat(step(meta, "dns").code()).isEqualTo("DB_BLOCKED");
        assertThat(tcpCalls).isEmpty();
        assertThat(connects.get()).isZero();
    }

    @Test
    @DisplayName("TCP: ret / zaman aşımı ayrı kod, üst düzey DB_UNREACHABLE (eskisiyle aynı), sürücü HİÇ çağrılmaz, metin IP:port'u söyler")
    void tcpFailures() throws Exception {
        DbQueryRunner.ConnectionTest refused = runner((ip, port, ms) -> { throw new ConnectException("Connection refused"); })
                .test(spec("require"), true);
        assertThat(refused.ok()).isFalse();
        assertThat(refused.failureReason()).isEqualTo("DB_UNREACHABLE");
        assertThat(refused.steps()).extracting(DbConnectionSteps.Step::status).containsExactly("ok", "fail", "skip", "skip", "skip", "skip");
        assertThat(step(refused, "tcp").code()).isEqualTo("TCP_REFUSED");
        assertThat(refused.error()).contains("10.0.0.5:5432").isEqualTo(step(refused, "tcp").detail());
        assertThat(refused.privileges().checked()).isFalse();
        DbQueryRunner.ConnectionTest timeout = runner((ip, port, ms) -> { throw new SocketTimeoutException("connect timed out"); })
                .test(spec("require"), true);
        assertThat(step(timeout, "tcp").code()).isEqualTo("TCP_TIMEOUT");
        assertThat(timeout.failureReason()).isEqualTo("DB_UNREACHABLE");
        assertThat(connects.get()).isZero();
    }

    @Test
    @DisplayName("oturum açma: kimlik reddi → login fail DB_AUTH, TLS adımı yapılandırmadan (sunucu yanıt verdi); veritabanı yok → DB_NOT_FOUND; üst düzey sınıflandırma değişmez")
    void loginFailures() throws Exception {
        connector = (u, p) -> { throw new SQLException("FATAL: password authentication failed for user \"appuser\"", "28P01"); };
        DbQueryRunner.ConnectionTest auth = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN).test(spec("verify-full"), true);
        assertThat(auth.failureReason()).isEqualTo("DB_AUTH");
        assertThat(auth.steps()).extracting(DbConnectionSteps.Step::status).containsExactly("ok", "ok", "ok", "fail", "skip", "skip");
        assertThat(step(auth, "login").code()).isEqualTo("DB_AUTH");
        assertThat(step(auth, "login").detail()).doesNotContain("appuser").doesNotContain("S3cr3t!pw");

        connector = (u, p) -> { throw new SQLException("FATAL: database \"app\" does not exist", "3D000"); };
        DbQueryRunner.ConnectionTest nodb = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN).test(spec("prefer"), true);
        assertThat(step(nodb, "login").code()).isEqualTo("DB_NOT_FOUND");
        assertThat(step(nodb, "tls").code()).isEqualTo("SSL_PREFER_DOWNGRADE");

        connector = (u, p) -> { throw new SQLException("I/O error", "08006", new SocketTimeoutException("Read timed out")); };
        DbQueryRunner.ConnectionTest lost = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN).test(spec("verify-full"), true);
        assertThat(step(lost, "tls").status()).isEqualTo("skip");   // el sıkışmanın bittiği bilinmiyor — "ok" denmez
        assertThat(step(lost, "login").status()).isEqualTo("fail");
    }

    @Test
    @DisplayName("TLS: sertifika doğrulanamadı → tls fail TLS_CERT, oturum açma skip; üst düzey DB_UNREACHABLE (SSL istisnası eski sınıflandırma)")
    void tlsFailure() throws Exception {
        connector = (u, p) -> { throw new SQLException("SSL error: PKIX path building failed", "08006",
                new javax.net.ssl.SSLHandshakeException("PKIX path building failed")); };
        DbQueryRunner.ConnectionTest t = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN).test(spec("verify-full"), true);
        assertThat(t.failureReason()).isEqualTo("DB_UNREACHABLE");
        assertThat(t.steps()).extracting(DbConnectionSteps.Step::status).containsExactly("ok", "ok", "fail", "skip", "skip", "skip");
        assertThat(step(t, "tls").code()).isEqualTo("TLS_CERT");
    }

    @Test
    @DisplayName("oturum kurulumu düşerse oturum açma adımı (eski açılış aşaması); SELECT 1 düşerse sorgu adımı; parola hiçbir ayrıntıda yok")
    void setupAndQueryFailures() throws Exception {
        when(setup.execute("SET TRANSACTION READ ONLY")).thenThrow(new SQLException("cannot set read only for S3cr3t!pw", "25001"));
        DbQueryRunner.ConnectionTest setupFail = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN).test(spec("prefer"), true);
        assertThat(setupFail.ok()).isFalse();
        assertThat(step(setupFail, "login").status()).isEqualTo("fail");
        assertThat(String.valueOf(setupFail.steps())).doesNotContain("S3cr3t!pw");

        org.mockito.Mockito.reset(setup);
        when(setup.executeQuery(anyString())).thenThrow(new SQLException("permission denied", "42501"));
        DbQueryRunner.ConnectionTest q = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN).test(spec("prefer"), true);
        assertThat(q.failureReason()).isEqualTo("DB_SQL_ERROR");
        assertThat(q.steps()).extracting(DbConnectionSteps.Step::status).containsExactly("ok", "ok", "warn", "ok", "fail", "skip");
        assertThat(step(q, "query").code()).isEqualTo("DB_SQL_ERROR");
    }

    @Test
    @DisplayName("gerçek soket: kapalı yerel port → TCP_REFUSED (sürücü denenmez); dinleyen port → tcp ok")
    void realSocketProbe() throws Exception {
        SsrfGuard local = mock(SsrfGuard.class);
        when(local.validate("127.0.0.1")).thenReturn(List.of(InetAddress.getByName("127.0.0.1")));
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = ss.getLocalPort();
        }
        AtomicInteger driver = new AtomicInteger();
        DbQueryRunner real = new DbQueryRunner(local, (u, p) -> { driver.incrementAndGet(); throw new SQLException("x", "08001"); },
                DbQueryRunner.TcpProbe.SOCKET);
        // Windows yerel kapalı porta RST'yi ~2 sn yeniden dener — zaman aşımı geniş tutulur (ret, zaman aşımından önce gelsin)
        DbConnectionSpec closed = new DbConnectionSpec(DbType.POSTGRESQL, "127.0.0.1", closedPort, "app", null, null, "u", "p",
                null, false, false, 15_000);
        DbQueryRunner.ConnectionTest t = real.test(closed, false);
        assertThat(step(t, "tcp").code()).isEqualTo("TCP_REFUSED");
        assertThat(driver.get()).isZero();

        try (ServerSocket open = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            DbConnectionSpec listening = new DbConnectionSpec(DbType.POSTGRESQL, "127.0.0.1", open.getLocalPort(), "app", null,
                    null, "u", "p", null, false, false, 2_000);
            DbQueryRunner.ConnectionTest o = real.test(listening, false);
            assertThat(step(o, "tcp").status()).isEqualTo("ok");
            assertThat(driver.get()).isEqualTo(1);
            assertThat(step(o, "login").status()).isEqualTo("fail");
        }
    }

    @Test
    @DisplayName("geçersiz port: tcp fail DB_DRIVER (tanım hatası) — soket açılmaz")
    void invalidPort() {
        DbQueryRunner.ConnectionTest t = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN).test(new DbConnectionSpec(DbType.POSTGRESQL,
                "db.example", 70_000, "app", null, null, "u", "p", null, false, false, 1_000), false);
        assertThat(t.failureReason()).isEqualTo("DB_DRIVER");
        assertThat(step(t, "tcp").code()).isEqualTo("DB_DRIVER");
        assertThat(tcpCalls).isEmpty();
    }

    @Test
    @DisplayName("tür yok: altı adım skip (deneme başlamadı)")
    void noType() {
        DbQueryRunner.ConnectionTest t = runner(DbQueryRunner.TcpProbe.ASSUME_OPEN).test(null, false);
        assertThat(t.ok()).isFalse();
        assertThat(t.steps()).hasSize(6).allMatch(s -> "skip".equals(s.status()));
    }

    @Test
    @DisplayName("TCP zaman aşımı sürücüyle aynı sınırda: 1–30 sn, varsayılan 10 sn")
    void tcpTimeoutClamp() {
        assertThat(DbQueryRunner.tcpTimeoutMs(spec(null))).isEqualTo(7_000);
        assertThat(DbQueryRunner.tcpTimeoutMs(new DbConnectionSpec(DbType.POSTGRESQL, "h", 1, "d", null, null, "u", "p", null,
                false, false, 0))).isEqualTo(10_000);
        assertThat(DbQueryRunner.tcpTimeoutMs(new DbConnectionSpec(DbType.POSTGRESQL, "h", 1, "d", null, null, "u", "p", null,
                false, false, 99_000))).isEqualTo(30_000);
        assertThat(DbQueryRunner.tcpTimeoutMs(new DbConnectionSpec(DbType.POSTGRESQL, "h", 1, "d", null, null, "u", "p", null,
                false, false, 10))).isEqualTo(1_000);
    }
}
