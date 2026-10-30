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

    @Test
    @DisplayName("Sürüm k6 sözcüğünün HEMEN ardındaki v'li sürümdür: geliştirme derlemesinin Go sürümü (go1.26.8) k6 sürümü "
            + "sanılmaz — bilinmeyen sürüm, ikili koşar (inceleme L3)")
    void devBuildGoVersionIsNotK6Version() {
        K6Profile dev = K6Profile.fromVersionOutput("k6", "k6 (devel) (go1.26.8, linux/amd64)\n", 1L);
        assertThat(dev.available()).isTrue();
        assertThat(dev.unsupported()).isFalse();
        assertThat(dev.versionKnown()).isFalse();
        assertThat(dev.version()).isEqualTo("k6 (devel) (go1.26.8, linux/amd64)");
        // Sürüm yalnız parantez içinde ya da v'siz: okunmaz (eski desen "1.26.8" ya da "2.3.0" okurdu).
        assertThat(K6Profile.fromVersionOutput("k6", "k6 2.3.0 (go1.26.8)", 1L).versionKnown()).isFalse();
        assertThat(K6Profile.fromVersionOutput("k6", "k6 (commit/v0.49.0, go1.21.6)", 1L).unsupported()).isFalse();
        // Gerçek biçimler: Linux k6, Windows k6.exe (gerçek 2.3.0 ve 0.49.0 çıktısı), yeniden adlandırılmış ikili, sürüm
        // satırından önce gelen günlük satırı.
        assertThat(K6Profile.fromVersionOutput("k6.exe",
                "k6.exe v0.49.0 (commit/b5328aa782, go1.21.6, windows/amd64)\n", 1L).unsupported()).isTrue();
        assertThat(K6Profile.fromVersionOutput("/opt/k6-candidate/k6-2.4",
                "k6-2.4 v2.4.1 (commit/abc, go1.26.9, linux/amd64)", 1L).version()).isEqualTo("v2.4.1");
        K6Profile afterLog = K6Profile.fromVersionOutput("k6",
                "time=\"2026-10-30T10:00:00Z\" level=warning msg=\"x v9.9.9\"\nk6 v2.3.0 (commit/e088784614, go1.26.8, linux/amd64)", 1L);
        assertThat(afterLog.version()).isEqualTo("v2.3.0");
        assertThat(afterLog.available()).isTrue();
        assertThat(K6Profile.fromVersionOutput("k6", "  k6 v2.3.0", 1L).version()).isEqualTo("v2.3.0");
    }
}
