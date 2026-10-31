package com.sitemonitor.service.scripted;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.service.ScriptedCheckerService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Koşum verisinin tel biçimi + TLS sertifika süslemesi + adım trendi (2026-10-31, k6 inceleme D1). */
class ScriptedRunViewTest {

    @Test
    void oldRowCarriesNoKeys() {
        assertThat(ScriptedRunView.fields(new ScriptedCheck())).isEmpty();
        assertThat(ScriptedRunView.fields((ScriptedCheck) null)).isEmpty();
        assertThat(ScriptedRunView.fields((ScriptedCheckerService.RunDetails) null)).isEmpty();
    }

    @Test
    void checkRowFields_onlyFilled_tlsUnknownUntilDecorated() {
        ScriptedCheck c = new ScriptedCheck();
        c.setStepsJson("[{\"name\":\"Giriş\",\"duration_ms\":36}]");
        c.setStepsTruncated(true);
        c.setThresholdsJson("[{\"metric\":\"checks\",\"expr\":\"rate>0.9\",\"ok\":false}]");
        c.setTlsJson("[{\"host\":\"a.example\",\"tls_version\":\"tls1.3\",\"requests\":2}]");
        c.setFailureReason("SCRIPT_THRESHOLD_FAILED");
        c.setFailureDetail("{\"phase\":\"SCRIPT\",\"threshold\":\"checks rate>0.9\"}");
        c.setContextJson("{\"process_budget_s\":130,\"egress\":\"direct\"}");
        Map<String, Object> f = ScriptedRunView.fields(c);
        assertThat(f).containsKeys("steps", "steps_truncated", "thresholds", "tls", "failure_reason", "failure_detail",
                "run_context");
        assertThat(f.get("steps_truncated")).isEqualTo(true);
        @SuppressWarnings("unchecked") List<Map<String, Object>> tls = (List<Map<String, Object>>) f.get("tls");
        assertThat(tls.get(0)).containsEntry("cert_state", "unknown").containsEntry("host", "a.example");
        assertThat(f.get("failure_detail")).isInstanceOf(Map.class);
        assertThat(f.get("run_context")).isInstanceOf(Map.class);
    }

    @Test
    void brokenJsonDropsOnlyThatField() {
        ScriptedCheck c = new ScriptedCheck();
        c.setStepsJson("{bozuk");
        c.setFailureReason("SCRIPT_CHECK_FAILED");
        c.setFailureDetail("[1]");                // nesne değil → yazılmaz
        Map<String, Object> f = ScriptedRunView.fields(c);
        assertThat(f).containsOnlyKeys("failure_reason");
    }

    @Test
    void runDetailsFields() {
        ScriptedCheckerService.RunDetails d = new ScriptedCheckerService.RunDetails(
                "[{\"name\":\"\"}]", false, null, null, "SCRIPT_NO_CHECKS", null, null);
        Map<String, Object> f = ScriptedRunView.fields(d);
        assertThat(f).containsOnlyKeys("steps", "steps_truncated", "failure_reason");
        assertThat(f.get("steps_truncated")).isEqualTo(false);
    }

    private static Map<String, Object> rowWithTls(String... hosts) {
        List<Map<String, Object>> tls = new ArrayList<>();
        for (String h : hosts) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("host", h);
            e.put("tls_version", "tls1.3");
            e.put("cert_state", "unknown");
            tls.add(e);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tls", tls);
        return row;
    }

    private static CertificateInventory inv(String domain, long team, Integer port, boolean active) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setTeamId(team);
        i.setPort(port);
        i.setActive(active);
        return i;
    }

    private static LatestCheck latestCheck(String domain, int days, String status) {
        LatestCheck l = new LatestCheck();
        l.setDomain(domain);
        l.setDaysRemaining(days);
        l.setStatus(status);
        return l;
    }

    @Test
    void decorateTls_trackedUntrackedUnknown_oneBatchRead() {
        AtomicInteger invCalls = new AtomicInteger();
        AtomicInteger latestCalls = new AtomicInteger();
        ScriptedRunView.CertLookup lookup = new ScriptedRunView.CertLookup() {
            @Override public List<CertificateInventory> inventory(Collection<String> hosts) {
                invCalls.incrementAndGet();
                assertThat(hosts).containsExactlyInAnyOrder("shop.example.com", "cdn.example.net", "secret.example.org");
                return List.of(inv("shop.example.com", 1, 8443, true), inv("Shop.Example.com", 1, 443, true),
                        inv("secret.example.org", 2, 443, true));
            }
            @Override public List<LatestCheck> latest(Collection<String> domains) {
                latestCalls.incrementAndGet();
                return List.of(latestCheck("Shop.Example.com", 42, "valid"));
            }
        };
        Map<String, Object> r1 = rowWithTls("Shop.Example.COM", "cdn.example.net");
        Map<String, Object> r2 = rowWithTls("secret.example.org");
        ScriptedRunView.decorateTls(List.of(r1, r2), lookup, i -> i.getTeamId() == 1L);
        assertThat(invCalls.get()).isEqualTo(1);
        assertThat(latestCalls.get()).isEqualTo(1);

        @SuppressWarnings("unchecked") List<Map<String, Object>> t1 = (List<Map<String, Object>>) r1.get("tls");
        assertThat(t1.get(0)).containsEntry("cert_state", "tracked");
        @SuppressWarnings("unchecked") Map<String, Object> cert = (Map<String, Object>) t1.get(0).get("cert");
        // 443 satırı tercih edildi
        assertThat(cert).containsEntry("domain", "Shop.Example.com").containsEntry("days_remaining", 42)
                .containsEntry("status", "valid");
        assertThat(t1.get(1)).containsEntry("cert_state", "untracked").doesNotContainKey("cert");
        // okuyamadığı takımın kaydı: ayrıntı verilmez
        @SuppressWarnings("unchecked") List<Map<String, Object>> t2 = (List<Map<String, Object>>) r2.get("tls");
        assertThat(t2.get(0)).containsEntry("cert_state", "unknown").doesNotContainKey("cert");
    }

    @Test
    void decorateTls_readFailureLeavesUnknown_noTlsNoRead() {
        Map<String, Object> row = rowWithTls("a.example");
        ScriptedRunView.decorateTls(List.of(row), new ScriptedRunView.CertLookup() {
            @Override public List<CertificateInventory> inventory(Collection<String> hosts) { throw new IllegalStateException("db"); }
            @Override public List<LatestCheck> latest(Collection<String> domains) { return List.of(); }
        }, i -> true);
        @SuppressWarnings("unchecked") List<Map<String, Object>> t = (List<Map<String, Object>>) row.get("tls");
        assertThat(t.get(0)).containsEntry("cert_state", "unknown");

        AtomicInteger calls = new AtomicInteger();
        ScriptedRunView.CertLookup counting = new ScriptedRunView.CertLookup() {
            @Override public List<CertificateInventory> inventory(Collection<String> hosts) { calls.incrementAndGet(); return List.of(); }
            @Override public List<LatestCheck> latest(Collection<String> domains) { calls.incrementAndGet(); return List.of(); }
        };
        ScriptedRunView.decorateTls(List.of(new LinkedHashMap<>()), counting, i -> true);
        ScriptedRunView.decorateTls(List.of(), counting, i -> true);
        ScriptedRunView.decorateTls(null, counting, i -> true);
        assertThat(calls.get()).isZero();
    }

    @Test
    void decorateTls_trackedWithoutLatestCheck_nullValues() {
        Map<String, Object> row = rowWithTls("a.example");
        ScriptedRunView.decorateTls(List.of(row), new ScriptedRunView.CertLookup() {
            @Override public List<CertificateInventory> inventory(Collection<String> hosts) { return List.of(inv("a.example", 1, 443, true)); }
            @Override public List<LatestCheck> latest(Collection<String> domains) { return List.of(); }
        }, i -> true);
        @SuppressWarnings("unchecked") List<Map<String, Object>> t = (List<Map<String, Object>>) row.get("tls");
        @SuppressWarnings("unchecked") Map<String, Object> cert = (Map<String, Object>) t.get(0).get("cert");
        assertThat(cert).containsEntry("days_remaining", null).containsEntry("status", null);
    }

    @Test
    void stepSeries_sortedPointsTopStepsFailedFlag() {
        List<Object[]> rows = new ArrayList<>();
        // yeniden eskiye gelir (repository DESC)
        rows.add(new Object[]{"2026-10-31T10:02:00", "[{\"name\":\"Giriş\",\"duration_ms\":30,\"failed_requests\":1,\"checks_failed\":0},"
                + "{\"name\":\"\",\"duration_ms\":null}]"});
        rows.add(new Object[]{"2026-10-31T10:01:00", "[{\"name\":\"Giriş\",\"duration_ms\":20,\"failed_requests\":0,\"checks_failed\":0}]"});
        rows.add(new Object[]{"2026-10-31T10:00:00", "{bozuk"});
        rows.add(null);
        List<Map<String, Object>> s = ScriptedRunView.stepSeries(rows, null);
        assertThat(s).extracting(m -> m.get("name")).containsExactly("Giriş", "");
        @SuppressWarnings("unchecked") List<Map<String, Object>> pts = (List<Map<String, Object>>) s.get(0).get("points");
        assertThat(pts).extracting(p -> p.get("ts")).containsExactly("2026-10-31T10:01:00", "2026-10-31T10:02:00");
        assertThat(pts).extracting(p -> p.get("failed")).containsExactly(false, true);
        assertThat(ScriptedRunView.stepSeries(null, null)).isEmpty();
    }

    @Test
    void stepSeries_keepsOnlyMostFrequentSteps() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            StringBuilder sb = new StringBuilder("[");
            sb.append("{\"name\":\"sık\",\"duration_ms\":1}");
            sb.append(",{\"name\":\"tek").append(i).append("\",\"duration_ms\":1}");
            sb.append("]");
            rows.add(new Object[]{String.format("2026-10-31T10:%02d:00", i), sb.toString()});
        }
        List<Map<String, Object>> s = ScriptedRunView.stepSeries(rows, null);
        assertThat(s).hasSize(ScriptedRunView.MAX_SERIES_STEPS);
        assertThat(s.get(0).get("name")).isEqualTo("sık");
    }
}
