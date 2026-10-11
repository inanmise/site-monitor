package com.sitemonitor.service.watchdog;

import com.sitemonitor.model.WatchdogState;
import com.sitemonitor.service.watchdog.WatchdogTransition.Action;
import com.sitemonitor.service.watchdog.WatchdogTransition.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Kendi kendini izleme durum geçişi (2026-10-11): onay, hatırlatma, teslim edilemeyen bildirimin yeniden denenmesi, iyileşme. */
class WatchdogTransitionTest {

    private static final Instant T0 = Instant.parse("2026-10-11T08:00:00Z");

    private static WatchdogState fresh() {
        WatchdogState s = new WatchdogState();
        s.setCheckKey("cert_sweep");
        return s;
    }

    private static Action step(WatchdogState s, Result r, int minute) {
        return WatchdogTransition.apply(s, r, "HIGH", "{}", T0.plus(Duration.ofMinutes(minute)), 2, 6);
    }

    @Test
    @DisplayName("tek kötü ölçüm alarm yapmaz; ikincisi PROBLEM + ALERT; araya giren iyi ölçüm sayacı sıfırlar")
    void confirmBeforeAlert() {
        WatchdogState s = fresh();
        assertThat(step(s, Result.PROBLEM, 0)).isEqualTo(Action.NONE);
        assertThat(s.getStatus()).isEqualTo("OK");
        assertThat(step(s, Result.OK, 1)).isEqualTo(Action.NONE);
        assertThat(step(s, Result.PROBLEM, 2)).isEqualTo(Action.NONE);
        assertThat(step(s, Result.PROBLEM, 3)).isEqualTo(Action.ALERT);
        assertThat(s.getStatus()).isEqualTo("PROBLEM");
        assertThat(s.getSince()).isEqualTo("2026-10-11T08:03:00");
        assertThat(s.getSeverity()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("bildirim teslim edildiyse susar; hatırlatma süresi dolunca REMIND; iki iyi ölçüm RECOVER")
    void remindAndRecover() {
        WatchdogState s = fresh();
        step(s, Result.PROBLEM, 0);
        assertThat(step(s, Result.PROBLEM, 1)).isEqualTo(Action.ALERT);
        WatchdogTransition.markNotified(s, Action.ALERT, true, T0.plus(Duration.ofMinutes(1)));
        assertThat(step(s, Result.PROBLEM, 2)).isEqualTo(Action.NONE);
        assertThat(step(s, Result.PROBLEM, 60 * 6 + 1)).isEqualTo(Action.REMIND);
        WatchdogTransition.markNotified(s, Action.REMIND, true, T0.plus(Duration.ofMinutes(60 * 6 + 1)));
        assertThat(step(s, Result.PROBLEM, 60 * 6 + 2)).isEqualTo(Action.NONE);
        assertThat(step(s, Result.OK, 400)).isEqualTo(Action.NONE);
        assertThat(step(s, Result.OK, 401)).isEqualTo(Action.RECOVER);
        assertThat(s.getStatus()).isEqualTo("OK");
        WatchdogTransition.markNotified(s, Action.RECOVER, true, T0.plus(Duration.ofMinutes(401)));
        assertThat(s.getSeverity()).isNull();
    }

    @Test
    @DisplayName("hiçbir kanaldan gidemeyen açılış 15 dk sonra yeniden denenir (her dakika değil)")
    void failedAlertRetried() {
        WatchdogState s = fresh();
        step(s, Result.PROBLEM, 0);
        assertThat(step(s, Result.PROBLEM, 1)).isEqualTo(Action.ALERT);
        WatchdogTransition.markNotified(s, Action.ALERT, false, T0.plus(Duration.ofMinutes(1)));
        assertThat(s.getNotifiedKind()).isEqualTo("ALERT_FAILED");
        assertThat(step(s, Result.PROBLEM, 5)).isEqualTo(Action.NONE);
        assertThat(step(s, Result.PROBLEM, 1 + WatchdogTransition.RETRY_MINUTES)).isEqualTo(Action.ALERT);
    }

    @Test
    @DisplayName("UNKNOWN / NA sayaçları değiştirmez; hatırlatma 0 = kapalı; önceki dönemden kalan bildirim yeni dönemi örtmez")
    void unknownAndNoReminder() {
        WatchdogState s = fresh();
        step(s, Result.PROBLEM, 0);
        assertThat(step(s, Result.UNKNOWN, 1)).isEqualTo(Action.NONE);
        assertThat(s.getProblemStreak()).isEqualTo(1);
        assertThat(step(s, Result.NA, 2)).isEqualTo(Action.NONE);
        assertThat(step(s, Result.PROBLEM, 3)).isEqualTo(Action.ALERT);
        WatchdogTransition.markNotified(s, Action.ALERT, true, T0.plus(Duration.ofMinutes(3)));
        assertThat(WatchdogTransition.apply(s, Result.PROBLEM, "HIGH", null, T0.plus(Duration.ofDays(3)), 2, 0)).isEqualTo(Action.NONE);

        // Yeni dönem: düzeldi (bildirim gitti), sonra yeniden bozuldu → yeni ALERT
        step(s, Result.OK, 5000);
        assertThat(step(s, Result.OK, 5001)).isEqualTo(Action.RECOVER);
        WatchdogTransition.markNotified(s, Action.RECOVER, true, T0.plus(Duration.ofMinutes(5001)));
        step(s, Result.PROBLEM, 5002);
        assertThat(step(s, Result.PROBLEM, 5003)).isEqualTo(Action.ALERT);
    }
}
