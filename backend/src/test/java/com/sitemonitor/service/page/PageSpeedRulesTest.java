package com.sitemonitor.service.page;

import com.sitemonitor.model.PageSpeedMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
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

        /**
         * TTFB eşiği GERÇEK kontrol isteğinin ilk baytına bakar (2026-11-14, madde 2 — bilinçli davranış değişikliği).
         *
         * <p>Eskiden eşik ayrı faz probunun sunucu süresine bakıyordu; prob kimlik / özel başlık göndermiyor ve yönlendirmeyi
         * izlemiyordu — kimlik isteyen sayfada 401'in, yönlenen sayfada 301'in süresini ölçüyordu. Artık faz probu eşiği ETKİLEMEZ.
         */
        @Test
        @DisplayName("TTFB eşiği gerçek isteğin TTFB'sine bakar (faz probu eşiği etkilemez)")
        void ttfbThresholdUsesRealRequest() {
            var m = withThresholds(0, 500, 0, 0);

            assertThat(PageSpeedRules.evaluate(m, null, 501, null, null))
                    .containsExactly(PageSpeedRules.BREACH_TTFB);
            assertThat(PageSpeedRules.evaluate(m, null, 500, null, null)).isEmpty();
            // Ölçüm kaydının kendisi: prob sunucu süresi taşınmaz, yalnız gerçek TTFB
            assertThat(PageSpeedRules.evaluate(m, PageSpeedRules.Measurement.basic(null, 2955, null, null)))
                    .containsExactly(PageSpeedRules.BREACH_TTFB);
        }

        @Test
        @DisplayName("Tür bütçeleri KB girilir, ölçüm bayt; kesin büyüktür; ölçülemeyen tür ihlal üretmez")
        void typeBudgets() {
            var m = withThresholds(null, null, null, null);
            m.setMaxJsKb(100);
            m.setMaxImgKb(200);
            m.setMaxFontKb(50);
            m.setMaxThirdPartyKb(300);
            var at = new PageSpeedRules.Measurement(null, null, 1000L * 1024, null,
                    100L * 1024, 200L * 1024, 50L * 1024, 300L * 1024, false);
            assertThat(PageSpeedRules.evaluate(m, at)).isEmpty();   // tam eşik serbest
            var over = new PageSpeedRules.Measurement(null, null, 1000L * 1024, null,
                    100L * 1024 + 1, 200L * 1024 + 1, 50L * 1024 + 1, 300L * 1024 + 1, false);
            assertThat(PageSpeedRules.evaluate(m, over)).containsExactly(PageSpeedRules.BREACH_JS, PageSpeedRules.BREACH_IMG,
                    PageSpeedRules.BREACH_FONT, PageSpeedRules.BREACH_THIRD);
            var unknown = new PageSpeedRules.Measurement(null, null, 1000L * 1024, null, null, null, null, null, false);
            assertThat(PageSpeedRules.evaluate(m, unknown)).isEmpty();
            // Alt sınır olan bayt bile aşıyorsa gerçek değer de aşar → ihlal geçerli
            var lower = new PageSpeedRules.Measurement(null, null, 1000L * 1024, null, 101L * 1024, null, null, null, true);
            assertThat(PageSpeedRules.evaluate(m, lower)).containsExactly(PageSpeedRules.BREACH_JS);
        }

        @Test
        @DisplayName("Asgari boyut: kesin küçükse ihlal (SLOW anahtarı MINSIZE); alt sınır toplamla karar verilmez")
        void minimumSize() {
            var m = withThresholds(null, null, null, null);
            m.setMinPageKb(20);
            assertThat(PageSpeedRules.evaluate(m, PageSpeedRules.Measurement.basic(null, null, 20L * 1024, null))).isEmpty();
            assertThat(PageSpeedRules.evaluate(m, PageSpeedRules.Measurement.basic(null, null, 20L * 1024 - 1, null)))
                    .containsExactly(PageSpeedRules.BREACH_MINSIZE);
            var lower = new PageSpeedRules.Measurement(null, null, 1024L, null, null, null, null, null, true);
            assertThat(PageSpeedRules.evaluate(m, lower)).isEmpty();
            // 0 = eşik yok
            m.setMinPageKb(0);
            assertThat(PageSpeedRules.evaluate(m, PageSpeedRules.Measurement.basic(null, null, 1L, null))).isEmpty();
        }

        @Test
        @DisplayName("Eşik tablosu: yalnız TANIMLI eşikler, ölçülen KB/ms, ihlal ve alt sınır bayrakları; eşik yoksa null")
        void thresholdTable() {
            var m = withThresholds(3000, 500, null, null);
            m.setMaxJsKb(100);
            m.setMinPageKb(10);
            var x = new PageSpeedRules.Measurement(3500, 400, 50L * 1024, 12, 150L * 1024, null, null, null, true);
            String t = PageSpeedRules.thresholdTable(m, x, List.of(PageSpeedRules.BREACH_LOAD, PageSpeedRules.BREACH_JS));
            assertThat(t).isEqualTo("LOAD\t3000\t3500\t1\t0\nTTFB\t500\t400\t0\t0\nMINSIZE\t10\t50\t0\t1\nJS\t100\t150\t1\t1");
            assertThat(PageSpeedRules.thresholdTable(withThresholds(null, null, null, null), x, List.of())).isNull();
            assertThat(PageSpeedRules.breachDetail(m, List.of(PageSpeedRules.BREACH_JS, PageSpeedRules.BREACH_MINSIZE), x))
                    .isEqualTo("JS:100>150,MINSIZE:10>50");
        }

        @Test
        @DisplayName("İhlal delili eşiği ve ölçüleni ÖLÇÜM ANINDAKİ değerlerle taşır")
        void breachDetailCarriesThresholdAndMeasured() {
            var m = withThresholds(3000, 500, 1000, 50);

            String d = PageSpeedRules.breachDetail(m,
                    List.of(PageSpeedRules.BREACH_TTFB, PageSpeedRules.BREACH_SIZE),
                    15094, 2955, 2000L * 1024, 182);

            // Boyut KB cinsinden: eşik KB girilir, delil de aynı birimde okunmalı.
            assertThat(d).isEqualTo("TTFB:500>2955,SIZE:1000>2000");
            assertThat(PageSpeedRules.breachDetail(m, List.of(), 1, 1, 1L, 1)).isNull();
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
