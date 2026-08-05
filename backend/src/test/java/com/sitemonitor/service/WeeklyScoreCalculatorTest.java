package com.sitemonitor.service;

import com.sitemonitor.service.WeeklyScoreCalculator.ScoreInputs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Haftalık sağlık skoru — ağırlık uygulaması, kırpma, bant eşikleri, delta işareti. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyScoreCalculatorTest {

    @Mock AppSettingsService appSettings;
    WeeklyScoreCalculator calc;

    @BeforeEach
    void setUp() {
        // Ağırlıklar varsayılana düşer (getDouble ikinci argümanı): crit 8, exp 2, weak 3, uptime 0.5.
        when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        calc = new WeeklyScoreCalculator(appSettings);
    }

    @Test
    @DisplayName("sıfır girdi → 100 (uptime null → ceza yok)")
    void zeroInputs_100() {
        assertThat(calc.score(new ScoreInputs(0, 0, null, 0))).isEqualTo(100);
    }

    @Test
    @DisplayName("ağırlıklı ceza: 100 − 8·2 − 2·1 − 3·1 − 0.5·(100−90) = 74 (amber)")
    void weightedPenalties() {
        int s = calc.score(new ScoreInputs(2, 1, 90.0, 1));
        assertThat(s).isEqualTo(74);
        assertThat(WeeklyScoreCalculator.band(s)).isEqualTo("amber");
    }

    @Test
    @DisplayName("aşırı ceza → [0,100] kırpılır (0)")
    void clampFloor() {
        assertThat(calc.score(new ScoreInputs(20, 0, null, 0))).isZero();     // 100−160 → 0
    }

    @Test
    @DisplayName("bant eşikleri: 80 yeşil, 79/60 amber, 59 kırmızı")
    void bands() {
        assertThat(WeeklyScoreCalculator.band(100)).isEqualTo("green");
        assertThat(WeeklyScoreCalculator.band(80)).isEqualTo("green");
        assertThat(WeeklyScoreCalculator.band(79)).isEqualTo("amber");
        assertThat(WeeklyScoreCalculator.band(60)).isEqualTo("amber");
        assertThat(WeeklyScoreCalculator.band(59)).isEqualTo("red");
        assertThat(WeeklyScoreCalculator.band(0)).isEqualTo("red");
    }

    @Test
    @DisplayName("tier1 uptime yüksekse uptime cezası küçülür (skor artar)")
    void higherUptime_higherScore() {
        int low  = calc.score(new ScoreInputs(0, 0, 90.0, 0));   // −0.5·10 = 95
        int high = calc.score(new ScoreInputs(0, 0, 99.0, 0));   // −0.5·1  = 100 (yuvarlanır)
        assertThat(high).isGreaterThan(low);
        assertThat(low).isEqualTo(95);
    }
}
