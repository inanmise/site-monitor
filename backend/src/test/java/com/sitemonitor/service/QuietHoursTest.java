package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sessiz saat penceresi kuralı (2026-10-01, onaylı öneri 15): Europe/Istanbul, gece yarısı geçişi, gün süzgeci
 * (pencerenin BAŞLADIĞI gün), seviye kuralı (KRİTİK asla), bozuk ayar = pencere yok, kayıt doğrulaması.
 */
class QuietHoursTest {

    /** Istanbul yerel saati → Instant (UTC+3, DST yok). */
    static Instant ist(String localIso) {
        return LocalDateTime.parse(localIso).atZone(QuietHours.ZONE).toInstant();
    }

    @Nested
    @DisplayName("pencere")
    class Window {
        @Test
        @DisplayName("gece yarısını geçen pencere: 22:00 dahil, 07:00 hariç; gün içi pencere dışı")
        void crossesMidnight() {
            QuietHours q = QuietHours.parse("22:00", "07:00", null, null);
            assertThat(q.activeAt(ist("2026-10-01T21:59:59"))).isFalse();
            assertThat(q.activeAt(ist("2026-10-01T22:00:00"))).isTrue();
            assertThat(q.activeAt(ist("2026-10-02T03:00:00"))).isTrue();
            assertThat(q.activeAt(ist("2026-10-02T06:59:59"))).isTrue();
            assertThat(q.activeAt(ist("2026-10-02T07:00:00"))).isFalse();
            assertThat(q.activeAt(ist("2026-10-02T12:00:00"))).isFalse();
        }

        @Test
        @DisplayName("oluşum: anahtar = pencerenin yerel BAŞLANGICI; gece 03:00 bir önceki günün penceresidir")
        void occurrenceKeyAndEnd() {
            QuietHours q = QuietHours.parse("22:00", "07:00", null, null);
            QuietHours.Occurrence late = q.occurrenceAt(ist("2026-10-01T23:30:00"));
            QuietHours.Occurrence early = q.occurrenceAt(ist("2026-10-02T03:00:00"));
            assertThat(late.key()).isEqualTo("2026-10-01T22:00");
            assertThat(early.key()).isEqualTo(late.key());
            assertThat(early.endInstant()).isEqualTo(ist("2026-10-02T07:00:00"));
            assertThat(early.label()).isEqualTo("22:00–07:00");
            assertThat(early.endLabel()).isEqualTo("07:00");
        }

        @Test
        @DisplayName("Istanbul saati: 22:00 IST = 19:00 UTC")
        void istanbulZone() {
            QuietHours q = QuietHours.parse("22:00", "23:00", null, null);
            assertThat(q.activeAt(Instant.parse("2026-10-01T19:00:00Z"))).isTrue();
            assertThat(q.activeAt(Instant.parse("2026-10-01T22:00:00Z"))).isFalse();   // 01:00 IST
        }

        @Test
        @DisplayName("gün içi pencere (12:00–13:00)")
        void sameDayWindow() {
            QuietHours q = QuietHours.parse("12:00", "13:00", null, null);
            assertThat(q.activeAt(ist("2026-10-01T12:30:00"))).isTrue();
            assertThat(q.activeAt(ist("2026-10-01T13:00:00"))).isFalse();
            assertThat(q.activeAt(ist("2026-10-01T11:59:00"))).isFalse();
        }

        @Test
        @DisplayName("gün süzgeci pencerenin BAŞLADIĞI güne uygulanır: Pzt–Cum 22–07 → Cumartesi 03:00 sessiz, Pazartesi 03:00 değil")
        void daysApplyToStartDay() {
            QuietHours q = QuietHours.parse("22:00", "07:00", "MON,TUE,WED,THU,FRI", null);
            // 2026-10-02 Cuma, 2026-10-03 Cumartesi, 2026-10-05 Pazartesi
            assertThat(q.activeAt(ist("2026-10-02T23:00:00"))).isTrue();    // Cuma gecesi
            assertThat(q.activeAt(ist("2026-10-03T03:00:00"))).isTrue();    // Cuma'nın penceresi
            assertThat(q.activeAt(ist("2026-10-03T23:00:00"))).isFalse();   // Cumartesi başlamaz
            assertThat(q.activeAt(ist("2026-10-05T03:00:00"))).isFalse();   // Pazar'ın penceresi — Pazar seçili değil
            assertThat(q.activeAt(ist("2026-10-05T23:00:00"))).isTrue();    // Pazartesi gecesi
        }
    }

    @Nested
    @DisplayName("seviye")
    class Level {
        @Test
        @DisplayName("varsayılan (boş/HIGH): yalnız UYARI ertelenir; YÜKSEK ve KRİTİK hemen")
        void defaultDefersWarningOnly() {
            QuietHours q = QuietHours.parse("22:00", "07:00", null, null);
            assertThat(q.defers("WARNING")).isTrue();
            assertThat(q.defers("HIGH")).isFalse();
            assertThat(q.defers("CRITICAL")).isFalse();
            assertThat(QuietHours.parse("22:00", "07:00", null, "HIGH").defers("HIGH")).isFalse();
        }

        @Test
        @DisplayName("CRITICAL ayarı: UYARI + YÜKSEK ertelenir; KRİTİK hiçbir ayarla ertelenmez")
        void criticalNeverDeferred() {
            QuietHours q = QuietHours.parse("22:00", "07:00", null, "CRITICAL");
            assertThat(q.defers("WARNING")).isTrue();
            assertThat(q.defers("HIGH")).isTrue();
            assertThat(q.defers("CRITICAL")).isFalse();
            assertThat(q.defers("critical")).isFalse();
            // tanınmayan asgari seviye → varsayılan (HIGH); KRİTİK yine geçer
            assertThat(QuietHours.parse("22:00", "07:00", null, "BOGUS").defers("HIGH")).isFalse();
        }
    }

    @Nested
    @DisplayName("bozuk ayar = pencere YOK")
    class Broken {
        @Test
        void brokenSettingsMeanNoWindow() {
            assertThat(QuietHours.parse(null, null, null, null)).isNull();
            assertThat(QuietHours.parse("22:00", "", null, null)).isNull();
            assertThat(QuietHours.parse("22:00", "22:00", null, null)).isNull();   // sıfır uzunluk ≠ 24 saat
            assertThat(QuietHours.parse("25:00", "07:00", null, null)).isNull();
            assertThat(QuietHours.parse("22:00", "7", null, null)).isNull();
            assertThat(QuietHours.parse("22:00", "07:00", "XYZ", null)).isNull();  // geçersiz gün listesi "her gün" OLMAZ
        }
    }

    @Nested
    @DisplayName("kayıt doğrulaması (normalize)")
    class Normalize {
        @Test
        @DisplayName("ikisi boş = kaldır; gün listesi kanonik; yedi gün = her gün (null)")
        void normalizes() {
            assertThat(QuietHours.normalize("", " ", null, null)).isEqualTo(QuietHours.Config.NONE);
            QuietHours.Config c = QuietHours.normalize(" 22:00 ", "07:00", List.of("fri", "MON"), "critical");
            assertThat(c).isEqualTo(new QuietHours.Config("22:00", "07:00", "MON,FRI", "CRITICAL"));
            assertThat(QuietHours.normalize("22:00", "07:00", "MON,TUE,WED,THU,FRI,SAT,SUN", "").days()).isNull();
            assertThat(QuietHours.normalize("22:00", "07:00", List.of(), null).days()).isNull();
            assertThat(QuietHours.normalize("22:00", "07:00", null, null).minLevel()).isNull();
        }

        @Test
        @DisplayName("hatalar 400 (IllegalArgumentException): tek uç, biçim, eşit uçlar, gün, seviye")
        void rejects() {
            assertThatThrownBy(() -> QuietHours.normalize("22:00", "", null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuietHours.normalize("9:00", "10:00", null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuietHours.normalize("24:00", "10:00", null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuietHours.normalize("10:00", "10:00", null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QuietHours.normalize("22:00", "07:00", List.of("MONDAY"), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MONDAY");
            assertThatThrownBy(() -> QuietHours.normalize("22:00", "07:00", null, "WARNING"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
