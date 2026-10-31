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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Sayfa Bütünlüğü "Kaynak analizi" (2026-10-31, kullanıcı isteği) — YALNIZ kullanıcı isteyince çalışan nesne taraması.
 *
 * <p>Akış: izlemenin sayfası sunucudan indirilir ({@link PageFetchCore}, izlemenin vekil kararıyla), nesneler keşfedilir
 * ({@link PageResourceDiscovery}: sayfanın yükledikleri → stilin {@code url()} / {@code @import} ettikleri → bağlantılı
 * belgeler → diğer bağlantılar), her biri GET ile indirilip boyutu ve süresi ölçülür (sınırlı eşzamanlılık, bayt / süre /
 * nesne tavanları), sonra {@link PageResourceAnalyzer} not, tahmini yükleme süresi, öneriler ve görsel etkisini çıkarır.
 *
 * <p><b>Sınırlar:</b> pod başına aynı anda {@value #MAX_PER_POD} tarama, izleme başına tek çalışan tarama (iki pod yarışırsa
 * küçük kimlik kazanır), kişi başına dakikada {@value #STARTS_PER_MINUTE} başlatma (yük sınırı, pod belleği). Tarama izlemeye
 * DOKUNMAZ: kontrol kaydı, alarm, bildirim yok.
 *
 * <p><b>Yarıda kalma:</b> çalışan tarama her saniye {@code heartbeat_ms} yazar; {@value #STALE_MS} ms'den eski RUNNING
 * satırı okunurken INTERRUPTED'e çekilir (pod öldü). Kapanışta ({@link ContextClosedEvent}) çalışan taramalar ölçümü bırakır
 * ve kendi satırlarını INTERRUPTED yazar.
 *
 * <p><b>Saklama:</b> özet 1 yıl ({@code page-resource-scans} politikası), nesne ayrıntısı izleme başına son
 * {@value #DETAIL_RUNS} çalıştırma (her bitişte eskilerin {@code items_json}'u NULL'a çekilir).
 */
@Slf4j
@Service
public class PageResourceScanService {

    public static final int MAX_PER_POD = 2;
    public static final int STARTS_PER_MINUTE = 3;
    public static final int DETAIL_RUNS = 20;
    public static final long STALE_MS = 45_000L;
    static final long HEARTBEAT_MS = 1_000L;
    static final int HISTORY_DAYS = 365;
    static final int LIST_MAX = 200;
    /** İzin verilen en uzun stil zinciri ({@code @import} içinde {@code @import}). */
    static final int CSS_DEPTH = 2;

    public static final String KEY_MAX_SECONDS = "site.monitor.page.resource-scan.max-seconds";
    public static final String KEY_CONCURRENCY = "site.monitor.page.resource-scan.concurrency";
    public static final String KEY_MAX_OBJECTS = "site.monitor.page.resource-scan.max-objects";
    public static final String KEY_MAX_TOTAL_MB = "site.monitor.page.resource-scan.max-total-mb";
    public static final String KEY_REQUEST_TIMEOUT_MS = "site.monitor.page.resource-scan.request-timeout-ms";

    public static final String RUNNING = "RUNNING", DONE = "DONE", FAILED = "FAILED", INTERRUPTED = "INTERRUPTED";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SUMMARY_COLS = "id, monitor_id, url, status, via, started_at, finished_at, heartbeat_ms, requested_by, "
            + "progress_done, progress_total, grade, grade_letter, load_ms, html_ttfb_ms, html_ms, page_bytes, request_count, "
            + "image_bytes, image_count, document_count, document_bytes, link_count, link_bytes, failed_count, capped, error, "
            + "items_kept";

    static final String SQL_INSERT = "INSERT INTO page_resource_scans (monitor_id, team_id, url, status, via, started_at, "
            + "started_ms, heartbeat_ms, owner_pod, requested_by, progress_done, progress_total, capped, items_kept) "
            + "VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, 0, 0, FALSE, FALSE)";
    static final String SQL_RUNNING = "SELECT " + SUMMARY_COLS + " FROM page_resource_scans WHERE monitor_id = ? "
            + "AND status = 'RUNNING' ORDER BY id";
    static final String SQL_SETTLE_STALE = "UPDATE page_resource_scans SET status = 'INTERRUPTED', finished_at = ?, "
            + "error = 'STALE' WHERE monitor_id = ? AND status = 'RUNNING' AND heartbeat_ms < ?";
    static final String SQL_PROGRESS = "UPDATE page_resource_scans SET progress_done = ?, progress_total = ?, heartbeat_ms = ? "
            + "WHERE id = ? AND status = 'RUNNING'";
    static final String SQL_DONE = "UPDATE page_resource_scans SET status = 'DONE', finished_at = ?, heartbeat_ms = ?, "
            + "progress_done = ?, progress_total = ?, grade = ?, grade_letter = ?, load_ms = ?, html_ttfb_ms = ?, html_ms = ?, "
            + "page_bytes = ?, request_count = ?, image_bytes = ?, image_count = ?, document_count = ?, document_bytes = ?, "
            + "link_count = ?, link_bytes = ?, failed_count = ?, capped = ?, analysis_json = ?, items_json = ?, items_kept = TRUE "
            + "WHERE id = ? AND status = 'RUNNING'";
    static final String SQL_END = "UPDATE page_resource_scans SET status = ?, finished_at = ?, heartbeat_ms = ?, error = ?, "
            + "html_ttfb_ms = ?, html_ms = ? WHERE id = ? AND status = 'RUNNING'";
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
    private final Object startLock = new Object();
    private final ConcurrentHashMap<String, Deque<Long>> rate = new ConcurrentHashMap<>();
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
                boolean viaProxy = proxyPolicy != null && proxyPolicy.decide(m.getUrl(), m.getUseProxy()).viaProxy();
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
                Snapshot snap = new Snapshot(id, m.getId(), m.getUrl(), m.getExcludePatterns(), viaProxy);
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

    // ── Okuma ───────────────────────────────────────────────────────────────────────────────

    /** Geçmiş (özetler, en yeni önce, 1 yıl içi) + çalışan tarama. */
    public Map<String, Object> list(long monitorId, int limit) {
        long now = clock.getAsLong();
        settleStale(monitorId, now);
        int lim = Math.max(1, Math.min(LIST_MAX, limit));
        String since = iso(now - HISTORY_DAYS * 86_400_000L);
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
     * Eskime eşiği: en az {@value #STALE_MS} ms; ana sayfanın tek çekimi (başlık + gövde süresi = 2 × istek zaman aşımı)
     * kalp atışsız geçtiği için ondan kısa olamaz — yoksa uzun zaman aşımında canlı tarama "yarıda kaldı" sayılırdı.
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
        m.put("error_code", code == null ? null : code.split(":", 2)[0]);
        // Ulaşılamama nedeni (CheckFailureReason adı) — arayüz kendi diline çevirir; ham kod metne konmaz
        m.put("error_reason", code != null && code.startsWith("PAGE_FAILED:") ? code.substring("PAGE_FAILED:".length()) : null);
        m.put("error", errorText(code));
        m.put("items_kept", rs.getBoolean("items_kept"));
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
            default -> Msg.t("Analiz beklenmeyen bir hatayla durdu. Yeniden deneyin; sorun sürerse yöneticinize bildirin.",
                    "The analysis stopped with an unexpected error. Try again; if it keeps happening, tell your administrator.");
        };
    }

    // ── Ölçüm ───────────────────────────────────────────────────────────────────────────────

    /** Arka plan işinin ihtiyacı (izlemenin başlatma anındaki değerleri). */
    record Snapshot(long id, long monitorId, String url, String excludes, boolean viaProxy) {}

    /** Ölçülecek aday. */
    private static final class Planned {
        final int order;
        final PageResourceDiscovery.Candidate c;
        final Planned parent;
        final int depth;
        // Ölçüm görevleri (sanal iş parçacıkları) yazar, tarama iş parçacığı okur. Stil gövdesi TUTULMAZ: ölçen görev
        // başvuruları hemen çıkarır (açılmış gövde 20 MB'a kadar olabilir — yüzlerce stil dalga sonuna dek bellekte kalırsa
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

    /** Taramanın paylaşılan durumu (bütçe, ilerleme, tavan bayrakları). */
    private final class Run {
        final Snapshot s;
        final Config c;
        final long deadline;
        final long budget;
        final AtomicLong spent = new AtomicLong();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger total = new AtomicInteger();
        volatile boolean timeCapped, bytesCapped;
        /** Satır artık RUNNING değil (başka okuyucu eskimiş sayıp kapattı ya da izleme silindi) — ölçüm bırakılır. */
        volatile boolean lost;
        volatile boolean objectsCapped;
        final AtomicInteger cssRefsRetained = new AtomicInteger();
        int excludedExtra;
        long lastBeat;
        String ua;
        String rootHost;

        Run(Snapshot s, Config c, long start) {
            this.s = s;
            this.c = c;
            this.deadline = start + c.maxSeconds() * 1000L;
            this.budget = c.maxTotalMb() * PageResourceAnalyzer.MB;
        }

        boolean halted() {
            return stopping || lost || Thread.currentThread().isInterrupted();
        }

        /** Kalp atışı + ilerleme; 0 satır = satır artık bu taramanın değil → {@link #lost}. */
        void beat(boolean force) {
            long now = clock.getAsLong();
            if (!force && now - lastBeat < HEARTBEAT_MS) return;
            lastBeat = now;
            try {
                if (jdbc.update(SQL_PROGRESS, done.get(), total.get(), now, s.id()) == 0) {
                    lost = true;
                    log.info("Sayfa kaynak analizi {} artık çalışıyor görünmüyor (yarıda kaldı sayıldı / silindi) — ölçüm bırakılıyor",
                            s.id());
                }
            } catch (RuntimeException e) {
                log.debug("Kaynak analizi ilerlemesi yazılamadı: {}", e.getMessage());
            }
        }
    }

    void run(Snapshot s, Config c) {
        long start = clock.getAsLong();
        Run run = new Run(s, c, start);
        ScanItem html = null;
        try {
            run.ua = pageChecker.effectiveUserAgent();
            run.total.set(1);
            run.beat(true);
            // Ana sayfa (yönlendirmelerle birlikte dakikalar sürebilir) ölçülürken de kalp atışı yazılır — yoksa okuyucular
            // canlı taramayı "eskidi" sayıp kapatırdı (inceleme 2026-10-31).
            CompletableFuture<PageFetchCore.MetaFetch> mainF = CompletableFuture.supplyAsync(() -> core.fetchWithMeta(s.url(),
                    "GET", new PageFetchCore.FetchOptions(c.requestTimeoutMs(), run.ua, null, true, true, s.viaProxy()), 0),
                    core.executor());
            PageFetchCore.MetaFetch main = awaitBeating(run, mainF);
            if (main == null || run.halted()) { end(s.id(), INTERRUPTED, "SHUTDOWN", null); return; }
            html = item(new PageResourceDiscovery.Candidate(s.url(), PageResourceDiscovery.HTML, true, false, false),
                    main, null);
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

            Set<String> known = new HashSet<>();
            known.add(PageResourceDiscovery.key(base));
            known.add(PageResourceDiscovery.key(s.url()));
            List<Planned> all = new ArrayList<>();
            AtomicInteger order = new AtomicInteger(1);
            List<Planned> phase = new ArrayList<>();
            for (PageResourceDiscovery.Candidate cand : found.pageLoad()) plan(run, all, phase, known, order, cand, null, 0, null);
            for (String inline : found.inlineCss()) {
                for (PageResourceDiscovery.Candidate cand : PageResourceDiscovery.cssRefs(inline, base)) {
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
            all.sort(java.util.Comparator.comparingInt(p -> p.order));
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
            finishDone(s, run, items, r);
        } catch (Throwable t) {
            if (t instanceof InterruptedException || run.halted()) {
                Thread.currentThread().interrupt();
                end(s.id(), INTERRUPTED, "SHUTDOWN", html);
            } else {
                log.warn("Sayfa kaynak analizi beklenmeyen hatayla durdu (tarama {}, izleme {}): {}", s.id(), s.monitorId(),
                        t.getClass().getSimpleName(), t);
                end(s.id(), FAILED, "INTERNAL:" + t.getClass().getSimpleName(), html);
            }
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

    /** Bir dalgayı sınırlı eşzamanlılıkla ölçer; süre / bayt bütçesi / kapanış kalanları atlar. */
    private void measure(Run run, List<Planned> list) throws InterruptedException {
        if (list.isEmpty() || run.halted()) return;
        Semaphore gate = new Semaphore(run.c.concurrency());
        List<CompletableFuture<Void>> futures = new ArrayList<>(list.size());
        for (Planned p : list) {
            futures.add(CompletableFuture.runAsync(() -> {
                if (!admit(run)) return;
                try {
                    gate.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (!admit(run)) return;
                    measureOne(run, p);
                } finally {
                    gate.release();
                }
            }, core.executor()));
        }
        for (CompletableFuture<Void> f : futures) {
            while (true) {
                if (run.halted()) {
                    futures.forEach(x -> x.cancel(true));
                    return;
                }
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
    }

    /** Tek bir işi kalp atışı yazarak bekler; kapanış / kayıp satır → null (iş iptal). İşin kendi zaman aşımları sınırlıdır. */
    private <T> T awaitBeating(Run run, CompletableFuture<T> f) throws InterruptedException {
        while (true) {
            if (run.halted()) { f.cancel(true); return null; }
            try {
                return f.get(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                run.beat(false);
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
        String cat = p.c.category();
        boolean css = PageResourceDiscovery.CSS.equals(cat);
        boolean image = PageResourceDiscovery.IMAGE.equals(cat) || PageResourceDiscovery.FAVICON.equals(cat);
        PageFetchCore.FetchOptions o = new PageFetchCore.FetchOptions(run.c.requestTimeoutMs(), run.ua, null, css, true,
                run.s.viaProxy());
        PageFetchCore.MetaFetch mf = core.fetchWithMeta(p.c.url(), "GET", o, image ? ImageDimensions.HEAD_BYTES : 0);
        run.spent.addAndGet(Math.max(0, mf.fetch().bytes()));
        ScanItem it = item(p.c, mf, run.rootHost);
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

    /** Ölçüm sonucunu nesne satırına çevirir; kategori içerik türüne göre incelir. */
    ScanItem item(PageResourceDiscovery.Candidate c, PageFetchCore.MetaFetch mf, String rootHost) {
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
        it.ttfbMs = Math.max(0, f.ttfbMs());
        it.durationMs = Math.max(0, f.durationMs());
        Long len = meta.contentLength();
        if (f.truncated() && len != null && len > f.bytes() && len <= MAX_HEADER_SIZE) {
            it.bytes = len;
            it.sizeSource = "header";
            it.truncated = false;
        } else {
            it.bytes = Math.max(0, f.bytes());
            it.truncated = f.truncated();
        }
        String host = PageFetchCore.hostOf(c.url());
        it.thirdParty = rootHost != null && host != null && !safeSameSite(host, rootHost);
        String enc = meta.contentEncoding();
        it.compressed = enc != null && !enc.isBlank() && !"identity".equalsIgnoreCase(enc.trim());
        it.cacheTtlS = cacheTtl(meta.cacheControl(), meta.expires(), meta.date(), clock.getAsLong());
        it.redirects = meta.redirects();
        if (PageResourceDiscovery.IMAGE.equals(it.category) || PageResourceDiscovery.FAVICON.equals(it.category)) {
            ImageDimensions.Size d = ImageDimensions.read(meta.head());
            if (d != null) { it.width = d.width(); it.height = d.height(); }
        }
        if (f.blocked()) it.error = "BLOCKED";
        else if (f.status() == 0) it.error = f.failure() != null ? f.failure().code() : "TRANSPORT_ERROR";
        return it;
    }

    private boolean safeSameSite(String host, String root) {
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

    // ── Bitiş ───────────────────────────────────────────────────────────────────────────────

    private void finishDone(Snapshot s, Run run, List<ScanItem> items, PageResourceAnalyzer.Result r) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (ScanItem it : items) rows.add(it.toMap());
        String analysis = JSON.writeValueAsString(r.analysis());
        String itemsJson = JSON.writeValueAsString(rows);
        long now = clock.getAsLong();
        int n = jdbc.update(SQL_DONE, iso(now), now, run.done.get(), run.total.get(), r.grade(), r.gradeLetter(), r.loadMs(),
                r.htmlTtfbMs(), r.htmlMs(), r.pageBytes(), r.requestCount(), r.imageBytes(), r.imageCount(), r.documentCount(),
                r.documentBytes(), r.linkCount(), r.linkBytes(), r.failedCount(), r.capped(), analysis, itemsJson, s.id());
        if (n == 0) {
            log.info("Sayfa kaynak analizi {} bitti ama satır artık çalışıyor değil (yarıda kaldı sayılmış) — sonuç yazılmadı", s.id());
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
                    html == null ? null : html.durationMs, id);
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
