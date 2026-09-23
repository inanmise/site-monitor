package com.sitemonitor.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * .tr (TRABIS) alan adı WHOIS'ini çeker. Kaynaklar {@code site.monitor.domain.tr-web-whois-providers} sırasına
 * göre denenir (vars. "isimtescil,trabis,trabis43"); ilk parse edilebilir yanıt kazanır ve HANGİ kaynağın
 * yanıtladığı {@link Fetched#provider()} ile döner (domain sorgulama kartı bunu gösterir):
 * <ul>
 *   <li><b>isimtescil</b> — HTTPS düz {@code GET ...?domainname=<d>} (token/oturum yok; Akbank domain'lerinin registrar'ı İHS).</li>
 *   <li><b>trabis</b> — HTTPS resmi BTK: {@code GET /whois} (gizli Laravel {@code _token} + oturum çerezi) →
 *       {@code POST /search-domain} → 302 → sonuç sayfası.</li>
 *   <li><b>trabis43</b> — ham TCP/43 WHOIS ({@code whois.trabis.gov.tr}); kurumsal egress'te genelde kapalı
 *       (CONNECT_TIMEOUT) — son çare, port-43 açık ortamda çalışır.</li>
 * </ul>
 * Üç kaynak da <b>birebir aynı</b> WHOIS metnini döndürür (doğrulandı); yalnız taşıma farklı. HTML yanıtlar
 * {@link #extractWhois} ile temizlenir, üçü de aynı {@link TrWhoisParser}'a verilir. HTTPS için kurumsal MITM
 * proxy'nin yeniden imzaladığı sertifika {@link CaAutoPinService} (TOFU) ile pinlenir — {@link RdapDomainClient}
 * ile aynı güven zinciri (trust-all YOK). HTTPS/443 kurumsal proxy'den geçer; port-43 geçmez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrWebWhoisClient {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SiteMonitor/1.0";

    private final AppSettingsService appSettings;
    private final TrustEvaluator trustEvaluator;
    private final CaAutoPinService caAutoPinService;
    private final SsrfGuard ssrfGuard;

    @Value("${site.monitor.proxy.host:}")     private String proxyHost;
    @Value("${site.monitor.proxy.port:0}")    private int    proxyPort;
    @Value("${site.monitor.proxy.user:}")     private String proxyUser;
    @Value("${site.monitor.proxy.pass:}")     private String proxyPass;
    @Value("${site.monitor.proxy.no-proxy:}") private String noProxyList;

    private SSLContext ssl;
    private ProxySelector proxySelector;   // null → doğrudan (proxy yok)
    private java.net.Authenticator proxyAuth;   // null → anonim proxy (bugünkü prod)
    // Paylaşılan, cookie'siz client'lar (NORMAL redirect) — isimtescil GET yolu bunları yeniden kullanır
    // (per-call HttpClient = selector-thread/pool churn; leak analizi #1). Proxy yoksa proxiedClient == directClient.
    private HttpClient directClient;
    private HttpClient proxiedClient;

    @PostConstruct
    void init() {
        this.ssl = trustEvaluator.pinAwareOutboundSslContext(
                caAutoPinService::trustManagerForHost, (h, prt) -> caAutoPinService.recordTrustFailure("tr-whois", h, prt));
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            this.proxySelector = ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort));
            this.proxyAuth = ProxyAuthSupport.proxyAuthenticatorOrNull(proxyUser, proxyPass, log, ".tr web-whois");
            log.info(".tr web-whois istemcisi proxy üzerinden: {}:{} (kimlik: {})", proxyHost, proxyPort,
                    proxyAuth != null ? "Basic/" + proxyUser : "anonim");
        } else {
            // RdapDomainClient ile aynı görünürlük kuralı (2026-08 prod: proxy'siz sessiz düşüş).
            log.warn(".tr web-whois istemcisi DOĞRUDAN çıkışta — proxy tanımsız (HTTP_PROXY_HOST boş).");
        }
        // Redirect.NEVER: NORMAL zinciri kutuphane icinde takip ediyordu ve ara hop'lar
        // uygulamaya gorunmuyordu — hedefler ({@code isimtescil-whois-url}/{@code trabis-whois-url})
        // YONETICI TARAFINDAN AYARLANABILIR dis adresler. Hop'lar artik sendFollowingSafely
        // icinde elle takip edilir ve her biri SsrfGuard'dan gecer.
        this.directClient  = newClient(null, HttpClient.Redirect.NEVER, false);
        this.proxiedClient = (proxySelector != null) ? newClient(null, HttpClient.Redirect.NEVER, true) : directClient;
    }

    @PreDestroy
    void close() {
        if (directClient != null) directClient.close();
        if (proxiedClient != null && proxiedClient != directClient) proxiedClient.close();
    }

    public boolean enabled() {
        return appSettings.getBoolean("site.monitor.domain.tr-web-whois-enabled", true);
    }

    /** Ham WHOIS metni + onu döndüren kaynağın anahtarı (isimtescil / trabis / trabis43). */
    public record Fetched(String raw, String provider) {}

    /**
     * Kayıt edilebilir .tr domain'i için WHOIS'i çeker (sağlayıcılar sırayla; ilk parse edilebilir kazanır).
     * Kazanan kaynağın anahtarını da taşır ({@link Fetched#provider()}). Hiçbiri sonuç vermezse {@code null}.
     * Ağ hataları yutulur (log.debug).
     */
    public Fetched fetch(String registrableDomain) {
        if (!enabled() || registrableDomain == null || registrableDomain.isBlank()) return null;
        for (String p : appSettings.getCsv("site.monitor.domain.tr-web-whois-providers", "isimtescil,trabis,trabis43")) {
            String provider = p.trim().toLowerCase(Locale.ROOT);
            if (provider.isEmpty()) continue;
            try {
                String raw = switch (provider) {
                    case "isimtescil" -> fetchIsimtescil(registrableDomain);
                    case "trabis"     -> fetchTrabis(registrableDomain);
                    case "trabis43"   -> fetchTrabis43(registrableDomain);
                    default           -> null;
                };
                if (raw != null && raw.toLowerCase(Locale.ROOT).contains("domain name")) {
                    log.debug(".tr whois {} → OK ({})", provider, registrableDomain);
                    return new Fetched(raw, provider);
                }
            } catch (Exception e) {
                log.debug(".tr whois {} başarısız ({}): {}", provider, registrableDomain, e.getMessage());
            }
        }
        return null;
    }

    /** {@link #fetch} — yalnız ham metin (kaynak gerekmeyen çağrılar için). */
    public String fetchRaw(String registrableDomain) {
        Fetched f = fetch(registrableDomain);
        return f == null ? null : f.raw();
    }

    // ── Sağlayıcılar ───────────────────────────────────────────────────────────

    /** isimtescil.net — düz GET, token/oturum yok. */
    private String fetchIsimtescil(String domain) throws Exception {
        String base = appSettings.getString("site.monitor.domain.isimtescil-whois-url", "https://www.isimtescil.net/whois");
        URI uri = URI.create(base + (base.contains("?") ? "&" : "?") + "domainname=" + enc(domain));
        HttpClient client = sharedClient(hostOf(uri));   // paylaşılan (cookie gerekmez) — per-call client YOK
        Resp resp = sendFollowingSafely(client, uri);
        return resp.statusCode() == 200 ? extractWhois(resp.body()) : null;
    }

    /** TRABIS (BTK) resmi web-whois — Laravel CSRF akışı: GET form (_token + çerez) → POST → 302 → sonuç. */
    private String fetchTrabis(String domain) throws Exception {
        String base = trimTrailingSlash(appSettings.getString("site.monitor.domain.trabis-whois-url", "https://www.trabis.gov.tr"));
        String host = hostOf(URI.create(base));
        boolean useProxy = proxySelector != null && !shouldBypass(host);
        // Domain başına taze oturum (çerez/token desync'i engeller); 302 elle takip edilir (POST redirect quirk'ünden kaçın).
        // try-with-resources: taze client GC beklemeden anında kapatılır → selector-thread/pool churn'ü önler (leak #1).
        try (HttpClient client = newClient(new CookieManager(null, CookiePolicy.ACCEPT_ALL), HttpClient.Redirect.NEVER, useProxy)) {
            Resp form = send(client, get(URI.create(base + "/whois")), host);
            if (form.statusCode() != 200) return null;
            String token = extractToken(form.body());
            if (token == null) return null;

            String body = "domain=" + enc(domain) + "&_token=" + enc(token);
            HttpRequest post = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/search-domain")).timeout(timeout())
                    .header("User-Agent", UA)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Referer", base + "/whois")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            Resp resp = send(client, post, host);

            String html;
            if (resp.statusCode() / 100 == 3) {                       // 302 → sonuç sayfası (aynı çerezle)
                // Ham URI.create + resolve, sema/host politikasi UYGULAMIYORDU: sonuc sayfasi
                // adresi sunucunun verdigi Location'dan geliyor, yani hedefin kontrolunde.
                // SafeRedirect http/https disi semayi ve host'suz hedefi reddeder; send() ise
                // yeni host'u SsrfGuard'dan gecirir.
                URI locUri = SafeRedirect.nextHop(URI.create(base + "/search-domain"),
                        resp.headers().firstValue("location").orElse(base + "/whois"));
                if (locUri == null) return null;
                Resp res = send(client, get(locUri), host);
                html = res.statusCode() == 200 ? res.body() : null;
            } else if (resp.statusCode() == 200) {
                html = resp.body();
            } else {
                return null;
            }
            return extractWhois(html);
        }
    }

    /** Ham TCP/43 WHOIS ({@code whois.trabis.gov.tr}) — proxy'siz doğrudan soket. Kurumsal egress'te 43 kapalıysa
     *  CONNECT_TIMEOUT ile düşer (son çare). Port-43 açık ortamda HTTPS ile birebir aynı metni döndürür. */
    private String fetchTrabis43(String domain) throws Exception {
        String host = appSettings.getString("site.monitor.domain.trabis-whois43-host", "whois.trabis.gov.tr");
        int timeoutMs = (int) timeout().toMillis();
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, 43), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            OutputStream os = sock.getOutputStream();
            os.write((domain + "\r\n").getBytes(StandardCharsets.US_ASCII));
            os.flush();
            StringBuilder sb = new StringBuilder();
            try (InputStream is = sock.getInputStream()) {
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = is.read(buf)) != -1 && total < 200_000) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    total += n;
                }
            }
            return extractWhois(sb.toString());   // BOM/başlık kırpar; "** Domain Name:"'den itibaren temiz blok
        }
    }

    // ── HTTP altyapısı (proxy + otomatik CA-pin, RdapDomainClient ile aynı desen) ──

    /** Paylaşılan (cookie'siz) client'ı host'a göre seç — proxy varsa ve host bypass listesinde değilse proxied. */
    private HttpClient sharedClient(String host) {
        return (proxySelector != null && !shouldBypass(host)) ? proxiedClient : directClient;
    }

    private HttpClient newClient(CookieManager cookies, HttpClient.Redirect redirect, boolean useProxy) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(redirect);
        if (proxySelector != null && useProxy) {
            b.proxy(proxySelector);
            if (proxyAuth != null) b.authenticator(proxyAuth);
        }
        if (ssl != null) b.sslContext(ssl);
        if (cookies != null) b.cookieHandler(cookies);
        return b.build();
    }

    private HttpRequest get(URI uri) {
        return HttpRequest.newBuilder().uri(uri).timeout(timeout()).header("User-Agent", UA).GET().build();
    }

    /** send + PKIX güven hatasında hedef host CA'sını otomatik pinle ve isteği bir kez tekrarla (RdapDomainClient deseni). */
    /**
     * Tavanli okunmus yanit. {@code HttpResponse<String>} yerine kullanilir: {@code ofString()}
     * TAVANSIZDIR ve bu istemcinin hedefleri YONETICI TARAFINDAN AYARLANABILIR
     * (rdap-bootstrap-url / tr-web-whois-providers) — yanlis ya da ele gecmis tek bir adres
     * dev bir govde donduerup tek-pod uretimi OOM ile dusurebilirdi. Erisimci adlari
     * bilerek `statusCode` ve `body`: cagiran kod aynen calisir.
     */
    record Resp(int statusCode, String body, java.net.http.HttpHeaders headers) {}

    /** web-WHOIS HTML govdesi icin tavan. Ham soket yolu zaten bayt sayaciyla kirpiyordu;
     *  eksik olan YALNIZ HTTP yoluydu. */
    private static final int MAX_BODY_BYTES = 1_000_000;

    /** Cozulemeyen host baglantiyi DURDURMAZ (proxy/split-DNS); blok kararlari aynen gecerlidir. */
    private void guard(String host) {
        if (host == null || host.isBlank())
            throw new SsrfGuard.BlockedException("gecersiz .tr web-whois hedefi");
        try {
            ssrfGuard.validate(host);
        } catch (SsrfGuard.UnresolvableHostException ue) {
            log.debug(".tr web-whois: {} yerelde cozulemedi, baglanti yine denenecek (proxy senaryosu)", host);
        }
    }

    /**
     * Yonlendirmeleri ELLE takip eden gonderim — her hop {@link SsrfGuard}'dan gecer.
     * isimtescil akisi duz GET'tir ve eskiden {@code Redirect.NORMAL} ile zinciri kutuphaneye
     * birakiyordu; ara hop'lar dogrulanmiyordu.
     */
    private Resp sendFollowingSafely(HttpClient client, URI start) throws Exception {
        URI current = start;
        for (int hop = 0; hop <= SafeRedirect.MAX_HOPS; hop++) {
            Resp resp = send(client, get(current), hostOf(current));
            if (!SafeRedirect.isRedirect(resp.statusCode())) return resp;
            URI next = SafeRedirect.nextHop(current, resp.headers().firstValue("location").orElse(null));
            if (next == null) return resp;   // takip edilemez sema/host -> 3xx oldugu gibi doner
            current = next;
        }
        throw new java.io.IOException("cok fazla yonlendirme (" + SafeRedirect.MAX_HOPS + " hop asildi)");
    }

    private Resp send(HttpClient client, HttpRequest req, String host) throws Exception {
        guard(req.uri().getHost());   // her istek: ilk hop da, elle takip edilen hop da
        try {
            return capped(client.send(req, HttpResponse.BodyHandlers.ofInputStream()));
        } catch (Exception e) {
            int port = req.uri().getPort() == -1 ? 443 : req.uri().getPort();
            if (CaAutoPinService.isTrustFailure(e) && caAutoPinService.pinFromServer(host, port, "tr-web-whois")) {
                log.info(".tr web-whois auto-pin sonrası tekrar: {}", host);
                return capped(client.send(req, HttpResponse.BodyHandlers.ofInputStream()));
            }
            throw e;
        }
    }

    private static Resp capped(HttpResponse<java.io.InputStream> r) throws java.io.IOException {
        // headers de tasinir: 302 yolunda `location` basligi okunuyor (yonlendirilen sonuc sayfasi).
        return new Resp(r.statusCode(),
                com.sitemonitor.util.HttpBodies.readCapped(r, MAX_BODY_BYTES, ".tr web-WHOIS"),
                r.headers());
    }

    private Duration timeout() {
        return Duration.ofMillis(Math.max(1000, Math.min(appSettings.getInt("site.monitor.domain.whois-timeout-ms", 6000), 30000)));
    }

    private boolean shouldBypass(String host) {
        if (noProxyList == null || noProxyList.isBlank() || host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String raw : noProxyList.split(",")) {
            String e = raw.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            if (e.startsWith(".")) e = e.substring(1);
            if (h.equals(e) || h.endsWith("." + e)) return true;
        }
        return false;
    }

    // ── Ayrıştırma yardımcıları (saf/statik — ağsız test edilebilir) ─────────────

    private static final Pattern TOKEN = Pattern.compile("name=\"_token\"[^>]*?value=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern BR    = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAG   = Pattern.compile("<[^>]+>");

    /** Laravel gizli CSRF {@code _token} değerini çıkarır. */
    static String extractToken(String html) {
        if (html == null) return null;
        Matcher m = TOKEN.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /**
     * HTML sonuç sayfasından ham WHOIS metin bloğunu çıkarır — iki düzen de desteklenir:
     * TRABIS {@code <pre>...</pre>} ve isimtescil {@code <p>...<br/>...</p>}. {@code <br>}→satır, kalan tag'ler
     * temizlenir, HTML entity'leri çözülür. "** Domain Name:" (yoksa "Domain Name:") başlangıcından
     * kapsayıcı bitişine kadar. Blok yoksa {@code null}.
     */
    static String extractWhois(String html) {
        if (html == null) return null;
        String t = BR.matcher(html).replaceAll("\n");
        int start = indexOfIgnoreCase(t, "** Domain Name:");
        if (start < 0) start = indexOfIgnoreCase(t, "Domain Name:");
        if (start < 0) return null;
        int end = indexOfIgnoreCase(t, "</pre>", start);
        if (end < 0) end = indexOfIgnoreCase(t, "</p>", start);
        if (end < 0) end = Math.min(t.length(), start + 4000);
        String block = TAG.matcher(t.substring(start, end)).replaceAll("");
        block = htmlUnescape(block).strip();
        return block.isEmpty() ? null : block;
    }

    private static int indexOfIgnoreCase(String hay, String needle) { return indexOfIgnoreCase(hay, needle, 0); }

    /** Case-insensitive index INTO the original string. Regex-based (needle'lar ASCII) — {@code toLowerCase} ile
     *  hesaplamak Türkçe "İ" (U+0130 → iki karakter) yüzünden kayan indeks döndürür; bu güvenli. */
    private static int indexOfIgnoreCase(String hay, String needle, int from) {
        int f = Math.max(0, Math.min(from, hay.length()));
        Matcher m = Pattern.compile(Pattern.quote(needle), Pattern.CASE_INSENSITIVE).matcher(hay);
        return m.find(f) ? m.start() : -1;
    }

    private static String htmlUnescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
                .replace("&nbsp;", " ");
    }

    private static String hostOf(URI uri) { return uri.getHost(); }

    private static String trimTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
