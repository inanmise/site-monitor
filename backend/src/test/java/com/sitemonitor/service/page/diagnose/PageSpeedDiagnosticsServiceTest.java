package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.PageSpeedCheckerService;
import com.sitemonitor.service.PageSpeedCheckerService.Measured;
import com.sitemonitor.service.PageSpeedCheckerService.Result;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.failure.CheckFailure;
import com.sitemonitor.service.failure.CheckFailureReason;
import com.sitemonitor.service.page.HttpPhaseProbe;
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
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sayfa Hızı uçtan uca tanılaması (2026-10-05) — gerçek yerel sunucu (ham ölçüm) + sonucu sabitlenmiş gerçek ölçüm
 * ({@code client_check}). Pinlenenler: eşik aşımı (metrik, değer, sınır, fark), "neden yavaş" bulguları (sunucu fazı, büyük
 * HTML, kaynaklar, ağırlık), DOWN / HTTP durumu, kısmi ölçüm, vekil ↔ doğrudan (PATH_DIFFERS, PROXY_SLOWER / DIRECT_SLOWER),
 * izlemenin isteğinin aynısı (UA, DNT, Basic auth, şifreli özel başlıklar; çekirdek başlığını ezen özel başlık atılır) ve
 * sırların HİÇBİR alanda düz görünmemesi. Dış ağa çıkılmaz.
 */
class PageSpeedDiagnosticsServiceTest {

    private static final String UA = "SiteMonitor-PageSpeed/1.0 (test)";
    private static final String TOKEN = "tok-SECRET-123456";
    private static final String PASS = "S3cretPass!9";
    private static HttpServer http;
    private static String base;
    private static ServerSocket wafProxy;
    private static final Map<String, String> seen = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "psdx-test"); t.setDaemon(true); return t; }));
        http.createContext("/fast", ex -> {
            for (String h : List.of("User-Agent", "DNT", "Authorization", "X-Api-Token", "Accept-Encoding", "Accept")) {
                String v = ex.getRequestHeaders().getFirst(h);
                if (v != null) seen.put(h, v);
            }
            send(ex, 200, "<html><body><p>Hızlı</p></body></html>".getBytes(StandardCharsets.UTF_8));
        });
        http.createContext("/slow", ex -> {
            try { Thread.sleep(450); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            send(ex, 200, "<html><body>yavaş</body></html>".getBytes(StandardCharsets.UTF_8));
        });
        http.createContext("/big", ex -> {
            byte[] b = new byte[1_200_000];
            java.util.Arrays.fill(b, (byte) 'a');
            send(ex, 200, b);
        });
        http.createContext("/nf", ex -> send(ex, 404, "<html><body>yok</body></html>".getBytes(StandardCharsets.UTF_8)));
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();

        wafProxy = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!wafProxy.isClosed()) {
                try {
                    Socket s = wafProxy.accept();
                    Thread h = new Thread(() -> answerWaf(s), "psdx-waf");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "psdx-waf-accept");
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
            byte[] body = "<html><body>Access Denied</body></html>".getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/html\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException ignore) { /* istemci gitti */ }
    }

    private static void send(HttpExchange ex, int status, byte[] b) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    // ── Kurulum ─────────────────────────────────────────────────────────────────────────────────

    private AppSettingsService settings;
    private ProxyPolicyService policy;
    private CaAutoPinService caAutoPin;
    private SecretCipher cipher;

    @BeforeEach
    void mocks() {
        seen.clear();
        settings = mock(AppSettingsService.class);
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        lenient().when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn("");
        policy = mock(ProxyPolicyService.class);
        routeIs(false);
        caAutoPin = mock(CaAutoPinService.class);
        cipher = mock(SecretCipher.class);
        lenient().when(cipher.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));   // test: şifreli = düz
    }

    private void routeIs(boolean viaProxy) {
        when(policy.decide(anyString(), anyString()))
                .thenReturn(new ProxyPolicyService.Decision(viaProxy, "monitor", viaProxy, false));
    }

    private PageSpeedDiagnosticsService service(Result direct, Result viaProxy, ProxySettings proxy) {
        PageSpeedCheckerService checker = mock(PageSpeedCheckerService.class);
        when(checker.effectiveUserAgent(any())).thenReturn(UA);
        when(checker.effectiveTimeoutMs(any())).thenReturn(3000);
        when(checker.checkForDiagnostics(any(), eq(false), anyInt())).thenReturn(direct);
        lenient().when(checker.checkForDiagnostics(any(), eq(true), anyInt())).thenReturn(viaProxy);
        PageSpeedDiagnosticsService s = new PageSpeedDiagnosticsService(new SsrfGuard(settings), new TrustEvaluator(settings),
                caAutoPin, proxy, policy, checker, cipher);
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

    private static PageSpeedMonitor monitor(String url) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(71L);
        m.setName("Vitrin hızı");
        m.setUrl(url);
        m.setTimeoutMs(3000);
        m.setUseProxy("AUTO");
        m.setTeamId(5L);
        return m;
    }

    private static Result okResult(long totalMs, long bytes, int requests) {
        return new Result("OK", 200, 80, 90, totalMs, bytes, requests, 0, false, false, List.of(), null, List.of(),
                new HttpPhaseProbe.Phases(3, 5, 12, 60, null), 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) { return (List<Map<String, Object>>) o; }

    private static List<String> codes(Map<String, Object> data) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : list(data.get("findings"))) out.add((String) f.get("code"));
        return out;
    }

    private static List<Map<String, Object>> findings(Map<String, Object> data, String code) {
        return list(data.get("findings")).stream().filter(f -> code.equals(f.get("code"))).toList();
    }

    private static String json(Object o) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(o);
    }

    // ── Testler ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("eşikler içinde: PAGESPEED_OK; istek izlemeninkinin aynısı (UA, DNT, Basic auth, özel başlık, Accept*); sırlar hiçbir alanda düz değil")
    void ok_requestFidelity_masking() {
        PageSpeedMonitor mon = monitor(base + "/fast");
        mon.setSendDnt(true);
        mon.setBasicAuthUser("ops");
        mon.setBasicAuthPassEnc(PASS);
        mon.setCustomHeadersEnc("X-Api-Token: " + TOKEN + "\nUser-Agent: Evil/1.0");
        Map<String, Object> data = service(okResult(400, 50_000, 10), null, null).diagnose(mon, true);
        assertThat(data).containsKeys("run_id", "kind", "started_at", "duration_ms", "monitor", "source", "proxy",
                "verdict", "findings", "paths", "comparison", "pagespeed");
        assertThat(data).containsEntry("kind", "pagespeed");
        assertThat(m(data.get("verdict"))).containsEntry("status", "ok").containsEntry("code", "PAGESPEED_OK");
        assertThat(list(data.get("paths")).get(0)).containsEntry("outcome", "ok");
        // izlemenin isteği: UA izlemeninki (özel başlıktaki User-Agent çekirdekte olduğu gibi ATILDI), DNT, Basic, özel başlık
        assertThat(seen).containsEntry("User-Agent", UA).containsEntry("DNT", "1").containsEntry("X-Api-Token", TOKEN)
                .containsEntry("Accept-Encoding", "gzip, deflate");
        assertThat(seen.get("Authorization")).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString(("ops:" + PASS).getBytes(StandardCharsets.UTF_8)));
        // maske: hiçbir alanda jeton / parola / Basic jetonu yok; istek satırında değer gizli
        String all = json(data);
        assertThat(all).doesNotContain(TOKEN).doesNotContain(PASS)
                .doesNotContain(Base64.getEncoder().encodeToString(("ops:" + PASS).getBytes(StandardCharsets.UTF_8)))
                .doesNotContain("Evil/1.0");
        @SuppressWarnings("unchecked") List<String> transcript = (List<String>) list(data.get("paths")).get(0).get("transcript");
        assertThat(String.join("\n", transcript)).contains("> X-Api-Token: ••••").contains("> Authorization: ••••").contains("> DNT: 1");
        Map<String, Object> adv = m(m(data.get("monitor")).get("advanced"));
        assertThat(adv).containsEntry("custom_headers", 1).containsEntry("basic_auth", true).containsEntry("send_dnt", true);
        Map<String, Object> ps = m(data.get("pagespeed"));
        assertThat(ps).containsEntry("analyzed", true);
        assertThat(list(ps.get("routes"))).hasSize(1);
        assertThat(list(ps.get("metrics"))).extracting(x -> x.get("key")).containsExactly("LOAD", "TTFB", "SIZE", "REQUESTS");
        verify(caAutoPin, never()).pinFromServer(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("eşik aşımı (LOAD + SIZE): hüküm uyarı THRESHOLD_BREACH (LOAD) değer/sınır/fark; kaynak ve ağırlık bulguları; yol 'slow'")
    void breach_loadAndSize() {
        PageSpeedMonitor mon = monitor(base + "/fast");
        mon.setMaxLoadMs(5000);
        mon.setMaxPageKb(2000);
        List<Measured> rows = List.of(
                new Measured("https://cdn.example.test/hero.jpg?token=abc", "IMG", 3L * 1024 * 1024, 7000, 200, true, false, false),
                new Measured("https://shop.example.test/app.js", "JS", 600L * 1024, 900, 200, false, false, false),
                new Measured("https://shop.example.test/site.css", "CSS", 80L * 1024, 200, 200, false, false, false));
        Result slow = new Result("SLOW", 200, 80, 300, 9000, 4L * 1024 * 1024, 4, 0, false, false, List.of("LOAD", "SIZE"),
                null, rows, new HttpPhaseProbe.Phases(3, 5, 12, 60, null), 2);
        Map<String, Object> data = service(slow, null, null).diagnose(mon, true);
        assertThat(m(data.get("verdict"))).containsEntry("status", "warn").containsEntry("code", "THRESHOLD_BREACH");
        assertThat(m(m(data.get("verdict")).get("params"))).containsEntry("reason", "LOAD").containsEntry("value", 9000L)
                .containsEntry("limit", 5000).containsEntry("over", 4000L).containsEntry("unit", "ms");
        List<Map<String, Object>> breaches = findings(data, "THRESHOLD_BREACH");
        assertThat(breaches).hasSize(2);
        assertThat(m(breaches.get(1).get("params"))).containsEntry("reason", "SIZE").containsEntry("value", 4096L)
                .containsEntry("limit", 2000).containsEntry("unit", "KB");
        assertThat(m(findings(data, "SLOW_RESOURCES").get(0).get("params"))).containsEntry("top_ms", 7000L)
                .containsEntry("html_ms", 300L);
        assertThat((String) m(findings(data, "SLOW_RESOURCES").get(0).get("params")).get("top_url")).doesNotContain("token=abc");
        assertThat(m(findings(data, "HEAVY_RESOURCES").get(0).get("params"))).containsEntry("count", 3);
        assertThat(codes(data)).doesNotContain("PAGESPEED_OK");
        assertThat(list(data.get("paths")).get(0)).containsEntry("outcome", "slow");
        Map<String, Object> ps = m(data.get("pagespeed"));
        Map<String, Object> load = list(ps.get("metrics")).get(0);
        assertThat(load).containsEntry("key", "LOAD").containsEntry("breached", true).containsEntry("ratio", 180L);
        assertThat(list(ps.get("heaviest")).get(0)).containsEntry("type", "IMG").containsEntry("third_party", true);
        assertThat(list(ps.get("slowest")).get(0)).containsEntry("ms", 7000L);
        assertThat(list(ps.get("by_type")).get(0)).containsEntry("type", "IMG");
        assertThat(m(ps.get("measured"))).containsEntry("skipped_lazy", 2).containsEntry("server_ms", 60);
        assertThat(m(list(data.get("paths")).get(0).get("client_check"))).containsEntry("ok", true).containsEntry("status", "SLOW");
    }

    @Test
    @DisplayName("sunucu yavaş (TTFB eşiği 200 ms, sunucu 450 ms düşünüyor): SLOW_SERVER reason=threshold, sınır eşik")
    void slowServer() {
        PageSpeedMonitor mon = monitor(base + "/slow");
        mon.setMaxTtfbMs(200);
        Map<String, Object> data = service(okResult(600, 2000, 1), null, null).diagnose(mon, true);
        Map<String, Object> f = findings(data, "SLOW_SERVER").get(0);
        assertThat(f).containsEntry("severity", "warn").containsEntry("path", "monitor");
        assertThat(m(f.get("params"))).containsEntry("reason", "threshold").containsEntry("limit", 200L);
        assertThat(((Number) m(f.get("params")).get("ms")).longValue()).isGreaterThanOrEqualTo(400L);
        assertThat(m(data.get("verdict"))).containsEntry("status", "warn").containsEntry("code", "SLOW_SERVER");
        assertThat(m(m(data.get("pagespeed")).get("phase_limits"))).containsEntry("ttfb_ms", 200L);
    }

    @Test
    @DisplayName("HTML 1 MB üstü: LARGE_BODY (KB)")
    void largeBody() {
        Map<String, Object> data = service(okResult(800, 1_300_000, 1), null, null).diagnose(monitor(base + "/big"), true);
        Map<String, Object> p = m(findings(data, "LARGE_BODY").get(0).get("params"));
        assertThat(((Number) p.get("kb")).longValue()).isGreaterThanOrEqualTo(1024L);
        assertThat(list(m(data.get("pagespeed")).get("routes")).get(0).get("body_bytes")).isEqualTo(1_200_000L);
    }

    @Test
    @DisplayName("ham ölçüm sayfayı aldı, izlemenin ölçümü alamadı: PAGESPEED_DOWN neden koduyla; HTTP 404 → PAGESPEED_HTTP_STATUS")
    void down_and_httpStatus() {
        Result down = new Result("DOWN", null, 0, 3000, 3000, 0, 1, 1, false, false, List.of(), "request timed out", List.of(),
                HttpPhaseProbe.NONE, 0, CheckFailure.of(CheckFailureReason.READ_TIMEOUT));
        Map<String, Object> data = service(down, null, null).diagnose(monitor(base + "/fast"), true);
        assertThat(m(data.get("verdict"))).containsEntry("status", "fail").containsEntry("code", "PAGESPEED_DOWN")
                .containsEntry("failed_step", "response");
        assertThat(m(findings(data, "PAGESPEED_DOWN").get(0).get("params"))).containsEntry("failure", "READ_TIMEOUT");
        assertThat(m(data.get("pagespeed"))).containsEntry("analyzed", false);

        Result nf = new Result("DOWN", 404, 10, 12, 15, 30, 1, 1, false, false, List.of(), "sayfa HTTP 404", List.of(),
                HttpPhaseProbe.NONE, 0, CheckFailure.of(CheckFailureReason.HTTP_STATUS));
        Map<String, Object> d2 = service(nf, null, null).diagnose(monitor(base + "/nf"), true);
        assertThat(m(d2.get("verdict"))).containsEntry("code", "PAGESPEED_HTTP_STATUS");
        assertThat(codes(d2)).doesNotContain("PAGESPEED_DOWN", "CLIENT_MISMATCH");
    }

    @Test
    @DisplayName("kısmi ölçüm + inmeyen kaynak: MEASUREMENT_PARTIAL (resources) uyarı, RESOURCES_FAILED bilgi")
    void partial_failed() {
        List<Measured> rows = List.of(new Measured("https://cdn.example.test/x.js", "JS", 0, 12, 404, true, true, false));
        Result r = new Result("OK", 200, 50, 60, 700, 90_000, 501, 2, true, false, List.of(), null, rows,
                new HttpPhaseProbe.Phases(1, 1, 1, 40, null), 0);
        Map<String, Object> data = service(r, null, null).diagnose(monitor(base + "/fast"), true);
        assertThat(findings(data, "MEASUREMENT_PARTIAL").get(0)).containsEntry("severity", "warn");
        assertThat(m(findings(data, "MEASUREMENT_PARTIAL").get(0).get("params"))).containsEntry("reason", "resources");
        assertThat(m(findings(data, "RESOURCES_FAILED").get(0).get("params"))).containsEntry("count", 2);
        assertThat(m(data.get("verdict"))).containsEntry("code", "MEASUREMENT_PARTIAL");
    }

    @Test
    @DisplayName("vekil (WAF 403) ↔ doğrudan (200): PATH_DIFFERS reason=pagespeed; yol satırları iki yolu da taşır")
    void proxyVsDirect_pathDiffers() {
        routeIs(true);
        Result blocked = new Result("DOWN", 403, 5, 6, 8, 40, 1, 1, false, false, List.of(), "sayfa HTTP 403", List.of(),
                HttpPhaseProbe.NONE, 0, CheckFailure.of(CheckFailureReason.HTTP_STATUS));
        Map<String, Object> data = service(okResult(400, 50_000, 10), blocked, proxyAt(wafProxy.getLocalPort()))
                .diagnose(monitor(base + "/fast"), true);
        assertThat(m(data.get("verdict"))).containsEntry("code", "PATH_DIFFERS");
        assertThat(m(findings(data, "PATH_DIFFERS").get(0).get("params"))).containsEntry("reason", "pagespeed")
                .containsEntry("failing_route", "proxy");
        List<Map<String, Object>> routes = list(m(data.get("pagespeed")).get("routes"));
        assertThat(routes).extracting(r -> r.get("route")).containsExactly("proxy", "direct");
        assertThat(routes.get(0)).containsEntry("http_status", 403);
        assertThat(codes(data)).doesNotContain("PROXY_SLOWER");
    }

    @Test
    @DisplayName("yol hızı: izlemenin vekil yolu belirgin yavaş → PROXY_SLOWER; doğrudan yavaş → DIRECT_SLOWER; fark küçük/düşen yol → yok")
    void routeFinding() {
        List<PageDiagSupport.Plan> viaProxy = List.of(new PageDiagSupport.Plan("monitor", "proxy", Map.of()),
                new PageDiagSupport.Plan("alternate", "direct", Map.of()));
        List<PageSpeedDiagnosticsService.ClientRun> clients = List.of(
                new PageSpeedDiagnosticsService.ClientRun(okResult(3000, 1, 1), 3000),
                new PageSpeedDiagnosticsService.ClientRun(okResult(1000, 1, 1), 1000));
        List<com.sitemonitor.service.http.diagnose.RawHttpProbe> probes = new ArrayList<>();
        probes.add(null);
        probes.add(null);
        Map<String, Object> f = PageSpeedDiagnosticsService.routeFinding(viaProxy, List.of("ok", "ok"), probes, clients);
        assertThat(f).containsEntry("code", "PROXY_SLOWER").containsEntry("severity", "warn").containsEntry("path", "monitor");
        assertThat(m(f.get("params"))).containsEntry("slow_ms", 3000L).containsEntry("fast_ms", 1000L).containsEntry("ratio", 3.0)
                .containsEntry("slow_route", "proxy").containsEntry("fast_route", "direct");

        List<PageDiagSupport.Plan> direct = List.of(new PageDiagSupport.Plan("monitor", "direct", Map.of()),
                new PageDiagSupport.Plan("alternate", "proxy", Map.of()));
        assertThat(PageSpeedDiagnosticsService.routeFinding(direct, List.of("slow", "ok"), probes, clients))
                .containsEntry("code", "DIRECT_SLOWER");
        // fark < 500 ms
        List<PageSpeedDiagnosticsService.ClientRun> close = List.of(
                new PageSpeedDiagnosticsService.ClientRun(okResult(900, 1, 1), 900),
                new PageSpeedDiagnosticsService.ClientRun(okResult(500, 1, 1), 500));
        assertThat(PageSpeedDiagnosticsService.routeFinding(viaProxy, List.of("ok", "ok"), probes, close)).isNull();
        // öteki yol düşüyor → karşılaştırma yok
        assertThat(PageSpeedDiagnosticsService.routeFinding(viaProxy, List.of("ok", "fail"), probes, clients)).isNull();
    }

    @Test
    @DisplayName("YAN ETKİSİZ YAPI: servis hiçbir depo / sweep / alarm / eskalasyon bileşeni taşımaz")
    void sideEffectFreeByConstruction() {
        for (Class<?> c = PageSpeedDiagnosticsService.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String type = f.getType().getName();
                assertThat(type).as(c.getSimpleName() + "." + f.getName())
                        .doesNotContain("Repository").doesNotContain("SchedulerService").doesNotContain("MonitoringOutageService")
                        .doesNotContain("EscalationService").doesNotContain("ActivityLog");
            }
        }
    }
}
