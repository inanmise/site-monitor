package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UptimeHttpCheckerService}.
 *
 * Like {@link PortCheckerServiceTest}, we use IANA's TEST-NET-1 (192.0.2.0/24)
 * for the unreachable case to avoid ISP DNS hijacking false positives.
 */
class UptimeHttpCheckerServiceTest {

    private final UptimeHttpCheckerService service = new UptimeHttpCheckerService(permissiveGuard());

    @Test
    @DisplayName("check returns status=up + response_ms when target accepts TCP")
    void check_listeningPort_returnsUp() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Map<String, Object> r = service.check("127.0.0.1", port, 1000);
            assertThat(r.get("status")).isEqualTo("up");
            assertThat(r.get("response_ms")).isInstanceOf(Long.class);
        }
    }

    @Test
    @DisplayName("check returns status=down for a closed local port")
    void check_closedPort_returnsDown() {
        Map<String, Object> r = service.check("127.0.0.1", 1, 500);
        assertThat(r.get("status")).isEqualTo("down");
        assertThat(r).containsKey("error");
        assertThat(r.get("response_ms")).isNull();
    }

    @Test
    @DisplayName("check returns status=down for an unreachable IP (TEST-NET-1)")
    void check_unreachableAddress_returnsDown() {
        Map<String, Object> r = service.check("192.0.2.1", 443, 200);
        assertThat(r.get("status")).isEqualTo("down");
        assertThat(r).containsKey("error");
    }

    @Test
    @DisplayName("response_ms is null on failure path")
    void check_failure_responseMsNull() {
        Map<String, Object> r = service.check("192.0.2.1", 443, 50);
        assertThat(r.get("response_ms")).isNull();
    }

    /** Testler 127.0.0.1'e baglanir -> SsrfGuard izin verici kurulur (loopback + ic ag acik);
     *  metadata/link-local YINE bloklu. PortCheckerServiceTest ile ayni desen. */
    private static SsrfGuard permissiveGuard() {
        AppSettingsService s = org.mockito.Mockito.mock(AppSettingsService.class);
        org.mockito.Mockito.when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        org.mockito.Mockito.when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        return new SsrfGuard(s);
    }

    @Test
    @DisplayName("SSRF: cloud-metadata hedefi baglanmadan ONCE reddedilir (down + neden)")
    void check_metadataTarget_blocked() {
        // Bu checker hic dogrulama yapmiyordu: hedefi tanimlayabilen kullanici, pod'un ulasabildigi
        // herhangi bir ic adrese TCP yoklamasi yaptirip up/down cevabindan varlik haritasi cikarabilirdi.
        Map<String, Object> r = service.check("169.254.169.254", 80, 500);
        assertThat(r.get("status")).isEqualTo("down");
        assertThat(r.get("response_ms")).isNull();
        assertThat((String) r.get("error")).contains("cloud-metadata");
    }
}
