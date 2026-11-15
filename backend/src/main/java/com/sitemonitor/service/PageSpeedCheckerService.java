package com.sitemonitor.service;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.page.HttpPhaseProbe;
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
 * <p><b>Ağ yolu:</b> varsayılan DOĞRUDAN ({@link PageFetchCore} pod'dan gider) — proxy gecikmesi ölçüme karışırsa
 * rakam sayfayı değil ağ yolunu anlatır. 2026-09-21'den itibaren izleme başına {@code useProxy} (OFF|AUTO|ON,
 * null=OFF) ile vekil SEÇİLEBİLİR (vekil-zorunlu sayfalar aksi hâlde hiç ölçülemiyordu); vekil yolunda faz kırılımı
 * ölçülmez (null), arayüz bunu söyler.
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
                           boolean truncated, Long startMs, String foundIn) {

        /**
         * Başvuran stil yok (2026-11-15 öncesi imza). {@code foundIn} (genişletilmiş ölçüm): kaynağı başvuran stil dosyasının
         * yönlendirme SONRASI adresi; satır içi stilden bulunduysa sayfanın adresi; HTML öğesinden geldiyse null.
         */
        public Measured(String url, String type, long bytes, long durationMs, Integer statusCode, boolean thirdParty,
                        boolean failed, boolean truncated, Long startMs) {
            this(url, type, bytes, durationMs, statusCode, thirdParty, failed, truncated, startMs, null);
        }

        /**
         * Başlangıç anı bilinmeyen satır (eski imza — testler ve geriye uyum). {@code startMs} (2026-11-15, Sayfa Hızı 2.
         * sürüm şelale görünümü): kaynağın isteği, sayfa yükünün başlangıcından (ana HTML isteği) kaç ms sonra ATILDI —
         * ölçülmüş değer (tahmin değil).
         */
        public Measured(String url, String type, long bytes, long durationMs, Integer statusCode, boolean thirdParty,
                        boolean failed, boolean truncated) {
            this(url, type, bytes, durationMs, statusCode, thirdParty, failed, truncated, null);
        }
    }

    /**
     * Bir ölçümün tamamı.
     *
     * @param status OK | SLOW | DOWN | CONFIG_ERROR — SLOW bir KESİNTİ DEĞİLDİR (uptime'a işlemez)
     */
    public record Result(String status, Integer statusCode, long ttfbMs, long htmlMs, long totalMs,
                         long totalBytes, int requestCount, int failedCount, boolean capped,
                         boolean bytesTruncated,
                         List<String> breached, String error, List<Measured> resources,
                         HttpPhaseProbe.Phases phases, int skippedLazy,
                         com.sitemonitor.service.failure.CheckFailure failure,
                         boolean timeTruncated, TypeBreakdown breakdown,
                         List<String> undecided, Long downloadMs, boolean extended, PageSpeedRules.Growth growth) {

        public boolean reachable() { return "OK".equals(status) || "SLOW".equals(status); }

        /**
         * Genişletilmiş kip ve büyüme referansı yok (2026-11-15 öncesi tam imza) — test ve geriye uyum. {@code extended}: ölçüm
         * genişletilmiş kipte yapıldı (madde 10); {@code growth}: büyüme karşılaştırmasının referansı (madde 13; null =
         * okunmadı / büyüme eşiği yok).
         */
        public Result(String status, Integer statusCode, long ttfbMs, long htmlMs, long totalMs,
                      long totalBytes, int requestCount, int failedCount, boolean capped,
                      boolean bytesTruncated, List<String> breached, String error, List<Measured> resources,
                      HttpPhaseProbe.Phases phases, int skippedLazy,
                      com.sitemonitor.service.failure.CheckFailure failure,
                      boolean timeTruncated, TypeBreakdown breakdown, List<String> undecided, Long downloadMs) {
            this(status, statusCode, ttfbMs, htmlMs, totalMs, totalBytes, requestCount, failedCount, capped, bytesTruncated,
                    breached, error, resources, phases, skippedLazy, failure, timeTruncated, breakdown, undecided, downloadMs,
                    false, null);
        }

        /** Kararsız eşik ve indirme süresi yok (inceleme öncesi imza) — test ve geriye uyum. */
        public Result(String status, Integer statusCode, long ttfbMs, long htmlMs, long totalMs,
                      long totalBytes, int requestCount, int failedCount, boolean capped,
                      boolean bytesTruncated, List<String> breached, String error, List<Measured> resources,
                      HttpPhaseProbe.Phases phases, int skippedLazy,
                      com.sitemonitor.service.failure.CheckFailure failure,
                      boolean timeTruncated, TypeBreakdown breakdown) {
            this(status, statusCode, ttfbMs, htmlMs, totalMs, totalBytes, requestCount, failedCount, capped, bytesTruncated,
                    breached, error, resources, phases, skippedLazy, failure, timeTruncated, breakdown, List.of(), null,
                    false, null);
        }

        /** Süre tavanı yok, kırılım yok (2026-11-14 öncesi tam imza) — test ve geriye uyum. */
        public Result(String status, Integer statusCode, long ttfbMs, long htmlMs, long totalMs,
                      long totalBytes, int requestCount, int failedCount, boolean capped,
                      boolean bytesTruncated, List<String> breached, String error, List<Measured> resources,
                      HttpPhaseProbe.Phases phases, int skippedLazy,
                      com.sitemonitor.service.failure.CheckFailure failure) {
            this(status, statusCode, ttfbMs, htmlMs, totalMs, totalBytes, requestCount, failedCount, capped, bytesTruncated,
                    breached, error, resources, phases, skippedLazy, failure, false, null, List.of(), null, false, null);
        }

        /**
         * TTFB — eşiğin, kartın, geçmişin ve önerinin okuduğu TEK sayı (2026-11-14, inceleme B2): faz probunun SON adımındaki
         * SUNUCU fazı (izlemenin koşullarıyla, taze bağlantıda). Prob ölçemediyse null = BİLİNMİYOR (0 değil, "uygun" değil).
         * {@link #ttfbMs()} bileşeni ana isteğin (havuz sıcaklığına bağlı) ilk baytıdır — yalnız iç bilgi.
         */
        public Integer serverTtfbMs() { return phases == null ? null : phases.serverMs(); }

        /** Toplam / tür baytları ALT SINIR mı: bayt tavanı, süre tavanı ya da kaynak tavanı (madde 4). */
        public boolean lowerBound() { return bytesTruncated || timeTruncated || capped; }

        /** Eşik karşılaştırmasının girdisi — TTFB faz probunun sunucu fazı ({@link #serverTtfbMs()}; null = bilinmiyor). */
        public PageSpeedRules.Measurement measurement() {
            TypeBreakdown b = breakdown;
            return new PageSpeedRules.Measurement((int) totalMs, serverTtfbMs(), totalBytes, requestCount,
                    b == null ? null : b.jsBytes(), b == null ? null : b.imgBytes(), b == null ? null : b.fontBytes(),
                    b == null ? null : b.thirdPartyBytes(), lowerBound(), growth);
        }

        /** Aynı ölçüm, yeni karar alanlarıyla (büyüme uygulandıktan sonra: durum, ihlaller, kararsızlar, referans). */
        Result decided(String newStatus, List<String> newBreached, List<String> newUndecided, PageSpeedRules.Growth g) {
            return new Result(newStatus, statusCode, ttfbMs, htmlMs, totalMs, totalBytes, requestCount, failedCount, capped,
                    bytesTruncated, newBreached, error, resources, phases, skippedLazy, failure, timeTruncated, breakdown,
                    newUndecided, downloadMs, extended, g);
        }

        /** Nedensiz biçim (2026-10-05 öncesi tam imza) — ölçüm sonucu, test ve geriye uyum. */
        public Result(String status, Integer statusCode, long ttfbMs, long htmlMs, long totalMs,
                      long totalBytes, int requestCount, int failedCount, boolean capped,
                      boolean bytesTruncated, List<String> breached, String error, List<Measured> resources,
                      HttpPhaseProbe.Phases phases, int skippedLazy) {
            this(status, statusCode, ttfbMs, htmlMs, totalMs, totalBytes, requestCount, failedCount,
                    capped, bytesTruncated, breached, error, resources, phases, skippedLazy, null);
        }

        /** Eski çağrı biçimi (faz/lazy bilgisi olmadan) — test ve geriye uyum. */
        public Result(String status, Integer statusCode, long ttfbMs, long htmlMs, long totalMs,
                      long totalBytes, int requestCount, int failedCount, boolean capped,
                      boolean bytesTruncated, List<String> breached, String error, List<Measured> resources) {
            this(status, statusCode, ttfbMs, htmlMs, totalMs, totalBytes, requestCount, failedCount,
                    capped, bytesTruncated, breached, error, resources, HttpPhaseProbe.NONE, 0, null);
        }
    }

    /**
     * Ölçümün tür kırılımı (2026-11-14, madde 9) — her kontrolde saklanır (pagespeed_checks). Bayt = ÖLÇÜLEN (telden); adet =
     * sayfanın istediği (envanter; ölçülemeyen de sayılır — istek sayısıyla aynı kural). {@code htmlBytes} ana belge;
     * img = IMG + FAVICON, other = IFRAME + OTHER. Birinci + üçüncü taraf = toplam bayt (ana belge birinci taraftır).
     */
    public record TypeBreakdown(long htmlBytes, long jsBytes, int jsCount, long cssBytes, int cssCount,
                                long imgBytes, int imgCount, long fontBytes, int fontCount,
                                long otherBytes, int otherCount, long firstPartyBytes, long thirdPartyBytes) {}

    /**
     * Büyüme alarmını ölçüme uygular (2026-11-15, madde 13 — saf): önceki 7 günün TAM, aynı kipteki zamanlanmış ölçümlerinden
     * referans ({@link PageSpeedRules#growthOf}, bant açıksa {@link PageSpeedRules#bandVerdict}) kurulur ve eşikler büyüme
     * referansıyla YENİDEN değerlendirilir (ihlaller, kararsızlar, durum). Sayfa alınamadıysa ya da izlemede büyüme eşiği
     * yoksa sonuç aynen döner. {@code samples} null = geçmiş okunamadı → büyüme eşikleri BİLİNMİYOR (ihlal de sağlık da değil).
     */
    public static Result withGrowth(Result res, PageSpeedMonitor m, List<PageSpeedRules.GrowthSample> samples) {
        return withGrowth(res, m, samples, false);
    }

    /**
     * {@link #withGrowth(Result, PageSpeedMonitor, List)} + geçmişin TAM okunup okunmadığı ({@code complete}: okuma sınıra
     * takılmadı → örnekler referans penceresinin tamamı; bant kontrollü uyumu buna bakar — {@link PageSpeedRules#bandVerdict}).
     */
    public static Result withGrowth(Result res, PageSpeedMonitor m, List<PageSpeedRules.GrowthSample> samples, boolean complete) {
        if (res == null || m == null || !res.reachable() || !growthConfigured(m)) return res;
        PageSpeedRules.Growth g = samples == null ? PageSpeedRules.Growth.NONE : PageSpeedRules.growthOf(samples);
        if (Boolean.TRUE.equals(m.getGrowthBytesBand()) && samples != null)
            g = g.withBand(PageSpeedRules.bandVerdict(res.totalBytes(), samples, complete));
        Result withRef = res.decided(res.status(), res.breached(), res.undecided(), g);
        List<String> breached = PageSpeedRules.evaluate(m, withRef.measurement());
        List<String> undecided = PageSpeedRules.undecided(m, withRef.measurement(), breached);
        return withRef.decided(breached.isEmpty() ? "OK" : "SLOW", breached, undecided, g);
    }

    /** İzlemede büyüme eşiği (yüzde ya da bant) tanımlı mı — değilse geçmiş HİÇ okunmaz. */
    public static boolean growthConfigured(PageSpeedMonitor m) {
        if (m == null) return false;
        return (m.getGrowthBytesPct() != null && m.getGrowthBytesPct() > 0)
                || (m.getGrowthRequestsPct() != null && m.getGrowthRequestsPct() > 0)
                || Boolean.TRUE.equals(m.getGrowthBytesBand());
    }

    /** {@link #normalizeType} türünü kırılım kovasına indirger: js | css | img | font | other. */
    public static String bucketOf(String type) {
        return switch (normalizeType(type)) {
            case "JS" -> "js";
            case "CSS" -> "css";
            case "IMG", "FAVICON" -> "img";
            case "FONT" -> "font";
            default -> "other";
        };
    }

    /** Envanter (adet) + ölçülenler (bayt) + ana belge → kırılım. Saf. */
    static TypeBreakdown breakdownOf(long htmlBytes, List<PageFetchCore.Resource> inventory, List<Measured> measured) {
        long js = 0, css = 0, img = 0, font = 0, other = 0, third = 0;
        int jsN = 0, cssN = 0, imgN = 0, fontN = 0, otherN = 0;
        for (PageFetchCore.Resource r : inventory) {
            switch (bucketOf(r.type())) {
                case "js" -> jsN++;
                case "css" -> cssN++;
                case "img" -> imgN++;
                case "font" -> fontN++;
                default -> otherN++;
            }
        }
        long total = htmlBytes;
        for (Measured x : measured) {
            long b = Math.max(0L, x.bytes());
            total += b;
            if (x.thirdParty()) third += b;
            switch (bucketOf(x.type())) {
                case "js" -> js += b;
                case "css" -> css += b;
                case "img" -> img += b;
                case "font" -> font += b;
                default -> other += b;
            }
        }
        return new TypeBreakdown(htmlBytes, js, jsN, css, cssN, img, imgN, font, fontN, other, otherN,
                total - third, third);
    }

    private final PageFetchCore core;
    private final HttpPhaseProbe phaseProbe;
    private final PublicSuffixService publicSuffixService;
    private final AppSettingsService appSettings;
    private final SecretCipher secretCipher;
    /** Vekil kararı (2026-09-21) — isteğe bağlı: bean yoksa (eski test kurulumları) her zaman doğrudan. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProxyPolicyService proxyPolicy;

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
                m, viaProxyFor(m), maxCheckSeconds(), extendedOf(m));
    }

    /** Genişletilmiş ölçüm açık mı (null = kapalı — mevcut izlemeler kullanıcı açana dek klasik kipte kalır). */
    public static boolean extendedOf(PageSpeedMonitor m) {
        return m != null && Boolean.TRUE.equals(m.getExtendedMeasurement());
    }

    /** İzlemenin vekil kararı: null/OFF → doğrudan (varsayılan); AUTO/ON → {@link ProxyPolicyService}. Bean yoksa doğrudan. */
    public boolean viaProxyFor(PageSpeedMonitor m) {
        String mode = ProxyPolicyService.normalizeModeDefaultOff(m == null ? null : m.getUseProxy());
        if (ProxyPolicyService.OFF.equals(mode) || proxyPolicy == null || m == null) return false;
        return proxyPolicy.decide(m.getUrl(), mode).viaProxy();
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
                null, viaProxyFor(draft), maxCheckSeconds(), extendedOf(draft));   // eşik değerlendirmesi yok
    }

    /**
     * Uçtan uca tanılama (2026-10-05): kayıtlı izlemenin ölçümü — {@link #check(PageSpeedMonitor)} ile AYNI yol (aynı
     * başlıklar, eşik değerlendirmesi, envanter kuralı), yalnız yol (vekil/doğrudan) çağırandan gelir ve duvar-saati tavanı
     * tanılamanın bütçesine kısılır. DB'ye hiçbir şey yazmaz (bu sınıf zaten yazmaz; kayıt sweep'te).
     *
     * @param viaProxy   ölçüm hangi yoldan (tanılama iki yolu da dener)
     * @param maxSeconds duvar-saati tavanı (10 sn tabanı; izlemenin ayarından büyükse ayar geçerli)
     */
    public Result checkForDiagnostics(PageSpeedMonitor m, boolean viaProxy, int maxSeconds) {
        String pass = decryptSecret(m.getBasicAuthPassEnc());
        int cap = Math.max(10, Math.min(maxSeconds, maxCheckSeconds()));
        return measure(m.getUrl(),
                timeoutOf(m),
                PageSpeedRules.clampConcurrency(m.getResourceConcurrency()),
                effectiveUserAgent(m),
                Boolean.TRUE.equals(m.getSendDnt()),
                PageSpeedRules.exclusion(Boolean.TRUE.equals(m.getExcludeTrackers()), m.getTrackerPatterns()),
                PageSpeedRules.basicAuthHeader(m.getBasicAuthUser(), pass),
                PageSpeedRules.parseHeaders(decryptSecret(m.getCustomHeadersEnc())),
                m, viaProxy, cap, extendedOf(m));
    }

    /** İzlemenin gönderdiği User-Agent (izleme ayarı, yoksa sistem varsayılanı) — tanılamanın ham ölçümü aynısını gönderir. */
    public String effectiveUserAgent(PageSpeedMonitor m) {
        return PageSpeedRules.userAgentOr(m == null ? null : m.getUserAgent(), defaultUserAgent());
    }

    /** İzlemenin istek zaman aşımı (boşsa sistem varsayılanı) — tanılama aynı değerden türetir. */
    public int effectiveTimeoutMs(PageSpeedMonitor m) {
        return timeoutOf(m);
    }

    // ── Ölçüm ────────────────────────────────────────────────────────────────

    private Result measure(String url, int timeoutMs, int concurrency, String userAgent, boolean dnt,
                           Predicate<String> exclude, String basicAuth, Map<String, String> customHeaders,
                           PageSpeedMonitor thresholds, boolean viaProxy, int maxSeconds, boolean extended) {
        // Yapılandırma hatası (şemasız/host'suz URL) kesinti DEĞİL: istek atılmaz ve alarm açılmaz.
        if (!MonitorUrls.isCheckable(url)) {
            return new Result("CONFIG_ERROR", null, 0, 0, 0, 0, 0, 0, false, false,
                    List.of(), MonitorUrls.CONFIG_ERROR_MSG, List.of(), HttpPhaseProbe.NONE, 0,
                    com.sitemonitor.service.failure.CheckFailure.of(com.sitemonitor.service.failure.CheckFailureReason.CONFIG_ERROR));
        }
        Map<String, String> headers = buildHeaders(dnt, basicAuth, customHeaders);
        // Faz kırılımı ve TTFB (2026-11-14, inceleme B2): HER adımda TAZE bağlantıyla, izlemenin GERÇEK koşullarıyla (UA,
        // kimlik, özel başlıklar, vekil kararı) ve yönlendirmeler SON adrese kadar izlenerek ölçülür. TTFB eşiği SON adımın
        // sunucu fazına bakar — anlamı "sunucu ne kadar düşündü" olarak kaldı; ana isteğin ilk baytı HttpClient havuzunun
        // o anki sıcaklığına bağlıydı (zamanlanmış kontrol soğuk, 30 sn sonraki teyit sıcak → eşik arada salınıyordu).
        // Prob ASIL ölçümün ön şartı değildir: ölçemezse TTFB bilinmiyor kalır (eşik değerlendirilmez), kontrol sürer.
        // Süre (2026-11-15, zaman aşımı kuralı): prob kontrolün duvar-saati bütçesinin İÇİNDE koşar ve en çok YARISINI kullanır
        // (kalan yarı asıl ölçüme kalır; dağıtık işin kirası bu bütçeye göre hesaplanır). Yük süresi probdan SONRA başlar —
        // prob ölçülen sayfa yükünü değiştirmez.
        long checkStart = System.currentTimeMillis();
        long deadline = checkStart + maxSeconds * 1000L;
        HttpPhaseProbe.Phases phases = phaseProbe.measure(
                new HttpPhaseProbe.Target(url, timeoutMs, userAgent, headers, viaProxy), checkStart + maxSeconds * 500L);

        long start = System.currentTimeMillis();

        PageFetchCore.FetchOptions htmlOpts = PageFetchCore.FetchOptions
                .body(timeoutMs, userAgent).withHeaders(headers).counting().withProxy(viaProxy);
        // TTFB (madde 2): bu GERÇEK isteğin ilk baytı — izlemenin UA'sı, kimliği, özel başlıkları, vekil kararı ve yönlendirme
        // zinciri dahil. Eşik, kart, geçmiş ve "Şimdi Dene" önerisi aynı sayıyı okur; faz probu yalnız bilgi verir.
        PageFetchCore.Fetch main = core.fetch(url, "GET", htmlOpts);

        if (main.blocked() || main.status() == 0 || main.status() >= 400 || main.body() == null) {
            String err = main.error() != null ? main.error()
                    : (main.status() >= 400 ? "sayfa HTTP " + main.status() : "sayfa alınamadı");
            return new Result("DOWN", main.status() == 0 ? null : main.status(),
                    main.ttfbMs(), main.durationMs(), System.currentTimeMillis() - start,
                    main.bytes(), 1, 1, false, false, List.of(), err, List.of(), phases, 0,
                    PageCheckerService.mainFailure(main));
        }

        String rootHost = PageFetchCore.hostOf(url);
        // AS_BROWSER_LOADS: a[href] hiç toplanmaz ve srcset'te görsel başına tek aday alınır.
        // Bu bir "iyileştirme" değil DOĞRULUK meselesi — envanter tavanı eskiden link'lere
        // harcanıyordu (link ağırlıklı bir sayfada gerçek kaynaklar hiç ölçülmüyordu) ve responsive
        // görsellerin her varyantı ayrı sayıldığı için sayfa ağırlığı katbekat şişiyordu.
        PageFetchCore.Inventory inv = core.inventoryDetailed(main.body(), url, url, exclude,
                extended ? PageFetchCore.InventoryOptions.AS_BROWSER_LOADS_EXTENDED : PageFetchCore.InventoryOptions.AS_BROWSER_LOADS);
        List<PageFetchCore.Resource> loadable = inv.resources();
        // Tavan artık YALNIZ indirilen kaynaklara bakıyor, yani "ölçüm kısmi" uyarısı gerçekten
        // kısmi olduğunda çıkıyor.
        boolean capped = inv.capped();

        Weighed weighed;
        if (extended) {
            // Genişletilmiş ölçüm (2026-11-15, madde 10): stil dosyalarının başvurduğu kaynaklar dalga dalga (en çok 2 kat)
            // planlanır; sayılan nesneler Brotli ile istenir. Envanter (istek sayısı / tür adedi) planlanan HER kaynağı içerir.
            Extended ex = weighExtended(inv, url, rootHost, timeoutMs, concurrency, userAgent, headers, deadline, viaProxy,
                    start, exclude);
            weighed = ex.weighed();
            loadable = ex.planned();
            capped = capped || ex.capped();
        } else {
            weighed = weighAll(loadable, rootHost, timeoutMs, concurrency, userAgent, headers, deadline, viaProxy, start);
        }
        List<Measured> measured = weighed.rows();

        long totalBytes = main.bytes();
        int failed = 0;
        // Ana sayfanın kendisi de tavana takılmış olabilir (dev HTML) — o da toplamı alt sınıra çevirir.
        boolean bytesTruncated = main.truncated() || weighed.budgetExhausted();
        for (Measured x : measured) {
            totalBytes += x.bytes();
            if (x.failed()) failed++;
            if (x.truncated()) bytesTruncated = true;
        }
        // İstek sayısı, SAYFANIN İSTEDİĞİ kadardır — bizim indirmeyi BAŞARDIĞIMIZ kadar değil.
        // measured.size() kullanmak ters bir alarm davranışı üretiyordu: sayfa ağırlaştıkça deadline
        // ya da bayt bütçesi daha çok kaynağı atlıyor, atlanan kaynak sayılmadığı için raporlanan
        // istek sayısı DÜŞÜYOR ve REQUESTS eşiği tam olarak EN KÖTÜ sayfalarda ateşlemeyi bırakıyordu.
        // Envanterdeki her satır tarayıcının yapacağı bir istektir (başarısız olanlar dahil); atlamak
        // bizim sınırımız, sayfanın davranışı değil. Envanterin kendi tavanı `capped` ile bildirilir.
        int requestCount = 1 + loadable.size();
        long totalMs = System.currentTimeMillis() - start;
        TypeBreakdown breakdown = breakdownOf(main.bytes(), loadable, measured);

        // HTML gövdesinin GERÇEK istekte inme süresi (başlıklar → gövde sonu; SSRF kapısı / DNS dahil değil — inceleme B6d).
        Long downloadMs = main.bodyMs() >= 0 ? main.bodyMs() : null;
        Result unrated = new Result("OK", main.status(), main.ttfbMs(), main.durationMs(), totalMs,
                totalBytes, requestCount, failed, capped, bytesTruncated, List.of(), null, measured,
                phases, inv.skippedLazy(), null, weighed.timeCut(), breakdown, List.of(), downloadMs, extended, null);
        List<String> breached = thresholds == null ? List.of()
                : PageSpeedRules.evaluate(thresholds, unrated.measurement());
        // Kanıtlanamayan tanımlı eşikler (inceleme B1): kısmi ölçümde eşiğin altında kalan bayt değeri, ölçülemeyen TTFB …
        List<String> undecided = thresholds == null ? List.of()
                : PageSpeedRules.undecided(thresholds, unrated.measurement(), breached);

        // Eşik aşımı bir PERFORMANS olayıdır, kesinti değil: status SLOW, ok=true kalır.
        String status = breached.isEmpty() ? "OK" : "SLOW";
        return new Result(status, main.status(), main.ttfbMs(), main.durationMs(), totalMs,
                totalBytes, requestCount, failed, capped, bytesTruncated, breached, null, measured,
                phases, inv.skippedLazy(), null, weighed.timeCut(), breakdown, undecided, downloadMs, extended, null);
    }

    // ── Genişletilmiş ölçüm (2026-11-15, Sayfa Hızı 2c madde 10) ─────────────────────────────────────────────

    /** Stil başvurularının izlendiği en çok kat (kaynak analiziyle aynı: sayfa stili → @import → @import). */
    static final int CSS_DEPTH = 2;

    /** Genişletilmiş ölçümün sonucu: tartım + planlanan HER kaynak (istek sayısı / tür adedi) + kaynak tavanı doldu mu. */
    record Extended(Weighed weighed, List<PageFetchCore.Resource> planned, boolean capped) {}

    /** Planlanan tek kaynak: envanter satırı, kat, başvuran (stil adresi; satır içi stil → sayfa; HTML öğesi → null). */
    record Planned(PageFetchCore.Resource res, int depth, String foundIn) {}

    /** Tartılan tek kaynak + (stil ise ve kat izin veriyorsa) başvuruları ve yönlendirme SONRASI adresi. */
    record Weighed1(Measured row, List<com.sitemonitor.service.page.scan.PageResourceDiscovery.Candidate> refs, String base) {}

    /** Stil gövdesi açılırken çözülen bayt tavanı (kaynak analizinin stil ayarıyla aynı). */
    private static final PageFetchCore.ScanTuning CSS_TUNING = new PageFetchCore.ScanTuning(null, PageFetchCore.ACCEPT_ENCODING,
            PageFetchCore.MAX_BODY_BYTES, com.sitemonitor.service.page.scan.PageResourceDiscovery.MAX_CSS_CHARS);

    /**
     * Dalga dalga tartım: dalga 0 = envanter + satır içi stillerin başvuruları; sonraki dalgalar = önceki dalgadaki stil
     * dosyalarının başvuruları (en çok {@value #CSS_DEPTH} kat). Aynı adres bir kez (parçasız anahtar), izlemenin hariç tutma
     * kuralı her yeni adrese uygulanır, planlanan toplam kaynak sayfa başına kaynak tavanıyla sınırlı (aşım → {@code capped}).
     * Süre / bayt bütçesi ve eşzamanlılık kapısı TÜM dalgalarda ortak — klasik tartımla aynı kurallar.
     */
    private Extended weighExtended(PageFetchCore.Inventory inv, String url, String rootHost, int timeoutMs, int concurrency,
                                   String userAgent, Map<String, String> headers, long deadline, boolean viaProxy, long loadStart,
                                   Predicate<String> exclude) {
        int cap = Math.max(1, inv.cap());
        java.util.Set<String> known = new java.util.HashSet<>();
        known.add(com.sitemonitor.service.page.scan.PageResourceDiscovery.key(url));
        List<Planned> all = new ArrayList<>();
        List<Planned> wave = new ArrayList<>();
        boolean[] capped = {false};
        for (PageFetchCore.Resource r : inv.resources()) {
            known.add(com.sitemonitor.service.page.scan.PageResourceDiscovery.key(r.url()));
            Planned p = new Planned(r, 0, null);
            all.add(p);
            wave.add(p);
        }
        String inlineBase = inv.baseUrl() != null ? inv.baseUrl() : url;
        for (String css : inv.inlineCss()) {
            for (var c : com.sitemonitor.service.page.scan.PageResourceDiscovery.cssRefs(css, inlineBase)) {
                plan(c, 0, url, url, known, exclude, all, wave, cap, capped);
            }
        }
        PageFetchCore.FetchOptions countOpts = PageFetchCore.FetchOptions
                .weigh(timeoutMs, userAgent).withHeaders(headers).withProxy(viaProxy);
        PageFetchCore.FetchOptions cssOpts = PageFetchCore.FetchOptions
                .body(timeoutMs, userAgent).withHeaders(headers).counting().withProxy(viaProxy);
        WeighState st = new WeighState(concurrency, maxTotalMeasuredBytes());
        List<Measured> rows = new ArrayList<>();
        for (int depth = 0; !wave.isEmpty() && depth <= CSS_DEPTH; depth++) {
            final int d = depth;
            List<Weighed1> done = weighWave(wave, p -> weighOneExtended(p, rootHost, countOpts, cssOpts, loadStart, d), st, deadline);
            List<Planned> next = new ArrayList<>();
            for (Weighed1 w : done) {
                rows.add(w.row());
                if (w.refs() == null || w.refs().isEmpty() || d >= CSS_DEPTH) continue;
                for (var c : w.refs()) plan(c, d + 1, w.base(), url, known, exclude, all, next, cap, capped);
            }
            wave = next;
        }
        List<PageFetchCore.Resource> planned = new ArrayList<>(all.size());
        for (Planned p : all) planned.add(p.res());
        return new Extended(new Weighed(rows, st.spent.get() >= st.budget, st.timeCut.get()), planned, capped[0]);
    }

    /** Başvuruyu planlar: bilinen / hariç tutulan / tavan dışı adres eklenmez (tavan aşımı işaretlenir). */
    private static void plan(com.sitemonitor.service.page.scan.PageResourceDiscovery.Candidate c, int depth, String foundIn,
                             String pageUrl, java.util.Set<String> known, Predicate<String> exclude, List<Planned> all,
                             List<Planned> out, int cap, boolean[] capped) {
        if (c == null || c.url() == null) return;
        String k = c.url();
        if (known.contains(k)) return;
        if (exclude != null) {
            boolean ex;
            try { ex = exclude.test(k); } catch (RuntimeException e) { ex = false; }
            if (ex) { known.add(k); return; }
        }
        if (all.size() >= cap) { capped[0] = true; return; }
        known.add(k);
        Planned p = new Planned(new PageFetchCore.Resource(k, typeOfCategory(c.category()), pageUrl), depth, foundIn);
        all.add(p);
        out.add(p);
    }

    /** Stil başvurusu kategorisi → envanter türü (kırılım kovası): yazı tipi, görsel, stil, diğer. */
    static String typeOfCategory(String category) {
        if (category == null) return "OTHER";
        return switch (category) {
            case com.sitemonitor.service.page.scan.PageResourceDiscovery.FONT -> "FONT";
            case com.sitemonitor.service.page.scan.PageResourceDiscovery.IMAGE -> "IMG";
            case com.sitemonitor.service.page.scan.PageResourceDiscovery.CSS -> "CSS";
            default -> "OTHER";
        };
    }

    /**
     * Genişletilmiş tartım: stil dosyası (kat izin veriyorsa) gövdesiyle istenir ({@code gzip, deflate} — Brotli çözücü yok),
     * başvuruları yönlendirme SONRASI adrese göre çıkarılır, gövde hemen atılır; diğer her nesne yalnız SAYILIR ve
     * {@code br} ile istenir (tarayıcının telden aldığı bayt).
     */
    private Weighed1 weighOneExtended(Planned p, String rootHost, PageFetchCore.FetchOptions countOpts,
                                      PageFetchCore.FetchOptions cssOpts, long loadStart, int depth) {
        long startMs = Math.max(0L, System.currentTimeMillis() - loadStart);
        PageFetchCore.Resource r = p.res();
        boolean parseCss = "CSS".equalsIgnoreCase(r.type()) && depth < CSS_DEPTH;
        PageFetchCore.Fetch f;
        List<com.sitemonitor.service.page.scan.PageResourceDiscovery.Candidate> refs = null;
        String base = null;
        if (parseCss) {
            PageFetchCore.MetaFetch mf = core.fetchWithMeta(r.url(), "GET", cssOpts, 0, CSS_TUNING);
            f = mf.fetch();
            if (f.body() != null && !f.blocked() && f.status() >= 200 && f.status() < 300) {
                base = mf.meta() != null && mf.meta().finalUrl() != null ? mf.meta().finalUrl() : r.url();
                String text = com.sitemonitor.service.page.scan.PageResourceDiscovery.decodeCssText(f.body(),
                        mf.meta() == null ? null : mf.meta().contentType());
                refs = com.sitemonitor.service.page.scan.PageResourceDiscovery.cssRefs(text, base);
            }
        } else {
            f = core.fetch(r.url(), "GET", countOpts, PageFetchCore.ACCEPT_ENCODING_COUNT);
        }
        boolean thirdParty = !publicSuffixService.sameSite(PageFetchCore.hostOf(r.url()), rootHost);
        boolean failed = f.blocked() || f.status() == 0 || f.status() >= 400;
        Measured row = new Measured(r.url(), r.type(), f.bytes(), f.durationMs(), f.status() == 0 ? null : f.status(),
                thirdParty, failed, f.truncated(), startMs, p.foundIn());
        return new Weighed1(row, refs, base);
    }

    /** Dalgalar arası ortak tartım durumu: eşzamanlılık kapısı, bayt bütçesi, süre tavanı işareti. */
    private static final class WeighState {
        final Semaphore gate;
        final long budget;
        final java.util.concurrent.atomic.AtomicLong spent = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean timeCut = new java.util.concurrent.atomic.AtomicBoolean();

        WeighState(int concurrency, long budget) {
            this.gate = new Semaphore(Math.max(1, concurrency));
            this.budget = budget;
        }
    }

    /**
     * Bir dalgayı sınırlı eşzamanlılıkla, ortak süre ve bayt bütçesine saygılı tartar ({@link #weighAll} ile aynı kurallar:
     * kapının önünde ve içinde süre / bütçe denetimi, toplama döngüsünde kalan süre kadar bekleme — süre dolunca kalanlar
     * bırakılır ve işaretlenir). Asla süresiz beklemez.
     */
    private List<Weighed1> weighWave(List<Planned> wave, java.util.function.Function<Planned, Weighed1> fn, WeighState st,
                                     long deadline) {
        List<CompletableFuture<Weighed1>> futures = new ArrayList<>(wave.size());
        for (Planned p : wave) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (System.currentTimeMillis() > deadline) { st.timeCut.set(true); return null; }
                if (st.spent.get() >= st.budget) return null;
                try {
                    st.gate.acquire();
                    try {
                        if (System.currentTimeMillis() > deadline) { st.timeCut.set(true); return null; }
                        if (st.spent.get() >= st.budget) return null;
                        Weighed1 w = fn.apply(p);
                        st.spent.addAndGet(w.row().bytes());
                        return w;
                    } finally { st.gate.release(); }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }, core.executor()));
        }
        List<Weighed1> out = new ArrayList<>(wave.size());
        for (CompletableFuture<Weighed1> f : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) { st.timeCut.set(true); break; }
            try {
                Weighed1 x = f.get(remaining, TimeUnit.MILLISECONDS);
                if (x != null) out.add(x);
            } catch (java.util.concurrent.TimeoutException te) {
                st.timeCut.set(true);
                break;
            } catch (Exception e) {
                /* bu kaynak düştü → atla, ölçümün geri kalanı geçerli */
            }
        }
        return out;
    }

    /**
     * Tartım sonucu: satırlar + toplam bayt bütçesinin dolup dolmadığı + DUVAR SAATİ tavanının ölçümü kesip kesmediği
     * (madde 4: süre dolunca indirilmemiş kaynaklar sessizce atlanıyordu; toplam "tam" görünüyordu).
     */
    private record Weighed(List<Measured> rows, boolean budgetExhausted, boolean timeCut) {}

    /** Alt kaynakları sınırlı eşzamanlılıkla, deadline'a VE toplam bayt bütçesine saygılı tartar. */
    private Weighed weighAll(List<PageFetchCore.Resource> resources, String rootHost, int timeoutMs,
                             int concurrency, String userAgent, Map<String, String> headers, long deadline,
                             boolean viaProxy, long loadStart) {
        if (resources.isEmpty()) return new Weighed(List.of(), false, false);
        // Sayfa ve alt kaynaklar AYNI yoldan gider (Sayfa Bütünlüğü ile aynı sözleşme).
        PageFetchCore.FetchOptions opts = PageFetchCore.FetchOptions
                .weigh(timeoutMs, userAgent).withHeaders(headers).withProxy(viaProxy);
        Semaphore gate = new Semaphore(concurrency);
        // Tek kontrolün indirebileceği TOPLAM bayt: kaynak başına tavan tek başına yetmiyor
        // (500 × 10 MB = 5 GB). Tek pod'da bant genişliği ve CPU gerçek sınırlar.
        final long budget = maxTotalMeasuredBytes();
        java.util.concurrent.atomic.AtomicLong spent = new java.util.concurrent.atomic.AtomicLong();
        // Süre tavanı bir kaynağı ATLATTI mı (kapıdan önce / kapıda / toplama döngüsünde) — madde 4.
        java.util.concurrent.atomic.AtomicBoolean timeCut = new java.util.concurrent.atomic.AtomicBoolean();
        List<CompletableFuture<Measured>> futures = new ArrayList<>(resources.size());
        for (PageFetchCore.Resource r : resources) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                // Kaba ön kontrol: kapıya boşuna sıraya girmemek için. Bağlayıcı OLAN kontrol
                // aşağıda, kapının İÇİNDE. supplyAsync tüm görevleri hemen kuyruğa attığı için
                // yalnız burada bakmak bütçeyi ETKİSİZ bırakıyordu: hepsi henüz spent=0 iken
                // geçip kapıda sıraya giriyor, sonra sırayla İNDİRİYORdu.
                if (System.currentTimeMillis() > deadline) { timeCut.set(true); return null; }
                if (spent.get() >= budget) return null;
                try {
                    gate.acquire();
                    try {
                        // Kapının İÇİNDE, isteği atmadan hemen önce: en güncel harcamayı görür.
                        // Eşzamanlılık N ise en fazla N-1 kaynak tavanı aşabilir (sınırlı taşma).
                        if (System.currentTimeMillis() > deadline) { timeCut.set(true); return null; }
                        if (spent.get() >= budget) return null;
                        Measured m = weighOne(r, rootHost, opts, loadStart);
                        spent.addAndGet(m.bytes());
                        return m;
                    } finally { gate.release(); }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }, core.executor()));
        }
        List<Measured> out = new ArrayList<>(resources.size());
        for (CompletableFuture<Measured> f : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) { timeCut.set(true); break; }   // deadline doldu → kalanları bırak (kısmi, İŞARETLİ)
            try {
                Measured x = f.get(remaining, TimeUnit.MILLISECONDS);
                if (x != null) out.add(x);
            } catch (java.util.concurrent.TimeoutException te) {
                timeCut.set(true);
                break;
            } catch (Exception e) {
                /* bu kaynak düştü → atla, ölçümün geri kalanı geçerli */
            }
        }
        return new Weighed(out, spent.get() >= budget, timeCut.get());
    }

    /**
     * Tek kaynağın ağırlığı. HEAD KULLANILMAZ — HEAD gövdeyi taşımaz, dolayısıyla kaç bayt indiğini
     * söyleyemez; ağırlık ölçmenin tek yolu GET'tir. (Sayfa Bütünlüğü izlemesi HEAD kullanır çünkü
     * orada soru "var mı", burada "ne kadar".)
     */
    private Measured weighOne(PageFetchCore.Resource r, String rootHost, PageFetchCore.FetchOptions opts, long loadStart) {
        // Şelale (2026-11-15): isteğin ATILDIĞI an, sayfa yükünün başlangıcına göre (saat geri alındıysa 0)
        long startMs = Math.max(0L, System.currentTimeMillis() - loadStart);
        PageFetchCore.Fetch f = core.fetch(r.url(), "GET", opts);
        boolean thirdParty = !publicSuffixService.sameSite(PageFetchCore.hostOf(r.url()), rootHost);
        boolean failed = f.blocked() || f.status() == 0 || f.status() >= 400;
        return new Measured(r.url(), r.type(), f.bytes(), f.durationMs(),
                f.status() == 0 ? null : f.status(), thirdParty, failed, f.truncated(), startMs);
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
    /**
     * Bir kontrolde indirilecek TOPLAM bayt tavanı — ops ayarlanabilir, varsayılanı
     * {@link PageFetchCore#MAX_TOTAL_MEASURED_BYTES}. Yanlış/çok küçük bir ayar ölçümü tamamen kör
     * etmesin diye 64 KB tabanı var; tavan aşılırsa kalan kaynaklar atlanır ve ölçüm "alt sınır"
     * olarak işaretlenir (sessizce eksik sayılmaz).
     */
    private long maxTotalMeasuredBytes() {
        int kb = appSettings.getInt("site.monitor.pagespeed.max-total-kb",
                (int) (PageFetchCore.MAX_TOTAL_MEASURED_BYTES / 1024));
        return Math.max(64L * 1024L, kb * 1024L);
    }

    private int maxCheckSeconds() {
        return Math.max(10, appSettings.getInt("site.monitor.pagespeed.max-check-seconds", 120));
    }

    /** Sayfa başına kaynak tavanı — envanterle aynı canlı ayar (tanılamanın "kısmi ölçüm" bulgusu bunu yazar). */
    public int maxResourcesPerCheck() {
        return PageFetchCore.maxResourcesPerCheck(appSettings);
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
