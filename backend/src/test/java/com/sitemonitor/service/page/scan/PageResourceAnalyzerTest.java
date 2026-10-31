package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kaynak analizinin saf hesabı: şelale tahmini, not, öneriler, görsel etkisi, kategoriler, notlar. */
class PageResourceAnalyzerTest {

    static final long KB = 1024, MB = 1024 * 1024;

    static ScanItem html(long ttfb, long ms, long bytes) {
        ScanItem i = item("https://a.com/", "HTML", bytes, ms);
        i.ttfbMs = ttfb;
        i.contentType = "text/html";
        i.compressed = true;
        return i;
    }

    static ScanItem item(String url, String cat, long bytes, long ms) {
        ScanItem i = new ScanItem();
        i.url = url;
        i.category = cat;
        i.bytes = bytes;
        i.durationMs = ms;
        i.status = 200;
        i.pageLoad = !"DOCUMENT".equals(cat) && !"LINK".equals(cat);
        i.cacheTtlS = 31_536_000L;
        i.compressed = true;
        return i;
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    @Test
    @DisplayName("şelale: HTML bittikten sonra 6 bağlantıya keşif sırasıyla; stilden bulunan stil inmeden başlamaz; lazy / belge / bağlantı tahmine girmez")
    void simulate() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(100, 500, 20 * KB));
        for (int k = 0; k < 7; k++) items.add(item("https://a.com/i" + k + ".png", "IMAGE", 10 * KB, 100));
        ScanItem css = item("https://a.com/s.css", "CSS", 5 * KB, 300);
        items.add(css);                                                  // 8. sıra
        ScanItem font = item("https://a.com/f.woff2", "FONT", 20 * KB, 50);
        font.fromCss = true;
        font.parent = 8;
        items.add(font);
        ScanItem lazy = item("https://a.com/lazy.jpg", "IMAGE", 100 * KB, 999);
        lazy.lazy = true;
        items.add(lazy);
        items.add(item("https://a.com/doc.pdf", "DOCUMENT", MB, 2000));
        items.add(item("https://a.com/about", "LINK", 30 * KB, 400));

        long load = PageResourceAnalyzer.simulate(items);
        assertThat(items.get(0).startMs).isEqualTo(0L);
        for (int k = 1; k <= 6; k++) assertThat(items.get(k).startMs).as("ilk 6 görsel HTML biter bitmez").isEqualTo(500L);
        assertThat(items.get(7).startMs).as("7. görsel bir bağlantı boşalınca").isEqualTo(600L);
        assertThat(css.startMs).isEqualTo(600L);                       // ikinci boşalan bağlantı
        assertThat(font.startMs).as("yazı tipi stil inince (600+300)").isEqualTo(900L);
        assertThat(lazy.startMs).isNull();
        assertThat(items.get(11).startMs).isNull();
        assertThat(items.get(12).startMs).isNull();
        assertThat(load).isEqualTo(950L);
    }

    @Test
    @DisplayName("eğri: sınırlarda değer, aralarda doğrusal, dışında uç değer; harf eşikleri")
    void curveAndLetter() {
        assertThat(PageResourceAnalyzer.curve(500, PageResourceAnalyzer.LOAD_CURVE)).isEqualTo(100);
        assertThat(PageResourceAnalyzer.curve(2500, PageResourceAnalyzer.LOAD_CURVE)).isEqualTo(83);   // 90→75 ortası 82,5
        assertThat(PageResourceAnalyzer.curve(20_000, PageResourceAnalyzer.LOAD_CURVE)).isEqualTo(0);
        assertThat(PageResourceAnalyzer.curve(0, PageResourceAnalyzer.ERROR_CURVE)).isEqualTo(100);
        assertThat(PageResourceAnalyzer.letter(90)).isEqualTo("A");
        assertThat(PageResourceAnalyzer.letter(89)).isEqualTo("B");
        assertThat(PageResourceAnalyzer.letter(70)).isEqualTo("C");
        assertThat(PageResourceAnalyzer.letter(60)).isEqualTo("D");
        assertThat(PageResourceAnalyzer.letter(50)).isEqualTo("E");
        assertThat(PageResourceAnalyzer.letter(49)).isEqualTo("F");
        assertThat(PageResourceAnalyzer.WEIGHTS.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    @DisplayName("hızlı, hafif, düzenli sayfa: A notu, tek öneri ALL_GOOD, özet sayılar sayfa yüküyle sınırlı")
    void cleanPage() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(120, 300, 30 * KB));
        items.add(item("https://a.com/s.css", "CSS", 20 * KB, 80));
        items.add(item("https://a.com/a.js", "JS", 40 * KB, 90));
        ScanItem img = item("https://a.com/logo.webp", "IMAGE", 30 * KB, 60);
        img.contentType = "image/webp";
        items.add(img);
        items.add(item("https://a.com/doc.pdf", "DOCUMENT", 3 * MB, 1500));
        items.add(item("https://a.com/about", "LINK", 25 * KB, 200));
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);

        assertThat(r.grade()).isGreaterThanOrEqualTo(90);
        assertThat(r.gradeLetter()).isEqualTo("A");
        assertThat(r.requestCount()).isEqualTo(4);
        assertThat(r.pageBytes()).isEqualTo(120 * KB);
        assertThat(r.documentCount()).isEqualTo(1);
        assertThat(r.documentBytes()).isEqualTo(3 * MB);
        assertThat(r.linkCount()).isEqualTo(1);
        assertThat(r.imageCount()).isEqualTo(1);
        assertThat(r.capped()).isFalse();
        List<Map<String, Object>> recs = get(r.analysis(), "recommendations");
        assertThat(recs).extracting(m -> m.get("code")).containsExactly("ALL_GOOD");
        List<Map<String, Object>> notes = get(r.analysis(), "notes");
        assertThat(notes).extracting(m -> m.get("code")).containsExactly("JS_NOT_EXECUTED");
        List<Map<String, Object>> scores = get(r.analysis(), "scores");
        assertThat(scores).extracting(m -> m.get("key")).containsExactly("load_time", "page_weight", "images", "requests",
                "server_response", "errors", "compression", "caching");
        List<Map<String, Object>> cats = get(r.analysis(), "categories");
        assertThat(cats).extracting(m -> m.get("category")).containsExactly("HTML", "CSS", "JS", "IMAGE", "DOCUMENT", "LINK");
    }

    @Test
    @DisplayName("ağır sayfa: büyük görseller, sıkıştırılmamış metin, önbelleksiz, kırık, yavaş sunucu, 3. taraf, kırık bağlantı → öneriler ve düşük not")
    void heavyPage() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(2000, 2500, 120 * KB));
        items.get(0).compressed = false;
        ScanItem big = item("https://a.com/hero.jpg", "IMAGE", 2 * MB, 3000);
        big.contentType = "image/jpeg";
        big.width = 4000;
        big.height = 3000;
        items.add(big);
        ScanItem mid = item("https://a.com/card.png", "IMAGE", 80 * KB, 300);
        mid.contentType = "image/png";
        items.add(mid);
        ScanItem js = item("https://a.com/app.js", "JS", 900 * KB, 1200);
        js.compressed = false;
        js.cacheTtlS = null;
        items.add(js);
        ScanItem broken = item("https://a.com/missing.css", "CSS", 0, 50);
        broken.status = 404;
        items.add(broken);
        ScanItem third = item("https://cdn.other.net/x.js", "JS", 3 * MB, 900);
        third.thirdParty = true;
        items.add(third);
        ScanItem redirected = item("https://a.com/r.png", "IMAGE", 5 * KB, 100);
        redirected.redirects = 2;
        items.add(redirected);
        ScanItem deadLink = item("https://a.com/old", "LINK", 0, 30);
        deadLink.status = 0;
        deadLink.error = "TIMEOUT";
        items.add(deadLink);
        ScanItem hugeDoc = item("https://a.com/k.pdf", "DOCUMENT", 12 * MB, 4000);
        items.add(hugeDoc);

        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items,
                new PageResourceAnalyzer.Limits(true, 1500, false, 300, false, 200, 3));
        assertThat(r.grade()).isLessThan(60);
        assertThat(r.failedCount()).as("kırık nesne + kırık bağlantı").isEqualTo(2);
        assertThat(r.capped()).isTrue();
        List<Map<String, Object>> recs = get(r.analysis(), "recommendations");
        List<Object> codes = recs.stream().map(m -> m.get("code")).toList();
        assertThat(codes).contains("BROKEN_RESOURCES", "LARGE_IMAGES", "OVERSIZED_PAGE", "SLOW_SERVER", "MODERN_IMAGE_FORMAT",
                "OVERSIZED_IMAGE_DIMENSIONS", "UNCOMPRESSED_TEXT", "NO_CACHE", "HEAVY_JS", "THIRD_PARTY_HEAVY", "SLOW_OBJECTS",
                "REDIRECTED_OBJECTS", "BROKEN_LINKS", "LARGE_DOCUMENTS").doesNotContain("ALL_GOOD");
        assertThat(PageResourceAnalyzer.RECOMMENDATION_CODES).containsAll(codes.stream().map(String::valueOf).toList());
        // önem sırası: önce high
        assertThat(recs.get(0).get("severity")).isEqualTo("high");
        Map<String, Object> large = recs.stream().filter(m -> "LARGE_IMAGES".equals(m.get("code"))).findFirst().orElseThrow();
        Map<String, Object> lp = get(large, "params");
        assertThat(lp).containsEntry("count", 1).containsEntry("limit_kb", 200);
        assertThat((Object) large.get("item_urls")).isEqualTo(List.of("https://a.com/hero.jpg"));
        assertThat((Long) large.get("saving_bytes")).isEqualTo(Math.round(2 * MB * 0.6));
        Map<String, Object> tp = recs.stream().filter(m -> "THIRD_PARTY_HEAVY".equals(m.get("code"))).findFirst().orElseThrow();
        Map<String, Object> tpp = get(tp, "params");
        assertThat(tpp.get("hosts")).isEqualTo(1);
        assertThat(tpp.get("host_list")).isEqualTo(List.of("cdn.other.net"));

        Map<String, Object> img = get(r.analysis(), "images");
        assertThat(img.get("count")).isEqualTo(3);
        assertThat(img.get("large_count")).isEqualTo(1);
        long imgBytes = 2 * MB + 80 * KB + 5 * KB;
        assertThat(img.get("bytes")).isEqualTo(imgBytes);
        assertThat(img.get("est_ms_4g")).isEqualTo(Math.round(imgBytes * 1000.0 / (9_000_000 / 8.0)));
        assertThat(img.get("est_ms_3g")).isEqualTo(Math.round(imgBytes * 1000.0 / (1_600_000 / 8.0)));
        assertThat(img.get("saving_bytes")).isEqualTo(Math.round(2 * MB * 0.6) + Math.round(80 * KB * 0.3));
        List<Map<String, Object>> top = get(img, "top");
        assertThat(top.get(0).get("url")).isEqualTo("https://a.com/hero.jpg");
        assertThat(top.get(0).get("width")).isEqualTo(4000);

        List<Map<String, Object>> notes = get(r.analysis(), "notes");
        assertThat(notes).extracting(m -> m.get("code")).containsExactly("JS_NOT_EXECUTED", "CAPPED_OBJECTS", "EXCLUDES_APPLIED");
        assertThat(PageResourceAnalyzer.NOTE_CODES).contains("CAPPED_TIME", "CAPPED_BYTES");
        Map<String, Object> timing = get(r.analysis(), "timing");
        assertThat(timing.get("parallel")).isEqualTo(6);
        assertThat(timing.get("load_ms")).isEqualTo(r.loadMs());
    }

    @Test
    @DisplayName("üçüncü taraf ve sıkıştırma: 3. taraf metin sıkıştırma / önbellek puanına girmez")
    void firstPartyRules() {
        ScanItem t = item("https://cdn.x.net/a.js", "JS", 50 * KB, 10);
        t.thirdParty = true;
        t.compressed = false;
        assertThat(PageResourceAnalyzer.compressibleText(t)).isFalse();
        assertThat(PageResourceAnalyzer.cacheableStatic(t)).isFalse();
        ScanItem small = item("https://a.com/a.css", "CSS", 500, 10);
        assertThat(PageResourceAnalyzer.compressibleText(small)).as("1 KB altı sayılmaz").isFalse();
        ScanItem svg = item("https://a.com/i.svg", "IMAGE", 5 * KB, 10);
        svg.contentType = "image/svg+xml";
        assertThat(PageResourceAnalyzer.compressibleText(svg)).isTrue();
        assertThat(PageResourceAnalyzer.legacyFormat(svg)).isFalse();
        ScanItem byExt = item("https://a.com/x.JPG", "IMAGE", 70 * KB, 10);
        assertThat(PageResourceAnalyzer.legacyFormat(byExt)).isTrue();
    }
}
