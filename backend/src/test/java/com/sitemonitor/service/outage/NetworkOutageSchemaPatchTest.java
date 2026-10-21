package com.sitemonitor.service.outage;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code network_outage_events} posta sütunları yaması (2026-10-21, çok pod — MP-01) — H2, eski şemadan iki kez. */
class NetworkOutageSchemaPatchTest {

    private final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void tearDown() {
        mp.close();
    }

    @Test
    @DisplayName("Eski şemaya altı posta sütunu NULLABLE eklenir; ikinci koşu hiçbir şey eklemez; eski satır NULL (= gönderilmiş) kalır")
    void addsColumnsIdempotently() {
        mp.jdbc().execute(NetworkOutageTestSchema.LEGACY_DDL);
        mp.jdbc().update("INSERT INTO network_outage_events (detected_at, status) VALUES ('2026-10-01T00:00:00', 'RESOLVED')");
        NetworkOutageSchemaPatch patch = new NetworkOutageSchemaPatch(mp.jdbc());

        assertThat(patch.apply()).isEqualTo(NetworkOutageSchemaPatch.COLUMNS.size());
        for (String c : NetworkOutageSchemaPatch.COLUMNS.keySet()) assertThat(patch.columnExists(c)).as(c).isTrue();
        assertThat(patch.apply()).isZero();
        assertThat(mp.jdbc().queryForObject("SELECT alert_mail_status FROM network_outage_events", String.class)).isNull();
    }

    @Test
    @DisplayName("Tablo yoksa yama atlanır (istisna yok)")
    void noTable_skipped() {
        assertThat(new NetworkOutageSchemaPatch(mp.jdbc()).apply()).isZero();
    }
}
