package com.certmonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CertificateCheckerServiceTest {

    @Mock
    private ChainValidationService chainValidationService;

    @Mock
    private DnsCheckerService dnsCheckerService;

    private CertificateCheckerService service;

    @BeforeEach
    void setUp() {
        service = new CertificateCheckerService(chainValidationService, dnsCheckerService, new ObjectMapper());
    }

    // ── SAN serialization ──────────────────────────────────────────────────────

    @Test
    @DisplayName("serializeSan and deserializeSan round-trip")
    void serializeSan_deserializeSan_roundTrip() {
        List<String> san = List.of("example.com", "www.example.com", "*.example.com");
        String json = service.serializeSan(san);
        List<String> result = service.deserializeSan(json);
        assertThat(result).containsExactlyElementsOf(san);
    }

    @Test
    @DisplayName("serializeSan with empty list returns '[]'")
    void serializeSan_emptyList_returnsEmptyJson() {
        String json = service.serializeSan(List.of());
        assertThat(json).isEqualTo("[]");
    }

    @Test
    @DisplayName("deserializeSan with null input returns empty list")
    void deserializeSan_nullInput_returnsEmpty() {
        assertThat(service.deserializeSan(null)).isEmpty();
    }

    @Test
    @DisplayName("deserializeSan with blank input returns empty list")
    void deserializeSan_blankInput_returnsEmpty() {
        assertThat(service.deserializeSan("   ")).isEmpty();
    }

    @Test
    @DisplayName("deserializeSan with invalid JSON returns empty list")
    void deserializeSan_invalidJson_returnsEmpty() {
        assertThat(service.deserializeSan("{not-valid-json}")).isEmpty();
    }

    @Test
    @DisplayName("deserializeSan with single-entry JSON list")
    void deserializeSan_singleEntry_returnsList() {
        List<String> result = service.deserializeSan("[\"example.com\"]");
        assertThat(result).containsExactly("example.com");
    }

    // ── Error check path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("check with unreachable domain returns error map with required keys")
    void check_unreachableDomain_returnsErrorMap() {
        // .invalid is RFC 2606 reserved, DNS lookup fails immediately
        Map<String, Object> result = service.check("host.invalid", 443);

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("domain")).isEqualTo("host.invalid");
        assertThat(result.get("error")).isNotNull();
        assertThat(result.get("warning")).isEqualTo(true);
        assertThat(result.get("chain_status")).isEqualTo("UNKNOWN");
        assertThat(result.get("revocation_status")).isEqualTo("UNKNOWN");
        assertThat(result.get("deployment_status")).isEqualTo("UNKNOWN");
        assertThat(result.get("fingerprint")).isNull();
        assertThat(result.get("checked_at")).isNotNull();
    }

    @Test
    @DisplayName("check always returns san key (empty list on error)")
    void check_error_sanIsEmptyList() {
        Map<String, Object> result = service.check("host.invalid", 443);
        Object san = result.get("san");
        assertThat(san).isNotNull().isInstanceOf(List.class);
    }

    @Test
    @DisplayName("check with connection refused returns error map")
    void check_connectionRefused_returnsErrorMap() {
        // Port 1 is almost certainly not open on localhost
        Map<String, Object> result = service.check("localhost", 1);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat((String) result.get("error")).isNotBlank();
    }

    // ── Retry-on-transient ─────────────────────────────────────────────────────

    /** Helper: spy on service with retry enabled and stub tryCheckOnce. */
    private CertificateCheckerService spyWithRetry(boolean enabled) {
        CertificateCheckerService spy = spy(service);
        ReflectionTestUtils.setField(spy, "retryOnTransient", enabled);
        ReflectionTestUtils.setField(spy, "maxAttempts", 2);
        ReflectionTestUtils.setField(spy, "retryDelayMs", 5L);
        return spy;
    }

    private static Map<String, Object> err(String cls, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "error");
        m.put("error_class", cls);
        m.put("error", msg);
        m.put("domain", "test.example.com");
        return m;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "ok");
        m.put("domain", "test.example.com");
        m.put("days_remaining", 100);
        return m;
    }

    @Test
    @DisplayName("check retries transient NETWORK error and reports recovery on success")
    void check_transientNetworkError_retriesAndRecovers() {
        CertificateCheckerService spy = spyWithRetry(true);
        doReturn(err("NETWORK", "Socket error: Connection reset"))
                .doReturn(ok())
                .when(spy).tryCheckOnce("test.example.com", 443, false);

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(2)).tryCheckOnce("test.example.com", 443, false);
        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("retry_recovered")).isEqualTo(true);
    }

    @Test
    @DisplayName("check retries persistent NETWORK error and marks retry_attempted")
    void check_persistentNetworkError_retriesThenFails() {
        CertificateCheckerService spy = spyWithRetry(true);
        doReturn(err("NETWORK", "Socket error: Connection reset"))
                .when(spy).tryCheckOnce("test.example.com", 443, false);

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(2)).tryCheckOnce("test.example.com", 443, false);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("retry_attempted")).isEqualTo(true);
    }

    @Test
    @DisplayName("check does NOT retry SSL handshake errors (real cert problem)")
    void check_sslHandshakeError_noRetry() {
        CertificateCheckerService spy = spyWithRetry(true);
        doReturn(err("SSL", "SSL handshake: unable to find valid certification path"))
                .when(spy).tryCheckOnce("test.example.com", 443, false);

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(1)).tryCheckOnce("test.example.com", 443, false);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result).doesNotContainKey("retry_attempted");
        assertThat(result).doesNotContainKey("retry_recovered");
    }

    @Test
    @DisplayName("check does NOT retry DNS errors (won't fix in 1s)")
    void check_dnsError_noRetry() {
        CertificateCheckerService spy = spyWithRetry(true);
        doReturn(err("DNS", "Domain resolution failed"))
                .when(spy).tryCheckOnce("test.example.com", 443, false);

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(1)).tryCheckOnce("test.example.com", 443, false);
        assertThat(result.get("status")).isEqualTo("error");
    }

    @Test
    @DisplayName("check with retry disabled via config makes only one attempt")
    void check_retryDisabled_singleAttempt() {
        CertificateCheckerService spy = spyWithRetry(false);
        doReturn(err("NETWORK", "Socket error: Connection reset"))
                .when(spy).tryCheckOnce("test.example.com", 443, false);

        Map<String, Object> result = spy.check("test.example.com", 443);

        verify(spy, times(1)).tryCheckOnce("test.example.com", 443, false);
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result).doesNotContainKey("retry_attempted");
    }

    @Test
    @DisplayName("isTransientError flags Connection reset / timeout / refused, ignores SSL+DNS")
    void isTransientError_classifiesCorrectly() {
        assertThat(service.isTransientError(err("NETWORK", "Socket error: Connection reset"))).isTrue();
        assertThat(service.isTransientError(err("NETWORK", "Connection timeout after 6s"))).isTrue();
        assertThat(service.isTransientError(err("NETWORK", "Connection refused/unreachable"))).isTrue();
        assertThat(service.isTransientError(err("NETWORK", "No route to host"))).isTrue();

        assertThat(service.isTransientError(err("SSL", "SSL handshake: anything"))).isFalse();
        assertThat(service.isTransientError(err("DNS", "Domain resolution failed"))).isFalse();
        assertThat(service.isTransientError(err("UNKNOWN", "Some weird failure"))).isFalse();

        Map<String, Object> okResult = ok();
        assertThat(service.isTransientError(okResult)).isFalse();
    }
}
