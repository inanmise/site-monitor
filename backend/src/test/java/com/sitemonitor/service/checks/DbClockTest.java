package com.sitemonitor.service.checks;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnchoredDbClock} (2026-10-29, çok pod Sürüm 3-3 paket A, tasarım §3.3): tembel çapa (kurucu sorgu yapmaz — K-1),
 * monoton saatle ileri tahmin, 30 sn'de bir yeniden çapa, okuma hatasında 5 dk'ya dek eski çapa sonra pod saati + stale,
 * uzun gidiş-dönüş reddi, gerçek H2 deyimi.
 */
class DbClockTest {

    private static final long DB0 = 1_900_000_000_000L;
    private static final long WALL = 1_700_000_000_000L;

    private final AtomicLong nano = new AtomicLong(1_000_000_000L);
    private final AtomicLong db = new AtomicLong(DB0);
    private final AtomicInteger queries = new AtomicInteger();
    private volatile boolean failing;
    private volatile long rttNanos;

    private AnchoredDbClock clock() {
        return new AnchoredDbClock(() -> {
            queries.incrementAndGet();
            if (failing) throw new IllegalStateException("db down");
            nano.addAndGet(rttNanos);
            return db.get();
        }, nano::get, () -> WALL);
    }

    private void advanceMs(long ms) {
        nano.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
        db.addAndGet(ms);
    }

    @Test
    @DisplayName("Kurucu sorgu yapmaz; ilk nowMs çapalar, sonrası ek sorgu olmadan ilerler; 30 sn'de bir yeniden çapa")
    void lazyAnchorAndExtrapolation() {
        AnchoredDbClock c = clock();
        assertThat(queries).hasValue(0);
        assertThat(c.stale()).as("hiç çapalanmadı").isTrue();
        assertThat(queries).as("stale() da sorgu yapmaz").hasValue(0);
        assertThat(c.nowMs()).isEqualTo(DB0);
        assertThat(queries).hasValue(1);
        assertThat(c.stale()).isFalse();
        advanceMs(10_000);
        assertThat(c.nowMs()).isEqualTo(DB0 + 10_000);
        advanceMs(19_000);
        assertThat(c.nowMs()).isEqualTo(DB0 + 29_000);
        assertThat(queries).hasValue(1);
        advanceMs(1_000);
        db.addAndGet(250);   // veritabanı saati pod saatinden biraz kaydı → yeniden çapa onu yakalar
        assertThat(c.nowMs()).isEqualTo(DB0 + 30_250);
        assertThat(queries).hasValue(2);
        assertThat(c.anchors()).isEqualTo(2);
    }

    @Test
    @DisplayName("Okuma hatası: eski çapadan 5 dk'ya dek ilerler (denemeler 30 sn'de bir), sonra pod saati + stale; düzelince yeniden çapa")
    void failureExtrapolatesThenFallsBack() {
        AnchoredDbClock c = clock();
        assertThat(c.nowMs()).isEqualTo(DB0);
        failing = true;
        advanceMs(31_000);
        assertThat(c.nowMs()).isEqualTo(DB0 + 31_000);
        assertThat(c.stale()).isFalse();
        int afterFirstFail = queries.get();
        advanceMs(1_000);
        c.nowMs();
        assertThat(queries).as("başarısız deneme 30 sn tekrarlanmaz").hasValue(afterFirstFail);
        advanceMs(DbClock.MAX_EXTRAPOLATE_MS);
        assertThat(c.nowMs()).as("5 dk aşıldı → pod saati").isEqualTo(WALL);
        assertThat(c.stale()).isTrue();
        assertThat(c.anchorFailures()).isPositive();
        failing = false;
        advanceMs(31_000);
        assertThat(c.nowMs()).isEqualTo(db.get());
        assertThat(c.stale()).isFalse();
    }

    @Test
    @DisplayName("Hiç çapalanamayan saat: pod saati + stale")
    void neverAnchored() {
        failing = true;
        AnchoredDbClock c = clock();
        assertThat(c.nowMs()).isEqualTo(WALL);
        assertThat(c.stale()).isTrue();
    }

    @Test
    @DisplayName("Gidiş-dönüş 2 sn'yi aşarsa ölçüm atılır; ortada çapalanır")
    void longRoundTripRejected() {
        rttNanos = TimeUnit.MILLISECONDS.toNanos(2_500);
        AnchoredDbClock c = clock();
        assertThat(c.nowMs()).isEqualTo(WALL);
        assertThat(c.stale()).isTrue();
        rttNanos = TimeUnit.MILLISECONDS.toNanos(400);
        advanceMs(31_000);
        long now = c.nowMs();
        // çapa anı gidiş-dönüşün ortası (t0 + 200 ms) → okuma sonu t0 + 400 ms'de "DB0 + 31 000 + 200"
        assertThat(now).isEqualTo(db.get() + 200);
    }

    @Test
    @DisplayName("Gerçek H2: SELECT CURRENT_TIMESTAMP okunur, değer duvar saatine yakın")
    void realH2Statement() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            AnchoredDbClock c = new AnchoredDbClock(mp.jdbc());
            long v = c.nowMs();
            assertThat(c.stale()).isFalse();
            assertThat(Math.abs(v - System.currentTimeMillis())).isLessThan(10_000L);
        }
    }

    @Test
    @DisplayName("Sistem saati sözleşmesi: DbClock.SYSTEM her zaman stale")
    void systemClock() {
        assertThat(DbClock.SYSTEM.stale()).isTrue();
        assertThat(Math.abs(DbClock.SYSTEM.nowMs() - System.currentTimeMillis())).isLessThan(5_000L);
    }
}
