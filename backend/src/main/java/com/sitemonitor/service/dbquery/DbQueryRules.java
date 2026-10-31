package com.sitemonitor.service.dbquery;

import java.util.List;
import java.util.Map;

/**
 * Değerlendirme kuralları modeli (sözleşme §3). JSON biçimi ({@code dbquery_monitors.rules_json}, API {@code rules}):
 * <pre>
 * { "id": "r1", "label": "…", "kind": "ROW_COUNT|COLUMN_VALUE|TEXT_MATCH|CHANGE",
 *   "column": "…", "aggregate": "FIRST|MAX|MIN|SUM|AVG|COUNT_NONNULL",
 *   "operator": "GT|GTE|LT|LTE|EQ|NEQ|BETWEEN|OUTSIDE|CONTAINS|NOT_CONTAINS|EQUALS|NOT_EQUALS|EMPTY|NOT_EMPTY",
 *   "scope": "ANY_ROW|ALL_ROWS|FIRST_ROW",
 *   "warning": 10 | [1, 5] | "text", "critical": 100 | [0, 2] | "text",
 *   "change": { "of": "ROW_COUNT|COLUMN", "mode": "PERCENT|ABSOLUTE", "direction": "INCREASE|DECREASE|ANY" } }
 * </pre>
 * İSKELET — gövdeler motor paketinde yazılır; imzalar sözleşmedir.
 */
public final class DbQueryRules {

    public static final int MAX_RULES = 10;

    /** Eşik: sayısal tek değer, aralık [low, high] ya da metin (TEXT_MATCH). Hepsi null = tanımsız. */
    public record Threshold(Double value, Double low, Double high, String text) {}

    public record Change(String of, String mode, String direction) {}

    public record Rule(String id, String label, String kind, String column, String aggregate, String operator,
                       String scope, Threshold warning, Threshold critical, Change change) {}

    /** Alan hatası: {@code field} = "rules[2].warning" gibi; {@code message} istek dilinde. */
    public record FieldError(String field, String code, String message) {}

    private DbQueryRules() {}

    /** API gövdesindeki listeyi (List of Map) kurallara çevirir; biçim hatası {@link FieldError} olarak döner, fırlatmaz. */
    public static Parsed fromApi(Object raw) {
        throw new UnsupportedOperationException("DbQueryRules.fromApi");
    }

    public record Parsed(List<Rule> rules, List<FieldError> errors) {}

    /** Saklanan JSON'u gevşek okur (bozuk kural atlanır, asla fırlatmaz). */
    public static List<Rule> fromJson(String json) {
        throw new UnsupportedOperationException("DbQueryRules.fromJson");
    }

    public static String toJson(List<Rule> rules) {
        throw new UnsupportedOperationException("DbQueryRules.toJson");
    }

    /** API'ye yazılan biçim (snake_case, null alanlar yok). */
    public static List<Map<String, Object>> toApi(List<Rule> rules) {
        throw new UnsupportedOperationException("DbQueryRules.toApi");
    }
}
