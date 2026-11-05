package com.sitemonitor.service.keyword.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.keyword.KeywordFold;
import com.sitemonitor.service.keyword.rules.KeywordRule.Kind;
import com.sitemonitor.service.keyword.rules.KeywordRule.ScopeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saf kural değerlendirici (kw sözleşmesi §6.2) — girdi: ETKİN kurallar ({@link KeywordRules#effective}), birleşim (AND / OR),
 * kontrolün içerik görünümü ({@link KeywordContent}) ve izlemenin etkin alarm seviyesi. Asla fırlatmaz; "bilinmiyor" sonucu
 * YOK — her kural her zaman sağlandı / ihlal sonucu verir.
 *
 * <ul>
 *   <li><b>Kural seviyesi:</b> kritik eşik varsa → kritik koşul ihlal → {@code CRITICAL}, değilse uyarı koşulu ihlal →
 *       {@code WARNING}, değilse {@code NONE}. Kritik eşik yoksa ihlal → {@code MONITOR} (izlemenin {@code alert_level}'ı).</li>
 *   <li><b>Birleşim</b> (sıra WARNING 1 &lt; HIGH 2 &lt; CRITICAL 3): {@code AND} (hepsi sağlanmalı) — herhangi biri ihlal → ihlal,
 *       seviye = en YÜKSEK; {@code OR} (en az biri) — hepsi ihlal → ihlal, seviye = en DÜŞÜK (en iyi kural belirler). Eşit
 *       sırada açık (kural) seviyesi izleme seviyesine yeğlenir.</li>
 *   <li><b>Bölge bulunamadı</b> ({@code SCOPE_NOT_FOUND}: CSS 0 öğe / JSON yolu yok) ya da <b>geçersiz</b> ({@code SCOPE_INVALID}:
 *       gövde JSON değil) → kural İHLAL, adet 0 — yön ne olursa olsun (beklenen bölge yok). Seviye: kuralın UYARI düzeyi
 *       (kritik eşik varsa {@code WARNING}, yoksa {@code MONITOR}) — eksik bölge yapısal bir sorundur, sayı kademesi değil.</li>
 *   <li><b>METİN</b>: {@link KeywordFold#count} (tavan {@link KeywordFold#MAX_COUNT}, not {@code COUNT_CAPPED}). <b>DÜZENLİ
 *       İFADE</b>: {@link SafeRegex#count} — ilk {@value SafeRegex#REGEX_SCAN_CAP} karakter, tavan {@value SafeRegex#MAX_COUNT};
 *       metin kırpıldıysa ya da zaman bütçesi bittiyse not {@code SCAN_TRUNCATED} (adet alt sınırdır).</li>
 *   <li><b>Zaman bütçesi:</b> düzenli ifade kuralı başına {@value #REGEX_RULE_BUDGET_MS} ms, değerlendirme başına toplam
 *       {@value #REGEX_TOTAL_BUDGET_MS} ms (her kural en az bir arama yapar) — on patolojik kural bile kontrolü uzatamaz.</li>
 * </ul>
 */
public final class KeywordRuleEvaluator {

    private KeywordRuleEvaluator() {}

    public static final String LEVEL_NONE = "NONE";
    public static final String LEVEL_WARNING = "WARNING";
    public static final String LEVEL_CRITICAL = "CRITICAL";
    public static final String LEVEL_MONITOR = "MONITOR";
    public static final String LEVEL_HIGH = "HIGH";

    /** Kural notları (ön yüz {@code keyword.rule.note.<NOT>} ile çevirir). Adlar kalıcıdır. */
    public static final String NOTE_SCOPE_NOT_FOUND = "SCOPE_NOT_FOUND";
    public static final String NOTE_SCOPE_INVALID = "SCOPE_INVALID";
    public static final String NOTE_COUNT_CAPPED = "COUNT_CAPPED";
    public static final String NOTE_SCAN_TRUNCATED = "SCAN_TRUNCATED";

    /** {@code rule_results_json} üst sınırı (karakter). */
    public static final int RESULTS_JSON_MAX = 4_000;
    /** {@code rules_summary} üst sınırı (karakter). */
    public static final int SUMMARY_MAX = 300;
    /** {@code pattern_preview} üst sınırı (karakter). */
    public static final int PREVIEW_MAX = 60;
    public static final long REGEX_RULE_BUDGET_MS = 1_000;
    public static final long REGEX_TOTAL_BUDGET_MS = 4_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Tek kuralın sonucu ({@code rule_results} öğesi). {@code matchStart} / {@code matchEnd}: ilk eşleşmenin bölge metnindeki
     * konumu (alıntı için; yalnız sayfa bölgesinde anlamlı) — JSON'a YAZILMAZ.
     */
    public record RuleResult(String id, String kind, String patternPreview, int count, boolean ok, String level, String note,
                             ScopeType scopeType, int matchStart, int matchEnd) {

        /** API / JSON biçimi (snake_case, sözleşme §6.2). */
        public Map<String, Object> toMap(int previewMax) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("pattern_preview", previewMax >= 0 ? clip(patternPreview, previewMax) : patternPreview);
            m.put("count", count);
            m.put("ok", ok);
            m.put("level", level);
            m.put("note", note);
            return m;
        }
    }

    /**
     * Değerlendirme sonucu.
     *
     * @param breached    kural birleşimi ihlal edildi mi
     * @param level       ihlalin birleşik seviyesi ({@code WARNING} | {@code HIGH} | {@code CRITICAL}); ihlal yoksa {@code null}
     * @param breachLevel {@code breach_level} kolonu: seviye bir KURALIN eşiğinden geldiyse ({@code WARNING} | {@code CRITICAL}),
     *                    izleme seviyesinden geldiyse ya da ihlal yoksa {@code null}
     * @param results     kural sonuçları (kural sırasıyla)
     * @param summary     TR özet (≤ {@value #SUMMARY_MAX}; yalnız ihlalde) — alarm iletisi / posta
     */
    public record Evaluation(boolean breached, String level, String breachLevel, List<RuleResult> results, String summary) {

        /** Kural #1'in sonucu (eski {@code found} / {@code count} / {@code occurrences} tüketicileri için); boşsa {@code null}. */
        public RuleResult primary() {
            return results == null || results.isEmpty() ? null : results.get(0);
        }

        /** Çok kurallı mı (neden kodu {@code RULES_NOT_MET}). */
        public boolean multi() {
            return results != null && results.size() > 1;
        }

        /** Tek kuralın bölge notu ({@code SCOPE_NOT_FOUND} / {@code SCOPE_INVALID}) — neden kodu için; yoksa {@code null}. */
        public String scopeNote() {
            RuleResult p = primary();
            if (p == null || multi()) return null;
            return NOTE_SCOPE_NOT_FOUND.equals(p.note()) || NOTE_SCOPE_INVALID.equals(p.note()) ? p.note() : null;
        }

        /** API listesi. */
        public List<Map<String, Object>> resultsApi() {
            List<Map<String, Object>> out = new ArrayList<>();
            if (results != null) for (RuleResult r : results) out.add(r.toMap(PREVIEW_MAX));
            return out;
        }

        /** {@code rule_results_json} — ≤ {@value #RESULTS_JSON_MAX} karakter (aşarsa desen önizlemeleri kısaltılır). */
        public String resultsJson() {
            return KeywordRuleEvaluator.resultsJson(results);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Kuralları değerlendirir.
     *
     * @param rules        etkin kurallar (boş → ihlal yok)
     * @param logic        "AND" | "OR" (başka değer → AND)
     * @param content      kontrolün içerik görünümü
     * @param monitorLevel izlemenin etkin {@code alert_level}'ı ({@code null} / tanınmayan → WARNING)
     */
    public static Evaluation evaluate(List<KeywordRule> rules, String logic, KeywordContent content, String monitorLevel) {
        if (rules == null || rules.isEmpty() || content == null) {
            return new Evaluation(false, null, null, List.of(), null);
        }
        String monLevel = normalizeLevel(monitorLevel);
        boolean or = KeywordRules.LOGIC_OR.equalsIgnoreCase(logic == null ? "" : logic.trim());
        long totalDeadline = System.nanoTime() + REGEX_TOTAL_BUDGET_MS * 1_000_000L;
        List<RuleResult> results = new ArrayList<>();
        for (KeywordRule r : rules) {
            results.add(evaluateOne(r, content, totalDeadline));
        }
        boolean breached;
        if (or) breached = results.stream().noneMatch(RuleResult::ok);
        else breached = results.stream().anyMatch(x -> !x.ok());
        if (!breached) return new Evaluation(false, null, null, List.copyOf(results), null);

        // seviye birleşimi
        String best = null;     // etkin seviye (WARNING/HIGH/CRITICAL)
        boolean bestExplicit = false;
        for (RuleResult x : results) {
            if (x.ok()) continue;
            boolean explicit = !LEVEL_MONITOR.equals(x.level());
            String eff = explicit ? x.level() : monLevel;
            if (best == null) { best = eff; bestExplicit = explicit; continue; }
            int cmp = rank(eff) - rank(best);
            boolean better = or ? cmp < 0 : cmp > 0;
            if (better || (cmp == 0 && explicit && !bestExplicit)) { best = eff; bestExplicit = explicit; }
        }
        String breachLevel = bestExplicit ? best : null;
        return new Evaluation(true, best, breachLevel, List.copyOf(results), summary(rules, results, or));
    }

    private static RuleResult evaluateOne(KeywordRule r, KeywordContent content, long totalDeadline) {
        String preview = clip(oneLine(r.pattern()), PREVIEW_MAX);
        KeywordContent.ScopeText scope;
        try {
            scope = content.scope(r);
        } catch (RuntimeException e) {
            scope = new KeywordContent.ScopeText("", NOTE_SCOPE_INVALID, false);
        }
        if (scope.note() != null) {   // bölge yok / geçersiz → yön ne olursa olsun ihlal
            String lvl = r.critical() != null ? LEVEL_WARNING : LEVEL_MONITOR;
            return new RuleResult(r.id(), r.kind().name(), preview, 0, false, lvl, scope.note(), r.scopeType(), -1, -1);
        }
        int count;
        int start = -1;
        int end = -1;
        String note = null;
        if (r.kind() == Kind.REGEX) {
            SafeRegex.Compiled c = SafeRegex.compileStored(r.pattern(), r.caseSensitive());
            long now = System.nanoTime();
            long remain = Math.max(0, totalDeadline - now);
            long budget = Math.min(REGEX_RULE_BUDGET_MS * 1_000_000L, remain);
            SafeRegex.Count k = SafeRegex.count(c, scope.text(), SafeRegex.MAX_COUNT, budget);
            count = k.count();
            start = k.firstStart();
            end = k.firstEnd();
            if (k.capped()) note = NOTE_COUNT_CAPPED;
            else if (k.scanTruncated()) note = NOTE_SCAN_TRUNCATED;
        } else {
            String needle = content.needle(r.pattern(), scope);
            KeywordFold.Count k = KeywordFold.count(scope.text(), needle, r.caseSensitive(), KeywordFold.MAX_COUNT);
            count = k.count();
            if (k.firstIndex() >= 0) { start = k.firstIndex(); end = start + needle.length(); }
            if (count >= KeywordFold.MAX_COUNT) note = NOTE_COUNT_CAPPED;
        }
        String level;
        boolean ok;
        if (r.critical() != null) {
            boolean critBreached = !r.critical().operator().healthy(count, r.critical().count());
            boolean warnBreached = !r.operator().healthy(count, r.count());
            level = critBreached ? LEVEL_CRITICAL : warnBreached ? LEVEL_WARNING : LEVEL_NONE;
            ok = !critBreached && !warnBreached;
        } else {
            ok = r.operator().healthy(count, r.count());
            level = ok ? LEVEL_NONE : LEVEL_MONITOR;
        }
        return new RuleResult(r.id(), r.kind().name(), preview, count, ok, level, note, r.scopeType(), start, end);
    }

    // ── birleşik özet ────────────────────────────────────────────────────────────────────────

    /** TR özet (yalnız ihlalde): tek kural "« Fiyat » 0 kez (beklenen: en az 1 kez)", çok kural "2/3 kural sağlanmadı: …". */
    static String summary(List<KeywordRule> rules, List<RuleResult> results, boolean or) {
        List<String> parts = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < results.size(); i++) {
            RuleResult x = results.get(i);
            if (x.ok()) continue;
            failed++;
            parts.add(item(rules.get(i), x));
        }
        String body = String.join("; ", parts);
        String s;
        if (results.size() == 1) s = body;
        else if (or) s = "Kuralların hiçbiri sağlanmadı (" + failed + "/" + results.size() + "): " + body;
        else s = failed + "/" + results.size() + " kural sağlanmadı: " + body;
        return clip(s, SUMMARY_MAX);
    }

    private static String item(KeywordRule r, RuleResult x) {
        String name = "« " + clip(oneLine(r.label() != null ? r.label() : r.pattern()), 40) + " »";
        if (NOTE_SCOPE_NOT_FOUND.equals(x.note())) {
            return name + ": içerik bölgesi bulunamadı (" + r.scopeType().name() + ": " + clip(r.scopeValue(), 40) + ")";
        }
        if (NOTE_SCOPE_INVALID.equals(x.note())) {
            return name + ": yanıt geçerli JSON değil (yol: " + clip(r.scopeValue(), 40) + ")";
        }
        String expected;
        if (r.critical() != null && LEVEL_CRITICAL.equals(x.level())) {
            expected = r.critical().operator().phraseTr(r.critical().count()) + ", kritik eşik";
        } else {
            expected = r.operator().phraseTr(r.count());
        }
        String capped = NOTE_COUNT_CAPPED.equals(x.note()) || NOTE_SCAN_TRUNCATED.equals(x.note()) ? "en az " : "";
        return name + " " + capped + x.count() + " kez (beklenen: " + expected + ")";
    }

    // ── JSON ─────────────────────────────────────────────────────────────────────────────────

    /** Sonuçlar → JSON (≤ {@value #RESULTS_JSON_MAX}); sığmazsa önizleme 60 → 30 → 10 → 0 karaktere kısalır. */
    public static String resultsJson(List<RuleResult> results) {
        if (results == null || results.isEmpty()) return null;
        for (int preview : new int[]{PREVIEW_MAX, 30, 10, 0}) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RuleResult r : results) rows.add(r.toMap(preview));
            try {
                String s = JSON.writeValueAsString(rows);
                if (s.length() <= RESULTS_JSON_MAX) return s;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * API listesi (kontrol sonucu haritasındaki {@code rule_results}, iş JSON'undan da dönebilir) → {@code rule_results_json}
     * (≤ {@value #RESULTS_JSON_MAX}; sığmazsa önizleme kısalır). Bozuk öğe atlanır; boş → {@code null}. Asla fırlatmaz.
     */
    public static String resultsJsonFromApi(Object apiList) {
        if (!(apiList instanceof List<?> l) || l.isEmpty()) return null;
        List<RuleResult> rs = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object cnt = m.get("count");
            rs.add(new RuleResult(m.get("id") == null ? null : String.valueOf(m.get("id")),
                    m.get("kind") == null ? null : String.valueOf(m.get("kind")),
                    m.get("pattern_preview") == null ? null : String.valueOf(m.get("pattern_preview")),
                    cnt instanceof Number n ? n.intValue() : 0, Boolean.TRUE.equals(m.get("ok")),
                    m.get("level") == null ? null : String.valueOf(m.get("level")),
                    m.get("note") == null ? null : String.valueOf(m.get("note")), null, -1, -1));
        }
        return resultsJson(rs);
    }

    /** {@code rule_results_json} → API listesi (bozuk → boş liste). Asla fırlatmaz. */
    public static List<Map<String, Object>> parseResults(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray()) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : root) {
                if (!n.isObject()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", n.path("id").asText(null));
                m.put("kind", n.path("kind").asText(null));
                m.put("pattern_preview", n.path("pattern_preview").asText(null));
                m.put("count", n.path("count").canConvertToInt() ? n.path("count").asInt() : null);
                m.put("ok", n.path("ok").isBoolean() ? n.path("ok").asBoolean() : null);
                m.put("level", n.path("level").asText(null));
                m.put("note", n.hasNonNull("note") ? n.path("note").asText() : null);
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── yardımcılar ──

    /** İzleme seviyesi → WARNING | HIGH | CRITICAL (null / tanınmayan → WARNING). */
    public static String normalizeLevel(String level) {
        if (level == null) return LEVEL_WARNING;
        String s = level.trim().toUpperCase(java.util.Locale.ROOT);
        return LEVEL_HIGH.equals(s) || LEVEL_CRITICAL.equals(s) ? s : LEVEL_WARNING;
    }

    static int rank(String level) {
        if (LEVEL_CRITICAL.equals(level)) return 3;
        if (LEVEL_HIGH.equals(level)) return 2;
        return 1;
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString();
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        if (max <= 0) return "";
        if (s.length() <= max) return s;
        int cut = max - 1;
        if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
        return s.substring(0, Math.max(0, cut)) + "…";
    }
}
