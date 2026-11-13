package com.sitemonitor.service.baseline;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.schedule.SideJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Taban çizgisi örneklerinin GÜNLÜK budaması (hata taraması 2026-11-13 #2 — {@code prune} hiç çağrılmıyordu): iş yan iş
 * koşucusuna gider, kilit + cron turu alındıysa budar, iki pod aynı tetikte TEK budama yapar, kapalıyken hiçbir şey.
 */
class BaselinePruneJobTest {

    @Test
    @DisplayName("tetik → yan iş (cron turu adı = kilit adı), kilit + tur alındıysa budar")
    void tickOffloadsAndPrunesUnderLockAndRound() {
        BaselineService svc = mock(BaselineService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(sched.offloadSideJob(any())).thenReturn(false);   // koşucu yok → gövde hemen
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
                .when(sched).runWithSchedulerLock(eq(BaselinePruneJob.NAME), any());
        when(sched.claimItemProtectedCronRound(BaselinePruneJob.NAME, BaselinePruneJob.CRON, BaselinePruneJob.ZONE)).thenReturn(true);
        when(svc.prune(any())).thenReturn(new BaselineSampleStore.PruneResult(42, 3, false));

        new BaselinePruneJob(svc, sched, true).tick();

        ArgumentCaptor<SideJob> job = ArgumentCaptor.forClass(SideJob.class);
        verify(sched).offloadSideJob(job.capture());
        assertThat(job.getValue().name()).isEqualTo(BaselinePruneJob.NAME);
        assertThat(job.getValue().cronRound()).isEqualTo(BaselinePruneJob.NAME);
        assertThat(job.getValue().heavy()).isTrue();
        verify(svc).prune(any());
    }

    @Test
    @DisplayName("tur başka pod'da alındı → budama YOK")
    void roundTakenElsewhereSkips() {
        BaselineService svc = mock(BaselineService.class);
        SchedulerService sched = mock(SchedulerService.class);
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
                .when(sched).runWithSchedulerLock(eq(BaselinePruneJob.NAME), any());
        when(sched.claimItemProtectedCronRound(any(), any(), any())).thenReturn(false);
        new BaselinePruneJob(svc, sched, true).runLocked();
        verify(svc, never()).prune(any());
    }

    @Test
    @DisplayName("kapalı (site.monitor.baseline.prune-enabled=false) → hiçbir şey")
    void disabledDoesNothing() {
        BaselineService svc = mock(BaselineService.class);
        SchedulerService sched = mock(SchedulerService.class);
        new BaselinePruneJob(svc, sched, false).tick();
        verify(sched, never()).offloadSideJob(any());
        verify(svc, never()).prune(any());
    }

    @Test
    @DisplayName("İKİ POD (gerçek SchedulerService + ortak H2 kilit / tur kaydı) aynı tetikte → TEK budama")
    void twoPodsPruneOnce() {
        BaselineService svc = mock(BaselineService.class);
        when(svc.prune(any())).thenReturn(new BaselineSampleStore.PruneResult(0, 0, false));
        long start = Instant.parse("2026-11-13T01:45:00Z").toEpochMilli();   // 04:45 İstanbul
        try (MultiPodTestSupport mp = MultiPodTestSupport.create(start)) {
            BaselinePruneJob podA = new BaselinePruneJob(svc, mp.pod(1).scheduler(), true);
            BaselinePruneJob podB = new BaselinePruneJob(svc, mp.pod(2).scheduler(), true);
            mp.advance(2_000);
            podA.runLocked();
            mp.advance(30_000);   // geç kalan ikinci pod: kilit bırakıldı, tur alınmış
            podB.runLocked();
            verify(svc, times(1)).prune(any());
        }
    }
}
