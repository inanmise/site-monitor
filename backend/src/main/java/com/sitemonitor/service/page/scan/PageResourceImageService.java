package com.sitemonitor.service.page.scan;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.HttpCheckerService;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.page.PageFetchCore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Kaynak analizi "Görsel aracı"nın SUNUCU tarafı (2026-11-14, kullanıcı kararı: görsel işleme TARAYICIDA; sunucu yalnız
 * taramanın bulduğu görselin özgün baytlarını verir ve "not ne olurdu?" sorusunu yanıtlar).
 *
 * <p><b>1) Özgün görsel ({@link #image}).</b> Sunucu ASLA açık vekil olmaz: yalnız o taramanın saklanan nesne listesindeki,
 * adresi BİREBİR eşleşen ve kategorisi GÖRSEL olan nesne çekilir; maskeli ({@code *****}) adres çekilemez. Çekim taramanın
 * kendi yoludur ({@link PageFetchCore}: her yönlendirme adımı SSRF muhafızından geçer, istek zaman aşımı taramanın ayarıdır),
 * vekil kararı taramanın o nesne için verdiği karardır ({@code via}). Gövde okunurken sınırlanır (en çok
 * {@value #MAX_IMAGE_BYTES} + 1 bayt tamponlanır), toplam süre sınırlıdır ({@link #totalBudgetMs}); tür YALNIZ sihirli
 * baytlardan belirlenir ({@link ImageDimensions#type}) — SVG / HTML hata sayfası reddedilir. Yük sınırları
 * ({@link ScanImageLimiter}) doğrulamalardan SONRA denetlenir; reddedilen istek sayılmaz, izinler {@code finally}'de bırakılır.
 * İstek başına ayrı istemciler ({@link PageFetchCore.ScanClients}): süre dolumu / kapanışta uçuştaki istek KESİLİR.
 *
 * <p><b>2) Projeksiyon ({@link #project}).</b> Dış istek yok, yazma yok: saklanan nesnelerin KOPYASI üzerinde analizin
 * performans hesabı ({@link PageResourceAnalyzer#performance} — {@code analyze}'ın çalıştırdığı kodun aynısı) iki kez koşar:
 * değişiklik olmadan ({@code before}) ve görsel boyutları değiştirilmiş hâliyle ({@code after}). Oluşturmayı engelleyen
 * bileşen HTML olgularına dayanır ve bitmiş analiz için saklanmaz; saklanan puanı yeniden kullanılır
 * ({@code reused_components}). {@code before} saklanan analizle BİREBİR aynı çıkmazsa (eski algoritma sürümü ya da nesne
 * listesinden yeniden kurulamayan şelale) projeksiyon verilmez ({@link ProjectionOutcome#NOT_PROJECTABLE}) — hayali fark
 * gösterilmez.
 */
@Slf4j
@Service
public class PageResourceImageService {

    /** Görsel gövdesinin tavanı (25 MiB) — tarayıcı motorunun girdi tavanıyla aynı. */
    public static final int MAX_IMAGE_BYTES = 25 * 1024 * 1024;
    /** Kişi başına dakikadaki en çok görsel indirme / aynı andaki en çok indirme; pod genelinde aynı andaki en çok indirme. */
    public static final int IMAGES_PER_MINUTE = 60, IMAGES_PER_USER = 2, IMAGES_PER_POD = 4;
    /** Eşzamanlılık reddinde önerilen bekleme (sn). */
    public static final long BUSY_RETRY_SECONDS = 2;
    /** Kişi başına dakikadaki en çok projeksiyon. */
    public static final int PROJECTIONS_PER_MINUTE = 30;
    /** Projeksiyon isteğindeki en çok görsel girdisi. */
    public static final int MAX_PROJECTION_IMAGES = 200;
    /** Projeksiyonda kabul edilen en büyük görsel boyutu (bayt) ve kenar (px). */
    public static final long MAX_PROJECTION_BYTES = 1L << 30;
    public static final int MAX_PROJECTION_SIDE = 100_000;
    /** İstek adresinin tavanı (keşif daha uzun adres üretmez). */
    public static final int MAX_URL_CHARS = PageResourceDiscovery.MAX_URL_CHARS;
    /** Bir taramadan okunan en çok nesne (tarama tavanı 5000; savunma). */
    static final int MAX_ITEMS = 10_000;
    /** Toplam çekim süresinin tavanı (ms). */
    static final long TOTAL_BUDGET_MAX_MS = 130_000L;
    /** Projeksiyonda saklanan puanı yeniden kullanılan bileşenler. */
    public static final List<String> REUSED_COMPONENTS = List.of("render_blocking");
    /**
     * Aynı kaynak sunucudan (adresin host'u) pod başına dakikadaki en çok görsel indirme — kurumsal çıkış adresinden tek bir
     * siteye yük (toplu indirme). Kişi sınırından ayrı; reddedilen istek kişinin dakika sayısına girmez.
     */
    public static final int IMAGES_PER_HOST_MINUTE = 30;
    /** Kişi başına / pod genelinde aynı anda en çok projeksiyon (her biri nesne listesini ayrıştırır, hesabı iki kez koşar). */
    public static final int PROJECTIONS_PER_USER = 2, PROJECTIONS_PER_POD = 4;
    /** {@code FETCH_FAILED.reason}: https adres http'ye yönlendirdi; özgün baytlar şifresiz adımdan alınmaz. */
    public static final String REASON_TLS_DOWNGRADE = "TLS_DOWNGRADE";
    /** Görsel dizini belleği: ömrü (ms), en çok tarama, tarama başına adres karakteri bütçesi (aşan dizin bellekte tutulmaz). */
    static final long INDEX_TTL_MS = 10_000L;
    static final int INDEX_MAX_ENTRIES = 8;
    static final long INDEX_MAX_CHARS = 1_000_000L;

    static final String SQL_ITEMS = "SELECT items_json FROM page_resource_scans WHERE id = ? AND monitor_id = ?";
    static final String SQL_STATE = "SELECT status, algorithm_version, items_kept FROM page_resource_scans "
            + "WHERE id = ? AND monitor_id = ?";
    static final String SQL_PROJECTION = "SELECT status, capped, grade, grade_letter, load_ms, page_bytes, image_bytes, "
            + "algorithm_version, analysis_json, items_json FROM page_resource_scans WHERE id = ? AND monitor_id = ?";

    private static final JsonFactory STREAM = new JsonFactory();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final PageFetchCore core;
    private final PageCheckerService pageChecker;
    private final PageResourceScanService scans;

    @Autowired(required = false)
    private ProxyPolicyService proxyPolicy;
    /** Son adımın TLS zincirinin güven değerlendirmesi (sistem deposu + kurumsal paket); yoksa başlık yazılmaz. */
    @Autowired(required = false)
    private TrustEvaluator trustEvaluator;

    private final LongSupplier clock;
    private final ScanImageLimiter imageLimiter;
    private final ScanImageLimiter projectionLimiter;
    /** Kaynak sunucu (host) başına dakika sınırı — yalnız sayar (eşzamanlılık yok). */
    private volatile ScanImageLimiter hostLimiter;
    /** Görsel dizini belleği (erişim sıralı; kilidi kendisi) — toplu indirmede nesne listesi bir kez ayrıştırılır. */
    private final Map<IndexKey, CachedIndex> index = new LinkedHashMap<>(16, 0.75f, true);
    private volatile long indexMaxChars = INDEX_MAX_CHARS;
    /** Nesne listesinin veritabanından okunma sayısı (testler / gözlem). */
    private final AtomicLong itemReads = new AtomicLong();
    /** Uçuştaki indirmelerin istemcileri (kapanışta hemen kesilir). */
    private final Set<PageFetchCore.ScanClients> active = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    @Autowired
    public PageResourceImageService(JdbcTemplate jdbc, PageFetchCore core, PageCheckerService pageChecker,
                                    PageResourceScanService scans) {
        this(jdbc, core, pageChecker, scans, System::currentTimeMillis);
    }

    /** Testler için: saat. */
    PageResourceImageService(JdbcTemplate jdbc, PageFetchCore core, PageCheckerService pageChecker,
                             PageResourceScanService scans, LongSupplier clock) {
        this.jdbc = jdbc;
        this.core = core;
        this.pageChecker = pageChecker;
        this.scans = scans;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.imageLimiter = new ScanImageLimiter(IMAGES_PER_MINUTE, IMAGES_PER_USER, IMAGES_PER_POD, this.clock);
        this.projectionLimiter = new ScanImageLimiter(PROJECTIONS_PER_MINUTE, PROJECTIONS_PER_USER, PROJECTIONS_PER_POD,
                this.clock);
        this.hostLimiter = new ScanImageLimiter(IMAGES_PER_HOST_MINUTE, 0, 0, this.clock);
    }

    /** Testler için (alan enjeksiyonu yerine). */
    void setProxyPolicy(ProxyPolicyService p) {
        this.proxyPolicy = p;
    }

    /** Testler için (alan enjeksiyonu yerine). */
    void setTrustEvaluator(TrustEvaluator t) {
        this.trustEvaluator = t;
    }

    /** Testler için: indirme sınırlayıcısı. */
    ScanImageLimiter imageLimiter() {
        return imageLimiter;
    }

    /** Testler için: projeksiyon sınırlayıcısı. */
    ScanImageLimiter projectionLimiter() {
        return projectionLimiter;
    }

    /** Testler için: kaynak sunucu sınırı (dakikada {@code perMinute}). */
    void setHostLimit(int perMinute) {
        this.hostLimiter = new ScanImageLimiter(perMinute, 0, 0, clock);
    }

    /** Testler için: dizin belleğinin tarama başına karakter bütçesi. */
    void setIndexMaxChars(long chars) {
        this.indexMaxChars = chars;
    }

    /** Testler için: nesne listesinin veritabanından okunma sayısı. */
    long itemReads() {
        return itemReads.get();
    }

    // ── 1) Özgün görsel ─────────────────────────────────────────────────────────────────────

    public enum ImageOutcome {
        OK, SCAN_NOT_FOUND, ITEMS_GONE, NOT_FOUND, MASKED, TOO_LARGE, RATE, BUSY, HOST_LIMITED, BLOCKED, FETCH_FAILED,
        UNSUPPORTED
    }

    /**
     * İndirme sonucu.
     *
     * @param body              görselin baytları (yalnız {@link ImageOutcome#OK})
     * @param type              sihirli baytlardan tür ({@code png|jpeg|gif|webp|bmp|avif})
     * @param width             başlıktan piksel genişliği; okunamadıysa null
     * @param retryAfterSeconds {@code RATE} / {@code BUSY} için önerilen bekleme
     * @param reason            {@code FETCH_FAILED}: ulaşılamama nedeni ({@code CheckFailureReason} adı); site yanıt verdiyse null
     * @param httpStatus        {@code FETCH_FAILED}: sitenin 2xx olmayan yanıt kodu; yoksa null
     * @param knownBytes        {@code TOO_LARGE}: bilinen boyut — taramanın ölçtüğü (çekilmeden reddedildi) ya da sunucunun
     *                          bildirdiği ({@code Content-Length}; gövde okunmadı); okunurken aşıldıysa null
     * @param tlsVerified       {@code OK}: son adım https ise zincir güvenilir (sistem deposu / kurumsal paket) VE adı
     *                          kapsıyor mu; son adım http ise ya da değerlendirici yoksa null
     * @param lease             {@code OK}: yük sınırı hakkı — yanıt YAZILANA (ya da istemci kopana) dek tutulur; çağıran
     *                          {@link #release()} ile bırakır (yazma sırasında bellek + iş parçacığı sayılır)
     */
    public record ImageResult(ImageOutcome outcome, byte[] body, String type, Integer width, Integer height,
                              long retryAfterSeconds, String reason, Integer httpStatus, Long knownBytes,
                              Boolean tlsVerified, ScanImageLimiter.Ticket lease) {

        /** Hak ve TLS hükmü olmadan (ret sonuçları, testler). */
        public ImageResult(ImageOutcome outcome, byte[] body, String type, Integer width, Integer height,
                           long retryAfterSeconds, String reason, Integer httpStatus, Long knownBytes) {
            this(outcome, body, type, width, height, retryAfterSeconds, reason, httpStatus, knownBytes, null, null);
        }

        ImageResult withLease(ScanImageLimiter.Ticket t) {
            return new ImageResult(outcome, body, type, width, height, retryAfterSeconds, reason, httpStatus, knownBytes,
                    tlsVerified, t);
        }

        ImageResult withTls(Boolean verified) {
            return new ImageResult(outcome, body, type, width, height, retryAfterSeconds, reason, httpStatus, knownBytes,
                    verified, lease);
        }

        /** Yük sınırı hakkını bırakır (yoksa / bırakılmışsa etkisiz). */
        public void release() {
            if (lease != null) lease.close();
        }

        static ImageResult of(ImageOutcome o) {
            return new ImageResult(o, null, null, null, null, 0, null, null, null);
        }

        static ImageResult failed(String reason, Integer httpStatus) {
            return new ImageResult(ImageOutcome.FETCH_FAILED, null, null, null, null, 0, reason, httpStatus, null);
        }

        static ImageResult busy() {
            return new ImageResult(ImageOutcome.BUSY, null, null, null, null, BUSY_RETRY_SECONDS, null, null, null);
        }
    }

    /** Saklanan nesne listesindeki görsel satırı (yalnız çekim için gerekenler). */
    record StoredImage(String via, long bytes, boolean sizeKnown) {}

    /** Nesne listesi araması: tarama var mı, liste saklanıyor mu, eşleşen GÖRSEL satırı (yoksa null). */
    record Lookup(boolean scanFound, boolean itemsKept, StoredImage image) {}

    /** Dizin belleği anahtarı — İZLEME de anahtarda: başka izlemenin aynı kimlikli taraması asla eşleşmez. */
    private record IndexKey(long scanId, long monitorId) {}

    private record CachedIndex(long loadedAt, Map<String, StoredImage> images) {}

    /**
     * Nesne listesinin GÖRSEL dizini: adres → ilk görsel satırı ({@code images}; bütçeyi aştıysa null — bellekte tutulmaz) +
     * istenen adresin eşleşmesi ({@code match}).
     */
    record ImageIndex(Map<String, StoredImage> images, StoredImage match) {}

    /**
     * Taramanın bulduğu TEK görselin özgün baytları (kapılar — izin + takımda çalıştırma — çağıranda geçmiş olmalı).
     *
     * @param url       nesnenin taramada dönen adresi (birebir)
     * @param proxyMode izlemenin vekil tercihi — yalnız nesnede saklı karar yoksa (eski tarama) kullanılır
     * @param userKey   kişi sınırı anahtarı
     */
    public ImageResult image(long monitorId, long scanId, String url, String proxyMode, String userKey) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_CHARS) return ImageResult.of(ImageOutcome.NOT_FOUND);
        Lookup lk = lookup(scanId, monitorId, url);
        if (!lk.scanFound()) return ImageResult.of(ImageOutcome.SCAN_NOT_FOUND);
        if (!lk.itemsKept()) return ImageResult.of(ImageOutcome.ITEMS_GONE);
        StoredImage img = lk.image();
        if (img == null) return ImageResult.of(ImageOutcome.NOT_FOUND);
        // Maskeli adres gerçek adres değildir (gizli değer saklanmadı) — çekilemez
        if (url.contains(SecretMask.MASK)) return ImageResult.of(ImageOutcome.MASKED);
        if (!PageFetchCore.isHttp(url)) return ImageResult.of(ImageOutcome.NOT_FOUND);
        // Taramanın ölçtüğü boyut tavanı aşıyorsa hiç çekilmez (25 MiB'lik boş indirme yok)
        if (img.sizeKnown() && img.bytes() > MAX_IMAGE_BYTES) {
            return new ImageResult(ImageOutcome.TOO_LARGE, null, null, null, null, 0, null, null, img.bytes());
        }
        if (stopping) return ImageResult.busy();
        // Yük sınırı doğrulamalardan SONRA: reddedilen (ve yukarıda elenen) istek sayılmaz
        ScanImageLimiter.Decision d = imageLimiter.tryAcquire(userKey);
        if (!d.allowed()) {
            return d.busy() ? ImageResult.busy()
                    : new ImageResult(ImageOutcome.RATE, null, null, null, null, d.retryAfterSeconds(), null, null, null);
        }
        ScanImageLimiter.Ticket ticket = d.ticket();
        ImageResult r = null;
        try {
            // Kaynak sunucu sınırı: reddedilirse kişinin hakkı GERİ alınır (dakika sayısına girmez)
            String host = ProxyPolicyService.hostOf(url);
            ScanImageLimiter.Decision h = hostLimiter.tryAcquire("h:" + (host == null ? "?" : host));
            if (!h.allowed()) {
                ticket.refund();
                return new ImageResult(ImageOutcome.HOST_LIMITED, null, null, null, null, h.retryAfterSeconds(), null, null,
                        null);
            }
            h.ticket().close();   // yalnız sayar
            r = fetch(url, viaProxy(img.via(), url, proxyMode));
            // Başarılı sonuçta hak yanıt YAZILANA dek çağıranda kalır (bellek + iş parçacığı yazma boyunca sayılır)
            return r.outcome() == ImageOutcome.OK ? r.withLease(ticket) : r;
        } finally {
            if (r == null || r.outcome() != ImageOutcome.OK) ticket.close();
        }
    }

    /**
     * Nesne listesinde adresi birebir eşleşen GÖRSEL satırını arar — AKIŞLA (sürücünün karakter akışından; liste belleğe
     * alınmaz, eşleşmede okuma durur), en çok {@value #MAX_ITEMS} nesne. Okunamayan / bozuk liste "saklanmıyor" sayılır.
     */
    Lookup lookup(long scanId, long monitorId, String url) {
        IndexKey key = new IndexKey(scanId, monitorId);
        long now = clock.getAsLong();
        synchronized (index) {
            CachedIndex c = index.get(key);
            if (c != null) {
                // Geçerli bellek: liste yeniden okunmaz (toplu indirme). Saat geri alınmışsa bellek geçersiz sayılır.
                if (now >= c.loadedAt() && now - c.loadedAt() < INDEX_TTL_MS) return new Lookup(true, true, c.images().get(url));
                index.remove(key);
            }
        }
        itemReads.incrementAndGet();
        long budget = indexMaxChars;
        Lookup lk = jdbc.query(SQL_ITEMS, rs -> {
            if (!rs.next()) return new Lookup(false, false, null);
            Reader reader = rs.getCharacterStream(1);
            if (reader == null) return new Lookup(true, false, null);
            try {
                ImageIndex idx = parseIndex(reader, url, budget);
                if (idx.images() != null) remember(key, now, idx.images());
                return new Lookup(true, true, idx.match());
            } catch (IOException | RuntimeException e) {
                log.warn("Kaynak analizi {} nesne listesi okunamadı: {}", scanId, e.getClass().getSimpleName());
                return new Lookup(true, false, null);
            }
        }, scanId, monitorId);
        return lk == null ? new Lookup(false, false, null) : lk;
    }

    private void remember(IndexKey key, long now, Map<String, StoredImage> images) {
        synchronized (index) {
            index.put(key, new CachedIndex(now, Collections.unmodifiableMap(images)));
            var it = index.entrySet().iterator();
            while (index.size() > INDEX_MAX_ENTRIES && it.hasNext()) {
                it.next();
                it.remove();   // en eski erişilen
            }
        }
    }

    /** {@code items_json} akışında adresi {@code url} olan ilk GÖRSEL nesnesi; yoksa null. Okuyucu bu çağrıda kapatılır. */
    static StoredImage findImage(Reader itemsJson, String url) throws IOException {
        return parseIndex(itemsJson, url, 0).match();
    }

    /**
     * Nesne listesini AKIŞLA (en çok {@value #MAX_ITEMS} nesne) okuyup GÖRSEL dizinini kurar: adres → ilk görsel satırı.
     * Adreslerin toplam uzunluğu {@code budgetChars}'ı aşarsa (ya da bütçe ≤ 0) dizin tutulmaz ({@code images} null) ve
     * eşleşme bulununca okuma durur. Okuyucu bu çağrıda kapatılır.
     */
    static ImageIndex parseIndex(Reader itemsJson, String url, long budgetChars) throws IOException {
        Map<String, StoredImage> images = budgetChars > 0 ? new HashMap<>() : null;
        long chars = 0;
        StoredImage match = null;
        try (JsonParser p = STREAM.createParser(itemsJson)) {
            if (p.nextToken() != JsonToken.START_ARRAY) return new ImageIndex(images == null ? null : Map.of(), null);
            int read = 0;
            while (p.nextToken() == JsonToken.START_OBJECT && read < MAX_ITEMS) {
                read++;
                String u = null, category = null, via = null, sizeSource = null;
                long bytes = 0;
                boolean truncated = false;
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    JsonToken v = p.nextToken();
                    if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {   // headers / findings / library okunmaz
                        p.skipChildren();
                        continue;
                    }
                    switch (name) {
                        case "url" -> u = v == JsonToken.VALUE_STRING ? p.getText() : null;
                        case "category" -> category = v == JsonToken.VALUE_STRING ? p.getText() : null;
                        case "via" -> via = v == JsonToken.VALUE_STRING ? p.getText() : null;
                        case "size_source" -> sizeSource = v == JsonToken.VALUE_STRING ? p.getText() : null;
                        case "bytes" -> bytes = v == JsonToken.VALUE_NUMBER_INT ? p.getLongValue() : 0;
                        case "truncated" -> truncated = v == JsonToken.VALUE_TRUE;
                        default -> { /* okunmayan düz alan */ }
                    }
                }
                if (u == null || !PageResourceDiscovery.IMAGE.equals(category)) continue;
                // Boyut "biliniyor": gövdeden sayılmış ve kesilmemiş ya da başlıktan; gövdesiz / indirilmemiş satırda bilinmiyor
                boolean known = bytes > 0 && !truncated && !ScanItem.SIZE_UNKNOWN.equals(sizeSource);
                StoredImage si = new StoredImage(via, bytes, known);
                if (match == null && u.equals(url)) match = si;
                if (images != null && !images.containsKey(u)) {
                    chars += u.length();
                    if (chars > budgetChars) images = null;   // bütçe aşıldı: dizin bellekte tutulmayacak
                    else images.put(u, si);
                }
                if (images == null && match != null) break;   // yalnız eşleşme aranıyor ve bulundu
            }
            return new ImageIndex(images, match);
        }
    }

    /** Vekil kararı: taramanın o nesne için verdiği karar; saklı karar yoksa (eski tarama) izlemenin kuralı host'a uygulanır. */
    private boolean viaProxy(String storedVia, String url, String proxyMode) {
        if ("proxy".equals(storedVia)) return true;
        if ("direct".equals(storedVia)) return false;
        if (proxyPolicy == null) return false;
        try {
            String host = ProxyPolicyService.hostOf(url);
            return host != null && proxyPolicy.decideForHost(host, proxyMode).viaProxy();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Toplam çekim süresi: başlıklar + gövde (çekirdek ikisini de istek zaman aşımıyla sınırlar) + yönlendirme payı. */
    static long totalBudgetMs(int requestTimeoutMs) {
        return Math.min(TOTAL_BUDGET_MAX_MS, 2L * Math.max(1_000, requestTimeoutMs) + 10_000L);
    }

    private ImageResult fetch(String url, boolean viaProxy) {
        int timeout = scans.config().requestTimeoutMs();
        String ua = pageChecker.effectiveUserAgent();
        PageFetchCore.ScanClients clients = core.newScanClients();
        active.add(clients);
        try {
            if (stopping) return ImageResult.busy();
            // Özgün baytlar: Accept-Encoding identity, ön boyutlu okuma, https → http adımı reddi, TLS zinciri. Saklanan ve
            // açılmış gövde tavanı = sınır + 1: fazlası hiç tamponlanmaz, tavanı aşan gövde ayırt edilir
            PageFetchCore.ScanTuning tuning = PageFetchCore.ScanTuning.original(clients, MAX_IMAGE_BYTES + 1);
            PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(timeout, ua, null, true, false, viaProxy);
            Future<PageFetchCore.OriginalFetch> f;
            try {
                f = core.executor().submit(() -> core.fetchOriginal(url, o, tuning));
            } catch (RejectedExecutionException closing) {
                return ImageResult.busy();
            }
            PageFetchCore.OriginalFetch mf;
            try {
                mf = f.get(totalBudgetMs(timeout), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                return ImageResult.failed("READ_TIMEOUT", null);
            } catch (InterruptedException ie) {
                f.cancel(true);
                Thread.currentThread().interrupt();
                return ImageResult.failed("TRANSPORT_ERROR", null);
            } catch (ExecutionException | CancellationException e) {
                return ImageResult.failed("TRANSPORT_ERROR", null);
            }
            if (mf.downgradeRefused()) return ImageResult.failed(REASON_TLS_DOWNGRADE, null);
            ImageResult r = classify(mf.fetch());
            if (r.outcome() != ImageOutcome.OK) return r;
            String finalUrl = mf.meta() == null || mf.meta().finalUrl() == null ? url : mf.meta().finalUrl();
            return r.withTls(tlsVerdict(finalUrl, mf.peerChain()));
        } finally {
            active.remove(clients);
            clients.shutdownNow();
        }
    }

    /**
     * Son adımın TLS hükmü: http → null (başlık yok); https → zincir sistem deposu ya da kurumsal paketle güvenilir
     * ({@link TrustEvaluator}) VE yaprak sertifika son adımın adını kapsıyor mu. Değerlendirici yoksa null. Çekim trust-all
     * bağlamla yapılır (iç sitelerin olağandışı CA'ları çalışmaya devam eder); güvenilmeyen zincir REDDEDİLMEZ, işaretlenir.
     */
    Boolean tlsVerdict(String finalUrl, X509Certificate[] chain) {
        if (finalUrl == null || !finalUrl.regionMatches(true, 0, "https:", 0, 6)) return null;
        TrustEvaluator te = trustEvaluator;
        if (te == null) return null;
        if (chain == null || chain.length == 0) return Boolean.FALSE;
        try {
            String host = ProxyPolicyService.hostOf(finalUrl);
            return te.evaluate(chain).trusted() && HttpCheckerService.hostnameMatches(chain[0], host);
        } catch (RuntimeException e) {
            return Boolean.FALSE;
        }
    }

    /** Çekim sonucunu yanıta çevirir: engel, ulaşılamama, 2xx olmayan yanıt, tavan, sihirli baytlardan tür. */
    static ImageResult classify(PageFetchCore.Fetch f) {
        if (f.blocked()) {
            // Çözülemeyen ad güvenlik engeli değil ulaşılamamadır (taramanın karar kuralıyla aynı ayrım)
            if (SsrfGuard.isUnresolvableMessage(f.error())) return ImageResult.failed(reason(f, "DNS_RESOLVE"), null);
            return ImageResult.of(ImageOutcome.BLOCKED);
        }
        if (f.status() == 0) return ImageResult.failed(reason(f, "TRANSPORT_ERROR"), null);
        if (f.status() < 200 || f.status() >= 300) return ImageResult.failed(null, f.status());
        byte[] body = f.body();
        if (f.bytes() > MAX_IMAGE_BYTES || (body != null && body.length > MAX_IMAGE_BYTES)) {
            // Gövde okunmadıysa (sunucunun bildirdiği boyut tavanı aşıyordu) bilinen boyut yanıta eklenir
            Long declared = body == null && f.truncated() ? Long.valueOf(f.bytes()) : null;
            return new ImageResult(ImageOutcome.TOO_LARGE, null, null, null, null, 0, null, null, declared);
        }
        String type = ImageDimensions.type(body);
        if (type == null) return ImageResult.of(ImageOutcome.UNSUPPORTED);
        ImageDimensions.Size size = ImageDimensions.readAny(body);
        return new ImageResult(ImageOutcome.OK, body, type, size == null ? null : size.width(),
                size == null ? null : size.height(), 0, null, null, null);
    }

    private static String reason(PageFetchCore.Fetch f, String fallback) {
        try {
            return f.failure() != null ? f.failure().code() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ── 2) "Not ne olurdu?" projeksiyonu ─────────────────────────────────────────────────────

    /**
     * Projeksiyon girdisi: bir görselin yeni boyutu.
     *
     * @param bytes  yeni boyut (bayt)
     * @param width  yeni genişlik (px); verilmediyse null (saklanan kalır)
     * @param type   yeni tür ({@code png|jpeg|gif|webp|bmp|avif}); verilmediyse null
     */
    public record Replacement(String url, long bytes, Integer width, Integer height, String type) {}

    public enum ProjectionOutcome { OK, SCAN_NOT_FOUND, NOT_DONE, ITEMS_GONE, NOT_PROJECTABLE, RATE, BUSY }

    /** {@code reason}: {@code NOT_PROJECTABLE} için {@code algorithm} (eski algoritma sürümü) | {@code baseline}. */
    public record Projection(ProjectionOutcome outcome, Map<String, Object> data, long retryAfterSeconds, String reason) {
        static Projection of(ProjectionOutcome o) {
            return new Projection(o, null, 0, null);
        }

        static Projection notProjectable(String reason) {
            return new Projection(ProjectionOutcome.NOT_PROJECTABLE, null, 0, reason);
        }
    }

    /** {@code NOT_PROJECTABLE} nedenleri. */
    public static final String REASON_ALGORITHM = "algorithm", REASON_BASELINE = "baseline";

    /** Projeksiyonun okuduğu satır. */
    private record Row(String status, boolean capped, Integer grade, Long loadMs, Long pageBytes, Long imageBytes,
                       Integer algorithmVersion, JsonNode analysis, List<Map<String, Object>> items) {}

    /**
     * "Not ne olurdu?" — yazma yok, dış istek yok (kapılar çağıranda geçmiş olmalı). Çok sayfalı taramada ANA sayfa.
     *
     * @param useMeasured önce taramanın kendi ölçtüğü {@code optimized_bytes} her görsele uygulanır
     * @param images      açık girdiler (ölçülmüşün üstüne yazar); adresi görsel nesnesiyle eşleşmeyen girdi sayılır, yok sayılır
     */
    public Projection project(long monitorId, long scanId, boolean useMeasured, List<Replacement> images, String userKey) {
        // Ucuz durum denetimi (büyük kolonlar okunmadan) — sınırdan ÖNCE: reddedilen istek sayılmaz
        Object[] state = jdbc.query(SQL_STATE, rs -> {
            if (!rs.next()) return null;
            int av = rs.getInt(2);
            Integer version = rs.wasNull() ? null : av;
            return new Object[]{rs.getString(1), version, rs.getBoolean(3)};
        }, scanId, monitorId);
        if (state == null) return Projection.of(ProjectionOutcome.SCAN_NOT_FOUND);
        if (!PageResourceScanService.DONE.equals(state[0])) return Projection.of(ProjectionOutcome.NOT_DONE);
        if (!Boolean.TRUE.equals(state[2])) return Projection.of(ProjectionOutcome.ITEMS_GONE);
        if (!Objects.equals(state[1], PageResourceAnalyzer.ALGORITHM_VERSION)) return Projection.notProjectable(REASON_ALGORITHM);

        ScanImageLimiter.Decision d = projectionLimiter.tryAcquire(userKey);
        if (!d.allowed()) {
            return d.busy() ? new Projection(ProjectionOutcome.BUSY, null, BUSY_RETRY_SECONDS, null)
                    : new Projection(ProjectionOutcome.RATE, null, Math.max(1, d.retryAfterSeconds()), null);
        }
        try (ScanImageLimiter.Ticket ticket = d.ticket()) {
            Row row = readRow(scanId, monitorId);
            if (row == null) return Projection.of(ProjectionOutcome.SCAN_NOT_FOUND);
            if (!PageResourceScanService.DONE.equals(row.status())) return Projection.of(ProjectionOutcome.NOT_DONE);
            if (row.items() == null) return Projection.of(ProjectionOutcome.ITEMS_GONE);   // arada ayrıntısı bırakıldı
            return compute(row, useMeasured, images == null ? List.of() : images);
        }
    }

    private Row readRow(long scanId, long monitorId) {
        return jdbc.query(SQL_PROJECTION, rs -> {
            if (!rs.next()) return null;
            String status = rs.getString("status");
            boolean capped = rs.getBoolean("capped");
            int g = rs.getInt("grade");
            Integer grade = rs.wasNull() ? null : g;
            long l = rs.getLong("load_ms");
            Long loadMs = rs.wasNull() ? null : l;
            long pb = rs.getLong("page_bytes");
            Long pageBytes = rs.wasNull() ? null : pb;
            long ib = rs.getLong("image_bytes");
            Long imageBytes = rs.wasNull() ? null : ib;
            int av = rs.getInt("algorithm_version");
            Integer version = rs.wasNull() ? null : av;
            JsonNode analysis = null;
            List<Map<String, Object>> items = null;
            try (Reader a = rs.getCharacterStream("analysis_json")) {
                if (a != null) analysis = JSON.readTree(a);
            } catch (IOException | RuntimeException e) {
                analysis = null;   // bozuk analiz → taban doğrulanamaz (NOT_PROJECTABLE)
            }
            try (Reader i = rs.getCharacterStream("items_json")) {
                if (i != null) items = JSON.readValue(i, new TypeReference<List<Map<String, Object>>>() {});
            } catch (IOException | RuntimeException e) {
                items = null;
            }
            return new Row(status, capped, grade, loadMs, pageBytes, imageBytes, version, analysis, items);
        }, scanId, monitorId);
    }

    private Projection compute(Row row, boolean useMeasured, List<Replacement> images) {
        if (row.items().isEmpty() || row.items().size() > MAX_ITEMS) return Projection.notProjectable(REASON_BASELINE);
        JsonNode scores = row.analysis() == null ? null : row.analysis().get("scores");
        if (scores == null || !scores.isArray()) return Projection.notProjectable(REASON_BASELINE);
        Map<String, Integer> stored = new HashMap<>();
        Integer rbScore = null;
        Object rbValue = null;
        for (JsonNode s : scores) {
            String key = s.path("key").asText(null);
            if (key == null) continue;
            JsonNode sc = s.get("score");
            Integer score = sc != null && sc.isIntegralNumber() ? Integer.valueOf(sc.intValue()) : null;
            stored.put(key, score);
            if ("render_blocking".equals(key)) {
                rbScore = score;
                JsonNode v = s.get("value");
                rbValue = v != null && v.isIntegralNumber() ? Long.valueOf(v.longValue()) : null;
            }
        }

        // Taban: saklanan nesnelerin kopyası üzerinde AYNI hesap — saklanan analizle birebir olmalı
        PageResourceAnalyzer.Performance before = PageResourceAnalyzer.performance(restore(row.items()), rbScore, rbValue);
        if (!sameAsStored(before, row, stored)) {
            log.info("Kaynak analizi projeksiyonu verilmedi: taban saklanan analizle aynı çıkmadı (not {} / saklanan {})",
                    before.grade(), row.grade());
            return Projection.notProjectable(REASON_BASELINE);
        }

        List<ScanItem> changed = restore(row.items());
        Map<String, List<ScanItem>> byUrl = new HashMap<>();
        for (ScanItem it : changed) {
            if (it.url != null && PageResourceDiscovery.IMAGE.equals(it.category)) {
                byUrl.computeIfAbsent(it.url, k -> new ArrayList<>(1)).add(it);
            }
        }
        Set<ScanItem> applied = Collections.newSetFromMap(new IdentityHashMap<>());
        if (useMeasured) {
            for (List<ScanItem> list : byUrl.values()) {
                for (ScanItem it : list) {
                    if (it.optimizedBytes == null) continue;
                    long measured = Math.max(0, it.optimizedBytes);
                    // Ölçüm kazanç göstermiyorsa görsel olduğu gibi kalır — analizin "ölçülmüş kazanç" kuralı (max(0, …))
                    if (measured >= it.bytes) continue;
                    it.bytes = measured;
                    applied.add(it);
                }
            }
        }
        int ignored = 0;
        for (Replacement r : images) {
            List<ScanItem> hit = r.url() == null ? null : byUrl.get(r.url());
            if (hit == null) {
                ignored++;
                continue;
            }
            for (ScanItem it : hit) {
                it.bytes = r.bytes();
                it.truncated = false;
                it.sizeSource = ScanItem.SIZE_BODY;
                it.optimizedBytes = null;
                if (r.width() != null) it.width = r.width();
                if (r.height() != null) it.height = r.height();
                String mime = ImageDimensions.mime(r.type());
                if (mime != null) it.contentType = mime;
                applied.add(it);
            }
        }
        PageResourceAnalyzer.Performance after = PageResourceAnalyzer.performance(changed, rbScore, rbValue);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("algorithm_version", row.algorithmVersion());
        data.put("capped", row.capped());
        data.put("applied", applied.size());
        data.put("ignored", ignored);
        data.put("before", side(before));
        data.put("after", side(after));
        data.put("reused_components", REUSED_COMPONENTS);
        return new Projection(ProjectionOutcome.OK, data, 0, null);
    }

    /**
     * Saklanan satırlardan nesne listesi (her çağrıda YENİ kopya). Şelale tahmininin {@code parent}'ı saklanmaz; nesnenin
     * {@code found_in}'i (onu bulduran stilin adresi) listede o adresi taşıyan İLK nesneye bağlanır — taramadaki
     * {@code parent} ile aynı sonuç (aynı adres iki kez maskelenip çakışırsa taban doğrulaması yakalar).
     */
    static List<ScanItem> restore(List<Map<String, Object>> rows) {
        List<ScanItem> items = new ArrayList<>(rows.size());
        Map<String, Integer> first = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            ScanItem it = ScanItem.fromStored(rows.get(i));
            it.seq = i;
            if (it.url != null) first.putIfAbsent(it.url, i);
            items.add(it);
        }
        for (int i = 1; i < items.size(); i++) {
            ScanItem it = items.get(i);
            Integer p = it.foundIn == null || PageResourceScanService.FOUND_IN_PAGE.equals(it.foundIn) ? null : first.get(it.foundIn);
            it.parent = p == null ? 0 : p;
        }
        return items;
    }

    /** Yeniden hesaplanan taban, saklanan özet ve bileşen puanlarıyla birebir mi. */
    private static boolean sameAsStored(PageResourceAnalyzer.Performance p, Row row, Map<String, Integer> stored) {
        if (row.grade() == null || row.grade() != p.grade()) return false;
        if (row.loadMs() == null || row.loadMs() != p.loadMs()) return false;
        if (row.pageBytes() == null || row.pageBytes() != p.pageBytes()) return false;
        if (row.imageBytes() == null || row.imageBytes() != p.imageBytes()) return false;
        for (Map<String, Object> s : p.scores()) {
            String key = (String) s.get("key");
            if (!stored.containsKey(key) || !Objects.equals(stored.get(key), s.get("score"))) return false;
        }
        return stored.size() == p.scores().size();
    }

    /** Yanıtın bir tarafı: not, harf, toplamlar, büyük görsel sayısı, bileşen → {puan, kayıp}. */
    private static Map<String, Object> side(PageResourceAnalyzer.Performance p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grade", p.grade());
        m.put("letter", p.letter());
        m.put("page_bytes", p.pageBytes());
        m.put("image_bytes", p.imageBytes());
        m.put("load_ms", p.loadMs());
        m.put("large_images", p.largeImages());
        Map<String, Object> scores = new LinkedHashMap<>();
        for (Map<String, Object> s : p.scores()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("score", s.get("score"));
            row.put("lost", s.get("lost"));
            scores.put((String) s.get("key"), row);
        }
        m.put("scores", scores);
        return m;
    }

    // ── Kapanış ─────────────────────────────────────────────────────────────────────────────

    /**
     * Yalnız bayrak + uçuştaki istekleri kes (beklemez): yeni indirme alınmaz, sürenler hemen düşer. Erken sırada
     * ({@code HIGHEST_PRECEDENCE + 5}): bildirim havuzu boşaltmasının ≤ 20 sn beklemesi sırasında yeni indirme başlamaz.
     */
    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 5)
    public void onContextClosed(ContextClosedEvent e) {
        stopping = true;
        for (PageFetchCore.ScanClients c : active) c.shutdownNow();
    }
}
