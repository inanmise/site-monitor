package com.certmonitor.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
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
 * .tr (TRABIS) alan adı WHOIS'ini <b>HTTPS web-whois</b> üzerinden çeker. Ham TCP/43 WHOIS'i kurumsal
 * egress'te kapalı olduğu için ({@link WhoisDomainClient} soketi CONNECT_TIMEOUT) .tr süre bitişini almanın
 * tek çalışan yolu budur — ve HTTPS/443, kurumsal proxy'den geçer.
 *
 * <p>Sağlayıcılar sırayla denenir ({@code cert.monitor.domain.tr-web-whois-providers}, vars. "isimtescil,trabis");
 * ilk parse edilebilir yanıt kazanır:
 * <ul>
 *   <li><b>isimtescil</b> — düz {@code GET ...?domainname=<d>} (token/oturum yok; Akbank domain'lerinin registrar'ı İHS).</li>
 *   <li><b>trabis</b> — resmi BTK: {@code GET /whois} (gizli Laravel {@code _token} + oturum çerezi) →
 *       {@code POST /search-domain} → 302 → sonuç sayfası.</li>
 * </ul>
 * Her iki sayfadan da ham WHOIS bloğu {@link #extractWhois} ile çıkarılıp aynı {@link TrWhoisParser}'a verilir
 * (port-43 ile birebir aynı metin formatı). Kurumsal MITM proxy'nin yeniden imzaladığı sertifika
 * {@link CaAutoPinService} (TOFU) ile pinlenir — {@link RdapDomainClient} ile aynı güven zinciri (trust-all YOK).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrWebWhoisClient {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CertMonitor/1.0";

    private final AppSettingsService appSettings;
    private final TrustEvaluator trustEvaluator;
    private final CaAutoPinService caAutoPinService;

    @Value("${cert.monitor.proxy.host:}")     private String proxyHost;
    @Value("${cert.monitor.proxy.port:0}")    private int    proxyPort;
    @Value("${cert.monitor.proxy.no-proxy:}") private String noProxyList;

    private SSLContext ssl;
    private ProxySelector proxySelector;   // null → doğrudan (proxy yok)

    @PostConstruct
    void init() {
        this.ssl = trustEvaluator.pinAwareOutboundSslContext(
                caAutoPinService::trustManagerForHost, caAutoPinService::recordTrustFailure);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            this.proxySelector = ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort));
            log.info(".tr web-whois istemcisi proxy üzerinden: {}:{}", proxyHost, proxyPort);
        }
    }

    public boolean enabled() {
        return appSettings.getBoolean("cert.monitor.domain.tr-web-whois-enabled", true);
    }

    /**
     * Kayıt edilebilir .tr domain'i için ham WHOIS metnini döner (sağlayıcılar sırayla denenir; ilk
     * parse edilebilir yanıt kazanır). Hiçbiri sonuç vermezse {@code null}. Ağ hataları yutulur (log.debug).
     */
    public String fetchRaw(String registrableDomain) {
        if (!enabled() || registrableDomain == null || registrableDomain.isBlank()) return null;
        for (String p : appSettings.getCsv("cert.monitor.domain.tr-web-whois-providers", "isimtescil,trabis")) {
            String provider = p.trim().toLowerCase(Locale.ROOT);
            if (provider.isEmpty()) continue;
            try {
                String raw = switch (provider) {
                    case "isimtescil" -> fetchIsimtescil(registrableDomain);
                    case "trabis"     -> fetchTrabis(registrableDomain);
                    default           -> null;
                };
                if (raw != null && raw.toLowerCase(Locale.ROOT).contains("domain name")) {
                    log.debug(".tr web-whois {} → OK ({})", provider, registrableDomain);
                    return raw;
                }
            } catch (Exception e) {
                log.debug(".tr web-whois {} başarısız ({}): {}", provider, registrableDomain, e.getMessage());
            }
        }
        return null;
    }

    // ── Sağlayıcılar ───────────────────────────────────────────────────────────

    /** isimtescil.net — düz GET, token/oturum yok. */
    private String fetchIsimtescil(String domain) throws Exception {
        String base = appSettings.getString("cert.monitor.domain.isimtescil-whois-url", "https://www.isimtescil.net/whois");
        URI uri = URI.create(base + (base.contains("?") ? "&" : "?") + "domainname=" + enc(domain));
        HttpClient client = newClient(null, HttpClient.Redirect.NORMAL, hostOf(uri));
        HttpResponse<String> resp = send(client, get(uri), hostOf(uri));
        return resp.statusCode() == 200 ? extractWhois(resp.body()) : null;
    }

    /** TRABIS (BTK) resmi web-whois — Laravel CSRF akışı: GET form (_token + çerez) → POST → 302 → sonuç. */
    private String fetchTrabis(String domain) throws Exception {
        String base = trimTrailingSlash(appSettings.getString("cert.monitor.domain.trabis-whois-url", "https://www.trabis.gov.tr"));
        String host = hostOf(URI.create(base));
        // Domain başına taze oturum (çerez/token desync'i engeller); 302 elle takip edilir (POST redirect quirk'ünden kaçın).
        HttpClient client = newClient(new CookieManager(null, CookiePolicy.ACCEPT_ALL), HttpClient.Redirect.NEVER, host);

        HttpResponse<String> form = send(client, get(URI.create(base + "/whois")), host);
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
        HttpResponse<String> resp = send(client, post, host);

        String html;
        if (resp.statusCode() / 100 == 3) {                       // 302 → sonuç sayfası (aynı çerezle)
            String loc = resp.headers().firstValue("location").orElse(base + "/whois");
            URI locUri = URI.create(loc);
            if (!locUri.isAbsolute()) locUri = URI.create(base).resolve(loc);
            HttpResponse<String> res = send(client, get(locUri), host);
            html = res.statusCode() == 200 ? res.body() : null;
        } else if (resp.statusCode() == 200) {
            html = resp.body();
        } else {
            return null;
        }
        return extractWhois(html);
    }

    // ── HTTP altyapısı (proxy + otomatik CA-pin, RdapDomainClient ile aynı desen) ──

    private HttpClient newClient(CookieManager cookies, HttpClient.Redirect redirect, String host) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(redirect);
        if (proxySelector != null && !shouldBypass(host)) b.proxy(proxySelector);
        if (ssl != null) b.sslContext(ssl);
        if (cookies != null) b.cookieHandler(cookies);
        return b.build();
    }

    private HttpRequest get(URI uri) {
        return HttpRequest.newBuilder().uri(uri).timeout(timeout()).header("User-Agent", UA).GET().build();
    }

    /** send + PKIX güven hatasında hedef host CA'sını otomatik pinle ve isteği bir kez tekrarla (RdapDomainClient deseni). */
    private HttpResponse<String> send(HttpClient client, HttpRequest req, String host) throws Exception {
        try {
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            int port = req.uri().getPort() == -1 ? 443 : req.uri().getPort();
            if (CaAutoPinService.isTrustFailure(e) && caAutoPinService.pinFromServer(host, port, "tr-web-whois")) {
                log.info(".tr web-whois auto-pin sonrası tekrar: {}", host);
                return client.send(req, HttpResponse.BodyHandlers.ofString());
            }
            throw e;
        }
    }

    private Duration timeout() {
        return Duration.ofMillis(Math.max(1000, Math.min(appSettings.getInt("cert.monitor.domain.whois-timeout-ms", 6000), 30000)));
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
