package com.sitemonitor.service;

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
    @DisplayName("blockReason: IPv6 loopback/any-local/link-local/cloud-metadata/multicast")
    void blockReason_ipv6() throws Exception {
        // loopback ::1 / any-local :: → allow-loopback'e bağlı
        assertThat(SsrfGuard.blockReason(ip("::1"), true, false)).isNotNull();
        assertThat(SsrfGuard.blockReason(ip("::1"), true, true)).isNull();
        assertThat(SsrfGuard.blockReason(ip("::"), true, false)).isNotNull();
        // link-local fe80:: / cloud-metadata fd00:ec2::254 / multicast ff02::1 → HER ZAMAN blok (allow'lar açık olsa bile)
        assertThat(SsrfGuard.blockReason(ip("fe80::1"), true, true)).isNotNull();
        assertThat(SsrfGuard.blockReason(ip("fd00:ec2::254"), true, true)).isNotNull();
        assertThat(SsrfGuard.blockReason(ip("ff02::1"), true, true)).isNotNull();
    }

    @Test
    @DisplayName("validate: metadata/loopback/boş/çözülemeyen reddedilir; public + (açıkken) iç ağ izinli")
    void validate_endToEnd() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(false);
        SsrfGuard g = new SsrfGuard(s);

        assertThatThrownBy(() -> g.validate("169.254.169.254")).isInstanceOf(SsrfGuard.BlockedException.class);
        assertThatThrownBy(() -> g.validate("127.0.0.1")).isInstanceOf(SsrfGuard.BlockedException.class);
        assertThatThrownBy(() -> g.validate("")).isInstanceOf(SsrfGuard.BlockedException.class);
        // çözülemeyen host → UnknownHostException → BlockedException (fail-safe)
        // (wildcard-DNS'li ağlarda var olmayan adlar çözüldüğü için sözdizimsel geçersiz ad — TestHosts)
        // ÇÖZÜLEMEYEN host AYRI tiptir: politika reddiyle aynı mesaja düşünce kullanıcı aracın
        // kendisini engellediğini sanıyordu, oysa çözüm host adını düzeltmek. Alt tip olduğu için
        // BlockedException yakalayan mevcut çağıranların hepsi eskisi gibi çalışır.
        assertThatThrownBy(() -> g.validate(TestHosts.UNRESOLVABLE))
                .isInstanceOf(SsrfGuard.UnresolvableHostException.class)
                .isInstanceOf(SsrfGuard.BlockedException.class);
        // Politika reddi ise ÇÖZÜLEMEYEN sayılmamalı — ikisi karışırsa mesaj yine yanlış olur.
        assertThatThrownBy(() -> g.validate("127.0.0.1"))
                .isInstanceOf(SsrfGuard.BlockedException.class)
                .isNotInstanceOf(SsrfGuard.UnresolvableHostException.class);
        assertThatCode(() -> g.validate("10.1.2.3")).doesNotThrowAnyException();   // iç ağ açık
        assertThat(g.validate("8.8.8.8")).isNotEmpty();
    }

    @Test
    @DisplayName("validate: iç ağ kapalıyken private hedef reddedilir")
    void validate_internalDisabled() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(false);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(false);
        SsrfGuard g = new SsrfGuard(s);
        assertThatThrownBy(() -> g.validate("192.168.10.10")).isInstanceOf(SsrfGuard.BlockedException.class);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("blacklistCidrs: metadata/multicast/link-local her zaman; loopback+RFC1918 toggle'a göre")
    void blacklistCidrs_honorsToggles() {
        // Varsayılan: internal AÇIK (RFC1918 listede YOK), loopback KAPALI (listede VAR)
        var def = SsrfGuard.blacklistCidrs(true, false);
        assertThat(def).contains("169.254.169.254/32", "fd00:ec2::254/128", "224.0.0.0/4", "169.254.0.0/16", "fe80::/10", "127.0.0.0/8");
        assertThat(def).doesNotContain("10.0.0.0/8");   // internal AÇIK → RFC1918 bloklanmaz

        // internal KAPALI + loopback AÇIK
        var strict = SsrfGuard.blacklistCidrs(false, true);
        assertThat(strict).contains("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7");
        assertThat(strict).doesNotContain("127.0.0.0/8");   // loopback AÇIK → bloklanmaz
        assertThat(strict).contains("169.254.169.254/32");  // metadata her zaman
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("blacklistCidrs() instance: canlı ayarları okur")
    void blacklistCidrs_instanceReadsSettings() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(false);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        var cidrs = new SsrfGuard(s).blacklistCidrs();
        assertThat(cidrs).contains("10.0.0.0/8").doesNotContain("127.0.0.0/8");
    }
}
