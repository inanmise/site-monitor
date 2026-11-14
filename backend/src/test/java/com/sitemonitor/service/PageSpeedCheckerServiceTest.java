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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sayfa Hızı ölçüm motoru — YEREL HTTP sunucusuyla uçtan uca (dış siteye bağımlılık YOK).
 *
 * <p>Burada pinlenen şeyler ölçümün DOĞRULUĞUdur: kaç bayt indi, kaç istek atıldı, hangi kaynak
 * sayıldı hangisi sayılmadı. Bunlar yanlışsa ekranda yine bir rakam görünür — sadece yanlış bir
 * rakam. Sessiz hata sınıfı olduğu için gerçek baytlarla doğrulanır.
 */
class PageSpeedCheckerServiceTest {

    private HttpServer server;
    private String base;
    private PageFetchCore core;
    private com.sitemonitor.service.page.HttpPhaseProbe phaseProbe;
    private PageSpeedCheckerService checker;
    /** Sunucunun gördüğü istek başlıkları: yol → başlık haritası (kimlik/DNT testleri buradan okur). */
    private final Map<String, Map<String, List<String>>> seenHeaders = new ConcurrentHashMap<>();
    /** Yol → kaç kez istendi (tracker hariç tutma "indirilmedi mi" diye buraya bakar). */
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();
    private AppSettingsService appSettings;

    @BeforeEach
    void setUp() throws IOException {
        AppSettingsService settings = mock(AppSettingsService.class);
        // SsrfGuard: loopback + internal İZİNLİ (test sunucusu 127.0.0.1'de).
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));

        appSettings = settings;
        SsrfGuard guard = new SsrfGuard(settings);
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        SecretCipher cipher = mock(SecretCipher.class);
        // Şifre çözme kimliktir: testte şifreli alan = düz metin.
        lenient().when(cipher.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));

        phaseProbe = new com.sitemonitor.service.page.HttpPhaseProbe(guard);
        core = new PageFetchCore(guard);
        core.init();
        checker = new PageSpeedCheckerService(core, phaseProbe, psl, settings, cipher);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        wireHandlers();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (core != null) core.shutdown();
    }

    // ── Lazy kaynaklar ve faz kırılımı ──────────────────────────────────────

    @Test
    @DisplayName("loading=lazy kaynaklar ÖLÇÜLMEZ ve atlanan sayısı raporlanır")
    void lazyResourcesAreSkippedAndCounted() {
        var r = checker.test(monitor("/lazy"));

        assertThat(r.skippedLazy()).as("atlanan sayı sessizce kaybolmamalı").isEqualTo(2);
        // Yalnız ana sayfa + lazy OLMAYAN görsel indirilmeli.
        assertThat(hits.getOrDefault("/lazy1.png", 0)).isZero();
        assertThat(hits.getOrDefault("/lazy2.png", 0)).isZero();
        assertThat(hits.getOrDefault("/a.png", 0)).isEqualTo(1);
        // 1 MB'lık iki lazy görsel toplama girmemeli.
        assertThat(r.totalBytes()).isLessThan(500_000L);
    }

    @Test
    @DisplayName("Sayfa Bütünlüğü envanteri lazy kaynakları ATLAMAZ (orada soru 'var mı')")
    void integrityInventoryStillSeesLazy() {
        byte[] html = ("<html><body><img src='" + base + "/a.png'>"
                + "<img src='" + base + "/lazy1.png' loading='lazy'></body></html>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var full = core.inventory(html, base, base + "/x", null, PageFetchCore.InventoryOptions.FULL);
        var browser = core.inventory(html, base, base + "/x", null,
                PageFetchCore.InventoryOptions.AS_BROWSER_LOADS);

        assertThat(full).hasSize(2);
        assertThat(browser).hasSize(1);
    }

    @Test
    @DisplayName("TTFB fazlara ayrılır: sunucu bekleme AYRI ölçülür ve eşik ona bakar")
    void phasesAreMeasured() {
        var r = checker.test(monitor("/"));

        // DNS/bağlantı ölçülmeli (yerel sunucuda TLS yok → null beklenir).
        assertThat(r.phases().dnsMs()).isNotNull();
        assertThat(r.phases().connectMs()).isNotNull();
        assertThat(r.phases().serverMs()).as("eşiğin baktığı değer").isNotNull();
        assertThat(r.phases().tlsMs()).as("düz HTTP'de TLS fazı yok").isNull();
        // Fazlar tek tek toplam ölçümden küçük olmalı — biri diğerini kapsamamalı.
        assertThat(r.phases().serverMs()).isLessThanOrEqualTo((int) r.totalMs() + 1000);
    }

    // ── Transfer boyutu ve saklama ──────────────────────────────────────────

    @Test
    @DisplayName("Accept-Encoding GÖNDERİLİR — ölçülen 'transfer boyutu' gerçekten telden geçen")
    void acceptEncodingIsSent() {
        checker.test(monitor("/"));

        // Java HttpClient bu başlığı kendiliğinden EKLEMEZ; göndermeyince sunucu sıkıştırmasız
        // yanıtlıyor ve arayüzdeki "transfer boyutu" etiketi yalan oluyordu.
        for (String path : new String[]{ "/", "/a.css", "/a.js", "/a.png" }) {
            var h = seenHeaders.get(path);
            assertThat(h).as("istek görülmedi: %s", path).isNotNull();
            assertThat(h.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Accept-Encoding")))
                    .as("Accept-Encoding eksik: %s", path).isTrue();
        }
    }

    /**
     * İNDİRİLEN İÇERİK HİÇBİR YERDE TUTULMAZ: ölçülür ve atılır.
     *
     * <p>Alt kaynak çekimi gövdeyi belleğe bile almaz ({@code countBytes} yolu yalnız sayar),
     * ölçüm satırı da yalnız üstveri taşır. Bu davranış bugün doğru; kapı, ileride "hata ayıklama
     * için gövdeyi de saklayalım" diye eklenecek bir alanın sessizce geçmesini engeller.
     */
    @Test
    @DisplayName("Ölçüm sonucu hiçbir kaynağın İÇERİĞİNİ taşımaz")
    void measurementCarriesNoContent() {
        var r = checker.test(monitor("/"));
        assertThat(r.resources()).isNotEmpty();

        for (var comp : PageSpeedCheckerService.Measured.class.getRecordComponents()) {
            assertThat(comp.getType())
                    .as("Measured.%s içerik taşıyor olabilir", comp.getName())
                    .isNotEqualTo(byte[].class);
        }
        for (var f : com.sitemonitor.model.PageSpeedResource.class.getDeclaredFields()) {
            assertThat(f.getType()).as("PageSpeedResource.%s içerik taşıyor", f.getName())
                    .isNotEqualTo(byte[].class);
            assertThat(f.getName().toLowerCase())
                    .as("PageSpeedResource.%s gövde saklıyor olabilir", f.getName())
                    .doesNotContain("body").doesNotContain("content");
        }
    }

    // ── Test sunucusu ────────────────────────────────────────────────────────

    private static final int CSS_BYTES = 3000;
    private static final int JS_BYTES = 5000;
    private static final int IMG_BYTES = 12000;
    private static final int TRACKER_BYTES = 40000;

    private void wireHandlers() {
        // Ana sayfa: 1 CSS + 1 JS + 1 IMG + 1 tracker + 1 a[href] linki.
        // a[href] tarayıcı tarafından İNDİRİLMEZ → ölçüme girmemeli.
        html("/", """
            <html><head>
              <link rel="stylesheet" href="/a.css">
              <script src="/a.js"></script>
              <script src="https://www.google-analytics.com/analytics.js"></script>
            </head><body>
              <img src="/a.png">
              <a href="/baska-sayfa">Baska sayfa</a>
            </body></html>""");
        bytes("/a.css", CSS_BYTES, "text/css");
        bytes("/a.js", JS_BYTES, "application/javascript");
        bytes("/a.png", IMG_BYTES, "image/png");
        bytes("/baska-sayfa", 99999, "text/html");   // istenirse ÇOK büyük → yanlışlıkla sayılırsa fark edilir

        html("/kimlikli", "<html><body><img src='/a.png'></body></html>");
        // Responsive gorsel: tarayici DPR/ekrana gore YALNIZ BIRINI indirir.
        html("/responsive", """
            <html><body>
              <img src="/a.png" srcset="/r-320.png 320w, /r-640.png 640w, /r-1280.png 1280w">
              <picture><source srcset="/p-a.webp 1x, /p-b.webp 2x"><img src="/a.css.png"></picture>
            </body></html>""");
        bytes("/r-320.png", 1000, "image/png");
        bytes("/r-640.png", 2000, "image/png");
        bytes("/r-1280.png", 4000, "image/png");
        bytes("/p-a.webp", 1000, "image/webp");
        bytes("/p-b.webp", 2000, "image/webp");
        bytes("/a.css.png", 1500, "image/png");
        // Butce sayfasi: dort ADET 30 KB kaynak. 64 KB tavaniyla ikisi indirilir, kalani ATLANIR.
        html("/agir", """
            <html><head>
              <script src="/h1.js"></script><script src="/h2.js"></script>
              <script src="/h3.js"></script><script src="/h4.js"></script>
            </head><body>x</body></html>""");
        for (int i = 1; i <= 4; i++) bytes("/h" + i + ".js", 30 * 1024, "application/javascript");
        // Link agirlikli sayfa: 600 a[href] + 2 gercek kaynak.
        StringBuilder linky = new StringBuilder("<html><head>"
                + "<link rel='stylesheet' href='/a.css'><script src='/a.js'></script></head><body>");
        for (int i = 0; i < 600; i++) linky.append("<a href='/l").append(i).append("'>x</a>");
        html("/linkli", linky.append("</body></html>").toString());
        // Bayt tavanini (10 MB) ASAN tek kaynak: sayim orada kesilmeli ve isaretlenmeli.
        html("/devasa", "<html><body><img src='/dev.bin'></body></html>");
        bytes("/dev.bin", (int) PageFetchCore.MAX_COUNT_BYTES + 4096, "application/octet-stream");
        // loading="lazy": tarayici ekran disindaki gorseli HIC istemez. Olcumun onu indirmesi
        // sayfayi kat kat agir gosteriyordu (44.6 MB olculen sayfada tarayici 6.1 MB indiriyordu).
        html("/lazy", """
            <html><body>
              <img src="/a.png">
              <img src="/lazy1.png" loading="lazy">
              <img src="/lazy2.png" loading="lazy">
            </body></html>""");
        bytes("/lazy1.png", 500_000, "image/png");
        bytes("/lazy2.png", 500_000, "image/png");
        html("/bos", "<html><body>hicbir kaynak yok</body></html>");
        server.createContext("/hata", ex -> { record(ex); ex.sendResponseHeaders(500, -1); ex.close(); });
        // Kaynağı kırık sayfa: ölçüm devam etmeli, failed_count artmalı.
        html("/kirik", "<html><head><script src='/yok.js'></script></head><body>x</body></html>");
        server.createContext("/yok.js", ex -> { record(ex); ex.sendResponseHeaders(404, -1); ex.close(); });
    }

    private void html(String path, String body) {
        server.createContext(path, ex -> {
            record(ex);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
    }

    private void bytes(String path, int size, String contentType) {
        server.createContext(path, ex -> {
            record(ex);
            byte[] b = new byte[size];
            java.util.Arrays.fill(b, (byte) 'x');
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
    }

    private void record(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        hits.merge(path, 1, Integer::sum);
        // DERİN kopya: exchange kapandıktan sonra Headers geri dönüştürülebiliyor; canlı referans tutmak
        // testi zamanlamaya bağımlı kılardı.
        Map<String, List<String>> copy = new java.util.LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> copy.put(k, List.copyOf(v)));
        seenHeaders.put(path, Collections.unmodifiableMap(copy));
    }

    private PageSpeedMonitor monitor(String path) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(1L);
        m.setName("test");
        m.setUrl(base + path);
        m.setTimeoutMs(5000);
        m.setResourceConcurrency(5);
        return m;
    }

    // ── 2026-11-14 Sayfa Hızı zenginleştirme: tür kırılımı, süre tavanı, gerçek TTFB, yeni eşikler ─────────────────────

    @Test
    @DisplayName("Tür kırılımı (madde 9): bayt ölçülen, adet sayfanın istediği; html + türler + birinci/üçüncü taraf = toplam")
    void typeBreakdownIsComputed() {
        var r = checker.check(monitor("/"));
        var b = r.breakdown();
        assertThat(b).isNotNull();
        assertThat(b.cssBytes()).isEqualTo(CSS_BYTES);
        assertThat(b.cssCount()).isEqualTo(1);
        assertThat(b.imgBytes()).isEqualTo(IMG_BYTES);
        assertThat(b.imgCount()).isEqualTo(1);
        assertThat(b.jsCount()).as("kendi betik + tracker").isEqualTo(2);
        assertThat(b.jsBytes()).isGreaterThanOrEqualTo(JS_BYTES);
        assertThat(b.fontCount()).isZero();
        assertThat(b.htmlBytes()).isPositive();
        assertThat(b.htmlBytes() + b.jsBytes() + b.cssBytes() + b.imgBytes() + b.fontBytes() + b.otherBytes())
                .isEqualTo(r.totalBytes());
        assertThat(b.firstPartyBytes() + b.thirdPartyBytes()).isEqualTo(r.totalBytes());
        assertThat(r.timeTruncated()).isFalse();
        // Alınamayan sayfada kırılım yok (bilinmiyor ≠ 0)
        assertThat(checker.check(monitor("/hata")).breakdown()).isNull();
    }

    @Test
    @DisplayName("Süre tavanı (madde 4): duvar saati dolunca atlanan kaynak varsa ölçüm İŞARETLİ (time_truncated), alt sınır")
    void wallClockCutIsFlagged() {
        // Yavaş kaynaklı sayfa: her kaynak 1,5 sn; tavan 10 sn (alt sınır) — 40 kaynak, eşzamanlılık 1 → bitemez.
        StringBuilder html = new StringBuilder("<html><head>");
        for (int i = 0; i < 40; i++) html.append("<script src='/yavas").append(i).append(".js'></script>");
        html("/yavas", html.append("</head><body>x</body></html>").toString());
        for (int i = 0; i < 40; i++) {
            server.createContext("/yavas" + i + ".js", ex -> {
                record(ex);
                try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                byte[] b = "x".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            });
        }
        PageSpeedMonitor m = monitor("/yavas");
        m.setResourceConcurrency(1);
        var r = checker.checkForDiagnostics(m, false, 10);
        assertThat(r.timeTruncated()).as("süre tavanı işaretlenmeli").isTrue();
        assertThat(r.lowerBound()).isTrue();
        assertThat(r.requestCount()).as("istek sayısı sayfanın istediği kadar").isEqualTo(41);
        assertThat(r.breakdown().jsCount()).isEqualTo(40);
    }

    @Test
    @DisplayName("TTFB (madde 2, inceleme B2): faz probu izlemenin koşullarıyla — kimlik + UA gider, yönlendirme izlenir; SON adımın sunucu fazı eşiğe girer")
    void ttfbThresholdUsesTheMonitorsConditions() {
        // /eski → 301 → /auth-yavas. Kimliksiz istek 401'i HEMEN alır; kimlikli istek 600 ms bekler. Eski prob kimliksizdi ve
        // yönlendirme izlemiyordu: 301'in (ya da 401'in) birkaç ms'sini ölçer, eşik hiç aşılmazdı.
        server.createContext("/eski", ex -> {
            record(ex);
            ex.getResponseHeaders().add("Location", "/auth-yavas");
            ex.sendResponseHeaders(301, -1);
            ex.close();
        });
        java.util.List<String> probeUas = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/auth-yavas", ex -> {
            record(ex);
            probeUas.add(String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
            boolean authed = ex.getRequestHeaders().getFirst("Authorization") != null;
            if (!authed) { ex.sendResponseHeaders(401, -1); ex.close(); return; }
            try { Thread.sleep(600); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            byte[] b = "<html><body>x</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        PageSpeedMonitor m = monitor("/eski");
        m.setBasicAuthUser("u");
        m.setBasicAuthPassEnc("p");
        m.setUserAgent("Izleme-UA/1.0");
        m.setMaxTtfbMs(300);
        var r = checker.check(m);
        assertThat(r.reachable()).isTrue();
        // eşiğin okuduğu sayı = probun SON adımının sunucu fazı (kimlikli 200'ün 600 ms'si)
        assertThat(r.serverTtfbMs()).isNotNull().isGreaterThanOrEqualTo(550);
        assertThat(r.measurement().ttfbMs()).isEqualTo(r.serverTtfbMs());
        assertThat(r.breached()).contains(com.sitemonitor.service.page.PageSpeedRules.BREACH_TTFB);
        // prob da izlemenin UA'sını gönderdi (eski "SiteMonitor-PhaseProbe" değil)
        assertThat(probeUas).isNotEmpty().allMatch("Izleme-UA/1.0"::equals);
    }

    @Test
    @DisplayName("TTFB (inceleme B2): faz probu her ölçümde TAZE bağlantı açar — havuz sıcaklığı sayıyı değiştirmez (bağlantı kapatılır)")
    void phaseProbeOpensAFreshConnectionEachTime() throws Exception {
        java.util.List<String> conn = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/taze", ex -> {
            conn.add(String.valueOf(ex.getRequestHeaders().getFirst("Connection")));
            byte[] b = "<html><body>x</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        var t = new com.sitemonitor.service.page.HttpPhaseProbe.Target(base + "/taze", 5000, "UA", java.util.Map.of(), false);
        var p1 = phaseProbe.measure(t);
        var p2 = phaseProbe.measure(t);
        assertThat(p1.serverMs()).isNotNull();
        assertThat(p2.serverMs()).isNotNull();
        // her adım "Connection: close" ile kendi soketini açıp kapatır — paylaşılan havuz yok
        assertThat(conn).hasSize(2).allMatch(c -> c.equalsIgnoreCase("close"));
    }

    @Test
    @DisplayName("TTFB (inceleme B1 / B2): prob ölçemezse TTFB BİLİNMİYOR — 0 değil; tanımlı TTFB eşiği kararsız, ölçüm 'eşik içinde' sayılmaz")
    void ttfbUnknownWhenProbeFails() {
        var stub = new com.sitemonitor.service.page.HttpPhaseProbe(new SsrfGuard(appSettings)) {
            @Override public Phases measure(Target target, long hardDeadlineMs) { return new Phases(null, null, null, null, "ölçülemedi"); }
        };
        SecretCipher cipher = mock(SecretCipher.class);
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        var c = new PageSpeedCheckerService(core, stub, psl, appSettings, cipher);
        PageSpeedMonitor m = monitor("/");
        m.setMaxTtfbMs(5000);
        var r = c.check(m);
        assertThat(r.reachable()).isTrue();
        assertThat(r.serverTtfbMs()).isNull();
        assertThat(r.breached()).isEmpty();
        assertThat(r.undecided()).containsExactly(com.sitemonitor.service.page.PageSpeedRules.BREACH_TTFB);
        assertThat(r.status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("Zaman aşımı kuralı (2026-11-15): faz probu kontrolün bütçesinin İÇİNDE — en çok yarısı (max-check-seconds / 2)")
    void phaseProbeRunsInsideTheCheckBudget() {
        java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong();
        var stub = new com.sitemonitor.service.page.HttpPhaseProbe(new SsrfGuard(appSettings)) {
            @Override public Phases measure(Target target, long hardDeadlineMs) {
                seen.set(hardDeadlineMs);
                return new Phases(1, 1, null, 10, null);
            }
        };
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        var c = new PageSpeedCheckerService(core, stub, psl, appSettings, mock(SecretCipher.class));
        long before = System.currentTimeMillis();
        c.check(monitor("/"));
        long budgetMs = Math.max(10, appSettings.getInt("site.monitor.pagespeed.max-check-seconds", 120)) * 1000L;
        assertThat(seen.get()).as("son tarih verilmeli (0 = sınırsız olurdu)").isGreaterThan(before);
        assertThat(seen.get() - before).as("prob bütçenin en çok yarısını kullanır").isLessThanOrEqualTo(budgetMs / 2 + 50);
    }

    @Test
    @DisplayName("Tür bütçesi ve asgari boyut ölçümde değerlendirilir; aşım SLOW'dur (kesinti değil)")
    void typeBudgetsAndMinSize() {
        PageSpeedMonitor m = monitor("/");
        m.setMaxImgKb(5);            // 12 000 bayt görsel > 5 KB
        m.setMinPageKb(100_000);     // sayfa ~20 KB < 100 000 KB
        var r = checker.check(m);
        assertThat(r.status()).isEqualTo("SLOW");
        assertThat(r.reachable()).isTrue();
        assertThat(r.breached()).contains(com.sitemonitor.service.page.PageSpeedRules.BREACH_IMG,
                com.sitemonitor.service.page.PageSpeedRules.BREACH_MINSIZE);
    }

    // ── Ölçümün doğruluğu ────────────────────────────────────────────────────

    @Test
    @DisplayName("Sayfa ölçülür: HTML + yüklenen alt kaynaklar sayılır, a[href] linki SAYILMAZ")
    void measuresHtmlAndSubresourcesButNotHyperlinks() {
        var r = checker.check(monitor("/"));

        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.reachable()).isTrue();
        // 1 HTML + CSS + JS + IMG + tracker = 5 istek. a[href] hedefi DAHİL DEĞİL.
        assertThat(r.requestCount()).isEqualTo(5);
        assertThat(hits).doesNotContainKey("/baska-sayfa");   // hiç istenmedi (asıl kanıt)

        // Bayt: HTML + üç kaynak (tracker dış host, bu testte çözülemez → 0 bayt katkı).
        assertThat(r.totalBytes()).isGreaterThanOrEqualTo(CSS_BYTES + JS_BYTES + IMG_BYTES);
        assertThat(r.ttfbMs()).isNotNegative();
        assertThat(r.totalMs()).isGreaterThanOrEqualTo(r.ttfbMs());   // TTFB toplamın İÇİNDE
    }

    @Test
    @DisplayName("Her kaynağın kendi ağırlığı ölçülür (HEAD değil GET — HEAD bayt taşımaz)")
    void eachResourceIsWeighedIndividually() {
        var r = checker.check(monitor("/"));

        var css = r.resources().stream().filter(x -> x.url().endsWith("/a.css")).findFirst().orElseThrow();
        var img = r.resources().stream().filter(x -> x.url().endsWith("/a.png")).findFirst().orElseThrow();

        assertThat(css.bytes()).isEqualTo(CSS_BYTES);
        assertThat(img.bytes()).isEqualTo(IMG_BYTES);
        assertThat(css.type()).isEqualTo("CSS");
        assertThat(img.type()).isEqualTo("IMG");
        assertThat(css.failed()).isFalse();
    }

    @Test
    @DisplayName("Tracker hariç tutma AÇIKken tracker ne indirilir ne sayılır")
    void trackerExclusionRemovesResourceFromMeasurement() {
        PageSpeedMonitor m = monitor("/");
        m.setExcludeTrackers(true);

        var r = checker.check(m);

        assertThat(r.requestCount()).isEqualTo(4);   // 5 değil: tracker düştü
        assertThat(r.resources()).noneMatch(x -> x.url().contains("google-analytics"));
    }

    @Test
    @DisplayName("Kırık alt kaynak ölçümü DÜŞÜRMEZ; failed sayacına yazılır")
    void brokenResourceCountsAsFailedButMeasurementContinues() {
        var r = checker.check(monitor("/kirik"));

        assertThat(r.reachable()).isTrue();       // sayfa alındı → kesinti değil
        assertThat(r.failedCount()).isEqualTo(1);
        assertThat(r.resources()).anyMatch(x -> x.url().endsWith("/yok.js") && x.failed());
    }

    @Test
    @DisplayName("Alt kaynağı olmayan sayfa: tek istek, ölçüm yine geçerli")
    void pageWithoutResources() {
        var r = checker.check(monitor("/bos"));

        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.requestCount()).isEqualTo(1);
        assertThat(r.resources()).isEmpty();
    }

    // ── Olcum DOGRULUGU: tarayicinin GERCEKTEN indirdigi kadari ──────────────

    @Test
    @DisplayName("Responsive gorselin srcset varyantlari AYRI AYRI sayilmaz — tarayici birini indirir")
    void srcsetVariantsAreNotDoubleCounted() {
        var r = checker.check(monitor("/responsive"));

        var urls = r.resources().stream().map(PageSpeedCheckerService.Measured::url).toList();
        // img[src] alindi; ayni gorselin 320/640/1280 varyantlari ALINMADI.
        assertThat(urls).anyMatch(u -> u.endsWith("/a.png"));
        assertThat(urls).noneMatch(u -> u.contains("/r-320") || u.contains("/r-640") || u.contains("/r-1280"));
        // <picture><source srcset> de atlanir; indirilecek olan kardes img'dir.
        assertThat(urls).noneMatch(u -> u.contains("/p-a") || u.contains("/p-b"));
        assertThat(urls).anyMatch(u -> u.endsWith("/a.css.png"));
        // Iki gorsel elemani → iki indirme (HTML ile birlikte 3 istek).
        assertThat(r.requestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Sayfa Butunlugu srcset adaylarinin HEPSINI ister — paylasilan cekirdek onu bozmaz")
    void pageIntegrityStillSeesEveryCandidate() {
        // Ayni HTML, FULL secenegiyle: kirik bir varyant kacmasin diye hepsi envantere girer.
        var full = core.inventory(("<html><body><img src='/a.png' "
                + "srcset='/r-320.png 320w, /r-640.png 640w'></body></html>").getBytes(StandardCharsets.UTF_8),
                base + "/", base + "/", null, PageFetchCore.InventoryOptions.FULL);

        assertThat(full).extracting(PageFetchCore.Resource::url)
                .anyMatch(u -> u.contains("/r-320"))
                .anyMatch(u -> u.contains("/r-640"));
    }

    @Test
    @DisplayName("a[href] linkleri kaynak TAVANINI yemez — 600 linkli sayfada gercek kaynaklar olculur")
    void hyperlinksDoNotConsumeTheResourceBudget() {
        var r = checker.check(monitor("/linkli"));

        // 600 link envanter tavanini (500) doldursaydi CSS/JS hic olculmezdi.
        var urls = r.resources().stream().map(PageSpeedCheckerService.Measured::url).toList();
        assertThat(urls).anyMatch(u -> u.endsWith("/a.css"));
        assertThat(urls).anyMatch(u -> u.endsWith("/a.js"));
        assertThat(r.requestCount()).isEqualTo(3);          // HTML + CSS + JS
        // "Olcum kismi" uyarisi da yanlis yere cikmaz.
        assertThat(r.capped()).isFalse();
        assertThat(hits).doesNotContainKey("/l0");
    }

    @Test
    @DisplayName("TOPLAM bayt bütçesi gerçekten bağlar — tavan dolunca kalan kaynak İNDİRİLMEZ")
    void totalByteBudgetActuallyStopsDownloads() {
        // Butce kontrolu kapinin DISINDA yapildiginda etkisizdi: supplyAsync tum gorevleri hemen
        // kuyruga attigi icin hepsi spent=0 iken gecip kapida siraya giriyor, sonra SIRAYLA
        // hepsini indiriyordu. Yani 150 MB tavani hicbir seyi sinirlamiyordu.
        when(appSettings.getInt(eq("site.monitor.pagespeed.max-total-kb"), anyInt())).thenReturn(64);
        PageSpeedMonitor m = monitor("/agir");
        m.setResourceConcurrency(1);           // sirali → hangi kaynagin atlandigi belirleyici

        var r = checker.check(m);

        int downloaded = 0;
        for (int i = 1; i <= 4; i++) if (hits.containsKey("/h" + i + ".js")) downloaded++;
        assertThat(downloaded).isLessThan(4);                 // ASIL KANIT: ag istegi HIC atilmadi
        assertThat(r.resources()).hasSizeLessThan(4);
        assertThat(r.bytesTruncated()).isTrue();              // toplam "alt sinir" olarak isaretli
    }

    @Test
    @DisplayName("İstek sayısı sayfanın İSTEDİĞİ kadardır — atlanan kaynak sayıyı DÜŞÜRMEZ")
    void requestCountReflectsWhatThePageAsksFor() {
        // measured.size() kullanmak ters bir alarm davranisi uretiyordu: sayfa agirlastikca daha
        // cok kaynak atlanir, atlanan sayilmadigi icin raporlanan istek sayisi DUSER ve REQUESTS
        // esigi tam olarak EN KOTU sayfalarda atesleme yapmaz.
        when(appSettings.getInt(eq("site.monitor.pagespeed.max-total-kb"), anyInt())).thenReturn(64);
        PageSpeedMonitor m = monitor("/agir");
        m.setResourceConcurrency(1);

        var r = checker.check(m);

        assertThat(r.requestCount()).isEqualTo(5);             // HTML + 4 script (atlananlar DAHIL)
        assertThat(r.resources().size()).isLessThan(4);        // ...ama hepsi olculemedi
    }

    @Test
    @DisplayName("Bütçe dolduğunda REQUESTS eşiği yine ateşler (regresyonun asıl bedeli)")
    void requestsThresholdStillFiresWhenBudgetTruncates() {
        when(appSettings.getInt(eq("site.monitor.pagespeed.max-total-kb"), anyInt())).thenReturn(64);
        PageSpeedMonitor m = monitor("/agir");
        m.setResourceConcurrency(1);
        m.setMaxRequests(4);                                   // 5 istek > 4 → ihlal

        var r = checker.check(m);

        assertThat(r.breached()).contains("REQUESTS");
        assertThat(r.status()).isEqualTo("SLOW");
    }

    // ── Kesinti / yapılandırma ───────────────────────────────────────────────

    // ── Kirpma: rakamin ALT SINIR oldugunu soyle ─────────────────────────────

    @Test
    @DisplayName("Bayt tavanini asan kaynak KIRPILIR ve isaretlenir — sessizce eksik sayilmaz")
    void oversizeResourceIsTruncatedAndFlagged() {
        var r = checker.check(monitor("/devasa"));

        var big = r.resources().stream().filter(x -> x.url().endsWith("/dev.bin")).findFirst().orElseThrow();
        assertThat(big.truncated()).isTrue();
        assertThat(big.bytes()).isEqualTo(PageFetchCore.MAX_COUNT_BYTES);   // tavanda durdu
        assertThat(big.failed()).isFalse();                                 // kirpma HATA degil
    }

    @Test
    @DisplayName("Tek kirpik kaynak TUM olcumu 'alt sinir' yapar — esik eksik toplama kurulmasin")
    void oneTruncatedResourceMarksTheWholeMeasurement() {
        assertThat(checker.check(monitor("/devasa")).bytesTruncated()).isTrue();
        // Kirpik kaynagi olmayan sayfada bayrak KALKMAZ (aksi halde uyari her yerde gorunur,
        // gorununce de kimse okumaz).
        assertThat(checker.check(monitor("/")).bytesTruncated()).isFalse();
    }

    @Test
    @DisplayName("Sayfa 500 dönerse DOWN — alt kaynaklar HİÇ istenmez")
    void serverErrorIsDownAndSkipsResources() {
        var r = checker.check(monitor("/hata"));

        assertThat(r.status()).isEqualTo("DOWN");
        assertThat(r.reachable()).isFalse();
        assertThat(r.error()).contains("500");
        assertThat(r.resources()).isEmpty();
    }

    @Test
    @DisplayName("Şemasız/host'suz URL → CONFIG_ERROR (kesinti DEĞİL, alarm açılmaz) ve istek ATILMAZ")
    void unparsableUrlIsConfigErrorNotOutage() {
        PageSpeedMonitor m = monitor("/");
        m.setUrl("bu-bir-url-degil");

        var r = checker.check(m);

        assertThat(r.status()).isEqualTo("CONFIG_ERROR");
        assertThat(r.reachable()).isFalse();
        assertThat(r.requestCount()).isZero();
    }

    // ── Eşikler ölçümle birleşince ───────────────────────────────────────────

    @Test
    @DisplayName("Eşik aşılınca durum SLOW olur — ok/reachable YİNE true (kesinti değil)")
    void breachMakesItSlowNotDown() {
        PageSpeedMonitor m = monitor("/");
        m.setMaxPageKb(1);   // 1 KB: kesin aşılır

        var r = checker.check(m);

        assertThat(r.status()).isEqualTo("SLOW");
        assertThat(r.reachable()).isTrue();          // uptime'a KESİNTİ olarak işlemez
        assertThat(r.breached()).contains("SIZE");
    }

    @Test
    @DisplayName("Eşik konmamışsa ölçüm ne olursa olsun OK kalır")
    void noThresholdsMeansAlwaysOk() {
        var r = checker.check(monitor("/"));

        assertThat(r.breached()).isEmpty();
        assertThat(r.status()).isEqualTo("OK");
    }

    // ── Kimlik ve başlıklar ──────────────────────────────────────────────────

    @Test
    @DisplayName("Basic auth başlığı GERÇEKTEN gönderilir (ana sayfa + alt kaynak)")
    void basicAuthHeaderIsSent() {
        PageSpeedMonitor m = monitor("/kimlikli");
        m.setBasicAuthUser("kadir");
        m.setBasicAuthPassEnc("gizli");   // mock cipher: decrypt = kimlik

        checker.check(m);

        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("kadir:gizli".getBytes(StandardCharsets.UTF_8));
        assertThat(seenHeaders.get("/kimlikli").get("Authorization")).containsExactly(expected);
        // Alt kaynak da aynı kimlikle istenmeli — yoksa korumalı statikler 401 döner ve ölçüm eksik olur.
        assertThat(seenHeaders.get("/a.png").get("Authorization")).containsExactly(expected);
    }

    @Test
    @DisplayName("DNT kapalıyken başlık HİÇ gönderilmez, açıkken 1 gider")
    void dntHeaderIsOptIn() {
        checker.check(monitor("/kimlikli"));
        assertThat(seenHeaders.get("/kimlikli").get("Dnt")).isNull();

        PageSpeedMonitor m = monitor("/kimlikli");
        m.setSendDnt(true);
        checker.check(m);
        assertThat(seenHeaders.get("/kimlikli").get("Dnt")).containsExactly("1");
    }

    @Test
    @DisplayName("Özel User-Agent uygulanır; verilmezse kendini tanıtan varsayılan gider")
    void userAgentIsApplied() {
        checker.check(monitor("/kimlikli"));
        assertThat(seenHeaders.get("/kimlikli").get("User-agent").get(0))
                .isEqualTo(PageSpeedMonitor.DEFAULT_UA);

        PageSpeedMonitor m = monitor("/kimlikli");
        m.setUserAgent("Ozel-Tarayici/9.9");
        checker.check(m);
        assertThat(seenHeaders.get("/kimlikli").get("User-agent").get(0)).isEqualTo("Ozel-Tarayici/9.9");
    }

    @Test
    @DisplayName("Özel başlıklar gönderilir; çekirdeğin AYRILMIŞ başlıkları ezilemez")
    void customHeadersAreSentButReservedOnesAreProtected() {
        PageSpeedMonitor m = monitor("/kimlikli");
        m.setCustomHeadersEnc("X-Api-Key: abc123\nUser-Agent: SAHTE");   // UA ezme denemesi

        checker.check(m);

        assertThat(seenHeaders.get("/kimlikli").get("X-api-key").get(0)).isEqualTo("abc123");
        // UA ayrılmış: özel başlık onu DEĞİŞTİREMEZ (kimliğimiz hedefin log'unda hep doğru görünsün).
        assertThat(seenHeaders.get("/kimlikli").get("User-agent").get(0)).isEqualTo(PageSpeedMonitor.DEFAULT_UA);
    }

    @Test
    @DisplayName("Kaydetmeden deneme eşik DEĞERLENDİRMEZ — kullanıcı önce ham ölçümü görsün")
    void testModeSkipsThresholdEvaluation() {
        PageSpeedMonitor draft = monitor("/");
        draft.setMaxPageKb(1);   // kaydedilmiş olsa SLOW olurdu

        var r = checker.test(draft);

        assertThat(r.breached()).isEmpty();
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.totalBytes()).isPositive();
    }

    @Test
    @DisplayName("Şifreli parola çözülemezse ölçüm kimliksiz DEVAM eder (kontrol tamamen düşmez)")
    void undecryptablePasswordDoesNotKillTheCheck() {
        SecretCipher boom = mock(SecretCipher.class);
        when(boom.decrypt(eq("bozuk"))).thenThrow(new IllegalStateException("anahtar degisti"));
        AppSettingsService settings = mock(AppSettingsService.class);
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        var c2 = new PageSpeedCheckerService(core, phaseProbe, psl, settings, boom);

        PageSpeedMonitor m = monitor("/kimlikli");
        m.setBasicAuthUser("kadir");
        m.setBasicAuthPassEnc("bozuk");

        var r = c2.check(m);

        assertThat(r.reachable()).isTrue();   // ölçüm yapıldı
    }
}
