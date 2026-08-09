package com.sitemonitor.service.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Ayın SON CUMA günü 10:00" cron'unun doğruluğu.
 *
 * <p>Bu, repodaki İLK {@code L} (last) niteleyicili cron ifadesidir — diğer tüm cron'lar
 * 6 alanlı basit ifadelerdir. {@code FRIL} yanlış yazılırsa (ör. {@code L FRI}, {@code FRI#L})
 * Spring ya ayrıştırma hatası verir ya da HER cuma tetikler; ikisi de sessiz üretim hatasıdır:
 * biri raporu hiç göndermez, diğeri ayda dört kez gönderir. Bu yüzden ifade davranışıyla
 * doğrulanır, sadece "ayrıştırılabiliyor mu" ile değil.
 */
class CertInventoryReportCronTest {

    private static final String CRON = "0 0 10 * * FRIL";
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    @Test
    @DisplayName("İfade ayrıştırılabiliyor")
    void parses() {
        assertThat(CronExpression.parse(CRON)).isNotNull();
    }

    @Test
    @DisplayName("Sıradaki 24 çalışma: hepsi ayın SON cuması, saat 10:00")
    void everyOccurrenceIsLastFridayAtTen() {
        CronExpression cron = CronExpression.parse(CRON);
        ZonedDateTime cursor = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, IST);

        for (int i = 0; i < 24; i++) {
            cursor = cron.next(cursor);
            assertThat(cursor).as("çalışma #%d üretilemedi", i).isNotNull();

            assertThat(cursor.getDayOfWeek()).as("gün: %s", cursor).isEqualTo(DayOfWeek.FRIDAY);
            assertThat(cursor.getHour()).as("saat: %s", cursor).isEqualTo(10);
            assertThat(cursor.getMinute()).isZero();

            ZonedDateTime lastFriday = cursor.with(TemporalAdjusters.lastInMonth(DayOfWeek.FRIDAY));
            assertThat(cursor.toLocalDate())
                    .as("ayın son cuması değil: %s (beklenen %s)", cursor.toLocalDate(), lastFriday.toLocalDate())
                    .isEqualTo(lastFriday.toLocalDate());
        }
    }

    @Test
    @DisplayName("Ayda TEK kez tetiklenir (her cuma değil)")
    void firesOncePerMonth() {
        CronExpression cron = CronExpression.parse(CRON);
        ZonedDateTime cursor = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, IST);

        ZonedDateTime first = cron.next(cursor);
        ZonedDateTime second = cron.next(first);

        // İki tetik arasında en az 4 hafta olmalı — her cuma tetiklense 7 gün olurdu.
        long days = java.time.Duration.between(first, second).toDays();
        assertThat(days).as("%s → %s", first, second).isGreaterThanOrEqualTo(28);
        assertThat(first.getMonthValue()).isNotEqualTo(second.getMonthValue());
    }

    @Test
    @DisplayName("Bilinen tarihler: 2026 Ocak→Nisan son cumaları")
    void knownDates() {
        CronExpression cron = CronExpression.parse(CRON);
        ZonedDateTime c = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, IST);

        // 2026: 30 Ocak, 27 Şubat, 27 Mart, 24 Nisan — hepsi cuma ve ayın sonuncusu
        String[] expected = { "2026-01-30", "2026-02-27", "2026-03-27", "2026-04-24" };
        for (String e : expected) {
            c = cron.next(c);
            assertThat(c.toLocalDate().toString()).isEqualTo(e);
        }
    }
}
