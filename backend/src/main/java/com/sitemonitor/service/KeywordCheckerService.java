package com.sitemonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class KeywordCheckerService {

    /** Yanıt gövdesi okuma tavanı (OOM koruması) — keyword aramaya fazlasıyla yeter. */
    private static final int MAX_BODY_BYTES = 2_000_000;

    private final SsrfGuard ssrfGuard;
    private HttpClient httpClient;
    // Vekilli eş (2026-09-21) — HttpCheckerService ile aynı desen; alan enjeksiyonu (yapıcı testlerde elle kuruluyor).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProxySettings proxySettings;
    private HttpClient proxiedClient;

    /** İç-CA / self-signed HTTPS sitelerini de izleyebilmek için trust-all
     *  (içerik kontrolü; sertifika geçerliliği ayrı cert checker'da izlenir). */
    @PostConstruct
    public void init() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Yönlendirmeler MANUEL takip edilir → her hop SsrfGuard'dan geçsin. Redirect.NORMAL
                // zinciri kütüphane içinde takip ediyordu: ilk host doğrulansa bile hedef sunucu bizi
                // 302 ile iç ağa/metadata ucuna yönlendirebiliyordu ve gövdeden alınan snippet
                // KULLANICIYA dönüyordu. Desen PageFetchCore'dan (bkz. SafeRedirect).
                .followRedirects(HttpClient.Redirect.NEVER);
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
        if (proxySettings != null && proxySettings.enabled()) {
            java.net.Authenticator auth = proxySettings.authenticator(log, "Keyword checker");
            HttpClient.Builder pb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).proxy(proxySettings.proxySelector());
            try { pb.sslContext(httpClient.sslContext()); } catch (Exception ignore) { /* varsayılan güven */ }
            if (auth != null) pb.authenticator(auth);
            proxiedClient = pb.build();
        }
    }

    /** viaProxy: vekilli istemci (yapılandırılmamışsa doğrudan). */
    private HttpClient clientFor(boolean viaProxy) {
        return viaProxy && proxiedClient != null ? proxiedClient : httpClient;
    }

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String url, String keyword, int timeoutMs) {
        return CompletableFuture.completedFuture(check(url, keyword, timeoutMs));
    }

    /** Geriye uyum: özel header'sız, case-insensitive. */
    public Map<String, Object> check(String url, String keyword, int timeoutMs) {
        return check(url, keyword, timeoutMs, null, false);
    }

    /** Geriye uyum: case-insensitive. */
    public Map<String, Object> check(String url, String keyword, int timeoutMs, String customHeaders) {
        return check(url, keyword, timeoutMs, customHeaders, false);
    }

    /** {"found", "http_status", "response_ms", "snippet"?, "error"?} döner. {@code caseSensitive}=true ise
     *  büyük/küçük harf DUYARLI eşleşme. Cache busting: URL'deki {timestamp} → güncel Unix saniye;
     *  customHeaders ("Name: Value" satırları, ör. Cache-Control: no-cache). */
    public Map<String, Object> check(String url, String keyword, int timeoutMs, String customHeaders, boolean caseSensitive) {
        return check(url, keyword, timeoutMs, customHeaders, caseSensitive, false);
    }

    /** @param viaProxy kurumsal vekil üzerinden (karar {@link ProxyPolicyService}); sonuçta {@code via} proxy|direct. */
    public Map<String, Object> check(String url, String keyword, int timeoutMs, String customHeaders, boolean caseSensitive, boolean viaProxy) {
        return check(url, keyword, timeoutMs, customHeaders, caseSensitive, viaProxy, null);
    }

    /**
     * İzlemenin adet koşulu (operatör + eşik) — verilirse kontrol, koşul SAĞLANMADIĞINDA nedenini ve "neden bulunamadı"
     * ipuçlarını da üretir (2026-10-04). Koşulun KENDİSİ burada uygulanmaz; sağlıklı-mı kararı çağıranda kalır
     * ({@link #evaluate}, değişmedi).
     */
    public record Expectation(String operator, int threshold) {
        public static Expectation of(com.sitemonitor.model.KeywordMonitor m) {
            return new Expectation(m.getMatchOperator(), m.getMatchCount() != null ? m.getMatchCount() : 1);
        }
    }

    /**
     * Zenginleştirilmiş kontrol (2026-10-04, keyword hata teşhisi). Eski anahtarlar ve anlamları AYNEN (found, count,
     * http_status, response_ms, snippet, error, config_error, via); EK anahtarlar:
     * <ul>
     *   <li>her yanıtta: {@code final_url} (maskeli), {@code redirect_count}, {@code content_type}, {@code body_bytes},
     *       {@code body_truncated} (okuma tavanı doldu), {@code charset} (bildirilen küme);</li>
     *   <li>istek tamamlanmadıysa: {@code failure_reason} + {@code failure_detail} ({@link
     *       com.sitemonitor.service.keyword.KeywordFailureClassifier});</li>
     *   <li>{@code expectation} verilmiş ve koşul sağlanmamışsa: {@code failure_reason}, {@code failure_detail},
     *       {@code hints} (kod listesi) ve {@code excerpt} (görünür metinden ≤ 600 karakter, maskeli) —
     *       {@link com.sitemonitor.service.keyword.KeywordBodyAnalyzer}. Ek istek ATILMAZ; gövde zaten bellekte.</li>
     * </ul>
     */
    public Map<String, Object> check(String url, String keyword, int timeoutMs, String customHeaders, boolean caseSensitive,
                                     boolean viaProxy, Expectation expectation) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        boolean proxied = viaProxy && proxiedClient != null;
        result.put("via", proxied ? "proxy" : "direct");
        // Yapılandırma hatası (şemasız/host'suz URL) kesinti DEĞİL — istek atılmaz, alarm da açılmaz
        // (SchedulerService config_error bayrağını okur). Eskiden bu durum sahte DOWN alarmı üretiyordu.
        if (!com.sitemonitor.util.MonitorUrls.isCheckable(url)) {
            result.put("found", false);
            result.put("count", 0);
            result.put("config_error", true);
            result.put("error", com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG);
            result.put("failure_reason", com.sitemonitor.service.keyword.KeywordFailureClassifier.CONFIG_ERROR);
            result.put("failure_detail", "İzlemenin URL'si geçersiz (şema ya da host yok); istek gönderilmedi.");
            return result;
        }
        Trail trail = new Trail();
        try {
            // SSRF: hedef host HER hop'ta doğrulanır (metadata/loopback/link-local blok; iç ağ ayara bağlı).
            HttpResponse<InputStream> resp = sendFollowingSafely(applyTimestamp(url), timeoutMs, customHeaders, viaProxy, trail);
            byte[] bytes;
            boolean truncated = false;
            long ms;
            // Bellek koruması (gövde tavanı) + SÜRE koruması (prod kapısı 2026-09-25, N1): readNBytes EOF ya da
            // tavan gelene dek bloklar; kalp atışı gönderen bir SSE ucunda bu günler sürer ve keyword sweep'i
            // donardı. Gövde, başlık süresi kadar daha beklenir; dolarsa kontrol "zaman aşımı" hatasıyla biter.
            try (InputStream is = com.sitemonitor.util.HttpBodies.withDeadline(
                    resp.body(), Math.max(1000, timeoutMs), "Keyword")) {
                bytes = is.readNBytes(MAX_BODY_BYTES);
                ms = System.currentTimeMillis() - start;   // süre ölçümü yoklamadan ÖNCE (yavaşlık kararı değişmesin)
                if (bytes.length >= MAX_BODY_BYTES) {
                    // Tavan doldu: devamı var mı? Tek bayt yoklaması — sonuç (found/count) yine YALNIZ ilk MAX_BODY_BYTES'tan.
                    // Yoklama süre sınırına takılırsa ya da hata verirse "devamı vardı" sayılır; kontrolün kendisi DÜŞMEZ.
                    try { truncated = is.read() != -1; } catch (Exception probe) { truncated = true; }
                }
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            String hay = caseSensitive ? body : body.toLowerCase(Locale.ROOT);
            String needle = keyword == null ? "" : (caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT));
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
            // ── Yanıt meta verisi (2026-10-04) — her yanıtta, ucuz (başlık + en çok 4 KB meta koklaması) ──
            String contentType = resp.headers().firstValue("content-type").orElse(null);
            result.put("final_url", com.sitemonitor.service.keyword.KeywordBodyAnalyzer.displayUrl(resp.uri()));
            result.put("redirect_count", trail.redirects);
            result.put("content_type", contentType == null ? null
                    : (contentType.length() > 200 ? contentType.substring(0, 200) : contentType));
            result.put("body_bytes", (long) bytes.length);
            result.put("body_truncated", truncated);
            result.put("charset", com.sitemonitor.service.keyword.KeywordBodyAnalyzer.declaredCharset(contentType, bytes));
            // ── Koşul sağlanmadıysa: neden + ipuçları + alıntı (yalnız başarısızlıkta; gövde zaten bellekte) ──
            if (expectation != null && !evaluate(count, expectation.operator(), expectation.threshold())) {
                var reason = com.sitemonitor.service.keyword.KeywordFailureClassifier.forCondition(
                        resp.statusCode(), count, expectation.operator(), expectation.threshold(), bytes.length, truncated, keyword);
                result.put("failure_reason", reason.code());
                result.put("failure_detail", reason.detail());
                var analysis = com.sitemonitor.service.keyword.KeywordBodyAnalyzer.analyze(
                        new com.sitemonitor.service.keyword.KeywordBodyAnalyzer.Input(bytes, contentType, keyword, caseSensitive,
                                count, expectation.operator(), expectation.threshold(), resp.statusCode(), url, resp.uri(),
                                trail.redirects, secretValues(customHeaders)));
                if (!analysis.hints().isEmpty()) result.put("hints", analysis.hints());
                if (analysis.excerpt() != null) result.put("excerpt", analysis.excerpt());
            }
        } catch (SsrfGuard.BlockedException be) {
            // Politika reddi — dış istek HİÇ atılmadı. response_ms yazılmaz: ölçülen bir yanıt yok.
            result.put("found", false);
            result.put("count", 0);
            result.put("error", be.getMessage());
            putFailure(result, be, trail, timeoutMs, proxied);
        } catch (Exception e) {
            result.put("found", false);
            result.put("count", 0);
            result.put("response_ms", System.currentTimeMillis() - start);
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            putFailure(result, e, trail, timeoutMs, proxied);
            log.debug("Keyword check failed for {}: {}", url, e.getMessage());
        }
        return result;
    }

    /** İstek tamamlanmadı → neden + ayrıntı + takıldığı URL (yönlendirme ortasında düştüyse o hop'un adresi). */
    private static void putFailure(Map<String, Object> result, Throwable e, Trail trail, int timeoutMs, boolean proxied) {
        try {
            var reason = com.sitemonitor.service.keyword.KeywordFailureClassifier.forException(
                    e, trail.current == null ? null : trail.current.getHost(), Math.max(1000, timeoutMs), proxied);
            result.put("failure_reason", reason.code());
            result.put("failure_detail", reason.detail());
            if (trail.current != null) result.put("final_url", com.sitemonitor.service.keyword.KeywordBodyAnalyzer.displayUrl(trail.current));
            result.put("redirect_count", trail.redirects);
        } catch (RuntimeException ignore) {
            // Teşhis kontrolün kendisini ASLA bozmaz: neden yazılamazsa eski hata metni yine kayıtta.
        }
    }

    /** Yönlendirme izi: şu anki (son denenen) URI + izlenen yönlendirme sayısı. */
    static final class Trail {
        URI current;
        int redirects;
    }

    /** Alıntıdan süzülecek sır değerleri: izlemenin özel başlıklarından sır OLABİLECEK değerler (MIME türü vb. hariç). */
    static java.util.List<String> secretValues(String customHeaders) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (customHeaders == null || customHeaders.isBlank()) return out;
        for (String line : customHeaders.split("\\r?\\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String name = line.substring(0, c).trim();
            String value = line.substring(c + 1).trim();
            if (com.sitemonitor.service.http.diagnose.HttpDiagnosticsService.secretLike(name, value)) {
                out.add(value);
                int sp = value.indexOf(' ');   // "Bearer <jeton>" → jetonun kendisi de
                if (sp > 0 && sp < value.length() - 1) out.add(value.substring(sp + 1).trim());
            }
        }
        return out;
    }

    /**
     * İsteği gönderir; yönlendirmeleri MANUEL takip eder ve HER hop'ta {@link SsrfGuard}'ı çalıştırır.
     *
     * <p>Bu uç, eşleşen metnin çevresinden 200 karakterlik bir {@code snippet} DÖNDÜRÜYOR; yani
     * takip edilen son hop'un gövdesi kullanıcıya ulaşıyor. Otomatik takipte (Redirect.NORMAL)
     * yalnız ilk host doğrulanıyordu ve keyword izlemesi sıradan kullanıcı yetkisiyle
     * tanımlanabildiği için bu gerçek bir veri sızıntısı yüzeyiydi.
     *
     * <p>Takip edilemeyen bir {@code Location} (http/https dışı şema, host'suz hedef) hata değildir:
     * 3xx yanıt OLDUĞU GİBİ döner ve gövdesi TÜKETİLMEZ — çağıran okuyacaktır.
     */
    private HttpResponse<InputStream> sendFollowingSafely(String url, int timeoutMs, String customHeaders, boolean viaProxy,
                                                         Trail trail)
            throws java.io.IOException, InterruptedException {
        URI current = URI.create(url);
        trail.current = current;
        // Özel başlıklar YALNIZ ilk host'a gider. customHeaders kullanıcı girdisi ve pratikte sır
        // taşıyor (Authorization / X-Api-Key); yönlendirme hedefi başka bir host'a çıktığında onu
        // da göndermek anahtarı yabancıya teslim etmek demek. Tarayıcıların cross-origin
        // yönlendirmede Authorization düşürmesiyle aynı kural.
        final String originHost = current.getHost() == null ? "" : current.getHost();
        for (int hop = 0; hop <= SafeRedirect.MAX_HOPS; hop++) {
            String host = current.getHost();
            if (host == null || host.isBlank())
                throw new SsrfGuard.BlockedException("geçersiz hedef URL: " + current);
            ssrfGuard.validate(host);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(current)
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .header("User-Agent", "SiteMonitor-KeywordMonitor/1.0");
            if (originHost.equalsIgnoreCase(host)) applyCustomHeaders(rb, customHeaders);
            HttpResponse<InputStream> resp =
                    clientFor(viaProxy).send(rb.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (!SafeRedirect.isRedirect(resp.statusCode())) return resp;
            URI next = SafeRedirect.nextHop(current, resp.headers().firstValue("location").orElse(null));
            // Takip edilemeyen hedef (şema dışı / host'suz / HTTPS→HTTP düşürmesi) → 3xx olduğu gibi
            // döner. HttpCheckerService:346 ile AYNI satır: elle takibe geçen iki çağıran da
            // Redirect.NORMAL'in davranışını korur (SafeRedirect.isDowngrade javadoc'u).
            if (next == null || SafeRedirect.isDowngrade(current, next)) return resp;
            // Yönlendirme gövdesi: bağlantı iadesi için kısa okuma — süre sınırlı (N1), hata yok sayılır.
            try (InputStream is = com.sitemonitor.util.HttpBodies.withDeadline(resp.body(), Math.max(1000, timeoutMs), "Keyword")) {
                is.readNBytes(4096);
            } catch (Exception ignore) { /* bağlantı iadesi */ }
            current = next;
            trail.current = next;
            trail.redirects++;
        }
        throw new java.io.IOException("çok fazla yönlendirme (" + SafeRedirect.MAX_HOPS + " hop aşıldı)");
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
