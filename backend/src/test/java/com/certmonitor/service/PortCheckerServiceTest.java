package com.certmonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PortCheckerService}.
 *
 * Real TCP sockets are used so we cover the open-vs-closed paths under
 * realistic conditions. We avoid `.invalid` hostnames because some ISPs
 * hijack them with captive portal IPs; instead we use the IANA-reserved
 * test net 192.0.2.0/24 for the "unreachable" probe.
 */
class PortCheckerServiceTest {

    private final PortCheckerService service = new PortCheckerService();

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
}
