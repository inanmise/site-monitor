package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** k6 Go çalışma zamanı tavanları — GOMEMLIMIT / GOMAXPROCS doğrulaması (2026-10-31, k6 inceleme A5). */
class ScriptedResourceLimitsTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "off", "OFF", "256MiB", "1GiB", "1024KiB", "512B", "2TiB", " 256MiB "})
    void memLimitAccepted(String v) {
        assertThat(ScriptedResourceLimits.validMemLimit(v)).isTrue();
        assertThatCode(() -> ScriptedResourceLimits.validate(ScriptedResourceLimits.KEY_MEM_LIMIT, v)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"512MB", "1G", "256 MiB", "256mib", "-1MiB", "1.5GiB", "abc", "1234567890123456MiB"})
    void memLimitRejectedWithFieldError(String v) {
        assertThat(ScriptedResourceLimits.validMemLimit(v)).isFalse();
        assertThatThrownBy(() -> ScriptedResourceLimits.validate(ScriptedResourceLimits.KEY_MEM_LIMIT, v))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey(ScriptedResourceLimits.FIELD_MEM_LIMIT));
    }

    @Test
    void maxProcs() {
        assertThat(ScriptedResourceLimits.validMaxProcs("1")).isTrue();
        assertThat(ScriptedResourceLimits.validMaxProcs("0")).isTrue();
        assertThat(ScriptedResourceLimits.validMaxProcs("")).isTrue();
        assertThat(ScriptedResourceLimits.validMaxProcs(null)).isTrue();
        assertThat(ScriptedResourceLimits.validMaxProcs("-1")).isFalse();
        assertThat(ScriptedResourceLimits.validMaxProcs("2.5")).isFalse();
        assertThat(ScriptedResourceLimits.validMaxProcs("1234567")).isFalse();
        assertThatThrownBy(() -> ScriptedResourceLimits.validate(ScriptedResourceLimits.KEY_MAX_PROCS, "iki"))
                .isInstanceOf(FieldValidationException.class);
        // başka anahtar: dokunulmaz
        assertThatCode(() -> ScriptedResourceLimits.validate("site.monitor.scripted.pool-size", "512MB")).doesNotThrowAnyException();
        assertThat(ScriptedResourceLimits.isKey(ScriptedResourceLimits.KEY_MAX_PROCS)).isTrue();
        assertThat(ScriptedResourceLimits.isKey("x")).isFalse();
    }

    @Test
    void effectiveValues_brokenStoredValueNotPutIntoEnv() {
        assertThat(ScriptedResourceLimits.effectiveMemLimit("256MiB")).isEqualTo("256MiB");
        assertThat(ScriptedResourceLimits.effectiveMemLimit(" 1GiB ")).isEqualTo("1GiB");
        assertThat(ScriptedResourceLimits.effectiveMemLimit("512MB")).isNull();      // eskiden k6'yı açılışta düşürürdü
        assertThat(ScriptedResourceLimits.effectiveMemLimit("0")).isNull();
        assertThat(ScriptedResourceLimits.effectiveMemLimit("off")).isNull();
        assertThat(ScriptedResourceLimits.effectiveMemLimit(null)).isNull();
        assertThat(ScriptedResourceLimits.effectiveMaxProcs("2")).isEqualTo("2");
        assertThat(ScriptedResourceLimits.effectiveMaxProcs("0")).isNull();
        assertThat(ScriptedResourceLimits.effectiveMaxProcs("x")).isNull();
        assertThat(ScriptedResourceLimits.effectiveMaxProcs("x".repeat(100))).isNull();
    }
}
