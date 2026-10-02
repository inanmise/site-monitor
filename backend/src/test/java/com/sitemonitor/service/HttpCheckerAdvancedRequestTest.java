package com.sitemonitor.service;

import com.sitemonitor.service.http.HttpRequestOptions;
import com.sitemonitor.service.http.JsonAssertion;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP izlemesinin gelişmiş isteği UÇTAN UCA (yerel HttpServer, 2026-10-01, onaylı öneri 9): sunucunun GERÇEKTEN ne
 * aldığı (yöntem, başlık, gövde) ve JSON doğrulamasının kararı. Eklentisiz yolun eskisiyle aynı istek ürettiği de burada
 * sunucu tarafından doğrulanır.
 */
class HttpCheckerAdvancedRequestTest {

    /** Sunucunun gördüğü istek. */
    record Seen(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            List<String> v = headers.get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    private HttpServer server;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;
    private volatile String responseBody = "{\"status\":\"ok\",\"items\":[{\"id\":7}]}";
    private HttpCheckerService svc;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] reqBody = ex.getRequestBody().readAllBytes();
            Map<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            ex.getRequestHeaders().forEach((k, v) -> h.put(k, List.copyOf(v)));
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), h, new String(reqBody, StandardCharsets.UTF_8)));
            if (ex.getRequestURI().getPath().equals("/redirect")) {
                // başka HOST adına (istek "localhost"a geldi → "127.0.0.1"e) yönlendir — sırlar oraya gitmemeli
                ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/landed");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn("");
        when(settings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(settings.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        svc = new HttpCheckerService(new TrustEvaluator(settings), mock(CaAutoPinService.class), new SsrfGuard(settings));
        svc.init();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private Map<String, Object> check(String path, String method, HttpRequestOptions o) {
        return svc.check(base + path, method, "200-399", 5000, false, true, false, o);
    }

    @Test
    @DisplayName("eklentisiz: 7 argümanlı giriş ile NONE aynı isteği gönderir — ek başlık yok, POST gövdesiz")
    void none_sameRequestAsLegacy() {
        for (String method : new String[]{"GET", "POST"}) {
            seen.clear();
            Map<String, Object> legacy = svc.check(base + "/x", method, "200-399", 5000, false, true, false);
            Map<String, Object> none = check("/x", method, HttpRequestOptions.NONE);
            assertThat(seen).hasSize(2);
            Seen a = seen.get(0), b = seen.get(1);
            assertThat(b.method()).isEqualTo(a.method()).isEqualTo(method);
            // h2c yükseltme başlıkları bağlantı durumuna göre ilk istekte görünüp ikincide görünmeyebilir — onlar hariç
            assertThat(endToEnd(b.headers())).isEqualTo(endToEnd(a.headers()));
            assertThat(b.header("Authorization")).isNull();
            assertThat(b.header("Content-Type")).isNull();
            assertThat(b.body()).isEmpty();
            assertThat(none.get("ok")).isEqualTo(legacy.get("ok")).isEqualTo(true);
            assertThat(none).doesNotContainKey("json_assertion_failed");
        }
    }

    @Test
    @DisplayName("eklentisiz yol gövdeyi DOĞRULAMAZ: JSON olmayan 200 yanıtı yine sağlıklı (karar yalnız durum kodu)")
    void none_doesNotInspectBody() {
        responseBody = "<html>bakım</html>";
        Map<String, Object> r = check("/x", "GET", HttpRequestOptions.NONE);
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("error")).isNull();
    }

    @Test
    @DisplayName("POST: gövde + içerik türü + özel başlık + Basic auth sunucuya ulaşır")
    void post_bodyHeadersAuth_delivered() {
        HttpRequestOptions o = new HttpRequestOptions("X-Api-Key: k-123", "izleme", "p@ss",
                "{\"probe\":true}", null, null, null);
        Map<String, Object> r = check("/probe", "POST", o);
        assertThat(r.get("ok")).isEqualTo(true);
        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("POST");
        assertThat(s.body()).isEqualTo("{\"probe\":true}");
        assertThat(s.header("Content-Type")).isEqualTo("application/json");
        assertThat(s.header("X-Api-Key")).isEqualTo("k-123");
        assertThat(s.header("Authorization")).isEqualTo(
                "Basic " + Base64.getEncoder().encodeToString("izleme:p@ss".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("GET seçilmişken kayıtlı gövde GÖNDERİLMEZ")
    void get_bodyNotSent() {
        Map<String, Object> r = check("/x", "GET", new HttpRequestOptions(null, null, null, "{\"a\":1}", null, null, null));
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(seen.get(0).method()).isEqualTo("GET");
        assertThat(seen.get(0).body()).isEmpty();
        assertThat(seen.get(0).header("Content-Type")).isNull();
    }

    /** Uçtan uca başlık adları — bağlantı/yükseltme (hop-by-hop) başlıkları hariç, küçük harf. */
    private static java.util.Set<String> endToEnd(Map<String, List<String>> headers) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String k : headers.keySet()) {
            String l = k.toLowerCase(java.util.Locale.ROOT);
            if (!l.equals("connection") && !l.equals("upgrade") && !l.equals("http2-settings")) out.add(l);
        }
        return out;
    }

    @Test
    @DisplayName("başka host'a yönlendirmede başlık ve Basic auth DÜŞER (yalnız ilk host alır)")
    void redirectToOtherHost_dropsSecrets() {
        HttpRequestOptions o = new HttpRequestOptions("X-Api-Key: k-123", "izleme", "p@ss", null, null, null, null);
        Map<String, Object> r = svc.check("http://localhost:" + server.getAddress().getPort() + "/redirect",
                "GET", "200-399", 5000, false, true, false, o);
        assertThat(r.get("http_status")).isEqualTo(200);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).header("X-Api-Key")).isEqualTo("k-123");
        assertThat(seen.get(0).header("Authorization")).isNotNull();
        assertThat(seen.get(1).path()).isEqualTo("/landed");
        assertThat(seen.get(1).header("X-Api-Key")).isNull();
        assertThat(seen.get(1).header("Authorization")).isNull();
    }

    @Test
    @DisplayName("JSON doğrulaması: eşleşen değer → sağlıklı; yol var (beklenen boş) → sağlıklı")
    void json_pass() {
        assertThat(check("/x", "GET", jsonOpts("$.status", "ok")).get("ok")).isEqualTo(true);
        assertThat(check("/x", "GET", jsonOpts("$.items[0].id", "7")).get("ok")).isEqualTo(true);
        assertThat(check("/x", "GET", jsonOpts("items[0]", null)).get("ok")).isEqualTo(true);
    }

    @Test
    @DisplayName("JSON doğrulaması düşer → DOWN: ok=false, neden error'da, durum kodu korunur, tanı BODY_ASSERTION")
    void json_fail_isDownWithReason() {
        responseBody = "{\"status\":\"degraded\"}";
        Map<String, Object> r = check("/x", "GET", jsonOpts("$.status", "ok"));
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("http_status")).isEqualTo(200);
        assertThat(r.get("error")).isEqualTo("JSON doğrulaması başarısız: $.status = \"degraded\" (beklenen \"ok\")");
        assertThat(r.get("json_assertion_failed")).isEqualTo(true);
        assertThat((String) r.get("error_detail")).contains("\"kind\":\"BODY_ASSERTION\"");

        Map<String, Object> missing = check("/x", "GET", jsonOpts("$.nope", null));
        assertThat((String) missing.get("error")).endsWith("$.nope bulunamadı");
    }

    @Test
    @DisplayName("JSON doğrulaması: ayrıştırılamayan gövde → DOWN")
    void json_unparseable_isDown() {
        responseBody = "<html>bakım</html>";
        Map<String, Object> r = check("/x", "GET", jsonOpts("$.status", null));
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("error")).isEqualTo(JsonAssertion.FAIL_PREFIX + "yanıt gövdesi geçerli JSON değil");
    }

    @Test
    @DisplayName("durum kodu uymadıysa JSON'a bakılmaz — sıradan durum uyuşmazlığı (bayrak yok)")
    void json_skippedOnStatusMismatch() {
        status = 500;
        Map<String, Object> r = check("/x", "GET", jsonOpts("$.status", "ok"));
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("http_status")).isEqualTo(500);
        assertThat(r).doesNotContainKey("json_assertion_failed");
        assertThat(r.get("error")).isNull();
    }

    private static HttpRequestOptions jsonOpts(String path, String expected) {
        return new HttpRequestOptions(null, null, null, null, null, path, expected);
    }
}
