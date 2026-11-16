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
    @DisplayName("Go sürümü (2026-11-16): gerçek 2.3.0 / 2.4.0 / 0.49 çıktılarından okunur; desteklenmeyen sürümde korunur")
    void parsesGoVersion() {
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v2.3.0 (commit/e088784614, go1.26.8, linux/amd64)\n", 1L).goVersion())
                .isEqualTo("go1.26.8");
        K6Profile v240 = K6Profile.fromVersionOutput("/opt/k6-candidate/k6",
                "k6 v2.4.0 (commit/dd9395906d, go1.26.11, linux/amd64)\n", 1L);
        assertThat(v240.version()).isEqualTo("v2.4.0");
        assertThat(v240.goVersion()).isEqualTo("go1.26.11");
        assertThat(K6Profile.fromVersionOutput("k6.exe", "k6.exe v2.4.0 (commit/dd9395906d, go1.26.11, windows/amd64)", 1L)
                .goVersion()).isEqualTo("go1.26.11");
        K6Profile old = K6Profile.fromVersionOutput("k6", "k6 v0.49.0 (commit/b5328aa782, go1.21.6, linux/amd64)\n", 1L);
        assertThat(old.unsupported()).isTrue();
        assertThat(old.goVersion()).isEqualTo("go1.21.6");
        // Ön sürüm eki ve iki parçalı sürüm
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v2.5.0 (commit/x, go1.27rc1, linux/amd64)", 1L).goVersion())
                .isEqualTo("go1.27rc1");
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v2.5.0 (commit/x, go1.27, linux/amd64)", 1L).goVersion())
                .isEqualTo("go1.27");
    }

    @Test
    @DisplayName("Go sürümü: geliştirme derlemesinden okunur; Go'suz çıktı / sözcük içi 'go' / başka satırdaki go → null")
    void goVersionEdgeCases() {
        K6Profile dev = K6Profile.fromVersionOutput("k6", "k6 (devel) (go1.26.8, linux/amd64)\n", 1L);
        assertThat(dev.versionKnown()).isFalse();
        assertThat(dev.goVersion()).isEqualTo("go1.26.8");
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v2.3.0", 1L).goVersion()).isNull();
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v2.3.0 (commit/x, cargo1.2.3, linux/amd64)", 1L).goVersion()).isNull();
        assertThat(K6Profile.fromVersionOutput("k6", "k6 v2.3.0 (commit/x, golang, go, go., go1., linux/amd64)", 1L).goVersion())
                .isNull();
        // Sürüm satırı bulunduysa YALNIZ o satır: önceki günlük satırındaki "go9.9" ya da sonraki satır Go sürümü sayılmaz
        assertThat(K6Profile.fromVersionOutput("k6",
                "time=\"x\" level=warning msg=\"go9.9.9\"\nk6 v2.4.0 (commit/x, linux/amd64)\nbuilt with go1.26.11", 1L).goVersion())
                .isNull();
        assertThat(K6Profile.fromVersionOutput("k6",
                "time=\"x\" level=warning msg=\"go9.9.9\"\nk6 v2.4.0 (commit/x, go1.26.11, linux/amd64)", 1L).goVersion())
                .isEqualTo("go1.26.11");
        // Sınır: çok uzun sayı dizisi belirteç tavanında kesilir, tarama sınırlı (büyük girdi takılmaz)
        String huge = "k6 v2.4.0 (go1." + "9".repeat(5000) + ")";
        assertThat(K6Profile.fromVersionOutput("k6", huge, 1L).goVersion()).hasSizeLessThanOrEqualTo(K6Profile.GO_VERSION_MAX_CHARS + 2);
        String manyG = "k6 v2.4.0 (" + "g".repeat(200_000) + ")";
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> assertThat(K6Profile.fromVersionOutput("k6", manyG, 1L).goVersion()).isNull());
        assertThat(K6Profile.unavailable("k6", 1L).goVersion()).isNull();
    }

    @Test
    @DisplayName("Go büyük/küçük sürüm ayrıştırma: go1.26.11 → 1.26; ek yok sayılır; bozuk → null")
    void goMajorMinor() {
        assertThat(K6Profile.goMajorMinor("go1.26.11")).containsExactly(1, 26);
        assertThat(K6Profile.goMajorMinor("go1.27rc1")).containsExactly(1, 27);
        assertThat(K6Profile.goMajorMinor("go2.0")).containsExactly(2, 0);
        for (String bad : new String[] { null, "", "go", "go1", "go1.", "1.26.8", "gox.1", "go1.x" }) {
            assertThat(K6Profile.goMajorMinor(bad)).as(String.valueOf(bad)).isNull();
        }
    }

    @Test
    @DisplayName("Go sürümsüz eski kurucu (kaynak uyumluluğu): goVersion null")
    void legacyConstructor() {
        K6Profile p = new K6Profile("k6", true, "v2.3.0", 2, 3, 0, 1L);
        assertThat(p.goVersion()).isNull();
        assertThat(p.available()).isTrue();
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
