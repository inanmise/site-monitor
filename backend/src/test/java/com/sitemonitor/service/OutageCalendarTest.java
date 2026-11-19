package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutageCalendar} — saf hesabın doğruluk tablosu. Zamanlar İSTANBUL yerel saati olarak yazılır ({@code +03:00},
 * yaz saati yok); hesap podun saat dilimine bağlı DEĞİLDİR (yalnız ofset). Ay: 2026-11 (30 gün).
 */
class OutageCalendarTest {

    private static final YearMonth NOV = YearMonth.of(2026, 11);

    /** İstanbul yerel "yyyy-MM-ddTHH:mm" → epoch ms. */
    private static long ist(String local) {
        return LocalDateTime.parse(local).toInstant(ZoneOffset.ofHours(3)).toEpochMilli();
    }

    private static OutageCalendar.Interval iv(String start, String end, String level) {
        return new OutageCalendar.Interval(ist(start), end == null ? null : ist(end), level);
    }

    private static OutageCalendar.Day day(OutageCalendar.Result r, int d) {
        return r.days().get(d - 1);
    }

    /** "Şimdi" ay bittikten sonra — tüm günler geçmiş. */
    private static final long AFTER = ist("2026-12-05T12:00");

    /**
     * Doğruluk tablosu — her satır: alarmlar ({@code başlangıç>bitiş@seviye}, {@code ;} ayraçlı, bitiş {@code -} = açık),
     * şimdi, gün, beklenen dakika ({@code null} = gelecek), alarm sayısı, en kötü seviye ({@code -} = yok).
     */
    @ParameterizedTest(name = "{0} | gün {2} → {3} dk, {4} alarm, {5}")
    @CsvSource(delimiter = '|', textBlock = """
            # tek alarm, aynı gün 10:00–11:30 → 90 dk
            2026-11-10T10:00>2026-11-10T11:30@HIGH                                        | 2026-12-05T12:00 | 10 | 90   | 1 | HIGH
            # ÇAKIŞAN iki alarm: 10:00–11:00 ve 10:30–12:00 → birleşim 10:00–12:00 = 120 dk (bir kez)
            2026-11-10T10:00>2026-11-10T11:00@WARNING;2026-11-10T10:30>2026-11-10T12:00@CRITICAL | 2026-12-05T12:00 | 10 | 120 | 2 | CRITICAL
            # ayrık iki alarm aynı gün → toplanır (30 + 30)
            2026-11-10T01:00>2026-11-10T01:30@HIGH;2026-11-10T20:00>2026-11-10T20:30@WARNING | 2026-12-05T12:00 | 10 | 60 | 2 | HIGH
            # İstanbul gece yarısını geçen alarm: 23:00 → ertesi 01:00 — ilk gün 60 dk
            2026-11-10T23:00>2026-11-11T01:00@HIGH                                        | 2026-12-05T12:00 | 10 | 60   | 1 | HIGH
            # aynı alarmın ikinci günü 60 dk
            2026-11-10T23:00>2026-11-11T01:00@HIGH                                        | 2026-12-05T12:00 | 11 | 60   | 1 | HIGH
            # birkaç gün süren alarm — ortadaki gün tam 1440 dk
            2026-11-12T12:00>2026-11-15T06:00@CRITICAL                                    | 2026-12-05T12:00 | 13 | 1440 | 1 | CRITICAL
            # aynı alarmın son günü 06:00'ya kadar = 360 dk
            2026-11-12T12:00>2026-11-15T06:00@CRITICAL                                    | 2026-12-05T12:00 | 15 | 360  | 1 | CRITICAL
            # önceki ayda başlayan alarm: 31 Ekim 22:00 → 1 Kasım 02:00 — 1 Kasım 120 dk
            2026-10-31T22:00>2026-11-01T02:00@HIGH                                        | 2026-12-05T12:00 | 1  | 120  | 1 | HIGH
            # günün tam başında biten alarm o güne SAYILMAZ
            2026-11-09T22:00>2026-11-10T00:00@HIGH                                        | 2026-12-05T12:00 | 10 | 0    | 0 | -
            # açık alarm: 20 Kasım 09:00'da açıldı, şimdi 20 Kasım 15:30 → bugün 390 dk
            2026-11-20T09:00>-@CRITICAL                                                   | 2026-11-20T15:30 | 20 | 390  | 1 | CRITICAL
            # bugün kısmen geçti, alarm yok → 0 (geçmiş kısmı bilinen)
            2026-11-02T09:00>2026-11-02T09:10@HIGH                                        | 2026-11-20T15:30 | 20 | 0    | 0 | -
            # gelecek gün (şimdi 20 Kasım) → null, 0 alarm, seviye yok
            2026-11-20T09:00>-@CRITICAL                                                   | 2026-11-20T15:30 | 21 | null | 0 | -
            # aynı anda açılıp kapanan (0 sn) alarm → 0 dk ama alarm o güne sayılır
            2026-11-05T10:00>2026-11-05T10:00@WARNING                                     | 2026-12-05T12:00 | 5  | 0    | 1 | WARNING
            # gelecekte açılmış alarm (saat kayması) bugüne sayılmaz
            2026-11-20T16:00>-@CRITICAL                                                   | 2026-11-20T15:30 | 20 | 0    | 0 | -
            # bilinmeyen seviye: sayılır, en kötü seviye boş
            2026-11-03T10:00>2026-11-03T10:05@INFO                                        | 2026-12-05T12:00 | 3  | 5    | 1 | -
            # bitişi başlangıçtan önce (bozuk saat) → sıfır uzunluk, açıldığı gün sayılır
            2026-11-04T10:00>2026-11-04T09:00@HIGH                                        | 2026-12-05T12:00 | 4  | 0    | 1 | HIGH
            """)
    void truthTable(String alarms, String now, int dayOfMonth, String minutes, int count, String worst) {
        List<OutageCalendar.Interval> list = new ArrayList<>();
        for (String a : alarms.split(";")) {
            String[] lvl = a.split("@");
            String[] se = lvl[0].split(">");
            list.add(iv(se[0].trim(), "-".equals(se[1].trim()) ? null : se[1].trim(), lvl[1].trim()));
        }
        OutageCalendar.Result r = OutageCalendar.compute(NOV, list, ist(now));
        OutageCalendar.Day d = day(r, dayOfMonth);
        assertThat(d.day()).isEqualTo(LocalDate.of(2026, 11, dayOfMonth));
        assertThat(d.outageMinutes()).isEqualTo("null".equals(minutes) ? null : Integer.valueOf(minutes));
        assertThat(d.alarms()).isEqualTo(count);
        assertThat(d.worstLevel()).isEqualTo("-".equals(worst) ? null : worst);
    }

    @Test
    @DisplayName("30 sn süren alarm 1 dk görünür; 0 ms süren alarm 0 dk (ama alarm sayılır)")
    void subMinuteRoundsUp() {
        long s = ist("2026-11-05T10:00");
        OutageCalendar.Result r = OutageCalendar.compute(NOV,
                List.of(new OutageCalendar.Interval(s, s + 30_000, "HIGH")), AFTER);
        assertThat(day(r, 5).outageMinutes()).isEqualTo(1);
        assertThat(day(r, 5).alarms()).isEqualTo(1);
    }

    @Test
    @DisplayName("gün sınırı İstanbul gece yarısı: UTC 20:59 ile 21:01 farklı günlere düşer")
    void istanbulMidnightBoundary() {
        // 10 Kasım UTC 20:30–21:30 = İstanbul 23:30–00:30 → 10'una 30 dk, 11'ine 30 dk
        long s = LocalDateTime.parse("2026-11-10T20:30").toInstant(ZoneOffset.UTC).toEpochMilli();
        long e = LocalDateTime.parse("2026-11-10T21:30").toInstant(ZoneOffset.UTC).toEpochMilli();
        OutageCalendar.Result r = OutageCalendar.compute(NOV, List.of(new OutageCalendar.Interval(s, e, "HIGH")), AFTER);
        assertThat(day(r, 10).outageMinutes()).isEqualTo(30);
        assertThat(day(r, 11).outageMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("ay boyu: gün sayısı, gelecek günler null, toplam = günlerin toplamı")
    void monthShapeAndTotal() {
        OutageCalendar.Result r = OutageCalendar.compute(NOV, List.of(
                iv("2026-11-01T00:00", "2026-11-01T01:00", "HIGH"),
                iv("2026-11-02T10:00", "2026-11-02T10:30", "WARNING")), ist("2026-11-02T12:00"));
        assertThat(r.days()).hasSize(30);
        assertThat(day(r, 1).outageMinutes()).isEqualTo(60);
        assertThat(day(r, 2).outageMinutes()).isEqualTo(30);
        assertThat(day(r, 3).outageMinutes()).isNull();
        assertThat(day(r, 30).outageMinutes()).isNull();
        assertThat(r.totalMinutes()).isEqualTo(90);
        assertThat(OutageCalendar.compute(YearMonth.of(2026, 2), List.of(), AFTER).days()).hasSize(28);
    }

    @Test
    @DisplayName("tüm gün kesintide birden çok alarm: gün 1440'ı aşmaz")
    void dayCappedAt1440() {
        OutageCalendar.Result r = OutageCalendar.compute(NOV, List.of(
                iv("2026-11-07T00:00", "2026-11-08T00:00", "HIGH"),
                iv("2026-11-06T12:00", "2026-11-09T00:00", "CRITICAL"),
                iv("2026-11-07T05:00", "2026-11-07T06:00", "WARNING")), AFTER);
        assertThat(day(r, 7).outageMinutes()).isEqualTo(1440);
        assertThat(day(r, 7).alarms()).isEqualTo(3);
        assertThat(day(r, 7).worstLevel()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("açık alarm şimdiye kadar: dünkü gün tam, bugün şimdiye kadar")
    void openAlarmUntilNow() {
        OutageCalendar.Result r = OutageCalendar.compute(NOV,
                List.of(iv("2026-11-18T20:00", null, "HIGH")), ist("2026-11-19T00:45"));
        assertThat(day(r, 18).outageMinutes()).isEqualTo(240);
        assertThat(day(r, 19).outageMinutes()).isEqualTo(45);
        assertThat(day(r, 20).outageMinutes()).isNull();
        assertThat(r.totalMinutes()).isEqualTo(285);
    }

    @Test
    @DisplayName("birleşim yardımcısı: iç içe, bitişik ve ayrık parçalar")
    void unionHelper() {
        List<long[]> p = new ArrayList<>(Arrays.asList(new long[]{0, 10}, new long[]{2, 5}, new long[]{10, 12}, new long[]{20, 25}));
        assertThat(OutageCalendar.unionMs(p)).isEqualTo(17);
        assertThat(OutageCalendar.unionMs(new ArrayList<>())).isZero();
        assertThat(OutageCalendar.ceilMinutes(1)).isEqualTo(1);
        assertThat(OutageCalendar.ceilMinutes(60_000)).isEqualTo(1);
        assertThat(OutageCalendar.ceilMinutes(60_001)).isEqualTo(2);
    }

    @Test
    @DisplayName("saklama sınırı: başlangıcı sınırdan önce olan günler veri yok (0 değil), toplam yalnız kapsanan günler")
    void coverageLimit() {
        OutageCalendar.Result r = OutageCalendar.compute(NOV, List.of(
                iv("2026-11-05T10:00", "2026-11-05T11:00", "CRITICAL"),
                iv("2026-11-12T10:00", "2026-11-12T10:30", "HIGH")), AFTER, ist("2026-11-10T12:00"));
        assertThat(day(r, 5).noData()).isTrue();
        assertThat(day(r, 5).outageMinutes()).isNull();
        assertThat(day(r, 5).alarms()).isZero();
        assertThat(day(r, 10).noData()).isTrue();    // gün başı sınırdan önce — o günün alarmları eksik olabilir
        assertThat(day(r, 11).noData()).isFalse();
        assertThat(day(r, 11).outageMinutes()).isZero();
        assertThat(day(r, 12).outageMinutes()).isEqualTo(30);
        assertThat(r.totalMinutes()).isEqualTo(30);
        // eski imza sınırsız: aynı veri, 5 Kasım dahil
        assertThat(OutageCalendar.compute(NOV, List.of(iv("2026-11-05T10:00", "2026-11-05T11:00", "HIGH")), AFTER)
                .totalMinutes()).isEqualTo(60);
    }
}
