package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code alert_storms} iki aşamalı kapanış sütunları yaması (2026-10-21, çok pod — inceleme S-1 / F1) — H2, eski şemadan iki kez. */
class StormCloseSchemaPatchTest {

    private final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void tearDown() {
        mp.close();
    }

    @Test
    @DisplayName("Eski şemaya beş kapanış sütunu NULLABLE eklenir; ikinci koşu hiçbir şey eklemez; eski satır NULL (= açık / eski kapanış) kalır")
    void addsColumnsIdempotently() {
        mp.jdbc().execute("CREATE TABLE alert_storms (id BIGINT PRIMARY KEY, scope_key VARCHAR(200) NOT NULL, "
                + "resolved BOOLEAN NOT NULL, resolved_at VARCHAR(30), resolve_reason VARCHAR(30))");
        mp.jdbc().update("INSERT INTO alert_storms (id, scope_key, resolved) VALUES (1, 'TEAM:1', true)");
        StormCloseSchemaPatch patch = new StormCloseSchemaPatch(mp.jdbc());

        assertThat(patch.apply()).isEqualTo(StormCloseSchemaPatch.COLUMNS.size());
        for (String c : StormCloseSchemaPatch.COLUMNS.keySet()) assertThat(patch.columnExists(c)).as(c).isTrue();
        assertThat(patch.apply()).isZero();
        assertThat(mp.jdbc().queryForObject("SELECT close_state FROM alert_storms", String.class)).isNull();
        // StormService'in koşullu kapanış SQL'leri yamalı şemada çalışır
        assertThat(mp.jdbc().update(StormService.SQL_CLOSE_CLAIM, "2026-10-21T00:00:00", "pod", "2026-10-21T00:00:00",
                StormService.RESOLVE_FLOOR, 1L)).as("kapanmış fırtına yeniden sahiplenilmez").isZero();
    }

    @Test
    @DisplayName("Tablo yoksa yama atlanır (istisna yok)")
    void noTable_skipped() {
        assertThat(new StormCloseSchemaPatch(mp.jdbc()).apply()).isZero();
    }
}
