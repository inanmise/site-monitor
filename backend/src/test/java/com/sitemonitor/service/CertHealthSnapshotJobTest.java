package com.sitemonitor.service;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sertifika sağlığı günlük görüntü işi (2026-11-13, DataQualitySnapshotJob deseni): yazılmışsa kilit almadan çıkar; değilse scheduler_lock altında yazar; kapalıysa hiçbir şey. */
class CertHealthSnapshotJobTest {

    @Test
    @DisplayName("bugün yazılmış → tek ön sorgu, kilit YOK")
    void alreadyWritten() {
        CertHealthDailyService svc = mock(CertHealthDailyService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(svc.snapshotWritten(any())).thenReturn(true);
        new CertHealthSnapshotJob(svc, sched, true).tick();
        verify(sched, never()).runWithSchedulerLock(any(), any());
        verify(svc, never()).writeDailySnapshot(any());
    }

    @Test
    @DisplayName("yazılmamış → scheduler_lock 'cert-health-snapshot' altında yazar (kilit içinde yeniden sorar)")
    void writesUnderLock() {
        CertHealthDailyService svc = mock(CertHealthDailyService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(svc.snapshotWritten(any())).thenReturn(false);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(sched).runWithSchedulerLock(eq(CertHealthSnapshotJob.LOCK_NAME), any());
        new CertHealthSnapshotJob(svc, sched, true).tick();
        verify(svc).writeDailySnapshot(any());
    }

    @Test
    @DisplayName("kapalı (site.monitor.cert-health.snapshot-enabled=false) → hiçbir sorgu")
    void disabled() {
        CertHealthDailyService svc = mock(CertHealthDailyService.class);
        SchedulerService sched = mock(SchedulerService.class);
        new CertHealthSnapshotJob(svc, sched, false).tick();
        verify(svc, never()).snapshotWritten(any());
        verify(sched, never()).runWithSchedulerLock(any(), any());
    }

    @Test
    @DisplayName("ön sorgu düşerse (tablo henüz yok) sessizce çıkar")
    void precheckFails() {
        CertHealthDailyService svc = mock(CertHealthDailyService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(svc.snapshotWritten(any())).thenThrow(new RuntimeException("relation does not exist"));
        new CertHealthSnapshotJob(svc, sched, true).tick();
        verify(sched, never()).runWithSchedulerLock(any(), any());
    }
}
