package com.sitemonitor.service;

import com.sitemonitor.controller.PublicHealthController;
import com.sitemonitor.service.schema.SchemaPatchRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dışarıdan çağrılan veritabanı sağlık kontrolü (2026-10-08, kullanıcı isteği). Kilitlenen sözleşme: durum UP /
 * DEGRADED / DOWN; DOWN → HTTP 503; gövdede gizli bilgi (hata metni, sunucu adı) YOK; kontrol süre sınırlı; sonuç
 * önbellekli (dış izleyici veritabanına yük bindiremez).
 */
class DatabaseHealthServiceTest {

    private static final SchemaPatchRunner.Summary SCHEMA_OK =
            new SchemaPatchRunner.Summary(3, 500, 0, List.of(), true, 1L);

    /** PostgreSQL gibi davranan bağlantı: recovery / transaction_read_only kontrol edilebilir. */
    private static DataSource pgLike(boolean inRecovery, String txReadOnly, AtomicInteger connections) throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection con = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet one = mock(ResultSet.class);
        ResultSet ro = mock(ResultSet.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenAnswer(i -> { connections.incrementAndGet(); return con; });
        when(con.createStatement()).thenReturn(st);
        when(con.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(st.executeQuery(anyString())).thenAnswer(i -> i.getArgument(0, String.class).contains("pg_is_in_recovery") ? ro : one);
        when(one.next()).thenReturn(true);
        when(ro.next()).thenReturn(true);
        when(ro.getBoolean(1)).thenReturn(inRecovery);
        when(ro.getString(2)).thenReturn(txReadOnly);
        return ds;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checks(DatabaseHealthService.Snapshot s) {
        return (Map<String, Object>) s.body().get("checks");
    }

    @SuppressWarnings("unchecked")
    private static String statusOf(DatabaseHealthService.Snapshot s, String check) {
        return String.valueOf(((Map<String, Object>) checks(s).get(check)).get("status"));
    }

    @Test
    @DisplayName("her şey yolunda → UP; bağlantı, sorgu, yazılabilirlik, havuz, şema kontrolleri gövdede")
    void healthy_isUp() throws Exception {
        var svc = new DatabaseHealthService(pgLike(false, "off", new AtomicInteger()), 0, 4000, 1000, () -> SCHEMA_OK);
        var s = svc.current();
        assertThat(s.status()).isEqualTo("UP");
        assertThat(s.body()).containsEntry("status", "UP").containsEntry("component", "database").containsKey("checked_at");
        assertThat(checks(s)).containsOnlyKeys("connection", "query", "writable", "pool", "schema");
        assertThat(statusOf(s, "writable")).isEqualTo("UP");
        assertThat(statusOf(s, "schema")).isEqualTo("UP");
    }

    @Test
    @DisplayName("salt-okunur / yedek (recovery) sunucu → DOWN (uygulama yazamaz), hata kodu READ_ONLY")
    void readOnly_isDown() throws Exception {
        for (var c : List.of(new Object[]{ true, "off" }, new Object[]{ false, "on" })) {
            var svc = new DatabaseHealthService(pgLike((Boolean) c[0], (String) c[1], new AtomicInteger()), 0, 4000, 1000, () -> SCHEMA_OK);
            var s = svc.current();
            assertThat(s.status()).isEqualTo("DOWN");
            assertThat(statusOf(s, "writable")).isEqualTo("DOWN");
        }
    }

    @Test
    @DisplayName("bağlantı alınamıyor → DOWN + CONNECTION_FAILED; istisna metni (sunucu adı, kullanıcı) gövdeye SIZMAZ")
    void connectionFailure_isDown_withoutLeakingMessage() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("Connection to db-prod-01.example.internal:5432 refused for user sitemonitor"));
        var s = new DatabaseHealthService(ds, 0, 4000, 1000, () -> SCHEMA_OK).current();
        assertThat(s.status()).isEqualTo("DOWN");
        assertThat(checks(s).get("connection")).isEqualTo(Map.of("status", "DOWN", "error", "CONNECTION_FAILED"));
        assertThat(s.body().toString()).doesNotContain("db-prod-01").doesNotContain("sitemonitor").doesNotContain("5432");
    }

    @Test
    @DisplayName("asılı veritabanı: kontrol süre sınırında biter → DOWN + TIMEOUT (çağıran beklemez)")
    void hungDatabase_timesOut() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(i -> { Thread.sleep(10_000); return null; });
        long t0 = System.currentTimeMillis();
        var s = new DatabaseHealthService(ds, 0, 600, 1000, () -> SCHEMA_OK).current();
        assertThat(System.currentTimeMillis() - t0).isLessThan(3_000);
        assertThat(s.status()).isEqualTo("DOWN");
        assertThat(checks(s).get("connection")).isEqualTo(Map.of("status", "DOWN", "error", "TIMEOUT"));
    }

    @Test
    @DisplayName("önbellek: süre içindeki ikinci çağrı veritabanına GİTMEZ, cached=true")
    void cached_withinWindow() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        var svc = new DatabaseHealthService(pgLike(false, "off", connections), 60_000, 4000, 1000, () -> SCHEMA_OK);
        assertThat(svc.current().body()).containsEntry("cached", false);
        assertThat(svc.current().body()).containsEntry("cached", true);
        assertThat(connections).hasValue(1);
    }

    @Test
    @DisplayName("başarısız şema yaması → DEGRADED (HTTP 200); şema henüz koşmadı → UP + pending")
    void schemaFailures_degrade() throws Exception {
        var failed = new SchemaPatchRunner.Summary(1, 500, 2, List.of("x", "y"), true, 1L);
        var s = new DatabaseHealthService(pgLike(false, "off", new AtomicInteger()), 0, 4000, 1000, () -> failed).current();
        assertThat(s.status()).isEqualTo("DEGRADED");
        assertThat(checks(s).get("schema")).isEqualTo(Map.of("status", "DEGRADED", "failed_patches", 2));
        var pending = new DatabaseHealthService(pgLike(false, "off", new AtomicInteger()), 0, 4000, 1000, () -> null).current();
        assertThat(pending.status()).isEqualTo("UP");
        assertThat(checks(pending).get("schema")).isEqualTo(Map.of("status", "UP", "pending", true));
    }

    @Test
    @DisplayName("uç: UP/DEGRADED → 200, DOWN → 503; her yanıt no-store")
    void endpoint_statusCodes_noStore() throws Exception {
        var up = new PublicHealthController(new DatabaseHealthService(pgLike(false, "off", new AtomicInteger()), 0, 4000, 1000, () -> SCHEMA_OK)).database();
        assertThat(up.getStatusCode().value()).isEqualTo(200);
        assertThat(up.getHeaders().getCacheControl()).contains("no-store");
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("down"));
        var down = new PublicHealthController(new DatabaseHealthService(broken, 0, 4000, 1000, () -> SCHEMA_OK)).database();
        assertThat(down.getStatusCode().value()).isEqualTo(503);
        assertThat(down.getBody()).containsEntry("status", "DOWN");
        assertThat(down.getHeaders().getCacheControl()).contains("no-store");
    }
}
