package com.sitemonitor.service;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * O-b4 (BUG_REGRESYON_2026-09-29b): SITE_CRAWL derin tarama bulgusu teyit zincirinde yalnız ANA sayfayla yeniden
 * ölçülüyor, ana sayfa temiz olduğundan "geçici dalgalanma" sayılıyor ve derin bulgu HİÇ alarm açmıyordu. Artık teyit
 * ve kurtarma yeniden ölçümü bulgunun KAYNAĞINA bakar: derin bulgu → sorunlu kaynak sayfalar (tavanlı); ana sayfa
 * bulgusu → ana sayfa. Kapanış kanıtı da aynı kaynaktan.
 */
class PageIntegritySourceRecheckTest {

    private static final String HOME = "https://app.example.com/";
    private static final String DEEP = "https://app.example.com/urunler";

    private MonitoringOutageService outage;
    private PageCheckerService pageChecker;
    private EscalationService escalation;
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
        pageChecker = mock(PageCheckerService.class);
        escalation = mock(EscalationService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(MonitoringOutageService.class, outage);
        provided.put(AppSettingsService.class, appSettings);
        provided.put(EscalationService.class, escalation);
        provided.put(CertificateInventoryRepository.class, mock(CertificateInventoryRepository.class));
        scheduler = build(SchedulerService.class, provided);
        ReflectionTestUtils.setField(scheduler, "keywordHeaderSecrets", mock(KeywordHeaderSecrets.class));
        ReflectionTestUtils.setField(scheduler, "pageCheckerService", pageChecker);
        ReflectionTestUtils.setField(scheduler, "pageCheckRepo", mock(com.sitemonitor.repository.PageCheckRepository.class));
        ReflectionTestUtils.setField(scheduler, "pageResourceIssueRepo", mock(com.sitemonitor.repository.PageResourceIssueRepository.class));
    }

    private static PageMonitor crawlMonitor() {
        PageMonitor m = new PageMonitor();
        m.setId(6L); m.setName("Sayfa A"); m.setUrl(HOME); m.setTeamId(7L); m.setMode("SITE_CRAWL");
        return m;
    }

    private static PageCheckerService.ResourceIssue broken(String sourcePage) {
        return new PageCheckerService.ResourceIssue(sourcePage + "/app.js", "SCRIPT", sourcePage, "BROKEN", true, 404, 12L);
    }

    private static PageCheckerService.PageCheckResult reachable(List<PageCheckerService.ResourceIssue> issues) {
        return new PageCheckerService.PageCheckResult(issues.isEmpty() ? "OK" : "DEGRADED", true, 200, 120L, 10,
                issues.size(), 0, 0, 1, null, null, null, issues);
    }

    private static PageCheckerService.PageCheckResult unreachable(Integer http) {
        return new PageCheckerService.PageCheckResult("DOWN", false, http, 4000L, 0, 0, 0, 0, 1, null, null,
                "ana sayfa alınamadı", List.of());
    }

    private void stub(String url, String mode, PageCheckerService.PageCheckResult res) {
        when(pageChecker.check(eq(url), eq(mode), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(res);
    }

    private static Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<MonitoringOutageService.SweepItem> integrityItems() {
        ArgumentCaptor<List<MonitoringOutageService.SweepItem>> c = ArgumentCaptor.forClass(List.class);
        verify(outage, atLeastOnce()).handleSweepResults(eq(EscalationService.TYPE_PAGE_INTEGRITY), c.capture(), anyBoolean());
        return c.getValue();
    }

    private Map<String, Object> crawl(PageMonitor m) {
        return ReflectionTestUtils.invokeMethod(scheduler, "recheckPage", m, false, "SITE_CRAWL");
    }

    @Test
    @DisplayName("O-b4: crawl DERİN bulgu → teyit kaynak sayfayı yeniden ölçer (ana sayfa temiz olsa da 'down') → alarm açılır")
    void deepFinding_confirmRechecksSourcePage_notHomePage() {
        PageMonitor m = crawlMonitor();
        stub(HOME, "SITE_CRAWL", reachable(List.of(broken(DEEP))));
        Map<String, Object> r = crawl(m);
        assertThat(r.get("problem_pages")).isEqualTo(List.of(DEEP));

        scheduler.evaluatePageNow(m, r);
        MonitoringOutageService.SweepItem it = integrityItems().get(0);
        assertThat(it.up()).isFalse();
        assertThat(it.ctxExtra()).containsEntry("integrity_scope", "CRAWL").containsEntry("problem_pages", List.of(DEEP));

        stub(HOME, "SINGLE_PAGE", reachable(List.of()));            // ana sayfa temiz — eskiden "geçici" hükmü buradan
        stub(DEEP, "SINGLE_PAGE", reachable(List.of(broken(DEEP))));
        assertThat(it.recheck().get().get("status")).as("derin bulgu sürüyor → teyit").isEqualTo("down");
        verify(pageChecker, never()).check(eq(HOME), eq("SINGLE_PAGE"), anyInt(), anyInt(), anyInt(), any(),
                anyInt(), anyInt(), anyInt(), anyBoolean());

        stub(DEEP, "SINGLE_PAGE", reachable(List.of()));
        assertThat(it.recheck().get().get("status")).as("kaynak sayfa artık temiz → geçici").isEqualTo("up");
        stub(DEEP, "SINGLE_PAGE", unreachable(null));
        assertThat(it.recheck().get().get("status")).as("ölçülemedi ≠ sağlıklı").isEqualTo("skipped");
        stub(DEEP, "SINGLE_PAGE", unreachable(404));
        assertThat(it.recheck().get().get("status")).as("kaynak sayfa kaldırıldı → bulgu artık sunulmuyor").isEqualTo("up");
    }

    @Test
    @DisplayName("O-b4 maliyet: yeniden ölçülen kaynak sayfa sayısı tavanlı (5)")
    void recheckIsCapped() {
        PageMonitor m = crawlMonitor();
        List<PageCheckerService.ResourceIssue> many = new ArrayList<>();
        for (int i = 1; i <= 8; i++) many.add(broken(HOME + "sayfa-" + i));
        stub(HOME, "SITE_CRAWL", reachable(many));
        Map<String, Object> r = crawl(m);
        assertThat((List<?>) r.get("problem_pages")).hasSize(SchedulerService.INTEGRITY_RECHECK_MAX_PAGES);

        scheduler.evaluatePageNow(m, r);
        MonitoringOutageService.SweepItem it = integrityItems().get(0);
        when(pageChecker.check(anyString(), eq("SINGLE_PAGE"), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(),
                anyInt(), anyBoolean())).thenReturn(reachable(List.of()));
        assertThat(it.recheck().get().get("status")).isEqualTo("up");
        verify(pageChecker, times(SchedulerService.INTEGRITY_RECHECK_MAX_PAGES)).check(anyString(), eq("SINGLE_PAGE"),
                anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("O-b4: ANA SAYFA kaynaklı alarm (crawl izlemesi) ana sayfa temizse kapanır; crawl kaynaklıysa ana sayfa kanıt değil")
    void homeScopedAlarm_closesOnCleanHome_crawlScopedDoesNot() {
        PageMonitor m = crawlMonitor();
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY)).thenReturn(Map.of("integrity_scope", "HOME"));
        scheduler.evaluatePageNow(m, raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE"));
        List<MonitoringOutageService.SweepItem> items = integrityItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).up()).isTrue();
        stub(HOME, "SINGLE_PAGE", reachable(List.of()));
        assertThat(items.get(0).recheck().get().get("status")).isEqualTo("up");

        clearInvocations(outage);
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));
        scheduler.evaluatePageNow(m, raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE"));
        assertThat(integrityItems()).as("crawl kaynaklı alarm: ana sayfa temizliği kanıt değil").isEmpty();
    }

    @Test
    @DisplayName("O-b4: ana sayfa bulgusu (crawl izlemesi) teyitte ana sayfayla ölçülür — temizse 'geçici' (eskiden 'skipped')")
    void homeFinding_rechecksHome() {
        PageMonitor m = crawlMonitor();
        scheduler.evaluatePageNow(m, raw("main_up", true, "integrity_up", false, "broken_resources", 1,
                "check_mode", "SINGLE_PAGE", "problem_pages", List.of(HOME)));
        MonitoringOutageService.SweepItem it = integrityItems().get(0);
        assertThat(it.up()).isFalse();
        assertThat(it.ctxExtra()).containsEntry("integrity_scope", "HOME").doesNotContainKey("problem_pages");
        stub(HOME, "SINGLE_PAGE", reachable(List.of()));
        assertThat(it.recheck().get().get("status")).isEqualTo("up");
    }

    @Test
    @DisplayName("O-b4: tam crawl temiz → kurtarma doğrulaması açık alarmın KAYNAK sayfalarını ölçer (ana sayfa değil)")
    void crawlClean_recoveryRechecksOpenAlarmSourcePages() {
        PageMonitor m = crawlMonitor();
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));
        scheduler.evaluatePageNow(m, raw("main_up", true, "integrity_up", true, "check_mode", "SITE_CRAWL"));
        MonitoringOutageService.SweepItem it = integrityItems().get(0);
        assertThat(it.up()).isTrue();

        stub(HOME, "SINGLE_PAGE", reachable(List.of()));
        stub(DEEP, "SINGLE_PAGE", reachable(List.of(broken(DEEP))));
        assertThat(it.recheck().get().get("status")).as("kaynak sayfada sorun sürüyor → kapanmaz").isEqualTo("down");
        stub(DEEP, "SINGLE_PAGE", reachable(List.of()));
        assertThat(it.recheck().get().get("status")).isEqualTo("up");
    }

    @Test
    @DisplayName("O-b4: kaynak bilgisi alarm bağlamına kalıcılaşır (snapshot whitelist)")
    void sourceKeysAreSnapshotted() {
        assertThat(EscalationService.RESOLVED_CONTEXT_KEYS).contains("integrity_scope", "problem_pages");
    }
}
