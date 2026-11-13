package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Günlük görüntü işi: 05:00 İstanbul öncesi yok; PostgreSQL değilse yok; yazılmışsa kilitsiz çıkar; değilse kilit altında yazar. */
class DbSizeSnapshotJobTest {

    static final Instant AFTER_FIVE = Instant.parse("2026-11-13T03:10:00Z");    // İstanbul 06:10
    static final Instant BEFORE_FIVE = Instant.parse("2026-11-13T01:35:00Z");   // İstanbul 04:35

    private static DbSizeSnapshotJob job(DbGrowthService svc, SchedulerService sched, boolean enabled, Instant now) {
        DbSizeSnapshotJob j = new DbSizeSnapshotJob(svc, sched, enabled);
        j.setClock(() -> now);
        return j;
    }

    @Test
    @DisplayName("05:00 İstanbul öncesi hiçbir sorgu yok (gece temizliği ve indeks bakımı bitsin)")
    void beforeFive() {
        DbGrowthService svc = mock(DbGrowthService.class);
        SchedulerService sched = mock(SchedulerService.class);
        job(svc, sched, true, BEFORE_FIVE).tick();
        verify(svc, never()).supported();
        verify(svc, never()).snapshotWritten(any());
    }

    @Test
    @DisplayName("PostgreSQL değil / tür okunamadı → hiçbir şey (kilit yok)")
    void notPostgres() {
        DbGrowthService svc = mock(DbGrowthService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(svc.supported()).thenReturn(false);
        job(svc, sched, true, AFTER_FIVE).tick();
        when(svc.supported()).thenReturn(null);
        job(svc, sched, true, AFTER_FIVE).tick();
        verify(svc, never()).snapshotWritten(any());
        verify(sched, never()).runWithSchedulerLock(any(), any());
    }

    @Test
    @DisplayName("bugün yazılmış → tek ön sorgu, kilit YOK")
    void alreadyWritten() {
        DbGrowthService svc = mock(DbGrowthService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(svc.supported()).thenReturn(true);
        when(svc.snapshotWritten(any())).thenReturn(true);
        job(svc, sched, true, AFTER_FIVE).tick();
        verify(sched, never()).runWithSchedulerLock(any(), any());
        verify(svc, never()).writeSnapshot(any(), any());
    }

    @Test
    @DisplayName("yazılmamış → scheduler_lock 'db-size-snapshot' altında yazar (kilit içinde yeniden sorar); yazım hatası yutulur")
    void writesUnderLock() {
        DbGrowthService svc = mock(DbGrowthService.class);
        SchedulerService sched = mock(SchedulerService.class);
        when(svc.supported()).thenReturn(true);
        when(svc.snapshotWritten(any())).thenReturn(false);
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(sched).runWithSchedulerLock(eq(DbSizeSnapshotJob.LOCK_NAME), any());
        job(svc, sched, true, AFTER_FIVE).tick();
        // kapanış denetimi geçirilir: yan iş kapanırken tablo döngüsü yarıda bırakılır
        org.mockito.ArgumentCaptor<java.util.function.BooleanSupplier> stop = org.mockito.ArgumentCaptor.forClass(java.util.function.BooleanSupplier.class);
        verify(svc).writeSnapshot(eq(AFTER_FIVE), stop.capture());
        when(sched.sideJobsStopping()).thenReturn(true);
        org.assertj.core.api.Assertions.assertThat(stop.getValue().getAsBoolean()).isTrue();
        when(svc.writeSnapshot(any(), any())).thenThrow(new RuntimeException("boom"));
        job(svc, sched, true, AFTER_FIVE).tick();   // istisna dışarı sızmaz
    }

    @Test
    @DisplayName("kapalı → hiçbir sorgu; ön sorgu düşerse (tablo henüz yok) sessizce çıkar")
    void disabledAndPrecheckFails() {
        DbGrowthService svc = mock(DbGrowthService.class);
        SchedulerService sched = mock(SchedulerService.class);
        job(svc, sched, false, AFTER_FIVE).tick();
        verify(svc, never()).supported();
        when(svc.supported()).thenReturn(true);
        when(svc.snapshotWritten(any())).thenThrow(new RuntimeException("relation does not exist"));
        job(svc, sched, true, AFTER_FIVE).tick();
        verify(sched, never()).runWithSchedulerLock(any(), any());
    }
}
