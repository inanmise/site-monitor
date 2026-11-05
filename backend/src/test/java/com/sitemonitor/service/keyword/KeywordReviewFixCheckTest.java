package com.sitemonitor.service.keyword;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.KeywordCheckerService;
import com.sitemonitor.service.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * kw son inceleme (2026-11-05) — kontrol yolu düzeltmeleri, gerçek HTTP (süreç içi sunucu):
 * <ul>
 *   <li>O-3: değişim yalnız istek tamamlandı ∧ HTTP &lt; 400 ∧ gövde dolu ∧ durum / son URL kuralı reddetmedi iken ölçülür; aksi
 *       {@code change_skipped} = neden, anlık görüntü (otomatik temel) YOK.</li>
 *   <li>D-7: değişim seçicisi metinli öğe seçmezse {@code SELECTOR_NOT_FOUND} (temel alınmaz, karşılaştırılmaz).</li>
 *   <li>D-1: alıntı ({@code snippet}) sır değerleri, base64 kimlik ve hassas alan adlarıyla maskelenir; pencere kenarındaki sır
 *       yarım kalmaz.</li>
 *   <li>D-8: kimlik bilgisi / özel başlık yönlendirmede yalnız AYNI KAYNAĞA (şema + host + port) gider.</li>
 * </ul>
 */
class KeywordReviewFixCheckTest {

    private final List<HttpServer> servers = new ArrayList<>();
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);

    @AfterEach
    void stop() {
        for (HttpServer s : servers) s.stop(0);
    }

    private String serve(String html) throws IOException {
        body.set(html);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(status.get(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) try (var os = ex.getResponseBody()) { os.write(b); }
            else ex.close();
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static KeywordCheckerService checker() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        KeywordCheckerService svc = new KeywordCheckerService(new SsrfGuard(s));
        svc.init();
        return svc;
    }

    private static KeywordCheckSpec.Builder spec(String url, String keyword) {
        return KeywordCheckSpec.builder(url, keyword).timeoutMs(5000).diagnose(true).monitorLevel("HIGH");
    }

    // ── O-3 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O-3: 5xx sayfası (durum kuralı yok) değişim ölçmez — HTTP_ERROR, parmak izi / temel yok / değişim anahtarı yok")
    void serverErrorPageIsNotMeasured() throws IOException {
        status.set(502);
        String url = serve("<p>Bad Gateway — bakımdayız</p>");
        var capture = new KeywordCheckSpec.ChangeCapture();
        Map<String, Object> r = checker().check(spec(url, "bakım").changeDetect(true).changeCapture(capture).build());
        assertThat(r.get("change_skipped")).isEqualTo(KeywordCheckerService.CHANGE_SKIP_HTTP_ERROR);
        assertThat(r).doesNotContainKeys("content_fingerprint", "baseline_missing", "content_changed");
        assertThat(capture.snapshot()).as("hata sayfası temel olamaz").isNull();
        // 4xx de
        status.set(404);
        assertThat(checker().check(spec(url, "bakım").changeDetect(true).build()).get("change_skipped"))
                .isEqualTo(KeywordCheckerService.CHANGE_SKIP_HTTP_ERROR);
    }

    @Test
    @DisplayName("O-3: boş 200 gövde EMPTY_BODY; durum kuralı reddi RULE_REJECTED; tamamlanmayan istek INCOMPLETE")
    void otherSkipReasons() throws IOException {
        String url = serve("");
        var capture = new KeywordCheckSpec.ChangeCapture();
        Map<String, Object> empty = checker().check(spec(url, "x").changeDetect(true).changeCapture(capture).build());
        assertThat(empty.get("change_skipped")).isEqualTo(KeywordCheckerService.CHANGE_SKIP_EMPTY_BODY);
        assertThat(capture.snapshot()).isNull();

        body.set("<p>sayfa</p>");
        Map<String, Object> rejected = checker().check(spec(url, "sayfa").changeDetect(true).expectedStatus("201").build());
        assertThat(rejected.get("change_skipped")).isEqualTo(KeywordCheckerService.CHANGE_SKIP_RULE_REJECTED);

        Map<String, Object> refused = checker().check(spec("http://127.0.0.1:1/", "x").changeDetect(true).build());
        assertThat(refused.get("ok")).isEqualTo(false);
        assertThat(refused.get("change_skipped")).isEqualTo(KeywordCheckerService.CHANGE_SKIP_INCOMPLETE);
        assertThat(refused).doesNotContainKey("content_changed");

        // değişim kapalı → not yok
        Map<String, Object> off = checker().check(spec("http://127.0.0.1:1/", "x").build());
        assertThat(off).doesNotContainKey("change_skipped");
        // sağlıklı 200 → ölçülür, not yok
        Map<String, Object> fine = checker().check(spec(url, "sayfa").changeDetect(true).build());
        assertThat(fine).doesNotContainKey("change_skipped").containsEntry("baseline_missing", true);
    }

    // ── D-7 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-7: seçici öğe seçmezse SELECTOR_NOT_FOUND — temel alınmaz; öğe varsa ölçülür")
    void selectorNotFound() throws IOException {
        String url = serve("<div id=\"a\"><p>Koşullar metni</p></div><div id=\"bos\"></div>");
        var capture = new KeywordCheckSpec.ChangeCapture();
        Map<String, Object> missing = checker().check(spec(url, "Koşullar").changeDetect(true).changeSelector("#yok")
                .changeCapture(capture).build());
        assertThat(missing.get("change_skipped")).isEqualTo(KeywordCheckerService.CHANGE_SKIP_SELECTOR_NOT_FOUND);
        assertThat(missing).doesNotContainKeys("baseline_missing", "content_changed", "content_fingerprint");
        assertThat(capture.snapshot()).isNull();
        // metinsiz öğe de "bulunamadı" (boş temel hiç tetiklenmez)
        assertThat(checker().check(spec(url, "Koşullar").changeDetect(true).changeSelector("#bos").build())
                .get("change_skipped")).isEqualTo(KeywordCheckerService.CHANGE_SKIP_SELECTOR_NOT_FOUND);
        var cap2 = new KeywordCheckSpec.ChangeCapture();
        Map<String, Object> found = checker().check(spec(url, "Koşullar").changeDetect(true).changeSelector("#a")
                .changeCapture(cap2).build());
        assertThat(found).doesNotContainKey("change_skipped").containsEntry("baseline_missing", true);
        assertThat(cap2.snapshot().text()).isEqualTo("Koşullar metni");
    }

    // ── D-1 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-1: alıntı başlık sırrını, Basic auth parolasını / base64 kimliğini ve \"password\" alanını maskeler")
    void snippetIsMasked() throws IOException {
        String secret = "sk9f8d7s6a5x4c3v2b1";
        String b64 = Base64.getEncoder().encodeToString("ali:Parola2026x".getBytes(StandardCharsets.UTF_8));
        // parola alanı eşleşmeden ÖNCE, sır ve base64 kimlik SONRA — hepsi ±50 karakterlik pencerede (base64 kenarı aşar)
        String url = serve("<p>{\"password\":\"hunter2pw\"} Durum: Fiyat listesi " + secret + " ve " + b64 + " son</p>");
        Map<String, Object> r = checker().check(spec(url, "Fiyat")
                .customHeaders("X-Api-Key: " + secret).basicAuthUser("ali").basicAuthPass("Parola2026x").build());
        String sn = (String) r.get("snippet");
        assertThat(sn).contains("Fiyat");
        assertThat(sn).doesNotContain(secret).doesNotContain(b64).doesNotContain("hunter2pw");
    }

    @Test
    @DisplayName("D-1: pencere kenarına denk gelen sır yarım kalmaz (pencere sırrı tam kapsayacak kadar genişler)")
    void secretAtWindowEdgeIsMaskedWhole() throws IOException {
        String secret = "ZQ7x9TokenValue000111";
        // eşleşmeden sonra 46 karakter dolgu → sır ±50 penceresinin kenarında başlar (ilk 4 karakteri pencerede)
        String filler = "a".repeat(46);
        String url = serve("<p>Fiyat" + filler + secret + " son</p>");
        Map<String, Object> r = checker().check(spec(url, "Fiyat").customHeaders("Authorization: Bearer " + secret).build());
        String sn = (String) r.get("snippet");
        assertThat(sn).contains("Fiyat").doesNotContain("ZQ7x").doesNotContain(secret);
    }

    // ── D-8 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-8: aynı host'un BAŞKA portuna yönlendirmede Authorization / özel başlık gitmez; aynı kaynağa gider")
    void credentialsOnlyToSameOrigin() throws IOException {
        List<String> seenAtB = new CopyOnWriteArrayList<>();
        HttpServer b = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        b.createContext("/", ex -> {
            seenAtB.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")) + "|"
                    + ex.getRequestHeaders().getFirst("X-Api-Key"));
            byte[] out = "<p>hedef sayfa</p>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) { os.write(out); }
        });
        b.start();
        servers.add(b);
        int portB = b.getAddress().getPort();

        List<String> seenAtA = new CopyOnWriteArrayList<>();
        HttpServer a = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        a.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            seenAtA.add(path + "|" + ex.getRequestHeaders().getFirst("Authorization"));
            if (path.equals("/other-port")) {
                ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/x");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } else if (path.equals("/same")) {
                ex.getResponseHeaders().add("Location", "/final");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } else {
                byte[] out = "<p>hedef sayfa</p>".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                try (var os = ex.getResponseBody()) { os.write(out); }
            }
        });
        a.start();
        servers.add(a);
        String base = "http://127.0.0.1:" + a.getAddress().getPort();

        Map<String, Object> r = checker().check(spec(base + "/other-port", "hedef").basicAuthUser("ali").basicAuthPass("Parola2026x")
                .customHeaders("X-Api-Key: k3y9v8u7t6").build());
        assertThat(r.get("ok")).as(String.valueOf(r)).isEqualTo(true);
        assertThat(seenAtB).hasSize(1);
        assertThat(seenAtB.get(0)).as("başka port = başka kaynak → kimlik ve özel başlık GİTMEZ").isEqualTo("null|null");

        Map<String, Object> same = checker().check(spec(base + "/same", "hedef").basicAuthUser("ali").basicAuthPass("Parola2026x").build());
        assertThat(same.get("ok")).isEqualTo(true);
        assertThat(seenAtA).anySatisfy(s -> assertThat(s).startsWith("/final|Basic "));
    }

    @Test
    @DisplayName("O-3 / D-7: kontrol satırı change_note'u taşır (yalnız bilinen kod); ölçülen değişimde not yok")
    void mapperWritesChangeNote() {
        com.sitemonitor.model.KeywordMonitor m = new com.sitemonitor.model.KeywordMonitor();
        m.setId(1L);
        m.setKeyword("x");
        java.util.Map<String, Object> r = new java.util.HashMap<>(Map.of("ok", true, "count", 1,
                "change_skipped", KeywordCheckerService.CHANGE_SKIP_HTTP_ERROR));
        assertThat(KeywordResultMapper.build(m, r, true, "2026-11-05T10:00:00").getChangeNote()).isEqualTo("HTTP_ERROR");
        r.put("change_skipped", "BILINMEYEN");
        assertThat(KeywordResultMapper.build(m, r, true, "2026-11-05T10:00:00").getChangeNote()).isNull();
        r.remove("change_skipped");
        r.put("content_changed", false);
        assertThat(KeywordResultMapper.build(m, r, true, "2026-11-05T10:00:00").getChangeNote()).isNull();
        assertThat(KeywordCheckerService.CHANGE_SKIP_CODES).containsExactly("HTTP_ERROR", "EMPTY_BODY", "INCOMPLETE",
                "RULE_REJECTED", "SELECTOR_NOT_FOUND");
    }
}
