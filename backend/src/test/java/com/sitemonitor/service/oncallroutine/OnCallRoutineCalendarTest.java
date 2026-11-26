package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncall.OnCallRotation;
import com.sitemonitor.service.oncallroutine.OnCallRoutineCalendar.Period;
import com.sitemonitor.service.oncallroutine.OnCallRoutineSettings.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nöbet rutini takvimi (2026-11-26): iş günü + tatil, haftalık son gün kaydırması, aylık kurallar, ISO hafta yıl geçişi,
 * devir dönemleri (haftalık rotasyon dilimi / ISO hafta, gün başlangıcı), sınırlı döngüler.
 */
class OnCallRoutineCalendarTest {

    static final Settings WEEKDAYS = OnCallRoutineSettings.defaults();
    static final LocalTime NINE = LocalTime.of(9, 0);

    @Test
    @DisplayName("iş günü: hafta günü ayarda ve (tatil atlanmıyorsa ya da tatil değilse)")
    void workdays() {
        LocalDate fri = LocalDate.parse("2026-11-27");
        LocalDate sat = LocalDate.parse("2026-11-28");
        assertThat(OnCallRoutineCalendar.isWorkday(fri, WEEKDAYS, Set.of())).isTrue();
        assertThat(OnCallRoutineCalendar.isWorkday(sat, WEEKDAYS, Set.of())).isFalse();
        assertThat(OnCallRoutineCalendar.isWorkday(fri, WEEKDAYS, Set.of(fri))).isFalse();
        Settings noSkip = new Settings(true, List.of(1, 2, 3, 4, 5), false, "09:00", WEEKDAYS.reminders(),
                WEEKDAYS.completionNotice(), WEEKDAYS.handoverNotice());
        assertThat(OnCallRoutineCalendar.isWorkday(fri, noSkip, Set.of(fri))).isTrue();
        assertThat(OnCallRoutineCalendar.isWorkday(null, WEEKDAYS, Set.of())).isFalse();
    }

    @Test
    @DisplayName("haftalık son gün: tatil Cuma → Perşembe; haftanın başı tatilse sonraki iş günü; iş günü yoksa boş")
    void weeklyDue() {
        LocalDate mon = LocalDate.parse("2026-11-23");
        LocalDate fri = mon.plusDays(4);
        Predicate<LocalDate> plain = OnCallRoutineCalendar.workdayTest(WEEKDAYS, Set.of());
        assertThat(OnCallRoutineCalendar.weeklyDueDay(mon, 5, plain)).contains(fri);
        Predicate<LocalDate> holidayFri = OnCallRoutineCalendar.workdayTest(WEEKDAYS, Set.of(fri));
        assertThat(OnCallRoutineCalendar.weeklyDueDay(mon, 5, holidayFri)).contains(fri.minusDays(1));
        Predicate<LocalDate> holidayMon = OnCallRoutineCalendar.workdayTest(WEEKDAYS, Set.of(mon));
        assertThat(OnCallRoutineCalendar.weeklyDueDay(mon, 1, holidayMon)).contains(mon.plusDays(1));
        // Cumartesi hedefi: önceki iş günü (Cuma)
        assertThat(OnCallRoutineCalendar.weeklyDueDay(mon, 6, plain)).contains(fri);
        Predicate<LocalDate> none = d -> false;
        assertThat(OnCallRoutineCalendar.weeklyDueDay(mon, 5, none)).isEmpty();
        // geçersiz gün → Cuma
        assertThat(OnCallRoutineCalendar.weeklyDueDay(mon, 0, plain)).contains(fri);
    }

    @Test
    @DisplayName("aylık: ilk / son iş günü, ayın 31'i Şubat'ta son güne iner, tatil kaydırması, n. / son hafta günü")
    void monthlyDue() {
        Predicate<LocalDate> plain = OnCallRoutineCalendar.workdayTest(WEEKDAYS, Set.of());
        YearMonth feb = YearMonth.parse("2027-02");
        assertThat(OnCallRoutineCalendar.monthlyDueDay(feb, "FIRST_WORKDAY", null, null, null, plain))
                .contains(LocalDate.parse("2027-02-01"));
        assertThat(OnCallRoutineCalendar.monthlyDueDay(feb, "LAST_WORKDAY", null, null, null, plain))
                .contains(LocalDate.parse("2027-02-26"));
        // 31 → 28 Şubat (Pazar) → ay içinde sonraki iş günü yok → önceki iş günü 26'sı
        assertThat(OnCallRoutineCalendar.monthlyDueDay(feb, "DAY", 31, null, null, plain))
                .contains(LocalDate.parse("2027-02-26"));
        // tatil kaydırması: 15'i iş günüyse kendisi, tatilse sonraki iş günü
        YearMonth dec = YearMonth.parse("2026-12");
        LocalDate d15 = LocalDate.parse("2026-12-15");
        assertThat(OnCallRoutineCalendar.monthlyDueDay(dec, "DAY", 15, null, null, plain)).contains(d15);
        Predicate<LocalDate> hol15 = OnCallRoutineCalendar.workdayTest(WEEKDAYS, Set.of(d15));
        assertThat(OnCallRoutineCalendar.monthlyDueDay(dec, "DAY", 15, null, null, hol15)).contains(d15.plusDays(1));
        // NTH: Aralık 2026'nın 2. Salısı = 8'i; son (5) Cuma = 25'i
        assertThat(OnCallRoutineCalendar.monthlyDueDay(dec, "NTH_WEEKDAY", null, 2, 2, plain))
                .contains(LocalDate.parse("2026-12-08"));
        assertThat(OnCallRoutineCalendar.monthlyDueDay(dec, "NTH_WEEKDAY", null, 5, 5, plain))
                .contains(LocalDate.parse("2026-12-25"));
        assertThat(OnCallRoutineCalendar.monthlyDueDay(dec, "WHATEVER", null, null, null, plain)).isEmpty();
        assertThat(OnCallRoutineCalendar.monthlyDueDay(dec, "FIRST_WORKDAY", null, null, null, d -> false)).isEmpty();
    }

    @Test
    @DisplayName("ISO hafta / ay anahtarları yıl geçişinde")
    void keysAcrossYears() {
        LocalDate d = LocalDate.parse("2027-01-01");   // Cuma — haftanın Pazartesi'si 2026-12-28
        assertThat(OnCallRoutineCalendar.weekKey(d)).isEqualTo("2026-12-28");
        assertThat(OnCallRoutineCalendar.monthKey(d)).isEqualTo("2027-01");
    }

    @Test
    @DisplayName("devir dönemi — çizelge yok: ISO hafta [Pzt 09:00, sonraki Pzt 09:00), Pazartesi 08:00 önceki haftaya ait")
    void handoverIsoWeek() {
        LocalDate wed = LocalDate.parse("2026-11-25");
        Period p = OnCallRoutineCalendar.handoverPeriod(null, NINE, wed);
        assertThat(p.rotation()).isFalse();
        assertThat(p.key()).isEqualTo(LocalDate.parse("2026-11-23"));
        assertThat(p.days()).hasSize(7);
        assertThat(p.first()).isEqualTo(LocalDate.parse("2026-11-23"));
        assertThat(p.last()).isEqualTo(LocalDate.parse("2026-11-29"));
        Instant mon0800 = LocalDateTime.parse("2026-11-30T08:00").atZone(OnCallRoutineCodes.TZ).toInstant();
        assertThat(OnCallRoutineCalendar.periodAt(null, NINE, mon0800).key()).isEqualTo(LocalDate.parse("2026-11-23"));
        Period prev = OnCallRoutineCalendar.previous(null, NINE, p);
        assertThat(prev.key()).isEqualTo(LocalDate.parse("2026-11-16"));
        assertThat(prev.end()).isEqualTo(p.start());
    }

    @Test
    @DisplayName("devir dönemi — haftalık rotasyon: Çarşamba 09:00 çapası dilimi; gün başlangıcı 08:00 iken ilk gün çapa günü")
    void handoverRotation() {
        OnCallRotation.Config wedAnchor = new OnCallRotation.Config(true, 7, LocalDateTime.parse("2026-11-04T09:00"), List.of(1L, 2L));
        Period p = OnCallRoutineCalendar.handoverPeriod(wedAnchor, NINE, LocalDate.parse("2026-11-26"));   // Perşembe
        assertThat(p.rotation()).isTrue();
        assertThat(p.key()).isEqualTo(LocalDate.parse("2026-11-25"));   // dilim Çarşamba 09:00'da başlar
        assertThat(p.days()).containsExactly(LocalDate.parse("2026-11-25"), LocalDate.parse("2026-11-26"),
                LocalDate.parse("2026-11-27"), LocalDate.parse("2026-11-28"), LocalDate.parse("2026-11-29"),
                LocalDate.parse("2026-11-30"), LocalDate.parse("2026-12-01"));
        // gün başlangıcı 10:00: çapa (09:00) gün başlangıcından önce → çapa günü dönemin ilk günü
        Period q = OnCallRoutineCalendar.handoverPeriod(wedAnchor, LocalTime.of(10, 0), LocalDate.parse("2026-11-26"));
        assertThat(q.key()).isEqualTo(LocalDate.parse("2026-11-25"));
        // gün başlangıcı 08:00: çapa günü 08:00 dilimden önce → dönemin ilk günü ertesi gün
        Period r = OnCallRoutineCalendar.handoverPeriod(wedAnchor, LocalTime.of(8, 0), LocalDate.parse("2026-11-27"));
        assertThat(r.key()).isEqualTo(LocalDate.parse("2026-11-26"));
        // günlük rotasyon → ISO hafta
        OnCallRotation.Config daily = new OnCallRotation.Config(true, 1, LocalDateTime.parse("2026-11-04T09:00"), List.of(1L, 2L));
        assertThat(OnCallRoutineCalendar.handoverPeriod(daily, NINE, LocalDate.parse("2026-11-26")).rotation()).isFalse();
        // kapalı çizelge → ISO hafta
        OnCallRotation.Config off = new OnCallRotation.Config(false, 7, LocalDateTime.parse("2026-11-04T09:00"), List.of(1L));
        assertThat(OnCallRoutineCalendar.handoverPeriod(off, NINE, LocalDate.parse("2026-11-26")).key())
                .isEqualTo(LocalDate.parse("2026-11-23"));
    }

    @Test
    @DisplayName("görevin dönem son günü: DAILY iş günü değilse boş, WEEK / MONTH kuralı, bozuk anahtar boş")
    void dueDay() {
        Predicate<LocalDate> plain = OnCallRoutineCalendar.workdayTest(WEEKDAYS, Set.of());
        OnCallRoutineTemplate.Task weekly = new OnCallRoutineTemplate.Task("t1", "x", null, List.of(), List.of(), true, true,
                "WEEKLY", 3, null, null, null, null, null);
        assertThat(OnCallRoutineCalendar.dueDay(weekly, "WEEK", "2026-11-23", plain)).contains(LocalDate.parse("2026-11-25"));
        assertThat(OnCallRoutineCalendar.dueDay(weekly, "DAY", "2026-11-28", plain)).isEmpty();
        assertThat(OnCallRoutineCalendar.dueDay(weekly, "WEEK", "bozuk", plain)).isEmpty();
        assertThat(OnCallRoutineCalendar.dueDay(weekly, "YEAR", "2026", plain)).isEqualTo(Optional.empty());
    }
}
