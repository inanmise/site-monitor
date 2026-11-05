package com.sitemonitor.service.keyword.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.http.JsonAssertion;
import com.sitemonitor.service.keyword.KeywordBodyAnalyzer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Tek kontrolün içerik görünümü (kw sözleşmesi §6.2) — kural değerlendiricisinin ve değişim parmak izinin girdisi. Kontrol
 * başına bir nesne, tek iş parçacığı.
 *
 * <p><b>Pahalı ayrıştırmalar tembel ve en çok BİR kez:</b> görünür metin, jsoup belgesi (yalnız bir CSS kuralı ya da değişim
 * seçicisi varsa) ve JSON ağacı (yalnız bir JSON kuralı varsa) ilk istendiğinde kurulur, sonra önbellekten döner. 2 MB'lık bir
 * gövdenin DOM'u bellek ister — gerekmeyen kontrolde hiç kurulmaz.
 *
 * <p><b>Sayfa metni</b> (§4.3): {@code VISIBLE} → görünür metin (her zaman normalleştirilmiş); {@code RAW} + {@code normalizeWs}
 * → varlıkları çözülmüş, boşlukları teke indirgenmiş ham metin; aksi hâlde ham metin (etiket / betik dahil — bugünkü davranış).
 * Normalleştirilmiş bölgede METİN kuralının iğnesi de aynı normalleştirmeden geçer ({@link #needle}).
 */
public final class KeywordContent {

    /** CSS bölgesinde birleştirilen en çok öğe. */
    public static final int MAX_CSS_ELEMENTS = 1_000;
    /** JSON bölgesinde nesne / dizi düğümünün sıkı JSON metni üst sınırı. */
    public static final int MAX_JSON_SCOPE_CHARS = 64_000;
    /** Görünür metin tarama / üretim tavanı (gövde tavanıyla aynı — kontrol yolunda TAM gövde). */
    public static final int VISIBLE_CAP = 2_000_000;
    /** Atalık denetiminin derinlik sınırı (CSS öğe tekilleştirmesi). */
    private static final int MAX_DEPTH = 512;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Bölge metni + (varsa) kural notu + normalleştirilmiş mi. */
    public record ScopeText(String text, String note, boolean normalized) {}

    private final String raw;
    private final String contentType;
    private final long bytes;
    private final boolean truncated;
    private final boolean visibleScope;
    private final boolean normalizeWs;
    private final UnaryOperator<String> visibleFn;
    private final UnaryOperator<String> normalizer;

    private String pageText;
    private Document doc;
    private boolean jsonParsed;
    private JsonNode jsonRoot;
    private final Map<String, ScopeText> cssCache = new LinkedHashMap<>();
    private final Map<String, List<String>> cssLineCache = new LinkedHashMap<>();
    private final Map<String, ScopeText> jsonCache = new LinkedHashMap<>();

    private KeywordContent(Builder b) {
        this.raw = b.raw == null ? "" : b.raw;
        this.contentType = b.contentType;
        this.bytes = b.bytes;
        this.truncated = b.truncated;
        this.visibleScope = b.visibleScope;
        this.normalizeWs = b.normalizeWs;
        this.visibleFn = b.visibleFn != null ? b.visibleFn
                : s -> KeywordBodyAnalyzer.visibleText(s, VISIBLE_CAP, VISIBLE_CAP);
        this.normalizer = b.normalizer != null ? b.normalizer
                : s -> KeywordBodyAnalyzer.collapseWs(KeywordBodyAnalyzer.decodeEntities(s));
    }

    public static Builder builder(String raw) {
        return new Builder(raw);
    }

    /** Kurucu — yalnız {@code raw} zorunlu; kapsam varsayılanı RAW, normalleştirme kapalı. */
    public static final class Builder {
        private final String raw;
        private String contentType;
        private long bytes;
        private boolean truncated;
        private boolean visibleScope;
        private boolean normalizeWs;
        private UnaryOperator<String> visibleFn;
        private UnaryOperator<String> normalizer;

        private Builder(String raw) { this.raw = raw; }

        public Builder contentType(String v) { this.contentType = v; return this; }
        public Builder bytes(long v) { this.bytes = v; return this; }
        public Builder truncated(boolean v) { this.truncated = v; return this; }
        /** {@code match_scope = VISIBLE} mi. */
        public Builder visibleScope(boolean v) { this.visibleScope = v; return this; }
        public Builder normalizeWs(boolean v) { this.normalizeWs = v; return this; }
        /** Görünür metin üreticisi (varsayılan {@link KeywordBodyAnalyzer#visibleText}). */
        public Builder visibleText(UnaryOperator<String> fn) { this.visibleFn = fn; return this; }
        /** Normalleştirici (varsayılan varlık çözme + boşluk indirgeme). */
        public Builder normalizer(UnaryOperator<String> fn) { this.normalizer = fn; return this; }
        public KeywordContent build() { return new KeywordContent(this); }
    }

    public String raw() { return raw; }
    public String contentType() { return contentType; }
    public long bytes() { return bytes; }
    public boolean truncated() { return truncated; }
    public boolean visibleScope() { return visibleScope; }

    /** Sayfa metni normalleştirilmiş mi (VISIBLE ya da RAW + normalizeWs). */
    public boolean pageNormalized() {
        return visibleScope || normalizeWs;
    }

    /** Sayfa metni (kapsama göre; önbellekli). */
    public String pageText() {
        if (pageText == null) {
            String t;
            if (visibleScope) t = visibleFn.apply(raw);
            else if (normalizeWs) t = normalizer.apply(raw);
            else t = raw;
            pageText = t == null ? "" : t;
        }
        return pageText;
    }

    /** Metin kuralının iğnesi — bölge metni normalleştirilmişse iğne de normalleştirilir. */
    public String needle(String pattern, ScopeText scope) {
        if (pattern == null) return "";
        return scope != null && scope.normalized() ? nz(normalizer.apply(pattern)) : pattern;
    }

    /** Kuralın bölge metni. */
    public ScopeText scope(KeywordRule rule) {
        return switch (rule.scopeType()) {
            case CSS -> css(rule.scopeValue());
            case JSON -> json(rule.scopeValue());
            case PAGE -> new ScopeText(pageText(), null, pageNormalized());
        };
    }

    /**
     * CSS bölgesi: seçilen öğelerin metni (ilk {@value #MAX_CSS_ELEMENTS}, her öğe bir satır; seçilmiş bir öğenin İÇİNDEKİ
     * öğe yeniden sayılmaz — iç içe {@code div} metni iki kez sayılmasın). Hiç öğe yoksa not {@code SCOPE_NOT_FOUND}.
     */
    public ScopeText css(String selector) {
        String key = selector == null ? "" : selector.trim();
        ScopeText hit = cssCache.get(key);
        if (hit != null) return hit;
        List<String> lines = cssLines(key);
        ScopeText r = lines == null || lines.isEmpty()
                ? new ScopeText("", KeywordRuleEvaluator.NOTE_SCOPE_NOT_FOUND, true)
                : new ScopeText(String.join("\n", lines), null, true);
        cssCache.put(key, r);
        return r;
    }

    /** CSS seçicisinin satırları (öğe başına bir satır, boşlar düşer); seçici çalışmazsa / öğe yoksa boş liste. */
    public List<String> cssLines(String selector) {
        String key = selector == null ? "" : selector.trim();
        List<String> hit = cssLineCache.get(key);
        if (hit != null) return hit;
        List<String> out = new ArrayList<>();
        if (!key.isEmpty()) {
            try {
                Elements els = document().select(key);
                Set<Element> picked = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Element el : els) {
                    if (picked.size() >= MAX_CSS_ELEMENTS) break;
                    if (insidePicked(el, picked)) continue;
                    picked.add(el);
                    // jsoup text() boşluğu indirger ama NBSP'yi korur — iğne normalleştirmesiyle aynı kalsın diye teke indirgenir
                    String t = KeywordBodyAnalyzer.collapseWs(el.text());
                    if (!t.isEmpty()) out.add(t);
                }
            } catch (RuntimeException e) {
                out.clear();   // kayıtta doğrulanmış seçici çalışmadıysa bölge yok sayılır
            }
        }
        List<String> r = List.copyOf(out);
        cssLineCache.put(key, r);
        return r;
    }

    private static boolean insidePicked(Element el, Set<Element> picked) {
        Element p = el.parent();
        for (int d = 0; p != null && d < MAX_DEPTH; d++, p = p.parent()) {
            if (picked.contains(p)) return true;
        }
        return false;
    }

    private Document document() {
        if (doc == null) doc = Jsoup.parse(raw);
        return doc;
    }

    /**
     * JSON bölgesi: gövde Jackson ile (en çok bir kez) ayrıştırılır, yol HTTP izlemesinin dilbilgisiyle çözülür. Dize düğüm →
     * metni; sayı / mantıksal → metin; {@code null} → boş metin (yol VAR); nesne / dizi → sıkı JSON (≤ {@value
     * #MAX_JSON_SCOPE_CHARS}). Yol yok → {@code SCOPE_NOT_FOUND}; gövde JSON değil → {@code SCOPE_INVALID}.
     */
    public ScopeText json(String path) {
        String key = path == null ? "" : path.trim();
        ScopeText hit = jsonCache.get(key);
        if (hit != null) return hit;
        ScopeText r = resolveJson(key);
        jsonCache.put(key, r);
        return r;
    }

    private ScopeText resolveJson(String path) {
        JsonNode root = jsonRoot();
        if (root == null) return new ScopeText("", KeywordRuleEvaluator.NOTE_SCOPE_INVALID, false);
        JsonNode node;
        try {
            node = JsonAssertion.resolve(root, JsonAssertion.parse(path));
        } catch (RuntimeException e) {
            node = null;
        }
        if (node == null) return new ScopeText("", KeywordRuleEvaluator.NOTE_SCOPE_NOT_FOUND, false);
        String text;
        if (node.isNull()) text = "";
        else if (node.isValueNode()) text = node.asText();
        else {
            text = node.toString();
            if (text.length() > MAX_JSON_SCOPE_CHARS) text = text.substring(0, MAX_JSON_SCOPE_CHARS);
        }
        return new ScopeText(text, null, false);
    }

    private JsonNode jsonRoot() {
        if (!jsonParsed) {
            jsonParsed = true;
            try {
                JsonNode n = raw.isBlank() ? null : JSON.readTree(raw);
                jsonRoot = n == null || n.isMissingNode() ? null : n;
            } catch (Exception e) {
                jsonRoot = null;
            }
        }
        return jsonRoot;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
