package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Takım etiket kataloğu (2026-09-22): on kaynaktan CSV birleştirme, büyük/küçük harf dedup, sayı ↓ ad ↑ sıralama, hata toleransı. */
class MonitoringTagServiceTest {

    @Test
    @DisplayName("etiketler CSV'den ayrıştırılır, büyük/küçük harf birleşir (ilk yazım kalır), kullanım sayısı ↓ sonra ad ↑ sıralanır")
    void mergesAndSorts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L))).thenReturn(List.of());
        when(jdbc.queryForList(contains("http_monitors"), eq(String.class), eq(7L))).thenReturn(java.util.Arrays.asList("prod, pci", " PCI ,payment", null, ""));
        when(jdbc.queryForList(contains("certificate_inventory"), eq(String.class), eq(7L))).thenReturn(List.of("Prod", "alpha"));

        List<MonitoringTagService.TagUse> out = new MonitoringTagService(jdbc).listForTeam(7L);
        assertThat(out).extracting(MonitoringTagService.TagUse::name).containsExactly("pci", "prod", "alpha", "payment");
        assertThat(out.get(0).count()).isEqualTo(2);   // pci + PCI
        assertThat(out.get(1).count()).isEqualTo(2);   // prod + Prod → ilk yazım "prod"
        // Yalnız istenen takım ve yalnız aktif/silinmemiş satırlar sorgulanır
        verify(jdbc, times(MonitoringTagService.MONITOR_TABLES.length)).queryForList(contains("active = true AND tags IS NOT NULL"), eq(String.class), eq(7L));
        verify(jdbc).queryForList(contains("deleted_at IS NULL"), eq(String.class), eq(7L));
    }

    @Test
    @DisplayName("takım yoksa boş; bir kaynak patlarsa (eski şema) diğerleri yine gelir")
    void tolerant() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(1L))).thenThrow(new RuntimeException("no column"));
        when(jdbc.queryForList(contains("ping_monitors"), eq(String.class), eq(1L))).thenReturn(List.of("x"));
        MonitoringTagService svc = new MonitoringTagService(jdbc);
        assertThat(svc.listForTeam(null)).isEmpty();
        assertThat(svc.listForTeam(1L)).extracting(MonitoringTagService.TagUse::name).containsExactly("x");
    }
}
