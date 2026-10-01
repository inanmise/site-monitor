package com.sitemonitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sunucu-içi kısa ömürlü bellek (2026-10-01, performans H1/M6): süre dolumu, fresh atlaması, kapalı kip, null sonuç,
 * anahtar tavanı (bellek büyümez) ve kapsam anahtarının sıra/tekrar bağımsızlığı.
 */
class TtlMemoTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger computed = new AtomicInteger();

    private String compute() { return "v" + computed.incrementAndGet(); }

    @Test
    @DisplayName("TTL içinde aynı değer, TTL dolunca yeniden hesap")
    void ttl() {
        TtlMemo<String> m = new TtlMemo<>(10, now::get);
        assertThat(m.get("k", 15_000, false, this::compute)).isEqualTo("v1");
        now.addAndGet(14_999);
        assertThat(m.get("k", 15_000, false, this::compute)).isEqualTo("v1");
        now.addAndGet(1);
        assertThat(m.get("k", 15_000, false, this::compute)).isEqualTo("v2");
    }

    @Test
    @DisplayName("fresh=true belleği atlar ve sonucu yazar — sonraki normal çağrı taze değeri görür")
    void freshBypassWrites() {
        TtlMemo<String> m = new TtlMemo<>(10, now::get);
        m.get("k", 15_000, false, this::compute);
        assertThat(m.get("k", 15_000, true, this::compute)).isEqualTo("v2");
        assertThat(m.get("k", 15_000, false, this::compute)).isEqualTo("v2");
        assertThat(computed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("ttl ≤ 0 ya da null anahtar → bellek yok; null sonuç saklanmaz")
    void disabledAndNulls() {
        TtlMemo<String> m = new TtlMemo<>(10, now::get);
        m.get("k", 0, false, this::compute);
        m.get("k", 0, false, this::compute);
        m.get(null, 15_000, false, this::compute);
        assertThat(computed.get()).isEqualTo(3);
        assertThat(m.size()).isZero();
        assertThat(m.get("n", 15_000, false, () -> null)).isNull();
        assertThat(m.size()).isZero();
    }

    @Test
    @DisplayName("anahtar tavanı: dolunca önce süresi dolanlar, yine doluysa tümü atılır — boyut asla tavanı aşmaz")
    void boundedSize() {
        TtlMemo<String> m = new TtlMemo<>(3, now::get);
        m.get("a", 1_000, false, this::compute);
        m.get("b", 1_000, false, this::compute);
        now.addAndGet(2_000);                          // a, b süresi doldu
        m.get("c", 1_000, false, this::compute);
        m.get("d", 1_000, false, this::compute);       // tavan 3: dolanlar (a, b) atılır
        assertThat(m.size()).isEqualTo(2);
        m.get("e", 1_000, false, this::compute);
        m.get("f", 1_000, false, this::compute);       // hepsi taze ve tavan dolu → temizlenir, f eklenir
        assertThat(m.size()).isLessThanOrEqualTo(3);
        for (int i = 0; i < 1_000; i++) m.get("x" + i, 1_000, false, this::compute);
        assertThat(m.size()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("scopeKey: global → ALL; liste yok → NONE; takım kümesi sıralı ve tekil (sıra/tekrar/null anahtarı bölmez)")
    void scopeKey() {
        assertThat(TtlMemo.scopeKey(true, List.of(5L))).isEqualTo("ALL");
        assertThat(TtlMemo.scopeKey(false, null)).isEqualTo("NONE");
        assertThat(TtlMemo.scopeKey(false, List.of())).isEqualTo("T:");
        assertThat(TtlMemo.scopeKey(false, Arrays.asList(14L, 3L, null, 14L))).isEqualTo("T:3,14");
        assertThat(TtlMemo.scopeKey(false, List.of(3L, 14L))).isEqualTo(TtlMemo.scopeKey(false, List.of(14L, 3L)));
        assertThat(TtlMemo.scopeKey(false, List.of(1L, 23L))).isNotEqualTo(TtlMemo.scopeKey(false, List.of(12L, 3L)));
    }
}
