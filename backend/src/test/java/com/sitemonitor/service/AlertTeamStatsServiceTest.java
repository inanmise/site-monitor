package com.sitemonitor.service;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /** Dar izdüşüm satırı (2026-11-13): {@code [id, teamId, domain, resolved, createdAt]}. */
    private static Object[] ev(long id, Long teamId, String domain, boolean resolved, String createdAt) {
        return new Object[]{id, teamId, domain, resolved, createdAt};
    }

    private static Object[] inv(String domain, Long sy, Long ug) { return new Object[]{domain, sy, ug}; }

    private static String daysAgo(int d) { return ISO.format(Instant.now().minus(d, ChronoUnit.DAYS).plusSeconds(60)); }

    @BeforeEach
    void setUp() {
        service = new AlertTeamStatsService(alertEventRepo, inventoryRepo, teamRepo);
        Team a = new Team(); a.setId(1L); a.setName("Takım A");
        Team b = new Team(); b.setId(2L); b.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, b));
        when(inventoryRepo.findActiveDomainTeams()).thenReturn(List.<Object[]>of(inv("cert.example.com", 2L, null)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> teams(Map<String, Object> data) { return (List<Map<String, Object>>) data.get("teams"); }

    @Test
    @DisplayName("takım_id ve envanter türevli alarmlar birlikte sayılır; açık/kapalı ve 7/30 gün kırılımı doğru")
    void countsBothSources() {
        when(alertEventRepo.findOpenTeamStatRows()).thenReturn(List.<Object[]>of(
                ev(1, 1L, "ping.example.com", false, daysAgo(40)),      // eski ama AÇIK → açık sayılır, 30 gün sütununa girmez
                ev(2, null, "cert.example.com", false, daysAgo(3))));   // envanterden Takım B
        when(alertEventRepo.findTeamStatRowsSince(anyString())).thenReturn(List.<Object[]>of(
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
        when(alertEventRepo.findOpenTeamStatRows()).thenReturn(List.<Object[]>of(
                ev(1, 1L, "ping.example.com", false, daysAgo(1)),
                ev(2, 2L, "other.example.com", false, daysAgo(1)),
                ev(3, null, "bilinmeyen.example.com", false, daysAgo(1))));
        when(alertEventRepo.findTeamStatRowsSince(anyString())).thenReturn(List.<Object[]>of());

        Map<String, Object> scoped = service.build(t -> t != null && t == 1L, false);
        assertThat(teams(scoped)).extracting(m -> m.get("team_name")).containsExactly("Takım A");
        assertThat(scoped).containsEntry("total_open", 1L);

        Map<String, Object> global = service.build(t -> true, true);
        assertThat(teams(global)).extracting(m -> m.get("team_id")).containsExactly(1L, 2L, null);   // "atanmamış" satırı en sonda
    }

    @Test
    @DisplayName("ALERTS-3 / DATA-5 (2026-10-22): SY damgalı sertifika alarmı UG takımının satırında da sayılır (hücre penceresiyle aynı küme); toplam alarm BİR kez")
    void ugTeamRowCounts() {
        when(inventoryRepo.findActiveDomainTeams()).thenReturn(List.<Object[]>of(inv("ug.example.com", 2L, 1L)));
        when(alertEventRepo.findOpenTeamStatRows()).thenReturn(List.<Object[]>of(ev(7, 2L, "ug.example.com", false, daysAgo(1))));
        when(alertEventRepo.findTeamStatRowsSince(anyString())).thenReturn(List.<Object[]>of());

        Map<String, Object> ugView = service.build(t -> t != null && t == 1L, false);
        assertThat(teams(ugView)).extracting(m -> m.get("team_id")).containsExactly(1L);
        assertThat(teams(ugView).get(0)).containsEntry("open", 1L);
        assertThat(ugView).containsEntry("total_open", 1L);

        Map<String, Object> global = service.build(t -> true, true);
        assertThat(teams(global)).extracting(m -> m.get("team_id")).containsExactlyInAnyOrder(1L, 2L);
        assertThat(global).as("toplam alarmı bir kez sayar").containsEntry("total_open", 1L);
    }

    @Test
    @DisplayName("hata taraması 2026-11-13: alarm ya da envanter okunamazsa FIRLAR (eskiden boş tablo → karnede her takıma 0)")
    void readFailureIsUnavailable() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(alertEventRepo).findOpenTeamStatRows();
        assertThatThrownBy(() -> service.build(t -> true, true)).isInstanceOf(AlertTeamStatsService.UnavailableException.class);
        org.mockito.Mockito.doReturn(List.of()).when(alertEventRepo).findOpenTeamStatRows();
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(alertEventRepo).findTeamStatRowsSince(anyString());
        assertThatThrownBy(() -> service.build(t -> true, true)).isInstanceOf(AlertTeamStatsService.UnavailableException.class);
        org.mockito.Mockito.doReturn(List.of()).when(alertEventRepo).findTeamStatRowsSince(anyString());
        assertThat(service.build(t -> true, true)).containsEntry("total_open", 0L);   // okununca gerçek boş tablo
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(inventoryRepo).findActiveDomainTeams();
        assertThatThrownBy(() -> service.build(t -> true, true)).isInstanceOf(AlertTeamStatsService.UnavailableException.class);
    }

    @Test
    @DisplayName("2026-11-13 performans: tam entity okunmaz (context_json) — yalnız dar izdüşümler")
    void readsNarrowProjectionsOnly() {
        when(alertEventRepo.findOpenTeamStatRows()).thenReturn(List.<Object[]>of(ev(1, 1L, "ping.example.com", false, daysAgo(1))));
        when(alertEventRepo.findTeamStatRowsSince(anyString())).thenReturn(List.of());
        service.build(t -> true, true);
        org.mockito.Mockito.verify(alertEventRepo, org.mockito.Mockito.never()).findAllOpenOrderBySeverity();
        org.mockito.Mockito.verify(alertEventRepo, org.mockito.Mockito.never()).findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString());
        org.mockito.Mockito.verify(inventoryRepo, org.mockito.Mockito.never()).findByActiveTrueOrderByDomainAsc();
    }
}
