package com.sitemonitor.service.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * İndeks bakım DDL'inin istemci soket sınırı (kapı incelemesi 2026-11-15). Havuz dışı oturum veri kaynağının
 * {@code socketTimeout}'unu (Helm {@code DB_SOCKET_TIMEOUT_SECONDS}, ör. 1800 sn) kopyalar; 45 dk'lık bir REINDEX istemcide
 * 30. dakikada 08006 ile kesiliyor, bekçi duruyor ve sunucu DDL'i gözetimsiz sürdürüyordu. Pinlenen: her
 * {@code executeBounded} soket sınırını deyim süresi + 60 sn yapar (DDL'den ÖNCE) ve sonra eski değere döndürür; sınır
 * kurulamazsa DDL koşulmaz (08000); bağlantı koparsa (08006) sunucudaki deyim YENİ bağlantıdan iptal edilir; deyim süresi
 * (57014) ya da bekçi iptali yeni bağlantı açtırmaz. PostgreSQL gerektirmez (sahte bağlantı).
 */
class JdbcIndexCatalogNetworkTimeoutTest {

    private static final String DDL = "REINDEX INDEX CONCURRENTLY public.idx_x";
    private static final int PID = 4242;
    private static final long STMT_MS = 45 * 60_000L;

    Connection conn;
    Statement st;
    final List<Integer> cancelled = new CopyOnWriteArrayList<>();

    /** Bekleyen yok, iptal yok — bekçi yalnız yoklar. */
    private static DdlBlockWatch.Probe idleProbe() {
        return new DdlBlockWatch.Probe() {
            @Override public int waitingFor(int pid) { return 0; }
            @Override public void cancel(int pid) { }
            @Override public void close() { }
        };
    }

    private JdbcIndexCatalog catalog() {
        return JdbcIndexCatalog.of(conn, (pid, maxMs) -> DdlBlockWatch.start(idleProbe(), pid, maxMs, 5,
                System::currentTimeMillis), cancelled::add);
    }

    @BeforeEach
    void setUp() throws SQLException {
        conn = mock(Connection.class);
        st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery("SELECT pg_backend_pid()")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(PID);
        when(conn.getNetworkTimeout()).thenReturn(1_800_000);
    }

    @Test
    @DisplayName("soket sınırı DDL'den önce deyim süresi + 60 sn olur, sonra eski değere (1800 sn) döner")
    void socketLimitAboveStatementTimeoutThenRestored() throws Exception {
        catalog().executeBounded(DDL, 30_000, STMT_MS);
        InOrder o = inOrder(conn, st);
        o.verify(conn).setNetworkTimeout(any(), eq((int) (STMT_MS + JdbcIndexCatalog.NETWORK_SLACK_MS)));
        o.verify(st).execute("SET statement_timeout = " + STMT_MS);
        o.verify(st).execute(DDL);
        o.verify(conn).setNetworkTimeout(any(), eq(1_800_000));
        assertThat(cancelled).isEmpty();
        assertThat(JdbcIndexCatalog.networkTimeoutMs(Long.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(JdbcIndexCatalog.networkTimeoutMs(0)).isEqualTo(1 + (int) JdbcIndexCatalog.NETWORK_SLACK_MS);
    }

    @Test
    @DisplayName("bağlantı koparsa (08006) sunucudaki deyim yeni bağlantıdan iptal edilir, hata aynen yükselir")
    void connectionLossCancelsOrphanOnFreshConnection() throws Exception {
        doThrow(new SQLException("An I/O error occurred while sending to the backend.", "08006")).when(st).execute(DDL);
        assertThatThrownBy(() -> catalog().executeBounded(DDL, 30_000, STMT_MS))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(BlockingOthersException.class)
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("08006");
        assertThat(cancelled).containsExactly(PID);
    }

    @Test
    @DisplayName("deyim süresi (57014) yeni bağlantı açtırmaz; iptal edicinin hatası DDL hatasını örtmez")
    void statementTimeoutDoesNotCancelOrphan() throws Exception {
        doThrow(new SQLException("canceling statement due to statement timeout", "57014")).when(st).execute(DDL);
        assertThatThrownBy(() -> catalog().executeBounded(DDL, 30_000, STMT_MS))
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("57014");
        assertThat(cancelled).isEmpty();

        doThrow(new SQLException("io", "08006")).when(st).execute(DDL);
        JdbcIndexCatalog failingCanceller = JdbcIndexCatalog.of(conn, (pid, maxMs) -> DdlBlockWatch.start(idleProbe(), pid,
                maxMs, 5, System::currentTimeMillis), pid -> { throw new SQLException("bağlanılamadı", "08001"); });
        assertThatThrownBy(() -> failingCanceller.executeBounded(DDL, 30_000, STMT_MS))
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("08006");
    }

    @Test
    @DisplayName("soket sınırı kurulamazsa DDL KOŞULMAZ (08000, kopma sayılmaz)")
    void socketLimitFailure_ddlNotRun() throws Exception {
        doThrow(new SQLException("closed", "08003")).when(conn).setNetworkTimeout(any(), anyInt());
        assertThatThrownBy(() -> catalog().executeBounded(DDL, 30_000, STMT_MS))
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("08000");
        verify(st, never()).execute(DDL);
        assertThat(cancelled).isEmpty();
        assertThat(JdbcIndexCatalog.connectionLost(new SQLException("x", "08000"))).isFalse();
        assertThat(JdbcIndexCatalog.connectionLost(new SQLException("x", "08006"))).isTrue();
        assertThat(JdbcIndexCatalog.connectionLost(new SQLException("x", "57014"))).isFalse();
        assertThat(JdbcIndexCatalog.connectionLost(new SQLException("x"))).isFalse();
    }
}
