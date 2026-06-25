package com.certmonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Keyword monitor checker — bir URL'nin HTTP yanıt gövdesini çekip içinde
 * anahtar kelimeyi (case-insensitive) arar. Koşul (içerir/içermez) uygulanmaz;
 * yalnız ham {@code found} gözlemi + meta döndürülür (koşul scheduler'da uygulanır).
 * HttpClient deseni GeoIpService ile aynıdır.
 */
@Slf4j
@Service
public class KeywordCheckerService {

    /** Yanıt gövdesi okuma tavanı (OOM koruması) — keyword aramaya fazlasıyla yeter. */
    private static final int MAX_BODY_BYTES = 2_000_000;

    private HttpClient httpClient;

    /** İç-CA / self-signed HTTPS sitelerini de izleyebilmek için trust-all
     *  (içerik kontrolü; sertifika geçerliliği ayrı cert checker'da izlenir). */
    @PostConstruct
    public void init() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            b.sslContext(ssl);
        } catch (Exception e) {
            log.warn("Keyword checker trust-all SSL kurulamadı, varsayılan kullanılacak: {}", e.getMessage());
        }
        httpClient = b.build();
    }

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String url, String keyword, int timeoutMs) {
        return CompletableFuture.completedFuture(check(url, keyword, timeoutMs));
    }

    /** Geriye uyum: özel header'sız. */
    public Map<String, Object> check(String url, String keyword, int timeoutMs) {
        return check(url, keyword, timeoutMs, null);
    }

    /** {"found", "http_status", "response_ms", "snippet"?, "error"?} döner. Cache busting: URL'deki
     *  {timestamp} → güncel Unix saniye; customHeaders ("Name: Value" satırları, ör. Cache-Control: no-cache). */
    public Map<String, Object> check(String url, String keyword, int timeoutMs, String customHeaders) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(applyTimestamp(url)))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .header("User-Agent", "CertMonitor-KeywordMonitor/1.0");
            applyCustomHeaders(rb, customHeaders);
            HttpRequest req = rb.GET().build();
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream is = resp.body()) {
                bytes = is.readNBytes(MAX_BODY_BYTES);   // bellek koruması: gövde tavanı
            }
            long ms = System.currentTimeMillis() - start;
            String body = new String(bytes, StandardCharsets.UTF_8);
            String hay = body.toLowerCase(Locale.ROOT);
            String needle = keyword != null ? keyword.toLowerCase(Locale.ROOT) : "";
            int count = 0;
            if (!needle.isEmpty()) {
                int from = 0, idx;
                while ((idx = hay.indexOf(needle, from)) >= 0) { count++; from = idx + needle.length(); }
            }
            boolean found = count > 0;

            result.put("found", found);
            result.put("count", count);
            result.put("http_status", resp.statusCode());
            result.put("response_ms", ms);
            if (found) {
                int idx = hay.indexOf(needle);
                int s = Math.max(0, idx - 50);
                int e = Math.min(body.length(), idx + keyword.length() + 50);
                String snip = body.substring(s, e).replaceAll("\\s+", " ").trim();
                if (snip.length() > 200) snip = snip.substring(0, 200);
                result.put("snippet", snip);
            }
        } catch (Exception e) {
            result.put("found", false);
            result.put("count", 0);
            result.put("response_ms", System.currentTimeMillis() - start);
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.debug("Keyword check failed for {}: {}", url, e.getMessage());
        }
        return result;
    }

    /** {timestamp} → güncel Unix saniye (her kontrolde benzersiz URL → ara cache bypass). */
    private static String applyTimestamp(String url) {
        if (url == null || !url.contains("{timestamp}")) return url;
        return url.replace("{timestamp}", String.valueOf(java.time.Instant.now().getEpochSecond()));
    }

    /** Satır başına "Name: Value" özel HTTP header'larını isteğe ekler (ör. Cache-Control: no-cache).
     *  HttpClient kısıtlı header'ları (Host/Connection vb.) reddederse o satır sessizce atlanır. */
    private static void applyCustomHeaders(HttpRequest.Builder rb, String customHeaders) {
        if (customHeaders == null || customHeaders.isBlank()) return;
        for (String line : customHeaders.split("\\r?\\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String name = line.substring(0, c).trim();
            String value = line.substring(c + 1).trim();
            if (name.isEmpty()) continue;
            try { rb.header(name, value); }
            catch (IllegalArgumentException ignore) { /* kısıtlı/geçersiz header — atla */ }
        }
    }

    /** Adet koşulu değerlendirmesi: SAĞLIKLI = (geçiş adedi) [operatör] (eşik). */
    public static boolean evaluate(int count, String op, int threshold) {
        return switch (op == null ? "GTE" : op) {
            case "LTE" -> count <= threshold;
            case "EQ"  -> count == threshold;
            case "GT"  -> count >  threshold;
            case "LT"  -> count <  threshold;
            default    -> count >= threshold;   // GTE
        };
    }

    /** Operatör + eşik → Türkçe ifade ("en az 3 kez" vb.) — mesaj/şablon/UI için. */
    public static String opPhrase(String op, int n) {
        return switch (op == null ? "GTE" : op) {
            case "LTE" -> "en fazla " + n + " kez";
            case "EQ"  -> "tam olarak " + n + " kez";
            case "GT"  -> n + " kezden fazla";
            case "LT"  -> n + " kezden az";
            default    -> "en az " + n + " kez";   // GTE
        };
    }
}
