package com.sitemonitor.service;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.page.HttpPhaseProbe;
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
import java.util.concurrent.CopyOnWriteArrayList;

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
 * Sayfa Hızı 2. sürüm madde 14 (2026-11-15): zamanlamalı (pencere / yasak / tatil / cron) Sayfa Hızı izlemesi sentetik
 * izlemenin planıyla ölçülür — klasik gövdede ve dağıtık gönderimde; düz aralıklı izleme bugünkü yoldan geçer (plan yazılmaz,
 * tatil okunmaz); planı kuran yalnız koşullu yazımı kazanan; zamanlama / tatil değişince plan sıfırlanır.
 */
class PageSpeedScheduleSweepTest {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    R33SweepHarness h;
    final List<PageSpeedMonitor> active = new ArrayList<>();
    final List<Long> measured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        h = new R33SweepHarness();
        lenient().when(h.pageSpeedRepo.findByActiveTrue()).thenAnswer(i -> new ArrayList<>(active));
        lenient().when(h.pageSpeedRepo.findAll()).thenAnswer(i -> new ArrayList<>(active));
        lenient().when(h.pageSpeedRepo.findOrphanCheckKeys()).thenAnswer(i -> R33SweepHarness.orphanKeys(new ArrayList<>(active), PageSpeedMonitor::getUrl));
        lenient().when(h.pageSpeedCheckRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(h.pageSpeedChecker.check(any(PageSpeedMonitor.class))).thenAnswer(i -> {
            PageSpeedMonitor m = i.getArgument(0);
            measured.add(m.getId());
            return new PageSpeedCheckerService.Result("OK", 200, 180L, 400L, 900L, 200_000L, 14, 0, false, false,
                    List.of(), null, List.of(), new HttpPhaseProbe.Phases(5, 10, 20, 145, null), 0);
        });
        lenient().doAnswer(i -> {
            Long id = i.getArgument(0);
            for (PageSpeedMonitor m : active) if (id.equals(m.getId())) m.setNextRunAt(i.getArgument(1));
            return 1;
        }).when(h.pageSpeedRepo).updateNextRunAt(anyLong(), any());
        lenient().doAnswer(i -> {
            Long id = i.getArgument(0);
            for (PageSpeedMonitor m : active)
                if (id.equals(m.getId()) && m.getNextRunAt() == null) { m.setNextRunAt(i.getArgument(1)); return 1; }
            return 0;
        }).when(h.pageSpeedRepo).updateNextRunAtIfNull(anyLong(), any());
        lenient().doAnswer(i -> {
            Long id = i.getArgument(0);
            for (PageSpeedMonitor m : active)
                if (id.equals(m.getId()) && java.util.Objects.equals(m.getNextRunAt(), i.getArgument(1))) {
                    m.setNextRunAt(i.getArgument(2));
                    return 1;
                }
            return 0;
        }).when(h.pageSpeedRepo).updateNextRunAtIfEquals(anyLong(), any(), any());
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
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPageSpeedChecksLocked");
    }

    private PageSpeedMonitor plain(long id) {
        PageSpeedMonitor m = R33SweepHarness.pageSpeed(id, "https://hiz-" + id + ".example.com/");
        active.add(m);
        return m;
    }

    private PageSpeedMonitor weekdayWindow(long id) {
        PageSpeedMonitor m = plain(id);
        m.setScheduleMode("INTERVAL");
        m.setIntervalSeconds(1800);
        m.setWindowsJson("[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]");
        return m;
    }

    private PageSpeedMonitor dailyCron(long id) {
        PageSpeedMonitor m = plain(id);
        m.setScheduleMode("CRON");
        m.setCronExpression("0 8 * * *");
        m.setIntervalSeconds(86_400);
        return m;
    }

    private long runs(long id) {
        return measured.stream().filter(x -> x == id).count();
    }

    private static Object[] row(PageSpeedMonitor m) {
        return new Object[]{m.getId(), m.getIntervalSeconds(), m.getScheduleMode(), m.getCronExpression(), m.getWindowsJson(),
                m.getBlackoutJson(), m.getSkipHolidays(), m.getSkipDatesJson(), m.getNextRunAt()};
    }

    @Test
    @DisplayName("düz aralıklı izleme bugünkü yoldan geçer — plan yazılmaz, tatil okunmaz")
    void plainMonitor_noPlanWrites() {
        at("2026-11-07T10:00:00");   // cumartesi
        plain(10);
        tick();
        assertThat(runs(10)).isEqualTo(1);
        verify(h.pageSpeedRepo, never()).updateNextRunAt(anyLong(), any());
        verify(h.pageSpeedRepo, never()).updateNextRunAtIfNull(anyLong(), any());
        verify(h.pageSpeedRepo, never()).updateNextRunAtIfEquals(anyLong(), any(), any());
    }

    @Test
    @DisplayName("izinli pencere dışında (cumartesi) ölçülmez; plan pazartesi 09:00'a yazılır; pencerede ölçülür, ızgara +30 dk")
    void windowOutside_planned_thenRunsInside() {
        at("2026-11-07T10:00:00");   // cumartesi
        PageSpeedMonitor m = weekdayWindow(11);
        tick();
        assertThat(runs(11)).isZero();
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T09:00:00")));

        at("2026-11-09T09:00:30");   // pazartesi
        tick();
        assertThat(runs(11)).isEqualTo(1);
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T09:30:00")));
        advanceSeconds(60);
        tick();
        assertThat(runs(11)).isEqualTo(1);
    }

    @Test
    @DisplayName("CRON — ilk görüşte tetik beklenir, tetikte ölçülür, ertesi tetik planlanır; 15 dk'dan geç tetik koşulmaz")
    void cron_runsAtTrigger_missedSkipped() {
        at("2026-11-09T07:00:00");
        PageSpeedMonitor m = dailyCron(13);
        tick();
        assertThat(runs(13)).isZero();
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T08:00:00")));
        at("2026-11-09T08:00:20");
        tick();
        assertThat(runs(13)).isEqualTo(1);
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-10T08:00:00")));

        PageSpeedMonitor late = dailyCron(14);
        at("2026-11-10T07:00:00");
        tick();
        at("2026-11-10T08:20:00");
        tick();
        assertThat(runs(14)).isZero();
        assertThat(late.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-11T08:00:00")));
    }

    @Test
    @DisplayName("gönderim de plana uyar — pencere dışındaki izleme gönderilmez, düz aralıklı izleme bugünkü gibi gider")
    void dispatchRespectsSchedule() {
        at("2026-11-07T10:00:00");   // cumartesi
        PageSpeedMonitor windowed = weekdayWindow(20);
        PageSpeedMonitor flat = plain(21);
        when(h.pageSpeedRepo.findCheckDispatchRows()).thenAnswer(i -> List.of(row(windowed), row(flat)));
        List<Long> due = h.scheduler.dueCheckJobIds(CheckJobTypes.PAGESPEED);
        assertThat(due).containsExactly(21L);
        assertThat(windowed.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T09:00:00")));
        verify(h.pageSpeedRepo, times(1)).updateNextRunAtIfNull(eq(20L), any());
        verify(h.pageSpeedRepo, never()).updateNextRunAtIfNull(eq(21L), any());

        at("2026-11-09T09:00:10");
        assertThat(h.scheduler.dueCheckJobIds(CheckJobTypes.PAGESPEED)).contains(20L);
    }

    @Test
    @DisplayName("eski iki kolonlu gönderim satırı bugünkü yoldan geçer (plan yazılmaz)")
    void dispatchLegacyRow_isPlain() {
        at("2026-11-07T10:00:00");
        when(h.pageSpeedRepo.findCheckDispatchRows()).thenReturn(List.<Object[]>of(new Object[]{30L, 1800}));
        assertThat(h.scheduler.dueCheckJobIds(CheckJobTypes.PAGESPEED)).containsExactly(30L);
        verify(h.pageSpeedRepo, never()).updateNextRunAt(anyLong(), any());
    }

    @Test
    @DisplayName("plan yokken klasik gövde ile gönderim aynı anda 'first' görür — yalnız koşullu yazımı kazanan ölçer")
    void firstPlanRace_onlyCasWinnerRuns() {
        at("2026-11-09T10:00:00");   // pazartesi, pencerede
        PageSpeedMonitor m = weekdayWindow(60);
        var spec = ScriptedSchedule.specOf(m);
        boolean a = h.scheduler.pageSpeedPlanDue(60L, spec, null, java.util.Set.of(), true);
        boolean b = h.scheduler.pageSpeedPlanDue(60L, spec, null, java.util.Set.of(), false);
        assertThat(a).isTrue();
        assertThat(b).isFalse();
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T10:30:00")));
    }

    @Test
    @DisplayName("plan yazımı DB hatası verirse ölçülmez, sonraki tık yeniden dener (sonsuz döngü yok: tık başına bir deneme)")
    void firstPlanWriteFailure_doesNotRun() {
        at("2026-11-09T10:00:00");
        PageSpeedMonitor m = weekdayWindow(61);
        when(h.pageSpeedRepo.updateNextRunAtIfNull(eq(61L), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("x"))
                .thenAnswer(i -> { m.setNextRunAt(i.getArgument(1)); return 1; });
        tick();
        assertThat(runs(61)).isZero();
        advanceSeconds(60);
        tick();
        assertThat(runs(61)).isEqualTo(1);
    }

    @Test
    @DisplayName("pageSpeedScheduleChanged: plan NULL yazılır, küme vadesi unutulur → sonraki tıkta yeni tanımdan kurulur")
    void scheduleChangedResetsPlan() {
        at("2026-11-09T10:00:00");
        PageSpeedMonitor m = weekdayWindow(40);
        tick();
        assertThat(runs(40)).isEqualTo(1);
        h.scheduler.pageSpeedScheduleChanged(40L);
        assertThat(m.getNextRunAt()).isNull();
        advanceSeconds(60);
        tick();   // plan sıfırlandı, pencerede → hemen ölçülür (yeni tanımın ilk planı)
        assertThat(runs(40)).isEqualTo(2);
    }

    @Test
    @DisplayName("pageSpeedCalendarChanged: tatile bakan CRON izlemesinin planı silinir, aralık kipindekinin silinmez; okuma hatası fırlatmaz")
    void calendarChanged() {
        when(h.pageSpeedRepo.findHolidayScheduledRows()).thenReturn(List.of(new Object[]{50L, "CRON"}, new Object[]{51L, "INTERVAL"}));
        h.scheduler.pageSpeedCalendarChanged();
        verify(h.pageSpeedRepo).updateNextRunAt(50L, null);
        verify(h.pageSpeedRepo, never()).updateNextRunAt(eq(51L), any());

        when(h.pageSpeedRepo.findHolidayScheduledRows()).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        h.scheduler.pageSpeedCalendarChanged();   // fırlatmaz
    }

    @Test
    @DisplayName("L5: tık planı okuduktan sonra plan sıfırlandıysa eski tanımın sonraki zamanı yeni planın üstüne yazılmaz")
    void staleTickDoesNotOverwriteReset() {
        at("2026-11-09T09:30:10");   // pazartesi, pencerede; plan 09:30
        PageSpeedMonitor m = weekdayWindow(70);
        String stored = ISO.format(ist("2026-11-09T09:30:00"));
        m.setNextRunAt(stored);
        var spec = ScriptedSchedule.specOf(m);
        m.setNextRunAt(null);   // kullanıcı kaydı tıkın okumasından SONRA planı sıfırladı
        assertThat(h.scheduler.pageSpeedPlanDue(70L, spec, stored, java.util.Set.of(), true)).isTrue();
        assertThat(m.getNextRunAt()).isNull();   // koşullu yazım 0 satır — sonraki tık yeni tanımdan kurar
        verify(h.pageSpeedRepo, never()).updateNextRunAt(eq(70L), any());
    }

    @Test
    @DisplayName("L4: plan sıfırlanmışken bekletilen dönem kısa yolu kullanılmaz — art arda iki tıkta iki ölçüm yok")
    @SuppressWarnings("unchecked")
    void retainedPeriodIgnoredAfterPlanReset() {
        at("2026-11-09T10:00:00");   // pazartesi, pencerede
        PageSpeedMonitor m = weekdayWindow(71);
        var spec = ScriptedSchedule.specOf(m);
        java.util.Map<String, java.util.concurrent.ConcurrentHashMap<Long, Long>> retained =
                (java.util.Map<String, java.util.concurrent.ConcurrentHashMap<Long, Long>>) ReflectionTestUtils.getField(h.scheduler, "retainedDue");
        retained.computeIfAbsent(CheckJobTypes.PAGESPEED, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(71L, h.clock.get() - 1_000L);
        boolean first = h.scheduler.pageSpeedPlanDue(71L, spec, m.getNextRunAt(), java.util.Set.of(), false);
        assertThat(first).isTrue();
        assertThat(m.getNextRunAt()).isEqualTo(ISO.format(ist("2026-11-09T10:30:00")));   // plan kuruldu
        advanceSeconds(60);
        assertThat(h.scheduler.pageSpeedPlanDue(71L, spec, m.getNextRunAt(), java.util.Set.of(), false)).isFalse();
    }
}
