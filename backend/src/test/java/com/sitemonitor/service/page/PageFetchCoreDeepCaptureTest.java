package com.sitemonitor.service.page;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kaynak analizi algoritma 3 — çekirdeğin derin yakalaması: HTTP sürümü, tel gövdesi özeti, betik örneği (kimlik / gzip;
 * br → yalnız sayım), {@code Set-Cookie} DEĞERSİZ, ana sayfa güvenlik başlıkları, bağlantı ölçümü. Ayarsız (izleme) çekim
 * hiçbir derin üst veri üretmez ve aynı sonucu verir.
 */
class PageFetchCoreDeepCaptureTest {

    HttpServer server;
    ExecutorService pool;
    String base;
    PageFetchCore core;
    PageFetchCore.ScanClients clients;
    static final String SCRIPT = "/*! jQuery v1.12.4 */\n" + "function f(a, b) {\n    return a + b;\n}\n".repeat(4000)
            + "//# sourceMappingURL=app.js.map\n";

    static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(o)) { g.write(b); }
        return o.toByteArray();
    }

    @BeforeEach
    void setUp() throws IOException {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        core = new PageFetchCore(new SsrfGuard(s));
        core.init();
        clients = core.newScanClients();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        byte[] script = SCRIPT.getBytes(StandardCharsets.UTF_8);
        byte[] gz = gzip(script);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/page" -> {
                    ex.getResponseHeaders().add("Location", "/page2");
                    ex.getResponseHeaders().add("Set-Cookie", "redir=sir1; Path=/; HttpOnly");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                }
                case "/page2" -> {
                    byte[] b = "<html><body>ok</body></html>".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Security-Policy", "default-src 'self'");
                    ex.getResponseHeaders().add("X-Frame-Options", "DENY");
                    ex.getResponseHeaders().add("X-Robots-Tag", "noindex");
                    ex.getResponseHeaders().add("Set-Cookie", "sid=COK-GIZLI-DEGER; Secure; SameSite=Lax");
                    ex.getResponseHeaders().add("ETag", "\"v1\"");
                    ex.sendResponseHeaders(200, b.length);
                    try (var os = ex.getResponseBody()) { os.write(b); }
                }
                case "/app.js" -> {
                    ex.getResponseHeaders().add("Content-Type", "application/javascript");
                    ex.sendResponseHeaders(200, script.length);
                    try (var os = ex.getResponseBody()) { os.write(script); }
                }
                case "/app.gz.js" -> {
                    ex.getResponseHeaders().add("Content-Encoding", "gzip");
                    ex.sendResponseHeaders(200, gz.length);
                    try (var os = ex.getResponseBody()) { os.write(gz); }
                }
                case "/app.zstd.js" -> {
                    // Çözücüsü olmayan kodlama (br ile aynı yol; JDK'nın test sunucusu "br" gövdesini göndermiyor)
                    ex.getResponseHeaders().add("Content-Encoding", "zstd");
                    ex.getResponseHeaders().add("Alt-Svc", "h3=\":443\"; ma=86400");
                    ex.sendResponseHeaders(200, 3000);
                    try (var os = ex.getResponseBody()) { os.write(new byte[3000]); }
                }
                case "/bad.gz.js" -> {
                    ex.getResponseHeaders().add("Content-Encoding", "gzip");
                    ex.sendResponseHeaders(200, 500);
                    try (var os = ex.getResponseBody()) { os.write(new byte[500]); }
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
        clients.shutdownNow();
        core.shutdown();
    }

    PageFetchCore.FetchOptions opts(boolean body, boolean count) {
        return new PageFetchCore.FetchOptions(10_000, "UA", null, body, count);
    }

    PageFetchCore.ScanTuning tuning() {
        return new PageFetchCore.ScanTuning(clients, PageFetchCore.ACCEPT_ENCODING_COUNT, PageFetchCore.MAX_BODY_BYTES, 1_000_000);
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    @DisplayName("izleme çekimi (ayarsız): derin üst veri YOK; sayım / gövde sonucu ayarlı çekimle aynı")
    void monitoringPathUnchanged() {
        PageFetchCore.MetaFetch plain = core.fetchWithMeta(base + "/app.js", "GET", opts(false, true), 0);
        assertThat(plain.meta().scan()).isNull();
        PageFetchCore.Fetch f = core.fetch(base + "/app.js", "GET", opts(false, true));
        PageFetchCore.MetaFetch deep = core.fetchWithMeta(base + "/app.js", "GET", opts(false, true), 0,
                tuning().capture(true, 65536, 4096, false));
        assertThat(deep.fetch().bytes()).isEqualTo(f.bytes()).isEqualTo(plain.fetch().bytes());
        assertThat(deep.fetch().status()).isEqualTo(f.status());
        assertThat(deep.fetch().truncated()).isEqualTo(f.truncated());
        PageFetchCore.MetaFetch noCapture = core.fetchWithMeta(base + "/app.js", "GET", opts(false, true), 0, tuning());
        assertThat(noCapture.meta().scan()).as("ayarlı ama yakalamasız: sürüm / çerez yine dolar").isNotNull();
        assertThat(noCapture.meta().scan().contentHash()).isNull();
        assertThat(noCapture.meta().scan().sampleHead()).isNull();
    }

    @Test
    @DisplayName("kimlik kodlu betik: özet = gövdenin SHA-256'sı; örnek baş + son; tamamı başta değil; HTTP sürümü")
    void identitySample() throws Exception {
        PageFetchCore.MetaFetch mf = core.fetchWithMeta(base + "/app.js", "GET", opts(false, true), 0,
                tuning().capture(true, 1024, 512, false));
        PageFetchCore.ScanMeta sm = mf.meta().scan();
        byte[] script = SCRIPT.getBytes(StandardCharsets.UTF_8);
        assertThat(sm.contentHash()).isEqualTo(sha(script));
        assertThat(new String(sm.sampleHead(), StandardCharsets.UTF_8)).startsWith("/*! jQuery v1.12.4").hasSize(1024);
        assertThat(new String(sm.sampleTail(), StandardCharsets.UTF_8)).endsWith("//# sourceMappingURL=app.js.map\n").hasSize(512);
        assertThat(sm.sampleComplete()).isFalse();
        assertThat(sm.sampleSkipped()).isNull();
        assertThat(sm.httpVersion()).isEqualTo("1.1");
        assertThat(mf.fetch().bytes()).isEqualTo(script.length);
    }

    @Test
    @DisplayName("gzip betik: tel baytı sayılır, özet tel baytının; örnek AÇILMIŞ metinden; bozuk gzip → decode, sayım sürer")
    void gzipSample() throws Exception {
        PageFetchCore.MetaFetch mf = core.fetchWithMeta(base + "/app.gz.js", "GET", opts(false, true), 0,
                tuning().capture(true, 65536, 4096, false));
        PageFetchCore.ScanMeta sm = mf.meta().scan();
        byte[] gz = gzip(SCRIPT.getBytes(StandardCharsets.UTF_8));
        assertThat(mf.fetch().bytes()).isEqualTo(gz.length);
        assertThat(sm.contentHash()).isEqualTo(sha(gz));
        assertThat(new String(sm.sampleHead(), StandardCharsets.UTF_8)).startsWith("/*! jQuery v1.12.4");
        assertThat(new String(sm.sampleTail(), StandardCharsets.UTF_8)).contains("sourceMappingURL=app.js.map");
        PageFetchCore.MetaFetch bad = core.fetchWithMeta(base + "/bad.gz.js", "GET", opts(false, true), 0,
                tuning().capture(true, 65536, 4096, false));
        assertThat(bad.meta().scan().sampleSkipped()).isEqualTo("decode");
        assertThat(bad.fetch().bytes()).isEqualTo(500);
        assertThat(bad.meta().scan().sampleTail()).isNull();
    }

    @Test
    @DisplayName("çözülemeyen kodlama (br / zstd): örnek yok (encoding), sayım ve özet var; Alt-Svc h3 başlığı taşınır")
    void undecodableCountsOnly() {
        PageFetchCore.MetaFetch mf = core.fetchWithMeta(base + "/app.zstd.js", "GET", opts(false, true), 0,
                tuning().capture(true, 65536, 4096, false));
        PageFetchCore.ScanMeta sm = mf.meta().scan();
        assertThat(sm.sampleHead()).isNull();
        assertThat(sm.sampleSkipped()).isEqualTo("encoding");
        assertThat(sm.contentHash()).isNotNull();
        assertThat(mf.fetch().bytes()).isEqualTo(3000);
        assertThat(sm.altSvc()).startsWith("h3=");
    }

    @Test
    @DisplayName("ana sayfa: güvenlik başlıkları yakalanır; tüm adımların Set-Cookie'si DEĞERSİZ; ETag")
    void pageHeadersAndCookies() {
        PageFetchCore.MetaFetch mf = core.fetchWithMeta(base + "/page", "GET", opts(true, true), 0,
                tuning().capture(true, 0, 0, true));
        PageFetchCore.ScanMeta sm = mf.meta().scan();
        assertThat(sm.pageHeaders()).containsKeys("content-security-policy", "x-frame-options", "x-robots-tag");
        assertThat(sm.pageHeaders().get("content-security-policy")).containsExactly("default-src 'self'");
        assertThat(sm.cookies()).extracting(PageFetchCore.SeenCookie::header)
                .containsExactly("redir=; Path=/; HttpOnly", "sid=; Secure; SameSite=Lax");
        assertThat(sm.cookies().toString()).doesNotContain("COK-GIZLI-DEGER").doesNotContain("sir1");
        assertThat(sm.etag()).isEqualTo("\"v1\"");
        assertThat(sm.contentHash()).isNotNull();
    }

    @Test
    @DisplayName("çerez değeri atma: adsız / bozuk → null; özniteliksiz ad=")
    void stripCookie() {
        assertThat(PageFetchCore.stripCookieValue("a=b; Secure")).isEqualTo("a=; Secure");
        assertThat(PageFetchCore.stripCookieValue("a=b")).isEqualTo("a=");
        assertThat(PageFetchCore.stripCookieValue("=b")).isNull();
        assertThat(PageFetchCore.stripCookieValue("noeq; Secure")).isNull();
    }

    @Test
    @DisplayName("örnekli sayım: tavan tel baytında; çözme sınırı; baş / son tampon")
    void sampleAndCountRules() throws Exception {
        byte[] data = "x".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        PageFetchCore.Sampled s = PageFetchCore.sampleAndCount(new ByteArrayInputStream(data), 4_000, null, 100, 50);
        assertThat(s.wire()).isEqualTo(4_000);
        assertThat(s.skipped()).isEqualTo("truncated");
        assertThat(s.tail()).isNull();
        PageFetchCore.Sampled full = PageFetchCore.sampleAndCount(new ByteArrayInputStream("abc".getBytes()), 100, "identity", 100, 50);
        assertThat(full.complete()).isTrue();
        assertThat(new String(full.head())).isEqualTo("abc");
        assertThat(new String(full.tail())).isEqualTo("abc");
        PageFetchCore.Sampled unknown = PageFetchCore.sampleAndCount(new ByteArrayInputStream(data), 100_000, "zstd", 100, 50);
        assertThat(unknown.skipped()).isEqualTo("encoding");
        assertThat(unknown.wire()).isEqualTo(10_000);
        assertThat(unknown.head()).isNull();
    }

    @Test
    @DisplayName("D-9: son tampon halkaya toplu kopyayla yazılır — tek sayılı / halkayı saran / halkadan büyük parçalarla son baytlar aynı")
    void sampleTailWithOddChunks() throws Exception {
        byte[] data = new byte[50_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 % 251);
        int[] chunks = {7, 8192, 1, 4999, 13, 250, 3, 8191};
        for (int tailN : new int[]{1, 5, 50, 4096, 8193, 9000, 60_000}) {
            for (String enc : new String[]{null, "gzip"}) {
                byte[] body = data;
                if ("gzip".equals(enc)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(data); }
                    body = bos.toByteArray();
                }
                PageFetchCore.Sampled s = PageFetchCore.sampleAndCount(new ChunkyStream(body, chunks), 1_000_000, enc, 100, tailN);
                int len = Math.min(data.length, tailN);
                assertThat(s.tail()).as("tailN=%d enc=%s", tailN, enc)
                        .isEqualTo(java.util.Arrays.copyOfRange(data, data.length - len, data.length));
                assertThat(s.head()).isEqualTo(java.util.Arrays.copyOf(data, 100));
                assertThat(s.wire()).isEqualTo(body.length);
            }
        }
    }

    /** Okumaları sırayla verilen boyutlarda döndüren akış (halkanın sarma / büyük parça durumları). */
    static final class ChunkyStream extends java.io.InputStream {
        final byte[] data;
        final int[] sizes;
        int pos, call;

        ChunkyStream(byte[] data, int[] sizes) {
            this.data = data;
            this.sizes = sizes;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) return -1;
            int n = Math.min(Math.min(len, sizes[call++ % sizes.length]), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    @Test
    @DisplayName("bağlantı ölçümü: yerel sunucuya DNS + TCP (TLS yok); kapalı port → connect_failed; çözülemeyen ad → dns_failed")
    void measureConnection() throws Exception {
        PageFetchCore.HostConnect ok = core.measureConnection("127.0.0.1", server.getAddress().getPort(), false, 2000);
        assertThat(ok.error()).isNull();
        assertThat(ok.dnsMs()).isNotNull();
        assertThat(ok.connectMs()).isNotNull();
        assertThat(ok.tlsMs()).isNull();
        int closed;
        try (ServerSocket ss = new ServerSocket(0)) { closed = ss.getLocalPort(); }
        assertThat(core.measureConnection("127.0.0.1", closed, false, 1000).error()).isEqualTo("connect_failed");
        assertThat(core.measureConnection("yok.invalid", 443, true, 1000).error()).isEqualTo("dns_failed");
        PageFetchCore.HostConnect tlsFail = core.measureConnection("127.0.0.1", server.getAddress().getPort(), true, 1500);
        assertThat(tlsFail.error()).as("düz HTTP sunucusunda TLS el sıkışması düşer").isEqualTo("tls_failed");
        assertThat(tlsFail.connectMs()).isNotNull();
        List<String> unused = List.of();
        assertThat(unused).isEmpty();
    }
}
