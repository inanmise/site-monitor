package com.sitemonitor.service.page.scan.deep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.page.PageFetchCore;
import com.sitemonitor.service.page.scan.PageResourceDiscovery;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sayfanın HTML'sinden derin analizin ihtiyaç duyduğu OLGULAR (2026-11, kaynak analizi algoritma 3) — SAF: ayrıştırılmış
 * belgeyi okur, ağ işi yapmaz, belgeyi değiştirmez. Belge {@link PageResourceDiscovery#parse} ile gelir ({@code <noscript>} /
 * {@code <template>} atılmış — JS açık tarayıcının gördüğü).
 *
 * <p>Statik HTML'dir: JavaScript ÇALIŞTIRILMAZ, sonradan eklenen öğeler görünmez. Listeler sınırlıdır (öğe türü başına
 * {@value #MAX_ELEMENTS}), örnek metinler kısa seçicilerdir ({@value #SAMPLE_CHARS} karakter) — girdi DEĞERLERİ, kişi verisi
 * yazılmaz. Döngüler belgenin boyutuyla sınırlıdır (belge zaten 8 MB açılmış gövdeyle sınırlı).
 */
public final class HtmlFacts {

    static final int MAX_ELEMENTS = 2000;
    static final int MAX_REFERENCED = 5000;
    static final int MAX_SAMPLES = 5;
    static final int SAMPLE_CHARS = 80;
    static final int MAX_JSON_LD = 20;
    static final int MAX_JSON_LD_CHARS = 100_000;
    static final int MAX_TYPES = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record ScriptTag(String url, boolean inHead, boolean async, boolean defer, boolean module, boolean integrity) {}

    public record StyleTag(String url, boolean inHead, String media, boolean integrity) {}

    /** {@code rel}: preload | modulepreload | preconnect | dns-prefetch. Önyüklemede {@code url}, bağlantıda {@code host}. */
    public record Hint(String rel, String url, String host, String as) {}

    public record Priority(String url, String value) {}

    /**
     * İçerik görseli (DOM sırası). {@code url}: tarayıcının indireceği aday (keşifle aynı kural, parçasız anahtar).
     */
    public record ImageTag(String url, int domIndex, boolean hasWidth, boolean hasHeight, boolean srcset,
                           boolean inPicture, boolean lazy) {}

    /** Sayılan sorun + ilk {@value #MAX_SAMPLES} örnek seçici. */
    public static final class Issue {
        public int count;
        public final List<String> samples = new ArrayList<>();

        void add(String sample) {
            count++;
            if (samples.size() < MAX_SAMPLES && sample != null) samples.add(DeepUrls.clip(sample, SAMPLE_CHARS));
        }
    }

    public String baseUrl;
    public boolean https;
    /** Açılmış HTML boyutu (bayt). */
    public long htmlBytes;
    public final List<ScriptTag> scripts = new ArrayList<>();
    public final List<StyleTag> stylesheets = new ArrayList<>();
    public final List<Hint> hints = new ArrayList<>();
    public final List<Priority> priorities = new ArrayList<>();
    public final List<ImageTag> images = new ArrayList<>();
    /** Önyükleme DIŞI öğelerin başvurduğu adresler (parçasız anahtar) — "kullanılmayan önyükleme" kuralı. */
    public final Set<String> referenced = new HashSet<>();
    public long inlineScriptBytes, inlineStyleBytes;
    public int dataUriCount;
    public long dataUriBytes;
    public int domNodes, maxDepth;
    /** {@code <meta http-equiv="Content-Security-Policy">} içeriği (başlık yoksa değerlendirilir). */
    public String metaCsp;

    // ── SEO ──
    /** İlk {@value #MAX_SAMPLES} başlık / açıklama metni; sayılar ayrı. */
    public final List<String> titles = new ArrayList<>();
    public int titleCount;
    public final List<String> descriptions = new ArrayList<>();
    public int descriptionCount;
    public int h1Count;
    public String firstH1;
    public final List<String> canonicals = new ArrayList<>();
    public String robots;
    public String lang;
    public String viewport;
    public final Set<String> ogKeys = new LinkedHashSet<>();
    public int jsonLdCount, jsonLdInvalid, microdataCount;
    public final Set<String> structuredTypes = new LinkedHashSet<>();

    // ── Erişilebilirlik ──
    public final Issue imgNoAlt = new Issue();
    public final Issue unlabeledInputs = new Issue();
    public final Issue unnamedLinks = new Issue();
    public final Issue unnamedButtons = new Issue();
    public final Issue headingSkips = new Issue();
    public int headingCount;
    public final Issue duplicateIds = new Issue();

    private HtmlFacts() {}

    /** Olgular; belge null ise null. {@code htmlBytes}: açılmış gövdenin boyutu. */
    public static HtmlFacts extract(Document doc, long htmlBytes) {
        if (doc == null) return null;
        HtmlFacts f = new HtmlFacts();
        f.baseUrl = doc.baseUri();
        f.https = DeepUrls.https(f.baseUrl);
        f.htmlBytes = htmlBytes;
        f.collectResources(doc);
        f.collectInline(doc);
        f.collectSeo(doc);
        f.collectA11y(doc);
        f.collectShape(doc);
        return f;
    }

    // ── Kaynaklar ve ipuçları ─────────────────────────────────────────────────────────────

    private void collectResources(Document doc) {
        Element head = doc.head();
        Set<Element> inHead = new HashSet<>(head.getAllElements());
        for (Element el : doc.select("script[src]")) {
            if (scripts.size() >= MAX_ELEMENTS) break;
            String u = key(el.absUrl("src"));
            if (u == null) continue;
            String type = el.attr("type").trim().toLowerCase(Locale.ROOT);
            refer(u);
            scripts.add(new ScriptTag(u, inHead.contains(el), el.hasAttr("async"), el.hasAttr("defer"),
                    "module".equals(type), !el.attr("integrity").isBlank()));
        }
        for (Element el : doc.select("link[href]")) {
            Set<String> rel = tokens(el.attr("rel"));
            String u = key(el.absUrl("href"));
            if (u == null) continue;
            String as = el.attr("as").trim().toLowerCase(Locale.ROOT);
            if (rel.contains("stylesheet") && stylesheets.size() < MAX_ELEMENTS) {
                refer(u);
                stylesheets.add(new StyleTag(u, inHead.contains(el), el.hasAttr("media") ? el.attr("media").trim() : null,
                        !el.attr("integrity").isBlank()));
            }
            if (hints.size() < MAX_ELEMENTS) {
                if (rel.contains("preload")) hints.add(new Hint("preload", u, null, as));
                else if (rel.contains("modulepreload")) hints.add(new Hint("modulepreload", u, null, "script"));
                if (rel.contains("preconnect")) hints.add(new Hint("preconnect", null, DeepUrls.host(u), null));
                if (rel.contains("dns-prefetch")) hints.add(new Hint("dns-prefetch", null, DeepUrls.host(u), null));
            }
            if (rel.stream().anyMatch(r -> r.contains("icon")) || rel.contains("manifest")) refer(u);
        }
        int domIndex = 0;
        for (Element el : doc.select("img")) {
            if (images.size() >= MAX_ELEMENTS) break;
            Element pic = el.parent() != null && "picture".equals(el.parent().normalName()) ? el.parent() : null;
            String u = key(pic != null ? PageResourceDiscovery.pictureUrl(pic) : PageResourceDiscovery.imageUrl(el));
            for (String c : srcsetUrls(el)) refer(c);
            refer(key(el.absUrl("src")));
            if (u == null) continue;
            images.add(new ImageTag(u, domIndex++, dim(el, "width"), dim(el, "height"), !el.attr("srcset").isBlank(),
                    pic != null, "lazy".equalsIgnoreCase(el.attr("loading").trim())));
        }
        for (Element el : doc.select("source")) {
            for (String c : srcsetUrls(el)) refer(c);
            refer(key(el.absUrl("src")));
        }
        for (Element el : doc.select("video[src], audio[src], iframe[src], frame[src], embed[src], input[type=image][src]")) {
            refer(key(el.absUrl("src")));
        }
        for (Element el : doc.select("video[poster]")) refer(key(el.absUrl("poster")));
        for (Element el : doc.select("object[data]")) refer(key(el.absUrl("data")));
        for (Element el : doc.select("[fetchpriority]")) {
            if (priorities.size() >= MAX_ELEMENTS) break;
            String attr = "img".equals(el.normalName()) || "script".equals(el.normalName()) ? "src" : "href";
            String u = "img".equals(el.normalName()) ? key(PageResourceDiscovery.imageUrl(el)) : key(el.absUrl(attr));
            if (u != null) priorities.add(new Priority(u, el.attr("fetchpriority").trim().toLowerCase(Locale.ROOT)));
        }
        for (Element el : doc.select("meta[http-equiv]")) {
            if ("content-security-policy".equalsIgnoreCase(el.attr("http-equiv").trim()) && metaCsp == null) {
                metaCsp = DeepUrls.clip(el.attr("content"), 8192);
            }
        }
    }

    private void refer(String key) {
        if (key != null && referenced.size() < MAX_REFERENCED) referenced.add(key);
    }

    private static boolean dim(Element el, String attr) {
        if (!el.attr(attr).isBlank()) return true;
        String style = el.attr("style").toLowerCase(Locale.ROOT);
        return style.contains(attr + ":") || style.contains(attr + " :") || style.contains("aspect-ratio");
    }

    private static List<String> srcsetUrls(Element el) {
        String set = el.attr("srcset");
        if (set.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String cand : set.split(",", 50)) {
            String c = cand.trim();
            int sp = c.indexOf(' ');
            String u = sp > 0 ? c.substring(0, sp) : c;
            if (u.isBlank()) continue;
            out.add(key(PageFetchCore.resolve(el.baseUri(), u)));
        }
        return out;
    }

    // ── Satır içi içerik ──────────────────────────────────────────────────────────────────

    private void collectInline(Document doc) {
        for (Element el : doc.select("script:not([src])")) inlineScriptBytes += utf8(el.data());
        for (Element el : doc.select("style")) {
            String css = el.data();
            inlineStyleBytes += utf8(css);
            countCssDataUris(css);
        }
        for (Element el : doc.select("[style]")) {
            String css = el.attr("style");
            inlineStyleBytes += utf8(css);
            countCssDataUris(css);
        }
        for (Element el : doc.getAllElements()) {
            for (String attr : new String[]{"src", "href", "poster", "data"}) {
                String v = el.attr(attr);
                if (v.length() > 5 && v.regionMatches(true, 0, "data:", 0, 5)) {
                    dataUriCount++;
                    dataUriBytes += v.length();
                }
            }
            String set = el.attr("srcset");
            if (!set.isEmpty() && set.regionMatches(true, 0, "data:", 0, 5)) {
                dataUriCount++;
                dataUriBytes += set.length();
            }
        }
    }

    /** CSS metnindeki {@code url(data:…)} sayısı ve boyutu — doğrusal. */
    void countCssDataUris(String css) {
        if (css == null || css.isEmpty()) return;
        String low = css.length() > PageResourceDiscovery.MAX_CSS_CHARS
                ? css.substring(0, PageResourceDiscovery.MAX_CSS_CHARS).toLowerCase(Locale.ROOT) : css.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = low.indexOf("data:", from);
            if (i < 0) return;
            int p = i - 1;
            while (p >= 0 && (low.charAt(p) == '"' || low.charAt(p) == '\'' || low.charAt(p) == ' ')) p--;
            boolean inUrl = p >= 3 && low.startsWith("url(", p - 3);
            int end = low.indexOf(')', i);
            if (end < 0) end = low.length();
            if (inUrl) {
                dataUriCount++;
                dataUriBytes += end - i;
            }
            from = Math.max(end, i + 5);
        }
    }

    private static long utf8(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;
        }
        return n;
    }

    private void collectShape(Document doc) {
        domNodes = doc.getAllElements().size();
        int[] max = {0};
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof Element && depth > max[0]) max[0] = depth;
            }
        }, doc);
        maxDepth = max[0];
    }

    // ── SEO ───────────────────────────────────────────────────────────────────────────────

    private void collectSeo(Document doc) {
        for (Element el : doc.select("title")) {
            titleCount++;
            if (titles.size() < MAX_SAMPLES) titles.add(DeepUrls.clip(el.text()));
        }
        for (Element el : doc.select("meta[name]")) {
            String name = el.attr("name").trim().toLowerCase(Locale.ROOT);
            String content = el.attr("content");
            switch (name) {
                case "description" -> {
                    descriptionCount++;
                    if (descriptions.size() < MAX_SAMPLES) descriptions.add(DeepUrls.clip(content));
                }
                case "robots" -> robots = robots == null ? DeepUrls.clip(content) : DeepUrls.clip(robots + ", " + content);
                case "viewport" -> { if (viewport == null) viewport = DeepUrls.clip(content); }
                default -> { }
            }
        }
        for (Element el : doc.select("meta[property]")) {
            String p = el.attr("property").trim().toLowerCase(Locale.ROOT);
            if (p.startsWith("og:") && ogKeys.size() < 30 && !el.attr("content").isBlank()) ogKeys.add(DeepUrls.clip(p, 40));
        }
        var h1 = doc.select("h1");
        h1Count = h1.size();
        if (!h1.isEmpty()) firstH1 = DeepUrls.clip(h1.first().text());
        for (Element el : doc.select("link[href]")) {
            if (tokens(el.attr("rel")).contains("canonical") && canonicals.size() < MAX_SAMPLES) {
                canonicals.add(el.absUrl("href").isBlank() ? el.attr("href") : el.absUrl("href"));
            }
        }
        Element html = doc.selectFirst("html");
        if (html != null && !html.attr("lang").isBlank()) lang = DeepUrls.clip(html.attr("lang"), 40);
        for (Element el : doc.select("script[type]")) {
            if (!"application/ld+json".equalsIgnoreCase(el.attr("type").trim())) continue;
            jsonLdCount++;
            if (jsonLdCount > MAX_JSON_LD) continue;
            String data = el.data();
            if (data.length() > MAX_JSON_LD_CHARS) { jsonLdInvalid++; continue; }
            try {
                JsonNode n = JSON.readTree(data);
                if (n == null) { jsonLdInvalid++; continue; }
                types(n, 0);
            } catch (Exception e) {
                jsonLdInvalid++;
            }
        }
        for (Element el : doc.select("[itemscope][itemtype]")) {
            microdataCount++;
            String t = el.attr("itemtype").trim();
            int slash = t.lastIndexOf('/');
            if (structuredTypes.size() < MAX_TYPES && !t.isEmpty()) structuredTypes.add(DeepUrls.clip(t.substring(slash + 1), 60));
        }
    }

    /** JSON-LD {@code @type} değerleri (üst düzey, dizi ve {@code @graph}; derinlik ≤ 2). */
    private void types(JsonNode n, int depth) {
        if (n == null || depth > 2 || structuredTypes.size() >= MAX_TYPES) return;
        if (n.isArray()) {
            for (int i = 0; i < Math.min(n.size(), 50); i++) types(n.get(i), depth + 1);
            return;
        }
        if (!n.isObject()) return;
        JsonNode t = n.get("@type");
        if (t != null && t.isTextual()) structuredTypes.add(DeepUrls.clip(t.asText(), 60));
        else if (t != null && t.isArray()) for (int i = 0; i < Math.min(t.size(), 5); i++) {
            if (t.get(i).isTextual() && structuredTypes.size() < MAX_TYPES) structuredTypes.add(DeepUrls.clip(t.get(i).asText(), 60));
        }
        JsonNode g = n.get("@graph");
        if (g != null) types(g, depth + 1);
    }

    // ── Erişilebilirlik ───────────────────────────────────────────────────────────────────

    private void collectA11y(Document doc) {
        for (Element el : doc.select("img")) {
            if (el.hasAttr("alt") || hidden(el)) continue;
            String role = el.attr("role").trim().toLowerCase(Locale.ROOT);
            if (role.equals("presentation") || role.equals("none")) continue;
            imgNoAlt.add("img[src=" + shortRef(el.attr("src").isBlank() ? el.attr("srcset") : el.attr("src")) + "]");
        }
        Set<String> labelFor = new HashSet<>();
        for (Element l : doc.select("label[for]")) labelFor.add(l.attr("for").trim());
        for (Element el : doc.select("input, select, textarea")) {
            String type = el.attr("type").trim().toLowerCase(Locale.ROOT);
            if ("input".equals(el.normalName()) && (type.equals("hidden") || type.equals("submit") || type.equals("reset")
                    || type.equals("button") || type.equals("image"))) continue;
            if (hidden(el)) continue;
            String id = el.id();
            boolean labeled = (!id.isEmpty() && labelFor.contains(id)) || insideLabel(el)
                    || named(el) || !el.attr("placeholder").isBlank();
            if (!labeled) {
                StringBuilder s = new StringBuilder(el.normalName());
                if (!type.isEmpty()) s.append("[type=").append(type).append(']');
                if (!el.attr("name").isBlank()) s.append("[name=").append(DeepUrls.clip(el.attr("name"), 30)).append(']');
                else if (!id.isEmpty()) s.append('#').append(DeepUrls.clip(id, 30));
                unlabeledInputs.add(s.toString());
            }
        }
        for (Element el : doc.select("a[href]")) {
            if (hidden(el) || accessibleName(el)) continue;
            unnamedLinks.add("a[href=" + shortRef(el.attr("href")) + "]");
        }
        for (Element el : doc.select("button")) {
            if (hidden(el) || accessibleName(el)) continue;
            unnamedButtons.add(selector(el));
        }
        for (Element el : doc.select("input[type=button]")) {
            if (hidden(el) || !el.attr("value").isBlank() || named(el)) continue;
            unnamedButtons.add(selector(el));
        }
        int prev = 0;
        for (Element h : doc.select("h1, h2, h3, h4, h5, h6")) {
            int level = h.normalName().charAt(1) - '0';
            headingCount++;
            if (prev > 0 && level > prev + 1) headingSkips.add("h" + prev + " → h" + level);
            prev = level;
        }
        Map<String, Integer> ids = new HashMap<>();
        for (Element el : doc.select("[id]")) {
            String id = el.id().trim();
            if (!id.isEmpty()) ids.merge(id, 1, Integer::sum);
        }
        ids.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).sorted()
                .forEach(id -> duplicateIds.add("#" + DeepUrls.clip(id, 40)));
    }

    /** Bir {@code <label>} içinde mi (atalar; derinlik belgeyle sınırlı). */
    private static boolean insideLabel(Element el) {
        for (Element p = el.parent(); p != null; p = p.parent()) if ("label".equals(p.normalName())) return true;
        return false;
    }

    /** aria-hidden="true" ya da hidden özniteliği (kendisi). */
    private static boolean hidden(Element el) {
        return "true".equalsIgnoreCase(el.attr("aria-hidden").trim()) || el.hasAttr("hidden");
    }

    /** aria-label / aria-labelledby / title dolu. */
    private static boolean named(Element el) {
        return !el.attr("aria-label").isBlank() || !el.attr("aria-labelledby").isBlank() || !el.attr("title").isBlank();
    }

    /** Erişilebilir ad: metin, aria-label / labelledby / title, alt'lı görsel ya da başlıklı SVG. */
    private static boolean accessibleName(Element el) {
        if (named(el) || !el.text().isBlank()) return true;
        for (Element img : el.select("img[alt]")) if (!img.attr("alt").isBlank()) return true;
        for (Element svg : el.select("svg")) {
            if (!svg.attr("aria-label").isBlank() || !svg.select("title").text().isBlank()) return true;
        }
        return !el.select("[aria-label]").isEmpty();
    }

    private static String selector(Element el) {
        StringBuilder s = new StringBuilder(el.normalName());
        if (!el.id().isEmpty()) s.append('#').append(DeepUrls.clip(el.id(), 30));
        else if (!el.className().isBlank()) s.append('.').append(DeepUrls.clip(el.className().trim().split("\\s+", 2)[0], 30));
        return s.toString();
    }

    /** Örnek için kısa adres: yol + dosya (sorgu yok — kişi verisi taşıyabilir). */
    static String shortRef(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String r = raw.trim();
        if (r.regionMatches(true, 0, "data:", 0, 5)) return "data:…";
        int q = r.indexOf('?');
        int h = r.indexOf('#');
        int cut = q < 0 ? h : (h < 0 ? q : Math.min(q, h));
        if (cut >= 0) r = r.substring(0, cut);
        if (r.length() > 60) r = "…" + r.substring(r.length() - 59);
        return r;
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────

    private static String key(String abs) {
        if (abs == null || abs.isBlank()) return null;
        String u = PageFetchCore.normalizeUrl(abs.trim());
        if (!PageFetchCore.isHttp(u) || u.length() > DeepUrls.MAX_URL_CHARS) return null;
        return PageResourceDiscovery.key(u);
    }

    private static Set<String> tokens(String rel) {
        Set<String> out = new HashSet<>();
        for (String t : rel.trim().toLowerCase(Locale.ROOT).split("\\s+", 20)) if (!t.isBlank()) out.add(t);
        return out;
    }
}
