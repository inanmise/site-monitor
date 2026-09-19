package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.repository.AlertThresholdRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tier bazlı eşik çözümü (2026-09-20): tier satırı varsa o, yoksa varsayılan; okuma düşerse alarm durmaz.
 */
class ThresholdResolutionTest {

    private static AlertThreshold row(Integer tier, int warn, int high, int crit) {
        AlertThreshold t = new AlertThreshold();
        t.setTier(tier); t.setWarningDays(warn); t.setHighDays(high); t.setCriticalDays(crit); t.setActive(true);
        return t;
    }

    @Test
    @DisplayName("Tier satırı varsa onun günleri, yoksa varsayılanın günleri döner")
    void tierOverrideElseDefault() {
        AlertThresholdRepository repo = mock(AlertThresholdRepository.class);
        when(repo.findFirstByActiveTrue()).thenReturn(Optional.of(row(null, 30, 15, 7)));
        when(repo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(row(1, 60, 30, 14)));

        ThresholdResolution r = ThresholdResolution.load(repo, null);
        assertThat(r.days(1)).containsExactly(14, 30, 60);      // {kritik, yüksek, uyarı}
        assertThat(r.days(2)).containsExactly(7, 15, 30);       // tier 2'nin satırı yok → varsayılan
        assertThat(r.days(null)).containsExactly(7, 15, 30);
        assertThat(r.hasOverride(1)).isTrue();
        assertThat(r.hasOverride(2)).isFalse();
        assertThat(r.hasOverride(null)).isFalse();
    }

    @Test
    @DisplayName("Seviye: aynı kalan gün, tier 1'de KRİTİK / tier'sızda UYARI olabilir")
    void levelDependsOnTier() {
        AlertThresholdRepository repo = mock(AlertThresholdRepository.class);
        when(repo.findFirstByActiveTrue()).thenReturn(Optional.of(row(null, 30, 15, 7)));
        when(repo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(row(1, 60, 30, 14)));
        ThresholdResolution r = ThresholdResolution.load(repo, null);

        assertThat(r.levelFor(1, 12)).isEqualTo("CRITICAL");
        assertThat(r.levelFor(null, 12)).isEqualTo("HIGH");
        assertThat(r.levelFor(1, 25)).isEqualTo("HIGH");
        assertThat(r.levelFor(null, 25)).isEqualTo("WARNING");
        assertThat(r.levelFor(1, 45)).isEqualTo("WARNING");
        assertThat(r.levelFor(null, 45)).isNull();
        assertThat(r.levelFor(null, null)).isNull();
    }

    @Test
    @DisplayName("Aynı tier'a iki aktif satır varsa EN ESKİ kazanır (deterministik)")
    void firstRowWinsForDuplicateTier() {
        AlertThresholdRepository repo = mock(AlertThresholdRepository.class);
        when(repo.findFirstByActiveTrue()).thenReturn(Optional.of(row(null, 30, 15, 7)));
        when(repo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(row(1, 40, 20, 10), row(1, 99, 98, 97)));
        assertThat(ThresholdResolution.load(repo, null).days(1)).containsExactly(10, 20, 40);
    }

    @Test
    @DisplayName("Varsayılan satır yoksa fallback; fallback da yoksa 30/15/7; okuma patlarsa da alarm üretimi durmaz")
    void fallbacks() {
        AlertThresholdRepository repo = mock(AlertThresholdRepository.class);
        when(repo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        assertThat(ThresholdResolution.load(repo, row(null, 20, 10, 5)).days(null)).containsExactly(5, 10, 20);
        assertThat(ThresholdResolution.load(repo, null).days(3)).containsExactly(7, 15, 30);

        AlertThresholdRepository broken = mock(AlertThresholdRepository.class);
        when(broken.findFirstByActiveTrue()).thenThrow(new RuntimeException("db down"));
        assertThat(ThresholdResolution.load(broken, null).days(1)).containsExactly(7, 15, 30);
    }

    @Test
    @DisplayName("Null gün alanları varsayılana çekilir (unboxing NPE yok)")
    void nullDaysAreDefaulted() {
        AlertThreshold t = new AlertThreshold();
        t.setWarningDays(null); t.setHighDays(null); t.setCriticalDays(null);
        assertThat(ThresholdResolution.fixed(t).days(null)).containsExactly(7, 15, 30);
        assertThat(ThresholdResolution.fixed(null).forTier(4)).isNotNull();
    }
}
