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

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok sayfalı kaynak analizi (prs3 G) — GERÇEK SQL (H2) + GERÇEK çekim çekirdeği + yerel HTTP sunucusu: ana sayfa bugünkü gibi,
 * iç sayfalar ana sayfanın bağlantılarından (aynı host, uzantısız / .html, DOM sırası, tekil, hariç tutma); ortak nesneler BİR
 * kez indirilir; açılamayan sayfa satırı; tek süre bütçesi dolunca biten sayfalar raporlanır, kalanlar "taranmadı";
 * özet satırı {@code scope='site'} + {@code pages_count}; tek sayfa analizi değişmez.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PageResourceSiteScanTest {

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    HttpServer server;
    String base;
    PageResourceScanService svc;
    AppSettingsService settings;
    final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    final AtomicLong clockOffset = new AtomicLong();
    volatile boolean jumpOnAbout;
    /** Doluysa "/contact.html" GET'i bu kapı açılana dek (en çok 10 sn) bekletir — koşu sırasındaki ilerlemeyi okumak için. */
    volatile java.util.concurrent.CountDownLatch contactGate;
    byte[] aboutHtml;
    byte[] aboutPng;

    static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", bos);
        return bos.toByteArray();
    }

    int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM page_resource_scans");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] img = png(1200, 800);
        aboutPng = png(200, 100);
        String main = """
                <html><head>
                  <link rel="stylesheet" href="/s.css">
                  <script src="/a.js"></script>
                </head><body>
                  <img src="/hero.png">
                  <a href="/about">a</a>
                  <a href="/about/">a again</a>
                  <a href="/contact.html">c</a>
                  <a href="/files/r.pdf">pdf</a>
                  <a href="/broken">b</a>
                  <a href="/gone">g</a>
                  <a href="/pdf-page">p</a>
                  <a href="/skip/me">s</a>
                  <a href="http://other.example.invalid/page">o</a>
                </body></html>
                """;
        aboutHtml = """
                <html><head>
                  <link rel="stylesheet" href="/s.css">
                  <script src="/a.js"></script>
                </head><body><img src="/about.png"></body></html>
                """.getBytes(StandardCharsets.UTF_8);
        String contact = """
                <html><head>
                  <link rel="stylesheet" href="/s.css">
                  <script src="/c.js"></script>
                </head><body>contact</body></html>
                """;
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            hits.computeIfAbsent(ex.getRequestMethod() + " " + path, k -> new AtomicInteger()).incrementAndGet();
            byte[] body;
            int status = 200;
            String ct;
            switch (path) {
                case "/" -> { body = main.getBytes(StandardCharsets.UTF_8); ct = "text/html; charset=utf-8"; }
                case "/about", "/about/" -> { body = aboutHtml; ct = "text/html"; }
                case "/contact.html" -> {
                    java.util.concurrent.CountDownLatch gate = contactGate;
                    if (gate != null && "GET".equals(ex.getRequestMethod())) {
                        try {
                            gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    body = contact.getBytes(StandardCharsets.UTF_8);
                    ct = "text/html";
                }
                case "/s.css" -> {
                    body = ("@font-face{font-family:F;src:url(/f.woff2) format('woff2')} body{background:url(/bg.png)}")
                            .getBytes(StandardCharsets.UTF_8);
                    ct = "text/css";
                }
                case "/a.js" -> { body = "x".repeat(4000).getBytes(StandardCharsets.UTF_8); ct = "application/javascript"; }
                case "/c.js" -> { body = "y".repeat(500).getBytes(StandardCharsets.UTF_8); ct = "application/javascript"; }
                case "/f.woff2" -> { body = new byte[2000]; ct = "font/woff2"; }
                case "/hero.png", "/bg.png" -> { body = img; ct = "image/png"; }
                case "/about.png" -> {
                    // Yalnız ilk iç sayfanın kendi görseli: süre bütçesini o sayfanın ölçümü sırasında bitirir
                    if (jumpOnAbout) clockOffset.addAndGet(10_000_000L);
                    body = aboutPng;
                    ct = "image/png";
                }
                case "/files/r.pdf", "/pdf-page" -> { body = new byte[3000]; ct = "application/pdf"; }
                case "/broken" -> { body = "nope".getBytes(); status = 500; ct = "text/plain"; }
                default -> { body = "missing".getBytes(); status = 404; ct = "text/plain"; }
            }
            ex.getResponseHeaders().add("Content-Type", ct);
            ex.sendResponseHeaders(status, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        settings = mock(AppSettingsService.class);
        when(settings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(settings.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(a -> a.getArgument(1));
        PageFetchCore core = new PageFetchCore(new SsrfGuard(settings));
        core.init();
        PageCheckerService checker = mock(PageCheckerService.class);
        when(checker.effectiveUserAgent()).thenReturn("SiteMonitor-Test");
        when(checker.excludePredicate(eq("/skip/"))).thenReturn(u -> u.contains("/skip/"));
        PublicSuffixService psl = mock(PublicSuffixService.class);
        when(psl.sameSite(anyString(), anyString())).thenAnswer(a -> a.getArgument(0).equals(a.getArgument(1)));
        svc = new PageResourceScanService(jdbc, core, checker, psl, settings);
        svc.clock = () -> System.currentTimeMillis() + clockOffset.get();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        svc.shutdown();
    }

    PageMonitor monitor(long id) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setTeamId(7L);
        m.setName("Ana sayfa");
        m.setUrl(base + "/");
        m.setExcludePatterns("/skip/");
        return m;
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    /** Durumu doğrudan tablodan bekler (saat kaydırılan testte okuma yolunun "eskidi" kuralı devreye girmesin). */
    Map<String, Object> awaitEnd(long monitorId, long scanId) throws InterruptedException {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            String st = jdbc.queryForObject("SELECT status FROM page_resource_scans WHERE id = ?", String.class, scanId);
            if (!"RUNNING".equals(st)) return svc.one(scanId, monitorId);
            Thread.sleep(50);
        }
        throw new AssertionError("tarama bitmedi");
    }

    long start(long monitorId, Integer maxPages) {
        PageResourceScanService.Start st = maxPages == null ? svc.start(monitor(monitorId), "u:1", "Ayşe")
                : svc.start(monitor(monitorId), "u:1", "Ayşe", new PageResourceScanService.SiteOptions(maxPages));
        assertThat(st.outcome()).isEqualTo(PageResourceScanService.Outcome.STARTED);
        return ((Number) st.scan().get("id")).longValue();
    }

    @Test
    @DisplayName("site taraması: ana sayfa + iç sayfalar (DOM sırası, tekil, hariç tutma, başka host / belge yok); ortaklar bir kez indirilir")
    void siteScanEndToEnd() throws Exception {
        long id = start(1, 10);
        Map<String, Object> one = awaitEnd(1, id);
        Map<String, Object> scan = get(one, "scan");
        assertThat(scan.get("status")).isEqualTo("DONE");
        assertThat(scan.get("scope")).isEqualTo("site");
        assertThat(scan.get("pages_count")).as("ana sayfa + about + contact").isEqualTo(3);
        assertThat(scan.get("capped")).isEqualTo(false);
        assertThat(scan.get("request_count")).as("ana sayfanın özeti: HTML + stil + betik + 2 görsel + yazı tipi").isEqualTo(6);
        List<Map<String, Object>> items = get(one, "items");
        assertThat(items).as("nesne listesi yalnız ana sayfanınki")
                .noneMatch(i -> ((String) i.get("url")).endsWith("/about.png") || ((String) i.get("url")).endsWith("/c.js"));

        Map<String, Object> analysis = get(one, "analysis");
        Map<String, Object> site = get(analysis, "site");
        List<Map<String, Object>> pages = get(site, "pages");
        // Ana sayfanın bağlantı denetiminde kırık bulunan /broken (500) ve /gone (404) aday değil — bütçe harcamaz
        assertThat(pages).extracting(p -> p.get("url")).containsExactly(base + "/", base + "/about", base + "/contact.html",
                base + "/pdf-page");
        assertThat(pages).extracting(p -> p.get("status")).containsExactly("ok", "ok", "ok", "failed");
        assertThat(pages.get(0).get("grade")).isEqualTo(scan.get("grade"));
        assertThat(pages.get(1).get("grade")).isNotNull();
        assertThat(pages.get(1).get("letter")).isNotNull();
        assertThat(pages.get(1).get("requests")).as("HTML + stil + betik + görsel + stilden 2 nesne").isEqualTo(6);
        Map<String, Object> scores = get(pages.get(1), "scores");
        assertThat(scores).containsKeys("security", "seo", "accessibility");
        assertThat(((Number) pages.get(1).get("unique_bytes")).longValue()).as("about: HTML + about.png")
                .isEqualTo((long) aboutHtml.length + aboutPng.length);
        assertThat(pages.get(1).get("shared_count")).as("s.css, a.js, bg.png, f.woff2").isEqualTo(4);
        assertThat(pages.get(3).get("error_code")).isEqualTo("PAGE_NOT_HTML");
        assertThat((String) pages.get(3).get("error")).isNotNull().doesNotStartWith("PAGE_");
        assertThat(((Map<String, Object>) get(scan, "progress"))).containsEntry("pages_done", 4).containsEntry("pages_total", 4);
        List<Map<String, Object>> mainItems = get(one, "items");
        assertThat(mainItems).as("kırık bağlantılar ana sayfanın sonucunda")
                .anyMatch(i -> ((String) i.get("url")).endsWith("/broken") && "broken".equals(i.get("verdict")))
                .anyMatch(i -> ((String) i.get("url")).endsWith("/gone") && "broken".equals(i.get("verdict")));

        List<Map<String, Object>> shared = get(site, "shared");
        Map<String, Object> css = shared.stream().filter(m -> ((String) m.get("url")).endsWith("/s.css")).findFirst().orElseThrow();
        assertThat(css.get("pages")).isEqualTo(3);
        assertThat(css.get("category")).isEqualTo("CSS");
        Map<String, Object> js = shared.stream().filter(m -> ((String) m.get("url")).endsWith("/a.js")).findFirst().orElseThrow();
        assertThat(js.get("pages")).isEqualTo(2);
        assertThat(shared).anyMatch(m -> ((String) m.get("url")).endsWith("/bg.png") && Integer.valueOf(3).equals(m.get("pages")));
        assertThat(shared).anyMatch(m -> ((String) m.get("url")).endsWith("/f.woff2") && Integer.valueOf(3).equals(m.get("pages")));
        Map<String, Object> totals = get(site, "totals");
        assertThat(totals).containsEntry("pages", 4).containsEntry("scanned", 3).containsEntry("failed", 1)
                .containsEntry("skipped", 0);

        // Ortak nesneler BİR kez (sayfalar arası URL önbelleği), stilden bulunanlar dahil
        for (String p : List.of("/s.css", "/a.js", "/bg.png", "/f.woff2", "/hero.png", "/about.png", "/c.js")) {
            assertThat(hits("GET " + p)).as("indirme sayısı " + p).isEqualTo(1);
        }
        assertThat(hits("GET /skip/me") + hits("HEAD /skip/me")).as("hariç tutulan").isZero();
        assertThat(hits("GET /files/r.pdf")).as("belge sayfa olarak taranmaz").isZero();
    }

    @Test
    @DisplayName("en çok sayfa: max_pages=2 → ana sayfa + ilk iç sayfa")
    void maxPagesLimits() throws Exception {
        long id = start(2, 2);
        Map<String, Object> one = awaitEnd(2, id);
        Map<String, Object> site = get(get(one, "analysis"), "site");
        List<Map<String, Object>> pages = get(site, "pages");
        assertThat(pages).extracting(p -> p.get("url")).containsExactly(base + "/", base + "/about");
        assertThat(((Map<String, Object>) get(one, "scan")).get("pages_count")).isEqualTo(2);
        assertThat(hits("GET /contact.html")).isZero();
    }

    @Test
    @DisplayName("tek süre bütçesi: süre ilk iç sayfanın ölçümünde dolar → biten sayfalar raporlanır, kalanlar 'taranmadı (süre)', özet capped")
    void timeBudgetReportsFinishedPages() throws Exception {
        when(settings.getInt(eq(PageResourceScanService.KEY_SITE_MAX_SECONDS), anyInt())).thenReturn(120);
        jumpOnAbout = true;
        long id = start(3, 10);
        Map<String, Object> one = awaitEnd(3, id);
        Map<String, Object> scan = get(one, "scan");
        assertThat(scan.get("status")).isEqualTo("DONE");
        // O-3: ana sayfa eksiksiz ölçüldü → özetin capped'i false (taranmayan iç sayfalar ana sayfayı "kısmi" yapmaz)
        assertThat(scan.get("capped")).isEqualTo(false);
        assertThat(scan.get("pages_count")).isEqualTo(2);
        Map<String, Object> site = get(get(one, "analysis"), "site");
        assertThat(site.get("incomplete")).as("site eksikliği ayrı alanda").isEqualTo(true);
        assertThat((Map<String, Object>) get(site, "totals")).containsEntry("skipped", 2);
        List<Map<String, Object>> pages = get(site, "pages");
        assertThat(pages).extracting(p -> p.get("status")).containsExactly("ok", "ok", "skipped", "skipped");
        assertThat(pages.get(0).get("capped")).isEqualTo(false);
        assertThat(pages.get(2).get("capped")).isEqualTo(true);
        assertThat(pages.get(2).get("error_code")).isEqualTo("NOT_SCANNED");
        assertThat(pages.get(2).get("error")).isNotNull();
        assertThat(hits("GET /contact.html")).isZero();

        // O-3: bütçe ana sayfanın kendi kapsamına bakar — sınır içindeki değerler "ok" (capped yüzünden "bilinmiyor" değil)
        com.sitemonitor.service.page.scan.budget.ResourceBudgetEvaluator.Result b =
                com.sitemonitor.service.page.scan.budget.ResourceBudgetEvaluator.evaluate(
                        com.sitemonitor.service.page.scan.budget.ResourceBudget.parse("{\"max_requests\":500,\"max_page_kb\":50000}"),
                        new com.sitemonitor.service.page.scan.budget.ResourceBudgetEvaluator.Facts("DONE",
                                (Boolean) scan.get("capped"), ((Number) scan.get("page_bytes")).longValue(),
                                ((Number) scan.get("request_count")).longValue(), null, null, null, null, null));
        assertThat(b.status()).isEqualTo("ok");
    }

    @Test
    @DisplayName("O2: koşu sırasında sayfa ilerlemesi DB'de — RUNNING satırında progress.pages_done / pages_total (liste + tek analiz); bitince 4/4 (kırık bağlantılar aday değil)")
    void runningSiteProgress() throws Exception {
        contactGate = new java.util.concurrent.CountDownLatch(1);
        long id;
        try {
            id = start(5, 10);
            long until = System.currentTimeMillis() + 20_000;
            Integer done = null;
            while (System.currentTimeMillis() < until) {
                done = jdbc.queryForObject("SELECT pages_done FROM page_resource_scans WHERE id = ?", Integer.class, id);
                if (done != null && done >= 2) break;
                Thread.sleep(20);
            }
            assertThat(done).as("ana sayfa + about bitti, contact bekliyor").isEqualTo(2);
            Map<String, Object> scan = get(svc.one(id, 5), "scan");
            assertThat(scan.get("status")).isEqualTo("RUNNING");
            assertThat(scan.get("scope")).isEqualTo("site");
            assertThat((Map<String, Object>) get(scan, "progress")).containsEntry("pages_done", 2).containsEntry("pages_total", 4);
            Map<String, Object> running = get(svc.list(5, 10), "running");
            assertThat((Map<String, Object>) get(running, "progress")).containsEntry("pages_done", 2)
                    .containsEntry("pages_total", 4);
        } finally {
            contactGate.countDown();
        }
        Map<String, Object> end = get(awaitEnd(5, id), "scan");
        assertThat(end.get("status")).isEqualTo("DONE");
        assertThat((Map<String, Object>) get(end, "progress")).containsEntry("pages_done", 4).containsEntry("pages_total", 4);
    }

    @Test
    @DisplayName("D-5: site süre bütçesi en çok 900 sn — 1800 / 60 gibi aralık dışı değer 900'e döner, 600 kalır")
    void siteMaxSecondsAtMost900() {
        when(settings.getInt(eq(PageResourceScanService.KEY_SITE_MAX_SECONDS), anyInt())).thenReturn(1800);
        assertThat(svc.siteLimits().maxSeconds()).isEqualTo(900);
        when(settings.getInt(eq(PageResourceScanService.KEY_SITE_MAX_SECONDS), anyInt())).thenReturn(60);
        assertThat(svc.siteLimits().maxSeconds()).isEqualTo(900);
        when(settings.getInt(eq(PageResourceScanService.KEY_SITE_MAX_SECONDS), anyInt())).thenReturn(600);
        assertThat(svc.siteLimits().maxSeconds()).isEqualTo(600);
        assertThat(PageResourceScanService.SITE_MAX_SECONDS_MAX).isEqualTo(900);
        when(settings.getInt(eq(PageResourceScanService.KEY_MAX_PAGES), anyInt())).thenReturn(50);
        assertThat(svc.siteLimits().maxPages()).isEqualTo(10);
    }

    @Test
    @DisplayName("D-6: önbellekteki stil başvuruları sayı ve karakter tavanıyla sınırlı; kesilen liste baştan kısalır")
    void siteRefsCacheIsBounded() {
        java.util.function.Function<Integer, List<PageResourceDiscovery.Candidate>> refs = n -> {
            List<PageResourceDiscovery.Candidate> l = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) l.add(new PageResourceDiscovery.Candidate("https://x/" + i + ".png", "IMAGE", true, false, true));
            return l;
        };
        PageResourceScanService.SiteRun byCount = new PageResourceScanService.SiteRun(5, Long.MAX_VALUE);
        assertThat(byCount.retainRefs(refs.apply(3))).hasSize(3);
        assertThat(byCount.retainRefs(refs.apply(3))).as("tavana kalan 2").hasSize(2);
        assertThat(byCount.retainRefs(refs.apply(3))).isEmpty();
        assertThat(byCount.retainRefs(List.of())).isEmpty();

        // "https://x/0.png" = 15 karakter → 40 karakterlik tavana 2 adres sığar
        PageResourceScanService.SiteRun byChars = new PageResourceScanService.SiteRun(1_000, 40);
        List<PageResourceDiscovery.Candidate> kept = byChars.retainRefs(refs.apply(5));
        assertThat(kept).extracting(PageResourceDiscovery.Candidate::url).containsExactly("https://x/0.png", "https://x/1.png");
        assertThat(byChars.retainRefs(refs.apply(1))).isEmpty();

        PageResourceScanService.SiteRun defaults = new PageResourceScanService.SiteRun(1500);
        assertThat(defaults.refsCap).isEqualTo(15_000);
        assertThat(defaults.charsCap).isEqualTo(PageResourceScanService.SITE_REFS_MAX_CHARS);
    }

    @Test
    @DisplayName("tek sayfa analizi değişmez: scope='page', pages_count yok, analizde site bölümü yok; liste sınırları sayfa ayarlarını taşır")
    void pageScopeUnchanged() throws Exception {
        long id = start(4, null);
        Map<String, Object> one = awaitEnd(4, id);
        Map<String, Object> scan = get(one, "scan");
        assertThat((Map<String, Object>) get(scan, "progress")).doesNotContainKeys("pages_done", "pages_total");
        assertThat(scan.get("scope")).isEqualTo("page");
        assertThat(scan.get("pages_count")).isNull();
        assertThat((Map<String, Object>) get(one, "analysis")).doesNotContainKey("site");
        assertThat(hits("GET /contact.html")).isZero();
        Map<String, Object> limits = get(svc.list(4, 10), "limits");
        assertThat(limits).containsEntry("max_pages", 10).containsEntry("site_max_seconds", 900);
    }

    @Test
    @DisplayName("sayfa hatası kuralı: engel, aktarım, HTTP, boş gövde, HTML olmayan içerik, başka host'a yönlendirme")
    void pageErrorRules() {
        PageFetchCore.Meta html = new PageFetchCore.Meta(base + "/x", 0, "text/html; charset=utf-8", null, null, null, null,
                null, null);
        byte[] b = "<html></html>".getBytes(StandardCharsets.UTF_8);
        assertThat(PageResourceScanService.pageError(new PageFetchCore.Fetch(0, 0, 0, 0, null, "x", true, false), html,
                "127.0.0.1")).isEqualTo("PAGE_BLOCKED");
        assertThat(PageResourceScanService.pageError(new PageFetchCore.Fetch(0, 0, 0, 0, null, "x", false, false), html,
                "127.0.0.1")).isEqualTo("PAGE_FAILED:TRANSPORT_ERROR");
        assertThat(PageResourceScanService.pageError(new PageFetchCore.Fetch(404, 0, 0, 3, b, null, false, false), html,
                "127.0.0.1")).isEqualTo("PAGE_HTTP:404");
        assertThat(PageResourceScanService.pageError(new PageFetchCore.Fetch(200, 0, 0, 0, new byte[0], null, false, false), html,
                "127.0.0.1")).isEqualTo("PAGE_EMPTY");
        PageFetchCore.Meta pdf = new PageFetchCore.Meta(base + "/x", 0, "application/pdf", null, null, null, null, null, null);
        assertThat(PageResourceScanService.pageError(new PageFetchCore.Fetch(200, 0, 0, 3, b, null, false, false), pdf,
                "127.0.0.1")).isEqualTo("PAGE_NOT_HTML");
        PageFetchCore.Meta off = new PageFetchCore.Meta("https://sso.example.test/login", 1, "text/html", null, null, null,
                null, null, null);
        assertThat(PageResourceScanService.pageError(new PageFetchCore.Fetch(200, 0, 0, 3, b, null, false, false), off,
                "127.0.0.1")).isEqualTo("PAGE_OFFSITE");
        PageFetchCore.Meta xhtml = new PageFetchCore.Meta(base + "/x", 0, "application/xhtml+xml", null, null, null, null,
                null, null);
        assertThat(PageResourceScanService.pageError(new PageFetchCore.Fetch(200, 0, 0, 3, b, null, false, false), xhtml,
                "127.0.0.1")).isNull();
        assertThat(PageResourceScanService.pageError(new PageFetchCore.Fetch(200, 0, 0, 3, b, null, false, false),
                PageFetchCore.Meta.NONE, "127.0.0.1")).as("içerik türü / son adres bilinmiyor → analiz edilir").isNull();
    }

    @Test
    @DisplayName("okuma: sayfa satırının hata KODU isteğin diline çevrilir (metin + kod + neden), bozuk bölüm dokunulmadan döner")
    void localizeSite() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("error", "PAGE_FAILED:DNS_FAILURE");
        Map<String, Object> a = new java.util.LinkedHashMap<>();
        a.put("site", new java.util.LinkedHashMap<>(Map.of("pages", new java.util.ArrayList<>(List.of(p)))));
        PageResourceScanService.localizeSite(a);
        assertThat(p.get("error_code")).isEqualTo("PAGE_FAILED");
        assertThat(p.get("error_reason")).isEqualTo("DNS_FAILURE");
        assertThat((String) p.get("error")).doesNotContain("PAGE_FAILED");
        Map<String, Object> bad = new java.util.LinkedHashMap<>(Map.of("site", "x"));
        assertThat(PageResourceScanService.localizeSite(bad)).isSameAs(bad);
        assertThat(PageResourceScanService.localizeSite(null)).isNull();
    }
}
