package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.util.Msg;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Değerlendirme kuralları modeli (sözleşme §3). JSON biçimi ({@code dbquery_monitors.rules_json}, API {@code rules}):
 * <pre>
 * { "id": "r1", "label": "…", "kind": "ROW_COUNT|COLUMN_VALUE|TEXT_MATCH|CHANGE|FRESHNESS",
 *   "column": "…", "aggregate": "FIRST|MAX|MIN|SUM|AVG|COUNT_NONNULL",
 *   "operator": "GT|GTE|LT|LTE|EQ|NEQ|BETWEEN|OUTSIDE|CONTAINS|NOT_CONTAINS|EQUALS|NOT_EQUALS|EMPTY|NOT_EMPTY",
 *   "scope": "ANY_ROW|ALL_ROWS|FIRST_ROW",
 *   "warning": 10 | [1, 5] | "text", "critical": 100 | [0, 2] | "text",
 *   "change": { "of": "ROW_COUNT|COLUMN", "mode": "PERCENT|ABSOLUTE", "direction": "INCREASE|DECREASE|ANY",
 *               "baseline": "previous|same_time_yesterday|same_time_last_week" } }
 * </pre>
 * Tazelik ({@code FRESHNESS}, r177): kolon bir zaman damgasıdır; hesap {@code MAX} (varsayılan; FIRST / MIN de olur), ölçüm =
 * veritabanı saatine göre YAŞ (saniye), operatör yalnız {@code GT} / {@code GTE} ("yaş ≥ X"). Değişim tabanı ({@code baseline},
 * varsayılan {@code previous}) mevsimsel olabilir: dünün / geçen haftanın aynı saatindeki zamanlanmış çalıştırma.
 * Operatör İHLAL koşuludur: {@code GT 10} → değer 10'dan büyükse ihlal; {@code BETWEEN [1, 5]} → aralığın İÇİNDEyse,
 * {@code OUTSIDE [1, 5]} → DIŞINDAysa ihlal. İki seviye (uyarı / kritik) sayısal kurallarda bağımsız eşiklerdir (kritik önce);
 * metin kuralı tek seviyelidir (kritik alanı doluysa kritik, değilse uyarı).
 *
 * <p>{@link #fromApi} KATIDIR (alan hatası {@code rules[N].<alt>}, istek dilinde ileti); {@link #fromJson} GEVŞEKTİR (bozuk
 * kural atlanır, asla fırlatmaz) — saklanan kayıt hiçbir zaman izlemeyi kırmaz.
 */
public final class DbQueryRules {

    public static final int MAX_RULES = 10;
    public static final int MAX_LABEL = 120, MAX_COLUMN = 128, MAX_TEXT = 500;

    public static final String KIND_ROW_COUNT = "ROW_COUNT", KIND_COLUMN_VALUE = "COLUMN_VALUE",
            KIND_TEXT_MATCH = "TEXT_MATCH", KIND_CHANGE = "CHANGE",
            /** Tazelik: zaman damgası kolonunun veritabanı saatine göre yaşı (saniye) — r177. */
            KIND_FRESHNESS = "FRESHNESS";

    public static final List<String> KINDS =
            List.of(KIND_ROW_COUNT, KIND_COLUMN_VALUE, KIND_TEXT_MATCH, KIND_CHANGE, KIND_FRESHNESS);
    public static final List<String> AGGREGATES = List.of("FIRST", "MAX", "MIN", "SUM", "AVG", "COUNT_NONNULL");
    public static final List<String> NUMERIC_OPERATORS = List.of("GT", "GTE", "LT", "LTE", "EQ", "NEQ", "BETWEEN", "OUTSIDE");
    public static final List<String> TEXT_OPERATORS =
            List.of("CONTAINS", "NOT_CONTAINS", "EQUALS", "NOT_EQUALS", "EMPTY", "NOT_EMPTY");
    public static final List<String> SCOPES = List.of("ANY_ROW", "ALL_ROWS", "FIRST_ROW");
    public static final List<String> CHANGE_OF = List.of("ROW_COUNT", "COLUMN");
    public static final List<String> CHANGE_MODES = List.of("PERCENT", "ABSOLUTE");
    public static final List<String> CHANGE_DIRECTIONS = List.of("INCREASE", "DECREASE", "ANY");
    /** Değişim tabanı (API değeri küçük harf, kalıcı): önceki çalıştırma / dünün aynı saati / geçen haftanın aynı saati. */
    public static final String BASELINE_PREVIOUS = "previous", BASELINE_YESTERDAY = "same_time_yesterday",
            BASELINE_LAST_WEEK = "same_time_last_week";
    public static final List<String> CHANGE_BASELINES = List.of(BASELINE_PREVIOUS, BASELINE_YESTERDAY, BASELINE_LAST_WEEK);
    /** Tazelik kuralında anlamlı hesaplar (toplam / ortalama zaman damgasında anlamsız). */
    public static final List<String> FRESHNESS_AGGREGATES = List.of("MAX", "MIN", "FIRST");
    /** Tazelik kuralının operatörleri: "yaş &gt; X" / "yaş ≥ X". */
    public static final List<String> FRESHNESS_OPERATORS = List.of("GT", "GTE");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Eşik: sayısal tek değer, aralık [low, high] ya da metin (TEXT_MATCH). Hepsi null = tanımsız. */
    public record Threshold(Double value, Double low, Double high, String text) {
        public boolean isRange() { return low != null && high != null; }
    }

    /**
     * @param baseline {@link #CHANGE_BASELINES} — {@code previous} (varsayılan, bugüne dek tek davranış) ya da mevsimsel taban
     */
    public record Change(String of, String mode, String direction, String baseline) {
        /** Eski üç alanlı biçim (taban = önceki çalıştırma). */
        public Change(String of, String mode, String direction) {
            this(of, mode, direction, BASELINE_PREVIOUS);
        }

        /** Taban null ise önceki çalıştırma. */
        public String baselineOrPrevious() {
            return baseline == null ? BASELINE_PREVIOUS : baseline;
        }
    }

    public record Rule(String id, String label, String kind, String column, String aggregate, String operator,
                       String scope, Threshold warning, Threshold critical, Change change) {}

    /** Alan hatası: {@code field} = "rules[2].warning" gibi; {@code message} istek dilinde. */
    public record FieldError(String field, String code, String message) {}

    private DbQueryRules() {}

    public record Parsed(List<Rule> rules, List<FieldError> errors) {}

    // ── API (katı) ───────────────────────────────────────────────────────────

    /** API gövdesindeki listeyi (List of Map) kurallara çevirir; biçim hatası {@link FieldError} olarak döner, fırlatmaz. */
    public static Parsed fromApi(Object raw) {
        List<Rule> rules = new ArrayList<>();
        List<FieldError> errors = new ArrayList<>();
        if (raw == null) return new Parsed(List.of(), List.of());
        if (!(raw instanceof List<?> list)) {
            errors.add(new FieldError("rules", "DBQUERY_RULES_INVALID", Msg.t(
                    "Kurallar bir liste olmalı · Gönderilen değer okunamadı · Kuralları yeniden ekleyin.",
                    "Rules must be a list · The value sent could not be read · Add the rules again.")));
            return new Parsed(List.of(), errors);
        }
        if (list.size() > MAX_RULES) {
            errors.add(new FieldError("rules", "DBQUERY_RULES_TOO_MANY", Msg.t(
                    "En çok " + MAX_RULES + " kural tanımlanabilir (" + list.size() + " gönderildi) · Çok sayıda kural "
                            + "alarmı anlaşılmaz yapar · Fazla kuralları silin ya da birleştirin.",
                    "At most " + MAX_RULES + " rules can be defined (" + list.size() + " sent) · Many rules make the "
                            + "alarm hard to understand · Delete or merge the extra rules.")));
        }
        Set<String> usedIds = new HashSet<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object id = m.get("id");
                if (id instanceof String s && !s.isBlank()) usedIds.add(s.trim());
            }
        }
        int n = Math.min(list.size(), MAX_RULES);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Rule r = parseRule(list.get(i), i, usedIds, seen, errors);
            if (r != null) rules.add(r);
        }
        return new Parsed(List.copyOf(rules), List.copyOf(errors));
    }

    // ── Saklanan JSON (gevşek) ───────────────────────────────────────────────

    /** Saklanan JSON'u gevşek okur (bozuk kural atlanır, asla fırlatmaz). */
    public static List<Rule> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Object> list = JSON.readValue(json, new TypeReference<List<Object>>() {});
            if (list == null) return List.of();
            Set<String> usedIds = new HashSet<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("id") instanceof String s && !s.isBlank()) usedIds.add(s.trim());
            }
            List<Rule> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < list.size() && out.size() < MAX_RULES; i++) {
                List<FieldError> errs = new ArrayList<>();
                Rule r = parseRule(list.get(i), i, usedIds, seen, errs);
                if (r != null && errs.isEmpty()) out.add(r);
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String toJson(List<Rule> rules) {
        try {
            return JSON.writeValueAsString(toApi(rules));
        } catch (Exception e) {
            return "[]";
        }
    }

    /** API'ye yazılan biçim (snake_case, null alanlar yok). */
    public static List<Map<String, Object>> toApi(List<Rule> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rules == null) return out;
        for (Rule r : rules) {
            if (r == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            put(m, "id", r.id());
            put(m, "label", r.label());
            put(m, "kind", r.kind());
            put(m, "column", r.column());
            put(m, "aggregate", r.aggregate());
            put(m, "operator", r.operator());
            put(m, "scope", r.scope());
            put(m, "warning", thresholdToApi(r.warning()));
            put(m, "critical", thresholdToApi(r.critical()));
            if (r.change() != null) {
                Map<String, Object> c = new LinkedHashMap<>();
                put(c, "of", r.change().of());
                put(c, "mode", r.change().mode());
                put(c, "direction", r.change().direction());
                // Önceki çalıştırma tabanı yazılmaz (saklı JSON ve eski istemciler bugünkü gibi kalsın)
                if (!BASELINE_PREVIOUS.equals(r.change().baselineOrPrevious())) put(c, "baseline", r.change().baseline());
                m.put("change", c);
            }
            out.add(m);
        }
        return out;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static Object thresholdToApi(Threshold t) {
        if (t == null) return null;
        if (t.text() != null) return t.text();
        if (t.isRange()) return List.of(number(t.low()), number(t.high()));
        return t.value() == null ? null : number(t.value());
    }

    /** Tam sayıysa uzun tam sayı (JSON'da "10", "10.0" değil). */
    private static Object number(Double d) {
        if (d == null) return null;
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.longValue();
        return d;
    }

    // ── Tek kural ────────────────────────────────────────────────────────────

    private static Rule parseRule(Object raw, int i, Set<String> usedIds, Set<String> seen, List<FieldError> errors) {
        String base = "rules[" + i + "]";
        if (!(raw instanceof Map<?, ?> m)) {
            errors.add(err(base, "DBQUERY_RULE_INVALID", Msg.t(
                    "Kural " + (i + 1) + " okunamadı · Kural bir nesne olmalı · Kuralı silip yeniden ekleyin.",
                    "Rule " + (i + 1) + " could not be read · A rule must be an object · Delete it and add it again.")));
            return null;
        }
        int before = errors.size();
        String id = str(m.get("id"));
        if (id == null) {
            int k = i + 1;
            while (usedIds.contains("r" + k) || seen.contains("r" + k)) k++;
            id = "r" + k;
        } else if (!validId(id)) {
            errors.add(err(base + ".id", "DBQUERY_RULE_ID", Msg.t(
                    "Kural " + (i + 1) + " kimliği geçersiz · Kimlik harf, rakam, '-' ya da '_' içermeli (en çok 40) · "
                            + "Kuralı silip yeniden ekleyin.",
                    "Rule " + (i + 1) + " has an invalid id · The id must use letters, digits, '-' or '_' (at most 40) · "
                            + "Delete the rule and add it again.")));
        }
        if (!seen.add(id)) {
            errors.add(err(base + ".id", "DBQUERY_RULE_ID", Msg.t(
                    "Kural " + (i + 1) + " kimliği başka bir kuralla aynı · Her kuralın kimliği tekil olmalı · "
                            + "Kuralı silip yeniden ekleyin.",
                    "Rule " + (i + 1) + " has the same id as another rule · Each rule needs a unique id · "
                            + "Delete the rule and add it again.")));
        }
        String label = str(m.get("label"));
        if (label != null && label.length() > MAX_LABEL) {
            errors.add(err(base + ".label", "DBQUERY_RULE_LABEL", Msg.t(
                    "Kural " + (i + 1) + " adı çok uzun (en çok " + MAX_LABEL + " karakter) · Kısa ad mailde ve "
                            + "ekranda okunur kalır · Adı kısaltın.",
                    "Rule " + (i + 1) + " name is too long (at most " + MAX_LABEL + " characters) · A short name stays "
                            + "readable in mails and on screen · Shorten it.")));
        }
        String kind = upper(m.get("kind"));
        if (kind == null || !KINDS.contains(kind)) {
            errors.add(err(base + ".kind", "DBQUERY_RULE_KIND", Msg.t(
                    "Kural " + (i + 1) + " türü seçilmedi · Tür: satır sayısı, kolon değeri, metin eşleşmesi, değişim ya da "
                            + "tazelik · Bir tür seçin.",
                    "Rule " + (i + 1) + " has no type · Type: row count, column value, text match, change or freshness · "
                            + "Choose a type.")));
            return null;
        }
        String column = str(m.get("column"));
        String aggregate = upper(m.get("aggregate"));
        String operator = upper(m.get("operator"));
        String scope = upper(m.get("scope"));
        Change change = null;

        boolean freshness = kind.equals(KIND_FRESHNESS);
        boolean needsColumn = kind.equals(KIND_COLUMN_VALUE) || kind.equals(KIND_TEXT_MATCH) || freshness;
        if (kind.equals(KIND_CHANGE)) {
            change = parseChange(m.get("change"), base, i, errors);
            needsColumn = change != null && "COLUMN".equals(change.of());
        }
        if (needsColumn) {
            if (column == null) {
                errors.add(err(base + ".column", "DBQUERY_RULE_COLUMN", Msg.t(
                        "Kural " + (i + 1) + " için kolon adı gerekli · Değer, sorgu sonucundaki bir kolondan okunur · "
                                + "Sorgunun döndürdüğü kolonlardan birini yazın.",
                        "Rule " + (i + 1) + " needs a column name · The value is read from a column of the query result · "
                                + "Enter one of the columns the query returns.")));
            } else if (column.length() > MAX_COLUMN) {
                errors.add(err(base + ".column", "DBQUERY_RULE_COLUMN", Msg.t(
                        "Kural " + (i + 1) + " kolon adı çok uzun (en çok " + MAX_COLUMN + ") · Kolon adı sorgu sonucuyla "
                                + "eşleşmeli · Kolon adını düzeltin.",
                        "Rule " + (i + 1) + " column name is too long (at most " + MAX_COLUMN + ") · It must match the "
                                + "query result · Fix the column name.")));
            }
        } else {
            column = null;
        }
        boolean usesAggregate = kind.equals(KIND_COLUMN_VALUE) || (change != null && "COLUMN".equals(change.of()));
        if (freshness) {
            // Tazelik: en yeni kayıt (MAX, varsayılan), en eski (MIN — ör. en eski bekleyen kaydın yaşı) ya da ilk satır
            if (aggregate == null) aggregate = "MAX";
            else if (!FRESHNESS_AGGREGATES.contains(aggregate)) {
                errors.add(err(base + ".aggregate", "DBQUERY_RULE_AGGREGATE", Msg.t(
                        "Kural " + (i + 1) + " için geçersiz hesap · Tazelik kuralında seçenekler: en yeni (en büyük), en eski "
                                + "(en küçük) ya da ilk satır · Bir seçenek seçin.",
                        "Rule " + (i + 1) + " has an invalid calculation · A freshness rule can use the newest (max), the "
                                + "oldest (min) or the first row · Choose one.")));
            }
        } else if (usesAggregate) {
            if (aggregate == null) aggregate = "FIRST";
            else if (!AGGREGATES.contains(aggregate)) {
                errors.add(err(base + ".aggregate", "DBQUERY_RULE_AGGREGATE", Msg.t(
                        "Kural " + (i + 1) + " için geçersiz hesap · Seçenekler: ilk satır, en büyük, en küçük, toplam, "
                                + "ortalama, dolu değer sayısı · Bir seçenek seçin.",
                        "Rule " + (i + 1) + " has an invalid calculation · Options: first row, max, min, sum, average, "
                                + "count of non-empty values · Choose one.")));
            }
        } else {
            aggregate = null;
        }
        boolean text = kind.equals(KIND_TEXT_MATCH);
        List<String> ops = text ? TEXT_OPERATORS : freshness ? FRESHNESS_OPERATORS : NUMERIC_OPERATORS;
        if (freshness && operator != null && NUMERIC_OPERATORS.contains(operator) && !ops.contains(operator)) {
            errors.add(err(base + ".operator", "DBQUERY_RULE_OPERATOR", Msg.t(
                    "Kural " + (i + 1) + " için karşılaştırma geçersiz · Tazelik kuralı yalnız yaş büyükse (> ya da ≥) "
                            + "alarm verir · Büyüktür ya da büyük-eşittir seçin.",
                    "Rule " + (i + 1) + " has an invalid comparison · A freshness rule only alarms when the age is greater "
                            + "(> or ≥) · Choose greater than or greater than or equal.")));
            return null;
        }
        if (operator == null || !ops.contains(operator)) {
            errors.add(err(base + ".operator", "DBQUERY_RULE_OPERATOR", Msg.t(
                    "Kural " + (i + 1) + " için karşılaştırma seçilmedi · Kural hangi durumda alarm vereceğini bilmeli · "
                            + "Bir karşılaştırma seçin.",
                    "Rule " + (i + 1) + " has no comparison · The rule must know when to alarm · Choose a comparison.")));
            return null;
        }
        if (text) {
            if (scope == null) scope = "ANY_ROW";
            else if (!SCOPES.contains(scope)) {
                errors.add(err(base + ".scope", "DBQUERY_RULE_SCOPE", Msg.t(
                        "Kural " + (i + 1) + " için geçersiz satır kapsamı · Seçenekler: herhangi bir satır, tüm satırlar, "
                                + "ilk satır · Bir seçenek seçin.",
                        "Rule " + (i + 1) + " has an invalid row scope · Options: any row, all rows, first row · "
                                + "Choose one.")));
            }
        } else {
            scope = null;
        }
        boolean range = operator.equals("BETWEEN") || operator.equals("OUTSIDE");
        boolean noText = operator.equals("EMPTY") || operator.equals("NOT_EMPTY");
        Threshold warning = parseThreshold(m.get("warning"), text, range, noText, base + ".warning", i, errors);
        Threshold critical = parseThreshold(m.get("critical"), text, range, noText, base + ".critical", i, errors);
        if (m.get("warning") == null && m.get("critical") == null) {
            errors.add(err(base + ".warning", "DBQUERY_RULE_THRESHOLD", Msg.t(
                    "Kural " + (i + 1) + " için eşik gerekli · Uyarı ya da kritik eşiğinden en az biri dolu olmalı · "
                            + "Bir eşik değeri girin.",
                    "Rule " + (i + 1) + " needs a threshold · At least one of the warning or critical thresholds is "
                            + "required · Enter a threshold value.")));
        }
        if (errors.size() > before) return null;
        return new Rule(id, label, kind, column, aggregate, operator, scope, warning, critical, change);
    }

    private static Change parseChange(Object raw, String base, int i, List<FieldError> errors) {
        if (!(raw instanceof Map<?, ?> c)) {
            errors.add(err(base + ".change", "DBQUERY_RULE_CHANGE", Msg.t(
                    "Kural " + (i + 1) + " için değişim ayarı gerekli · Neyin, nasıl (yüzde / adet) ve hangi yönde "
                            + "değiştiği seçilmeli · Değişim ayarlarını doldurun.",
                    "Rule " + (i + 1) + " needs change settings · Choose what changes, how (percent / absolute) and in "
                            + "which direction · Fill in the change settings.")));
            return null;
        }
        String of = upper(c.get("of"));
        String mode = upper(c.get("mode"));
        String dir = upper(c.get("direction"));
        String baseline = str(c.get("baseline"));
        if (of == null) of = "ROW_COUNT";
        if (mode == null) mode = "PERCENT";
        if (dir == null) dir = "ANY";
        baseline = baseline == null ? BASELINE_PREVIOUS : baseline.toLowerCase(Locale.ROOT);
        if (!CHANGE_BASELINES.contains(baseline)) {
            errors.add(err(base + ".change.baseline", "DBQUERY_RULE_CHANGE", Msg.t(
                    "Kural " + (i + 1) + " değişim tabanı geçersiz · Taban önceki çalıştırma, dünün aynı saati ya da geçen "
                            + "haftanın aynı saati olabilir · Bir taban seçin.",
                    "Rule " + (i + 1) + " change baseline is invalid · The baseline can be the previous run, the same time "
                            + "yesterday or the same time last week · Choose a baseline.")));
            return null;
        }
        if (!CHANGE_OF.contains(of) || !CHANGE_MODES.contains(mode) || !CHANGE_DIRECTIONS.contains(dir)) {
            errors.add(err(base + ".change", "DBQUERY_RULE_CHANGE", Msg.t(
                    "Kural " + (i + 1) + " değişim ayarı geçersiz · Değişim satır sayısında ya da bir kolonda; yüzde ya da "
                            + "adet olarak; artış, azalış ya da her iki yönde ölçülür · Seçenekleri yeniden seçin.",
                    "Rule " + (i + 1) + " change settings are invalid · Change is measured on the row count or a column; "
                            + "as percent or absolute; as increase, decrease or either · Choose the options again.")));
            return null;
        }
        return new Change(of, mode, dir, baseline);
    }

    private static Threshold parseThreshold(Object raw, boolean text, boolean range, boolean noText, String field, int i,
                                            List<FieldError> errors) {
        if (raw == null) return null;
        if (text) {
            String s = raw instanceof String str ? str : (raw instanceof Number || raw instanceof Boolean ? String.valueOf(raw) : null);
            if (s == null) {
                errors.add(thresholdError(field, i));
                return null;
            }
            if (s.length() > MAX_TEXT) {
                errors.add(err(field, "DBQUERY_RULE_THRESHOLD", Msg.t(
                        "Kural " + (i + 1) + " aranan metni çok uzun (en çok " + MAX_TEXT + " karakter) · Kısa ve ayırt "
                                + "edici bir metin daha güvenilir eşleşir · Metni kısaltın.",
                        "Rule " + (i + 1) + " search text is too long (at most " + MAX_TEXT + " characters) · A short, "
                                + "distinctive text matches more reliably · Shorten it.")));
                return null;
            }
            if (s.isEmpty() && !noText) {
                errors.add(err(field, "DBQUERY_RULE_THRESHOLD", Msg.t(
                        "Kural " + (i + 1) + " için aranan metin boş · Metin eşleşmesi bir metne göre yapılır · "
                                + "Aranacak metni yazın.",
                        "Rule " + (i + 1) + " search text is empty · Text matching needs a text · Enter the text to look for.")));
                return null;
            }
            return new Threshold(null, null, null, s);
        }
        if (range) {
            if (raw instanceof List<?> l && l.size() == 2) {
                Double lo = toDouble(l.get(0)), hi = toDouble(l.get(1));
                if (lo != null && hi != null && lo <= hi) return new Threshold(null, lo, hi, null);
            }
            errors.add(err(field, "DBQUERY_RULE_THRESHOLD", Msg.t(
                    "Kural " + (i + 1) + " aralığı geçersiz · Aralık [alt, üst] biçiminde iki sayı ve alt ≤ üst olmalı · "
                            + "Alt ve üst sınırı girin.",
                    "Rule " + (i + 1) + " range is invalid · A range is two numbers [low, high] with low ≤ high · "
                            + "Enter the low and high limits.")));
            return null;
        }
        Double v = toDouble(raw);
        if (v == null) {
            errors.add(thresholdError(field, i));
            return null;
        }
        return new Threshold(v, null, null, null);
    }

    private static FieldError thresholdError(String field, int i) {
        return err(field, "DBQUERY_RULE_THRESHOLD", Msg.t(
                "Kural " + (i + 1) + " eşiği bir sayı olmalı · Karşılaştırma sayısal bir değerle yapılır · "
                        + "Eşiği sayı olarak girin (ondalık ayırıcı nokta).",
                "Rule " + (i + 1) + " threshold must be a number · The comparison uses a numeric value · "
                        + "Enter the threshold as a number."));
    }

    // ── Kural parmak izi (r177) ─────────────────────────────────────────────

    /**
     * Kuralın ÖLÇÜMÜNÜ belirleyen tanımın kısa özeti (16 hex): değişim kuralının tabanı ve geriye dönük deneme yalnız aynı
     * kimlik + aynı parmak izi taşıyan saklı ölçümü kullanır (H-1: kolon / hesap / değişimin konusu değişince eski ölçü
     * başka bir şeyi ölçmüş olur). Eşik, seviye, ad ve değişimin kipi / yönü / tabanı ölçümü değiştirmez — parmak izine
     * GİRMEZ (eşik değiştirmek tabanı sıfırlamaz). Metin eşleşmesinde ölçü (eşleşen satır sayısı) aranan metne, operatöre ve
     * kapsama bağlıdır, onlar girer. Biçim kalıcıdır (saklı satırlar onunla eşlenir).
     */
    public static String fingerprint(Rule r) {
        if (r == null || r.kind() == null) return null;
        String col = r.column() == null ? "" : r.column().trim().toLowerCase(Locale.ROOT);
        String agg = r.aggregate() == null ? "" : r.aggregate();
        String basis = switch (r.kind()) {
            case KIND_ROW_COUNT -> KIND_ROW_COUNT;
            case KIND_COLUMN_VALUE, KIND_FRESHNESS -> r.kind() + "|" + col + "|" + agg;
            case KIND_TEXT_MATCH -> {
                Threshold t = r.critical() != null ? r.critical() : r.warning();
                String needle = t == null || t.text() == null ? "" : DbQueryEvaluator.TextMatcher.fold(t.text());
                yield KIND_TEXT_MATCH + "|" + col + "|" + r.operator() + "|" + (r.scope() == null ? "ANY_ROW" : r.scope())
                        + "|" + needle;
            }
            case KIND_CHANGE -> {
                String of = r.change() == null ? "ROW_COUNT" : r.change().of();
                yield KIND_CHANGE + "|" + of + ("COLUMN".equals(of) ? "|" + col + "|" + agg : "");
            }
            default -> r.kind();
        };
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(basis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    static Double toDouble(Object o) {
        if (o == null || o instanceof Boolean) return null;
        double d;
        if (o instanceof Number n) d = n instanceof BigDecimal bd ? bd.doubleValue() : n.doubleValue();
        else if (o instanceof String s) {
            String t = s.trim();
            if (t.isEmpty() || t.length() > 40) return null;
            try { d = Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
        } else return null;
        return Double.isFinite(d) ? d : null;
    }

    private static boolean validId(String id) {
        if (id.length() > 40) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return !id.isEmpty();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String upper(Object o) {
        String s = str(o);
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static FieldError err(String field, String code, String message) {
        return new FieldError(field, code, message);
    }
}
