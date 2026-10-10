package com.sitemonitor.service.quality;

import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Günlük görüntü işi: yazılmışsa kilit almadan çıkar; değilse scheduler_lock altında yazar; kapalıysa hiçbir şey. */
class DataQualitySnapshotJobTest {

    @Test
    @DisplayName("bugün yazılmış → tek ön sorgu, kilit YOK")
    void alreadyWritten() {
        DataQualityService svc = mock(DataQualityService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(svc.snapshotWritten(any())).thenReturn(true);
        new DataQualitySnapshotJob(svc, sched, true).tick();
        verify(sched, never()).runWithSchedulerLock(any(), any());
        verify(svc, never()).writeDailySnapshot(any());
    }

    @Test
    @DisplayName("yazılmamış → scheduler_lock 'data-quality-snapshot' altında yazar (kilit içinde yeniden sorar)")
    void writesUnderLock() {
        DataQualityService svc = mock(DataQualityService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(svc.snapshotWritten(any())).thenReturn(false);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(sched).runWithSchedulerLock(eq(DataQualitySnapshotJob.LOCK_NAME), any());
        new DataQualitySnapshotJob(svc, sched, true).tick();
        verify(svc).writeDailySnapshot(any());
    }

    @Test
    @DisplayName("kapalı (site.monitor.data-quality.snapshot-enabled=false) → hiçbir sorgu")
    void disabled() {
        DataQualityService svc = mock(DataQualityService.class);
        SchedulerService sched = mock(SchedulerService.class);
        new DataQualitySnapshotJob(svc, sched, false).tick();
        verify(svc, never()).snapshotWritten(any());
        verify(sched, never()).runWithSchedulerLock(any(), any());
    }

    @Test
    @DisplayName("ön sorgu düşerse (tablo henüz yok) sessizce çıkar")
    void precheckFails() {
        DataQualityService svc = mock(DataQualityService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(svc.snapshotWritten(any())).thenThrow(new RuntimeException("relation does not exist"));
        new DataQualitySnapshotJob(svc, sched, true).tick();
        verify(sched, never()).runWithSchedulerLock(any(), any());
    }
}
