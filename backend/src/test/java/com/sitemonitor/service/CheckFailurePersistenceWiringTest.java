package com.sitemonitor.service;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.model.PageCheck;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedCheck;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingCheck;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortCheck;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.UptimeCheck;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.PingCheckRepository;
import com.sitemonitor.repository.PortCheckRepository;
import com.sitemonitor.repository.UptimeCheckRepository;
import com.sitemonitor.service.failure.CheckFailure;
import com.sitemonitor.service.failure.CheckFailureClassifier;
import com.sitemonitor.service.failure.CheckFailureReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kontrol hata teşhisi (2026-10-05) — KALICILAŞTIRMA kablolaması: SchedulerService'in her kayıt noktası checker'ın
 * {@code failure_reason} / {@code failure_detail} değerlerini kayda kopyalar; başarılı kontrolde kolonlar NULL kalır ve
 * mevcut alanların (open / up / status / ok / value / changed) hesabı DEĞİŞMEZ. DNS'te başarısız sorgu hata metnini de
 * saklar, değişiklik tespiti ise eskisi gibi davranır (taban sorgusu yalnız başarılı sorguda).
 *
 * <p>Elle kontrol uçları ({@code MonitoringController.triggerPort|Ping|Dns}) aynı kopyalamayı yapar —
 * {@code MonitoringControllerTest} pinler.
 */
class CheckFailurePersistenceWiringTest {

    private SchedulerService scheduler;
    private PingCheckerService pingChecker;
    private PortCheckerService portChecker;
    private UptimeHttpCheckerService uptimeChecker;
    private DnsCheckerService dnsChecker;
    private PageCheckerService pageChecker;
    private PageSpeedCheckerService pageSpeedChecker;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) {
        return (T) ReflectionTestUtils.getField(scheduler, name);
    }

    @BeforeEach
    void setUp() throws Exception {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        pingChecker = mock(PingCheckerService.class);
        portChecker = mock(PortCheckerService.class);
        uptimeChecker = mock(UptimeHttpCheckerService.class);
        dnsChecker = mock(DnsCheckerService.class);
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AppSettingsService.class, appSettings);
        provided.put(PingCheckerService.class, pingChecker);
        provided.put(PortCheckerService.class, portChecker);
        provided.put(UptimeHttpCheckerService.class, uptimeChecker);
        provided.put(DnsCheckerService.class, dnsChecker);
        scheduler = build(SchedulerService.class, provided);

        pageChecker = mock(PageCheckerService.class);
        pageSpeedChecker = mock(PageSpeedCheckerService.class);
        ReflectionTestUtils.setField(scheduler, "keywordHeaderSecrets", mock(KeywordHeaderSecrets.class));
        ReflectionTestUtils.setField(scheduler, "pageCheckerService", pageChecker);
        ReflectionTestUtils.setField(scheduler, "pageCheckRepo", mock(com.sitemonitor.repository.PageCheckRepository.class));
        ReflectionTestUtils.setField(scheduler, "pageResourceIssueRepo", mock(com.sitemonitor.repository.PageResourceIssueRepository.class));
        ReflectionTestUtils.setField(scheduler, "pageSpeedCheckerService", pageSpeedChecker);
        ReflectionTestUtils.setField(scheduler, "pageSpeedCheckRepo", mock(com.sitemonitor.repository.PageSpeedCheckRepository.class));
        ReflectionTestUtils.setField(scheduler, "pageSpeedResourceRepo", mock(com.sitemonitor.repository.PageSpeedResourceRepository.class));
        ThreadPoolTaskExecutor exec = mock(ThreadPoolTaskExecutor.class);
        // startNetworkCheck: işi satır içinde koştur (deterministik) — SchedulerServiceTest deseni
        lenient().doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
                .when(exec).execute(any(Runnable.class));
        ReflectionTestUtils.setField(scheduler, "certCheckExecutor", exec);
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ── Ping ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ping: ICMP yok (na) → satır up=false + ICMP_UNAVAILABLE (alarm sonucu 'skipped' AYNEN); başarılıda kolonlar NULL")
    void ping() {
        PingMonitor m = new PingMonitor();
        m.setId(3L); m.setName("P"); m.setHost("host.example.test");
        Map<String, Object> na = map("up", false, "na", true, "error", "ICMP bu ortamda kullanılamıyor (yetki/binary)");
        CheckFailureClassifier.forPing(true, null, "Operation not permitted").with("target", "host.example.test").applyTo(na);
        when(pingChecker.check(eq("host.example.test"), any(), anyInt(), anyInt())).thenReturn(na);

        Map<String, Object> out = ReflectionTestUtils.invokeMethod(scheduler, "recheckPing", m);
        assertThat(out.get("status")).as("alarm kararı değişmedi").isEqualTo("skipped");
        PingCheckRepository repo = field("pingCheckRepo");
        ArgumentCaptor<PingCheck> c = ArgumentCaptor.forClass(PingCheck.class);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getUp()).isFalse();
        assertThat(c.getValue().getFailureReason()).isEqualTo("ICMP_UNAVAILABLE");
        assertThat(c.getValue().getFailureDetail()).contains("\"phase\":\"POLICY\"").contains("host.example.test");

        reset(repo);
        when(pingChecker.check(eq("host.example.test"), any(), anyInt(), anyInt()))
                .thenReturn(map("up", true, "rtt_ms", 5L, "packet_loss", 0));
        ReflectionTestUtils.invokeMethod(scheduler, "recheckPing", m);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getUp()).isTrue();
        assertThat(c.getValue().getFailureReason()).isNull();
        assertThat(c.getValue().getFailureDetail()).isNull();
    }

    // ── Port ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Port: kapalı → neden + ayrıntı (yol, vekil reddi) kayda; open/error aynen")
    void port() {
        PortMonitor m = new PortMonitor();
        m.setId(4L); m.setName("Port"); m.setHost("db.example.test"); m.setPort(5432);
        Map<String, Object> r = map("open", false, "response_ms", null, "error", "vekil tüneli reddetti: HTTP/1.1 403",
                "via", "proxy", "proxy_refused", true);
        CheckFailureClassifier.forException(new java.io.IOException("vekil tüneli reddetti: HTTP/1.1 403"), true)
                .with("proxy_refused", true).with("allowed_ports", List.of(443, 8443)).applyTo(r);
        when(portChecker.check(m)).thenReturn(r);

        ReflectionTestUtils.invokeMethod(scheduler, "recheckPort", m);
        PortCheckRepository repo = field("portCheckRepo");
        ArgumentCaptor<PortCheck> c = ArgumentCaptor.forClass(PortCheck.class);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getOpen()).isFalse();
        assertThat(c.getValue().getError()).isEqualTo("vekil tüneli reddetti: HTTP/1.1 403");
        assertThat(c.getValue().getFailureReason()).isEqualTo("PROXY_REFUSED");
        assertThat(c.getValue().getFailureDetail()).contains("\"proxy_refused\":true").contains("\"via\":\"proxy\"").contains("8443");
    }

    // ── Uptime ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Durum (uptime): down → neden + ayrıntı kayda; up → kolonlar NULL")
    void uptime() {
        Map<String, Object> down = map("via", "direct", "status", "down", "response_ms", null, "error", "Connection refused");
        CheckFailureClassifier.forException(new ConnectException("Connection refused"), false)
                .with("target", "app.example.test:443").with("via", "direct").applyTo(down);
        when(uptimeChecker.check("app.example.test", 443, 10000, false)).thenReturn(down);

        ReflectionTestUtils.invokeMethod(scheduler, "recheckUptime", "app.example.test", 443, false);
        UptimeCheckRepository repo = field("uptimeCheckRepo");
        ArgumentCaptor<UptimeCheck> c = ArgumentCaptor.forClass(UptimeCheck.class);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getStatus()).isEqualTo("down");
        assertThat(c.getValue().getFailureReason()).isEqualTo("CONNECT_REFUSED");
        assertThat(c.getValue().getFailureDetail()).contains("\"via\":\"direct\"").contains("app.example.test:443");

        reset(repo);
        when(uptimeChecker.check("app.example.test", 443, 10000, false)).thenReturn(map("via", "direct", "status", "up", "response_ms", 12L));
        ReflectionTestUtils.invokeMethod(scheduler, "recheckUptime", "app.example.test", 443, false);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getFailureReason()).isNull();
        assertThat(c.getValue().getFailureDetail()).isNull();
    }

    // ── DNS ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DNS: başarısız sorgu hata metni + nedeni SAKLAR, value='' ve changed=false; taban sorgusu YAPILMAZ (eskisi gibi)")
    void dns_failureStored_changeDetectionUnchanged() {
        CertificateInventoryRepository inv = field("inventoryRepo");
        CertificateInventory ci = new CertificateInventory();
        ci.setDomain("x.example.test");
        when(inv.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(ci));
        DnsMonitor m = new DnsMonitor();
        m.setId(7L); m.setDomain("x.example.test"); m.setRecordType("A");
        DnsMonitorRepository monRepo = field("dnsMonitorRepo");
        when(monRepo.findByActiveTrue()).thenReturn(List.of(m));
        DnsRecordRepository repo = field("dnsRecordRepo");

        Map<String, Object> failed = map("success", false, "values", List.of(), "error", "NXDOMAIN", "response_ms", 31L);
        CheckFailureClassifier.forDnsRcode(3, "NXDOMAIN").with("record_type", "A").applyTo(failed);
        when(dnsChecker.check("x.example.test", "A")).thenReturn(failed);

        scheduler.runDnsChecks();

        ArgumentCaptor<DnsRecord> c = ArgumentCaptor.forClass(DnsRecord.class);
        verify(repo).save(c.capture());
        DnsRecord rec = c.getValue();
        assertThat(rec.getValue()).as("başarısızlık ölçütü aynı (boş değer)").isEmpty();
        assertThat(rec.getChanged()).isFalse();
        assertThat(rec.getError()).isEqualTo("NXDOMAIN");
        assertThat(rec.getFailureReason()).isEqualTo("DNS_NXDOMAIN");
        assertThat(rec.getFailureDetail()).contains("\"rcode\":\"NXDOMAIN\"");
        verify(repo, never()).findLatestScheduledSuccessful(anyLong());

        // Başarılı sorgu: değişiklik tespiti son BAŞARILI zamanlanmış kayda karşı (eskisi gibi); hata kolonları NULL
        reset(repo);
        ((Map<?, ?>) ReflectionTestUtils.getField(scheduler, "lastMonitorCheckAt")).clear();
        DnsRecord prev = new DnsRecord();
        prev.setValue("192.0.2.10");
        when(repo.findLatestScheduledSuccessful(7L)).thenReturn(Optional.of(prev));
        when(dnsChecker.check("x.example.test", "A")).thenReturn(map("success", true, "values", List.of("192.0.2.20")));
        scheduler.runDnsChecks();
        verify(repo).save(c.capture());
        assertThat(c.getValue().getValue()).isEqualTo("192.0.2.20");
        assertThat(c.getValue().getChanged()).isTrue();
        assertThat(c.getValue().getPreviousValue()).isEqualTo("192.0.2.10");
        assertThat(c.getValue().getError()).isNull();
        assertThat(c.getValue().getFailureReason()).isNull();
        assertThat(c.getValue().getFailureDetail()).isNull();
    }

    @Test
    @DisplayName("DnsCheckerService.applyFailure: hata metni tavanlı; value/changed/manual alanlarına dokunmaz; null güvenli")
    void dns_applyFailure() {
        DnsRecord r = new DnsRecord();
        r.setValue(""); r.setChanged(false); r.setManual(true);
        Map<String, Object> res = map("success", false, "error", "x".repeat(3000));
        CheckFailure.of(CheckFailureReason.DNS_TIMEOUT).applyTo(res);
        DnsCheckerService.applyFailure(r, res);
        assertThat(r.getError()).hasSize(DnsCheckerService.ERROR_MAX);
        assertThat(r.getFailureReason()).isEqualTo("DNS_TIMEOUT");
        assertThat(r.getValue()).isEmpty();
        assertThat(r.getChanged()).isFalse();
        assertThat(r.getManual()).isTrue();
        DnsCheckerService.applyFailure(null, res);
        DnsCheckerService.applyFailure(r, null);
    }

    // ── Sayfa Bütünlüğü ───────────────────────────────────────────────────────────────────────

    private void stubPage(PageCheckerService.PageCheckResult res) {
        when(pageChecker.check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(res);
    }

    private PageCheck pageRow(PageMonitor m) {
        com.sitemonitor.repository.PageCheckRepository repo = field("pageCheckRepo");
        reset(repo);
        ReflectionTestUtils.invokeMethod(scheduler, "recheckPage", m, false, "SINGLE_PAGE");
        ArgumentCaptor<PageCheck> c = ArgumentCaptor.forClass(PageCheck.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("Sayfa Bütünlüğü: DOWN (istisna) / DOWN (HTTP 503) / DEGRADED / CONFIG_ERROR / OK — neden ve ok/status")
    void page() {
        PageMonitor m = new PageMonitor();
        m.setId(6L); m.setName("Sayfa"); m.setUrl("https://site.example.test/"); m.setTeamId(1L);

        CheckFailure transport = CheckFailureClassifier.forException(new ConnectException("Connection refused"), false)
                .with("target", "site.example.test");
        stubPage(new PageCheckerService.PageCheckResult("DOWN", false, null, 300L, 0, 0, 0, 0, 1, null, null,
                "Connection refused", List.of(), transport));
        PageCheck down = pageRow(m);
        assertThat(down.getOk()).isFalse();
        assertThat(down.getStatus()).isEqualTo("DOWN");
        assertThat(down.getFailureReason()).isEqualTo("CONNECT_REFUSED");
        assertThat(down.getFailureDetail()).contains("site.example.test").contains("\"via\":\"direct\"");

        stubPage(new PageCheckerService.PageCheckResult("DOWN", false, 503, 120L, 0, 0, 0, 0, 1, null, null,
                "ana sayfa HTTP 503", List.of()));
        PageCheck http = pageRow(m);
        assertThat(http.getFailureReason()).isEqualTo("HTTP_STATUS");
        assertThat(http.getFailureDetail()).contains("\"http_status\":503");

        PageCheckerService.ResourceIssue broken = new PageCheckerService.ResourceIssue(
                "https://site.example.test/app.js", "SCRIPT", "https://site.example.test/", "BROKEN", true, 404, 10L);
        stubPage(new PageCheckerService.PageCheckResult("DEGRADED", true, 200, 400L, 12, 1, 0, 0, 1, null, null, null,
                List.of(broken)));
        PageCheck degraded = pageRow(m);
        assertThat(degraded.getStatus()).isEqualTo("DEGRADED");
        assertThat(degraded.getFailureReason()).isEqualTo("RESOURCES_BROKEN");
        assertThat(degraded.getFailureDetail()).contains("\"broken\":1").contains("\"total_resources\":12");

        stubPage(new PageCheckerService.PageCheckResult("CONFIG_ERROR", false, null, 0L, 0, 0, 0, 0, 0, null, null,
                "yapılandırma hatası", List.of(), CheckFailure.of(CheckFailureReason.CONFIG_ERROR)));
        assertThat(pageRow(m).getFailureReason()).isEqualTo("CONFIG_ERROR");

        stubPage(new PageCheckerService.PageCheckResult("OK", true, 200, 200L, 12, 0, 0, 0, 1, null, null, null, List.of()));
        PageCheck ok = pageRow(m);
        assertThat(ok.getOk()).isTrue();
        assertThat(ok.getFailureReason()).isNull();
        assertThat(ok.getFailureDetail()).isNull();
    }

    // ── Sayfa Hızı ────────────────────────────────────────────────────────────────────────────

    private PageSpeedCheck speedRow(PageSpeedMonitor m) {
        com.sitemonitor.repository.PageSpeedCheckRepository repo = field("pageSpeedCheckRepo");
        reset(repo);
        when(repo.findTopByMonitorIdOrderByCheckedAtDesc(anyLong())).thenReturn(Optional.empty());
        ReflectionTestUtils.invokeMethod(scheduler, "recheckPageSpeed", m, false);
        ArgumentCaptor<PageSpeedCheck> c = ArgumentCaptor.forClass(PageSpeedCheck.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("Sayfa Hızı: DOWN ile CONFIG_ERROR artık ayırt edilir (ikisi de ok=false); SLOW kesinti değil → neden yok")
    void pageSpeed() {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(9L); m.setName("Hız"); m.setUrl("https://shop.example.test/"); m.setTeamId(1L); m.setTimeoutMs(8000);

        CheckFailure transport = CheckFailureClassifier.forException(
                new java.net.http.HttpTimeoutException("request timed out"), false).with("target", "shop.example.test");
        when(pageSpeedChecker.check(m)).thenReturn(new PageSpeedCheckerService.Result("DOWN", null, 0, 0, 8000, 0, 1, 1,
                false, false, List.of(), "request timed out", List.of(), com.sitemonitor.service.page.HttpPhaseProbe.NONE, 0, transport));
        PageSpeedCheck down = speedRow(m);
        assertThat(down.getOk()).isFalse();
        assertThat(down.getErrorMessage()).isEqualTo("request timed out");
        assertThat(down.getFailureReason()).isEqualTo("READ_TIMEOUT");
        assertThat(down.getFailureDetail()).contains("shop.example.test").contains("\"timeout_ms\":8000");

        when(pageSpeedChecker.check(m)).thenReturn(new PageSpeedCheckerService.Result("CONFIG_ERROR", null, 0, 0, 0, 0, 0, 0,
                false, false, List.of(), "yapılandırma hatası", List.of()));
        PageSpeedCheck cfg = speedRow(m);
        assertThat(cfg.getOk()).isFalse();
        assertThat(cfg.getFailureReason()).isEqualTo("CONFIG_ERROR");

        when(pageSpeedChecker.check(m)).thenReturn(new PageSpeedCheckerService.Result("SLOW", 200, 900, 1200, 6000, 2_000_000, 40, 0,
                false, false, List.of("LOAD"), null, List.of()));
        PageSpeedCheck slow = speedRow(m);
        assertThat(slow.getOk()).isTrue();
        assertThat(slow.getFailureReason()).isNull();
        assertThat(slow.getFailureDetail()).isNull();
    }
}
