package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Görsel uçlarının yük sınırı: dakika penceresi (kayan), kişi başına ve pod başına eşzamanlılık; reddedilen istek sayılmaz;
 * hak kapatılınca yuva geri gelir (iki kez kapatmak zararsız); harita sınırlı.
 */
class ScanImageLimiterTest {

    final AtomicLong now = new AtomicLong(1_000_000L);

    @Test
    @DisplayName("kişi başına 2, pod başına 4 eşzamanlı: fazlası 'meşgul'; hak bırakılınca yeniden alınır; çift kapatma yuvayı iki kez bırakmaz")
    void concurrency() {
        ScanImageLimiter l = new ScanImageLimiter(60, 2, 4, now::get);
        ScanImageLimiter.Decision a1 = l.tryAcquire("u:1");
        ScanImageLimiter.Decision a2 = l.tryAcquire("u:1");
        assertThat(a1.allowed()).isTrue();
        assertThat(a2.allowed()).isTrue();
        ScanImageLimiter.Decision a3 = l.tryAcquire("u:1");
        assertThat(a3.allowed()).isFalse();
        assertThat(a3.busy()).as("kişinin uçuştaki işi dolu").isTrue();
        assertThat(l.freePodSlots()).isEqualTo(2);

        ScanImageLimiter.Decision b1 = l.tryAcquire("u:2");
        ScanImageLimiter.Decision b2 = l.tryAcquire("u:2");
        assertThat(b1.allowed() && b2.allowed()).isTrue();
        assertThat(l.freePodSlots()).isZero();
        ScanImageLimiter.Decision c1 = l.tryAcquire("u:3");
        assertThat(c1.allowed()).isFalse();
        assertThat(c1.busy()).as("pod dolu").isTrue();

        a1.ticket().close();
        a1.ticket().close();   // ikinci kapatma zararsız
        assertThat(l.freePodSlots()).isEqualTo(1);
        assertThat(l.tryAcquire("u:3").allowed()).isTrue();
        assertThat(l.freePodSlots()).isZero();
        // u:1'in bir işi hâlâ sürüyor (a2): bir tane daha alabilir ama pod dolu
        assertThat(l.tryAcquire("u:1").busy()).isTrue();
    }

    @Test
    @DisplayName("dakikada N: N kabulden sonra RATE + bekleme saniyesi; reddedilen (meşgul / sınır) istek pencereye girmez; pencere kayar")
    void perMinute() {
        ScanImageLimiter l = new ScanImageLimiter(3, 1, 4, now::get);
        ScanImageLimiter.Decision first = l.tryAcquire("u:1");
        assertThat(first.allowed()).isTrue();
        // Uçuştayken gelen istekler meşgul döner ve SAYILMAZ
        for (int i = 0; i < 10; i++) assertThat(l.tryAcquire("u:1").busy()).isTrue();
        first.ticket().close();
        now.addAndGet(10_000);
        try (ScanImageLimiter.Ticket t = l.tryAcquire("u:1").ticket()) { assertThat(t).isNotNull(); }
        now.addAndGet(10_000);
        try (ScanImageLimiter.Ticket t = l.tryAcquire("u:1").ticket()) { assertThat(t).isNotNull(); }
        ScanImageLimiter.Decision limited = l.tryAcquire("u:1");
        assertThat(limited.allowed()).isFalse();
        assertThat(limited.busy()).isFalse();
        assertThat(limited.retryAfterSeconds()).as("ilk kabulden 60 sn sonra").isEqualTo(40);
        // Sınırdaki istekler de sayılmaz: bekleme uzamaz
        for (int i = 0; i < 5; i++) assertThat(l.tryAcquire("u:1").retryAfterSeconds()).isEqualTo(40);
        assertThat(l.freePodSlots()).as("reddedilen istek yuva tutmaz").isEqualTo(4);
        // Başka kişi etkilenmez
        assertThat(l.tryAcquire("u:2").allowed()).isTrue();
        now.addAndGet(40_000);
        assertThat(l.tryAcquire("u:1").allowed()).as("pencere kaydı").isTrue();

        // Saat geri alınırsa pencere sıfırlanır: kişi saat farkı kadar kilitli kalmaz, bekleme en çok 60 sn
        ScanImageLimiter c = new ScanImageLimiter(1, 0, 0, now::get);
        assertThat(c.tryAcquire("u:9").allowed()).isTrue();
        assertThat(c.tryAcquire("u:9").allowed()).isFalse();
        now.addAndGet(-3_600_000);
        assertThat(c.tryAcquire("u:9").allowed()).as("saat bir saat geri").isTrue();
        ScanImageLimiter.Decision again = c.tryAcquire("u:9");
        assertThat(again.allowed()).isFalse();
        assertThat(again.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("refund: kabul geri alınır — hak bırakılır ve dakika damgası silinir; ikinci çağrı / kapatılmış hakta çağrı damga silmez")
    void refund() {
        ScanImageLimiter l = new ScanImageLimiter(2, 1, 1, now::get);
        ScanImageLimiter.Ticket t = l.tryAcquire("u:1").ticket();
        assertThat(l.recentStarts("u:1")).isEqualTo(1);
        t.refund();
        assertThat(l.recentStarts("u:1")).isZero();
        assertThat(l.freePodSlots()).isEqualTo(1);
        t.refund();
        t.close();
        assertThat(l.freePodSlots()).as("çift bırakma yuvayı iki kez vermez").isEqualTo(1);
        for (int i = 0; i < 5; i++) l.tryAcquire("u:1").ticket().refund();
        assertThat(l.recentStarts("u:1")).as("geri alınanlar sayılmaz").isZero();
        ScanImageLimiter.Ticket a = l.tryAcquire("u:1").ticket();
        a.close();
        l.tryAcquire("u:1").ticket().close();
        assertThat(l.tryAcquire("u:1").allowed()).as("dakikada 2").isFalse();
        a.refund();
        assertThat(l.recentStarts("u:1")).as("kapatılmış hak geri alınamaz").isEqualTo(2);
    }

    @Test
    @DisplayName("eşzamanlılık sınırı olmayan kullanım (projeksiyon): yalnız dakika penceresi; anahtarsız kişi tek kovada")
    void rateOnly() {
        ScanImageLimiter l = new ScanImageLimiter(2, 0, 0, now::get);
        assertThat(l.freePodSlots()).isEqualTo(-1);
        assertThat(l.tryAcquire(null).allowed()).isTrue();
        assertThat(l.tryAcquire(" ").allowed()).isTrue();
        ScanImageLimiter.Decision d = l.tryAcquire(null);
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("harita sınırlı: dolunca boşta kalanlar budanır, uçuştaki iş budanmaz; yine doluysa yeni kişiler ortak kovayı paylaşır")
    void boundedMap() {
        ScanImageLimiter l = new ScanImageLimiter(60, 2, 0, now::get);
        List<ScanImageLimiter.Ticket> held = new ArrayList<>();
        held.add(l.tryAcquire("u:tutan").ticket());
        for (int i = 0; i < ScanImageLimiter.MAX_USERS - 1; i++) l.tryAcquire("u:" + i).ticket().close();
        assertThat(l.trackedUsers()).isEqualTo(ScanImageLimiter.MAX_USERS);
        // Pencere dolmadan yeni kişi: budanacak boş kayıt yok → ortak kova (harita büyümez)
        assertThat(l.tryAcquire("u:yeni-1").allowed()).isTrue();
        assertThat(l.trackedUsers()).isEqualTo(ScanImageLimiter.MAX_USERS + 1);   // + ortak kova
        assertThat(l.tryAcquire("u:yeni-2").allowed()).isTrue();
        assertThat(l.trackedUsers()).as("ikinci yeni kişi de ortak kovada").isEqualTo(ScanImageLimiter.MAX_USERS + 1);
        // Pencere geçince boşta kalanlar budanır; uçuştaki işi olan kalır
        now.addAndGet(61_000);
        assertThat(l.tryAcquire("u:yeni-3").allowed()).isTrue();
        assertThat(l.trackedUsers()).isLessThanOrEqualTo(3);
        held.get(0).close();
        // Tutanın kaydı budanmadı: hakkı bırakınca kişi yeniden iki iş alabilir
        assertThat(l.tryAcquire("u:tutan").allowed()).isTrue();
        assertThat(l.tryAcquire("u:tutan").allowed()).isTrue();
        assertThat(l.tryAcquire("u:tutan").busy()).isTrue();
    }
}
