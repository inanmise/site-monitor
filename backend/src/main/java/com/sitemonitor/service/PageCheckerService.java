package com.sitemonitor.service;

import com.sitemonitor.service.page.PageFetchCore;
import com.sitemonitor.service.page.PageFetchCore.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import static com.sitemonitor.service.page.PageFetchCore.hostOf;
import static com.sitemonitor.service.page.PageFetchCore.isHttp;
import static com.sitemonitor.service.page.PageFetchCore.originOf;
import static com.sitemonitor.service.page.PageFetchCore.resolve;
import static com.sitemonitor.service.page.PageFetchCore.sanitize;
import static com.sitemonitor.service.page.PageFetchCore.stripFragment;

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

    /** Bir CRAWL genelinde toplam doğrulanacak azami kaynak — bellek + DB-insert patlamasını sınırlar (M2). */
    private static final int MAX_TOTAL_RESOURCES = PageFetchCore.MAX_TOTAL_RESOURCES;
    /** Tarayıcı-uyumlu varsayılan UA (Mozilla-prefix → naif WAF/UA filtreleri 403/406 üretmez; kimlik + iletişim
     *  korunur). Admin {@code site.monitor.page.user-agent} ile override edebilir (F4). */
    private static final String DEFAULT_UA = "Mozilla/5.0 (compatible; SiteMonitor-PageCheck/1.0; +https://sitemonitor)";
    /** robots.txt User-agent eşleşmesi için sabit bot token'ı (UA browser-y olsa da robots bunu tanır). */
    private static final String BOT_TOKEN = "sitemonitor-pagecheck";

    /** Ağ davranışı (trust-all TLS, hop-başına SSRF, gövde tavanı) ve jsoup envanteri burada — Sayfa Hızı
     *  izlemesiyle PAYLAŞILIR; güvenlik kuralı olduğu için ikinci bir kopyası olmamalı. */
    private final PageFetchCore core;
    private final PublicSuffixService publicSuffixService;
    private final AppSettingsService appSettings;   // page.user-agent canlı okuma (F4)

    // ── Sonuç tipleri ────────────────────────────────────────────────────────
    public record ResourceIssue(String resourceUrl, String resourceType, String sourcePage,
                                String issueType, boolean firstParty, Integer httpStatus, Long durationMs) {}

    public record PageCheckResult(String status, boolean mainReachable, Integer httpStatus, long responseMs,
                                  int totalResources, int brokenResources, int timeoutResources,
                                  int mixedContentCount,
                                  int pagesCrawled, String contentHash, Long bodyBytes, String error,
                                  List<ResourceIssue> issues) {}

    // ── Giriş noktaları ──────────────────────────────────────────────────────

    /** SINGLE_PAGE veya SITE_CRAWL — moda göre yönlendirir. {@code maxCheckSeconds} tüm kontrol için wall-clock
     *  üst sınırı (yavaş/yanıt-vermeyen hedefin scheduler/request thread'ini süresiz tutmasını engeller — H1/M1). */
    public PageCheckResult check(String url, String mode, int timeoutMs, int slowMs, int concurrency,
                                 String excludePatterns, int crawlDepth, int crawlMaxPages, int maxCheckSeconds) {
        // Yapılandırma hatası (şemasız/host'suz URL) kesinti DEĞİL: istek atılmaz, CONFIG_ERROR döner ve
        // SchedulerService bunun için alarm açmaz. Eskiden URI.create şemasız değeri relative referans sayıp
        // host=null verdiği için sonuç DOWN oluyor ve takıma sahte "Sayfa yüklenemiyor" e-postası gidiyordu.
        if (!com.sitemonitor.util.MonitorUrls.isCheckable(url)) {
            return new PageCheckResult("CONFIG_ERROR", false, null, 0L, 0, 0, 0, 0, 0, null, null,
                    com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG, List.of());
        }
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
        if (!com.sitemonitor.util.MonitorUrls.isCheckable(url)) {        // check(...) ile aynı yapılandırma geçidi
            return new PageCheckResult("CONFIG_ERROR", false, null, 0L, 0, 0, 0, 0, 0, null, null,
                    com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG, List.of());
        }
        return checkSinglePage(url, timeoutMs, 2000, 5, Excludes.EMPTY, System.currentTimeMillis() + 60_000L);
    }

    // ── SINGLE_PAGE ──────────────────────────────────────────────────────────
    private PageCheckResult checkSinglePage(String url, int timeoutMs, int slowMs, int concurrency,
                                            Excludes excludes, long deadline) {
        long start = System.currentTimeMillis();
        String rootHost = hostOf(url);
        PageFetchCore.Fetch main = fetchFollowing(url, "GET", true, timeoutMs);
        if (main.blocked() || main.body() == null || main.status() >= 400 || main.status() == 0) {
            long ms = System.currentTimeMillis() - start;
            String err = main.error() != null ? main.error()
                    : (main.status() >= 400 ? "ana sayfa HTTP " + main.status() : "ana sayfa alınamadı");
            return new PageCheckResult("DOWN", false, main.status() == 0 ? null : main.status(), ms,
                    0, 0, 0, 0, 1, null, null, err, List.of());
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

        PageFetchCore.Fetch first = fetchFollowing(url, "GET", true, timeoutMs);
        if (first.blocked() || first.body() == null || first.status() >= 400 || first.status() == 0) {
            long ms = System.currentTimeMillis() - start;
            return new PageCheckResult("DOWN", false, first.status() == 0 ? null : first.status(), ms,
                    0, 0, 0, 0, 0, null, null,
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

            PageFetchCore.Fetch pr = pageUrl.equals(url) ? first : fetchFollowing(pageUrl, "GET", true, timeoutMs);
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

    // ── Kaynak envanteri (jsoup) — PageFetchCore'a devredildi ────────────────
    // rootHost parametresi burada hiç kullanılmıyordu (ölü parametre); çekirdek imzasında yok.
    private List<Resource> inventory(byte[] bytes, String baseUrl, String sourcePage, String rootHost,
                                     Excludes excludes) {
        return core.inventory(bytes, baseUrl, sourcePage, excludes::matches);
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
            }, core.executor()));
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
        PageFetchCore.Fetch res = verifyWithRetry(r.url(), timeoutMs);
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
    private PageFetchCore.Fetch verifyWithRetry(String url, int timeoutMs) {
        PageFetchCore.Fetch r = verifyOnce(url, timeoutMs);
        boolean bad = r.blocked() || r.status() == 0 || r.status() >= 400;
        if (r.blocked() || !bad) return r;   // engellendi ya da zaten iyi → retry yok
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return r; }
        // Retry: en son gözlemi döndür (geçici takılma düzelmişse iyi sonuç kazanır; hâlâ kötüyse yine kırık sayılır).
        return verifyOnce(url, timeoutMs);
    }

    private PageFetchCore.Fetch verifyOnce(String url, int timeoutMs) {
        PageFetchCore.Fetch head = fetchFollowing(url, "HEAD", false, timeoutMs);
        if (head.blocked()) return head;
        // HEAD çoğu WAF/CDN/ASP.NET(.aspx) sunucusunda YANLIŞ ele alınır (405/501 değil; 400/403/404/500 dönebilir
        // ama aynı kaynak GET'te 200'dür). Bu yüzden HEAD transport hatası (0) VEYA herhangi bir >=400 dönerse
        // GET ile TEYİT et — GET de kötüyse gerçekten kırık, GET iyiyse sağlıklı (false-positive önleme).
        if (head.status() == 0 || head.status() >= 400) {
            return fetchFollowing(url, "GET", false, timeoutMs);
        }
        return head;
    }

    // ── Fetch — PageFetchCore'a devredildi (trust-all TLS + hop-başına SSRF orada) ────
    private PageFetchCore.Fetch fetchFollowing(String url, String method, boolean wantBody, int timeoutMs) {
        PageFetchCore.FetchOptions opts = wantBody
                ? PageFetchCore.FetchOptions.body(timeoutMs, userAgent())
                : PageFetchCore.FetchOptions.probe(timeoutMs, userAgent());
        return core.fetch(url, method, opts);
    }

    // ── robots.txt / sitemap ─────────────────────────────────────────────────
    private Set<String> fetchRobotsDisallow(String url, int timeoutMs) {
        Set<String> disallow = new HashSet<>();
        try {
            String robots = originOf(url) + "/robots.txt";
            PageFetchCore.Fetch r = fetchFollowing(robots, "GET", true, timeoutMs);
            if (r.body() == null || r.status() >= 400) return disallow;
            boolean applies = false;   // yalnız "*" veya bizim UA grubunu uygula
            for (String line : new String(r.body(), StandardCharsets.UTF_8).split("\\r?\\n")) {
                String l = line.trim();
                int c = l.indexOf('#'); if (c >= 0) l = l.substring(0, c).trim();
                if (l.isEmpty()) continue;
                String low = l.toLowerCase(Locale.ROOT);
                if (low.startsWith("user-agent:")) {
                    String ua = l.substring(11).trim().toLowerCase(Locale.ROOT);
                    applies = "*".equals(ua) || ua.contains("sitemonitor")
                            || ua.contains("certmonitor") || BOT_TOKEN.startsWith(ua);   // geriye-uyum: eski UA grubu robots kurallari
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
            PageFetchCore.Fetch r = fetchFollowing(originOf(url) + "/sitemap.xml", "GET", true, timeoutMs);
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
        // KIRIK ve ZAMAN AŞIMI AYRI sayaçlar (2026-08-04): kesin kırık URL ile yanıt vermeyen URL
        // aynı sayaçta toplanmaz — UI/e-posta/istatistik ayrı gösterir. DEGRADED kararı ikisini de kapsar
        // (timeout toggle'ı kapalıysa demote SchedulerService.recheckPage'te yapılır).
        int broken = 0, timeouts = 0, mixed = 0;
        for (ResourceIssue i : issues) {
            if ("MIXED_CONTENT".equals(i.issueType())) mixed++;
            else if ("BROKEN".equals(i.issueType())) broken++;
            else if ("TIMEOUT".equals(i.issueType())) timeouts++;
        }
        String status = (broken > 0 || timeouts > 0 || mixed > 0) ? "DEGRADED" : "OK";
        return new PageCheckResult(status, true, httpStatus, ms, total, broken, timeouts, mixed, pages, hash, bytes, null, issues);
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

    /** İstek User-Agent'ı — canlı config (F4); boş/null ise tarayıcı-uyumlu varsayılan. */
    private String userAgent() {
        String ua = appSettings.getString("site.monitor.page.user-agent", DEFAULT_UA);
        return (ua == null || ua.isBlank()) ? DEFAULT_UA : ua;
    }

    /** Birinci-taraf ayrımı PSL servisinde — Sayfa Hızı izlemesiyle ORTAK kural (tek kopya). */
    private boolean sameSite(String host, String rootHost) {
        return publicSuffixService.sameSite(host, rootHost);
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
