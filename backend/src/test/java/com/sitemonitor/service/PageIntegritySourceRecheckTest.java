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
        // Bulgu 4 (2026-11-13): hedefli kurtarma ölçümü ayrı yürütücüde koşar — bu sınıfın senaryoları AYNI iş parçacığında
        // koşan yürütücüyle eski (eşzamanlı) davranışı doğrular; eşzamansız davranış ayrı testte (probeRunsOffTheEvaluationThread).
        ReflectionTestUtils.setField(scheduler, "integrityProbeExecutor", (java.util.concurrent.Executor) Runnable::run);
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
    @DisplayName("O-b4 maliyet: yeniden ölçülen kaynak sayfa sayısı tavanlı (5); inceleme 2026-11-13 (ORTA 2): listeye "
            + "girmeyen sayfaların TARAMA bulgusu açılış teyidinde sayılır (yeniden ölçülen 5 sayfa temiz olsa da eşik tutar)")
    void recheckIsCapped() {
        PageMonitor m = crawlMonitor();
        List<PageCheckerService.ResourceIssue> many = new ArrayList<>();
        for (int i = 1; i <= 8; i++) many.add(broken(HOME + "sayfa-" + i));
        stub(HOME, "SITE_CRAWL", reachable(many));
        Map<String, Object> r = crawl(m);
        assertThat((List<?>) r.get("problem_pages")).hasSize(SchedulerService.INTEGRITY_RECHECK_MAX_PAGES);
        assertThat(r).containsEntry("problem_pages_truncated", true);
        assertThat((List<?>) r.get(SchedulerService.INTEGRITY_UNLISTED_ISSUES)).as("6.–8. sayfaların bulgusu").hasSize(3);

        scheduler.evaluatePageNow(m, r);
        MonitoringOutageService.SweepItem it = integrityItems().get(0);
        assertThat(it.ctxExtra()).containsEntry("problem_pages_truncated", true)
                .doesNotContainKey(SchedulerService.INTEGRITY_UNLISTED_ISSUES);   // alarm bağlamına girmez
        when(pageChecker.check(anyString(), eq("SINGLE_PAGE"), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(),
                anyInt(), anyBoolean())).thenReturn(reachable(List.of()));
        Map<String, Object> st = it.recheck().get();
        assertThat(st.get("status")).as("listedeki 5 sayfa temiz ama 6.–8. sayfaların tarama bulgusu eşiği (1) tutar")
                .isEqualTo("down");
        assertThat(String.valueOf(st.get("error"))).contains("tarama bulgusu");
        verify(pageChecker, times(SchedulerService.INTEGRITY_RECHECK_MAX_PAGES)).check(anyString(), eq("SINGLE_PAGE"),
                anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Bulgu 1/2: kırpılırsa EN ÇOK sorunlu 5 sayfa seçilir (ilk görülme sırasıyla); 5 ve altı kırpılmaz")
    void truncatedListPicksHeaviestPages() {
        java.util.LinkedHashMap<String, Integer> per = new java.util.LinkedHashMap<>();
        per.put("a", 1); per.put("b", 4); per.put("c", 1); per.put("d", 2); per.put("e", 1); per.put("f", 3); per.put("g", 1);
        assertThat(SchedulerService.integrityProblemPages(per)).containsExactly("a", "b", "c", "d", "f");
        java.util.LinkedHashMap<String, Integer> few = new java.util.LinkedHashMap<>();
        few.put("x", 1); few.put("y", 9);
        assertThat(SchedulerService.integrityProblemPages(few)).containsExactly("x", "y");

        // Tarama sonucunda da: 16 sorunlu kaynaklı sayfa ağır basar (ilk görülen 5 hafif sayfa değil)
        PageMonitor m = crawlMonitor();
        List<PageCheckerService.ResourceIssue> issues = new ArrayList<>();
        for (int i = 1; i <= 6; i++) issues.add(broken(HOME + "hafif-" + i));
        for (int k = 0; k < 16; k++)
            issues.add(new PageCheckerService.ResourceIssue(HOME + "img" + k + ".png", "IMG", HOME + "sozlesmeler",
                    "BROKEN", true, 404, 5L));
        stub(HOME, "SITE_CRAWL", reachable(issues));
        Map<String, Object> r = crawl(m);
        assertThat(r.get("problem_pages").toString()).contains(HOME + "sozlesmeler");
        assertThat((List<?>) r.get("problem_pages")).hasSize(5);
        assertThat(r).containsEntry("problem_pages_truncated", true);
    }

    @Test
    @DisplayName("Bulgu 2: yeniden ölçüm kaynakları adresle TEKİLLEŞTİRİR — ortak şablon kaynağı iki sayfada tek sayılır "
            + "(eşik 2 → tek kırık kaynakla 'down' değil)")
    void recheckDedupsSharedResource() {
        PageMonitor m = crawlMonitor();
        m.setMinBrokenResources(2);
        String deep2 = HOME + "hakkimizda";
        PageCheckerService.ResourceIssue shared = new PageCheckerService.ResourceIssue(HOME + "ortak/app.js", "JS", DEEP,
                "BROKEN", true, 404, 5L);
        PageCheckerService.ResourceIssue sharedOn2 = new PageCheckerService.ResourceIssue(HOME + "ortak/app.js", "JS", deep2,
                "BROKEN", true, 404, 5L);
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP, deep2),
                        "problem_pages_truncated", false));
        scheduler.evaluatePageNow(m, raw("main_up", true, "integrity_up", true, "check_mode", "SITE_CRAWL"));
        MonitoringOutageService.SweepItem it = integrityItems().get(0);
        stub(DEEP, "SINGLE_PAGE", reachable(List.of(shared)));
        stub(deep2, "SINGLE_PAGE", reachable(List.of(sharedOn2)));
        assertThat(it.recheck().get().get("status")).as("1 tekil kırık < eşik 2").isEqualTo("up");
        PageCheckerService.ResourceIssue other = new PageCheckerService.ResourceIssue(HOME + "baska.png", "IMG", deep2,
                "BROKEN", true, 404, 5L);
        stub(deep2, "SINGLE_PAGE", reachable(List.of(sharedOn2, other)));
        assertThat(it.recheck().get().get("status")).as("2 tekil kırık = eşik").isEqualTo("down");
    }

    @Test
    @DisplayName("Bulgu 1: kırpılmış listeli açık CRAWL alarmı sık taramada HEDEFLİ ölçümle kapanmaz (kapanış temiz günlük "
            + "taramaya kalır); bayraksız eski alarmda 5 sayfa = kırpılmış sayılır; bayrak false ise hedefli kapanış sürer")
    void truncatedOpenAlarm_noTargetedClosure() {
        PageMonitor m = crawlMonitor();
        List<String> five = List.of(HOME + "1", HOME + "2", HOME + "3", HOME + "4", HOME + "5");
        when(pageChecker.check(anyString(), eq("SINGLE_PAGE"), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(),
                anyInt(), anyBoolean())).thenReturn(reachable(List.of()));
        Map<String, Object> home = raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE");

        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", five, "problem_pages_truncated", true));
        scheduler.evaluatePageNow(m, home);
        assertThat(integrityItems()).as("kırpılmış → kalem yok").isEmpty();

        clearInvocations(outage);
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", five));   // eski alarm: bayrak yok
        scheduler.evaluatePageNow(m, home);
        assertThat(integrityItems()).as("bayraksız tavan dolu liste → kırpılmış say").isEmpty();
        verify(pageChecker, never()).check(anyString(), eq("SINGLE_PAGE"), anyInt(), anyInt(), anyInt(), any(), anyInt(),
                anyInt(), anyInt(), anyBoolean());

        clearInvocations(outage);
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", five, "problem_pages_truncated", false));
        scheduler.evaluatePageNow(m, home);
        List<MonitoringOutageService.SweepItem> rec = integrityItems();
        assertThat(rec).as("tam liste + temiz → hedefli kurtarma kalemi").hasSize(1);
        assertThat(rec.get(0).up()).isTrue();
    }

    @Test
    @DisplayName("Bulgu 1: günlük tarama hâlâ sorunlu → açık alarmın kaynağı (sayfalar + kırpılma + CRAWL) tazelenir; temiz "
            + "tarama tazelemez")
    @SuppressWarnings("unchecked")
    void crawlDownRefreshesOpenAlarmSource() {
        PageMonitor m = crawlMonitor();
        com.sitemonitor.repository.PageMonitorRepository repo = mock(com.sitemonitor.repository.PageMonitorRepository.class);
        when(repo.findByActiveTrue()).thenReturn(List.of(m));
        ReflectionTestUtils.setField(scheduler, "pageMonitorRepo", repo);
        List<PageCheckerService.ResourceIssue> many = new ArrayList<>();
        for (int i = 1; i <= 7; i++) many.add(broken(HOME + "s" + i));
        stub(HOME, "SITE_CRAWL", reachable(many));
        ReflectionTestUtils.invokeMethod(scheduler, "runPageCrawlsLocked");
        ArgumentCaptor<Map<String, Object>> owner = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(escalation).refreshOpenAlertContext(eq(HOME), eq(EscalationService.TYPE_PAGE_INTEGRITY), owner.capture(),
                patch.capture());
        assertThat(owner.getValue()).as("yalnız bu izlemenin alarmı (DÜŞÜK 7)").containsEntry("monitor_id", 6L)
                .containsEntry("team_id", 7L);
        assertThat(patch.getValue()).containsEntry("integrity_scope", "CRAWL").containsEntry("problem_pages_truncated", true);
        assertThat((List<String>) patch.getValue().get("problem_pages")).hasSize(5);

        clearInvocations(escalation);
        stub(HOME, "SITE_CRAWL", reachable(List.of()));   // temiz tarama → kaynak tazelenmez (kurtarma yolu ayrı)
        ReflectionTestUtils.invokeMethod(scheduler, "runPageCrawlsLocked");
        verify(escalation, never()).refreshOpenAlertContext(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Bulgu 4: hedefli ölçüm değerlendirme döngüsünün DIŞINDA koşar — çağrı ölçümü beklemez, sonuç SONRAKİ "
            + "değerlendirmede okunur; sayfa listesi değişince eski sonuç kanıt sayılmaz")
    void probeRunsOffTheEvaluationThread() {
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> queued = new java.util.concurrent.ConcurrentLinkedQueue<>();
        ReflectionTestUtils.setField(scheduler, "integrityProbeExecutor", (java.util.concurrent.Executor) queued::add);
        PageMonitor m = crawlMonitor();
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));
        stub(DEEP, "SINGLE_PAGE", reachable(List.of()));
        Map<String, Object> r = raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE");

        List<MonitoringOutageService.SweepItem> d1 = new ArrayList<>(), i1 = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, d1, i1, true);
        assertThat(i1).as("ölçüm başladı ama çağrı onu beklemedi → bu tık kalem yok").isEmpty();
        assertThat(queued).hasSize(1);
        verify(pageChecker, never()).check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(),
                anyInt(), anyBoolean());

        List<MonitoringOutageService.SweepItem> d2 = new ArrayList<>(), i2 = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, d2, i2, false);
        assertThat(i2).as("ölçüm hâlâ sürüyor → ikinci ölçüm yok, kalem yok").isEmpty();
        assertThat(queued).hasSize(1);

        queued.poll().run();   // ölçüm (ayrı yürütücüde) biter: sayfa temiz
        List<MonitoringOutageService.SweepItem> d3 = new ArrayList<>(), i3 = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, d3, i3, true);
        assertThat(i3).as("hazır 'up' sonucu tüketilir → kurtarma kalemi").hasSize(1);
        assertThat(i3.get(0).up()).isTrue();

        // Sayfa listesi değişti (günlük tarama tazeledi) → eski sonuç kanıt değil, yenisi başlar
        List<MonitoringOutageService.SweepItem> d4 = new ArrayList<>(), i4 = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, d4, i4, false);   // elle: kadansa takılmaz
        assertThat(queued).hasSize(1);
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP, HOME + "yeni")));
        queued.poll().run();
        List<MonitoringOutageService.SweepItem> d5 = new ArrayList<>(), i5 = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, d5, i5, false);
        assertThat(i5).as("eski listeyle ölçülmüş sonuç kullanılmaz").isEmpty();
        assertThat(queued).as("yeni liste için yeni ölçüm").hasSize(1);
    }

    @Test
    @DisplayName("Bulgu 4: 'hâlâ sorunlu' sonucu tüketilince kalem yok ve SIRADAKİ ölçüm aynı değerlendirmede başlar")
    void probeNotUpStartsTheNextAtOnce() {
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> queued = new java.util.concurrent.ConcurrentLinkedQueue<>();
        ReflectionTestUtils.setField(scheduler, "integrityProbeExecutor", (java.util.concurrent.Executor) queued::add);
        PageMonitor m = crawlMonitor();
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));
        stub(DEEP, "SINGLE_PAGE", reachable(List.of(broken(DEEP))));
        Map<String, Object> r = raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE");

        List<MonitoringOutageService.SweepItem> d = new ArrayList<>(), i = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, d, i, false);
        queued.poll().run();   // sonuç: hâlâ kırık
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, d, i, false);
        assertThat(i).as("hâlâ sorunlu → kalem yok").isEmpty();
        assertThat(queued).as("sıradaki ölçüm hemen başladı").hasSize(1);

        stub(DEEP, "SINGLE_PAGE", reachable(List.of()));
        queued.poll().run();   // düzeldi
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, d, i, false);
        assertThat(i).hasSize(1);
        assertThat(i.get(0).up()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private Map<Long, SchedulerService.IntegrityProbe> probes() {
        return (Map<Long, SchedulerService.IntegrityProbe>) ReflectionTestUtils.getField(scheduler, "integrityProbes");
    }

    private void evaluateHome(PageMonitor m, List<MonitoringOutageService.SweepItem> integ) {
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m,
                raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE"), new ArrayList<>(), integ, false);
    }

    @Test
    @DisplayName("İnceleme 2026-11-13 (ORTA 3): bayatlamış ama BİTMEMİŞ ölçüm varken aynı izleme için ikinci iş gönderilmez "
            + "(sayfa listesi değişti / alarm kapandı); bitince sonucu atılır ve sıradaki başlar")
    void staleInFlightProbeIsNotDuplicated() {
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> queued = new java.util.concurrent.ConcurrentLinkedQueue<>();
        ReflectionTestUtils.setField(scheduler, "integrityProbeExecutor", (java.util.concurrent.Executor) queued::add);
        PageMonitor m = crawlMonitor();
        String other = HOME + "yeni";
        stub(DEEP, "SINGLE_PAGE", reachable(List.of()));
        stub(other, "SINGLE_PAGE", reachable(List.of()));
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));
        List<MonitoringOutageService.SweepItem> integ = new ArrayList<>();
        evaluateHome(m, integ);
        assertThat(queued).hasSize(1);

        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP, other)));
        evaluateHome(m, integ);
        assertThat(queued).as("liste değişti ama eski iş kuyrukta → ikinci iş YOK").hasSize(1);
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY)).thenReturn(null);   // alarm kapandı
        evaluateHome(m, integ);
        assertThat(queued).hasSize(1);
        assertThat(probes()).as("uçuştaki iş işaretli kalır").containsKey(6L);

        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP, other)));
        queued.poll().run();   // eski iş biter (eski liste, geçersiz) — sonucu kanıt sayılmaz
        evaluateHome(m, integ);
        assertThat(integ).as("eski işin up sonucu kullanılmaz").isEmpty();
        assertThat(queued).as("eski iş bitti → sıradaki başladı").hasSize(1);
        queued.poll().run();
        evaluateHome(m, integ);
        assertThat(integ).hasSize(1);
        assertThat(integ.get(0).up()).isTrue();
    }

    @Test
    @DisplayName("İnceleme 2026-11-13 (ORTA 3): tazelik ölçümün BİTİŞİNDEN sayılır — kuyrukta 15 dk bekleyip yeni biten "
            + "sonuç kullanılır; bitişinden 15 dk sonra okunan sonuç bayattır; 30 dk'dan uzun SÜREN iş takılı sayılıp bırakılır")
    void probeFreshnessCountsFromFinish() {
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> queued = new java.util.concurrent.ConcurrentLinkedQueue<>();
        ReflectionTestUtils.setField(scheduler, "integrityProbeExecutor", (java.util.concurrent.Executor) queued::add);
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        ReflectionTestUtils.setField(scheduler, "integrityProbeClock", (java.util.function.LongSupplier) clock::get);
        PageMonitor m = crawlMonitor();
        stub(DEEP, "SINGLE_PAGE", reachable(List.of()));
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));
        List<MonitoringOutageService.SweepItem> integ = new ArrayList<>();
        evaluateHome(m, integ);
        clock.addAndGet(15 * 60_000L);   // kuyrukta 15 dk bekledi (geçerlilik: 2 × 300 sn aralık + 60 sn = 11 dk)
        queued.poll().run();
        evaluateHome(m, integ);
        assertThat(integ).as("yeni biten sonuç taze").hasSize(1);

        integ.clear();
        evaluateHome(m, integ);   // yeni ölçüm başlar
        queued.poll().run();
        clock.addAndGet(15 * 60_000L);   // bitişinden 15 dk sonra okunuyor
        evaluateHome(m, integ);
        assertThat(integ).as("bitişinden geçerlilik süresi geçti → bayat").isEmpty();
        assertThat(queued).as("bayat sonuç atıldı → yenisi başladı").hasSize(1);

        probes().get(6L).startedAt().set(clock.get());   // iş başladı ama bitmiyor (takıldı)
        clock.addAndGet(SchedulerService.INTEGRITY_PROBE_STUCK_MS - 60_000L);
        evaluateHome(m, integ);
        assertThat(queued).as("süren iş: ikinci iş yok").hasSize(1);
        clock.addAndGet(2 * 60_000L);
        evaluateHome(m, integ);
        assertThat(queued).as("takılı iş bırakıldı → yenisi").hasSize(2);
    }

    /** Silahlanınca ilk iki {@code get} çağrısını bir bariyerde buluşturur — iki değerlendirme aynı bitmiş ölçümü GÖRÜR. */
    @SuppressWarnings("serial")
    private static final class RacingProbes extends java.util.concurrent.ConcurrentHashMap<Long, SchedulerService.IntegrityProbe> {
        final java.util.concurrent.atomic.AtomicInteger armed = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);

        @Override
        public SchedulerService.IntegrityProbe get(Object key) {
            SchedulerService.IntegrityProbe v = super.get(key);
            if (armed.getAndDecrement() > 0) {
                try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { /* süre doldu */ }
            }
            return v;
        }
    }

    @Test
    @DisplayName("Gerileme taraması 2026-11-13 (bulgu 2): elle değerlendirme ile sık tarama tıkı AYNI bitmiş ölçümü görse de "
            + "sonucu yalnız girdiyi silen tüketir → tek ölçümden TEK 'up' kalemi (kurtarma sayacı +2 ilerlemez)")
    void finishedProbeIsConsumedOnce() throws Exception {
        RacingProbes racing = new RacingProbes();
        ReflectionTestUtils.setField(scheduler, "integrityProbes", racing);
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> queued = new java.util.concurrent.ConcurrentLinkedQueue<>();
        ReflectionTestUtils.setField(scheduler, "integrityProbeExecutor", (java.util.concurrent.Executor) queued::add);
        PageMonitor m = crawlMonitor();
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));
        stub(DEEP, "SINGLE_PAGE", reachable(List.of()));
        evaluateHome(m, new ArrayList<>());   // ölçüm başlar
        queued.poll().run();                  // ölçüm biter: temiz ("up")

        racing.armed.set(2);
        List<MonitoringOutageService.SweepItem> a = java.util.Collections.synchronizedList(new ArrayList<>());
        List<MonitoringOutageService.SweepItem> b = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var fa = pool.submit(() -> evaluateHome(m, a));
            var fb = pool.submit(() -> evaluateHome(m, b));
            fa.get(10, java.util.concurrent.TimeUnit.SECONDS);
            fb.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(racing.barrier.isBroken()).as("iki değerlendirme aynı ölçümü gördü (bariyer buluştu)").isFalse();
        List<MonitoringOutageService.SweepItem> all = new ArrayList<>(a);
        all.addAll(b);
        assertThat(all).as("bitmiş ölçüm yalnız BİR kez tüketilir").hasSize(1);
        assertThat(all.get(0).up()).isTrue();
        assertThat(queued).as("kaybeden yeni ölçüm de başlatmaz").isEmpty();
    }

    @Test
    @DisplayName("İnceleme 2026-11-13 (DÜŞÜK 4): kuyrukta bekleyen ölçüm kapanış başladıktan sonra ÖLÇMEZ (skipped)")
    void queuedProbeDoesNotMeasureAfterShutdownStarted() {
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> queued = new java.util.concurrent.ConcurrentLinkedQueue<>();
        ReflectionTestUtils.setField(scheduler, "integrityProbeExecutor", (java.util.concurrent.Executor) queued::add);
        PageMonitor m = crawlMonitor();
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));
        evaluateHome(m, new ArrayList<>());
        SchedulerService.IntegrityProbe p = probes().get(6L);
        ReflectionTestUtils.setField(scheduler, "shutdownStarted", true);
        queued.poll().run();
        verify(pageChecker, never()).check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(),
                anyInt(), anyBoolean());
        assertThat(p.result().join().status()).isEqualTo("skipped");
    }

    @Test
    @DisplayName("O-b4: ANA SAYFA kaynaklı alarm (crawl izlemesi) ana sayfa temizse kapanır")
    void homeScopedAlarm_closesOnCleanHome() {
        PageMonitor m = crawlMonitor();
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY)).thenReturn(Map.of("integrity_scope", "HOME"));
        scheduler.evaluatePageNow(m, raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE"));
        List<MonitoringOutageService.SweepItem> items = integrityItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).up()).isTrue();
        stub(HOME, "SINGLE_PAGE", reachable(List.of()));
        assertThat(items.get(0).recheck().get().get("status")).isEqualTo("up");
    }

    @Test
    @DisplayName("FIX oto-çözülme: crawl kaynaklı alarm + temiz ANA SAYFA turu → sorunlu sayfa HEDEFLİ ölçülür; "
            + "temizse sağlıklı kurtarma kalemi, hâlâ kırıksa kalem yok (D-7 korunur)")
    void crawlScopedAlarm_targetedRecoveryOnFrequentHomeSweep() {
        PageMonitor m = crawlMonitor();
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));

        // Sorunlu sayfa HÂLÂ kırık → sık taramada kalem üretilmez (korunumlu: alarm açık kalır, günlük crawl doğrular).
        stub(DEEP, "SINGLE_PAGE", reachable(List.of(broken(DEEP))));
        scheduler.evaluatePageNow(m, raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE"));
        assertThat(integrityItems()).as("sorunlu sayfa hâlâ kırık → kalem yok").isEmpty();

        // Sorunlu sayfa ARTIK temiz → sağlıklı kurtarma kalemi (eskiden yalnız günlük crawl kapatabiliyordu).
        clearInvocations(outage);
        stub(DEEP, "SINGLE_PAGE", reachable(List.of()));
        scheduler.evaluatePageNow(m, raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE"));
        List<MonitoringOutageService.SweepItem> rec = integrityItems();
        assertThat(rec).as("sorunlu sayfa temiz → sağlıklı kurtarma kalemi").hasSize(1);
        assertThat(rec.get(0).up()).isTrue();
        assertThat(rec.get(0).ctxExtra()).containsEntry("integrity_scope", "CRAWL")
                .containsEntry("problem_pages", List.of(DEEP));
        // Kurtarma zinciri yine sorunlu sayfaları ölçer (ana sayfayı değil).
        stub(DEEP, "SINGLE_PAGE", reachable(List.of(broken(DEEP))));
        assertThat(rec.get(0).recheck().get().get("status")).as("kurtarma yeniden ölçümü sorunlu sayfaya bakar").isEqualTo("down");

        // Kaynaksız (eski) crawl alarmı → hedef yok → kalem yok (günlük crawl kapatır, D-7).
        clearInvocations(outage);
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL"));
        scheduler.evaluatePageNow(m, raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE"));
        assertThat(integrityItems()).as("kaynaksız crawl alarmı → kalem yok").isEmpty();
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

    @Test
    @DisplayName("FIX perf: zamanlı sweep'te hedefli kurtarma ölçümü izleme başına kadenslenir (ardışık tık ölçmez); "
            + "elle kontrol throttle'a tabi değildir")
    @SuppressWarnings("unchecked")
    void scheduledTargetedRecovery_isThrottledPerMonitor_manualIsNot() {
        PageMonitor m = crawlMonitor();
        when(escalation.openAlertContext(HOME, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(Map.of("integrity_scope", "CRAWL", "problem_pages", List.of(DEEP)));
        stub(DEEP, "SINGLE_PAGE", reachable(List.of()));   // sorunlu sayfa temiz → ölçülürse sağlıklı kurtarma kalemi
        Map<String, Object> r = raw("main_up", true, "integrity_up", true, "check_mode", "SINGLE_PAGE");

        // Zamanlı sweep (scheduledSweep=true): ilk tık ölçer → kalem; kadence içindeki ikinci tık ÖLÇMEZ → kalem yok (nötr).
        List<MonitoringOutageService.SweepItem> down1 = new ArrayList<>(), integ1 = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, down1, integ1, true);
        assertThat(integ1).as("ilk zamanlı tık ölçer → sağlıklı kurtarma kalemi").hasSize(1);
        assertThat(integ1.get(0).up()).isTrue();

        List<MonitoringOutageService.SweepItem> down2 = new ArrayList<>(), integ2 = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, down2, integ2, true);
        assertThat(integ2).as("kadence içindeki ikinci zamanlı tık ölçmez → kalem yok (MOS sayacı nötr)").isEmpty();

        // Elle kontrol (scheduledSweep=false): throttle'a tabi değil → her seferinde ölçer.
        List<MonitoringOutageService.SweepItem> down3 = new ArrayList<>(), integ3 = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, r, down3, integ3, false);
        assertThat(integ3).as("elle kontrol throttle'a tabi değil → ölçer").hasSize(1);
    }
}
