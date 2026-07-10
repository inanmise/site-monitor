package com.certmonitor.service;

import com.certmonitor.model.MaintenanceWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceServiceTest {

    // Occurrence metodları repo kullanmaz → null repo yeterli.
    private final MaintenanceService svc = new MaintenanceService(null);

    private static MaintenanceWindow win(String startUtc, int dur, String rec, String tz) {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setStartAt(startUtc); w.setDurationMinutes(dur); w.setRecurrence(rec); w.setTimezone(tz);
        w.setActive(true);
        return w;
    }

    @Test
    @DisplayName("NONE: yalnız [start, start+dur] aralığında aktif; öncesi upcoming, sonrası completed")
    void oneTime() {
        MaintenanceWindow w = win("2026-01-01T10:00:00", 60, "NONE", "UTC");
        assertThat(svc.isActiveAt(w, Instant.parse("2026-01-01T10:30:00Z"))).isTrue();
        assertThat(svc.isActiveAt(w, Instant.parse("2026-01-01T09:59:00Z"))).isFalse();
        assertThat(svc.isActiveAt(w, Instant.parse("2026-01-01T11:01:00Z"))).isFalse();
        assertThat(svc.computeStatus(w, Instant.parse("2026-01-01T09:00:00Z"))).isEqualTo("upcoming");
        assertThat(svc.computeStatus(w, Instant.parse("2026-01-01T10:30:00Z"))).isEqualTo("active");
        assertThat(svc.computeStatus(w, Instant.parse("2026-01-01T12:00:00Z"))).isEqualTo("completed");
    }

    @Test
    @DisplayName("paused (active=false) pencere hiçbir zaman aktif değil")
    void pausedNeverActive() {
        MaintenanceWindow w = win("2026-01-01T10:00:00", 60, "NONE", "UTC");
        w.setActive(false);
        assertThat(svc.isActiveAt(w, Instant.parse("2026-01-01T10:30:00Z"))).isFalse();
        assertThat(svc.computeStatus(w, Instant.parse("2026-01-01T10:30:00Z"))).isEqualTo("paused");
    }

    @Test
    @DisplayName("DAILY: her gün aynı yerel saatte aktif")
    void daily() {
        // Anchor 02:00 Europe/Istanbul (+03) = 2025-12-31T23:00:00Z; 120 dk
        MaintenanceWindow w = win("2025-12-31T23:00:00", 120, "DAILY", "Europe/Istanbul");
        // 2026-03-15 02:30 Istanbul = 2026-03-14T23:30:00Z → aktif (günlük tekrar)
        assertThat(svc.isActiveAt(w, Instant.parse("2026-03-14T23:30:00Z"))).isTrue();
        // 2026-03-15 05:00 Istanbul = 2026-03-15T02:00:00Z → aralık dışı
        assertThat(svc.isActiveAt(w, Instant.parse("2026-03-15T02:00:00Z"))).isFalse();
    }

    @Test
    @DisplayName("DAILY DST-güvenli: London 02:00 yaz saatinde (BST) 01:00Z'ye kayar")
    void dailyDstSafe() {
        // Anchor 2026-01-10 02:00 Europe/London (kış GMT+0) = 2026-01-10T02:00:00Z; 60 dk
        MaintenanceWindow w = win("2026-01-10T02:00:00", 60, "DAILY", "Europe/London");
        // Yaz (BST +1): yerel 02:30 = 01:30Z → aktif (yerel 02:00 saati DST'de korunur)
        assertThat(svc.isActiveAt(w, Instant.parse("2026-07-10T01:30:00Z"))).isTrue();
        // Yaz 02:30Z = yerel 03:30 → aralık dışı
        assertThat(svc.isActiveAt(w, Instant.parse("2026-07-10T02:30:00Z"))).isFalse();
    }

    @Test
    @DisplayName("WEEKLY: yalnız seçili günlerde (Pzt/Çar) aktif")
    void weekly() {
        MaintenanceWindow w = win("2026-01-05T10:00:00", 60, "WEEKLY", "UTC");   // 2026-01-05 = Pazartesi
        w.setDaysOfWeek("1,3");   // Pzt, Çar
        assertThat(svc.isActiveAt(w, Instant.parse("2026-01-05T10:30:00Z"))).isTrue();   // Pzt
        assertThat(svc.isActiveAt(w, Instant.parse("2026-01-06T10:30:00Z"))).isFalse();  // Sal
        assertThat(svc.isActiveAt(w, Instant.parse("2026-01-07T10:30:00Z"))).isTrue();   // Çar
    }

    @Test
    @DisplayName("MONTHLY: ayın gününde aktif; kısa ayda son güne clamp (31 → Şubat 28)")
    void monthlyClamp() {
        MaintenanceWindow w = win("2026-01-31T10:00:00", 60, "MONTHLY", "UTC");
        w.setDayOfMonth(31);
        assertThat(svc.isActiveAt(w, Instant.parse("2026-01-31T10:30:00Z"))).isTrue();
        assertThat(svc.isActiveAt(w, Instant.parse("2026-02-28T10:30:00Z"))).isTrue();   // clamp
        assertThat(svc.isActiveAt(w, Instant.parse("2026-02-27T10:30:00Z"))).isFalse();
    }

    @Test
    @DisplayName("nextOccurrence: DAILY için bugünkü saat geçtiyse yarınki başlangıç")
    void nextOccurrence() {
        MaintenanceWindow w = win("2026-01-01T09:00:00", 60, "DAILY", "UTC");
        assertThat(svc.nextOccurrence(w, Instant.parse("2026-01-01T12:00:00Z"))).isEqualTo("2026-01-02T09:00:00");
        assertThat(svc.nextOccurrence(w, Instant.parse("2026-01-01T06:00:00Z"))).isEqualTo("2026-01-01T09:00:00");
    }
}
