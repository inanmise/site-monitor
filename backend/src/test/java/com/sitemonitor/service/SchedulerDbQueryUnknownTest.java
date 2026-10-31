package com.sitemonitor.service;

import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.service.dbquery.DbQueryAlarmText;
import com.sitemonitor.service.dbquery.DbQueryAlertRowsService;
import com.sitemonitor.service.dbquery.DbQueryCheckerService;
import com.sitemonitor.service.dbquery.DbQueryEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * r177 (H-2): sorgu çalışıyor ama kurallar YAPILANDIRMA sorunuyla değerlendirilemiyorsa (kolon yok, sayı değil …) izleme
 * sessizce ölmesin — ardışık {@code site.monitor.dbquery.unknown-alert-runs} (3) zamanlanmış çalıştırmadan sonra
 * DBQUERY_FAIL kalemi {@code [DB_RULE_UNEVALUABLE]} ile düşer; "taban kuruluyor" notları saymaz; değerlendirilebilen
 * çalıştırma sayacı sıfırlar; 0 = kapalı.
 */
class SchedulerDbQueryUnknownTest {

    SchedulerService scheduler;
    DbQueryCheckerService checker;
    DbQueryMonitorRepository monitorRepo;
    MonitoringOutageService mos;
    AppSettingsService settings;
    final List<DbQueryCheck> saved = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Constructor<?> c = Arrays.stream(SchedulerService.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = mock(pt[i]);
        scheduler = (SchedulerService) c.newInstance(args);
        settings = (AppSettingsService) ReflectionTestUtils.getField(scheduler, "appSettings");
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        mos = (MonitoringOutageService) ReflectionTestUtils.getField(scheduler, "monitoringOutageService");
        ThreadPoolTaskExecutor exec = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(exec).execute(any(Runnable.class));
        ReflectionTestUtils.setField(scheduler, "certCheckExecutor", exec);
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
        ReflectionTestUtils.setField(scheduler, "scheduleClock",
                (java.util.function.LongSupplier) SchedulerDbQuerySweepTest.NOW::toEpochMilli);
        checker = mock(DbQueryCheckerService.class);
        monitorRepo = mock(DbQueryMonitorRepository.class);
        DbQueryCheckRepository checkRepo = mock(DbQueryCheckRepository.class);
        lenient().when(checkRepo.save(any(DbQueryCheck.class))).thenAnswer(i -> {
            DbQueryCheck x = i.getArgument(0);
            x.setId(100L + saved.size());
            saved.add(x);
            return x;
        });
        lenient().when(checker.tryReserve(any())).thenReturn(() -> { });
        lenient().when(checker.runReserved(any(), any(), any())).thenAnswer(inv -> {
            DbQueryMonitor mm = inv.getArgument(0);
            java.util.function.Function<DbQueryCheckerService.Result, Object> then = inv.getArgument(2);
            return java.util.concurrent.CompletableFuture.completedFuture(then.apply(checker.check(mm, false)));
        });
        ReflectionTestUtils.setField(scheduler, "dbQueryCheckerService", checker);
        ReflectionTestUtils.setField(scheduler, "dbQueryMonitorRepo", monitorRepo);
        ReflectionTestUtils.setField(scheduler, "dbQueryCheckRepo", checkRepo);
        ReflectionTestUtils.setField(scheduler, "dbQueryAlertRows", mock(DbQueryAlertRowsService.class));
        ReflectionTestUtils.setField(scheduler, "dbQueryScheduling",
                SchedulerDbQuerySweepTest.fake(SchedulerDbQuerySweepTest.NOW.plusSeconds(300), true));
    }

    private static DbQueryCheckerService.Result unevaluable(String note) {
        DbQueryEvaluator.RuleResult rr = new DbQueryEvaluator.RuleResult("v", "Bekleyen tutar", "COLUMN_VALUE", "—",
                "≤ 10 (uyarı)", "NONE", true, false, note, null, null);
        return new DbQueryCheckerService.Result(DbQueryCheckerService.STATUS_UNKNOWN, "NONE", 40, 3, false, List.of(),
                List.of(), List.of(), List.of(), false, List.of(rr), 3.0, false, null, null,
                SchedulerDbQuerySweepTest.iso(SchedulerDbQuerySweepTest.NOW), "abc", 3L, "PostgreSQL 16");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<MonitoringOutageService.SweepItem> failItems() {
        ReflectionTestUtils.invokeMethod(scheduler, "runDbQueryChecksLocked");
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List> items = ArgumentCaptor.forClass(List.class);
        verify(mos, times(3)).handleSweepResults(type.capture(), items.capture());
        for (int i = 0; i < type.getAllValues().size(); i++)
            if (EscalationService.TYPE_DBQUERY_FAIL.equals(type.getAllValues().get(i))) return items.getAllValues().get(i);
        throw new AssertionError("FAIL kalemi yok");
    }

    private DbQueryMonitor monitor(int unknownRuns) {
        DbQueryMonitor m = SchedulerDbQuerySweepTest.monitor(31L, "Tutar izleme");
        m.setUnknownRuns(unknownRuns);
        when(monitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        return m;
    }

    @Test
    @DisplayName("eşiğin altında: sayaç artar, FAIL kalemi UP (henüz alarm yok); kontrol satırı UNKNOWN")
    void belowLimitCountsButStaysUp() {
        DbQueryMonitor m = monitor(1);
        when(checker.check(m, false)).thenReturn(unevaluable(DbQueryEvaluator.NOTE_COLUMN_MISSING));
        List<MonitoringOutageService.SweepItem> fail = failItems();
        assertThat(fail).hasSize(1);
        assertThat(fail.get(0).up()).isTrue();
        verify(monitorRepo).markDbQueryUnknown(31L);
        assertThat(m.getUnknownRuns()).isEqualTo(2);
        assertThat(saved.get(0).getStatus()).isEqualTo("UNKNOWN");
        assertThat(saved.get(0).getOk()).isTrue();   // sorgu çalıştı — kesinti değil
    }

    @Test
    @DisplayName("eşikte: FAIL kalemi DOWN, [DB_RULE_UNEVALUABLE] (CONFIG), notlar bağlamda; yeniden ölçüm de değerlendirilemiyorsa düşük kalır")
    void atLimitFailItemGoesDown() {
        DbQueryMonitor m = monitor(2);
        when(checker.check(m, false)).thenReturn(unevaluable(DbQueryEvaluator.NOTE_NOT_NUMERIC));
        MonitoringOutageService.SweepItem it = failItems().get(0);
        assertThat(it.up()).isFalse();
        assertThat(it.error()).startsWith("[" + DbQueryAlarmText.RULE_UNEVALUABLE_CODE + "]").contains("3");
        assertThat(DbQueryAlarmText.codeOf(it.error())).isEqualTo("DB_RULE_UNEVALUABLE");
        assertThat(it.ctxExtra()).containsEntry("failure_reason", "DB_RULE_UNEVALUABLE");
        assertThat(it.ctxExtra().get("unevaluable_notes")).isEqualTo(List.of(DbQueryEvaluator.NOTE_NOT_NUMERIC));
        // teyit: yeniden ölçüm de değerlendirilemiyor → down
        when(checker.recheck(any(), any())).thenReturn(unevaluable(DbQueryEvaluator.NOTE_NOT_NUMERIC));
        Map<String, Object> again = it.recheck().get();
        assertThat(again).containsEntry("status", "down");
    }

    @Test
    @DisplayName("taban kuruluyor (baseline_reset / no_seasonal_baseline / first_run) alarm beslemez; değerlendirilebilen çalıştırma sayacı sıfırlar")
    void waitingNotesAndEvaluableRunsResetTheCounter() {
        DbQueryMonitor m = monitor(5);
        DbQueryEvaluator.RuleResult waiting = new DbQueryEvaluator.RuleResult("c", "Değişim", "CHANGE", "3", "", "NONE", true,
                false, DbQueryEvaluator.NOTE_BASELINE_RESET, 3.0, null);
        DbQueryCheckerService.Result r = new DbQueryCheckerService.Result(DbQueryCheckerService.STATUS_UNKNOWN, "NONE", 40, 3,
                false, List.of(), List.of(), List.of(), List.of(), false, List.of(waiting), 3.0, false, null, null,
                SchedulerDbQuerySweepTest.iso(SchedulerDbQuerySweepTest.NOW), "abc", 3L, "PostgreSQL 16");
        when(checker.check(m, false)).thenReturn(r);
        assertThat(failItems().get(0).up()).isTrue();
        verify(monitorRepo).clearDbQueryUnknown(31L);
        verify(monitorRepo, never()).markDbQueryUnknown(anyLong());
        assertThat(m.getUnknownRuns()).isZero();
    }

    @Test
    @DisplayName("ayar 0 = kapalı: değerlendirilemeyen çalıştırma FAIL kalemini düşürmez")
    void zeroTurnsTheAlarmOff() {
        when(settings.getInt(eq("site.monitor.dbquery.unknown-alert-runs"), anyInt())).thenReturn(0);
        assertThat(scheduler.dbQueryUnknownAlertRuns()).isZero();
        DbQueryMonitor m = monitor(10);
        when(checker.check(m, false)).thenReturn(unevaluable(DbQueryEvaluator.NOTE_COLUMN_MISSING));
        assertThat(failItems().get(0).up()).isTrue();
        // aralık sınırı
        when(settings.getInt(eq("site.monitor.dbquery.unknown-alert-runs"), anyInt())).thenReturn(99);
        assertThat(scheduler.dbQueryUnknownAlertRuns()).isEqualTo(20);
        when(settings.getInt(eq("site.monitor.dbquery.unknown-alert-runs"), anyInt())).thenReturn(-4);
        assertThat(scheduler.dbQueryUnknownAlertRuns()).isZero();
    }

    @Test
    @DisplayName("elle değerlendirme: sayaç yok — değerlendirilemeyen sonuç FAIL kalemini düşürür (açık alarmı kanıtsız kapatmaz)")
    void manualEvaluationNeverClosesWithoutEvidence() {
        DbQueryMonitor m = SchedulerDbQuerySweepTest.monitor(32L, "Elle");
        when(checker.check(m, true)).thenReturn(unevaluable(DbQueryEvaluator.NOTE_COLUMN_MISSING));
        Map<String, Object> raw = scheduler.triggerDbQueryCheck(m);
        assertThat(raw).containsEntry("status", "UNKNOWN");
        verify(monitorRepo, never()).markDbQueryUnknown(anyLong());
        scheduler.evaluateDbQueryNow(m, raw);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List> items = ArgumentCaptor.forClass(List.class);
        verify(mos).handleSweepResults(eq(EscalationService.TYPE_DBQUERY_FAIL), items.capture(), eq(true));
        @SuppressWarnings("unchecked")
        List<MonitoringOutageService.SweepItem> fail = items.getValue();
        assertThat(fail.get(0).up()).isFalse();
    }

    @Test
    @DisplayName("ikinci tur D-1: geçici sorgu hatasının teyidinde sorgu düzelip kural değerlendirilemezse eşik dolmadan DOWN DEĞİL")
    void confirmationRespectsTheUnknownLimit() {
        DbQueryMonitor m = monitor(0);
        DbQueryCheckerService.Result failed = new DbQueryCheckerService.Result(DbQueryCheckerService.STATUS_FAIL, "NONE", 40,
                null, false, List.of(), List.of(), List.of(), List.of(), false, List.of(), null, false, "bağlantı kurulamadı",
                "DB_UNREACHABLE", SchedulerDbQuerySweepTest.iso(SchedulerDbQuerySweepTest.NOW), "abc", 3L, null);
        when(checker.check(m, false)).thenReturn(failed);
        MonitoringOutageService.SweepItem it = failItems().get(0);
        assertThat(it.up()).isFalse();
        assertThat(it.error()).startsWith("[DB_UNREACHABLE]");
        // teyit: sorgu çalıştı ama kural değerlendirilemedi, sayaç 0 < 3 → iyileşme sayılır (DB_RULE_UNEVALUABLE açılmaz)
        when(checker.recheck(any(), any())).thenReturn(unevaluable(DbQueryEvaluator.NOTE_COLUMN_MISSING));
        assertThat(it.recheck().get()).containsEntry("status", "up");
        verify(monitorRepo, never()).markDbQueryUnknown(anyLong());   // sorgu hatası sayacı değiştirmez
    }
}
