package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.dbquery.DbQueryRules.FieldError;
import com.sitemonitor.service.dbquery.DbQueryRules.Parsed;
import com.sitemonitor.service.dbquery.DbQueryRules.Rule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kural modeli: katı API okuması (alan hataları {@code rules[N].<alt>}), gevşek JSON okuması, gidiş-dönüş. */
class DbQueryRulesTest {

    private static Map<String, Object> rule(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<String> fields(Parsed p) {
        return p.errors().stream().map(FieldError::field).toList();
    }

    @Test
    void validRulesOfEveryKind() {
        Parsed p = DbQueryRules.fromApi(List.of(
                rule("kind", "ROW_COUNT", "operator", "GT", "warning", 10, "critical", 100),
                rule("id", "sum-1", "label", "Toplam", "kind", "column_value", "column", "tutar", "aggregate", "sum",
                        "operator", "BETWEEN", "critical", List.of(0, 5)),
                rule("kind", "TEXT_MATCH", "column", "durum", "operator", "CONTAINS", "warning", "ERROR"),
                rule("kind", "CHANGE", "operator", "GT", "warning", 20,
                        "change", Map.of("of", "ROW_COUNT", "mode", "PERCENT", "direction", "INCREASE"))));
        assertThat(p.errors()).isEmpty();
        assertThat(p.rules()).hasSize(4);
        Rule r0 = p.rules().get(0);
        assertThat(r0.id()).isEqualTo("r1");
        assertThat(r0.column()).isNull();
        assertThat(r0.warning().value()).isEqualTo(10.0);
        Rule r1 = p.rules().get(1);
        assertThat(r1.id()).isEqualTo("sum-1");
        assertThat(r1.kind()).isEqualTo("COLUMN_VALUE");
        assertThat(r1.aggregate()).isEqualTo("SUM");
        assertThat(r1.critical().isRange()).isTrue();
        assertThat(p.rules().get(2).scope()).isEqualTo("ANY_ROW");
        assertThat(p.rules().get(2).warning().text()).isEqualTo("ERROR");
        assertThat(p.rules().get(3).change().direction()).isEqualTo("INCREASE");
    }

    @Test
    void generatedIdsSkipExplicitOnes() {
        Parsed p = DbQueryRules.fromApi(List.of(
                rule("kind", "ROW_COUNT", "operator", "GT", "warning", 1),
                rule("id", "r1", "kind", "ROW_COUNT", "operator", "LT", "warning", 1)));
        assertThat(p.errors()).isEmpty();
        assertThat(p.rules()).extracting(Rule::id).containsExactly("r2", "r1");
    }

    @Test
    void fieldErrorsCarryRulePath() {
        Parsed p = DbQueryRules.fromApi(List.of(
                rule("kind", "ROW_COUNT", "operator", "GT"),                                   // eşik yok
                rule("kind", "COLUMN_VALUE", "operator", "GT", "warning", 1),                  // kolon yok
                rule("kind", "TEXT_MATCH", "column", "c", "operator", "GT", "warning", "x"),   // sayısal operatör
                rule("kind", "ROW_COUNT", "operator", "BETWEEN", "warning", List.of(5, 1)),    // alt > üst
                rule("kind", "ROW_COUNT", "operator", "GT", "warning", "on"),                 // sayı değil
                rule("kind", "NOPE", "operator", "GT", "warning", 1),
                rule("id", "kötü id!", "kind", "ROW_COUNT", "operator", "GT", "warning", 1),
                rule("kind", "CHANGE", "operator", "GT", "warning", 1, "change", "x")));
        assertThat(fields(p)).contains("rules[0].warning", "rules[1].column", "rules[2].operator", "rules[3].warning",
                "rules[4].warning", "rules[5].kind", "rules[6].id", "rules[7].change");
        assertThat(p.rules()).isEmpty();
        assertThat(p.errors()).allSatisfy(e -> assertThat(e.message()).contains("·"));
    }

    @Test
    void tooManyRules() {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < 11; i++) list.add(rule("kind", "ROW_COUNT", "operator", "GT", "warning", i));
        Parsed p = DbQueryRules.fromApi(list);
        assertThat(fields(p)).contains("rules");
        assertThat(p.rules()).hasSize(10);
    }

    @Test
    void notAListAndNull() {
        assertThat(fields(DbQueryRules.fromApi("x"))).containsExactly("rules");
        Parsed empty = DbQueryRules.fromApi(null);
        assertThat(empty.rules()).isEmpty();
        assertThat(empty.errors()).isEmpty();
    }

    @Test
    void duplicateIdsRejected() {
        Parsed p = DbQueryRules.fromApi(List.of(
                rule("id", "a", "kind", "ROW_COUNT", "operator", "GT", "warning", 1),
                rule("id", "a", "kind", "ROW_COUNT", "operator", "GT", "warning", 2)));
        assertThat(fields(p)).contains("rules[1].id");
    }

    @Test
    void textMatchEmptyOperatorNeedsNoText() {
        Parsed p = DbQueryRules.fromApi(List.of(rule("kind", "TEXT_MATCH", "column", "c", "operator", "EMPTY", "critical", "")));
        assertThat(p.errors()).isEmpty();
        Parsed q = DbQueryRules.fromApi(List.of(rule("kind", "TEXT_MATCH", "column", "c", "operator", "CONTAINS", "critical", "")));
        assertThat(fields(q)).contains("rules[0].critical");
    }

    @Test
    void jsonRoundTripAndLenientRead() {
        Parsed p = DbQueryRules.fromApi(List.of(
                rule("kind", "ROW_COUNT", "operator", "GT", "warning", 10, "critical", 100.5),
                rule("kind", "COLUMN_VALUE", "column", "x", "operator", "OUTSIDE", "warning", List.of(1, 2)),
                rule("kind", "TEXT_MATCH", "column", "c", "operator", "NOT_CONTAINS", "warning", "OK", "scope", "ALL_ROWS"),
                rule("kind", "CHANGE", "column", "x", "aggregate", "MAX", "operator", "GTE", "critical", 5,
                        "change", Map.of("of", "COLUMN", "mode", "ABSOLUTE", "direction", "ANY"))));
        String json = DbQueryRules.toJson(p.rules());
        assertThat(json).contains("\"warning\":10,").contains("\"critical\":100.5").doesNotContain("null");
        assertThat(DbQueryRules.fromJson(json)).isEqualTo(p.rules());
        // bozuk öğe atlanır, geçerliler kalır; bozuk JSON → boş
        String mixed = "[{\"kind\":\"ROW_COUNT\",\"operator\":\"GT\",\"warning\":1},{\"kind\":\"??\"},42]";
        assertThat(DbQueryRules.fromJson(mixed)).hasSize(1);
        assertThat(DbQueryRules.fromJson("{bozuk")).isEmpty();
        assertThat(DbQueryRules.fromJson(null)).isEmpty();
        assertThat(DbQueryRules.toApi(p.rules()).get(1).get("warning")).isEqualTo(List.of(1L, 2L));
    }
}
