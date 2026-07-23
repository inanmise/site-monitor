package com.certmonitor.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Çok-A "happy-eyeballs" bağlantı yardımcısı — IP-literal tespiti, ilk-erişilebilene bağlanma. */
class NetworkResolverTest {

    @Test
    void isIpLiteral_detectsV4AndV6_notHostnames() {
        assertThat(NetworkResolver.isIpLiteral("127.0.0.1")).isTrue();
        assertThat(NetworkResolver.isIpLiteral("172.31.129.6")).isTrue();
        assertThat(NetworkResolver.isIpLiteral("::1")).isTrue();
        assertThat(NetworkResolver.isIpLiteral("2001:db8::1")).isTrue();
        assertThat(NetworkResolver.isIpLiteral("callcenterfacechat.akbank.com")).isFalse();
        assertThat(NetworkResolver.isIpLiteral("")).isFalse();
        assertThat(NetworkResolver.isIpLiteral(null)).isFalse();
    }

    @Test
    void allAddresses_resolvesLoopback_emptyOnFailure() {
        assertThat(NetworkResolver.allAddresses("127.0.0.1")).isNotEmpty();
        assertThat(NetworkResolver.allAddresses("nonexistent.host.invalid.example")).isEmpty();
    }

    @Test
    void connectFirstReachable_connectsToOpenPort() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            try (Socket s = NetworkResolver.connectFirstReachable("127.0.0.1", port, 2000)) {
                assertThat(s.isConnected()).isTrue();
            }
        }
    }

    @Test
    void connectFirstReachable_throwsWhenPortClosed() throws Exception {
        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) { closedPort = tmp.getLocalPort(); }  // kapanınca port serbest
        assertThatThrownBy(() -> NetworkResolver.connectFirstReachable("127.0.0.1", closedPort, 1000))
                .isInstanceOf(IOException.class);
    }

    @Test
    void firstReachable_returnsOpenAddress_nullWhenNoneOpen() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int open = server.getLocalPort();
            List<InetAddress> addrs = NetworkResolver.allAddresses("127.0.0.1");
            assertThat(NetworkResolver.firstReachable(addrs, open, 2000)).isNotNull();

            int closed;
            try (ServerSocket tmp = new ServerSocket(0)) { closed = tmp.getLocalPort(); }
            assertThat(NetworkResolver.firstReachable(addrs, closed, 1000)).isNull();
        }
    }
}
