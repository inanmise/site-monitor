package com.sitemonitor.service;

import com.sitemonitor.service.http.HttpRequestOptions;
import com.sitemonitor.service.http.HttpRequestRules;
import com.sitemonitor.service.http.JsonAssertion;
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
 * Gövde saklanmaz (yalnız durum kodu); bağlantı havuza dönsün diye tüketilir ama SÜRE SINIRIYLA
 * ({@link #sendDrained} — akış yapan hedef sweep'i donduramaz) → düşük maliyet. İSTİSNA (2026-10-01): izlemede JSON
 * doğrulaması tanımlıysa gövde tavanlı (2 MB) okunup doğrulanır; özel başlık / Basic auth / POST gövdesi de
 * {@link HttpRequestOptions} ile opsiyoneldir — tanımsızken istek ve karar eskisinin birebir aynısıdır.
 *
 * {@code verifySsl=false} (varsayılan) → trust-all SSL (yalnız erişilebilirlik; iç-CA/self-signed dahil);
 * {@code verifySsl=true} → JVM cacerts VEYA Genel Ayarlar kurumsal CA paketi VEYA host'un otomatik
 * pinlenmiş CA'sı ({@link TrustEvaluator}, {@link CaAutoPinService}; canlı reload) ile doğrulama;
 * TLS hatası bağlantı hatası olarak down sayılır. PKIX güven hatasında auto-pin açıksa CA sunucudan
 * çekilip pinlenir ve kontrol BİR kez tekrarlanır (sonuçta {@code repinned=true}).
 * Yönlendirmeler UYGULAMA katmanında takip edilir ({@link #sendFollowing}) — her hop {@link SsrfGuard}'dan
 * geçsin diye. Bu yüzden yalnız 2 istemci ön-kurulur: {trustAll, strict} × {redirect NEVER}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpCheckerService {

    private final TrustEvaluator trustEvaluator;
    private final CaAutoPinService caAutoPinService;
    private final SsrfGuard ssrfGuard;

    // Yönlendirme takibi UYGULAMA katmanında (bkz. sendFollowing + SafeRedirect): kütüphane içi takipte
    // ara hop'lar SsrfGuard'a hiç uğramıyordu. Bu yüzden yalnız NEVER client'ları kurulur — ayrıca iki
    // client (+ selector-thread + connection pool) eksilir; tek pod'da bu da bir kazanç.
    private HttpClient trustAllNoFollow;
    private HttpClient strictNoFollow;
    // Vekilli eşler (2026-09-21): izleme "vekil üzerinden" istiyorsa bunlar kullanılır. Alan enjeksiyonu (required=false):
    // yapıcı imzası testlerde elle kuruluyor; vekil bileşeni yoksa (test) vekilli istemci kurulmaz → doğrudan.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProxySettings proxySettings;
    private HttpClient trustAllProxied;
    private HttpClient strictProxied;

    // Çok-A pin yolu için saklanan SSLContext'ler (paylaşılan client'larla aynı güven) + per-host pinned client cache.
    private SSLContext trustAllCtx;
    private SSLContext strictCtx;
    // Per-host pinned client cache — SINIRLI LRU. Her JDK HttpClient kendi selector-thread + connection
    // pool + FD tutar; sınırsız ConcurrentHashMap 200-1000 domainde yüzlerce-1000+ resident client →
    // thread/FD/heap sızıntısıydı. Erişim-sıralı LinkedHashMap; kapasiteyi aşınca en eski client KAPATILIR
    // (JDK 21+ HttpClient AutoCloseable). Erişim synchronized (LinkedHashMap thread-safe değil + LRU mutasyonu).
    private static final int MAX_PINNED_CLIENTS = 64;
    // D3: tahliye edilen client KİLİT ALTINDA kapatılmaz — JDK21 HttpClient.close() uçuşan
    // istekleri bekler; synchronized(pinnedClients) içinde beklemek TÜM lookup'ları bloklardı.
    // removeEldestEntry yalnız bu listeye bırakır; kapatma kilit bırakıldıktan sonra yapılır.
    private final List<HttpClient> evictedClients = new ArrayList<>();
    private final Map<String, HttpClient> pinnedClients =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HttpClient> eldest) {
                    if (size() > MAX_PINNED_CLIENTS) {
                        evictedClients.add(eldest.getValue());
                        return true;
                    }
                    return false;
                }
            };

    /** Kilit dışında, birikmiş tahliyeleri kapatır (D3). */
    private void closeEvictedClients() {
        List<HttpClient> toClose;
        synchronized (pinnedClients) {
            if (evictedClients.isEmpty()) return;
            toClose = new ArrayList<>(evictedClients);
            evictedClients.clear();
        }
        for (HttpClient c : toClose) {
            try { c.close(); } catch (Exception ignore) { /* best-effort */ }
        }
    }

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
                caAutoPinService::trustManagerForHost, (h, prt) -> caAutoPinService.recordTrustFailure("http-check", h, prt));
        this.trustAllCtx = trustAll;
        this.strictCtx   = strict;
        trustAllNoFollow = build(trustAll);
        strictNoFollow   = build(strict);
        if (proxySettings != null && proxySettings.enabled()) {
            java.net.Authenticator auth = proxySettings.authenticator(log, "HTTP checker");
            trustAllProxied = build(trustAll, proxySettings.proxySelector(), auth);
            strictProxied   = build(strict, proxySettings.proxySelector(), auth);
            log.info("HTTP checker vekilli istemci hazır: {}:{} (kimlik: {})", proxySettings.host(), proxySettings.port(),
                    auth != null ? "Basic" : "anonim");
        }
    }

    private HttpClient build(SSLContext ssl) { return build(ssl, null, null); }

    private HttpClient build(SSLContext ssl, java.net.ProxySelector proxy, java.net.Authenticator auth) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (ssl != null) b.sslContext(ssl);
        if (proxy != null) b.proxy(proxy);
        if (auth != null) b.authenticator(auth);
        return b.build();
    }

    private HttpClient client(boolean verifySsl) { return client(verifySsl, false); }

    /** viaProxy: vekilli eş; vekil kurulmamışsa (test/yapılandırmasız) doğrudan istemciye düşer — sessizce değil, DEBUG'la. */
    private HttpClient client(boolean verifySsl, boolean viaProxy) {
        if (viaProxy) {
            HttpClient p = verifySsl ? strictProxied : trustAllProxied;
            if (p != null) return p;
            log.debug("HTTP checker: vekil istendi ama yapılandırılmamış → doğrudan");
        }
        return verifySsl ? strictNoFollow : trustAllNoFollow;
    }

    /** Bir deneme sonucu + yakalanan hata (trust-failure sınıflandırması için). */
    private record Attempt(Map<String, Object> result, Exception cause) {}

    private HttpFailureDiagnostics.Trace newTrace(String url, String method, int timeoutMs, boolean verifySsl, boolean followRedirects, boolean proxied) {
        String proxyTarget = proxied && proxySettings != null ? proxySettings.host() + ":" + proxySettings.port() : null;
        return new HttpFailureDiagnostics.Trace().start(url, method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT),
                timeoutMs, verifySsl, followRedirects, proxied ? "proxy" : "direct", proxyTarget);
    }

    /** Hata sonrası hedef IP'yi çözer (başarı yolunda ek maliyet YOK). Çözülemiyorsa liste boş kalır — tanı DNS der. */
    private static void lateResolve(HttpFailureDiagnostics.Trace trace) {
        if (trace.host == null) return;
        if (NetworkResolver.isIpLiteral(trace.host)) { trace.targetIp = trace.host; trace.resolvedIps.add(trace.host); trace.dnsMs = 0L; return; }   // literal: çözümleme yok
        long t0 = System.currentTimeMillis();
        try { trace.resolved(NetworkResolver.allAddresses(trace.host), System.currentTimeMillis() - t0); }
        catch (Exception ignore) { trace.dnsMs = System.currentTimeMillis() - t0; }
    }

    /**
     * {"http_status", "response_ms", "ok", "error"?, "repinned"?} döner. Strict (verifySsl=true) https
     * kontrolü PKIX güven hatasıyla düşerse ve auto-pin açıksa: hedef host (+ handshake'te reddedilen
     * redirect hedefleri) sunucudan pinlenir ve kontrol BİR kez tekrarlanır — rekürsiyon yok.
     */
    public Map<String, Object> check(String url, String method, String expectedStatus,
                                     int timeoutMs, boolean verifySsl, boolean followRedirects) {
        return check(url, method, expectedStatus, timeoutMs, verifySsl, followRedirects, false);
    }

    /**
     * @param viaProxy kurumsal vekil üzerinden (karar {@link ProxyPolicyService}'te verilir); sonuçta {@code via}
     *                 {@code proxy|direct} döner — sertifika kontrolüyle aynı sözleşme.
     */
    public Map<String, Object> check(String url, String method, String expectedStatus,
                                     int timeoutMs, boolean verifySsl, boolean followRedirects, boolean viaProxy) {
        return check(url, method, expectedStatus, timeoutMs, verifySsl, followRedirects, viaProxy, HttpRequestOptions.NONE);
    }

    /**
     * Gelişmiş istek seçenekleriyle kontrol (2026-10-01, onaylı öneri 9): özel başlıklar + Basic auth (yalnız İLK
     * host'a), POST gövdesi (yalnız POST + dolu gövde) ve JSON doğrulaması (yalnız durum kodu uyduysa; gövde
     * {@link HttpRequestRules#MAX_RESPONSE_BYTES} tavanıyla okunur). {@link HttpRequestOptions#NONE} ile davranış
     * yukarıdaki 7 argümanlı girişin BİREBİR aynısıdır; çağıranlar eklentisiz izlemede yine o girişi kullanır.
     *
     * <p>JSON doğrulaması düşerse sonuç {@code ok=false} + {@code error} (neden) + {@code json_assertion_failed=true}
     * olur — mevcut HTTP_DOWN kesinti yolundan geçer, yeni alarm türü yoktur.
     */
    public Map<String, Object> check(String url, String method, String expectedStatus,
                                     int timeoutMs, boolean verifySsl, boolean followRedirects, boolean viaProxy,
                                     HttpRequestOptions options) {
        final HttpRequestOptions opts = options == null ? HttpRequestOptions.NONE : options;
        // Tanıdaki `via` GERÇEKTEN vekil kullanıldı mı sorusunu yanıtlar (2026-09-22): izlemede useProxy=ON olsa da
        // sistemde vekil tanımlı değilse istek doğrudan gider. doCheck aynı ifadeyi kullanıyor; erken dönen iki dal
        // (config_error / SSRF) ham bayrağı geçtiği için tanı panelinde "via: proxy, proxy: null" gösteriyordu.
        boolean proxied = viaProxy && client(verifySsl, true) != client(verifySsl, false);
        // Yapılandırma hatası (şemasız/host'suz URL) kesinti DEĞİL — istek atılmaz, alarm da açılmaz
        // (SchedulerService config_error bayrağını okur). Eskiden bu durum sahte DOWN alarmı üretiyordu.
        if (!com.sitemonitor.util.MonitorUrls.isCheckable(url)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("config_error", true);
            r.put("error", com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG);
            r.put("error_detail", HttpFailureDiagnostics.toJson(HttpFailureDiagnostics.forException(
                    newTrace(url, method, timeoutMs, verifySsl, followRedirects, proxied),
                    new IllegalArgumentException("URL: " + com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG))));
            return r;
        }
        // SSRF: hedef host'u istekten önce doğrula (metadata/loopback/link-local blok; iç ağ ayara bağlı).
        String blocked = ssrfBlockReason(url);
        if (blocked != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("error", blocked);
            r.put("error_detail", HttpFailureDiagnostics.toJson(HttpFailureDiagnostics.forException(
                    newTrace(url, method, timeoutMs, verifySsl, followRedirects, proxied), new SsrfGuard.BlockedException(blocked))));
            return r;
        }
        // Su damgası: bu kontrol BAŞLAMADAN önceki güven hatası kayıtları bize ait değil. Eskiden
        // drain haritayı topluca boşaltıyordu ve paralel sweep'te bir monitör diğerinin kaydını
        // çalıyordu; watermark + kaynak filtresi kaydı sahibine bağlar.
        long trustWatermark = System.currentTimeMillis();
        Attempt a1 = doCheck(url, method, expectedStatus, timeoutMs, verifySsl, followRedirects, viaProxy, opts);
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
        for (String hp : caAutoPinService.recentTrustFailuresSince("http-check", trustWatermark)) {
            int idx = hp.lastIndexOf(':');
            if (idx <= 0) continue;
            try {
                pinned |= caAutoPinService.pinFromServer(
                        hp.substring(0, idx), Integer.parseInt(hp.substring(idx + 1)), "http-check");
            } catch (NumberFormatException ignore) { /* bozuk anahtar — atla */ }
        }
        if (!pinned) return a1.result();
        Attempt a2 = doCheck(url, method, expectedStatus, timeoutMs, verifySsl, followRedirects, viaProxy, opts);
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
                            int timeoutMs, boolean verifySsl, boolean followRedirects, boolean viaProxy,
                            HttpRequestOptions opts) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        boolean proxied = viaProxy && client(verifySsl, true) != client(verifySsl, false);
        result.put("via", proxied ? "proxy" : "direct");
        // Tanı izi (2026-09-22): çözümlenen IP'ler, pinlenen hedef, yönlendirme zinciri — yalnız hata anında JSON'a döner.
        HttpFailureDiagnostics.Trace trace = newTrace(url, method, timeoutMs, verifySsl, followRedirects, proxied);
        trace.expectedStatus = expectedStatus;
        Exception failure = null;
        // Gövde YALNIZ JSON doğrulaması tanımlıysa tamponlanır; aksi hâlde bugünkü gibi süre sınırıyla tüketilir.
        BodyCapture capture = opts.hasJsonAssertion() ? new BodyCapture() : null;
        try {
            String m = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
            HttpResponse<java.io.InputStream> resp = sendFollowing(
                    URI.create(url.trim()), m, timeoutMs, verifySsl, followRedirects, viaProxy, trace, opts, capture);
            long ms = System.currentTimeMillis() - start;
            int status = resp.statusCode();
            result.put("http_status", status);
            result.put("response_ms", ms);
            boolean ok = matchesStatus(status, expectedStatus);
            // JSON doğrulaması (2026-10-01): durum kodu UYDUYSA gövdeye bakılır. Düşerse aynı HTTP_DOWN yolu — neden
            // `error`'da (kart/geçmiş/e-posta onu gösterir), tanı BODY_ASSERTION.
            String assertionFailure = ok && capture != null
                    ? (capture.failure != null ? capture.failure
                       : JsonAssertion.evaluate(capture.bytes, capture.truncated, opts.jsonPath(), opts.jsonExpected()))
                    : null;
            if (assertionFailure != null) ok = false;
            result.put("ok", ok);
            if (assertionFailure != null) {
                result.put("error", assertionFailure);
                result.put("json_assertion_failed", true);
                trace.httpStatus = status; trace.elapsedMs = ms;
                result.put("error_detail", HttpFailureDiagnostics.toJson(HttpFailureDiagnostics.forBodyAssertion(trace, assertionFailure)));
            } else if (!ok) {
                trace.httpStatus = status; trace.elapsedMs = ms;
                result.put("error_detail", HttpFailureDiagnostics.toJson(HttpFailureDiagnostics.forStatusMismatch(trace)));
            }
        } catch (Exception e) {
            failure = e;
            result.put("http_status", null);
            long ms = System.currentTimeMillis() - start;
            result.put("response_ms", ms);
            result.put("ok", false);
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            trace.elapsedMs = ms;
            if (trace.resolvedIps.isEmpty()) lateResolve(trace);   // DNS'e hiç gelinmediyse/pin yoksa hedef IP'yi şimdi çöz (JVM önbelleği — ucuz)
            result.put("error_detail", HttpFailureDiagnostics.toJson(HttpFailureDiagnostics.forException(trace, e)));
            log.debug("HTTP check failed for {}: {}", url, e.getMessage());
        }
        return new Attempt(result, failure);
    }

    /**
     * Çok-A farkındalıklı gönderim. Tek-A / IP-literal / host-yok → mevcut paylaşılan client yolu
     * (davranış AYNEN korunur, regresyon yok). Birden çok A kaydında: erişilebilir bir IP'ye pinleyip
     * SNI=domain ile gönderir; <b>herhangi bir sorunda eski yola düşer</b> (en kötü durumda bugünle aynı).
     */
    /**
     * Yönlendirmeleri MANUEL takip eder ve HER hop'ta {@link SsrfGuard}'ı çalıştırır.
     *
     * <p>Önceden {@code Redirect.NORMAL} client'ı kullanılıyordu: zincir kütüphane içinde takip
     * edildiği için yalnız İLK host doğrulanıyor, hedef sunucunun {@code 302 Location:} ile
     * gösterdiği iç adresler denetimden kaçıyordu. Bu uç gövde döndürmez (yalnız durum kodu +
     * süre) ama yine de bir varlık/zamanlama oracle'ıdır.
     *
     * <p>Davranış korunur: {@code followRedirects=false} ise hiç hop yapılmaz (bugünkü yolun
     * birebir aynısı) ve HTTPS → düz HTTP düşürmesi {@code Redirect.NORMAL} gibi TAKİP EDİLMEZ.
     * Çok-A pin yolu ({@link #sendMultiAware}) her hop için ayrı ayrı çalışır.
     */
    private HttpResponse<java.io.InputStream> sendFollowing(URI baseUri, String method, int timeoutMs,
                                             boolean verifySsl, boolean followRedirects, boolean viaProxy,
                                             HttpFailureDiagnostics.Trace trace, HttpRequestOptions opts,
                                             BodyCapture capture)
            throws java.io.IOException, InterruptedException {
        if (!followRedirects) return sendMultiAware(baseUri, method, timeoutMs, verifySsl, viaProxy, trace, opts, true, capture);
        // Özel başlıklar, Basic auth ve gövde YALNIZ ilk host'a gider (Anahtar Kelime ile aynı kural): yönlendirme başka
        // bir host'a çıkınca onları da göndermek kimliği yabancıya teslim etmek olurdu — tarayıcıların cross-origin
        // yönlendirmede Authorization düşürmesiyle aynı.
        final String originHost = baseUri.getHost();
        URI current = baseUri;
        String m = method;
        for (int hop = 0; hop <= SafeRedirect.MAX_HOPS; hop++) {
            // İlk hop check() içinde zaten doğrulandı; sonrakiler burada (aynı politika, aynı mesaj).
            if (hop > 0) {
                String host = current.getHost();
                if (host == null || host.isBlank())
                    throw new SsrfGuard.BlockedException("geçersiz yönlendirme hedefi: " + current);
                ssrfGuard.validate(host);
            }
            boolean origin = originHost != null && originHost.equalsIgnoreCase(current.getHost());
            HttpResponse<java.io.InputStream> resp = sendMultiAware(current, m, timeoutMs, verifySsl, viaProxy, trace, opts, origin, capture);
            if (!SafeRedirect.isRedirect(resp.statusCode())) return resp;
            URI next = SafeRedirect.nextHop(current, resp.headers().firstValue("location").orElse(null));
            if (trace != null) trace.hop(next);
            // Takip edilemeyen hedef (şema dışı / host'suz / güvenlik düşürmesi) → 3xx olduğu gibi döner.
            if (next == null || SafeRedirect.isDowngrade(current, next)) return resp;
            m = SafeRedirect.nextMethod(resp.statusCode(), m);
            current = next;
        }
        throw new java.io.IOException("çok fazla yönlendirme (" + SafeRedirect.MAX_HOPS + " hop aşıldı)");
    }

    private HttpResponse<java.io.InputStream> sendMultiAware(URI baseUri, String method, int timeoutMs, boolean verifySsl, boolean viaProxy,
                                              HttpFailureDiagnostics.Trace trace, HttpRequestOptions opts, boolean origin,
                                              BodyCapture capture)
            throws java.io.IOException, InterruptedException {
        HttpClient shared = client(verifySsl, viaProxy);
        String host = baseUri.getHost();
        // Vekil yolunda çok-A pin uygulanmaz: hedefi vekil çözer, IP'ye yeniden yazmak CONNECT'i bozar.
        if (viaProxy && shared != client(verifySsl, false)) {
            return sendDrained(shared, buildRequest(baseUri, method, timeoutMs, null, opts, origin), timeoutMs, capture);
        }
        if (host == null || NetworkResolver.isIpLiteral(host)) {
            return sendDrained(shared, buildRequest(baseUri, method, timeoutMs, null, opts, origin), timeoutMs, capture);
        }
        long dns0 = System.currentTimeMillis();
        List<InetAddress> addrs = NetworkResolver.allAddresses(host);
        if (trace != null && trace.resolvedIps.isEmpty()) trace.resolved(addrs, System.currentTimeMillis() - dns0);
        if (addrs.size() <= 1) {
            return sendDrained(shared, buildRequest(baseUri, method, timeoutMs, null, opts, origin), timeoutMs, capture);
        }
        // Strict (verifySsl) doğrulama host-bazlı auto-pin'e dayanır; IP'ye pinlemek trust manager'ın
        // gördüğü host'u (=IP) pin anahtarından (=hostname) ayırıp pin lookup'ını bozar. Bu yüzden çok-A
        // pin YALNIZ trust-all (verifySsl=false — website monitörlerinin varsayılanı) için uygulanır;
        // strict eski paylaşılan-client yolunu korur (auto-pin bütünlüğü). Cert checker ayrı yoldadır.
        boolean https = "https".equalsIgnoreCase(baseUri.getScheme());
        int port = baseUri.getPort() != -1 ? baseUri.getPort() : (https ? 443 : 80);
        InetAddress reachable = verifySsl ? null
                : NetworkResolver.firstReachable(addrs, port, Math.min(Math.max(1000, timeoutMs), 4000));
        HttpClient pinned = reachable != null ? pinnedClient(host, false) : null;
        if (trace != null) trace.pinned(reachable);
        if (pinned != null) {
            try {
                URI pinnedUri = rewriteHostToIp(baseUri, reachable, port);
                HttpResponse<java.io.InputStream> r = sendDrained(
                        pinned, buildRequest(pinnedUri, method, timeoutMs, host, opts, origin), timeoutMs, capture);
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
        return sendDrained(shared, buildRequest(baseUri, method, timeoutMs, null, opts, origin), timeoutMs, capture);
    }

    /**
     * Gönderir ve gövdeyi SÜRE SINIRIYLA tüketir (prod kapısı 2026-09-25, N1).
     *
     * <p>Eskiden {@code BodyHandlers.discarding()} kullanılıyordu: {@code send()} gövde bitene kadar
     * dönmüyor ve java.net.http'nin zaman aşımı yalnız başlıklara kadar işliyor. Başlığı gönderip
     * gövdeyi bitirmeyen tek bir hedef (SSE, MJPEG kamera, radyo akışı) HTTP sweep'ini kalıcı olarak
     * donduruyordu. Şimdi başlıklar geldiğinde yanıt alınır, gövde en çok {@code timeoutMs} daha
     * tüketilir (normal sayfada davranış aynı: tam okunur, bağlantı havuza döner, response_ms gövdeyi
     * kapsar). Süre dolarsa akış kesilir; sonuç YİNE durum koduna göre verilir — bu kontrolün sözleşmesi
     * "yalnız durum kodu"dur ve akış yapan bir uç ayakta sayılmalıdır.
     */
    private static HttpResponse<java.io.InputStream> sendDrained(HttpClient client, HttpRequest req, int timeoutMs,
                                                                 BodyCapture capture)
            throws java.io.IOException, InterruptedException {
        HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (capture != null) {
            // JSON doğrulaması (2026-10-01): gövde TAVANLI ve SÜRE SINIRLI okunur (Anahtar Kelime ile aynı 2 MB).
            capture.read(resp.body(), Math.max(1000, timeoutMs));
            return resp;
        }
        boolean complete = com.sitemonitor.util.HttpBodies.drain(resp.body(), Math.max(1000, timeoutMs), "HTTP");
        if (!complete) {
            log.debug("HTTP check: {} gövdesi {} ms içinde bitmedi — akış kesildi, durum kodu {} kullanılıyor",
                    req.uri(), Math.max(1000, timeoutMs), resp.statusCode());
        }
        return resp;
    }

    /**
     * İstek kurucusu. {@code applyExtras=false} ya da {@link HttpRequestOptions#NONE} ile çıktı 2026-10-01 öncesinin
     * BİREBİR aynısıdır (User-Agent + gerekiyorsa Host; POST gövdesiz) — {@code HttpRequestBuildTest} kilitler.
     *
     * @param applyExtras özel başlık / Basic auth / gövde bu hop'a uygulanır mı (yalnız İLK host)
     */
    static HttpRequest buildRequest(URI uri, String method, int timeoutMs, String hostHeader,
                                    HttpRequestOptions opts, boolean applyExtras) {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .header("User-Agent", "SiteMonitor-HttpMonitor/1.0");
        if (hostHeader != null) {
            // "Host" kısıtlı header — yalnız -Djdk.httpclient.allowRestrictedHeaders=host set ise geçer.
            // Set edilemezse: HTTPS'te SNI zaten domain'e yönlendirir; sessizce geç.
            try { rb.header("Host", hostHeader); }
            catch (IllegalArgumentException ignore) { /* kısıtlı header kapalı — SNI'ye güven */ }
        }
        boolean extras = applyExtras && opts != null && !opts.isEmpty();
        boolean sendBody = extras && opts.sendsBody(method);
        if (extras) {
            String auth = HttpRequestRules.basicAuthHeader(opts.basicAuthUser(), opts.basicAuthPass());
            if (auth != null) trySetHeader(rb, "Authorization", auth);
            if (sendBody) trySetHeader(rb, "Content-Type", opts.effectiveContentType());
            // Kullanıcı başlıkları EN SON (Sayfa Hızı ile aynı): aynı adı taşıyan başlık Authorization / Content-Type /
            // User-Agent'ı bilinçli ezebilsin (özel jeton şeması kullanan iç servisler).
            for (Map.Entry<String, String> h : HttpRequestRules.parseHeaders(opts.headers()).entrySet()) {
                trySetHeader(rb, h.getKey(), h.getValue());
            }
        }
        switch (method) {
            case "HEAD" -> rb.method("HEAD", HttpRequest.BodyPublishers.noBody());
            case "POST" -> rb.POST(sendBody
                    ? HttpRequest.BodyPublishers.ofString(opts.body(), java.nio.charset.StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody());
            default     -> rb.GET();
        }
        return rb.build();
    }

    /** Kısıtlı/geçersiz başlığı (HttpClient reddederse) sessizce atlar — kayıt anında zaten doğrulandı. */
    private static void trySetHeader(HttpRequest.Builder rb, String name, String value) {
        try { rb.setHeader(name, value); }
        catch (IllegalArgumentException ignore) { /* kısıtlı/geçersiz — atla */ }
    }

    /**
     * JSON doğrulaması için yanıt gövdesi tamponu. Yönlendirme zincirinde her hop üzerine yazar — son yanıtın gövdesi
     * kalır. Tavan aşılırsa {@code truncated}; süre dolarsa {@code failure} (gövde doğrulanamadı → DOWN).
     */
    static final class BodyCapture {
        byte[] bytes;
        boolean truncated;
        String failure;

        void read(java.io.InputStream body, long timeoutMs) throws java.io.IOException {
            bytes = null; truncated = false; failure = null;
            try (java.io.InputStream is = com.sitemonitor.util.HttpBodies.withDeadline(body, timeoutMs, "HTTP")) {
                byte[] b = is.readNBytes(HttpRequestRules.MAX_RESPONSE_BYTES + 1);
                if (b.length > HttpRequestRules.MAX_RESPONSE_BYTES) {
                    truncated = true;
                    bytes = java.util.Arrays.copyOf(b, HttpRequestRules.MAX_RESPONSE_BYTES);
                } else {
                    bytes = b;
                }
            } catch (com.sitemonitor.util.HttpBodies.BodyDeadlineException te) {
                failure = JsonAssertion.FAIL_PREFIX + "yanıt gövdesi " + timeoutMs + " ms içinde tamamen okunamadı";
            }
        }
    }

    /** Per-host pinned client: SNI=host, yerleşik endpoint-identification kapalı (URI=IP). Güven paylaşılan ctx'ten. */
    private HttpClient pinnedClient(String host, boolean verifySsl) {
        SSLContext ctx = verifySsl ? strictCtx : trustAllCtx;
        if (ctx == null) return null;
        String key = host + "|" + verifySsl;
        // synchronized: LinkedHashMap (LRU) thread-safe değil; computeIfAbsent + removeEldestEntry atomik olmalı.
        HttpClient client;
        synchronized (pinnedClients) {
            client = pinnedClients.computeIfAbsent(key, k -> {
                SSLParameters sp = ctx.getDefaultSSLParameters();
                sp.setServerNames(List.of(new SNIHostName(host)));
                sp.setEndpointIdentificationAlgorithm(null);
                return HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .sslContext(ctx)
                        .sslParameters(sp)
                        .build();
            });
        }
        closeEvictedClients();   // D3: kapatma kilit DIŞINDA
        return client;
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
