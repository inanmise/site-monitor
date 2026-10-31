package com.sitemonitor.service;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * Çok pod R1-B (2026-10-21, MP-14) — iletilen elle kontrol sonucunun JSON gidiş-dönüşü dokuz türün HEPSİNDE değerlendirmeyi
 * DEĞİŞTİRMEZ: liderin gelen kutusundan okuduğu ham sonuç, takipçinin bellekteki sonucuyla aynı alarm kalemlerini (tür,
 * anahtar, ayrıntı, sağlıklı mı, hata, bağlam) üretir. Sayı türleri JSON'da genişleyebilir (Long → Integer); bağlam
 * alarm olayına zaten JSON olarak yazıldığı için karşılaştırma JSON biçimindedir.
 */
class ManualEvalRoundTripTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private MonitoringOutageService outage;
    private SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        outage = mock(MonitoringOutageService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(MonitoringOutageService.class, outage);
        provided.put(AppSettingsService.class, appSettings);
        scheduler = ManualCheckNoAlarmTest.build(SchedulerService.class, provided);
    }

    private static Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> row(Object... kv) {
        return raw(kv);
    }

    /** Tür → (giriş, gerçekçi ham sonuç). */
    private record Case(String type, BiConsumer<SchedulerService, Map<String, Object>> entry, Map<String, Object> raw) { }

    private List<Case> cases() {
        List<Case> c = new ArrayList<>();
        HttpMonitor http = new HttpMonitor(); http.setId(1L); http.setName("HTTP A"); http.setUrl("https://a.example.com");
        http.setTeamId(7L); http.setSlowResponseEnabled(true); http.setSlowThresholdMs(800);
        c.add(new Case("http", (s, r) -> s.evaluateHttpNow(http, r), raw("ok", false, "http_status", 503,
                "response_ms", 1234L, "error", "HTTP 503", "error_detail", "Service Unavailable",
                "json_assertion_failed", true, "failure_reason", "HTTP_STATUS")));
        PortMonitor port = new PortMonitor(); port.setId(2L); port.setName("Port A"); port.setHost("db.example.com");
        port.setPort(5432); port.setTeamId(7L); port.setStandalone(true);
        c.add(new Case("port", (s, r) -> s.evaluatePortNow(port, r), raw("open", true, "response_ms", 12L, "error", null)));
        PingMonitor ping = new PingMonitor(); ping.setId(3L); ping.setName("Ping A"); ping.setHost("gw.example.com");
        ping.setTeamId(7L);
        c.add(new Case("ping", (s, r) -> s.evaluatePingNow(ping, r), raw("up", true, "rtt_ms", 21L, "packet_loss", 0,
                "checked_at", "2026-10-21T06:00:00", "na", false)));
        KeywordMonitor kw = new KeywordMonitor(); kw.setId(4L); kw.setName("Kelime A"); kw.setUrl("https://k.example.com");
        kw.setKeyword("Tamam"); kw.setTeamId(7L);
        c.add(new Case("keyword", (s, r) -> s.evaluateKeywordNow(kw, r), raw("found", true, "count", 2,
                "http_status", 200, "response_ms", 310L, "snippet", "… Tamam …", "hints", List.of("LOGIN_WALL"),
                "excerpt", "<html>…</html>")));
        DnsMonitor dns = new DnsMonitor(); dns.setId(5L); dns.setName("DNS A"); dns.setDomain("d.example.com");
        dns.setRecordType("A"); dns.setTeamId(7L); dns.setStandalone(true);
        c.add(new Case("dns", (s, r) -> s.evaluateDnsNow(dns, r), raw("success", true,
                "values", List.of("1.1.1.1", "2.2.2.2"), "response_ms", 5L, "ttl", 300L)));
        PageMonitor page = new PageMonitor(); page.setId(6L); page.setName("Sayfa A"); page.setUrl("https://p.example.com");
        page.setTeamId(7L); page.setMode("SINGLE_PAGE");
        c.add(new Case("page", (s, r) -> s.evaluatePageNow(page, r), raw("status", "DEGRADED", "main_up", true,
                "integrity_up", false, "check_mode", "SINGLE_PAGE", "broken_resources", 2, "timeout_count", 1,
                "mixed_content_count", 0, "problem_total", 3,
                "problem_rows", List.of(row("url", "https://p.example.com/a.js", "status", 404, "kind", "BROKEN"),
                        row("url", "https://cdn.example.com/b.css", "status", null, "kind", "TIMEOUT")),
                "problem_resources", List.of("https://p.example.com/a.js"), "http_status", 200, "response_ms", 900L)));
        PageSpeedMonitor speed = new PageSpeedMonitor(); speed.setId(7L); speed.setName("Hız A");
        speed.setUrl("https://s.example.com"); speed.setTeamId(7L);
        c.add(new Case("pagespeed", (s, r) -> s.evaluatePageSpeedNow(speed, r), raw("status", "SLOW", "reachable", true,
                "within_thresholds", false, "breached", List.of("LOAD", "TTFB", "SIZE"), "response_ms", 5200L,
                "ttfb_ms", 950L, "total_bytes", 3_456_789L, "request_count", 41, "bytes_truncated", true,
                "http_status", 200, "heavy_rows", List.of(row("url", "https://s.example.com/hero.jpg", "bytes", 1_200_000L)),
                "heavy_total", 1)));
        ScriptedMonitor sc = new ScriptedMonitor(); sc.setId(8L); sc.setName("Senaryo A"); sc.setTeamId(7L);
        sc.setSlowResponseEnabled(true);
        c.add(new Case("scripted", (s, r) -> s.evaluateScriptedNow(sc, r), raw("up", false, "status", "FAIL",
                "detail", "FAIL — 0✓/2✗ · request timeout", "checks_failed", 2,
                "failed_checks", List.of(row("name", "giriş", "fails", 1), row("name", "sepet", "fails", 3)),
                "output_tail", "ERRO[0001] request timeout", "response_ms", 15_000L, "error", "request timeout")));
        com.sitemonitor.model.DbQueryMonitor dq = new com.sitemonitor.model.DbQueryMonitor(); dq.setId(10L); dq.setName("Sorgu A");
        dq.setTeamId(7L); dq.setPurpose("Bekleyen siparişler birikmesin"); dq.setSlowQueryMs(2000);
        c.add(new Case("dbquery", (s, r) -> s.evaluateDbQueryNow(dq, r), raw("status", "BREACH", "skipped", false, "ok", true,
                "level", "CRITICAL", "duration_ms", 2500L, "row_count", 1250, "row_count_capped", false, "metric_value", 1250.0,
                "slow", true, "slow_query_ms", 2000, "rule_summary", "Bekleyen: gelen 1.250 · beklenen ≤ 100 (kritik eşik)",
                "rule_results", List.of(row("id", "r1", "label", "Bekleyen", "kind", "ROW_COUNT", "actual", "1.250",
                        "expected", "≤ 100", "level", "CRITICAL", "passed", false, "evaluated", true, "measure", 1250.0,
                        "compared", 1250.0)),
                "checked_at", "2026-10-31T06:00:00", "profile_name", "Sipariş DB", "db_type", "POSTGRESQL",
                "profile_id", 3L, "check_id", 77L)));
        DomainMonitor dm = new DomainMonitor(); dm.setId(9L); dm.setName("Alan A"); dm.setDomain("example.com");
        dm.setTeamId(7L);
        c.add(new Case("domain", (s, r) -> s.evaluateDomainAlarmsNow(dm, r), raw("domain", "example.com", "source", "RDAP",
                "status", "WARNING", "days_remaining", 25, "expiry_date", "2026-11-15", "registrar", "Reg Ltd",
                "status_codes", List.of("clientTransferProhibited", "autoRenewPeriod"),
                "nameservers", List.of("ns1.example.net", "ns2.example.net"), "changed", true,
                "change_detail", "nameserver seti değişti;", "epp_critical", false, "epp_warn", true,
                "transfer_lock", "CLIENT", "blacklist_status", "SKIPPED", "checked_at", "2026-10-21T06:00:00",
                "resolved_ips", List.of("93.184.216.34"), "error", null)));
        return c;
    }

    /** Girişin ürettiği alarm kalemlerinin JSON görünümü (tür → kalemler). */
    private List<String> evaluate(Case c, Map<String, Object> raw) {
        clearInvocations(outage);
        c.entry().accept(scheduler, raw);
        List<String> out = new ArrayList<>();
        for (Invocation i : mockingDetails(outage).getInvocations()) {
            if (!i.getMethod().getName().equals("handleSweepResults")) continue;
            Object[] a = i.getArguments();
            assertThat(a.length >= 3 && Boolean.TRUE.equals(a[2])).as("%s: elle işaretli", c.type()).isTrue();
            @SuppressWarnings("unchecked")
            List<MonitoringOutageService.SweepItem> items = (List<MonitoringOutageService.SweepItem>) a[1];
            for (MonitoringOutageService.SweepItem it : items) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("list", a[0]);
                v.put("type", it.alertType());
                v.put("domain", it.domain());
                v.put("detail", it.detail());
                v.put("up", it.up());
                v.put("error", it.error());
                v.put("recheck", it.recheck() != null);
                v.put("ctx", it.ctxExtra());
                out.add(JSON.writeValueAsString(v));
            }
        }
        return out;
    }

    @Test
    @DisplayName("JSON gidiş-dönüşü dokuz türün HEPSİNDE aynı alarm kalemlerini üretir (takipçi → gelen kutusu → lider)")
    void roundTripPreservesEvaluation_allNineTypes() {
        List<String> types = new ArrayList<>();
        for (Case c : cases()) {
            List<String> direct = evaluate(c, c.raw());
            String json = ManualEvalInbox.toJson(c.raw());
            Map<String, Object> back = ManualEvalInbox.parse(json);
            List<String> forwarded = evaluate(c, back);
            assertThat(direct).as("%s: değerlendirme kalem üretmeli", c.type()).isNotEmpty();
            assertThat(forwarded).as("%s: iletilen sonuç aynı kalemleri üretmeli", c.type()).isEqualTo(direct);
            types.add(c.type());
        }
        assertThat(types).containsExactlyInAnyOrder(ManualEvalInbox.TYPE_HTTP, ManualEvalInbox.TYPE_PORT,
                ManualEvalInbox.TYPE_PING, ManualEvalInbox.TYPE_KEYWORD, ManualEvalInbox.TYPE_DNS, ManualEvalInbox.TYPE_PAGE,
                ManualEvalInbox.TYPE_PAGESPEED, ManualEvalInbox.TYPE_SCRIPTED, ManualEvalInbox.TYPE_DOMAIN,
                ManualEvalInbox.TYPE_DBQUERY);
    }

    @Test
    @DisplayName("Jackson'ın tanımadığı değer (ör. nesne / dizi / Instant) JSON'u bozmaz — düz türlere indirgenir")
    void unknownValuesAreFlattened() {
        Map<String, Object> raw = raw("when", Instant.parse("2026-10-21T06:00:00Z"), "arr", new int[]{1, 2},
                "obj", new Object(), "nested", raw("x", List.of(1L, 2L)));
        Map<String, Object> back = ManualEvalInbox.parse(ManualEvalInbox.toJson(raw));
        assertThat(back).containsKeys("when", "arr", "obj", "nested");
        assertThat(back.get("arr")).isEqualTo(List.of(1, 2));
        assertThat(back.get("nested")).isEqualTo(Map.of("x", List.of(1, 2)));
    }
}
