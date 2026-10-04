package com.sitemonitor.service.keyword.diagnose;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.KeywordCheckerService;
import com.sitemonitor.service.KeywordHeaderSecrets;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Keyword uçtan uca tanılaması (2026-10-04) — gerçek yerel sunucu + test içi "WAF vekili" + izlemenin GERÇEK istemcisi
 * ({@code client_check}). Pinlenenler: hüküm/bulgular, anahtar kelime çözümlemesi (adet, bağlam, alternatifler, ipuçları),
 * izlemenin isteğinin aynısı (User-Agent, özel başlıklar), vekil↔doğrudan karşılaştırması (PATH_DIFFERS), sırların HİÇBİR
 * alanda düz görünmemesi ve yan etkisizlik (yalnız başlık okunur). Dış ağa çıkılmaz.
 */
class KeywordDiagnosticsServiceTest {

    private static final String SECRET = "tok-SECRET-123456";
    private static HttpServer http;
    private static String base;
    private static ServerSocket wafProxy;

    @BeforeAll
    static void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "kwdx-test"); t.setDaemon(true); return t; }));
        http.createContext("/ok", ex -> send(ex, 200, "text/html; charset=utf-8",
                "<html><head><title>Mağaza</title></head><body><p>Kampanya başladı!</p><p>Yeni kampanya ürünleri</p></body></html>"));
        http.createContext("/login", ex -> send(ex, 200, "text/html; charset=utf-8",
                "<html><head><title>Oturum aç</title></head><body><form><input name=\"u\"><input type=\"password\" name=\"p\"></form></body></html>"));
        http.createContext("/upper", ex -> send(ex, 200, "text/plain", "durum: tamam"));
        http.createContext("/echo", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "SID=abcdef0123456789secret; Path=/; HttpOnly");
            send(ex, 200, "application/json", "{\"password\":\"hunter2\",\"seen\":\""
                    + ex.getRequestHeaders().getFirst("Authorization") + "\",\"ua\":\"" + ex.getRequestHeaders().getFirst("User-Agent") + "\"}");
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();

        // Test içi "vekil": her isteğe WAF engelleme sayfası (403) döner — vekil yolunun bozuk, doğrudan yolun sağlam olduğu vaka.
        wafProxy = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!wafProxy.isClosed()) {
                try {
                    Socket s = wafProxy.accept();
                    Thread h = new Thread(() -> answerWaf(s), "kwdx-waf");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "kwdx-waf-accept");
        t.setDaemon(true);
        t.start();
    }

    @AfterAll
    static void stop() throws IOException {
        if (http != null) http.stop(0);
        if (wafProxy != null) wafProxy.close();
    }

    private static void answerWaf(Socket s) {
        try (s) {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, state = 0;
            while ((b = in.read()) != -1) {
                head.write(b);
                state = (b == '\r' || b == '\n') ? state + 1 : 0;
                if (state >= 4) break;
            }
            byte[] body = ("<html><head><title>Access Denied</title></head><body>The requested URL was rejected. "
                    + "Please consult with your administrator. Your support ID is: 987654</body></html>").getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/html\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException ignore) { /* istemci gitti */ }
    }

    private static void send(HttpExchange ex, int status, String ct, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", ct);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    // ── Kurulum ─────────────────────────────────────────────────────────────────────────────────

    private AppSettingsService settings;
    private ProxyPolicyService policy;
    private KeywordHeaderSecrets headers;

    @BeforeEach
    void mocks() {
        settings = mock(AppSettingsService.class);
        when(settings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(settings.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(settings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn("");
        policy = mock(ProxyPolicyService.class);
        routeIs(false);
        headers = mock(KeywordHeaderSecrets.class);
    }

    private void routeIs(boolean viaProxy) {
        when(policy.decide(anyString(), anyString()))
                .thenReturn(new ProxyPolicyService.Decision(viaProxy, "monitor", viaProxy, false));
    }

    private KeywordDiagnosticsService service(ProxySettings proxy) {
        KeywordCheckerService checker = new KeywordCheckerService(new SsrfGuard(settings));
        if (proxy != null) ReflectionTestUtils.setField(checker, "proxySettings", proxy);
        checker.init();
        KeywordDiagnosticsService s = new KeywordDiagnosticsService(new SsrfGuard(settings), new TrustEvaluator(settings),
                checker, mock(CaAutoPinService.class), proxy, policy, headers);
        s.env = k -> null;
        return s;
    }

    private static ProxySettings proxyAt(int port) {
        ProxySettings p = new ProxySettings();
        ReflectionTestUtils.setField(p, "host", "127.0.0.1");
        ReflectionTestUtils.setField(p, "port", port);
        ReflectionTestUtils.setField(p, "user", "");
        ReflectionTestUtils.setField(p, "pass", "");
        ReflectionTestUtils.setField(p, "noProxy", "intranet.example");
        return p;
    }

    private static KeywordMonitor monitor(String url, String keyword, String op, int n, boolean cs) {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(41L);
        m.setName("Kelime tanı");
        m.setUrl(url);
        m.setKeyword(keyword);
        m.setMatchOperator(op);
        m.setMatchCount(n);
        m.setCaseSensitive(cs);
        m.setTimeoutMs(3000);
        m.setUseProxy("AUTO");
        m.setTeamId(5L);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) { return (List<Map<String, Object>>) o; }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x));
        return out;
    }

    private static List<String> codes(Map<String, Object> data) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : list(data.get("findings"))) out.add((String) f.get("code"));
        return out;
    }

    private static String json(Object o) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(o);
    }

    // ── Testler ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bulunamadı (giriş sayfası): hüküm KEYWORD_NOT_FOUND, LOGIN_PAGE ipucu, istemci de düşüyor, sözleşme blokları")
    void notFound_loginPage() {
        Map<String, Object> data = service(null).diagnose(monitor(base + "/login", "Kampanya", "GTE", 1, false), true);
        assertThat(data).containsKeys("run_id", "kind", "started_at", "duration_ms", "monitor", "source", "proxy",
                "verdict", "findings", "paths", "comparison", "keyword");
        assertThat(data).containsEntry("kind", "keyword");
        assertThat(m(data.get("verdict"))).containsEntry("status", "fail").containsEntry("code", "KEYWORD_NOT_FOUND")
                .containsEntry("failed_step", "body");
        assertThat(codes(data)).contains("KEYWORD_NOT_FOUND", "LOGIN_PAGE");
        assertThat(m(data.get("comparison"))).containsEntry("available", false);
        Map<String, Object> kw = m(data.get("keyword"));
        assertThat(kw).containsEntry("occurrences", 0).containsEntry("condition_met", false).containsEntry("analyzed", true)
                .containsEntry("failure_reason", "KEYWORD_NOT_FOUND").containsEntry("http_status", 200)
                .containsEntry("checker_cap_bytes", 2_000_000);
        assertThat(strings(kw.get("hints"))).contains("LOGIN_PAGE");
        assertThat((String) kw.get("visible_text_preview")).contains("Oturum aç");
        Map<String, Object> path = list(data.get("paths")).get(0);
        assertThat(path).containsEntry("key", "monitor").containsEntry("route", "direct").containsEntry("outcome", "fail")
                .containsEntry("http_status", 200);
        assertThat(m(path.get("keyword"))).containsEntry("occurrences", 0).containsEntry("condition_met", false);
        Map<String, Object> client = m(path.get("client_check"));
        assertThat(client).containsEntry("ok", false).containsEntry("failure_reason", "KEYWORD_NOT_FOUND").containsEntry("occurrences", 0);
        // İzlemenin isteğinin aynısı: keyword izlemesinin User-Agent'ı
        Map<String, Object> hop = list(path.get("hops")).get(0);
        assertThat(json(m(hop.get("request")).get("headers"))).contains("SiteMonitor-KeywordMonitor/1.0");
        assertThat(m(data.get("monitor"))).containsEntry("keyword", "Kampanya").containsEntry("method", "GET")
                .containsEntry("verify_ssl", false);
    }

    @Test
    @DisplayName("bulundu: KEYWORD_OK, en çok 5 bağlam (vurgu parçası), alternatif sayımlar, istemciyle uyum (CLIENT_MISMATCH yok)")
    void found_contexts() {
        Map<String, Object> data = service(null).diagnose(monitor(base + "/ok", "kampanya", "GTE", 1, false), true);
        assertThat(m(data.get("verdict"))).containsEntry("status", "ok").containsEntry("code", "KEYWORD_OK");
        assertThat(codes(data)).doesNotContain("CLIENT_MISMATCH");
        Map<String, Object> kw = m(data.get("keyword"));
        assertThat(kw).containsEntry("occurrences", 2).containsEntry("condition_met", true);
        assertThat(strings(kw.get("hints"))).isEmpty();
        List<Map<String, Object>> ctx = list(kw.get("contexts"));
        assertThat(ctx).hasSize(2);
        assertThat(ctx.get(0)).containsEntry("match", "Kampanya").containsEntry("source", "visible");
        assertThat(m(kw.get("alternatives"))).containsEntry("raw", 2).containsEntry("case_insensitive", 2);
        assertThat(m(list(data.get("paths")).get(0).get("client_check"))).containsEntry("ok", true);
    }

    @Test
    @DisplayName("harf duyarlı kural + sayfada farklı harf: CASE_MISMATCH ipucu bulgu olarak ve alternatif sayımda")
    void caseMismatch() {
        Map<String, Object> data = service(null).diagnose(monitor(base + "/upper", "TAMAM", "GTE", 1, true), true);
        assertThat(m(data.get("verdict"))).containsEntry("code", "KEYWORD_NOT_FOUND");
        assertThat(codes(data)).contains("CASE_MISMATCH");
        assertThat(m(m(data.get("keyword")).get("alternatives"))).containsEntry("raw", 0).containsEntry("case_insensitive", 1);
    }

    @Test
    @DisplayName("maske: özel başlık değeri ve yansıtılan jeton, Set-Cookie değeri, JSON parola alanı HİÇBİR alanda düz değil")
    void secretsMaskedEverywhere() {
        when(headers.effectiveHeaders(any())).thenReturn("Authorization: Bearer " + SECRET + "\nAccept: text/html");
        Map<String, Object> data = service(null).diagnose(monitor(base + "/echo", "welcome", "GTE", 1, false), true);
        String all = json(data);
        assertThat(all).doesNotContain(SECRET).doesNotContain("abcdef0123456789secret").doesNotContain("hunter2");
        Map<String, Object> hop = list(list(data.get("paths")).get(0).get("hops")).get(0);
        assertThat(json(m(hop.get("request")).get("headers"))).contains("Authorization").contains("••••");
        @SuppressWarnings("unchecked") List<String> transcript = (List<String>) list(data.get("paths")).get(0).get("transcript");
        assertThat(String.join("\n", transcript)).contains("> Authorization: ••••").doesNotContain(SECRET);
        assertThat(m(m(data.get("monitor")).get("advanced"))).containsEntry("custom_headers", 2);
        // yan etkisizlik: başlık sırrı yalnız OKUNDU (şifreleme/yazma yok)
        verify(headers).effectiveHeaders(any());
        verifyNoMoreInteractions(headers);
    }

    @Test
    @DisplayName("vekil (WAF 403) ↔ doğrudan (200, kelime var): PATH_DIFFERS hükmü, iki yol, vekil yolunda WAF ipucu + HTTP hata bulgusu")
    void proxyVsDirect_pathDiffers() {
        routeIs(true);
        Map<String, Object> data = service(proxyAt(wafProxy.getLocalPort())).diagnose(monitor(base + "/ok", "Kampanya", "GTE", 1, false), true);
        assertThat(m(data.get("verdict"))).containsEntry("code", "PATH_DIFFERS").containsEntry("status", "fail");
        assertThat(m(data.get("comparison"))).containsEntry("available", true).containsEntry("differs", true);
        List<Map<String, Object>> paths = list(data.get("paths"));
        assertThat(paths).hasSize(2);
        assertThat(paths.get(0)).containsEntry("key", "monitor").containsEntry("route", "proxy").containsEntry("outcome", "fail")
                .containsEntry("http_status", 403);
        assertThat(paths.get(1)).containsEntry("key", "alternate").containsEntry("route", "direct").containsEntry("outcome", "ok");
        assertThat(m(paths.get(1).get("keyword"))).containsEntry("occurrences", 2).containsEntry("condition_met", true);
        assertThat(codes(data)).contains("KEYWORD_HTTP_ERROR", "WAF_OR_BLOCK_PAGE");
        assertThat(strings(m(data.get("keyword")).get("hints"))).contains("WAF_OR_BLOCK_PAGE");
        // izlemenin gerçek istemcisi de vekil yolunda düşüyor, doğrudan yolda geçiyor
        assertThat(m(paths.get(0).get("client_check"))).containsEntry("ok", false).containsEntry("http_status", 403);
        assertThat(m(paths.get(1).get("client_check"))).containsEntry("ok", true);
        assertThat(m(data.get("proxy"))).containsEntry("configured", true).containsEntry("auth", false);
    }

    @Test
    @DisplayName("compare=false: vekil tanımlı olsa da tek yol")
    void compareOff() {
        Map<String, Object> data = service(proxyAt(wafProxy.getLocalPort())).diagnose(monitor(base + "/ok", "Kampanya", "GTE", 1, false), false);
        assertThat(list(data.get("paths"))).hasSize(1);
        assertThat(m(data.get("comparison"))).containsEntry("available", false);
    }

    @Test
    @DisplayName("URL'deki {timestamp} izlemedeki gibi güncel saniyeyle değiştirilir (ham soket yolu da geçerli URL görür)")
    void timestampPlaceholder() {
        Map<String, Object> data = service(null).diagnose(monitor(base + "/ok?t={timestamp}", "Kampanya", "GTE", 1, false), true);
        Map<String, Object> path = list(data.get("paths")).get(0);
        assertThat(path).containsEntry("outcome", "ok");
        assertThat((String) m(list(path.get("hops")).get(0).get("request")).get("line")).matches("GET /ok\\?t=\\d+ HTTP/1\\.1");
    }

    @Test
    @DisplayName("'olmamalı' kuralı: yasak kelime bulundu → KEYWORD_FOUND_FORBIDDEN; bağlam eşleşmeyi gösterir")
    void forbidden() {
        Map<String, Object> data = service(null).diagnose(monitor(base + "/ok", "Kampanya başladı", "LTE", 0, false), true);
        assertThat(m(data.get("verdict"))).containsEntry("code", "KEYWORD_FOUND_FORBIDDEN");
        assertThat(list(m(data.get("keyword")).get("contexts"))).hasSize(1);
        assertThat(m(data.get("keyword"))).containsEntry("absence_rule", true);
    }
}
