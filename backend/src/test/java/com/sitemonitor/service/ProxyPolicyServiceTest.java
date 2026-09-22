package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP/Keyword/Sayfa izlemeleri için vekil kararı (2026-09-21): AUTO = envanterle aynı (alan adı envanterde
 * use_proxy=true ise vekil), ON = vekil (NO_PROXY ve yapılandırma kuralıyla), OFF = doğrudan; kaynak ve baypas
 * bilgisi arayüz için taşınır. Mevcut davranış korunur: envanterde işaretli olmayan/olmayan alan adı AUTO'da doğrudan.
 */
class ProxyPolicyServiceTest {

    private ProxySettings settings;
    private CertificateInventoryRepository repo;
    private ProxyPolicyService svc;

    private static CertificateInventory inv(String domain, Boolean useProxy) {
        CertificateInventory c = new CertificateInventory(); c.setDomain(domain); c.setUseProxy(useProxy); return c;
    }

    @BeforeEach
    void setUp() {
        settings = new ProxySettings();
        ReflectionTestUtils.setField(settings, "host", "proxy.example.com");
        ReflectionTestUtils.setField(settings, "port", 8080);
        ReflectionTestUtils.setField(settings, "noProxy", ".internal.example.com");
        repo = mock(CertificateInventoryRepository.class);
        when(repo.findByDomain(anyString())).thenReturn(Optional.empty());
        svc = new ProxyPolicyService(settings, repo);
    }

    @Test
    @DisplayName("AUTO: envanter kaydı use_proxy=true → vekil (kaynak inventory); www. varyantı da eşleşir")
    void autoInheritsInventory() {
        when(repo.findByDomain("a.example.com")).thenReturn(Optional.of(inv("a.example.com", true)));
        ProxyPolicyService.Decision d = svc.decide("https://a.example.com/health", "AUTO");
        assertThat(d.viaProxy()).isTrue();
        assertThat(d.source()).isEqualTo("inventory");
        assertThat(d.via()).isEqualTo("proxy");
        // www.a.example.com envanterde yok, a.example.com var → aynı site
        assertThat(svc.decide("https://www.a.example.com/", null).viaProxy()).isTrue();
    }

    @Test
    @DisplayName("AUTO: envanterde yok ya da use_proxy=false → doğrudan (bugünkü davranış); null/bozuk kip AUTO sayılır")
    void autoDirectWhenInventorySilent() {
        assertThat(svc.decide("https://b.example.com/", "AUTO")).isEqualTo(ProxyPolicyService.Decision.direct("none"));
        when(repo.findByDomain("c.example.com")).thenReturn(Optional.of(inv("c.example.com", false)));
        ProxyPolicyService.Decision d = svc.decide("https://c.example.com/", "garbage");
        assertThat(d.viaProxy()).isFalse();
        assertThat(d.source()).isEqualTo("inventory");
        assertThat(ProxyPolicyService.normalizeMode(null)).isEqualTo("AUTO");
        assertThat(ProxyPolicyService.normalizeMode(" on ")).isEqualTo("ON");
    }

    @Test
    @DisplayName("ON: vekil; NO_PROXY eşleşen hedefte baypas (istendi ama doğrudan); OFF: envanter ne derse desin doğrudan")
    void onOffAndBypass() {
        ProxyPolicyService.Decision on = svc.decide("https://d.example.com/", "ON");
        assertThat(on.viaProxy()).isTrue(); assertThat(on.source()).isEqualTo("monitor"); assertThat(on.bypassed()).isFalse();
        ProxyPolicyService.Decision by = svc.decide("https://x.internal.example.com/", "ON");
        assertThat(by.viaProxy()).isFalse(); assertThat(by.wanted()).isTrue(); assertThat(by.bypassed()).isTrue();
        when(repo.findByDomain("e.example.com")).thenReturn(Optional.of(inv("e.example.com", true)));
        ProxyPolicyService.Decision off = svc.decide("https://e.example.com/", "OFF");
        assertThat(off.viaProxy()).isFalse(); assertThat(off.source()).isEqualTo("monitor");
    }

    @Test
    @DisplayName("Vekil yapılandırılmamışsa ON/AUTO-envanter bile doğrudan — baypas olarak işaretlenir")
    void noProxyConfigured() {
        ReflectionTestUtils.setField(settings, "host", "");
        when(repo.findByDomain("f.example.com")).thenReturn(Optional.of(inv("f.example.com", true)));
        assertThat(svc.decide("https://f.example.com/", "AUTO").viaProxy()).isFalse();
        assertThat(svc.decide("https://f.example.com/", "AUTO").bypassed()).isTrue();
        assertThat(svc.decide("https://f.example.com/", "ON").viaProxy()).isFalse();
        assertThat(svc.decide("not a url", "ON").viaProxy()).isFalse();
    }
}
