package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.AlertTeamStatsService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.MonitoringOverviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nöbet rutini görev sayaçları (2026-11-26): tanımlar izne göre gizli; izinsiz anahtar hesaplanmaz; okuma hatası
 * {@code error:"unavailable"} + değer null (asla 0 / yeşil) ve belleğe girmez; izleme genel bakışı eksikse {@code partial};
 * yalnız istenen anahtarlar; takım süzgeci; bağlantılar takımı taşır.
 */
class OnCallRoutineSignalsTest {

    static final Long TEAM = 7L;
    static final LocalDate TODAY = LocalDate.parse("2026-11-26");
    static final BiPredicate<String, String> ALL = (r, a) -> true;

    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OnCallRoutineSignals sig;
    final AtomicBoolean incidentsFail = new AtomicBoolean(false);
    final AtomicInteger incidentQueries = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        sig = new OnCallRoutineSignals(jdbc);
        doAnswer(i -> {
            incidentQueries.incrementAndGet();
            if (incidentsFail.get()) throw new org.springframework.dao.DataAccessResourceFailureException("db");
            String sql = i.getArgument(0);
            RowCallbackHandler h = i.getArgument(1);
            List<long[]> rows = new ArrayList<>();
            if (sql.contains("FROM incident_records WHERE")) rows.add(new long[] {7, 2});
            else if (sql.contains("incident_postmortems")) rows.add(new long[] {8, 5});
            else if (sql.contains("incident_action_items")) {
                assertThat(sql).contains("a.due_date < '2026-11-26'");
                rows.add(new long[] {7, 1});
            }
            for (long[] r : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject(1)).thenReturn(r[0]);
                when(rs.getInt(2)).thenReturn((int) r[1]);
                h.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> signal(Map<String, Object> out, String key) {
        return (Map<String, Object>) ((Map<String, Object>) out.get("signals")).get(key);
    }

    @Test
    @DisplayName("tanımlar: anahtar sırası kalıcı; izinsiz anahtar 'hidden'")
    void defs() {
        assertThat(OnCallRoutineSignals.KEYS).containsExactly("open_alerts", "down_monitors", "stale_monitors",
                "open_incidents", "open_postmortems", "overdue_actions", "expiring_certs");
        List<Map<String, Object>> d = OnCallRoutineSignals.defs((r, a) -> "alerts.read".equals(r) && "view".equals(a));
        assertThat(d).hasSize(7);
        assertThat(d.get(0)).containsEntry("key", "open_alerts").containsEntry("hidden", false);
        assertThat(d.subList(1, 7)).allSatisfy(m -> assertThat(m).containsEntry("hidden", true));
        assertThat(d.get(6)).containsEntry("perm", "inventory.list");
    }

    @Test
    @DisplayName("izinsiz anahtar gizli ve hesaplanmaz; istenmeyen anahtar yanıtta yok")
    void hiddenNotComputed() {
        Map<String, Object> out = sig.compute(TEAM, Set.of("open_incidents", "down_monitors"), (r, a) -> false, false,
                false, TODAY);
        assertThat(signal(out, "open_incidents")).containsEntry("hidden", true).containsEntry("value", null)
                .containsEntry("tone", "neutral");
        assertThat(signal(out, "down_monitors")).containsEntry("hidden", true);
        assertThat(((Map<?, ?>) out.get("signals")).keySet().stream().map(String::valueOf).toList())
                .containsExactly("down_monitors", "open_incidents");
        assertThat(incidentQueries.get()).isZero();
    }

    @Test
    @DisplayName("olay ailesi: tek okuma üç sayacı besler, takım süzgeci; okuma hatası unavailable + null ve belleğe girmez")
    void incidentsFamily() {
        incidentsFail.set(true);
        Set<String> want = Set.of("open_incidents", "open_postmortems", "overdue_actions");
        Map<String, Object> bad = sig.compute(TEAM, want, ALL, false, false, TODAY);
        for (String k : want) {
            assertThat(signal(bad, k)).as(k).containsEntry("value", null).containsEntry("error", "unavailable")
                    .containsEntry("tone", "neutral").containsEntry("hidden", false);
        }
        incidentsFail.set(false);
        Map<String, Object> ok = sig.compute(TEAM, want, ALL, false, false, TODAY);
        assertThat(signal(ok, "open_incidents")).containsEntry("value", 2).containsEntry("tone", "warn")
                .containsEntry("error", null);
        assertThat(signal(ok, "open_postmortems")).as("başka takımın sayısı karışmaz").containsEntry("value", 0)
                .containsEntry("tone", "ok");
        assertThat(signal(ok, "overdue_actions")).containsEntry("value", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> link = (Map<String, Object>) signal(ok, "open_incidents").get("link");
        assertThat(link).containsEntry("tab", "incident-history");
        assertThat(((Map<?, ?>) link.get("params")).get("ih_team")).isEqualTo("7");
        int before = incidentQueries.get();
        sig.compute(TEAM, want, ALL, false, false, TODAY);
        assertThat(incidentQueries.get()).as("başarılı sonuç bellekten").isEqualTo(before);
    }

    @Test
    @DisplayName("izleme sayaçları: yalnız takımın etkin + silinmemiş izlemeleri; genel bakış eksikse partial; okunamazsa unavailable")
    void monitors() {
        MonitoringOverviewService overview = mock(MonitoringOverviewService.class);
        sig.overview = overview;
        when(overview.orgOverview(anyLong())).thenReturn(Map.of(
                "monitors", List.of(
                        Map.of("team_id", 7, "active", true, "deleted", false, "status", "down"),
                        Map.of("team_id", 7, "active", true, "deleted", false, "status", "down"),
                        Map.of("team_id", 7, "active", false, "deleted", false, "status", "down"),
                        Map.of("team_id", 7, "active", true, "deleted", true, "status", "down"),
                        Map.of("team_id", 7, "active", true, "deleted", false, "status", "stale"),
                        Map.of("team_id", 8, "active", true, "deleted", false, "status", "down")),
                "types", List.of(Map.of("type", "http", "unavailable", true))));
        Map<String, Object> out = sig.compute(TEAM, Set.of("down_monitors", "stale_monitors"), ALL, false, false, TODAY);
        assertThat(signal(out, "down_monitors")).containsEntry("value", 2).containsEntry("tone", "bad")
                .containsEntry("partial", true);
        assertThat(signal(out, "stale_monitors")).containsEntry("value", 1).containsEntry("tone", "warn");

        when(overview.orgOverview(anyLong())).thenReturn(null);
        Map<String, Object> none = sig.compute(TEAM, Set.of("down_monitors"), ALL, false, false, TODAY);
        assertThat(signal(none, "down_monitors")).containsEntry("value", null).containsEntry("error", "unavailable");
        sig.overview = null;
        assertThat(signal(sig.compute(TEAM, Set.of("stale_monitors"), ALL, false, false, TODAY), "stale_monitors"))
                .containsEntry("error", "unavailable");
    }

    @Test
    @DisplayName("açık alarm ve süresi yaklaşan sertifika: takımın satırı; SY ya da UG sahipliği; servis yoksa unavailable")
    void alertsAndCerts() {
        AlertTeamStatsService stats = mock(AlertTeamStatsService.class);
        sig.alertStats = stats;
        when(stats.build(any(), anyBoolean(), anyBoolean())).thenReturn(Map.of("teams", List.of(
                Map.of("team_id", 8, "open", 9), Map.of("team_id", 7, "open", 3))));
        assertThat(signal(sig.compute(TEAM, Set.of("open_alerts"), ALL, false, true, TODAY), "open_alerts"))
                .containsEntry("value", 3);

        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        CertificateService certs = mock(CertificateService.class);
        when(inv.findActiveDomainTeams()).thenReturn(List.of(
                new Object[] {"a.example.com", 7L, null},
                new Object[] {"b.example.com", 9L, 7L},
                new Object[] {"c.example.com", 8L, null}));
        when(certs.getAllLatest()).thenReturn(List.of(cert("A.example.com", 12), cert("b.example.com", -3),
                cert("c.example.com", 1), cert("a.example.com", null)));
        Map<String, Object> noSvc = sig.compute(TEAM, Set.of("expiring_certs"), ALL, false, false, TODAY);
        assertThat(signal(noSvc, "expiring_certs")).containsEntry("error", "unavailable").containsEntry("value", null);
        sig.inventory = inv;
        sig.certificates = certs;
        assertThat(signal(sig.compute(TEAM, Set.of("expiring_certs"), ALL, false, false, TODAY), "expiring_certs"))
                .containsEntry("value", 2);
    }

    @Test
    @DisplayName("kısmi okumada 0 yeşil değildir (nötr); kısmi okumada > 0 sorun kanıtlıdır; tam okumada 0 yeşil")
    void partialZeroIsNotGreen() {
        assertThat(OnCallRoutineSignals.signal("stale_monitors", TEAM, 0, true, null)).containsEntry("tone", "neutral")
                .containsEntry("partial", true);
        assertThat(OnCallRoutineSignals.signal("down_monitors", TEAM, 0, true, null)).containsEntry("tone", "neutral");
        assertThat(OnCallRoutineSignals.signal("down_monitors", TEAM, 2, true, null)).containsEntry("tone", "bad");
        assertThat(OnCallRoutineSignals.signal("stale_monitors", TEAM, 1, true, null)).containsEntry("tone", "warn");
        assertThat(OnCallRoutineSignals.signal("stale_monitors", TEAM, 0, false, null)).containsEntry("tone", "ok");

        MonitoringOverviewService overview = mock(MonitoringOverviewService.class);
        sig.overview = overview;
        when(overview.orgOverview(anyLong())).thenReturn(Map.of(
                "monitors", List.of(Map.of("team_id", 8, "active", true, "deleted", false, "status", "down")),
                "types", List.of(Map.of("type", "http", "checks_unavailable", true))));
        Map<String, Object> out = sig.compute(TEAM, Set.of("down_monitors"), ALL, false, false, TODAY);
        assertThat(signal(out, "down_monitors")).containsEntry("value", 0).containsEntry("partial", true)
                .containsEntry("tone", "neutral");
    }

    @Test
    @DisplayName("süresi yaklaşan sertifika: kurum geneli harita bellekte — yoklama başına envanter ve son kontroller yeniden okunmaz; fresh 5 sn")
    void expiringCertsMemoised() {
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        CertificateService certs = mock(CertificateService.class);
        AtomicInteger invReads = new AtomicInteger();
        AtomicInteger certReads = new AtomicInteger();
        when(inv.findActiveDomainTeams()).thenAnswer(i -> {
            invReads.incrementAndGet();
            return List.of(new Object[] {"a.example.com", 7L, null}, new Object[] {"b.example.com", 9L, 7L},
                    new Object[] {"d.example.com", 9L, null});
        });
        when(certs.getAllLatest()).thenAnswer(i -> {
            certReads.incrementAndGet();
            return List.of(cert("a.example.com", 5), cert("b.example.com", 20), cert("d.example.com", 3));
        });
        sig.inventory = inv;
        sig.certificates = certs;
        for (int i = 0; i < 5; i++) {
            assertThat(signal(sig.compute(TEAM, Set.of("expiring_certs"), ALL, false, false, TODAY), "expiring_certs"))
                    .containsEntry("value", 2);
        }
        // aynı bellek başka takımı da besler (takım süzgeci her çağrıda bellekte)
        assertThat(signal(sig.compute(9L, Set.of("expiring_certs"), ALL, false, false, TODAY), "expiring_certs"))
                .containsEntry("value", 2);
        assertThat(signal(sig.compute(42L, Set.of("expiring_certs"), ALL, false, false, TODAY), "expiring_certs"))
                .containsEntry("value", 0).containsEntry("tone", "ok");
        assertThat(invReads.get()).as("envanter yoklama başına okunmaz").isEqualTo(1);
        assertThat(certReads.get()).isEqualTo(1);

        // okuma hatası bellek saklamaz; sonraki yoklama yeniden dener
        OnCallRoutineSignals fresh = new OnCallRoutineSignals(jdbc);
        CertificateInventoryRepository broken = mock(CertificateInventoryRepository.class);
        when(broken.findActiveDomainTeams()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        fresh.inventory = broken;
        fresh.certificates = certs;
        assertThat(signal(fresh.compute(TEAM, Set.of("expiring_certs"), ALL, false, false, TODAY), "expiring_certs"))
                .containsEntry("error", "unavailable").containsEntry("value", null);
        fresh.inventory = inv;
        assertThat(signal(fresh.compute(TEAM, Set.of("expiring_certs"), ALL, false, false, TODAY), "expiring_certs"))
                .containsEntry("value", 2);
    }

    static CertificateDto cert(String domain, Integer days) {
        CertificateDto c = new CertificateDto();
        c.setDomain(domain);
        c.setDaysRemaining(days);
        return c;
    }
}
