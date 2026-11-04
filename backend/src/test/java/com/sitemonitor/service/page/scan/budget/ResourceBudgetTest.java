package com.sitemonitor.service.page.scan.budget;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bütçe modeli (F1): saklanan JSON GEVŞEK okunur (bozuk / aralık dışı / kesirli değer o sınırı tanımsız yapar, asla fırlatmaz),
 * istek KATI uygulanır (yalnız gönderilen anahtar değişir, null kaldırır, geçersiz değer alan hatası). Aralıklar sözleşmeden.
 */
class ResourceBudgetTest {

    @Test
    @DisplayName("alanlar sözleşmedeki sırayla ve aralıklarla — tek kaynak")
    void fieldsMatchContract() {
        assertThat(ResourceBudget.FIELDS).extracting(ResourceBudget.Field::key).containsExactly(
                "max_page_kb", "max_requests", "max_js_kb", "max_image_kb", "max_load_ms", "min_grade", "min_security_score");
        assertThat(ResourceBudget.FIELDS).extracting(f -> f.min() + "-" + f.max()).containsExactly(
                "50-51200", "5-2000", "10-20480", "10-51200", "200-60000", "0-100", "0-100");
        assertThat(ResourceBudget.FIELDS).extracting(ResourceBudget.Field::kind).containsExactly(
                ResourceBudget.Kind.MAX, ResourceBudget.Kind.MAX, ResourceBudget.Kind.MAX, ResourceBudget.Kind.MAX,
                ResourceBudget.Kind.MAX, ResourceBudget.Kind.MIN, ResourceBudget.Kind.MIN);
    }

    /** Gevşek okuma doğruluk tablosu: saklanan metin → beklenen JSON (null = bütçe yok). */
    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "NULL | NULL",
            "'' | NULL",
            "'   ' | NULL",
            "bozuk | NULL",
            "[1,2] | NULL",
            "null | NULL",
            "{} | NULL",
            "'{\"max_page_kb\":500}' | '{\"max_page_kb\":500}'",
            // sıra her zaman alan sırası
            "'{\"min_grade\":80,\"max_page_kb\":500}' | '{\"max_page_kb\":500,\"min_grade\":80}'",
            // aralık dışı / kesirli / metin / mantıksal → o sınır tanımsız, diğeri kalır
            "'{\"max_page_kb\":49,\"min_grade\":80}' | '{\"min_grade\":80}'",
            "'{\"max_page_kb\":51201}' | NULL",
            "'{\"max_requests\":12.5}' | NULL",
            "'{\"max_requests\":12.0}' | '{\"max_requests\":12}'",
            "'{\"max_requests\":\"12\"}' | NULL",
            "'{\"min_grade\":true}' | NULL",
            "'{\"min_grade\":-1}' | NULL",
            "'{\"min_grade\":0}' | '{\"min_grade\":0}'",
            "'{\"min_security_score\":100}' | '{\"min_security_score\":100}'",
            // bilinmeyen anahtar yok sayılır
            "'{\"max_cookies\":3,\"max_load_ms\":3000}' | '{\"max_load_ms\":3000}'",
            "'{\"max_load_ms\":1e20}' | NULL",
    })
    void lenientParse(String stored, String expected) {
        ResourceBudget b = ResourceBudget.parse(stored);
        assertThat(b.toJson()).isEqualTo(expected);
        assertThat(b.defined()).isEqualTo(expected != null);
    }

    @Test
    @DisplayName("çok uzun saklı metin ayrıştırılmaz (boş bütçe)")
    void tooLongStoredIgnored() {
        String pad = "{\"max_page_kb\":500,\"x\":\"" + "a".repeat(ResourceBudget.MAX_STORED_CHARS) + "\"}";
        assertThat(ResourceBudget.parse(pad).defined()).isFalse();
    }

    @Test
    @DisplayName("toMap yedi anahtarı her zaman taşır (tanımsız = null)")
    void toMapCarriesAllKeys() {
        Map<String, Object> m = ResourceBudget.parse("{\"min_grade\":70}").toMap();
        assertThat(m).containsOnlyKeys("max_page_kb", "max_requests", "max_js_kb", "max_image_kb", "max_load_ms",
                "min_grade", "min_security_score");
        assertThat(m.get("min_grade")).isEqualTo(70);
        assertThat(m.get("max_page_kb")).isNull();
    }

    @Test
    @DisplayName("withChanges: yalnız gönderilen anahtar değişir; null sınırı kaldırır; bilinmeyen anahtar yok sayılır")
    void withChangesOnlySentKeys() {
        ResourceBudget b = ResourceBudget.parse("{\"max_page_kb\":500,\"min_grade\":80}");
        Map<String, Object> body = new HashMap<>();
        body.put("max_requests", 120);
        body.put("min_grade", null);
        body.put("unknown_key", "x");
        ResourceBudget after = b.withChanges(body);
        assertThat(after.toJson()).isEqualTo("{\"max_page_kb\":500,\"max_requests\":120}");
        // girdi değişmez (değer nesnesi)
        assertThat(b.toJson()).isEqualTo("{\"max_page_kb\":500,\"min_grade\":80}");
        // boş gövde → aynı bütçe
        assertThat(b.withChanges(Map.of())).isEqualTo(b);
        assertThat(b.withChanges(null)).isEqualTo(b);
        // hepsi kaldırılırsa EMPTY (kolon NULL)
        Map<String, Object> clear = new HashMap<>();
        clear.put("max_page_kb", null);
        clear.put("min_grade", null);
        assertThat(b.withChanges(clear).toJson()).isNull();
        assertThat(b.withChanges(clear).defined()).isFalse();
    }

    /** Katı doğrulama doğruluk tablosu: alan + değer → kabul (true) / alan hatası (false). */
    @ParameterizedTest(name = "[{index}] {0}={1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "max_page_kb | 50 | true", "max_page_kb | 51200 | true", "max_page_kb | 49 | false", "max_page_kb | 51201 | false",
            "max_requests | 5 | true", "max_requests | 2000 | true", "max_requests | 4 | false", "max_requests | 2001 | false",
            "max_js_kb | 10 | true", "max_js_kb | 20480 | true", "max_js_kb | 9 | false", "max_js_kb | 20481 | false",
            "max_image_kb | 10 | true", "max_image_kb | 51200 | true", "max_image_kb | 51201 | false",
            "max_load_ms | 200 | true", "max_load_ms | 60000 | true", "max_load_ms | 199 | false", "max_load_ms | 60001 | false",
            "min_grade | 0 | true", "min_grade | 100 | true", "min_grade | -1 | false", "min_grade | 101 | false",
            "min_security_score | 0 | true", "min_security_score | 100 | true", "min_security_score | 101 | false",
    })
    void strictRanges(String key, long value, boolean accepted) {
        Map<String, Object> body = Map.of(key, value);
        if (accepted) {
            assertThat(ResourceBudget.EMPTY.withChanges(body).limit(key)).isEqualTo((int) value);
        } else {
            assertThatThrownBy(() -> ResourceBudget.EMPTY.withChanges(body))
                    .isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsOnlyKeys(key));
        }
    }

    @Test
    @DisplayName("tam sayı olmayan değer (metin, kesirli, mantıksal, liste) alan hatasıdır; .0 kabul")
    void strictTypes() {
        for (Object bad : List.of("500", 500.5, true, List.of(500), Map.of("v", 500), Double.NaN)) {
            assertThatThrownBy(() -> ResourceBudget.EMPTY.withChanges(Map.of("max_page_kb", bad)))
                    .as("değer %s", bad)
                    .isInstanceOf(FieldValidationException.class);
        }
        assertThat(ResourceBudget.EMPTY.withChanges(Map.of("max_page_kb", 500.0)).limit("max_page_kb")).isEqualTo(500);
    }

    @Test
    @DisplayName("ilk geçersiz alan (alan sırasıyla) bildirilir; geçerli alanlar da yazılmaz")
    void firstInvalidFieldReported() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("min_grade", 500);
        body.put("max_page_kb", 1);
        assertThatThrownBy(() -> ResourceBudget.EMPTY.withChanges(body))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsOnlyKeys("max_page_kb"));
    }

    @Test
    @DisplayName("eşitlik değer üzerinden (sıra ve metin biçimi önemsiz)")
    void equality() {
        assertThat(ResourceBudget.parse("{\"min_grade\":80,\"max_page_kb\":500}"))
                .isEqualTo(ResourceBudget.parse("{\"max_page_kb\":500.0,\"min_grade\":80}"))
                .hasSameHashCodeAs(ResourceBudget.parse("{\"max_page_kb\":500,\"min_grade\":80}"));
        assertThat(ResourceBudget.parse(null)).isEqualTo(ResourceBudget.EMPTY);
        assertThat(ResourceBudget.field("max_js_kb").column()).isEqualTo("js_bytes");
        assertThat(ResourceBudget.field("nope")).isNull();
    }
}
