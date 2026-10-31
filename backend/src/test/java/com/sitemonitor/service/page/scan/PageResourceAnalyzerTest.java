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
        assertThat(r.failedCount()).as("yalnız sayfa nesneleri (kırık bağlantı BROKEN_LINKS önerisinde)").isEqualTo(1);
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
    @DisplayName("sınırlı (kısmi) ölçümde sorun bulunmasa da ALL_GOOD yok — ölçülmeyen nesneler bilinmiyor")
    void cappedNeverAllGood() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(120, 300, 30 * KB));
        PageResourceAnalyzer.Result clean = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        List<Map<String, Object>> recs = get(clean.analysis(), "recommendations");
        assertThat(recs).extracting(m -> m.get("code")).containsExactly("ALL_GOOD");
        List<ScanItem> again = new ArrayList<>();
        again.add(html(120, 300, 30 * KB));
        PageResourceAnalyzer.Result capped = PageResourceAnalyzer.analyze(again,
                new PageResourceAnalyzer.Limits(false, 0, true, 60, false, 0, 0));
        List<Map<String, Object>> none = get(capped.analysis(), "recommendations");
        assertThat(none).isEmpty();
        assertThat(capped.capped()).isTrue();
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

    // ── Algoritma 2 ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("algoritma 2: kökte algorithm_version, zamanlamada son adım TTFB + yönlendirme; SLOW_SERVER yalnız son adıma bakar")
    void algorithmVersionAndRedirectSplit() {
        List<ScanItem> items = new ArrayList<>();
        ScanItem h = html(300, 2600, 30 * KB);   // son adımın ilk baytı 300 ms; zincirin tamamı uzun
        h.redirects = 2;
        h.redirectMs = 2000;
        items.add(h);
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        assertThat(r.analysis().get("algorithm_version")).isEqualTo(2);
        assertThat(r.analysis().keySet().iterator().next()).isEqualTo("algorithm_version");
        Map<String, Object> timing = get(r.analysis(), "timing");
        assertThat(timing).containsEntry("ttfb_ms", 300L).containsEntry("redirect_count", 2).containsEntry("redirect_ms", 2000L);
        List<Map<String, Object>> recs = get(r.analysis(), "recommendations");
        assertThat(recs).extracting(m -> m.get("code")).doesNotContain("SLOW_SERVER");
        assertThat(r.htmlTtfbMs()).isEqualTo(300);
    }

    @Test
    @DisplayName("not bileşenleri: full_at / zero_at (unit ile aynı birim, eğrilerden) ve lost = ağırlık × (100 − puan) / 100, 1 ondalık")
    void scoreTargetsAndLost() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(120, 2500, 30 * KB));   // yükleme 2500 ms → 83 puan
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        List<Map<String, Object>> scores = get(r.analysis(), "scores");
        Map<String, Map<String, Object>> by = new java.util.LinkedHashMap<>();
        for (Map<String, Object> s : scores) by.put((String) s.get("key"), s);
        assertThat(by.get("load_time")).containsEntry("full_at", 1000L).containsEntry("zero_at", 12_000L)
                .containsEntry("score", 83).containsEntry("lost", 4.3);   // 25 × 17 / 100 = 4,25 → 4,3
        assertThat(by.get("page_weight")).containsEntry("full_at", 512 * KB).containsEntry("zero_at", 15 * MB);
        assertThat(by.get("images")).containsEntry("full_at", 512 * KB).containsEntry("zero_at", 8 * MB);
        assertThat(by.get("requests")).containsEntry("full_at", 25).containsEntry("zero_at", 300);
        assertThat(by.get("server_response")).containsEntry("full_at", 200L).containsEntry("zero_at", 3000L);
        assertThat(by.get("errors")).containsEntry("full_at", 0).containsEntry("zero_at", 10);
        assertThat(by.get("compression")).containsEntry("full_at", 100).containsEntry("zero_at", 0).containsEntry("lost", 0.0);
        assertThat(by.get("caching")).containsEntry("full_at", 100).containsEntry("zero_at", 0);
        double lost = scores.stream().mapToDouble(s -> ((Number) s.get("lost")).doubleValue()).sum();
        assertThat(Math.round(100 - lost)).as("kayıpların toplamı notu açıklar").isEqualTo(r.grade());
    }

    @Test
    @DisplayName("bulgular: öneriyi üreten kuralın nesnelerine yazılır; item_count TÜM sayı (item_urls ≤ 10); sayfa düzeyi kodlarda null")
    void findingsAndItemCount() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(2000, 2500, 120 * KB));
        items.get(0).compressed = false;
        for (int k = 0; k < 12; k++) {
            ScanItem js = item("https://a.com/n" + k + ".js", "JS", 60 * KB, 200);
            js.cacheTtlS = null;
            items.add(js);
        }
        ScanItem big = item("https://a.com/hero.jpg", "IMAGE", 2 * MB, 3000);
        big.contentType = "image/jpeg";
        big.width = 4000;
        big.height = 3000;
        items.add(big);
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        List<Map<String, Object>> recs = get(r.analysis(), "recommendations");
        Map<String, Map<String, Object>> by = new java.util.LinkedHashMap<>();
        for (Map<String, Object> m : recs) by.put((String) m.get("code"), m);
        assertThat(by.get("NO_CACHE").get("item_count")).isEqualTo(12);
        assertThat((List<?>) by.get("NO_CACHE").get("item_urls")).hasSize(10);
        assertThat(by.get("SLOW_SERVER").get("item_count")).as("sayfa düzeyi").isNull();
        assertThat(big.findings).contains("LARGE_IMAGES", "OVERSIZED_IMAGE_DIMENSIONS", "SLOW_OBJECTS");
        assertThat(items.get(0).findings).as("HTML: sıkıştırılmamış metin; SLOW_SERVER nesneye yazılmaz")
                .contains("UNCOMPRESSED_TEXT").doesNotContain("SLOW_SERVER");
        // her bulgu kodunun öneri satırındaki item_count = o kodu taşıyan nesne sayısı (tek kaynak)
        for (Map<String, Object> m : recs) {
            String code = (String) m.get("code");
            if (!PageResourceAnalyzer.FINDING_CODES.contains(code)) continue;
            long carrying = items.stream().filter(i -> i.findings.contains(code)).count();
            assertThat(((Number) m.get("item_count")).longValue()).as(code).isEqualTo(carrying);
        }
        assertThat(items).allMatch(i -> PageResourceAnalyzer.FINDING_CODES.containsAll(i.findings));
        assertThat(big.toMap().get("findings")).isEqualTo(new ArrayList<>(big.findings));
    }

    @Test
    @DisplayName("karar: yalnız 'broken' hata puanına ve BROKEN_* önerilerine girer; doğrulanamayan / engellenen girmez")
    void verdictDrivesBroken() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(120, 300, 30 * KB));
        ScanItem waf = item("https://cdn.x.net/w.js", "JS", 0, 40);
        waf.status = 403;
        waf.verdict = ScanItem.UNVERIFIED;
        waf.thirdParty = true;
        items.add(waf);
        ScanItem ssrf = item("https://social.x/w.js", "JS", 0, 5);
        ssrf.status = 0;
        ssrf.verdict = ScanItem.BLOCKED;
        items.add(ssrf);
        ScanItem gone = item("https://a.com/gone.css", "CSS", 0, 30);
        gone.status = 404;
        gone.verdict = ScanItem.BROKEN;
        items.add(gone);
        ScanItem linkedin = item("https://www.linkedin.com/company/x", "LINK", 0, 30);
        linkedin.status = 999;
        linkedin.verdict = ScanItem.UNVERIFIED;
        items.add(linkedin);
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        assertThat(r.failedCount()).isEqualTo(1);
        List<Map<String, Object>> recs = get(r.analysis(), "recommendations");
        Map<String, Object> broken = recs.stream().filter(m -> "BROKEN_RESOURCES".equals(m.get("code"))).findFirst().orElseThrow();
        assertThat(broken.get("item_urls")).isEqualTo(List.of("https://a.com/gone.css"));
        assertThat(recs).extracting(m -> m.get("code")).doesNotContain("BROKEN_LINKS");
        List<Map<String, Object>> tp = get(r.analysis(), "third_parties");
        assertThat(tp).hasSize(1);
        assertThat(tp.get(0)).containsEntry("host", "cdn.x.net").containsEntry("count", 1L).containsEntry("failed", 1L);
    }

    @Test
    @DisplayName("indirilmeyen medya (skipped) hiçbir toplama girmez: boyut, istek, kategori, öneri; not_loaded_count sayar")
    void skippedNotCounted() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(120, 300, 30 * KB));
        ScanItem video = item("https://a.com/promo.mp4", "MEDIA", 0, 0);
        video.pageLoad = false;
        video.lazy = true;
        video.status = 0;
        video.verdict = ScanItem.SKIPPED;
        items.add(video);
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        assertThat(r.requestCount()).isEqualTo(1);
        assertThat(r.linkCount()).isZero();
        assertThat(r.failedCount()).isZero();
        assertThat(r.analysis().get("not_loaded_count")).isEqualTo(1);
        List<Map<String, Object>> cats = get(r.analysis(), "categories");
        assertThat(cats).extracting(m -> m.get("category")).containsExactly("HTML");
        List<Map<String, Object>> recs = get(r.analysis(), "recommendations");
        assertThat(recs).extracting(m -> m.get("code")).containsExactly("ALL_GOOD");
        assertThat(video.startMs).isNull();
    }

    @Test
    @DisplayName("ölçülmüş görsel kazancı: ölçülen görselde gerçek fark, ölçülemeyende eski tahmin; LARGE_IMAGES kazancı ölçümü kullanır")
    void measuredImageSaving() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(120, 300, 30 * KB));
        ScanItem measured = item("https://a.com/m.jpg", "IMAGE", 1200 * KB, 400);
        measured.contentType = "image/jpeg";
        measured.optimizedBytes = 180 * KB;
        items.add(measured);
        ScanItem estimated = item("https://a.com/e.png", "IMAGE", 400 * KB, 300);
        estimated.contentType = "image/png";
        items.add(estimated);
        ScanItem already = item("https://a.com/ok.jpg", "IMAGE", 150 * KB, 100);
        already.contentType = "image/jpeg";
        already.optimizedBytes = 150 * KB;   // yeniden kodlama küçültmedi
        items.add(already);
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        Map<String, Object> img = get(r.analysis(), "images");
        long measuredSave = 1200 * KB - 180 * KB;
        long estimatedSave = Math.round(400 * KB * 0.6);
        assertThat(img).containsEntry("saving_measured", true).containsEntry("measured_count", 2)
                .containsEntry("saving_bytes_measured", measuredSave).containsEntry("saving_bytes_estimated", estimatedSave)
                .containsEntry("saving_bytes", measuredSave + estimatedSave);
        List<Map<String, Object>> recs = get(r.analysis(), "recommendations");
        Map<String, Object> large = recs.stream().filter(m -> "LARGE_IMAGES".equals(m.get("code"))).findFirst().orElseThrow();
        assertThat(large.get("saving_bytes")).isEqualTo(measuredSave + estimatedSave);
        List<Map<String, Object>> top = get(img, "top");
        assertThat(top.get(0)).containsEntry("optimized_bytes", 180 * KB);
        assertThat(top.get(1)).doesNotContainKey("optimized_bytes");

        // hiçbiri ölçülmediyse saving_measured = false
        List<ScanItem> none = new ArrayList<>();
        none.add(html(120, 300, 30 * KB));
        ScanItem e2 = item("https://a.com/e2.png", "IMAGE", 400 * KB, 300);
        e2.contentType = "image/png";
        none.add(e2);
        Map<String, Object> img2 = get(PageResourceAnalyzer.analyze(none, PageResourceAnalyzer.Limits.NONE).analysis(), "images");
        assertThat(img2).containsEntry("saving_measured", false).containsEntry("measured_count", 0);
    }

    @Test
    @DisplayName("üçüncü taraf tablosu: host başına istek / bayt / yüklenemeyen / kategoriler, bayta göre en çok 20")
    void thirdPartyTable() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(120, 300, 30 * KB));
        for (int k = 0; k < 25; k++) {
            ScanItem t = item("https://cdn" + k + ".net/a.js", "JS", (k + 1) * KB, 50);
            t.thirdParty = true;
            items.add(t);
        }
        ScanItem font = item("https://cdn24.net/f.woff2", "FONT", 10 * KB, 50);
        font.thirdParty = true;
        items.add(font);
        List<Map<String, Object>> tp = get(PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE).analysis(),
                "third_parties");
        assertThat(tp).hasSize(20);
        assertThat(tp.get(0)).containsEntry("host", "cdn24.net").containsEntry("count", 2L).containsEntry("bytes", 35 * KB)
                .containsEntry("failed", 0L).containsEntry("categories", List.of("JS", "FONT"));
        assertThat(tp.get(19).get("host")).isEqualTo("cdn5.net");
    }
}
