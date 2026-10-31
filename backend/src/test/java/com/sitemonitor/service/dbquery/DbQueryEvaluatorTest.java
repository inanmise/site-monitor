package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.dbquery.DbQueryEvaluator.Outcome;
import com.sitemonitor.service.dbquery.DbQueryEvaluator.RuleResult;
import com.sitemonitor.service.dbquery.DbQueryRules.Rule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Saf değerlendirici: iki seviyeli eşik (kritik önce), ANY / ALL, metin eşleşmesi, değişim, değerlendirilemeyen kural. */
class DbQueryEvaluatorTest {

    private static Rule rule(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        DbQueryRules.Parsed p = DbQueryRules.fromApi(List.of(m));
        assertThat(p.errors()).as("geçerli kural").isEmpty();
        return p.rules().get(0);
    }

    private static DbQueryRunner.QueryResult result(List<String> cols, Object[][] rows) {
        List<DbQueryRunner.Column> c = cols.stream().map(n -> new DbQueryRunner.Column(n, "VARCHAR")).toList();
        List<List<Object>> r = new ArrayList<>();
        for (Object[] row : rows) r.add(Arrays.asList(row));
        return new DbQueryRunner.QueryResult(true, 12, r.size(), false, c, r, false, null, null, null);
    }

    private static DbQueryRunner.QueryResult rows(int n) {
        Object[][] r = new Object[n][];
        for (int i = 0; i < n; i++) r[i] = new Object[] {BigDecimal.valueOf(i)};
        return result(List.of("id"), r);
    }

    private static Outcome eval(List<Rule> rules, String logic, DbQueryRunner.QueryResult r) {
        return DbQueryEvaluator.evaluate(rules, logic, r, Map.of(), "tr");
    }

    @Test
    void rowCountTwoLevelsCriticalFirst() {
        Rule r = rule("kind", "ROW_COUNT", "operator", "GT", "warning", 10, "critical", 100);
        assertThat(eval(List.of(r), "ANY", rows(5)).status()).isEqualTo("OK");
        Outcome w = eval(List.of(r), "ANY", rows(50));
        assertThat(w.status()).isEqualTo("BREACH");
        assertThat(w.level()).isEqualTo("WARNING");
        Outcome c = eval(List.of(r), "ANY", rows(150));
        assertThat(c.level()).isEqualTo("CRITICAL");
        RuleResult rr = c.results().get(0);
        assertThat(rr.actual()).isEqualTo("150 satır");
        assertThat(rr.expected()).isEqualTo("≤ 10 (uyarı) · ≤ 100 (kritik)");
        assertThat(rr.measure()).isEqualTo(150.0);
        assertThat(c.metricValue()).isEqualTo(150.0);
    }

    @Test
    void cappedRowCountIsLowerBound() {
        Rule r = rule("kind", "ROW_COUNT", "operator", "GTE", "critical", 1000);
        DbQueryRunner.QueryResult capped = new DbQueryRunner.QueryResult(true, 1, 1000, true,
                List.of(new DbQueryRunner.Column("id", "INT")), List.of(), false, null, null, null);
        RuleResult rr = eval(List.of(r), "ANY", capped).results().get(0);
        assertThat(rr.level()).isEqualTo("CRITICAL");
        assertThat(rr.actual()).isEqualTo("≥ 1.000 satır");
        assertThat(rr.note()).isEqualTo(DbQueryEvaluator.NOTE_CAPPED);
    }

    @Test
    void columnValueAggregates() {
        DbQueryRunner.QueryResult r = result(List.of("Tutar", "ad"), new Object[][] {
                {new BigDecimal("10.5"), "a"}, {new BigDecimal("20"), "b"}, {null, "c"}, {"30", "d"}});
        Map<String, Double> expected = Map.of("FIRST", 10.5, "MAX", 30.0, "MIN", 10.5, "SUM", 60.5, "AVG", 60.5 / 3,
                "COUNT_NONNULL", 3.0);
        for (Map.Entry<String, Double> e : expected.entrySet()) {
            Rule rule = rule("kind", "COLUMN_VALUE", "column", "tutar", "aggregate", e.getKey(), "operator", "GT",
                    "warning", 1_000_000);
            RuleResult rr = eval(List.of(rule), "ANY", r).results().get(0);
            assertThat(rr.evaluated()).as(e.getKey()).isTrue();
            assertThat(rr.measure()).as(e.getKey()).isCloseTo(e.getValue(), org.assertj.core.data.Offset.offset(1e-9));
        }
        assertThat(DbQueryEvaluator.fmt(60.5, false)).isEqualTo("60,5");
        assertThat(DbQueryEvaluator.fmt(1250.5, false)).isEqualTo("1.250,5");
        assertThat(DbQueryEvaluator.fmt(1250.5, true)).isEqualTo("1,250.5");
        assertThat(DbQueryEvaluator.fmt(0.0042, false)).isEqualTo("0,0042");
    }

    @Test
    void unevaluableRuleIsNotABreach() {
        DbQueryRunner.QueryResult r = result(List.of("ad"), new Object[][] {{"x"}});
        Rule missing = rule("kind", "COLUMN_VALUE", "column", "yok", "operator", "GT", "critical", 0);
        Rule notNumber = rule("kind", "COLUMN_VALUE", "column", "ad", "operator", "GT", "critical", 0);
        Outcome o = eval(List.of(missing, notNumber), "ANY", r);
        assertThat(o.status()).isEqualTo("OK");
        assertThat(o.results()).allSatisfy(rr -> {
            assertThat(rr.evaluated()).isFalse();
            assertThat(rr.passed()).isTrue();
            assertThat(rr.level()).isEqualTo("NONE");
        });
        assertThat(o.results().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_COLUMN_MISSING);
        assertThat(o.results().get(1).note()).isEqualTo(DbQueryEvaluator.NOTE_NOT_NUMERIC);
        // metrik: değerlendirilen sayısal kural yok → satır sayısı
        assertThat(o.metricValue()).isEqualTo(1.0);
        // satır yok: FIRST değerlendirilemez, SUM 0
        DbQueryRunner.QueryResult empty = result(List.of("v"), new Object[][] {});
        assertThat(eval(List.of(rule("kind", "COLUMN_VALUE", "column", "v", "operator", "GT", "warning", 1)), "ANY", empty)
                .results().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_NO_ROWS);
        assertThat(eval(List.of(rule("kind", "COLUMN_VALUE", "column", "v", "aggregate", "SUM", "operator", "LT",
                "warning", 1)), "ANY", empty).level()).isEqualTo("WARNING");
    }

    @Test
    void anyAndAllLogic() {
        Rule a = rule("kind", "ROW_COUNT", "operator", "GT", "critical", 1);     // ihlal (CRITICAL)
        Rule b = rule("kind", "ROW_COUNT", "operator", "GT", "warning", 2);      // ihlal (WARNING)
        Rule c = rule("kind", "ROW_COUNT", "operator", "GT", "warning", 100);    // geçer
        DbQueryRunner.QueryResult r = rows(5);
        assertThat(eval(List.of(a, b), "ANY", r).level()).isEqualTo("CRITICAL");
        assertThat(eval(List.of(a, b), "ALL", r).level()).isEqualTo("WARNING");
        assertThat(eval(List.of(a, c), "ALL", r).status()).isEqualTo("OK");
        assertThat(eval(List.of(a, c), "ANY", r).status()).isEqualTo("BREACH");
        // ALL: değerlendirilemeyen kural ihlal sayılmaz → BREACH yok
        Rule missing = rule("kind", "COLUMN_VALUE", "column", "yok", "operator", "GT", "critical", 0);
        assertThat(eval(List.of(a, missing), "ALL", r).status()).isEqualTo("OK");
        assertThat(eval(List.of(), "ANY", r).status()).isEqualTo("OK");
    }

    @Test
    void textMatchScopesAndOperators() {
        DbQueryRunner.QueryResult r = result(List.of("durum"), new Object[][] {{"İŞLEM ERROR: x"}, {"ok"}, {null}, {"error 2"}});
        Rule any = rule("kind", "TEXT_MATCH", "column", "durum", "operator", "CONTAINS", "critical", "error");
        RuleResult rr = eval(List.of(any), "ANY", r).results().get(0);
        assertThat(rr.level()).isEqualTo("CRITICAL");
        assertThat(rr.measure()).isEqualTo(2.0);
        assertThat(rr.actual()).isEqualTo("'error' içeren 2 satır");
        assertThat(rr.actual()).doesNotContain("İŞLEM");                 // hücre değeri arayüze sızmaz
        Rule all = rule("kind", "TEXT_MATCH", "column", "durum", "operator", "CONTAINS", "warning", "error", "scope", "ALL_ROWS");
        assertThat(eval(List.of(all), "ANY", r).status()).isEqualTo("OK");
        Rule first = rule("kind", "TEXT_MATCH", "column", "durum", "operator", "CONTAINS", "warning", "işlem", "scope", "FIRST_ROW");
        assertThat(eval(List.of(first), "ANY", r).level()).isEqualTo("WARNING");   // İ/i katlaması
        Rule empty = rule("kind", "TEXT_MATCH", "column", "durum", "operator", "EMPTY", "warning", "");
        assertThat(eval(List.of(empty), "ANY", r).results().get(0).measure()).isEqualTo(1.0);
        Rule eq = rule("kind", "TEXT_MATCH", "column", "durum", "operator", "EQUALS", "warning", " OK ");
        assertThat(eval(List.of(eq), "ANY", r).status()).isEqualTo("BREACH");
        Rule notContains = rule("kind", "TEXT_MATCH", "column", "durum", "operator", "NOT_CONTAINS", "warning", "o",
                "scope", "ALL_ROWS");
        assertThat(eval(List.of(notContains), "ANY", r).status()).isEqualTo("OK");
    }

    @Test
    void textMatchIsLinearOnAdversarialData() {
        String cell = "a".repeat(4000);
        Object[][] data = new Object[2000][];
        for (int i = 0; i < data.length; i++) data[i] = new Object[] {cell};
        DbQueryRunner.QueryResult r = result(List.of("c"), data);
        Rule rule = rule("kind", "TEXT_MATCH", "column", "c", "operator", "CONTAINS", "warning", "a".repeat(499) + "b");
        long t0 = System.nanoTime();
        assertThat(eval(List.of(rule), "ANY", r).status()).isEqualTo("OK");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void changeRule() {
        Rule pct = rule("id", "c1", "kind", "CHANGE", "operator", "GT", "warning", 20, "critical", 50,
                "change", Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "INCREASE"));
        // ilk çalıştırma: değerlendirilmez, ölçü yazılır
        RuleResult first = eval(List.of(pct), "ANY", rows(100)).results().get(0);
        assertThat(first.evaluated()).isFalse();
        assertThat(first.note()).isEqualTo(DbQueryEvaluator.NOTE_FIRST_RUN);
        assertThat(first.measure()).isEqualTo(100.0);
        // %30 artış → uyarı
        RuleResult up = DbQueryEvaluator.evaluate(List.of(pct), "ANY", rows(130), Map.of("c1", 100.0), "tr").results().get(0);
        assertThat(up.level()).isEqualTo("WARNING");
        assertThat(up.compared()).isEqualTo(30.0);
        assertThat(up.actual()).isEqualTo("%30 artış (100 → 130)");
        assertThat(up.expected()).isEqualTo("artış ≤ %20 (uyarı) · artış ≤ %50 (kritik)");
        // azalış INCREASE kuralını tetiklemez
        assertThat(DbQueryEvaluator.evaluate(List.of(pct), "ANY", rows(10), Map.of("c1", 100.0), "tr").status()).isEqualTo("OK");
        // önceki 0 → adet farkı gibi
        RuleResult zero = DbQueryEvaluator.evaluate(List.of(pct), "ANY", rows(60), Map.of("c1", 0.0), "tr").results().get(0);
        assertThat(zero.note()).isEqualTo(DbQueryEvaluator.NOTE_PREV_ZERO);
        assertThat(zero.level()).isEqualTo("CRITICAL");
        // ANY yön, mutlak
        Rule abs = rule("id", "c2", "kind", "CHANGE", "operator", "GTE", "warning", 5,
                "change", Map.of("of", "ROW_COUNT", "mode", "ABSOLUTE", "direction", "ANY"));
        RuleResult down = DbQueryEvaluator.evaluate(List.of(abs), "ANY", rows(4), Map.of("c2", 10.0), "en").results().get(0);
        assertThat(down.level()).isEqualTo("WARNING");
        assertThat(down.actual()).isEqualTo("6 decrease (10 → 4)");
    }

    @Test
    void failedQueryEvaluatesNothing() {
        DbQueryRunner.QueryResult failed = new DbQueryRunner.QueryResult(false, 0, 0, false, List.of(), List.of(), false,
                "x", "DB_TIMEOUT", null);
        Outcome o = eval(List.of(rule("kind", "ROW_COUNT", "operator", "GT", "warning", 0)), "ANY", failed);
        assertThat(o.status()).isEqualTo("OK");
        assertThat(o.results().get(0).note()).isEqualTo(DbQueryEvaluator.NOTE_QUERY_FAILED);
    }

    @Test
    void englishTextsAndOperators() {
        Rule r = rule("kind", "ROW_COUNT", "operator", "OUTSIDE", "warning", List.of(1, 10));
        RuleResult rr = DbQueryEvaluator.evaluate(List.of(r), "ANY", rows(1500), Map.of(), "en").results().get(0);
        assertThat(rr.actual()).isEqualTo("1,500 rows");
        assertThat(rr.expected()).isEqualTo("within 1 – 10 (warning)");
        assertThat(rr.level()).isEqualTo("WARNING");
        assertThat(DbQueryEvaluator.violates("BETWEEN", 5, new DbQueryRules.Threshold(null, 1d, 10d, null))).isTrue();
        assertThat(DbQueryEvaluator.violates("EQ", 5, new DbQueryRules.Threshold(5d, null, null, null))).isTrue();
        assertThat(DbQueryEvaluator.violates("NEQ", 5, new DbQueryRules.Threshold(5d, null, null, null))).isFalse();
        assertThat(DbQueryEvaluator.violates("LTE", 5, new DbQueryRules.Threshold(5d, null, null, null))).isTrue();
    }

    @Test
    void ruleResultsJsonRoundTripFeedsNextRun() {
        Rule pct = rule("id", "c1", "kind", "CHANGE", "operator", "GT", "warning", 20,
                "change", Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "ANY"));
        Outcome o = eval(List.of(pct), "ANY", rows(40));
        String json = DbQueryEvaluator.toJson(o.results());
        assertThat(DbQueryEvaluator.fromJson(json)).isEqualTo(o.results());
        assertThat(DbQueryEvaluator.measuresFromJson(json)).containsEntry("c1", 40.0);
        assertThat(DbQueryEvaluator.measuresFromJson("bozuk")).isEmpty();
    }
}
