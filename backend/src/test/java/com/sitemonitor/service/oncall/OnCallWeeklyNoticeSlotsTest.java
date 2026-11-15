package com.sitemonitor.service.oncall;

import com.sitemonitor.model.OnCallSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Haftalık nöbet bildirimi — saf zaman hesabı (2026-11-15): İstanbul dilimi (UTC+3, yaz saati yok), ISO hafta anahtarı,
 * Pazar gecesi kenarı, telafi penceresi, sıradaki gönderim ve varsayılanlar. 2026-11-16 Pazartesidir.
 */
class OnCallWeeklyNoticeSlotsTest {

    @Test
    @DisplayName("varsayılan Pazartesi 08:30 İstanbul = 05:30 UTC; hafta anahtarı o haftanın Pazartesi'si")
    void defaultSlotAndWeekKey() {
        Instant slot = OnCallWeeklyNoticeSlots.slotInWeek(LocalDate.parse("2026-11-16"), 1, "08:30");
        assertThat(slot).isEqualTo(Instant.parse("2026-11-16T05:30:00Z"));
        assertThat(OnCallWeeklyNoticeSlots.weekKey(slot)).isEqualTo("2026-11-16");
        // Çarşamba 14:00 → aynı haftanın anahtarı
        Instant wed = OnCallWeeklyNoticeSlots.slotInWeek(LocalDate.parse("2026-11-16"), 3, "14:00");
        assertThat(wed).isEqualTo(Instant.parse("2026-11-18T11:00:00Z"));
        assertThat(OnCallWeeklyNoticeSlots.weekKey(wed)).isEqualTo("2026-11-16");
    }

    @Test
    @DisplayName("yaz saati yok: Mart sonu ve Ekim sonu haftalarında da İstanbul = UTC+3")
    void noDst() {
        assertThat(OnCallWeeklyNoticeSlots.slotInWeek(LocalDate.parse("2026-03-30"), 1, "08:30"))
                .isEqualTo(Instant.parse("2026-03-30T05:30:00Z"));
        assertThat(OnCallWeeklyNoticeSlots.slotInWeek(LocalDate.parse("2026-10-26"), 1, "08:30"))
                .isEqualTo(Instant.parse("2026-10-26T05:30:00Z"));
    }

    @Test
    @DisplayName("Pazar gecesi: Pazar 23:30 dilimi önceki Pazartesi'nin haftasıdır; telafi penceresi gece yarısını en az 1 sa aşar")
    void sundayNightEdge() {
        Instant sun = OnCallWeeklyNoticeSlots.slotInWeek(LocalDate.parse("2026-11-16"), 7, "23:30");
        assertThat(sun).isEqualTo(Instant.parse("2026-11-22T20:30:00Z"));
        assertThat(OnCallWeeklyNoticeSlots.weekKey(sun)).isEqualTo("2026-11-16");
        // gün sonu Pazartesi 00:00 (İstanbul) ama en az 1 saat → 00:30
        assertThat(OnCallWeeklyNoticeSlots.catchUpEnd(sun)).isEqualTo(Instant.parse("2026-11-22T21:30:00Z"));
        // Pazartesi 00:10 İstanbul: adaylar önceki haftanın (Pazar 23:30) ve bu haftanın (gelecek Pazar) dilimi
        Instant mon0010 = Instant.parse("2026-11-22T21:10:00Z");
        List<Instant> c = OnCallWeeklyNoticeSlots.candidates(mon0010, 7, "23:30");
        assertThat(c).containsExactly(sun, Instant.parse("2026-11-29T20:30:00Z"));
        assertThat(mon0010).isBefore(OnCallWeeklyNoticeSlots.catchUpEnd(c.get(0)));
        assertThat(OnCallWeeklyNoticeSlots.candidateWeekKeys(mon0010)).containsExactly("2026-11-16", "2026-11-23");
    }

    @Test
    @DisplayName("telafi penceresi = dilimin İstanbul gününün sonu (08:30 → ertesi 00:00)")
    void catchUpWindow() {
        Instant slot = Instant.parse("2026-11-16T05:30:00Z");
        assertThat(OnCallWeeklyNoticeSlots.catchUpEnd(slot)).isEqualTo(Instant.parse("2026-11-16T21:00:00Z"));
    }

    @Test
    @DisplayName("sıradaki gönderim: gelecek dilim; geçmiş ama penceredeki gönderilmemiş dilim; gönderildiyse gelecek hafta")
    void next() {
        OnCallWeeklyNoticeSlots.Settings st = new OnCallWeeklyNoticeSlots.Settings(true, true, true, 1, "08:30");
        Instant sun = Instant.parse("2026-11-15T10:00:00Z");
        assertThat(OnCallWeeklyNoticeSlots.next(st, sun, k -> false)).isEqualTo(Instant.parse("2026-11-16T05:30:00Z"));
        Instant mon0900 = Instant.parse("2026-11-16T06:00:00Z");
        assertThat(OnCallWeeklyNoticeSlots.next(st, mon0900, k -> false)).isEqualTo(Instant.parse("2026-11-16T05:30:00Z"));
        assertThat(OnCallWeeklyNoticeSlots.next(st, mon0900, Set.of("2026-11-16")::contains))
                .isEqualTo(Instant.parse("2026-11-23T05:30:00Z"));
        Instant tue = Instant.parse("2026-11-17T06:00:00Z");
        assertThat(OnCallWeeklyNoticeSlots.next(st, tue, k -> false)).isEqualTo(Instant.parse("2026-11-23T05:30:00Z"));
        assertThat(OnCallWeeklyNoticeSlots.next(new OnCallWeeklyNoticeSlots.Settings(false, true, true, 1, "08:30"), sun, k -> false)).isNull();

        // İnceleme 2026-11-15: 08:30 gönderildikten sonra saat 18:00'e (ya da gün Çarşamba'ya) alındı — bu haftanın
        // dilimi GELECEKTE ama hafta bitti: sıradaki gönderim gelecek haftadır, "bugün 18:00" değil
        Instant mon1000 = Instant.parse("2026-11-16T07:00:00Z");
        OnCallWeeklyNoticeSlots.Settings evening = new OnCallWeeklyNoticeSlots.Settings(true, true, true, 1, "18:00");
        assertThat(OnCallWeeklyNoticeSlots.next(evening, mon1000, Set.of("2026-11-16")::contains))
                .isEqualTo(Instant.parse("2026-11-23T15:00:00Z"));
        OnCallWeeklyNoticeSlots.Settings wednesday = new OnCallWeeklyNoticeSlots.Settings(true, true, true, 3, "08:30");
        assertThat(OnCallWeeklyNoticeSlots.next(wednesday, mon1000, Set.of("2026-11-16")::contains))
                .isEqualTo(Instant.parse("2026-11-25T05:30:00Z"));
        // hafta bitmemişse (yeniden denenecek FAILED satırı "bitti" sayılmaz — çağıranın kuralı) bugün 18:00
        assertThat(OnCallWeeklyNoticeSlots.next(evening, mon1000, k -> false)).isEqualTo(Instant.parse("2026-11-16T15:00:00Z"));
        // geçmiş dilim penceredeyse ve hafta bitmediyse "az sonra" (geçmiş an), bittiyse gelecek hafta
        assertThat(OnCallWeeklyNoticeSlots.next(st, mon1000, k -> false)).isEqualTo(Instant.parse("2026-11-16T05:30:00Z"));
        assertThat(OnCallWeeklyNoticeSlots.next(st, mon1000, k -> true)).isNull();   // bütün haftalar bitti (yalnız test)
    }

    @Test
    @DisplayName("ayar: boş = kapalı / iki kanal açık / Pazartesi 08:30; bozuk gün ya da saat varsayılana döner")
    void settingsDefaults() {
        OnCallSchedule s = new OnCallSchedule();
        OnCallWeeklyNoticeSlots.Settings d = OnCallWeeklyNoticeSlots.Settings.of(s);
        assertThat(d).isEqualTo(new OnCallWeeklyNoticeSlots.Settings(false, true, true, 1, "08:30"));
        s.setWeeklyNoticeEnabled(true);
        s.setWeeklyNoticeMail(false);
        s.setWeeklyNoticeDay(9);
        s.setWeeklyNoticeTime("25:00");
        assertThat(OnCallWeeklyNoticeSlots.Settings.of(s)).isEqualTo(new OnCallWeeklyNoticeSlots.Settings(true, false, true, 1, "08:30"));
        assertThat(OnCallWeeklyNoticeSlots.validTime("00:00")).isTrue();
        assertThat(OnCallWeeklyNoticeSlots.validTime("23:59")).isTrue();
        assertThat(OnCallWeeklyNoticeSlots.validTime("24:00")).isFalse();
        assertThat(OnCallWeeklyNoticeSlots.validTime("8:30")).isFalse();
        assertThat(OnCallWeeklyNoticeSlots.validTime("08:30:00")).isFalse();
        assertThat(OnCallWeeklyNoticeSlots.validDay(0)).isFalse();
        assertThat(OnCallWeeklyNoticeSlots.validDay(7)).isTrue();
    }
}
