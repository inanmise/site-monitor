package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProcessProbe gerçek süreç çalıştırma testi — OS'e uygun hızlı/yavaş komutla
 * çıktı yakalama ve deadlock-safe timeout davranışını doğrular.
 */
class ProcessProbeTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    @DisplayName("run: hızlı komut çıktısını yakalar, timedOut=false")
    void run_capturesOutput() {
        List<String> args = isWindows()
                ? List.of("cmd", "/c", "echo", "merhaba")
                : List.of("sh", "-c", "echo merhaba");
        ProcessProbe.Result r = ProcessProbe.run(args, null, 5);
        assertThat(r.timedOut()).isFalse();
        assertThat(r.output()).contains("merhaba");
        assertThat(r.exitCode()).isZero();
    }

    @Test
    @DisplayName("run: uzun komut timeout'a uğrar ve sonlandırılır (deadlock yok)")
    void run_timesOut() {
        // ~5sn süren komut, 1sn timeout → öldürülmeli, asılı kalmamalı
        List<String> args = isWindows()
                ? List.of("ping", "-n", "6", "127.0.0.1")
                : List.of("sh", "-c", "sleep 5");
        long t0 = System.currentTimeMillis();
        ProcessProbe.Result r = ProcessProbe.run(args, null, 1);
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(r.timedOut()).isTrue();
        assertThat(r.output()).contains("zaman aşımı");
        // 1s timeout + 3s kill payı; 10s'i aşmamalı (asılı kalmadığının kanıtı)
        assertThat(elapsed).isLessThan(10_000);
    }

    @Test
    @DisplayName("run: olmayan binary → çalıştırılamadı (asılmaz)")
    void run_missingBinary() {
        ProcessProbe.Result r = ProcessProbe.run(List.of("this-binary-does-not-exist-xyz"), null, 5);
        assertThat(r.output()).contains("çalıştırılamadı");
        assertThat(r.exitCode()).isEqualTo(-1);
    }
}
