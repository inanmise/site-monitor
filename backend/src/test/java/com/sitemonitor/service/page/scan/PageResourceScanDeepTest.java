package com.sitemonitor.service.page.scan;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.page.PageFetchCore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Algoritma 3 uçtan uca — GERÇEK SQL (H2) + GERÇEK çekim çekirdeği + yerel HTTP sunucusunda gerçekçi bir sayfa: güvenlik
 * başlıkları (eksik / zayıf), çerezler (değer saklanmaz), oluşturmayı engelleyen betik ve stil, aynı içerikli iki dosya,
 * küçültülmemiş betik, açıkta kaynak haritası, eski jQuery (bilinen açıklar), alt metni olmayan görsel, boyutsuz görsel,
 * {@code font-display}'siz yazı tipi, hareketli GIF. Özet kolonları (js / css / font baytı, kategori puanları) yazılır.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PageResourceScanDeepTest {

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    HttpServer server;
    String base;
    PageResourceScanService svc;
    final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    static final String APP_JS = "// Uygulama\n" + "function topla(a, b) {\n    // iki sayıyı toplar\n    return a + b;\n}\n\n".repeat(400)
            + "//# sourceMappingURL=app.js.map\n";
    static final String JQUERY = "/*! jQuery v1.12.4 | (c) jQuery Foundation | jquery.org/license */\n"
            + "!function(a,b){\"object\"==typeof module?b(a):b(a)}(this,function(a){var c=[];return c});".repeat(80);
    static final String CSS = "@font-face{font-family:'Kurum';font-weight:400;src:url(/f/kurum.woff2) format('woff2')}"
            + "body{background:url(/img/anim.gif)}";

    static byte[] gif(int frames) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, 0, 0, 0});
        for (int k = 0; k < frames; k++) {
            o.writeBytes(new byte[]{0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0});            // grafik denetim uzantısı
            o.writeBytes(new byte[]{0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0});                 // görüntü tanımlayıcısı
            o.writeBytes(new byte[]{2, 2, 0x44, 0x01, 0});                             // LZW + veri + son
        }
        o.write(0x3B);
        byte[] pad = new byte[12_000];
        o.writeBytes(pad);
        return o.toByteArray();
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM page_resource_scans");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] gifBody = gif(3);
        String html = """
                <!doctype html><html><head>
                  <title>Kurum</title>
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <link rel="stylesheet" href="/css/site.css">
                  <script src="/js/app.js"></script>
                  <script src="/js/jquery-1.12.4.min.js"></script>
                  <script src="/js/kopya.js"></script>
                  <link rel="preload" href="/img/kullanilmayan.png" as="image">
                </head><body>
                  <h1>Kurum</h1>
                  <img src="/img/logo.png">
                  <img src="/img/b1.png" alt="b" width="10" height="10"><img src="/img/b2.png" alt="b" width="10" height="10">
                  <img src="/img/b3.png" alt="b" width="10" height="10"><img src="/img/buyuk.png" alt="b" width="10" height="10">
                  <a href="/hakkimizda">Hakkımızda</a>
                </body></html>
                """;
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            hits.computeIfAbsent(ex.getRequestMethod() + " " + path, k -> new AtomicInteger()).incrementAndGet();
            byte[] body;
            String ct;
            int status = 200;
            switch (path) {
                case "/" -> {
                    body = html.getBytes(StandardCharsets.UTF_8);
                    ct = "text/html; charset=utf-8";
                    ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
                    ex.getResponseHeaders().add("Content-Security-Policy", "script-src 'self' 'unsafe-inline'");
                    ex.getResponseHeaders().add("Set-Cookie", "ASP.NET_SessionId=GIZLI-OTURUM-DEGERI; path=/");
                    ex.getResponseHeaders().add("Set-Cookie", "tercih=koyu; Max-Age=3600; SameSite=Lax; HttpOnly");
                }
                case "/css/site.css" -> { body = CSS.getBytes(StandardCharsets.UTF_8); ct = "text/css"; }
                case "/js/app.js" -> { body = APP_JS.getBytes(StandardCharsets.UTF_8); ct = "application/javascript"; }
                case "/js/app.js.map" -> {
                    if ("HEAD".equals(ex.getRequestMethod())) { ex.getResponseHeaders().add("Content-Type", "application/json");
                        ex.sendResponseHeaders(200, -1); ex.close(); return; }
                    body = "{}".getBytes(); ct = "application/json";
                }
                case "/js/jquery-1.12.4.min.js", "/js/kopya.js" -> {
                    body = JQUERY.getBytes(StandardCharsets.UTF_8);
                    ct = "application/javascript";
                }
                case "/img/anim.gif" -> { body = gifBody; ct = "image/gif"; }
                case "/f/kurum.woff2" -> { body = new byte[4000]; ct = "font/woff2"; }
                case "/img/logo.png", "/img/b1.png", "/img/b2.png", "/img/b3.png", "/img/kullanilmayan.png" -> {
                    body = PageResourceScanServiceTest.png(20, 20);
                    ct = "image/png";
                }
                case "/img/buyuk.png" -> { body = PageResourceScanServiceTest.png(1200, 900); ct = "image/png"; }
                case "/hakkimizda" -> { body = "<html>h</html>".getBytes(StandardCharsets.UTF_8); ct = "text/html"; }
                default -> { body = "yok".getBytes(); status = 404; ct = "text/plain"; }
            }
            ex.getResponseHeaders().add("Content-Type", ct);
            if (!path.equals("/")) ex.getResponseHeaders().add("Cache-Control", "public, max-age=86400");
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(status, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(status, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(s.getInt(anyString(), anyInt())).thenAnswer(a -> a.getArgument(1));
        PageFetchCore core = new PageFetchCore(new SsrfGuard(s));
        core.init();
        PageCheckerService checker = mock(PageCheckerService.class);
        when(checker.effectiveUserAgent()).thenReturn("SiteMonitor-Test");
        when(checker.excludePredicate(org.mockito.ArgumentMatchers.any())).thenReturn(u -> false);
        PublicSuffixService psl = mock(PublicSuffixService.class);
        when(psl.sameSite(anyString(), anyString())).thenAnswer(a -> a.getArgument(0).equals(a.getArgument(1)));
        svc = new PageResourceScanService(jdbc, core, checker, psl, s);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        svc.shutdown();
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    static Map<String, Object> rec(Map<String, Object> analysis, String code) {
        List<Map<String, Object>> recs = get(analysis, "recommendations");
        return recs.stream().filter(m -> code.equals(m.get("code"))).findFirst().orElse(null);
    }

    static Map<String, Object> item(List<Map<String, Object>> items, String end) {
        return items.stream().filter(i -> ((String) i.get("url")).endsWith(end)).findFirst().orElseThrow();
    }

    Map<String, Object> run() throws Exception {
        PageMonitor m = new PageMonitor();
        m.setId(41L);
        m.setTeamId(7L);
        m.setName("Kurum");
        m.setUrl(base + "/");
        PageResourceScanService.Start st = svc.start(m, "u:41", "Test");
        long id = ((Number) st.scan().get("id")).longValue();
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            Map<String, Object> one = svc.one(id, 41);
            if (!"RUNNING".equals(((Map<String, Object>) one.get("scan")).get("status"))) return one;
            Thread.sleep(50);
        }
        throw new AssertionError("tarama bitmedi");
    }

    @Test
    @DisplayName("uçtan uca derin analiz: A–E bölümleri, öneriler, nesne alanları, özet kolonları; çerez değeri hiçbir yerde yok")
    void deepEndToEnd() throws Exception {
        Map<String, Object> one = run();
        Map<String, Object> scan = get(one, "scan");
        assertThat(scan.get("status")).isEqualTo("DONE");
        assertThat(scan.get("algorithm_version")).isEqualTo(3);
        assertThat((Long) scan.get("js_bytes")).isEqualTo((long) APP_JS.getBytes(StandardCharsets.UTF_8).length
                + 2L * JQUERY.getBytes(StandardCharsets.UTF_8).length);
        assertThat((Long) scan.get("css_bytes")).isEqualTo(CSS.length());
        assertThat((Long) scan.get("font_bytes")).isEqualTo(4000L);
        assertThat(scan.get("security_score")).isNotNull();
        assertThat(scan.get("seo_score")).isNotNull();
        assertThat(scan.get("a11y_score")).isNotNull();
        assertThat(scan.get("scope")).isEqualTo("page");

        Map<String, Object> analysis = get(one, "analysis");
        assertThat(analysis.get("algorithm_version")).isEqualTo(3);

        // A — oluşturmayı engelleyenler + kullanılmayan önyükleme + host ölçümü
        Map<String, Object> loading = get(analysis, "loading");
        Map<String, Object> rb = get(loading, "render_blocking");
        assertThat(rb.get("count")).as("site.css + 3 betik").isEqualTo(4);
        assertThat(rec(analysis, "RENDER_BLOCKING_RESOURCES")).isNotNull();
        assertThat(rec(analysis, "UNUSED_PRELOAD")).isNotNull();
        List<Map<String, Object>> hosts = get(loading, "hosts");
        assertThat(hosts).singleElement().satisfies(h -> {
            assertThat(h).containsEntry("host", "127.0.0.1").containsEntry("measured", true).containsEntry("http_version", "1.1");
            assertThat(h.get("connect_ms")).isNotNull();
            assertThat(h.get("tls_ms")).isNull();
        });

        // B — aynı içerik (jquery ×2), küçültülmemiş app.js, açık kaynak haritası, kütüphane
        Map<String, Object> waste = get(analysis, "waste");
        List<Map<String, Object>> dups = get(waste, "duplicates");
        assertThat(dups).singleElement().satisfies(d -> assertThat(d).containsEntry("kind", "same_content"));
        List<Map<String, Object>> unmin = get(waste, "unminified");
        assertThat(unmin).extracting(u -> (String) u.get("url")).anyMatch(u -> u.endsWith("/js/app.js"));
        List<Map<String, Object>> maps = get(waste, "source_maps");
        assertThat(maps).singleElement().satisfies(mp -> assertThat(mp).containsEntry("exposed", true));
        assertThat(hits.get("HEAD /js/app.js.map").get()).isEqualTo(1);
        assertThat(hits.get("GET /js/app.js.map")).as("harita gövdesi indirilmez").isNull();
        assertThat(rec(analysis, "SOURCE_MAP_EXPOSED")).isNotNull();

        // C — boyutsuz görsel, hareketli GIF, font-display'siz yazı tipi
        Map<String, Object> media = get(analysis, "media");
        Map<String, Object> images = get(media, "images");
        assertThat((List<?>) images.get("missing_dimensions")).hasSize(1);
        List<Map<String, Object>> gifs = get(images, "animated_gif");
        assertThat(gifs).singleElement().satisfies(g -> assertThat(g).containsEntry("frames", 3));
        Map<String, Object> fonts = get(media, "fonts");
        assertThat((List<?>) fonts.get("missing_display")).hasSize(1);
        assertThat((List<?>) fonts.get("non_woff2")).isEmpty();

        // D — başlıklar, çerezler (DEĞERSİZ), eski jQuery
        Map<String, Object> sec = get(analysis, "security");
        List<Map<String, Object>> headers = get(sec, "headers");
        Map<String, Object> csp = headers.stream().filter(h -> "csp".equals(h.get("key"))).findFirst().orElseThrow();
        assertThat(csp).containsEntry("status", "weak").containsEntry("issue", "unsafe_inline");
        Map<String, Object> hsts = headers.stream().filter(h -> "hsts".equals(h.get("key"))).findFirst().orElseThrow();
        assertThat(hsts).containsEntry("issue", "not_https");
        List<Map<String, Object>> cookies = get(sec, "cookies");
        assertThat(cookies).extracting(c -> c.get("name")).containsExactly("ASP.NET_SessionId", "tercih");
        assertThat(rec(analysis, "INSECURE_COOKIES")).isNotNull();
        assertThat(rec(analysis, "WEAK_CSP")).isNotNull();
        List<Map<String, Object>> libs = get(sec, "libraries");
        assertThat(libs).anySatisfy(l -> {
            assertThat(l).containsEntry("name", "jquery").containsEntry("version", "1.12.4");
            assertThat((List<?>) l.get("vulns")).isNotEmpty();
        });
        assertThat(rec(analysis, "VULNERABLE_LIBRARY")).containsEntry("finding", "VULNERABLE_LIBRARY");

        // E — SEO + erişilebilirlik
        Map<String, Object> audit = get(analysis, "html_audit");
        Map<String, Object> a11y = get(audit, "accessibility");
        List<Map<String, Object>> checks = get(a11y, "checks");
        assertThat(checks).filteredOn(c -> "img_alt".equals(c.get("key"))).singleElement()
                .satisfies(c -> assertThat(c).containsEntry("count", 1));

        // Nesne alanları
        List<Map<String, Object>> items = get(one, "items");
        Map<String, Object> app = item(items, "/js/app.js");
        assertThat(app).containsEntry("minified", false).containsEntry("render_blocking", true)
                .containsEntry("http_version", "1.1").containsEntry("integrity", false);
        assertThat((String) app.get("source_map")).endsWith("/js/app.js.map");
        assertThat((String) app.get("content_hash")).hasSize(16);
        Map<String, Object> jq = item(items, "/js/jquery-1.12.4.min.js");
        assertThat(jq.get("library")).isEqualTo(Map.of("name", "jquery", "version", "1.12.4"));
        assertThat(jq.get("minified")).isEqualTo(true);
        assertThat(jq.get("findings").toString()).contains("DUPLICATE", "VULNERABLE_LIBRARY", "RENDER_BLOCKING");
        Map<String, Object> css = item(items, "/css/site.css");
        assertThat(css).containsEntry("render_blocking", true);

        // Çerez DEĞERİ saklanmaz (analiz, nesneler, özet)
        String stored = jdbc.queryForObject("SELECT analysis_json || items_json FROM page_resource_scans WHERE monitor_id = 41",
                String.class);
        assertThat(stored).doesNotContain("GIZLI-OTURUM-DEGERI").doesNotContain("koyu");
    }

    @Test
    @DisplayName("O-5: vekil üzerinden çekilen nesnede HTTP sürümü vekilin değeridir → bilinmiyor (null); doğrudan çekimde ölçülen")
    void proxiedItemHasNoHttpVersion() {
        PageFetchCore.Fetch f = new PageFetchCore.Fetch(200, 5, 9, 4000, null, null, false, false);
        PageFetchCore.ScanMeta sm = new PageFetchCore.ScanMeta("1.1", "\"e1\"", null, null, "h3=\":443\"", null,
                "abc", null, null, false, "encoding", List.of(), null);
        PageFetchCore.Meta meta = new PageFetchCore.Meta("https://a.com/x.js", 0, "application/javascript", null, 4000L,
                "max-age=600", null, null, null, null, null, 0L, null, sm);
        PageResourceDiscovery.Candidate c = new PageResourceDiscovery.Candidate("https://a.com/x.js", "JS", true, false, false);
        ScanItem proxied = svc.item(c, new PageFetchCore.MetaFetch(f, meta), "a.com", false, "proxy", "page");
        assertThat(proxied.via).isEqualTo("proxy");
        assertThat(proxied.httpVersion).as("vekilin ALPN'i sitenin sürümü değildir").isNull();
        assertThat(proxied.h3Advertised).as("Alt-Svc kökenin başlığı: korunur").isTrue();
        assertThat(proxied.toDeepMap()).containsEntry("http_version", null);
        ScanItem direct = svc.item(c, new PageFetchCore.MetaFetch(f, meta), "a.com", false, "direct", "page");
        assertThat(direct.httpVersion).isEqualTo("1.1");
    }

    @Test
    @DisplayName("D5: kırık / hata yanıtının içerik türü bağlantıyı 'belge' yapmaz; başarılı HTML olmayan yanıt belge olur")
    void errorResponseContentTypeDoesNotMakeADocument() {
        PageResourceDiscovery.Candidate link = new PageResourceDiscovery.Candidate("https://a.com/urunler", "LINK", false, false, false);
        for (int status : new int[]{404, 500, 403, 0}) {
            ScanItem it = svc.item(link, metaFetch(status, "text/plain; charset=utf-8"), "a.com", true, "direct", "page");
            assertThat(it.category).as("durum %d", status).isEqualTo("LINK");
            assertThat(it.pageLoad).isFalse();
        }
        assertThat(svc.item(link, metaFetch(200, "application/pdf"), "a.com", true, "direct", "page").category)
                .isEqualTo("DOCUMENT");
        assertThat(svc.item(link, metaFetch(200, "text/html"), "a.com", true, "direct", "page").category).isEqualTo("LINK");
        PageResourceDiscovery.Candidate doc = new PageResourceDiscovery.Candidate("https://a.com/k.pdf", "DOCUMENT", false, false, false);
        assertThat(svc.item(doc, metaFetch(404, "text/html"), "a.com", true, "direct", "page").category)
                .as("uzantıdan belge, durum ne olursa olsun belge").isEqualTo("DOCUMENT");
        PageResourceDiscovery.Candidate other = new PageResourceDiscovery.Candidate("https://a.com/x", "OTHER", true, false, false);
        assertThat(svc.item(other, metaFetch(404, "text/css"), "a.com", false, "direct", "page").category).isEqualTo("OTHER");
        assertThat(svc.item(other, metaFetch(200, "text/css"), "a.com", false, "direct", "page").category).isEqualTo("CSS");
        assertThat(PageResourceScanService.refine(link, "text/plain", 404)).isEqualTo("LINK");
        assertThat(PageResourceScanService.refine(link, "text/plain", 200)).isEqualTo("DOCUMENT");
    }

    static PageFetchCore.MetaFetch metaFetch(int status, String contentType) {
        PageFetchCore.Fetch f = new PageFetchCore.Fetch(status, 5, 9, status == 0 ? 0 : 1200, null,
                status == 0 ? "TIMEOUT" : null, false, false);
        return new PageFetchCore.MetaFetch(f, new PageFetchCore.Meta("https://a.com/x", 0, contentType, null, 1200L, null, null,
                null, null));
    }
}
