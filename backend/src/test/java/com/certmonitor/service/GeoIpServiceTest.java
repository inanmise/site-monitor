package com.certmonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class GeoIpServiceTest {

    private GeoIpService service;

    @BeforeEach
    void setUp() {
        service = new GeoIpService();
        ReflectionTestUtils.setField(service, "apiUrl", "http://test/%s");
        ReflectionTestUtils.setField(service, "timeoutMillis", 500L);
        ReflectionTestUtils.setField(service, "cacheTtlMs", 3_600_000L);
        service.init();
    }

    // ── isPrivateIp ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("127.0.0.1 → private")
    void isPrivateIp_loopback_returnsTrue() {
        assertThat(service.isPrivateIp("127.0.0.1")).isTrue();
    }

    @Test
    @DisplayName("::1 → private")
    void isPrivateIp_ipv6Loopback_returnsTrue() {
        assertThat(service.isPrivateIp("::1")).isTrue();
    }

    @Test
    @DisplayName("10.0.0.1 → private")
    void isPrivateIp_tenDotRange_returnsTrue() {
        assertThat(service.isPrivateIp("10.0.0.1")).isTrue();
    }

    @Test
    @DisplayName("192.168.1.1 → private")
    void isPrivateIp_192168Range_returnsTrue() {
        assertThat(service.isPrivateIp("192.168.1.1")).isTrue();
    }

    @Test
    @DisplayName("172.16.0.1 → private")
    void isPrivateIp_172_16Range_returnsTrue() {
        assertThat(service.isPrivateIp("172.16.0.1")).isTrue();
    }

    @Test
    @DisplayName("172.31.255.255 → private")
    void isPrivateIp_172_31Range_returnsTrue() {
        assertThat(service.isPrivateIp("172.31.255.255")).isTrue();
    }

    @Test
    @DisplayName("172.32.0.1 → NOT private (outside 16–31 range)")
    void isPrivateIp_172_32Range_returnsFalse() {
        assertThat(service.isPrivateIp("172.32.0.1")).isFalse();
    }

    @Test
    @DisplayName("8.8.8.8 → not private")
    void isPrivateIp_publicIp_returnsFalse() {
        assertThat(service.isPrivateIp("8.8.8.8")).isFalse();
    }

    @Test
    @DisplayName("null → treated as private")
    void isPrivateIp_nullInput_returnsTrue() {
        assertThat(service.isPrivateIp(null)).isTrue();
    }

    @Test
    @DisplayName("empty string → treated as private")
    void isPrivateIp_emptyInput_returnsTrue() {
        assertThat(service.isPrivateIp("")).isTrue();
    }

    // ── lookup (private IP) ───────────────────────────────────────────────────

    @Test
    @DisplayName("lookup of private IP → returns GeoInfo(Private, LAN, Internal) without HTTP call")
    void lookup_privateIp_returnsPrivateGeoInfo() {
        GeoIpService.GeoInfo info = service.lookup("127.0.0.1");
        assertThat(info.country()).isEqualTo("Private");
        assertThat(info.city()).isEqualTo("LAN");
        assertThat(info.org()).isEqualTo("Internal");
    }
}
