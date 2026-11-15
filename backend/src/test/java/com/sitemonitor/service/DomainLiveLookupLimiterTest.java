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

    @Test
    @DisplayName("M1: \"Şimdi kontrol et\" yalnız izleme penceresi — canlı sorguyla ORTAK sayılır, kullanıcı penceresi ve eşzamanlılık reddi yok")
    void monitorWindowOnly() {
        AtomicLong now = new AtomicLong(10_000L);
        var l = new DomainLiveLookupLimiter(now::get);
        var a = l.tryAcquire("a", 1L);   // iki canlı sorgu uçuşta (pod yuvaları dolu)
        var b = l.tryAcquire("b", 2L);
        assertThat(a.allowed() && b.allowed()).isTrue();
        // aynı kullanıcı 20 FARKLI izlemeyi kontrol edebilir (toplu "Şimdi kontrol et") — eşzamanlılık reddi de yok
        for (long id = 100; id < 120; id++) assertThat(l.tryAcquireMonitor(id)).isZero();
        assertThat(l.inFlight()).isEqualTo(2);
        // izleme 1: canlı sorgu 1 + 5 kontrol = 6 → 7. kontrol bekler
        for (int i = 1; i < DomainLiveLookupLimiter.PER_MINUTE; i++) assertThat(l.tryAcquireMonitor(1L)).isZero();
        assertThat(l.tryAcquireMonitor(1L)).isBetween(1L, 60L);
        assertThat(l.tryAcquire("z", 1L).allowed()).isFalse();
        now.addAndGet(60_000L);
        assertThat(l.tryAcquireMonitor(1L)).isZero();
    }

    @Test
    @DisplayName("LOW 5: anahtar tavanında budama boş kayıtları siler; sayım kaybolmaz (aynı izleme yine sınırlı)")
    void pruneKeepsCounting() {
        AtomicLong now = new AtomicLong(0L);
        var l = new DomainLiveLookupLimiter(now::get);
        for (long id = 0; id < DomainLiveLookupLimiter.MAX_KEYS; id++) assertThat(l.tryAcquireMonitor(id)).isZero();
        now.addAndGet(61_000L);   // hepsinin penceresi doldu → sonraki istek budar
        for (int i = 0; i < DomainLiveLookupLimiter.PER_MINUTE; i++) assertThat(l.tryAcquireMonitor(5L)).isZero();
        assertThat(l.keys()).isLessThan(10);
        assertThat(l.tryAcquireMonitor(5L)).isPositive();
    }
}
