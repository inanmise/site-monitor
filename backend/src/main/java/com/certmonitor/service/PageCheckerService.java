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
    /** Tek kontrolde doğrulanacak azami (tekil) kaynak — tek-pod yük koruması; aşılırsa WARN + kırpılır. */
    private static final int MAX_RESOURCES_PER_CHECK = 500;
    /** Manuel redirect zinciri üst sınırı. */
    private static final int MAX_REDIRECTS = 5;
    private static final String UA = "CertMonitor-PageCheck/1.0";

    private final SsrfGuard ssrfGuard;
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

    /** SINGLE_PAGE veya SITE_CRAWL — moda göre yönlendirir. */
    public PageCheckResult check(String url, String mode, int timeoutMs, int slowMs, int concurrency,
                                 String excludePatterns, int crawlDepth, int crawlMaxPages) {
        List<Pattern> excludes = compileExcludes(excludePatterns);
        if ("SITE_CRAWL".equalsIgnoreCase(mode)) {
            return crawlSite(url, timeoutMs, slowMs, clampConcurrency(concurrency), excludes,
                    Math.max(0, crawlDepth), Math.max(1, crawlMaxPages));
        }
        return checkSinglePage(url, timeoutMs, slowMs, clampConcurrency(concurrency), excludes);
    }

    /** Kaydetmeden canlı test için basit sarmalayıcı (SINGLE_PAGE, varsayılan eşikler). */
    public PageCheckResult test(String url, int timeoutMs) {
        return checkSinglePage(url, timeoutMs, 2000, 5, List.of());
    }

    // ── SINGLE_PAGE ──────────────────────────────────────────────────────────
    private PageCheckResult checkSinglePage(String url, int timeoutMs, int slowMs, int concurrency,
                                            List<Pattern> excludes) {
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
        String body = new String(main.body(), StandardCharsets.UTF_8);
        List<Resource> resources = inventory(body, url, url, rootHost, excludes);
        List<ResourceIssue> issues = verifyAll(resources, pageHttps, rootHost, timeoutMs, slowMs, concurrency);
        long ms = System.currentTimeMillis() - start;
        return summarize(issues, resources.size(), 1, main.status(), ms, sha256(main.body()),
                (long) main.body().length);
    }

    // ── SITE_CRAWL ───────────────────────────────────────────────────────────
    private PageCheckResult crawlSite(String url, int timeoutMs, int slowMs, int concurrency,
                                      List<Pattern> excludes, int maxDepth, int maxPages) {
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

        while (!queue.isEmpty() && pagesCrawled < maxPages) {
            String[] node = queue.poll();
            String pageUrl = node[0];
            int depth = Integer.parseInt(node[1]);
            if (visited.contains(pageUrl) || depth > maxDepth) continue;
            if (isExcluded(pageUrl, excludes) || isDisallowed(pageUrl, disallow)) continue;
            visited.add(pageUrl);

            FetchResult pr = pageUrl.equals(url) ? first : fetchFollowing(pageUrl, "GET", true, timeoutMs);
            if (pr.blocked() || pr.body() == null || pr.status() >= 400 || pr.status() == 0) {
                // Crawl sırasında erişilemeyen İÇ sayfa = kırık link (kaynak sayfası bir üst adımda kaydedildi)
                continue;
            }
            pagesCrawled++;
            String pageBody = new String(pr.body(), StandardCharsets.UTF_8);
            List<Resource> resources = inventory(pageBody, pageUrl, pageUrl, rootHost, excludes);

            // Bu sayfadaki kaynakları (site genelinde tekil) doğrula
            List<Resource> fresh = new ArrayList<>();
            for (Resource r : resources) {
                if (verified.add(r.url())) fresh.add(r);
            }
            totalResources += fresh.size();
            allIssues.addAll(verifyAll(fresh, pageHttps, rootHost, timeoutMs, slowMs, concurrency));

            // Same-origin a[href] linkleri kuyruğa (derinlik+1)
            if (depth < maxDepth) {
                for (Resource r : resources) {
                    if (!"LINK".equals(r.type())) continue;
                    if (!sameSite(hostOf(r.url()), rootHost)) continue;   // yalnız site içi
                    String norm = stripFragment(r.url());
                    if (!visited.contains(norm) && !isExcluded(norm, excludes) && !isDisallowed(norm, disallow)) {
                        queue.add(new String[]{ norm, String.valueOf(depth + 1) });
                    }
                }
            }
        }
        if (!queue.isEmpty()) log.debug("Crawl {} — {} sayfa limitine ulaşıldı, {} kuyrukta bırakıldı",
                url, maxPages, queue.size());

        long ms = System.currentTimeMillis() - start;
        return summarize(allIssues, totalResources, Math.max(1, pagesCrawled), first.status(), ms, rootHash, rootBytes);
    }

    // ── Kaynak envanteri (jsoup) ─────────────────────────────────────────────
    private record Resource(String url, String type, String sourcePage) {}

    private List<Resource> inventory(String html, String baseUrl, String sourcePage, String rootHost,
                                     List<Pattern> excludes) {
        Document doc = Jsoup.parse(html, baseUrl);
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
            if (!isHttp(r.url()) || isExcluded(r.url(), excludes)) continue;
            list.add(r);
            if (list.size() >= MAX_RESOURCES_PER_CHECK) {
                log.warn("Sayfa {} — {} kaynak limitine ulaşıldı, kalanlar atlandı", sourcePage, MAX_RESOURCES_PER_CHECK);
                break;
            }
        }
        return list;
    }

    private void addAll(Map<String, Resource> out, Document doc, String css, String attr, String type, String src) {
        for (Element el : doc.select(css)) {
            String abs = el.absUrl(attr);
            if (abs == null || abs.isBlank()) abs = el.attr(attr);   // parse edilemezse ham değer (mixed/broken tespiti için)
            if (abs.isBlank()) continue;
            out.putIfAbsent(abs, new Resource(abs, type, src));
        }
    }

    /** srcset: "url 1x, url2 2w" listesindeki her aday URL. */
    private void addSrcset(Map<String, Resource> out, Document doc, String src) {
        for (Element el : doc.select("img[srcset], source[srcset]")) {
            for (String cand : el.attr("srcset").split(",")) {
                String u = cand.trim().split("\\s+")[0];
                if (u.isBlank()) continue;
                String abs = el.root().baseUri().isBlank() ? u : resolve(el.baseUri(), u);
                out.putIfAbsent(abs, new Resource(abs, "IMG", src));
            }
        }
    }

    // ── Kaynak doğrulama ─────────────────────────────────────────────────────
    private List<ResourceIssue> verifyAll(List<Resource> resources, boolean pageHttps, String rootHost,
                                          int timeoutMs, int slowMs, int concurrency) {
        if (resources.isEmpty()) return List.of();
        Semaphore gate = new Semaphore(concurrency);
        List<CompletableFuture<ResourceIssue>> futures = new ArrayList<>();
        for (Resource r : resources) {
            futures.add(CompletableFuture.supplyAsync(() -> {
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
            ResourceIssue i = f.join();
            if (i != null) issues.add(i);
        }
        return issues;
    }

    /** Bir kaynağı doğrula → sorun varsa {@link ResourceIssue}, sağlıklıysa null. */
    private ResourceIssue verifyOne(Resource r, boolean pageHttps, String rootHost, int timeoutMs, int slowMs) {
        boolean firstParty = sameSite(hostOf(r.url()), rootHost);
        // Mixed content: https sayfada http:// kaynak — istek atmadan işaretle (headline sorun).
        if (pageHttps && r.url().toLowerCase(Locale.ROOT).startsWith("http://")) {
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
            return new ResourceIssue(r.url(), r.type(), r.sourcePage(), "BROKEN", firstParty, res.status(), res.durationMs());
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
        if (r.blocked() || !bad) return r;
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return r; }
        FetchResult retry = verifyOnce(url, timeoutMs);
        // retry düzeldiyse onu, hâlâ kötüyse ilk sonucu döndür (false-positive önleme)
        return (!retry.blocked() && retry.status() != 0 && retry.status() < 400) ? retry : retry;
    }

    private FetchResult verifyOnce(String url, int timeoutMs) {
        FetchResult head = fetchFollowing(url, "HEAD", false, timeoutMs);
        if (head.blocked()) return head;
        if (head.status() == 405 || head.status() == 501 || head.status() == 0) {
            return fetchFollowing(url, "GET", false, timeoutMs);   // HEAD desteklenmiyor → GET
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
                    ssrfGuard.validate(host);
                } catch (SsrfGuard.BlockedException be) {
                    return new FetchResult(0, System.currentTimeMillis() - start, null, be.getMessage(), true);
                }
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(current))
                        .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                        .header("User-Agent", UA);
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
                    String ua = l.substring(11).trim();
                    applies = "*".equals(ua) || UA.toLowerCase(Locale.ROOT).startsWith(ua.toLowerCase(Locale.ROOT));
                } else if (applies && low.startsWith("disallow:")) {
                    String path = l.substring(9).trim();
                    if (!path.isEmpty()) disallow.add(path);
                }
            }
        } catch (Exception e) { log.debug("robots.txt okunamadı {}: {}", url, e.getMessage()); }
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
        } catch (Exception e) { log.debug("sitemap.xml okunamadı {}: {}", url, e.getMessage()); }
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

    private List<Pattern> compileExcludes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Pattern> out = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String p = line.trim();
            if (p.isEmpty()) continue;
            // Glob → regex (yalnız '*'); geçersizse literal substring'e düş.
            try {
                String rx = ".*" + Pattern.quote(p).replace("*", "\\E.*\\Q") + ".*";
                out.add(Pattern.compile(rx));
            } catch (Exception e) {
                out.add(Pattern.compile(".*" + Pattern.quote(p) + ".*"));
            }
        }
        return out;
    }

    private boolean isExcluded(String url, List<Pattern> excludes) {
        for (Pattern p : excludes) if (p.matcher(url).matches()) return true;
        return false;
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

    /** Birinci-taraf: aynı host ya da aynı apex domain (www/cdn alt-alanları dahil). */
    private static boolean sameSite(String host, String rootHost) {
        if (host == null || rootHost == null) return false;
        if (host.equalsIgnoreCase(rootHost)) return true;
        String a = apex(host), b = apex(rootHost);
        return !a.isEmpty() && a.equalsIgnoreCase(b);
    }

    /** Naif apex: son iki etiket (alarm politikası için yeterli; PSL değil). */
    private static String apex(String host) {
        String[] p = host.split("\\.");
        return p.length >= 2 ? p[p.length - 2] + "." + p[p.length - 1] : host;
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
