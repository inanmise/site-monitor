package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bağlantı denemesi adımları (2026-11-05, dbq2 A3) — saf eşlemeler: TCP hata kodları, TLS adımı (yapılandırmadan), sürücü
 * hatasının TLS / oturum açma adımına yerleşimi, yetki adımı, eksik adımların {@code skip} tamamlanması.
 */
class DbConnectionStepsTest {

    private static DbConnectionSpec pg(String ssl) {
        return new DbConnectionSpec(DbType.POSTGRESQL, "db", 5432, "app", null, null, "u", "p", ssl, false, false, 5_000);
    }

    private static DbConnectionSpec mssql(boolean encrypt, boolean trust) {
        return new DbConnectionSpec(DbType.MSSQL, "db", 1433, "app", null, null, "u", "p", null, encrypt, trust, 5_000);
    }

    private static DbConnectionSpec oracle(boolean tcps) {
        return new DbConnectionSpec(DbType.ORACLE, "db", 1521, "XE", "SERVICE", null, "u", "p", null, false, false, 5_000,
                null, tcps);
    }

    @Test
    @DisplayName("TCP: ret → TCP_REFUSED, zaman aşımı (soket ya da 'timed out' iletili ConnectException) → TCP_TIMEOUT, yol yok → TCP_UNREACHABLE")
    void tcpCodes() {
        assertThat(DbConnectionSteps.tcpCode(new ConnectException("Connection refused"))).isEqualTo("TCP_REFUSED");
        assertThat(DbConnectionSteps.tcpCode(new SocketTimeoutException("Connect timed out"))).isEqualTo("TCP_TIMEOUT");
        assertThat(DbConnectionSteps.tcpCode(new ConnectException("Connection timed out: connect"))).isEqualTo("TCP_TIMEOUT");
        assertThat(DbConnectionSteps.tcpCode(new NoRouteToHostException("No route to host"))).isEqualTo("TCP_UNREACHABLE");
        assertThat(DbConnectionSteps.tcpCode(new java.io.IOException("x", new ConnectException("refused")))).isEqualTo("TCP_REFUSED");
        assertThat(DbConnectionSteps.tcpCode(new IllegalStateException("?"))).isEqualTo("TCP_UNREACHABLE");
        assertThat(DbConnectionSteps.tcpMessage("TCP_REFUSED", "10.0.0.5:5432")).contains("10.0.0.5:5432").contains("reddedildi");
    }

    @Test
    @DisplayName("TLS adımı yapılandırmadan: doğrulamalı kip ok; prefer / require / disable uyarı (bağlantı güvenliği kodlarıyla); MSSQL ve Oracle aynı mantık")
    void tlsOutcome() {
        assertThat(DbConnectionSteps.tlsOutcome(pg("verify-full")).status()).isEqualTo("ok");
        assertThat(DbConnectionSteps.tlsOutcome(pg("verify-ca")).status()).isEqualTo("ok");
        assertThat(DbConnectionSteps.tlsOutcome(pg(null)).code()).isEqualTo("SSL_PREFER_DOWNGRADE");
        assertThat(DbConnectionSteps.tlsOutcome(pg("prefer")).status()).isEqualTo("warn");
        assertThat(DbConnectionSteps.tlsOutcome(pg("require")).code()).isEqualTo("SSL_NOT_VERIFIED");
        assertThat(DbConnectionSteps.tlsOutcome(pg("disable")).code()).isEqualTo("SSL_DISABLED");
        assertThat(DbConnectionSteps.tlsOutcome(mssql(false, false)).code()).isEqualTo("MSSQL_UNENCRYPTED");
        assertThat(DbConnectionSteps.tlsOutcome(mssql(true, true)).code()).isEqualTo("MSSQL_TRUST_SERVER_CERT");
        assertThat(DbConnectionSteps.tlsOutcome(mssql(true, false)).status()).isEqualTo("ok");
        assertThat(DbConnectionSteps.tlsOutcome(oracle(true)).status()).isEqualTo("ok");
        DbConnectionSteps.Step plain = DbConnectionSteps.tlsOutcome(oracle(false));
        assertThat(plain.status()).isEqualTo("warn");
        assertThat(plain.code()).isEqualTo("SSL_DISABLED");
        assertThat(plain.detail()).contains("NNE");
        assertThat(plain.ms()).isNull();
        // her kod bağlantı güvenliği uyarı listesinde (ön yüz aynı çare metnini kullanır)
        for (DbConnectionSpec s : List.of(pg(null), pg("require"), pg("disable"), mssql(false, false), mssql(true, true)))
            assertThat(DbConnectionProfileService.SECURITY_WARNING_CODES).contains(DbConnectionSteps.tlsOutcome(s).code());
    }

    @Test
    @DisplayName("sürücü hatası yerleşimi: SSL / sertifika → TLS; veritabanı / servis yok → DB_NOT_FOUND; kimlik → DB_AUTH (sertifika sözcüğü olsa da); MSSQL sarmalı → TLS")
    void loginFailurePlacement() {
        var cert = DbConnectionSteps.loginFailure(new SQLException("SSL error",
                new javax.net.ssl.SSLHandshakeException("PKIX path building failed")), "DB_UNREACHABLE");
        assertThat(cert).isEqualTo(new DbConnectionSteps.Placement("tls", "TLS_CERT"));
        var cpv = DbConnectionSteps.loginFailure(new SQLException("x", new java.security.cert.CertPathValidatorException("expired")),
                "DB_UNREACHABLE");
        assertThat(cpv.code()).isEqualTo("TLS_CERT");
        var hs = DbConnectionSteps.loginFailure(new SQLException("x", new javax.net.ssl.SSLException("handshake_failure")),
                "DB_UNREACHABLE");
        assertThat(hs).isEqualTo(new DbConnectionSteps.Placement("tls", "TLS_FAILED"));
        var wrapped = DbConnectionSteps.loginFailure(new SQLException("The driver could not establish a secure connection to SQL "
                + "Server by using Secure Sockets Layer (SSL) encryption.", "08S01"), "DB_UNREACHABLE");
        assertThat(wrapped).isEqualTo(new DbConnectionSteps.Placement("tls", "TLS_FAILED"));
        assertThat(DbConnectionSteps.loginFailure(new SQLException("The server does not support SSL.", "08004"), "DB_UNREACHABLE").step())
                .isEqualTo("tls");

        assertThat(DbConnectionSteps.loginFailure(new SQLException("FATAL: database \"x\" does not exist", "3D000"), "DB_SQL_ERROR"))
                .isEqualTo(new DbConnectionSteps.Placement("login", "DB_NOT_FOUND"));
        assertThat(DbConnectionSteps.loginFailure(new SQLException("Cannot open database \"x\"", "S0001", 4060), "DB_AUTH").code())
                .isEqualTo("DB_NOT_FOUND");
        assertThat(DbConnectionSteps.loginFailure(new SQLException("Listener refused the connection with the following error: "
                + "ORA-12514, TNS:listener does not currently know of service requested"), "DB_UNREACHABLE").code())
                .isEqualTo("DB_NOT_FOUND");

        assertThat(DbConnectionSteps.loginFailure(new SQLException("FATAL: password authentication failed", "28P01"), "DB_AUTH"))
                .isEqualTo(new DbConnectionSteps.Placement("login", "DB_AUTH"));
        assertThat(DbConnectionSteps.loginFailure(new SQLException("FATAL: certificate authentication failed for user", "28000"),
                "DB_AUTH")).isEqualTo(new DbConnectionSteps.Placement("login", "DB_AUTH"));
        assertThat(DbConnectionSteps.loginFailure(new SQLException("Connection reset", "08006"), "DB_UNREACHABLE"))
                .isEqualTo(new DbConnectionSteps.Placement("login", "DB_UNREACHABLE"));
        assertThat(DbConnectionSteps.loginFailure(new IllegalStateException("?"), null).code()).isEqualTo("DB_DRIVER");
    }

    @Test
    @DisplayName("yetki adımı: kapalı → skip NOT_CHECKED; yazabilir → warn ACCOUNT_WRITABLE + bulgu kodları; okunamadı → warn CHECK_FAILED (asla ok); salt-okur → ok (bilgi bulgusu detayda)")
    void privilegesStep() {
        assertThat(DbConnectionSteps.privileges(DbAccountPrivileges.Result.NOT_CHECKED, null))
                .isEqualTo(new DbConnectionSteps.Step("privileges", "skip", null, "NOT_CHECKED",
                        DbConnectionSteps.privileges(null, null).detail()));
        var w = DbConnectionSteps.privileges(new DbAccountPrivileges.Result(true, false, List.of(
                new DbAccountPrivileges.Finding("CAN_WRITE_TABLES", "public.t"), new DbAccountPrivileges.Finding("CAN_CREATE", "public"),
                new DbAccountPrivileges.Finding("PUBLIC_SCHEMA_CREATE", "public"))), 12L);
        assertThat(w.status()).isEqualTo("warn");
        assertThat(w.code()).isEqualTo("ACCOUNT_WRITABLE");
        assertThat(w.detail()).isEqualTo("CAN_WRITE_TABLES, CAN_CREATE");
        assertThat(w.ms()).isEqualTo(12L);
        var unknown = DbConnectionSteps.privileges(new DbAccountPrivileges.Result(true, null, List.of(
                new DbAccountPrivileges.Finding("CHECK_FAILED", null))), 5L);
        assertThat(unknown.status()).isEqualTo("warn");
        assertThat(unknown.code()).isEqualTo("CHECK_FAILED");
        var ro = DbConnectionSteps.privileges(new DbAccountPrivileges.Result(true, true, List.of(
                new DbAccountPrivileges.Finding("PUBLIC_SCHEMA_CREATE", "public"))), 3L);
        assertThat(ro.status()).isEqualTo("ok");
        assertThat(ro.code()).isNull();
        assertThat(ro.detail()).isEqualTo("PUBLIC_SCHEMA_CREATE");
    }

    @Test
    @DisplayName("API: her zaman altı adım, sabit sırada; eksikler skip; null → hepsi skip; tüm kodlar listede")
    void apiShape() {
        List<Map<String, Object>> none = DbConnectionSteps.toApi(null);
        assertThat(none).hasSize(6).extracting(m -> m.get("key")).containsExactly("dns", "tcp", "tls", "login", "query", "privileges");
        assertThat(none).allMatch(m -> "skip".equals(m.get("status")) && m.get("code") == null && m.get("ms") == null);
        List<Map<String, Object>> partial = DbConnectionSteps.toApi(List.of(
                new DbConnectionSteps.Step("dns", "ok", 3L, null, "10.0.0.5"),
                new DbConnectionSteps.Step("tcp", "fail", 1000L, "TCP_TIMEOUT", "x")));
        assertThat(partial).hasSize(6);
        assertThat(partial.get(1)).containsEntry("status", "fail").containsEntry("code", "TCP_TIMEOUT").containsEntry("ms", 1000L);
        assertThat(partial.subList(2, 6)).allMatch(m -> "skip".equals(m.get("status")));
        assertThat(DbConnectionSteps.CODES).doesNotHaveDuplicates()
                .contains("DB_DNS", "DB_BLOCKED", "TCP_REFUSED", "TCP_TIMEOUT", "TCP_UNREACHABLE", "TLS_CERT", "TLS_FAILED",
                        "DB_AUTH", "DB_NOT_FOUND", "ACCOUNT_WRITABLE", "CHECK_FAILED", "NOT_CHECKED", "SSL_DISABLED");
    }
}
