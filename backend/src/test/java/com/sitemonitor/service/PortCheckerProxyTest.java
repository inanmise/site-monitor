package com.sitemonitor.service;

import com.sitemonitor.model.PortMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Port izlemesi vekil üzerinden (2026-09-24): yerel sahte CONNECT vekili ile uçtan uca. Vekil tünel isteğini okur,
 * senaryoya göre 200 (tünel) ya da 403 (izin yok) döner; 200'den sonra aynı soket "hedef" gibi davranır.
 */
class PortCheckerProxyTest {

    /** Sahte vekil: her bağlantıda CONNECT satırını kaydeder, sonra davranışı çalıştırır. */
    private static final class FakeProxy implements AutoCloseable {
        final ServerSocket server;
        final List<String> connectLines = Collections.synchronizedList(new ArrayList<>());
        final List<String> tunnelRequests = Collections.synchronizedList(new ArrayList<>());
        final Thread thread;

        FakeProxy(BiConsumer<BufferedReader, OutputStream> afterConnect, String connectStatus) throws Exception {
            server = new ServerSocket(0);
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                        OutputStream out = s.getOutputStream();
                        connectLines.add(in.readLine());
                        String l;
                        while ((l = in.readLine()) != null && !l.isEmpty()) { /* başlıkları tüket */ }
                        out.write((connectStatus + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        if (connectStatus.contains(" 200")) afterConnect.accept(in, out);
                    } catch (Exception ignored) { /* soket kapandı */ }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception { server.close(); }
    }

    private FakeProxy proxy;

    @AfterEach
    void tearDown() throws Exception { if (proxy != null) proxy.close(); }

    private static PortCheckerService serviceWithProxy(int proxyPort, List<String> connectPorts) {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getCsv(eq(PortCheckerService.CONNECT_PORTS_KEY), anyString())).thenReturn(connectPorts);
        PortCheckerService svc = new PortCheckerService(new SsrfGuard(s));
        ProxySettings ps = new ProxySettings();
        ReflectionTestUtils.setField(ps, "host", "127.0.0.1");
        ReflectionTestUtils.setField(ps, "port", proxyPort);
        ReflectionTestUtils.setField(svc, "proxySettings", ps);
        ReflectionTestUtils.setField(svc, "appSettings", s);
        return svc;
    }

    @Test
    @DisplayName("TCP vekil üzerinden: vekil CONNECT'e 200 dönerse açık; istek tam 'CONNECT host:port'; via=proxy")
    void tcpViaProxy_tunnelOk() throws Exception {
        proxy = new FakeProxy((in, out) -> { }, "HTTP/1.1 200 Connection established");
        PortCheckerService svc = serviceWithProxy(proxy.port(), List.of("443", "8443"));

        Map<String, Object> r = svc.check("localhost", 443, 2000, "TCP", null, null, "auto", true);

        assertThat(r).containsEntry("open", true).containsEntry("via", "proxy");
        assertThat(proxy.connectLines).first().isEqualTo("CONNECT localhost:443 HTTP/1.1");
    }

    @Test
    @DisplayName("vekil tüneli reddederse (403) 'port kapalı' DEĞİL: proxy_refused + hata mesajında izinli portlar")
    void proxyRefused_isReportedAsRefusalWithAllowedPorts() throws Exception {
        proxy = new FakeProxy((in, out) -> { }, "HTTP/1.1 403 Forbidden");
        PortCheckerService svc = serviceWithProxy(proxy.port(), List.of("443", "8443"));

        Map<String, Object> r = svc.check("localhost", 22, 2000, "TCP", null, null, "auto", true);

        assertThat(r).containsEntry("open", false).containsEntry("via", "proxy").containsEntry("proxy_refused", true);
        assertThat((String) r.get("error")).contains("vekil tüneli reddetti").contains("403").contains("izinli: 443, 8443");
    }

    @Test
    @DisplayName("BANNER vekil üzerinden: tünelden gelen bant beklenen metinle eşleşir")
    void bannerViaProxy() throws Exception {
        proxy = new FakeProxy((in, out) -> {
            try { out.write("220 mail ready\r\n".getBytes(StandardCharsets.ISO_8859_1)); out.flush(); Thread.sleep(200); } catch (Exception ignored) { }
        }, "HTTP/1.1 200 Connection established");
        PortCheckerService svc = serviceWithProxy(proxy.port(), List.of("25"));

        Map<String, Object> r = svc.check("localhost", 25, 2000, "BANNER", null, "220", "auto", true);

        assertThat(r).containsEntry("open", true).containsEntry("via", "proxy");
        assertThat((String) r.get("detail")).startsWith("220 mail ready");
    }

    @Test
    @DisplayName("HTTP vekil üzerinden (düz, 8080): tünelde elle GET yolla; durum kodu beklenenle karşılaştırılır")
    void httpViaProxy() throws Exception {
        proxy = new FakeProxy((in, out) -> {
            try {
                proxy.tunnelRequests.add(in.readLine());
                String l; while ((l = in.readLine()) != null && !l.isEmpty()) { /* başlıklar */ }
                out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (Exception ignored) { }
        }, "HTTP/1.1 200 Connection established");
        PortCheckerService svc = serviceWithProxy(proxy.port(), List.of("8080"));

        Map<String, Object> r = svc.check("localhost", 8080, 2000, "HTTP", "/health", "2xx", "auto", true);

        assertThat(r).containsEntry("open", true).containsEntry("via", "proxy").containsEntry("detail", "HTTP 204 · vekil üzerinden");
        assertThat(proxy.tunnelRequests).first().isEqualTo("GET /health HTTP/1.1");
    }

    @Test
    @DisplayName("UDP vekil istense de DOĞRUDAN: vekile hiç bağlanılmaz, via=direct")
    void udpNeverUsesProxy() throws Exception {
        proxy = new FakeProxy((in, out) -> { }, "HTTP/1.1 200 Connection established");
        PortCheckerService svc = serviceWithProxy(proxy.port(), List.of("443"));

        Map<String, Object> r = svc.check("127.0.0.1", 9, 300, "UDP", null, null, "auto", true);

        assertThat(r).containsEntry("via", "direct");
        Thread.sleep(100);
        assertThat(proxy.connectLines).isEmpty();
    }

    @Test
    @DisplayName("proxyDecision: kayıtlı kip null/boş → OFF (mevcut izlemeler doğrudan); UDP + ON → istendi ama geçemez")
    void proxyDecision_defaultOffAndUdp() {
        PortCheckerService svc = serviceWithProxy(1, List.of("443"));
        ProxyPolicyService policy = mock(ProxyPolicyService.class);
        when(policy.decideForHost(anyString(), eq("ON"))).thenReturn(new ProxyPolicyService.Decision(true, "monitor", true, false));
        ReflectionTestUtils.setField(svc, "proxyPolicy", policy);

        assertThat(svc.proxyDecision("a.example.com", "TCP", null).viaProxy()).isFalse();
        assertThat(svc.proxyDecision("a.example.com", "TCP", "").viaProxy()).isFalse();
        assertThat(svc.proxyDecision("a.example.com", "TCP", "OFF").viaProxy()).isFalse();
        assertThat(svc.proxyDecision("a.example.com", "TCP", "on").viaProxy()).isTrue();
        ProxyPolicyService.Decision udp = svc.proxyDecision("a.example.com", "udp", "ON");
        assertThat(udp.viaProxy()).isFalse();
        assertThat(udp.wanted()).isTrue();
        assertThat(udp.bypassed()).isTrue();

        PortMonitor m = new PortMonitor();
        m.setHost("a.example.com"); m.setPort(443); m.setProtocol("TCP");   // useProxy null → mevcut kayıt
        assertThat(svc.proxyDecision(m.getHost(), m.getProtocol(), m.getUseProxy()).via()).isEqualTo("direct");
    }

    @Test
    @DisplayName("proxyConnectPorts: ayar ayrıştırılır (bozuk/aralık dışı atlanır, sıralı); boşsa varsayılan 443, 8443")
    void connectPorts_parsing() {
        assertThat(serviceWithProxy(1, List.of("8443", " 22", "x", "70000", "443")).proxyConnectPorts()).containsExactly(22, 443, 8443);
        assertThat(serviceWithProxy(1, List.of("abc")).proxyConnectPorts()).containsExactly(443, 8443);
    }
}
