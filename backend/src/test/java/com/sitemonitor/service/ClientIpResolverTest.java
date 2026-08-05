package com.sitemonitor.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private ClientIpResolver resolver(String[] headers, int index) {
        ClientIpResolver r = new ClientIpResolver();
        ReflectionTestUtils.setField(r, "headers", headers);
        ReflectionTestUtils.setField(r, "index", index);
        return r;
    }

    @Test
    void leftmostXffByDefault() {
        ClientIpResolver r = resolver(new String[]{"X-Forwarded-For"}, 0);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "10.218.204.187, 172.21.116.251");
        req.setRemoteAddr("172.21.116.251");
        assertThat(r.resolve(req)).isEqualTo("10.218.204.187");
    }

    @Test
    void prefersConfiguredHeaderOrder() {
        // XFF proxy IP'si taşısa bile, sıralı listede önce gelen X-Real-IP gerçek client'ı verir
        ClientIpResolver r = resolver(new String[]{"X-Real-IP", "X-Forwarded-For"}, 0);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "172.21.116.251");
        req.addHeader("X-Real-IP", "10.218.204.187");
        assertThat(r.resolve(req)).isEqualTo("10.218.204.187");
    }

    @Test
    void fallsBackToRemoteAddrWhenNoConfiguredHeader() {
        ClientIpResolver r = resolver(new String[]{"X-Forwarded-For"}, 0);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");
        assertThat(r.resolve(req)).isEqualTo("10.0.0.5");
    }

    @Test
    void normalizesIpv4MappedIpv6() {
        ClientIpResolver r = resolver(new String[]{"X-Forwarded-For"}, 0);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("::ffff:10.0.0.5");
        assertThat(r.resolve(req)).isEqualTo("10.0.0.5");
    }

    @Test
    void negativeIndexPicksTrustedHopFromRight() {
        // İstemci en sol XFF'i spoof edebilir; -1 = en sağ = güvenilir proxy'nin eklediği değer.
        ClientIpResolver r = resolver(new String[]{"X-Forwarded-For"}, -1);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.9");
        req.setRemoteAddr("10.0.0.9");
        assertThat(r.resolve(req)).isEqualTo("10.0.0.9");
    }

    @Test
    void outOfBoundsIndexFallsBackToRemoteAddr() {
        // Saldırgan kısa/spoof XFF gönderirse sınır-dışı index spoof'a düşmemeli → remoteAddr.
        ClientIpResolver r = resolver(new String[]{"X-Forwarded-For"}, 5);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        req.setRemoteAddr("10.0.0.9");
        assertThat(r.resolve(req)).isEqualTo("10.0.0.9");
    }

    @Test
    void nullRequestReturnsUnknown() {
        assertThat(resolver(new String[]{"X-Forwarded-For"}, 0).resolve(null)).isEqualTo("unknown");
    }

    @Test
    void debugInfoExposesHeadersAndResolved() {
        ClientIpResolver r = resolver(new String[]{"X-Forwarded-For"}, 0);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "10.218.204.187");
        req.setRemoteAddr("172.21.116.251");
        var info = r.debugInfo(req);
        assertThat(info.get("resolved")).isEqualTo("10.218.204.187");
        assertThat(info.get("remote_addr")).isEqualTo("172.21.116.251");
    }
}
