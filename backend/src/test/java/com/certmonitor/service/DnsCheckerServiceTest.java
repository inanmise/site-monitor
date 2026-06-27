package com.certmonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DnsCheckerService}.
 *
 * The service does live DNS lookups via JNDI. Test environments behave wildly
 * differently here: some CI runners have ISP wildcard DNS that "resolves"
 * anything to a captive portal, .invalid is sometimes hijacked, etc. So we
 * assert against the *contract* the rest of the app relies on -- the shape
 * of the returned map -- without making promises about the actual live
 * resolution result.
 */
class DnsCheckerServiceTest {

    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final DnsCheckerService service = new DnsCheckerService(appSettings);

    @BeforeEach
    void stubSettings() {
        // getInt(key, fallback) → fallback (ör. query-timeout-ms = 2000)
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    @DisplayName("check returns a result map with success/values keys")
    void check_returnsExpectedShape() {
        Map<String, Object> r = service.check("example.com", "A");
        assertThat(r).containsKeys("success", "values");
        assertThat(r.get("values")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("success=false branch always carries an error key")
    void check_failureCarriesErrorMessage() {
        // Try several inputs that should fail in most environments. Even if one of
        // them happens to resolve, at least one is virtually guaranteed to fail.
        Map<String, Object> r1 = service.check("", "A");
        Map<String, Object> r2 = service.check("totally-bogus-host-cert-monitor-test.zzz", "AAAA");
        Map<String, Object> r3 = service.check("example.com", "FAKE-RECORD-TYPE");
        boolean anyFailed = false;
        for (Map<String, Object> r : new Map[]{ r1, r2, r3 }) {
            if (Boolean.FALSE.equals(r.get("success"))) {
                assertThat(r).containsKey("error");
                assertThat((List<?>) r.get("values")).isEmpty();
                anyFailed = true;
            }
        }
        assertThat(anyFailed)
            .as("at least one of the bogus inputs should fail; if all succeed your DNS resolver is hijacked")
            .isTrue();
    }

    @Test
    @DisplayName("values list is never null, regardless of outcome")
    void check_valuesListNeverNull() {
        assertThat(service.check("example.com", "A").get("values")).isNotNull();
        assertThat(service.check("",            "A").get("values")).isNotNull();
        assertThat(service.check("a.b.c.d.e.f", "AAAA").get("values")).isNotNull();
    }

    @Test
    @DisplayName("check does not throw on edge-case input")
    void check_doesNotThrowOnEdgeInput() {
        // Each of these must come back as a result map, not a thrown exception.
        service.check(null, "A");
        service.check("example.com", null);
        service.check("\t\t  \n", "A");
    }

    @Test
    @DisplayName("check captures response_ms regardless of success")
    void check_responseMsIsCaptured() {
        Map<String, Object> ok = service.check("example.com", "A");
        Map<String, Object> bad = service.check("totally-bogus-host-cert-monitor-test.zzz", "A");
        assertThat(ok).containsKey("response_ms");
        assertThat(bad).containsKey("response_ms");
        assertThat(ok.get("response_ms")).isInstanceOf(Long.class);
        assertThat((Long) ok.get("response_ms")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("check carries ttl key (Long or null)")
    void check_ttlKeyAlwaysPresent() {
        Map<String, Object> r = service.check("example.com", "A");
        assertThat(r).containsKey("ttl");
        Object ttl = r.get("ttl");
        // ttl is either a Long (success path) or null (failure path) — never a String/Integer
        assertThat(ttl == null || ttl instanceof Long).isTrue();
    }

    @Test
    @DisplayName("enrichedQuery returns all standard record types and soa key")
    void enrichedQuery_shape() {
        Map<String, Object> data = service.enrichedQuery("example.com");
        assertThat(data).containsKeys("records", "soa", "authoritative_servers");
        @SuppressWarnings("unchecked")
        Map<String, Object> records = (Map<String, Object>) data.get("records");
        assertThat(records).containsKeys("A", "AAAA", "CNAME", "MX", "TXT", "NS");
    }

    // ── detectChange — round-robin vs real change ────────────────────────────

    @Test
    @DisplayName("detectChange: prev == null is NONE (first ever check)")
    void detectChange_firstCheck_isNone() {
        assertThat(DnsCheckerService.detectChange(null, "1.2.3.4"))
            .isEqualTo(DnsCheckerService.ChangeKind.NONE);
    }

    @Test
    @DisplayName("detectChange: identical strings is NONE")
    void detectChange_identical_isNone() {
        assertThat(DnsCheckerService.detectChange("1.2.3.4\n5.6.7.8", "1.2.3.4\n5.6.7.8"))
            .isEqualTo(DnsCheckerService.ChangeKind.NONE);
    }

    @Test
    @DisplayName("detectChange: same set in different order is NONE")
    void detectChange_sameSetReordered_isNone() {
        assertThat(DnsCheckerService.detectChange("1.2.3.4\n5.6.7.8", "5.6.7.8\n1.2.3.4"))
            .isEqualTo(DnsCheckerService.ChangeKind.NONE);
    }

    @Test
    @DisplayName("detectChange: subset overlap is ROTATED (CDN edge rotation)")
    void detectChange_subsetOverlap_isRotated() {
        // Akbank example: prev had two edge IPs, now returns one of them
        assertThat(DnsCheckerService.detectChange(
                "217.169.192.73\n217.169.204.113",
                "217.169.204.113"))
            .isEqualTo(DnsCheckerService.ChangeKind.ROTATED);
    }

    @Test
    @DisplayName("detectChange: new IP added with one shared is ROTATED")
    void detectChange_partialOverlap_isRotated() {
        assertThat(DnsCheckerService.detectChange(
                "1.2.3.4",
                "1.2.3.4\n9.9.9.9"))
            .isEqualTo(DnsCheckerService.ChangeKind.ROTATED);
    }

    @Test
    @DisplayName("unexpectedValues: beklenmeyen değer raporlanır; rotasyon (alt küme) tolere edilir")
    void unexpectedValues_flexible() {
        // beklenmeyen yeni değer → raporla
        assertThat(DnsCheckerService.unexpectedValues("1.2.3.4\n5.6.7.8", List.of("9.9.9.9")))
            .containsExactly("9.9.9.9");
        // rotasyon: canlı = beklenenin alt kümesi → sapma yok
        assertThat(DnsCheckerService.unexpectedValues("1.2.3.4\n5.6.7.8", List.of("1.2.3.4")))
            .isEmpty();
        // beklenen boş/null = kilit kapalı → boş
        assertThat(DnsCheckerService.unexpectedValues("", List.of("9.9.9.9"))).isEmpty();
        assertThat(DnsCheckerService.unexpectedValues(null, List.of("9.9.9.9"))).isEmpty();
        // karışık: bir beklenen + bir beklenmeyen → yalnız beklenmeyeni raporla
        assertThat(DnsCheckerService.unexpectedValues("1.2.3.4", List.of("1.2.3.4", "9.9.9.9")))
            .containsExactly("9.9.9.9");
    }

    @Test
    @DisplayName("detectChange: disjoint sets is CHANGED (real change)")
    void detectChange_disjoint_isChanged() {
        assertThat(DnsCheckerService.detectChange("1.2.3.4", "9.9.9.9"))
            .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
        assertThat(DnsCheckerService.detectChange(
                "217.169.192.73\n217.169.204.113",
                "5.5.5.5\n6.6.6.6"))
            .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
    }
}
