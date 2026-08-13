package com.sitemonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6 script sürüm numarası kuralı (x.y.z).
 *
 * <p>Sözleşme: ilk kayıt 1.0.0; sonraki kayıtlarda İÇERİK değiştiyse varsayılan yama artar,
 * kullanıcı isterse küçük/büyük değişiklik seçebilir. Bozuk/eski etiket zinciri kilitlememeli.
 */
class ScriptedVersioningTest {

    @Test
    @DisplayName("İlk sürüm 1.0.0 — önceki sürüm yoksa bump türü ne olursa olsun")
    void firstVersion() {
        assertThat(MonitoringController.nextVersion(null, null)).isEqualTo("1.0.0");
        assertThat(MonitoringController.nextVersion(null, "major")).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("Varsayılan artış YAMA'dır (bilinmeyen/boş tür de yamaya düşer)")
    void patchIsDefault() {
        assertThat(MonitoringController.nextVersion("1.0.0", null)).isEqualTo("1.0.1");
        assertThat(MonitoringController.nextVersion("1.0.9", "patch")).isEqualTo("1.0.10");
        assertThat(MonitoringController.nextVersion("1.0.0", "sacmalik")).isEqualTo("1.0.1");
    }

    @Test
    @DisplayName("Küçük değişiklik minor'ı artırıp yamayı sıfırlar; büyük değişiklik ikisini de sıfırlar")
    void minorAndMajor() {
        assertThat(MonitoringController.nextVersion("1.2.7", "minor")).isEqualTo("1.3.0");
        assertThat(MonitoringController.nextVersion("1.2.7", "major")).isEqualTo("2.0.0");
        assertThat(MonitoringController.nextVersion("9.9.9", "major")).isEqualTo("10.0.0");
    }

    @Test
    @DisplayName("Büyük/küçük harf ve boşluk toleransı (form değeri doğrudan geliyor)")
    void bumpTypeIsCaseInsensitive() {
        assertThat(MonitoringController.nextVersion("1.0.0", " MAJOR ")).isEqualTo("2.0.0");
        assertThat(MonitoringController.nextVersion("1.0.0", "Minor")).isEqualTo("1.1.0");
    }

    @Test
    @DisplayName("Ayrıştırılamayan etiket zinciri KİLİTLEMEZ — 1.0.0'a düşer")
    void brokenLabelFallsBack() {
        assertThat(MonitoringController.nextVersion("bozuk", "patch")).isEqualTo("1.0.0");
        assertThat(MonitoringController.nextVersion("", "minor")).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("Sürüm öneki/soneki olan etiketlerde de sayılar okunur")
    void tolerantParse() {
        assertThat(MonitoringController.nextVersion("v1.4.2", "patch")).isEqualTo("1.4.3");
    }
}
