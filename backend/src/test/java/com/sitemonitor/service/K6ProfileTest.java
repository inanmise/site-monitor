package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6 ikili profili (2026-10-29, k6 0.49 → 2.3 geçişi): sürüm okuma ve desteklenen en eski sürüm (2026-10-30, Yayın C —
 * imajda tek ikili; 2.0'dan eski k6 "desteklenmeyen sürüm" olarak koşmaz).
 */
class K6ProfileTest {

    @Test
    @DisplayName("k6 version çıktısı: 2.3 gerçek çıktısından sürüm okunur, ikili kullanılabilir")
    void parsesRealVersionOutput() {
        K6Profile p = K6Profile.fromVersionOutput("k6", "k6 v2.3.0 (commit/e088784614, go1.26.8, linux/amd64)\n", 1L);
        assertThat(p.available()).isTrue();
        assertThat(p.unsupported()).isFalse();
        assertThat(p.version()).isEqualTo("v2.3.0");
        assertThat(p.major()).isEqualTo(2);
        assertThat(p.minor()).isEqualTo(3);
        K6Profile win = K6Profile.fromVersionOutput("k6.exe", "k6.exe v2.3.0 (commit/e088784614, go1.26.8, windows/amd64)", 1L);
        assertThat(win.version()).isEqualTo("v2.3.0");
    }

    @Test
    @DisplayName("2.0'dan eski ikili: bulundu ama desteklenmiyor — available=false, sürüm metni korunur")
    void olderThanTwoIsUnsupported() {
        K6Profile old = K6Profile.fromVersionOutput("k6", "k6 v0.49.0 (commit/b5328aa782, go1.21.6, linux/amd64)\n", 1L);
        assertThat(old.available()).isFalse();
        assertThat(old.unsupported()).isTrue();
        assertThat(old.version()).isEqualTo("v0.49.0");
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v1.8.2", 1L).unsupported()).isTrue();
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v2.0.0", 1L).available()).isTrue();
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v3.1.4", 1L).available()).isTrue();
        assertThat(K6Profile.MIN_SUPPORTED_MAJOR).isEqualTo(2);
        assertThat(K6Profile.MIN_SUPPORTED_VERSION).isEqualTo("v2.0.0");
    }

    @Test
    @DisplayName("Bulunamayan ikili 'desteklenmeyen' DEĞİL — sürümü yok")
    void unavailableIsNotUnsupported() {
        K6Profile p = K6Profile.unavailable("k6", 1L);
        assertThat(p.available()).isFalse();
        assertThat(p.unsupported()).isFalse();
        assertThat(p.version()).isNull();
    }

    @Test
    @DisplayName("Boş / sürümsüz çıktı: kullanılabilir ama numarasız (bilinmeyen sürüm reddedilmez); null çıktı = bulunamadı")
    void oddOutputs() {
        assertThat(K6Profile.fromVersionOutput("k6", null, 1L).available()).isFalse();
        K6Profile p = K6Profile.fromVersionOutput("k6", "k6 " + "x".repeat(100), 1L);
        assertThat(p.available()).isTrue();
        assertThat(p.unsupported()).isFalse();
        assertThat(p.versionKnown()).isFalse();
        assertThat(p.version()).hasSizeLessThanOrEqualTo(40);
    }
}
