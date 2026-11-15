package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.page.scan.PageResourceAnalyzerTest.KB;
import static com.sitemonitor.service.page.scan.PageResourceAnalyzerTest.get;
import static com.sitemonitor.service.page.scan.PageResourceAnalyzerTest.html;
import static com.sitemonitor.service.page.scan.PageResourceAnalyzerTest.item;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Algoritma 4 (2026-11-15) — önbellek sınıflarının nota, önerilere, nesne bulgularına ve {@code waste.caching} bloğuna
 * etkisi. Kullanıcı kararı: her ziyarette doğrulanan ve ömrü verilmemiş ama {@code Last-Modified} taşıyan nesne YARIM puan.
 */
class PageResourceCacheClassesTest {

    static ScanItem cls(String url, String cat, String cacheClass, Long ttl, boolean validator) {
        ScanItem i = item(url, cat, 20 * KB, 50);
        i.cacheClass = cacheClass;
        i.cacheTtlS = ttl;
        i.validator = validator;
        return i;
    }

    static Map<String, Map<String, Object>> recsByCode(PageResourceAnalyzer.Result r) {
        List<Map<String, Object>> recs = get(r.analysis(), "recommendations");
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (Map<String, Object> m : recs) by.put((String) m.get("code"), m);
        return by;
    }

    static Map<String, Object> score(PageResourceAnalyzer.Result r, String key) {
        List<Map<String, Object>> scores = get(r.analysis(), "scores");
        return scores.stream().filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("not: doğrulanan / sezgisel / doğrulayıcılı kısa 0,5; uzun saklanan 1; ömürsüz / no-store / doğrulayıcısız kısa 0")
    void cachingScore() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(100, 300, 20 * KB));
        items.add(cls("https://a.com/a.css", "CSS", CachePolicy.STORED, 86_400L, true));       // 1,0
        items.add(cls("https://a.com/b.css", "CSS", CachePolicy.STORED, 7_200L, false));       // 0,5
        items.add(cls("https://a.com/c.js", "JS", CachePolicy.REVALIDATE, -1L, true));         // 0,5
        items.add(cls("https://a.com/d.js", "JS", CachePolicy.HEURISTIC, null, true));         // 0,5
        items.add(cls("https://a.com/e.png", "IMAGE", CachePolicy.SHORT, 600L, true));         // 0,5
        items.add(cls("https://a.com/f.png", "IMAGE", CachePolicy.SHORT, 600L, false));        // 0
        items.add(cls("https://a.com/g.png", "IMAGE", CachePolicy.NONE, null, false));         // 0
        items.add(cls("https://a.com/h.png", "IMAGE", CachePolicy.NO_STORE, -1L, true));       // 0
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        // (1 + 0,5 + 0,5 + 0,5 + 0,5) / 8 = 3 / 8 = %37,5 → 38
        assertThat(score(r, "caching")).containsEntry("score", 38).containsEntry("value", 38);
        assertThat(r.analysis()).containsEntry("algorithm_version", 4);

        // Aynı nesneler algoritma 3 kuralıyla (sınıfsız): yalnız ömür → (1 + 0,5) / 8 = %18,75 → 19 — sürüm artışının nedeni
        for (ScanItem i : items) i.cacheClass = null;
        PageResourceAnalyzer.Result old = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        assertThat(score(old, "caching")).containsEntry("score", 19);
    }

    @Test
    @DisplayName("öneriler: sınıf başına kod (NO_CACHE yok), nesnelere bulgu, önem: doğrulanan / sezgisel düşük, ≥ 10 orta")
    void recommendations() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(100, 300, 20 * KB));
        for (int k = 0; k < 10; k++) items.add(cls("https://a.com/ns" + k + ".js", "JS", CachePolicy.NO_STORE, -1L, false));
        for (int k = 0; k < 3; k++) items.add(cls("https://a.com/n" + k + ".js", "JS", CachePolicy.NONE, null, false));
        for (int k = 0; k < 12; k++) items.add(cls("https://a.com/r" + k + ".png", "IMAGE", CachePolicy.REVALIDATE, -1L, true));
        items.add(cls("https://a.com/h.css", "CSS", CachePolicy.HEURISTIC, null, true));
        items.add(cls("https://a.com/s.css", "CSS", CachePolicy.SHORT, 120L, false));
        items.add(cls("https://a.com/ok.css", "CSS", CachePolicy.STORED, 86_400L, true));
        ScanItem third = cls("https://cdn.x.net/t.js", "JS", CachePolicy.NONE, null, false);
        third.thirdParty = true;
        items.add(third);
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        Map<String, Map<String, Object>> by = recsByCode(r);
        assertThat(by).doesNotContainKey("NO_CACHE");
        assertThat(by.get("CACHE_NO_STORE")).containsEntry("severity", "medium").containsEntry("item_count", 10)
                .containsEntry("finding", "CACHE_NO_STORE");
        assertThat(by.get("CACHE_NONE")).containsEntry("severity", "low").containsEntry("item_count", 3);
        Map<String, Object> noneParams = get(by.get("CACHE_NONE"), "params");
        assertThat(noneParams).isEqualTo(Map.of("count", 3));
        assertThat(by.get("CACHE_REVALIDATE")).as("12 nesne ama doğrulanan hep düşük").containsEntry("severity", "low")
                .containsEntry("item_count", 12);
        assertThat((List<?>) by.get("CACHE_REVALIDATE").get("item_urls")).hasSize(10);
        assertThat(by.get("CACHE_HEURISTIC")).containsEntry("severity", "low").containsEntry("item_count", 1);
        assertThat(by.get("CACHE_SHORT")).containsEntry("severity", "low").containsEntry("item_count", 1);
        assertThat(PageResourceAnalyzer.RECOMMENDATION_CODES).containsAll(by.keySet());
        assertThat(items.get(1).findings).containsExactly("CACHE_NO_STORE");
        assertThat(items.get(14).findings).containsExactly("CACHE_REVALIDATE");
        assertThat(items.stream().filter(i -> "https://a.com/ok.css".equals(i.url)).findFirst().orElseThrow().findings)
                .as("uzun saklanan: bulgu yok").isEmpty();
        assertThat(third.findings).as("üçüncü taraf puana / öneriye girmez").isEmpty();
        assertThat(PageResourceAnalyzer.FINDING_CODES).containsAll(PageResourceAnalyzer.CACHE_CLASS_CODE.values());

        // NO_VALIDATOR: none / no_store nesneler girmez (onlar CACHE_NONE / CACHE_NO_STORE'da); doğrulayıcısız kısa girer
        assertThat(items.get(1).findings).doesNotContain("NO_VALIDATOR");
        assertThat(items.stream().filter(i -> "https://a.com/s.css".equals(i.url)).findFirst().orElseThrow().findings)
                .contains("CACHE_SHORT", "NO_VALIDATOR");
    }

    @Test
    @DisplayName("sınıfsız eski / test nesnesi: ömürden none / short (eski NO_CACHE kümesi); not ve öneri kapsamı değişmez")
    void legacyItemsWithoutClass() {
        List<ScanItem> items = new ArrayList<>();
        items.add(html(100, 300, 20 * KB));
        ScanItem a = item("https://a.com/a.js", "JS", 20 * KB, 50);
        a.cacheTtlS = null;
        ScanItem b = item("https://a.com/b.js", "JS", 20 * KB, 50);
        b.cacheTtlS = 600L;
        ScanItem c = item("https://a.com/c.js", "JS", 20 * KB, 50);
        c.cacheTtlS = 86_400L;
        items.addAll(List.of(a, b, c));
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        Map<String, Map<String, Object>> by = recsByCode(r);
        assertThat(by.get("CACHE_NONE")).containsEntry("item_count", 1);
        assertThat(by.get("CACHE_SHORT")).containsEntry("item_count", 1);
        assertThat(score(r, "caching")).containsEntry("score", 33);
        Map<String, Object> caching = get(get(r.analysis(), "waste"), "caching");
        assertThat(caching).as("sınıfsız girdide sınıf sayıları yazılmaz").doesNotContainKey("classes");
    }

    @Test
    @DisplayName("waste.caching: sınıf sayıları puanla aynı küme; ikinci ziyaret: doğrulanan istek ama gövde yok, no-store tüm gövde")
    void wasteCaching() {
        List<ScanItem> items = new ArrayList<>();
        ScanItem h = html(100, 300, 40 * KB);
        h.cacheClass = CachePolicy.NONE;
        h.cacheTtlS = null;
        items.add(h);                                                                                       // istek + 40 KB
        items.add(cls("https://a.com/a.css", "CSS", CachePolicy.STORED, 86_400L, false));                  // —
        items.add(cls("https://a.com/b.js", "JS", CachePolicy.REVALIDATE, -1L, true));                     // istek
        ScanItem heu = cls("https://a.com/c.js", "JS", CachePolicy.HEURISTIC, null, true);
        heu.cacheHeuristicS = 30L * 86_400;
        items.add(heu);                                                                                     // —
        items.add(cls("https://a.com/d.png", "IMAGE", CachePolicy.NO_STORE, -1L, true));                   // istek + 20 KB
        items.add(cls("https://a.com/e.png", "IMAGE", CachePolicy.SHORT, 600L, true));                     // istek
        items.add(cls("https://a.com/f.png", "IMAGE", CachePolicy.SHORT, 600L, false));                    // istek + 20 KB
        ScanItem third = cls("https://cdn.x.net/t.js", "JS", CachePolicy.NONE, null, false);
        third.thirdParty = true;
        items.add(third);                                                                                   // istek + 20 KB
        PageResourceAnalyzer.Result r = PageResourceAnalyzer.analyze(items, PageResourceAnalyzer.Limits.NONE);
        Map<String, Object> caching = get(get(r.analysis(), "waste"), "caching");
        Map<String, Integer> classes = get(caching, "classes");
        assertThat(classes).isEqualTo(Map.of("stored", 1, "short", 2, "revalidate", 1, "heuristic", 1, "none", 0, "no_store", 1));
        assertThat(classes.keySet()).containsExactlyElementsOf(CachePolicy.CLASSES);
        assertThat(caching).containsEntry("repeat_visit_bytes", 100 * KB).containsEntry("repeat_visit_requests", 6);
    }
}
