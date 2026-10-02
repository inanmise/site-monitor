package com.sitemonitor.service.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Şema yaması çalıştırıcısı (2026-10-01, onaylı öneri 4). Pinlenen sözleşme:
 * <ul>
 *   <li>Davranış eski {@code SchedulerService.patch} ile aynı: kolon/tablo varsa ifade ÇALIŞMAZ; hata açılışı
 *       DURDURMAZ (istisna dışarı sızmaz).</li>
 *   <li>"Zaten var / yok / yinelenen" hataları (SQLSTATE ya da İngilizce mesaj) sessiz; gerçek hata PostgreSQL'de
 *       sayılır ve özette listelenir; PostgreSQL dışında (H2) sayılmaz.</li>
 *   <li>Advisory lock: alınırsa iş biter bitmez — iş hata verse de — bırakılır ve bağlantı kapanır; alınamazsa iş
 *       yine koşar (açılış asla kilitte takılmaz).</li>
 * </ul>
 */
class SchemaPatchRunnerTest {

    private JdbcTemplate jdbc;
    private DataSource ds;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        ds = mock(DataSource.class);
        conn = mock(Connection.class);
        when(jdbc.getDataSource()).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
    }

    private static UncategorizedSQLException sqlError(String state, String msg) {
        return new UncategorizedSQLException("patch", "x", new SQLException(msg, state));
    }

    @Test
    @DisplayName("Kolon zaten varsa ALTER çalışmaz; yoksa çalışır ve 'applied' sayılır")
    void addColumn_skipsWhenPresent() {
        when(jdbc.queryForObject(contains("information_schema.columns"), eq(Integer.class), any(), any()))
                .thenReturn(1, 0);
        var r = new SchemaPatchRunner(jdbc, 10);
        r.patch("ALTER TABLE port_monitors ADD COLUMN deleted_at VARCHAR(30)");
        verify(jdbc, never()).execute(anyString());
        r.patch("ALTER TABLE port_monitors ADD COLUMN deleted_at VARCHAR(30)");
        verify(jdbc).execute("ALTER TABLE port_monitors ADD COLUMN deleted_at VARCHAR(30)");
        var s = r.finish();
        assertThat(s.applied()).isEqualTo(1);
        assertThat(s.noop()).isEqualTo(1);
        assertThat(s.failed()).isZero();
    }

    @Test
    @DisplayName("Beklenen hata (SQLSTATE ya da mesaj) sessiz; gerçek hata PostgreSQL'de sayılır, istisna sızmaz")
    void failures_classified() {
        doThrow(sqlError("42P07", "ilişki zaten mevcut"))                       // Türkçe sunucu mesajı, kod belirleyici
                .doThrow(sqlError(null, "ERROR: index \"x\" already exists"))
                .doThrow(sqlError("42804", "column \"x\" cannot be cast automatically"))
                .when(jdbc).execute(anyString());
        var r = new SchemaPatchRunner(jdbc, 10);
        r.patch("CREATE INDEX idx_a ON t(a)");
        r.patch("CREATE INDEX idx_b ON t(b)");
        r.patch("ALTER TABLE t ALTER COLUMN c TYPE INTEGER");
        var s = r.finish();
        assertThat(s.failed()).isEqualTo(1);
        assertThat(s.noop()).isEqualTo(2);
        assertThat(s.failures()).singleElement().asString().contains("ALTER TABLE t ALTER COLUMN c TYPE INTEGER")
                .contains("cannot be cast");
        assertThat(SchemaPatchRunner.last()).isSameAs(s);
    }

    @Test
    @DisplayName("PostgreSQL dışında (H2) PG'ye özgü yamanın düşmesi başarısızlık sayılmaz")
    void nonPostgres_failureIsNotCounted() throws Exception {
        when(conn.getMetaData().getDatabaseProductName()).thenReturn("H2");
        doThrow(sqlError("42001", "Syntax error")).when(jdbc).execute(anyString());
        var r = new SchemaPatchRunner(jdbc, 10);
        r.patch("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx ON t(a)");
        assertThat(r.finish().failed()).isZero();
    }

    @Test
    @DisplayName("UPDATE: etkilenen satır > 0 'applied', 0 'noop'")
    void dataPatch_countsRows() {
        when(jdbc.update(anyString())).thenReturn(3, 0);
        var r = new SchemaPatchRunner(jdbc, 10);
        r.patch("UPDATE t SET a = 1 WHERE a IS NULL");
        r.patch("UPDATE t SET a = 1 WHERE a IS NULL");
        var s = r.finish();
        assertThat(s.applied()).isEqualTo(1);
        assertThat(s.noop()).isEqualTo(1);
    }

    private PreparedStatement lockStatement(boolean acquired) throws Exception {
        PreparedStatement tryLock = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(acquired);
        when(tryLock.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(tryLock);
        PreparedStatement unlock = mock(PreparedStatement.class);
        when(conn.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlock);
        return unlock;
    }

    @Test
    @DisplayName("Kilit alınır, iş koşar, kilit bırakılır ve bağlantı kapanır — iş hata verse de")
    void runExclusive_releasesLockEvenOnFailure() throws Exception {
        PreparedStatement unlock = lockStatement(true);
        var r = new SchemaPatchRunner(jdbc, 10);
        assertThatThrownBy(() -> r.runExclusive(() -> { throw new IllegalStateException("yama patladı"); }))
                .isInstanceOf(IllegalStateException.class);
        verify(unlock).setLong(1, SchemaPatchRunner.LOCK_KEY);
        verify(unlock).execute();
        verify(conn, atLeastOnce()).close();
        assertThat(r.finish().locked()).isTrue();
    }

    @Test
    @DisplayName("Kilit süresi içinde alınamazsa iş yine koşar (kilitsiz), kilit bırakılmaya çalışılmaz")
    void runExclusive_proceedsWithoutLockAfterWait() throws Exception {
        PreparedStatement unlock = lockStatement(false);
        var r = new SchemaPatchRunner(jdbc, 20);
        AtomicBoolean ran = new AtomicBoolean();
        r.runExclusive(() -> ran.set(true));
        assertThat(ran).isTrue();
        verify(unlock, never()).execute();
        assertThat(r.finish().locked()).isFalse();
    }

    @Test
    @DisplayName("PostgreSQL değilse kilit denenmez, iş doğrudan koşar")
    void runExclusive_nonPostgres_runsDirectly() throws Exception {
        when(conn.getMetaData().getDatabaseProductName()).thenReturn("H2");
        var r = new SchemaPatchRunner(jdbc, 10);
        AtomicBoolean ran = new AtomicBoolean();
        r.runExclusive(() -> ran.set(true));
        assertThat(ran).isTrue();
        verify(conn, never()).prepareStatement(anyString());
    }

    @Test
    @DisplayName("isExpected: SQLSTATE dilden bağımsız; tanınmayan kod + mesaj gerçek hata")
    void isExpected_codes() {
        assertThat(SchemaPatchRunner.isExpected("42701", "sütun zaten var")).isTrue();
        assertThat(SchemaPatchRunner.isExpected("23505", "x")).isTrue();
        assertThat(SchemaPatchRunner.isExpected(null, "relation \"t\" does not exist")).isTrue();
        assertThat(SchemaPatchRunner.isExpected("42804", "cannot be cast")).isFalse();
        assertThat(SchemaPatchRunner.isExpected("53100", "could not extend file: No space left on device")).isFalse();
    }
}
