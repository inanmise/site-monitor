package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Yönetici özeti (2026-09-12, #20): KPI sayımı, delta, SLA ihlali sayımı, takım sırası (en kötü üstte), kapsam. Tarihler kayan. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutiveStatsServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock TeamRepository teamRepo;
    @Mock MonitorSparklineService sparklineService;
    @Mock AppSettingsService appSettings;
    @Mock com.sitemonitor.repository.CertificateCheckRepository certificateCheckRepo;
    ExecutiveStatsService svc;

    private static CertificateInventory inv(String d, Long team) { CertificateInventory i = new CertificateInventory(); i.setDomain(d); i.setTeamId(team); i.setActive(true); return i; }
    private static LatestCheck lc(String d, Integer days, String status) { LatestCheck c = new LatestCheck(); c.setDomain(d); c.setDaysRemaining(days); c.setStatus(status); return c; }
    private static AlertEvent ev(long id, String domain, Long team, String level, int daysAgo) {
        AlertEvent e = new AlertEvent(); e.setId(id); e.setDomain(domain); e.setTeamId(team); e.setAlertLevel(level); e.setAlertType("HTTP_DOWN");
        e.setCreatedAt(ISO.format(Instant.now().minus(daysAgo, ChronoUnit.DAYS))); return e;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> m(Map<String, Object> b, String k) { return (Map<String, Object>) b.get(k); }

    @BeforeEach
    void setUp() {
        svc = new ExecutiveStatsService(inventoryRepo, latestCheckRepo, alertEventRepo, teamRepo, sparklineService, appSettings, certificateCheckRepo);
        Team a = new Team(); a.setId(1L); a.setName("Takım A"); Team b = new Team(); b.setId(2L); b.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, b));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv("ok1.example.com", 1L), inv("ok2.example.com", 1L), inv("soon.example.com", 1L), inv("err.example.com", 1L),
                inv("bok.example.com", 2L), inv("bexp.example.com", 2L), inv("bexp2.example.com", 2L), inv("never.example.com", 2L)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("ok1.example.com", 200, "valid"), lc("ok2.example.com", 90, "valid"), lc("soon.example.com", 5, "valid"), lc("err.example.com", null, "error"),
                lc("bok.example.com", 60, "valid"), lc("bexp.example.com", -2, "valid"), lc("bexp2.example.com", -5, "valid")));
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(ev(1, "soon.example.com", 1L, "CRITICAL", 1), ev(2, "bexp.example.com", 2L, "WARNING", 3)));
        when(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString())).thenReturn(List.of(
                ev(1, "soon.example.com", 1L, "CRITICAL", 1), ev(2, "bexp.example.com", 2L, "WARNING", 3), ev(3, "ok1.example.com", 1L, "WARNING", 45), ev(4, "ok1.example.com", 1L, "WARNING", 50)));
        when(appSettings.getDouble(eq("site.monitor.sla.target-pct"), anyDouble())).thenReturn(99.9);
        when(sparklineService.monitorTeams(anyString())).thenReturn(Map.of());
        when(sparklineService.monitorTeams("http")).thenReturn(Map.of(10L, 1L, 11L, 2L));
        when(sparklineService.availability(eq("http"), anyInt(), any())).thenAnswer(i -> {
            Set<Long> ids = i.getArgument(2);
            Map<Long, Map<String, Object>> out = new java.util.HashMap<>();
            if (ids.contains(10L)) out.put(10L, Map.of("n", 100, "fail", 5, "up_pct", 95.0, "bad_hours", 2));
            if (ids.contains(11L)) out.put(11L, Map.of("n", 100, "fail", 0, "up_pct", 100.0, "bad_hours", 0));
            return out;
        });
    }

    @Test
    @DisplayName("global: sağlık %= temiz/değerlendirilen (hiç kontrol edilmeyen paydada değil); 30 gün altı/7 gün/dolmuş/hata; açık alarm + delta (2 son 30g − 2 önceki); SLA 1 ihlal / 2 monitör; takımlar en kötü üstte")
    void globalSummary() {
        Map<String, Object> b = svc.build(t -> true);
        Map<String, Object> c = m(b, "certs");
        assertThat(c).containsEntry("total", 8).containsEntry("ok", 3).containsEntry("under30", 1).containsEntry("under7", 1)
                .containsEntry("expired", 2).containsEntry("error", 1).containsEntry("unchecked", 1);
        assertThat((Double) c.get("health_pct")).isEqualTo(42.9);   // 3 / 7
        Map<String, Object> a = m(b, "alerts");
        assertThat(a).containsEntry("open", 2).containsEntry("critical", 1).containsEntry("opened_last30", 2).containsEntry("opened_prev30", 2).containsEntry("delta", 0);
        Map<String, Object> s = m(b, "sla");
        assertThat(s).containsEntry("monitors", 2).containsEntry("breaches", 1);
        @SuppressWarnings("unchecked") List<Map<String, Object>> teams = (List<Map<String, Object>>) b.get("teams");
        assertThat(teams).hasSize(2);
        assertThat(teams.get(0)).containsEntry("team_name", "Takım B").containsEntry("open_alerts", 1);   // 1/3 = %33 → en kötü
        assertThat(teams.get(1)).containsEntry("team_name", "Takım A");
        assertThat((Double) teams.get(1).get("health_pct")).isEqualTo(50.0);   // 2 ok / (2+1 soon+1 err)
    }

    @Test
    @DisplayName("takım 1 kapsamı: yalnız kendi alanları/alarmları/monitörleri; SLA ihlali 1 (monitör 10)")
    void scoped() {
        Map<String, Object> b = svc.build(t -> t != null && t == 1L);
        assertThat(m(b, "certs")).containsEntry("total", 4).containsEntry("expired", 0);
        assertThat(m(b, "alerts")).containsEntry("open", 1).containsEntry("critical", 1);
        assertThat(m(b, "sla")).containsEntry("monitors", 1).containsEntry("breaches", 1);
        @SuppressWarnings("unchecked") List<Map<String, Object>> teams = (List<Map<String, Object>>) b.get("teams");
        assertThat(teams).hasSize(1);
    }
    @Test
    @DisplayName("2026-09-12 #7: son 7 gün — yeni alan (created_at), silinen (deleted_at), yenilenen (parmak izi), açılan/çözülen alarm; kapsam")
    void recentChanges() {
        CertificateInventory fresh = inv("new.example.com", 1L); fresh.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        CertificateInventory old = inv("ok1.example.com", 1L); old.setCreatedAt(ISO.format(Instant.now().minus(40, ChronoUnit.DAYS)));
        CertificateInventory gone = inv("gone.example.com", 1L); gone.setDeletedAt(ISO.format(Instant.now().minus(1, ChronoUnit.DAYS)));
        CertificateInventory foreign = inv("f.example.com", 2L); foreign.setCreatedAt(ISO.format(Instant.now().minus(1, ChronoUnit.DAYS)));
        when(inventoryRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(fresh, old, gone, foreign));
        when(certificateCheckRepo.domainsWithFingerprintChangeSince(anyString())).thenReturn(List.of("ok1.example.com", "f.example.com"));
        Map<String, Object> c = svc.recentChanges(7, t -> t != null && t == 1L);
        assertThat(c).containsEntry("days", 7).containsEntry("added", 1).containsEntry("removed", 1).containsEntry("renewed", 1)
                .containsEntry("alerts_opened", 1).containsEntry("alerts_resolved", 0);   // setUp: ev(1) 1 gün önce açık; 45/50 gün öncekiler dışarıda
    }
}
