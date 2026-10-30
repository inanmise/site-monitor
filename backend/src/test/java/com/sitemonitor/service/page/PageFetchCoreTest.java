package com.sitemonitor.service.page;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sayfa çekme çekirdeğinin AĞ sözleşmesi — sıkıştırma bombası koruması ve yönlendirme politikası.
 */
class PageFetchCoreTest {

    private static PageFetchCore newCore() {
        // Testler 127.0.0.1'e bağlanır → SsrfGuard izin verici (loopback + iç ağ); metadata yine bloklu.
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        PageFetchCore core = new PageFetchCore(new SsrfGuard(s));
        core.init();
        return core;
    }

    /** {@code Content-Encoding: gzip} ile sıkıştırılmış gövde döndüren sunucu. */
    private static HttpServer serveGzip(byte[] plain) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bos)) { g.write(plain); }
        byte[] gz = bos.toByteArray();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, gz.length);
            try (var os = ex.getResponseBody()) { os.write(gz); }
        });
        server.start();
        return server;
    }

    /** 200 + chunked başlık, sonra gövdeye her 100 ms'de bir bayt — istemci kesene dek (MJPEG/SSE taklidi). */
    private static HttpServer streamingServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-stream");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (var os = ex.getResponseBody()) {
                for (int i = 0; i < 600; i++) { os.write('x'); os.flush(); Thread.sleep(100); }
            } catch (Exception ignore) { /* istemci kesti */ }
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("N1: gövdesini bitirmeyen kaynak — ayrıştırma çekimi HATA ile, ağırlık ölçümü 'truncated' ile, "
            + "yoklama durum koduyla bütçede döner (iş parçacığı/soket sızmaz)")
    void stalledBody_boundedInEveryMode() throws IOException {
        HttpServer server = streamingServer();
        try {
            PageFetchCore core = newCore();
            PageFetchCore.Fetch body = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(10),
                    () -> core.fetch(url(server), "GET", PageFetchCore.FetchOptions.body(1500, "t")));
            assertThat(body.error()).contains("tamamlanmadı");

            PageFetchCore.Fetch weigh = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(10),
                    () -> core.fetch(url(server), "GET", PageFetchCore.FetchOptions.weigh(1500, "t")));
            assertThat(weigh.error()).isNull();
            assertThat(weigh.status()).isEqualTo(200);
            assertThat(weigh.truncated()).as("süre dolunca sayım 'en az bu kadar' olarak işaretlenir").isTrue();
            assertThat(weigh.bytes()).isPositive();

            PageFetchCore.Fetch probe = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(10),
                    () -> core.fetch(url(server), "GET", PageFetchCore.FetchOptions.probe(1500, "t")));
            assertThat(probe.error()).isNull();
            assertThat(probe.status()).isEqualTo(200);
        } finally {
            server.stop(0);
        }
    }

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort() + "/";
    }

    /** {@code n} benzersiz bağlantılı sayfa — izlenen büyük bir ana sayfa ~520 kaynak taşıyordu (2026-10-30). */
    private static byte[] pageWithLinks(int n) {
        StringBuilder sb = new StringBuilder("<html><body>");
        for (int i = 0; i < n; i++) sb.append("<a href=\"/p/").append(i).append("\">x</a>");
        return sb.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static PageFetchCore coreWithCap(Integer configured) {
        PageFetchCore core = newCore();
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getInt(org.mockito.ArgumentMatchers.eq(PageFetchCore.MAX_RESOURCES_KEY), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> configured == null ? i.getArgument(1) : configured);
        org.springframework.test.util.ReflectionTestUtils.setField(core, "appSettings", s);
        return core;
    }

    @Test
    @DisplayName("kaynak tavanı (2026-10-30): varsayılan 2000 — 520 kaynaklı büyük ana sayfa artık TAM envanterlenir; canlı ayar "
            + "uygulanır ve tavana gelince kırpılır")
    void resourceCap_defaultAndLiveSetting() {
        String base = "https://example.com/";
        PageFetchCore.Inventory full = coreWithCap(null).inventoryDetailed(pageWithLinks(520), base, base, null,
                PageFetchCore.InventoryOptions.FULL);
        assertThat(full.resources()).hasSize(520);
        assertThat(full.cap()).isEqualTo(2000);
        assertThat(full.capped()).isFalse();

        PageFetchCore.Inventory capped = coreWithCap(150).inventoryDetailed(pageWithLinks(520), base, base, null,
                PageFetchCore.InventoryOptions.FULL);
        assertThat(capped.resources()).hasSize(150);
        assertThat(capped.cap()).isEqualTo(150);
        assertThat(capped.capped()).isTrue();
        assertThat(coreWithCap(150).inventory(pageWithLinks(520), base, base, null)).hasSize(150);
        // Yapıcıyla kurulan (ayarsız) çekirdek de varsayılanı uygular
        assertThat(newCore().inventory(pageWithLinks(520), base, base, null)).hasSize(520);
    }

    @Test
    @DisplayName("kaynak tavanı sınırları: boş / 0 / negatif → 2000; 100'ün altı 100'e, 10000'in üstü 10000'e sıkışır; okuma hatası "
            + "varsayılan; crawl toplamı en az 1500 ve sayfa tavanının 3 katı")
    void resourceCap_clampAndCrawlTotal() {
        assertThat(PageFetchCore.maxResourcesPerCheck(null)).isEqualTo(2000);
        assertThat(coreWithCap(0).maxResourcesPerCheck()).isEqualTo(2000);
        assertThat(coreWithCap(-5).maxResourcesPerCheck()).isEqualTo(2000);
        assertThat(coreWithCap(20).maxResourcesPerCheck()).isEqualTo(100);
        assertThat(coreWithCap(750).maxResourcesPerCheck()).isEqualTo(750);
        assertThat(coreWithCap(50_000).maxResourcesPerCheck()).isEqualTo(10_000);
        AppSettingsService broken = mock(AppSettingsService.class);
        when(broken.getInt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("ayar okunamadı"));
        assertThat(PageFetchCore.maxResourcesPerCheck(broken)).isEqualTo(2000);

        assertThat(PageFetchCore.maxTotalResources(2000)).isEqualTo(6000);
        assertThat(PageFetchCore.maxTotalResources(100)).isEqualTo(1500);
        assertThat(PageFetchCore.maxTotalResources(500)).isEqualTo(1500);
    }

    @Test
    @DisplayName("gzip: normal boyutlu gövde TAM açılır (regresyon kapısı)")
    void gzipBody_decodedFully() throws IOException {
        String html = "<html><body><img src=\"/a.png\"><script src=\"/b.js\"></script></body></html>";
        HttpServer server = serveGzip(html.getBytes(StandardCharsets.UTF_8));
        try {
            PageFetchCore core = newCore();
            PageFetchCore.Fetch f = core.fetch(url(server), "GET", PageFetchCore.FetchOptions.body(3000, "t"));
            assertThat(f.status()).isEqualTo(200);
            assertThat(new String(f.body(), StandardCharsets.UTF_8)).isEqualTo(html);
            // Envanter de çalışmalı — açma sadece "patlamadı" değil, DOĞRU sonuç vermeli
            assertThat(core.inventory(f.body(), url(server), url(server), null)).hasSize(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("SIKIŞTIRMA BOMBASI: açılmış gövde tavanda kesilir — heap tüketilmez")
    void gzipBomb_cappedAtDecodedLimit() throws IOException {
        // Telden gelen gövde MAX_BODY_BYTES ile zaten sınırlıydı, AÇILMIŞ boyut değildi:
        // readAllBytes() 1000:1 oranlı bir yanıtı gigabaytlara açabiliyordu. Tek pod → OOM = kesinti.
        // 25 MB sıfır ≈ 25 KB gzip (telde tavanın çok altında, yani eski kod bunu sonuna kadar açardı).
        byte[] bomb = new byte[25 * 1024 * 1024];
        HttpServer server = serveGzip(bomb);
        try {
            PageFetchCore.Fetch f = newCore()
                    .fetch(url(server), "GET", PageFetchCore.FetchOptions.body(5000, "t"));
            assertThat(f.status()).isEqualTo(200);
            assertThat(f.body()).hasSize(PageFetchCore.MAX_DECODED_BYTES);
            assertThat(f.body().length).isLessThan(bomb.length);   // TAM açılmadı
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Tavan tel tavanının üstünde — gerçek sayfalar kırpılmaz")
    void decodedCapLeavesRoomForRealPages() {
        // Sıkıştırılmış HTML tipik olarak 5:1 açılır; tavan tel tavanının ALTINDA olsaydı
        // sıradan büyük sayfaların envanteri sessizce eksilirdi.
        assertThat(PageFetchCore.MAX_DECODED_BYTES).isGreaterThan(PageFetchCore.MAX_BODY_BYTES);
    }

    @Test
    @DisplayName("SSRF: cloud-metadata ucuna yönlendirme engellenir — gövde DÖNMEZ")
    void redirectToMetadata_blocked() throws IOException {
        HttpServer entry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        entry.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        entry.start();
        try {
            PageFetchCore.Fetch f = newCore()
                    .fetch(url(entry), "GET", PageFetchCore.FetchOptions.body(3000, "t"));
            assertThat(f.blocked()).isTrue();
            assertThat(f.body()).isNull();
            assertThat(f.error()).contains("cloud-metadata");
        } finally {
            entry.stop(0);
        }
    }

    @Test
    @DisplayName("Ham UTF-8 Location ('yurt-ıci') tarayıcı gibi izlenir — 200, yanlış 404 'kırık bağlantı' yok "
            + "(2026-10-30, akyatirim.com.tr)")
    void rawUtf8Location_followedLikeABrowser() throws IOException {
        java.util.List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            seen.add(path);
            if ("/tr/tradeall/yurt-%C4%B1ci/".equals(path)) {
                // JDK sunucusu başlık karakterlerini birer bayt yazar → telde ham C4 B1 (prod sunucusunun yaptığı gibi)
                String raw = new String("/tr/tradeall/yurt-ıci".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.ISO_8859_1);
                ex.getResponseHeaders().add("Location", raw);
                ex.sendResponseHeaders(301, -1);
            } else if ("/tr/tradeall/yurt-%C4%B1ci".equals(path)) {
                byte[] b = "<html>ok</html>".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (var os = ex.getResponseBody()) { os.write(b); }
            } else {
                ex.sendResponseHeaders(404, -1);
            }
            ex.close();
        });
        site.start();
        try {
            String start = "http://127.0.0.1:" + site.getAddress().getPort() + "/tr/tradeall/yurt-%C4%B1ci/";
            PageFetchCore.Fetch f = newCore().fetch(start, "GET", PageFetchCore.FetchOptions.body(3000, "t"));
            assertThat(seen).containsExactly("/tr/tradeall/yurt-%C4%B1ci/", "/tr/tradeall/yurt-%C4%B1ci");
            assertThat(f.status()).isEqualTo(200);
            assertThat(f.error()).isNull();
        } finally {
            site.stop(0);
        }
    }

    @Test
    @DisplayName("SSRF: http/https DIŞI şemaya yönlendirme takip edilmez — 3xx olduğu gibi döner")
    void redirectToFileScheme_notFollowed() throws IOException {
        HttpServer entry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        entry.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", "file:///etc/passwd");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        entry.start();
        try {
            PageFetchCore.Fetch f = newCore()
                    .fetch(url(entry), "GET", PageFetchCore.FetchOptions.body(3000, "t"));
            assertThat(f.status()).isEqualTo(302);
            assertThat(f.blocked()).isFalse();
            assertThat(f.body()).isNull();
        } finally {
            entry.stop(0);
        }
    }
}
