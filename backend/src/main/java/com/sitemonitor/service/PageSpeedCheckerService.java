package com.sitemonitor.service;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.page.PageFetchCore;
import com.sitemonitor.service.page.PageSpeedRules;
import com.sitemonitor.util.MonitorUrls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Sayfa Hızı ölçüm motoru: hedef sayfayı ve TARAYICININ YÜKLEYECEĞİ alt kaynakları çeker, süreyi ve
 * ağırlığı ölçer, eşikleri değerlendirir.
 *
 * <p><b>Ölçümün dürüst sınırı:</b> gerçek bir tarayıcı yoktur, JavaScript ÇALIŞMAZ. Bu yüzden LCP/CLS
 * gibi Core Web Vitals metrikleri iddia edilmez; ölçülen şey "sunucu ne kadar sürede veriyor ve sayfa
 * ne kadar ağır". JS ile sonradan enjekte edilen kaynaklar sayıma girmez — arayüz bunu açıkça söyler.
 *
 * <p><b>a[href] linkleri ölçüme GİRMEZ:</b> tarayıcı onları indirmez, navigasyon hedefidir. Sayfa
 * Bütünlüğü izlemesi onları kırık-link diye kontrol eder; burada saymak sayfayı olduğundan ağır
 * gösterirdi.
 *
 * <p><b>Ağ yolu:</b> proxy kullanılmaz ({@link PageFetchCore} pod'dan doğrudan gider) — proxy gecikmesi
 * ölçüme karışırsa rakam sayfayı değil ağ yolunu anlatır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageSpeedCheckerService {

    /**
     * Ölçülen tek kaynak.
     *
     * @param truncated bu kaynağın okuması {@link PageFetchCore#MAX_COUNT_BYTES} tavanında KESİLDİ —
     *                  {@code bytes} gerçek boyut değil ALT SINIRDIR. İşaretlenmezse tek bir dev
     *                  dosya (video/ISO) toplamı sessizce olduğundan küçük gösterir ve o toplama
     *                  bakarak konan eşik yanlış yere oturur.
     */
    public record Measured(String url, String type, long bytes, long durationMs,
                           Integer statusCode, boolean thirdParty, boolean failed,
                           boolean truncated) {}

    /**
     * Bir ölçümün tamamı.
     *
     * @param status OK | SLOW | DOWN | CONFIG_ERROR — SLOW bir KESİNTİ DEĞİLDİR (uptime'a işlemez)
     */
    public record Result(String status, Integer statusCode, long ttfbMs, long htmlMs, long totalMs,
                         long totalBytes, int requestCount, int failedCount, boolean capped,
                         boolean bytesTruncated,
                         List<String> breached, String error, List<Measured> resources) {

        public boolean reachable() { return "OK".equals(status) || "SLOW".equals(status); }
    }

    private final PageFetchCore core;
    private final PublicSuffixService publicSuffixService;
    private final AppSettingsService appSettings;
    private final SecretCipher secretCipher;

    // ── Giriş noktaları ──────────────────────────────────────────────────────

    /** Kayıtlı bir izlemenin ölçümü. */
    public Result check(PageSpeedMonitor m) {
        String pass = decryptSecret(m.getBasicAuthPassEnc());
        return measure(m.getUrl(),
                timeoutOf(m),
                PageSpeedRules.clampConcurrency(m.getResourceConcurrency()),
                PageSpeedRules.userAgentOr(m.getUserAgent(), defaultUserAgent()),
                Boolean.TRUE.equals(m.getSendDnt()),
                PageSpeedRules.exclusion(Boolean.TRUE.equals(m.getExcludeTrackers()), m.getTrackerPatterns()),
                PageSpeedRules.basicAuthHeader(m.getBasicAuthUser(), pass),
                PageSpeedRules.parseHeaders(decryptSecret(m.getCustomHeadersEnc())),
                m);
    }

    /**
     * Kaydetmeden canlı deneme — DB'ye hiçbir şey yazmaz. Eşik değerlendirmesi yapılmaz
     * (henüz eşik yok), kullanıcı yalnız ham ölçümü görür.
     */
    public Result test(PageSpeedMonitor draft) {
        String pass = decryptSecret(draft.getBasicAuthPassEnc());
        return measure(draft.getUrl(),
                timeoutOf(draft),
                PageSpeedRules.clampConcurrency(draft.getResourceConcurrency()),
                PageSpeedRules.userAgentOr(draft.getUserAgent(), defaultUserAgent()),
                Boolean.TRUE.equals(draft.getSendDnt()),
                PageSpeedRules.exclusion(Boolean.TRUE.equals(draft.getExcludeTrackers()), draft.getTrackerPatterns()),
                PageSpeedRules.basicAuthHeader(draft.getBasicAuthUser(), pass),
                PageSpeedRules.parseHeaders(decryptSecret(draft.getCustomHeadersEnc())),
                null);   // eşik değerlendirmesi yok
    }

    // ── Ölçüm ────────────────────────────────────────────────────────────────

    private Result measure(String url, int timeoutMs, int concurrency, String userAgent, boolean dnt,
                           Predicate<String> exclude, String basicAuth, Map<String, String> customHeaders,
                           PageSpeedMonitor thresholds) {
        // Yapılandırma hatası (şemasız/host'suz URL) kesinti DEĞİL: istek atılmaz ve alarm açılmaz.
        if (!MonitorUrls.isCheckable(url)) {
            return new Result("CONFIG_ERROR", null, 0, 0, 0, 0, 0, 0, false, false,
                    List.of(), MonitorUrls.CONFIG_ERROR_MSG, List.of());
        }
        long start = System.currentTimeMillis();
        long deadline = start + maxCheckSeconds() * 1000L;
        Map<String, String> headers = buildHeaders(dnt, basicAuth, customHeaders);

        PageFetchCore.FetchOptions htmlOpts = PageFetchCore.FetchOptions
                .body(timeoutMs, userAgent).withHeaders(headers).counting();
        PageFetchCore.Fetch main = core.fetch(url, "GET", htmlOpts);

        if (main.blocked() || main.status() == 0 || main.status() >= 400 || main.body() == null) {
            String err = main.error() != null ? main.error()
                    : (main.status() >= 400 ? "sayfa HTTP " + main.status() : "sayfa alınamadı");
            return new Result("DOWN", main.status() == 0 ? null : main.status(),
                    main.ttfbMs(), main.durationMs(), System.currentTimeMillis() - start,
                    main.bytes(), 1, 1, false, false, List.of(), err, List.of());
        }

        String rootHost = PageFetchCore.hostOf(url);
        List<PageFetchCore.Resource> all = core.inventory(main.body(), url, url, exclude);
        List<PageFetchCore.Resource> loadable = new ArrayList<>(all.size());
        for (PageFetchCore.Resource r : all) {
            // a[href] tarayıcı tarafından İNDİRİLMEZ → sayfa ağırlığına girmez.
            if (!"LINK".equals(r.type())) loadable.add(r);
        }
        boolean capped = all.size() >= PageFetchCore.MAX_RESOURCES_PER_CHECK;

        List<Measured> measured = weighAll(loadable, rootHost, timeoutMs, concurrency, userAgent, headers, deadline);

        long totalBytes = main.bytes();
        int failed = 0;
        // Ana sayfanın kendisi de tavana takılmış olabilir (dev HTML) — o da toplamı alt sınıra çevirir.
        boolean bytesTruncated = main.truncated();
        for (Measured x : measured) {
            totalBytes += x.bytes();
            if (x.failed()) failed++;
            if (x.truncated()) bytesTruncated = true;
        }
        int requestCount = 1 + measured.size();
        long totalMs = System.currentTimeMillis() - start;

        List<String> breached = thresholds == null ? List.of()
                : PageSpeedRules.evaluate(thresholds, (int) totalMs, (int) main.ttfbMs(), totalBytes, requestCount);

        // Eşik aşımı bir PERFORMANS olayıdır, kesinti değil: status SLOW, ok=true kalır.
        String status = breached.isEmpty() ? "OK" : "SLOW";
        return new Result(status, main.status(), main.ttfbMs(), main.durationMs(), totalMs,
                totalBytes, requestCount, failed, capped, bytesTruncated, breached, null, measured);
    }

    /** Alt kaynakları sınırlı eşzamanlılıkla, deadline'a saygılı biçimde tartar. */
    private List<Measured> weighAll(List<PageFetchCore.Resource> resources, String rootHost, int timeoutMs,
                                    int concurrency, String userAgent, Map<String, String> headers, long deadline) {
        if (resources.isEmpty()) return List.of();
        PageFetchCore.FetchOptions opts = PageFetchCore.FetchOptions
                .weigh(timeoutMs, userAgent).withHeaders(headers);
        Semaphore gate = new Semaphore(concurrency);
        List<CompletableFuture<Measured>> futures = new ArrayList<>(resources.size());
        for (PageFetchCore.Resource r : resources) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (System.currentTimeMillis() > deadline) return null;
                try {
                    gate.acquire();
                    try { return weighOne(r, rootHost, opts); }
                    finally { gate.release(); }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }, core.executor()));
        }
        List<Measured> out = new ArrayList<>(resources.size());
        for (CompletableFuture<Measured> f : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;   // deadline doldu → kalanları bırak (kısmi ama tutarlı ölçüm)
            try {
                Measured x = f.get(remaining, TimeUnit.MILLISECONDS);
                if (x != null) out.add(x);
            } catch (java.util.concurrent.TimeoutException te) {
                break;
            } catch (Exception e) {
                /* bu kaynak düştü → atla, ölçümün geri kalanı geçerli */
            }
        }
        return out;
    }

    /**
     * Tek kaynağın ağırlığı. HEAD KULLANILMAZ — HEAD gövdeyi taşımaz, dolayısıyla kaç bayt indiğini
     * söyleyemez; ağırlık ölçmenin tek yolu GET'tir. (Sayfa Bütünlüğü izlemesi HEAD kullanır çünkü
     * orada soru "var mı", burada "ne kadar".)
     */
    private Measured weighOne(PageFetchCore.Resource r, String rootHost, PageFetchCore.FetchOptions opts) {
        PageFetchCore.Fetch f = core.fetch(r.url(), "GET", opts);
        boolean thirdParty = !publicSuffixService.sameSite(PageFetchCore.hostOf(r.url()), rootHost);
        boolean failed = f.blocked() || f.status() == 0 || f.status() >= 400;
        return new Measured(r.url(), r.type(), f.bytes(), f.durationMs(),
                f.status() == 0 ? null : f.status(), thirdParty, failed, f.truncated());
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    private Map<String, String> buildHeaders(boolean dnt, String basicAuth, Map<String, String> custom) {
        Map<String, String> h = new LinkedHashMap<>();
        if (dnt) h.put("DNT", "1");
        if (basicAuth != null) h.put("Authorization", basicAuth);
        // Kullanıcı başlıkları EN SON: aynı adı taşıyan bir başlık DNT/Authorization'ı bilinçli ezebilsin
        // (özel token şeması kullanan iç servisler için). Çekirdeğin kendi başlıkları yine korunur.
        if (custom != null) h.putAll(custom);
        return h;
    }

    /** Şifreli değeri çöz; çözülemezse ölçüm o kimlik olmadan devam eder (kontrolü tamamen düşürmez —
     *  anahtar rotasyonu tüm sayfa hızı izlemelerini birden kör etmesin). */
    private String decryptSecret(String enc) {
        if (enc == null || enc.isBlank()) return null;
        try {
            return secretCipher.decrypt(enc);
        } catch (Exception e) {
            log.warn("Sayfa hızı şifreli alanı çözülemedi, o kimlik olmadan devam ediliyor: {}", e.getMessage());
            return null;
        }
    }

    private int timeoutOf(PageSpeedMonitor m) {
        Integer t = m.getTimeoutMs();
        return t == null || t <= 0 ? appSettings.getInt("site.monitor.pagespeed.default-timeout-ms", 10000) : t;
    }

    private String defaultUserAgent() {
        String ua = appSettings.getString("site.monitor.pagespeed.user-agent", PageSpeedMonitor.DEFAULT_UA);
        return (ua == null || ua.isBlank()) ? PageSpeedMonitor.DEFAULT_UA : ua;
    }

    /** Tüm ölçüm için wall-clock üst sınırı — yanıt vermeyen hedef scheduler thread'ini süresiz tutmasın. */
    private int maxCheckSeconds() {
        return Math.max(10, appSettings.getInt("site.monitor.pagespeed.max-check-seconds", 120));
    }

    /** Kaynak türünü kırılım tablosunun beklediği kümeye indirger. */
    public static String normalizeType(String type) {
        if (type == null) return "OTHER";
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "IMG", "CSS", "JS", "IFRAME", "FONT", "FAVICON" -> type.toUpperCase(Locale.ROOT);
            default -> "OTHER";
        };
    }
}
