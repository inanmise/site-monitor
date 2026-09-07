package com.sitemonitor.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DB büyüme gauge'ları: pg_stat_user_tables örneği → gauge değerleri + izlenmeyen tablo atlanır. */
@ExtendWith(MockitoExtension.class)
class DbGrowthMetricsTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock AppSettingsService appSettings;
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private DbGrowthMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new DbGrowthMetrics(registry, jdbcTemplate, appSettings);
        metrics.registerGauges();
    }

    @Test
    @DisplayName("sample: izlenen tablonun satır/boyutu gauge'a yansır; izlenmeyen atlanır")
    void sample_populatesGauges() throws Exception {
        when(appSettings.getInt(eq("site.monitor.db.growth-warn-rows"), anyInt())).thenReturn(5_000_000);
        ResultSet rs = mock(ResultSet.class);
        // 1. satır: izlenen tablo; 2. satır: izlenmeyen (atlanmalı)
        when(rs.getString(1)).thenReturn("port_checks", "some_other_table");
        when(rs.getLong(2)).thenReturn(6_000_000L, 999L);
        when(rs.getLong(3)).thenReturn(12345L, 111L);
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(1);
            h.processRow(rs);   // port_checks
            h.processRow(rs);   // some_other_table
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));

        metrics.sample();

        assertThat(metrics.rows("port_checks")).isEqualTo(6_000_000L);
        assertThat(metrics.bytes("port_checks")).isEqualTo(12345L);
        assertThat(metrics.rows("some_other_table")).isEqualTo(-1L);   // izlenmiyor → kaydedilmedi
        // Gauge /metrics'te doğru değeri verir.
        assertThat(registry.get("db.table.rows").tag("table", "port_checks").gauge().value()).isEqualTo(6_000_000.0);
        assertThat(registry.get("db.table.bytes").tag("table", "port_checks").gauge().value()).isEqualTo(12345.0);
    }

    @Test
    @DisplayName("sample: DB hatası fırlatmaz (Postgres-only sorgu, best-effort)")
    void sample_swallowsDbError() {
        when(appSettings.getInt(anyString(), anyInt())).thenReturn(5_000_000);
        doThrow(new RuntimeException("pg-only")).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));
        metrics.sample();   // fırlatmamalı
        assertThat(metrics.rows("port_checks")).isZero();   // registerGauges 0'la başlattı
    }

    // ── Eşik varsayılanı: properties ↔ kod yedeği ─────────────────────────────

    /**
     * Eşik İKİ yerde yazılı ve ikisi AYNI olmalı — {@code PasswordPolicyDefaultTest} ile aynı
     * gerekçe: {@code application.properties} çalışan uygulamanın değeri, {@code getInt(...)}
     * ikinci argümanı ise ayar silindiğinde/okunamadığında devreye giren yedek. Ayrışırlarsa
     * eşik sessizce eski değerine döner ve kimse fark etmez.
     *
     * <p><b>Değer neden pinleniyor:</b> 5M eşiği {@code activity_log} 90 gün saklanırken
     * konmuştu; 2026-08'de saklama 365 güne çıkarılınca satır sayısı beklendiği gibi ~4 katına
     * çıktı ve eşik üretimde 5 dakikada bir yanmaya başladı. 2026-09'da kullanıcı kararıyla
     * 20M'ye alındı — ölçüm doğruydu, ölçek eskiydi; veri KISALTILMADI. Testin kırılması
     * "yanlış yaptın" demez, "bu eşiği gerçekten değiştirmek istediğine emin misin" der.
     */
    @Test
    @DisplayName("Buyume esigi varsayilani 20M ve properties ile kod yedegi AYNI")
    void growthThresholdDefault_matchesProperties() throws Exception {
        java.util.regex.Matcher p = java.util.regex.Pattern
                .compile("db[.]growth-warn-rows=[$][{]DB_GROWTH_WARN_ROWS:([0-9]+)[}]")
                .matcher(java.nio.file.Files.readString(
                        java.nio.file.Path.of("src/main/resources/application.properties")));
        assertThat(p.find()).as("properties'te esik satiri bulunamadi").isTrue();

        java.util.regex.Matcher c = java.util.regex.Pattern
                .compile("\"site[.]monitor[.]db[.]growth-warn-rows\",\\s*([0-9_]+)")
                .matcher(java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/main/java/com/sitemonitor/service/DbGrowthMetrics.java")));
        assertThat(c.find()).as("koddaki yedek deger bulunamadi").isTrue();

        long fromProps = Long.parseLong(p.group(1));
        long fromCode  = Long.parseLong(c.group(1).replace("_", ""));

        assertThat(fromProps).as("bilincli karar: 20M").isEqualTo(20_000_000L);
        assertThat(fromCode).as("kod yedegi properties ile ayrismis - ayar silinince eski esige doner")
                .isEqualTo(fromProps);
    }
}
