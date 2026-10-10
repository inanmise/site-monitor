package com.sitemonitor.service.crypto;

import com.sitemonitor.service.crypto.CryptoClassifier.Category;
import com.sitemonitor.service.crypto.PqcMigrationPriority.Input;
import com.sitemonitor.service.crypto.PqcMigrationPriority.Score;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PQC geçiş önceliği (2026-10-10) — puan doğruluk tablosu, bant eşikleri ve arayüze giden kural tablosunun sabitlerle
 * aynı olduğu. Puanı değiştiren bir kural güncellemesi bu tabloyu bilerek günceller.
 */
class PqcMigrationPriorityTest {

    @ParameterizedTest(name = "T{0} {1} {2}g network={3} internal={4} pfs={5} → {6} {7}")
    @CsvSource(nullValues = "null", value = {
            // tier, category, days, network, internal, pfs, score, band
            "1, BROKEN, 10, true, null, false, 100, P1",     // 40 + 30 + 20 + 5 + 5
            "1, LEGACY, 20, true, null, true, 85, P1",       // 40 + 20 + 20 + 5
            "1, MODERN, 400, true, false, true, 55, P2",     // 40 + 10 + 0 + 5
            "1, MODERN, 400, true, true, true, 50, P2",      // iç sertifika → hndl 0
            "2, LEGACY, 120, true, null, null, 60, P2",      // 25 + 20 + 10 + 5
            "3, LEGACY, 60, true, true, true, 45, P3",       // 10 + 20 + 15
            "null, BROKEN, 100, true, null, true, 60, P2",   // 15 + 30 + 10 + 5
            "4, MODERN, 400, true, null, true, 20, P4",      // 5 + 10 + 0 + 5
            "4, MODERN, null, false, null, null, 15, P4",    // manuel: hndl yok
            "1, UNKNOWN, null, true, null, null, 60, P2",    // 40 + 15 + 0 + 5
            "2, LEGACY, -5, false, null, null, 65, P2",      // dolmuş: yenileme 20 — 25 + 20 + 20
            "1, PQC_READY, 5, true, null, false, 0, DONE",
    })
    void score(Integer tier, Category category, Integer days, boolean network, Boolean internal, Boolean pfs,
               int expectedScore, String expectedBand) {
        Score s = PqcMigrationPriority.score(new Input(tier, category, days, network, internal, pfs));
        assertThat(s.score()).isEqualTo(expectedScore);
        assertThat(s.band()).isEqualTo(expectedBand);
        assertThat(s.exposure() + s.strength() + s.renewal() + s.hndl()).isEqualTo(expectedScore);
    }

    @ParameterizedTest(name = "{0} gün → {1}")
    @CsvSource(nullValues = "null", value = {"-30, 20", "0, 20", "30, 20", "31, 15", "90, 15", "91, 10", "180, 10",
            "181, 5", "365, 5", "366, 0", "null, 0"})
    void renewalWindow(Integer days, int points) {
        assertThat(PqcMigrationPriority.renewal(days)).isEqualTo(points);
    }

    @ParameterizedTest(name = "katman {0} → {1}")
    @CsvSource(nullValues = "null", value = {"1, 40", "2, 25", "3, 10", "4, 5", "null, 15", "9, 15"})
    void exposure(Integer tier, int points) {
        assertThat(PqcMigrationPriority.exposure(tier)).isEqualTo(points);
    }

    @Test
    @DisplayName("bant eşikleri: 70 P1, 50 P2, 30 P3, altı P4")
    void bands() {
        assertThat(PqcMigrationPriority.band(100)).isEqualTo("P1");
        assertThat(PqcMigrationPriority.band(70)).isEqualTo("P1");
        assertThat(PqcMigrationPriority.band(69)).isEqualTo("P2");
        assertThat(PqcMigrationPriority.band(50)).isEqualTo("P2");
        assertThat(PqcMigrationPriority.band(49)).isEqualTo("P3");
        assertThat(PqcMigrationPriority.band(30)).isEqualTo("P3");
        assertThat(PqcMigrationPriority.band(29)).isEqualTo("P4");
        assertThat(PqcMigrationPriority.band(0)).isEqualTo("P4");
    }

    @Test
    @DisplayName("en yüksek olası puan 100 (bileşen tavanları toplamı)")
    void maxIs100() {
        int max = PqcMigrationPriority.TIER1 + PqcMigrationPriority.STRENGTH_BROKEN
                + PqcMigrationPriority.RENEWAL_STEPS[0][1] + PqcMigrationPriority.HNDL_EXTERNAL + PqcMigrationPriority.HNDL_NO_PFS;
        assertThat(max).isEqualTo(100);
    }

    @Test
    @DisplayName("arayüzün kural tablosu sabitlerden üretilir (ikinci kopya yok)")
    @SuppressWarnings("unchecked")
    void ruleTableMirrorsConstants() {
        Map<String, Object> r = PqcMigrationPriority.ruleTable();
        assertThat((Map<String, Object>) r.get("exposure")).containsEntry("1", 40).containsEntry("2", 25)
                .containsEntry("3", 10).containsEntry("4", 5).containsEntry("none", 15);
        assertThat((Map<String, Object>) r.get("strength")).containsEntry("BROKEN", 30).containsEntry("LEGACY", 20)
                .containsEntry("UNKNOWN", 15).containsEntry("MODERN", 10).containsEntry("PQC_READY", 0);
        List<Map<String, Object>> renewal = (List<Map<String, Object>>) r.get("renewal");
        assertThat(renewal).hasSize(PqcMigrationPriority.RENEWAL_STEPS.length);
        for (int i = 0; i < renewal.size(); i++) {
            assertThat(renewal.get(i)).containsEntry("max_days", PqcMigrationPriority.RENEWAL_STEPS[i][0])
                    .containsEntry("points", PqcMigrationPriority.RENEWAL_STEPS[i][1]);
        }
        assertThat((Map<String, Object>) r.get("hndl")).containsEntry("external", 5).containsEntry("no_pfs", 5);
        assertThat(((List<Map<String, Object>>) r.get("bands")).stream().map(b -> b.get("band")).toList())
                .containsExactly("P1", "P2", "P3", "P4");
        assertThat(r.get("max")).isEqualTo(100);
    }
}
