package com.sitemonitor.service.page.scan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Görsel aracının nesne alanları (2026-11-14): bildirilen boyut HTML'den ({@code declared_width} / {@code declared_height}),
 * önerilen hedef genişlik kuralı ({@code suggested_width} / {@code suggested_reason}), saklanan satırdan geri kurma
 * ({@link ScanItem#fromStored}) ve analizle AYNI performans hesabı ({@link PageResourceAnalyzer#performance}).
 */
class PageResourceImageFieldsTest {

    static final String BASE = "https://ornek.com.tr/";
    static final long KB = 1024, MB = 1024 * 1024;

    private static Map<String, PageResourceDiscovery.Candidate> discover(String body) {
        PageResourceDiscovery.Found f = PageResourceDiscovery.discover(("<html><body>" + body + "</body></html>")
                .getBytes(StandardCharsets.UTF_8), BASE, null);
        return f.pageLoad().stream().collect(Collectors.toMap(c -> c.url().substring(BASE.length()), Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
    }

    @Test
    @DisplayName("bildirilen boyut: yalnız sayısal width / height (px soneki olur); %, auto, boş, 0 ve saçma değerler yok sayılır")
    void declaredFromAttributes() {
        Map<String, PageResourceDiscovery.Candidate> p = discover("""
                <img src="a.jpg" width="600" height="400">
                <img src="b.jpg" width="300px" height=" 200 ">
                <img src="c.jpg" width="100%" height="auto">
                <img src="d.jpg">
                <img src="e.jpg" width="0" height="50">
                <img src="f.jpg" width="99999999999" height="10">
                <img src="g.jpg" width="12.5">
                <img src="h.jpg" width="640" height="auto">
                <input type="image" src="btn.png" width="120" height="40">
                <video poster="poster.jpg" width="1280" height="720"></video>
                """);
        assertThat(p.get("a.jpg").declaredWidth()).isEqualTo(600);
        assertThat(p.get("a.jpg").declaredHeight()).isEqualTo(400);
        assertThat(p.get("b.jpg").declaredWidth()).isEqualTo(300);
        assertThat(p.get("b.jpg").declaredHeight()).isEqualTo(200);
        for (String none : List.of("c.jpg", "d.jpg", "e.jpg", "f.jpg", "g.jpg")) {
            assertThat(p.get(none).declaredWidth()).as(none).isNull();
            assertThat(p.get(none).declaredHeight()).as(none).isNull();
        }
        assertThat(p.get("h.jpg").declaredWidth()).isEqualTo(640);
        assertThat(p.get("h.jpg").declaredHeight()).as("yükseklik sayısal değil").isNull();
        assertThat(p.get("btn.png").declaredWidth()).isEqualTo(120);
        assertThat(p.get("poster.jpg").declaredWidth()).isEqualTo(1280);
        assertThat(p.get("poster.jpg").declaredHeight()).isEqualTo(720);
    }

    @Test
    @DisplayName("aynı görsel birden çok öğede: en geniş kullanım geçerli; bir kullanım boyutsuzsa bildirilen boyut YOK; preload boyutu bozmaz")
    void declaredMergedAcrossUses() {
        Map<String, PageResourceDiscovery.Candidate> p = discover("""
                <link rel="preload" as="image" href="hero.jpg">
                <img src="hero.jpg" width="200" height="100">
                <img src="hero.jpg" width="800" height="400">
                <img src="hero.jpg" width="400" height="200">
                <img src="mixed.jpg" width="200" height="100">
                <img src="mixed.jpg">
                """);
        assertThat(p.get("hero.jpg").declaredWidth()).as("en geniş kullanım").isEqualTo(800);
        assertThat(p.get("hero.jpg").declaredHeight()).isEqualTo(400);
        assertThat(p.get("mixed.jpg").declaredWidth()).as("boyutsuz kullanım var → bilinmiyor").isNull();
        assertThat(p.get("mixed.jpg").declaredHeight()).isNull();
    }

    @Test
    @DisplayName("picture: seçilen source'un kendi width'i varsa o, yoksa img'ninki; görsel olmayan adaylar boyut taşımaz")
    void declaredInPicture() {
        Map<String, PageResourceDiscovery.Candidate> p = discover("""
                <picture><source srcset="p1.webp" type="image/webp"><img src="p1.jpg" width="500" height="250"></picture>
                <picture><source srcset="p2.webp" type="image/webp" width="900" height="300"><img src="p2.jpg" width="500"></picture>
                <script src="app.js" width="10"></script>
                <iframe src="frame.html" width="300" height="200"></iframe>
                """);
        assertThat(p.get("p1.webp").declaredWidth()).isEqualTo(500);
        assertThat(p.get("p1.webp").declaredHeight()).isEqualTo(250);
        assertThat(p.get("p2.webp").declaredWidth()).isEqualTo(900);
        assertThat(p.get("p2.webp").declaredHeight()).isEqualTo(300);
        assertThat(p.get("app.js").declaredWidth()).isNull();
        assertThat(p.get("frame.html").declaredWidth()).isNull();
        // Eski yapıcılar bildirilen boyutsuz aday üretir
        assertThat(new PageResourceDiscovery.Candidate("u", "IMAGE", true, false, false).declaredWidth()).isNull();
        assertThat(new PageResourceDiscovery.Candidate("u", "IMAGE", true, false, false, false).declaredHeight()).isNull();
    }

    @Test
    @DisplayName("önerilen genişlik: declared (doğal ≥ 2,2 × bildirilen → 2 × bildirilen), max_width (doğal > 2000 → 1920), yoksa öneri yok; asla doğaldan büyük değil")
    void suggestWidthRules() {
        // declared: eşik tam %10
        assertThat(PageResourceAnalyzer.suggestWidth(2200, 1000)).isEqualTo(new PageResourceAnalyzer.WidthSuggestion(2000, "declared"));
        assertThat(PageResourceAnalyzer.suggestWidth(4000, 600)).isEqualTo(new PageResourceAnalyzer.WidthSuggestion(1200, "declared"));
        assertThat(PageResourceAnalyzer.suggestWidth(1320, 600)).isEqualTo(new PageResourceAnalyzer.WidthSuggestion(1200, "declared"));
        assertThat(PageResourceAnalyzer.suggestWidth(1319, 600)).as("eşiğin 1 px altı, 2000'den dar").isNull();
        // declared kuralı tutmuyor ama görsel çok geniş → max_width
        assertThat(PageResourceAnalyzer.suggestWidth(2199, 1000)).isEqualTo(new PageResourceAnalyzer.WidthSuggestion(1920, "max_width"));
        assertThat(PageResourceAnalyzer.suggestWidth(3000, 1400)).isEqualTo(new PageResourceAnalyzer.WidthSuggestion(1920, "max_width"));
        assertThat(PageResourceAnalyzer.suggestWidth(2001, null)).isEqualTo(new PageResourceAnalyzer.WidthSuggestion(1920, "max_width"));
        assertThat(PageResourceAnalyzer.suggestWidth(2000, null)).as("tam sınır: geniş sayılmaz").isNull();
        assertThat(PageResourceAnalyzer.suggestWidth(1200, null)).isNull();
        assertThat(PageResourceAnalyzer.suggestWidth(1200, 800)).isNull();
        assertThat(PageResourceAnalyzer.suggestWidth(null, 300)).as("doğal genişlik bilinmiyor").isNull();
        assertThat(PageResourceAnalyzer.suggestWidth(0, 300)).isNull();
        assertThat(PageResourceAnalyzer.suggestWidth(3000, 0)).as("bildirilen 0 yok sayılır").isEqualTo(
                new PageResourceAnalyzer.WidthSuggestion(1920, "max_width"));
        // Öneri hiçbir zaman doğal genişlikten büyük değil
        for (int natural : new int[]{1, 500, 1999, 2001, 2200, 5000, 100_000}) {
            for (Integer declared : new Integer[]{null, 1, 100, 909, 1000, 2500, 50_000}) {
                PageResourceAnalyzer.WidthSuggestion s = PageResourceAnalyzer.suggestWidth(natural, declared);
                if (s != null) assertThat(s.width()).as(natural + " / " + declared).isLessThanOrEqualTo(natural).isPositive();
            }
        }
        assertThat(PageResourceAnalyzer.OVERSIZED_WIDTH).isEqualTo(2000);
        assertThat(PageResourceAnalyzer.SUGGESTED_MAX_WIDTH).isEqualTo(1920);
    }

    static ScanItem image(String url, long bytes, Integer w, Integer h) {
        ScanItem it = new ScanItem();
        it.url = url;
        it.category = "IMAGE";
        it.contentType = "image/jpeg";
        it.status = 200;
        it.bytes = bytes;
        it.durationMs = 120;
        it.ttfbMs = 40;
        it.pageLoad = true;
        it.verdict = ScanItem.OK;
        it.width = w;
        it.height = h;
        return it;
    }

    @Test
    @DisplayName("nesne satırı: declared_* ve suggested_* yalnız görselde ve yalnız varsa; diğer satırlar bayt bayt eskisi gibi")
    void itemMapFields() {
        ScanItem wide = image("https://a.com/hero.jpg", 900 * KB, 4000, 2000);
        wide.declaredWidth = 600;
        wide.declaredHeight = 300;
        Map<String, Object> m = wide.toMap();
        assertThat(m).containsEntry("declared_width", 600).containsEntry("declared_height", 300)
                .containsEntry("suggested_width", 1200).containsEntry("suggested_reason", "declared");
        assertThat(wide.toDeepMap()).containsEntry("suggested_width", 1200).containsEntry("declared_width", 600);

        ScanItem big = image("https://a.com/big.jpg", 900 * KB, 3000, 2000);
        assertThat(big.toMap()).containsEntry("suggested_width", 1920).containsEntry("suggested_reason", "max_width")
                .doesNotContainKeys("declared_width", "declared_height");

        ScanItem small = image("https://a.com/logo.png", 20 * KB, 300, 100);
        assertThat(small.toMap()).doesNotContainKeys("declared_width", "declared_height", "suggested_width", "suggested_reason");

        // Görsel olmayan nesne: boyutu ve bildirilen değeri olsa bile alan yazılmaz
        ScanItem icon = image("https://a.com/favicon.png", 20 * KB, 4000, 4000);
        icon.category = "FAVICON";
        icon.declaredWidth = 16;
        assertThat(icon.toMap()).doesNotContainKeys("declared_width", "suggested_width", "suggested_reason");
    }

    @Test
    @DisplayName("measurementCopy bildirilen boyutu taşır (çok sayfalı tarama o sayfanın adayından yeniden yazar)")
    void measurementCopyCarriesDeclared() {
        ScanItem it = image("https://a.com/x.jpg", 10 * KB, 800, 600);
        it.declaredWidth = 400;
        it.declaredHeight = 300;
        ScanItem c = it.measurementCopy();
        assertThat(c.declaredWidth).isEqualTo(400);
        assertThat(c.declaredHeight).isEqualTo(300);
    }

    @Test
    @DisplayName("fromStored: saklanan satır (JSON gidiş-dönüşü) aynı satırı üretir; bozuk türler ve null harita fırlatmaz")
    void fromStoredRoundTrip() throws Exception {
        ScanItem it = image("https://cdn.b.com/a/hero.jpg?v=3", 2 * MB, 4000, 3000);
        it.truncated = false;
        it.thirdParty = true;
        it.compressed = false;
        it.cacheTtlS = 86_400L;
        it.redirects = 1;
        it.fromCss = true;
        it.lazy = true;
        it.host = "cdn.b.com";
        it.foundIn = "https://a.com/s.css";
        it.finalUrl = "https://cdn.b.com/a/hero-final.jpg";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content_type", "image/jpeg");
        headers.put("server", null);
        it.headers = headers;
        it.via = "direct";
        it.optimizedBytes = 600 * KB;
        it.findings.add("LARGE_IMAGES");
        it.httpVersion = "2";
        it.contentHash = "0123456789abcdef";
        it.thirdPartyCategory = "cdn";
        it.declaredWidth = 800;
        it.declaredHeight = 600;
        it.startMs = 345L;

        ObjectMapper json = new ObjectMapper();
        Map<String, Object> stored = json.readValue(json.writeValueAsString(it.toDeepMap()), new TypeReference<>() {});
        ScanItem back = ScanItem.fromStored(stored);
        assertThat(back.toDeepMap()).isEqualTo(it.toDeepMap());
        assertThat(back.bytes).isEqualTo(2 * MB);
        assertThat(back.optimizedBytes).isEqualTo(600 * KB);
        assertThat(back.cacheTtlS).isEqualTo(86_400L);
        assertThat(back.parent).as("parent saklanmaz — çağıran found_in'den bağlar").isEqualTo(-1);

        ScanItem js = new ScanItem();
        js.url = "https://a.com/app.js";
        js.category = "JS";
        js.status = 200;
        js.pageLoad = true;
        js.minified = Boolean.FALSE;
        js.libraryName = "jquery";
        js.libraryVersion = "3.7.1";
        js.sourceMap = "https://a.com/app.js.map";
        js.renderBlocking = true;
        js.integrity = Boolean.TRUE;
        Map<String, Object> jsStored = json.readValue(json.writeValueAsString(js.toDeepMap()), new TypeReference<>() {});
        assertThat(ScanItem.fromStored(jsStored).toDeepMap()).isEqualTo(js.toDeepMap());

        Map<String, Object> junk = new LinkedHashMap<>();
        junk.put("url", 42);
        junk.put("bytes", "çok");
        junk.put("headers", List.of("x"));
        junk.put("findings", "LARGE");
        junk.put("width", "geniş");
        ScanItem j = ScanItem.fromStored(junk);
        assertThat(j.url).isNull();
        assertThat(j.bytes).isZero();
        assertThat(j.headers).isNull();
        assertThat(j.findings).isEmpty();
        assertThat(j.width).isNull();
        assertThat(ScanItem.fromStored(null).sizeSource).isEqualTo(ScanItem.SIZE_BODY);
    }

    @Test
    @DisplayName("performance = analyze'ın notu: aynı nesnelerde not, harf, toplamlar ve bileşen satırları birebir (engelleyen bileşen çağırandan)")
    void performanceEqualsAnalyze() {
        List<ScanItem> items = new ArrayList<>();
        ScanItem html = new ScanItem();
        html.url = "https://a.com/";
        html.category = "HTML";
        html.status = 200;
        html.bytes = 60 * KB;
        html.ttfbMs = 700;
        html.durationMs = 900;
        html.pageLoad = true;
        html.compressed = true;
        items.add(html);
        ScanItem css = new ScanItem();
        css.url = "https://a.com/s.css";
        css.category = "CSS";
        css.status = 200;
        css.bytes = 90 * KB;
        css.durationMs = 300;
        css.pageLoad = true;
        css.cacheTtlS = 3_600L;
        items.add(css);
        for (int i = 0; i < 9; i++) {
            ScanItem img = image("https://a.com/i" + i + ".jpg", (150 + 60L * i) * KB, 1600, 900);
            img.durationMs = 200 + 90L * i;
            img.cacheTtlS = i % 2 == 0 ? 86_400L : null;
            if (i == 4) img.parent = 1;        // stilden bulunan görsel: stil inmeden başlamaz
            if (i == 5) img.lazy = true;
            if (i == 6) { img.status = 404; img.verdict = ScanItem.BROKEN; }
            items.add(img);
        }
        ScanItem skipped = image("https://a.com/never.jpg", 0, null, null);
        skipped.verdict = ScanItem.SKIPPED;
        skipped.pageLoad = false;
        items.add(skipped);
        ScanItem doc = image("https://a.com/r.pdf", 3 * MB, null, null);
        doc.category = "DOCUMENT";
        doc.pageLoad = false;
        items.add(doc);

        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scores = (List<Map<String, Object>>) r.analysis().get("scores");
        Map<String, Object> rb = scores.stream().filter(s -> "render_blocking".equals(s.get("key"))).findFirst().orElseThrow();
        assertThat(rb.get("score")).as("HTML olgusu yok → bilinmiyor").isNull();

        PageResourceAnalyzer.Performance p = PageResourceAnalyzer.performance(items, null, null);
        assertThat(p.grade()).isEqualTo(r.grade());
        assertThat(p.letter()).isEqualTo(r.gradeLetter());
        assertThat(p.loadMs()).isEqualTo(r.loadMs());
        assertThat(p.pageBytes()).isEqualTo(r.pageBytes());
        assertThat(p.imageBytes()).isEqualTo(r.imageBytes());
        assertThat(p.scores()).isEqualTo(scores);
        @SuppressWarnings("unchecked")
        Map<String, Object> img = (Map<String, Object>) r.analysis().get("images");
        assertThat(p.largeImages()).isEqualTo(img.get("large_count"));

        // Saklanan engelleyen puanı verilince not 100 ağırlık üzerinden: bileşen satırı o puanı taşır
        PageResourceAnalyzer.Performance withRb = PageResourceAnalyzer.performance(items, 60, 400 * KB);
        Map<String, Object> rbRow = withRb.scores().stream().filter(s -> "render_blocking".equals(s.get("key"))).findFirst().orElseThrow();
        assertThat(rbRow.get("score")).isEqualTo(60);
        assertThat(rbRow.get("value")).isEqualTo(400 * KB);
        assertThat(rbRow.get("lost")).isEqualTo(4.0);
        double lost = withRb.scores().stream().mapToDouble(s -> ((Number) s.get("lost")).doubleValue()).sum();
        assertThat(lost).as("kayıpların toplamı 100 − not'u açıklar").isCloseTo(100 - withRb.grade(), org.assertj.core.data.Offset.offset(1.0));
    }
}
