package com.certmonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordCheckerServiceTest {

    @Test
    @DisplayName("evaluate: ≥/≤/=/>/< operatörleri doğru değerlendirir")
    void evaluateOperators() {
        assertThat(KeywordCheckerService.evaluate(3, "GTE", 3)).isTrue();
        assertThat(KeywordCheckerService.evaluate(2, "GTE", 3)).isFalse();
        assertThat(KeywordCheckerService.evaluate(2, "LTE", 2)).isTrue();
        assertThat(KeywordCheckerService.evaluate(3, "LTE", 2)).isFalse();
        assertThat(KeywordCheckerService.evaluate(3, "EQ", 3)).isTrue();
        assertThat(KeywordCheckerService.evaluate(2, "EQ", 3)).isFalse();
        assertThat(KeywordCheckerService.evaluate(4, "GT", 3)).isTrue();
        assertThat(KeywordCheckerService.evaluate(3, "GT", 3)).isFalse();
        assertThat(KeywordCheckerService.evaluate(2, "LT", 3)).isTrue();
        assertThat(KeywordCheckerService.evaluate(3, "LT", 3)).isFalse();
        // "bulunmamalı" = LTE 0
        assertThat(KeywordCheckerService.evaluate(0, "LTE", 0)).isTrue();
        assertThat(KeywordCheckerService.evaluate(1, "LTE", 0)).isFalse();
        // null operatör → GTE varsayımı
        assertThat(KeywordCheckerService.evaluate(1, null, 1)).isTrue();
    }

    @Test
    @DisplayName("opPhrase: operatör + eşik → Türkçe ifade")
    void opPhrase() {
        assertThat(KeywordCheckerService.opPhrase("GTE", 3)).isEqualTo("en az 3 kez");
        assertThat(KeywordCheckerService.opPhrase("LTE", 2)).isEqualTo("en fazla 2 kez");
        assertThat(KeywordCheckerService.opPhrase("EQ", 1)).isEqualTo("tam olarak 1 kez");
        assertThat(KeywordCheckerService.opPhrase("GT", 5)).isEqualTo("5 kezden fazla");
        assertThat(KeywordCheckerService.opPhrase("LT", 4)).isEqualTo("4 kezden az");
    }
}
