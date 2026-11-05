package com.sitemonitor.service.keyword.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.keyword.rules.KeywordRule.Kind;
import com.sitemonitor.service.keyword.rules.KeywordRule.Op;
import com.sitemonitor.service.keyword.rules.KeywordRule.Scope;
import com.sitemonitor.service.keyword.rules.KeywordRule.ScopeType;
import com.sitemonitor.service.keyword.rules.KeywordRule.Threshold;
import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Anahtar kelime kurallarının biçimi (kw sözleşmesi §6.1): istek doğrulaması (SIKI — 400 {@code VALIDATION_FAILED} + alan),
 * saklı değerin okunması (HOŞGÖRÜLÜ — bozuk kural düşer, hiç kural kalmazsa eski tek kelime alanları), JSON / API yazımı ve
 * eski tek kelime alanlarıyla eşleme.
 *
 * <p><b>Tel biçimi</b> ({@code rules_json} ve API {@code rules} aynı, anahtarlar snake_case): {@code id}, {@code kind},
 * {@code pattern}, {@code case_sensitive}, {@code operator}, {@code count}, {@code critical} ({@code {operator, count}} ya da
 * null), {@code scope} ({@code {type, value}} ya da null = sayfa), {@code label}. Alan hatası anahtarı istek anahtarıdır:
 * {@code rules}, {@code rules[2].pattern}, {@code rules[0].critical} …
 *
 * <p><b>Anlamsız koşul</b> (B10, kayıtta 400): {@code GTE 0} (her zaman sağlıklı), {@code LT 0} (asla sağlanmaz), tavan üstü eşik;
 * ayrıca sayım tavanda durduğu için {@code LTE tavan} (her zaman sağlıklı) ve {@code GT tavan} (asla sağlanmaz). Aynı kural eski tek
 * alan yolunda ({@code operator} / {@code matchCount}) {@link #requireMeaningfulLegacy} ile.
 *
 * <p><b>İki eşik</b> (400 {@code rules[i].critical}): aynı yön ailesi — üst sınır (LTE, LT: az olmalı) ya da alt sınır (GTE, GT: çok
 * olmalı); {@code EQ} ile kritik YOK. Etkin sınırla: üst sınırda kritik &gt; uyarı, alt sınırda kritik &lt; uyarı; eşitlik reddedilir.
 */
public final class KeywordRules {

    private KeywordRules() {}

    public static final int MAX_RULES = 10;
    /** {@code rules_json} üst sınırı (karakter). */
    public static final int MAX_JSON_CHARS = 16_000;
    /** Metin deseni = eski {@code keyword} kolonu (VARCHAR(255)). */
    public static final int MAX_TEXT_PATTERN = 255;
    public static final int MAX_REGEX_PATTERN = SafeRegex.MAX_PATTERN;
    public static final int MAX_LABEL = 80;
    public static final String LOGIC_AND = "AND";
    public static final String LOGIC_OR = "OR";
    /** Eski izlemenin tek kuralının kimliği. */
    public static final String LEGACY_ID = "r1";

    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,20}");
    private static final ObjectMapper JSON = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  İstek (sıkı)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * İstek gövdesindeki {@code rules} değerini doğrular. {@code null} → {@code null} (anahtar yok sayılsın); boş liste → boş
     * liste (çağıran {@code rules_json}'ı temizler, eski alanlar gövdeden). Geçersiz → {@link FieldValidationException}.
     */
    public static List<KeywordRule> parseRequest(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) {
            throw new FieldValidationException("rules", Msg.t("Kurallar bir liste olmalı.", "Rules must be a list."));
        }
        if (list.size() > MAX_RULES) {
            throw new FieldValidationException("rules", Msg.t("En fazla " + MAX_RULES + " kural tanımlanabilir.",
                    "At most " + MAX_RULES + " rules can be defined."));
        }
        List<KeywordRule> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> reserved = new HashSet<>();   // açık kimlikler — otomatik kimlik bunlarla çakışmasın
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("id") != null && !m.get("id").toString().isBlank()) {
                reserved.add(m.get("id").toString().trim());
            }
        }
        for (int i = 0; i < list.size(); i++) {
            KeywordRule r = parseOne(list.get(i), i, ids, reserved);
            ids.add(r.id());
            out.add(r);
        }
        if (!out.isEmpty() && toJson(out).length() > MAX_JSON_CHARS) {
            throw new FieldValidationException("rules", Msg.t("Kurallar çok uzun (en fazla " + MAX_JSON_CHARS + " karakter) — "
                    + "desen, etiket ya da seçicileri kısaltın.", "The rules are too long (at most " + MAX_JSON_CHARS
                    + " characters) — shorten the patterns, labels or selectors."));
        }
        return List.copyOf(out);
    }

    private static KeywordRule parseOne(Object raw, int i, Set<String> takenIds, Set<String> reserved) {
        String f = "rules[" + i + "]";
        if (!(raw instanceof Map<?, ?> m)) {
            throw new FieldValidationException(f, Msg.t("Kural geçersiz (nesne bekleniyor).", "The rule is invalid (an object is expected)."));
        }
        // id — istemci üretir; yoksa sıra kimliği atanır (çakışmasız)
        String id = str(m.get("id"));
        if (id == null || id.isBlank()) {
            int k = i + 1;
            id = "r" + k;
            while ((takenIds.contains(id) || reserved.contains(id)) && k < 1000) id = "r" + (++k);
        } else {
            id = id.trim();
            if (!ID.matcher(id).matches()) {
                throw new FieldValidationException(f + ".id", Msg.t("Kural kimliği küçük harf, rakam ve tire içerebilir (en çok 20 karakter).",
                        "The rule id may contain lowercase letters, digits and hyphens (at most 20 characters)."));
            }
            if (takenIds.contains(id)) {
                throw new FieldValidationException(f + ".id", Msg.t("Kural kimliği listede tekrar ediyor: " + id,
                        "The rule id is repeated in the list: " + id));
            }
        }
        // tür
        Object kindRaw = m.get("kind");
        Kind kind;
        if (kindRaw == null || kindRaw.toString().isBlank()) kind = Kind.TEXT;
        else {
            String k = kindRaw.toString().trim().toUpperCase(Locale.ROOT);
            if ("TEXT".equals(k)) kind = Kind.TEXT;
            else if ("REGEX".equals(k)) kind = Kind.REGEX;
            else throw new FieldValidationException(f + ".kind", Msg.t("Kural türü TEXT (metin) ya da REGEX (düzenli ifade) olmalı.",
                    "The rule kind must be TEXT or REGEX."));
        }
        boolean cs = bool(m.get("case_sensitive"), f + ".case_sensitive");
        // desen
        String pattern = str(m.get("pattern"));
        validatePattern(kind, pattern, cs, f + ".pattern");
        // koşul
        int cap = kind == Kind.REGEX ? SafeRegex.MAX_COUNT : com.sitemonitor.service.keyword.KeywordFold.MAX_COUNT;
        Op op = m.get("operator") == null ? Op.GTE : Op.parse(m.get("operator"));
        if (op == null) {
            throw new FieldValidationException(f + ".operator", Msg.t("Koşul işleci GTE, LTE, EQ, GT ya da LT olmalı.",
                    "The condition operator must be GTE, LTE, EQ, GT or LT."));
        }
        int count = m.get("count") == null ? 1 : intIn(m.get("count"), 0, cap, f + ".count");
        String why = meaningless(op, count, cap);
        if (why != null) throw new FieldValidationException(f + ".count", why);
        // kritik eşik
        Threshold critical = null;
        Object critRaw = m.get("critical");
        if (critRaw != null) {
            if (!(critRaw instanceof Map<?, ?> cm)) {
                throw new FieldValidationException(f + ".critical", Msg.t("Kritik eşik geçersiz (işleç ve adet bekleniyor).",
                        "The critical threshold is invalid (operator and count are expected)."));
            }
            Op cop = Op.parse(cm.get("operator"));
            if (cop == null) {
                throw new FieldValidationException(f + ".critical", Msg.t("Kritik eşiğin işleci GTE, LTE, GT ya da LT olmalı.",
                        "The critical threshold operator must be GTE, LTE, GT or LT."));
            }
            int ccount = intIn(cm.get("count"), 0, cap, f + ".critical");
            String cwhy = meaningless(cop, ccount, cap);
            if (cwhy != null) throw new FieldValidationException(f + ".critical", cwhy);
            String twoWhy = twoLevelProblem(op, count, cop, ccount);
            if (twoWhy != null) throw new FieldValidationException(f + ".critical", twoWhy);
            critical = new Threshold(cop, ccount);
        }
        // bölge
        Scope scope = parseScope(m.get("scope"), f + ".scope");
        // etiket
        String label = str(m.get("label"));
        if (label != null) {
            label = label.trim();
            if (label.isEmpty()) label = null;
            else if (label.length() > MAX_LABEL) {
                throw new FieldValidationException(f + ".label", Msg.t("Etiket en fazla " + MAX_LABEL + " karakter olabilir.",
                        "The label can be at most " + MAX_LABEL + " characters."));
            } else if (hasControl(label)) {
                throw new FieldValidationException(f + ".label", Msg.t("Etiket satır sonu ya da denetim karakteri içeremez.",
                        "The label cannot contain a line break or control character."));
            }
        }
        return new KeywordRule(id, kind, pattern, cs, op, count, critical, scope, label);
    }

    private static void validatePattern(Kind kind, String pattern, boolean cs, String field) {
        if (pattern == null || pattern.isBlank()) {
            throw new FieldValidationException(field, kind == Kind.REGEX
                    ? Msg.t("Düzenli ifade boş olamaz.", "The regular expression cannot be empty.")
                    : Msg.t("Aranacak metin boş olamaz.", "The text to search for cannot be empty."));
        }
        if (kind == Kind.TEXT) {
            if (pattern.length() > MAX_TEXT_PATTERN) {
                throw new FieldValidationException(field, Msg.t("Aranacak metin en fazla " + MAX_TEXT_PATTERN + " karakter olabilir.",
                        "The text to search for can be at most " + MAX_TEXT_PATTERN + " characters."));
            }
            if (pattern.indexOf('\r') >= 0 || pattern.indexOf('\n') >= 0) {
                throw new FieldValidationException(field, Msg.t("Aranacak metin satır sonu içeremez.",
                        "The text to search for cannot contain a line break."));
            }
            return;
        }
        SafeRegex.RegexError e = SafeRegex.validate(pattern, cs);
        if (e != null) throw new FieldValidationException(field, Msg.t(e.reasonTr(), e.reasonEn()));
    }

    private static Scope parseScope(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new FieldValidationException(field, Msg.t("Arama bölgesi geçersiz (tür ve değer bekleniyor).",
                    "The search region is invalid (type and value are expected)."));
        }
        String type = str(m.get("type"));
        ScopeType t;
        if (type == null || type.isBlank() || "PAGE".equalsIgnoreCase(type.trim())) return null;
        if ("CSS".equalsIgnoreCase(type.trim())) t = ScopeType.CSS;
        else if ("JSON".equalsIgnoreCase(type.trim())) t = ScopeType.JSON;
        else throw new FieldValidationException(field, Msg.t("Arama bölgesi PAGE (sayfa), CSS ya da JSON olmalı.",
                    "The search region must be PAGE, CSS or JSON."));
        String value = str(m.get("value"));
        String why = t == ScopeType.CSS ? KeywordScopes.validateCss(value) : KeywordScopes.validateJsonPath(value);
        if (why != null) throw new FieldValidationException(field, why);
        return new Scope(t, value.trim());
    }

    /**
     * Anlamsız koşul iletisi ya da {@code null} (B10). {@code cap} sayım tavanıdır (sayım bu değerde durur).
     */
    public static String meaningless(Op op, int count, int cap) {
        if (op == Op.GTE && count == 0) {
            return Msg.t("\"En az 0 kez\" koşulu her zaman sağlanır, alarm hiç açılmaz. En az 1 seçin ya da başka bir koşul kullanın.",
                    "\"At least 0 times\" is always met, so no alarm would ever open. Choose at least 1 or another condition.");
        }
        if (op == Op.LT && count == 0) {
            return Msg.t("\"0 kezden az\" koşulu hiçbir zaman sağlanamaz, izleme hep alarmda kalır. \"En fazla 0 kez\" (hiç geçmemeli) seçin.",
                    "\"Fewer than 0 times\" can never be met, so the monitor would always alarm. Choose \"at most 0 times\" (must not appear).");
        }
        if (count > cap) {
            return Msg.t("Eşik en fazla " + cap + " olabilir (sayım bu değerde durur).",
                    "The threshold can be at most " + cap + " (counting stops at this value).");
        }
        if (op == Op.LTE && count >= cap) {
            return Msg.t("\"En fazla " + cap + " kez\" koşulu her zaman sağlanır (sayım " + cap + "'de durur). Daha küçük bir eşik seçin.",
                    "\"At most " + cap + " times\" is always met (counting stops at " + cap + "). Choose a smaller threshold.");
        }
        if (op == Op.GT && count >= cap) {
            return Msg.t("\"" + cap + " kezden fazla\" koşulu sağlanamaz (sayım " + cap + "'de durur). Daha küçük bir eşik seçin.",
                    "\"More than " + cap + " times\" can never be met (counting stops at " + cap + "). Choose a smaller threshold.");
        }
        return null;
    }

    /** İki eşik tutarlılığı (uyarı ↔ kritik) — sorun yoksa {@code null}. */
    public static String twoLevelProblem(Op warn, int warnCount, Op crit, int critCount) {
        if (warn == Op.EQ || crit == Op.EQ) {
            return Msg.t("Tam eşitlik (=) koşulunda kritik eşik tanımlanamaz.",
                    "A critical threshold cannot be set with an exact (=) condition.");
        }
        if (warn.upper() != crit.upper()) {
            return Msg.t("Kritik eşik uyarı koşuluyla aynı yönde olmalı (ikisi de \"en az / fazla\" ya da ikisi de \"en fazla / az\").",
                    "The critical threshold must go in the same direction as the warning condition (both \"at least / more\" or "
                            + "both \"at most / fewer\").");
        }
        long w = warn.bound(warnCount);
        long c = crit.bound(critCount);
        if (warn.upper() && c <= w) {
            return Msg.t("Kritik eşik uyarı eşiğinden daha geniş olmalı: \"az olmalı\" kuralında kritik sınır uyarı sınırından BÜYÜK olmalı "
                            + "(ör. uyarı en fazla 5, kritik en fazla 20).",
                    "The critical threshold must be wider than the warning one: in a \"fewer than\" rule the critical limit must be "
                            + "GREATER than the warning limit (e.g. warning at most 5, critical at most 20).");
        }
        if (warn.lower() && c >= w) {
            return Msg.t("Kritik eşik uyarı eşiğinden daha geniş olmalı: \"çok olmalı\" kuralında kritik sınır uyarı sınırından KÜÇÜK olmalı "
                            + "(ör. uyarı en az 10, kritik en az 3).",
                    "The critical threshold must be wider than the warning one: in an \"at least\" rule the critical limit must be "
                            + "SMALLER than the warning limit (e.g. warning at least 10, critical at least 3).");
        }
        return null;
    }

    /**
     * Eski tek alan yolunun (B10) aynı kuralı: {@code operator} / {@code matchCount} gövdesi anlamsızsa 400 {@code matchCount}.
     * Saklı eski değer çalışmada değerlendirilmeye devam eder (bu yalnız KAYIT kapısı).
     */
    public static void requireMeaningfulLegacy(String operator, Integer matchCount) {
        Op op = operator == null ? Op.GTE : Op.parse(operator);
        if (op == null) op = Op.GTE;
        int n = matchCount == null ? 1 : matchCount;
        String why = meaningless(op, n, com.sitemonitor.service.keyword.KeywordFold.MAX_COUNT);
        if (why != null) throw new FieldValidationException("matchCount", why);
    }

    /** İstek {@code ruleLogic} → "AND" | "OR"; boş → {@code null} (anahtar yok sayılsın); geçersiz → 400 {@code ruleLogic}. */
    public static String parseLogic(Object raw) {
        if (raw == null || raw.toString().isBlank()) return null;
        String s = raw.toString().trim().toUpperCase(Locale.ROOT);
        if (LOGIC_AND.equals(s) || LOGIC_OR.equals(s)) return s;
        throw new FieldValidationException("ruleLogic", Msg.t("Kural birleşimi AND (hepsi sağlanmalı) ya da OR (en az biri) olmalı.",
                "The rule logic must be AND (all must be met) or OR (at least one)."));
    }

    /** Saklı {@code rule_logic} → etkin değer (boş / bozuk → AND). */
    public static String effectiveLogic(String stored) {
        return stored != null && LOGIC_OR.equalsIgnoreCase(stored.trim()) ? LOGIC_OR : LOGIC_AND;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Saklı değer (hoşgörülü)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** {@code rules_json} → kurallar; bozuk kural düşer, bozuk metin → boş liste. Asla fırlatmaz. */
    public static List<KeywordRule> readStored(String json) {
        if (json == null || json.isBlank()) return List.of();
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            return List.of();
        }
        if (root == null || !root.isArray()) return List.of();
        List<KeywordRule> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : root) {
            if (out.size() >= MAX_RULES) break;
            KeywordRule r = readOne(n, out.size(), ids);
            if (r != null) { ids.add(r.id()); out.add(r); }
        }
        return List.copyOf(out);
    }

    private static KeywordRule readOne(JsonNode n, int idx, Set<String> ids) {
        if (n == null || !n.isObject()) return null;
        String id = n.path("id").asText("");
        if (!ID.matcher(id).matches() || ids.contains(id)) id = "r" + (idx + 1);
        if (ids.contains(id)) return null;
        Kind kind = "REGEX".equalsIgnoreCase(n.path("kind").asText("")) ? Kind.REGEX : Kind.TEXT;
        String pattern = n.path("pattern").asText("");
        if (pattern.isBlank()) return null;
        boolean cs = n.path("case_sensitive").asBoolean(false);
        Op op = Op.parse(n.path("operator").asText("GTE"));
        if (op == null) op = Op.GTE;
        int count = n.path("count").canConvertToInt() ? Math.max(0, n.path("count").asInt(1)) : 1;
        Threshold crit = null;
        JsonNode c = n.path("critical");
        if (c.isObject()) {
            Op cop = Op.parse(c.path("operator").asText(""));
            if (cop != null && c.path("count").canConvertToInt()) {
                int cc = Math.max(0, c.path("count").asInt());
                if (twoLevelProblemQuiet(op, count, cop, cc)) crit = null;
                else crit = new Threshold(cop, cc);
            }
        }
        Scope scope = null;
        JsonNode s = n.path("scope");
        if (s.isObject()) {
            String t = s.path("type").asText("");
            String v = s.path("value").asText("");
            if ("CSS".equalsIgnoreCase(t) && !v.isBlank()) scope = new Scope(ScopeType.CSS, v.trim());
            else if ("JSON".equalsIgnoreCase(t) && !v.isBlank()) scope = new Scope(ScopeType.JSON, v.trim());
        }
        String label = n.hasNonNull("label") ? n.path("label").asText(null) : null;
        if (label != null && label.isBlank()) label = null;
        return new KeywordRule(id, kind, pattern, cs, op, count, crit, scope, label);
    }

    private static boolean twoLevelProblemQuiet(Op w, int wc, Op c, int cc) {
        if (w == Op.EQ || c == Op.EQ || w.upper() != c.upper()) return true;
        long wb = w.bound(wc), cb = c.bound(cc);
        return w.upper() ? cb <= wb : cb >= wb;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Yazım + eski alanlar
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Kurallar → {@code rules_json} (snake_case); boş / null → {@code null}. */
    public static String toJson(List<KeywordRule> rules) {
        if (rules == null || rules.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(toApi(rules));
        } catch (Exception e) {
            throw new IllegalStateException("kurallar yazılamadı", e);
        }
    }

    /** Kurallar → API listesi (snake_case anahtarlar, {@code rules_json} ile aynı biçim). */
    public static List<Map<String, Object>> toApi(List<KeywordRule> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rules == null) return out;
        for (KeywordRule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("kind", r.kind().name());
            m.put("pattern", r.pattern());
            m.put("case_sensitive", r.caseSensitive());
            m.put("operator", r.operator().name());
            m.put("count", r.count());
            if (r.critical() == null) m.put("critical", null);
            else {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("operator", r.critical().operator().name());
                c.put("count", r.critical().count());
                m.put("critical", c);
            }
            if (r.scopeType() == ScopeType.PAGE) m.put("scope", null);
            else {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("type", r.scopeType().name());
                s.put("value", r.scopeValue());
                m.put("scope", s);
            }
            m.put("label", r.label());
            out.add(m);
        }
        return out;
    }

    /** Eski tek kelime alanları → tek kural (id {@value #LEGACY_ID}, metin, sayfa). Boş kelime → {@code null}. */
    public static KeywordRule fromLegacy(String keyword, String operator, Integer matchCount, Boolean caseSensitive) {
        if (keyword == null || keyword.isEmpty()) return null;
        Op op = Op.parse(operator);
        if (op == null) op = Op.GTE;
        int n = matchCount == null ? 1 : Math.max(0, matchCount);
        return new KeywordRule(LEGACY_ID, Kind.TEXT, keyword, Boolean.TRUE.equals(caseSensitive), op, n, null, null, null);
    }

    /**
     * ETKİN kurallar: {@code rules_json} dolu ve okunabiliyorsa onlar, değilse eski tek kelime alanlarından tek kural (yoksa boş).
     */
    public static List<KeywordRule> effective(String rulesJson, String keyword, String operator, Integer matchCount,
                                              Boolean caseSensitive) {
        List<KeywordRule> stored = readStored(rulesJson);
        if (!stored.isEmpty()) return stored;
        KeywordRule legacy = fromLegacy(keyword, operator, matchCount, caseSensitive);
        return legacy == null ? List.of() : List.of(legacy);
    }

    /** Basit tek kural mı (eski alanlara sığar — sunucu {@code rules_json}'ı NULL bırakır, §6.6). */
    public static boolean isSimple(List<KeywordRule> rules) {
        return rules != null && rules.size() == 1 && rules.get(0).simple();
    }

    /** Eski alan aynası (kural #1): keyword, operator, matchCount, caseSensitive. */
    public record LegacyMirror(String keyword, String operator, int matchCount, boolean caseSensitive) {}

    /** Kural #1'in eski alan aynası; boş liste → {@code null}. */
    public static LegacyMirror legacyMirror(List<KeywordRule> rules) {
        if (rules == null || rules.isEmpty()) return null;
        KeywordRule r = rules.get(0);
        return new LegacyMirror(r.pattern(), r.operator().name(), r.count(), r.caseSensitive());
    }

    // ── küçük yardımcılar ──

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean bool(Object o, String field) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        String s = o.toString().trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        throw new FieldValidationException(field, Msg.t("Değer doğru / yanlış (true / false) olmalı.", "The value must be true or false."));
    }

    private static int intIn(Object o, int min, int max, String field) {
        Long v = null;
        if (o instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) v = n.longValue();
        } else if (o != null) {
            try { v = Long.parseLong(o.toString().trim()); } catch (NumberFormatException ignore) { /* aşağıda hata */ }
        }
        if (v == null || v < min || v > max) {
            throw new FieldValidationException(field, Msg.t("Adet " + min + " ile " + max + " arasında bir tam sayı olmalı.",
                    "The count must be a whole number between " + min + " and " + max + "."));
        }
        return v.intValue();
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }
}
