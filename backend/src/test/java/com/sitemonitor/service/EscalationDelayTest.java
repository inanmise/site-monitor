package com.sitemonitor.service;

import com.sitemonitor.model.EscalationContact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Zamana bağlı eskalasyon adımının saf kuralları (2026-10-01): gecikme ayrıştırma ve anlık liste süzgeci. */
class EscalationDelayTest {

    private static EscalationContact c(long id, Integer delay) {
        EscalationContact c = new EscalationContact();
        c.setId(id);
        c.setDelayMinutes(delay);
        return c;
    }

    @Test
    @DisplayName("Ayrıştırma: boş / null / 0 → anlık (null); 1–1440 tam sayı → dakika; diğer her şey 400")
    void parse_rules() {
        assertThat(EscalationDelay.parse(null)).isNull();
        assertThat(EscalationDelay.parse("")).isNull();
        assertThat(EscalationDelay.parse("  ")).isNull();
        assertThat(EscalationDelay.parse(0)).isNull();
        assertThat(EscalationDelay.parse("0")).isNull();
        assertThat(EscalationDelay.parse(1)).isEqualTo(1);
        assertThat(EscalationDelay.parse(30)).isEqualTo(30);
        assertThat(EscalationDelay.parse(" 45 ")).isEqualTo(45);
        assertThat(EscalationDelay.parse(1440)).isEqualTo(1440);
        assertThat(EscalationDelay.parse(60.0)).isEqualTo(60);
        for (Object bad : new Object[]{1441, -1, "-5", "abc", 2.5, "10dk"}) {
            assertThatThrownBy(() -> EscalationDelay.parse(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Süzgeç: gecikmeli kişi yoksa AYNI liste; varsa yalnız döngüdeki gecikmeliler kalır, sıra korunur")
    void filter_rules() {
        List<EscalationContact> none = List.of(c(1, null), c(2, 0));
        assertThat(EscalationDelay.anyDelayed(none)).isFalse();
        assertThat(EscalationDelay.immediateOnly(none)).isSameAs(none);
        assertThat(EscalationDelay.withoutPending(none, Set.of(1L))).isSameAs(none);

        EscalationContact a = c(1, null), d1 = c(2, 30), b = c(3, 0), d2 = c(4, 1440);
        List<EscalationContact> mixed = List.of(a, d1, b, d2);
        assertThat(EscalationDelay.immediateOnly(mixed)).containsExactly(a, b);
        assertThat(EscalationDelay.withoutPending(mixed, Set.of(4L))).containsExactly(a, b, d2);
        assertThat(EscalationDelay.withoutPending(mixed, null)).containsExactly(a, b);
        assertThat(EscalationDelay.isDelayed(null)).isFalse();
    }
}
