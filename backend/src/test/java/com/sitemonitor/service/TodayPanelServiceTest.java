package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** "Sizin için — bugün" (2026-09-12, #3): kapsam predicate'i, eşikler, sıralama, haftalık rapor durumu. Tarihler kayan. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TodayPanelServiceTest {

    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock WeakAlgorithmExceptionRepository exceptionRepo;
    @Mock WeeklyReportRepository weeklyReportRepo;
    @Mock TeamRepository teamRepo;
    TodayPanelService svc;

    private static CertificateInventory inv(String d, Long team) { CertificateInventory i = new CertificateInventory(); i.setDomain(d); i.setTeamId(team); i.setActive(true); return i; }
    private static LatestCheck lc(String d, Integer days) { LatestCheck c = new LatestCheck(); c.setDomain(d); c.setDaysRemaining(days); return c; }
    @SuppressWarnings("unchecked") private static Map<String, Object> block(Map<String, Object> b, String k) { return (Map<String, Object>) b.get(k); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> items(Map<String, Object> blk) { return (List<Map<String, Object>>) blk.get("items"); }

    @BeforeEach
    void setUp() {
        svc = new TodayPanelService(inventoryRepo, latestCheckRepo, alertEventRepo, exceptionRepo, weeklyReportRepo, teamRepo);
        Team a = new Team(); a.setId(1L); a.setName("Takım A");
        when(teamRepo.findAll()).thenReturn(List.of(a));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("soon.example.com", 1L), inv("exp.example.com", 1L), inv("far.example.com", 1L), inv("other.example.com", 2L)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(lc("soon.example.com", 12), lc("exp.example.com", -3), lc("far.example.com", 200), lc("other.example.com", 2)));
        AlertEvent open = new AlertEvent(); open.setId(9L); open.setDomain("soon.example.com"); open.setAlertType("HTTP_DOWN"); open.setAlertLevel("CRITICAL"); open.setTeamId(1L);
        AlertEvent foreign = new AlertEvent(); foreign.setId(10L); foreign.setDomain("other.example.com"); foreign.setAlertType("X"); foreign.setAlertLevel("WARNING"); foreign.setTeamId(2L);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(open, foreign));
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Istanbul"));
        WeakAlgorithmException soonEx = new WeakAlgorithmException(); soonEx.setDomain("soon.example.com"); soonEx.setUntil(today.plusDays(5).toString());
        WeakAlgorithmException oldEx = new WeakAlgorithmException(); oldEx.setDomain("far.example.com"); oldEx.setUntil(today.minusDays(2).toString());
        WeakAlgorithmException lateEx = new WeakAlgorithmException(); lateEx.setDomain("exp.example.com"); lateEx.setUntil(today.plusDays(60).toString());
        when(exceptionRepo.findAll()).thenReturn(List.of(soonEx, oldEx, lateEx));
        WeeklyReport draft = new WeeklyReport(); draft.setId(77L); draft.setStatus("DRAFT");
        when(weeklyReportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.of(draft));
    }

    @Test
    @DisplayName("takım 1 kullanıcısı: 30 gün altı 2 (1 dolmuş, en az gün üstte), açık alarm 1 (takım 2'ninki elenir), istisna 2 (60 gün elenir, dolmuş 1), haftalık DRAFT → eksik")
    void scopedPanel() {
        Map<String, Object> b = svc.build(t -> t != null && t == 1L, List.of(1L));
        Map<String, Object> certs = block(b, "certs");
        assertThat(certs).containsEntry("count", 2).containsEntry("expired", 1);
        assertThat(items(certs).get(0)).containsEntry("domain", "exp.example.com").containsEntry("team_name", "Takım A");
        Map<String, Object> alerts = block(b, "alerts");
        assertThat(alerts).containsEntry("count", 1).containsEntry("critical", 1);
        assertThat(items(alerts).get(0)).containsEntry("id", 9L).containsEntry("type", "HTTP_DOWN");
        Map<String, Object> exc = block(b, "exceptions");
        assertThat(exc).containsEntry("count", 2).containsEntry("expired", 1);
        assertThat(items(exc).get(0)).containsEntry("domain", "far.example.com");
        Map<String, Object> weekly = block(b, "weekly");
        assertThat(weekly).containsEntry("count", 1).containsEntry("missing", 1);
        assertThat(items(weekly).get(0)).containsEntry("status", "DRAFT").containsEntry("report_id", 77L).containsEntry("team_name", "Takım A");
        assertThat(b.get("scope_domains")).isEqualTo(3);
    }

    @Test
    @DisplayName("global görüntüleyici, kendi takımı yok: her şeyi görür ama haftalık blok boş (count 0) — 'tüm takımlar eksik' gürültüsü yok; bir blok düşerse diğerleri kalır")
    void globalViewer() {
        when(weeklyReportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(exceptionRepo.findAll()).thenThrow(new IllegalStateException("db"));
        Map<String, Object> b = svc.build(t -> true, List.of());
        assertThat(block(b, "certs")).containsEntry("count", 3);   // other.example.com (2 gün) da girer
        assertThat(block(b, "alerts")).containsEntry("count", 2);
        assertThat(block(b, "weekly")).containsEntry("count", 0).containsEntry("missing", 0);
        assertThat(block(b, "exceptions")).containsEntry("count", 0).containsEntry("error", "IllegalStateException");
    }
}
