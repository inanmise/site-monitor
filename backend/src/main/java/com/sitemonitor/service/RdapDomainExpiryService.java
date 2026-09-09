package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
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
    private final TrustEvaluator trustEvaluator;
    private final CaAutoPinService caAutoPinService;
    private final SsrfGuard ssrfGuard;

    // Vekil ayarları — RdapDomainClient ile AYNI anahtarlar. Bu sınıfta HİÇ YOKTU: aynı hedeflere
    // (iana / rdap.org) giden kardeş istemci vekil üzerinden çıkarken bu servis doğrudan çıkıyor ve
    // kurumsal ağda her sorgu "HTTP connect timed out" ile düşüyordu. Sonuç sessizdi: days=null →
    // SchedulerService.evalHttpDomain "up" yazıyor, yani alan adı bitiş hatırlatıcısı hiç uyarmıyordu.
    @Value("${site.monitor.proxy.host:}")     private String proxyHost;
    @Value("${site.monitor.proxy.port:0}")    private int    proxyPort;
    @Value("${site.monitor.proxy.user:}")     private String proxyUser;
    @Value("${site.monitor.proxy.pass:}")     private String proxyPass;
    @Value("${site.monitor.proxy.no-proxy:}") private String noProxyList;

    private HttpClient proxied;

    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1000L;   // 12 saat
    private static final int  MAX_CACHE_ENTRIES = 5_000;             // sert üst sınır (heap koruması)
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private HttpClient http;

    // Kurumsal MITM-proxy'nin yeniden imzaladığı RDAP sertifikası → cacerts + admin kurumsal CA paketi +
    // otomatik pinlenmiş CA (CaAutoPinService). @PostConstruct: bean hazır olur.
    //
    // DÜZELTİLDİ: burada eskiden "RdapDomainClient ile aynı hedef hostlar olduğundan pinler oradan
    // gelir, ayrıca pin tetikleyici gerekmez" yazıyordu. Varsayım yanlıştı — bootstrap data.iana.org'a,
    // alan sorgusu ise TLD'nin YETKİLİ RDAP sunucusuna gider. Tetikleyici artık sendOnce'ta.
    @PostConstruct
    void init() {
        SSLContext ssl = trustEvaluator.pinAwareOutboundSslContext(
                caAutoPinService::trustManagerForHost, caAutoPinService::recordTrustFailure);
        this.http = newClient(ssl, null, null);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            java.net.Authenticator auth =
                    ProxyAuthSupport.proxyAuthenticatorOrNull(proxyUser, proxyPass, log, "RDAP expiry");
            this.proxied = newClient(ssl, ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)), auth);
            log.info("RDAP expiry istemcisi proxy üzerinden: {}:{} (kimlik: {})", proxyHost, proxyPort,
                    auth != null ? "Basic/" + proxyUser : "anonim");
        } else {
            this.proxied = this.http;
            // RdapDomainClient ile aynı görünürlük kuralı: sessiz düşüş 2026-08 prod kesintisinde
            // teşhisi geciktirmişti. Bu servis için düşüşün sonucu ayrıca SESSİZ (days=null → "up").
            log.warn("RDAP expiry istemcisi DOĞRUDAN çıkışta — proxy tanımsız (HTTP_PROXY_HOST boş). "
                    + "Kurumsal ağda dış RDAP erişimi firewall'a takılır ve alan adı bitiş "
                    + "hatırlatıcısı sessizce çalışmaz.");
        }
    }

    private HttpClient newClient(SSLContext ssl, ProxySelector proxy, java.net.Authenticator auth) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Redirect.NEVER: hop'lar asagida ELLE takip edilir, her biri SsrfGuard'dan gecer.
                .followRedirects(HttpClient.Redirect.NEVER);
        if (ssl != null) b.sslContext(ssl);
        if (proxy != null) b.proxy(proxy);
        if (auth != null) b.authenticator(auth);
        return b.build();
    }

    /** Hedefe göre istemci: NO_PROXY eşleşen host doğrudan, diğerleri vekil üzerinden. */
    private HttpClient clientFor(String host) {
        if (proxied == http) return http;
        return shouldBypass(host) ? http : proxied;
    }

    /** NO_PROXY eşleşmesi — RdapDomainClient/TrWebWhoisClient ile aynı kural. */
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
        if (c != null) {
            if ((System.currentTimeMillis() - c.fetchedAtMs) < CACHE_TTL_MS) return c.result;
            cache.remove(domain);   // süresi dolan girdi bekletilmez (kaldırılan monitörlerin anahtarları birikmesin)
        }

        Map<String, Object> res = query(domain);
        // GeoIpService deseni: sert üst sınır — heap büyümesine karşı basit self-heal (nadiren tetiklenir,
        // cache sweep'lerle hızla yeniden dolar). Uzun uptime'da monitör churn'ü sınırsız anahtar bırakmasın.
        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        cache.put(domain, new Cached(res, System.currentTimeMillis()));
        return res;
    }

    /**
     * Yonlendirmeleri ELLE takip eden gonderim — her hop {@link SsrfGuard}'dan gecer.
     *
     * <p>Bu istemci {@code Redirect.NORMAL} kullaniyordu: zincir kutuphane icinde takip ediliyor,
     * ara hop'lar uygulamaya gorunmuyordu. Hedef ({@code site.monitor.http.rdap-base-url})
     * YONETICI TARAFINDAN AYARLANABILIR ve DIS bir sunucu: ele gecmis bir RDAP ucu
     * {@code 302 Location: http://169.254.169.254/} ile pod'u ic aga yonlendirebiliyordu.
     * Ayni sinif KeywordChecker/HttpChecker/HstsDiagnostics'te duzeltilmisti; bu servis atlanmis.
     *
     * <p>{@code UnresolvableHostException} TOLERE EDILIR (proxy/split-DNS) —
     * {@code ChainValidationService.guardTarget} ile ayni hosgoru.
     */
    private HttpResponse<java.io.InputStream> sendFollowingSafely(HttpRequest req)
            throws java.io.IOException, InterruptedException {
        URI current = req.uri();
        for (int hop = 0; hop <= SafeRedirect.MAX_HOPS; hop++) {
            String host = current.getHost();
            if (host == null || host.isBlank())
                throw new SsrfGuard.BlockedException("gecersiz RDAP hedefi: " + current);
            try {
                ssrfGuard.validate(host);
            } catch (SsrfGuard.UnresolvableHostException ue) {
                log.debug("RDAP: {} yerelde cozulemedi, baglanti yine denenecek (proxy senaryosu)", host);
            }
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(current);
            req.timeout().ifPresent(b::timeout);
            req.headers().map().forEach((n, vs) -> vs.forEach(v -> b.header(n, v)));
            int port = current.getPort() == -1 ? 443 : current.getPort();
            HttpResponse<java.io.InputStream> resp = sendOnce(b.GET().build(), host, port);
            if (!SafeRedirect.isRedirect(resp.statusCode())) return resp;
            URI next = SafeRedirect.nextHop(current, resp.headers().firstValue("location").orElse(null));
            if (next == null) return resp;   // takip edilemez sema/host -> 3xx oldugu gibi doner
            try (java.io.InputStream is = resp.body()) { is.readNBytes(4096); } catch (Exception ignore) { /* baglanti iadesi */ }
            current = next;
        }
        throw new java.io.IOException("cok fazla yonlendirme (" + SafeRedirect.MAX_HOPS + " hop asildi)");
    }

    /**
     * Tek hop — PKIX guven hatasinda hedef host'un CA'si pinlenir ve istek BIR kez tekrarlanir.
     *
     * <p>{@code RdapDomainClient.sendOnce} ile AYNI desen ve ayni gerekcesi var; bu servis
     * atlanmisti. Atlanmanin dayanagi yukaridaki (artik duzeltilmis) yorumdu: "RdapDomainClient ile
     * ayni hedef hostlar, pinler oradan gelir". Varsayim YANLISTI ve uretimde soyle gorundu:
     *
     * <pre>
     * RdapDomainClient        : IANA RDAP bootstrap yuklendi: 1200 TLD          &lt;- basarili
     * RdapDomainExpiryService : RDAP lookup failed for &lt;alan&gt;: (certificate_unknown)
     *                           PKIX path building failed                        &lt;- kalici hata
     * </pre>
     *
     * Bootstrap {@code data.iana.org}'a gider; alan sorgusu ise TLD'nin YETKILI RDAP sunucusuna
     * (ornegin registry'nin kendi hostu) gider. O host hic pinlenmemis oldugu icin kurumsal
     * TLS-araya-giren proxy'nin sertifikasi dogrulanamiyor ve alan adi sure-bitisi kontrolu
     * SESSIZCE (log.debug) calismiyordu — kullanici alarm beklerken hicbir sey olmuyordu.
     *
     * <p>Pin-farkindali SSLContext'e sahip olmak YETMEZ: o yalnizca ZATEN pinlenmis CA'lari kabul
     * eder, ilk karsilasmada pini KENDI olusturmaz. Tetikleyici burasidir.
     */
    private HttpResponse<java.io.InputStream> sendOnce(HttpRequest req, String host, int port)
            throws java.io.IOException, InterruptedException {
        try {
            return clientFor(host).send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.io.IOException e) {
            if (CaAutoPinService.isTrustFailure(e) && caAutoPinService.pinFromServer(host, port, "rdap")) {
                log.info("RDAP expiry auto-pin sonrasi tekrar deneniyor: {}", host);
                return clientFor(host).send(req, HttpResponse.BodyHandlers.ofInputStream());
            }
            throw e;
        }
    }

    private Map<String, Object> query(String domain) {
        String base = appSettings.getString("site.monitor.http.rdap-base-url", "https://rdap.org/domain/");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + URLEncoder.encode(domain, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/rdap+json")
                    .header("User-Agent", "SiteMonitor-HttpMonitor/1.0")
                    .GET().build();
            // TAVANLI okuma: ofString() tavansizdir ve RDAP hedefi ayarlanabilir bir adres —
            // dev bir govde tek-pod uretimi OOM ile dusururdu. Tavan asilirsa acik hata atilir,
            // sessizce kirpilmaz (kirpik JSON "gecersiz yanit" gibi gorunup asil nedeni gizlerdi).
            HttpResponse<java.io.InputStream> resp = sendFollowingSafely(req);
            if (resp.statusCode() != 200) return unknown(domain, "rdap http " + resp.statusCode());
            String rawBody = com.sitemonitor.util.HttpBodies.readCapped(resp, 1_000_000, "RDAP");
            JsonNode root = mapper.readTree(rawBody);
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
            // GÜVEN hatası ile SIRADAN hata ayrılır. RDAP sorgusu pek çok normal sebeple düşer
            // (TLD desteklemiyor, hız sınırı, geçici ağ) — hepsini WARN yapmak gürültü olurdu.
            // Ama auto-pin'den SONRA da süren bir PKIX hatası KALICI bir yapılandırma sorunudur:
            // alan adı süre-bitişi kontrolü tümüyle çalışmaz ve kullanıcı beklediği alarmı hiç almaz.
            // DEBUG'da bırakıldığı için üretimde (INFO) tamamen görünmezdi.
            if (CaAutoPinService.isTrustFailure(e)) {
                log.warn("RDAP güven hatası ({}): {} — alan adı süre-bitişi kontrolü ÇALIŞMIYOR. "
                        + "Kurumsal CA paketi (site.monitor.trust.ca-bundle-pem) veya proxy/NO_PROXY "
                        + "ayarını denetleyin.", domain, e.getMessage());
            } else {
                log.debug("RDAP lookup failed for {}: {}", domain, e.getMessage());
            }
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
            // ChronoUnit.DAYS sıfıra doğru kırpar — dolalı <24 saat olmuş domain 0 gün görünüyordu;
            // DomainCheckerService.daysUntil (D5) ile aynı floorDiv kuralı: negatif korunur.
            return (int) Math.floorDiv(when.toEpochMilli() - Instant.now().toEpochMilli(), 86_400_000L);
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
