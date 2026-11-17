package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kullanıcı etkinliği dar satırı (2026-11-17 perf): Sistem Sağlığı 30 sn'de bir okur; servis fotoğraf dahil tam kullanıcı
 * varlıkları yerine {@code findActivityRows} satırından GEÇİCİ kullanıcı nesneleri kurar. Kapılar: (1) servisin okuduğu her
 * kullanıcı alanı satırda taşınır (yeni bir alan okunursa burası kırmızı — satıra eklenmeli), (2) satır → nesne dönüşümü
 * kayıpsız, (3) uç tam varlık okumaz.
 */
class UserActivityProjectionGateTest {

    /** {@code UserActivityService.activityUser} ile doldurulan alanlar (getter adları). */
    private static final Set<String> PROJECTED = Set.of(
            "Id", "Username", "DisplayName", "FirstName", "LastName", "Email", "Active", "TeamId", "SystemRole", "OrgRole",
            "AuthSource", "CreatedAt", "LastLoginAt", "LastLoginIp", "LastLoginMethod", "LastSeenAt", "PrevLoginAt", "PrevLoginIp",
            "FailedBeforeLogin", "FailedSinceLogin", "LastFailedLoginAt", "LastFailedLoginIp", "LastFailedLoginReason",
            "PermanentLock", "Department", "EmployeeId", "Title", "TourState", "ActiveSessionId", "PhotoBase64", "TeamIds");

    private static Path source() {
        Path p = Path.of("src/main/java/com/sitemonitor/service/UserActivityService.java");
        return Files.exists(p) ? p : Path.of("backend").resolve(p);
    }

    @Test
    @DisplayName("kapı: servisin kullanıcı nesnesinden okuduğu her alan dar satırda var (yoksa geçici nesnede null kalırdı)")
    void everyReadFieldIsProjected() throws IOException {
        String src = Files.readString(source(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\b(?:u|au)\\.get([A-Z][A-Za-z0-9]*)\\(\\)").matcher(src);
        Set<String> used = new TreeSet<>();
        while (m.find()) used.add(m.group(1));
        assertThat(used).isNotEmpty();
        assertThat(PROJECTED).containsAll(used);
        // TourStateService.summarize da geçici nesneleri okur — yalnız active + tourState
        String tour = Files.readString(source().resolveSibling("TourStateService.java"), StandardCharsets.UTF_8);
        int from = tour.indexOf("public static Map<String, Integer> summarize(");
        Matcher t = Pattern.compile("\\bu\\.get([A-Z][A-Za-z0-9]*)\\(\\)").matcher(tour.substring(from, tour.indexOf("\n    }\n", from)));
        while (t.find()) assertThat(PROJECTED).contains(t.group(1));
    }

    @Test
    @DisplayName("satır → geçici kullanıcı kayıpsız (fotoğraf yerine VAR işareti; ek takımlar HashSet)")
    void rowToUserIsLossless() {
        AppUser u = new AppUser();
        u.setId(7L); u.setUsername("N7"); u.setDisplayName("Ayşe Yılmaz (Ödeme / Uzman)"); u.setFirstName("Ayşe"); u.setLastName("Yılmaz");
        u.setEmail("ayse@example.com"); u.setActive(false); u.setTeamId(5L); u.setSystemRole("TEAM_ADMIN"); u.setOrgRole("PO");
        u.setAuthSource("LDAP"); u.setCreatedAt("2026-01-01T00:00:00"); u.setLastLoginAt("2026-11-01T10:00:00");
        u.setLastLoginIp("192.0.2.1"); u.setLastLoginMethod("LDAP"); u.setLastSeenAt("2026-11-01T10:05:00");
        u.setPrevLoginAt("2026-10-30T09:00:00"); u.setPrevLoginIp("192.0.2.2"); u.setFailedBeforeLogin(2); u.setFailedSinceLogin(1);
        u.setLastFailedLoginAt("2026-11-01T09:59:00"); u.setLastFailedLoginIp("192.0.2.3"); u.setLastFailedLoginReason("BAD_PASSWORD");
        u.setPermanentLock(true); u.setDepartment("Ödeme"); u.setEmployeeId("7007"); u.setTitle("Uzman");
        u.setTourState("{\"status\":\"completed\"}"); u.setActiveSessionId("sess-7"); u.setPhotoBase64("iVBORw0KGgo=");
        u.setTeamIds(new LinkedHashSet<>(List.of(5L, 9L)));

        Map<Long, Set<Long>> extra = new HashMap<>();
        for (Object[] p : UserActivityRows.pairsOf(List.of(u))) extra.computeIfAbsent((Long) p[0], k -> new java.util.HashSet<>()).add((Long) p[1]);
        AppUser c = UserActivityService.activityUser(UserActivityRows.rowsOf(List.of(u)).get(0), extra);

        assertThat(c).usingRecursiveComparison()
                .comparingOnlyFields("id", "username", "displayName", "firstName", "lastName", "email", "active", "teamId",
                        "systemRole", "orgRole", "authSource", "createdAt", "lastLoginAt", "lastLoginIp", "lastLoginMethod",
                        "lastSeenAt", "prevLoginAt", "prevLoginIp", "failedBeforeLogin", "failedSinceLogin", "lastFailedLoginAt",
                        "lastFailedLoginIp", "lastFailedLoginReason", "permanentLock", "department", "employeeId", "title",
                        "tourState", "activeSessionId")
                .isEqualTo(u);
        assertThat(c.getTeamIds()).containsExactlyInAnyOrder(5L, 9L);
        assertThat(c.getPhotoBase64()).isNotBlank();

        u.setPhotoBase64(null);
        u.setActive(null);
        u.setFailedSinceLogin(null);
        AppUser noPhoto = UserActivityService.activityUser(UserActivityRows.rowsOf(List.of(u)).get(0), Map.of());
        assertThat(noPhoto.getPhotoBase64()).isNull();
        assertThat(noPhoto.getActive()).as("null değer null kalır (varsayılan true değil)").isNull();
        assertThat(noPhoto.getFailedSinceLogin()).isNull();
        assertThat(noPhoto.getTeamIds()).isEmpty();
    }

    @Test
    @DisplayName("uç tam kullanıcı varlıklarını (fotoğraf dahil) okumaz; has_photo ve ek takımlar dar satırdan")
    @SuppressWarnings("unchecked")
    void overviewDoesNotLoadEntities() {
        AuditLogRepository audit = mock(AuditLogRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        AppSettingsService settings = mock(AppSettingsService.class);
        PageUsageService usage = mock(PageUsageService.class);
        when(settings.getInt(eq("site.monitor.ui.inactivity-minutes"), anyInt())).thenReturn(30);
        when(usage.rowsSince(anyInt())).thenReturn(List.of());
        when(audit.findLoginEventsSince(any(), any())).thenReturn(List.of());
        when(teams.findAll()).thenReturn(List.of());
        when(users.findAll()).thenThrow(new AssertionError("tam varlık okunmamalı"));
        AppUser a = new AppUser(); a.setId(1L); a.setUsername("N1"); a.setActive(true); a.setTeamId(5L); a.setPhotoBase64("iVBOR");
        a.setTeamIds(new LinkedHashSet<>(List.of(5L, 9L)));
        AppUser b = new AppUser(); b.setId(2L); b.setUsername("N2"); b.setActive(true); b.setTeamId(9L);
        when(users.findActivityRows()).thenReturn(UserActivityRows.rowsOf(List.of(a, b)));
        when(users.findAllTeamMembershipPairs()).thenReturn(UserActivityRows.pairsOf(List.of(a, b)));
        UserActivityService svc = new UserActivityService(audit, users, teams, mock(UserService.class), usage,
                mock(org.springframework.jdbc.core.JdbcTemplate.class), settings);

        Map<String, Object> o = svc.getOverview();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) o.get("login_status");
        Map<String, Object> n1 = rows.stream().filter(r -> "N1".equals(r.get("username"))).findFirst().orElseThrow();
        Map<String, Object> n2 = rows.stream().filter(r -> "N2".equals(r.get("username"))).findFirst().orElseThrow();
        assertThat(n1).containsEntry("has_photo", true);
        assertThat((List<Long>) n1.get("team_ids")).containsExactlyInAnyOrder(5L, 9L);
        assertThat(n2).containsEntry("has_photo", false);
        assertThat((List<Long>) n2.get("team_ids")).isEmpty();
        verify(users, never()).findAll();
    }
}
