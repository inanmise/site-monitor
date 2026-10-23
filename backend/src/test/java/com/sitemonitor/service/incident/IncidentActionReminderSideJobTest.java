package com.sitemonitor.service.incident;

import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.IncidentActionItemRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.schedule.SideJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R3-1 3e (2026-10-23): olay aksiyonu hatırlatmasının 09:00 tetiği yan iş yerleşiminden geçer — lider başka canlı pod varken
 * erteler; ön denetim YOK (öğe başına sahiplenme ikinci koşuyu zararsız kılar: aynı gün aynı öğe ikinci kez postalanmaz).
 */
class IncidentActionReminderSideJobTest {

    final SchedulerService scheduler = mock(SchedulerService.class);

    IncidentActionReminderService job() {
        IncidentActionReminderService j = new IncidentActionReminderService(mock(IncidentActionItemRepository.class),
                mock(IncidentRecordRepository.class), mock(AppUserRepository.class), mock(EmailNotificationService.class),
                mock(NotificationLogRepository.class), mock(AppSettingsService.class));
        ReflectionTestUtils.setField(j, "scheduler", scheduler);
        return j;
    }

    @Test
    @DisplayName("Zamanlayıcı üstlenirse gövde burada koşmaz; iş adı = kilit, cron'lu, kısa, ön denetimsiz")
    void offloaded() {
        IncidentActionReminderService j = job();
        when(scheduler.offloadSideJob(any())).thenReturn(true);
        j.scheduled();
        ArgumentCaptor<SideJob> c = ArgumentCaptor.forClass(SideJob.class);
        verify(scheduler).offloadSideJob(c.capture());
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any());
        assertThat(c.getValue().name()).isEqualTo(IncidentActionReminderService.LOCK);
        assertThat(c.getValue().cronTriggered()).isTrue();
        assertThat(c.getValue().cron()).isEqualTo(IncidentActionReminderService.CRON);
        assertThat(c.getValue().heavy()).isFalse();
        assertThat(c.getValue().alreadyDone()).isNull();
        // FX-SIDE #2: tetik başına küme turu — ertelenen denemenin ön denetimi bu tur (cronRoundTaken)
        assertThat(c.getValue().cronRound()).isEqualTo("incident-action-reminder");
        assertThat(c.getValue().zone()).isEqualTo("Europe/Istanbul");
    }

    @Test
    @DisplayName("FX-SIDE #2: kilit altında önce tetiğin küme turu (öğe korumalı) sahiplenilir — alınamazsa (başka pod koştu) "
            + "hatırlatma koşmaz; alınırsa koşar")
    void claimsTheCronRoundInsideTheLock() {
        IncidentActionReminderService j = job();
        IncidentActionItemRepository actions = (IncidentActionItemRepository)
                ReflectionTestUtils.getField(j, "actions");
        when(scheduler.callWithSchedulerLock(eq(IncidentActionReminderService.LOCK), any())).thenAnswer(i ->
                java.util.Optional.ofNullable(((java.util.function.Supplier<?>) i.getArgument(1)).get()));
        j.scheduled();
        verify(scheduler).claimItemProtectedCronRound("incident-action-reminder", IncidentActionReminderService.CRON,
                "Europe/Istanbul");
        verify(actions, never()).reminderCandidates(anyString());

        when(scheduler.claimItemProtectedCronRound(anyString(), anyString(), anyString())).thenReturn(true);
        j.scheduled();
        verify(actions).reminderCandidates(anyString());
    }

    @Test
    @DisplayName("Üstlenmezse gövde bugünkü gibi kilit altında burada koşar; kapalıysa hiçbir şey")
    void inlineWhenNotOffloaded() {
        IncidentActionReminderService j = job();
        j.scheduled();
        verify(scheduler).callWithSchedulerLock(eq(IncidentActionReminderService.LOCK), any());
        IncidentActionReminderService off = job();
        off.setEnabled(false);
        org.mockito.Mockito.clearInvocations(scheduler);
        off.scheduled();
        verify(scheduler, never()).offloadSideJob(any());
    }
}
