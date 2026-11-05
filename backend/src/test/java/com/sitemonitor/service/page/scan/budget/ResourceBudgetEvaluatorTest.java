package com.sitemonitor.service.page.scan.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bütçe değerlendirmesi (F1) doğruluk tablosu. Kural: bilinmeyen asla "geçti" değildir — bitmemiş analiz, eksik değer
 * ({@code missing}) ve tavana takılan analizin sınırı geçen değeri ({@code capped}) {@code ok:null}; kanıtlı aşım bilinmeyenden
 * önce gelir.
 */
class ResourceBudgetEvaluatorTest {

    /** status, capped, page_bytes, requests, js_bytes, image_bytes, load_ms, grade, security_score — "-" = null. */
    static ResourceBudgetEvaluator.Facts facts(String status, boolean capped, String... v) {
        Long[] n = new Long[7];
        for (int i = 0; i < 7; i++) n[i] = "-".equals(v[i]) ? null : Long.parseLong(v[i]);
        return new ResourceBudgetEvaluator.Facts(status, capped, n[0], n[1], n[2], n[3], n[4], n[5], n[6]);
    }

    /**
     * Doğruluk tablosu: bütçe JSON'u | durum | tavan | yedi değer (boşlukla) | beklenen durum | aşım sayısı | sınır başına ok
     * (t / f / ? = null, alan sırasıyla) | sınır başına neden (- = null).
     */
    @ParameterizedTest(name = "[{index}] {0} / {1} {2} / {3} → {4}")
    @CsvSource(delimiter = '|', value = {
            // bütçe yok → none, değer okunmaz
            "{} | DONE | false | 1 1 1 1 1 1 1 | none | 0 | '' | ''",
            // tek sınır: altında / tam sınırda / üstünde (KB tam bayt: 500 KB = 512000 bayt)
            "'{\"max_page_kb\":500}' | DONE | false | 511999 - - - - - - | ok | 0 | t | -",
            "'{\"max_page_kb\":500}' | DONE | false | 512000 - - - - - - | ok | 0 | t | -",
            "'{\"max_page_kb\":500}' | DONE | false | 512001 - - - - - - | exceeded | 1 | f | -",
            // değer yok → unknown (eski analiz / ölçülemedi), asla ok
            "'{\"max_page_kb\":500}' | DONE | false | - - - - - - - | unknown | 0 | ? | missing",
            // algoritma 3 kolonu yok (js / güvenlik) — diğer sınır geçse de bütün durum unknown
            "'{\"max_page_kb\":500,\"max_js_kb\":100}' | DONE | false | 1000 - - - - - - | unknown | 0 | t ? | - missing",
            // aşım bilinmeyenden önce gelir
            "'{\"max_page_kb\":500,\"max_js_kb\":100}' | DONE | false | 999999 - - - - - - | exceeded | 1 | f ? | - missing",
            // bitmemiş analiz → hepsi not_done
            "'{\"max_requests\":50}' | RUNNING | false | 1 1 1 1 1 1 1 | unknown | 0 | ? | not_done",
            "'{\"max_requests\":50}' | FAILED | false | 1 1 1 1 1 1 1 | unknown | 0 | ? | not_done",
            "'{\"max_requests\":50}' | INTERRUPTED | true | 1 1 1 1 1 1 1 | unknown | 0 | ? | not_done",
            // tavan: üst sınırlı değer altındaysa bilinmez, üstündeyse kanıtlı aşım
            "'{\"max_requests\":50}' | DONE | true | - 40 - - - - - | unknown | 0 | ? | capped",
            "'{\"max_requests\":50}' | DONE | true | - 51 - - - - - | exceeded | 1 | f | -",
            // MIN sınırlar: not / güvenlik puanı
            "'{\"min_grade\":80}' | DONE | false | - - - - - 80 - | ok | 0 | t | -",
            "'{\"min_grade\":80}' | DONE | false | - - - - - 79 - | exceeded | 1 | f | -",
            "'{\"min_grade\":80}' | DONE | true | - - - - - 90 - | unknown | 0 | ? | capped",
            // D-2: kısmi analizde not bir üst sınır DEĞİL (sıkıştırma / önbellek oran) — sınırın altında da olsa bilinmez
            "'{\"min_grade\":80}' | DONE | true | - - - - - 60 - | unknown | 0 | ? | capped",
            "'{\"min_grade\":80,\"max_requests\":50}' | DONE | true | - 51 - - - 60 - | exceeded | 1 | f ? | - capped",
            "'{\"min_security_score\":70}' | DONE | false | - - - - - - 69 | exceeded | 1 | f | -",
            // güvenlik puanı yalnız ceza düşer → kısmi analizin puanı üst sınır: altındaysa kanıtlı aşım, değilse bilinmez
            "'{\"min_security_score\":70}' | DONE | true | - - - - - - 40 | exceeded | 1 | f | -",
            "'{\"min_security_score\":70}' | DONE | true | - - - - - - 90 | unknown | 0 | ? | capped",
            "'{\"min_security_score\":70}' | DONE | false | - - - - - - - | unknown | 0 | ? | missing",
            "'{\"min_grade\":0}' | DONE | false | - - - - - 0 - | ok | 0 | t | -",
            // hepsi bir arada, iki aşım
            "'{\"max_page_kb\":1000,\"max_requests\":100,\"max_js_kb\":300,\"max_image_kb\":500,\"max_load_ms\":3000,\"min_grade\":70,\"min_security_score\":60}' | DONE | false | 2048000 80 307200 400000 3500 75 60 | exceeded | 2 | f t t t f t t | - - - - - - -",
            "'{\"max_page_kb\":1000,\"max_requests\":100,\"max_js_kb\":300,\"max_image_kb\":500,\"max_load_ms\":3000,\"min_grade\":70,\"min_security_score\":60}' | DONE | false | 1024000 100 307200 512000 3000 70 60 | ok | 0 | t t t t t t t | - - - - - - -",
    })
    void truthTable(String budgetJson, String status, boolean capped, String values, String expStatus, int expExceeded,
                    String expOk, String expReason) {
        ResourceBudget b = ResourceBudget.parse(budgetJson);
        ResourceBudgetEvaluator.Result r = ResourceBudgetEvaluator.evaluate(b, facts(status, capped, values.trim().split("\\s+")));
        assertThat(r.status()).isEqualTo(expStatus);
        assertThat(r.exceeded()).isEqualTo(expExceeded);
        assertThat(r.defined()).isEqualTo(b.defined());
        List<String> oks = expOk.isBlank() ? List.of() : List.of(expOk.trim().split("\\s+"));
        List<String> reasons = expReason.isBlank() ? List.of() : List.of(expReason.trim().split("\\s+"));
        assertThat(r.checks()).hasSize(oks.size());
        for (int i = 0; i < oks.size(); i++) {
            Boolean want = switch (oks.get(i)) { case "t" -> Boolean.TRUE; case "f" -> Boolean.FALSE; default -> null; };
            assertThat(r.checks().get(i).ok()).as("sınır %d", i).isEqualTo(want);
            String reason = "-".equals(reasons.get(i)) ? null : reasons.get(i);
            assertThat(r.checks().get(i).reason()).as("neden %d", i).isEqualTo(reason);
        }
    }

    @Test
    @DisplayName("bütçe tanımlı ama analiz yok (null) → hepsi bilinmiyor, asla ok")
    void nullFactsIsUnknown() {
        ResourceBudgetEvaluator.Result r = ResourceBudgetEvaluator.evaluate(ResourceBudget.parse("{\"min_grade\":50}"), null);
        assertThat(r.status()).isEqualTo("unknown");
        assertThat(r.checks().get(0).ok()).isNull();
        assertThat(r.checks().get(0).reason()).isEqualTo("not_done");
    }

    @Test
    @DisplayName("KB gösterimi sonuçla çelişmez: aşan değer yukarı, aşmayan aşağı yuvarlanır; tam bayt da döner")
    void kilobyteDisplayAgreesWithVerdict() {
        ResourceBudget b = ResourceBudget.parse("{\"max_page_kb\":500}");
        // 500 KB + 1 bayt: 500,0009 KB → gösterim 500,1 (aşıldı), "500,0 aşıldı" değil
        ResourceBudgetEvaluator.Check over = ResourceBudgetEvaluator.evaluate(b,
                facts("DONE", false, "512001", "-", "-", "-", "-", "-", "-")).checks().get(0);
        assertThat(over.ok()).isFalse();
        assertThat(over.actual()).isEqualTo(500.1);
        assertThat(over.actualBytes()).isEqualTo(512001L);
        // 499,96 KB → 499,9 (geçti; 500,0 yazıp kafa karıştırmaz)
        ResourceBudgetEvaluator.Check under = ResourceBudgetEvaluator.evaluate(b,
                facts("DONE", false, "511959", "-", "-", "-", "-", "-", "-")).checks().get(0);
        assertThat(under.ok()).isTrue();
        assertThat(under.actual()).isEqualTo(499.9);
        assertThat(ResourceBudgetEvaluator.kilobytes(0, false)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toMap biçimi: defined / status / exceeded / capped / checks{key, limit, unit, actual, ok, reason}")
    void mapShape() {
        Map<String, Object> m = ResourceBudgetEvaluator.evaluate(ResourceBudget.parse("{\"max_requests\":10,\"max_js_kb\":10}"),
                facts("DONE", false, "-", "12", "1024", "-", "-", "-", "-")).toMap();
        assertThat(m).containsEntry("defined", true).containsEntry("status", "exceeded").containsEntry("exceeded", 1)
                .containsEntry("capped", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) m.get("checks");
        assertThat(checks.get(0)).containsEntry("key", "max_requests").containsEntry("limit", 10).containsEntry("unit", "count")
                .containsEntry("actual", 12L).containsEntry("ok", false).doesNotContainKey("actual_bytes");
        assertThat(checks.get(1)).containsEntry("key", "max_js_kb").containsEntry("unit", "kb").containsEntry("actual", 1.0)
                .containsEntry("actual_bytes", 1024L).containsEntry("ok", true);
        assertThat(ResourceBudgetEvaluator.none().toMap()).containsEntry("defined", false).containsEntry("status", "none")
                .containsEntry("exceeded", 0);
    }
}
