package com.sitemonitor.service.metrics;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sistem metrikleri kolon yaması (2026-10-23, çok pod R2b, MP-37c): eksik {@code clock_skew_ms} eklenir, ikinci koşu hiçbir şey
 * yapmaz, tablo yoksa atlanır; kolon NULLABLE (eski satırlar / 20.166.0 pod'larının satırları null = ölçülmedi).
 */
class SystemMetricsSchemaPatchTest {

    MultiPodTestSupport mp;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    @Test
    @DisplayName("eski şema: kolon eklenir, ikinci koşu 0; eski satır null kalır")
    void addsMissingColumnIdempotently() {
        mp.jdbc().execute("CREATE TABLE system_metric_samples (id BIGINT PRIMARY KEY, instance_id VARCHAR(80), sampled_at VARCHAR(25))");
        mp.jdbc().update("INSERT INTO system_metric_samples(id, instance_id, sampled_at) VALUES (1, 'pod-a', '2026-10-23T10:00:00')");
        SystemMetricsSchemaPatch patch = new SystemMetricsSchemaPatch(mp.jdbc());
        assertThat(patch.apply()).isEqualTo(2);
        assertThat(patch.columnExists("system_metric_samples", "schema_failed_patches")).isTrue();
        assertThat(patch.columnExists("system_metric_samples", "clock_skew_ms")).isTrue();
        assertThat(patch.apply()).isZero();
        assertThat(mp.jdbc().queryForObject("SELECT clock_skew_ms FROM system_metric_samples WHERE id = 1", Long.class)).isNull();
    }

    @Test
    @DisplayName("tablo yoksa atlanır (hata yok)")
    void missingTableIsSkipped() {
        assertThat(new SystemMetricsSchemaPatch(mp.jdbc()).apply()).isZero();
    }
}
