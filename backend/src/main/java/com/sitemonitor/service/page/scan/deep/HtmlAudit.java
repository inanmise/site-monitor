package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.scan.PageResourceDiscovery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * E — SEO ve erişilebilirlik denetimi (2026-11, kaynak analizi algoritma 3) — SAF, {@link HtmlFacts} üzerinden.
 *
 * <p>Statik HTML: JavaScript çalışmaz, renk karşıtlığı ölçülmez (tarayıcı gerekir — not {@code CONTRAST_NOT_CHECKED}). Puan:
 * denetimlerin ağırlıklı toplamı (pass = tam, warn = yarım, fail = 0), 0–100.
 */
public final class HtmlAudit {

    public static final String PASS = "pass", WARN = "warn", FAIL = "fail";

    /** SEO ağırlıkları (toplam 100, sıra = arayüz sırası). */
    public static final Map<String, Integer> SEO_WEIGHTS;
    /** Erişilebilirlik ağırlıkları (toplam 100). */
    public static final Map<String, Integer> A11Y_WEIGHTS;
    static {
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put("title", 20);
        s.put("meta_description", 15);
        s.put("h1", 10);
        s.put("canonical", 10);
        s.put("robots", 15);
        s.put("lang", 5);
        s.put("viewport", 10);
        s.put("open_graph", 5);
        s.put("structured_data", 10);
        SEO_WEIGHTS = java.util.Collections.unmodifiableMap(s);
        Map<String, Integer> a = new LinkedHashMap<>();
        a.put("img_alt", 20);
        a.put("form_labels", 20);
        a.put("link_names", 15);
        a.put("button_names", 15);
        a.put("heading_order", 10);
        a.put("html_lang", 10);
        a.put("duplicate_ids", 10);
        A11Y_WEIGHTS = java.util.Collections.unmodifiableMap(a);
    }

    static final int TITLE_MIN = 10, TITLE_MAX = 60, DESC_MIN = 50, DESC_MAX = 160;
    /** Sayıya dayalı erişilebilirlik denetimi: 0 → pass, ≤ 2 → warn, fazlası fail. */
    static final int A11Y_WARN_MAX = 2;

    /** Denetim satırı (SEO'da {@code samples} null, erişilebilirlikte {@code value} null). */
    public record Check(String key, String status, String issue, String value, Integer count, List<String> samples) {
        Map<String, Object> toMap(boolean seo) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("status", status);
            m.put("issue", issue);
            if (seo) m.put("value", value);
            m.put("count", count);
            if (!seo) m.put("samples", samples == null ? List.of() : samples);
            return m;
        }
    }

    /** Sonuç: bölüm haritası + puanlar. */
    public record Result(Map<String, Object> section, Integer seoScore, Integer a11yScore) {}

    private HtmlAudit() {}

    /**
     * @param f           HTML olguları (null → bölüm yok, puanlar null)
     * @param xRobotsTag  ana yanıtın {@code X-Robots-Tag} değeri; yoksa null
     * @param pageUrl     ana sayfanın son adresi (canonical karşılaştırması)
     */
    public static Result audit(HtmlFacts f, String xRobotsTag, String pageUrl) {
        if (f == null) return new Result(null, null, null);
        List<Check> seo = seo(f, xRobotsTag, pageUrl);
        List<Check> a11y = a11y(f);
        int seoScore = score(seo, SEO_WEIGHTS);
        int a11yScore = score(a11y, A11Y_WEIGHTS);
        Map<String, Object> section = new LinkedHashMap<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("score", seoScore);
        s.put("checks", seo.stream().map(c -> c.toMap(true)).toList());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("score", a11yScore);
        a.put("checks", a11y.stream().map(c -> c.toMap(false)).toList());
        section.put("seo", s);
        section.put("accessibility", a);
        return new Result(section, seoScore, a11yScore);
    }

    static int score(List<Check> checks, Map<String, Integer> weights) {
        double sum = 0;
        int total = 0;
        for (Check c : checks) {
            int w = weights.getOrDefault(c.key(), 0);
            total += w;
            sum += w * (PASS.equals(c.status()) ? 1.0 : WARN.equals(c.status()) ? 0.5 : 0.0);
        }
        return total == 0 ? 0 : (int) Math.round(sum * 100.0 / total);
    }

    // ── SEO ───────────────────────────────────────────────────────────────────────────────

    static List<Check> seo(HtmlFacts f, String xRobotsTag, String pageUrl) {
        List<Check> out = new ArrayList<>();
        // Başlık
        String title = f.titles.isEmpty() ? null : f.titles.get(0);
        if (f.titleCount == 0 || title == null || title.isBlank()) out.add(seo("title", FAIL, "missing", null, f.titleCount));
        else if (f.titleCount > 1) out.add(seo("title", WARN, "multiple", title, f.titleCount));
        else if (title.length() < TITLE_MIN) out.add(seo("title", WARN, "too_short", title, 1));
        else if (title.length() > TITLE_MAX) out.add(seo("title", WARN, "too_long", title, 1));
        else out.add(seo("title", PASS, null, title, 1));
        // Açıklama
        String desc = f.descriptions.isEmpty() ? null : f.descriptions.get(0);
        if (f.descriptionCount == 0 || desc == null || desc.isBlank()) {
            out.add(seo("meta_description", FAIL, "missing", null, f.descriptionCount));
        } else if (f.descriptionCount > 1) out.add(seo("meta_description", WARN, "multiple", desc, f.descriptionCount));
        else if (desc.length() < DESC_MIN) out.add(seo("meta_description", WARN, "too_short", desc, 1));
        else if (desc.length() > DESC_MAX) out.add(seo("meta_description", WARN, "too_long", desc, 1));
        else out.add(seo("meta_description", PASS, null, desc, 1));
        // H1
        if (f.h1Count == 0) out.add(seo("h1", FAIL, "missing", null, 0));
        else if (f.h1Count > 1) out.add(seo("h1", WARN, "multiple", f.firstH1, f.h1Count));
        else out.add(seo("h1", PASS, null, f.firstH1, 1));
        // Canonical
        if (f.canonicals.isEmpty()) out.add(seo("canonical", WARN, "missing", null, 0));
        else if (f.canonicals.size() > 1) {
            out.add(seo("canonical", FAIL, "multiple", DeepUrls.mask(DeepUrls.clip(f.canonicals.get(0))), f.canonicals.size()));
        } else {
            String c = f.canonicals.get(0);
            String shown = DeepUrls.mask(DeepUrls.clip(c));
            if (!com.sitemonitor.service.page.PageFetchCore.isHttp(c)) out.add(seo("canonical", FAIL, "invalid", shown, 1));
            else if (pageUrl != null && !samePage(c, pageUrl)) out.add(seo("canonical", WARN, "other_page", shown, 1));
            else out.add(seo("canonical", PASS, null, shown, 1));
        }
        // Robots (meta + X-Robots-Tag)
        String robots = join(f.robots, xRobotsTag);
        String rl = robots == null ? "" : robots.toLowerCase(Locale.ROOT);
        if (rl.contains("noindex") || rl.contains("none")) out.add(seo("robots", FAIL, "noindex", robots, null));
        else if (rl.contains("nofollow")) out.add(seo("robots", WARN, "nofollow", robots, null));
        else out.add(seo("robots", PASS, null, robots, null));
        // Dil
        out.add(f.lang == null ? seo("lang", FAIL, "missing", null, null) : seo("lang", PASS, null, f.lang, null));
        // Viewport
        if (f.viewport == null) out.add(seo("viewport", FAIL, "missing", null, null));
        else if (noZoom(f.viewport)) out.add(seo("viewport", WARN, "no_zoom", f.viewport, null));
        else out.add(seo("viewport", PASS, null, f.viewport, null));
        // Open Graph
        String og = f.ogKeys.isEmpty() ? null : DeepUrls.clip(String.join(", ", f.ogKeys));
        boolean full = f.ogKeys.contains("og:title") && f.ogKeys.contains("og:description") && f.ogKeys.contains("og:image");
        if (f.ogKeys.isEmpty()) out.add(seo("open_graph", WARN, "missing", null, 0));
        else if (!full) out.add(seo("open_graph", WARN, "partial", og, f.ogKeys.size()));
        else out.add(seo("open_graph", PASS, null, og, f.ogKeys.size()));
        // Yapılandırılmış veri
        int sd = f.jsonLdCount + f.microdataCount;
        String types = f.structuredTypes.isEmpty() ? null : DeepUrls.clip(String.join(", ", f.structuredTypes));
        if (sd == 0) out.add(seo("structured_data", WARN, "missing", null, 0));
        else if (f.jsonLdInvalid > 0 && f.structuredTypes.isEmpty()) out.add(seo("structured_data", WARN, "invalid_json", null, sd));
        else if (f.jsonLdInvalid > 0) out.add(seo("structured_data", WARN, "invalid_json", types, sd));
        else out.add(seo("structured_data", PASS, null, types, sd));
        return out;
    }

    private static Check seo(String key, String status, String issue, String value, Integer count) {
        return new Check(key, status, issue, value, count, null);
    }

    /** {@code user-scalable=no} ya da {@code maximum-scale} < 2. */
    static boolean noZoom(String viewport) {
        String v = viewport.toLowerCase(Locale.ROOT).replace(" ", "");
        if (v.contains("user-scalable=no") || v.contains("user-scalable=0")) return true;
        int i = v.indexOf("maximum-scale=");
        if (i < 0) return false;
        int s = i + "maximum-scale=".length();
        int e = s;
        while (e < v.length() && (Character.isDigit(v.charAt(e)) || v.charAt(e) == '.')) e++;
        try {
            return Double.parseDouble(v.substring(s, e)) < 2.0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** Aynı sayfa mı: parçasız, sondaki "/" ve sorgu yok sayılır, host büyük / küçük harf duyarsız. */
    static boolean samePage(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String u) {
        String k = PageResourceDiscovery.key(u);
        int q = k.indexOf('?');
        if (q >= 0) k = k.substring(0, q);
        if (k.endsWith("/")) k = k.substring(0, k.length() - 1);
        int s = k.indexOf("://");
        if (s < 0) return k.toLowerCase(Locale.ROOT);
        int slash = k.indexOf('/', s + 3);
        String origin = slash < 0 ? k : k.substring(0, slash);
        String path = slash < 0 ? "" : k.substring(slash);
        return origin.toLowerCase(Locale.ROOT) + path;
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b == null || b.isBlank() ? null : DeepUrls.clip(b);
        if (b == null || b.isBlank()) return DeepUrls.clip(a);
        return DeepUrls.clip(a + ", " + b);
    }

    // ── Erişilebilirlik ───────────────────────────────────────────────────────────────────

    static List<Check> a11y(HtmlFacts f) {
        List<Check> out = new ArrayList<>();
        out.add(counted("img_alt", f.imgNoAlt));
        out.add(counted("form_labels", f.unlabeledInputs));
        out.add(counted("link_names", f.unnamedLinks));
        out.add(counted("button_names", f.unnamedButtons));
        if (f.headingCount == 0) out.add(new Check("heading_order", WARN, "no_headings", null, 0, List.of()));
        else if (f.headingSkips.count > 0) {
            out.add(new Check("heading_order", WARN, "skipped", null, f.headingSkips.count, List.copyOf(f.headingSkips.samples)));
        } else out.add(new Check("heading_order", PASS, null, null, 0, List.of()));
        out.add(f.lang == null ? new Check("html_lang", FAIL, "missing", null, 1, List.of("html"))
                : new Check("html_lang", PASS, null, null, 0, List.of()));
        out.add(counted("duplicate_ids", f.duplicateIds));
        return out;
    }

    private static Check counted(String key, HtmlFacts.Issue i) {
        String status = i.count == 0 ? PASS : i.count <= A11Y_WARN_MAX ? WARN : FAIL;
        return new Check(key, status, i.count == 0 ? null : "found", null, i.count, List.copyOf(i.samples));
    }
}
