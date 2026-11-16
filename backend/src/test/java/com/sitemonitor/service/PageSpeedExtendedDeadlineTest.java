package com.sitemonitor.service;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.page.PageFetchCore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Sayfa Hızı 2c genişletilmiş ölçüm (inceleme 2026-11-17, madde 10): stil dalgaları kontrolün duvar-saati bütçesini PAYLAŞIR —
 * sonraki bir dalga bütçeyi uzatamaz. Yerel sunucu: sayfa → 3 sn'lik stil (dalga 0) → 3 sn'lik stil (dalga 1) → askıda kalan
 * stil (dalga 2, 30 sn uyur). Bütçe 10 sn (ayarın tabanı). Ortak süre sınırı varken kontrol ~10 sn'de döner, ölçüm "süre
 * tavanında kesildi" işaretlenir ve askıdaki stil ölçülmüş sayılmaz. Dalga başına yeni süre sınırı (ya da dalga 2'ye hiç sınır
 * vermemek) kontrolü istek zaman aşımına (8 sn → ~14 sn) ya da daha uzuna taşırdı.
 */
class PageSpeedExtendedDeadlineTest {

    private HttpServer server;
    private ExecutorService serverPool;
    private String base;
    private PageFetchCore core;
    private PageSpeedCheckerService checker;
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        AppSettingsService settings = mock(AppSettingsService.class);
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getInt(eq("site.monitor.pagespeed.max-check-seconds"), anyInt())).thenReturn(10);
        lenient().when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        SsrfGuard guard = new SsrfGuard(settings);
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        core = new PageFetchCore(guard);
        org.springframework.test.util.ReflectionTestUtils.setField(core, "appSettings", settings);
        core.init();
        checker = new PageSpeedCheckerService(core, new com.sitemonitor.service.page.HttpPhaseProbe(guard), psl, settings,
                mock(SecretCipher.class));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Askıda kalan istek diğerlerini bekletmesin (varsayılan sunucu tek iş parçacıklı)
        serverPool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ps-deadline-test"); t.setDaemon(true); return t; });
        server.setExecutor(serverPool);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        text("/sayfa", "text/html; charset=utf-8",
                "<html><head><link rel=\"stylesheet\" href=\"/d0.css\"></head><body><img src=\"/logo.png\"></body></html>", 0);
        text("/d0.css", "text/css", "@import url(d1.css); .a{background:url(a.png)}", 3_000);
        text("/d1.css", "text/css", "@import url(d2.css); .b{background:url(b.png)}", 3_000);
        text("/d2.css", "text/css", ".c{background:url(c.png)}", 30_000);
        for (String p : new String[]{"/logo.png", "/a.png", "/b.png", "/c.png"}) text(p, "image/png", "x", 0);
        server.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null) server.stop(0);
        if (serverPool != null) {
            serverPool.shutdownNow();
            serverPool.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (core != null) core.shutdown();
    }

    @Test
    @DisplayName("Dalgalar TEK süre sınırını paylaşır: askıdaki 3. dalga kontrolü bütçenin ötesine taşıyamaz; ölçüm süre tavanında "
            + "kesildi işaretlenir, askıdaki stil ölçülmez")
    void wavesShareOneDeadline() {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(3L);
        m.setName("dalga");
        m.setUrl(base + "/sayfa");
        m.setTimeoutMs(8_000);
        m.setResourceConcurrency(4);
        m.setExtendedMeasurement(true);
        m.setExcludeTrackers(false);

        long t0 = System.nanoTime();
        var r = checker.check(m);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(r.reachable()).isTrue();
        assertThat(r.extended()).isTrue();
        // Bütçe 10 sn (+ kısa toparlanma payı); dalga başına yeni sınır ≥ 14 sn sürerdi (d2'nin 8 sn istek zaman aşımı)
        assertThat(elapsedMs).as("kontrol süresi (ms)").isLessThan(12_000L);
        assertThat(r.timeTruncated()).as("süre tavanı işareti").isTrue();
        // Dalga 0 ve 1 ölçüldü; askıdaki 3. dalga stili ölçülmüş satır değil (istek atıldı ama sonuç beklenmedi)
        assertThat(r.resources()).extracting(x -> x.url().substring(base.length()))
                .contains("/d0.css", "/d1.css", "/logo.png")
                .doesNotContain("/d2.css");
        assertThat(hits.getOrDefault("/d2.css", 0)).as("3. dalga planlandı ve istendi").isEqualTo(1);
    }

    private void text(String path, String type, String body, long delayMs) {
        server.createContext(path, ex -> {
            if (!ex.getRequestURI().getPath().equals(path)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            hits.merge(path, 1, Integer::sum);
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); ex.close(); return; }
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type);
            try {
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            } catch (IOException ignore) {
                // istemci bağlantıyı bıraktı
            } finally {
                ex.close();
            }
        });
    }
}
