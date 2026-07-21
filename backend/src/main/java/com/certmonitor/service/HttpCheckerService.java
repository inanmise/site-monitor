package com.certmonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP / Website uptime checker — bir URL'ye istek atıp yanıt durum kodunu + süresini ölçer.
 * SAĞLIKLI (ok) = durum kodu {@code expectedStatus} pattern'ine uyuyor ve hata yok.
 * Gövde okunmaz (yalnız durum kodu; {@link HttpResponse.BodyHandlers#discarding()}) → düşük maliyet.
 *
 * {@code verifySsl=false} (varsayılan) → trust-all SSL (yalnız erişilebilirlik; iç-CA/self-signed dahil);
 * {@code verifySsl=true} → JVM cacerts VEYA Genel Ayarlar kurumsal CA paketi ({@link TrustEvaluator},
 * canlı reload) ile doğrulama; TLS hatası bağlantı hatası olarak down sayılır.
 * Yönlendirme takibi client düzeyinde olduğundan (java.net.http) 4 istemci ön-kurulur:
 * {trustAll, strict} × {redirect NORMAL, NEVER}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpCheckerService {

    private final TrustEvaluator trustEvaluator;

    private HttpClient trustAllFollow;
    private HttpClient trustAllNoFollow;
    private HttpClient strictFollow;
    private HttpClient strictNoFollow;

    @PostConstruct
    public void init() {
        SSLContext trustAll = null;
        try {
            trustAll = SSLContext.getInstance("TLS");
            trustAll.init(null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
        } catch (Exception e) {
            log.warn("HTTP checker trust-all SSL kurulamadı, varsayılan kullanılacak: {}", e.getMessage());
        }
        // Strict: cacerts VEYA kurumsal CA paketi; kompozit TM ayarı her handshake'te canlı okur,
        // client'ın bir kez kurulması reload'u engellemez. null dönerse varsayılan güvene düşülür.
        SSLContext strict = trustEvaluator.outboundSslContext();
        trustAllFollow   = build(trustAll, HttpClient.Redirect.NORMAL);
        trustAllNoFollow = build(trustAll, HttpClient.Redirect.NEVER);
        strictFollow     = build(strict,   HttpClient.Redirect.NORMAL);
        strictNoFollow   = build(strict,   HttpClient.Redirect.NEVER);
    }

    private HttpClient build(SSLContext ssl, HttpClient.Redirect redirect) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(redirect);
        if (ssl != null) b.sslContext(ssl);
        return b.build();
    }

    private HttpClient client(boolean verifySsl, boolean followRedirects) {
        if (verifySsl) return followRedirects ? strictFollow : strictNoFollow;
        return followRedirects ? trustAllFollow : trustAllNoFollow;
    }

    /** {"http_status", "response_ms", "ok", "error"?} döner. */
    public Map<String, Object> check(String url, String method, String expectedStatus,
                                     int timeoutMs, boolean verifySsl, boolean followRedirects) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String m = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url.trim()))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .header("User-Agent", "CertMonitor-HttpMonitor/1.0");
            switch (m) {
                case "HEAD" -> rb.method("HEAD", HttpRequest.BodyPublishers.noBody());
                case "POST" -> rb.POST(HttpRequest.BodyPublishers.noBody());
                default     -> rb.GET();
            }
            HttpResponse<Void> resp = client(verifySsl, followRedirects)
                    .send(rb.build(), HttpResponse.BodyHandlers.discarding());
            long ms = System.currentTimeMillis() - start;
            int status = resp.statusCode();
            result.put("http_status", status);
            result.put("response_ms", ms);
            result.put("ok", matchesStatus(status, expectedStatus));
        } catch (Exception e) {
            result.put("http_status", null);
            result.put("response_ms", System.currentTimeMillis() - start);
            result.put("ok", false);
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.debug("HTTP check failed for {}: {}", url, e.getMessage());
        }
        return result;
    }

    /**
     * Durum kodu, pattern'e uyuyor mu. Pattern virgülle ayrılmış token listesi; her token:
     *  - kesin kod: "200"
     *  - onlar-jokerı: "2xx" → 200-299
     *  - aralık: "200-399"
     * Boş/geçersiz pattern → varsayılan 200-399 (2xx/3xx) sağlıklı sayılır.
     */
    public static boolean matchesStatus(int status, String pattern) {
        if (pattern == null || pattern.isBlank()) return status >= 200 && status <= 399;
        for (String tokRaw : pattern.split(",")) {
            String tok = tokRaw.trim().toLowerCase(Locale.ROOT);
            if (tok.isEmpty()) continue;
            try {
                if (tok.length() == 3 && Character.isDigit(tok.charAt(0)) && tok.charAt(1) == 'x' && tok.charAt(2) == 'x') {
                    int base = (tok.charAt(0) - '0') * 100;
                    if (status >= base && status <= base + 99) return true;
                } else if (tok.contains("-")) {
                    String[] p = tok.split("-", 2);
                    int lo = Integer.parseInt(p[0].trim());
                    int hi = Integer.parseInt(p[1].trim());
                    if (status >= Math.min(lo, hi) && status <= Math.max(lo, hi)) return true;
                } else {
                    if (status == Integer.parseInt(tok)) return true;
                }
            } catch (NumberFormatException ignore) { /* geçersiz token — atla */ }
        }
        return false;
    }
}
