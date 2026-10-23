package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.evalstate.EvalSnapshot;
import com.sitemonitor.service.evalstate.EvalStateKeys;
import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.evalstate.EvalStateSource;
import com.sitemonitor.service.evalstate.MirrorRecorder;
import com.sitemonitor.service.evalstate.RecordingSink;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-1 A2 — kalıcı değerlendirme durumu İKİLİ YAZIM kancaları (tasarım R3-1 §2.5). Her kanca noktası →
 * beklenen kaydedici çağrısı (ve o andaki kaynak değeri — "kanca mutasyondan SONRA"). Kancalar karar yolunu değiştirmez
 * (I-1 ayrı sınıfta: {@link MonitoringOutageDualWriteDecisionTest}); burada içerik ve sıra.
 *
 * <p>Gerçek kaydedici (A1) birleşince aynı senaryoların H2 tablosu sonuçları {@code delta-R3-1-A2.md}'deki bekleyen testte;
 * burada tablo sonucu, sözleşmeye sadık eşzamanlı ayna ({@link MirrorRecorder}) ile doğrulanır.
 */
class MonitoringOutageDualWriteTest {

    static final String PORT = EscalationService.TYPE_PORT_DOWN;
    static final String HOST = "db.example.com";

    AlertEventRepository alertRepo;
    EscalationService escalation;
    JdbcTemplate jdbc;
    AppSettingsService settings;
    NetworkOutageEventRepository outageRepo;
    MonitoringOutageService mos;
    RecordingSink sink;
    final AtomicLong clock = new AtomicLong(MultiPodTestSupport.T0);

    static ScheduledThreadPoolExecutor immediate() {
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
        alertRepo = mock(AlertEventRepository.class);
        escalation = mock(EscalationService.class);
        jdbc = mock(JdbcTemplate.class);
        settings = mock(AppSettingsService.class);
        outageRepo = mock(NetworkOutageEventRepository.class);
        mos = build(alertRepo, escalation, jdbc, settings, outageRepo, clock);
        sink = RecordingSink.recording();
        mos.setEvalStateSink(sink);
    }

    static MonitoringOutageService build(AlertEventRepository alertRepo, EscalationService escalation, JdbcTemplate jdbc,
                                         AppSettingsService settings, NetworkOutageEventRepository outageRepo,
                                         AtomicLong clock) {
        MonitoringOutageService s = new MonitoringOutageService(alertRepo, escalation, jdbc,
                mock(DnsRecordRepository.class), mock(DnsMonitorRepository.class), settings, outageRepo);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        for (String f : List.of("uptimeAlertEnabled", "portAlertEnabled", "dnsAlertEnabled", "keywordAlertEnabled",
                "pingAlertEnabled")) {
            ReflectionTestUtils.setField(s, f, true);
        }
        ReflectionTestUtils.setField(s, "confirmAttempts", 3);
        ReflectionTestUtils.setField(s, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(s, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(s, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(s, "confirmExecutor", immediate());
        ReflectionTestUtils.setField(s, "recoveryExecutor", immediate());
        ReflectionTestUtils.setField(s, "changeLockRetryMs", 1L);
        ReflectionTestUtils.setField(s, "nowMs", (LongSupplier) clock::get);
        ReflectionTestUtils.setField(s, "suppressionClock", (LongSupplier) clock::get);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        return s;
    }

    static Map<String, Object> status(String s, String error) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", s);
        m.put("error", error);
        return m;
    }

    static MonitoringOutageService.SweepItem item(String type, String domain, String detail, boolean up, Long monitorId,
                                                  Map<String, Object> extra, Supplier<Map<String, Object>> recheck) {
        Map<String, Object> ctx = new LinkedHashMap<>(extra == null ? Map.of() : extra);
        if (monitorId != null) ctx.put("monitor_id", monitorId);
        return new MonitoringOutageService.SweepItem(type, domain, detail, up, up ? null : "connect timed out", ctx, recheck);
    }

    AlertEvent openAlarm(String type, String domain) {
        AlertEvent e = new AlertEvent();
        e.setId(42L);
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("WARNING");
        e.setAcknowledged(false);
        e.setResolved(false);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
        return e;
    }

    // ── Bağlama ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bağlama: ayarlayıcı kaynağı bağlar; bindSource fırlatsa da bağlama kalır, açılış düşmez")
    void setter_bindsSource_throwingBindTolerated() {
        assertThat(sink.bindCalls()).isEqualTo(1);
        assertThat(sink.source()).isSameAs(mos);
        RecordingSink bad = RecordingSink.throwing();
        mos.setEvalStateSink(bad);   // fırlatmaz
        assertThat(mos.evalStateSink()).isSameAs(bad);
        assertThat(mos.evalStateHookFailures()).isEqualTo(1);
        mos.setEvalStateSink(null);
        assertThat(mos.evalStateSink()).isNull();
    }

    @Test
    @DisplayName("Spring bağlaması döngüsüz: sağlayıcı yalnız saklanır, bağlam yenilenince TEK kaydedici bir kez bağlanır")
    @SuppressWarnings("unchecked")
    void contextRefreshed_bindsUniqueSinkOnce() {
        MonitoringOutageService s = build(alertRepo, escalation, jdbc, settings, outageRepo, clock);
        ObjectProvider<EvalStateSink> provider = mock(ObjectProvider.class);
        RecordingSink rec = RecordingSink.recording();
        when(provider.getIfUnique()).thenReturn(rec);
        s.setEvalStateSinkProvider(provider);
        assertThat(s.evalStateSink()).as("enjeksiyonda çözülmez (döngü yok)").isNull();
        s.bindEvalStateSink();
        s.bindEvalStateSink();
        assertThat(s.evalStateSink()).isSameAs(rec);
        assertThat(rec.bindCalls()).isEqualTo(1);
        verify(provider, times(1)).getIfUnique();

        // Kaydedici yok / tek değil → bağlanmaz, hata da yok.
        MonitoringOutageService none = build(alertRepo, escalation, jdbc, settings, outageRepo, clock);
        ObjectProvider<EvalStateSink> empty = mock(ObjectProvider.class);
        when(empty.getIfUnique()).thenThrow(new IllegalStateException("boom"));
        none.setEvalStateSinkProvider(empty);
        none.bindEvalStateSink();
        assertThat(none.evalStateSink()).isNull();
    }

    // ── Kurtarma ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("recovery: her sayılan sağlıklı tur → kanca, kaynak YENİ değeri gösterir; kapanışta sıfır değer")
    void recoveryCounter_hookAfterMutation() {
        openAlarm(PORT, HOST);
        Map<String, Object> rc = Map.of("monitor_recovery_checks", 3);
        mos.handleSweepResults(PORT, List.of(item(PORT, HOST, "5432/TCP", true, 7L, rc, null)));
        clock.addAndGet(60_000);
        mos.handleSweepResults(PORT, List.of(item(PORT, HOST, "5432/TCP", true, 7L, rc, null)));

        List<RecordingSink.Op> rec = sink.ops("recovery");
        assertThat(rec).hasSize(2);
        assertThat(rec.get(0).key()).isEqualTo(PORT + ":" + HOST);
        assertThat(rec.get(0).extra()).isEqualTo(PORT + "|" + HOST);
        assertThat(rec.get(0).value()).isEqualTo(new EvalStateSource.RecoveryValues(1, MultiPodTestSupport.T0, null, null));
        assertThat(rec.get(1).value())
                .isEqualTo(new EvalStateSource.RecoveryValues(2, MultiPodTestSupport.T0 + 60_000, null, null));

        clock.addAndGet(60_000);
        mos.handleSweepResults(PORT, List.of(item(PORT, HOST, "5432/TCP", true, 7L, rc, null)));
        verify(escalation).resolveMonitoringAlertsForDomain(HOST, PORT);
        List<RecordingSink.Op> after = sink.ops("recovery");
        assertThat(after).hasSize(4);   // 3. sayım + kapanışta sıfırlama
        assertThat(after.get(3).value()).isEqualTo(EvalStateSource.RecoveryValues.NONE);
    }

    @Test
    @DisplayName("recovery: açık alarmsız sağlıklı alan adı her turda sıfırlanır ama DEĞİŞMEYEN anahtar kuyruğa girmez")
    void healthyWithoutAlarm_noRecoveryOps() {
        for (int i = 0; i < 3; i++) {
            mos.handleSweepResults(PORT, List.of(item(PORT, HOST, "5432/TCP", true, 7L, null, null),
                    item(PORT, "other.example.com", "443/TCP", true, 8L, null, null)));
            clock.addAndGet(30_000);
        }
        assertThat(sink.ops("recovery")).isEmpty();
        assertThat(sink.ops("observation")).hasSize(6);
    }

    @Test
    @DisplayName("recovery: aktif kurtarma zinciri başlarken kuşak, bitince sıfır — zincir alanları da aynada")
    void activeRecoveryChain_generationMirrored() {
        openAlarm(PORT, HOST);
        MonitoringMultiPodKit.Manual queued = new MonitoringMultiPodKit.Manual();
        ReflectionTestUtils.setField(mos, "recoveryExecutor", queued);
        Map<String, Object> rc = Map.of("monitor_recovery_checks", 2, "monitor_recovery_interval_ms", 1000L);
        mos.handleSweepResults(PORT, List.of(item(PORT, HOST, "5432/TCP", true, 7L, rc, () -> status("up", null))));

        List<RecordingSink.Op> rec = sink.ops("recovery");
        assertThat(rec).hasSize(2);   // sayım (1/2) + zincir başladı
        EvalStateSource.RecoveryValues started = (EvalStateSource.RecoveryValues) rec.get(1).value();
        assertThat(started.upCount()).isEqualTo(1);
        assertThat(started.chainGen()).isNotNull();
        assertThat(started.chainStartedMs()).isEqualTo(MultiPodTestSupport.T0);

        queued.drain();   // zincirin adımı koşar → kapatır
        verify(escalation).resolveMonitoringAlertsForDomain(HOST, PORT);
        List<RecordingSink.Op> after = sink.ops("recovery");
        assertThat(after.get(after.size() - 1).value()).isEqualTo(EvalStateSource.RecoveryValues.NONE);
    }

    // ── Gözlem ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("observation: HER kalem (UP / DOWN / kimliksiz), elle bayrağı, gözlem anı; kaydediciye recheck'siz İNCE kopya")
    void observations_everyItem_slimCopy() {
        Supplier<Map<String, Object>> recheck = () -> status("down", "connect timed out");
        mos.handleSweepResults(PORT, List.of(
                item(PORT, HOST, "5432/TCP", true, 7L, Map.of("team_id", 3L, "standalone", true, "extra", "x"), null),
                item(PORT, "down.example.com", "443/TCP", false, 8L, null, recheck),
                item(EscalationService.TYPE_ACCESSIBILITY, "inv.example.com", "443", true, null, null, null)), true);

        List<RecordingSink.Op> obs = sink.ops("observation");
        assertThat(obs).extracting(RecordingSink.Op::key).containsExactly(
                PORT + "|m:7", PORT + "|m:8", PORT + "|d:inv.example.com|443");
        assertThat(obs).extracting(RecordingSink.Op::extra).containsOnly("true@" + MultiPodTestSupport.T0);
        MonitoringOutageService.SweepItem first = (MonitoringOutageService.SweepItem) obs.get(0).value();
        assertThat(first.recheck()).isNull();
        assertThat(first.ctxExtra()).containsOnlyKeys("monitor_id", "team_id", "standalone");
        MonitoringOutageService.SweepItem second = (MonitoringOutageService.SweepItem) obs.get(1).value();
        assertThat(second.up()).isFalse();
        assertThat(second.error()).isEqualTo("connect timed out");
        assertThat(second.recheck()).as("izleme varlığını yakalayan lambda kaydediciye gitmez").isNull();
        assertThat(sink.ops("typeState")).as("elle tur damgası yok").isEmpty();
    }

    @Test
    @DisplayName("observation: teyit yeniden kontrolü SAĞLIKLI → bellekteki DOWN gözlemi düşer ve 'up' gözlemi kuyruğa girer")
    void transientRecheck_upObservationMirrored() {
        mos.handleSweepResults(PORT, List.of(item(PORT, HOST, "5432/TCP", false, 7L, null, () -> status("up", null))));
        List<RecordingSink.Op> obs = sink.ops("observation");
        assertThat(obs).hasSize(2);
        assertThat(((MonitoringOutageService.SweepItem) obs.get(0).value()).up()).isFalse();
        assertThat(((MonitoringOutageService.SweepItem) obs.get(1).value()).up()).isTrue();
        assertThat(obs.get(1).key()).isEqualTo(PORT + "|m:7");
        assertThat(mos.verifySnapshot().downObserved()).isEmpty();
        assertThat(sink.ops("chainEnd")).hasSize(1);
    }

    // ── Tür bayrakları ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("typeState: zamanlanmış her tur (kaydedici kısar), bastırma girişi / çıkışı; aralık yalnız %10'dan fazla değişince")
    void typeState_roundsSuppressionAndInterval() {
        List<MonitoringOutageService.SweepItem> healthy = List.of(item(PORT, "a.example.com", "1", true, 1L, null, null));
        mos.handleSweepResults(PORT, healthy);
        assertThat(sink.ops("typeState")).hasSize(1);
        assertThat(((EvalStateSource.TypeValues) sink.ops("typeState").get(0).value()).scheduledRoundSeen()).isTrue();

        clock.addAndGet(30_000);
        mos.handleSweepResults(PORT, healthy);   // tur + ilk aralık (0 → 30 sn)
        assertThat(sink.ops("typeState")).hasSize(3);
        assertThat(((EvalStateSource.TypeValues) sink.ops("typeState").get(2).value()).roundIntervalMs()).isEqualTo(30_000L);

        clock.addAndGet(31_000);
        mos.handleSweepResults(PORT, healthy);   // aralık %3 değişti → yalnız tur
        assertThat(sink.ops("typeState")).hasSize(4);

        // Bastırma girişi: 3 ağ-sınıfı DOWN (oran ≥ eşik) → tur + aralık değişimi + geçiş
        clock.addAndGet(31_000);
        sink.clear();
        mos.handleSweepResults(PORT, List.of(
                item(PORT, "a.example.com", "1", false, 1L, null, () -> status("down", "connect timed out")),
                item(PORT, "b.example.com", "1", false, 2L, null, () -> status("down", "connect timed out")),
                item(PORT, "c.example.com", "1", false, 3L, null, () -> status("down", "connect timed out"))));
        assertThat(sink.ops("typeState")).extracting(o -> ((EvalStateSource.TypeValues) o.value()).suppressionActive())
                .contains(Boolean.TRUE);
        int whileOn = sink.ops("typeState").size();
        assertThat(whileOn).isEqualTo(2);   // tur + bastırma girişi (aralık aynı)

        // Çıkış: sağlıklı turlar oranı eşiğin altına indirir → FALSE
        sink.clear();
        clock.addAndGet(31_000);
        List<MonitoringOutageService.SweepItem> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) many.add(item(PORT, "h" + i + ".example.com", "1", true, 100L + i, null, null));
        mos.handleSweepResults(PORT, many);
        assertThat(sink.ops("typeState")).extracting(o -> ((EvalStateSource.TypeValues) o.value()).suppressionActive())
                .last().isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("intervalChanged: ilk ölçüm ve %10'dan büyük değişim")
    void intervalChangedRule() {
        assertThat(MonitoringOutageService.intervalChanged(0, 0)).isFalse();
        assertThat(MonitoringOutageService.intervalChanged(0, 30_000)).isTrue();
        assertThat(MonitoringOutageService.intervalChanged(30_000, 32_000)).isFalse();
        assertThat(MonitoringOutageService.intervalChanged(30_000, 33_001)).isTrue();
        assertThat(MonitoringOutageService.intervalChanged(30_000, 26_000)).isTrue();
        assertThat(MonitoringOutageService.intervalChanged(30_000, 0)).isFalse();
    }

    // ── Teyit zincirleri ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Zincir: başlangıç (deneme 0, gecikme) → her denemede 2 ilerleme → bitiş; zincir kimliği tutarlı")
    void confirmationChain_fullLifecycle() {
        mos.handleSweepResults(PORT, List.of(item(PORT, HOST, "5432/TCP", false, 7L,
                Map.of("monitor_confirm_interval_ms", 2_500L), () -> status("down", "connect timed out"))));
        verify(escalation).processConfirmedOutage(any(), any(), any(), any());

        List<RecordingSink.Op> chain = sink.ops().stream().filter(o -> o.kind().startsWith("chain")).toList();
        assertThat(chain).extracting(RecordingSink.Op::kind).containsExactly("chainStart",
                "chainProgress", "chainProgress", "chainProgress", "chainProgress", "chainProgress", "chainProgress",
                "chainEnd");
        String key = PORT + ":" + HOST + ":5432/TCP";
        assertThat(chain).extracting(RecordingSink.Op::key).containsOnly(key);
        MonitoringOutageService.ConfirmState initial = (MonitoringOutageService.ConfirmState) chain.get(0).value();
        assertThat(initial.attempt()).isZero();
        assertThat(initial.totalAttempts()).isEqualTo(3);
        String chainId = String.valueOf(chain.get(7).extra());
        assertThat(String.valueOf(chain.get(0).extra())).startsWith(chainId + "|2500|").endsWith("|" + HOST);
        assertThat(chain.get(1).extra()).isEqualTo(chainId + "|0");   // deneme 1 başladı, liste boş
        assertThat(chain.get(2).extra()).isEqualTo(chainId + "|1");   // deneme 1 eklendi
        assertThat(((MonitoringOutageService.ConfirmState) chain.get(6).value()).attempt()).isEqualTo(3);
        assertThat(chain.get(6).extra()).isEqualTo(chainId + "|3");
        assertThat(sink.ops("sweepEnd")).extracting(RecordingSink.Op::key).containsExactly(PORT);
    }

    @Test
    @DisplayName("Zincir: değişiklik alarmı kilidi meşgul → her yeniden denemeden ÖNCE FINISHING, en sonda bitiş")
    void changeLockRetry_finishingThenEnd() {
        when(jdbc.update(startsWith("INSERT INTO scheduler_lock"), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("kilit başka yazarda"));
        mos.handleDnsSweep(List.of(), List.of(), List.of(new MonitoringOutageService.DnsChange("changed.example.com", "A",
                "1.1.1.1", "2.2.2.2", "2026-10-23T10:00:00", null, null, () -> status("down", null))), List.of(), List.of());

        List<RecordingSink.Op> fin = sink.ops("chainFinishing");
        assertThat(fin).extracting(RecordingSink.Op::value)
                .containsExactly(1, 2, 3, 4, 5, 6);
        List<String> kinds = sink.ops().stream().map(RecordingSink.Op::kind).filter(k -> k.startsWith("chain")).toList();
        assertThat(kinds.get(kinds.size() - 1)).isEqualTo("chainEnd");
        assertThat(kinds.indexOf("chainEnd")).isGreaterThan(kinds.lastIndexOf("chainFinishing"));
        assertThat(sink.ops("sweepEnd")).extracting(RecordingSink.Op::key).contains(EscalationService.TYPE_DNS_CHANGED);
    }

    @Test
    @DisplayName("Eski (zincir kimliksiz) teyit çağrısı: bitişte anahtarı tutan zincirin kimliği; sahipsiz kayıtta -1")
    void legacyChainRelease_endsWithOwnerId() {
        ScheduledThreadPoolExecutor hold = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                return null;   // ilk deneme planlanır ama koşmaz
            }
        };
        ReflectionTestUtils.setField(mos, "confirmExecutor", hold);
        MonitoringOutageService.SweepItem it = item(PORT, HOST, "5432/TCP", false, 7L, null, () -> status("up", null));
        mos.handleSweepResults(PORT, List.of(it));
        long chainId = Long.parseLong(String.valueOf(sink.ops("chainStart").get(0).extra()).split("\\|")[0]);
        mos.runConfirmAttempt(PORT + ":" + HOST + ":5432/TCP", it, "2026-10-23T10:00:00", new ArrayList<>(), 1);
        assertThat(sink.ops("chainEnd")).extracting(RecordingSink.Op::extra).containsExactly(chainId);

        // Sahipsiz kayıt (zincir kimliği yok): eski yol kaydı bırakır, bitiş -1 kimliğiyle; kayıt yoksa bitiş yok.
        sink.clear();
        String orphanKey = PORT + ":orphan.example.com:1";
        @SuppressWarnings("unchecked")
        Map<String, MonitoringOutageService.ConfirmState> inFlight =
                (Map<String, MonitoringOutageService.ConfirmState>) ReflectionTestUtils.getField(mos, "inFlight");
        inFlight.put(orphanKey, new MonitoringOutageService.ConfirmState(PORT, "orphan.example.com", "1", 0, 3,
                "2026-10-23T10:00:00", 0L));
        MonitoringOutageService.SweepItem orphan = item(PORT, "orphan.example.com", "1", false, null, null,
                () -> status("up", null));
        mos.runConfirmAttempt(orphanKey, orphan, "2026-10-23T10:00:00", new ArrayList<>(), 1);
        assertThat(sink.ops("chainEnd")).extracting(RecordingSink.Op::extra).containsExactly(-1L);
        sink.clear();
        mos.runConfirmAttempt(orphanKey, orphan, "2026-10-23T10:00:00", new ArrayList<>(), 1);
        assertThat(sink.ops("chainEnd")).isEmpty();
    }

    @Test
    @DisplayName("sweepEnd: her değerlendirme çağrısının sonunda (boş / kapıdan dönen çağrı dâhil), DNS turu ve yeniden kuyruk")
    void sweepEnd_afterEveryCall() {
        mos.handleSweepResults(PORT, List.of());
        mos.requeueDnsChanges(List.of());
        mos.handleDnsSweep(List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(sink.ops("sweepEnd")).extracting(RecordingSink.Op::key).containsExactly(PORT,
                EscalationService.TYPE_DNS_CHANGED, EscalationService.TYPE_DNS_FAILURE, EscalationService.TYPE_DNS_SLOW,
                EscalationService.TYPE_DNS_UNEXPECTED, EscalationService.TYPE_DNS_INCONSISTENT,
                EscalationService.TYPE_DNS_CHANGED);
    }

    @Test
    @DisplayName("Liderlik sıfırlaması kanca çağırmaz (kaydedici aynı olayı dinler); sonra kaynak boş değer verir")
    void reset_callsNoHooks() {
        openAlarm(PORT, HOST);
        mos.handleSweepResults(PORT, List.of(item(PORT, HOST, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 3), null)));
        sink.clear();
        mos.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "p", 0L, SweepLeadershipChangedEvent.LOST));
        assertThat(sink.ops()).isEmpty();
        assertThat(mos.recoveryValues(PORT + ":" + HOST)).isEqualTo(EvalStateSource.RecoveryValues.NONE);
        assertThat(mos.typeValues(PORT)).isEqualTo(EvalStateSource.TypeValues.NONE);
    }

    // ── Kaynak ve görüntü ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("errorClass sözleşmesi: isOutageClass(e, d) ⇔ sınıf ∈ {NETWORK, DNS}; sınıflar beklenen")
    void errorClassContract() {
        String[][] table = {
                {null, "x.com", null}, {"  ", "x.com", null},
                {"Connect timed out", "x.com", "NETWORK"}, {"Connection refused", "x.com", "NETWORK"},
                {"java.net.UnknownHostException: x.com", "x.com", "DNS"}, {"x.com", "x.com", "DNS"}, {"X.COM ", "x.com", "DNS"},
                {"Temporary failure in name resolution", "x.com", "DNS"},
                {"Illegal character in path — connect timed out", "x.com", "CONFIG"},
                {"izin verilmeyen hedef: 10.0.0.1", "x.com", "CONFIG"},
                {"HTTP 500", "x.com", "OTHER"}, {"keyword not found", null, "OTHER"},
        };
        for (String[] row : table) {
            String cls = mos.errorClass(row[0], row[1]);
            assertThat(cls).as(row[0]).isEqualTo(row[2]);
            assertThat(MonitoringOutageService.isOutageClass(row[0], row[1]))
                    .as("sözleşme: " + row[0]).isEqualTo("NETWORK".equals(cls) || "DNS".equals(cls));
        }
    }

    @Test
    @DisplayName("verifySnapshot: kopyalar (ince DOWN + hata sınıfı) ve pencere örneği YAZMADAN; wouldSuppress = tur kuralı")
    void snapshot_copiesAndPeekDoesNotMutate() {
        openAlarm(PORT, HOST);
        mos.handleSweepResults(PORT, List.of(
                item(PORT, HOST, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 3), null),
                item(PORT, "other.example.com", "5433/TCP", false, 9L, Map.of("team_id", 4L, "standalone", true),
                        () -> status("down", "connect timed out"))));
        clock.addAndGet(30_000);
        EvalSnapshot before = mos.verifySnapshot();
        Object tickBefore = ((long[]) ((Map<?, ?>) ReflectionTestUtils.getField(mos, "suppressionTick")).get(PORT)).clone();
        MonitoringOutageService.WindowSample peek1 = mos.peekWindowSample(PORT);
        MonitoringOutageService.WindowSample peek2 = mos.peekWindowSample(PORT);
        assertThat(peek1).isEqualTo(peek2);
        assertThat((long[]) ((Map<?, ?>) ReflectionTestUtils.getField(mos, "suppressionTick")).get(PORT))
                .isEqualTo((long[]) tickBefore);

        assertThat(before.atMs()).isEqualTo(MultiPodTestSupport.T0 + 30_000);
        assertThat(before.recoveryUpCount()).containsExactly(Map.entry(PORT + ":" + HOST, 1));
        EvalSnapshot.Down d = before.downObserved().get(PORT + "|9");
        assertThat(d).isEqualTo(new EvalSnapshot.Down(PORT, "other.example.com", "5433/TCP", 9L, 4L, true, "NETWORK",
                MultiPodTestSupport.T0));
        assertThat(before.scheduledRoundSeen()).containsExactly(PORT);
        assertThat(before.windows().get(PORT)).isEqualTo(new EvalSnapshot.Window(peek1.networkDown(), peek1.total(), 300_000L));
        assertThat(before.windows().get(PORT)).isEqualTo(new EvalSnapshot.Window(1, 2, 300_000L));
        assertThat(mos.wouldSuppress(PORT, 3, 4)).isTrue();
        assertThat(mos.wouldSuppress(PORT, 2, 2)).as("giriş ≥ 3 ister").isFalse();
    }

    @Test
    @DisplayName("replayEvalState: bellekteki kurtarma, tür ve DOWN gözlemleri yeniden kuyruğa (bellekte olmayan anahtar oynatılmaz)")
    void replay_enqueuesMemoryState() {
        openAlarm(PORT, HOST);
        mos.handleSweepResults(PORT, List.of(
                item(PORT, HOST, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 3), null)));
        mos.handleSweepResults(PORT, List.of(item(PORT, "x.example.com", "1", false, 11L, null,
                () -> status("down", "connect timed out"))));
        sink.clear();
        int n = mos.replayEvalState();
        assertThat(sink.ops("recovery")).extracting(RecordingSink.Op::key).containsExactly(PORT + ":" + HOST);
        assertThat(sink.ops("recovery").get(0).value())
                .isEqualTo(new EvalStateSource.RecoveryValues(1, MultiPodTestSupport.T0, null, null));
        assertThat(sink.ops("typeState")).extracting(RecordingSink.Op::key).containsExactly(PORT);
        assertThat(sink.ops("observation")).extracting(RecordingSink.Op::key).containsExactly(PORT + "|m:11");
        assertThat(sink.ops("observation").get(0).extra()).isEqualTo("false@" + MultiPodTestSupport.T0);
        assertThat(sink.ops().stream().filter(o -> o.kind().startsWith("chain")).toList()).as("zincir oynatılmaz").isEmpty();
        assertThat(n).isEqualTo(3);

        mos.setEvalStateSink(null);
        assertThat(mos.replayEvalState()).as("kaydedici yok → hiçbir şey").isZero();
    }

    // ── Bozuk kaydedici ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Her çağrıda fırlatan kaydedici: istisna MOS'a yayılmaz, sayılır; zincir ve alarm sürer")
    void throwingSink_neverPropagates() {
        mos.setEvalStateSink(RecordingSink.throwing());
        long before = mos.evalStateHookFailures();
        mos.handleSweepResults(PORT, List.of(item(PORT, HOST, "5432/TCP", false, 7L, null,
                () -> status("down", "connect timed out"))));
        verify(escalation).processConfirmedOutage(any(), any(), any(), any());
        assertThat(mos.evalStateHookFailures() - before).isGreaterThan(5);
        assertThat(mos.activeConfirmations(null)).isEmpty();
    }

    // ── H2 aynası: kanca → tablo satırı ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("H2 aynası: kanca noktaları → beklenen satırlar (kurtarma sayacı, DOWN / UP gözlemi, tür bayrakları, zincir silindi)")
    void mirrorTuplesOnH2() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            MirrorRecorder rec = new MirrorRecorder(mp.jdbc(), "pod-a", 1L, clock::get);
            mos.setEvalStateSink(rec);
            openAlarm(PORT, HOST);
            ScheduledThreadPoolExecutor hold = new ScheduledThreadPoolExecutor(1) {
                @Override
                public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                    return null;
                }
            };
            ReflectionTestUtils.setField(mos, "confirmExecutor", hold);
            mos.handleSweepResults(PORT, List.of(
                    item(PORT, HOST, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 3), null),
                    item(PORT, "down.example.com", "1/TCP", false, 8L, Map.of("team_id", 2L), () -> status("down", "x"))));

            assertThat(mp.jdbc().queryForMap("SELECT recovery_up_count, recovery_last_counted_ms, gen, writer "
                    + "FROM monitor_eval_state WHERE alert_type = ? AND domain = ?", PORT, HOST))
                    .containsEntry("RECOVERY_UP_COUNT", 1).containsEntry("RECOVERY_LAST_COUNTED_MS", MultiPodTestSupport.T0)
                    .containsEntry("GEN", 1L).containsEntry("WRITER", "pod-a");
            assertThat(mp.jdbc().queryForMap("SELECT up, network_down, error_class, source, team_id FROM monitor_last_status "
                    + "WHERE alert_type = ? AND item_key = ?", PORT, "m:8"))
                    .containsEntry("UP", false).containsEntry("NETWORK_DOWN", true).containsEntry("ERROR_CLASS", "NETWORK")
                    .containsEntry("SOURCE", "S");
            assertThat(mp.jdbc().queryForObject("SELECT team_id FROM monitor_last_status WHERE item_key = 'm:8'", Long.class))
                    .isEqualTo(2L);
            assertThat(mp.jdbc().queryForObject("SELECT up FROM monitor_last_status WHERE item_key = 'm:7'", Boolean.class))
                    .isTrue();
            assertThat(mp.jdbc().queryForObject("SELECT last_scheduled_round_ms FROM monitor_eval_type_state "
                    + "WHERE alert_type = ?", Long.class, PORT)).isNotNull();
            String key = PORT + ":down.example.com:1/TCP";
            assertThat(mp.jdbc().queryForObject("SELECT state FROM monitor_confirmations WHERE key_hash = ?", String.class,
                    EvalStateKeys.keyHash(key))).isEqualTo("RUNNING");

            mos.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "p", 0L, SweepLeadershipChangedEvent.LOST));
            assertThat(mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_confirmations", Integer.class))
                    .as("sıfırlama kanca çağırmaz — eski kuşak satırı kalır (kaydedici ABANDONED işaretler)").isEqualTo(1);
        }
    }
}
