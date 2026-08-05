package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PingCheckerService#buildPingArgs} — sistem {@code ping} komut satırının kurulumu.
 * check() gerçek ICMP'ye çıktığı için burada yalnız OS-bağımsız, deterministik değişmezler
 * doğrulanır (ilk token "ping", IP sürümü bayrağı, sayım değeri, host en sonda). iputils/Windows
 * arasındaki sayım/timeout bayrağı (-c/-n, -w) OS'a göre değişir → platforma özel assert yok.
 */
class PingCheckerServiceTest {

    @Test
    @DisplayName("buildPingArgs: v4 → 'ping' başta, '-4' bayrağı, sayım değeri var, host en sonda")
    void buildPingArgs_v4_hasFlagCountAndHostLast() {
        List<String> args = PingCheckerService.buildPingArgs("example.com", "v4", 3, 5);

        assertThat(args).first().isEqualTo("ping");
        assertThat(args).contains("-4").doesNotContain("-6");
        assertThat(args).contains("3");                    // sayım değeri (-c/-n ile)
        assertThat(args).last().isEqualTo("example.com");  // host daima en sonda
    }

    @Test
    @DisplayName("buildPingArgs: v6 → '-6' bayrağı eklenir ('-4' değil)")
    void buildPingArgs_v6_addsV6Flag() {
        List<String> args = PingCheckerService.buildPingArgs("example.com", "v6", 1, 5);

        assertThat(args).contains("-6").doesNotContain("-4");
        assertThat(args).last().isEqualTo("example.com");
    }

    @Test
    @DisplayName("buildPingArgs: IP sürümü belirtilmemiş (auto) → ne '-4' ne '-6'")
    void buildPingArgs_autoVersion_noFamilyFlag() {
        List<String> args = PingCheckerService.buildPingArgs("example.com", "auto", 2, 5);

        assertThat(args).doesNotContain("-4", "-6");
        assertThat(args).first().isEqualTo("ping");
        assertThat(args).last().isEqualTo("example.com");
    }
}
