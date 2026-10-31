package com.sitemonitor.service.page.scan;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.page.PageFetchCore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.util.Random;
import java.util.Set;
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
 * Kaynak analizi ALGORİTMA 2 (2026-11) — gerçek SQL (H2) + gerçek çekim çekirdeği + yerel HTTP sunucusu (ve yerel "vekil"):
 * izlemeyle aynı kırık kararı (403 / 999 doğrulanamadı, 404 kırık, tek yeniden deneme), bağlantı / belge HEAD-önce, indirilmeyen
 * medya, noscript pikseli, yönlendirme / son adım TTFB ayrımı, nesne başına vekil kararı (NO_PROXY), iptal (aynı pod / başka
 * pod), süre dolunca uçuştaki isteklerin kesilmesi, geçmiş penceresi = saklama ayarı, ölçülmüş görsel kazancı, nesne ek alanları.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PageResourceScanAlgorithmTest {

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    HttpServer server;
    HttpServer proxy;
    java.util.concurrent.ExecutorService serverPool;
    String base;
    int port;
    AppSettingsService settings;
    PageFetchCore core;
    PageCheckerService checker;
    PublicSuffixService psl;
    PageResourceScanService svc;
    final Map<String, AtomicInteger> gets = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> heads = new ConcurrentHashMap<>();
    final AtomicInteger proxyHits = new AtomicInteger();
    final AtomicInteger flaky = new AtomicInteger();
    volatile byte[] photo;
    volatile byte[] alpha;

    static byte[] noisePng(int w, int h, boolean withAlpha) throws IOException {
        BufferedImage img = new BufferedImage(w, h, withAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Random r = new Random(42);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = r.nextInt(0x1000000);
                img.setRGB(x, y, withAlpha ? ((x % 2 == 0 ? 0x80 : 0xFF) << 24) | rgb : rgb);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    static void respond(HttpExchange ex, int status, String ct, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", ct);
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var os = ex.getResponseBody()) { if (body.length > 0) os.write(body); }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM page_resource_scans");
        photo = noisePng(420, 420, false);
        alpha = noisePng(300, 300, true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(serverPool);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            ("HEAD".equals(ex.getRequestMethod()) ? heads : gets).computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            switch (path) {
                case "/" -> respond(ex, 200, "text/html", ("""
                        <html><head><link rel="stylesheet" href="/s.css"><script src="/waf.js"></script>
                        <script src="/flaky.js"></script></head><body>
                        <noscript><img src="/pixel.gif"></noscript>
                        <img src="/gone.png"><img src="/photo.png"><img src="/alpha.png">
                        <video controls preload="none"><source src="/v.mp4"><source src="/v.webm"></video>
                        <a href="/doc.pdf">doc</a><a href="/nohead">nohead</a><a href="/li">linkedin</a>
                        </body></html>""").getBytes(StandardCharsets.UTF_8));
                case "/s.css" -> respond(ex, 200, "text/css",
                        "/* .x{background:url(/old.png)} */ .a{background:url(/bg.png)}".getBytes(StandardCharsets.UTF_8));
                case "/bg.png" -> respond(ex, 200, "image/png", noisePng(8, 8, false));
                case "/waf.js" -> {
                    ex.getResponseHeaders().add("Set-Cookie", "session=GIZLI; HttpOnly");
                    ex.getResponseHeaders().add("Server", "AkamaiGHost");
                    ex.getResponseHeaders().add("X-Cache", "TCP_DENIED");
                    respond(ex, 403, "text/html", "denied".getBytes());
                }
                case "/flaky.js" -> {
                    if (flaky.incrementAndGet() == 1) respond(ex, 500, "text/plain", "boom".getBytes());
                    else respond(ex, 200, "application/javascript", "x".repeat(2000).getBytes());
                }
                case "/photo.png" -> respond(ex, 200, "image/png", photo);
                case "/alpha.png" -> respond(ex, 200, "image/png", alpha);
                case "/doc.pdf" -> {
                    if ("HEAD".equals(ex.getRequestMethod())) {
                        ex.getResponseHeaders().add("Content-Type", "application/pdf");
                        ex.getResponseHeaders().add("Content-Length", "5000000");
                        ex.sendResponseHeaders(200, -1);
                        ex.close();
                    } else {
                        respond(ex, 200, "application/pdf", new byte[100_000]);
                    }
                }
                case "/nohead" -> {
                    if ("HEAD".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); ex.close(); }
                    else respond(ex, 200, "text/html", "<html>ok</html>".getBytes());
                }
                case "/li" -> respond(ex, 999, "text/html", "bot".getBytes());
                case "/r0" -> {
                    sleep(250);
                    ex.getResponseHeaders().add("Location", "/r1");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                }
                case "/r1" -> respond(ex, 200, "text/html", "<html><body><img src=\"/bg.png\"></body></html>".getBytes());
                case "/hangpage" -> respond(ex, 200, "text/html",
                        "<html><script src=\"/hang.js\"></script><a href=\"/about2\">a</a></html>".getBytes());
                case "/hang.js" -> {
                    sleep(20_000);
                    respond(ex, 200, "application/javascript", "x".getBytes());
                }
                case "/proxypage" -> respond(ex, 200, "text/html",
                        ("<html><img src=\"http://localhost:" + port + "/p.png\"><img src=\"/local.png\"></html>").getBytes());
                case "/local.png", "/p.png" -> respond(ex, 200, "image/png", noisePng(4, 4, false));
                default -> respond(ex, 404, "text/plain", "missing".getBytes());
            }
        });
        server.start();
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;

        settings = mock(AppSettingsService.class);
        when(settings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(settings.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(a -> a.getArgument(1));
        core = new PageFetchCore(new SsrfGuard(settings));
        core.init();
        checker = mock(PageCheckerService.class);
        when(checker.effectiveUserAgent()).thenReturn("SiteMonitor-Test");
        when(checker.excludePredicate(org.mockito.ArgumentMatchers.any())).thenReturn(u -> false);
        psl = mock(PublicSuffixService.class);
        when(psl.sameSite(anyString(), anyString())).thenAnswer(a -> a.getArgument(0).equals(a.getArgument(1)));
        svc = new PageResourceScanService(jdbc, core, checker, psl, settings);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverPool.shutdownNow();   // asılı (20 sn) işleyiciler kesilsin
        if (proxy != null) proxy.stop(0);
        svc.shutdown();
    }

    PageMonitor monitor(long id, String path) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setTeamId(7L);
        m.setName("Ana sayfa");
        m.setUrl(base + path);
        return m;
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    Map<String, Object> awaitEnd(PageResourceScanService s, long monitorId, long scanId) throws InterruptedException {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            Map<String, Object> one = s.one(scanId, monitorId);
            Map<String, Object> scan = get(one, "scan");
            if (!"RUNNING".equals(scan.get("status"))) return one;
            Thread.sleep(50);
        }
        throw new AssertionError("tarama bitmedi");
    }

    static long id(PageResourceScanService.Start st) {
        return ((Number) st.scan().get("id")).longValue();
    }

    static Map<String, Object> byUrlEnd(List<Map<String, Object>> items, String suffix) {
        return items.stream().filter(i -> ((String) i.get("url")).endsWith(suffix)).findFirst().orElseThrow(
                () -> new AssertionError("nesne yok: " + suffix));
    }

    void awaitFreeSlots(int n, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (svc.freeSlots() < n && System.currentTimeMillis() < until) Thread.sleep(20);
        assertThat(svc.freeSlots()).as("pod yuvaları bırakıldı").isEqualTo(n);
    }

    @Test
    @DisplayName("karar izlemeyle aynı: 403 / 999 doğrulanamadı, 404 kırık; tek seferlik 500 yeniden denenir; bağlantı / belge HEAD-önce")
    void verdictsRetryAndHeadFirst() throws Exception {
        PageResourceScanService.Start st = svc.start(monitor(1, "/"), "u:1", "A");
        Map<String, Object> one = awaitEnd(svc, 1, id(st));
        Map<String, Object> scan = get(one, "scan");
        assertThat(scan.get("status")).isEqualTo("DONE");
        assertThat(scan.get("failed_count")).as("yalnız 404 kırık; 403 doğrulanamadı").isEqualTo(1);
        List<Map<String, Object>> items = get(one, "items");

        Map<String, Object> waf = byUrlEnd(items, "/waf.js");
        assertThat(waf).containsEntry("verdict", "unverified").containsEntry("status", 403);
        Map<String, Object> wafHeaders = get(waf, "headers");
        assertThat(wafHeaders.keySet()).isSubsetOf(Set.of("content_type", "content_encoding", "cache_control", "expires",
                "content_length", "server", "x_cache"));
        assertThat(wafHeaders).containsEntry("server", "AkamaiGHost").containsEntry("x_cache", "TCP_DENIED");
        assertThat(waf.toString()).doesNotContain("GIZLI");

        Map<String, Object> fl = byUrlEnd(items, "/flaky.js");
        assertThat(fl).containsEntry("verdict", "ok").containsEntry("status", 200);
        assertThat(gets.get("/flaky.js").get()).as("bir kez yeniden denendi").isEqualTo(2);

        Map<String, Object> gone = byUrlEnd(items, "/gone.png");
        assertThat(gone).containsEntry("verdict", "broken");
        assertThat((List<Object>) gone.get("findings")).contains("BROKEN_RESOURCES");

        Map<String, Object> pdf = byUrlEnd(items, "/doc.pdf");
        assertThat(pdf).containsEntry("verdict", "ok").containsEntry("size_source", "header");
        assertThat(((Number) pdf.get("bytes")).longValue()).isEqualTo(5_000_000L);
        assertThat(gets.get("/doc.pdf")).as("belge gövdesi indirilmedi").isNull();
        assertThat(heads.get("/doc.pdf").get()).isEqualTo(1);

        Map<String, Object> nohead = byUrlEnd(items, "/nohead");
        assertThat(nohead).containsEntry("verdict", "ok");
        assertThat(gets.get("/nohead").get()).as("HEAD 405 → gövdesiz GET teyidi").isEqualTo(1);

        Map<String, Object> li = byUrlEnd(items, "/li");
        assertThat(li).containsEntry("verdict", "unverified").containsEntry("status", 999);

        assertThat(gets.get("/pixel.gif")).as("noscript pikseli istenmez").isNull();
        Map<String, Object> video = byUrlEnd(items, "/v.mp4");
        assertThat(video).containsEntry("verdict", "skipped").containsEntry("page_load", false).containsEntry("bytes", 0);
        assertThat(gets.get("/v.mp4")).isNull();
        assertThat(items).noneMatch(i -> ((String) i.get("url")).endsWith("/v.webm"));
        assertThat(gets.get("/old.png")).as("CSS yorumundaki url() istenmez").isNull();

        Map<String, Object> bg = byUrlEnd(items, "/bg.png");
        assertThat(bg).containsEntry("found_in", base + "/s.css").containsEntry("host", "127.0.0.1").containsEntry("via", "direct");
        assertThat(byUrlEnd(items, "/gone.png")).containsEntry("found_in", "page");
        assertThat(items.get(0)).containsEntry("found_in", null).containsEntry("final_url", null);

        Map<String, Object> analysis = get(one, "analysis");
        assertThat(analysis.get("algorithm_version")).isEqualTo(2);
        assertThat(analysis.get("not_loaded_count")).isEqualTo(1);
        List<Map<String, Object>> recs = get(analysis, "recommendations");
        assertThat(recs).extracting(m -> m.get("code")).doesNotContain("BROKEN_LINKS");
        Map<String, Object> broken = recs.stream().filter(m -> "BROKEN_RESOURCES".equals(m.get("code"))).findFirst().orElseThrow();
        assertThat(broken.get("item_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("yönlendirme: TTFB yalnız son adım; redirect_count / redirect_ms ayrı; özet algoritma 2 + pod + iptal edilmedi")
    void redirectSplitAndSummary() throws Exception {
        PageResourceScanService.Start st = svc.start(monitor(2, "/r0"), "u:2", "B");
        Map<String, Object> one = awaitEnd(svc, 2, id(st));
        Map<String, Object> scan = get(one, "scan");
        assertThat(scan.get("status")).isEqualTo("DONE");
        assertThat(scan.get("redirect_count")).isEqualTo(1);
        assertThat((Long) scan.get("redirect_ms")).isGreaterThanOrEqualTo(250L);
        assertThat((Long) scan.get("ttfb_ms")).as("son adım hızlı").isLessThan(250L).isEqualTo(scan.get("html_ttfb_ms"));
        assertThat(scan.get("algorithm_version")).isEqualTo(2);
        assertThat(scan.get("owner_pod")).isNotNull();
        assertThat(scan.get("cancelled")).isEqualTo(false);
        List<Map<String, Object>> items = get(one, "items");
        assertThat((String) items.get(0).get("final_url")).endsWith("/r1");
        Map<String, Object> list = svc.list(2, 10);
        Map<String, Object> row = ((List<Map<String, Object>>) list.get("items")).get(0);
        assertThat(row).containsEntry("redirect_count", 1).containsEntry("algorithm_version", 2);
    }

    @Test
    @DisplayName("eski satır (algoritma 1): yeni alanlar null / yok — sunucu uydurmaz")
    void legacyRowHasNoNewFields() {
        jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, started_ms, heartbeat_ms, "
                + "progress_done, progress_total, capped, items_kept, html_ttfb_ms, analysis_json) "
                + "VALUES (3, 'x', 'DONE', ?, 0, 0, 1, 1, FALSE, FALSE, 900, '{\"scores\":[]}')",
                PageResourceScanService.iso(System.currentTimeMillis()));
        Map<String, Object> row = ((List<Map<String, Object>>) svc.list(3, 10).get("items")).get(0);
        assertThat(row).containsEntry("algorithm_version", null).containsEntry("ttfb_ms", null)
                .containsEntry("redirect_count", null).containsEntry("redirect_ms", null).containsEntry("cancelled", false)
                .containsEntry("html_ttfb_ms", 900L);
    }

    @Test
    @DisplayName("nesne başına vekil: ana sayfa NO_PROXY'de doğrudan, başka host'taki nesne izlemenin ON tercihiyle vekilden")
    void perObjectProxyDecision() throws Exception {
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", ex -> {
            proxyHits.incrementAndGet();
            respond(ex, 200, "image/png", noisePng(4, 4, false));
        });
        proxy.start();
        ProxySettings ps = new ProxySettings();
        ReflectionTestUtils.setField(ps, "host", "127.0.0.1");
        ReflectionTestUtils.setField(ps, "port", proxy.getAddress().getPort());
        ReflectionTestUtils.setField(ps, "noProxy", "127.0.0.1");
        PageFetchCore pcore = new PageFetchCore(new SsrfGuard(settings));
        ReflectionTestUtils.setField(pcore, "proxySettings", ps);
        pcore.init();
        PageResourceScanService psvc = new PageResourceScanService(jdbc, pcore, checker, psl, settings);
        psvc.setProxyPolicy(new ProxyPolicyService(ps, null));
        try {
            PageMonitor m = monitor(4, "/proxypage");
            m.setUseProxy("ON");
            PageResourceScanService.Start st = psvc.start(m, "u:4", "C");
            assertThat(st.scan().get("via")).as("ana sayfa NO_PROXY'de").isEqualTo("direct");
            Map<String, Object> one = awaitEnd(psvc, 4, id(st));
            List<Map<String, Object>> items = get(one, "items");
            assertThat(byUrlEnd(items, "/local.png")).containsEntry("via", "direct").containsEntry("verdict", "ok");
            assertThat(byUrlEnd(items, "localhost:" + port + "/p.png")).containsEntry("via", "proxy").containsEntry("verdict", "ok");
            assertThat(proxyHits.get()).as("dış host'un nesnesi vekilden geçti").isGreaterThanOrEqualTo(1);
            assertThat(gets.get("/p.png")).as("doğrudan gitmedi").isNull();
        } finally {
            psvc.shutdown();
            pcore.shutdown();
        }
    }

    @Test
    @DisplayName("iptal: çalışan tarama INTERRUPTED / CANCELLED, uçuştaki istek kesilir, pod yuvası hemen boşalır; tekrar iptal NOT_RUNNING")
    void cancelStopsRun() throws Exception {
        PageResourceScanService.Start st = svc.start(monitor(5, "/hangpage"), "u:5", "D");
        long scanId = id(st);
        Thread.sleep(700);   // hang.js uçuşta
        assertThat(svc.freeSlots()).isEqualTo(1);
        long t0 = System.currentTimeMillis();
        PageResourceScanService.Cancel c = svc.cancel(scanId, 5);
        assertThat(c.outcome()).isEqualTo(PageResourceScanService.CancelOutcome.CANCELLED);
        assertThat(c.scan()).containsEntry("status", "INTERRUPTED").containsEntry("error_code", "CANCELLED")
                .containsEntry("cancelled", true);
        assertThat((String) c.scan().get("error")).startsWith("İptal edildi");
        awaitFreeSlots(2, 6_000);
        assertThat(System.currentTimeMillis() - t0).as("20 sn'lik istek beklenmedi").isLessThan(6_000L);
        assertThat(jdbc.queryForObject("SELECT cancel_requested FROM page_resource_scans WHERE id = ?", Boolean.class, scanId))
                .isTrue();
        Thread.sleep(300);
        Map<String, Object> scan = get(svc.one(scanId, 5), "scan");
        assertThat(scan).containsEntry("status", "INTERRUPTED").containsEntry("error_code", "CANCELLED");
        assertThat(svc.cancel(scanId, 5).outcome()).isEqualTo(PageResourceScanService.CancelOutcome.NOT_RUNNING);
        assertThat(svc.cancel(987_654L, 5).outcome()).isEqualTo(PageResourceScanService.CancelOutcome.NOT_FOUND);
        assertThat(svc.cancel(scanId, 6).outcome()).as("başka izlemenin taraması").isEqualTo(PageResourceScanService.CancelOutcome.NOT_FOUND);
    }

    @Test
    @DisplayName("çok pod: iptal başka pod'dan gelir → koşan pod kalp atışında görüp durur; satır CANCELLED kalır (DONE yazılmaz)")
    void cancelFromOtherPod() throws Exception {
        PageResourceScanService other = new PageResourceScanService(jdbc, core, checker, psl, settings);
        PageResourceScanService.Start st = svc.start(monitor(6, "/hangpage"), "u:6", "E");
        long scanId = id(st);
        Thread.sleep(700);
        assertThat(other.cancel(scanId, 6).outcome()).isEqualTo(PageResourceScanService.CancelOutcome.CANCELLED);
        awaitFreeSlots(2, 8_000);
        Map<String, Object> scan = get(svc.one(scanId, 6), "scan");
        assertThat(scan).containsEntry("status", "INTERRUPTED").containsEntry("error_code", "CANCELLED")
                .containsEntry("grade", null);
        other.shutdown();
    }

    @Test
    @DisplayName("süre dolumu: uçuştaki istek kesilir (20 sn beklenmez), tarama kısmi DONE + CAPPED_TIME")
    void deadlineCancelsInFlight() throws Exception {
        AtomicLong offset = new AtomicLong();
        svc.clock = () -> System.currentTimeMillis() + offset.get();
        PageResourceScanService.Start st = svc.start(monitor(7, "/hangpage"), "u:7", "F");
        long scanId = id(st);
        Thread.sleep(700);
        long t0 = System.currentTimeMillis();
        offset.set(30 * 60_000L);   // süre bütçesi doldu
        String status = null;
        long until = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < until) {
            status = jdbc.queryForObject("SELECT status FROM page_resource_scans WHERE id = ?", String.class, scanId);
            if (!"RUNNING".equals(status)) break;
            Thread.sleep(50);
        }
        assertThat(status).isEqualTo("DONE");
        assertThat(System.currentTimeMillis() - t0).isLessThan(8_000L);
        awaitFreeSlots(2, 6_000);
        String analysis = jdbc.queryForObject("SELECT analysis_json FROM page_resource_scans WHERE id = ?", String.class, scanId);
        assertThat(analysis).contains("CAPPED_TIME");
        assertThat(jdbc.queryForObject("SELECT capped FROM page_resource_scans WHERE id = ?", Boolean.class, scanId)).isTrue();
    }

    @Test
    @DisplayName("geçmiş penceresi saklama ayarına bağlı (taban 30 gün); limits.retention_days")
    void historyFollowsRetention() {
        long now = System.currentTimeMillis();
        for (int days : new int[]{10, 40, 400}) {
            jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, started_ms, heartbeat_ms, "
                    + "progress_done, progress_total, capped, items_kept) VALUES (8, 'x', 'DONE', ?, 0, 0, 0, 0, FALSE, FALSE)",
                    PageResourceScanService.iso(now - days * 86_400_000L));
        }
        assertThat((List<?>) svc.list(8, 200).get("items")).as("varsayılan 365 gün").hasSize(2);
        when(settings.getInt(eq(PageResourceScanService.KEY_RETENTION_DAYS), anyInt())).thenReturn(30);
        Map<String, Object> l30 = svc.list(8, 200);
        assertThat((List<?>) l30.get("items")).hasSize(1);
        assertThat(((Map<String, Object>) l30.get("limits")).get("retention_days")).isEqualTo(30);
        when(settings.getInt(eq(PageResourceScanService.KEY_RETENTION_DAYS), anyInt())).thenReturn(730);
        assertThat((List<?>) svc.list(8, 200).get("items")).hasSize(3);
        when(settings.getInt(eq(PageResourceScanService.KEY_RETENTION_DAYS), anyInt())).thenReturn(5);
        assertThat(((Map<String, Object>) svc.list(8, 200).get("limits")).get("retention_days")).as("taban").isEqualTo(30);
    }

    @Test
    @DisplayName("ölçülmüş görsel kazancı: büyük saydam olmayan PNG yeniden kodlanır (optimized_bytes < bytes); saydam PNG de ölçülür")
    void measuredImageSaving() throws Exception {
        PageResourceScanService.Start st = svc.start(monitor(9, "/"), "u:9", "G");
        Map<String, Object> one = awaitEnd(svc, 9, id(st));
        List<Map<String, Object>> items = get(one, "items");
        Map<String, Object> ph = byUrlEnd(items, "/photo.png");
        assertThat(((Number) ph.get("bytes")).longValue()).isEqualTo(photo.length);
        assertThat(ph).containsKey("optimized_bytes");
        assertThat(((Number) ph.get("optimized_bytes")).longValue()).isLessThan(photo.length);
        Map<String, Object> al = byUrlEnd(items, "/alpha.png");
        assertThat(al).containsKey("optimized_bytes");
        assertThat(((Number) al.get("optimized_bytes")).longValue()).isLessThanOrEqualTo(alpha.length);
        assertThat(byUrlEnd(items, "/bg.png")).as("100 KB altı ölçülmez").doesNotContainKey("optimized_bytes");
        Map<String, Object> img = get(get(one, "analysis"), "images");
        assertThat(img).containsEntry("saving_measured", true).containsEntry("measured_count", 2);
    }

    // ── İnceleme O-2: bellek izni İKİNCİ indirmenin gerçek başlığından ───────────────────────────────

    static byte[] pngHdr(int w, int h, int depth, int colorType, int size) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(Math.max(64, size));
        b.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        b.putInt(13).put(new byte[]{'I', 'H', 'D', 'R'}).putInt(w).putInt(h);
        b.put((byte) depth).put((byte) colorType).put((byte) 0).put((byte) 0).put((byte) 0).putInt(0);
        return b.array();
    }

    static ScanItem firstMeasure(int w, int h, long bytes, String ct) {
        ScanItem it = new ScanItem();
        it.url = "https://a.com/x.png";
        it.category = "IMAGE";
        it.contentType = ct;
        it.width = w;
        it.height = h;
        it.bytes = bytes;
        return it;
    }

    @Test
    @DisplayName("ölçüm planı: aynı biçim / boyut / bayt → başlıktan izin (16 bit RGBA 12 B/piksel); farklı boyut / bayt / biçim ya da bütçe aşımı → ölçülmez")
    void measurePlanRules() {
        byte[] small = pngHdr(1000, 1000, 16, 6, 200_000);
        PageResourceScanService.MeasurePlan ok = PageResourceScanService.measurePlan(
                firstMeasure(1000, 1000, small.length, "image/png"), small, small.length);
        assertThat(ok.measurable()).isTrue();
        assertThat(ok.memoryMb()).as("1 MP × 12 B + gövde").isEqualTo((int) ((12_000_000L + small.length + PageResourceAnalyzer.MB - 1)
                / PageResourceAnalyzer.MB));
        // ikinci indirme farklı piksel boyutunda (aynı bayt) → ölçülmez
        byte[] bigger = pngHdr(6300, 6300, 16, 6, 200_000);
        assertThat(PageResourceScanService.measurePlan(firstMeasure(1000, 1000, bigger.length, "image/png"), bigger, bigger.length)
                .skipReason()).isEqualTo("size_changed");
        // tel baytı farklı → ölçülmez
        assertThat(PageResourceScanService.measurePlan(firstMeasure(1000, 1000, small.length + 1, "image/png"), small, small.length)
                .skipReason()).isEqualTo("bytes_changed");
        // biçim farklı (ilk ölçüm JPEG) → ölçülmez
        assertThat(PageResourceScanService.measurePlan(firstMeasure(1000, 1000, small.length, "image/jpeg"), small, small.length)
                .skipReason()).isEqualTo("format_changed");
        // aynı görsel ama başlıktan hesaplanan bellek bütçeyi aşar (36 MP × 12 B ≈ 432 MB > 200 MB) → ölçülmez
        byte[] huge = pngHdr(6000, 6000, 16, 6, 300_000);
        assertThat(PageResourceScanService.measurePlan(firstMeasure(6000, 6000, huge.length, "image/png"), huge, huge.length)
                .skipReason()).isEqualTo("over_budget");
        // 8 bit RGB aynı boyutta bütçeye sığar (36 MP × 3 B ≈ 108 MB)
        byte[] rgb = pngHdr(6000, 6000, 8, 2, 300_000);
        assertThat(PageResourceScanService.measurePlan(firstMeasure(6000, 6000, rgb.length, "image/png"), rgb, rgb.length)
                .measurable()).isTrue();
        assertThat(PageResourceScanService.measurePlan(firstMeasure(1000, 1000, 10, "image/png"), new byte[0], 0)
                .measurable()).isFalse();
    }

    @Test
    @DisplayName("uçtan uca: görsel ikinci indirmede değişirse (farklı boyut) yeniden kodlanmaz — tahmin kalır")
    void secondDownloadChanged_notMeasured() throws Exception {
        AtomicInteger swaps = new AtomicInteger();
        byte[] other = noisePng(300, 300, false);
        server.createContext("/swap.png", ex -> respond(ex, 200, "image/png", swaps.incrementAndGet() == 1 ? photo : other));
        server.createContext("/swappage", ex -> respond(ex, 200, "text/html",
                "<html><body><img src=\"/swap.png\"></body></html>".getBytes(StandardCharsets.UTF_8)));
        PageResourceScanService.Start st = svc.start(monitor(10, "/swappage"), "u:10", "H");
        Map<String, Object> one = awaitEnd(svc, 10, id(st));
        Map<String, Object> sw = byUrlEnd(get(one, "items"), "/swap.png");
        assertThat(((Number) sw.get("width")).intValue()).isEqualTo(420);
        assertThat(sw).doesNotContainKey("optimized_bytes");
        assertThat(swaps.get()).as("ikinci indirme yapıldı").isEqualTo(2);
        Map<String, Object> img = get(get(one, "analysis"), "images");
        assertThat(img).containsEntry("saving_measured", false).containsEntry("measured_count", 0);
    }
}
