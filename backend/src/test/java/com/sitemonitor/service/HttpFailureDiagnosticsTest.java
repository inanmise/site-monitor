package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP hata tanısı (2026-09-22): istisna → evre/tür, iz → kaynak/hedef/bekleme, JSON sözleşmesi. */
class HttpFailureDiagnosticsTest {

    private static HttpFailureDiagnostics.Trace trace() {
        return new HttpFailureDiagnostics.Trace().start("https://www.example.com:8443/health", "GET", 5000, true, true, "direct", null);
    }

    @Test
    @DisplayName("sınıflandırma: bağlantı zaman aşımı / yanıt zaman aşımı / DNS / reddedildi / TLS güven / hostname / reset / SSRF / yönlendirme")
    void classify() {
        assertThat(HttpFailureDiagnostics.classify(new java.net.http.HttpConnectTimeoutException("HTTP connect timed out")))
                .isEqualTo(HttpFailureDiagnostics.Kind.CONNECT_TIMEOUT);
        assertThat(HttpFailureDiagnostics.classify(new java.net.http.HttpTimeoutException("request timed out")))
                .isEqualTo(HttpFailureDiagnostics.Kind.RESPONSE_TIMEOUT);
        assertThat(HttpFailureDiagnostics.classify(new java.net.UnknownHostException("nope.example.com")))
                .isEqualTo(HttpFailureDiagnostics.Kind.DNS_UNRESOLVED);
        assertThat(HttpFailureDiagnostics.classify(new java.net.ConnectException("Connection refused")))
                .isEqualTo(HttpFailureDiagnostics.Kind.CONNECT_REFUSED);
        assertThat(HttpFailureDiagnostics.classify(new java.io.IOException("wrap",
                new javax.net.ssl.SSLHandshakeException("PKIX path building failed: unable to find valid certification path"))))
                .isEqualTo(HttpFailureDiagnostics.Kind.TLS_CERT_UNTRUSTED);
        assertThat(HttpFailureDiagnostics.classify(new javax.net.ssl.SSLHandshakeException("No subject alternative DNS name matching a.example.com found")))
                .isEqualTo(HttpFailureDiagnostics.Kind.TLS_HOSTNAME_MISMATCH);
        assertThat(HttpFailureDiagnostics.classify(new javax.net.ssl.SSLHandshakeException("(certificate_unknown) No name matching a.example.com found")))
                .isEqualTo(HttpFailureDiagnostics.Kind.TLS_HOSTNAME_MISMATCH);   // JDK sarmalı: ad uyuşmazlığı güven hatasından önce
        assertThat(HttpFailureDiagnostics.classify(new javax.net.ssl.SSLHandshakeException("Received fatal alert: handshake_failure")))
                .isEqualTo(HttpFailureDiagnostics.Kind.TLS_HANDSHAKE);
        assertThat(HttpFailureDiagnostics.classify(new java.net.SocketException("Connection reset")))
                .isEqualTo(HttpFailureDiagnostics.Kind.CONNECTION_RESET);
        assertThat(HttpFailureDiagnostics.classify(new java.io.IOException("HTTP/1.1 header parser received no bytes")))
                .isEqualTo(HttpFailureDiagnostics.Kind.CONNECTION_CLOSED);
        assertThat(HttpFailureDiagnostics.classify(new SsrfGuard.BlockedException("blocked")))
                .isEqualTo(HttpFailureDiagnostics.Kind.SSRF_BLOCKED);
        assertThat(HttpFailureDiagnostics.classify(new java.io.IOException("çok fazla yönlendirme (5 hop aşıldı)")))
                .isEqualTo(HttpFailureDiagnostics.Kind.TOO_MANY_REDIRECTS);
        assertThat(HttpFailureDiagnostics.classify(new java.io.IOException("Tunnel failed, got: 407")))
                .isEqualTo(HttpFailureDiagnostics.Kind.PROXY_AUTH);
        assertThat(HttpFailureDiagnostics.classify(new java.net.ConnectException("Connection refused: proxy")))
                .isEqualTo(HttpFailureDiagnostics.Kind.PROXY_CONNECT);
        assertThat(HttpFailureDiagnostics.classify(new RuntimeException("boom")))
                .isEqualTo(HttpFailureDiagnostics.Kind.UNKNOWN);
        assertThat(HttpFailureDiagnostics.classify(null)).isEqualTo(HttpFailureDiagnostics.Kind.UNKNOWN);
    }

    @Test
    @DisplayName("evre eşlemesi türden türer: CONNECT_TIMEOUT→CONNECT, RESPONSE_TIMEOUT→RESPONSE, DNS→DNS, TLS→TLS, STATUS_MISMATCH→RESPONSE")
    void phases() {
        assertThat(HttpFailureDiagnostics.Kind.CONNECT_TIMEOUT.phase).isEqualTo(HttpFailureDiagnostics.Phase.CONNECT);
        assertThat(HttpFailureDiagnostics.Kind.RESPONSE_TIMEOUT.phase).isEqualTo(HttpFailureDiagnostics.Phase.RESPONSE);
        assertThat(HttpFailureDiagnostics.Kind.DNS_UNRESOLVED.phase).isEqualTo(HttpFailureDiagnostics.Phase.DNS);
        assertThat(HttpFailureDiagnostics.Kind.TLS_CERT_UNTRUSTED.phase).isEqualTo(HttpFailureDiagnostics.Phase.TLS);
        assertThat(HttpFailureDiagnostics.Kind.STATUS_MISMATCH.phase).isEqualTo(HttpFailureDiagnostics.Phase.RESPONSE);
        assertThat(HttpFailureDiagnostics.Kind.SSRF_BLOCKED.phase).isEqualTo(HttpFailureDiagnostics.Phase.POLICY);
    }

    @Test
    @DisplayName("iz: URL'den şema/host/port; çözümleme ilk IP'yi hedef yapar, pin onu ezer; yönlendirme zinciri tavanlı")
    void traceCollects() throws Exception {
        HttpFailureDiagnostics.Trace tr = trace();
        assertThat(tr.scheme).isEqualTo("https");
        assertThat(tr.host).isEqualTo("www.example.com");
        assertThat(tr.port).isEqualTo(8443);
        tr.resolved(List.of(InetAddress.getByName("192.0.2.10"), InetAddress.getByName("192.0.2.11")), 12);
        assertThat(tr.resolvedIps).containsExactly("192.0.2.10", "192.0.2.11");
        assertThat(tr.targetIp).isEqualTo("192.0.2.10");
        assertThat(tr.dnsMs).isEqualTo(12);
        tr.pinned(InetAddress.getByName("192.0.2.11"));
        assertThat(tr.targetIp).isEqualTo("192.0.2.11");
        for (int i = 0; i < 20; i++) tr.hop(URI.create("https://www.example.com/r" + i));
        assertThat(tr.redirects).hasSizeLessThanOrEqualTo(SafeRedirect.MAX_HOPS + 2);
        // Varsayılan port: https → 443, http → 80
        assertThat(new HttpFailureDiagnostics.Trace().start("http://a.example.com/", "GET", 1000, false, false, "direct", null).port).isEqualTo(80);
        assertThat(new HttpFailureDiagnostics.Trace().start("https://a.example.com/", "GET", 1000, false, false, "direct", null).port).isEqualTo(443);
    }

    @Test
    @DisplayName("forException: tür/evre + kaynak/hedef/port + zaman aşımı/bekleme + istisna zinciri (en fazla 6 halka); JSON'a çevrilir")
    void forException() throws Exception {
        HttpFailureDiagnostics.Trace tr = trace();
        tr.resolved(List.of(InetAddress.getByName("127.0.0.1")), 3);
        tr.elapsedMs = 5004;
        Throwable deep = new RuntimeException("l0");
        for (int i = 1; i < 10; i++) deep = new RuntimeException("l" + i, deep);
        Map<String, Object> d = HttpFailureDiagnostics.forException(tr, new java.net.http.HttpConnectTimeoutException("HTTP connect timed out"));
        assertThat(d).containsEntry("kind", "CONNECT_TIMEOUT").containsEntry("phase", "CONNECT")
                .containsEntry("host", "www.example.com").containsEntry("port", 8443)
                .containsEntry("target_ip", "127.0.0.1").containsEntry("timeout_ms", 5000).containsEntry("elapsed_ms", 5004L)
                .containsEntry("via", "direct").containsEntry("exception", "java.net.http.HttpConnectTimeoutException");
        // loopback hedefe çıkış arayüzü çekirdekten sorulur — paket gönderilmez; sonuç boş olabilir ama patlamaz
        assertThat(d).containsKey("local_ip");
        assertThat(HttpFailureDiagnostics.causeChain(deep)).hasSize(HttpFailureDiagnostics.MAX_CAUSES);
        String json = HttpFailureDiagnostics.toJson(d);
        assertThat(json).contains("\"kind\":\"CONNECT_TIMEOUT\"").contains("\"resolved_ips\":[\"127.0.0.1\"]");
        assertThat(HttpFailureDiagnostics.toJson(null)).isNull();
    }

    @Test
    @DisplayName("forStatusMismatch: beklenen/gelen kod + yönlendirme zinciri; vekil yolunda TCP hedefi vekildir")
    void statusMismatchAndProxy() {
        HttpFailureDiagnostics.Trace tr = new HttpFailureDiagnostics.Trace().start("https://www.example.com/", "HEAD", 3000, false, true, "proxy", "proxy.example.net:8080");
        tr.expectedStatus = "200"; tr.httpStatus = 503; tr.elapsedMs = 120;
        tr.hop(URI.create("https://www.example.com/login"));
        Map<String, Object> d = HttpFailureDiagnostics.forStatusMismatch(tr);
        assertThat(d).containsEntry("kind", "STATUS_MISMATCH").containsEntry("expected_status", "200").containsEntry("http_status", 503)
                .containsEntry("via", "proxy").containsEntry("proxy", "proxy.example.net:8080");
        assertThat((List<?>) d.get("redirects")).isEqualTo(List.of("https://www.example.com/login"));
        assertThat(d).doesNotContainKey("exception");
    }

    @Test
    @DisplayName("localIpFor: boş/çözülemeyen hedefte null, patlamaz")
    void localIp() {
        assertThat(HttpFailureDiagnostics.localIpFor(null, 443)).isNull();
        assertThat(HttpFailureDiagnostics.localIpFor("", 443)).isNull();
        assertThat(HttpFailureDiagnostics.localIpFor(TestHosts.UNRESOLVABLE, 443)).isNull();
    }
}
