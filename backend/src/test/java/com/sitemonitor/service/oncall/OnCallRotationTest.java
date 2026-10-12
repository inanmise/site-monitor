package com.sitemonitor.service.oncall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Nöbet rotasyonu saf hesabı (2026-10-11): haftalık / günlük dönem, İstanbul devir saati, yer değiştirme önceliği. */
class OnCallRotationTest {

    // Pazartesi 2026-10-05 09:00 İstanbul = 06:00Z
    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 10, 5, 9, 0);
    private static final OnCallRotation.Config WEEKLY = new OnCallRotation.Config(true, 7, ANCHOR, List.of(11L, 22L, 33L));

    @Test
    @DisplayName("haftalık: ilk hafta 1. üye, devir İstanbul 09:00'da; üçüncüden sonra başa döner")
    void weekly() {
        assertThat(OnCallRotation.current(WEEKLY, List.of(), Instant.parse("2026-10-05T06:00:00Z")).userId()).isEqualTo(11L);
        assertThat(OnCallRotation.current(WEEKLY, List.of(), Instant.parse("2026-10-12T05:59:59Z")).userId()).isEqualTo(11L);
        OnCallRotation.Slot s = OnCallRotation.current(WEEKLY, List.of(), Instant.parse("2026-10-12T06:00:00Z"));
        assertThat(s.userId()).isEqualTo(22L);
        assertThat(s.start()).isEqualTo(Instant.parse("2026-10-12T06:00:00Z"));
        assertThat(s.end()).isEqualTo(Instant.parse("2026-10-19T06:00:00Z"));
        assertThat(OnCallRotation.current(WEEKLY, List.of(), Instant.parse("2026-10-26T07:00:00Z")).userId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("günlük dönem; başlamadan önce nöbetçi yok; kapalı ya da üyesiz çizelgede nöbetçi yok")
    void dailyAndEdges() {
        var daily = new OnCallRotation.Config(true, 1, ANCHOR, List.of(11L, 22L));
        assertThat(OnCallRotation.current(daily, List.of(), Instant.parse("2026-10-06T07:00:00Z")).userId()).isEqualTo(22L);
        assertThat(OnCallRotation.current(daily, List.of(), Instant.parse("2026-10-04T07:00:00Z")).userId()).isNull();
        assertThat(OnCallRotation.current(new OnCallRotation.Config(false, 7, ANCHOR, List.of(11L)), List.of(), Instant.now()).userId()).isNull();
        assertThat(OnCallRotation.current(new OnCallRotation.Config(true, 7, ANCHOR, List.of()), List.of(), Instant.now()).userId()).isNull();
    }

    @Test
    @DisplayName("yer değiştirme aralığında o kişi; çakışmada en son eklenen; aralık dışında rotasyon")
    void overrides() {
        var o1 = new OnCallRotation.Override(1, 99L, Instant.parse("2026-10-06T00:00:00Z"), Instant.parse("2026-10-08T00:00:00Z"));
        var o2 = new OnCallRotation.Override(2, 77L, Instant.parse("2026-10-07T00:00:00Z"), Instant.parse("2026-10-07T12:00:00Z"));
        OnCallRotation.Slot a = OnCallRotation.current(WEEKLY, List.of(o1, o2), Instant.parse("2026-10-06T10:00:00Z"));
        assertThat(a.userId()).isEqualTo(99L);
        assertThat(a.override()).isTrue();
        assertThat(OnCallRotation.current(WEEKLY, List.of(o1, o2), Instant.parse("2026-10-07T06:00:00Z")).userId()).isEqualTo(77L);
        assertThat(OnCallRotation.current(WEEKLY, List.of(o1, o2), Instant.parse("2026-10-08T00:00:00Z")).userId()).isEqualTo(11L);
        // 2026-10-12: yer değiştirme yalnız ETKİN çizelgede geçerli — çizelgesiz / kapalı takımda pencere de "nöbetçi yok" der
        // (bildirim, takım satırı ve 7/24 bu takımları zaten atlıyordu).
        assertThat(OnCallRotation.current(null, List.of(o1), Instant.parse("2026-10-06T10:00:00Z")).userId()).isNull();
        assertThat(OnCallRotation.current(new OnCallRotation.Config(false, 7, ANCHOR, List.of(11L)), List.of(o1),
                Instant.parse("2026-10-06T10:00:00Z")).userId()).isNull();
    }

    @Test
    @DisplayName("yaklaşan dilimler: sıralı, ardışık, sınırlı; başlamadıysa ilk dilim anchor'da")
    void upcoming() {
        List<OnCallRotation.Slot> next = OnCallRotation.upcoming(WEEKLY, Instant.parse("2026-10-13T00:00:00Z"), 4);
        assertThat(next).extracting(OnCallRotation.Slot::userId).containsExactly(22L, 33L, 11L, 22L);
        assertThat(next.get(1).start()).isEqualTo(next.get(0).end());
        assertThat(OnCallRotation.upcoming(WEEKLY, Instant.parse("2026-10-01T00:00:00Z"), 1).get(0).start())
                .isEqualTo(Instant.parse("2026-10-05T06:00:00Z"));
        assertThat(OnCallRotation.upcoming(WEEKLY, Instant.now(), 999)).hasSize(OnCallRotation.MAX_UPCOMING);
    }
}
