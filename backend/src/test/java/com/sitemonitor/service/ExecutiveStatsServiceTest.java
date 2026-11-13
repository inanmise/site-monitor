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

    private static Object[] row(String d, Long sy, Long ug) { return new Object[]{d, sy, ug}; }
    private static Object[] open(Long team, String domain, String level) { return new Object[]{team, domain, level}; }
    private static Object[] created(Long team, String domain, int daysAgo) {
        return new Object[]{team, domain, ISO.format(Instant.now().minus(daysAgo, ChronoUnit.DAYS))};
    }

    @BeforeEach
    void setUp() {
        svc = new ExecutiveStatsService(inventoryRepo, latestCheckRepo, alertEventRepo, teamRepo, sparklineService, appSettings, certificateCheckRepo);
        Team a = new Team(); a.setId(1L); a.setName("Takım A"); Team b = new Team(); b.setId(2L); b.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, b));
        when(inventoryRepo.findActiveDomainTeams()).thenReturn(List.of(
                row("ok1.example.com", 1L, null), row("ok2.example.com", 1L, null), row("soon.example.com", 1L, null), row("err.example.com", 1L, null),
                row("bok.example.com", 2L, null), row("bexp.example.com", 2L, null), row("bexp2.example.com", 2L, null), row("never.example.com", 2L, null)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("ok1.example.com", 200, "valid"), lc("ok2.example.com", 90, "valid"), lc("soon.example.com", 5, "valid"), lc("err.example.com", null, "error"),
                lc("bok.example.com", 60, "valid"), lc("bexp.example.com", -2, "valid"), lc("bexp2.example.com", -5, "valid")));
        when(alertEventRepo.findOpenStatRows()).thenReturn(List.of(open(1L, "soon.example.com", "CRITICAL"), open(2L, "bexp.example.com", "WARNING")));
        when(alertEventRepo.findCreatedStatRowsSince(anyString())).thenReturn(List.of(
                created(1L, "soon.example.com", 1), created(2L, "bexp.example.com", 3), created(1L, "ok1.example.com", 45), created(1L, "ok1.example.com", 50)));
        when(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString())).thenReturn(List.of(
                ev(1, "soon.example.com", 1L, "CRITICAL", 1), ev(2, "bexp.example.com", 2L, "WARNING", 3), ev(3, "ok1.example.com", 1L, "WARNING", 45), ev(4, "ok1.example.com", 1L, "WARNING", 50)));
        when(appSettings.getDouble(eq("site.monitor.sla.target-pct"), anyDouble())).thenReturn(99.9);
        when(sparklineService.monitorTeams(anyString())).thenReturn(Map.of());
        when(sparklineService.monitorTeams("http")).thenReturn(Map.of(10L, 1L, 11L, 2L));
        // Ortak (tüm filo) SLA önbelleği — görünür alt küme MonitorSparklineService.subset ile (gerçek statik)
        when(sparklineService.availabilityAll(eq("http"), anyInt())).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                10L, Map.of("n", 100, "fail", 5, "up_pct", 95.0, "bad_hours", 2),
                11L, Map.of("n", 100, "fail", 0, "up_pct", 100.0, "bad_hours", 0))));
        when(sparklineService.monitorNames(eq("http"), any())).thenReturn(Map.of(10L, "Ödeme API"));
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
    @DisplayName("2026-09-12 #7: son 7 gün — yeni alan (created_at), silinen (2026-10-07: geçmişteki DELETE satırı), yenilenen (parmak izi), açılan/çözülen alarm; kapsam")
    void recentChanges() {
        CertificateInventory fresh = inv("new.example.com", 1L); fresh.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        fresh.setId(101L);
        CertificateInventory old = inv("ok1.example.com", 1L); old.setCreatedAt(ISO.format(Instant.now().minus(40, ChronoUnit.DAYS)));
        old.setId(102L);
        CertificateInventory foreign = inv("f.example.com", 2L); foreign.setCreatedAt(ISO.format(Instant.now().minus(1, ChronoUnit.DAYS)));
        foreign.setId(103L);
        when(inventoryRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(fresh, old, foreign));
        // Silme KALICI: satır yok — sayaç ürün geçmişinden. 201 (takım 1) iki kez silinmiş (eski çöp kutusu) → BİR sayılır;
        // 202 başka takımın → sayılmaz; 102 silinip geri yüklenmiş (hâlâ canlı) → sayılmaz.
        com.sitemonitor.repository.MonitorChangeLogRepository changeLog =
                org.mockito.Mockito.mock(com.sitemonitor.repository.MonitorChangeLogRepository.class);
        when(changeLog.findInventoryDeletesSince(anyString())).thenReturn(List.of(
                new Object[]{201L, 1L}, new Object[]{201L, 1L}, new Object[]{202L, 2L}, new Object[]{102L, 1L}));
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "changeLogRepo", changeLog);
        when(certificateCheckRepo.domainsWithFingerprintChangeSince(anyString())).thenReturn(List.of("ok1.example.com", "f.example.com"));
        Map<String, Object> c = svc.recentChanges(7, t -> t != null && t == 1L);
        assertThat(c).containsEntry("days", 7).containsEntry("added", 1).containsEntry("removed", 1).containsEntry("renewed", 1)
                .containsEntry("alerts_opened", 1).containsEntry("alerts_resolved", 0);   // setUp: ev(1) 1 gün önce açık; 45/50 gün öncekiler dışarıda
    }

    @Test
    @DisplayName("ALERTS-3 / DATA-5 (2026-10-22): yalnız UG takımı (3) görülebilen kayıt — sertifika ve SY damgalı açık alarm istatistik özetine girer")
    void ugTeamCounted() {
        when(inventoryRepo.findActiveDomainTeams()).thenReturn(List.of(row("ok1.example.com", 1L, null), row("bexp.example.com", 2L, 3L)));
        Map<String, Object> out = svc.build(t -> t != null && t == 3L);
        assertThat(m(out, "certs")).containsEntry("total", 1).containsEntry("expired", 1);
        assertThat(m(out, "alerts")).containsEntry("open", 1);
        assertThat(m(svc.build(t -> t != null && t == 4L), "certs")).containsEntry("total", 0);
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Map<String, Object> b, String k) { return (List<Map<String, Object>>) b.get(k); }
    private static Map<String, Object> team(Map<String, Object> b, long id) {
        return list(b, "teams").stream().filter(t -> Long.valueOf(id).equals(t.get("team_id"))).findFirst().orElse(null);
    }

    @Test
    @DisplayName("2026-11-13: takım satırı SY ∪ UG (sertifika iki takımda da), kurum toplamı bir kez; değerlendirilen alanı")
    void teamRowsCountSyAndUg() {
        when(inventoryRepo.findActiveDomainTeams()).thenReturn(List.of(
                row("ok1.example.com", 1L, 2L), row("soon.example.com", 1L, null), row("bok.example.com", 2L, 2L), row("never.example.com", 2L, null)));
        Map<String, Object> b = svc.build(t -> true);
        assertThat(m(b, "certs")).containsEntry("total", 4).containsEntry("evaluated", 3).containsEntry("unchecked", 1);
        assertThat(team(b, 1L)).containsEntry("total", 2).containsEntry("ok", 1).containsEntry("under30", 1);
        // Takım B: ok1 (UG) + bok (SY = UG, bir kez) + never
        assertThat(team(b, 2L)).containsEntry("total", 3).containsEntry("ok", 2).containsEntry("unchecked", 1).containsEntry("evaluated", 2);
        assertThat((Double) team(b, 2L).get("health_pct")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("2026-11-13: sertifikası olmayan takımın açık alarmı da takım satırına girer")
    void alertOnlyTeamGetsRow() {
        Team c = new Team(); c.setId(3L); c.setName("Takım C");
        Team a = new Team(); a.setId(1L); a.setName("Takım A"); Team bt = new Team(); bt.setId(2L); bt.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, bt, c));
        when(alertEventRepo.findOpenStatRows()).thenReturn(List.of(open(3L, "api.example.com", "HIGH"), open(1L, "soon.example.com", "CRITICAL")));
        Map<String, Object> b = svc.build(t -> true);
        assertThat(team(b, 3L)).containsEntry("team_name", "Takım C").containsEntry("total", 0).containsEntry("open_alerts", 1);
        assertThat(team(b, 3L).get("health_pct")).isNull();
        assertThat(m(b, "alerts")).containsEntry("open", 2).containsEntry("visible", true);
    }

    @Test
    @DisplayName("2026-11-13: alerts.read yok → alarm bloğu okunmaz (visible=false), takım satırlarında alarm null (asla 0)")
    void alertsHiddenWithoutPermission() {
        Map<String, Object> b = svc.build(t -> true, false, null, false);
        assertThat(m(b, "alerts")).containsOnlyKeys("visible").containsEntry("visible", false);
        assertThat(list(b, "teams")).allSatisfy(t -> assertThat(t.get("open_alerts")).isNull());
        org.mockito.Mockito.verify(alertEventRepo, org.mockito.Mockito.never()).findOpenStatRows();
        org.mockito.Mockito.verify(alertEventRepo, org.mockito.Mockito.never()).findCreatedStatRowsSince(anyString());
    }

    @Test
    @DisplayName("2026-11-13: alarm okuması düşerse error_msg + takım alarmları null (bilinmiyor ≠ 0)")
    void alertReadFailureIsUnknown() {
        when(alertEventRepo.findOpenStatRows()).thenThrow(new RuntimeException("db down"));
        Map<String, Object> b = svc.build(t -> true);
        assertThat(m(b, "alerts")).containsKey("error_msg").doesNotContainKey("open");
        assertThat(list(b, "teams")).allSatisfy(t -> assertThat(t.get("open_alerts")).isNull());
    }

    @Test
    @DisplayName("2026-11-13: SLA ortak önbellekten (availabilityAll + subset) — en kötü listesinde ad, tür, takım; takım kırılımı; kapsam")
    void slaWorstNamedAndByTeam() {
        Map<String, Object> b = svc.build(t -> true);
        List<Map<String, Object>> worst = (List<Map<String, Object>>) m(b, "sla").get("worst");
        assertThat(worst).hasSize(1);
        assertThat(worst.get(0)).containsEntry("type", "http").containsEntry("id", 10L).containsEntry("name", "Ödeme API").containsEntry("team_id", 1L);
        List<Map<String, Object>> byTeam = (List<Map<String, Object>>) m(b, "sla").get("by_team");
        assertThat(byTeam).extracting(x -> x.get("team_id"), x -> x.get("monitors"), x -> x.get("breaches"))
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(1L, 1, 1), org.assertj.core.groups.Tuple.tuple(2L, 1, 0));
        // Kapsam: takım 2 görmez → monitör 10 yok
        Map<String, Object> s2 = m(svc.build(t -> t != null && t == 2L), "sla");
        assertThat(s2).containsEntry("monitors", 1).containsEntry("breaches", 0);
        org.mockito.Mockito.verify(sparklineService, org.mockito.Mockito.never()).availability(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("2026-11-13 eşitlik: son kontroller Pano önbelleğinden (getAllLatest) okununca sonuç latest_checks okumasıyla BİREBİR aynı")
    void latestFromCertCacheEqualsRepository() {
        Map<String, Object> fromRepo = svc.build(t -> true);
        CertificateService cs = org.mockito.Mockito.mock(CertificateService.class);
        List<com.sitemonitor.dto.CertificateDto> dtos = latestCheckRepo.findAll().stream()
                .map(c -> com.sitemonitor.dto.CertificateDto.from(c, List.of(), List.of(), List.of())).toList();
        when(cs.getAllLatest()).thenReturn(dtos);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "certService", cs);
        Map<String, Object> fromCache = svc.build(t -> true);
        fromRepo.remove("generated_at"); fromCache.remove("generated_at");
        assertThat(fromCache).isEqualTo(fromRepo);
    }

    @Test
    @DisplayName("2026-11-13: bellek — aynı kapsam anahtarı ikinci çağrıda hesaplamaz; fresh 5 sn içinde yine bellekten")
    void memoisedPerScope() {
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "execCacheMs", 60_000L);
        svc.build(t -> true, true, "ALL", false);
        svc.build(t -> true, true, "ALL", false);
        svc.build(t -> true, true, "ALL", true);
        org.mockito.Mockito.verify(inventoryRepo, org.mockito.Mockito.times(1)).findActiveDomainTeams();
        svc.build(t -> true, false, "ALL", false);   // alarm izni ayrı anahtar
        org.mockito.Mockito.verify(inventoryRepo, org.mockito.Mockito.times(2)).findActiveDomainTeams();
    }
}
