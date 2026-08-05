package com.sitemonitor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConnectionDiagnosticsServiceTest {

    @Mock
    private CertificateCheckerService checker;

    private ThreadPoolTaskExecutor executor;
    private ConnectionDiagnosticsService service;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.initialize();
        service = new ConnectionDiagnosticsService(checker, executor);
        ReflectionTestUtils.setField(service, "diagTimeoutSeconds", 2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private static Map<String, Object> okResult() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "valid");
        m.put("subject", "localhost");
        m.put("days_remaining", 90);
        m.put("tls_version", "TLSv1.3");
        m.put("elapsed_ms", 42L);
        return m;
    }

    private static Map<String, Object> errResult(String stage) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "error");
        m.put("error", "Connection timeout after 2s");
        m.put("error_class", "NETWORK");
        m.put("error_stage", stage);
        m.put("elapsed_ms", 2003L);
        return m;
    }

    @Test
    @DisplayName("diagnose without proxy config runs 2 direct combos")
    void diagnose_noProxy_twoCombos() {
        when(checker.tryCheckOnce(eq("localhost"), eq(443), any(CertificateCheckerService.CheckOptions.class)))
                .thenReturn(okResult());

        Map<String, Object> out = service.diagnose("localhost", 443);

        assertThat(out.get("proxy_configured")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> combos = (List<Map<String, Object>>) out.get("combos");
        assertThat(combos).hasSize(2);
        assertThat(combos).extracting(c -> c.get("id"))
                .containsExactly("direct+browser", "direct+default");
        assertThat(combos).allSatisfy(c -> {
            assertThat(c.get("status")).isEqualTo("ok");
            assertThat(c.get("step_reached")).isEqualTo("cert-ok");
            assertThat(c.get("tls_version")).isEqualTo("TLSv1.3");
        });
    }

    @Test
    @DisplayName("diagnose with proxy config runs 4 combos and maps error stage")
    void diagnose_withProxy_fourCombos() {
        ReflectionTestUtils.setField(service, "proxyHost", "proxy.local");
        ReflectionTestUtils.setField(service, "proxyPort", 8080);
        when(checker.tryCheckOnce(eq("localhost"), eq(443), any(CertificateCheckerService.CheckOptions.class)))
                .thenAnswer(inv -> {
                    CertificateCheckerService.CheckOptions opts = inv.getArgument(2);
                    return opts.viaProxy() ? errResult("tls-handshake") : okResult();
                });

        Map<String, Object> out = service.diagnose("localhost", 443);

        assertThat(out.get("proxy_configured")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> combos = (List<Map<String, Object>>) out.get("combos");
        assertThat(combos).hasSize(4);
        assertThat(combos).extracting(c -> c.get("id"))
                .containsExactly("direct+browser", "direct+default", "proxy+browser", "proxy+default");
        Map<String, Object> proxyCombo = combos.get(2);
        assertThat(proxyCombo.get("status")).isEqualTo("error");
        assertThat(proxyCombo.get("step_reached")).isEqualTo("tls-handshake");
        assertThat(proxyCombo.get("error_class")).isEqualTo("NETWORK");
    }

    @Test
    @DisplayName("DNS resolution failure does not abort the combo probes")
    void diagnose_dnsFailure_combosStillRun() {
        // Spy + stub: some resolvers hijack NXDOMAIN, so a real lookup of
        // host.invalid is not guaranteed to fail in every environment.
        ConnectionDiagnosticsService spy = spy(service);
        Map<String, Object> dnsFail = new HashMap<>();
        dnsFail.put("ips", List.of());
        dnsFail.put("error", "host.invalid: Name or service not known");
        dnsFail.put("elapsed_ms", 3L);
        doReturn(dnsFail).when(spy).resolveDns("host.invalid");
        when(checker.tryCheckOnce(eq("host.invalid"), eq(443), any(CertificateCheckerService.CheckOptions.class)))
                .thenReturn(errResult("tcp-connect"));

        Map<String, Object> out = spy.diagnose("host.invalid", 443);

        @SuppressWarnings("unchecked")
        Map<String, Object> dns = (Map<String, Object>) out.get("dns");
        assertThat(dns.get("error")).isNotNull();
        assertThat((List<?>) dns.get("ips")).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> combos = (List<Map<String, Object>>) out.get("combos");
        assertThat(combos).hasSize(2);
        assertThat(combos).allSatisfy(c -> assertThat(c.get("status")).isEqualTo("error"));
    }

    @Test
    @DisplayName("diagnose includes source (pod) info and passes route fields through combos")
    void diagnose_includesSourceAndRoute() {
        ConnectionDiagnosticsService spy = spy(service);
        Map<String, Object> src = new HashMap<>();
        src.put("hostname", "site-monitor-pod-abc");
        src.put("ips", List.of("10.128.2.34"));
        doReturn(src).when(spy).resolveSource();

        Map<String, Object> raw = okResult();
        raw.put("source_ip", "10.128.2.34");
        raw.put("source_port", 48512);
        raw.put("peer_ip", "217.169.196.216");
        raw.put("peer_port", 443);
        when(checker.tryCheckOnce(eq("localhost"), eq(443), any(CertificateCheckerService.CheckOptions.class)))
                .thenReturn(raw);

        Map<String, Object> out = spy.diagnose("localhost", 443);

        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) out.get("source");
        assertThat(source.get("hostname")).isEqualTo("site-monitor-pod-abc");
        assertThat(source.get("ips")).isEqualTo(List.of("10.128.2.34"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> combos = (List<Map<String, Object>>) out.get("combos");
        assertThat(combos).allSatisfy(c -> {
            assertThat(c.get("source_ip")).isEqualTo("10.128.2.34");
            assertThat(c.get("source_port")).isEqualTo(48512);
            assertThat(c.get("peer_ip")).isEqualTo("217.169.196.216");
            assertThat(c.get("peer_port")).isEqualTo(443);
        });
    }

    @Test
    @DisplayName("a throwing probe yields a synthetic error combo instead of failing the response")
    void diagnose_throwingProbe_syntheticError() {
        when(checker.tryCheckOnce(eq("localhost"), eq(443), any(CertificateCheckerService.CheckOptions.class)))
                .thenAnswer(inv -> {
                    CertificateCheckerService.CheckOptions opts = inv.getArgument(2);
                    if ("default".equals(opts.tlsMode())) throw new RuntimeException("boom");
                    return okResult();
                });

        Map<String, Object> out = service.diagnose("localhost", 443);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> combos = (List<Map<String, Object>>) out.get("combos");
        assertThat(combos).hasSize(2);
        assertThat(combos.get(0).get("status")).isEqualTo("ok");
        Map<String, Object> failed = combos.get(1);
        assertThat(failed.get("status")).isEqualTo("error");
        assertThat((String) failed.get("error")).contains("Probe failed");
        assertThat(failed.get("step_reached")).isEqualTo("unknown");
    }
}
