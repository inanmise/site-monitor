package com.sitemonitor.service;

import com.sitemonitor.service.schema.SchemaPatchRunner;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ayarlar → Veritabanı Bilgileri (2026-10-09 yeniden tasarım). Kilitlenen sözleşme: mevcut alanlar aynı adla döner;
 * yeni alanlar (saat dilimi, tablo sayısı, SSL, havuz süreleri, şema yaması SAYILARI) eklenir; JDBC URL'indeki her gizli
 * değer maskelenir; şema yamalarının hata metinleri ve SQL'leri İSTEMCİYE GİTMEZ; bir sorgu düşerse diğerleri yine döner.
 */
class DatabaseInfoServiceTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static DatabaseInfoService service(JdbcTemplate jdbc, javax.sql.DataSource ds) {
        DatabaseInfoService s = new DatabaseInfoService(jdbc, ds);
        s.schemaSummary = () -> new SchemaPatchRunner.Summary(4, 512, 1,
                List.of("ALTER TABLE gizli_tablo ADD COLUMN x — ERROR: permission denied for relation gizli_tablo"),
                true, 1_760_000_000_000L);
        return s;
    }

    @Test
    @DisplayName("Alanlar: eski adlar korunur + saat dilimi, tablo sayısı, SSL, havuz süreleri, şema yaması sayıları")
    void returnsExistingAndNewFields() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenAnswer(i -> {
            String sql = i.getArgument(0, String.class);
            if (sql.contains("current_database()") && sql.startsWith("SELECT current_database")) return "appdb";
            if (sql.contains("version()")) return "PostgreSQL 16.4 on x86_64-pc-linux-gnu, compiled by gcc 12.2, 64-bit";
            if (sql.contains("TimeZone")) return "Europe/Istanbul";
            if (sql.contains("pg_stat_ssl")) return "TLSv1.3";
            if (sql.contains("pg_size_pretty")) return "512 MB";
            return null;
        });
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenAnswer(i -> {
            String sql = i.getArgument(0, String.class);
            if (sql.contains("pg_stat_user_tables")) return 87;
            if (sql.contains("pg_stat_activity")) return 12;
            if (sql.contains("inet_server_port")) return 5432;
            return null;
        });
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(Boolean.TRUE);

        HikariDataSource ds = mock(HikariDataSource.class);
        Connection con = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(con);
        when(con.getMetaData()).thenReturn(md);
        when(md.getURL()).thenReturn("jdbc:postgresql://db.example.com:5432/appdb?user=app&password=s3cr3t&sslmode=require");
        when(md.getDriverName()).thenReturn("PostgreSQL JDBC Driver");
        when(md.getDriverVersion()).thenReturn("42.7.4");
        HikariPoolMXBean mx = mock(HikariPoolMXBean.class);
        when(ds.getHikariPoolMXBean()).thenReturn(mx);
        when(mx.getActiveConnections()).thenReturn(3);
        when(mx.getIdleConnections()).thenReturn(7);
        when(mx.getTotalConnections()).thenReturn(10);
        when(mx.getThreadsAwaitingConnection()).thenReturn(0);
        when(ds.getPoolName()).thenReturn("SiteMonitorPool");
        when(ds.getMaximumPoolSize()).thenReturn(20);
        when(ds.getMinimumIdle()).thenReturn(5);
        when(ds.getConnectionTimeout()).thenReturn(30_000L);
        when(ds.getIdleTimeout()).thenReturn(600_000L);
        when(ds.getMaxLifetime()).thenReturn(1_800_000L);

        Map<String, Object> out = service(jdbc, ds).getInfo();

        // Mevcut alanlar (adları değişmedi)
        assertThat(out).containsKeys("database", "user", "session_user", "server_addr", "server_port", "version",
                "version_full", "encoding", "collation", "size", "start_time", "uptime", "server_time", "max_connections",
                "active_connections", "jdbc_url", "driver_name", "driver_version", "pool");
        assertThat(out.get("version")).isEqualTo("PostgreSQL 16.4");
        assertThat(out.get("size")).isEqualTo("512 MB");
        assertThat(out.get("active_connections")).isEqualTo(12);
        // Yeni alanlar
        assertThat(out.get("timezone")).isEqualTo("Europe/Istanbul");
        assertThat(out.get("table_count")).isEqualTo(87);
        assertThat(out.get("ssl")).isEqualTo(Boolean.TRUE);
        assertThat(out.get("ssl_version")).isEqualTo("TLSv1.3");
        // Parola maskeli, gerisi aynen
        assertThat((String) out.get("jdbc_url"))
                .isEqualTo("jdbc:postgresql://db.example.com:5432/appdb?user=app&password=***&sslmode=require")
                .doesNotContain("s3cr3t");

        Map<String, Object> pool = map(out.get("pool"));
        assertThat(pool).containsEntry("name", "SiteMonitorPool").containsEntry("active", 3).containsEntry("idle", 7)
                .containsEntry("total", 10).containsEntry("waiting", 0).containsEntry("max_size", 20).containsEntry("min_idle", 5)
                .containsEntry("connection_timeout_ms", 30_000L).containsEntry("idle_timeout_ms", 600_000L)
                .containsEntry("max_lifetime_ms", 1_800_000L);

        Map<String, Object> schema = map(out.get("schema_patches"));
        assertThat(schema).containsEntry("applied", 4).containsEntry("noop", 512).containsEntry("failed", 1)
                .containsEntry("locked", true).containsKey("finished_at");
        assertThat(schema.get("finished_at")).asString().endsWith("Z");
        // Hata metni / SQL istemciye gitmez
        assertThat(schema).doesNotContainKey("failures");
        assertThat(out.toString()).doesNotContain("permission denied").doesNotContain("gizli_tablo");
    }

    @Test
    @DisplayName("Sorgular düşerse (H2 / yetki yok) alanlar null döner, istisna yayılmaz; havuz ve şema özeti yine gelir")
    void failingQueries_degradeToNull() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(Class.class))).thenThrow(new RuntimeException("relation pg_stat_ssl does not exist"));
        javax.sql.DataSource ds = mock(javax.sql.DataSource.class);
        when(ds.getConnection()).thenThrow(new java.sql.SQLException("connection refused db.example.com"));

        DatabaseInfoService s = new DatabaseInfoService(jdbc, ds);
        s.schemaSummary = () -> null;   // yamalar henüz koşmadı
        Map<String, Object> out = s.getInfo();

        assertThat(out.get("database")).isNull();
        assertThat(out.get("ssl")).isNull();
        assertThat(out.get("timezone")).isNull();
        assertThat(out).doesNotContainKey("jdbc_url");
        assertThat(map(out.get("pool"))).isEmpty();
        assertThat(map(out.get("schema_patches"))).containsEntry("pending", true);
        assertThat(out.toString()).doesNotContain("connection refused").doesNotContain("does not exist");
    }

    @Test
    @DisplayName("JDBC URL maskesi: password, sslpassword, passwd, pwd, secret, token, *key ve kullanıcı:parola@ biçimi")
    void sanitizeUrl_masksEverySecret() {
        assertThat(DatabaseInfoService.sanitizeUrl(null)).isNull();
        assertThat(DatabaseInfoService.sanitizeUrl("jdbc:postgresql://db.example.com:5432/appdb"))
                .isEqualTo("jdbc:postgresql://db.example.com:5432/appdb");
        assertThat(DatabaseInfoService.sanitizeUrl("jdbc:postgresql://h.example.com/db?PASSWORD=Abc&sslpassword=kp&ssl=true"))
                .isEqualTo("jdbc:postgresql://h.example.com/db?PASSWORD=***&sslpassword=***&ssl=true");
        assertThat(DatabaseInfoService.sanitizeUrl("jdbc:x://h.example.com/db;pwd=p1;passwd=p2;secret=p3;token=p4;apikey=p5"))
                .isEqualTo("jdbc:x://h.example.com/db;pwd=***;passwd=***;secret=***;token=***;apikey=***");
        assertThat(DatabaseInfoService.sanitizeUrl("jdbc:postgresql://app:hunter2@db.example.com:5432/appdb"))
                .isEqualTo("jdbc:postgresql://app:***@db.example.com:5432/appdb");
        // Çoklu sunucu + port: kullanıcı bilgisi yok → dokunulmaz
        assertThat(DatabaseInfoService.sanitizeUrl("jdbc:postgresql://h1.example.com:5432,h2.example.com:5433/appdb?targetServerType=primary"))
                .isEqualTo("jdbc:postgresql://h1.example.com:5432,h2.example.com:5433/appdb?targetServerType=primary");
        // "user=" parolaya benzemez — kullanıcı adı gizlenmez (sayfa zaten bağlantı kullanıcısını gösterir)
        assertThat(DatabaseInfoService.sanitizeUrl("jdbc:postgresql://h.example.com/db?user=app"))
                .isEqualTo("jdbc:postgresql://h.example.com/db?user=app");
    }
}
