package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * r177 bağlantı kurulumu: DBA izi (uygulama adı + sorgu yorumu), kurum CA paketiyle TLS (PostgreSQL {@code sslfactory},
 * MSSQL {@code trustManagerClass}), Oracle TCPS (ad URL'de, bağlantı doğrulanmış IP'ye kilitli).
 */
class DbQueryR177ConnectionTest {

    private static DbConnectionSpec spec(DbType type, String ssl, boolean encrypt, boolean trustCert, boolean tcps) {
        return new DbConnectionSpec(type, "db.ornek.com", tcps ? DbJdbcUrls.ORACLE_TCPS_PORT : type.defaultPort(), "app",
                "SERVICE", null, "u", "pw", ssl, encrypt, trustCert, 3_000, DbConnectionSpec.DEFAULT_APP_NAME, tcps);
    }

    private static final InetAddress IP;

    static {
        try {
            IP = InetAddress.getByAddress("db.ornek.com", new byte[] {10, 0, 0, 5});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void appNameIsSanitisedAndTagsTheQuery() {
        DbConnectionSpec s = spec(DbType.POSTGRESQL, "require", false, false, false).withAppName("SiteMonitor dbq#42");
        assertThat(s.safeAppName()).isEqualTo("SiteMonitor dbq#42");
        assertThat(DbQueryRunner.tagged(s, "SELECT 1")).isEqualTo("/* SiteMonitor dbq#42 */ SELECT 1");
        // yorumu kapatıp araya SQL sokmak mümkün değil: izinsiz karakterler düşer
        DbConnectionSpec evil = s.withAppName("x */ DROP TABLE t; /*");
        assertThat(evil.safeAppName()).doesNotContain("*").doesNotContain("/").doesNotContain(";");
        assertThat(DbQueryRunner.tagged(evil, "SELECT 1")).startsWith("/* ").endsWith(" */ SELECT 1");
        assertThat(s.withAppName(null).safeAppName()).isEqualTo("SiteMonitor");
        assertThat(s.withAppName("ş".repeat(5)).safeAppName()).isEqualTo("SiteMonitor");
        assertThat(s.withAppName("a".repeat(500)).safeAppName()).hasSizeLessThanOrEqualTo(60);
        // parola metne girmez
        assertThat(s.toString()).doesNotContain("pw");
    }

    @Test
    void postgresSessionNameAndCorporateTrust() {
        Properties req = DbJdbcUrls.build(spec(DbType.POSTGRESQL, "require", false, false, false)
                .withAppName("SiteMonitor dbq#7"), IP, 10).props();
        assertThat(req.getProperty("ApplicationName")).isEqualTo("SiteMonitor dbq#7");
        assertThat(req.getProperty("sslmode")).isEqualTo("require");
        assertThat(req.getProperty("sslfactory")).isNull();   // doğrulamasız kipte sürücünün kendi fabrikası
        for (String mode : new String[] {"verify-full", "verify-ca"}) {
            Properties p = DbJdbcUrls.build(spec(DbType.POSTGRESQL, mode, false, false, false), IP, 10).props();
            assertThat(p.getProperty("sslfactory")).as(mode).isEqualTo(DbTrustSslSocketFactory.class.getName());
        }
    }

    @Test
    void mssqlApplicationNameAndTrustManager() {
        Properties enc = DbJdbcUrls.build(spec(DbType.MSSQL, null, true, false, false).withAppName("SiteMonitor dbq-test"),
                IP, 10).props();
        assertThat(enc.getProperty("applicationName")).isEqualTo("SiteMonitor dbq-test");
        assertThat(enc.getProperty("trustManagerClass")).isEqualTo(DbTrustManager.class.getName());
        assertThat(enc.getProperty("trustManagerConstructorArg")).isEqualTo("db.ornek.com");
        assertThat(enc.getProperty("hostNameInCertificate")).isEqualTo("db.ornek.com");
        // sunucu sertifikasına körü körüne güven / şifresiz: özel güven yöneticisi yok
        assertThat(DbJdbcUrls.build(spec(DbType.MSSQL, null, true, true, false), IP, 10).props()
                .getProperty("trustManagerClass")).isNull();
        assertThat(DbJdbcUrls.build(spec(DbType.MSSQL, null, false, false, false), IP, 10).props()
                .getProperty("trustManagerClass")).isNull();
    }

    @Test
    void oracleTcpsUrl() {
        DbJdbcUrls.Target tcp = DbJdbcUrls.build(spec(DbType.ORACLE, null, false, false, false), IP, 10);
        assertThat(tcp.url()).contains("(PROTOCOL=TCP)").contains("(HOST=10.0.0.5)").doesNotContain("SSL_SERVER_DN_MATCH");
        assertThat(tcp.tcps()).isNull();
        assertThat(tcp.props().getProperty("v$session.program")).isEqualTo("SiteMonitor");
        DbJdbcUrls.Target tcps = DbJdbcUrls.build(spec(DbType.ORACLE, null, false, false, true), IP, 10);
        assertThat(tcps.url()).isEqualTo("jdbc:oracle:thin:@(DESCRIPTION=(CONNECT_TIMEOUT=3)(ADDRESS=(PROTOCOL=TCPS)"
                + "(HOST=db.ornek.com)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=app))(SECURITY=(SSL_SERVER_DN_MATCH=TRUE)))");
        assertThat(tcps.props().getProperty("oracle.net.ssl_server_dn_match")).isEqualTo("true");
        assertThat(tcps.tcps().host()).isEqualTo("db.ornek.com");
        assertThat(tcps.tcps().pinned()).isEqualTo(IP);
        // TCPS'te ad zorunlu ve geçerli olmalı (sertifika adla doğrulanır)
        DbConnectionSpec bad = new DbConnectionSpec(DbType.ORACLE, "db ornek", 2484, "app", "SERVICE", null, "u", "pw", null,
                false, false, 3_000, null, true);
        assertThatThrownBy(() -> DbJdbcUrls.build(bad, IP, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Gerçek ojdbc11 sürücüsüyle: TCPS bağlantısı URL'deki ADI çözmez, doğrulanmış (kilitli) IP'ye gider — çözülemeyen bir
     * ad verilir, yerel bir soket dinlenir; bağlantı oraya ulaşırsa kilit çalışıyordur. Sunucu TLS konuşmadığı için el
     * sıkışma başarısız olur (SQLException) — "her şeye güven" yolu yok.
     */
    @Test
    void oracleTcpsConnectsToThePinnedAddressOnly() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            AtomicBoolean reached = new AtomicBoolean();
            CountDownLatch accepted = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    reached.set(true);
                    accepted.countDown();
                    s.getInputStream().read(new byte[64]);
                } catch (Exception ignored) {
                    accepted.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
            DbConnectionSpec s = new DbConnectionSpec(DbType.ORACLE, "dbq-tcps-pin.invalid", server.getLocalPort(), "app",
                    "SERVICE", null, "u", "pw", null, false, false, 3_000, null, true);
            DbJdbcUrls.Target target = DbJdbcUrls.build(s, InetAddress.getLoopbackAddress(), 5);
            assertTimeoutPreemptively(Duration.ofSeconds(40), () ->
                    assertThatThrownBy(() -> DbQueryRunner.oracleTcpsConnect(target)).isInstanceOf(SQLException.class));
            assertThat(accepted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reached.get()).as("bağlantı kilitli IP'ye ulaştı").isTrue();
        }
    }
}
