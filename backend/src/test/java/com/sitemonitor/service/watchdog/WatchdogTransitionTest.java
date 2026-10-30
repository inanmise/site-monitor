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

    private static Action hold(WatchdogState s, int minute) {
        return WatchdogTransition.apply(s, Result.UNKNOWN, null, "{}", T0.plus(Duration.ofMinutes(minute)), 2, 6, false, true);
    }

    @Test
    @DisplayName("tutma (2026-10-30, kanıtsız düzelme yok): açık sorun kapanmaz ama hatırlatma ve teslim edilemeyen bildirimin "
            + "yeniden denemesi sürer; düz UNKNOWN eskisi gibi hiçbir şey yapmaz")
    void holdKeepsProblemAndItsFollowUps() {
        WatchdogState s = fresh();
        step(s, Result.PROBLEM, 0);
        assertThat(step(s, Result.PROBLEM, 1)).isEqualTo(Action.ALERT);
        WatchdogTransition.markNotified(s, Action.ALERT, false, T0.plus(Duration.ofMinutes(1)));   // ALERT_FAILED
        assertThat(hold(s, 5)).as("15 dk dolmadan yeniden deneme yok").isEqualTo(Action.NONE);
        assertThat(hold(s, 17)).as("teslim edilemeyen açılış tutmada da yeniden denenir").isEqualTo(Action.ALERT);
        WatchdogTransition.markNotified(s, Action.ALERT, true, T0.plus(Duration.ofMinutes(17)));
        assertThat(hold(s, 60)).isEqualTo(Action.NONE);
        assertThat(hold(s, 17 + 60 * 6)).as("hatırlatma tutmada da gider").isEqualTo(Action.REMIND);
        assertThat(s.getStatus()).isEqualTo("PROBLEM");
        // Düz UNKNOWN (ölçülemedi) — hatırlatma dahil hiçbir şey
        WatchdogState u = fresh();
        step(u, Result.PROBLEM, 0);
        step(u, Result.PROBLEM, 1);
        WatchdogTransition.markNotified(u, Action.ALERT, true, T0.plus(Duration.ofMinutes(1)));
        assertThat(step(u, Result.UNKNOWN, 60 * 7)).isEqualTo(Action.NONE);
    }

    @Test
    @DisplayName("tutma onay sayacını böler: açık sorunda OK, tutma, OK düzelme sayılmaz; kapalı kontrolde PROBLEM, tutma, PROBLEM "
            + "alarm sayılmaz (karma sürümde ardışık olmayan ölçümler onay olmaz)")
    void holdBreaksConfirmationStreaks() {
        WatchdogState s = fresh();
        step(s, Result.PROBLEM, 0);
        step(s, Result.PROBLEM, 1);
        WatchdogTransition.markNotified(s, Action.ALERT, true, T0.plus(Duration.ofMinutes(1)));
        assertThat(step(s, Result.OK, 2)).isEqualTo(Action.NONE);
        assertThat(hold(s, 3)).isEqualTo(Action.NONE);
        assertThat(step(s, Result.OK, 4)).as("tutma sağlıklı sayacı sıfırladı").isEqualTo(Action.NONE);
        assertThat(step(s, Result.OK, 5)).isEqualTo(Action.RECOVER);

        WatchdogState k = fresh();
        assertThat(step(k, Result.PROBLEM, 0)).isEqualTo(Action.NONE);
        assertThat(hold(k, 1)).isEqualTo(Action.NONE);
        assertThat(step(k, Result.PROBLEM, 2)).as("tutma sorunlu sayacı sıfırladı").isEqualTo(Action.NONE);
        assertThat(k.getStatus()).isEqualTo("OK");
        assertThat(step(k, Result.PROBLEM, 3)).isEqualTo(Action.ALERT);
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
    @DisplayName("UNKNOWN sayaçları değiştirmez; hatırlatma 0 = kapalı; önceki dönemden kalan bildirim yeni dönemi örtmez")
    void unknownAndNoReminder() {
        WatchdogState s = fresh();
        step(s, Result.PROBLEM, 0);
        assertThat(step(s, Result.UNKNOWN, 1)).isEqualTo(Action.NONE);
        assertThat(s.getProblemStreak()).isEqualTo(1);
        assertThat(step(s, Result.UNKNOWN, 2)).isEqualTo(Action.NONE);
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

    @Test
    @DisplayName("2026-10-12: açık sorun NA'ya dönerse (kontrolün konusu kalmadı) kapanır ve kapanış bildirilir; sorun yokken NA sessiz")
    void naClosesOpenProblem() {
        WatchdogState s = fresh();
        step(s, Result.PROBLEM, 0);
        assertThat(step(s, Result.PROBLEM, 1)).isEqualTo(Action.ALERT);
        WatchdogTransition.markNotified(s, Action.ALERT, true, T0.plus(Duration.ofMinutes(1)));
        assertThat(step(s, Result.NA, 2)).isEqualTo(Action.RECOVER);
        assertThat(s.getStatus()).isEqualTo("OK");
        assertThat(s.getProblemStreak()).isZero();
        assertThat(step(s, Result.NA, 3)).isEqualTo(Action.NONE);   // tekrar bildirim yok
        // Sorun yokken NA: sessiz, onay sayacı sıfırlanır (yarım kalmış sorun serisi NA'dan sonra baştan sayılır)
        WatchdogState t = fresh();
        step(t, Result.PROBLEM, 0);
        assertThat(step(t, Result.NA, 1)).isEqualTo(Action.NONE);
        assertThat(step(t, Result.PROBLEM, 2)).isEqualTo(Action.NONE);
        assertThat(step(t, Result.PROBLEM, 3)).isEqualTo(Action.ALERT);
    }
}
