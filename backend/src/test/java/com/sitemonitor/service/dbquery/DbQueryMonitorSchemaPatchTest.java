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
}
