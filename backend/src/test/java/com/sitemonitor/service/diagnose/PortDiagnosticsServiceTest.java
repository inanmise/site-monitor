package com.sitemonitor.service.diagnose;

import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.PortCheckerService;
import com.sitemonitor.service.ProxyPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Port uçtan uca tanılaması (2026-10-05) — sahte ağla her bulgu dalı: bağlantı reddi / süzülme / yol yok, bazı IP'ler
 * kapalı, vekil tüneli (izinsiz port / ret / ulaşılamaz), yol farkı (PATH_DIFFERS), TLS el sıkışması + bilgi bulguları,
 * HTTP durum kodu, banner (eşleşme / boş / uyuşmaz), UDP (yanıt / ICMP / yanıtsız), yavaş yanıt, maskeleme ve yan
 * etkisizlik (gerçek kontrol yalnız {@code check(m)} ile — kayıt yok).
 */
class PortDiagnosticsServiceTest {

    private static final String HOST = "db.example.test";
    private static final String SECRET = "Prx-Pa55-Secret";

    private FakeNetDiagNetwork net;
    private NetDiagWorkers workers;
    private PortCheckerService checker;
    private PortDiagnosticsService svc;

    @BeforeEach
    void setUp() {
        net = new FakeNetDiagNetwork();
        workers = new NetDiagWorkers();
        checker = mock(PortCheckerService.class);
        svc = new PortDiagnosticsService(net, workers, checker);
        svc.env = k -> null;
        net.hosts.put(HOST, List.of(FakeNetDiagNetwork.ip("192.0.2.20"), FakeNetDiagNetwork.ip("192.0.2.21")));
        when(checker.proxyDecision(anyString(), anyString(), any())).thenReturn(ProxyPolicyService.Decision.direct("monitor"));
        when(checker.proxyConnectPorts()).thenReturn(List.of(443, 8443));
        when(checker.check(any(PortMonitor.class))).thenReturn(open(true));
    }

    @AfterEach
    void tearDown() { workers.shutdown(); }

    private static PortMonitor monitor(String protocol, int port) {
        PortMonitor m = new PortMonitor();
        m.setId(11L);
        m.setName("Veritabanı");
        m.setHost(HOST);
        m.setPort(port);
        m.setProtocol(protocol);
        m.setTimeoutMs(2000);
        m.setIpVersion("auto");
        return m;
    }

    private static Map<String, Object> open(boolean open) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("open", open);
        r.put("via", "direct");
        r.put("response_ms", open ? 9L : null);
        if (!open) r.put("error", "Connection refused");
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findings(Map<String, Object> d) { return (List<Map<String, Object>>) d.get("findings"); }

    private static List<Object> codes(Map<String, Object> d) { return findings(d).stream().map(f -> f.get("code")).toList(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> verdict(Map<String, Object> d) { return (Map<String, Object>) d.get("verdict"); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> d) { return (Map<String, Object>) verdict(d).get("params"); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> step(Map<String, Object> d, String key) {
        for (Map<String, Object> s : (List<Map<String, Object>>) d.get("steps")) if (key.equals(s.get("key"))) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(Map<String, Object> d, String key) { return (Map<String, Object>) step(d, key).get("detail"); }

    private static X509Certificate cert(String cn, List<String> sans, Instant notAfter) throws Exception {
        X509Certificate c = mock(X509Certificate.class);
        when(c.getSubjectX500Principal()).thenReturn(new X500Principal("CN=" + cn));
        when(c.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test Issuing CA"));
        when(c.getNotAfter()).thenReturn(Date.from(notAfter));
        java.util.Collection<List<?>> san = new java.util.ArrayList<>();
        for (String s : sans) san.add(List.of(2, s));
        when(c.getSubjectAlternativeNames()).thenReturn(san);
        return c;
    }

    @Test
    @DisplayName("TCP açık: ilk IP'de bağlanır, PORT_OK; gerçek kontrol yalnız check(m) — başka hiçbir checker çağrısı yok")
    void tcpOk_noSideEffects() {
        net.connects.put("192.0.2.20:5432", FakeNetDiagNetwork.FakeSocket.of(""));
        net.connects.put("192.0.2.21:5432", FakeNetDiagNetwork.FakeSocket.of(""));
        Map<String, Object> d = svc.diagnose(monitor("TCP", 5432));
        assertThat(verdict(d)).containsEntry("code", "PORT_OK").containsEntry("status", "ok");
        assertThat(detail(d, "connect")).containsEntry("ip", "192.0.2.20");
        assertThat(d.get("paths")).isNull();   // vekil tanımsız → karşılaştırma yok
        verify(checker).check(any(PortMonitor.class));
        verify(checker).proxyDecision(HOST, "TCP", null);
        verify(checker, org.mockito.Mockito.atLeastOnce()).proxyConnectPorts();
        verifyNoMoreInteractions(checker);
    }

    @Test
    @DisplayName("bağlantı reddi → CONNECT_REFUSED; zaman aşımı → CONNECT_TIMEOUT_FILTERED; yol yok → NETWORK_UNREACHABLE")
    void connectFailures() {
        net.connects.put("192.0.2.20:5432", new ConnectException("Connection refused"));
        net.connects.put("192.0.2.21:5432", new ConnectException("Connection refused"));
        when(checker.check(any(PortMonitor.class))).thenReturn(open(false));
        assertThat(verdict(svc.diagnose(monitor("TCP", 5432)))).containsEntry("code", "CONNECT_REFUSED");

        net.connects.clear();   // sahte: tanımsız bağlantı = zaman aşımı
        Map<String, Object> to = svc.diagnose(monitor("TCP", 5432));
        assertThat(verdict(to)).containsEntry("code", "CONNECT_TIMEOUT_FILTERED");
        assertThat(params(to)).containsEntry("port", 5432);

        net.connects.put("192.0.2.20:5432", new NoRouteToHostException("No route to host"));
        net.connects.put("192.0.2.21:5432", new NoRouteToHostException("No route to host"));
        assertThat(verdict(svc.diagnose(monitor("TCP", 5432)))).containsEntry("code", "NETWORK_UNREACHABLE");
    }

    @Test
    @DisplayName("çok-A: bir IP kapalı, diğeri açık → kontrol geçer + SOME_IPS_DOWN (warn)")
    void someIpsDown() {
        net.connects.put("192.0.2.20:5432", new SocketTimeoutException("connect timed out"));
        net.connects.put("192.0.2.21:5432", FakeNetDiagNetwork.FakeSocket.of(""));
        Map<String, Object> d = svc.diagnose(monitor("TCP", 5432));
        assertThat(verdict(d)).containsEntry("code", "SOME_IPS_DOWN").containsEntry("status", "warn");
        assertThat(codes(d)).contains("PORT_OK");
        assertThat(detail(d, "connect")).containsEntry("ip", "192.0.2.21");
        assertThat(String.valueOf(params(d).get("ips"))).contains("192.0.2.20 (timeout)");
    }

    @Test
    @DisplayName("vekil yolu: izinsiz port → PROXY_PORT_NOT_ALLOWED, öteki (doğrudan) yol açık → PATH_DIFFERS hükmü")
    void proxyPortNotAllowed_pathDiffers() throws Exception {
        net.proxy = true;
        when(checker.proxyDecision(anyString(), anyString(), any())).thenReturn(new ProxyPolicyService.Decision(true, "monitor", true, false));
        net.tunnels.put(HOST + ":5432", new NetDiagNetwork.TunnelRefusedException("HTTP/1.1 403 Forbidden"));
        net.connects.put("192.0.2.20:5432", FakeNetDiagNetwork.FakeSocket.of(""));
        net.connects.put("192.0.2.21:5432", FakeNetDiagNetwork.FakeSocket.of(""));
        when(checker.check(any(PortMonitor.class))).thenReturn(open(false));
        Map<String, Object> d = svc.diagnose(monitor("TCP", 5432));
        assertThat(verdict(d)).containsEntry("code", "PATH_DIFFERS").containsEntry("status", "fail");
        assertThat(params(d)).containsEntry("failing_route", "proxy").containsEntry("working_route", "direct");
        assertThat(codes(d)).contains("PROXY_PORT_NOT_ALLOWED");
        @SuppressWarnings("unchecked")
        Map<String, Object> route = (Map<String, Object>) d.get("route");
        assertThat(route).containsEntry("own", "proxy").containsEntry("proxy_configured", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> paths = (List<Map<String, Object>>) d.get("paths");
        assertThat(paths).hasSize(2);
        assertThat(paths.get(0)).containsEntry("key", "monitor").containsEntry("route", "proxy").containsEntry("outcome", "fail");
        assertThat(paths.get(1)).containsEntry("key", "alternate").containsEntry("route", "direct").containsEntry("outcome", "ok");
        assertThat(detail(d, "proxy_tunnel")).containsEntry("port_allowed", false).containsEntry("status_line", "HTTP/1.1 403 Forbidden");
    }

    @Test
    @DisplayName("vekil izinli porta tüneli reddetti → PROXY_REFUSED; vekile ulaşılamadı → PROXY_UNREACHABLE; Proxy-Authorization maskeli")
    void proxyRefused_unreachable_masked() throws Exception {
        net.proxy = true;
        net.proxyAuth = true;
        net.proxySecret = SECRET;
        when(checker.proxyDecision(anyString(), anyString(), any())).thenReturn(new ProxyPolicyService.Decision(true, "monitor", true, false));
        net.tunnels.put(HOST + ":443", new NetDiagNetwork.TunnelRefusedException("HTTP/1.1 407 Proxy Authentication Required " + SECRET));
        Map<String, Object> d = svc.diagnose(monitor("TCP", 443));
        assertThat(codes(d)).contains("PROXY_REFUSED");
        String json = new ObjectMapper().writeValueAsString(d);
        assertThat(json).doesNotContain(SECRET).contains("Proxy-Authorization: ••••");

        net.tunnels.put(HOST + ":443", new ConnectException("Connection refused"));
        Map<String, Object> d2 = svc.diagnose(monitor("TCP", 443));
        assertThat(codes(d2)).contains("PROXY_UNREACHABLE");
    }

    @Test
    @DisplayName("UDP vekilden geçmez: PROXY_NOT_APPLICABLE, karşılaştırma yok; yanıt / ICMP / yanıtsız dalları")
    void udp() {
        net.proxy = true;
        net.udps.put("192.0.2.20:161", new NetDiagNetwork.UdpResult("reply", 42, null));
        Map<String, Object> ok = svc.diagnose(monitor("UDP", 161));
        assertThat(verdict(ok)).containsEntry("code", "PORT_OK");
        assertThat(codes(ok)).contains("PROXY_NOT_APPLICABLE");
        assertThat(ok.get("paths")).isNull();
        assertThat(net.calls).noneMatch(c -> c.startsWith("tunnel"));

        net.udps.put("192.0.2.20:161", new NetDiagNetwork.UdpResult("unreachable", 0, "ICMP Port Unreachable"));
        when(checker.check(any(PortMonitor.class))).thenReturn(open(false));
        assertThat(verdict(svc.diagnose(monitor("UDP", 161)))).containsEntry("code", "UDP_PORT_UNREACHABLE");

        net.udps.remove("192.0.2.20:161");
        assertThat(verdict(svc.diagnose(monitor("UDP", 161)))).containsEntry("code", "UDP_NO_REPLY");
    }

    @Test
    @DisplayName("TLS: el sıkışması başarısız → TLS_HANDSHAKE_FAILED; başarılı ama güvenilmez / ad uyuşmaz / yakında biter → warn bulgular")
    void tls() throws Exception {
        net.connects.put("192.0.2.20:8443", FakeNetDiagNetwork.FakeSocket.of(""));
        net.tls = new javax.net.ssl.SSLHandshakeException("Received fatal alert: handshake_failure");
        when(checker.check(any(PortMonitor.class))).thenReturn(open(false));
        assertThat(verdict(svc.diagnose(monitor("TLS", 8443)))).containsEntry("code", "TLS_HANDSHAKE_FAILED");

        X509Certificate leaf = cert("other.example.test", List.of("other.example.test"), Instant.now().plus(10, ChronoUnit.DAYS));
        net.tls = new NetDiagNetwork.TlsSession(null, "TLSv1.3", "TLS_AES_256_GCM_SHA384", null, new X509Certificate[]{ leaf });
        net.trust = new NetDiagNetwork.Trust(false, "PKIX path building failed");
        when(checker.check(any(PortMonitor.class))).thenReturn(open(true));
        Map<String, Object> d = svc.diagnose(monitor("TLS", 8443));
        assertThat(verdict(d)).containsEntry("status", "warn");
        assertThat(codes(d)).contains("TLS_UNTRUSTED", "TLS_HOSTNAME_MISMATCH", "CERT_EXPIRES_SOON", "PORT_OK");
        assertThat(detail(d, "tls")).containsEntry("protocol", "TLSv1.3").containsEntry("hostname_match", false)
                .containsEntry("trusted", false).containsEntry("sni", HOST);

        X509Certificate expired = cert(HOST, List.of(HOST), Instant.now().minus(3, ChronoUnit.DAYS));
        net.tls = new NetDiagNetwork.TlsSession(null, "TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", null, new X509Certificate[]{ expired });
        net.trust = new NetDiagNetwork.Trust(true, null);
        assertThat(codes(svc.diagnose(monitor("TLS", 8443)))).contains("CERT_EXPIRED").doesNotContain("TLS_HOSTNAME_MISMATCH", "TLS_UNTRUSTED");
    }

    @Test
    @DisplayName("HTTP: durum kodu beklenene uymaz → HTTP_STATUS_MISMATCH; geçersiz yanıt → HTTP_BAD_RESPONSE; istek izlemeninkiyle aynı")
    void http() {
        net.hosts.put(HOST, List.of(FakeNetDiagNetwork.ip("192.0.2.20")));
        FakeNetDiagNetwork.FakeSocket s = FakeNetDiagNetwork.FakeSocket.of("HTTP/1.1 503 Service Unavailable\r\nServer: x\r\n\r\n");
        net.connects.put("192.0.2.20:8080", s);
        when(checker.check(any(PortMonitor.class))).thenReturn(open(false));
        PortMonitor m = monitor("HTTP", 8080);
        m.setSendData("/health");
        Map<String, Object> d = svc.diagnose(m);
        assertThat(verdict(d)).containsEntry("code", "HTTP_STATUS_MISMATCH");
        assertThat(params(d)).containsEntry("status", 503).containsEntry("expected", "2xx-3xx");
        assertThat(s.written()).startsWith("GET /health HTTP/1.1\r\nHost: db.example.test:8080\r\nUser-Agent: SiteMonitor-PortCheck");

        net.connects.put("192.0.2.20:8080", FakeNetDiagNetwork.FakeSocket.of("SSH-2.0-OpenSSH_9.6\r\n"));
        assertThat(verdict(svc.diagnose(m))).containsEntry("code", "HTTP_BAD_RESPONSE");

        net.connects.put("192.0.2.20:8080", FakeNetDiagNetwork.FakeSocket.of("HTTP/1.1 204 No Content\r\n\r\n"));
        when(checker.check(any(PortMonitor.class))).thenReturn(open(true));
        assertThat(verdict(svc.diagnose(m))).containsEntry("code", "PORT_OK");
    }

    @Test
    @DisplayName("BANNER: beklenen metin yok → BANNER_MISMATCH; hiç veri yok → BANNER_EMPTY; eşleşme → PORT_OK + önizlemeler")
    void banner() {
        net.hosts.put(HOST, List.of(FakeNetDiagNetwork.ip("192.0.2.20")));
        PortMonitor m = monitor("BANNER", 25);
        m.setExpect("220");
        m.setSendData("EHLO diag.example.test\\r\\n");
        FakeNetDiagNetwork.FakeSocket s = FakeNetDiagNetwork.FakeSocket.of("554 5.7.1 Access denied\r\n");
        net.connects.put("192.0.2.20:25", s);
        when(checker.check(any(PortMonitor.class))).thenReturn(open(false));
        Map<String, Object> d = svc.diagnose(m);
        assertThat(verdict(d)).containsEntry("code", "BANNER_MISMATCH");
        assertThat(params(d)).containsEntry("expected", "220");
        assertThat(s.written()).isEqualTo("EHLO diag.example.test\r\n");
        assertThat(detail(d, "banner")).containsEntry("received_bytes", 25).containsEntry("matched", false);
        assertThat(String.valueOf(detail(d, "banner").get("hex_preview"))).startsWith("35 35 34");

        net.connects.put("192.0.2.20:25", new FakeNetDiagNetwork.SilentSocket());
        assertThat(verdict(svc.diagnose(m))).containsEntry("code", "BANNER_EMPTY");

        net.connects.put("192.0.2.20:25", FakeNetDiagNetwork.FakeSocket.of("220 mail.example.test ESMTP ready\r\n"));
        when(checker.check(any(PortMonitor.class))).thenReturn(open(true));
        Map<String, Object> ok = svc.diagnose(m);
        assertThat(verdict(ok)).containsEntry("code", "PORT_OK");
        assertThat(String.valueOf(detail(ok, "banner").get("text_preview"))).startsWith("220 mail.example.test");
    }

    @Test
    @DisplayName("yavaş yanıt eşiği açıkken eşik aşılırsa SLOW_RESPONSE (warn)")
    void slowResponse() {
        net.hosts.put(HOST, List.of(FakeNetDiagNetwork.ip("192.0.2.20")));
        PortMonitor m = monitor("BANNER", 25);
        m.setSlowResponseEnabled(true);
        m.setSlowThresholdMs(0);
        // eşik 0 ms; okuma 5 ms bekler → ölçülen süre eşiği kesin aşar (0 ms'lik koşu bulguyu üretmezdi)
        net.connects.put("192.0.2.20:25", new SlowSocket("220 ready\r\n"));
        Map<String, Object> d = svc.diagnose(m);
        assertThat(codes(d)).contains("SLOW_RESPONSE", "PORT_OK");
    }

    @Test
    @DisplayName("politika reddi → hiçbir bağlantı yok, gerçek kontrol çağrılmaz")
    void policyBlocked() {
        net.blocked.put(HOST, "izin verilmeyen hedef " + HOST + " → 127.0.0.1 (loopback/any-local)");
        Map<String, Object> d = svc.diagnose(monitor("TCP", 5432));
        assertThat(verdict(d)).containsEntry("code", "POLICY_BLOCKED");
        assertThat(net.calls).noneMatch(c -> c.startsWith("connect") || c.startsWith("tunnel") || c.startsWith("udp"));
        verify(checker, org.mockito.Mockito.never()).check(any(PortMonitor.class));
    }

    @Test
    @DisplayName("ad çözümlenemedi → DNS_FAILED; izlemenin kontrolü gibi vekil yolunda da tünel denenmez")
    void unresolvable_evenViaProxy() {
        net.proxy = true;
        when(checker.proxyDecision(anyString(), anyString(), any())).thenReturn(new ProxyPolicyService.Decision(true, "monitor", true, false));
        net.hosts.remove(HOST);
        Map<String, Object> d = svc.diagnose(monitor("TCP", 443));
        assertThat(verdict(d)).containsEntry("code", "DNS_FAILED").containsEntry("status", "fail");
        assertThat(step(d, "proxy_tunnel")).containsEntry("status", "skip");
        assertThat(net.calls).noneMatch(c -> c.startsWith("tunnel") || c.startsWith("connect"));
        verify(checker, org.mockito.Mockito.never()).check(any(PortMonitor.class));
    }

    @Test
    @DisplayName("gerçek kontrol tanılamayla uyuşmazsa CLIENT_MISMATCH")
    void clientMismatch() {
        net.connects.put("192.0.2.20:5432", FakeNetDiagNetwork.FakeSocket.of(""));
        when(checker.check(any(PortMonitor.class))).thenReturn(open(false));
        assertThat(codes(svc.diagnose(monitor("TCP", 5432)))).contains("CLIENT_MISMATCH");
    }

    /** Okuma 5 ms bekleyen soket (SLOW_RESPONSE için ölçülebilir süre). */
    static final class SlowSocket extends java.net.Socket {
        private final byte[] data;
        SlowSocket(String s) { this.data = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1); }
        @Override public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(data) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    try { Thread.sleep(5); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
                    return super.read(b, off, len);
                }
            };
        }
        @Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        @Override public synchronized void setSoTimeout(int t) { /* sahte */ }
        @Override public synchronized void close() throws IOException { /* sahte */ }
    }
}
