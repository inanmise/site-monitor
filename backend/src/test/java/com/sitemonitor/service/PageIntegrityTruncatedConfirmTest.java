package com.sitemonitor.service;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * İnceleme 2026-11-13 (ORTA 2, sayfa bütünlüğü): günlük tarama 5'ten FAZLA sayfada sorun bulduğunda sorunlu sayfa listesi
 * kırpılır ve açılış teyidi yalnız listedeki 5 sayfayı yeniden ölçer. Eskiden eşik bu 5 sayfanın toplamından büyükse ya da
 * ilk 5 sayfa düzelip 6.+ sayfalar bozuk kaldıysa teyit "skipped" deyip zinciri iptal ediyor, alarm HİÇ açılmıyordu (her gün
 * aynı döngü). Artık listeye girmeyen sayfaların TARAMA bulgusu teyitte sayılır. Bu sınıf GERÇEK teyit zinciriyle
 * ({@link MonitoringOutageService}) doğrular — zincir "skipped"de iptal olur, "up"ta geçici sayar, "down"da alarmı açar.
 */
class PageIntegrityTruncatedConfirmTest {

    private static final String HOME = "https://site.example.com/";
    private static final int PAGES = 8;   // 5 yeniden ölçülür, 3 listeye girmez

    private PageCheckerService pageChecker;
    private SchedulerService scheduler;
    private EscalationService esc;
    private MonitoringMultiPodKit.Manual chain;
    private MonitoringOutageService mos;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    private static AppSettingsService defaults() {
        AppSettingsService s = mock(AppSettingsService.class);
        lenient().when(s.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(s.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(s.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(s.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        return s;
    }

    @BeforeEach
    void setUp() throws Exception {
        pageChecker = mock(PageCheckerService.class);
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(MonitoringOutageService.class, mock(MonitoringOutageService.class));
        provided.put(AppSettingsService.class, defaults());
        provided.put(EscalationService.class, mock(EscalationService.class));
        provided.put(CertificateInventoryRepository.class, mock(CertificateInventoryRepository.class));
        scheduler = build(SchedulerService.class, provided);
        ReflectionTestUtils.setField(scheduler, "keywordHeaderSecrets", mock(KeywordHeaderSecrets.class));
        ReflectionTestUtils.setField(scheduler, "pageCheckerService", pageChecker);
        ReflectionTestUtils.setField(scheduler, "pageCheckRepo", mock(com.sitemonitor.repository.PageCheckRepository.class));
        ReflectionTestUtils.setField(scheduler, "pageResourceIssueRepo", mock(com.sitemonitor.repository.PageResourceIssueRepository.class));

        // Gerçek teyit zinciri (DisabledTypeConfirmationTest deseni): adımlar elle koşturulur.
        esc = mock(EscalationService.class);
        AlertEventRepository alerts = mock(AlertEventRepository.class);
        when(alerts.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        mos = new MonitoringOutageService(alerts, esc, mock(JdbcTemplate.class), mock(DnsRecordRepository.class),
                mock(DnsMonitorRepository.class), defaults(), mock(NetworkOutageEventRepository.class));
        ReflectionTestUtils.setField(mos, "confirmAttempts", 2);
        ReflectionTestUtils.setField(mos, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(mos, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(mos, "bulkMinErrors", 3);
        chain = new MonitoringMultiPodKit.Manual();
        ReflectionTestUtils.setField(mos, "confirmExecutor", chain);
        ReflectionTestUtils.setField(mos, "recoveryExecutor", MonitoringMultiPodKit.immediate());
    }

    private static PageMonitor monitor(int minBroken) {
        PageMonitor m = new PageMonitor();
        m.setId(41L); m.setName("Site"); m.setUrl(HOME); m.setTeamId(7L); m.setMode("SITE_CRAWL");
        m.setConfirmAttempts(2);
        m.setMinBrokenResources(minBroken);
        return m;
    }

    private static String page(int n) {
        return HOME + "sayfa-" + n;
    }

    private static PageCheckerService.ResourceIssue broken(int n) {
        return new PageCheckerService.ResourceIssue(page(n) + "/gorsel.png", "IMG", page(n), "BROKEN", true, 404, 9L);
    }

    private static PageCheckerService.PageCheckResult reachable(List<PageCheckerService.ResourceIssue> issues) {
        return new PageCheckerService.PageCheckResult(issues.isEmpty() ? "OK" : "DEGRADED", true, 200, 120L, 10,
                issues.size(), 0, 0, PAGES, null, null, null, issues);
    }

    private void stub(String url, String mode, PageCheckerService.PageCheckResult res) {
        when(pageChecker.check(eq(url), eq(mode), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(res);
    }

    /** Günlük tarama: her sayfada 1 kırık; yeniden ölçümde {@code stillBroken} sayfalar (1..5) hâlâ kırık. */
    private void runConfirmChain(PageMonitor m, java.util.Set<Integer> stillBroken) {
        List<PageCheckerService.ResourceIssue> all = new ArrayList<>();
        for (int i = 1; i <= PAGES; i++) all.add(broken(i));
        stub(HOME, "SITE_CRAWL", reachable(all));
        for (int i = 1; i <= PAGES; i++)
            stub(page(i), "SINGLE_PAGE", reachable(stillBroken.contains(i) ? List.of(broken(i)) : List.of()));
        Map<String, Object> r = ReflectionTestUtils.invokeMethod(scheduler, "recheckPage", m, false, "SITE_CRAWL");
        assertThat(r).containsEntry("problem_pages_truncated", true);
        List<MonitoringOutageService.SweepItem> down = new ArrayList<>(), integ = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, down, integ, true);
        assertThat(integ).hasSize(1);
        assertThat(integ.get(0).up()).isFalse();
        mos.handleSweepResults(EscalationService.TYPE_PAGE_INTEGRITY, integ);   // zamanlı tur → teyit zinciri
        chain.drain();
    }

    @Test
    @DisplayName("eşik (7) en sorunlu 5 sayfanın toplamından (5) büyük — listedeki sayfalar hâlâ kırık + listeye girmeyen 3 "
            + "sayfanın tarama bulgusu = 8 ≥ 7 → alarm AÇILIR (eskiden 'skipped' → zincir iptal, alarm hiç açılmıyordu)")
    void thresholdAboveTopFive_alarmOpens() {
        runConfirmChain(monitor(7), java.util.Set.of(1, 2, 3, 4, 5));
        verify(esc, times(1)).processConfirmedOutage(eq(HOME), eq(EscalationService.TYPE_PAGE_INTEGRITY), anyString(), any());
    }

    @Test
    @DisplayName("ilk 5 sayfa düzeldi, 6.+ sayfalar hâlâ bozuk (eşik 1) → alarm AÇILIR")
    void topFiveFixedRestStillBroken_alarmOpens() {
        runConfirmChain(monitor(1), java.util.Set.of());
        verify(esc, times(1)).processConfirmedOutage(eq(HOME), eq(EscalationService.TYPE_PAGE_INTEGRITY), anyString(), any());
    }

    // ── Gerileme taraması 2026-11-13 (bulgu 1): paylaşılan şablon kaynağı ──────────────────────────────────────────────
    private static final String SHARED = HOME + "assets/ortak.css";

    /** Tarama: sayfa 1..5'te İKİŞER kırık (listeye girer), paylaşılan kaynak yalnız ilk görüldüğü ANA SAYFAYA atfedilir (1 sorun
     *  → listeye girmez, "unlisted"). Teyit: sayfa 1..5 yeniden ölçülür, kendi kırıkları geçmiştir; {@code sharedOnPages}
     *  doğruysa paylaşılan kaynak o sayfalarda da yüklenip TEMİZ çıkar. */
    private void runSharedResourceChain(boolean sharedOnPages) {
        List<PageCheckerService.ResourceIssue> all = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            all.add(new PageCheckerService.ResourceIssue(page(i) + "/a.png", "IMG", page(i), "BROKEN", true, 404, 9L));
            all.add(new PageCheckerService.ResourceIssue(page(i) + "/b.png", "IMG", page(i), "BROKEN", true, 404, 9L));
        }
        all.add(new PageCheckerService.ResourceIssue(SHARED, "CSS", HOME, "BROKEN", true, 502, 9L));   // geçici CDN takılması
        stub(HOME, "SITE_CRAWL", reachable(all));
        for (int i = 1; i <= 5; i++) {
            java.util.Set<String> clean = sharedOnPages
                    ? java.util.Set.of(SHARED, page(i) + "/a.png", page(i) + "/b.png")
                    : java.util.Set.of(page(i) + "/a.png", page(i) + "/b.png");
            stub(page(i), "SINGLE_PAGE", new PageCheckerService.PageCheckResult("OK", true, 200, 120L, clean.size(),
                    0, 0, 0, 1, null, null, null, List.of(), null, clean));
        }
        PageMonitor m = monitor(1);
        Map<String, Object> r = ReflectionTestUtils.invokeMethod(scheduler, "recheckPage", m, false, "SITE_CRAWL");
        assertThat(r).containsEntry("problem_pages_truncated", true);
        @SuppressWarnings("unchecked") List<String> listed = (List<String>) r.get("problem_pages");
        assertThat(listed).as("ana sayfa listeye girmez").doesNotContain(HOME).hasSize(5);
        List<MonitoringOutageService.SweepItem> down = new ArrayList<>(), integ = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, down, integ, true);
        assertThat(integ).hasSize(1);
        assertThat(integ.get(0).up()).isFalse();
        mos.handleSweepResults(EscalationService.TYPE_PAGE_INTEGRITY, integ);   // zamanlı tur → teyit zinciri
        chain.drain();
    }

    @Test
    @DisplayName("bulgu 1: paylaşılan kaynak listedeki sayfalarda yeniden ölçülüp TEMİZ çıktı → taramanın listeye girmeyen "
            + "(ana sayfa) bayat kopyası sayılmaz → geçici, alarm AÇILMAZ (eskiden her teyit 'down' → sahte alarm ~24 sa)")
    void sharedResourceRemeasuredClean_unlistedCopyIgnored() {
        runSharedResourceChain(true);
        verify(esc, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("bulgu 1 kontrol: paylaşılan kaynak listedeki sayfalarda yeniden ÖLÇÜLMEDİYSE listeye girmeyen sayfanın "
            + "bulgusu kötümser sayılmaya devam eder → alarm AÇILIR")
    void sharedResourceNotRemeasured_stillCounts() {
        runSharedResourceChain(false);
        verify(esc, times(1)).processConfirmedOutage(eq(HOME), eq(EscalationService.TYPE_PAGE_INTEGRITY), anyString(), any());
    }

    @Test
    @DisplayName("kontrol: ölçülmeyen sayfalar kötümser sayıldığı hâlde eşik tutmuyor (2 + 3 = 5 < 7) → geçici, alarm yok")
    void belowThresholdEvenPessimistic_transient() {
        runConfirmChain(monitor(7), java.util.Set.of(1, 2));
        verify(esc, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
    }
}
