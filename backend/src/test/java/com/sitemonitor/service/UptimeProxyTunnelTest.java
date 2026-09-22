package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Durum (uptime) yoklamasinin kurumsal vekil CONNECT tuneli (2026-09-21): envanter "Proxy uzerinden kontrol et = Evet"
 * olan alan adi sertifika kontrolunde vekilden gecerken Durum izlemesi pod'dan dogrudan cikiyor ve hep "down" kaliyordu.
 * Sahte vekil: yerel ServerSocket, CONNECT satirini okur, 200 ya da 403 doner.
 */
class UptimeProxyTunnelTest {

    private static SsrfGuard permissiveGuard() {
        AppSettingsService s = org.mockito.Mockito.mock(AppSettingsService.class);
        org.mockito.Mockito.when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        org.mockito.Mockito.when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        return new SsrfGuard(s);
    }

    private static ProxySettings proxyAt(int port, String user, String pass) {
        ProxySettings p = new ProxySettings();
        ReflectionTestUtils.setField(p, "host", "127.0.0.1");
        ReflectionTestUtils.setField(p, "port", port);
        ReflectionTestUtils.setField(p, "user", user);
        ReflectionTestUtils.setField(p, "pass", pass);
        ReflectionTestUtils.setField(p, "noProxy", "");
        return p;
    }

    /** Tek istegi kabul eden sahte vekil: CONNECT istegini yakalar, verilen durum satirini doner. */
    private static Thread fakeProxy(ServerSocket server, String statusLine, AtomicReference<String> seenRequest) {
        Thread t = new Thread(() -> {
            try (Socket c = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
                StringBuilder req = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) req.append(line).append('\n');
                seenRequest.set(req.toString());
                c.getOutputStream().write((statusLine + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                c.getOutputStream().flush();
                Thread.sleep(200);   // istemci tuneli kapatana kadar acik tut
            } catch (Exception ignored) { /* test sonu */ }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    @DisplayName("viaProxy=true: hedefe DEGIL vekile baglanir, CONNECT host:port gonderir, 200 -> up + via=proxy")
    void viaProxy_connectTunnel_up() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0)) {
            AtomicReference<String> seen = new AtomicReference<>();
            fakeProxy(proxy, "HTTP/1.1 200 Connection established", seen);
            UptimeHttpCheckerService svc = new UptimeHttpCheckerService(permissiveGuard());
            ReflectionTestUtils.setField(svc, "proxySettings", proxyAt(proxy.getLocalPort(), "u", "p"));

            // Hedef: dinlemeyen bir port — dogrudan yol "down" verirdi; vekil 200 dedigi icin "up" beklenir.
            Map<String, Object> r = svc.check("127.0.0.1", 1, 1500, true);

            assertThat(r.get("status")).isEqualTo("up");
            assertThat(r.get("via")).isEqualTo("proxy");
            assertThat(r.get("response_ms")).isInstanceOf(Long.class);
            assertThat(seen.get()).startsWith("CONNECT 127.0.0.1:1 HTTP/1.1\n");
            assertThat(seen.get()).contains("Host: 127.0.0.1:1").contains("Proxy-Authorization: Basic ");
        }
    }

    @Test
    @DisplayName("viaProxy=true: vekil tuneli reddederse (403) down + hata vekilin durum satirini tasir")
    void viaProxy_tunnelRefused_down() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0)) {
            fakeProxy(proxy, "HTTP/1.1 403 Forbidden", new AtomicReference<>());
            UptimeHttpCheckerService svc = new UptimeHttpCheckerService(permissiveGuard());
            ReflectionTestUtils.setField(svc, "proxySettings", proxyAt(proxy.getLocalPort(), "", ""));

            Map<String, Object> r = svc.check("127.0.0.1", 1, 1500, true);

            assertThat(r.get("status")).isEqualTo("down");
            assertThat(r.get("via")).isEqualTo("proxy");
            assertThat((String) r.get("error")).contains("403");
        }
    }

    @Test
    @DisplayName("viaProxy=false ve eski 3-arg imza: dogrudan yol, via=direct (mevcut davranis korunur)")
    void direct_unchanged() throws IOException {
        try (ServerSocket target = new ServerSocket(0)) {
            UptimeHttpCheckerService svc = new UptimeHttpCheckerService(permissiveGuard());
            Map<String, Object> r = svc.check("127.0.0.1", target.getLocalPort(), 1000);
            assertThat(r.get("status")).isEqualTo("up");
            assertThat(r.get("via")).isEqualTo("direct");
        }
    }

    @Test
    @DisplayName("ProxySettings bean'i yoksa viaProxy=true bile dogrudan gider (eski kurulumlar kirilmaz)")
    void noProxyBean_fallsBackToDirect() throws IOException {
        try (ServerSocket target = new ServerSocket(0)) {
            UptimeHttpCheckerService svc = new UptimeHttpCheckerService(permissiveGuard());
            Map<String, Object> r = svc.check("127.0.0.1", target.getLocalPort(), 1000, true);
            assertThat(r.get("status")).isEqualTo("up");
        }
    }
}
