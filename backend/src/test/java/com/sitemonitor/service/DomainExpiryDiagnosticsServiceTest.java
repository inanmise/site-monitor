package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orkestratör: mock RDAP/WHOIS trace'lerinden doğru overall (source/expiry/days) + adım sırası. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DomainExpiryDiagnosticsServiceTest {

    @Mock RdapDomainClient rdap;
    @Mock WhoisDomainClient whois;
    @Mock PublicSuffixService psl;

    private DomainExpiryDiagnosticsService svc;

    @BeforeEach
    void setUp() {
        svc = new DomainExpiryDiagnosticsService(rdap, whois, psl);
        when(psl.registrableDomain("example.com")).thenReturn("example.com");
        when(psl.tldOf("example.com")).thenReturn("com");
    }

    private Map<String, Object> step(String name, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", name);
        m.put("status", status);
        return m;
    }

    @Test
    @DisplayName("Registry başarılı → source=RDAP_REGISTRY, WHOIS atlanır")
    void registrySuccess() {
        List<Map<String, Object>> steps = new ArrayList<>(List.of(
                step("IANA_BOOTSTRAP", "ok"), step("RDAP_REGISTRY", "ok")));
        Map<String, Object> rdapResult = new LinkedHashMap<>();
        rdapResult.put("steps", steps);
        rdapResult.put("source", "RDAP_REGISTRY");
        rdapResult.put("expiry_date", "2027-01-15T00:00:00Z");
        rdapResult.put("registrar", "MarkMonitor Inc.");
        when(rdap.diagnoseSteps(eq("example.com"), any())).thenReturn(rdapResult);

        Map<String, Object> out = svc.diagnose("example.com");

        assertThat(out.get("source")).isEqualTo("RDAP_REGISTRY");
        assertThat(out.get("expiry_date")).isEqualTo("2027-01-15T00:00:00Z");
        assertThat(out.get("registrar")).isEqualTo("MarkMonitor Inc.");
        assertThat(out.get("days_remaining")).isNotNull();
        verify(whois, never()).diagnose(any());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allSteps = (List<Map<String, Object>>) out.get("steps");
        // PSL + 2 RDAP + WHOIS(skip)
        assertThat(allSteps).extracting(s -> s.get("step"))
                .containsExactly("PSL", "IANA_BOOTSTRAP", "RDAP_REGISTRY", "WHOIS");
        assertThat(allSteps.get(3).get("status")).isEqualTo("skip");
    }

    @Test
    @DisplayName("RDAP PKIX ile başarısız → WHOIS fallback ok → source=WHOIS")
    void rdapFailsWhoisOk() {
        List<Map<String, Object>> steps = new ArrayList<>(List.of(
                step("IANA_BOOTSTRAP", "fail"), step("RDAP_REGISTRY", "skip"), step("RDAP_ORG", "fail")));
        Map<String, Object> rdapResult = new LinkedHashMap<>();
        rdapResult.put("steps", steps);
        rdapResult.put("source", "FAILED");
        when(rdap.diagnoseSteps(eq("example.com"), any())).thenReturn(rdapResult);

        Map<String, Object> whoisStep = step("WHOIS", "ok");
        whoisStep.put("expiry_date", "2026-08-01T00:00:00Z");
        whoisStep.put("registrar", "TR-Nic");
        when(whois.diagnose("example.com")).thenReturn(whoisStep);

        Map<String, Object> out = svc.diagnose("example.com");

        assertThat(out.get("source")).isEqualTo("WHOIS");
        assertThat(out.get("expiry_date")).isEqualTo("2026-08-01T00:00:00Z");
        assertThat(out.get("registrar")).isEqualTo("TR-Nic");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allSteps = (List<Map<String, Object>>) out.get("steps");
        assertThat(allSteps).extracting(s -> s.get("step"))
                .containsExactly("PSL", "IANA_BOOTSTRAP", "RDAP_REGISTRY", "RDAP_ORG", "WHOIS");
    }

    @Test
    @DisplayName("Her şey başarısız → source=FAILED, days null")
    void allFail() {
        List<Map<String, Object>> steps = new ArrayList<>(List.of(step("IANA_BOOTSTRAP", "fail")));
        Map<String, Object> rdapResult = new LinkedHashMap<>();
        rdapResult.put("steps", steps);
        rdapResult.put("source", "FAILED");
        when(rdap.diagnoseSteps(eq("example.com"), any())).thenReturn(rdapResult);

        Map<String, Object> whoisStep = step("WHOIS", "fail");
        whoisStep.put("error_class", "CONNECT_TIMEOUT");
        when(whois.diagnose("example.com")).thenReturn(whoisStep);

        Map<String, Object> out = svc.diagnose("example.com");

        assertThat(out.get("source")).isEqualTo("FAILED");
        assertThat(out.get("expiry_date")).isNull();
        assertThat(out.get("days_remaining")).isNull();
    }

    @Test
    @DisplayName("PSL çözülemezse → tek PSL adımı, FAILED")
    void pslFail() {
        when(psl.registrableDomain("bad_input")).thenReturn(null);

        Map<String, Object> out = svc.diagnose("bad_input");

        assertThat(out.get("source")).isEqualTo("FAILED");
        assertThat(out.get("registrable")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allSteps = (List<Map<String, Object>>) out.get("steps");
        assertThat(allSteps).hasSize(1);
        assertThat(allSteps.get(0).get("step")).isEqualTo("PSL");
        assertThat(allSteps.get(0).get("status")).isEqualTo("fail");
        verify(rdap, never()).diagnoseSteps(any(), any());
    }
}
