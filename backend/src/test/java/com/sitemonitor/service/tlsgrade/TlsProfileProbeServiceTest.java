package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.tlsgrade.FakeTlsServer.Reply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TLS profili yoklaması (2026-10-10): sürüm/zımbalama/zayıf takım birleştirmesi, SSRF engeli, vekil kararı ve reddi,
 * hiçbir şey kabul edilmeyince "bilinmiyor" (tahmin yok) ve uç başına süre bütçesi.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TlsProfileProbeServiceTest {

    private SsrfGuard ssrf;
    private ProxySettings proxy;
    private TlsProfileProbeService service;

    @BeforeEach
    void setUp() {
        ssrf = mock(SsrfGuard.class);
        proxy = mock(ProxySettings.class);
        when(ssrf.validate(anyString())).thenReturn(List.of(InetAddress.getLoopbackAddress()));
        when(proxy.enabled()).thenReturn(false);
        service = new TlsProfileProbeService(ssrf, proxy);
        service.probeTimeoutMs = 2_000;
        service.endpointBudgetMs = 20_000;
    }

    /** Modern sunucu: 1.3 + 1.2 (zımbalama var), 1.0/1.1 protocol_version ile ret, zayıf takım handshake_failure. */
    static Reply modernServer(FakeTlsServer.Hello h) {
        if (h.offersTls13()) return Reply.of(FakeTlsServer.serverHello(0x0304, 0x1301, true, false));
        if (h.legacyVersion() < 0x0303) return Reply.of(FakeTlsServer.alert(2, 70));
        if (h.suites().get(0) == 0x0005) return Reply.of(FakeTlsServer.alert(2, 40));
        return Reply.of(FakeTlsServer.concat(
                FakeTlsServer.serverHello(0x0303, 0xc02f, false, h.statusRequest()),
                FakeTlsServer.record(22, FakeTlsServer.handshake(11, new byte[64])),
                FakeTlsServer.record(22, FakeTlsServer.handshake(22, new byte[16]))));
    }

    @Test
    @DisplayName("Modern sunucu: TLS 1.3/1.2 açık, 1.1/1.0 kapalı, zımbalama var, zayıf takım yok → OK")
    void modernProfile() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(TlsProfileProbeServiceTest::modernServer, 5)) {
            service.connectorFactory = (host, port, viaProxy, addrs) -> s.connector();
            TlsProfile p = service.probe("www.example.com", 443, false, TlsProfile.TRIGGER_SCHEDULED, null);
            assertThat(p.getStatus()).isEqualTo(TlsProfile.STATUS_OK);
            assertThat(p.getTls13()).isEqualTo(TlsProfile.YES);
            assertThat(p.getTls12()).isEqualTo(TlsProfile.YES);
            assertThat(p.getTls11()).isEqualTo(TlsProfile.NO);
            assertThat(p.getTls10()).isEqualTo(TlsProfile.NO);
            assertThat(p.getOcspStapling()).isEqualTo(TlsProfile.YES);
            assertThat(p.getWeakCipher()).isEqualTo(TlsProfile.NO);
            assertThat(p.getPreferredCipher()).isEqualTo("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
            assertThat(p.getVia()).isEqualTo("direct");
            assertThat(p.getProbedAt()).isNotBlank();
            assertThat(p.getDetail()).contains("\"p\":\"tls10\"").contains("\"a\":70");
            assertThat(s.hellos).hasSize(5);
        }
    }

    @Test
    @DisplayName("Eski sunucu: TLS 1.0 açık + RC4 kabul ediliyor → profil bunu söyler, not B'den aşağı")
    void legacyProfileFeedsGrade() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> {
            if (h.offersTls13()) return Reply.of(FakeTlsServer.serverHello(0x0303, 0xc013, false, false));
            if (h.suites().get(0) == 0x0005) return Reply.of(FakeTlsServer.serverHello(0x0303, 0x0005, false, false));
            return Reply.of(FakeTlsServer.serverHello(h.legacyVersion(), 0xc013, false, false));
        }, 5)) {
            service.connectorFactory = (host, port, viaProxy, addrs) -> s.connector();
            TlsProfile p = service.probe("old.example.com", 443, false, TlsProfile.TRIGGER_SCHEDULED, null);
            assertThat(p.getTls10()).isEqualTo(TlsProfile.YES);
            assertThat(p.getTls11()).isEqualTo(TlsProfile.YES);
            assertThat(p.getTls13()).isEqualTo(TlsProfile.NO);
            assertThat(p.getOcspStapling()).isEqualTo(TlsProfile.NO);
            assertThat(p.getWeakCipher()).isEqualTo(TlsProfile.YES);
            assertThat(p.getWeakCipherSuite()).isEqualTo("TLS_RSA_WITH_RC4_128_SHA");
            TlsGradeRules.Grade g = TlsGradeRules.evaluate(TlsGradeRulesTest.perfectCheck(), p, false);
            assertThat(g.grade()).isEqualTo("C");
            assertThat(g.codes()).contains("WEAK_CIPHER_ACCEPTED", "TLS10_ENABLED", "TLS11_ENABLED", "NO_TLS13");
        }
    }

    @Test
    @DisplayName("SSRF engeli → BLOCKED, hiç bağlanılmaz, tüm sorular UNKNOWN")
    void blocked() {
        when(ssrf.validate("meta.internal")).thenThrow(new SsrfGuard.BlockedException("izin verilmeyen hedef"));
        AtomicBoolean connected = new AtomicBoolean();
        service.connectorFactory = (host, port, viaProxy, addrs) -> t -> { connected.set(true); throw new IOException("x"); };
        TlsProfile p = service.probe("meta.internal", 443, false, TlsProfile.TRIGGER_SCHEDULED, null);
        assertThat(p.getStatus()).isEqualTo(TlsProfile.STATUS_BLOCKED);
        assertThat(p.getTls10()).isEqualTo(TlsProfile.UNKNOWN);
        assertThat(p.getTls13()).isEqualTo(TlsProfile.UNKNOWN);
        assertThat(connected).isFalse();
    }

    @Test
    @DisplayName("Vekil kararı kontrolle aynı: use_proxy ∧ vekil tanımlı ∧ NO_PROXY dışı; vekil reddi → FAILED, tek deneme")
    void proxyDecisionAndRefusal() {
        when(proxy.enabled()).thenReturn(true);
        when(proxy.bypass("inside.example.com")).thenReturn(true);
        AtomicInteger opens = new AtomicInteger();
        AtomicBoolean sawProxy = new AtomicBoolean();
        service.connectorFactory = (host, port, viaProxy, addrs) -> {
            sawProxy.set(viaProxy);
            return t -> { opens.incrementAndGet(); throw new IOException("vekil tüneli reddetti: HTTP/1.1 403 Forbidden"); };
        };
        TlsProfile p = service.probe("www.example.com", 443, true, TlsProfile.TRIGGER_MANUAL, "ali");
        assertThat(sawProxy).isTrue();
        assertThat(p.getVia()).isEqualTo("proxy");
        assertThat(p.getStatus()).isEqualTo(TlsProfile.STATUS_FAILED);
        assertThat(p.getError()).contains("403");
        assertThat(opens.get()).as("ilk yoklamada bağlantı yoksa geri kalanı denenmez").isEqualTo(1);
        assertThat(p.getProbedBy()).isEqualTo("ali");

        service.probe("inside.example.com", 443, true, TlsProfile.TRIGGER_SCHEDULED, null);
        assertThat(sawProxy).as("NO_PROXY kapsamındaki ad doğrudan gider").isFalse();
        service.probe("www.example.com", 443, false, TlsProfile.TRIGGER_SCHEDULED, null);
        assertThat(sawProxy).as("use_proxy kapalı → doğrudan").isFalse();
    }

    @Test
    @DisplayName("Hiçbir sürüm kabul edilmez (hep kapanır) → FAILED, kapanma 'kapalı' sayılmaz (UNKNOWN)")
    void nothingAcceptedIsUnknown() throws IOException {
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.close(), 5)) {
            service.connectorFactory = (host, port, viaProxy, addrs) -> s.connector();
            TlsProfile p = service.probe("waf.example.com", 443, false, TlsProfile.TRIGGER_SCHEDULED, null);
            assertThat(p.getStatus()).isEqualTo(TlsProfile.STATUS_FAILED);
            assertThat(p.getTls10()).isEqualTo(TlsProfile.UNKNOWN);
            assertThat(p.getTls12()).isEqualTo(TlsProfile.UNKNOWN);
            assertThat(p.getWeakCipher()).isEqualTo(TlsProfile.UNKNOWN);
        }
    }

    @Test
    @DisplayName("Uç bütçesi dolunca kalan yoklamalar atlanır (UNKNOWN) — toplam süre sınırlı")
    void endpointBudgetBounds() throws IOException {
        service.probeTimeoutMs = 700;
        service.endpointBudgetMs = 1_600;
        try (FakeTlsServer s = new FakeTlsServer(h -> Reply.stall(3_000), 5)) {
            service.connectorFactory = (host, port, viaProxy, addrs) -> s.connector();
            long t0 = System.currentTimeMillis();
            TlsProfile p = service.probe("slow.example.com", 443, false, TlsProfile.TRIGGER_SCHEDULED, null);
            assertThat(System.currentTimeMillis() - t0).isLessThan(6_000);
            assertThat(p.getStatus()).isEqualTo(TlsProfile.STATUS_FAILED);
            assertThat(p.getTls10()).isEqualTo(TlsProfile.UNKNOWN);
            assertThat(p.getDetail()).contains("TIMEOUT");
        }
    }

    @Test
    @DisplayName("versionState: kapanma yalnız başka sürüm kabul edildiyse 'kapalı' (NO)")
    void versionStateRules() {
        TlsHelloProbe.HelloResult closed = new TlsHelloProbe.HelloResult(TlsHelloProbe.Outcome.CLOSED, 0, 0, "UNKNOWN", null, "x", 1);
        TlsHelloProbe.HelloResult timeout = new TlsHelloProbe.HelloResult(TlsHelloProbe.Outcome.TIMEOUT, 0, 0, "UNKNOWN", null, "x", 1);
        assertThat(TlsProfileProbeService.versionState(closed, true)).isEqualTo(TlsProfile.NO);
        assertThat(TlsProfileProbeService.versionState(closed, false)).isEqualTo(TlsProfile.UNKNOWN);
        assertThat(TlsProfileProbeService.versionState(timeout, true)).isEqualTo(TlsProfile.UNKNOWN);
        assertThat(TlsProfileProbeService.versionState(null, true)).isEqualTo(TlsProfile.UNKNOWN);
    }
}
