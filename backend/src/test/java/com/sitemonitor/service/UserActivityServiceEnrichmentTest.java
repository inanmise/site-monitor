package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * System Health kullanıcı etkinliği zenginleştirmesi (2026-09-13): sayfa kullanımı özeti (#1), boşta/oturum düşme (#2),
 * anomali onayı + kullanıcı zaman çizelgesi (#3), atıl hesaplar (#5), takım hiç-girmeyenler (#7), kaynak ilk/son görülme (#8).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserActivityServiceEnrichmentTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock AuditLogRepository auditLogRepo;
    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock UserService userService;
    @Mock PageUsageService pageUsage;
    @Mock AppSettingsService appSettings;
    @Mock JdbcTemplate jdbc;

    private UserActivityService service;

    private static AppUser user(long id, String name, Long team, boolean active, String lastLogin) {
        AppUser u = new AppUser(); u.setId(id); u.setUsername(name); u.setTeamId(team); u.setActive(active); u.setLastLoginAt(lastLogin);
        return u;
    }
    private static AuditLog ev(long id, String actor, String ip, String outcome, Long teamId, String flags, Instant when) {
        AuditLog a = new AuditLog(); a.setId(id); a.setEventType("SUCCESS".equals(outcome) ? "LOGIN" : "LOGIN_FAILED");
        a.setEventTime(ISO.format(when)); a.setActor(actor); a.setActorTeamId(teamId); a.setIpAddress(ip); a.setOutcome(outcome);
        a.setAnomalyFlags(flags); a.setIpOrg("Example ISP"); return a;
    }
    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); return t; }

    @BeforeEach
    void setUp() {
        service = new UserActivityService(auditLogRepo, userRepo, teamRepo, userService, pageUsage, jdbc, appSettings);
        when(appSettings.getInt(eq("site.monitor.ui.inactivity-minutes"), anyInt())).thenReturn(30);
        when(teamRepo.findAll()).thenReturn(List.of(team(5L, "Takım A"), team(9L, "Takım B")));
        when(userRepo.findAllWithActiveSession()).thenReturn(List.of());
        when(pageUsage.rowsSince(anyInt())).thenReturn(List.of());
        when(auditLogRepo.findLoginEventsSince(any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("#5 atıl hesaplar: 30/90 gün eşikleri, hiç girmemiş, pasif hesap sayılmaz; details.dormant en eski önce")
    void dormantAccounts() {
        Instant now = Instant.now();
        when(userRepo.findAll()).thenReturn(List.of(
                user(1, "fresh", 5L, true, ISO.format(now.minus(1, ChronoUnit.DAYS))),
                user(2, "old40", 5L, true, ISO.format(now.minus(40, ChronoUnit.DAYS))),
                user(3, "old100", 9L, true, ISO.format(now.minus(100, ChronoUnit.DAYS))),
                user(4, "never", 9L, true, null),
                user(5, "inactive", 9L, false, null)));
        Map<String, Object> o = service.getOverview();
        Map<?, ?> sum = (Map<?, ?>) o.get("summary");
        assertThat(sum.get("total_users")).isEqualTo(4L);
        assertThat(sum.get("dormant_30d")).isEqualTo(3L);
        assertThat(sum.get("dormant_90d")).isEqualTo(2L);
        assertThat(sum.get("never_logged_in")).isEqualTo(1L);
        assertThat(((Map<?, ?>) o.get("office_hours")).get("start")).isEqualTo(8);
        assertThat(o.get("window_days")).isEqualTo(7);
        @SuppressWarnings("unchecked") List<Map<String, Object>> dormant = (List<Map<String, Object>>) ((Map<?, ?>) o.get("details")).get("dormant");
        assertThat(dormant).extracting(m -> m.get("username")).containsExactly("never", "old100", "old40");
        assertThat(dormant.get(1).get("team_name")).isEqualTo("Takım B");
    }

    @Test
    @DisplayName("#2 aktif oturum: idle_sec = şimdi − last_seen; expires_in = hareketsizlik ayarı − idle; son sekme PageUsage'dan")
    void activeSessionIdleAndExpiry() {
        AppUser u = user(1, "admin", 5L, true, ISO.format(Instant.now()));
        u.setActiveSessionId("sid-1"); u.setLastSeenAt(ISO.format(Instant.now().minusSeconds(120)));
        when(userRepo.findAllWithActiveSession()).thenReturn(List.of(u));
        when(userRepo.findAll()).thenReturn(List.of(u));
        when(userService.hasLiveSession(u)).thenReturn(true);
        // Oturumun LOGIN satırı 50 dk önce; aynı oturumdaki daha yeni denetim satırı (anomali onayı) süreyi SIFIRLAMAMALI
        AuditLog login = ev(1, "admin", "10.0.0.1", "SUCCESS", 5L, null, Instant.now().minusSeconds(3000)); login.setSessionId("sid-1");
        AuditLog later = ev(2, "admin", "10.0.0.1", "SUCCESS", 5L, null, Instant.now().minusSeconds(10)); later.setEventType("LOGIN_ANOMALY_ACK"); later.setSessionId("sid-1");
        when(auditLogRepo.findTopByActorAndSessionIdAndEventTypeOrderByEventTimeDesc("admin", "sid-1", "LOGIN")).thenReturn(Optional.of(login));
        when(auditLogRepo.findTopByActorAndSessionIdOrderByEventTimeDesc(anyString(), anyString())).thenReturn(Optional.of(later));
        when(auditLogRepo.findTopByActorAndEventTypeAndOutcomeOrderByEventTimeDesc(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(pageUsage.lastTabOf("admin")).thenReturn(new String[]{"forecast", "2026-09-13T00:00:00"});
        Map<String, Object> o = service.getOverview();
        @SuppressWarnings("unchecked") Map<String, Object> row = ((List<Map<String, Object>>) o.get("active_users")).get(0);
        assertThat((Long) row.get("duration_min")).isBetween(49L, 51L);
        long idle = (Long) row.get("idle_sec");
        assertThat(idle).isBetween(118L, 125L);
        assertThat((Long) row.get("expires_in_sec")).isBetween(30 * 60 - 125L, 30 * 60 - 118L);
        assertThat(row.get("last_tab")).isEqualTo("forecast");
    }

    @Test
    @DisplayName("#1 sayfa kullanımı: sekme/kullanıcı/takım/trend özetleri; dakika = ping × 15 / 60; pay yüzdesi")
    void usageSummary() {
        when(userRepo.findAll()).thenReturn(List.of(user(1, "admin", 5L, true, null), user(2, "bob", 9L, true, null)));
        when(pageUsage.rowsSince(7)).thenReturn(List.of(
                row("2026-09-12", "admin", "forecast", 240), row("2026-09-12", "bob", "forecast", 120),
                row("2026-09-13", "admin", "domains", 40), row("2026-09-13", "bob", "health", 0)));
        Map<String, Object> o = service.getOverview();
        Map<?, ?> usage = (Map<?, ?>) o.get("usage");
        @SuppressWarnings("unchecked") List<Map<String, Object>> pages = (List<Map<String, Object>>) usage.get("pages");
        assertThat(pages.get(0).get("tab")).isEqualTo("forecast");
        assertThat(pages.get(0).get("minutes")).isEqualTo(90L);      // 360 ping × 15 / 60
        assertThat(pages.get(0).get("users")).isEqualTo(2);
        assertThat(pages.get(0).get("share")).isEqualTo(90.0);      // 360 / 400
        assertThat(usage.get("total_minutes")).isEqualTo(100L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> users = (List<Map<String, Object>>) usage.get("users");
        assertThat(users.get(0).get("username")).isEqualTo("admin");
        assertThat(users.get(0).get("top_tab")).isEqualTo("forecast");
        assertThat(users.get(0).get("team_name")).isEqualTo("Takım A");
        @SuppressWarnings("unchecked") List<Map<String, Object>> teams = (List<Map<String, Object>>) usage.get("teams");
        assertThat(teams).extracting(m -> m.get("team_name")).containsExactlyInAnyOrder("Takım A", "Takım B");
        @SuppressWarnings("unchecked") List<Map<String, Object>> trend = (List<Map<String, Object>>) usage.get("trend");
        assertThat(trend).extracting(m -> m.get("day")).containsExactly("2026-09-12", "2026-09-13");
    }

    @Test
    @DisplayName("#7/#8: takım satırı üye sayısı + pencerede hiç girmeyenler; kaynak IP ilk/son görülme, arkasındaki kullanıcılar, org")
    void teamNeverLoggedAndSourceSeen() {
        Instant now = Instant.now();
        when(userRepo.findAll()).thenReturn(List.of(user(1, "admin", 5L, true, null), user(2, "carol", 5L, true, null), user(3, "bob", 9L, true, null)));
        when(auditLogRepo.findLoginEventsSince(any(), any())).thenReturn(List.of(
                ev(1, "admin", "10.0.0.1", "SUCCESS", 5L, null, now.minus(3, ChronoUnit.DAYS)),
                ev(2, "bob", "10.0.0.1", "SUCCESS", 9L, null, now.minus(1, ChronoUnit.HOURS)),
                ev(3, "bob", "10.0.0.2", "FAILURE", 9L, "OFF_HOURS", now)));
        Map<String, Object> o = service.getOverview();
        @SuppressWarnings("unchecked") List<Map<String, Object>> teams = (List<Map<String, Object>>) ((Map<?, ?>) o.get("role_team")).get("by_team");
        Map<String, Object> teamA = teams.stream().filter(t -> Long.valueOf(5L).equals(t.get("team_id"))).findFirst().orElseThrow();
        assertThat(teamA.get("member_count")).isEqualTo(2);
        @SuppressWarnings("unchecked") List<Map<String, Object>> never = (List<Map<String, Object>>) teamA.get("never_logged");
        assertThat(never).extracting(m -> m.get("username")).containsExactly("carol");
        @SuppressWarnings("unchecked") List<Map<String, Object>> src = (List<Map<String, Object>>) o.get("top_sources");
        Map<String, Object> ip1 = src.stream().filter(s -> "10.0.0.1".equals(s.get("ip"))).findFirst().orElseThrow();
        assertThat(ip1.get("user_count")).isEqualTo(2);
        assertThat(ip1.get("org")).isEqualTo("Example ISP");
        assertThat(String.valueOf(ip1.get("first_seen"))).isLessThan(String.valueOf(ip1.get("last_seen")));
        assertThat(ip1.get("new_this_week")).isEqualTo(true);
    }

    @Test
    @DisplayName("#3 anomali onayı: onay UPDATE→INSERT; kaldırma DELETE; recent satırlarında ack + unacked sayacı")
    void anomalyAck() {
        when(jdbc.update(startsWith("UPDATE login_anomaly_ack"), any(Object[].class))).thenReturn(0);
        Map<String, Object> stamp = service.acknowledgeAnomaly(42L, "admin", " inceledim ", true);
        assertThat(stamp.get("by")).isEqualTo("admin");
        assertThat(stamp.get("note")).isEqualTo("inceledim");
        verify(jdbc).update(startsWith("INSERT INTO login_anomaly_ack"), eq(42L), eq("admin"), anyString(), eq("inceledim"));
        assertThat(service.acknowledgeAnomaly(42L, "admin", null, false)).isNull();
        verify(jdbc).update(startsWith("DELETE FROM login_anomaly_ack"), eq(42L));

        Instant now = Instant.now();
        when(userRepo.findAll()).thenReturn(List.of());
        when(auditLogRepo.findLoginEventsSince(any(), any())).thenReturn(List.of(
                ev(7, "bob", "10.0.0.2", "FAILURE", 9L, "OFF_HOURS", now), ev(8, "bob", "10.0.0.2", "FAILURE", 9L, "BRUTE_FORCE", now)));
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(1); ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("audit_id")).thenReturn(7L); when(rs.getString("acked_by")).thenReturn("admin");
            when(rs.getString("acked_at")).thenReturn("2026-09-13T00:00:00"); when(rs.getString("note")).thenReturn(null);
            h.processRow(rs); return null;
        }).when(jdbc).query(startsWith("SELECT audit_id"), any(RowCallbackHandler.class), any(Object[].class));
        Map<?, ?> an = (Map<?, ?>) service.getOverview().get("anomalies");
        assertThat(an.get("unacked_recent")).isEqualTo(1L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> recent = (List<Map<String, Object>>) an.get("recent");
        assertThat(recent.stream().filter(r -> r.get("ack") != null).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("#3 kullanıcı zaman çizelgesi: yalnız o kullanıcının olayları (büyük/küçük harf duyarsız), sayaçlar, anomali alt listesi")
    void userTimeline() {
        Instant now = Instant.now();
        when(auditLogRepo.findLoginEventsSince(any(), any())).thenReturn(List.of(
                ev(1, "Bob", "10.0.0.1", "SUCCESS", 9L, null, now.minus(2, ChronoUnit.DAYS)),
                ev(2, "bob", "10.0.0.2", "FAILURE", 9L, "UNUSUAL_IP", now.minus(1, ChronoUnit.DAYS)),
                ev(3, "alice", "10.0.0.3", "SUCCESS", 5L, null, now)));
        Map<String, Object> t = service.userTimeline("BOB", 20);
        assertThat(t.get("logins")).isEqualTo(1L);
        assertThat(t.get("failed")).isEqualTo(1L);
        assertThat(t.get("distinct_ips")).isEqualTo(2L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> events = (List<Map<String, Object>>) t.get("events");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("id")).isEqualTo(2L);   // yeni → eski
        @SuppressWarnings("unchecked") List<Map<String, Object>> anoms = (List<Map<String, Object>>) t.get("anomalies");
        assertThat(anoms).hasSize(1);
    }

    @Test
    @DisplayName("Kullanıcı dizini (2026-09-20): login_status satırı e-posta / org rolü / takım id+ek takımlar / sicil / oluşturulma / son görülme / tur / kilit taşır")
    void loginStatusCarriesDirectoryFields() {
        AppUser u = user(3, "carol", 5L, true, ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        u.setEmail("carol@example.com"); u.setOrgRole("TECH"); u.setEmployeeId("E-3"); u.setCreatedAt("2026-01-05T10:00:00");
        u.setLastSeenAt("2026-09-19T08:00:00"); u.setTeamIds(new LinkedHashSet<>(List.of(5L, 9L))); u.setPermanentLock(true);
        u.setTitle("Uzman"); u.setDepartment("BT"); u.setAuthSource("LDAP");
        u.setTourState("{\"status\":\"completed\",\"updated_at\":\"2026-02-01T09:00:00\"}");
        AppUser v = user(4, "dave", 9L, true, null);   // tur durumu yok → "none"
        when(userRepo.findAll()).thenReturn(List.of(u, v));
        Map<String, Object> o = service.getOverview();
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) o.get("login_status");
        Map<String, Object> carol = rows.stream().filter(r -> "carol".equals(r.get("username"))).findFirst().orElseThrow();
        assertThat(carol.get("email")).isEqualTo("carol@example.com");
        assertThat(carol.get("org_role")).isEqualTo("TECH");
        assertThat(carol.get("team_id")).isEqualTo(5L);
        assertThat(carol.get("team_ids")).isEqualTo(List.of(5L, 9L));
        assertThat(carol.get("employee_id")).isEqualTo("E-3");
        assertThat(carol.get("created_at")).isEqualTo("2026-01-05T10:00:00");
        assertThat(carol.get("last_seen_at")).isEqualTo("2026-09-19T08:00:00");
        assertThat(carol.get("permanent_lock")).isEqualTo(true);
        assertThat(carol.get("title")).isEqualTo("Uzman");
        assertThat(carol.get("tour_status")).isEqualTo("completed");
        assertThat(carol.get("tour_at")).isEqualTo("2026-02-01T09:00:00");
        Map<String, Object> dave = rows.stream().filter(r -> "dave".equals(r.get("username"))).findFirst().orElseThrow();
        assertThat(dave.get("tour_status")).isEqualTo("none");
        assertThat(dave.get("team_ids")).isEqualTo(List.of());
        assertThat(dave.get("permanent_lock")).isEqualTo(false);
    }

    @Test
    @DisplayName("Giriş / anomali satırları (2026-09-20): aktör → ad, rol, takım, kimlik kaynağı (büyük/küçük harf duyarsız); bilinmeyen aktörde null")
    void eventRowsCarryActorTeam() {
        Instant now = Instant.now();
        AppUser bob = user(2, "Bob", 9L, true, ISO.format(now)); bob.setDisplayName("Bob Example"); bob.setSystemRole("USER"); bob.setAuthSource("LDAP");
        when(userRepo.findAll()).thenReturn(List.of(bob));
        when(auditLogRepo.findLoginEventsSince(any(), any())).thenReturn(List.of(
                ev(1, "bob", "10.0.0.1", "SUCCESS", 9L, null, now.minusSeconds(60)),
                ev(2, "bob", "10.0.0.2", "FAILURE", 9L, "UNUSUAL_IP", now.minusSeconds(30)),
                ev(3, "ghost", "10.0.0.3", "SUCCESS", null, null, now)));
        Map<String, Object> o = service.getOverview();
        @SuppressWarnings("unchecked") Map<String, Object> details = (Map<String, Object>) o.get("details");
        @SuppressWarnings("unchecked") List<Map<String, Object>> logins = (List<Map<String, Object>>) details.get("logins");
        Map<String, Object> bobRow = logins.stream().filter(r -> "bob".equals(r.get("actor"))).findFirst().orElseThrow();
        assertThat(bobRow.get("display_name")).isEqualTo("Bob Example");
        assertThat(bobRow.get("team_name")).isEqualTo("Takım B");
        assertThat(bobRow.get("team_id")).isEqualTo(9L);
        assertThat(bobRow.get("system_role")).isEqualTo("USER");
        assertThat(bobRow.get("auth_source")).isEqualTo("LDAP");
        Map<String, Object> ghost = logins.stream().filter(r -> "ghost".equals(r.get("actor"))).findFirst().orElseThrow();
        assertThat(ghost.get("team_name")).isNull();
        assertThat(ghost.get("display_name")).isNull();
        @SuppressWarnings("unchecked") List<Map<String, Object>> recent = (List<Map<String, Object>>) ((Map<?, ?>) o.get("anomalies")).get("recent");
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).get("team_name")).isEqualTo("Takım B");
        assertThat(recent.get(0).get("user_id")).isEqualTo(2L);
    }

    private static Map<String, Object> row(String day, String user, String tab, long pings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day); m.put("username", user); m.put("tab", tab); m.put("pings", pings); m.put("first_seen", day + "T08:00:00"); m.put("last_seen", day + "T09:00:00");
        return m;
    }
}
