package com.sitemonitor.service.page;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kaynak analizi için çekirdek ayarları (2026-11): adım süreleri (yönlendirme ayrı, son adımın ilk baytı), tarama
 * istemcileri ({@code shutdownNow} uçuştaki isteği keser), {@code Accept-Encoding} (yalnız sayım yolunda br), açılmış gövde
 * tavanı, kesme bayrağının geri konması, sunucu / önbellek başlıkları. Ayarsız çağrı (izlemeler) bugünkü gibi.
 */
class PageFetchCoreScanTuningTest {

    HttpServer server;
    ExecutorService pool;
    String base;
    PageFetchCore core;
    final List<String> acceptEncodings = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        core = new PageFetchCore(new SsrfGuard(s));
        core.init();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        byte[] big = "a".repeat(1_000_000).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) { g.write(big); }
        byte[] gzBody = gz.toByteArray();
        server.createContext("/", ex -> {
            acceptEncodings.add(ex.getRequestHeaders().getFirst("Accept-Encoding"));
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/a" -> {
                    try { Thread.sleep(200); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
                    ex.getResponseHeaders().add("Location", "/b");
                    ex.sendResponseHeaders(301, -1);
                    ex.close();
                }
                case "/b" -> {
                    byte[] b = "ok".getBytes();
                    ex.getResponseHeaders().add("Server", "nginx");
                    ex.getResponseHeaders().add("CF-Cache-Status", "HIT");
                    ex.sendResponseHeaders(200, b.length);
                    try (var os = ex.getResponseBody()) { os.write(b); }
                }
                case "/gz" -> {
                    ex.getResponseHeaders().add("Content-Encoding", "gzip");
                    ex.sendResponseHeaders(200, gzBody.length);
                    try (var os = ex.getResponseBody()) { os.write(gzBody); }
                }
                case "/hang" -> {
                    try { Thread.sleep(15_000); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
                    ex.sendResponseHeaders(200, -1);
                    ex.close();
                }
                default -> { ex.sendResponseHeaders(404, -1); ex.close(); }
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        pool.shutdownNow();
        core.shutdown();
    }

    PageFetchCore.FetchOptions opts(boolean body, boolean count) {
        return new PageFetchCore.FetchOptions(10_000, "UA", null, body, count);
    }

    @Test
    @DisplayName("yönlendirme ayrı ölçülür: redirect_ms ≥ adım süresi, son adımın ilk baytı küçük; Fetch.ttfbMs zincirin tamamı (değişmedi)")
    void redirectTimingSplit() {
        PageFetchCore.MetaFetch mf = core.fetchWithMeta(base + "/a", "GET", opts(false, true), 0);
        assertThat(mf.fetch().status()).isEqualTo(200);
        assertThat(mf.meta().redirects()).isEqualTo(1);
        assertThat(mf.meta().redirectMs()).isGreaterThanOrEqualTo(200L);
        assertThat(mf.meta().finalTtfbMs()).isNotNull().isLessThan(200L);
        assertThat(mf.fetch().ttfbMs()).as("izlemelerin TTFB'si zincirin tamamı").isGreaterThanOrEqualTo(200L);
        assertThat(mf.meta().server()).isEqualTo("nginx");
        assertThat(mf.meta().xCache()).isEqualTo("HIT");
        assertThat(mf.meta().finalUrl()).endsWith("/b");
    }

    @Test
    @DisplayName("Accept-Encoding: ayarsız çağrı 'gzip, deflate' (izleme), sayım ayarı 'gzip, deflate, br'")
    void acceptEncoding() {
        core.fetch(base + "/b", "GET", opts(false, true));
        try (PageFetchCore.ScanClients c = core.newScanClients()) {
            core.fetchWithMeta(base + "/b", "GET", opts(false, true), 0,
                    new PageFetchCore.ScanTuning(c, PageFetchCore.ACCEPT_ENCODING_COUNT, PageFetchCore.MAX_BODY_BYTES, 1000));
        }
        assertThat(acceptEncodings).containsExactly("gzip, deflate", "gzip, deflate, br");
    }

    @Test
    @DisplayName("açılmış gövde tavanı ayardan (stil gövdesi taramanın karakter sınırı kadar açılır); ayarsız tavan değişmedi")
    void decodedCap() {
        try (PageFetchCore.ScanClients c = core.newScanClients()) {
            PageFetchCore.MetaFetch mf = core.fetchWithMeta(base + "/gz", "GET", opts(true, true), 0,
                    new PageFetchCore.ScanTuning(c, PageFetchCore.ACCEPT_ENCODING, PageFetchCore.MAX_BODY_BYTES, 1000));
            assertThat(mf.fetch().body()).hasSize(1000);
        }
        assertThat(core.fetch(base + "/gz", "GET", opts(true, false)).body()).hasSize(1_000_000);
    }

    @Test
    @DisplayName("tarama istemcisi shutdownNow uçuştaki isteği hemen keser; kesilen iş parçacığının bayrağı geri konur")
    void scanClientsAbortInFlight() throws Exception {
        PageFetchCore.ScanClients c = core.newScanClients();
        PageFetchCore.ScanTuning t = new PageFetchCore.ScanTuning(c, PageFetchCore.ACCEPT_ENCODING_COUNT,
                PageFetchCore.MAX_BODY_BYTES, 1000);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            long t0 = System.currentTimeMillis();
            Future<PageFetchCore.MetaFetch> f = exec.submit(() -> core.fetchWithMeta(base + "/hang", "GET", opts(false, true), 0, t));
            Thread.sleep(300);
            c.shutdownNow();
            PageFetchCore.MetaFetch mf = f.get(5, TimeUnit.SECONDS);
            assertThat(mf.fetch().status()).isZero();
            assertThat(System.currentTimeMillis() - t0).isLessThan(5_000L);

            // kesme: kesilmiş iş parçacığında istek atılamaz, bayrak geri konur (çağıran sonucu atabilsin)
            Future<Boolean> h = exec.submit(() -> {
                Thread.currentThread().interrupt();
                try (PageFetchCore.ScanClients c3 = core.newScanClients()) {
                    core.fetchWithMeta(base + "/b", "GET", opts(false, true), 0,
                            new PageFetchCore.ScanTuning(c3, PageFetchCore.ACCEPT_ENCODING_COUNT, PageFetchCore.MAX_BODY_BYTES, 1000));
                }
                return Thread.interrupted();
            });
            assertThat(h.get(5, TimeUnit.SECONDS)).as("kesilmiş iş parçacığı kesilmiş kalır").isTrue();
        } finally {
            exec.shutdownNow();
        }
    }
}
