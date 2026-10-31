package com.sitemonitor.service.dbquery;

import java.util.List;
import java.util.Map;

/**
 * Saf kural değerlendirici (sözleşme §3). Girdi: kurallar, birleşim (ANY | ALL), sorgu sonucu ve önceki zamanlanmış
 * çalıştırmanın kural başına TABAN ölçüleri ({@link RuleResult#measure()} — değişim kuralı için). Asla fırlatmaz.
 * İSKELET — gövde motor paketinde yazılır.
 */
public final class DbQueryEvaluator {

    public static final String LEVEL_NONE = "NONE", LEVEL_WARNING = "WARNING", LEVEL_CRITICAL = "CRITICAL";

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
        throw new UnsupportedOperationException("DbQueryEvaluator.evaluate");
    }
}
