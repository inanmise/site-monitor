package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Maskeli kolon ölçüsünün postada gizlenmesi kuralı (dbq2 inceleme O2). */
class DbQueryMailMaskTest {

    private static Map<String, Object> res(String id, String kind, Double compared, boolean evaluated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("actual", compared == null ? "—" : String.valueOf(compared));
        m.put("expected", "bant 10–20");
        m.put("evaluated", evaluated);
        m.put("passed", false);
        m.put("compared", compared);
        m.put("stats", Map.of("mean", 15.0));
        return m;
    }

    @Test
    @DisplayName("maskeli kural kimlikleri: büyük/küçük harf duyarsız, satır sayısı ve metin eşleşmesi hariç, kimliksiz → *; "
            + "doğrulanmamış (yeni) tür de sayılır; bozuk JSON → boş")
    void maskedRuleIds() {
        String rules = "[{\"id\":\"a\",\"kind\":\"COLUMN_VALUE\",\"column\":\"Tutar\"},"
                + "{\"id\":\"b\",\"kind\":\"ROW_COUNT\"},{\"id\":\"c\",\"kind\":\"TEXT_MATCH\",\"column\":\"tutar\"},"
                + "{\"id\":\"d\",\"kind\":\"ANOMALY\",\"column\":\"TUTAR\"},{\"id\":\"e\",\"kind\":\"FRESHNESS\",\"column\":\"zaman\"}]";
        assertThat(DbQueryMailMask.maskedRuleIds(rules, "[\" tutar \"]")).containsExactlyInAnyOrder("a", "d");
        assertThat(DbQueryMailMask.maskedRuleIds(rules, "[]")).isEmpty();
        assertThat(DbQueryMailMask.maskedRuleIds(rules, null)).isEmpty();
        assertThat(DbQueryMailMask.maskedRuleIds("{bozuk", "[\"tutar\"]")).isEmpty();
        assertThat(DbQueryMailMask.maskedRuleIds("[{\"kind\":\"CHANGE\",\"column\":\"tutar\"}]", "[\"tutar\"]"))
                .containsExactly(DbQueryMailMask.ALL);
    }

    @Test
    @DisplayName("ölçü maskesi: değerin kaynağı ilk değerlendirilmiş + karşılaştırma değeri olan + metin eşleşmesi olmayan kural; "
            + "kaynak yoksa satır sayısı (maskesiz); sonuç yoksa temkinli")
    void metricMasked() {
        Set<String> ids = Set.of("b");
        assertThat(DbQueryMailMask.metricMasked(List.of(res("a", "COLUMN_VALUE", 1.0, true), res("b", "COLUMN_VALUE", 2.0, true)), ids)).isFalse();
        assertThat(DbQueryMailMask.metricMasked(List.of(res("a", "COLUMN_VALUE", 1.0, false), res("b", "COLUMN_VALUE", 2.0, true)), ids)).isTrue();
        assertThat(DbQueryMailMask.metricMasked(List.of(res("x", "TEXT_MATCH", 3.0, true), res("b", "COLUMN_VALUE", 2.0, true)), ids)).isTrue();
        assertThat(DbQueryMailMask.metricMasked(List.of(res("b", "COLUMN_VALUE", null, true)), ids)).isFalse();
        assertThat(DbQueryMailMask.metricMasked(List.of(), ids)).isTrue();
        assertThat(DbQueryMailMask.metricMasked(List.of(), Set.of())).isFalse();
    }

    @Test
    @DisplayName("O1 (hata avı 2026-11-05): değer, henüz değerlendirilmemiş (taban kuruluyor / örnek yetersiz) AKILLI kuraldan "
            + "geldiyse maske onu görür — motorun seçim kuralıyla aynı (measuresMetric)")
    void metricMaskedFollowsEngineSelectionForSmartRules() {
        Map<String, Object> anomaly = res("r1", "ANOMALY", 1234.5, false);
        anomaly.put("note", DbQueryEvaluator.NOTE_BUILDING_BASELINE);
        Map<String, Object> fresh = res("r2", "FRESHNESS", 7200.0, true);
        // Senaryo B: ilk sırada taban kuran akıllı kural (maskeli kolon), sonra maskesiz tazelik kuralı → değer akıllı kuraldan
        assertThat(DbQueryMailMask.metricMasked(List.of(anomaly, fresh), Set.of("r1"))).isTrue();
        // Senaryo A: tek kural, örnek yetersiz → değer o kuraldan, maskeli
        Map<String, Object> insufficient = res("r1", "ANOMALY", 1234.5, false);
        insufficient.put("note", DbQueryEvaluator.NOTE_INSUFFICIENT_SAMPLES);
        assertThat(DbQueryMailMask.metricMasked(List.of(insufficient), Set.of("r1"))).isTrue();
        // Donmuş değer de akıllı kuraldır; değerlendirilmemiş SIRADAN kural ise değerin kaynağı değildir
        assertThat(DbQueryMailMask.metricMasked(List.of(res("f", "FROZEN", 5.0, false)), Set.of("f"))).isTrue();
        assertThat(DbQueryMailMask.metricMasked(List.of(res("c", "COLUMN_VALUE", 5.0, false), fresh), Set.of("c"))).isFalse();
        // Motor ile maske aynı kaynağı seçer: değerlendiricinin ürettiği metric_value maskeli kuralın değeri
        DbQueryRules.Rule an = DbQueryRules.fromApi(List.of(Map.of("id", "r1", "kind", "ANOMALY", "column", "tutar",
                "min_samples", 5))).rules().get(0);
        DbQueryRules.Rule fr = DbQueryRules.fromApi(List.of(Map.of("id", "r2", "kind", "ROW_COUNT", "operator", "GT",
                "warning", 0))).rules().get(0);
        DbQueryEvaluator.Outcome o = DbQueryEvaluator.evaluate(List.of(an, fr), "ANY",
                new DbQueryRunner.QueryResult(true, 5, 1, false, List.of(new DbQueryRunner.Column("tutar", "NUMERIC")),
                        List.of(List.<Object>of(1234.5)), false, null, null, null),
                new DbQueryEvaluator.Baselines(Map.of(), Map.of(), Map.of("r1", DbQueryEvaluator.History.EMPTY)), "tr");
        assertThat(o.results().get(0).evaluated()).as("taban kuruluyor").isFalse();
        assertThat(o.metricValue()).as("değer akıllı kuraldan").isEqualTo(1234.5);
        assertThat(DbQueryMailMask.metricMasked(DbQueryAlarmText.toApi(o.results()), Set.of("r1"))).isTrue();
    }

    @Test
    @DisplayName("measuresMetric: değerlendirilmiş ya da akıllı; karşılaştırma değeri şart; metin eşleşmesi asla; tür büyük/küçük harf duyarsız")
    void measuresMetricRule() {
        assertThat(DbQueryEvaluator.measuresMetric("COLUMN_VALUE", true, 1.0)).isTrue();
        assertThat(DbQueryEvaluator.measuresMetric("COLUMN_VALUE", false, 1.0)).isFalse();
        assertThat(DbQueryEvaluator.measuresMetric("ANOMALY", false, 1.0)).isTrue();
        assertThat(DbQueryEvaluator.measuresMetric("frozen", false, 1.0)).isTrue();
        assertThat(DbQueryEvaluator.measuresMetric("ANOMALY", true, null)).isFalse();
        assertThat(DbQueryEvaluator.measuresMetric("TEXT_MATCH", true, 3.0)).isFalse();
        assertThat(DbQueryEvaluator.measuresMetric(null, true, 3.0)).isTrue();
    }

    @Test
    @DisplayName("maskeli sonuç: gelen ••••, ölçü / istatistik alanları atılır; beklenen yalnız yapılandırma türünde açık")
    void maskResults() {
        List<Map<String, Object>> out = DbQueryMailMask.maskResults(
                List.of(res("a", "COLUMN_VALUE", 9.0, true), res("d", "ANOMALY", 9.0, true), res("z", "COLUMN_VALUE", 1.0, true)),
                Set.of("a", "d"));
        assertThat(out.get(0)).containsEntry("actual", "••••").containsEntry("expected", "bant 10–20")
                .doesNotContainKeys("compared", "stats");
        assertThat(out.get(1)).containsEntry("actual", "••••").containsEntry("expected", "••••");
        assertThat(out.get(2)).containsEntry("actual", "1.0").containsKey("compared");
    }
}
