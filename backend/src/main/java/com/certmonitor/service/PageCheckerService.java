package com.certmonitor.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Sayfa Bütünlüğü kontrol motoru — bir web sayfasının KOD SEVİYESİNDE sağlıklı yüklendiğini doğrular:
 * jsoup ile HTML'i parse eder, kaynak envanterini (img/CSS/JS/link/iframe/font/favicon) çıkarır, her kaynağı
 * sınırlı eşzamanlılıkla doğrular (önce HEAD, desteklenmiyorsa GET) ve kırık / mixed content / yavaş sorunlarını
 * raporlar. HttpClient + trust-all + gövde-tavanı deseni {@link KeywordCheckerService} ile aynıdır.
 *
 * <p><b>SSRF:</b> ana sayfa, parse'tan çıkan HER kaynak ve HER redirect adımı istekten önce
 * {@link SsrfGuard#validate(String)}'ten geçer (parse'tan gelen URL'ler kullanıcı girdisi kadar güvensizdir);
 * redirect'ler MANUEL takip edilir ki her hop denetlensin (DNS-rebind / redirect-SSRF kapanır).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageCheckerService {

    /** Ana sayfa gövde okuma tavanı (OOM koruması + hash için yeterli). */
    private static final int MAX_BODY_BYTES = 2_000_000;
    /** Tek SAYFANIN doğrulanacak azami (tekil) kaynağı — tek-pod yük koruması; aşılırsa WARN + kırpılır. */
    private static final int MAX_RESOURCES_PER_CHECK = 500;
    /** Bir CRAWL genelinde toplam doğrulanacak azami kaynak — bellek + DB-insert patlamasını sınırlar (M2). */
    private static final int MAX_TOTAL_RESOURCES = 1500;
    /** Manuel redirect zinciri üst sınırı. */
    private static final int MAX_REDIRECTS = 5;
    /** Tarayıcı-uyumlu varsayılan UA (Mozilla-prefix → naif WAF/UA filtreleri 403/406 üretmez; kimlik + iletişim
     *  korunur). Admin {@code cert.monitor.page.user-agent} ile override edebilir (F4). */
    private static final String DEFAULT_UA = "Mozilla/5.0 (compatible; CertMonitor-PageCheck/1.0; +https://certmonitor)";
    /** robots.txt User-agent eşleşmesi için sabit bot token'ı (UA browser-y olsa da robots bunu tanır). */
    private static final String BOT_TOKEN = "certmonitor-pagecheck";

    private final SsrfGuard ssrfGuard;
    private final PublicSuffixService publicSuffixService;
    private final AppSettingsService appSettings;   // page.user-agent canlı okuma (F4)
    private HttpClient httpClient;
    /** Kaynak doğrulama fan-out'u için sanal-thread executor (I/O-bound; concurrency Semaphore ile sınırlanır). */
    private ExecutorService resourceExecutor;

    // ── Sonuç tipleri ────────────────────────────────────────────────────────
    public record ResourceIssue(String resourceUrl, String resourceType, String sourcePage,
                                String issueType, boolean firstParty, Integer httpStatus, Long durationMs) {}

    public record PageCheckResult(String status, boolean mainReachable, Integer httpStatus, long responseMs,
                                  int totalResources, int brokenResources, int mixedContentCount,
                                  int pagesCrawled, String contentHash, Long bodyBytes, String error,
                                  List<ResourceIssue> issues) {}

    private record FetchResult(int status, long durationMs, byte[] body, String error, boolean blocked) {}

    @PostConstruct
    public void init() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);   // redirect'leri manuel takip → her hop SSRF'den geçsin
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            b.sslContext(ssl);
        } catch (Exception e) {
            log.warn("Page checker trust-all SSL kurulamadı, varsayılan kullanılacak: {}", e.getMessage());
        }
        httpClient = b.build();
        resourceExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PreDestroy
    public void shutdown() {
        if (resourceExecutor != null) resourceExecutor.shutdownNow();
    }

    // ── Giriş noktaları ──────────────────────────────────────────────────────

    /** SINGLE_PAGE veya SITE_CRAWL — moda göre yönlendirir. {@code maxCheckSeconds} tüm kontrol için wall-clock
     *  üst sınırı (yavaş/yanıt-vermeyen hedefin scheduler/request thread'ini süresiz tutmasını engeller — H1/M1). */
    public PageCheckResult check(String url, String mode, int timeoutMs, int slowMs, int concurrency,
                                 String excludePatterns, int crawlDepth, int crawlMaxPages, int maxCheckSeconds) {
        Excludes excludes = compileExcludes(excludePatterns);
        long deadline = System.currentTimeMillis() + Math.max(5, maxCheckSeconds) * 1000L;
        if ("SITE_CRAWL".equalsIgnoreCase(mode)) {
            return crawlSite(url, timeoutMs, slowMs, clampConcurrency(concurrency), excludes,
                    Math.max(0, crawlDepth), Math.max(1, crawlMaxPages), deadline);
        }
        return checkSinglePage(url, timeoutMs, slowMs, clampConcurrency(concurrency), excludes, deadline);
    }

    /** Kaydetmeden canlı test için basit sarmalayıcı (SINGLE_PAGE, varsayılan eşikler + 60sn deadline). */
    public PageCheckResult test(String url, int timeoutMs) {
        return checkSinglePage(url, timeoutMs, 2000, 5, Excludes.EMPTY, System.currentTimeMillis() + 60_000L);
    }

    // ── SINGLE_PAGE ──────────────────────────────────────────────────────────
    private PageCheckResult checkSinglePage(String url, int timeoutMs, int slowMs, int concurrency,
                                            Excludes excludes, long deadline) {
        long start = System.currentTimeMillis();
        String rootHost = hostOf(url);
        FetchResult main = fetchFollowing(url, "GET", true, timeoutMs);
        if (main.blocked() || main.body() == null || main.status() >= 400 || main.status() == 0) {
            long ms = System.currentTimeMillis() - start;
            String err = main.error() != null ? main.error()
                    : (main.status() >= 400 ? "ana sayfa HTTP " + main.status() : "ana sayfa alınamadı");
            return new PageCheckResult("DOWN", false, main.status() == 0 ? null : main.status(), ms,
                    0, 0, 0, 1, null, null, err, List.of());
        }
        boolean pageHttps = url.toLowerCase(Locale.ROOT).startsWith("https://");
        List<Resource> resources = inventory(main.body(), url, url, rootHost, excludes);
        List<ResourceIssue> issues = verifyAll(resources, pageHttps, rootHost, timeoutMs, slowMs, concurrency, deadline);
        long ms = System.currentTimeMillis() - start;
        return summarize(issues, resources.size(), 1, main.status(), ms, sha256(main.body()),
                (long) main.body().length);
    }

    // ── SITE_CRAWL ───────────────────────────────────────────────────────────
    private PageCheckResult crawlSite(String url, int timeoutMs, int slowMs, int concurrency,
                                      Excludes excludes, int maxDepth, int maxPages, long deadline) {
        long start = System.currentTimeMillis();
        String rootHost = hostOf(url);
        boolean pageHttps = url.toLowerCase(Locale.ROOT).startsWith("https://");
        Set<String> disallow = fetchRobotsDisallow(url, timeoutMs);

        FetchResult first = fetchFollowing(url, "GET", true, timeoutMs);
        if (first.blocked() || first.body() == null || first.status() >= 400 || first.status() == 0) {
            long ms = System.currentTimeMillis() - start;
            return new PageCheckResult("DOWN", false, first.status() == 0 ? null : first.status(), ms,
                    0, 0, 0, 0, null, null,
                    first.error() != null ? first.error() : "ana sayfa alınamadı", List.of());
        }

        Deque<String[]> queue = new ArrayDeque<>();          // {url, depth}
        Set<String> visited = new HashSet<>();
        Set<String> verified = new HashSet<>();              // kaynaklar site genelinde tekilleştirilir
        queue.add(new String[]{ url, "0" });
        for (String seed : fetchSitemapSeeds(url, timeoutMs, rootHost)) {
            if (!seed.equalsIgnoreCase(url)) queue.add(new String[]{ seed, "1" });
        }

        List<ResourceIssue> allIssues = new ArrayList<>();
        String rootHash = sha256(first.body());
        long rootBytes = first.body().length;
        int pagesCrawled = 0, totalResources = 0;
        boolean capped = false;

        // Deadline (H1/M1) VE global kaynak capi (M2) — hangisi önce dolarsa crawl durur.
        while (!queue.isEmpty() && pagesCrawled < maxPages && System.currentTimeMillis() < deadline) {
            if (totalResources >= MAX_TOTAL_RESOURCES) { capped = true; break; }
            String[] node = queue.poll();
            String pageUrl = node[0];
            int depth = Integer.parseInt(node[1]);
            if (visited.contains(pageUrl) || depth > maxDepth) continue;
            if (excludes.matches(pageUrl) || isDisallowed(pageUrl, disallow)) continue;
            visited.add(pageUrl);

            FetchResult pr = pageUrl.equals(url) ? first : fetchFollowing(pageUrl, "GET", true, timeoutMs);
            if (pr.blocked() || pr.body() == null || pr.status() >= 400 || pr.status() == 0) {
                // Crawl sırasında erişilemeyen İÇ sayfa = kırık link (kaynak sayfası bir üst adımda kaydedildi)
                continue;
            }
            pagesCrawled++;
            List<Resource> resources = inventory(pr.body(), pageUrl, pageUrl, rootHost, excludes);

            // Bu sayfadaki kaynakları (site genelinde tekil) doğrula — global cap'e kadar
            List<Resource> fresh = new ArrayList<>();
            for (Resource r : resources) {
                if (totalResources + fresh.size() >= MAX_TOTAL_RESOURCES) { capped = true; break; }
                if (verified.add(r.url())) fresh.add(r);
            }
            totalResources += fresh.size();
            allIssues.addAll(verifyAll(fresh, pageHttps, rootHost, timeoutMs, slowMs, concurrency, deadline));

            // Same-origin a[href] linkleri kuyruğa (derinlik+1)
            if (depth < maxDepth) {
                for (Resource r : resources) {
                    if (!"LINK".equals(r.type())) continue;
                    if (!sameSite(hostOf(r.url()), rootHost)) continue;   // yalnız site içi (PSL: aynı kayıtlı domain)
                    String norm = stripFragment(r.url());
                    if (norm.equalsIgnoreCase(pageUrl)) continue;   // kendine link (self/fragment) — atla
                    if (!visited.contains(norm) && !excludes.matches(norm) && !isDisallowed(norm, disallow)) {
                        queue.add(new String[]{ norm, String.valueOf(depth + 1) });
                    }
                }
            }
        }
        if (capped) log.warn("Crawl {} — {} toplam-kaynak capine ulaşıldı, kalan atlandı", sanitize(url), MAX_TOTAL_RESOURCES);
        else if (System.currentTimeMillis() >= deadline) log.warn("Crawl {} — deadline'a ulaşıldı, kısmi sonuç", sanitize(url));
        else if (!queue.isEmpty()) log.debug("Crawl {} — {} sayfa limiti, {} kuyrukta bırakıldı", sanitize(url), maxPages, queue.size());

        long ms = System.currentTimeMillis() - start;
        return summarize(allIssues, totalResources, Math.max(1, pagesCrawled), first.status(), ms, rootHash, rootBytes);
    }

    // ── Kaynak envanteri (jsoup) ─────────────────────────────────────────────
    private record Resource(String url, String type, String sourcePage) {}

    private List<Resource> inventory(byte[] bytes, String baseUrl, String sourcePage, String rootHost,
                                     Excludes excludes) {
        Document doc;
        try {
            // Bayt-stream + null charset → jsoup <meta charset>/BOM'dan charset'i otomatik tespit eder (L1);
            // parse hatasında (bozuk/dev HTML) boş envanter (L2 — controller'a exception sızmaz).
            doc = Jsoup.parse(new java.io.ByteArrayInputStream(bytes), null, baseUrl);
        } catch (Exception e) {
            log.debug("HTML parse edilemedi {}: {}", sanitize(sourcePage), e.getMessage());
            return List.of();
        }
        LinkedHashMap<String, Resource> out = new LinkedHashMap<>();   // absUrl → Resource (dedup, sıra korunur)
        addAll(out, doc, "img[src]", "src", "IMG", sourcePage);
        addSrcset(out, doc, sourcePage);
        addAll(out, doc, "link[rel=stylesheet][href]", "href", "CSS", sourcePage);
        addAll(out, doc, "script[src]", "src", "JS", sourcePage);
        addAll(out, doc, "iframe[src]", "src", "IFRAME", sourcePage);
        addAll(out, doc, "link[rel~=(?i)icon][href]", "href", "FAVICON", sourcePage);
        addAll(out, doc, "link[rel=preload][as=font][href]", "href", "FONT", sourcePage);
        addAll(out, doc, "a[href]", "href", "LINK", sourcePage);
        List<Resource> list = new ArrayList<>();
        for (Resource r : out.values()) {
            if (!isHttp(r.url()) || excludes.matches(r.url())) continue;
            // Sayfanın kendisine çözülen link (a[href="#x"], href="") — gereksiz self-request (L7)
            if ("LINK".equals(r.type()) && stripFragment(r.url()).equalsIgnoreCase(sourcePage)) continue;
            list.add(r);
            if (list.size() >= MAX_RESOURCES_PER_CHECK) {
                log.warn("Sayfa {} — {} kaynak limitine ulaşıldı, kalanlar atlandı", sanitize(sourcePage), MAX_RESOURCES_PER_CHECK);
                break;
            }
        }
        return list;
    }

    private void addAll(Map<String, Resource> out, Document doc, String css, String attr, String type, String src) {
        for (Element el : doc.select(css)) {
            String abs = el.absUrl(attr);
            if (abs == null || abs.isBlank()) abs = el.attr(attr);   // parse edilemezse ham değer (mixed/broken tespiti için)
            abs = normalizeUrl(abs);
            if (abs.isBlank()) continue;
            out.putIfAbsent(abs, new Resource(abs, type, src));
        }
    }

    /** HTML'den gelen URL'de URI.create'i PATLATAN kodlanmamış ASCII karakterleri (boşluk " < > | { } ^ \ [ ] `)
     *  yüzde-kodlar — tarayıcı da böyle yapar; aksi halde host çözülemez → yanlış "kırık" (akbank
     *  'urune davet-main.jpg' vakası + tracking URL'lerindeki [ | ). '%' ve non-ASCII'ye DOKUNMAZ (Java non-ASCII'yi
     *  tolere eder; zaten-kodlanmış %XX bozulmaz). */
    private static final String URL_UNSAFE = " \"<>|{}^`\\[]";
    static String normalizeUrl(String url) {
        if (url == null) return "";
        boolean needs = false;
        for (int i = 0; i < url.length(); i++) if (URL_UNSAFE.indexOf(url.charAt(i)) >= 0) { needs = true; break; }
        if (!needs) return url;
        StringBuilder sb = new StringBuilder(url.length() + 12);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (URL_UNSAFE.indexOf(c) >= 0) sb.append('%').append(Character.forDigit((c >> 4) & 0xF, 16))
                    .append(Character.forDigit(c & 0xF, 16));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** srcset: "url 1x, url2 2w" listesindeki her aday URL. */
    private void addSrcset(Map<String, Resource> out, Document doc, String src) {
        for (Element el : doc.select("img[srcset], source[srcset]")) {
            for (String cand : el.attr("srcset").split(",")) {
                String u = cand.trim().split("\\s+")[0];
                if (u.isBlank()) continue;
                String abs = normalizeUrl(el.root().baseUri().isBlank() ? u : resolve(el.baseUri(), u));
                out.putIfAbsent(abs, new Resource(abs, "IMG", src));
            }
        }
    }

    // ── Kaynak doğrulama ─────────────────────────────────────────────────────
    private List<ResourceIssue> verifyAll(List<Resource> resources, boolean pageHttps, String rootHost,
                                          int timeoutMs, int slowMs, int concurrency, long deadline) {
        if (resources.isEmpty()) return List.of();
        Semaphore gate = new Semaphore(concurrency);
        List<CompletableFuture<ResourceIssue>> futures = new ArrayList<>();
        for (Resource r : resources) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (System.currentTimeMillis() > deadline) return null;   // deadline geçti → çalıştırma
                try {
                    gate.acquire();
                    try { return verifyOne(r, pageHttps, rootHost, timeoutMs, slowMs); }
                    finally { gate.release(); }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }, resourceExecutor));
        }
        List<ResourceIssue> issues = new ArrayList<>();
        for (CompletableFuture<ResourceIssue> f : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;   // deadline doldu → kalanları bırak (kısmi ama tutarlı sonuç)
            try {
                // Deadline'ı aşan join YAPMA — kalan future'lar sanal-thread'te düşer (her fetch'in kendi timeout'u var).
                ResourceIssue i = f.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (i != null) issues.add(i);
            } catch (java.util.concurrent.TimeoutException te) {
                break;   // deadline'a takıldı → kısmi sonuç
            } catch (Exception e) {
                /* bu future hata verdi (ExecutionException) → bu kaynağı atla, devam et */
            }
        }
        return issues;
    }

    /** Mixed content: HTTPS sayfada http:// ile YÜKLENEN alt-kaynak (img/css/js/iframe/font/favicon). a[href]
     *  HYPERLINK'i (LINK) navigasyon hedefidir — tarayıcı mixed-content uyarısı üretmez → HARİÇ (false-positive önleme). */
    static boolean isMixedContent(boolean pageHttps, String resourceType, String url) {
        return pageHttps && !"LINK".equals(resourceType) && url.toLowerCase(Locale.ROOT).startsWith("http://");
    }

    /** >=400 durum kodu sınıflandırması (F2): kesin-yok/sunucu-hatası mı yoksa belirsiz/erişim/geçici mi.
     *  404/410 → BROKEN (kesin yok); 5xx (503 HARİÇ) → BROKEN (sunucu hatası); 401/403/429/451/503 + diğer tüm
     *  4xx (400/405/406…) → BLOCKED (WAF bot-blok / rate-limit / geçici — tarayıcıda/oturumda yüklenebilir). */
    static String classifyStatus(int status) {
        if (status == 404 || status == 410) return "BROKEN";
        if (status >= 500 && status != 503) return "BROKEN";
        return "BLOCKED";
    }

    /** Bir sorunun DEGRADED ALARMINA (e-posta) sayılıp sayılmadığı. Sorunlar TABLODA/sayaçta her zaman görünür;
     *  bu YALNIZ alarm/e-posta geçididir. Q2: BLOCKED/SLOW hiç alarm üretmez. Q1: LINK (a[href]) kesin-yok (404/410)
     *  VEYA kesin transport hatası (httpStatus null — NXDOMAIN/bağlantı reddi; verifyOne blocked/hata yolları) alarm
     *  sayılır (2026-08-03: ölü dış link — DNS kaydı silinmiş hedef — 3P toggle açıkken alarm üretebilsin); dış linkin
     *  5xx/timeout/belirsiz durumu yine alarm üretmez. Yüklenen alt-kaynak: broken/timeout alarm.
     *  MIXED_CONTENT → true (mixed toggle ayrıca SchedulerService'te uygulanır). */
    static boolean countsForAlarm(String issueType, String resourceType, Integer httpStatus) {
        if (issueType == null) return false;
        switch (issueType) {
            case "MIXED_CONTENT": return true;
            case "BLOCKED": case "SLOW": return false;
            case "BROKEN": case "TIMEOUT":
                if ("LINK".equals(resourceType)) {
                    if ("TIMEOUT".equals(issueType)) return false;   // link timeout: belirsiz — alarm YOK
                    return httpStatus == null || httpStatus == 404 || httpStatus == 410;
                }
                return true;
            default: return false;
        }
    }

    /** Bir kaynağı doğrula → sorun varsa {@link ResourceIssue}, sağlıklıysa null. */
    private ResourceIssue verifyOne(Resource r, boolean pageHttps, String rootHost, int timeoutMs, int slowMs) {
        boolean firstParty = sameSite(hostOf(r.url()), rootHost);
        // Mixed content: https sayfada http:// YÜKLENEN kaynak — istek atmadan işaretle (headline sorun). LINK hariç.
        if (isMixedContent(pageHttps, r.type(), r.url())) {
            return new ResourceIssue(r.url(), r.type(), r.sourcePage(), "MIXED_CONTENT", firstParty, null, null);
        }
        FetchResult res = verifyWithRetry(r.url(), timeoutMs);
        if (res.blocked()) {
            return new ResourceIssue(r.url(), r.type(), r.sourcePage(), "BROKEN", firstParty, null, res.durationMs());
        }
        if (res.status() == 0) {   // transport hatası / timeout
            String type = res.error() != null && res.error().toLowerCase(Locale.ROOT).contains("timed out")
                    ? "TIMEOUT" : "BROKEN";
            return new ResourceIssue(r.url(), r.type(), r.sourcePage(), type, firstParty, null, res.durationMs());
        }
        if (res.status() >= 400) {
            return new ResourceIssue(r.url(), r.type(), r.sourcePage(), classifyStatus(res.status()), firstParty, res.status(), res.durationMs());
        }
        if (res.durationMs() > slowMs) {
            return new ResourceIssue(r.url(), r.type(), r.sourcePage(), "SLOW", firstParty, res.status(), res.durationMs());
        }
        return null;   // sağlıklı
    }

    /** HEAD → (405/501 ya da transport hatasında) GET; kırık/timeout kararı için kısa aralıklı tek retry. */
    private FetchResult verifyWithRetry(String url, int timeoutMs) {
        FetchResult r = verifyOnce(url, timeoutMs);
        boolean bad = r.blocked() || r.status() == 0 || r.status() >= 400;
        if (r.blocked() || !bad) return r;   // engellendi ya da zaten iyi → retry yok
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return r; }
        // Retry: en son gözlemi döndür (geçici takılma düzelmişse iyi sonuç kazanır; hâlâ kötüyse yine kırık sayılır).
        return verifyOnce(url, timeoutMs);
    }

    private FetchResult verifyOnce(String url, int timeoutMs) {
        FetchResult head = fetchFollowing(url, "HEAD", false, timeoutMs);
        if (head.blocked()) return head;
        // HEAD çoğu WAF/CDN/ASP.NET(.aspx) sunucusunda YANLIŞ ele alınır (405/501 değil; 400/403/404/500 dönebilir
        // ama aynı kaynak GET'te 200'dür). Bu yüzden HEAD transport hatası (0) VEYA herhangi bir >=400 dönerse
        // GET ile TEYİT et — GET de kötüyse gerçekten kırık, GET iyiyse sağlıklı (false-positive önleme).
        if (head.status() == 0 || head.status() >= 400) {
            return fetchFollowing(url, "GET", false, timeoutMs);
        }
        return head;
    }

    // ── Manuel redirect takipli fetch (her hop SSRF'den geçer) ───────────────
    private FetchResult fetchFollowing(String url, String method, boolean wantBody, int timeoutMs) {
        long start = System.currentTimeMillis();
        String current = url;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                String host = hostOf(current);
                try {
                    if (host == null) throw new SsrfGuard.BlockedException("geçersiz URL: " + current);
                    // SSRF (her hop). NOT (M4 residual): validate() çözülen IP'leri döndürür ama HttpClient host'u
                    // yeniden çözer → TOCTOU/DNS-rebind penceresi. Metadata/loopback/link-local HER ZAMAN bloklu +
                    // JVM pozitif-DNS cache pratik riski azaltır; IP-pinning (NetworkResolver) bilinçli uygulanmadı
                    // (HTTPS SNI karmaşası + kaynak-başı maliyet). Ops: networkaddress.cache.ttl'i 0'a çekmeyin.
                    ssrfGuard.validate(host);
                } catch (SsrfGuard.BlockedException be) {
                    return new FetchResult(0, System.currentTimeMillis() - start, null, be.getMessage(), true);
                }
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(current))
                        .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                        // Tarayıcı-benzeri header seti (F4): katı sunucular Accept/Accept-Language yoksa 406/403 döner.
                        .header("User-Agent", userAgent())
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "tr,en;q=0.9");
                HttpRequest req = "HEAD".equals(method)
                        ? rb.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
                        : rb.GET().build();
                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int sc = resp.statusCode();
                if (sc >= 300 && sc < 400) {
                    String loc = resp.headers().firstValue("location").orElse(null);
                    try (InputStream is = resp.body()) { is.readNBytes(4096); } catch (Exception ignore) {}
                    if (loc == null || loc.isBlank()) {   // yönlendirme hedefi yok → olduğu gibi dön
                        return new FetchResult(sc, System.currentTimeMillis() - start, null, null, false);
                    }
                    current = resolve(current, loc);
                    if ("HEAD".equals(method) && sc == 303) method = "GET";   // 303 See Other → GET
                    continue;
                }
                byte[] body = null;
                try (InputStream is = resp.body()) {
                    if (wantBody) body = is.readNBytes(MAX_BODY_BYTES);
                    else is.readNBytes(4096);   // gövdeyi tüket (bağlantı iadesi)
                }
                return new FetchResult(sc, System.currentTimeMillis() - start, body, null, false);
            }
            return new FetchResult(0, System.currentTimeMillis() - start, null, "çok fazla yönlendirme", false);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new FetchResult(0, System.currentTimeMillis() - start, null, msg, false);
        }
    }

    // ── robots.txt / sitemap ─────────────────────────────────────────────────
    private Set<String> fetchRobotsDisallow(String url, int timeoutMs) {
        Set<String> disallow = new HashSet<>();
        try {
            String robots = originOf(url) + "/robots.txt";
            FetchResult r = fetchFollowing(robots, "GET", true, timeoutMs);
            if (r.body() == null || r.status() >= 400) return disallow;
            boolean applies = false;   // yalnız "*" veya bizim UA grubunu uygula
            for (String line : new String(r.body(), StandardCharsets.UTF_8).split("\\r?\\n")) {
                String l = line.trim();
                int c = l.indexOf('#'); if (c >= 0) l = l.substring(0, c).trim();
                if (l.isEmpty()) continue;
                String low = l.toLowerCase(Locale.ROOT);
                if (low.startsWith("user-agent:")) {
                    String ua = l.substring(11).trim().toLowerCase(Locale.ROOT);
                    applies = "*".equals(ua) || ua.contains("certmonitor") || BOT_TOKEN.startsWith(ua);
                } else if (applies && low.startsWith("disallow:")) {
                    String path = l.substring(9).trim();
                    if (!path.isEmpty()) disallow.add(path);
                }
            }
        } catch (Exception e) { log.debug("robots.txt okunamadı {}: {}", sanitize(url), e.getMessage()); }
        return disallow;
    }

    private List<String> fetchSitemapSeeds(String url, int timeoutMs, String rootHost) {
        List<String> seeds = new ArrayList<>();
        try {
            FetchResult r = fetchFollowing(originOf(url) + "/sitemap.xml", "GET", true, timeoutMs);
            if (r.body() == null || r.status() >= 400) return seeds;
            var m = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>", Pattern.CASE_INSENSITIVE).matcher(
                    new String(r.body(), StandardCharsets.UTF_8));
            while (m.find() && seeds.size() < 100) {
                String loc = m.group(1).trim();
                if (isHttp(loc) && sameSite(hostOf(loc), rootHost)) seeds.add(stripFragment(loc));
            }
        } catch (Exception e) { log.debug("sitemap.xml okunamadı {}: {}", sanitize(url), e.getMessage()); }
        return seeds;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────
    private PageCheckResult summarize(List<ResourceIssue> issues, int total, int pages, int httpStatus,
                                      long ms, String hash, Long bytes) {
        int broken = 0, mixed = 0;
        for (ResourceIssue i : issues) {
            if ("MIXED_CONTENT".equals(i.issueType())) mixed++;
            else if ("BROKEN".equals(i.issueType()) || "TIMEOUT".equals(i.issueType())) broken++;
        }
        String status = (broken > 0 || mixed > 0) ? "DEGRADED" : "OK";
        return new PageCheckResult(status, true, httpStatus, ms, total, broken, mixed, pages, hash, bytes, null, issues);
    }

    private static int clampConcurrency(int c) { return Math.max(1, Math.min(20, c)); }

    /** PER-CHECK hariç-tutma eşleştiricisi (regex + literal). Singleton serviste paylaşımlı alan YOK → thread-safe:
     *  her {@code check()} kendi immutable örneğini taşır. */
    private record Excludes(List<Pattern> regex, List<String> literals) {
        static final Excludes EMPTY = new Excludes(List.of(), List.of());
        boolean matches(String url) {
            String low = url.toLowerCase(Locale.ROOT);
            for (String lit : literals) if (!lit.isEmpty() && low.contains(lit)) return true;
            for (Pattern p : regex) if (p.matcher(url).matches()) return true;
            return false;
        }
    }

    /** Hariç-tutma desenleri: her satır bir glob. `Pattern.quote` regex-injection'ı ve ÜSTEL ReDoS'u önler
     *  (iç içe niceleyici üretilemez). M3: çok sayıda '*' → çok sayıda ardışık `.*` polinom backtracking'e yol
     *  açabilir → satır başına `*` ≤ MAX_STARS, uzunluk ≤ MAX_LEN; aşan/`*`'sız desen regex yerine literal
     *  substring (contains) ile eşleştirilir (backtracking imkânsız). Satır sayısı da caplenir. */
    private static final int EXCLUDE_MAX_LINES = 50, EXCLUDE_MAX_LEN = 200, EXCLUDE_MAX_STARS = 6;
    private Excludes compileExcludes(String raw) {
        if (raw == null || raw.isBlank()) return Excludes.EMPTY;
        List<Pattern> regex = new ArrayList<>();
        List<String> literals = new ArrayList<>();
        int lines = 0;
        for (String line : raw.split("\\r?\\n")) {
            String p = line.trim();
            if (p.isEmpty()) continue;
            if (++lines > EXCLUDE_MAX_LINES) { log.warn("Hariç-tutma: {} satır capine ulaşıldı, kalan yok sayıldı", EXCLUDE_MAX_LINES); break; }
            if (p.length() > EXCLUDE_MAX_LEN) p = p.substring(0, EXCLUDE_MAX_LEN);
            int stars = (int) p.chars().filter(c -> c == '*').count();
            // '*'sız (literal contains) VEYA çok '*'lı (ReDoS riski) → literal substring eşleşmesi (regex değil).
            if (stars == 0 || stars > EXCLUDE_MAX_STARS) {
                literals.add(p.replace("*", "").toLowerCase(Locale.ROOT));
                continue;
            }
            try {
                regex.add(Pattern.compile(".*" + Pattern.quote(p).replace("*", "\\E.*\\Q") + ".*"));
            } catch (Exception e) {
                literals.add(p.replace("*", "").toLowerCase(Locale.ROOT));
            }
        }
        return new Excludes(regex, literals);
    }

    private boolean isDisallowed(String url, Set<String> disallow) {
        if (disallow.isEmpty()) return false;
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isEmpty()) path = "/";
            for (String d : disallow) if (path.startsWith(d)) return true;
        } catch (Exception ignore) {}
        return false;
    }

    private static boolean isHttp(String url) {
        String l = url.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return null; }
    }

    /** Log-forging önleme (L4): loglanan URL/host'taki CR/LF'yi boşlukla değiştir (flat-file satır enjeksiyonu). */
    private static String sanitize(String s) { return s == null ? null : s.replace('\n', ' ').replace('\r', ' '); }

    /** İstek User-Agent'ı — canlı config (F4); boş/null ise tarayıcı-uyumlu varsayılan. */
    private String userAgent() {
        String ua = appSettings.getString("cert.monitor.page.user-agent", DEFAULT_UA);
        return (ua == null || ua.isBlank()) ? DEFAULT_UA : ua;
    }

    private static String originOf(String url) {
        try {
            URI u = URI.create(url);
            int port = u.getPort();
            return u.getScheme() + "://" + u.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) { return url; }
    }

    private static String resolve(String base, String ref) {
        try { return URI.create(base).resolve(ref).toString(); } catch (Exception e) { return ref; }
    }

    private static String stripFragment(String url) {
        int h = url.indexOf('#');
        return h >= 0 ? url.substring(0, h) : url;
    }

    /** Birinci-taraf: aynı host ya da aynı KAYITLI DOMAIN (eTLD+1, PSL). www/cdn alt-alanları dahil.
     *  PSL şart: naif "son 2 etiket" .com.tr/.co.uk gibi çok-etiketli suffix'lerde a.com.tr ile b.com.tr'yi
     *  yanlışlıkla aynı-site sayar → crawl kapsam kaçışı + 3.-taraf'ın 1.-taraf sanılması (yanlış alarm). */
    private boolean sameSite(String host, String rootHost) {
        if (host == null || rootHost == null) return false;
        if (host.equalsIgnoreCase(rootHost)) return true;
        String a = publicSuffixService.registrableDomain(host);
        String b = publicSuffixService.registrableDomain(rootHost);
        if (a != null && b != null) return a.equalsIgnoreCase(b);
        return false;   // PSL çözemezse (salt-suffix vb.) host eşitliği yukarıda kontrol edildi → farklı say
    }

    private static String sha256(byte[] data) {
        if (data == null) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
