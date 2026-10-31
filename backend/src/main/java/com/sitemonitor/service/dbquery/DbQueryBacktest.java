package com.sitemonitor.service.dbquery;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Geriye dönük eşik denemesi (r177, Ö-8): "Bu eşiklerle son N günde kaç alarm açılırdı?" — SAKLI zamanlanmış çalıştırmaların
 * kural ölçümleri ({@code rule_results_json.measure}) YENİ kurallarla yeniden değerlendirilir. Veritabanına sorgu YOK, satır
 * verisi YOK. Saf, durumlu tek kullanımlık: satırlar ZAMAN SIRASIYLA {@link #accept} ile verilir (bellekte yalnız mevsimsel
 * taban penceresi), {@link #report} sonucu döner.
 *
 * <p>Kurallar: bir saklı ölçü yeni kuralda kullanılır ancak aynı kural kimliği + aynı sorgu özeti + aynı ölçüm tanımı
 * ({@link DbQueryEvaluator#sameMeasureDefinition}; parmak izi olmayan eski satırda metin eşleşmesi kullanılamaz — aranan metin
 * doğrulanamaz). Satır sayısı / kolon değeri / tazelik: ölçü yeni eşiklerle; metin eşleşmesi: saklı karar (eşleşme sayısı
 * aynı tanımla aynıdır) yeni seviyeyle; değişim: saklı TABAN ölçülerinden yeniden hesap (önceki = bir önceki uygun satır;
 * mevsimsel = dünün / geçen haftanın aynı saatine en yakın uygun satır, ± min(aralığın yarısı, 30 dk)). Birleşim ANY / ALL
 * canlı değerlendiriciyle aynı. Alarm sayımı: teyit / kurtarma yeniden ölçümleri saklanmadığından ardışık ihlal eden
 * çalıştırmalar TEK alarm sayılır; değerlendirilebilen ihlalsiz bir çalıştırma alarmı kapatır, değerlendirilemeyen kapatmaz.
 */
public final class DbQueryBacktest {

    /** Atlanma nedeni kodları ({@code per_rule[].skipped_reason}, kalıcı). */
    public static final String SKIP_NOT_FOUND = "not_found", SKIP_DEFINITION_CHANGED = "definition_changed",
            SKIP_NO_MEASURE = "no_measure";

    /** Saklı bir çalıştırma (izdüşüm). */
    public record Row(long id, Instant at, boolean ok, String sqlHash, List<DbQueryEvaluator.RuleResult> results) {}

    private final List<DbQueryRules.Rule> rules;
    private final boolean all;
    private final String sqlHash;
    private final Instant from;
    private final long toleranceMs;

    // Sayaçlar
    private int runs, evaluated, warning, critical, alarms, skippedOtherQuery, failedRuns;
    private boolean alarmOpen;
    private Instant firstAt, lastAt, windowFirst, windowLast;
    private final Map<String, int[]> perRule = new LinkedHashMap<>();    // [evaluated, warning, critical, found, compatible]
    /** Değişim kuralı başına: son uygun taban ölçüsü (önceki çalıştırma). */
    private final Map<String, Double> lastMeasure = new HashMap<>();
    /** Mevsimsel değişim kuralı başına: (an, ölçü) penceresi — en çok 7 gün + tolerans. */
    private final Map<String, Deque<long[]>> seasonal = new HashMap<>();

    /**
     * @param from        değerlendirilecek pencerenin başı — öncesindeki satırlar yalnız TABAN olarak kullanılır
     * @param intervalSec izlemenin etkin aralığı (mevsimsel tolerans)
     */
    public DbQueryBacktest(List<DbQueryRules.Rule> rules, String logic, String sqlHash, Instant from, Integer intervalSec) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.all = "ALL".equalsIgnoreCase(logic == null ? "" : logic.trim());
        this.sqlHash = sqlHash;
        this.from = from;
        long intervalMs = Math.max(60_000L, (intervalSec == null ? 300L : intervalSec) * 1000L);
        this.toleranceMs = Math.min(30L * 60 * 1000, intervalMs / 2);
        for (DbQueryRules.Rule r : this.rules) perRule.put(r.id(), new int[5]);
    }

    /** Pencereden ne kadar geri satır okunmalı (mevsimsel taban için): yoksa 0. */
    public static long lookbackMs(List<DbQueryRules.Rule> rules) {
        long out = 0;
        if (rules == null) return 0;
        for (DbQueryRules.Rule r : rules) {
            if (!DbQueryRules.KIND_CHANGE.equals(r.kind()) || r.change() == null) continue;
            String b = r.change().baselineOrPrevious();
            if (DbQueryRules.BASELINE_LAST_WEEK.equals(b)) out = Math.max(out, 7L * 86_400_000L + 31L * 60_000L);
            else if (DbQueryRules.BASELINE_YESTERDAY.equals(b)) out = Math.max(out, 86_400_000L + 31L * 60_000L);
        }
        return out;
    }

    /** Bir satır (zaman sırasıyla). */
    public void accept(Row row) {
        if (row == null || row.at() == null) return;
        boolean inWindow = from == null || !row.at().isBefore(from);
        boolean sameQuery = row.sqlHash() == null || sqlHash == null || sqlHash.equals(row.sqlHash());
        Map<String, DbQueryEvaluator.RuleResult> stored = new HashMap<>();
        if (row.results() != null) for (DbQueryEvaluator.RuleResult r : row.results()) if (r != null && r.id() != null) stored.putIfAbsent(r.id(), r);
        if (inWindow) {
            runs++;
            if (windowFirst == null) windowFirst = row.at();
            windowLast = row.at();
        }
        if (!row.ok()) {
            if (inWindow) failedRuns++;
            return;   // sorgu çalışmadı: kural ölçüsü yok; alarm durumu değişmez
        }
        if (!sameQuery) {
            if (inWindow) skippedOtherQuery++;
            return;
        }
        List<Verdict> verdicts = new ArrayList<>();
        for (DbQueryRules.Rule r : rules) {
            DbQueryEvaluator.RuleResult sr = stored.get(r.id());
            int[] pr = perRule.get(r.id());
            Verdict v = evaluate(r, sr, row.at(), pr, inWindow);
            verdicts.add(v);
        }
        if (!inWindow) return;
        String level = combine(verdicts);
        if (level == null) return;   // karar verilemedi: sayılmaz, açık alarm kapanmaz
        evaluated++;
        if ("NONE".equals(level)) {
            alarmOpen = false;
            return;
        }
        if ("CRITICAL".equals(level)) critical++;
        else warning++;
        if (firstAt == null) firstAt = row.at();
        lastAt = row.at();
        if (!alarmOpen) {
            alarms++;
            alarmOpen = true;
        }
    }

    /** @param level NONE | WARNING | CRITICAL; {@code evaluated} false = karar yok (eşik denemesinde "bilinmiyor") */
    private record Verdict(boolean evaluated, boolean passed, String level, boolean inconclusive) {}

    private Verdict evaluate(DbQueryRules.Rule r, DbQueryEvaluator.RuleResult sr, Instant at, int[] pr, boolean inWindow) {
        if (sr == null) return new Verdict(false, true, "NONE", true);
        if (inWindow) pr[3]++;
        boolean legacyText = DbQueryRules.KIND_TEXT_MATCH.equals(r.kind()) && sr.fingerprint() == null;
        if (legacyText || !DbQueryEvaluator.sameMeasureDefinition(sr, r)) return new Verdict(false, true, "NONE", true);
        if (inWindow) pr[4]++;
        Verdict v = switch (r.kind()) {
            case DbQueryRules.KIND_ROW_COUNT, DbQueryRules.KIND_COLUMN_VALUE, DbQueryRules.KIND_FRESHNESS -> {
                if (!sr.evaluated() || sr.measure() == null) {
                    // değer yok (satır yok / NULL) → canlı değerlendiricide "geçti, değerlendirilemedi"
                    boolean inconclusive = DbQueryEvaluator.isInconclusive(sr.kind(), sr.note());
                    yield new Verdict(false, true, "NONE", inconclusive);
                }
                String lvl = DbQueryEvaluator.numericLevel(r, sr.measure());
                yield new Verdict(true, "NONE".equals(lvl), lvl, false);
            }
            case DbQueryRules.KIND_TEXT_MATCH -> {
                if (!sr.evaluated()) yield new Verdict(false, true, "NONE", DbQueryEvaluator.isInconclusive(sr.kind(), sr.note()));
                boolean violated = !sr.passed();
                String lvl = violated ? (r.critical() != null ? "CRITICAL" : "WARNING") : "NONE";
                yield new Verdict(true, !violated, lvl, false);
            }
            case DbQueryRules.KIND_CHANGE -> change(r, sr, at);
            default -> new Verdict(false, true, "NONE", true);
        };
        if (inWindow && v.evaluated()) {
            pr[0]++;
            if ("CRITICAL".equals(v.level())) pr[2]++;
            else if ("WARNING".equals(v.level())) pr[1]++;
        }
        return v;
    }

    private Verdict change(DbQueryRules.Rule r, DbQueryEvaluator.RuleResult sr, Instant at) {
        Double base = sr.measure();
        String mode = r.change() == null ? DbQueryRules.BASELINE_PREVIOUS : r.change().baselineOrPrevious();
        Double prev;
        if (DbQueryRules.BASELINE_PREVIOUS.equals(mode)) {
            prev = lastMeasure.get(r.id());
            if (base != null) lastMeasure.put(r.id(), base);
        } else {
            long back = DbQueryRules.BASELINE_LAST_WEEK.equals(mode) ? 7L * 86_400_000L : 86_400_000L;
            Deque<long[]> win = seasonal.computeIfAbsent(r.id(), k -> new ArrayDeque<>());
            long target = at.toEpochMilli() - back;
            prev = null;
            long bestGap = Long.MAX_VALUE;
            for (long[] e : win) {
                long gap = Math.abs(e[0] - target);
                if (gap <= toleranceMs && gap < bestGap) {
                    bestGap = gap;
                    prev = Double.longBitsToDouble(e[1]);
                }
            }
            if (base != null) {
                win.addLast(new long[]{at.toEpochMilli(), Double.doubleToLongBits(base)});
                long keep = at.toEpochMilli() - back - toleranceMs - 1;
                while (!win.isEmpty() && win.peekFirst()[0] < keep) win.removeFirst();
            }
        }
        // Ölçü yok → kanıt eksikliği; ölçü var ama taban yok → "taban kuruluyor": ihlal değil, kanıt eksikliği de değil
        // (canlı değerlendiriciyle aynı — r177 ikinci tur O-6)
        if (base == null) return new Verdict(false, true, "NONE", true);
        if (prev == null) return new Verdict(false, true, "NONE", false);
        DbQueryEvaluator.RuleResult rr = DbQueryEvaluator.changeAgainst(r, base, prev, null, "", false);
        return new Verdict(true, rr.passed(), rr.level(), false);
    }

    /** ANY / ALL birleşimi (canlı değerlendiriciyle aynı); karar verilemiyorsa null. */
    private String combine(List<Verdict> vs) {
        if (vs.isEmpty()) return null;
        if (all) {
            String min = "CRITICAL";
            boolean anyInconclusive = false;
            for (Verdict v : vs) {
                if (!v.evaluated()) {
                    if (v.inconclusive()) anyInconclusive = true;
                    else return "NONE";   // değer yok = geçti → ALL ihlal olamaz
                    continue;
                }
                if (v.passed()) return "NONE";
                if (rank(v.level()) < rank(min)) min = v.level();
            }
            return anyInconclusive ? null : min;
        }
        String max = "NONE";
        boolean anyInconclusive = false, anyDecided = false;
        for (Verdict v : vs) {
            if (!v.evaluated()) {
                if (v.inconclusive()) anyInconclusive = true;
                else anyDecided = true;
                continue;
            }
            anyDecided = true;
            if (!v.passed() && rank(v.level()) > rank(max)) max = v.level();
        }
        if (!"NONE".equals(max)) return max;
        return anyInconclusive || !anyDecided ? null : "NONE";
    }

    private static int rank(String level) {
        if ("CRITICAL".equals(level)) return 2;
        if ("WARNING".equals(level)) return 1;
        return 0;
    }

    /** Sonuç (API biçimi, sözleşme 2.D + {@code from} / {@code to} / {@code skipped_other_query} / {@code failed_runs}). */
    public Map<String, Object> report(boolean truncated) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", runs);
        out.put("evaluated", evaluated);
        Map<String, Object> wb = new LinkedHashMap<>();
        wb.put("warning", warning);
        wb.put("critical", critical);
        out.put("would_breach", wb);
        out.put("would_open_alarms", alarms);
        out.put("first_at", firstAt == null ? null : iso(firstAt));
        out.put("last_at", lastAt == null ? null : iso(lastAt));
        out.put("from", windowFirst == null ? null : iso(windowFirst));
        out.put("to", windowLast == null ? null : iso(windowLast));
        out.put("failed_runs", failedRuns);
        out.put("skipped_other_query", skippedOtherQuery);
        out.put("truncated", truncated);
        List<Map<String, Object>> pr = new ArrayList<>();
        for (DbQueryRules.Rule r : rules) {
            int[] c = perRule.get(r.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("evaluated", c[0]);
            m.put("warning", c[1]);
            m.put("critical", c[2]);
            m.put("skipped_reason", c[0] > 0 ? null : c[3] == 0 ? SKIP_NOT_FOUND : c[4] == 0 ? SKIP_DEFINITION_CHANGED : SKIP_NO_MEASURE);
            pr.add(m);
        }
        out.put("per_rule", pr);
        return out;
    }

    private static String iso(Instant t) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(t);
    }
}
