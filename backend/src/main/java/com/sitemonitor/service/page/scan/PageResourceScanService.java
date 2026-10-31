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
import java.time.ZonedDateTime;
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

    public static final String RUNNING = "RUNNING", DONE = "DONE", FAILED = "FAILED", INTERRUPTED = "INTERRUPTED";
    /** {@code found_in} değeri: nesne HTML'de (ya da satır içi stilde) bulundu. */
    public static final String FOUND_IN_PAGE = "page";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SUMMARY_COLS = "id, monitor_id, url, status, via, started_at, finished_at, heartbeat_ms, requested_by, "
            + "progress_done, progress_total, grade, grade_letter, load_ms, html_ttfb_ms, html_ms, page_bytes, request_count, "
            + "image_bytes, image_count, document_count, document_bytes, link_count, link_bytes, failed_count, capped, error, "
            + "items_kept, owner_pod, redirect_count, redirect_ms, algorithm_version, cancel_requested";

    static final String SQL_INSERT = "INSERT INTO page_resource_scans (monitor_id, team_id, url, status, via, started_at, "
            + "started_ms, heartbeat_ms, owner_pod, requested_by, progress_done, progress_total, capped, items_kept, "
            + "algorithm_version) VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, 0, 0, FALSE, FALSE, ?)";
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
            + "redirect_count = ?, redirect_ms = ? WHERE id = ? AND status = 'RUNNING'";
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
    static final String SQL_DELETE_MONITOR = "DELETE FROM page_resource_scans WHERE monitor_id = ?";

    private final JdbcTemplate jdbc;
    private final PageFetchCore core;
    private final PageCheckerService pageChecker;
    private final PublicSuffixService publicSuffix;
    private final AppSettingsService settings;

    @Autowired(required = false)
    private ProxyPolicyService proxyPolicy;

    private final Semaphore podSlots = new Semaphore(MAX_PER_POD);
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
                    id = insert(m, viaProxy, now, requestedBy);
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
                Snapshot snap = new Snapshot(id, m.getId(), m.getUrl(), m.getExcludePatterns(), viaProxy, m.getUseProxy());
                Config c = config();
                try {
                    executor().execute(() -> {
                        try {
                            run(snap, c);
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

    private Long insert(PageMonitor m, boolean viaProxy, long now, String requestedBy) {
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
            o.put("analysis", parseMap(rs.getString("analysis_json")));
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
        m.put("progress", progress);
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
        return m;
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
    record Snapshot(long id, long monitorId, String url, String excludes, boolean viaProxy, String proxyMode) {
        /** Geriye uyum: tercih yok → ana sayfanın kararı her nesneye (ON/OFF eşdeğeri). */
        Snapshot(long id, long monitorId, String url, String excludes, boolean viaProxy) {
            this(id, monitorId, url, excludes, viaProxy, viaProxy ? ProxyPolicyService.ON : ProxyPolicyService.OFF);
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
        final PageFetchCore.ScanTuning htmlTuning, cssTuning, countTuning, probeTuning, imageTuning;
        /** Nesne başına vekil kararı host önbelleği (AUTO kipinde envanter okuması host başına bir kez). */
        final Map<String, Boolean> viaByHost = new ConcurrentHashMap<>();

        Run(Snapshot s, Config c, long start, PageFetchCore.ScanClients clients) {
            this.s = s;
            this.c = c;
            this.deadline = start + c.maxSeconds() * 1000L;
            this.budget = c.maxTotalMb() * PageResourceAnalyzer.MB;
            this.clients = clients;
            this.htmlTuning = new PageFetchCore.ScanTuning(clients, PageFetchCore.ACCEPT_ENCODING,
                    PageFetchCore.MAX_BODY_BYTES, HTML_MAX_DECODED);
            this.cssTuning = new PageFetchCore.ScanTuning(clients, PageFetchCore.ACCEPT_ENCODING,
                    PageFetchCore.MAX_BODY_BYTES, PageResourceDiscovery.MAX_CSS_CHARS);
            this.countTuning = new PageFetchCore.ScanTuning(clients, PageFetchCore.ACCEPT_ENCODING_COUNT,
                    PageFetchCore.MAX_BODY_BYTES, PageResourceDiscovery.MAX_CSS_CHARS);
            this.probeTuning = countTuning;
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
            Predicate<String> exclude = pageChecker.excludePredicate(s.excludes());
            PageResourceDiscovery.Found found = PageResourceDiscovery.discover(f.body(), base, exclude);
            // Satır içi stiller belgenin <base href>'iyle çözülür (HTML öğeleri jsoup'ta zaten öyle — inceleme H12)
            String inlineBase = found.baseUrl() != null && !found.baseUrl().isBlank() ? found.baseUrl() : base;

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
            PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, lim);
            finishDone(s, run, items, r, html);
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
                : getWithRetry(run, p.c.url(), viaProxy, css, image);
        if (mf == null || run.halted()) return;   // kesilen (iptal / süre dolumu) iş sonuç yazmaz
        ScanItem it = item(p.c, mf, run.rootHost, probe, viaProxy ? "proxy" : "direct", foundIn);
        if (css && p.depth < CSS_DEPTH && mf.fetch().body() != null && it.ok()) {
            // Başvurular HEMEN çıkarılır, gövde atılır; göreli adresler tarayıcı gibi yönlendirme SONRASI adrese göre çözülür.
            String base = mf.meta() != null && mf.meta().finalUrl() != null ? mf.meta().finalUrl() : p.c.url();
            List<PageResourceDiscovery.Candidate> refs =
                    PageResourceDiscovery.cssRefs(decodeText(mf.fetch().body(), mf.meta().contentType()), base);
            // Bekleyen (henüz planlanmamış) başvuru sayısı nesne tavanıyla sınırlı — fazlası zaten planlanamaz.
            int room = run.c.maxObjects() - run.cssRefsRetained.getAndAdd(refs.size());
            if (room < refs.size()) {
                run.objectsCapped = true;
                refs = room <= 0 ? List.of() : List.copyOf(refs.subList(0, room));
            }
            p.cssRefs = refs;
        }
        p.item = it;
        run.done.incrementAndGet();
    }

    /** Sayfa yükü nesnesi: GET + bayt sayımı; tek seferlik hata (zaman aşımı / engel değil) bir kez yeniden denenir. */
    private PageFetchCore.MetaFetch getWithRetry(Run run, String url, boolean viaProxy, boolean css, boolean image) {
        PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(run.c.requestTimeoutMs(), run.ua, null, css, true, viaProxy);
        PageFetchCore.ScanTuning t = css ? run.cssTuning : run.countTuning;
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
        it.category = refine(c, it.contentType);
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
        it.cacheTtlS = cacheTtl(meta.cacheControl(), meta.expires(), meta.date(), clock.getAsLong());
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
        return it;
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
    static String refine(PageResourceDiscovery.Candidate c, String ct) {
        String cat = c.category();
        if (ct == null) return cat;
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
     * Önbellek süresi (sn): {@code no-store} / {@code no-cache} → -1, {@code max-age=N} → N, yoksa {@code Expires} −
     * {@code Date} (yoksa şimdi), hiçbiri yoksa null. Bozuk değer null.
     */
    static Long cacheTtl(String cacheControl, String expires, String date, long nowMs) {
        if (cacheControl != null) {
            boolean noCache = false;
            Long maxAge = null;
            for (String part : cacheControl.toLowerCase(Locale.ROOT).split(",")) {
                String d = part.trim();
                if (d.equals("no-store") || d.equals("no-cache")) noCache = true;
                else if (d.startsWith("max-age=")) {
                    try {
                        maxAge = Math.max(0L, Long.parseLong(d.substring(8).replace("\"", "").trim()));
                    } catch (NumberFormatException ignore) { /* bozuk değer */ }
                }
            }
            if (noCache) return -1L;
            if (maxAge != null) return maxAge;
        }
        if (expires != null && !expires.isBlank()) {
            try {
                long exp = ZonedDateTime.parse(expires.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                long ref = nowMs;
                if (date != null && !date.isBlank()) {
                    try {
                        ref = ZonedDateTime.parse(date.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                    } catch (RuntimeException ignore) { /* Date bozuk → şimdi */ }
                }
                return Math.max(0L, (exp - ref) / 1000L);
            } catch (RuntimeException e) {
                return 0L;   // "Expires: 0" / "-1" — süresi geçmiş sayılır
            }
        }
        return null;
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

    // ── Bitiş ───────────────────────────────────────────────────────────────────────────────

    private void finishDone(Snapshot s, Run run, List<ScanItem> items, PageResourceAnalyzer.Result r, ScanItem html)
            throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (ScanItem it : items) rows.add(it.toMap());
        String analysis = JSON.writeValueAsString(r.analysis());
        String itemsJson = JSON.writeValueAsString(rows);
        long now = clock.getAsLong();
        int n = jdbc.update(SQL_DONE, iso(now), now, run.done.get(), run.total.get(), r.grade(), r.gradeLetter(), r.loadMs(),
                r.htmlTtfbMs(), r.htmlMs(), r.pageBytes(), r.requestCount(), r.imageBytes(), r.imageCount(), r.documentCount(),
                r.documentBytes(), r.linkCount(), r.linkBytes(), r.failedCount(), r.capped(), analysis, itemsJson,
                html.redirects, html.redirectMs, s.id());
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
