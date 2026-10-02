package com.sitemonitor.service.http.diagnose;

import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.HttpCheckerService;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SafeRedirect;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HTTP uçtan uca tanılaması (2026-10-02) — gerçek yerel sunuculara (düz HTTP, kendinden imzalı HTTPS) ve test içi
 * vekillere (mutlak biçim iletici, CONNECT tüneli, kimlik isteyen, "kara delik") karşı ham ölçüm + bulgu + maske.
 * İzlemenin gerçek istemcisi ({@code client_check}) burada mock — onun yan etkisizliği
 * {@code HttpCheckerDiagnosticsOverloadTest}'te gerçek istemciyle sınanır.
 */
class HttpDiagnosticsServiceTest {

    private static HttpServer http;
    private static String base;
    private static HttpsServer https;
    private static String httpsBase;

    @BeforeAll
    static void startServers() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "diag-test-http"); t.setDaemon(true); return t; }));
        http.createContext("/ok", ex -> {
            byte[] b = "<html><body>merhaba</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().add("Set-Cookie", "SESSIONID=abc123secretvalue; Path=/; HttpOnly");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        http.createContext("/slow", ex -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
            try { ex.sendResponseHeaders(200, -1); } catch (IOException ignore) { /* istemci gitti */ }
            ex.close();
        });
        http.createContext("/auth", ex -> {
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"intranet\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        http.createContext("/r1", ex -> { ex.getResponseHeaders().add("Location", "/r2"); ex.sendResponseHeaders(302, -1); ex.close(); });
        http.createContext("/r2", ex -> { ex.getResponseHeaders().add("Location", "/ok"); ex.sendResponseHeaders(301, -1); ex.close(); });
        http.createContext("/loop", ex -> { ex.getResponseHeaders().add("Location", "/loop"); ex.sendResponseHeaders(302, -1); ex.close(); });
        http.createContext("/big", ex -> {
            byte[] b = "x".repeat(40_000).getBytes(StandardCharsets.US_ASCII);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        http.createContext("/chunked", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, 0);   // 0 → chunked
            try (OutputStream os = ex.getResponseBody()) {
                os.write("parca-1;".getBytes(StandardCharsets.US_ASCII));
                os.flush();
                os.write("parca-2".getBytes(StandardCharsets.US_ASCII));
            }
        });
        http.createContext("/bin", ex -> {
            byte[] b = new byte[1000];
            for (int i = 0; i < b.length; i++) b[i] = (byte) i;
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        http.createContext("/json", ex -> {
            byte[] b = "{\"status\":\"UP\",\"items\":[1,2]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        http.createContext("/echo", ex -> {
            // Sunucu gelen sırları gövdede GERİ yansıtır — derin süzgeç bunları önizlemeden silmeli.
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String tok = ex.getRequestHeaders().getFirst("X-Custom-Token");
            byte[] b = ("{\"password\":\"hunter2\",\"seen_auth\":\"" + auth + "\",\"seen_token\":\"" + tok + "\",\"user\":\"x\"}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("Set-Cookie", "SESSIONID=abc123secretvalue; Path=/; HttpOnly");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();

        Security.addProvider(new BouncyCastleProvider());
        KeyPair kp = keyPair();
        X509Certificate cert = selfSigned(kp, "CN=localhost, O=SiteMonitor Diag Test");
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("server", kp.getPrivate(), new char[0], new X509Certificate[]{ cert });
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, new SecureRandom());
        https = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverCtx));
        https.createContext("/", ex -> {
            byte[] b = "tls-ok".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        https.start();
        httpsBase = "https://localhost:" + https.getAddress().getPort();
    }

    @AfterAll
    static void stopServers() {
        if (http != null) http.stop(0);
        if (https != null) https.stop(0);
    }

    // ── Kurulum ─────────────────────────────────────────────────────────────────────────────────

    private HttpCheckerService checker;
    private CaAutoPinService pin;
    private ProxyPolicyService policy;
    private SecretCipher cipher;
    private AppSettingsService settings;

    @BeforeEach
    void mocks() {
        settings = mock(AppSettingsService.class);
        when(settings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(settings.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(settings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn("");
        checker = mock(HttpCheckerService.class);
        clientSays(true, 200, null);
        pin = mock(CaAutoPinService.class);
        policy = mock(ProxyPolicyService.class);
        routeIs(false);
        cipher = mock(SecretCipher.class);
        when(cipher.decrypt(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).substring("enc:".length()));
    }

    private void clientSays(boolean ok, Integer status, String error) {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", ok);
        r.put("http_status", status);
        r.put("response_ms", 5L);
        r.put("http_version", status == null ? null : "HTTP_1_1");
        r.put("error", error);
        when(checker.checkForDiagnostics(anyString(), anyString(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(r);
    }

    private void routeIs(boolean viaProxy) {
        when(policy.decide(anyString(), anyString()))
                .thenReturn(new ProxyPolicyService.Decision(viaProxy, "monitor", viaProxy, false));
    }

    private HttpDiagnosticsService service(ProxySettings proxy) {
        HttpDiagnosticsService s = new HttpDiagnosticsService(new SsrfGuard(settings), new TrustEvaluator(settings),
                checker, pin, proxy, policy, cipher);
        s.env = k -> null;
        return s;
    }

    private static ProxySettings proxyAt(int port, String user, String pass) {
        ProxySettings p = new ProxySettings();
        ReflectionTestUtils.setField(p, "host", "127.0.0.1");
        ReflectionTestUtils.setField(p, "port", port);
        ReflectionTestUtils.setField(p, "user", user);
        ReflectionTestUtils.setField(p, "pass", pass);
        ReflectionTestUtils.setField(p, "noProxy", "intranet.example");
        return p;
    }

    private static HttpMonitor monitor(String url, int timeoutMs) {
        HttpMonitor m = new HttpMonitor();
        m.setId(36L);
        m.setName("Diag test");
        m.setUrl(url);
        m.setMethod("GET");
        m.setExpectedStatus("200-399");
        m.setTimeoutMs(timeoutMs);
        m.setVerifySsl(false);
        m.setFollowRedirects(true);
        m.setUseProxy("AUTO");
        m.setTeamId(5L);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> path(Map<String, Object> data, int i) {
        return (Map<String, Object>) ((List<Object>) data.get("paths")).get(i);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hops(Map<String, Object> path) {
        return (List<Map<String, Object>>) path.get("hops");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findings(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("findings");
    }

    private static List<String> codes(Map<String, Object> data) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : findings(data)) out.add((String) f.get("code"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String stepStatus(Map<String, Object> hop, String key) {
        for (Map<String, Object> s : (List<Map<String, Object>>) hop.get("steps")) {
            if (key.equals(s.get("key"))) return (String) s.get("status");
        }
        return null;
    }

    private static String json(Object o) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(o);
    }

    // ── Doğrudan yol ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("200: adımlar, istek satırı/başlıkları, yanıt başlıkları + metin önizlemesi, OK hükmü, sözleşme blokları")
    void directOk_fullShape() {
        Map<String, Object> data = service(null).diagnose(monitor(base + "/ok", 1500), true);

        assertThat(data).containsKeys("run_id", "started_at", "duration_ms", "monitor", "source", "proxy", "verdict",
                "findings", "paths", "comparison");
        assertThat(m(data.get("verdict"))).containsEntry("status", "ok").containsEntry("code", "OK");
        assertThat(m(data.get("comparison"))).containsEntry("available", false).containsEntry("differs", false);
        assertThat(m(data.get("proxy"))).containsEntry("configured", false).containsEntry("auth", false);
        assertThat((List<?>) data.get("paths")).hasSize(1);

        Map<String, Object> p = path(data, 0);
        assertThat(p).containsEntry("key", "monitor").containsEntry("route", "direct").containsEntry("outcome", "ok")
                .containsEntry("http_status", 200).containsEntry("failed_step", null).containsEntry("error", null);
        assertThat(m(p.get("timeline"))).containsEntry("tls_ms", 0L).containsEntry("proxy_ms", 0L);
        assertThat(m(p.get("timeline")).get("ttfb_ms")).isNotNull();
        Map<String, Object> hop = hops(p).get(0);
        assertThat(stepStatus(hop, "dns")).isEqualTo("ok");
        assertThat(stepStatus(hop, "tcp")).isEqualTo("ok");
        assertThat(stepStatus(hop, "request")).isEqualTo("ok");
        assertThat(stepStatus(hop, "response")).isEqualTo("ok");
        assertThat(stepStatus(hop, "body")).isEqualTo("ok");
        assertThat(hop.get("tls")).isNull();
        assertThat(hop.get("proxy")).isNull();
        assertThat(m(hop.get("dns")).get("addresses")).asInstanceOf(InstanceOfAssertFactories.LIST).contains("127.0.0.1");
        assertThat(m(hop.get("tcp")).get("remote")).isEqualTo("127.0.0.1:" + http.getAddress().getPort());
        assertThat(m(hop.get("request")).get("line")).isEqualTo("GET /ok HTTP/1.1");
        assertThat(json(m(hop.get("request")).get("headers"))).contains("SiteMonitor-HttpMonitor/1.0").contains("Connection");
        Map<String, Object> resp = m(hop.get("response"));
        assertThat(resp).containsEntry("status", 200).containsEntry("http_version", "HTTP/1.1");
        assertThat((String) resp.get("status_line")).startsWith("HTTP/1.1 200");
        Map<String, Object> body = m(resp.get("body"));
        assertThat(body).containsEntry("text", true).containsEntry("complete", true).containsEntry("preview_truncated", false);
        assertThat((String) body.get("preview")).contains("merhaba");
        // Set-Cookie: yalnız DEĞER gizli, ad + öznitelikler görünür.
        assertThat(json(resp.get("headers"))).contains("SESSIONID=••••; Path=/; HttpOnly").doesNotContain("abc123secretvalue");
        @SuppressWarnings("unchecked") List<String> transcript = (List<String>) p.get("transcript");
        assertThat(transcript).contains("> GET /ok HTTP/1.1").anyMatch(l -> l.startsWith("< HTTP/1.1 200"));
        assertThat(m(p.get("client_check"))).containsEntry("ok", true).containsEntry("http_version", "HTTP_1_1");
    }

    @Test
    @DisplayName("YANIT TAKILDI: istek gitti, süre içinde yanıt yok → RESPONSE_TIMEOUT (request ok, failed_step response)")
    void responseTimeout() {
        clientSays(false, null, "request timed out");
        long t0 = System.currentTimeMillis();
        Map<String, Object> data = service(null).diagnose(monitor(base + "/slow", 1000), true);
        long ms = System.currentTimeMillis() - t0;

        assertThat(ms).as("yol bütçesi ~timeout").isLessThan(2_900L);
        Map<String, Object> p = path(data, 0);
        assertThat(p).containsEntry("outcome", "fail").containsEntry("failed_step", "response").containsEntry("http_status", null);
        Map<String, Object> hop = hops(p).get(0);
        assertThat(stepStatus(hop, "request")).isEqualTo("ok");
        assertThat(stepStatus(hop, "response")).isEqualTo("fail");
        assertThat(stepStatus(hop, "body")).isEqualTo("skip");
        Map<String, Object> v = m(data.get("verdict"));
        assertThat(v).containsEntry("status", "fail").containsEntry("code", "RESPONSE_TIMEOUT")
                .containsEntry("failed_step", "response").containsEntry("path", "monitor");
        assertThat(m(v.get("params"))).containsEntry("ms", 1000).containsEntry("route", "direct");
        assertThat(m(p.get("error")).get("class")).isEqualTo("java.net.SocketTimeoutException");
        @SuppressWarnings("unchecked") List<String> transcript = (List<String>) p.get("transcript");
        assertThat(transcript).contains("* No response within 1000 ms");
        assertThat(codes(data)).doesNotContain("CLIENT_MISMATCH");   // istemci de düştü → uyumlu
    }

    @Test
    @DisplayName("401: sunucu ayakta ama kimlik istiyor → AUTH_REQUIRED (beklenen dışı = fail), WWW-Authenticate görünür")
    void authRequired() {
        clientSays(false, 401, null);
        Map<String, Object> data = service(null).diagnose(monitor(base + "/auth", 1500), true);
        Map<String, Object> v = m(data.get("verdict"));
        assertThat(v).containsEntry("code", "AUTH_REQUIRED").containsEntry("status", "fail");
        assertThat(m(v.get("params"))).containsEntry("status", 401).containsEntry("expected", "200-399");
        Map<String, Object> hop = hops(path(data, 0)).get(0);
        assertThat(json(m(hop.get("response")).get("headers"))).contains("Basic realm=");

        // Beklenen kod 401 ise aynı yanıt info düzeyinde → hüküm OK
        HttpMonitor mon = monitor(base + "/auth", 1500);
        mon.setExpectedStatus("401");
        clientSays(true, 401, null);
        Map<String, Object> ok = service(null).diagnose(mon, true);
        assertThat(m(ok.get("verdict"))).containsEntry("code", "OK").containsEntry("status", "ok");
        assertThat(findings(ok)).anySatisfy(f -> assertThat(f).containsEntry("code", "AUTH_REQUIRED").containsEntry("severity", "info"));
    }

    @Test
    @DisplayName("yönlendirme zinciri: her hop ayrı nesne, redirect bloğu, son durum 200")
    void redirectChain() {
        Map<String, Object> data = service(null).diagnose(monitor(base + "/r1", 1500), true);
        Map<String, Object> p = path(data, 0);
        assertThat(p).containsEntry("outcome", "ok").containsEntry("http_status", 200);
        List<Map<String, Object>> hops = hops(p);
        assertThat(hops).hasSize(3);
        Map<String, Object> r0 = m(hops.get(0).get("redirect"));
        assertThat(r0).containsEntry("status", 302).containsEntry("location", "/r2").containsEntry("cross_host", false)
                .containsEntry("extras_dropped", false);
        assertThat((String) r0.get("next_url")).endsWith("/r2");
        assertThat(m(hops.get(1).get("redirect"))).containsEntry("status", 301);
        assertThat(hops.get(2).get("redirect")).isNull();
        assertThat(hops.get(2)).containsEntry("index", 2);
    }

    @Test
    @DisplayName("yönlendirme döngüsü: üst sınır aşılınca REDIRECT_LOOP")
    void redirectLoop() {
        clientSays(false, null, "çok fazla yönlendirme");
        Map<String, Object> data = service(null).diagnose(monitor(base + "/loop", 1500), true);
        assertThat(m(data.get("verdict"))).containsEntry("code", "REDIRECT_LOOP");
        assertThat(hops(path(data, 0))).hasSize(SafeRedirect.MAX_HOPS + 1);
    }

    @Test
    @DisplayName("32 KB üstü metin gövdesi: önizleme kırpılır, toplam bayt sayılır; parçalı (chunked) gövde çözülür")
    void largeAndChunkedBodies() {
        Map<String, Object> data = service(null).diagnose(monitor(base + "/big", 1500), false);
        Map<String, Object> body = m(m(hops(path(data, 0)).get(0).get("response")).get("body"));
        assertThat(body).containsEntry("bytes", 40_000L).containsEntry("complete", true).containsEntry("preview_truncated", true);
        assertThat(((String) body.get("preview")).length()).isEqualTo(RawHttpProbe.PREVIEW_BYTES);

        Map<String, Object> chunked = service(null).diagnose(monitor(base + "/chunked", 1500), false);
        Map<String, Object> cb = m(m(hops(path(chunked, 0)).get(0).get("response")).get("body"));
        assertThat(cb).containsEntry("complete", true).containsEntry("preview", "parca-1;parca-2");
    }

    @Test
    @DisplayName("ikili gövde: önizleme yok (text=false), bayt sayısı var")
    void binaryBody_noPreview() {
        Map<String, Object> data = service(null).diagnose(monitor(base + "/bin", 1500), false);
        Map<String, Object> body = m(m(hops(path(data, 0)).get(0).get("response")).get("body"));
        assertThat(body).containsEntry("text", false).containsEntry("preview", null).containsEntry("bytes", 1000L);
    }

    @Test
    @DisplayName("JSON doğrulaması: geçerse OK; düşerse JSON_ASSERTION_FAIL (failed_step body)")
    void jsonAssertion() {
        HttpMonitor mon = monitor(base + "/json", 1500);
        mon.setJsonPath("$.status");
        mon.setJsonExpected("UP");
        assertThat(m(service(null).diagnose(mon, false).get("verdict"))).containsEntry("code", "OK");

        mon.setJsonExpected("DOWN");
        clientSays(false, 200, "JSON doğrulaması başarısız: ...");
        Map<String, Object> data = service(null).diagnose(mon, false);
        Map<String, Object> v = m(data.get("verdict"));
        assertThat(v).containsEntry("code", "JSON_ASSERTION_FAIL").containsEntry("failed_step", "body");
        assertThat(m(v.get("params"))).containsEntry("path", "$.status");
        assertThat(path(data, 0)).containsEntry("outcome", "fail").containsEntry("http_status", 200);
    }

    @Test
    @DisplayName("CLIENT_MISMATCH: ham ölçüm geçti, izleme istemcisi düştü → uyarı (hüküm warn)")
    void clientMismatch() {
        clientSays(false, null, "request timed out");
        Map<String, Object> data = service(null).diagnose(monitor(base + "/ok", 1500), false);
        Map<String, Object> v = m(data.get("verdict"));
        assertThat(v).containsEntry("status", "warn").containsEntry("code", "CLIENT_MISMATCH");
        assertThat(m(v.get("params"))).containsEntry("route", "direct").containsEntry("raw_status", 200)
                .containsEntry("client_error", "request timed out");
    }

    @Test
    @DisplayName("yavaşlık eşiği: başarılı ama eşik üstü → SLOW uyarısı")
    void slowWarning() {
        HttpMonitor mon = monitor(base + "/ok", 1500);
        mon.setSlowResponseEnabled(true);
        mon.setSlowThresholdMs(0);
        Map<String, Object> data = service(null).diagnose(mon, false);
        assertThat(codes(data)).contains("SLOW", "OK");
        assertThat(m(m(data.get("monitor")).get("advanced"))).containsEntry("slow_threshold_ms", 0);
    }

    @Test
    @DisplayName("SSRF: engelli hedef (cloud-metadata) → SSRF_BLOCKED, bağlantı açılmaz")
    void ssrfBlocked() {
        clientSays(false, null, "izin verilmeyen hedef");
        Map<String, Object> data = service(null).diagnose(monitor("http://169.254.169.254/latest/meta-data", 1000), true);
        Map<String, Object> v = m(data.get("verdict"));
        assertThat(v).containsEntry("code", "SSRF_BLOCKED").containsEntry("failed_step", "dns");
        Map<String, Object> hop = hops(path(data, 0)).get(0);
        assertThat(hop.get("tcp")).isNull();
        assertThat(stepStatus(hop, "tcp")).isEqualTo("skip");
    }

    @Test
    @DisplayName("kaynak bilgisi: POD_NAME / NODE_NAME / POD_IP ortamdan")
    void sourceFromEnv() {
        HttpDiagnosticsService s = service(null);
        Map<String, String> env = Map.of("POD_NAME", "sitemonitor-abc-1", "NODE_NAME", "worker-7", "POD_IP", "10.1.2.3");
        s.env = env::get;
        Map<String, Object> data = s.diagnose(monitor(base + "/ok", 1500), false);
        assertThat(m(data.get("source"))).containsEntry("pod", "sitemonitor-abc-1").containsEntry("node", "worker-7")
                .containsEntry("pod_ip", "10.1.2.3");
        // POD_NAME yoksa HOSTNAME
        s.env = Map.of("HOSTNAME", "host-x")::get;
        assertThat(m(s.diagnose(monitor(base + "/ok", 1500), false).get("source"))).containsEntry("pod", "host-x");
    }

    // ── TLS ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HTTPS kendinden imzalı + verify AÇIK → zincir yakalanır, güvenilmez → TLS_UNTRUSTED (istek gönderilmez)")
    void tlsUntrusted_whenVerifyOn() {
        clientSays(false, null, "PKIX path building failed");
        HttpMonitor mon = monitor(httpsBase + "/", 1500);
        mon.setVerifySsl(true);
        Map<String, Object> data = service(null).diagnose(mon, false);
        Map<String, Object> v = m(data.get("verdict"));
        assertThat(v).containsEntry("code", "TLS_UNTRUSTED").containsEntry("failed_step", "tls");
        Map<String, Object> hop = hops(path(data, 0)).get(0);
        Map<String, Object> tls = m(hop.get("tls"));
        assertThat(tls).containsEntry("trusted", false).containsEntry("hostname_match", true).containsEntry("sni", "localhost");
        assertThat((String) tls.get("protocol")).startsWith("TLSv1.");
        assertThat(tls.get("cipher")).isNotNull();
        @SuppressWarnings("unchecked") List<Map<String, Object>> chain = (List<Map<String, Object>>) tls.get("chain");
        assertThat(chain).hasSize(1);
        assertThat((String) chain.get(0).get("subject")).contains("CN=localhost");
        assertThat(chain.get(0)).containsEntry("key", "RSA 2048").containsEntry("sig_alg", "SHA256withRSA");
        assertThat((String) chain.get(0).get("sha256")).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}");
        assertThat(chain.get(0).get("san")).asInstanceOf(InstanceOfAssertFactories.LIST).contains("localhost");
        assertThat(stepStatus(hop, "request")).isEqualTo("skip");
        verify(pin, never()).pinFromServer(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("HTTPS kendinden imzalı + verify KAPALI → yanıt alınır, güven bilgisi yine raporlanır (bulgu yok)")
    void tlsVerifyOff_reportsTrustButSucceeds() {
        Map<String, Object> data = service(null).diagnose(monitor(httpsBase + "/", 1500), false);
        assertThat(m(data.get("verdict"))).containsEntry("code", "OK");
        Map<String, Object> p = path(data, 0);
        Map<String, Object> tls = m(hops(p).get(0).get("tls"));
        assertThat(tls).containsEntry("trusted", false);
        assertThat(tls.get("trust_error")).isNotNull();
        assertThat(m(p.get("timeline")).get("tls_ms")).isNotNull();
        assertThat(codes(data)).doesNotContain("TLS_UNTRUSTED", "TLS_HOSTNAME_MISMATCH");
    }

    // ── Vekil ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("düz http + vekil: mutlak biçim istek vekile gider, vekil iletir → 200 (proxy.mode absolute)")
    void proxyAbsoluteForm() throws Exception {
        try (TestProxy proxy = TestProxy.forwarding()) {
            routeIs(true);
            Map<String, Object> data = service(proxyAt(proxy.port(), "", "")).diagnose(monitor(base + "/ok", 1500), false);
            Map<String, Object> p = path(data, 0);
            assertThat(p).containsEntry("route", "proxy").containsEntry("outcome", "ok").containsEntry("http_status", 200);
            Map<String, Object> hop = hops(p).get(0);
            assertThat(stepStatus(hop, "proxy_connect")).isEqualTo("ok");
            assertThat(m(hop.get("proxy"))).containsEntry("mode", "absolute").containsEntry("connect_request", null);
            assertThat(m(hop.get("dns"))).containsEntry("via_proxy", true);
            assertThat(m(hop.get("tcp")).get("remote")).isEqualTo("127.0.0.1:" + proxy.port());
            assertThat(m(hop.get("request")).get("line")).isEqualTo("GET " + base + "/ok HTTP/1.1");
            assertThat(proxy.requestLines()).anyMatch(l -> l.equals("GET " + base + "/ok HTTP/1.1"));
            assertThat(m(data.get("proxy"))).containsEntry("configured", true).containsEntry("port", proxy.port())
                    .containsEntry("no_proxy", "intranet.example");
        }
    }

    @Test
    @DisplayName("https + vekil: CONNECT tüneli (giden/gelen satırlar), ardından TLS + 200; vekil parolası hiçbir yerde düz değil")
    void proxyConnectTunnel_masksProxyCredentials() throws Exception {
        try (TestProxy proxy = TestProxy.forwarding()) {
            routeIs(true);
            Map<String, Object> data = service(proxyAt(proxy.port(), "pxuser", "pr0xyP@ss!"))
                    .diagnose(monitor(httpsBase + "/", 1500), false);
            Map<String, Object> p = path(data, 0);
            assertThat(p).containsEntry("outcome", "ok").containsEntry("http_status", 200);
            Map<String, Object> hop = hops(p).get(0);
            Map<String, Object> px = m(hop.get("proxy"));
            assertThat(px).containsEntry("mode", "connect");
            assertThat(px.get("connect_request")).asInstanceOf(InstanceOfAssertFactories.LIST)
                    .contains("CONNECT localhost:" + https.getAddress().getPort() + " HTTP/1.1", "Proxy-Authorization: ••••");
            assertThat(m(px.get("connect_response"))).containsEntry("status", 200);
            assertThat(stepStatus(hop, "proxy_tunnel")).isEqualTo("ok");
            assertThat(stepStatus(hop, "tls")).isEqualTo("ok");
            assertThat(m(data.get("proxy"))).containsEntry("auth", true);
            String all = json(data);
            String token = Base64.getEncoder().encodeToString("pxuser:pr0xyP@ss!".getBytes(StandardCharsets.UTF_8));
            assertThat(all).doesNotContain("pr0xyP@ss!").doesNotContain(token);
            assertThat(proxy.requestLines()).anyMatch(l -> l.startsWith("Proxy-Authorization: Basic "));
        }
    }

    @Test
    @DisplayName("vekil 407 → PROXY_AUTH_REQUIRED (adım proxy_tunnel)")
    void proxyAuthRequired() throws Exception {
        try (TestProxy proxy = TestProxy.requiringAuth()) {
            routeIs(true);
            clientSays(false, null, "407");
            Map<String, Object> data = service(proxyAt(proxy.port(), "", "")).diagnose(monitor(httpsBase + "/", 1500), false);
            Map<String, Object> v = m(data.get("verdict"));
            assertThat(v).containsEntry("code", "PROXY_AUTH_REQUIRED").containsEntry("failed_step", "proxy_tunnel");
            assertThat(m(v.get("params"))).containsEntry("proxy", "127.0.0.1:" + proxy.port());
        }
    }

    @Test
    @DisplayName("PROD VAKASI: 'kara delik' vekil (kabul eder, hiç cevap vermez) → vekil yolu YANIT TAKILDI, doğrudan yol 200 → PATH_DIFFERS")
    void blackHoleProxy_pathDiffers() throws Exception {
        try (TestProxy proxy = TestProxy.blackHole()) {
            routeIs(true);
            when(checker.checkForDiagnostics(anyString(), anyString(), any(), anyInt(), anyBoolean(), anyBoolean(), eq(true), any()))
                    .thenReturn(clientResult(false, null, "request timed out"));
            when(checker.checkForDiagnostics(anyString(), anyString(), any(), anyInt(), anyBoolean(), anyBoolean(), eq(false), any()))
                    .thenReturn(clientResult(true, 200, null));
            long t0 = System.currentTimeMillis();
            Map<String, Object> data = service(proxyAt(proxy.port(), "", "")).diagnose(monitor(base + "/ok", 1000), true);
            long ms = System.currentTimeMillis() - t0;
            assertThat(ms).as("iki yol PARALEL koşar").isLessThan(2_500L);

            Map<String, Object> mon = path(data, 0);
            Map<String, Object> alt = path(data, 1);
            assertThat(mon).containsEntry("key", "monitor").containsEntry("route", "proxy").containsEntry("outcome", "fail")
                    .containsEntry("failed_step", "response");
            assertThat(alt).containsEntry("key", "alternate").containsEntry("route", "direct").containsEntry("outcome", "ok")
                    .containsEntry("http_status", 200);
            assertThat(m(alt.get("decision"))).containsEntry("source", "compare").containsEntry("wanted", false);
            Map<String, Object> hop = hops(mon).get(0);
            assertThat(stepStatus(hop, "proxy_connect")).isEqualTo("ok");
            assertThat(stepStatus(hop, "request")).isEqualTo("ok");
            assertThat(stepStatus(hop, "response")).isEqualTo("fail");

            Map<String, Object> v = m(data.get("verdict"));
            assertThat(v).containsEntry("code", "PATH_DIFFERS").containsEntry("status", "fail").containsEntry("failed_step", "response");
            assertThat(m(v.get("params"))).containsEntry("failing_route", "proxy").containsEntry("working_route", "direct")
                    .containsEntry("working_status", 200);
            assertThat(m(data.get("comparison"))).containsEntry("available", true).containsEntry("differs", true);
            assertThat(findings(data)).anySatisfy(f -> assertThat(f).containsEntry("code", "RESPONSE_TIMEOUT")
                    .containsEntry("path", "monitor"));
        }
    }

    @Test
    @DisplayName("iki yol da yanıtsız (vekil kara delik + doğrudan yavaş uç) → BOTH_PATHS_FAIL, PATH_DIFFERS değil")
    void bothPathsFail() throws Exception {
        try (TestProxy proxy = TestProxy.blackHole()) {
            routeIs(true);
            clientSays(false, null, "request timed out");
            Map<String, Object> data = service(proxyAt(proxy.port(), "", "")).diagnose(monitor(base + "/slow", 1000), true);
            Map<String, Object> v = m(data.get("verdict"));
            assertThat(v).containsEntry("code", "BOTH_PATHS_FAIL").containsEntry("status", "fail").containsEntry("failed_step", "response");
            assertThat(m(v.get("params"))).containsEntry("failed_step", "response");
            assertThat(codes(data)).doesNotContain("PATH_DIFFERS");
            assertThat(m(data.get("comparison"))).containsEntry("available", true).containsEntry("differs", false);
        }
    }

    @Test
    @DisplayName("vekil CONNECT'i 403 ile reddeder → PROXY_TUNNEL_REFUSED {proxy, status}")
    void proxyTunnelRefused() throws Exception {
        try (TestProxy proxy = TestProxy.denying()) {
            routeIs(true);
            clientSays(false, null, "403");
            Map<String, Object> data = service(proxyAt(proxy.port(), "", "")).diagnose(monitor(httpsBase + "/", 1500), false);
            Map<String, Object> v = m(data.get("verdict"));
            assertThat(v).containsEntry("code", "PROXY_TUNNEL_REFUSED").containsEntry("failed_step", "proxy_tunnel");
            assertThat(m(v.get("params"))).containsEntry("status", 403).containsEntry("proxy", "127.0.0.1:" + proxy.port());
            Map<String, Object> hop = hops(path(data, 0)).get(0);
            assertThat(m(m(hop.get("proxy")).get("connect_response"))).containsEntry("status_line", "HTTP/1.1 403 Forbidden");
        }
    }

    @Test
    @DisplayName("vekile TCP bağlantısı kurulamaz → PROXY_CONNECT_FAIL (adım proxy_connect)")
    void proxyConnectFail() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { closed = s.getLocalPort(); }
        routeIs(true);
        clientSays(false, null, "Connection refused");
        Map<String, Object> data = service(proxyAt(closed, "", "")).diagnose(monitor(base + "/ok", 1000), false);
        Map<String, Object> v = m(data.get("verdict"));
        assertThat(v).containsEntry("code", "PROXY_CONNECT_FAIL").containsEntry("failed_step", "proxy_connect");
        Map<String, Object> hop = hops(path(data, 0)).get(0);
        assertThat(stepStatus(hop, "proxy_connect")).isEqualTo("fail");
        assertThat(m(hop.get("tcp")).get("attempts")).asInstanceOf(InstanceOfAssertFactories.LIST).isNotEmpty();
    }

    @Test
    @DisplayName("doğrudan yolda kapalı port → TCP_REFUSED (Windows SYN yinelemesinde süre dolarsa TCP_TIMEOUT), adım tcp")
    void directClosedPort() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { closed = s.getLocalPort(); }
        clientSays(false, null, "Connection refused");
        Map<String, Object> data = service(null).diagnose(monitor("http://127.0.0.1:" + closed + "/", 1000), false);
        Map<String, Object> v = m(data.get("verdict"));
        assertThat((String) v.get("code")).isIn("TCP_REFUSED", "TCP_TIMEOUT");
        assertThat(v).containsEntry("failed_step", "tcp");
        assertThat(m(v.get("params"))).containsEntry("address", "127.0.0.1:" + closed);
    }

    @Test
    @DisplayName("compare=false: vekil tanımlı olsa da yalnız izlemenin yolu")
    void compareFalse_singlePath() throws Exception {
        try (TestProxy proxy = TestProxy.forwarding()) {
            Map<String, Object> data = service(proxyAt(proxy.port(), "", "")).diagnose(monitor(base + "/ok", 1500), false);
            assertThat((List<?>) data.get("paths")).hasSize(1);
            assertThat(m(data.get("comparison"))).containsEntry("available", false);
        }
    }

    // ── Maske ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MASKE: Authorization + şifreli özel başlıkların TÜMÜ gizli; yankılanan sırlar önizlemeden, transcript'ten, JSON'dan süzülür")
    void masking_requestHeadersAndEchoedSecrets() {
        HttpMonitor mon = monitor(base + "/echo", 1500);
        mon.setBasicAuthUser("alice");
        mon.setBasicAuthPassEnc("enc:S3cr3tPass!");
        mon.setCustomHeadersEnc("enc:X-Custom-Token: tok-9876543210\nAccept: application/json");
        Map<String, Object> data = service(null).diagnose(mon, false);

        Map<String, Object> hop = hops(path(data, 0)).get(0);
        String reqHeaders = json(m(hop.get("request")).get("headers"));
        assertThat(reqHeaders).contains("{\"name\":\"Authorization\",\"value\":\"••••\",\"masked\":true}")
                .contains("{\"name\":\"X-Custom-Token\",\"value\":\"••••\",\"masked\":true}")
                .contains("{\"name\":\"Accept\",\"value\":\"••••\",\"masked\":true}")
                .contains("{\"name\":\"User-Agent\",\"value\":\"SiteMonitor-HttpMonitor/1.0\",\"masked\":false}");
        Map<String, Object> body = m(m(hop.get("response")).get("body"));
        String preview = (String) body.get("preview");
        assertThat(preview).doesNotContain("hunter2").doesNotContain("tok-9876543210").contains("\"user\":\"x\"");

        String token = Base64.getEncoder().encodeToString("alice:S3cr3tPass!".getBytes(StandardCharsets.UTF_8));
        String all = json(data);
        assertThat(all).doesNotContain("S3cr3tPass!").doesNotContain(token).doesNotContain("tok-9876543210")
                .doesNotContain("abc123secretvalue");
        @SuppressWarnings("unchecked") List<String> transcript = (List<String>) path(data, 0).get("transcript");
        assertThat(transcript).contains("> Authorization: ••••", "> X-Custom-Token: ••••");
        assertThat(m(m(data.get("monitor")).get("advanced"))).containsEntry("custom_headers", 2).containsEntry("basic_auth", true);
    }

    @Test
    @DisplayName("Set-Cookie maskesi: ad + öznitelikler görünür, değer gizli; eşittirsiz değer tümüyle gizli")
    void setCookieMask() {
        assertThat(HttpDiagMasker.maskSetCookie("ASP.NET_SessionId=xyz; path=/; HttpOnly")).isEqualTo("ASP.NET_SessionId=••••; path=/; HttpOnly");
        assertThat(HttpDiagMasker.maskSetCookie("opaque")).isEqualTo("••••");
        HttpDiagMasker mk = new HttpDiagMasker(List.of("X-Tenant"), List.of());
        assertThat(mk.requestHeader("cookie", "a=b")).containsEntry("value", "••••").containsEntry("masked", true);
        assertThat(mk.requestHeader("X-Api-Key", "k")).containsEntry("masked", true);
        assertThat(mk.requestHeader("x-auth-token", "k")).containsEntry("masked", true);
        assertThat(mk.requestHeader("x-tenant", "acme")).containsEntry("masked", true);
        assertThat(mk.requestHeader("Host", "h")).containsEntry("masked", false);
        assertThat(mk.responseHeader("WWW-Authenticate", "Basic realm=\"x\"")).containsEntry("masked", false);
    }

    @Test
    @DisplayName("sır olabilecek özel başlık değeri: MIME türü süzgece girmez (tanı bozulmasın), jeton girer")
    void secretLike() {
        assertThat(HttpDiagnosticsService.secretLike("Accept", "application/json")).isFalse();
        assertThat(HttpDiagnosticsService.secretLike("X-Trace", "abc")).isFalse();
        assertThat(HttpDiagnosticsService.secretLike("X-Custom-Token", "x")).isTrue();
        assertThat(HttpDiagnosticsService.secretLike("X-Thing", "9f8e7d6c5b4a")).isTrue();
    }

    // ── Yan etki yok ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YAN ETKİ YOK: servis kontrol deposu / kesinti / eskalasyon / zamanlayıcıya bağlı DEĞİL; CA pinlemez; gerçek istemcinin tanılama girişi çağrılır")
    void noSideEffects() {
        for (Class<?> t : HttpDiagnosticsService.class.getDeclaredConstructors()[0].getParameterTypes()) {
            assertThat(t.getSimpleName()).isNotIn("HttpCheckRepository", "MonitoringOutageService", "EscalationService",
                    "SchedulerService", "HttpMonitorRepository", "AlertEventRepository");
        }
        service(null).diagnose(monitor(base + "/ok", 1500), false);
        verify(checker).checkForDiagnostics(eq(base + "/ok"), eq("GET"), eq("200-399"), eq(1500), eq(false), eq(true), eq(false), any());
        verify(checker, never()).check(anyString(), anyString(), any(), anyInt(), anyBoolean(), anyBoolean());
        verify(checker, never()).check(anyString(), anyString(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(checker, never()).check(anyString(), anyString(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any());
        verify(pin, never()).pinFromServer(anyString(), anyInt(), anyString());
        verify(pin, never()).recordTrustFailure(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("süre bütçesi 1–30 sn'ye kısılır")
    void timeoutClamp() {
        assertThat(HttpDiagnosticsService.clampTimeout(null)).isEqualTo(10_000);
        assertThat(HttpDiagnosticsService.clampTimeout(50)).isEqualTo(1_000);
        assertThat(HttpDiagnosticsService.clampTimeout(120_000)).isEqualTo(30_000);
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> clientResult(boolean ok, Integer status, String error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", ok);
        r.put("http_status", status);
        r.put("response_ms", 1L);
        r.put("http_version", null);
        r.put("error", error);
        return r;
    }

    /** Test içi vekil: iletici (mutlak biçim + CONNECT), 407 isteyen ya da hiç cevap vermeyen "kara delik". */
    static final class TestProxy implements Closeable {
        enum Mode { FORWARD, AUTH_407, DENY_403, BLACK_HOLE }

        private final ServerSocket server;
        private final Mode mode;
        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());

        static TestProxy forwarding() throws IOException { return new TestProxy(Mode.FORWARD); }
        static TestProxy requiringAuth() throws IOException { return new TestProxy(Mode.AUTH_407); }
        static TestProxy blackHole() throws IOException { return new TestProxy(Mode.BLACK_HOLE); }
        static TestProxy denying() throws IOException { return new TestProxy(Mode.DENY_403); }

        private TestProxy(Mode mode) throws IOException {
            this.mode = mode;
            this.server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(this::acceptLoop, "test-proxy");
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        List<String> requestLines() { synchronized (lines) { return new ArrayList<>(lines); } }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket c = server.accept();
                    open.add(c);
                    Thread t = new Thread(() -> handle(c), "test-proxy-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void handle(Socket c) {
            try {
                InputStream in = c.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int b, state = 0;
                while ((b = in.read()) != -1) {   // başlık sonuna (CRLFCRLF) kadar bayt bayt
                    head.write(b);
                    state = (b == '\r' || b == '\n') ? state + 1 : 0;
                    if (state == 4) break;
                }
                String h = head.toString(StandardCharsets.ISO_8859_1);
                for (String l : h.split("\r\n")) if (!l.isEmpty()) lines.add(l);
                String first = h.split("\r\n", 2)[0];
                OutputStream out = c.getOutputStream();
                if (mode == Mode.BLACK_HOLE) {
                    while (in.read() != -1) { /* hiç cevap verme — istemci kapatana dek tut */ }
                    return;
                }
                if (mode == Mode.DENY_403) {
                    out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }
                if (mode == Mode.AUTH_407 && !h.contains("Proxy-Authorization:")) {
                    out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"px\"\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }
                String[] parts = first.split(" ");
                Socket upstream;
                if ("CONNECT".equals(parts[0])) {
                    String[] hp = parts[1].split(":");
                    upstream = new Socket(hp[0], Integer.parseInt(hp[1]));
                    out.write("HTTP/1.1 200 Connection established\r\nVia: 1.1 test-proxy\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else {
                    URI u = URI.create(parts[1]);
                    upstream = new Socket(u.getHost(), u.getPort());
                    upstream.getOutputStream().write(head.toByteArray());   // mutlak biçim olduğu gibi iletilir
                    upstream.getOutputStream().flush();
                }
                open.add(upstream);
                Thread up = new Thread(() -> pipe(upstream, c), "test-proxy-up");
                up.setDaemon(true);
                up.start();
                pipe(c, upstream);
            } catch (IOException ignore) {
                // test vekili: bağlantı hataları önemsiz
            } finally {
                try { c.close(); } catch (IOException ignore) { /* kapalı */ }
            }
        }

        private static void pipe(Socket from, Socket to) {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException ignore) {
                // karşı taraf kapattı
            } finally {
                try { to.shutdownOutput(); } catch (IOException ignore) { /* kapalı */ }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (open) {
                for (Socket s : open) { try { s.close(); } catch (IOException ignore) { /* kapalı */ } }
            }
        }
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair kp, String dn) throws Exception {
        X500Name name = new X500Name(dn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.nanoTime()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(30, ChronoUnit.DAYS)),
                name, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.getPrivate());
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer));
    }
}
