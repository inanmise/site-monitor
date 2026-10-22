package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aylık yönetici özetinin zamanlaması (çok pod R2a, MP-42, 2026-10-22): planlı tetik küme genelinde BİR KEZ (cron turu) ve
 * yalnız CANLI cron bu dakikada tetikleniyorsa; telafi saatte bir kez (iki aşamalı tur) — geç kalan ikinci pod deneme
 * hakkını boşa harcamaz.
 */
class ExecutiveSummarySchedulingTest {

    private final ExecutiveSummaryDeliveryService delivery = mock(ExecutiveSummaryDeliveryService.class);
    private final com.sitemonitor.service.AppSettingsService appSettings = mock(com.sitemonitor.service.AppSettingsService.class);
    private final SchedulerService scheduler = mock(SchedulerService.class);
    private final ExecutiveSummaryTeamService teams = mock(ExecutiveSummaryTeamService.class);
    private ExecutiveSummaryScheduling scheduling;

    /** 1 Ekim 2026 09:00 İstanbul (varsayılan cron "0 0 9 1 * *"). */
    private static final Instant FIRE = Instant.parse("2026-10-01T06:00:00Z");

    @BeforeEach
    void setUp() {
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        scheduling = new ExecutiveSummaryScheduling(delivery, new ExecutiveSummarySettings(appSettings), scheduler, teams);
    }

    @Test
    @DisplayName("planlı tetik: iki pod aynı tetikte — cron turunu kazanan TEK pod gönderir")
    void twoPodsSameFire_oneRun() {
        when(scheduler.claimCronRound(eq(ExecutiveSummaryScheduling.SCHEDULED_ROUND), eq(ExecutiveSummarySettings.DEFAULT_CRON),
                eq("Europe/Istanbul"))).thenReturn(true).thenReturn(false);
        scheduling.scheduledTick(FIRE);
        scheduling.scheduledTick(FIRE.plusSeconds(30));
        verify(delivery, times(1)).runScheduled();
    }

    @Test
    @DisplayName("zamanlama tetik planlandıktan sonra değişti (1'i 09:00 → 2'si 09:00): eski tetik göndermez; yeni tetik anında gönderir")
    void oldFireAfterCronChange_notDue() {
        when(appSettings.getString(eq(ExecutiveSummarySettings.CRON_KEY), any())).thenReturn("0 0 9 2 * *");
        when(scheduler.claimCronRound(anyString(), anyString(), anyString())).thenReturn(true);
        scheduling.scheduledTick(FIRE);                                   // eski planlı tetik
        verify(delivery, never()).runScheduled();
        verify(scheduler, never()).claimCronRound(anyString(), anyString(), anyString());
        scheduling.scheduledTick(FIRE.plusSeconds(24 * 3600));            // 2'si 09:00
        verify(delivery, times(1)).runScheduled();
    }

    @Test
    @DisplayName("telafi: saatte bir küme turu — iki pod aynı saatte → bir telafi; tur yalnız başarıyla bitince tamamlanır")
    void catchUpRoundOncePerHour() {
        when(scheduler.claimRound(eq(ExecutiveSummaryScheduling.CATCH_UP_ROUND), anyLong())).thenReturn(true).thenReturn(false);
        scheduling.catchUpTick();
        scheduling.catchUpTick();
        verify(delivery, times(1)).catchUp();
        verify(scheduler, times(1)).completeRound(ExecutiveSummaryScheduling.CATCH_UP_ROUND);
        assertThat(ExecutiveSummaryScheduling.CATCH_UP_ROUND_MS).isLessThan(3_600_000L);

        when(scheduler.claimRound(eq(ExecutiveSummaryScheduling.CATCH_UP_ROUND), anyLong())).thenReturn(true);
        when(delivery.catchUp()).thenThrow(new IllegalStateException("db"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduling.catchUpTick()).isInstanceOf(IllegalStateException.class);
        verify(scheduler, times(1)).completeRound(ExecutiveSummaryScheduling.CATCH_UP_ROUND);   // yarıda kalan tur tamamlanmaz
    }

    @Test
    @DisplayName("kayıtlı görevler kilit altında koşar (planlı + telafi)")
    void tasksRunUnderLock() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduling.configureTasks(registrar);
        assertThat(registrar.getTriggerTaskList()).hasSize(2);
        doAnswer(inv -> null).when(scheduler).runWithSchedulerLock(eq(ExecutiveSummaryScheduling.LOCK), any());
        registrar.getTriggerTaskList().get(0).getRunnable().run();
        verify(scheduler).runWithSchedulerLock(eq("executive-summary"), any());
    }
}
