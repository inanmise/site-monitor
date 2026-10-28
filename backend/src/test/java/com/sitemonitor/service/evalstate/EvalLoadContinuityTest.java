package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.evalstate.EvalLoadContinuity.Decision;
import com.sitemonitor.service.evalstate.EvalLoadContinuity.Tier;
import com.sitemonitor.service.evalstate.EvalStateSql.PriorGeneration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-2 A — devirde yükleme süreklilik kararı (tasarım R3-2 §2.4): denetimler sırayla, ilk düşen nedeni verir;
 * sınırlar (5 dk, 30 sn) kapsayıcı; {@code handover_ms ≠ updated_ms} (devirden sonra herhangi bir nabız / CAS) temiz devir
 * değildir; 3-1 CAS'ının bıraktığı bayat işaret sütunları ({@code state_gen ≠ gen}) asla yüklenmez.
 */
class EvalLoadContinuityTest {

    static final String ME = "pod-b-00000002";
    static final String OLD = "pod-a-00000001";
    static final long NOW = 10_000_000L;
    static final long MIN5 = 5L * 60 * 1000;

    /** Temiz devir: gen 7, işaret 7, kalıcı an = devir anı = nabız anı = NOW − 10 sn. */
    static PriorGeneration clean() {
        long t = NOW - 10_000L;
        return new PriorGeneration(7, OLD, t, 7L, t, t);
    }

    static PriorGeneration with(long gen, String writer, long updatedMs, Long stateGen, Long flushedMs, Long handoverMs) {
        return new PriorGeneration(gen, writer, updatedMs, stateGen, flushedMs, handoverMs);
    }

    static Stream<Arguments> table() {
        long t = NOW - 10_000L;
        return Stream.of(
                // # | açıklama | satır | sütun yok | katman | neden
                Arguments.of("1 sütunlar yok", clean(), true, Tier.NONE, EvalLoadReport.REASON_COLUMNS_MISSING),
                Arguments.of("2 satır yok", null, false, Tier.NONE, EvalLoadReport.REASON_NO_PREVIOUS_GENERATION),
                Arguments.of("2 ilk kuşak (gen 0)", with(0, OLD, t, 0L, t, t), false, Tier.NONE,
                        EvalLoadReport.REASON_NO_PREVIOUS_GENERATION),
                Arguments.of("3 son yazan bu pod", with(7, ME, t, 7L, t, t), false, Tier.NONE,
                        EvalLoadReport.REASON_WRITER_IS_SELF),
                Arguments.of("4 işaretsiz (20.168.x)", with(7, OLD, t, null, null, null), false, Tier.NONE,
                        EvalLoadReport.REASON_PREVIOUS_UNMARKED),
                Arguments.of("4 bayat işaret (3-1 CAS araya girdi)", with(8, OLD, t, 7L, t, t), false, Tier.NONE,
                        EvalLoadReport.REASON_PREVIOUS_UNMARKED),
                Arguments.of("5 eksik kuşak", with(7, OLD, t, 7L, null, null), false, Tier.NONE,
                        EvalLoadReport.REASON_PREVIOUS_INCOMPLETE),
                Arguments.of("6 kalıcı an gelecekte > 30 sn", with(7, OLD, NOW + 30_001, 7L, NOW + 30_001, NOW + 30_001),
                        false, Tier.NONE, EvalLoadReport.REASON_CLOCK_SKEW),
                Arguments.of("6 sınır: tam 30 sn gelecekte → geçer", with(7, OLD, NOW + 30_000, 7L, NOW + 30_000, NOW + 30_000),
                        false, Tier.FULL, null),
                Arguments.of("7 bayat > 5 dk", with(7, OLD, NOW - MIN5 - 1, 7L, NOW - MIN5 - 1, null), false, Tier.NONE,
                        EvalLoadReport.REASON_PREVIOUS_STALE),
                Arguments.of("7 sınır: tam 5 dk → gözlemler", with(7, OLD, NOW - MIN5, 7L, NOW - MIN5, null), false,
                        Tier.OBSERVATIONS, EvalLoadReport.REASON_NO_HANDOVER),
                Arguments.of("8 devir yok", with(7, OLD, t, 7L, t, null), false, Tier.OBSERVATIONS,
                        EvalLoadReport.REASON_NO_HANDOVER),
                Arguments.of("8 devirden sonra nabız (handover ≠ updated)", with(7, OLD, t + 1, 7L, t, t), false,
                        Tier.OBSERVATIONS, EvalLoadReport.REASON_NO_HANDOVER),
                Arguments.of("9 devir > 5 dk (kalıcı an taze)", with(7, OLD, NOW - MIN5 - 1, 7L, t, NOW - MIN5 - 1), false,
                        Tier.OBSERVATIONS, EvalLoadReport.REASON_HANDOVER_TOO_OLD),
                Arguments.of("9 sınır: devir tam 5 dk → tam", with(7, OLD, NOW - MIN5, 7L, NOW - MIN5, NOW - MIN5), false,
                        Tier.FULL, null),
                Arguments.of("9 devir gelecekte > 30 sn (kalıcı an şimdi)", with(7, OLD, NOW + 30_001, 7L, NOW, NOW + 30_001),
                        false, Tier.OBSERVATIONS, EvalLoadReport.REASON_CLOCK_SKEW),
                Arguments.of("→ tam aday", clean(), false, Tier.FULL, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    @DisplayName("§2.4 doğruluk tablosu")
    void truthTable(String label, PriorGeneration prior, boolean columnsMissing, Tier tier, String reason) {
        Decision d = EvalLoadContinuity.decide(prior, ME, NOW, columnsMissing);
        assertThat(d.tier()).as(label).isEqualTo(tier);
        assertThat(d.reason()).as(label).isEqualTo(reason);
        assertThat(d.prior()).isSameAs(prior);
    }

    @Test
    @DisplayName("Denetim sırası: sütun yokluğu her şeyden önce, sonra kuşak, yazan, işaret, eksiksizlik, saat, yaş")
    void orderOfChecks() {
        // Hem yazan = ben hem işaretsiz → ilk düşen (3) kazanır
        assertThat(EvalLoadContinuity.decide(with(7, ME, 1, null, null, null), ME, NOW, false).reason())
                .isEqualTo(EvalLoadReport.REASON_WRITER_IS_SELF);
        // İşaretsiz + eksik → 4
        assertThat(EvalLoadContinuity.decide(with(7, OLD, 1, 6L, null, null), ME, NOW, false).reason())
                .isEqualTo(EvalLoadReport.REASON_PREVIOUS_UNMARKED);
        // Sütun yok → satır hiç okunmamış olabilir (null) — yine sütun nedeni
        assertThat(EvalLoadContinuity.decide(null, ME, NOW, true).reason()).isEqualTo(EvalLoadReport.REASON_COLUMNS_MISSING);
    }

    @Test
    @DisplayName("Yaşlar halefin saatiyle: kalıcı yaş ve devir yaşı ≥ 0; NONE raporu kaynak + yaşları taşır, deneme katmanı yok")
    void agesAndReports() {
        Decision full = EvalLoadContinuity.decide(clean(), ME, NOW, false);
        assertThat(full.durableAgeMs()).isEqualTo(10_000L);
        assertThat(full.handoverAgeMs()).isEqualTo(10_000L);
        assertThat(full.loadTier()).isEqualTo(EvalLoad.TIER_FULL);
        assertThat(full.intermediateSinceMs()).isEqualTo(NOW - 10_000L - EvalLoadContinuity.INTERMEDIATE_SLACK_MS);

        Decision obs = EvalLoadContinuity.decide(with(7, OLD, NOW - 20_000, 7L, NOW - 20_000, null), ME, NOW, false);
        assertThat(obs.handoverAgeMs()).as("devir yok → yaş yok").isNull();
        assertThat(obs.loadTier()).isEqualTo(EvalLoad.TIER_OBSERVATIONS);
        assertThat(obs.intermediateSinceMs()).isEqualTo(Long.MIN_VALUE);

        Decision skew = EvalLoadContinuity.decide(with(7, OLD, NOW + 31_000, 7L, NOW + 31_000, NOW + 31_000), ME, NOW, false);
        assertThat(skew.durableAgeMs()).as("gelecekteki an → 0").isZero();

        Decision stale = EvalLoadContinuity.decide(with(9, OLD, NOW - MIN5 - 1, 9L, NOW - MIN5 - 1, null), ME, NOW, false);
        EvalLoadReport r = stale.noneReport(NOW, ME);
        assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
        assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_PREVIOUS_STALE);
        assertThat(r.attemptedTier()).isNull();
        assertThat(r.fromGeneration()).isEqualTo(9L);
        assertThat(r.fromPod()).isEqualTo(OLD);
        assertThat(r.durableAgeMs()).isEqualTo(MIN5 + 1);
        assertThat(r.pod()).isEqualTo(ME);

        EvalLoadReport first = EvalLoadContinuity.decide(null, ME, NOW, false).noneReport(NOW, ME);
        assertThat(first.fromGeneration()).isNull();
        assertThat(first.fromPod()).isNull();
    }

    @Test
    @DisplayName("Denetim 10: ara lider sayısı > 0 → tam aday gözlemlere iner (intermediate_leader); 0 → aynı karar")
    void intermediateLeaderDowngrades() {
        Decision full = EvalLoadContinuity.decide(clean(), ME, NOW, false);
        assertThat(EvalLoadContinuity.afterIntermediate(full, 0)).isSameAs(full);
        Decision down = EvalLoadContinuity.afterIntermediate(full, 3);
        assertThat(down.tier()).isEqualTo(Tier.OBSERVATIONS);
        assertThat(down.reason()).isEqualTo(EvalLoadReport.REASON_INTERMEDIATE_LEADER);
        assertThat(down.handoverAgeMs()).isEqualTo(full.handoverAgeMs());
        Decision obs = EvalLoadContinuity.decide(with(7, OLD, NOW - 1, 7L, NOW - 1, null), ME, NOW, false);
        assertThat(EvalLoadContinuity.afterIntermediate(obs, 5)).as("yalnız tam aday iner").isSameAs(obs);
        assertThat(EvalLoadContinuity.afterIntermediate(null, 5)).isNull();
    }

    @Test
    @DisplayName("Her neden kodu EvalLoadReport.REASONS'ta; gözlem nedenleri OBSERVATIONS_REASONS'ta")
    void reasonsArePermanentCodes() {
        for (Arguments a : table().toList()) {
            Object[] v = a.get();
            String reason = (String) v[4];
            if (reason == null) continue;
            assertThat(EvalLoadReport.REASONS).contains(reason);
            if (v[3] == Tier.OBSERVATIONS) assertThat(EvalLoadReport.OBSERVATIONS_REASONS).contains(reason);
        }
        assertThat(EvalLoadReport.OBSERVATIONS_REASONS).contains(EvalLoadReport.REASON_INTERMEDIATE_LEADER);
    }
}
