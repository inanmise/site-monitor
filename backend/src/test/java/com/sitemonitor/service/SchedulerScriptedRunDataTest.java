package com.sitemonitor.service;

import com.sitemonitor.service.failure.CheckFailureReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Senaryo koşum verisinin kaydı (2026-10-31, k6 inceleme D1): neden kodu kolona (VARCHAR(40)) yalnız katalogdaki bir kod
 * olarak yazılır. Kaydın tamamı {@code ClassicSweepGoldenTest} scripted altınında (izleme 3 "Ödeme akışı").
 */
class SchedulerScriptedRunDataTest {

    @Test
    void fitFailureReason_onlyCatalogueCodes() {
        for (CheckFailureReason r : new CheckFailureReason[]{CheckFailureReason.SCRIPT_CHECK_FAILED,
                CheckFailureReason.SCRIPT_THRESHOLD_FAILED, CheckFailureReason.SCRIPT_EXCEPTION, CheckFailureReason.SCRIPT_ABORTED,
                CheckFailureReason.SCRIPT_NO_CHECKS, CheckFailureReason.SCRIPT_PROCESS_TIMEOUT, CheckFailureReason.SCRIPT_INTERRUPTED,
                CheckFailureReason.SCRIPT_FILE_READ_BLOCKED, CheckFailureReason.K6_UNAVAILABLE, CheckFailureReason.READ_TIMEOUT}) {
            assertThat(SchedulerService.fitFailureReason(r.name())).isEqualTo(r.name());
            assertThat(r.name().length()).isLessThanOrEqualTo(40);
        }
        assertThat(SchedulerService.fitFailureReason("BILINMEYEN_KOD")).isNull();
        assertThat(SchedulerService.fitFailureReason("X".repeat(41))).isNull();
        assertThat(SchedulerService.fitFailureReason(null)).isNull();
        assertThat(SchedulerService.fitFailureReason(" ")).isNull();
    }

    @Test
    void scriptPhaseCodes() {
        assertThat(CheckFailureReason.SCRIPT_CHECK_FAILED.phase).isEqualTo(CheckFailureReason.Phase.SCRIPT);
        assertThat(CheckFailureReason.SCRIPT_FILE_READ_BLOCKED.phase).isEqualTo(CheckFailureReason.Phase.POLICY);
        assertThat(CheckFailureReason.K6_UNAVAILABLE.phase).isEqualTo(CheckFailureReason.Phase.POLICY);
    }
}
