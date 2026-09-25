package com.sitemonitor.service;

import com.sitemonitor.model.PortMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PortCheckerService}.
 *
 * Real TCP sockets are used so we cover the open-vs-closed paths under
 * realistic conditions. We avoid `.invalid` hostnames because some ISPs
 * hijack them with captive portal IPs; instead we use the IANA-reserved
 * test net 192.0.2.0/24 for the "unreachable" probe.
 */
class PortCheckerServiceTest {

    // Testler 127.0.0.1'e bağlanır → guard'ı izin verici kur (loopback + iç ağ açık). Metadata/link-local yine bloklu.
    private static PortCheckerService permissiveService() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        return new PortCheckerService(new SsrfGuard(s));
    }

    private final PortCheckerService service = permissiveService();

    @Test
    @DisplayName("check returns the open contract for a listening port")
    void check_openPort_returnsOpen() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Map<String, Object> r = service.check("127.0.0.1", port, 1000);
            assertThat(r.get("open")).isEqualTo(true);
            assertThat(r.get("response_ms")).isInstanceOf(Long.class);
            assertThat((Long) r.get("response_ms")).isGreaterThanOrEqualTo(0L);
        }
    }

    @Test
    @DisplayName("check on a closed local port returns open=false with an error")
    void check_closedPort_returnsClosed() {
        // Port 1 is reserved and not listening on standard dev/CI hosts.
        Map<String, Object> r = service.check("127.0.0.1", 1, 500);
        assertThat(r.get("open")).isEqualTo(false);
        assertThat(r).containsKey("error");
        assertThat(r.get("response_ms")).isNull();
    }

    @Test
    @DisplayName("check against the IANA TEST-NET-1 address times out cleanly")
    void check_unreachableAddress_returnsClosed() {
        // 192.0.2.1 is part of IANA's TEST-NET-1 reserved for documentation; it
        // is guaranteed not to be routable and will time out, not be hijacked.
        Map<String, Object> r = service.check("192.0.2.1", 443, 200);
        assertThat(r.get("open")).isEqualTo(false);
        assertThat(r).containsKey("error");
    }

    @Test
    @DisplayName("check always returns a result map with the documented keys")
    void check_alwaysReturnsResultShape() {
        Map<String, Object> r = service.check("192.0.2.1", 443, 50);
        assertThat(r).containsKey("open");
        assertThat(r).containsKey("response_ms");
    }

    @Test
    @DisplayName("bilinmeyen tip TCP'ye duser (acik portta open=true)")
    void unknownType_fallsBackToTcp() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            Map<String, Object> r = service.check("127.0.0.1", server.getLocalPort(), 1000, "WEIRD", null, null);
            assertThat(r.get("open")).isEqualTo(true);
        }
    }

    @Test
    @DisplayName("check(PortMonitor) tipe gore dispatch eder; TLS duz portta exception sizdirmadan down")
    void checkByMonitor_dispatchesTls_failsGracefully() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            PortMonitor m = new PortMonitor();
            m.setHost("127.0.0.1");
            m.setPort(server.getLocalPort());
            m.setTimeoutMs(1200);
            m.setProtocol("TLS");
            Map<String, Object> r = service.check(m);
            assertThat(r.get("open")).isEqualTo(false);
            assertThat(r).containsKey("error");
        }
    }

    @Test
    @DisplayName("check ipVersion=v4: IPv4 literaline bağlanır (open) + response_ms")
    void check_ipVersionV4_connects() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            Map<String, Object> r = service.check("127.0.0.1", server.getLocalPort(), 1000, "TCP", null, null, "v4");
            assertThat(r.get("open")).isEqualTo(true);
            assertThat(r.get("response_ms")).isInstanceOf(Long.class);
        }
    }

    @Test
    @DisplayName("check ipVersion=v6: yalnız-IPv4 hedefte aile bulunamaz → open=false + error")
    void check_ipVersionV6_noV6Address_returnsDown() {
        // 192.0.2.1 (IANA TEST-NET-1) yalnız IPv4 literali → v6 çözümlemesi başarısız (No IPv6 address)
        Map<String, Object> r = service.check("192.0.2.1", 443, 300, "TCP", null, null, "v6");
        assertThat(r.get("open")).isEqualTo(false);
        assertThat(r).containsKey("error");
    }

    @Test
    @DisplayName("check ipVersion=auto: mevcut davranışı korur (açık portta open)")
    void check_ipVersionAuto_unchanged() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            Map<String, Object> r = service.check("127.0.0.1", server.getLocalPort(), 1000, "TCP", null, null, "auto");
            assertThat(r.get("open")).isEqualTo(true);
        }
    }

    @Test
    @DisplayName("httpStatusMatches: bos->2xx/3xx, tam, sinif (2xx), aralik (200-399), coklu")
    void httpStatusMatches_patterns() {
        assertThat(PortCheckerService.httpStatusMatches(200, null)).isTrue();
        assertThat(PortCheckerService.httpStatusMatches(404, null)).isFalse();
        assertThat(PortCheckerService.httpStatusMatches(200, "200")).isTrue();
        assertThat(PortCheckerService.httpStatusMatches(204, "2xx")).isTrue();
        assertThat(PortCheckerService.httpStatusMatches(301, "200-399")).isTrue();
        assertThat(PortCheckerService.httpStatusMatches(404, "2xx,3xx")).isFalse();
        assertThat(PortCheckerService.httpStatusMatches(401, "401, 403")).isTrue();
    }

    // ── Regresyon R3 (2026-09-25): vekil tünelindeki durum satırı tavanlı + süreli okunur ──

    @Test
    @DisplayName("readStatusLine: CRLF'li durum satırını okur; satırsız biten akışta null")
    void readStatusLine_normal() throws IOException {
        long far = System.nanoTime() + 5_000_000_000L;
        assertThat(PortCheckerService.readStatusLine(
                new java.io.ByteArrayInputStream("HTTP/1.1 204 No Content\r\nX: y\r\n".getBytes()), far))
                .isEqualTo("HTTP/1.1 204 No Content");
        assertThat(PortCheckerService.readStatusLine(new java.io.ByteArrayInputStream(new byte[0]), far)).isNull();
    }

    @Test
    @DisplayName("readStatusLine: satır sonu göndermeyen hedef 8 KB'ta KESİLİR (tavansız tampon büyümez)")
    void readStatusLine_capped() {
        byte[] flood = new byte[PortCheckerService.STATUS_LINE_MAX + 100];
        java.util.Arrays.fill(flood, (byte) 'A');
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PortCheckerService.readStatusLine(
                        new java.io.ByteArrayInputStream(flood), System.nanoTime() + 5_000_000_000L))
                .isInstanceOf(IOException.class).hasMessageContaining("uzun");
    }

    @Test
    @DisplayName("readStatusLine: süre tavanı dolunca damlatan hedef zaman aşımına düşer (iş parçacığı tutulmaz)")
    void readStatusLine_deadline() {
        java.io.InputStream drip = new java.io.InputStream() {
            @Override public int read() { return 'H'; }   // hiç satır sonu yok
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        PortCheckerService.readStatusLine(drip, System.nanoTime() - 1))
                .isInstanceOf(java.net.SocketTimeoutException.class);
    }
}
