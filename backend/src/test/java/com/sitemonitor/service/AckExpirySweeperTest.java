package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Süreli onay süpürücüsü (2026-11-19): kümede tek pod — {@code scheduler_lock} "ack-expiry" altında; hata turu düşürmez. */
class AckExpirySweeperTest {

    @Test
    @DisplayName("kilit adı kalıcı; tur kilit altında koşar (2 dk'lık yenilenen kilit)")
    @SuppressWarnings("unchecked")
    void runsUnderSchedulerLock() throws Exception {
        assertThat(AckExpirySweeper.LOCK).isEqualTo("ack-expiry");
        EscalationService esc = mock(EscalationService.class);
        SchedulerService scheduler = mock(SchedulerService.class);
        when(scheduler.callWithSchedulerLock(eq("ack-expiry"), eq(Duration.ofMinutes(2)), any(Supplier.class)))
                .thenAnswer(i -> Optional.ofNullable(((Supplier<Object>) i.getArgument(2)).get()));
        AckExpirySweeper sweeper = new AckExpirySweeper(esc);
        sweeper.setScheduler(scheduler);

        sweeper.scheduledSweep();

        verify(esc).expireDueAcknowledgements(any(Instant.class));
    }

    @Test
    @DisplayName("kilit başka pod'da → tur hiçbir şey yapmaz")
    @SuppressWarnings("unchecked")
    void lockElsewhere_noWork() throws Exception {
        EscalationService esc = mock(EscalationService.class);
        SchedulerService scheduler = mock(SchedulerService.class);
        when(scheduler.callWithSchedulerLock(eq("ack-expiry"), any(Duration.class), any(Supplier.class))).thenReturn(Optional.empty());
        AckExpirySweeper sweeper = new AckExpirySweeper(esc);
        sweeper.setScheduler(scheduler);

        sweeper.scheduledSweep();

        verify(esc, never()).expireDueAcknowledgements(any());
    }

    @Test
    @DisplayName("turdaki hata dışarı kaçmaz (zamanlayıcı ölmez)")
    void failureIsSwallowed() {
        EscalationService esc = mock(EscalationService.class);
        when(esc.expireDueAcknowledgements(any())).thenThrow(new IllegalStateException("db"));
        AckExpirySweeper sweeper = new AckExpirySweeper(esc);

        assertThatCode(sweeper::scheduledSweep).doesNotThrowAnyException();
    }
}
