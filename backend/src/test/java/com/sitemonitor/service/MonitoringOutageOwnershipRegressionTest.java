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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 20.91.0 öncesi regresyon taraması (BUG_REGRESYON_2026-09-29) — kapanış uzlaştırmasının takım yalıtımı ve elle kontrol.
 *
 * <ul>
 *   <li><b>Y-1</b> — anahtar (alan adı + tür) takımlar arasında paylaşılabilir (bağımsız + envanter türevi aynı host, iki
 *       takımın aynı URL'si, aynı adlı senaryo). D2 "kardeş hâlâ DOWN" kuralı takıma bakmıyordu: B takımının DOWN
 *       izlemesi A'nın olayını açık tutuyor ve B'nin arızasının yeniden uyarısı A'nın olayına (A'ya) gidiyordu.</li>
 *   <li><b>O-1</b> — elle "Şimdi kontrol et" tıklamaları kurtarma aralığını atlıyordu (3 hızlı tık = saniyeler içinde
 *       kapanış + ÇÖZÜLDÜ).</li>
 *   <li><b>D-5 / D-6 / D-11</b> — gözlem haritası budanmıyordu; yeniden başlatma sonrası ilk zamanlanmış turdan önce
 *       kardeşin elle kontrolü alarmı kapatabiliyordu; bildirimi kapatılan türün açık alarmları donuyordu.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringOutageOwnershipRegressionTest {

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
    private static final long TEAM_A = 7L, TEAM_B = 8L;
    private static final long MON_A = 1L, MON_B = 2L;

    private static ScheduledThreadPoolExecutor immediateExecutor() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                command.run();
                return null;
            }
        };
    }

    /** Görevleri kuyrukta tutan (hiç koşturmayan) zamanlayıcı — yalnız pasif sayacı sınamak için. */
    private static final class NeverRuns extends ScheduledThreadPoolExecutor {
        NeverRuns() { super(1); }
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) { return null; }
    }

    @BeforeEach
    void setUp() {
        service = new MonitoringOutageService(alertEventRepo, escalationService, jdbcTemplate,
                dnsRecordRepo, dnsMonitorRepo, appSettings, networkOutageRepo);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        for (String f : List.of("uptimeAlertEnabled", "portAlertEnabled", "dnsAlertEnabled", "keywordAlertEnabled", "pingAlertEnabled"))
            ReflectionTestUtils.setField(service, f, true);
        ReflectionTestUtils.setField(service, "confirmAttempts", 3);
        ReflectionTestUtils.setField(service, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(service, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(service, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(service, "confirmExecutor", immediateExecutor());
        ReflectionTestUtils.setField(service, "recoveryExecutor", immediateExecutor());
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
    }

    /** Gerçek biçimli açık olay: bağımsız izlemenin açtığı olay takım damgası + bağlam anlık görüntüsü taşır. */
    private static AlertEvent openEvent(String domain, String type, long openerMonitorId, long teamId) {
        AlertEvent e = new AlertEvent();
        e.setId(900L);
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("WARNING");
        e.setTeamId(teamId);
        e.setContextJson("{\"monitor_id\":" + openerMonitorId + ",\"team_id\":" + teamId + ",\"standalone\":true}");
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setCreatedAt("2026-09-28T22:22:00");
        return e;
    }

    private void open(AlertEvent e) {
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
    }

    /** SchedulerService.portSweepItem ile aynı anahtarlar: bağımsız satırda team_id + standalone. */
    private static Map<String, Object> ctx(long monitorId, Long teamId, int recoveryChecks) {
        Map<String, Object> m = new HashMap<>();
        m.put("monitor_id", monitorId);
        m.put("monitor_confirm_attempts", 3);
        m.put("monitor_confirm_interval_ms", 30000L);
        m.put("monitor_recovery_checks", recoveryChecks);
        m.put("monitor_recovery_interval_ms", 30000L);
        if (teamId != null) { m.put("team_id", teamId); m.put("standalone", true); }
        m.put("alert_level", "WARNING");
        return m;
    }

    private static Map<String, Object> status(String s) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", s);
        if ("down".equals(s)) m.put("error", "Connect timed out");
        return m;
    }

    private static MonitoringOutageService.SweepItem up(String type, String domain, long monitorId, Long teamId, int rc) {
        return new MonitoringOutageService.SweepItem(type, domain, "443/TCP", true, null, ctx(monitorId, teamId, rc),
                () -> status("up"));
    }

    private static MonitoringOutageService.SweepItem down(String type, String domain, long monitorId, Long teamId) {
        return new MonitoringOutageService.SweepItem(type, domain, "443/TCP", false, "Connect timed out",
                ctx(monitorId, teamId, 3), () -> status("down"));
    }

    // ── Y-1 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Y-1: B takımının DOWN izlemesi (ayrı tur) A'nın olayına yeniden uyarı ÜRETMEZ ve kapanışını ENGELLEMEZ")
    void otherTeamsDownMonitor_neitherRealertsNorBlocksOwnersAlarm() {
        open(openEvent(HOST, PORT, MON_A, TEAM_A));

        service.handleSweepResults(PORT, List.of(down(PORT, HOST, MON_B, TEAM_B)));   // B'nin turu
        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());

        service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 1)));   // A'nın turu: A sağlıklı
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    @Test
    @DisplayName("Y-1: aynı turda A sağlıklı + B DOWN → A'nın olayı A'nın sağlıklı sonucuyla kapanır, yeniden uyarı yok")
    void sameBatch_ownerHealthy_foreignDown_ownerAlarmCloses() {
        open(openEvent(HOST, PORT, MON_A, TEAM_A));

        service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 1), down(PORT, HOST, MON_B, TEAM_B)));

        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Y-1: A'nın olayı kapandıktan sonra B'nin DOWN turu KENDİ alarmını B'nin bağlamıyla (B takımı) açar")
    void afterOwnerCloses_foreignMonitorOpensItsOwnAlarmForItsTeam() {
        open(openEvent(HOST, PORT, MON_A, TEAM_A));
        service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 1), down(PORT, HOST, MON_B, TEAM_B)));
        verify(escalationService).resolveMonitoringAlertsForDomain(HOST, PORT);

        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());   // A'nın olayı kapandı
        service.handleSweepResults(PORT, List.of(down(PORT, HOST, MON_B, TEAM_B)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
        verify(escalationService).processConfirmedOutage(eq(HOST), eq(PORT), anyString(), c.capture());
        assertThat(c.getValue().get("team_id")).as("B'nin alarmı B'ye").isEqualTo(TEAM_B);
        assertThat(c.getValue().get("monitor_id")).isEqualTo(MON_B);
    }

    @Test
    @DisplayName("Y-1: bağımsız olay + envanter TÜREVİ kardeş (takım damgasız) DOWN → kapanışı engellemez")
    void derivedSiblingDoesNotBlockStandaloneAlarm() {
        open(openEvent(HOST, PORT, MON_A, TEAM_A));
        service.handleSweepResults(PORT, List.of(down(PORT, HOST, 3L, null)));   // türev satır (team_id / standalone yok)
        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());

        service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 1)));
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    @Test
    @DisplayName("Y-1 korunur: AYNI takımın kardeşi (ör. 443 + 8443) DOWN iken alarm kapanmaz; sahibin DOWN'u yeniden uyarı yolunu işletir")
    void sameTeamSibling_stillBlocks_andOwnerDownRealerts() {
        open(openEvent(HOST, PORT, MON_A, TEAM_A));
        service.handleSweepResults(PORT, List.of(down(PORT, HOST, 4L, TEAM_A)));
        verify(escalationService, times(1)).processConfirmedOutage(eq(HOST), eq(PORT), anyString(), any());

        service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 1)));
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    @Test
    @DisplayName("Y-1: iki takımda AYNI adlı senaryo — B'nin FAIL'i A'nın olayını açık tutmaz")
    void sameNameScenarioInAnotherTeam_doesNotBlock() {
        String name = "Giriş Akışı";
        open(openEvent(name, SCRIPTED, MON_A, TEAM_A));
        service.handleSweepResults(SCRIPTED, List.of(down(SCRIPTED, name, MON_B, TEAM_B)));
        service.handleSweepResults(SCRIPTED, List.of(up(SCRIPTED, name, MON_A, TEAM_A, 1)));
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(name, SCRIPTED);
        verify(escalationService, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
    }

    // ── O-1 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O-1: 3 hızlı elle sağlıklı kontrol alarmı KAPATMAZ; kurtarma aralığı (30 sn) gözetilince 3. sayılır ve kapatır")
    void rapidManualClicks_doNotBypassRecoveryInterval() {
        open(openEvent(HOST, PORT, MON_A, TEAM_A));
        ReflectionTestUtils.setField(service, "recoveryExecutor", new NeverRuns());   // yalnız sayaç sınanıyor
        AtomicLong clock = new AtomicLong(5_000_000L);
        ReflectionTestUtils.setField(service, "nowMs", (java.util.function.LongSupplier) clock::get);

        for (int i = 0; i < 3; i++) service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 3)), true);
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        clock.addAndGet(30_000L);
        service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 3)), true);   // 2/3
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        clock.addAndGet(30_000L);
        service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 3)), true);   // 3/3
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    @Test
    @DisplayName("O-1: zamanlanmış turlar aralık kuralına tabi DEĞİL (sweep zaten ızgaralı) — 3 tur 3/3 sayar")
    void scheduledRounds_areNotGapLimited() {
        open(openEvent(HOST, PORT, MON_A, TEAM_A));
        ReflectionTestUtils.setField(service, "recoveryExecutor", new NeverRuns());
        AtomicLong clock = new AtomicLong(5_000_000L);
        ReflectionTestUtils.setField(service, "nowMs", (java.util.function.LongSupplier) clock::get);

        for (int i = 0; i < 3; i++) service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 3)));
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    // ── D-6 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-6: yeniden başlatma sonrası ilk zamanlanmış turdan ÖNCE, olayı açmayan izlemenin elle sağlıklı sonucu kapatmaz")
    void manualRecoveryDeferredUntilFirstScheduledRound() {
        open(openEvent(HOST, PORT, MON_A, TEAM_A));
        service.handleSweepResults(PORT, List.of(up(PORT, HOST, 5L, TEAM_A, 1)), true);   // aynı takımın başka izlemesi
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        service.handleSweepResults(PORT, List.of(up(PORT, "other.example.com", 6L, TEAM_A, 1)));   // ilk zamanlanmış tur
        service.handleSweepResults(PORT, List.of(up(PORT, HOST, 5L, TEAM_A, 1)), true);
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    @Test
    @DisplayName("D-6: olayı AÇAN izlemenin kendi elle sağlıklı sonucu ertelenmez")
    void openerManualRecovery_notDeferred() {
        open(openEvent(HOST, PORT, MON_A, TEAM_A));
        service.handleSweepResults(PORT, List.of(up(PORT, HOST, MON_A, TEAM_A, 1)), true);
        verify(escalationService, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    // ── D-11 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-11: bildirimi kapatılan türün açık alarmları SESSİZCE kapanır; açık türe dokunulmaz")
    void disablingType_closesItsOpenAlarmsSilently() {
        when(appSettings.getBoolean(eq("site.monitor.scripted.alert-enabled"), anyBoolean())).thenReturn(false);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(
                openEvent("Giriş Akışı", SCRIPTED, MON_A, TEAM_A), openEvent(HOST, PORT, MON_B, TEAM_A)));
        ReflectionTestUtils.setField(service, "settingsReconcileExecutor", (java.util.concurrent.Executor) Runnable::run);

        service.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.scripted.alert-enabled"), "save"));

        verify(escalationService).resolveOpenAlertsSilently(eq("Giriş Akışı"), eq(Set.of(SCRIPTED)), anyString());
        verify(escalationService, never()).resolveOpenAlertsSilently(eq(HOST), any(), anyString());
        verify(escalationService, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    @Test
    @DisplayName("D-11: hiçbir tür kapalı değilse açık alarmlar SORGULANMAZ (her ayar kaydında maliyet yok)")
    void noDisabledType_noQuery() {
        ReflectionTestUtils.setField(service, "settingsReconcileExecutor", (java.util.concurrent.Executor) Runnable::run);
        service.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.port.alert-enabled"), "save"));
        verify(alertEventRepo, never()).findAllOpenOrderBySeverity();
    }

    @Test
    @DisplayName("D-b8: toplu kapanış ayar kaydı işleminin İÇİNDE koşmaz — AFTER_COMMIT dinleyici + arka plan yürütücüsü")
    void bulkClose_runsAfterCommit_offTheCallerThread() throws Exception {
        var m = MonitoringOutageService.class.getMethod("onSettingsChanged", AppSettingsChangedEvent.class);
        var tel = m.getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class);
        assertThat(tel).as("işlem içi eşzamanlı @EventListener değil").isNotNull();
        assertThat(tel.phase()).isEqualTo(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT);
        assertThat(tel.fallbackExecution()).as("işlemsiz yayında da koşsun").isTrue();

        when(appSettings.getBoolean(eq("site.monitor.scripted.alert-enabled"), anyBoolean())).thenReturn(false);
        List<Runnable> queued = new java.util.ArrayList<>();
        ReflectionTestUtils.setField(service, "settingsReconcileExecutor", (java.util.concurrent.Executor) queued::add);
        service.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.scripted.alert-enabled"), "save"));
        verify(alertEventRepo, never()).findAllOpenOrderBySeverity();   // çağıran iş parçacığında sorgu/kapanış YOK
        assertThat(queued).hasSize(1);
    }

    @Test
    @DisplayName("D-b9: ELLE kapanan değişiklik alarmları (DNS_CHANGED, DOMAINMON_CHANGED) toplu sessiz kapanışa girmez")
    void bulkClose_skipsManualCloseChangeAlarms() {
        when(appSettings.getBoolean(eq("site.monitor.dns.alert-enabled"), anyBoolean())).thenReturn(false);
        when(appSettings.getBoolean(eq("site.monitor.domain.alert-enabled"), anyBoolean())).thenReturn(false);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(
                openEvent("example.com", EscalationService.TYPE_DNS_CHANGED, MON_A, TEAM_A),
                openEvent("example.com", EscalationService.TYPE_DOMAINMON_CHANGED, MON_B, TEAM_A),
                openEvent("example.com", EscalationService.TYPE_DNS_FAILURE, MON_A, TEAM_A)));

        service.closeAlarmsOfDisabledTypes("test");

        verify(escalationService).resolveOpenAlertsSilently(eq("example.com"),
                eq(Set.of(EscalationService.TYPE_DNS_FAILURE)), anyString());
        verify(escalationService, never()).resolveOpenAlertsSilently(any(),
                eq(Set.of(EscalationService.TYPE_DNS_CHANGED)), anyString());
        verify(escalationService, never()).resolveOpenAlertsSilently(any(),
                eq(Set.of(EscalationService.TYPE_DOMAINMON_CHANGED)), anyString());
    }

    // ── D-5 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-5: gözlem kaydı İNCE (recheck lambdası / izleme varlığı tutulmaz) ve artık izlenmeyen izlemenin kaydı budanır")
    @SuppressWarnings("unchecked")
    void observations_areSlim_andPruned() {
        service.handleSweepResults(PORT, List.of(down(PORT, "a.example.com", 41L, TEAM_A), down(PORT, "b.example.com", 42L, TEAM_A)));
        Map<String, Object> map = (Map<String, Object>) ReflectionTestUtils.getField(service, "downObserved");
        assertThat(map).hasSize(2);
        Object obs = map.values().iterator().next();
        MonitoringOutageService.SweepItem slim = (MonitoringOutageService.SweepItem) ReflectionTestUtils.invokeMethod(obs, "item");
        assertThat(slim.recheck()).as("recheck lambdası izleme varlığını yakalar — tutulmamalı").isNull();
        assertThat(slim.ctxExtra()).containsOnlyKeys("monitor_id", "team_id", "standalone");

        service.setStillMonitored(it -> !Long.valueOf(41L).equals(it.ctxExtra().get("monitor_id")));
        int removed = service.pruneObservations(System.currentTimeMillis());
        assertThat(removed).isEqualTo(1);
        assertThat(map).hasSize(1);
    }
}
