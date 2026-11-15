package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sitemonitor.service.page.PageFetchCore;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Önbellek sınıfı doğruluk tablosu (2026-11-15, kaynak analizi algoritma 4). {@code -} = başlık yok. Tarih sütunları
 * {@code DATE} referansına göre: {@code past} = 1 sa önce, {@code future} = 2 gün sonra, {@code soon} = 10 dk sonra.
 */
class CachePolicyTest {

    static final String DATE = "Sun, 15 Nov 2026 10:00:00 GMT";
    static final ZonedDateTime REF = ZonedDateTime.parse(DATE, DateTimeFormatter.RFC_1123_DATE_TIME);
    static final String PAST = fmt(REF.minusHours(1)), FUTURE = fmt(REF.plusDays(2)), SOON = fmt(REF.plusMinutes(10));
    static final String LM = fmt(REF.minusDays(30));
    static final long NOW = REF.toInstant().toEpochMilli();

    static String fmt(ZonedDateTime z) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(z);
    }

    static String v(String s) {
        if (s == null || s.equals("-")) return null;
        return switch (s) {
            case "past" -> PAST;
            case "future" -> FUTURE;
            case "soon" -> SOON;
            case "lm" -> LM;
            default -> s;
        };
    }

    @ParameterizedTest(name = "{index}: CC=[{0}] Pragma=[{1}] Expires=[{2}] ETag=[{3}] LM=[{4}] → {5} ttl={6} kredi={7}")
    @CsvSource(delimiter = '|', nullValues = "", value = {
            // Cache-Control                     | Pragma   | Expires | ETag  | LM  | sınıf      | ttl   | kredi
            "no-store                            | -        | -       | -     | -   | no_store   | -1    | 0",
            "no-store                            | -        | -       | \"e\" | lm  | no_store   | -1    | 0",
            "public, max-age=31536000, no-store  | -        | -       | -     | -   | no_store   | -1    | 0",
            "no-cache                            | -        | -       | -     | -   | none       | -1    | 0",
            "no-cache                            | -        | -       | \"e\" | -   | revalidate | -1    | 0.5",
            "no-cache                            | -        | -       | -     | lm  | revalidate | -1    | 0.5",
            "no-cache, max-age=86400             | -        | -       | \"e\" | -   | revalidate | -1    | 0.5",
            "max-age=0                           | -        | -       | -     | -   | none       | 0     | 0",
            "max-age=0, must-revalidate          | -        | -       | \"e\" | -   | revalidate | 0     | 0.5",
            "max-age=60                          | -        | -       | -     | -   | short      | 60    | 0",
            "max-age=60                          | -        | -       | \"e\" | -   | short      | 60    | 0.5",
            "max-age=3599                        | -        | -       | -     | lm  | short      | 3599  | 0.5",
            "max-age=3600                        | -        | -       | -     | -   | stored     | 3600  | 0.5",
            "public, max-age=86400               | -        | -       | -     | -   | stored     | 86400 | 1.0",
            "max-age=86400, immutable            | -        | -       | \"e\" | lm  | stored     | 86400 | 1.0",
            "-                                   | -        | past    | -     | -   | none       | 0     | 0",
            "-                                   | -        | past    | \"e\" | -   | revalidate | 0     | 0.5",
            "-                                   | -        | past    | -     | lm  | revalidate | 0     | 0.5",
            "-                                   | -        | future  | -     | -   | stored     | 172800| 1.0",
            "-                                   | -        | soon    | -     | -   | short      | 600   | 0",
            "-                                   | -        | 0       | -     | -   | none       | 0     | 0",
            "-                                   | -        | -1      | \"e\" | -   | revalidate | 0     | 0.5",
            "max-age=600                         | -        | future  | -     | -   | short      | 600   | 0",
            "-                                   | no-cache | future  | -     | -   | none       | -1    | 0",
            "-                                   | no-cache | -       | \"e\" | -   | revalidate | -1    | 0.5",
            // Chromium kuralı (2026-11-15): yanıttaki Pragma: no-cache, Cache-Control'de ömür olsa da no-cache sayılır
            "max-age=86400                       | no-cache | -       | -     | -   | none       | -1    | 0",
            "max-age=86400                       | no-cache | -       | \"e\" | -   | revalidate | -1    | 0.5",
            "public                              | no-cache | -       | -     | lm  | revalidate | -1    | 0.5",
            "no-store                            | no-cache | -       | \"e\" | -   | no_store   | -1    | 0",
            "max-age=86400                       | x-no-cache | -     | -     | -   | stored     | 86400 | 1.0",
            "no-cache=\"Set-Cookie\", max-age=600| -        | -       | -     | -   | short      | 600   | 0",
            "no-cache=\"Set-Cookie, X-A\", max-age=7200| -  | -       | -     | -   | stored     | 7200  | 0.5",
            // Tırnak içinde kaçırılmış tırnak tırnağı kapatmaz: ardından gelen no-store ayrı yönergedir / tırnak içinde metindir
            "no-cache=\"a\\\"b\", no-store          | -        | -       | -     | -   | no_store   | -1    | 0",
            "no-cache=\"a\\\"b, no-store\"          | -        | -       | -     | -   | none       | -     | 0",
            "no-cache=\"a\\\"b\", max-age=86400     | -        | -       | -     | -   | stored     | 86400 | 1.0",
            "s-maxage=86400                      | -        | -       | -     | -   | none       | -     | 0",
            "s-maxage=86400                      | -        | -       | -     | lm  | heuristic  | -     | 0.5",
            "public, s-maxage=600, max-age=7200  | -        | -       | -     | -   | stored     | 7200  | 0.5",
            "-                                   | -        | -       | -     | -   | none       | -     | 0",
            "-                                   | -        | -       | \"e\" | -   | revalidate | -     | 0.5",
            "-                                   | -        | -       | -     | lm  | heuristic  | -     | 0.5",
            "private                             | -        | -       | \"e\" | lm  | heuristic  | -     | 0.5",
            "-                                   | -        | -       | -     | yok | revalidate | -     | 0.5",
            "max-age=abc                         | -        | -       | -     | -   | none       | 0     | 0",
            "max-age=abc                         | -        | -       | \"e\" | -   | revalidate | 0     | 0.5",
            "max-age=600, max-age=86400          | -        | -       | -     | -   | short      | 600   | 0",
            "MAX-AGE = \"86400\"                 | -        | -       | -     | -   | stored     | 86400 | 1.0",
            "max-age=99999999999999999999        | -        | -       | -     | -   | stored     | 2147483648 | 1.0",
            // RFC 9110 §5.6.7 tarih biçimleri (DATE = Sun, 15 Nov 2026 10:00:00 GMT): gün adı doğrulanmaz
            "-                                   | -        | Tuesday, 17-Nov-26 10:00:00 GMT | - | - | stored | 172800 | 1.0",
            "-                                   | -        | Tue Nov 17 10:00:00 2026 | -     | -   | stored     | 172800| 1.0",
            "-                                   | -        | Wed Nov  4 10:00:00 2026 | -     | -   | none       | 0     | 0",
            "-                                   | -        | Fri, 17 Nov 2026 10:00:00 GMT | - | -   | stored     | 172800| 1.0",
            "-                                   | -        | 17 Nov 2026 10:00:00 GMT | -     | -   | stored     | 172800| 1.0",
            "-                                   | -        | Tue, 17 Nov 2026 13:00:00 +0300 | - | - | stored     | 172800| 1.0",
            "-                                   | -        | Sunday, 06-Nov-94 08:49:37 GMT | - | - | none     | 0     | 0",
            "-                                   | -        | Mon, 31 Feb 2027 10:00:00 GMT | - | -   | none       | 0     | 0",
            "-                                   | -        | Tue, 17 Nov 2026 25:00:00 GMT | - | -   | none       | 0     | 0",
            "-                                   | -        | -       | -     | Mon, 16 Oct 2026 10:00:00 GMT | heuristic | - | 0.5",
            "-                                   | -        | -       | -     | Friday, 16-Oct-26 10:00:00 GMT | heuristic | - | 0.5",
            "-                                   | -        | -       | -     | Fri Oct 16 10:00:00 2026 | heuristic | - | 0.5",
    })
    void truthTable(String cc, String pragma, String expires, String etag, String lm, String cls, String ttl, String credit) {
        String expected = cls;
        CachePolicy.Policy p = CachePolicy.classify(v(cc), v(pragma), v(expires), DATE, v(etag), v(lm), 0L);
        assertThat(p.cacheClass()).isEqualTo(expected);
        assertThat(p.ttlS()).isEqualTo(ttl == null || ttl.equals("-") ? null : Long.valueOf(ttl.trim()));
        if (credit != null && !credit.equals("-")) {
            assertThat(CachePolicy.credit(p.cacheClass(), p.ttlS(), p.validator())).isEqualTo(Double.parseDouble(credit));
        }
    }

    @Test
    @DisplayName("sezgisel ömür: (Date − Last-Modified) / 10 — Date yoksa şimdi; gelecekteki Last-Modified → 0; okunamayan → doğrulayıcı")
    void heuristicEstimate() {
        CachePolicy.Policy p = CachePolicy.classify(null, null, null, DATE, null, LM, 0L);
        assertThat(p.cacheClass()).isEqualTo(CachePolicy.HEURISTIC);
        assertThat(p.heuristicS()).isEqualTo(30L * 86_400 / 10);
        assertThat(p.validator()).isTrue();
        CachePolicy.Policy noDate = CachePolicy.classify(null, null, null, null, null, LM, NOW);
        assertThat(noDate.heuristicS()).isEqualTo(30L * 86_400 / 10);
        CachePolicy.Policy future = CachePolicy.classify(null, null, null, DATE, null, FUTURE, 0L);
        assertThat(future.cacheClass()).isEqualTo(CachePolicy.HEURISTIC);
        assertThat(future.heuristicS()).isZero();
        CachePolicy.Policy bad = CachePolicy.classify(null, null, null, DATE, null, "dün", 0L);
        assertThat(bad.cacheClass()).isEqualTo(CachePolicy.REVALIDATE);
        assertThat(bad.heuristicS()).isNull();
        assertThat(CachePolicy.classify("max-age=0", null, null, DATE, null, LM, 0L).heuristicS())
                .as("ömür verildiyse sezgisel yok").isNull();
    }

    @Test
    @DisplayName("Expires: Date yoksa şimdiye göre; boş Cache-Control yok sayılır; Pragma: no-cache her durumda sayılır")
    void expiresAndBlank() {
        assertThat(CachePolicy.classify(null, null, FUTURE, null, null, null, NOW).ttlS()).isEqualTo(172_800L);
        assertThat(CachePolicy.classify("   ", "no-cache", null, null, "\"e\"", null, NOW).cacheClass())
                .isEqualTo(CachePolicy.REVALIDATE);
        assertThat(CachePolicy.classify("", null, null, null, null, null, NOW).cacheClass()).isEqualTo(CachePolicy.NONE);
    }

    @Test
    @DisplayName("yönerge ayrıştırma: tırnaklı virgül bölmez, ad küçük harf, değer tırnaksız, değersiz → null")
    void directives() {
        List<String[]> d = CachePolicy.directives("Public, no-cache=\"Set-Cookie, X-Foo\", MAX-AGE=\"60\", , immutable");
        assertThat(d).extracting(x -> x[0]).containsExactly("public", "no-cache", "max-age", "immutable");
        assertThat(d.get(1)[1]).isEqualTo("Set-Cookie, X-Foo");
        assertThat(d.get(2)[1]).isEqualTo("60");
        assertThat(d.get(0)[1]).isNull();
        // kapanmayan tırnak: kalan metin tek değer — taşma / döngü yok
        assertThat(CachePolicy.directives("no-cache=\"a, b")).hasSize(1);
        // kaçırılmış tırnak tırnağı kapatmaz; sondaki tek ters bölü taşmaz
        assertThat(CachePolicy.directives("no-cache=\"a\\\"b\", no-store")).extracting(x -> x[0])
                .containsExactly("no-cache", "no-store");
        assertThat(CachePolicy.directives("no-cache=\"a\\\"b, no-store\"")).extracting(x -> x[0]).containsExactly("no-cache");
        assertThat(CachePolicy.directives("no-cache=\"a\\")).hasSize(1);
        assertThat(CachePolicy.deltaSeconds("")).isZero();
        assertThat(CachePolicy.deltaSeconds("-5")).isZero();
        assertThat(CachePolicy.deltaSeconds("120")).isEqualTo(120L);
    }

    @Test
    @DisplayName("tarih: üç biçim, gün adı yok sayılır, iki haneli yıl 50 yıl kuralı, bölge; bozuk → yedek değer")
    void httpDates() {
        long imf = ZonedDateTime.parse("Sun, 06 Nov 1994 08:49:37 GMT", DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        long fb = Long.MIN_VALUE;
        for (String s : List.of("Sun, 06 Nov 1994 08:49:37 GMT", "Sunday, 06-Nov-94 08:49:37 GMT", "Sun Nov  6 08:49:37 1994",
                "Thu, 06 Nov 1994 08:49:37 GMT", "06 Nov 1994 08:49:37 GMT", "sun, 06 nov 1994 08:49:37 gmt",
                "Sun, 06 Nov 1994 11:49:37 +0300", "Nov 6 08:49:37 1994", "Sunday, 06-Nov-1994 08:49:37 GMT")) {
            assertThat(CachePolicy.parseHttpDate(s, NOW, fb)).as(s).isEqualTo(imf);
        }
        // İki haneli yıl: şimdi 2026 → "76" 2076 (tam 50 yıl ileri), "77" 1977 (50 yıldan fazla ileri), "26" 2026
        assertThat(CachePolicy.parseHttpDate("Wed, 01-Jan-76 00:00:00 GMT", NOW, fb))
                .isEqualTo(ZonedDateTime.parse("2076-01-01T00:00:00Z").toInstant().toEpochMilli());
        assertThat(CachePolicy.parseHttpDate("Sat, 01-Jan-77 00:00:00 GMT", NOW, fb))
                .isEqualTo(ZonedDateTime.parse("1977-01-01T00:00:00Z").toInstant().toEpochMilli());
        assertThat(CachePolicy.parseHttpDate("Thu, 01-Jan-26 00:00:00 GMT", NOW, fb))
                .isEqualTo(ZonedDateTime.parse("2026-01-01T00:00:00Z").toInstant().toEpochMilli());
        // Artık saniye 59 sayılır
        assertThat(CachePolicy.parseHttpDate("Sun, 06 Nov 1994 08:49:60 GMT", NOW, fb)).isEqualTo(imf + 22_000);
        for (String bad : List.of("0", "-1", "dün", "Sun", "Sun, 06 Foo 1994 08:49:37 GMT", "Sun, 06 Nov 94x 08:49:37 GMT",
                "Sun, 06 Nov 1994 08:49 GMT", "Sun, 06 Nov 1994 08:49:37 EST", "Sun, 06-Nov 1994 08:49:37 GMT",
                "Sun, 06 Nov 1994 08:49:37 GMT extra", "Sun, 06 Nov 199 08:49:37 GMT", "x".repeat(65))) {
            assertThat(CachePolicy.parseHttpDate(bad, NOW, fb)).as(bad).isEqualTo(fb);
        }
    }

    @Test
    @DisplayName("tek doğrulayıcı kuralı: boş ETag / Last-Modified doğrulayıcı değil — kısa ömür + boş ETag kredi 0")
    void blankValidator() {
        assertThat(CachePolicy.hasValidator("", "  ")).isFalse();
        assertThat(CachePolicy.hasValidator(null, null)).isFalse();
        assertThat(CachePolicy.hasValidator("\"e\"", null)).isTrue();
        assertThat(CachePolicy.hasValidator(null, LM)).isTrue();
        CachePolicy.Policy p = CachePolicy.classify("max-age=600", null, null, DATE, "", null, 0L);
        assertThat(p.cacheClass()).isEqualTo(CachePolicy.SHORT);
        assertThat(p.validator()).isFalse();
        assertThat(CachePolicy.credit(p.cacheClass(), p.ttlS(), p.validator())).isZero();
        assertThat(CachePolicy.classify("no-cache", null, null, DATE, " ", "", 0L).cacheClass()).isEqualTo(CachePolicy.NONE);
    }

    @Test
    @DisplayName("birleşik Cache-Control satırları: sonraki satır yalnız BÜTÜN sığarsa eklenir — yarım yönerge (no-st / max-age=3) yazılmaz")
    void joinedHeaderLines() {
        String first290 = "max-age=60, ext=" + "a".repeat(274);
        assertThat(first290).hasSize(290);
        String j = PageFetchCore.joinHeaderValues(List.of(first290, "no-store"));
        assertThat(j).hasSize(300).endsWith(", no-store");
        assertThat(CachePolicy.classify(j, null, null, DATE, null, null, 0L).cacheClass()).isEqualTo(CachePolicy.NO_STORE);
        String first295 = "max-age=60, ext=" + "a".repeat(279);
        String cut = PageFetchCore.joinHeaderValues(List.of(first295, "no-store"));
        assertThat(cut).as("sığmayan satır yarım yazılmaz").isEqualTo(first295);
        assertThat(CachePolicy.classify(cut, null, null, DATE, null, null, 0L).cacheClass()).isEqualTo(CachePolicy.SHORT);
        String first285 = "max-age=60, ext=" + "a".repeat(269);
        String skip = PageFetchCore.joinHeaderValues(List.of(first285, "max-age=360000, immutable", "  ", "no-store"));
        assertThat(skip).as("sığmayan satır atlanır, sonraki kısa satır eklenir").isEqualTo(first285 + ", no-store");
        assertThat(skip).doesNotContain("max-age=3600");
        // Tek uzun satır eski kuralla kırpılır; 8 satırdan sonrasına bakılmaz; boş / yok → null
        assertThat(PageFetchCore.joinHeaderValues(List.of("x".repeat(400)))).hasSize(300);
        List<String> many = new ArrayList<>(Collections.nCopies(8, "public"));
        many.add("no-store");
        assertThat(PageFetchCore.joinHeaderValues(many)).doesNotContain("no-store");
        assertThat(PageFetchCore.joinHeaderValues(List.of(" ", ""))).isNull();
        assertThat(PageFetchCore.joinHeaderValues(List.of())).isNull();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    @DisplayName("dev başlık: doğrusal, okunan karakter ve yönerge sınırlı (ek yönergeler okunmaz)")
    void boundedOnHugeHeader() {
        String huge = "a, ".repeat(200_000) + "no-store";
        List<String[]> d = CachePolicy.directives(huge);
        assertThat(d.size()).isLessThanOrEqualTo(CachePolicy.MAX_DIRECTIVES);
        assertThat(CachePolicy.classify(huge, null, null, DATE, null, null, 0L).cacheClass())
                .as("sınırın ötesindeki no-store okunmaz → ömürsüz, doğrulayıcısız").isEqualTo(CachePolicy.NONE);
        String quotes = "\"".repeat(500_000);
        assertThat(CachePolicy.directives(quotes).size()).isLessThanOrEqualTo(1);
        String escapes = "\"" + "\\".repeat(500_000);
        assertThat(CachePolicy.directives(escapes).size()).isLessThanOrEqualTo(1);
        assertThat(CachePolicy.parseHttpDate(" ".repeat(60) + "x".repeat(10), NOW, -7L)).isEqualTo(-7L);
        assertThat(CachePolicy.deltaSeconds("9".repeat(100_000))).isEqualTo(CachePolicy.MAX_DELTA_S);
    }

    @Test
    @DisplayName("nesne: kaydedilmiş sınıf esas; sınıfsız nesne eski ömür kuralı (kredi birebir, doğrulayıcı yok sayılır)")
    void itemFallback() {
        ScanItem old = new ScanItem();
        old.validator = true;
        for (Object[] row : new Object[][]{{null, 0.0, "none"}, {-1L, 0.0, "none"}, {0L, 0.0, "none"}, {600L, 0.0, "short"},
                {3_600L, 0.5, "stored"}, {86_400L, 1.0, "stored"}}) {
            old.cacheTtlS = (Long) row[0];
            assertThat(CachePolicy.creditOf(old)).as(String.valueOf(row[0])).isEqualTo((double) row[1]);
            assertThat(CachePolicy.classOf(old)).isEqualTo(row[2]);
        }
        ScanItem neu = new ScanItem();
        neu.cacheClass = CachePolicy.SHORT;
        neu.cacheTtlS = 600L;
        neu.validator = true;
        assertThat(CachePolicy.creditOf(neu)).isEqualTo(0.5);
        neu.validator = false;
        assertThat(CachePolicy.creditOf(neu)).isZero();
    }

    @Test
    @DisplayName("ikinci ziyaret: stored hiçbir şey; revalidate / heuristic istek ama gövde yok; no_store doğrulayıcıyla da tüm gövde")
    void repeatVisit() {
        record Row(String cls, Long heuristic, boolean validator, boolean request, boolean body) {}
        for (Row r : List.of(
                new Row(CachePolicy.STORED, null, false, false, false),
                new Row(CachePolicy.REVALIDATE, null, true, true, false),
                new Row(CachePolicy.HEURISTIC, 86_400L, true, false, false),
                new Row(CachePolicy.HEURISTIC, 60L, true, true, false),
                new Row(CachePolicy.HEURISTIC, null, true, true, false),
                new Row(CachePolicy.SHORT, null, true, true, false),
                new Row(CachePolicy.SHORT, null, false, true, true),
                new Row(CachePolicy.NONE, null, false, true, true),
                new Row(CachePolicy.NO_STORE, null, true, true, true))) {
            ScanItem i = new ScanItem();
            i.cacheClass = r.cls();
            i.cacheHeuristicS = r.heuristic();
            i.validator = r.validator();
            assertThat(CachePolicy.repeatVisit(i)).as(r.toString()).isEqualTo(new CachePolicy.Repeat(r.request(), r.body()));
        }
        ScanItem legacy = new ScanItem();
        legacy.cacheTtlS = 600L;
        legacy.validator = true;
        assertThat(CachePolicy.repeatVisit(legacy)).as("sınıfsız: eski kural").isEqualTo(new CachePolicy.Repeat(true, false));
    }
}
