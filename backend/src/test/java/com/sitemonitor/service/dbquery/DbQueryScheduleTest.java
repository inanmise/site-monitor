package com.sitemonitor.service.dbquery;

import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.service.dbquery.DbQuerySchedule.FieldError;
import com.sitemonitor.service.dbquery.DbQuerySchedule.Spec;
import com.sitemonitor.service.dbquery.DbQuerySchedule.Window;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Saf zamanlama: aralık / cron, İstanbul saati, izinli / yasak pencereler (gece yarısını aşan dahil), tatil + ek tarih. */
class DbQueryScheduleTest {

    /** 2026-11-02 Pazartesi. */
    private static Instant ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(DbQuerySchedule.ZONE).toInstant();
    }

    private static String local(Instant t) {
        return ZonedDateTime.ofInstant(t, DbQuerySchedule.ZONE).toLocalDateTime().toString();
    }

    private static Spec interval(int s, List<Window> windows, List<Window> blackouts) {
        return new Spec("INTERVAL", s, null, windows, blackouts, false, List.of());
    }

    private static Spec cron(String c, List<Window> windows) {
        return new Spec("CRON", null, c, windows, List.of(), false, List.of());
    }

    private static final Set<LocalDate> NO_HOLIDAYS = Set.of();

    @Test
    void intervalWithoutFilters() {
        Spec s = interval(300, List.of(), List.of());
        Instant now = ist("2026-11-02T10:00:00");
        assertThat(DbQuerySchedule.firstDue(s, now, NO_HOLIDAYS)).isEqualTo(now);
        assertThat(local(DbQuerySchedule.nextAfter(s, now, NO_HOLIDAYS))).isEqualTo("2026-11-02T10:05");
        assertThat(DbQuerySchedule.effectiveIntervalSeconds(s, now, NO_HOLIDAYS)).isEqualTo(300);
        assertThat(DbQuerySchedule.nextRuns(s, now, 3, NO_HOLIDAYS)).extracting(DbQueryScheduleTest::local)
                .containsExactly("2026-11-02T10:00", "2026-11-02T10:05", "2026-11-02T10:10");
    }

    @Test
    void intervalSkipsToNextWindow() {
        Window weekdays = new Window(List.of(1, 2, 3, 4, 5), "09:00", "18:00");
        Spec s = interval(3600, List.of(weekdays), List.of());
        assertThat(local(DbQuerySchedule.nextAfter(s, ist("2026-11-02T17:30:00"), NO_HOLIDAYS))).isEqualTo("2026-11-03T09:00");
        // Cuma 17:30 → Pazartesi 09:00
        assertThat(local(DbQuerySchedule.nextAfter(s, ist("2026-11-06T17:30:00"), NO_HOLIDAYS))).isEqualTo("2026-11-09T09:00");
        assertThat(local(DbQuerySchedule.firstDue(s, ist("2026-11-07T12:00:00"), NO_HOLIDAYS))).isEqualTo("2026-11-09T09:00");
        assertThat(DbQuerySchedule.allowedAt(s, ist("2026-11-02T08:59:59"), NO_HOLIDAYS)).isFalse();
        assertThat(DbQuerySchedule.allowedAt(s, ist("2026-11-02T09:00:00"), NO_HOLIDAYS)).isTrue();
        assertThat(DbQuerySchedule.allowedAt(s, ist("2026-11-02T18:00:00"), NO_HOLIDAYS)).isFalse();
    }

    @Test
    void windowCrossingMidnight() {
        // Pazartesi 22:00 → Salı 06:00 (gün = başladığı gün)
        Window night = new Window(List.of(1), "22:00", "06:00");
        Spec s = interval(600, List.of(night), List.of());
        assertThat(DbQuerySchedule.allowedAt(s, ist("2026-11-02T23:30:00"), NO_HOLIDAYS)).isTrue();
        assertThat(DbQuerySchedule.allowedAt(s, ist("2026-11-03T05:59:00"), NO_HOLIDAYS)).isTrue();
        assertThat(DbQuerySchedule.allowedAt(s, ist("2026-11-03T06:00:00"), NO_HOLIDAYS)).isFalse();
        assertThat(DbQuerySchedule.allowedAt(s, ist("2026-11-03T23:00:00"), NO_HOLIDAYS)).isFalse();   // Salı başlangıcı yok
        assertThat(local(DbQuerySchedule.nextAfter(s, ist("2026-11-03T05:55:00"), NO_HOLIDAYS))).isEqualTo("2026-11-09T22:00");
        // 24:00 gün sonu
        Window late = new Window(List.of(), "23:00", "24:00");
        assertThat(DbQuerySchedule.allowedAt(interval(60, List.of(late), List.of()), ist("2026-11-02T23:59:30"), NO_HOLIDAYS)).isTrue();
    }

    @Test
    void blackoutsHolidaysAndExtraDates() {
        Window lunch = new Window(List.of(), "12:00", "13:00");
        Spec s = new Spec("INTERVAL", 1800, null, List.of(), List.of(lunch), true, List.of("2026-11-04"));
        Set<LocalDate> holidays = Set.of(LocalDate.parse("2026-11-03"));
        assertThat(local(DbQuerySchedule.nextAfter(s, ist("2026-11-02T11:45:00"), holidays))).isEqualTo("2026-11-02T13:00");
        // Salı tatil, Çarşamba ek tarih → Perşembe 00:00
        assertThat(local(DbQuerySchedule.nextAfter(s, ist("2026-11-02T23:45:00"), holidays))).isEqualTo("2026-11-05T00:00");
        // tatili yok sayan izleme o gün çalışır
        Spec noSkip = new Spec("INTERVAL", 1800, null, List.of(), List.of(lunch), false, List.of());
        assertThat(DbQuerySchedule.allowedAt(noSkip, ist("2026-11-03T10:00:00"), holidays)).isTrue();
    }

    @Test
    void cronFiveSixFieldsAndMacros() {
        Instant now = ist("2026-11-02T07:30:00");
        assertThat(local(DbQuerySchedule.nextAfter(cron("0 8 * * 1-5", List.of()), now, NO_HOLIDAYS))).isEqualTo("2026-11-02T08:00");
        assertThat(local(DbQuerySchedule.nextAfter(cron("30 0 8 * * MON-FRI", List.of()), now, NO_HOLIDAYS)))
                .isEqualTo("2026-11-02T08:00:30");
        assertThat(local(DbQuerySchedule.nextAfter(cron("@hourly", List.of()), now, NO_HOLIDAYS))).isEqualTo("2026-11-02T08:00");
        assertThat(local(DbQuerySchedule.nextAfter(cron("@daily", List.of()), now, NO_HOLIDAYS))).isEqualTo("2026-11-03T00:00");
        assertThat(local(DbQuerySchedule.nextAfter(cron("@monthly", List.of()), now, NO_HOLIDAYS))).isEqualTo("2026-12-01T00:00");
        assertThat(DbQuerySchedule.parseCron("@yearly")).isNull();
        assertThat(DbQuerySchedule.parseCron("0 8 * *")).isNull();
        assertThat(DbQuerySchedule.parseCron("bozuk ifade x y z")).isNull();
        // cron + pencere: hafta sonu tetikleri atlanır
        Spec weekdaysOnly = cron("0 */4 * * *", List.of(new Window(List.of(1, 2, 3, 4, 5), "00:00", "24:00")));
        assertThat(local(DbQuerySchedule.nextAfter(weekdaysOnly, ist("2026-11-06T21:00:00"), NO_HOLIDAYS)))
                .isEqualTo("2026-11-09T00:00");
        assertThat(DbQuerySchedule.effectiveIntervalSeconds(cron("0 */4 * * *", List.of()), now, NO_HOLIDAYS)).isEqualTo(4 * 3600);
        // firstDue = şimdiden sonraki ilk tetik
        assertThat(local(DbQuerySchedule.firstDue(cron("@hourly", List.of()), ist("2026-11-02T08:00:00"), NO_HOLIDAYS)))
                .isEqualTo("2026-11-02T09:00");
    }

    @Test
    void validateRules() {
        Instant now = ist("2026-11-02T10:00:00");
        assertThat(DbQuerySchedule.validate(interval(300, List.of(), List.of()), 60, now, NO_HOLIDAYS)).isEmpty();
        assertThat(codes(DbQuerySchedule.validate(interval(30, List.of(), List.of()), 60, now, NO_HOLIDAYS)))
                .containsExactly("interval_seconds:" + DbQuerySchedule.CODE_INTERVAL);
        assertThat(codes(DbQuerySchedule.validate(interval(120, List.of(), List.of()), 300, now, NO_HOLIDAYS)))
                .containsExactly("interval_seconds:" + DbQuerySchedule.CODE_INTERVAL);
        assertThat(codes(DbQuerySchedule.validate(interval(8 * 86400, List.of(), List.of()), 60, now, NO_HOLIDAYS)))
                .containsExactly("interval_seconds:" + DbQuerySchedule.CODE_INTERVAL);
        assertThat(codes(DbQuerySchedule.validate(cron("* * * * * *", List.of()), 60, now, NO_HOLIDAYS)))
                .containsExactly("cron_expression:" + DbQuerySchedule.CODE_CRON_TOO_FREQUENT);
        assertThat(codes(DbQuerySchedule.validate(cron("*/5 * * * *", List.of()), 600, now, NO_HOLIDAYS)))
                .containsExactly("cron_expression:" + DbQuerySchedule.CODE_CRON_TOO_FREQUENT);
        assertThat(DbQuerySchedule.validate(cron("*/5 * * * *", List.of()), 60, now, NO_HOLIDAYS)).isEmpty();
        assertThat(codes(DbQuerySchedule.validate(cron("x", List.of()), 60, now, NO_HOLIDAYS)))
                .containsExactly("cron_expression:" + DbQuerySchedule.CODE_CRON_INVALID);
        assertThat(codes(DbQuerySchedule.validate(new Spec("NOPE", 300, null, List.of(), List.of(), false, List.of()), 60,
                now, NO_HOLIDAYS))).containsExactly("schedule:" + DbQuerySchedule.CODE_MODE);
        assertThat(codes(DbQuerySchedule.validate(interval(300, List.of(new Window(List.of(8), "09:00", "10:00")), List.of()),
                60, now, NO_HOLIDAYS))).containsExactly("windows:" + DbQuerySchedule.CODE_WINDOW);
        assertThat(codes(DbQuerySchedule.validate(interval(300, List.of(), List.of(new Window(List.of(), "25:00", "10:00"))),
                60, now, NO_HOLIDAYS))).containsExactly("blackouts:" + DbQuerySchedule.CODE_WINDOW);
        assertThat(codes(DbQuerySchedule.validate(new Spec("INTERVAL", 300, null, List.of(), List.of(), false,
                List.of("2026-13-01")), 60, now, NO_HOLIDAYS))).containsExactly("skip_dates:" + DbQuerySchedule.CODE_SKIP_DATE);
        // hiç çalışmaz: tüm gün yasak
        Spec never = interval(300, List.of(), List.of(new Window(List.of(), "00:00", "24:00")));
        assertThat(codes(DbQuerySchedule.validate(never, 60, now, NO_HOLIDAYS)))
                .containsExactly("schedule:" + DbQuerySchedule.CODE_NEVER_RUNS);
        // Şubat 30 hiç gelmez
        assertThat(codes(DbQuerySchedule.validate(cron("0 0 30 2 *", List.of()), 60, now, NO_HOLIDAYS)))
                .containsExactly("cron_expression:" + DbQuerySchedule.CODE_NEVER_RUNS);
        assertThat(DbQuerySchedule.validate(interval(30, List.of(), List.of()), 60, now, NO_HOLIDAYS).get(0).message())
                .contains("·");
    }

    private static List<String> codes(List<FieldError> errors) {
        return errors.stream().map(e -> e.field() + ":" + e.code()).toList();
    }

    @Test
    void apiAndEntityRoundTrip() {
        Map<String, Object> body = new HashMap<>();
        body.put("schedule_mode", "cron");
        body.put("cron_expression", " 0 8 * * 1-5 ");
        body.put("windows", List.of(Map.of("days", List.of(1, 2, 3), "from", "08:00", "to", "20:00")));
        body.put("blackouts", List.of(Map.of("days", List.of(), "from", "12:00", "to", "13:00")));
        body.put("skip_holidays", true);
        body.put("skip_dates", List.of("2026-12-31"));
        Spec spec = DbQuerySchedule.fromApi(body);
        assertThat(spec.mode()).isEqualTo("CRON");
        DbQueryMonitor m = new DbQueryMonitor();
        Instant now = ist("2026-11-02T07:00:00");
        DbQuerySchedule.apply(spec, m, now, NO_HOLIDAYS);
        assertThat(m.getScheduleMode()).isEqualTo("CRON");
        assertThat(m.getCronExpression()).isEqualTo("0 8 * * 1-5");
        assertThat(m.getWindowsJson()).isEqualTo("[{\"days\":[1,2,3],\"from\":\"08:00\",\"to\":\"20:00\"}]");
        assertThat(m.getSkipHolidays()).isTrue();
        assertThat(m.getSkipDatesJson()).isEqualTo("[\"2026-12-31\"]");
        assertThat(m.getIntervalSeconds()).isGreaterThanOrEqualTo(86_400);   // hafta içi 08:00 → medyan 1 gün
        Spec back = DbQuerySchedule.of(m);
        assertThat(back.cron()).isEqualTo("0 8 * * 1-5");
        assertThat(back.windows()).isEqualTo(spec.windows());
        assertThat(back.blackouts()).isEqualTo(spec.blackouts());
        assertThat(back.skipDates()).isEqualTo(List.of("2026-12-31"));
        // interval kipi: aralık olduğu gibi, cron temizlenir
        Spec iv = DbQuerySchedule.fromApi(Map.of("schedule_mode", "INTERVAL", "interval_seconds", "600"));
        DbQuerySchedule.apply(iv, m, now, NO_HOLIDAYS);
        assertThat(m.getIntervalSeconds()).isEqualTo(600);
        assertThat(m.getCronExpression()).isNull();
        assertThat(m.getWindowsJson()).isNull();
        // bozuk JSON kolonları gevşek okunur
        m.setWindowsJson("{bozuk");
        m.setSkipDatesJson("[\"x\", \"2026-01-01\"]");
        assertThat(DbQuerySchedule.of(m).windows()).isEmpty();
        assertThat(DbQuerySchedule.of(m).skipDates()).containsExactly("2026-01-01");
    }

    @Test
    void malformedApiValuesAreRejectedByValidate() {
        Map<String, Object> body = new HashMap<>();
        body.put("schedule_mode", "INTERVAL");
        body.put("interval_seconds", 300);
        body.put("windows", List.of("bozuk"));
        assertThat(codes(DbQuerySchedule.validate(DbQuerySchedule.fromApi(body), 60, Instant.now(), NO_HOLIDAYS)))
                .containsExactly("windows:" + DbQuerySchedule.CODE_WINDOW);
        body.put("windows", List.of(Map.of("days", "pazartesi", "from", "09:00", "to", "10:00")));
        assertThat(codes(DbQuerySchedule.validate(DbQuerySchedule.fromApi(body), 60, Instant.now(), NO_HOLIDAYS)))
                .containsExactly("windows:" + DbQuerySchedule.CODE_WINDOW);
    }

    @Test
    void searchesAreBounded() {
        // Yılda tek bir dakika izinli (29 Şubat yok → 2027'de hiç) + her dakika cron: sonuç null, ama hızla
        Spec rare = new Spec("CRON", null, "0 * * * * *", List.of(new Window(List.of(7), "03:00", "03:01")),
                List.of(new Window(List.of(), "00:00", "24:00")), false, List.of());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            assertThat(DbQuerySchedule.nextAfter(rare, ist("2026-11-02T00:00:00"), NO_HOLIDAYS)).isNull();
            List<String> manyDates = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) manyDates.add(LocalDate.parse("2026-11-02").plusDays(i).toString());
            Spec dense = new Spec("INTERVAL", 60, null, List.of(new Window(List.of(1), "10:00", "10:01")), List.of(), true,
                    manyDates);
            Instant t = DbQuerySchedule.nextAfter(dense, ist("2026-11-02T00:00:00"), NO_HOLIDAYS);
            assertThat(t).isNotNull();
            assertThat(local(t)).isEqualTo("2027-02-15T10:00");
            assertThat(DbQuerySchedule.nextRuns(cron("@hourly", List.of()), Instant.now(), 100, NO_HOLIDAYS)).hasSize(100);
        });
    }
}
