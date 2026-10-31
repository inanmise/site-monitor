package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.SsrfGuard;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static com.sitemonitor.service.dbquery.DbQueryErrors.Phase.CONNECT;
import static com.sitemonitor.service.dbquery.DbQueryErrors.Phase.QUERY;
import static org.assertj.core.api.Assertions.assertThat;

/** Sürücü hatası sınıflandırma (SQLState + satıcı kodu + neden zinciri) ve maskeleme. */
class DbQueryErrorsTest {

    private static String c(Throwable e, DbQueryErrors.Phase phase, DbType type) {
        return DbQueryErrors.classify(e, phase, false, type);
    }

    private static SQLException sql(String msg, String state, int vendor) {
        return new SQLException(msg, state, vendor);
    }

    @Test
    void timeouts() {
        assertThat(c(sql("canceling statement due to statement timeout", "57014", 0), QUERY, DbType.POSTGRESQL))
                .isEqualTo("DB_TIMEOUT");
        assertThat(c(sql("lock timeout", "55P03", 0), QUERY, DbType.POSTGRESQL)).isEqualTo("DB_TIMEOUT");
        assertThat(c(sql("The query has timed out.", "HY008", 0), QUERY, DbType.MSSQL)).isEqualTo("DB_TIMEOUT");
        assertThat(c(sql("Lock request time out period exceeded.", "S00051", 1222), QUERY, DbType.MSSQL)).isEqualTo("DB_TIMEOUT");
        assertThat(c(sql("ORA-01013: user requested cancel", "72000", 1013), QUERY, DbType.ORACLE)).isEqualTo("DB_TIMEOUT");
        assertThat(c(new SQLTimeoutException("x"), QUERY, DbType.ORACLE)).isEqualTo("DB_TIMEOUT");
        SQLException io = new SQLException("I/O error", "08006", 0, new SocketTimeoutException("Read timed out"));
        assertThat(c(io, QUERY, DbType.POSTGRESQL)).isEqualTo("DB_TIMEOUT");
        assertThat(c(io, CONNECT, DbType.POSTGRESQL)).isEqualTo("DB_UNREACHABLE");
        assertThat(DbQueryErrors.classify(sql("anything", "42P01", 0), QUERY, true, DbType.POSTGRESQL)).isEqualTo("DB_TIMEOUT");
    }

    @Test
    void authAndConnection() {
        assertThat(c(sql("password authentication failed", "28P01", 0), CONNECT, DbType.POSTGRESQL)).isEqualTo("DB_AUTH");
        assertThat(c(sql("Login failed for user 'x'.", "S0001", 18456), CONNECT, DbType.MSSQL)).isEqualTo("DB_AUTH");
        assertThat(c(sql("Cannot open database", "S0001", 4060), CONNECT, DbType.MSSQL)).isEqualTo("DB_AUTH");
        assertThat(c(sql("ORA-01017: invalid username/password", "72000", 1017), CONNECT, DbType.ORACLE)).isEqualTo("DB_AUTH");
        assertThat(c(sql("Connection refused", "08001", 0, new ConnectException("refused")), CONNECT, DbType.POSTGRESQL))
                .isEqualTo("DB_UNREACHABLE");
        assertThat(c(sql("The TCP/IP connection to the host has failed", "08S01", 0), CONNECT, DbType.MSSQL))
                .isEqualTo("DB_UNREACHABLE");
        assertThat(c(sql("ORA-12541: TNS:no listener", null, 12541), CONNECT, DbType.ORACLE)).isEqualTo("DB_UNREACHABLE");
        assertThat(c(sql("IO Error", null, 17002), CONNECT, DbType.ORACLE)).isEqualTo("DB_UNREACHABLE");
    }

    private static SQLException sql(String msg, String state, int vendor, Throwable cause) {
        return new SQLException(msg, state, vendor, cause);
    }

    @Test
    void sqlDnsBlockedDriver() {
        assertThat(c(sql("relation \"x\" does not exist", "42P01", 0), QUERY, DbType.POSTGRESQL)).isEqualTo("DB_SQL_ERROR");
        assertThat(c(sql("cannot execute INSERT in a read-only transaction", "25006", 0), QUERY, DbType.POSTGRESQL))
                .isEqualTo("DB_SQL_ERROR");
        assertThat(c(sql("ORA-00942: table or view does not exist", "42000", 942), QUERY, DbType.ORACLE)).isEqualTo("DB_SQL_ERROR");
        assertThat(c(new SsrfGuard.UnresolvableHostException("x"), CONNECT, DbType.MSSQL)).isEqualTo("DB_DNS");
        assertThat(c(new SsrfGuard.BlockedException("x"), CONNECT, DbType.MSSQL)).isEqualTo("DB_BLOCKED");
        assertThat(c(new IllegalStateException("x"), QUERY, DbType.MSSQL)).isEqualTo("DB_DRIVER");
        // DNS hatası bir SQL istisnasının içinde bile en güçlü sinyaldir
        assertThat(c(sql("x", "08001", 0, new java.net.UnknownHostException("h")), CONNECT, DbType.POSTGRESQL)).isEqualTo("DB_DNS");
    }

    @Test
    void cyclicCauseChainIsBounded() {
        SQLException a = new SQLException("a", "42000");
        SQLException b = new SQLException("b", "42000");
        a.initCause(b);
        b.initCause(a);
        assertThat(c(a, QUERY, DbType.POSTGRESQL)).isEqualTo("DB_SQL_ERROR");
        assertThat(DbQueryErrors.describe(a)).isEqualTo("a");
    }

    @Test
    void masking() {
        DbConnectionSpec spec = new DbConnectionSpec(DbType.POSTGRESQL, "h", 1, "d", null, null, "Ayse_DBA", "p@ss w0rd!",
                null, false, false, 1_000);
        String raw = "FATAL: password authentication failed for user \"ayse_dba\"\n\tpassword=p@ss w0rd!; "
                + "url=jdbc:x://h?pwd=gizli&x=1  p%40ss+w0rd%21";
        String m = DbQueryErrors.mask(raw, spec);
        assertThat(m).doesNotContain("p@ss").doesNotContain("ayse_dba").doesNotContain("gizli").doesNotContain("p%40ss")
                .doesNotContain("\n").doesNotContain("\t");
        assertThat(m).contains("authentication failed");
        // kullanıcı adı yalnız tam sözcükse maskelenir
        DbConnectionSpec shortUser = new DbConnectionSpec(DbType.POSTGRESQL, "h", 1, "d", null, null, "it", null, null,
                false, false, 1_000);
        assertThat(DbQueryErrors.mask("permitted to it now", shortUser)).isEqualTo("permitted to ***** now");
        assertThat(DbQueryErrors.mask("x".repeat(2_000), null)).hasSize(DbQueryErrors.MAX_ERROR_CHARS);
        assertThat(DbQueryErrors.mask(null, spec)).isNull();
    }
}
