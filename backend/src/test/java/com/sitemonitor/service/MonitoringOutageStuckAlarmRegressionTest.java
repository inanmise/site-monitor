package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression (prod 2026-09-29): izleme uzun süredir SAĞLIKLI ama açık alarm KAPANMIYOR.
 *
 * <p>Vaka 1 — bağımsız Port izlemesi (app.example.com:443/TCP, WARNING): 01:22'de kısa kesinti, alarm açıldı; sonraki
 * 10 saatte her kontrol "Open · Connection OK", alarm açık kaldı ve kontrol geçmişinde TEK bir kurtarma doğrulaması
 * (aktif recovery re-check'i) bile yok. Vaka 2 — bağımsız Sentetik izleme (ad "/" içeriyor), 4,5 gündür 292/292 geçti,
 * SCRIPTED_FAIL açık.
 *
 * <p>Kapanış yolunun (MonitoringOutageService.handleSweepResults) üç ayrı yerde kilitlenebildiği bulundu:
 * <ol>
 *   <li><b>K1 — toplu-kesinti bastırması kurtarmayı da kesiyordu.</b> Bir turda hedeflerin ≥%50'si (en az 3) ağ-sınıfı
 *       hatayla düşükse metot HİÇBİR alan adını işlemeden dönüyordu: yeni alarm açılmaması doğru, ama SAĞLIKLI dönen
 *       ve açık alarmı olan hedefin kurtarması da hiç başlamıyordu. Filoda sürekli ulaşılamayan hedefler varsa (pod'dan
 *       doğrudan erişimi olmayan envanter host'ları, zaman aşımına düşen k6 senaryoları) bastırma kalıcılaşır ve
 *       bu türün hiçbir alarmı kapanmaz.</li>
 *   <li><b>K2 — kurtarma canlılığı yalnız bellekteki aktif zincire bağlıydı.</b> Aktif kurtarmada (varsayılan: 3 kontrol,
 *       30 sn) pasif sayaç SİLİNİYOR ve karar tek bir zamanlanmış zincire bırakılıyordu; zincir kaydı haritada kalıp
 *       görevi hiç koşmazsa (ya da düşerse) sonraki her sağlıklı tur "zaten sürüyor" deyip atlıyordu — alarm yeniden
 *       başlatmaya kadar açık kalıyordu. Artık her sağlıklı tur pasif sayacı da artırır (uzlaştırma: N ardışık sağlıklı
 *       tur = kapat) ve süresi aşılmış zincir değiştirilir.</li>
 *   <li><b>K5 — tür bazında alarm bildirimleri kapatıldığında açık alarmlar donuyordu.</b> {@code *.alert-enabled=false}
 *       iken kontroller sürse de metot en başta dönüyordu; açık alarm hiçbir zaman kapanmıyordu.</li>
 * </ol>
 * Ek olarak D2: aynı anahtarı (alan adı + tür) paylaşan iki izlemeden biri DOWN iken diğerinin sağlıklı turu alarmı
 * KAPATMAMALI (bölünmüş turlarda eskiden kapatıp hemen yeniden açıyordu).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringOutageStuckAlarmRegressionTest {

    @Mock AlertEventRepository alertEventRepo;
    @Mock EscalationService escalationService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock DnsMonitorRepository dnsMonitorRepo;
    @Mock AppSettingsService appSettings;
    @Mock NetworkOutageEventRepository networkOutageRepo;

    private MonitoringOutageService service;

    private static final String PORT = EscalationService.TYPE_PORT_DOWN;
    private static final String SCRIPTED = EscalationService.TYPE_SCRIPTED_FAIL;
    private static final String HOST = "app.example.com";
    /** Vaka 2'deki gibi "/" içeren bir senaryo adı (alarm anahtarı = izleme adı). */
    private static final String SCENARIO = "Takım A / Giriş Akışı";

    /** schedule() çağrısını anında, aynı thread'de koşturan zamanlayıcı. */
    private static ScheduledThreadPoolExecutor immediateExecutor() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                command.run();
                return null;
            }
        };
    }

    /** Görevleri kuyrukta tutan, testin elle ilerlettiği zamanlayıcı — "hiç koşmayan zincir" için de kullanılır. */
    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        ManualScheduler() { super(1); }
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            queue.add(command);
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        service = newService();
    }

    /** Yeni örnek = uygulama yeniden başlatıldı: bellek-içi teyit/kurtarma durumu boş. */
    private MonitoringOutageService newService() {
        MonitoringOutageService s = new MonitoringOutageService(alertEventRepo, escalationService, jdbcTemplate,
                dnsRecordRepo, dnsMonitorRepo, appSettings, networkOutageRepo);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        ReflectionTestUtils.setField(s, "uptimeAlertEnabled", true);
        ReflectionTestUtils.setField(s, "portAlertEnabled", true);
        ReflectionTestUtils.setField(s, "dnsAlertEnabled", true);
        ReflectionTestUtils.setField(s, "keywordAlertEnabled", true);
        ReflectionTestUtils.setField(s, "pingAlertEnabled", true);
        ReflectionTestUtils.setField(s, "confirmAttempts", 3);
        ReflectionTestUtils.setField(s, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(s, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(s, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(s, "confirmExecutor", immediateExecutor());
        ReflectionTestUtils.setField(s, "recoveryExecutor", immediateExecutor());
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        return s;
    }

    private void openAlarm(String domain, String type) {
        openAlarm(domain, type, 11L);
    }

    /** Gerçek biçimli açık olay: bağımsız izlemenin açtığı olay takım damgası (7) + bağlam anlık görüntüsü taşır. */
    private AlertEvent openAlarm(String domain, String type, long openerMonitorId) {
        AlertEvent e = openEvent(domain, type, openerMonitorId, 7L);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
        return e;
    }

    private static AlertEvent openEvent(String domain, String type, long openerMonitorId, long teamId) {
        AlertEvent e = new AlertEvent();
        e.setId(501L);
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("WARNING");
        e.setTeamId(teamId);
        e.setContextJson("{\"monitor_id\":" + openerMonitorId + ",\"team_id\":" + teamId + ",\"standalone\":true}");
        e.setAcknowledged(true);   // vaka 1: kullanıcı onaylamıştı — onay kapanışı ETKİLEMEMELİ
        e.setResolved(false);
        e.setCreatedAt("2026-09-28T22:22:00");
        return e;
    }

    private static Map<String, Object> up() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "up");
        return m;
    }

    private static Map<String, Object> down(String error) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "down");
        m.put("error", error);
        return m;
    }

    /** Gerçek sweep bağlamı biçimi: SchedulerService.portSweepItem / addScriptedSweepItems ile aynı anahtarlar. */
    private static Map<String, Object> monitorCtx(long monitorId, int recoveryChecks) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("monitor_id", monitorId);
        ctx.put("monitor_confirm_attempts", 3);
        ctx.put("monitor_confirm_interval_ms", 30000L);
        ctx.put("monitor_recovery_checks", recoveryChecks);
        ctx.put("monitor_recovery_interval_ms", 30000L);
        ctx.put("team_id", 7L);
        ctx.put("standalone", true);
        ctx.put("alert_level", "WARNING");
        return ctx;
    }

    private static MonitoringOutageService.SweepItem healthy(String type, String domain, long monitorId,
                                                             int recoveryChecks, AtomicInteger rechecks) {
        return new MonitoringOutageService.SweepItem(type, domain, "443/TCP", true, null,
                monitorCtx(monitorId, recoveryChecks), () -> { rechecks.incrementAndGet(); return up(); });
    }

    private static MonitoringOutageService.SweepItem failing(String type, String domain, long monitorId,
                                                             String error, AtomicInteger rechecks) {
        return new MonitoringOutageService.SweepItem(type, domain, "443/TCP", false, error,
                monitorCtx(monitorId, 3), () -> { rechecks.incrementAndGet(); return down(error); });
    }

    // ── K1: toplu-kesinti bastırması kurtarmayı engellememeli ──────────────────────────────

    @Test
    @DisplayName("VAKA 1 (Port): turda 3 başka host ağ hatasıyla DOWN (bastırma) — sağlıklı host'un açık alarmı YİNE kapanır")
    void portCase_suppressedSweep_healthyHostWithOpenAlarm_closes() {
        openAlarm(HOST, PORT);
        AtomicInteger healthyRechecks = new AtomicInteger();
        AtomicInteger failingRechecks = new AtomicInteger();

        service.handleSweepResults(PORT, List.of(
                healthy(PORT, HOST, 11L, 3, healthyRechecks),
                failing(PORT, "a.example.com", 12L, "Connect timed out", failingRechecks),
                failing(PORT, "b.example.com", 13L, "Connect timed out", failingRechecks),
                failing(PORT, "c.example.com", 14L, "Connection refused: getsockopt", failingRechecks)));

        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
        assertThat(healthyRechecks.get()).as("aktif kurtarma doğrulamaları koştu (3 ardışık başarılı)").isEqualTo(2);
        // Bastırmanın ASIL işi korunur: ağ kesintisi şüphesinde yeni alarm/teyit yok, olay kaydı yazılır.
        assertThat(failingRechecks.get()).as("bastırılan DOWN hedefler için teyit zinciri başlamaz").isZero();
        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
        verify(networkOutageRepo).save(any());
    }

    @Test
    @DisplayName("VAKA 2 (Sentetik, ad '/' içeriyor): 3 başka senaryo zaman aşımıyla FAIL (bastırma) — geçen senaryonun alarmı kapanır")
    void scriptedCase_suppressedSweep_passingScenarioWithOpenAlarm_closes() {
        openAlarm(SCENARIO, SCRIPTED);
        AtomicInteger r = new AtomicInteger();

        service.handleSweepResults(SCRIPTED, List.of(
                healthy(SCRIPTED, SCENARIO, 21L, 3, r),
                failing(SCRIPTED, "Senaryo B", 22L, "FAIL — 0✓/2✗ · Request Failed … request timeout", new AtomicInteger()),
                failing(SCRIPTED, "Senaryo C", 23L, "FAIL — 0✓/1✗ · connection refused", new AtomicInteger()),
                failing(SCRIPTED, "Senaryo D", 24L, "FAIL — 0✓/3✗ · i/o timeout", new AtomicInteger())));

        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(SCENARIO, SCRIPTED);
        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Bastırma sürerken açık alarmlı hedef yine DOWN → kurtarma sayacı SIFIRLANIR, alarm kapanmaz")
    void suppressedSweep_openAlarmStillDown_doesNotClose() {
        openAlarm(HOST, PORT);
        service.handleSweepResults(PORT, List.of(
                failing(PORT, HOST, 11L, "Connect timed out", new AtomicInteger()),
                failing(PORT, "a.example.com", 12L, "Connect timed out", new AtomicInteger()),
                failing(PORT, "b.example.com", 13L, "Connect timed out", new AtomicInteger())));

        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    // ── K2: kurtarma canlılığı — kaybolan / takılan aktif zincir ────────────────────────────

    @Test
    @DisplayName("K2: aktif kurtarma zinciri HİÇ koşmazsa (kayıp görev) — 3 ardışık sağlıklı tur alarmı yine kapatır")
    void lostRecoveryChain_passiveBackstop_closesAfterRequiredSweeps() {
        openAlarm(HOST, PORT);
        ManualScheduler neverRuns = new ManualScheduler();
        ReflectionTestUtils.setField(service, "recoveryExecutor", neverRuns);
        AtomicInteger r = new AtomicInteger();

        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));
        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));   // 3/3 ardışık sağlıklı tur
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    @Test
    @DisplayName("K2: kurtarma arasında DOWN tur → pasif sayaç sıfırlanır (ardışıklık şartı korunur)")
    void passiveBackstop_downRoundResetsCounter() {
        openAlarm(HOST, PORT);
        ReflectionTestUtils.setField(service, "recoveryExecutor", new ManualScheduler());
        AtomicInteger r = new AtomicInteger();

        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));
        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));
        service.handleSweepResults(PORT, List.of(failing(PORT, HOST, 11L, "Connect timed out", new AtomicInteger())));
        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));
        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    @Test
    @DisplayName("K2: süresi aşılmış (takılı) aktif zincir değiştirilir — yeni zincir alarmı kapatır, eskisi kapatamaz")
    void staleRecoveryChain_isReplaced() {
        openAlarm(HOST, PORT);
        ManualScheduler rec = new ManualScheduler();
        ReflectionTestUtils.setField(service, "recoveryExecutor", rec);
        AtomicLong clock = new AtomicLong(1_000_000L);
        ReflectionTestUtils.setField(service, "nowMs", (java.util.function.LongSupplier) clock::get);
        AtomicInteger r = new AtomicInteger();

        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));
        assertThat(rec.queue).hasSize(1);
        Runnable stuck = rec.queue.poll();   // bu görev "hiç koşmadı"

        clock.addAndGet(20 * 60_000L);       // 20 dk sonra (3 × 30 sn + pay çoktan geçti)
        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, r)));
        assertThat(rec.queue).as("takılı zincir YENİSİYLE değiştirilmeli (eskiden 'zaten sürüyor' deyip atlıyordu)").hasSize(1);

        for (int guard = 0; !rec.queue.isEmpty() && guard < 10; guard++) rec.queue.poll().run();
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);

        stuck.run();   // eski kuşağın geç ateşlenen görevi ikinci kez kapatamaz
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    // ── K5: tür bildirimleri kapalıyken açık alarm donmamalı ────────────────────────────────

    @Test
    @DisplayName("K5: scripted.alert-enabled=false iken açık alarm, senaryo geçince YİNE kapanır; yeni alarm açılmaz")
    void alertsDisabled_openAlarmStillClosesWhenHealthy() {
        when(appSettings.getBoolean(eq("site.monitor.scripted.alert-enabled"), anyBoolean())).thenReturn(false);
        openAlarm(SCENARIO, SCRIPTED);
        AtomicInteger other = new AtomicInteger();

        service.handleSweepResults(SCRIPTED, List.of(
                healthy(SCRIPTED, SCENARIO, 21L, 1, new AtomicInteger()),
                failing(SCRIPTED, "Senaryo B", 22L, "FAIL — assertion", other)));

        // D-1 (2026-09-29): tür bildirimleri kapalıyken kapanış SESSİZ — çözüm e-postası/webhook yok (push simetrik).
        verify(escalationService, times(1)).resolveOpenAlertsSilently(eq(SCENARIO), eq(java.util.Set.of(SCRIPTED)), anyString());
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        assertThat(other.get()).as("bildirimler kapalıyken yeni teyit zinciri başlamaz").isZero();
        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
    }

    // ── D2: aynı anahtarı paylaşan izlemeler ─────────────────────────────────────────────────

    @Test
    @DisplayName("D2: aynı host'ta izleme A DOWN (önceki tur), B sağlıklı (ayrı turlar) → B'nin turları alarmı KAPATMAZ")
    void sharedKey_siblingStillDown_notClosedBySplitBatch() {
        openAlarm(HOST, PORT);
        AtomicInteger r = new AtomicInteger();

        service.handleSweepResults(PORT, List.of(failing(PORT, HOST, 1L, "Connect timed out", new AtomicInteger())));
        for (int i = 0; i < 3; i++) service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 2L, 3, r)));
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 1L, 3, r)));   // A da düzeldi
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    @Test
    @DisplayName("D2: DOWN kalan kardeş izleme artık izlenmiyorsa (duraklatıldı/silindi) kapanışı ENGELLEMEZ")
    void sharedKey_siblingNoLongerMonitored_doesNotBlock() {
        openAlarm(HOST, PORT);
        service.handleSweepResults(PORT, List.of(failing(PORT, HOST, 1L, "Connect timed out", new AtomicInteger())));
        service.setStillMonitored(item -> !Long.valueOf(1L).equals(item.ctxExtra().get("monitor_id")));

        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 2L, 3, new AtomicInteger())));
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    @Test
    @DisplayName("D2: kardeşin teyit zinciri 'geçici dalgalanma' ile bittiyse (recheck UP) kardeş DOWN sayılmaz")
    void sharedKey_siblingTransientDownClearedByConfirmation() {
        AtomicInteger calls = new AtomicInteger();
        // A: sweep DOWN, teyitte ilk recheck UP → alarm açılmaz, A'nın DOWN kaydı düşer
        service.handleSweepResults(PORT, List.of(new MonitoringOutageService.SweepItem(PORT, HOST, "443/TCP", false,
                "Connect timed out", monitorCtx(1L, 3), () -> { calls.incrementAndGet(); return up(); })));
        openAlarm(HOST, PORT);   // (B'nin kendi eski alarmı)

        service.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 2L, 3, new AtomicInteger())));
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    // ── H1 (çürütme pini): kapanış geçişe değil DB durumuna bakar ───────────────────────────

    @Test
    @DisplayName("H1: yeniden başlatma sonrası (boş bellek) DB'de açık alarm + sağlıklı tur → kapanır (geçiş gerekmez)")
    void restart_freshInstance_openAlarmInDb_closesOnHealthySweep() {
        MonitoringOutageService fresh = newService();
        openAlarm(HOST, PORT);

        fresh.handleSweepResults(PORT, List.of(healthy(PORT, HOST, 11L, 3, new AtomicInteger())));
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    // ── Kardeş türler: aynı kapanış hattı (handleSweepResults) — her tür için bastırma altında kurtarma ──

    @Test
    @DisplayName("Kardeş türler: bastırma altında sağlıklı hedefin açık alarmı HER izleme türünde kapanır")
    void everySweepType_recoversUnderSuppression() {
        List<String> types = List.of(
                EscalationService.TYPE_ACCESSIBILITY, EscalationService.TYPE_PORT_DOWN, EscalationService.TYPE_PORT_SLOW,
                EscalationService.TYPE_DNS_FAILURE, EscalationService.TYPE_DNS_SLOW, EscalationService.TYPE_DNS_UNEXPECTED,
                EscalationService.TYPE_DNS_INCONSISTENT, EscalationService.TYPE_KEYWORD, EscalationService.TYPE_KEYWORD_SLOW,
                EscalationService.TYPE_KEYWORD_SSL, EscalationService.TYPE_KEYWORD_DOMAIN_EXPIRY,
                EscalationService.TYPE_PING_DOWN, EscalationService.TYPE_PING_SLOW, EscalationService.TYPE_HTTP_DOWN,
                EscalationService.TYPE_HTTP_SSL, EscalationService.TYPE_DOMAIN_EXPIRY, EscalationService.TYPE_PAGE_DOWN,
                EscalationService.TYPE_PAGE_INTEGRITY, EscalationService.TYPE_PAGESPEED_DOWN, EscalationService.TYPE_PAGESPEED_SLOW,
                EscalationService.TYPE_SCRIPTED_FAIL, EscalationService.TYPE_SCRIPTED_SLOW,
                EscalationService.TYPE_DOMAINMON_EXPIRY, EscalationService.TYPE_DOMAINMON_UNKNOWN,
                EscalationService.TYPE_DOMAINMON_STATUS, EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK,
                EscalationService.TYPE_DOMAINMON_BLACKLIST);
        List<String> notClosed = new ArrayList<>();
        for (String t : types) {
            MonitoringOutageService s = newService();
            reset(escalationService);
            openAlarm(HOST, t);
            List<MonitoringOutageService.SweepItem> batch = new ArrayList<>();
            batch.add(healthy(t, HOST, 31L, 1, new AtomicInteger()));
            batch.add(failing(t, "a.example.com", 32L, "Connect timed out", new AtomicInteger()));
            batch.add(failing(t, "b.example.com", 33L, "Connect timed out", new AtomicInteger()));
            batch.add(failing(t, "c.example.com", 34L, "Connect timed out", new AtomicInteger()));
            s.handleSweepResults(t, batch);
            if (mockingDetails(escalationService).getInvocations().stream()
                    .noneMatch(inv -> inv.getMethod().getName().equals("resolveMonitoringAlertsForDomain")
                            && HOST.equals(inv.getArgument(0)) && t.equals(inv.getArgument(1)))) {
                notClosed.add(t);
            }
        }
        assertThat(notClosed).as("bastırma altında kapanmayan türler").isEmpty();
    }
}
