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
    /** Tek SAYFANIN işlenecek azami (tekil) kaynağı — tek-pod yük koruması; aşılırsa WARN + kırpılır. */
    public static final int MAX_RESOURCES_PER_CHECK = 500;
    /** Bir CRAWL genelinde toplam azami kaynak — bellek + DB-insert patlamasını sınırlar. */
    public static final int MAX_TOTAL_RESOURCES = 1500;
    /** Manuel redirect zinciri üst sınırı. */
    private static final int MAX_REDIRECTS = 5;
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
     */
    public record Fetch(int status, long ttfbMs, long durationMs, long bytes, byte[] body,
                        String error, boolean blocked, boolean truncated) {}

    /** HTML'den çıkarılmış tek bir alt kaynak. */
    public record Resource(String url, String type, String sourcePage) {}

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

    @PreDestroy
    public void shutdown() {
        if (resourceExecutor != null) resourceExecutor.shutdownNow();
    }

    /** Kaynak doğrulama/ölçüm fan-out'u için paylaşılan sanal-thread executor. */
    public ExecutorService executor() { return resourceExecutor; }

    // ── Manuel redirect takipli fetch (her hop SSRF'den geçer) ───────────────

    /**
     * Bir URL'i çeker; redirect'leri MANUEL takip eder ve her hop'ta SSRF muhafızını çalıştırır.
     * Hiçbir zaman exception fırlatmaz — hata Fetch.error()/Fetch.blocked() olarak döner.
     */
    public Fetch fetch(String url, String method, FetchOptions opts) {
        long start = System.currentTimeMillis();
        // Kendi SSRF/DNS kontrolumuzun suresi TTFB'ye YAZILMAZ: hedefin yavasligi degil bizim
        // guvenlik kapimizin maliyetidir. Olcum penceresinden dusulur (her hop icin toplanir).
        long guardMs = 0L;
        String current = url;
        String m = method;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                String host = hostOf(current);
                try {
                    if (host == null) throw new SsrfGuard.BlockedException("geçersiz URL: " + current);
                    // SSRF (her hop). NOT (M4 residual): validate() çözülen IP'leri döndürür ama HttpClient host'u
                    // yeniden çözer → TOCTOU/DNS-rebind penceresi. Metadata/loopback/link-local HER ZAMAN bloklu +
                    // JVM pozitif-DNS cache pratik riski azaltır; IP-pinning (NetworkResolver) bilinçli uygulanmadı
                    // (HTTPS SNI karmaşası + kaynak-başı maliyet). Ops: networkaddress.cache.ttl'i 0'a çekmeyin.
                    long g0 = System.currentTimeMillis();
                    ssrfGuard.validate(host);
                    guardMs += System.currentTimeMillis() - g0;
                } catch (SsrfGuard.BlockedException be) {
                    return new Fetch(0, 0L, System.currentTimeMillis() - start, 0L, null, be.getMessage(), true, false);
                }
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(current))
                        .timeout(Duration.ofMillis(Math.max(1000, opts.timeoutMs())))
                        // Tarayıcı-benzeri header seti: katı sunucular Accept/Accept-Language yoksa 406/403 döner.
                        .header("User-Agent", opts.userAgent())
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "tr,en;q=0.9")
                        // Java HttpClient bu basligi KENDILIGINDEN EKLEMEZ ve otomatik ACMAZ.
                        // Gondermeyince sunucu sikistirmasiz yaniyor: arayuz "transfer boyutu"
                        // diyordu ama olculen sey acilmis boyuttu (44.6 MB ↔ tarayicida 6.1 MB).
                        // Artik telde ne gidiyorsa o sayiliyor; govde YALNIZ ayristirmak icin aciliyor.
                        .header("Accept-Encoding", "gzip, deflate");
                applyExtraHeaders(rb, opts.extraHeaders());
                HttpRequest req = "HEAD".equals(m)
                        ? rb.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
                        : rb.GET().build();
                HttpClient c = opts.viaProxy() && proxiedClient != null ? proxiedClient : httpClient;
                HttpResponse<InputStream> resp = c.send(req, HttpResponse.BodyHandlers.ofInputStream());
                // send() başlıklar geldiğinde döner → ilk-bayt anı burasıdır (gövde henüz okunmadı).
                long ttfb = Math.max(0L, System.currentTimeMillis() - start - guardMs);
                int sc = resp.statusCode();
                if (sc >= 300 && sc < 400) {
                    String loc = resp.headers().firstValue("location").orElse(null);
                    try (InputStream is = resp.body()) { is.readNBytes(4096); } catch (Exception ignore) { /* gövde iadesi */ }
                    // Hedef yok / http(s) DIŞI şema (file:, gopher:) / host'suz → olduğu gibi dön.
                    // Politika SafeRedirect'te tek kopya: aynı kural keyword/HTTP/HSTS için de geçerli.
                    String next = SafeRedirect.nextHop(current, loc);
                    if (next == null) {
                        return new Fetch(sc, ttfb, System.currentTimeMillis() - start, 0L, null, null, false, false);
                    }
                    current = next;
                    m = SafeRedirect.nextMethod(sc, m);   // 303 See Other → GET
                    continue;
                }
                byte[] body = null;
                long count = 0L;
                boolean truncated = false;
                String encoding = resp.headers().firstValue("content-encoding").orElse(null);
                try (InputStream is = resp.body()) {
                    if (opts.wantBody()) {
                        // SAYIM telden gelen (sıkıştırılmış) bayt üzerinden; GÖVDE ayrıştırmak için açılır.
                        // İkisini karıştırmak iki ayrı hataya yol açardı: sıkıştırılmışı parse etmek
                        // envanteri BOŞ bırakır (hiç kaynak ölçülmez), açılmışı saymak da "transfer
                        // boyutu" etiketini yine yalancı yapardı.
                        byte[] raw = is.readNBytes(MAX_BODY_BYTES);
                        count = raw.length;
                        body = decode(raw, encoding);
                        if (opts.countBytes()) {                   // tavanın ötesini SAY (sakla değil)
                            count += drain(is, MAX_COUNT_BYTES - count);
                            truncated = count >= MAX_COUNT_BYTES;
                        }
                    } else if (opts.countBytes()) {
                        count = drain(is, MAX_COUNT_BYTES);
                        truncated = count >= MAX_COUNT_BYTES;
                    } else {
                        is.readNBytes(4096);   // gövdeyi tüket (bağlantı iadesi)
                    }
                }
                return new Fetch(sc, ttfb, System.currentTimeMillis() - start, count, body, null, false, truncated);
            }
            return new Fetch(0, 0L, System.currentTimeMillis() - start, 0L, null, "çok fazla yönlendirme", false, false);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Fetch(0, 0L, System.currentTimeMillis() - start, 0L, null, msg, false, false);
        }
    }

    /**
     * Sıkıştırılmış gövdeyi açar (gzip/deflate). Açılamazsa HAM bayt döner: bozuk/kesik bir
     * akış yüzünden sayfanın tamamen ölçülemez hale gelmesindense, ayrıştırıcının elinden
     * geleni yapması yeğdir (jsoup bozuk girdide boş envanter döner, istisna sızmaz).
     */
    private static byte[] decode(byte[] raw, String encoding) {
        if (raw == null || raw.length == 0 || encoding == null) return raw;
        String enc = encoding.trim().toLowerCase(java.util.Locale.ROOT);
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(raw)) {
            if (enc.contains("gzip")) {
                try (java.util.zip.GZIPInputStream g = new java.util.zip.GZIPInputStream(in)) {
                    return readCapped(g, raw.length, enc);
                }
            }
            if (enc.contains("deflate")) {
                try (java.util.zip.InflaterInputStream d =
                             new java.util.zip.InflaterInputStream(in, new java.util.zip.Inflater(true))) {
                    return readCapped(d, raw.length, enc);
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

    private static byte[] readCapped(InputStream in, int rawLen, String enc) throws java.io.IOException {
        byte[] out = in.readNBytes(MAX_DECODED_BYTES);
        if (out.length >= MAX_DECODED_BYTES) {
            // Sessizce kırpmak "sayfa değişti" sanrısı üretir — kırpma GÖRÜNÜR olmalı.
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
    private static long drain(InputStream is, long cap) throws java.io.IOException {
        if (cap <= 0) return 0L;
        byte[] buf = new byte[8192];
        long total = 0L;
        while (total < cap) {
            int want = (int) Math.min(buf.length, cap - total);
            int n = is.read(buf, 0, want);
            if (n <= 0) break;
            total += n;
        }
        return total;
    }

    /** Çekirdeğin kendi başlıkları ek başlıklarla EZİLEMEZ; ayrıca kontrol karakteri (CR/LF) taşıyan
     *  satırlar sessizce atılır — başlık enjeksiyonu kapanır. */
    private static final Set<String> RESERVED_HEADERS =
            Set.of("user-agent", "accept", "accept-language", "host", "content-length", "connection");

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
     *                           indirmez) ve daha kötüsü {@link #MAX_RESOURCES_PER_CHECK} bütçesini
     *                           yer: link ağırlıklı bir sayfada 500'lük tavan linklere harcanır ve
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

    /** Envanter + ölçüm dışı bırakılanların sayısı. */
    public record Inventory(List<Resource> resources, int skippedLazy) {}

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
            if (list.size() >= MAX_RESOURCES_PER_CHECK) {
                log.warn("Sayfa {} — {} kaynak limitine ulaşıldı, kalanlar atlandı", sanitize(sourcePage), MAX_RESOURCES_PER_CHECK);
                break;
            }
        }
        lastSkippedLazy.set(skippedLazy);
        return list;
    }

    /** Envanter + atlanan lazy sayısı — sayının çağırana ULAŞMASI için (ölçüm raporunda yazılır). */
    public Inventory inventoryDetailed(byte[] bytes, String baseUrl, String sourcePage,
                                       Predicate<String> exclude, InventoryOptions opts) {
        List<Resource> list = inventory(bytes, baseUrl, sourcePage, exclude, opts);
        return new Inventory(list, lastSkippedLazy.get());
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
