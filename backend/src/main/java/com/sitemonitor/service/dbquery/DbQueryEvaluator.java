package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saf kural değerlendirici (sözleşme §3). Girdi: kurallar, birleşim (ANY | ALL), sorgu sonucu ve önceki zamanlanmış
 * çalıştırmanın kural başına TABAN ölçüleri ({@link RuleResult#measure()} — değişim kuralı için). Asla fırlatmaz.
 *
 * <p>Kurallar:
 * <ul>
 *   <li>Sayısal kuralda kritik eşik önce denetlenir; sağlanırsa CRITICAL, değilse uyarı eşiği sağlanırsa WARNING. "Sağlanır"
 *       = operatörün İHLAL koşulu ({@code GT 10} → değer &gt; 10).</li>
 *   <li>Metin eşleşmesi tek seviyelidir (kritik alanı doluysa kritik, değilse uyarı); ölçü = koşulu sağlayan satır sayısı.
 *       Okunur metne hücre değeri ASLA yazılmaz (yalnız sayı) — arayüz sorgu verisini göstermez.</li>
 *   <li>Değişim kuralı önceki ölçüye göredir; önceki yoksa değerlendirilmez ("ilk çalıştırma"); yüzde kipinde önceki 0 ise
 *       adet farkı gibi davranır.</li>
 *   <li>Değerlendirilemeyen kural (kolon yok, sayı değil, satır yok …) ihlal SAYILMAZ.</li>
 *   <li>ANY: en az bir ihlal → BREACH, seviye = en yükseği; ALL: değerlendirilen TÜM kurallar ihlalde (hepsi
 *       değerlendirilmiş olmalı) → BREACH, seviye = en düşüğü.</li>
 * </ul>
 * Satır sayısı sınırda kesildiyse ({@code rowCountCapped}) satır sayısı alt sınırdır ("≥ N"); kolon hesapları okunan
 * satırlar üzerindedir (not {@code partial}).
 */
public final class DbQueryEvaluator {

    public static final String LEVEL_NONE = "NONE", LEVEL_WARNING = "WARNING", LEVEL_CRITICAL = "CRITICAL";

    /** Not kodları (ön yüz {@code dbq.rr.note.<kod>} ile çevirir). */
    public static final String NOTE_COLUMN_MISSING = "column_missing", NOTE_NOT_NUMERIC = "not_numeric",
            NOTE_NO_ROWS = "no_rows", NOTE_FIRST_RUN = "first_run", NOTE_PREV_ZERO = "prev_zero",
            NOTE_PARTIAL = "partial", NOTE_CAPPED = "capped", NOTE_QUERY_FAILED = "query_failed";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param actual    ölçülen değerin okunur metni ("1.250", "%12,5 artış", "'ERROR' içeren 3 satır")
     * @param expected  beklenen koşulun okunur metni ("≤ 10 (uyarı) · ≤ 100 (kritik)")
     * @param level     NONE | WARNING | CRITICAL — ihlalin seviyesi
     * @param passed    ihlal YOK
     * @param evaluated false = değerlendirilemedi (kolon yok, sayı değil, ilk çalıştırma …) — ihlal sayılmaz
     * @param note      kısa açıklama kodu ya da metni (null olabilir)
     * @param measure   TABAN ölçü (satır sayısı / kolon toplamı …) — bir sonraki çalıştırmanın değişim kuralı bunu okur
     * @param compared  eşikle karşılaştırılan sayı (değişim kuralında değişim miktarı; diğerlerinde = measure)
     */
    public record RuleResult(String id, String label, String kind, String actual, String expected, String level,
                             boolean passed, boolean evaluated, String note, Double measure, Double compared) {}

    /**
     * @param status      OK | BREACH
     * @param level       NONE | WARNING | CRITICAL
     * @param metricValue grafik değeri: ilk değerlendirilen sayısal kuralın compared değeri; yoksa satır sayısı
     */
    public record Outcome(String status, String level, List<RuleResult> results, Double metricValue) {}

    private DbQueryEvaluator() {}

    /**
     * @param previousMeasures kural kimliği → önceki zamanlanmış çalıştırmanın {@code measure} değeri (boş = ilk çalıştırma)
     * @param lang             "tr" | "en" — actual / expected metinlerinin dili
     */
    public static Outcome evaluate(List<DbQueryRules.Rule> rules, String logic, DbQueryRunner.QueryResult result,
                                   Map<String, Double> previousMeasures, String lang) {
        boolean en = "en".equalsIgnoreCase(lang);
        List<DbQueryRules.Rule> rs = rules == null ? List.of() : rules;
        Map<String, Double> prev = previousMeasures == null ? Map.of() : previousMeasures;
        Double rowMetric = result == null ? null : (double) result.rowCount();
        if (result == null || !result.ok()) {
            List<RuleResult> out = new ArrayList<>();
            for (DbQueryRules.Rule r : rs) {
                if (r == null) continue;
                out.add(new RuleResult(r.id(), r.label(), r.kind(), "—", expected(r, en), LEVEL_NONE, true, false,
                        NOTE_QUERY_FAILED, null, null));
            }
            return new Outcome("OK", LEVEL_NONE, List.copyOf(out), null);
        }
        Ctx ctx = new Ctx(result, en);
        List<RuleResult> out = new ArrayList<>();
        for (DbQueryRules.Rule r : rs) {
            if (r == null) continue;
            RuleResult rr;
            try {
                rr = evaluateRule(r, ctx, prev.get(r.id()));
            } catch (RuntimeException e) {
                rr = new RuleResult(r.id(), r.label(), r.kind(), "—", expected(r, en), LEVEL_NONE, true, false,
                        NOTE_NOT_NUMERIC, null, null);
            }
            out.add(rr);
        }
        boolean all = "ALL".equalsIgnoreCase(logic == null ? "" : logic.trim());
        String status = "OK", level = LEVEL_NONE;
        if (!out.isEmpty()) {
            if (all) {
                boolean breach = true;
                String min = LEVEL_CRITICAL;
                for (RuleResult rr : out) {
                    if (!rr.evaluated() || rr.passed()) { breach = false; break; }
                    if (rank(rr.level()) < rank(min)) min = rr.level();
                }
                if (breach) { status = "BREACH"; level = min; }
            } else {
                String max = LEVEL_NONE;
                for (RuleResult rr : out)
                    if (rr.evaluated() && !rr.passed() && rank(rr.level()) > rank(max)) max = rr.level();
                if (!LEVEL_NONE.equals(max)) { status = "BREACH"; level = max; }
            }
        }
        Double metric = rowMetric;
        for (RuleResult rr : out) {
            if (rr.evaluated() && rr.compared() != null && !DbQueryRules.KIND_TEXT_MATCH.equals(rr.kind())) {
                metric = rr.compared();
                break;
            }
        }
        return new Outcome(status, level, List.copyOf(out), metric);
    }

    private static int rank(String level) {
        if (LEVEL_CRITICAL.equals(level)) return 2;
        if (LEVEL_WARNING.equals(level)) return 1;
        return 0;
    }

    // ── Kural başına ─────────────────────────────────────────────────────────

    private record Ctx(DbQueryRunner.QueryResult result, boolean en) {}

    private static RuleResult evaluateRule(DbQueryRules.Rule r, Ctx ctx, Double previous) {
        boolean en = ctx.en();
        String exp = expected(r, en);
        DbQueryRunner.QueryResult qr = ctx.result();
        switch (r.kind()) {
            case DbQueryRules.KIND_ROW_COUNT -> {
                double v = qr.rowCount();
                String level = numericLevel(r, v);
                String actual = (qr.rowCountCapped() ? "≥ " : "") + fmt(v, en) + (en ? " rows" : " satır");
                return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, LEVEL_NONE.equals(level), true,
                        qr.rowCountCapped() ? NOTE_CAPPED : null, v, v);
            }
            case DbQueryRules.KIND_COLUMN_VALUE -> {
                Agg a = aggregate(qr, r.column(), r.aggregate());
                if (a.value() == null)
                    return notEvaluated(r, exp, a.note());
                String level = numericLevel(r, a.value());
                return new RuleResult(r.id(), r.label(), r.kind(), fmt(a.value(), en), exp, level,
                        LEVEL_NONE.equals(level), true, a.note(), a.value(), a.value());
            }
            case DbQueryRules.KIND_TEXT_MATCH -> {
                return textMatch(r, qr, exp, en);
            }
            case DbQueryRules.KIND_CHANGE -> {
                return change(r, qr, previous, exp, en);
            }
            default -> {
                return notEvaluated(r, exp, NOTE_NOT_NUMERIC);
            }
        }
    }

    private static RuleResult notEvaluated(DbQueryRules.Rule r, String exp, String note) {
        return new RuleResult(r.id(), r.label(), r.kind(), "—", exp, LEVEL_NONE, true, false, note, null, null);
    }

    /** Kritik önce; ihlal koşulu operatöre göre. */
    static String numericLevel(DbQueryRules.Rule r, double v) {
        if (r.critical() != null && violates(r.operator(), v, r.critical())) return LEVEL_CRITICAL;
        if (r.warning() != null && violates(r.operator(), v, r.warning())) return LEVEL_WARNING;
        return LEVEL_NONE;
    }

    static boolean violates(String op, double v, DbQueryRules.Threshold t) {
        if (t == null) return false;
        if ("BETWEEN".equals(op) || "OUTSIDE".equals(op)) {
            if (!t.isRange()) return false;
            boolean inside = v >= t.low() && v <= t.high();
            return "BETWEEN".equals(op) == inside;
        }
        if (t.value() == null) return false;
        double x = t.value();
        return switch (op) {
            case "GT" -> v > x;
            case "GTE" -> v >= x;
            case "LT" -> v < x;
            case "LTE" -> v <= x;
            case "EQ" -> Double.compare(v, x) == 0;
            case "NEQ" -> Double.compare(v, x) != 0;
            default -> false;
        };
    }

    // ── Kolon hesabı ─────────────────────────────────────────────────────────

    private record Agg(Double value, String note) {}

    static int columnIndex(DbQueryRunner.QueryResult qr, String column) {
        if (column == null || qr.columns() == null) return -1;
        String c = column.trim();
        for (int i = 0; i < qr.columns().size(); i++) if (qr.columns().get(i).name().equalsIgnoreCase(c)) return i;
        return -1;
    }

    private static Agg aggregate(DbQueryRunner.QueryResult qr, String column, String aggregate) {
        int idx = columnIndex(qr, column);
        if (idx < 0) return new Agg(null, NOTE_COLUMN_MISSING);
        List<List<Object>> rows = qr.rows() == null ? List.of() : qr.rows();
        String partial = qr.rowCountCapped() || qr.valuesTruncated() || rows.size() < qr.rowCount() ? NOTE_PARTIAL : null;
        String agg = aggregate == null ? "FIRST" : aggregate;
        if ("COUNT_NONNULL".equals(agg)) {
            long n = 0;
            for (List<Object> row : rows) if (idx < row.size() && row.get(idx) != null) n++;
            return new Agg((double) n, partial);
        }
        if ("FIRST".equals(agg)) {
            if (rows.isEmpty()) return new Agg(null, NOTE_NO_ROWS);
            List<Object> first = rows.get(0);
            Double d = idx < first.size() ? numeric(first.get(idx)) : null;
            return d == null ? new Agg(null, NOTE_NOT_NUMERIC) : new Agg(d, null);
        }
        double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        long count = 0, nonNumeric = 0;
        for (List<Object> row : rows) {
            Object cell = idx < row.size() ? row.get(idx) : null;
            if (cell == null) continue;
            Double d = numeric(cell);
            if (d == null) { nonNumeric++; continue; }
            sum += d;
            if (d < min) min = d;
            if (d > max) max = d;
            count++;
        }
        if (count == 0) {
            if ("SUM".equals(agg) && nonNumeric == 0) return new Agg(0.0, rows.isEmpty() ? NOTE_NO_ROWS : partial);
            return new Agg(null, nonNumeric > 0 ? NOTE_NOT_NUMERIC : NOTE_NO_ROWS);
        }
        double v = switch (agg) {
            case "MAX" -> max;
            case "MIN" -> min;
            case "SUM" -> sum;
            case "AVG" -> sum / count;
            default -> Double.NaN;
        };
        if (!Double.isFinite(v)) return new Agg(null, NOTE_NOT_NUMERIC);
        return new Agg(v, partial);
    }

    /** Hücre → sayı: BigDecimal / Number; metin ise yalın sayı biçimi (nokta ondalık); diğerleri sayı değil. */
    static Double numeric(Object cell) {
        if (cell == null || cell instanceof Boolean) return null;
        if (cell instanceof Number n) {
            double d = n instanceof BigDecimal bd ? bd.doubleValue() : n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (cell instanceof String s) return DbQueryRules.toDouble(s);
        return null;
    }

    // ── Metin eşleşmesi ──────────────────────────────────────────────────────

    private static RuleResult textMatch(DbQueryRules.Rule r, DbQueryRunner.QueryResult qr, String exp, boolean en) {
        int idx = columnIndex(qr, r.column());
        if (idx < 0) return notEvaluated(r, exp, NOTE_COLUMN_MISSING);
        List<List<Object>> rows = qr.rows() == null ? List.of() : qr.rows();
        String scope = r.scope() == null ? "ANY_ROW" : r.scope();
        DbQueryRules.Threshold t = r.critical() != null ? r.critical() : r.warning();
        String lvl = r.critical() != null ? LEVEL_CRITICAL : LEVEL_WARNING;
        String needle = t == null || t.text() == null ? "" : t.text();
        TextMatcher m = new TextMatcher(r.operator(), needle);
        if ("FIRST_ROW".equals(scope) || "ALL_ROWS".equals(scope)) {
            if (rows.isEmpty()) return notEvaluated(r, exp, NOTE_NO_ROWS);
        }
        long matched = 0, total = 0;
        int limit = "FIRST_ROW".equals(scope) ? Math.min(1, rows.size()) : rows.size();
        for (int i = 0; i < limit; i++) {
            List<Object> row = rows.get(i);
            Object cell = idx < row.size() ? row.get(idx) : null;
            total++;
            if (m.matches(cell == null ? null : cellText(cell))) matched++;
        }
        boolean violated = switch (scope) {
            case "ALL_ROWS" -> total > 0 && matched == total;
            default -> matched > 0;
        };
        String note = qr.rowCountCapped() || qr.valuesTruncated() || rows.size() < qr.rowCount() ? NOTE_PARTIAL : null;
        String what = textCondition(r.operator(), needle, en);
        String actual;
        if ("FIRST_ROW".equals(scope)) {
            actual = en ? (violated ? "first row: " + what : "first row: not " + what)
                    : (violated ? "ilk satır: " + what : "ilk satır: " + what + " değil");
        } else if ("ALL_ROWS".equals(scope)) {
            actual = en ? fmt(matched, true) + " / " + fmt(total, true) + " rows " + what
                    : what + " " + fmt(matched, false) + " / " + fmt(total, false) + " satır";
        } else {
            actual = en ? fmt(matched, true) + " rows " + what : what + " " + fmt(matched, false) + " satır";
        }
        String level = violated ? lvl : LEVEL_NONE;
        return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, !violated, true, note,
                (double) matched, (double) matched);
    }

    /** Hücrenin metin biçimi (sayı → düz yazım). */
    static String cellText(Object cell) {
        if (cell instanceof BigDecimal bd) return bd.toPlainString();
        return String.valueOf(cell);
    }

    /**
     * Büyük-küçük harf duyarsız metin karşılaştırıcı. İçerme araması KMP ile DOĞRUSAL (hücre ≤ 4000 karakter × çok satır;
     * saf {@code indexOf} kötü durumda karesel olurdu). Karşılaştırma karakter katlamasıyla (İ/i, I/ı dahil).
     */
    static final class TextMatcher {
        private final String op;
        private final String needle;
        private final int[] fail;

        TextMatcher(String op, String rawNeedle) {
            this.op = op == null ? "CONTAINS" : op;
            this.needle = fold(rawNeedle == null ? "" : rawNeedle);
            this.fail = kmpTable(this.needle);
        }

        boolean matches(String value) {
            switch (op) {
                case "EMPTY":
                    return value == null || value.isBlank();
                case "NOT_EMPTY":
                    return value != null && !value.isBlank();
                case "EQUALS":
                    return value != null && fold(value.trim()).equals(needle.trim());
                case "NOT_EQUALS":
                    return value == null || !fold(value.trim()).equals(needle.trim());
                case "NOT_CONTAINS":
                    return value == null || !contains(fold(value));
                case "CONTAINS":
                default:
                    return value != null && contains(fold(value));
            }
        }

        private boolean contains(String hay) {
            if (needle.isEmpty()) return true;
            int k = 0;
            for (int i = 0; i < hay.length(); i++) {
                char c = hay.charAt(i);
                while (k > 0 && needle.charAt(k) != c) k = fail[k - 1];
                if (needle.charAt(k) == c) k++;
                if (k == needle.length()) return true;
            }
            return false;
        }

        private static int[] kmpTable(String p) {
            int[] f = new int[Math.max(1, p.length())];
            int k = 0;
            for (int i = 1; i < p.length(); i++) {
                while (k > 0 && p.charAt(k) != p.charAt(i)) k = f[k - 1];
                if (p.charAt(k) == p.charAt(i)) k++;
                f[i] = k;
            }
            return f;
        }

        /** Karakter katlaması: büyük harf → küçük harf (İ → i, I → i, ı → i) — dil bağımsız, uzunluk korunur. */
        static String fold(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                sb.append(Character.toLowerCase(Character.toUpperCase(s.charAt(i))));
            }
            return sb.toString();
        }
    }

    // ── Değişim ──────────────────────────────────────────────────────────────

    private static RuleResult change(DbQueryRules.Rule r, DbQueryRunner.QueryResult qr, Double previous, String exp,
                                     boolean en) {
        DbQueryRules.Change ch = r.change() == null ? new DbQueryRules.Change("ROW_COUNT", "PERCENT", "ANY") : r.change();
        Double base;
        String baseNote = null;
        if ("COLUMN".equals(ch.of())) {
            Agg a = aggregate(qr, r.column(), r.aggregate());
            if (a.value() == null) return notEvaluated(r, exp, a.note());
            base = a.value();
            baseNote = a.note();
        } else {
            base = (double) qr.rowCount();
            if (qr.rowCountCapped()) baseNote = NOTE_CAPPED;
        }
        if (previous == null || !Double.isFinite(previous)) {
            return new RuleResult(r.id(), r.label(), r.kind(), fmt(base, en), exp, LEVEL_NONE, true, false,
                    NOTE_FIRST_RUN, base, null);
        }
        double diff = base - previous;
        double signed = switch (ch.direction()) {
            case "INCREASE" -> diff;
            case "DECREASE" -> -diff;
            default -> Math.abs(diff);
        };
        boolean percent = "PERCENT".equals(ch.mode());
        String note = baseNote;
        double compared;
        if (percent && previous != 0) {
            compared = signed / Math.abs(previous) * 100.0;
        } else {
            compared = signed;
            if (percent) { percent = false; note = NOTE_PREV_ZERO; }
        }
        String level = numericLevel(r, compared);
        String dirWord = changeWord(diff, ch.direction(), en);
        String amount = percent ? pct(Math.abs(diff) / Math.abs(previous) * 100.0, en) : fmt(Math.abs(diff), en);
        String span = " (" + fmt(previous, en) + " → " + fmt(base, en) + ")";
        String actual = (diff == 0 ? dirWord : amount + " " + dirWord) + span;
        return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, LEVEL_NONE.equals(level), true, note,
                base, round(compared));
    }

    private static String changeWord(double diff, String direction, boolean en) {
        if (diff > 0) return en ? "increase" : "artış";
        if (diff < 0) return en ? "decrease" : "azalış";
        return en ? "no change" : "değişim yok";
    }

    // ── Okunur "beklenen" metni ──────────────────────────────────────────────

    /** Sağlıklı durumun metni: ihlal koşulunun tersi + seviye. */
    static String expected(DbQueryRules.Rule r, boolean en) {
        if (r == null) return "";
        if (DbQueryRules.KIND_TEXT_MATCH.equals(r.kind())) {
            DbQueryRules.Threshold t = r.critical() != null ? r.critical() : r.warning();
            String needle = t == null || t.text() == null ? "" : t.text();
            String cond = textCondition(r.operator(), needle, en);
            String scope = r.scope() == null ? "ANY_ROW" : r.scope();
            String lvl = levelWord(r.critical() != null ? LEVEL_CRITICAL : LEVEL_WARNING, en);
            String body = switch (scope) {
                case "ALL_ROWS" -> en ? "not every row " + cond : "satırların hepsi " + cond + " olmamalı";
                case "FIRST_ROW" -> en ? "first row not " + cond : "ilk satır " + cond + " olmamalı";
                default -> en ? "no row " + cond : "hiçbir satır " + cond + " olmamalı";
            };
            return body + " (" + lvl + ")";
        }
        String prefix = "";
        boolean percent = false;
        if (DbQueryRules.KIND_CHANGE.equals(r.kind()) && r.change() != null) {
            percent = "PERCENT".equals(r.change().mode());
            prefix = switch (r.change().direction()) {
                case "INCREASE" -> en ? "increase " : "artış ";
                case "DECREASE" -> en ? "decrease " : "azalış ";
                default -> en ? "change " : "değişim ";
            };
        }
        List<String> parts = new ArrayList<>();
        if (r.warning() != null) parts.add(prefix + healthy(r.operator(), r.warning(), percent, en) + " (" + levelWord(LEVEL_WARNING, en) + ")");
        if (r.critical() != null) parts.add(prefix + healthy(r.operator(), r.critical(), percent, en) + " (" + levelWord(LEVEL_CRITICAL, en) + ")");
        return String.join(" · ", parts);
    }

    private static String healthy(String op, DbQueryRules.Threshold t, boolean percent, boolean en) {
        if (t.isRange()) {
            String range = num(t.low(), percent, en) + " – " + num(t.high(), percent, en);
            if ("BETWEEN".equals(op)) return en ? "outside " + range : range + " dışında";
            return en ? "within " + range : range + " arasında";
        }
        String v = t.value() == null ? "—" : num(t.value(), percent, en);
        return switch (op) {
            case "GT" -> "≤ " + v;
            case "GTE" -> "< " + v;
            case "LT" -> "≥ " + v;
            case "LTE" -> "> " + v;
            case "EQ" -> "≠ " + v;
            case "NEQ" -> "= " + v;
            default -> v;
        };
    }

    private static String num(double v, boolean percent, boolean en) {
        return percent ? pct(v, en) : fmt(v, en);
    }

    private static String textCondition(String op, String needle, boolean en) {
        String q = "'" + (needle.length() > 60 ? needle.substring(0, 60) + "…" : needle) + "'";
        return switch (op == null ? "" : op) {
            case "NOT_CONTAINS" -> en ? "not containing " + q : q + " içermeyen";
            case "EQUALS" -> en ? "equal to " + q : q + " olan";
            case "NOT_EQUALS" -> en ? "not equal to " + q : q + " olmayan";
            case "EMPTY" -> en ? "empty" : "boş";
            case "NOT_EMPTY" -> en ? "not empty" : "dolu";
            default -> en ? "containing " + q : q + " içeren";
        };
    }

    private static String levelWord(String level, boolean en) {
        if (LEVEL_CRITICAL.equals(level)) return en ? "critical" : "kritik";
        return en ? "warning" : "uyarı";
    }

    // ── Sayı biçimi ──────────────────────────────────────────────────────────

    /** TR "1.250,5" / EN "1,250.5"; en çok 2 ondalık (|x| &lt; 1 ise 4), sondaki sıfırlar yok. */
    public static String fmt(double v, boolean en) {
        if (!Double.isFinite(v)) return "—";
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(en ? Locale.US : Locale.forLanguageTag("tr-TR"));
        DecimalFormat df = new DecimalFormat("#,##0.##", sym);
        if (v != 0 && Math.abs(v) < 1) df.setMaximumFractionDigits(4);
        df.setRoundingMode(RoundingMode.HALF_UP);
        String s = df.format(v);
        return "-0".equals(s) ? "0" : s;
    }

    private static String pct(double v, boolean en) {
        return en ? fmt(v, true) + "%" : "%" + fmt(v, false);
    }

    private static double round(double v) {
        if (!Double.isFinite(v)) return v;
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    // ── Kontrol satırı JSON'u (rule_results_json) ────────────────────────────

    /** {@code rule_results_json} biçimi: API RuleResult ile aynı snake_case alanlar. */
    public static String toJson(List<RuleResult> results) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (results != null) {
            for (RuleResult r : results) {
                if (r == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id());
                m.put("label", r.label());
                m.put("kind", r.kind());
                m.put("actual", r.actual());
                m.put("expected", r.expected());
                m.put("level", r.level());
                m.put("passed", r.passed());
                m.put("evaluated", r.evaluated());
                m.put("note", r.note());
                m.put("measure", r.measure());
                m.put("compared", r.compared());
                out.add(m);
            }
        }
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Gevşek okuma (bozuk öğe atlanır, asla fırlatmaz). */
    public static List<RuleResult> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> list = JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<RuleResult> out = new ArrayList<>();
            if (list == null) return out;
            for (Map<String, Object> m : list) {
                if (m == null || !(m.get("id") instanceof String id)) continue;
                out.add(new RuleResult(id, str(m.get("label")), str(m.get("kind")), str(m.get("actual")),
                        str(m.get("expected")), str(m.get("level")), Boolean.TRUE.equals(m.get("passed")),
                        Boolean.TRUE.equals(m.get("evaluated")), str(m.get("note")),
                        DbQueryRules.toDouble(m.get("measure")), DbQueryRules.toDouble(m.get("compared"))));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Değişim kuralının "önceki ölçüleri": kural kimliği → measure (null ölçüler atlanır). */
    public static Map<String, Double> measuresFromJson(String json) {
        Map<String, Double> out = new HashMap<>();
        for (RuleResult r : fromJson(json)) if (r.measure() != null) out.put(r.id(), r.measure());
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
