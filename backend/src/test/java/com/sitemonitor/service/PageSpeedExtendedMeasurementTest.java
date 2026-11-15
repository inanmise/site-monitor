package com.sitemonitor.service;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.page.PageFetchCore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Sayfa Hızı 2c — genişletilmiş ölçüm (2026-11-15, madde 10), YEREL HTTP sunucusuyla uçtan uca.
 *
 * <p>Pinlenen: stil dosyalarının başvurduğu yazı tipi / arka plan görseli / {@code @import} (en çok 2 kat) ölçülür, göreli
 * adres yönlendirme SONRASI stil adresine göre çözülür, satır içi stil ({@code <style>}, {@code style=""}) okunur ama
 * {@code <noscript>} içindeki okunmaz, hariç tutma kuralı yeni adreslere de uygulanır, sayılan nesneler {@code br} ile, ayrıştırılan
 * stil {@code br}'siz istenir, başvuran stil satıra yazılır, kaynak tavanı aşılınca ölçüm kısmi işaretlenir. Kapalı kipte
 * (varsayılan, mevcut izlemeler) hiçbiri olmaz.
 */
class PageSpeedExtendedMeasurementTest {

    private HttpServer server;
    private String base;
    private PageFetchCore core;
    private PageSpeedCheckerService checker;
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();
    private final Map<String, String> encodings = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        AppSettingsService settings = mock(AppSettingsService.class);
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getInt(eq(PageFetchCore.MAX_RESOURCES_KEY), anyInt())).thenReturn(100);   // tavan testi için en küçük
        lenient().when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        SsrfGuard guard = new SsrfGuard(settings);
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        SecretCipher cipher = mock(SecretCipher.class);
        core = new PageFetchCore(guard);
        org.springframework.test.util.ReflectionTestUtils.setField(core, "appSettings", settings);
        core.init();
        checker = new PageSpeedCheckerService(core, new com.sitemonitor.service.page.HttpPhaseProbe(guard), psl, settings, cipher);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        html("/sayfa", """
            <html><head>
              <link rel="stylesheet" href="/old.css">
              <style>.a{background:url(/inline-bg.png)}</style>
            </head><body>
              <div style="background-image:url('/div-bg.png')">x</div>
              <noscript><style>.n{background:url(/noscript.png)}</style></noscript>
              <img src="/logo.png">
            </body></html>""");
        server.createContext("/old.css", ex -> {
            record(ex);
            ex.getResponseHeaders().add("Location", "/css/main.css");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        text("/css/main.css", "text/css", """
            @import url("sub.css");
            /* yorum: url(/yorum.png) okunmaz */
            @font-face{font-family:F;src:url(f.eot);src:url(f.eot?#iefix) format('embedded-opentype'),url(f.woff2) format('woff2'),url(f.ttf) format('truetype')}
            .h{background:url(img/hero.png)}
            .t{background:url(/izleme/takip.png)}
            """);
        text("/css/sub.css", "text/css", "@import url(deep.css); .s{background:url(s.png)}");
        text("/css/deep.css", "text/css", ".d{background:url(deep.png)}");
        for (String p : new String[]{"/inline-bg.png", "/div-bg.png", "/noscript.png", "/logo.png", "/css/f.woff2",
                "/css/f.eot", "/css/f.ttf", "/css/img/hero.png", "/img/hero.png", "/izleme/takip.png", "/css/s.png",
                "/css/deep.png", "/yorum.png"}) {
            bytes(p, 1000);
        }
        // Tavan sayfası: 99 görsel + 1 stil = 100 kaynak (tavan); stilin 3 başvurusu tavanı aşar
        StringBuilder cap = new StringBuilder("<html><head><link rel='stylesheet' href='/tavan.css'></head><body>");
        for (int i = 0; i < 99; i++) cap.append("<img src='/t").append(i).append(".png'>");
        html("/tavan-sayfa", cap.append("</body></html>").toString());
        text("/tavan.css", "text/css", ".a{background:url(/x1.png)}.b{background:url(/x2.png)}.c{background:url(/x3.png)}");
        for (int i = 0; i < 99; i++) bytes("/t" + i + ".png", 10);
        for (int i = 1; i <= 3; i++) bytes("/x" + i + ".png", 10);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (core != null) core.shutdown();
    }

    private PageSpeedMonitor monitor(String path, Boolean extended) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(7L);
        m.setName("ext");
        m.setUrl(base + path);
        m.setTimeoutMs(5000);
        m.setResourceConcurrency(4);
        m.setExtendedMeasurement(extended);
        m.setExcludeTrackers(false);
        m.setTrackerPatterns("/izleme/");
        return m;
    }

    @Test
    @DisplayName("Genişletilmiş: stil başvuruları (font, arka plan, @import 2 kat, satır içi) ölçülür; nihai adrese göre çözülür")
    void extendedFollowsStylesheets() {
        var r = checker.check(monitor("/sayfa", true));
        assertThat(r.extended()).isTrue();
        Map<String, PageSpeedCheckerService.Measured> byPath = r.resources().stream()
                .collect(Collectors.toMap(x -> x.url().substring(base.length()), x -> x, (a, b) -> a));

        assertThat(byPath).containsKeys("/old.css", "/logo.png", "/inline-bg.png", "/div-bg.png", "/css/f.woff2",
                "/css/img/hero.png", "/css/sub.css", "/css/s.png", "/css/deep.css");
        // Yönlendirme SONRASI adres: göreli img/hero.png → /css/img/hero.png (sayfa köküne göre DEĞİL)
        assertThat(hits.getOrDefault("/img/hero.png", 0)).isZero();
        // @font-face son src'nin ilk desteklenen biçimi — eot / ttf indirilmez
        assertThat(hits.getOrDefault("/css/f.eot", 0)).isZero();
        assertThat(hits.getOrDefault("/css/f.ttf", 0)).isZero();
        // 2. kattaki stil tartılır ama başvuruları izlenmez (kaynak analiziyle aynı derinlik)
        assertThat(hits.getOrDefault("/css/deep.png", 0)).isZero();
        // noscript içindeki satır içi stil okunmaz; CSS yorumu okunmaz; hariç tutma kuralı yeni adrese de uygulanır
        assertThat(hits.getOrDefault("/noscript.png", 0)).isZero();
        assertThat(hits.getOrDefault("/yorum.png", 0)).isZero();
        assertThat(hits.getOrDefault("/izleme/takip.png", 0)).isZero();

        // Başvuran: stil dosyasının (yönlendirme sonrası) adresi; satır içi stil → sayfa; HTML öğesi → null
        assertThat(byPath.get("/css/img/hero.png").foundIn()).isEqualTo(base + "/css/main.css");
        assertThat(byPath.get("/css/s.png").foundIn()).isEqualTo(base + "/css/sub.css");
        assertThat(byPath.get("/inline-bg.png").foundIn()).isEqualTo(base + "/sayfa");
        assertThat(byPath.get("/logo.png").foundIn()).isNull();

        // Tür kırılımı + istek sayısı yeni kaynakları içerir
        assertThat(r.breakdown().fontCount()).isEqualTo(1);
        assertThat(r.requestCount()).isEqualTo(1 + r.resources().size());
        assertThat(r.capped()).isFalse();

        // Accept-Encoding: sayılan nesne br ile, ayrıştırılan stil br'siz
        assertThat(encodings.get("/logo.png")).contains("br");
        assertThat(encodings.get("/css/f.woff2")).contains("br");
        assertThat(encodings.get("/css/main.css")).doesNotContain("br");
        assertThat(encodings.get("/css/sub.css")).doesNotContain("br");
        assertThat(encodings.get("/sayfa")).doesNotContain("br");
    }

    @Test
    @DisplayName("Kapalı (null = mevcut izleme): yalnız HTML'in yüklediği kaynaklar — stil başvurusu ve br yok")
    void classicUnchanged() {
        var r = checker.check(monitor("/sayfa", null));
        assertThat(r.extended()).isFalse();
        assertThat(r.resources()).extracting(x -> x.url().substring(base.length()))
                .containsExactlyInAnyOrder("/old.css", "/logo.png");
        assertThat(r.resources()).allSatisfy(x -> assertThat(x.foundIn()).isNull());
        assertThat(hits.getOrDefault("/css/f.woff2", 0)).isZero();
        assertThat(hits.getOrDefault("/inline-bg.png", 0)).isZero();
        assertThat(encodings.get("/logo.png")).doesNotContain("br");
    }

    @Test
    @DisplayName("Kaynak tavanı (100) stil başvurularıyla aşılırsa ölçüm KISMİ işaretlenir; tavan dışı adres indirilmez")
    void capIsRespected() {
        var r = checker.check(monitor("/tavan-sayfa", true));
        assertThat(r.capped()).isTrue();
        assertThat(r.requestCount()).isEqualTo(101);
        for (int i = 1; i <= 3; i++) assertThat(hits.getOrDefault("/x" + i + ".png", 0)).isZero();
    }

    private void html(String path, String body) {
        text(path, "text/html; charset=utf-8", body);
    }

    private void text(String path, String type, String body) {
        server.createContext(path, ex -> {
            record(ex);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
    }

    private void bytes(String path, int size) {
        server.createContext(path, ex -> {
            if (!ex.getRequestURI().getPath().equals(path)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            record(ex);
            byte[] b = new byte[size];
            ex.getResponseHeaders().add("Content-Type", path.endsWith(".woff2") ? "font/woff2" : "image/png");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
    }

    private void record(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        hits.merge(path, 1, Integer::sum);
        List<String> ae = ex.getRequestHeaders().get("Accept-Encoding");
        encodings.put(path, ae == null ? "" : String.join(",", ae));
    }
}
