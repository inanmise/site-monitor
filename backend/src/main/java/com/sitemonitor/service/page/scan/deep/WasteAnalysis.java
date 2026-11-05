package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.scan.PageResourceDiscovery;
import com.sitemonitor.service.page.scan.ScanItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B — İsraf ve önbellek (2026-11, kaynak analizi algoritma 3) → {@code analysis.waste}. SAF.
 *
 * <ul>
 *   <li><b>Tekrarlar:</b> YALNIZ içerik özeti (tel gövdesinin SHA-256'sı) aynı, ≥ {@value #DUPLICATE_MIN_BYTES} baytlık nesneler.
 *       Grubun bütün üyeleri aynı host + yoldaysa (yalnız sorgu farklı — önbellek kırıcı) {@code same_path}, değilse
 *       {@code same_content}. Özeti bilinmeyen ya da farklı olan nesneler ASLA tekrar sayılmaz (aynı yolda farklı sorgu
 *       çoğunlukla farklı içeriktir: {@code gtag/js?id=G-…} / {@code ?id=AW-…}, iki {@code css2?family=…},
 *       {@code _next/image?url=…}).</li>
 *   <li><b>Kütüphane sürümleri:</b> aynı kütüphanenin birden çok sürümü.</li>
 *   <li><b>Küçültülmemiş:</b> örnekten sezgisel ({@link AssetSample}); kazanç tahmin.</li>
 *   <li><b>Kaynak haritası:</b> {@code sourceMappingURL} + HEAD 2xx → açık.</li>
 *   <li><b>Önbellek:</b> süre kovaları, doğrulayıcısız (ETag / Last-Modified yok) birinci taraf durağan nesneler, parmak izli
 *       ama kısa önbellekli adresler, CDN isabeti, tekrar ziyaret tahmini (≥ 1 sa taze ya da doğrulayıcılı → 0 bayt).</li>
 * </ul>
 */
public final class WasteAnalysis {

    static final long DUPLICATE_MIN_BYTES = 1024;
    static final long HEAVY_HTML_BYTES = 150 * 1024L, HEAVY_HTML_HIGH = 500 * 1024L;
    static final int LARGE_DOM_NODES = 1500, LARGE_DOM_HIGH = 3000, LARGE_DOM_DEPTH = 32;
    static final long DATA_URI_BYTES = 100 * 1024L, DATA_URI_MEDIUM = 300 * 1024L;
    static final long CACHE_FRESH_S = 3_600, CACHE_DAY_S = 86_400, CACHE_WEEK_S = 604_800;
    static final long DUP_MEDIUM_BYTES = 100 * 1024L, MINIFY_MEDIUM_BYTES = 50 * 1024L;
    static final int MAX_URLS = 10;

    public record Result(DeepSection section) {}

    private WasteAnalysis() {}

    public static Result build(ScanItem html, List<ScanItem> page, HtmlFacts f, DeepContext ctx) {
        Map<String, Object> json = new LinkedHashMap<>();
        List<DeepRec> recs = new ArrayList<>();

        // ── Tekrarlar ─────────────────────────────────────────────────────────────────────
        List<Map<String, Object>> dups = new ArrayList<>();
        Set<ScanItem> dupItems = new LinkedHashSet<>();
        long wasted = 0;
        Map<String, List<ScanItem>> byHash = new LinkedHashMap<>();
        for (ScanItem it : page) {
            if (it == html || !it.ok() || it.contentHash == null || it.bytes < DUPLICATE_MIN_BYTES) continue;
            byHash.computeIfAbsent(it.contentHash, k -> new ArrayList<>()).add(it);
        }
        for (List<ScanItem> g : byHash.values()) {
            if (g.size() < 2) continue;
            long w = DeepSection.bytes(g) - g.stream().mapToLong(i -> i.bytes).max().orElse(0);
            dups.add(dup(samePath(g) ? "same_path" : "same_content", g, w));
            wasted += w;
            dupItems.addAll(g);
        }
        DeepSection.putList(json, "duplicates", dups);
        if (!dups.isEmpty()) {
            recs.add(new DeepRec("DUPLICATE_RESOURCES", wasted >= DUP_MEDIUM_BYTES ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", dups.size(), "bytes", wasted), wasted, null, new ArrayList<>(dupItems), "DUPLICATE"));
        }

        // ── Kütüphane sürümleri ───────────────────────────────────────────────────────────
        Map<String, Set<String>> versions = new LinkedHashMap<>();
        Map<String, List<ScanItem>> libItems = new LinkedHashMap<>();
        for (ScanItem it : page) {
            if (it.libraryName == null) continue;
            libItems.computeIfAbsent(it.libraryName, k -> new ArrayList<>()).add(it);
            if (it.libraryVersion != null) versions.computeIfAbsent(it.libraryName, k -> new LinkedHashSet<>()).add(it.libraryVersion);
        }
        List<Map<String, Object>> libs = new ArrayList<>();
        List<ScanItem> multiLibItems = new ArrayList<>();
        List<String> libNames = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : versions.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", e.getKey());
            r.put("versions", new ArrayList<>(e.getValue()));
            r.put("urls", libItems.get(e.getKey()).stream().limit(MAX_URLS).map(i -> DeepUrls.mask(i.url)).toList());
            libs.add(r);
            libNames.add(e.getKey());
            multiLibItems.addAll(libItems.get(e.getKey()));
        }
        DeepSection.putList(json, "libraries", libs);
        if (!libs.isEmpty()) {
            recs.add(new DeepRec("DUPLICATE_LIBRARIES", DeepRec.MEDIUM,
                    DeepRec.params("count", libs.size(), "name_list", libNames.stream().limit(5).toList()), null, null,
                    multiLibItems, null));
        }

        // ── Küçültülmemiş ─────────────────────────────────────────────────────────────────
        List<Map<String, Object>> unmin = new ArrayList<>();
        List<ScanItem> unminItems = new ArrayList<>();
        long unminBytes = 0, unminSaving = 0;
        for (ScanItem it : page) {
            if (!Boolean.FALSE.equals(it.minified)) continue;
            boolean js = PageResourceDiscovery.JS.equals(it.category);
            long save = AssetSample.estimatedSaving(it.bytes, js);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("url", DeepUrls.mask(it.url));
            r.put("bytes", it.bytes);
            r.put("est_saving_bytes", save);
            unmin.add(r);
            unminItems.add(it);
            unminBytes += it.bytes;
            unminSaving += save;
        }
        unmin.sort(Comparator.comparingLong((Map<String, Object> m) -> -((Number) m.get("bytes")).longValue()));
        DeepSection.putList(json, "unminified", unmin);
        if (!unmin.isEmpty()) {
            recs.add(new DeepRec("UNMINIFIED_ASSETS", unminSaving >= MINIFY_MEDIUM_BYTES ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", unmin.size(), "bytes", unminBytes), unminSaving, null, unminItems, "UNMINIFIED"));
        }

        // ── Kaynak haritaları ─────────────────────────────────────────────────────────────
        List<Map<String, Object>> maps = new ArrayList<>();
        List<ScanItem> exposedItems = new ArrayList<>();
        for (ScanItem it : page) {
            if (it.sourceMap == null) continue;
            Boolean exposed = ctx.sourceMaps().get(it.sourceMap);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("url", DeepUrls.mask(it.url));
            r.put("map_url", DeepUrls.mask(it.sourceMap));
            r.put("exposed", exposed);
            maps.add(r);
            if (Boolean.TRUE.equals(exposed)) exposedItems.add(it);
        }
        DeepSection.putList(json, "source_maps", maps);
        if (!exposedItems.isEmpty()) {
            recs.add(new DeepRec("SOURCE_MAP_EXPOSED", DeepRec.LOW, DeepRec.params("count", exposedItems.size()), null, null,
                    exposedItems, "SOURCE_MAP_EXPOSED"));
        }

        // ── HTML ──────────────────────────────────────────────────────────────────────────
        if (f != null) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("html_bytes", f.htmlBytes);
            h.put("inline_script_bytes", f.inlineScriptBytes);
            h.put("inline_style_bytes", f.inlineStyleBytes);
            h.put("dom_nodes", f.domNodes);
            h.put("max_depth", f.maxDepth);
            h.put("data_uri_count", f.dataUriCount);
            h.put("data_uri_bytes", f.dataUriBytes);
            json.put("html", h);
            if (f.htmlBytes > HEAVY_HTML_BYTES) {
                recs.add(new DeepRec("HEAVY_HTML", f.htmlBytes > HEAVY_HTML_HIGH ? DeepRec.HIGH : DeepRec.MEDIUM,
                        DeepRec.params("bytes", f.htmlBytes, "inline_bytes", f.inlineScriptBytes + f.inlineStyleBytes,
                                "limit_kb", HEAVY_HTML_BYTES / 1024), null, null, List.of(html), null));
            }
            if (f.domNodes > LARGE_DOM_NODES || f.maxDepth > LARGE_DOM_DEPTH) {
                String sev = f.domNodes > LARGE_DOM_HIGH ? DeepRec.HIGH : f.domNodes > LARGE_DOM_NODES ? DeepRec.MEDIUM : DeepRec.LOW;
                recs.add(new DeepRec("LARGE_DOM", sev, DeepRec.params("nodes", f.domNodes, "depth", f.maxDepth,
                        "limit", LARGE_DOM_NODES), null, null, List.of(html), null));
            }
            if (f.dataUriBytes > DATA_URI_BYTES) {
                recs.add(new DeepRec("INLINE_DATA_URI", f.dataUriBytes > DATA_URI_MEDIUM ? DeepRec.MEDIUM : DeepRec.LOW,
                        DeepRec.params("count", f.dataUriCount, "bytes", f.dataUriBytes), null, null, List.of(html), null));
            }
        } else {
            json.put("html", null);
        }

        // ── Önbellek ──────────────────────────────────────────────────────────────────────
        Map<String, Integer> buckets = new LinkedHashMap<>();
        for (String b : List.of("none", "lt_1h", "lt_1d", "lt_7d", "ge_7d")) buckets.put(b, 0);
        List<ScanItem> noValidator = new ArrayList<>();
        List<Map<String, Object>> versionedShort = new ArrayList<>();
        List<ScanItem> versionedItems = new ArrayList<>();
        int hit = 0, miss = 0, unknown = 0;
        long repeat = 0, total = 0;
        for (ScanItem it : page) {
            total += it.bytes;
            String cdn = it.headers == null ? null : it.headers.get("cdn_cache");
            if ("hit".equals(cdn)) hit++;
            else if ("miss".equals(cdn)) miss++;
            else unknown++;
            boolean fresh = it.cacheTtlS != null && it.cacheTtlS >= CACHE_FRESH_S;
            if (!fresh && !it.validator) repeat += it.bytes;
            if (it == html || !staticObject(it)) continue;
            Long ttl = it.cacheTtlS;
            String b = ttl == null || ttl <= 0 ? "none" : ttl < CACHE_FRESH_S ? "lt_1h" : ttl < CACHE_DAY_S ? "lt_1d"
                    : ttl < CACHE_WEEK_S ? "lt_7d" : "ge_7d";
            buckets.merge(b, 1, Integer::sum);
            if (!it.thirdParty && !it.validator && (ttl == null || ttl < CACHE_DAY_S)) noValidator.add(it);
            if (DeepUrls.versioned(it.url) && (ttl == null || ttl < CACHE_WEEK_S)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("url", DeepUrls.mask(it.url));
                r.put("ttl_s", ttl == null ? null : Math.max(-1, ttl));
                versionedShort.add(r);
                versionedItems.add(it);
            }
        }
        Map<String, Object> caching = new LinkedHashMap<>();
        caching.put("buckets", buckets);
        caching.put("no_validator", noValidator.size());
        DeepSection.putList(caching, "versioned_short", versionedShort);
        Map<String, Object> cdn = new LinkedHashMap<>();
        cdn.put("hit", hit);
        cdn.put("miss", miss);
        cdn.put("unknown", unknown);
        caching.put("cdn", cdn);
        caching.put("repeat_visit_bytes", repeat);
        caching.put("repeat_visit_pct", total <= 0 ? 0.0 : Math.round(repeat * 1000.0 / total) / 10.0);
        json.put("caching", caching);
        if (!versionedShort.isEmpty()) {
            recs.add(new DeepRec("SHORT_CACHE_VERSIONED", versionedShort.size() >= 10 ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", versionedShort.size()), null, null, versionedItems, null));
        }
        if (!noValidator.isEmpty()) {
            recs.add(new DeepRec("NO_VALIDATOR", DeepRec.LOW, DeepRec.params("count", noValidator.size()), null, null,
                    noValidator, "NO_VALIDATOR"));
        }
        return new Result(new DeepSection(json, recs));
    }

    /** Durağan nesne: 2xx stil / betik / görsel / yazı tipi / medya / simge. */
    static boolean staticObject(ScanItem i) {
        if (i.status < 200 || i.status >= 300) return false;
        return Set.of(PageResourceDiscovery.CSS, PageResourceDiscovery.JS, PageResourceDiscovery.IMAGE,
                PageResourceDiscovery.FONT, PageResourceDiscovery.MEDIA, PageResourceDiscovery.FAVICON).contains(i.category);
    }

    /** Grubun bütün üyeleri aynı host + yolda mı (yalnız sorgu farklı — aynı içerik önbellek kırıcıyla tekrar çekilmiş)? */
    static boolean samePath(List<ScanItem> g) {
        String key = null;
        for (ScanItem it : g) {
            String host = it.host != null ? it.host : DeepUrls.host(it.url);
            String k = host + DeepUrls.path(it.url);
            if (key == null) key = k;
            else if (!key.equals(k)) return false;
        }
        return key != null;
    }

    private static Map<String, Object> dup(String kind, List<ScanItem> g, long wasted) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", kind);
        r.put("urls", g.stream().limit(MAX_URLS).map(i -> DeepUrls.mask(i.url)).toList());
        r.put("bytes_wasted", wasted);
        return r;
    }
}
