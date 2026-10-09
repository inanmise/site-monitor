package com.sitemonitor.service.lockout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** İlerleyici kilit merdiveni (2026-10-09) — mevcut hesap ve bilinmeyen adın ORTAK hesabı. */
class LockoutLadderTest {

    private final LockoutLadder ladder = new LockoutLadder(List.of(30L, 120L, 600L, 1800L), List.of(5, 3, 2, 1));

    @Test
    @DisplayName("Gereken hata sayısı kademeyle daralır (5/3/2/1), liste dışında 1; null = 0. kademe")
    void failuresNeeded() {
        assertThat(ladder.failuresNeeded(null)).isEqualTo(5);
        assertThat(ladder.failuresNeeded(0)).isEqualTo(5);
        assertThat(ladder.failuresNeeded(1)).isEqualTo(3);
        assertThat(ladder.failuresNeeded(2)).isEqualTo(2);
        assertThat(ladder.failuresNeeded(3)).isEqualTo(1);
        assertThat(ladder.failuresNeeded(4)).isEqualTo(1);
        assertThat(ladder.failuresNeeded(40)).isEqualTo(1);
    }

    @Test
    @DisplayName("İhlal süresi 30/120/600/1800, son kademeden sonra son süre yinelenir (kalıcı kilit yok)")
    void durationsRepeatLast() {
        assertThat(ladder.durationSeconds(1)).isEqualTo(30);
        assertThat(ladder.durationSeconds(2)).isEqualTo(120);
        assertThat(ladder.durationSeconds(3)).isEqualTo(600);
        assertThat(ladder.durationSeconds(4)).isEqualTo(1800);
        assertThat(ladder.durationSeconds(5)).isEqualTo(1800);
        assertThat(ladder.durationSeconds(99)).isEqualTo(1800);
        assertThat(ladder.levels()).isEqualTo(4);
    }

    @Test
    @DisplayName("escalate: kademe +1, damgalar saniyeye kırpılmış Z'siz UTC ISO")
    void escalateStamps() {
        Instant now = Instant.parse("2026-10-09T08:00:00.900Z");
        LockoutLadder.Step s = ladder.escalate(null, now);
        assertThat(s.level()).isEqualTo(1);
        assertThat(s.seconds()).isEqualTo(30);
        assertThat(s.lockedAt()).isEqualTo("2026-10-09T08:00:00");
        assertThat(s.lockoutUntil()).isEqualTo("2026-10-09T08:00:30");

        LockoutLadder.Step s5 = ladder.escalate(4, now);
        assertThat(s5.level()).isEqualTo(5);
        assertThat(s5.seconds()).isEqualTo(1800);
        assertThat(s5.lockoutUntil()).isEqualTo("2026-10-09T08:30:00");
    }

    @Test
    @DisplayName("Kalan süre aşağı yuvarlanır; dolmuş / boş / bozuk damga 0")
    void remainingSeconds() {
        Instant now = Instant.parse("2026-10-09T08:00:00.250Z");
        assertThat(LockoutLadder.remainingSeconds("2026-10-09T08:00:30", now)).isEqualTo(29);
        assertThat(LockoutLadder.remainingSeconds("2026-10-09T08:00:00", now)).isZero();
        assertThat(LockoutLadder.remainingSeconds("2026-10-09T07:00:00", now)).isZero();
        assertThat(LockoutLadder.remainingSeconds(null, now)).isZero();
        assertThat(LockoutLadder.remainingSeconds(" ", now)).isZero();
        assertThat(LockoutLadder.remainingSeconds("bozuk", now)).isZero();
    }

    @Test
    @DisplayName("Boş / null yapılandırma varsayılanlara düşer")
    void defaults() {
        LockoutLadder d = new LockoutLadder(null, null);
        assertThat(d.durationSeconds(1)).isEqualTo(30);
        assertThat(d.durationSeconds(4)).isEqualTo(1800);
        assertThat(d.failuresNeeded(0)).isEqualTo(5);
        assertThat(new LockoutLadder(List.of(), List.of(5)).durationSeconds(2)).isEqualTo(120);
    }
}
