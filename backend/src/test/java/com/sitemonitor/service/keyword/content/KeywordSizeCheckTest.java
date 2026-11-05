package com.sitemonitor.service.keyword.content;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sayfa boyutu eşiği (kw sözleşmesi §7.1, D1). */
class KeywordSizeCheckTest {

    /** min, max, bayt, kırpıldı, istek tamam, beklenen size_ok (null = değerlendirilmedi), neden. */
    static final Object[][] TABLE = {
            {null, null, 500L, false, true, null, null},                       // kapalı
            {100, null, 500L, false, true, true, null},
            {1000, null, 500L, false, true, false, "BODY_TOO_SMALL"},
            {null, 400, 500L, false, true, false, "BODY_TOO_LARGE"},
            {null, 600, 500L, false, true, true, null},
            {null, 2_000_000, 2_000_000L, true, true, false, "BODY_TOO_LARGE"}, // kırpılan gövde > max demektir
            {100, 600, 500L, false, true, true, null},
            {1000, 2000, 500L, false, false, null, null},                     // istek tamamlanmadı → değerlendirilmez
            {500, 500, 500L, false, true, true, null},                        // sınır dahil
    };

    @Test
    @DisplayName("Doğruluk tablosu: alt / üst sınır, kırpılmış gövde = çok büyük, istek tamamlanmadı → null")
    void table() {
        for (Object[] r : TABLE) {
            var res = KeywordSizeCheck.evaluate((Integer) r[0], (Integer) r[1], (Long) r[2], (Boolean) r[3], (Boolean) r[4]);
            String label = java.util.Arrays.toString(r);
            assertThat(res.sizeOk()).as(label).isEqualTo(r[5]);
            assertThat(res.reason()).as(label).isEqualTo(r[6]);
            if (r[6] != null) assertThat(res.detail()).as(label).isNotBlank();
        }
    }

    @Test
    @DisplayName("Doğrulama: 1 – 2 000 000 tam sayı, boş → kapalı, min ≤ max (400 maxBytes)")
    void validate() {
        assertThat(KeywordSizeCheck.validate(null, "")).isEqualTo(new KeywordSizeCheck.Limits(null, null));
        assertThat(KeywordSizeCheck.validate(100, "2000")).isEqualTo(new KeywordSizeCheck.Limits(100, 2000));
        assertThatThrownBy(() -> KeywordSizeCheck.validate(0, null)).isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("minBytes"));
        assertThatThrownBy(() -> KeywordSizeCheck.validate(null, 2_000_001)).isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("maxBytes"));
        assertThatThrownBy(() -> KeywordSizeCheck.validate(500, 100)).isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("maxBytes"));
        assertThatThrownBy(() -> KeywordSizeCheck.validate(1.5, null)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> KeywordSizeCheck.validate("abc", null)).isInstanceOf(FieldValidationException.class);
    }
}
