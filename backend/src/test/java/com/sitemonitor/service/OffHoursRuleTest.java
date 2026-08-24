package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Mesai dışı" (OFF_HOURS) kuralı — hafta içi 08:00–20:00 MESAİ, gerisi mesai dışı.
 *
 * <p>Bu mantık bugüne dek HİÇ test edilmemişti ve iki kusuru vardı:
 * <ul>
 *   <li>Hesap {@code ZoneOffset.UTC} ile yapılıyordu; kurum Europe/Istanbul (UTC+3) olduğu için
 *       pencere 3 saat kayıyordu — sabah 08:00'deki normal giriş "mesai dışı" damgalanıyor,
 *       akşam 22:00'deki giriş damgalanmıyordu.</li>
 *   <li>Mevcut testteki yorum ("0–0 ile hafta içi false döner") YANLIŞTI: {@code hour >= 0} her
 *       zaman doğru olduğu için o ayarla kural DAİMA mesai dışı derdi.</li>
 * </ul>
 *
 * <p>Kural saf bir fonksiyona çıkarıldı: {@code now()} çağıran bir mantık ancak süitin KOŞTUĞU
 * saate göre sınanabilirdi (CI runner UTC, geliştirici makinesi Istanbul — projede yaşanmış tuzak).
 */
class OffHoursRuleTest {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final int START = 8;
    private static final int END = 20;

    /** 2026-08-24 Pazartesi, 2026-08-28 Cuma, 2026-08-29 Cumartesi, 2026-08-30 Pazar. */
    private static ZonedDateTime ist(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), IST);
    }

    @ParameterizedTest(name = "{0} → mesai disi mi: {1}")
    @CsvSource({
        // ── Hafta ici SINIRLAR — kuralin can alici noktasi ──
        "2026-08-24T07:59:59, true",    // mesai baslamadan hemen once
        "2026-08-24T08:00:00, false",   // baslangic DAHIL
        "2026-08-24T12:30:00, false",   // gun ortasi
        "2026-08-24T19:59:59, false",   // bitisten hemen once
        "2026-08-24T20:00:00, true",    // bitis HARIC → 20:00 artik mesai disi
        "2026-08-24T23:30:00, true",
        "2026-08-24T00:15:00, true",    // gece yarisi sonrasi
        // ── Cuma aksami / hafta sonu ──
        "2026-08-28T19:00:00, false",   // cuma mesai ici
        "2026-08-28T21:00:00, true",    // cuma aksami
        "2026-08-29T12:00:00, true",    // CUMARTESI gunduz bile mesai disi
        "2026-08-30T10:00:00, true",    // PAZAR
    })
    @DisplayName("Hafta ici 08:00-20:00 mesai; sinirlar ve hafta sonu")
    void windowBoundaries(String localTime, boolean expectedOffHours) {
        assertThat(AuditService.isOffHours(ist(localTime), START, END)).isEqualTo(expectedOffHours);
    }

    @Test
    @DisplayName("SAAT DILIMI: ayni AN, UTC'de bakilirsa YANLIS sonuc verir (3 saatlik kayma)")
    void utcWouldGiveTheWrongAnswer() {
        // Pazartesi 08:30 Istanbul = 05:30 UTC. Kurum saatinde MESAI ICI, UTC'de "mesai disi".
        ZonedDateTime istanbulMorning = ist("2026-08-24T08:30:00");

        assertThat(AuditService.isOffHours(istanbulMorning, START, END)).isFalse();
        // Ayni anin UTC gorunumu ters cevap verir — eski hatanin ta kendisi.
        assertThat(AuditService.isOffHours(
                istanbulMorning.withZoneSameInstant(java.time.ZoneOffset.UTC), START, END)).isTrue();
    }

    @Test
    @DisplayName("SAAT DILIMI: hafta GUNU de kayar — cumartesi 01:00 Istanbul, UTC'de hala cuma")
    void weekendBoundaryAlsoShifts() {
        ZonedDateTime saturdayNight = ist("2026-08-29T01:00:00");

        assertThat(AuditService.isOffHours(saturdayNight, START, END)).isTrue();   // cumartesi
        // UTC'de bu an cuma 22:00 — hafta sonu kurali kacar (yine de saat nedeniyle true doner,
        // ama SEBEBI yanlistir; asagidaki senaryo farki acikca gosterir).
        assertThat(saturdayNight.withZoneSameInstant(java.time.ZoneOffset.UTC)
                .getDayOfWeek().getValue()).isEqualTo(5);   // FRIDAY
    }

    @Test
    @DisplayName("Cumartesi 10:00 Istanbul UTC'de cumartesi 07:00 — saat kuralina takilir ama GUN kurali dogru olan")
    void weekendIsWeekendRegardlessOfHour() {
        // Hafta sonunun TAMAMI mesai disidir; saatten bagimsiz.
        assertThat(AuditService.isOffHours(ist("2026-08-29T10:00:00"), START, END)).isTrue();
        assertThat(AuditService.isOffHours(ist("2026-08-30T14:00:00"), START, END)).isTrue();
    }
}
