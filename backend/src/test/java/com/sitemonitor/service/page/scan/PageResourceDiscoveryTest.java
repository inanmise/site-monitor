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
        assertThat(p.get("https://ornek.com.tr/apple.png").category()).isEqualTo("FAVICON");
        assertThat(p.get("https://ornek.com.tr/site.webmanifest").category()).isEqualTo("OTHER");
        assertThat(p.keySet()).noneMatch(u -> u.contains("cdn.example.net") && !u.endsWith("lib.js"));   // dns-prefetch yok
        assertThat(p.get("https://cdn.example.net/lib.js").category()).isEqualTo("JS");
        assertThat(p.get("https://ornek.com.tr/sayfa/logo.png").category()).isEqualTo("IMAGE");
        assertThat(p).containsKey("https://ornek.com.tr/sayfa/small.jpg").doesNotContainKey("https://ornek.com.tr/sayfa/big.jpg");
        assertThat(p.get("https://ornek.com.tr/sayfa/below.jpg").lazy()).isTrue();
        assertThat(p.get("https://ornek.com.tr/v/poster.jpg").category()).isEqualTo("IMAGE");
        assertThat(p.get("https://ornek.com.tr/v/clip.mp4").lazy()).as("otomatik oynatılmayan medya geç yüklenir").isTrue();
        assertThat(p.get("https://ornek.com.tr/v/auto.webm").lazy()).isFalse();
        assertThat(p.get("https://www.youtube.com/embed/x").category()).isEqualTo("IFRAME");
        assertThat(p.get("https://www.youtube.com/embed/x").lazy()).isTrue();
        assertThat(p.keySet()).noneMatch(u -> u.endsWith("/sayfa/"));   // canonical ve sayfanın kendisi yok
        // satır içi stiller ayrıca döner
        assertThat(f.inlineCss()).hasSize(2);
        assertThat(f.pageLoad()).allMatch(PageResourceDiscovery.Candidate::pageLoad);
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
                "https://ornek.com.tr/f/x.woff2", "https://ornek.com.tr/f/x.ttf", "https://ornek.com.tr/v/clip");
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
    @DisplayName("bozuk / boş HTML boş sonuç verir; uzantı yardımcı")
    void edgeCases() {
        PageResourceDiscovery.Found f = PageResourceDiscovery.discover(null, BASE, null);
        assertThat(f.pageLoad()).isEmpty();
        assertThat(PageResourceDiscovery.ext("https://a.com/x/rapor.PDF?x=1")).isEqualTo("pdf");
        assertThat(PageResourceDiscovery.ext("https://a.com/x/")).isEmpty();
        assertThat(PageResourceDiscovery.ext("https://a.com/x.y/z")).isEmpty();
    }
}
