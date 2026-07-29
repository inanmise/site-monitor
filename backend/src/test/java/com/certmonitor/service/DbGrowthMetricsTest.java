package com.certmonitor.service;

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
        when(appSettings.getInt(eq("cert.monitor.db.growth-warn-rows"), anyInt())).thenReturn(5_000_000);
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
}
