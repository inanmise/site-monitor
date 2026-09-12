package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Gürültü analizi (2026-09-12, #18): sayım/pay/ortalama süre, flap kuralı (5+ alarm & ort ≤ 10 dk), ısı haritası, kapsam, gün tavanı. */
@ExtendWith(MockitoExtension.class)
class AlertNoiseServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    @Mock AlertEventRepository alertEventRepo;
    @Mock CertificateInventoryRepository inventoryRepo;

    private static AlertEvent ev(long id, String domain, String type, Long team, int hoursAgo, Integer durMin) {
        AlertEvent e = new AlertEvent(); e.setId(id); e.setDomain(domain); e.setAlertType(type); e.setTeamId(team); e.setAlertLevel("CRITICAL");
        Instant c = Instant.now().minus(hoursAgo, ChronoUnit.HOURS);
        e.setCreatedAt(ISO.format(c));
        if (durMin != null) { e.setResolved(true); e.setResolvedAt(ISO.format(c.plus(durMin, ChronoUnit.MINUTES))); }
        return e;
    }

    @Test
    @DisplayName("flap: 6 kısa alarm (3 dk) → aday; 3 uzun alarm değil; pay yüzdesi; takım 2 elenir; en çok üstte")
    void noise() {
        CertificateInventory inv = new CertificateInventory(); inv.setDomain("flap.example.com"); inv.setTeamId(1L); inv.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        List<AlertEvent> events = new ArrayList<>();
        for (int i = 0; i < 6; i++) events.add(ev(i, "flap.example.com", "HTTP_DOWN", null, 2 + i, 3));   // takımsız ama alan görünür
        for (int i = 10; i < 13; i++) events.add(ev(i, "slow.example.com", "PING_DOWN", 1L, 5 + i, 60));
        events.add(ev(99, "other.example.com", "X", 2L, 1, 1));   // takım 2 → dışarıda
        when(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString())).thenReturn(events);

        AlertNoiseService svc = new AlertNoiseService(alertEventRepo, inventoryRepo);
        Map<String, Object> out = svc.build(999, t -> t != null && t == 1L);

        assertThat(out).containsEntry("days", 90).containsEntry("total", 9).containsEntry("distinct_targets", 2);
        @SuppressWarnings("unchecked") List<Map<String, Object>> top = (List<Map<String, Object>>) out.get("top");
        assertThat(top.get(0)).containsEntry("domain", "flap.example.com").containsEntry("count", 6).containsEntry("avg_minutes", 3.0);
        assertThat((Double) top.get(0).get("share_pct")).isEqualTo(66.7);
        assertThat(top.get(1)).containsEntry("domain", "slow.example.com").containsEntry("avg_minutes", 60.0);
        @SuppressWarnings("unchecked") List<Map<String, Object>> flap = (List<Map<String, Object>>) out.get("flapping");
        assertThat(flap).hasSize(1);
        assertThat(flap.get(0)).containsEntry("domain", "flap.example.com").containsEntry("suggestion", "raise-confirm");
        @SuppressWarnings("unchecked") Map<String, Object> heat = (Map<String, Object>) out.get("heat");
        @SuppressWarnings("unchecked") List<List<Integer>> rows = (List<List<Integer>>) heat.get("rows");
        assertThat(rows).hasSize(7);
        assertThat(rows.stream().flatMap(List::stream).mapToInt(Integer::intValue).sum()).isEqualTo(9);
        assertThat((Integer) heat.get("peak")).isGreaterThan(0);
    }
}
