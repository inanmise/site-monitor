package com.sitemonitor.service.page.scan.budget;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kaynak analizi EĞİLİMİ (2026-11, F2): bitmiş (DONE) analizlerin özet kolonları zaman çizgisi + "en çok büyüyen nesneler".
 *
 * <p><b>Sorgu sayısı sabit:</b> (1) pencerenin bitmiş analizleri — yalnız özet kolonları, {@code analysis_json} OKUNMAZ, en çok
 * {@value #MAX_POINTS} (+1 kesilme denetimi); (2) büyüme için nesne ayrıntısı saklı ({@code items_kept}) en yeni ≤ 20 analizin
 * {@code items_json}'u — analiz BAŞINA ayrı sorgu (bellekte aynı anda tek analizin ayrıntısı), sürücünün karakter akışından
 * ayrıştırma ({@link ResourceGrowth#parse(long, java.io.Reader)}); pod'da aynı anda en çok {@value #GROWTH_CONCURRENCY} büyüme
 * hesabı, yuva {@value #GROWTH_WAIT_MS} ms'de boşalmazsa {@code growth: null} + {@code growth_busy: true}. Aynı analiz
 * kümesinin büyüme sonucu küçük bir bellekte tutulur: bitmiş analiz değişmez, sonuç küme kimliklerine bağlıdır (bayatlamaz).
 *
 * <p><b>Algoritma sürümü:</b> büyüme yalnız EN YENİ saklı analizle AYNI algoritma sürümündeki analizlerden hesaplanır
 * (sürüm değişince ölçüm yöntemi — ör. Brotli kabulü — baytı değiştirebilir; sahte büyüme gösterilmez). Noktalar her sürümü
 * taşır ({@code algorithm_version}); arayüz sürüm değişimini işaretler.
 *
 * <p><b>Eski şema:</b> algoritma 3 kolonları ({@code js_bytes} …, {@code scope}) yoksa noktalar onlarsız (null) okunur.
 */
@Slf4j
@Service
public class ResourceTrendService {

    /** İzin verilen pencereler (gün); başka değer → {@value #DEFAULT_DAYS}. */
    public static final Set<Integer> ALLOWED_DAYS = Set.of(30, 90, 365);
    public static final int DEFAULT_DAYS = 90;
    public static final int MAX_POINTS = 200;
    /** Büyümeye giren en çok analiz (= nesne ayrıntısının saklandığı çalıştırma sayısı). */
    public static final int GROWTH_SCANS = 20;
    /** Büyüme belleği (izleme × analiz kümesi) — en çok bu kadar giriş. */
    static final int GROWTH_CACHE_MAX = 64;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final String POINT_COLS_BASE = "id, finished_at, algorithm_version, grade, load_ms, page_bytes, request_count, "
            + "image_bytes, capped, items_kept";
    static final String POINT_COLS_DEEP = ", js_bytes, css_bytes, font_bytes, security_score, seo_score, a11y_score, scope";
    static final String SQL_POINTS = "SELECT " + POINT_COLS_BASE + POINT_COLS_DEEP + " FROM page_resource_scans "
            + "WHERE monitor_id = ? AND status = 'DONE' AND started_at >= ? ORDER BY id DESC LIMIT ?";
    static final String SQL_POINTS_LEGACY = "SELECT " + POINT_COLS_BASE + " FROM page_resource_scans "
            + "WHERE monitor_id = ? AND status = 'DONE' AND started_at >= ? ORDER BY id DESC LIMIT ?";
    /** Tek analizin nesne ayrıntısı (O-4: kimlik başına ayrı sorgu — aynı anda bellekte en çok bir analiz). */
    static final String SQL_ITEM_ONE = "SELECT items_json FROM page_resource_scans WHERE id = ? AND monitor_id = ? "
            + "AND items_json IS NOT NULL";
    /** Pod'da aynı anda en çok bu kadar büyüme hesabı (bellek tepesi). */
    static final int GROWTH_CONCURRENCY = 2;
    /** Yuva için en çok bekleme; dolarsa {@code growth: null} + {@code growth_busy}. */
    static final long GROWTH_WAIT_MS = 1_500L;
    /** Paket içi: testler yuvaları doldurabilsin. */
    final Semaphore growthSlots = new Semaphore(GROWTH_CONCURRENCY);

    private static final long WARN_EVERY_MS = 3_600_000L;
    private final AtomicLong lastLegacyWarn = new AtomicLong();

    private final JdbcTemplate jdbc;
    /** Büyüme belleği: "izleme:kimlik,kimlik,…" → sonuç (erişim sıralı, en eski düşer). */
    private final Map<String, List<Map<String, Object>>> growthCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Map<String, Object>>> eldest) {
                    return size() > GROWTH_CACHE_MAX;
                }
            });
    /** Testler için: saat. */
    java.util.function.LongSupplier clock = System::currentTimeMillis;

    public ResourceTrendService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** İstekteki gün değeri → izin verilen pencere (bilinmeyen / okunamayan → {@value #DEFAULT_DAYS}). */
    public static int days(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 6) return DEFAULT_DAYS;
        try {
            int d = Integer.parseInt(raw.trim());
            return ALLOWED_DAYS.contains(d) ? d : DEFAULT_DAYS;
        } catch (NumberFormatException e) {
            return DEFAULT_DAYS;
        }
    }

    /**
     * Eğilim: {@code points} (eskiden yeniye, ≤ {@value #MAX_POINTS}; pencerede daha fazlası varsa en yeniler +
     * {@code truncated}), her noktada güncel bütçeye göre {@code budget_status} / {@code budget_exceeded}, {@code growth}
     * (≤ 20) ve dayanağı {@code growth_basis}.
     */
    public Map<String, Object> trend(long monitorId, int days, ResourceBudget budget) {
        int d = ALLOWED_DAYS.contains(days) ? days : DEFAULT_DAYS;
        String since = ISO.format(Instant.ofEpochMilli(clock.getAsLong() - d * 86_400_000L));
        List<Point> rows = points(monitorId, since);
        boolean truncated = rows.size() > MAX_POINTS;
        if (truncated) rows = rows.subList(0, MAX_POINTS);

        // Büyüme dayanağı: ayrıntısı saklı en yeni ≤ 20 analiz, en yenisinin algoritma sürümüyle aynı olanlar
        List<Long> growthIds = new ArrayList<>();
        Integer basisVersion = null;
        boolean basisSet = false;
        for (Point p : rows) {                       // en yeni önce
            if (!p.itemsKept) continue;
            if (!basisSet) {
                basisVersion = p.algorithmVersion;
                basisSet = true;
            }
            if (!Objects.equals(version(p.algorithmVersion), version(basisVersion))) continue;
            growthIds.add(p.id);
            if (growthIds.size() >= GROWTH_SCANS) break;
        }
        Collections.reverse(growthIds);              // eskiden yeniye
        List<Map<String, Object>> growth = growthIds.size() < 2 ? List.of() : growth(monitorId, growthIds);
        boolean growthBusy = growth == null;   // hesap yuvası dolu — bilinmiyor (boş liste "büyüyen yok" demek olurdu)

        List<Map<String, Object>> points = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) points.add(rows.get(i).toMap(budget));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("points", points);
        out.put("truncated", truncated);
        out.put("max_points", MAX_POINTS);
        out.put("growth", growth);
        if (growthBusy) out.put("growth_busy", true);
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("scans", growthIds.size());
        basis.put("algorithm_version", basisVersion);
        basis.put("from_scan_id", growthIds.isEmpty() ? null : growthIds.get(0));
        basis.put("to_scan_id", growthIds.isEmpty() ? null : growthIds.get(growthIds.size() - 1));
        out.put("growth_basis", basis);
        Map<String, Object> b = (budget == null ? ResourceBudget.EMPTY : budget).toMap();
        b.put("defined", budget != null && budget.defined());
        out.put("budget", b);
        return out;
    }

    /** NULL sürüm = algoritma 1. */
    private static int version(Integer v) {
        return v == null ? 1 : v;
    }

    private List<Point> points(long monitorId, String since) {
        try {
            return jdbc.query(SQL_POINTS, (rs, i) -> Point.of(rs, true), monitorId, since, MAX_POINTS + 1);
        } catch (BadSqlGrammarException e) {
            long now = System.currentTimeMillis();
            long last = lastLegacyWarn.get();
            if (now - last >= WARN_EVERY_MS && lastLegacyWarn.compareAndSet(last, now)) {
                log.warn("Kaynak analizi eğilim kolonları (js_bytes … scope) okunamadı — eski şema ya da yama eksik; "
                        + "bu değerler boş gösterilir: {}", e.getClass().getSimpleName());
            }
            return jdbc.query(SQL_POINTS_LEGACY, (rs, i) -> Point.of(rs, false), monitorId, since, MAX_POINTS + 1);
        }
    }

    /**
     * Büyüme hesabı (O-4): bellekte yoksa pod'da en çok {@value #GROWTH_CONCURRENCY} hesap aynı anda; yuva
     * {@value #GROWTH_WAIT_MS} ms içinde boşalmazsa null (çağıran {@code growth: null} + {@code growth_busy: true} yazar).
     */
    private List<Map<String, Object>> growth(long monitorId, List<Long> ids) {
        StringJoiner key = new StringJoiner(",", monitorId + ":", "");
        for (Long id : ids) key.add(String.valueOf(id));
        String k = key.toString();
        List<Map<String, Object>> cached = growthCache.get(k);
        if (cached != null) return cached;
        boolean slot;
        try {
            slot = growthSlots.tryAcquire(GROWTH_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!slot) return null;
        try {
            cached = growthCache.get(k);   // beklerken başka istek hesaplamış olabilir
            if (cached != null) return cached;
            return computeGrowth(monitorId, ids, k);
        } finally {
            growthSlots.release();
        }
    }

    /**
     * Analizleri TEK TEK okur (kimlik başına bir sorgu — bellekte aynı anda en çok bir analizin ayrıntısı) ve karakter
     * akışından ayrıştırır; ham metin String olarak tutulmaz.
     */
    private List<Map<String, Object>> computeGrowth(long monitorId, List<Long> ids, String k) {
        List<ResourceGrowth.ScanObjects> ordered = new ArrayList<>();
        for (Long id : ids) {
            ResourceGrowth.ScanObjects s = jdbc.query(SQL_ITEM_ONE, rs -> {
                if (!rs.next()) return null;
                java.io.Reader reader = rs.getCharacterStream("items_json");
                return reader == null ? null : ResourceGrowth.parse(id, reader);
            }, id, monitorId);
            // Arada ayrıntısı silinmiş analiz (son 20 kuralı) atlanır; kalan küme yine eskiden yeniye
            if (s != null) ordered.add(s);
        }
        List<Map<String, Object>> out = List.copyOf(ResourceGrowth.compute(ordered));
        // Küme tam okunduysa bellekte tut (silinmiş ayrıntı → bir sonraki çağrı yeni kümeyle gelir)
        if (ordered.size() == ids.size()) growthCache.put(k, out);
        return out;
    }

    /** Bir eğilim noktası (özet kolonları). */
    record Point(long id, String finishedAt, Integer algorithmVersion, Long grade, Long loadMs, Long pageBytes,
                 Long requestCount, Long imageBytes, boolean capped, boolean itemsKept, Long jsBytes, Long cssBytes,
                 Long fontBytes, Long securityScore, Long seoScore, Long a11yScore, String scope) {

        static Point of(ResultSet rs, boolean deep) throws SQLException {
            Long av = ResourceBudgetService.longOf(rs, "algorithm_version");
            boolean capped = rs.getBoolean("capped");
            if (rs.wasNull()) capped = false;
            boolean kept = rs.getBoolean("items_kept");
            if (rs.wasNull()) kept = false;
            return new Point(rs.getLong("id"), rs.getString("finished_at"), av == null ? null : av.intValue(),
                    ResourceBudgetService.longOf(rs, "grade"), ResourceBudgetService.longOf(rs, "load_ms"),
                    ResourceBudgetService.longOf(rs, "page_bytes"), ResourceBudgetService.longOf(rs, "request_count"),
                    ResourceBudgetService.longOf(rs, "image_bytes"), capped, kept,
                    deep ? ResourceBudgetService.longOf(rs, "js_bytes") : null,
                    deep ? ResourceBudgetService.longOf(rs, "css_bytes") : null,
                    deep ? ResourceBudgetService.longOf(rs, "font_bytes") : null,
                    deep ? ResourceBudgetService.longOf(rs, "security_score") : null,
                    deep ? ResourceBudgetService.longOf(rs, "seo_score") : null,
                    deep ? ResourceBudgetService.longOf(rs, "a11y_score") : null,
                    deep ? rs.getString("scope") : null);
        }

        Map<String, Object> toMap(ResourceBudget budget) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scan_id", id);
            m.put("finished_at", finishedAt);
            m.put("algorithm_version", algorithmVersion);
            m.put("grade", grade);
            m.put("load_ms", loadMs);
            m.put("page_bytes", pageBytes);
            m.put("request_count", requestCount);
            m.put("js_bytes", jsBytes);
            m.put("css_bytes", cssBytes);
            m.put("image_bytes", imageBytes);
            m.put("font_bytes", fontBytes);
            m.put("security_score", securityScore);
            m.put("seo_score", seoScore);
            m.put("a11y_score", a11yScore);
            m.put("capped", capped);
            m.put("scope", scope == null ? "page" : scope);
            ResourceBudgetEvaluator.Result r = ResourceBudgetEvaluator.evaluate(budget, new ResourceBudgetEvaluator.Facts(
                    "DONE", capped, pageBytes, requestCount, jsBytes, imageBytes, loadMs, grade, securityScore));
            m.put("budget_status", r.status());
            m.put("budget_exceeded", r.exceeded());
            return m;
        }
    }
}
