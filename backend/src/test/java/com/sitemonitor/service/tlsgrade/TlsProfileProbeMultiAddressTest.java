package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SsrfGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHECKS-9 (2026-10-22): TLS profili yoklaması doğrudan yolda SSRF'nin döndürdüğü İLK adrese bağlanıyordu. Split-VIP
 * host'ta ilk IP portu dinlemiyorsa profil FAILED oluyordu (not "profil ölçülemedi" tavanına düşer) — sertifika kontrolü
 * tüm adresleri denediği için host sağlıklı görünürken. Gerçek varsayılan bağlantı kurucu (connectorFactory değişmez).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TlsProfileProbeMultiAddressTest {

    @Test
    @DisplayName("İki adres, ilki bağlantıyı reddediyor → ikinciye bağlanılır, profil OK ve 5 yoklamanın hepsi aynı sunucuda")
    void firstAddressRefuses_secondIsUsed() throws Exception {
        try (FakeTlsServer s = new FakeTlsServer(TlsProfileProbeServiceTest::modernServer, 5)) {
            SsrfGuard ssrf = mock(SsrfGuard.class);
            ProxySettings proxy = mock(ProxySettings.class);
            // 127.0.0.2'de dinleyen yok (sunucu yalnız 127.0.0.1'e bağlı) → bağlantı reddedilir
            when(ssrf.validate(anyString())).thenReturn(List.of(InetAddress.getByName("127.0.0.2"),
                    InetAddress.getLoopbackAddress()));
            when(proxy.enabled()).thenReturn(false);
            TlsProfileProbeService service = new TlsProfileProbeService(ssrf, proxy);
            service.probeTimeoutMs = 2_000;
            service.endpointBudgetMs = 25_000;

            TlsProfile p = service.probe("www.example.com", s.port(), false, TlsProfile.TRIGGER_SCHEDULED, null);

            assertThat(p.getStatus()).as("error=%s", p.getError()).isEqualTo(TlsProfile.STATUS_OK);
            assertThat(p.getTls13()).isEqualTo(TlsProfile.YES);
            assertThat(p.getTls12()).isEqualTo(TlsProfile.YES);
            assertThat(p.getVia()).isEqualTo("direct");
            assertThat(s.hellos).hasSize(5);
        }
    }

    @Test
    @DisplayName("directConnector: ulaşılan adres hatırlanır, düşerse liste yeniden denenir; hiçbiri yoksa IOException")
    void directConnector_stickyAndFallback() throws Exception {
        try (FakeTlsServer s = new FakeTlsServer(h -> FakeTlsServer.Reply.close(), 4)) {
            TlsHelloProbe.Connector c = TlsProfileProbeService.directConnector(
                    List.of(InetAddress.getByName("127.0.0.2"), InetAddress.getLoopbackAddress()), s.port());
            try (var a = c.open(2_000)) {
                assertThat(a.getInetAddress()).isEqualTo(InetAddress.getLoopbackAddress());
            }
            try (var b = c.open(2_000)) {
                assertThat(b.getInetAddress()).isEqualTo(InetAddress.getLoopbackAddress());
            }
        }
        TlsHelloProbe.Connector none = TlsProfileProbeService.directConnector(List.of(), 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> none.open(500)).isInstanceOf(java.io.IOException.class);
    }
}
