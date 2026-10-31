package com.sitemonitor.service.page.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.sitemonitor.service.page.scan.PageResourceDiscovery.CSS;
import static com.sitemonitor.service.page.scan.PageResourceDiscovery.DOCUMENT;
import static com.sitemonitor.service.page.scan.PageResourceDiscovery.FAVICON;
import static com.sitemonitor.service.page.scan.PageResourceDiscovery.FONT;
import static com.sitemonitor.service.page.scan.PageResourceDiscovery.HTML;
import static com.sitemonitor.service.page.scan.PageResourceDiscovery.IMAGE;
import static com.sitemonitor.service.page.scan.PageResourceDiscovery.JS;
import static com.sitemonitor.service.page.scan.PageResourceDiscovery.LINK;
import static com.sitemonitor.service.page.scan.PageResourceDiscovery.MEDIA;

/**
 * Kaynak analizinin SAF hesabı (2026-10-31): ölçülen nesnelerden tahmini yükleme süresi (şelale), 100 üzerinden performans
 * notu, öneriler, görsellerin sayfa hızına etkisi ve kategori dökümü. Ağ / veritabanı yok — tablo testleriyle sabitlenir.
 *
 * <p><b>Yükleme süresi bir TAHMİNDİR:</b> sunucudan ölçülen nesne süreleri, tarayıcının HTML'yi indirdikten sonra
 * {@value #PARALLEL} paralel bağlantıyla nesneleri keşif sırasıyla istediği varsayımıyla yerleştirilir; stilin
 * {@code url()} ile bulduğu nesne o stil inmeden başlayamaz. Geç yüklenen (lazy) nesneler, belgeler ve bağlantılar tahmine
 * girmez. JavaScript çalıştırılmaz.
 *
 * <p>Not (ağırlıklar toplamı 100): yükleme süresi 25, sayfa boyutu 20, görseller 15, istek sayısı 10, sunucu yanıtı (TTFB)
 * 10, hatalar 8, sıkıştırma 7, önbellek 5. Harf: A ≥ 90, B ≥ 80, C ≥ 70, D ≥ 60, E ≥ 50, F altı.
 *
 * <p><b>Algoritma sürümü {@value #ALGORITHM_VERSION}</b> (2026-11, analiz kökünde {@code algorithm_version}; eski kayıtta yok =
 * 1): TTFB yalnız son adımın ilk baytı (yönlendirmeler ayrı), kırık kararı Sayfa Bütünlüğü izlemesiyle aynı (yalnız
 * {@code broken} hata puanına ve BROKEN_* önerilerine girer; doğrulanamayan / engellenen girmez), indirilmeyen medya
 * hiçbir toplama girmez, görsel kazancı ölçülen görsellerde ölçülmüş. Not formülü ya da girdisi değişirse sürüm ARTAR.
 */
public final class PageResourceAnalyzer {

    /** Analiz algoritmasının sürümü — farklı sürümlü iki analiz karşılaştırılırken arayüz uyarır. */
    public static final int ALGORITHM_VERSION = 2;

    public static final int PARALLEL = 6;

    public static final long KB = 1024L, MB = 1024L * 1024L;
    public static final int LARGE_IMAGE_LIMIT_KB = 200;
    public static final long LARGE_IMAGE_BYTES = LARGE_IMAGE_LIMIT_KB * KB;
    public static final long LEGACY_FORMAT_MIN_BYTES = 50 * KB;
    public static final int OVERSIZED_WIDTH = 2000;
    public static final int PAGE_LIMIT_MB = 3;
    public static final int REQUEST_LIMIT = 100;
    public static final long SLOW_SERVER_MS = 800;
    public static final long TEXT_MIN_BYTES = KB;
    public static final long CACHE_GOOD_S = 86_400, CACHE_OK_S = 3_600;
    public static final long HEAVY_JS_BYTES = 500 * KB, HEAVY_CSS_BYTES = 150 * KB, HEAVY_FONT_BYTES = 300 * KB;
    public static final int HEAVY_FONT_COUNT = 6;
    public static final double THIRD_PARTY_SHARE = 30.0;
    public static final long THIRD_PARTY_MIN_BYTES = 300 * KB;
    public static final long SLOW_OBJECT_MS = 1000;
    public static final long LARGE_DOCUMENT_BYTES = 5 * MB;
    /** Tahmini aktarım hızları (bayt / sn): 4G ≈ 9 Mbit/s, 3G ≈ 1,6 Mbit/s. */
    public static final double BPS_4G = 9_000_000 / 8.0, BPS_3G = 1_600_000 / 8.0;
    /** Kazanç tahminleri: büyük görseli yeniden boyutlandır / sıkıştır ≈ %60, eski biçimden WebP/AVIF ≈ %30,
     *  sıkıştırılmamış metin ≈ %70. */
    static final double SAVE_LARGE_IMAGE = 0.60, SAVE_MODERN_FORMAT = 0.30, SAVE_TEXT = 0.70;
    static final int MAX_ITEM_URLS = 10;
    /** {@code third_parties} tablosunun en çok satırı (bayta göre). */
    static final int MAX_THIRD_PARTIES = 20;

    /**
     * Nesneye bulgu olarak yazılan öneri kodları ({@code findings}) — öneriyi üreten kuralın nesne listesinin AYNISI.
     * Sayfa düzeyindeki kodlar (OVERSIZED_PAGE, TOO_MANY_REQUESTS, SLOW_SERVER, THIRD_PARTY_HEAVY, ALL_GOOD) nesneye
     * yazılmaz; onların öneri satırında {@code item_count} null'dır.
     */
    public static final Set<String> FINDING_CODES = Set.of("UNCOMPRESSED_TEXT", "NO_CACHE", "LARGE_IMAGES",
            "MODERN_IMAGE_FORMAT", "OVERSIZED_IMAGE_DIMENSIONS", "SLOW_OBJECTS", "REDIRECTED_OBJECTS", "BROKEN_RESOURCES",
            "BROKEN_LINKS", "LARGE_DOCUMENTS", "HEAVY_JS", "HEAVY_CSS", "HEAVY_FONTS");

    /** Ağırlıklar (sıra = arayüzdeki sıra). */
    public static final Map<String, Integer> WEIGHTS;
    static {
        Map<String, Integer> w = new LinkedHashMap<>();
        w.put("load_time", 25);
        w.put("page_weight", 20);
        w.put("images", 15);
        w.put("requests", 10);
        w.put("server_response", 10);
        w.put("errors", 8);
        w.put("compression", 7);
        w.put("caching", 5);
        WEIGHTS = java.util.Collections.unmodifiableMap(w);
    }

    static final double[][] LOAD_CURVE = {{1000, 100}, {2000, 90}, {3000, 75}, {5000, 50}, {8000, 25}, {12000, 0}};
    static final double[][] WEIGHT_CURVE_MB = {{0.5, 100}, {1, 90}, {2, 75}, {4, 50}, {8, 20}, {15, 0}};
    static final double[][] REQUEST_CURVE = {{25, 100}, {50, 90}, {80, 75}, {120, 50}, {200, 20}, {300, 0}};
    static final double[][] TTFB_CURVE = {{200, 100}, {500, 85}, {800, 70}, {1500, 40}, {3000, 0}};
    static final double[][] IMAGE_CURVE_MB = {{0.5, 100}, {1, 85}, {2, 65}, {4, 35}, {8, 0}};
    static final double[][] ERROR_CURVE = {{0, 100}, {1, 80}, {2, 65}, {5, 30}, {10, 0}};

    /**
     * Bileşen hedefleri {tam puan eşiği, sıfır puan eşiği} — {@code unit} ile aynı birimde; eğrilerden türetilir (tek kaynak).
     * Görsellerde ayrıca her büyük görsel (≥ {@value #LARGE_IMAGE_LIMIT_KB} KB) 8 puan düşürür.
     */
    static final Map<String, Object[]> TARGETS = Map.of(
            "load_time", new Object[]{(long) LOAD_CURVE[0][0], (long) LOAD_CURVE[LOAD_CURVE.length - 1][0]},
            "page_weight", new Object[]{Math.round(WEIGHT_CURVE_MB[0][0] * MB),
                    Math.round(WEIGHT_CURVE_MB[WEIGHT_CURVE_MB.length - 1][0] * MB)},
            "images", new Object[]{Math.round(IMAGE_CURVE_MB[0][0] * MB), Math.round(IMAGE_CURVE_MB[IMAGE_CURVE_MB.length - 1][0] * MB)},
            "requests", new Object[]{(int) REQUEST_CURVE[0][0], (int) REQUEST_CURVE[REQUEST_CURVE.length - 1][0]},
            "server_response", new Object[]{(long) TTFB_CURVE[0][0], (long) TTFB_CURVE[TTFB_CURVE.length - 1][0]},
            "errors", new Object[]{(int) ERROR_CURVE[0][0], (int) ERROR_CURVE[ERROR_CURVE.length - 1][0]},
            "compression", new Object[]{100, 0},
            "caching", new Object[]{100, 0});

    /** Taramanın sınırları — ölçüm notları için. */
    public record Limits(boolean objectsCapped, int maxObjects, boolean timeCapped, int maxSeconds,
                         boolean bytesCapped, int maxTotalMb, int excluded) {
        public static final Limits NONE = new Limits(false, 0, false, 0, false, 0, 0);

        public boolean capped() {
            return objectsCapped || timeCapped || bytesCapped;
        }
    }

    /** Özet sayılar (tarama satırına yazılır) + ayrıntılı analiz (JSON). */
    public record Result(int grade, String gradeLetter, long loadMs, long htmlTtfbMs, long htmlMs, long pageBytes,
                         int requestCount, long imageBytes, int imageCount, int documentCount, long documentBytes,
                         int linkCount, long linkBytes, int failedCount, boolean capped, Map<String, Object> analysis) {}

    private PageResourceAnalyzer() {}

    /**
     * @param items ölçülen nesneler; 0. sıra HTML (sayfanın kendisi), diğerleri keşif sırasıyla. {@code startMs}
     *              bu çağrıda yazılır.
     */
    public static Result analyze(List<ScanItem> items, Limits limits) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("HTML satırı yok");
        ScanItem html = items.get(0);
        Limits lim = limits == null ? Limits.NONE : limits;

        long loadMs = simulate(items);

        List<ScanItem> page = new ArrayList<>();
        List<ScanItem> docs = new ArrayList<>();
        List<ScanItem> links = new ArrayList<>();
        int notLoaded = 0;
        for (ScanItem it : items) {
            it.findings.clear();
            if (it != html && it.skipped()) { notLoaded++; continue; }   // indirilmedi: hiçbir toplama girmez
            if (it.pageLoad) page.add(it);
            else if (DOCUMENT.equals(it.category)) docs.add(it);
            else links.add(it);
        }
        long pageBytes = sum(page);
        List<ScanItem> images = page.stream().filter(PageResourceAnalyzer::isImage).toList();
        long imageBytes = sum(images);
        int pageFailed = (int) page.stream().filter(ScanItem::failed).count();
        // Özet "açılmayan nesne" = sayfa nesneleri (istek kutucuğu ve hatalar puanıyla aynı); kırık belge / bağlantı ayrı öneri.
        int failed = pageFailed;

        // ── Alt puanlar ─────────────────────────────────────────────────────────────────
        List<ScanItem> largeImages = images.stream().filter(i -> i.bytes >= LARGE_IMAGE_BYTES).toList();
        int imageScore = Math.min(curve(imageBytes / (double) MB, IMAGE_CURVE_MB),
                Math.max(0, 100 - 8 * largeImages.size()));
        List<ScanItem> texts = page.stream().filter(PageResourceAnalyzer::compressibleText).toList();
        int compressionPct = texts.isEmpty() ? 100
                : (int) Math.round(100.0 * texts.stream().filter(i -> i.compressed).count() / texts.size());
        List<ScanItem> statics = page.stream().filter(PageResourceAnalyzer::cacheableStatic).toList();
        int cachingPct = statics.isEmpty() ? 100 : (int) Math.round(statics.stream()
                .mapToDouble(i -> i.cacheTtlS == null ? 0 : i.cacheTtlS >= CACHE_GOOD_S ? 1.0 : i.cacheTtlS >= CACHE_OK_S ? 0.5 : 0)
                .sum() * 100.0 / statics.size());

        List<Map<String, Object>> scores = new ArrayList<>();
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put("load_time", curve(loadMs, LOAD_CURVE));
        s.put("page_weight", curve(pageBytes / (double) MB, WEIGHT_CURVE_MB));
        s.put("images", imageScore);
        s.put("requests", curve(page.size(), REQUEST_CURVE));
        s.put("server_response", curve(html.ttfbMs, TTFB_CURVE));
        s.put("errors", curve(pageFailed, ERROR_CURVE));
        s.put("compression", compressionPct);
        s.put("caching", cachingPct);
        Map<String, Object[]> values = Map.of(
                "load_time", new Object[]{loadMs, "ms"},
                "page_weight", new Object[]{pageBytes, "bytes"},
                "images", new Object[]{imageBytes, "bytes"},
                "requests", new Object[]{page.size(), "count"},
                "server_response", new Object[]{html.ttfbMs, "ms"},
                "errors", new Object[]{pageFailed, "count"},
                "compression", new Object[]{compressionPct, "pct"},
                "caching", new Object[]{cachingPct, "pct"});
        double weighted = 0;
        for (Map.Entry<String, Integer> w : WEIGHTS.entrySet()) {
            int sc = s.get(w.getKey());
            weighted += sc * w.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", w.getKey());
            row.put("score", sc);
            row.put("weight", w.getValue());
            row.put("value", values.get(w.getKey())[0]);
            row.put("unit", values.get(w.getKey())[1]);
            // Hedefler (unit ile aynı birim): tam puan ve sıfır puan eşiği + ağırlıklı kayıp puan (1 ondalık)
            Object[] target = TARGETS.get(w.getKey());
            row.put("full_at", target[0]);
            row.put("zero_at", target[1]);
            row.put("lost", Math.round(w.getValue() * (100 - sc) / 10.0) / 10.0);
            scores.add(row);
        }
        int grade = (int) Math.round(weighted / 100.0);
        String letter = letter(grade);

        // ── Kategoriler ─────────────────────────────────────────────────────────────────
        Map<String, long[]> cat = new LinkedHashMap<>();
        for (String c : PageResourceDiscovery.CATEGORIES) cat.put(c, new long[3]);
        for (ScanItem it : items) {
            if (it != html && it.skipped()) continue;
            long[] a = cat.computeIfAbsent(it.category == null ? PageResourceDiscovery.OTHER : it.category, k -> new long[3]);
            a[0]++;
            a[1] += it.bytes;
            a[2] += it.durationMs;
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map.Entry<String, long[]> e : cat.entrySet()) {
            if (e.getValue()[0] == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", e.getKey());
            row.put("count", e.getValue()[0]);
            row.put("bytes", e.getValue()[1]);
            row.put("duration_ms", e.getValue()[2]);
            categories.add(row);
        }

        // Öneriler nesnelere bulgu ({@code findings}) yazar — görsel listesi (toMap) bundan SONRA kurulur
        List<Map<String, Object>> recs = recommendations(html, page, images, docs, links, pageBytes, loadMs, lim.capped());

        // ── Görsel etkisi ───────────────────────────────────────────────────────────────
        // Kazanç: sunucuda ölçülen görselde ölçülmüş (yeniden kodlanmış boyut farkı), diğerlerinde eski tahmin
        long measuredSaving = 0, estimatedSaving = 0;
        int measuredCount = 0;
        for (ScanItem i : images) {
            if (i.optimizedBytes != null) {
                measuredCount++;
                measuredSaving += measuredSaving(i);
            } else if (i.bytes >= LARGE_IMAGE_BYTES) {
                estimatedSaving += Math.round(i.bytes * SAVE_LARGE_IMAGE);
            } else if (legacyFormat(i) && i.bytes >= LEGACY_FORMAT_MIN_BYTES) {
                estimatedSaving += Math.round(i.bytes * SAVE_MODERN_FORMAT);
            }
        }
        long imageSaving = measuredSaving + estimatedSaving;
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("count", images.size());
        img.put("bytes", imageBytes);
        img.put("share_pct", pct1(imageBytes, pageBytes));
        img.put("large_count", largeImages.size());
        img.put("large_bytes", sum(largeImages));
        img.put("est_ms_4g", transferMs(imageBytes, BPS_4G));
        img.put("est_ms_3g", transferMs(imageBytes, BPS_3G));
        img.put("saving_bytes", imageSaving);
        img.put("saving_ms_4g", transferMs(imageSaving, BPS_4G));
        img.put("saving_ms_3g", transferMs(imageSaving, BPS_3G));
        img.put("saving_measured", measuredCount > 0);
        img.put("measured_count", measuredCount);
        img.put("saving_bytes_measured", measuredSaving);
        img.put("saving_bytes_estimated", estimatedSaving);
        img.put("top", images.stream().sorted(Comparator.comparingLong((ScanItem i) -> i.bytes).reversed())
                .limit(10).map(ScanItem::toMap).toList());

        // ── Zamanlama ───────────────────────────────────────────────────────────────────
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("load_ms", loadMs);
        timing.put("sum_ms", items.stream().filter(i -> i.startMs != null).mapToLong(i -> i.durationMs).sum());
        timing.put("slowest_ms", page.stream().mapToLong(i -> i.durationMs).max().orElse(0));
        timing.put("parallel", PARALLEL);
        timing.put("ttfb_ms", html.ttfbMs);
        timing.put("redirect_count", html.redirects);
        timing.put("redirect_ms", html.redirectMs);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("algorithm_version", ALGORITHM_VERSION);
        analysis.put("scores", scores);
        analysis.put("categories", categories);
        analysis.put("recommendations", recs);
        analysis.put("images", img);
        analysis.put("third_parties", thirdParties(page));
        analysis.put("timing", timing);
        analysis.put("not_loaded_count", notLoaded);
        analysis.put("notes", notes(lim));

        return new Result(grade, letter, loadMs, html.ttfbMs, html.durationMs, pageBytes, page.size(), imageBytes,
                images.size(), docs.size(), sum(docs), links.size(), sum(links), failed, lim.capped(), analysis);
    }

    // ── Yükleme tahmini (şelale) ────────────────────────────────────────────────────────────

    /** {@value #PARALLEL} bağlantıya keşif sırasıyla yerleştirir; {@code startMs} yazar, tahmini yükleme süresini döner. */
    static long simulate(List<ScanItem> items) {
        ScanItem html = items.get(0);
        html.startMs = 0L;
        long htmlEnd = Math.max(0, html.durationMs);
        long[] slots = new long[PARALLEL];
        java.util.Arrays.fill(slots, htmlEnd);
        long[] finish = new long[items.size()];
        finish[0] = htmlEnd;
        long load = htmlEnd;
        for (int i = 1; i < items.size(); i++) {
            ScanItem it = items.get(i);
            if (!it.pageLoad || it.lazy) {
                it.startMs = null;
                finish[i] = -1;
                continue;
            }
            int slot = 0;
            for (int k = 1; k < slots.length; k++) if (slots[k] < slots[slot]) slot = k;
            long start = slots[slot];
            if (it.parent > 0 && it.parent < i && finish[it.parent] >= 0) start = Math.max(start, finish[it.parent]);
            long end = start + Math.max(0, it.durationMs);
            slots[slot] = end;
            finish[i] = end;
            it.startMs = start;
            load = Math.max(load, end);
        }
        return load;
    }

    // ── Öneriler ────────────────────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> recommendations(ScanItem html, List<ScanItem> page, List<ScanItem> images,
                                                             List<ScanItem> docs, List<ScanItem> links, long pageBytes,
                                                             long loadMs, boolean capped) {
        List<Map<String, Object>> out = new ArrayList<>();

        List<ScanItem> broken = page.stream().filter(ScanItem::failed).toList();
        if (!broken.isEmpty()) out.add(rec("BROKEN_RESOURCES", "high", params("count", broken.size()), null, null, broken));

        List<ScanItem> large = images.stream().filter(i -> i.bytes >= LARGE_IMAGE_BYTES).toList();
        if (!large.isEmpty()) {
            long bytes = sum(large);
            long save = 0;
            for (ScanItem i : large) save += i.optimizedBytes != null ? measuredSaving(i) : Math.round(i.bytes * SAVE_LARGE_IMAGE);
            boolean high = large.stream().anyMatch(i -> i.bytes >= MB) || bytes >= 2 * MB;
            out.add(rec("LARGE_IMAGES", high ? "high" : "medium",
                    params("count", large.size(), "bytes", bytes, "limit_kb", LARGE_IMAGE_LIMIT_KB), save,
                    transferMs(save, BPS_4G), large));
        }

        if (pageBytes > PAGE_LIMIT_MB * MB) {
            out.add(rec("OVERSIZED_PAGE", pageBytes > 2L * PAGE_LIMIT_MB * MB ? "high" : "medium",
                    params("bytes", pageBytes, "limit_mb", PAGE_LIMIT_MB), pageBytes - PAGE_LIMIT_MB * MB,
                    transferMs(pageBytes - PAGE_LIMIT_MB * MB, BPS_4G), List.of()));
        }

        if (html.ttfbMs > SLOW_SERVER_MS) {
            out.add(rec("SLOW_SERVER", html.ttfbMs > 1800 ? "high" : "medium",
                    params("ttfb_ms", html.ttfbMs, "limit_ms", SLOW_SERVER_MS), null, html.ttfbMs - SLOW_SERVER_MS, List.of(html)));
        }

        if (page.size() > REQUEST_LIMIT) {
            out.add(rec("TOO_MANY_REQUESTS", page.size() > 2 * REQUEST_LIMIT ? "high" : "medium",
                    params("count", page.size(), "limit", REQUEST_LIMIT), null, null, List.of()));
        }

        List<ScanItem> legacy = images.stream()
                .filter(i -> legacyFormat(i) && i.bytes >= LEGACY_FORMAT_MIN_BYTES && i.bytes < LARGE_IMAGE_BYTES).toList();
        if (!legacy.isEmpty()) {
            long bytes = sum(legacy);
            long save = 0;
            for (ScanItem i : legacy) save += i.optimizedBytes != null ? measuredSaving(i) : Math.round(i.bytes * SAVE_MODERN_FORMAT);
            out.add(rec("MODERN_IMAGE_FORMAT", bytes >= MB ? "medium" : "low",
                    params("count", legacy.size(), "bytes", bytes), save, transferMs(save, BPS_4G), legacy));
        }

        List<ScanItem> wide = images.stream().filter(i -> i.width != null && i.width > OVERSIZED_WIDTH).toList();
        if (!wide.isEmpty()) {
            int maxW = wide.stream().mapToInt(i -> i.width).max().orElse(0);
            out.add(rec("OVERSIZED_IMAGE_DIMENSIONS", "medium", params("count", wide.size(), "max_width", maxW),
                    null, null, wide));
        }

        List<ScanItem> plain = page.stream().filter(PageResourceAnalyzer::compressibleText).filter(i -> !i.compressed).toList();
        if (!plain.isEmpty()) {
            long bytes = sum(plain);
            long save = Math.round(bytes * SAVE_TEXT);
            out.add(rec("UNCOMPRESSED_TEXT", bytes >= 100 * KB ? "medium" : "low",
                    params("count", plain.size(), "bytes", bytes), save, transferMs(save, BPS_4G), plain));
        }

        List<ScanItem> noCache = page.stream().filter(PageResourceAnalyzer::cacheableStatic)
                .filter(i -> i.cacheTtlS == null || i.cacheTtlS < CACHE_OK_S).toList();
        if (!noCache.isEmpty()) {
            out.add(rec("NO_CACHE", noCache.size() >= 10 ? "medium" : "low", params("count", noCache.size()), null, null, noCache));
        }

        heavy(out, "HEAVY_JS", page, JS, HEAVY_JS_BYTES, Integer.MAX_VALUE, 3 * HEAVY_JS_BYTES);
        heavy(out, "HEAVY_CSS", page, CSS, HEAVY_CSS_BYTES, Integer.MAX_VALUE, 3 * HEAVY_CSS_BYTES);
        heavy(out, "HEAVY_FONTS", page, FONT, HEAVY_FONT_BYTES, HEAVY_FONT_COUNT, Long.MAX_VALUE);

        List<ScanItem> third = page.stream().filter(i -> i.thirdParty).toList();
        long thirdBytes = sum(third);
        double thirdShare = pct1(thirdBytes, pageBytes);
        if (thirdBytes >= THIRD_PARTY_MIN_BYTES && thirdShare >= THIRD_PARTY_SHARE) {
            Map<String, Long> byHost = new HashMap<>();
            for (ScanItem i : third) byHost.merge(host(i.url), i.bytes, Long::sum);
            List<String> hostList = byHost.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(5).map(Map.Entry::getKey).toList();
            Map<String, Object> p = params("bytes", thirdBytes, "share_pct", thirdShare, "hosts", byHost.size());
            p.put("host_list", hostList);
            out.add(rec("THIRD_PARTY_HEAVY", thirdShare >= 50 ? "high" : "medium", p, null, null, third));
        }

        List<ScanItem> slow = page.stream().filter(i -> i.durationMs > SLOW_OBJECT_MS).toList();
        if (!slow.isEmpty()) {
            out.add(rec("SLOW_OBJECTS", slow.size() >= 5 ? "medium" : "low",
                    params("count", slow.size(), "limit_ms", SLOW_OBJECT_MS), null, null, slow));
        }

        List<ScanItem> redirected = page.stream().filter(i -> i.redirects > 0).toList();
        if (!redirected.isEmpty()) {
            out.add(rec("REDIRECTED_OBJECTS", "low", params("count", redirected.size()), null, null, redirected));
        }

        List<ScanItem> brokenLinks = new ArrayList<>();
        for (ScanItem i : docs) if (i.failed()) brokenLinks.add(i);
        for (ScanItem i : links) if (i.failed()) brokenLinks.add(i);
        if (!brokenLinks.isEmpty()) {
            out.add(rec("BROKEN_LINKS", "medium", params("count", brokenLinks.size()), null, null, brokenLinks));
        }

        List<ScanItem> bigDocs = docs.stream().filter(i -> i.bytes >= LARGE_DOCUMENT_BYTES).toList();
        if (!bigDocs.isEmpty()) {
            out.add(rec("LARGE_DOCUMENTS", "low", params("count", bigDocs.size(), "bytes", sum(bigDocs)), null, null, bigDocs));
        }

        // Kısmi (sınırlı) ölçümde "sorun yok" denmez — ölçülmeyen nesneler hakkında bir şey bilinmiyor
        if (out.isEmpty() && !capped) out.add(rec("ALL_GOOD", "low", new LinkedHashMap<>(), null, null, List.of()));
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> severityRank((String) m.get("severity")))
                .thenComparing((Map<String, Object> m) -> -longOr(m.get("saving_bytes")))
                .thenComparing((Map<String, Object> m) -> (String) m.get("code")));
        return out;
    }

    private static void heavy(List<Map<String, Object>> out, String code, List<ScanItem> page, String category,
                              long limitBytes, int limitCount, long highBytes) {
        List<ScanItem> list = page.stream().filter(i -> category.equals(i.category)).toList();
        long bytes = sum(list);
        if (bytes > limitBytes || list.size() > limitCount) {
            out.add(rec(code, bytes > highBytes ? "high" : "medium", params("bytes", bytes, "count", list.size()),
                    null, null, list));
        }
    }

    private static List<Map<String, Object>> notes(Limits lim) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(note("JS_NOT_EXECUTED", Map.of()));
        if (lim.objectsCapped()) out.add(note("CAPPED_OBJECTS", Map.of("limit", lim.maxObjects())));
        if (lim.timeCapped()) out.add(note("CAPPED_TIME", Map.of("seconds", lim.maxSeconds())));
        if (lim.bytesCapped()) out.add(note("CAPPED_BYTES", Map.of("mb", lim.maxTotalMb())));
        if (lim.excluded() > 0) out.add(note("EXCLUDES_APPLIED", Map.of("count", lim.excluded())));
        return out;
    }

    private static Map<String, Object> note(String code, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("params", params);
        return m;
    }

    /**
     * Öneri satırı. Nesne listesi ({@code about}) öneriyi üreten kuralın listesidir: {@link #FINDING_CODES}'taki kodlar o
     * nesnelere bulgu olarak yazılır ve {@code item_count} TÜM sayıdır ({@code item_urls} en çok {@value #MAX_ITEM_URLS}
     * kalır); sayfa düzeyindeki kodlarda {@code item_count} null.
     */
    private static Map<String, Object> rec(String code, String severity, Map<String, Object> params, Long savingBytes,
                                           Long savingMs, List<ScanItem> about) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("severity", severity);
        m.put("params", params);
        m.put("saving_bytes", savingBytes);
        m.put("saving_ms", savingMs);
        m.put("item_urls", about.stream().sorted(Comparator.comparingLong((ScanItem i) -> i.bytes).reversed())
                .limit(MAX_ITEM_URLS).map(i -> i.url).toList());
        boolean attach = FINDING_CODES.contains(code);
        if (attach) for (ScanItem i : about) i.findings.add(code);
        m.put("item_count", attach ? about.size() : null);
        return m;
    }

    /** Ölçülmüş görsel kazancı (bayt): telden gelen boyut − yeniden kodlanmış boyut, 0 altına inmez. */
    static long measuredSaving(ScanItem i) {
        return i.optimizedBytes == null ? 0L : Math.max(0L, i.bytes - i.optimizedBytes);
    }

    /**
     * Üçüncü taraf sunucular (sayfa yükü, en çok {@value #MAX_THIRD_PARTIES}, bayta göre): istek, bayt, yüklenemeyen
     * (karar {@code ok} değil — kırık + doğrulanamayan + engellenen) ve görülen kategoriler (sabit kategori sırasıyla).
     */
    static List<Map<String, Object>> thirdParties(List<ScanItem> page) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        Map<String, Set<String>> cats = new HashMap<>();
        for (ScanItem i : page) {
            if (!i.thirdParty) continue;
            String h = i.host != null ? i.host : host(i.url);
            long[] a = agg.computeIfAbsent(h, k -> new long[3]);
            a[0]++;
            a[1] += i.bytes;
            boolean notOk = i.verdict != null ? !ScanItem.OK.equals(i.verdict) : i.failed();
            if (notOk) a[2]++;
            cats.computeIfAbsent(h, k -> new HashSet<>()).add(i.category == null ? PageResourceDiscovery.OTHER : i.category);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        agg.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_THIRD_PARTIES)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("host", e.getKey());
                    row.put("count", e.getValue()[0]);
                    row.put("bytes", e.getValue()[1]);
                    row.put("failed", e.getValue()[2]);
                    Set<String> seen = cats.getOrDefault(e.getKey(), Set.of());
                    row.put("categories", PageResourceDiscovery.CATEGORIES.stream().filter(seen::contains).toList());
                    out.add(row);
                });
        return out;
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────

    /** Parçalı doğrusal eğri: ilk noktanın solunda ilk değer, sonun sağında son değer; 0–100'e sıkıştırılmış tamsayı. */
    static int curve(double x, double[][] pts) {
        if (x <= pts[0][0]) return clamp(pts[0][1]);
        for (int i = 1; i < pts.length; i++) {
            if (x <= pts[i][0]) {
                double x0 = pts[i - 1][0], y0 = pts[i - 1][1], x1 = pts[i][0], y1 = pts[i][1];
                return clamp(y0 + (y1 - y0) * (x - x0) / (x1 - x0));
            }
        }
        return clamp(pts[pts.length - 1][1]);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(100, Math.round(v)));
    }

    public static String letter(int grade) {
        if (grade >= 90) return "A";
        if (grade >= 80) return "B";
        if (grade >= 70) return "C";
        if (grade >= 60) return "D";
        if (grade >= 50) return "E";
        return "F";
    }

    static boolean isImage(ScanItem i) {
        return IMAGE.equals(i.category);
    }

    /** Eski görsel biçimi (JPEG / PNG / GIF / BMP) — WebP / AVIF / SVG değil. */
    static boolean legacyFormat(ScanItem i) {
        String ct = i.contentType == null ? "" : i.contentType;
        if (ct.startsWith("image/")) {
            return ct.contains("jpeg") || ct.contains("jpg") || ct.contains("png") || ct.contains("gif") || ct.contains("bmp");
        }
        String e = PageResourceDiscovery.ext(i.url);
        return Set.of("jpg", "jpeg", "png", "gif", "bmp").contains(e);
    }

    /** Sıkıştırılabilir metin: birinci taraf, başarılı, > 1 KB, HTML / CSS / JS / JSON / XML / SVG / text. */
    static boolean compressibleText(ScanItem i) {
        if (i.thirdParty || !i.ok() || i.bytes <= TEXT_MIN_BYTES) return false;
        if (HTML.equals(i.category) || CSS.equals(i.category) || JS.equals(i.category)) return true;
        String ct = i.contentType == null ? "" : i.contentType;
        return ct.startsWith("text/") || ct.contains("javascript") || ct.contains("json") || ct.contains("xml")
                || ct.contains("svg");
    }

    /** Önbelleklenebilir durağan nesne: birinci taraf, 2xx, stil / betik / görsel / yazı tipi / medya / simge. */
    static boolean cacheableStatic(ScanItem i) {
        if (i.thirdParty || i.status < 200 || i.status >= 300) return false;
        return Set.of(CSS, JS, IMAGE, FONT, MEDIA, FAVICON).contains(i.category);
    }

    static long transferMs(long bytes, double bps) {
        return bytes <= 0 ? 0 : Math.round(bytes * 1000.0 / bps);
    }

    static double pct1(long part, long whole) {
        if (whole <= 0) return 0.0;
        return Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static long sum(List<ScanItem> list) {
        long t = 0;
        for (ScanItem i : list) t += i.bytes;
        return t;
    }

    private static int severityRank(String s) {
        return switch (s == null ? "" : s) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    private static long longOr(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    static String host(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** Analizde geçen kod adları (i18n kapısı için). */
    public static final Set<String> RECOMMENDATION_CODES = Set.copyOf(new HashSet<>(List.of(
            "LARGE_IMAGES", "MODERN_IMAGE_FORMAT", "OVERSIZED_IMAGE_DIMENSIONS", "OVERSIZED_PAGE", "TOO_MANY_REQUESTS",
            "SLOW_SERVER", "UNCOMPRESSED_TEXT", "NO_CACHE", "BROKEN_RESOURCES", "HEAVY_JS", "HEAVY_CSS", "HEAVY_FONTS",
            "THIRD_PARTY_HEAVY", "SLOW_OBJECTS", "REDIRECTED_OBJECTS", "BROKEN_LINKS", "LARGE_DOCUMENTS", "ALL_GOOD")));
    public static final Set<String> NOTE_CODES =
            Set.of("JS_NOT_EXECUTED", "CAPPED_OBJECTS", "CAPPED_TIME", "CAPPED_BYTES", "EXCLUDES_APPLIED");
}
