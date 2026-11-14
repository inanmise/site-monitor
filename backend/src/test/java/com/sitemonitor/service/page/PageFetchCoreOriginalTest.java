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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Görsel aracının "özgün bayt" çekimi ({@link PageFetchCore#fetchOriginal}, 2026-11-14 inceleme düzeltmeleri):
 * {@code Accept-Encoding: identity} istenir, gövde {@code Content-Length}'ten ön boyutlu okunur (yalancı uzunluk tavanla
 * sınırlı), bildirilen boyut tavana ulaşıyorsa gövde HİÇ okunmaz, sunucu yine sıkıştırırsa tavanla açılır, düz http son
 * adımda TLS zinciri yoktur. Ayarsız ve tarama çekimleri bu kurallardan etkilenmez.
 */
class PageFetchCoreOriginalTest {

    static final int CAP = 1000;

    HttpServer server;
    ExecutorService pool;
    String base;
    PageFetchCore core;
    final Map<String, String> acceptEncoding = new ConcurrentHashMap<>();
    final AtomicLong bigSent = new AtomicLong();

    @BeforeEach
    void setUp() throws IOException {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        core = new PageFetchCore(new SsrfGuard(s));
        core.init();
        byte[] small = new byte[600];
        Arrays.fill(small, (byte) 7);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) { g.write(new byte[50_000]); }
        byte[] bomb = gz.toByteArray();
        ByteArrayOutputStream gzs = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gzs)) { g.write(small); }
        byte[] gzSmall = gzs.toByteArray();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            acceptEncoding.put(path, String.valueOf(ex.getRequestHeaders().getFirst("Accept-Encoding")));
            switch (path) {
                case "/small" -> {
                    ex.sendResponseHeaders(200, small.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(small); }
                }
                case "/gz" -> {
                    ex.getResponseHeaders().add("Content-Encoding", "gzip");   // identity istendi ama yine sıkıştırdı
                    ex.sendResponseHeaders(200, gzSmall.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(gzSmall); }
                }
                case "/bomb" -> {
                    ex.getResponseHeaders().add("Content-Encoding", "gzip");
                    ex.sendResponseHeaders(200, bomb.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(bomb); }
                }
                case "/big" -> {
                    // 64 MB bildirir: gövde HİÇ okunmamalı (gönderilen yalnız soket tamponları kadar)
                    long total = 64L * 1024 * 1024;
                    ex.sendResponseHeaders(200, total);
                    byte[] chunk = new byte[64 * 1024];
                    try (OutputStream os = ex.getResponseBody()) {
                        for (long left = total; left > 0; left -= chunk.length) {
                            os.write(chunk);
                            bigSent.addAndGet(chunk.length);
                        }
                    } catch (IOException clientClosed) {
                        // istemci okumadan bıraktı
                    }
                }
                case "/redir" -> {
                    ex.getResponseHeaders().add("Location", "/small");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                }
                default -> {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                }
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

    PageFetchCore.OriginalFetch original(String path) {
        try (PageFetchCore.ScanClients c = core.newScanClients()) {
            return core.fetchOriginal(base + path, new PageFetchCore.FetchOptions(5000, "UA", null, true, false),
                    PageFetchCore.ScanTuning.original(c, CAP + 1));
        }
    }

    static InputStream in(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) i;
        return new ByteArrayInputStream(b);
    }

    @Test
    @DisplayName("ön boyutlu okuma: bildirilen = gelen, kısa gövde kırpılır, yalancı uzun gövde tavana dek eklenir, bildirim yok / tavanda → readNBytes")
    void readPresizedRules() throws IOException {
        assertThat(PageFetchCore.readPresized(in(500), 1001, 500)).hasSize(500).isEqualTo(in(500).readAllBytes());
        assertThat(PageFetchCore.readPresized(in(300), 1001, 500)).as("gövde bildirilenden kısa").hasSize(300);
        byte[] longer = PageFetchCore.readPresized(in(800), 1001, 500);
        assertThat(longer).as("yalancı Content-Length: kalan da okunur").hasSize(800).isEqualTo(in(800).readAllBytes());
        assertThat(PageFetchCore.readPresized(in(5000), 1001, 500)).as("uzun gövde yine tavanla sınırlı").hasSize(1001);
        assertThat(PageFetchCore.readPresized(in(5000), 1001, -1)).as("bildirim yok").hasSize(1001);
        assertThat(PageFetchCore.readPresized(in(5000), 1001, 1001)).as("bildirim tavanda").hasSize(1001);
        assertThat(PageFetchCore.readPresized(in(0), 1001, 0)).isEmpty();
        assertThat(PageFetchCore.readPresized(in(3), 1001, 0)).as("0 bildirip gövde gönderen").hasSize(3);
    }

    @Test
    @DisplayName("Accept-Encoding: identity istenir (her adımda); gövde bayt bayt aynı; düz http son adımda TLS zinciri yok, düşürme yok")
    void identityAndPlainHttp() {
        PageFetchCore.OriginalFetch f = original("/redir");
        assertThat(f.fetch().status()).isEqualTo(200);
        assertThat(f.fetch().body()).hasSize(600).containsOnly((byte) 7);
        assertThat(acceptEncoding).containsEntry("/redir", "identity").containsEntry("/small", "identity");
        assertThat(f.peerChain()).isNull();
        assertThat(f.downgradeRefused()).isFalse();
        assertThat(f.meta().finalUrl()).isEqualTo(base + "/small");
    }

    @Test
    @DisplayName("sunucu identity'yi yok sayıp sıkıştırırsa bugünkü gibi açılır; açılmış gövde tavanı aşarsa tavanda kesilir (bomba)")
    void gzipStillHandledAndCapped() {
        PageFetchCore.OriginalFetch f = original("/gz");
        assertThat(f.fetch().body()).hasSize(600).containsOnly((byte) 7);
        PageFetchCore.OriginalFetch b = original("/bomb");
        assertThat(b.fetch().body()).as("50 000 bayta açılan gövde tavan + 1'de durur").hasSize(CAP + 1);
    }

    @Test
    @DisplayName("bildirilen boyut tavana ulaşıyor: gövde HİÇ okunmaz (64 MB'ın yalnız soket tamponu kadarı gider), bayt sayısı = bildirilen")
    void declaredOverCapIsNotRead() {
        PageFetchCore.OriginalFetch f = original("/big");
        assertThat(f.fetch().status()).isEqualTo(200);
        assertThat(f.fetch().body()).isNull();
        assertThat(f.fetch().truncated()).isTrue();
        assertThat(f.fetch().bytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(bigSent.get()).as("gövde okunmadı").isLessThan(16L * 1024 * 1024);
    }

    @Test
    @DisplayName("özgün çekim yalnız özgün ayarla; tarama / izleme ayarı identity istemez ve ön boyut kullanmaz")
    void originalTuningOnly() {
        try (PageFetchCore.ScanClients c = core.newScanClients()) {
            PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(5000, "UA", null, true, false);
            assertThatThrownBy(() -> core.fetchOriginal(base + "/small", o, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> core.fetchOriginal(base + "/small", o,
                    new PageFetchCore.ScanTuning(c, PageFetchCore.ACCEPT_ENCODING, CAP + 1, CAP + 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            PageFetchCore.MetaFetch scan = core.fetchWithMeta(base + "/big", "GET", o, 0,
                    new PageFetchCore.ScanTuning(c, PageFetchCore.ACCEPT_ENCODING, CAP + 1, CAP + 1));
            assertThat(scan.fetch().body()).as("tarama yolu tavana dek OKUR (bugünkü davranış)").hasSize(CAP + 1);
            assertThat(acceptEncoding.get("/big")).isEqualTo(PageFetchCore.ACCEPT_ENCODING);
        }
        PageFetchCore.ScanTuning t = PageFetchCore.ScanTuning.original(null, 10);
        assertThat(t.original()).isTrue();
        assertThat(t.acceptEncoding()).isEqualTo("identity");
        assertThat(t.capture(true, 1, 1, true).original()).as("capture özgün bayrağını korur").isTrue();
        assertThat(new PageFetchCore.ScanTuning(null, "x", 1, 1).original()).isFalse();
    }
}
