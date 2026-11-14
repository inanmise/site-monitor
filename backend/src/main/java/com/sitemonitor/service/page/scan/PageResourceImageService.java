package com.sitemonitor.service.page.scan;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.page.PageFetchCore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
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

    private final ScanImageLimiter imageLimiter;
    private final ScanImageLimiter projectionLimiter;
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
        this.imageLimiter = new ScanImageLimiter(IMAGES_PER_MINUTE, IMAGES_PER_USER, IMAGES_PER_POD, clock);
        this.projectionLimiter = new ScanImageLimiter(PROJECTIONS_PER_MINUTE, 0, 0, clock);
    }

    /** Testler için (alan enjeksiyonu yerine). */
    void setProxyPolicy(ProxyPolicyService p) {
        this.proxyPolicy = p;
    }

    /** Testler için: indirme sınırlayıcısı. */
    ScanImageLimiter imageLimiter() {
        return imageLimiter;
    }

    // ── 1) Özgün görsel ─────────────────────────────────────────────────────────────────────

    public enum ImageOutcome {
        OK, SCAN_NOT_FOUND, ITEMS_GONE, NOT_FOUND, MASKED, TOO_LARGE, RATE, BUSY, BLOCKED, FETCH_FAILED, UNSUPPORTED
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
     * @param knownBytes        {@code TOO_LARGE}: taramanın ölçtüğü boyut (çekilmeden reddedildiyse); bilinmiyorsa null
     */
    public record ImageResult(ImageOutcome outcome, byte[] body, String type, Integer width, Integer height,
                              long retryAfterSeconds, String reason, Integer httpStatus, Long knownBytes) {
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
        try (ScanImageLimiter.Ticket ticket = d.ticket()) {
            return fetch(url, viaProxy(img.via(), url, proxyMode));
        }
    }

    /**
     * Nesne listesinde adresi birebir eşleşen GÖRSEL satırını arar — AKIŞLA (sürücünün karakter akışından; liste belleğe
     * alınmaz, eşleşmede okuma durur), en çok {@value #MAX_ITEMS} nesne. Okunamayan / bozuk liste "saklanmıyor" sayılır.
     */
    Lookup lookup(long scanId, long monitorId, String url) {
        Lookup lk = jdbc.query(SQL_ITEMS, rs -> {
            if (!rs.next()) return new Lookup(false, false, null);
            Reader reader = rs.getCharacterStream(1);
            if (reader == null) return new Lookup(true, false, null);
            try {
                return new Lookup(true, true, findImage(reader, url));
            } catch (IOException | RuntimeException e) {
                log.warn("Kaynak analizi {} nesne listesi okunamadı: {}", scanId, e.getClass().getSimpleName());
                return new Lookup(true, false, null);
            }
        }, scanId, monitorId);
        return lk == null ? new Lookup(false, false, null) : lk;
    }

    /** {@code items_json} akışında adresi {@code url} olan ilk GÖRSEL nesnesi; yoksa null. Okuyucu bu çağrıda kapatılır. */
    static StoredImage findImage(Reader itemsJson, String url) throws IOException {
        try (JsonParser p = STREAM.createParser(itemsJson)) {
            if (p.nextToken() != JsonToken.START_ARRAY) return null;
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
                if (url.equals(u) && PageResourceDiscovery.IMAGE.equals(category)) {
                    // Boyut "biliniyor": gövdeden sayılmış ve kesilmemiş ya da başlıktan; gövdesiz / indirilmemiş satırda bilinmiyor
                    boolean known = bytes > 0 && !truncated && !ScanItem.SIZE_UNKNOWN.equals(sizeSource);
                    return new StoredImage(via, bytes, known);
                }
            }
            return null;
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
            // Saklanan ve açılmış gövde tavanı = sınır + 1: fazlası hiç tamponlanmaz, tavanı aşan gövde ayırt edilir
            PageFetchCore.ScanTuning tuning = new PageFetchCore.ScanTuning(clients, PageFetchCore.ACCEPT_ENCODING,
                    MAX_IMAGE_BYTES + 1, MAX_IMAGE_BYTES + 1);
            PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(timeout, ua, null, true, false, viaProxy);
            Future<PageFetchCore.MetaFetch> f;
            try {
                f = core.executor().submit(() -> core.fetchWithMeta(url, "GET", o, 0, tuning));
            } catch (RejectedExecutionException closing) {
                return ImageResult.busy();
            }
            PageFetchCore.MetaFetch mf;
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
            return classify(mf.fetch());
        } finally {
            active.remove(clients);
            clients.shutdownNow();
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
            return ImageResult.of(ImageOutcome.TOO_LARGE);
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

    public enum ProjectionOutcome { OK, SCAN_NOT_FOUND, NOT_DONE, ITEMS_GONE, NOT_PROJECTABLE, RATE }

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
        if (!d.allowed()) return new Projection(ProjectionOutcome.RATE, null, Math.max(1, d.retryAfterSeconds()), null);
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

    /** Yalnız bayrak + uçuştaki istekleri kes (beklemez): yeni indirme alınmaz, sürenler hemen düşer. */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent e) {
        stopping = true;
        for (PageFetchCore.ScanClients c : active) c.shutdownNow();
    }
}
