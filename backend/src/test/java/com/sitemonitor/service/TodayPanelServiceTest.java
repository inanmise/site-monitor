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
import com.sitemonitor.repository.TodayPanelSnapshotRepository;
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
    @Mock TodayPanelSnapshotRepository snapshotRepo;
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

    private static Map<String, Object> mnt(String kind, Long id, String name, Long team, boolean pub) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kind", kind); m.put("window_id", id); m.put("name", name); m.put("team_id", team); m.put("domain", null); m.put("public", pub);
        return m;
    }
    private static Map<String, Object> exrow(String domain, Long team, long daysLeft) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kind", "EXCEPTION"); m.put("domain", domain); m.put("team_id", team); m.put("days_left", daysLeft);
        return m;
    }
    private static Map<String, Object> ident(Long id, Long team, String domain) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id); m.put("team_id", team); m.put("domain", domain);
        return m;
    }

    @BeforeEach
    void setUp() {
        svc = new TodayPanelService(inventoryRepo, latestCheckRepo, alertEventRepo, weeklyReportRepo, teamRepo, monitorInsights, snapshotRepo);
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
                // duraklatılmış (2026-09-23: sayı değil satır): takım 1'de 10 gündür + 2 gündür, takım 2'de 30 gündür
                List.of(mrow("HTTP", 11L, "eski-api", "https://soon.example.com/old", "soon.example.com", 1L, Map.of("kind", "PAUSED", "paused_days", 10L)),
                        mrow("PING", 12L, "gw2", "10.0.0.2", "10.0.0.2", 1L, Map.of("kind", "PAUSED", "paused_days", 2L)),
                        mrow("PORT", 13L, "b-port", "b.internal:22", "b.internal", 2L, Map.of("kind", "PAUSED", "paused_days", 30L))),
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
                                "findings", List.of(Map.of("key", "signature", "value_key", "weakAlgorithm", "value_args", List.of("SHA1withRSA"))))),
                // bakım: herkese açık süren + takım 2'nin ve takım 1'in yaklaşan penceresi
                List.of(mnt("MAINT_ACTIVE", 1L, "Gece bakımı", null, true),
                        mnt("MAINT_SOON", 3L, "Takım A bakımı", 1L, false),
                        mnt("MAINT_SOON", 2L, "Takım B bakımı", 2L, false)),
                // dolacak istisna: takım 1'in alanı + takım 2'nin alanı
                List.of(exrow("soon.example.com", 1L, 2L), exrow("other.example.com", 2L, 1L)),
                // son 24 saat: açılan 3 (takım 1 · takım 2 · takımsız-ama-alanı-görünür), çözülen 1 (takım 2), yenilenen 2
                Map.of("opened", List.of(ident(1L, 1L, "soon.example.com"), ident(2L, 2L, "other.example.com"), ident(3L, null, "exp.example.com")),
                        "resolved", List.of(ident(4L, 2L, "other.example.com")),
                        "renewed", List.of(ident(null, null, "far.example.com"), ident(null, null, "other.example.com")))));
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
        // 2026-09-23: "paused" TÜM takımların sayısıydı (3) — kapsam sızıntısı; artık yalnız görünür satırlar (takım 1: 2).
        assertThat(stale).containsEntry("count", 1).containsEntry("paused", 2);
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

    // ── Susturulmuş ve bakımda (2026-09-23) ─────────────────────────────────────────────────

    @Test
    @DisplayName("quiet: takım 1 kullanıcısı herkese açık + kendi takımının bakımını, kendi duraklatılmışlarını ve kendi alanının istisnasını görür; sıra bakım(süren→yaklaşan) → duraklatılmış → istisna")
    void quiet_scopedAndOrdered() {
        Map<String, Object> q = block(svc.build(t -> t != null && t == 1L, List.of(1L)), "quiet");
        assertThat(q).containsEntry("count", 5).containsEntry("maint_active", 1L).containsEntry("maint_soon", 1L)
                .containsEntry("paused", 2).containsEntry("paused_long", 1L).containsEntry("exceptions", 1);
        assertThat(items(q)).extracting(m -> m.get("kind"))
                .containsExactly("MAINT_ACTIVE", "MAINT_SOON", "PAUSED", "PAUSED", "EXCEPTION");
        assertThat(items(q)).extracting(m -> m.get("window_id") != null ? "MW:" + m.get("window_id") : m.get("monitor_id") != null ? m.get("monitor_id") : m.get("domain"))
                .containsExactly("MW:1", "MW:3", 11L, 12L, "soon.example.com");
        assertThat(items(q).get(1)).containsEntry("team_name", "Takım A");
    }

    @Test
    @DisplayName("quiet: maintenance.view izni yoksa bakım satırları kartta HİÇ çıkmaz (pencere adları sızmaz); diğerleri kalır")
    void quiet_hidesMaintenanceWithoutPermission() {
        Map<String, Object> q = block(svc.build(t -> t != null && t == 1L, List.of(1L), TodayPanelService.TOP, false), "quiet");
        assertThat(q).containsEntry("count", 3).containsEntry("maint_active", 0L).containsEntry("maint_soon", 0L);
        assertThat(items(q)).extracting(m -> m.get("kind")).doesNotContain("MAINT_ACTIVE", "MAINT_SOON");
    }

    @Test
    @DisplayName("recent: son 24 saat şeridi görünür satırları sayar — açılan 2 (takım 1 + görünür alan), çözülen 0 (takım 2'ninki), yenilenen 1")
    void recent_scoped() {
        Map<String, Object> r = block(svc.build(t -> t != null && t == 1L, List.of(1L)), "recent");
        assertThat(r).containsEntry("opened", 2).containsEntry("resolved", 0).containsEntry("renewed", 1).containsEntry("hours", 24);
        Map<String, Object> g = block(svc.build(t -> true, List.of()), "recent");
        assertThat(g).containsEntry("opened", 3).containsEntry("resolved", 1).containsEntry("renewed", 2);
    }

    // ── Dünden bugüne (2026-09-23) ──────────────────────────────────────────────────────────

    private static final java.time.format.DateTimeFormatter ISO =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC);

    private static com.sitemonitor.model.TodayPanelSnapshot snap(java.time.Instant takenAt, String payload) {
        com.sitemonitor.model.TodayPanelSnapshot s = new com.sitemonitor.model.TodayPanelSnapshot();
        s.setTakenAt(ISO.format(takenAt)); s.setPayload(payload);
        return s;
    }

    @Test
    @DisplayName("trend: ~24 saat önceki görüntü kullanıcının görünürlüğüyle sayılır → prev; görüntüde olmayan karta prev YAZILMAZ; trend_at döner")
    void trend_prevCountsAreScoped() {
        java.time.Instant now = java.time.Instant.now();
        String payload = """
                {"certs":[{"k":"soon.example.com","d":"soon.example.com"},{"k":"other.example.com","d":"other.example.com"}],
                 "alerts":[{"k":"A:9","t":1,"d":"soon.example.com"},{"k":"A:5","t":1},{"k":"A:6","t":2}],
                 "quiet":[{"k":"MW:9","m":true,"a":true},{"k":"MW:8","m":true,"t":2},{"k":"HTTP:11","t":1,"d":"soon.example.com"}]}
                """;
        com.sitemonitor.model.TodayPanelSnapshot s = snap(now.minus(java.time.Duration.ofHours(24).plusMinutes(10)), payload);
        when(snapshotRepo.findFirstByTakenAtLessThanEqualOrderByTakenAtDesc(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.of(s));

        Map<String, Object> b = svc.build(t -> t != null && t == 1L, List.of(1L), TodayPanelService.TOP, true, now);

        assertThat(block(b, "certs")).containsEntry("prev", 1L);    // other.example.com takım 2'nin — sayılmaz
        assertThat(block(b, "alerts")).containsEntry("prev", 2L);
        assertThat(block(b, "quiet")).containsEntry("prev", 2L);    // herkese açık bakım + kendi duraklatılmışı; takım 2 penceresi yok
        assertThat(block(b, "flapping")).doesNotContainKey("prev"); // görüntüde kart yok → "+N" uydurulmaz
        assertThat(block(b, "weekly")).doesNotContainKey("prev");   // haftalık karta trend yok
        assertThat(b.get("trend_at")).isEqualTo(s.getTakenAt());
        // bakım izni yoksa bakım kimlikleri de sayılmaz
        Map<String, Object> noMaint = svc.build(t -> t != null && t == 1L, List.of(1L), TodayPanelService.TOP, false, now.plusSeconds(1));
        assertThat(block(noMaint, "quiet")).containsEntry("prev", 1L);
    }

    @Test
    @DisplayName("trend: 24+3 saatten eski görüntü (uzun kesinti) ya da hiç görüntü yoksa karşılaştırma yapılmaz")
    void trend_tooOldOrMissing() {
        java.time.Instant now = java.time.Instant.now();
        when(snapshotRepo.findFirstByTakenAtLessThanEqualOrderByTakenAtDesc(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(snap(now.minus(java.time.Duration.ofHours(28)), "{\"certs\":[]}")));
        Map<String, Object> b = svc.build(t -> true, List.of(), TodayPanelService.TOP, true, now);
        assertThat(block(b, "certs")).doesNotContainKey("prev");
        assertThat(b).doesNotContainKey("trend_at");
    }

    @Test
    @DisplayName("trend kaydı: son görüntü 50 dk'dan yeniyse yazılmaz; değilse TÜM takımların kart kimlikleri yazılır ve 72 saatten eskiler silinir")
    void trend_recordSnapshot() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        when(snapshotRepo.findFirstByOrderByTakenAtDesc()).thenReturn(Optional.of(snap(now.minus(java.time.Duration.ofMinutes(20)), "{}")));
        svc.recordTrendSnapshot(now);
        org.mockito.Mockito.verify(snapshotRepo, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());

        when(snapshotRepo.findFirstByOrderByTakenAtDesc()).thenReturn(Optional.of(snap(now.minus(java.time.Duration.ofHours(2)), "{}")));
        svc.recordTrendSnapshot(now);
        org.mockito.ArgumentCaptor<com.sitemonitor.model.TodayPanelSnapshot> cap = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.TodayPanelSnapshot.class);
        org.mockito.Mockito.verify(snapshotRepo).save(cap.capture());
        org.mockito.Mockito.verify(snapshotRepo).deleteOlderThan(ISO.format(now.minus(java.time.Duration.ofHours(72))));
        assertThat(cap.getValue().getTakenAt()).isEqualTo(ISO.format(now));
        Map<String, List<Map<String, Object>>> cards = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(cap.getValue().getPayload(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(cards.keySet()).containsExactlyElementsOf(TodayPanelService.TREND_CARDS);
        assertThat(cards.get("certs")).extracting(m -> m.get("k")).containsExactly("exp.example.com", "other.example.com", "soon.example.com");   // en az gün üstte
        assertThat(cards.get("alerts")).extracting(m -> m.get("k")).containsExactly("A:9", "A:10");
        assertThat(cards.get("quiet")).extracting(m -> m.get("k")).contains("MW:1", "MW:2", "MW:3", "HTTP:11", "PORT:13", "EXCEPTION:other.example.com");
        assertThat(cards.get("quiet").get(0)).containsEntry("m", true).containsEntry("a", true);
    }
}
