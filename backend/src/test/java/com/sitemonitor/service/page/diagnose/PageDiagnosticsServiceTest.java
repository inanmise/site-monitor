package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.PageCheckerService.PageCheckResult;
import com.sitemonitor.service.PageCheckerService.ResourceIssue;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.failure.CheckFailure;
import com.sitemonitor.service.failure.CheckFailureReason;
import com.sitemonitor.service.page.PageFetchCore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

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
 * Sayfa Bütünlüğü uçtan uca tanılaması (2026-10-05) — gerçek yerel sunucu + test içi "WAF vekili"; izlemenin gerçek
 * kontrolü ya GERÇEK {@link PageCheckerService} (çekirdek + PSL; yerel sunucuya gider) ya da sonucu sabitlenmiş sahte
 * (bulgu dalları). Pinlenenler: hüküm / bulgu dalları (kırık, zaman aşımı, mixed, birinci ↔ üçüncü taraf, belirsiz, yavaş,
 * sınırlar, DOWN), izlemenin isteğinin aynısı (User-Agent, çekirdeğin Accept* başlıkları, gzip önizlemesi), vekil ↔
 * doğrudan karşılaştırması, sırların/URL sorgusunun düz görünmemesi, yan etkisizlik. Dış ağa çıkılmaz.
 */
class PageDiagnosticsServiceTest {

    private static final String UA = "Mozilla/5.0 (compatible; SiteMonitor-PageCheck/1.0; +https://sitemonitor)";
    private static HttpServer http;
    private static String base;
    private static ServerSocket wafProxy;
    private static volatile String lastAcceptEncoding;
    private static volatile String lastUserAgent;
    private static volatile String lastAcceptLanguage;

    @BeforeAll
    static void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "pgdx-test"); t.setDaemon(true); return t; }));
        http.createContext("/page", ex -> {
            lastAcceptEncoding = ex.getRequestHeaders().getFirst("Accept-Encoding");
            lastUserAgent = ex.getRequestHeaders().getFirst("User-Agent");
            lastAcceptLanguage = ex.getRequestHeaders().getFirst("Accept-Language");
            sendGzipIfAsked(ex, "<html><head><title>Mağaza</title><link rel=\"stylesheet\" href=\"/ok.css\"></head>"
                    + "<body><p>Merhaba</p></body></html>");
        });
        http.createContext("/broken", ex -> send(ex, 200, "text/html; charset=utf-8",
                "<html><body><img src=\"/missing.png?token=GIZLIJETON123\"><link rel=\"stylesheet\" href=\"/ok.css\"></body></html>"));
        http.createContext("/ok.css", ex -> send(ex, 200, "text/css", "body{color:#000}"));
        http.createContext("/missing.png", ex -> send(ex, 404, "text/plain", "yok"));
        http.createContext("/err", ex -> send(ex, 500, "text/html", "<html><body>Sunucu hatası</body></html>"));
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();

        wafProxy = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!wafProxy.isClosed()) {
                try {
                    Socket s = wafProxy.accept();
                    Thread h = new Thread(() -> answerWaf(s), "pgdx-waf");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "pgdx-waf-accept");
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
            byte[] body = "<html><head><title>Access Denied</title></head><body>Rejected</body></html>".getBytes(StandardCharsets.UTF_8);
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
        if ("HEAD".equals(ex.getRequestMethod())) {   // kaynak doğrulaması önce HEAD dener — gövdesiz yanıt
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static void sendGzipIfAsked(HttpExchange ex, String body) throws IOException {
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        if (ae != null && ae.contains("gzip")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(raw); }
            raw = bos.toByteArray();
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, raw.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(raw); }
    }

    // ── Kurulum ─────────────────────────────────────────────────────────────────────────────────

    private AppSettingsService settings;
    private ProxyPolicyService policy;
    private CaAutoPinService caAutoPin;
    private PageFetchCore core;

    @BeforeEach
    void mocks() {
        settings = mock(AppSettingsService.class);
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        lenient().when(settings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        lenient().when(settings.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        lenient().when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn("");
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        policy = mock(ProxyPolicyService.class);
        routeIs(false);
        caAutoPin = mock(CaAutoPinService.class);
    }

    @AfterEach
    void closeCore() {
        if (core != null) core.shutdown();
    }

    private void routeIs(boolean viaProxy) {
        when(policy.decide(anyString(), anyString()))
                .thenReturn(new ProxyPolicyService.Decision(viaProxy, "monitor", viaProxy, false));
    }

    private PageCheckerService realChecker() {
        SsrfGuard guard = new SsrfGuard(settings);
        core = new PageFetchCore(guard);
        core.init();
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        return new PageCheckerService(core, psl, settings);
    }

    private PageDiagnosticsService service(PageCheckerService checker, ProxySettings proxy) {
        PageDiagnosticsService s = new PageDiagnosticsService(new SsrfGuard(settings), new TrustEvaluator(settings),
                caAutoPin, proxy, policy, checker, settings);
        s.env = k -> null;
        return s;
    }

    /** Sonucu sabitlenmiş gerçek kontrol (vekil yolu ayrı). */
    private static PageCheckerService fakeChecker(PageCheckResult direct, PageCheckResult viaProxy) {
        PageCheckerService c = mock(PageCheckerService.class);
        when(c.effectiveUserAgent()).thenReturn(UA);
        when(c.check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), eq(false)))
                .thenReturn(direct);
        when(c.check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), eq(true)))
                .thenReturn(viaProxy);
        return c;
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

    private static PageMonitor monitor(String url) {
        PageMonitor m = new PageMonitor();
        m.setId(61L);
        m.setName("Mağaza sayfası");
        m.setUrl(url);
        m.setTimeoutMs(3000);
        m.setSlowResourceMs(2000);
        m.setResourceConcurrency(5);
        m.setUseProxy("AUTO");
        m.setTeamId(5L);
        return m;
    }

    private static PageCheckResult ok(int total, List<ResourceIssue> issues) {
        int broken = 0, timeouts = 0, mixed = 0;
        for (ResourceIssue i : issues) {
            if ("BROKEN".equals(i.issueType())) broken++;
            if ("TIMEOUT".equals(i.issueType())) timeouts++;
            if ("MIXED_CONTENT".equals(i.issueType())) mixed++;
        }
        String status = broken + timeouts + mixed > 0 ? "DEGRADED" : "OK";
        return new PageCheckResult(status, true, 200, 120L, total, broken, timeouts, mixed, 1, "h", 900L, null, issues);
    }

    private static ResourceIssue issue(String url, String type, String kind, boolean firstParty, Integer status, Long ms) {
        return new ResourceIssue(url, type, "https://shop.example.test/", kind, firstParty, status, ms);
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

    private static Map<String, Object> finding(Map<String, Object> data, String code) {
        return list(data.get("findings")).stream().filter(f -> code.equals(f.get("code"))).findFirst().orElseThrow();
    }

    private static String json(Object o) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(o);
    }

    // ── Testler ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sağlıklı sayfa (gerçek kontrol): PAGE_OK, sözleşme blokları, izlemenin isteğinin aynısı + gzip önizlemesi açılır")
    void ok_realChecker_requestFidelity() {
        Map<String, Object> data = service(realChecker(), null).diagnose(monitor(base + "/page"), true);
        assertThat(data).containsKeys("run_id", "kind", "started_at", "duration_ms", "monitor", "source", "proxy",
                "verdict", "findings", "paths", "comparison", "page");
        assertThat(data).containsEntry("kind", "page");
        assertThat(m(data.get("verdict"))).containsEntry("status", "ok").containsEntry("code", "PAGE_OK");
        assertThat(codes(data)).doesNotContain("CLIENT_MISMATCH");
        Map<String, Object> path = list(data.get("paths")).get(0);
        assertThat(path).containsEntry("key", "monitor").containsEntry("route", "direct").containsEntry("outcome", "ok")
                .containsEntry("http_status", 200);
        assertThat(m(path.get("client_check"))).containsEntry("ok", true).containsEntry("status", "OK");
        // İzlemenin isteğinin aynısı: Sayfa Bütünlüğü UA'sı + çekirdeğin Accept-Language / Accept-Encoding başlıkları
        assertThat(lastUserAgent).isEqualTo(UA);
        assertThat(lastAcceptEncoding).isEqualTo(PageFetchCore.ACCEPT_ENCODING);
        assertThat(lastAcceptLanguage).isEqualTo(PageFetchCore.ACCEPT_LANGUAGE);
        Map<String, Object> hop = list(path.get("hops")).get(0);
        String reqHeaders = json(m(hop.get("request")).get("headers"));
        assertThat(reqHeaders).contains(PageFetchCore.ACCEPT).contains("gzip, deflate").contains("SiteMonitor-PageCheck");
        // Gzip gövde: sayım teldeki bayt, önizleme açılmış metin
        Map<String, Object> body = m(m(hop.get("response")).get("body"));
        assertThat(body).containsEntry("content_encoding", "gzip").containsEntry("text", true);
        assertThat((String) body.get("preview")).contains("Merhaba");
        Map<String, Object> page = m(data.get("page"));
        assertThat(page).containsEntry("analyzed", true).containsEntry("recorded_status", "OK").containsEntry("monitor_ok", true)
                .containsEntry("mode", "SINGLE_PAGE");
        assertThat(m(page.get("totals"))).containsEntry("resources", 1).containsEntry("broken", 0).containsEntry("alarm", 0);
        // Yan etkisiz: CA pinlenmez, güven hatası kaydedilmez
        verify(caAutoPin, never()).pinFromServer(anyString(), anyInt(), anyString());
        verify(caAutoPin, never()).recordTrustFailure(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("kendi sitesinde kırık görsel (gerçek kontrol): RESOURCES_BROKEN fail + SAME_HOST_BROKEN; satırda URL sorgusu maskeli")
    void brokenFirstParty_realChecker() {
        Map<String, Object> data = service(realChecker(), null).diagnose(monitor(base + "/broken"), true);
        assertThat(m(data.get("verdict"))).containsEntry("status", "fail").containsEntry("code", "RESOURCES_BROKEN")
                .containsEntry("failed_step", "resources");
        assertThat(codes(data)).contains("RESOURCES_BROKEN", "SAME_HOST_BROKEN").doesNotContain("THIRD_PARTY_ONLY", "PAGE_OK");
        assertThat(list(data.get("paths")).get(0)).containsEntry("outcome", "fail");
        Map<String, Object> page = m(data.get("page"));
        assertThat(page).containsEntry("recorded_status", "DEGRADED").containsEntry("monitor_ok", false);
        Map<String, Object> row = list(page.get("issues")).get(0);
        assertThat(row).containsEntry("kind", "BROKEN").containsEntry("status", 404).containsEntry("alarm", true)
                .containsEntry("first_party", true).containsEntry("resource_type", "IMG").containsEntry("via", "direct");
        assertThat((String) row.get("url")).contains("/missing.png").doesNotContain("GIZLIJETON123");
        // Çözümleme ve bulgularda sorgu değeri maskeli (gövde önizlemesi sayfanın kendi HTML'idir — HTTP tanılamasıyla aynı
        // kural: canlı gösterilir, geçmişe yazılmaz).
        assertThat(json(data.get("page"))).doesNotContain("GIZLIJETON123");
        assertThat(json(data.get("findings"))).doesNotContain("GIZLIJETON123");
        assertThat(json(data.get("verdict"))).doesNotContain("GIZLIJETON123");
    }

    @Test
    @DisplayName("yalnız üçüncü taraf kırık (alarm kapalı): izleme OK; hüküm uyarı RESOURCES_BROKEN, THIRD_PARTY_ONLY bilgi (quiet)")
    void thirdPartyOnly_quiet() {
        // dış JS 404 (3. taraf alarmı kapalı → alarm yok) + dış bağlantı zaman aşımı (bağlantı zaman aşımı hiç alarm değil)
        PageCheckResult r = ok(12, List.of(
                issue("https://cdn.example.test/lib.js", "JS", "BROKEN", false, 404, 30L),
                issue("https://fonts.example.test/page", "LINK", "TIMEOUT", false, null, 3000L)));
        Map<String, Object> data = service(fakeChecker(r, r), null).diagnose(monitor(base + "/page"), true);
        assertThat(m(data.get("verdict"))).containsEntry("status", "warn").containsEntry("code", "RESOURCES_BROKEN");
        assertThat(list(data.get("paths")).get(0)).containsEntry("outcome", "ok");
        Map<String, Object> third = finding(data, "THIRD_PARTY_ONLY");
        assertThat(third).containsEntry("severity", "info");
        assertThat(m(third.get("params"))).containsEntry("count", 2).containsEntry("reason", "quiet");
        assertThat((String) m(third.get("params")).get("hosts")).contains("cdn.example.test").contains("fonts.example.test");
        assertThat(finding(data, "RESOURCES_TIMEOUT")).containsEntry("severity", "warn");
        assertThat(codes(data)).contains("PAGE_OK").doesNotContain("SAME_HOST_BROKEN");
    }

    @Test
    @DisplayName("üçüncü taraf alarmı AÇIK: kırık dış kaynak alarm → fail; THIRD_PARTY_ONLY uyarı (alarm varyantı)")
    void thirdPartyOnly_alarm() {
        PageMonitor mon = monitor(base + "/page");
        mon.setAlertThirdParty(true);
        PageCheckResult r = ok(5, List.of(issue("https://cdn.example.test/lib.js", "JS", "BROKEN", false, 404, 30L)));
        Map<String, Object> data = service(fakeChecker(r, r), null).diagnose(mon, true);
        assertThat(m(data.get("verdict"))).containsEntry("status", "fail").containsEntry("code", "RESOURCES_BROKEN");
        assertThat(finding(data, "THIRD_PARTY_ONLY")).containsEntry("severity", "warn");
        assertThat(m(finding(data, "THIRD_PARTY_ONLY").get("params"))).containsEntry("reason", "alarm");
    }

    @Test
    @DisplayName("mixed content: alarm açıkken fail, kapalıyken uyarı; belirsiz + yavaş kaynak bilgi bulguları")
    void mixedContent_blocked_slow() {
        PageCheckResult r = ok(8, List.of(
                issue("http://shop.example.test/a.png", "IMG", "MIXED_CONTENT", true, null, null),
                issue("https://shop.example.test/admin.js", "JS", "BLOCKED", true, 403, 12L),
                issue("https://shop.example.test/big.jpg", "IMG", "SLOW", true, 200, 4200L)));
        Map<String, Object> on = service(fakeChecker(r, r), null).diagnose(monitor(base + "/page"), true);
        assertThat(m(on.get("verdict"))).containsEntry("status", "fail").containsEntry("code", "MIXED_CONTENT");
        assertThat(finding(on, "RESOURCES_BLOCKED")).containsEntry("severity", "info");
        assertThat(m(finding(on, "RESOURCES_SLOW").get("params"))).containsEntry("slowest_ms", 4200L).containsEntry("ms", 2000);
        // sıralama: alarma sayılan önce
        assertThat(list(m(on.get("page")).get("issues")).get(0)).containsEntry("kind", "MIXED_CONTENT").containsEntry("alarm", true);

        PageMonitor off = monitor(base + "/page");
        off.setAlertMixedContent(false);
        Map<String, Object> data = service(fakeChecker(r, r), null).diagnose(off, true);
        assertThat(m(data.get("verdict"))).containsEntry("status", "warn").containsEntry("code", "MIXED_CONTENT");
        assertThat(list(data.get("paths")).get(0)).containsEntry("outcome", "ok");
    }

    @Test
    @DisplayName("zaman aşımı alarmı kapalı: kayda OK geçer (sweep kuralı), RESOURCES_TIMEOUT uyarı")
    void timeoutToggleOff() {
        PageMonitor mon = monitor(base + "/page");
        mon.setAlertTimeout(false);
        PageCheckResult r = ok(4, List.of(issue("https://shop.example.test/slow.css", "CSS", "TIMEOUT", true, null, 3000L)));
        Map<String, Object> data = service(fakeChecker(r, r), null).diagnose(mon, true);
        assertThat(m(data.get("page"))).containsEntry("recorded_status", "OK").containsEntry("monitor_ok", true);
        assertThat(finding(data, "RESOURCES_TIMEOUT")).containsEntry("severity", "warn");
        assertThat(m(data.get("verdict"))).containsEntry("status", "warn");
    }

    @Test
    @DisplayName("ham ölçüm sayfayı aldı ama izlemenin kontrolü alamadı: PAGE_DOWN (neden kodu ile)")
    void clientDown() {
        PageCheckResult down = new PageCheckResult("DOWN", false, null, 3000L, 0, 0, 0, 0, 1, null, null,
                "request timed out", List.of(), CheckFailure.of(CheckFailureReason.READ_TIMEOUT));
        Map<String, Object> data = service(fakeChecker(down, down), null).diagnose(monitor(base + "/page"), true);
        assertThat(m(data.get("verdict"))).containsEntry("status", "fail").containsEntry("code", "PAGE_DOWN")
                .containsEntry("failed_step", "response");
        assertThat(m(finding(data, "PAGE_DOWN").get("params"))).containsEntry("failure", "READ_TIMEOUT")
                .containsEntry("route", "direct");
        assertThat(m(data.get("page"))).containsEntry("analyzed", false).containsEntry("failure_reason", "READ_TIMEOUT");
    }

    @Test
    @DisplayName("sayfa HTTP 500: PAGE_HTTP_STATUS (yanıt adımı); kontrol de düşüyor → CLIENT_MISMATCH yok")
    void httpStatus() {
        PageCheckResult down = new PageCheckResult("DOWN", false, 500, 20L, 0, 0, 0, 0, 1, null, null,
                "ana sayfa HTTP 500", List.of(), CheckFailure.of(CheckFailureReason.HTTP_STATUS));
        Map<String, Object> data = service(fakeChecker(down, down), null).diagnose(monitor(base + "/err"), true);
        assertThat(m(data.get("verdict"))).containsEntry("code", "PAGE_HTTP_STATUS").containsEntry("failed_step", "response");
        assertThat(m(finding(data, "PAGE_HTTP_STATUS").get("params"))).containsEntry("status", 500);
        assertThat(codes(data)).doesNotContain("CLIENT_MISMATCH", "PAGE_DOWN");
    }

    @Test
    @DisplayName("ham ölçüm düşüyor, kontrol sayfayı alıyor → CLIENT_MISMATCH uyarısı")
    void clientMismatch() {
        PageCheckResult r = ok(3, List.of());
        Map<String, Object> data = service(fakeChecker(r, r), null).diagnose(monitor(base + "/err"), true);
        assertThat(codes(data)).contains("PAGE_HTTP_STATUS", "CLIENT_MISMATCH");
    }

    @Test
    @DisplayName("vekil (WAF 403) ↔ doğrudan (200): PATH_DIFFERS reason=page, iki yol, karşılaştırma farklı")
    void proxyVsDirect_pathDiffers() {
        routeIs(true);
        PageCheckResult good = ok(3, List.of());
        PageCheckResult blocked = new PageCheckResult("DOWN", false, 403, 15L, 0, 0, 0, 0, 1, null, null,
                "ana sayfa HTTP 403", List.of(), CheckFailure.of(CheckFailureReason.HTTP_STATUS));
        Map<String, Object> data = service(fakeChecker(good, blocked), proxyAt(wafProxy.getLocalPort()))
                .diagnose(monitor(base + "/page"), true);
        assertThat(m(data.get("verdict"))).containsEntry("code", "PATH_DIFFERS").containsEntry("status", "fail");
        assertThat(m(finding(data, "PATH_DIFFERS").get("params"))).containsEntry("reason", "page")
                .containsEntry("failing_route", "proxy").containsEntry("working_route", "direct");
        assertThat(m(data.get("comparison"))).containsEntry("available", true).containsEntry("differs", true);
        List<Map<String, Object>> paths = list(data.get("paths"));
        assertThat(paths).hasSize(2);
        assertThat(paths.get(0)).containsEntry("route", "proxy").containsEntry("outcome", "fail").containsEntry("http_status", 403);
        assertThat(paths.get(1)).containsEntry("route", "direct").containsEntry("outcome", "ok");
        assertThat(m(paths.get(1).get("page"))).containsEntry("broken", 0).containsEntry("resources", 3);
    }

    @Test
    @DisplayName("compare=false: vekil tanımlı olsa da tek yol")
    void compareOff() {
        PageCheckResult r = ok(1, List.of());
        Map<String, Object> data = service(fakeChecker(r, r), proxyAt(wafProxy.getLocalPort())).diagnose(monitor(base + "/page"), false);
        assertThat(list(data.get("paths"))).hasSize(1);
        assertThat(m(data.get("comparison"))).containsEntry("available", false);
    }

    @Test
    @DisplayName("sınırlar: kaynak tavanı (500) → CRAWL_LIMIT uyarısı; SITE_CRAWL izleme → yalnız başlangıç sayfası bilgisi")
    void limits() {
        PageMonitor mon = monitor(base + "/page");
        mon.setMode("SITE_CRAWL");
        mon.setCrawlMaxPages(40);
        PageCheckResult r = ok(PageFetchCore.MAX_RESOURCES_PER_CHECK, List.of());
        Map<String, Object> data = service(fakeChecker(r, r), null).diagnose(mon, true);
        List<Map<String, Object>> limits = list(data.get("findings")).stream().filter(f -> "CRAWL_LIMIT".equals(f.get("code"))).toList();
        assertThat(limits).hasSize(2);
        assertThat(m(limits.get(0).get("params"))).containsEntry("reason", "resources").containsEntry("cap", 500);
        assertThat(limits.get(0)).containsEntry("severity", "warn");
        assertThat(m(limits.get(1).get("params"))).containsEntry("reason", "single_page").containsEntry("pages", 40);
        assertThat(m(data.get("page"))).containsEntry("monitor_mode", "SITE_CRAWL");
        assertThat(m(m(data.get("page")).get("limits"))).containsEntry("resource_cap_hit", true);
    }

    @Test
    @DisplayName("sorun listesi en çok 20 satır; toplam ayrıca; kaynak sayfası sayfanın kendisiyse boş")
    void issuesCapped() {
        List<ResourceIssue> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(issue("https://shop.example.test/i" + i + ".png", "IMG", "BROKEN", true, 404, 5L));
        PageCheckResult r = ok(40, many);
        PageMonitor mon = monitor("https://shop.example.test/");
        Map<String, Object> page = PageDiagnosticsService.block(evalFor(r, mon), r, mon, mon.getUrl(), "direct", 45,
                new PageDiagnosticsService.ClientRun(r, 100));
        assertThat(list(page.get("issues"))).hasSize(PageDiagnosticsService.MAX_ISSUES);
        assertThat(page).containsEntry("issues_total", 30);
        assertThat(list(page.get("issues")).get(0).get("source_page")).isNull();
    }

    private static PageDiagnosticsService.PathEval evalFor(PageCheckResult r, PageMonitor mon) {
        PageDiagnosticsService.PathEval ev = new PageDiagnosticsService.PathEval();
        ev.counts = PageDiagnosticsService.count(r, mon);
        ev.recordedStatus = r.status();
        return ev;
    }

    @Test
    @DisplayName("izlemenin kontrolü süre bütçesinde bitmedi: PAGE_DOWN reason=unfinished")
    void clientUnfinished() {
        PageDiagnosticsService svc = service(fakeChecker(ok(1, List.of()), ok(1, List.of())), null);
        // ham ölçüm başarılı, izlemenin kontrolü yok (süre bütçesinde bitmedi)
        var spec = svc.spec(new PageDiagSupport.Plan("monitor", "direct", Map.of()), base + "/page", 3000,
                com.sitemonitor.service.http.HttpRequestOptions.NONE, null, System.currentTimeMillis() + 10_000, UA);
        var probe = new com.sitemonitor.service.http.diagnose.RawHttpProbe(spec, new SsrfGuard(settings),
                new TrustEvaluator(settings), caAutoPin, new com.sitemonitor.service.http.diagnose.HttpDiagMasker(List.of(), List.of()), null).run();
        PageDiagnosticsService.PathEval ev = PageDiagnosticsService.evaluate(probe, spec, null, monitor(base + "/page"), base + "/page", 45, true);
        assertThat(ev.outcome).isEqualTo("fail");
        Map<String, Object> f = ev.findings.get(0);
        assertThat(f).containsEntry("code", "PAGE_DOWN");
        assertThat(m(f.get("params"))).containsEntry("reason", "unfinished");
        assertThat(ev.block).containsEntry("analyzed", false);
    }

    @Test
    @DisplayName("YAN ETKİSİZ YAPI: servis hiçbir depo / sweep / alarm / eskalasyon / anomali bileşeni taşımaz")
    void sideEffectFreeByConstruction() {
        for (Class<?> c = PageDiagnosticsService.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String type = f.getType().getName();
                assertThat(type).as(c.getSimpleName() + "." + f.getName())
                        .doesNotContain("Repository").doesNotContain("SchedulerService").doesNotContain("MonitoringOutageService")
                        .doesNotContain("EscalationService").doesNotContain("AnomalyGuard").doesNotContain("ActivityLog");
            }
        }
    }
}
