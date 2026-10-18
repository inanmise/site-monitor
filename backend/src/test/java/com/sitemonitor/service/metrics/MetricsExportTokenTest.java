package com.sitemonitor.service.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İş ölçümleri belirteci (2026-10-18): yok / kısa → kapalı; yalnız {@code Bearer <tam belirteç>} eşleşir; büyük-küçük harf
 * önekte serbest; çok uzun başlık okunmaz; değer {@code toString}'de görünmez.
 */
class MetricsExportTokenTest {

    static final String TOKEN = "s3cr3t-token-0123456789abcdef-0123456789";

    @Test
    @DisplayName("yok ya da 32 karakterden kısa → tanımlı değil, hiçbir başlık eşleşmez")
    void notConfigured() {
        String thirtyOne = "a".repeat(MetricsExportToken.MIN_LENGTH - 1);
        assertThat(MetricsExportToken.MIN_LENGTH).isEqualTo(32);
        assertThat(MetricsExportToken.of(thirtyOne).configured()).isFalse();
        assertThat(MetricsExportToken.of(thirtyOne + "b").configured()).isTrue();
        for (String raw : new String[] { null, "", "   ", "short-token", "sixteen-chars-ok", thirtyOne }) {
            MetricsExportToken t = MetricsExportToken.of(raw);
            assertThat(t.configured()).as(String.valueOf(raw)).isFalse();
            assertThat(t.matches("Bearer " + raw)).isFalse();
        }
        assertThat(MetricsExportToken.of("short-token").tooShort()).isTrue();
        assertThat(MetricsExportToken.of("").tooShort()).isFalse();
    }

    @Test
    @DisplayName("tanımlı: yalnız Bearer + tam belirteç; boşluklar kırpılır, önek büyük-küçük harf duyarsız")
    void matches() {
        MetricsExportToken t = MetricsExportToken.of("  " + TOKEN + "  ");
        assertThat(t.configured()).isTrue();
        assertThat(t.matches("Bearer " + TOKEN)).isTrue();
        assertThat(t.matches("bearer " + TOKEN)).isTrue();
        assertThat(t.matches("  Bearer   " + TOKEN + " ")).isTrue();
        assertThat(t.matches(null)).isFalse();
        assertThat(t.matches("")).isFalse();
        assertThat(t.matches("Bearer")).isFalse();
        assertThat(t.matches("Bearer ")).isFalse();
        assertThat(t.matches(TOKEN)).as("önek yok").isFalse();
        assertThat(t.matches("Basic " + TOKEN)).isFalse();
        assertThat(t.matches("Bearer " + TOKEN + "x")).isFalse();
        assertThat(t.matches("Bearer " + TOKEN.substring(1))).isFalse();
        assertThat(t.matches("Bearer " + "a".repeat(MetricsExportToken.MAX_HEADER))).as("çok uzun başlık").isFalse();
        assertThat(t.toString()).doesNotContain(TOKEN);
    }
}
