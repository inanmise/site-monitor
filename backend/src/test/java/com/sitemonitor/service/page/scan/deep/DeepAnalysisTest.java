package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.scan.PageResourceAnalyzer;
import com.sitemonitor.service.page.scan.PageResourceDiscovery;
import com.sitemonitor.service.page.scan.ScanItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Derin bölümlerin (A–D) analizciyle birlikte hesabı: sayfa + HTML olguları + bağlam → bölüm alanları, öneriler, bulgular,
 * kategori puanları ve not kodları.
 */
class DeepAnalysisTest {

    static final long KB = 1024;
    static final String PAGE = "https://ornek.com.tr/";

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
        i.host = DeepUrls.host(url);
        i.verdict = ScanItem.OK;
        return i;
    }

    static HtmlFacts facts(String html) {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        return HtmlFacts.extract(PageResourceDiscovery.parse(b, PAGE), b.length);
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    static Map<String, Object> rec(PageResourceAnalyzer.Result r, String code) {
        List<Map<String, Object>> recs = get(r.analysis(), "recommendations");
        return recs.stream().filter(m -> code.equals(m.get("code"))).findFirst().orElse(null);
    }

    static final String HTML = """
            <html lang="tr"><head><title>Ornek sayfa başlığı</title>
              <link rel="stylesheet" href="/css/site.css">
              <script src="/js/app.js"></script>
              <script src="https://cdn.other.net/jquery-1.12.4.min.js"></script>
              <link rel="preload" href="/img/unused.png" as="image">
              <link rel="preconnect" href="https://nobody.example.org">
            </head><body>
              <img src="/img/a.png"><img src="/img/b.png"><img src="/img/c.png"><img src="/img/d.png">
              <img src="/img/e.png" width="10" height="10"><img src="/img/wide.jpg" width="1600" height="900">
              <img src="http://ornek.com.tr/img/plain.png" width="1" height="1">
            </body></html>
            """;

    List<ScanItem> items() {
        List<ScanItem> items = new ArrayList<>();
        ScanItem html = item(PAGE, "HTML", 40 * KB, 400);
        html.ttfbMs = 120;
        html.contentType = "text/html";
        html.cacheTtlS = null;
        items.add(html);
        ScanItem css = item("https://ornek.com.tr/css/site.css", "CSS", 60 * KB, 200);
        css.minified = false;
        css.contentHash = "a".repeat(64);
        items.add(css);
        ScanItem imported = item("https://ornek.com.tr/css/import.css", "CSS", 10 * KB, 100);
        imported.fromCss = true;
        imported.parent = 1;
        items.add(imported);
        ScanItem js = item("https://ornek.com.tr/js/app.js", "JS", 200 * KB, 300);
        js.minified = false;
        js.sourceMap = "https://ornek.com.tr/js/app.js.map";
        js.httpVersion = "1.1";
        items.add(js);
        ScanItem jq = item("https://cdn.other.net/jquery-1.12.4.min.js", "JS", 95 * KB, 250);
        jq.thirdParty = true;
        jq.libraryName = "jquery";
        jq.libraryVersion = "1.12.4";
        jq.contentHash = "b".repeat(64);
        jq.httpVersion = "2";
        items.add(jq);
        ScanItem jq2 = item("https://ornek.com.tr/js/jquery.min.js?v=1", "JS", 95 * KB, 250);
        jq2.libraryName = "jquery";
        jq2.libraryVersion = "3.6.0";
        jq2.contentHash = "b".repeat(64);
        jq2.cacheTtlS = 600L;
        items.add(jq2);
        for (String n : List.of("a", "b", "c", "d", "e")) {
            ScanItem im = item("https://ornek.com.tr/img/" + n + ".png", "IMAGE", 30 * KB, 50);
            im.contentType = "image/png";
            items.add(im);
        }
        ScanItem wide = item("https://ornek.com.tr/img/wide.jpg", "IMAGE", 300 * KB, 300);
        wide.width = 1600;
        wide.contentType = "image/jpeg";
        items.add(wide);
        ScanItem plain = item("http://ornek.com.tr/img/plain.png", "IMAGE", 2 * KB, 50);
        items.add(plain);
        ScanItem gif = item("https://ornek.com.tr/img/anim.gif", "IMAGE", 600 * KB, 400);
        gif.gifFrames = 12;
        gif.contentType = "image/gif";
        gif.fromCss = true;
        gif.parent = 1;
        items.add(gif);
        ScanItem preload = item("https://ornek.com.tr/img/unused.png", "IMAGE", 5 * KB, 40);
        items.add(preload);
        ScanItem font = item("https://ornek.com.tr/f/inter.ttf", "FONT", 80 * KB, 80);
        font.contentType = "font/ttf";
        font.fromCss = true;
        font.parent = 1;
        items.add(font);
        for (int k = 0; k < items.size(); k++) items.get(k).seq = k;
        return items;
    }

    DeepContext ctx(HtmlFacts f) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("strict-transport-security", List.of("max-age=31536000"));
        headers.put("x-content-type-options", List.of("nosniff"));
        CookieFacts.Cookie sid = CookieFacts.parse("JSESSIONID=; Path=/", "ornek.com.tr", 0);
        CookieFacts.Cookie ga = CookieFacts.parse("_ga=; Secure; SameSite=Lax", "cdn.other.net", 0);
        return new DeepContext(f, headers, PAGE,
                List.of(new DeepContext.CookieSeen(sid, false), new DeepContext.CookieSeen(ga, true)),
                List.of(new FontFaces.Face("Inter", "400", null, "https://ornek.com.tr/f/inter.ttf", "https://ornek.com.tr/css/site.css")),
                Set.of(), Map.of("ornek.com.tr", new DeepContext.HostTiming(5L, 10L, 20L, true, null),
                "cdn.other.net", DeepContext.HostTiming.notMeasured("proxy")), 0,
                Map.of("https://ornek.com.tr/js/app.js.map", true), 0, 2);
    }

    @Test
    @DisplayName("algoritma 3: kategori puanları, bileşenler, bölümler ve not kodları kökte")
    void rootShape() {
        HtmlFacts f = facts(HTML);
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items(), PageResourceAnalyzer.Limits.NONE, ctx(f));
        Map<String, Object> a = r.analysis();
        assertThat(a.get("algorithm_version")).isEqualTo(3);
        assertThat(a).containsKeys("category_scores", "loading", "waste", "media", "security", "html_audit");
        Map<String, Object> cs = get(a, "category_scores");
        assertThat(((Map<String, Object>) cs.get("performance")).get("score")).isEqualTo(r.grade());
        assertThat(((Map<String, Object>) cs.get("security")).get("score")).isEqualTo(r.securityScore()).isNotNull();
        assertThat(((Map<String, Object>) cs.get("seo")).get("score")).isEqualTo(r.seoScore()).isNotNull();
        List<Map<String, Object>> scores = get(a, "scores");
        assertThat(scores).extracting(m -> m.get("key")).containsExactly("load_time", "page_weight", "images", "requests",
                "server_response", "render_blocking", "errors", "compression", "caching");
        assertThat(scores).extracting(m -> (Integer) m.get("weight")).containsExactly(20, 15, 15, 10, 10, 10, 8, 7, 5);
        List<Map<String, Object>> notes = get(a, "notes");
        assertThat(notes).extracting(m -> m.get("code")).contains("JS_NOT_EXECUTED", "JS_COOKIES_NOT_SEEN",
                "CONTRAST_NOT_CHECKED", "ASSET_SAMPLES_UNAVAILABLE");
        assertThat(r.jsBytes()).isEqualTo(390 * KB);
        assertThat(r.cssBytes()).isEqualTo(70 * KB);
        assertThat(r.fontBytes()).isEqualTo(80 * KB);
    }

    @Test
    @DisplayName("A: <head>'deki defer'siz betik + medyasız stil + @import engeller; ön bağlantı / önyükleme kullanımı; hostlar; profiller")
    void loading() {
        HtmlFacts f = facts(HTML);
        List<ScanItem> items = items();
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE, ctx(f));
        Map<String, Object> loading = get(r.analysis(), "loading");
        Map<String, Object> rb = get(loading, "render_blocking");
        List<Map<String, Object>> rows = get(rb, "items");
        assertThat(rows).extracting(m -> m.get("kind")).containsExactly("stylesheet", "css_import", "script", "script");
        assertThat(rows).extracting(m -> m.get("reason")).containsExactly("no_media", "import", "no_async_defer", "no_async_defer");
        assertThat(rb.get("count")).isEqualTo(4);
        assertThat((Long) rb.get("est_first_paint_ms")).isGreaterThan(400L);
        assertThat(items.get(1).renderBlocking).isTrue();
        assertThat(items.get(1).findings).contains("RENDER_BLOCKING");
        assertThat(rec(r, "RENDER_BLOCKING_RESOURCES")).containsEntry("finding", "RENDER_BLOCKING").containsEntry("item_count", 4);

        Map<String, Object> hints = get(loading, "hints");
        List<Map<String, Object>> preload = get(hints, "preload");
        assertThat(preload).singleElement().satisfies(p -> assertThat(p).containsEntry("used", false).containsEntry("as", "image"));
        assertThat(rec(r, "UNUSED_PRELOAD")).isNotNull();
        assertThat(rec(r, "UNUSED_PRECONNECT")).isNotNull().extracting(m -> ((Map<?, ?>) m.get("params")).get("host_list"))
                .isEqualTo(List.of("nobody.example.org"));
        List<Map<String, Object>> missing = get(hints, "missing_preconnect");
        assertThat(missing).singleElement().satisfies(m -> assertThat(m).containsEntry("host", "cdn.other.net")
                .containsEntry("blocking", true));
        assertThat(rec(r, "MISSING_PRECONNECT")).containsEntry("severity", "medium").containsEntry("finding", null);

        List<Map<String, Object>> hosts = get(loading, "hosts");
        Map<String, Object> main = hosts.stream().filter(h -> "ornek.com.tr".equals(h.get("host"))).findFirst().orElseThrow();
        assertThat(main).containsEntry("dns_ms", 5L).containsEntry("tls_ms", 20L).containsEntry("measured", true);
        Map<String, Object> cdn = hosts.stream().filter(h -> "cdn.other.net".equals(h.get("host"))).findFirst().orElseThrow();
        assertThat(cdn).containsEntry("measured", false).containsEntry("reason", "proxy").containsEntry("third_party", true);
        assertThat(loading.get("http1_first_party_hosts")).as("sürümü bilinen tek nesne HTTP/1.1").isEqualTo(1);
        assertThat(rec(r, "HTTP1_ONLY")).isNotNull().containsEntry("severity", "low");
        List<Map<String, Object>> profiles = get(loading, "profiles");
        assertThat(profiles).extracting(m -> m.get("key")).containsExactly("fiber", "4g", "3g", "slow3g");
        assertThat((Long) profiles.get(3).get("load_ms")).isGreaterThan((Long) profiles.get(0).get("load_ms"));
    }

    @Test
    @DisplayName("B: aynı içerik, kütüphane sürümleri, küçültülmemiş, açık kaynak haritası, önbellek kovaları ve tekrar ziyaret")
    void waste() {
        HtmlFacts f = facts(HTML);
        List<ScanItem> items = items();
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE, ctx(f));
        Map<String, Object> waste = get(r.analysis(), "waste");
        List<Map<String, Object>> dups = get(waste, "duplicates");
        assertThat(dups).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("kind", "same_content").containsEntry("bytes_wasted", 95 * KB);
            assertThat((List<?>) d.get("urls")).hasSize(2);
        });
        assertThat(rec(r, "DUPLICATE_RESOURCES")).containsEntry("finding", "DUPLICATE").containsEntry("saving_bytes", 95 * KB);
        List<Map<String, Object>> libs = get(waste, "libraries");
        assertThat(libs).singleElement().satisfies(l -> assertThat(l.get("versions")).isEqualTo(List.of("1.12.4", "3.6.0")));
        List<Map<String, Object>> unmin = get(waste, "unminified");
        assertThat(unmin).extracting(m -> m.get("url")).containsExactly("https://ornek.com.tr/js/app.js",
                "https://ornek.com.tr/css/site.css");
        assertThat(rec(r, "UNMINIFIED_ASSETS")).containsEntry("finding", "UNMINIFIED");
        List<Map<String, Object>> maps = get(waste, "source_maps");
        assertThat(maps).singleElement().satisfies(m -> assertThat(m).containsEntry("exposed", true));
        assertThat(items.get(3).findings).contains("SOURCE_MAP_EXPOSED", "UNMINIFIED");
        Map<String, Object> caching = get(waste, "caching");
        Map<String, Integer> buckets = get(caching, "buckets");
        assertThat(buckets.get("lt_1h")).isEqualTo(1);
        assertThat(caching.get("no_validator")).isEqualTo(1);
        assertThat((List<?>) caching.get("versioned_short")).hasSize(1);
        assertThat(rec(r, "SHORT_CACHE_VERSIONED")).isNotNull();
        assertThat((Long) caching.get("repeat_visit_bytes")).isEqualTo(40 * KB + 95 * KB);   // HTML + kısa önbellekli
        Map<String, Object> html = get(waste, "html");
        assertThat(html.get("dom_nodes")).isEqualTo(f.domNodes);
    }

    @Test
    @DisplayName("C: boyutsuz görsel, srcset'siz geniş görsel, ekran dışı geç yüklenmeyen, hareketli GIF, yazı tipi biçimi / display")
    void media() {
        HtmlFacts f = facts(HTML);
        List<ScanItem> items = items();
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE, ctx(f));
        Map<String, Object> media = get(r.analysis(), "media");
        Map<String, Object> images = get(media, "images");
        assertThat((List<?>) images.get("missing_dimensions")).hasSize(4);
        assertThat(rec(r, "IMAGES_WITHOUT_DIMENSIONS")).containsEntry("finding", "MISSING_DIMENSIONS").containsEntry("item_count", 4);
        List<Map<String, Object>> srcset = get(images, "missing_srcset");
        assertThat(srcset).singleElement().satisfies(m -> assertThat(m).containsEntry("width", 1600));
        List<Map<String, Object>> notLazy = get(images, "not_lazy");
        assertThat(notLazy).extracting(m -> m.get("url")).containsExactly("https://ornek.com.tr/img/e.png",
                "https://ornek.com.tr/img/wide.jpg");
        assertThat(rec(r, "OFFSCREEN_NOT_LAZY")).containsEntry("finding", "NOT_LAZY");
        List<Map<String, Object>> gifs = get(images, "animated_gif");
        assertThat(gifs).singleElement().satisfies(m -> assertThat(m).containsEntry("frames", 12));
        assertThat(rec(r, "ANIMATED_GIF")).containsEntry("severity", "medium");
        Map<String, Object> fonts = get(media, "fonts");
        assertThat(fonts.get("count")).isEqualTo(1);
        assertThat((List<?>) fonts.get("non_woff2")).hasSize(1);
        assertThat((List<?>) fonts.get("missing_display")).hasSize(1);
        assertThat(rec(r, "FONT_DISPLAY_MISSING")).containsEntry("finding", "FONT_DISPLAY_MISSING");
        assertThat(rec(r, "NON_WOFF2_FONTS")).isNotNull();
        assertThat(items.get(items.size() - 1).findings).contains("FONT_DISPLAY_MISSING");
    }

    @Test
    @DisplayName("D: başlık satırları + eksikler, SRI'siz 3. taraf betik, karışık içerik, güvensiz çerez, açıklı kütüphane, 3. taraf kategorisi")
    void security() {
        HtmlFacts f = facts(HTML);
        List<ScanItem> items = items();
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE, ctx(f));
        Map<String, Object> sec = get(r.analysis(), "security");
        List<Map<String, Object>> headers = get(sec, "headers");
        assertThat(headers).extracting(m -> m.get("key")).containsExactlyElementsOf(SecurityHeaders.KEYS);
        Map<String, Object> missing = rec(r, "SECURITY_HEADERS_MISSING");
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) ((Map<?, ?>) missing.get("params")).get("keys");
        assertThat(keys).contains("csp", "frame_protection").doesNotContain("hsts", "x_content_type_options");
        assertThat(missing.get("severity")).isEqualTo("medium");
        assertThat((List<?>) sec.get("sri_missing")).hasSize(1);
        assertThat(items.get(4).integrity).isFalse();
        assertThat(items.get(3).integrity).isFalse();
        assertThat(items.get(5).integrity).as("HTML öğesinden gelmeyen betik").isNull();
        assertThat(rec(r, "MISSING_SRI")).containsEntry("finding", "MISSING_SRI").containsEntry("severity", "medium");
        assertThat((List<?>) sec.get("mixed_content")).hasSize(1);
        assertThat(rec(r, "MIXED_CONTENT")).containsEntry("severity", "medium");
        List<Map<String, Object>> cookies = get(sec, "cookies");
        assertThat(cookies).hasSize(2);
        assertThat(cookies.get(0)).containsEntry("name", "JSESSIONID").containsEntry("third_party", false);
        assertThat(cookies.toString()).doesNotContain("value");
        assertThat(rec(r, "INSECURE_COOKIES")).containsEntry("severity", "high");
        List<Map<String, Object>> libs = get(sec, "libraries");
        Map<String, Object> old = libs.stream().filter(l -> "1.12.4".equals(l.get("version"))).findFirst().orElseThrow();
        assertThat((List<?>) old.get("vulns")).hasSize(4);
        assertThat(sec.get("catalog_date")).isEqualTo(JsLibraryCatalog.CATALOG_DATE);
        assertThat(rec(r, "VULNERABLE_LIBRARY")).containsEntry("finding", "VULNERABLE_LIBRARY")
                .extracting(m -> ((Map<?, ?>) m.get("params")).get("name_list")).isEqualTo(List.of("jquery 1.12.4"));
        assertThat(items.get(4).thirdPartyCategory).isEqualTo("other");
        assertThat((Integer) sec.get("score")).isBetween(0, 70);
        assertThat(sec.get("letter")).isNotNull();
    }

    @Test
    @DisplayName("boş bağlam: HTML / başlık bilgisi yok → güvenlik / SEO / erişilebilirlik puanı null, bölümler 'bilinmiyor' alanlarıyla")
    void emptyContext() {
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items(), PageResourceAnalyzer.Limits.NONE);
        assertThat(r.securityScore()).isNull();
        assertThat(r.seoScore()).isNull();
        assertThat(r.a11yScore()).isNull();
        Map<String, Object> cs = get(r.analysis(), "category_scores");
        assertThat(((Map<String, Object>) cs.get("security"))).containsEntry("score", null).containsEntry("letter", null);
        assertThat(r.analysis().get("html_audit")).isNull();
        Map<String, Object> sec = get(r.analysis(), "security");
        assertThat(sec.get("headers")).isNull();
        Map<String, Object> loading = get(r.analysis(), "loading");
        assertThat(loading.get("hints")).isNull();
        assertThat(loading.get("render_blocking")).as("D-1: olgu yoksa engelleyenler bilinmiyor").isNull();
        List<Map<String, Object>> scores = get(r.analysis(), "scores");
        assertThat(scores.stream().filter(m -> "render_blocking".equals(m.get("key"))).findFirst().orElseThrow())
                .containsEntry("score", null).containsEntry("lost", null);
        assertThat(rec(r, "RENDER_BLOCKING_RESOURCES")).isNull();
        Map<String, Object> media = get(r.analysis(), "media");
        assertThat(((Map<String, Object>) media.get("images")).get("html_checked")).isEqualTo(false);
        assertThat(rec(r, "SECURITY_HEADERS_MISSING")).isNull();
    }

    @Test
    @DisplayName("D-10: üçüncü taraf kategorisinin host listesi ≤ 50 satır + hosts_total; kategori listesi de _total taşır")
    void thirdPartyCategoryHostsAreCapped() {
        List<ScanItem> items = new ArrayList<>();
        items.add(item(PAGE, "HTML", 10 * KB, 100));
        for (int k = 0; k < 60; k++) {
            ScanItem t = item("https://cdn" + k + ".ucuncu-taraf.example/x.js", "JS", KB, 20);
            t.thirdParty = true;
            items.add(t);
        }
        ScanItem ga = item("https://www.google-analytics.com/analytics.js", "JS", 20 * KB, 40);
        ga.thirdParty = true;
        items.add(ga);
        for (int k = 0; k < items.size(); k++) items.get(k).seq = k;
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE, DeepContext.EMPTY);
        Map<String, Object> sec = get(r.analysis(), "security");
        List<Map<String, Object>> cats = get(sec, "third_party_categories");
        assertThat(sec.get("third_party_categories_total")).isEqualTo(cats.size());
        Map<String, Object> other = cats.stream().filter(c -> "other".equals(c.get("category"))).findFirst().orElseThrow();
        assertThat((List<?>) other.get("hosts")).hasSize(DeepSection.MAX_ROWS);
        assertThat(other).containsEntry("hosts_total", 60).containsEntry("requests", 60L).containsEntry("bytes", 60 * KB);
        Map<String, Object> analytics = cats.stream().filter(c -> "analytics".equals(c.get("category"))).findFirst().orElseThrow();
        assertThat(analytics).containsEntry("hosts", List.of("www.google-analytics.com")).containsEntry("hosts_total", 1);
    }

    @Test
    @DisplayName("D-8: biçimi bilinmeyen yazı tipi (octet-stream, uzantısız) 'woff2 değil' sayılmaz; yalnız sayılır")
    void unknownFontFormatIsNotNonWoff2() {
        List<ScanItem> items = new ArrayList<>();
        items.add(item(PAGE, "HTML", 10 * KB, 100));
        ScanItem opaque = item("https://ornek.com.tr/fonts/get?id=inter", "FONT", 40 * KB, 50);
        opaque.contentType = "application/octet-stream";
        ScanItem byExt = item("https://ornek.com.tr/fonts/inter.woff2", "FONT", 30 * KB, 50);
        byExt.contentType = "application/octet-stream";
        ScanItem ttf = item("https://ornek.com.tr/fonts/eski.ttf", "FONT", 90 * KB, 50);
        ttf.contentType = "font/ttf";
        items.addAll(List.of(opaque, byExt, ttf));
        for (int k = 0; k < items.size(); k++) items.get(k).seq = k;
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE, DeepContext.EMPTY);
        Map<String, Object> fonts = get(get(r.analysis(), "media"), "fonts");
        List<Map<String, Object>> nonWoff2 = get(fonts, "non_woff2");
        assertThat(nonWoff2).singleElement().satisfies(m -> assertThat(m).containsEntry("format", "ttf"));
        assertThat(fonts.get("unknown_format")).isEqualTo(1);
        assertThat(rec(r, "NON_WOFF2_FONTS")).containsEntry("saving_bytes", Math.round(90 * KB * 0.30))
                .extracting(m -> ((Map<?, ?>) m.get("params")).get("count")).isEqualTo(1);
    }

    @Test
    @DisplayName("O-2: aynı yol, farklı sorgu → içerik özeti farklı ya da bilinmiyorsa tekrar DEĞİL; özet aynıysa same_path")
    void duplicatesNeedEqualContent() {
        List<ScanItem> items = new ArrayList<>();
        items.add(item(PAGE, "HTML", 10 * KB, 100));
        ScanItem g1 = item("https://www.googletagmanager.com/gtag/js?id=G-ABC123", "JS", 90 * KB, 100);
        g1.contentHash = "1".repeat(64);
        ScanItem g2 = item("https://www.googletagmanager.com/gtag/js?id=AW-987654", "JS", 80 * KB, 100);
        g2.contentHash = "2".repeat(64);
        ScanItem f1 = item("https://fonts.googleapis.com/css2?family=Roboto:wght@400", "CSS", 2 * KB, 50);   // özet yok
        ScanItem f2 = item("https://fonts.googleapis.com/css2?family=Inter:wght@400", "CSS", 2 * KB, 50);
        ScanItem n1 = item("https://ornek.com.tr/_next/image?url=%2Fa.png&w=640", "IMAGE", 40 * KB, 50);
        n1.contentHash = "3".repeat(64);
        ScanItem n2 = item("https://ornek.com.tr/_next/image?url=%2Fb.png&w=640", "IMAGE", 40 * KB, 50);
        n2.contentHash = "4".repeat(64);
        ScanItem h1 = item("https://ornek.com.tr/ImageHandler.ashx?id=1", "IMAGE", 20 * KB, 50);
        ScanItem h2 = item("https://ornek.com.tr/ImageHandler.ashx?id=2", "IMAGE", 20 * KB, 50);   // bayt eşit, özet yok
        ScanItem v1 = item("https://ornek.com.tr/js/app.js?v=1", "JS", 30 * KB, 50);
        v1.contentHash = "5".repeat(64);
        ScanItem v2 = item("https://ornek.com.tr/js/app.js?v=2", "JS", 30 * KB, 50);
        v2.contentHash = "5".repeat(64);
        items.addAll(List.of(g1, g2, f1, f2, n1, n2, h1, h2, v1, v2));
        for (int k = 0; k < items.size(); k++) items.get(k).seq = k;

        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE, DeepContext.EMPTY);
        Map<String, Object> waste = get(r.analysis(), "waste");
        List<Map<String, Object>> dups = get(waste, "duplicates");
        assertThat(dups).as("yalnız aynı özetli önbellek kırıcı tekrar").singleElement().satisfies(d -> {
            assertThat(d).containsEntry("kind", "same_path").containsEntry("bytes_wasted", 30 * KB);
            assertThat((List<?>) d.get("urls")).hasSize(2);
        });
        assertThat(rec(r, "DUPLICATE_RESOURCES")).containsEntry("saving_bytes", 30 * KB);
        for (ScanItem other : List.of(g1, g2, f1, f2, n1, n2, h1, h2)) assertThat(other.findings).doesNotContain("DUPLICATE");
        assertThat(v1.findings).contains("DUPLICATE");
        assertThat(v2.findings).contains("DUPLICATE");
    }

    @Test
    @DisplayName("O-2: özetleri farklı aynı yollu nesneler → öneri ve israf baytı yok")
    void noDuplicateWithoutEqualHash() {
        List<ScanItem> items = new ArrayList<>();
        items.add(item(PAGE, "HTML", 10 * KB, 100));
        ScanItem a = item("https://www.googletagmanager.com/gtag/js?id=G-ABC123", "JS", 90 * KB, 100);
        a.contentHash = "1".repeat(64);
        ScanItem b = item("https://www.googletagmanager.com/gtag/js?id=AW-987654", "JS", 90 * KB, 100);
        b.contentHash = "2".repeat(64);
        items.addAll(List.of(a, b));
        for (int k = 0; k < items.size(); k++) items.get(k).seq = k;
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE, DeepContext.EMPTY);
        Map<String, Object> waste = get(r.analysis(), "waste");
        assertThat((List<?>) waste.get("duplicates")).isEmpty();
        assertThat(rec(r, "DUPLICATE_RESOURCES")).isNull();
    }

    @Test
    @DisplayName("maske: gizli şüpheli sorgu parametresi nesne satırında, öneri adreslerinde ve bölümde aynı maskeyle")
    void maskedEverywhere() {
        List<ScanItem> items = new ArrayList<>();
        ScanItem html = item(PAGE, "HTML", 10 * KB, 100);
        items.add(html);
        ScanItem js = item("https://ornek.com.tr/a.js?token=gizli", "JS", 10 * KB, 50);
        js.minified = false;
        items.add(js);
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE, DeepContext.EMPTY);
        assertThat(js.toDeepMap().get("url")).isEqualTo("https://ornek.com.tr/a.js?token=*****");
        assertThat(rec(r, "UNMINIFIED_ASSETS").get("item_urls")).isEqualTo(List.of("https://ornek.com.tr/a.js?token=*****"));
        assertThat(r.analysis().toString()).doesNotContain("gizli");
    }
}
