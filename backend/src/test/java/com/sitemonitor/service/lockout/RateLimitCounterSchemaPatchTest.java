package com.sitemonitor.service.lockout;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Ortak oran sınırı tablosunun açılış yaması (2026-10-23, çok pod Sürüm 2a — MP-31): idempotent, H2'de gerçek DDL. */
class RateLimitCounterSchemaPatchTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void tearDown() {
        mp.close();
    }

    @Test
    @DisplayName("Tablo yoksa kurar (+ indeks); ikinci koşu tabloyu yeniden kurmaz; servis hemen yazabilir")
    void createsTableIdempotently() {
        RateLimitCounterSchemaPatch patch = new RateLimitCounterSchemaPatch(mp.jdbc());
        assertThat(patch.tableExists()).isFalse();
        assertThat(patch.apply()).isEqualTo(2);
        assertThat(patch.tableExists()).isTrue();
        assertThat(patch.apply()).as("tablo var — yalnız IF NOT EXISTS indeks (H2'de katalog ön denetimi yok)").isEqualTo(1);

        RateLimitCounterService svc = new RateLimitCounterService(mp.jdbc(), mp.clockSupplier());
        String key = RateLimitCounterService.key("login-ip", "10.0.0.5");
        assertThat(svc.hit(key, new RateLimitCounterService.Limit(60_000L, 1, 30_000L)).memory()).isFalse();
        assertThat(svc.blockedSeconds(key)).isEqualTo(30L);
        Integer idx = mp.jdbc().queryForObject("SELECT count(*) FROM information_schema.indexes "
                + "WHERE lower(index_name) = 'idx_rlc_expires'", Integer.class);
        assertThat(idx).isPositive();
    }
}
