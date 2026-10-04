package com.sitemonitor.service;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PingCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D-7 (BUG_REGRESYON_2026-09-29) — "ölçülemedi / veri yok" SAĞLIKLI DEĞİLDİR: yavaşlık (PORT_SLOW, KEYWORD_SLOW, PING_SLOW,
 * PAGESPEED_SLOW) ve sayfa bütünlüğü (PAGE_INTEGRITY) kalemleri ölçüm yokken "up" üretiyor, açık alarmı kanıtsız
 * "ÇÖZÜLDÜ" diye kapatıyordu (hedef DOWN iken yavaşlık alarmı kapanıyor; SITE_CRAWL'ın açtığı bütünlük alarmını yalnız ana
 * sayfaya bakan 60 sn'lik tur kapatıyordu). Kural O-2/O-5 ile aynı: ölçülemeyen sonuç kalem ÜRETMEZ (sayacı ne artırır ne
 * sıfırlar), yeniden ölçüm "skipped" döner. Yavaşlık izlemesi KAPALIYKEN asılı alarmı kapatan sentetik "up" KORUNUR.
 */
class SlowAndIntegrityNoDataTest {

    private MonitoringOutageService outage;
    private PortMonitorRepository portRepo;
    private PortCheckerService portChecker;
    private KeywordMonitorRepository keywordRepo;
    private KeywordCheckerService keywordChecker;
    private PingCheckerService pingChecker;
    private PingCheckRepository pingCheckRepo;
    private PageCheckerService pageChecker;
    private SchedulerService scheduler;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    @BeforeEach
    void setUp() throws Exception {
        outage = mock(MonitoringOutageService.class);
        portRepo = mock(PortMonitorRepository.class);
        portChecker = mock(PortCheckerService.class);
        keywordRepo = mock(KeywordMonitorRepository.class);
        keywordChecker = mock(KeywordCheckerService.class);
        pingChecker = mock(PingCheckerService.class);
        pingCheckRepo = mock(PingCheckRepository.class);
        pageChecker = mock(PageCheckerService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(MonitoringOutageService.class, outage);
        provided.put(AppSettingsService.class, appSettings);
        provided.put(PortMonitorRepository.class, portRepo);
        provided.put(PortCheckerService.class, portChecker);
        provided.put(KeywordMonitorRepository.class, keywordRepo);
        provided.put(KeywordCheckerService.class, keywordChecker);
        provided.put(PingCheckerService.class, pingChecker);
        provided.put(PingCheckRepository.class, pingCheckRepo);
        provided.put(CertificateInventoryRepository.class, inv);
        scheduler = build(SchedulerService.class, provided);
        ReflectionTestUtils.setField(scheduler, "keywordHeaderSecrets", mock(KeywordHeaderSecrets.class));
        ReflectionTestUtils.setField(scheduler, "pageCheckerService", pageChecker);
        ReflectionTestUtils.setField(scheduler, "pageCheckRepo", mock(com.sitemonitor.repository.PageCheckRepository.class));
        ReflectionTestUtils.setField(scheduler, "pageResourceIssueRepo", mock(com.sitemonitor.repository.PageResourceIssueRepository.class));
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
    }

    private static Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<MonitoringOutageService.SweepItem> itemsFor(String type) {
        ArgumentCaptor<List<MonitoringOutageService.SweepItem>> c = ArgumentCaptor.forClass(List.class);
        verify(outage, atLeastOnce()).handleSweepResults(eq(type), c.capture(), anyBoolean());
        return c.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<MonitoringOutageService.SweepItem> sweepItemsFor(String type) {
        ArgumentCaptor<List<MonitoringOutageService.SweepItem>> c = ArgumentCaptor.forClass(List.class);
        verify(outage, atLeastOnce()).handleSweepResults(eq(type), c.capture());
        return c.getValue();
    }

    // ── PORT_SLOW ──────────────────────────────────────────────────────────────────────────────

    private static PortMonitor port(boolean slowEnabled) {
        PortMonitor m = new PortMonitor();
        m.setId(2L); m.setName("Port A"); m.setHost("app.example.com"); m.setPort(443); m.setProtocol("TCP");
        m.setTeamId(7L); m.setStandalone(true); m.setActive(true);
        m.setSlowResponseEnabled(slowEnabled); m.setSlowThresholdMs(3000);
        return m;
    }

    private void runPortSweep(PortMonitor m, Map<String, Object> check) {
        when(portRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(portRepo.findAll()).thenReturn(List.of(m));
        when(portChecker.check(any(PortMonitor.class))).thenReturn(check);
        ReflectionTestUtils.invokeMethod(scheduler, "runPortChecksLocked");
    }

    @Test
    @DisplayName("PORT_SLOW: yavaşlık AÇIK ve port KAPALI (süre ölçülemedi) → SLOW kalemi yok; yeniden ölçüm 'skipped'")
    void portSlow_portDown_noItem_andRecheckSkipped() {
        runPortSweep(port(true), raw("open", false, "error", "Connection refused", "response_ms", 4000L));
        assertThat(sweepItemsFor(EscalationService.TYPE_PORT_SLOW)).isEmpty();
    }

    @Test
    @DisplayName("PORT_SLOW korunur: yavaşlık KAPALI → sentetik 'up' (asılı alarm kapanır); açık + ölçülü yavaş → DOWN, yeniden ölçüm port kapalıyken 'skipped'")
    void portSlow_disabledSyntheticUp_andMeasuredSlowDown() {
        runPortSweep(port(false), raw("open", false, "error", "Connection refused"));
        List<MonitoringOutageService.SweepItem> off = sweepItemsFor(EscalationService.TYPE_PORT_SLOW);
        assertThat(off).hasSize(1);
        assertThat(off.get(0).up()).isTrue();

        clearInvocations(outage);
        ((Map<?, ?>) ReflectionTestUtils.getField(scheduler, "lastMonitorCheckAt")).clear();   // checkDue ızgarası sıfırlansın
        runPortSweep(port(true), raw("open", true, "response_ms", 4500L));
        List<MonitoringOutageService.SweepItem> slow = sweepItemsFor(EscalationService.TYPE_PORT_SLOW);
        assertThat(slow).hasSize(1);
        assertThat(slow.get(0).up()).isFalse();

        when(portChecker.check(any(PortMonitor.class))).thenReturn(raw("open", false, "error", "timed out"));
        assertThat(slow.get(0).recheck().get().get("status")).isEqualTo("skipped");
    }

    // ── KEYWORD_SLOW ───────────────────────────────────────────────────────────────────────────

    private static KeywordMonitor keyword(boolean slowEnabled) {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(4L); m.setName("Kelime A"); m.setUrl("https://app.example.com/"); m.setKeyword("Tamam");
        m.setTeamId(7L); m.setActive(true); m.setSlowResponseEnabled(slowEnabled); m.setSlowThresholdMs(3000);
        return m;
    }

    @Test
    @DisplayName("KEYWORD_SLOW: yavaşlık AÇIK ve HTTP hatası (süre ölçülemedi) → SLOW kalemi yok; yeniden ölçüm 'skipped'")
    void keywordSlow_httpError_noItem_andRecheckSkipped() {
        KeywordMonitor m = keyword(true);
        when(keywordRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(keywordRepo.findAll()).thenReturn(List.of(m));
        // Zamanlanmış tur beklentili (7 argümanlı) kontrolü çağırır (2026-10-04, keyword hata teşhisi); yavaşlık
        // yeniden ölçümü (evalKeywordSlow) eski 6 argümanlı kontrolde kaldı — aşağıdaki üçüncü saplama onu besler.
        when(keywordChecker.check(anyString(), anyString(), anyInt(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(raw("found", false, "error", "connect timed out"));
        ReflectionTestUtils.invokeMethod(scheduler, "runKeywordChecksLocked");
        assertThat(sweepItemsFor(EscalationService.TYPE_KEYWORD_SLOW)).isEmpty();

        // Ölçülü ve yavaş tur → DOWN kalemi; yeniden ölçüm HTTP hatasında "skipped"
        clearInvocations(outage);
        ((Map<?, ?>) ReflectionTestUtils.getField(scheduler, "lastMonitorCheckAt")).clear();   // checkDue ızgarası sıfırlansın
        when(keywordChecker.check(anyString(), anyString(), anyInt(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(raw("found", true, "count", 1, "response_ms", 5000L));
        ReflectionTestUtils.invokeMethod(scheduler, "runKeywordChecksLocked");
        MonitoringOutageService.SweepItem slow = sweepItemsFor(EscalationService.TYPE_KEYWORD_SLOW).get(0);
        assertThat(slow.up()).isFalse();
        when(keywordChecker.check(anyString(), anyString(), anyInt(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(raw("found", false, "error", "connect timed out"));
        assertThat(slow.recheck().get().get("status")).isEqualTo("skipped");
    }

    // ── PING_SLOW ──────────────────────────────────────────────────────────────────────────────

    private static PingMonitor ping(boolean slowEnabled) {
        PingMonitor m = new PingMonitor();
        m.setId(7L); m.setName("Ping A"); m.setHost("gw.example.com"); m.setTeamId(7L);
        m.setSlowResponseEnabled(slowEnabled); m.setSlowBaselineWindowMinutes(10); m.setSlowThresholdPercent(20);
        return m;
    }

    @Test
    @DisplayName("PING_SLOW: host DOWN (ölçüm yok) elle kontrolde SLOW kalemi yok — eskiden 'up' sayılıp yavaşlık alarmını kapatıyordu")
    void pingSlow_hostDown_noItem() {
        scheduler.evaluatePingNow(ping(true), raw("up", false, "error", "timeout"));
        assertThat(itemsFor(EscalationService.TYPE_PING_SLOW)).isEmpty();
    }

    @Test
    @DisplayName("PING_SLOW: taban çizgisi yetersiz / okunamadı / ölçüm yok → hüküm 'skipped' (ne alarm ne kapanış); KAPALI → 'up'")
    void pingSlow_verdicts() {
        assertThat(scheduler.pingSlowVerdict(ping(true), null, null).get("status")).isEqualTo("skipped");
        when(pingCheckRepo.slowBaseline(eq(7L), anyString(), anyString()))
                .thenReturn(java.util.List.<Object[]>of(new Object[]{ 10.0, 2L }));
        assertThat(scheduler.pingSlowVerdict(ping(true), 900L, null).get("status")).isEqualTo("skipped");
        assertThat(scheduler.pingSlowVerdict(ping(false), 900L, null).get("status")).isEqualTo("up");
    }

    // ── PAGE_INTEGRITY ─────────────────────────────────────────────────────────────────────────

    private static PageMonitor page(String mode) {
        PageMonitor m = new PageMonitor();
        m.setId(6L); m.setName("Sayfa A"); m.setUrl("https://app.example.com/"); m.setTeamId(7L); m.setMode(mode);
        return m;
    }

    @Test
    @DisplayName("PAGE_INTEGRITY: ana sayfa alınamadı (bütünlük ölçülemedi) → bütünlük kalemi yok; yeniden ölçüm 'skipped'")
    void pageIntegrity_mainDown_noItem_andRecheckSkipped() {
        scheduler.evaluatePageNow(page("SINGLE_PAGE"), raw("main_up", false, "integrity_up", true, "error", "HTTP 503",
                "check_mode", "SINGLE_PAGE"));
        assertThat(itemsFor(EscalationService.TYPE_PAGE_INTEGRITY)).isEmpty();

        clearInvocations(outage);
        scheduler.evaluatePageNow(page("SINGLE_PAGE"), raw("main_up", true, "integrity_up", false, "broken_resources", 2,
                "check_mode", "SINGLE_PAGE"));
        MonitoringOutageService.SweepItem integ = itemsFor(EscalationService.TYPE_PAGE_INTEGRITY).get(0);
        assertThat(integ.up()).isFalse();
        when(pageChecker.check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageCheckerService.PageCheckResult("DOWN", false, 503, 100L, 0, 0, 0, 0, 1, null, null,
                        "HTTP 503", List.of()));
        assertThat(integ.recheck().get().get("status")).isEqualTo("skipped");
    }

    @Test
    @DisplayName("PAGE_INTEGRITY: SITE_CRAWL izlemesinde yalnız ANA sayfaya bakan tur bütünlüğü 'sağlıklı' SAYMAZ; gün boyu crawl sayar")
    void pageIntegrity_crawlMonitor_singlePageUpIsNotEvidence() {
        scheduler.evaluatePageNow(page("SITE_CRAWL"), raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE"));
        assertThat(itemsFor(EscalationService.TYPE_PAGE_INTEGRITY)).as("ana sayfa temiz ≠ site temiz").isEmpty();

        clearInvocations(outage);
        scheduler.evaluatePageNow(page("SITE_CRAWL"), raw("main_up", true, "integrity_up", false, "broken_resources", 1,
                "check_mode", "SINGLE_PAGE"));
        List<MonitoringOutageService.SweepItem> bad = itemsFor(EscalationService.TYPE_PAGE_INTEGRITY);
        assertThat(bad).as("ana sayfadaki sorun yine kanıttır").hasSize(1);
        assertThat(bad.get(0).up()).isFalse();

        clearInvocations(outage);
        scheduler.evaluatePageNow(page("SITE_CRAWL"), raw("main_up", true, "integrity_up", true, "check_mode", "SITE_CRAWL"));
        List<MonitoringOutageService.SweepItem> crawl = itemsFor(EscalationService.TYPE_PAGE_INTEGRITY);
        assertThat(crawl).hasSize(1);
        assertThat(crawl.get(0).up()).isTrue();
    }

    @Test
    @DisplayName("PAGE_INTEGRITY korunur: yapılandırma hatası → sentetik 'up' (asılı alarm kapanır)")
    void pageIntegrity_configError_stillSyntheticUp() {
        scheduler.evaluatePageNow(page("SINGLE_PAGE"), raw("main_up", false, "config_error", true, "check_mode", "SINGLE_PAGE"));
        List<MonitoringOutageService.SweepItem> it = itemsFor(EscalationService.TYPE_PAGE_INTEGRITY);
        assertThat(it).hasSize(1);
        assertThat(it.get(0).up()).isTrue();
    }

    // ── PAGESPEED_SLOW ─────────────────────────────────────────────────────────────────────────

    private static PageSpeedMonitor speed() {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(8L); m.setName("Hız A"); m.setUrl("https://app.example.com/"); m.setTeamId(7L);
        return m;
    }

    @Test
    @DisplayName("PAGESPEED_SLOW: sayfa alınamadı (ölçüm yok) → SLOW kalemi yok; eşik içi → 'up'; yapılandırma hatası → sentetik 'up'")
    void pageSpeedSlow_unreachable_noItem() {
        scheduler.evaluatePageSpeedNow(speed(), raw("reachable", false, "error", "timeout"));
        assertThat(itemsFor(EscalationService.TYPE_PAGESPEED_SLOW)).isEmpty();

        clearInvocations(outage);
        scheduler.evaluatePageSpeedNow(speed(), raw("reachable", true, "within_thresholds", true));
        assertThat(itemsFor(EscalationService.TYPE_PAGESPEED_SLOW)).hasSize(1);
        assertThat(itemsFor(EscalationService.TYPE_PAGESPEED_SLOW).get(0).up()).isTrue();

        clearInvocations(outage);
        scheduler.evaluatePageSpeedNow(speed(), raw("config_error", true));
        assertThat(itemsFor(EscalationService.TYPE_PAGESPEED_SLOW)).hasSize(1);
        assertThat(itemsFor(EscalationService.TYPE_PAGESPEED_SLOW).get(0).up()).isTrue();
    }
}
