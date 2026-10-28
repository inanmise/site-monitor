package com.sitemonitor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;
import com.sitemonitor.service.evalstate.EvalLoad;
import com.sitemonitor.service.evalstate.EvalLoadGate;
import com.sitemonitor.service.evalstate.EvalLoadReport;
import com.sitemonitor.service.evalstate.EvalSnapshot;
import com.sitemonitor.service.evalstate.EvalStateMode;
import com.sitemonitor.service.evalstate.EvalStateReadSwitch;
import com.sitemonitor.service.evalstate.EvalStateRecorder;
import com.sitemonitor.service.evalstate.EvalStateSchemaPatch;
import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.evalstate.EvalStateSource;
import com.sitemonitor.service.evalstate.RecordingSink;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-2 B — {@code MonitoringOutageService} devirde yükleme (tasarım R3-2 §1.1, §1.4, §1.5, §3.1, §3.3, §8.4):
 * katman başına alan eşlemesi, yüklenen hata sınıfının yeniden oynatma / doğrulamada korunması, FALSE bastırmanın
 * konmaması, D-6'nın yalnız FULL'da rahatlaması, her atma nedeni, boş olmayan belleğe uygulamanın reddi, ayarın yeniden
 * denetimi, ayar okuma hatası = kapalı, kurulum engelleri, kapı bekleyişi ve günlük satırları.
 */
class MonitoringOutageEvalLoadTest {

    static final String PORT = EscalationService.TYPE_PORT_DOWN;
    static final String HOST = "db.example.com";
    static final long T0 = MultiPodTestSupport.T0;

    final AtomicLong clock = new AtomicLong(T0);
    final AlertEventRepository alertRepo = mock(AlertEventRepository.class);
    final EscalationService escalation = mock(EscalationService.class);
    final AppSettingsService settings = mock(AppSettingsService.class);
    MonitoringOutageService mos;
    ReadySink sink;
    ListAppender<ILoggingEvent> logs;
    Logger logger;
    final List<Thread> threads = new ArrayList<>();

    /** Kurulum engeli ayarlanabilen kaydedici — kancalar {@link RecordingSink}'e gider. */
    static final class ReadySink implements EvalStateSink {
        final RecordingSink rec = RecordingSink.recording();
        volatile String blocker;
        volatile boolean throwOnBlocker;
        final AtomicInteger blockerCalls = new AtomicInteger();

        @Override public String loadBlocker() {
            blockerCalls.incrementAndGet();
            if (throwOnBlocker) throw new IllegalStateException("engel okunamadı (test)");
            return blocker;
        }
        @Override public void recovery(String rkey, String alertType, String domain) { rec.recovery(rkey, alertType, domain); }
        @Override public void observation(String alertType, SweepItem item, boolean manual, long atMs) {
            rec.observation(alertType, item, manual, atMs);
        }
        @Override public void observationPruned(String alertType, SweepItem item) { rec.observationPruned(alertType, item); }
        @Override public void typeState(String alertType) { rec.typeState(alertType); }
        @Override public void chainStart(String k, long id, SweepItem item, ConfirmState s, String f, long d) {
            rec.chainStart(k, id, item, s, f, d);
        }
        @Override public void chainProgress(String k, long id, ConfirmState s, List<Map<String, Object>> a) {
            rec.chainProgress(k, id, s, a);
        }
        @Override public void chainFinishing(String k, long id, int t) { rec.chainFinishing(k, id, t); }
        @Override public void chainEnd(String k, long id) { rec.chainEnd(k, id); }
        @Override public void sweepEnd(String alertType) { rec.sweepEnd(alertType); }
        @Override public void bindSource(EvalStateSource source) { rec.bindSource(source); }
        @Override public Status status() { return rec.status(); }
    }

    @BeforeEach
    void setUp() {
        mos = new MonitoringOutageService(alertRepo, escalation, mock(JdbcTemplate.class), mock(DnsRecordRepository.class),
                mock(DnsMonitorRepository.class), settings, mock(NetworkOutageEventRepository.class));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        for (String f : List.of("uptimeAlertEnabled", "portAlertEnabled", "dnsAlertEnabled", "keywordAlertEnabled",
                "pingAlertEnabled")) {
            ReflectionTestUtils.setField(mos, f, true);
        }
        ReflectionTestUtils.setField(mos, "confirmAttempts", 3);
        ReflectionTestUtils.setField(mos, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(mos, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(mos, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(mos, "confirmExecutor", MonitoringMultiPodKit.immediate());
        ReflectionTestUtils.setField(mos, "recoveryExecutor", MonitoringMultiPodKit.immediate());
        ReflectionTestUtils.setField(mos, "nowMs", (LongSupplier) clock::get);
        ReflectionTestUtils.setField(mos, "suppressionClock", (LongSupplier) clock::get);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        sink = new ReadySink();
        mos.setEvalStateSink(sink);
        mos.setEvalLoadPodId("pod-b-00000002");
        logger = (Logger) LoggerFactory.getLogger(MonitoringOutageService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        logger.detachAppender(logs);
        for (Thread t : threads) {
            t.interrupt();
            t.join(2_000);
        }
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────────

    void gained() {
        mos.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "pod-b", clock.get(),
                SweepLeadershipChangedEvent.GAINED));
    }

    void lost() {
        mos.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "pod-b", clock.get(),
                SweepLeadershipChangedEvent.LOST));
    }

    /** Ayar açık + kaydedici hazır + GAINED → kapı LOADING; belirteci döner. */
    long armed() {
        mos.setEvalReadEnabled(() -> true);
        gained();
        long token = mos.evalLoadToken();
        assertThat(token).as("kapı kuruldu").isPositive();
        return token;
    }

    static Map<String, Object> status(String s) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", s);
        return m;
    }

    static SweepItem item(boolean up, long monitorId, int recoveryChecks, Supplier<Map<String, Object>> recheck) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_id", monitorId);
        ctx.put("team_id", 7L);
        ctx.put("standalone", true);
        ctx.put("monitor_recovery_checks", recoveryChecks);
        return new SweepItem(PORT, HOST, monitorId + "/TCP", up, up ? null : "connect timed out", ctx, recheck);
    }

    AlertEvent openAlarm(long openerMonitorId) {
        AlertEvent e = new AlertEvent();
        e.setId(42L);
        e.setDomain(HOST);
        e.setAlertType(PORT);
        e.setAlertLevel("WARNING");
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setTeamId(7L);
        e.setContextJson("{\"monitor_id\":" + openerMonitorId + ",\"team_id\":7,\"standalone\":true}");
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
        return e;
    }

    static EvalLoad load(long token, String tier) {
        return load(token, tier, true);
    }

    /** Her alanı dolu temsilci yük (FULL'da her şey; OBSERVATIONS'da kurucu karar alanlarını boşaltır). */
    static EvalLoad load(long token, String tier, boolean withSibling) {
        Map<String, EvalLoad.Sibling> siblings = withSibling
                ? Map.of(EvalLoad.siblingKey(PORT, 5L), new EvalLoad.Sibling(PORT, HOST, "5/TCP", 5L, 7L, true,
                        EvalStateSource.ERROR_DNS, T0 - 60_000))
                : Map.of();
        return new EvalLoad(token, tier,
                Map.of(PORT + ":" + HOST, new EvalLoad.Recovery(2, T0 - 30_000), PORT + ":zero.example.com",
                        new EvalLoad.Recovery(0, T0)),
                siblings,
                Set.of(PORT), Set.of(PORT),
                Map.of(PORT, Map.of("a.example.com", new EvalLoad.SeedMark(true, T0 - 10_000),
                        "b.example.com", new EvalLoad.SeedMark(false, T0 - 20_000))),
                Map.of(PORT, 30_000L, EscalationService.TYPE_ACCESSIBILITY, 0L),
                new EvalLoad.Meta(3L, "pod-a-00000001", EvalLoad.TIER_FULL.equals(tier) ? 2_000L : null, 1_500L,
                        EvalLoad.TIER_FULL.equals(tier) ? null : EvalLoadReport.REASON_NO_HANDOVER, 40L));
    }

    Thread start(Runnable r) {
        Thread t = new Thread(r, "mos-eval-test");
        t.setDaemon(true);
        threads.add(t);
        t.start();
        return t;
    }

    static void awaitTrue(java.util.function.BooleanSupplier c) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("koşul 5 sn içinde sağlanmadı");
            Thread.onSpinWait();
        }
    }

    List<String> messages(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    List<String> loadLines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("devirde")).toList();
    }

    @SuppressWarnings("unchecked")
    <T> T field(String name) {
        return (T) ReflectionTestUtils.getField(mos, name);
    }

    // ── Kurulum ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ayar KAPALI (tedarikçi yok): GAINED kapıyı kurmaz, kaydedici sorulmaz, rapor none/setting_off, günlük satırı YOK")
    void settingOff_noArm_noLog() {
        gained();
        assertThat(mos.evalLoadToken()).isZero();
        assertThat(mos.evalLoadGate().state()).isEqualTo(EvalLoadGate.State.NONE);
        assertThat(sink.blockerCalls).hasValue(0);
        EvalLoadReport r = mos.lastEvalLoad();
        assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
        assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_SETTING_OFF);
        assertThat(r.pod()).isEqualTo("pod-b-00000002");
        assertThat(loadLines()).isEmpty();
        assertThat(sink.rec.ops()).isEmpty();
        lost();
        assertThat(mos.lastEvalLoad().reason()).isEqualTo(EvalLoadReport.REASON_SETTING_OFF);
        assertThat(loadLines()).isEmpty();
    }

    @Test
    @DisplayName("ayar tedarikçisi fırlatırsa KAPALI sayılır; Spring sağlayıcısı (EvalStateReadSwitch) canlı okunur")
    void readEnabledFailureIsOff_andProviderWiring() {
        mos.setEvalReadEnabled(() -> { throw new IllegalStateException("ayar okunamadı"); });
        gained();
        assertThat(mos.evalLoadToken()).isZero();
        assertThat(mos.lastEvalLoad().reason()).isEqualTo(EvalLoadReport.REASON_SETTING_OFF);

        AppSettingsService live = mock(AppSettingsService.class);
        AtomicBoolean on = new AtomicBoolean(true);
        when(live.getBoolean(eq(EvalStateReadSwitch.KEY), anyBoolean())).thenAnswer(i -> on.get());
        @SuppressWarnings("unchecked")
        ObjectProvider<AppSettingsService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(live);
        mos.setEvalReadSettingsProvider(provider);
        assertThat(mos.evalReadEnabledNow()).isTrue();
        on.set(false);
        assertThat(mos.evalReadEnabledNow()).as("canlı").isFalse();
        mos.setEvalReadSettingsProvider(null);
        assertThat(mos.evalReadEnabledNow()).isFalse();
    }

    @Test
    @DisplayName("kurulum engelleri: kaydedici yok → no_recorder; kip kapalı → mode_off; engel okunamadı → no_recorder (kanca hatası sayılır)")
    void armBlockers() {
        mos.setEvalReadEnabled(() -> true);
        sink.blocker = EvalLoadReport.REASON_MODE_OFF;
        gained();
        assertThat(mos.evalLoadToken()).isZero();
        assertThat(mos.lastEvalLoad().reason()).isEqualTo(EvalLoadReport.REASON_MODE_OFF);
        assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("devirde yüklenmedi (mode_off)"));

        sink.throwOnBlocker = true;
        long before = mos.hookFailures();
        gained();
        assertThat(mos.lastEvalLoad().reason()).isEqualTo(EvalLoadReport.REASON_NO_RECORDER);
        assertThat(mos.hookFailures()).isEqualTo(before + 1);

        mos.setEvalStateSink(null);
        gained();
        assertThat(mos.lastEvalLoad().reason()).isEqualTo(EvalLoadReport.REASON_NO_RECORDER);
        assertThat(mos.evalLoadGate().state()).isEqualTo(EvalLoadGate.State.NONE);
    }

    @Test
    @DisplayName("LOST / RELEASED kapıyı söker: bekleyen yükleme leadership_changed (INFO), geç gelen yük uygulanmaz ve raporu ezmez")
    void lostDisarms_lateApplyStale() {
        long token = armed();
        lost();
        assertThat(mos.evalLoadToken()).isZero();
        EvalLoadReport r = mos.lastEvalLoad();
        assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
        assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("devirde yüklenmedi (leadership_changed)"));

        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL))).isFalse();
        assertThat(mos.lastEvalLoad()).isSameAs(r);
        assertThat(mos.verifySnapshot().recoveryUpCount()).as("bellek boş kaldı").isEmpty();
        mos.evalLoadSkipped(token, EvalLoadReport.none(clock.get(), "x", EvalLoadReport.REASON_PREVIOUS_UNMARKED));
        assertThat(mos.lastEvalLoad()).as("eski belirteçli atlama yok sayılır").isSameAs(r);

        // Yeni dönem (A → B → A): eski yükleme yeni belirteci bitiremez.
        long token2 = armed();
        assertThat(token2).isGreaterThan(token);
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL))).isFalse();
        assertThat(mos.evalLoadToken()).isEqualTo(token2);
        mos.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "pod-b", clock.get(),
                SweepLeadershipChangedEvent.RELEASED));
        assertThat(mos.evalLoadToken()).isZero();
        assertThat(mos.applyEvalLoad(null)).isFalse();
        EvalLoadReport released = mos.lastEvalLoad();
        assertThat(released.reason()).isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);

        // Eski belirtecin geç gelen bitiş raporu (ör. bekleyenin zaman aşımı) yeni dönemin raporunu EZMEZ.
        ReflectionTestUtils.invokeMethod(mos, "reportEnded", new EvalLoadGate.Ended(token, EvalLoadReport.REASON_TIMEOUT,
                0L, 0));
        assertThat(mos.lastEvalLoad()).isSameAs(released);
    }

    // ── Uygulama: katman başına alan eşlemesi ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FULL: sayaç + son sayım, DOWN kardeş (sınıfıyla), bastırma TRUE, tur görüldü, pencere + aralık yüklenir; rapor + INFO")
    void applyFull_mapsEveryField() {
        long token = armed();
        clock.addAndGet(250);
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL))).isTrue();

        assertThat(mos.recoveryValues(PORT + ":" + HOST))
                .isEqualTo(new EvalStateSource.RecoveryValues(2, T0 - 30_000, null, null));
        assertThat(mos.recoveryValues(PORT + ":zero.example.com")).as("0 sayaç konmaz")
                .isEqualTo(EvalStateSource.RecoveryValues.NONE);
        assertThat(mos.typeValues(PORT)).isEqualTo(new EvalStateSource.TypeValues(true, true, 30_000L));
        assertThat(mos.typeValues(EscalationService.TYPE_ACCESSIBILITY)).as("0 aralık konmaz")
                .isEqualTo(EvalStateSource.TypeValues.NONE);
        EvalStateSource.DownValues dv = mos.downObservation(PORT, 5L);
        assertThat(dv.present()).isTrue();
        assertThat(dv.domain()).isEqualTo(HOST);
        assertThat(dv.detail()).isEqualTo("5/TCP");
        assertThat(dv.teamId()).isEqualTo(7L);
        assertThat(dv.standalone()).isTrue();
        assertThat(dv.errorClass()).isEqualTo(EvalStateSource.ERROR_DNS);
        assertThat(dv.atMs()).isEqualTo(T0 - 60_000);
        MonitoringOutageService.WindowSample ws = mos.peekWindowSample(PORT);
        assertThat(ws.networkDown()).isEqualTo(1);
        assertThat(ws.total()).isEqualTo(2);
        assertThat(mos.windowLengthMs(PORT)).as("pencere tohumlanan aralıkla").isEqualTo(300_000L);
        assertThat(mos.outageSuppressed(PORT)).isTrue();
        assertThat(mos.activeConfirmations(null)).as("zincirler yüklenmez").isEmpty();

        EvalLoadReport r = mos.lastEvalLoad();
        assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
        assertThat(r.reason()).isNull();
        assertThat(r.recoveryCounters()).isEqualTo(2);
        assertThat(r.siblings()).isEqualTo(1);
        assertThat(r.suppressions()).isEqualTo(1);
        assertThat(r.roundSeen()).isEqualTo(1);
        assertThat(r.windowDomains()).isEqualTo(2);
        assertThat(r.fromGeneration()).isEqualTo(3L);
        assertThat(r.pod()).isEqualTo("pod-b-00000002");
        assertThat(mos.evalLoadToken()).as("yükleme bitti").isZero();
        assertThat(messages(Level.INFO)).anyMatch(m -> m.startsWith("Kalıcı değerlendirme durumu devirde yüklendi (tam)")
                && m.contains("kuşak 3 (pod-a-00000001)") && m.contains("1 DOWN kardeş"));
        assertThat(sink.rec.ops()).as("uygulama kanca çağırmaz").isEmpty();
    }

    @Test
    @DisplayName("OBSERVATIONS: yalnız DOWN kardeş + pencere + aralık; sayaç, bastırma ve tur (D-6) YÜKLENMEZ")
    void applyObservations_onlyObservations() {
        long token = armed();
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_OBSERVATIONS))).isTrue();
        assertThat(mos.recoveryValues(PORT + ":" + HOST)).isEqualTo(EvalStateSource.RecoveryValues.NONE);
        assertThat(mos.typeValues(PORT)).isEqualTo(new EvalStateSource.TypeValues(null, false, 30_000L));
        assertThat(mos.outageSuppressed(PORT)).isFalse();
        assertThat(mos.downObservation(PORT, 5L).present()).isTrue();
        assertThat(mos.peekWindowSample(PORT).total()).isEqualTo(2);
        EvalLoadReport r = mos.lastEvalLoad();
        assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_OBSERVATIONS);
        assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_NO_HANDOVER);
        assertThat(r.recoveryCounters()).isZero();
        assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("devirde yüklendi (gözlemler)"));
    }

    @Test
    @DisplayName("FALSE bastırma konmaz (yokluk ≡ false); TTL'i aşmış kardeş yüklenmez")
    void falseSuppressionAndExpiredSiblingNotPut() {
        long token = armed();
        EvalLoad l = new EvalLoad(token, EvalLoad.TIER_FULL, Map.of(),
                Map.of(EvalLoad.siblingKey(PORT, 9L), new EvalLoad.Sibling(PORT, HOST, "9/TCP", 9L, null, null, null,
                        T0 - MonitoringOutageService.siblingTtlMs() - 1)),
                Set.of(), Set.of(), Map.of(), Map.of(PORT, 60_000L), null);
        assertThat(mos.applyEvalLoad(l)).isTrue();
        assertThat(mos.typeValues(PORT).suppressionActive()).isNull();
        assertThat(mos.downObservation(PORT, 9L).present()).isFalse();
        assertThat(mos.lastEvalLoad().siblings()).as("rapor yükteki sayıyı söyler").isEqualTo(1);
    }

    @Test
    @DisplayName("§1.5: yüklenen kardeşin hata sınıfı (metin yok) doğrulama görüntüsünde ve yeniden oynatmada korunur")
    void loadedErrorClassSurvivesSnapshotAndReplay() {
        long token = armed();
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL))).isTrue();
        EvalSnapshot.Down d = mos.verifySnapshot().downObserved().get(EvalLoad.siblingKey(PORT, 5L));
        assertThat(d.errorClass()).isEqualTo(EvalStateSource.ERROR_DNS);
        assertThat(d.monitorId()).isEqualTo(5L);

        int n = mos.replay();
        assertThat(n).as("1 kurtarma + 1 tür + 1 kardeş").isEqualTo(3);
        assertThat(sink.rec.ops("observation")).singleElement().satisfies(op -> {
            SweepItem it = (SweepItem) op.value();
            assertThat(it.up()).isFalse();
            assertThat(it.error()).as("metin tabloda yok").isNull();
        });
        assertThat(sink.rec.ops("recovery")).hasSize(1);
        assertThat(sink.rec.ops("typeState")).hasSize(1);
        // Canlı gözlem hâlâ metninden sınıflanır.
        mos.handleSweepResults(PORT, List.of(new SweepItem(PORT, "x.example.com", "1/TCP", false, "Connection refused",
                Map.of("monitor_id", 11L), () -> status("down"))), true);
        assertThat(mos.downObservation(PORT, 11L).errorClass()).isEqualTo(EvalStateSource.ERROR_NETWORK);
    }

    @Test
    @DisplayName("§1.5 gerçek kaydedici (H2): yüklenen kardeş yeniden oynatılınca satır error_class / network_down yükteki sınıfla yazılır")
    void loadedErrorClassReplayedByRealRecorder() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            new EvalStateSchemaPatch(mp.jdbc()).apply();
            EvalStateRecorder rec = EvalStateRecorder.builder(mp.jdbc()).writer("pod-b-00000002").leaderHint(() -> true)
                    .mode(() -> EvalStateMode.WRITE).clock(clock::get).flushMs(50).build();
            try {
                mos.setEvalStateSink(rec);
                long token = 77L;
                mos.setEvalReadEnabled(() -> true);
                mos.evalLoadGate().arm(token, clock.get() + EvalLoadGate.LOAD_TIMEOUT_MS);   // bugünkü kaydedicinin engeli var
                assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL))).isTrue();
                assertThat(mos.replay()).isEqualTo(3);
                assertThat(rec.flushNow()).isTrue();
                assertThat(mp.jdbc().queryForMap("SELECT up, network_down, error_class, team_id, domain FROM "
                        + "monitor_last_status WHERE alert_type = ? AND item_key = ?", PORT, "m:5"))
                        .containsEntry("UP", false).containsEntry("NETWORK_DOWN", true)
                        .containsEntry("ERROR_CLASS", EvalStateSource.ERROR_DNS).containsEntry("TEAM_ID", 7L)
                        .containsEntry("DOMAIN", HOST);
                assertThat(mp.jdbc().queryForObject("SELECT recovery_up_count FROM monitor_eval_state WHERE alert_type = ? "
                        + "AND domain = ?", Integer.class, PORT, HOST)).isEqualTo(2);
            } finally {
                rec.stop();
            }
        }
    }

    // ── D-6 (tasarım §1.4) ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D-6 FULL'da rahatlar: önceki sahibin tur işareti yüklendi → açmayan izlemenin elle sağlıklı sonucu kapatır")
    void d6RelievedInFull() {
        long token = armed();
        openAlarm(1L);
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL, false))).isTrue();
        mos.handleSweepResults(PORT, List.of(item(true, 2L, 1, () -> status("up"))), true);
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
    }

    @Test
    @DisplayName("D-6 OBSERVATIONS'da devrede kalır: açmayan izlemenin elle sağlıklı sonucu ilk zamanlanmış tura dek ertelenir")
    void d6ActiveInObservations() {
        long token = armed();
        openAlarm(1L);
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_OBSERVATIONS, false))).isTrue();
        mos.handleSweepResults(PORT, List.of(item(true, 2L, 1, () -> status("up"))), true);
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        assertThat(messages(Level.INFO)).anyMatch(m -> m.startsWith("Elle kurtarma ertelendi"));
    }

    @Test
    @DisplayName("yüklenen DOWN kardeş (OBSERVATIONS) paylaşılan alarmı açık tutar — açanın sağlıklı sonucu kapatmaz")
    void loadedSiblingHoldsSharedAlarm() {
        long token = armed();
        openAlarm(7L);
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_OBSERVATIONS))).isTrue();
        mos.handleSweepResults(PORT, List.of(item(true, 7L, 3, () -> status("up"))));
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        assertThat(messages(Level.INFO)).anyMatch(m -> m.startsWith("Recovery bekliyor"));
    }

    @Test
    @DisplayName("tarama yüklemeyi bekler, sonra YÜKLENEN sayaçla karar verir (2/3 + 1 = 3/3 → tek kapanış); bekleme rapora yazılır")
    void sweepWaitsForLoadThenUsesLoadedCounter() throws Exception {
        long token = armed();
        openAlarm(7L);
        CountDownLatch done = new CountDownLatch(1);
        start(() -> {
            mos.handleSweepResults(PORT, List.of(item(true, 7L, 3, () -> status("up"))));
            done.countDown();
        });
        awaitTrue(() -> mos.evalLoadGate().waiters() == 1);
        assertThat(done.await(150, TimeUnit.MILLISECONDS)).as("kapı LOADING — tarama bekler").isFalse();
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        clock.addAndGet(400);
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL, false))).isTrue();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(HOST, PORT);
        EvalLoadReport r = mos.lastEvalLoad();
        assertThat(r.waiters()).isEqualTo(1);
        assertThat(r.maxWaitMs()).isEqualTo(400L);
    }

    @Test
    @DisplayName("kontrol: yükleme atlanınca (süreklilik reddi) tarama boş bellekle sürer — 1/3, kapanış yok (bugünkü davranış)")
    void skippedLoadIsRestartSemantics() throws Exception {
        long token = armed();
        openAlarm(7L);
        CountDownLatch done = new CountDownLatch(1);
        start(() -> {
            mos.handleSweepResults(PORT, List.of(item(true, 7L, 3, () -> status("up"))));
            done.countDown();
        });
        awaitTrue(() -> mos.evalLoadGate().waiters() == 1);
        mos.evalLoadSkipped(token, EvalLoadReport.none(clock.get(), "pod-b-00000002",
                EvalLoadReport.REASON_PREVIOUS_UNMARKED, null, 3L, "pod-a-00000001", null, 1_000L, 5L));
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        assertThat(mos.recoveryValues(PORT + ":" + HOST).upCount()).isEqualTo(1);
        EvalLoadReport r = mos.lastEvalLoad();
        assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_PREVIOUS_UNMARKED);
        assertThat(r.waiters()).isEqualTo(1);
        assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("devirde yüklenmedi (previous_unmarked)"));
        mos.evalLoadSkipped(token, null);
        assertThat(mos.lastEvalLoad()).as("ikinci atlama yok sayılır").isSameAs(r);

        long token2 = armed();
        mos.evalLoadSkipped(token2, null);
        assertThat(mos.lastEvalLoad().reason()).as("raporsuz atlama = okunamadı").isEqualTo(EvalLoadReport.REASON_READ_FAILED);
        assertThat(messages(Level.WARN)).anyMatch(m -> m.contains("devirde yüklenmedi (read_failed)"));
    }

    // ── Atma nedenleri ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("son tarih: geç uygulama timeout (WARN); kimse girmese de rapor okuması zaman aşımını bildirir")
    void timeoutDiscards() {
        long token = armed();
        clock.addAndGet(EvalLoadGate.LOAD_TIMEOUT_MS);
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL))).isFalse();
        EvalLoadReport r = mos.lastEvalLoad();
        assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
        assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_TIMEOUT);
        assertThat(r.attemptedTier()).isEqualTo(EvalLoad.TIER_FULL);
        assertThat(r.fromGeneration()).isEqualTo(3L);
        assertThat(mos.verifySnapshot().recoveryUpCount()).isEmpty();
        assertThat(messages(Level.WARN)).anyMatch(m -> m.contains("devirde yüklenmedi (timeout)"));

        long token2 = armed();
        clock.addAndGet(EvalLoadGate.LOAD_TIMEOUT_MS);
        EvalLoadReport r2 = mos.lastEvalLoad();
        assertThat(r2.reason()).isEqualTo(EvalLoadReport.REASON_TIMEOUT);
        assertThat(mos.evalLoadToken()).isZero();
        assertThat(mos.applyEvalLoad(load(token2, EvalLoad.TIER_FULL))).as("geç yük reddedilir").isFalse();
        assertThat(mos.lastEvalLoad()).isSameAs(r2);
    }

    @Test
    @DisplayName("ayar yükleme sırasında kapandı → setting_turned_off (WARN), bellek boş")
    void settingTurnedOffDuringLoad() {
        AtomicBoolean on = new AtomicBoolean(true);
        mos.setEvalReadEnabled(on::get);
        gained();
        long token = mos.evalLoadToken();
        on.set(false);
        assertThat(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL))).isFalse();
        assertThat(mos.lastEvalLoad().reason()).isEqualTo(EvalLoadReport.REASON_SETTING_TURNED_OFF);
        assertThat(mos.verifySnapshot().downObserved()).isEmpty();
        assertThat(messages(Level.WARN)).anyMatch(m -> m.contains("(setting_turned_off)"));
    }

    @Test
    @DisplayName("boş olmayan belleğe uygulama reddedilir (evaluated_before_load) — birleştirme yok, bellek olduğu gibi")
    void nonEmptyMemoryRejected() {
        mos.handleSweepResults(PORT, List.of(new SweepItem(PORT, "w.example.com", "1/TCP", true, null,
                Map.of("monitor_id", 21L), null)));   // zamanlanmış tur: tur işareti + pencere belleğe girdi
        mos.setEvalReadEnabled(() -> true);
        mos.evalLoadGate().arm(5L, clock.get() + EvalLoadGate.LOAD_TIMEOUT_MS);   // sıfırlamasız kurulum (yalnız test)
        assertThat(mos.applyEvalLoad(load(5L, EvalLoad.TIER_FULL))).isFalse();
        assertThat(mos.lastEvalLoad().reason()).isEqualTo(EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD);
        assertThat(mos.recoveryValues(PORT + ":" + HOST)).isEqualTo(EvalStateSource.RecoveryValues.NONE);
        assertThat(mos.downObservation(PORT, 5L).present()).isFalse();
        assertThat(mos.typeValues(PORT).scheduledRoundSeen()).isTrue();
        assertThat(messages(Level.WARN)).anyMatch(m -> m.contains("(evaluated_before_load)"));
    }

    @Test
    @DisplayName("kurulumdan önce giren değerlendirme sayılır: uygulama onu bekler, bellek dolu bulur → evaluated_before_load")
    void slippedInEvaluationDiscardsLoad() throws Exception {
        MonitoringMultiPodKit.Manual ex = new MonitoringMultiPodKit.Manual();
        ReflectionTestUtils.setField(mos, "confirmExecutor", ex);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenAnswer(i -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        mos.setEvalReadEnabled(() -> true);
        CountDownLatch done = new CountDownLatch(1);
        start(() -> {
            mos.handleSweepResults(PORT, List.of(item(false, 31L, 3, () -> status("down"))));
            done.countDown();
        });
        assertThat(inside.await(3, TimeUnit.SECONDS)).isTrue();
        gained();                                       // değerlendirme NONE'da girdi — sayılıyor
        long token = mos.evalLoadToken();
        AtomicReference<Boolean> applied = new AtomicReference<>();
        Thread applier = start(() -> applied.set(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL))));
        Thread.sleep(150);
        assertThat(applied.get()).as("uygulama uçuştaki değerlendirmeyi bekler").isNull();
        release.countDown();
        applier.join(5_000);
        assertThat(applied.get()).isFalse();
        assertThat(mos.lastEvalLoad().reason()).isEqualTo(EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD);
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(mos.recoveryValues(PORT + ":" + HOST)).as("yük birleştirilmedi").isEqualTo(EvalStateSource.RecoveryValues.NONE);
        assertThat(mos.activeConfirmations(null)).as("değerlendirmenin kendi zinciri").hasSize(1);
    }

    @Test
    @DisplayName("uçuştaki değerlendirme son tarihe dek bitmezse evaluation_busy (WARN); yük uygulanmaz")
    void evaluationBusyDiscards() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenAnswer(i -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        mos.setEvalReadEnabled(() -> true);
        start(() -> mos.handleSweepResults(PORT, List.of(item(true, 41L, 3, () -> status("up")))));
        assertThat(inside.await(3, TimeUnit.SECONDS)).isTrue();
        gained();
        long token = mos.evalLoadToken();
        AtomicReference<Boolean> applied = new AtomicReference<>();
        Thread applier = start(() -> applied.set(mos.applyEvalLoad(load(token, EvalLoad.TIER_FULL))));
        awaitTrue(() -> applier.getState() == Thread.State.TIMED_WAITING);   // uygulama uçuştakini bekliyor
        clock.addAndGet(EvalLoadGate.LOAD_TIMEOUT_MS);
        applier.join(5_000);
        release.countDown();
        assertThat(applied.get()).isFalse();
        assertThat(mos.lastEvalLoad().reason()).isEqualTo(EvalLoadReport.REASON_EVALUATION_BUSY);
        assertThat(messages(Level.WARN)).anyMatch(m -> m.contains("(evaluation_busy)"));
    }

    @Test
    @DisplayName("boş toplu iş kapıda beklemez; DNS turu ve yeniden kuyruk da kapıdan geçer (iç çağrı yeniden beklemez)")
    void emptyBatchSkipsGate_dnsEntriesGated() throws Exception {
        armed();
        mos.handleSweepResults(PORT, List.of());       // LOADING'de bile anında döner
        mos.requeueDnsChanges(List.of());
        assertThat(mos.evalLoadGate().waiters()).isZero();

        CountDownLatch dnsDone = new CountDownLatch(1);
        start(() -> {
            mos.handleDnsSweep(List.of(new SweepItem(EscalationService.TYPE_DNS_FAILURE, "d.example.com", "A", true, null,
                    Map.of("monitor_id", 51L), null)), List.of(), List.of(), List.of(), List.of());
            dnsDone.countDown();
        });
        awaitTrue(() -> mos.evalLoadGate().waiters() == 1);
        CountDownLatch requeueDone = new CountDownLatch(1);
        start(() -> {
            mos.requeueDnsChanges(List.of(new MonitoringOutageService.DnsChange("c.example.com", "A", "1.1.1.1", "2.2.2.2",
                    "2026-10-23T10:00:00", null, null, () -> status("up"))));
            requeueDone.countDown();
        });
        awaitTrue(() -> mos.evalLoadGate().waiters() == 2);
        lost();
        assertThat(dnsDone.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(requeueDone.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(mos.evalLoadGate().inFlight()).isZero();
    }

    // ── Devir kancaları (tasarım §5.1) ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("devir kancaları: HANDOVER'a geçer, durulma uçuşta değerlendirme yoksa true, bitince NONE")
    void handoverHooks() {
        mos.beginEvalHandover();
        assertThat(mos.evalLoadGate().state()).isEqualTo(EvalLoadGate.State.HANDOVER);
        assertThat(mos.awaitEvalQuiescent(50)).isTrue();
        mos.endEvalHandover();
        assertThat(mos.evalLoadGate().state()).isEqualTo(EvalLoadGate.State.NONE);
        mos.endEvalHandover();
        assertThat(loadLines()).isEmpty();
    }
}
