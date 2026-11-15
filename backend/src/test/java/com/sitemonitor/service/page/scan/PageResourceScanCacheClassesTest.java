package com.sitemonitor.service.page.scan;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.page.PageFetchCore;
import com.sun.net.httpserver.Headers;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Algoritma 4 uçtan uca (2026-11-15) — GERÇEK SQL (H2) + GERÇEK çekim çekirdeği + yerel HTTP sunucusu, her nesne ayrı önbellek
 * başlığıyla: sınıf nesne satırına ({@code cache_class}) yazılır, başlıklar (Pragma dahil) kaydedilir, sınıf başına öneri,
 * {@code waste.caching.classes}, önbellek puanı ve ikinci ziyaret tahmini. İki ayrı {@code Cache-Control} satırındaki
 * {@code no-store} kaybolmaz.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PageResourceScanCacheClassesTest {

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    HttpServer server;
    String base;
    PageResourceScanService svc;

    static final String LAST_MODIFIED = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().minusDays(40));
    static final String PAST = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().minusDays(1));

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM page_resource_scans");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String html = """
                <!doctype html><html><head><title>Önbellek</title>
                  <link rel="stylesheet" href="/c/long.css"><link rel="stylesheet" href="/c/short.css">
                  <script src="/j/nostore.js" defer></script><script src="/j/two-lines.js" defer></script>
                  <script src="/j/revalidate.js" defer></script><script src="/j/pragma.js" defer></script>
                </head><body>
                  <img src="/i/heuristic.png" width="20" height="20" alt="a">
                  <img src="/i/expired.png" width="20" height="20" alt="b">
                  <img src="/i/none.png" width="20" height="20" alt="c">
                  <img src="/i/etag-only.png" width="20" height="20" alt="d">
                </body></html>
                """;
        byte[] png = PageResourceScanServiceTest.png(20, 20);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            Headers h = ex.getResponseHeaders();
            byte[] body;
            String ct;
            int status = 200;
            switch (path) {
                case "/" -> { body = html.getBytes(StandardCharsets.UTF_8); ct = "text/html; charset=utf-8"; h.add("Cache-Control", "no-cache"); }
                case "/c/long.css" -> { body = "a{}".repeat(400).getBytes(); ct = "text/css"; h.add("Cache-Control", "public, max-age=31536000, immutable"); }
                case "/c/short.css" -> {
                    body = "b{}".repeat(400).getBytes(); ct = "text/css";
                    h.add("Cache-Control", "max-age=300");
                    h.add("ETag", "\"s1\"");
                }
                case "/j/nostore.js" -> { body = "var a=1;".repeat(200).getBytes(); ct = "application/javascript"; h.add("Cache-Control", "no-store"); }
                case "/j/two-lines.js" -> {
                    body = "var b=2;".repeat(200).getBytes(); ct = "application/javascript";
                    h.add("Cache-Control", "public, max-age=86400");
                    h.add("Cache-Control", "no-store");                      // ikinci satır: kaybolmamalı
                }
                case "/j/revalidate.js" -> {
                    body = "var c=3;".repeat(200).getBytes(); ct = "application/javascript";
                    h.add("Cache-Control", "no-cache");
                    h.add("ETag", "\"r1\"");
                }
                case "/j/pragma.js" -> {
                    body = "var d=4;".repeat(200).getBytes(); ct = "application/javascript";
                    h.add("Cache-Control", "public, max-age=86400");    // Chromium: Pragma: no-cache ömrü yine geçersiz kılar
                    h.add("Pragma", "no-cache");
                    h.add("Last-Modified", LAST_MODIFIED);
                }
                case "/i/heuristic.png" -> { body = png; ct = "image/png"; h.add("Last-Modified", LAST_MODIFIED); }
                case "/i/expired.png" -> { body = png; ct = "image/png"; h.add("Expires", PAST); }
                case "/i/none.png" -> { body = png; ct = "image/png"; }
                case "/i/etag-only.png" -> { body = png; ct = "image/png"; h.add("ETag", "W/\"e1\""); }
                default -> { body = "yok".getBytes(); status = 404; ct = "text/plain"; }
            }
            h.add("Content-Type", ct);
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

    static Map<String, Object> item(List<Map<String, Object>> items, String end) {
        return items.stream().filter(i -> ((String) i.get("url")).endsWith(end)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> run() throws Exception {
        PageMonitor m = new PageMonitor();
        m.setId(77L);
        m.setTeamId(7L);
        m.setName("Önbellek");
        m.setUrl(base + "/");
        PageResourceScanService.Start st = svc.start(m, "u:77", "Test");
        long id = ((Number) st.scan().get("id")).longValue();
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            Map<String, Object> one = svc.one(id, 77);
            if (!"RUNNING".equals(((Map<String, Object>) one.get("scan")).get("status"))) return one;
            Thread.sleep(50);
        }
        throw new AssertionError("tarama bitmedi");
    }

    @Test
    @DisplayName("uçtan uca: her nesnenin sınıfı, kaydedilen başlıklar, sınıf başına öneri, sınıf sayıları, puan, ikinci ziyaret")
    void cacheClassesEndToEnd() throws Exception {
        Map<String, Object> one = run();
        Map<String, Object> scan = get(one, "scan");
        assertThat(scan.get("status")).isEqualTo("DONE");
        assertThat(scan.get("algorithm_version")).isEqualTo(4);
        List<Map<String, Object>> items = get(one, "items");

        assertThat(item(items, "/c/long.css")).containsEntry("cache_class", "stored");
        assertThat(((Number) item(items, "/c/long.css").get("cache_ttl_s")).longValue()).isEqualTo(31_536_000L);
        assertThat(item(items, "/c/short.css")).containsEntry("cache_class", "short");
        assertThat(((Number) item(items, "/c/short.css").get("cache_ttl_s")).longValue()).isEqualTo(300L);
        assertThat(item(items, "/j/nostore.js")).containsEntry("cache_class", "no_store");
        Map<String, Object> twoLines = item(items, "/j/two-lines.js");
        assertThat(twoLines).as("ikinci Cache-Control satırındaki no-store").containsEntry("cache_class", "no_store");
        assertThat((String) get(get(twoLines, "headers"), "cache_control")).contains("max-age=86400").contains("no-store");
        assertThat(item(items, "/j/revalidate.js")).containsEntry("cache_class", "revalidate");
        Map<String, Object> pragma = item(items, "/j/pragma.js");
        assertThat(pragma).as("Cache-Control: max-age=86400 olsa da Pragma: no-cache + Last-Modified (Chromium kuralı)")
                .containsEntry("cache_class", "revalidate");
        assertThat((Map<String, Object>) get(pragma, "headers")).containsEntry("pragma", "no-cache");
        Map<String, Object> heuristic = item(items, "/i/heuristic.png");
        assertThat(heuristic).containsEntry("cache_class", "heuristic").containsEntry("cache_ttl_s", null);
        assertThat(((Number) heuristic.get("cache_heuristic_s")).longValue()).isBetween(39L * 86_400 / 10, 41L * 86_400 / 10);
        assertThat(item(items, "/i/expired.png")).as("süresi geçmiş Expires, doğrulayıcı yok").containsEntry("cache_class", "none");
        assertThat(item(items, "/i/none.png")).containsEntry("cache_class", "none").doesNotContainKey("cache_heuristic_s");
        assertThat(item(items, "/i/etag-only.png")).as("ömür yok, yalnız ETag").containsEntry("cache_class", "revalidate");
        assertThat(item(items, "/i/heuristic.png").get("findings")).isEqualTo(List.of("CACHE_HEURISTIC"));

        Map<String, Object> analysis = get(one, "analysis");
        List<Map<String, Object>> recs = get(analysis, "recommendations");
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : recs) {
            String code = (String) r.get("code");
            if (code.startsWith("CACHE_")) counts.put(code, ((Number) r.get("item_count")).intValue());
        }
        assertThat(counts).isEqualTo(Map.of("CACHE_NO_STORE", 2, "CACHE_NONE", 2, "CACHE_SHORT", 1,
                "CACHE_REVALIDATE", 3, "CACHE_HEURISTIC", 1));
        assertThat(recs).extracting(r -> r.get("code")).doesNotContain("NO_CACHE");

        Map<String, Object> caching = get(get(analysis, "waste"), "caching");
        assertThat((Map<String, Object>) get(caching, "classes")).isEqualTo(Map.of("stored", 1, "short", 1, "revalidate", 3,
                "heuristic", 1, "none", 2, "no_store", 2));
        // ikinci ziyaret istekleri: 11 sayfa nesnesinden long.css (saklanan) ve heuristic.png (tahmini ömür 4 gün ≥ 1 sa) hariç 9
        // — HTML (no-cache, doğrulayıcısız) dahil
        assertThat(caching.get("repeat_visit_requests")).isEqualTo(9);

        List<Map<String, Object>> scores = get(analysis, "scores");
        Map<String, Object> cachingScore = scores.stream().filter(s -> "caching".equals(s.get("key"))).findFirst().orElseThrow();
        // 10 durağan nesne: long 1 + short(ETag) 0,5 + revalidate 0,5 + pragma 0,5 + heuristic 0,5 + etag-only 0,5 = 3,5 / 10
        assertThat(cachingScore.get("score")).isEqualTo(35);
    }
}
