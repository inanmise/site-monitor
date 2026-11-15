package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** A4 (2026-11-15): alan adı canlı sorgusunun yük sınırı — kullanıcı + izleme başına dakikada 6, pod başına aynı anda 2. */
class DomainLiveLookupLimiterTest {

    @Test
    @DisplayName("Kullanıcı başına dakikada 6; 7. istek bekleme süresiyle reddedilir, dakika dolunca yeniden izin")
    void perUserMinute() {
        AtomicLong now = new AtomicLong(1_000_000L);
        var l = new DomainLiveLookupLimiter(now::get);
        for (int i = 0; i < DomainLiveLookupLimiter.PER_MINUTE; i++) {
            var d = l.tryAcquire("ayse", (long) i);   // farklı izlemeler — yalnız kullanıcı penceresi dolar
            assertThat(d.allowed()).isTrue();
            d.ticket().close();
        }
        var refused = l.tryAcquire("ayse", 99L);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.busy()).isFalse();
        assertThat(refused.retryAfterSeconds()).isBetween(1L, 60L);
        now.addAndGet(60_000L);
        assertThat(l.tryAcquire("ayse", 99L).allowed()).isTrue();
    }

    @Test
    @DisplayName("İzleme başına dakikada 6 — farklı kullanıcılar aynı izlemeyi sınırsız sorgulayamaz; reddedilen istek pencereye yazılmaz")
    void perMonitorMinute() {
        AtomicLong now = new AtomicLong(5_000L);
        var l = new DomainLiveLookupLimiter(now::get);
        for (int i = 0; i < DomainLiveLookupLimiter.PER_MINUTE; i++) {
            var d = l.tryAcquire("user" + i, 7L);
            assertThat(d.allowed()).isTrue();
            d.ticket().close();
        }
        assertThat(l.tryAcquire("baska", 7L).allowed()).isFalse();
        // reddedilen istek "baska"nın penceresine yazılmadı: başka izlemede hemen izin
        assertThat(l.tryAcquire("baska", 8L).allowed()).isTrue();
    }

    @Test
    @DisplayName("Pod başına aynı anda en çok 2; bilet kapanınca yuva geri gelir (çift kapatma zararsız)")
    void podInFlightCap() {
        var l = new DomainLiveLookupLimiter(() -> 0L);
        var a = l.tryAcquire("a", 1L);
        var b = l.tryAcquire("b", 2L);
        var c = l.tryAcquire("c", 3L);
        assertThat(a.allowed()).isTrue();
        assertThat(b.allowed()).isTrue();
        assertThat(c.allowed()).isFalse();
        assertThat(c.busy()).isTrue();
        assertThat(c.retryAfterSeconds()).isPositive();
        a.ticket().close();
        a.ticket().close();
        assertThat(l.inFlight()).isEqualTo(1);
        assertThat(l.tryAcquire("c", 3L).allowed()).isTrue();
    }
}
