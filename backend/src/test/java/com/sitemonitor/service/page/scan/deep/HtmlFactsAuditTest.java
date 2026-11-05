package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.scan.PageResourceDiscovery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** HTML olguları (E denetimi, A ipuçları, C görseller, B HTML boyutu) ve SEO / erişilebilirlik puanlaması. */
class HtmlFactsAuditTest {

    static HtmlFacts facts(String html) {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        return HtmlFacts.extract(PageResourceDiscovery.parse(b, "https://ornek.com.tr/sayfa"), b.length);
    }

    static final String GOOD = """
            <!doctype html><html lang="tr"><head>
              <title>Ornek Bank — İnternet Şubesi</title>
              <meta name="description" content="Ornek Bank internet şubesi: hesaplarınızı, kartlarınızı ve yatırımlarınızı güvenle yönetin.">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <link rel="canonical" href="https://ornek.com.tr/sayfa">
              <meta property="og:title" content="Ornek"><meta property="og:description" content="d"><meta property="og:image" content="https://ornek.com.tr/og.png">
              <script type="application/ld+json">{"@context":"https://schema.org","@type":"Organization","name":"Ornek"}</script>
              <link rel="stylesheet" href="/css/site.css">
              <link rel="stylesheet" href="/css/print.css" media="print">
              <script src="/js/app.js"></script>
              <script src="/js/late.js" defer></script>
              <script src="https://cdn.example.net/lib.js" integrity="sha384-abc" crossorigin="anonymous"></script>
              <link rel="preload" href="/fonts/inter.woff2" as="font" crossorigin>
              <link rel="preconnect" href="https://cdn.example.net">
              <link rel="dns-prefetch" href="https://unused.example.org">
            </head><body>
              <h1>Hoş geldiniz</h1><h2>Hesaplar</h2><h3>Vadesiz</h3>
              <img src="/img/logo.png" alt="Ornek" width="120" height="40">
              <form><label for="q">Ara</label><input id="q" name="q" type="search"><button>Ara</button></form>
              <a href="/hakkimizda">Hakkımızda</a>
            </body></html>
            """;

    @Test
    @DisplayName("iyi sayfa: SEO denetimlerinin hepsi geçer, erişilebilirlik tam puan; olgular doğru")
    void goodPage() {
        HtmlFacts f = facts(GOOD);
        assertThat(f.https).isTrue();
        assertThat(f.scripts).extracting(HtmlFacts.ScriptTag::url).containsExactly("https://ornek.com.tr/js/app.js",
                "https://ornek.com.tr/js/late.js", "https://cdn.example.net/lib.js");
        assertThat(f.scripts.get(0).inHead()).isTrue();
        assertThat(f.scripts.get(1).defer()).isTrue();
        assertThat(f.scripts.get(2).integrity()).isTrue();
        assertThat(f.stylesheets).extracting(HtmlFacts.StyleTag::media).containsExactly(null, "print");
        assertThat(f.hints).extracting(HtmlFacts.Hint::rel).containsExactly("preload", "preconnect", "dns-prefetch");
        assertThat(f.hints.get(1).host()).isEqualTo("cdn.example.net");
        assertThat(f.images).singleElement().satisfies(i -> {
            assertThat(i.url()).isEqualTo("https://ornek.com.tr/img/logo.png");
            assertThat(i.hasWidth()).isTrue();
            assertThat(i.hasHeight()).isTrue();
        });
        assertThat(f.referenced).contains("https://ornek.com.tr/js/app.js", "https://ornek.com.tr/img/logo.png")
                .doesNotContain("https://ornek.com.tr/fonts/inter.woff2");
        assertThat(f.inlineScriptBytes).isPositive();

        HtmlAudit.Result r = HtmlAudit.audit(f, null, "https://ornek.com.tr/sayfa");
        Map<String, Object> seo = get(r.section(), "seo");
        List<Map<String, Object>> checks = get(seo, "checks");
        assertThat(checks).extracting(c -> c.get("key")).containsExactlyElementsOf(HtmlAudit.SEO_WEIGHTS.keySet());
        assertThat(checks).allSatisfy(c -> assertThat(c.get("status")).as(String.valueOf(c.get("key"))).isEqualTo("pass"));
        assertThat(r.seoScore()).isEqualTo(100);
        assertThat(r.a11yScore()).isEqualTo(100);
        Map<String, Object> structured = checks.get(8);
        assertThat(structured.get("value")).isEqualTo("Organization");
    }

    @Test
    @DisplayName("sorunlu sayfa: SEO uyarı / hatalar, erişilebilirlik sayıları ve kısa örnekler (kişi verisi yok)")
    void badPage() {
        HtmlFacts f = facts("""
                <html><head><title>A</title><title>B</title>
                <meta name="robots" content="noindex, nofollow">
                <meta name="viewport" content="width=device-width, user-scalable=no">
                <link rel="canonical" href="https://ornek.com.tr/baska">
                <meta property="og:title" content="x">
                <script type="application/ld+json">{bozuk</script>
                </head><body>
                <h1>Bir</h1><h1>İki</h1><h4>Atladı</h4>
                <img src="/a.png"><img src="/b.png?u=ayse@ornek.com"><img src="/c.png" alt="">
                <img src="/d.png" role="presentation"><img src="/e.png" aria-hidden="true">
                <input type="email" name="eposta" value="ayse@ornek.com"><input type="text" placeholder="Ad"><input type="hidden" name="t">
                <label>Soyad <input name="soyad"></label>
                <a href="/x"></a><a href="/y"><img src="/i.png" alt="Ana sayfa"></a><a href="/z" aria-label="Kapat"></a>
                <button></button><button class="btn close"></button><input type="button">
                <div id="dup"></div><span id="dup"></span><p id="ok"></p>
                </body></html>
                """);
        HtmlAudit.Result r = HtmlAudit.audit(f, "noarchive", "https://ornek.com.tr/sayfa");
        Map<String, Object> seo = get(r.section(), "seo");
        Map<String, Map<String, Object>> by = byKey(get(seo, "checks"));
        assertThat(by.get("title")).containsEntry("status", "warn").containsEntry("issue", "multiple").containsEntry("count", 2);
        assertThat(by.get("meta_description")).containsEntry("status", "fail").containsEntry("issue", "missing");
        assertThat(by.get("h1")).containsEntry("issue", "multiple").containsEntry("count", 2);
        assertThat(by.get("canonical")).containsEntry("issue", "other_page");
        assertThat(by.get("robots")).containsEntry("status", "fail").containsEntry("issue", "noindex");
        assertThat((String) by.get("robots").get("value")).contains("noarchive");
        assertThat(by.get("lang")).containsEntry("status", "fail");
        assertThat(by.get("viewport")).containsEntry("issue", "no_zoom");
        assertThat(by.get("open_graph")).containsEntry("issue", "partial");
        assertThat(by.get("structured_data")).containsEntry("issue", "invalid_json");

        Map<String, Object> a11y = get(r.section(), "accessibility");
        Map<String, Map<String, Object>> a = byKey(get(a11y, "checks"));
        assertThat(a.get("img_alt")).containsEntry("status", "warn").containsEntry("count", 2);
        assertThat(a.get("img_alt").get("samples").toString()).contains("img[src=/a.png]", "img[src=/b.png]")
                .doesNotContain("ayse@ornek.com");
        assertThat(a.get("form_labels")).containsEntry("count", 1);
        assertThat(a.get("form_labels").get("samples").toString()).contains("input[type=email][name=eposta]")
                .doesNotContain("ayse@ornek.com");
        assertThat(a.get("link_names")).containsEntry("count", 1);
        assertThat(a.get("button_names")).containsEntry("status", "fail").containsEntry("count", 3);
        assertThat(a.get("heading_order")).containsEntry("issue", "skipped");
        assertThat(a.get("heading_order").get("samples").toString()).contains("h1 → h4");
        assertThat(a.get("html_lang")).containsEntry("status", "fail");
        assertThat(a.get("duplicate_ids")).containsEntry("count", 1);
        assertThat(r.seoScore()).isBetween(0, 60);
        assertThat(r.a11yScore()).isBetween(0, 60);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0} → {1}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '#', nullValues = "-", value = {
            // D-7: yönergeler BELİRTEÇ — değer alan yönergelerin "none" değeri noindex DEĞİL
            "max-image-preview:none#-",
            "index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1#-",
            "max-image-preview: none, max-video-preview:0#-",
            "noimageindex, notranslate#-",
            "unavailable_after: Friday, 25-Jun-10 15:00:00 GMT#-",
            "noindex#noindex",
            "NOINDEX, NoFollow#noindex",
            "none#noindex",
            "index, none#noindex",
            "nofollow#nofollow",
            "googlebot: noindex#noindex",
            "googlebot: max-image-preview:none#-",
            "otherbot: nofollow; index#nofollow",
            "noindex nofollow#noindex",
            "nonexistent, nonesuch#-",
            "-#-"})
    void robotsTokens(String robots, String expected) {
        assertThat(HtmlAudit.robotsVerdict(robots)).isEqualTo(expected);
    }

    @Test
    @DisplayName("D-7: haber sitesi max-image-preview:none → robots geçti, SEO puanı düşmez")
    void robotsMaxImagePreviewNoneIsNotNoindex() {
        HtmlFacts f = facts("""
                <html lang="tr"><head><title>Haber başlığı yeterince uzun bir başlık</title>
                <meta name="robots" content="max-image-preview:none, max-snippet:-1">
                </head><body><h1>Haber</h1></body></html>
                """);
        Map<String, Map<String, Object>> by = byKey(get(get(HtmlAudit.audit(f, null, null).section(), "seo"), "checks"));
        assertThat(by.get("robots")).containsEntry("status", "pass").containsEntry("issue", null);
        Map<String, Map<String, Object>> hdr = byKey(get(get(HtmlAudit.audit(f, "googlebot: noindex", null).section(), "seo"),
                "checks"));
        assertThat(hdr.get("robots")).containsEntry("status", "fail").containsEntry("issue", "noindex");
    }

    @Test
    @DisplayName("HTML olguları yoksa bölüm ve puanlar null (uydurma yok)")
    void noFacts() {
        HtmlAudit.Result r = HtmlAudit.audit(null, null, null);
        assertThat(r.section()).isNull();
        assertThat(r.seoScore()).isNull();
        assertThat(r.a11yScore()).isNull();
    }

    @Test
    @DisplayName("veri adresleri: öznitelik ve satır içi stildeki url(data:) sayılır; DOM düğümü ve derinlik")
    void dataUrisAndShape() {
        HtmlFacts f = facts("<html><body><img src=\"data:image/png;base64,AAAA\"><div style=\"background:url('data:image/gif;base64,BBBB')\">"
                + "<div><div><span>x</span></div></div></div><style>.a{background:url(data:image/png;base64,CCCCCC)}</style></body></html>");
        assertThat(f.dataUriCount).isEqualTo(3);
        assertThat(f.dataUriBytes).isPositive();
        assertThat(f.domNodes).isGreaterThanOrEqualTo(8);
        assertThat(f.maxDepth).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("@font-face: aile, ağırlık, font-display, seçilen dosya (keşifle aynı kural)")
    void fontFaces() {
        String css = "/* yorum @font-face{} */ @font-face{font-family:\"Inter\";font-weight:400;font-display:swap;"
                + "src:url(/f/inter.eot);src:url(/f/inter.woff2) format('woff2'),url(/f/inter.woff) format('woff')}"
                + "@font-face{font-family:Inter;font-weight:700 !important;src:url(/f/inter-b.ttf)}"
                + "@font-face{src:url(/f/nofamily.woff2)}";
        List<FontFaces.Face> faces = FontFaces.scan(css, "https://ornek.com.tr/css/site.css", "https://ornek.com.tr/css/site.css");
        assertThat(faces).hasSize(2);
        assertThat(faces.get(0)).isEqualTo(new FontFaces.Face("Inter", "400", "swap", "https://ornek.com.tr/f/inter.woff2",
                "https://ornek.com.tr/css/site.css"));
        assertThat(faces.get(1).weight()).isEqualTo("700");
        assertThat(faces.get(1).display()).isNull();
        assertThat(faces.get(1).url()).isEqualTo("https://ornek.com.tr/f/inter-b.ttf");
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> checks) {
        Map<String, Map<String, Object>> m = new java.util.LinkedHashMap<>();
        for (Map<String, Object> c : checks) m.put((String) c.get("key"), c);
        return m;
    }
}
