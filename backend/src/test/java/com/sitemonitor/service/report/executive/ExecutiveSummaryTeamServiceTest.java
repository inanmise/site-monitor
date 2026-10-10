package com.sitemonitor.service.report.executive;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.ExecutiveSummaryTeamSettings;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamReportRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamSettingsRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.InactiveRecipientGuard;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Takım yönetici özeti ayarları ve alıcıları (2026-10-10): varsayılanlar, dört alıcı kaynağı, pasif adres düşmesi,
 * takımdan ayrılan üye, uyarı kodları, kaydetme doğrulaması (yalnız aktif üye, geçerli adres), toplu çözüm (takım başına
 * sorgu yok). Sahte depolar — veritabanı yok.
 */
class ExecutiveSummaryTeamServiceTest {

    private final ExecutiveSummaryTeamSettingsRepository settingsRepo = mock(ExecutiveSummaryTeamSettingsRepository.class);
    private final ExecutiveSummaryTeamReportRepository reportRepo = mock(ExecutiveSummaryTeamReportRepository.class);
    private final TeamRepository teamRepo = mock(TeamRepository.class);
    private final AppUserRepository userRepo = mock(AppUserRepository.class);
    private final UserService userService = mock(UserService.class);
    private final InactiveRecipientGuard guard = mock(InactiveRecipientGuard.class);
    private ExecutiveSummaryTeamService svc;

    private Team team;
    private AppUser manager, ldapAdmin, otherTeamAdmin, globalLdapAdmin, member1, member2, noEmail, passive;

    @BeforeEach
    void setUp() {
        svc = new ExecutiveSummaryTeamService(settingsRepo, reportRepo, teamRepo, userRepo, userService);
        svc.setInactiveGuard(guard);
        svc.setClock(() -> Instant.parse("2026-10-10T09:00:00Z"));
        team = team(5L, "Ödeme", 100L);
        manager = user(100L, "mudur", "Müdür Bey", "mudur@example.com", "USER", "LDAP", Set.of(9L));
        ldapAdmin = user(101L, "kapsamli", "Kapsamlı Müdür", "kapsamli@example.com", "ADMIN", "LDAP", Set.of(1L));
        otherTeamAdmin = user(102L, "baska", "Başka Müdür", "baska@example.com", "ADMIN", "LDAP", Set.of(2L));
        globalLdapAdmin = user(103L, "global", "Global", "global@example.com", "ADMIN", "LDAP", Set.of());
        member1 = user(201L, "uye1", "Ayşe", "ayse@example.com", "USER", "LOCAL", Set.of(5L));
        member2 = user(202L, "uye2", "Mehmet", "mehmet@example.com", "USER", "LOCAL", Set.of(5L));
        noEmail = user(203L, "uye3", "Epostasız", null, "USER", "LOCAL", Set.of(5L));
        passive = user(204L, "uye4", "Pasif", "pasif@example.com", "USER", "LOCAL", Set.of(5L));
        passive.setActive(false);
        when(teamRepo.findById(5L)).thenReturn(Optional.of(team));
        when(teamRepo.findAllById(any())).thenReturn(List.of(team));
        when(userRepo.findMembersOfTeams(anyCollection())).thenReturn(List.of(member1, member2, noEmail, passive));
        when(userRepo.findByActiveTrueOrderByUsernameAsc())
                .thenReturn(List.of(manager, ldapAdmin, otherTeamAdmin, globalLdapAdmin, member1, member2, noEmail));
        when(userService.computeManageTeamIds(ldapAdmin)).thenReturn(List.of(1L, 5L));
        when(userService.computeManageTeamIds(otherTeamAdmin)).thenReturn(List.of(2L));
        when(userService.computeManageTeamIds(globalLdapAdmin)).thenReturn(null);
        when(settingsRepo.findById(anyLong())).thenReturn(Optional.empty());
        when(settingsRepo.findAllById(any())).thenReturn(List.of());
    }

    private static Team team(Long id, String name, Long managerId) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setManagerId(managerId);
        t.setActive(true);
        return t;
    }

    private static AppUser user(Long id, String username, String display, String email, String role, String source,
                                Set<Long> teamIds) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(display);
        u.setEmail(email);
        u.setSystemRole(role);
        u.setAuthSource(source);
        u.setActive(true);
        u.setTeamIds(new LinkedHashSet<>(teamIds));
        return u;
    }

    private void settings(boolean enabled, boolean mgr, boolean admins, String users, String extra) {
        ExecutiveSummaryTeamSettings s = new ExecutiveSummaryTeamSettings();
        s.setTeamId(5L);
        s.setEnabled(enabled);
        s.setIncludeManager(mgr);
        s.setIncludeTeamAdmins(admins);
        s.setRecipientUserIdsCsv(users);
        s.setExtraEmails(extra);
        when(settingsRepo.findById(5L)).thenReturn(Optional.of(s));
        when(settingsRepo.findAllById(any())).thenReturn(List.of(s));
    }

    @Test
    @DisplayName("satır yok → kapalı; varsayılan alıcılar: takım müdürü + takımı yöneten (AD) müdürler; global ve başka takımın müdürü YOK")
    void defaults() {
        ExecutiveSummaryTeamService.Settings s = svc.settingsOf(5L);
        assertThat(s.enabled()).isFalse();
        assertThat(s.includeManager()).isTrue();
        assertThat(s.includeTeamAdmins()).isTrue();
        ExecutiveSummaryTeamService.Recipients rc = svc.recipients(5L);
        assertThat(rc.emails()).containsExactly("mudur@example.com", "kapsamli@example.com");
        assertThat(rc.managerCount()).isEqualTo(1);
        assertThat(rc.adminCount()).isEqualTo(1);
        assertThat(rc.notes()).isEmpty();
    }

    @Test
    @DisplayName("dört kaynak birleşir, küçük harf tekil; seçilen üyeden e-postasız/pasif olan ve takımdan ayrılan düşer; pasif-only ek adres düşer")
    void sourcesUnionAndDrops() {
        settings(true, true, true, "201,203,204,999", "AYSE@example.com, cto@example.com, eski@example.com");
        when(guard.isInactiveOnlyEmail("eski@example.com")).thenReturn(true);
        ExecutiveSummaryTeamService.Recipients rc = svc.recipients(5L);
        assertThat(rc.emails()).containsExactly("mudur@example.com", "kapsamli@example.com", "ayse@example.com",
                "cto@example.com");
        assertThat(rc.memberCount()).isEqualTo(1);              // 203 e-postasız, 204 pasif, 999 takımda değil
        assertThat(rc.extraCount()).isEqualTo(1);               // AYSE zaten üyeden geldi (tekil)
        assertThat(rc.droppedInactive()).isEqualTo(1);
        assertThat(rc.notes()).contains(ExecutiveSummaryTeamService.NOTE_LEFT_MEMBERS);
    }

    @Test
    @DisplayName("uyarılar: müdür atanmamış / müdür pasif / müdürün adresi yok / takımı yöneten müdür yok")
    void notes() {
        team.setManagerId(null);
        when(userService.computeManageTeamIds(ldapAdmin)).thenReturn(List.of(1L));
        ExecutiveSummaryTeamService.Recipients rc = svc.recipients(5L);
        assertThat(rc.isEmpty()).isTrue();
        assertThat(rc.notes()).containsExactly(ExecutiveSummaryTeamService.NOTE_NO_MANAGER,
                ExecutiveSummaryTeamService.NOTE_NO_TEAM_ADMINS);

        team.setManagerId(100L);
        manager.setEmail(" ");
        assertThat(svc.recipients(5L).notes()).contains(ExecutiveSummaryTeamService.NOTE_MANAGER_NO_EMAIL);
        manager.setActive(false);
        assertThat(svc.recipients(5L).notes()).contains(ExecutiveSummaryTeamService.NOTE_MANAGER_INACTIVE);
    }

    @Test
    @DisplayName("kaydetme: yalnız gövdedeki alanlar; değişen alanlar döner; seçilen kimlikler CSV saklanır; damga + aktör yazılır")
    void saveWritesOnlyBodyFields() {
        Set<String> changed = svc.save(5L, Map.of("enabled", true, "extra_emails", "cto@example.com; cfo@example.com"),
                List.of(202L, 201L, 202L), 0, "yonetici");
        assertThat(changed).containsExactly("enabled", "extra_emails", "user_ids");
        ArgumentCaptor<ExecutiveSummaryTeamSettings> cap = ArgumentCaptor.forClass(ExecutiveSummaryTeamSettings.class);
        verify(settingsRepo).save(cap.capture());
        ExecutiveSummaryTeamSettings row = cap.getValue();
        assertThat(row.getEnabled()).isTrue();
        assertThat(row.getIncludeManager()).isTrue();          // gövdede yok → varsayılan korunur
        assertThat(row.getRecipientUserIdsCsv()).isEqualTo("202,201");
        assertThat(row.getExtraEmails()).isEqualTo("cto@example.com, cfo@example.com");
        assertThat(row.getUpdatedBy()).isEqualTo("yonetici");
        assertThat(row.getUpdatedAt()).isEqualTo("2026-10-10T09:00:00");
    }

    @Test
    @DisplayName("kaydetme doğrulaması: takımda olmayan / pasif üye, çözülemeyen kimlik, geçersiz adres → 400 alanıyla; hiçbir şey yazılmaz")
    void saveValidation() {
        assertThatThrownBy(() -> svc.save(5L, Map.of(), List.of(101L), 0, "y"))
                .isInstanceOf(FieldValidationException.class).hasMessageContaining("aktif üyeleri");
        assertThatThrownBy(() -> svc.save(5L, Map.of(), List.of(204L), 0, "y"))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> svc.save(5L, Map.of(), List.of(), 2, "y"))
                .isInstanceOf(FieldValidationException.class).hasMessageContaining("2");
        assertThatThrownBy(() -> svc.save(5L, Map.of("extra_emails", "iyi@example.com, kotu-adres"), null, 0, "y"))
                .isInstanceOf(FieldValidationException.class).hasMessageContaining("kotu-adres");
        verify(settingsRepo, never()).save(any());
    }

    @Test
    @DisplayName("değişiklik yoksa satır yazılmaz (damga değişmez)")
    void noChangeNoWrite() {
        settings(false, true, true, null, null);
        Set<String> changed = svc.save(5L, Map.of("enabled", false, "include_manager", true), null, 0, "y");
        assertThat(changed).isEmpty();
        verify(settingsRepo, never()).save(any());
    }

    @Test
    @DisplayName("ayar ekranı: müdür, yöneten müdürler, seçilebilir AKTİF üyeler (A→Z, seçili işaretli), önizleme, sayılar; kişi user_id anahtarıyla")
    void detail() {
        settings(true, true, true, "202", "cto@example.com");
        Map<String, Object> d = svc.detail(5L);
        assertThat(d).containsEntry("team_id", 5L).containsEntry("team_name", "Ödeme").containsEntry("enabled", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> mgr = (Map<String, Object>) d.get("manager");
        assertThat(mgr).containsEntry("user_id", 100L).containsEntry("email", "mudur@example.com");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) d.get("members");
        assertThat(members).extracting(m -> m.get("name")).containsExactly("Ayşe", "Epostasız", "Mehmet");
        assertThat(members).filteredOn(m -> Boolean.TRUE.equals(m.get("selected"))).extracting(m -> m.get("user_id"))
                .containsExactly(202L);
        assertThat(d.get("recipient_count")).isEqualTo(4);
        assertThat(d).containsKeys("recipient_preview", "recipient_counts", "notes", "history", "team_admins");
    }

    @Test
    @DisplayName("toplu çözüm (zamanlanmış koşu): N takım için üyeler ve müdürler BİR kez okunur; pasif takım sonuçta yok")
    void bulkResolveReadsOnce() {
        Team b = team(6L, "Ağ", null);
        Team off = team(7L, "Pasif takım", null);
        off.setActive(false);
        when(teamRepo.findAllById(any())).thenReturn(new ArrayList<>(List.of(team, b, off)));
        Map<Long, ExecutiveSummaryTeamService.Recipients> out = svc.recipientsFor(List.of(5L, 6L, 7L));
        assertThat(out).containsOnlyKeys(5L, 6L);
        verify(userRepo, org.mockito.Mockito.times(1)).findMembersOfTeams(anyCollection());
        verify(userRepo, org.mockito.Mockito.times(1)).findByActiveTrueOrderByUsernameAsc();
    }
}
