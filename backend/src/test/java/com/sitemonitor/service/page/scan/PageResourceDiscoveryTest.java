package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Nesne keşfi — tarayıcının indirdikleri, bağlantılı belgeler, bağlantılar; CSS taraması doğrusal. */
class PageResourceDiscoveryTest {

    static final String BASE = "https://ornek.com.tr/sayfa/";

    static final String HTML = """
            <html><head>
              <link rel="stylesheet" href="/css/site.css">
              <link rel="stylesheet" href="/css/site.css#dup">
              <link rel="preload" as="font" href="/f/inter.woff2" crossorigin>
              <link rel="preload" as="image" href="hero.webp">
              <link rel="modulepreload" href="/js/mod.js">
              <link rel="icon" href="/favicon.ico">
              <link rel="apple-touch-icon" href="/apple.png">
              <link rel="manifest" href="/site.webmanifest">
              <link rel="canonical" href="https://ornek.com.tr/sayfa/">
              <link rel="dns-prefetch" href="https://cdn.example.net">
              <script src="https://cdn.example.net/lib.js"></script>
              <style>.a{background:url('/img/bg.png')} @font-face{src:url("../f/x.woff")}</style>
            </head><body>
              <img src="logo.png" alt="">
              <img srcset="small.jpg 1x, big.jpg 2x" alt="">
              <img src="below.jpg" loading="lazy" alt="">
              <video poster="/v/poster.jpg"><source src="/v/clip.mp4"></video>
              <video autoplay src="/v/auto.webm"></video>
              <iframe src="https://www.youtube.com/embed/x" loading="lazy"></iframe>
              <div style="background-image:url(/img/inline.svg)"></div>
              <a href="/belgeler/rapor.pdf">rapor</a>
              <a href="/belgeler/tablo.XLSX?v=2">tablo</a>
              <a href="/hakkimizda">hakkımızda</a>
              <a href="https://baska.com/">dış</a>
              <a href="#ust">üst</a>
              <a href="mailto:a@b.c">posta</a>
              <a href="tel:+90">tel</a>
              <a href="javascript:void(0)">js</a>
              <a href="">kendisi</a>
              <a href="/logo-link.png">görsel bağlantısı</a>
              <a href="logo.png">sayfa zaten yüklüyor</a>
              <a href="/gizli/admin">hariç</a>
            </body></html>
            """;

    private static Map<String, PageResourceDiscovery.Candidate> byUrl(List<PageResourceDiscovery.Candidate> list) {
        return list.stream().collect(Collectors.toMap(PageResourceDiscovery.Candidate::url, Function.identity(),
                (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @Test
    @DisplayName("sayfanın yükledikleri: stil, betik, görsel (tek aday), yazı tipi, simge, medya, iframe; lazy işaretli")
    void pageLoad() {
        PageResourceDiscovery.Found f = PageResourceDiscovery.discover(HTML.getBytes(StandardCharsets.UTF_8), BASE,
                u -> u.contains("/gizli/"));
        Map<String, PageResourceDiscovery.Candidate> p = byUrl(f.pageLoad());
        assertThat(p.get("https://ornek.com.tr/css/site.css").category()).isEqualTo("CSS");
        assertThat(p.keySet()).doesNotContain("https://ornek.com.tr/css/site.css#dup");   // parçasız aynı URL tekil
        assertThat(p.get("https://ornek.com.tr/f/inter.woff2").category()).isEqualTo("FONT");
        assertThat(p.get("https://ornek.com.tr/sayfa/hero.webp").category()).isEqualTo("IMAGE");
        assertThat(p.get("https://ornek.com.tr/js/mod.js").category()).isEqualTo("JS");
        assertThat(p.get("https://ornek.com.tr/favicon.ico").category()).isEqualTo("FAVICON");
        assertThat(p).as("apple-touch-icon masaüstünde indirilmez").doesNotContainKey("https://ornek.com.tr/apple.png");
        assertThat(p.get("https://ornek.com.tr/site.webmanifest").category()).isEqualTo("OTHER");
        assertThat(p.keySet()).noneMatch(u -> u.contains("cdn.example.net") && !u.endsWith("lib.js"));   // dns-prefetch yok
        assertThat(p.get("https://cdn.example.net/lib.js").category()).isEqualTo("JS");
        assertThat(p.get("https://ornek.com.tr/sayfa/logo.png").category()).isEqualTo("IMAGE");
        assertThat(p).containsKey("https://ornek.com.tr/sayfa/small.jpg").doesNotContainKey("https://ornek.com.tr/sayfa/big.jpg");
        assertThat(p.get("https://ornek.com.tr/sayfa/below.jpg").lazy()).isTrue();
        assertThat(p.get("https://ornek.com.tr/v/poster.jpg").category()).isEqualTo("IMAGE");
        assertThat(p.get("https://ornek.com.tr/v/clip.mp4").lazy()).as("otomatik oynatılmayan medya geç yüklenir").isTrue();
        assertThat(p.get("https://ornek.com.tr/v/clip.mp4").notLoaded()).as("preload yok: tarayıcı gövdeyi indirmez").isTrue();
        assertThat(p.get("https://ornek.com.tr/v/clip.mp4").pageLoad()).isFalse();
        assertThat(p.get("https://ornek.com.tr/v/auto.webm").lazy()).isFalse();
        assertThat(p.get("https://www.youtube.com/embed/x").category()).isEqualTo("IFRAME");
        assertThat(p.get("https://www.youtube.com/embed/x").lazy()).isTrue();
        assertThat(p.keySet()).noneMatch(u -> u.endsWith("/sayfa/"));   // canonical ve sayfanın kendisi yok
        // satır içi stiller ayrıca döner
        assertThat(f.inlineCss()).hasSize(2);
        assertThat(f.pageLoad()).allMatch(c -> c.pageLoad() || c.notLoaded());
        assertThat(f.baseUrl()).isEqualTo(BASE);
    }

    @Test
    @DisplayName("bağlantılar: dosya uzantılıysa BELGE, değilse BAĞLANTI; mailto / tel / js / # / kendisi / sayfanın yüklediği yok; hariç tutulan sayılır")
    void documentsAndLinks() {
        PageResourceDiscovery.Found f = PageResourceDiscovery.discover(HTML.getBytes(StandardCharsets.UTF_8), BASE,
                u -> u.contains("/gizli/"));
        assertThat(f.documents()).extracting(PageResourceDiscovery.Candidate::url).containsExactly(
                "https://ornek.com.tr/belgeler/rapor.pdf", "https://ornek.com.tr/belgeler/tablo.XLSX?v=2",
                "https://ornek.com.tr/logo-link.png");
        assertThat(f.documents()).allMatch(c -> "DOCUMENT".equals(c.category()) && !c.pageLoad());
        assertThat(f.links()).extracting(PageResourceDiscovery.Candidate::url)
                .containsExactly("https://ornek.com.tr/hakkimizda", "https://baska.com/");
        assertThat(f.links()).allMatch(c -> "LINK".equals(c.category()) && !c.pageLoad());
        assertThat(f.excluded()).isEqualTo(1);
    }

    @Test
    @DisplayName("CSS: url() (tırnaklı / çıplak), @import (iki biçim); data: ve # atlanır; kategori uzantıdan")
    void cssRefs() {
        String css = """
                @import "base.css";
                @import url(print.css) print;
                .a { background: url('img/a.png') }
                .b { background: url( "../img/b.svg" ) }
                .c { background: url(data:image/png;base64,AAAA) }
                .d { mask: url(#m) }
                @font-face { src: url(../f/x.woff2) format("woff2"), url(../f/x.ttf) }
                .e { background: url(img/a.png) }
                .f { background: url(/v/clip) }
                """;
        List<PageResourceDiscovery.Candidate> refs = PageResourceDiscovery.cssRefs(css, "https://ornek.com.tr/css/site.css");
        Map<String, PageResourceDiscovery.Candidate> m = byUrl(refs);
        assertThat(m.keySet()).containsExactly(
                "https://ornek.com.tr/css/base.css", "https://ornek.com.tr/css/print.css",
                "https://ornek.com.tr/css/img/a.png", "https://ornek.com.tr/img/b.svg",
                "https://ornek.com.tr/f/x.woff2", "https://ornek.com.tr/v/clip");
        assertThat(m.get("https://ornek.com.tr/css/base.css").category()).isEqualTo("CSS");
        assertThat(m.get("https://ornek.com.tr/css/img/a.png").category()).isEqualTo("IMAGE");
        assertThat(m.get("https://ornek.com.tr/f/x.woff2").category()).isEqualTo("FONT");
        assertThat(m.get("https://ornek.com.tr/v/clip").category()).isEqualTo("OTHER");
        assertThat(refs).allMatch(c -> c.fromCss() && c.pageLoad());
    }

    @Test
    @DisplayName("CSS taraması doğrusal ve sınırlı: kapanmayan url( ve dev girdi kilitlemez, en çok 500 başvuru")
    void cssIsBounded() {
        assertThat(PageResourceDiscovery.cssRefs("a{background:url(", "https://x.com/")).isEmpty();
        assertThat(PageResourceDiscovery.cssRefs("@import ", "https://x.com/")).isEmpty();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2_000; i++) sb.append(".c").append(i).append("{background:url(/i/").append(i).append(".png)}");
        assertThat(PageResourceDiscovery.cssRefs(sb.toString(), "https://x.com/")).hasSize(PageResourceDiscovery.MAX_CSS_REFS);
        String evil = "url(".repeat(400_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                () -> PageResourceDiscovery.cssRefs(evil, "https://x.com/"));
        String quoted = "url('".repeat(300_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                () -> PageResourceDiscovery.cssRefs(quoted, "https://x.com/"));
        String imports = "@import".repeat(300_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                () -> PageResourceDiscovery.cssRefs(imports, "https://x.com/"));
    }

    @Test
    @DisplayName("çok uzun adresler (> 4096) hem HTML'de hem CSS'te atlanır")
    void longUrlsSkipped() {
        String longPath = "/x/" + "a".repeat(5000) + ".png";
        String html = "<html><body><img src=\"" + longPath + "\"><img src=\"/ok.png\"></body></html>";
        PageResourceDiscovery.Found f = PageResourceDiscovery.discover(html.getBytes(StandardCharsets.UTF_8), BASE, null);
        assertThat(f.pageLoad()).extracting(PageResourceDiscovery.Candidate::url).containsExactly("https://ornek.com.tr/ok.png");
        assertThat(PageResourceDiscovery.cssRefs("a{background:url(" + longPath + ")} b{background:url(/k.png)}", BASE))
                .extracting(PageResourceDiscovery.Candidate::url).containsExactly("https://ornek.com.tr/k.png");
    }

    @Test
    @DisplayName("bozuk / boş HTML boş sonuç verir; uzantı yardımcı")
    void edgeCases() {
        PageResourceDiscovery.Found f = PageResourceDiscovery.discover(null, BASE, null);
        assertThat(f.pageLoad()).isEmpty();
        assertThat(PageResourceDiscovery.ext("https://a.com/x/rapor.PDF?x=1")).isEqualTo("pdf");
        assertThat(PageResourceDiscovery.ext("https://a.com/x/")).isEmpty();
        assertThat(PageResourceDiscovery.ext("https://a.com/x.y/z")).isEmpty();
    }

    // ── Algoritma 2: tarayıcı gibi keşif (inceleme H2/H4/H5/H6/H8/H12) ─────────────────────────────

    private static Map<String, PageResourceDiscovery.Candidate> pageOf(String html) {
        return byUrl(PageResourceDiscovery.discover(html.getBytes(StandardCharsets.UTF_8), BASE, null).pageLoad());
    }

    @Test
    @DisplayName("<noscript> ve <template> içi istenmez (GTM / piksel noscript'leri tetiklenmez)")
    void noscriptAndTemplateIgnored() {
        Map<String, PageResourceDiscovery.Candidate> p = pageOf("""
                <html><head><noscript><link rel="stylesheet" href="/ns.css"></noscript></head><body>
                  <noscript><img src="https://px.ads.linkedin.com/collect/?pid=1&fmt=gif"></noscript>
                  <noscript><iframe src="https://www.googletagmanager.com/ns.html?id=GTM-X"></iframe></noscript>
                  <template><img src="/tpl.png"><script src="/tpl.js"></script></template>
                  <img src="/real.png">
                </body></html>
                """);
        assertThat(p.keySet()).containsExactly("https://ornek.com.tr/real.png");
    }

    @Test
    @DisplayName("medya: yalnız autoplay / preload=auto indirilir, yalnız İLK uygun kaynak; diğerleri tek 'indirilmedi' adayı")
    void mediaRules() {
        Map<String, PageResourceDiscovery.Candidate> p = pageOf("""
                <html><body>
                  <video autoplay><source src="/a.m3u8" type="application/x-mpegURL"><source src="/a.mp4" type="video/mp4">
                    <source src="/a.webm" type="video/webm"><track src="/a.vtt" default><track src="/b.vtt"></video>
                  <video preload="auto"><source src="/b.mp4"><source src="/b.webm"></video>
                  <video preload><source src="/c.mp4"></video>
                  <video controls preload="none"><source src="/d.mp4"><source src="/d.webm"></video>
                  <video preload="metadata" src="/e.mp4"></video>
                  <audio><source src="/f.mp3"><source src="/f.ogg"></audio>
                </body></html>
                """);
        assertThat(p.get("https://ornek.com.tr/a.mp4").notLoaded()).isFalse();
        assertThat(p.get("https://ornek.com.tr/a.mp4").lazy()).as("autoplay tahmine girer").isFalse();
        assertThat(p).doesNotContainKeys("https://ornek.com.tr/a.m3u8", "https://ornek.com.tr/a.webm",
                "https://ornek.com.tr/b.webm", "https://ornek.com.tr/d.webm", "https://ornek.com.tr/f.ogg",
                "https://ornek.com.tr/b.vtt");
        assertThat(p.get("https://ornek.com.tr/a.vtt").category()).as("varsayılan altyazı indirilen medyayla").isEqualTo("OTHER");
        assertThat(p.get("https://ornek.com.tr/b.mp4").notLoaded()).isFalse();
        assertThat(p.get("https://ornek.com.tr/b.mp4").lazy()).as("preload=auto geç yüklenir").isTrue();
        assertThat(p.get("https://ornek.com.tr/c.mp4").notLoaded()).as("boş preload = auto").isFalse();
        for (String u : List.of("https://ornek.com.tr/d.mp4", "https://ornek.com.tr/e.mp4", "https://ornek.com.tr/f.mp3")) {
            PageResourceDiscovery.Candidate c = p.get(u);
            assertThat(c.notLoaded()).as(u).isTrue();
            assertThat(c.pageLoad()).as(u).isFalse();
            assertThat(c.category()).isEqualTo("MEDIA");
        }
    }

    @Test
    @DisplayName("<picture>: ilk desteklenen türdeki source (srcset'in ilki); img yedeği yalnız uygun source yoksa; max-width medyalı sona")
    void pictureSources() {
        Map<String, PageResourceDiscovery.Candidate> p = pageOf("""
                <html><body>
                  <picture><source type="image/avif" srcset="h.avif 1x, h2.avif 2x"><source type="image/webp" srcset="h.webp">
                    <img src="h.jpg" loading="lazy"></picture>
                  <picture><source type="image/jxl" srcset="j.jxl"><img src="j.jpg"></picture>
                  <picture><source media="(max-width: 600px)" srcset="m-small.webp"><source srcset="m-big.webp">
                    <img src="m.jpg"></picture>
                  <picture><img srcset="only.png 1x, only2.png 2x"></picture>
                </body></html>
                """);
        assertThat(p).containsKey("https://ornek.com.tr/sayfa/h.avif")
                .doesNotContainKeys("https://ornek.com.tr/sayfa/h2.avif", "https://ornek.com.tr/sayfa/h.webp",
                        "https://ornek.com.tr/sayfa/h.jpg", "https://ornek.com.tr/sayfa/j.jxl", "https://ornek.com.tr/sayfa/m.jpg",
                        "https://ornek.com.tr/sayfa/m-small.webp");
        assertThat(p.get("https://ornek.com.tr/sayfa/h.avif").lazy()).as("img'in loading'i").isTrue();
        assertThat(p).containsKeys("https://ornek.com.tr/sayfa/j.jpg", "https://ornek.com.tr/sayfa/m-big.webp",
                "https://ornek.com.tr/sayfa/only.png");
    }

    @Test
    @DisplayName("betik: nomodule ve çalıştırılmayan türler (text/template) sayılmaz; type=module sayılır; mobil simgeler yok")
    void scriptsAndIcons() {
        Map<String, PageResourceDiscovery.Candidate> p = pageOf("""
                <html><head>
                  <link rel="icon" href="/fav.png"><link rel="apple-touch-icon" href="/apple.png">
                  <link rel="apple-touch-icon-precomposed" href="/apple2.png"><link rel="mask-icon" href="/mask.svg">
                  <script type="module" src="/main-es2015.js"></script>
                  <script nomodule src="/main-es5.js"></script>
                  <script type="text/template" src="/tpl.html"></script>
                  <script type="text/javascript; charset=utf-8" src="/classic.js"></script>
                </head></html>
                """);
        assertThat(p.keySet()).containsExactlyInAnyOrder("https://ornek.com.tr/fav.png", "https://ornek.com.tr/main-es2015.js",
                "https://ornek.com.tr/classic.js");
    }

    @Test
    @DisplayName("@font-face (Font Awesome 4.7): yalnız SON src'nin ilk desteklenen biçimi (woff2); #iefix kopyası ve svg yok")
    void fontAwesomeOneFont() {
        String css = "@font-face{font-family:'FontAwesome';src:url('../fonts/fontawesome-webfont.eot?v=4.7.0');"
                + "src:url('../fonts/fontawesome-webfont.eot?#iefix&v=4.7.0') format('embedded-opentype'),"
                + "url('../fonts/fontawesome-webfont.woff2?v=4.7.0') format('woff2'),"
                + "url('../fonts/fontawesome-webfont.woff?v=4.7.0') format('woff'),"
                + "url('../fonts/fontawesome-webfont.ttf?v=4.7.0') format('truetype'),"
                + "url('../fonts/fontawesome-webfont.svg?v=4.7.0#fontawesomeregular') format('svg');"
                + "font-weight:normal;font-style:normal}"
                + ".fa{display:inline-block;background:url(../img/sprite.png)}";
        List<PageResourceDiscovery.Candidate> refs = PageResourceDiscovery.cssRefs(css, "https://ornek.com.tr/css/fa.css");
        assertThat(refs).extracting(PageResourceDiscovery.Candidate::url).containsExactly(
                "https://ornek.com.tr/fonts/fontawesome-webfont.woff2?v=4.7.0", "https://ornek.com.tr/img/sprite.png");
        assertThat(refs.get(0).category()).isEqualTo("FONT");
    }

    @Test
    @DisplayName("@font-face sıra ve yedek kuralları: listede ilk desteklenen; yalnız eot / svg varsa ilki (FONT); local() ve data: indirme değil")
    void fontFaceTable() {
        String base = "https://ornek.com.tr/css/x.css";
        // satır: CSS → beklenen tek URL (null = hiç başvuru yok)
        String[][] table = {
                {"@font-face{src:url(a.ttf) format('truetype'), url(a.woff2) format('woff2')}", "https://ornek.com.tr/css/a.ttf"},
                {"@font-face{src:local('Inter'), url(b.woff) format(\"woff\")}", "https://ornek.com.tr/css/b.woff"},
                {"@font-face{src:url(c.eot?#iefix) format('embedded-opentype'), url(c.svg#c) format('svg')}", "https://ornek.com.tr/css/c.eot"},
                {"@font-face{src:url(d.svg#d) format('svg')}", "https://ornek.com.tr/css/d.svg"},
                {"@font-face{src:url(data:font/woff2;base64,AAAA) format('woff2'), url(e.woff) format('woff')}", null},
                {"@font-face{src:url(f.weird) format('x-unknown'), url(f.otf)}", "https://ornek.com.tr/css/f.otf"},
                {"@font-face{src:url(g.woff2) format('woff2') tech(variations)}", "https://ornek.com.tr/css/g.woff2"},
                {"@font-face{font-family:x}", null},
        };
        for (String[] row : table) {
            List<PageResourceDiscovery.Candidate> refs = PageResourceDiscovery.cssRefs(row[0], base);
            if (row[1] == null) {
                assertThat(refs).as(row[0]).isEmpty();
            } else {
                assertThat(refs).as(row[0]).extracting(PageResourceDiscovery.Candidate::url).containsExactly(row[1]);
                assertThat(refs.get(0).category()).as(row[0]).isEqualTo("FONT");
            }
        }
    }

    @Test
    @DisplayName("CSS yorumundaki url() istenmez; dizgi içindeki '/*' yorum değildir; kapanmayan yorum sonuna kadar")
    void cssComments() {
        String css = "/* eski: .hero{background:url(../img/old-banner.jpg)} */ .a{background:url(new.png)}\n"
                + ".b:after{content:\"/*\"} .c{background:url(c.png)}\n"
                + "/* kapanmıyor .d{background:url(d.png)}";
        assertThat(PageResourceDiscovery.cssRefs(css, "https://ornek.com.tr/css/s.css"))
                .extracting(PageResourceDiscovery.Candidate::url)
                .containsExactly("https://ornek.com.tr/css/new.png", "https://ornek.com.tr/css/c.png");
    }

    @Test
    @DisplayName("anahtar: parça ve sonda kalan boş '?' atılır (font.eot?#iefix = font.eot)")
    void keyDropsEmptyQuery() {
        assertThat(PageResourceDiscovery.key("https://a.com/f.eot?#iefix")).isEqualTo("https://a.com/f.eot");
        assertThat(PageResourceDiscovery.key("https://a.com/f.eot?")).isEqualTo("https://a.com/f.eot");
        assertThat(PageResourceDiscovery.key("https://a.com/f.eot?v=1#x")).isEqualTo("https://a.com/f.eot?v=1");
    }

    @Test
    @DisplayName("<base href> belgenin taban adresidir: satır içi stiller onunla çözülür")
    void baseHrefForInlineStyles() {
        String html = "<html><head><base href=\"https://cdn.ornek.net/assets/\"><style>.a{background:url(i.png)}</style>"
                + "</head><body><img src=\"logo.png\"></body></html>";
        PageResourceDiscovery.Found f = PageResourceDiscovery.discover(html.getBytes(StandardCharsets.UTF_8), BASE, null);
        assertThat(f.baseUrl()).isEqualTo("https://cdn.ornek.net/assets/");
        assertThat(byUrl(f.pageLoad())).containsKey("https://cdn.ornek.net/assets/logo.png");
        assertThat(PageResourceDiscovery.cssRefs(f.inlineCss().get(0), f.baseUrl()))
                .extracting(PageResourceDiscovery.Candidate::url).containsExactly("https://cdn.ornek.net/assets/i.png");
    }

    @Test
    @DisplayName("yorum ayıklama ve @font-face seçimi doğrusal: kötü girdi kilitlemez")
    void commentAndFontFaceBounded() {
        String comments = "/*".repeat(300_000);
        String quotes = "\"'".repeat(300_000) + "/*x*/";
        String faces = "@font-face{src:".repeat(100_000);
        String srcs = "@font-face{" + "src src: src;".repeat(100_000) + "}";
        for (String evil : List.of(comments, quotes, faces, srcs)) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                    () -> PageResourceDiscovery.cssRefs(evil, "https://x.com/"));
        }
    }
}
