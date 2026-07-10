package com.certmonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registrar (WHOIS) domain kayıt bitişini RDAP üzerinden (HTTPS + JSON) sorgular.
 * RDAP, WHOIS metin-ayrıştırmasına göre çok daha sağlamdır: {@code events[]} içinde
 * {@code eventAction == "expiration"} → {@code eventDate}.
 *
 * Sonuç per-domain cache'lenir (bitiş nadiren değişir; TTL 12s). Public kaydı olmayan
 * (ör. iç Akbank) domain'lerde RDAP 404/boş döner → {@code days_remaining = null} (unknown,
 * alarm YOK). TLS sertifika bitişinden (CertificateCheckerService) bağımsızdır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RdapDomainExpiryService {

    private final AppSettingsService appSettings;

    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1000L;   // 12 saat
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** İki-seviyeli public ekler (eTLD+1 çıkarımı için; tam PSL değil, yaygın olanlar + .tr). */
    private static final Set<String> TWO_LEVEL_TLDS = Set.of(
            "com.tr", "net.tr", "org.tr", "gov.tr", "edu.tr", "k12.tr", "av.tr", "bel.tr",
            "biz.tr", "gen.tr", "info.tr", "name.tr", "tel.tr", "web.tr", "tv.tr",
            "co.uk", "org.uk", "gov.uk", "ac.uk", "me.uk",
            "com.au", "net.au", "org.au", "co.nz", "co.jp", "com.br", "com.cn",
            "co.in", "com.sg", "com.hk", "com.mx", "com.ar", "co.za");

    private record Cached(Map<String, Object> result, long fetchedAtMs) {}

    /** {"domain", "expiry_date"?, "days_remaining"? (null=unknown), "error"?} döner. */
    public Map<String, Object> check(String hostOrUrl) {
        String domain = registrableDomain(extractHost(hostOrUrl));
        if (domain == null || domain.isBlank()) return unknown(hostOrUrl, "invalid domain");

        Cached c = cache.get(domain);
        if (c != null && (System.currentTimeMillis() - c.fetchedAtMs) < CACHE_TTL_MS) return c.result;

        Map<String, Object> res = query(domain);
        cache.put(domain, new Cached(res, System.currentTimeMillis()));
        return res;
    }

    private Map<String, Object> query(String domain) {
        String base = appSettings.getString("cert.monitor.http.rdap-base-url", "https://rdap.org/domain/");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + URLEncoder.encode(domain, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/rdap+json")
                    .header("User-Agent", "CertMonitor-HttpMonitor/1.0")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return unknown(domain, "rdap http " + resp.statusCode());
            JsonNode root = mapper.readTree(resp.body());
            JsonNode events = root.get("events");
            if (events != null && events.isArray()) {
                for (JsonNode ev : events) {
                    String action = ev.path("eventAction").asText("");
                    if ("expiration".equalsIgnoreCase(action)) {
                        String date = ev.path("eventDate").asText(null);
                        Integer days = daysUntil(date);
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("domain", domain);
                        out.put("expiry_date", date);
                        out.put("days_remaining", days);
                        return out;
                    }
                }
            }
            return unknown(domain, "no expiration event");
        } catch (Exception e) {
            log.debug("RDAP lookup failed for {}: {}", domain, e.getMessage());
            return unknown(domain, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static Map<String, Object> unknown(String domain, String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", domain);
        out.put("expiry_date", null);
        out.put("days_remaining", null);   // null = unknown → alarm YOK
        out.put("error", error);
        return out;
    }

    /** ISO-8601 tarihten bugüne kalan tam gün (negatif = süresi geçmiş). Parse edilemezse null. */
    static Integer daysUntil(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            Instant when;
            try { when = OffsetDateTime.parse(iso).toInstant(); }
            catch (Exception e1) {
                try { when = Instant.parse(iso); }
                catch (Exception e2) { when = LocalDate.parse(iso.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant(); }
            }
            return (int) ChronoUnit.DAYS.between(Instant.now(), when);
        } catch (Exception e) { return null; }
    }

    /** URL veya host'tan çıplak host'u ayıklar (şema/port/path atılır). */
    static String extractHost(String hostOrUrl) {
        if (hostOrUrl == null) return null;
        String s = hostOrUrl.trim();
        if (s.isEmpty()) return null;
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');   if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');      if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');   if (colon >= 0) s = s.substring(0, colon);
        return s.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
    }

    /** host → eTLD+1 (kayıt edilebilir domain). İki-seviyeli ekler için 3 etiket, aksi halde 2. */
    static String registrableDomain(String host) {
        if (host == null || host.isBlank()) return null;
        String[] labels = host.split("\\.");
        if (labels.length <= 2) return host;
        String lastTwo = labels[labels.length - 2] + "." + labels[labels.length - 1];
        int take = TWO_LEVEL_TLDS.contains(lastTwo) ? 3 : 2;
        if (labels.length < take) return host;
        StringBuilder sb = new StringBuilder();
        for (int i = labels.length - take; i < labels.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(labels[i]);
        }
        return sb.toString();
    }
}
