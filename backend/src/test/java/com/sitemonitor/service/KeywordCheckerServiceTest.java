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
}
