package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alan adı hatırlatmasının sahiplenme belirteci kolonu (2026-10-23, çok pod R2a · D2, MP-45) — eksik kolonu ekler, ikinci koşu
 * hiçbir şey yapmaz, tablo yoksa atlar; kolon NULLABLE (eski satırlar NULL kalır → tekil indeksin dışında).
 */
class DomainReminderSchemaPatchTest {

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
    @DisplayName("eski şema: claim_token eklenir, ikinci koşu no-op; eski satır NULL kalır; tablo yoksa atlar")
    void addsColumnIdempotently() {
        DomainReminderSchemaPatch patch = new DomainReminderSchemaPatch(mp.jdbc());
        assertThat(patch.apply()).as("tablo yok").isFalse();

        mp.jdbc().execute("CREATE TABLE domain_expiry_reminders (id BIGINT PRIMARY KEY, monitor_id BIGINT, expiry_date VARCHAR(40), "
                + "threshold_days INT, status VARCHAR(24), sent_at VARCHAR(30))");
        mp.jdbc().update("INSERT INTO domain_expiry_reminders VALUES (1, 7, '2027-01-01', 7, 'SENT', '2026-10-01T00:00:00')");
        assertThat(patch.apply()).isTrue();
        assertThat(patch.columnExists()).isTrue();
        assertThat(patch.apply()).isFalse();
        assertThat(mp.jdbc().queryForObject("SELECT claim_token FROM domain_expiry_reminders WHERE id = 1", String.class)).isNull();
    }
}
