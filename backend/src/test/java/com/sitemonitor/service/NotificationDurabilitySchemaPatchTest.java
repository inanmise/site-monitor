package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bildirim dayanıklılığı kolonları (2026-10-21, çok pod R1 / C2) — eksik kolonu ekler, ikinci koşu hiçbir şey yapmaz, tablo
 * yoksa atlar; yeni kolonlar NULLABLE (eski satırlar işaretsiz kalır — süpürücü dokunmaz).
 */
class NotificationDurabilitySchemaPatchTest {

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
    @DisplayName("eski şema: kolonlar eklenir, ikinci koşu 0; eski satır NULL işaretle kalır")
    void addsMissingColumnsIdempotently() {
        mp.jdbc().execute("CREATE TABLE alert_events (id BIGINT PRIMARY KEY, domain VARCHAR(255))");
        mp.jdbc().execute("CREATE TABLE login_anomaly_incident (id BIGINT PRIMARY KEY, last_alert_at VARCHAR(40))");
        mp.jdbc().update("INSERT INTO alert_events(id, domain) VALUES (1, 'a')");
        NotificationDurabilitySchemaPatch patch = new NotificationDurabilitySchemaPatch(mp.jdbc());
        assertThat(patch.apply()).isEqualTo(3);
        assertThat(patch.columnExists("alert_events", "resolution_notify")).isTrue();
        assertThat(patch.columnExists("alert_events", "resolution_notify_at")).isTrue();
        assertThat(patch.columnExists("login_anomaly_incident", "last_alert_attempt_at")).isTrue();
        assertThat(patch.apply()).isZero();
        assertThat(mp.jdbc().queryForObject("SELECT resolution_notify FROM alert_events WHERE id = 1", String.class)).isNull();
    }

    @Test
    @DisplayName("tablo yoksa atlanır (hata yok)")
    void missingTablesAreSkipped() {
        assertThat(new NotificationDurabilitySchemaPatch(mp.jdbc()).apply()).isZero();
    }
}
