package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod R2a paketi D1 kolonları (2026-10-22) — eksik kolonu ekler, ikinci koşu hiçbir şey yapmaz, tablo yoksa atlar; yeni
 * kolonlar NULLABLE (eski satır: damga yok, sayaç 0).
 */
class ReportIntegritySchemaPatchTest {

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
    @DisplayName("eski şema: tech_error_reminders.inflight_at + tls_grade_changes.notify_attempts + audit_log.seq_locked eklenir, ikinci koşu 0; eski satır NULL")
    void addsMissingColumnsIdempotently() {
        mp.jdbc().execute("CREATE TABLE tech_error_reminders (id BIGINT PRIMARY KEY, kind VARCHAR(20))");
        mp.jdbc().execute("CREATE TABLE tls_grade_changes (id BIGINT PRIMARY KEY, notify_state VARCHAR(60))");
        mp.jdbc().execute("CREATE TABLE audit_log (id BIGINT PRIMARY KEY, seq BIGINT)");
        mp.jdbc().update("INSERT INTO tech_error_reminders(id, kind) VALUES (1, 'PERIOD')");
        ReportIntegritySchemaPatch patch = new ReportIntegritySchemaPatch(mp.jdbc());
        assertThat(patch.apply()).isEqualTo(3);
        assertThat(patch.columnExists("tech_error_reminders", "inflight_at")).isTrue();
        assertThat(patch.columnExists("tls_grade_changes", "notify_attempts")).isTrue();
        assertThat(patch.columnExists("audit_log", "seq_locked")).isTrue();
        assertThat(patch.apply()).isZero();
        assertThat(mp.jdbc().queryForObject("SELECT inflight_at FROM tech_error_reminders WHERE id = 1", String.class)).isNull();
    }

    @Test
    @DisplayName("tablo yoksa atlanır (hata yok)")
    void missingTablesAreSkipped() {
        assertThat(new ReportIntegritySchemaPatch(mp.jdbc()).apply()).isZero();
    }
}
