package com.sitemonitor.service.scripted;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** "Test Çalıştır" kullanıcı sınırı: aynı anda 1, dakikada 6 (2026-10-31, k6 inceleme A4). */
class ScriptedTestLimiterTest {

    @Test
    void oneAtATimePerUser() {
        AtomicLong now = new AtomicLong(1_000_000);
        ScriptedTestLimiter l = new ScriptedTestLimiter(now::get);
        ScriptedTestLimiter.Decision first = l.tryAcquire("ayse");
        assertThat(first.allowed()).isTrue();
        ScriptedTestLimiter.Decision second = l.tryAcquire("ayse");
        assertThat(second.allowed()).isFalse();
        assertThat(second.concurrent()).isTrue();
        assertThat(second.retryAfterSeconds()).isPositive();
        // başka kullanıcı etkilenmez
        assertThat(l.tryAcquire("mehmet").allowed()).isTrue();
        first.ticket().close();
        first.ticket().close();          // ikinci kapanış zararsız
        assertThat(l.tryAcquire("ayse").allowed()).isTrue();
    }

    @Test
    void sixPerMinuteThenRetryAfter() {
        AtomicLong now = new AtomicLong(1_000_000);
        ScriptedTestLimiter l = new ScriptedTestLimiter(now::get);
        for (int i = 0; i < ScriptedTestLimiter.PER_MINUTE; i++) {
            ScriptedTestLimiter.Decision d = l.tryAcquire("ayse");
            assertThat(d.allowed()).as("deneme %d", i).isTrue();
            d.ticket().close();
            now.addAndGet(1_000);
        }
        ScriptedTestLimiter.Decision refused = l.tryAcquire("ayse");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.concurrent()).isFalse();
        // ilk deneme 1_000_000'da; şimdi 1_006_000 → 54 sn kaldı
        assertThat(refused.retryAfterSeconds()).isEqualTo(54);
        now.set(1_000_000 + 60_000);
        ScriptedTestLimiter.Decision again = l.tryAcquire("ayse");
        assertThat(again.allowed()).isTrue();
    }

    @Test
    void blankUserSharesOneBucket() {
        ScriptedTestLimiter l = new ScriptedTestLimiter(() -> 0L);
        assertThat(l.tryAcquire(null).allowed()).isTrue();
        assertThat(l.tryAcquire(" ").allowed()).isFalse();
    }
}
