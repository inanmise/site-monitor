package com.certmonitor.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * PageCheckerService — YEREL HTTP sunucusuyla (dış gerçek siteye bağımlılık YOK) uçtan uca kontrol motoru
 * testleri: sağlam sayfa (OK), kırık kaynak (DEGRADED + PageResourceIssue), erişilemez ana sayfa (DOWN),
 * redirect zinciri, HEAD→405→GET düşüşü, hariç-tutma desenleri, SSRF (metadata/link-local kaynak engellenir),
 * SITE_CRAWL derinlik/limit + robots.txt.
 */
class PageCheckerServiceTest {

    private HttpServer server;
    private String base;
    private PageCheckerService checker;

    @BeforeEach
    void setUp() throws IOException {
        // SsrfGuard: loopback + internal İZİNLİ (test sunucusu 127.0.0.1'de) → ama metadata/link-local hep bloklu.
        AppSettingsService settings = mock(AppSettingsService.class);
        lenient().when(settings.getBoolean(eq("cert.monitor.monitoring.allow-internal-targets"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        lenient().when(settings.getBoolean(eq("cert.monitor.monitoring.allow-loopback-targets"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        lenient().when(settings.getBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        SsrfGuard guard = new SsrfGuard(settings);
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();   // PSL kuralları (registrableDomain — 1./3.-taraf + crawl kapsamı)

        checker = new PageCheckerService(guard, psl);
        checker.init();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        wireHandlers();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (checker != null) checker.shutdown();
    }

    // ── Testler ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sağlam sayfa: tüm kaynaklar 200 → OK, sorun yok")
    void allGood_ok() {
        var r = checker.check(base + "/ok", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.mainReachable()).isTrue();
        assertThat(r.brokenResources()).isZero();
        assertThat(r.issues()).isEmpty();
        assertThat(r.totalResources()).isGreaterThanOrEqualTo(3);   // img + css + js
        assertThat(r.contentHash()).isNotBlank();
    }

    @Test
    @DisplayName("Kırık img (404) → DEGRADED + BROKEN PageResourceIssue (birinci-taraf)")
    void brokenImg_degraded() {
        var r = checker.check(base + "/broken", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("DEGRADED");
        assertThat(r.brokenResources()).isGreaterThanOrEqualTo(1);
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.issueType()).isEqualTo("BROKEN");
            assertThat(i.resourceUrl()).contains("/missing.png");
            assertThat(i.firstParty()).isTrue();
            assertThat(i.httpStatus()).isEqualTo(404);
        });
    }

    @Test
    @DisplayName("Erişilemez ana sayfa (500) → DOWN, mainReachable=false")
    void mainError_down() {
        var r = checker.check(base + "/down500", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("DOWN");
        assertThat(r.mainReachable()).isFalse();
    }

    @Test
    @DisplayName("Redirect zinciri (302 → /ok) izlenir → OK")
    void redirectChain_followed() {
        var r = checker.check(base + "/redirect", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.mainReachable()).isTrue();
    }

    @Test
    @DisplayName("HEAD desteklemeyen kaynak (405) → GET'e düşer, kaynak sağlıklı sayılır")
    void headUnsupported_fallsToGet() {
        var r = checker.check(base + "/headonly", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.issues()).isEmpty();
    }

    @Test
    @DisplayName("Hariç-tutma deseni eşleşen kaynak atlanır → kırık sayılmaz")
    void excludePattern_skipsResource() {
        var r = checker.check(base + "/excludable", "SINGLE_PAGE", 5000, 2000, 5, "/ads/", 2, 50, 60);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.issues()).isEmpty();
    }

    @Test
    @DisplayName("SSRF: link-local/metadata kaynağa istek engellenir → BROKEN (dış istek atılmaz)")
    void ssrfResource_blocked() {
        var r = checker.check(base + "/ssrf", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("DEGRADED");
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.issueType()).isEqualTo("BROKEN");
            assertThat(i.resourceUrl()).contains("169.254.169.254");
        });
    }

    @Test
    @DisplayName("SITE_CRAWL: derinlik/limit içinde site içi linkler taranır; robots.txt Disallow yolu atlanır")
    void crawl_respectsDepthAndRobots() {
        var r = checker.check(base + "/site", "SITE_CRAWL", 5000, 2000, 5, null, 1, 10, 60);
        // Kök + /site/a taranır (broken img'leri sorun üretir); /site/b robots.txt ile Disallow → taranmaz.
        assertThat(r.pagesCrawled()).isGreaterThanOrEqualTo(2);
        assertThat(r.issues()).anySatisfy(i -> assertThat(i.sourcePage()).contains("/site/a"));
        assertThat(r.issues()).noneSatisfy(i -> assertThat(i.sourcePage()).contains("/site/b"));
    }

    @Test
    @DisplayName("H4: farklı kayıtlı-domain'deki kaynak ÜÇÜNCÜ-taraf işaretlenir (sameSite/PSL)")
    void thirdPartyResource_classified() {
        // .invalid TLD asla çözülmez → SSRF 'çözümlenemeyen host' → BROKEN; host farklı registrable domain → 3.-taraf.
        var r = checker.check(base + "/thirdparty", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("DEGRADED");
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.resourceUrl()).contains("nonexistent.invalid");
            assertThat(i.firstParty()).isFalse();   // 127.0.0.1 ile aynı-site DEĞİL
        });
    }

    @Test
    @DisplayName("E19: yanıt vermeyen (hung) hedef per-request timeout ile TEMİZ sonlanır (asılı kalmaz)")
    void hungTarget_timesOutCleanly() {
        long t0 = System.currentTimeMillis();
        var r = checker.check(base + "/hang", "SINGLE_PAGE", 1500, 2000, 5, null, 2, 50, 60);
        long dt = System.currentTimeMillis() - t0;
        assertThat(r.status()).isEqualTo("DOWN");            // ana fetch timeout → DOWN
        assertThat(dt).isLessThan(8000);                    // ~1.5s timeout + pay; 10s'lik hang'e ASILMAZ
    }

    @Test
    @DisplayName("E20: 400-kaynaklı sayfa × 20 kontrol → kaynak cap tutar + platform-thread sızıntısı yok")
    void resourceHeavy_repeated_stableThreads() {
        java.lang.management.ThreadMXBean tb = java.lang.management.ManagementFactory.getThreadMXBean();
        int before = tb.getThreadCount();
        for (int i = 0; i < 20; i++) {
            var r = checker.check(base + "/heavy", "SINGLE_PAGE", 3000, 2000, 5, null, 2, 50, 60);
            assertThat(r.status()).isEqualTo("OK");
            assertThat(r.totalResources()).isLessThanOrEqualTo(500);   // MAX_RESOURCES_PER_CHECK cap
            assertThat(r.totalResources()).isGreaterThanOrEqualTo(300);
        }
        int after = tb.getThreadCount();
        // Sanal-thread executor → carrier (platform) thread havuzu CPU-sınırlı; getThreadCount platform thread sayar.
        assertThat(after - before).isLessThan(40);   // 8000 kaynak-isteği sonrası platform-thread stabil (sızıntı yok)
    }

    // ── Test sunucusu ─────────────────────────────────────────────────────────
    private void wireHandlers() {
        // Sağlıklı sayfa + kaynakları
        html("/ok", "<html><body><img src='/img.png'><link rel='stylesheet' href='/style.css'>"
                + "<script src='/app.js'></script></body></html>");
        ok200("/img.png"); ok200("/style.css"); ok200("/app.js");

        // Kırık img + sağlam script
        html("/broken", "<html><body><img src='/missing.png'><script src='/app.js'></script></body></html>");
        status("/missing.png", 404);

        // Ana sayfa 500
        server.createContext("/down500", ex -> respond(ex, 500, "boom"));

        // 302 → /ok
        server.createContext("/redirect", ex -> { ex.getResponseHeaders().add("Location", base + "/ok"); respond(ex, 302, ""); });

        // HEAD 405, GET 200
        html("/headonly", "<html><body><script src='/noheadres'></script></body></html>");
        server.createContext("/noheadres", ex -> {
            if ("HEAD".equals(ex.getRequestMethod())) respond(ex, 405, "");
            else respond(ex, 200, "ok");
        });

        // Hariç-tutulabilir: /ads/track.png kırık ama exclude ile atlanır
        html("/excludable", "<html><body><img src='/ads/track.png'></body></html>");
        status("/ads/track.png", 404);

        // SSRF: link-local/metadata kaynak
        html("/ssrf", "<html><body><img src='http://169.254.169.254/x.png'></body></html>");

        // Üçüncü-taraf: farklı kayıtlı-domain (asla çözülmeyen .invalid) kaynak
        html("/thirdparty", "<html><body><img src='http://sub.nonexistent.invalid/x.png'></body></html>");

        // Hung: ana sayfa yanıtı geciktirir (>timeout) → checker per-request timeout ile DOWN döner
        server.createContext("/hang", ex -> {
            try { Thread.sleep(10_000); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
            respond(ex, 200, "late");
        });

        // Ağır: 400 TEKİL kaynaklı sayfa (E20). /asset/* prefix handler hepsine 200 döner.
        StringBuilder heavy = new StringBuilder("<html><body>");
        for (int i = 0; i < 400; i++) heavy.append("<img src='/asset/").append(i).append(".png'>");
        heavy.append("</body></html>");
        html("/heavy", heavy.toString());
        server.createContext("/asset", ex -> respond(ex, 200, "x"));   // /asset/* → 200

        // Crawl: kök → /site/a, /site/b (site içi); robots.txt /site/b'yi engeller
        html("/site", "<html><body><a href='/site/a'>a</a><a href='/site/b'>b</a></body></html>");
        html("/site/a", "<html><body><img src='/site/broken-a.png'></body></html>");
        html("/site/b", "<html><body><img src='/site/broken-b.png'></body></html>");
        status("/site/broken-a.png", 404);
        status("/site/broken-b.png", 404);
        server.createContext("/robots.txt", ex -> respond(ex, 200, "User-agent: *\nDisallow: /site/b\n"));
    }

    private void html(String path, String body) {
        server.createContext(path, ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            respond(ex, 200, body);
        });
    }

    private void ok200(String path) { status(path, 200); }

    private void status(String path, int code) {
        server.createContext(path, ex -> respond(ex, code, code == 200 ? "ok" : "err"));
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(code, -1);   // gövde yok
            ex.close();
            return;
        }
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) { try (OutputStream os = ex.getResponseBody()) { os.write(b); } }
        ex.close();
    }
}
