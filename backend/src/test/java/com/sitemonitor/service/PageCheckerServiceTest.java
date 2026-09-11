package com.sitemonitor.service;

import com.sitemonitor.service.page.PageFetchCore;
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
    /** Ağ çekirdeği artık ayrı bir bean — HttpClient/executor yaşam döngüsü ONUN üzerinde. */
    private PageFetchCore core;

    @BeforeEach
    void setUp() throws IOException {
        // SsrfGuard: loopback + internal İZİNLİ (test sunucusu 127.0.0.1'de) → ama metadata/link-local hep bloklu.
        AppSettingsService settings = mock(AppSettingsService.class);
        lenient().when(settings.getBoolean(eq("site.monitor.monitoring.allow-internal-targets"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        lenient().when(settings.getBoolean(eq("site.monitor.monitoring.allow-loopback-targets"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        lenient().when(settings.getBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        SsrfGuard guard = new SsrfGuard(settings);
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();   // PSL kuralları (registrableDomain — 1./3.-taraf + crawl kapsamı)
        // page.user-agent: config yok → DEFAULT_UA (getString fallback = 2. arg)
        lenient().when(settings.getString(anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(1));

        core = new PageFetchCore(guard);
        core.init();
        checker = new PageCheckerService(core, psl, settings);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        wireHandlers();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (core != null) core.shutdown();
    }

    // ── Testler ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Şemasız/host'suz URL → CONFIG_ERROR (DOWN DEĞİL): yapılandırma hatası kesinti alarmı üretmemeli")
    void schemalessUrl_configError_notDown() {
        var r = checker.check("www.axess.com.tr", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("CONFIG_ERROR");     // eskiden "DOWN" → sahte KRİTİK e-posta
        assertThat(r.mainReachable()).isFalse();
        assertThat(r.error()).isEqualTo(com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG);
        assertThat(r.totalResources()).isZero();              // hiç istek atılmadı
        assertThat(checker.test("https://", 3000).status()).isEqualTo("CONFIG_ERROR");   // host'suz: ad-hoc test yolu
    }

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
        assertThat(r.timeoutResources()).isZero();   // 2026-08-04: KIRIK ve ZAMAN AŞIMI ayrı sayaçlar
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
    @DisplayName("SSRF: link-local/metadata kaynağa istek engellenir → BLOCKED/Belirsiz (dış istek atılmaz, alarm yok)")
    void ssrfResource_blocked() {
        // 2026-09-10: politika reddi kaynağın KIRIK olduğunu göstermez (pod'un görüş açısı kısıtlı) — eskiden
        // BROKEN sayılıyordu ve kurum DNS'inin iç "engel" IP'sine çözdüğü çalışan linkler kırık görünüyordu.
        var r = checker.check(base + "/ssrf", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.issueType()).isEqualTo("BLOCKED");
            assertThat(i.resourceUrl()).contains("169.254.169.254");
            assertThat(i.httpStatus()).isNull();
        });
        assertThat(PageCheckerService.countsForAlarm("BLOCKED", "IMG", null)).isFalse();
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
        // Host çözülemez (TestHosts: geçersiz sözdizimi → wildcard-DNS'te bile çözülmez) → SSRF 'çözümlenemeyen
        // host'; host farklı registrable domain → 3.-taraf. 2026-09-10: 3P çözülemeyen host BLOCKED/Belirsiz
        // (filtreli kurumsal DNS'in NXDOMAIN'i kanıt değil) → sayfa DEGRADED olmaz, alarm yok.
        var r = checker.check(base + "/thirdparty", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.brokenResources()).isZero();
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.resourceUrl()).contains(TestHosts.UNRESOLVABLE);
            assertThat(i.firstParty()).isFalse();   // 127.0.0.1 ile aynı-site DEĞİL
            assertThat(i.issueType()).isEqualTo("BLOCKED");
        });
    }

    @Test
    @DisplayName("2026-09-10: BİRİNCİ-taraf çözülemeyen host hâlâ BROKEN (kendi alanımızın ölü kaydı her yerden ölü)")
    void firstPartyUnresolvable_staysBroken() {
        // Sayfa host'u 127.0.0.1 → sameSite yalnız aynı host; 'localhost' farklı → bu test için 1P'yi
        // doğrudan sınıflandırıcıyla pinliyoruz: alarm geçidi BROKEN+LINK+null → true, BLOCKED → false.
        assertThat(PageCheckerService.countsForAlarm("BROKEN", "LINK", null)).isTrue();
        assertThat(PageCheckerService.countsForAlarm("BLOCKED", "LINK", null)).isFalse();
        assertThat(SsrfGuard.isUnresolvableMessage(SsrfGuard.UNRESOLVABLE_PREFIX + "x.example.com")).isTrue();
        assertThat(SsrfGuard.isUnresolvableMessage("izin verilmeyen hedef x → 10.0.0.1 (iç)")).isFalse();
        assertThat(SsrfGuard.isUnresolvableMessage(null)).isFalse();
    }

    @Test
    @DisplayName("2026-09-10: zaman aşımına uğrayan kaynak için retry YOK — kaynak başına en çok ~2×timeout (HEAD+GET)")
    void timedOutResource_noRetry_boundedCost() {
        long t0 = System.currentTimeMillis();
        var r = checker.check(base + "/hangres", "SINGLE_PAGE", 1500, 2000, 5, null, 2, 50, 60);
        long dt = System.currentTimeMillis() - t0;
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.issueType()).isEqualTo("TIMEOUT");
            assertThat(i.resourceUrl()).endsWith("/hang");
        });
        // Eski zincir: HEAD 1.5 s + GET 1.5 s + 300 ms + HEAD 1.5 s + GET 1.5 s ≈ 6.3 s. Yeni: ≈ 3 s.
        assertThat(dt).isLessThan(5000);
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

    @Test
    @DisplayName("Mixed content YALNIZ yüklenen alt-kaynaklar için; a[href] hyperlink (LINK) HARİÇ (false-positive önleme)")
    void isMixedContent_excludesHyperlinks() {
        // https sayfada http:// yüklenen alt-kaynak → mixed content
        assertThat(PageCheckerService.isMixedContent(true, "IMG", "http://x.example/y.png")).isTrue();
        assertThat(PageCheckerService.isMixedContent(true, "CSS", "http://x.example/a.css")).isTrue();
        // a[href] hyperlink → mixed content DEĞİL (navigasyon hedefi; tarayıcı uyarı üretmez) — google.com bug'ı
        assertThat(PageCheckerService.isMixedContent(true, "LINK", "http://www.google.com.tr/intl/tr/services/")).isFalse();
        // http sayfada / https kaynakta mixed yok
        assertThat(PageCheckerService.isMixedContent(false, "IMG", "http://x.example/y.png")).isFalse();
        assertThat(PageCheckerService.isMixedContent(true, "IMG", "https://x.example/y.png")).isFalse();
    }

    @Test
    @DisplayName("HEAD 404 ama GET 200 dönen kaynak (ASP.NET/.aspx) → GET ile teyit, KIRIK sayılmaz (example gayrimenkulsatis bug'ı)")
    void headBadGetOk_notBroken() {
        var r = checker.check(base + "/headbad", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.issues()).isEmpty();
    }

    @Test
    @DisplayName("URL'de kodlanmamış BOŞLUK olan kaynak %20'ye kodlanır → yüklenir, KIRIK sayılmaz (example urune davet bug'ı)")
    void spacedResourceUrl_encodedNotBroken() {
        var r = checker.check(base + "/spaceimg", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.issues()).isEmpty();
    }

    @Test
    @DisplayName("F1: URI-illegal ASCII karakterler ( [ ] | boşluk) %XX'e kodlanır; zaten-kodlu/non-ASCII bozulmaz")
    void normalizeUrl_encodesUnsafeAscii() {
        assertThat(PageFetchCore.normalizeUrl("http://x/a b.png")).isEqualTo("http://x/a%20b.png");
        assertThat(PageFetchCore.normalizeUrl("http://x/a[b].png")).isEqualTo("http://x/a%5bb%5d.png");
        assertThat(PageFetchCore.normalizeUrl("http://x/a|b.js")).isEqualTo("http://x/a%7cb.js");
        assertThat(PageFetchCore.normalizeUrl("http://x/ok%20done.png")).isEqualTo("http://x/ok%20done.png");
        assertThat(PageFetchCore.normalizeUrl("http://x/temiz.png")).isEqualTo("http://x/temiz.png");
    }

    @Test
    @DisplayName("F2: durum sınıflandırma (404/5xx→BROKEN, 401/403/429/503/4xx→BLOCKED) + alarm geçidi")
    void classifyStatus_and_countsForAlarm() {
        assertThat(PageCheckerService.classifyStatus(404)).isEqualTo("BROKEN");
        assertThat(PageCheckerService.classifyStatus(410)).isEqualTo("BROKEN");
        assertThat(PageCheckerService.classifyStatus(500)).isEqualTo("BROKEN");
        assertThat(PageCheckerService.classifyStatus(504)).isEqualTo("BROKEN");
        assertThat(PageCheckerService.classifyStatus(503)).isEqualTo("BLOCKED");   // geçici
        assertThat(PageCheckerService.classifyStatus(403)).isEqualTo("BLOCKED");
        assertThat(PageCheckerService.classifyStatus(429)).isEqualTo("BLOCKED");
        assertThat(PageCheckerService.classifyStatus(400)).isEqualTo("BLOCKED");
        // Alarm geçidi (Q1/Q2)
        assertThat(PageCheckerService.countsForAlarm("BROKEN", "IMG", 404)).isTrue();    // alt-kaynak
        assertThat(PageCheckerService.countsForAlarm("BROKEN", "LINK", 404)).isTrue();   // link kesin-yok
        assertThat(PageCheckerService.countsForAlarm("BROKEN", "LINK", 500)).isFalse();  // dış link 5xx → alarm YOK
        assertThat(PageCheckerService.countsForAlarm("BROKEN", "LINK", null)).isTrue();  // dış link KESİN transport hatası (NXDOMAIN/refused) → alarm (2026-08-03)
        assertThat(PageCheckerService.countsForAlarm("TIMEOUT", "LINK", null)).isFalse();// dış link timeout → alarm YOK
        assertThat(PageCheckerService.countsForAlarm("TIMEOUT", "IMG", null)).isTrue();  // alt-kaynak timeout
        assertThat(PageCheckerService.countsForAlarm("BLOCKED", "IMG", 403)).isFalse();  // blocked → alarm YOK
        assertThat(PageCheckerService.countsForAlarm("SLOW", "IMG", 200)).isFalse();
        assertThat(PageCheckerService.countsForAlarm("MIXED_CONTENT", "IMG", null)).isTrue();
    }

    @Test
    @DisplayName("403 dönen kaynak → BROKEN değil BLOCKED (broken sayacına girmez → OK, alarm üretmez)")
    void forbiddenResource_classifiedBlocked() {
        var r = checker.check(base + "/forbiddenpage", "SINGLE_PAGE", 5000, 2000, 5, null, 2, 50, 60);
        assertThat(r.status()).isEqualTo("OK");            // BLOCKED broken sayacına girmez
        assertThat(r.brokenResources()).isZero();
        assertThat(r.issues()).anySatisfy(i -> assertThat(i.issueType()).isEqualTo("BLOCKED"));
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

        // Üçüncü-taraf: farklı kayıtlı-domain + çözülemeyen host (TestHosts — wildcard DNS'e dayanıklı)
        html("/thirdparty", "<html><body><img src='http://sub." + TestHosts.UNRESOLVABLE + "/x.png'></body></html>");

        // Hung alt-kaynak: sayfa sağlam, tek img /hang'e gider (timeout → retry'siz TIMEOUT)
        html("/hangres", "<html><body><img src='/hang'></body></html>");

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

        // HEAD 404 / GET 200 (ASP.NET/.aspx benzeri yanlış HEAD davranışı)
        html("/headbad", "<html><body><img src='/head404get200'></body></html>");
        server.createContext("/head404get200", ex -> {
            if ("HEAD".equals(ex.getRequestMethod())) respond(ex, 404, "");
            else respond(ex, 200, "ok");
        });
        // Kodlanmamış boşluk içeren img src (/asset zaten 200 döner; motor %20'ye kodlar)
        html("/spaceimg", "<html><body><img src='/asset/a b.png'></body></html>");
        // 403 dönen kaynak → BLOCKED (kırık değil)
        html("/forbiddenpage", "<html><body><img src='/forbidden'></body></html>");
        server.createContext("/forbidden", ex -> respond(ex, 403, "no"));

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
