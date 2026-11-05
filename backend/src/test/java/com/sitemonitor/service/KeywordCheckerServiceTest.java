package com.sitemonitor.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeywordCheckerServiceTest {

    @Test
    @DisplayName("evaluate: ≥/≤/=/>/< operatörleri doğru değerlendirir")
    void evaluateOperators() {
        assertThat(KeywordCheckerService.evaluate(3, "GTE", 3)).isTrue();
        assertThat(KeywordCheckerService.evaluate(2, "GTE", 3)).isFalse();
        assertThat(KeywordCheckerService.evaluate(2, "LTE", 2)).isTrue();
        assertThat(KeywordCheckerService.evaluate(3, "LTE", 2)).isFalse();
        assertThat(KeywordCheckerService.evaluate(3, "EQ", 3)).isTrue();
        assertThat(KeywordCheckerService.evaluate(2, "EQ", 3)).isFalse();
        assertThat(KeywordCheckerService.evaluate(4, "GT", 3)).isTrue();
        assertThat(KeywordCheckerService.evaluate(3, "GT", 3)).isFalse();
        assertThat(KeywordCheckerService.evaluate(2, "LT", 3)).isTrue();
        assertThat(KeywordCheckerService.evaluate(3, "LT", 3)).isFalse();
        // "bulunmamalı" = LTE 0
        assertThat(KeywordCheckerService.evaluate(0, "LTE", 0)).isTrue();
        assertThat(KeywordCheckerService.evaluate(1, "LTE", 0)).isFalse();
        // null operatör → GTE varsayımı
        assertThat(KeywordCheckerService.evaluate(1, null, 1)).isTrue();
    }

    @Test
    @DisplayName("opPhrase: operatör + eşik → Türkçe ifade")
    void opPhrase() {
        assertThat(KeywordCheckerService.opPhrase("GTE", 3)).isEqualTo("en az 3 kez");
        assertThat(KeywordCheckerService.opPhrase("LTE", 2)).isEqualTo("en fazla 2 kez");
        assertThat(KeywordCheckerService.opPhrase("EQ", 1)).isEqualTo("tam olarak 1 kez");
        assertThat(KeywordCheckerService.opPhrase("GT", 5)).isEqualTo("5 kezden fazla");
        assertThat(KeywordCheckerService.opPhrase("LT", 4)).isEqualTo("4 kezden az");
    }

    // ── check() — gerçek HTTP yolu (in-process sunucu) ───────────────────────

    private static HttpServer serve(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (var os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        return server;
    }

    private static KeywordCheckerService newChecker() {
        // Test 127.0.0.1'e bağlanır → SsrfGuard izin verici (loopback + iç ağ). Metadata/link-local yine bloklu.
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        KeywordCheckerService svc = new KeywordCheckerService(new SsrfGuard(s));
        svc.init();   // @PostConstruct — HttpClient kur
        return svc;
    }

    @Test
    @DisplayName("check: şemasız URL yapılandırma hatası (config_error) — istek atılmaz, kesinti sayılmaz")
    void check_schemalessUrl_configError() {
        Map<String, Object> r = newChecker().check("www.axess.com.tr", "abc", 3000);
        assertThat(r.get("config_error")).isEqualTo(true);
        assertThat(r.get("found")).isEqualTo(false);
        assertThat(r.get("error")).isEqualTo(com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG);
        assertThat(r.get("http_status")).isNull();     // hiç istek gitmedi
    }

    @Test
    @DisplayName("check: case-insensitive non-overlapping sayım + http_status + snippet")
    void check_countsAndMeta() throws IOException {
        HttpServer server = serve("<p>ABCabc abc tail</p>");   // 'abc' → 3 kez (3,6,10)
        try {
            Map<String, Object> r = newChecker()
                    .check("http://127.0.0.1:" + server.getAddress().getPort() + "/", "abc", 3000);
            assertThat(r.get("found")).isEqualTo(true);
            assertThat(r.get("count")).isEqualTo(3);
            assertThat(r.get("http_status")).isEqualTo(200);
            assertThat(r.get("response_ms")).isInstanceOf(Long.class);
            assertThat(r.get("snippet")).isInstanceOf(String.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("check: kelime yoksa found=false, count=0")
    void check_notFound() throws IOException {
        HttpServer server = serve("<p>nothing here</p>");
        try {
            Map<String, Object> r = newChecker()
                    .check("http://127.0.0.1:" + server.getAddress().getPort() + "/", "example", 3000);
            assertThat(r.get("found")).isEqualTo(false);
            assertThat(r.get("count")).isEqualTo(0);
            assertThat(r.get("http_status")).isEqualTo(200);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("check: caseSensitive=true büyük/küçük harf DUYARLI sayar; false duyarsız")
    void check_caseSensitive() throws IOException {
        HttpServer server = serve("<p>SUCCESS ok success done</p>");   // 'SUCCESS' 1, 'success' 1
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            KeywordCheckerService svc = newChecker();
            // Duyarsız (varsayılan): SUCCESS + success → 2
            Map<String, Object> insensitive = svc.check(url, "success", 3000, null, false);
            assertThat(insensitive.get("count")).isEqualTo(2);
            // Duyarlı: yalnız küçük 'success' → 1
            Map<String, Object> sensitiveLower = svc.check(url, "success", 3000, null, true);
            assertThat(sensitiveLower.get("count")).isEqualTo(1);
            // Duyarlı: yalnız büyük 'SUCCESS' → 1
            Map<String, Object> sensitiveUpper = svc.check(url, "SUCCESS", 3000, null, true);
            assertThat(sensitiveUpper.get("count")).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("N1: gövdesini bitirmeyen uç (SSE kalp atışı) keyword sweep'ini DONDURMAZ — bütçede 'zaman aşımı' hatası")
    void check_stalledBody_timesOut() throws Exception {
        HttpServer server = HttpCheckerServiceTest.streamingServer();   // 200 + saniyede 10 bayt, sonsuz
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            long t0 = System.nanoTime();
            Map<String, Object> r = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(10), () -> newChecker().check(url, "hedef", 2000));
            long ms = (System.nanoTime() - t0) / 1_000_000L;

            assertThat(r.get("found")).isEqualTo(false);
            assertThat((String) r.get("error")).contains("tamamlanmadı");
            assertThat(ms).isLessThan(8_000L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("check: bağlantı reddi → found=false, count=0, error döner")
    void check_connectionError() {
        Map<String, Object> r = newChecker().check("http://127.0.0.1:1/", "x", 500);
        assertThat(r.get("found")).isEqualTo(false);
        assertThat(r.get("count")).isEqualTo(0);
        assertThat(r).containsKey("error");
    }

    // ── Yönlendirme güvenliği (her hop SsrfGuard'dan geçer) ──────────────────

    /** Verilen hedefe 302 ile yönlendiren sunucu. */
    private static HttpServer redirectTo(String location) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("Yönlendirme ZİNCİRİ takip edilir — kelime son hop'ta bulunur (davranış korunur)")
    void check_followsRedirectChain() throws IOException {
        HttpServer target = serve("<p>redirected abc</p>");
        HttpServer entry = redirectTo("http://127.0.0.1:" + target.getAddress().getPort() + "/");
        try {
            Map<String, Object> r = newChecker()
                    .check("http://127.0.0.1:" + entry.getAddress().getPort() + "/", "abc", 3000);
            assertThat(r.get("found")).isEqualTo(true);
            assertThat(r.get("count")).isEqualTo(1);
            assertThat(r.get("http_status")).isEqualTo(200);
        } finally {
            entry.stop(0);
            target.stop(0);
        }
    }

    @Test
    @DisplayName("SSRF: cloud-metadata ucuna YÖNLENDİRME engellenir — gövde/snippet DÖNMEZ")
    void check_redirectToMetadata_blocked() throws IOException {
        // Redirect.NORMAL kullanılırken yalnız İLK host doğrulanıyordu; hedef sunucu bizi
        // 169.254.169.254'e yönlendirip yanıt gövdesini snippet olarak GERİ ALDIRABİLİYORDU.
        HttpServer entry = redirectTo("http://169.254.169.254/latest/meta-data/");
        try {
            Map<String, Object> r = newChecker()
                    .check("http://127.0.0.1:" + entry.getAddress().getPort() + "/", "ami-id", 3000);
            assertThat(r.get("found")).isEqualTo(false);
            assertThat(r.get("count")).isEqualTo(0);
            assertThat((String) r.get("error")).contains("cloud-metadata");
            assertThat(r).doesNotContainKey("snippet");
            assertThat(r).doesNotContainKey("http_status");   // son hop hiç tamamlanmadı
        } finally {
            entry.stop(0);
        }
    }

    @Test
    @DisplayName("SSRF: http/https DIŞI şemaya yönlendirme takip edilmez — 3xx olduğu gibi döner")
    void check_redirectToFileScheme_notFollowed() throws IOException {
        HttpServer entry = redirectTo("file:///etc/passwd");
        try {
            Map<String, Object> r = newChecker()
                    .check("http://127.0.0.1:" + entry.getAddress().getPort() + "/", "root", 3000);
            assertThat(r.get("found")).isEqualTo(false);
            assertThat(r.get("http_status")).isEqualTo(302);   // takip edilmedi, hata da değil
            assertThat(r).doesNotContainKey("snippet");
        } finally {
            entry.stop(0);
        }
    }

    @Test
    @DisplayName("SSRF: yönlendirme DÖNGÜSÜ hop sınırında durur (sonsuz takip yok)")
    void check_redirectLoop_stopsAtHopLimit() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", "/next");   // kendine döner
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        try {
            Map<String, Object> r = newChecker()
                    .check("http://127.0.0.1:" + server.getAddress().getPort() + "/", "x", 3000);
            assertThat(r.get("found")).isEqualTo(false);
            assertThat((String) r.get("error")).contains("yönlendirme");
        } finally {
            server.stop(0);
        }
    }

    // ── CHECKS-3 (2026-10-22): "İ" küçültülünce 2 karakter olur — alıntı konumu özgün metne çevrilmeli ──────────

    @Test
    @DisplayName("CHECKS-3: 100 × 'İ' + abc + acmebank (harf duyarsız) → bulundu, hata YOK, alıntıda anahtar kelime var")
    void check_turkishDottedI_beforeMatch_neverFailsAndSnippetHasKeyword() throws IOException {
        HttpServer server = serve("İ".repeat(100) + "abc" + "acmebank");
        try {
            Map<String, Object> r = newChecker()
                    .check("http://127.0.0.1:" + server.getAddress().getPort() + "/", "acmebank", 3000);
            assertThat(r.get("error")).isNull();
            assertThat(r.get("found")).isEqualTo(true);
            assertThat(r.get("count")).isEqualTo(1);
            assertThat((String) r.get("snippet")).contains("acmebank");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("CHECKS-3: eşleşmeden önceki 'İ'ler alıntıyı kaydırmaz — uzun gövdede alıntı anahtar kelimeyi içerir")
    void check_turkishDottedI_snippetNotShifted() throws IOException {
        String body = "GİRİŞ İLETİŞİM BİREYSEL İŞLEMLER ".repeat(6) + "DURUM: TAMAM acmebank " + "x".repeat(300);
        HttpServer server = serve(body);
        try {
            Map<String, Object> r = newChecker()
                    .check("http://127.0.0.1:" + server.getAddress().getPort() + "/", "acmebank", 3000);
            assertThat(r.get("error")).isNull();
            assertThat((String) r.get("snippet")).contains("acmebank").contains("DURUM");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("CHECKS-3 / kw §4.1: anahtar kelimenin kendisi 'İ' içeriyor (İLETİŞİM) → katlama uzunluk korur, alıntı tam kelimeyle")
    void snippet_keywordWithDottedI() {
        String body = "ana sayfa İLETİŞİM formu";
        com.sitemonitor.service.keyword.KeywordFold.Count c =
                com.sitemonitor.service.keyword.KeywordFold.count(body, "iletişim", false, 10);
        assertThat(c.firstIndex()).isEqualTo(body.indexOf("İLETİŞİM"));
        assertThat(KeywordCheckerService.snippet(body, c.firstIndex(), "iletişim".length())).isEqualTo(body);
    }

    @Test
    @DisplayName("snippet — konum aynen; geçersiz konum boş alıntı (istisna yok); ±50 karakter ve ≤ 200, boşluk indirgenir")
    void snippet_bounds() {
        String body = "abc keyword def";
        assertThat(KeywordCheckerService.snippet(body, 4, 7)).isEqualTo(body);
        assertThat(KeywordCheckerService.snippet(body, -1, 7)).isEmpty();
        assertThat(KeywordCheckerService.snippet(null, 0, 1)).isEmpty();
        assertThat(KeywordCheckerService.snippet(body, 999, 1)).isEmpty();
        String long1 = "x".repeat(100) + "  orta \n\n kelime  " + "y".repeat(400);
        String sn = KeywordCheckerService.snippet(long1, long1.indexOf("kelime"), 6);
        assertThat(sn).contains("orta kelime").hasSizeLessThanOrEqualTo(200);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  kw sözleşmesi (2026-11-05) — gerçek sunucuda: katlama, karakter kümesi, kapsam, istek, durum / son URL kuralı, süre
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Yanıtı ve gelen isteğin ayrıntısını kaydeden küçük sunucu. */
    private static final class Recorder {
        final java.util.List<String> methods = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<java.util.Map<String, java.util.List<String>>> headers = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    private static void record(Recorder rec, com.sun.net.httpserver.HttpExchange ex) throws IOException {
        rec.methods.add(ex.getRequestMethod());
        rec.paths.add(ex.getRequestURI().getPath());
        java.util.Map<String, java.util.List<String>> h = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ex.getRequestHeaders().forEach((k, v) -> h.put(k, new java.util.ArrayList<>(v)));
        rec.headers.add(h);
        rec.bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (var os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    private static com.sitemonitor.service.keyword.KeywordCheckSpec.Builder spec(String url, String kw) {
        return com.sitemonitor.service.keyword.KeywordCheckSpec.builder(url, kw).timeoutMs(3000).diagnose(true);
    }

    @Test
    @DisplayName("kw B3: harf duyarsız aramada Türkçe İ/ı doğru eşleşir — 'IŞIK' sayfası 'ışık' kelimesini bulur, 'ok' true")
    void turkishFold_realServer() throws IOException {
        HttpServer server = serve("<h1>IŞIK KAMPANYASI</h1><p>İletişim: 444</p>");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Map<String, Object> r = newChecker().check(spec(url, "ışık").build());
            assertThat(r.get("count")).isEqualTo(1);
            assertThat(r.get("ok")).isEqualTo(true);
            assertThat(newChecker().check(spec(url, "iletişim").build()).get("count")).isEqualTo(1);
            assertThat(newChecker().check(spec(url, "ISTANBUL").build()).get("ok")).isEqualTo(false);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("kw B4: windows-1254 sayfa (Content-Type) doğru çözülür — 'Giriş' bulunur, decoded_charset yazılır, charset bildirileni tutar")
    void windows1254Page() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> respond(ex, 200, "text/html; charset=ISO-8859-9",
                "<p>Giriş yapın — Şifremi unuttum</p>".getBytes(java.nio.charset.Charset.forName("windows-1254"))));
        server.start();
        try {
            Map<String, Object> r = newChecker().check(spec("http://127.0.0.1:" + server.getAddress().getPort() + "/", "giriş").build());
            assertThat(r.get("count")).isEqualTo(1);
            assertThat(r.get("ok")).isEqualTo(true);
            assertThat(r.get("decoded_charset")).isEqualTo("windows-1254");
            assertThat(r.get("charset")).isEqualTo("ISO-8859-9");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("kw A3: VISIBLE kapsam — betik içindeki yasak kelime sayılmaz, etiketlerle bölünmüş ifade bulunur; RAW bugünkü gibi")
    void visibleScope() throws IOException {
        HttpServer server = serve("<script>var hata='Sistem hatası';</script><p>Hoş <b>geldiniz</b></p>");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Map<String, Object> raw = newChecker().check(spec(url, "Sistem hatası").condition("LTE", 0).build());
            assertThat(raw.get("ok")).isEqualTo(false);
            assertThat(raw.get("match_scope")).isEqualTo("RAW");
            Map<String, Object> vis = newChecker().check(spec(url, "Sistem hatası").condition("LTE", 0).matchScope("VISIBLE").build());
            assertThat(vis.get("ok")).isEqualTo(true);
            assertThat(vis.get("match_scope")).isEqualTo("VISIBLE");
            assertThat(newChecker().check(spec(url, "Hoş geldiniz").matchScope("VISIBLE").build()).get("count")).isEqualTo(1);
            assertThat(newChecker().check(spec(url, "Hoş geldiniz").build()).get("count")).isEqualTo(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("kw B-2: POST + gövde + içerik türü yalnız ilk host'a; 303 yönlendirmede GET'e döner ve gövde gönderilmez")
    void postBodyAndRedirectMethod() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", ex -> {
            record(rec, ex);
            ex.getResponseHeaders().add("Location", "/result");
            respond(ex, 303, null, new byte[0]);
        });
        server.createContext("/result", ex -> {
            record(rec, ex);
            respond(ex, 200, "text/html", "<p>Sonuç bulundu</p>".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        try {
            Map<String, Object> r = newChecker().check(spec("http://127.0.0.1:" + server.getAddress().getPort() + "/submit", "Sonuç")
                    .method("POST").requestBody("{\"q\":\"x\"}").requestContentType("application/json").build());
            assertThat(r.get("ok")).isEqualTo(true);
            assertThat(r.get("method")).isEqualTo("POST");
            assertThat(rec.methods).containsExactly("POST", "GET");
            assertThat(rec.bodies.get(0)).isEqualTo("{\"q\":\"x\"}");
            assertThat(rec.headers.get(0).get("Content-Type")).containsExactly("application/json");
            assertThat(rec.bodies.get(1)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("kw B-3 / B8: Basic auth + özel başlık yalnız ilk host'a; aynı adlı özel başlık UA'yı EZER (ikinci değer EKLENMEZ)")
    void basicAuthAndHeaderOverride() throws IOException {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        HttpServer other = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getByName("localhost"), 0), 0);
        other.createContext("/", ex -> { record(b, ex); respond(ex, 200, "text/plain", "hedef tamam".getBytes(StandardCharsets.UTF_8)); });
        other.start();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // başka host'a yönlendirme: localhost (farklı host adı) → ilk host'un sırları gitmemeli
        origin.createContext("/", ex -> {
            record(a, ex);
            ex.getResponseHeaders().add("Location", "http://localhost:" + other.getAddress().getPort() + "/");
            respond(ex, 302, null, new byte[0]);
        });
        origin.start();
        try {
            Map<String, Object> r = newChecker().check(spec("http://127.0.0.1:" + origin.getAddress().getPort() + "/", "tamam")
                    .basicAuthUser("izleme").basicAuthPass("s3cr3t!")
                    .customHeaders("User-Agent: Ozel/1.0\nX-Api-Key: abc123")
                    .build());
            assertThat(r.get("ok")).isEqualTo(true);
            var first = a.headers.get(0);
            assertThat(first.get("User-Agent")).containsExactly("Ozel/1.0");   // ezildi, eklenmedi
            assertThat(first.get("Authorization")).containsExactly("Basic "
                    + java.util.Base64.getEncoder().encodeToString("izleme:s3cr3t!".getBytes(StandardCharsets.UTF_8)));
            assertThat(first.get("X-Api-Key")).containsExactly("abc123");
            var second = b.headers.get(0);
            assertThat(second.get("Authorization")).isNull();
            assertThat(second.get("X-Api-Key")).isNull();
            assertThat(second.get("User-Agent")).containsExactly(com.sitemonitor.service.keyword.KeywordRequestRules.DEFAULT_USER_AGENT);
        } finally {
            origin.stop(0);
            other.stop(0);
        }
    }

    @Test
    @DisplayName("kw B-4: UA kipleri — DEFAULT bugünkü, BROWSER tarayıcı dizgesi, CUSTOM özel değer")
    void userAgentModes() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { record(rec, ex); respond(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)); });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            newChecker().check(spec(url, "ok").build());
            newChecker().check(spec(url, "ok").userAgentMode("BROWSER").build());
            newChecker().check(spec(url, "ok").userAgentMode("CUSTOM").userAgent("Probe/9").build());
            assertThat(rec.headers.get(0).get("User-Agent")).containsExactly("SiteMonitor-KeywordMonitor/1.0");
            assertThat(rec.headers.get(1).get("User-Agent")).containsExactly(com.sitemonitor.service.keyword.KeywordRequestRules.BROWSER_USER_AGENT);
            assertThat(rec.headers.get(2).get("User-Agent")).containsExactly("Probe/9");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("kw B-5: yönlendirme takibi KAPALI — 3xx yanıtın kendisi değerlendirilir, hedefe gidilmez, neden REDIRECT_NOT_FOLLOWED")
    void followRedirectsOff() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            record(rec, ex);
            if ("/a".equals(ex.getRequestURI().getPath())) {
                ex.getResponseHeaders().add("Location", "/b");
                respond(ex, 302, "text/html", "<p>Moved</p>".getBytes(StandardCharsets.UTF_8));
            } else respond(ex, 200, "text/html", "<p>Hedef</p>".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/a";
            Map<String, Object> off = newChecker().check(spec(url, "Hedef").followRedirects(false).build());
            assertThat(off.get("http_status")).isEqualTo(302);
            assertThat(off.get("ok")).isEqualTo(false);
            assertThat(off.get("failure_reason")).isEqualTo("REDIRECT_NOT_FOLLOWED");
            assertThat(rec.paths).containsExactly("/a");
            Map<String, Object> on = newChecker().check(spec(url, "Hedef").build());
            assertThat(on.get("ok")).isEqualTo(true);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("kw B-1: durum kuralı — 503 bakım sayfası 'içermemeli' kuralında artık sağlıksız (HTTP_STATUS); kural yoksa bugünkü (sağlıklı)")
    void expectedStatusRule() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> respond(ex, 503, "text/html", "<p>Bakımdayız</p>".getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Map<String, Object> legacy = newChecker().check(spec(url, "Sistem hatası").condition("LTE", 0).build());
            assertThat(legacy.get("ok")).isEqualTo(true);
            assertThat(legacy.get("status_accepted")).isNull();
            Map<String, Object> rule = newChecker().check(spec(url, "Sistem hatası").condition("LTE", 0).expectedStatus("2xx,3xx").build());
            assertThat(rule.get("ok")).isEqualTo(false);
            assertThat(rule.get("status_accepted")).isEqualTo(false);
            assertThat(rule.get("failure_reason")).isEqualTo("HTTP_STATUS");
            assertThat((String) rule.get("failure_detail")).startsWith("Kabul edilen: 2xx,3xx; gelen: 503");
            assertThat(rule.get("error")).isNull();   // durum uyuşmazlığı "hata" değil (ağ sınıfı değil)
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("kw B-5: son URL kuralı — giriş sayfasına yönlendirme FINAL_URL_MISMATCH; beklenen yolda sağlıklı")
    void expectedFinalUrlRule() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if ("/panel".equals(ex.getRequestURI().getPath())) {
                ex.getResponseHeaders().add("Location", "/login?next=/panel");
                respond(ex, 302, null, new byte[0]);
            } else respond(ex, 200, "text/html", "<form><input type=password></form><p>Panel</p>".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/panel";
            Map<String, Object> r = newChecker().check(spec(url, "Panel").expectedFinalUrl("/panel").build());
            assertThat(r.get("ok")).isEqualTo(false);
            assertThat(r.get("final_url_ok")).isEqualTo(false);
            assertThat(r.get("failure_reason")).isEqualTo("FINAL_URL_MISMATCH");
            assertThat(newChecker().check(spec(url, "Panel").expectedFinalUrl("/login").build()).get("ok")).isEqualTo(true);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("kw B13: saklı 120 sn zaman aşımı çalışmada 60 sn'ye kırpılır; tanım yoksa 10 sn")
    void timeoutClamp() {
        assertThat(spec("http://x", "a").timeoutMs(120_000).build().effectiveTimeoutMs()).isEqualTo(60_000);
        assertThat(com.sitemonitor.service.keyword.KeywordCheckSpec.builder("http://x", "a").build().effectiveTimeoutMs()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("istek kurucusu: eklentisiz GET bugünküyle aynı (yalnız UA); POST gövdesi yalnız applyExtras iken")
    void buildRequestShape() {
        java.net.http.HttpRequest plain = KeywordCheckerService.buildRequest(java.net.URI.create("http://x.example/"), "GET", 1000,
                com.sitemonitor.service.keyword.KeywordRequestRules.DEFAULT_USER_AGENT,
                com.sitemonitor.service.http.HttpRequestOptions.NONE, true);
        assertThat(plain.method()).isEqualTo("GET");
        assertThat(plain.headers().map().keySet()).containsExactly("User-Agent");
        var opts = new com.sitemonitor.service.http.HttpRequestOptions("X-A: 1", "u", "p", "body", null, null, null);
        java.net.http.HttpRequest cross = KeywordCheckerService.buildRequest(java.net.URI.create("http://y.example/"), "POST", 1000,
                "UA", opts, false);
        assertThat(cross.headers().firstValue("Authorization")).isEmpty();
        assertThat(cross.headers().firstValue("X-A")).isEmpty();
        assertThat(cross.bodyPublisher().orElseThrow().contentLength()).isEqualTo(0);
        java.net.http.HttpRequest first = KeywordCheckerService.buildRequest(java.net.URI.create("http://y.example/"), "POST", 1000,
                "UA", opts, true);
        assertThat(first.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(first.bodyPublisher().orElseThrow().contentLength()).isEqualTo(4);
    }

    @Test
    @DisplayName("alıntı süzgeci: Basic auth parolası ve kimliğin base64 hâli sır listesinde (yansıtan sayfa sızdırmaz)")
    void secretValuesIncludeBasicCredential() {
        com.sitemonitor.service.keyword.KeywordCheckSpec spec = com.sitemonitor.service.keyword.KeywordCheckSpec
                .builder("https://x.example.com/", "a").basicAuthUser("izleme").basicAuthPass("Gizli-77")
                .customHeaders("X-Api-Key: anahtar-123456").build();
        String b64 = java.util.Base64.getEncoder().encodeToString("izleme:Gizli-77".getBytes(StandardCharsets.UTF_8));
        assertThat(KeywordCheckerService.secretValues(spec)).contains("Gizli-77", b64, "anahtar-123456");
        assertThat(KeywordCheckerService.secretValues(com.sitemonitor.service.keyword.KeywordCheckSpec
                .builder("https://x.example.com/", "a").build())).isEmpty();
    }
}
