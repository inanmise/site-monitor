package com.sitemonitor.service;

import com.sitemonitor.service.keyword.KeywordCheckSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Anahtar kelime kontrolünün yönlendirme kuralları — hata avı 2026-11-06 (H4#1 / H4#2):
 * <ul>
 *   <li>301 / 302 + POST → GET (gövdesiz, Content-Type yok); 303 → GET; 307 / 308 yöntem + gövde korunur (gerçek sunucu).</li>
 *   <li>Aynı host'ta http:80 → https:443 yükseltmesinde Basic auth + özel başlık İKİNCİ adımda da gider; başka portun https'ine
 *       gitmez (sahte istemci — 80 / 443'e testte bağlanılamaz; karar {@link SafeRedirect#extrasMayFollow}).</li>
 * </ul>
 */
class KeywordRedirectRulesTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        for (HttpServer s : servers) s.stop(0);
    }

    /** Gelen isteğin yöntemi / yolu / başlıkları / gövdesi. */
    private record Seen(String method, String path, String auth, String apiKey, String contentType, String body) {}

    private static KeywordCheckerService realChecker() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        KeywordCheckerService svc = new KeywordCheckerService(new SsrfGuard(s));
        svc.init();
        return svc;
    }

    /** /login → {@code status} ile /home'a yönlendirir; /home kelimeyi döndürür. Her istek kaydedilir. */
    private String loginServer(int status, List<Seen> seen) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("X-Api-Key"),
                    ex.getRequestHeaders().getFirst("Content-Type"),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (ex.getRequestURI().getPath().equals("/login")) {
                ex.getResponseHeaders().add("Location", "/home");
                ex.sendResponseHeaders(status, -1);
                ex.close();
                return;
            }
            // sunucu ikinci adımda POST'u reddeder (yalnız GET'e açık bir sayfa — gerçek giriş sonrası ana sayfa gibi)
            boolean post = "POST".equals(ex.getRequestMethod());
            byte[] out = (post ? "<p>Method Not Allowed</p>" : "<p>Hoş geldiniz — Hesabım</p>").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(post ? 405 : 200, out.length);
            try (var os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static KeywordCheckSpec.Builder postSpec(String url) {
        return KeywordCheckSpec.builder(url, "Hesabım").timeoutMs(5000).diagnose(true)
                .method("POST").requestBody("user=ali&pass=x").requestContentType("application/x-www-form-urlencoded")
                .basicAuthUser("izleme").basicAuthPass("Gizli-2026").customHeaders("X-Api-Key: k3y-abc");
    }

    @Test
    @DisplayName("H4#1: POST /login → 302 /home → GET, gövdesiz ve Content-Type'sız (aynı kaynakta kimlik yine gider); eskiden yeniden "
            + "POST ediliyor, sayfa 405 dönüp sahte alarm açıyordu")
    void post302BecomesGetWithoutBody() throws IOException {
        for (int status : new int[]{301, 302}) {
            List<Seen> seen = new CopyOnWriteArrayList<>();
            String base = loginServer(status, seen);
            Map<String, Object> r = realChecker().check(postSpec(base + "/login").build());
            assertThat(r.get("ok")).as(status + " " + r).isEqualTo(true);
            assertThat(r.get("http_status")).isEqualTo(200);
            assertThat(seen).hasSize(2);
            Seen first = seen.get(0);
            assertThat(first.method()).isEqualTo("POST");
            assertThat(first.body()).isEqualTo("user=ali&pass=x");
            assertThat(first.contentType()).isEqualTo("application/x-www-form-urlencoded");
            Seen second = seen.get(1);
            assertThat(second.method()).as("301 / 302 + POST → GET").isEqualTo("GET");
            assertThat(second.path()).isEqualTo("/home");
            assertThat(second.body()).as("gövde gönderilmez").isEmpty();
            assertThat(second.contentType()).as("Content-Type gönderilmez").isNull();
            assertThat(second.auth()).as("aynı kaynak → kimlik gider").startsWith("Basic ");
            assertThat(second.apiKey()).isEqualTo("k3y-abc");
        }
    }

    @Test
    @DisplayName("H4#1: 307 / 308 yöntemi VE gövdeyi korur (standardın amacı); 303 her yöntemde GET")
    void post307And308KeepMethodAndBody() throws IOException {
        for (int status : new int[]{307, 308}) {
            List<Seen> seen = new CopyOnWriteArrayList<>();
            String base = loginServer(status, seen);
            Map<String, Object> r = realChecker().check(postSpec(base + "/login").build());
            assertThat(seen).hasSize(2);
            assertThat(seen.get(1).method()).as(String.valueOf(status)).isEqualTo("POST");
            assertThat(seen.get(1).body()).isEqualTo("user=ali&pass=x");
            assertThat(seen.get(1).contentType()).isEqualTo("application/x-www-form-urlencoded");
            assertThat(r.get("http_status")).as("sunucu POST'u reddediyor — korunduğunun kanıtı").isEqualTo(405);
        }
        List<Seen> seen = new CopyOnWriteArrayList<>();
        String base = loginServer(303, seen);
        assertThat(realChecker().check(postSpec(base + "/login").build()).get("ok")).isEqualTo(true);
        assertThat(seen.get(1).method()).isEqualTo("GET");
        assertThat(seen.get(1).body()).isEmpty();
    }

    // ── http → https yükseltmesi (sahte istemci) ─────────────────────────────────────────────────

    /** İstekleri kaydeden, URI'ye göre hazır yanıt dönen sahte istemci (ağ yok). */
    static final class FakeClient extends HttpClient {
        final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
        private final Function<URI, FakeResponse> route;

        FakeClient(Function<URI, FakeResponse> route) { this.route = route; }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return null; }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            requests.add(request);
            FakeResponse r = route.apply(request.uri());
            return (HttpResponse<T>) new Resp(request, r);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }
    }

    record FakeResponse(int status, String location, String body) {}

    private record Resp(HttpRequest request, FakeResponse r) implements HttpResponse<InputStream> {
        @Override public int statusCode() { return r.status(); }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            Map<String, List<String>> h = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (r.location() != null) h.put("location", List.of(r.location()));
            h.put("content-type", List.of("text/html; charset=utf-8"));
            return HttpHeaders.of(h, (a, b) -> true);
        }
        @Override public InputStream body() {
            return new ByteArrayInputStream(r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8));
        }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    /** Sahte istemcili denetleyici — SSRF koruması sahte (ad çözümlemesi yok). */
    private static KeywordCheckerService fakeChecker(FakeClient client) {
        KeywordCheckerService svc = new KeywordCheckerService(mock(SsrfGuard.class)) {
            @Override
            HttpClient clientFor(boolean viaProxy) { return client; }
        };
        svc.init();
        return svc;
    }

    private static KeywordCheckSpec getSpec(String url) {
        return KeywordCheckSpec.builder(url, "Hesabım").timeoutMs(5000).diagnose(true)
                .basicAuthUser("izleme").basicAuthPass("Gizli-2026").customHeaders("X-Api-Key: k3y-abc").build();
    }

    @Test
    @DisplayName("H4#2: aynı host'ta http:80 → https:443 yükseltmesinde Basic auth + özel başlık İKİNCİ adımda da gider (sayfa 401 "
            + "dönüp sahte alarm açmaz)")
    void httpToHttpsUpgradeKeepsCredentials() {
        FakeClient client = new FakeClient(u -> "http".equals(u.getScheme())
                ? new FakeResponse(301, "https://kw.example.test/hesap", null)
                : new FakeResponse(200, null, "<p>Hesabım</p>"));
        Map<String, Object> r = fakeChecker(client).check(getSpec("http://kw.example.test/hesap"));
        assertThat(r.get("ok")).as(String.valueOf(r)).isEqualTo(true);
        assertThat(client.requests).hasSize(2);
        HttpRequest second = client.requests.get(1);
        assertThat(second.uri().toString()).isEqualTo("https://kw.example.test/hesap");
        assertThat(second.headers().firstValue("Authorization")).as("yükseltme = aynı kaynak").isPresent();
        assertThat(second.headers().firstValue("X-Api-Key")).contains("k3y-abc");
    }

    @Test
    @DisplayName("H4#2: başka portun http'sinden https'e, ya da https → başka host geçişinde kimlik / özel başlık ikinci adımda GİTMEZ")
    void otherUpgradesDropCredentials() {
        FakeClient client = new FakeClient(u -> u.getPort() == 8080
                ? new FakeResponse(302, "https://kw.example.test/hesap", null)
                : new FakeResponse(200, null, "<p>Hesabım</p>"));
        Map<String, Object> r = fakeChecker(client).check(getSpec("http://kw.example.test:8080/hesap"));
        assertThat(r.get("ok")).isEqualTo(true);
        HttpRequest second = client.requests.get(1);
        assertThat(second.headers().firstValue("Authorization")).as("http:8080 → https:443 başka kaynak").isEmpty();
        assertThat(second.headers().firstValue("X-Api-Key")).isEmpty();

        FakeClient other = new FakeClient(u -> "kw.example.test".equals(u.getHost())
                ? new FakeResponse(302, "https://baska.example.test/", null)
                : new FakeResponse(200, null, "<p>Hesabım</p>"));
        fakeChecker(other).check(getSpec("https://kw.example.test/"));
        assertThat(other.requests.get(1).headers().firstValue("Authorization")).isEmpty();
    }
}
