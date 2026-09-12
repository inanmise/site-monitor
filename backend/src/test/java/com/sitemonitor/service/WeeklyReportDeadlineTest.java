package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Haftalık rapor son giriş zamanı ayrıştırma (2026-09-12) — bozuk değer sessizce varsayılana düşer. */
class WeeklyReportDeadlineTest {

    @Test
    @DisplayName("boş → Cuma 15:00 (geçerli); FRI/17:30 → Cuma 17:30; kısa saat 9:05 kabul")
    void parse_defaultsAndValid() {
        WeeklyReportDeadline d = WeeklyReportDeadline.parse(null, null);
        assertThat(d.day()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(d.time()).isEqualTo(LocalTime.of(15, 0));
        assertThat(d.valid()).isTrue();
        assertThat(d.dayCode()).isEqualTo("FRI");
        assertThat(d.timeText()).isEqualTo("15:00");
        assertThat(d.dayNameTr()).isEqualTo("Cuma");
        assertThat(d.dayNameEn()).isEqualTo("Friday");

        WeeklyReportDeadline t = WeeklyReportDeadline.parse("thu", "17:30");
        assertThat(t.day()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(t.timeText()).isEqualTo("17:30");
        assertThat(WeeklyReportDeadline.parse("MONDAY", "9:05").timeText()).isEqualTo("09:05");
    }

    @Test
    @DisplayName("bozuk gün/saat → varsayılan ama valid=false (ayar ekranı uyarabilsin, sayfa kırılmasın)")
    void parse_invalidFallsBack() {
        WeeklyReportDeadline d = WeeklyReportDeadline.parse("Funday", "25:99");
        assertThat(d.day()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(d.time()).isEqualTo(LocalTime.of(15, 0));
        assertThat(d.valid()).isFalse();
        assertThat(WeeklyReportDeadline.parse("SAT", "yarım").valid()).isFalse();
        assertThat(WeeklyReportDeadline.parse("SAT", "yarım").day()).isEqualTo(DayOfWeek.SATURDAY);
    }
}
