package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** k6 ikili profili (2026-10-29, k6 0.49 → 2.3 geçişi): sürüm okuma, --address kuralı, sözdizimi düzeyi. */
class K6ProfileTest {

    @Test
    @DisplayName("k6 version çıktısı: 0.49 ve 2.3 gerçek çıktılarından sürüm okunur")
    void parsesRealVersionOutput() {
        K6Profile old = K6Profile.fromVersionOutput("k6", "k6 v0.49.0 (commit/b5328aa782, go1.21.6, linux/amd64)\n", 1L);
        assertThat(old.available()).isTrue();
        assertThat(old.version()).isEqualTo("v0.49.0");
        assertThat(old.major()).isZero();
        assertThat(old.minor()).isEqualTo(49);
        K6Profile next = K6Profile.fromVersionOutput("/opt/k6-next/k6",
                "k6.exe v2.3.0 (commit/e088784614, go1.26.8, windows/amd64)", 1L);
        assertThat(next.version()).isEqualTo("v2.3.0");
        assertThat(next.major()).isEqualTo(2);
    }

    @Test
    @DisplayName("--address yalnız 2.0'dan eski (ya da sürümü okunamayan) ikiliye — 2.x'te API'yi AÇARDI")
    void apiAddressOnlyBelowTwo() {
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v0.49.0", 1L).needsApiAddress()).isTrue();
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v1.8.2", 1L).needsApiAddress()).isTrue();
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v2.0.0", 1L).needsApiAddress()).isFalse();
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v2.3.0", 1L).needsApiAddress()).isFalse();
        // Sürüm okunamadı ⇒ eski davranış (bayrak verilir)
        assertThat(K6Profile.fromVersionOutput("k6", "k6 dev build", 1L).needsApiAddress()).isTrue();
        assertThat(K6Profile.unavailable("k6", 1L).needsApiAddress()).isTrue();
    }

    @Test
    @DisplayName("Modern sözdizimi 0.53'ten itibaren; okunamayan sürüm = bilinmiyor (null)")
    void modernSyntaxThreshold() {
        assertThat(K6Profile.fromVersionOutput("k6", "v0.49.0", 1L).modernSyntax()).isFalse();
        assertThat(K6Profile.fromVersionOutput("k6", "v0.53.0", 1L).modernSyntax()).isTrue();
        assertThat(K6Profile.fromVersionOutput("k6", "v2.3.0", 1L).modernSyntax()).isTrue();
        assertThat(K6Profile.fromVersionOutput("k6", "garip", 1L).modernSyntax()).isNull();
        assertThat(K6Profile.unavailable("k6", 1L).modernSyntax()).isNull();
    }

    @Test
    @DisplayName("Sürüm karşılaştırması büyük → küçük → yama sırasıyla")
    void compareVersions() {
        K6Profile a = K6Profile.fromVersionOutput("a", "v0.49.0", 1L);
        K6Profile b = K6Profile.fromVersionOutput("b", "v2.3.0", 1L);
        K6Profile c = K6Profile.fromVersionOutput("c", "v2.3.1", 1L);
        assertThat(K6Profile.compareVersions(a, b)).isNegative();
        assertThat(K6Profile.compareVersions(c, b)).isPositive();
        assertThat(K6Profile.compareVersions(b, b)).isZero();
    }

    @Test
    @DisplayName("Boş / sürümsüz çıktı: kullanılabilir ama numarasız; null çıktı = bulunamadı")
    void oddOutputs() {
        assertThat(K6Profile.fromVersionOutput("k6", null, 1L).available()).isFalse();
        K6Profile p = K6Profile.fromVersionOutput("k6", "k6 " + "x".repeat(100), 1L);
        assertThat(p.available()).isTrue();
        assertThat(p.versionKnown()).isFalse();
        assertThat(p.version()).hasSizeLessThanOrEqualTo(40);
    }
}
