package com.certmonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy-aware RDAP istemcisi (alan adı süre bitişi + registrar + EPP status + nameserver).
 * IANA bootstrap (data.iana.org/rdap/dns.json) ile TLD→RDAP sunucusu bulunur; bulunamazsa/hata olursa
 * rdap.org aggregator'a düşer. Kurumsal DMZ proxy'si {@code cert.monitor.proxy.*} ile onurlandırılır
 * (HTTPS CONNECT tüneli — {@code java.net.http.HttpClient} otomatik). 429 → exponential backoff.
 *
 * Dönen ham bilgi (tarihler String; days/status HESAPLAMASI DomainCheckerService'te merkezileşir):
 * {@code {source:"RDAP", expiry_date?, registration_date?, last_changed?, registrar?, status_codes:List,
 * nameservers:List, error?}}. Hata/veri yok → {@code error} dolu, source "NONE".
 */
@Slf4j
@Service
public class RdapDomainClient {

    private final AppSettingsService appSettings;
    private final PublicSuffixService psl;
    private final TrustEvaluator trustEvaluator;

    public RdapDomainClient(AppSettingsService appSettings, PublicSuffixService psl, TrustEvaluator trustEvaluator) {
        this.appSettings = appSettings;
        this.psl = psl;
        this.trustEvaluator = trustEvaluator;
    }

    @Value("${cert.monitor.proxy.host:}")     private String proxyHost;
    @Value("${cert.monitor.proxy.port:0}")    private int    proxyPort;
    @Value("${cert.monitor.proxy.no-proxy:}") private String noProxyList;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient direct;
    private HttpClient proxied;

    private static final long BOOTSTRAP_TTL_MS = 24L * 60 * 60 * 1000L;
    /** Negatif-cache: bootstrap fetch'i (başarılı/başarısız fark etmez) en fazla bu sıklıkta dene.
     *  İlk açılışta bootstrap başarısızsa HER domain lookup'ının 8s timeout'u tekrar tekrar yemesini önler. */
    private static final long BOOTSTRAP_RETRY_MS = 5L * 60 * 1000L;
    private volatile Map<String, String> bootstrap;   // tld -> rdap base (trailing '/')
    private volatile long bootstrapFetchedAt;         // son BAŞARILI fetch
    private volatile long bootstrapAttemptedAt;       // son DENEME (başarılı/başarısız) — negatif-cache

    // ── Veri kaynağı durum izleme (Sistem Sağlığı kartı için) ──────────────────
    private volatile Instant lastSuccessAt;
    private volatile Instant lastErrorAt;
    private volatile String  lastReason;
    private volatile boolean lastUsedFallback;
    private volatile boolean bootstrapLoaded;
    private volatile int     bootstrapTldCount;

    @PostConstruct
    public void init() {
        Duration ct = Duration.ofSeconds(5);
        // Kurumsal TLS-araya-giren proxy, RDAP sunucusunun sertifikasını cacerts'te olmayan bir iç Root CA ile
        // yeniden imzalar → varsayılan güven "PKIX path building failed" ile patlar. Çözüm: cacerts + admin'in
        // Genel Ayarlar'da girdiği kurumsal CA paketiyle doğrulayan SSLContext (TrustEvaluator, canlı reload).
        SSLContext ssl = trustEvaluator.outboundSslContext();
        direct = newClient(ct, null, ssl);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            proxied = newClient(ct, ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)), ssl);
            log.info("RDAP istemcisi proxy üzerinden: {}:{}", proxyHost, proxyPort);
        } else {
            proxied = direct;
        }
    }

    private static HttpClient newClient(Duration ct, ProxySelector proxy, SSLContext ssl) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(ct).followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null) b.proxy(proxy);
        if (ssl != null) b.sslContext(ssl);
        return b.build();
    }

    private HttpClient clientFor(String host) {
        if (proxied == direct) return direct;
        return shouldBypass(host) ? direct : proxied;
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

    /** Kayıtlı domain için RDAP sorgusu. registrableDomain zaten eTLD+1 (PSL) olmalı. */
    public Map<String, Object> lookup(String registrableDomain) {
        if (registrableDomain == null || registrableDomain.isBlank()) return err("invalid domain");
        String tld = psl.tldOf(registrableDomain);
        Map<String, Object> res = null;

        boolean viaFallback = false;

        String base = bootstrapBase(tld);   // IANA bootstrap
        if (base != null) res = tryRdap(base + "domain/" + enc(registrableDomain), registrableDomain);

        if (res == null || res.get("error") != null) {   // rdap.org aggregator fallback
            String fb = appSettings.getString("cert.monitor.domain.rdap-fallback-url", "https://rdap.org/domain/");
            Map<String, Object> res2 = tryRdap(fb + enc(registrableDomain), registrableDomain);
            if (res2.get("error") == null) { res = res2; viaFallback = true; }
            else if (res == null) res = res2;
        }
        if (res == null) res = err("no rdap");
        recordOutcome(res, viaFallback);
        return res;
    }

    /** Son lookup sonucunu durum kartı için kaydet (başarı/hata + kaynak: primary vs fallback). */
    private void recordOutcome(Map<String, Object> res, boolean viaFallback) {
        if (res.get("error") == null) {
            lastSuccessAt = Instant.now();
            lastUsedFallback = viaFallback;
            lastReason = null;
        } else {
            lastErrorAt = Instant.now();
            lastReason = String.valueOf(res.get("error"));
        }
    }

    /**
     * Domain-expiry veri kaynağının anlık durumu (Sistem Sağlığı kartı).
     * source: RDAP (birincil OK) / FALLBACK (rdap.org ile OK, birincil bozuk) / NONE (son sorgu başarısız) / IDLE (henüz sorgu yok).
     */
    public Map<String, Object> getSourceStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        Instant ok = lastSuccessAt, er = lastErrorAt;
        boolean lastWasSuccess = ok != null && (er == null || ok.isAfter(er));
        String source; boolean alarm;
        if (lastWasSuccess) {
            source = lastUsedFallback ? "FALLBACK" : "RDAP";
            alarm  = lastUsedFallback;               // fallback çalışıyor ama birincil/bootstrap bozuk → uyarı
        } else if (er != null) {
            source = "NONE"; alarm = true;           // son sorgu başarısız
        } else {
            source = "IDLE"; alarm = false;          // henüz sorgu koşmadı
        }
        m.put("source", source);
        m.put("alarm", alarm);
        m.put("reason", lastWasSuccess ? null : lastReason);
        m.put("last_success", ok != null ? ok.toString() : null);
        m.put("last_error", er != null ? er.toString() : null);
        m.put("bootstrap_loaded", bootstrapLoaded);
        m.put("bootstrap_tld_count", bootstrapTldCount);
        return m;
    }

    /** Tek bir RDAP URL'sini dener; 429 → exponential backoff, geçici hata → 1 retry. */
    private Map<String, Object> tryRdap(String url, String domain) {
        String host; try { host = URI.create(url).getHost(); } catch (Exception e) { return err("bad url"); }
        int attempts = 0; long backoff = 1000;
        while (true) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(6))
                        .header("Accept", "application/rdap+json")
                        .header("User-Agent", "CertMonitor-DomainMonitor/1.0")
                        .GET().build();
                HttpResponse<String> resp = clientFor(host).send(req, HttpResponse.BodyHandlers.ofString());
                int sc = resp.statusCode();
                if (sc == 429 && attempts < 2) { attempts++; sleep(backoff); backoff *= 2; continue; }
                if (sc != 200) return err("rdap http " + sc);
                return parse(resp.body(), domain);
            } catch (Exception e) {
                if (attempts < 1) { attempts++; sleep(backoff); backoff *= 2; continue; }
                log.debug("RDAP {} başarısız: {}", domain, e.getMessage());
                return err(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
    }

    private Map<String, Object> parse(String body, String domain) {
        try {
            JsonNode root = mapper.readTree(body);
            String expiry = null, registration = null, lastChanged = null;
            JsonNode events = root.get("events");
            if (events != null && events.isArray()) {
                for (JsonNode ev : events) {
                    String a = ev.path("eventAction").asText("");
                    String d = ev.path("eventDate").asText(null);
                    if ("expiration".equalsIgnoreCase(a)) expiry = d;
                    else if ("registration".equalsIgnoreCase(a)) registration = d;
                    else if ("last changed".equalsIgnoreCase(a)) lastChanged = d;
                }
            }
            List<String> status = new ArrayList<>();
            JsonNode st = root.get("status");
            if (st != null && st.isArray()) for (JsonNode s : st) status.add(s.asText());
            List<String> ns = new ArrayList<>();
            JsonNode nsArr = root.get("nameservers");
            if (nsArr != null && nsArr.isArray()) for (JsonNode n : nsArr) {
                String name = n.path("ldhName").asText(null);
                if (name != null && !name.isBlank()) ns.add(name.toLowerCase(Locale.ROOT));
            }
            String registrar = extractRegistrar(root);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", "RDAP");
            out.put("expiry_date", expiry);
            out.put("registration_date", registration);
            out.put("last_changed", lastChanged);
            out.put("registrar", registrar);
            out.put("status_codes", status);
            out.put("nameservers", ns);
            return out;
        } catch (Exception e) {
            return err("rdap parse: " + e.getMessage());
        }
    }

    /** entities[] içinde roles=registrar olanın vCard "fn" değerini çıkarır. */
    private static String extractRegistrar(JsonNode root) {
        JsonNode entities = root.get("entities");
        if (entities == null || !entities.isArray()) return null;
        for (JsonNode ent : entities) {
            JsonNode roles = ent.get("roles");
            boolean isReg = false;
            if (roles != null && roles.isArray()) for (JsonNode r : roles) if ("registrar".equalsIgnoreCase(r.asText())) isReg = true;
            if (!isReg) continue;
            JsonNode vcard = ent.get("vcardArray");
            if (vcard != null && vcard.isArray() && vcard.size() > 1 && vcard.get(1).isArray()) {
                for (JsonNode item : vcard.get(1)) {
                    if (item.isArray() && item.size() >= 4 && "fn".equalsIgnoreCase(item.get(0).asText())) {
                        String fn = item.get(3).asText(null);
                        if (fn != null && !fn.isBlank()) return fn;
                    }
                }
            }
            String handle = ent.path("handle").asText(null);
            if (handle != null && !handle.isBlank()) return handle;
        }
        return null;
    }

    /** IANA bootstrap'tan TLD'nin RDAP base URL'si (trailing '/'), yoksa null. Registry 24s cache'li;
     *  başarısızlıkta 5dk negatif-cache ile her lookup'ta yeniden fetch edilmez (8s timeout storm'u önlenir). */
    private String bootstrapBase(String tld) {
        if (tld == null) return null;
        Map<String, String> b = bootstrap;
        long now = System.currentTimeMillis();
        boolean fresh = b != null && (now - bootstrapFetchedAt) <= BOOTSTRAP_TTL_MS;
        if (!fresh && (now - bootstrapAttemptedAt) >= BOOTSTRAP_RETRY_MS) {
            Map<String, String> fetched = fetchBootstrap();          // bootstrapAttemptedAt'i set eder
            if (fetched != null && !fetched.isEmpty()) {
                bootstrap = fetched; bootstrapFetchedAt = now; b = fetched;
            } else {
                b = bootstrap;   // fetch başarısız → eski cache (varsa) korunur, yoksa null (fallback'e düşer)
            }
        }
        return b != null ? b.get(tld) : null;
    }

    private Map<String, String> fetchBootstrap() {
        bootstrapAttemptedAt = System.currentTimeMillis();   // negatif-cache: başarı/başarısızlık fark etmez
        String url = appSettings.getString("cert.monitor.domain.rdap-bootstrap-url", "https://data.iana.org/rdap/dns.json");
        try {
            String host = URI.create(url).getHost();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "CertMonitor-DomainMonitor/1.0").GET().build();
            HttpResponse<String> resp = clientFor(host).send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) { log.warn("IANA RDAP bootstrap http {}", resp.statusCode()); return null; }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode services = root.get("services");
            Map<String, String> map = new ConcurrentHashMap<>();
            if (services != null && services.isArray()) {
                for (JsonNode svc : services) {
                    if (!svc.isArray() || svc.size() < 2) continue;
                    JsonNode tlds = svc.get(0), urls = svc.get(1);
                    if (!urls.isArray() || urls.isEmpty()) continue;
                    String base = urls.get(0).asText();
                    if (!base.endsWith("/")) base = base + "/";
                    for (JsonNode t : tlds) map.put(t.asText().toLowerCase(Locale.ROOT), base);
                }
            }
            log.info("IANA RDAP bootstrap yüklendi: {} TLD", map.size());
            bootstrapLoaded = true;
            bootstrapTldCount = map.size();
            return map;
        } catch (Exception e) {
            log.warn("IANA RDAP bootstrap alınamadı ({}), rdap.org fallback kullanılacak: {}", url, e.getMessage());
            return null;   // başarısız → çağıran eski cache'i (varsa) korur; yoksa doğrudan fallback
        }
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "NONE");
        out.put("error", msg != null ? msg : "unknown");
        return out;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
