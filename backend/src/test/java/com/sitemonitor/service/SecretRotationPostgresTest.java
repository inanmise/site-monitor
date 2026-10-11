package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gizli anahtar döndürme (2026-10-11) GERÇEK şemada: birim testi kendi tablolarını kurar; burada her konumun tablo /
 * kolon / ad kolonu / {@code updated_at} adının Hibernate'in ürettiği şemayla aynı olduğu doğrulanır (yanlış ad üretimde
 * "bu konum okunamadı" olur ve önceki anahtar hiç "kaldırılabilir" görünmez).
 */
@PostgresIntegration
class SecretRotationPostgresTest {

    @Test
    @DisplayName("her konumun okuma ve koşullu yazma cümlesi gerçek şemada hatasız çalışır")
    @SuppressWarnings("unchecked")
    void everyLocationRunsOnRealSchema() {
        SecretRotationService svc = PostgresIt.app().bean(SecretRotationService.class);
        Map<String, Object> status = svc.status();
        List<Map<String, Object>> locs = (List<Map<String, Object>>) status.get("locations");
        assertThat(locs).hasSize(SecretRotationService.LOCATIONS.size());
        assertThat(locs).allSatisfy(l -> assertThat(l).as(String.valueOf(l.get("id"))).doesNotContainKey("error"));
        for (SecretRotationService.Location loc : SecretRotationService.LOCATIONS) {
            // Var olmayan satıra koşullu yazma: 0 satır, SQL hatası yok (kolon adları + updated_at doğru)
            assertThat(svc.write(loc, -987_654_321L, "enc:v1:yok", "enc:v1:yeni")).as(loc.id()).isFalse();
            assertThat(svc.page(loc, Long.MIN_VALUE)).as(loc.id()).isNotNull();
        }
    }
}
