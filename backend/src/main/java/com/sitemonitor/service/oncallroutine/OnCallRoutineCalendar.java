package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncall.OnCallRotation;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Nöbet rutini takvimi (2026-11-26) — SAF hesap (Europe/Istanbul, UTC+3 sabit, yaz saati yok). İş günü, haftalık / aylık
 * görevin son günü, ISO hafta / ay anahtarları ve devir dönemi. Her döngü sınırlı (ay taraması ≤ 31, hafta ≤ 7, dönem ≤ 8).
 */
public final class OnCallRoutineCalendar {

    private OnCallRoutineCalendar() {}

    /** İş günü sınaması: haftanın günü ayarda VE (tatil atlanmıyorsa ya da tatil değilse). */
    public static boolean isWorkday(LocalDate d, OnCallRoutineSettings.Settings s, Set<LocalDate> holidays) {
        if (d == null || s == null) return false;
        if (!s.workdays().contains(d.getDayOfWeek().getValue())) return false;
        return !(s.skipHolidays() && holidays != null && holidays.contains(d));
    }

    public static Predicate<LocalDate> workdayTest(OnCallRoutineSettings.Settings s, Set<LocalDate> holidays) {
        return d -> isWorkday(d, s, holidays);
    }

    /** ISO haftanın Pazartesi'si. */
    public static LocalDate monday(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }

    public static String weekKey(LocalDate d) {
        return monday(d).toString();
    }

    public static String monthKey(LocalDate d) {
        return YearMonth.from(d).toString();
    }

    /**
     * Haftalık görevin son günü: haftanın {@code weekday} günü; iş günü değilse haftadaki en yakın ÖNCEKİ iş günü, yoksa
     * en yakın SONRAKİ; haftada iş günü yoksa görev o hafta yok.
     */
    public static Optional<LocalDate> weeklyDueDay(LocalDate monday, int weekday, Predicate<LocalDate> workday) {
        int w = weekday < 1 || weekday > 7 ? 5 : weekday;
        LocalDate target = monday.plusDays(w - 1L);
        if (workday.test(target)) return Optional.of(target);
        for (int i = w - 1; i >= 1; i--) {
            LocalDate d = monday.plusDays(i - 1L);
            if (workday.test(d)) return Optional.of(d);
        }
        for (int i = w + 1; i <= 7; i++) {
            LocalDate d = monday.plusDays(i - 1L);
            if (workday.test(d)) return Optional.of(d);
        }
        return Optional.empty();
    }

    /** Aylık görevin son günü (kural; ay içinde iş günü yoksa görev o ay yok). */
    public static Optional<LocalDate> monthlyDueDay(YearMonth m, String rule, Integer monthDay, Integer nth, Integer weekday,
                                                    Predicate<LocalDate> workday) {
        int len = m.lengthOfMonth();
        if ("FIRST_WORKDAY".equals(rule)) {
            for (int i = 1; i <= len; i++) {
                LocalDate d = m.atDay(i);
                if (workday.test(d)) return Optional.of(d);
            }
            return Optional.empty();
        }
        if ("LAST_WORKDAY".equals(rule)) {
            for (int i = len; i >= 1; i--) {
                LocalDate d = m.atDay(i);
                if (workday.test(d)) return Optional.of(d);
            }
            return Optional.empty();
        }
        LocalDate target;
        if ("DAY".equals(rule)) {
            int day = monthDay == null ? 1 : Math.max(1, monthDay);
            target = m.atDay(Math.min(day, len));
        } else if ("NTH_WEEKDAY".equals(rule)) {
            int w = weekday == null || weekday < 1 || weekday > 7 ? 1 : weekday;
            int n = nth == null || nth < 1 ? 1 : Math.min(nth, 5);
            List<LocalDate> matches = new ArrayList<>();
            for (int i = 1; i <= len; i++) {
                LocalDate d = m.atDay(i);
                if (d.getDayOfWeek().getValue() == w) matches.add(d);
            }
            if (matches.isEmpty()) return Optional.empty();
            target = n == 5 || n > matches.size() ? matches.get(matches.size() - 1) : matches.get(n - 1);
        } else {
            return Optional.empty();
        }
        if (workday.test(target)) return Optional.of(target);
        // iş günü değil → ay içinde sonraki iş günü, yoksa önceki
        for (LocalDate d = target.plusDays(1); !d.isAfter(m.atEndOfMonth()); d = d.plusDays(1)) {
            if (workday.test(d)) return Optional.of(d);
        }
        for (LocalDate d = target.minusDays(1); !d.isBefore(m.atDay(1)); d = d.minusDays(1)) {
            if (workday.test(d)) return Optional.of(d);
        }
        return Optional.empty();
    }

    /** Görevin bu dönemdeki son günü (DAILY: tarihin kendisi; dönem görevin değilse boş). */
    public static Optional<LocalDate> dueDay(OnCallRoutineTemplate.Task t, String periodKind, String periodKey,
                                             Predicate<LocalDate> workday) {
        try {
            switch (periodKind) {
                case OnCallRoutineCodes.KIND_DAY -> {
                    LocalDate d = LocalDate.parse(periodKey);
                    return workday.test(d) ? Optional.of(d) : Optional.empty();
                }
                case OnCallRoutineCodes.KIND_WEEK -> {
                    return weeklyDueDay(LocalDate.parse(periodKey), t.weekday() == null ? 5 : t.weekday(), workday);
                }
                case OnCallRoutineCodes.KIND_MONTH -> {
                    return monthlyDueDay(YearMonth.parse(periodKey), t.monthlyRule(), t.monthDay(), t.nth(), t.weekday(), workday);
                }
                default -> {
                    return Optional.empty();
                }
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Bir tarihin İstanbul "referans anı" (gün başlangıcı). */
    public static Instant at(LocalDate d, LocalTime t) {
        return LocalDateTime.of(d, t).atZone(OnCallRoutineCodes.TZ).toInstant();
    }

    public static LocalDate localDate(Instant i) {
        return i.atZone(OnCallRoutineCodes.TZ).toLocalDate();
    }

    // ── Devir dönemi (§1.5) ──────────────────────────────────────────────────────────────────────

    /**
     * Devir dönemi: {@code key} = dönemin ilk günü, {@code [start, end)} = dönem aralığı, {@code days} = referans anı
     * aralıkta olan yerel günler. {@code rotation} = dönem etkin HAFTALIK çizelgenin dilimi mi (yoksa ISO hafta).
     */
    public record Period(LocalDate key, Instant start, Instant end, List<LocalDate> days, boolean rotation) {
        public Period {
            days = days == null ? List.of() : List.copyOf(days);
        }

        public LocalDate first() { return days.isEmpty() ? key : days.get(0); }

        public LocalDate last() { return days.isEmpty() ? key : days.get(days.size() - 1); }

        public boolean contains(Instant i) { return !i.isBefore(start) && i.isBefore(end); }
    }

    /** Tarihin devir dönemi (referans anı = {@code date @ dayStart}). */
    public static Period handoverPeriod(OnCallRotation.Config cfg, LocalTime dayStart, LocalDate date) {
        return periodAt(cfg, dayStart, at(date, dayStart));
    }

    /**
     * Anı içeren devir dönemi. Etkin HAFTALIK çizelge (rotasyon başlamış, üyesi var) → temel rotasyon dilimi (yer
     * değiştirmeler yok sayılır); aksi halde ISO hafta {@code [Pzt @ dayStart, sonraki Pzt @ dayStart)}.
     */
    public static Period periodAt(OnCallRotation.Config cfg, LocalTime dayStart, Instant at) {
        if (cfg != null && cfg.enabled() && cfg.periodDays() >= 7 && cfg.anchor() != null
                && cfg.members() != null && !cfg.members().isEmpty()) {
            OnCallRotation.Slot slot = OnCallRotation.baseSlot(cfg, at);
            if (slot.userId() != null && slot.start() != null && slot.end() != null) {
                return build(slot.start(), slot.end(), dayStart, true);
            }
        }
        LocalDateTime ldt = LocalDateTime.ofInstant(at, OnCallRoutineCodes.TZ);
        LocalDate refDay = ldt.toLocalTime().isBefore(dayStart) ? ldt.toLocalDate().minusDays(1) : ldt.toLocalDate();
        LocalDate mon = monday(refDay);
        return build(at(mon, dayStart), at(mon.plusWeeks(1), dayStart), dayStart, false);
    }

    private static Period build(Instant start, Instant end, LocalTime dayStart, boolean rotation) {
        LocalDate sd = localDate(start);
        LocalDate key = !start.isAfter(at(sd, dayStart)) ? sd : sd.plusDays(1);
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            LocalDate d = key.plusDays(i);
            Instant ref = at(d, dayStart);
            if (!ref.isBefore(end)) break;
            if (!ref.isBefore(start)) days.add(d);
        }
        return new Period(key, start, end, days, rotation);
    }

    /** Önceki dönem (geçerli dönemin başlangıcından hemen önceki an). */
    public static Period previous(OnCallRotation.Config cfg, LocalTime dayStart, Period current) {
        return periodAt(cfg, dayStart, current.start().minusSeconds(1));
    }

    /** İki tarih arasındaki gün sayısı (ikisi dahil değil farkı). */
    public static long daysBetween(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b);
    }
}
