package com.sitemonitor.service.page;

import com.sitemonitor.model.PageSpeedMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sayfa Hızı saf kuralları — eşik değerlendirmesi, tracker hariç tutma, başlık ayrıştırma.
 *
 * <p>Bu kuralların hatası SESSİZDİR: eşik bir yönde kayarsa ya alarm hiç gelmez ya her kontrolde
 * gelir; tracker deseni genişse kendi kaynaklarınız ölçüm dışı kalır ve sayfa olduğundan hafif
 * görünür. Hiçbiri ağ ya da DB gerektirmediği için sınır sınır pinlenebilir.
 */
class PageSpeedRulesTest {

    private static PageSpeedMonitor withThresholds(Integer load, Integer ttfb, Integer kb, Integer reqs) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setMaxLoadMs(load);
        m.setMaxTtfbMs(ttfb);
        m.setMaxPageKb(kb);
        m.setMaxRequests(reqs);
        return m;
    }

    @Nested
    @DisplayName("Eşik değerlendirmesi")
    class Thresholds {

        @Test
        @DisplayName("Eşik NULL ise o metrik hiç değerlendirilmez — kullanıcı yalnız umursadığını bağlar")
        void nullThresholdNeverBreaches() {
            var m = withThresholds(null, null, null, null);

            assertThat(PageSpeedRules.evaluate(m, 999_999, 999_999, 999_999_999L, 9999)).isEmpty();
        }

        @Test
        @DisplayName("Eşik 0 'sınırsız' demektir — 'her şey ihlal' DEĞİL")
        void zeroThresholdMeansUnlimited() {
            var m = withThresholds(0, 0, 0, 0);

            assertThat(PageSpeedRules.evaluate(m, 5000, 5000, 5_000_000L, 500)).isEmpty();
        }

        @Test
        @DisplayName("Tam eşik değeri İHLAL SAYILMAZ — 'en fazla 3000 ms' 3000'e izin verir")
        void exactThresholdIsAllowed() {
            var m = withThresholds(3000, 500, 1000, 50);

            assertThat(PageSpeedRules.evaluate(m, 3000, 500, 1000L * 1024, 50)).isEmpty();
        }

        @Test
        @DisplayName("Eşiğin bir birim üstü ihlaldir")
        void oneOverThresholdBreaches() {
            var m = withThresholds(3000, 500, 1000, 50);

            assertThat(PageSpeedRules.evaluate(m, 3001, 500, 1000L * 1024, 50))
                    .containsExactly(PageSpeedRules.BREACH_LOAD);
            assertThat(PageSpeedRules.evaluate(m, 3000, 501, 1000L * 1024, 50))
                    .containsExactly(PageSpeedRules.BREACH_TTFB);
            assertThat(PageSpeedRules.evaluate(m, 3000, 500, 1000L * 1024 + 1, 50))
                    .containsExactly(PageSpeedRules.BREACH_SIZE);
            assertThat(PageSpeedRules.evaluate(m, 3000, 500, 1000L * 1024, 51))
                    .containsExactly(PageSpeedRules.BREACH_REQUESTS);
        }

        @Test
        @DisplayName("Boyut eşiği KB girilir, ölçüm BAYT — çevrim tek yerde (1 KB = 1024 bayt)")
        void sizeThresholdConvertsKilobytesToBytes() {
            var m = withThresholds(null, null, 1, null);   // 1 KB

            assertThat(PageSpeedRules.evaluate(m, null, null, 1024L, null)).isEmpty();       // tam 1 KB → serbest
            assertThat(PageSpeedRules.evaluate(m, null, null, 1025L, null))
                    .containsExactly(PageSpeedRules.BREACH_SIZE);
        }

        @Test
        @DisplayName("Ölçülemeyen (null) metrik ihlal üretmez — eksik veri alarma dönüşmemeli")
        void nullMeasurementNeverBreaches() {
            var m = withThresholds(1, 1, 1, 1);

            assertThat(PageSpeedRules.evaluate(m, null, null, null, null)).isEmpty();
        }

        @Test
        @DisplayName("Birden fazla eşik birlikte aşılabilir; hepsi raporlanır")
        void multipleBreachesReported() {
            var m = withThresholds(1000, 100, 10, 5);

            assertThat(PageSpeedRules.evaluate(m, 2000, 200, 100L * 1024, 50))
                    .containsExactlyInAnyOrder(PageSpeedRules.BREACH_LOAD, PageSpeedRules.BREACH_TTFB,
                            PageSpeedRules.BREACH_SIZE, PageSpeedRules.BREACH_REQUESTS);
        }

        @Test
        @DisplayName("İhlal yoksa DB kolonuna null yazılır (boş dize değil) — sorgular 'ihlal var mı'yı böyle okuyor")
        void emptyBreachListSerializesToNull() {
            assertThat(PageSpeedRules.joinBreaches(java.util.List.of())).isNull();
            assertThat(PageSpeedRules.joinBreaches(null)).isNull();
            assertThat(PageSpeedRules.joinBreaches(java.util.List.of("LOAD", "SIZE"))).isEqualTo("LOAD,SIZE");
        }

        @Test
        @DisplayName("Monitör null ise (kaydetmeden deneme) hiç eşik değerlendirilmez")
        void nullMonitorMeansNoEvaluation() {
            assertThat(PageSpeedRules.evaluate(null, 99999, 99999, 99999L, 999)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Tracker hariç tutma")
    class Exclusion {

        @Test
        @DisplayName("Kapalıyken ve desen yokken HİÇBİR şey hariç tutulmaz")
        void disabledExcludesNothing() {
            var p = PageSpeedRules.exclusion(false, null);

            assertThat(p.test("https://www.google-analytics.com/analytics.js")).isFalse();
            assertThat(p.test("https://banka.com.tr/app.js")).isFalse();
        }

        @Test
        @DisplayName("Açıkken yerleşik tracker'lar hariç, kendi kaynaklarımız DAHİL kalır")
        void builtinTrackersExcludedOwnAssetsKept() {
            var p = PageSpeedRules.exclusion(true, null);

            assertThat(p.test("https://www.google-analytics.com/analytics.js")).isTrue();
            assertThat(p.test("https://www.googletagmanager.com/gtm.js?id=X")).isTrue();
            // Kendi statiğimiz ASLA düşmemeli — düşerse sayfa olduğundan hafif ölçülür.
            assertThat(p.test("https://banka.com.tr/static/app.js")).isFalse();
            assertThat(p.test("https://cdn.banka.com.tr/logo.png")).isFalse();
        }

        @Test
        @DisplayName("Kullanıcı desenleri yerleşik listeye EKlenir ve tracker kapalıyken de çalışır")
        void userPatternsWorkIndependently() {
            var p = PageSpeedRules.exclusion(false, "reklam.example\nsayac.example");

            assertThat(p.test("https://reklam.example/a.js")).isTrue();
            assertThat(p.test("https://sayac.example/b.gif")).isTrue();
            assertThat(p.test("https://banka.com.tr/app.js")).isFalse();
        }

        @Test
        @DisplayName("Desen eşleşmesi büyük/küçük harf duyarsız")
        void matchingIsCaseInsensitive() {
            var p = PageSpeedRules.exclusion(false, "REKLAM.example");

            assertThat(p.test("https://Reklam.Example/a.js")).isTrue();
        }

        @Test
        @DisplayName("Desenler satır VE virgülle ayrılabilir; boşlar atılır")
        void patternsSplitOnLinesAndCommas() {
            assertThat(PageSpeedRules.splitPatterns("a.example, b.example\n\nc.example,,"))
                    .containsExactly("a.example", "b.example", "c.example");
            assertThat(PageSpeedRules.splitPatterns("   ")).isEmpty();
            assertThat(PageSpeedRules.splitPatterns(null)).isEmpty();
        }

        @Test
        @DisplayName("Desen sayısı ve uzunluğu caplenir — yapıştırılan dev liste ölçümü yavaşlatmasın")
        void patternsAreCapped() {
            String many = "x".repeat(1) + java.util.stream.IntStream.range(0, 200)
                    .mapToObj(i -> "\nd" + i + ".example").reduce("", String::concat);
            assertThat(PageSpeedRules.splitPatterns(many)).hasSize(PageSpeedRules.MAX_PATTERNS);

            String longOne = "y".repeat(500);
            assertThat(PageSpeedRules.splitPatterns(longOne).get(0)).hasSize(PageSpeedRules.MAX_PATTERN_LEN);
        }

        @Test
        @DisplayName("null URL eşleşme denemesinde patlamaz")
        void nullUrlIsSafe() {
            assertThat(PageSpeedRules.exclusion(true, "a").test(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("İstek başlıkları")
    class Headers {

        @Test
        @DisplayName("'Ad: değer' satırları ayrıştırılır; boş satır ve # yorumu atlanır")
        void parsesNameValueLines() {
            Map<String, String> h = PageSpeedRules.parseHeaders(
                    "X-Api-Key: abc123\n\n# yorum satiri\nX-Env: prod");

            assertThat(h).containsExactly(
                    java.util.Map.entry("X-Api-Key", "abc123"),
                    java.util.Map.entry("X-Env", "prod"));
        }

        @Test
        @DisplayName("Değerdeki iki nokta korunur (Bearer jetonları / URL'ler bozulmasın)")
        void keepsColonsInValue() {
            assertThat(PageSpeedRules.parseHeaders("Authorization: Bearer a:b:c"))
                    .containsEntry("Authorization", "Bearer a:b:c");
        }

        @Test
        @DisplayName("İki nokta taşımayan yarım satır sessizce yok sayılır — istek patlamaz")
        void ignoresMalformedLines() {
            assertThat(PageSpeedRules.parseHeaders("bu bir baslik degil\nX-Ok: 1"))
                    .containsExactly(java.util.Map.entry("X-Ok", "1"));
        }

        @Test
        @DisplayName("Kontrol karakteri (CR/LF) taşıyan satır ATILIR — başlık enjeksiyonu buradan başlar")
        void dropsControlCharacters() {
            final String CR = String.valueOf((char) 13);
            // Yalniz CR (LF siz) satir ayirici DEGILDIR -> deger icinde kalir ve enjeksiyon denemesi olur.
            assertThat(PageSpeedRules.parseHeaders("X-Bad: deger" + CR + "Injected: evet")).isEmpty();
            // Baslik ADINDA kontrol karakteri de reddedilir.
            assertThat(PageSpeedRules.parseHeaders("X-Bad" + (char) 7 + "Name: v")).isEmpty();
            // CRLF GERCEK satir ayiricidir: ikinci satir normal bir baslik olur (enjeksiyon degil).
            assertThat(PageSpeedRules.parseHeaders("X-A: 1" + CR + String.valueOf((char) 10) + "X-B: 2"))
                    .hasSize(2);
        }

        @Test
        @DisplayName("Başlık sayısı caplenir")
        void headerCountIsCapped() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) sb.append("X-H").append(i).append(": v\n");

            assertThat(PageSpeedRules.parseHeaders(sb.toString())).hasSize(PageSpeedRules.MAX_CUSTOM_HEADERS);
        }

        @Test
        @DisplayName("Boş/null girdi boş harita verir")
        void blankInputGivesEmptyMap() {
            assertThat(PageSpeedRules.parseHeaders(null)).isEmpty();
            assertThat(PageSpeedRules.parseHeaders("   ")).isEmpty();
        }

        @Test
        @DisplayName("Basic auth başlığı RFC uyumlu base64 üretir; kullanıcı adı yoksa null")
        void basicAuthHeader() {
            assertThat(PageSpeedRules.basicAuthHeader("kadir", "gizli"))
                    .isEqualTo("Basic " + java.util.Base64.getEncoder()
                            .encodeToString("kadir:gizli".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            // Parola boş olabilir (bazı iç servisler yalnız kullanıcı adı ister).
            assertThat(PageSpeedRules.basicAuthHeader("kadir", null)).isNotNull();
            // Kullanıcı adı yoksa parola tek başına anlamsız.
            assertThat(PageSpeedRules.basicAuthHeader(null, "gizli")).isNull();
            assertThat(PageSpeedRules.basicAuthHeader("  ", "gizli")).isNull();
        }
    }

    @Nested
    @DisplayName("Clamp'ler")
    class Clamps {

        @Test
        @DisplayName("Aralık TABANIN altına inemez — form atlanabilir, sunucu atlanamaz")
        void intervalFloorIsEnforced() {
            assertThat(PageSpeedRules.clampInterval(60)).isEqualTo(PageSpeedMonitor.MIN_INTERVAL_SECONDS);
            assertThat(PageSpeedRules.clampInterval(0)).isEqualTo(PageSpeedMonitor.MIN_INTERVAL_SECONDS);
            assertThat(PageSpeedRules.clampInterval(-1)).isEqualTo(PageSpeedMonitor.MIN_INTERVAL_SECONDS);
            assertThat(PageSpeedRules.clampInterval(null)).isEqualTo(PageSpeedMonitor.MIN_INTERVAL_SECONDS);
            // Tabanın üstü olduğu gibi korunur.
            assertThat(PageSpeedRules.clampInterval(3600)).isEqualTo(3600);
        }

        @Test
        @DisplayName("Eşzamanlılık 1..20 aralığına kırpılır — tek pod'u boğmasın")
        void concurrencyIsClamped() {
            assertThat(PageSpeedRules.clampConcurrency(0)).isEqualTo(1);
            assertThat(PageSpeedRules.clampConcurrency(1000)).isEqualTo(20);
            assertThat(PageSpeedRules.clampConcurrency(null)).isEqualTo(5);
            assertThat(PageSpeedRules.clampConcurrency(7)).isEqualTo(7);
        }

        @Test
        @DisplayName("UA boş/null ise varsayılana düşer")
        void userAgentFallsBack() {
            assertThat(PageSpeedRules.userAgentOr(null, "VARSAYILAN")).isEqualTo("VARSAYILAN");
            assertThat(PageSpeedRules.userAgentOr("  ", "VARSAYILAN")).isEqualTo("VARSAYILAN");
            assertThat(PageSpeedRules.userAgentOr("Ozel/1.0", "VARSAYILAN")).isEqualTo("Ozel/1.0");
        }
    }
}
