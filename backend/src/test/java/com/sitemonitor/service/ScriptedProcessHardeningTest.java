package com.sitemonitor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İşlem sertleştirmesi karar mantığı (2026-10-31, k6 inceleme A2). Gerçek {@code prctl} yalnız Linux imajında koşar —
 * imajdaki etkisini {@code scripts/image-boot-check.sh} adım 9 doğrular; burada taklit {@code prctl} ile karar yolları.
 */
class ScriptedProcessHardeningTest {

    @Test
    void disabled_neverCallsPrctl() {
        AtomicInteger calls = new AtomicInteger();
        ScriptedProcessHardening h = new ScriptedProcessHardening(false, "Linux", (o, a) -> { calls.incrementAndGet(); return 0; });
        assertThat(h.apply()).isEqualTo(ScriptedProcessHardening.Outcome.DISABLED);
        assertThat(calls.get()).isZero();
    }

    @Test
    void notLinux_noop() {
        AtomicInteger calls = new AtomicInteger();
        for (String os : new String[]{"Windows 10", "Mac OS X", "", null}) {
            ScriptedProcessHardening h = new ScriptedProcessHardening(true, os, (o, a) -> { calls.incrementAndGet(); return 0; });
            assertThat(h.apply()).as(String.valueOf(os)).isEqualTo(ScriptedProcessHardening.Outcome.NOT_LINUX);
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void linux_setsNonDumpableThenVerifies_onlyOnce() {
        List<Integer> options = new ArrayList<>();
        int[] dumpable = {1};
        ScriptedProcessHardening h = new ScriptedProcessHardening(true, "Linux", (o, a) -> {
            options.add(o);
            if (o == ScriptedProcessHardening.PR_SET_DUMPABLE) { dumpable[0] = (int) a; return 0; }
            if (o == ScriptedProcessHardening.PR_GET_DUMPABLE) return dumpable[0];
            return -1;
        });
        assertThat(h.outcome()).isEqualTo(ScriptedProcessHardening.Outcome.NOT_ATTEMPTED);
        assertThat(h.apply()).isEqualTo(ScriptedProcessHardening.Outcome.APPLIED);
        assertThat(h.apply()).isEqualTo(ScriptedProcessHardening.Outcome.APPLIED);
        assertThat(options).containsExactly(ScriptedProcessHardening.PR_SET_DUMPABLE, ScriptedProcessHardening.PR_GET_DUMPABLE);
        assertThat(dumpable[0]).isZero();
    }

    @Test
    void linux_prctlRefusedOrNotTaking_isFailedNotThrown() {
        assertThat(new ScriptedProcessHardening(true, "linux", (o, a) -> -1).apply())
                .isEqualTo(ScriptedProcessHardening.Outcome.FAILED);
        // dönüş 0 ama durum hâlâ dump-edilebilir → başarısız sayılır
        assertThat(new ScriptedProcessHardening(true, "Linux", (o, a) -> o == ScriptedProcessHardening.PR_GET_DUMPABLE ? 1 : 0).apply())
                .isEqualTo(ScriptedProcessHardening.Outcome.FAILED);
        assertThat(new ScriptedProcessHardening(true, "Linux", (o, a) -> { throw new UnsatisfiedLinkError("yok"); }).apply())
                .isEqualTo(ScriptedProcessHardening.Outcome.FAILED);
    }

    @Test
    void isLinux() {
        assertThat(ScriptedProcessHardening.isLinux("Linux")).isTrue();
        assertThat(ScriptedProcessHardening.isLinux("linux-gnu")).isTrue();
        assertThat(ScriptedProcessHardening.isLinux("Windows 11")).isFalse();
        assertThat(ScriptedProcessHardening.isLinux(null)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    void realConstructorOffLinux_isNoop() {
        assertThat(new ScriptedProcessHardening(true).apply()).isEqualTo(ScriptedProcessHardening.Outcome.NOT_LINUX);
    }

    @Test
    void afterStart_neverThrows() throws Exception {
        new ScriptedProcessHardening(true, "Windows 10", null).afterStart(null);
        Process p = new ProcessBuilder(javaBin(), "-version").redirectErrorStream(true).start();
        try {
            // Linux dışı: dokunmaz; "Linux" denirse /proc yazımı (burada yok) sessizce başarısız olur
            new ScriptedProcessHardening(true, "Windows 10", null).afterStart(p);
            ScriptedProcessHardening fake = new ScriptedProcessHardening(true, "Linux", null);
            fake.afterStart(p);
            fake.afterStart(p);
        } finally {
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
        }
    }

    private static String javaBin() {
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
