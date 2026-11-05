package com.sitemonitor.service.keyword;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anahtar kelime gelişmiş istek kuralları (kw sözleşmesi §5): kabul edilen durum kodu dilbilgisi, son URL önek karşılaştırması,
 * User-Agent kipi ve doğrulaması, zaman aşımı aralığı, posta konusu şablonu.
 */
class KeywordRequestRulesTest {

    @Test
    @DisplayName("durum kodu dilbilgisi: 2xx | NNN | NNN-NNN (100–599), ≤ 20 öğe, ≤ 100 karakter; boş = kapalı (geçerli)")
    void expectedStatusGrammar() {
        for (String ok : new String[] { "2xx", "200", "200-399", "200,301", "2xx, 3XX ,404", "100-599", " 5xx " }) {
            assertThat(KeywordRequestRules.validateExpectedStatus(ok)).as(ok).isNull();
        }
        assertThat(KeywordRequestRules.validateExpectedStatus(null)).isNull();
        assertThat(KeywordRequestRules.validateExpectedStatus("  ")).isNull();
        for (String bad : new String[] { "6xx", "2x", "099", "600", "abc", "200-100", "200,,300", "2xx;3xx", "20O",
                "200-", "-200", "1".repeat(101) }) {
            assertThat(KeywordRequestRules.validateExpectedStatus(bad)).as(bad).isNotNull();
        }
        assertThat(KeywordRequestRules.validateExpectedStatus(String.join(",", java.util.Collections.nCopies(21, "200"))))
                .isNotNull();
        assertThat(KeywordRequestRules.canonicalExpectedStatus(" 2XX , 301 ")).isEqualTo("2xx,301");
        assertThat(KeywordRequestRules.canonicalExpectedStatus("")).isNull();
    }

    @Test
    @DisplayName("son URL kuralı: önek, şema / host küçük harf, varsayılan port ve parça düşer; '/' ile başlayan yol izleme kaynağına göre")
    void finalUrlMatches() {
        String mon = "https://Shop.Example.com:443/start?x=1";
        URI actual = URI.create("https://shop.example.com/panel/orders?y=2#top");
        assertThat(KeywordRequestRules.finalUrlMatches(actual, "/panel", mon)).isTrue();
        assertThat(KeywordRequestRules.finalUrlMatches(actual, "https://SHOP.example.com:443/panel", mon)).isTrue();
        assertThat(KeywordRequestRules.finalUrlMatches(actual, "https://shop.example.com/panel/orders#x", mon)).isTrue();
        assertThat(KeywordRequestRules.finalUrlMatches(URI.create("https://shop.example.com/login?next=/panel"), "/panel", mon))
                .isFalse();
        assertThat(KeywordRequestRules.finalUrlMatches(URI.create("http://shop.example.com/panel"), "/panel", mon)).isFalse();
        assertThat(KeywordRequestRules.finalUrlMatches(URI.create("https://shop.example.com:8443/panel"), "/panel", mon)).isFalse();
        assertThat(KeywordRequestRules.finalUrlMatches(actual, null, mon)).isTrue();
        assertThat(KeywordRequestRules.finalUrlMatches(null, "/panel", mon)).isFalse();
    }

    @Test
    @DisplayName("son URL doğrulaması: mutlak http(s) ya da '/' yol; protokol-göreli, şemasız, boşluk / CR-LF, ftp reddedilir")
    void validateFinalUrl() {
        assertThat(KeywordRequestRules.validateExpectedFinalUrl("/panel")).isNull();
        assertThat(KeywordRequestRules.validateExpectedFinalUrl("https://x.example.com/a?b=1")).isNull();
        assertThat(KeywordRequestRules.validateExpectedFinalUrl("")).isNull();
        for (String bad : new String[] { "//x.example.com/a", "x.example.com/a", "ftp://x.example.com/", "/a b", "/a\r\nb",
                "https:///nohost", "/" + "a".repeat(2000) }) {
            assertThat(KeywordRequestRules.validateExpectedFinalUrl(bad)).as(bad).isNotNull();
        }
    }

    @Test
    @DisplayName("User-Agent: kip DEFAULT / BROWSER / CUSTOM; özel değer yazdırılabilir ASCII (CR/LF, Türkçe harf, ≥ 300 yok)")
    void userAgent() {
        assertThat(KeywordRequestRules.effectiveUserAgent(null, null)).isEqualTo(KeywordRequestRules.DEFAULT_USER_AGENT);
        assertThat(KeywordRequestRules.effectiveUserAgent("browser", null)).isEqualTo(KeywordRequestRules.BROWSER_USER_AGENT);
        assertThat(KeywordRequestRules.BROWSER_USER_AGENT).doesNotContain("SiteMonitor");
        assertThat(KeywordRequestRules.effectiveUserAgent("CUSTOM", "Probe/2.0")).isEqualTo("Probe/2.0");
        assertThat(KeywordRequestRules.effectiveUserAgent("CUSTOM", "kötü\r\nX: y")).isEqualTo(KeywordRequestRules.DEFAULT_USER_AGENT);
        assertThat(KeywordRequestRules.effectiveUserAgent("CUSTOM", " ")).isEqualTo(KeywordRequestRules.DEFAULT_USER_AGENT);
        assertThat(KeywordRequestRules.validateUserAgent("Mozilla/5.0 (X11)")).isNull();
        assertThat(KeywordRequestRules.validateUserAgent("Ajan\nX")).isNotNull();
        assertThat(KeywordRequestRules.validateUserAgent("Türkçe")).isNotNull();
        assertThat(KeywordRequestRules.validateUserAgent("a".repeat(301))).isNotNull();
        assertThat(KeywordRequestRules.validateUserAgentMode("CUSTOM")).isNull();
        assertThat(KeywordRequestRules.validateUserAgentMode("ROBOT")).isNotNull();
    }

    @Test
    @DisplayName("yöntem / kapsam / zaman aşımı / posta konusu doğrulaması")
    void misc() {
        assertThat(KeywordRequestRules.method("post")).isEqualTo("POST");
        assertThat(KeywordRequestRules.method("HEAD")).isEqualTo("GET");
        assertThat(KeywordRequestRules.validateMethod("HEAD")).isNotNull();
        assertThat(KeywordRequestRules.validateMethod("post")).isNull();
        assertThat(KeywordRequestRules.validateMatchScope("VISIBLE")).isNull();
        assertThat(KeywordRequestRules.validateMatchScope("TEXT")).isNotNull();
        assertThat(KeywordRequestRules.validateTimeoutMs(999)).isNotNull();
        assertThat(KeywordRequestRules.validateTimeoutMs(60_001)).isNotNull();
        assertThat(KeywordRequestRules.validateTimeoutMs(60_000)).isNull();
        assertThat(KeywordRequestRules.effectiveTimeoutMs(120_000)).isEqualTo(60_000);
        assertThat(KeywordRequestRules.effectiveTimeoutMs(null)).isEqualTo(10_000);
        assertThat(KeywordRequestRules.effectiveTimeoutMs(10)).isEqualTo(1_000);
        assertThat(KeywordRequestRules.validateMailSubjectTemplate("{ad} · {neden}")).isNull();
        assertThat(KeywordRequestRules.validateMailSubjectTemplate("a\nb")).isNotNull();
        assertThat(KeywordRequestRules.validateMailSubjectTemplate("x".repeat(201))).isNotNull();
    }
}
