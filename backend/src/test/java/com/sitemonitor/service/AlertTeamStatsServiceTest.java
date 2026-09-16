package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Alarm Geçmişi takım kırılımı (2026-09-16): takım başına açık / kapalı / son 7 / son 30.
 * İki kaynak da sayılmalı — izleme alarmında satırın team_id'si, sertifika alarmında domain→envanter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertTeamStatsServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock AlertEventRepository alertEventRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;

    private AlertTeamStatsService service;

    private static AlertEvent ev(long id, Long teamId, String domain, boolean resolved, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setTeamId(teamId); e.setDomain(domain); e.setResolved(resolved); e.setCreatedAt(createdAt);
        e.setAlertType("PING_DOWN"); e.setAlertLevel("CRITICAL");
        return e;
    }

    private static String daysAgo(int d) { return ISO.format(Instant.now().minus(d, ChronoUnit.DAYS).plusSeconds(60)); }

    @BeforeEach
    void setUp() {
        service = new AlertTeamStatsService(alertEventRepo, inventoryRepo, teamRepo);
        Team a = new Team(); a.setId(1L); a.setName("Takım A");
        Team b = new Team(); b.setId(2L); b.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, b));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("cert.example.com"); inv.setTeamId(2L);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> teams(Map<String, Object> data) { return (List<Map<String, Object>>) data.get("teams"); }

    @Test
    @DisplayName("takım_id ve envanter türevli alarmlar birlikte sayılır; açık/kapalı ve 7/30 gün kırılımı doğru")
    void countsBothSources() {
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(
                ev(1, 1L, "ping.example.com", false, daysAgo(40)),      // eski ama AÇIK → açık sayılır, 30 gün sütununa girmez
                ev(2, null, "cert.example.com", false, daysAgo(3))));   // envanterden Takım B
        when(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString())).thenReturn(List.of(
                ev(2, null, "cert.example.com", false, daysAgo(3)),     // KESİŞİM: iki kez sayılmamalı
                ev(3, 1L, "ping.example.com", true, daysAgo(2)),
                ev(4, 1L, "ping.example.com", true, daysAgo(20))));

        Map<String, Object> data = service.build(t -> true, true);

        assertThat(data).containsEntry("total_open", 2L).containsEntry("total_closed", 2L)
                .containsEntry("total_last7", 2L).containsEntry("total_last30", 3L).containsEntry("window_days", 30);
        List<Map<String, Object>> rows = teams(data);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("team_name", "Takım A")     // en çok açık üstte
                .containsEntry("open", 1L).containsEntry("closed", 2L).containsEntry("last7", 1L).containsEntry("last30", 2L);
        assertThat(rows.get(1)).containsEntry("team_name", "Takım B")
                .containsEntry("open", 1L).containsEntry("closed", 0L).containsEntry("last7", 1L).containsEntry("last30", 1L);
    }

    @Test
    @DisplayName("kapsam: yalnız görebildiği takım sayılır; takımı çözülemeyen alarm global OLMAYANA hiç gösterilmez")
    void scopeIsEnforced() {
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(
                ev(1, 1L, "ping.example.com", false, daysAgo(1)),
                ev(2, 2L, "other.example.com", false, daysAgo(1)),
                ev(3, null, "bilinmeyen.example.com", false, daysAgo(1))));
        when(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        Map<String, Object> scoped = service.build(t -> t != null && t == 1L, false);
        assertThat(teams(scoped)).extracting(m -> m.get("team_name")).containsExactly("Takım A");
        assertThat(scoped).containsEntry("total_open", 1L);

        Map<String, Object> global = service.build(t -> true, true);
        assertThat(teams(global)).extracting(m -> m.get("team_id")).containsExactly(1L, 2L, null);   // "atanmamış" satırı en sonda
    }

    @Test
    @DisplayName("depo düşerse panel boş döner (liste etkilenmez)")
    void degradesGracefully() {
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenThrow(new IllegalStateException("db"));
        Map<String, Object> data = service.build(t -> true, true);
        assertThat(teams(data)).isEmpty();
        assertThat(data).containsEntry("total_open", 0L);
    }
}
