package com.sitemonitor.service.page.scan;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.page.PageFetchCore;
import com.sitemonitor.service.SsrfGuard;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tüm site kaynak analizi (2026-11-15) — GERÇEK SQL (H2) + GERÇEK çekim çekirdeği + yerel HTTP sunucusu: site haritası
 * (robots.txt → dizin → gzip) ve taranan HER sayfanın bağlantıları her derinlikte kuyruğa girer; sayfa sınırında taranmayan
 * sayfalar adıyla listelenir; sürdürme önceki sayfaları devralıp kalan kuyruğu tarar; yanıt vermeyen site haritası taramayı
 * süre sınırıyla geçer.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PageResourceFullSiteScanTest {

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    HttpServer server;
    ExecutorService serverPool;
    String base;
    PageResourceScanService svc;
    AppSettingsService settings;
    final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    volatile boolean withRobots = true;
    /** robots.txt başka sunucudaki bir site haritasını da listeler (indirilmemeli). */
    volatile boolean robotsOtherHost;
    /** robots.txt YALNIZ başka sunucudaki haritayı listeler (sitenin kendi /sitemap.xml'i denenmeli). */
    volatile boolean robotsOnlyOtherHost;
    /** Doluysa site haritası isteği bu kadar ms bekletilir (sınırlı) — süre sınırı denetimi. */
    volatile long sitemapStallMs;
    final AtomicInteger userSeq = new AtomicInteger();

    static String page(String... links) {
        StringBuilder sb = new StringBuilder("<html><head><link rel=\"stylesheet\" href=\"/s.css\"></head><body>");
        for (String l : links) sb.append("<a href=\"").append(l).append("\">x</a>");
        return sb.append("</body></html>").toString();
    }

    static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(s.getBytes(StandardCharsets.UTF_8)); }
        return bos.toByteArray();
    }

    int hits(String key) {
        AtomicInteger n = hits.get(key);
        return n == null ? 0 : n.get();
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM page_resource_scans");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newFixedThreadPool(8);
        server.setExecutor(serverPool);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            hits.computeIfAbsent(ex.getRequestMethod() + " " + path, k -> new AtomicInteger()).incrementAndGet();
            String host = "http://" + ex.getRequestHeaders().getFirst("Host");
            byte[] body;
            int status = 200;
            String ct = "text/html; charset=utf-8";
            switch (path) {
                case "/" -> body = page("/a", "/b").getBytes(StandardCharsets.UTF_8);
                case "/a" -> body = page("/a/deep1", "/").getBytes(StandardCharsets.UTF_8);
                case "/a/deep1" -> body = page("/a/deep1/deeper").getBytes(StandardCharsets.UTF_8);
                case "/a/deep1/deeper", "/c", "/only-in-sitemap", "/p5", "/p6" -> body = page().getBytes(StandardCharsets.UTF_8);
                case "/b" -> body = page("/c", "/p5", "/p6").getBytes(StandardCharsets.UTF_8);
                case "/s.css" -> { body = "body{color:red}".getBytes(StandardCharsets.UTF_8); ct = "text/css"; }
                case "/robots.txt" -> {
                    if (!withRobots) { body = "no".getBytes(); status = 404; ct = "text/plain"; break; }
                    body = ("User-agent: *\n" + (robotsOtherHost || robotsOnlyOtherHost ? "Sitemap: http://other.example.invalid/sm.xml\n" : "")
                            + (robotsOnlyOtherHost ? "" : "Sitemap: " + host + "/sm-index.xml\n")).getBytes(StandardCharsets.UTF_8);
                    ct = "text/plain";
                }
                case "/sm-index.xml" -> {
                    long stall = sitemapStallMs;
                    if (stall > 0) {
                        try {
                            Thread.sleep(stall);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    body = ("<sitemapindex><sitemap><loc>" + host + "/sm1.xml.gz</loc></sitemap></sitemapindex>")
                            .getBytes(StandardCharsets.UTF_8);
                    ct = "application/xml";
                }
                case "/sm1.xml.gz" -> {
                    body = gzip("<urlset><url><loc>" + host + "/only-in-sitemap</loc></url><url><loc>" + host + "/a</loc></url>"
                            + "<url><loc>https://other.example.invalid/x</loc></url><url><loc>" + host + "/doc.pdf</loc></url></urlset>");
                    ct = "application/gzip";
                }
                default -> { body = "missing".getBytes(); status = 404; ct = "text/plain"; }
            }
            ex.getResponseHeaders().add("Content-Type", ct);
            try {
                ex.sendResponseHeaders(status, body.length);
                try (var os = ex.getResponseBody()) { os.write(body); }
            } catch (IOException ignored) {
                // istemci zaman aşımıyla bağlantıyı kesti
            }
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
        PublicSuffixService psl = mock(PublicSuffixService.class);
        when(psl.sameSite(anyString(), anyString())).thenAnswer(a -> a.getArgument(0).equals(a.getArgument(1)));
        svc = new PageResourceScanService(jdbc, core, checker, psl, settings);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        server.stop(0);
        serverPool.shutdownNow();
        serverPool.awaitTermination(5, TimeUnit.SECONDS);
        svc.shutdown();
    }

    PageMonitor monitor(long id) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setTeamId(7L);
        m.setName("Ana sayfa");
        m.setUrl(base + "/");
        return m;
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String k) {
        return (T) m.get(k);
    }

    Map<String, Object> awaitEnd(long monitorId, long scanId) throws InterruptedException {
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            String st = jdbc.queryForObject("SELECT status FROM page_resource_scans WHERE id = ?", String.class, scanId);
            if (!"RUNNING".equals(st)) return svc.one(scanId, monitorId);
            Thread.sleep(50);
        }
        throw new AssertionError("tarama bitmedi");
    }

    long startFull(long monitorId, int maxPages, Long resumeFrom) {
        PageResourceScanService.Start st = svc.start(monitor(monitorId), "u:" + userSeq.incrementAndGet(), "Ayşe",
                new PageResourceScanService.SiteOptions(maxPages, true, resumeFrom));
        assertThat(st.outcome()).isEqualTo(PageResourceScanService.Outcome.STARTED);
        return ((Number) st.scan().get("id")).longValue();
    }

    @Test
    @DisplayName("tüm site: her derinlikte bağlantılar + robots → dizin → gzip site haritası; hepsi taranır, kuyruk biter")
    void fullCrawlEveryDepthAndSitemap() throws Exception {
        long id = startFull(1, 1000, null);
        Map<String, Object> one = awaitEnd(1, id);
        Map<String, Object> scan = get(one, "scan");
        assertThat(scan.get("status")).isEqualTo("DONE");
        assertThat(scan.get("crawl")).isEqualTo("full");
        assertThat(scan.get("scope")).isEqualTo("site");
        Map<String, Object> site = get(get(one, "analysis"), "site");
        List<Map<String, Object>> pages = get(site, "pages");
        assertThat(pages).extracting(p -> p.get("url")).as("ana sayfa önce; bağlantılar, site haritası, derin sayfalar")
                .containsExactlyInAnyOrder(base + "/", base + "/a", base + "/b", base + "/only-in-sitemap", base + "/a/deep1",
                        base + "/c", base + "/p5", base + "/p6", base + "/a/deep1/deeper");
        assertThat(pages.get(0).get("url")).isEqualTo(base + "/");
        assertThat(pages).extracting(p -> p.get("status")).containsOnly("ok");
        assertThat(scan.get("pages_count")).isEqualTo(9);

        Map<String, Object> crawl = get(site, "crawl");
        assertThat(crawl.get("mode")).isEqualTo("full");
        assertThat(crawl.get("complete")).isEqualTo(true);
        assertThat(crawl.get("stop_reason")).isNull();
        assertThat(crawl).doesNotContainKey("discovery_incomplete");
        assertThat(scan.get("resumable")).as("kuyruk bitti: sürdürülecek bir şey yok").isEqualTo(false);
        assertThat((Integer) crawl.get("from_sitemap")).as("yalnız haritada olan sayfa").isEqualTo(1);
        Map<String, Object> sm = get(crawl, "sitemap");
        assertThat(sm).containsEntry("found", true).containsEntry("from_robots", true).containsEntry("files_read", 2)
                .containsEntry("added", 1).containsEntry("truncated", false);
        assertThat((List<?>) get(sm, "errors")).isEmpty();
        assertThat((List<?>) get(site, "unscanned")).isEmpty();
        assertThat(site.get("unscanned_total")).isEqualTo(0);
        assertThat(site.get("incomplete")).isEqualTo(false);
        assertThat(hits("GET /s.css")).as("ortak dosya bir kez").isEqualTo(1);
        assertThat(hits("GET /a")).as("her sayfa bir kez").isEqualTo(1);
        assertThat(hits("GET /doc.pdf")).as("belge sayfa olarak taranmaz").isZero();
        String ck = jdbc.queryForObject("SELECT site_checkpoint FROM page_resource_scans WHERE id = ?", String.class, id);
        assertThat(ck).as("kuyruk bitti: ara sürdürme noktası silinir (budama yuvası tüketmez)").isNull();
        assertThat(one.toString()).doesNotContain("done_urls").doesNotContain("site_checkpoint");
    }

    @Test
    @DisplayName("sayfa sınırı: taranmayan sayfalar adıyla + nedenle listelenir; sürdürme önceki sayfaları devralıp kalanı tarar")
    void pageCapListsUnscannedThenResumeCompletes() throws Exception {
        long first = startFull(2, 4, null);
        Map<String, Object> one = awaitEnd(2, first);
        Map<String, Object> site = get(get(one, "analysis"), "site");
        assertThat((List<?>) get(site, "pages")).hasSize(4);
        Map<String, Object> crawl = get(site, "crawl");
        assertThat(crawl.get("stop_reason")).isEqualTo("PAGES");
        assertThat(crawl.get("complete")).isEqualTo(false);
        List<Map<String, Object>> unscanned = get(site, "unscanned");
        assertThat(unscanned).isNotEmpty().allMatch(u -> "PAGES".equals(u.get("reason")));
        assertThat(site.get("unscanned_total")).isEqualTo(unscanned.size());
        assertThat(site.get("incomplete")).isEqualTo(true);
        int firstUnscanned = unscanned.size();

        long second = startFull(2, 1000, first);
        Map<String, Object> two = awaitEnd(2, second);
        Map<String, Object> site2 = get(get(two, "analysis"), "site");
        List<Map<String, Object>> pages2 = get(site2, "pages");
        assertThat(pages2).extracting(p -> p.get("url")).as("önceki 3 iç sayfa + yeni sayfalar; ana sayfa bir kez")
                .doesNotHaveDuplicates().hasSize(9);
        assertThat(pages2).filteredOn(p -> p.get("from_scan") != null).hasSize(3)
                .allMatch(p -> ((Number) p.get("from_scan")).longValue() == first);
        Map<String, Object> crawl2 = get(site2, "crawl");
        assertThat(((Number) crawl2.get("resumed_from")).longValue()).isEqualTo(first);
        assertThat(crawl2.get("prior_pages")).isEqualTo(3);
        assertThat(crawl2.get("complete")).isEqualTo(true);
        assertThat(((Map<String, Object>) get(two, "scan")).get("pages_count")).isEqualTo(9);
        assertThat(firstUnscanned).isGreaterThan(0);
        assertThat(hits("GET /a")).as("devralınan sayfa yeniden taranmaz").isEqualTo(1);

        assertThatThrownBy(() -> svc.loadResume(2, second)).isInstanceOf(PageResourceScanService.ResumeException.class)
                .extracting("code").isEqualTo("RESOURCE_SCAN_NOT_RESUMABLE");   // tamamlanan taramanın noktası yok
        String ck1 = jdbc.queryForObject("SELECT site_checkpoint FROM page_resource_scans WHERE id = ?", String.class, first);
        assertThat(ck1).as("kuyruğu kalan taramanın noktası saklanır, API'ye dönmez").contains("\"done_urls\"");
        assertThat(one.toString()).doesNotContain("done_urls").doesNotContain("site_checkpoint");
    }

    @Test
    @DisplayName("sürdürme reddi: bilinmeyen / başka izlemenin / tüm site olmayan tarama")
    void resumeRefusals() throws Exception {
        assertThatThrownBy(() -> svc.loadResume(3, 999_999)).isInstanceOf(PageResourceScanService.ResumeException.class)
                .extracting("code").isEqualTo("RESOURCE_SCAN_NOT_FOUND");
        PageResourceScanService.Start st = svc.start(monitor(3), "u:x", "Ayşe", new PageResourceScanService.SiteOptions(3));
        long siteId = ((Number) st.scan().get("id")).longValue();
        awaitEnd(3, siteId);
        assertThatThrownBy(() -> svc.loadResume(3, siteId)).isInstanceOf(PageResourceScanService.ResumeException.class)
                .extracting("code").isEqualTo("RESOURCE_SCAN_NOT_RESUMABLE");
        assertThatThrownBy(() -> svc.loadResume(4, siteId)).isInstanceOf(PageResourceScanService.ResumeException.class)
                .extracting("code").isEqualTo("RESOURCE_SCAN_NOT_FOUND");
    }

    @Test
    @DisplayName("başka sunucudaki site haritası indirilmez, sayılır; site-only taramada resumable alanı yok")
    void otherHostSitemapSkipped() throws Exception {
        robotsOtherHost = true;
        long id = startFull(7, 1000, null);
        Map<String, Object> site = get(get(awaitEnd(7, id), "analysis"), "site");
        Map<String, Object> crawl = get(site, "crawl");
        Map<String, Object> sm = get(crawl, "sitemap");
        assertThat(sm.get("other_host")).isEqualTo(1);
        assertThat((List<?>) get(sm, "errors")).as("denenmedi, hata değil").isEmpty();
        assertThat(sm.get("files_read")).as("sitenin kendi haritaları okundu").isEqualTo(2);
        assertThat(crawl.get("complete")).as("başka sunucudaki haritanın sayfaları bilinmiyor").isEqualTo(false);
        assertThat(crawl.get("discovery_incomplete")).isEqualTo(true);
        PageResourceScanService.Start st = svc.start(monitor(8), "u:y", "Ayşe", new PageResourceScanService.SiteOptions(3));
        long siteId = ((Number) st.scan().get("id")).longValue();
        Map<String, Object> scan = get(awaitEnd(8, siteId), "scan");
        assertThat(scan).doesNotContainKey("resumable");
    }

    @Test
    @DisplayName("robots yalnız başka sunucudaki haritayı listeler: sitenin kendi /sitemap.xml'i denenir; 404 hata değil, keşif eksik")
    void onlyOtherHostSitemapTriesDefault() throws Exception {
        robotsOnlyOtherHost = true;
        long id = startFull(9, 1000, null);
        Map<String, Object> site = get(get(awaitEnd(9, id), "analysis"), "site");
        Map<String, Object> crawl = get(site, "crawl");
        Map<String, Object> sm = get(crawl, "sitemap");
        assertThat(hits("GET /sitemap.xml")).as("varsayılan harita denendi").isEqualTo(1);
        assertThat(sm.get("default_seed")).isEqualTo(true);
        assertThat(sm.get("other_host")).isEqualTo(1);
        assertThat((List<?>) get(sm, "errors")).as("varsayılan haritanın 404'ü hata değil").isEmpty();
        assertThat(crawl.get("complete")).isEqualTo(false);
        assertThat(crawl.get("discovery_incomplete")).isEqualTo(true);
        assertThat(PageResourceScanService.hostOf("http://a b.example/x.xml")).as("okunamayan adres").isNull();
        assertThat(PageResourceScanService.hostOf("/rel.xml")).isNull();
        assertThat(PageResourceScanService.hostOf("ftp://a.example/x")).isNull();
        assertThat(PageResourceScanService.sameHost("https://A.Example/x.xml", "http://a.example:8080")).isTrue();
        assertThat(PageResourceScanService.sameHost("https://cdn.a.example/x.xml", "https://a.example")).isFalse();
    }

    @Test
    @DisplayName("denetim noktası budama: izleme başına en yeni 5 biten taramanınki kalır, çalışan satıra dokunulmaz")
    void checkpointsArePruned() {
        String ins = "INSERT INTO page_resource_scans (monitor_id, team_id, url, status, via, started_at, started_ms, heartbeat_ms, "
                + "owner_pod, requested_by, progress_done, progress_total, capped, items_kept, algorithm_version, scope, site_crawl, "
                + "site_checkpoint) VALUES (11, 7, 'http://x/', ?, 'direct', '2026-11-15T00:00:00', 0, 0, 'p', 'u', 0, 0, FALSE, FALSE, "
                + "3, 'site', 'full', '{\"v\":1}')";
        for (int i = 0; i < 7; i++) jdbc.update(ins, "DONE");
        jdbc.update(ins, "RUNNING");
        svc.pruneItems(11);
        List<String> rows = jdbc.queryForList("SELECT CASE WHEN site_checkpoint IS NULL THEN 'n' ELSE 'y' END || status "
                + "FROM page_resource_scans WHERE monitor_id = 11 ORDER BY id DESC", String.class);
        assertThat(rows).containsExactly("yRUNNING", "yDONE", "yDONE", "yDONE", "yDONE", "yDONE", "nDONE", "nDONE");
    }

    @Test
    @DisplayName("robots / site haritası yok: tarama bağlantılarla sürer, hata yazılmaz")
    void noSitemap() throws Exception {
        withRobots = false;
        long id = startFull(5, 1000, null);
        Map<String, Object> site = get(get(awaitEnd(5, id), "analysis"), "site");
        Map<String, Object> sm = get(get(site, "crawl"), "sitemap");
        assertThat(sm.get("found")).isEqualTo(false);
        assertThat(sm.get("from_robots")).isEqualTo(false);
        assertThat((List<?>) get(sm, "errors")).as("varsayılan /sitemap.xml'in yokluğu hata değil").isEmpty();
        assertThat((List<?>) get(site, "pages")).as("yalnız haritadaki sayfa yok").hasSize(8);
    }

    @Test
    @DisplayName("yanıt vermeyen site haritası: istek zaman aşımıyla kesilir, tarama sürer ve biter")
    void stalledSitemapIsCut() throws Exception {
        when(settings.getInt(eq(PageResourceScanService.KEY_REQUEST_TIMEOUT_MS), anyInt())).thenReturn(2_000);
        sitemapStallMs = 15_000;
        long started = System.currentTimeMillis();
        long id = startFull(6, 1000, null);
        Map<String, Object> one = awaitEnd(6, id);
        long took = System.currentTimeMillis() - started;
        Map<String, Object> site = get(get(one, "analysis"), "site");
        Map<String, Object> sm = get(get(site, "crawl"), "sitemap");
        assertThat(sm.get("found")).isEqualTo(false);
        assertThat((List<String>) get(sm, "errors")).as("robots'taki harita okunamadı").hasSize(1);
        assertThat((List<?>) get(site, "pages")).hasSize(8);
        Map<String, Object> crawl = get(site, "crawl");
        assertThat(crawl.get("stop_reason")).as("kuyruk bitti").isNull();
        assertThat(crawl.get("complete")).as("listelenen harita okunamadı: 'her sayfa tarandı' denmez").isEqualTo(false);
        assertThat(crawl.get("discovery_incomplete")).isEqualTo(true);
        assertThat(site.get("incomplete")).as("bütün sayfalar tarandı: sayfa uyarısı değil, keşif uyarısı").isEqualTo(false);
        assertThat(took).as("15 sn bekletilen harita taramayı bekletmez").isLessThan(14_000);
    }
}
