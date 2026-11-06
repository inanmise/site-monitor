package com.sitemonitor.service.http.diagnose;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.http.HttpRequestOptions;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Uçtan uca tanının ({@link RawHttpProbe}) yönlendirme kuralları — hata avı 2026-11-06 (H4#1 / H4#5): tanı izlemenin GERÇEK
 * istemcisinin kuralını taşır. Anahtar kelime tanısı ({@code originExtras}) kimlik bilgisini aynı host'un BAŞKA portuna göndermez
 * ({@code extras_dropped} true) — kontrolle aynı tek yardımcı ({@code SafeRedirect.extrasMayFollow}); HTTP tanısı bugünkü host
 * kuralında kalır. POST + 302 sonrası GET (gövdesiz).
 */
class RawHttpProbeExtrasRuleTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        for (HttpServer s : servers) s.stop(0);
    }

    private static SsrfGuard permissiveGuard() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        return new SsrfGuard(s);
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler h) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", h);
        s.start();
        servers.add(s);
        return s;
    }

    private static RawHttpProbe run(RawHttpProbe.Spec spec) {
        HttpDiagMasker masker = new HttpDiagMasker(List.of("X-Api-Key"), List.of("Gizli-2026", "k3y-abc"));
        return new RawHttpProbe(spec, permissiveGuard(), null, null, masker, null).run();
    }

    private static HttpRequestOptions opts(String body) {
        return new HttpRequestOptions("X-Api-Key: k3y-abc", "izleme", "Gizli-2026", body, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redirectOf(RawHttpProbe p, int hop) {
        return (Map<String, Object>) p.hops().get(hop).get("redirect");
    }

    @Test
    @DisplayName("H4#5: anahtar kelime tanısı aynı host'un BAŞKA portuna yönlendirmede Authorization / özel başlık GÖNDERMEZ "
            + "(extras_dropped true); HTTP tanısı bugünkü host kuralında (gönderir, extras_dropped false)")
    void keywordUsesOriginRuleHttpKeepsHostRule() throws Exception {
        List<String> seenAtB = new CopyOnWriteArrayList<>();
        HttpServer b = server(ex -> {
            seenAtB.add(ex.getRequestHeaders().getFirst("Authorization") + "|" + ex.getRequestHeaders().getFirst("X-Api-Key"));
            byte[] out = "<p>Hesabım</p>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) { os.write(out); }
        });
        int portB = b.getAddress().getPort();
        HttpServer a = server(ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/hesap");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        String url = "http://127.0.0.1:" + a.getAddress().getPort() + "/";
        long deadline = System.currentTimeMillis() + 20_000;

        RawHttpProbe.Spec kw = new RawHttpProbe.Spec("monitor", "direct", url, "GET", null, 3000, false, true, opts(null),
                null, 0, null, deadline, "UA", true);
        assertThat(kw.originExtras()).isTrue();
        RawHttpProbe pk = run(kw);
        assertThat(pk.finalStatus()).isEqualTo(200);
        assertThat(seenAtB).containsExactly("null|null");
        assertThat(redirectOf(pk, 0)).containsEntry("extras_dropped", true).containsEntry("cross_host", false);

        seenAtB.clear();
        RawHttpProbe.Spec http = new RawHttpProbe.Spec("monitor", "direct", url, "GET", "200-399", 3000, false, true, opts(null),
                null, 0, null, deadline);
        assertThat(http.originExtras()).isFalse();
        RawHttpProbe ph = run(http);
        assertThat(ph.finalStatus()).isEqualTo(200);
        assertThat(seenAtB).hasSize(1);
        assertThat(seenAtB.get(0)).startsWith("Basic ").endsWith("|k3y-abc");
        assertThat(redirectOf(ph, 0)).containsEntry("extras_dropped", false);
    }

    @Test
    @DisplayName("H4#5: Spec.extrasFollow — keyword aynı host'ta http:80 → https:443 yükseltmesine izin verir, başka port / host'a "
            + "vermez; HTTP host kuralı aynı host'un her portuna")
    void extrasFollowRule() {
        RawHttpProbe.Spec kw = new RawHttpProbe.Spec("monitor", "direct", "http://a.example.test/", "GET", null, 3000, false, true,
                HttpRequestOptions.NONE, null, 0, null, 0L, "UA", true);
        RawHttpProbe.Spec http = new RawHttpProbe.Spec("monitor", "direct", "http://a.example.test/", "GET", null, 3000, false, true,
                HttpRequestOptions.NONE, null, 0, null, 0L);
        URI first = URI.create("http://a.example.test/");
        assertThat(kw.extrasFollow(first, URI.create("https://a.example.test/x"))).isTrue();
        assertThat(kw.extrasFollow(first, URI.create("https://a.example.test:8443/x"))).isFalse();
        assertThat(kw.extrasFollow(first, URI.create("http://a.example.test:8080/x"))).isFalse();
        assertThat(kw.extrasFollow(first, URI.create("https://b.example.test/"))).isFalse();
        assertThat(http.extrasFollow(first, URI.create("http://a.example.test:8080/x"))).isTrue();
        assertThat(http.extrasFollow(first, URI.create("https://b.example.test/"))).isFalse();
    }

    @Test
    @DisplayName("H4#1: tanı da POST + 302 sonrası GET gönderir (gövdesiz, Content-Length yok) — izlemeyle aynı istek")
    void post302BecomesGetInProbe() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer s = server(ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestURI().getPath() + "|" + ex.getRequestMethod() + "|" + body);
            if (ex.getRequestURI().getPath().equals("/login")) {
                ex.getResponseHeaders().add("Location", "/home");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] out = "<p>Hesabım</p>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) { os.write(out); }
        });
        String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/login";
        RawHttpProbe p = run(new RawHttpProbe.Spec("monitor", "direct", url, "POST", null, 3000, false, true, opts("u=ali"),
                null, 0, null, System.currentTimeMillis() + 20_000, "UA", true));
        assertThat(p.finalStatus()).isEqualTo(200);
        assertThat(seen).containsExactly("/login|POST|u=ali", "/home|GET|");
    }
}
