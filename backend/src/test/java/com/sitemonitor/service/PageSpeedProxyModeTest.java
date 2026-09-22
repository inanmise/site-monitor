package com.sitemonitor.service;

import com.sitemonitor.model.PageSpeedMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sayfa Hizi vekil kipi (2026-09-21): varsayilan OFF — mevcut kayitlar (null) ve bilinmeyen girdi dogrudan kalir;
 * yalnizca acik AUTO/ON karari ProxyPolicyService'e sorar. HTTP/Keyword/Sayfa'daki null->AUTO kuralindan BILEREK farkli.
 */
class PageSpeedProxyModeTest {

    private static PageSpeedCheckerService checker(ProxyPolicyService policy) {
        PageSpeedCheckerService c = new PageSpeedCheckerService(
                mock(com.sitemonitor.service.page.PageFetchCore.class),
                mock(com.sitemonitor.service.page.HttpPhaseProbe.class),
                mock(PublicSuffixService.class), mock(AppSettingsService.class), mock(SecretCipher.class));
        ReflectionTestUtils.setField(c, "proxyPolicy", policy);
        return c;
    }

    private static PageSpeedMonitor monitor(String mode) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setUrl("https://www.example.com/");
        m.setUseProxy(mode);
        return m;
    }

    @Test
    @DisplayName("normalizeModeDefaultOff: null/bos/bilinmeyen -> OFF; AUTO ve ON korunur; kucuk harf kabul")
    void normalizeDefaultOff() {
        assertThat(ProxyPolicyService.normalizeModeDefaultOff(null)).isEqualTo("OFF");
        assertThat(ProxyPolicyService.normalizeModeDefaultOff("")).isEqualTo("OFF");
        assertThat(ProxyPolicyService.normalizeModeDefaultOff("garbage")).isEqualTo("OFF");
        assertThat(ProxyPolicyService.normalizeModeDefaultOff("auto")).isEqualTo("AUTO");
        assertThat(ProxyPolicyService.normalizeModeDefaultOff("ON")).isEqualTo("ON");
        // Kardes kural degismedi: HTTP/Keyword/Sayfa'da null -> AUTO
        assertThat(ProxyPolicyService.normalizeMode(null)).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("null ve OFF kipte ProxyPolicyService'e HIC sorulmaz -> dogrudan (mevcut olcum yolu)")
    void nullOrOff_neverConsultsPolicy() {
        ProxyPolicyService policy = mock(ProxyPolicyService.class);
        when(policy.decide(anyString(), anyString())).thenThrow(new AssertionError("sorulmamaliydi"));
        PageSpeedCheckerService c = checker(policy);
        assertThat(c.viaProxyFor(monitor(null))).isFalse();
        assertThat(c.viaProxyFor(monitor("OFF"))).isFalse();
    }

    @Test
    @DisplayName("AUTO/ON kipte karar ProxyPolicyService'ten gelir")
    void autoOrOn_consultsPolicy() {
        ProxyPolicyService policy = mock(ProxyPolicyService.class);
        when(policy.decide(eq("https://www.example.com/"), eq("ON")))
                .thenReturn(new ProxyPolicyService.Decision(true, "monitor", true, false));
        when(policy.decide(eq("https://www.example.com/"), eq("AUTO")))
                .thenReturn(new ProxyPolicyService.Decision(false, "none", false, false));
        PageSpeedCheckerService c = checker(policy);
        assertThat(c.viaProxyFor(monitor("ON"))).isTrue();
        assertThat(c.viaProxyFor(monitor("AUTO"))).isFalse();
    }

    @Test
    @DisplayName("Bean yoksa (eski kurulum) ON bile dogrudan -> hicbir sey kirilmaz")
    void noPolicyBean_direct() {
        assertThat(checker(null).viaProxyFor(monitor("ON"))).isFalse();
    }
}
