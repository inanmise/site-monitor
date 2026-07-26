package com.certmonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SSRF politikası: cloud-metadata/link-local her zaman blok; loopback allow-loopback'e; iç/private allow-internal'e bağlı. */
class SsrfGuardTest {

    private static InetAddress ip(String s) throws Exception { return InetAddress.getByName(s); }

    @Test
    @DisplayName("blockReason: metadata/link-local her zaman; loopback/any-local ve private ayara göre; public izinli")
    void blockReason_policy() throws Exception {
        // cloud-metadata → ayardan bağımsız (allow'lar açık olsa bile) blok
        assertThat(SsrfGuard.blockReason(ip("169.254.169.254"), true, true)).isNotNull();
        // link-local → her zaman blok
        assertThat(SsrfGuard.blockReason(ip("169.254.1.1"), true, true)).isNotNull();
        // loopback / any-local → allow-loopback'e bağlı
        assertThat(SsrfGuard.blockReason(ip("127.0.0.1"), true, false)).isNotNull();
        assertThat(SsrfGuard.blockReason(ip("127.0.0.1"), true, true)).isNull();
        assertThat(SsrfGuard.blockReason(ip("0.0.0.0"), true, false)).isNotNull();
        // site-local (private) → allow-internal'e bağlı
        assertThat(SsrfGuard.blockReason(ip("10.0.0.5"), false, false)).isNotNull();
        assertThat(SsrfGuard.blockReason(ip("10.0.0.5"), true, false)).isNull();
        assertThat(SsrfGuard.blockReason(ip("192.168.1.10"), false, false)).isNotNull();
        // public → izinli
        assertThat(SsrfGuard.blockReason(ip("8.8.8.8"), false, false)).isNull();
        // IPv6 ULA (fc00::/7) → iç ağ
        assertThat(SsrfGuard.blockReason(ip("fd12:3456::1"), false, false)).isNotNull();
        assertThat(SsrfGuard.blockReason(ip("fd12:3456::1"), true, false)).isNull();
    }

    @Test
    @DisplayName("validate: metadata/loopback/boş reddedilir; public + (açıkken) iç ağ izinli")
    void validate_endToEnd() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("cert.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(s.getBoolean("cert.monitor.monitoring.allow-loopback-targets", false)).thenReturn(false);
        SsrfGuard g = new SsrfGuard(s);

        assertThatThrownBy(() -> g.validate("169.254.169.254")).isInstanceOf(SsrfGuard.BlockedException.class);
        assertThatThrownBy(() -> g.validate("127.0.0.1")).isInstanceOf(SsrfGuard.BlockedException.class);
        assertThatThrownBy(() -> g.validate("")).isInstanceOf(SsrfGuard.BlockedException.class);
        assertThatCode(() -> g.validate("10.1.2.3")).doesNotThrowAnyException();   // iç ağ açık
        assertThat(g.validate("8.8.8.8")).isNotEmpty();
    }

    @Test
    @DisplayName("validate: iç ağ kapalıyken private hedef reddedilir")
    void validate_internalDisabled() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("cert.monitor.monitoring.allow-internal-targets", true)).thenReturn(false);
        when(s.getBoolean("cert.monitor.monitoring.allow-loopback-targets", false)).thenReturn(false);
        SsrfGuard g = new SsrfGuard(s);
        assertThatThrownBy(() -> g.validate("192.168.10.10")).isInstanceOf(SsrfGuard.BlockedException.class);
    }
}
