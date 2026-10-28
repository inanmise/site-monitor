package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.evalstate.MirrorRecorder;
import com.sitemonitor.service.evalstate.RecordingSink;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-1 — değişmez I-1 (tasarım R3-1): kalıcı değerlendirme durumu kaydedicisi YOK, KAYDEDİYOR (sahte /
 * H2 aynası / gerçek kaydedici), JDBC'si HATA veriyor ya da HER ÇAĞRIDA FIRLATIYOR olsa da {@link MonitoringOutageService}'in dış dünyayla etkileşimi — her
 * {@code EscalationService} çağrısı (alarm açma, kapatma, yeniden uyarı, push / posta yolu), {@code notification_logs} izi,
 * ağ kesintisi kaydı, kilit / değişiklik SQL'i, ayar okuması — sırasıyla ve argümanlarıyla BİREBİR aynıdır.
 *
 * <p>Senaryolar mevcut MOS testlerinin temsilcileridir ({@code MonitoringOutageServiceTest},
 * {@code MonitoringOutageWindowedSuppressionTest}, {@code MonitoringOutageSweepReconcileRegressionTest},
 * {@code ManualCheckNoAlarmTest}, {@code LeadershipChangeResetTest}). Tutanak = tüm sahte bağımlılıkların çağrıları, küresel
 * Mockito sıra numarasıyla; ISO zaman damgaları normalize edilir (çalıştırmalar arası saniye sınırı).
 * Gerçek kaydedici (A1 {@code EvalStateRecorder}) iki varyantla: H2'ye yazan ve JDBC'si her çağrıda fırlatan (yazım hatası
 * sayılır, karar aynı); ikisinde de senaryodan sonra boşaltılır.
 */
class MonitoringOutageDualWriteDecisionTest {

    /**
     * Çok pod Sürüm 3-2 (tasarım R3-2 §9.1, J-1): {@code READ_OFF_REAL_RECORDER} = gerçek kaydedici + okuma ayarı AÇIKÇA
     * kapalı; {@code READ_ON_NO_LEADERSHIP_CHANGE} = okuma ayarı AÇIK, kaydedicinin kurulum engeli var / GAINED yok — kapı
     * hiç kurulmaz, geçiş saydamdır. İkisinin de tutanağı kaydedicisizle birebir aynı olmalı.
     */
    enum Variant { NONE, RECORDING, MIRROR_H2, THROWING, REAL_RECORDER, REAL_RECORDER_FAILING_JDBC, READ_OFF_REAL_RECORDER,
        READ_ON_NO_LEADERSHIP_CHANGE }

    static final String PORT = EscalationService.TYPE_PORT_DOWN;
    static final String ACC = EscalationService.TYPE_ACCESSIBILITY;
    static final Pattern ISO_TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z?");

    /** Bir koşunun bağlamı (sahte bağımlılıklar + servis + saat). */
    static final class Env implements AutoCloseable {
        final AtomicLong clock = new AtomicLong(MultiPodTestSupport.T0);
        final AlertEventRepository alertRepo = mock(AlertEventRepository.class, "alertRepo");
        final EscalationService escalation = mock(EscalationService.class, "escalation");
        final JdbcTemplate jdbc = mock(JdbcTemplate.class, "jdbc");
        final AppSettingsService settings = mock(AppSettingsService.class, "settings");
        final NetworkOutageEventRepository outageRepo = mock(NetworkOutageEventRepository.class, "outageRepo");
        final DnsRecordRepository dnsRecordRepo = mock(DnsRecordRepository.class, "dnsRecordRepo");
        final DnsMonitorRepository dnsMonitorRepo = mock(DnsMonitorRepository.class, "dnsMonitorRepo");
        final NotificationLogRepository logRepo = mock(NotificationLogRepository.class, "logRepo");
        final MonitoringOutageService mos;
        final RecordingSink recording;
        MultiPodTestSupport h2;
        com.sitemonitor.service.evalstate.EvalStateRecorder real;

        Env(Variant v) {
            mos = new MonitoringOutageService(alertRepo, escalation, jdbc, dnsRecordRepo, dnsMonitorRepo, settings, outageRepo);
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
            ReflectionTestUtils.setField(mos, "confirmExecutor", immediate());
            ReflectionTestUtils.setField(mos, "recoveryExecutor", immediate());
            ReflectionTestUtils.setField(mos, "changeLockRetryMs", 1L);
            ReflectionTestUtils.setField(mos, "nowMs", (LongSupplier) clock::get);
            ReflectionTestUtils.setField(mos, "suppressionClock", (LongSupplier) clock::get);
            ReflectionTestUtils.setField(mos, "notificationLogRepo", logRepo);
            ReflectionTestUtils.setField(mos, "settingsReconcileExecutor", (java.util.concurrent.Executor) Runnable::run);
            when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
            EvalStateSink sink = switch (v) {
                case NONE -> null;
                case RECORDING, READ_ON_NO_LEADERSHIP_CHANGE -> RecordingSink.recording();
                case THROWING -> RecordingSink.throwing();
                case MIRROR_H2 -> {
                    h2 = MultiPodTestSupport.create();
                    yield new MirrorRecorder(h2.jdbc(), "pod-a", 1L, clock::get);
                }
                case REAL_RECORDER, READ_OFF_REAL_RECORDER -> {
                    h2 = MultiPodTestSupport.create();
                    new com.sitemonitor.service.evalstate.EvalStateSchemaPatch(h2.jdbc()).apply();
                    com.sitemonitor.service.evalstate.EvalStateRecorder.Builder rb =
                            com.sitemonitor.service.evalstate.EvalStateRecorder.builder(h2.jdbc()).writer("pod-a-00000001")
                            .leaderHint(() -> true)
                            .mode(() -> com.sitemonitor.service.evalstate.EvalStateMode.VERIFY)
                            .clock(clock::get).flushMs(50);
                    // 3-2 (J-1): READ_OFF varyantında okuma ayarı kaydedicide de AÇIKÇA kapalı (3-1 ifade kümesi).
                    if (v == Variant.READ_OFF_REAL_RECORDER) rb.readEnabled(() -> false);
                    real = rb.build();
                    yield real;
                }
                case REAL_RECORDER_FAILING_JDBC -> {
                    JdbcTemplate broken = mock(JdbcTemplate.class, inv -> {
                        throw new org.springframework.dao.DataAccessResourceFailureException("db down (test)");
                    });
                    real = com.sitemonitor.service.evalstate.EvalStateRecorder.builder(broken).writer("pod-a-00000001")
                            .leaderHint(() -> true)
                            .mode(() -> com.sitemonitor.service.evalstate.EvalStateMode.VERIFY)
                            .clock(clock::get).flushMs(50).build();
                    yield real;
                }
            };
            recording = sink instanceof RecordingSink r ? r : null;
            if (sink != null) mos.setEvalStateSink(sink);
            // 3-2: okuma ayarı (elle kurulan serviste tedarikçi yok = kapalı; bu iki varyant açıkça verir).
            if (v == Variant.READ_OFF_REAL_RECORDER) mos.setEvalReadEnabled(() -> false);
            if (v == Variant.READ_ON_NO_LEADERSHIP_CHANGE) mos.setEvalReadEnabled(() -> true);
        }

        /** Senaryo başlangıcı — kurulum sırasındaki saplama çağrıları tutanağa girmesin. */
        void begin() {
            marker = maxSeq();
        }

        long marker = -1;

        long maxSeq() {
            long max = -1;
            for (Object m : mocks()) {
                for (Invocation inv : Mockito.mockingDetails(m).getInvocations()) max = Math.max(max, inv.getSequenceNumber());
            }
            return max;
        }

        List<Object> mocks() {
            return List.of(alertRepo, escalation, jdbc, settings, outageRepo, dnsRecordRepo, dnsMonitorRepo, logRepo);
        }

        /** Sahte bağımlılıkların senaryo boyunca aldığı çağrılar, küresel sırayla, zaman damgaları normalize. */
        List<String> transcript() {
            List<Invocation> all = new ArrayList<>();
            for (Object m : mocks()) {
                for (Invocation inv : Mockito.mockingDetails(m).getInvocations()) {
                    if (inv.getSequenceNumber() > marker) all.add(inv);
                }
            }
            all.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
            List<String> out = new ArrayList<>();
            for (Invocation inv : all) out.add(ISO_TS.matcher(inv.toString()).replaceAll("<ts>"));
            return out;
        }

        void advance(long ms) {
            clock.addAndGet(ms);
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

        @Override
        public void close() {
            if (real != null) real.stop();
            if (h2 != null) h2.close();
        }
    }

    static ScheduledThreadPoolExecutor immediate() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                command.run();
                return null;
            }
        };
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

    interface Scenario {
        void run(Env env);
    }

    /** Senaryoyu dört varyantta koşturur; tutanakların hepsi kaydedicisiz koşununkine eşit ve boş değil. */
    static void assertSameDecisions(Scenario scenario) {
        Map<Variant, List<String>> out = new LinkedHashMap<>();
        for (Variant v : Variant.values()) {
            try (Env env = new Env(v)) {
                env.begin();
                scenario.run(env);
                if (env.real != null) env.real.flushNow();
                out.put(v, env.transcript());
                if (v == Variant.REAL_RECORDER_FAILING_JDBC) {
                    assertThat(env.real.status().writeFailures()).as("kaydedici gerçekten yazamadı").isPositive();
                }
                if (env.recording != null && v == Variant.RECORDING) {
                    assertThat(env.recording.ops()).as("kaydedici gerçekten çağrıldı").isNotEmpty();
                }
                if (v == Variant.THROWING) {
                    assertThat(env.mos.evalStateHookFailures()).as("bozuk kaydedici çağrıldı ve yutuldu").isPositive();
                }
                // 3-2 (J-1): kapı hiçbir varyantta kurulmadı; ayar kapalıyken rapor (varsa) yalnız none/setting_off.
                assertThat(env.mos.evalLoadGate().state()).as(v + ": kapı kurulmadı")
                        .isEqualTo(com.sitemonitor.service.evalstate.EvalLoadGate.State.NONE);
                assertThat(env.mos.evalLoadToken()).isZero();
                com.sitemonitor.service.evalstate.EvalLoadReport lr = env.mos.lastEvalLoad();
                if (lr != null) {
                    assertThat(lr.loaded()).isFalse();
                    assertThat(lr.reason()).isEqualTo(v == Variant.READ_ON_NO_LEADERSHIP_CHANGE
                            ? com.sitemonitor.service.evalstate.EvalLoadReport.REASON_NO_RECORDER
                            : com.sitemonitor.service.evalstate.EvalLoadReport.REASON_SETTING_OFF);
                }
            }
        }
        List<String> base = out.get(Variant.NONE);
        assertThat(base).as("senaryo dış dünyaya dokunmalı").isNotEmpty();
        for (Variant v : Variant.values()) {
            assertThat(out.get(v)).as("varyant " + v + " kararları kaydedicisizle aynı").containsExactlyElementsOf(base);
        }
    }

    // ── Senaryolar ──────────────────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(Scenarios.class)
    @DisplayName("I-1: kaydedici yok / kaydediyor / fırlatıyor — MOS kararları birebir aynı")
    void sameDecisions(Scenarios s) {
        assertSameDecisions(s.scenario);
    }

    enum Scenarios {
        CONFIRM_ALL_FAIL(env -> {
            env.mos.handleSweepResults(ACC, List.of(
                    item(ACC, "down.example.com", "443", false, null, Map.of("port", 443), () -> status("down", "timeout")),
                    item(ACC, "ok.example.com", "443", true, null, Map.of(), () -> status("up", null))));
        }),
        TRANSIENT_NO_ALARM(env -> {
            AtomicInteger calls = new AtomicInteger();
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "flap.example.com", "443/TCP", false, 5L, null,
                    () -> calls.incrementAndGet() < 2 ? status("down", "timeout") : status("up", null))));
        }),
        PASSIVE_RECOVERY(env -> {
            env.openAlarm(PORT, "db.example.com");
            for (int i = 0; i < 3; i++) {
                env.mos.handleSweepResults(PORT, List.of(item(PORT, "db.example.com", "5432/TCP", true, 7L,
                        Map.of("monitor_recovery_checks", 3), () -> status("up", null))));
                env.advance(60_000);
            }
        }),
        ACTIVE_RECOVERY(env -> {
            env.openAlarm(PORT, "db.example.com");
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "db.example.com", "5432/TCP", true, 7L,
                    Map.of("monitor_recovery_checks", 3, "monitor_recovery_interval_ms", 1000L), () -> status("up", null))));
        }),
        DOWN_WHILE_OPEN_REALERT(env -> {
            env.openAlarm(PORT, "db.example.com");
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "db.example.com", "5432/TCP", true, 7L,
                    Map.of("monitor_recovery_checks", 3), () -> status("up", null))));
            env.advance(30_000);
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "db.example.com", "5432/TCP", false, 7L,
                    Map.of("monitor_recovery_checks", 3), () -> status("down", "timeout"))));
        }),
        WINDOWED_SUPPRESSION_ENTER_EXIT(env -> {
            List<MonitoringOutageService.SweepItem> down = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                down.add(item(PORT, "n" + i + ".example.com", "443/TCP", false, 10L + i, null,
                        () -> status("down", "Connect timed out")));
            }
            env.mos.handleSweepResults(PORT, down);
            env.advance(30_000);
            env.mos.handleSweepResults(PORT, down);
            env.advance(30_000);
            List<MonitoringOutageService.SweepItem> healthy = new ArrayList<>();
            for (int i = 0; i < 12; i++) healthy.add(item(PORT, "h" + i + ".example.com", "443/TCP", true, 100L + i, null, null));
            env.mos.handleSweepResults(PORT, healthy);
        }),
        MANUAL_NO_ALARM_THEN_CLOSE(env -> {
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "m.example.com", "443/TCP", false, 21L, null,
                    () -> status("down", "timeout"))), true);
            env.openAlarm(PORT, "m.example.com");
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "m.example.com", "443/TCP", true, 21L, null,
                    () -> status("up", null))), true);
        }),
        SIBLING_BLOCKS_CLOSE(env -> {
            AlertEvent e = env.openAlarm(PORT, "s.example.com");
            e.setContextJson("{\"monitor_id\":1}");
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "s.example.com", "5433/TCP", false, 2L, null,
                    () -> status("down", "timeout"))));
            env.advance(30_000);
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "s.example.com", "5432/TCP", true, 1L, null,
                    () -> status("up", null))));
            env.advance(30_000);
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "s.example.com", "5433/TCP", true, 2L, null,
                    () -> status("up", null)), item(PORT, "s.example.com", "5432/TCP", true, 1L, null, () -> status("up", null))));
        }),
        DNS_CHANGE_LOCK_BUSY_GIVES_UP(env -> {
            when(env.jdbc.update(startsWith("INSERT INTO scheduler_lock"), any(), any(), any()))
                    .thenThrow(new DuplicateKeyException("kilit başka yazarda"));
            env.mos.handleDnsSweep(List.of(), List.of(), List.of(new MonitoringOutageService.DnsChange("c.example.com", "A",
                    "1.1.1.1", "2.2.2.2", "2026-10-23T10:00:00", null, null, () -> status("down", null), null, null, true,
                    31L)), List.of(), List.of());
        }),
        DNS_CHANGE_CONFIRMED_AND_REQUEUE(env -> {
            MonitoringOutageService.DnsChange c = new MonitoringOutageService.DnsChange("c.example.com", "A",
                    "1.1.1.1", "2.2.2.2", "2026-10-23T10:00:00", 4L, null, () -> status("down", null), null, null, true, 31L);
            env.mos.handleDnsSweep(List.of(item(EscalationService.TYPE_DNS_FAILURE, "c.example.com", "A", true, 31L, null,
                    null)), List.of(), List.of(c), List.of(), List.of());
            env.mos.requeueDnsChanges(List.of(c));
        }),
        LEADERSHIP_RESET_MID_CHAIN(env -> {
            MonitoringMultiPodKit.Manual ex = new MonitoringMultiPodKit.Manual();
            ReflectionTestUtils.setField(env.mos, "confirmExecutor", ex);
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "r.example.com", "443/TCP", false, 41L, null,
                    () -> status("down", "timeout"))));
            env.mos.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "p", 0L, SweepLeadershipChangedEvent.LOST));
            ex.drain();
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "r.example.com", "443/TCP", false, 41L, null,
                    () -> status("down", "timeout"))));
            ex.drain();
        }),
        FENCED_FOLLOWER_AND_LOST_LEASE(env -> {
            AtomicBoolean leader = new AtomicBoolean(false);
            env.mos.setScheduledResultsFence(leader::get);
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "f.example.com", "443/TCP", false, 51L, null,
                    () -> status("down", "timeout"))));
            leader.set(true);
            env.openAlarm(PORT, "f.example.com");
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "f.example.com", "443/TCP", true, 51L, null,
                    () -> status("up", null))));
        }),
        DISABLED_TYPE_RECONCILE_ONLY(env -> {
            when(env.settings.getBoolean(eq("site.monitor.port.alert-enabled"), anyBoolean())).thenReturn(false);
            env.openAlarm(PORT, "d.example.com");
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "d.example.com", "443/TCP", true, 61L, null,
                    () -> status("up", null)), item(PORT, "e.example.com", "443/TCP", false, 62L, null,
                    () -> status("down", "timeout"))));
            env.mos.onSettingsChanged(new AppSettingsChangedEvent(java.util.Set.of("site.monitor.port.alert-enabled"), "save"));
        }),
        // R3-1 FX-STATE (F2): budama kancaları — duraklatılan kardeşi downSibling budar (kapanış sürer), duraklatılan DOWN
        // gözlemini pruneObservations budar; kaydedici ne olursa olsun kararlar aynı.
        PRUNE_HOOKS_SIBLING_AND_PAUSED(env -> {
            Map<Long, Boolean> monitored = new HashMap<>();
            env.mos.setStillMonitored(it -> {
                Object id = it.ctxExtra() == null ? null : it.ctxExtra().get("monitor_id");
                return !(id instanceof Number n) || monitored.getOrDefault(n.longValue(), true);
            });
            AlertEvent e = env.openAlarm(PORT, "s.example.com");
            e.setContextJson("{\"monitor_id\":1}");
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "s.example.com", "5433/TCP", false, 2L, null,
                    () -> status("down", "timeout"))));
            monitored.put(2L, false);
            env.advance(30_000);
            for (int i = 0; i < 3; i++) {
                env.mos.handleSweepResults(PORT, List.of(item(PORT, "s.example.com", "5432/TCP", true, 1L,
                        Map.of("monitor_recovery_checks", 3), () -> status("up", null))));
                env.advance(60_000);
            }
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "p.example.com", "443/TCP", false, 3L, null,
                    () -> status("down", "timeout"))));
            monitored.put(3L, false);
            env.advance(11 * 60_000L);
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "q.example.com", "443/TCP", true, 4L, null,
                    () -> status("up", null))));
            if (env.recording != null) {
                assertThat(env.recording.ops("observationPruned")).as("iki budama yolu da çalıştı")
                        .extracting(RecordingSink.Op::key).containsExactly(PORT + "|m:2", PORT + "|m:3");
            }
        }),
        // 3-2 (J-1): liderlik GERİ ALINDI (GAINED) — ayar kapalıyken ya da kaydedicinin engeli varken kapı kurulmaz; ardışık
        // sağlıklı turlar bugünkü gibi sıfırdan sayılır (3 tur sonra tek kapanış).
        LEADERSHIP_GAINED_THEN_ROUNDS(env -> {
            env.openAlarm(PORT, "g.example.com");
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "g.example.com", "443/TCP", true, 81L,
                    Map.of("monitor_recovery_checks", 3), () -> status("up", null))));
            env.mos.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "p", 0L, SweepLeadershipChangedEvent.LOST));
            env.mos.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "p", 0L, SweepLeadershipChangedEvent.GAINED));
            for (int i = 0; i < 3; i++) {
                env.advance(60_000);
                env.mos.handleSweepResults(PORT, List.of(item(PORT, "g.example.com", "443/TCP", true, 81L,
                        Map.of("monitor_recovery_checks", 3), () -> status("up", null))));
            }
        }),
        IMMEDIATE_MODE_AND_CONFIG_ERROR_CLOSE(env -> {
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "i.example.com", "443/TCP", false, 71L,
                    Map.of("monitor_confirm_attempts", 0), () -> status("down", "timeout"))));
            env.openAlarm(PORT, "cfg.example.com");
            env.mos.handleSweepResults(PORT, List.of(item(PORT, "cfg.example.com", "443/TCP", true, 72L,
                    Map.of(MonitoringOutageService.CTX_CONFIG_ERROR, true), () -> status("up", null))));
        });

        final Scenario scenario;

        Scenarios(Scenario scenario) {
            this.scenario = scenario;
        }
    }
}
