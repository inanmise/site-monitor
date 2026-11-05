package com.sitemonitor.service.keyword.rules;

import com.sitemonitor.service.keyword.KeywordSecretWindow;
import com.sitemonitor.service.keyword.KeywordBodyAnalyzer;
import com.sitemonitor.service.keyword.KeywordFold;
import com.google.re2j.Matcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canlı eşleşme bağlamları (kw sözleşmesi §8.2 / §10.3, E2) — kural başına ilk ≤ {@value #MAX_CONTEXTS} eşleşmenin çevresi
 * ({@code before} / {@code match} / {@code after}, ±{@value #RADIUS} karakter), kuralın KENDİ bölgesinde (sayfa / CSS / JSON) ve
 * kuralın kendi aramasıyla (metin: Türkçe katlamalı; desen: RE2J). Saf; sır maskesi (başlık değerleri + hassas alan adları)
 * uygulanır. Sınırlı: kural başına en çok {@value #MAX_CONTEXTS} arama adımı, desende ilk 512 K karakter ve süre bütçesi.
 */
public final class KeywordMatchContexts {

    private KeywordMatchContexts() {}

    /** Kural başına en çok bağlam. */
    public static final int MAX_CONTEXTS = 5;
    /** Eşleşmenin iki yanında gösterilen karakter. */
    public static final int RADIUS = 60;
    /** Eşleşme metninin gösterim tavanı. */
    public static final int MATCH_MAX = 200;
    /** Desen kuralında tüm bağlam aramalarının süre bütçesi (ms). */
    public static final long REGEX_BUDGET_MS = 1_000;

    /**
     * @param rules      etkin kurallar
     * @param results    aynı ölçümün kural sonuçları (sayımlar buradan; boyları kurallarla aynı olmalı, değilse sayım bağlamdan)
     * @param content    aynı ölçümün içerik görünümü
     * @param pageSource sayfa bölgesinin kaynak etiketi ({@code RAW} | {@code VISIBLE})
     * @param secrets    bilinen sır değerleri
     * @return kural başına {@code {id, kind, pattern_preview, count, note, contexts:[{before, match, after, source}]}}
     */
    public static List<Map<String, Object>> forRules(List<KeywordRule> rules, List<KeywordRuleEvaluator.RuleResult> results,
                                                     KeywordContent content, String pageSource, Collection<String> secrets) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rules == null || content == null) return out;
        for (int i = 0; i < rules.size() && i < KeywordRules.MAX_RULES; i++) {
            KeywordRule r = rules.get(i);
            KeywordRuleEvaluator.RuleResult res = results != null && i < results.size() ? results.get(i) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id());
            row.put("kind", r.kind().name());
            row.put("pattern_preview", KeywordRuleEvaluator.clip(r.pattern(), KeywordRuleEvaluator.PREVIEW_MAX));
            KeywordContent.ScopeText scope;
            try {
                scope = content.scope(r);
            } catch (RuntimeException e) {
                scope = new KeywordContent.ScopeText("", KeywordRuleEvaluator.NOTE_SCOPE_INVALID, false);
            }
            String source = switch (r.scopeType()) {
                case CSS -> "CSS";
                case JSON -> "JSON";
                case PAGE -> pageSource == null ? "RAW" : pageSource;
            };
            List<Map<String, Object>> contexts = scope.note() != null ? List.of()
                    : r.kind() == KeywordRule.Kind.REGEX ? regexContexts(r, scope.text(), source, secrets)
                    : textContexts(r, content.needle(r.pattern(), scope), scope.text(), source, secrets);
            row.put("count", res != null ? res.count() : contexts.size());
            row.put("note", res != null ? res.note() : scope.note());
            row.put("contexts", contexts);
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> textContexts(KeywordRule r, String needle, String text, String source,
                                                          Collection<String> secrets) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) return out;
        String hay = r.caseSensitive() ? text : KeywordFold.fold(text);
        String n = r.caseSensitive() ? needle : KeywordFold.fold(needle);
        int from = 0;
        for (int step = 0; step < MAX_CONTEXTS; step++) {
            int idx = hay.indexOf(n, from);
            if (idx < 0) break;
            out.add(context(text, idx, idx + n.length(), source, secrets));
            from = idx + n.length();
        }
        return out;
    }

    private static List<Map<String, Object>> regexContexts(KeywordRule r, String text, String source, Collection<String> secrets) {
        List<Map<String, Object>> out = new ArrayList<>();
        SafeRegex.Compiled c = SafeRegex.compileStored(r.pattern(), r.caseSensitive());
        if (c == null || text == null || text.isEmpty()) return out;
        String cut = text.length() > SafeRegex.REGEX_SCAN_CAP ? text.substring(0, SafeRegex.REGEX_SCAN_CAP) : text;
        String hay = r.caseSensitive() ? cut : KeywordFold.fold(cut);
        long deadline = System.nanoTime() + REGEX_BUDGET_MS * 1_000_000L;
        Matcher m = c.pattern().matcher(hay);
        int pos = 0;
        for (int step = 0; step < MAX_CONTEXTS && pos <= hay.length(); step++) {
            if (System.nanoTime() > deadline || !m.find(pos)) break;
            int s = m.start();
            int e = m.end();
            if (e <= s) { pos = s + 1; continue; }   // boş eşleşme (kayıtta reddedilir — savunma)
            out.add(context(cut, s, e, source, secrets));
            pos = e;
        }
        return out;
    }

    /**
     * Bağlam: maske önce / eşleşme / sonra parçalarının BİRLEŞİMİNE uygulanır, sonra bölünür (kw son inceleme D-9 —
     * {@link KeywordSecretWindow}); pencere kenarındaki sır tam kapsanacak biçimde genişletilir. Sır parça sınırını aşıyorsa
     * bağlam tek maskeli parça olarak {@code before}'da döner ({@code match} / {@code after} boş).
     */
    static Map<String, Object> context(String text, int s, int e, String source, Collection<String> secrets) {
        int[] w = KeywordSecretWindow.expand(text, Math.max(0, s - RADIUS), Math.min(text.length(), e + RADIUS), secrets);
        int b = Math.min(w[0], s);
        int a = Math.max(w[1], e);
        KeywordSecretWindow.Pieces p = KeywordSecretWindow.mask(text, b, s, e, a, secrets);
        String lead = b > 0 ? "…" : "";
        String tail = a < text.length() ? "…" : "";
        Map<String, Object> c = new LinkedHashMap<>();
        if (p.joined()) {
            c.put("before", lead + KeywordBodyAnalyzer.collapseWs(p.before()) + tail);
            c.put("match", "");
            c.put("after", "");
        } else {
            c.put("before", lead + KeywordBodyAnalyzer.collapseWs(p.before()));
            String match = p.match();
            c.put("match", match.length() > MATCH_MAX ? match.substring(0, MATCH_MAX) + "…" : match);
            c.put("after", KeywordBodyAnalyzer.collapseWs(p.after()) + tail);
        }
        c.put("source", source);
        return c;
    }
}
