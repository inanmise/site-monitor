package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UA özetleyici — SIRA SÖZLEŞMESİ testleri.
 *
 * <p>UA dizeleri kasten birbirini taklit eder; bu testlerin çoğu "X, Y'den ÖNCE kontrol edilmeli"
 * kuralını pinler. Sıra bozulursa derleme geçer, ekran sessizce yanlış tarayıcı yazar.
 */
class UserAgentSummaryTest {

    @ParameterizedTest(name = "{1} · {2} ← {0}")
    @CsvSource(delimiter = '|', value = {
        // ── Taklit eden UA'lar: sira bozulursa hepsi 'Chrome' ya da 'Safari' cikar ──
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36 Edg/120 | Windows | Edge",
        "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120 Safari/537.36 OPR/106            | Windows | Opera",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36        | Windows | Chrome",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0             | Windows | Firefox",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605 | macOS   | Safari",
        // ── iOS: TASINAN ayristiricinin GERCEK kusuru (hepsi 'Safari/' tasir) ──
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 CriOS/120 Safari/604 | iOS | Chrome",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 FxiOS/121 Safari/604 | iOS | Firefox",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17 Safari/604| iOS | Safari",
        // ── Android UA'si 'Linux' icerir; Linux'tan ONCE bakilmali ──
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36  | Android | Chrome",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 EdgA/120  | Android | Edge",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36                  | Linux   | Chrome",
    })
    @DisplayName("Taniyici sirasi: Edge/Opera Chrome'dan, iOS tarayicilari Safari'den, Android Linux'tan ONCE")
    void recognisesInTheRightOrder(String ua, String expectedOs, String expectedBrowser) {
        var s = UserAgentSummary.of(ua);
        assertThat(s.os()).isEqualTo(expectedOs);
        assertThat(s.browser()).isEqualTo(expectedBrowser);
    }

    @Test
    @DisplayName("device: mobil ve masaustu ayrilir (ikon secimi buna bakar)")
    void classifiesDevice() {
        assertThat(UserAgentSummary.of("Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile").device()).isEqualTo("mobile");
        assertThat(UserAgentSummary.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) Safari/604").device()).isEqualTo("mobile");
        assertThat(UserAgentSummary.of("Mozilla/5.0 (Windows NT 10.0) Chrome/120").device()).isEqualTo("desktop");
        assertThat(UserAgentSummary.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) Safari/605").device()).isEqualTo("desktop");
    }

    @Test
    @DisplayName("TANINMAYAN UA null doner — arayuz ham dize DOKMEZ, genel 'Oturum' etiketi kullanir")
    void unknownYieldsNulls() {
        for (String ua : new String[]{ null, "", "   ", "curl/8.4.0", "SiteMonitor-PageSpeed/20.31.0" }) {
            var s = UserAgentSummary.of(ua);
            assertThat(s.isUnknown()).as("ua=%s", ua).isTrue();
            assertThat(s.label()).as("ua=%s", ua).isNull();
        }
    }

    @Test
    @DisplayName("label(): iki parca varsa birlestirir, tek parca varsa onu verir")
    void labelFormatting() {
        assertThat(UserAgentSummary.labelOf(
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120 Safari/537.36"))
                .isEqualTo("Windows · Chrome");
        // OS taninir, tarayici taninmaz → yalniz OS
        assertThat(UserAgentSummary.labelOf("Mozilla/5.0 (Windows NT 10.0) BilinmeyenTarayici/1"))
                .isEqualTo("Windows");
    }
}
