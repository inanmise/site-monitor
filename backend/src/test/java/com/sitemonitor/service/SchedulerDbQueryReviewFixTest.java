package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.service.dbquery.DbQueryAlarmText;
import com.sitemonitor.service.dbquery.DbQueryAlertRowsService;
import com.sitemonitor.service.dbquery.DbQueryCheckerService;
import com.sitemonitor.service.dbquery.DbQueryEvaluator;
import com.sitemonitor.service.dbquery.DbQueryRunner;
import com.sitemonitor.service.dbquery.DbQuerySchedule;
import com.sitemonitor.service.dbquery.HolidayCalendarService;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Veritabanı sorgu izlemesi tarama katmanının 2026-10-31 inceleme düzeltmeleri: değişim kuralının tabanı (Y1, gerçek motor +
 * gerçek MOS), eşik hatası kodlu (Y2, gerçek MOS bastırması), izin ayırma (O1), yeniden deneme penceresi (O2), zamanlama /
 * tatil değişiminde küme vadesi (O3, gerçek küme zamanlama tablosu H2'de), ardışık atlama (O5), aralık ızgarası (O6),
 * değerlendirilemeyen kural (D3).
 */
class SchedulerDbQueryReviewFixTest {

    static final Instant T0 = Instant.parse("2026-11-02T06:00:00Z");   // Pazartesi 09:00 İstanbul

    SchedulerService scheduler;
    DbQueryCheckerService checker;
    DbQueryMonitorRepository monitorRepo;
    DbQueryCheckRepository checkRepo;
    MonitoringOutageService mosMock;
    AppSettingsService settings;
    final AtomicLong clock = new AtomicLong(T0.toEpochMilli());
    final List<DbQueryCheck> saved = new ArrayList<>();
    final Map<Long, DbQueryMonitor> byId = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        Constructor<?> c = Arrays.stream(SchedulerService.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = mock(pt[i]);
        scheduler = (SchedulerService) c.newInstance(args);
        settings = (AppSettingsService) ReflectionTestUtils.getField(scheduler, "appSettings");
        defaults(settings);
        mosMock = (MonitoringOutageService) ReflectionTestUtils.getField(scheduler, "monitoringOutageService");
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
        ReflectionTestUtils.setField(scheduler, "scheduleClock", (java.util.function.LongSupplier) clock::get);

        checker = mock(DbQueryCheckerService.class);
        lenient().when(checker.tryReserve(any())).thenReturn(() -> { });
        lenient().when(checker.runReserved(any(), any(), any())).thenAnswer(inv -> {
            DbQueryMonitor mm = inv.getArgument(0);
            java.util.function.Function<DbQueryCheckerService.Result, Object> then = inv.getArgument(2);
            return java.util.concurrent.CompletableFuture.completedFuture(then.apply(checker.check(mm, false)));
        });
        monitorRepo = mock(DbQueryMonitorRepository.class);
        lenient().when(monitorRepo.updateNextRunAt(anyLong(), any())).thenAnswer(i -> {
            DbQueryMonitor m = byId.get((Long) i.getArgument(0));
            if (m != null) m.setNextRunAt(i.getArgument(1));
            return 1;
        });
        checkRepo = mock(DbQueryCheckRepository.class);
        lenient().when(checkRepo.save(any(DbQueryCheck.class))).thenAnswer(i -> {
            DbQueryCheck x = i.getArgument(0);
            x.setId(100L + saved.size());
            saved.add(x);
            return x;
        });
        lenient().when(checkRepo.findRecentScheduledOk(anyLong())).thenAnswer(i ->
                scheduledRows(i.getArgument(0), true).limit(2).toList());
        lenient().when(checkRepo.findLatestScheduled(anyLong())).thenAnswer(i ->
                scheduledRows(i.getArgument(0), false).findFirst());
        lenient().when(checkRepo.findScheduledOkBefore(anyLong(), anyString(), anyLong())).thenAnswer(i -> {
            String at = i.getArgument(1);
            long before = i.getArgument(2);
            return scheduledRows(i.getArgument(0), true)
                    .filter(x -> x.getCheckedAt().compareTo(at) < 0 || (x.getCheckedAt().equals(at) && x.getId() < before))
                    .findFirst();
        });
        ReflectionTestUtils.setField(scheduler, "dbQueryCheckerService", checker);
        ReflectionTestUtils.setField(scheduler, "dbQueryMonitorRepo", monitorRepo);
        ReflectionTestUtils.setField(scheduler, "dbQueryCheckRepo", checkRepo);
        ReflectionTestUtils.setField(scheduler, "dbQueryAlertRows", mock(DbQueryAlertRowsService.class));
    }

    /** Depo sorgularının taklidi: izlemenin zamanlanmış satırları (elle / teyit hariç), yeniden yeniye. */
    java.util.stream.Stream<DbQueryCheck> scheduledRows(long id, boolean okOnly) {
        return List.copyOf(saved).stream()
                .filter(x -> x.getMonitorId() == id && (!okOnly || Boolean.TRUE.equals(x.getOk()))
                        && !Boolean.TRUE.equals(x.getManual()) && !Boolean.TRUE.equals(x.getRecheck()))
                .sorted(Comparator.comparing(DbQueryCheck::getCheckedAt).thenComparing(DbQueryCheck::getId).reversed());
    }

    static void defaults(AppSettingsService s) {
        lenient().when(s.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(s.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(s.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(s.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
    }

    static String iso(Instant t) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(t);
    }

    DbQueryMonitor monitor(long id, String name) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(id);
        m.setName(name);
        m.setActive(true);
        m.setTeamId(7L);
        m.setProfileId(3L);
        m.setPurpose("Bekleyen siparişler birikmesin");
        m.setSqlText("SELECT id FROM siparis WHERE durum = 'BEKLIYOR'");
        byId.put(id, m);
        return m;
    }

    void run() {
        ReflectionTestUtils.invokeMethod(scheduler, "runDbQueryChecksLocked");
    }

    /** Sahte zamanlama: sonraki = after + adım, izin {@code allowed}. */
    static SchedulerService.DbQueryScheduling fake(long stepSeconds, AtomicReference<Boolean> allowed) {
        return new SchedulerService.DbQueryScheduling() {
            @Override public DbQuerySchedule.Spec specOf(DbQueryMonitor m) {
                return new DbQuerySchedule.Spec("INTERVAL", (int) stepSeconds, null, List.of(), List.of(), false, List.of());
            }
            @Override public Instant nextAfter(DbQuerySchedule.Spec s, Instant after, Set<LocalDate> h) {
                return after.plusSeconds(stepSeconds);
            }
            @Override public boolean allowedAt(DbQuerySchedule.Spec s, Instant t, Set<LocalDate> h) { return allowed.get(); }
        };
    }

    static DbQueryCheckerService.Result breach(String label, String actual) {
        return new DbQueryCheckerService.Result("BREACH", "CRITICAL", 50, 12, false, List.of(), List.of(), List.of(),
                List.of(), false, List.of(new DbQueryEvaluator.RuleResult("r1", label, "ROW_COUNT", actual, "≤ 10",
                "CRITICAL", false, true, null, 12.0, 12.0)), 12.0, false, null, null, iso(T0), "h", 3L, null);
    }

    static DbQueryCheckerService.Result ok() {
        return new DbQueryCheckerService.Result("OK", "NONE", 50, 1, false, List.of(), List.of(), List.of(), List.of(), false,
                List.of(), 1.0, false, null, null, iso(T0), "h", 3L, null);
    }

    static DbQueryCheckerService.Result skipped() {
        return new DbQueryCheckerService.Result("SKIPPED", "NONE", 0, null, false, List.of(), List.of(), List.of(), List.of(),
                false, List.of(), null, false, "meşgul", "DB_BUSY", iso(T0), null, 3L, null);
    }

    /** Teyit denemelerini hemen koşan yürütücü. */
    static ScheduledThreadPoolExecutor immediate() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                command.run();
                return null;
            }
        };
    }

    /** Gerçek MOS (teyit hemen, bastırma eşikleri varsayılan) — kalemlerin alarm hattındaki sonucu için. */
    static MonitoringOutageService realMos(AlertEventRepository alerts, EscalationService esc, AppSettingsService s,
                                           com.sitemonitor.repository.NetworkOutageEventRepository outages) {
        MonitoringOutageService mos = new MonitoringOutageService(alerts, esc, mock(JdbcTemplate.class),
                mock(com.sitemonitor.repository.DnsRecordRepository.class),
                mock(com.sitemonitor.repository.DnsMonitorRepository.class), s, outages);
        ReflectionTestUtils.setField(mos, "confirmAttempts", 1);
        ReflectionTestUtils.setField(mos, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(mos, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(mos, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(mos, "confirmExecutor", immediate());
        return mos;
    }

    // ── Y1 — değişim kuralının tabanı (gerçek motor + gerçek MOS) ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Y1: değişim kuralı")
    class ChangeRule {

        AlertEventRepository alerts;
        EscalationService esc;
        final List<AlertEvent> open = new ArrayList<>();
        final List<Integer> values = new ArrayList<>(List.of(300, 300, 500, 520, 540, 610, 615));
        final AtomicInteger next = new AtomicInteger();

        @BeforeEach
        void realEngine() {
            DbConnectionProfileRepository profiles = mock(DbConnectionProfileRepository.class);
            DbConnectionProfile p = new DbConnectionProfile();
            p.setId(3L);
            p.setTeamId(7L);
            p.setDbType("POSTGRESQL");
            p.setHost("db.example");
            p.setPort(5432);
            p.setDatabaseName("app");
            p.setUsername("u");
            when(profiles.findById(3L)).thenReturn(Optional.of(p));
            DbQueryRunner runner = mock(DbQueryRunner.class);
            when(runner.run(any(), anyString(), anyInt(), anyInt(), any(DbQueryRunner.RunOptions.class))).thenAnswer(i -> {
                int v = values.get(Math.min(next.getAndIncrement(), values.size() - 1));
                return new DbQueryRunner.QueryResult(true, 5, v, false, List.of(new DbQueryRunner.Column("id", "INT")),
                        List.of(), false, null, null, "PostgreSQL 16");
            });
            AppSettingsService es = mock(AppSettingsService.class);
            defaults(es);
            DbQueryCheckerService real = new DbQueryCheckerService(profiles, checkRepo, mock(SecretCipher.class), runner, es);
            ReflectionTestUtils.setField(scheduler, "dbQueryCheckerService", real);
            ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(300, new AtomicReference<>(true)));

            alerts = mock(AlertEventRepository.class);
            when(alerts.findOpenByDomainIn(anyCollection())).thenAnswer(i -> List.copyOf(open));
            esc = mock(EscalationService.class);
            ReflectionTestUtils.setField(scheduler, "monitoringOutageService",
                    realMos(alerts, esc, settings, mock(com.sitemonitor.repository.NetworkOutageEventRepository.class)));
        }

        @AfterEach
        void stopEngine() {
            ((DbQueryCheckerService) ReflectionTestUtils.getField(scheduler, "dbQueryCheckerService")).shutdown();
        }

        void sweep(DbQueryMonitor m) {
            m.setNextRunAt(iso(Instant.ofEpochMilli(clock.get()).minusSeconds(1)));
            run();
            clock.addAndGet(400_000);
        }

        @Test
        @DisplayName("300 → 500 sıçrama: varsayılan teyitle alarm AÇILIR; elle kontrol kapatmaz; sonraki zamanlanmış önceki zamanlanmışla kıyaslanır")
        void spikeOpensManualKeepsNextComparesScheduled() {
            DbQueryMonitor m = monitor(11L, "Bekleyen sipariş değişimi");
            m.setRulesJson("[{\"id\":\"c\",\"label\":\"Bekleyen sipariş artışı\",\"kind\":\"CHANGE\",\"operator\":\"GT\","
                    + "\"warning\":100,\"change\":{\"of\":\"ROW_COUNT\",\"mode\":\"ABSOLUTE\",\"direction\":\"INCREASE\"}}]");
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));

            sweep(m);   // 300 — ilk çalıştırma (taban yok): eşik kalemi yok
            sweep(m);   // 300 — değişim yok
            sweep(m);   // 500 — ihlal; teyit (520) ONAYLANAN çalıştırmanın tabanıyla (300) kıyaslar → alarm açılır
            verify(esc, times(1)).processConfirmedOutage(eq("Bekleyen sipariş değişimi"),
                    eq(EscalationService.TYPE_DBQUERY_THRESHOLD), anyString(), any());
            DbQueryCheck confirm = saved.get(3);
            assertThat(confirm.getRecheck()).as("teyit satırı bant dışı").isTrue();
            assertThat(confirm.getManual()).isFalse();
            assertThat(confirm.getStatus()).isEqualTo("BREACH");

            // Açık alarm var: elle "Şimdi kontrol et" (540) tabanı 300 → ihlal sürüyor → sahte kapanış YOK.
            AlertEvent ev = new AlertEvent();
            ev.setId(900L);
            ev.setDomain("Bekleyen sipariş değişimi");
            ev.setAlertType(EscalationService.TYPE_DBQUERY_THRESHOLD);
            ev.setTeamId(7L);
            ev.setContextJson("{\"monitor_id\":11}");
            ev.setResolved(false);
            open.add(ev);
            Map<String, Object> raw = scheduler.triggerDbQueryCheck(m);
            assertThat(raw).containsEntry("status", "BREACH");
            assertThat(saved.get(4).getManual()).isTrue();
            scheduler.evaluateDbQueryNow(m, raw);
            verify(esc, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

            // Sonraki zamanlanmış (610) önceki ZAMANLANMIŞLA (500) kıyaslanır: +110 → ihlal (teyit / elle satırı taban değil).
            sweep(m);
            DbQueryCheck s4 = saved.get(5);
            assertThat(s4.getManual()).isFalse();
            assertThat(s4.getRecheck()).isFalse();
            assertThat(s4.getStatus()).isEqualTo("BREACH");
            assertThat(DbQueryEvaluator.fromJson(s4.getRuleResultsJson()).get(0).compared()).isEqualTo(110.0);
            verify(esc, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

            // 615: +5 → kural sağlandı → alarm normal kurtarmayla kapanır.
            sweep(m);
            verify(esc, times(1)).resolveMonitoringAlertsForDomain("Bekleyen sipariş değişimi",
                    EscalationService.TYPE_DBQUERY_THRESHOLD);
        }
    }

    // ── İkinci tur (2026-10-31) O-1 / O-2 — teyit zinciri (gerçek motor + gerçek MOS) ──────────────────────────────

    @Nested
    @DisplayName("ikinci tur: teyit zinciri")
    class ConfirmChain {

        AlertEventRepository alerts;
        EscalationService esc;
        MonitoringOutageService mos;
        DbQueryCheckerService real;
        final List<Integer> values = new ArrayList<>();
        final AtomicInteger next = new AtomicInteger();
        final java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();

        void engine(List<Integer> vals, int perConnection) {
            values.addAll(vals);
            DbConnectionProfileRepository profiles = mock(DbConnectionProfileRepository.class);
            DbConnectionProfile p = new DbConnectionProfile();
            p.setId(3L);
            p.setTeamId(7L);
            p.setDbType("POSTGRESQL");
            p.setHost("db.example");
            p.setPort(5432);
            p.setDatabaseName("app");
            p.setUsername("u");
            when(profiles.findById(3L)).thenReturn(Optional.of(p));
            DbQueryRunner runner = mock(DbQueryRunner.class);
            when(runner.run(any(), anyString(), anyInt(), anyInt(), any(DbQueryRunner.RunOptions.class))).thenAnswer(i -> {
                int v = values.get(Math.min(next.getAndIncrement(), values.size() - 1));
                return new DbQueryRunner.QueryResult(true, 5, v, false, List.of(new DbQueryRunner.Column("id", "INT")),
                        List.of(), false, null, null, "PostgreSQL 16");
            });
            AppSettingsService es = mock(AppSettingsService.class);
            defaults(es);
            lenient().when(es.getInt(eq(DbQueryCheckerService.KEY_PER_CONNECTION), anyInt())).thenReturn(perConnection);
            real = new DbQueryCheckerService(profiles, checkRepo, mock(SecretCipher.class), runner, es);
            ReflectionTestUtils.setField(scheduler, "dbQueryCheckerService", real);
            alerts = mock(AlertEventRepository.class);
            when(alerts.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
            esc = mock(EscalationService.class);
            mos = realMos(alerts, esc, settings, mock(com.sitemonitor.repository.NetworkOutageEventRepository.class));
            ReflectionTestUtils.setField(scheduler, "monitoringOutageService", mos);
        }

        /** Teyit denemelerini KUYRUKTA tutan yürütücü — test sırayla koşturur (zincir sürerken tarama araya girer). */
        ScheduledThreadPoolExecutor deferred() {
            return new ScheduledThreadPoolExecutor(1) {
                @Override
                public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                    queued.add(command);
                    return null;
                }
            };
        }

        @AfterEach
        void stopEngine() {
            if (real != null) real.shutdown();
        }

        @Test
        @DisplayName("O-1: bağlantı izni dolu → teyit beklemeden sonra ZAMANLANMIŞ ihlali kanıt sayar, alarm açılır; satır yazılmaz")
        void permitFullConfirmationUsesScheduledBreach() {
            engine(List.of(12), 1);
            ReflectionTestUtils.setField(real, "recheckWaitMs", 150L);   // üretimde RECHECK_WAIT_MS = 10 sn
            ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(300, new AtomicReference<>(true)));
            DbQueryMonitor m = monitor(81L, "Bekleyen kuyruk");
            m.setRulesJson("[{\"id\":\"r\",\"label\":\"Bekleyen kuyruk\",\"kind\":\"ROW_COUNT\",\"operator\":\"GT\","
                    + "\"critical\":10}]");
            m.setNextRunAt(iso(T0.minusSeconds(5)));
            DbQueryMonitor sameConnection = monitor(82L, "Aynı bağlantıdaki izleme");
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
            // Teyit adımı koşmadan hemen önce bağlantının TEK iznini başka bir izleme tutar (yük altındaki bağlantı).
            AtomicReference<DbQueryCheckerService.Reservation> held = new AtomicReference<>();
            ReflectionTestUtils.setField(mos, "confirmExecutor", new ScheduledThreadPoolExecutor(1) {
                @Override
                public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                    if (held.get() == null) held.set(real.tryReserve(sameConnection));
                    command.run();
                    return null;
                }
            });

            run();

            assertThat(held.get()).as("izin gerçekten doluydu").isNotNull();
            verify(esc, times(1)).processConfirmedOutage(eq("Bekleyen kuyruk"), eq(EscalationService.TYPE_DBQUERY_THRESHOLD),
                    anyString(), any());
            assertThat(saved).as("ölçülemeyen teyit satır yazmaz (bant dışı satır da yok)").hasSize(1);
            assertThat(saved.get(0).getRecheck()).isFalse();
            held.get().release();
        }

        @Test
        @DisplayName("O-1: kurtarma adımında izin yoksa 'skipped' kalır (kanıtsız kapanış yok); FAIL teyidinde zamanlanmış hata kanıttır")
        void recoveryStaysSkippedFailConfirmationUsesEvidence() {
            ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(300, new AtomicReference<>(true)));
            DbQueryMonitor m = monitor(84L, "Kurtarma");
            m.setSlowQueryMs(10);
            m.setNextRunAt(iso(T0.minusSeconds(5)));
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
            when(checker.check(m, false)).thenReturn(ok());
            when(checker.recheck(eq(m), any())).thenReturn(skipped());
            run();
            @SuppressWarnings({"unchecked", "rawtypes"})
            ArgumentCaptor<List<MonitoringOutageService.SweepItem>> items = ArgumentCaptor.forClass((Class) List.class);
            verify(mosMock, times(3)).handleSweepResults(anyString(), items.capture());
            MonitoringOutageService.SweepItem failUp = items.getAllValues().get(0).get(0);
            assertThat(failUp.up()).isTrue();
            assertThat(failUp.recheck().get()).as("kurtarma kanıt ister").containsEntry("status", "skipped");
            MonitoringOutageService.SweepItem slowDown = items.getAllValues().get(2).get(0);
            assertThat(slowDown.up()).as("50 ms ≥ 10 ms").isFalse();
            assertThat(slowDown.recheck().get()).as("yavaş teyidi: zamanlanmış ölçüm kanıt")
                    .containsEntry("status", "down").containsEntry("duration_ms", 50L).containsEntry("threshold_ms", 10);

            // Sorgu hatası (FAIL) teyidi ölçülemedi → zamanlanmış hata kanıt.
            DbQueryMonitor f = monitor(85L, "Hatalı sorgu");
            f.setNextRunAt(iso(T0.minusSeconds(5)));
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(f));
            when(checker.check(f, false)).thenReturn(new DbQueryCheckerService.Result("FAIL", "NONE", 30_000, null, false,
                    List.of(), List.of(), List.of(), List.of(), false, List.of(), null, false, "bağlantı kurulamadı",
                    "DB_UNREACHABLE", iso(T0), "h", 3L, null));
            when(checker.recheck(eq(f), any())).thenReturn(skipped());
            clock.addAndGet(400_000);
            run();
            verify(mosMock, times(6)).handleSweepResults(anyString(), items.capture());
            MonitoringOutageService.SweepItem failDown = items.getAllValues().get(items.getAllValues().size() - 3).get(0);
            assertThat(failDown.up()).isFalse();
            Map<String, Object> ev = failDown.recheck().get();
            assertThat(ev).containsEntry("status", "down").containsEntry("error", failDown.error());

            // Elle değerlendirmenin kalemi (zincir başlatan zamanlanmış çalıştırma yok) kanıt kullanmaz.
            List<MonitoringOutageService.SweepItem> fl = new ArrayList<>(), th = new ArrayList<>(), sl = new ArrayList<>();
            Map<String, Object> raw = new HashMap<>(Map.of("status", "FAIL", "error", "x", "failure_reason", "DB_UNREACHABLE"));
            ReflectionTestUtils.invokeMethod(scheduler, "addDbQuerySweepItems", f, raw, fl, th, sl, false);
            assertThat(fl.get(0).recheck().get()).containsEntry("status", "skipped");
        }

        @Test
        @DisplayName("O-2: aralık 60 sn, 3 teyit, %50 artış kuralı — zincir sürerken yeni zamanlanmış satır yazılsa da taban SABİT, alarm açılır")
        void changeBaselinePinnedAcrossTheChain() {
            // S1 100, S2 100, S3 200 (+%100 ihlal), teyit-1 200, S4 210 (zincir sürerken), teyit-2 210, teyit-3 215
            engine(List.of(100, 100, 200, 200, 210, 210, 215), 4);
            ReflectionTestUtils.setField(mos, "confirmExecutor", deferred());
            ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(60, new AtomicReference<>(true)));
            DbQueryMonitor m = monitor(83L, "Bekleyen artış");
            m.setRulesJson("[{\"id\":\"c\",\"label\":\"Bekleyen artış\",\"kind\":\"CHANGE\",\"operator\":\"GT\","
                    + "\"warning\":50,\"change\":{\"of\":\"ROW_COUNT\",\"mode\":\"PERCENT\",\"direction\":\"INCREASE\"}}]");
            m.setConfirmAttempts(3);
            m.setConfirmIntervalSeconds(30);
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));

            sweep(m);   // S1 100 — ilk çalıştırma
            sweep(m);   // S2 100 — değişim yok
            sweep(m);   // S3 200 — ihlal; zincir başladı (teyit-1 kuyrukta)
            assertThat(queued).hasSize(1);
            queued.poll().run();   // teyit-1: 200 vs S2 100 → ihlal (teyit-2 kuyrukta)
            assertThat(queued).hasSize(1);
            sweep(m);   // S4 210 — S3'e göre +%5: yeni zincir yok, ama SON zamanlanmış satır artık S4
            assertThat(saved.get(4).getRecheck()).isFalse();
            queued.poll().run();   // teyit-2: 210 vs S2 100 (S3 / S4 değil) → ihlal
            assertThat(queued).as("teyit-2 ihlal gördü — zincir sürüyor, teyit-3 kuyrukta").hasSize(1);
            queued.poll().run();   // teyit-3: 215 vs S2 100 → ihlal → alarm
            verify(esc, times(1)).processConfirmedOutage(eq("Bekleyen artış"), eq(EscalationService.TYPE_DBQUERY_THRESHOLD),
                    anyString(), any());
            List<DbQueryCheck> rechecks = saved.stream().filter(c -> Boolean.TRUE.equals(c.getRecheck())).toList();
            assertThat(rechecks).hasSize(3);
            assertThat(rechecks).allSatisfy(c -> assertThat(c.getStatus()).as("her teyit S2 tabanıyla ihlal").isEqualTo("BREACH"));
            assertThat(DbQueryEvaluator.fromJson(rechecks.get(1).getRuleResultsJson()).get(0).passed()).isFalse();
        }

        void sweep(DbQueryMonitor m) {
            m.setNextRunAt(iso(Instant.ofEpochMilli(clock.get()).minusSeconds(1)));
            run();
            clock.addAndGet(60_000);
        }
    }

    // ── İkinci tur D-3 / D-6 — satır belleği ve atlama sayacı ──────────────────────────────────────────────────

    @Test
    @DisplayName("D-3: satır belleğini YALNIZ zamanlanmış çalıştırma değiştirir — teyit / elle kontrolün sağlıklı sonucu silmez")
    void onlyScheduledRunTouchesRowMemory() {
        DbQueryAlertRowsService rows = mock(DbQueryAlertRowsService.class);
        ReflectionTestUtils.setField(scheduler, "dbQueryAlertRows", rows);
        DbQueryMonitor m = monitor(91L, "Bellek");
        DbQueryCheckerService.Result b = breach("Bekleyen", "12 satır");
        when(checker.check(m, false)).thenReturn(b);
        when(checker.check(m, true)).thenReturn(ok());
        when(checker.recheck(eq(m), any())).thenReturn(ok());
        scheduler.recheckDbQuery(m, DbQueryCheckerService.Run.SCHEDULED);
        scheduler.recheckDbQuery(m, DbQueryCheckerService.Run.RECHECK, null);
        scheduler.triggerDbQueryCheck(m);
        verify(rows, times(1)).remember(91L, b);
        verify(rows, never()).remember(eq(91L), eq(ok()));
        assertThat(saved).hasSize(3);
    }

    @Test
    @DisplayName("D-6: atlama sayacı elle başarılı kontrolde, zamanlama değişiminde ve duraklat / devamda sıfırlanır")
    void skipCountersAreClearedOutsideTheSweep() {
        @SuppressWarnings("unchecked")
        Map<Long, Integer> retry = (Map<Long, Integer>) ReflectionTestUtils.getField(scheduler, "dbQueryRetry");
        DbQueryMonitor m = monitor(92L, "Sayaç");
        m.setSkippedRuns(3);
        m.setSkippedSince(iso(T0.minusSeconds(900)));
        retry.put(92L, 2);
        when(checker.check(m, true)).thenReturn(ok());
        scheduler.triggerDbQueryCheck(m);
        verify(monitorRepo).clearDbQuerySkipped(92L);
        assertThat(m.getSkippedRuns()).isZero();
        assertThat(m.getSkippedSince()).isNull();
        assertThat(retry).as("atlanan zamanlanmış dönem yine denenir (elle kontrol alarm açmaz)").containsKey(92L);

        // Çalışamayan elle kontrol (izin yok) sayacı sıfırlamaz.
        DbQueryMonitor busy = monitor(93L, "Meşgul");
        busy.setSkippedRuns(2);
        when(checker.check(busy, true)).thenReturn(skipped());
        scheduler.triggerDbQueryCheck(busy);
        verify(monitorRepo, never()).clearDbQuerySkipped(93L);

        scheduler.dbQueryScheduleChanged(94L);
        verify(monitorRepo).clearDbQuerySkipped(94L);

        retry.put(95L, 1);
        scheduler.dbQueryActiveChanged(95L);
        verify(monitorRepo).clearDbQuerySkipped(95L);
        assertThat(retry).doesNotContainKey(95L);

        when(monitorRepo.clearDbQuerySkipped(96L)).thenThrow(new IllegalStateException("db"));
        scheduler.dbQueryActiveChanged(96L);   // fırlatmaz
    }

    // ── Y2 — eşik hatası kodlu: "timeout" sözcüklü kurallar toplu kesinti bastırması AÇMAZ ──────────────────────────

    @Test
    @DisplayName("Y2: 'timeout' / 'connection refused' adlı 3 kural ihlali → bastırma YOK, üç alarm teyitle açılır")
    void timeoutNamedRulesDoNotSuppress() {
        AlertEventRepository alerts = mock(AlertEventRepository.class);
        when(alerts.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        EscalationService esc = mock(EscalationService.class);
        com.sitemonitor.repository.NetworkOutageEventRepository outages =
                mock(com.sitemonitor.repository.NetworkOutageEventRepository.class);
        MonitoringOutageService mos = realMos(alerts, esc, settings, outages);
        ReflectionTestUtils.setField(scheduler, "monitoringOutageService", mos);
        ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(300, new AtomicReference<>(true)));

        DbQueryMonitor a = monitor(21L, "Ödeme timeout sayısı");
        DbQueryMonitor b = monitor(22L, "SMS gateway timeout hataları");
        DbQueryMonitor c = monitor(23L, "Bağlantı reddi");
        for (DbQueryMonitor m : List.of(a, b, c)) m.setNextRunAt(iso(T0.minusSeconds(5)));
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(a, b, c));
        when(checker.check(a, false)).thenReturn(breach("Ödeme timeout sayısı", "12 satır (timed out)"));
        when(checker.check(b, false)).thenReturn(breach("SMS gateway timeout hataları", "12 satır"));
        when(checker.check(c, false)).thenReturn(breach("Bağlantı reddi (connection refused)", "12 satır"));
        when(checker.recheck(eq(a), any())).thenReturn(breach("Ödeme timeout sayısı", "12 satır (timed out)"));
        when(checker.recheck(eq(b), any())).thenReturn(breach("SMS gateway timeout hataları", "12 satır"));
        when(checker.recheck(eq(c), any())).thenReturn(breach("Bağlantı reddi (connection refused)", "12 satır"));

        run();

        assertThat(mos.outageSuppressed(EscalationService.TYPE_DBQUERY_THRESHOLD)).isFalse();
        verify(outages, never()).save(any());
        verify(esc, times(3)).processConfirmedOutage(anyString(), eq(EscalationService.TYPE_DBQUERY_THRESHOLD),
                anyString(), any());
    }

    @Test
    @DisplayName("Y2: eşik kaleminin hatası [DB_THRESHOLD] — sınıfı CONFIG (ağ değil); FAIL kodlu, SLOW hatasız")
    void itemErrorsAreClassifiedByCode() {
        ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(300, new AtomicReference<>(true)));
        DbQueryMonitor m = monitor(24L, "Kuyruk timeout");
        m.setSlowQueryMs(10);
        m.setNextRunAt(iso(T0.minusSeconds(5)));
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(checker.check(m, false)).thenReturn(breach("Kuyruk timeout (connection reset)", "12 satır"));
        run();
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<MonitoringOutageService.SweepItem>> items = ArgumentCaptor.forClass((Class) List.class);
        verify(mosMock, times(3)).handleSweepResults(type.capture(), items.capture());
        MonitoringOutageService.SweepItem th = items.getAllValues().get(1).get(0);
        assertThat(th.up()).isFalse();
        assertThat(th.error()).startsWith("[" + DbQueryAlarmText.THRESHOLD_CODE + "]");
        assertThat(MonitoringOutageService.classifyError(th.error(), th.domain())).isEqualTo(MonitoringOutageService.ERROR_CONFIG);
        assertThat(MonitoringOutageService.isOutageClass(th.error(), th.domain())).isFalse();
        MonitoringOutageService.SweepItem slow = items.getAllValues().get(2).get(0);
        assertThat(slow.up()).isFalse();
        assertThat(slow.error()).isNull();
        // teyit yeniden ölçümü de kodlu "down" döner
        when(checker.recheck(eq(m), any())).thenReturn(breach("Kuyruk timeout (connection reset)", "12 satır"));
        Map<String, Object> r = th.recheck().get();
        assertThat(r).containsEntry("status", "down");
        assertThat(String.valueOf(r.get("error"))).startsWith("[DB_THRESHOLD]");
    }

    // ── D3 — değerlendirilemeyen kural ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D3: kolon yok / önceki okunamadı → eşik kalemi YOK (ne açılır ne kanıtsız kapanır); teyit yeniden ölçümü 'skipped'")
    void inconclusiveRulesProduceNoThresholdItem() {
        ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(300, new AtomicReference<>(true)));
        DbQueryMonitor m = monitor(31L, "Kolonu silinmiş");
        m.setNextRunAt(iso(T0.minusSeconds(5)));
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        DbQueryCheckerService.Result missing = new DbQueryCheckerService.Result("OK", "NONE", 50, 1, false, List.of(),
                List.of(), List.of(), List.of(), false, List.of(new DbQueryEvaluator.RuleResult("r1", "Gecikme", "COLUMN_VALUE",
                "—", "≤ 10", "NONE", true, false, DbQueryEvaluator.NOTE_COLUMN_MISSING, null, null)), 1.0, false, null, null,
                iso(T0), "h", 3L, null);
        when(checker.check(m, false)).thenReturn(missing);
        when(checker.recheck(eq(m), any())).thenReturn(missing);
        run();
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<MonitoringOutageService.SweepItem>> items = ArgumentCaptor.forClass((Class) List.class);
        verify(mosMock, times(3)).handleSweepResults(type.capture(), items.capture());
        assertThat(items.getAllValues().get(0)).hasSize(1);   // sorgu çalıştı → FAIL kalemi up
        assertThat(items.getAllValues().get(0).get(0).up()).isTrue();
        assertThat(items.getAllValues().get(1)).as("eşik kararı bilinmiyor").isEmpty();
    }

    // ── O1 — izin ayırma yalnız tarama iş parçacığında ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O1: izin hemen ayrılamazsa tarama bekler ve bırakılınca çalıştırır; süre dolunca ATLAR (sayaç yazılır)")
    void admissionWaitsThenSkips() {
        ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(300, new AtomicReference<>(true)));
        DbQueryMonitor m = monitor(41L, "İzin bekleyen");
        m.setNextRunAt(iso(T0.minusSeconds(5)));
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(checker.check(m, false)).thenReturn(ok());
        AtomicInteger calls = new AtomicInteger();
        when(checker.tryReserve(m)).thenAnswer(i -> calls.incrementAndGet() < 3 ? null
                : (DbQueryCheckerService.Reservation) () -> { });
        run();
        assertThat(saved).hasSize(1);
        verify(checker, atLeastOnce()).awaitRelease(anyLong());

        // İzin hiç yok: süre dolunca ATLANIR — satır yok, ardışık atlama yazılır.
        ReflectionTestUtils.setField(scheduler, "dbQueryAdmissionWaitMs", 50L);
        doAnswer(i -> { Thread.sleep(5); return null; }).when(checker).awaitRelease(anyLong());
        when(checker.tryReserve(m)).thenReturn(null);
        m.setNextRunAt(iso(T0.minusSeconds(5)));
        clock.addAndGet(400_000);
        run();
        assertThat(saved).hasSize(1);
        verify(monitorRepo).markDbQuerySkipped(eq(41L), anyString());
        assertThat(m.getSkippedRuns()).isEqualTo(1);
        assertThat(m.getSkippedSince()).isNotNull();

        // Sonraki çalıştırma gerçekleşince sayaç sıfırlanır.
        when(checker.tryReserve(m)).thenReturn(() -> { });
        clock.addAndGet(10_000);
        run();   // yeniden dene (izinli an)
        assertThat(saved).hasSize(2);
        verify(monitorRepo).clearDbQuerySkipped(41L);
        assertThat(m.getSkippedRuns()).isZero();
        assertThat(m.getSkippedSince()).isNull();
    }

    @Test
    @DisplayName("O1: çalıştırma motorun havuzunda — tarama certCheckExecutor'a iş GÖNDERMEZ")
    void sweepDoesNotUseSharedExecutor() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor shared =
                mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
        ReflectionTestUtils.setField(scheduler, "certCheckExecutor", shared);
        ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(300, new AtomicReference<>(true)));
        DbQueryMonitor m = monitor(42L, "Ayrı havuz");
        m.setNextRunAt(iso(T0.minusSeconds(5)));
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(checker.check(m, false)).thenReturn(ok());
        run();
        verify(shared, never()).execute(any(Runnable.class));
        verify(checker).runReserved(eq(m), any(), any());
    }

    // ── O2 — yeniden deneme yalnız izinli anda ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O2: atlanan çalıştırma yasak pencerede / tatilde YENİDEN DENENMEZ; duraklatılan izlemenin girişi düşer")
    void retryRespectsWindowAndPause() {
        AtomicReference<Boolean> allowed = new AtomicReference<>(true);
        ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(3600, allowed));
        DbQueryMonitor m = monitor(51L, "Yedekleme saati");
        m.setNextRunAt(iso(T0.minusSeconds(5)));
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(checker.check(m, false)).thenReturn(skipped(), ok());
        run();   // atlandı → yeniden dene girişi
        assertThat(saved).isEmpty();
        @SuppressWarnings("unchecked")
        Map<Long, Integer> retry = (Map<Long, Integer>) ReflectionTestUtils.getField(scheduler, "dbQueryRetry");
        assertThat(retry).containsKey(51L);

        allowed.set(false);   // yasak pencere başladı
        clock.addAndGet(30_000);
        run();
        assertThat(saved).as("yasak pencerede sorgu çalışmaz").isEmpty();
        assertThat(retry).doesNotContainKey(51L);

        // Duraklatılan izlemenin girişi tarama listesinde yoksa düşer.
        allowed.set(true);
        retry.put(52L, 3);
        run();
        assertThat(retry).doesNotContainKey(52L);
    }

    // ── O6 — aralık ızgarası ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O6: aralık kipinde sonraki vade ızgaradan (plan + k × aralık) — gecikme taşınmaz, birden çok aralık geç kalınca ileri atlanır")
    void intervalGridNoDrift() {
        SchedulerService.DbQueryScheduling sch = fake(60, new AtomicReference<>(true));
        DbQuerySchedule.Spec spec = new DbQuerySchedule.Spec("INTERVAL", 60, null, List.of(), List.of(), false, List.of());
        Instant planned = T0;
        var p = SchedulerService.dbQueryPlan(sch, spec, iso(planned), T0.plusSeconds(50), Set.of());
        assertThat(p.run()).isTrue();
        assertThat(p.next()).as("50 sn geç başladı → sonraki yine plan + 60 sn").isEqualTo(T0.plusSeconds(60));
        p = SchedulerService.dbQueryPlan(sch, spec, iso(planned), T0.plusSeconds(130), Set.of());
        assertThat(p.next()).as("iki aralık geç → T+120 atlanır, T+180").isEqualTo(T0.plusSeconds(180));

        // Gerçek takvimle: 5 dk aralık, 09:00:00 planı 09:00:40'ta koştu → sonraki 09:05:00.
        DbQueryMonitor m = new DbQueryMonitor();
        m.setScheduleMode("INTERVAL");
        m.setIntervalSeconds(300);
        DbQuerySchedule.Spec real = DbQuerySchedule.of(m);
        p = SchedulerService.dbQueryPlan(SchedulerService.DBQUERY_SCHEDULE, real, iso(T0), T0.plusSeconds(40), Set.of());
        assertThat(p.next()).isEqualTo(T0.plusSeconds(300));
    }

    // ── O3 / K6 — zamanlama ve tatil değişimi (gerçek küme zamanlama tablosu, H2) ─────────────────────────────────────

    @Nested
    @DisplayName("O3: küme vadesi")
    class ClusterKey {

        ClusterScheduleService cluster;
        JdbcTemplate jdbc;
        final AtomicReference<Set<LocalDate>> holidays = new AtomicReference<>(Set.of());

        @BeforeEach
        void cluster() {
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    "jdbc:h2:mem:dbq-o3-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(ds);
            jdbc.execute(ClusterScheduleService.DDL);
            cluster = new ClusterScheduleService(jdbc, "pod-a");
            ReflectionTestUtils.setField(scheduler, "clusterSchedule", cluster);
            HolidayCalendarService cal = mock(HolidayCalendarService.class);
            when(cal.days()).thenAnswer(i -> holidays.get());
            ReflectionTestUtils.setField(scheduler, "holidayCalendarService", cal);
            ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", SchedulerService.DBQUERY_SCHEDULE);
        }

        DbQueryMonitor cron(long id, String expr, boolean skipHolidays) {
            DbQueryMonitor m = monitor(id, "Cron " + id);
            m.setScheduleMode("CRON");
            m.setCronExpression(expr);
            m.setSkipHolidays(skipHolidays);
            m.setNextRunAt(null);
            when(checker.check(m, false)).thenReturn(ok());
            return m;
        }

        /** İstanbul yerel saati (UTC+3). */
        void at(String istanbulLocal) {
            clock.set(java.time.LocalDateTime.parse(istanbulLocal).atZone(DbQuerySchedule.ZONE).toInstant().toEpochMilli());
        }

        Long storedDue(long id) {
            List<Long> l = jdbc.queryForList("SELECT next_due_at FROM monitor_check_schedule WHERE monitor_key = ?", Long.class,
                    "dbquery:" + id);
            return l.isEmpty() ? null : l.get(0);
        }

        @Test
        @DisplayName("günlük 18:00 → 09:00 değişince (dbQueryScheduleChanged) O GÜNÜN 09:00'ı çalışır")
        void cronMovedEarlierRunsToday() {
            DbQueryMonitor m = cron(61L, "0 18 * * *", false);
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
            at("2026-11-02T17:00:00");
            run();
            assertThat(m.getNextRunAt()).isEqualTo("2026-11-02T15:00:00");
            at("2026-11-02T18:00:10");
            run();
            verify(checker, times(1)).check(m, false);
            assertThat(storedDue(61L)).as("küme vadesi yarın 18:00").isNotNull();

            at("2026-11-03T08:00:00");
            m.setCronExpression("0 9 * * *");
            scheduler.dbQueryScheduleChanged(61L);
            assertThat(m.getNextRunAt()).isNull();
            assertThat(storedDue(61L)).as("küme vadesi sıfırlandı").isNull();
            at("2026-11-03T08:00:30");
            run();
            assertThat(m.getNextRunAt()).isEqualTo("2026-11-03T06:00:00");
            at("2026-11-03T09:00:10");
            run();
            verify(checker, times(2)).check(m, false);
        }

        @Test
        @DisplayName("eski denetleyici yalnız planı silse de ('first' yolu) küme vadesi sıfırlanır — 09:00 çalışır")
        void firstPathResetsClusterKey() {
            DbQueryMonitor m = cron(62L, "0 18 * * *", false);
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
            at("2026-11-02T18:00:10");
            m.setNextRunAt("2026-11-02T15:00:00");
            run();
            verify(checker, times(1)).check(m, false);
            m.setCronExpression("0 9 * * *");
            m.setNextRunAt(null);
            at("2026-11-03T08:00:30");
            run();
            at("2026-11-03T09:00:10");
            run();
            verify(checker, times(2)).check(m, false);
        }

        @Test
        @DisplayName("tatilden silinen gün plandan önceyse yeniden planlanır (replanned + küme vadesi) ve çalışır")
        void holidayRemovedIsReplanned() {
            DbQueryMonitor m = cron(63L, "0 9 * * *", true);
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
            holidays.set(Set.of(LocalDate.parse("2026-11-03")));
            at("2026-11-02T08:00:00");
            run();
            at("2026-11-02T09:00:10");
            run();
            verify(checker, times(1)).check(m, false);
            assertThat(m.getNextRunAt()).as("salı tatil → çarşamba").isEqualTo("2026-11-04T06:00:00");

            holidays.set(Set.of());
            at("2026-11-02T12:00:00");
            run();
            assertThat(m.getNextRunAt()).as("salı yeniden planlandı").isEqualTo("2026-11-03T06:00:00");
            at("2026-11-03T09:00:10");
            run();
            verify(checker, times(2)).check(m, false);
        }

        DbQueryCheckerService.Result okAt(String checkedAt) {
            return new DbQueryCheckerService.Result("OK", "NONE", 50, 1, false, List.of(), List.of(), List.of(), List.of(),
                    false, List.of(), 1.0, false, null, null, checkedAt, "h", 3L, null);
        }

        @Test
        @DisplayName("D-1: tetikten 10 sn önce sıfırlanan CRON planı, sonraki tıkta (09:00:15) o tetiği KAYBETMEZ — bir kez çalışır")
        void cronResetJustBeforeTriggerStillRuns() {
            DbQueryMonitor m = cron(67L, "0 9 * * *", false);
            when(checker.check(m, false)).thenReturn(okAt("2026-11-03T06:00:16"));
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
            at("2026-11-03T08:59:30");
            run();
            assertThat(m.getNextRunAt()).isEqualTo("2026-11-03T06:00:00");
            at("2026-11-03T08:59:50");
            scheduler.dbQueryScheduleChanged(67L);
            assertThat(m.getNextRunAt()).isNull();
            at("2026-11-03T09:00:15");
            run();
            verify(checker, times(1)).check(m, false);
            assertThat(m.getNextRunAt()).as("sonraki tetik yarın").isEqualTo("2026-11-04T06:00:00");
            at("2026-11-03T09:00:45");
            run();
            verify(checker, times(1)).check(m, false);
        }

        @Test
        @DisplayName("D-1: tetik ZATEN koştuktan sonra sıfırlanan plan aynı tetiği yeniden koşmaz (geri bakış son çalıştırmadan başlar)")
        void cronResetAfterTriggerRanDoesNotRunTwice() {
            DbQueryMonitor m = cron(68L, "0 9 * * *", false);
            when(checker.check(m, false)).thenReturn(okAt("2026-11-03T06:00:06"));
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
            m.setNextRunAt("2026-11-03T06:00:00");
            at("2026-11-03T09:00:05");
            run();
            verify(checker, times(1)).check(m, false);
            at("2026-11-03T09:00:20");
            scheduler.dbQueryScheduleChanged(68L);
            at("2026-11-03T09:00:35");
            run();
            verify(checker, times(1)).check(m, false);
            assertThat(m.getNextRunAt()).isEqualTo("2026-11-04T06:00:00");

            // Son çalıştırma okunamazsa geri bakış YAPILMAZ (çift çalıştırma riskine girilmez).
            DbQueryMonitor n = cron(70L, "0 9 * * *", false);
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(n));
            when(checkRepo.findLatestScheduled(70L)).thenThrow(new IllegalStateException("db"));
            at("2026-11-04T09:00:15");
            run();
            verify(checker, never()).check(n, false);
            assertThat(n.getNextRunAt()).isEqualTo("2026-11-05T06:00:00");
        }

        @Test
        @DisplayName("D-6: kaçırılan CRON tetiği (duraklat / devam) eski atlama sayacını sıfırlar")
        void missedTriggerClearsSkipCounters() {
            DbQueryMonitor m = cron(69L, "0 9 * * *", false);
            m.setNextRunAt("2026-11-01T06:00:00");
            m.setSkippedRuns(4);
            m.setSkippedSince("2026-11-01T06:00:30");
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
            at("2026-11-03T12:00:00");
            run();
            verify(checker, never()).check(m, false);
            verify(monitorRepo).clearDbQuerySkipped(69L);
            assertThat(m.getSkippedRuns()).isZero();
            assertThat(m.getNextRunAt()).isEqualTo("2026-11-04T06:00:00");
        }

        @Test
        @DisplayName("dbQueryCalendarChanged: tatile bakan CRON izlemesinin planı silinir, ARALIK izlemesininki silinmez")
        void calendarChangedResetsCronOnly() {
            DbQueryMonitor c = cron(64L, "0 9 * * *", true);
            DbQueryMonitor i = monitor(65L, "Aralık");
            i.setScheduleMode("INTERVAL");
            i.setIntervalSeconds(300);
            i.setSkipHolidays(true);
            i.setNextRunAt("2026-11-04T06:00:00");
            c.setNextRunAt("2026-11-04T06:00:00");
            DbQueryMonitor other = cron(66L, "0 9 * * *", false);
            other.setNextRunAt("2026-11-03T06:00:00");
            when(monitorRepo.findByActiveTrue()).thenReturn(List.of(c, i, other));
            scheduler.dbQueryCalendarChanged();
            assertThat(c.getNextRunAt()).isNull();
            assertThat(i.getNextRunAt()).isEqualTo("2026-11-04T06:00:00");
            assertThat(other.getNextRunAt()).isEqualTo("2026-11-03T06:00:00");
            verify(monitorRepo).updateNextRunAt(64L, null);
            verify(monitorRepo, never()).updateNextRunAt(eq(65L), isNull());
            verify(monitorRepo, never()).updateNextRunAt(eq(66L), isNull());
        }
    }

    // ── O5 — gecikmiş görünürlüğü ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("O5: 10 dk'dan uzun süren ardışık atlama 'gecikmiş'; plan ileride olsa da; atlama yoksa plan kuralı")
    void skippedTooLongIsStale() {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setNextRunAt(iso(T0.plusSeconds(3600)));
        assertThat(MonitoringOverviewService.isDbQueryStale(m, T0)).isFalse();
        m.setSkippedSince(iso(T0.minusSeconds(300)));
        assertThat(MonitoringOverviewService.isDbQueryStale(m, T0)).as("5 dk — henüz değil").isFalse();
        m.setSkippedSince(iso(T0.minusSeconds(700)));
        assertThat(MonitoringOverviewService.isDbQueryStale(m, T0)).isTrue();
        m.setSkippedSince(null);
        m.setNextRunAt(iso(T0.minusSeconds(400)));
        assertThat(MonitoringOverviewService.isDbQueryStale(m, T0)).as("plan kuralı aynen").isTrue();
        assertThat(MonitoringOverviewService.isDbQueryStale((DbQueryMonitor) null, T0)).isFalse();
    }
}
