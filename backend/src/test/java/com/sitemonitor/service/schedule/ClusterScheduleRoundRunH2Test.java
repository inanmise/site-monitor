package com.sitemonitor.service.schedule;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İki aşamalı tur kaydı (2026-10-21, çok pod R1-A, MP-15) — {@link ClusterScheduleService#claimRun} satırı koşu kirasına
 * ilerletir, {@link ClusterScheduleService#complete} yalnız satır hâlâ o kirada ve o sahipteyse ızgara vadesine taşır.
 */
class ClusterScheduleRoundRunH2Test {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void close() {
        mp.close();
    }

    long due(String key) {
        return mp.jdbc().queryForObject("SELECT next_due_at FROM monitor_check_schedule WHERE monitor_key = ?", Long.class, key);
    }

    @Test
    @DisplayName("koşu sahiplenmesi kirayı yazar; iki pod yarışırsa tek kazanan; tamamlanınca ızgara vadesine geçer")
    void claimRunThenComplete() {
        ClusterScheduleService a = mp.schedule(MultiPodTestSupport.owner(1));
        ClusterScheduleService b = mp.schedule(MultiPodTestSupport.owner(2));
        long now = mp.now();
        long[] prevA = {-1};
        long[] prevB = {-1};
        List<ClusterScheduleService.Claim> claims = mp.race(
                () -> a.claimRun("round:uptime", now, HOUR, null, 10 * MIN, prevA),
                () -> b.claimRun("round:uptime", now, HOUR, null, 10 * MIN, prevB));
        assertThat(claims.stream().filter(ClusterScheduleService.Claim::due)).hasSize(1);
        ClusterScheduleService winner = claims.get(0).due() ? a : b;
        long lease = claims.get(0).due() ? claims.get(0).nextDueAt() : claims.get(1).nextDueAt();
        assertThat(lease).isEqualTo(now + 10 * MIN);
        assertThat(due("round:uptime")).isEqualTo(now + 10 * MIN);
        assertThat((claims.get(0).due() ? prevA : prevB)[0]).as("ilk görüşte önceki vade = şimdi").isEqualTo(now);

        ClusterScheduleService loser = winner == a ? b : a;
        assertThat(loser.complete("round:uptime", lease, now + HOUR)).as("başka sahip tamamlayamaz").isFalse();
        assertThat(winner.complete("round:uptime", lease, now + HOUR)).isTrue();
        assertThat(due("round:uptime")).isEqualTo(now + HOUR);
        assertThat(winner.complete("round:uptime", lease, now + 2 * HOUR)).as("ikinci tamamlama etkisiz").isFalse();
    }

    @Test
    @DisplayName("yarıda kalan koşu (tamamlanmadı): koşu kirası dolmadan kimse sahiplenemez, dolunca başka pod yeniden koşar")
    void abandonedRun_dueAgainAfterRunLease() {
        ClusterScheduleService a = mp.schedule(MultiPodTestSupport.owner(1));
        ClusterScheduleService b = mp.schedule(MultiPodTestSupport.owner(2));
        long t0 = mp.now();
        assertThat(a.claimRun("round:page-crawl", t0, 24 * HOUR, null, 30 * MIN, null).due()).isTrue();
        // a öldü — tamamlamadı
        assertThat(b.claimRun("round:page-crawl", t0 + 29 * MIN, 24 * HOUR, null, 30 * MIN, null).due()).isFalse();
        long[] prev = {-1};
        ClusterScheduleService.Claim again = b.claimRun("round:page-crawl", t0 + 31 * MIN, 24 * HOUR, null, 30 * MIN, prev);
        assertThat(again.due()).isTrue();
        assertThat(prev[0]).as("ızgara önceki kiradan hesaplanır").isEqualTo(t0 + 30 * MIN);
        // eski koşunun geç tamamlaması yeni koşunun kirasını ezmez
        assertThat(a.complete("round:page-crawl", t0 + 30 * MIN, t0 + 24 * HOUR)).isFalse();
        assertThat(due("round:page-crawl")).isEqualTo(again.nextDueAt());
    }

    @Test
    @DisplayName("koşu kirası turun aralığından kısaysa 'aralık kısaltıldı' sanılmaz (kira içinde vadesi gelmemiş sayılır)")
    void runLeaseIsNotMistakenForShortenedInterval() {
        ClusterScheduleService a = mp.schedule(MultiPodTestSupport.owner(1));
        long t0 = mp.now();
        assertThat(a.claimRun("round:cert-stale", t0, 5 * MIN, null, 5 * MIN, null).due()).isTrue();
        assertThat(a.claimRun("round:cert-stale", t0 + MIN, 5 * MIN, null, 5 * MIN, null).due()).isFalse();
        assertThat(a.claim("round:cert-stale", t0 + MIN, 5 * MIN, null, p -> p + 5 * MIN).due()).isFalse();
    }
}
