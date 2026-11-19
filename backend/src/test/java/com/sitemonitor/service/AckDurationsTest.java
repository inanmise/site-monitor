package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Süreli onay süre listesi ve istek değerinin çözümü (2026-11-19). Ön yüz aynı listeyi okur (ackDurationModel.test.js). */
class AckDurationsTest {

    @Test
    @DisplayName("liste: 1 sa, 4 sa, 8 sa, 1 gün, 3 gün — sıra = arayüz sırası")
    void allowedList() {
        assertThat(AckDurations.ALLOWED_MINUTES).containsExactly(60, 240, 480, 1440, 4320);
        assertThat(AckDurations.FIELD).isEqualTo("duration_minutes");
    }

    @Test
    @DisplayName("boş / null / 0 → süresiz (null)")
    void emptyIsUnlimited() {
        assertThat(AckDurations.parse(null)).isNull();
        assertThat(AckDurations.parse("")).isNull();
        assertThat(AckDurations.parse("  ")).isNull();
        assertThat(AckDurations.parse(0)).isNull();
        assertThat(AckDurations.parse("0")).isNull();
    }

    @Test
    @DisplayName("listedeki değer sayı ya da metin olarak kabul edilir")
    void allowedValues() {
        for (int m : AckDurations.ALLOWED_MINUTES) {
            assertThat(AckDurations.parse(m)).isEqualTo(m);
            assertThat(AckDurations.parse((long) m)).isEqualTo(m);
            assertThat(AckDurations.parse(String.valueOf(m))).isEqualTo(m);
            assertThat(AckDurations.parse(" " + m + " ")).isEqualTo(m);
            assertThat(AckDurations.parse((double) m)).isEqualTo(m);
        }
    }

    @Test
    @DisplayName("listede olmayan, kesirli, negatif, çok büyük ya da sayı olmayan değer → 400 alanı duration_minutes")
    void invalidValues() {
        for (Object bad : new Object[] { 30, 61, -60, 60.5, "abc", "60m", "1e3", Long.MAX_VALUE, "9999999", true,
                java.util.List.of(60) }) {
            assertThatThrownBy(() -> AckDurations.parse(bad))
                    .as("değer %s", bad)
                    .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                    .satisfies(e -> assertThat(((GlobalExceptionHandler.FieldValidationException) e).getFields())
                            .containsKey(AckDurations.FIELD));
        }
    }
}
