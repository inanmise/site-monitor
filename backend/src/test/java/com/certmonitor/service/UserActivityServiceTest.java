package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.AuditLog;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.AuditLogRepository;
import com.certmonitor.repository.TeamRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UserActivityService birim testi (saf Mockito). Tek getOverview() çağrısının ürettiği toplulaştırmaları
 * (summary, active_users, series success/failed/blocked, top users/sources, anomali CSV, rol/takım, heatmap)
 * doğrular. Olaylar "şimdi" zaman damgalı → gün/24h penceresine düşer; bucket sınırı kararsızlığından
 * kaçınmak için seri toplamları üzerinden assert edilir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserActivityServiceTest {

    @Mock AuditLogRepository auditLogRepo;
    @Mock AppUserRepository  userRepo;
    @Mock TeamRepository     teamRepo;
    @Mock UserService        userService;

    private UserActivityService service;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new UserActivityService(auditLogRepo, userRepo, teamRepo, userService);
    }

    private AuditLog ev(String actor, String ip, String outcome, String role, Long teamId,
                        String flags, String country, String city, Instant when) {
        AuditLog a = new AuditLog();
        a.setEventType("SUCCESS".equals(outcome) ? "LOGIN" : "LOGIN_FAILED");
        a.setEventTime(ISO.format(when));
        a.setActor(actor);
        a.setActorRole(role);
        a.setActorTeamId(teamId);
        a.setIpAddress(ip);
        a.setIpCountry(country);
        a.setIpCity(city);
        a.setOutcome(outcome);
        a.setAnomalyFlags(flags);
        a.setUserAgent("Mozilla/5.0 Test");
        return a;
    }

    @SuppressWarnings("unchecked")
    private long sumSeries(Map<String, Object> overview, String gran, String field) {
        Map<String, Object> series = (Map<String, Object>) overview.get("series");
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) series.get(gran);
        return buckets.stream().mapToLong(b -> ((Number) b.get(field)).longValue()).sum();
    }

    private Map<String, Object> overviewWithWindow(List<AuditLog> window) {
        when(auditLogRepo.findLoginEventsSince(any(), any())).thenReturn(window);
        when(userRepo.findAllWithActiveSession()).thenReturn(List.of());
        when(teamRepo.findAll()).thenReturn(List.of());
        return service.getOverview();
    }

    @Test
    @DisplayName("series: SUCCESS/FAILURE/BLOCKED doğru sınıflanır (gün toplamı)")
    void series_classifiesOutcomes() {
        Instant now = Instant.now();
        List<AuditLog> window = List.of(
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, "TR", "Istanbul", now),
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, "TR", "Istanbul", now),
                ev("bob",   "2.2.2.2", "FAILURE", "USER",  20L, "OFF_HOURS", null, null, now),
                ev("carol", "3.3.3.3", "BLOCKED", null,    null, "RATE_LIMITED", null, null, now));

        Map<String, Object> o = overviewWithWindow(window);

        assertThat(sumSeries(o, "day", "success")).isEqualTo(2);
        assertThat(sumSeries(o, "day", "failed")).isEqualTo(1);
        assertThat(sumSeries(o, "day", "blocked")).isEqualTo(1);
        assertThat(sumSeries(o, "day", "total")).isEqualTo(4);
        // gün serisi 7 kova, saat 24, dakika 60
        assertThat(((List<?>) ((Map<?, ?>) o.get("series")).get("day"))).hasSize(7);
        assertThat(((List<?>) ((Map<?, ?>) o.get("series")).get("hour"))).hasSize(24);
        assertThat(((List<?>) ((Map<?, ?>) o.get("series")).get("minute"))).hasSize(60);
    }

    @Test
    @DisplayName("summary: 24h login/failed/anomali/unique sayımları")
    @SuppressWarnings("unchecked")
    void summary_counts() {
        Instant now = Instant.now();
        List<AuditLog> window = List.of(
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, "TR", "Istanbul", now),
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, "TR", "Istanbul", now),
                ev("bob",   "2.2.2.2", "FAILURE", "USER",  20L, "OFF_HOURS", null, null, now),
                ev("carol", "3.3.3.3", "BLOCKED", null,    null, "RATE_LIMITED", null, null, now));

        Map<String, Object> sum = (Map<String, Object>) overviewWithWindow(window).get("summary");

        assertThat(((Number) sum.get("logins_24h")).longValue()).isEqualTo(2);
        assertThat(((Number) sum.get("failed_24h")).longValue()).isEqualTo(2);
        assertThat(((Number) sum.get("anomalies_24h")).longValue()).isEqualTo(2);
        assertThat(((Number) sum.get("unique_users_24h")).longValue()).isEqualTo(1); // sadece alice SUCCESS
        assertThat(((Number) sum.get("active_count")).longValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("details: KPI drill-down listeleri (24s login/başarısız/anomali/tekil)")
    @SuppressWarnings("unchecked")
    void kpiDetails_lists() {
        Instant now = Instant.now();
        List<AuditLog> window = List.of(
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, "TR", "Istanbul", now),
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, "TR", "Istanbul", now),
                ev("bob",   "2.2.2.2", "FAILURE", "USER",  20L, "OFF_HOURS", null, null, now),
                ev("carol", "3.3.3.3", "BLOCKED", null,    null, "RATE_LIMITED", null, null, now));

        Map<String, Object> det = (Map<String, Object>) overviewWithWindow(window).get("details");

        assertThat((List<?>) det.get("logins")).hasSize(2);
        assertThat((List<?>) det.get("failed")).hasSize(2);          // FAILURE + BLOCKED
        assertThat((List<?>) det.get("anomalies")).hasSize(2);       // OFF_HOURS + RATE_LIMITED
        assertThat((List<?>) det.get("unique_users")).hasSize(1);    // alice
    }

    @Test
    @DisplayName("top_users: başarılı login sayısına göre sıralı")
    @SuppressWarnings("unchecked")
    void topUsers_rankedByLogins() {
        Instant now = Instant.now();
        List<AuditLog> window = List.of(
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, null, null, now),
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, null, null, now),
                ev("bob",   "2.2.2.2", "SUCCESS", "USER",  20L, null, null, null, now),
                ev("bob",   "2.2.2.2", "FAILURE", "USER",  20L, null, null, null, now));

        List<Map<String, Object>> top = (List<Map<String, Object>>) overviewWithWindow(window).get("top_users");

        assertThat(top).isNotEmpty();
        assertThat(top.get(0).get("username")).isEqualTo("alice");
        assertThat(((Number) top.get(0).get("logins")).longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("top_sources: IP'ye göre toplam/başarılı/başarısız")
    @SuppressWarnings("unchecked")
    void topSources_byIp() {
        Instant now = Instant.now();
        List<AuditLog> window = List.of(
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, "TR", "Istanbul", now),
                ev("bob",   "1.1.1.1", "FAILURE", "USER",  20L, null, "TR", "Istanbul", now),
                ev("carol", "9.9.9.9", "SUCCESS", "USER",  20L, null, null, null, now));

        List<Map<String, Object>> sources = (List<Map<String, Object>>) overviewWithWindow(window).get("top_sources");

        Map<String, Object> top = sources.get(0);
        assertThat(top.get("ip")).isEqualTo("1.1.1.1");
        assertThat(((Number) top.get("total")).longValue()).isEqualTo(2);
        assertThat(((Number) top.get("success")).longValue()).isEqualTo(1);
        assertThat(((Number) top.get("failed")).longValue()).isEqualTo(1);
        assertThat(top.get("country")).isEqualTo("TR");
    }

    @Test
    @DisplayName("anomalies: CSV ayrıştırma → bayrak başına sayım + recent")
    @SuppressWarnings("unchecked")
    void anomalies_csvSplit() {
        Instant now = Instant.now();
        List<AuditLog> window = List.of(
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, "OFF_HOURS,UNUSUAL_IP", null, null, now),
                ev("bob",   "2.2.2.2", "FAILURE", "USER",  20L, "BRUTE_FORCE", null, null, now));

        Map<String, Object> an = (Map<String, Object>) overviewWithWindow(window).get("anomalies");
        Map<String, Object> counts = (Map<String, Object>) an.get("counts");

        assertThat(((Number) counts.get("OFF_HOURS")).longValue()).isEqualTo(1);
        assertThat(((Number) counts.get("UNUSUAL_IP")).longValue()).isEqualTo(1);
        assertThat(((Number) counts.get("BRUTE_FORCE")).longValue()).isEqualTo(1);
        assertThat(((Number) counts.get("GEO_VELOCITY")).longValue()).isEqualTo(0);
        assertThat(((Number) an.get("total")).longValue()).isEqualTo(3);
        assertThat((List<?>) an.get("recent")).hasSize(2);
    }

    @Test
    @DisplayName("role_team: yalnız başarılı login'ler rol/takıma göre")
    @SuppressWarnings("unchecked")
    void roleTeam_breakdown() {
        Instant now = Instant.now();
        List<AuditLog> window = List.of(
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, null, null, now),
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, null, null, now),
                ev("bob",   "2.2.2.2", "FAILURE", "USER",  20L, null, null, null, now));
        Team teamX = team(10L, "Team X");
        when(teamRepo.findAll()).thenReturn(List.of(teamX));

        Map<String, Object> rt = (Map<String, Object>) overviewWithWindow2(window).get("role_team");
        List<Map<String, Object>> roles = (List<Map<String, Object>>) rt.get("by_role");
        List<Map<String, Object>> teams = (List<Map<String, Object>>) rt.get("by_team");

        assertThat(roles.get(0).get("role")).isEqualTo("ADMIN");
        assertThat(((Number) roles.get(0).get("count")).longValue()).isEqualTo(2);
        assertThat(teams.get(0).get("team_id")).isEqualTo(10L);
        assertThat(teams.get(0).get("team_name")).isEqualTo("Team X");
        assertThat(((Number) teams.get(0).get("count")).longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("heatmap: 7×24 matris + max")
    @SuppressWarnings("unchecked")
    void heatmap_dimensions() {
        Instant now = Instant.now();
        List<AuditLog> window = List.of(
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, null, null, now));

        List<Map<String, Object>> hms = (List<Map<String, Object>>) overviewWithWindow(window).get("heatmaps");
        assertThat(hms).hasSize(4);                       // bu hafta + 3 önceki = 4 hafta
        Map<String, Object> hm = hms.get(0);              // bu hafta (now olayını içerir)
        List<List<Long>> matrix = (List<List<Long>>) hm.get("matrix");

        assertThat(matrix).hasSize(7);
        assertThat(matrix.get(0)).hasSize(24);
        assertThat(((Number) hm.get("max")).longValue()).isEqualTo(1);
        assertThat(hm.get("from")).isNotNull();
        assertThat(hm.get("to")).isNotNull();
        assertThat(hm.get("failed")).isNotNull();   // başarısız-login matrisi (kırmızı hücre)
        // Hücre detayları: tıklayınca o saatteki girişleri getirir (toplam 1 olay)
        Map<String, List<Object>> cells = (Map<String, List<Object>>) hm.get("cells");
        int total = cells.values().stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(1);
    }

    @Test
    @DisplayName("getLoginSeries: aralık+granülarite kovaları + outcome ayrımı")
    @SuppressWarnings("unchecked")
    void getLoginSeries_buckets() {
        Instant now = Instant.now();
        List<AuditLog> window = List.of(
                ev("alice", "1.1.1.1", "SUCCESS", "ADMIN", 10L, null, null, null, now),
                ev("bob",   "2.2.2.2", "FAILURE", "USER",  20L, null, null, null, now));
        when(auditLogRepo.findLoginEventsBetween(any(), any(), any())).thenReturn(window);

        Map<String, Object> res = service.getLoginSeries(
                ISO.format(now.minusSeconds(3600)), ISO.format(now.plusSeconds(60)), "hour");

        assertThat(res.get("granularity")).isEqualTo("hour");
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) res.get("buckets");
        long success = buckets.stream().mapToLong(b -> ((Number) b.get("success")).longValue()).sum();
        long failed  = buckets.stream().mapToLong(b -> ((Number) b.get("failed")).longValue()).sum();
        assertThat(success).isEqualTo(1);
        assertThat(failed).isEqualTo(1);
    }

    @Test
    @DisplayName("getLoginSeries: geçersiz/eksik aralık → boş kova")
    void getLoginSeries_invalid_empty() {
        Map<String, Object> res = service.getLoginSeries(null, null, "day");
        assertThat((List<?>) res.get("buckets")).isEmpty();
    }

    @Test
    @DisplayName("active_users: aktif kullanıcı oturum audit'inden detay + süre")
    @SuppressWarnings("unchecked")
    void activeUsers_detailFromAudit() {
        AppUser alice = new AppUser();
        alice.setUsername("alice");
        alice.setDisplayName("Alice A");
        alice.setSystemRole("ADMIN");
        alice.setTeamId(10L);
        alice.setActiveSessionId("SESS-A");

        Instant loginTime = Instant.now().minusSeconds(600); // 10 dk önce
        AuditLog sessionEv = ev("alice", "127.0.0.1", "SUCCESS", "ADMIN", 10L, null, "TR", "Istanbul", loginTime);

        Team teamX = team(10L, "Team X");
        when(auditLogRepo.findLoginEventsSince(any(), any())).thenReturn(List.of());
        when(userRepo.findAllWithActiveSession()).thenReturn(List.of(alice));
        when(userService.hasLiveSession(any())).thenReturn(true);   // canlı oturum filtresi
        when(teamRepo.findAll()).thenReturn(List.of(teamX));
        when(auditLogRepo.findTopByActorAndSessionIdOrderByEventTimeDesc("alice", "SESS-A"))
                .thenReturn(Optional.of(sessionEv));

        Map<String, Object> o = service.getOverview();
        List<Map<String, Object>> active = (List<Map<String, Object>>) o.get("active_users");

        assertThat(active).hasSize(1);
        Map<String, Object> row = active.get(0);
        assertThat(row.get("username")).isEqualTo("alice");
        assertThat(row.get("team_name")).isEqualTo("Team X");
        assertThat(row.get("ip")).isEqualTo("127.0.0.1");
        assertThat(row.get("country")).isEqualTo("TR");
        assertThat(((Number) row.get("duration_min")).longValue()).isGreaterThanOrEqualTo(9);
        assertThat(((Number) ((Map<String, Object>) o.get("summary")).get("active_count")).longValue()).isEqualTo(1);
    }

    // role_team testinde teamRepo zaten stub'lı; ortak yardımcıyı tekrar etmemek için ayrı sarmalayıcı
    private Map<String, Object> overviewWithWindow2(List<AuditLog> window) {
        when(auditLogRepo.findLoginEventsSince(any(), any())).thenReturn(window);
        when(userRepo.findAllWithActiveSession()).thenReturn(List.of());
        return service.getOverview();
    }

    private Team team(Long id, String name) {
        Team t = mock(Team.class);
        when(t.getId()).thenReturn(id);
        when(t.getName()).thenReturn(name);
        return t;
    }
}
