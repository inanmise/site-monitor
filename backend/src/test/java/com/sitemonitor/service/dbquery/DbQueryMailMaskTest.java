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
