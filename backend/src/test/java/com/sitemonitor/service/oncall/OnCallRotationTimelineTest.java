package com.sitemonitor.service.oncall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nöbet zaman çizelgesi (2026-11-15, haftalık nöbet bildirimi): bir aralığın her devri ve yer değiştirmesi, {@code current}
 * ile aynı öncelik kuralı; aynı kişinin art arda parçaları birleşir; sınır tavanı.
 */
class OnCallRotationTimelineTest {

    private static final Instant MON = Instant.parse("2026-11-16T05:30:00Z");          // Pazartesi 08:30 İstanbul
    private static final Instant NEXT_MON = Instant.parse("2026-11-23T05:30:00Z");

    @Test
    @DisplayName("haftalık rotasyon hafta ortasında devrediyorsa iki parça")
    void weeklyHandoverMidWeek() {
        // ilk devir Çarşamba 09:00 İstanbul; 1 → 2 → 1 …
        OnCallRotation.Config c = new OnCallRotation.Config(true, 7, LocalDateTime.parse("2026-11-04T09:00"), List.of(1L, 2L));
        List<OnCallRotation.Slot> line = OnCallRotation.timeline(c, List.of(), MON, NEXT_MON, 50);
        assertThat(line).hasSize(2);
        assertThat(line.get(0).userId()).isEqualTo(2L);
        assertThat(line.get(0).start()).isEqualTo(MON);
        assertThat(line.get(0).end()).isEqualTo(Instant.parse("2026-11-18T06:00:00Z"));
        assertThat(line.get(1).userId()).isEqualTo(1L);
        assertThat(line.get(1).end()).isEqualTo(NEXT_MON);
    }

    @Test
    @DisplayName("yer değiştirme hafta içinde bir parça açar; en son eklenen kazanır; aynı kişi birleşir")
    void overrideInsideWeek() {
        OnCallRotation.Config c = new OnCallRotation.Config(true, 7, LocalDateTime.parse("2026-11-16T08:30"), List.of(1L));
        List<OnCallRotation.Override> ovs = List.of(
                new OnCallRotation.Override(10, 3L, Instant.parse("2026-11-18T06:00:00Z"), Instant.parse("2026-11-19T06:00:00Z")),
                new OnCallRotation.Override(11, 4L, Instant.parse("2026-11-18T18:00:00Z"), Instant.parse("2026-11-19T06:00:00Z")));
        List<OnCallRotation.Slot> line = OnCallRotation.timeline(c, ovs, MON, NEXT_MON, 50);
        assertThat(line).extracting(OnCallRotation.Slot::userId).containsExactly(1L, 3L, 4L, 1L);
        assertThat(line.get(1).override()).isTrue();
        assertThat(line.get(2).override()).isTrue();
        assertThat(line.get(2).overrideId()).isEqualTo(11L);
        assertThat(line.get(3).start()).isEqualTo(Instant.parse("2026-11-19T06:00:00Z"));
        assertThat(line.get(3).override()).isFalse();
    }

    @Test
    @DisplayName("günlük rotasyon tek kişiyse tek parça (birleşir); kapalı çizelge = tek boş parça; günlük çok kişi en çok 8 parça")
    void mergeAndDisabled() {
        OnCallRotation.Config one = new OnCallRotation.Config(true, 1, LocalDateTime.parse("2026-11-01T09:00"), List.of(5L));
        assertThat(OnCallRotation.timeline(one, List.of(), MON, NEXT_MON, 50)).singleElement()
                .satisfies(s -> assertThat(s.userId()).isEqualTo(5L));
        OnCallRotation.Config off = new OnCallRotation.Config(false, 7, LocalDateTime.parse("2026-11-01T09:00"), List.of(5L));
        assertThat(OnCallRotation.timeline(off, List.of(), MON, NEXT_MON, 50)).singleElement()
                .satisfies(s -> assertThat(s.userId()).isNull());
        OnCallRotation.Config daily = new OnCallRotation.Config(true, 1, LocalDateTime.parse("2026-11-01T09:00"), List.of(1L, 2L, 3L));
        List<OnCallRotation.Slot> line = OnCallRotation.timeline(daily, List.of(), MON, NEXT_MON, 50);
        assertThat(line).hasSize(8);
        assertThat(OnCallRotation.timeline(daily, List.of(), MON, NEXT_MON, 3)).hasSize(3);
    }

    @Test
    @DisplayName("rotasyon hafta ortasında başlıyorsa önce boş parça")
    void anchorInsideWeek() {
        OnCallRotation.Config c = new OnCallRotation.Config(true, 7, LocalDateTime.parse("2026-11-19T09:00"), List.of(1L));
        List<OnCallRotation.Slot> line = OnCallRotation.timeline(c, List.of(), MON, NEXT_MON, 50);
        assertThat(line).extracting(OnCallRotation.Slot::userId).containsExactly(null, 1L);
        assertThat(line.get(1).start()).isEqualTo(Instant.parse("2026-11-19T06:00:00Z"));
    }
}
