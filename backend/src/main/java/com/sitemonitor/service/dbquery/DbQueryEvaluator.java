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
 *   <li>Değerlendirilemeyen kural (kolon yok, sayı değil, satır yok …) ihlal SAYILMAZ. Kanıt EKSİKLİĞİ notlu olanı
 *       ({@link #INCONCLUSIVE_NOTES}: kolon yok, sayı değil, ilk çalıştırma, önceki çalıştırma okunamadı) "geçti" de
 *       sayılmaz — eşik alarmının kararı "bilinmiyor" olur (açılmaz, açık alarm kanıtsız kapanmaz; 2026-10-31 inceleme D3).
 *       Satır yok / değer boş (NULL) ise ihlal edecek değer yoktur — geçti sayılır.</li>
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
            NOTE_PARTIAL = "partial", NOTE_CAPPED = "capped", NOTE_QUERY_FAILED = "query_failed",
            /** Değer boş (NULL — ör. boş kümede MAX) — ihlal edecek değer yok (2026-10-31). */
            NOTE_NULL_VALUE = "null_value",
            /** Değişim kuralının önceki çalıştırması okunamadı (veritabanı hatası) — ilk çalıştırma DEĞİL (2026-10-31). */
            NOTE_PREVIOUS_UNREADABLE = "prev_unreadable",
            /**
             * Değişim kuralının tabanı başka bir sorguyla ya da başka bir kural tanımıyla ölçülmüş (sorgu / kolon / hesap /
             * değişimin konusu değişti — r177, H-1): eski ölçüyle kıyaslanmaz, taban bu çalıştırmayla yeniden kurulur.
             */
            NOTE_BASELINE_RESET = "baseline_reset",
            /** Mevsimsel taban (dün / geçen hafta aynı saat) için uygun zamanlanmış çalıştırma yok (r177). */
            NOTE_NO_SEASONAL_BASELINE = "no_seasonal_baseline",
            /** Tazelik kuralı: kolon değeri bir zaman damgası olarak okunamadı (r177). */
            NOTE_NOT_TIMESTAMP = "not_timestamp",
            /** Tazelik kuralı: veritabanı saati okunamadı — yaş hesaplanamaz (r177). */
            NOTE_DB_CLOCK_UNAVAILABLE = "db_clock_unavailable";

    /**
     * Kanıt eksikliği notları (2026-10-31 inceleme D3): bu notla değerlendirilemeyen kural "geçti" sayılmaz — eşik kaleminin
     * kararı bilinmiyor ({@code DbQueryAlarmText#thresholdVerdict}). Satır yok / değer boş bu kümede DEĞİL (değer yoksa
     * ihlal de yoktur; aksi hâlde "takılı iş yok → satır yok" sorgusunun alarmı hiç kapanmazdı) — tazelik kuralı hariç
     * ({@link #isInconclusive}).
     */
    public static final java.util.Set<String> INCONCLUSIVE_NOTES =
            java.util.Set.of(NOTE_COLUMN_MISSING, NOTE_NOT_NUMERIC, NOTE_FIRST_RUN, NOTE_PREVIOUS_UNREADABLE,
                    NOTE_BASELINE_RESET, NOTE_NO_SEASONAL_BASELINE, NOTE_NOT_TIMESTAMP, NOTE_DB_CLOCK_UNAVAILABLE);

    /**
     * "Taban kuruluyor" notları (r177): kanıt eksikliğidir (kontrol satırı {@code UNKNOWN}) ama YAPILANDIRMA hatası değildir —
     * kendiliğinden düzelir (bir sonraki / dünkü çalıştırma taban olur). Ardışık değerlendirilemeyen çalıştırma alarmını
     * ({@code DB_RULE_UNEVALUABLE}) BESLEMEZ: yeni bir "geçen hafta aynı saat" kuralı bir hafta boyunca alarm üretmesin.
     */
    public static final java.util.Set<String> WAITING_NOTES =
            java.util.Set.of(NOTE_FIRST_RUN, NOTE_BASELINE_RESET, NOTE_NO_SEASONAL_BASELINE);

    /**
     * Değerlendirilemeyen bir kural sonucu KANIT EKSİKLİĞİ mi (geçti sayılmaz): {@link #INCONCLUSIVE_NOTES}; tazelik kuralında
     * ayrıca satır yok / değer boş (zaman damgası yoksa tazelik bilinemez — boş tablo "taze" değildir).
     */
    public static boolean isInconclusive(String kind, String note) {
        if (note == null) return false;
        if (INCONCLUSIVE_NOTES.contains(note)) return true;
        return DbQueryRules.KIND_FRESHNESS.equals(kind) && (NOTE_NO_ROWS.equals(note) || NOTE_NULL_VALUE.equals(note));
    }

    /** Kanıt eksikliği ve "taban kuruluyor" değil — yapılandırma / veri sorunu (ardışık değerlendirilemeyen alarmı besler). */
    public static boolean isConfigUnevaluable(String kind, String note) {
        return isInconclusive(kind, note) && !WAITING_NOTES.contains(note);
    }

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
     * @param fingerprint kuralın ölçüm tanımının özeti ({@link DbQueryRules#fingerprint}, r177) — bir sonraki çalıştırmanın
     *                    değişim tabanı ve geriye dönük deneme bununla eşler; bu sürümden önceki satırlarda yok (null)
     */
    public record RuleResult(String id, String label, String kind, String actual, String expected, String level,
                             boolean passed, boolean evaluated, String note, Double measure, Double compared,
                             String fingerprint) {
        /** Eski on bir alanlı biçim (parmak izi yok). */
        public RuleResult(String id, String label, String kind, String actual, String expected, String level,
                          boolean passed, boolean evaluated, String note, Double measure, Double compared) {
            this(id, label, kind, actual, expected, level, passed, evaluated, note, measure, compared, null);
        }

        /** Parmak izi eklenmiş kopya. */
        RuleResult withFingerprint(String fp) {
            return new RuleResult(id, label, kind, actual, expected, level, passed, evaluated, note, measure, compared, fp);
        }
    }

    /**
     * @param status      OK | BREACH
     * @param level       NONE | WARNING | CRITICAL
     * @param metricValue grafik değeri: ilk değerlendirilen sayısal kuralın compared değeri; yoksa satır sayısı
     */
    public record Outcome(String status, String level, List<RuleResult> results, Double metricValue) {
        /** En az bir kural kanıt eksikliğiyle değerlendirilemedi ({@link #isInconclusive}) — r177 kontrol durumu UNKNOWN. */
        public boolean inconclusive() {
            if (results == null) return false;
            for (RuleResult r : results) if (r != null && !r.evaluated() && isInconclusive(r.kind(), r.note())) return true;
            return false;
        }

        /** En az bir kural YAPILANDIRMA sorunuyla değerlendirilemedi ({@link #isConfigUnevaluable}). */
        public boolean configUnevaluable() {
            if (results == null) return false;
            for (RuleResult r : results) if (r != null && !r.evaluated() && isConfigUnevaluable(r.kind(), r.note())) return true;
            return false;
        }
    }

    /**
     * Değişim kuralının tabanları (r177): kural kimliği → taban ölçü; ölçü yoksa {@code notes}'taki not (tabanın neden olmadığı:
     * {@link #NOTE_BASELINE_RESET}, {@link #NOTE_NO_SEASONAL_BASELINE}, {@link #NOTE_PREVIOUS_UNREADABLE}); not da yoksa
     * {@link #NOTE_FIRST_RUN}.
     */
    public record Baselines(Map<String, Double> measures, Map<String, String> notes) {
        public static final Baselines NONE = new Baselines(Map.of(), Map.of());

        Double measure(String id) { return measures == null || id == null ? null : measures.get(id); }

        String note(String id) { return notes == null || id == null ? null : notes.get(id); }
    }

    private DbQueryEvaluator() {}

    /**
     * @param previousMeasures kural kimliği → önceki zamanlanmış çalıştırmanın {@code measure} değeri (boş = ilk çalıştırma)
     * @param lang             "tr" | "en" — actual / expected metinlerinin dili
     */
    public static Outcome evaluate(List<DbQueryRules.Rule> rules, String logic, DbQueryRunner.QueryResult result,
                                   Map<String, Double> previousMeasures, String lang) {
        return evaluate(rules, logic, result, previousMeasures, lang, false);
    }

    /**
     * @param previousUnreadable önceki çalıştırma OKUNAMADI (veritabanı hatası) — değişim kuralı "ilk çalıştırma" değil
     *                           {@link #NOTE_PREVIOUS_UNREADABLE} notuyla değerlendirilemez (kanıt eksikliği, D3)
     */
    public static Outcome evaluate(List<DbQueryRules.Rule> rules, String logic, DbQueryRunner.QueryResult result,
                                   Map<String, Double> previousMeasures, String lang, boolean previousUnreadable) {
        Map<String, String> notes = new HashMap<>();
        if (previousUnreadable && rules != null) {
            for (DbQueryRules.Rule r : rules) if (r != null && r.id() != null) notes.put(r.id(), NOTE_PREVIOUS_UNREADABLE);
        }
        return evaluate(rules, logic, result, new Baselines(previousMeasures == null ? Map.of() : previousMeasures, notes), lang);
    }

    /** Kural başına taban ölçü + tabanın yokluğunun nedeni ({@link Baselines}) — r177. */
    public static Outcome evaluate(List<DbQueryRules.Rule> rules, String logic, DbQueryRunner.QueryResult result,
                                   Baselines baselines, String lang) {
        boolean en = "en".equalsIgnoreCase(lang);
        List<DbQueryRules.Rule> rs = rules == null ? List.of() : rules;
        Baselines base = baselines == null ? Baselines.NONE : baselines;
        Double rowMetric = result == null ? null : (double) result.rowCount();
        if (result == null || !result.ok()) {
            List<RuleResult> out = new ArrayList<>();
            for (DbQueryRules.Rule r : rs) {
                if (r == null) continue;
                out.add(new RuleResult(r.id(), r.label(), r.kind(), "—", expected(r, en), LEVEL_NONE, true, false,
                        NOTE_QUERY_FAILED, null, null, DbQueryRules.fingerprint(r)));
            }
            return new Outcome("OK", LEVEL_NONE, List.copyOf(out), null);
        }
        Ctx ctx = new Ctx(result, en);
        List<RuleResult> out = new ArrayList<>();
        for (DbQueryRules.Rule r : rs) {
            if (r == null) continue;
            RuleResult rr;
            try {
                rr = evaluateRule(r, ctx, base.measure(r.id()), base.note(r.id()));
            } catch (RuntimeException e) {
                rr = new RuleResult(r.id(), r.label(), r.kind(), "—", expected(r, en), LEVEL_NONE, true, false,
                        NOTE_NOT_NUMERIC, null, null);
            }
            out.add(rr.withFingerprint(DbQueryRules.fingerprint(r)));
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

    private static RuleResult evaluateRule(DbQueryRules.Rule r, Ctx ctx, Double previous, String missingNote) {
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
                return change(r, qr, previous, missingNote, exp, en);
            }
            case DbQueryRules.KIND_FRESHNESS -> {
                return freshness(r, qr, exp, en);
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
            Object cell = idx < first.size() ? first.get(idx) : null;
            if (cell == null) return new Agg(null, NOTE_NULL_VALUE);   // boş kümede MAX(...) = NULL — ihlal edecek değer yok
            Double d = numeric(cell);
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
            if (nonNumeric > 0) return new Agg(null, NOTE_NOT_NUMERIC);
            return new Agg(null, rows.isEmpty() ? NOTE_NO_ROWS : NOTE_NULL_VALUE);   // satır var ama hepsi NULL
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

    private static RuleResult change(DbQueryRules.Rule r, DbQueryRunner.QueryResult qr, Double previous,
                                     String missingNote, String exp, boolean en) {
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
                    missingNote != null ? missingNote : NOTE_FIRST_RUN, base, null);
        }
        return changeAgainst(r, base, previous, baseNote, exp, en);
    }

    /**
     * Değişim kuralının eşik kararı — ölçü ({@code base}) ve taban ({@code previous}) bilindiğinde. Geriye dönük deneme saklı
     * ölçülerle de bunu kullanır (aynı hesap, aynı metin).
     */
    static RuleResult changeAgainst(DbQueryRules.Rule r, double base, double previous, String baseNote, String exp,
                                    boolean en) {
        DbQueryRules.Change ch = r.change() == null ? new DbQueryRules.Change("ROW_COUNT", "PERCENT", "ANY") : r.change();
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

    // ── Tazelik (r177) ───────────────────────────────────────────────────────

    /** Zaman damgası metninin üst sınırı (daha uzunu tarih değildir — doğrusal, sınırlı ayrıştırma). */
    static final int MAX_TIMESTAMP_CHARS = 64;

    /**
     * Tazelik: kolondaki zaman damgalarının hesabı (MAX = en yeni, MIN = en eski, FIRST = ilk satır) veritabanı saatine göre
     * YAŞA (saniye) çevrilir. Saat dilimli değerler mutlaktır; dilimsiz değerler ({@code timestamp} / {@code datetime2} /
     * Oracle {@code DATE}) veritabanı saatinin diliminde yorumlanır ({@link DbQueryRunner.DbClock#offset}). Ölçüm = yaş;
     * eşikler "yaş &gt; X" / "yaş ≥ X". Satır yok / değer boş → kanıt eksikliği ({@link #isInconclusive}); veritabanı saati
     * okunamadı → {@link #NOTE_DB_CLOCK_UNAVAILABLE}; değer tarih değil → {@link #NOTE_NOT_TIMESTAMP}.
     */
    private static RuleResult freshness(DbQueryRules.Rule r, DbQueryRunner.QueryResult qr, String exp, boolean en) {
        int idx = columnIndex(qr, r.column());
        if (idx < 0) return notEvaluated(r, exp, NOTE_COLUMN_MISSING);
        List<List<Object>> rows = qr.rows() == null ? List.of() : qr.rows();
        if (rows.isEmpty()) return notEvaluated(r, exp, NOTE_NO_ROWS);
        DbQueryRunner.DbClock clock = qr.dbClock();
        if (clock == null || clock.now() == null) return notEvaluated(r, exp, NOTE_DB_CLOCK_UNAVAILABLE);
        java.time.ZoneOffset zone = clock.offset() == null ? java.time.ZoneOffset.UTC : clock.offset();
        String agg = r.aggregate() == null ? "MAX" : r.aggregate();
        java.time.Instant picked = null;
        boolean anyValue = false;
        int limit = "FIRST".equals(agg) ? Math.min(1, rows.size()) : rows.size();
        for (int i = 0; i < limit; i++) {
            List<Object> row = rows.get(i);
            Object cell = idx < row.size() ? row.get(idx) : null;
            if (cell == null) continue;
            anyValue = true;
            java.time.Instant t = timestampOf(cell, zone);
            if (t == null) return notEvaluated(r, exp, NOTE_NOT_TIMESTAMP);
            if (picked == null || ("MIN".equals(agg) ? t.isBefore(picked) : t.isAfter(picked))) picked = t;
        }
        if (!anyValue || picked == null) return notEvaluated(r, exp, NOTE_NULL_VALUE);
        double age = (clock.now().toEpochMilli() - picked.toEpochMilli()) / 1000.0;
        age = round(age);
        String level = numericLevel(r, age);
        String partial = qr.rowCountCapped() || qr.valuesTruncated() || rows.size() < qr.rowCount() ? NOTE_PARTIAL : null;
        String actual = age < 0
                ? (en ? "in the future (" + duration(-age, true) + " ahead of the database clock)"
                      : "gelecekte (veritabanı saatinden " + duration(-age, false) + " ileride)")
                : (en ? "age " + duration(age, true) : "yaş " + duration(age, false));
        return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, LEVEL_NONE.equals(level), true, partial,
                age, age);
    }

    /**
     * Hücre → an. Çalıştırıcının okuduğu biçimler: ISO yerel tarih-saat ({@code 2026-10-31T10:15:30.123}), saat dilimli ISO
     * ({@code …+03:00} / {@code …Z}), yalnız tarih ({@code 2026-10-31} — günün başı); sürücülerin metin biçimleri
     * ({@code 2026-10-31 10:15:30.1234567 +03:00}). Sayı, mantıksal ve tanınmayan metin → null (tahmin YOK — örn. epoch).
     * Doğrusal: girdi ≤ {@value #MAX_TIMESTAMP_CHARS} karakter, düzenli ifade yok.
     */
    static java.time.Instant timestampOf(Object cell, java.time.ZoneOffset zone) {
        if (!(cell instanceof String raw)) return null;
        String s = raw.trim();
        if (s.length() < 10 || s.length() > MAX_TIMESTAMP_CHARS) return null;
        java.time.ZoneOffset z = zone == null ? java.time.ZoneOffset.UTC : zone;
        try {
            if (s.length() == 10) return java.time.LocalDate.parse(s).atStartOfDay(z).toInstant();
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (i == 10 && c == ' ') { b.append('T'); continue; }
                b.append(c);
            }
            String t = b.toString();
            // Dilim eki: "Z", "+03:00", "+0300", "+03", ve önündeki tek boşluk (" +03:00")
            int sign = -1;
            for (int i = t.length() - 1; i > 10; i--) {
                char c = t.charAt(i);
                if (c == '+' || (c == '-' && i > 16)) { sign = i; break; }
                if (c == 'Z' && i == t.length() - 1) { sign = i; break; }
            }
            if (sign < 0) return java.time.LocalDateTime.parse(t).atOffset(z).toInstant();
            String local = t.substring(0, sign).trim();
            String off = t.substring(sign);
            java.time.ZoneOffset o;
            if ("Z".equals(off)) o = java.time.ZoneOffset.UTC;
            else {
                String d = off.substring(1);
                if (d.length() == 4 && d.indexOf(':') < 0) d = d.substring(0, 2) + ":" + d.substring(2);
                if (d.length() == 2) d = d + ":00";
                o = java.time.ZoneOffset.of(off.charAt(0) + d);
            }
            return java.time.LocalDateTime.parse(local).atOffset(o).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Süre metni: "45 sn", "12 dk 3 sn", "3 sa 5 dk", "2 g 4 sa" (EN "45 s", "12 min 3 s", "3 h 5 min", "2 d 4 h"). */
    static String duration(double seconds, boolean en) {
        if (!Double.isFinite(seconds)) return "—";
        long s = Math.round(Math.abs(seconds));
        long d = s / 86_400, h = (s % 86_400) / 3_600, m = (s % 3_600) / 60, sec = s % 60;
        String ds = en ? " d" : " g", hs = en ? " h" : " sa", ms = en ? " min" : " dk", ss = en ? " s" : " sn";
        if (d > 0) return d + ds + (h > 0 ? " " + h + hs : "");
        if (h > 0) return h + hs + (m > 0 ? " " + m + ms : "");
        if (m > 0) return m + ms + (sec > 0 ? " " + sec + ss : "");
        return sec + ss;
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
        if (DbQueryRules.KIND_FRESHNESS.equals(r.kind())) {
            List<String> parts = new ArrayList<>();
            String ageWord = en ? "age " : "yaş ";
            if (r.warning() != null) parts.add(ageWord + healthyAge(r.operator(), r.warning(), en) + " (" + levelWord(LEVEL_WARNING, en) + ")");
            if (r.critical() != null) parts.add(ageWord + healthyAge(r.operator(), r.critical(), en) + " (" + levelWord(LEVEL_CRITICAL, en) + ")");
            return String.join(" · ", parts);
        }
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

    /** Tazelik eşiğinin sağlıklı metni: GT 600 → "≤ 10 dk", GTE 600 → "< 10 dk". */
    private static String healthyAge(String op, DbQueryRules.Threshold t, boolean en) {
        String v = t.value() == null ? "—" : duration(t.value(), en);
        return ("GTE".equals(op) ? "< " : "≤ ") + v;
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
                if (r.fingerprint() != null) m.put("fingerprint", r.fingerprint());
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
                        DbQueryRules.toDouble(m.get("measure")), DbQueryRules.toDouble(m.get("compared")),
                        str(m.get("fingerprint"))));
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

    /**
     * Saklı bir kural ölçüsü bugünkü kuralın TABANI olabilir mi (r177, H-1): aynı kimlik zaten eşleşmiş; parmak izi varsa
     * eşit olmalı ({@link DbQueryRules#fingerprint}); bu sürümden ÖNCEKİ satırda (parmak izi yok) yalnız tür denetlenir —
     * eski satırı reddetmek her değişim kuralını yayından sonra bir çalıştırma "değerlendirilemedi" bırakırdı (günlük cron'da
     * bir gün); sorgu özeti ayrıca çağıranda karşılaştırılır.
     */
    public static boolean sameMeasureDefinition(RuleResult stored, DbQueryRules.Rule current) {
        if (stored == null || current == null) return false;
        if (stored.fingerprint() != null) return stored.fingerprint().equals(DbQueryRules.fingerprint(current));
        return stored.kind() == null || stored.kind().equals(current.kind());
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
