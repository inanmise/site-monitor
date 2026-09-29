package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ELLE KONTROL ALARM AÇMAZ (ürün kararı 2026-09-29, prod olayı).
 *
 * <p>Olay: kapsamlı yönetici Sentetik İzleme'de toplu "Şimdi Kontrol Et (39)" çalıştırdı. Her tetik ucu
 * ({@code POST /monitoring/<tür>/{id}/check}) sonucu kaydedip {@code SchedulerService.evaluate*Now} ile
 * zamanlayıcıyla AYNI değerlendirme hattına giriyordu ({@code handleSweepResults(tür, kalemler, manual=true)}).
 * {@code manual=true} yalnız ≥%50 toplu-kesinti bastırmasını ATLIYORDU — yani tam da "çok izleme birden düştü"
 * sinyalini yutan kapıyı kapatıyordu. Başarısız her elle kontrol teyit zinciri başlatıp alarm açtı; açık
 * (hayalet) alarmı olan izlemede ise {@code processConfirmedOutage} anında çağrılıp yarım kalmış ilk bildirimi /
 * günlük yeniden uyarıyı gönderdi. Yeni alarmlar {@code StormService.evaluate}'e girip org geneli fırtına üretti.
 *
 * <p>Kural (kullanıcı): elle kontrol (tekil ve toplu, TÜM türler) YENİ alarm açmaz; eskalasyon / yeniden uyarı /
 * fırtına tetiklemez; açık olayın bildirim sayaçlarını ilerletmez. Sonuç kaydı ucun işidir (burada değil).
 * Sağlıklıysa açık alarmı KAPATABİLİR. Başarısız elle kontrol sonraki zamanlanmış sweep'in normal kurallarla
 * alarm açmasını ENGELLEMEZ. Zamanlanmış davranış DEĞİŞMEZ.
 *
 * <p>Servis, kurucusu değişse de derlensin diye parametre TİPİNE göre sahte nesnelerle kurulur (paralel
 * geliştirmede bir bağımlılık eklenirse bu dosya kırılmaz).
 */
class ManualCheckNoAlarmTest {

    /** Elle tetiklenebilen (tetik ucu olan) her türün alarm tipleri — evaluate*Now'ların beslediği küme. */
    static final List<String> MANUAL_TYPES = List.of(
            EscalationService.TYPE_HTTP_DOWN,
            EscalationService.TYPE_KEYWORD,
            EscalationService.TYPE_PING_DOWN, EscalationService.TYPE_PING_SLOW,
            EscalationService.TYPE_PORT_DOWN,
            EscalationService.TYPE_DNS_FAILURE,
            EscalationService.TYPE_PAGE_DOWN, EscalationService.TYPE_PAGE_INTEGRITY,
            EscalationService.TYPE_PAGESPEED_DOWN, EscalationService.TYPE_PAGESPEED_SLOW,
            EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW,
            EscalationService.TYPE_DOMAINMON_UNKNOWN, EscalationService.TYPE_DOMAINMON_EXPIRY,
            EscalationService.TYPE_DOMAINMON_STATUS, EscalationService.TYPE_DOMAINMON_CHANGED,
            EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK, EscalationService.TYPE_DOMAINMON_BLACKLIST);

    private AlertEventRepository alertEventRepo;
    private EscalationService escalationService;
    private AppSettingsService appSettings;
    private NetworkOutageEventRepository networkOutageRepo;
    private MonitoringOutageService service;

    /** Kurucunun parametre tiplerine göre örnek kurar; verilmeyen her bağımlılık sahte nesnedir. */
    @SuppressWarnings("unchecked")
    static <T> T build(Class<T> type, Map<Class<?>, Object> provided) {
        try {
            Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
            Class<?>[] pt = c.getParameterTypes();
            Object[] args = new Object[pt.length];
            for (int i = 0; i < pt.length; i++) {
                args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
            }
            return (T) c.newInstance(args);
        } catch (Exception e) {
            throw new IllegalStateException("kurulamadı: " + type.getSimpleName(), e);
        }
    }

    /** schedule() görevini aynı iş parçacığında HEMEN koşturur — teyit/kurtarma zinciri deterministik. */
    private static ScheduledThreadPoolExecutor immediateExecutor() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                command.run();
                return null;
            }
        };
    }

    @BeforeEach
    void setUp() {
        alertEventRepo = mock(AlertEventRepository.class);
        escalationService = mock(EscalationService.class);
        appSettings = mock(AppSettingsService.class);
        networkOutageRepo = mock(NetworkOutageEventRepository.class);
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AlertEventRepository.class, alertEventRepo);
        provided.put(EscalationService.class, escalationService);
        provided.put(AppSettingsService.class, appSettings);
        provided.put(NetworkOutageEventRepository.class, networkOutageRepo);
        provided.put(JdbcTemplate.class, mock(JdbcTemplate.class));
        service = build(MonitoringOutageService.class, provided);

        // Ayar geçersiz kılma yok → çağıranın varsayılanı döner.
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        for (String f : List.of("uptimeAlertEnabled", "portAlertEnabled", "dnsAlertEnabled",
                "keywordAlertEnabled", "pingAlertEnabled")) {
            ReflectionTestUtils.setField(service, f, true);
        }
        ReflectionTestUtils.setField(service, "confirmAttempts", 3);
        ReflectionTestUtils.setField(service, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(service, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(service, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(service, "confirmExecutor", immediateExecutor());
        ReflectionTestUtils.setField(service, "recoveryExecutor", immediateExecutor());
        lenient().when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
    }

    private static Map<String, Object> result(String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        if ("down".equals(status)) m.put("error", "connect timed out");
        return m;
    }

    private static MonitoringOutageService.SweepItem item(String type, String domain, boolean up,
                                                          Map<String, Object> extra,
                                                          Supplier<Map<String, Object>> recheck) {
        return new MonitoringOutageService.SweepItem(type, domain, "d", up,
                up ? null : "connect timed out", extra, recheck);
    }

    private static AlertEvent openAlert(String domain, String type) {
        AlertEvent e = new AlertEvent();
        e.setId(77L);
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("WARNING");
        e.setTeamId(7L);
        e.setAcknowledged(false);
        e.setResolved(false);
        // Hayalet olay imzası: ilk bildirim damgası YOK → eski kod "yarım kalmış ilk bildirimi" hemen tamamlıyordu.
        e.setLastReAlertAt(null);
        return e;
    }

    private static Map<String, Object> ctx(Integer confirmAttempts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("team_id", 7L);
        m.put("monitor_id", 11L);
        if (confirmAttempts != null) m.put("monitor_confirm_attempts", confirmAttempts);
        return m;
    }

    // ── 1) Başarısız elle kontrol: yeni alarm YOK ──────────────────────────────────────────

    @Test
    @DisplayName("Elle kontrol DOWN + açık alarm yok → HİÇBİR türde teyit zinciri/alarm başlamaz (3 denemeli ve anında kip)")
    void manualDown_noOpenAlarm_opensNothing_everyType() {
        List<String> opened = new ArrayList<>();
        for (Integer attempts : new Integer[]{null, 0}) {          // null → varsayılan 3 deneme; 0 → anında kip
            for (String type : MANUAL_TYPES) {
                clearInvocations(escalationService);
                AtomicInteger rechecks = new AtomicInteger();
                String domain = "m-" + type.toLowerCase() + "-" + attempts + ".example.com";
                service.handleSweepResults(type, List.of(item(type, domain, false, ctx(attempts),
                        () -> { rechecks.incrementAndGet(); return result("down"); })), true);
                boolean alarm = !org.mockito.Mockito.mockingDetails(escalationService).getInvocations().stream()
                        .filter(inv -> inv.getMethod().getName().equals("processConfirmedOutage")).toList().isEmpty();
                if (alarm || rechecks.get() > 0) opened.add(type + (attempts == null ? "" : "(anında)"));
            }
        }
        assertThat(opened).as("elle kontrolde alarm/teyit zinciri başlatan türler").isEmpty();
        assertThat(service.activeConfirmations(null)).as("elle kontrol teyit zinciri bırakmamalı").isEmpty();
    }

    // ── 2) Açık (hayalet) alarm + başarısız elle kontrol: yeniden uyarı / eskalasyon / ilk bildirim YOK ──

    @Test
    @DisplayName("Elle kontrol DOWN + AÇIK alarm → processConfirmedOutage çağrılmaz (yeniden uyarı / eskalasyon / yarım ilk bildirim yok)")
    void manualDown_openAlarm_neverDispatches_everyType() {
        List<String> dispatched = new ArrayList<>();
        for (String type : MANUAL_TYPES) {
            clearInvocations(escalationService);
            String domain = "open-" + type.toLowerCase() + ".example.com";
            lenient().when(alertEventRepo.findOpenByDomainIn(anyCollection()))
                    .thenReturn(List.of(openAlert(domain, type)));
            service.handleSweepResults(type, List.of(item(type, domain, false, ctx(null), () -> result("down"))), true);
            boolean called = !org.mockito.Mockito.mockingDetails(escalationService).getInvocations().stream()
                    .filter(inv -> inv.getMethod().getName().equals("processConfirmedOutage")).toList().isEmpty();
            if (called) dispatched.add(type);
        }
        assertThat(dispatched).as("elle kontrolde açık alarmın bildirim hattını tetikleyen türler").isEmpty();
    }

    // ── 3) Sağlıklı elle kontrol açık alarmı KAPATABİLİR (varsayılan) ─────────────────────────

    @Test
    @DisplayName("Elle kontrol UP + açık alarm → alarm kapanır (kurtarma kuralı: 1 başarılı kontrol)")
    void manualUp_openAlarm_resolves() {
        String type = EscalationService.TYPE_SCRIPTED_FAIL;
        String domain = "senaryo-a";
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(openAlert(domain, type)));

        service.handleSweepResults(type, List.of(item(type, domain, true, ctx(null), () -> result("up"))), true);

        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(domain, type);
        verify(escalationService, never()).processConfirmedOutage(any(), any(), any(), any());
    }

    // ── 4) Elle kontrol ağ kesintisi kayıtlarına dokunmaz ─────────────────────────────────────

    @Test
    @DisplayName("Elle kontrol ağ-kesintisi bastırma kaydına DOKUNMAZ — sweep'in açtığı ONGOING kaydı kapatmaz")
    void manual_neverTouchesNetworkOutageBookkeeping() {
        String type = EscalationService.TYPE_HTTP_DOWN;
        // Zamanlanmış sweep: 3/3 ağ-sınıfı DOWN → bastırma açılır (NetworkOutageEvent ONGOING yazılır).
        List<MonitoringOutageService.SweepItem> sweep = new ArrayList<>();
        for (int i = 1; i <= 3; i++) sweep.add(item(type, "h" + i + ".example.com", false, ctx(null), () -> result("down")));
        service.handleSweepResults(type, sweep);
        clearInvocations(networkOutageRepo);

        // Kesinti sürerken kullanıcı TEK izlemeyi elle kontrol eder (sonuç sağlıklı ya da değil).
        service.handleSweepResults(type, List.of(item(type, "h1.example.com", true, ctx(null), () -> result("up"))), true);
        service.handleSweepResults(type, List.of(item(type, "h2.example.com", false, ctx(null), () -> result("down"))), true);

        // Eski kod elle yolda clearSuppression çağırıyor, sweep'in ONGOING kaydını "RESOLVED" işaretliyordu.
        verifyNoInteractions(networkOutageRepo);
    }

    // ── 5) Zamanlanmış davranış DEĞİŞMEDİ ───────────────────────────────────────────────────

    @Test
    @DisplayName("Zamanlanmış sweep DOWN + açık alarm yok → teyit (3 deneme) sonrası alarm AÇILIR (değişmedi)")
    void scheduledDown_stillConfirmsAndOpens() {
        String type = EscalationService.TYPE_SCRIPTED_FAIL;
        AtomicInteger rechecks = new AtomicInteger();
        service.handleSweepResults(type, List.of(item(type, "senaryo-b", false, ctx(null),
                () -> { rechecks.incrementAndGet(); return result("down"); })));

        assertThat(rechecks.get()).isEqualTo(3);
        verify(escalationService, times(1)).processConfirmedOutage(eq("senaryo-b"), eq(type), any(), any());
    }

    @Test
    @DisplayName("Zamanlanmış sweep DOWN + açık alarm → kesinti-sürüyor hattı (yeniden uyarı kadansı) işler (değişmedi)")
    void scheduledDown_openAlarm_stillRunsOngoingPath() {
        String type = EscalationService.TYPE_HTTP_DOWN;
        String domain = "https://site.example.com";
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(openAlert(domain, type)));

        service.handleSweepResults(type, List.of(item(type, domain, false, ctx(null), () -> result("down"))));

        verify(escalationService, times(1)).processConfirmedOutage(eq(domain), eq(type), any(), any());
    }

    // ── 6) Başarısız elle kontrol sonraki sweep'in alarm açmasını ENGELLEMEZ ─────────────────

    @Test
    @DisplayName("Elle kontrol DOWN (alarm yok) → ardından zamanlanmış sweep DOWN → TAM BİR alarm (sweep'ten)")
    void manualDown_thenScheduledSweep_opensExactlyOnce() {
        String type = EscalationService.TYPE_PORT_DOWN;
        String host = "db.example.com";
        service.handleSweepResults(type, List.of(item(type, host, false, ctx(null), () -> result("down"))), true);
        service.handleSweepResults(type, List.of(item(type, host, false, ctx(null), () -> result("down"))));

        // Eski kod: elle kontrol zaten bir alarm açıyordu (1) + sweep ikinciyi (2).
        verify(escalationService, times(1)).processConfirmedOutage(eq(host), eq(type), any(), any());
    }
}
