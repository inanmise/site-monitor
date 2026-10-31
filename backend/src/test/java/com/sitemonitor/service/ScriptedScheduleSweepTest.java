package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.service.checks.CheckJobTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * k6r B3 + D2 (2026-10-31): sentetik izlemenin klasik gövdesi ve dağıtık gönderimi.
 *
 * <ul>
 *   <li>B3 — yürütülemeyen (SKIPPED / bekleme süresi aşıldı) koşumun dönemi GERİ verilir: bir sonraki tıkta bir kez yeniden
 *       sunulur; her tıkta bir deneme; duraklatılan izlemenin geri verilen dönemi düşer.</li>
 *   <li>D2 — zamanlamalı izleme (pencere / yasak / tatil / cron) dbquery planıyla koşar, hem klasik gövdede hem gönderimde;
 *       düz aralıklı izleme bugünkü yoldan geçer (plan yazılmaz, tatil okunmaz).</li>
 * </ul>
 */
class ScriptedScheduleSweepTest {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    R33SweepHarness h;
    final List<ScriptedMonitor> active = new ArrayList<>();
    final List<Long> submitted = new ArrayList<>();
    /** Sıradaki sonuçlar (izleme kimliği başına; boşsa PASS). */
    final Map<Long, List<String>> scripted = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        h = new R33SweepHarness();
        lenient().when(h.scriptedRepo.findByActiveTrue()).thenAnswer(i -> new ArrayList<>(active));
        lenient().when(h.scriptedRepo.findAll()).thenAnswer(i -> new ArrayList<>(active));
        lenient().when(h.scriptedCheckRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(h.scriptedChecker.submit(any(ScriptedMonitor.class))).thenAnswer(i -> {
            ScriptedMonitor m = i.getArgument(0);
            submitted.add(m.getId());
            List<String> q = scripted.get(m.getId());
            String status = q == null || q.isEmpty() ? "PASS" : q.remove(0);
            return CompletableFuture.completedFuture(result(status));
        });
        // Planın hedefli yazımı: bellekteki varlığa da yansısın (bir sonraki tık saklı planı okusun)
        lenient().doAnswer(i -> {
            Long id = i.getArgument(0);
            for (ScriptedMonitor m : active) if (id.equals(m.getId())) m.setNextRunAt(i.getArgument(1));
            return 1;
        }).when(h.scriptedRepo).updateNextRunAt(anyLong(), any());
    }

    private static ScriptedCheckerService.ScriptedResult result(String status) {
        boolean skipped = ScriptedCheckerService.STATUS_SKIPPED.equals(status);
        return new ScriptedCheckerService.ScriptedResult(status, "PASS".equals(status), skipped ? null : 500L, skipped ? -1 : 0,
                skipped ? null : 1, skipped ? null : 0, skipped ? null : 500L, 80L, 120L, "[]", null,
                skipped ? "k6 havuzu dolu — kontrol atlandı" : null, false, ScriptedCheckerService.Phases.EMPTY);
    }

    private static Instant ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    private void at(String localIstanbul) {
        h.clock.set(ist(localIstanbul).toEpochMilli());
    }

    private void advanceSeconds(long s) {
        h.clock.addAndGet(s * 1000L);
    }

    private void tick() {
        ReflectionTestUtils.invokeMethod(h.scheduler, "runScriptedChecksLocked");
    }

    private ScriptedMonitor plain(long id) {
        ScriptedMonitor m = R33SweepHarness.scripted(id, "akış-" + id);
        active.add(m);
        return m;
    }

    private ScriptedMonitor weekdayWindow(long id) {
        ScriptedMonitor m = plain(id);
        m.setScheduleMode("INTERVAL");
        m.setIntervalSeconds(300);
        m.setWindowsJson("[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]");
        return m;
    }

    private ScriptedMonitor dailyCron(long id) {
        ScriptedMonitor m = plain(id);
        m.setScheduleMode("CRON");
        m.setCronExpression("0 8 * * *");
        m.setIntervalSeconds(86_400);
        return m;
    }

    private long submits(long id) {
        return submitted.stream().filter(x -> x == id).count();
    }

    // ── B3: klasik kipte atlanan dönem ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B3: SKIPPED koşumun dönemi geri verilir — bir sonraki tıkta BİR KEZ yeniden koşar, sonra olağan aralık")
    void skippedPeriodIsRetriedNextTick() {
        at("2026-11-02T10:00:00");
        plain(1);
        scripted.put(1L, new ArrayList<>(List.of(ScriptedCheckerService.STATUS_SKIPPED)));
        tick();
        assertThat(submits(1)).isEqualTo(1);
        advanceSeconds(60);
        tick();   // geri verilen dönem → koşar (PASS)
        assertThat(submits(1)).isEqualTo(2);
        advanceSeconds(60);
        tick();   // aralık 300 sn — henüz vadesi yok
        assertThat(submits(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("B3: hep atlanırsa her tıkta bir deneme (sonsuz bekleme / döngü yok, dönem başına sınır yok)")
    void alwaysSkippedRetriesOncePerTick() {
        at("2026-11-02T10:00:00");
        plain(2);
        scripted.put(2L, new ArrayList<>(List.of("SKIPPED", "SKIPPED", "SKIPPED", "SKIPPED")));
        for (int i = 0; i < 4; i++) {
            tick();
            advanceSeconds(60);
        }
        assertThat(submits(2)).isEqualTo(4);
    }

    @Test
    @DisplayName("B3: bekleme süresi aşılan (sonuç gelmeyen) koşumun dönemi de geri verilir")
    void timedOutFutureIsRetried() {
        at("2026-11-02T10:00:00");
        plain(3);
        AtomicInteger calls = new AtomicInteger();
        when(h.scriptedChecker.submit(any(ScriptedMonitor.class))).thenAnswer(i -> {
            calls.incrementAndGet();
            if (calls.get() == 1) {
                return new java.util.concurrent.FutureTask<ScriptedCheckerService.ScriptedResult>(() -> null) {
                    @Override public ScriptedCheckerService.ScriptedResult get(long t, java.util.concurrent.TimeUnit u)
                            throws java.util.concurrent.TimeoutException { throw new java.util.concurrent.TimeoutException("bekleme"); }
                };
            }
            return CompletableFuture.completedFuture(result("PASS"));
        });
        tick();
        advanceSeconds(60);
        tick();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("B3: duraklatılan izlemenin geri verilen dönemi düşer — devamda zamanlamaya bakmadan koşmaz")
    void pausedMonitorDropsRetainedPeriod() {
        at("2026-11-02T10:00:00");
        ScriptedMonitor m = plain(4);
        scripted.put(4L, new ArrayList<>(List.of("SKIPPED")));
        tick();                       // atlandı → geri verildi
        active.clear();
        advanceSeconds(60);
        tick();                       // izleme aktif değil → geri verilen dönem düşer
        active.add(m);
        advanceSeconds(60);
        tick();                       // aralık 300 sn dolmadı, geri verilen dönem yok → koşmaz
        assertThat(submits(4)).isEqualTo(1);
    }

    @Test
    @DisplayName("B3: SKIPPED satır / alarm üretmez (bugünkü gibi) — yalnız dönem geri verilir")
    void skippedWritesNoRow() {
        at("2026-11-02T10:00:00");
        plain(5);
        scripted.put(5L, new ArrayList<>(List.of("SKIPPED")));
        tick();
        verify(h.scriptedCheckRepo, never()).save(any());
    }

    // ── D2: zamanlama — klasik gövde ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D2: düz aralıklı izleme bugünkü yoldan geçer — plan yazılmaz")
    void plainMonitor_noPlanWrites() {
        at("2026-11-07T10:00:00");   // cumartesi
        plain(10);
        tick();
        assertThat(submits(10)).isEqualTo(1);
        verify(h.scriptedRepo, never()).updateNextRunAt(anyLong(), any());
    }

    @Test
    @DisplayName("D2: izinli pencere dışında (cumartesi) koşmaz; plan pazartesi 09:00'a yazılır; pencerede koşar")
    void windowOutside_planned_thenRunsInside() {
        at("2026-11-07T10:00:00");   // cumartesi
        ScriptedMonitor m = weekdayWindow(11);
        tick();
        assertThat(submits(11)).isZero();
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T09:00:00")));

        at("2026-11-09T09:00:30");   // pazartesi, pencere açıldı
        tick();
        assertThat(submits(11)).isEqualTo(1);
        // ızgara: 09:00 + 300 sn
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T09:05:00")));
        advanceSeconds(60);
        tick();
        assertThat(submits(11)).isEqualTo(1);   // henüz vade değil
    }

    @Test
    @DisplayName("D2: pencerede ilk görüşte hemen koşar (ARALIK kipi, 'first')")
    void windowInside_firstSightRuns() {
        at("2026-11-09T10:00:00");   // pazartesi
        ScriptedMonitor m = weekdayWindow(12);
        tick();
        assertThat(submits(12)).isEqualTo(1);
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T10:05:00")));
    }

    @Test
    @DisplayName("D2: CRON — ilk görüşte tetik beklenir, tetikte koşar, ertesi tetik planlanır")
    void cron_runsAtTrigger() {
        at("2026-11-09T07:00:00");
        ScriptedMonitor m = dailyCron(13);
        tick();
        assertThat(submits(13)).isZero();
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T08:00:00")));

        at("2026-11-09T08:00:20");
        tick();
        assertThat(submits(13)).isEqualTo(1);
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-10T08:00:00")));
    }

    @Test
    @DisplayName("D2: CRON tetiği 15 dk'dan fazla kaçırıldıysa geç koşulmaz, sonraki tetik planlanır")
    void cron_missedTriggerIsSkipped() {
        at("2026-11-09T07:00:00");
        ScriptedMonitor m = dailyCron(14);
        tick();
        at("2026-11-09T08:20:00");
        tick();
        assertThat(submits(14)).isZero();
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-10T08:00:00")));
    }

    @Test
    @DisplayName("D2 + B3: zamanlamalı izlemenin atlanan dönemi yalnız İZİNLİ anda yeniden sunulur")
    void retainedPeriodRespectsWindow() {
        at("2026-11-09T17:58:30");
        ScriptedMonitor m = weekdayWindow(15);
        scripted.put(15L, new ArrayList<>(List.of("SKIPPED", "SKIPPED")));
        tick();                                  // pencerede, ilk görüş → koşar ama atlandı → geri verildi
        assertThat(submits(15)).isEqualTo(1);
        at("2026-11-09T17:59:30");
        tick();                                  // hâlâ pencerede → geri verilen dönem koşar (yine atlandı)
        assertThat(submits(15)).isEqualTo(2);
        at("2026-11-09T18:00:30");
        tick();                                  // pencere kapandı → geri verilen dönem düşer, plan pencere dışını reddeder
        assertThat(submits(15)).isEqualTo(2);
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-10T09:00:00")));
    }

    // ── D2: dağıtık gönderim ────────────────────────────────────────────────────────────────────────────────────────

    private static Object[] row(ScriptedMonitor m) {
        return new Object[]{m.getId(), m.getIntervalSeconds(), m.getScheduleMode(), m.getCronExpression(), m.getWindowsJson(),
                m.getBlackoutJson(), m.getSkipHolidays(), m.getSkipDatesJson(), m.getNextRunAt()};
    }

    @Test
    @DisplayName("D2: gönderim de plana uyar — pencere dışındaki izleme gönderilmez, düz aralıklı izleme bugünkü gibi gider")
    void dispatchRespectsSchedule() {
        at("2026-11-07T10:00:00");   // cumartesi
        ScriptedMonitor windowed = weekdayWindow(20);
        ScriptedMonitor flat = plain(21);
        when(h.scriptedRepo.findCheckDispatchRows()).thenAnswer(i -> List.of(row(windowed), row(flat)));
        List<Long> due = h.scheduler.dueCheckJobIds(CheckJobTypes.SCRIPTED);
        assertThat(due).containsExactly(21L);
        assertThat(windowed.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T09:00:00")));
        verify(h.scriptedRepo, times(1)).updateNextRunAt(eq(20L), any());
        verify(h.scriptedRepo, never()).updateNextRunAt(eq(21L), any());

        at("2026-11-09T09:00:10");
        assertThat(h.scheduler.dueCheckJobIds(CheckJobTypes.SCRIPTED)).contains(20L);
    }

    @Test
    @DisplayName("D2: eski iki kolonlu gönderim satırı (izdüşüm öncesi) bugünkü yoldan geçer")
    void dispatchLegacyRow_isPlain() {
        at("2026-11-07T10:00:00");
        when(h.scriptedRepo.findCheckDispatchRows()).thenReturn(List.<Object[]>of(new Object[]{30L, 300}));
        assertThat(h.scheduler.dueCheckJobIds(CheckJobTypes.SCRIPTED)).containsExactly(30L);
        verify(h.scriptedRepo, never()).updateNextRunAt(anyLong(), any());
    }

    // ── Plan sıfırlama kancaları ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("scriptedScheduleChanged: plan NULL yazılır, küme vadesi unutulur → sonraki tıkta yeni tanımdan kurulur")
    void scheduleChangedResetsPlan() {
        at("2026-11-09T10:00:00");
        ScriptedMonitor m = weekdayWindow(40);
        tick();
        assertThat(submits(40)).isEqualTo(1);
        h.scheduler.scriptedScheduleChanged(40L);
        verify(h.scriptedRepo).updateNextRunAt(40L, null);
        assertThat(m.getNextRunAt()).isNull();
        advanceSeconds(30);
        tick();   // plan yok + pencerede → "first" → hemen koşar
        assertThat(submits(40)).isEqualTo(2);
    }

    @Test
    @DisplayName("scriptedCalendarChanged: tatile bakan CRON izlemesinin planı silinir, aralık kipindekinin silinmez")
    void calendarChangedResetsCronPlansOnly() {
        when(h.scriptedRepo.findHolidayScheduledRows()).thenReturn(List.of(new Object[]{50L, "CRON"}, new Object[]{51L, "INTERVAL"},
                new Object[]{52L, null}));
        h.scheduler.scriptedCalendarChanged();
        verify(h.scriptedRepo).updateNextRunAt(50L, null);
        verify(h.scriptedRepo, never()).updateNextRunAt(eq(51L), any());
        verify(h.scriptedRepo, never()).updateNextRunAt(eq(52L), any());
    }

    @Test
    @DisplayName("scriptedCalendarChanged: okuma hatası fırlatmaz (plan sonraki tıkta kendiliğinden düzelir)")
    void calendarChangedReadFailureIsSwallowed() {
        when(h.scriptedRepo.findHolidayScheduledRows()).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        h.scheduler.scriptedCalendarChanged();
        verify(h.scriptedRepo, never()).updateNextRunAt(anyLong(), any());
    }
}
