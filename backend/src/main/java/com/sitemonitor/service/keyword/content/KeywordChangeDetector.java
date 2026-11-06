package com.sitemonitor.service.keyword.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.config.RequestLoggingFilter;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.keyword.content.ContentMasker.Mask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * İçerik değişimi çekirdeği (kw sözleşmesi §7.2, D2) — saf: parmak izi, temel karşılaştırması ve SINIRLI fark.
 *
 * <p><b>Metin</b>: satır listesi (seçici boşsa görünür metin satırları — {@code KeywordBodyAnalyzer.visibleLines}; doluysa
 * seçilen öğelerin metni). Her satır sırayla sır maskesinden ({@code RequestLoggingFilter.redactSensitiveFields} + başlık sır
 * değerleri — analizördeki maskeyle aynı) ve {@code change_mask}'ten ({@link ContentMasker}) geçer. Parmak izi = maskeli TÜM
 * metnin SHA-256'sı; temel metni ≤ {@value #BASELINE_TEXT_MAX} karakter saklanır (parmak izi tam metinden).
 *
 * <p><b>{@code spec_hash}</b> = (maske, seçici, istek parmak izi, {@value #CHANGE_ALGO_VERSION}) özeti — ayar, istek tanımı ya da
 * algoritma değişince eski temel YOK sayılır (başka bir metinle ya da başka bir istekle ölçülmüştür, kıyaslanmaz).
 *
 * <p><b>Fark</b> (doğrusal): ortak önek / sonek satırları sayılır, aradaki bölge temelden {@code removed}, güncelden
 * {@code added}; her biri ilk {@value #SUMMARY_LINES} satır, satır ≤ {@value #SUMMARY_LINE_MAX} karakter; JSON ≤
 * {@value #SUMMARY_JSON_MAX}. Temel metni kırpılmışsa güncel metin AYNI uzunlukta kırpılarak kıyaslanır ve {@code truncated}
 * yazılır (özet eksik olabilir — değişim kararı tam parmak izinden).
 */
public final class KeywordChangeDetector {

    private KeywordChangeDetector() {}

    /** Algoritma sürümü — değişince tüm temeller yeniden alınır ({@code spec_hash}). 2: istek parmak izi eklendi (2026-11-06). */
    public static final int CHANGE_ALGO_VERSION = 2;
    public static final int BASELINE_TEXT_MAX = 64_000;
    public static final int SUMMARY_JSON_MAX = 4_000;
    public static final int SUMMARY_LINES = 20;
    public static final int SUMMARY_LINE_MAX = 200;
    /** Değerlendirilen en çok satır (görünür metin 2 M karakterden). */
    public static final int MAX_LINES = 100_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ölçüm anlık görüntüsü: parmak izi, saklanacak metin (≤ 64 000), kırpıldı mı, maskeli satırlar, satır sayısı, metin uzunluğu. */
    public record Snapshot(String fingerprint, String text, boolean textTruncated, List<String> lines, int lineCount,
                           long chars) {}

    /** Satırlar → maskeli anlık görüntü. {@code secrets}: bilinen sır değerleri (başlık değerleri) — boş olabilir. */
    public static Snapshot snapshot(List<String> rawLines, Set<Mask> masks, Collection<String> secrets) {
        List<String> lines = new ArrayList<>();
        if (rawLines != null) {
            for (String l : rawLines) {
                if (lines.size() >= MAX_LINES) break;
                if (l == null) continue;
                String t = l.trim();
                if (t.isEmpty()) continue;
                t = RequestLoggingFilter.redactSensitiveFields(t);
                if (secrets != null && !secrets.isEmpty()) t = SecretMask.maskValues(t, secrets);
                t = ContentMasker.apply(t, masks);
                lines.add(t);
            }
        }
        String full = String.join("\n", lines);
        boolean truncated = full.length() > BASELINE_TEXT_MAX;
        String stored = truncated ? cut(full, BASELINE_TEXT_MAX) : full;
        return new Snapshot(sha256Hex(full), stored, truncated, List.copyOf(lines), lines.size(), full.length());
    }

    /**
     * {@code spec_hash} — maske (kanonik CSV), seçici (kırpılmış), İSTEK parmak izi ({@link
     * com.sitemonitor.service.keyword.KeywordRequestRules#requestFingerprint}) ve algoritma sürümünün ilk 16 onaltılığı.
     *
     * <p>İstek parmak izi hata avı 2026-11-06 H4#3 ile eklendi: temel ölçüldüğü İSTEĞE bağlıdır (URL, yöntem, gövde, kimlik,
     * başlıklar, yönlendirme). Eskiden yalnız maske + seçici giriyordu; eski URL'yle koşan bayat bir görev yeni URL'nin temelini
     * alabiliyor (kalıcı sahte KEYWORD_CHANGED), yöntem / gövde / başlık değişince temel silinmiyordu. Artık tanım değişince eski
     * temel YOK sayılır ve ilk sağlıklı zamanlanmış kontrolde yenilenir (alarm açılmaz). Bu sürümle önceki biçimde yazılmış her
     * temel de bir kez yenilenir ({@value #CHANGE_ALGO_VERSION}).
     */
    public static String specHash(String maskCsv, String selector, String requestFingerprint) {
        String m = ContentMasker.toCsv(ContentMasker.parseStored(maskCsv));
        String sel = selector == null ? "" : selector.trim();
        String req = requestFingerprint == null ? "" : requestFingerprint;
        return sha256Hex("v" + CHANGE_ALGO_VERSION + "|" + (m == null ? "" : m) + "|" + sel + "|" + req).substring(0, 16);
    }

    /** Kayıtlı izlemenin {@code spec_hash}'i ({@code headersPlain}: çağıranın çözdüğü özel başlıklar; çözülemezse null). */
    public static String specHashFor(com.sitemonitor.model.KeywordMonitor m, String headersPlain) {
        return specHash(m == null ? null : m.getChangeMask(), m == null ? null : m.getChangeSelector(),
                com.sitemonitor.service.keyword.KeywordRequestRules.requestFingerprint(m, headersPlain));
    }

    /** Kontrol tanımının {@code spec_hash}'i — kayıtlı izlemeninkiyle ({@link #specHashFor(com.sitemonitor.model.KeywordMonitor,
     *  String)}) aynı değer. */
    public static String specHashFor(com.sitemonitor.service.keyword.KeywordCheckSpec spec) {
        return specHash(spec.changeMask(), spec.changeSelector(),
                com.sitemonitor.service.keyword.KeywordRequestRules.requestFingerprint(spec));
    }

    /** Karşılaştırmada kullanılan temel alanları (+ alarm postası için alınma zamanı / kaynağı — karşılaştırmaya girmez). */
    public record BaselineView(String fingerprint, String specHash, String text, boolean textTruncated, String takenAt,
                               String source) {
        public BaselineView(String fingerprint, String specHash, String text, boolean textTruncated) {
            this(fingerprint, specHash, text, textTruncated, null, null);
        }
    }

    /**
     * Karşılaştırma sonucu: {@code changed} {@code null} = temel yok ya da başka ayarla alınmış ({@code baselineMissing});
     * {@code summary} yalnız değişimde.
     */
    public record Comparison(Boolean changed, boolean baselineMissing, Summary summary) {
        public static final Comparison MISSING = new Comparison(null, true, null);
    }

    /** Temel ile güncel ölçümü karşılaştırır. */
    public static Comparison compare(BaselineView baseline, Snapshot current, String currentSpecHash) {
        if (current == null) return new Comparison(null, false, null);
        if (baseline == null || baseline.fingerprint() == null || baseline.specHash() == null
                || !baseline.specHash().equals(currentSpecHash)) {
            return Comparison.MISSING;
        }
        if (baseline.fingerprint().equals(current.fingerprint())) return new Comparison(false, false, null);
        return new Comparison(true, false, diff(baseline.text(), baseline.textTruncated(), current));
    }

    // ── Fark ─────────────────────────────────────────────────────────────────────────────────

    /** Sınırlı fark özeti. */
    public record Summary(int linesBefore, int linesAfter, List<String> removed, List<String> added, int removedTotal,
                          int addedTotal, boolean truncated) {

        /** "N satır değişti" — kaldırılan ve eklenen satırların büyüğü (bir satır düzenlemesi = 1). */
        public int changedLines() {
            return Math.max(removedTotal, addedTotal);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lines_before", linesBefore);
            m.put("lines_after", linesAfter);
            m.put("removed", removed);
            m.put("added", added);
            m.put("removed_total", removedTotal);
            m.put("added_total", addedTotal);
            m.put("truncated", truncated);
            return m;
        }

        /** JSON (≤ {@value #SUMMARY_JSON_MAX}). */
        public String toJson() {
            return summaryJson(this);
        }
    }

    /** Temel metni (maskeli, belki kırpılmış) ile güncel anlık görüntünün farkı. */
    public static Summary diff(String baselineText, boolean baselineTruncated, Snapshot current) {
        List<String> base = splitLines(baselineText);
        List<String> cur;
        if (baselineTruncated) {
            cur = splitLines(cut(String.join("\n", current.lines()), BASELINE_TEXT_MAX));
        } else {
            cur = current.lines();
        }
        int nb = base.size();
        int nc = cur.size();
        int p = 0;
        while (p < nb && p < nc && base.get(p).equals(cur.get(p))) p++;
        int s = 0;
        while (s < nb - p && s < nc - p && base.get(nb - 1 - s).equals(cur.get(nc - 1 - s))) s++;
        List<String> removed = window(base, p, nb - s);
        List<String> added = window(cur, p, nc - s);
        return new Summary(nb, current.lineCount(), removed, added, Math.max(0, nb - s - p),
                Math.max(0, nc - s - p), baselineTruncated);
    }

    private static List<String> window(List<String> lines, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < to && out.size() < SUMMARY_LINES; i++) out.add(clipLine(lines.get(i), SUMMARY_LINE_MAX));
        return out;
    }

    static String summaryJson(Summary s) {
        if (s == null) return null;
        int[][] steps = {{SUMMARY_LINES, SUMMARY_LINE_MAX}, {SUMMARY_LINES, 100}, {10, 100}, {5, 60}, {0, 0}};
        for (int[] st : steps) {
            Summary t = new Summary(s.linesBefore(), s.linesAfter(), shrink(s.removed(), st[0], st[1]),
                    shrink(s.added(), st[0], st[1]), s.removedTotal(), s.addedTotal(), s.truncated());
            try {
                String j = JSON.writeValueAsString(t.toMap());
                if (j.length() <= SUMMARY_JSON_MAX) return j;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static List<String> shrink(List<String> lines, int max, int lineMax) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        for (String l : lines) {
            if (out.size() >= max) break;
            out.add(clipLine(l, lineMax));
        }
        return out;
    }

    /**
     * Sonuç haritasındaki {@code change_summary} (harita — iş JSON'undan gelince sayılar Integer / Long olabilir; ya da JSON metni)
     * → kolon JSON'u (≤ {@value #SUMMARY_JSON_MAX}); okunamazsa {@code null}. Asla fırlatmaz.
     */
    public static String summaryJsonOf(Object raw) {
        Map<String, Object> m = raw instanceof Map<?, ?> mm ? stringKeys(mm) : raw instanceof String js ? parseSummary(js) : null;
        if (m == null) return null;
        try {
            return summaryJson(new Summary(intOf(m.get("lines_before")), intOf(m.get("lines_after")), stringList(m.get("removed")),
                    stringList(m.get("added")), intOf(m.get("removed_total")), intOf(m.get("added_total")),
                    Boolean.TRUE.equals(m.get("truncated"))));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> stringKeys(Map<?, ?> mm) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : mm.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null && out.size() < SUMMARY_LINES) out.add(String.valueOf(x));
        return out;
    }

    /** {@code change_summary} JSON → API nesnesi (bozuk → {@code null}). Asla fırlatmaz. */
    public static Map<String, Object> parseSummary(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || !n.isObject()) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lines_before", n.path("lines_before").asInt(0));
            m.put("lines_after", n.path("lines_after").asInt(0));
            m.put("removed", strings(n.path("removed")));
            m.put("added", strings(n.path("added")));
            m.put("removed_total", n.path("removed_total").asInt(0));
            m.put("added_total", n.path("added_total").asInt(0));
            m.put("truncated", n.path("truncated").asBoolean(false));
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) for (JsonNode x : arr) if (out.size() < SUMMARY_LINES) out.add(x.asText(""));
        return out;
    }

    /** Özetten "N satır değişti" sayısı (Map ya da JSON metni); okunamazsa {@code null}. */
    public static Integer changedLines(Object summary) {
        Map<String, Object> m = null;
        if (summary instanceof Map<?, ?> raw) {
            m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
        } else if (summary instanceof String s) {
            m = parseSummary(s);
        }
        if (m == null) return null;
        int r = m.get("removed_total") instanceof Number a ? a.intValue() : 0;
        int a = m.get("added_total") instanceof Number b ? b.intValue() : 0;
        return Math.max(r, a);
    }

    // ── yardımcılar ──

    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int from = 0;
        int n = text.length();
        while (from <= n && out.size() < MAX_LINES) {
            int nl = text.indexOf('\n', from);
            if (nl < 0) { out.add(text.substring(from)); break; }
            out.add(text.substring(from, nl));
            from = nl + 1;
        }
        return out;
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        int c = max;
        if (Character.isHighSurrogate(s.charAt(c - 1))) c--;
        return s.substring(0, c);
    }

    private static String clipLine(String s, int max) {
        if (s == null) return "";
        if (max <= 0) return "";
        if (s.length() <= max) return s;
        return cut(s, max - 1) + "…";
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 yok", e);
        }
    }
}
