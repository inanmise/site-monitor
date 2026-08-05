package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OS-duyarlı komut kurma + özet yardımcılarının birim testleri. Gerçek komut
 * çalıştırma (ProcessProbe) ortam bağımlı olduğundan burada test edilmez.
 */
class NetworkDiagnosticsServiceTest {

    @Test
    @DisplayName("buildPingArgs: Windows -n 4 / Linux -c 4 -w")
    void buildPingArgs_osAware() {
        List<String> args = NetworkDiagnosticsService.buildPingArgs("example.com");
        assertThat(args.get(0)).isEqualTo("ping");
        if (NetworkDiagnosticsService.isWindows()) {
            assertThat(args).containsExactly("ping", "-n", "4", "example.com");
        } else {
            assertThat(args).containsExactly("ping", "-c", "4", "-w", "8", "example.com");
        }
    }

    @Test
    @DisplayName("buildTracerouteArgs: Windows tracert / Linux traceroute (bounded)")
    void buildTracerouteArgs_osAware() {
        List<String> args = NetworkDiagnosticsService.buildTracerouteArgs("example.com");
        if (NetworkDiagnosticsService.isWindows()) {
            assertThat(args).containsExactly("tracert", "-h", "15", "-w", "2000", "example.com");
        } else {
            assertThat(args).containsExactly("traceroute", "-m", "15", "-w", "2", "-q", "1", "example.com");
        }
    }

    @Test
    @DisplayName("buildCurlArgs: şema porta göre, null device OS'e göre")
    void buildCurlArgs_schemeAndNull() {
        String nul = NetworkDiagnosticsService.nullDevice();
        assertThat(NetworkDiagnosticsService.buildCurlArgs("ex.com", 443))
                .containsExactly("curl", "-sS", "-v", "--max-time", "8", "-o", nul, "https://ex.com");
        assertThat(NetworkDiagnosticsService.buildCurlArgs("ex.com", 80))
                .containsExactly("curl", "-sS", "-v", "--max-time", "8", "-o", nul, "http://ex.com");
        assertThat(NetworkDiagnosticsService.buildCurlArgs("ex.com", 8443))
                .contains("https://ex.com:8443");
    }

    @Test
    @DisplayName("firstMeaningfulLine: ilk boş olmayan satır, kısaltma")
    void firstMeaningfulLine() {
        assertThat(NetworkDiagnosticsService.firstMeaningfulLine("\n\n  hello \nworld")).isEqualTo("hello");
        assertThat(NetworkDiagnosticsService.firstMeaningfulLine("")).isEqualTo("—");
        assertThat(NetworkDiagnosticsService.firstMeaningfulLine(null)).isEqualTo("");
        String longLine = "x".repeat(200);
        assertThat(NetworkDiagnosticsService.firstMeaningfulLine(longLine)).hasSize(121).endsWith("…");
    }

    @Test
    @DisplayName("nullDevice: Windows NUL / diğer /dev/null")
    void nullDevice_osAware() {
        assertThat(NetworkDiagnosticsService.nullDevice())
                .isEqualTo(NetworkDiagnosticsService.isWindows() ? "NUL" : "/dev/null");
    }
}
