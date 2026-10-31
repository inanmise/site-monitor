package com.sitemonitor.service.dbquery;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DbQueryMonitorSchemaPatch} (inceleme 2026-10-31, K1) — H2 tarafı: ifade indeksi PostgreSQL'e özgüdür, H2'de hiçbir
 * ifade çalışmaz (yarışı denetleyicinin ad kilidi keser); türü okunamayan veritabanında da bu açılışta hiçbir şey yapılmaz
 * (asla "PostgreSQL değil → bitti"). Çift ad sorgusu taşınabilir. PostgreSQL'de indeks + yarış: {@code DbQueryNamePostgresTest}.
 */
class DbQueryMonitorSchemaPatchTest {

    MultiPodTestSupport mp;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute("CREATE TABLE IF NOT EXISTS dbquery_monitors (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL)");
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    @Test
    @DisplayName("H2: yama hiçbir ifade çalıştırmaz (indeks yok); çift ad listesi taşınabilir SQL ile okunur")
    void h2DoesNothingAndDuplicatesQueryIsPortable() {
        JdbcTemplate jdbc = mp.jdbc();
        jdbc.update("INSERT INTO dbquery_monitors (id, name) VALUES (1, 'Bekleyen siparisler'), (2, 'BEKLEYEN SIPARISLER'), (3, 'Tekil')");
        DbQueryMonitorSchemaPatch patch = new DbQueryMonitorSchemaPatch(jdbc);
        assertThat(patch.apply()).isFalse();
        assertThat(patch.duplicates()).hasSize(1);
        // H2'de ifade indeksi kurulmadı → çift ad (uygulama dışından) yine yazılabilir; karar uygulamada
        jdbc.update("INSERT INTO dbquery_monitors (id, name) VALUES (4, 'tekil')");
    }

    @Test
    @DisplayName("Tür okunamadı (bağlantı alınamadı) → bu açılışta atlanır, istisna yok, hiçbir ifade çalışmaz")
    void unknownDialectSkips() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("havuz dolu"));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(ds);
        assertThat(new DbQueryMonitorSchemaPatch(jdbc).apply()).isFalse();
        verify(jdbc, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Kalıcı adlar: indeks adı ux_dbqm_name_lower; DDL SchemaDdlGuard'ın tanıdığı CREATE UNIQUE INDEX IF NOT EXISTS biçiminde")
    void permanentNames() {
        assertThat(DbQueryMonitorSchemaPatch.NAME_INDEX).isEqualTo("ux_dbqm_name_lower");
        assertThat(DbQueryMonitorSchemaPatch.DDL)
                .startsWith("CREATE UNIQUE INDEX IF NOT EXISTS ux_dbqm_name_lower ON dbquery_monitors")
                .contains("LOWER(name)");
    }

    @Test
    @DisplayName("r177: eski tablolara yeni kolonlar eklenir (idempotent); eski satırlar NULL okunur, kırılmaz")
    void r177ColumnsAddedIdempotently() {
        JdbcTemplate jdbc = mp.jdbc();
        jdbc.execute("CREATE TABLE IF NOT EXISTS db_connection_profiles (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        jdbc.update("INSERT INTO dbquery_monitors (id, name) VALUES (7, 'Eski izleme')");
        jdbc.update("INSERT INTO db_connection_profiles (id, name) VALUES (3, 'Eski bağlantı')");
        DbQueryMonitorSchemaPatch patch = new DbQueryMonitorSchemaPatch(jdbc);
        assertThat(patch.ensureColumns()).isEqualTo(DbQueryMonitorSchemaPatch.COLUMNS.size());
        assertThat(patch.ensureColumns()).isZero();
        assertThat(jdbc.queryForMap("SELECT mail_columns_mode, unknown_runs FROM dbquery_monitors WHERE id = 7"))
                .containsEntry("MAIL_COLUMNS_MODE", null).containsEntry("UNKNOWN_RUNS", null);
        assertThat(jdbc.queryForMap("SELECT oracle_tcps, last_privileges_json FROM db_connection_profiles WHERE id = 3"))
                .containsEntry("ORACLE_TCPS", null).containsEntry("LAST_PRIVILEGES_JSON", null);
        jdbc.update("UPDATE dbquery_monitors SET unknown_runs = COALESCE(unknown_runs, 0) + 1 WHERE id = 7");
        assertThat(jdbc.queryForObject("SELECT unknown_runs FROM dbquery_monitors WHERE id = 7", Integer.class)).isEqualTo(1);
    }
}
