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

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort() + "/";
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
