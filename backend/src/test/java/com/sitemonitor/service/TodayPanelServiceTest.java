package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
    @Mock TodayMonitorInsightsService monitorInsights;
    @Mock WeeklyReportRepository weeklyReportRepo;
    @Mock TeamRepository teamRepo;
    TodayPanelService svc;

    private static CertificateInventory inv(String d, Long team) { CertificateInventory i = new CertificateInventory(); i.setDomain(d); i.setTeamId(team); i.setActive(true); return i; }
    private static LatestCheck lc(String d, Integer days) { LatestCheck c = new LatestCheck(); c.setDomain(d); c.setDaysRemaining(days); return c; }
    @SuppressWarnings("unchecked") private static Map<String, Object> block(Map<String, Object> b, String k) { return (Map<String, Object>) b.get(k); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> items(Map<String, Object> blk) { return (List<Map<String, Object>>) blk.get("items"); }
    private static Map<String, Object> nrow(String channel, String target, String error, String domain, Long team) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("channel", channel); m.put("target", target); m.put("error", error); m.put("at", "2026-09-19T10:00:00"); m.put("domain", domain); m.put("team_id", team);
        return m;
    }
    private static Map<String, Object> mrow(String type, Long id, String name, String target, String domain, Long team, Map<String, Object> extra) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", type); m.put("monitor_id", id); m.put("name", name); m.put("target", target); m.put("domain", domain); m.put("team_id", team);
        m.putAll(extra);
        return m;
    }

    @BeforeEach
    void setUp() {
        svc = new TodayPanelService(inventoryRepo, latestCheckRepo, alertEventRepo, weeklyReportRepo, teamRepo, monitorInsights);
        Team a = new Team(); a.setId(1L); a.setName("Takım A"); a.setWeeklyReportsEnabled(true);   // modül açık (2026-09-16)
        when(teamRepo.findAll()).thenReturn(List.of(a));
        when(teamRepo.findById(1L)).thenReturn(Optional.of(a));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("soon.example.com", 1L), inv("exp.example.com", 1L), inv("far.example.com", 1L), inv("other.example.com", 2L)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(lc("soon.example.com", 12), lc("exp.example.com", -3), lc("far.example.com", 200), lc("other.example.com", 2)));
        AlertEvent open = new AlertEvent(); open.setId(9L); open.setDomain("soon.example.com"); open.setAlertType("HTTP_DOWN"); open.setAlertLevel("CRITICAL"); open.setTeamId(1L);
        AlertEvent foreign = new AlertEvent(); foreign.setId(10L); foreign.setDomain("other.example.com"); foreign.setAlertType("X"); foreign.setAlertLevel("WARNING"); foreign.setTeamId(2L);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(open, foreign));
        // İzleme anlık görüntüsü (2026-09-19): takım 1'de kararsız 1 + takımsız DNS (envanterde görünür alan) 1,
        // takım 2'de yavaş 1; bayat 1 (takım 1) + duraklatılmış 3; alan adı: takım 1'de -2 gün (dolmuş) ve 12 gün, takım 2'de 5 gün.
        when(monitorInsights.snapshot()).thenReturn(new TodayMonitorInsightsService.Snapshot(
                List.of(mrow("HTTP", 1L, "api", "https://soon.example.com/x", "soon.example.com", 1L, Map.of("transitions", 4, "last_status", "DOWN")),
                        mrow("DNS", 2L, "dns-a", "other.example.com", "other.example.com", null, Map.of("transitions", 3, "last_status", "UP")),
                        mrow("DNS", 3L, "dns-b", "soon.example.com", "soon.example.com", null, Map.of("transitions", 3, "last_status", "UP"))),
                List.of(mrow("PING", 4L, "gw", "10.0.0.1", "10.0.0.1", 2L, Map.of("today_ms", 300L, "baseline_ms", 100L, "ratio", 3.0))),
                List.of(mrow("PORT", 5L, "smtp", "mail.example.com:25", "mail.example.com", 1L, Map.of("age_min", 90L, "expected_min", 10L))),
                3,
                List.of(mrow("DOMAIN", 6L, "d1", "exp.example.com", "exp.example.com", 1L, Map.of("days", -2)),
                        mrow("DOMAIN", 7L, "d2", "other.example.com", "other.example.com", 2L, Map.of("days", 5)),
                        mrow("DOMAIN", 8L, "d3", "soon.example.com", "soon.example.com", 1L, Map.of("days", 12))),
                1,
                // bildirim: takım 1 e-posta + takım 2 push + takımsız-ama-alanı-görünür webhook
                List.of(nrow("EMAIL", "a@example.com", "SMTP 550", "soon.example.com", 1L),
                        nrow("PUSH", "user1", "HTTP 500", null, 2L),
                        nrow("WEBHOOK", "hook", "timeout", "exp.example.com", null)),
                // sağlık: takım 1'de kritik (güven) + takım 2'de zayıf imza
                List.of(Map.of("domain", "exp.example.com", "team_id", 1L, "critical", true, "silenced", false,
                                "findings", List.of(Map.of("key", "trust", "value_key", "untrusted", "value_args", List.of()))),
                        Map.of("domain", "other.example.com", "team_id", 2L, "critical", false, "silenced", false,
                                "findings", List.of(Map.of("key", "signature", "value_key", "weakAlgorithm", "value_args", List.of("SHA1withRSA")))))));
        WeeklyReport draft = new WeeklyReport(); draft.setId(77L); draft.setStatus("DRAFT");
        when(weeklyReportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.of(draft));
    }

    @Test
    @DisplayName("limit: panel TOP(5) ile kırpar, full (MAX) tamamını döner; count her iki halde de tam sayı (2026-09-18)")
    void limit_capsItemsButNotCount() {
        java.util.List<CertificateInventory> invs = new java.util.ArrayList<>();
        java.util.List<LatestCheck> lcs = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) { invs.add(inv("c" + i + ".example.com", 1L)); lcs.add(lc("c" + i + ".example.com", i + 1)); }
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(invs);
        when(latestCheckRepo.findAll()).thenReturn(lcs);
        Map<String, Object> capped = block(svc.build(t -> true, List.of(1L)), "certs");
        assertThat(capped.get("count")).isEqualTo(8);
        assertThat(items(capped)).hasSize(TodayPanelService.TOP);
        Map<String, Object> full = block(svc.build(t -> true, List.of(1L), Integer.MAX_VALUE), "certs");
        assertThat(full.get("count")).isEqualTo(8);
        assertThat(items(full)).hasSize(8);
    }

    @Test
    @DisplayName("takım 1 kullanıcısı: 30 gün altı 2 (1 dolmuş, en az gün üstte), açık alarm 1 (takım 2'ninki elenir), izleme kartları takım/alan süzgeçli, haftalık DRAFT → eksik")
    void scopedPanel() {
        Map<String, Object> b = svc.build(t -> t != null && t == 1L, List.of(1L));
        Map<String, Object> certs = block(b, "certs");
        assertThat(certs).containsEntry("count", 2).containsEntry("expired", 1);
        assertThat(items(certs).get(0)).containsEntry("domain", "exp.example.com").containsEntry("team_name", "Takım A");
        Map<String, Object> alerts = block(b, "alerts");
        assertThat(alerts).containsEntry("count", 1).containsEntry("critical", 1);
        assertThat(items(alerts).get(0)).containsEntry("id", 9L).containsEntry("type", "HTTP_DOWN");
        // İzleme kartları: takım 1 satırları + takımsız-ama-alanı-görünür satır girer; takım 2 / görünmez alan elenir;
        // önbellek satırı kopyalanır (team_name eklenir), sıralama anlık görüntüden korunur.
        Map<String, Object> flap = block(b, "flapping");
        assertThat(flap).containsEntry("count", 2);
        assertThat(items(flap)).extracting(m -> m.get("monitor_id")).containsExactly(1L, 3L);
        assertThat(items(flap).get(0)).containsEntry("team_name", "Takım A").containsEntry("transitions", 4);
        assertThat(items(flap).get(1)).containsEntry("team_name", null);
        assertThat(block(b, "slow")).containsEntry("count", 0);
        Map<String, Object> stale = block(b, "stale");
        assertThat(stale).containsEntry("count", 1).containsEntry("paused", 3);
        Map<String, Object> domains = block(b, "domains");
        assertThat(domains).containsEntry("count", 2).containsEntry("expired", 1L);
        assertThat(items(domains).get(0)).containsEntry("domain", "exp.example.com").containsEntry("days", -2);
        assertThat(monitorInsights.snapshot().flapping().get(0)).doesNotContainKey("team_name");   // önbellek satırı değişmedi
        // Bildirim: takım 1 e-posta + görünür alanın webhook'u girer, takım 2 push elenir; kanal sayaçları
        Map<String, Object> notif = block(b, "notifications");
        assertThat(notif).containsEntry("count", 2).containsEntry("email", 1L).containsEntry("webhook", 1L).containsEntry("push", 0L);
        // Sağlık: yalnız takım 1'in kritik bulgusu
        Map<String, Object> health = block(b, "health");
        assertThat(health).containsEntry("count", 1).containsEntry("critical", 1L);
        assertThat(items(health).get(0)).containsEntry("domain", "exp.example.com").containsEntry("team_name", "Takım A");
        Map<String, Object> weekly = block(b, "weekly");
        assertThat(weekly).containsEntry("count", 1).containsEntry("missing", 1);
        assertThat(items(weekly).get(0)).containsEntry("status", "DRAFT").containsEntry("report_id", 77L).containsEntry("team_name", "Takım A");
        assertThat(b.get("scope_domains")).isEqualTo(3);
    }

    @Test
    @DisplayName("global görüntüleyici, kendi takımı yok: her şeyi görür ama haftalık blok boş (count 0) — 'tüm takımlar eksik' gürültüsü yok; izleme anlık görüntüsü düşerse diğerleri kalır")
    void globalViewer() {
        when(weeklyReportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(monitorInsights.snapshot()).thenThrow(new IllegalStateException("db"));
        Map<String, Object> b = svc.build(t -> true, List.of());
        assertThat(block(b, "certs")).containsEntry("count", 3);   // other.example.com (2 gün) da girer
        assertThat(block(b, "alerts")).containsEntry("count", 2);
        assertThat(block(b, "weekly")).containsEntry("count", 0).containsEntry("missing", 0);
        // İzleme anlık görüntüsü düşerse dört izleme kartı da boş (count 0) çizilir, panel yıkılmaz.
        for (String k : List.of("flapping", "slow", "stale", "domains", "notifications", "health")) assertThat(block(b, k)).containsEntry("count", 0);
        assertThat(block(b, "stale")).containsEntry("paused", 0);
    }
    @Test
    @DisplayName("2026-09-16: Haftalık Raporlar modülü KAPALI takım haftalık kartta sayılmaz (count 0 → arayüz kartı çizmez)")
    void weeklyBlock_skipsDisabledTeams() {
        Team off = new Team(); off.setId(1L); off.setName("Takım A"); off.setWeeklyReportsEnabled(false);
        when(teamRepo.findById(1L)).thenReturn(Optional.of(off));

        Map<String, Object> weekly = block(svc.build(t -> t != null && t == 1L, List.of(1L)), "weekly");

        assertThat(weekly).containsEntry("count", 0).containsEntry("missing", 0);
        assertThat(items(weekly)).isEmpty();
    }
}
