package com.certmonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String url, String keyword, int timeoutMs) {
        return CompletableFuture.completedFuture(check(url, keyword, timeoutMs));
    }

    /** {"found", "http_status", "response_ms", "snippet"?, "error"?} döner. */
    public Map<String, Object> check(String url, String keyword, int timeoutMs) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .header("User-Agent", "CertMonitor-KeywordMonitor/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - start;
            String body = resp.body() != null ? resp.body() : "";
            String hay = body.toLowerCase(Locale.ROOT);
            String needle = keyword != null ? keyword.toLowerCase(Locale.ROOT) : "";
            boolean found = !needle.isEmpty() && hay.contains(needle);

            result.put("found", found);
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
            result.put("response_ms", System.currentTimeMillis() - start);
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.debug("Keyword check failed for {}: {}", url, e.getMessage());
        }
        return result;
    }
}
