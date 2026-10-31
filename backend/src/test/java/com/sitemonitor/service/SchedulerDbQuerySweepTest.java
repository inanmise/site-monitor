package com.sitemonitor.service;

import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.service.dbquery.DbQueryAlertRowsService;
import com.sitemonitor.service.dbquery.DbQueryCheckerService;
import com.sitemonitor.service.dbquery.DbQueryEvaluator;
import com.sitemonitor.service.dbquery.DbQuerySchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Veritabanı sorgu izlemesi (11. tür, 2026-10-31) — tarama, zamanlama kararı, alarm kalemleri ve elle değerlendirme.
 * Motor ({@link DbQueryCheckerService}) ve zamanlama hesabı ({@link SchedulerService.DbQueryScheduling}) sahtedir; bu test
 * yalnız tarama katmanının sözleşmesini sınar.
 */
class SchedulerDbQuerySweepTest {

    static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

    SchedulerService scheduler;
    DbQueryCheckerService checker;
    DbQueryMonitorRepository monitorRepo;
    DbQueryCheckRepository checkRepo;
    DbQueryAlertRowsService alertRows;
    MonitoringOutageService mos;
    ActivityLogService activity;
    final List<DbQueryCheck> saved = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Constructor<?> c = Arrays.stream(SchedulerService.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = mock(pt[i]);
        scheduler = (SchedulerService) c.newInstance(args);
        AppSettingsService settings = (AppSettingsService) ReflectionTestUtils.getField(scheduler, "appSettings");
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        mos = (MonitoringOutageService) ReflectionTestUtils.getField(scheduler, "monitoringOutageService");
        activity = (ActivityLogService) ReflectionTestUtils.getField(scheduler, "activityLog");

        ThreadPoolTaskExecutor exec = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(exec).execute(any(Runnable.class));
        ReflectionTestUtils.setField(scheduler, "certCheckExecutor", exec);
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
        ReflectionTestUtils.setField(scheduler, "scheduleClock", (java.util.function.LongSupplier) NOW::toEpochMilli);

        checker = mock(DbQueryCheckerService.class);
        monitorRepo = mock(DbQueryMonitorRepository.class);
        checkRepo = mock(DbQueryCheckRepository.class);
        alertRows = mock(DbQueryAlertRowsService.class);
        lenient().when(checkRepo.save(any(DbQueryCheck.class))).thenAnswer(i -> {
            DbQueryCheck x = i.getArgument(0);
            x.setId(100L + saved.size());
            saved.add(x);
            return x;
        });
        // Zamanlanmış tarama izni bekletmeden ayırır ve motorun havuzunda koşar (inceleme O1): sahte motorda ayırma her zaman
        // başarılı, çalıştırma eşzamanlı — sonuç testin check(m, false) taslağından.
        lenient().when(checker.tryReserve(any())).thenReturn(() -> { });
        lenient().when(checker.runReserved(any(), any(), any())).thenAnswer(inv -> {
            DbQueryMonitor mm = inv.getArgument(0);
            java.util.function.Function<DbQueryCheckerService.Result, Object> then = inv.getArgument(2);
            return java.util.concurrent.CompletableFuture.completedFuture(then.apply(checker.check(mm, false)));
        });
        ReflectionTestUtils.setField(scheduler, "dbQueryCheckerService", checker);
        ReflectionTestUtils.setField(scheduler, "dbQueryMonitorRepo", monitorRepo);
        ReflectionTestUtils.setField(scheduler, "dbQueryCheckRepo", checkRepo);
        ReflectionTestUtils.setField(scheduler, "dbQueryAlertRows", alertRows);
    }

    // ── Zamanlama kararı (saf) ──────────────────────────────────────────────────────────────────────────────────────

    /** Sahte zamanlama: sonraki çalışma {@code next}, izin penceresi {@code allowed}. */
    static SchedulerService.DbQueryScheduling fake(Instant next, boolean allowed) {
        return new SchedulerService.DbQueryScheduling() {
            @Override public DbQuerySchedule.Spec specOf(DbQueryMonitor m) { return spec("INTERVAL"); }
            @Override public Instant nextAfter(DbQuerySchedule.Spec s, Instant after, Set<LocalDate> h) { return next; }
            @Override public boolean allowedAt(DbQuerySchedule.Spec s, Instant t, Set<LocalDate> h) { return allowed; }
        };
    }

    static DbQuerySchedule.Spec spec(String mode) {
        return new DbQuerySchedule.Spec(mode, 300, "CRON".equals(mode) ? "0 * * * *" : null, List.of(), List.of(), false, List.of());
    }

    /** Zamanlayıcının yazdığı biçim (UTC, saniye, son eksiz). */
    static String iso(Instant t) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(t);
    }

    @Test
    @DisplayName("plan: ilk görüşte INTERVAL hemen çalışır, CRON çalışmaz (yalnız vadeyi yazar), pencere dışındaki INTERVAL bekler")
    void plan_firstSight() {
        Instant next = NOW.plusSeconds(300);
        var p = SchedulerService.dbQueryPlan(fake(next, true), spec("INTERVAL"), null, NOW, Set.of());
        assertThat(p.run()).isTrue();
        assertThat(p.write()).isTrue();
        assertThat(p.next()).isEqualTo(next);
        assertThat(p.reason()).isEqualTo("first");

        p = SchedulerService.dbQueryPlan(fake(next, true), spec("CRON"), null, NOW, Set.of());
        assertThat(p.run()).as("cron tetiği geçmeden çalışmaz").isFalse();
        assertThat(p.write()).isTrue();
        assertThat(p.next()).isEqualTo(next);

        p = SchedulerService.dbQueryPlan(fake(next, false), spec("INTERVAL"), null, NOW, Set.of());
        assertThat(p.run()).isFalse();
        assertThat(p.write()).isTrue();
    }

    @Test
    @DisplayName("plan: vadesi gelmemiş → yazma yok; zamanlama kısaltıldı → yeniden planlanır; vade yok → never")
    void plan_notYetReplannedNever() {
        Instant planned = NOW.plusSeconds(3600);
        var p = SchedulerService.dbQueryPlan(fake(NOW.plusSeconds(3550), true), spec("INTERVAL"), iso(planned), NOW, Set.of());
        assertThat(p.run()).isFalse();
        assertThat(p.write()).as("60 sn tolerans içinde — yazma yok").isFalse();
        assertThat(p.reason()).isEqualTo("not_yet");

        p = SchedulerService.dbQueryPlan(fake(NOW.plusSeconds(300), true), spec("INTERVAL"), iso(planned), NOW, Set.of());
        assertThat(p.run()).isFalse();
        assertThat(p.write()).isTrue();
        assertThat(p.next()).isEqualTo(NOW.plusSeconds(300));
        assertThat(p.reason()).isEqualTo("replanned");

        p = SchedulerService.dbQueryPlan(fake(null, true), spec("INTERVAL"), iso(planned), NOW, Set.of());
        assertThat(p.run()).isFalse();
        assertThat(p.next()).isNull();
        assertThat(p.reason()).isEqualTo("never");
    }

    @Test
    @DisplayName("plan: vadesi geçmiş → çalışır; CRON 15 dk'dan fazla geciktiyse kaçırılmış (geç çalışmaz); pencere dışı → çalışmaz")
    void plan_dueMissedOutside() {
        Instant next = NOW.plusSeconds(3600);
        var p = SchedulerService.dbQueryPlan(fake(next, true), spec("INTERVAL"), iso(NOW.minusSeconds(5)), NOW, Set.of());
        assertThat(p.run()).isTrue();
        assertThat(p.next()).isEqualTo(next);
        assertThat(p.reason()).isEqualTo("due");

        p = SchedulerService.dbQueryPlan(fake(next, true), spec("CRON"), iso(NOW.minusSeconds(10 * 60)), NOW, Set.of());
        assertThat(p.run()).as("10 dk geç cron hâlâ çalışır").isTrue();

        p = SchedulerService.dbQueryPlan(fake(next, true), spec("CRON"), iso(NOW.minusSeconds(20 * 60)), NOW, Set.of());
        assertThat(p.run()).isFalse();
        assertThat(p.reason()).isEqualTo("missed");
        assertThat(p.next()).isEqualTo(next);

        p = SchedulerService.dbQueryPlan(fake(next, true), spec("INTERVAL"), iso(NOW.minusSeconds(20 * 60)), NOW, Set.of());
        assertThat(p.run()).as("aralık kipinde gecikme kaçırma sayılmaz").isTrue();

        p = SchedulerService.dbQueryPlan(fake(next, false), spec("INTERVAL"), iso(NOW.minusSeconds(5)), NOW, Set.of());
        assertThat(p.run()).isFalse();
        assertThat(p.reason()).isEqualTo("outside_window");
        assertThat(p.write()).isTrue();
    }

    @Test
    @DisplayName("plan: bozuk vade metni ilk görüş gibi ele alınır; zamanlama hesabı fırlatırsa çalışmaz (asla döngü)")
    void plan_badInputsSafe() {
        var p = SchedulerService.dbQueryPlan(fake(NOW.plusSeconds(300), true), spec("INTERVAL"), "bozuk", NOW, Set.of());
        assertThat(p.reason()).isEqualTo("first");
        SchedulerService.DbQueryScheduling throwing = new SchedulerService.DbQueryScheduling() {
            @Override public DbQuerySchedule.Spec specOf(DbQueryMonitor m) { throw new IllegalStateException(); }
            @Override public Instant nextAfter(DbQuerySchedule.Spec s, Instant a, Set<LocalDate> h) { throw new IllegalStateException(); }
            @Override public boolean allowedAt(DbQuerySchedule.Spec s, Instant t, Set<LocalDate> h) { throw new IllegalStateException(); }
        };
        p = SchedulerService.dbQueryPlan(throwing, spec("INTERVAL"), iso(NOW.minusSeconds(5)), NOW, Set.of());
        assertThat(p.run()).isFalse();
    }

    // ── Tarama ────────────────────────────────────────────────────────────────────────────────────────────────────

    static DbQueryMonitor monitor(long id, String name) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(id);
        m.setName(name);
        m.setActive(true);
        m.setTeamId(7L);
        m.setProfileId(3L);
        m.setPurpose("Bekleyen siparişler birikmesin");
        m.setSqlText("SELECT id, tutar FROM siparis WHERE durum = 'BEKLIYOR'");
        m.setNextRunAt(iso(NOW.minusSeconds(5)));
        return m;
    }

    static DbQueryCheckerService.Result result(String status, String level, long ms, Integer rows, String error, String reason) {
        List<DbQueryEvaluator.RuleResult> rules = "FAIL".equals(status) ? List.of()
                : List.of(new DbQueryEvaluator.RuleResult("r1", "Bekleyen sipariş", "ROW_COUNT", String.valueOf(rows),
                        "≤ 10", "CRITICAL", !"BREACH".equals(status), true, null, rows == null ? null : rows.doubleValue(),
                        rows == null ? null : rows.doubleValue()));
        return new DbQueryCheckerService.Result(status, level, ms, rows, false, List.of(),
                List.of("id", "tutar"), List.of(List.of("1", "100"), List.of("2", "250")), List.of("tutar"), false,
                rules, rows == null ? null : rows.doubleValue(), false, error, reason, iso(NOW), "abc123", 3L, "PostgreSQL 16");
    }

    void schedule(Instant next, boolean allowed) {
        ReflectionTestUtils.setField(scheduler, "dbQueryScheduling", fake(next, allowed));
    }

    @SuppressWarnings("unchecked")
    Map<String, List<MonitoringOutageService.SweepItem>> runSweep() {
        ReflectionTestUtils.invokeMethod(scheduler, "runDbQueryChecksLocked");
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List> items = ArgumentCaptor.forClass(List.class);
        verify(mos, times(3)).handleSweepResults(type.capture(), items.capture());
        Map<String, List<MonitoringOutageService.SweepItem>> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < type.getAllValues().size(); i++) out.put(type.getAllValues().get(i), items.getAllValues().get(i));
        return out;
    }

    @Test
    @DisplayName("tarama: kural ihlali → eşik kalemi kuralın seviyesiyle DOWN, sorgu hatası kalemi UP; satır verisi bağlama GİRMEZ")
    void sweep_breachItemsWithRuleLevel_noRowsInContext() {
        DbQueryMonitor m = monitor(11L, "Bekleyen Siparişler");
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        schedule(NOW.plusSeconds(300), true);
        when(checker.check(m, false)).thenReturn(result("BREACH", "CRITICAL", 120, 25, null, null));

        var items = runSweep();
        assertThat(items.keySet()).containsExactly(EscalationService.TYPE_DBQUERY_FAIL,
                EscalationService.TYPE_DBQUERY_THRESHOLD, EscalationService.TYPE_DBQUERY_SLOW);
        var fail = items.get(EscalationService.TYPE_DBQUERY_FAIL);
        assertThat(fail).hasSize(1);
        assertThat(fail.get(0).up()).isTrue();
        assertThat(fail.get(0).domain()).isEqualTo("Bekleyen Siparişler");

        var th = items.get(EscalationService.TYPE_DBQUERY_THRESHOLD);
        assertThat(th).hasSize(1);
        assertThat(th.get(0).up()).isFalse();
        assertThat(th.get(0).ctxExtra()).containsEntry("alert_level", "CRITICAL")
                .containsEntry("dbquery_status", "BREACH").containsEntry("row_count", 25)
                .containsEntry("purpose", "Bekleyen siparişler birikmesin").containsEntry("team_id", 7L);
        assertThat(String.valueOf(th.get(0).ctxExtra().get("rule_summary"))).contains("Bekleyen sipariş");

        // Satırlar YALNIZ alarm satırı servisinin belleğinde — alarm bağlamı / anlık görüntü / devir tablosu görmez.
        for (var list : items.values()) for (var it : list) {
            assertThat(it.ctxExtra()).doesNotContainKeys(DbQueryAlertRowsService.CTX_ROWS, "mail_rows", "rows");
            assertThat(String.valueOf(it.ctxExtra())).doesNotContain("250");
        }
        verify(alertRows).remember(eq(11L), any(DbQueryCheckerService.Result.class));

        // Kontrol satırı: ihlal kesinti değildir (ok = true); satır verisi yok; vade yazıldı.
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getOk()).isTrue();
        assertThat(saved.get(0).getStatus()).isEqualTo("BREACH");
        assertThat(saved.get(0).getManual()).isFalse();
        verify(monitorRepo).updateNextRunAt(11L, iso(NOW.plusSeconds(300)));
    }

    @Test
    @DisplayName("tarama: sorgu çalışmadı → yalnız FAIL kalemi DOWN ([KOD] hata); eşik / süre hakkında kanıt yok → kalem yok")
    void sweep_failOnlyFailItem() {
        DbQueryMonitor m = monitor(12L, "Rapor Sorgusu");
        m.setSlowQueryMs(500);
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        schedule(NOW.plusSeconds(300), true);
        when(checker.check(m, false)).thenReturn(result("FAIL", "NONE", 30_000, null, "bağlantı kurulamadı", "DB_UNREACHABLE"));

        var items = runSweep();
        var fail = items.get(EscalationService.TYPE_DBQUERY_FAIL);
        assertThat(fail).hasSize(1);
        assertThat(fail.get(0).up()).isFalse();
        assertThat(fail.get(0).error()).startsWith("[DB_UNREACHABLE]").contains("bağlantı kurulamadı");
        assertThat(items.get(EscalationService.TYPE_DBQUERY_THRESHOLD)).isEmpty();
        assertThat(items.get(EscalationService.TYPE_DBQUERY_SLOW)).isEmpty();
        assertThat(saved.get(0).getOk()).isFalse();
        assertThat(saved.get(0).getFailureReason()).isEqualTo("DB_UNREACHABLE");
    }

    @Test
    @DisplayName("tarama: yavaş sorgu eşiği aşıldıysa SLOW DOWN; eşik kapalıysa sentetik UP (asılı alarm kapanabilir)")
    void sweep_slowItem() {
        DbQueryMonitor slowM = monitor(13L, "Yavaş");
        slowM.setSlowQueryMs(1000);
        DbQueryMonitor noTh = monitor(14L, "Eşiksiz");
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(slowM, noTh));
        schedule(NOW.plusSeconds(300), true);
        when(checker.check(slowM, false)).thenReturn(result("OK", "NONE", 1500, 3, null, null));
        when(checker.check(noTh, false)).thenReturn(result("OK", "NONE", 9000, 3, null, null));

        var slow = runSweep().get(EscalationService.TYPE_DBQUERY_SLOW);
        assertThat(slow).hasSize(2);
        assertThat(slow.get(0).up()).isFalse();
        assertThat(slow.get(0).ctxExtra()).containsEntry("threshold_ms", 1000);
        assertThat(slow.get(1).up()).isTrue();
    }

    @Test
    @DisplayName("tarama: meşgul (SKIPPED) → kontrol satırı / kalem yok; sonraki tık vade beklemeden yeniden dener")
    void sweep_skippedRetriedNextTick() {
        DbQueryMonitor m = monitor(15L, "Meşgul");
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        schedule(NOW.plusSeconds(300), true);
        when(checker.check(m, false)).thenReturn(
                new DbQueryCheckerService.Result("SKIPPED", "NONE", 0, null, false, List.of(), List.of(), List.of(), List.of(),
                        false, List.of(), null, false, "eşzamanlılık sınırı", "DB_BUSY", iso(NOW), null, 3L, null),
                result("OK", "NONE", 50, 0, null, null));

        var items = runSweep();
        assertThat(items.values()).allSatisfy(l -> assertThat(l).isEmpty());
        assertThat(saved).isEmpty();
        verify(alertRows, never()).remember(anyLong(), any());

        // Vade artık gelecekte (yazıldı) — ama harcanan dönem yeniden denenir.
        m.setNextRunAt(iso(NOW.plusSeconds(300)));
        org.mockito.Mockito.clearInvocations(mos);
        var again = runSweep();
        assertThat(saved).hasSize(1);
        assertThat(again.get(EscalationService.TYPE_DBQUERY_FAIL)).hasSize(1);
    }

    @Test
    @DisplayName("tarama: vadesi gelmemiş izleme çalışmaz; pencere dışındaki izleme çalışmaz ama vadesi ileri yazılır")
    void sweep_notDueAndOutsideWindow() {
        DbQueryMonitor later = monitor(16L, "Sonra");
        later.setNextRunAt(iso(NOW.plusSeconds(600)));
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(later));
        schedule(NOW.plusSeconds(600), true);
        runSweep();
        verify(checker, never()).check(any(), anyBoolean());
        verify(monitorRepo, never()).updateNextRunAt(anyLong(), any());

        org.mockito.Mockito.clearInvocations(mos);
        DbQueryMonitor outside = monitor(17L, "Pencere dışı");
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(outside));
        schedule(NOW.plusSeconds(7200), false);
        runSweep();
        verify(checker, never()).check(any(), anyBoolean());
        verify(monitorRepo).updateNextRunAt(17L, iso(NOW.plusSeconds(7200)));
    }

    @Test
    @DisplayName("tarama: alarmlar kapalıysa (site.monitor.dbquery.alert-enabled=false) tarama hiç başlamaz")
    void sweep_disabledTypeDoesNothing() {
        AppSettingsService settings = (AppSettingsService) ReflectionTestUtils.getField(scheduler, "appSettings");
        when(settings.getBoolean(eq("site.monitor.dbquery.alert-enabled"), anyBoolean())).thenReturn(false);
        scheduler.runDbQueryChecks();
        verify(monitorRepo, never()).findByActiveTrue();
        verify(checker, never()).check(any(), anyBoolean());
    }

    // ── Elle kontrol + değerlendirme ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("elle 'Şimdi kontrol et': manual=true satır; ham sonuç SATIR İÇERMEZ; değerlendirme manual=true ile (alarm açmaz)")
    void manualCheck_rawResultAndManualEvaluation() {
        DbQueryMonitor m = monitor(18L, "Elle");
        when(checker.check(m, true)).thenReturn(result("BREACH", "WARNING", 80, 12, null, null));

        Map<String, Object> raw = scheduler.triggerDbQueryCheck(m);
        assertThat(raw).containsKeys("status", "skipped", "ok", "level", "duration_ms", "row_count", "row_count_capped",
                "metric_value", "slow", "slow_query_ms", "rule_results", "rule_summary", "error", "failure_reason",
                "checked_at", "sql_hash", "profile_id", "check_id");
        assertThat(raw).containsEntry("status", "BREACH").containsEntry("ok", true).containsEntry("level", "WARNING")
                .containsEntry("skipped", false);
        assertThat(raw).doesNotContainKeys("rows", "mail_rows", "columns", DbQueryAlertRowsService.CTX_ROWS);
        assertThat(String.valueOf(raw)).doesNotContain("250");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getManual()).isTrue();
        verify(activity).recordCheck(eq(ActivityLogService.DBQUERY), eq(18L), eq("Elle"), any(), eq(7L), eq(true),
                eq("manual"), any());

        scheduler.evaluateDbQueryNow(m, raw);
        verify(mos).handleSweepResults(eq(EscalationService.TYPE_DBQUERY_FAIL), anyList(), eq(true));
        verify(mos).handleSweepResults(eq(EscalationService.TYPE_DBQUERY_THRESHOLD), anyList(), eq(true));
        verify(mos).handleSweepResults(eq(EscalationService.TYPE_DBQUERY_SLOW), anyList(), eq(true));
        verify(mos, never()).handleSweepResults(anyString(), anyList());
    }

    @Test
    @DisplayName("elle kontrol: motor fırlatırsa SKIPPED (DB_BUSY) — satır yok, alarm yok")
    void manualCheck_engineThrowsIsSkipped() {
        DbQueryMonitor m = monitor(19L, "Bozuk motor");
        when(checker.check(m, true)).thenThrow(new IllegalStateException("x"));
        Map<String, Object> raw = scheduler.triggerDbQueryCheck(m);
        assertThat(raw).containsEntry("status", "SKIPPED").containsEntry("skipped", true).containsEntry("failure_reason", "DB_BUSY");
        assertThat(raw.get("ok")).isNull();
        assertThat(saved).isEmpty();
        scheduler.evaluateDbQueryNow(m, raw);
        ArgumentCaptor<List> items = ArgumentCaptor.forClass(List.class);
        verify(mos, times(3)).handleSweepResults(anyString(), items.capture(), eq(true));
        assertThat(items.getAllValues()).allSatisfy(l -> assertThat(l).isEmpty());
    }

    @Test
    @DisplayName("izlenmeye devam ediyor mu: DBQUERY_ öneki izlemenin etkin olmasına bakar")
    void stillMonitored() {
        DbQueryMonitor m = monitor(20L, "Etkin");
        when(monitorRepo.findById(20L)).thenReturn(java.util.Optional.of(m));
        DbQueryMonitor p = monitor(21L, "Duraklatılmış");
        p.setActive(false);
        when(monitorRepo.findById(21L)).thenReturn(java.util.Optional.of(p));
        assertThat(scheduler.isStillMonitored(new MonitoringOutageService.SweepItem(EscalationService.TYPE_DBQUERY_FAIL,
                "Etkin", "sorgu", false, null, Map.of("monitor_id", 20L), null))).isTrue();
        assertThat(scheduler.isStillMonitored(new MonitoringOutageService.SweepItem(EscalationService.TYPE_DBQUERY_THRESHOLD,
                "Duraklatılmış", "x", false, null, Map.of("monitor_id", 21L), null))).isFalse();
    }
}
