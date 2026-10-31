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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kaynak analizi servisi — GERÇEK SQL (H2, varlıktan üretilen tablo) + GERÇEK çekim çekirdeği + yerel HTTP sunucusu:
 * başlat → arka planda ölç → DONE (özet, analiz, nesneler), çalışırken ikinci başlatma, kişi sınırı, pod kapasitesi,
 * sayfa hatası → FAILED, eskiyen satır → INTERRUPTED, kapanış → INTERRUPTED, son 20 ayrıntı, izleme silme.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PageResourceScanServiceTest {

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    HttpServer server;
    String base;
    PageResourceScanService svc;
    PageCheckerService checker;
    final AtomicInteger hits = new AtomicInteger();

    static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", bos);
        return bos.toByteArray();
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM page_resource_scans");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] img = png(1200, 800);
        String html = """
                <html><head>
                  <link rel="stylesheet" href="/s.css">
                  <script src="/a.js"></script>
                </head><body>
                  <img src="/hero.png">
                  <img src="/missing.png">
                  <a href="/files/r.pdf">r</a>
                  <a href="/about">about</a>
                  <a href="/skip/me">skip</a>
                </body></html>
                """;
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            String ct;
            switch (path) {
                case "/" -> { body = html.getBytes(StandardCharsets.UTF_8); ct = "text/html; charset=utf-8"; }
                case "/s.css" -> { body = "body{background:url(/bg.png)}".getBytes(StandardCharsets.UTF_8); ct = "text/css"; }
                case "/a.js" -> { body = "x".repeat(4000).getBytes(StandardCharsets.UTF_8); ct = "application/javascript"; }
                case "/hero.png", "/bg.png" -> { body = img; ct = "image/png"; }
                case "/files/r.pdf" -> { body = new byte[3000]; ct = "application/pdf"; }
                case "/about" -> { body = "<html>about</html>".getBytes(StandardCharsets.UTF_8); ct = "text/html"; }
                case "/broken" -> { body = "nope".getBytes(); status = 500; ct = "text/plain"; }
                case "/redir-page" -> {
                    body = ("<html><head><link rel=\"stylesheet\" href=\"/old/s.css\"></head><body>"
                            + "<div style=\"background:url(/skip/inline.png)\"></div></body></html>").getBytes(StandardCharsets.UTF_8);
                    ct = "text/html";
                }
                case "/old/s.css" -> {
                    ex.getResponseHeaders().add("Location", "/v42/css/s.css");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                    return;
                }
                case "/v42/css/s.css" -> {
                    body = "a{background:url(../img/bg.png)} b{background:url(/skip/x.png)}".getBytes(StandardCharsets.UTF_8);
                    ct = "text/css";
                }
                case "/v42/img/bg.png" -> { body = img; ct = "image/png"; }
                default -> { body = "missing".getBytes(); status = 404; ct = "text/plain"; }
            }
            ex.getResponseHeaders().add("Content-Type", ct);
            if (path.endsWith(".png")) ex.getResponseHeaders().add("Cache-Control", "public, max-age=86400");
            if (path.equals("/a.js")) ex.getResponseHeaders().add("Cache-Control", "no-store");
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
        checker = mock(PageCheckerService.class);
        when(checker.effectiveUserAgent()).thenReturn("SiteMonitor-Test");
        when(checker.excludePredicate(eq("/skip/"))).thenReturn(u -> u.contains("/skip/"));
        PublicSuffixService psl = mock(PublicSuffixService.class);
        when(psl.sameSite(anyString(), anyString())).thenAnswer(a -> a.getArgument(0).equals(a.getArgument(1)));
        svc = new PageResourceScanService(jdbc, core, checker, psl, s);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        svc.shutdown();
    }

    PageMonitor monitor(long id, String path) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setTeamId(7L);
        m.setName("Ana sayfa");
        m.setUrl(base + path);
        m.setExcludePatterns("/skip/");
        return m;
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    Map<String, Object> awaitEnd(long monitorId, long scanId) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until) {
            Map<String, Object> one = svc.one(scanId, monitorId);
            Map<String, Object> scan = get(one, "scan");
            if (!"RUNNING".equals(scan.get("status"))) return one;
            Thread.sleep(50);
        }
        throw new AssertionError("tarama bitmedi");
    }

    @Test
    @DisplayName("başlat → arka planda ölç → DONE: özet, analiz, nesneler (stilden bulunan görsel, belge, bağlantı, kırık, boyut, önbellek)")
    void endToEnd() throws Exception {
        PageResourceScanService.Start st = svc.start(monitor(1, "/"), "u:1", "Ayşe Yılmaz");
        assertThat(st.outcome()).isEqualTo(PageResourceScanService.Outcome.STARTED);
        assertThat(st.scan().get("status")).isEqualTo("RUNNING");
        assertThat(st.scan().get("requested_by")).isEqualTo("Ayşe Yılmaz");
        assertThat(st.scan().get("via")).isEqualTo("direct");
        long id = ((Number) st.scan().get("id")).longValue();

        Map<String, Object> one = awaitEnd(1, id);
        Map<String, Object> scan = get(one, "scan");
        assertThat(scan.get("status")).isEqualTo("DONE");
        assertThat(scan.get("grade")).isNotNull();
        assertThat(scan.get("grade_letter")).isNotNull();
        assertThat(scan.get("request_count")).as("HTML + stil + betik + 2 görsel + stilden görsel").isEqualTo(6);
        assertThat(scan.get("document_count")).isEqualTo(1);
        assertThat(scan.get("link_count")).isEqualTo(1);
        assertThat(scan.get("failed_count")).isEqualTo(1);
        assertThat(scan.get("items_kept")).isEqualTo(true);
        assertThat(one.get("items_kept")).isEqualTo(true);

        List<Map<String, Object>> items = get(one, "items");
        assertThat(items.get(0).get("category")).isEqualTo("HTML");
        Map<String, Object> hero = items.stream().filter(i -> ((String) i.get("url")).endsWith("/hero.png")).findFirst().orElseThrow();
        assertThat(hero.get("category")).isEqualTo("IMAGE");
        assertThat(hero.get("width")).isEqualTo(1200);
        assertThat(hero.get("height")).isEqualTo(800);
        assertThat(hero.get("cache_ttl_s")).isEqualTo(86400);
        assertThat(((Number) hero.get("bytes")).longValue()).isEqualTo(png(1200, 800).length);
        assertThat(hero.get("start_ms")).isNotNull();
        Map<String, Object> bg = items.stream().filter(i -> ((String) i.get("url")).endsWith("/bg.png")).findFirst().orElseThrow();
        assertThat(bg.get("from_css")).isEqualTo(true);
        Map<String, Object> js = items.stream().filter(i -> ((String) i.get("url")).endsWith("/a.js")).findFirst().orElseThrow();
        assertThat(js.get("cache_ttl_s")).isEqualTo(-1);
        assertThat(js.get("content_type")).isEqualTo("application/javascript");
        Map<String, Object> missing = items.stream().filter(i -> ((String) i.get("url")).endsWith("/missing.png")).findFirst().orElseThrow();
        assertThat(missing.get("status")).isEqualTo(404);
        Map<String, Object> pdf = items.stream().filter(i -> ((String) i.get("url")).endsWith("/r.pdf")).findFirst().orElseThrow();
        assertThat(pdf.get("category")).isEqualTo("DOCUMENT");
        assertThat(pdf.get("page_load")).isEqualTo(false);
        assertThat(pdf.get("start_ms")).isNull();
        assertThat(items).noneMatch(i -> ((String) i.get("url")).contains("/skip/"));

        Map<String, Object> analysis = get(one, "analysis");
        assertThat((List<?>) analysis.get("scores")).hasSize(8);
        List<Map<String, Object>> notes = get(analysis, "notes");
        assertThat(notes).extracting(n -> n.get("code")).contains("JS_NOT_EXECUTED", "EXCLUDES_APPLIED");

        Map<String, Object> list = svc.list(1, 200);
        assertThat((List<?>) list.get("items")).hasSize(1);
        assertThat(list.get("running")).isNull();
        Map<String, Object> limits = get(list, "limits");
        assertThat(limits.get("detail_runs")).isEqualTo(20);
        // listede nesne / analiz yok (yalnız özet)
        assertThat(((List<Map<String, Object>>) list.get("items")).get(0)).doesNotContainKeys("analysis", "items");
    }

    @Test
    @DisplayName("sayfa HTTP 500 → FAILED, kod + dilde hata metni; hiç nesne ölçülmez")
    void pageErrorFails() throws Exception {
        PageResourceScanService.Start st = svc.start(monitor(2, "/broken"), "u:1", "A");
        long id = ((Number) st.scan().get("id")).longValue();
        Map<String, Object> scan = get(awaitEnd(2, id), "scan");
        assertThat(scan.get("status")).isEqualTo("FAILED");
        assertThat(scan.get("error_code")).isEqualTo("PAGE_HTTP");
        assertThat((String) scan.get("error")).contains("HTTP 500");
        assertThat(scan.get("html_ms")).isNotNull();
        assertThat(scan.get("grade")).isNull();
    }

    @Test
    @DisplayName("çalışan tarama varken ikinci başlatma RUNNING (aynı satır, ilerlemesiyle); pod kapasitesi 2 → üçüncü BUSY")
    void runningAndBusy() throws Exception {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO page_resource_scans (monitor_id, team_id, url, status, started_at, started_ms, heartbeat_ms, "
                + "progress_done, progress_total, capped, items_kept) VALUES (3, 7, 'x', 'RUNNING', ?, ?, ?, 2, 9, FALSE, FALSE)",
                PageResourceScanService.iso(now), now, now);
        PageResourceScanService.Start r = svc.start(monitor(3, "/"), "u:9", "B");
        assertThat(r.outcome()).isEqualTo(PageResourceScanService.Outcome.RUNNING);
        Map<String, Object> progress = get(r.scan(), "progress");
        assertThat(progress).containsEntry("done", 2).containsEntry("total", 9);

        slowPage();
        assertThat(svc.start(monitor(10, "/slowpage"), "u:10", "G").outcome()).isEqualTo(PageResourceScanService.Outcome.STARTED);
        assertThat(svc.start(monitor(11, "/slowpage"), "u:11", "G").outcome()).isEqualTo(PageResourceScanService.Outcome.STARTED);
        PageResourceScanService.Start busy = svc.start(monitor(12, "/slowpage"), "u:12", "G");
        assertThat(busy.outcome()).isEqualTo(PageResourceScanService.Outcome.BUSY);
        assertThat(busy.retryAfterSeconds()).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM page_resource_scans WHERE monitor_id = 12", Integer.class))
                .as("dolu pod satır yazmaz").isZero();
    }

    void slowPage() {
        server.createContext("/slow.js", ex -> {
            try { Thread.sleep(2_500); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
            ex.sendResponseHeaders(200, 1);
            try (var os = ex.getResponseBody()) { os.write('x'); }
        });
        server.createContext("/slowpage", ex -> {
            byte[] b = "<html><script src=\"/slow.js\"></script></html>".getBytes();
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (var os = ex.getResponseBody()) { os.write(b); }
        });
    }

    PageResourceScanService.Start startRetry(PageMonitor m, String user) throws InterruptedException {
        for (int k = 0; k < 100; k++) {
            PageResourceScanService.Start st = svc.start(m, user, "D");
            if (st.outcome() != PageResourceScanService.Outcome.BUSY) return st;
            Thread.sleep(20);   // önceki tarama bitti ama yuvasını henüz bırakmadı
        }
        throw new AssertionError("pod yuvası boşalmadı");
    }

    @Test
    @DisplayName("kişi sınırı: üç başlatmadan sonra dördüncü RATE + bekleme saniyesi")
    void rateLimit() throws Exception {
        long t0 = System.currentTimeMillis();
        svc.clock = () -> t0;
        for (int k = 0; k < 3; k++) {
            PageResourceScanService.Start st = startRetry(monitor(300 + k, "/"), "u:6");
            assertThat(st.outcome()).isEqualTo(PageResourceScanService.Outcome.STARTED);
            awaitEnd(300 + k, ((Number) st.scan().get("id")).longValue());
        }
        PageResourceScanService.Start r = svc.start(monitor(400, "/"), "u:6", "D");
        assertThat(r.outcome()).isEqualTo(PageResourceScanService.Outcome.RATE);
        assertThat(r.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("kalp atışı eskiyen RUNNING satırı okunurken INTERRUPTED (STALE) olur ve yenisi başlatılabilir")
    void staleBecomesInterrupted() {
        long old = System.currentTimeMillis() - PageResourceScanService.STALE_MS - 5_000;
        jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, started_ms, heartbeat_ms, "
                + "progress_done, progress_total, capped, items_kept) VALUES (4, 'x', 'RUNNING', ?, ?, ?, 0, 0, FALSE, FALSE)",
                PageResourceScanService.iso(old), old, old);
        Map<String, Object> list = svc.list(4, 50);
        Map<String, Object> first = ((List<Map<String, Object>>) list.get("items")).get(0);
        assertThat(first.get("status")).isEqualTo("INTERRUPTED");
        assertThat(first.get("error_code")).isEqualTo("STALE");
        assertThat(list.get("running")).isNull();
    }

    @Test
    @DisplayName("kapanış bayrağı: çalışan tarama INTERRUPTED (SHUTDOWN) yazar; kapanırken yeni başlatma BUSY")
    void shutdownInterrupts() throws Exception {
        slowPage();   // yavaş bir alt kaynak: ölçüm sürerken kapanış
        PageResourceScanService.Start st = svc.start(monitor(5, "/slowpage"), "u:1", "E");
        long id = ((Number) st.scan().get("id")).longValue();
        Thread.sleep(600);
        svc.onContextClosed(null);
        Map<String, Object> scan = get(awaitEnd(5, id), "scan");
        assertThat(scan.get("status")).isEqualTo("INTERRUPTED");
        assertThat(scan.get("error_code")).isEqualTo("SHUTDOWN");
        assertThat(svc.start(monitor(6, "/"), "u:2", "F").outcome()).isEqualTo(PageResourceScanService.Outcome.BUSY);
    }

    @Test
    @DisplayName("nesne ayrıntısı izleme başına son 20 çalıştırmada kalır; izleme silinince taramalar gider")
    void pruneAndDelete() {
        for (int k = 0; k < 23; k++) {
            jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, started_ms, heartbeat_ms, "
                    + "progress_done, progress_total, capped, items_kept, items_json) VALUES (8, 'x', 'DONE', ?, 0, 0, 0, 0, "
                    + "FALSE, TRUE, '[]')", PageResourceScanService.iso(System.currentTimeMillis()));
        }
        svc.pruneItems(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM page_resource_scans WHERE monitor_id = 8 AND items_json IS NOT NULL",
                Integer.class)).isEqualTo(20);
        Long oldest = jdbc.queryForObject("SELECT MIN(id) FROM page_resource_scans WHERE monitor_id = 8", Long.class);
        Map<String, Object> one = svc.one(oldest, 8);
        assertThat(one.get("items_kept")).isEqualTo(false);
        assertThat(one.get("items")).isNull();
        assertThat(svc.one(oldest, 9)).as("başka izlemenin taraması okunmaz").isNull();
        assertThat(svc.deleteForMonitor(8)).isEqualTo(23);
    }

    @Test
    @DisplayName("stil yönlendirmesi: url() SON adrese göre çözülür; stilden / satır içi stilden bulunanlara hariç tutma uygulanır")
    void cssFinalUrlAndExcludes() throws Exception {
        PageResourceScanService.Start st = svc.start(monitor(20, "/redir-page"), "u:20", "H");
        Map<String, Object> one = awaitEnd(20, ((Number) st.scan().get("id")).longValue());
        List<Map<String, Object>> items = get(one, "items");
        assertThat(items).extracting(i -> (String) i.get("url")).anyMatch(u -> u.endsWith("/v42/img/bg.png"))
                .noneMatch(u -> u.endsWith("/img/bg.png") && !u.contains("/v42/"))
                .noneMatch(u -> u.contains("/skip/"));
        Map<String, Object> bg = items.stream().filter(i -> ((String) i.get("url")).endsWith("/v42/img/bg.png")).findFirst().orElseThrow();
        assertThat(bg.get("status")).isEqualTo(200);
        Map<String, Object> analysis = get(one, "analysis");
        List<Map<String, Object>> notes = get(analysis, "notes");
        Map<String, Object> ex = notes.stream().filter(n -> "EXCLUDES_APPLIED".equals(n.get("code"))).findFirst().orElseThrow();
        Map<String, Object> exParams = get(ex, "params");
        assertThat(exParams.get("count")).as("satır içi + stilden").isEqualTo(2);
    }

    @Test
    @DisplayName("satır başka okuyucu tarafından kapatıldıysa (eskimiş sayıldı) tarama ölçümü bırakır ve hiçbir şey yazmaz")
    void lostRowStopsTheRun() throws Exception {
        slowPage();
        PageResourceScanService.Start st = svc.start(monitor(21, "/slowpage"), "u:21", "I");
        long id = ((Number) st.scan().get("id")).longValue();
        Thread.sleep(300);
        jdbc.update("UPDATE page_resource_scans SET status = 'INTERRUPTED', error = 'STALE' WHERE id = ?", id);
        long until = System.currentTimeMillis() + 4_000;
        // ölçüm kalp atışında kaybı görür; satır INTERRUPTED / STALE kalır (DONE yazılmaz)
        while (System.currentTimeMillis() < until) {
            Thread.sleep(200);
            Map<String, Object> scan = get(svc.one(id, 21), "scan");
            assertThat(scan.get("status")).isEqualTo("INTERRUPTED");
        }
        Map<String, Object> scan = get(svc.one(id, 21), "scan");
        assertThat(scan.get("error_code")).isEqualTo("STALE");
        assertThat(scan.get("grade")).isNull();
    }

    @Test
    @DisplayName("ulaşılamayan sayfa: metin ham kod içermez, neden ayrı alanda; başlıktaki saçma boyut kabul edilmez")
    void failedReasonAndHeaderClamp() throws Exception {
        PageMonitor m = monitor(22, "/");
        m.setUrl("http://127.0.0.1:1/");   // kapalı port → bağlantı reddi
        PageResourceScanService.Start st = svc.start(m, "u:22", "J");
        Map<String, Object> scan = get(awaitEnd(22, ((Number) st.scan().get("id")).longValue()), "scan");
        assertThat(scan.get("status")).isEqualTo("FAILED");
        assertThat(scan.get("error_code")).isEqualTo("PAGE_FAILED");
        assertThat(scan.get("error_reason")).isNotNull();
        assertThat((String) scan.get("error")).doesNotContain((String) scan.get("error_reason"));

        PageFetchCore.Fetch cut = new PageFetchCore.Fetch(200, 5, 9, 10L * 1024 * 1024, null, null, false, true);
        PageFetchCore.Meta huge = new PageFetchCore.Meta("https://a/", 0, "video/mp4", null, Long.MAX_VALUE / 2, null, null, null, null);
        ScanItem it = svc.item(new PageResourceDiscovery.Candidate("https://a/v.mp4", "MEDIA", true, true, false),
                new PageFetchCore.MetaFetch(cut, huge), "a");
        assertThat(it.bytes).isEqualTo(10L * 1024 * 1024);
        assertThat(it.truncated).isTrue();
        assertThat(it.sizeSource).isEqualTo("body");
        PageFetchCore.Meta real = new PageFetchCore.Meta("https://a/", 0, "video/mp4", null, 50L * 1024 * 1024, null, null, null, null);
        ScanItem ok = svc.item(new PageResourceDiscovery.Candidate("https://a/v.mp4", "MEDIA", true, true, false),
                new PageFetchCore.MetaFetch(cut, real), "a");
        assertThat(ok.bytes).isEqualTo(50L * 1024 * 1024);
        assertThat(ok.sizeSource).isEqualTo("header");
    }

    @Test
    @DisplayName("önbellek süresi: no-store / max-age / Expires − Date / bozuk")
    void cacheTtl() {
        assertThat(PageResourceScanService.cacheTtl("public, max-age=600", null, null, 0)).isEqualTo(600L);
        assertThat(PageResourceScanService.cacheTtl("no-cache, max-age=600", null, null, 0)).isEqualTo(-1L);
        assertThat(PageResourceScanService.cacheTtl(null, "Wed, 21 Oct 2026 07:28:00 GMT", "Wed, 21 Oct 2026 07:18:00 GMT", 0))
                .isEqualTo(600L);
        assertThat(PageResourceScanService.cacheTtl(null, "0", null, 0)).isEqualTo(0L);
        assertThat(PageResourceScanService.cacheTtl(null, null, null, 0)).isNull();
        assertThat(PageResourceScanService.cacheTtl("max-age=abc", null, null, 0)).isNull();
        assertThat(PageResourceScanService.contentType("Text/HTML; charset=UTF-8")).isEqualTo("text/html");
    }
}
