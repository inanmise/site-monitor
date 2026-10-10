package com.sitemonitor.service.quality;

import com.sitemonitor.service.quality.DataQualityScore.Band;
import com.sitemonitor.service.quality.DataQualityScore.Count;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Puan formülü doğruluk tablosu — {@link DataQualityScore} belgesindeki formülün birebir karşılığı. */
class DataQualityScoreTest {

    private static Map<DataQualityRule, Count> counts(Object... kv) {
        Map<DataQualityRule, Count> m = new EnumMap<>(DataQualityRule.class);
        for (int i = 0; i < kv.length; i += 3) {
            m.put((DataQualityRule) kv[i], new Count((Integer) kv[i + 1], (Integer) kv[i + 2]));
        }
        return m;
    }

    @Test
    @DisplayName("hiç kural uygulanmıyor → puan yok (null), bant NO_DATA — boş takım 100 görünmez")
    void noApplicableRule() {
        assertThat(DataQualityScore.score(Map.of())).isNull();
        assertThat(DataQualityScore.score(counts(DataQualityRule.INV_NO_TIER, 0, 0))).isNull();
        assertThat(DataQualityScore.band(null)).isEqualTo(Band.NO_DATA);
    }

    @Test
    @DisplayName("kusursuz kova → 100; tamamı kusurlu tek kural → 0")
    void extremes() {
        assertThat(DataQualityScore.score(counts(DataQualityRule.INV_NO_TIER, 5, 0, DataQualityRule.TEAM_NO_MEMBERS, 1, 0)))
                .isEqualTo(100);
        assertThat(DataQualityScore.score(counts(DataQualityRule.TEAM_NO_MEMBERS, 1, 1))).isEqualTo(0);
    }

    @Test
    @DisplayName("ağırlıklı ortalama: YÜKSEK (3) %0 sağlıklı + DÜŞÜK (1) %100 sağlıklı → 25")
    void weighted() {
        // 100 × (3·0 + 1·1) / (3 + 1) = 25
        assertThat(DataQualityScore.score(counts(
                DataQualityRule.TEAM_NO_ESCALATION, 1, 1,
                DataQualityRule.MON_NO_GROUP, 4, 0))).isEqualTo(25);
        // 100 × (2·0,5 + 1·1) / 3 = 66,67 → 67
        assertThat(DataQualityScore.score(counts(
                DataQualityRule.INV_NO_TIER, 10, 5,
                DataQualityRule.MON_NO_GROUP, 2, 0))).isEqualTo(67);
    }

    @Test
    @DisplayName("büyüklükten bağımsız: aynı ORAN aynı puan (10/1000 ile 1/100 eşit)")
    void sizeIndependent() {
        Integer big = DataQualityScore.score(counts(DataQualityRule.INV_NO_TIER, 1000, 10, DataQualityRule.TEAM_NO_MEMBERS, 1, 0));
        Integer small = DataQualityScore.score(counts(DataQualityRule.INV_NO_TIER, 100, 1, DataQualityRule.TEAM_NO_MEMBERS, 1, 0));
        assertThat(big).isEqualTo(small).isEqualTo(100 - 0);   // 100 × (2·0,99 + 3·1)/5 = 99,6 → 100
        Integer big2 = DataQualityScore.score(counts(DataQualityRule.INV_NO_TIER, 1000, 500));
        Integer small2 = DataQualityScore.score(counts(DataQualityRule.INV_NO_TIER, 2, 1));
        assertThat(big2).isEqualTo(small2).isEqualTo(50);
    }

    @Test
    @DisplayName("kaybedilen puanların toplamı 100 − puan; kusursuz / uygulanmayan kural 0 kaybettirir")
    void pointsLostSumToGap() {
        Map<DataQualityRule, Count> c = counts(
                DataQualityRule.TEAM_NO_ESCALATION, 1, 1,      // 3 · 1   → 100·3/6 = 50
                DataQualityRule.INV_NO_TIER, 4, 2,             // 2 · 0,5 → 100·1/6 = 16,7
                DataQualityRule.MON_NO_GROUP, 3, 0,            // 0
                DataQualityRule.MON_DUPLICATE, 0, 0);          // uygulanmaz
        assertThat(DataQualityScore.pointsLost(c, DataQualityRule.TEAM_NO_ESCALATION)).isEqualTo(50.0);
        assertThat(DataQualityScore.pointsLost(c, DataQualityRule.INV_NO_TIER)).isEqualTo(16.7);
        assertThat(DataQualityScore.pointsLost(c, DataQualityRule.MON_NO_GROUP)).isZero();
        assertThat(DataQualityScore.pointsLost(c, DataQualityRule.MON_DUPLICATE)).isZero();
        assertThat(DataQualityScore.pointsLost(c, DataQualityRule.INV_NO_TEAM)).isZero();
        int score = DataQualityScore.score(c);
        assertThat(score).isEqualTo(33);
        assertThat(50.0 + 16.7).isCloseTo(100 - score, org.assertj.core.data.Offset.offset(0.5));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"100,EXCELLENT", "90,EXCELLENT", "89,GOOD", "75,GOOD", "74,NEEDS_ATTENTION", "50,NEEDS_ATTENTION",
            "49,POOR", "0,POOR"})
    @DisplayName("bant sınırları: ≥90 Mükemmel, ≥75 İyi, ≥50 İyileştirilmeli, <50 Zayıf")
    void bands(int score, Band band) {
        assertThat(DataQualityScore.band(score)).isEqualTo(band);
    }

    @Test
    @DisplayName("sayaç geçersiz olamaz (negatif ya da kusurlu > uygun)")
    void countValidation() {
        assertThatThrownBy(() -> new Count(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Count(1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Count(0, 0).applies()).isFalse();
        assertThat(new Count(4, 1).health()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("ağırlık önemden türer (YÜKSEK 3, ORTA 2, DÜŞÜK 1) ve her kural bir kapsama sahip")
    void weightsFromSeverity() {
        for (DataQualityRule r : DataQualityRule.values()) {
            assertThat(r.weight()).isEqualTo(switch (r.severity) { case HIGH -> 3; case MEDIUM -> 2; case LOW -> 1; });
            assertThat(r.scope).isNotNull();
        }
        assertThat(DataQualityRule.INV_NO_TEAM.ownership).isTrue();
        assertThat(DataQualityRule.MON_NO_TEAM.ownership).isTrue();
        assertThat(DataQualityRule.values()).filteredOn(r -> r.ownership).hasSize(2);
    }
}
