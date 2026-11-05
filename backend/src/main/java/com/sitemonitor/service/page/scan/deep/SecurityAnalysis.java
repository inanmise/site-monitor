package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.scan.PageResourceAnalyzer;
import com.sitemonitor.service.page.scan.PageResourceDiscovery;
import com.sitemonitor.service.page.scan.ScanItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D — Güvenlik ve uyumluluk (2026-11, kaynak analizi algoritma 3) → {@code analysis.security}. SAF.
 *
 * <p>Başlıklar ana sayfanın yanıtından ({@link SecurityHeaders}); SRI, karışık içerik ve kütüphaneler sayfa yükü nesnelerinden;
 * çerezler görülen {@code Set-Cookie}'lerden (DEĞER YOK; JavaScript'in yazdıkları görülmez). Üçüncü taraf kategorileri
 * {@link ThirdPartyCatalog}'tan; "ilk yüklemede izleme" SEZGİSELDİR (izin yönetimi görülemez). Puan {@link SecurityScore}.
 */
public final class SecurityAnalysis {

    public record Result(DeepSection section, Integer score, String letter) {}

    private SecurityAnalysis() {}

    public static Result build(ScanItem html, List<ScanItem> page, HtmlFacts f, DeepContext ctx) {
        Map<String, Object> json = new LinkedHashMap<>();
        List<DeepRec> recs = new ArrayList<>();
        boolean https = DeepUrls.https(ctx.pageUrl() != null ? ctx.pageUrl() : html.url);
        boolean headersKnown = ctx.pageHeaders() != null;

        // ── Başlıklar ─────────────────────────────────────────────────────────────────────
        List<SecurityHeaders.Header> headers = headersKnown
                ? SecurityHeaders.evaluate(ctx.pageHeaders(), f == null ? null : f.metaCsp, https) : List.of();
        List<Map<String, Object>> headerRows = new ArrayList<>();
        List<String> missingKeys = new ArrayList<>();
        for (SecurityHeaders.Header h : headers) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", h.key());
            r.put("status", h.status());
            r.put("value", h.value());
            r.put("issue", h.issue());
            headerRows.add(r);
            if (SecurityHeaders.MISSING.equals(h.status())) missingKeys.add(h.key());
        }
        if (!missingKeys.isEmpty()) {
            boolean core = missingKeys.contains("csp") || missingKeys.contains("hsts")
                    || missingKeys.contains("x_content_type_options") || missingKeys.contains("frame_protection");
            boolean both = https && missingKeys.contains("csp") && missingKeys.contains("hsts");
            recs.add(new DeepRec("SECURITY_HEADERS_MISSING", both ? DeepRec.HIGH : core ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", missingKeys.size(), "keys", missingKeys), null, null, List.of(html), null));
        }
        if (headersKnown) {
            SecurityHeaders.CspResult csp = SecurityHeaders.csp(ctx.pageHeaders(), f == null ? null : f.metaCsp);
            List<String> weak = csp.issues().stream().filter(i -> !"meta_only".equals(i)).toList();
            if (SecurityHeaders.WEAK.equals(csp.status()) && !weak.isEmpty()) {
                recs.add(new DeepRec("WEAK_CSP", DeepRec.MEDIUM, DeepRec.params("issues", weak), null, null, List.of(html), null));
            }
        }

        // ── SRI ───────────────────────────────────────────────────────────────────────────
        Map<String, Boolean> integrity = new HashMap<>();
        if (f != null) {
            for (HtmlFacts.ScriptTag t : f.scripts) integrity.putIfAbsent(t.url(), t.integrity());
            for (HtmlFacts.StyleTag t : f.stylesheets) integrity.putIfAbsent(t.url(), t.integrity());
        }
        List<Map<String, Object>> sri = new ArrayList<>();
        List<ScanItem> sriItems = new ArrayList<>();
        boolean sriScript = false;
        for (ScanItem it : page) {
            boolean js = PageResourceDiscovery.JS.equals(it.category), css = PageResourceDiscovery.CSS.equals(it.category);
            it.integrity = (js || css) && integrity.containsKey(it.url) ? integrity.get(it.url) : null;
            if (!it.thirdParty || !Boolean.FALSE.equals(it.integrity)) continue;
            sri.add(row("url", DeepUrls.mask(it.url), "kind", js ? "script" : "stylesheet"));
            sriItems.add(it);
            if (js) sriScript = true;
        }
        DeepSection.putList(json, "sri_missing", sri);
        if (!sriItems.isEmpty()) {
            recs.add(new DeepRec("MISSING_SRI", sriScript ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", sriItems.size()), null, null, sriItems, "MISSING_SRI"));
        }

        // ── Karışık içerik ────────────────────────────────────────────────────────────────
        List<Map<String, Object>> mixed = new ArrayList<>();
        List<ScanItem> mixedItems = new ArrayList<>();
        int active = 0;
        if (https) {
            for (ScanItem it : page) {
                if (it == html || !DeepUrls.http(it.url)) continue;
                mixed.add(row("url", DeepUrls.mask(it.url)));
                mixedItems.add(it);
                if (PageResourceDiscovery.JS.equals(it.category) || PageResourceDiscovery.CSS.equals(it.category)
                        || PageResourceDiscovery.IFRAME.equals(it.category)) active++;
            }
        }
        DeepSection.putList(json, "mixed_content", mixed);
        if (!mixedItems.isEmpty()) {
            recs.add(new DeepRec("MIXED_CONTENT", active > 0 ? DeepRec.HIGH : DeepRec.MEDIUM,
                    DeepRec.params("count", mixedItems.size(), "active", active), null, null, mixedItems, "MIXED_CONTENT"));
        }

        // ── Çerezler ──────────────────────────────────────────────────────────────────────
        List<Map<String, Object>> cookies = new ArrayList<>();
        List<String> insecureNames = new ArrayList<>();
        boolean insecureSession = false;
        Set<String> seenCookie = new HashSet<>();
        for (DeepContext.CookieSeen cs : ctx.cookies()) {
            CookieFacts.Cookie c = cs.cookie();
            if (!seenCookie.add(c.name() + "|" + c.host())) continue;
            List<String> issues = CookieFacts.issues(c, https);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", c.name());
            r.put("host", c.host());
            r.put("third_party", cs.thirdParty());
            r.put("secure", c.secure());
            r.put("http_only", c.httpOnly());
            r.put("same_site", c.sameSite());
            r.put("issues", issues);
            cookies.add(r);
            if (!cs.thirdParty() && CookieFacts.insecure(issues, c.name())) {
                if (!insecureNames.contains(c.name())) insecureNames.add(c.name());
                if (CookieFacts.sessionLike(c.name())
                        && (issues.contains(CookieFacts.NOT_SECURE) || issues.contains(CookieFacts.NO_HTTP_ONLY))) {
                    insecureSession = true;
                }
            }
        }
        DeepSection.putList(json, "cookies", cookies);
        if (!insecureNames.isEmpty()) {
            recs.add(new DeepRec("INSECURE_COOKIES", insecureSession ? DeepRec.HIGH : DeepRec.MEDIUM,
                    DeepRec.params("count", insecureNames.size(), "name_list", insecureNames.stream().limit(5).toList()),
                    null, null, List.of(html), null));
        }

        // ── Kütüphaneler ve bilinen açıklar ───────────────────────────────────────────────
        List<Map<String, Object>> libs = new ArrayList<>();
        List<ScanItem> vulnItems = new ArrayList<>();
        List<String> vulnNames = new ArrayList<>();
        List<String> libSeverities = new ArrayList<>();
        String maxSeverity = null;
        Set<String> seenLib = new LinkedHashSet<>();
        for (ScanItem it : page) {
            if (it.libraryName == null) continue;
            JsLibraryCatalog.Library lib = new JsLibraryCatalog.Library(it.libraryName, it.libraryVersion);
            List<JsLibraryCatalog.Vuln> vulns = JsLibraryCatalog.vulnsOf(lib);
            List<Map<String, Object>> vr = new ArrayList<>();
            String libMax = null;
            for (JsLibraryCatalog.Vuln v : vulns) {
                vr.add(row("id", v.id(), "severity", v.severity(), "kind", v.kind(), "summary", v.summary()));
                if (libMax == null || JsLibraryCatalog.severityRank(v.severity()) > JsLibraryCatalog.severityRank(libMax)) {
                    libMax = v.severity();
                }
            }
            libs.add(row("name", lib.name(), "version", lib.version(), "url", DeepUrls.mask(it.url), "vulns", vr));
            if (libMax != null) {
                vulnItems.add(it);
                String label = lib.name() + " " + lib.version();
                if (seenLib.add(label)) {
                    vulnNames.add(label);
                    libSeverities.add(libMax);
                }
                if (maxSeverity == null || JsLibraryCatalog.severityRank(libMax) > JsLibraryCatalog.severityRank(maxSeverity)) {
                    maxSeverity = libMax;
                }
            }
        }
        DeepSection.putList(json, "libraries", libs);
        json.put("catalog_date", JsLibraryCatalog.CATALOG_DATE);
        if (!vulnItems.isEmpty()) {
            recs.add(new DeepRec("VULNERABLE_LIBRARY", JsLibraryCatalog.severityRank(maxSeverity) >= 3 ? DeepRec.HIGH : DeepRec.MEDIUM,
                    DeepRec.params("count", vulnNames.size(), "name_list", vulnNames.stream().limit(5).toList(),
                            "max_severity", maxSeverity), null, null, vulnItems, "VULNERABLE_LIBRARY"));
        }

        // ── Üçüncü taraflar ve ilk yüklemede izleme ───────────────────────────────────────
        Map<String, Set<String>> catHosts = new LinkedHashMap<>();
        Map<String, long[]> catAgg = new LinkedHashMap<>();
        for (String c : ThirdPartyCatalog.CATEGORIES) { catHosts.put(c, new LinkedHashSet<>()); catAgg.put(c, new long[2]); }
        Map<String, String> trackingHosts = new LinkedHashMap<>();
        List<ScanItem> trackingItems = new ArrayList<>();
        for (ScanItem it : page) {
            if (!it.thirdParty) { it.thirdPartyCategory = null; continue; }
            String cat = ThirdPartyCatalog.categoryOf(it.url);
            it.thirdPartyCategory = cat;
            String host = it.host != null ? it.host : DeepUrls.host(it.url);
            catHosts.get(cat).add(host);
            catAgg.get(cat)[0]++;
            catAgg.get(cat)[1] += it.bytes;
            if (ThirdPartyCatalog.tracking(cat) && !it.lazy) {
                trackingHosts.putIfAbsent(host, cat);
                trackingItems.add(it);
            }
        }
        List<Map<String, Object>> cats = new ArrayList<>();
        for (String c : ThirdPartyCatalog.CATEGORIES) {
            if (catAgg.get(c)[0] == 0) continue;
            Map<String, Object> r = row("category", c);
            DeepSection.putList(r, "hosts", new ArrayList<>(catHosts.get(c)));   // ≤ 50 + hosts_total (inceleme D-10)
            r.put("requests", catAgg.get(c)[0]);
            r.put("bytes", catAgg.get(c)[1]);
            cats.add(r);
        }
        DeepSection.putList(json, "third_party_categories", cats);
        List<Map<String, Object>> tracking = new ArrayList<>();
        for (Map.Entry<String, String> e : trackingHosts.entrySet()) tracking.add(row("host", e.getKey(), "category", e.getValue()));
        DeepSection.putList(json, "tracking_on_load", tracking);
        if (!tracking.isEmpty()) {
            List<String> catList = new ArrayList<>(new LinkedHashSet<>(trackingHosts.values()));
            recs.add(new DeepRec("TRACKING_ON_LOAD", DeepRec.LOW,
                    DeepRec.params("count", tracking.size(), "categories", catList), null, null, trackingItems, null));
        }

        // ── Puan ──────────────────────────────────────────────────────────────────────────
        Integer score = null;
        String letter = null;
        if (headersKnown) {
            int mixedPassive = mixedItems.size() - active;
            score = SecurityScore.score(headers, insecureNames.size(), sriItems.size(), libSeverities, active, mixedPassive);
            letter = PageResourceAnalyzer.letter(score);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("letter", letter);
        out.put("headers", headersKnown ? headerRows : null);
        out.putAll(json);
        return new Result(new DeepSection(out, recs), score, letter);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
