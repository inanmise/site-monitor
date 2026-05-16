package com.certmonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CertificateCheckerServiceTest {

    @Mock
    private ChainValidationService chainValidationService;

    private CertificateCheckerService service;

    @BeforeEach
    void setUp() {
        service = new CertificateCheckerService(chainValidationService, new ObjectMapper());
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
}
