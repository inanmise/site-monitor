package com.certmonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    private final DnsCheckerService service = new DnsCheckerService();

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
}
