package com.certmonitor.service;

import lombok.extern.slf4j.Slf4j;
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

    private static final String API_URL = "http://ip-api.com/json/%s?fields=status,country,city,org";
    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private final ConcurrentHashMap<String, CachedGeo> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3_600_000L;

    public record GeoInfo(String country, String city, String org) {}

    private record CachedGeo(GeoInfo info, long fetchedAt) {}

    public GeoInfo lookup(String ip) {
        if (isPrivateIp(ip)) return new GeoInfo("Private", "LAN", "Internal");

        CachedGeo cached = cache.get(ip);
        if (cached != null && (System.currentTimeMillis() - cached.fetchedAt()) < CACHE_TTL_MS) {
            return cached.info();
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(API_URL, ip)))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                GeoInfo info = parse(resp.body());
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
