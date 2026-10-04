package com.sitemonitor.service.keyword;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.KeywordCheckerService;
import com.sitemonitor.service.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Keyword hata teşhisi (2026-10-04) — GERÇEK istemci + yerel sunucu: beklentili kontrol her başarısızlık türünde nedeni,
 * ayrıntıyı, yanıt meta verisini, ipuçlarını ve (yalnız başarısızlıkta) maskeli alıntıyı üretir; {@code found} /
 * {@code count} / {@code error} anlamı DEĞİŞMEDİ, beklentisiz çağrı teşhis üretmez. Dış ağa çıkılmaz.
 */
class KeywordCheckerDiagnosisTest {

    private static HttpServer http;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "kw-diag-test"); t.setDaemon(true); return t; }));
        http.createContext("/page", ex -> send(ex, 200, "text/html; charset=utf-8",
                "<html><head><title>Mağaza</title></head><body><h1>Hoş geldiniz</h1><p>Ürünler listesi</p></body></html>"));
        http.createContext("/down", ex -> send(ex, 503, "text/html", "<html><title>Service Unavailable</title><body>bakımdayız</body></html>"));
        http.createContext("/empty", ex -> { ex.sendResponseHeaders(204, -1); ex.close(); });
        http.createContext("/echo", ex -> send(ex, 200, "application/json",
                "{\"password\":\"hunter2\",\"seen\":\"" + ex.getRequestHeaders().getFirst("Authorization") + "\"}"));
        http.createContext("/slow", ex -> {
            try { Thread.sleep(2500); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
            try { send(ex, 200, "text/plain", "geç"); } catch (IOException ignore) { /* istemci gitti */ }
        });
        http.createContext("/hop", ex -> { ex.getResponseHeaders().add("Location", "/page?token=s3cr3tvalue"); ex.sendResponseHeaders(302, -1); ex.close(); });
        http.createContext("/big", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, 0);
            byte[] chunk = "a".repeat(64 * 1024).getBytes(StandardCharsets.US_ASCII);
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 40; i++) os.write(chunk);   // ≈ 2.6 MB > 2 MB tavan
            } catch (IOException ignore) { /* istemci tavanda kesti */ }
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterAll
    static void stop() { if (http != null) http.stop(0); }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, String ct, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", ct);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static KeywordCheckerService checker() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        KeywordCheckerService svc = new KeywordCheckerService(new SsrfGuard(s));
        svc.init();
        return svc;
    }

    private static final KeywordCheckerService.Expectation PRESENT = new KeywordCheckerService.Expectation("GTE", 1);

    @Test
    @DisplayName("bulunamadı: KEYWORD_NOT_FOUND + TR ayrıntı + meta + alıntı; found/count/error anlamı aynı")
    void notFound() {
        Map<String, Object> r = checker().check(base + "/page", "Kampanya", 3000, null, false, false, PRESENT);
        assertThat(r).containsEntry("found", false).containsEntry("count", 0).containsEntry("http_status", 200)
                .doesNotContainKey("error");
        assertThat(r).containsEntry("failure_reason", "KEYWORD_NOT_FOUND")
                .containsEntry("redirect_count", 0).containsEntry("body_truncated", false).containsEntry("charset", "UTF-8");
        assertThat((String) r.get("failure_detail")).contains("« Kampanya »").contains("HTTP 200");
        assertThat((String) r.get("final_url")).isEqualTo(base + "/page");
        assertThat((String) r.get("content_type")).startsWith("text/html");
        assertThat((Long) r.get("body_bytes")).isPositive();
        assertThat((String) r.get("excerpt")).contains("Hoş geldiniz").doesNotContain("<h1>");
    }

    @Test
    @DisplayName("bulundu: teşhis alanı YOK (yalnız meta), snippet aynı")
    void foundHasNoDiagnosis() {
        Map<String, Object> r = checker().check(base + "/page", "ürünler", 3000, null, false, false, PRESENT);
        assertThat(r).containsEntry("found", true).containsEntry("count", 1);
        assertThat(r).doesNotContainKeys("failure_reason", "failure_detail", "hints", "excerpt");
        assertThat(r).containsKeys("final_url", "content_type", "body_bytes", "body_truncated");
        assertThat((String) r.get("snippet")).contains("Ürünler");
    }

    @Test
    @DisplayName("beklentisiz (eski 6 argümanlı) çağrı teşhis üretmez — eski çağıranlar birebir")
    void legacyCallHasNoDiagnosis() {
        Map<String, Object> r = checker().check(base + "/page", "Kampanya", 3000, null, false, false);
        assertThat(r).containsEntry("found", false).doesNotContainKeys("failure_reason", "hints", "excerpt");
    }

    @Test
    @DisplayName("HTTP 503: HTTP_STATUS + bakım ipucu")
    void httpStatus() {
        Map<String, Object> r = checker().check(base + "/down", "Ürünler", 3000, null, false, false, PRESENT);
        assertThat(r).containsEntry("http_status", 503).containsEntry("failure_reason", "HTTP_STATUS");
        assertThat(r.get("error")).isNull();
        @SuppressWarnings("unchecked") List<String> hints = (List<String>) r.get("hints");
        assertThat(hints).contains("MAINTENANCE_PAGE");
    }

    @Test
    @DisplayName("boş gövde (204): EMPTY_BODY")
    void emptyBody() {
        Map<String, Object> r = checker().check(base + "/empty", "x", 3000, null, false, false, PRESENT);
        assertThat(r).containsEntry("failure_reason", "EMPTY_BODY").containsEntry("body_bytes", 0L);
    }

    @Test
    @DisplayName("'olmamalı' kuralı: yasak kelime bulundu → KEYWORD_FOUND_FORBIDDEN, alıntı eşleşmenin çevresi")
    void forbidden() {
        Map<String, Object> r = checker().check(base + "/page", "Hoş geldiniz", 3000, null, false, false,
                new KeywordCheckerService.Expectation("LTE", 0));
        assertThat(r).containsEntry("found", true).containsEntry("failure_reason", "KEYWORD_FOUND_FORBIDDEN");
        assertThat((String) r.get("excerpt")).contains("Hoş geldiniz");
    }

    @Test
    @DisplayName("yönlendirme: son URL (hassas sorgu maskeli) + yönlendirme sayısı")
    void redirectMeta() {
        Map<String, Object> r = checker().check(base + "/hop", "Kampanya", 3000, null, false, false, PRESENT);
        assertThat(r).containsEntry("redirect_count", 1).containsEntry("failure_reason", "KEYWORD_NOT_FOUND");
        assertThat((String) r.get("final_url")).isEqualTo(base + "/page?token=*****").doesNotContain("s3cr3tvalue");
    }

    @Test
    @DisplayName("2 MB tavan: kelime ilk 2 MB'ta yok → BODY_TRUNCATED, body_truncated=true")
    void truncated() {
        Map<String, Object> r = checker().check(base + "/big", "sonda", 10_000, null, false, false, PRESENT);
        assertThat(r).containsEntry("body_truncated", true).containsEntry("failure_reason", "BODY_TRUNCATED")
                .containsEntry("body_bytes", 2_000_000L);
    }

    @Test
    @DisplayName("zaman aşımı: TIMEOUT_READ + error metni korunur")
    void timeout() {
        Map<String, Object> r = checker().check(base + "/slow", "geç", 1000, null, false, false, PRESENT);
        assertThat(r).containsEntry("found", false).containsEntry("failure_reason", "TIMEOUT_READ");
        assertThat(r.get("error")).isNotNull();
        assertThat((String) r.get("final_url")).isEqualTo(base + "/slow");
    }

    @Test
    @DisplayName("bağlantı reddi: kapalı porta CONNECTION_REFUSED")
    void refused() throws IOException {
        int port;
        try (ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { port = ss.getLocalPort(); }
        Map<String, Object> r = checker().check("http://127.0.0.1:" + port + "/", "x", 2000, null, false, false, PRESENT);
        assertThat(r).containsEntry("failure_reason", "CONNECTION_REFUSED");
        assertThat(r.get("error")).isNotNull();
    }

    @Test
    @DisplayName("TLS: TLS yerine düz metin konuşan uca https → TLS el sıkışma ailesi (java.net.http takılan el sıkışmayı "
            + "'connect timed out' diye bildirebilir — sınıf eşlemesi KeywordFailureClassifierTest'te deterministik)")
    void tls() throws Exception {
        try (ServerSocket plain = new ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread t = new Thread(() -> {
                try (java.net.Socket s = plain.accept()) {
                    s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    s.getOutputStream().flush();
                } catch (IOException ignore) { /* test bitti */ }
            });
            t.setDaemon(true);
            t.start();
            Map<String, Object> r = checker().check("https://127.0.0.1:" + plain.getLocalPort() + "/page", "x", 3000,
                    null, false, false, PRESENT);
            assertThat(r.get("failure_reason")).as(String.valueOf(r.get("error")))
                    .isIn("TLS_HANDSHAKE", "CONNECTION_RESET", "TIMEOUT_CONNECT");
            assertThat(r).containsEntry("found", false).doesNotContainKey("excerpt");
            assertThat(r.get("error")).isNotNull();
        }
    }

    @Test
    @DisplayName("SSRF: metadata adresi engellenir → SSRF_BLOCKED, istek atılmaz (response_ms yok)")
    void ssrf() {
        Map<String, Object> r = checker().check("http://169.254.169.254/latest/meta-data", "x", 2000, null, false, false, PRESENT);
        assertThat(r).containsEntry("failure_reason", "SSRF_BLOCKED").doesNotContainKey("response_ms");
    }

    @Test
    @DisplayName("yapılandırma hatası: şemasız URL → CONFIG_ERROR (config_error bayrağı aynı)")
    void configError() {
        Map<String, Object> r = checker().check("site-without-scheme.example", "x", 2000, null, false, false, PRESENT);
        assertThat(r).containsEntry("config_error", true).containsEntry("failure_reason", "CONFIG_ERROR");
    }

    @Test
    @DisplayName("alıntı sırları maskeler: yansıtılan Authorization jetonu ve JSON parola alanı")
    void excerptMasksSecrets() {
        Map<String, Object> r = checker().check(base + "/echo", "welcome", 3000,
                "Authorization: Bearer tok-ABCDEF123456\nAccept: application/json", false, false, PRESENT);
        String ex = (String) r.get("excerpt");
        assertThat(ex).isNotNull().doesNotContain("tok-ABCDEF123456").doesNotContain("hunter2");
    }
}
