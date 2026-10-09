package com.sitemonitor.service.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Şema yaması kilit güvenliği (2026-10-09): yerinde olan değişiklik çalıştırılmaz; çalışan ifade lock_timeout ile
 * sınırlı ve oturum ayarı havuza sızmaz. Gerçek PostgreSQL davranışı {@code SchemaDdlGuardPostgresTest}'te.
 */
class SchemaDdlGuardTest {

    private JdbcTemplate jdbc;
    private Connection conn;
    private Statement st;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        DataSource ds = mock(DataSource.class);
        conn = mock(Connection.class);
        st = mock(Statement.class);
        when(jdbc.getDataSource()).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(conn.createStatement()).thenReturn(st);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            ConnectionCallback<?> cb = inv.getArgument(0);
            return cb.doInConnection(conn);
        });
    }

    @Test
    @DisplayName("Kolon tipi hedefle aynıysa (text / character varying(n)) yerinde; farklıysa çalışır")
    void alterType_inPlaceOnlyWhenSame() {
        when(jdbc.queryForList(contains("format_type"), eq(String.class), eq("certificate_inventory"), eq("owner")))
                .thenReturn(List.of("text"), List.of("character varying(255)"));
        SchemaDdlGuard g = new SchemaDdlGuard(jdbc);
        assertThat(g.alreadyInPlace("ALTER TABLE certificate_inventory ALTER COLUMN owner TYPE TEXT")).isTrue();
        assertThat(g.alreadyInPlace("ALTER TABLE certificate_inventory ALTER COLUMN owner TYPE TEXT")).isFalse();

        when(jdbc.queryForList(contains("format_type"), eq(String.class), eq("user_push_deliveries"), eq("push_trigger")))
                .thenReturn(List.of("character varying(40)"), List.of("character varying(20)"));
        assertThat(g.alreadyInPlace("ALTER TABLE user_push_deliveries ALTER COLUMN push_trigger TYPE VARCHAR(40)")).isTrue();
        assertThat(g.alreadyInPlace("ALTER TABLE user_push_deliveries ALTER COLUMN push_trigger TYPE VARCHAR(40)")).isFalse();
    }

    @Test
    @DisplayName("DROP NOT NULL: kolon zaten boş geçilebilirse (ya da kolon yoksa) yerinde; NOT NULL ise çalışır")
    void dropNotNull() {
        when(jdbc.queryForList(contains("attnotnull"), eq(Boolean.class), eq("app_users"), eq("password_hash")))
                .thenReturn(List.of(false), List.of(true), List.of());
        SchemaDdlGuard g = new SchemaDdlGuard(jdbc);
        String ddl = "ALTER TABLE app_users ALTER COLUMN password_hash DROP NOT NULL";
        assertThat(g.alreadyInPlace(ddl)).isTrue();
        assertThat(g.alreadyInPlace(ddl)).isFalse();
        assertThat(g.alreadyInPlace(ddl)).as("kolon yok → eski yolda da sessiz no-op").isTrue();
    }

    @Test
    @DisplayName("DROP CONSTRAINT IF EXISTS: kısıt yoksa yerinde (ALTER yine de kilit alırdı); varsa çalışır")
    void dropConstraint() {
        when(jdbc.queryForObject(contains("pg_constraint"), eq(Integer.class), eq("incident_options"), eq("uk_inc_opt_type_value")))
                .thenReturn(0, 1);
        SchemaDdlGuard g = new SchemaDdlGuard(jdbc);
        String ddl = "ALTER TABLE incident_options DROP CONSTRAINT IF EXISTS uk_inc_opt_type_value";
        assertThat(g.alreadyInPlace(ddl)).isTrue();
        assertThat(g.alreadyInPlace(ddl)).isFalse();
    }

    @Test
    @DisplayName("CREATE [UNIQUE] INDEX [CONCURRENTLY] IF NOT EXISTS: ad katalogda varsa yerinde")
    void createIndex() {
        when(jdbc.queryForObject(contains("to_regclass"), eq(Boolean.class), eq("idx_ae_created_at"))).thenReturn(true, false);
        SchemaDdlGuard g = new SchemaDdlGuard(jdbc);
        assertThat(g.alreadyInPlace("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ae_created_at ON alert_events(created_at)")).isTrue();
        assertThat(g.alreadyInPlace("CREATE INDEX IF NOT EXISTS idx_ae_created_at ON alert_events(created_at)")).isFalse();
    }

    @Test
    @DisplayName("Tanınmayan biçim / katalog hatası / PostgreSQL değil → false (eski davranış: ifade çalışır)")
    void unknownOrFailing_runsAsBefore() throws Exception {
        SchemaDdlGuard g = new SchemaDdlGuard(jdbc);
        assertThat(g.alreadyInPlace("ALTER TABLE x RENAME COLUMN a TO b")).isFalse();
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any())).thenThrow(new RuntimeException("katalog yok"));
        assertThat(g.alreadyInPlace("ALTER TABLE x ALTER COLUMN y TYPE TEXT")).isFalse();
        when(conn.getMetaData().getDatabaseProductName()).thenReturn("H2");
        assertThat(new SchemaDdlGuard(jdbc).alreadyInPlace("ALTER TABLE x ALTER COLUMN y DROP NOT NULL")).isFalse();
    }

    @Test
    @DisplayName("Çalışan ifade AYNI bağlantıda SET lock_timeout → ifade → RESET; hata olsa da RESET (havuza sızmaz)")
    void executeBounded_setsAndResetsLockTimeout() throws Exception {
        SchemaDdlGuard g = new SchemaDdlGuard(jdbc);
        g.executeBounded("ALTER TABLE app_users ALTER COLUMN password_hash DROP NOT NULL", false);
        InOrder o = inOrder(st);
        o.verify(st).execute("SET lock_timeout = '20s'");
        o.verify(st).execute("ALTER TABLE app_users ALTER COLUMN password_hash DROP NOT NULL");
        o.verify(st).execute("RESET lock_timeout");

        when(st.execute("ALTER TABLE t ALTER COLUMN c TYPE TEXT"))
                .thenThrow(new SQLException("canceling statement due to lock timeout", "55P03"));
        assertThatThrownBy(() -> g.executeBounded("ALTER TABLE t ALTER COLUMN c TYPE TEXT", false))
                .satisfies(e -> assertThat(SchemaDdlGuard.isLockTimeout(e)).isTrue());
        verify(st, times(2)).execute("RESET lock_timeout");
    }

    @Test
    @DisplayName("DML etkilenen satır sayısını döner; CONCURRENTLY ve PostgreSQL dışı sınırsız eski yoldan")
    void executeBounded_dmlAndConcurrently() throws Exception {
        when(st.executeUpdate("UPDATE t SET a = 1 WHERE a IS NULL")).thenReturn(3);
        SchemaDdlGuard g = new SchemaDdlGuard(jdbc);
        assertThat(g.executeBounded("UPDATE t SET a = 1 WHERE a IS NULL", true)).isEqualTo(3);

        g.executeBounded("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_x ON t(a)", false);
        verify(jdbc).execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_x ON t(a)");
        verify(st, never()).execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_x ON t(a)");
    }

    @Test
    @DisplayName("isLockTimeout: yalnız SQLSTATE 55P03 (sarılmış da olsa)")
    void isLockTimeout() {
        assertThat(SchemaDdlGuard.isLockTimeout(new UncategorizedSQLException("p", "x", new SQLException("m", "55P03")))).isTrue();
        assertThat(SchemaDdlGuard.isLockTimeout(new UncategorizedSQLException("p", "x", new SQLException("m", "42P07")))).isFalse();
        assertThat(SchemaDdlGuard.isLockTimeout(new RuntimeException("x"))).isFalse();
    }
}
