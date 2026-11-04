package com.sitemonitor.service.page;

import com.sitemonitor.service.SafeRedirect;
import com.sitemonitor.service.SsrfGuard;
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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * Sayfa çekme çekirdeği — HTML/alt-kaynak indirmenin ve kaynak envanteri çıkarmanın TEK yeri.
 *
 * <p>İki tüketicisi vardır ve amaçları farklıdır:
 * <ul>
 *   <li>{@code PageCheckerService} (Sayfa Bütünlüğü) — kaynak SAĞLIKLI mı (kırık/mixed/yavaş)</li>
 *   <li>{@code PageSpeedCheckerService} (Sayfa Hızı) — kaynak NE KADAR AĞIR ve NE KADAR SÜRDÜ</li>
 * </ul>
 *
 * <p>Ortak olan şey ağ davranışıdır: trust-all TLS, MANUEL redirect takibi (her hop
 * {@link SsrfGuard#validate(String)}'ten geçsin — DNS-rebind/redirect-SSRF kapanır), gövde tavanı,
 * jsoup kaynak envanteri. Bu davranış iki yerde ayrı ayrı yaşarsa biri düzeltilip diğeri unutulur;
 * güvenlik kuralı olduğu için tek kopya şart.
 *
 * <p><b>Proxy kullanılmaz</b> — diğer checker'larla aynı (pod'dan doğrudan). Sayfa Hızı için bu ayrıca
 * bir DOĞRULUK meselesidir: proxy üzerinden geçen ölçüm proxy'nin gecikmesini sayfaya fatura eder.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageFetchCore {

    /** Ana sayfa gövde okuma tavanı (OOM koruması + hash için yeterli). */
    public static final int MAX_BODY_BYTES = 2_000_000;
    /**
     * Tek SAYFANIN işlenecek azami (tekil) kaynağı — tek-pod yük koruması; aşılırsa WARN + kırpılır. CANLI ayar
     * {@value #MAX_RESOURCES_KEY} ({@link #maxResourcesPerCheck(AppSettingsService)}). 2026-10-30: sabit 500'dü; izlenen büyük bir ana
     * sayfası (~520 kaynak: 340 bağlantı, 150 görsel) her turda tavana takılıp kalan kaynakları hiç denetlemiyordu → varsayılan
     * {@value #DEFAULT_MAX_RESOURCES_PER_CHECK}. Doğrulamanın asıl sınırı yine kontrolün duvar-saati süresidir
     * ({@code site.monitor.page.max-check-seconds}, eşzamanlılık {@code site.monitor.page.resource-concurrency}).
     */
    public static final String MAX_RESOURCES_KEY = "site.monitor.page.max-resources";
    public static final int DEFAULT_MAX_RESOURCES_PER_CHECK = 2000;
    public static final int MIN_RESOURCES_PER_CHECK = 100;
    public static final int MAX_RESOURCES_PER_CHECK_LIMIT = 10_000;
    /** Bir CRAWL genelinde toplam azami kaynağın TABANI — gerçek tavan {@link #maxTotalResources(int)} (bellek + DB-insert). */
    public static final int MIN_TOTAL_RESOURCES = 1500;
    /** Manuel redirect zinciri üst sınırı. (public: uçtan uca tanılamanın ham ölçümü aynı sınırı uygular, 2026-10-05) */
    public static final int MAX_REDIRECTS = 5;

    /**
     * Her hop'ta gönderilen tarayıcı-benzeri başlıklar — TEK kopya: çekim ({@link #fetch}) ve Sayfa Bütünlüğü / Sayfa Hızı
     * uçtan uca tanılamasının ham ölçümü (2026-10-05) aynı değerleri gönderir; ayrışırsa tanı başka bir isteği anlatır.
     */
    public static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    public static final String ACCEPT_LANGUAGE = "tr,en;q=0.9";
    public static final String ACCEPT_ENCODING = "gzip, deflate";
    /**
     * Gövdesi AYRIŞTIRILMAYAN (yalnız sayılan) çekimlerin başlığı — kaynak analizi (2026-11): tarayıcı {@code br} ister ve
     * Brotli veren CDN'lerde tel baytı gzip'ten küçüktür. Java'da Brotli çözücü yok; bu yüzden YALNIZ sayım yolunda istenir
     * (HTML ve CSS ayrıştırılacağı için {@link #ACCEPT_ENCODING} ile istenir). İzleme çekimleri bu başlığı göndermez.
     */
    public static final String ACCEPT_ENCODING_COUNT = "gzip, deflate, br";
    /** Önbellek isabet başlığı adayları (sırayla ilk dolu olan). */
    private static final List<String> X_CACHE_HEADERS = List.of("x-cache", "cf-cache-status", "x-cache-status", "x-proxy-cache");

    /**
     * Kaynak analizinin TARAMA BAŞINA ayrı istemcileri (2026-11, inceleme H10/H11): izleme kontrollerinin bağlantı havuzunu
     * paylaşmaz (sıcak / soğuk havuz ölçümü oynatmasın, tarama aynı host'a giden izlemeyi yavaşlatmasın) ve
     * {@link #shutdownNow()} uçuştaki TÜM istekleri keser (iptal / süre dolumu / kapanış). Güvenlik kuralı aynıdır: aynı
     * trust-all bağlamı, MANUEL yönlendirme (her adım SSRF'den geçer), vekil seçicisi ve kimliği.
     */
    public static final class ScanClients implements AutoCloseable {
        private final HttpClient direct;
        private final HttpClient proxied;

        ScanClients(HttpClient direct, HttpClient proxied) {
            this.direct = direct;
            this.proxied = proxied;
        }

        HttpClient pick(boolean viaProxy) {
            return viaProxy && proxied != null ? proxied : direct;
        }

        /** Uçuştaki istekleri keser; tekrar çağrılabilir, asla fırlatmaz. */
        public void shutdownNow() {
            try { direct.shutdownNow(); } catch (RuntimeException ignore) { /* kapatma hatası önemsiz */ }
            if (proxied != null) {
                try { proxied.shutdownNow(); } catch (RuntimeException ignore) { /* kapatma hatası önemsiz */ }
            }
        }

        @Override
        public void close() {
            shutdownNow();
        }
    }

    /**
     * Kaynak analizi çekim ayarı: hangi istemciler, hangi {@code Accept-Encoding}, saklanan gövde ve açılmış gövde tavanı
     * (bellek tepesi: stil gövdesi taramanın karakter sınırına göre açılır — inceleme H15). {@code null} = izleme davranışı.
     */
    public record ScanTuning(ScanClients clients, String acceptEncoding, int maxBodyBytes, int maxDecodedBytes,
                             boolean hash, int sampleHead, int sampleTail, boolean pageHeaders) {
        /** Algoritma 2 ayarı: özet / örnek / sayfa başlığı yakalama yok. */
        public ScanTuning(ScanClients clients, String acceptEncoding, int maxBodyBytes, int maxDecodedBytes) {
            this(clients, acceptEncoding, maxBodyBytes, maxDecodedBytes, false, 0, 0, false);
        }

        /** Aynı ayar + derin yakalama (algoritma 3): gövde özeti, betik örneği (baş / son bayt), ana sayfa başlıkları. */
        public ScanTuning capture(boolean hash, int sampleHead, int sampleTail, boolean pageHeaders) {
            return new ScanTuning(clients, acceptEncoding, maxBodyBytes, maxDecodedBytes, hash, Math.max(0, sampleHead),
                    Math.max(0, sampleTail), pageHeaders);
        }
    }

    /**
     * Kaynak analizinin derin yakalaması (2026-11, algoritma 3) — YALNIZ ayarlı ({@link ScanTuning}) çekimde dolar; izleme
     * çekimi bunu hiç üretmez (yolu bayt-özdeş).
     *
     * @param httpVersion    son yanıtın HTTP sürümü ({@code 1.1} | {@code 2})
     * @param contentHash    tel gövdesinin SHA-256'sı (onaltılık); gövde tam okunmadıysa / istenmediyse null
     * @param sampleHead     çözülmüş gövdenin ilk baytları (betik örneği); istenmediyse / okunamadıysa null
     * @param sampleTail     çözülmüş gövdenin SON baytları — yalnız gövde sonuna dek çözüldüyse; yoksa null
     * @param sampleComplete çözülmüş gövdenin TAMAMI {@code sampleHead}'de
     * @param sampleSkipped  örnek neden yok: {@code encoding} (br / bilinmeyen kodlama), {@code decode} (bozuk akış),
     *                       {@code truncated} (kesildi); varsa null
     * @param cookies        tüm adımlardaki {@code Set-Cookie}'ler — DEĞERİ ÇEKİRDEKTE ATILMIŞ ({@code ad=; öznitelikler})
     * @param pageHeaders    seçili güvenlik başlıkları (küçük harfli ad → değerler); istenmediyse null
     */
    public record ScanMeta(String httpVersion, String etag, String lastModified, String age, String altSvc,
                           String sourceMapHeader, String contentHash, byte[] sampleHead, byte[] sampleTail,
                           boolean sampleComplete, String sampleSkipped, List<SeenCookie> cookies,
                           Map<String, List<String>> pageHeaders) {}

    /** Görülen çerez başlığı: hangi host'tan + DEĞERSİZ başlık ({@code ad=; Secure; HttpOnly; …}). */
    public record SeenCookie(String host, String header) {}

    /** Ana sayfadan yakalanan güvenlik başlıkları (küçük harf). */
    public static final List<String> PAGE_HEADERS = List.of("content-security-policy", "content-security-policy-report-only",
            "strict-transport-security", "x-content-type-options", "x-frame-options", "referrer-policy", "permissions-policy",
            "feature-policy", "cross-origin-opener-policy", "cross-origin-resource-policy", "x-robots-tag");
    /** Çekim başına tutulan en çok çerez başlığı. */
    static final int MAX_COOKIES = 50;
    /** Sayfa başlığı değerinin tavanı (CSP uzun olabilir; yalnız bellekte, değerlendirme için). */
    static final int PAGE_HEADER_MAX = 8192;
    /** Örnek için çözülecek en çok bayt (sıkıştırma bombası: bunun ötesi çözülmeden yalnız sayılır). */
    static final long MAX_SAMPLE_DECODED = 64L * 1024 * 1024;
    /** Bayt SAYARKEN tek kaynaktan okunacak tavan: dev bir dosya (video/ISO) ölçümü kilitlemesin.
     *  Tavana ulaşılırsa sayım burada durur — "en az bu kadar" demektir, truncated bayrağı işaretler. */
    // 1024 tabanlı: arayüz boyutları KB/MB olarak 1024 tabanıyla gösteriyor; ondalık 10.000.000
    // bırakılsaydı kırpılan satır "≥ 9,5 MB" gibi tuhaf bir sayı gösterirdi.
    public static final long MAX_COUNT_BYTES = 10L * 1024 * 1024;
    /**
     * Bir kontrolde indirilecek TOPLAM bayt tavanı.
     *
     * <p>Kaynak başına tavan tek başına yetmiyor: 500 kaynak × 10 MB teorik olarak 5 GB eder ve
     * tek sınır duvar-saati deadline'ı kalırdı. Tek pod, 100 eşzamanlı kullanıcıya hizmet veriyor;
     * bir ölçümün yüzlerce MB çekmesi hem bant genişliğini hem CPU'yu yer. Tavana ulaşılınca kalan
     * kaynaklar ATLANIR ve ölçüm "alt sınır" olarak işaretlenir — sessizce eksik sayılmaz.
     */
    public static final long MAX_TOTAL_MEASURED_BYTES = 150L * 1024 * 1024;

    private final SsrfGuard ssrfGuard;
    // Vekilli eş (2026-09-21): Sayfa Bütünlüğü izlemesi "vekil üzerinden" istiyorsa; alan enjeksiyonu (yapıcı testlerde elle).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.ProxySettings proxySettings;
    private HttpClient proxiedClient;
    // Kaynak tavanı canlı okunur (2026-10-30); yapıcı testlerinde yok → varsayılan tavan.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.AppSettingsService appSettings;

    private HttpClient httpClient;
    /** Kaynak fan-out'u için sanal-thread executor (I/O-bound; eşzamanlılık çağıran tarafta Semaphore ile sınırlanır). */
    private ExecutorService resourceExecutor;

    // ── Sonuç tipleri ────────────────────────────────────────────────────────

    /**
     * Tek bir çekim sonucu.
     *
     * @param status      HTTP durum kodu; transport hatasında 0
     * @param ttfbMs      isteğin başından SON yanıtın başlıkları gelene kadar (redirect zinciri dahil)
     * @param durationMs  isteğin başından gövde tamamlanana kadar — ttfbMs bunun içindedir
     * @param bytes       gövde bayt sayısı; yalnız wantBody veya countBytes istendiyse anlamlı
     * @param body        gövde içeriği; yalnız wantBody istendiyse dolu
     * @param error       transport hatası mesajı (varsa)
     * @param blocked     SSRF muhafızı isteği ENGELLEDİ (dış istek hiç atılmadı)
     * @param truncated   bayt sayımı MAX_COUNT_BYTES tavanında kesildi
     * @param failure     aktarım hatasının SINIFLANDIRMASI (2026-10-05, hata teşhisi) — istisnanın kendisinden, yakalandığı
     *                    yerde; engelleme / yönlendirme sınırı da dahil. Başarılı (HTTP yanıtı gelen) çekimde null. Yalnız
     *                    üst veri: status/error/blocked anlamı DEĞİŞMEDİ.
     */
    public record Fetch(int status, long ttfbMs, long durationMs, long bytes, byte[] body,
                        String error, boolean blocked, boolean truncated,
                        com.sitemonitor.service.failure.CheckFailure failure) {

        /** Geriye uyum: sınıflandırmasız (yanıt gelen çekim). */
        public Fetch(int status, long ttfbMs, long durationMs, long bytes, byte[] body,
                     String error, boolean blocked, boolean truncated) {
            this(status, ttfbMs, durationMs, bytes, body, error, blocked, truncated, null);
        }
    }

    /** HTML'den çıkarılmış tek bir alt kaynak. */
    public record Resource(String url, String type, String sourcePage) {}

    /**
     * Çekimin üst verisi (2026-10-31, Sayfa Bütünlüğü "Kaynak analizi") — SON yanıtın başlıkları, yönlendirme sonrası URL,
     * yönlendirme sayısı ve (istenirse) gövdenin ilk baytları (görsel boyutu okumak için). Yalnız {@link #fetchWithMeta}
     * doldurur; {@link #fetch} davranışı değişmedi.
     *
     * @param finalUrl        son isteğin URL'si (yönlendirme yoksa istenen URL); hiç yanıt gelmediyse null
     * @param redirects       izlenen yönlendirme sayısı
     * @param contentLength   {@code Content-Length} (telden gelen, sıkıştırılmış boyut); yoksa / bozuksa null
     * @param head            gövdenin ilk baytları (telden, en çok istenen kadar); istenmediyse null
     * @param server          {@code Server} başlığı (yalnız gösterim; sınırlı)
     * @param xCache          önbellek isabet başlığı ({@code X-Cache}, yoksa {@code CF-Cache-Status} / {@code X-Cache-Status}
     *                        / {@code X-Proxy-Cache}); yoksa null
     * @param redirectMs      yönlendirme ADIMLARININ toplam süresi (2026-11, kaynak analizi algoritma 2 — her adımın isteği +
     *                        başlığı + atılan gövdesi; SSRF denetimi düşülür); yönlendirme yoksa 0
     * @param finalTtfbMs     YALNIZ son adımın ilk baytı (son isteğin başından başlıklarına; yönlendirmeler hariç); son
     *                        adıma hiç yanıt gelmediyse null. {@link Fetch#ttfbMs()} zincirin tamamını ölçmeye devam eder.
     */
    public record Meta(String finalUrl, int redirects, String contentType, String contentEncoding, Long contentLength,
                       String cacheControl, String expires, String date, byte[] head, String server, String xCache,
                       long redirectMs, Long finalTtfbMs, ScanMeta scan) {
        public static final Meta NONE = new Meta(null, 0, null, null, null, null, null, null, null);

        /** Algoritma 2 alanları (derin yakalama yok). */
        public Meta(String finalUrl, int redirects, String contentType, String contentEncoding, Long contentLength,
                    String cacheControl, String expires, String date, byte[] head, String server, String xCache,
                    long redirectMs, Long finalTtfbMs) {
            this(finalUrl, redirects, contentType, contentEncoding, contentLength, cacheControl, expires, date, head, server,
                    xCache, redirectMs, finalTtfbMs, null);
        }

        /** Geriye uyum (algoritma 1 alanları): sunucu / önbellek başlığı ve adım süreleri yok. */
        public Meta(String finalUrl, int redirects, String contentType, String contentEncoding, Long contentLength,
                    String cacheControl, String expires, String date, byte[] head) {
            this(finalUrl, redirects, contentType, contentEncoding, contentLength, cacheControl, expires, date, head,
                    null, null, 0L, null);
        }
    }

    /** {@link #fetchWithMeta} sonucu: çekimin kendisi + üst verisi. */
    public record MetaFetch(Fetch fetch, Meta meta) {}

    /** Üst veri toplayıcısı — yalnız {@link #fetchWithMeta} çağrısının kendi yığınında yaşar (paylaşılmaz). */
    private static final class MetaSink {
        final int headBytes;
        String finalUrl;
        int redirects;
        java.net.http.HttpHeaders headers;
        byte[] head;
        long redirectMs;
        Long finalTtfbMs;
        // ── derin yakalama (yalnız ayarlı çekim) ──
        boolean deep;
        HttpClient.Version version;
        String contentHash;
        byte[] sampleHead, sampleTail;
        boolean sampleComplete;
        String sampleSkipped;
        List<SeenCookie> cookies;
        Map<String, List<String>> pageHeaders;

        MetaSink(int headBytes) { this.headBytes = Math.max(0, headBytes); }

        /** Bir adımın {@code Set-Cookie}'leri — değer HEMEN atılır. */
        void addCookies(String host, java.net.http.HttpHeaders h) {
            for (String v : h.allValues("set-cookie")) {
                if (cookies == null) cookies = new ArrayList<>();
                if (cookies.size() >= MAX_COOKIES) return;
                String stripped = stripCookieValue(v);
                if (stripped != null) cookies.add(new SeenCookie(host == null ? null : host.toLowerCase(Locale.ROOT), stripped));
            }
        }

        /** Ana sayfanın güvenlik başlıkları (seçili adlar, değer başına ≤ {@value #PAGE_HEADER_MAX}, ad başına ≤ 5 değer). */
        void capturePageHeaders(java.net.http.HttpHeaders h) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            for (String name : PAGE_HEADERS) {
                List<String> vals = h.allValues(name);
                if (vals.isEmpty()) continue;
                List<String> kept = new ArrayList<>();
                for (String v : vals) {
                    if (kept.size() >= 5 || v == null) break;
                    String t = v.trim();
                    kept.add(t.length() > PAGE_HEADER_MAX ? t.substring(0, PAGE_HEADER_MAX) : t);
                }
                m.put(name, List.copyOf(kept));
            }
            pageHeaders = m;
        }

        ScanMeta scanMeta() {
            if (!deep) return null;
            String ver = version == null ? null : version == HttpClient.Version.HTTP_2 ? "2" : "1.1";
            java.net.http.HttpHeaders h = headers;
            String sm = h == null ? null : clip(h.firstValue("sourcemap").orElse(h.firstValue("x-sourcemap").orElse(null)));
            return new ScanMeta(ver, h == null ? null : clip(h.firstValue("etag").orElse(null)),
                    h == null ? null : clip(h.firstValue("last-modified").orElse(null)),
                    h == null ? null : clip(h.firstValue("age").orElse(null)),
                    h == null ? null : clip(h.firstValue("alt-svc").orElse(null)), sm, contentHash, sampleHead, sampleTail,
                    sampleComplete, sampleSkipped, cookies == null ? List.of() : List.copyOf(cookies), pageHeaders);
        }

        Meta toMeta() {
            if (headers == null) {
                return new Meta(finalUrl, redirects, null, null, null, null, null, null, head, null, null, redirectMs, finalTtfbMs,
                        scanMeta());
            }
            Long len = null;
            String cl = headers.firstValue("content-length").orElse(null);
            if (cl != null) {
                try { long v = Long.parseLong(cl.trim()); if (v >= 0) len = v; } catch (NumberFormatException ignore) { /* bozuk başlık */ }
            }
            String xCache = null;
            for (String h : X_CACHE_HEADERS) {
                xCache = clip(headers.firstValue(h).orElse(null));
                if (xCache != null && !xCache.isEmpty()) break;
                xCache = null;
            }
            return new Meta(finalUrl, redirects, clip(headers.firstValue("content-type").orElse(null)),
                    clip(headers.firstValue("content-encoding").orElse(null)), len,
                    clip(headers.firstValue("cache-control").orElse(null)), clip(headers.firstValue("expires").orElse(null)),
                    clip(headers.firstValue("date").orElse(null)), head, clip(headers.firstValue("server").orElse(null)),
                    xCache, redirectMs, finalTtfbMs, scanMeta());
        }

        /** Başlık değerleri sınırlı tutulur (dev bir başlık belleği / DB'yi şişirmesin). */
        private static String clip(String v) {
            if (v == null) return null;
            String t = v.trim();
            return t.length() > 300 ? t.substring(0, 300) : t;
        }
    }

    /**
     * Çekim seçenekleri.
     *
     * @param timeoutMs     istek başına zaman aşımı (alt sınır 1000 ms)
     * @param userAgent     gönderilecek User-Agent
     * @param extraHeaders  ek istek başlıkları (DNT, Authorization, kullanıcı tanımlı) — null olabilir
     * @param wantBody      gövde MAX_BODY_BYTES'a kadar SAKLANSIN mı (parse edilecekse)
     * @param countBytes    gövde saklanmadan SAYILSIN mı (ağırlık ölçümü) — wantBody ile birlikte de kullanılabilir
     */
    public record FetchOptions(int timeoutMs, String userAgent, Map<String, String> extraHeaders,
                               boolean wantBody, boolean countBytes, boolean viaProxy) {

        /** Geriye uyum: vekilsiz. */
        public FetchOptions(int timeoutMs, String userAgent, Map<String, String> extraHeaders, boolean wantBody, boolean countBytes) {
            this(timeoutMs, userAgent, extraHeaders, wantBody, countBytes, false);
        }

        /** Kurumsal vekil üzerinden (karar ProxyPolicyService'te). */
        public FetchOptions withProxy(boolean v) {
            return new FetchOptions(timeoutMs, userAgent, extraHeaders, wantBody, countBytes, v);
        }

        /** Gövdesi parse edilecek çekim (ana sayfa). */
        public static FetchOptions body(int timeoutMs, String userAgent) {
            return new FetchOptions(timeoutMs, userAgent, null, true, false);
        }

        /** Yalnız ulaşılabilirlik yoklaması — gövde ne saklanır ne sayılır (Sayfa Bütünlüğü deseni). */
        public static FetchOptions probe(int timeoutMs, String userAgent) {
            return new FetchOptions(timeoutMs, userAgent, null, false, false);
        }

        /** Ağırlık ölçümü — gövde saklanmaz, yalnız baytı sayılır (Sayfa Hızı deseni). */
        public static FetchOptions weigh(int timeoutMs, String userAgent) {
            return new FetchOptions(timeoutMs, userAgent, null, false, true);
        }

        public FetchOptions withHeaders(Map<String, String> h) {
            return new FetchOptions(timeoutMs, userAgent, h, wantBody, countBytes, viaProxy);
        }

        public FetchOptions counting() {
            return new FetchOptions(timeoutMs, userAgent, extraHeaders, wantBody, true, viaProxy);
        }
    }

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
            log.warn("Sayfa çekme çekirdeği trust-all SSL kurulamadı, varsayılan kullanılacak: {}", e.getMessage());
        }
        httpClient = b.build();
        if (proxySettings != null && proxySettings.enabled()) {
            java.net.Authenticator auth = proxySettings.authenticator(log, "Page fetch");
            HttpClient.Builder pb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).proxy(proxySettings.proxySelector());
            try { pb.sslContext(httpClient.sslContext()); } catch (Exception ignore) { /* varsayılan güven */ }
            if (auth != null) pb.authenticator(auth);
            proxiedClient = pb.build();
        }
        resourceExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Kaynak analizi için YENİ istemci çifti (tarama başına; çağıran kapatır). Ortak istemciyle aynı güven bağlamı ve vekil
     * seçicisi; bağlantı havuzu ayrıdır.
     */
    public ScanClients newScanClients() {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        try { if (httpClient != null) b.sslContext(httpClient.sslContext()); } catch (Exception ignore) { /* varsayılan güven */ }
        HttpClient direct = b.build();
        HttpClient proxied = null;
        if (proxySettings != null && proxySettings.enabled()) {
            java.net.Authenticator auth = proxySettings.authenticator(log, "Page resource scan");
            HttpClient.Builder pb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).proxy(proxySettings.proxySelector());
            try { if (httpClient != null) pb.sslContext(httpClient.sslContext()); } catch (Exception ignore) { /* varsayılan güven */ }
            if (auth != null) pb.authenticator(auth);
            proxied = pb.build();
        }
        return new ScanClients(direct, proxied);
    }

    @PreDestroy
    public void shutdown() {
        if (resourceExecutor != null) resourceExecutor.shutdownNow();
    }

    /** Kaynak doğrulama/ölçüm fan-out'u için paylaşılan sanal-thread executor. */
    public ExecutorService executor() { return resourceExecutor; }

    /**
     * Bağlantı kurulum ölçümü (2026-11, kaynak analizi algoritma 3 — "host bağlantı süreleri"): DNS çözümü (SSRF muhafızının
     * çözümüyle — engelli adres hiç bağlanılmaz), doğrulanmış İLK adrese TCP bağlantısı ({@link com.sitemonitor.service.NetworkResolver},
     * yeniden çözmez → DNS-rebind kapalı) ve istenirse TLS el sıkışması (izleme çekimiyle aynı güven bağlamı, SNI). Her adım
     * {@code timeoutMs} ile sınırlı; asla fırlatmaz. Vekil kararı ÇAĞIRANDA (vekil üzerinden gidilen host ölçülmez).
     *
     * @param error {@code blocked | dns_failed | connect_failed | tls_failed}; başarıda null
     */
    public record HostConnect(Long dnsMs, Long connectMs, Long tlsMs, String error) {}

    public HostConnect measureConnection(String host, int port, boolean tls, int timeoutMs) {
        int t = Math.max(500, timeoutMs);
        long d0 = System.nanoTime();
        List<java.net.InetAddress> addrs;
        try {
            addrs = ssrfGuard.validate(host);
        } catch (SsrfGuard.UnresolvableHostException e) {
            return new HostConnect(null, null, null, "dns_failed");
        } catch (SsrfGuard.BlockedException e) {
            return new HostConnect(null, null, null, "blocked");
        } catch (RuntimeException e) {
            return new HostConnect(null, null, null, "dns_failed");
        }
        long dnsMs = (System.nanoTime() - d0) / 1_000_000L;
        long c0 = System.nanoTime();
        java.net.Socket socket;
        try {
            socket = com.sitemonitor.service.NetworkResolver.connectFirstReachable(addrs, port, t);
        } catch (Exception e) {
            return new HostConnect(dnsMs, null, null, "connect_failed");
        }
        long connectMs = (System.nanoTime() - c0) / 1_000_000L;
        if (!tls) {
            closeQuietly(socket);
            return new HostConnect(dnsMs, connectMs, null, null);
        }
        long t0 = System.nanoTime();
        try {
            socket.setSoTimeout(t);
            SSLContext ctx = httpClient != null ? httpClient.sslContext() : SSLContext.getDefault();
            try (javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) ctx.getSocketFactory().createSocket(socket, host, port, true)) {
                ssl.setSoTimeout(t);
                ssl.startHandshake();
            }
            return new HostConnect(dnsMs, connectMs, (System.nanoTime() - t0) / 1_000_000L, null);
        } catch (Exception e) {
            return new HostConnect(dnsMs, connectMs, null, "tls_failed");
        } finally {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(java.net.Socket s) {
        try { s.close(); } catch (Exception ignore) { /* kapatma hatası önemsiz */ }
    }

    // ── Manuel redirect takipli fetch (her hop SSRF'den geçer) ───────────────

    /**
     * Bir URL'i çeker; redirect'leri MANUEL takip eder ve her hop'ta SSRF muhafızını çalıştırır.
     * Hiçbir zaman exception fırlatmaz — hata Fetch.error()/Fetch.blocked() olarak döner.
     */
    public Fetch fetch(String url, String method, FetchOptions opts) {
        return fetch(url, method, opts, null, null);
    }

    /**
     * {@link #fetch} + üst veri (2026-10-31, kaynak analizi): son yanıtın başlıkları, son URL, yönlendirme sayısı ve
     * {@code headBytes > 0} ise gövdenin ilk baytları (yalnız SAYIM yolunda — {@code countBytes} ve {@code !wantBody}).
     * Ağ davranışı, sınırlar ve SSRF kuralı {@link #fetch} ile birebir aynıdır. Hiçbir zaman fırlatmaz.
     */
    public MetaFetch fetchWithMeta(String url, String method, FetchOptions opts, int headBytes) {
        return fetchWithMeta(url, method, opts, headBytes, null);
    }

    /**
     * {@link #fetchWithMeta(String, String, FetchOptions, int)} + kaynak analizi ayarı ({@link ScanTuning}): tarama başına
     * istemci, {@code Accept-Encoding}, saklanan / açılmış gövde tavanı. {@code tuning == null} → izleme davranışı birebir.
     * Ayarlı çağrıda kesilen iş parçacığının kesme bayrağı geri konur (çağıran sonucu atabilsin).
     */
    public MetaFetch fetchWithMeta(String url, String method, FetchOptions opts, int headBytes, ScanTuning tuning) {
        MetaSink sink = new MetaSink(headBytes);
        Fetch f = fetch(url, method, opts, sink, tuning);
        return new MetaFetch(f, sink.toMeta());
    }

    private Fetch fetch(String url, String method, FetchOptions opts, MetaSink sink, ScanTuning tuning) {
        long start = System.currentTimeMillis();
        // Kendi SSRF/DNS kontrolumuzun suresi TTFB'ye YAZILMAZ: hedefin yavasligi degil bizim
        // guvenlik kapimizin maliyetidir. Olcum penceresinden dusulur (her hop icin toplanir).
        long guardMs = 0L;
        final int maxBody = tuning == null ? MAX_BODY_BYTES : Math.max(1, tuning.maxBodyBytes());
        final int maxDecoded = tuning == null ? MAX_DECODED_BYTES : Math.max(1, tuning.maxDecodedBytes());
        final String acceptEncoding = tuning == null || tuning.acceptEncoding() == null ? ACCEPT_ENCODING : tuning.acceptEncoding();
        String current = url;
        String m = method;
        // Ek başlıklar YALNIZ ilk host'a gider. extraHeaders pratikte sır taşıyor — Sayfa Hızı
        // izlemesinin Basic Auth parolası buradan geçiyor (PageSpeedCheckerService.buildHeaders,
        // decryptSecret ile çözülmüş hâli) ve RESERVED_HEADERS bunu kapsamıyor. Başlık hop
        // döngüsünün içinde uygulandığı için, izlenen adres 302 ile yabancı bir host'a
        // yönlendirdiğinde parola o host'a aynen gidiyordu. Tarayıcıların cross-origin
        // yönlendirmede Authorization düşürmesiyle aynı kural. (Düşürmenin KENDİSİ bu sınıfta
        // bilinçli olarak takip edilmeye devam ediyor — SafeRedirect.isDowngrade javadoc'u.)
        final String originHost = hostOf(url) == null ? "" : hostOf(url);
        // Derin yakalama YALNIZ ayarlı (kaynak analizi) çekimde; izleme çekiminde hiçbir ek iş yok (bayt-özdeş yol).
        final boolean deep = sink != null && tuning != null;
        if (deep) sink.deep = true;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                String host = hostOf(current);
                // Adım süresi (kaynak analizi, algoritma 2): yönlendirme adımları ve son adımın ilk baytı AYRI ölçülür.
                long hopStart = System.currentTimeMillis();
                long hopGuard = 0L;
                try {
                    if (host == null) throw new SsrfGuard.BlockedException("geçersiz URL: " + current);
                    // SSRF (her hop). NOT (M4 residual): validate() çözülen IP'leri döndürür ama HttpClient host'u
                    // yeniden çözer → TOCTOU/DNS-rebind penceresi. Metadata/loopback/link-local HER ZAMAN bloklu +
                    // JVM pozitif-DNS cache pratik riski azaltır; IP-pinning (NetworkResolver) bilinçli uygulanmadı
                    // (HTTPS SNI karmaşası + kaynak-başı maliyet). Ops: networkaddress.cache.ttl'i 0'a çekmeyin.
                    long g0 = System.currentTimeMillis();
                    ssrfGuard.validate(host);
                    hopGuard = System.currentTimeMillis() - g0;
                    guardMs += hopGuard;
                } catch (SsrfGuard.BlockedException be) {
                    return new Fetch(0, 0L, System.currentTimeMillis() - start, 0L, null, be.getMessage(), true, false,
                            classified(be, opts, current));
                }
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(current))
                        .timeout(Duration.ofMillis(Math.max(1000, opts.timeoutMs())))
                        // Tarayıcı-benzeri header seti: katı sunucular Accept/Accept-Language yoksa 406/403 döner.
                        .header("User-Agent", opts.userAgent())
                        .header("Accept", ACCEPT)
                        .header("Accept-Language", ACCEPT_LANGUAGE)
                        // Java HttpClient bu basligi KENDILIGINDEN EKLEMEZ ve otomatik ACMAZ.
                        // Gondermeyince sunucu sikistirmasiz yaniyor: arayuz "transfer boyutu"
                        // diyordu ama olculen sey acilmis boyuttu (44.6 MB ↔ tarayicida 6.1 MB).
                        // Artik telde ne gidiyorsa o sayiliyor; govde YALNIZ ayristirmak icin aciliyor.
                        .header("Accept-Encoding", acceptEncoding);
                if (originHost.equalsIgnoreCase(host)) applyExtraHeaders(rb, opts.extraHeaders());
                HttpRequest req = "HEAD".equals(m)
                        ? rb.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
                        : rb.GET().build();
                HttpClient c = tuning != null && tuning.clients() != null
                        ? tuning.clients().pick(opts.viaProxy())
                        : (opts.viaProxy() && proxiedClient != null ? proxiedClient : httpClient);
                HttpResponse<InputStream> resp = c.send(req, HttpResponse.BodyHandlers.ofInputStream());
                // send() başlıklar geldiğinde döner → ilk-bayt anı burasıdır (gövde henüz okunmadı).
                long headersAt = System.currentTimeMillis();
                long ttfb = Math.max(0L, headersAt - start - guardMs);
                long hopTtfb = Math.max(0L, headersAt - hopStart - hopGuard);
                int sc = resp.statusCode();
                if (deep) sink.addCookies(host, resp.headers());
                // Gövde SÜRE bütçesi (prod kapısı 2026-09-25, N1): istek zaman aşımı yalnız başlıklara kadar
                // işliyor; gövdeyi bitirmeyen bir kaynak (MJPEG <img>, SSE) okuyan iş parçacığını ve soketi
                // süresiz tutuyor, sayfa kontrolünün deadline'ı dolunca da SIZIYORDU. Gövde başlık süresi
                // kadar daha beklenir.
                long bodyMs = Math.max(1000, opts.timeoutMs());
                if (sc >= 300 && sc < 400) {
                    String loc = resp.headers().firstValue("location").orElse(null);
                    try (InputStream is = com.sitemonitor.util.HttpBodies.withDeadline(resp.body(), bodyMs, "Sayfa")) {
                        is.readNBytes(4096);
                    } catch (Exception ignore) { /* gövde iadesi */ }
                    // Hedef yok / http(s) DIŞI şema (file:, gopher:) / host'suz → olduğu gibi dön.
                    // Politika SafeRedirect'te tek kopya: aynı kural keyword/HTTP/HSTS için de geçerli.
                    String next = SafeRedirect.nextHop(current, loc);
                    if (next == null) {
                        if (sink != null) { sink.finalUrl = current; sink.headers = resp.headers(); sink.finalTtfbMs = hopTtfb; }
                        return new Fetch(sc, ttfb, System.currentTimeMillis() - start, 0L, null, null, false, false);
                    }
                    if (sink != null) {
                        sink.redirects++;
                        sink.redirectMs += Math.max(0L, System.currentTimeMillis() - hopStart - hopGuard);
                    }
                    current = next;
                    m = SafeRedirect.nextMethod(sc, m);   // 303 See Other → GET
                    continue;
                }
                byte[] body = null;
                long count = 0L;
                boolean truncated = false;
                String encoding = resp.headers().firstValue("content-encoding").orElse(null);
                if (sink != null) { sink.finalUrl = current; sink.headers = resp.headers(); sink.finalTtfbMs = hopTtfb; }
                if (deep) {
                    sink.version = resp.version();
                    if (tuning.pageHeaders()) sink.capturePageHeaders(resp.headers());
                }
                java.security.MessageDigest digest = deep && tuning.hash() ? sha256() : null;
                try (InputStream bodyIn = com.sitemonitor.util.HttpBodies.withDeadline(resp.body(), bodyMs, "Sayfa")) {
                    // Özet istenirse tel baytları akışla özetlenir (gövde tutulmaz); aksi halde aynı akış.
                    InputStream is = digest == null ? bodyIn : new java.security.DigestInputStream(bodyIn, digest);
                    if (opts.wantBody()) {
                        // SAYIM telden gelen (sıkıştırılmış) bayt üzerinden; GÖVDE ayrıştırmak için açılır.
                        // İkisini karıştırmak iki ayrı hataya yol açardı: sıkıştırılmışı parse etmek
                        // envanteri BOŞ bırakır (hiç kaynak ölçülmez), açılmışı saymak da "transfer
                        // boyutu" etiketini yine yalancı yapardı.
                        // Süre dolarsa BodyDeadlineException → aşağıdaki catch: kesik gövdeyi ayrıştırmak
                        // sahte "sayfa değişti"/eksik envanter üretirdi, hata olarak raporlanır.
                        byte[] raw = is.readNBytes(maxBody);
                        count = raw.length;
                        body = decode(raw, encoding, maxDecoded);
                        if (opts.countBytes()) {                   // tavanın ötesini SAY (sakla değil)
                            Drained d = drain(is, MAX_COUNT_BYTES - count);
                            count += d.bytes();
                            truncated = count >= MAX_COUNT_BYTES || d.timedOut();
                        }
                    } else if (opts.countBytes()) {
                        boolean headTimedOut = false;
                        if (sink != null && sink.headBytes > 0) {
                            // Gövdenin ilk baytları (görsel boyutu) — sayımın PARÇASIDIR, ayrıca indirilmez.
                            try {
                                sink.head = is.readNBytes((int) Math.min(sink.headBytes, MAX_COUNT_BYTES));
                            } catch (com.sitemonitor.util.HttpBodies.BodyDeadlineException te) {
                                headTimedOut = true;
                            }
                            count = sink.head == null ? 0L : sink.head.length;
                        }
                        if (headTimedOut) {
                            truncated = true;
                        } else if (deep && tuning.sampleHead() > 0) {
                            // Betik örneği: tel akışı sayılırken çözülür; baş ve son baytlar tutulur, gövde tutulmaz.
                            Sampled sm = sampleAndCount(is, MAX_COUNT_BYTES - count, encoding, tuning.sampleHead(),
                                    tuning.sampleTail());
                            count += sm.wire();
                            truncated = count >= MAX_COUNT_BYTES || sm.timedOut();
                            sink.sampleHead = sm.head();
                            sink.sampleTail = truncated ? null : sm.tail();
                            sink.sampleComplete = !truncated && sm.complete();
                            sink.sampleSkipped = truncated && sm.skipped() == null ? "truncated" : sm.skipped();
                        } else {
                            Drained d = drain(is, MAX_COUNT_BYTES - count);
                            count += d.bytes();
                            truncated = count >= MAX_COUNT_BYTES || d.timedOut();
                        }
                    } else {
                        // Yalnız ulaşılabilirlik: durum kodu başlıkta — gövdesini bitirmeyen kaynak HATA değil.
                        try { is.readNBytes(4096); }   // gövdeyi tüket (bağlantı iadesi)
                        catch (com.sitemonitor.util.HttpBodies.BodyDeadlineException ignore) { /* akış kesildi */ }
                    }
                }
                if (digest != null && !truncated && (opts.wantBody() || opts.countBytes())) sink.contentHash = hex(digest.digest());
                return new Fetch(sc, ttfb, System.currentTimeMillis() - start, count, body, null, false, truncated);
            }
            return new Fetch(0, 0L, System.currentTimeMillis() - start, 0L, null, "çok fazla yönlendirme", false, false,
                    redirectLimit(opts, current));
        } catch (Exception e) {
            // Kaynak analizi: kesilen (iptal / süre dolumu) iş parçacığının bayrağı geri konur — çağıran sonucu "kırık"
            // saymadan atabilsin. İzleme çağrıları (tuning yok) bugünkü gibi.
            if (tuning != null && e instanceof InterruptedException) Thread.currentThread().interrupt();
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Fetch(0, 0L, System.currentTimeMillis() - start, 0L, null, msg, false, false,
                    classified(e, opts, current));
        }
    }

    /**
     * Aktarım hatasının sınıflandırması (2026-10-05, hata teşhisi) — istisnanın KENDİSİNDEN, yakalandığı yerde (metinden
     * tahminden daha kesin). Ayrıntıya yalnız host, yol ve zaman aşımı eklenir (tam URL değil: sorgu dizgisi sır taşıyabilir).
     * Asla fırlatmaz; sınıflandırılamazsa null (Fetch eskisi gibi kalır).
     */
    private static com.sitemonitor.service.failure.CheckFailure classified(Throwable t, FetchOptions opts, String url) {
        try {
            boolean proxy = opts != null && opts.viaProxy();
            return com.sitemonitor.service.failure.CheckFailureClassifier.forException(t, proxy)
                    .with("target", hostOf(url))
                    .withIfAbsent("via", proxy ? "proxy" : "direct")
                    .with("timeout_ms", opts == null ? null : Math.max(1000, opts.timeoutMs()));
        } catch (Exception e) {
            return null;
        }
    }

    /** Yönlendirme zinciri {@link #MAX_REDIRECTS}'i aştı — istisnasız yol. */
    private static com.sitemonitor.service.failure.CheckFailure redirectLimit(FetchOptions opts, String url) {
        try {
            boolean proxy = opts != null && opts.viaProxy();
            return com.sitemonitor.service.failure.CheckFailure
                    .of(com.sitemonitor.service.failure.CheckFailureReason.REDIRECT_LIMIT)
                    .with("target", hostOf(url))
                    .with("via", proxy ? "proxy" : "direct")
                    .with("max_redirects", MAX_REDIRECTS);
        } catch (Exception e) {
            return null;
        }
    }

    /** Örneklenmiş sayım sonucu: tel baytı, baş / son çözülmüş baytlar, tamamı başta mı, neden yok, süre doldu mu. */
    record Sampled(long wire, byte[] head, byte[] tail, boolean complete, String skipped, boolean timedOut) {}

    /**
     * Tel akışını tavana kadar SAYARKEN çözer ve baş / son baytları tutar (kaynak analizi, algoritma 3). Çözülebilen kodlama:
     * kimlik / gzip / deflate; br ve bilinmeyen kodlama → yalnız sayım ({@code skipped=encoding}). Çözme en çok
     * {@value #MAX_SAMPLE_DECODED} bayt sürer (sıkıştırma bombası), sonra kalan tel baytları çözülmeden sayılır. Bozuk akış
     * → {@code skipped=decode}, sayım sürer. Bellek: baş + son tampon.
     */
    static Sampled sampleAndCount(InputStream in, long cap, String encoding, int headN, int tailN) throws java.io.IOException {
        CappedCounter wire = new CappedCounter(in, Math.max(0, cap));
        String enc = encoding == null ? "" : encoding.trim().toLowerCase(Locale.ROOT);
        byte[] head = new byte[headN];
        int headLen = 0;
        byte[] ring = new byte[Math.max(1, tailN)];
        long total = 0;
        boolean complete = false, timedOut = false;
        String skipped = null;
        InputStream decoded = null;
        try {
            if (enc.isEmpty() || enc.equals("identity")) decoded = wire;
            else if (enc.contains("gzip")) decoded = new java.util.zip.GZIPInputStream(wire, 8192);
            else if (enc.contains("deflate")) {
                decoded = new java.util.zip.InflaterInputStream(wire, new java.util.zip.Inflater(true), 8192);
            } else skipped = "encoding";
            if (decoded != null) {
                byte[] buf = new byte[8192];
                while (total < MAX_SAMPLE_DECODED) {
                    int n = decoded.read(buf, 0, buf.length);
                    if (n < 0) { complete = true; break; }
                    if (headLen < headN) {
                        int k = Math.min(n, headN - headLen);
                        System.arraycopy(buf, 0, head, headLen, k);
                        headLen += k;
                    }
                    for (int i = 0; i < n; i++) ring[(int) ((total + i) % ring.length)] = buf[i];
                    total += n;
                }
            }
        } catch (com.sitemonitor.util.HttpBodies.BodyDeadlineException te) {
            timedOut = true;
        } catch (java.io.IOException e) {
            if (wire.timedOut) timedOut = true;
            else if (!wire.capped) skipped = "decode";
        }
        if (!timedOut) {
            Drained d = drain(wire, Long.MAX_VALUE);
            if (d.timedOut()) timedOut = true;
        }
        if (wire.capped && skipped == null) skipped = "truncated";
        boolean full = complete && skipped == null && !timedOut;
        byte[] headOut = decoded == null || headLen == 0 && !full ? null : java.util.Arrays.copyOf(head, headLen);
        byte[] tailOut = null;
        if (full && tailN > 0) {
            int len = (int) Math.min(total, tailN);
            tailOut = new byte[len];
            for (int i = 0; i < len; i++) tailOut[i] = ring[(int) ((total - len + i) % ring.length)];
        }
        return new Sampled(wire.count, headOut, tailOut, full && total <= headN, skipped, timedOut);
    }

    /** Tel sayacı: tavanda EOF döner ({@code capped}); süre aşımını işaretler. */
    private static final class CappedCounter extends java.io.FilterInputStream {
        final long cap;
        long count;
        boolean capped, timedOut;

        CappedCounter(InputStream in, long cap) {
            super(in);
            this.cap = cap;
        }

        @Override
        public int read() throws java.io.IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (count >= cap) { capped = true; return -1; }
            int want = (int) Math.min(len, cap - count);
            int n;
            try {
                n = super.read(b, off, want);
            } catch (com.sitemonitor.util.HttpBodies.BodyDeadlineException te) {
                timedOut = true;
                throw te;
            }
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws java.io.IOException {
            return 0;
        }
    }

    /** Değeri ATILMIŞ çerez başlığı: {@code ad=v; a; b} → {@code ad=; a; b}; adsız → null. Uzunluk sınırlı. */
    static String stripCookieValue(String setCookie) {
        if (setCookie == null) return null;
        String v = setCookie.length() > 4096 ? setCookie.substring(0, 4096) : setCookie;
        int eq = v.indexOf('=');
        int semi = v.indexOf(';');
        if (eq <= 0 || (semi >= 0 && semi < eq)) return null;
        String name = v.substring(0, eq).trim();
        if (name.isEmpty()) return null;
        return semi < 0 ? name + "=" : name + "=" + v.substring(semi);
    }

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /**
     * Sıkıştırılmış gövdeyi açar (gzip/deflate). Açılamazsa HAM bayt döner: bozuk/kesik bir
     * akış yüzünden sayfanın tamamen ölçülemez hale gelmesindense, ayrıştırıcının elinden
     * geleni yapması yeğdir (jsoup bozuk girdide boş envanter döner, istisna sızmaz).
     */
    private static byte[] decode(byte[] raw, String encoding, int cap) {
        if (raw == null || raw.length == 0 || encoding == null) return raw;
        String enc = encoding.trim().toLowerCase(java.util.Locale.ROOT);
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(raw)) {
            if (enc.contains("gzip")) {
                try (java.util.zip.GZIPInputStream g = new java.util.zip.GZIPInputStream(in)) {
                    return readCapped(g, raw.length, enc, cap);
                }
            }
            if (enc.contains("deflate")) {
                try (java.util.zip.InflaterInputStream d =
                             new java.util.zip.InflaterInputStream(in, new java.util.zip.Inflater(true))) {
                    return readCapped(d, raw.length, enc, cap);
                }
            }
        } catch (Exception e) {
            log.debug("Gövde açılamadı ({}), ham bayt kullanılıyor: {}", enc, e.getMessage());
        }
        return raw;
    }

    /**
     * Açılmış gövde tavanı — <b>sıkıştırma bombası</b> koruması.
     *
     * <p>Telden okunan gövde {@link #MAX_BODY_BYTES} ile zaten sınırlı, ama AÇILMIŞ boyut değildi:
     * 1000:1 oranlı 2 MB'lık bir yanıt 2 GB'a açılıp heap'i tüketebiliyordu. Prod TEK pod ve
     * OOM = kesinti, üstelik hedef URL'i sıradan bir kullanıcı tanımlayabiliyor.
     *
     * <p>Tavan tel tavanının 10 katı: gerçek sayfalar burnunu bile sürtmez (HTML tipik olarak
     * 5:1 sıkışır, yani 2 MB tel ≈ 10 MB HTML), bomba ise burada durur.
     */
    public static final int MAX_DECODED_BYTES = 10 * MAX_BODY_BYTES;

    private static byte[] readCapped(InputStream in, int rawLen, String enc, int cap) throws java.io.IOException {
        byte[] out = in.readNBytes(cap);
        if (out.length >= cap && cap == MAX_DECODED_BYTES) {
            // Sessizce kırpmak "sayfa değişti" sanrısı üretir — kırpma GÖRÜNÜR olmalı. (Kaynak analizinin daha küçük
            // tavanı bilinçlidir: stilin yalnız taranan kısmı açılır — uyarı gürültü olurdu.)
            log.warn("Açılmış gövde {} bayt tavanına dayandı ({}, telde {} bayt) — kaynak envanteri EKSİK olabilir; "
                    + "sıkıştırma bombası olabilir", MAX_DECODED_BYTES, enc, rawLen);
        }
        return out;
    }

    /**
     * Akışı tavana kadar oku ve SAY — içeriği tutma (ağırlık ölçümü; bellek sabit kalır).
     *
     * <p>Son okuma KALAN kadar istenir; aksi halde tam tampon istenip tavan bir tampon boyu
     * (8 KB) aşılıyordu. Bunu düzeltmek şart: kırpılan bir kaynak ekranda "≥ 10 MB" olarak
     * gösteriliyor ve rakamın gerçekten tavanda durması gerekiyor, tavanın biraz üstünde değil.
     */
    private static Drained drain(InputStream is, long cap) throws java.io.IOException {
        if (cap <= 0) return new Drained(0L, false);
        byte[] buf = new byte[8192];
        long total = 0L;
        try {
            while (total < cap) {
                int want = (int) Math.min(buf.length, cap - total);
                int n = is.read(buf, 0, want);
                if (n <= 0) break;
                total += n;
            }
        } catch (com.sitemonitor.util.HttpBodies.BodyDeadlineException te) {
            // Süre doldu (N1): bayt tavanıyla AYNI anlam — "en az bu kadar", truncated işaretlenir.
            return new Drained(total, true);
        }
        return new Drained(total, false);
    }

    /** Sayım sonucu: okunan bayt + gövde süre bütçesinde bitmedi mi. */
    private record Drained(long bytes, boolean timedOut) {}

    /** Çekirdeğin kendi başlıkları ek başlıklarla EZİLEMEZ; ayrıca kontrol karakteri (CR/LF) taşıyan
     *  satırlar sessizce atılır — başlık enjeksiyonu kapanır. */
    private static final Set<String> RESERVED_HEADERS =
            Set.of("user-agent", "accept", "accept-language", "host", "content-length", "connection");

    /** Ek başlık bu adla gönderilebilir mi (çekirdeğin kendi başlıklarını ezemez) — tanılamanın ham ölçümü de uygular. */
    public static boolean isReservedHeader(String name) {
        return name != null && RESERVED_HEADERS.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    private static void applyExtraHeaders(HttpRequest.Builder rb, Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) return;
        for (Map.Entry<String, String> e : extra.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || v == null || k.isBlank()) continue;
            if (RESERVED_HEADERS.contains(k.toLowerCase(Locale.ROOT))) continue;
            if (hasControlChars(k) || hasControlChars(v)) {
                log.warn("Ek başlık atlandı (kontrol karakteri): {}", sanitize(k));
                continue;
            }
            try { rb.header(k, v); } catch (IllegalArgumentException ignore) { /* HttpClient kısıtlı başlık listesi */ }
        }
    }

    static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20 || s.charAt(i) == 0x7F) return true;
        return false;
    }

    // ── Kaynak envanteri (jsoup) ─────────────────────────────────────────────

    /**
     * Envanter davranışı — iki tüketicinin İHTİYACI FARKLI.
     *
     * @param hyperlinks         {@code a[href]} bağlantıları da toplansın mı. Sayfa Bütünlüğü onları
     *                           kırık-link diye kontrol eder; Sayfa Hızı için ANLAMSIZDIR (tarayıcı
     *                           indirmez) ve daha kötüsü {@link #maxResourcesPerCheck() kaynak tavanı} bütçesini
     *                           yer: link ağırlıklı bir sayfada tavan linklere harcanır ve
     *                           GERÇEK kaynaklar hiç ölçülmez.
     * @param allImageCandidates {@code srcset}'teki TÜM adaylar toplansın mı. Tarayıcı bir görsel
     *                           için ekran/DPR'a göre YALNIZ BİRİNİ indirir; hepsini saymak sayfa
     *                           ağırlığını katbekat şişirir. Sayfa Bütünlüğü hepsini ister (herhangi
     *                           biri kırık olabilir), Sayfa Hızı görsel başına BİR tane ister.
     */
    public record InventoryOptions(boolean hyperlinks, boolean allImageCandidates, boolean skipLazy) {
        /** Sayfa Bütünlüğü: her şeyi topla (mevcut davranış). {@code loading=lazy} DAHİL —
         *  orada soru "bu kaynak var mı", tarayıcının onu ne zaman istediği değil. */
        public static final InventoryOptions FULL = new InventoryOptions(true, true, false);
        /**
         * Sayfa Hızı: tarayıcının GERÇEKTEN indireceği kadarı — link yok, görsel başına tek aday,
         * {@code loading="lazy"} kaynaklar ATLANIR.
         *
         * <p>Lazy atlama bir "iyileştirme" değil doğruluk meselesi: tarayıcı ekran dışındaki
         * (karusel slaytı, sayfa altı) görselleri hiç istemez. Onları indirmek sayfayı olduğundan
         * kat kat ağır gösteriyordu — ölçülen 44.6 MB / 182 istek, aynı sayfada tarayıcı 6.1 MB /
         * 124 istek. Atlanan sayı raporlanır: sessizce eksiltmek de fazla saymak kadar yanıltıcı.
         */
        public static final InventoryOptions AS_BROWSER_LOADS = new InventoryOptions(false, false, true);
    }

    /**
     * Sayfa başına kaynak tavanı (canlı ayar, sınırlı): boş / sıfır / negatif ya da okunamayan değer varsayılan
     * {@value #DEFAULT_MAX_RESOURCES_PER_CHECK}; {@value #MIN_RESOURCES_PER_CHECK}–{@value #MAX_RESOURCES_PER_CHECK_LIMIT} arasına
     * sıkıştırılır. TEK kaynak: envanter, crawl toplamı, sayfa hızı "kısmi ölçüm" bayrağı ve tanılama aynı değeri okur.
     */
    public static int maxResourcesPerCheck(com.sitemonitor.service.AppSettingsService settings) {
        int v;
        try {
            v = settings == null ? DEFAULT_MAX_RESOURCES_PER_CHECK : settings.getInt(MAX_RESOURCES_KEY, DEFAULT_MAX_RESOURCES_PER_CHECK);
        } catch (RuntimeException e) {
            v = DEFAULT_MAX_RESOURCES_PER_CHECK;
        }
        if (v <= 0) v = DEFAULT_MAX_RESOURCES_PER_CHECK;
        return Math.max(MIN_RESOURCES_PER_CHECK, Math.min(MAX_RESOURCES_PER_CHECK_LIMIT, v));
    }

    /** Bu servisin canlı sayfa başına kaynak tavanı. */
    public int maxResourcesPerCheck() {
        return maxResourcesPerCheck(appSettings);
    }

    /** Crawl genelinde toplam kaynak tavanı — en az {@value #MIN_TOTAL_RESOURCES}, sayfa tavanının 3 katı (tek dev sayfa crawl'u boşa düşürmesin). */
    public static int maxTotalResources(int perPage) {
        return Math.max(MIN_TOTAL_RESOURCES, perPage * 3);
    }

    /** Envanter + ölçüm dışı bırakılanların sayısı + uygulanan sayfa başına kaynak tavanı. */
    public record Inventory(List<Resource> resources, int skippedLazy, int cap) {
        public Inventory(List<Resource> resources, int skippedLazy) {
            this(resources, skippedLazy, DEFAULT_MAX_RESOURCES_PER_CHECK);
        }

        /** Envanter tavana takıldı mı — kalan kaynaklar atlandı ("kısmi"). */
        public boolean capped() {
            return resources.size() >= cap;
        }
    }

    /** Geriye uyum: seçeneksiz çağrı Sayfa Bütünlüğü davranışını korur. */
    public List<Resource> inventory(byte[] bytes, String baseUrl, String sourcePage, Predicate<String> exclude) {
        return inventory(bytes, baseUrl, sourcePage, exclude, InventoryOptions.FULL);
    }

    /**
     * HTML gövdesinden alt kaynak envanterini çıkarır (img/srcset/CSS/JS/iframe/favicon/font/a[href]).
     * Aynı URL bir kez döner (sıra korunur). Parse hatası boş liste verir — çağırana exception sızmaz.
     *
     * @param exclude URL bazlı hariç-tutma yüklemi; null ise hiçbir şey hariç tutulmaz
     */
    public List<Resource> inventory(byte[] bytes, String baseUrl, String sourcePage, Predicate<String> exclude,
                                    InventoryOptions opts) {
        return inventory(bytes, baseUrl, sourcePage, exclude, opts, maxResourcesPerCheck());
    }

    private List<Resource> inventory(byte[] bytes, String baseUrl, String sourcePage, Predicate<String> exclude,
                                     InventoryOptions opts, int cap) {
        Document doc;
        try {
            // Bayt-stream + null charset → jsoup <meta charset>/BOM'dan charset'i otomatik tespit eder;
            // parse hatasında (bozuk/dev HTML) boş envanter (çağırana exception sızmaz).
            doc = Jsoup.parse(new java.io.ByteArrayInputStream(bytes), null, baseUrl);
        } catch (Exception e) {
            log.debug("HTML parse edilemedi {}: {}", sanitize(sourcePage), e.getMessage());
            return List.of();
        }
        LinkedHashMap<String, Resource> out = new LinkedHashMap<>();   // absUrl → Resource (dedup, sıra korunur)
        // loading="lazy" URL'leri: aşağıda envanterden düşülür (yalnız AS_BROWSER_LOADS'ta).
        java.util.Set<String> lazyUrls = new java.util.HashSet<>();
        if (opts.skipLazy()) {
            for (Element el : doc.select("img[loading=lazy][src], iframe[loading=lazy][src]")) {
                String abs = normalizeUrl(el.absUrl("src"));
                if (!abs.isBlank()) lazyUrls.add(abs);
            }
        }
        addAll(out, doc, "img[src]", "src", "IMG", sourcePage);
        addSrcset(out, doc, sourcePage, opts.allImageCandidates());
        addAll(out, doc, "link[rel=stylesheet][href]", "href", "CSS", sourcePage);
        addAll(out, doc, "script[src]", "src", "JS", sourcePage);
        addAll(out, doc, "iframe[src]", "src", "IFRAME", sourcePage);
        addAll(out, doc, "link[rel~=(?i)icon][href]", "href", "FAVICON", sourcePage);
        addAll(out, doc, "link[rel=preload][as=font][href]", "href", "FONT", sourcePage);
        if (opts.hyperlinks()) addAll(out, doc, "a[href]", "href", "LINK", sourcePage);
        List<Resource> list = new ArrayList<>();
        int skippedLazy = 0;
        for (Resource r : out.values()) {
            if (!isHttp(r.url())) continue;
            if (lazyUrls.contains(r.url())) { skippedLazy++; continue; }
            if (exclude != null && exclude.test(r.url())) continue;
            // Sayfanın kendisine çözülen link (a[href="#x"], href="") — gereksiz self-request
            if ("LINK".equals(r.type()) && stripFragment(r.url()).equalsIgnoreCase(sourcePage)) continue;
            list.add(r);
            if (list.size() >= cap) {
                log.warn("Sayfa {} — {} kaynak limitine ulaşıldı, kalanlar atlandı (tavan: {})", sanitize(sourcePage), cap,
                        MAX_RESOURCES_KEY);
                break;
            }
        }
        lastSkippedLazy.set(skippedLazy);
        return list;
    }

    /** Envanter + atlanan lazy sayısı — sayının çağırana ULAŞMASI için (ölçüm raporunda yazılır). */
    public Inventory inventoryDetailed(byte[] bytes, String baseUrl, String sourcePage,
                                       Predicate<String> exclude, InventoryOptions opts) {
        int cap = maxResourcesPerCheck();
        List<Resource> list = inventory(bytes, baseUrl, sourcePage, exclude, opts, cap);
        return new Inventory(list, lastSkippedLazy.get(), cap);
    }

    /** {@link #inventoryDetailed} çağıran thread'in son sayısını taşır — paylaşılan alan DEĞİL
     *  (bu servis tekil ve envanter paralel çağrılabilir; ThreadLocal olmadan sayı karışırdı). */
    private final ThreadLocal<Integer> lastSkippedLazy = ThreadLocal.withInitial(() -> 0);

    private void addAll(Map<String, Resource> out, Document doc, String css, String attr, String type, String src) {
        for (Element el : doc.select(css)) {
            String abs = el.absUrl(attr);
            if (abs == null || abs.isBlank()) abs = el.attr(attr);   // parse edilemezse ham değer (mixed/broken tespiti için)
            abs = normalizeUrl(abs);
            if (abs.isBlank()) continue;
            out.putIfAbsent(abs, new Resource(abs, type, src));
        }
    }

    /**
     * srcset: "url 1x, url2 2w" listesindeki aday URL'ler.
     *
     * <p>{@code allCandidates=false} (Sayfa Hızı) ise tarayıcı davranışı taklit edilir: bir görsel
     * için YALNIZ BİR dosya indirilir. {@code <source srcset>} tamamen atlanır ve {@code img}'in
     * srcset'i yalnız {@code src} YOKSA (yani indirilecek başka aday yoksa) ilk adayla temsil
     * edilir. Aksi halde tek bir görsel 4-5 kez sayılır ve sayfa ağırlığı katbekat şişer.
     */
    private void addSrcset(Map<String, Resource> out, Document doc, String src, boolean allCandidates) {
        String selector = allCandidates ? "img[srcset], source[srcset]" : "img[srcset]";
        for (Element el : doc.select(selector)) {
            if (!allCandidates && !el.attr("src").isBlank()) continue;   // src zaten toplandı
            for (String cand : el.attr("srcset").split(",")) {
                String u = cand.trim().split("\\s+")[0];
                if (u.isBlank()) continue;
                String abs = normalizeUrl(el.root().baseUri().isBlank() ? u : resolve(el.baseUri(), u));
                out.putIfAbsent(abs, new Resource(abs, "IMG", src));
                if (!allCandidates) break;   // görsel başına tek aday
            }
        }
    }

    // ── URL yardımcıları ─────────────────────────────────────────────────────

    /** HTML'den gelen URL'de URI.create'i PATLATAN kodlanmamış ASCII karakterleri (boşluk, tırnak, köşeli
     *  parantez, dikey çizgi vb.) yüzde-kodlar — tarayıcı da böyle yapar; aksi halde host çözülemez → yanlış
     *  "kırık". Yüzde işaretine ve non-ASCII'ye DOKUNMAZ (zaten-kodlanmış %XX bozulmaz). */
    private static final String URL_UNSAFE = " \"<>|{}^`\\[]";

    public static String normalizeUrl(String url) {
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

    public static boolean isHttp(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    public static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return null; }
    }

    public static String originOf(String url) {
        try {
            URI u = URI.create(url);
            int port = u.getPort();
            return u.getScheme() + "://" + u.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) { return url; }
    }

    public static String resolve(String base, String ref) {
        try { return URI.create(base).resolve(ref).toString(); } catch (Exception e) { return ref; }
    }

    public static String stripFragment(String url) {
        int h = url.indexOf('#');
        return h >= 0 ? url.substring(0, h) : url;
    }

    /** Log-forging önleme: loglanan URL/host'taki CR/LF'yi boşlukla değiştir (flat-file satır enjeksiyonu). */
    public static String sanitize(String s) { return s == null ? null : s.replace('\n', ' ').replace('\r', ' '); }
}
