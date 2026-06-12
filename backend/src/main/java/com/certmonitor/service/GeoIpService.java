package com.certmonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class GeoIpService {

    @Value("${cert.monitor.geoip.api-url:http://ip-api.com/json/%s?fields=status,country,city,org}")
    private String apiUrl;

    @Value("${cert.monitor.geoip.timeout-millis:500}")
    private long timeoutMillis;

    @Value("${cert.monitor.geoip.cache-ttl-ms:3600000}")
    private long cacheTtlMs;

    /** Cache üst sınırı — TTL'e ek olarak sayı tavanı (sınırsız büyüme engeli). */
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private HttpClient httpClient;
    private final ConcurrentHashMap<String, CachedGeo> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
    }

    public record GeoInfo(String country, String city, String org) {}

    private record CachedGeo(GeoInfo info, long fetchedAt) {}

    public GeoInfo lookup(String ip) {
        if (isPrivateIp(ip)) return new GeoInfo("Private", "LAN", "Internal");

        CachedGeo cached = cache.get(ip);
        if (cached != null && (System.currentTimeMillis() - cached.fetchedAt()) < cacheTtlMs) {
            return cached.info();
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(apiUrl, ip)))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                GeoInfo info = parse(resp.body());
                // Sayı tavanına ulaşıldıysa cache'i boşalt (kaba ama bounded; nadiren tetiklenir)
                if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
                cache.put(ip, new CachedGeo(info, System.currentTimeMillis()));
                return info;
            }
        } catch (Exception e) {
            log.debug("Geo IP lookup failed for {}: {}", ip, e.getMessage());
        }
        return new GeoInfo(null, null, null);
    }

    private GeoInfo parse(String json) {
        return new GeoInfo(field(json, "country"), field(json, "city"), field(json, "org"));
    }

    private String field(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }

    public boolean isPrivateIp(String ip) {
        if (ip == null || ip.isBlank()) return true;
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) return true;
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception ignored) {}
        }
        return false;
    }
}
