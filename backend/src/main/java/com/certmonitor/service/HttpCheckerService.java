package com.certmonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP / Website uptime checker — bir URL'ye istek atıp yanıt durum kodunu + süresini ölçer.
 * SAĞLIKLI (ok) = durum kodu {@code expectedStatus} pattern'ine uyuyor ve hata yok.
 * Gövde okunmaz (yalnız durum kodu; {@link HttpResponse.BodyHandlers#discarding()}) → düşük maliyet.
 *
 * {@code verifySsl=false} (varsayılan) → trust-all SSL (yalnız erişilebilirlik; iç-CA/self-signed dahil);
 * {@code verifySsl=true} → JVM cacerts VEYA Genel Ayarlar kurumsal CA paketi VEYA host'un otomatik
 * pinlenmiş CA'sı ({@link TrustEvaluator}, {@link CaAutoPinService}; canlı reload) ile doğrulama;
 * TLS hatası bağlantı hatası olarak down sayılır. PKIX güven hatasında auto-pin açıksa CA sunucudan
 * çekilip pinlenir ve kontrol BİR kez tekrarlanır (sonuçta {@code repinned=true}).
 * Yönlendirme takibi client düzeyinde olduğundan (java.net.http) 4 istemci ön-kurulur:
 * {trustAll, strict} × {redirect NORMAL, NEVER}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpCheckerService {

    private final TrustEvaluator trustEvaluator;
    private final CaAutoPinService caAutoPinService;
    private final SsrfGuard ssrfGuard;

    private HttpClient trustAllFollow;
    private HttpClient trustAllNoFollow;
    private HttpClient strictFollow;
    private HttpClient strictNoFollow;

    // Çok-A pin yolu için saklanan SSLContext'ler (paylaşılan client'larla aynı güven) + per-host pinned client cache.
    private SSLContext trustAllCtx;
    private SSLContext strictCtx;
    // Per-host pinned client cache — SINIRLI LRU. Her JDK HttpClient kendi selector-thread + connection
    // pool + FD tutar; sınırsız ConcurrentHashMap 200-1000 domainde yüzlerce-1000+ resident client →
    // thread/FD/heap sızıntısıydı. Erişim-sıralı LinkedHashMap; kapasiteyi aşınca en eski client KAPATILIR
    // (JDK 21+ HttpClient AutoCloseable). Erişim synchronized (LinkedHashMap thread-safe değil + LRU mutasyonu).
    private static final int MAX_PINNED_CLIENTS = 64;
    private final Map<String, HttpClient> pinnedClients =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HttpClient> eldest) {
                    if (size() > MAX_PINNED_CLIENTS) {
                        try { eldest.getValue().close(); } catch (Exception ignore) { /* best-effort */ }
                        return true;
                    }
                    return false;
                }
            };

    private static final Pattern CN_PATTERN = Pattern.compile("CN=([^,]+)", Pattern.CASE_INSENSITIVE);

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
        // Strict: cacerts VEYA kurumsal CA paketi VEYA host'un pinlenmiş CA'sı; TM ayar/pin'i her
        // handshake'te canlı okur, client'ın bir kez kurulması reload'u engellemez. null → varsayılan güven.
        SSLContext strict = trustEvaluator.pinAwareOutboundSslContext(
                caAutoPinService::trustManagerForHost, caAutoPinService::recordTrustFailure);
        this.trustAllCtx = trustAll;
        this.strictCtx   = strict;
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

    /** Bir deneme sonucu + yakalanan hata (trust-failure sınıflandırması için). */
    private record Attempt(Map<String, Object> result, Exception cause) {}

    /**
     * {"http_status", "response_ms", "ok", "error"?, "repinned"?} döner. Strict (verifySsl=true) https
     * kontrolü PKIX güven hatasıyla düşerse ve auto-pin açıksa: hedef host (+ handshake'te reddedilen
     * redirect hedefleri) sunucudan pinlenir ve kontrol BİR kez tekrarlanır — rekürsiyon yok.
     */
    public Map<String, Object> check(String url, String method, String expectedStatus,
                                     int timeoutMs, boolean verifySsl, boolean followRedirects) {
        // Yapılandırma hatası (şemasız/host'suz URL) kesinti DEĞİL — istek atılmaz, alarm da açılmaz
        // (SchedulerService config_error bayrağını okur). Eskiden bu durum sahte DOWN alarmı üretiyordu.
        if (!com.certmonitor.util.MonitorUrls.isCheckable(url)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("config_error", true);
            r.put("error", com.certmonitor.util.MonitorUrls.CONFIG_ERROR_MSG);
            return r;
        }
        // SSRF: hedef host'u istekten önce doğrula (metadata/loopback/link-local blok; iç ağ ayara bağlı).
        String blocked = ssrfBlockReason(url);
        if (blocked != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("error", blocked);
            return r;
        }
        Attempt a1 = doCheck(url, method, expectedStatus, timeoutMs, verifySsl, followRedirects);
        if (Boolean.TRUE.equals(a1.result().get("ok")) || !verifySsl
                || !isTrustFailure(a1.cause()) || !caAutoPinService.isEnabled()) {
            return a1.result();
        }
        boolean pinned = false;
        try {
            URI uri = URI.create(url.trim());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                int port = uri.getPort() == -1 ? 443 : uri.getPort();
                pinned = caAutoPinService.pinFromServer(uri.getHost(), port, "http-check");
            }
        } catch (Exception e) {
            log.debug("Auto-pin URL parse failed for {}: {}", url, e.getMessage());
        }
        // Redirect hedefi farklı bir host'ta reddedilmiş olabilir — TM'in kaydettiği hedefleri de pinle.
        for (String hp : caAutoPinService.drainRecentTrustFailures()) {
            int idx = hp.lastIndexOf(':');
            if (idx <= 0) continue;
            try {
                pinned |= caAutoPinService.pinFromServer(
                        hp.substring(0, idx), Integer.parseInt(hp.substring(idx + 1)), "http-check");
            } catch (NumberFormatException ignore) { /* bozuk anahtar — atla */ }
        }
        if (!pinned) return a1.result();
        Attempt a2 = doCheck(url, method, expectedStatus, timeoutMs, verifySsl, followRedirects);
        a2.result().put("repinned", true);
        return a2.result();
    }

    /** Cause zincirinde PKIX/güven-yolu hatası var mı? (Kanonik sınıflandırma CaAutoPinService'te.) */
    static boolean isTrustFailure(Throwable t) {
        return CaAutoPinService.isTrustFailure(t);
    }

    /** SSRF: URL host'u çözülüp doğrulanır → engelliyse neden, değilse null. Parse hatası/relatif URL → null
     *  (doCheck normal hata yolunda ele alır). Not: HttpClient isteği yeniden çözer → dar DNS-rebind kalıntısı. */
    private String ssrfBlockReason(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) return null;
            ssrfGuard.validate(host);
            return null;
        } catch (SsrfGuard.BlockedException be) {
            return be.getMessage();
        } catch (Exception e) {
            return null;
        }
    }

    private Attempt doCheck(String url, String method, String expectedStatus,
                            int timeoutMs, boolean verifySsl, boolean followRedirects) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        Exception failure = null;
        try {
            String m = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
            HttpResponse<Void> resp = sendMultiAware(
                    URI.create(url.trim()), m, timeoutMs, verifySsl, followRedirects);
            long ms = System.currentTimeMillis() - start;
            int status = resp.statusCode();
            result.put("http_status", status);
            result.put("response_ms", ms);
            result.put("ok", matchesStatus(status, expectedStatus));
        } catch (Exception e) {
            failure = e;
            result.put("http_status", null);
            result.put("response_ms", System.currentTimeMillis() - start);
            result.put("ok", false);
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.debug("HTTP check failed for {}: {}", url, e.getMessage());
        }
        return new Attempt(result, failure);
    }

    /**
     * Çok-A farkındalıklı gönderim. Tek-A / IP-literal / host-yok → mevcut paylaşılan client yolu
     * (davranış AYNEN korunur, regresyon yok). Birden çok A kaydında: erişilebilir bir IP'ye pinleyip
     * SNI=domain ile gönderir; <b>herhangi bir sorunda eski yola düşer</b> (en kötü durumda bugünle aynı).
     */
    private HttpResponse<Void> sendMultiAware(URI baseUri, String method, int timeoutMs,
                                              boolean verifySsl, boolean followRedirects)
            throws java.io.IOException, InterruptedException {
        HttpClient shared = client(verifySsl, followRedirects);
        String host = baseUri.getHost();
        if (host == null || NetworkResolver.isIpLiteral(host)) {
            return shared.send(buildRequest(baseUri, method, timeoutMs, null), HttpResponse.BodyHandlers.discarding());
        }
        List<InetAddress> addrs = NetworkResolver.allAddresses(host);
        if (addrs.size() <= 1) {
            return shared.send(buildRequest(baseUri, method, timeoutMs, null), HttpResponse.BodyHandlers.discarding());
        }
        // Strict (verifySsl) doğrulama host-bazlı auto-pin'e dayanır; IP'ye pinlemek trust manager'ın
        // gördüğü host'u (=IP) pin anahtarından (=hostname) ayırıp pin lookup'ını bozar. Bu yüzden çok-A
        // pin YALNIZ trust-all (verifySsl=false — website monitörlerinin varsayılanı) için uygulanır;
        // strict eski paylaşılan-client yolunu korur (auto-pin bütünlüğü). Cert checker ayrı yoldadır.
        boolean https = "https".equalsIgnoreCase(baseUri.getScheme());
        int port = baseUri.getPort() != -1 ? baseUri.getPort() : (https ? 443 : 80);
        InetAddress reachable = verifySsl ? null
                : NetworkResolver.firstReachable(addrs, port, Math.min(Math.max(1000, timeoutMs), 4000));
        HttpClient pinned = reachable != null ? pinnedClient(host, false, followRedirects) : null;
        if (pinned != null) {
            try {
                URI pinnedUri = rewriteHostToIp(baseUri, reachable, port);
                HttpResponse<Void> r = pinned.send(
                        buildRequest(pinnedUri, method, timeoutMs, host), HttpResponse.BodyHandlers.discarding());
                // Strict HTTPS: SNI=domain gönderdik ama URI=IP olduğundan yerleşik hostname doğrulaması
                // kapalı → peer sertifikayı domain'e göre elle doğrula (güven zinciri TM'de zaten kontrol edildi).
                if (!verifySsl || !https || peerHostnameMatches(r, host)) {
                    return r;
                }
                log.debug("Pinned multi-A HTTP: peer hostname mismatch for {} → falling back", host);
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                log.debug("Pinned multi-A HTTP attempt failed for {} → falling back: {}", host, e.getMessage());
            }
        }
        // Fallback: bugünkü paylaşılan-client davranışı (pin başarısız/uygun değilse bugünden kötü değil).
        return shared.send(buildRequest(baseUri, method, timeoutMs, null), HttpResponse.BodyHandlers.discarding());
    }

    private HttpRequest buildRequest(URI uri, String method, int timeoutMs, String hostHeader) {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .header("User-Agent", "CertMonitor-HttpMonitor/1.0");
        if (hostHeader != null) {
            // "Host" kısıtlı header — yalnız -Djdk.httpclient.allowRestrictedHeaders=host set ise geçer.
            // Set edilemezse: HTTPS'te SNI zaten domain'e yönlendirir; sessizce geç.
            try { rb.header("Host", hostHeader); }
            catch (IllegalArgumentException ignore) { /* kısıtlı header kapalı — SNI'ye güven */ }
        }
        switch (method) {
            case "HEAD" -> rb.method("HEAD", HttpRequest.BodyPublishers.noBody());
            case "POST" -> rb.POST(HttpRequest.BodyPublishers.noBody());
            default     -> rb.GET();
        }
        return rb.build();
    }

    /** Per-host pinned client: SNI=host, yerleşik endpoint-identification kapalı (URI=IP). Güven paylaşılan ctx'ten. */
    private HttpClient pinnedClient(String host, boolean verifySsl, boolean followRedirects) {
        SSLContext ctx = verifySsl ? strictCtx : trustAllCtx;
        if (ctx == null) return null;
        String key = host + "|" + verifySsl + "|" + followRedirects;
        // synchronized: LinkedHashMap (LRU) thread-safe değil; computeIfAbsent + removeEldestEntry atomik olmalı.
        synchronized (pinnedClients) {
            return pinnedClients.computeIfAbsent(key, k -> {
                SSLParameters sp = ctx.getDefaultSSLParameters();
                sp.setServerNames(List.of(new SNIHostName(host)));
                sp.setEndpointIdentificationAlgorithm(null);
                return HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                        .sslContext(ctx)
                        .sslParameters(sp)
                        .build();
            });
        }
    }

    /** Kapanışta pinned client'ları serbest bırak (selector-thread + FD). Best-effort; paylaşılan 4 client JVM ile gider. */
    @jakarta.annotation.PreDestroy
    public void closePinnedClients() {
        synchronized (pinnedClients) {
            for (HttpClient c : pinnedClients.values()) {
                try { c.close(); } catch (Exception ignore) { /* best-effort */ }
            }
            pinnedClients.clear();
        }
    }

    /** baseUri'nin host'unu IP-literaline çevirir (şema/port/path/query korunur). */
    private static URI rewriteHostToIp(URI baseUri, InetAddress ip, int port) {
        String h = ip.getHostAddress();
        if (ip instanceof java.net.Inet6Address) {
            int z = h.indexOf('%'); if (z >= 0) h = h.substring(0, z);   // zone-id kırp
            h = "[" + h + "]";
        }
        StringBuilder sb = new StringBuilder(baseUri.getScheme()).append("://").append(h).append(":").append(port);
        String path = baseUri.getRawPath();
        sb.append(path == null || path.isEmpty() ? "/" : path);
        if (baseUri.getRawQuery() != null) sb.append("?").append(baseUri.getRawQuery());
        return URI.create(sb.toString());
    }

    /** Yanıtın TLS oturumundaki peer sertifika, domain'e (SAN/CN, wildcard) uyuyor mu. */
    private static boolean peerHostnameMatches(HttpResponse<?> resp, String host) {
        SSLSession session = resp.sslSession().orElse(null);
        if (session == null) return false;
        try {
            Certificate[] peer = session.getPeerCertificates();
            if (peer.length == 0 || !(peer[0] instanceof X509Certificate leaf)) return false;
            return hostnameMatches(leaf, host);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean hostnameMatches(X509Certificate cert, String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> e : sans) {
                    if (Integer.valueOf(2).equals(e.get(0)) && e.get(1) != null) names.add(String.valueOf(e.get(1)));
                }
            }
        } catch (Exception ignore) { /* SAN yoksa CN'e düş */ }
        if (names.isEmpty()) {
            Matcher m = CN_PATTERN.matcher(cert.getSubjectX500Principal().getName());
            if (m.find()) names.add(m.group(1).trim());
        }
        for (String n : names) {
            if (matchName(n.toLowerCase(Locale.ROOT), h)) return true;
        }
        return false;
    }

    /** RFC 6125 sadeleştirilmiş: tam eşleşme veya en soldaki '*' joker (tek etiket). */
    static boolean matchName(String pattern, String host) {
        if (pattern.equals(host)) return true;
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);          // ".example.com"
            int dot = host.indexOf('.');
            return dot > 0 && host.substring(dot).equals(suffix);
        }
        return false;
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
