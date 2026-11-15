package com.sitemonitor.service.page.scan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.config.ShutdownDeadline;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.PublicSuffixService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.page.PageFetchCore;
import com.sitemonitor.service.page.scan.deep.AssetSample;
import com.sitemonitor.service.page.scan.deep.CookieFacts;
import com.sitemonitor.service.page.scan.deep.DeepContext;
import com.sitemonitor.service.page.scan.deep.FontFaces;
import com.sitemonitor.service.page.scan.deep.HtmlFacts;
import com.sitemonitor.service.page.scan.deep.DeepUrls;
import com.sitemonitor.service.page.scan.site.SiteCrawl;
import com.sitemonitor.service.page.scan.site.SitePages;
import com.sitemonitor.service.page.scan.site.SitemapReader;
import com.sitemonitor.util.Msg;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Sayfa Bütünlüğü "Kaynak analizi" (2026-10-31, kullanıcı isteği) — YALNIZ kullanıcı isteyince çalışan nesne taraması.
 *
 * <p>Akış: izlemenin sayfası sunucudan indirilir ({@link PageFetchCore}), nesneler keşfedilir ({@link PageResourceDiscovery}:
 * sayfanın yükledikleri → stilin {@code url()} / {@code @import} ettikleri → bağlantılı belgeler → diğer bağlantılar), sayfa
 * yükü nesneleri GET ile indirilip boyutu ve süresi ölçülür, bağlantı ve belgeler önce HEAD ile yoklanır (sınırlı
 * eşzamanlılık, bayt / süre / nesne tavanları), en büyük görseller sunucuda yeniden kodlanıp ölçülmüş kazanç çıkarılır, sonra
 * {@link PageResourceAnalyzer} not, tahmini yükleme süresi, öneriler ve görsel etkisini çıkarır.
 *
 * <p><b>Algoritma 2 (2026-11, inceleme H1/H7/H9/H10/H11/H13/H15/H16):</b>
 * <ul>
 *   <li>Vekil kararı NESNE başına: izlemenin kuralı ({@link ProxyPolicyService#decideForHost}) her nesnenin kendi host'una
 *       uygulanır (NO_PROXY'deki alan doğrudan, dış CDN izlemenin tercihiyle); nesne {@code via} alanı yolu söyler.</li>
 *   <li>Kırık kararı Sayfa Bütünlüğü izlemesiyle AYNI ({@link #verdictOf}): 401 / 403 / 429 / 451 / 503 / diğer 4xx ve
 *       standart dışı kodlar (999) "doğrulanamadı", SSRF / kurum DNS engeli "engellendi"; tek seferlik ağ hatası ve ≥ 400
 *       bir kez yeniden denenir (zaman aşımı denenmez).</li>
 *   <li>Bağlantı ve belgeler HEAD-önce (desteklenmezse gövdesiz GET), boyut {@code Content-Length}'ten — gövde indirilmez.</li>
 *   <li>Tarama başına ayrı istemciler ({@link PageFetchCore.ScanClients}): izleme kontrollerinin havuzunu paylaşmaz; iptal /
 *       süre dolumu / kapanışta uçuştaki istekler KESİLİR ve pod yuvası uçuştaki işler bitince bırakılır.</li>
 *   <li>TTFB yalnız son adımın ilk baytı; yönlendirme sayısı ve süresi ayrı ({@code redirect_count}, {@code redirect_ms}).</li>
 * </ul>
 *
 * <p><b>Sınırlar:</b> pod başına aynı anda {@value #MAX_PER_POD} tarama, izleme başına tek çalışan tarama (iki pod yarışırsa
 * küçük kimlik kazanır), kişi başına dakikada {@value #STARTS_PER_MINUTE} başlatma (yük sınırı, pod belleği). Tarama izlemeye
 * DOKUNMAZ: kontrol kaydı, alarm, bildirim yok.
 *
 * <p><b>Yarıda kalma / iptal:</b> çalışan tarama her saniye {@code heartbeat_ms} yazar; {@value #STALE_MS} ms'den eski RUNNING
 * satırı okunurken INTERRUPTED'e çekilir (pod öldü). İptal ({@link #cancel}) satırı tek koşullu UPDATE ile INTERRUPTED /
 * CANCELLED yapar ve {@code cancel_requested} yazar — taramayı koşan pod (hangisi olursa olsun) bir sonraki kalp atışında
 * satırın artık kendisinin olmadığını görüp durur; aynı pod'daysa hemen. Kapanışta ({@link ContextClosedEvent}) çalışan
 * taramalar ölçümü bırakır ve kendi satırlarını INTERRUPTED yazar.
 *
 * <p><b>Algoritma 3 (2026-11, derin analiz A–E):</b> HTML tek kez ayrıştırılır ve olguları ({@link HtmlFacts}) çıkarılır;
 * keşfedilen host'ların bağlantı kurulumu ölçülür (≤ {@value PageResourceAnalyzer#HOST_TIMING_LIMIT}, vekil üzerinden
 * gidilenler ölçülmez); her nesnenin HTTP sürümü, tel gövdesi özeti, betik örneği (küçültme / kaynak haritası / kütüphane),
 * {@code Set-Cookie}'leri (DEĞERSİZ) ve ana sayfanın güvenlik başlıkları toplanır; stillerin {@code @font-face}'leri okunur;
 * açıkta olabilecek kaynak haritaları HEAD ile denetlenir (≤ {@value PageResourceAnalyzer#SOURCE_MAP_LIMIT}). Yeni istekler
 * aynı SSRF / vekil / iptal / süre kurallarına uyar. JavaScript yine ÇALIŞTIRILMAZ.
 *
 * <p><b>Saklama:</b> özet {@code site.monitor.page.resource-scan.retention-days} (varsayılan 1 yıl; liste penceresi de bu),
 * nesne ayrıntısı izleme başına son {@value #DETAIL_RUNS} çalıştırma (her bitişte eskilerin {@code items_json}'u NULL'a çekilir).
 */
@Slf4j
@Service
public class PageResourceScanService {

    public static final int MAX_PER_POD = 2;
    public static final int STARTS_PER_MINUTE = 3;
    public static final int DETAIL_RUNS = 20;
    public static final long STALE_MS = 45_000L;
    static final long HEARTBEAT_MS = 1_000L;
    /** Saklama ayarı okunamazsa liste penceresi (gün) — politikanın varsayılanı. */
    static final int HISTORY_DAYS = 365;
    /** Saklama politikasının tabanı ({@code RetentionCatalog} {@code page-resource-scans}). */
    static final int HISTORY_DAYS_MIN = 30;
    static final int LIST_MAX = 200;
    /** İzin verilen en uzun stil zinciri ({@code @import} içinde {@code @import}). */
    static final int CSS_DEPTH = 2;
    /** Yeniden denemeden önceki bekleme (Sayfa Bütünlüğü izlemesiyle aynı). */
    static final long RETRY_DELAY_MS = 300L;
    /** Kapanış / iptal sonrası uçuştaki işlerin bitmesi için en çok bekleme (sonra pod yuvası yine bırakılır). */
    static final long QUIET_WAIT_MS = 5_000L;
    /** Tarama HTML'inin açılmış gövde tavanı (jsoup girdisi; bellek tepesi — inceleme H15). */
    static final int HTML_MAX_DECODED = 8 * 1024 * 1024;
    /** Host bağlantı ölçümü: eşzamanlılık, adım başına zaman aşımı tavanı ve aşamanın en uzun süresi. */
    static final int HOST_TIMING_CONCURRENCY = 6;
    static final int HOST_TIMING_TIMEOUT_MS = 5_000;
    static final long HOST_TIMING_PHASE_MS = 15_000L;
    /** Kaynak haritası denetiminin en uzun süresi. */
    static final long SOURCE_MAP_PHASE_MS = 15_000L;
    /** Bellekte tutulan en çok çerez / {@code @font-face} / stil başvurusu (tarama başına). */
    static final int MAX_COOKIES = 200, MAX_FONT_FACES = 1000, MAX_CSS_REFERENCED = 10_000;

    // ── Ölçülmüş görsel kazancı (inceleme P11) ──
    static final int OPT_MAX_IMAGES = 20;
    static final long OPT_MIN_BYTES = 100 * PageResourceAnalyzer.KB;
    static final long OPT_MAX_PIXELS = 40_000_000L;
    static final int OPT_MAX_BODY = (int) PageFetchCore.MAX_COUNT_BYTES;
    /** Görsel aşamasının taramanın içindeki en uzun süresi. */
    static final long OPT_PHASE_MS = 60_000L;
    /** Pod genelinde aynı anda yeniden kodlanan görsel. */
    static final int OPT_CONCURRENT = 2;
    /**
     * Pod genelinde görsel çözme bellek bütçesi (MB). İzin İKİNCİ indirmenin başlığından hesaplanan çalışma belleğiyle alınır
     * ({@link #measurePlan}); bütçeyi aşan görsel ölçülmez, büyük görsel tek başına çalışır.
     */
    static final int OPT_MEMORY_MB = 200;

    public static final String KEY_MAX_SECONDS = "site.monitor.page.resource-scan.max-seconds";
    public static final String KEY_CONCURRENCY = "site.monitor.page.resource-scan.concurrency";
    public static final String KEY_MAX_OBJECTS = "site.monitor.page.resource-scan.max-objects";
    public static final String KEY_MAX_TOTAL_MB = "site.monitor.page.resource-scan.max-total-mb";
    public static final String KEY_REQUEST_TIMEOUT_MS = "site.monitor.page.resource-scan.request-timeout-ms";
    public static final String KEY_RETENTION_DAYS = "site.monitor.page.resource-scan.retention-days";
    // ── G çok sayfalı analiz (prs3 be2) ──
    /** Çok sayfalı taramada en çok sayfa (ana sayfa dahil) — istek bunu aşamaz. */
    public static final String KEY_MAX_PAGES = "site.monitor.page.resource-scan.max-pages";
    /**
     * Çok sayfalı taramanın TEK süre bütçesi (tüm site), 120–900 sn (D-5: site analizi pod'un 2 tarama yuvasından birini en çok
     * 15 dk tutar; aralık dışı / okunamayan değer → 900).
     */
    public static final String KEY_SITE_MAX_SECONDS = "site.monitor.page.resource-scan.site-max-seconds";
    public static final int MAX_PAGES_DEFAULT = 10, MAX_PAGES_MIN = 2, MAX_PAGES_MAX = 20;
    public static final int SITE_MAX_SECONDS_DEFAULT = 900, SITE_MAX_SECONDS_MIN = 120, SITE_MAX_SECONDS_MAX = 900;
    /** Özet satırının {@code scope} değerleri (eski satır null = {@code page}). */
    public static final String SCOPE_PAGE = "page", SCOPE_SITE = "site";
    // ── Tüm site taraması (2026-11-15, kullanıcı: "tüm site taranmalı, nerede ne kadar nesne varsa hepsi analiz edilmeli, eksik
    // analiz edilen sayfa olmamalı"; karar: en çok 1000 sayfa / 60 dk, site haritası + her derinlikte iç bağlantılar) ──
    public static final String KEY_FULL_MAX_PAGES = "site.monitor.page.resource-scan.full-site-max-pages";
    public static final String KEY_FULL_MAX_SECONDS = "site.monitor.page.resource-scan.full-site-max-seconds";
    public static final String KEY_FULL_MAX_OBJECTS = "site.monitor.page.resource-scan.full-site-max-objects";
    public static final String KEY_FULL_MAX_TOTAL_MB = "site.monitor.page.resource-scan.full-site-max-total-mb";
    // 2026-11-15 (kullanıcı isteği: "tarama sınırlarını 2500 sayfa olacak şekilde arttıralım"): 1000 → 2500 sayfa; süre,
    // nesne ve indirme bütçeleri aynı oranda (×2,5) büyür — yoksa 2500 sayfalık tarama süreye / nesneye takılırdı.
    // Bellek: SiteRun önbelleği benzersiz nesne başına bir ölçüm kopyası tutar (≈ 1–2 KB) → 75 000 nesnede ≈ 150 MB üst sınır.
    public static final int FULL_MAX_PAGES_DEFAULT = 2500, FULL_MAX_PAGES_MIN = 20, FULL_MAX_PAGES_MAX = 2500;
    public static final int FULL_MAX_SECONDS_DEFAULT = 9000, FULL_MAX_SECONDS_MIN = 300, FULL_MAX_SECONDS_MAX = 10_800;
    public static final int FULL_MAX_OBJECTS_DEFAULT = 75_000, FULL_MAX_OBJECTS_MIN = 2_000, FULL_MAX_OBJECTS_MAX = 150_000;
    public static final int FULL_MAX_TOTAL_MB_DEFAULT = 7500, FULL_MAX_TOTAL_MB_MIN = 200, FULL_MAX_TOTAL_MB_MAX = 25_000;
    /** Özet satırının {@code crawl} değeri: tüm site taraması (null = sayfa sayısı seçilen site taraması ya da tek sayfa). */
    public static final String CRAWL_FULL = "full";
    /** Analizde listelenen taranmamış adres tavanı (toplam {@code unscanned_total} ayrıca). */
    static final int MAX_UNSCANNED_LISTED = 2_000;
    /** Denetim noktasında saklanan kuyruk tavanı. */
    static final int CHECKPOINT_QUEUE_MAX = 5_000;
    /** Denetim noktası en geç bu kadar sayfada ya da bu sürede bir yazılır (pod durursa kaldığı yerden sürdürmek için). */
    static final int CHECKPOINT_EVERY_PAGES = 25;
    static final long CHECKPOINT_EVERY_MS = 120_000;
    /** Sürdürülen zincirde (önceki + yeni) en çok sayfa satırı. */
    public static final int RESUME_MAX_ROWS = 5_000;
    /** Site haritası evresinin süre ve dosya tavanları. */
    static final long SITEMAP_PHASE_MS = 120_000;
    static final int SITEMAP_MAX_FILES = 50;
    static final int SITEMAP_MAX_BYTES = 10 * 1024 * 1024;
    static final int ROBOTS_MAX_BYTES = 512 * 1024;
    /** Site haritası hata listesi tavanı (adresler maskeli). */
    static final int SITEMAP_MAX_ERRORS = 10;
    /**
     * Tüm site taramasında bellekte tutulan sayfa-kaynak kaydı tavanı (her sayfanın yüklediği nesneler — tekil / ortak bayt
     * hesabı için). Dolunca tarama "nesne sınırı" nedeniyle durur, kalan sayfalar listelenir ve sürdürülebilir (≈ 50 MB).
     */
    static final int MAX_RETAINED_RESOURCES = 400_000;

    public static final String RUNNING = "RUNNING", DONE = "DONE", FAILED = "FAILED", INTERRUPTED = "INTERRUPTED";
    /** {@code found_in} değeri: nesne HTML'de (ya da satır içi stilde) bulundu. */
    public static final String FOUND_IN_PAGE = "page";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SUMMARY_COLS = "id, monitor_id, url, status, via, started_at, finished_at, heartbeat_ms, requested_by, "
            + "progress_done, progress_total, grade, grade_letter, load_ms, html_ttfb_ms, html_ms, page_bytes, request_count, "
            + "image_bytes, image_count, document_count, document_bytes, link_count, link_bytes, failed_count, capped, error, "
            + "items_kept, owner_pod, redirect_count, redirect_ms, algorithm_version, cancel_requested, "
            + "js_bytes, css_bytes, font_bytes, security_score, seo_score, a11y_score, scope, pages_count, pages_done, pages_total, "
            + "site_crawl, CASE WHEN site_checkpoint IS NULL THEN FALSE ELSE TRUE END AS site_resumable";

    static final String SQL_INSERT = "INSERT INTO page_resource_scans (monitor_id, team_id, url, status, via, started_at, "
            + "started_ms, heartbeat_ms, owner_pod, requested_by, progress_done, progress_total, capped, items_kept, "
            + "algorithm_version, scope) VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, 0, 0, FALSE, FALSE, ?, ?)";
    static final String SQL_RUNNING = "SELECT " + SUMMARY_COLS + " FROM page_resource_scans WHERE monitor_id = ? "
            + "AND status = 'RUNNING' ORDER BY id";
    static final String SQL_SETTLE_STALE = "UPDATE page_resource_scans SET status = 'INTERRUPTED', finished_at = ?, "
            + "error = 'STALE' WHERE monitor_id = ? AND status = 'RUNNING' AND heartbeat_ms < ?";
    static final String SQL_PROGRESS = "UPDATE page_resource_scans SET progress_done = ?, progress_total = ?, heartbeat_ms = ? "
            + "WHERE id = ? AND status = 'RUNNING'";
    static final String SQL_DONE = "UPDATE page_resource_scans SET status = 'DONE', finished_at = ?, heartbeat_ms = ?, "
            + "progress_done = ?, progress_total = ?, grade = ?, grade_letter = ?, load_ms = ?, html_ttfb_ms = ?, html_ms = ?, "
            + "page_bytes = ?, request_count = ?, image_bytes = ?, image_count = ?, document_count = ?, document_bytes = ?, "
            + "link_count = ?, link_bytes = ?, failed_count = ?, capped = ?, analysis_json = ?, items_json = ?, items_kept = TRUE, "
            + "redirect_count = ?, redirect_ms = ?, js_bytes = ?, css_bytes = ?, font_bytes = ?, security_score = ?, "
            + "seo_score = ?, a11y_score = ? WHERE id = ? AND status = 'RUNNING'";
    /** Çok sayfalı tarama bitişi: {@link #SQL_DONE} + {@code scope = 'site'} ve {@code pages_count} (tamamlanan sayfa). */
    static final String SQL_DONE_SITE = "UPDATE page_resource_scans SET status = 'DONE', finished_at = ?, heartbeat_ms = ?, "
            + "progress_done = ?, progress_total = ?, grade = ?, grade_letter = ?, load_ms = ?, html_ttfb_ms = ?, html_ms = ?, "
            + "page_bytes = ?, request_count = ?, image_bytes = ?, image_count = ?, document_count = ?, document_bytes = ?, "
            + "link_count = ?, link_bytes = ?, failed_count = ?, capped = ?, analysis_json = ?, items_json = ?, items_kept = TRUE, "
            + "redirect_count = ?, redirect_ms = ?, js_bytes = ?, css_bytes = ?, font_bytes = ?, security_score = ?, "
            + "seo_score = ?, a11y_score = ?, scope = 'site', pages_count = ? WHERE id = ? AND status = 'RUNNING'";
    /** Çok sayfalı koşunun sayfa ilerlemesi (seçimde toplam, her sayfa kararında bitmiş) — yalnız çalışan satıra. */
    static final String SQL_SITE_PROGRESS = "UPDATE page_resource_scans SET pages_done = ?, pages_total = ? "
            + "WHERE id = ? AND status = 'RUNNING'";
    static final String SQL_END = "UPDATE page_resource_scans SET status = ?, finished_at = ?, heartbeat_ms = ?, error = ?, "
            + "html_ttfb_ms = ?, html_ms = ?, redirect_count = ?, redirect_ms = ? WHERE id = ? AND status = 'RUNNING'";
    /** İptal: tek koşullu yazım — çalışan pod bir sonraki kalp atışında satırın artık RUNNING olmadığını görüp durur. */
    static final String SQL_CANCEL = "UPDATE page_resource_scans SET status = 'INTERRUPTED', finished_at = ?, heartbeat_ms = ?, "
            + "error = 'CANCELLED', cancel_requested = TRUE WHERE id = ? AND monitor_id = ? AND status = 'RUNNING'";
    static final String SQL_CANCEL_FLAG = "SELECT cancel_requested FROM page_resource_scans WHERE id = ?";
    static final String SQL_LIST = "SELECT " + SUMMARY_COLS + " FROM page_resource_scans WHERE monitor_id = ? AND started_at >= ? "
            + "ORDER BY id DESC LIMIT ?";
    static final String SQL_ONE = "SELECT " + SUMMARY_COLS + ", analysis_json, items_json FROM page_resource_scans "
            + "WHERE id = ? AND monitor_id = ?";
    static final String SQL_KEPT_IDS = "SELECT id FROM page_resource_scans WHERE monitor_id = ? AND items_json IS NOT NULL "
            + "ORDER BY id DESC";
    static final String SQL_DROP_ITEMS = "UPDATE page_resource_scans SET items_json = NULL, items_kept = FALSE WHERE id = ?";
    /** Tüm site taraması denetim noktası tutulan satırlar (en yeni önce) — {@value #CHECKPOINT_KEEP} dışındakiler bırakılır. */
    static final String SQL_CHECKPOINT_IDS = "SELECT id FROM page_resource_scans WHERE monitor_id = ? AND site_checkpoint IS NOT NULL "
            + "AND status <> 'RUNNING' ORDER BY id DESC";
    static final String SQL_DROP_CHECKPOINT = "UPDATE page_resource_scans SET site_checkpoint = NULL WHERE id = ? AND status <> 'RUNNING'";
    /** İzleme başına tutulan en çok tüm site denetim noktası (sürdürme yalnız bunlardan). */
    static final int CHECKPOINT_KEEP = 5;
    static final String SQL_DELETE_MONITOR = "DELETE FROM page_resource_scans WHERE monitor_id = ?";
    /** Tüm site taraması (2026-11-15). */
    static final String SQL_SET_CRAWL = "UPDATE page_resource_scans SET site_crawl = ? WHERE id = ?";
    static final String SQL_CHECKPOINT = "UPDATE page_resource_scans SET site_checkpoint = ? WHERE id = ? AND status = 'RUNNING'";
    static final String SQL_CHECKPOINT_FINAL = "UPDATE page_resource_scans SET site_checkpoint = ? WHERE id = ?";
    static final String SQL_RESUME_SOURCE = "SELECT status, site_crawl, site_checkpoint FROM page_resource_scans "
            + "WHERE id = ? AND monitor_id = ?";

    private final JdbcTemplate jdbc;
    private final PageFetchCore core;
    private final PageCheckerService pageChecker;
    private final PublicSuffixService publicSuffix;
    private final AppSettingsService settings;

    @Autowired(required = false)
    private ProxyPolicyService proxyPolicy;

    private final Semaphore podSlots = new Semaphore(MAX_PER_POD);
    /** Site haritası açma + ayrıştırma: pod başına tek dosya (açılmış dosya ≤ 50 MB — iki tarama aynı anda bellek doldurmasın). */
    private final Semaphore sitemapParse = new Semaphore(1);
    private final Semaphore imageSlots = new Semaphore(OPT_CONCURRENT);
    private final Semaphore imageMemory = new Semaphore(OPT_MEMORY_MB);
    private final Object startLock = new Object();
    private final ConcurrentHashMap<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    /** Bu pod'da koşan taramalar (aynı pod'a düşen iptal hemen keser). */
    private final ConcurrentHashMap<Long, Run> active = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    private volatile ExecutorService executor;
    /** Testler için: saat. */
    java.util.function.LongSupplier clock = System::currentTimeMillis;

    public PageResourceScanService(JdbcTemplate jdbc, PageFetchCore core, PageCheckerService pageChecker,
                                   PublicSuffixService publicSuffix, AppSettingsService settings) {
        this.jdbc = jdbc;
        this.core = core;
        this.pageChecker = pageChecker;
        this.publicSuffix = publicSuffix;
        this.settings = settings;
    }

    /** Testler için (alan enjeksiyonu yerine). */
    void setProxyPolicy(ProxyPolicyService p) {
        this.proxyPolicy = p;
    }

    /** Testler için: boştaki pod yuvası (uçuştaki işler bitince bırakılır). */
    int freeSlots() {
        return podSlots.availablePermits();
    }

    // ── Ayarlar ─────────────────────────────────────────────────────────────────────────────

    /** Canlı sınırlar (okunamayan / aralık dışı değer varsayılana döner). */
    public record Config(int maxSeconds, int concurrency, int maxObjects, int maxTotalMb, int requestTimeoutMs) {}

    public Config config() {
        return new Config(
                bounded(KEY_MAX_SECONDS, 300, 60, 900),
                bounded(KEY_CONCURRENCY, 6, 1, 16),
                bounded(KEY_MAX_OBJECTS, 1500, 100, 5000),
                bounded(KEY_MAX_TOTAL_MB, 200, 10, 1000),
                bounded(KEY_REQUEST_TIMEOUT_MS, 15_000, 2_000, 60_000));
    }

    /** Çok sayfalı taramanın canlı sınırları: en çok sayfa (ana sayfa dahil) ve tüm sitenin süre bütçesi (sn). */
    public record SiteLimits(int maxPages, int maxSeconds) {}

    public SiteLimits siteLimits() {
        return new SiteLimits(bounded(KEY_MAX_PAGES, MAX_PAGES_DEFAULT, MAX_PAGES_MIN, MAX_PAGES_MAX),
                bounded(KEY_SITE_MAX_SECONDS, SITE_MAX_SECONDS_DEFAULT, SITE_MAX_SECONDS_MIN, SITE_MAX_SECONDS_MAX));
    }

    /** Tüm site taramasının canlı sınırları: yeni sayfa, süre (sn), site geneli nesne ve bayt (MB) tavanı. */
    public record FullSiteLimits(int maxPages, int maxSeconds, int maxObjects, int maxTotalMb) {}

    public FullSiteLimits fullSiteLimits() {
        return new FullSiteLimits(bounded(KEY_FULL_MAX_PAGES, FULL_MAX_PAGES_DEFAULT, FULL_MAX_PAGES_MIN, FULL_MAX_PAGES_MAX),
                bounded(KEY_FULL_MAX_SECONDS, FULL_MAX_SECONDS_DEFAULT, FULL_MAX_SECONDS_MIN, FULL_MAX_SECONDS_MAX),
                bounded(KEY_FULL_MAX_OBJECTS, FULL_MAX_OBJECTS_DEFAULT, FULL_MAX_OBJECTS_MIN, FULL_MAX_OBJECTS_MAX),
                bounded(KEY_FULL_MAX_TOTAL_MB, FULL_MAX_TOTAL_MB_DEFAULT, FULL_MAX_TOTAL_MB_MIN, FULL_MAX_TOTAL_MB_MAX));
    }

    /**
     * Çok sayfalı tarama isteği (gövde {@code {scope:"site", max_pages:N}}). Yoksa bugünkü tek sayfa analizi.
     *
     * @param maxPages   en çok sayfa, ana sayfa dahil (çağıran {@link #siteLimits()} / {@link #fullSiteLimits()} tavanına kırpar)
     * @param full       tüm site taraması (her derinlikte iç bağlantılar + site haritası; 2026-11-15)
     * @param resumeFrom tüm site taramasında sürdürülecek önceki taramanın kimliği (null = baştan)
     */
    public record SiteOptions(int maxPages, boolean full, Long resumeFrom) {
        public SiteOptions(int maxPages) {
            this(maxPages, false, null);
        }
    }

    private int bounded(String key, int def, int min, int max) {
        int v;
        try {
            v = settings == null ? def : settings.getInt(key, def);
        } catch (RuntimeException e) {
            v = def;
        }
        return v < min || v > max ? def : v;
    }

    /**
     * Liste penceresi (gün) = saklama ayarı (inceleme H13): saklama politikasıyla aynı kural — ayar, tabanı
     * {@value #HISTORY_DAYS_MIN}'un altına inemez; okunamazsa {@value #HISTORY_DAYS}. 100 yılla sınırlı (taşma yok).
     */
    int historyDays() {
        int v;
        try {
            v = settings == null ? HISTORY_DAYS : settings.getInt(KEY_RETENTION_DAYS, HISTORY_DAYS);
        } catch (RuntimeException e) {
            v = HISTORY_DAYS;
        }
        return Math.min(36_500, Math.max(HISTORY_DAYS_MIN, v));
    }

    // ── Başlatma ────────────────────────────────────────────────────────────────────────────

    /** Başlatma sonucu: STARTED (yeni), RUNNING (izlemenin zaten çalışan taraması), BUSY (pod dolu), RATE (kişi sınırı). */
    public enum Outcome { STARTED, RUNNING, BUSY, RATE }

    public record Start(Outcome outcome, Map<String, Object> scan, long retryAfterSeconds) {}

    /**
     * Taramayı başlatır (kapılar — izin + takım kapsamı — çağıranda geçmiş olmalı). Asla uzun sürmez: ölçüm arka planda.
     *
     * @param userKey kişi sınırı anahtarı
     * @param requestedBy çalıştıranın görünen adı
     */
    public Start start(PageMonitor m, String userKey, String requestedBy) {
        return start(m, userKey, requestedBy, null);
    }

    /**
     * {@link #start(PageMonitor, String, String)} + çok sayfalı seçenek ({@code site} null = tek sayfa, bugünkü davranış).
     * Çok sayfalı taramada süre bütçesi {@code site-max-seconds}'tır (tüm site için tek bütçe); nesne / bayt tavanları aynı.
     */
    public Start start(PageMonitor m, String userKey, String requestedBy, SiteOptions site) {
        if (stopping) return new Start(Outcome.BUSY, null, 10);
        long now = clock.getAsLong();
        synchronized (startLock) {
            settleStale(m.getId(), now);
            List<Map<String, Object>> running = jdbc.query(SQL_RUNNING, this::summaryRow, m.getId());
            if (!running.isEmpty()) return new Start(Outcome.RUNNING, running.get(0), 0);
            long wait = rateWait(userKey, now);
            if (wait > 0) return new Start(Outcome.RATE, null, wait);
            if (!podSlots.tryAcquire()) return new Start(Outcome.BUSY, null, 15);
            boolean handedOff = false;
            try {
                boolean viaProxy = decideVia(m.getUrl(), m.getUseProxy());
                Long id;
                try {
                    id = insert(m, viaProxy, now, requestedBy, site == null ? SCOPE_PAGE : SCOPE_SITE);
                } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                    // ux_prs_one_running (PostgreSQL): başka pod aynı anda başlattı → onun satırını izle
                    List<Map<String, Object>> other = jdbc.query(SQL_RUNNING, this::summaryRow, m.getId());
                    if (!other.isEmpty()) return new Start(Outcome.RUNNING, other.get(0), 0);
                    throw dup;
                }
                // İki pod aynı anda başlattıysa: küçük kimlik kazanır, büyük olan kendini siler.
                List<Map<String, Object>> after = jdbc.query(SQL_RUNNING, this::summaryRow, m.getId());
                if (!after.isEmpty() && !id.equals(((Number) after.get(0).get("id")).longValue())) {
                    jdbc.update("DELETE FROM page_resource_scans WHERE id = ? AND status = 'RUNNING'", id);
                    return new Start(Outcome.RUNNING, after.get(0), 0);
                }
                recordStart(userKey, now);
                Snapshot snap = new Snapshot(id, m.getId(), m.getUrl(), m.getExcludePatterns(), viaProxy, m.getUseProxy(), site);
                Config c = config();
                if (site != null && site.full()) {
                    // Tüm site: süre ve bayt bütçesi tüm site için; nesne tavanı SAYFA başına (site geneli tavan SiteRun'da)
                    FullSiteLimits fl = fullSiteLimits();
                    c = new Config(fl.maxSeconds(), c.concurrency(), c.maxObjects(), fl.maxTotalMb(), c.requestTimeoutMs());
                    markCrawl(id);
                } else if (site != null) {
                    // Çok sayfalı: tüm site için tek süre bütçesi (nesne / bayt / eşzamanlılık / zaman aşımı aynı)
                    c = new Config(siteLimits().maxSeconds(), c.concurrency(), c.maxObjects(), c.maxTotalMb(), c.requestTimeoutMs());
                }
                Config runConfig = c;
                try {
                    executor().execute(() -> {
                        try {
                            run(snap, runConfig);
                        } finally {
                            podSlots.release();
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    // Kapanış başladı: satır yarıda kalmış olarak hemen kapanır (eskimesini beklemez)
                    end(id, INTERRUPTED, "SHUTDOWN", null);
                    return new Start(Outcome.BUSY, null, 10);
                }
                handedOff = true;
                Map<String, Object> row = one(id, m.getId());
                return new Start(Outcome.STARTED, row == null ? null : castScan(row), 0);
            } finally {
                if (!handedOff) podSlots.release();
            }
        }
    }

    /** Satırı tüm site taraması olarak işaretler (özet {@code crawl}). Yazılamazsa yalnız etiket eksik kalır. */
    private void markCrawl(long id) {
        try {
            jdbc.update(SQL_SET_CRAWL, CRAWL_FULL, id);
        } catch (RuntimeException e) {
            log.debug("Kaynak analizi tüm site etiketi yazılamadı: {}", e.getClass().getSimpleName());
        }
    }

    /** Vekil kararı (izlemenin kuralı, verilen adresin host'u için); karar verilemezse doğrudan. */
    private boolean decideVia(String url, String mode) {
        if (proxyPolicy == null) return false;
        try {
            return proxyPolicy.decide(url, mode).viaProxy();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castScan(Map<String, Object> one) {
        return (Map<String, Object>) one.get("scan");
    }

    private Long insert(PageMonitor m, boolean viaProxy, long now, String requestedBy, String scope) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        String by = requestedBy == null ? null : (requestedBy.length() > 200 ? requestedBy.substring(0, 200) : requestedBy);
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(SQL_INSERT, new String[]{"id"});
            ps.setLong(1, m.getId());
            if (m.getTeamId() == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, m.getTeamId());
            ps.setString(3, m.getUrl());
            ps.setString(4, viaProxy ? "proxy" : "direct");
            ps.setString(5, iso(now));
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setString(8, SchedulerService.instanceId());
            ps.setString(9, by);
            ps.setInt(10, PageResourceAnalyzer.ALGORITHM_VERSION);
            ps.setString(11, scope);
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) throw new IllegalStateException("tarama kimliği alınamadı");
        return key.longValue();
    }

    private long rateWait(String userKey, long now) {
        Deque<Long> dq = rate.get(userKey);
        if (dq == null) return 0;
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() >= 60_000L) dq.pollFirst();
            if (dq.size() < STARTS_PER_MINUTE) return 0;
            return Math.max(1, (60_000L - (now - dq.peekFirst()) + 999) / 1000);
        }
    }

    private void recordStart(String userKey, long now) {
        if (rate.size() > 5_000) rate.clear();
        Deque<Long> dq = rate.computeIfAbsent(userKey, k -> new ArrayDeque<>());
        synchronized (dq) { dq.addLast(now); }
    }

    private ExecutorService executor() {
        ExecutorService e = executor;
        if (e == null) {
            synchronized (this) {
                if (executor == null) {
                    AtomicInteger n = new AtomicInteger();
                    executor = Executors.newFixedThreadPool(MAX_PER_POD, r -> {
                        Thread t = new Thread(r, "page-resource-scan-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                }
                e = executor;
            }
        }
        return e;
    }

    // ── İptal ───────────────────────────────────────────────────────────────────────────────

    /** İptal sonucu: CANCELLED (satır INTERRUPTED / CANCELLED oldu), NOT_RUNNING (zaten bitmiş), NOT_FOUND. */
    public enum CancelOutcome { CANCELLED, NOT_RUNNING, NOT_FOUND }

    public record Cancel(CancelOutcome outcome, Map<String, Object> scan) {}

    /**
     * Çalışan taramayı iptal eder (kapılar çağıranda). Tek koşullu UPDATE: satır yalnız RUNNING iken INTERRUPTED / CANCELLED
     * olur ve {@code cancel_requested} yazılır; koşan pod kalp atışında görüp durur (bu pod'daysa hemen kesilir). Eskiyen
     * satır önce STALE kapanır (iptal sayılmaz).
     */
    public Cancel cancel(long scanId, long monitorId) {
        long now = clock.getAsLong();
        settleStale(monitorId, now);
        int n = jdbc.update(SQL_CANCEL, iso(now), now, scanId, monitorId);
        Map<String, Object> one = one(scanId, monitorId);
        if (one == null) return new Cancel(CancelOutcome.NOT_FOUND, null);
        if (n == 0) return new Cancel(CancelOutcome.NOT_RUNNING, castScan(one));
        Run local = active.get(scanId);
        if (local != null) local.abort();
        log.info("Sayfa kaynak analizi {} iptal edildi (izleme {}){}", scanId, monitorId,
                local != null ? " — bu pod'da hemen kesildi" : " — koşan pod kalp atışında duracak");
        return new Cancel(CancelOutcome.CANCELLED, castScan(one));
    }

    // ── Okuma ───────────────────────────────────────────────────────────────────────────────

    /** Geçmiş (özetler, en yeni önce, saklama penceresi içi) + çalışan tarama. */
    public Map<String, Object> list(long monitorId, int limit) {
        long now = clock.getAsLong();
        settleStale(monitorId, now);
        int lim = Math.max(1, Math.min(LIST_MAX, limit));
        int days = historyDays();
        String since = iso(now - days * 86_400_000L);
        List<Map<String, Object>> items = jdbc.query(SQL_LIST, this::summaryRow, monitorId, since, lim);
        Map<String, Object> running = null;
        for (Map<String, Object> s : items) if (RUNNING.equals(s.get("status"))) { running = s; break; }
        if (running == null) {
            List<Map<String, Object>> r = jdbc.query(SQL_RUNNING, this::summaryRow, monitorId);
            if (!r.isEmpty()) running = r.get(0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("running", running);
        Config c = config();
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_seconds", c.maxSeconds());
        limits.put("max_objects", c.maxObjects());
        limits.put("max_total_mb", c.maxTotalMb());
        limits.put("detail_runs", DETAIL_RUNS);
        limits.put("parallel", PageResourceAnalyzer.PARALLEL);
        limits.put("retention_days", days);
        limits.put("algorithm_version", PageResourceAnalyzer.ALGORITHM_VERSION);
        // ── G çok sayfalı (ek alanlar; eski sunucuda yok) ──
        SiteLimits sl = siteLimits();
        limits.put("max_pages", sl.maxPages());
        limits.put("site_max_seconds", sl.maxSeconds());
        // ── tüm site taraması (2026-11-15; eski sunucuda yok) ──
        FullSiteLimits fl = fullSiteLimits();
        limits.put("full_site_max_pages", fl.maxPages());
        limits.put("full_site_max_seconds", fl.maxSeconds());
        out.put("limits", limits);
        return out;
    }

    /** Tek tarama: özet + analiz + nesneler; yoksa (ya da başka izlemenin) null. */
    public Map<String, Object> one(long scanId, long monitorId) {
        settleStale(monitorId, clock.getAsLong());
        List<Map<String, Object>> rows = jdbc.query(SQL_ONE, (rs, i) -> {
            Map<String, Object> scan = summaryRow(rs, i);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("scan", scan);
            o.put("analysis", localizeSite(parseMap(rs.getString("analysis_json"))));
            List<Object> items = parseList(rs.getString("items_json"));
            o.put("items", items);
            o.put("items_kept", items != null);
            return o;
        }, scanId, monitorId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** İzleme silinince taramaları da gider (URL'ler ve kişi adı taşır). */
    public int deleteForMonitor(long monitorId) {
        try {
            return jdbc.update(SQL_DELETE_MONITOR, monitorId);
        } catch (RuntimeException e) {
            log.warn("Sayfa kaynak analizi kayıtları silinemedi (izleme {}): {} — gece temizliği öksüz kural siler",
                    monitorId, e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Eskime eşiği: en az {@value #STALE_MS} ms ve iki istek zaman aşımı + pay (uzun zaman aşımında tek bir nesneyi
     * bekleyen canlı tarama "yarıda kaldı" sayılmasın — kalp atışı bekleyişte de yazılır ama yedek pay korunur).
     */
    long staleMs() {
        return Math.max(STALE_MS, 2L * config().requestTimeoutMs() + 15_000L);
    }

    private void settleStale(long monitorId, long now) {
        try {
            jdbc.update(SQL_SETTLE_STALE, iso(now), monitorId, now - staleMs());
        } catch (RuntimeException e) {
            log.debug("Eskiyen kaynak analizi kapatılamadı: {}", e.getMessage());
        }
    }

    Map<String, Object> summaryRow(ResultSet rs, int i) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("monitor_id", rs.getLong("monitor_id"));
        m.put("url", rs.getString("url"));
        m.put("status", rs.getString("status"));
        m.put("via", rs.getString("via"));
        m.put("started_at", rs.getString("started_at"));
        m.put("finished_at", rs.getString("finished_at"));
        m.put("requested_by", rs.getString("requested_by"));
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("done", intOr(rs, "progress_done"));
        progress.put("total", intOr(rs, "progress_total"));
        // Çok sayfalı koşunun sayfa ilerlemesi (DB'den — başka pod'un koşusu da görünür); yalnız yazılmışsa
        Integer pagesDone = intObj(rs, "pages_done");
        Integer pagesTotal = intObj(rs, "pages_total");
        if (pagesDone != null || pagesTotal != null) {
            progress.put("pages_done", pagesDone);
            progress.put("pages_total", pagesTotal);
        }
        m.put("progress", progress);
        m.put("crawl", rs.getString("site_crawl"));   // tüm site taraması: "full" (2026-11-15); diğerleri null
        if (CRAWL_FULL.equals(rs.getString("site_crawl"))) m.put("resumable", rs.getBoolean("site_resumable"));
        m.put("grade", intObj(rs, "grade"));
        m.put("grade_letter", rs.getString("grade_letter"));
        m.put("load_ms", longObj(rs, "load_ms"));
        m.put("html_ttfb_ms", longObj(rs, "html_ttfb_ms"));
        m.put("html_ms", longObj(rs, "html_ms"));
        m.put("page_bytes", longObj(rs, "page_bytes"));
        m.put("request_count", intObj(rs, "request_count"));
        m.put("image_bytes", longObj(rs, "image_bytes"));
        m.put("image_count", intObj(rs, "image_count"));
        m.put("document_count", intObj(rs, "document_count"));
        m.put("document_bytes", longObj(rs, "document_bytes"));
        m.put("link_count", intObj(rs, "link_count"));
        m.put("link_bytes", longObj(rs, "link_bytes"));
        m.put("failed_count", intObj(rs, "failed_count"));
        m.put("capped", rs.getBoolean("capped"));
        String code = rs.getString("error");
        String prefix = code == null ? null : code.split(":", 2)[0];
        m.put("error_code", prefix);
        // Ulaşılamama nedeni (CheckFailureReason adı) — arayüz kendi diline çevirir; ham kod metne konmaz
        m.put("error_reason", code != null && code.startsWith("PAGE_FAILED:") ? code.substring("PAGE_FAILED:".length()) : null);
        m.put("error", errorText(code));
        m.put("items_kept", rs.getBoolean("items_kept"));
        // ── algoritma 2 (eski satırda null — okuyan "yok" sayar) ──
        m.put("owner_pod", ClusterHealthStore.podOf(rs.getString("owner_pod")));
        m.put("redirect_count", intObj(rs, "redirect_count"));
        m.put("redirect_ms", longObj(rs, "redirect_ms"));
        Integer av = intObj(rs, "algorithm_version");
        m.put("algorithm_version", av);
        // Yalnız son adımın ilk baytı (yönlendirmeler hariç) — algoritma 1 satırında html_ttfb_ms zincirin tamamıdır, burada yok
        m.put("ttfb_ms", av != null && av >= 2 ? longObj(rs, "html_ttfb_ms") : null);
        boolean cancelFlag = rs.getBoolean("cancel_requested");
        m.put("cancelled", (!rs.wasNull() && cancelFlag) || "CANCELLED".equals(prefix));
        // ── algoritma 3 (eski satırda null) ──
        m.put("js_bytes", longObj(rs, "js_bytes"));
        m.put("css_bytes", longObj(rs, "css_bytes"));
        m.put("font_bytes", longObj(rs, "font_bytes"));
        m.put("security_score", intObj(rs, "security_score"));
        m.put("seo_score", intObj(rs, "seo_score"));
        m.put("a11y_score", intObj(rs, "a11y_score"));
        String scope = rs.getString("scope");
        m.put("scope", scope == null ? "page" : scope);
        m.put("pages_count", intObj(rs, "pages_count"));
        return m;
    }

    /**
     * Çok sayfalı analizde sayfa satırlarının saklanan hata KODLARI okuma anında isteğin diline çevrilir — özet satırıyla aynı
     * biçim: {@code error} = metin, {@code error_code} = önek, {@code error_reason} = ulaşılamama nedeni (yalnız
     * {@code PAGE_FAILED}). Bölüm yoksa / bozuksa analiz aynen döner.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> localizeSite(Map<String, Object> analysis) {
        if (analysis == null || !(analysis.get("site") instanceof Map<?, ?> site)) return analysis;
        if (!(site.get("pages") instanceof List<?> pages)) return analysis;
        for (Object o : pages) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> p = (Map<String, Object>) raw;
            Object e = p.get("error");
            String code = e instanceof String s && !s.isBlank() ? s : null;
            String prefix = code == null ? null : code.split(":", 2)[0];
            p.put("error_code", prefix);
            p.put("error_reason", code != null && code.startsWith("PAGE_FAILED:") ? code.substring("PAGE_FAILED:".length()) : null);
            p.put("error", errorText(code));
        }
        return analysis;
    }

    /** Saklanan hata kodunu isteğin diline çevirir (ham kod / istisna metni dönmez). */
    static String errorText(String code) {
        if (code == null || code.isBlank()) return null;
        String[] p = code.split(":", 2);
        String arg = p.length > 1 ? p[1] : "";
        return switch (p[0]) {
            case "PAGE_HTTP" -> Msg.t("Sayfa HTTP " + arg + " döndürdü; analiz yapılamadı. Sayfanın açıldığını kontrol edip yeniden deneyin.",
                    "The page returned HTTP " + arg + "; the analysis could not run. Check that the page opens and try again.");
            case "PAGE_FAILED" -> Msg.t("Sayfaya ulaşılamadı; analiz yapılamadı. Ağ erişimini kontrol edip yeniden deneyin.",
                    "The page could not be reached; the analysis could not run. Check network access and try again.");
            case "PAGE_BLOCKED" -> Msg.t("Sayfanın adresi güvenlik kuralına takıldı (iç / yasaklı adres); analiz yapılmadı.",
                    "The page address was blocked by a security rule (internal / forbidden address); the analysis did not run.");
            case "PAGE_EMPTY" -> Msg.t("Sayfanın içeriği okunamadı; analiz yapılamadı.",
                    "The page content could not be read; the analysis could not run.");
            case "SHUTDOWN" -> Msg.t("Analiz, sunucu (pod) kapanırken yarıda kaldı. Yeniden çalıştırın.",
                    "The analysis was cut short while the server (pod) was shutting down. Run it again.");
            case "STALE" -> Msg.t("Analizi çalıştıran sunucu (pod) yanıt vermeyi bıraktı; analiz yarıda kaldı. Yeniden çalıştırın.",
                    "The server (pod) running the analysis stopped responding; the analysis was cut short. Run it again.");
            case "CANCELLED" -> Msg.t("İptal edildi. Analizi yeniden çalıştırabilirsiniz.",
                    "Cancelled. You can run the analysis again.");
            // ── G çok sayfalı: sayfa satırı kodları ──
            case "PAGE_NOT_HTML" -> Msg.t("Adres bir HTML sayfası döndürmedi (başka içerik türü); sayfa analiz edilmedi.",
                    "The address did not return an HTML page (another content type); the page was not analysed.");
            case "PAGE_OFFSITE" -> Msg.t("Sayfa başka bir siteye yönlendirdi (ör. oturum açma); o sayfa analiz edilmedi.",
                    "The page redirected to another site (for example a sign-in page); that page was not analysed.");
            case "NOT_SCANNED" -> switch (arg) {
                case "BYTES" -> Msg.t("Toplam indirme sınırı doldu; bu sayfa taranmadı. Ayarlardan sınırı artırıp yeniden çalıştırın.",
                        "The total download limit was reached; this page was not scanned. Raise the limit in Settings and run it again.");
                case "OBJECTS" -> Msg.t("Nesne sınırı doldu; bu sayfa taranmadı. Ayarlardan sınırı artırıp yeniden çalıştırın.",
                        "The object limit was reached; this page was not scanned. Raise the limit in Settings and run it again.");
                default -> Msg.t("Süre bütçesi doldu; bu sayfa taranmadı. Daha az sayfayla ya da daha uzun süreyle yeniden çalıştırın.",
                        "The time budget ran out; this page was not scanned. Run it again with fewer pages or a longer time limit.");
            };
            default -> Msg.t("Analiz beklenmeyen bir hatayla durdu. Yeniden deneyin; sorun sürerse yöneticinize bildirin.",
                    "The analysis stopped with an unexpected error. Try again; if it keeps happening, tell your administrator.");
        };
    }

    // ── Ölçüm ───────────────────────────────────────────────────────────────────────────────

    /**
     * Arka plan işinin ihtiyacı (izlemenin başlatma anındaki değerleri).
     *
     * @param viaProxy  ana sayfanın vekil kararı (özetteki {@code via})
     * @param proxyMode izlemenin vekil tercihi (AUTO / ON / OFF) — nesne başına karar bununla verilir
     */
    record Snapshot(long id, long monitorId, String url, String excludes, boolean viaProxy, String proxyMode, SiteOptions site) {
        /** Geriye uyum: tercih yok → ana sayfanın kararı her nesneye (ON/OFF eşdeğeri). */
        Snapshot(long id, long monitorId, String url, String excludes, boolean viaProxy) {
            this(id, monitorId, url, excludes, viaProxy, viaProxy ? ProxyPolicyService.ON : ProxyPolicyService.OFF);
        }

        /** Tek sayfa (çok sayfalı seçenek yok). */
        Snapshot(long id, long monitorId, String url, String excludes, boolean viaProxy, String proxyMode) {
            this(id, monitorId, url, excludes, viaProxy, proxyMode, null);
        }
    }

    /** Ölçülecek aday. */
    private static final class Planned {
        final int order;
        final PageResourceDiscovery.Candidate c;
        final Planned parent;
        final int depth;
        // Ölçüm görevleri (sanal iş parçacıkları) yazar, tarama iş parçacığı okur. Stil gövdesi TUTULMAZ: ölçen görev
        // başvuruları hemen çıkarır (açılmış gövde büyük olabilir — yüzlerce stil dalga sonuna dek bellekte kalırsa
        // pod taşar; inceleme 2026-10-31).
        volatile ScanItem item;
        volatile List<PageResourceDiscovery.Candidate> cssRefs;
        // ── G çok sayfalı: nesnenin derin olgulara katkısı (yalnız çok sayfalı taramada yazılır; ortak nesne başka sayfada
        // yeniden ölçülmeden bu katkılar o sayfanın bağlamına eklenir) ──
        volatile List<PageResourceDiscovery.Candidate> siteRefs;
        /** Stilin tam başvuru sayısı ({@link #siteRefs} bellek tavanıyla kesildiyse daha büyük). */
        volatile int siteRefsFull;
        volatile List<FontFaces.Face> siteFaces;
        volatile List<DeepContext.CookieSeen> siteCookies;
        volatile boolean siteSampleMissing;
        /** Bu sayfada ölçülmedi — önceki sayfanın ölçümü (önbellek) kullanıldı. */
        SiteCached cached;

        Planned(int order, PageResourceDiscovery.Candidate c, Planned parent, int depth) {
            this.order = order;
            this.c = c;
            this.parent = parent;
            this.depth = depth;
        }
    }

    /** Taramanın paylaşılan durumu (bütçe, ilerleme, tavan bayrakları, istemciler). */
    private final class Run {
        final Snapshot s;
        final Config c;
        final long deadline;
        final long budget;
        final AtomicLong spent = new AtomicLong();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger total = new AtomicInteger();
        volatile boolean timeCapped, bytesCapped;
        /** Satır artık RUNNING değil (başka okuyucu eskimiş sayıp kapattı, iptal edildi ya da izleme silindi). */
        volatile boolean lost;
        /** Aynı pod'da iptal edildi — uçuştaki istekler kesildi. */
        volatile boolean aborted;
        volatile boolean objectsCapped;
        final AtomicInteger cssRefsRetained = new AtomicInteger();
        /** Uçuştaki ölçüm görevleri (pod yuvası bunlar bitince bırakılır). */
        final AtomicInteger inFlight = new AtomicInteger();
        int excludedExtra;
        long lastBeat;
        String ua;
        String rootHost;
        final PageFetchCore.ScanClients clients;
        final PageFetchCore.ScanTuning htmlTuning, cssTuning, countTuning, jsTuning, probeTuning, imageTuning;
        // ── algoritma 3: ölçüm görevleri yazar (eşzamanlı), tarama iş parçacığı okur; hepsi sınırlı ──
        final java.util.Queue<DeepContext.CookieSeen> cookies = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final AtomicInteger cookieCount = new AtomicInteger();
        final java.util.Queue<FontFaces.Face> fontFaces = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final AtomicInteger fontFaceCount = new AtomicInteger();
        final Set<String> cssReferenced = ConcurrentHashMap.newKeySet();
        final AtomicInteger samplesUnavailable = new AtomicInteger();
        final Map<String, DeepContext.HostTiming> hostTimings = new ConcurrentHashMap<>();
        volatile int hostsNotMeasured;
        final Map<String, Boolean> sourceMaps = new ConcurrentHashMap<>();
        volatile int sourceMapsNotChecked;
        /** Ana sayfa https mi (çerez sorunları için). */
        volatile boolean httpsPage;
        /** Nesne başına vekil kararı host önbelleği (AUTO kipinde envanter okuması host başına bir kez). */
        final Map<String, Boolean> viaByHost = new ConcurrentHashMap<>();
        /** Çok sayfalı taramanın paylaşılan durumu (tek sayfada null). */
        final SiteRun site;
        /** Son taranan iç sayfanın bağlantı adresleri (tüm site taraması kuyruğu besler; her sayfada yenilenir). */
        volatile List<String> pageLinks = List.of();

        Run(Snapshot s, Config c, long start, PageFetchCore.ScanClients clients) {
            this.s = s;
            this.c = c;
            this.site = s.site() == null ? null
                    : s.site().full() ? new SiteRun(fullSiteLimits().maxObjects(), true) : new SiteRun(c.maxObjects());
            this.deadline = start + c.maxSeconds() * 1000L;
            this.budget = c.maxTotalMb() * PageResourceAnalyzer.MB;
            this.clients = clients;
            // Algoritma 3: gövde özeti her yük nesnesinde; ana sayfada güvenlik başlıkları; betikte çözülmüş örnek
            this.htmlTuning = new PageFetchCore.ScanTuning(clients, PageFetchCore.ACCEPT_ENCODING,
                    PageFetchCore.MAX_BODY_BYTES, HTML_MAX_DECODED).capture(true, 0, 0, true);
            this.cssTuning = new PageFetchCore.ScanTuning(clients, PageFetchCore.ACCEPT_ENCODING,
                    PageFetchCore.MAX_BODY_BYTES, PageResourceDiscovery.MAX_CSS_CHARS).capture(true, 0, 0, false);
            PageFetchCore.ScanTuning count = new PageFetchCore.ScanTuning(clients, PageFetchCore.ACCEPT_ENCODING_COUNT,
                    PageFetchCore.MAX_BODY_BYTES, PageResourceDiscovery.MAX_CSS_CHARS);
            this.countTuning = count.capture(true, 0, 0, false);
            this.jsTuning = count.capture(true, AssetSample.HEAD_CHARS, AssetSample.TAIL_CHARS, false);
            this.probeTuning = count;
            this.imageTuning = new PageFetchCore.ScanTuning(clients, PageFetchCore.ACCEPT_ENCODING, OPT_MAX_BODY, OPT_MAX_BODY);
        }

        boolean halted() {
            return stopping || lost || aborted || Thread.currentThread().isInterrupted();
        }

        /** Aynı pod'daki iptal: bayrak + uçuştaki istekleri kes. */
        void abort() {
            aborted = true;
            clients.shutdownNow();
        }

        /** Nesne başına vekil kararı — izlemenin tercihi nesnenin host'una uygulanır (NO_PROXY dahil). */
        boolean via(String url) {
            if (proxyPolicy == null) return false;
            String host = ProxyPolicyService.hostOf(url);
            if (host == null) return false;
            return viaByHost.computeIfAbsent(host, h -> {
                try {
                    return proxyPolicy.decideForHost(h, s.proxyMode()).viaProxy();
                } catch (RuntimeException e) {
                    return false;
                }
            });
        }

        /** Ölçüm görevi (sanal iş parçacığı); uçuştaki sayıya girer. İptalde ({@link Future#cancel}) kesilir. */
        <T> Future<T> submit(Callable<T> task) {
            return core.executor().submit(() -> {
                inFlight.incrementAndGet();
                try {
                    return task.call();
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }

        /** Kalp atışı + ilerleme; 0 satır = satır artık bu taramanın değil (iptal / eskime / silme) → {@link #lost}. */
        void beat(boolean force) {
            long now = clock.getAsLong();
            if (!force && now - lastBeat < HEARTBEAT_MS) return;
            lastBeat = now;
            try {
                if (jdbc.update(SQL_PROGRESS, done.get(), total.get(), now, s.id()) == 0) {
                    lost = true;
                    log.info("Sayfa kaynak analizi {} artık çalışıyor görünmüyor ({}) — ölçüm bırakılıyor", s.id(),
                            cancelRequested(s.id()) ? "iptal edildi" : "yarıda kaldı sayıldı / silindi");
                }
            } catch (RuntimeException e) {
                log.debug("Kaynak analizi ilerlemesi yazılamadı: {}", e.getMessage());
            }
        }

        /** Uçuştaki istekleri keser ve görevlerin bitmesini en çok {@value #QUIET_WAIT_MS} ms bekler (gerçek saat). */
        void close() {
            clients.shutdownNow();
            long until = System.currentTimeMillis() + QUIET_WAIT_MS;
            while (inFlight.get() > 0 && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (inFlight.get() > 0) {
                log.debug("Kaynak analizi {}: {} ölçüm görevi kapanışta hâlâ sürüyor — pod yuvası yine bırakılıyor",
                        s.id(), inFlight.get());
            }
        }
    }

    private boolean cancelRequested(long id) {
        try {
            Boolean b = jdbc.query(SQL_CANCEL_FLAG, rs -> rs.next() ? rs.getBoolean(1) : null, id);
            return Boolean.TRUE.equals(b);
        } catch (RuntimeException e) {
            return false;
        }
    }

    void run(Snapshot s, Config c) {
        long start = clock.getAsLong();
        PageFetchCore.ScanClients clients;
        try {
            clients = core.newScanClients();
        } catch (RuntimeException e) {
            log.warn("Sayfa kaynak analizi {} istemcisi kurulamadı: {}", s.id(), e.getClass().getSimpleName());
            end(s.id(), FAILED, "INTERNAL:" + e.getClass().getSimpleName(), null);
            return;
        }
        Run run = new Run(s, c, start, clients);
        active.put(s.id(), run);
        ScanItem html = null;
        try {
            run.ua = pageChecker.effectiveUserAgent();
            run.total.set(1);
            run.beat(true);
            // Ana sayfa (yönlendirmelerle birlikte dakikalar sürebilir) ölçülürken de kalp atışı yazılır — yoksa okuyucular
            // canlı taramayı "eskidi" sayıp kapatırdı (inceleme 2026-10-31).
            PageFetchCore.FetchOptions mainOpts = new PageFetchCore.FetchOptions(c.requestTimeoutMs(), run.ua, null, true, true,
                    s.viaProxy());
            Future<PageFetchCore.MetaFetch> mainF = run.submit(() -> core.fetchWithMeta(s.url(), "GET", mainOpts, 0,
                    run.htmlTuning));
            PageFetchCore.MetaFetch main = awaitBeating(run, mainF, Long.MAX_VALUE);
            if (main == null || run.halted()) { end(s.id(), INTERRUPTED, "SHUTDOWN", null); return; }
            html = item(new PageResourceDiscovery.Candidate(s.url(), PageResourceDiscovery.HTML, true, false, false),
                    main, null, false, s.viaProxy() ? "proxy" : "direct", null);
            html.seq = 0;
            PageFetchCore.Fetch f = main.fetch();
            if (f.blocked()) { end(s.id(), FAILED, "PAGE_BLOCKED", html); return; }
            if (f.status() == 0) {
                end(s.id(), FAILED, "PAGE_FAILED:" + (f.failure() != null ? f.failure().code() : "TRANSPORT_ERROR"), html);
                return;
            }
            if (f.status() >= 400) { end(s.id(), FAILED, "PAGE_HTTP:" + f.status(), html); return; }
            if (f.body() == null || f.body().length == 0) { end(s.id(), FAILED, "PAGE_EMPTY", html); return; }
            run.spent.addAndGet(f.bytes());
            run.done.set(1);

            String base = main.meta().finalUrl() != null ? main.meta().finalUrl() : s.url();
            run.rootHost = PageFetchCore.hostOf(base);
            run.httpsPage = com.sitemonitor.service.page.scan.deep.DeepUrls.https(base);
            collectCookies(run, main.meta());
            Predicate<String> exclude = pageChecker.excludePredicate(s.excludes());
            // Algoritma 3: HTML TEK kez ayrıştırılır — olgular (A–E) ve keşif aynı belgeden
            org.jsoup.nodes.Document doc = PageResourceDiscovery.parse(f.body(), base);
            HtmlFacts facts = doc == null ? null : HtmlFacts.extract(doc, f.body().length);
            PageResourceDiscovery.Found found = doc == null ? PageResourceDiscovery.discover(f.body(), base, exclude)
                    : PageResourceDiscovery.discoverParsed(doc, base, exclude);
            doc = null;   // belge bırakılır (bellek)
            // Satır içi stiller belgenin <base href>'iyle çözülür (HTML öğeleri jsoup'ta zaten öyle — inceleme H12)
            String inlineBase = found.baseUrl() != null && !found.baseUrl().isBlank() ? found.baseUrl() : base;
            for (String inline : found.inlineCss()) cssFacts(run, inline, inlineBase, base);
            // Bağlantı kurulum süreleri (keşfedilen host'lar; nesneler çekilmeden — DNS önbelleği soğukken)
            measureHosts(run, base, found);

            Set<String> known = new HashSet<>();
            known.add(PageResourceDiscovery.key(base));
            known.add(PageResourceDiscovery.key(s.url()));
            List<Planned> all = new ArrayList<>();
            AtomicInteger order = new AtomicInteger(1);
            List<Planned> phase = new ArrayList<>();
            for (PageResourceDiscovery.Candidate cand : found.pageLoad()) plan(run, all, phase, known, order, cand, null, 0, null);
            for (String inline : found.inlineCss()) {
                for (PageResourceDiscovery.Candidate cand : PageResourceDiscovery.cssRefs(inline, inlineBase)) {
                    plan(run, all, phase, known, order, cand, null, 0, exclude);
                }
            }
            // Sayfanın yükledikleri; stil gövdelerinden bulunanlar sonraki dalgada (en çok CSS_DEPTH kat)
            for (int depth = 0; !phase.isEmpty() && depth <= CSS_DEPTH; depth++) {
                measure(run, phase);
                List<Planned> next = new ArrayList<>();
                for (Planned p : phase) {
                    List<PageResourceDiscovery.Candidate> refs = p.cssRefs;
                    p.cssRefs = null;
                    if (refs == null || p.item == null || p.depth >= CSS_DEPTH) continue;
                    for (PageResourceDiscovery.Candidate cand : refs) plan(run, all, next, known, order, cand, p, p.depth + 1, exclude);
                }
                phase = next;
            }
            // Açıkta olabilecek kaynak haritaları (HEAD; sayfa yükü bitince, belge / bağlantıdan önce)
            checkSourceMaps(run, all);
            // Ölçülmüş görsel kazancı: sayfa yükünün en büyük görselleri (belge / bağlantı yoklamasından önce)
            optimizeImages(run, all);
            List<Planned> docs = new ArrayList<>();
            for (PageResourceDiscovery.Candidate cand : found.documents()) plan(run, all, docs, known, order, cand, null, 0, null);
            measure(run, docs);
            List<Planned> links = new ArrayList<>();
            for (PageResourceDiscovery.Candidate cand : found.links()) plan(run, all, links, known, order, cand, null, 0, null);
            measure(run, links);

            if (run.halted()) { end(s.id(), INTERRUPTED, "SHUTDOWN", html); return; }

            // Ölçülenler keşif sırasıyla; ebeveyn sırası son listedeki konuma çevrilir
            List<ScanItem> items = new ArrayList<>();
            items.add(html);
            Map<Integer, Integer> pos = new HashMap<>();
            all.sort(Comparator.comparingInt(p -> p.order));
            for (Planned p : all) {
                if (p.item == null) continue;
                pos.put(p.order, items.size());
                p.item.seq = items.size();
                items.add(p.item);
            }
            for (Planned p : all) {
                if (p.item == null) continue;
                p.item.parent = p.parent == null ? 0 : pos.getOrDefault(p.parent.order, 0);
            }
            PageResourceAnalyzer.Limits lim = new PageResourceAnalyzer.Limits(run.objectsCapped, c.maxObjects(),
                    run.timeCapped, c.maxSeconds(), run.bytesCapped, c.maxTotalMb(), found.excluded() + run.excludedExtra);
            PageFetchCore.ScanMeta mainScan = main.meta() == null ? null : main.meta().scan();
            DeepContext deep = new DeepContext(facts, mainScan == null ? null : mainScan.pageHeaders(), base,
                    new ArrayList<>(run.cookies), new ArrayList<>(run.fontFaces), Set.copyOf(run.cssReferenced),
                    Map.copyOf(run.hostTimings), run.hostsNotMeasured, Map.copyOf(run.sourceMaps), run.sourceMapsNotChecked,
                    run.samplesUnavailable.get());
            PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, lim, deep);
            if (run.site == null) {
                finishDone(s, run, items, r, html);
            } else {
                // Çok sayfalı: ana sayfanın analizi bugünkü gibi; ardından iç sayfalar AYNI bütçeyle (süre, bayt, nesne)
                List<String> linkUrls = new ArrayList<>(found.links().size());
                for (PageResourceDiscovery.Candidate cand : found.links()) linkUrls.add(cand.url());
                if (s.site().full()) {
                    FullCrawl fc = scanSiteFull(run, s, base, exclude, linkUrls, all, items, r);
                    if (fc == null || run.halted()) { end(s.id(), INTERRUPTED, "SHUTDOWN", html); return; }
                    finishSite(s, run, items, r, html, fc.pages(), fc.prior(), fc.extra(), fc.checkpoint());
                } else {
                    List<SitePages.Page> pages = scanSite(run, s, base, exclude, linkUrls, all, items, r);
                    if (pages == null || run.halted()) { end(s.id(), INTERRUPTED, "SHUTDOWN", html); return; }
                    finishSite(s, run, items, r, html, pages);
                }
            }
        } catch (Throwable t) {
            if (t instanceof InterruptedException || run.halted()) {
                Thread.currentThread().interrupt();
                end(s.id(), INTERRUPTED, "SHUTDOWN", html);
            } else {
                log.warn("Sayfa kaynak analizi beklenmeyen hatayla durdu (tarama {}, izleme {}): {}", s.id(), s.monitorId(),
                        t.getClass().getSimpleName(), t);
                end(s.id(), FAILED, "INTERNAL:" + t.getClass().getSimpleName(), html);
            }
        } finally {
            run.close();
            active.remove(s.id(), run);
        }
    }

    /**
     * Adayı plana alır (tekil URL, nesne tavanı). {@code exclude}: stilden / satır içi stilden bulunanlara izlemenin hariç
     * tutma desenleri burada uygulanır (HTML'dekiler keşifte elendi) ve sayılır.
     */
    private static void plan(Run run, List<Planned> all, List<Planned> phase, Set<String> known, AtomicInteger order,
                             PageResourceDiscovery.Candidate cand, Planned parent, int depth, Predicate<String> exclude) {
        String k = PageResourceDiscovery.key(cand.url());
        if (!known.add(k)) return;
        if (exclude != null) {
            boolean ex;
            try { ex = exclude.test(cand.url()); } catch (RuntimeException e) { ex = false; }
            if (ex) { run.excludedExtra++; return; }
        }
        if (all.size() + 1 >= run.c.maxObjects()) {   // +1: HTML satırı
            run.objectsCapped = true;
            return;
        }
        Planned p = new Planned(order.getAndIncrement(), cand, parent, depth);
        all.add(p);
        phase.add(p);
        run.total.incrementAndGet();
    }

    /**
     * Bir dalgayı sınırlı eşzamanlılıkla ölçer; süre / bayt bütçesi / kapanış kalanları atlar. Dönerken bitmemiş görevler
     * KESİLİR ({@link Future#cancel(boolean)} sanal iş parçacığını keser; kesilen görev sonuç yazmaz).
     */
    private void measure(Run run, List<Planned> list) throws InterruptedException {
        if (list.isEmpty() || run.halted()) return;
        Semaphore gate = new Semaphore(run.c.concurrency());
        List<Future<Object>> futures = new ArrayList<>(list.size());
        try {
            for (Planned p : list) {
                futures.add(run.submit(() -> {
                    if (!admit(run)) return null;
                    gate.acquire();
                    try {
                        if (!admit(run)) return null;
                        measureOne(run, p);
                    } finally {
                        gate.release();
                    }
                    return null;
                }));
            }
            for (Future<Object> f : futures) {
                while (true) {
                    if (run.halted()) return;
                    long remaining = run.deadline - clock.getAsLong();
                    if (remaining <= 0) {
                        run.timeCapped = true;
                        return;
                    }
                    try {
                        f.get(Math.min(remaining, HEARTBEAT_MS), TimeUnit.MILLISECONDS);
                        break;
                    } catch (java.util.concurrent.TimeoutException te) {
                        run.beat(false);
                    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
                        break;   // bu nesne düştü — ölçümün geri kalanı geçerli
                    }
                }
                run.beat(false);
            }
        } finally {
            for (Future<Object> f : futures) if (!f.isDone()) f.cancel(true);
        }
    }

    /**
     * Tek bir işi kalp atışı yazarak bekler; kapanış / kayıp satır / {@code until} (saat) → null (iş kesilir). İşin kendi
     * zaman aşımları sınırlıdır.
     */
    private <T> T awaitBeating(Run run, Future<T> f, long until) throws InterruptedException {
        while (true) {
            if (run.halted() || clock.getAsLong() >= until) { f.cancel(true); return null; }
            try {
                return f.get(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                run.beat(false);
            } catch (java.util.concurrent.CancellationException ce) {
                return null;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e.getCause() == null ? e : e.getCause());
            }
        }
    }

    private boolean admit(Run run) {
        if (run.halted()) return false;
        if (clock.getAsLong() > run.deadline) { run.timeCapped = true; return false; }
        if (run.spent.get() >= run.budget) { run.bytesCapped = true; return false; }
        return true;
    }

    private void measureOne(Run run, Planned p) {
        String foundIn = p.parent == null ? FOUND_IN_PAGE : p.parent.c.url();
        if (p.c.notLoaded()) {
            // Tarayıcı indirmez (ör. preload="none" medya) — ağ işi yok, listede "indirilmedi"
            p.item = notLoadedItem(p.c, run.rootHost, foundIn);
            run.done.incrementAndGet();
            return;
        }
        String cat = p.c.category();
        boolean css = PageResourceDiscovery.CSS.equals(cat);
        boolean image = PageResourceDiscovery.IMAGE.equals(cat) || PageResourceDiscovery.FAVICON.equals(cat);
        boolean probe = !p.c.pageLoad();   // belge / bağlantı: HEAD-önce, gövdesiz
        String host = lowerHost(p.c.url());
        boolean firstParty = firstParty(host, run.rootHost);
        boolean viaProxy = run.via(p.c.url());
        PageFetchCore.MetaFetch mf = probe ? probeWithRetry(run, p.c.url(), viaProxy, firstParty)
                : getWithRetry(run, p.c.url(), viaProxy, css, image,
                        PageResourceDiscovery.JS.equals(cat) || PageResourceDiscovery.JS.equals(category(p.c.url())));
        if (mf == null || run.halted()) return;   // kesilen (iptal / süre dolumu) iş sonuç yazmaz
        ScanItem it = item(p.c, mf, run.rootHost, probe, viaProxy ? "proxy" : "direct", foundIn);
        if (!probe) {
            List<DeepContext.CookieSeen> seen = collectCookies(run, mf.meta());
            if (run.site != null) p.siteCookies = seen;
            if (PageResourceDiscovery.JS.equals(it.category) && it.ok()) {
                boolean missing = inspectScript(run, it, mf);
                if (run.site != null) p.siteSampleMissing = missing;
            }
        }
        if (css && mf.fetch().body() != null && it.ok()) {
            // Başvurular HEMEN çıkarılır, gövde atılır; göreli adresler tarayıcı gibi yönlendirme SONRASI adrese göre çözülür.
            String base = mf.meta() != null && mf.meta().finalUrl() != null ? mf.meta().finalUrl() : p.c.url();
            String text = decodeText(mf.fetch().body(), mf.meta().contentType());
            List<PageResourceDiscovery.Candidate> refs = cssFacts(run, text, base, p.c.url(), p);
            inspectStylesheet(it, text, base, mf);
            if (p.depth < CSS_DEPTH) {
                // Bekleyen (henüz planlanmamış) başvuru sayısı nesne tavanıyla sınırlı — fazlası zaten planlanamaz.
                int room = run.c.maxObjects() - run.cssRefsRetained.getAndAdd(refs.size());
                if (room < refs.size()) {
                    run.objectsCapped = true;
                    refs = room <= 0 ? List.of() : List.copyOf(refs.subList(0, room));
                }
                p.cssRefs = refs;
            }
        }
        p.item = it;
        run.done.incrementAndGet();
    }

    /** Sayfa yükü nesnesi: GET + bayt sayımı; tek seferlik hata (zaman aşımı / engel değil) bir kez yeniden denenir. */
    private PageFetchCore.MetaFetch getWithRetry(Run run, String url, boolean viaProxy, boolean css, boolean image, boolean js) {
        PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(run.c.requestTimeoutMs(), run.ua, null, css, true, viaProxy);
        PageFetchCore.ScanTuning t = css ? run.cssTuning : js ? run.jsTuning : run.countTuning;
        int head = image ? ImageDimensions.HEAD_BYTES : 0;
        PageFetchCore.MetaFetch a = core.fetchWithMeta(url, "GET", o, head, t);
        run.spent.addAndGet(Math.max(0, a.fetch().bytes()));
        if (!retryable(a.fetch()) || !pause(RETRY_DELAY_MS) || !admit(run)) return a;
        PageFetchCore.MetaFetch b = core.fetchWithMeta(url, "GET", o, head, t);
        run.spent.addAndGet(Math.max(0, b.fetch().bytes()));
        return b;
    }

    /** Belge / bağlantı: izlemenin kuralı — HEAD, desteklenmezse gövdesiz GET teyidi; tek yeniden deneme. */
    private PageFetchCore.MetaFetch probeWithRetry(Run run, String url, boolean viaProxy, boolean firstParty) {
        PageFetchCore.MetaFetch a = probeOnce(run, url, viaProxy, firstParty);
        if (!retryable(a.fetch()) || !pause(RETRY_DELAY_MS) || run.halted()) return a;
        return probeOnce(run, url, viaProxy, firstParty);
    }

    private PageFetchCore.MetaFetch probeOnce(Run run, String url, boolean viaProxy, boolean firstParty) {
        PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(run.c.requestTimeoutMs(), run.ua, null, false, false,
                viaProxy);
        PageFetchCore.MetaFetch head = core.fetchWithMeta(url, "HEAD", o, 0, run.probeTuning);
        PageFetchCore.Fetch h = head.fetch();
        if (h.blocked()) return head;
        // 3. taraf bağlantıda HEAD zaman aşımı → GET teyidi de atlanır (izlemeyle aynı: belirsiz, ikinci bekleyiş yok)
        if (!firstParty && PageCheckerService.isTimedOut(h)) return head;
        // HEAD'i yanlış işleyen sunucular (405 / 501 / WAF 403 / ASP.NET 404…): gövdesiz GET ile teyit
        if (h.status() == 0 || h.status() >= 400) return core.fetchWithMeta(url, "GET", o, 0, run.probeTuning);
        return head;
    }

    /** İzlemenin yeniden deneme kuralı: kötü (0 ya da ≥ 400) ama engel ve zaman aşımı değil. */
    static boolean retryable(PageFetchCore.Fetch f) {
        boolean bad = f.blocked() || f.status() == 0 || f.status() >= 400;
        return bad && !f.blocked() && !PageCheckerService.isTimedOut(f);
    }

    /** Kesilebilir bekleme; kesilirse false (bayrak geri konur). */
    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Kırık / doğrulanamadı kararı — Sayfa Bütünlüğü izlemesinin kuralı ({@link PageCheckerService#classifyStatus},
     * SSRF-engel kuralı, zaman aşımı sınıfı):
     * <ul>
     *   <li>güvenlik kuralı engeli → {@code blocked}; yalnız BİRİNCİ taraf alanın çözülemeyen adı {@code broken}</li>
     *   <li>aktarım hatası → {@code broken}; zaman aşımı sayfa yükünde {@code broken}, bağlantı / belgede {@code unverified}</li>
     *   <li>404 / 410 / 5xx (503 hariç) → {@code broken}; 401 / 403 / 429 / 451 / 503 / diğer 4xx ve standart dışı kodlar
     *       (LinkedIn 999) → {@code unverified} (bot engeli — tarayıcıda açılabilir)</li>
     * </ul>
     */
    static String verdictOf(PageFetchCore.Fetch f, boolean firstParty, boolean pageLoad) {
        if (f.blocked()) return SsrfGuard.isUnresolvableMessage(f.error()) && firstParty ? ScanItem.BROKEN : ScanItem.BLOCKED;
        int sc = f.status();
        if (sc == 0) return PageCheckerService.isTimedOut(f) && !pageLoad ? ScanItem.UNVERIFIED : ScanItem.BROKEN;
        if (sc < 100 || sc >= 600) return ScanItem.UNVERIFIED;
        if (sc >= 400) return "BROKEN".equals(PageCheckerService.classifyStatus(sc)) ? ScanItem.BROKEN : ScanItem.UNVERIFIED;
        return ScanItem.OK;
    }

    /** Geriye uyum: sayfa yükü nesnesi, yol ve bulunduğu yer bilinmeden. */
    ScanItem item(PageResourceDiscovery.Candidate c, PageFetchCore.MetaFetch mf, String rootHost) {
        return item(c, mf, rootHost, false, null, null);
    }

    /**
     * Ölçüm sonucunu nesne satırına çevirir; kategori içerik türüne göre incelir.
     *
     * @param probe  gövdesiz yoklama (belge / bağlantı) — boyut yalnız {@code Content-Length}'ten
     * @param via    çekim yolu ({@code proxy} | {@code direct})
     * @param foundIn bulunduğu yer (stil URL'si ya da {@code page}); HTML satırında null
     */
    ScanItem item(PageResourceDiscovery.Candidate c, PageFetchCore.MetaFetch mf, String rootHost, boolean probe, String via,
                  String foundIn) {
        PageFetchCore.Fetch f = mf.fetch();
        PageFetchCore.Meta meta = mf.meta() == null ? PageFetchCore.Meta.NONE : mf.meta();
        ScanItem it = new ScanItem();
        it.url = c.url();
        it.contentType = contentType(meta.contentType());
        it.category = refine(c, it.contentType, f.status());
        it.pageLoad = !PageResourceDiscovery.DOCUMENT.equals(it.category) && !PageResourceDiscovery.LINK.equals(it.category);
        it.lazy = c.lazy();
        it.fromCss = c.fromCss();
        it.status = f.blocked() ? 0 : f.status();
        // Algoritma 2: yalnız son adımın ilk baytı (yönlendirmeler redirect_ms'de)
        it.ttfbMs = Math.max(0, meta.finalTtfbMs() != null ? meta.finalTtfbMs() : f.ttfbMs());
        it.durationMs = Math.max(0, f.durationMs());
        it.redirectMs = Math.max(0, meta.redirectMs());
        Long len = meta.contentLength();
        if (probe) {
            // Gövde indirilmedi: boyut başlıktan (yoksa bilinmiyor)
            boolean known = len != null && len <= MAX_HEADER_SIZE;
            it.bytes = known ? len : 0;
            it.sizeSource = known ? ScanItem.SIZE_HEADER : ScanItem.SIZE_UNKNOWN;
            it.truncated = false;
        } else if (f.truncated() && len != null && len > f.bytes() && len <= MAX_HEADER_SIZE) {
            it.bytes = len;
            it.sizeSource = ScanItem.SIZE_HEADER;
            it.truncated = false;
        } else {
            it.bytes = Math.max(0, f.bytes());
            it.truncated = f.truncated();
        }
        it.host = lowerHost(c.url());
        boolean first = firstParty(it.host, rootHost);
        it.thirdParty = !first;
        String enc = meta.contentEncoding();
        it.compressed = enc != null && !enc.isBlank() && !"identity".equalsIgnoreCase(enc.trim());
        PageFetchCore.ScanMeta scan = meta.scan();
        // Algoritma 4: önbellek sınıfı yalnız yanıt geldiyse ve doğrulayıcı (ETag / Last-Modified) bilgisi yakalandıysa —
        // yoksa sınıf yazılmaz (eski ömür kuralına düşülür), uydurulmaz.
        CachePolicy.Policy cache = CachePolicy.classify(meta.cacheControl(), scan == null ? null : scan.pragma(),
                meta.expires(), meta.date(), scan == null ? null : scan.etag(), scan == null ? null : scan.lastModified(),
                clock.getAsLong());
        it.cacheTtlS = cache.ttlS();
        if (scan != null && f.status() > 0) {
            it.cacheClass = cache.cacheClass();
            it.cacheHeuristicS = cache.heuristicS();
        }
        it.redirects = meta.redirects();
        it.finalUrl = meta.redirects() > 0 ? meta.finalUrl() : null;
        if (PageResourceDiscovery.IMAGE.equals(it.category) || PageResourceDiscovery.FAVICON.equals(it.category)) {
            ImageDimensions.Size d = ImageDimensions.read(meta.head());
            if (d != null) { it.width = d.width(); it.height = d.height(); }
        }
        if (f.blocked()) it.error = "BLOCKED";
        else if (f.status() == 0) it.error = f.failure() != null ? f.failure().code() : "TRANSPORT_ERROR";
        it.verdict = verdictOf(f, first, it.pageLoad);
        it.via = via;
        it.foundIn = foundIn;
        it.headers = f.status() > 0 ? headers(meta) : null;
        // ── algoritma 3 ──
        PageFetchCore.ScanMeta sm = meta.scan();
        if (sm != null && f.status() > 0) {
            // Vekil üzerinden çekilen nesnenin HTTP sürümü vekilin değeridir (SSL inceleyen vekil ALPN'de çoğunlukla yalnız
            // http/1.1 sunar) — host ölçümündeki "vekilli ölçülmez" kuralıyla aynı: bilinmiyor (inceleme O-5).
            it.httpVersion = "proxy".equals(via) ? null : sm.httpVersion();
            it.contentHash = probe || it.truncated ? null : sm.contentHash();
            it.validator = sm.etag() != null || sm.lastModified() != null;
            it.h3Advertised = sm.altSvc() != null && h3(sm.altSvc());
        }
        if (PageResourceDiscovery.IMAGE.equals(it.category) && meta.head() != null) {
            int frames = ImageDimensions.gifFrames(meta.head());
            if (frames > 0) it.gifFrames = frames;
        }
        // Görsel aracı (2026-11-14): görseli gösteren öğenin bildirdiği boyut (önerilen hedef genişlik bundan türetilir)
        if (PageResourceDiscovery.IMAGE.equals(it.category)) {
            it.declaredWidth = c.declaredWidth();
            it.declaredHeight = c.declaredHeight();
        }
        return it;
    }

    /** {@code Alt-Svc}'de HTTP/3 duyurusu ({@code h3=} ya da {@code h3-NN=}). */
    static boolean h3(String altSvc) {
        String a = altSvc.toLowerCase(Locale.ROOT);
        int i = a.indexOf("h3");
        while (i >= 0) {
            boolean start = i == 0 || a.charAt(i - 1) == ' ' || a.charAt(i - 1) == ',';
            int j = i + 2;
            while (j < a.length() && (Character.isDigit(a.charAt(j)) || a.charAt(j) == '-')) j++;
            if (start && j < a.length() && a.charAt(j) == '=') return true;
            i = a.indexOf("h3", i + 2);
        }
        return false;
    }

    /** CDN önbellek durumu: x-cache / cf-cache-status içinde HIT / MISS; yoksa null. */
    static String cdnCache(String xCache, String age) {
        if (xCache != null) {
            String x = xCache.toUpperCase(Locale.ROOT);
            if (x.contains("HIT")) return "hit";
            if (x.contains("MISS") || x.contains("EXPIRED") || x.contains("BYPASS") || x.contains("DYNAMIC")) return "miss";
        }
        if (age != null) {
            try {
                if (Long.parseLong(age.trim()) > 0) return "hit";
            } catch (NumberFormatException ignore) { /* bozuk başlık */ }
        }
        return null;
    }

    /** İndirilmeyen aday (tarayıcı istemez) — boyutsuz, kararı {@code skipped}. */
    ScanItem notLoadedItem(PageResourceDiscovery.Candidate c, String rootHost, String foundIn) {
        ScanItem it = new ScanItem();
        it.url = c.url();
        it.category = c.category();
        it.pageLoad = false;
        it.lazy = true;
        it.status = 0;
        it.bytes = 0;
        it.sizeSource = ScanItem.SIZE_UNKNOWN;
        it.host = lowerHost(c.url());
        it.thirdParty = !firstParty(it.host, rootHost);
        it.verdict = ScanItem.SKIPPED;
        it.foundIn = foundIn;
        return it;
    }

    /** En çok bu kadar karakter (başlık değerleri). */
    static final int HEADER_VALUE_MAX = 200;

    /**
     * Gösterilen yanıt başlıkları — YALNIZ şunlar: içerik türü / kodlaması / önbellek / süre sonu / uzunluk / sunucu /
     * önbellek isabeti. Çerez, kimlik ve diğer başlıklar ASLA taşınmaz. Değerler ≤ {@value #HEADER_VALUE_MAX} karakter.
     */
    static Map<String, String> headers(PageFetchCore.Meta meta) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("content_type", clipHeader(meta.contentType()));
        h.put("content_encoding", clipHeader(meta.contentEncoding()));
        h.put("cache_control", clipHeader(meta.cacheControl()));
        h.put("expires", clipHeader(meta.expires()));
        h.put("content_length", meta.contentLength() == null ? null : String.valueOf(meta.contentLength()));
        h.put("server", clipHeader(meta.server()));
        if (meta.xCache() != null) h.put("x_cache", clipHeader(meta.xCache()));
        // ── algoritma 3 (yalnız varsa) ──
        PageFetchCore.ScanMeta sm = meta.scan();
        if (sm != null) {
            if (sm.etag() != null) h.put("etag", clipHeader(sm.etag()));
            if (sm.lastModified() != null) h.put("last_modified", clipHeader(sm.lastModified()));
            if (sm.age() != null) h.put("age", clipHeader(sm.age()));
            if (sm.pragma() != null) h.put("pragma", clipHeader(sm.pragma()));
            if (sm.altSvc() != null) h.put("alt_svc", clipHeader(sm.altSvc()));
            String cdn = cdnCache(meta.xCache(), sm.age());
            if (cdn != null) h.put("cdn_cache", cdn);
        }
        return h;
    }

    private static String clipHeader(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.length() > HEADER_VALUE_MAX ? t.substring(0, HEADER_VALUE_MAX) : t;
    }

    static String lowerHost(String url) {
        String h = PageFetchCore.hostOf(url);
        return h == null ? null : h.toLowerCase(Locale.ROOT);
    }

    private boolean firstParty(String host, String root) {
        if (root == null || host == null) return true;
        try {
            return publicSuffix.sameSite(host, root);
        } catch (RuntimeException e) {
            return host.equalsIgnoreCase(root);
        }
    }

    /** İçerik türüne göre kategori: bağlantı HTML değilse BELGE; OTHER / stilden bulunan içerik türüyle incelir. */
    /**
     * Keşif türünü yanıtın içerik türüyle inceltir — YALNIZ başarılı (2xx) yanıtta. Kırık / hata yanıtının içerik türü
     * (düz metin 404 / 500 sayfası) hata sayfasınındır, nesnenin değil: bağlantı {@code LINK} kalır, "belge" sayılmaz
     * (belge = uzantı ya da başarılı yanıtın HTML olmayan içerik türü; sözleşme incelemesi D5).
     */
    static String refine(PageResourceDiscovery.Candidate c, String ct, int status) {
        String cat = c.category();
        if (ct == null || status < 200 || status >= 300) return cat;
        if (PageResourceDiscovery.LINK.equals(cat)) {
            boolean html = ct.contains("html");
            return html || ct.isEmpty() ? PageResourceDiscovery.LINK : PageResourceDiscovery.DOCUMENT;
        }
        if (PageResourceDiscovery.OTHER.equals(cat)) {
            if (ct.startsWith("image/")) return PageResourceDiscovery.IMAGE;
            if (ct.startsWith("font/") || ct.contains("font")) return PageResourceDiscovery.FONT;
            if (ct.equals("text/css")) return PageResourceDiscovery.CSS;
            if (ct.contains("javascript") || ct.contains("ecmascript")) return PageResourceDiscovery.JS;
            if (ct.startsWith("video/") || ct.startsWith("audio/")) return PageResourceDiscovery.MEDIA;
        }
        return cat;
    }

    /** Başlıktan kabul edilen en büyük boyut (1 TB) — saçma / taşan bir Content-Length toplamları bozmasın. */
    static final long MAX_HEADER_SIZE = 1L << 40;

    static String contentType(String raw) {
        if (raw == null) return null;
        String t = raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    /**
     * Önbellek süresi (sn): {@code no-store} / çıplak {@code no-cache} → -1, {@code max-age=N} → N (ilk geçen; sayısal değilse
     * 0 = bayat), yoksa {@code Expires} − {@code Date} (yoksa şimdi; bozuk {@code Expires} → 0), hiçbiri yoksa null. Kural
     * {@link CachePolicy#classify}'dadır (tek kaynak).
     */
    static Long cacheTtl(String cacheControl, String expires, String date, long nowMs) {
        return CachePolicy.ttl(cacheControl, null, expires, date, nowMs);
    }

    private static String decodeText(byte[] body, String contentType) {
        Charset cs = StandardCharsets.UTF_8;
        if (contentType != null) {
            int i = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                String name = contentType.substring(i + 8).split(";", 2)[0].replace("\"", "").trim();
                try { cs = Charset.forName(name); } catch (RuntimeException ignore) { /* bilinmeyen kodlama → UTF-8 */ }
            }
        }
        int n = Math.min(body.length, PageResourceDiscovery.MAX_CSS_CHARS);
        return new String(body, 0, n, cs);
    }

    // ── Derin analiz (algoritma 3) yardımcıları ────────────────────────────────────────────

    /** Adresin uzantısından kategori tahmini (çekim ayarı seçimi için; keşif kategorisi ayrıca incelir). */
    private static String category(String url) {
        String e = PageResourceDiscovery.ext(url);
        return e.equals("js") || e.equals("mjs") ? PageResourceDiscovery.JS : "";
    }

    /**
     * Görülen {@code Set-Cookie}'ler (değer çekirdekte atıldı) — sayfa yükü yanıtlarından, sınırlı. Taramanın bağlamına eklenir;
     * dönen liste bu yanıtın katkısıdır (çok sayfalı taramada ortak nesnenin katkısı diğer sayfalara aktarılır).
     */
    private List<DeepContext.CookieSeen> collectCookies(Run run, PageFetchCore.Meta meta) {
        PageFetchCore.ScanMeta sm = meta == null ? null : meta.scan();
        if (sm == null || sm.cookies().isEmpty()) return List.of();
        long now = clock.getAsLong();
        List<DeepContext.CookieSeen> seen = new ArrayList<>();
        for (PageFetchCore.SeenCookie sc : sm.cookies()) {
            if (seen.size() >= MAX_COOKIES) break;
            CookieFacts.Cookie c = CookieFacts.parse(sc.header(), sc.host(), now);
            if (c == null) continue;
            seen.add(new DeepContext.CookieSeen(c, !firstParty(sc.host(), run.rootHost)));
        }
        addCookies(run, seen);
        return seen;
    }

    private static void addCookies(Run run, List<DeepContext.CookieSeen> seen) {
        for (DeepContext.CookieSeen cs : seen) {
            if (run.cookieCount.get() >= MAX_COOKIES) return;
            run.cookieCount.incrementAndGet();
            run.cookies.add(cs);
        }
    }

    /** Stil metninin olguları: {@code @font-face}'ler ve başvurular (anahtarlar "kullanılmayan önyükleme" için). */
    private List<PageResourceDiscovery.Candidate> cssFacts(Run run, String css, String base, String cssUrl) {
        return cssFacts(run, css, base, cssUrl, null);
    }

    /**
     * {@link #cssFacts(Run, String, String, String)} + çok sayfalı taramada nesnenin katkısı {@code holder}'a yazılır (tüm
     * başvurular ve yazı tipi blokları — diğer sayfalarda yeniden ölçülmeden aynı stil bağlama eklenir).
     */
    private List<PageResourceDiscovery.Candidate> cssFacts(Run run, String css, String base, String cssUrl, Planned holder) {
        List<PageResourceDiscovery.Candidate> refs = PageResourceDiscovery.cssRefs(css, base);
        List<FontFaces.Face> faces = FontFaces.scan(css, base, cssUrl);
        addCssFacts(run, refs, faces);
        if (holder != null && run.site != null) {
            holder.siteRefs = run.site.retainRefs(refs);
            holder.siteRefsFull = refs.size();
            holder.siteFaces = faces;
        }
        return refs;
    }

    private static void addCssFacts(Run run, List<PageResourceDiscovery.Candidate> refs, List<FontFaces.Face> faces) {
        for (PageResourceDiscovery.Candidate r : refs) {
            if (run.cssReferenced.size() >= MAX_CSS_REFERENCED) break;
            run.cssReferenced.add(r.url());
        }
        for (FontFaces.Face face : faces) {
            if (run.fontFaceCount.incrementAndGet() > MAX_FONT_FACES) break;
            run.fontFaces.add(face);
        }
    }

    /**
     * Betik örneği: küçültme, kaynak haritası, kütüphane (örnek bellekte kalır, saklanmaz).
     *
     * @return örnek okunamadı mı (br / kesik — "örnek yok" notuna sayılır)
     */
    private boolean inspectScript(Run run, ScanItem it, PageFetchCore.MetaFetch mf) {
        PageFetchCore.ScanMeta sm = mf.meta() == null ? null : mf.meta().scan();
        String head = sm == null || sm.sampleHead() == null ? null : utf8(sm.sampleHead());
        String tail = sm == null || sm.sampleTail() == null ? null : utf8(sm.sampleTail());
        if (head == null) run.samplesUnavailable.incrementAndGet();
        AssetSample.Result res = AssetSample.inspect(it.url, head, sm != null && sm.sampleComplete() ? null : tail, true);
        it.minified = res.minified();
        if (res.library() != null) {
            it.libraryName = res.library().name();
            it.libraryVersion = res.library().version();
        } else {
            com.sitemonitor.service.page.scan.deep.JsLibraryCatalog.Library byUrl =
                    com.sitemonitor.service.page.scan.deep.JsLibraryCatalog.detect(it.url, null);
            if (byUrl != null) { it.libraryName = byUrl.name(); it.libraryVersion = byUrl.version(); }
        }
        String base = mf.meta() != null && mf.meta().finalUrl() != null ? mf.meta().finalUrl() : it.url;
        it.sourceMap = sourceMapUrl(base, sm == null ? null : sm.sourceMapHeader(), res.sourceMap());
        return head == null;
    }

    /** Stil örneği (çözülmüş gövdeden): küçültme ve kaynak haritası. */
    private void inspectStylesheet(ScanItem it, String text, String base, PageFetchCore.MetaFetch mf) {
        String head = text.length() > AssetSample.HEAD_CHARS ? text.substring(0, AssetSample.HEAD_CHARS) : text;
        String tail = text.length() > AssetSample.HEAD_CHARS
                ? text.substring(Math.max(0, text.length() - AssetSample.TAIL_CHARS)) : null;
        AssetSample.Result res = AssetSample.inspect(it.url, head, tail, false);
        it.minified = res.minified();
        PageFetchCore.ScanMeta sm = mf.meta() == null ? null : mf.meta().scan();
        it.sourceMap = sourceMapUrl(base, sm == null ? null : sm.sourceMapHeader(), res.sourceMap());
    }

    /** Kaynak haritası adresi: {@code SourceMap} başlığı önce, yoksa yorum; mutlak http(s), ≤ 4096; değilse null. */
    static String sourceMapUrl(String base, String header, String comment) {
        String raw = header != null && !header.isBlank() ? header.trim() : comment;
        if (raw == null || raw.isBlank() || raw.length() > PageResourceDiscovery.MAX_CSS_CHARS) return null;
        String u = PageFetchCore.normalizeUrl(PageFetchCore.resolve(base, PageFetchCore.normalizeUrl(raw)));
        if (!PageFetchCore.isHttp(u) || u.length() > 4096) return null;
        return PageResourceDiscovery.key(u);
    }

    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /**
     * Keşfedilen host'ların bağlantı kurulum süresi (DNS / TCP / TLS) — en çok {@value PageResourceAnalyzer#HOST_TIMING_LIMIT}
     * host (en çok nesnesi olan önce, ana host ilk), {@value #HOST_TIMING_CONCURRENCY} eşzamanlı, en çok
     * {@value #HOST_TIMING_PHASE_MS} ms. Vekil üzerinden gidilen host ölçülmez ({@code proxy}). İptal / süre dolumunda biten
     * ölçümler kalır, kalanlar {@code cancelled}.
     */
    private void measureHosts(Run run, String base, PageResourceDiscovery.Found found) throws InterruptedException {
        Map<String, String> sample = new LinkedHashMap<>();   // host → örnek adres
        Map<String, Integer> counts = new HashMap<>();
        String mainHost = lowerHost(base);
        if (mainHost != null) { sample.put(mainHost, base); counts.put(mainHost, Integer.MAX_VALUE); }
        for (PageResourceDiscovery.Candidate cand : found.pageLoad()) {
            if (cand.notLoaded()) continue;
            String h = lowerHost(cand.url());
            if (h == null) continue;
            sample.putIfAbsent(h, cand.url());
            counts.merge(h, 1, (a, b) -> a == Integer.MAX_VALUE ? a : a + b);
        }
        List<String> hosts = new ArrayList<>(sample.keySet());
        hosts.sort(Comparator.comparingInt((String h) -> -counts.getOrDefault(h, 0)).thenComparing(h -> h));
        int limit = PageResourceAnalyzer.HOST_TIMING_LIMIT;
        run.hostsNotMeasured = Math.max(0, hosts.size() - limit);
        if (hosts.size() > limit) hosts = new ArrayList<>(hosts.subList(0, limit));
        if (run.halted() || hosts.isEmpty()) return;
        long phaseEnd = Math.min(run.deadline, clock.getAsLong() + HOST_TIMING_PHASE_MS);
        int timeout = Math.min(HOST_TIMING_TIMEOUT_MS, run.c.requestTimeoutMs());
        Semaphore gate = new Semaphore(HOST_TIMING_CONCURRENCY);
        Map<String, Future<DeepContext.HostTiming>> futures = new LinkedHashMap<>();
        for (String h : hosts) {
            String url = sample.get(h);
            futures.put(h, run.submit(() -> {
                gate.acquire();
                try {
                    if (run.halted()) return DeepContext.HostTiming.notMeasured("cancelled");
                    if (run.via(url)) return DeepContext.HostTiming.notMeasured("proxy");
                    int port = com.sitemonitor.service.page.scan.deep.DeepUrls.port(url);
                    boolean tls = com.sitemonitor.service.page.scan.deep.DeepUrls.https(url);
                    PageFetchCore.HostConnect hc = core.measureConnection(h, port < 0 ? (tls ? 443 : 80) : port, tls, timeout);
                    return new DeepContext.HostTiming(hc.dnsMs(), hc.connectMs(), hc.tlsMs(), hc.error() == null, hc.error());
                } finally {
                    gate.release();
                }
            }));
        }
        for (Map.Entry<String, Future<DeepContext.HostTiming>> e : futures.entrySet()) {
            DeepContext.HostTiming t;
            try {
                t = awaitBeating(run, e.getValue(), phaseEnd);
            } catch (IllegalStateException ex) {
                t = null;
            }
            run.hostTimings.put(e.getKey(), t == null ? DeepContext.HostTiming.notMeasured("cancelled") : t);
        }
    }

    /**
     * Açıkta olabilecek kaynak haritaları: betik / stil nesnelerinin {@code sourceMappingURL}'leri HEAD ile denetlenir (en çok
     * {@value PageResourceAnalyzer#SOURCE_MAP_LIMIT}, {@value #SOURCE_MAP_PHASE_MS} ms). 2xx → açık; 401 / 403 / 404 / 410 →
     * kapalı; diğerleri (405 / 501 / aktarım hatası / engel) → bilinmiyor (null). Gövde indirilmez.
     */
    private void checkSourceMaps(Run run, List<Planned> all) throws InterruptedException {
        java.util.LinkedHashSet<String> maps = new java.util.LinkedHashSet<>();
        for (Planned p : all) if (p.item != null && p.item.sourceMap != null) maps.add(p.item.sourceMap);
        if (maps.isEmpty() || run.halted()) return;
        List<String> list = new ArrayList<>(maps);
        int limit = PageResourceAnalyzer.SOURCE_MAP_LIMIT;
        run.sourceMapsNotChecked = Math.max(0, list.size() - limit);
        if (list.size() > limit) list = new ArrayList<>(list.subList(0, limit));
        long phaseEnd = Math.min(run.deadline, clock.getAsLong() + SOURCE_MAP_PHASE_MS);
        Map<String, Future<Boolean>> futures = new LinkedHashMap<>();
        for (String u : list) {
            boolean viaProxy = run.via(u);
            futures.put(u, run.submit(() -> {
                PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(run.c.requestTimeoutMs(), run.ua, null, false,
                        false, viaProxy);
                PageFetchCore.Fetch h = core.fetchWithMeta(u, "HEAD", o, 0, run.probeTuning).fetch();
                if (h.blocked() || h.status() == 0) return null;
                if (h.status() >= 200 && h.status() < 300) return Boolean.TRUE;
                int sc = h.status();
                return sc == 401 || sc == 403 || sc == 404 || sc == 410 ? Boolean.FALSE : null;
            }));
        }
        for (Map.Entry<String, Future<Boolean>> e : futures.entrySet()) {
            Boolean v;
            try {
                v = awaitBeating(run, e.getValue(), phaseEnd);
            } catch (IllegalStateException ex) {
                v = null;
            }
            if (v != null) run.sourceMaps.put(e.getKey(), v);
        }
    }

    // ── Ölçülmüş görsel kazancı ─────────────────────────────────────────────────────────────

    /**
     * Sayfa yükündeki en büyük ≤ {@value #OPT_MAX_IMAGES} JPEG / PNG görseli (≥ 100 KB, ≤ 40 MP, boyutu başlıktan biliniyor)
     * sunucuda yeniden kodlar → {@link ScanItem#optimizedBytes}. Sırayla, taramanın süre bütçesi içinde (en çok
     * {@value #OPT_PHASE_MS} ms); pod genelinde {@value #OPT_CONCURRENT} eşzamanlı ve bellek bütçeli. Ölçülemeyen görsel
     * eski tahminle kalır (sessizce "kazanç yok" denmez).
     */
    private void optimizeImages(Run run, List<Planned> all) throws InterruptedException {
        if (run.halted() || run.timeCapped) return;
        List<ScanItem> cands = all.stream().map(p -> p.item).filter(Objects::nonNull)
                .filter(PageResourceScanService::optimizable)
                .sorted(Comparator.comparingLong((ScanItem i) -> i.bytes).reversed())
                .limit(OPT_MAX_IMAGES).toList();
        if (cands.isEmpty()) return;
        long phaseEnd = Math.min(run.deadline, clock.getAsLong() + OPT_PHASE_MS);
        for (ScanItem it : cands) {
            if (run.halted() || clock.getAsLong() >= phaseEnd) break;
            Future<Long> f = run.submit(() -> optimizeOne(run, it, phaseEnd));
            Long opt = awaitBeating(run, f, phaseEnd);
            if (opt != null) it.optimizedBytes = opt;
        }
    }

    /** Yeniden kodlamaya uygun mu: başarılı sayfa yükü görseli, JPEG / PNG, 100 KB – 10 MB, kesilmemiş, piksel boyutu belli. */
    static boolean optimizable(ScanItem i) {
        if (!PageResourceDiscovery.IMAGE.equals(i.category) || !i.pageLoad || i.skipped() || !i.ok() || i.truncated) return false;
        if (i.bytes < OPT_MIN_BYTES || i.bytes > OPT_MAX_BODY) return false;
        if (i.width == null || i.height == null || (long) i.width * i.height > OPT_MAX_PIXELS) return false;
        String ct = i.contentType == null ? "" : i.contentType;
        if (!ct.isEmpty()) return ct.equals("image/jpeg") || ct.equals("image/jpg") || ct.equals("image/pjpeg") || ct.equals("image/png");
        String e = PageResourceDiscovery.ext(i.url);
        return e.equals("jpg") || e.equals("jpeg") || e.equals("png");
    }

    /** İkinci indirmenin ölçülebilirliği: ölçülür (bellek izni MB ve başlık) ya da neden. */
    record MeasurePlan(int memoryMb, ImageDimensions.Header header, String skipReason) {
        static MeasurePlan skip(String reason) {
            return new MeasurePlan(0, null, reason);
        }

        boolean measurable() {
            return skipReason == null;
        }
    }

    /**
     * Bellek izni İKİNCİ indirmenin GERÇEK başlığından (inceleme O-2): ilk ölçümle aynı biçim, aynı piksel boyutu ve aynı tel
     * baytı değilse ölçülmez (görsel değişmiş ya da istek başına farklı içerik — izin eski boyuta göre olurdu); başlıktan
     * hesaplanan çalışma belleği ({@link ImageOptimizer#workingBytes} — 16 bit kanal, palet, gri, JPEG bileşen sayısı) pod
     * bütçesini ({@value #OPT_MEMORY_MB} MB) ya da piksel tavanını aşarsa ölçülmez. Her durumda eski tahmin kalır.
     */
    static MeasurePlan measurePlan(ScanItem first, byte[] body, long wireBytes) {
        if (body == null || body.length == 0) return MeasurePlan.skip("empty");
        if (wireBytes != first.bytes) return MeasurePlan.skip("bytes_changed");
        ImageDimensions.Header h = ImageDimensions.header(body);
        if (h == null) return MeasurePlan.skip("unsupported");
        boolean firstPng = "image/png".equals(first.contentType)
                || (first.contentType == null && "png".equals(PageResourceDiscovery.ext(first.url)));
        if (!h.format().equals(firstPng ? "png" : "jpeg")) return MeasurePlan.skip("format_changed");
        if (first.width == null || first.height == null || h.width() != first.width || h.height() != first.height) {
            return MeasurePlan.skip("size_changed");
        }
        if ((long) h.width() * h.height() > OPT_MAX_PIXELS) return MeasurePlan.skip("too_many_pixels");
        long need = ImageOptimizer.workingBytes(h, body.length);
        long mb = (need + PageResourceAnalyzer.MB - 1) / PageResourceAnalyzer.MB;
        if (mb > OPT_MEMORY_MB) return MeasurePlan.skip("over_budget");
        return new MeasurePlan((int) Math.max(1, mb), h, null);
    }

    private Long optimizeOne(Run run, ScanItem it, long phaseEnd) throws InterruptedException {
        long wait = Math.max(1, Math.min(10_000L, phaseEnd - clock.getAsLong()));
        // Yuva önce: gövde (≤ 10 MB) yuva başına tek — pod'da en çok {OPT_CONCURRENT} gövde bellekte
        if (!imageSlots.tryAcquire(wait, TimeUnit.MILLISECONDS)) return null;
        try {
            boolean viaProxy = "proxy".equals(it.via);
            PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(run.c.requestTimeoutMs(), run.ua, null, true, true,
                    viaProxy);
            PageFetchCore.MetaFetch mf = core.fetchWithMeta(it.url, "GET", o, 0, run.imageTuning);
            PageFetchCore.Fetch f = mf.fetch();
            run.spent.addAndGet(Math.max(0, f.bytes()));
            if (Thread.currentThread().isInterrupted() || run.halted()) return null;
            if (f.status() < 200 || f.status() >= 300 || f.body() == null || f.truncated()) return null;
            MeasurePlan plan = measurePlan(it, f.body(), f.bytes());
            if (!plan.measurable()) {
                log.debug("Kaynak analizi görsel kazancı ölçülmedi ({}): {}", plan.skipReason(), PageFetchCore.sanitize(it.url));
                return null;
            }
            long wait2 = Math.max(1, Math.min(10_000L, phaseEnd - clock.getAsLong()));
            if (!imageMemory.tryAcquire(plan.memoryMb(), wait2, TimeUnit.MILLISECONDS)) return null;
            try {
                // İzin başlığa göre alındı; çözücü de çözmeden önce aynı boyutu ve izni aşmayan ham türü doğrular
                ImageOptimizer.Outcome out = ImageOptimizer.optimize(f.body(), plan.header().width(), plan.header().height(),
                        (long) plan.memoryMb() * PageResourceAnalyzer.MB);
                if (out == null) return null;
                return Math.min(it.bytes, out.bytes());
            } finally {
                imageMemory.release(plan.memoryMb());
            }
        } finally {
            imageSlots.release();
        }
    }

    // ── G çok sayfalı analiz (prs3 be2, 2026-11) ──────────────────────────────────────────────
    //
    // Ana sayfa bugünkü gibi ölçülür ve analiz edilir (belge / bağlantı doğrulaması YALNIZ ana sayfada). Ardından ana sayfanın
    // bağlantılarından seçilen iç sayfalar sırayla: sayfa başına HTML + sayfa yükü nesneleri + A–E analizi. Sayfalar arası ortak
    // nesneler BİR kez ölçülür (URL önbelleği; nesnenin derin olgulara katkısı — çerez, yazı tipi bloğu, stil başvurusu, örnek
    // okunamadı — kopyayla birlikte o sayfanın bağlamına eklenir). Süre / bayt / nesne tavanı tüm site için TEK bütçedir;
    // dolduğunda biten sayfalar raporlanır, kalanlar "taranmadı" (skipped) satırı olur ve {@code analysis.site.incomplete}
    // işaretlenir. Özet satırının {@code capped}'ı YALNIZ ana sayfanın kendi kırpılmasıdır (O-3): özet değerleri ana
    // sayfanındır; bütçe ve eğilim onu değerlendirir.

    /** Bir nesnenin önbellekteki ölçümü ve derin olgulara katkısı (yeniden ölçülmeden diğer sayfalara aktarılır). */
    record SiteCached(ScanItem item, List<PageResourceDiscovery.Candidate> refs, List<FontFaces.Face> faces,
                      List<DeepContext.CookieSeen> cookies, boolean sampleMissing, boolean refsCut) {}

    /** Önbellekte tutulan stil başvurularının site başına karakter tavanı (adresler; ≈ 8 MB) — D-6. */
    static final long SITE_REFS_MAX_CHARS = 4_000_000L;
    /** Önbellekte tutulan stil başvurusu sayısı tavanı = nesne tavanı × bu çarpan (≤ 50 000) — D-6. */
    static final int SITE_REFS_PER_OBJECT = 10;

    /** Çok sayfalı taramanın paylaşılan durumu: URL önbelleği ve site geneli nesne sayacı. */
    static final class SiteRun {
        final Map<String, SiteCached> cache = new ConcurrentHashMap<>();
        /** Ölçülen (planlanan) nesne sayısı — tüm site için TEK nesne tavanı. */
        final AtomicInteger objects = new AtomicInteger();
        /** Önbellekte tutulan stil başvurusu sayısı ve adres karakteri (bellek tavanları — D-6). */
        final AtomicInteger refsRetained = new AtomicInteger();
        final AtomicLong refCharsRetained = new AtomicLong();
        final int refsCap;
        final long charsCap;
        /** Site geneli nesne tavanı (sayfa sayısı seçilen taramada sayfa tavanıyla aynı; tüm sitede ayrı ayar). */
        final int maxObjects;
        /** Tüm site taraması (2026-11-15): her iç sayfaya ayrıca sayfa başına nesne tavanı uygulanır. */
        final boolean full;
        /** Tüm site taramasında sayfa-kaynak anahtarları paylaşılır (aynı nesne her sayfada yeni dizgi tutmaz). */
        final Map<String, String> keyPool = new ConcurrentHashMap<>();

        String pooled(String key) {
            if (!full || key == null) return key;
            String prev = keyPool.putIfAbsent(key, key);
            return prev == null ? key : prev;
        }

        SiteRun(int maxObjects) {
            this(maxObjects, false);
        }

        SiteRun(int maxObjects, boolean full) {
            this.refsCap = Math.max(1, Math.min(maxObjects, 5_000)) * SITE_REFS_PER_OBJECT;
            this.charsCap = SITE_REFS_MAX_CHARS;
            this.maxObjects = maxObjects;
            this.full = full;
        }

        /** Testler için: tavanlar elle. */
        SiteRun(int refsCap, long charsCap) {
            this.refsCap = refsCap;
            this.charsCap = charsCap;
            this.maxObjects = Integer.MAX_VALUE;
            this.full = false;
        }

        /**
         * Başvuruların önbellekte saklanacak kısmı: sayı ve karakter tavanı içinde baştan. Tavan dolduysa kesilmiş liste döner
         * ({@link SiteCached#refsCut} — o stili önbellekten kullanan sayfa "kısmi" işaretlenir). Ölçüldüğü sayfa bu listeyi
         * KULLANMAZ (kendi oda sınırlı başvuru listesiyle planlar).
         */
        List<PageResourceDiscovery.Candidate> retainRefs(List<PageResourceDiscovery.Candidate> refs) {
            if (refs == null || refs.isEmpty()) return List.of();
            int take = 0;
            long chars = 0;
            for (PageResourceDiscovery.Candidate c : refs) {
                long len = c.url() == null ? 0 : c.url().length();
                if (refsRetained.get() + take + 1 > refsCap || refCharsRetained.get() + chars + len > charsCap) break;
                take++;
                chars += len;
            }
            refsRetained.addAndGet(take);
            refCharsRetained.addAndGet(chars);
            if (take == refs.size()) return refs;
            return take == 0 ? List.of() : List.copyOf(refs.subList(0, take));
        }
    }

    /**
     * İç sayfaları tarar. Dönen liste: ilk satır ana sayfa, sonra seçilen sayfalar (bitti / açılamadı / taranmadı). Tarama
     * durduruldu (iptal, kapanış, satır kayboldu) → null.
     */
    private List<SitePages.Page> scanSite(Run run, Snapshot s, String base, Predicate<String> exclude, List<String> linkUrls,
                                          List<Planned> mainAll, List<ScanItem> mainItems, PageResourceAnalyzer.Result mainR)
            throws InterruptedException {
        SiteRun site = run.site;
        site.objects.set(mainAll.size() + 1);   // ana sayfanın planladıkları + HTML (plan() ile aynı sayım)
        for (Planned p : mainAll) cache(site, p);
        List<SitePages.Page> pages = new ArrayList<>();
        pages.add(donePage(s.url(), mainItems, mainR, mainR.capped()));
        int extra = Math.max(0, s.site().maxPages() - 1);
        // Ana sayfanın bağlantı denetiminde kırık bulunanlar aday olmaz (bütçe harcamaz; ana sayfanın sonucunda zaten kırık)
        Set<String> broken = new HashSet<>();
        for (ScanItem it : mainItems) {
            if (!it.pageLoad && it.url != null && SitePages.brokenLink(it.verdict, it.status)) broken.add(it.url);
        }
        List<String> urls = SitePages.select(linkUrls, s.url(), base, exclude, extra, broken);
        int total = 1 + urls.size();
        sitePagesProgress(s.id(), 1, total);   // ana sayfa bitti; toplam = seçilen sayfalar + ana sayfa
        for (String u : urls) {
            if (run.halted()) return null;
            String why = siteBudgetLeft(run);
            if (why != null) {
                pages.add(SitePages.Page.skipped(u, why));
            } else {
                SitePages.Page pg = scanExtraPage(run, u, exclude);
                if (pg == null) return null;
                pages.add(pg);
            }
            sitePagesProgress(s.id(), pages.size(), total);
            run.beat(false);
        }
        return pages;
    }

    /**
     * Sayfa ilerlemesini DB'ye yazar (yalnız çalışan satıra; liste ve tek analiz yanıtı {@code progress.pages_done} /
     * {@code pages_total} olarak okur — başka pod'dan da görünür). Yazılamazsa yalnız ilerleme eksik kalır (ölçüm sürer).
     */
    private void sitePagesProgress(long id, int done, int total) {
        try {
            jdbc.update(SQL_SITE_PROGRESS, done, total, id);
        } catch (RuntimeException e) {
            log.debug("Kaynak analizi sayfa ilerlemesi yazılamadı: {}", e.getClass().getSimpleName());
        }
    }

    /** Bütçe kaldı mı: null = devam; değilse sayfa satırının kodu ({@code NOT_SCANNED:TIME|BYTES|OBJECTS}). */
    private String siteBudgetLeft(Run run) {
        if (clock.getAsLong() >= run.deadline) { run.timeCapped = true; return "NOT_SCANNED:TIME"; }
        if (run.spent.get() >= run.budget) { run.bytesCapped = true; return "NOT_SCANNED:BYTES"; }
        if (run.site.objects.get() + 1 >= run.site.maxObjects) { run.objectsCapped = true; return "NOT_SCANNED:OBJECTS"; }
        return null;
    }

    /** Tüm site taramasının sonucu: bu taramanın sayfaları, devralınan satırlar, bölüme eklenecek alanlar, son denetim noktası. */
    record FullCrawl(List<SitePages.Page> pages, List<Map<String, Object>> prior, Map<String, Object> extra, String checkpoint) {}

    /** Sürdürülen taramanın denetim noktası: görüntü satırları (önceki taramaların), taranmış adresler, kuyruk (ham adresler). */
    public record Resume(List<Map<String, Object>> rows, List<String> doneUrls, List<String> queue) {}

    /** Sürdürme reddi: {@code code} istemciye gider (bulunamadı / çalışıyor / sürdürülemez / kuyruk boş / sınır). */
    public static final class ResumeException extends RuntimeException {
        public final String code;

        public ResumeException(String code) {
            super(code);
            this.code = code;
        }
    }

    /**
     * Sürdürülecek taramanın denetim noktası (2026-11-15). Uygun değilse {@link ResumeException}: bulunamadı
     * ({@code RESOURCE_SCAN_NOT_FOUND}), çalışıyor ({@code RESOURCE_SCAN_RUNNING}), tüm site taraması değil / nokta yok ya da
     * okunamıyor ({@code RESOURCE_SCAN_NOT_RESUMABLE}), taranacak adres kalmadı ({@code RESOURCE_SCAN_NOTHING_TO_RESUME}),
     * zincir {@value #RESUME_MAX_ROWS} sayfaya ulaştı ({@code RESOURCE_SCAN_RESUME_LIMIT}).
     */
    @SuppressWarnings("unchecked")
    public Resume loadResume(long monitorId, long scanId) {
        List<String[]> row = jdbc.query(SQL_RESUME_SOURCE, (rs, i) -> new String[]{rs.getString("status"),
                rs.getString("site_crawl"), rs.getString("site_checkpoint")}, scanId, monitorId);
        if (row.isEmpty()) throw new ResumeException("RESOURCE_SCAN_NOT_FOUND");
        String[] r = row.get(0);
        if (RUNNING.equals(r[0])) throw new ResumeException("RESOURCE_SCAN_RUNNING");
        if (!CRAWL_FULL.equals(r[1]) || r[2] == null || r[2].isBlank()) throw new ResumeException("RESOURCE_SCAN_NOT_RESUMABLE");
        Map<String, Object> ck = parseMap(r[2]);
        if (ck == null) throw new ResumeException("RESOURCE_SCAN_NOT_RESUMABLE");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (ck.get("rows") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) rows.add((Map<String, Object>) m);
        List<String> done = new ArrayList<>();
        if (ck.get("done_urls") instanceof List<?> l) for (Object o : l) if (o instanceof String u) done.add(u);
        List<String> queue = new ArrayList<>();
        if (ck.get("queue") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> m && m.get("u") instanceof String u) queue.add(u);
        }
        if (queue.isEmpty()) throw new ResumeException("RESOURCE_SCAN_NOTHING_TO_RESUME");
        if (rows.size() >= RESUME_MAX_ROWS - 1) throw new ResumeException("RESOURCE_SCAN_RESUME_LIMIT");
        return new Resume(rows, done, queue);
    }

    /**
     * Tüm site taraması (2026-11-15): genişlik öncelikli kuyruk ({@link SiteCrawl}) — ana sayfanın bağlantıları, site haritası
     * ({@link #readSitemaps}) ve taranan HER sayfanın bağlantıları. Sınırlar: bu taramada en çok {@code maxPages} sayfa (ana
     * sayfa dahil; sürdürülen zincir toplamı {@value #RESUME_MAX_ROWS}), tüm site süre / bayt bütçesi, site geneli nesne tavanı.
     * Bütçe dolunca kalan kuyruk "taranmadı" listesine nedeniyle yazılır. Denetim noktası en geç {@value #CHECKPOINT_EVERY_PAGES}
     * sayfada / {@value #CHECKPOINT_EVERY_MS} ms'de bir yazılır (pod durursa sürdürülebilir). Durduruldu → null.
     */
    private FullCrawl scanSiteFull(Run run, Snapshot s, String base, Predicate<String> exclude, List<String> linkUrls,
                                   List<Planned> mainAll, List<ScanItem> mainItems, PageResourceAnalyzer.Result mainR)
            throws InterruptedException {
        SiteRun site = run.site;
        site.objects.set(mainAll.size() + 1);
        for (Planned p : mainAll) cache(site, p);
        List<SitePages.Page> pages = new ArrayList<>();
        pages.add(donePage(s.url(), mainItems, mainR, mainR.capped()));
        Set<String> skip = new HashSet<>();
        for (ScanItem it : mainItems) {
            if (!it.pageLoad && it.url != null && SitePages.brokenLink(it.verdict, it.status)) skip.add(it.url);
        }
        Resume resume = null;
        boolean resumeFailed = false;
        if (s.site().resumeFrom() != null) {
            try {
                resume = loadResume(s.monitorId(), s.site().resumeFrom());
            } catch (RuntimeException e) {
                resumeFailed = true;   // başlatmada denetlendi; arada değiştiyse baştan taranır (bilgi alanında söylenir)
            }
        }
        List<Map<String, Object>> prior = new ArrayList<>();
        List<String> doneUrls = new ArrayList<>();
        if (resume != null) {
            prior.addAll(resume.rows());
            doneUrls.addAll(resume.doneUrls());
            skip.addAll(resume.doneUrls());
        }
        SiteCrawl crawl = new SiteCrawl(s.url(), base, exclude, skip);
        if (resume != null) {
            for (int i = 0; i < resume.queue().size(); i += SiteCrawl.MAX_LINKS_PER_PAGE) {
                crawl.offer(resume.queue().subList(i, Math.min(resume.queue().size(), i + SiteCrawl.MAX_LINKS_PER_PAGE)),
                        SiteCrawl.SOURCE_RESUME);
            }
        }
        crawl.offer(linkUrls, SiteCrawl.SOURCE_LINK);
        sitePagesProgress(s.id(), prior.size() + 1, prior.size() + 1 + crawl.queued());
        Map<String, Object> sitemap = readSitemaps(run, base, crawl);
        if (run.halted()) return null;
        int maxPages = s.site().maxPages();
        int cap = Math.max(1, Math.min(maxPages, RESUME_MAX_ROWS - prior.size()));
        String stop = null;
        long lastCk = clock.getAsLong();
        int sinceCk = 0;
        long retained = pages.get(0).resources().size();
        while (true) {
            if (run.halted()) return null;
            if (pages.size() >= cap) {
                if (crawl.queued() > 0) stop = "PAGES";
                break;
            }
            if (retained >= MAX_RETAINED_RESOURCES) {
                if (crawl.queued() > 0) stop = "OBJECTS";
                break;
            }
            SiteCrawl.Entry e = crawl.poll();
            if (e == null) break;
            String why = siteBudgetLeft(run);
            if (why != null) {
                crawl.pushFront(e);
                stop = why.startsWith("NOT_SCANNED:") ? why.substring("NOT_SCANNED:".length()) : why;
                break;
            }
            SitePages.Page pg = scanExtraPage(run, e.url(), exclude);
            if (pg == null) return null;
            if (SitePages.SKIPPED.equals(pg.status())) {
                // Süre sayfanın HTML'i inerken doldu: sayfa taranmadı — kuyruğun önüne döner (sürdürmede yeniden denenir)
                crawl.pushFront(e);
                stop = "TIME";
                break;
            }
            pages.add(pg);
            retained += pg.resources().size();
            doneUrls.add(e.url());
            if (SitePages.DONE.equals(pg.status())) crawl.offer(run.pageLinks, SiteCrawl.SOURCE_LINK);
            run.pageLinks = List.of();
            int done = prior.size() + pages.size();
            sitePagesProgress(s.id(), done, Math.min(prior.size() + cap, done + crawl.queued()));
            run.beat(false);
            long now = clock.getAsLong();
            if (++sinceCk >= CHECKPOINT_EVERY_PAGES || now - lastCk >= CHECKPOINT_EVERY_MS) {
                writeCheckpoint(s.id(), checkpointJson(s, pages, prior, crawl, doneUrls));
                sinceCk = 0;
                lastCk = now;
            }
        }
        if (stop == null && (crawl.dropped() > 0 || crawl.droppedAtLeast())) stop = "KNOWN_CAP";
        // Keşif eksik: site haritası evresi kesildi (dosya / süre / adres tavanı) ya da listelenen bir harita okunamadı —
        // bulunamayan sayfalar olabilir, "her sayfa tarandı" denmez.
        boolean discoveryCut = Boolean.TRUE.equals(sitemap.get("truncated"))
                || (sitemap.get("errors") instanceof List<?> errs && !errs.isEmpty())
                || (sitemap.get("other_host") instanceof Number oh && oh.intValue() > 0);
        List<Map<String, Object>> unscanned = new ArrayList<>();
        String reason = stop == null ? "PAGES" : stop;
        for (SiteCrawl.Entry e : crawl.remaining(MAX_UNSCANNED_LISTED)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", DeepUrls.mask(e.url()));
            m.put("source", e.source());
            m.put("reason", reason);
            unscanned.add(m);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("mode", CRAWL_FULL);
        info.put("max_pages", maxPages);
        info.put("max_seconds", run.c.maxSeconds());
        info.put("discovered", prior.size() + pages.size() + crawl.queued() + crawl.dropped());
        info.put("from_links", crawl.fromLinks());
        info.put("from_sitemap", crawl.fromSitemap());
        info.put("dropped", crawl.dropped());
        if (crawl.droppedAtLeast()) info.put("dropped_at_least", true);
        info.put("stop_reason", stop);   // null = kuyruk bitti: bulunan bütün sayfalar tarandı
        info.put("complete", stop == null && !discoveryCut);
        if (stop == null && discoveryCut) info.put("discovery_incomplete", true);
        info.put("sitemap", sitemap);
        info.put("resumed_from", resume == null ? null : s.site().resumeFrom());
        info.put("prior_pages", prior.size());
        if (resumeFailed) info.put("resume_failed", true);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("crawl", info);
        extra.put("unscanned", unscanned);
        extra.put("unscanned_total", crawl.queued());
        return new FullCrawl(pages, prior, extra, crawl.queued() == 0 ? null : checkpointJson(s, pages, prior, crawl, doneUrls));
    }

    /**
     * Denetim noktası (sürdürme için; API'ye hiç dönmez): önceki + bu taramanın sayfa satırları (ana sayfa hariç — sürdüren
     * tarama ana sayfayı yeniden ölçer), taranmış ham adresler, kuyruk (ham adres + kaynak, en çok {@value #CHECKPOINT_QUEUE_MAX}).
     * Yazılamazsa null.
     */
    private String checkpointJson(Snapshot s, List<SitePages.Page> pages, List<Map<String, Object>> prior, SiteCrawl crawl,
                                  List<String> doneUrls) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", 1);
            m.put("crawl", CRAWL_FULL);
            List<Map<String, Object>> rows = new ArrayList<>(prior.size() + pages.size());
            rows.addAll(prior);
            for (int i = 1; i < pages.size(); i++) {
                Map<String, Object> row = new LinkedHashMap<>(SitePages.row(pages.get(i)));
                row.put("from_scan", s.id());
                rows.add(row);
            }
            m.put("rows", rows);
            m.put("done_urls", doneUrls.size() > RESUME_MAX_ROWS ? doneUrls.subList(0, RESUME_MAX_ROWS) : doneUrls);
            List<Map<String, Object>> q = new ArrayList<>();
            for (SiteCrawl.Entry e : crawl.remaining(CHECKPOINT_QUEUE_MAX)) {
                Map<String, Object> qe = new LinkedHashMap<>();
                qe.put("u", e.url());
                qe.put("s", e.source());
                q.add(qe);
            }
            m.put("queue", q);
            m.put("queue_total", crawl.queued());
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            log.debug("Kaynak analizi sürdürme noktası kurulamadı: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void writeCheckpoint(long id, String json) {
        if (json == null) return;
        try {
            jdbc.update(SQL_CHECKPOINT, json, id);
        } catch (RuntimeException e) {
            log.debug("Kaynak analizi sürdürme noktası yazılamadı: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Site haritaları (2026-11-15): robots.txt'teki {@code Sitemap:} satırları, yoksa {@code /sitemap.xml}; dizinler en çok iki
     * kat açılır. Sınırlar: {@value #SITEMAP_MAX_FILES} dosya, dosya başına {@value #SITEMAP_MAX_BYTES} bayt, evre
     * {@value #SITEMAP_PHASE_MS} ms (tarama süresinin içinde), her istek tarama zaman aşımıyla. Bulunan sayfa adresleri
     * kuyruğa eklenir ({@link SiteCrawl} aynı aday kurallarını uygular). Dönen bilgi analize yazılır (adresler maskeli).
     */
    private Map<String, Object> readSitemaps(Run run, String base, SiteCrawl crawl) throws InterruptedException {
        Map<String, Object> info = new LinkedHashMap<>();
        String origin = originOf(base);
        if (origin == null) {
            info.put("found", false);
            return info;
        }
        long until = Math.min(run.deadline, clock.getAsLong() + SITEMAP_PHASE_MS);
        List<String> errors = new ArrayList<>();
        List<String> seeds = new ArrayList<>();
        byte[] robotsBody = fetchBytes(run, origin + "/robots.txt", ROBOTS_MAX_BYTES, until).body();
        if (robotsBody != null) seeds.addAll(SitemapReader.robotsSitemaps(new String(robotsBody, StandardCharsets.UTF_8), origin + "/"));
        boolean fromRobots = !seeds.isEmpty();
        java.util.ArrayDeque<Object[]> todo = new java.util.ArrayDeque<>();
        Set<String> seenFiles = new HashSet<>();
        int otherHost = 0;
        for (String u : seeds) {
            String h = hostOf(u);
            if (h == null) { addError(errors, u); continue; }   // okunamayan adres hata sayılır, "başka sunucu" değil
            if (!sameHost(u, origin)) { otherHost++; continue; }   // başka host'taki harita indirilmez
            if (seenFiles.add(u)) todo.add(new Object[]{u, 0});
        }
        // robots.txt'te harita yok ya da hepsi başka sunucuda: sitenin kendi /sitemap.xml'i de denenir
        boolean defaultSeed = false;
        if (todo.isEmpty() && seenFiles.add(origin + "/sitemap.xml")) {
            todo.add(new Object[]{origin + "/sitemap.xml", 0});
            defaultSeed = true;
        }
        int files = 0, read = 0, urls = 0, added = 0;
        boolean truncated = false;
        outer:
        while (!todo.isEmpty()) {
            if (files >= SITEMAP_MAX_FILES) { truncated = true; break; }
            if (run.halted()) return info;
            if (clock.getAsLong() >= until) { truncated = true; break; }
            run.beat(false);
            Object[] t = todo.pollFirst();
            String url = (String) t[0];
            int depth = (Integer) t[1];
            files++;
            Bytes got = fetchBytes(run, url, SITEMAP_MAX_BYTES, until);
            byte[] body = got.body();
            if (run.halted()) return info;
            if (body == null) {
                if (clock.getAsLong() >= until) { truncated = true; break; }
                // Varsayılan /sitemap.xml'in YOKLUĞU (404 / 410) hata değil; başka her başarısızlık (5xx, zaman aşımı,
                // tavanı aşan gövde) keşfi eksik bırakır
                boolean absent = got.status() == 404 || got.status() == 410;
                if (!(defaultSeed && depth == 0 && absent)) addError(errors, url);
                continue;
            }
            // Açma + ayrıştırma arka planda (pod başına tek seferde bir dosya — bellek tepe noktası sınırlı); bekleme kalp
            // atışı yazar, süre / kapanış gelince bırakılır.
            Future<SitemapReader.Parsed> pf = run.submit(() -> {
                long wait = until - clock.getAsLong();
                if (wait <= 0 || !sitemapParse.tryAcquire(wait, TimeUnit.MILLISECONDS)) return null;   // süre içinde sıra gelmedi
                try {
                    return SitemapReader.parse(SitemapReader.decode(body));
                } finally {
                    sitemapParse.release();
                }
            });
            SitemapReader.Parsed parsed;
            try {
                parsed = awaitBeating(run, pf, until);
            } catch (IllegalStateException e) {
                addError(errors, url);
                continue;
            }
            if (run.halted()) return info;
            if (parsed == null) { truncated = true; break; }
            read++;
            if (parsed.truncated()) truncated = true;
            if (parsed.index()) {
                if (depth < 2) {
                    for (String loc : parsed.locs()) {
                        if (seenFiles.size() >= SITEMAP_MAX_FILES * 4) break;
                        if (hostOf(loc) == null) { addError(errors, loc); continue; }
                        if (!sameHost(loc, origin)) { otherHost++; continue; }
                        if (seenFiles.add(loc)) todo.add(new Object[]{loc, depth + 1});
                    }
                }
            } else {
                urls += parsed.locs().size();
                List<String> locs = parsed.locs();
                for (int i = 0; i < locs.size(); i += SiteCrawl.MAX_LINKS_PER_PAGE) {
                    if (run.halted()) return info;
                    if (crawl.full()) { crawl.markDroppedAtLeast(); truncated = true; break outer; }   // kalan adresler sayılamadı
                    if (clock.getAsLong() >= until) { truncated = true; break outer; }
                    added += crawl.offer(locs.subList(i, Math.min(locs.size(), i + SiteCrawl.MAX_LINKS_PER_PAGE)),
                            SiteCrawl.SOURCE_SITEMAP);
                    run.beat(false);
                }
            }
        }
        info.put("found", read > 0);
        if (otherHost > 0) info.put("other_host", otherHost);
        info.put("default_seed", defaultSeed);
        info.put("from_robots", fromRobots);
        info.put("files", files);
        info.put("files_read", read);
        info.put("urls", urls);
        info.put("added", added);
        info.put("truncated", truncated);
        info.put("errors", errors);
        return info;
    }

    /** Adresin host'u (kodlanmamış boşluk / | / { … önce kodlanır); mutlak http(s) değilse ya da okunamazsa null. */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            java.net.URI u = java.net.URI.create(PageFetchCore.normalizeUrl(url.trim()));
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            return u.getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Aynı host mu (şema farkı — http ↔ https — ve bağlantı noktası serbest; alt alan adı BAŞKA host sayılır). */
    static boolean sameHost(String url, String origin) {
        String a = hostOf(url);
        String b = hostOf(origin);
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static void addError(List<String> errors, String url) {
        if (errors.size() < SITEMAP_MAX_ERRORS) errors.add(DeepUrls.mask(url));
    }

    /** {@code https://host:port} (bağlantı noktası varsayılansa yazılmaz); geçersiz → null. */
    static String originOf(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            if (u.getScheme() == null || u.getHost() == null) return null;
            return u.getScheme() + "://" + u.getRawAuthority().replaceFirst("^.*@", "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Ham baytlar (robots.txt, site haritası): SSRF her adımda, tarama zaman aşımı ve {@code until} son tarihi; 200 değilse,
     * gövde tavanı aşarsa ya da süre dolarsa null. Bayt bütçesine sayılır.
     */
    /** Ham indirme sonucu: gövde (200 ve tavan içinde; yoksa null) ve HTTP durumu (-1 = yanıt yok / zaman aşımı). */
    record Bytes(byte[] body, int status) {}

    private Bytes fetchBytes(Run run, String url, int max, long until) throws InterruptedException {
        boolean via = run.via(url);
        // countBytes=false: tavandan sonra gövde okunmaz (sayım için boşa indirme yok)
        PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(run.c.requestTimeoutMs(), run.ua, null, true, false, via);
        Future<PageFetchCore.OriginalFetch> f = run.submit(() -> core.fetchOriginal(url, o,
                PageFetchCore.ScanTuning.original(run.clients, max + 1)));
        PageFetchCore.OriginalFetch of;
        try {
            of = awaitBeating(run, f, until);
        } catch (IllegalStateException e) {
            return new Bytes(null, -1);   // çekim hatası (bağlantı / zaman aşımı) — site haritası yalnız eksik kalır
        }
        if (of == null || of.fetch() == null) return new Bytes(null, -1);
        PageFetchCore.Fetch fe = of.fetch();
        // Bütçeye yalnız gerçekten okunabilen kadar (tavanı aşan bildirilen Content-Length okunmadan sayılmaz)
        run.spent.addAndGet(Math.max(0, Math.min(fe.bytes(), (long) max + 1)));
        if (fe.status() != 200 || fe.body() == null || fe.body().length > max) return new Bytes(null, fe.status());
        return new Bytes(fe.body(), fe.status());
    }

    /** Ölçülen sayfa yükü nesnesini önbelleğe alır (belge / bağlantı yoklaması, indirilmeyen ve önbellekten gelen hariç). */
    private static void cache(SiteRun site, Planned p) {
        if (p.item == null || p.cached != null || !p.c.pageLoad() || p.item.skipped()) return;
        List<PageResourceDiscovery.Candidate> refs = p.siteRefs == null ? List.of() : p.siteRefs;
        boolean cut = p.siteRefsFull > refs.size();
        site.cache.putIfAbsent(PageResourceDiscovery.key(p.c.url()), new SiteCached(p.item.measurementCopy(), refs,
                p.siteFaces == null ? List.of() : p.siteFaces, p.siteCookies == null ? List.of() : p.siteCookies,
                p.siteSampleMissing, cut));
    }

    /** Tek iç sayfa: HTML + sayfa yükü nesneleri (ortaklar önbellekten) + analiz. Durduruldu → null. */
    private SitePages.Page scanExtraPage(Run run, String url, Predicate<String> exclude) throws InterruptedException {
        boolean objectsWere = run.objectsCapped;
        run.objectsCapped = false;   // sayfanın kendi "nesne tavanı" bayrağı (ana sayfadan miras kalmasın)
        try {
            return scanExtraPageInner(run, url, exclude);
        } finally {
            run.objectsCapped = objectsWere || run.objectsCapped;
        }
    }

    private SitePages.Page scanExtraPageInner(Run run, String url, Predicate<String> exclude) throws InterruptedException {
        run.pageLinks = List.of();
        resetPageFacts(run);
        run.total.incrementAndGet();
        boolean via = run.via(url);
        PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(run.c.requestTimeoutMs(), run.ua, null, true, true, via);
        Future<PageFetchCore.MetaFetch> f = run.submit(() -> core.fetchWithMeta(url, "GET", o, 0, run.htmlTuning));
        PageFetchCore.MetaFetch mf = awaitBeating(run, f, run.deadline);
        if (run.halted()) return null;
        if (mf == null) {
            run.timeCapped = true;
            return SitePages.Page.skipped(url, "NOT_SCANNED:TIME");
        }
        run.done.incrementAndGet();
        PageFetchCore.Fetch fe = mf.fetch();
        run.spent.addAndGet(Math.max(0, fe.bytes()));
        String err = pageError(fe, mf.meta(), run.rootHost);
        if (err != null) return SitePages.Page.failed(url, err);
        ScanItem html = item(new PageResourceDiscovery.Candidate(url, PageResourceDiscovery.HTML, true, false, false), mf, null,
                false, via ? "proxy" : "direct", null);
        html.seq = 0;
        String base = mf.meta() != null && mf.meta().finalUrl() != null ? mf.meta().finalUrl() : url;
        collectCookies(run, mf.meta());
        org.jsoup.nodes.Document doc = PageResourceDiscovery.parse(fe.body(), base);
        HtmlFacts facts = doc == null ? null : HtmlFacts.extract(doc, fe.body().length);
        PageResourceDiscovery.Found found = doc == null ? PageResourceDiscovery.discover(fe.body(), base, exclude)
                : PageResourceDiscovery.discoverParsed(doc, base, exclude);
        doc = null;   // belge bırakılır (bellek)
        if (run.site.full) {
            // Tüm site: bu sayfanın bağlantıları kuyruğu besler (en çok SiteCrawl.MAX_LINKS_PER_PAGE değerlendirilir)
            List<String> links = new ArrayList<>(Math.min(found.links().size(), SiteCrawl.MAX_LINKS_PER_PAGE));
            for (PageResourceDiscovery.Candidate cand : found.links()) {
                if (links.size() >= SiteCrawl.MAX_LINKS_PER_PAGE) break;
                links.add(cand.url());
            }
            run.pageLinks = links;
        }
        String inlineBase = found.baseUrl() != null && !found.baseUrl().isBlank() ? found.baseUrl() : base;
        for (String inline : found.inlineCss()) cssFacts(run, inline, inlineBase, base);

        Set<String> known = new HashSet<>();
        known.add(PageResourceDiscovery.key(base));
        known.add(PageResourceDiscovery.key(url));
        List<Planned> nodes = new ArrayList<>();
        List<Planned> measured = new ArrayList<>();
        AtomicInteger order = new AtomicInteger(1);
        List<Planned> phase = new ArrayList<>();
        for (PageResourceDiscovery.Candidate cand : found.pageLoad()) sitePlan(run, nodes, phase, known, order, cand, null, 0, null);
        for (String inline : found.inlineCss()) {
            for (PageResourceDiscovery.Candidate cand : PageResourceDiscovery.cssRefs(inline, inlineBase)) {
                sitePlan(run, nodes, phase, known, order, cand, null, 0, exclude);
            }
        }
        for (int depth = 0; !phase.isEmpty() && depth <= CSS_DEPTH; depth++) {
            List<Planned> toMeasure = new ArrayList<>();
            for (Planned p : phase) if (p.cached == null) toMeasure.add(p);
            measure(run, toMeasure);
            if (run.halted()) return null;
            measured.addAll(toMeasure);
            List<Planned> next = new ArrayList<>();
            for (Planned p : phase) {
                List<PageResourceDiscovery.Candidate> refs;
                if (p.cached != null) {
                    refs = p.cached.refs();
                } else {
                    // Bu sayfada ölçülen stil: sayfanın KENDİ oda sınırlı listesi (kesilirse ölçümde zaten "nesne tavanı"
                    // işaretlenir); önbelleğe giden liste bellek tavanıyla kesilebilir, onu yalnız sonraki sayfalar kullanır (D-6)
                    refs = p.cssRefs;
                    p.cssRefs = null;
                    if (p.item == null) continue;
                    cache(run.site, p);
                }
                if (refs == null || refs.isEmpty() || p.depth >= CSS_DEPTH) continue;
                for (PageResourceDiscovery.Candidate cand : refs) sitePlan(run, nodes, next, known, order, cand, p, p.depth + 1, exclude);
            }
            phase = next;
        }
        checkSourceMaps(run, measured);
        if (run.halted()) return null;

        List<ScanItem> items = new ArrayList<>();
        items.add(html);
        Map<Integer, Integer> pos = new HashMap<>();
        nodes.sort(Comparator.comparingInt(p -> p.order));
        for (Planned p : nodes) {
            if (p.item == null) continue;
            pos.put(p.order, items.size());
            p.item.seq = items.size();
            items.add(p.item);
        }
        for (Planned p : nodes) {
            if (p.item == null) continue;
            p.item.parent = p.parent == null ? 0 : pos.getOrDefault(p.parent.order, 0);
        }
        PageResourceAnalyzer.Limits lim = new PageResourceAnalyzer.Limits(run.objectsCapped, run.c.maxObjects(),
                run.timeCapped, run.c.maxSeconds(), run.bytesCapped, run.c.maxTotalMb(), found.excluded() + run.excludedExtra);
        PageFetchCore.ScanMeta sm = mf.meta() == null ? null : mf.meta().scan();
        DeepContext deep = new DeepContext(facts, sm == null ? null : sm.pageHeaders(), base,
                new ArrayList<>(run.cookies), new ArrayList<>(run.fontFaces), Set.copyOf(run.cssReferenced),
                Map.copyOf(run.hostTimings), 0, Map.copyOf(run.sourceMaps), run.sourceMapsNotChecked,
                run.samplesUnavailable.get());
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, lim, deep);
        return donePage(url, items, r, lim.capped(), run.site::pooled);
    }

    /**
     * İç sayfanın adayını plana alır: sayfada tekil, hariç tutma (stilden bulunanlar), önbellekte varsa ölçülmeden kopyası
     * (katkısı bağlama eklenir), yoksa site geneli nesne tavanı içinde ölçüme.
     */
    private void sitePlan(Run run, List<Planned> nodes, List<Planned> phase, Set<String> known, AtomicInteger order,
                          PageResourceDiscovery.Candidate cand, Planned parent, int depth, Predicate<String> exclude) {
        String k = PageResourceDiscovery.key(cand.url());
        if (!known.add(k)) return;
        if (exclude != null) {
            boolean ex;
            try { ex = exclude.test(cand.url()); } catch (RuntimeException e) { ex = false; }
            if (ex) { run.excludedExtra++; return; }
        }
        if (run.site.full && nodes.size() + 1 >= run.c.maxObjects()) {   // tüm site: sayfa başına nesne tavanı (+1: HTML)
            run.objectsCapped = true;
            return;
        }
        SiteCached hit = cand.notLoaded() ? null : run.site.cache.get(k);
        Planned p = new Planned(order.getAndIncrement(), cand, parent, depth);
        if (hit != null) {
            ScanItem it = hit.item().measurementCopy();
            it.lazy = cand.lazy();
            it.fromCss = cand.fromCss();
            // Bildirilen boyut bu sayfanın öğesine aittir (önbellekteki kopya ilk sayfanınkini taşır)
            boolean image = PageResourceDiscovery.IMAGE.equals(it.category);
            it.declaredWidth = image ? cand.declaredWidth() : null;
            it.declaredHeight = image ? cand.declaredHeight() : null;
            it.foundIn = parent == null ? FOUND_IN_PAGE : parent.c.url();
            p.item = it;
            p.cached = hit;
            replay(run, hit);
        } else {
            if (run.site.objects.get() + 1 >= run.site.maxObjects) {
                run.objectsCapped = true;
                return;
            }
            run.site.objects.incrementAndGet();
            run.total.incrementAndGet();
        }
        nodes.add(p);
        phase.add(p);
    }

    /** Önbellekten gelen nesnenin derin olgulara katkısı bu sayfanın bağlamına eklenir. */
    private static void replay(Run run, SiteCached hit) {
        addCookies(run, hit.cookies());
        addCssFacts(run, hit.refs(), hit.faces());
        if (hit.sampleMissing()) run.samplesUnavailable.incrementAndGet();
        if (hit.refsCut()) run.objectsCapped = true;   // saklanan başvuru listesi kesikti — ölçüm eksik olabilir
    }

    /** Sayfaya özgü derin olgular her iç sayfada sıfırlanır (host ölçümleri ve kaynak haritası sonuçları adres anahtarlı, kalır). */
    private static void resetPageFacts(Run run) {
        run.cookies.clear();
        run.cookieCount.set(0);
        run.fontFaces.clear();
        run.fontFaceCount.set(0);
        run.cssReferenced.clear();
        run.samplesUnavailable.set(0);
        run.sourceMapsNotChecked = 0;
        run.hostsNotMeasured = 0;
        run.cssRefsRetained.set(0);
        run.excludedExtra = 0;
    }

    /**
     * İç sayfanın açılamama kodu (null = analiz edilebilir): güvenlik engeli, aktarım hatası, HTTP ≥ 400, boş gövde, HTML
     * olmayan içerik, başka host'a yönlendirme (ör. oturum açma sayfası — o site analiz edilmez).
     */
    static String pageError(PageFetchCore.Fetch f, PageFetchCore.Meta meta, String rootHost) {
        if (f.blocked()) return "PAGE_BLOCKED";
        if (f.status() == 0) return "PAGE_FAILED:" + (f.failure() != null ? f.failure().code() : "TRANSPORT_ERROR");
        if (f.status() >= 400) return "PAGE_HTTP:" + f.status();
        if (f.body() == null || f.body().length == 0) return "PAGE_EMPTY";
        String ct = meta == null ? null : contentType(meta.contentType());
        if (ct != null && !ct.contains("html")) return "PAGE_NOT_HTML";
        String fin = meta == null ? null : meta.finalUrl();
        if (fin != null && rootHost != null) {
            String h = PageFetchCore.hostOf(fin);
            if (h != null && !h.equalsIgnoreCase(rootHost)) return "PAGE_OFFSITE";
        }
        return null;
    }

    /** Tamamlanan sayfanın satırı: analizin özet değerleri + sayfa yükü nesneleri (ortak / tekil bayt için). */
    static SitePages.Page donePage(String url, List<ScanItem> items, PageResourceAnalyzer.Result r, boolean capped) {
        return donePage(url, items, r, capped, k -> k);
    }

    static SitePages.Page donePage(String url, List<ScanItem> items, PageResourceAnalyzer.Result r, boolean capped,
                                   java.util.function.UnaryOperator<String> pool) {
        Map<String, SitePages.Resource> res = new LinkedHashMap<>();
        for (int i = 1; i < items.size(); i++) {
            ScanItem it = items.get(i);
            if (!it.pageLoad || it.skipped() || it.url == null) continue;
            res.putIfAbsent(pool.apply(PageResourceDiscovery.key(it.url)), new SitePages.Resource(it.url, it.category, Math.max(0, it.bytes)));
        }
        long htmlBytes = items.isEmpty() ? 0 : Math.max(0, items.get(0).bytes);
        return new SitePages.Page(url, SitePages.DONE, null, r.grade(), r.gradeLetter(), r.loadMs(), r.pageBytes(),
                r.requestCount(), r.securityScore(), r.seoScore(), r.a11yScore(),
                SitePages.topRecs(r.analysis() == null ? null : r.analysis().get("recommendations")), htmlBytes, res, capped);
    }

    /** Çok sayfalı bitiş: ana sayfanın satır değerleri + {@code analysis.site} + {@code scope='site'}, {@code pages_count}. */
    private void finishSite(Snapshot s, Run run, List<ScanItem> items, PageResourceAnalyzer.Result r, ScanItem html,
                            List<SitePages.Page> pages) throws Exception {
        finishSite(s, run, items, r, html, pages, null, null, null);
    }

    private void finishSite(Snapshot s, Run run, List<ScanItem> items, PageResourceAnalyzer.Result r, ScanItem html,
                            List<SitePages.Page> pages, List<Map<String, Object>> prior, Map<String, Object> extra,
                            String checkpoint) throws Exception {
        Map<String, Object> analysisMap = new LinkedHashMap<>(r.analysis());
        analysisMap.put("site", prior == null && extra == null ? SitePages.section(pages) : SitePages.section(pages, prior, extra));
        int priorScanned = 0;
        if (prior != null) for (Map<String, Object> row : prior) if (SitePages.DONE.equals(row.get("status"))) priorScanned++;
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (ScanItem it : items) rows.add(it.toDeepMap());
        String analysis = JSON.writeValueAsString(analysisMap);
        String itemsJson = JSON.writeValueAsString(rows);
        boolean capped = r.capped();   // O-3: yalnız ana sayfanın kırpılması (site eksikliği analysis.site.incomplete)
        long now = clock.getAsLong();
        int n = jdbc.update(SQL_DONE_SITE, iso(now), now, run.done.get(), run.total.get(), r.grade(), r.gradeLetter(), r.loadMs(),
                r.htmlTtfbMs(), r.htmlMs(), r.pageBytes(), r.requestCount(), r.imageBytes(), r.imageCount(), r.documentCount(),
                r.documentBytes(), r.linkCount(), r.linkBytes(), r.failedCount(), capped, analysis, itemsJson,
                html.redirects, html.redirectMs, r.jsBytes(), r.cssBytes(), r.fontBytes(), r.securityScore(), r.seoScore(),
                r.a11yScore(), SitePages.scanned(pages) + priorScanned, s.id());
        if (n == 0) {
            log.info("Sayfa kaynak analizi {} (çok sayfalı) bitti ama satır artık çalışıyor değil (yarıda kaldı / iptal edildi) — "
                    + "sonuç yazılmadı", s.id());
            return;
        }
        if (checkpoint != null || (s.site() != null && s.site().full())) {
            try {
                // Tüm site: son nokta (kuyruk boşsa NULL — çalışırken yazılan ara nokta silinir)
                jdbc.update(SQL_CHECKPOINT_FINAL, checkpoint, s.id());
            } catch (RuntimeException e) {
                log.warn("Kaynak analizi {} sürdürme noktası yazılamadı — bu analiz sürdürülemez: {}", s.id(),
                        e.getClass().getSimpleName());
            }
        }
        pruneItems(s.monitorId());
    }

    // ── Bitiş ───────────────────────────────────────────────────────────────────────────────

    private void finishDone(Snapshot s, Run run, List<ScanItem> items, PageResourceAnalyzer.Result r, ScanItem html)
            throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (ScanItem it : items) rows.add(it.toDeepMap());
        String analysis = JSON.writeValueAsString(r.analysis());
        String itemsJson = JSON.writeValueAsString(rows);
        long now = clock.getAsLong();
        int n = jdbc.update(SQL_DONE, iso(now), now, run.done.get(), run.total.get(), r.grade(), r.gradeLetter(), r.loadMs(),
                r.htmlTtfbMs(), r.htmlMs(), r.pageBytes(), r.requestCount(), r.imageBytes(), r.imageCount(), r.documentCount(),
                r.documentBytes(), r.linkCount(), r.linkBytes(), r.failedCount(), r.capped(), analysis, itemsJson,
                html.redirects, html.redirectMs, r.jsBytes(), r.cssBytes(), r.fontBytes(), r.securityScore(), r.seoScore(),
                r.a11yScore(), s.id());
        if (n == 0) {
            log.info("Sayfa kaynak analizi {} bitti ama satır artık çalışıyor değil (yarıda kaldı / iptal edildi) — sonuç yazılmadı",
                    s.id());
            return;
        }
        pruneItems(s.monitorId());
    }

    /** İzlemenin son {@value #DETAIL_RUNS} çalıştırması dışındakilerin nesne listesini bırakır (özet kalır). */
    void pruneItems(long monitorId) {
        try {
            List<Long> ids = jdbc.queryForList(SQL_KEPT_IDS, Long.class, monitorId);
            for (int i = DETAIL_RUNS; i < ids.size(); i++) jdbc.update(SQL_DROP_ITEMS, ids.get(i));
            List<Long> cks = jdbc.queryForList(SQL_CHECKPOINT_IDS, Long.class, monitorId);
            for (int i = CHECKPOINT_KEEP; i < cks.size(); i++) jdbc.update(SQL_DROP_CHECKPOINT, cks.get(i));
        } catch (RuntimeException e) {
            log.warn("Eski kaynak analizi ayrıntıları bırakılamadı (izleme {}): {}", monitorId, e.getClass().getSimpleName());
        }
    }

    private void end(long id, String status, String code, ScanItem html) {
        long now = clock.getAsLong();
        try {
            jdbc.update(SQL_END, status, iso(now), now, code, html == null ? null : html.ttfbMs,
                    html == null ? null : html.durationMs, html == null ? null : html.redirects,
                    html == null ? null : html.redirectMs, id);
        } catch (RuntimeException e) {
            log.warn("Sayfa kaynak analizi {} durumu yazılamadı ({}): {} — eskiyince yarıda kaldı sayılır", id, status,
                    e.getClass().getSimpleName());
        }
    }

    // ── Kapanış ─────────────────────────────────────────────────────────────────────────────

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent e) {
        stopping = true;   // yalnız bayrak: çalışan taramalar ölçümü bırakıp kendi satırını yazar
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
        for (Run r : active.values()) r.clients.shutdownNow();   // uçuştaki istekler hemen düşsün
        ExecutorService e = executor;
        if (e == null) return;
        e.shutdownNow();
        try {
            e.awaitTermination(ShutdownDeadline.waitMs(1_500), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────

    static String iso(long ms) {
        return ISO.format(Instant.ofEpochMilli(ms));
    }

    private static int intOr(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? 0 : v;
    }

    private static Integer intObj(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long longObj(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Object> parseList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
