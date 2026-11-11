package com.sitemonitor.service.baseline;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.baseline.BaselineMath.History;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dinamik taban çizgisi örnek deposu GERÇEK PostgreSQL'de: en yeni N okuma, {@code beforeMs} dışlaması ve MEVSİMSEL okuma
 * ({@code MOD/ABS/LEAST} ile günün aynı saatine düşen örnekler, dairesel uzaklık). Şema yaması uygulama bağlamında
 * çalıştığından tablo hazırdır; benzersiz bir izleme kimliği kullanılır ve sonunda temizlenir (paylaşılan bağlam).
 */
@PostgresIntegration
class BaselineSamplePostgresTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final String HTTP = BaselineTypes.HTTP;

    @Test
    @DisplayName("mevsimsel okuma (MOD/ABS/LEAST) ve en yeni N — PostgreSQL")
    void readSeasonalAndRecentOnPostgres() {
        BaselineSampleStore store = PostgresIt.app().bean(BaselineSampleStore.class);
        assertThat(store.ready()).isTrue();
        long id = 900_000_000L + (long) (Math.random() * 1_000_000);
        long DAY = BaselineSampleStore.DAY_MS, tol = BaselineSampleStore.SEASON_TOLERANCE_MIN_MS;
        long nowPos = Math.floorMod(T0, DAY);
        try {
            for (int d = 0; d < 5; d++) {
                store.append(HTTP, id, T0 + d * DAY, 100 + d, false);                  // her gün 08:00
                store.append(HTTP, id, T0 + d * DAY + 20 * 60_000, 150 + d, false);    // 08:20 (±30 dk)
            }
            for (int d = 0; d < 5; d++) store.append(HTTP, id, T0 + d * DAY + 6 * 3_600_000L, 900, false);   // 14:00 (başka slot)

            History seasonal = store.readSeasonal(HTTP, id, T0 + 10 * DAY, 50, DAY, nowPos, tol);
            assertThat(seasonal.samples()).hasSize(10);                        // yalnız 08:00 + 08:20 × 5 gün
            assertThat(seasonal.samples()).allMatch(s -> s.value() < 200);     // 14:00 (900) dışarı
            assertThat(seasonal.complete()).isFalse();

            History recent = store.readRecent(HTTP, id, T0 + 10 * DAY, 50);
            assertThat(recent.samples()).hasSize(15);                          // mevsimsiz → hepsi
            assertThat(recent.samples().get(0).breach()).isFalse();
            assertThat(store.count(HTTP, id)).isEqualTo(15);
        } finally {
            store.deleteForMonitor(HTTP, id);
        }
    }
}
