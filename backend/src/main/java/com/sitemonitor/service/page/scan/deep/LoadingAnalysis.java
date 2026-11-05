package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.scan.PageResourceDiscovery;
import com.sitemonitor.service.page.scan.ScanItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A — Yükleme ve ağ (2026-11, kaynak analizi algoritma 3) → {@code analysis.loading}. SAF.
 *
 * <p><b>Oluşturmayı engelleyen</b> (statik HTML'den, sezgisel): {@code <head>}'deki async / defer / module olmayan betik
 * ({@code no_async_defer}), {@code <head>}'deki medyasız ya da {@code print} dışı medyalı stil ({@code no_media}) ve bu
 * stillerin {@code @import} zinciri ({@code import}). Tahmini ilk boyama: HTML'nin bitişi + engelleyen kaynakların şelaledeki
 * bitişi (yükleme tahminiyle aynı şelale — önce {@code PageResourceAnalyzer.simulate} çalışmış olmalı).
 */
public final class LoadingAnalysis {

    /** Yeni bağlantı ipucu önerisi: engelleyen kaynağı ya da en az bu kadar isteği olan host. */
    static final int PRECONNECT_MIN_REQUESTS = 3;
    static final int MANY_HOSTS = 10;
    static final int HTTP1_MEDIUM_REQUESTS = 30;
    /** {@link ScanItem#via} değeri: nesne vekil üzerinden çekildi. */
    static final String PROXY = "proxy";
    static final long BLOCKING_HIGH_BYTES = 300 * 1024L, BLOCKING_MEDIUM_BYTES = 100 * 1024L;

    /**
     * Bölüm + engelleyen sayı / bayt (performans notu bileşeni) + tahmini ilk boyama. {@code blockingKnown} false = HTML
     * olguları yok (ayrıştırılamadı): engelleyenler BİLİNMİYOR — not bileşeni puanlanmaz, {@code render_blocking} null.
     */
    public record Result(DeepSection section, int blockingCount, long blockingBytes, long firstPaintMs, boolean blockingKnown) {}

    private LoadingAnalysis() {}

    /**
     * @param items tüm nesneler (0 = HTML); {@code startMs} yazılmış olmalı
     * @param page  sayfa yükü nesneleri (indirilmeyenler hariç, HTML dahil)
     */
    public static Result build(List<ScanItem> items, List<ScanItem> page, HtmlFacts f, DeepContext ctx) {
        ScanItem html = items.get(0);
        Map<String, Object> json = new LinkedHashMap<>();
        List<DeepRec> recs = new ArrayList<>();

        // ── Oluşturmayı engelleyenler ─────────────────────────────────────────────────────
        Map<String, HtmlFacts.ScriptTag> scripts = new HashMap<>();
        Map<String, HtmlFacts.StyleTag> styles = new HashMap<>();
        if (f != null) {
            for (HtmlFacts.ScriptTag t : f.scripts) scripts.putIfAbsent(t.url(), t);
            for (HtmlFacts.StyleTag t : f.stylesheets) styles.putIfAbsent(t.url(), t);
        }
        List<ScanItem> blocking = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i < items.size(); i++) {
            ScanItem it = items.get(i);
            it.renderBlocking = false;
            if (f == null || !it.pageLoad || it.skipped()) continue;   // olgu yoksa engelleyen bilinmez (inceleme D-1)
            String reason = null, kind = null;
            if (PageResourceDiscovery.JS.equals(it.category) && !it.fromCss) {
                HtmlFacts.ScriptTag t = scripts.get(it.url);
                if (t != null && t.inHead() && !t.async() && !t.defer() && !t.module()) { kind = "script"; reason = "no_async_defer"; }
            } else if (PageResourceDiscovery.CSS.equals(it.category)) {
                if (!it.fromCss) {
                    HtmlFacts.StyleTag t = styles.get(it.url);
                    if (t != null && t.inHead() && (t.media() == null || !"print".equalsIgnoreCase(t.media().trim()))) {
                        kind = "stylesheet";
                        reason = "no_media";
                    }
                } else if (blockingAncestor(items, it)) {
                    kind = "css_import";
                    reason = "import";
                }
            }
            if (kind == null) continue;
            it.renderBlocking = true;
            blocking.add(it);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("url", DeepUrls.mask(it.url));
            row.put("kind", kind);
            row.put("bytes", it.bytes);
            row.put("duration_ms", it.durationMs);
            row.put("reason", reason);
            rows.add(row);
        }
        long htmlEnd = Math.max(0, html.durationMs);
        long firstPaint = htmlEnd;
        for (ScanItem b : blocking) if (b.startMs != null) firstPaint = Math.max(firstPaint, b.startMs + Math.max(0, b.durationMs));
        long blockingBytes = DeepSection.bytes(blocking);
        if (f != null) {
            Map<String, Object> rb = new LinkedHashMap<>();
            DeepSection.putList(rb, "items", rows);
            rb.put("count", blocking.size());
            rb.put("bytes", blockingBytes);
            rb.put("est_first_paint_ms", firstPaint);
            json.put("render_blocking", rb);
        } else {
            json.put("render_blocking", null);   // HTML olguları yok: bilinmiyor (boş liste "engelleyen yok" demek olurdu)
        }
        if (!blocking.isEmpty()) {
            String sev = blocking.size() >= 6 || blockingBytes >= BLOCKING_HIGH_BYTES ? DeepRec.HIGH
                    : blocking.size() >= 3 || blockingBytes >= BLOCKING_MEDIUM_BYTES ? DeepRec.MEDIUM : DeepRec.LOW;
            recs.add(new DeepRec("RENDER_BLOCKING_RESOURCES", sev,
                    DeepRec.params("count", blocking.size(), "bytes", blockingBytes, "est_first_paint_ms", firstPaint),
                    null, Math.max(0, firstPaint - htmlEnd), blocking, "RENDER_BLOCKING"));
        }

        // ── Kaynak ipuçları ───────────────────────────────────────────────────────────────
        Map<String, List<ScanItem>> byHost = new LinkedHashMap<>();
        for (ScanItem it : page) {
            String h = it.host != null ? it.host : DeepUrls.host(it.url);
            if (h != null) byHost.computeIfAbsent(h, k -> new ArrayList<>()).add(it);
        }
        String mainHost = html.host != null ? html.host : DeepUrls.host(html.url);
        Map<String, Object> hints = new LinkedHashMap<>();
        List<Map<String, Object>> preload = new ArrayList<>();
        List<ScanItem> unusedPreload = new ArrayList<>();
        Set<String> hinted = new HashSet<>();
        List<Map<String, Object>> preconnect = new ArrayList<>(), dnsPrefetch = new ArrayList<>();
        List<String> unusedHosts = new ArrayList<>();
        Map<String, ScanItem> itemByUrl = new HashMap<>();
        for (ScanItem it : items) itemByUrl.putIfAbsent(it.url, it);
        if (f != null) {
            for (HtmlFacts.Hint h : f.hints) {
                switch (h.rel()) {
                    case "preload", "modulepreload" -> {
                        Boolean used = preloadUsed(h, f, ctx);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("url", DeepUrls.mask(h.url()));
                        r.put("as", h.as() == null || h.as().isEmpty() ? null : h.as());
                        r.put("used", used);
                        preload.add(r);
                        if (Boolean.FALSE.equals(used)) {
                            ScanItem it = itemByUrl.get(h.url());
                            if (it != null) unusedPreload.add(it);
                        }
                    }
                    case "preconnect", "dns-prefetch" -> {
                        if (h.host() == null) continue;
                        hinted.add(h.host());
                        boolean used = byHost.containsKey(h.host());
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("host", h.host());
                        r.put("used", used);
                        ("preconnect".equals(h.rel()) ? preconnect : dnsPrefetch).add(r);
                        if (!used && !unusedHosts.contains(h.host())) unusedHosts.add(h.host());
                    }
                    default -> { }
                }
            }
        }
        List<Map<String, Object>> missing = new ArrayList<>();
        List<String> missingHosts = new ArrayList<>();
        List<ScanItem> missingItems = new ArrayList<>();
        boolean missingBlocking = false;
        Long missingSaving = null;
        for (Map.Entry<String, List<ScanItem>> e : byHost.entrySet()) {
            if (e.getKey().equals(mainHost) || hinted.contains(e.getKey())) continue;
            List<ScanItem> list = e.getValue().stream().filter(i -> !i.lazy).toList();
            long blockingHere = list.stream().filter(i -> i.renderBlocking).count();
            if (blockingHere == 0 && list.size() < PRECONNECT_MIN_REQUESTS) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("host", e.getKey());
            r.put("requests", list.size());
            r.put("bytes", DeepSection.bytes(list));
            r.put("blocking", blockingHere > 0);
            missing.add(r);
            missingHosts.add(e.getKey());
            missingItems.addAll(list);
            if (blockingHere > 0) missingBlocking = true;
            DeepContext.HostTiming t = ctx.hostTimings().get(e.getKey());
            Long setup = t == null ? null : t.setupMs();
            if (setup != null) missingSaving = missingSaving == null ? setup : Math.max(missingSaving, setup);
        }
        List<Map<String, Object>> priorities = new ArrayList<>();
        if (f != null) {
            for (HtmlFacts.Priority p : f.priorities) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("url", DeepUrls.mask(p.url()));
                r.put("value", p.value());
                priorities.add(r);
            }
        }
        DeepSection.putList(hints, "preload", preload);
        DeepSection.putList(hints, "preconnect", preconnect);
        DeepSection.putList(hints, "dns_prefetch", dnsPrefetch);
        DeepSection.putList(hints, "missing_preconnect", missing);
        DeepSection.putList(hints, "fetchpriority", priorities);
        json.put("hints", f == null ? null : hints);
        if (!missing.isEmpty()) {
            recs.add(new DeepRec("MISSING_PRECONNECT", missingBlocking ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", missing.size(), "host_list", missingHosts.stream().limit(5).toList()),
                    null, missingSaving, missingItems, null));
        }
        long unusedPreloadCount = preload.stream().filter(r -> Boolean.FALSE.equals(r.get("used"))).count();
        if (unusedPreloadCount > 0) {
            recs.add(new DeepRec("UNUSED_PRELOAD", unusedPreloadCount >= 3 ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", (int) unusedPreloadCount), null, null, unusedPreload, null));
        }
        if (!unusedHosts.isEmpty()) {
            recs.add(new DeepRec("UNUSED_PRECONNECT", DeepRec.LOW,
                    DeepRec.params("count", unusedHosts.size(), "host_list", unusedHosts.stream().limit(5).toList()),
                    null, null, List.of(), null));
        }

        // ── Host tablosu ──────────────────────────────────────────────────────────────────
        List<Map<String, Object>> hosts = new ArrayList<>();
        int http1FirstParty = 0;
        List<String> http1Hosts = new ArrayList<>();
        int http1Requests = 0;
        List<ScanItem> http1Items = new ArrayList<>();
        List<Map.Entry<String, List<ScanItem>>> sortedHosts = new ArrayList<>(byHost.entrySet());
        sortedHosts.sort(Comparator.comparingInt((Map.Entry<String, List<ScanItem>> e) -> -e.getValue().size())
                .thenComparingLong(e -> -DeepSection.bytes(e.getValue())).thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, List<ScanItem>> e : sortedHosts) {
            List<ScanItem> list = e.getValue();
            boolean third = list.stream().allMatch(i -> i.thirdParty);
            boolean proxied = list.stream().allMatch(i -> PROXY.equals(i.via));
            String version = mostCommonVersion(list);
            boolean h3 = list.stream().anyMatch(i -> i.h3Advertised);
            boolean tls = list.stream().anyMatch(i -> DeepUrls.https(i.url));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("host", e.getKey());
            r.put("third_party", third);
            r.put("requests", list.size());
            r.put("bytes", DeepSection.bytes(list));
            r.put("http_version", version);
            r.put("h3_advertised", h3);
            DeepContext.HostTiming t = ctx.hostTimings().get(e.getKey());
            if (t == null) t = DeepContext.HostTiming.notMeasured("not_measured");
            r.put("dns_ms", t.dnsMs());
            r.put("connect_ms", t.connectMs());
            r.put("tls_ms", t.tlsMs());
            r.put("measured", t.measured());
            r.put("reason", proxied ? PROXY : t.reason());
            hosts.add(r);
            if (!third && tls && "1.1".equals(version)) {
                http1FirstParty++;
                http1Hosts.add(e.getKey());
                http1Requests += list.size();
                http1Items.addAll(list);
            }
        }
        json.put("hosts", hosts.size() > DeepSection.MAX_ROWS ? new ArrayList<>(hosts.subList(0, DeepSection.MAX_ROWS)) : hosts);
        json.put("hosts_total", hosts.size());
        json.put("http1_first_party_hosts", http1FirstParty);
        if (hosts.size() > MANY_HOSTS) {
            recs.add(new DeepRec("MANY_HOSTS", hosts.size() > 2 * MANY_HOSTS ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", hosts.size(), "limit", MANY_HOSTS), null, null, List.of(), null));
        }
        if (http1FirstParty > 0) {
            recs.add(new DeepRec("HTTP1_ONLY", http1Requests >= HTTP1_MEDIUM_REQUESTS ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", http1FirstParty, "host_list", http1Hosts.stream().limit(5).toList()),
                    null, null, http1Items, null));
        }

        // ── Ağ profilleri ─────────────────────────────────────────────────────────────────
        List<NetworkProfiles.Obj> objs = new ArrayList<>();
        for (ScanItem it : page) {
            if (it == html || it.lazy) continue;
            objs.add(new NetworkProfiles.Obj(it.host != null ? it.host : DeepUrls.host(it.url), DeepUrls.https(it.url), it.bytes,
                    it.renderBlocking, cssDepth(items, it)));
        }
        json.put("profiles", NetworkProfiles.estimate(mainHost, DeepUrls.https(html.url), html.bytes, objs));
        return new Result(new DeepSection(json, recs), blocking.size(), blockingBytes, firstPaint, f != null);
    }

    /**
     * Önyükleme kullanılıyor mu: başka bir HTML öğesi ya da stil başvuruyorsa true; başvurulmuyorsa yalnız stil / görsel /
     * yazı tipi için false (betik, fetch ve bilinmeyen türü JavaScript kullanabilir → null).
     */
    static Boolean preloadUsed(HtmlFacts.Hint h, HtmlFacts f, DeepContext ctx) {
        if (h.url() == null) return null;
        if (f.referenced.contains(h.url()) || ctx.cssReferenced().contains(h.url())) return true;
        String as = h.as() == null ? "" : h.as().toLowerCase(Locale.ROOT);
        return "modulepreload".equals(h.rel()) ? null
                : as.equals("style") || as.equals("image") || as.equals("font") ? Boolean.FALSE : null;
    }

    /** Engelleyen bir stil atası var mı ({@code @import} zinciri; atalar sıra numarasıyla). */
    private static boolean blockingAncestor(List<ScanItem> items, ScanItem it) {
        ScanItem cur = it;
        for (int guard = 0; guard < 8 && cur.fromCss && cur.parent > 0 && cur.parent < items.size(); guard++) {
            ScanItem p = items.get(cur.parent);
            if (!PageResourceDiscovery.CSS.equals(p.category)) return false;
            if (p.renderBlocking) return true;
            cur = p;
        }
        return false;
    }

    /** Stil zinciri derinliği (HTML'den 0). */
    static int cssDepth(List<ScanItem> items, ScanItem it) {
        int d = 0;
        ScanItem cur = it;
        for (int guard = 0; guard < 8 && cur.fromCss && cur.parent > 0 && cur.parent < items.size(); guard++) {
            d++;
            cur = items.get(cur.parent);
        }
        return d;
    }

    /**
     * Hostun en sık HTTP sürümü; vekil üzerinden çekilen nesneler sayılmaz (sürüm vekilin değeridir — inceleme O-5), bu
     * yüzden tamamı vekilli host null döner ve {@code HTTP1_ONLY}'ye girmez.
     */
    private static String mostCommonVersion(List<ScanItem> list) {
        int v1 = 0, v2 = 0;
        for (ScanItem i : list) {
            if (PROXY.equals(i.via)) continue;
            if ("2".equals(i.httpVersion)) v2++;
            else if ("1.1".equals(i.httpVersion)) v1++;
        }
        if (v1 == 0 && v2 == 0) return null;
        return v2 >= v1 ? "2" : "1.1";
    }
}
