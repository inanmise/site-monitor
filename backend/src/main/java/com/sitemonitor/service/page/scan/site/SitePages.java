package com.sitemonitor.service.page.scan.site;

import com.sitemonitor.service.page.scan.PageResourceDiscovery;
import com.sitemonitor.service.page.scan.deep.DeepUrls;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Çok sayfalı kaynak analizi (prs3 G, 2026-11) — saf kurallar: hangi iç sayfalar taranır, sayfa sonuçlarından
 * {@code analysis.site} bölümü nasıl kurulur. Ağ / veritabanı yok; tarama motoru
 * ({@code PageResourceScanService}) sayfaları ölçüp buraya verir.
 *
 * <p><b>Sayfa seçimi</b> ({@link #select}): ana sayfanın bağlantı adayları ({@code links} — keşif sırası = DOM sırası, parçasız,
 * tekil, izlemenin hariç tutma desenleri uygulanmış) içinden ana sayfayla AYNI şema + host + bağlantı noktası olan, uzantısız ya
 * da {@code .html} / {@code .htm} sayfalar; ana sayfanın kendisi (yönlendirme sonrası adresi dahil) ve yalnız sondaki {@code /}
 * farkı olan kopyalar elenir. En çok {@code max} adres.
 *
 * <p><b>Ortak / tekil bayt</b>: yalnız tamamlanan ({@link #DONE}) sayfaların sayfa yükü nesneleri sayılır (indirilmeyenler hariç;
 * belge / bağlantı yoklamaları sayfa yükü değildir). Bir nesne (parçasız adres) ≥ 2 sayfada yükleniyorsa ORTAK'tır. Sayfanın
 * {@code unique_bytes}'ı = kendi HTML'i + yalnız o sayfanın yüklediği nesneler; {@code shared_count} = o sayfanın yüklediği ortak
 * nesne sayısı. {@code totals.total_unique_bytes} = sitenin tamamı, her nesne BİR kez (HTML'ler dahil). Bitmeyen sayfada bu
 * değerler null (bilinmiyor ≠ 0).
 */
public final class SitePages {

    /**
     * Sayfa durumu (tel değeri, küçük harf — arayüz sözleşmesi): tamamlandı / sayfa açılamadı / bütçe (süre, bayt, nesne)
     * dolduğu için taranmadı.
     */
    public static final String DONE = "ok", FAILED = "failed", SKIPPED = "skipped";
    /** {@code shared} listesinin tavanı. */
    public static final int MAX_SHARED = 50;
    /** Sayfa başına öne çıkan öneri kodu sayısı. */
    public static final int TOP_RECS = 5;
    /** Taranacak sayfa uzantıları ({@code ""} = uzantısız). */
    static final Set<String> PAGE_EXT = Set.of("", "html", "htm");
    /** Tek adresin en uzun hali (keşifle aynı tavan). */
    static final int MAX_URL_CHARS = 4096;

    private SitePages() {}

    // ── Sayfa seçimi ────────────────────────────────────────────────────────────────────────

    /**
     * Taranacak iç sayfalar (ana sayfa hariç), DOM sırasıyla.
     *
     * @param links    ana sayfanın bağlantı adaylarının adresleri (keşif sırası)
     * @param mainUrl  izlemenin adresi
     * @param finalUrl ana sayfanın yönlendirme sonrası adresi (null = yok) — host / şema bundan alınır
     * @param exclude  izlemenin hariç tutma yüklemi (null = yok; keşifte zaten uygulanır, burada savunma)
     * @param max      en çok kaç adres (≤ 0 → boş)
     */
    public static List<String> select(List<String> links, String mainUrl, String finalUrl, Predicate<String> exclude, int max) {
        List<String> out = new ArrayList<>();
        if (links == null || links.isEmpty() || max <= 0) return out;
        Origin origin = Origin.of(finalUrl != null && !finalUrl.isBlank() ? finalUrl : mainUrl);
        if (origin == null) return out;
        Set<String> seen = new HashSet<>();
        addSelf(seen, mainUrl);
        addSelf(seen, finalUrl);
        for (String u : links) {
            if (out.size() >= max) break;
            if (u == null || u.isBlank() || u.length() > MAX_URL_CHARS) continue;
            String k = PageResourceDiscovery.key(u);
            Origin o = Origin.of(k);
            if (o == null || !o.sameAs(origin)) continue;
            if (!PAGE_EXT.contains(PageResourceDiscovery.ext(k))) continue;
            String norm = normalized(k);
            if (norm == null || !seen.add(norm)) continue;
            if (exclude != null && safeTest(exclude, k)) continue;
            out.add(k);
        }
        return out;
    }

    private static void addSelf(Set<String> seen, String url) {
        if (url == null || url.isBlank()) return;
        String n = normalized(PageResourceDiscovery.key(url));
        if (n != null) seen.add(n);
    }

    private static boolean safeTest(Predicate<String> p, String u) {
        try {
            return p.test(u);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Tekilleştirme biçimi: küçük harf şema + host, etkin bağlantı noktası, boş yol → "/", sondaki "/" atılmış yol, sorgu aynen. */
    static String normalized(String url) {
        try {
            URI u = URI.create(url);
            Origin o = Origin.of(url);
            if (o == null) return null;
            String path = u.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String q = u.getRawQuery();
            return o.scheme + "://" + o.host + ":" + o.port + path + (q == null ? "" : "?" + q);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Şema + host + etkin bağlantı noktası. */
    record Origin(String scheme, String host, int port) {
        static Origin of(String url) {
            if (url == null) return null;
            try {
                URI u = URI.create(url);
                String s = u.getScheme();
                String h = u.getHost();
                if (s == null || h == null || h.isBlank()) return null;
                s = s.toLowerCase(Locale.ROOT);
                if (!s.equals("http") && !s.equals("https")) return null;
                int p = u.getPort() >= 0 ? u.getPort() : (s.equals("https") ? 443 : 80);
                return new Origin(s, h.toLowerCase(Locale.ROOT), p);
            } catch (RuntimeException e) {
                return null;
            }
        }

        boolean sameAs(Origin o) {
            return o != null && scheme.equals(o.scheme) && host.equals(o.host) && port == o.port;
        }
    }

    // ── Sonuç ───────────────────────────────────────────────────────────────────────────────

    /** Sayfanın yüklediği nesne (parçasız adres anahtarıyla). */
    public record Resource(String url, String category, long bytes) {}

    /**
     * Bir sayfanın sonucu. {@code error} saklanan KOD'dur ({@code PAGE_HTTP:404}, {@code PAGE_FAILED:<neden>},
     * {@code PAGE_BLOCKED}, {@code PAGE_EMPTY}, {@code PAGE_NOT_HTML}, {@code PAGE_OFFSITE}, {@code NOT_SCANNED:TIME|BYTES|OBJECTS});
     * okuma anında isteğin diline çevrilir.
     *
     * @param resources anahtar → nesne (HTML hariç, yalnız sayfa yükü; bitmeyen sayfada boş)
     */
    public record Page(String url, String status, String error, Integer grade, String letter, Long loadMs, Long pageBytes,
                       Integer requests, Integer security, Integer seo, Integer accessibility, List<String> topRecs,
                       long htmlBytes, Map<String, Resource> resources, boolean capped) {

        public static Page failed(String url, String code) {
            return new Page(url, FAILED, code, null, null, null, null, null, null, null, null, List.of(), 0, Map.of(), false);
        }

        public static Page skipped(String url, String code) {
            return new Page(url, SKIPPED, code, null, null, null, null, null, null, null, null, List.of(), 0, Map.of(), true);
        }

        boolean done() {
            return DONE.equals(status);
        }
    }

    /** Analizin öneri listesinden ilk {@value #TOP_RECS} kod (öneriler önem + kazanca göre zaten sıralı). */
    public static List<String> topRecs(Object recommendations) {
        List<String> out = new ArrayList<>();
        if (!(recommendations instanceof List<?> list)) return out;
        for (Object o : list) {
            if (out.size() >= TOP_RECS) break;
            if (o instanceof Map<?, ?> m && m.get("code") instanceof String c && !c.isBlank()) out.add(c);
        }
        return out;
    }

    /**
     * {@code analysis.site} bölümü: {@code pages[]} (sırayla; ilk satır ana sayfa), {@code shared[]} (≥ 2 sayfada yüklenen
     * nesneler, en çok sayfada olan önce, eşitlikte büyük bayt, ≤ {@value #MAX_SHARED}), {@code totals}.
     */
    public static Map<String, Object> section(List<Page> pages) {
        Map<String, Integer> pagesOf = new HashMap<>();
        Map<String, Resource> first = new LinkedHashMap<>();
        for (Page p : pages) {
            if (!p.done()) continue;
            for (Map.Entry<String, Resource> e : p.resources().entrySet()) {
                pagesOf.merge(e.getKey(), 1, Integer::sum);
                first.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        List<Map<String, Object>> pageRows = new ArrayList<>(pages.size());
        int scanned = 0, failed = 0, skipped = 0, graded = 0;
        long gradeSum = 0;
        long totalUnique = 0;
        boolean anyDone = false;
        for (Page p : pages) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", DeepUrls.mask(p.url()));
            m.put("status", p.status());
            m.put("error", p.error());
            m.put("grade", p.grade());
            m.put("letter", p.letter());
            m.put("load_ms", p.loadMs());
            m.put("page_bytes", p.pageBytes());
            m.put("requests", p.requests());
            Map<String, Object> scores = new LinkedHashMap<>();
            scores.put("security", p.security());
            scores.put("seo", p.seo());
            scores.put("accessibility", p.accessibility());
            m.put("scores", scores);
            m.put("top_recs", List.copyOf(p.topRecs()));
            if (p.done()) {
                long unique = Math.max(0, p.htmlBytes());
                int shared = 0;
                for (Map.Entry<String, Resource> e : p.resources().entrySet()) {
                    if (pagesOf.getOrDefault(e.getKey(), 0) >= 2) shared++;
                    else unique += Math.max(0, e.getValue().bytes());
                }
                m.put("unique_bytes", unique);
                m.put("shared_count", shared);
                scanned++;
                anyDone = true;
                totalUnique += Math.max(0, p.htmlBytes());
                if (p.grade() != null) { graded++; gradeSum += p.grade(); }
            } else {
                m.put("unique_bytes", null);
                m.put("shared_count", null);
                if (FAILED.equals(p.status())) failed++;
                else skipped++;
            }
            m.put("capped", p.capped());
            pageRows.add(m);
        }
        for (Resource r : first.values()) totalUnique += Math.max(0, r.bytes());

        List<Map.Entry<String, Resource>> sharedList = new ArrayList<>();
        for (Map.Entry<String, Resource> e : first.entrySet()) {
            if (pagesOf.getOrDefault(e.getKey(), 0) >= 2) sharedList.add(e);
        }
        sharedList.sort(Comparator.comparingInt((Map.Entry<String, Resource> e) -> -pagesOf.get(e.getKey()))
                .thenComparing((Map.Entry<String, Resource> e) -> -e.getValue().bytes())
                .thenComparing(Map.Entry::getKey));
        List<Map<String, Object>> shared = new ArrayList<>();
        for (Map.Entry<String, Resource> e : sharedList) {
            if (shared.size() >= MAX_SHARED) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", DeepUrls.mask(e.getValue().url()));
            m.put("category", e.getValue().category());
            m.put("bytes", e.getValue().bytes());
            m.put("pages", pagesOf.get(e.getKey()));
            shared.add(m);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("pages", pages.size());
        totals.put("scanned", scanned);
        totals.put("failed", failed);
        totals.put("skipped", skipped);
        totals.put("avg_grade", graded == 0 ? null : (int) Math.round((double) gradeSum / graded));
        totals.put("total_unique_bytes", anyDone ? totalUnique : null);
        totals.put("shared", sharedList.size());

        Map<String, Object> site = new LinkedHashMap<>();
        site.put("pages", pageRows);
        site.put("shared", shared);
        site.put("totals", totals);
        return site;
    }

    /** Taranan (tamamlanan) sayfa sayısı — özet satırının {@code pages_count}'u. */
    public static int scanned(List<Page> pages) {
        int n = 0;
        for (Page p : pages) if (p.done()) n++;
        return n;
    }

    /** Bir sayfa yarıda kaldı ya da bütçe yüzünden taranmadı mı (özet satırının {@code capped}'ı). */
    public static boolean anyCapped(List<Page> pages) {
        for (Page p : pages) if (p.capped()) return true;
        return false;
    }
}
